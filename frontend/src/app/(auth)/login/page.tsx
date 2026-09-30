"use client";

import { tenantDashboardPath } from "@/lib/auth/dashboard-paths";
import { Suspense } from "react";
import { LoginForm } from "@/components/auth/login-form";
import { LoadingState } from "@/components/states/loading-state";


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
        resolveDashboardPath={tenantDashboardPath}
        showRegisterLink
      />
    </Suspense>
  );
}
