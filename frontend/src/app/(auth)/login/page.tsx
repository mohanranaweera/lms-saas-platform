"use client";

import { Suspense } from "react";
import { LoginForm } from "@/components/auth/login-form";
import { LoadingState } from "@/components/states/loading-state";

const DASHBOARD_PATH_BY_ROLE: Record<string, string> = {
  STUDENT: "/student/dashboard",
  TEACHER: "/teacher/dashboard",
  TENANT_ADMIN: "/tenant-admin/dashboard",
  // STAFF intentionally omitted — no staff dashboard route group exists yet in
  // this codebase (staff sub-role data model is Module 3/RBAC). A STAFF login
  // succeeds but resolves to no known target; `LoginForm` surfaces that instead
  // of guessing a route. See final report for this flagged gap.
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
