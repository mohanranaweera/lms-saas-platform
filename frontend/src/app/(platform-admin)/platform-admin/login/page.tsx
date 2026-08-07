"use client";

import { Suspense } from "react";
import { LoginForm } from "@/components/auth/login-form";
import { LoadingState } from "@/components/states/loading-state";

/**
 * Platform Admin login — structurally separate from tenant login (calls
 * `/v1/platform-admin/auth/login`, never resolves/requires a tenant, per
 * docs/api/identity-access-service.md). No tenant branding and no register
 * link: there is no self-serve Platform Admin registration.
 */
export default function PlatformAdminLoginPage() {
  return (
    <Suspense fallback={<LoadingState label="Loading sign-in…" />}>
      <LoginForm
        kind="platform-admin"
        title="Platform Admin sign in"
        description="Cross-tenant administration access."
        resolveDashboardPath={() => "/platform-admin/dashboard"}
      />
    </Suspense>
  );
}
