"use client";

import { useState, type ReactNode } from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { AlertCircle, ArrowLeft } from "lucide-react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { DataTable, type DataTableColumn } from "@/components/ui/data-table";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { LiveRegion } from "@/components/ui/live-region";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { SettlementStatusBadge } from "@/components/finance/settlement-status-badge";
import { SignedAmount } from "@/components/finance/signed-amount";
import { useAuth } from "@/lib/auth/auth-context";
import { canManageFinance } from "@/lib/auth/permissions";
import { isApiClientError } from "@/lib/api/error";
import { formatDateTime, formatMoney } from "@/lib/format";
import {
  useAdjustSettlement,
  useFinanceTeachers,
  useMarkSettlementPaid,
  useTeacherSettlement,
  type SettlementCourseBreakdown,
  type TeacherSettlement,
} from "@/lib/api/finance";
import {
  adjustmentSchema,
  markPaidSchema,
  type AdjustmentFormValues,
  type MarkPaidFormValues,
} from "@/lib/validation/finance";

/**
 * Teacher payout statement detail (Wave 7). Every figure shown is the stored
 * server-side snapshot — this page never recomputes. Adjustments are
 * append-only correction rows; "Mark paid" is one-way record-keeping.
 */
export default function TeacherSettlementDetailPage() {
  const params = useParams<{ settlementId: string }>();
  const settlementId = params?.settlementId ?? "";
  const { session } = useAuth();
  const canManage = canManageFinance(session?.role ?? null);
  const query = useTeacherSettlement(settlementId);
  const teachersQuery = useFinanceTeachers();

  const courseColumns: DataTableColumn<SettlementCourseBreakdown>[] = [
    { key: "course", header: "Course", cell: (row) => row.courseTitle ?? `Course #${row.courseId.slice(0, 8)}` },
    { key: "entries", header: "Ledger entries", cell: (row) => row.entryCount },
    { key: "gross", header: "Gross", cell: (row) => formatMoney(row.gross) },
    { key: "refunds", header: "Refunds", cell: (row) => formatMoney(row.refunds) },
    { key: "net", header: "Net", cell: (row) => <SignedAmount value={row.net} /> },
  ];
  const adjustmentColumns: DataTableColumn<TeacherSettlement>[] = [
    { key: "date", header: "Recorded", cell: (row) => formatDateTime(row.calculatedAt) },
    {
      key: "amount",
      header: "Amount",
      cell: (row) => <SignedAmount value={row.shareAmount} currency={row.currency} />,
    },
    { key: "reason", header: "Reason", cell: (row) => row.reason ?? "—" },
    { key: "by", header: "By", cell: (row) => row.calculatedByEmail ?? "—" },
  ];

  return (
    <div className="flex flex-col gap-6">
      <Link
        href="/tenant-admin/finance/teacher-payouts"
        className="flex items-center gap-1 text-sm underline underline-offset-4"
      >
        <ArrowLeft aria-hidden="true" className="size-4" />
        Back to teacher payouts
      </Link>

      <QueryStateBoundary
        query={query}
        loadingLabel="Loading statement…"
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
      >
        {(detail) => {
          const s = detail.settlement;
          const teacherName =
            teachersQuery.data?.find((t) => t.userId === s.teacherId)?.name ?? s.teacherEmail ?? "Teacher";
          return (
            <div className="flex flex-col gap-6">
              <div className="flex flex-col gap-2 sm:flex-row sm:items-start sm:justify-between">
                <div>
                  <h1 className="text-xl font-semibold text-foreground">
                    {teacherName} — {s.periodStart} to {s.periodEnd}
                  </h1>
                  <p className="text-sm text-muted-foreground">
                    Calculated {formatDateTime(s.calculatedAt)} by {s.calculatedByEmail ?? "—"}
                  </p>
                </div>
                <SettlementStatusBadge status={s.status} />
              </div>

              <dl className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
                <Figure label="Gross revenue" value={formatMoney(s.grossAmount ?? 0)} />
                <Figure label="Refunds" value={formatMoney(s.refundAmount ?? 0)} />
                <Figure label="Net revenue" value={<SignedAmount value={s.netAmount ?? 0} />} />
                <Figure label="Revenue share" value={`${s.sharePercent}% (rate at calculation time)`} />
                <Figure
                  label="Calculated share"
                  value={<SignedAmount value={s.shareAmount} currency={s.currency} />}
                />
                <Figure
                  label="Payable incl. adjustments"
                  value={<SignedAmount value={s.effectiveShareAmount} currency={s.currency} />}
                />
              </dl>

              {s.status === "PAID" ? (
                <p className="text-sm text-muted-foreground">
                  Marked paid {s.paidAt ? formatDateTime(s.paidAt) : ""} by {s.paidByEmail ?? "—"}
                  {s.payoutReference ? ` — reference ${s.payoutReference}` : ""}.
                </p>
              ) : null}

              <section aria-labelledby="breakdown-heading" className="flex flex-col gap-3">
                <h2 id="breakdown-heading" className="text-base font-semibold text-foreground">
                  By course
                </h2>
                <DataTable
                  columns={courseColumns}
                  rows={detail.courses}
                  rowKey={(row) => row.courseId}
                  caption="Statement breakdown by course"
                  cardHeading={(row) => row.courseTitle ?? `Course #${row.courseId.slice(0, 8)}`}
                />
              </section>

              <section aria-labelledby="adjustments-heading" className="flex flex-col gap-3">
                <h2 id="adjustments-heading" className="text-base font-semibold text-foreground">
                  Adjustments
                </h2>
                {detail.adjustments.length === 0 ? (
                  <p className="text-sm text-muted-foreground">No adjustments have been recorded.</p>
                ) : (
                  <DataTable
                    columns={adjustmentColumns}
                    rows={detail.adjustments}
                    rowKey={(row) => row.id}
                    caption="Statement adjustments"
                    cardHeading={(row) => formatMoney(row.shareAmount, row.currency)}
                  />
                )}
              </section>

              {canManage ? (
                <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
                  <AdjustmentForm settlementId={s.id} />
                  {s.status === "CALCULATED" ? <MarkPaidForm settlementId={s.id} /> : null}
                </div>
              ) : null}
            </div>
          );
        }}
      </QueryStateBoundary>
    </div>
  );
}

function Figure({ label, value }: { label: string; value: ReactNode }) {
  return (
    <div className="flex flex-col gap-1 rounded-lg border border-border p-3">
      <dt className="text-sm text-muted-foreground">{label}</dt>
      <dd className="text-base font-semibold text-foreground">{value}</dd>
    </div>
  );
}

function ErrorAlert({ message }: { message: string | null }) {
  return message ? (
    <Alert variant="destructive">
      <AlertCircle aria-hidden="true" />
      <AlertDescription>{message}</AlertDescription>
    </Alert>
  ) : null;
}

function AdjustmentForm({ settlementId }: { settlementId: string }) {
  const mutation = useAdjustSettlement(settlementId);
  const [pageError, setPageError] = useState<string | null>(null);
  const {
    register,
    handleSubmit,
    setError,
    reset,
    formState: { errors },
  } = useForm<AdjustmentFormValues>({
    resolver: zodResolver(adjustmentSchema),
    defaultValues: { amount: "", reason: "" },
  });
  const onSubmit = handleSubmit(async (values) => {
    setPageError(null);
    try {
      await mutation.mutateAsync({ amount: values.amount, reason: values.reason.trim() });
      reset();
    } catch (error) {
      if (!isApiClientError(error)) {
        setPageError("Something went wrong. Please try again.");
        return;
      }
      const mapped = error.fieldErrors.filter((f) => f.field === "amount" || f.field === "reason");
      mapped.forEach((f) =>
        setError(f.field as keyof AdjustmentFormValues, { type: "server", message: f.message })
      );
      if (mapped.length === 0) setPageError(error.message);
    }
  });
  return (
    <form
      className="flex flex-col gap-3 rounded-lg border border-border p-4"
      noValidate
      aria-busy={mutation.isPending}
      aria-labelledby="adjust-heading"
      onSubmit={onSubmit}
    >
      <h2 id="adjust-heading" className="text-base font-semibold text-foreground">
        Add an adjustment
      </h2>
      <p className="text-sm text-muted-foreground">
        Use a negative amount to reduce what is owed. The original statement is not changed.
      </p>
      <LiveRegion message={mutation.isPending ? "Saving adjustment…" : ""} />
      <ErrorAlert message={pageError} />
      <div className="flex flex-col gap-1.5">
        <Label htmlFor="adjust-amount">Amount</Label>
        <Input id="adjust-amount" inputMode="decimal" aria-invalid={!!errors.amount} {...register("amount")} />
        {errors.amount ? (
          <p role="alert" className="text-xs text-destructive">
            {errors.amount.message}
          </p>
        ) : null}
      </div>
      <div className="flex flex-col gap-1.5">
        <Label htmlFor="adjust-reason">Reason</Label>
        <Input id="adjust-reason" aria-invalid={!!errors.reason} {...register("reason")} />
        {errors.reason ? (
          <p role="alert" className="text-xs text-destructive">
            {errors.reason.message}
          </p>
        ) : null}
      </div>
      <Button type="submit" disabled={mutation.isPending} className="self-start">
        {mutation.isPending ? "Saving…" : "Add adjustment"}
      </Button>
    </form>
  );
}

function MarkPaidForm({ settlementId }: { settlementId: string }) {
  const mutation = useMarkSettlementPaid(settlementId);
  const [pageError, setPageError] = useState<string | null>(null);
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<MarkPaidFormValues>({
    resolver: zodResolver(markPaidSchema),
    defaultValues: { payoutReference: "" },
  });
  const onSubmit = handleSubmit(async (values) => {
    setPageError(null);
    try {
      await mutation.mutateAsync(values.payoutReference?.trim() ?? "");
    } catch (error) {
      setPageError(isApiClientError(error) ? error.message : "Something went wrong. Please try again.");
    }
  });
  return (
    <form
      className="flex flex-col gap-3 rounded-lg border border-border p-4"
      noValidate
      aria-busy={mutation.isPending}
      aria-labelledby="paid-heading"
      onSubmit={onSubmit}
    >
      <h2 id="paid-heading" className="text-base font-semibold text-foreground">
        Mark as paid
      </h2>
      <p className="text-sm text-muted-foreground">
        Record that the institute has paid this teacher. This cannot be undone and does not move any
        money.
      </p>
      <LiveRegion message={mutation.isPending ? "Marking as paid…" : ""} />
      <ErrorAlert message={pageError} />
      <div className="flex flex-col gap-1.5">
        <Label htmlFor="payout-reference">Payout reference (optional)</Label>
        <Input id="payout-reference" autoComplete="off" {...register("payoutReference")} />
        {errors.payoutReference ? (
          <p role="alert" className="text-xs text-destructive">
            {errors.payoutReference.message}
          </p>
        ) : null}
      </div>
      <Button type="submit" disabled={mutation.isPending} className="self-start">
        {mutation.isPending ? "Saving…" : "Mark as paid"}
      </Button>
    </form>
  );
}
