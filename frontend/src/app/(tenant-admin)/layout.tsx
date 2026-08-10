import { DashboardShell } from "@/components/layout/dashboard-shell";
import { TenantAdminNav } from "@/components/layout/nav/tenant-admin-nav";
import { LogoutControl } from "@/components/auth/logout-control";

export default function TenantAdminLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <DashboardShell
      portalLabel="Tenant Admin"
      nav={<TenantAdminNav />}
      headerActions={
        <LogoutControl kind="tenant" portalLabel="Tenant Admin" requireConfirmation />
      }
    >
      {children}
    </DashboardShell>
  );
}
