"use client";

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

  return (
    <section aria-labelledby="my-attendance-summary-heading" className="flex flex-col gap-3">
      <h2 id="my-attendance-summary-heading" className="text-base font-medium text-foreground">
        Attendance by course
      </h2>
      <QueryStateBoundary
        query={query}
        loadingLabel="Loading your attendance summary…"
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/student/dashboard" }}
        isEmpty={(rows) => rows.length === 0}
        emptyState={{
          title: "No attendance summary yet",
          description: "Your per-course attendance rate appears here once a teacher records attendance for you.",
        }}
      >
        {(rows) => (
          <ul className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {rows.map((row) => (
              <li key={row.courseId} className="flex flex-col gap-1 rounded-lg border border-border p-4">
                <span className="text-sm font-medium text-foreground">
                  {row.courseName ?? shortId(row.courseId, "Course")}
                </span>
                <span className="text-2xl font-semibold text-foreground">
                  {row.attendanceRate.toFixed(1)}%
                  <span className="sr-only"> attendance</span>
                </span>
                <span className="text-xs text-muted-foreground">
                  {row.present} present · {row.late} late · {row.absent} absent ({row.total} marked)
                </span>
              </li>
            ))}
          </ul>
        )}
      </QueryStateBoundary>
    </section>
  );
}
