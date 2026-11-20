"use client";

import { useAuth } from "@/lib/auth/auth-context";
import { canManageInstituteConfig } from "@/lib/auth/permissions";
import { useTenantConfigDomain } from "@/lib/api/tenant-config";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { SettingsHeader } from "@/components/settings/settings-tab-strip";
import { AnalyticsSection } from "./analytics-section";
import { OperationalOptionsSection } from "./operational-options-section";

/**
 * Settings → Configs (SET-CFG-01, Klass "Configs"). Reads GENERAL (GA4
 * measurement id), STUDENT (self-enrollment switch) and NOTIFICATION (SMS
 * switch). Every request is real and server-enforced (`BRANDING_SETTINGS`
 * VIEW to read, CREATE_EDIT to save); `canManageInstituteConfig` only decides
 * whether the controls render editable — a Read-only Auditor sees the values
 * with every control disabled.
 */
export default function ConfigsSettingsPage() {
  const { session } = useAuth();
  const canManage = canManageInstituteConfig(session?.role ?? null);
  const general = useTenantConfigDomain("GENERAL");
  const student = useTenantConfigDomain("STUDENT");
  const notification = useTenantConfigDomain("NOTIFICATION");

  return (
    <div className="flex flex-col gap-6">
      <SettingsHeader
        active="/tenant-admin/settings/configs"
        description="Google Analytics, student self-enrollment and SMS notifications."
      />

      <QueryStateBoundary
        query={general}
        loadingLabel="Loading configurations…"
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
      >
        {(generalProperties) => (
          <div className="flex flex-col gap-6">
            <AnalyticsSection properties={generalProperties} canManage={canManage} />
            <QueryStateBoundary query={student} loadingLabel="Loading operational options…" loginPath="/login">
              {(studentProperties) => (
                <QueryStateBoundary query={notification} loadingLabel="Loading operational options…" loginPath="/login">
                  {(notificationProperties) => (
                    <OperationalOptionsSection
                      studentProperties={studentProperties}
                      notificationProperties={notificationProperties}
                      canManage={canManage}
                    />
                  )}
                </QueryStateBoundary>
              )}
            </QueryStateBoundary>
          </div>
        )}
      </QueryStateBoundary>
    </div>
  );
}
