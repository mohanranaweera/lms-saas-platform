import { DashboardShell } from "@/components/layout/dashboard-shell";
import { TenantAdminNav } from "@/components/layout/nav/tenant-admin-nav";
import { LogoutControl } from "@/components/auth/logout-control";
import { RouteGuard } from "@/components/auth/route-guard";

/**
 * MVP-007 (Teacher Management) note: `RouteGuard` is wired around the
 * *entire* `(tenant-admin)` route group here, not scoped to just
 * `/tenant-admin/teachers`. This was necessary — Teacher Management is the
 * first page in this group to call `authorizedFetch`, which needs a session
 * established before it fires — and `RouteGuard` already existed unused for
 * exactly this purpose (see its own doc comment). Applying it at the route
 * group's layout, rather than only inside the teachers pages, is a
 * deliberate, portal-wide UX decision (avoids a flash of unauthenticated
 * dashboard/profile/settings content on a stale reload, consistent with how
 * this guard is meant to be used) — not an accidental scope expansion.
 * `(student)`, `(teacher)`, and `(platform-admin)` remain unguarded; wiring
 * `RouteGuard` into those is a separate decision for whoever first adds a
 * real authenticated data call to one of them, not implied by this change.
 */
export default function TenantAdminLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <RouteGuard kind="tenant" loginPath="/login">
      <DashboardShell
        portalLabel="Tenant Admin"
        nav={<TenantAdminNav />}
        headerActions={
          <LogoutControl kind="tenant" portalLabel="Tenant Admin" requireConfirmation />
        }
      >
        {children}
      </DashboardShell>
    </RouteGuard>
  );
}
