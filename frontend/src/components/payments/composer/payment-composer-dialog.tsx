"use client";

import { useEffect, useMemo, useRef, useState, type KeyboardEvent } from "react";
import { useRouter } from "next/navigation";
import { useQueryClient } from "@tanstack/react-query";
import { Dialog as DialogPrimitive } from "@base-ui/react/dialog";
import { CreditCard, XIcon } from "lucide-react";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { randomUuid } from "@/lib/uuid";
import { useInitiatePayment } from "@/lib/api/payments";
import { hasBillingAddress, useOwnBillingAddress } from "@/lib/api/students";
import {
  klassStudentKeys,
  useCheckoutCourses,
  useCheckoutQuote,
  useCreateComposerOrder,
  useUploadComposerSlip,
} from "@/lib/api/klass-student";
import { isApiClientError } from "@/lib/api/error";
import { isAllowedGatewayAction, submitGatewayForm } from "@/lib/payments/submit-gateway-form";
import {
  COMPOSER_TABS,
  EMPTY_CART,
  addCartItem,
  cartCourseIds,
  cartItemCount,
  composerErrorMessage,
  isDeliveryDetailsError,
  isStaleSelectionError,
  quoteFromQuoteChanged,
  removeCartItem,
  scopeForTab,
  setCartDiscountCode,
  setCartPostage,
  setRewardAccepted,
  toQuoteRequest,
  toggleCartItem,
  withTimeout,
  type ComposerCart,
  type ComposerPreset,
  type ComposerTab,
  type SelectedItem,
} from "./composer-model";
import { ComposerOffersPanel } from "./composer-offers-panel";
import { ComposerSummaryPanel, SlipSubmittedPanel, type FlowState, type PaymentMethod } from "./composer-summary-panel";

/** A single network step may take this long before the student gets an actionable retry. */
const STEP_TIMEOUT_MS = 30_000;
const TIMEOUT_MESSAGE =
  "This is taking longer than expected. Your selection is safe — try again (you won't be charged twice).";
const QUOTE_MISSING_MESSAGE = "Your total is still being calculated. Wait a moment, then try again.";

/**
 * "Make Payment" composer (EP-M01..M05, EP-P01..P05, EP-S01..S02, CM-P01..P04).
 * One dialog for every scope; a multi-item cart that may span several
 * courses (switching course keeps the cart) with per-course postage; every
 * amount comes from the server quote; the order is created server-side from
 * item identifiers + the latest quote token (client totals are never sent). Online payment hands the
 * backend-signed fields to the gateway; slip payment uploads evidence for
 * review. Neither unlocks anything here: lesson access changes only after the
 * backend confirms the payment or a reviewer approves the slip (ADR-028).
 */
export function PaymentComposerDialog({
  open,
  onOpenChange,
  preset,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  preset: ComposerPreset | null;
}) {
  return (
    <DialogPrimitive.Root open={open} onOpenChange={(next) => onOpenChange(next)}>
      <DialogPrimitive.Portal>
        <DialogPrimitive.Backdrop className="fixed inset-0 z-50 bg-black/40 transition-opacity duration-150 data-ending-style:opacity-0 data-starting-style:opacity-0" />
        <DialogPrimitive.Popup
          data-testid="payment-composer"
          className="fixed inset-0 z-50 flex flex-col bg-popover text-sm text-popover-foreground shadow-lg outline-none transition duration-150 data-ending-style:opacity-0 data-starting-style:opacity-0 sm:inset-auto sm:top-1/2 sm:left-1/2 sm:max-h-[calc(100dvh-2rem)] sm:w-[calc(100%-2rem)] sm:max-w-5xl sm:-translate-x-1/2 sm:-translate-y-1/2 sm:rounded-xl sm:border sm:border-border"
        >
          <div className="flex items-start justify-between gap-3 border-b border-border px-4 py-3">
            <div className="flex items-start gap-3">
              <span className="mt-0.5 inline-flex size-9 items-center justify-center rounded-lg bg-blue-50 text-blue-700 dark:bg-blue-950 dark:text-blue-200">
                <CreditCard className="size-5" aria-hidden="true" />
              </span>
              <div>
                <DialogPrimitive.Title className="text-base font-semibold text-foreground">Make Payment</DialogPrimitive.Title>
                <DialogPrimitive.Description className="text-sm text-muted-foreground">
                  Select what you want to pay for and choose how to pay
                </DialogPrimitive.Description>
              </div>
            </div>
            <DialogPrimitive.Close
              render={<Button type="button" variant="ghost" size="icon-sm" aria-label="Close" data-testid="composer-close" />}
            >
              <XIcon aria-hidden="true" />
            </DialogPrimitive.Close>
          </div>
          {open ? <ComposerBody preset={preset} onClose={() => onOpenChange(false)} /> : null}
        </DialogPrimitive.Popup>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  );
}

function ComposerBody({ preset, onClose }: { preset: ComposerPreset | null; onClose: () => void }) {
  const router = useRouter();
  const queryClient = useQueryClient();

  const [tab, setTab] = useState<ComposerTab>(preset?.tab ?? "MONTHLY");
  const [course, setCourse] = useState<{ id: string; title: string | null } | null>(
    preset ? { id: preset.courseId, title: preset.courseTitle ?? null } : null
  );
  // The cart spans courses: switching course never clears it.
  const [cart, setCart] = useState<ComposerCart>(() =>
    preset?.item ? addCartItem(EMPTY_CART, preset.item) : EMPTY_CART
  );
  const [monthlyYear, setMonthlyYear] = useState<number | null>(
    preset?.item?.request.scope === "MONTHLY" ? (preset.item.request.year ?? null) : null
  );
  const [method, setMethod] = useState<PaymentMethod | null>(null);
  const [flow, setFlow] = useState<FlowState>({ phase: "idle" });
  const [submitted, setSubmitted] = useState(false);
  const [priceChanged, setPriceChanged] = useState(false);
  // One idempotency key pair per (composer session, cart): a retry of the
  // same cart replays the same order/payment server-side; a different cart
  // (items or postage) is a different order.
  const [keys, setKeys] = useState(() => ({ order: randomUuid(), payment: randomUuid() }));
  const slipOrderId = useRef<string | null>(null);

  const quoteRequest = useMemo(() => toQuoteRequest(cart), [cart]);
  const selectedKeys = useMemo(() => new Set(cart.items.map((item) => item.key)), [cart]);
  const quoteQuery = useCheckoutQuote(quoteRequest);
  // Shares the offers panel's cache; names a preset course whose title wasn't passed in.
  const tabCourses = useCheckoutCourses(scopeForTab(tab));
  const courseTitle =
    course?.title ?? tabCourses.data?.find((entry) => entry.courseId === course?.id)?.title ?? null;
  const createOrder = useCreateComposerOrder();
  const initiatePayment = useInitiatePayment();
  const uploadSlip = useUploadComposerSlip();
  const billingAddressQuery = useOwnBillingAddress({ enabled: method === "online" });
  const billingAddressMissing =
    method === "online" && billingAddressQuery.isSuccess && !hasBillingAddress(billingAddressQuery.data);

  const quoteError = quoteQuery.isError ? quoteQuery.error : null;
  // A stale selection (already paid / pending / not eligible) means our offer
  // states are out of date - re-read them so the grid shows the truth.
  useEffect(() => {
    if (quoteError && isStaleSelectionError(quoteError)) {
      queryClient.invalidateQueries({ queryKey: klassStudentKeys.checkoutAll(), refetchType: "active" });
    }
  }, [quoteError, queryClient]);

  const resetForNewCart = () => {
    setKeys({ order: randomUuid(), payment: randomUuid() });
    slipOrderId.current = null;
    setFlow({ phase: "idle" });
    setPriceChanged(false);
  };

  const changeCart = (next: ComposerCart) => {
    if (next === cart) return;
    setCart(next);
    resetForNewCart();
  };

  const toggleItem = (item: SelectedItem) => {
    if (flow.phase === "working") return;
    changeCart(toggleCartItem(cart, item));
  };

  const removeItem = (key: string) => {
    if (flow.phase === "working") return;
    changeCart(removeCartItem(cart, key));
  };

  const togglePostage = (courseId: string, selected: boolean) => {
    if (flow.phase === "working") return;
    changeCart(setCartPostage(cart, courseId, selected));
  };

  // DISC-01: a code or an accepted free-course offer is part of the cart, so changing
  // one re-quotes and starts a new order attempt (new idempotency keys).
  const discountControls = {
    rateLimited: isApiClientError(quoteError) && quoteError.code === "DISCOUNT_CODE_RATE_LIMITED",
    onApplyCode: (code: string) => {
      if (flow.phase === "working") return;
      changeCart(setCartDiscountCode(cart, code));
    },
    onRemoveCode: () => {
      if (flow.phase === "working") return;
      changeCart(setCartDiscountCode(cart, null));
    },
    onAcceptReward: (ruleId: string) => {
      if (flow.phase === "working") return;
      changeCart(setRewardAccepted(cart, ruleId, true));
    },
    onDeclineReward: (ruleId: string) => {
      if (flow.phase === "working") return;
      changeCart(setRewardAccepted(cart, ruleId, false));
    },
  };

  const selectCourse = (id: string, title: string) => {
    if (course?.id !== id) setMonthlyYear(null);
    setCourse({ id, title });
  };

  const requote = () => {
    queryClient.invalidateQueries({ queryKey: klassStudentKeys.quoteAll() });
  };

  const invalidateAfterSubmit = () => {
    queryClient.invalidateQueries({ queryKey: klassStudentKeys.all });
  };

  /**
   * Creates the order from the latest quote's token. A 409 QUOTE_CHANGED
   * shows the server's fresh quote and stops: the student reviews the new
   * total and confirms again (never auto-submitted).
   */
  const createOrderFromQuote = async () => {
    const quoteToken = quoteQuery.data?.quoteToken;
    if (!quoteToken) throw new Error(QUOTE_MISSING_MESSAGE);
    try {
      return await withTimeout(
        createOrder.mutateAsync({ ...quoteRequest, quoteToken, idempotencyKey: keys.order }),
        STEP_TIMEOUT_MS,
        TIMEOUT_MESSAGE
      );
    } catch (error) {
      const fresh = quoteFromQuoteChanged(error);
      if (fresh) {
        queryClient.setQueryData(klassStudentKeys.quote(quoteRequest), fresh);
        // No order was created; the next confirmation is a new attempt.
        setKeys({ order: randomUuid(), payment: randomUuid() });
        setPriceChanged(true);
      } else if (isApiClientError(error) && error.code === "QUOTE_REQUIRED") {
        requote();
      }
      throw error;
    }
  };

  const failureMessage = (error: unknown) =>
    error instanceof Error && (error.message === TIMEOUT_MESSAGE || error.message === QUOTE_MISSING_MESSAGE)
      ? error.message
      : composerErrorMessage(error);

  const payOnline = async () => {
    if (flow.phase === "working" || cart.items.length === 0) return;
    setPriceChanged(false);
    try {
      setFlow({ phase: "working", method: "online", step: "Creating your order…" });
      const order = await createOrderFromQuote();
      setFlow({ phase: "working", method: "online", step: "Opening the payment page…" });
      const initiation = await withTimeout(
        initiatePayment.mutateAsync({ orderId: order.id, idempotencyKey: keys.payment }),
        STEP_TIMEOUT_MS,
        TIMEOUT_MESSAGE
      );
      invalidateAfterSubmit();
      if (initiation.formAction && initiation.formFields) {
        if (!isAllowedGatewayAction(initiation.formAction)) {
          setFlow({
            phase: "error",
            method: "online",
            message: "We couldn't open the secure payment page. Try again, or upload a payment slip instead.",
          });
          return;
        }
        // Leaves the app for the gateway; the return page only re-reads status.
        submitGatewayForm(initiation.formAction, initiation.formFields);
        return;
      }
      onClose();
      router.push(`/student/payments/awaiting-confirmation/${order.id}`);
    } catch (error) {
      setFlow({ phase: "error", method: "online", message: failureMessage(error) });
    }
  };

  const submitSlip = async ({ referenceNumber, file }: { referenceNumber: string; file: File }) => {
    if (flow.phase === "working" || cart.items.length === 0) return;
    setPriceChanged(false);
    try {
      let orderId = slipOrderId.current;
      if (!orderId) {
        setFlow({ phase: "working", method: "slip", step: "Creating your order…" });
        const order = await createOrderFromQuote();
        orderId = order.id;
        slipOrderId.current = order.id;
      }
      setFlow({ phase: "working", method: "slip", step: "Uploading your payment slip…" });
      await withTimeout(uploadSlip.mutateAsync({ orderId, referenceNumber, file }), 2 * STEP_TIMEOUT_MS, TIMEOUT_MESSAGE);
      setFlow({ phase: "idle" });
      setSubmitted(true);
      invalidateAfterSubmit();
    } catch (error) {
      setFlow({ phase: "error", method: "slip", message: failureMessage(error) });
    }
  };

  const startAnother = () => {
    setSubmitted(false);
    setMethod(null);
    changeCart(EMPTY_CART);
  };

  if (submitted) {
    return (
      <div className="min-h-0 flex-1 overflow-y-auto">
        <SlipSubmittedPanel onClose={onClose} onAnother={startAnother} />
      </div>
    );
  }

  const quoteStatus: "idle" | "pending" | "error" | "success" =
    cart.items.length === 0 ? "idle" : quoteQuery.isError ? "error" : quoteQuery.isSuccess ? "success" : "pending";

  return (
    <div className="flex min-h-0 flex-1 flex-col overflow-y-auto lg:flex-row lg:overflow-hidden">
      <div className="flex flex-col gap-4 border-border p-4 lg:w-2/3 lg:overflow-y-auto lg:border-r">
        <ComposerTabs tab={tab} onChange={setTab} />
        {cartCourseIds(cart).length > 1 ? (
          <p className="text-xs text-muted-foreground" role="status" data-testid="composer-multi-course-note">
            Your cart has items from {cartCourseIds(cart).length} courses. They are paid together in one payment.
          </p>
        ) : null}
        <div role="tabpanel" id="composer-tabpanel" aria-labelledby={`composer-tab-${tab}`}>
          <ComposerOffersPanel
            tab={tab}
            courseId={course?.id ?? null}
            courseTitle={courseTitle}
            monthlyYear={monthlyYear}
            selectedKeys={selectedKeys}
            onSelectCourse={selectCourse}
            onYearChange={setMonthlyYear}
            onToggleItem={toggleItem}
            onCloseForCheckout={onClose}
            cartCountFor={(courseId) => cartItemCount(cart, courseId)}
          />
        </div>
      </div>
      <div className="flex flex-col gap-4 border-t border-border p-4 lg:w-1/3 lg:overflow-y-auto lg:border-t-0">
        <ComposerSummaryPanel
          cart={cart}
          currency={quoteQuery.data?.currency ?? "LKR"}
          quote={quoteQuery.data}
          quoteStatus={quoteStatus}
          quoteError={quoteError ? composerErrorMessage(quoteError) : null}
          deliveryMissing={quoteError !== null && isDeliveryDetailsError(quoteError)}
          priceChanged={priceChanged}
          onRetryQuote={() => quoteQuery.refetch()}
          onRemove={removeItem}
          onTogglePostage={togglePostage}
          onAddressSaved={requote}
          method={method}
          onMethodChange={(next) => {
            setMethod(next);
            if (flow.phase === "error") setFlow({ phase: "idle" });
          }}
          flow={flow}
          onPayOnline={payOnline}
          onSubmitSlip={submitSlip}
          onCancel={onClose}
          billingAddressMissing={billingAddressMissing}
          discount={discountControls}
        />
      </div>
    </div>
  );
}

function ComposerTabs({ tab, onChange }: { tab: ComposerTab; onChange: (tab: ComposerTab) => void }) {
  const refs = useRef<Record<string, HTMLButtonElement | null>>({});

  const onKeyDown = (event: KeyboardEvent<HTMLButtonElement>, index: number) => {
    if (!["ArrowRight", "ArrowLeft", "Home", "End"].includes(event.key)) return;
    event.preventDefault();
    let next = index;
    if (event.key === "ArrowRight") next = (index + 1) % COMPOSER_TABS.length;
    if (event.key === "ArrowLeft") next = (index - 1 + COMPOSER_TABS.length) % COMPOSER_TABS.length;
    if (event.key === "Home") next = 0;
    if (event.key === "End") next = COMPOSER_TABS.length - 1;
    const value = COMPOSER_TABS[next];
    onChange(value);
    refs.current[value]?.focus();
  };

  return (
    <div role="tablist" aria-label="Payment type" className="flex flex-nowrap gap-1 overflow-x-auto border-b border-border">
      {COMPOSER_TABS.map((value, index) => {
        const selected = value === tab;
        return (
          <button
            key={value}
            ref={(el) => {
              refs.current[value] = el;
            }}
            type="button"
            role="tab"
            id={`composer-tab-${value}`}
            aria-selected={selected}
            aria-controls="composer-tabpanel"
            tabIndex={selected ? 0 : -1}
            onClick={() => onChange(value)}
            onKeyDown={(event) => onKeyDown(event, index)}
            data-testid={`composer-tab-${value}`}
            className={cn(
              "shrink-0 border-b-2 px-3 py-2 text-xs font-semibold tracking-wide outline-none transition-colors focus-visible:ring-3 focus-visible:ring-ring/50",
              selected
                ? "border-blue-600 text-blue-700 dark:text-blue-300"
                : "border-transparent text-muted-foreground hover:text-foreground"
            )}
          >
            {value}
          </button>
        );
      })}
    </div>
  );
}
