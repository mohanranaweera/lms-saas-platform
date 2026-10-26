import { keepPreviousData, useMutation, useQuery } from "@tanstack/react-query";
import { useAuth } from "@/lib/auth/auth-context";
import { reportKeys, type ReportPaymentMethod, type ReportPeriodMode } from "./reports";

/**
 * Typed client for RPT-02 Student Payment Report
 * (`/api/v1/reports/student-payments`, `docs/specs/student-payment-report.md`
 * §8 plus the as-implemented controller). Every endpoint is server-gated on
 * `FINANCE_EXPENSES`/`VIEW` and tenant-scoped from the authenticated context;
 * nothing here makes an authorization decision or sends a tenant id.
 *
 * Every money figure - row amounts, group subtotals, summary totals - is a
 * server-computed 2-dp decimal string. The UI only formats them; it never
 * sums rows or groups and never combines currencies.
 */

const BASE = "/v1/reports/student-payments";

export type StudentPaymentGroupBy = "NONE" | "STUDENT" | "COURSE" | "STUDENT_COURSE";
export type StudentPaymentDisplayMethod = "ONLINE" | "BANK_TRANSFER" | "CASH" | "CARD" | "UNKNOWN";

/** Filter labels (spec §3-R.5): GATEWAY is any online gateway payment, not only cards. */
export const STUDENT_PAYMENT_METHOD_FILTER_LABELS: Record<ReportPaymentMethod, string> = {
  GATEWAY: "Online (gateway)",
  MANUAL_SLIP: "Bank transfer (slip)",
  MANUAL_RECEIPT: "Manual receipt",
};

export function studentPaymentMethodFilterLabel(method: string | null | undefined): string {
  if (!method) return "All methods";
  return STUDENT_PAYMENT_METHOD_FILTER_LABELS[method as ReportPaymentMethod] ?? method;
}

/** Display label of a row / summary method (spec §3-R.5). */
export function studentPaymentDisplayMethodLabel(method: string, manual: boolean): string {
  if (manual) {
    if (method === "CASH") return "Manual – cash";
    if (method === "CARD") return "Manual – card";
    if (method === "BANK_TRANSFER") return "Manual – bank transfer";
  }
  if (method === "ONLINE") return "Online (gateway)";
  if (method === "BANK_TRANSFER") return "Bank transfer (slip)";
  if (method === "UNKNOWN") return "Unknown";
  return method;
}

export const STUDENT_PAYMENT_LIMITS = { maxCourses: 50, maxMonths: 36, maxSpanDays: 366 } as const;
export const STUDENT_PAYMENT_PAGE_SIZE = 50;

export interface ReportCourseRef {
  id: string;
  name: string;
}

export interface StudentPaymentOptions {
  courses: ReportCourseRef[];
  paymentMethods: string[];
  groupByModes: string[];
}

export interface StudentPaymentRunRequest {
  /** Empty / absent = all courses of the caller's tenant. */
  courseIds?: string[];
  paymentMethod?: ReportPaymentMethod;
  periodMode: ReportPeriodMode;
  months?: string[];
  startDate?: string;
  endDate?: string;
}

export interface StudentPaymentMethodTotal {
  method: string;
  manual: boolean;
  total: string;
  receiptCount: number;
}

export interface StudentPaymentSummary {
  studentsPaid: number;
  receiptCount: number;
  refundCount: number;
  rowCount: number;
  coursePaymentTotal: string;
  byMethod: StudentPaymentMethodTotal[];
  serviceFeeTotal: string;
  postageTotal: string;
  receivedTotal: string;
}

export interface StudentPaymentSection {
  currency: string;
  summary: StudentPaymentSummary;
}

export interface CountAmount {
  count: number;
  amount: string;
}

export interface StudentPaymentReconciliation {
  currency: string;
  grossCoursePayments: string;
  refunds: string;
  staffAdjustments: CountAmount;
  excludedNonCash: CountAmount;
  excludedNotMonthAttributable: CountAmount;
}

export interface StudentPaymentReport {
  runId: string;
  expiresAt: string;
  generatedAt: string;
  timezone: string;
  institute: { name: string };
  filters: {
    courses: ReportCourseRef[];
    paymentMethod: string | null;
    periodMode: ReportPeriodMode;
    months: string[] | null;
    startDate: string | null;
    endDate: string | null;
    /** Server-formatted; displayed as-is, never parsed. */
    periodLabel: string;
  };
  sections: StudentPaymentSection[];
  reconciliation: {
    byCurrency: StudentPaymentReconciliation[];
    notes: string[];
  };
  rowCount: number;
}

export interface StudentPaymentRow {
  rowNo: number;
  currency: string;
  entryId: string;
  receiptId: string;
  refund: boolean;
  studentId: string | null;
  studentName: string;
  /** String with leading zeros ("0084"); never treat as a number. */
  indexNumber: string | null;
  courseId: string | null;
  courseName: string;
  reason: string;
  scope: string;
  method: string;
  manual: boolean;
  /** `YYYY-MM-DD` when `dateOnly`, else an ISO-8601 UTC instant. */
  paymentDate: string;
  dateOnly: boolean;
  recordedAt: string;
  amount: string;
  serviceFee: string;
  postage: string;
}

export interface StudentPaymentGroup {
  level: "STUDENT" | "COURSE";
  key: string;
  parentKey: string | null;
  label: string;
  indexNumber: string | null;
  currency: string;
  coursePayment: string;
  serviceFee: string;
  postage: string;
  receiptCount: number;
  rowCount: number;
}

export interface StudentPaymentRowsPage {
  groupBy: StudentPaymentGroupBy;
  page: number;
  size: number;
  totalRows: number;
  totalPages: number;
  rows: StudentPaymentRow[];
  /** Whole-run subtotals of every group that has a row on this page. */
  groups: StudentPaymentGroup[];
}

export const studentPaymentKeys = {
  all: [...reportKeys.all, "student-payments"] as const,
  options: () => [...studentPaymentKeys.all, "options"] as const,
  rows: (runId: string, groupBy: StudentPaymentGroupBy, page: number, size: number) =>
    [...studentPaymentKeys.all, "rows", runId, groupBy, page, size] as const,
};

/** `GET /options` - courses of the caller's tenant, filterable methods, grouping modes. */
export function useStudentPaymentOptions() {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: studentPaymentKeys.options(),
    queryFn: () => authorizedFetch<StudentPaymentOptions>("tenant", `${BASE}/options`),
    retry: false,
  });
}

/** `POST /runs` - computes a short-lived (server-held) run. `signal` lets the screen abort an in-flight search. */
export function useCreateStudentPaymentRun() {
  const { authorizedFetch } = useAuth();
  return useMutation({
    mutationFn: ({ request, signal }: { request: StudentPaymentRunRequest; signal?: AbortSignal }) =>
      authorizedFetch<StudentPaymentReport>("tenant", `${BASE}/runs`, {
        method: "POST",
        body: JSON.stringify(request),
        signal,
      }),
  });
}

/**
 * `GET /runs/{runId}/rows` - one page of the stored run in the requested
 * grouping. A run is immutable for its lifetime, so a fetched page never goes
 * stale; changing grouping or page re-fetches the same run (no new run).
 */
export function useStudentPaymentRows(
  runId: string | null,
  groupBy: StudentPaymentGroupBy,
  page: number,
  size = STUDENT_PAYMENT_PAGE_SIZE
) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: studentPaymentKeys.rows(runId ?? "", groupBy, page, size),
    queryFn: () => {
      const params = new URLSearchParams({ groupBy, page: String(page), size: String(size) });
      return authorizedFetch<StudentPaymentRowsPage>(
        "tenant",
        `${BASE}/runs/${encodeURIComponent(runId ?? "")}/rows?${params.toString()}`
      );
    },
    enabled: !!runId,
    retry: false,
    staleTime: Infinity,
    gcTime: 5 * 60 * 1000,
    placeholderData: keepPreviousData,
  });
}

export function studentPaymentPdfPath(runId: string, groupBy: StudentPaymentGroupBy): string {
  return `${BASE}/runs/${encodeURIComponent(runId)}/pdf?groupBy=${encodeURIComponent(groupBy)}`;
}

export function toGroupBy(byStudent: boolean, byCourse: boolean): StudentPaymentGroupBy {
  if (byStudent && byCourse) return "STUDENT_COURSE";
  if (byStudent) return "STUDENT";
  if (byCourse) return "COURSE";
  return "NONE";
}

/** Identity of a group (spec: currency, level, parentKey, key). */
export function groupIdentity(currency: string, level: string, parentKey: string | null, key: string): string {
  return `${currency}|${level}|${parentKey ?? ""}|${key}`;
}

/** The (outermost-first) group identities a row belongs to - mirrors the backend's id keys ("unknown" for a missing id). */
export function rowGroupIdentities(row: StudentPaymentRow, groupBy: StudentPaymentGroupBy): string[] {
  const student = row.studentId ?? "unknown";
  const course = row.courseId ?? "unknown";
  switch (groupBy) {
    case "STUDENT":
      return [groupIdentity(row.currency, "STUDENT", null, student)];
    case "COURSE":
      return [groupIdentity(row.currency, "COURSE", null, course)];
    case "STUDENT_COURSE":
      return [
        groupIdentity(row.currency, "STUDENT", null, student),
        groupIdentity(row.currency, "COURSE", student, course),
      ];
    default:
      return [];
  }
}
