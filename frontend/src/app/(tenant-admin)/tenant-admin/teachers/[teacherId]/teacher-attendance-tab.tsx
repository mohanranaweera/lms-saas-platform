"use client";

import { useState } from "react";
import { Button } from "@/components/ui/button";
import { DataTable, type DataTableColumn } from "@/components/ui/data-table";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { useCourses } from "@/lib/api/courses";
import { useAttendanceReports, type AttendanceRecordResponse } from "@/lib/api/attendance";
import { formatDateTime, shortId } from "@/lib/format";
import { CourseScopeSelect } from "./course-scope-select";

const PAGE_SIZE = 10;

const columns: DataTableColumn<AttendanceRecordResponse>[] = [
  { key: "student", header: "Student", cell: (row) => shortId(row.studentId, "Student") },
  { key: "status", header: "Status", cell: (row) => row.status },
  { key: "markedAt", header: "Marked at", cell: (row) => formatDateTime(row.markedAt) },
];

/**
 * Attendance tab (Wave 3, PAR-04-03) — no teacherId-scoped attendance
 * endpoint exists on the backend; this scopes the existing staff-wide `GET
 * /v1/attendance/reports?courseId=` (`useAttendanceReports`, already server-
 * filtered to the caller's own tenant) to one of this teacher's own courses,
 * picked via `CourseScopeSelect`.
 */
export function TeacherAttendanceTab({ teacherId }: { teacherId: string }) {
  const coursesQuery = useCourses({ teacherId, size: 100 });
  // `null` means "no explicit choice yet" — falls back to the first assigned
  // course, derived directly from the query's already-available `data`
  // (no `setState`-in-effect round trip; see `teacher-roster-tab.tsx`'s
  // identical reasoning).
  const [explicitCourseId, setExplicitCourseId] = useState<string | null>(null);
  const [page, setPage] = useState(0);
  const selectedCourseId = explicitCourseId ?? coursesQuery.data?.content[0]?.id ?? "";

  const attendanceQuery = useAttendanceReports(
    { courseId: selectedCourseId || undefined, page, size: PAGE_SIZE },
    { enabled: selectedCourseId.length > 0 }
  );

  return (
    <QueryStateBoundary
      query={coursesQuery}
      loadingLabel="Loading assigned courses…"
      isEmpty={(data) => data.content.length === 0}
      emptyState={{
        title: "No assigned courses",
        description: "This teacher has no courses assigned to them, so there is no attendance to show.",
      }}
    >
      {(courseData) => (
        <div className="flex flex-col gap-4">
          <CourseScopeSelect
            idPrefix="teacher-attendance"
            courses={courseData.content}
            value={selectedCourseId}
            onChange={(next) => {
              setExplicitCourseId(next);
              setPage(0);
            }}
          />
          {selectedCourseId ? (
            <QueryStateBoundary
              query={attendanceQuery}
              loadingLabel="Loading attendance…"
              isEmpty={(data) => data.content.length === 0 && page === 0}
              emptyState={{
                title: "No attendance recorded yet",
                description: "No attendance has been recorded for this course yet.",
              }}
            >
              {(data) => (
                <div className="flex flex-col gap-4">
                  {data.content.length === 0 ? (
                    <p className="text-sm text-muted-foreground">No records on this page.</p>
                  ) : (
                    <DataTable
                      columns={columns}
                      rows={data.content}
                      rowKey={(row) => row.id}
                      caption="Attendance records"
                      cardHeading={(row) => shortId(row.studentId, "Student")}
                      cardHeadingAdornment={(row) => (
                        <span className="text-xs text-muted-foreground">{row.status}</span>
                      )}
                    />
                  )}
                  <div className="flex items-center justify-between">
                    <Button
                      type="button"
                      variant="outline"
                      size="sm"
                      onClick={() => setPage((current) => Math.max(0, current - 1))}
                      disabled={page === 0 || attendanceQuery.isFetching}
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
                      disabled={data.page + 1 >= data.totalPages || attendanceQuery.isFetching}
                    >
                      Next
                    </Button>
                  </div>
                </div>
              )}
            </QueryStateBoundary>
          ) : null}
        </div>
      )}
    </QueryStateBoundary>
  );
}
