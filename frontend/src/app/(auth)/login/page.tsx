"use client";

import { Suspense } from "react";
import { LoginForm } from "@/components/auth/login-form";
import { LoadingState } from "@/components/states/loading-state";

const DASHBOARD_PATH_BY_ROLE: Record<string, string> = {
  STUDENT: "/student/dashboard",
  TEACHER: "/teacher/dashboard",
  TENANT_ADMIN: "/tenant-admin/dashboard",
  // The seven staff sub-roles named in .claude/rules/ui-ux.md §1 ("Staff
  // sub-roles") all operate inside the Tenant Admin portal, same as
  // TENANT_ADMIN itself — per-page permission checks (e.g. Student
  // Management's `canManageStudents()`, `QueryStateBoundary`'s 403 handling)
  // gate what each sub-role can actually see/do within it. There is no
  // separate staff dashboard route group, and none is needed.
  FINANCE_STAFF: "/tenant-admin/dashboard",
  COURSE_COORDINATOR: "/tenant-admin/dashboard",
  STUDENT_SUPPORT: "/tenant-admin/dashboard",
  CONTENT_MANAGER: "/tenant-admin/dashboard",
  EXAM_MANAGER: "/tenant-admin/dashboard",
  ATTENDANCE_OPERATOR: "/tenant-admin/dashboard",
  READ_ONLY_AUDITOR: "/tenant-admin/dashboard",
  // TEACHER_ASSISTANT intentionally omitted — its portal is an open,
  // unratified decision (docs/plans/MVP-006 Student Management.md §21 item 8;
  // user-roles-and-permissions.md §3), not something to guess here.
};

/**
 * Real, tenant-scoped login — wired to `POST /v1/auth/login`
 * (docs/api/identity-access-service.md). Tenant identity is resolved entirely
 * server-side from the request's Host header; this page never sends or
 * receives a `tenant_id`.
 */
export default function LoginPage() {
  return (
    <Suspense fallback={<LoadingState label="Loading sign-in…" />}>
      <LoginForm
        kind="tenant"
        title="Sign in"
        description="Access your LMS Platform account."
        resolveDashboardPath={(role) => (role ? (DASHBOARD_PATH_BY_ROLE[role] ?? null) : null)}
        showRegisterLink
      />
    </Suspense>
  );
}
