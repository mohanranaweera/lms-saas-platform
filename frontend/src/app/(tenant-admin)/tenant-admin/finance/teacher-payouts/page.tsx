"use client";

import { useState } from "react";
import Link from "next/link";
import { Controller, useForm, type Control, type FieldValues, type Path } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { AlertCircle, Calculator, Percent } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { DataTable, type DataTableColumn } from "@/components/ui/data-table";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { LiveRegion } from "@/components/ui/live-region";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { SignedAmount } from "@/components/finance/signed-amount";
import { SettlementStatusBadge } from "@/components/finance/settlement-status-badge";
import { useAuth } from "@/lib/auth/auth-context";
import { canManageFinance } from "@/lib/auth/permissions";
import { isApiClientError } from "@/lib/api/error";
import {
  useAddTeacherShareRate,
  useCalculateSettlement,
  useFinanceTeachers,
  useTeacherSettlements,
  useTeacherShareRates,
  type FinanceTeacher,
  type TeacherSettlement,
  type TeacherSettlementStatus,
  type TeacherShareRate,
} from "@/lib/api/finance";
import {
  calculateSettlementSchema,
  shareRateSchema,
  type CalculateSettlementFormValues,
  type ShareRateFormValues,
} from "@/lib/validation/finance";

const ALL = "__all__";

/**
 * Tenant Admin Teacher Payouts (Wave 7 settlement foundation, PAR-23-03,
 * PAR-24-02/04). Consumes settlement records — this screen never computes a
 * payout figure itself. A statement is calculated server-side from the
 * payment ledger for a CLOSED period at the teacher's rate in effect, and its
 * stored figures never change afterwards; corrections are adjustments.
 * "Mark paid" records that the institute paid the teacher outside the
 * platform — no money moves here.
 */
export default function TeacherPayoutsPage() {
  const { session } = useAuth();
  const canManage = canManageFinance(session?.role ?? null);
  const [status, setStatus] = useState<string>(ALL);
  const [teacherFilter, setTeacherFilter] = useState<string>(ALL);
  const [page, setPage] = useState(0);
  const [panel, setPanel] = useState<"rate" | "calculate" | null>(null);

  const teachersQuery = useFinanceTeachers();
  const teachers = teachersQuery.data ?? [];
  const nameOf = (id: string, email?: string | null) =>
    teachers.find((t) => t.userId === id)?.name ?? email ?? `Teacher #${id.slice(0, 8)}`;
  const ratesQuery = useTeacherShareRates();
  const settlementsQuery = useTeacherSettlements({
    status: status === ALL ? undefined : (status as TeacherSettlementStatus),
    teacherId: teacherFilter === ALL ? undefined : teacherFilter,
    kind: "REGULAR",
    page,
  });

  const settlementColumns: DataTableColumn<TeacherSettlement>[] = [
    { key: "teacher", header: "Teacher", cell: (row) => nameOf(row.teacherId, row.teacherEmail) },
    { key: "period", header: "Period", cell: (row) => `${row.periodStart} to ${row.periodEnd}` },
    { key: "net", header: "Net revenue", cell: (row) => <SignedAmount value={row.netAmount ?? 0} /> },
    { key: "rate", header: "Share", cell: (row) => `${row.sharePercent}%` },
    {
      key: "amount",
      header: "Payable",
      cell: (row) => <SignedAmount value={row.effectiveShareAmount} currency={row.currency} />,
    },
    {
      key: "status",
      header: "Status",
      cell: (row) => <SettlementStatusBadge status={row.status} />,
      hideOnCard: true,
    },
    {
      key: "view",
      header: "Details",
      hideOnCard: true,
      cell: (row) => <DetailLink row={row} label={nameOf(row.teacherId, row.teacherEmail)} />,
    },
  ];
  const rateColumns: DataTableColumn<TeacherShareRate>[] = [
    { key: "teacher", header: "Teacher", cell: (row) => nameOf(row.teacherId, row.teacherEmail) },
    { key: "percent", header: "Share", cell: (row) => `${row.sharePercent}%` },
    { key: "from", header: "Effective from", cell: (row) => row.effectiveFrom },
  ];

  const filtered = status !== ALL || teacherFilter !== ALL;

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
        <div>
          <h1 className="text-xl font-semibold text-foreground">Teacher payouts</h1>
          <p className="text-sm text-muted-foreground">
            Statements are calculated from confirmed payments and refunds for a closed period, at the
            teacher&apos;s revenue share in effect. Stored statements never change — use an
            adjustment to correct one. Marking a statement paid only records the payout.
          </p>
        </div>
        {canManage ? (
          <div className="flex flex-wrap gap-2">
            <Button type="button" variant="outline" onClick={() => setPanel(panel === "rate" ? null : "rate")}>
              <Percent aria-hidden="true" />
              Set share rate
            </Button>
            <Button type="button" onClick={() => setPanel(panel === "calculate" ? null : "calculate")}>
              <Calculator aria-hidden="true" />
              Calculate statement
            </Button>
          </div>
        ) : null}
      </div>

      {canManage && panel === "rate" ? <RateForm teachers={teachers} onDone={() => setPanel(null)} /> : null}
      {canManage && panel === "calculate" ? (
        <CalculateForm teachers={teachers} onDone={() => setPanel(null)} />
      ) : null}

      <section aria-labelledby="settlements-heading" className="flex flex-col gap-3">
        <h2 id="settlements-heading" className="text-base font-semibold text-foreground">
          Statements
        </h2>
        <div className="flex flex-col gap-3 sm:flex-row sm:items-end">
          <FilterSelect
            id="payout-filter-teacher"
            label="Teacher"
            value={teacherFilter}
            onChange={(v) => {
              setTeacherFilter(v);
              setPage(0);
            }}
            options={[
              { value: ALL, label: "All teachers" },
              ...teachers.map((t) => ({ value: t.userId, label: t.name })),
            ]}
          />
          <FilterSelect
            id="payout-filter-status"
            label="Status"
            value={status}
            onChange={(v) => {
              setStatus(v);
              setPage(0);
            }}
            options={[
              { value: ALL, label: "All statuses" },
              { value: "CALCULATED", label: "Calculated (unpaid)" },
              { value: "PAID", label: "Paid" },
            ]}
          />
        </div>
        <QueryStateBoundary
          query={settlementsQuery}
          loadingLabel="Loading statements…"
          loginPath="/login"
          permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
          isEmpty={(data) => data.content.length === 0}
          emptyState={
            filtered
              ? {
                  title: "No statements match your filters",
                  description: "Try a different teacher or status.",
                  action: {
                    label: "Clear filters",
                    onClick: () => {
                      setStatus(ALL);
                      setTeacherFilter(ALL);
                    },
                  },
                }
              : {
                  title: "No statements yet",
                  description: canManage
                    ? "Set a teacher's share rate, then calculate a statement for a closed period."
                    : "Teacher payout statements calculated by your finance team will appear here.",
                }
          }
        >
          {(data) => (
            <div className="flex flex-col gap-3">
              <DataTable
                columns={settlementColumns}
                rows={data.content}
                rowKey={(row) => row.id}
                caption="Teacher payout statements"
                cardHeading={(row) => nameOf(row.teacherId, row.teacherEmail)}
                cardHeadingAdornment={(row) => <SettlementStatusBadge status={row.status} />}
                cardFooter={(row) => <DetailLink row={row} label={nameOf(row.teacherId, row.teacherEmail)} />}
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
      </section>

      <section aria-labelledby="rates-heading" className="flex flex-col gap-3">
        <h2 id="rates-heading" className="text-base font-semibold text-foreground">
          Revenue share rates
        </h2>
        <QueryStateBoundary
          query={ratesQuery}
          loadingLabel="Loading share rates…"
          loginPath="/login"
          permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
          isEmpty={(data) => data.length === 0}
          emptyState={{
            title: "No share rates set",
            description: "A teacher needs a revenue share rate before a statement can be calculated.",
          }}
        >
          {(data) => (
            <DataTable
              columns={rateColumns}
              rows={data}
              rowKey={(row) => row.id}
              caption="Revenue share rate history"
              cardHeading={(row) => nameOf(row.teacherId, row.teacherEmail)}
            />
          )}
        </QueryStateBoundary>
      </section>
    </div>
  );
}

function DetailLink({ row, label }: { row: TeacherSettlement; label: string }) {
  return (
    <Link
      href={`/tenant-admin/finance/teacher-payouts/${row.id}`}
      className="text-sm underline underline-offset-4"
      aria-label={`View statement for ${label}, ${row.periodStart} to ${row.periodEnd}`}
    >
      View
    </Link>
  );
}

function FilterSelect({
  id,
  label,
  value,
  onChange,
  options,
}: {
  id: string;
  label: string;
  value: string;
  onChange: (value: string) => void;
  options: { value: string; label: string }[];
}) {
  return (
    <div className="flex flex-col gap-1.5 sm:w-56">
      <Label htmlFor={id}>{label}</Label>
      <Select value={value} onValueChange={(v) => onChange(v ?? ALL)}>
        <SelectTrigger id={id} className="w-full">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          {options.map((option) => (
            <SelectItem key={option.value} value={option.value}>
              {option.label}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
    </div>
  );
}

function TeacherPicker<T extends FieldValues>({
  control,
  name,
  id,
  teachers,
  error,
}: {
  control: Control<T>;
  name: Path<T>;
  id: string;
  teachers: FinanceTeacher[];
  error?: string;
}) {
  return (
    <div className="flex flex-col gap-1.5">
      <Label htmlFor={id}>Teacher</Label>
      <Controller
        control={control}
        name={name}
        render={({ field }) => (
          <Select value={(field.value as string) || null} onValueChange={(v) => field.onChange(v ?? "")}>
            <SelectTrigger id={id} className="w-full" aria-invalid={!!error}>
              <SelectValue placeholder="Select a teacher…" />
            </SelectTrigger>
            <SelectContent>
              {teachers.map((teacher) => (
                <SelectItem key={teacher.userId} value={teacher.userId}>
                  {teacher.name} ({teacher.email})
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        )}
      />
      <FieldError message={error} />
    </div>
  );
}

function FieldError({ message }: { message?: string }) {
  return message ? (
    <p role="alert" className="text-xs text-destructive">
      {message}
    </p>
  ) : null;
}

function PageError({ message }: { message: string | null }) {
  return message ? (
    <Alert variant="destructive">
      <AlertCircle aria-hidden="true" />
      <AlertDescription>{message}</AlertDescription>
    </Alert>
  ) : null;
}

function RateForm({ teachers, onDone }: { teachers: FinanceTeacher[]; onDone: () => void }) {
  const mutation = useAddTeacherShareRate();
  const [pageError, setPageError] = useState<string | null>(null);
  const {
    register,
    control,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<ShareRateFormValues>({
    resolver: zodResolver(shareRateSchema),
    defaultValues: { teacherId: "", sharePercent: "", effectiveFrom: "" },
  });
  const onSubmit = handleSubmit(async (values) => {
    setPageError(null);
    try {
      await mutation.mutateAsync(values);
      onDone();
    } catch (error) {
      if (!isApiClientError(error)) {
        setPageError("Something went wrong. Please try again.");
        return;
      }
      if (error.status === 409) {
        setError("effectiveFrom", {
          type: "server",
          message: "This teacher already has a rate starting on this date.",
        });
        return;
      }
      const mapped = error.fieldErrors.filter((f) =>
        ["teacherId", "sharePercent", "effectiveFrom"].includes(f.field)
      );
      mapped.forEach((f) =>
        setError(f.field as keyof ShareRateFormValues, { type: "server", message: f.message })
      );
      if (mapped.length === 0) setPageError(error.message);
    }
  });
  return (
    <form
      className="flex flex-col gap-3 rounded-lg border border-border p-4"
      noValidate
      aria-busy={mutation.isPending}
      aria-labelledby="rate-form-heading"
      onSubmit={onSubmit}
    >
      <h2 id="rate-form-heading" className="text-base font-semibold text-foreground">
        Set a revenue share rate
      </h2>
      <p className="text-sm text-muted-foreground">
        Adds a new rate from the chosen date. Earlier rates and already-calculated statements are not
        changed.
      </p>
      <LiveRegion message={mutation.isPending ? "Saving rate…" : ""} />
      <PageError message={pageError} />
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
        <TeacherPicker
          control={control}
          name="teacherId"
          id="rate-teacher"
          teachers={teachers}
          error={errors.teacherId?.message}
        />
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="rate-percent">Share (%)</Label>
          <Input
            id="rate-percent"
            inputMode="decimal"
            aria-invalid={!!errors.sharePercent}
            {...register("sharePercent")}
          />
          <FieldError message={errors.sharePercent?.message} />
        </div>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="rate-from">Effective from</Label>
          <Input id="rate-from" type="date" aria-invalid={!!errors.effectiveFrom} {...register("effectiveFrom")} />
          <FieldError message={errors.effectiveFrom?.message} />
        </div>
      </div>
      <div className="flex gap-2">
        <Button type="submit" disabled={mutation.isPending}>
          {mutation.isPending ? "Saving…" : "Save rate"}
        </Button>
        <Button type="button" variant="outline" onClick={onDone} disabled={mutation.isPending}>
          Cancel
        </Button>
      </div>
    </form>
  );
}

function CalculateForm({ teachers, onDone }: { teachers: FinanceTeacher[]; onDone: () => void }) {
  const mutation = useCalculateSettlement();
  const [pageError, setPageError] = useState<string | null>(null);
  const {
    register,
    control,
    handleSubmit,
    setError,
    formState: { errors },
  } = useForm<CalculateSettlementFormValues>({
    resolver: zodResolver(calculateSettlementSchema),
    defaultValues: { teacherId: "", periodStart: "", periodEnd: "" },
  });
  const onSubmit = handleSubmit(async (values) => {
    setPageError(null);
    try {
      await mutation.mutateAsync(values);
      onDone();
    } catch (error) {
      if (!isApiClientError(error)) {
        setPageError("Something went wrong. Please try again.");
        return;
      }
      const mapped = error.fieldErrors.filter((f) =>
        ["teacherId", "periodStart", "periodEnd"].includes(f.field)
      );
      mapped.forEach((f) =>
        setError(f.field as keyof CalculateSettlementFormValues, { type: "server", message: f.message })
      );
      if (mapped.length === 0) setPageError(error.message);
    }
  });
  return (
    <form
      className="flex flex-col gap-3 rounded-lg border border-border p-4"
      noValidate
      aria-busy={mutation.isPending}
      aria-labelledby="calc-form-heading"
      onSubmit={onSubmit}
    >
      <h2 id="calc-form-heading" className="text-base font-semibold text-foreground">
        Calculate a statement
      </h2>
      <p className="text-sm text-muted-foreground">
        The period must have ended. Each payment or refund is included in at most one statement.
      </p>
      <LiveRegion message={mutation.isPending ? "Calculating statement…" : ""} />
      <PageError message={pageError} />
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
        <TeacherPicker
          control={control}
          name="teacherId"
          id="calc-teacher"
          teachers={teachers}
          error={errors.teacherId?.message}
        />
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="calc-start">Period start</Label>
          <Input id="calc-start" type="date" aria-invalid={!!errors.periodStart} {...register("periodStart")} />
          <FieldError message={errors.periodStart?.message} />
        </div>
        <div className="flex flex-col gap-1.5">
          <Label htmlFor="calc-end">Period end</Label>
          <Input id="calc-end" type="date" aria-invalid={!!errors.periodEnd} {...register("periodEnd")} />
          <FieldError message={errors.periodEnd?.message} />
        </div>
      </div>
      <div className="flex gap-2">
        <Button type="submit" disabled={mutation.isPending}>
          {mutation.isPending ? "Calculating…" : "Calculate"}
        </Button>
        <Button type="button" variant="outline" onClick={onDone} disabled={mutation.isPending}>
          Cancel
        </Button>
      </div>
    </form>
  );
}
