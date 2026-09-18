"use client";

import { useAssignableRoles, type RoleSummary } from "@/lib/api/roles";
import { DataTable, type DataTableColumn } from "@/components/ui/data-table";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { Badge } from "@/components/ui/badge";

/**
 * Roles & Permissions (Wave 1, Administration group). Read-only listing of
 * the tenant-assignable role catalog (`GET /api/v1/roles`,
 * `STAFF_AND_ROLES`/`VIEW`) — the same grant that backs the Staff list, so
 * this shares `canViewStaff` for its nav gate rather than a separate helper.
 *
 * This is deliberately NOT a permission-matrix editor: RBAC grants are
 * code-fixed in `PermissionCheckServiceImpl`, not tenant-editable, so there
 * is no mutation control anywhere on this page (verified by construction —
 * no button/action renders here at all, not merely hidden/disabled) and no
 * mutation hook exists in `lib/api/roles.ts` to wire one to.
 */
export default function RolesAndPermissionsPage() {
  const query = useAssignableRoles();

  const columns: DataTableColumn<RoleSummary>[] = [
    { key: "displayName", header: "Role", cell: (role) => role.displayName, hideOnCard: true },
    {
      key: "description",
      header: "Description",
      cell: (role) => role.description ?? "—",
    },
    {
      key: "portalRouteGroup",
      header: "Portal",
      cell: (role) => role.portalRouteGroup,
    },
    {
      key: "selfRegisters",
      header: "Self-registers",
      cell: (role) => (role.selfRegisters ? "Yes" : "No"),
    },
    {
      key: "isProvisional",
      header: "Status",
      cell: (role) =>
        role.isProvisional ? (
          <Badge variant="outline">Provisional</Badge>
        ) : (
          <Badge variant="secondary">Active</Badge>
        ),
      hideOnCard: true,
    },
  ];

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold text-foreground">Roles &amp; permissions</h1>
        <p className="text-sm text-muted-foreground">
          The staff roles assignable within your institute. Permissions for each role are
          fixed by the platform and can&apos;t be edited here.
        </p>
      </div>

      <QueryStateBoundary
        query={query}
        loadingLabel="Loading roles…"
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
        isEmpty={(roles) => roles.length === 0}
        emptyState={{
          title: "No assignable roles configured",
          description:
            "Your institute currently has no tenant-assignable roles in the platform's role catalog. Contact platform support if this is unexpected.",
        }}
      >
        {(roles) => (
          <DataTable
            columns={columns}
            rows={roles}
            rowKey={(role) => role.code}
            caption="Roles and permissions"
            cardHeading={(role) => role.displayName}
            cardHeadingAdornment={(role) =>
              role.isProvisional ? <Badge variant="outline">Provisional</Badge> : null
            }
          />
        )}
      </QueryStateBoundary>
    </div>
  );
}
