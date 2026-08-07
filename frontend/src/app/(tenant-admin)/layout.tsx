import { DashboardShell } from "@/components/layout/dashboard-shell";
import { TenantAdminNav } from "@/components/layout/nav/tenant-admin-nav";

export default function TenantAdminLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <DashboardShell
      portalLabel="Tenant Admin"
      nav={<TenantAdminNav />}
      logout={{ kind: "tenant", requireConfirmation: true }}
    >
      {children}
    </DashboardShell>
  );
}
