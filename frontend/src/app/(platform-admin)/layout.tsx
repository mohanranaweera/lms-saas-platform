import { DashboardShell } from "@/components/layout/dashboard-shell";
import { PlatformAdminNav } from "@/components/layout/nav/platform-admin-nav";

export default function PlatformAdminLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <DashboardShell portalLabel="Platform Admin" nav={<PlatformAdminNav />}>
      {children}
    </DashboardShell>
  );
}
