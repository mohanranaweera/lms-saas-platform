"use client";

import { useEffect, useState } from "react";
import { Plus } from "lucide-react";
import { useAuth } from "@/lib/auth/auth-context";
import { canManageStaff } from "@/lib/auth/permissions";
import { useStaffList, type StaffResponse } from "@/lib/api/staff";
import { Button } from "@/components/ui/button";
import { DataTable, type DataTableColumn } from "@/components/ui/data-table";
import { StaffStatusBadge } from "@/components/staff/staff-status-badge";
import { QueryStateBoundary } from "@/components/states/query-state-boundary";
import { CreateStaffSheet } from "./create-staff-sheet";

/**
 * Staff Management list (MVP-005, `STAFF-1`, Tenant Admin/Read-only
 * Auditor). `GET /api/v1/staff` is enforced server-side
 * (`STAFF_AND_ROLES`/`VIEW`) — this page issues the real request
 * unconditionally and lets `QueryStateBoundary` render
 * `PermissionDeniedState` on an actual `403`; the nav entry
 * (`tenant-admin-nav.tsx`, gated on `canViewStaff`) is pure UX convenience
 * only, and "Add staff" is additionally gated on `canManageStaff` (Tenant
 * Admin only — Read-only Auditor sees the list but no create action).
 *
 * No server-side pagination/search/filter exists on this endpoint (see
 * `StaffController`), matching `students/page.tsx`'s equivalent note — this
 * list renders the full tenant-scoped result set as-is, no client-side
 * filtering added since this module's scope doesn't call for it.
 */
export default function StaffListPage() {
  const { session } = useAuth();
  const canManage = canManageStaff(session?.role ?? null);
  const staffQuery = useStaffList();

  const [sheetOpen, setSheetOpen] = useState(false);
  const [createdNotice, setCreatedNotice] = useState<string | null>(null);

  useEffect(() => {
    if (!createdNotice) return;
    const timeout = setTimeout(() => setCreatedNotice(null), 5000);
    return () => clearTimeout(timeout);
  }, [createdNotice]);

  const handleCreated = (staff: StaffResponse) => {
    setCreatedNotice(`${staff.name} was added.`);
  };

  const columns: DataTableColumn<StaffResponse>[] = [
    { key: "name", header: "Name", cell: (staff) => staff.name, hideOnCard: true },
    { key: "email", header: "Email", cell: (staff) => staff.email },
    { key: "roleCode", header: "Role", cell: (staff) => staff.roleCode },
    {
      key: "status",
      header: "Status",
      cell: (staff) => <StaffStatusBadge status={staff.status} />,
      hideOnCard: true,
    },
  ];

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
        <div>
          <h1 className="text-xl font-semibold text-foreground">Staff</h1>
          <p className="text-sm text-muted-foreground">
            Manage this institute&apos;s staff accounts and their assigned roles.
          </p>
        </div>
        {canManage ? (
          <Button
            type="button"
            aria-haspopup="dialog"
            aria-expanded={sheetOpen}
            onClick={() => setSheetOpen(true)}
          >
            <Plus aria-hidden="true" />
            Add staff
          </Button>
        ) : null}
      </div>

      <div role="status" aria-live="polite">
        {createdNotice ? <p className="text-sm text-muted-foreground">{createdNotice}</p> : null}
      </div>

      <QueryStateBoundary
        query={staffQuery}
        loadingLabel="Loading staff…"
        loginPath="/login"
        permissionDenied={{ dashboardHref: "/tenant-admin/dashboard" }}
        isEmpty={(staff) => staff.length === 0}
        emptyState={{
          title: "No staff accounts yet",
          description: canManage
            ? "This institute doesn't have any staff accounts yet. Add your first staff member to get started."
            : "This institute doesn't have any staff accounts yet.",
          action: canManage ? { label: "Add staff", onClick: () => setSheetOpen(true) } : undefined,
        }}
      >
        {(staff) => (
          <DataTable
            columns={columns}
            rows={staff}
            rowKey={(member) => member.id}
            caption="Staff"
            cardHeading={(member) => member.name}
            cardHeadingAdornment={(member) => <StaffStatusBadge status={member.status} />}
          />
        )}
      </QueryStateBoundary>

      {canManage ? (
        <CreateStaffSheet open={sheetOpen} onOpenChange={setSheetOpen} onCreated={handleCreated} />
      ) : null}
    </div>
  );
}
