import { useMutation, useQueries, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "@/lib/auth/auth-context";
import { isApiClientError } from "./error";
import type {
  CheckoutOffers,
  CheckoutQuote,
  MoneyValue,
  PurchaseScope,
  QuoteItemRequest,
} from "./klass-student";

/**
 * ADR-030 staff manual payments (`/api/v1/staff/students/{studentId}/**`,
 * plan §4 of `docs/plans/klass-manual-payment-free-card.md`).
 *
 * `{studentId}` is a student_profile id that the backend resolves inside the
 * caller's tenant (404 otherwise) - the page's `?studentId=` / `?courseId=`
 * query params are selection inputs only, never trusted. No tenant, student
 * or price field is ever sent in a body: amounts come from the server quote;
 * `expectedReceivedAmount` is only compared server-side (409 AMOUNT_MISMATCH).
 * Reads need `PAYMENTS_SLIPS/VIEW`, quote/record `PAYMENTS_SLIPS/APPROVE` -
 * enforced by the backend regardless of what the UI shows.
 */

export type HistoryStatus = "RECEIVED" | "PENDING_SLIP" | "PENDING_ONLINE" | "REJECTED";
export type HistorySource = "MANUAL" | "SLIP" | "GATEWAY" | "OTHER";
export type ManualPaymentMethod = "CASH" | "BANK_TRANSFER" | "CARD";

export const MANUAL_PAYMENT_METHODS: { value: ManualPaymentMethod; label: string }[] = [
  { value: "CASH", label: "Cash" },
  { value: "BANK_TRANSFER", label: "Bank Transfer" },
  { value: "CARD", label: "Card" },
];

export function paymentMethodLabel(method: string | null | undefined): string {
  return MANUAL_PAYMENT_METHODS.find((entry) => entry.value === method)?.label ?? method ?? "—";
}

export interface StaffStudentInfo {
  studentId: string;
  name: string;
  email: string | null;
  mobile: string | null;
}

export interface PaymentContextCourse {
  courseId: string;
  title: string;
  pricingModel: string;
  monthlyAmount: MoneyValue | null;
  currency: string;
  postageFee: MoneyValue | null;
}

/** `GET .../payment-context`: the student + their (non-draft) course participations, title order. */
export interface PaymentContext {
  student: StaffStudentInfo;
  /** Tenant timezone (IANA id) - "today" for the received-on date. */
  timezone: string;
  courses: PaymentContextCourse[];
}

export interface ReceiptLine {
  lineNo: number;
  courseId: string | null;
  courseTitle: string | null;
  scope: PurchaseScope;
  label: string;
  year: number | null;
  month: number | null;
  lineTotal: MoneyValue;
}

export interface HistoryReceipt {
  receiptId: string;
  method: ManualPaymentMethod | string;
  referenceNumber: string | null;
  receivedOn: string;
  notes: string | null;
  adjustmentReason: string | null;
  recordedBy: string;
  /** Best-effort staff email; may be null. */
  recordedByName: string | null;
  recordedAt: string;
}

export interface HistoryEntry {
  orderId: string;
  createdAt: string;
  status: HistoryStatus;
  source: HistorySource;
  currency: string;
  orderTotal: MoneyValue;
  receivedAmount: MoneyValue | null;
  staffAdjustmentAmount: MoneyValue | null;
  refundedAmount: MoneyValue | null;
  paymentId: string | null;
  slipId: string | null;
  receipt: HistoryReceipt | null;
  lines: ReceiptLine[];
}

export interface ManualPaymentQuoteRequest {
  items: QuoteItemRequest[];
  postageCourseIds: string[];
}

/** `POST .../manual-payments` body. Money as decimal strings; the server recomputes everything. */
export interface ManualPaymentRecordRequest extends ManualPaymentQuoteRequest {
  quoteToken: string;
  staffAdjustmentAmount: string;
  adjustmentReason: string | null;
  expectedReceivedAmount: string;
  paymentMethod: ManualPaymentMethod;
  receivedOn: string;
  referenceNumber: string | null;
  notes: string | null;
  idempotencyKey: string;
}

/** 201 new receipt / 200 replay (`replay = true`). */
export interface ManualPaymentReceipt {
  receiptId: string;
  orderId: string;
  paymentId: string;
  currency: string;
  expectedAmount: MoneyValue;
  staffAdjustmentAmount: MoneyValue;
  receivedAmount: MoneyValue;
  paymentMethod: ManualPaymentMethod | string;
  referenceNumber: string | null;
  receivedOn: string;
  notes: string | null;
  adjustmentReason: string | null;
  recordedBy: string;
  recordedAt: string;
  lines: ReceiptLine[];
  replay: boolean;
}

export const staffPaymentKeys = {
  all: ["staff-payments"] as const,
  student: (studentId: string) => [...staffPaymentKeys.all, studentId] as const,
  context: (studentId: string) => [...staffPaymentKeys.student(studentId), "context"] as const,
  offers: (studentId: string, courseId: string, scope: PurchaseScope) =>
    [...staffPaymentKeys.student(studentId), "offers", courseId, scope] as const,
  history: (studentId: string, courseId: string) =>
    [...staffPaymentKeys.student(studentId), "history", courseId] as const,
  quote: (studentId: string, body: ManualPaymentQuoteRequest) =>
    [...staffPaymentKeys.student(studentId), "quote", body] as const,
};

/** 4xx responses are answers, not transient failures - don't retry them. */
function retryUnlessClientError(failureCount: number, error: unknown): boolean {
  if (isApiClientError(error) && error.status >= 400 && error.status < 500) return false;
  return failureCount < 2;
}

function base(studentId: string): string {
  return `/v1/staff/students/${encodeURIComponent(studentId)}`;
}

/** `GET .../payment-context` - 404 when the id isn't a student of the caller's institute. */
export function useStaffPaymentContext(studentId: string | null) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: staffPaymentKeys.context(studentId ?? ""),
    queryFn: () => authorizedFetch<PaymentContext>("tenant", `${base(studentId ?? "")}/payment-context`),
    enabled: Boolean(studentId),
    retry: retryUnlessClientError,
  });
}

function offersPath(studentId: string, courseId: string, scope: PurchaseScope): string {
  const search = new URLSearchParams({ courseId, scope });
  return `${base(studentId)}/payment-offers?${search.toString()}`;
}

/** `GET .../payment-offers?courseId=&scope=` (all years) - the existing `CheckoutOffers` shape for that student. */
export function useStaffPaymentOffers(studentId: string | null, courseId: string | null, scope: PurchaseScope) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: staffPaymentKeys.offers(studentId ?? "", courseId ?? "", scope),
    queryFn: () => authorizedFetch<CheckoutOffers>("tenant", offersPath(studentId ?? "", courseId ?? "", scope)),
    enabled: Boolean(studentId) && Boolean(courseId),
    retry: retryUnlessClientError,
  });
}

/** Offers for several courses at once (the Add Payment month picker). Same cache entries as `useStaffPaymentOffers`. */
export function useStaffPaymentOffersForCourses(studentId: string, courseIds: string[], scope: PurchaseScope) {
  const { authorizedFetch } = useAuth();
  return useQueries({
    queries: courseIds.map((courseId) => ({
      queryKey: staffPaymentKeys.offers(studentId, courseId, scope),
      queryFn: () => authorizedFetch<CheckoutOffers>("tenant", offersPath(studentId, courseId, scope)),
      retry: retryUnlessClientError,
    })),
  });
}

/** `GET .../payment-history?courseId=` - newest first; only orders with a line for the course. */
export function useStaffPaymentHistory(studentId: string | null, courseId: string | null) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: staffPaymentKeys.history(studentId ?? "", courseId ?? ""),
    queryFn: () =>
      authorizedFetch<HistoryEntry[]>(
        "tenant",
        `${base(studentId ?? "")}/payment-history?${new URLSearchParams({ courseId: courseId ?? "" }).toString()}`
      ),
    enabled: Boolean(studentId) && Boolean(courseId),
    retry: retryUnlessClientError,
  });
}

/**
 * `POST .../manual-payments/quote` - a server re-price of the selection,
 * modelled as a query keyed by the selection so a stale answer for an old
 * selection is never shown as the current one.
 */
export function useStaffManualPaymentQuote(studentId: string, body: ManualPaymentQuoteRequest, enabled: boolean) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: staffPaymentKeys.quote(studentId, body),
    queryFn: () =>
      authorizedFetch<CheckoutQuote>("tenant", `${base(studentId)}/manual-payments/quote`, {
        method: "POST",
        body: JSON.stringify(body),
      }),
    enabled: enabled && body.items.length > 0,
    retry: retryUnlessClientError,
    staleTime: 0,
  });
}

/** Mutation key of `useRecordManualPayment` (lets the dialog shell block dismissal while in flight). */
export const RECORD_MANUAL_PAYMENT_KEY = ["staff-payments", "record"] as const;

/**
 * `POST .../manual-payments` - records a staff-confirmed receipt (201) or
 * replays the receipt of the same idempotency key (200). Never shown as
 * saved unless this resolves.
 */
export function useRecordManualPayment(studentId: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationKey: RECORD_MANUAL_PAYMENT_KEY,
    mutationFn: (body: ManualPaymentRecordRequest) =>
      authorizedFetch<ManualPaymentReceipt>("tenant", `${base(studentId)}/manual-payments`, {
        method: "POST",
        body: JSON.stringify(body),
      }),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: staffPaymentKeys.student(studentId) }),
  });
}

/** The fresh quote a 409 `QUOTE_CHANGED` carries in `error.details.quote`, else null. */
export function freshQuoteFromError(error: unknown): CheckoutQuote | null {
  if (!isApiClientError(error) || error.code !== "QUOTE_CHANGED") return null;
  const quote = error.details?.quote;
  if (!quote || typeof quote !== "object" || !Array.isArray((quote as CheckoutQuote).lines)) return null;
  return quote as CheckoutQuote;
}

/** 422 `ADJUSTMENT_WITH_POSTAGE_UNSUPPORTED` - a staff discount can't be combined with postage. */
export const ADJUSTMENT_WITH_POSTAGE_MESSAGE = "A discount can't be combined with postage";

/** Staff-facing copy for a manual payment quote/record failure. */
export function manualPaymentErrorMessage(error: unknown): string {
  if (!isApiClientError(error)) return "Something went wrong. Nothing was recorded. Please try again.";
  switch (error.code) {
    case "QUOTE_CHANGED":
      return "The amount changed since you reviewed it. Nothing was recorded - check the new total and confirm again.";
    case "AMOUNT_MISMATCH":
      return "The amount you confirmed doesn't match the server's amount. Nothing was recorded - review the total and try again.";
    case "ITEM_PENDING":
      return "One of the selected months already has a student payment waiting (a slip under review or an online payment in progress). Nothing was recorded. Approve or reject it in Payment Slips instead.";
    case "ITEM_ALREADY_PAID":
      return "One of the selected months is already paid. Nothing was recorded - remove it and try again.";
    case "ITEM_NOT_ELIGIBLE":
      return "One of the selected months can't be paid for right now. Remove it and try again.";
    case "EMPTY_SELECTION":
      return "Select at least one course and month.";
    case "QUOTE_REQUIRED":
      return "The total needs to be recalculated. Review it and confirm again.";
    case "ADJUSTMENT_REASON_REQUIRED":
      return "Enter a reason for the discount.";
    case "ADJUSTMENT_TOO_LARGE":
      return "The discount must be less than the total. To give the months for free, issue a Free Card instead.";
    case "ADJUSTMENT_WITH_POSTAGE_UNSUPPORTED":
      return ADJUSTMENT_WITH_POSTAGE_MESSAGE;
    case "IDEMPOTENCY_KEY_REUSED":
      return "This form was already used for a different payment. Close it and start again.";
    case "CURRENCY_MISMATCH":
      return error.message || "These courses are priced in different currencies. Record them separately.";
    case "POSTAGE_COURSE_NOT_IN_CART":
      return "Postage was selected for a course with no months selected. Untick it or select a month.";
    case "DELIVERY_DETAILS_REQUIRED":
      return "Postage needs a delivery address on the student's record. Untick postage or add the address first.";
    case "NETWORK_ERROR":
      return "We couldn't reach the server. Nothing was confirmed - try again (it won't be recorded twice).";
    default:
      if (error.status === 403) return "You don't have permission to record payments.";
      if (error.status === 404) return "This student or course was not found in your institute.";
      return error.message || "Something went wrong. Nothing was recorded. Please try again.";
  }
}

// ---------------------------------------------------------------------------
// Display helpers (formatting/arithmetic for display only)
// ---------------------------------------------------------------------------

/** Money string → integer cents (NaN for invalid input). */
export function toCents(value: MoneyValue | null | undefined): number {
  if (value === null || value === undefined || value === "") return Number.NaN;
  const parsed = typeof value === "number" ? value : Number(String(value).trim());
  return Number.isFinite(parsed) ? Math.round(parsed * 100) : Number.NaN;
}

/** Integer cents → "1234.50" (the decimal string the API takes). */
export function centsToDecimal(cents: number): string {
  const sign = cents < 0 ? "-" : "";
  const abs = Math.abs(cents);
  return `${sign}${Math.floor(abs / 100)}.${String(abs % 100).padStart(2, "0")}`;
}

/** "YYYY-MM-DD" shifted by `days` (calendar arithmetic, UTC-safe). */
export function addDays(isoDate: string, days: number): string {
  const [year, month, day] = isoDate.split("-").map(Number);
  const date = new Date(Date.UTC(year, month - 1, day + days));
  return date.toISOString().slice(0, 10);
}

/** "YYYY-MM-DD" of today in the given IANA timezone (falls back to the browser's). */
export function todayIn(timezone: string | null | undefined): string {
  try {
    return new Intl.DateTimeFormat("en-CA", {
      timeZone: timezone || undefined,
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
    }).format(new Date());
  } catch {
    return new Intl.DateTimeFormat("en-CA", { year: "numeric", month: "2-digit", day: "2-digit" }).format(new Date());
  }
}
