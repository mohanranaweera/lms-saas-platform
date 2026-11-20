"use client";

import { useAuth } from "@/lib/auth/auth-context";
import { canManageIntegrations } from "@/lib/auth/permissions";
import { useTenantIntegrations } from "@/lib/api/integrations";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import {
  BunnySection,
  DynTubeSection,
  TeacherConnectionsSection,
  TeacherProvidersSection,
  VideoHostSection,
} from "@/components/integrations/admin-integration-sections";
import { BunnyLibrariesSection } from "@/components/integrations/bunny-libraries-section";
import { SettingsHeader } from "@/components/settings/settings-tab-strip";

/**
 * Tenant Admin → Institute configuration → Integrations (MEDIA-INT-01 /
 * ADR-018): which services teachers may connect with their own accounts, the
 * institute's single DynTube and Bunny.net accounts, the active video host,
 * each teacher's own Bunny.net library (MEDIA-INT-01A), and oversight of
 * teachers' connections.
 *
 * API keys are write-only (never prefilled, never returned). Read-only roles
 * see status only; the backend enforces `INTEGRATIONS` on every call
 * regardless of what this page shows.
 */
export default function TenantIntegrationsPage() {
  const { session } = useAuth();
  const canManage = canManageIntegrations(session?.role ?? null);
  const query = useTenantIntegrations();

  return (
    <div className="flex flex-col gap-6">
      <SettingsHeader
        active="/tenant-admin/settings/integrations"
        description="Zoom, YouTube and Vimeo are connected by each teacher with their own account. DynTube and Bunny.net are one account for the whole institute."
      />

      <QueryStateBoundary
        query={query}
        loadingLabel="Loading integrations…"
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
      >
        {(data) => (
          <div className="flex flex-col gap-8">
            <div className="max-w-2xl">
              <TeacherProvidersSection
                key={JSON.stringify(data.teacherProviders)}
                providers={data.teacherProviders}
                canManage={canManage}
              />
            </div>

            <section aria-labelledby="video-hosting-heading" className="flex max-w-2xl flex-col gap-4">
              <div>
                <h2 id="video-hosting-heading" className="text-base font-medium text-foreground">
                  Institute video hosting
                </h2>
                <p className="text-sm text-muted-foreground">
                  One account each for the whole institute. Keys are checked with the provider before they are saved
                  and are never shown again.
                </p>
              </div>
              <DynTubeSection connection={data.dyntube} canManage={canManage} />
              <BunnySection key={data.bunny?.id ?? "none"} connection={data.bunny} canManage={canManage} />
              <VideoHostSection videoHost={data.videoHost} dyntube={data.dyntube} bunny={data.bunny} canManage={canManage} />
            </section>

            <BunnyLibrariesSection canManage={canManage} bunnyConnected={data.bunny?.status === "ACTIVE"} />

            <TeacherConnectionsSection rows={data.teacherConnections} canManage={canManage} />

            {!canManage ? (
              <p className="text-sm text-muted-foreground">Only a Tenant Admin can change these settings.</p>
            ) : null}
          </div>
        )}
      </QueryStateBoundary>
    </div>
  );
}
