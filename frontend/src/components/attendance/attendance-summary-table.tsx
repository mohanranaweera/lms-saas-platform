"use client";

import { DataTable, type DataTableColumn } from "@/components/ui/data-table";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import {
  useCourseAttendanceSummary,
  type AttendanceSummaryParams,
  type AttendanceSummaryRow,
} from "@/lib/api/attendance";
import { shortId } from "@/lib/format";

import { useT } from "@/i18n/i18n-provider";
const columns: DataTableColumn<AttendanceSummaryRow>[] = [
  {
    key: "student",
    header: "Student",
    cell: (row) => row.studentName ?? shortId(row.studentId, "Student"),
    hideOnCard: true,
  },
  { key: "present", header: "Present", cell: (row) => row.present },
  { key: "late", header: "Late", cell: (row) => row.late },
  { key: "absent", header: "Absent", cell: (row) => row.absent },
  { key: "total", header: "Sessions marked", cell: (row) => row.total },
  {
    key: "rate",
    header: "Attendance",
    // Server-computed (present + late) / total — displayed, never recomputed.
    cell: (row) => `${row.attendanceRate.toFixed(1)}%`,
  },
];

/**
 * Per-student attendance percentages for ONE course (Wave 8, `GET
 * /v1/attendance/summary`). Shared by the Teacher and Tenant Admin reports
 * screens — the backend scopes the result (Teacher: own course only; staff:
 * `ATTENDANCE`/`VIEW`), so a 403/404 here surfaces through
 * `QueryStateBoundary` rather than being predicted client-side.
 */
export function AttendanceSummaryTable({
  courseId,
  params,
  dashboardHref,
}: {
  courseId: string;
  params?: AttendanceSummaryParams;
  dashboardHref: string;
}) {
  const t = useT();
  const query = useCourseAttendanceSummary(courseId, params);

  return (
    <section aria-labelledby="attendance-summary-heading" className="flex flex-col gap-3">
      <div>
        <h2 id="attendance-summary-heading" className="text-base font-medium text-foreground">
          {t("attendance.summaryTable.attendanceSummary")}
        </h2>
        <p className="text-xs text-muted-foreground">
          {t("attendance.summaryTable.attendanceRateCountsPresent")}
        </p>
      </div>
      <QueryStateBoundary
        query={query}
        loadingLabel={t("attendance.summaryTable.loadingAttendanceSummary")}
        loginPath="/login"
        permissionDenied={{ dashboardHref }}
        isEmpty={(rows) => rows.length === 0}
        emptyState={{
          title: t("attendance.summaryTable.noAttendanceRecordedFor"),
          description: t("attendance.summaryTable.onceAttendanceIsMarked"),
        }}
      >
        {(rows) => (
          <DataTable
            columns={columns}
            rows={rows}
            rowKey={(row) => row.studentId}
            caption={t("attendance.summaryTable.attendanceSummaryByStudent")}
            cardHeading={(row) => row.studentName ?? shortId(row.studentId, "Student")}
          />
        )}
      </QueryStateBoundary>
    </section>
  );
}
