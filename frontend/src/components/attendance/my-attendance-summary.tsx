"use client";

import { useT } from "@/i18n/i18n-provider";

import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { useMyAttendanceSummary, type AttendanceSummaryParams } from "@/lib/api/attendance";
import { shortId } from "@/lib/format";

/**
 * Student's own per-course attendance percentages (Wave 8, `GET
 * /v1/attendance/my/summary`) — owner-only server-side, so this only ever
 * renders the calling student's own totals. The rate is server-computed
 * (`(present + late) / total`) and displayed as-is.
 */
export function MyAttendanceSummary({ params }: { params?: AttendanceSummaryParams }) {
  const query = useMyAttendanceSummary(params);
  const t = useT();

  return (
    <section aria-labelledby="my-attendance-summary-heading" className="flex flex-col gap-3">
      <h2 id="my-attendance-summary-heading" className="text-base font-medium text-foreground">
        {t("student.attendanceSummary.heading")}
      </h2>
      <QueryStateBoundary
        query={query}
        loadingLabel={t("student.attendanceSummary.loading")}
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/student/dashboard" }}
        isEmpty={(rows) => rows.length === 0}
        emptyState={{
          title: t("student.attendanceSummary.empty.title"),
          description: t("student.attendanceSummary.empty.description"),
        }}
      >
        {(rows) => (
          <ul className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {rows.map((row) => (
              <li key={row.courseId} className="flex flex-col gap-1 rounded-lg border border-border p-4">
                <span className="text-sm font-medium text-foreground">
                  {row.courseName ?? shortId(row.courseId, t("student.common.courseIdLabel"))}
                </span>
                <span className="text-2xl font-semibold text-foreground">
                  {row.attendanceRate.toFixed(1)}%
                  <span className="sr-only"> {t("student.attendanceSummary.rateSuffix")}</span>
                </span>
                <span className="text-xs text-muted-foreground">
                  {t("student.attendanceSummary.counts", { present: row.present, late: row.late, absent: row.absent, total: row.total })}
                </span>
              </li>
            ))}
          </ul>
        )}
      </QueryStateBoundary>
    </section>
  );
}
