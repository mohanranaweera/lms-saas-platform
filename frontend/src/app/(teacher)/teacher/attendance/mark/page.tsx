import { MarkAttendancePanel } from "@/components/attendance/mark-attendance-panel";

/**
 * Teacher Mark Attendance (MVP-016, plan §11 screen #1). Thin route wrapper —
 * all data/behavior lives in `MarkAttendancePanel`, shared verbatim with the
 * staff screen (`(tenant-admin)/tenant-admin/attendance/mark/page.tsx`); the
 * two differ only in role/route/nav, never in UI (see that component's doc
 * comment).
 */
export default function TeacherMarkAttendancePage() {
  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-xl font-semibold text-foreground">Mark Attendance</h1>
        <p className="text-sm text-muted-foreground">
          Select a course, module, and session to record attendance for your students.
        </p>
      </div>
      <MarkAttendancePanel dashboardHref="/teacher/dashboard" />
    </div>
  );
}
