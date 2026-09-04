"use client";

import { useMemo, useState } from "react";
import { Button } from "@/components/ui/button";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { EmptyState } from "@/components/states/empty-state";
import { LiveRegion } from "@/components/ui/live-region";
import { AttendanceFilterForm } from "@/components/attendance/attendance-filter-form";
import { AttendanceStatusChip } from "@/components/attendance/attendance-status-chip";
import { useCourses } from "@/lib/api/courses";
import { useAttendanceReports, type AttendanceListParams } from "@/lib/api/attendance";
import { formatDateTime, shortId } from "@/lib/format";

const PAGE_SIZE = 20;

/**
 * Teacher Attendance Reports (MVP-016, plan §11 screen #2). `GET
 * /api/v1/attendance/reports` is role-dispatched server-side to this
 * Teacher's own courses only — never fetched unfiltered and filtered
 * client-side (`.claude/rules/ui-ux.md` §1).
 *
 * Mobile-first card list rather than `DataTable`, matching the plan's brief
 * for this screen — each record already carries only one student's status
 * per row, so a card list reads naturally at every width, unlike the
 * Tenant Admin Reports screen (#4) which needs `DataTable`'s
 * table-on-desktop/cards-on-mobile behavior for a denser, wider row shape.
 *
 * `filtersActive` mirrors `teacher/courses/page.tsx`'s own pattern, tracked
 * from the applied filter params (not the raw form state) so it reflects
 * what was actually submitted, not what's merely typed into the form.
 */
export default function TeacherAttendanceReportsPage() {
  const coursesQuery = useCourses();
  const [params, setParams] = useState<Pick<AttendanceListParams, "courseId" | "from" | "to">>({});
  const [page, setPage] = useState(0);
  const query = useAttendanceReports({ ...params, page, size: PAGE_SIZE });

  // Gracefully falls back to an empty course list if `useCourses()` is still
  // loading or fails — the filter degrades, it never blocks this whole page.
  const courseOptions = useMemo(
    () => (coursesQuery.data?.content ?? []).map((course) => ({ id: course.id, label: course.name })),
    [coursesQuery.data]
  );

  const filtersActive = Boolean(params.courseId || params.from || params.to);
  // Background refetch (page-turn / filter-apply) case only — see
  // `lib/api/attendance.ts`'s `useAttendanceReports` doc comment.
  const isRefetching = query.isFetching && query.data !== undefined;

  function handleApply(next: Pick<AttendanceListParams, "courseId" | "from" | "to">) {
    setParams(next);
    setPage(0);
  }

  function handleClear() {
    setParams({});
    setPage(0);
  }

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold text-foreground">Attendance Reports</h1>
        <p className="text-sm text-muted-foreground">
          Attendance recorded for the courses you teach.
        </p>
      </div>

      <AttendanceFilterForm
        idPrefix="teacher-attendance-reports"
        courseOptions={courseOptions}
        onApply={handleApply}
        onClear={handleClear}
        disabled={query.isFetching}
      />

      <QueryStateBoundary
        query={query}
        loadingLabel="Loading attendance records…"
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/teacher/dashboard" }}
        isEmpty={(data) => data.content.length === 0 && page === 0}
        emptyState={
          filtersActive
            ? {
                title: "No sessions match the selected date filter",
                description: "Try a different course or date range, or clear your filters.",
                action: { label: "Clear filters", onClick: handleClear },
              }
            : {
                title: "No attendance records yet",
                description:
                  "Attendance you record for your own courses' sessions will appear here.",
              }
        }
      >
        {(data) => (
          <div className="flex flex-col gap-3" aria-busy={isRefetching}>
            <LiveRegion message={isRefetching ? "Updating…" : ""} />
            {data.content.length === 0 ? (
              <EmptyState
                title="No more results"
                description="There are no records on this page. Go back to an earlier page."
              />
            ) : (
              <ul className={`flex flex-col gap-2 ${isRefetching ? "opacity-60" : ""}`}>
                {data.content.map((record) => (
                  <li
                    key={record.id}
                    className="flex flex-col gap-2 rounded-lg border border-border p-4 sm:flex-row sm:items-center sm:justify-between"
                  >
                    <div className="flex flex-col gap-1">
                      <span className="text-sm font-medium text-foreground">
                        {shortId(record.courseId, "Course")}
                      </span>
                      <span className="text-xs text-muted-foreground">
                        {shortId(record.sessionId, "Session")} · {shortId(record.studentId, "Student")}
                      </span>
                      <span className="text-xs text-muted-foreground">
                        Marked {formatDateTime(record.markedAt)}
                      </span>
                    </div>
                    <AttendanceStatusChip status={record.status} />
                  </li>
                ))}
              </ul>
            )}
            <div className="flex items-center justify-between">
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => setPage((current) => Math.max(0, current - 1))}
                disabled={page === 0 || query.isFetching}
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
                onClick={() => setPage((current) => current + 1)}
                disabled={data.page + 1 >= data.totalPages || query.isFetching}
              >
                Next
              </Button>
            </div>
          </div>
        )}
      </QueryStateBoundary>
    </div>
  );
}
