"use client";

import { useMemo, useState } from "react";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { EmptyState } from "@/components/states/empty-state";
import { LiveRegion } from "@/components/ui/live-region";
import { DataTable, type DataTableColumn } from "@/components/ui/data-table";
import { AttendanceFilterForm } from "@/components/attendance/attendance-filter-form";
import { AttendanceStatusChip } from "@/components/attendance/attendance-status-chip";
import { useCourses } from "@/lib/api/courses";
import {
  useAttendanceReports,
  type AttendanceListParams,
  type AttendanceRecordResponse,
} from "@/lib/api/attendance";
import { formatDateTime, shortId } from "@/lib/format";

const PAGE_SIZE = 20;

const columns: DataTableColumn<AttendanceRecordResponse>[] = [
  { key: "course", header: "Course", cell: (row) => shortId(row.courseId, "Course") },
  { key: "session", header: "Session", cell: (row) => shortId(row.sessionId, "Session") },
  { key: "student", header: "Student", cell: (row) => shortId(row.studentId, "Student") },
  {
    key: "status",
    header: "Status",
    cell: (row) => <AttendanceStatusChip status={row.status} />,
    hideOnCard: true,
  },
  { key: "markedAt", header: "Marked at", cell: (row) => formatDateTime(row.markedAt) },
];

/**
 * Skeleton rows shown while `useAttendanceReports` is `pending` (plan §11:
 * "skeleton table rows for screen #4"). `QueryStateBoundary`'s own loading
 * branch only ever renders the generic `LoadingState` label with no slot for
 * a custom loading render, so this page checks `query.status === "pending"`
 * itself and renders this instead of invoking `QueryStateBoundary` at all for
 * that render — `QueryStateBoundary` is only reached once the query has left
 * `pending`, so its own (never-hit) `loadingLabel` prop is unused here.
 * Mirrors `DataTable`'s own table-on-desktop/cards-on-mobile split so there is
 * no layout shift once real rows arrive.
 */
function AttendanceReportsTableSkeleton() {
  return (
    <div aria-hidden="true" className="flex flex-col gap-3">
      <div className="hidden overflow-hidden rounded-lg border border-border md:block">
        <div className="divide-y divide-border">
          {Array.from({ length: 6 }).map((_, index) => (
            <div key={index} className="flex items-center gap-6 px-4 py-3">
              <Skeleton className="h-4 w-28" />
              <Skeleton className="h-4 w-28" />
              <Skeleton className="h-4 w-28" />
              <Skeleton className="h-5 w-20 rounded-md" />
              <Skeleton className="h-4 w-36" />
            </div>
          ))}
        </div>
      </div>
      <ul className="flex flex-col gap-3 md:hidden">
        {Array.from({ length: 3 }).map((_, index) => (
          <li key={index} className="flex flex-col gap-2 rounded-lg border border-border p-4">
            <Skeleton className="h-4 w-32" />
            <Skeleton className="h-3 w-full" />
            <Skeleton className="h-3 w-2/3" />
          </li>
        ))}
      </ul>
    </div>
  );
}

/**
 * Tenant Admin / Attendance Operator / Read-only Auditor Attendance Reports
 * (MVP-016, plan §11 screen #4). `GET /api/v1/attendance/reports` dispatches
 * tenant-wide server-side for these roles (unlike the Teacher variant, which
 * is scoped to that Teacher's own courses) — this page issues the real
 * request unconditionally and lets `QueryStateBoundary` render
 * `PermissionDeniedState` on an actual 403; the nav entry
 * (`tenant-admin-nav.tsx`, gated on `canViewAttendanceReports`) is pure UX
 * convenience only.
 *
 * Report-only: no mark/edit affordance anywhere on this screen, so a
 * Read-only Auditor sees exactly the same UI as Tenant Admin/Attendance
 * Operator here — correct, since there is no mutating control to hide.
 */
export default function TenantAdminAttendanceReportsPage() {
  const coursesQuery = useCourses();
  const [params, setParams] = useState<Pick<AttendanceListParams, "courseId" | "from" | "to">>({});
  const [page, setPage] = useState(0);
  const query = useAttendanceReports({ ...params, page, size: PAGE_SIZE });

  // Tenant-wide course list for these roles (no ownership filter) — degrades
  // gracefully to an empty filter list if this read is still loading/fails.
  const courseOptions = useMemo(
    () => (coursesQuery.data?.content ?? []).map((course) => ({ id: course.id, label: course.name })),
    [coursesQuery.data]
  );

  const filtersActive = Boolean(params.courseId || params.from || params.to);
  // Background refetch (page-turn / filter-apply) case only — the
  // `query.status === "pending"` branch below already owns the true
  // initial-load skeleton and is left untouched; this only covers the
  // "already showing a page, quietly fetching the next one" case. See
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
        <h1 className="text-xl font-semibold text-foreground">Attendance reports</h1>
        <p className="text-sm text-muted-foreground">
          Attendance recorded across every course in your tenant. This is a read-only report —
          attendance itself is recorded from the Mark Attendance screen.
        </p>
      </div>

      <AttendanceFilterForm
        idPrefix="tenant-admin-attendance-reports"
        courseOptions={courseOptions}
        onApply={handleApply}
        onClear={handleClear}
        disabled={query.isFetching}
      />

      {query.status === "pending" ? (
        <>
          <p role="status" aria-live="polite" aria-busy="true" className="sr-only">
            Loading attendance reports…
          </p>
          <AttendanceReportsTableSkeleton />
        </>
      ) : (
        <QueryStateBoundary
          query={query}
          loginPath="/login"
          permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
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
                  description: "Attendance recorded across your tenant's courses will appear here.",
                }
          }
        >
          {(data) => (
            <div className="flex flex-col gap-4" aria-busy={isRefetching}>
              <LiveRegion message={isRefetching ? "Updating…" : ""} />
              {data.content.length === 0 ? (
                <EmptyState
                  title="No more results"
                  description="There are no records on this page. Go back to an earlier page."
                />
              ) : (
                <div className={isRefetching ? "opacity-60" : undefined}>
                  <DataTable
                    columns={columns}
                    rows={data.content}
                    rowKey={(row) => row.id}
                    caption="Attendance reports"
                    cardHeading={(row) => shortId(row.courseId, "Course")}
                    cardHeadingAdornment={(row) => <AttendanceStatusChip status={row.status} />}
                  />
                </div>
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
      )}
    </div>
  );
}
