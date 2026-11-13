"use client";

import { useMemo, useState } from "react";
import { Button } from "@/components/ui/button";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { EmptyState } from "@/components/states/empty-state";
import { LiveRegion } from "@/components/ui/live-region";
import { AttendanceFilterForm } from "@/components/attendance/attendance-filter-form";
import { AttendanceStatusChip } from "@/components/attendance/attendance-status-chip";
import { MyAttendanceSummary } from "@/components/attendance/my-attendance-summary";
import { formatAttendanceSession } from "@/components/attendance/attendance-session-label";
import { useMyEnrolledCourseSummaries } from "@/lib/api/enrollments";
import { useMyAttendance, type AttendanceListParams } from "@/lib/api/attendance";
import { shortId } from "@/lib/format";
import { useT } from "@/i18n/i18n-provider";
import { useLocaleFormat } from "@/i18n/use-locale-format";

const PAGE_SIZE = 20;

/**
 * Student My Attendance (MVP-016, plan §11 screen #3). `GET
 * /api/v1/attendance/my` is owner-only (`hasRole('STUDENT')`, no id param) —
 * this always renders this student's own attendance history, never another
 * student's (`.claude/rules/ui-ux.md` §1).
 *
 * The filter's course options come from `useMyEnrolledCourseSummaries()`
 * (`lib/api/enrollments.ts`), which documents its own "graceful degradation"
 * contract: a failed/pending read here degrades to an empty course list
 * rather than blocking this page — this page's own attendance read is
 * entirely independent of that query's status.
 */
export default function StudentAttendancePage() {
  const courseSummariesQuery = useMyEnrolledCourseSummaries();
  const t = useT();
  const { formatDateTime } = useLocaleFormat();
  const [params, setParams] = useState<Pick<AttendanceListParams, "courseId" | "from" | "to">>({});
  const [page, setPage] = useState(0);
  const query = useMyAttendance({ ...params, page, size: PAGE_SIZE });

  const courseOptions = useMemo(
    () => (courseSummariesQuery.data ?? []).map((course) => ({ id: course.id, label: course.name })),
    [courseSummariesQuery.data]
  );
  const courseNames = useMemo(
    () => new Map(courseOptions.map((option) => [option.id, option.label])),
    [courseOptions]
  );

  const filtersActive = Boolean(params.courseId || params.from || params.to);
  // Background refetch (page-turn / filter-apply) case only — `query.data`
  // is already populated (from `keepPreviousData`) so `QueryStateBoundary`'s
  // own initial-load skeleton/loading path is never reached here; this is
  // purely the "already have a page on screen, quietly fetching the next
  // one" signal `lib/api/attendance.ts`'s hook doc comment calls out.
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
        <h1 className="text-xl font-semibold break-words text-foreground">{t("student.attendance.title")}</h1>
        <p className="text-sm text-muted-foreground">{t("student.attendance.subtitle")}</p>
      </div>

      <AttendanceFilterForm
        idPrefix="student-attendance"
        courseOptions={courseOptions}
        onApply={handleApply}
        onClear={handleClear}
        disabled={query.isFetching}
      />

      <MyAttendanceSummary params={{ from: params.from, to: params.to }} />

      <QueryStateBoundary
        query={query}
        loadingLabel={t("student.attendance.loading")}
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/student/dashboard" }}
        isEmpty={(data) => data.content.length === 0 && page === 0}
        emptyState={
          filtersActive
            ? {
                title: t("student.attendance.noMatch.title"),
                description: t("student.attendance.noMatch.description"),
                action: { label: t("student.attendance.noMatch.clear"), onClick: handleClear },
              }
            : {
                title: t("student.attendance.empty.title"),
                description: t("student.attendance.empty.description"),
              }
        }
      >
        {(data) => (
          <div className="flex flex-col gap-3" aria-busy={isRefetching}>
            <LiveRegion message={isRefetching ? t("student.common.updating") : ""} />
            {data.content.length === 0 ? (
              <EmptyState
                title={t("student.common.noMoreResults.title")}
                description={t("student.common.noMoreResults.description")}
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
                        {courseNames.get(record.courseId) ?? shortId(record.courseId, t("student.common.courseIdLabel"))}
                      </span>
                      <span className="text-xs text-muted-foreground">
                        {formatAttendanceSession(record)} · {t("student.attendance.marked", { when: formatDateTime(record.markedAt) })}
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
                {t("student.common.previous")}
              </Button>
              <span className="text-xs text-muted-foreground">
                {t("student.common.pageOf", { page: data.page + 1, total: Math.max(data.totalPages, 1) })}
              </span>
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => setPage((current) => current + 1)}
                disabled={data.page + 1 >= data.totalPages || query.isFetching}
              >
                {t("student.common.next")}
              </Button>
            </div>
          </div>
        )}
      </QueryStateBoundary>
    </div>
  );
}
