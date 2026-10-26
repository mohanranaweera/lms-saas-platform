"use client";

import { forwardRef } from "react";
import { Download } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Switch } from "@/components/ui/switch";
import { ExpiredNotice, EXPORT_DISABLED_REASON_ID, StaleNotice } from "@/components/reports/report-notices";
import { InfoHint } from "@/components/reports/info-hint";
import { formatReportGenerated } from "@/lib/api/reports";
import {
  studentPaymentDisplayMethodLabel,
  studentPaymentMethodFilterLabel,
  type StudentPaymentReconciliation,
  type StudentPaymentReport,
  type StudentPaymentRowsPage,
  type StudentPaymentSection,
} from "@/lib/api/student-payment-report";
import { cn } from "@/lib/utils";
import { formatCodeAmount, isZeroAmount } from "./format";
import { StudentPaymentTable } from "./student-payment-table";

/**
 * Renders a server-computed RPT-02 run: header + applied filters, per-currency
 * summary (server figures only - nothing is summed here, currencies are never
 * combined), grouping switches, Export PDF and the paged detail table.
 */

export interface StudentPaymentResultsProps {
  report: StudentPaymentReport;
  stale: boolean;
  expired: boolean;
  downloading: boolean;
  downloadError: string | null;
  byStudent: boolean;
  byCourse: boolean;
  onGroupingChange: (byStudent: boolean, byCourse: boolean) => void;
  rows: StudentPaymentRowsPage | null;
  rowsLoading: boolean;
  rowsError: unknown;
  onRetryRows: () => void;
  onPageChange: (page: number) => void;
  onExport: () => void;
  onRunAgain: () => void;
}

export const StudentPaymentResults = forwardRef<HTMLHeadingElement, StudentPaymentResultsProps>(
  function StudentPaymentResults(
    {
      report,
      stale,
      expired,
      downloading,
      downloadError,
      byStudent,
      byCourse,
      onGroupingChange,
      rows,
      rowsLoading,
      rowsError,
      onRetryRows,
      onPageChange,
      onExport,
      onRunAgain,
    },
    headingRef
  ) {
    const exportDisabled = stale || expired || downloading;
    const hasReason = stale || expired;
    const { filters } = report;
    const currencies = report.sections.map((section) => section.currency);

    return (
      <div className="flex flex-col gap-6" data-testid="student-payment-results">
        <div className="flex flex-col gap-3">
          <div className="flex flex-col gap-1">
            <h2 ref={headingRef} tabIndex={-1} className="text-base font-semibold outline-none" data-testid="result-heading">
              Payment history report — {filters.periodLabel}
            </h2>
            <p className="text-sm text-muted-foreground">{report.institute.name}</p>
          </div>
          <ul className="flex flex-wrap gap-1.5 text-xs" aria-label="Applied filters" data-testid="applied-filters">
            {filters.courses.length === 0 ? (
              <FilterChip>Course: All courses</FilterChip>
            ) : (
              filters.courses.map((course) => <FilterChip key={course.id}>Course: {course.name}</FilterChip>)
            )}
            <FilterChip>Payment method: {studentPaymentMethodFilterLabel(filters.paymentMethod)}</FilterChip>
            <FilterChip>{filters.periodMode === "MONTHS" ? "Billing months" : "Received dates"}: {filters.periodLabel}</FilterChip>
          </ul>
          <p className="text-xs text-muted-foreground" data-testid="generated-at">
            Generated {formatReportGenerated(report.generatedAt, report.timezone)} ({report.timezone})
          </p>
        </div>

        {stale ? <StaleNotice /> : expired ? <ExpiredNotice onRunAgain={onRunAgain} /> : null}

        {downloadError && !expired ? (
          <p role="alert" className="rounded-md border border-destructive/30 bg-destructive/5 px-3 py-2 text-sm text-destructive">
            {downloadError}
          </p>
        ) : null}

        {report.sections.length > 1 ? (
          <p className="text-sm text-muted-foreground" data-testid="multi-currency-note">
            Amounts in different currencies are never combined.
          </p>
        ) : null}

        {report.sections.map((section) => (
          <CurrencySummary
            key={section.currency}
            section={section}
            reconciliation={report.reconciliation.byCurrency.find((rec) => rec.currency === section.currency)}
          />
        ))}

        <div className="flex flex-col gap-3 rounded-lg border border-border p-3 sm:flex-row sm:items-center sm:justify-between">
          <fieldset className="m-0 flex flex-wrap items-center gap-x-6 gap-y-2 border-0 p-0">
            <legend className="sr-only">Grouping</legend>
            <div className="flex items-center gap-2">
              <Switch
                id="group-by-student"
                checked={byStudent}
                onCheckedChange={(checked) => onGroupingChange(checked, byCourse)}
              />
              <Label htmlFor="group-by-student">Group by student</Label>
            </div>
            <div className="flex items-center gap-2">
              <Switch
                id="group-by-course"
                checked={byCourse}
                onCheckedChange={(checked) => onGroupingChange(byStudent, checked)}
              />
              <Label htmlFor="group-by-course">Group by course</Label>
            </div>
          </fieldset>
          <Button
            type="button"
            onClick={onExport}
            disabled={exportDisabled}
            aria-describedby={hasReason ? EXPORT_DISABLED_REASON_ID : undefined}
          >
            <Download aria-hidden="true" />
            {downloading ? "Preparing PDF…" : "Export PDF"}
          </Button>
        </div>

        {!expired ? (
          <StudentPaymentTable
            rowsPage={rows}
            currencies={currencies}
            timezone={report.timezone}
            loading={rowsLoading}
            error={rowsError}
            onRetry={onRetryRows}
            onPageChange={onPageChange}
          />
        ) : null}

        <Reconciliation byCurrency={report.reconciliation.byCurrency} notes={report.reconciliation.notes} />
      </div>
    );
  }
);

function FilterChip({ children }: { children: React.ReactNode }) {
  return <li className="rounded-full bg-muted px-2.5 py-1 text-foreground">{children}</li>;
}

function CurrencySummary({
  section,
  reconciliation,
}: {
  section: StudentPaymentSection;
  reconciliation?: StudentPaymentReconciliation;
}) {
  const { currency, summary } = section;
  const money = (amount: string) => formatCodeAmount(amount, currency);
  const headingId = `summary-${currency}`;
  const students = summary.studentsPaid;

  return (
    <section aria-labelledby={headingId} className="flex flex-col gap-4" data-testid={`summary-${currency}`}>
      <h3 id={headingId} className="text-sm font-semibold">
        Summary ({currency})
      </h3>
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
        <Kpi label="Total amount" value={money(summary.coursePaymentTotal)} accent="border-l-blue-500" />
        <Kpi label="Total students" value={String(students)} accent="border-l-emerald-500" />
        <Kpi label="Total payments" value={String(summary.receiptCount)} accent="border-l-violet-500" />
      </div>
      <div className="flex flex-col gap-1.5 rounded-lg border border-border p-3 text-sm">
        <p data-testid={`summary-headline-${currency}`}>
          Filtered results show {students} {students === 1 ? "student" : "students"} paid, totaling{" "}
          <strong className="tabular-nums">{money(summary.coursePaymentTotal)}</strong>
        </p>
        <ul className="flex flex-col gap-1" aria-label={`Totals by payment method (${currency})`}>
          {summary.byMethod.map((entry) => (
            <li key={`${entry.method}-${entry.manual}`} className="flex flex-wrap justify-between gap-x-4" data-testid="method-total">
              <span className="text-muted-foreground">
                Total {studentPaymentDisplayMethodLabel(entry.method, entry.manual)} payments ({entry.receiptCount}{" "}
                {entry.receiptCount === 1 ? "payment" : "payments"})
              </span>
              <span className="font-medium tabular-nums">{money(entry.total)}</span>
            </li>
          ))}
        </ul>
        <SummaryLine label="Service fees collected on top of these amounts" value={money(summary.serviceFeeTotal)} testId={`service-fees-${currency}`} />
        {!isZeroAmount(summary.postageTotal) ? (
          <SummaryLine label="Postage collected on top of these amounts" value={money(summary.postageTotal)} testId={`postage-${currency}`} />
        ) : null}
        {summary.refundCount > 0 || (reconciliation && !isZeroAmount(reconciliation.refunds)) ? (
          <SummaryLine
            label={`Refunds already deducted (${summary.refundCount} ${summary.refundCount === 1 ? "refund" : "refunds"})`}
            value={reconciliation ? money(reconciliation.refunds) : "—"}
            testId={`refunds-${currency}`}
          />
        ) : null}
        {reconciliation && reconciliation.staffAdjustments.count > 0 ? (
          <SummaryLine
            label={`Staff adjustments on manual receipts (${reconciliation.staffAdjustments.count})`}
            value={money(reconciliation.staffAdjustments.amount)}
            hint="Amounts staff waived when recording a manual receipt: expected minus received. They were not received, so they are not in the totals."
            testId={`staff-adjustments-${currency}`}
          />
        ) : null}
        <div className="mt-1 flex flex-wrap justify-between gap-x-4 border-t border-border pt-2 font-semibold" data-testid={`received-total-${currency}`}>
          <span className="flex items-center gap-1">
            Received (net) total
            <InfoHint label="Received (net) total">
              Course payments plus service fees and postage, after refunds.
            </InfoHint>
          </span>
          <span className="tabular-nums">{money(summary.receivedTotal)}</span>
        </div>
      </div>
    </section>
  );
}

function SummaryLine({ label, value, hint, testId }: { label: string; value: string; hint?: string; testId?: string }) {
  return (
    <div className="flex flex-wrap justify-between gap-x-4" data-testid={testId}>
      <span className="flex items-center gap-1 text-muted-foreground">
        {label}
        {hint ? <InfoHint label={label}>{hint}</InfoHint> : null}
      </span>
      <span className="font-medium tabular-nums">{value}</span>
    </div>
  );
}

function Kpi({ label, value, accent }: { label: string; value: string; accent: string }) {
  return (
    <div className={cn("rounded-lg border border-border border-l-4 bg-card p-3", accent)} data-testid="kpi">
      <p className="text-xs font-medium tracking-wide text-muted-foreground uppercase">{label}</p>
      <p className="mt-1 text-lg font-semibold tabular-nums">{value}</p>
    </div>
  );
}

function Reconciliation({ byCurrency, notes }: { byCurrency: StudentPaymentReconciliation[]; notes: string[] }) {
  if (byCurrency.length === 0 && notes.length === 0) return null;
  return (
    <section aria-labelledby="reconciliation-heading" className="flex flex-col gap-3 rounded-lg border border-border p-3">
      <h3 id="reconciliation-heading" className="text-sm font-semibold">
        Reconciliation notes
      </h3>
      {byCurrency.map((rec) => {
        const money = (amount: string) => formatCodeAmount(amount, rec.currency);
        return (
          <dl
            key={rec.currency}
            className="grid grid-cols-1 gap-x-6 gap-y-1 text-sm sm:grid-cols-[auto_1fr]"
            aria-label={`Reconciliation (${rec.currency})`}
          >
            <dt className="text-muted-foreground">Gross course payments ({rec.currency})</dt>
            <dd>{money(rec.grossCoursePayments)}</dd>
            <dt className="text-muted-foreground">Refunds</dt>
            <dd>{money(rec.refunds)}</dd>
            {rec.staffAdjustments.count > 0 ? (
              <>
                <dt className="text-muted-foreground">Staff adjustments (not received)</dt>
                <dd>
                  {rec.staffAdjustments.count} · {money(rec.staffAdjustments.amount)}
                </dd>
              </>
            ) : null}
            {rec.excludedNonCash.count > 0 ? (
              <>
                <dt className="text-muted-foreground">Excluded non-cash (free / staff-granted)</dt>
                <dd>
                  {rec.excludedNonCash.count} · {money(rec.excludedNonCash.amount)}
                </dd>
              </>
            ) : null}
            {rec.excludedNotMonthAttributable.count > 0 ? (
              <>
                <dt className="text-muted-foreground">Excluded (no billing month)</dt>
                <dd>
                  {rec.excludedNotMonthAttributable.count} · {money(rec.excludedNotMonthAttributable.amount)}
                </dd>
              </>
            ) : null}
          </dl>
        );
      })}
      {notes.length > 0 ? (
        <ul className="list-disc space-y-1 pl-5 text-sm text-muted-foreground" data-testid="reconciliation-notes">
          {notes.map((note) => (
            <li key={note}>{note}</li>
          ))}
        </ul>
      ) : null}
    </section>
  );
}
