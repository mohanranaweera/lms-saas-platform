"use client";

import { forwardRef } from "react";
import Link from "next/link";
import { Download } from "lucide-react";
import { Button, buttonVariants } from "@/components/ui/button";
import { ExpiredNotice, EXPORT_DISABLED_REASON_ID, StaleNotice } from "@/components/reports/report-notices";
import { formatReportGenerated } from "@/lib/api/reports";
import type { UnpaidStudentsReport, UnpaidStudentsRowsPage } from "@/lib/api/unpaid-students-report";

interface Props {
  report: UnpaidStudentsReport;
  rows: UnpaidStudentsRowsPage | null;
  rowsLoading: boolean;
  rowsError: boolean;
  stale: boolean;
  expired: boolean;
  downloading: boolean;
  downloadError: string | null;
  onExport: () => void;
  onRunAgain: () => void;
  onRetryRows: () => void;
  onPageChange: (page: number) => void;
}

export const UnpaidStudentsResults = forwardRef<HTMLHeadingElement, Props>(function UnpaidStudentsResults(
  { report, rows, rowsLoading, rowsError, stale, expired, downloading, downloadError, onExport, onRunAgain, onRetryRows, onPageChange },
  ref
) {
  return (
    <div className="flex flex-col gap-5" data-testid="unpaid-students-results">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
        <div className="flex flex-col gap-2">
          <h2 ref={ref} tabIndex={-1} className="text-base font-semibold outline-none">
            Unpaid Students Report — {report.filters.billingMonthLabel}
          </h2>
          <p className="text-sm text-muted-foreground">{report.institute.name}</p>
          <ul className="flex flex-wrap gap-1.5 text-xs" aria-label="Applied filters">
            <li className="rounded-full bg-muted px-2.5 py-1">Course: {report.course.name}</li>
            <li className="rounded-full bg-destructive/10 px-2.5 py-1 text-destructive">{report.unpaidCount} unpaid student{report.unpaidCount === 1 ? "" : "s"}</li>
            <li className="rounded-full bg-muted px-2.5 py-1">At least one attended: {report.filters.atLeastOneAttended ? "Yes" : "No"}</li>
          </ul>
          <p className="text-xs text-muted-foreground">
            Current course participation as of {formatReportGenerated(report.enrollmentBasis.asOf, report.timezone)}. Generated {formatReportGenerated(report.generatedAt, report.timezone)} ({report.timezone}).
          </p>
        </div>
        <Button type="button" onClick={onExport} disabled={stale || expired || downloading} aria-describedby={stale || expired ? EXPORT_DISABLED_REASON_ID : undefined}>
          <Download aria-hidden="true" /> {downloading ? "Preparing PDF…" : "Export PDF"}
        </Button>
      </div>
      {stale ? <StaleNotice /> : expired ? <ExpiredNotice onRunAgain={onRunAgain} /> : null}
      {downloadError && !expired ? <p role="alert" className="rounded-md border border-destructive/30 bg-destructive/5 px-3 py-2 text-sm text-destructive">{downloadError}</p> : null}
      {!expired ? (
        <section aria-label="Unpaid students table" aria-busy={rowsLoading}>
          {rowsError ? (
            <div role="alert" className="rounded-lg border p-4 text-sm">Rows could not be loaded. <Button type="button" variant="outline" size="sm" onClick={onRetryRows}>Try again</Button></div>
          ) : !rows ? (
            <p className="text-sm text-muted-foreground">Loading student rows…</p>
          ) : (
            <>
              <div className="hidden overflow-x-auto rounded-lg border md:block">
                <table className="w-full text-sm"><thead className="bg-muted/60"><tr><th className="px-3 py-2 text-right">#</th><th className="px-3 py-2 text-left">Index no.</th><th className="px-3 py-2 text-left">Student</th><th className="px-3 py-2 text-left">Email</th><th className="px-3 py-2 text-left">Mobile</th><th className="px-3 py-2 text-right"><span className="sr-only">Action</span></th></tr></thead>
                  <tbody>{rows.rows.map((row, index) => <tr key={row.studentId} className="border-t"><td className="px-3 py-2 text-right">{rows.page * rows.size + index + 1}</td><td className="px-3 py-2">{dash(row.indexNumber)}</td><td className="px-3 py-2 font-medium">{row.studentName}</td><td className="px-3 py-2">{dash(row.email)}</td><td className="px-3 py-2">{dash(row.mobile)}</td><td className="px-3 py-2 text-right">{row.studentProfileId ? <Link className={buttonVariants({ size: "sm", variant: "outline" })} href={`/tenant-admin/students/${encodeURIComponent(row.studentProfileId)}`}>Student details</Link> : <span className="text-muted-foreground">—</span>}</td></tr>)}</tbody>
                </table>
              </div>
              <ul className="grid gap-3 md:hidden" aria-label="Unpaid students">{rows.rows.map((row, index) => <li key={row.studentId} className="rounded-lg border p-3"><div className="flex justify-between gap-3"><div><p className="font-medium">{row.studentName}</p><p className="text-xs text-muted-foreground">#{rows.page * rows.size + index + 1} · Index {dash(row.indexNumber)}</p></div>{row.studentProfileId ? <Link className={buttonVariants({ size: "sm", variant: "outline" })} href={`/tenant-admin/students/${encodeURIComponent(row.studentProfileId)}`}>Details</Link> : null}</div><dl className="mt-3 grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 text-sm"><dt className="text-muted-foreground">Email</dt><dd className="break-all">{dash(row.email)}</dd><dt className="text-muted-foreground">Mobile</dt><dd>{dash(row.mobile)}</dd></dl></li>)}</ul>
              {rows.totalPages > 1 ? <div className="mt-3 flex items-center justify-between"><Button type="button" variant="outline" size="sm" disabled={rows.page === 0 || rowsLoading} onClick={() => onPageChange(rows.page - 1)}>Previous</Button><p className="text-sm text-muted-foreground">Page {rows.page + 1} of {rows.totalPages} · {rows.totalRows} students</p><Button type="button" variant="outline" size="sm" disabled={rows.page + 1 >= rows.totalPages || rowsLoading} onClick={() => onPageChange(rows.page + 1)}>Next</Button></div> : null}
            </>
          )}
        </section>
      ) : null}
    </div>
  );
});

function dash(value: string | null | undefined) {
  return value?.trim() ? value : "—";
}
