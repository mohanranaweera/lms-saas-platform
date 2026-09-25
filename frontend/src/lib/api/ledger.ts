import { useQuery } from "@tanstack/react-query";
import { useAuth } from "@/lib/auth/auth-context";
import type { PageResponse } from "./courses";

/**
 * Typed client + React Query hooks for `ledger-settlement-management`'s
 * authenticated read endpoints (`/api/v1/ledger/**`). Follows
 * `lib/api/courses.ts`'s conventions (`/v1/...` paths — the client's
 * `NEXT_PUBLIC_API_BASE_URL` already includes the `/api` prefix —
 * `authorizedFetch("tenant", ...)`).
 *
 * Every screen reading these endpoints must treat the ledger — never
 * `order`/`payment` state directly — as the source of truth for "what was
 * actually paid/refunded," per `.claude/rules/payments.md` §2/§4.
 */

export type LedgerEntryType = "PAYMENT_CONFIRMED" | "REFUND";

/**
 * Mirrors `PaymentOperationalState` (Wave 6 §1.3/§3.2) — a computed,
 * read-model-only projection, never a persisted column. `UNDER_REVIEW`
 * collapses both `SUBMITTED`/`UNDER_REVIEW` slip sub-states into one value —
 * this client never needs (and must never invent) the sub-distinction.
 */
export type PaymentOperationalState =
  | "UNPAID"
  | "PENDING"
  | "UNDER_REVIEW"
  | "PAID"
  | "REJECTED"
  | "REFUNDED";

/**
 * Mirrors `PaymentMethod` (Wave 6 §4/§10 judgment call 2) — derived
 * server-side from the confirmed payment's `gatewayReference` prefix
 * convention, never recomputed here.
 */
export type PaymentMethod = "GATEWAY" | "MANUAL_SLIP" | "FREE" | "STAFF_GRANTED";

/**
 * Mirrors `LedgerHistoryEntryResponse`. Wave 6 §4 extends this with
 * `courseId`/`courseTitle`/`billingPeriodId`/`operationalState`/`method`/
 * `reference` — additive, on-the-wire-nullable fields (the backend's own
 * DTO javadoc notes these are structurally unreachable as `null` via any
 * controller after this wave, but kept nullable defensively here too, per
 * `.claude/rules/frontend.md`'s "don't paper over a shape mismatch" —
 * treating a real gap as `null` rather than assuming non-null). `courseId`/
 * `billingPeriodId` are opaque traceability ids only — this client never
 * resolves a period's date range from them; render `courseTitle` directly
 * and a short id for the billing period (see `shortId` in `lib/format.ts`).
 */
export interface LedgerHistoryEntryResponse {
  id: string;
  orderId: string;
  paymentId: string | null;
  entryType: LedgerEntryType;
  amount: number;
  reversesEntryId: string | null;
  createdAt: string;
  courseId: string | null;
  courseTitle: string | null;
  /** `null` for `ONE_TIME`/`FREE`/`CUSTOM` pricing — only `MONTHLY`/`SESSION` orders resolve a billing period. */
  billingPeriodId: string | null;
  operationalState: PaymentOperationalState | null;
  method: PaymentMethod | null;
  reference: string | null;
}

export interface LedgerDashboardParams {
  page?: number;
  size?: number;
  sort?: string;
  /** Wave 6 §4 — optional `PaymentOperationalState` filter; omitted returns every entry. */
  status?: PaymentOperationalState;
  /** Wave 6 §4 — optional `PaymentMethod` filter; combinable with `status` (AND). */
  method?: PaymentMethod;
}

/** Mirrors `OutstandingOrderResponse` (`GET /api/v1/ledger/outstanding`). */
export interface OutstandingOrderResponse {
  orderId: string;
  studentId: string;
  courseId: string;
  courseTitle: string | null;
  amount: number;
  currency: string;
  operationalState: PaymentOperationalState;
}

export interface LedgerOutstandingParams {
  page?: number;
  size?: number;
}

/** Mirrors `CoursePaymentSummaryResponse`/`CoursePaymentSummaryBucketResponse` (`GET /api/v1/ledger/courses/{courseId}/summary`). */
export interface CoursePaymentSummaryBucketResponse {
  state: PaymentOperationalState;
  count: number;
  totalAmount: number;
}

export interface CoursePaymentSummaryResponse {
  courseId: string;
  courseTitle: string | null;
  totalOrders: number;
  byState: CoursePaymentSummaryBucketResponse[];
}

export const ledgerKeys = {
  all: ["ledger"] as const,
  history: () => [...ledgerKeys.all, "history"] as const,
  dashboard: (params?: LedgerDashboardParams) =>
    [...ledgerKeys.all, "dashboard", params ?? {}] as const,
  outstanding: (params?: LedgerOutstandingParams) =>
    [...ledgerKeys.all, "outstanding", params ?? {}] as const,
  courseSummary: (courseId: string) => [...ledgerKeys.all, "course-summary", courseId] as const,
};

/**
 * `GET /api/v1/ledger/history` — Student role only, always the caller's own
 * history (no id param exists). Returns a bare, unpaginated array.
 */
export function useLedgerHistory() {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: ledgerKeys.history(),
    queryFn: () =>
      authorizedFetch<LedgerHistoryEntryResponse[]>("tenant", "/v1/ledger/history"),
  });
}

function buildDashboardQuery(params?: LedgerDashboardParams): string {
  const search = new URLSearchParams();
  search.set("page", String(params?.page ?? 0));
  search.set("size", String(params?.size ?? 20));
  search.set("sort", params?.sort ?? "createdAt,desc");
  if (params?.status) search.set("status", params.status);
  if (params?.method) search.set("method", params.method);
  return `?${search.toString()}`;
}

/**
 * `GET /api/v1/ledger/dashboard` — any authenticated caller; server enforces
 * `PAYMENTS_SLIPS`/`VIEW` (Tenant Admin, Finance Staff, Student Support,
 * Read-only Auditor; 403 for everyone else). Spring `Pageable` params.
 *
 * `options.enabled` (default `true`), mirroring `useCourseLessons`'s
 * convention in `lib/api/courses.ts` — lets a caller that already knows,
 * client-side, that the current role lacks `PAYMENTS_SLIPS`/`VIEW` (a pure
 * UX-visibility decision, e.g. `canViewPaymentDashboard(role)`) skip firing
 * this request entirely rather than triggering a guaranteed 403. Backend
 * enforcement is unchanged and remains authoritative regardless of this flag.
 */
export function useLedgerDashboard(
  params?: LedgerDashboardParams,
  options?: { enabled?: boolean }
) {
  const { authorizedFetch } = useAuth();
  const queryString = buildDashboardQuery(params);
  return useQuery({
    queryKey: ledgerKeys.dashboard(params),
    queryFn: () =>
      authorizedFetch<PageResponse<LedgerHistoryEntryResponse>>(
        "tenant",
        `/v1/ledger/dashboard${queryString}`
      ),
    enabled: options?.enabled ?? true,
  });
}

/**
 * `GET /v1/students/{id}/ledger` (Wave 3, staff-facing, studentId-scoped —
 * lives in `ledger-settlement-management`, the owning domain of
 * `ledger_entry`, not duplicated into `user-management`, per the wave-03 plan
 * §4). Plain array, no pagination — matches `StudentLedgerController`. Query
 * key intentionally matches the shape `lib/api/students.ts#useEnrollStudent`'s
 * `onSuccess` invalidates (`["students", id, "ledger"]`).
 */
export function useStudentLedger(studentId: string) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: ["students", studentId, "ledger"],
    queryFn: () =>
      authorizedFetch<LedgerHistoryEntryResponse[]>("tenant", `/v1/students/${studentId}/ledger`),
    enabled: studentId.length > 0,
  });
}

function buildOutstandingQuery(params?: LedgerOutstandingParams): string {
  const search = new URLSearchParams();
  search.set("page", String(params?.page ?? 0));
  search.set("size", String(params?.size ?? 20));
  return `?${search.toString()}`;
}

/**
 * `GET /api/v1/ledger/outstanding` (Wave 6 §4) — tenant-admin,
 * `PAYMENTS_SLIPS`/`VIEW`-gated, paginated. Orders with no `PAID`/`REFUNDED`
 * operational state (i.e. `UNPAID`/`PENDING`/`UNDER_REVIEW`/`REJECTED`).
 */
export function useLedgerOutstanding(params?: LedgerOutstandingParams) {
  const { authorizedFetch } = useAuth();
  const queryString = buildOutstandingQuery(params);
  return useQuery({
    queryKey: ledgerKeys.outstanding(params),
    queryFn: () =>
      authorizedFetch<PageResponse<OutstandingOrderResponse>>(
        "tenant",
        `/v1/ledger/outstanding${queryString}`
      ),
  });
}

/**
 * `GET /api/v1/ledger/courses/{courseId}/summary` (Wave 6 §4) — tenant-admin,
 * `PAYMENTS_SLIPS`/`VIEW`-gated. `enabled` defaults to whether `courseId` is
 * non-empty — the Course Payment Summary screen only fires this once a
 * course has actually been picked from its selector, never speculatively.
 */
export function useLedgerCourseSummary(courseId: string, options?: { enabled?: boolean }) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: ledgerKeys.courseSummary(courseId),
    queryFn: () =>
      authorizedFetch<CoursePaymentSummaryResponse>(
        "tenant",
        `/v1/ledger/courses/${courseId}/summary`
      ),
    enabled: (options?.enabled ?? true) && courseId.length > 0,
  });
}
