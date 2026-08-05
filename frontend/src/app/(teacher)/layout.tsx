import { DashboardShell } from "@/components/layout/dashboard-shell";
import { TeacherNav } from "@/components/layout/nav/teacher-nav";

export default function TeacherLayout({ children }: { children: React.ReactNode }) {
  return (
    <DashboardShell portalLabel="Teacher Portal" nav={<TeacherNav />}>
      {children}
    </DashboardShell>
  );
}
