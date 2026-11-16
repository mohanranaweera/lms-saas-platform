"use client";

import { useState } from "react";
import { useForm, useWatch } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { ChevronDown, ChevronUp, Pencil, Plus, Search, XCircle } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardFooter } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { LiveRegion } from "@/components/ui/live-region";
import { Textarea } from "@/components/ui/textarea";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { FinanceHubFrame } from "@/components/finance/finance-tabs";
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
  useCancelSchedule,
  useDueOccurrences,
  useFinanceAccounts,
  useFinanceSchedules,
  usePostOccurrence,
  useSaveSchedule,
  useScheduleOccurrences,
  useSkipOccurrence,
  type FinanceSchedule,
  type OccurrenceStatus,
  type ScheduleInput,
  type ScheduleOccurrence,
  type ScheduleStatus,
} from "@/lib/api/finance-book";
import { scheduleSchema, type ScheduleFormValues } from "@/lib/validation/finance-book";

const OCCURRENCE_LABEL: Record<OccurrenceStatus, string> = {
  DUE: "Due",
  POSTED: "Recorded",
  FAILED: "Failed",
  SKIPPED: "Skipped",
};

const STATUS_LABEL: Record<ScheduleStatus, string> = {
  ACTIVE: "Active",
  COMPLETED: "Completed",
  CANCELLED: "Cancelled",
};

function monthLabel(yyyyMm: string): string {
  const [y, m] = yyyyMm.split("-").map(Number);
  if (!y || !m) return yyyyMm;
  return new Intl.DateTimeFormat("en-US", { month: "long", year: "numeric", timeZone: "UTC" }).format(new Date(Date.UTC(y, m - 1, 1)));
}

function cadence(s: FinanceSchedule): string {
  return s.scheduleType === "MONTHLY" ? `Monthly on day ${s.dayOfMonth}` : `Once on ${formatDate(s.scheduledDate)}`;
}

/**
 * FIN-01 Scheduled tab. LMS policy (differs from Klass): the scheduler only
 * makes occurrences due; money moves only when an authorised user records an
 * occurrence. Monthly schedules skip months that lack the chosen day. Failed
 * postings keep their reason and can be retried. Cancelling keeps recorded
 * occurrences.
 */
export default function FinanceScheduledPage() {
  const gates = useFinanceGates();
  const [status, setStatus] = useState<ScheduleStatus | undefined>("ACTIVE");
  const [searchDraft, setSearchDraft] = useState("");
  const [search, setSearch] = useState("");
  const [page, setPage] = useState(0);
  const [editing, setEditing] = useState<FinanceSchedule | "new" | null>(null);
  const query = useFinanceSchedules({ search, status, page, size: 20 });

  return (
    <FinanceHubFrame
      title="Scheduled transactions"
      description="Recurring or future income, expenses and transfers."
      actions={
        gates.schedulesManage ? (
          <Button type="button" onClick={() => setEditing("new")}>
            <Plus aria-hidden="true" />
            Schedule transaction
          </Button>
        ) : null
      }
    >
      <InfoNote testId="schedule-policy-note">
        Scheduled transactions are not posted automatically. When one falls due it appears under &quot;Due now&quot; and a
        finance user records it. Monthly schedules on the 29th–31st skip months that don&apos;t have that day.
      </InfoNote>

      <DuePanel canPost={gates.record} canSkip={gates.schedulesManage} />

      <form
        role="search"
        aria-label="Filter scheduled transactions"
        className="flex flex-col gap-3 sm:flex-row sm:items-end"
        onSubmit={(event) => {
          event.preventDefault();
          setSearch(searchDraft);
          setPage(0);
        }}
      >
        <div className="flex flex-1 flex-col gap-1.5">
          <Label htmlFor="schedule-search">Search by name or note</Label>
          <div className="flex gap-2">
            <Input id="schedule-search" type="search" value={searchDraft} onChange={(e) => setSearchDraft(e.target.value)} />
            <Button type="submit" variant="outline" size="icon" aria-label="Search">
              <Search aria-hidden="true" />
            </Button>
          </div>
        </div>
        <div className="flex flex-col gap-1.5 sm:w-48">
          <Label htmlFor="schedule-status">Status</Label>
          <select
            id="schedule-status"
            className={SELECT_CLASS}
            value={status ?? ""}
            onChange={(e) => {
              setStatus((e.target.value || undefined) as ScheduleStatus | undefined);
              setPage(0);
            }}
          >
            <option value="">All</option>
            <option value="ACTIVE">Active</option>
            <option value="COMPLETED">Completed</option>
            <option value="CANCELLED">Cancelled</option>
          </select>
        </div>
      </form>

      <QueryStateBoundary
        query={query}
        loadingLabel="Loading scheduled transactions…"
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
        isEmpty={(data) => data.content.length === 0}
        emptyState={{
          title: "No scheduled transactions",
          description: gates.schedulesManage
            ? "Schedule rent, salaries or other repeating amounts so they are ready to record when due."
            : "Nothing is scheduled yet.",
          action: gates.schedulesManage ? { label: "Schedule transaction", onClick: () => setEditing("new") } : undefined,
        }}
      >
        {(data) => (
          <div className="flex flex-col gap-3">
            <ul className="grid grid-cols-1 gap-4 lg:grid-cols-2" aria-label="Scheduled transactions">
              {data.content.map((schedule) => (
                <li key={schedule.id}>
                  <ScheduleCard schedule={schedule} canManage={gates.schedulesManage} onEdit={() => setEditing(schedule)} />
                </li>
              ))}
            </ul>
            {data.totalPages > 1 ? (
              <div className="flex items-center justify-between">
                <Button type="button" variant="outline" size="sm" disabled={page === 0} onClick={() => setPage((p) => Math.max(0, p - 1))}>
                  Previous
                </Button>
                <span className="text-xs text-muted-foreground">
                  Page {data.page + 1} of {data.totalPages}
                </span>
                <Button type="button" variant="outline" size="sm" disabled={data.page + 1 >= data.totalPages} onClick={() => setPage((p) => p + 1)}>
                  Next
                </Button>
              </div>
            ) : null}
          </div>
        )}
      </QueryStateBoundary>

      <FinanceDialog
        open={editing !== null}
        onOpenChange={(o) => !o && setEditing(null)}
        title={editing && editing !== "new" ? `Edit ${editing.name}` : "Schedule transaction"}
        testId="schedule-dialog"
      >
        <ScheduleForm schedule={editing && editing !== "new" ? editing : null} onDone={() => setEditing(null)} />
      </FinanceDialog>
    </FinanceHubFrame>
  );
}

function OccurrenceBadge({ status }: { status: OccurrenceStatus }) {
  const cls =
    status === "FAILED"
      ? "border-red-500 text-red-700 dark:text-red-300"
      : status === "DUE"
        ? "border-amber-500 text-amber-800 dark:text-amber-200"
        : "";
  return (
    <Badge variant="outline" className={cls}>
      {OCCURRENCE_LABEL[status]}
    </Badge>
  );
}

function OccurrenceActions({ occurrence, canPost, canSkip, label }: { occurrence: ScheduleOccurrence; canPost: boolean; canSkip: boolean; label: string }) {
  const post = usePostOccurrence();
  const skip = useSkipOccurrence();
  const [error, setError] = useState<string | null>(null);
  if (occurrence.status !== "DUE" && occurrence.status !== "FAILED") return null;
  const busy = post.isPending || skip.isPending;
  return (
    <div className="flex flex-col gap-1">
      <div className="flex flex-wrap gap-2">
        {canPost ? (
          <Button
            type="button"
            size="sm"
            disabled={busy}
            aria-label={`${occurrence.status === "FAILED" ? "Retry" : "Record"} ${label} due ${occurrence.dueDate}`}
            onClick={async () => {
              setError(null);
              try {
                await post.mutateAsync(occurrence.id);
              } catch (e) {
                setError(errorMessage(e, "Could not record it. The reason is shown on the occurrence."));
              }
            }}
          >
            {post.isPending ? "Recording…" : occurrence.status === "FAILED" ? "Retry" : "Record"}
          </Button>
        ) : null}
        {canSkip ? (
          <Button
            type="button"
            size="sm"
            variant="outline"
            disabled={busy}
            aria-label={`Skip ${label} due ${occurrence.dueDate}`}
            onClick={async () => {
              setError(null);
              try {
                await skip.mutateAsync(occurrence.id);
              } catch (e) {
                setError(errorMessage(e));
              }
            }}
          >
            Skip
          </Button>
        ) : null}
      </div>
      {error ? (
        <p role="alert" className="text-xs text-destructive">
          {error}
        </p>
      ) : null}
    </div>
  );
}

function DuePanel({ canPost, canSkip }: { canPost: boolean; canSkip: boolean }) {
  const due = useDueOccurrences();
  const schedules = useFinanceSchedules({ status: "ACTIVE", page: 0, size: 100 });
  const rows = due.data ?? [];
  if (!due.isSuccess || rows.length === 0) return null;
  const byId = new Map((schedules.data?.content ?? []).map((s) => [s.id, s]));
  return (
    <section aria-labelledby="due-heading" className="flex flex-col gap-2 rounded-xl border border-amber-300 bg-amber-50/50 p-4 dark:border-amber-900 dark:bg-amber-950/30" data-testid="due-panel">
      <h3 id="due-heading" className="text-sm font-semibold text-foreground">
        Due now ({rows.length})
      </h3>
      <ul className="flex flex-col divide-y divide-border">
        {rows.map((occurrence) => {
          const schedule = byId.get(occurrence.scheduleId);
          const name = schedule?.name ?? "Scheduled transaction";
          return (
            <li key={occurrence.id} className="flex flex-col gap-2 py-2 sm:flex-row sm:items-center sm:justify-between">
              <div className="flex flex-col gap-0.5 text-sm">
                <div className="flex flex-wrap items-center gap-2">
                  {schedule ? <TransactionTypeBadge type={schedule.type} /> : null}
                  <span className="font-medium">{name}</span>
                  {schedule ? <span className="tabular-nums">{formatMoney(schedule.amount, schedule.currency)}</span> : null}
                  <OccurrenceBadge status={occurrence.status} />
                </div>
                <span className="text-xs text-muted-foreground">Due {formatDate(occurrence.dueDate)}</span>
                {occurrence.lastError ? (
                  <span className="text-xs text-destructive">Last attempt failed: {occurrence.lastError}</span>
                ) : null}
              </div>
              <OccurrenceActions occurrence={occurrence} canPost={canPost} canSkip={canSkip} label={name} />
            </li>
          );
        })}
      </ul>
    </section>
  );
}

function ScheduleCard({ schedule, canManage, onEdit }: { schedule: FinanceSchedule; canManage: boolean; onEdit: () => void }) {
  const cancel = useCancelSchedule();
  const [showHistory, setShowHistory] = useState(false);
  const [confirmCancel, setConfirmCancel] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const active = schedule.status === "ACTIVE";
  const accounts =
    schedule.type === "TRANSFER"
      ? `${schedule.fromAccountName ?? "—"} → ${schedule.toAccountName ?? "—"}`
      : (schedule.type === "INCOME" ? schedule.toAccountName : schedule.fromAccountName) ?? "—";

  return (
    <Card data-testid={`schedule-card-${schedule.id}`} className="h-full">
      <CardContent className="flex flex-col gap-3">
        <div className="flex flex-wrap items-start justify-between gap-2">
          <div className="min-w-0">
            <h3 className="truncate text-base font-semibold text-foreground">{schedule.name}</h3>
            <p className="text-sm text-muted-foreground">{cadence(schedule)}</p>
          </div>
          <div className="flex items-center gap-1">
            <TransactionTypeBadge type={schedule.type} />
            <Badge variant={active ? "secondary" : "outline"}>{STATUS_LABEL[schedule.status]}</Badge>
          </div>
        </div>
        <dl className="grid grid-cols-2 gap-x-4 gap-y-1 text-sm">
          <dt className="text-muted-foreground">Amount</dt>
          <dd className="text-right font-semibold tabular-nums">{formatMoney(schedule.amount, schedule.currency)}</dd>
          <dt className="text-muted-foreground">Account</dt>
          <dd className="truncate text-right">{accounts}</dd>
          {schedule.categoryName ? (
            <>
              <dt className="text-muted-foreground">Category</dt>
              <dd className="truncate text-right">{schedule.categoryName}</dd>
            </>
          ) : null}
          <dt className="text-muted-foreground">Next due</dt>
          <dd className="text-right">{active ? formatDate(schedule.nextDueDate) : "—"}</dd>
          <dt className="text-muted-foreground">Recorded / due / failed</dt>
          <dd className="text-right tabular-nums">
            {schedule.postedOccurrences} / {schedule.openOccurrences} / {schedule.failedOccurrences}
          </dd>
        </dl>
        {schedule.note ? <p className="text-sm text-muted-foreground">{schedule.note}</p> : null}
        {schedule.skippedMonths.length > 0 ? (
          <p className="text-xs text-muted-foreground">
            Skipped (no day {schedule.dayOfMonth}): {schedule.skippedMonths.map(monthLabel).join(", ")}
          </p>
        ) : null}
        {schedule.lastExecution ? (
          <p className="text-xs text-muted-foreground">
            Last: <OccurrenceBadge status={schedule.lastExecution.status} /> due {formatDate(schedule.lastExecution.dueDate)}
            {schedule.lastExecution.lastError ? ` — ${schedule.lastExecution.lastError}` : ""}
          </p>
        ) : null}
        {confirmCancel ? (
          <div role="group" aria-label="Confirm cancellation" className="flex flex-col gap-2 rounded-lg border border-destructive/40 p-3 text-sm">
            <p>Cancel this schedule? Nothing further falls due. Recorded transactions stay as they are.</p>
            <div className="flex gap-2">
              <Button
                type="button"
                size="sm"
                variant="destructive"
                disabled={cancel.isPending}
                onClick={async () => {
                  setError(null);
                  try {
                    await cancel.mutateAsync(schedule.id);
                    setConfirmCancel(false);
                  } catch (e) {
                    setError(errorMessage(e));
                  }
                }}
              >
                {cancel.isPending ? "Cancelling…" : "Cancel schedule"}
              </Button>
              <Button type="button" size="sm" variant="outline" disabled={cancel.isPending} onClick={() => setConfirmCancel(false)}>
                Keep
              </Button>
            </div>
          </div>
        ) : null}
        {error ? (
          <p role="alert" className="text-xs text-destructive">
            {error}
          </p>
        ) : null}
        {showHistory ? <OccurrenceHistory scheduleId={schedule.id} /> : null}
      </CardContent>
      <CardFooter className="flex flex-wrap justify-end gap-2 border-t border-border py-2">
        <Button type="button" variant="ghost" size="sm" aria-expanded={showHistory} onClick={() => setShowHistory((v) => !v)}>
          {showHistory ? <ChevronUp aria-hidden="true" /> : <ChevronDown aria-hidden="true" />}
          History
        </Button>
        {canManage && active ? (
          <>
            <Button type="button" variant="ghost" size="sm" aria-label={`Edit ${schedule.name}`} onClick={onEdit}>
              <Pencil aria-hidden="true" />
              Edit
            </Button>
            <Button type="button" variant="ghost" size="sm" className="text-destructive" aria-label={`Cancel ${schedule.name}`} onClick={() => setConfirmCancel(true)}>
              <XCircle aria-hidden="true" />
              Cancel
            </Button>
          </>
        ) : null}
      </CardFooter>
    </Card>
  );
}

function OccurrenceHistory({ scheduleId }: { scheduleId: string }) {
  const query = useScheduleOccurrences(scheduleId);
  return (
    <QueryStateBoundary
      query={query}
      loadingLabel="Loading history…"
      loginPath="/login"
      isEmpty={(rows) => rows.length === 0}
      emptyState={{ title: "Nothing due yet", description: "Occurrences appear here once their date arrives." }}
    >
      {(rows) => (
        <ul className="flex flex-col divide-y divide-border rounded-lg border border-border text-sm" aria-label="Occurrence history">
          {rows.map((o) => (
            <li key={o.id} className="flex flex-wrap items-center justify-between gap-2 px-3 py-1.5">
              <span>{formatDate(o.dueDate)}</span>
              <span className="flex items-center gap-2">
                {o.lastError ? <span className="text-xs text-destructive">{o.lastError}</span> : null}
                <OccurrenceBadge status={o.status} />
              </span>
            </li>
          ))}
        </ul>
      )}
    </QueryStateBoundary>
  );
}

const SCHEDULE_FIELDS = ["name", "type", "amount", "accountId", "fromAccountId", "toAccountId", "categoryId", "note", "scheduleType", "scheduledDate", "dayOfMonth"] as const;

function ScheduleForm({ schedule, onDone }: { schedule: FinanceSchedule | null; onDone: () => void }) {
  const accounts = useFinanceAccounts(false);
  const categories = useExpenseCategories(false);
  const save = useSaveSchedule();
  const [formError, setFormError] = useState<string | null>(null);
  const singleAccount = schedule ? (schedule.type === "INCOME" ? schedule.toAccountId : schedule.fromAccountId) : null;
  const {
    register,
    handleSubmit,
    control,
    setError,
    formState: { errors },
  } = useForm<ScheduleFormValues>({
    resolver: zodResolver(scheduleSchema),
    defaultValues: {
      name: schedule?.name ?? "",
      type: schedule?.type ?? "EXPENSE",
      amount: schedule ? schedule.amount.toFixed(2) : "",
      accountId: singleAccount ?? "",
      fromAccountId: schedule?.fromAccountId ?? "",
      toAccountId: schedule?.toAccountId ?? "",
      categoryId: schedule?.categoryId ?? "",
      note: schedule?.note ?? "",
      scheduleType: schedule?.scheduleType ?? "ONE_TIME",
      scheduledDate: schedule?.scheduledDate ?? todayIso(),
      dayOfMonth: schedule?.dayOfMonth ? String(schedule.dayOfMonth) : "1",
    },
  });
  const type = useWatch({ control, name: "type" });
  const scheduleType = useWatch({ control, name: "scheduleType" });
  const dayOfMonth = Number(useWatch({ control, name: "dayOfMonth" }));
  const accountList = accounts.data ?? [];

  const onSubmit = handleSubmit(async (values) => {
    setFormError(null);
    const input: ScheduleInput = { ...values, dayOfMonth: Number(values.dayOfMonth) };
    try {
      await save.mutateAsync({ id: schedule?.id, input });
      onDone();
    } catch (e) {
      setFormError(applyServerError(e, SCHEDULE_FIELDS, setError, "Could not save the schedule."));
    }
  });

  const accountOptions = (
    <>
      <option value="">Select…</option>
      {accountList.map((a) => (
        <option key={a.id} value={a.id}>
          {a.name}
        </option>
      ))}
    </>
  );

  return (
    <form className="flex flex-col gap-4" noValidate aria-busy={save.isPending} onSubmit={onSubmit}>
      <LiveRegion message={save.isPending ? "Saving schedule…" : ""} />
      <FormAlert message={formError} />
      <div className="flex flex-col gap-1.5">
        <Label htmlFor="schedule-name">Name</Label>
        <Input id="schedule-name" aria-invalid={!!errors.name} {...register("name")} />
        <FieldError id="schedule-name-error" message={errors.name?.message} />
      </div>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="schedule-type">Type</Label>
          <select id="schedule-type" className={SELECT_CLASS} {...register("type")}>
            <option value="EXPENSE">Expense</option>
            <option value="INCOME">Income</option>
            <option value="TRANSFER">Transfer</option>
          </select>
        </div>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="schedule-amount">Amount</Label>
          <Input
            id="schedule-amount"
            inputMode="decimal"
            aria-invalid={!!errors.amount}
            {...register("amount")}
          />
          <FieldError id="schedule-amount-error" message={errors.amount?.message} />
        </div>
        {type === "TRANSFER" ? (
          <>
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="schedule-from">From account</Label>
              <select id="schedule-from" className={SELECT_CLASS} aria-invalid={!!errors.fromAccountId} {...register("fromAccountId")}>
                {accountOptions}
              </select>
              <FieldError id="schedule-from-error" message={errors.fromAccountId?.message} />
            </div>
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="schedule-to">To account</Label>
              <select
                id="schedule-to"
                className={SELECT_CLASS}
                aria-invalid={!!errors.toAccountId}
                {...register("toAccountId")}
              >
                {accountOptions}
              </select>
              <FieldError id="schedule-to-error" message={errors.toAccountId?.message} />
            </div>
          </>
        ) : (
          <>
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="schedule-account">Account</Label>
              <select id="schedule-account" className={SELECT_CLASS} aria-invalid={!!errors.accountId} {...register("accountId")}>
                {accountOptions}
              </select>
              <FieldError id="schedule-account-error" message={errors.accountId?.message} />
            </div>
            <div className="flex flex-col gap-1.5">
              <Label htmlFor="schedule-category">Category</Label>
              <select id="schedule-category" className={SELECT_CLASS} aria-invalid={!!errors.categoryId} {...register("categoryId")}>
                <option value="">Select…</option>
                {(categories.data ?? []).map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.name}
                  </option>
                ))}
              </select>
              <FieldError id="schedule-category-error" message={errors.categoryId?.message} />
            </div>
          </>
        )}
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="schedule-cadence">Schedule type</Label>
          <select id="schedule-cadence" className={SELECT_CLASS} {...register("scheduleType")}>
            <option value="ONE_TIME">One time</option>
            <option value="MONTHLY">Monthly</option>
          </select>
        </div>
        {scheduleType === "MONTHLY" ? (
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="schedule-day">Day of month</Label>
            <Input
              id="schedule-day"
              type="number"
              min={1}
              max={31}
              aria-invalid={!!errors.dayOfMonth}
              aria-describedby="schedule-day-hint"
              {...register("dayOfMonth")}
            />
            <p id="schedule-day-hint" className="text-xs text-muted-foreground">
              {dayOfMonth >= 29 ? `Months without a day ${dayOfMonth} are skipped, not moved.` : "Falls due on this day each month."}
            </p>
            <FieldError id="schedule-day-error" message={errors.dayOfMonth?.message} />
          </div>
        ) : (
          <div className="flex flex-col gap-1.5">
            <Label htmlFor="schedule-date">Date</Label>
            <Input id="schedule-date" type="date" aria-invalid={!!errors.scheduledDate} {...register("scheduledDate")} />
            <FieldError id="schedule-date-error" message={errors.scheduledDate?.message} />
          </div>
        )}
      </div>
      <div className="flex flex-col gap-1.5">
        <Label htmlFor="schedule-note">Note (optional)</Label>
        <Textarea id="schedule-note" rows={2} {...register("note")} />
        <FieldError id="schedule-note-error" message={errors.note?.message} />
      </div>
      <p className="text-xs text-muted-foreground">Nothing is posted until someone records the occurrence when it falls due.</p>
      <div className="flex justify-end gap-2">
        <Button type="button" variant="outline" onClick={onDone} disabled={save.isPending}>
          Cancel
        </Button>
        <Button type="submit" disabled={save.isPending}>
          {save.isPending ? "Saving…" : schedule ? "Save" : "Schedule"}
        </Button>
      </div>
    </form>
  );
}
