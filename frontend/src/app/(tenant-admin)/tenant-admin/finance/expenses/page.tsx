"use client";

import { useState } from "react";
import { AlertCircle, FileText, Plus } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { DataTable, type DataTableColumn } from "@/components/ui/data-table";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { DateRangeFilter } from "@/components/finance/date-range-filter";
import { useAuth } from "@/lib/auth/auth-context";
import { canManageFinance } from "@/lib/auth/permissions";
import { formatMoney } from "@/lib/format";
import {
  EXPENSE_METHODS,
  expenseMethodLabel,
  useExpenseAttachmentUrl,
  useExpenseCategories,
  useExpenses,
  type DateRangeParams,
  type Expense,
  type ExpenseMethod,
} from "@/lib/api/finance";
import { ExpenseForm } from "./expense-form";
import { VoidExpenseDialog } from "./void-expense-dialog";

const ALL = "__all__";
const PAGE_SIZE = 20;

/**
 * Tenant Admin Expenses (Wave 7, PAR-23-01/05). Append-only: no edit or delete
 * control exists; voiding is the only correction and keeps the record.
 * Record/void actions render only for `canManageFinance(role)` (UX only —
 * Read-only Auditor still gets a real backend 403 on any direct request).
 */
export default function ExpensesPage() {
  const { session } = useAuth();
  const canManage = canManageFinance(session?.role ?? null);
  const [range, setRange] = useState<DateRangeParams>({});
  const [categoryId, setCategoryId] = useState<string>(ALL);
  const [method, setMethod] = useState<string>(ALL);
  const [includeVoided, setIncludeVoided] = useState(false);
  const [page, setPage] = useState(0);
  const [showForm, setShowForm] = useState(false);

  const categoriesQuery = useExpenseCategories(true);
  const activeCategories = (categoriesQuery.data ?? []).filter((c) => !c.archived);
  const filtered =
    !!range.from || !!range.to || categoryId !== ALL || method !== ALL || includeVoided;
  const expensesQuery = useExpenses({
    ...range,
    categoryId: categoryId === ALL ? undefined : categoryId,
    method: method === ALL ? undefined : (method as ExpenseMethod),
    includeVoided,
    page,
    size: PAGE_SIZE,
  });

  const resetFilters = () => {
    setRange({});
    setCategoryId(ALL);
    setMethod(ALL);
    setIncludeVoided(false);
    setPage(0);
  };

  const columns: DataTableColumn<Expense>[] = [
    { key: "date", header: "Date", cell: (row) => row.expenseDate },
    { key: "category", header: "Category", cell: (row) => row.categoryName ?? "—" },
    { key: "description", header: "Description", cell: (row) => row.description },
    { key: "amount", header: "Amount", cell: (row) => formatMoney(row.amount, row.currency) },
    { key: "method", header: "Method", cell: (row) => expenseMethodLabel(row.method) },
    { key: "reference", header: "Reference", cell: (row) => row.reference ?? "—" },
    { key: "createdBy", header: "Recorded by", cell: (row) => row.createdByEmail ?? "—" },
    { key: "status", header: "Status", cell: (row) => <ExpenseStatus expense={row} />, hideOnCard: true },
    {
      key: "actions",
      header: "Actions",
      hideOnCard: true,
      cell: (row) => <RowActions expense={row} canManage={canManage} />,
    },
  ];

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
        <div>
          <h1 className="text-xl font-semibold text-foreground">Expenses</h1>
          <p className="text-sm text-muted-foreground">
            Institute expenses. Records cannot be edited or deleted — void a record and add a
            corrected one instead.
          </p>
        </div>
        {canManage && !showForm ? (
          <Button type="button" onClick={() => setShowForm(true)} disabled={activeCategories.length === 0}>
            <Plus aria-hidden="true" />
            Record expense
          </Button>
        ) : null}
      </div>

      {canManage && categoriesQuery.isSuccess && activeCategories.length === 0 ? (
        <Alert>
          <AlertCircle aria-hidden="true" />
          <AlertDescription>
            Create an expense category first under Finance → Expense Categories.
          </AlertDescription>
        </Alert>
      ) : null}

      {showForm ? <ExpenseForm categories={activeCategories} onDone={() => setShowForm(false)} /> : null}

      <div className="flex flex-col gap-3 rounded-lg border border-border p-4">
        <DateRangeFilter
          idPrefix="expenses"
          value={range}
          onApply={(next) => {
            setRange(next);
            setPage(0);
          }}
        />
        <div className="flex flex-col gap-3 sm:flex-row sm:items-end">
          <div className="flex flex-col gap-1.5 sm:w-56">
            <Label htmlFor="expense-filter-category">Category</Label>
            <Select
              value={categoryId}
              onValueChange={(v) => {
                setCategoryId(v ?? ALL);
                setPage(0);
              }}
            >
              <SelectTrigger id="expense-filter-category" className="w-full">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value={ALL}>All categories</SelectItem>
                {(categoriesQuery.data ?? []).map((category) => (
                  <SelectItem key={category.id} value={category.id}>
                    {category.name}
                    {category.archived ? " (archived)" : ""}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          <div className="flex flex-col gap-1.5 sm:w-48">
            <Label htmlFor="expense-filter-method">Method</Label>
            <Select
              value={method}
              onValueChange={(v) => {
                setMethod(v ?? ALL);
                setPage(0);
              }}
            >
              <SelectTrigger id="expense-filter-method" className="w-full">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value={ALL}>All methods</SelectItem>
                {EXPENSE_METHODS.map((m) => (
                  <SelectItem key={m.value} value={m.value}>
                    {m.label}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          <label className="flex items-center gap-2 text-sm" htmlFor="expense-filter-voided">
            <input
              id="expense-filter-voided"
              type="checkbox"
              className="size-4"
              checked={includeVoided}
              onChange={(event) => {
                setIncludeVoided(event.target.checked);
                setPage(0);
              }}
            />
            Show voided
          </label>
        </div>
      </div>

      <QueryStateBoundary
        query={expensesQuery}
        loadingLabel="Loading expenses…"
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
        isEmpty={(data) => data.content.length === 0}
        emptyState={
          filtered
            ? {
                title: "No expenses match your filters",
                description: "Try a different date range, category or method.",
                action: { label: "Clear filters", onClick: resetFilters },
              }
            : {
                title: "No expenses recorded yet",
                description: canManage
                  ? "Record your first expense to start tracking institute spending."
                  : "Expenses recorded by your finance team will appear here.",
              }
        }
      >
        {(data) => (
          <div className="flex flex-col gap-4">
            <DataTable
              columns={columns}
              rows={data.content}
              rowKey={(row) => row.id}
              caption="Expenses"
              cardHeading={(row) => `${row.description} — ${formatMoney(row.amount, row.currency)}`}
              cardHeadingAdornment={(row) => <ExpenseStatus expense={row} />}
              cardFooter={(row) => <RowActions expense={row} canManage={canManage} />}
            />
            <div className="flex items-center justify-between">
              <Button
                type="button"
                variant="outline"
                size="sm"
                disabled={page === 0}
                onClick={() => setPage((p) => Math.max(0, p - 1))}
              >
                Previous
              </Button>
              <span className="text-xs text-muted-foreground">
                Page {data.page + 1} of {Math.max(data.totalPages, 1)}
              </span>
              <Button
                type="button"
                variant="outline"
                size="sm"
                disabled={data.page + 1 >= data.totalPages}
                onClick={() => setPage((p) => p + 1)}
              >
                Next
              </Button>
            </div>
          </div>
        )}
      </QueryStateBoundary>
    </div>
  );
}

function ExpenseStatus({ expense }: { expense: Expense }) {
  if (!expense.voided) return <Badge variant="secondary">Recorded</Badge>;
  return (
    <span className="flex flex-col gap-0.5">
      <Badge variant="destructive">Voided</Badge>
      {expense.voidReason ? (
        <span className="text-xs text-muted-foreground">Reason: {expense.voidReason}</span>
      ) : null}
    </span>
  );
}

function RowActions({ expense, canManage }: { expense: Expense; canManage: boolean }) {
  return (
    <div className="flex flex-wrap gap-2">
      {expense.hasAttachment ? <ReceiptButton expense={expense} /> : null}
      {canManage && !expense.voided ? <VoidExpenseDialog expense={expense} /> : null}
    </div>
  );
}

/** Fetches a fresh short-lived signed URL on every click; never cached or rendered. */
function ReceiptButton({ expense }: { expense: Expense }) {
  const mutation = useExpenseAttachmentUrl();
  const [error, setError] = useState<string | null>(null);
  return (
    <span className="flex flex-col gap-1">
      <Button
        type="button"
        variant="outline"
        size="sm"
        disabled={mutation.isPending}
        aria-label={`View receipt for ${expense.description}`}
        onClick={async () => {
          setError(null);
          try {
            const { url } = await mutation.mutateAsync(expense.id);
            window.open(url, "_blank", "noopener,noreferrer");
          } catch {
            setError("Couldn't open the receipt. Please try again.");
          }
        }}
      >
        <FileText aria-hidden="true" />
        Receipt
      </Button>
      {error ? (
        <span role="alert" className="text-xs text-destructive">
          {error}
        </span>
      ) : null}
    </span>
  );
}
