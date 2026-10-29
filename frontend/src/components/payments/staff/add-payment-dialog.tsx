"use client";

import { useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { useForm, useWatch } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import Link from "next/link";
import { useIsMutating, useQueryClient } from "@tanstack/react-query";
import { AlertCircle, AlertTriangle, CheckCircle2, CreditCard, RotateCcw } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { LiveRegion } from "@/components/ui/live-region";
import { MultiSelect } from "@/components/ui/multi-select";
import { Textarea } from "@/components/ui/textarea";
import { isApiClientError } from "@/lib/api/error";
import { formatCurrency, monthLong, type CheckoutOfferItem, type CheckoutQuote } from "@/lib/api/klass-student";
import { useFreeCards } from "@/lib/api/free-cards";
import {
  ADJUSTMENT_WITH_POSTAGE_MESSAGE,
  MANUAL_PAYMENT_METHODS,
  RECORD_MANUAL_PAYMENT_KEY,
  addDays,
  centsToDecimal,
  freshQuoteFromError,
  manualPaymentErrorMessage,
  paymentMethodLabel,
  staffPaymentKeys,
  toCents,
  todayIn,
  useRecordManualPayment,
  useStaffManualPaymentQuote,
  useStaffPaymentOffersForCourses,
  type ManualPaymentMethod,
  type ManualPaymentQuoteRequest,
  type ManualPaymentReceipt,
  type ManualPaymentRecordRequest,
  type PaymentContext,
} from "@/lib/api/staff-payments";
import { formatDateTime } from "@/lib/format";
import { randomUuid } from "@/lib/uuid";
import { FieldError, NATIVE_SELECT_CLASS, StaffDialog } from "./staff-dialog";
import { addPaymentSchema, type AddPaymentValues } from "./schemas";

const QUOTE_DEBOUNCE_MS = 300;
const DISCOUNT_CHIPS = [75, 50, 25] as const;

/** Server field name → form field (422 VALIDATION_ERROR field errors). */
const SERVER_FIELD: Record<string, keyof AddPaymentValues> = {
  staffAdjustmentAmount: "discount",
  adjustmentReason: "reason",
  paymentMethod: "method",
  receivedOn: "receivedOn",
  referenceNumber: "reference",
  notes: "notes",
  items: "monthKeys",
};

/**
 * "Add Payment" (Klass S2 + spec §1 additions): a staff-confirmed cash /
 * bank transfer / card receipt for the selected student. Every amount comes
 * from the server quote; `expectedReceivedAmount` (= total − staff discount)
 * is only compared server-side. Nothing is shown as saved until the POST
 * answers 200/201. One idempotency key per open dialog, reused for an
 * unchanged retry and regenerated once the user changes anything after a
 * failed attempt (the server answers 409 IDEMPOTENCY_KEY_REUSED otherwise).
 */
export function AddPaymentDialog({
  open,
  onOpenChange,
  context,
  defaultCourseId,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  context: PaymentContext;
  defaultCourseId: string | null;
}) {
  const busy = useIsMutating({ mutationKey: RECORD_MANUAL_PAYMENT_KEY }) > 0;
  return (
    <StaffDialog
      open={open}
      onOpenChange={onOpenChange}
      title="Add Payment"
      description={`Record a payment received from ${context.student.name}`}
      icon={<CreditCard className="size-5" aria-hidden="true" />}
      busy={busy}
      testId="add-payment-dialog"
    >
      <AddPaymentBody context={context} defaultCourseId={defaultCourseId} onClose={() => onOpenChange(false)} />
    </StaffDialog>
  );
}

function useDebounced(value: string, ms: number): string {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), ms);
    return () => clearTimeout(timer);
  }, [value, ms]);
  return debounced;
}

function monthKey(year: number, month: number): string {
  return `${year}-${String(month).padStart(2, "0")}`;
}

function monthText(key: string): string {
  const [year, month] = key.split("-");
  return `${year} ${monthLong(Number(month))}`;
}

interface MonthOption {
  key: string;
  year: number;
  month: number;
  /** Selected courses with an AVAILABLE offer for this month. */
  availableCourseIds: string[];
  /** Disabled-state label when no selected course can be paid for this month. */
  blockedLabel: string | null;
}

function AddPaymentBody({
  context,
  defaultCourseId,
  onClose,
}: {
  context: PaymentContext;
  defaultCourseId: string | null;
  onClose: () => void;
}) {
  const studentId = context.student.studentId;
  const queryClient = useQueryClient();
  const monthlyCourses = useMemo(
    () => context.courses.filter((course) => course.pricingModel === "MONTHLY"),
    [context.courses]
  );
  const courseById = useMemo(() => new Map(context.courses.map((course) => [course.courseId, course])), [context.courses]);
  const today = useMemo(() => todayIn(context.timezone), [context.timezone]);
  const minReceivedOn = useMemo(() => addDays(today, -365), [today]);
  const schema = useMemo(() => addPaymentSchema({ today, minReceivedOn }), [today, minReceivedOn]);

  const {
    register,
    control,
    setValue,
    setError,
    clearErrors,
    handleSubmit,
    getValues,
    formState: { errors },
  } = useForm<AddPaymentValues>({
    resolver: zodResolver(schema),
    defaultValues: {
      courseIds:
        defaultCourseId && monthlyCourses.some((course) => course.courseId === defaultCourseId) ? [defaultCourseId] : [],
      monthKeys: [],
      postage: [],
      discount: "",
      reason: "",
      reference: "",
      method: "",
      receivedOn: today,
      notes: "",
    },
  });
  const courseIds = useWatch({ control, name: "courseIds" });
  const monthKeys = useWatch({ control, name: "monthKeys" });
  const postage = useWatch({ control, name: "postage" });
  const discount = useWatch({ control, name: "discount" });

  const [phase, setPhase] = useState<"form" | "confirm" | "done">("form");
  const [submitError, setSubmitError] = useState<{ code: string | null; message: string } | null>(null);
  const [receipt, setReceipt] = useState<ManualPaymentReceipt | null>(null);
  const [announcement, setAnnouncement] = useState("");

  // One key per open dialog; `usedFor` = the payload it was last sent with. An
  // unchanged retry reuses it; any edit after a failed attempt gets a new key.
  const idempotency = useRef<{ key: string; usedFor: string | null }>({ key: randomUuid(), usedFor: null });
  const submitting = useRef(false);
  const record = useRecordManualPayment(studentId);

  // --- offers / months -------------------------------------------------------
  const offerQueries = useStaffPaymentOffersForCourses(studentId, courseIds, "MONTHLY");
  const offersLoading = offerQueries.some((query) => query.isPending);
  const offersError = offerQueries.find((query) => query.isError)?.error ?? null;
  const offersSignature = offerQueries.map((query) => query.dataUpdatedAt).join(",");

  const offersByCourse = useMemo(() => {
    const map = new Map<string, Map<string, CheckoutOfferItem>>();
    courseIds.forEach((courseId, index) => {
      const data = offerQueries[index]?.data;
      if (!data || !data.supported) return;
      const byMonth = new Map<string, CheckoutOfferItem>();
      for (const item of data.items) {
        if (item.year && item.month) byMonth.set(monthKey(item.year, item.month), item);
      }
      map.set(courseId, byMonth);
    });
    return map;
    // `offerQueries` is a new array every render; `offersSignature` tracks its data.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [courseIds, offersSignature]);

  const monthOptions = useMemo<MonthOption[]>(() => {
    const keys = new Set<string>();
    for (const byMonth of offersByCourse.values()) for (const key of byMonth.keys()) keys.add(key);
    return [...keys].sort().map((key) => {
      const [year, month] = key.split("-").map(Number);
      const states = courseIds
        .map((courseId) => offersByCourse.get(courseId)?.get(key))
        .filter((item): item is CheckoutOfferItem => !!item);
      const available = courseIds.filter((courseId) => offersByCourse.get(courseId)?.get(key)?.state === "AVAILABLE");
      const pending = states.some((item) => item.state === "PENDING");
      return {
        key,
        year,
        month,
        availableCourseIds: available,
        blockedLabel: available.length > 0 ? null : pending ? "Pending payment" : "Paid",
      };
    });
  }, [offersByCourse, courseIds]);

  const optionByKey = useMemo(() => new Map(monthOptions.map((option) => [option.key, option])), [monthOptions]);
  const selectedMonths = useMemo(
    () => monthKeys.filter((key) => (optionByKey.get(key)?.availableCourseIds.length ?? 0) > 0).sort(),
    [monthKeys, optionByKey]
  );

  const items = useMemo(
    () =>
      selectedMonths.flatMap((key) => {
        const option = optionByKey.get(key)!;
        return courseIds
          .filter((courseId) => option.availableCourseIds.includes(courseId))
          .map((courseId) => ({ courseId, scope: "MONTHLY" as const, year: option.year, month: option.month }));
      }),
    [selectedMonths, optionByKey, courseIds]
  );
  const coursesWithItems = useMemo(() => new Set(items.map((item) => item.courseId)), [items]);
  const postageCourses = courseIds
    .map((courseId) => courseById.get(courseId))
    .filter((course): course is NonNullable<typeof course> => !!course && toCents(course.postageFee) > 0);
  const postageCourseIds = useMemo(
    () => postage.filter((courseId) => coursesWithItems.has(courseId)),
    [postage, coursesWithItems]
  );
  const postageSelected = postageCourseIds.length > 0;

  // --- free-card coverage warning (informational; never blocks) ------------
  const freeCards = useFreeCards({ studentId, size: 100 });
  const coveredWarnings = useMemo(() => {
    const cards = (freeCards.data?.content ?? []).filter(
      (card) => card.status === "ACTIVE" || card.status === "SCHEDULED"
    );
    return items
      .filter((item) =>
        cards.some(
          (card) => card.courseId === item.courseId && card.coveredMonths.includes(monthKey(item.year, item.month))
        )
      )
      .map((item) => `${monthText(monthKey(item.year, item.month))} – ${courseById.get(item.courseId)?.title ?? "Course"}`);
  }, [freeCards.data, items, courseById]);

  // --- quote -----------------------------------------------------------------
  const quoteBodyKey = JSON.stringify({ items, postageCourseIds } satisfies ManualPaymentQuoteRequest);
  const debouncedKey = useDebounced(quoteBodyKey, QUOTE_DEBOUNCE_MS);
  const debouncedBody = useMemo(() => JSON.parse(debouncedKey) as ManualPaymentQuoteRequest, [debouncedKey]);
  const quoteQuery = useStaffManualPaymentQuote(studentId, debouncedBody, true);
  const quoteCurrent = debouncedKey === quoteBodyKey && items.length > 0;
  const quote: CheckoutQuote | undefined = quoteCurrent ? quoteQuery.data : undefined;
  const quoteLoading = items.length > 0 && (!quoteCurrent || quoteQuery.isFetching);

  const currency = quote?.currency ?? courseById.get(courseIds[0] ?? "")?.currency ?? "LKR";
  const totalCents = quote ? toCents(quote.total) : Number.NaN;
  const subtotalCents = quote ? toCents(quote.subtotal) : Number.NaN;
  const discountCents = postageSelected || discount.trim() === "" ? 0 : toCents(discount);
  const netCents = Number.isFinite(totalCents) && Number.isFinite(discountCents) ? totalCents - discountCents : Number.NaN;

  const setArray = (name: "courseIds" | "monthKeys" | "postage", next: string[]) => {
    setValue(name, next, { shouldDirty: true });
    clearErrors(name);
  };
  const setDiscount = (next: string) => {
    setValue("discount", next, { shouldDirty: true });
    clearErrors("discount");
  };

  /** Checks the schema can't see (they need the quote). True when ready to confirm. */
  const checkAgainstQuote = (values: AddPaymentValues): boolean => {
    if (items.length === 0) {
      setError("monthKeys", { type: "manual", message: "Select at least one month that can be paid for." });
      return false;
    }
    if (!quote || quoteLoading) {
      setSubmitError({ code: null, message: "The total is still being calculated. Wait a moment, then try again." });
      return false;
    }
    const cents = postageSelected || values.discount.trim() === "" ? 0 : toCents(values.discount);
    if (cents >= totalCents) {
      setError("discount", {
        type: "manual",
        message: "The discount must be less than the total. To give the months for free, issue a Free Card instead.",
      });
      return false;
    }
    return true;
  };

  const review = handleSubmit((values) => {
    setSubmitError(null);
    if (checkAgainstQuote(values)) setPhase("confirm");
  });

  const confirm = async () => {
    if (submitting.current || !quote) return;
    const values = getValues();
    if (!checkAgainstQuote(values)) {
      setPhase("form");
      return;
    }
    submitting.current = true;
    setSubmitError(null);
    const adjustment = postageSelected || values.discount.trim() === "" ? 0 : toCents(values.discount);
    const payload: Omit<ManualPaymentRecordRequest, "idempotencyKey"> = {
      items,
      postageCourseIds,
      quoteToken: quote.quoteToken,
      staffAdjustmentAmount: centsToDecimal(adjustment),
      adjustmentReason: adjustment > 0 ? values.reason.trim() : null,
      expectedReceivedAmount: centsToDecimal(totalCents - adjustment),
      paymentMethod: values.method as ManualPaymentMethod,
      receivedOn: values.receivedOn,
      referenceNumber: values.reference.trim() || null,
      notes: values.notes.trim() || null,
    };
    const signature = JSON.stringify(payload);
    const idem = idempotency.current;
    if (idem.usedFor !== null && idem.usedFor !== signature) idem.key = randomUuid();
    idem.usedFor = signature;
    try {
      const result = await record.mutateAsync({ ...payload, idempotencyKey: idem.key });
      setReceipt(result);
      setPhase("done");
      setAnnouncement("Payment recorded.");
    } catch (error) {
      handleError(error);
    } finally {
      submitting.current = false;
    }
  };

  const handleError = (error: unknown) => {
    const code = isApiClientError(error) ? error.code : null;
    const message = manualPaymentErrorMessage(error);
    setSubmitError({ code, message });
    if (code === "QUOTE_CHANGED") {
      const fresh = freshQuoteFromError(error);
      if (fresh) queryClient.setQueryData(staffPaymentKeys.quote(studentId, debouncedBody), fresh);
      else void quoteQuery.refetch();
      setPhase("confirm");
      return;
    }
    if (code === "AMOUNT_MISMATCH") void quoteQuery.refetch();
    if (code === "ITEM_ALREADY_PAID" || code === "ITEM_PENDING" || code === "ITEM_NOT_ELIGIBLE") {
      void queryClient.invalidateQueries({ queryKey: staffPaymentKeys.student(studentId) });
    }
    let mapped = false;
    const mapField = (field: keyof AddPaymentValues, fieldMessage: string) => {
      setError(field, { type: "server", message: fieldMessage });
      mapped = true;
    };
    if (code === "ADJUSTMENT_REASON_REQUIRED") mapField("reason", message);
    if (code === "ADJUSTMENT_TOO_LARGE" || code === "ADJUSTMENT_WITH_POSTAGE_UNSUPPORTED") mapField("discount", message);
    if (isApiClientError(error)) {
      for (const fieldError of error.fieldErrors) {
        const field = SERVER_FIELD[fieldError.field];
        if (field) mapField(field, fieldError.message);
      }
    }
    if (mapped) setPhase("form");
  };

  // --- rendering ---------------------------------------------------------------
  if (phase === "done" && receipt) {
    return (
      <div className="flex flex-col gap-4 p-4" data-testid="add-payment-success">
        <LiveRegion message={announcement} />
        <Alert>
          <CheckCircle2 aria-hidden="true" className="text-green-700" />
          <AlertDescription>
            {receipt.replay ? "This payment was already recorded - showing the saved receipt." : "Payment recorded."}
          </AlertDescription>
        </Alert>
        <dl className="grid grid-cols-1 gap-2 sm:grid-cols-2">
          <SummaryItem label="Receipt ID" value={<span className="font-mono text-xs">{receipt.receiptId}</span>} testId="receipt-id" />
          <SummaryItem label="Payment ID" value={<span className="font-mono text-xs">{receipt.paymentId}</span>} testId="receipt-payment-id" />
          <SummaryItem label="Amount received" value={formatCurrency(receipt.receivedAmount, receipt.currency)} testId="receipt-amount" />
          <SummaryItem label="Method" value={paymentMethodLabel(receipt.paymentMethod)} />
          <SummaryItem label="Received on" value={receipt.receivedOn} />
          <SummaryItem label="Recorded at" value={formatDateTime(receipt.recordedAt)} />
        </dl>
        <ul className="flex flex-col gap-1 rounded-lg border border-border p-3" aria-label="Receipt lines">
          {receipt.lines.map((line) => (
            <li key={line.lineNo} className="flex justify-between gap-2">
              <span>
                {line.courseTitle ? `${line.courseTitle} – ` : ""}
                {line.label}
              </span>
              <span>{formatCurrency(line.lineTotal, receipt.currency)}</span>
            </li>
          ))}
        </ul>
        <div className="flex justify-end">
          <Button type="button" onClick={onClose} data-testid="add-payment-done">
            Done
          </Button>
        </div>
      </div>
    );
  }

  const quotePanel = (
    <QuoteSummary
      quote={quote}
      loading={quoteLoading}
      error={quoteCurrent && quoteQuery.isError ? quoteQuery.error : null}
      hasItems={items.length > 0}
      discountCents={discountCents}
      netCents={netCents}
      currency={currency}
    />
  );

  if (phase === "confirm" && quote) {
    const values = getValues();
    return (
      <div className="flex flex-col gap-4 p-4" data-testid="add-payment-confirm">
        <p className="font-medium text-foreground">Confirm this payment</p>
        <dl className="grid grid-cols-1 gap-2 sm:grid-cols-2">
          <SummaryItem label="Student" value={context.student.name} />
          <SummaryItem
            label="Course(s)"
            value={courseIds.filter((id) => coursesWithItems.has(id)).map((id) => courseById.get(id)?.title).join(", ")}
          />
          <SummaryItem label="Month(s)" value={selectedMonths.map(monthText).join(", ")} />
          <SummaryItem label="Payment method" value={paymentMethodLabel(values.method)} />
          <SummaryItem label="Received on" value={values.receivedOn} />
          {values.reference.trim() ? <SummaryItem label="Reference" value={values.reference.trim()} /> : null}
          <SummaryItem
            label="Net amount"
            value={<span className="text-base font-semibold">{formatCurrency(centsToDecimal(netCents), currency)}</span>}
            testId="confirm-net"
          />
        </dl>
        {quotePanel}
        <SubmitErrorAlert error={submitError} />
        <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
          <Button
            type="button"
            variant="outline"
            onClick={() => {
              setPhase("form");
              setSubmitError(null);
            }}
            disabled={record.isPending}
          >
            Back
          </Button>
          <Button
            type="button"
            onClick={() => void confirm()}
            disabled={record.isPending || quoteLoading}
            aria-busy={record.isPending}
            data-testid="add-payment-confirm-submit"
          >
            {record.isPending ? "Recording…" : "Confirm & record payment"}
          </Button>
        </div>
      </div>
    );
  }

  return (
    <form
      className="flex flex-col gap-4 p-4"
      noValidate
      onSubmit={(event) => void review(event)}
      data-testid="add-payment-form"
    >
      {monthlyCourses.length === 0 ? (
        <Alert>
          <AlertCircle aria-hidden="true" />
          <AlertDescription>
            {context.student.name} has no monthly-priced courses, so there is nothing to record here.
          </AlertDescription>
        </Alert>
      ) : null}

      <div className="flex flex-col gap-1.5">
        <Label htmlFor="add-payment-courses">Course(s) *</Label>
        <MultiSelect
          id="add-payment-courses"
          testId="add-payment-courses"
          options={monthlyCourses.map((course) => ({ value: course.courseId, label: course.title }))}
          value={courseIds}
          onChange={(next) => setArray("courseIds", next)}
          placeholder="Select courses"
          searchPlaceholder="Type to filter courses…"
          emptyText="No monthly courses for this student."
          invalid={!!errors.courseIds}
          aria-describedby="add-payment-courses-hint add-payment-courses-error"
        />
        <p id="add-payment-courses-hint" className="text-xs text-muted-foreground">
          Select multiple courses to pay at once
        </p>
        <FieldError id="add-payment-courses-error" message={errors.courseIds?.message} />
      </div>

      <fieldset className="flex flex-col gap-1.5" aria-describedby="add-payment-months-error" data-testid="add-payment-months">
        <legend className="mb-1.5 text-sm font-medium">Month *</legend>
        {courseIds.length === 0 ? (
          <p className="text-xs text-muted-foreground">Select a course to see its months.</p>
        ) : offersLoading ? (
          <p className="text-xs text-muted-foreground" role="status">
            Loading months…
          </p>
        ) : offersError ? (
          <p className="text-xs text-destructive" role="alert">
            {manualPaymentErrorMessage(offersError)}
          </p>
        ) : monthOptions.length === 0 ? (
          <p className="text-xs text-muted-foreground">No billing months are set up for the selected courses.</p>
        ) : (
          <div className="grid max-h-48 grid-cols-1 gap-1 overflow-y-auto rounded-lg border border-border p-2 sm:grid-cols-2">
            {monthOptions.map((option) => {
              const disabled = option.availableCourseIds.length === 0;
              const id = `add-payment-month-${option.key}`;
              return (
                <label
                  key={option.key}
                  htmlFor={id}
                  className={`flex items-center gap-2 rounded-md px-2 py-1 ${disabled ? "text-muted-foreground" : "hover:bg-muted"}`}
                >
                  <input
                    id={id}
                    type="checkbox"
                    className="size-4 accent-blue-600"
                    disabled={disabled}
                    checked={!disabled && monthKeys.includes(option.key)}
                    onChange={(event) =>
                      setArray(
                        "monthKeys",
                        event.target.checked
                          ? [...monthKeys, option.key]
                          : monthKeys.filter((key) => key !== option.key)
                      )
                    }
                  />
                  <span>
                    {monthText(option.key)}
                    {option.blockedLabel ? ` – ${option.blockedLabel}` : ""}
                  </span>
                </label>
              );
            })}
          </div>
        )}
        <FieldError id="add-payment-months-error" message={errors.monthKeys?.message} />
        {coveredWarnings.length > 0 ? (
          <ul className="flex flex-col gap-1" data-testid="free-card-warning">
            {coveredWarnings.map((text) => (
              <li key={text} className="flex items-start gap-1.5 text-xs text-amber-800 dark:text-amber-200">
                <AlertTriangle className="mt-0.5 size-3.5 shrink-0" aria-hidden="true" />
                <span>
                  {text}: Covered by a free card — recording a payment is still allowed
                </span>
              </li>
            ))}
          </ul>
        ) : null}
      </fieldset>

      {postageCourses.length > 0 ? (
        <fieldset className="flex flex-col gap-1">
          <legend className="mb-1 text-sm font-medium">Postage</legend>
          {postageCourses.map((course) => {
            const id = `add-payment-postage-${course.courseId}`;
            const enabled = coursesWithItems.has(course.courseId);
            return (
              <label key={course.courseId} htmlFor={id} className="flex items-center gap-2">
                <input
                  id={id}
                  type="checkbox"
                  className="size-4 accent-blue-600"
                  disabled={!enabled}
                  checked={enabled && postage.includes(course.courseId)}
                  onChange={(event) => {
                    if (event.target.checked) {
                      setArray("postage", [...postage, course.courseId]);
                      // A staff discount can't be combined with postage (422 ADJUSTMENT_WITH_POSTAGE_UNSUPPORTED).
                      setDiscount("");
                      setValue("reason", "");
                      clearErrors("reason");
                    } else {
                      setArray("postage", postage.filter((entry) => entry !== course.courseId));
                    }
                  }}
                />
                Postage for {course.title} ({formatCurrency(course.postageFee, course.currency)})
              </label>
            );
          })}
        </fieldset>
      ) : null}

      <div className="flex flex-col gap-1.5">
        <Label htmlFor="add-payment-discount">Discount ({currency})</Label>
        <div className="flex gap-2">
          <Input
            id="add-payment-discount"
            inputMode="decimal"
            value={postageSelected ? "" : discount}
            placeholder="0"
            disabled={postageSelected}
            onChange={(event) => setDiscount(event.target.value)}
            aria-invalid={!!errors.discount || undefined}
            aria-describedby="add-payment-discount-hint add-payment-discount-error"
            data-testid="add-payment-discount"
          />
          <Button
            type="button"
            variant="outline"
            size="icon"
            aria-label="Reset discount"
            disabled={postageSelected}
            onClick={() => {
              setDiscount("");
              setValue("reason", "");
            }}
          >
            <RotateCcw aria-hidden="true" />
          </Button>
        </div>
        <div className="flex flex-wrap gap-2">
          {DISCOUNT_CHIPS.map((pct) => (
            <Button
              key={pct}
              type="button"
              variant="secondary"
              size="sm"
              disabled={postageSelected || !Number.isFinite(subtotalCents)}
              onClick={() => setDiscount(centsToDecimal(Math.round((subtotalCents * pct) / 100)))}
              data-testid={`add-payment-chip-${pct}`}
            >
              {pct}% Discount
            </Button>
          ))}
        </div>
        <p id="add-payment-discount-hint" className="text-xs text-muted-foreground">
          {postageSelected
            ? ADJUSTMENT_WITH_POSTAGE_MESSAGE
            : "Optional. Percentages apply to the course fees. Recorded with your reason; the student is not charged it."}
        </p>
        <FieldError id="add-payment-discount-error" message={errors.discount?.message} />
      </div>

      {!postageSelected && discountCents > 0 ? (
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="add-payment-reason">Discount reason *</Label>
          <Textarea
            id="add-payment-reason"
            {...register("reason")}
            aria-invalid={!!errors.reason || undefined}
            aria-describedby="add-payment-reason-error"
            data-testid="add-payment-reason"
          />
          <FieldError id="add-payment-reason-error" message={errors.reason?.message} />
        </div>
      ) : null}

      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="add-payment-method">Payment Method *</Label>
          <select
            id="add-payment-method"
            className={NATIVE_SELECT_CLASS}
            {...register("method")}
            aria-invalid={!!errors.method || undefined}
            aria-describedby="add-payment-method-error"
            data-testid="add-payment-method"
          >
            <option value="">Select…</option>
            {MANUAL_PAYMENT_METHODS.map((entry) => (
              <option key={entry.value} value={entry.value}>
                {entry.label}
              </option>
            ))}
          </select>
          <FieldError id="add-payment-method-error" message={errors.method?.message} />
        </div>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="add-payment-received-on">Received on *</Label>
          <Input
            id="add-payment-received-on"
            type="date"
            min={minReceivedOn}
            max={today}
            {...register("receivedOn")}
            aria-invalid={!!errors.receivedOn || undefined}
            aria-describedby="add-payment-received-on-error"
            data-testid="add-payment-received-on"
          />
          <FieldError id="add-payment-received-on-error" message={errors.receivedOn?.message} />
        </div>
      </div>

      <div className="flex flex-col gap-1.5">
        <Label htmlFor="add-payment-reference">Reference Number</Label>
        <Input
          id="add-payment-reference"
          maxLength={100}
          {...register("reference")}
          aria-invalid={!!errors.reference || undefined}
          aria-describedby="add-payment-reference-error"
          data-testid="add-payment-reference"
        />
        <FieldError id="add-payment-reference-error" message={errors.reference?.message} />
      </div>

      <div className="flex flex-col gap-1.5">
        <Label htmlFor="add-payment-notes">Notes</Label>
        <Textarea
          id="add-payment-notes"
          maxLength={1000}
          {...register("notes")}
          aria-invalid={!!errors.notes || undefined}
          aria-describedby="add-payment-notes-error"
          data-testid="add-payment-notes"
        />
        <FieldError id="add-payment-notes-error" message={errors.notes?.message} />
      </div>

      {quotePanel}
      <SubmitErrorAlert error={submitError} />

      <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
        <Button type="button" variant="outline" onClick={onClose}>
          Cancel
        </Button>
        <Button type="submit" disabled={monthlyCourses.length === 0} data-testid="add-payment-review">
          Add Payment
        </Button>
      </div>
    </form>
  );
}

function SummaryItem({ label, value, testId }: { label: string; value: ReactNode; testId?: string }) {
  return (
    <div className="flex flex-col">
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className="break-words text-foreground" data-testid={testId}>
        {value}
      </dd>
    </div>
  );
}

function SubmitErrorAlert({ error }: { error: { code: string | null; message: string } | null }) {
  if (!error) return null;
  return (
    <Alert variant="destructive" data-testid="add-payment-error">
      <AlertCircle aria-hidden="true" />
      <AlertDescription>
        {error.message}
        {error.code === "ITEM_PENDING" ? (
          <>
            {" "}
            <Link href="/tenant-admin/payments?tab=slips" className="font-medium underline">
              Open Payment Slips
            </Link>
          </>
        ) : null}
      </AlertDescription>
    </Alert>
  );
}

function QuoteSummary({
  quote,
  loading,
  error,
  hasItems,
  discountCents,
  netCents,
  currency,
}: {
  quote: CheckoutQuote | undefined;
  loading: boolean;
  error: unknown;
  hasItems: boolean;
  discountCents: number;
  netCents: number;
  currency: string;
}) {
  if (!hasItems) {
    return (
      <p className="text-right text-base text-muted-foreground" data-testid="add-payment-net">
        Net Amount: {formatCurrency(0, currency)}
      </p>
    );
  }
  if (error) {
    return (
      <Alert variant="destructive" data-testid="add-payment-quote-error">
        <AlertCircle aria-hidden="true" />
        <AlertDescription>
          {manualPaymentErrorMessage(error)}
          {isApiClientError(error) && error.code === "ITEM_PENDING" ? (
            <>
              {" "}
              <Link href="/tenant-admin/payments?tab=slips" className="font-medium underline">
                Open Payment Slips
              </Link>
            </>
          ) : null}
        </AlertDescription>
      </Alert>
    );
  }
  if (!quote) {
    return (
      <p className="text-right text-sm text-muted-foreground" role="status">
        Calculating total…
      </p>
    );
  }
  const amount = (value: Parameters<typeof formatCurrency>[0]) => formatCurrency(value, quote.currency);
  return (
    <div className="flex flex-col gap-2 rounded-lg border border-border p-3" aria-busy={loading} data-testid="add-payment-quote">
      <ul className="flex flex-col gap-1" aria-label="Quoted items">
        {quote.lines.map((line) => (
          <li key={line.lineNo} className="flex justify-between gap-2">
            <span className="min-w-0">
              {line.courseTitle ? `${line.courseTitle} – ` : ""}
              {line.label}
              {line.discountName ? <span className="text-xs text-muted-foreground"> ({line.discountName})</span> : null}
            </span>
            <span className="shrink-0">{amount(line.lineTotal)}</span>
          </li>
        ))}
      </ul>
      <dl className="flex flex-col gap-0.5 border-t border-border pt-2 text-sm">
        <Row label="Course fees" value={amount(quote.subtotal)} />
        {toCents(quote.facilityFeeTotal) > 0 ? <Row label="Facility fee" value={amount(quote.facilityFeeTotal)} /> : null}
        {toCents(quote.postageTotal) > 0 ? <Row label="Postage" value={amount(quote.postageTotal)} /> : null}
        {toCents(quote.discountTotal) > 0 ? <Row label="Automatic discounts" value={`− ${amount(quote.discountTotal)}`} /> : null}
        <Row label="Total" value={amount(quote.total)} testId="add-payment-total" />
        {discountCents > 0 ? <Row label="Discount" value={`− ${amount(centsToDecimal(discountCents))}`} /> : null}
      </dl>
      <p className="text-right text-lg font-medium text-foreground" data-testid="add-payment-net">
        Net Amount: {Number.isFinite(netCents) ? amount(centsToDecimal(netCents)) : "—"}
      </p>
    </div>
  );
}

function Row({ label, value, testId }: { label: string; value: string; testId?: string }) {
  return (
    <div className="flex justify-between gap-2">
      <dt className="text-muted-foreground">{label}</dt>
      <dd data-testid={testId}>{value}</dd>
    </div>
  );
}
