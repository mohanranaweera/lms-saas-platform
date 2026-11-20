"use client";

import { Suspense } from "react";
import { LoginForm } from "@/components/auth/login-form";
import { LoadingState } from "@/components/states/loading-state";

import { useT } from "@/i18n/i18n-provider";
/**
 * Platform Admin login — structurally separate from tenant login (calls
 * `/v1/platform-admin/auth/login`, never resolves/requires a tenant, per
 * docs/api/identity-access-service.md). No tenant branding and no register
 * link: there is no self-serve Platform Admin registration.
 */
export default function PlatformAdminLoginPage() {
  const t = useT();
  return (
    <Suspense fallback={<LoadingState label={t("auth.platformLogin.loadingSignIn")} />}>
      <LoginForm
        kind="platform-admin"
        title={t("auth.platformLogin.platformAdminSignIn")}
        description={t("auth.platformLogin.crossTenantAdministrationAccess")}
        resolveDashboardPath={() => "/platform-admin/dashboard"}
      />
    </Suspense>
  );
}
