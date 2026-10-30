"use client";

import { useAuth } from "@/lib/auth/auth-context";
import { canManageInstituteConfig } from "@/lib/auth/permissions";
import { useTenantConfigDomain } from "@/lib/api/tenant-config";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { GeneralConfigForm } from "./general-config-form";
import { LoginOptionsSection } from "./login-options-section";
import { SettingsHeader } from "@/components/settings/settings-tab-strip";

/**
 * Tenant Admin (and Read-only Auditor, view-only) General settings
 * (Wave 1 — Tenant Admin IA + Configuration Framework). `GET
 * /api/v1/tenant-config/GENERAL` is enforced server-side
 * (`BRANDING_SETTINGS`/`VIEW`) — this page issues the real request
 * unconditionally and lets `QueryStateBoundary` render
 * `PermissionDeniedState` on an actual `403`; the nav entry
 * (`tenant-admin-nav.tsx`, gated on `canViewInstituteConfig`) is pure UX
 * convenience only.
 *
 * `GENERAL`'s registered keys are fixed by `ConfigPropertyRegistry` and
 * always all come back (values `null` until first set). This tab edits all of
 * them except `default_currency`, which is edited on Settings → Payments
 * (SET-PAY-01) — there is no genuine "zero data" case to build an
 * `EmptyState` for here, unlike a list screen, so no `isEmpty`/`emptyState`
 * prop is passed to `QueryStateBoundary`.
 *
 * Whether the form renders editable or read-only is decided by
 * `canManageInstituteConfig(role)` (`BRANDING_SETTINGS`/`CREATE_EDIT`,
 * Tenant Admin only) — Read-only Auditor still reaches this page (holds
 * `VIEW`) and sees every current value, just with no Save action and every
 * field disabled, per `GeneralConfigForm`'s own doc comment.
 */
export default function GeneralSettingsPage() {
  const { session } = useAuth();
  const canManage = canManageInstituteConfig(session?.role ?? null);
  const query = useTenantConfigDomain("GENERAL");

  return (
    <div className="flex flex-col gap-6">
      <SettingsHeader
        active="/tenant-admin/settings/general"
        description="Institute name, time zone, and the contact details and address shown on your public page."
      />

      <QueryStateBoundary
        query={query}
        loadingLabel="Loading general settings…"
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
      >
        {(properties) => (
          <div className="flex flex-col gap-6">
            <GeneralConfigForm properties={properties} canManage={canManage} />
            <LoginOptionsSection properties={properties} canManage={canManage} />
          </div>
        )}
      </QueryStateBoundary>
    </div>
  );
}
