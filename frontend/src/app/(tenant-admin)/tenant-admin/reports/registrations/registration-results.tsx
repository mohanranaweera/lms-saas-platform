"use client";
import Link from "next/link";
import { Download } from "lucide-react";
import { Button, buttonVariants } from "@/components/ui/button";
import {
  ExpiredNotice,
  EXPORT_DISABLED_REASON_ID,
  StaleNotice,
} from "@/components/reports/report-notices";
import { formatReportGenerated } from "@/lib/api/reports";
import type {
  RegistrationReport,
  RegistrationRowsPage,
} from "@/lib/api/registration-report";
export function RegistrationResults({
  report,
  rows,
  loading,
  error,
  stale,
  expired,
  downloading,
  onExport,
  onRunAgain,
  onRetry,
  onPageChange,
}: {
  report: RegistrationReport;
  rows: RegistrationRowsPage | null;
  loading: boolean;
  error: boolean;
  stale: boolean;
  expired: boolean;
  downloading: boolean;
  onExport: () => void;
  onRunAgain: () => void;
  onRetry: () => void;
  onPageChange: (page: number) => void;
}) {
  return (
    <div className="flex flex-col gap-5" data-testid="registration-results">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
        <div>
          <h2 className="font-semibold">
            Registered Students Report — {report.filters.startDate} to{" "}
            {report.filters.endDate}
          </h2>
          <p className="text-sm text-muted-foreground">
            {report.institute.name}
          </p>
          <div className="mt-2 flex flex-wrap gap-2 text-xs">
            <span className="rounded-full bg-primary/10 px-2.5 py-1 text-primary">
              {report.totalRegistrations} registration
              {report.totalRegistrations === 1 ? "" : "s"}
            </span>
            <span className="rounded-full bg-muted px-2.5 py-1">
              {report.filters.courses.length
                ? report.filters.courses.map((course) => course.name).join(", ")
                : "All Courses"}
            </span>
          </div>
          <p className="mt-2 text-xs text-muted-foreground">
            Current course associations and payment context as of{" "}
            {formatReportGenerated(report.currentFacts.asOf, report.timezone)}.
          </p>
        </div>
        <Button
          onClick={onExport}
          disabled={stale || expired || downloading}
          aria-describedby={
            stale || expired ? EXPORT_DISABLED_REASON_ID : undefined
          }
        >
          <Download aria-hidden="true" />
          {downloading ? "Preparing PDF…" : "Export PDF"}
        </Button>
      </div>
      {stale ? (
        <StaleNotice />
      ) : expired ? (
        <ExpiredNotice onRunAgain={onRunAgain} />
      ) : null}
      {!expired ? (
        <section aria-label="Registration results" aria-busy={loading}>
          {error ? (
            <div role="alert" className="rounded-lg border p-4">
              Rows could not be loaded.{" "}
              <Button size="sm" variant="outline" onClick={onRetry}>
                Try again
              </Button>
            </div>
          ) : !rows ? (
            <p className="text-sm text-muted-foreground">
              Loading registration rows…
            </p>
          ) : (
            <>
              <div className="hidden overflow-x-auto rounded-lg border md:block">
                <table className="w-full text-sm">
                  <thead className="bg-muted/60">
                    <tr>
                      <th className="px-3 py-2 text-right">#</th>
                      <th className="px-3 py-2 text-left">Index no.</th>
                      <th className="px-3 py-2 text-left">Student</th>
                      <th className="px-3 py-2 text-left">Email</th>
                      <th className="px-3 py-2 text-left">Mobile</th>
                      <th className="px-3 py-2 text-left">Registered By</th>
                      <th className="px-3 py-2 text-left">Last payment</th>
                      <th>
                        <span className="sr-only">Action</span>
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {rows.rows.map((row, index) => (
                      <tr key={row.studentProfileId} className="border-t">
                        <td className="px-3 py-2 text-right">
                          {rows.page * rows.size + index + 1}
                        </td>
                        <td className="px-3 py-2">{dash(row.indexNumber)}</td>
                        <td className="px-3 py-2 font-medium">
                          {dash(row.studentName)}
                        </td>
                        <td className="px-3 py-2">{dash(row.email)}</td>
                        <td className="whitespace-nowrap px-3 py-2">
                          {dash(row.mobile)}
                        </td>
                        <td className="px-3 py-2">{dash(row.registeredBy)}</td>
                        <td className="px-3 py-2">{dash(row.lastPayment)}</td>
                        <td className="px-3 py-2">
                          <Link
                            className={buttonVariants({
                              size: "sm",
                              variant: "outline",
                            })}
                            href={`/tenant-admin/students/${encodeURIComponent(row.studentProfileId)}`}
                          >
                            Student details
                          </Link>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <ul className="grid gap-3 md:hidden">
                {rows.rows.map((row, index) => (
                  <li
                    key={row.studentProfileId}
                    className="rounded-lg border p-3"
                  >
                    <div className="flex justify-between gap-2">
                      <div>
                        <p className="font-medium">{dash(row.studentName)}</p>
                        <p className="text-xs text-muted-foreground">
                          #{rows.page * rows.size + index + 1} · Index{" "}
                          {dash(row.indexNumber)}
                        </p>
                      </div>
                      <Link
                        className={buttonVariants({
                          size: "sm",
                          variant: "outline",
                        })}
                        href={`/tenant-admin/students/${encodeURIComponent(row.studentProfileId)}`}
                      >
                        Details
                      </Link>
                    </div>
                    <dl className="mt-3 grid grid-cols-[auto_1fr] gap-x-3 text-sm">
                      <dt className="text-muted-foreground">Email</dt>
                      <dd className="break-all">{dash(row.email)}</dd>
                      <dt className="text-muted-foreground">Mobile</dt>
                      <dd>{dash(row.mobile)}</dd>
                      <dt className="text-muted-foreground">Registered by</dt>
                      <dd>{dash(row.registeredBy)}</dd>
                      <dt className="text-muted-foreground">Last payment</dt>
                      <dd>{dash(row.lastPayment)}</dd>
                    </dl>
                  </li>
                ))}
              </ul>
              {rows.totalPages > 1 ? (
                <div className="mt-3 flex items-center justify-between">
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={!rows.page || loading}
                    onClick={() => onPageChange(rows.page - 1)}
                  >
                    Previous
                  </Button>
                  <p className="text-sm text-muted-foreground">
                    Page {rows.page + 1} of {rows.totalPages} · {rows.totalRows}{" "}
                    registrations
                  </p>
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={rows.page + 1 >= rows.totalPages || loading}
                    onClick={() => onPageChange(rows.page + 1)}
                  >
                    Next
                  </Button>
                </div>
              ) : null}
            </>
          )}
        </section>
      ) : null}
    </div>
  );
}
function dash(value: string | null | undefined) {
  return value?.trim() ? value : "—";
}
