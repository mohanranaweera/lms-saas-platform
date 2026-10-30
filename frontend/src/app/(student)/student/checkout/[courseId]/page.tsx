"use client";

import { useState } from "react";
import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { AlertCircle } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { LiveRegion } from "@/components/ui/live-region";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { useCourse } from "@/lib/api/courses";
import { useCreateOrder, useInitiatePayment } from "@/lib/api/payments";
import { isApiClientError } from "@/lib/api/error";
import {
  CoursePriceText,
  CoursePricingNotice,
  isCourseCheckoutAvailable,
} from "@/components/courses/course-price-display";
import { randomUuid } from "@/lib/uuid";
import { useCheckoutPaymentOptions } from "@/lib/api/payment-gateway";
import { hasBillingAddress, useOwnBillingAddress } from "@/lib/api/students";
import { isAllowedGatewayAction, submitGatewayForm } from "@/lib/payments/submit-gateway-form";

/**
 * Checkout entry point (PAY-1/PAY-2). Shows the course's server-computed
 * name/price/access as read-only display only — there is no editable price
 * field anywhere on this screen, structurally: pricing is only ever rendered
 * via `CoursePriceText`/`getCoursePriceLabel` (see
 * `components/courses/course-price-display.tsx`), never bound to a form
 * input, and `OrderCreateRequest`/`useCreateOrder` only ever sends
 * `{ courseId }` (see `lib/api/payments.ts` — the type has no `price`/
 * `amount` field to even populate).
 *
 * A `CUSTOM`-priced course (`requiresManualQuote: true`) has no self-serve
 * checkout at all — the backend always rejects order creation for it with a
 * 409 since only an authorized staff actor may supply a custom amount, so
 * this page never renders the Enroll/Pay-by-bank-transfer form for one;
 * see the `checkoutAvailable` gate below. A `MONTHLY`/`SESSION` course with
 * no configured billing period yet (`resolvedAmount === null`) is gated the
 * same way rather than letting checkout attempt and fail.
 *
 * "Enroll" creates the order, then immediately initiates a gateway payment
 * attempt for it, then redirects to the awaiting-confirmation screen for
 * that order id. It never redirects to `PaymentInitiationResponse
 * .redirectTarget` itself and never marks anything "paid" here — that is
 * exclusively the awaiting-confirmation screen's job, reading only the
 * polled `GET /api/v1/orders/{id}/payment-status` response.
 *
 * "Pay by bank transfer" is a second, distinct path (MVP-011): it creates
 * the same kind of `Order` via the same `useCreateOrder` hook (no gateway
 * call at all) and redirects to the Payment Slip Upload screen for that
 * order id instead. Enrollment is never activated from here either way —
 * only a backend-confirmed payment or an approved manual slip can do that.
 * `mode` tracks which action is in flight so clicking one button never
 * visually spins the other, and both are disabled while either is pending.
 *
 * PAY-GW-01 (ADR-017): for a paid course the online button ("Pay online")
 * is only offered when `GET /api/v1/checkout/payment-options` reports the
 * institute's own PayHere account as active, and only enabled once the
 * student has a billing address (PayHere requires it; the backend re-checks
 * and refuses before creating any payment). When initiation returns
 * `formAction`/`formFields`, the backend-signed fields are posted to PayHere
 * unchanged (`submitGatewayForm`, PayHere origins only); PayHere later
 * returns the student to awaiting-confirmation, which still only trusts the
 * polled backend status. A FREE course keeps the single "Enroll" action.
 * Both helper reads degrade safely: if either fails, the online button is
 * simply not offered and bank transfer remains.
 *
 * Wave 6 §3.3/§4/§5: every order-creation and payment-initiation call sends
 * a client-generated `idempotencyKey` (UUID), so a double-click or a
 * network-retry of the *same* submit action replays the original request
 * server-side instead of creating a second order/payment. Both keys are
 * created once via `useState`'s lazy initializer (stable across re-renders
 * for the lifetime of this page mount, i.e. one "checkout session" per plan
 * §5) and regenerated only when a genuinely new attempt starts (`handle*`'s
 * `finally`-less catch path does NOT regenerate the key — a retry after a
 * failure must reuse it; only the reset of the whole in-flight `mode` lets
 * the same key be resent unchanged on the next click).
 */
export default function CheckoutPage() {
  const params = useParams<{ courseId: string }>();
  const courseId = params.courseId;
  const router = useRouter();

  const courseQuery = useCourse(courseId);
  const paymentOptionsQuery = useCheckoutPaymentOptions();
  const onlinePaymentAvailable = paymentOptionsQuery.data?.onlinePayment != null;
  // SET-PAY-01: the backend reports whether the institute accepts bank transfer
  // (and rejects a slip upload when it doesn't); unknown = offer it as before.
  const bankSlipAvailable = paymentOptionsQuery.data?.bankSlip !== false;
  const billingAddressQuery = useOwnBillingAddress({ enabled: onlinePaymentAvailable });
  const billingAddressReady = hasBillingAddress(billingAddressQuery.data);
  const createOrder = useCreateOrder();
  const initiatePayment = useInitiatePayment();
  const [flowError, setFlowError] = useState<string | null>(null);
  const [mode, setMode] = useState<"gateway" | "slip" | null>(null);
  const [orderIdempotencyKey] = useState<string>(() => randomUuid());
  const [paymentIdempotencyKey] = useState<string>(() => randomUuid());

  const isSubmitting = mode !== null;

  async function handleEnroll() {
    if (isSubmitting) return;
    setFlowError(null);
    setMode("gateway");
    try {
      const order = await createOrder.mutateAsync({
        courseId,
        idempotencyKey: orderIdempotencyKey,
      });
      const initiation = await initiatePayment.mutateAsync({
        orderId: order.id,
        idempotencyKey: paymentIdempotencyKey,
      });
      if (initiation.formAction && initiation.formFields) {
        if (!isAllowedGatewayAction(initiation.formAction)) {
          // Never post the student's details anywhere but PayHere.
          setFlowError(
            "We couldn't open the secure payment page. Please try again or pay by bank transfer."
          );
          setMode(null);
          return;
        }
        // Leaves the app for PayHere; `mode` stays set so nothing re-submits.
        submitGatewayForm(initiation.formAction, initiation.formFields);
        return;
      }
      router.push(`/student/payments/awaiting-confirmation/${order.id}`);
    } catch (error) {
      setFlowError(
        isApiClientError(error) ? error.message : "Something went wrong. Please try again."
      );
      setMode(null);
    }
  }

  async function handlePayBySlip() {
    if (isSubmitting) return;
    setFlowError(null);
    setMode("slip");
    try {
      const order = await createOrder.mutateAsync({
        courseId,
        idempotencyKey: orderIdempotencyKey,
      });
      router.push(`/student/payments/slip-upload/${order.id}`);
    } catch (error) {
      setFlowError(
        isApiClientError(error) ? error.message : "Something went wrong. Please try again."
      );
      setMode(null);
    }
  }

  return (
    <div className="mx-auto flex w-full max-w-xl flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold text-foreground">Checkout</h1>
        <p className="text-sm text-muted-foreground">
          Review the course details below, then continue to payment.
        </p>
      </div>

      <QueryStateBoundary
        query={courseQuery}
        loadingLabel="Loading course…"
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/student/dashboard" }}
      >
        {(course) => {
          const checkoutAvailable = isCourseCheckoutAvailable(course) && course.status === "PUBLIC";
          const isFree = course.pricingModel === "FREE";
          const showOnlineButton = isFree || onlinePaymentAvailable;
          const needsAddress = !isFree && onlinePaymentAvailable && !billingAddressReady;

          return (
            <div className="flex flex-col gap-4 rounded-lg border border-border p-4">
              <div>
                <h2 className="text-lg font-medium text-foreground">{course.name}</h2>
                <p className="text-sm text-muted-foreground">{course.category}</p>
              </div>

              <dl className="grid grid-cols-2 gap-4 rounded-lg border border-border p-4 text-sm">
                <div>
                  <dt className="text-xs font-medium text-muted-foreground">Price</dt>
                  {/* Read-only display only — never a student-editable input. */}
                  <dd className="text-base font-semibold text-foreground">
                    <CoursePriceText course={course} />
                  </dd>
                </div>
                <div>
                  <dt className="text-xs font-medium text-muted-foreground">Access</dt>
                  <dd className="text-foreground">
                    {course.accessDurationDays
                      ? `${course.accessDurationDays} days`
                      : "Lifetime access"}
                  </dd>
                </div>
              </dl>

              {course.status !== "PUBLIC" ? (
                <Alert role="status">
                  <AlertCircle aria-hidden="true" />
                  <AlertDescription>
                    This course isn&apos;t currently open for enrollment.
                  </AlertDescription>
                </Alert>
              ) : null}

              <CoursePricingNotice course={course} />

              {flowError ? (
                <Alert variant="destructive">
                  <AlertCircle aria-hidden="true" />
                  <AlertDescription>{flowError}</AlertDescription>
                </Alert>
              ) : null}

              {course.requiresManualQuote ? null : (
                <>
                  <LiveRegion
                    message={
                      mode === "gateway"
                        ? "Starting checkout…"
                        : mode === "slip"
                          ? "Preparing bank transfer upload…"
                          : ""
                    }
                  />

                  <div className="flex flex-col gap-2">
                    {needsAddress && billingAddressQuery.isSuccess ? (
                      <Alert role="status">
                        <AlertCircle aria-hidden="true" />
                        <AlertDescription>
                          Online payment needs your billing address.{" "}
                          <Link href="/student/profile" className="font-medium underline">
                            Add your address in My Profile
                          </Link>
                          , then come back to pay online.
                        </AlertDescription>
                      </Alert>
                    ) : null}

                    {showOnlineButton ? (
                      <Button
                        type="button"
                        onClick={handleEnroll}
                        disabled={isSubmitting || !checkoutAvailable || needsAddress}
                        aria-busy={mode === "gateway"}
                      >
                        {mode === "gateway"
                          ? "Starting checkout…"
                          : isFree
                            ? "Enroll"
                            : "Pay online (PayHere)"}
                      </Button>
                    ) : paymentOptionsQuery.isPending ? null : (
                      <p className="text-sm text-muted-foreground">
                        Online payment isn&apos;t available at your institute yet — pay by bank
                        transfer below.
                      </p>
                    )}

                    {bankSlipAvailable ? (
                      <>
                        <Button
                          type="button"
                          variant="outline"
                          onClick={handlePayBySlip}
                          disabled={isSubmitting || !checkoutAvailable}
                          aria-busy={mode === "slip"}
                        >
                          {mode === "slip" ? "Preparing…" : "Pay by bank transfer"}
                        </Button>
                        <p className="text-xs text-muted-foreground">
                          Already sent a bank transfer? Upload your payment slip instead of paying online.
                        </p>
                      </>
                    ) : null}
                  </div>
                </>
              )}
            </div>
          );
        }}
      </QueryStateBoundary>
    </div>
  );
}
