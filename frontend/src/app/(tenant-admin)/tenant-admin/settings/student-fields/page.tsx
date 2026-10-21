"use client";

import { useAuth } from "@/lib/auth/auth-context";
import { canManageInstituteConfig } from "@/lib/auth/permissions";
import { useStudentProfileFieldSettings } from "@/lib/api/student-profile-fields";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { StudentFieldSettingsForm } from "./student-field-settings-form";

/**
 * Tenant Admin student profile field settings (STU-PROF-01): which details
 * students are asked for, which are required, and which appear on the
 * public registration form. `GET/PUT /v1/tenant-config/student-profile-fields`
 * is enforced server-side (`BRANDING_SETTINGS` VIEW/CREATE_EDIT); this page
 * issues the real request and lets `QueryStateBoundary` render the
 * permission-denied state on a real 403. `canManageInstituteConfig` only
 * picks editable vs read-only (Read-only Auditor holds VIEW).
 *
 * The backend always returns the full catalogue, so there is no empty state.
 */
export default function StudentFieldSettingsPage() {
  const { session } = useAuth();
  const canManage = canManageInstituteConfig(session?.role ?? null);
  const query = useStudentProfileFieldSettings();

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold text-foreground">Student fields</h1>
        <p className="text-sm text-muted-foreground">
          Choose the details your institute collects from students. Registration stays short — students fill in
          the rest from their profile page.
        </p>
      </div>

      <QueryStateBoundary
        query={query}
        loadingLabel="Loading student field settings…"
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
      >
        {(settings) => <StudentFieldSettingsForm settings={settings} canManage={canManage} />}
      </QueryStateBoundary>
    </div>
  );
}
