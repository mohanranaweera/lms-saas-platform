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

/** Mirrors `LedgerHistoryEntryResponse`. */
export interface LedgerHistoryEntryResponse {
  id: string;
  orderId: string;
  paymentId: string | null;
  entryType: LedgerEntryType;
  amount: number;
  reversesEntryId: string | null;
  createdAt: string;
}

export interface LedgerDashboardParams {
  page?: number;
  size?: number;
  sort?: string;
}

export const ledgerKeys = {
  all: ["ledger"] as const,
  history: () => [...ledgerKeys.all, "history"] as const,
  dashboard: (params?: LedgerDashboardParams) =>
    [...ledgerKeys.all, "dashboard", params ?? {}] as const,
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
