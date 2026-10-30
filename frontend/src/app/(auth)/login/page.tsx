"use client";

import { tenantDashboardPath } from "@/lib/auth/dashboard-paths";
import { Suspense } from "react";
import { LoginForm } from "@/components/auth/login-form";
import { LoadingState } from "@/components/states/loading-state";
import { usePublicHome } from "@/lib/api/public-home";


/**
 * Real, tenant-scoped login — wired to `POST /v1/auth/login`
 * (docs/api/identity-access-service.md). Tenant identity is resolved entirely
 * server-side from the request's Host header; this page never sends or
 * receives a `tenant_id`.
 */
export default function LoginPage() {
  // Same cached host-tenant read as the auth shell's logo (no extra request).
  const instituteName = usePublicHome().data?.instituteName?.trim();
  return (
    <Suspense fallback={<LoadingState label="Loading sign-in…" />}>
      <LoginForm
        kind="tenant"
        title="Sign in"
        description={instituteName ? `Access your ${instituteName} account.` : "Access your account."}
        resolveDashboardPath={tenantDashboardPath}
        showRegisterLink
      />
    </Suspense>
  );
}
