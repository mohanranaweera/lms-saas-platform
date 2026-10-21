import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "@/lib/auth/auth-context";
import { isApiClientError } from "./error";
import type { PageResponse } from "./courses";
import type { MoneyValue } from "./klass-student";
import { staffPaymentKeys } from "./staff-payments";

/**
 * ADR-030 Free Cards (`/api/v1/free-cards`, plan §4). A Free Card is
 * complimentary MONTHLY access for one student + course over a required date
 * range - never a payment, order or ledger entry. Status is derived by the
 * server from time (SCHEDULED / ACTIVE / EXPIRED) or revocation (REVOKED).
 * View = `FREE_CARDS/VIEW`, issue = `CREATE_EDIT`, revoke = `DELETE` (a
 * status change, never a row delete) - all enforced server-side.
 */

export type FreeCardStatus = "SCHEDULED" | "ACTIVE" | "EXPIRED" | "REVOKED";
export type FreeCardType = "FULL" | "HALF";

export const FREE_CARD_STATUS_OPTIONS: { value: FreeCardStatus; label: string }[] = [
  { value: "SCHEDULED", label: "Scheduled" },
  { value: "ACTIVE", label: "Active" },
  { value: "EXPIRED", label: "Expired" },
  { value: "REVOKED", label: "Revoked" },
];

export interface FreeCard {
  id: string;
  /** student_profile id; null if the profile no longer resolves. */
  studentId: string | null;
  studentName: string | null;
  courseId: string;
  courseTitle: string | null;
  cardType: FreeCardType | string;
  validFrom: string;
  validTo: string;
  timezone: string;
  effectiveFrom: string;
  effectiveUntil: string;
  /** "yyyy-MM" */
  coveredMonths: string[];
  status: FreeCardStatus;
  reason: string;
  currency: string | null;
  nominalMonthlyAmount: MoneyValue | null;
  nominalValue: MoneyValue | null;
  issuedBy: string;
  issuedByName: string | null;
  issuedAt: string;
  revokedAt: string | null;
  revokedBy: string | null;
  revokedByName: string | null;
  revokedReason: string | null;
  enrollmentCreated: boolean;
  replay: boolean;
}

export interface FreeCardListParams {
  studentId?: string | null;
  courseId?: string | null;
  status?: FreeCardStatus | null;
  page?: number;
  size?: number;
}

export interface IssueFreeCardRequest {
  studentId: string;
  courseId: string;
  cardType: FreeCardType;
  validFrom: string;
  validTo: string;
  reason: string;
  idempotencyKey: string;
}

export const freeCardKeys = {
  all: ["free-cards"] as const,
  list: (params: FreeCardListParams) => [...freeCardKeys.all, "list", params] as const,
};

function retryUnlessClientError(failureCount: number, error: unknown): boolean {
  if (isApiClientError(error) && error.status >= 400 && error.status < 500) return false;
  return failureCount < 2;
}

/** `GET /api/v1/free-cards?studentId=&courseId=&status=&page=&size=` - tenant-scoped page. */
export function useFreeCards(params: FreeCardListParams, options?: { enabled?: boolean }) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: freeCardKeys.list(params),
    queryFn: () => {
      const search = new URLSearchParams();
      if (params.studentId) search.set("studentId", params.studentId);
      if (params.courseId) search.set("courseId", params.courseId);
      if (params.status) search.set("status", params.status);
      search.set("page", String(params.page ?? 0));
      search.set("size", String(params.size ?? 20));
      return authorizedFetch<PageResponse<FreeCard>>("tenant", `/v1/free-cards?${search.toString()}`);
    },
    enabled: options?.enabled ?? true,
    retry: retryUnlessClientError,
    placeholderData: keepPreviousData,
  });
}

function useInvalidateFreeCards() {
  const queryClient = useQueryClient();
  return () => {
    void queryClient.invalidateQueries({ queryKey: freeCardKeys.all });
    // Issuing may create the course participation (enrollmentCreated) - re-read the payment context.
    void queryClient.invalidateQueries({ queryKey: staffPaymentKeys.all });
  };
}

export const ISSUE_FREE_CARD_KEY = ["free-cards", "issue"] as const;

/** `POST /api/v1/free-cards` - 201 new / 200 idempotent replay. */
export function useIssueFreeCard() {
  const { authorizedFetch } = useAuth();
  const invalidate = useInvalidateFreeCards();
  return useMutation({
    mutationKey: ISSUE_FREE_CARD_KEY,
    mutationFn: (body: IssueFreeCardRequest) =>
      authorizedFetch<FreeCard>("tenant", "/v1/free-cards", { method: "POST", body: JSON.stringify(body) }),
    onSuccess: () => invalidate(),
  });
}

/** `POST /api/v1/free-cards/{id}/revoke` - reason required; 409 `FREE_CARD_ALREADY_REVOKED`. */
export function useRevokeFreeCard() {
  const { authorizedFetch } = useAuth();
  const invalidate = useInvalidateFreeCards();
  return useMutation({
    mutationFn: ({ id, reason }: { id: string; reason: string }) =>
      authorizedFetch<FreeCard>("tenant", `/v1/free-cards/${encodeURIComponent(id)}/revoke`, {
        method: "POST",
        body: JSON.stringify({ reason }),
      }),
    onSuccess: () => invalidate(),
  });
}

export const VALIDITY_INVALID_MESSAGE =
  "Check the dates: From can't be earlier than the first day of this month, To must be on or after From and not in the past, and the card can cover at most 24 months.";

export function freeCardErrorMessage(error: unknown): string {
  if (!isApiClientError(error)) return "Something went wrong. Please try again.";
  switch (error.code) {
    case "FREE_CARD_OVERLAP":
      return "This student already has a free card for this course that overlaps these dates. Revoke it or choose other dates.";
    case "FREE_CARD_UNSUPPORTED_COURSE":
      return "Free cards can only be issued for monthly-priced courses.";
    case "VALIDITY_INVALID":
      return VALIDITY_INVALID_MESSAGE;
    case "REGISTRATION_CLOSED":
      return "Registration for this course is closed, so the student can't be added to it.";
    case "FREE_CARD_TYPE_UNSUPPORTED":
      return "Only Full free cards are supported.";
    case "FREE_CARD_ALREADY_REVOKED":
      return "This free card was already revoked.";
    case "IDEMPOTENCY_KEY_REUSED":
      return "This form was already used for a different free card. Close it and start again.";
    case "NETWORK_ERROR":
      return "We couldn't reach the server. Try again (it won't be issued twice).";
    default:
      if (error.status === 403) return "You don't have permission to do this.";
      if (error.status === 404) return "The student, course or free card was not found in your institute.";
      return error.message || "Something went wrong. Please try again.";
  }
}

const MONTH_LONG = [
  "January",
  "February",
  "March",
  "April",
  "May",
  "June",
  "July",
  "August",
  "September",
  "October",
  "November",
  "December",
];

/** "2026-08" → "2026 August". */
export function formatYearMonth(value: string): string {
  const [year, month] = value.split("-");
  const name = MONTH_LONG[Number(month) - 1];
  return name ? `${year} ${name}` : value;
}

/**
 * Calendar months ("yyyy-MM") touched by [from, to] - for the confirmation
 * summary only; the server computes the card's real coverage.
 */
export function monthsBetween(from: string, to: string): string[] {
  const start = /^(\d{4})-(\d{2})-\d{2}$/.exec(from);
  const end = /^(\d{4})-(\d{2})-\d{2}$/.exec(to);
  if (!start || !end || to < from) return [];
  let year = Number(start[1]);
  let month = Number(start[2]);
  const endYear = Number(end[1]);
  const endMonth = Number(end[2]);
  const months: string[] = [];
  while ((year < endYear || (year === endYear && month <= endMonth)) && months.length < 120) {
    months.push(`${year}-${String(month).padStart(2, "0")}`);
    month += 1;
    if (month > 12) {
      month = 1;
      year += 1;
    }
  }
  return months;
}
