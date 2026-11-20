"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { ChevronLeft } from "lucide-react";
import { PageHeader } from "@/components/ui/page-header";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { useTeacherIncomeOptions } from "@/lib/api/reports";
import { useAuth } from "@/lib/auth/auth-context";
import { canViewTeachers } from "@/lib/auth/permissions";
import { TeacherIncomeWorkspace } from "./teacher-income-workspace";

/**
 * RPT-01 Teacher Income Summary (`docs/specs/teacher-income-summary-report.md`).
 * `GET /options` is gated server-side on `FINANCE_EXPENSES`/`VIEW`; a 403
 * renders the permission-denied state for the whole report.
 */
export default function TeacherIncomeReportPage() {
  const optionsQuery = useTeacherIncomeOptions();
  const router = useRouter();
  const { session } = useAuth();
  // UX only: offer the Teachers link when the role can open that page (it re-checks server-side).
  const canOpenTeachers = canViewTeachers(session?.role ?? null);

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
        title="Teacher Income Summary Report"
        description="View earnings of teachers for a selected period or specific months."
      />
      <QueryStateBoundary
        query={optionsQuery}
        loadingLabel="Loading report options…"
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
        isEmpty={(options) => options.teachers.length === 0}
        emptyState={{
          title: "No teachers yet",
          description: canOpenTeachers
            ? "This report shows teacher earnings. Add teachers and assign them to courses, then come back to run it."
            : "This report shows teacher earnings. Ask a Tenant Admin to add teachers and assign them to courses, then come back to run it.",
          action: canOpenTeachers
            ? { label: "Go to Teachers", onClick: () => router.push("/tenant-admin/teachers") }
            : undefined,
        }}
        genericErrorMessage="The report options could not be loaded."
      >
        {(options) => <TeacherIncomeWorkspace options={options} />}
      </QueryStateBoundary>
    </div>
  );
}
