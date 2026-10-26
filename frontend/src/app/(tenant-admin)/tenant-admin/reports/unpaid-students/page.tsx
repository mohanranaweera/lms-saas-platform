"use client";

import Link from "next/link";
import { ChevronLeft } from "lucide-react";
import { PageHeader } from "@/components/ui/page-header";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { useUnpaidStudentsOptions } from "@/lib/api/unpaid-students-report";
import { UnpaidStudentsWorkspace } from "./unpaid-students-workspace";

export default function UnpaidStudentsReportPage() {
  const optionsQuery = useUnpaidStudentsOptions();
  return (
    <div className="flex flex-col gap-6">
      <Link href="/tenant-admin/reports" className="inline-flex w-fit items-center gap-1 text-sm text-muted-foreground hover:text-foreground hover:underline">
        <ChevronLeft className="size-4" aria-hidden="true" /> All reports
      </Link>
      <PageHeader title="Unpaid Students Report" description="Generate a list of unpaid students for a selected period or specific months." />
      <QueryStateBoundary
        query={optionsQuery}
        loadingLabel="Loading report options…"
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
        genericErrorMessage="The report options could not be loaded."
      >
        {(options) => <UnpaidStudentsWorkspace options={options} />}
      </QueryStateBoundary>
    </div>
  );
}
