import { DashboardShell } from "@/components/layout/dashboard-shell";
import { StudentNav } from "@/components/layout/nav/student-nav";

export default function StudentLayout({ children }: { children: React.ReactNode }) {
  return (
    <DashboardShell portalLabel="Student Portal" nav={<StudentNav />}>
      {children}
    </DashboardShell>
  );
}
