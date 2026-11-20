"use client";

import Link from "next/link";
import { ChevronLeft } from "lucide-react";
import { PageHeader } from "@/components/ui/page-header";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { useCourseSummaryOptions } from "@/lib/api/course-summary-report";
import { CourseSummaryWorkspace } from "./course-summary-workspace";

export default function CourseSummaryReportPage() {
  const options = useCourseSummaryOptions();
  return <div className="flex flex-col gap-6">
    <Link href="/tenant-admin/reports" className="inline-flex w-fit items-center gap-1 text-sm text-muted-foreground hover:text-foreground hover:underline"><ChevronLeft className="size-4" aria-hidden="true" />All reports</Link>
    <PageHeader title="Course Summary Report" description="Select a teacher and course to generate summary insights." />
    <QueryStateBoundary query={options} loadingLabel="Loading Course Summary options…" loginPath="/login" permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }} genericErrorMessage="Course Summary options could not be loaded." isEmpty={(data) => data.teachers.length === 0} emptyState={{ title: "No teachers available", description: "Add an authorized teacher before generating a course summary." }}>
      {(data) => <CourseSummaryWorkspace options={data} />}
    </QueryStateBoundary>
  </div>;
}
