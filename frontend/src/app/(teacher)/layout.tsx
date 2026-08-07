import { DashboardShell } from "@/components/layout/dashboard-shell";
import { TeacherNav } from "@/components/layout/nav/teacher-nav";
import { LogoutControl } from "@/components/auth/logout-control";

export default function TeacherLayout({ children }: { children: React.ReactNode }) {
  return (
    <DashboardShell
      portalLabel="Teacher Portal"
      nav={<TeacherNav />}
      headerActions={<LogoutControl kind="tenant" portalLabel="Teacher Portal" />}
    >
      {children}
    </DashboardShell>
  );
}
