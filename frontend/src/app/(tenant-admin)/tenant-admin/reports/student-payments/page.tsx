"use client";

import Link from "next/link";
import { ChevronLeft } from "lucide-react";
import { PageHeader } from "@/components/ui/page-header";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { useStudentPaymentOptions } from "@/lib/api/student-payment-report";
import { StudentPaymentWorkspace } from "./student-payment-workspace";

/**
 * RPT-02 Student Payment Report (`docs/specs/student-payment-report.md`).
 * `GET /options` is gated server-side on `FINANCE_EXPENSES`/`VIEW`; a 403
 * renders the permission-denied state for the whole report. Courses are an
 * optional filter, so a tenant without courses still gets the workspace
 * (its search simply returns the empty result state).
 */
export default function StudentPaymentReportPage() {
  const optionsQuery = useStudentPaymentOptions();

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
        title="Student Payment Report"
        description="Find student payments made within a selected time frame or specific months."
      />
      <QueryStateBoundary
        query={optionsQuery}
        loadingLabel="Loading report options…"
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
        genericErrorMessage="The report options could not be loaded."
      >
        {(options) => <StudentPaymentWorkspace options={options} />}
      </QueryStateBoundary>
    </div>
  );
}
