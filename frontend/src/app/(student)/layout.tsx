import { DashboardShell } from "@/components/layout/dashboard-shell";
import { StudentNav } from "@/components/layout/nav/student-nav";
import { LogoutControl } from "@/components/auth/logout-control";
import { RouteGuard } from "@/components/auth/route-guard";

export default function StudentLayout({ children }: { children: React.ReactNode }) {
  return (
    <RouteGuard kind="tenant" loginPath="/login">
      <DashboardShell
        portalLabel="Student Portal"
        nav={<StudentNav />}
        headerActions={<LogoutControl kind="tenant" portalLabel="Student Portal" />}
      >
        {children}
      </DashboardShell>
    </RouteGuard>
  );
}
