"use client";

import type { ReactNode } from "react";
import { BrandedDashboardShell } from "@/components/branding/portal-branding";
import { TeacherNav } from "@/components/layout/nav/teacher-nav";
import { LogoutControl } from "@/components/auth/logout-control";
import { ReconnectBanner } from "@/components/integrations/reconnect-banner";
import { useNamespaces, useT } from "@/i18n/i18n-provider";

/**
 * Teacher Portal chrome (I18N-01 pilot): loads the `teacher` message
 * namespace for every Teacher Portal route and localizes the portal label.
 */
export function TeacherPortalShell({ children }: { children: ReactNode }) {
  useNamespaces(["teacher"]);
  const t = useT();
  const portalLabel = t("common.portal.teacher");
  return (
    <BrandedDashboardShell
      portalLabel={portalLabel}
      nav={<TeacherNav />}
      headerActions={<LogoutControl kind="tenant" portalLabel={portalLabel} />}
    >
      <ReconnectBanner />
      {children}
    </BrandedDashboardShell>
  );
}
