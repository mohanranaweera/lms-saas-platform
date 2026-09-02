import { DashboardShell } from "@/components/layout/dashboard-shell";
import { TeacherNav } from "@/components/layout/nav/teacher-nav";
import { LogoutControl } from "@/components/auth/logout-control";
import { RouteGuard } from "@/components/auth/route-guard";

export default function TeacherLayout({ children }: { children: React.ReactNode }) {
  return (
    <RouteGuard kind="tenant" loginPath="/login">
      <DashboardShell
        portalLabel="Teacher Portal"
        nav={<TeacherNav />}
        headerActions={<LogoutControl kind="tenant" portalLabel="Teacher Portal" />}
      >
        {children}
      </DashboardShell>
    </RouteGuard>
  );
}
