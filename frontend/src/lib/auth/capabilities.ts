/**
 * STAFF-MGMT-02 capability helpers (UX only). The backend's capability
 * catalog (`identityaccessservice.api.Capability`) and its
 * `@RequiresCapability` interceptor are the only enforcement point; what this
 * file returns only decides what to render. Every gated action still handles
 * a real 403 from the server.
 *
 * Capability keys mirror `Capability.key()` verbatim. Treated as plain strings
 * so a new server-side key never breaks the type check; the constants below
 * are the ones this UI actually reads.
 */
export type CapabilityKey = string;

export const CAP = {
  STAFF_VIEW: "STAFF_VIEW",
  STAFF_MANAGE: "STAFF_MANAGE",
  STAFF_ASSIGN_ROLES: "STAFF_ASSIGN_ROLES",
  STAFF_PERMISSIONS_EDIT: "STAFF_PERMISSIONS_EDIT",
  STAFF_ACTIVITY_VIEW: "STAFF_ACTIVITY_VIEW",
  COURSES_VIEW: "COURSES_VIEW",
  COURSES_ADD: "COURSES_ADD",
  STUDENTS_VIEW: "STUDENTS_VIEW",
  STUDENTS_ADD: "STUDENTS_ADD",
  STUDENTS_EDIT: "STUDENTS_EDIT",
  STUDENTS_DELETE: "STUDENTS_DELETE",
  STUDENTS_SEND_NOTIFICATION: "STUDENTS_SEND_NOTIFICATION",
  STUDENTS_APPROVE_PENDING: "STUDENTS_APPROVE_PENDING",
  STUDENTS_EXPORT_CSV: "STUDENTS_EXPORT_CSV",
  STUDENTS_EXPORT_QR: "STUDENTS_EXPORT_QR",
  STUDENTS_RESET_PASSWORD: "STUDENTS_RESET_PASSWORD",
  PAYMENTS_VIEW: "PAYMENTS_VIEW",
  PAYMENTS_ADD: "PAYMENTS_ADD",
  PAYMENTS_ISSUE_FREE_CARDS: "PAYMENTS_ISSUE_FREE_CARDS",
  PAYMENTS_APPROVE_PENDING: "PAYMENTS_APPROVE_PENDING",
  PAYMENTS_UNDO: "PAYMENTS_UNDO",
  EXAMS_VIEW: "EXAMS_VIEW",
  ATTENDANCE_VIEW: "ATTENDANCE_VIEW",
  ATTENDANCE_ADD: "ATTENDANCE_ADD",
  MEDIA_VIEW: "MEDIA_VIEW",
  NOTIFICATIONS_VIEW: "NOTIFICATIONS_VIEW",
  TIMETABLE_VIEW: "TIMETABLE_VIEW",
  TEACHERS_VIEW: "TEACHERS_VIEW",
  FINANCE_VIEW_EXPENSES: "FINANCE_VIEW_EXPENSES",
  FINANCE_VIEW_INCOME_SUMMARY: "FINANCE_VIEW_INCOME_SUMMARY",
} as const;

/** Every `REPORTS_*` capability; holding any one shows the Reports entry. */
export const REPORT_CAPABILITIES: readonly CapabilityKey[] = [
  "REPORTS_TEACHER_INCOME",
  "REPORTS_STUDENT_PAYMENTS",
  "REPORTS_COURSE_SUMMARY",
  "REPORTS_ATTENDANCE",
  "REPORTS_UNPAID_STUDENTS",
  "REPORTS_REGISTRATIONS",
  "REPORTS_STUDENT_BEHAVIOR",
  "REPORTS_UNDO_PAYMENTS",
];

/**
 * Roles whose access is entirely tenant-configured (STAFF-MGMT-02 §2.1):
 * they hold nothing by default, so the static role helpers in
 * `permissions.ts` don't describe them. Every other role keeps its existing
 * role-based helper unchanged.
 */
export const CAPABILITY_ROLES: readonly string[] = ["OPERATOR", "MANAGER"];

export function isCapabilityRole(role: string | null): boolean {
  return role !== null && CAPABILITY_ROLES.includes(role);
}

/**
 * The single gate used across the tenant-admin portal: for OPERATOR /
 * MANAGER the answer comes from the caller's effective capabilities
 * (`GET /v1/me/permissions`; `capability` may list alternatives, any one
 * suffices); for every other role the existing `legacyCheck` (a
 * `permissions.ts` role helper) is returned untouched. While capabilities
 * are still loading, a capability role sees nothing extra (fails closed).
 */
export function can(
  capability: CapabilityKey | readonly CapabilityKey[],
  role: string | null,
  legacyCheck: boolean,
  capabilities: ReadonlySet<CapabilityKey> | null | undefined
): boolean {
  if (!isCapabilityRole(role)) return legacyCheck;
  if (!capabilities) return false;
  const wanted = typeof capability === "string" ? [capability] : capability;
  return wanted.some((key) => capabilities.has(key));
}
