"use client";

import { useGoogleOAuthApp } from "@/lib/api/platform-oauth-apps";
import { PageHeader } from "@/components/ui/page-header";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { GoogleOAuthAppCard } from "./google-oauth-app-card";

/**
 * Platform Admin → Integrations (VIDEO Phase 5c, ADR-018 Amendment 5): the
 * platform's own provider apps. Today: the Google app behind every
 * institute's YouTube connection.
 */
export default function PlatformIntegrationsPage() {
  const query = useGoogleOAuthApp();

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="Integrations"
        description="Platform-wide provider apps. These apply to every institute; institutes connect their own accounts through them."
      />
      <QueryStateBoundary
        query={query}
        loadingLabel="Loading integrations…"
        loginPath="/platform-admin/login"
        permissionDenied={{ dashboardHref: "/platform-admin/dashboard" }}
      >
        {(view) => (
          <div className="max-w-2xl">
            <GoogleOAuthAppCard view={view} />
          </div>
        )}
      </QueryStateBoundary>
    </div>
  );
}
