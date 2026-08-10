import { DashboardShell } from "@/components/layout/dashboard-shell";
import { StudentNav } from "@/components/layout/nav/student-nav";
import { LogoutControl } from "@/components/auth/logout-control";

export default function StudentLayout({ children }: { children: React.ReactNode }) {
  return (
    <DashboardShell
      portalLabel="Student Portal"
      nav={<StudentNav />}
      headerActions={<LogoutControl kind="tenant" portalLabel="Student Portal" />}
    >
      {children}
    </DashboardShell>
  );
}
