import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useAuth } from "@/lib/auth/auth-context";
import type { PageResponse } from "./courses";

/**
 * Typed client + React Query hooks for Wave 7 Finance (`/api/v1/finance/**`):
 * `finance-expense-management` (expense categories, expenses, reports) and
 * `ledger-settlement-management`'s teacher settlement foundation. Every
 * endpoint is server-gated on `FINANCE_EXPENSES` (Tenant Admin + Finance Staff
 * full; Read-only Auditor view-only; everyone else 403) — nothing here makes
 * an authorization decision.
 *
 * Income figures are always ledger-derived server-side (see
 * `docs/api/finance-expense-management.md`) — this client never computes or
 * edits income. Money values arrive as JSON numbers and are only ever
 * formatted here, never summed for display of an authoritative total.
 */

export type ExpenseMethod = "CASH" | "BANK_TRANSFER" | "CARD" | "CHEQUE" | "ONLINE" | "OTHER";

export const EXPENSE_METHODS: { value: ExpenseMethod; label: string }[] = [
  { value: "CASH", label: "Cash" },
  { value: "BANK_TRANSFER", label: "Bank transfer" },
  { value: "CARD", label: "Card" },
  { value: "CHEQUE", label: "Cheque" },
  { value: "ONLINE", label: "Online" },
  { value: "OTHER", label: "Other" },
];

export function expenseMethodLabel(method: ExpenseMethod): string {
  return EXPENSE_METHODS.find((m) => m.value === method)?.label ?? method;
}

export interface ExpenseCategory {
  id: string;
  name: string;
  description: string | null;
  archived: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface Expense {
  id: string;
  expenseDate: string;
  categoryId: string;
  categoryName: string | null;
  description: string;
  amount: number;
  currency: string;
  method: ExpenseMethod;
  reference: string | null;
  hasAttachment: boolean;
  attachmentFilename: string | null;
  attachmentMimeType: string | null;
  attachmentSizeBytes: number | null;
  createdBy: string;
  createdByEmail: string | null;
  createdAt: string;
  voided: boolean;
  voidedAt: string | null;
  voidedBy: string | null;
  voidedByEmail: string | null;
  voidReason: string | null;
}

export interface ExpenseListParams {
  from?: string;
  to?: string;
  categoryId?: string;
  method?: ExpenseMethod;
  includeVoided?: boolean;
  page?: number;
  size?: number;
}

export interface NewExpenseInput {
  categoryId: string;
  expenseDate: string;
  description: string;
  amount: string;
  method: ExpenseMethod;
  reference?: string;
  attachment?: File | null;
}

export interface DateRangeParams {
  from?: string;
  to?: string;
}

export interface IncomeSummary {
  gross: number;
  refunds: number;
  net: number;
  paymentCount: number;
  refundCount: number;
}

export interface ExpenseSummary {
  total: number;
  count: number;
  byCategory: { categoryId: string; categoryName: string | null; total: number; count: number }[];
  byMethod: { method: ExpenseMethod; total: number; count: number }[];
}

export interface FinanceSummary {
  from: string;
  to: string;
  timezone: string;
  currency: string;
  income: IncomeSummary;
  expenses: ExpenseSummary;
  netResult: number;
}

export interface CourseRevenueRow {
  courseId: string;
  courseTitle: string | null;
  teacherId: string | null;
  gross: number;
  refunds: number;
  net: number;
  paymentCount: number;
  refundCount: number;
}

export interface CourseRevenueReport {
  from: string;
  to: string;
  currency: string;
  rows: CourseRevenueRow[];
  totals: IncomeSummary;
}

export interface TeacherRevenueRow {
  teacherId: string | null;
  teacherEmail: string | null;
  courseCount: number;
  gross: number;
  refunds: number;
  net: number;
  paymentCount: number;
  refundCount: number;
}

export interface TeacherRevenueReport {
  from: string;
  to: string;
  currency: string;
  rows: TeacherRevenueRow[];
  totals: IncomeSummary;
}

export interface PeriodRow {
  period: string;
  from: string;
  to: string;
  incomeGross: number;
  refunds: number;
  incomeNet: number;
  expenses: number;
  netResult: number;
}

export interface PeriodReport {
  from: string;
  to: string;
  timezone: string;
  currency: string;
  rows: PeriodRow[];
}

export interface FinanceTeacher {
  userId: string;
  name: string;
  email: string;
}

export interface TeacherShareRate {
  id: string;
  teacherId: string;
  teacherEmail: string | null;
  sharePercent: number;
  effectiveFrom: string;
  createdBy: string;
  createdAt: string;
}

export type TeacherSettlementKind = "REGULAR" | "ADJUSTMENT";
export type TeacherSettlementStatus = "CALCULATED" | "PAID";

export interface TeacherSettlement {
  id: string;
  kind: TeacherSettlementKind;
  teacherId: string;
  teacherEmail: string | null;
  periodStart: string | null;
  periodEnd: string | null;
  grossAmount: number | null;
  refundAmount: number | null;
  netAmount: number | null;
  sharePercent: number | null;
  shareAmount: number;
  effectiveShareAmount: number;
  currency: string;
  adjustsSettlementId: string | null;
  reason: string | null;
  status: TeacherSettlementStatus;
  calculatedBy: string;
  calculatedByEmail: string | null;
  calculatedAt: string;
  paidBy: string | null;
  paidByEmail: string | null;
  paidAt: string | null;
  payoutReference: string | null;
}

export interface SettlementCourseBreakdown {
  courseId: string;
  courseTitle: string | null;
  gross: number;
  refunds: number;
  net: number;
  entryCount: number;
}

export interface TeacherSettlementDetail {
  settlement: TeacherSettlement;
  courses: SettlementCourseBreakdown[];
  adjustments: TeacherSettlement[];
}

export interface TeacherSettlementListParams {
  teacherId?: string;
  status?: TeacherSettlementStatus;
  kind?: TeacherSettlementKind;
  page?: number;
  size?: number;
}

export const financeKeys = {
  all: ["finance"] as const,
  categories: (includeArchived: boolean) =>
    [...financeKeys.all, "categories", { includeArchived }] as const,
  categoriesAll: () => [...financeKeys.all, "categories"] as const,
  expensesAll: () => [...financeKeys.all, "expenses"] as const,
  expenses: (params: ExpenseListParams) => [...financeKeys.expensesAll(), params] as const,
  reportsAll: () => [...financeKeys.all, "reports"] as const,
  report: (name: string, params: DateRangeParams) =>
    [...financeKeys.reportsAll(), name, params] as const,
  teachers: () => [...financeKeys.all, "teachers"] as const,
  ratesAll: () => [...financeKeys.all, "rates"] as const,
  settlementsAll: () => [...financeKeys.all, "settlements"] as const,
  settlements: (params: TeacherSettlementListParams) =>
    [...financeKeys.settlementsAll(), "list", params] as const,
  settlement: (id: string) => [...financeKeys.settlementsAll(), "detail", id] as const,
};

function query(params: Record<string, string | number | boolean | undefined>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== "") search.set(key, String(value));
  }
  const qs = search.toString();
  return qs ? `?${qs}` : "";
}

// ---------------------------------------------------------------------------
// Categories
// ---------------------------------------------------------------------------

export function useExpenseCategories(includeArchived = false) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: financeKeys.categories(includeArchived),
    queryFn: () =>
      authorizedFetch<ExpenseCategory[]>(
        "tenant",
        `/v1/finance/expense-categories${query({ includeArchived })}`
      ),
  });
}

export function useSaveExpenseCategory() {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, name, description }: { id?: string; name: string; description?: string }) =>
      authorizedFetch<ExpenseCategory>(
        "tenant",
        id ? `/v1/finance/expense-categories/${id}` : "/v1/finance/expense-categories",
        { method: id ? "PUT" : "POST", body: JSON.stringify({ name, description: description || null }) }
      ),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: financeKeys.categoriesAll() }),
  });
}

export function useSetCategoryArchived() {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, archived }: { id: string; archived: boolean }) =>
      authorizedFetch<ExpenseCategory>(
        "tenant",
        `/v1/finance/expense-categories/${id}/${archived ? "archive" : "unarchive"}`,
        { method: "POST" }
      ),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: financeKeys.categoriesAll() }),
  });
}

// ---------------------------------------------------------------------------
// Expenses
// ---------------------------------------------------------------------------

export function useExpenses(params: ExpenseListParams) {
  const { authorizedFetch } = useAuth();
  const qs = query({
    from: params.from,
    to: params.to,
    categoryId: params.categoryId,
    method: params.method,
    includeVoided: params.includeVoided ? true : undefined,
    page: params.page ?? 0,
    size: params.size ?? 20,
  });
  return useQuery({
    queryKey: financeKeys.expenses(params),
    queryFn: () => authorizedFetch<PageResponse<Expense>>("tenant", `/v1/finance/expenses${qs}`),
  });
}

/**
 * `POST /api/v1/finance/expenses` (multipart). No tenant/currency/creator is
 * ever sent — the server resolves all three. The receipt is validated
 * server-side (magic bytes, size) regardless of any client-side check.
 */
export function useCreateExpense() {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: NewExpenseInput) => {
      const formData = new FormData();
      formData.append("categoryId", input.categoryId);
      formData.append("expenseDate", input.expenseDate);
      formData.append("description", input.description);
      formData.append("amount", input.amount);
      formData.append("method", input.method);
      if (input.reference) formData.append("reference", input.reference);
      if (input.attachment) formData.append("attachment", input.attachment);
      return authorizedFetch<Expense>("tenant", "/v1/finance/expenses", {
        method: "POST",
        body: formData,
      });
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: financeKeys.expensesAll() });
      queryClient.invalidateQueries({ queryKey: financeKeys.reportsAll() });
    },
  });
}

/** One-way void with a mandatory reason — expenses are never edited or deleted. */
export function useVoidExpense() {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, reason }: { id: string; reason: string }) =>
      authorizedFetch<Expense>("tenant", `/v1/finance/expenses/${id}/void`, {
        method: "POST",
        body: JSON.stringify({ reason }),
      }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: financeKeys.expensesAll() });
      queryClient.invalidateQueries({ queryKey: financeKeys.reportsAll() });
    },
  });
}

/**
 * A mutation, not a query (mirrors `useSlipDownloadUrl`): a short-lived
 * signed URL is fetched fresh on every click and never cached or stored.
 */
export function useExpenseAttachmentUrl() {
  const { authorizedFetch } = useAuth();
  return useMutation({
    mutationFn: (id: string) =>
      authorizedFetch<{ url: string; expiresAt: string }>(
        "tenant",
        `/v1/finance/expenses/${id}/attachment-url`
      ),
  });
}

// ---------------------------------------------------------------------------
// Reports
// ---------------------------------------------------------------------------

function useReport<T>(name: string, params: DateRangeParams) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: financeKeys.report(name, params),
    queryFn: () =>
      authorizedFetch<T>(
        "tenant",
        `/v1/finance/reports/${name}${query({ from: params.from, to: params.to })}`
      ),
  });
}

export const useFinanceSummary = (params: DateRangeParams) =>
  useReport<FinanceSummary>("summary", params);
export const useCourseRevenue = (params: DateRangeParams) =>
  useReport<CourseRevenueReport>("course-revenue", params);
export const useTeacherRevenue = (params: DateRangeParams) =>
  useReport<TeacherRevenueReport>("teacher-revenue", params);
export const useFinancePeriods = (params: DateRangeParams) =>
  useReport<PeriodReport>("periods", params);

// ---------------------------------------------------------------------------
// Teacher settlement foundation
// ---------------------------------------------------------------------------

export function useFinanceTeachers() {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: financeKeys.teachers(),
    queryFn: () => authorizedFetch<FinanceTeacher[]>("tenant", "/v1/finance/teachers"),
  });
}

export function useTeacherShareRates() {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: financeKeys.ratesAll(),
    queryFn: () => authorizedFetch<TeacherShareRate[]>("tenant", "/v1/finance/teacher-share-rates"),
  });
}

export function useAddTeacherShareRate() {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: { teacherId: string; sharePercent: string; effectiveFrom: string }) =>
      authorizedFetch<TeacherShareRate>("tenant", "/v1/finance/teacher-share-rates", {
        method: "POST",
        body: JSON.stringify({ ...body, sharePercent: Number(body.sharePercent) }),
      }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: financeKeys.ratesAll() }),
  });
}

export function useTeacherSettlements(params: TeacherSettlementListParams) {
  const { authorizedFetch } = useAuth();
  const qs = query({
    teacherId: params.teacherId,
    status: params.status,
    kind: params.kind,
    page: params.page ?? 0,
    size: params.size ?? 20,
  });
  return useQuery({
    queryKey: financeKeys.settlements(params),
    queryFn: () =>
      authorizedFetch<PageResponse<TeacherSettlement>>(
        "tenant",
        `/v1/finance/teacher-settlements${qs}`
      ),
  });
}

export function useTeacherSettlement(id: string) {
  const { authorizedFetch } = useAuth();
  return useQuery({
    queryKey: financeKeys.settlement(id),
    queryFn: () =>
      authorizedFetch<TeacherSettlementDetail>("tenant", `/v1/finance/teacher-settlements/${id}`),
    enabled: id.length > 0,
  });
}

export function useCalculateSettlement() {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: { teacherId: string; periodStart: string; periodEnd: string }) =>
      authorizedFetch<TeacherSettlementDetail>("tenant", "/v1/finance/teacher-settlements", {
        method: "POST",
        body: JSON.stringify(body),
      }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: financeKeys.settlementsAll() }),
  });
}

export function useMarkSettlementPaid(id: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (payoutReference: string) =>
      authorizedFetch<TeacherSettlementDetail>(
        "tenant",
        `/v1/finance/teacher-settlements/${id}/mark-paid`,
        { method: "POST", body: JSON.stringify({ payoutReference: payoutReference || null }) }
      ),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: financeKeys.settlementsAll() }),
  });
}

export function useAdjustSettlement(id: string) {
  const { authorizedFetch } = useAuth();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: { amount: string; reason: string }) =>
      authorizedFetch<TeacherSettlementDetail>(
        "tenant",
        `/v1/finance/teacher-settlements/${id}/adjustments`,
        { method: "POST", body: JSON.stringify({ amount: Number(body.amount), reason: body.reason }) }
      ),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: financeKeys.settlementsAll() }),
  });
}
