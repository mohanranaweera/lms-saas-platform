"use client";

import { useState } from "react";
import { DataTable, type DataTableColumn } from "@/components/ui/data-table";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { DateRangeFilter } from "@/components/finance/date-range-filter";
import { SignedAmount } from "@/components/finance/signed-amount";
import { formatMoney } from "@/lib/format";
import {
  useCourseRevenue,
  useFinanceTeachers,
  useTeacherRevenue,
  type CourseRevenueRow,
  type DateRangeParams,
  type TeacherRevenueRow,
} from "@/lib/api/finance";

/**
 * Tenant Admin Finance Reports (Wave 7, PAR-23-04). Course and teacher revenue
 * are ledger-derived server-side (confirmed payments minus refunds recorded in
 * the range). Teacher revenue groups each course under its CURRENT teacher —
 * stated on screen so a reassigned course's history isn't misread.
 */
export default function FinanceReportsPage() {
  const [range, setRange] = useState<DateRangeParams>({});
  const courseQuery = useCourseRevenue(range);
  const teacherQuery = useTeacherRevenue(range);
  const teachersQuery = useFinanceTeachers();
  const teacherNames = new Map((teachersQuery.data ?? []).map((t) => [t.userId, t.name]));
  const teacherLabel = (id: string | null, email?: string | null) =>
    (id && teacherNames.get(id)) || email || (id ? `Teacher #${id.slice(0, 8)}` : "Unassigned");

  const courseColumns: DataTableColumn<CourseRevenueRow>[] = [
    { key: "course", header: "Course", cell: (row) => row.courseTitle ?? `Course #${row.courseId.slice(0, 8)}` },
    { key: "teacher", header: "Teacher", cell: (row) => teacherLabel(row.teacherId) },
    { key: "payments", header: "Payments", cell: (row) => row.paymentCount },
    { key: "gross", header: "Gross", cell: (row) => formatMoney(row.gross) },
    { key: "refunds", header: "Refunds", cell: (row) => formatMoney(row.refunds) },
    { key: "net", header: "Net", cell: (row) => <SignedAmount value={row.net} /> },
  ];
  const teacherColumns: DataTableColumn<TeacherRevenueRow>[] = [
    { key: "teacher", header: "Teacher", cell: (row) => teacherLabel(row.teacherId, row.teacherEmail) },
    { key: "courses", header: "Courses", cell: (row) => row.courseCount },
    { key: "payments", header: "Payments", cell: (row) => row.paymentCount },
    { key: "gross", header: "Gross", cell: (row) => formatMoney(row.gross) },
    { key: "refunds", header: "Refunds", cell: (row) => formatMoney(row.refunds) },
    { key: "net", header: "Net", cell: (row) => <SignedAmount value={row.net} /> },
  ];

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold text-foreground">Finance reports</h1>
        <p className="text-sm text-muted-foreground">
          Revenue from confirmed payments, less refunds, for the selected dates. Teacher revenue is
          grouped by each course&apos;s current teacher.
        </p>
      </div>

      <DateRangeFilter idPrefix="finance-reports" value={range} onApply={setRange} />

      <section aria-labelledby="course-revenue-heading" className="flex flex-col gap-3">
        <h2 id="course-revenue-heading" className="text-base font-semibold text-foreground">
          Course revenue
        </h2>
        <QueryStateBoundary
          query={courseQuery}
          loadingLabel="Loading course revenue…"
          loginPath="/login"
          permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
          isEmpty={(data) => data.rows.length === 0}
          emptyState={{
            title: "No course revenue in this period",
            description: "No payments or refunds were recorded for these dates. Try a wider range.",
          }}
        >
          {(data) => (
            <div className="flex flex-col gap-2">
              <DataTable
                columns={courseColumns}
                rows={data.rows}
                rowKey={(row) => row.courseId}
                caption={`Course revenue ${data.from} to ${data.to}`}
                cardHeading={(row) => row.courseTitle ?? `Course #${row.courseId.slice(0, 8)}`}
              />
              <p className="text-sm text-muted-foreground">
                Total net: <SignedAmount value={data.totals.net} currency={data.currency} /> (
                {data.from} to {data.to})
              </p>
            </div>
          )}
        </QueryStateBoundary>
      </section>

      <section aria-labelledby="teacher-revenue-heading" className="flex flex-col gap-3">
        <h2 id="teacher-revenue-heading" className="text-base font-semibold text-foreground">
          Teacher revenue
        </h2>
        <QueryStateBoundary
          query={teacherQuery}
          loadingLabel="Loading teacher revenue…"
          loginPath="/login"
          permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
          isEmpty={(data) => data.rows.length === 0}
          emptyState={{
            title: "No teacher revenue in this period",
            description: "No payments or refunds were recorded for these dates. Try a wider range.",
          }}
        >
          {(data) => (
            <DataTable
              columns={teacherColumns}
              rows={data.rows}
              rowKey={(row) => row.teacherId ?? "unassigned"}
              caption={`Teacher revenue ${data.from} to ${data.to}`}
              cardHeading={(row) => teacherLabel(row.teacherId, row.teacherEmail)}
            />
          )}
        </QueryStateBoundary>
      </section>
    </div>
  );
}
