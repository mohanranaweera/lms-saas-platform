"use client";
import Link from "next/link";
import { ChevronLeft } from "lucide-react";
import { PageHeader } from "@/components/ui/page-header";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { useRegistrationOptions } from "@/lib/api/registration-report";
import { RegistrationWorkspace } from "./registration-workspace";
export default function RegistrationReportPage() {
  const query = useRegistrationOptions();
  return (
    <div className="flex flex-col gap-6">
      <Link
        href="/tenant-admin/reports"
        className="inline-flex w-fit items-center gap-1 text-sm text-muted-foreground hover:text-foreground hover:underline"
      >
        <ChevronLeft className="size-4" aria-hidden="true" /> All reports
      </Link>
      <PageHeader
        title="Registration Report"
        description="Generate a list of registrations for a selected date range."
      />
      <QueryStateBoundary
        query={query}
        loadingLabel="Loading registration report options…"
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
        genericErrorMessage="The registration report options could not be loaded."
      >
        {(options) => <RegistrationWorkspace options={options} />}
      </QueryStateBoundary>
    </div>
  );
}
