"use client";

import Link from "next/link";
import { ArrowRight } from "lucide-react";
import { LoadingState } from "@/components/states/loading-state";
import { ErrorState } from "@/components/states/error-state";
import { EmptyState } from "@/components/states/empty-state";
import { useCapabilities } from "@/lib/api/my-permissions";
import { CAP, REPORT_CAPABILITIES, type CapabilityKey } from "@/lib/auth/capabilities";
import { formatRoleLabel } from "@/lib/format";

const SHORTCUTS: Array<{ label: string; href: string; capability: CapabilityKey | readonly CapabilityKey[] }> = [
  { label: "Students", href: "/tenant-admin/students", capability: CAP.STUDENTS_VIEW },
  { label: "Courses", href: "/tenant-admin/courses", capability: CAP.COURSES_VIEW },
  { label: "Teachers", href: "/tenant-admin/teachers", capability: CAP.TEACHERS_VIEW },
  { label: "Calendar", href: "/tenant-admin/calendar", capability: CAP.TIMETABLE_VIEW },
  { label: "Payments", href: "/tenant-admin/payments", capability: CAP.PAYMENTS_VIEW },
  { label: "Exams", href: "/tenant-admin/exams", capability: CAP.EXAMS_VIEW },
  { label: "Attendance", href: "/tenant-admin/attendance/reports", capability: CAP.ATTENDANCE_VIEW },
  { label: "Media Library", href: "/tenant-admin/media", capability: CAP.MEDIA_VIEW },
  { label: "Communications", href: "/tenant-admin/communications", capability: CAP.NOTIFICATIONS_VIEW },
  {
    label: "Finance",
    href: "/tenant-admin/finance",
    capability: [CAP.FINANCE_VIEW_EXPENSES, CAP.FINANCE_VIEW_INCOME_SUMMARY],
  },
  { label: "Reports", href: "/tenant-admin/reports", capability: REPORT_CAPABILITIES },
];

/**
 * STAFF-MGMT-02 dashboard for OPERATOR / MANAGER: a welcome plus shortcuts
 * to the areas their effective capabilities allow. No data widgets (each
 * would need its own grant); every destination re-checks server-side.
 */
export function CapabilityRoleDashboard() {
  const { role, query, can } = useCapabilities();

  if (query.status === "pending") return <LoadingState label="Loading your workspace…" />;
  if (query.status === "error") {
    return (
      <ErrorState
        message="Your permissions couldn't be loaded. Please try again."
        onRetry={() => void query.refetch()}
      />
    );
  }

  const allowed = SHORTCUTS.filter((shortcut) => can(shortcut.capability, false));

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold text-foreground">Welcome</h1>
        <p className="text-sm text-muted-foreground">
          You&apos;re signed in as {role ? formatRoleLabel(role) : "staff"}. Your institute&apos;s administrator
          decides what this role can access.
        </p>
      </div>
      {allowed.length === 0 ? (
        <EmptyState
          title="No areas available yet"
          description="Your role doesn't have any permissions yet. Ask a Tenant Admin to grant access under Staff → Permissions."
        />
      ) : (
        <nav aria-label="Shortcuts">
          <ul className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {allowed.map((shortcut) => (
              <li key={shortcut.href}>
                <Link
                  href={shortcut.href}
                  className="flex items-center justify-between rounded-xl border border-border bg-card p-4 font-medium text-foreground outline-none hover:bg-muted/40 focus-visible:ring-3 focus-visible:ring-ring/50"
                >
                  {shortcut.label}
                  <ArrowRight className="size-4 text-muted-foreground" aria-hidden="true" />
                </Link>
              </li>
            ))}
          </ul>
        </nav>
      )}
    </div>
  );
}
