"use client";

import { useState } from "react";
import Link from "next/link";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { Pencil, Plus, Search, Undo2 } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { DataTable, type DataTableColumn } from "@/components/ui/data-table";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { LiveRegion } from "@/components/ui/live-region";
import { Textarea } from "@/components/ui/textarea";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { FinanceHubFrame } from "@/components/finance/finance-tabs";
import { RecordTransactionDialog } from "@/components/finance/book/record-transaction-dialog";
import {
  FieldError,
  FinanceDialog,
  FormAlert,
  InfoNote,
  SELECT_CLASS,
  TransactionTypeBadge,
  applyServerError,
  errorMessage,
  formatDate,
  todayIso,
  useFinanceGates,
} from "@/components/finance/book/shared";
import { formatMoney } from "@/lib/format";
import { useExpenseCategories } from "@/lib/api/finance";
import {
  useFinanceAccounts,
  useFinanceTransactions,
  usePatchTransaction,
  usePostUnposted,
  useReconcilePayments,
  useReverseTransaction,
  useUnpostedTransactions,
  type CorrectionOwner,
  type FinanceTransaction,
  type ReconcileResult,
  type TransactionListParams,
  type TransactionStatus,
  type TransactionType,
} from "@/lib/api/finance-book";
import {
  editTransactionSchema,
  reconcileSchema,
  reverseTransactionSchema,
  type EditTransactionFormValues,
  type ReconcileFormValues,
  type ReverseTransactionFormValues,
} from "@/lib/validation/finance-book";

const PAGE_SIZES = [10, 25, 50];

const OWNER: Record<CorrectionOwner, { label: string; href: string }> = {
  PAYMENTS: { label: "Correct in Payments", href: "/tenant-admin/payments" },
  TEACHER_WALLET: { label: "Teacher wallet", href: "/tenant-admin/finance/teacher-payouts" },
  EXPENSES: { label: "Void in Expenses", href: "/tenant-admin/finance/expenses" },
};

const SOURCE_LABEL: Record<FinanceTransaction["sourceType"], string> = {
  MANUAL: "Manual",
  LEDGER_ENTRY: "Student payment",
  EXPENSE: "Expense",
  WALLET_ENTRY: "Teacher payout",
  SCHEDULE: "Scheduled",
};

/**
 * FIN-01 Transactions tab (Klass parity): server-paged, filterable list of
 * account-book rows, Record Transaction, note/category edit (amounts are
 * immutable), reversal with a reason, and the Unposted queue (student
 * payments with no routed account) with an opt-in Reconcile for payments
 * before the cutover. Student-payment rows are corrected in Payments, never
 * here - the server returns `reversibleHere` / `correctionOwner` for that.
 */
export default function FinanceTransactionsPage() {
  const gates = useFinanceGates();
  const [filters, setFilters] = useState<TransactionListParams>({});
  const [searchDraft, setSearchDraft] = useState("");
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(10);
  const [recordOpen, setRecordOpen] = useState(false);
  const [editing, setEditing] = useState<FinanceTransaction | null>(null);
  const [reversing, setReversing] = useState<FinanceTransaction | null>(null);
  const accounts = useFinanceAccounts(true);
  const categories = useExpenseCategories(true);
  const query = useFinanceTransactions({ ...filters, page, size });

  const setFilter = (patch: Partial<TransactionListParams>) => {
    setFilters((f) => ({ ...f, ...patch }));
    setPage(0);
  };

  const columns: DataTableColumn<FinanceTransaction>[] = [
    { key: "type", header: "Type", cell: (row) => <TypeCell row={row} />, hideOnCard: true },
    { key: "amount", header: "Amount", cell: (row) => <AmountCell row={row} />, hideOnCard: true },
    { key: "category", header: "Category", cell: (row) => row.categoryName ?? (row.sourceType === "LEDGER_ENTRY" ? "Student payment" : "—") },
    { key: "from", header: "From account", cell: (row) => row.fromAccountName ?? "—" },
    { key: "to", header: "To account", cell: (row) => row.toAccountName ?? "—" },
    { key: "date", header: "Transaction date", cell: (row) => formatDate(row.transactionDate) },
    { key: "source", header: "Source", cell: (row) => SOURCE_LABEL[row.sourceType] },
    {
      key: "note",
      header: "Note",
      cell: (row) => <span className="line-clamp-2 max-w-56 break-words">{row.reason ? `${row.note ?? ""} (${row.reason})`.trim() : (row.note ?? "—")}</span>,
    },
    {
      key: "actions",
      header: "Actions",
      hideOnCard: true,
      cell: (row) => <RowActions row={row} gates={gates} onEdit={() => setEditing(row)} onReverse={() => setReversing(row)} />,
    },
  ];

  return (
    <FinanceHubFrame
      title="Transactions"
      description="Every posting to your accounts, newest first. Amounts can't be edited; a mistake is corrected by a reversal."
      actions={
        gates.record ? (
          <Button type="button" onClick={() => setRecordOpen(true)}>
            <Plus aria-hidden="true" />
            Record transaction
          </Button>
        ) : null
      }
    >
      <UnpostedPanel canPost={gates.record} canReconcile={gates.routing} />

      <form
        role="search"
        aria-label="Filter transactions"
        className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4"
        onSubmit={(event) => {
          event.preventDefault();
          setFilter({ search: searchDraft });
        }}
      >
        <div className="flex flex-col gap-1.5 sm:col-span-2">
          <Label htmlFor="tx-search">Search notes</Label>
          <div className="flex gap-2">
            <Input id="tx-search" type="search" value={searchDraft} onChange={(e) => setSearchDraft(e.target.value)} />
            <Button type="submit" variant="outline" size="icon" aria-label="Search">
              <Search aria-hidden="true" />
            </Button>
          </div>
        </div>
        <FilterSelect id="tx-filter-type" label="Type" value={filters.type} onChange={(v) => setFilter({ type: v as TransactionType | undefined })}
          options={["INCOME", "EXPENSE", "TRANSFER", "ADJUSTMENT", "REFUND", "REVERSAL", "OPENING"].map((t) => ({ value: t, label: t.charAt(0) + t.slice(1).toLowerCase() }))} />
        <FilterSelect id="tx-filter-status" label="Status" value={filters.status} onChange={(v) => setFilter({ status: v as TransactionStatus | undefined })}
          options={[{ value: "POSTED", label: "Posted" }, { value: "REVERSED", label: "Reversed" }, { value: "UNPOSTED", label: "Unposted" }]} />
        <FilterSelect id="tx-filter-account" label="Account" value={filters.accountId} onChange={(v) => setFilter({ accountId: v })}
          options={(accounts.data ?? []).map((a) => ({ value: a.id, label: a.archived ? `${a.name} (archived)` : a.name }))} />
        <FilterSelect id="tx-filter-category" label="Category" value={filters.categoryId} onChange={(v) => setFilter({ categoryId: v })}
          options={(categories.data ?? []).map((c) => ({ value: c.id, label: c.name }))} />
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="tx-filter-from">From</Label>
          <Input id="tx-filter-from" type="date" value={filters.from ?? ""} onChange={(e) => setFilter({ from: e.target.value || undefined })} />
        </div>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="tx-filter-to">To</Label>
          <Input id="tx-filter-to" type="date" value={filters.to ?? ""} onChange={(e) => setFilter({ to: e.target.value || undefined })} />
        </div>
      </form>

      <QueryStateBoundary
        query={query}
        loadingLabel="Loading transactions…"
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
        isEmpty={(data) => data.content.length === 0}
        emptyState={{
          title: "No transactions",
          description: Object.values(filters).some(Boolean)
            ? "Nothing matches these filters."
            : "Postings appear here when payments are confirmed or your team records a transaction.",
          action: gates.record ? { label: "Record transaction", onClick: () => setRecordOpen(true) } : undefined,
        }}
      >
        {(data) => (
          <div className="flex flex-col gap-3">
            <DataTable
              columns={columns}
              rows={data.content}
              rowKey={(row) => row.id}
              caption="Finance transactions"
              cardHeading={(row) => <AmountCell row={row} />}
              cardHeadingAdornment={(row) => <TypeCell row={row} />}
              cardFooter={(row) => <RowActions row={row} gates={gates} onEdit={() => setEditing(row)} onReverse={() => setReversing(row)} />}
            />
            <div className="flex flex-wrap items-center justify-between gap-3 text-sm">
              <div className="flex items-center gap-2">
                <Label htmlFor="tx-page-size">Items per page</Label>
                <select id="tx-page-size" className={`${SELECT_CLASS} w-20`} value={size} onChange={(e) => { setSize(Number(e.target.value)); setPage(0); }}>
                  {PAGE_SIZES.map((s) => (
                    <option key={s} value={s}>
                      {s}
                    </option>
                  ))}
                </select>
              </div>
              <div className="flex items-center gap-2">
                <Button type="button" variant="outline" size="sm" disabled={page === 0} onClick={() => setPage((p) => Math.max(0, p - 1))}>
                  Previous
                </Button>
                <span className="text-xs text-muted-foreground" data-testid="tx-page-info">
                  {data.totalElements === 0
                    ? "0 of 0"
                    : `${data.page * data.size + 1}–${data.page * data.size + data.content.length} of ${data.totalElements}`}
                </span>
                <Button type="button" variant="outline" size="sm" disabled={data.page + 1 >= data.totalPages} onClick={() => setPage((p) => p + 1)}>
                  Next
                </Button>
              </div>
            </div>
          </div>
        )}
      </QueryStateBoundary>

      <RecordTransactionDialog open={recordOpen} onOpenChange={setRecordOpen} />
      <FinanceDialog open={editing !== null} onOpenChange={(o) => !o && setEditing(null)} title="Edit transaction" description="Only the note and category can change." testId="edit-transaction-dialog">
        {editing ? <EditForm row={editing} onDone={() => setEditing(null)} /> : null}
      </FinanceDialog>
      <FinanceDialog open={reversing !== null} onOpenChange={(o) => !o && setReversing(null)} title="Reverse transaction" testId="reverse-transaction-dialog">
        {reversing ? <ReverseForm row={reversing} onDone={() => setReversing(null)} /> : null}
      </FinanceDialog>
    </FinanceHubFrame>
  );
}

function FilterSelect({
  id,
  label,
  value,
  options,
  onChange,
}: {
  id: string;
  label: string;
  value: string | undefined;
  options: { value: string; label: string }[];
  onChange: (value: string | undefined) => void;
}) {
  return (
    <div className="flex flex-col gap-1.5">
      <Label htmlFor={id}>{label}</Label>
      <select id={id} className={SELECT_CLASS} value={value ?? ""} onChange={(e) => onChange(e.target.value || undefined)}>
        <option value="">All</option>
        {options.map((o) => (
          <option key={o.value} value={o.value}>
            {o.label}
          </option>
        ))}
      </select>
    </div>
  );
}

function TypeCell({ row }: { row: FinanceTransaction }) {
  return (
    <span className="flex flex-wrap items-center gap-1">
      <TransactionTypeBadge type={row.type} />
      {row.status !== "POSTED" ? <Badge variant="outline">{row.status === "REVERSED" ? "Reversed" : "Unposted"}</Badge> : null}
    </span>
  );
}

function AmountCell({ row }: { row: FinanceTransaction }) {
  const outflow = row.type === "EXPENSE" || row.type === "REFUND";
  const inflow = row.type === "INCOME" || row.type === "OPENING";
  return (
    <span
      className={
        row.status === "REVERSED"
          ? "text-muted-foreground tabular-nums line-through"
          : inflow
            ? "text-green-700 tabular-nums dark:text-green-400"
            : outflow
              ? "text-red-700 tabular-nums dark:text-red-400"
              : "tabular-nums"
      }
    >
      {formatMoney(row.amount, row.currency)}
    </span>
  );
}

function RowActions({
  row,
  gates,
  onEdit,
  onReverse,
}: {
  row: FinanceTransaction;
  gates: ReturnType<typeof useFinanceGates>;
  onEdit: () => void;
  onReverse: () => void;
}) {
  const editable = gates.record && row.status === "POSTED" && row.type !== "REVERSAL" && row.type !== "OPENING";
  const reversible = gates.reverse && row.status === "POSTED" && row.reversibleHere;
  const owner = row.status === "POSTED" && !row.reversibleHere && row.correctionOwner ? OWNER[row.correctionOwner] : null;
  if (!editable && !reversible && !owner) return <span className="text-muted-foreground">—</span>;
  return (
    <div className="flex flex-wrap items-center gap-1">
      {editable ? (
        <Button type="button" variant="ghost" size="icon-sm" aria-label={`Edit note and category of ${row.type.toLowerCase()} on ${row.transactionDate}`} onClick={onEdit}>
          <Pencil aria-hidden="true" />
        </Button>
      ) : null}
      {reversible ? (
        <Button type="button" variant="ghost" size="icon-sm" className="text-destructive" aria-label={`Reverse ${row.type.toLowerCase()} on ${row.transactionDate}`} onClick={onReverse}>
          <Undo2 aria-hidden="true" />
        </Button>
      ) : null}
      {owner ? (
        <Link href={owner.href} className="text-xs underline underline-offset-4">
          {owner.label}
        </Link>
      ) : null}
    </div>
  );
}

function EditForm({ row, onDone }: { row: FinanceTransaction; onDone: () => void }) {
  const patch = usePatchTransaction();
  const categories = useExpenseCategories(false);
  const [formError, setFormError] = useState<string | null>(null);
  const categorised = row.type === "INCOME" || row.type === "EXPENSE";
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<EditTransactionFormValues>({ resolver: zodResolver(editTransactionSchema), defaultValues: { note: row.note ?? "", categoryId: row.categoryId ?? "" } });

  const onSubmit = handleSubmit(async (values) => {
    setFormError(null);
    try {
      await patch.mutateAsync({ id: row.id, note: values.note, categoryId: categorised ? values.categoryId || null : null });
      onDone();
    } catch (e) {
      setFormError(applyServerError(e, ["note", "categoryId"], setError, "Could not save the change."));
    }
  });

  return (
    <form className="flex flex-col gap-4" noValidate aria-busy={patch.isPending} onSubmit={onSubmit}>
      <LiveRegion message={patch.isPending ? "Saving…" : ""} />
      <FormAlert message={formError} />
      <p className="text-sm">
        <TransactionTypeBadge type={row.type} /> <span className="font-semibold tabular-nums">{formatMoney(row.amount, row.currency)}</span>{" "}
        <span className="text-muted-foreground">on {formatDate(row.transactionDate)}</span>
      </p>
      {categorised ? (
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="edit-tx-category">Category</Label>
          <select id="edit-tx-category" className={SELECT_CLASS} {...register("categoryId")}>
            <option value="">{row.sourceType === "LEDGER_ENTRY" ? "Student payment (default)" : "Select…"}</option>
            {(categories.data ?? []).map((c) => (
              <option key={c.id} value={c.id}>
                {c.name}
              </option>
            ))}
          </select>
          <FieldError id="edit-tx-category-error" message={errors.categoryId?.message} />
        </div>
      ) : null}
      <div className="flex flex-col gap-1.5">
        <Label htmlFor="edit-tx-note">Note</Label>
        <Textarea id="edit-tx-note" rows={3} {...register("note")} />
        <FieldError id="edit-tx-note-error" message={errors.note?.message} />
      </div>
      <div className="flex justify-end gap-2">
        <Button type="button" variant="outline" onClick={onDone} disabled={patch.isPending}>
          Cancel
        </Button>
        <Button type="submit" disabled={patch.isPending}>
          {patch.isPending ? "Saving…" : "Save"}
        </Button>
      </div>
    </form>
  );
}

function ReverseForm({ row, onDone }: { row: FinanceTransaction; onDone: () => void }) {
  const reverse = useReverseTransaction();
  const [formError, setFormError] = useState<string | null>(null);
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<ReverseTransactionFormValues>({ resolver: zodResolver(reverseTransactionSchema), defaultValues: { reason: "" } });

  const onSubmit = handleSubmit(async ({ reason }) => {
    setFormError(null);
    try {
      await reverse.mutateAsync({ id: row.id, reason });
      onDone();
    } catch (e) {
      setFormError(applyServerError(e, ["reason"], setError, "Could not reverse the transaction."));
    }
  });

  return (
    <form className="flex flex-col gap-4" noValidate aria-busy={reverse.isPending} onSubmit={onSubmit}>
      <LiveRegion message={reverse.isPending ? "Reversing…" : ""} />
      <FormAlert message={formError} />
      <p className="text-sm">
        Reverse <TransactionTypeBadge type={row.type} />{" "}
        <span className="font-semibold tabular-nums">{formatMoney(row.amount, row.currency)}</span> from{" "}
        {formatDate(row.transactionDate)}?
      </p>
      <InfoNote>
        {row.sourceType === "EXPENSE"
          ? "This voids the expense as well. Both stay in the history, marked as reversed."
          : "A reversing entry is added and balances are restored. The original stays in the history, marked as reversed."}
      </InfoNote>
      <div className="flex flex-col gap-1.5">
        <Label htmlFor="reverse-reason">Reason</Label>
        <Textarea
          id="reverse-reason"
          rows={2}
          aria-invalid={!!errors.reason}
          {...register("reason")}
        />
        <FieldError id="reverse-reason-error" message={errors.reason?.message} />
      </div>
      <div className="flex justify-end gap-2">
        <Button type="button" variant="outline" onClick={onDone} disabled={reverse.isPending}>
          Cancel
        </Button>
        <Button type="submit" variant="destructive" disabled={reverse.isPending}>
          {reverse.isPending ? "Reversing…" : "Reverse"}
        </Button>
      </div>
    </form>
  );
}

/**
 * Student payments confirmed while their method had no routed account. Each
 * row is posted once to an account (the server refuses a second post).
 */
function UnpostedPanel({ canPost, canReconcile }: { canPost: boolean; canReconcile: boolean }) {
  const unposted = useUnpostedTransactions();
  const accounts = useFinanceAccounts(false);
  const [reconcileOpen, setReconcileOpen] = useState(false);
  const rows = unposted.data ?? [];
  if (!unposted.isSuccess || (rows.length === 0 && !canReconcile)) return null;

  return (
    <section aria-labelledby="unposted-heading" className="flex flex-col gap-3 rounded-xl border border-amber-300 bg-amber-50/50 p-4 dark:border-amber-900 dark:bg-amber-950/30" data-testid="unposted-panel">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div>
          <h3 id="unposted-heading" className="text-sm font-semibold text-foreground">
            Unposted ({rows.length})
          </h3>
          <p className="text-xs text-muted-foreground">
            Student payments and refunds whose payment method has no account. Choose an account to post each one, or set
            up routing under Accounts → Configure.
          </p>
        </div>
        {canReconcile ? (
          <Button type="button" variant="outline" size="sm" onClick={() => setReconcileOpen(true)}>
            Reconcile past payments
          </Button>
        ) : null}
      </div>
      {rows.length > 0 ? (
        <ul className="flex flex-col divide-y divide-border">
          {rows.map((row) => (
            <UnpostedRow key={row.id} row={row} canPost={canPost} accounts={(accounts.data ?? []).map((a) => ({ id: a.id, name: a.name }))} />
          ))}
        </ul>
      ) : null}
      <FinanceDialog open={reconcileOpen} onOpenChange={setReconcileOpen} title="Reconcile past payments" testId="reconcile-dialog">
        <ReconcileForm onDone={() => setReconcileOpen(false)} />
      </FinanceDialog>
    </section>
  );
}

function UnpostedRow({ row, canPost, accounts }: { row: FinanceTransaction; canPost: boolean; accounts: { id: string; name: string }[] }) {
  const post = usePostUnposted();
  const [accountId, setAccountId] = useState("");
  const [error, setError] = useState<string | null>(null);
  const selectId = `unposted-account-${row.id}`;
  return (
    <li className="flex flex-col gap-2 py-2 sm:flex-row sm:items-center sm:justify-between">
      <div className="flex flex-wrap items-center gap-2 text-sm">
        <TransactionTypeBadge type={row.type} />
        <span className="font-semibold tabular-nums">{formatMoney(row.amount, row.currency)}</span>
        <span className="text-muted-foreground">{formatDate(row.transactionDate)}</span>
        {row.note ? <span className="text-muted-foreground">· {row.note}</span> : null}
      </div>
      {canPost ? (
        <div className="flex flex-col gap-1">
          <div className="flex items-center gap-2">
            <label htmlFor={selectId} className="sr-only">
              Account for {row.type.toLowerCase()} of {formatMoney(row.amount, row.currency)}
            </label>
            <select id={selectId} className={`${SELECT_CLASS} sm:w-48`} value={accountId} onChange={(e) => setAccountId(e.target.value)}>
              <option value="">Choose account…</option>
              {accounts.map((a) => (
                <option key={a.id} value={a.id}>
                  {a.name}
                </option>
              ))}
            </select>
            <Button
              type="button"
              size="sm"
              disabled={!accountId || post.isPending}
              onClick={async () => {
                setError(null);
                try {
                  await post.mutateAsync({ id: row.id, accountId });
                } catch (e) {
                  setError(errorMessage(e));
                }
              }}
            >
              {post.isPending ? "Posting…" : "Post"}
            </Button>
          </div>
          {error ? (
            <p role="alert" className="text-xs text-destructive">
              {error}
            </p>
          ) : null}
        </div>
      ) : null}
    </li>
  );
}

function ReconcileForm({ onDone }: { onDone: () => void }) {
  const reconcile = useReconcilePayments();
  const [result, setResult] = useState<ReconcileResult | null>(null);
  const [formError, setFormError] = useState<string | null>(null);
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<ReconcileFormValues>({ resolver: zodResolver(reconcileSchema), defaultValues: { from: "" } });

  const onSubmit = handleSubmit(async ({ from }) => {
    setFormError(null);
    try {
      setResult(await reconcile.mutateAsync(from));
    } catch (e) {
      setFormError(applyServerError(e, ["from"], setError, "Could not reconcile payments."));
    }
  });

  return (
    <form className="flex flex-col gap-4" noValidate aria-busy={reconcile.isPending} onSubmit={onSubmit}>
      <LiveRegion message={reconcile.isPending ? "Reconciling…" : ""} />
      <p className="text-sm text-muted-foreground">
        Adds confirmed cash payments since this date that are not yet in the book. Payments whose method has an account are
        posted to it; the rest appear under Unposted. Payments already in the book are skipped, so it is safe to run again.
      </p>
      <FormAlert message={formError} />
      <div className="flex flex-col gap-1.5">
        <Label htmlFor="reconcile-from">Start date</Label>
        <Input id="reconcile-from" type="date" max={todayIso()} aria-invalid={!!errors.from} {...register("from")} />
        <FieldError id="reconcile-from-error" message={errors.from?.message} />
      </div>
      {result ? (
        <p role="status" className="text-sm" data-testid="reconcile-result">
          {formatDate(result.from)} to {formatDate(result.to)}: {result.posted} posted, {result.unposted} unposted, {result.skipped} already in the book.
        </p>
      ) : null}
      <div className="flex justify-end gap-2">
        <Button type="button" variant="outline" onClick={onDone} disabled={reconcile.isPending}>
          {result ? "Close" : "Cancel"}
        </Button>
        {!result ? (
          <Button type="submit" disabled={reconcile.isPending}>
            {reconcile.isPending ? "Reconciling…" : "Reconcile"}
          </Button>
        ) : null}
      </div>
    </form>
  );
}
