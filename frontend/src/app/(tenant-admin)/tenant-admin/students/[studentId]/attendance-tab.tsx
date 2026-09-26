"use client";

import { useState } from "react";
import { Button } from "@/components/ui/button";
import { DataTable, type DataTableColumn } from "@/components/ui/data-table";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { useStudentAttendanceReport } from "@/lib/api/attendance";
import type { AttendanceRecordResponse } from "@/lib/api/attendance";
import { formatDateTime, shortId } from "@/lib/format";
import { formatAttendanceSession } from "@/components/attendance/attendance-session-label";

const PAGE_SIZE = 10;

const columns: DataTableColumn<AttendanceRecordResponse>[] = [
  { key: "course", header: "Course", cell: (row) => shortId(row.courseId) },
  { key: "session", header: "Session", cell: (row) => formatAttendanceSession(row) },
  { key: "status", header: "Status", cell: (row) => row.status },
  { key: "markedAt", header: "Marked at", cell: (row) => formatDateTime(row.markedAt) },
];

/** Attendance tab (Wave 3) — `GET /v1/attendance/students/{id}/report`, paginated. */
export function AttendanceTab({ studentId }: { studentId: string }) {
  const [page, setPage] = useState(0);
  const query = useStudentAttendanceReport(studentId, { page, size: PAGE_SIZE });

  return (
    <QueryStateBoundary
      query={query}
      loadingLabel="Loading attendance…"
      isEmpty={(data) => data.content.length === 0 && page === 0}
      emptyState={{
        title: "No attendance recorded yet",
        description: "No attendance records exist for this student yet.",
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
              cardHeading={(row) => shortId(row.courseId)}
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
  );
}
