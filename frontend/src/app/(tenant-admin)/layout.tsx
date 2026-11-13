import { RouteGuard } from "@/components/auth/route-guard";
import { TenantAdminShell } from "./tenant-admin-shell";

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
 * `(student)` and `(teacher)` have since gained the same `RouteGuard
 * kind="tenant"` wiring around their own route-group layouts (see
 * `(student)/layout.tsx` and `(teacher)/layout.tsx`, the latter added in
 * MVP-014), so all three tenant-context portals are now guarded the same
 * way. `(platform-admin)` remains unguarded; wiring `RouteGuard` into it is
 * a separate decision for whoever first adds a real authenticated data call
 * there, not implied by this change.
 */
export default function TenantAdminLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <RouteGuard kind="tenant" loginPath="/login">
      <TenantAdminShell>{children}</TenantAdminShell>
    </RouteGuard>
  );
}
