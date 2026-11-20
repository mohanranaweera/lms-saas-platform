import { RouteGuard } from "@/components/auth/route-guard";
import { TeacherPortalShell } from "./teacher-portal-shell";

export default function TeacherLayout({ children }: { children: React.ReactNode }) {
  return (
    <RouteGuard kind="tenant" loginPath="/login">
      <TeacherPortalShell>{children}</TeacherPortalShell>
    </RouteGuard>
  );
}
