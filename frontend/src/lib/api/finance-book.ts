import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "@/lib/auth/auth-context";
import type { PageResponse } from "./courses";
import { financeKeys } from "./finance";

/**
 * FIN-01 account book (Klass-style Finance tabs): typed client + React Query
 * hooks for `/api/v1/finance/{accounts,transactions,schedules,budget,student-income}`
 * (`docs/api/finance-expense-management.md`, "Finance account book (FIN-01)").
 *
 * Nothing here makes an authorization decision: every endpoint is
 * capability-checked server-side. No tenant id is ever sent. Money arrives as
 * JSON numbers and is only formatted, never summed into an authoritative total.
 * Student payments reach the book only through backend events; this client can
 * post an UNPOSTED row to an account but never creates or edits student income.
 */

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

export type RoutingMethodCode = "CASH" | "CARD" | "BANK_TRANSFER" | "GATEWAY" | "MANUAL_SLIP" | "OTHER";

export interface FinanceAccount {
  id: string;
  name: string;
  description: string | null;
  currency: string;
  balance: number;
  archived: boolean;
  mappedMethods: RoutingMethodCode[];
  createdAt: string;
  updatedAt: string;
}

export interface FinanceRouting {
  cashAccountId: string | null;
  cardAccountId: string | null;
  bankOnlineAccountId: string | null;
  otherAccountId: string | null;
  methods: Partial<Record<RoutingMethodCode, string>>;
  updatedAt: string | null;
}

export type RoutingInput = Pick<FinanceRouting, "cashAccountId" | "cardAccountId" | "bankOnlineAccountId" | "otherAccountId">;

export type TransactionType = "OPENING" | "INCOME" | "EXPENSE" | "TRANSFER" | "ADJUSTMENT" | "REFUND" | "REVERSAL";
export type TransactionStatus = "POSTED" | "REVERSED" | "UNPOSTED";
export type SourceType = "MANUAL" | "LEDGER_ENTRY" | "EXPENSE" | "WALLET_ENTRY" | "SCHEDULE";
export type CorrectionOwner = "PAYMENTS" | "TEACHER_WALLET" | "EXPENSES";

export interface FinanceTransaction {
  id: string;
  type: TransactionType;
  status: TransactionStatus;
  amount: number;
  currency: string;
  fromAccountId: string | null;
  fromAccountName: string | null;
  toAccountId: string | null;
  toAccountName: string | null;
  categoryId: string | null;
  categoryName: string | null;
  transactionDate: string;
  postedAt: string | null;
  note: string | null;
  sourceType: SourceType;
  sourceId: string | null;
  reversesTransactionId: string | null;
  reversedByTransactionId: string | null;
  balanceBefore: number | null;
  balanceAfter: number | null;
  reason: string | null;
  createdBy: string | null;
  createdByEmail: string | null;
  createdAt: string;
  reversedAt: string | null;
  reversibleHere: boolean;
  correctionOwner: CorrectionOwner | null;
}

export interface TransactionListParams {
  search?: string;
  type?: TransactionType;
  accountId?: string;
  categoryId?: string;
  status?: TransactionStatus;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}

export type RecordableType = "INCOME" | "EXPENSE" | "TRANSFER";

export interface RecordTransactionInput {
  type: RecordableType;
  amount: string;
  transactionDate: string;
  note?: string;
  categoryId?: string;
  accountId?: string;
  fromAccountId?: string;
  toAccountId?: string;
}

export interface ReconcileResult {
  from: string;
  to: string;
  posted: number;
  unposted: number;
  skipped: number;
}

export type ScheduleType = "ONE_TIME" | "MONTHLY";
export type ScheduleStatus = "ACTIVE" | "COMPLETED" | "CANCELLED";
export type OccurrenceStatus = "DUE" | "POSTED" | "FAILED" | "SKIPPED";

export interface ScheduleOccurrence {
  id: string;
  scheduleId: string;
  dueDate: string;
  status: OccurrenceStatus;
  transactionId: string | null;
  attempts: number;
  lastError: string | null;
  executedAt: string | null;
  executedBy: string | null;
}

export interface FinanceSchedule {
  id: string;
  name: string;
  type: RecordableType;
  amount: number;
  currency: string;
  fromAccountId: string | null;
  fromAccountName: string | null;
  toAccountId: string | null;
  toAccountName: string | null;
  categoryId: string | null;
  categoryName: string | null;
  note: string | null;
  scheduleType: ScheduleType;
  scheduledDate: string | null;
  dayOfMonth: number | null;
  startsOn: string | null;
  status: ScheduleStatus;
  nextDueDate: string | null;
  openOccurrences: number;
  postedOccurrences: number;
  failedOccurrences: number;
  skippedOccurrences: number;
  lastExecution: ScheduleOccurrence | null;
  skippedMonths: string[];
  createdAt: string;
  updatedAt: string;
  cancelledAt: string | null;
}

export interface ScheduleListParams {
  search?: string;
  status?: ScheduleStatus;
  page?: number;
  size?: number;
}

export interface ScheduleInput {
  name: string;
  type: RecordableType;
  amount: string;
  accountId?: string;
  fromAccountId?: string;
  toAccountId?: string;
  categoryId?: string;
  note?: string;
  scheduleType: ScheduleType;
  scheduledDate?: string;
  dayOfMonth?: number;
}

export interface CategoryTotal {
  categoryId: string | null;
  label: string;
  total: number;
  count: number;
}

export interface AccountBalance {
  accountId: string;
  name: string;
  description: string | null;
  balance: number;
}

export interface TrendPoint {
  period: string;
  income: number;
  expenses: number;
  net: number;
}

export interface BudgetDashboard {
  from: string;
  to: string;
  timezone: string;
  currency: string;
  totalBalance: number;
  incomeForPeriod: number;
  grossIncomeForPeriod: number;
  refundsForPeriod: number;
  expensesForPeriod: number;
  netActivity: number;
  adjustmentsForPeriod: number;
  transfersForPeriod: number;
  incomeCount: number;
  expenseCount: number;
  incomeByCategory: CategoryTotal[];
  expensesByCategory: CategoryTotal[];
  accountBalances: AccountBalance[];
  trendGranularity: "DAY" | "MONTH";
  cashFlowTrend: TrendPoint[];
  notes: string[];
}

export interface IncomeTotals {
  grossIncome: number;
  refunds: number;
  netIncome: number;
  teacherShare: number;
  instituteCommission: number;
  serviceFees: number;
  unratedRevenue: number;
  expenses: number;
  netAfterExpenses: number;
  paymentCount: number;
  averagePayment: number;
}

export interface IncomeMonthPoint {
  month: string;
  label: string;
  income: number;
  refunds: number;
  netIncome: number;
  expenses: number;
  commission: number;
  teacherShare: number;
  paymentCount: number;
}

export interface MethodTotal {
  code: string;
  label: string;
  online: boolean;
  count: number;
  total: number;
}

export interface MethodBreakdown {
  onlineCount: number;
  onlineTotal: number;
  manualCount: number;
  manualTotal: number;
  onlinePercent: number;
  byCode: MethodTotal[];
}

export interface TeacherMonthRow {
  teacherId: string | null;
  teacherName: string | null;
  month: string;
  monthLabel: string;
  income: number;
  forInstitute: number | null;
  forTeacher: number | null;
  rateMissing: boolean;
}

export interface WalletMovement {
  entryId: string;
  kind: string;
  amount: number;
  description: string | null;
  recordedAt: string;
}

export interface IncomeWallet {
  teacherId: string;
  walletId: string;
  currency: string;
  balance: number;
  unpaidSettlements: number;
  walletEntries: number;
  payIns: WalletMovement[];
  payouts: WalletMovement[];
}

export interface StudentIncome {
  year: number;
  currency: string;
  timezone: string;
  generatedAt: string;
  totals: IncomeTotals;
  months: IncomeMonthPoint[];
  methods: MethodBreakdown;
  teacherRows: TeacherMonthRow[];
  wallet: IncomeWallet | null;
  accountBook: { postedStudentIncome: number; unpostedCount: number };
  notes: string[];
}

export interface IncomePaymentRow {
  ledgerEntryId: string;
  orderId: string | null;
  paymentId: string | null;
  refund: boolean;
  amount: number;
  currency: string;
  method: string;
  methodCode: string;
  date: string;
  recordedAt: string;
  courseCount: number;
}

export interface StudentIncomeMonth {
  month: string;
  currency: string;
  timezone: string;
  totals: IncomeTotals;
  methods: MethodBreakdown;
  teacherRows: TeacherMonthRow[];
  payments: IncomePaymentRow[];
}

// ---------------------------------------------------------------------------
// Keys + helpers
// ---------------------------------------------------------------------------

export const bookKeys = {
  all: [...financeKeys.all, "book"] as const,
  accountsAll: () => [...bookKeys.all, "accounts"] as const,
  accounts: (includeArchived: boolean) => [...bookKeys.accountsAll(), { includeArchived }] as const,
  routing: () => [...bookKeys.all, "routing"] as const,
  transactionsAll: () => [...bookKeys.all, "transactions"] as const,
  transactions: (params: TransactionListParams) => [...bookKeys.transactionsAll(), "list", params] as const,
  unposted: () => [...bookKeys.transactionsAll(), "unposted"] as const,
  schedulesAll: () => [...bookKeys.all, "schedules"] as const,
  schedules: (params: ScheduleListParams) => [...bookKeys.schedulesAll(), "list", params] as const,
  due: () => [...bookKeys.schedulesAll(), "due"] as const,
  occurrences: (id: string) => [...bookKeys.schedulesAll(), "occurrences", id] as const,
  budget: (params: { from?: string; to?: string }) => [...bookKeys.all, "budget", params] as const,
  incomeAll: () => [...bookKeys.all, "student-income"] as const,
  income: (year: number) => [...bookKeys.incomeAll(), year] as const,
  incomeMonth: (month: string) => [...bookKeys.incomeAll(), "month", month] as const,
};

function query(params: Record<string, string | number | boolean | undefined>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== "") search.set(key, String(value));
  }
  const qs = search.toString();
  return qs ? `?${qs}` : "";
}

function blankToNull(value: string | undefined): string | null {
  return value && value.trim() !== "" ? value.trim() : null;
}

function idempotencyHeaders(key: string | undefined): HeadersInit | undefined {
  return key ? { "Idempotency-Key": key } : undefined;
}

/** Everything a posting can move: balances, lists, budget, unposted, income comparison. */
function useInvalidateBook() {
  const queryClient = useQueryClient();
  return () => {
    void queryClient.invalidateQueries({ queryKey: bookKeys.all });
    void queryClient.invalidateQueries({ queryKey: financeKeys.expensesAll() });
    void queryClient.invalidateQueries({ queryKey: financeKeys.reportsAll() });
  };
}

// ---------------------------------------------------------------------------
// Accounts + routing
// ---------------------------------------------------------------------------

export function useFinanceAccounts(includeArchived = false) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: bookKeys.accounts(includeArchived),
    queryFn: () =>
      authorizedFetch<FinanceAccount[]>("tenant", `/v1/finance/accounts${query({ includeArchived })}`),
  });
}

export function useCreateAccount() {
  const { authorizedFetch } = useAuth();
  const invalidate = useInvalidateBook();
  return useMutation({
    mutationFn: (body: { name: string; description?: string; openingBalance: string }) =>
      authorizedFetch<FinanceAccount>("tenant", "/v1/finance/accounts", {
        method: "POST",
        body: JSON.stringify({
          name: body.name.trim(),
          description: blankToNull(body.description),
          openingBalance: Number(body.openingBalance),
        }),
      }),
    onSuccess: invalidate,
  });
}

export function useUpdateAccount() {
  const { authorizedFetch } = useAuth();
  const invalidate = useInvalidateBook();
  return useMutation({
    mutationFn: ({ id, name, description }: { id: string; name: string; description?: string }) =>
      authorizedFetch<FinanceAccount>("tenant", `/v1/finance/accounts/${id}`, {
        method: "PUT",
        body: JSON.stringify({ name: name.trim(), description: blankToNull(description) }),
      }),
    onSuccess: invalidate,
  });
}

export function useSetAccountArchived() {
  const { authorizedFetch } = useAuth();
  const invalidate = useInvalidateBook();
  return useMutation({
    mutationFn: ({ id, archived }: { id: string; archived: boolean }) =>
      authorizedFetch<FinanceAccount>("tenant", `/v1/finance/accounts/${id}/${archived ? "archive" : "unarchive"}`, {
        method: "POST",
      }),
    onSuccess: invalidate,
  });
}

/** `expectedBalance` is the balance the user saw; a stale one is a server 409. */
export function useAdjustAccount() {
  const { authorizedFetch } = useAuth();
  const invalidate = useInvalidateBook();
  return useMutation({
    mutationFn: (input: {
      id: string;
      targetBalance: string;
      expectedBalance: number;
      reason: string;
      idempotencyKey: string;
    }) =>
      authorizedFetch<FinanceTransaction>("tenant", `/v1/finance/accounts/${input.id}/adjustments`, {
        method: "POST",
        headers: idempotencyHeaders(input.idempotencyKey),
        body: JSON.stringify({
          targetBalance: Number(input.targetBalance),
          expectedBalance: input.expectedBalance,
          reason: input.reason.trim(),
        }),
      }),
    onSuccess: invalidate,
  });
}

export function useFinanceRouting() {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: bookKeys.routing(),
    queryFn: () => authorizedFetch<FinanceRouting>("tenant", "/v1/finance/accounts/routing"),
  });
}

export function useSaveRouting() {
  const { authorizedFetch } = useAuth();
  const invalidate = useInvalidateBook();
  return useMutation({
    mutationFn: (body: RoutingInput) =>
      authorizedFetch<FinanceRouting>("tenant", "/v1/finance/accounts/routing", {
        method: "PUT",
        body: JSON.stringify(body),
      }),
    onSuccess: invalidate,
  });
}

// ---------------------------------------------------------------------------
// Transactions
// ---------------------------------------------------------------------------

export function useFinanceTransactions(params: TransactionListParams) {
  const { authorizedFetch } = useAuth();
  const qs = query({
    search: params.search?.trim() || undefined,
    type: params.type,
    accountId: params.accountId,
    categoryId: params.categoryId,
    status: params.status,
    from: params.from,
    to: params.to,
    page: params.page ?? 0,
    size: params.size ?? 10,
  });
  return useQuery({
    queryKey: bookKeys.transactions(params),
    queryFn: () => authorizedFetch<PageResponse<FinanceTransaction>>("tenant", `/v1/finance/transactions${qs}`),
  });
}

export function useUnpostedTransactions() {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: bookKeys.unposted(),
    queryFn: () => authorizedFetch<FinanceTransaction[]>("tenant", "/v1/finance/transactions/unposted"),
  });
}

export function useRecordTransaction() {
  const { authorizedFetch } = useAuth();
  const invalidate = useInvalidateBook();
  return useMutation({
    mutationFn: ({ input, idempotencyKey }: { input: RecordTransactionInput; idempotencyKey: string }) => {
      const transfer = input.type === "TRANSFER";
      return authorizedFetch<FinanceTransaction>("tenant", "/v1/finance/transactions", {
        method: "POST",
        headers: idempotencyHeaders(idempotencyKey),
        body: JSON.stringify({
          type: input.type,
          amount: Number(input.amount),
          transactionDate: input.transactionDate,
          note: blankToNull(input.note),
          categoryId: transfer ? null : blankToNull(input.categoryId),
          accountId: transfer ? null : blankToNull(input.accountId),
          fromAccountId: transfer ? blankToNull(input.fromAccountId) : null,
          toAccountId: transfer ? blankToNull(input.toAccountId) : null,
        }),
      });
    },
    onSuccess: invalidate,
  });
}

export function usePatchTransaction() {
  const { authorizedFetch } = useAuth();
  const invalidate = useInvalidateBook();
  return useMutation({
    mutationFn: ({ id, note, categoryId }: { id: string; note?: string; categoryId?: string | null }) =>
      authorizedFetch<FinanceTransaction>("tenant", `/v1/finance/transactions/${id}`, {
        method: "PATCH",
        body: JSON.stringify({ note: note ?? null, categoryId: categoryId ?? null }),
      }),
    onSuccess: invalidate,
  });
}

export function useReverseTransaction() {
  const { authorizedFetch } = useAuth();
  const invalidate = useInvalidateBook();
  return useMutation({
    mutationFn: ({ id, reason }: { id: string; reason: string }) =>
      authorizedFetch<FinanceTransaction>("tenant", `/v1/finance/transactions/${id}/reverse`, {
        method: "POST",
        body: JSON.stringify({ reason: reason.trim() }),
      }),
    onSuccess: invalidate,
  });
}

export function usePostUnposted() {
  const { authorizedFetch } = useAuth();
  const invalidate = useInvalidateBook();
  return useMutation({
    mutationFn: ({ id, accountId }: { id: string; accountId: string }) =>
      authorizedFetch<FinanceTransaction>("tenant", `/v1/finance/transactions/${id}/post`, {
        method: "POST",
        body: JSON.stringify({ accountId }),
      }),
    onSuccess: invalidate,
  });
}

export function useReconcilePayments() {
  const { authorizedFetch } = useAuth();
  const invalidate = useInvalidateBook();
  return useMutation({
    mutationFn: (from: string) =>
      authorizedFetch<ReconcileResult>("tenant", "/v1/finance/transactions/reconcile", {
        method: "POST",
        body: JSON.stringify({ from: from || null }),
      }),
    onSuccess: invalidate,
  });
}

// ---------------------------------------------------------------------------
// Schedules
// ---------------------------------------------------------------------------

export function useFinanceSchedules(params: ScheduleListParams) {
  const { authorizedFetch } = useAuth();
  const qs = query({
    search: params.search?.trim() || undefined,
    status: params.status,
    page: params.page ?? 0,
    size: params.size ?? 20,
  });
  return useQuery({
    queryKey: bookKeys.schedules(params),
    queryFn: () => authorizedFetch<PageResponse<FinanceSchedule>>("tenant", `/v1/finance/schedules${qs}`),
  });
}

export function useDueOccurrences() {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: bookKeys.due(),
    queryFn: () => authorizedFetch<ScheduleOccurrence[]>("tenant", "/v1/finance/schedules/due"),
  });
}

export function useScheduleOccurrences(id: string, enabled = true) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: bookKeys.occurrences(id),
    queryFn: () => authorizedFetch<ScheduleOccurrence[]>("tenant", `/v1/finance/schedules/${id}/occurrences`),
    enabled,
  });
}

function scheduleBody(input: ScheduleInput) {
  const transfer = input.type === "TRANSFER";
  const monthly = input.scheduleType === "MONTHLY";
  return JSON.stringify({
    name: input.name.trim(),
    type: input.type,
    amount: Number(input.amount),
    accountId: transfer ? null : blankToNull(input.accountId),
    fromAccountId: transfer ? blankToNull(input.fromAccountId) : null,
    toAccountId: transfer ? blankToNull(input.toAccountId) : null,
    categoryId: transfer ? null : blankToNull(input.categoryId),
    note: blankToNull(input.note),
    scheduleType: input.scheduleType,
    scheduledDate: monthly ? null : blankToNull(input.scheduledDate),
    dayOfMonth: monthly ? (input.dayOfMonth ?? null) : null,
  });
}

export function useSaveSchedule() {
  const { authorizedFetch } = useAuth();
  const invalidate = useInvalidateBook();
  return useMutation({
    mutationFn: ({ id, input }: { id?: string; input: ScheduleInput }) =>
      authorizedFetch<FinanceSchedule>("tenant", id ? `/v1/finance/schedules/${id}` : "/v1/finance/schedules", {
        method: id ? "PUT" : "POST",
        body: scheduleBody(input),
      }),
    onSuccess: invalidate,
  });
}

export function useCancelSchedule() {
  const { authorizedFetch } = useAuth();
  const invalidate = useInvalidateBook();
  return useMutation({
    mutationFn: (id: string) =>
      authorizedFetch<FinanceSchedule>("tenant", `/v1/finance/schedules/${id}/cancel`, { method: "POST" }),
    onSuccess: invalidate,
  });
}

/**
 * Records one due occurrence (a manual, authorised posting - the scheduler
 * never moves money). A validation failure is a 409 that leaves the
 * occurrence FAILED with `lastError`; the lists are refreshed either way so
 * the failure reason is shown from the server's own record.
 */
export function usePostOccurrence() {
  const { authorizedFetch } = useAuth();
  const invalidate = useInvalidateBook();
  return useMutation({
    mutationFn: (id: string) =>
      authorizedFetch<ScheduleOccurrence>("tenant", `/v1/finance/schedules/occurrences/${id}/post`, {
        method: "POST",
      }),
    onSettled: invalidate,
  });
}

export function useSkipOccurrence() {
  const { authorizedFetch } = useAuth();
  const invalidate = useInvalidateBook();
  return useMutation({
    mutationFn: (id: string) =>
      authorizedFetch<ScheduleOccurrence>("tenant", `/v1/finance/schedules/occurrences/${id}/skip`, {
        method: "POST",
      }),
    onSuccess: invalidate,
  });
}

// ---------------------------------------------------------------------------
// Dashboards
// ---------------------------------------------------------------------------

export function useBudgetDashboard(params: { from?: string; to?: string }) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: bookKeys.budget(params),
    queryFn: () =>
      authorizedFetch<BudgetDashboard>("tenant", `/v1/finance/budget${query({ from: params.from, to: params.to })}`),
  });
}

export function useStudentIncome(year: number) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: bookKeys.income(year),
    queryFn: () => authorizedFetch<StudentIncome>("tenant", `/v1/finance/student-income${query({ year })}`),
  });
}

export function useStudentIncomeMonth(month: string) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: bookKeys.incomeMonth(month),
    queryFn: () => authorizedFetch<StudentIncomeMonth>("tenant", `/v1/finance/student-income/months/${month}`),
    enabled: /^\d{4}-\d{2}$/.test(month),
  });
}
