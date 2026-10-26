"use client";

import Link from "next/link";
import { ChevronLeft } from "lucide-react";
import { PageHeader } from "@/components/ui/page-header";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { useStudentBehaviorOptions } from "@/lib/api/student-behavior-report";
import { StudentBehaviorWorkspace } from "./student-behavior-workspace";

export default function StudentBehaviorReportPage() {
  const options = useStudentBehaviorOptions();
  return <div className="flex flex-col gap-6"><Link href="/tenant-admin/reports" className="inline-flex w-fit items-center gap-1 text-sm text-muted-foreground hover:text-foreground hover:underline"><ChevronLeft className="size-4" aria-hidden="true" /> All reports</Link><PageHeader title="Student Behavior Report" description="Generate a report of new, dropped, and continuing students for a selected course and month." /><QueryStateBoundary query={options} loadingLabel="Loading report options…" loginPath="/login" permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }} genericErrorMessage="The report options could not be loaded.">{(data) => <StudentBehaviorWorkspace options={data} />}</QueryStateBoundary></div>;
}
