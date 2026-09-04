import { MarkAttendancePanel } from "@/components/attendance/mark-attendance-panel";

/**
 * Staff Mark Attendance (MVP-016, plan §11 screen #5) — Tenant Admin /
 * Attendance Operator. Same UI as the Teacher screen (`MarkAttendancePanel`),
 * differing only in the tenant-wide (vs. own-courses) scope `useCourses()`
 * already resolves server-side for this caller's role.
 *
 * No extra client-side role gate is added here: `MarkAttendancePanel`'s
 * `QueryStateBoundary` already renders `PermissionDeniedState` on a real
 * backend 403 for a Read-only Auditor (or any other unauthorized staff role)
 * that navigates directly to this route — the nav entry itself
 * (`tenant-admin-nav.tsx`, gated on `canMarkAttendanceStaff`) is pure UX
 * convenience, never the enforcement mechanism.
 */
export default function TenantAdminMarkAttendancePage() {
  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold text-foreground">Mark Attendance</h1>
        <p className="text-sm text-muted-foreground">
          Select a course, module, and session to record attendance across your tenant.
        </p>
      </div>
      <MarkAttendancePanel dashboardHref="/tenant-admin/dashboard" />
    </div>
  );
}
