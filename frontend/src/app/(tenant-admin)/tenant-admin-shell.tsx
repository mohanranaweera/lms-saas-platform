"use client";

import type { ReactNode } from "react";
import { DashboardShell } from "@/components/layout/dashboard-shell";
import { TenantAdminNav } from "@/components/layout/nav/tenant-admin-nav";
import { LogoutControl } from "@/components/auth/logout-control";
import { useNamespaces, useT } from "@/i18n/i18n-provider";

/**
 * Tenant Admin chrome (I18N-01): loads the `tenantAdmin` namespace for every
 * Tenant Admin route and localizes the portal label and navigation.
 * The pages inside are not localized yet.
 */
export function TenantAdminShell({ children }: { children: ReactNode }) {
  useNamespaces(["tenantAdmin"]);
  const t = useT();
  const portalLabel = t("tenantAdmin.portal");
  return (
    <DashboardShell
      portalLabel={portalLabel}
      nav={<TenantAdminNav />}
      headerActions={<LogoutControl kind="tenant" portalLabel={portalLabel} requireConfirmation />}
    >
      {children}
    </DashboardShell>
  );
}
