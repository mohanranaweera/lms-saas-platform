"use client";

import { useAuth } from "@/lib/auth/auth-context";
import { canManageInstituteConfig } from "@/lib/auth/permissions";
import { useGeneralAndBrandingConfig } from "@/lib/api/tenant-config";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { BrandingConfigForm } from "./branding-config-form";

/**
 * Tenant Admin (and Read-only Auditor, view-only) Branding settings
 * (Wave 1). `GET /api/v1/tenant-config/BRANDING` (plus `GENERAL`, for the
 * live preview panel's institute name — see
 * `lib/api/tenant-config.ts#useGeneralAndBrandingConfig`) is enforced
 * server-side (`BRANDING_SETTINGS`/`VIEW`) — this page issues the real
 * requests unconditionally and lets `QueryStateBoundary` render
 * `PermissionDeniedState` on an actual `403`; the nav entry
 * (`tenant-admin-nav.tsx`, gated on `canViewInstituteConfig`) is pure UX
 * convenience only.
 *
 * `BRANDING`'s 4 registered keys are fixed by `ConfigPropertyRegistry` and
 * always come back as a 4-element array (values `null` until first set) —
 * no genuine "zero data" case, so no `isEmpty`/`emptyState` prop here either
 * (see `settings/general/page.tsx`'s identical reasoning).
 */
export default function BrandingSettingsPage() {
  const { session } = useAuth();
  const canManage = canManageInstituteConfig(session?.role ?? null);
  const query = useGeneralAndBrandingConfig();

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold text-foreground">Branding settings</h1>
        <p className="text-sm text-muted-foreground">
          Your institute&apos;s brand colors, logo, and favicon. Preview reflects unsaved
          changes.
        </p>
      </div>

      <QueryStateBoundary
        query={query}
        loadingLabel="Loading branding settings…"
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
      >
        {({ general, branding }) => {
          const instituteName = general.find((property) => property.key === "institute_name")?.value;
          return (
            <BrandingConfigForm
              properties={branding}
              canManage={canManage}
              instituteName={typeof instituteName === "string" ? instituteName : ""}
            />
          );
        }}
      </QueryStateBoundary>
    </div>
  );
}
