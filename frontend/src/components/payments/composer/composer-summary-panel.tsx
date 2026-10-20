"use client";

import { useId, useState } from "react";
import Link from "next/link";
import { useForm, useWatch } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { AlertCircle, Building2, CheckCircle2, Copy, Loader2, Paperclip, Truck, XIcon } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { LiveRegion } from "@/components/ui/live-region";
import { cn } from "@/lib/utils";
import { formatCurrency, toAmount, type CheckoutQuote } from "@/lib/api/klass-student";
import {
  ACCEPTED_SLIP_MIME_TYPES,
  ACCEPTED_SLIP_FORMATS_LABEL,
  MAX_SLIP_FILE_SIZE_LABEL,
  SLIP_UPLOAD_DEFAULT_VALUES,
  slipUploadSchema,
  toSlipUploadInput,
  type SlipUploadFormValues,
} from "@/lib/validation/payment-slip";
import { BillingAddressForm } from "@/components/students/billing-address-form";
import {
  SCOPE_LINE_LABELS,
  facilityFeeSourceText,
  groupCartByCourse,
  groupQuoteByCourse,
  hasDeliveryAddress,
  hasPostageCharge,
  lineKey,
  linePeriodText,
  type ComposerCart,
  type QuoteCourseGroup,
} from "./composer-model";

export type PaymentMethod = "online" | "slip";

export type FlowState =
  | { phase: "idle" }
  | { phase: "working"; method: PaymentMethod; step: string }
  | { phase: "error"; method: PaymentMethod; message: string };

/**
 * Right side of the composer: "Payment summary" from the server quote, then
 * the payment method. With nothing selected there is no pay action at all.
 * Items are grouped by course (a payment may span several courses), each with
 * its course-fee subtotal, facility fee and postage choice. Totals, fees,
 * postage, the delivery address and bank details are rendered exactly as
 * quoted; nothing here computes money.
 */
export function ComposerSummaryPanel({
  cart,
  currency,
  quote,
  quoteStatus,
  quoteError,
  deliveryMissing,
  priceChanged,
  onRetryQuote,
  onRemove,
  onTogglePostage,
  onAddressSaved,
  method,
  onMethodChange,
  flow,
  onPayOnline,
  onSubmitSlip,
  onCancel,
  billingAddressMissing,
}: {
  cart: ComposerCart;
  currency: string;
  quote: CheckoutQuote | undefined;
  quoteStatus: "idle" | "pending" | "error" | "success";
  quoteError: string | null;
  /** The server said postage needs a delivery address that isn't on record (422 DELIVERY_DETAILS_REQUIRED). */
  deliveryMissing: boolean;
  /** The order attempt answered 409 QUOTE_CHANGED; the quote shown is the fresh one. */
  priceChanged: boolean;
  onRetryQuote: () => void;
  onRemove: (key: string) => void;
  onTogglePostage: (courseId: string, selected: boolean) => void;
  onAddressSaved: () => void;
  method: PaymentMethod | null;
  onMethodChange: (method: PaymentMethod) => void;
  flow: FlowState;
  onPayOnline: () => void;
  onSubmitSlip: (input: { referenceNumber: string; file: File }) => void;
  onCancel: () => void;
  billingAddressMissing: boolean;
}) {
  const busy = flow.phase === "working";
  const empty = cart.items.length === 0;
  const zero = formatCurrency(0, currency);
  const quoted = quoteStatus === "success" && quote !== undefined;
  const cartGroups = groupCartByCourse(cart);
  const titleFor = (courseId: string) => cartGroups.find((group) => group.courseId === courseId)?.courseTitle;
  const groups = quoted ? groupQuoteByCourse(quote, titleFor) : [];
  const facilityFee = quoted ? toAmount(quote.facilityFeeTotal) : 0;
  const postage = quoted ? toAmount(quote.postageTotal) : 0;

  return (
    <div className="flex flex-col gap-4" data-testid="composer-summary">
      <section aria-labelledby="composer-summary-heading" className="flex flex-col gap-3 rounded-lg border border-border bg-muted/20 p-4">
        <h3 id="composer-summary-heading" className="text-sm font-semibold text-foreground">
          Payment summary
        </h3>

        {empty ? (
          <p className="text-sm text-muted-foreground" data-testid="composer-summary-empty">
            No items selected
          </p>
        ) : quoted ? (
          <div className="flex flex-col gap-3" data-testid="composer-summary-lines">
            {groups.map((group) => (
              <QuotedCourseGroup
                key={group.courseId}
                group={group}
                quote={quote}
                busy={busy}
                postageSelected={cart.postageCourseIds.includes(group.courseId)}
                onRemove={onRemove}
                onTogglePostage={onTogglePostage}
              />
            ))}
          </div>
        ) : (
          <div className="flex flex-col gap-3">
            {cartGroups.map((group) => (
              <section
                key={group.courseId}
                aria-label={group.courseTitle}
                className="flex flex-col gap-2 rounded-md border border-border bg-background p-2"
                data-testid="composer-summary-course"
                data-course-id={group.courseId}
              >
                <p className="truncate text-sm font-medium text-foreground">{group.courseTitle}</p>
                <ul className="flex flex-col gap-1" aria-label={`Selected items for ${group.courseTitle}`}>
                  {group.items.map((item) => (
                    <li key={item.key} className="flex items-center justify-between gap-2 text-sm">
                      <span className="truncate text-foreground">{item.label}</span>
                      <Button
                        type="button"
                        variant="ghost"
                        size="icon-xs"
                        aria-label={`Remove ${item.label} (${group.courseTitle})`}
                        onClick={() => onRemove(item.key)}
                        disabled={busy}
                        data-testid="composer-summary-remove"
                      >
                        <XIcon aria-hidden="true" />
                      </Button>
                    </li>
                  ))}
                </ul>
                {cart.postageCourseIds.includes(group.courseId) ? (
                  <PostageCheckbox
                    courseId={group.courseId}
                    courseTitle={group.courseTitle}
                    checked
                    disabled={busy}
                    amountText="Postage amount is shown once the total is calculated"
                    onChange={onTogglePostage}
                  />
                ) : null}
              </section>
            ))}
          </div>
        )}

        {!empty && quoteStatus === "pending" ? (
          <p className="flex items-center gap-2 text-xs text-muted-foreground" role="status">
            <Loader2 className="size-3 animate-spin" aria-hidden="true" />
            Calculating total…
          </p>
        ) : null}

        {!empty && quoteStatus === "error" && quoteError ? (
          <div role="alert" className="flex flex-col items-start gap-2 text-sm text-destructive" data-testid="composer-quote-error">
            <span>{quoteError}</span>
            {deliveryMissing ? null : (
              <Button type="button" size="sm" variant="outline" onClick={onRetryQuote}>
                Try again
              </Button>
            )}
          </div>
        ) : null}

        {!empty && (deliveryMissing || (quoted && quote.deliveryRequired)) ? (
          <DeliveryBlock
            quote={quoted ? quote : undefined}
            forceForm={deliveryMissing || (quoted && !hasDeliveryAddress(quote))}
            onSaved={onAddressSaved}
          />
        ) : null}

        {priceChanged && quoted ? (
          <Alert role="alert" data-testid="composer-price-changed">
            <AlertCircle aria-hidden="true" />
            <AlertDescription>Prices changed — review the updated total, then confirm again.</AlertDescription>
          </Alert>
        ) : null}

        <dl className="flex flex-col gap-1 border-t border-border pt-3 text-sm">
          <div className="flex justify-between">
            <dt className="text-muted-foreground">Course fees</dt>
            <dd className="text-foreground" data-testid="composer-subtotal">
              {quoted ? formatCurrency(quote.subtotal, quote.currency) : zero}
            </dd>
          </div>
          {quoted && facilityFee > 0 ? (
            <div className="flex justify-between">
              <dt className="text-muted-foreground">Facility fee</dt>
              <dd className="text-foreground" data-testid="composer-facility-fee">
                {formatCurrency(quote.facilityFeeTotal, quote.currency)}
              </dd>
            </div>
          ) : null}
          {quoted && postage > 0 ? (
            <div className="flex justify-between">
              <dt className="text-muted-foreground">Postage</dt>
              <dd className="text-foreground" data-testid="composer-postage-total">
                {formatCurrency(quote.postageTotal, quote.currency)}
              </dd>
            </div>
          ) : null}
          {quoted ? (
            <div className="flex justify-between">
              <dt className="text-muted-foreground">Discounts</dt>
              <dd className="text-foreground" data-testid="composer-discount-total">
                {toAmount(quote.discountTotal) > 0
                  ? `− ${formatCurrency(quote.discountTotal, quote.currency)}`
                  : formatCurrency(0, quote.currency)}
              </dd>
            </div>
          ) : null}
          <div className="flex justify-between font-semibold">
            <dt className="text-foreground">Total</dt>
            <dd className="text-foreground" data-testid="composer-total">
              {quoted ? formatCurrency(quote.total, quote.currency) : zero}
            </dd>
          </div>
          {quoted && facilityFee > 0 && quote.feeNote ? (
            <p className="text-xs text-muted-foreground" data-testid="composer-fee-note">
              {quote.feeNote}
            </p>
          ) : null}
        </dl>
      </section>

      {quoted && !empty ? (
        <MethodSection
          quote={quote}
          method={method}
          onMethodChange={onMethodChange}
          flow={flow}
          onPayOnline={onPayOnline}
          onSubmitSlip={onSubmitSlip}
          onCancel={onCancel}
          billingAddressMissing={billingAddressMissing}
        />
      ) : (
        <div className="flex justify-end">
          <Button type="button" variant="outline" onClick={onCancel} data-testid="composer-cancel">
            CANCEL
          </Button>
        </div>
      )}
    </div>
  );
}

/** One course of the quote: its lines, course-fee subtotal, facility fee and postage choice (all server values). */
function QuotedCourseGroup({
  group,
  quote,
  busy,
  postageSelected,
  onRemove,
  onTogglePostage,
}: {
  group: QuoteCourseGroup;
  quote: CheckoutQuote;
  busy: boolean;
  postageSelected: boolean;
  onRemove: (key: string) => void;
  onTogglePostage: (courseId: string, selected: boolean) => void;
}) {
  const charge = group.charge;
  const currency = charge?.currency ?? quote.currency;
  const feeSource = charge ? facilityFeeSourceText(charge, quote.feeBasis) : null;
  return (
    <section
      aria-label={group.courseTitle}
      className="flex flex-col gap-2 rounded-md border border-border bg-background p-2"
      data-testid="composer-summary-course"
      data-course-id={group.courseId}
    >
      <p className="truncate text-sm font-medium text-foreground" data-testid="composer-summary-course-title">
        {group.courseTitle}
      </p>
      <ul className="flex flex-col gap-1" aria-label={`Selected items for ${group.courseTitle}`}>
        {group.lines.map((line) => (
          <li key={line.lineNo} className="flex items-start justify-between gap-2 text-sm" data-testid="composer-summary-line">
            <span className="min-w-0 text-xs text-muted-foreground">
              {linePeriodText(line)} — {SCOPE_LINE_LABELS[line.scope] ?? line.scope}
            </span>
            <span className="flex shrink-0 items-center gap-1">
              <span className="font-medium text-foreground">
                {formatCurrency(line.unitAmount, quote.currency).replace(`${quote.currency} `, "")}
              </span>
              <Button
                type="button"
                variant="ghost"
                size="icon-xs"
                aria-label={`Remove ${linePeriodText(line)} (${group.courseTitle})`}
                onClick={() => onRemove(lineKey(line, group.courseId))}
                disabled={busy}
                data-testid="composer-summary-remove"
              >
                <XIcon aria-hidden="true" />
              </Button>
            </span>
          </li>
        ))}
      </ul>
      {charge ? (
        <dl className="flex flex-col gap-0.5 border-t border-border pt-2 text-xs">
          <div className="flex justify-between gap-2">
            <dt className="text-muted-foreground">Course fees</dt>
            <dd className="text-foreground" data-testid="composer-course-subtotal">
              {formatCurrency(charge.subtotal, currency)}
            </dd>
          </div>
          {quote.feeBasis !== "NONE" ? (
            <div className="flex justify-between gap-2">
              <dt className="text-muted-foreground">
                Facility fee{feeSource ? <span className="block text-[11px]">{feeSource}</span> : null}
              </dt>
              <dd className="text-foreground" data-testid="composer-course-facility-fee">
                {formatCurrency(charge.facilityFee, currency)}
              </dd>
            </div>
          ) : null}
        </dl>
      ) : null}
      {/* No configured postage = no delivery option. A stale selection (postage removed after
          it was ticked) still renders so the student can untick it. */}
      {charge && (hasPostageCharge(charge) || postageSelected) ? (
        <PostageCheckbox
          courseId={group.courseId}
          courseTitle={group.courseTitle}
          checked={postageSelected}
          disabled={busy}
          amountText={hasPostageCharge(charge) ? formatCurrency(charge.postageFee, currency) : "No postage charge"}
          onChange={onTogglePostage}
        />
      ) : null}
    </section>
  );
}

function PostageCheckbox({
  courseId,
  courseTitle,
  checked,
  disabled,
  amountText,
  onChange,
}: {
  courseId: string;
  courseTitle: string;
  checked: boolean;
  disabled: boolean;
  amountText: string;
  onChange: (courseId: string, selected: boolean) => void;
}) {
  const id = `composer-postage-${courseId}`;
  return (
    <div className="flex items-start gap-2 text-xs" data-testid="composer-postage">
      <input
        id={id}
        type="checkbox"
        className="mt-0.5 size-4 accent-blue-600"
        checked={checked}
        disabled={disabled}
        onChange={(event) => onChange(courseId, event.target.checked)}
        aria-describedby={`${id}-amount`}
        data-testid="composer-postage-checkbox"
      />
      <label htmlFor={id} className="flex flex-1 flex-wrap justify-between gap-1">
        <span className="text-foreground">
          Deliver printed materials by post<span className="sr-only"> for {courseTitle}</span>
        </span>
        <span id={`${id}-amount`} className="text-muted-foreground" data-testid="composer-postage-amount">
          {amountText}
        </span>
      </label>
    </div>
  );
}

/**
 * Delivery address for postage: the on-record billing address from the quote.
 * When none is on record (or the student wants to change it) the existing
 * billing-address form is shown inline; saving it re-quotes.
 */
function DeliveryBlock({
  quote,
  forceForm,
  onSaved,
}: {
  quote: CheckoutQuote | undefined;
  forceForm: boolean;
  onSaved: () => void;
}) {
  const [editing, setEditing] = useState(false);
  const delivery = quote?.delivery ?? null;
  const showForm = forceForm || editing;
  return (
    <section
      aria-labelledby="composer-delivery-heading"
      className="flex flex-col gap-2 rounded-md border border-border bg-background p-3 text-sm"
      data-testid="composer-delivery"
    >
      <h4 id="composer-delivery-heading" className="flex items-center gap-1.5 text-sm font-medium text-foreground">
        <Truck className="size-4" aria-hidden="true" />
        Delivery address
      </h4>
      {!showForm && delivery ? (
        <>
          <address className="text-xs not-italic text-foreground" data-testid="composer-delivery-address">
            {delivery.recipientName ? <span className="block font-medium">{delivery.recipientName}</span> : null}
            <span className="block">{delivery.addressLine}</span>
            <span className="block">{delivery.city}</span>
            {delivery.phone ? <span className="block text-muted-foreground">{delivery.phone}</span> : null}
          </address>
          <Button type="button" size="sm" variant="outline" className="w-fit" onClick={() => setEditing(true)}>
            Change address
          </Button>
        </>
      ) : (
        <BillingAddressForm
          idPrefix="composer-delivery"
          variant="inline"
          heading={forceForm ? "Add your delivery address" : "Change your delivery address"}
          description="Postage is sent to your billing address. Saving it updates your profile and recalculates the total."
          onSaved={() => {
            setEditing(false);
            onSaved();
          }}
          onCancel={editing && !forceForm ? () => setEditing(false) : undefined}
        />
      )}
    </section>
  );
}

function MethodSection({
  quote,
  method,
  onMethodChange,
  flow,
  onPayOnline,
  onSubmitSlip,
  onCancel,
  billingAddressMissing,
}: {
  quote: CheckoutQuote;
  method: PaymentMethod | null;
  onMethodChange: (method: PaymentMethod) => void;
  flow: FlowState;
  onPayOnline: () => void;
  onSubmitSlip: (input: { referenceNumber: string; file: File }) => void;
  onCancel: () => void;
  billingAddressMissing: boolean;
}) {
  const groupName = useId();
  const busy = flow.phase === "working";
  const bank = quote.bankDetails;
  const slipAvailable = quote.methods.slip && bank !== null;

  return (
    <section aria-labelledby={`${groupName}-heading`} className="flex flex-col gap-3">
      <h3 id={`${groupName}-heading`} className="text-sm font-semibold text-foreground">
        Payment method
      </h3>
      <div role="radiogroup" aria-labelledby={`${groupName}-heading`} className="flex flex-col gap-2">
        {quote.methods.online ? (
          <MethodOption
            name={groupName}
            value="online"
            checked={method === "online"}
            disabled={busy}
            onChange={() => onMethodChange("online")}
            title="Pay online"
            subtitle="Card or online banking"
            testId="composer-method-online"
          />
        ) : null}
        {quote.methods.slip ? (
          <MethodOption
            name={groupName}
            value="slip"
            checked={method === "slip"}
            disabled={busy || !slipAvailable}
            onChange={() => onMethodChange("slip")}
            title="Upload a payment slip"
            subtitle={bank ? "Paid at a bank? Upload the deposit slip." : "Your institute hasn't added bank details yet"}
            testId="composer-method-slip"
          />
        ) : null}
        {!quote.methods.online && !quote.methods.slip ? (
          <p className="text-sm text-muted-foreground" role="status">
            No payment method is available right now. Contact your institute.
          </p>
        ) : null}
      </div>

      {flow.phase === "error" ? (
        <Alert variant="destructive" data-testid="composer-flow-error">
          <AlertCircle aria-hidden="true" />
          <AlertDescription>{flow.message}</AlertDescription>
        </Alert>
      ) : null}
      <LiveRegion message={flow.phase === "working" ? flow.step : ""} />

      {method === "online" && quote.methods.online ? (
        <div className="flex flex-col gap-2">
          {billingAddressMissing ? (
            <Alert role="status">
              <AlertCircle aria-hidden="true" />
              <AlertDescription>
                Online payment needs your billing address.{" "}
                <Link href="/student/profile" className="font-medium underline">
                  Add it in My Profile
                </Link>
                , then come back to pay online.
              </AlertDescription>
            </Alert>
          ) : null}
          <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
            <Button type="button" variant="outline" onClick={onCancel} data-testid="composer-cancel">
              CANCEL
            </Button>
            <Button
              type="button"
              onClick={onPayOnline}
              disabled={busy || billingAddressMissing}
              aria-busy={busy}
              data-testid="composer-proceed-online"
              className="bg-blue-600 text-white hover:bg-blue-700"
            >
              {busy ? (
                <>
                  <Loader2 className="animate-spin" aria-hidden="true" />
                  {flow.phase === "working" ? flow.step : "Working…"}
                </>
              ) : flow.phase === "error" ? (
                "TRY AGAIN"
              ) : (
                "PROCEED TO PAYMENT"
              )}
            </Button>
          </div>
        </div>
      ) : null}

      {method === "slip" && slipAvailable && bank ? (
        <SlipForm bank={bank} busy={busy} retry={flow.phase === "error"} onSubmit={onSubmitSlip} onCancel={onCancel} />
      ) : null}

      {method === null ? (
        <div className="flex justify-end">
          <Button type="button" variant="outline" onClick={onCancel} data-testid="composer-cancel">
            CANCEL
          </Button>
        </div>
      ) : null}
    </section>
  );
}

function MethodOption({
  name,
  value,
  checked,
  disabled,
  onChange,
  title,
  subtitle,
  testId,
}: {
  name: string;
  value: string;
  checked: boolean;
  disabled: boolean;
  onChange: () => void;
  title: string;
  subtitle: string;
  testId: string;
}) {
  const id = `${name}-${value}`;
  return (
    <label
      htmlFor={id}
      className={cn(
        "flex cursor-pointer items-start gap-3 rounded-lg border p-3",
        checked ? "border-blue-600 bg-blue-50 dark:bg-blue-950" : "border-border",
        disabled && "cursor-not-allowed opacity-60"
      )}
      data-testid={testId}
    >
      <input
        id={id}
        type="radio"
        name={name}
        value={value}
        checked={checked}
        disabled={disabled}
        onChange={onChange}
        className="mt-1 size-4 accent-blue-600"
      />
      <span className="flex flex-col">
        <span className="text-sm font-medium text-foreground">{title}</span>
        <span className="text-xs text-muted-foreground">{subtitle}</span>
      </span>
    </label>
  );
}

function SlipForm({
  bank,
  busy,
  retry,
  onSubmit,
  onCancel,
}: {
  bank: NonNullable<CheckoutQuote["bankDetails"]>;
  busy: boolean;
  retry: boolean;
  onSubmit: (input: { referenceNumber: string; file: File }) => void;
  onCancel: () => void;
}) {
  const fileId = useId();
  const refId = useId();
  const [copied, setCopied] = useState("");
  const {
    register,
    handleSubmit,
    setValue,
    control,
    formState: { errors },
  } = useForm<SlipUploadFormValues>({
    resolver: zodResolver(slipUploadSchema),
    defaultValues: SLIP_UPLOAD_DEFAULT_VALUES,
    mode: "onChange",
  });
  const referenceNumber = useWatch({ control, name: "referenceNumber" }) ?? "";
  const file = useWatch({ control, name: "file" }) as File | undefined;
  const fileError = typeof errors.file?.message === "string" ? errors.file.message : null;

  const copyAccount = async () => {
    if (!bank.accountNumber) return;
    try {
      await navigator.clipboard.writeText(bank.accountNumber);
      setCopied("Account number copied");
    } catch {
      setCopied("Couldn't copy. Select the number and copy it manually.");
    }
  };

  // SUBMIT stays disabled until a file is chosen (and the reference the
  // backend requires is filled in); Zod re-validates on submit.
  const canSubmit = file instanceof File && !fileError && referenceNumber.trim().length > 0 && !busy;

  return (
    <form
      className="flex flex-col gap-3"
      data-testid="composer-slip-form"
      noValidate
      onSubmit={handleSubmit((values) => onSubmit(toSlipUploadInput(values)))}
    >
      <div className="flex flex-col gap-2 rounded-lg border border-border bg-muted/20 p-3 text-sm" data-testid="composer-bank-details">
        <p className="text-xs font-semibold tracking-wide text-muted-foreground uppercase">Deposit to</p>
        <div className="flex items-start gap-2">
          <Building2 className="mt-0.5 size-4 text-blue-700" aria-hidden="true" />
          <div className="flex flex-col">
            <span className="font-medium text-foreground">{bank.bankName ?? "Bank not set"}</span>
            {bank.branch ? <span className="text-xs text-muted-foreground">{bank.branch}</span> : null}
          </div>
        </div>
        <dl className="flex flex-col gap-1">
          <div className="flex justify-between gap-2">
            <dt className="text-muted-foreground">Account holder</dt>
            <dd className="text-right font-medium text-foreground">{bank.accountHolder ?? "—"}</dd>
          </div>
          <div className="flex items-center justify-between gap-2">
            <dt className="text-muted-foreground">Account number</dt>
            <dd className="flex items-center gap-1 font-mono font-medium text-foreground">
              <span data-testid="composer-account-number">{bank.accountNumber ?? "—"}</span>
              {bank.accountNumber ? (
                <Button type="button" variant="ghost" size="icon-xs" aria-label="Copy account number" onClick={copyAccount} data-testid="composer-copy-account">
                  <Copy aria-hidden="true" />
                </Button>
              ) : null}
            </dd>
          </div>
        </dl>
        <LiveRegion message={copied} />
      </div>

      <div className="flex flex-col gap-1">
        <Label htmlFor={refId}>Deposit reference number *</Label>
        <Input
          id={refId}
          maxLength={255}
          aria-required="true"
          aria-invalid={errors.referenceNumber ? true : undefined}
          aria-describedby={`${refId}-help${errors.referenceNumber ? ` ${refId}-error` : ""}`}
          disabled={busy}
          data-testid="composer-slip-reference"
          {...register("referenceNumber")}
        />
        <p id={`${refId}-help`} className="text-xs text-muted-foreground">
          The reference or transaction number printed on your deposit slip.
        </p>
        {errors.referenceNumber?.message ? (
          <p id={`${refId}-error`} role="alert" className="text-xs text-destructive">
            {errors.referenceNumber.message}
          </p>
        ) : null}
      </div>

      <div className="flex flex-col gap-1">
        <Label htmlFor={fileId}>Payment slip *</Label>
        <div className="flex items-center gap-2 rounded-lg border border-input px-2.5 py-1.5">
          <Paperclip className="size-4 text-muted-foreground" aria-hidden="true" />
          <input
            id={fileId}
            type="file"
            accept={ACCEPTED_SLIP_MIME_TYPES.join(",")}
            aria-required="true"
            disabled={busy}
            aria-describedby={`${fileId}-help${fileError ? ` ${fileId}-error` : ""}`}
            aria-invalid={fileError ? true : undefined}
            onChange={(event) =>
              setValue("file", event.target.files?.[0], { shouldValidate: true, shouldDirty: true })
            }
            className="min-w-0 flex-1 text-sm file:mr-2 file:rounded-md file:border-0 file:bg-muted file:px-2 file:py-1 file:text-sm"
            data-testid="composer-slip-file"
          />
        </div>
        <p id={`${fileId}-help`} className="text-xs text-muted-foreground">
          A photo or PDF of your bank deposit slip. ({ACCEPTED_SLIP_FORMATS_LABEL}, up to {MAX_SLIP_FILE_SIZE_LABEL})
        </p>
        {fileError ? (
          <p id={`${fileId}-error`} role="alert" className="text-xs text-destructive">
            {fileError}
          </p>
        ) : null}
      </div>

      <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
        <Button type="button" variant="outline" onClick={onCancel} data-testid="composer-cancel">
          CANCEL
        </Button>
        <Button
          type="submit"
          disabled={!canSubmit}
          aria-busy={busy}
          data-testid="composer-submit-slip"
          className="bg-blue-600 text-white hover:bg-blue-700"
        >
          {busy ? <Loader2 className="animate-spin" aria-hidden="true" /> : null}
          {busy ? "Submitting…" : retry ? "TRY AGAIN" : "SUBMIT FOR APPROVAL"}
        </Button>
      </div>
    </form>
  );
}

/** Shown after a slip upload succeeds. Nothing is unlocked; a reviewer decides. */
export function SlipSubmittedPanel({ onClose, onAnother }: { onClose: () => void; onAnother: () => void }) {
  return (
    <div role="status" className="flex flex-col items-center gap-3 px-4 py-10 text-center" data-testid="composer-slip-submitted">
      <CheckCircle2 className="size-10 text-green-600" aria-hidden="true" />
      <p className="text-base font-semibold text-foreground">Submitted — pending approval</p>
      <p className="max-w-md text-sm text-muted-foreground">
        Your institute will check your payment slip. The lessons you paid for open after it&apos;s approved — you
        don&apos;t need to do anything else.
      </p>
      <div className="flex flex-wrap justify-center gap-2">
        <Link href="/student/payments/history" onClick={onClose} className="text-sm font-medium text-blue-700 underline dark:text-blue-300">
          View payment history
        </Link>
      </div>
      <div className="flex gap-2">
        <Button type="button" variant="outline" onClick={onAnother}>
          Make another payment
        </Button>
        <Button type="button" onClick={onClose} data-testid="composer-done">
          Close
        </Button>
      </div>
    </div>
  );
}
