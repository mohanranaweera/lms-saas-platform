"use client";

import { BookOpen, GraduationCap, Receipt } from "lucide-react";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { StatCard } from "@/components/dashboard/stat-card";
import { useStudents } from "@/lib/api/students";
import { useLedgerDashboard } from "@/lib/api/ledger";
import { useTenantCourseCounts } from "@/lib/api/tenant-overview";
import { useAuth } from "@/lib/auth/auth-context";
import { canViewPaymentDashboard } from "@/lib/auth/permissions";

/**
 * Tenant Admin Overview (MVP-015 TADASH-1 — rebuild of the prior static
 * placeholder). Per plan §4.1/§11: three independent, already-tenant-scoped
 * reads (`GET /api/v1/students`, two `GET /api/v1/courses` variants combined
 * by `useTenantCourseCounts`, `GET /api/v1/ledger/dashboard?size=1`),
 * composed client-side into stat cards — pure display arithmetic, no new
 * endpoint, no business logic. Each card owns its own `QueryStateBoundary` so
 * one domain's loading/error/empty state never affects the other two (plan
 * §4.1 steps 4-5), unlike Teacher/Student dashboard's single-boundary
 * pattern. No tenant selector/switcher renders anywhere here (plan §4.1 step
 * 6, `.claude/rules/ui-ux.md` §1 "Tenant Admin" — regression-checked, never
 * built).
 */
export default function TenantAdminDashboardPage() {
  const { session } = useAuth();
  const role = session?.role ?? null;
  const canViewPayments = canViewPaymentDashboard(role);

  const studentsQuery = useStudents();
  const courseCountsQuery = useTenantCourseCounts();
  // `enabled: canViewPayments` (see `useLedgerDashboard`'s own doc comment):
  // 4 of the 8 in-scope Tenant Admin sub-roles (Course Coordinator, Content
  // Manager, Exam Manager, Attendance Operator) never hold `PAYMENTS_SLIPS`/
  // `VIEW`, so this request simply isn't fired for them rather than firing a
  // guaranteed 403 on every dashboard load. Purely a UX-visibility decision;
  // the backend's own enforcement is unchanged.
  const ledgerQuery = useLedgerDashboard({ page: 0, size: 1 }, { enabled: canViewPayments });

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold text-foreground">Overview</h1>
        <p className="text-sm text-muted-foreground">
          {canViewPayments
            ? "Your institute's students, courses, and payments at a glance."
            : "Your institute's students and courses at a glance."}
        </p>
      </div>

      <div
        className={`grid grid-cols-1 gap-4 sm:grid-cols-2 ${
          canViewPayments ? "lg:grid-cols-3" : "lg:grid-cols-2"
        }`}
      >
        <QueryStateBoundary
          query={studentsQuery}
          loadingLabel="Loading student count…"
          loginPath="/login"
          genericErrorMessage="Couldn't load student count. Please try again."
        >
          {(students) => (
            <StatCard
              label="Total Students"
              value={students.length}
              icon={<GraduationCap className="size-4" />}
              hint={students.length === 0 ? "No students enrolled yet" : undefined}
              action={
                students.length === 0
                  ? { label: "Add a student", href: "/tenant-admin/students" }
                  : undefined
              }
            />
          )}
        </QueryStateBoundary>

        <QueryStateBoundary
          query={courseCountsQuery}
          loadingLabel="Loading course counts…"
          loginPath="/login"
          genericErrorMessage="Couldn't load course counts. Please try again."
        >
          {(counts) => (
            <StatCard
              label="Total Courses"
              value={counts.total}
              icon={<BookOpen className="size-4" />}
              hint={
                counts.total === 0
                  ? "No courses created yet"
                  : `${counts.published} published, ${counts.draft} draft`
              }
              action={
                counts.total === 0
                  ? { label: "Create a course", href: "/tenant-admin/courses" }
                  : undefined
              }
            />
          )}
        </QueryStateBoundary>

        {canViewPayments ? (
          <QueryStateBoundary
            query={ledgerQuery}
            loadingLabel="Loading payments recorded…"
            loginPath="/login"
            genericErrorMessage="Couldn't load payment entries. Please try again."
          >
            {(page) => (
              <StatCard
                label="Payment Entries Recorded"
                value={page.totalElements}
                icon={<Receipt className="size-4" />}
                hint={
                  page.totalElements === 0
                    ? "No payment entries recorded yet. This is a count, not a currency amount."
                    : "Entries recorded in the ledger. This is a count, not a currency amount."
                }
                // Deliberately no `action` here: unlike the Students/Courses cards,
                // there is no Tenant-Admin-initiated "record a payment" action reachable
                // from this dashboard — payments flow through the separate order/payment
                // process, not an admin-initiated create action. Do not add one.
              />
            )}
          </QueryStateBoundary>
        ) : null}
      </div>
    </div>
  );
}
