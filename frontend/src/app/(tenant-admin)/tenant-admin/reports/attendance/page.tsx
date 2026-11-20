"use client";

import Link from "next/link";
import { ChevronLeft } from "lucide-react";
import { PageHeader } from "@/components/ui/page-header";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { useAttendanceReportOptions } from "@/lib/api/attendance-report";
import { AttendanceReportWorkspace } from "./attendance-report-workspace";

export default function AttendanceReportPage() {
  const optionsQuery = useAttendanceReportOptions();
  return (
    <div className="flex flex-col gap-6">
      <Link
        href="/tenant-admin/reports"
        className="inline-flex w-fit items-center gap-1 text-sm text-muted-foreground hover:text-foreground hover:underline"
      >
        <ChevronLeft className="size-4" aria-hidden="true" />
        All reports
      </Link>
      <PageHeader
        title="Attendance Report"
        description="Filter and generate student attendance reports by date range."
      />
      <QueryStateBoundary
        query={optionsQuery}
        loadingLabel="Loading attendance report options…"
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
        genericErrorMessage="Attendance report options could not be loaded."
        isEmpty={(options) => options.courses.length === 0}
        emptyState={{
          title: "No courses available",
          description: "Create or receive access to a course before generating an attendance report.",
        }}
      >
        {(options) => <AttendanceReportWorkspace options={options} />}
      </QueryStateBoundary>
    </div>
  );
}
