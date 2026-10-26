"use client";

import { forwardRef } from "react";
import { Download, FileText } from "lucide-react";
import { Button } from "@/components/ui/button";
import { DataTable, type DataTableColumn } from "@/components/ui/data-table";
import { cn } from "@/lib/utils";
import {
  formatReportAmount,
  formatReportGenerated,
  reportPaymentMethodLabel,
  type ReportCourseRow,
  type ReportCurrencyReconciliation,
  type ReportSection,
  type ReportTeacherBlock,
  type TeacherIncomeReport,
} from "@/lib/api/reports";
import { InfoHint } from "@/components/reports/info-hint";
import { ExpiredNotice, StaleNotice } from "@/components/reports/report-notices";

/**
 * Renders a server-computed RPT-01 run. Every figure is the backend's own
 * decimal string, formatted only; nothing is summed here, and each currency
 * is its own section (never combined).
 */

const TEACHER_SHARE_HINT =
  "The teacher's earned share of this revenue at the share rate in effect. It is not the cash paid out to the teacher - see Payouts.";
const BALANCE_HINT =
  "Movement for this period: Teacher share (earned) minus Payouts. It is not an opening or closing account balance.";
const PAYINS_HINT = "There is no record of money paid by teachers to the institute, so this is not tracked.";

function isZero(amount: string | null | undefined): boolean {
  return !amount || Number(amount) === 0;
}

export interface TeacherIncomeResultsProps {
  report: TeacherIncomeReport;
  stale: boolean;
  expired: boolean;
  downloading: string | null;
  downloadError: string | null;
  onExportSummary: () => void;
  onDownloadPayslip: (teacher: ReportTeacherBlock) => void;
  /** Re-submits the current filters (used when the run has expired). */
  onRunAgain: () => void;
}

export const TeacherIncomeResults = forwardRef<HTMLHeadingElement, TeacherIncomeResultsProps>(
  function TeacherIncomeResults(
    { report, stale, expired, downloading, downloadError, onExportSummary, onDownloadPayslip, onRunAgain },
    headingRef
  ) {
    const exportDisabled = stale || expired || downloading !== null;
    const disabledReason = stale
      ? "Filters changed - run the search again to export."
      : expired
        ? "This report has expired - run it again."
        : undefined;
    const { filters } = report;

    return (
      <div className="flex flex-col gap-6" data-testid="teacher-income-results">
        <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
          <div className="flex flex-col gap-1">
            <h2 ref={headingRef} tabIndex={-1} className="text-base font-semibold outline-none">
              Teacher income summary
            </h2>
            <p className="text-sm text-muted-foreground">{report.institute.name}</p>
          </div>
          <div className="flex flex-col items-start gap-1 sm:items-end">
            <Button
              type="button"
              onClick={onExportSummary}
              disabled={exportDisabled}
              aria-describedby={disabledReason ? "export-disabled-reason" : undefined}
            >
              <Download aria-hidden="true" />
              {downloading === "summary" ? "Preparing PDF…" : "Export PDF"}
            </Button>
          </div>
        </div>

        {stale ? <StaleNotice /> : expired ? <ExpiredNotice onRunAgain={onRunAgain} /> : null}

        {downloadError && !expired ? (
          <p role="alert" className="rounded-md border border-destructive/30 bg-destructive/5 px-3 py-2 text-sm text-destructive">
            {downloadError}
          </p>
        ) : null}

        <section aria-labelledby="applied-filters-heading" className="rounded-lg border border-border p-3">
          <h3 id="applied-filters-heading" className="mb-2 text-sm font-semibold">
            Applied filters
          </h3>
          <dl className="grid grid-cols-1 gap-x-6 gap-y-1.5 text-sm sm:grid-cols-[auto_1fr]">
            <dt className="text-muted-foreground">
              {filters.periodMode === "MONTHS" ? "Billing months" : "Payment received (confirmed)"}
            </dt>
            <dd data-testid="applied-period">{filters.periodLabel}</dd>
            <dt className="text-muted-foreground">Teachers</dt>
            <dd>{filters.teachers.map((teacher) => teacher.name).join(", ")}</dd>
            <dt className="text-muted-foreground">Payment method</dt>
            <dd>{reportPaymentMethodLabel(filters.paymentMethod)}</dd>
            <dt className="text-muted-foreground">Generated</dt>
            <dd>
              {formatReportGenerated(report.generatedAt, report.timezone)} ({report.timezone})
            </dd>
          </dl>
        </section>

        {report.sections.length > 1 ? (
          <p className="text-sm text-muted-foreground" data-testid="multi-currency-note">
            Amounts in different currencies are never combined.
          </p>
        ) : null}

        {report.sections.map((section) => (
          <CurrencySection
            key={section.currency}
            section={section}
            downloading={downloading}
            payslipDisabled={exportDisabled}
            disabledReasonId={disabledReason ? "export-disabled-reason" : undefined}
            onDownloadPayslip={onDownloadPayslip}
          />
        ))}

        <Reconciliation byCurrency={report.reconciliation.byCurrency} notes={report.reconciliation.notes} />
      </div>
    );
  }
);

function CurrencySection({
  section,
  downloading,
  payslipDisabled,
  disabledReasonId,
  onDownloadPayslip,
}: {
  section: ReportSection;
  downloading: string | null;
  payslipDisabled: boolean;
  disabledReasonId?: string;
  onDownloadPayslip: (teacher: ReportTeacherBlock) => void;
}) {
  const { currency, totals } = section;
  const money = (amount: string | null | undefined) => formatReportAmount(amount, currency);
  const headingId = `section-${currency}`;

  return (
    <section aria-labelledby={headingId} className="flex flex-col gap-4" data-testid={`currency-section-${currency}`}>
      <h3 id={headingId} className="text-sm font-semibold">
        Amounts in {currency}
      </h3>
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <Kpi label="Total Revenue" value={money(totals.revenue)} accent="border-l-blue-500" />
        <Kpi
          label="Teacher share (earned)"
          value={money(totals.teacherIncome)}
          accent="border-l-emerald-500"
          hint={TEACHER_SHARE_HINT}
        />
        <Kpi label="Institute Commission" value={money(totals.commission)} accent="border-l-violet-500" />
        <Kpi label="Total Teachers" value={String(totals.teacherCount)} accent="border-l-amber-500" />
      </div>
      <p className="text-sm text-muted-foreground" data-testid={`service-fees-${currency}`}>
        Service fees collected (institute only, not in the figures above):{" "}
        <span className="font-medium text-foreground">{money(totals.serviceFees)}</span>
      </p>
      {!isZero(totals.unratedRevenue) ? (
        <p className="text-sm text-muted-foreground">
          * {money(totals.unratedRevenue)} of revenue has no teacher share rate in effect, so it is not
          included in Teacher share or Institute Commission.
        </p>
      ) : null}
      <p className="text-xs text-muted-foreground">
        Payouts are settlements marked paid in this period; the payment-method filter does not apply to them.
      </p>

      <ul className="flex flex-col gap-4" aria-label={`Teachers (${currency})`}>
        {section.teachers.map((teacher) => (
          <li key={teacher.teacherId}>
            <TeacherCard
              teacher={teacher}
              currency={currency}
              downloading={downloading === `payslip:${teacher.teacherId}`}
              disabled={payslipDisabled}
              disabledReasonId={disabledReasonId}
              onDownload={() => onDownloadPayslip(teacher)}
            />
          </li>
        ))}
      </ul>
    </section>
  );
}

function Kpi({ label, value, accent, hint }: { label: string; value: string; accent: string; hint?: string }) {
  return (
    <div className={cn("rounded-lg border border-border border-l-4 bg-card p-3", accent)} data-testid="kpi">
      <div className="flex items-center gap-1 text-xs font-medium tracking-wide text-muted-foreground uppercase">
        <span>{label}</span>
        {hint ? <InfoHint label={label}>{hint}</InfoHint> : null}
      </div>
      <p className="mt-1 text-lg font-semibold tabular-nums">{value}</p>
    </div>
  );
}

function TeacherCard({
  teacher,
  currency,
  downloading,
  disabled,
  disabledReasonId,
  onDownload,
}: {
  teacher: ReportTeacherBlock;
  currency: string;
  downloading: boolean;
  disabled: boolean;
  disabledReasonId?: string;
  onDownload: () => void;
}) {
  const money = (amount: string | null | undefined) => formatReportAmount(amount, currency);
  const { totals } = teacher;
  const headingId = `teacher-${currency}-${teacher.teacherId}`;

  const columns: DataTableColumn<ReportCourseRow>[] = [
    {
      key: "course",
      header: "Course",
      cell: (row) => (
        <span className="text-foreground">
          {row.courseName}
          {row.rateMissing ? (
            <>
              <span aria-hidden="true"> *</span>
              <span className="sr-only"> (no share rate for all or part of this row)</span>
            </>
          ) : null}
        </span>
      ),
      hideOnCard: true,
    },
    { key: "payments", header: "No. Payments", cell: (row) => row.paymentCount, className: "tabular-nums" },
    { key: "total", header: "Total", cell: (row) => money(row.revenue), className: "tabular-nums" },
    { key: "teacher", header: "Teacher share", cell: (row) => money(row.teacherIncome), className: "tabular-nums" },
    { key: "commission", header: "Commission", cell: (row) => money(row.commission), className: "tabular-nums" },
  ];

  return (
    <article aria-labelledby={headingId} className="flex flex-col gap-3 rounded-lg border border-border p-3" data-testid="teacher-card">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h4 id={headingId} className="text-sm font-semibold">
          {teacher.teacherName}
        </h4>
        <Button
          type="button"
          variant="outline"
          size="sm"
          onClick={onDownload}
          disabled={disabled}
          aria-describedby={disabled ? disabledReasonId : undefined}
          aria-label={`Download payslip for ${teacher.teacherName} (${currency})`}
        >
          <FileText aria-hidden="true" />
          {downloading ? "Preparing…" : "Payslip"}
        </Button>
      </div>
      <dl className="grid grid-cols-2 gap-3 text-sm sm:grid-cols-4">
        <Metric label="Total income (earned share)" value={money(totals.teacherIncome)} hint={TEACHER_SHARE_HINT} />
        <Metric label="Payouts" value={money(totals.payouts)} valueClass="text-red-700 dark:text-red-400" />
        <Metric
          label="Payins"
          value={totals.payins === null ? "Not tracked" : money(totals.payins)}
          valueClass={totals.payins === null ? "text-muted-foreground font-normal" : undefined}
          hint={PAYINS_HINT}
        />
        <Metric label="Balance" value={money(totals.balance)} valueClass="text-blue-700 dark:text-blue-400" hint={BALANCE_HINT} />
      </dl>
      {teacher.courses.length === 0 ? (
        <p className="text-sm text-muted-foreground">
          No course income in this period - only payouts are shown for this teacher.
        </p>
      ) : (
        <>
          <DataTable
            columns={columns}
            rows={teacher.courses}
            rowKey={(row) => row.courseId}
            caption={`Course income for ${teacher.teacherName} (${currency})`}
            cardHeading={(row) => (
              <>
                {row.courseName}
                {row.rateMissing ? (
                  <>
                    <span aria-hidden="true"> *</span>
                    <span className="sr-only"> (no share rate for all or part of this row)</span>
                  </>
                ) : null}
              </>
            )}
          />
          {teacher.courses.some((row) => row.rateMissing) ? (
            <p className="text-xs text-muted-foreground">
              * No teacher share rate was in effect for all or part of this course&apos;s revenue; that part is
              not in Teacher share or Commission (&ldquo;—&rdquo; = no rate at all).
            </p>
          ) : null}
        </>
      )}
    </article>
  );
}

function Metric({
  label,
  value,
  valueClass,
  hint,
}: {
  label: string;
  value: string;
  valueClass?: string;
  hint?: string;
}) {
  return (
    <div className="flex flex-col gap-0.5">
      <dt className="flex items-center gap-1 text-xs text-muted-foreground">
        {label}
        {hint ? <InfoHint label={label}>{hint}</InfoHint> : null}
      </dt>
      <dd className={cn("font-semibold tabular-nums", valueClass)}>{value}</dd>
    </div>
  );
}

function Reconciliation({ byCurrency, notes }: { byCurrency: ReportCurrencyReconciliation[]; notes: string[] }) {
  if (byCurrency.length === 0 && notes.length === 0) return null;
  return (
    <section aria-labelledby="reconciliation-heading" className="flex flex-col gap-3 rounded-lg border border-border p-3">
      <h3 id="reconciliation-heading" className="text-sm font-semibold">
        Reconciliation notes
      </h3>
      {byCurrency.map((rec) => {
        const money = (amount: string) => formatReportAmount(amount, rec.currency);
        return (
          <dl
            key={rec.currency}
            className="grid grid-cols-1 gap-x-6 gap-y-1 text-sm sm:grid-cols-[auto_1fr]"
            aria-label={`Reconciliation (${rec.currency})`}
          >
            <dt className="text-muted-foreground">Gross revenue ({rec.currency})</dt>
            <dd>{money(rec.grossRevenue)}</dd>
            <dt className="text-muted-foreground">Refunds</dt>
            <dd>{money(rec.refunds)}</dd>
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
            {!isZero(rec.settledBasisDifference) ? (
              <>
                <dt className="text-muted-foreground">Settled basis difference</dt>
                <dd>{money(rec.settledBasisDifference)}</dd>
              </>
            ) : null}
            {!isZero(rec.unassignedRevenue) ? (
              <>
                <dt className="text-muted-foreground">Revenue of courses with no teacher (not shown)</dt>
                <dd>{money(rec.unassignedRevenue)}</dd>
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
