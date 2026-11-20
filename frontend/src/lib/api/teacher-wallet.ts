import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "@/lib/auth/auth-context";
import { financeKeys } from "./finance";

/**
 * TEACHER-WALLET-01 (ADR-036) — Teachers → Show Wallet. One teacher per call;
 * the Teachers table never loads balances. `balance` is the amount payable to
 * the teacher = unpaid settlements + wallet entries. Every rule (permissions,
 * overdraft, precision, idempotency, stale balance) is enforced server-side.
 */

export type WalletEntryKind = "OPENING" | "PAY_IN" | "PAY_OUT" | "BALANCE_ADJ";

export interface TeacherWallet {
  teacherId: string;
  exists: boolean;
  walletId: string | null;
  currency: string;
  balance: number | null;
  unpaidSettlements: number | null;
  walletEntries: number | null;
  createdAt: string | null;
}

export type WalletActivityEvent =
  | WalletEntryKind
  | "SETTLEMENT_EARNED"
  | "SETTLEMENT_ADJUSTED"
  | "SETTLEMENT_PAID";

/**
 * One activity line. `source: "WALLET"` lines are wallet entries (with the
 * balance before/after). `source: "SETTLEMENT"` lines are read-only settlement
 * events from Teacher Payouts — earned when calculated, paid when marked paid —
 * derived from the settlement record, never copied into the wallet.
 */
export interface WalletEntry {
  id: string;
  kind: string;
  event: WalletActivityEvent;
  source: "WALLET" | "SETTLEMENT";
  /** Signed effect on the balance: pay-outs and paid settlements are negative. */
  amount: number;
  currency: string;
  description: string | null;
  balanceBefore: number | null;
  balanceAfter: number | null;
  createdAt: string;
  createdBy: string;
  createdByEmail: string | null;
  accountName: string | null;
  categoryName: string | null;
  settlementId: string | null;
  periodStart: string | null;
  periodEnd: string | null;
  payoutReference: string | null;
}

export interface WalletPostResult {
  wallet: TeacherWallet;
  entry: WalletEntry;
  created: boolean;
}

export interface WalletPostingInput {
  amount: string;
  description: string;
  accountId: string | null;
  categoryId: string | null;
  expectedBalance: number;
  currency: string;
  idempotencyKey: string;
}

export const walletKeys = {
  all: (teacherId: string) => [...financeKeys.all, "wallet", teacherId] as const,
  wallet: (teacherId: string) => [...walletKeys.all(teacherId), "summary"] as const,
  entries: (teacherId: string, kind: WalletEntryKind) => [...walletKeys.all(teacherId), "entries", kind] as const,
};

const base = (teacherId: string) => `/v1/finance/teachers/${teacherId}/wallet`;

export function useTeacherWallet(teacherId: string | null) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: walletKeys.wallet(teacherId ?? ""),
    queryFn: () => authorizedFetch<TeacherWallet>("tenant", base(teacherId as string)),
    enabled: Boolean(teacherId),
    // Always re-read when the sheet opens: a balance is never trusted from cache.
    staleTime: 0,
  });
}

export function useWalletEntries(teacherId: string | null, kind: WalletEntryKind, enabled: boolean) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: walletKeys.entries(teacherId ?? "", kind),
    queryFn: () => authorizedFetch<WalletEntry[]>("tenant", `${base(teacherId as string)}/entries?kind=${kind}&limit=10`),
    enabled: Boolean(teacherId) && enabled,
    staleTime: 0,
  });
}

function useInvalidateWallet(teacherId: string) {
  const queryClient = useQueryClient();
  return () => queryClient.invalidateQueries({ queryKey: walletKeys.all(teacherId) });
}

export function useCreateWallet(teacherId: string) {
  const { authorizedFetch } = useAuth();
  const invalidate = useInvalidateWallet(teacherId);
  return useMutation({
    mutationFn: (body: { openingBalance: string; currency: string }) =>
      authorizedFetch<TeacherWallet>("tenant", base(teacherId), {
        method: "POST",
        body: JSON.stringify({ openingBalance: Number(body.openingBalance), currency: body.currency }),
      }),
    // A concurrent-creation 409 also refreshes, so the existing wallet is shown.
    onSettled: invalidate,
  });
}

export function useWalletPosting(teacherId: string, direction: "pay-outs" | "pay-ins") {
  const { authorizedFetch } = useAuth();
  const invalidate = useInvalidateWallet(teacherId);
  return useMutation({
    mutationFn: (input: WalletPostingInput) =>
      authorizedFetch<WalletPostResult>("tenant", `${base(teacherId)}/${direction}`, {
        method: "POST",
        headers: { "Idempotency-Key": input.idempotencyKey },
        body: JSON.stringify({
          amount: Number(input.amount),
          description: input.description,
          currency: input.currency,
          accountId: input.accountId,
          categoryId: input.categoryId,
          expectedBalance: input.expectedBalance,
        }),
      }),
    onSettled: invalidate,
  });
}

export function useAdjustWallet(teacherId: string) {
  const { authorizedFetch } = useAuth();
  const invalidate = useInvalidateWallet(teacherId);
  return useMutation({
    mutationFn: (input: { targetBalance: string; expectedBalance: number; reason: string; idempotencyKey: string }) =>
      authorizedFetch<WalletPostResult>("tenant", `${base(teacherId)}/adjustments`, {
        method: "POST",
        headers: { "Idempotency-Key": input.idempotencyKey },
        body: JSON.stringify({
          targetBalance: Number(input.targetBalance),
          expectedBalance: input.expectedBalance,
          reason: input.reason,
        }),
      }),
    onSettled: invalidate,
  });
}

/** Parses a user-entered amount into integer cents; `null` when invalid (sign, >2 decimals, garbage). */
export function toCents(value: string): number | null {
  const trimmed = value.trim();
  if (!/^\d{1,10}(\.\d{1,2})?$/.test(trimmed)) return null;
  const [whole, fraction = ""] = trimmed.split(".");
  return Number(whole) * 100 + Number(fraction.padEnd(2, "0"));
}

export function centsOf(amount: number): number {
  return Math.round(amount * 100);
}
