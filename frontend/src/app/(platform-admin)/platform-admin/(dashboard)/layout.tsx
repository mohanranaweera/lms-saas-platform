import { DashboardShell } from "@/components/layout/dashboard-shell";
import { PlatformAdminNav } from "@/components/layout/nav/platform-admin-nav";
import { LogoutControl } from "@/components/auth/logout-control";

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
 */
export default function PlatformAdminDashboardLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <DashboardShell
      portalLabel="Platform Admin"
      nav={<PlatformAdminNav />}
      headerActions={
        <LogoutControl kind="platform-admin" portalLabel="Platform Admin" requireConfirmation />
      }
    >
      {children}
    </DashboardShell>
  );
}
