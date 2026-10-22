import { useCallback } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { env } from "@/lib/env";
import { useAuth } from "@/lib/auth/auth-context";
import { ApiClientError, isApiClientError } from "./error";
import type { ApiResponse } from "./types";

/**
 * Typed client for RPT-01 Teacher Income Summary
 * (`/api/v1/reports/teacher-income`, `docs/specs/teacher-income-summary-report.md`
 * §4.1 "As implemented"). Every endpoint is server-gated on
 * `FINANCE_EXPENSES`/`VIEW` and tenant-scoped from the authenticated context;
 * nothing here makes an authorization decision or sends a tenant id.
 *
 * Every figure is computed server-side and arrives as a 2-dp decimal string.
 * This module never computes, sums or re-derives a money figure - and never
 * across currencies - it only formats what the backend returned.
 */

const BASE = "/v1/reports/teacher-income";

export type ReportPaymentMethod = "GATEWAY" | "MANUAL_SLIP" | "MANUAL_RECEIPT";
export type ReportPeriodMode = "MONTHS" | "DATES";

/** Labels from `docs/specs/reporting-catalog.md` (payment-method mapping). */
export const REPORT_PAYMENT_METHOD_LABELS: Record<ReportPaymentMethod, string> = {
  GATEWAY: "Online (card)",
  MANUAL_SLIP: "Bank transfer (slip)",
  MANUAL_RECEIPT: "Manual receipt (cash)",
};

export function reportPaymentMethodLabel(method: string | null | undefined): string {
  if (!method) return "All methods";
  return REPORT_PAYMENT_METHOD_LABELS[method as ReportPaymentMethod] ?? method;
}

export const REPORT_LIMITS = { maxTeachers: 50, maxMonths: 36, maxSpanDays: 366 } as const;

export interface ReportTeacherRef {
  id: string;
  name: string;
}

export interface TeacherIncomeOptions {
  teachers: ReportTeacherRef[];
  paymentMethods: string[];
}

export interface TeacherIncomeRunRequest {
  teacherIds: string[];
  paymentMethod?: ReportPaymentMethod;
  periodMode: ReportPeriodMode;
  /** MONTHS only - `"YYYY-MM"`. */
  months?: string[];
  /** DATES only - `YYYY-MM-DD`, inclusive. */
  startDate?: string;
  endDate?: string;
}

export interface ReportTotals {
  revenue: string;
  teacherIncome: string;
  commission: string;
  /** Signed; institute-only (facility fees + postage). Not additive across teachers. */
  serviceFees: string;
  payouts: string;
  /** Always `null` - no source exists ("Not tracked"). */
  payins: string | null;
  /** Teacher income - payouts: a period movement, not an account balance. */
  balance: string;
  paymentCount: number;
  teacherCount: number;
  unratedRevenue: string;
}

export interface ReportCourseRow {
  courseId: string;
  courseName: string;
  paymentCount: number;
  revenue: string;
  /** `null` only when no part of the row has an effective rate. */
  teacherIncome: string | null;
  commission: string | null;
  /** `true` if any part of the row has no effective rate. */
  rateMissing: boolean;
}

export interface ReportTeacherBlock {
  teacherId: string;
  teacherName: string;
  totals: ReportTotals;
  courses: ReportCourseRow[];
}

export interface ReportSection {
  currency: string;
  totals: ReportTotals;
  teachers: ReportTeacherBlock[];
}

export interface ReportCountAmount {
  count: number;
  amount: string;
}

export interface ReportCurrencyReconciliation {
  currency: string;
  grossRevenue: string;
  refunds: string;
  excludedNonCash: ReportCountAmount;
  excludedNotMonthAttributable: ReportCountAmount;
  settledBasisDifference: string;
  unassignedRevenue: string;
}

export interface TeacherIncomeReport {
  runId: string;
  expiresAt: string;
  generatedAt: string;
  timezone: string;
  institute: { name: string };
  filters: {
    teachers: ReportTeacherRef[];
    paymentMethod: string | null;
    periodMode: ReportPeriodMode;
    months: string[] | null;
    startDate: string | null;
    endDate: string | null;
    periodLabel: string;
  };
  sections: ReportSection[];
  reconciliation: {
    byCurrency: ReportCurrencyReconciliation[];
    notes: string[];
  };
}

export const reportKeys = {
  all: ["reports"] as const,
  teacherIncomeOptions: () => [...reportKeys.all, "teacher-income", "options"] as const,
};

/** `GET /options` - teachers of the caller's tenant + filterable payment methods. */
export function useTeacherIncomeOptions() {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: reportKeys.teacherIncomeOptions(),
    queryFn: () => authorizedFetch<TeacherIncomeOptions>("tenant", `${BASE}/options`),
    retry: false,
  });
}

/** `POST /runs` - computes a short-lived (server-held) report run. `signal` lets the screen abort an in-flight search. */
export function useCreateTeacherIncomeRun() {
  const { authorizedFetch } = useAuth();
  return useMutation({
    mutationFn: ({ request, signal }: { request: TeacherIncomeRunRequest; signal?: AbortSignal }) =>
      authorizedFetch<TeacherIncomeReport>("tenant", `${BASE}/runs`, {
        method: "POST",
        body: JSON.stringify(request),
        signal,
      }),
  });
}

export function summaryPdfPath(runId: string): string {
  return `${BASE}/runs/${encodeURIComponent(runId)}/pdf`;
}

export function payslipPdfPath(runId: string, teacherId: string): string {
  return `${BASE}/runs/${encodeURIComponent(runId)}/teachers/${encodeURIComponent(teacherId)}/payslip.pdf`;
}

/**
 * Authenticated binary download. `authorizedFetch` only understands the JSON
 * `ApiResponse` envelope, so PDFs are fetched here with the same token source
 * (`ensureAccessToken`) and the same single 401 refresh-and-retry rule. Errors
 * are still JSON envelopes and are surfaced as `ApiClientError` (e.g. 404 for
 * an expired run, 422 `REPORT_TOO_LARGE`). The bytes never touch a public URL:
 * the caller turns the Blob into a short-lived object URL for the download.
 */
export function useAuthorizedBlobFetch() {
  const { ensureAccessToken } = useAuth();

  return useCallback(
    async (path: string): Promise<Blob> => {
      const url = `${env.NEXT_PUBLIC_API_BASE_URL}${path}`;

      const attempt = async (token: string) => {
        try {
          return await fetch(url, {
            credentials: "include",
            headers: { Accept: "application/pdf, application/json", Authorization: `Bearer ${token}` },
          });
        } catch {
          throw new ApiClientError(
            {
              code: "NETWORK_ERROR",
              message: "Unable to reach the server. Check your connection and try again.",
              fieldErrors: [],
            },
            0
          );
        }
      };

      let response = await attempt(await ensureAccessToken("tenant"));
      if (response.status === 401) {
        const error = await toApiError(response);
        if (error.code !== "UNAUTHENTICATED" && error.code !== "SESSION_REVOKED") throw error;
        response = await attempt(await ensureAccessToken("tenant", { force: true }));
      }
      if (!response.ok) {
        throw await toApiError(response);
      }
      return response.blob();
    },
    [ensureAccessToken]
  );
}

async function toApiError(response: Response): Promise<ApiClientError> {
  let payload: ApiResponse<unknown> | null = null;
  try {
    payload = (await response.json()) as ApiResponse<unknown>;
  } catch {
    payload = null;
  }
  return new ApiClientError(
    payload?.error ?? { code: "UNKNOWN_ERROR", message: "An unexpected error occurred.", fieldErrors: [] },
    response.status,
    payload?.traceId
  );
}

/**
 * Downloads a report PDF (`summary` or one teacher's `payslip`) for an
 * existing run and saves it. A mutation, not a query: every click is a fresh,
 * authenticated, uncached (`no-store`) request for the run the screen shows.
 */
export function useDownloadReportPdf() {
  const fetchBlob = useAuthorizedBlobFetch();
  return useMutation({
    mutationFn: async (input: { path: string; filename: string }) => {
      const blob = await fetchBlob(input.path);
      saveBlob(blob, input.filename);
      return input.filename;
    },
  });
}

/**
 * Saves a Blob via a temporary object URL. Revocation is delayed: revoking in
 * the same tick can cancel the download in some browsers (e.g. Firefox).
 */
export function saveBlob(blob: Blob, filename: string): void {
  const objectUrl = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = objectUrl;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(objectUrl), 30_000);
}

/** Friendly copy for a failed PDF export. */
export function reportDownloadErrorMessage(error: unknown): string {
  if (isApiClientError(error)) {
    if (error.status === 404) return "This report has expired — run it again.";
    if (error.status === 403) return "You don't have permission to export this report.";
    if (error.code === "REPORT_TOO_LARGE") {
      return error.fieldErrors[0]?.message
        ? `The report is too large to export as PDF: ${error.fieldErrors[0].message}.`
        : "The report is too large to export as PDF. Narrow the teachers or period.";
    }
    if (error.status === 503) return "Reports are temporarily unavailable. Please try again shortly.";
    return error.message;
  }
  return "The download failed. Please try again.";
}

/** "Rs. 1,234.00" for LKR, otherwise "USD 1,234.00". Formats the server string; never re-computes it. */
export function formatReportAmount(amount: string | null | undefined, currency: string): string {
  if (amount === null || amount === undefined) return "—";
  const negative = amount.trim().startsWith("-");
  const [intPart, fracPart = ""] = amount.trim().replace(/^[-+]/, "").split(".");
  const grouped = intPart.replace(/\B(?=(\d{3})+(?!\d))/g, ",");
  const fraction = (fracPart + "00").slice(0, 2);
  const prefix = currency === "LKR" ? "Rs." : currency;
  return `${negative ? "-" : ""}${prefix} ${grouped}.${fraction}`;
}
