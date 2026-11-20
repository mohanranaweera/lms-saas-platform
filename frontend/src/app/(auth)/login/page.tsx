"use client";

import { tenantDashboardPath } from "@/lib/auth/dashboard-paths";
import { Suspense } from "react";
import { LoginForm } from "@/components/auth/login-form";
import { LoadingState } from "@/components/states/loading-state";
import { usePublicHome } from "@/lib/api/public-home";
import { useT } from "@/i18n/i18n-provider";
import { useTenantDocumentTitle } from "@/lib/branding/use-tenant-head";


/**
 * Real, tenant-scoped login — wired to `POST /v1/auth/login`
 * (docs/api/identity-access-service.md). Tenant identity is resolved entirely
 * server-side from the request's Host header; this page never sends or
 * receives a `tenant_id`.
 */
export default function LoginPage() {
  // Same cached host-tenant read as the auth shell's logo (no extra request).
  const instituteName = usePublicHome().data?.instituteName?.trim();
  const t = useT();
  useTenantDocumentTitle(() =>
    instituteName ? t("auth.login.documentTitle", { name: instituteName }) : null
  );
  return (
    <Suspense fallback={<LoadingState label={t("auth.login.loading")} />}>
      <LoginForm
        kind="tenant"
        title={t("auth.login.title")}
        description={
          instituteName ? t("auth.login.descriptionNamed", { name: instituteName }) : t("auth.login.description")
        }
        resolveDashboardPath={tenantDashboardPath}
        showRegisterLink
      />
    </Suspense>
  );
}
