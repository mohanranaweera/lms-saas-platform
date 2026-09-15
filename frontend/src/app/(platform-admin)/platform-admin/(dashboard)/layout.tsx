import { DashboardShell } from "@/components/layout/dashboard-shell";
import { PlatformAdminNav } from "@/components/layout/nav/platform-admin-nav";
import { LogoutControl } from "@/components/auth/logout-control";
import { RouteGuard } from "@/components/auth/route-guard";

/**
 * Dashboard chrome (sidebar/header/logout) for authenticated Platform Admin
 * routes only. Route-group folder structure:
 *
 *   app/(platform-admin)/platform-admin/(dashboard)/dashboard/page.tsx  -> /platform-admin/dashboard (this shell)
 *   app/(platform-admin)/platform-admin/login/page.tsx                  -> /platform-admin/login (own, chrome-less layout)
 *
 * `(platform-admin)` (outer) is a pure route-group label — it contributes
 * nothing to the URL, which is why the real `platform-admin` path segment is
 * repeated as an actual folder underneath it. `(dashboard)` (inner) is a
 * second, nested route group scoping this shell to only the dashboard
 * subtree, so `/platform-admin/login` — a sibling of `(dashboard)` under the
 * same real `platform-admin` segment — never receives it. See
 * `platform-admin/login/layout.tsx` for that route's own chrome.
 *
 * `RouteGuard kind="platform-admin"` is wired around this entire subtree
 * (MVP-020), mirroring `(tenant-admin)/layout.tsx`'s exact wiring — this
 * closes the gap that layout's own doc comment named ("`(platform-admin)`
 * remains unguarded; wiring `RouteGuard` into it is a separate decision for
 * whoever first adds a real authenticated data call there"): the Tenant
 * List/Approval Queue, Payments dashboard, and Platform Audit Log all call
 * `authorizedFetch("platform-admin", ...)` and need a session established
 * first. This is UX convenience only (avoids a flash of unauthenticated
 * dashboard content on a stale reload) — the backend independently rejects
 * every request regardless of whether this guard ran.
 */
export default function PlatformAdminDashboardLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <RouteGuard kind="platform-admin" loginPath="/platform-admin/login">
      <DashboardShell
        portalLabel="Platform Admin"
        nav={<PlatformAdminNav />}
        headerActions={
          <LogoutControl kind="platform-admin" portalLabel="Platform Admin" requireConfirmation />
        }
      >
        {children}
      </DashboardShell>
    </RouteGuard>
  );
}
