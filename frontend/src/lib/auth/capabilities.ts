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
  COURSES_VIEW_PAYMENT_STATUS: "COURSES_VIEW_PAYMENT_STATUS",
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
  MEDIA_MANAGE_FOLDERS: "MEDIA_MANAGE_FOLDERS",
  MEDIA_SHARE_FOLDERS: "MEDIA_SHARE_FOLDERS",
  NOTIFICATIONS_VIEW: "NOTIFICATIONS_VIEW",
  TIMETABLE_VIEW: "TIMETABLE_VIEW",
  TEACHERS_VIEW: "TEACHERS_VIEW",
  FINANCE_VIEW_EXPENSES: "FINANCE_VIEW_EXPENSES",
  FINANCE_VIEW_INCOME_SUMMARY: "FINANCE_VIEW_INCOME_SUMMARY",
  FINANCE_VIEW_CATEGORY: "FINANCE_VIEW_CATEGORY",
  FINANCE_DASHBOARD_ANNUAL_STATS: "FINANCE_DASHBOARD_ANNUAL_STATS",
  FINANCE_ADD_EXPENSE: "FINANCE_ADD_EXPENSE",
  FINANCE_VOID_EXPENSE: "FINANCE_VOID_EXPENSE",
  FINANCE_ACCOUNT_VIEW_CONFIGURATION: "FINANCE_ACCOUNT_VIEW_CONFIGURATION",
  TEACHERS_VIEW_WALLET: "TEACHERS_VIEW_WALLET",
  FINANCE_CALCULATE_SETTLEMENTS: "FINANCE_CALCULATE_SETTLEMENTS",
  FINANCE_MARK_SETTLEMENT_PAID: "FINANCE_MARK_SETTLEMENT_PAID",
  FINANCE_ADJUST_SETTLEMENT: "FINANCE_ADJUST_SETTLEMENT",
  FINANCE_EDIT_SHARE_RATES: "FINANCE_EDIT_SHARE_RATES",
  PAYMENTS_REVOKE_FREE_CARDS: "PAYMENTS_REVOKE_FREE_CARDS",
  PAYMENTS_LIST_UNDO: "PAYMENTS_LIST_UNDO",
  PAYMENTS_UPDATE_RULES: "PAYMENTS_UPDATE_RULES",
  COURSES_EDIT: "COURSES_EDIT",
  COURSES_ENROLL_STUDENTS: "COURSES_ENROLL_STUDENTS",
  COURSES_VIEW_ARCHIVED: "COURSES_VIEW_ARCHIVED",
  STUDENTS_VIEW_PENDING: "STUDENTS_VIEW_PENDING",
  STUDENTS_LOG_AS: "STUDENTS_LOG_AS",
  MODULES_QUESTIONS_VIEW: "MODULES_QUESTIONS_VIEW",
  MODULES_QUESTIONS_ADD: "MODULES_QUESTIONS_ADD",
  MODULES_QUESTIONS_EDIT: "MODULES_QUESTIONS_EDIT",
  MODULES_QUESTIONS_IMPORT: "MODULES_QUESTIONS_IMPORT",
  EXAMS_SCHEDULE: "EXAMS_SCHEDULE",
  EXAMS_EDIT: "EXAMS_EDIT",
  EXAMS_MANAGE: "EXAMS_MANAGE",
  EXAMS_VIEW_RESULTS: "EXAMS_VIEW_RESULTS",
  EXAMS_CREATE_LEADERBOARD: "EXAMS_CREATE_LEADERBOARD",
  EXAMS_GENERATE_REPORT: "EXAMS_GENERATE_REPORT",
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
 * they hold nothing by default, so they get the capability-driven dashboard
 * instead of the static-role widgets.
 */
export const CAPABILITY_ROLES: readonly string[] = ["OPERATOR", "MANAGER"];

/**
 * The seven seeded staff sub-roles (STAFF-MGMT-03 §6.1): their default access
 * is the static matrix, but a Tenant Admin can now grant or revoke catalogued
 * capabilities for them, so `GET /v1/me/permissions` (default + overrides) is
 * what describes them, not the static role helpers.
 */
export const SEEDED_CAPABILITY_ROLES: readonly string[] = [
  "FINANCE_STAFF",
  "COURSE_COORDINATOR",
  "STUDENT_SUPPORT",
  "CONTENT_MANAGER",
  "EXAM_MANAGER",
  "ATTENDANCE_OPERATOR",
  "READ_ONLY_AUDITOR",
];

/** OPERATOR / MANAGER only (nothing by default). */
export function isCapabilityRole(role: string | null): boolean {
  return role !== null && CAPABILITY_ROLES.includes(role);
}

/**
 * Every role whose access the tenant can configure: OPERATOR, MANAGER and the
 * seven seeded sub-roles. For these, `can(...)` answers from capabilities.
 * Tenant Admin, Teacher and Teacher Assistant keep their role helpers.
 */
export function isCapabilityDrivenRole(role: string | null): boolean {
  return role !== null && (CAPABILITY_ROLES.includes(role) || SEEDED_CAPABILITY_ROLES.includes(role));
}

/**
 * The single gate used across the tenant-admin portal. For capability-driven
 * roles (OPERATOR, MANAGER and the seven seeded sub-roles) the answer comes
 * from the caller's effective capabilities (`GET /v1/me/permissions`;
 * `capability` may list alternatives, any one suffices), so a capability a
 * Tenant Admin revoked hides its action even though the static role would
 * hold it. For every other role (Tenant Admin, Teacher) the existing
 * `legacyCheck` (a `permissions.ts` role helper) is returned untouched. While
 * capabilities are still loading, a capability-driven role sees nothing
 * extra (fails closed); if the request fails, a seeded sub-role falls back
 * to its static default (`legacyCheck`). Actions with no catalogued capability keep calling
 * the role helper directly, without `can`.
 */
export function can(
  capability: CapabilityKey | readonly CapabilityKey[],
  role: string | null,
  legacyCheck: boolean,
  capabilities: ReadonlySet<CapabilityKey> | null | undefined,
  options: { capabilitiesFailed?: boolean } = {}
): boolean {
  if (!isCapabilityDrivenRole(role)) return legacyCheck;
  if (!capabilities) {
    // A seeded sub-role's default access is its static matrix (what the
    // legacy helper models), so if `/v1/me/permissions` could not be loaded
    // it degrades to that default. OPERATOR / MANAGER hold nothing by default
    // and stay closed. Loading always fails closed.
    if (options.capabilitiesFailed && SEEDED_CAPABILITY_ROLES.includes(role as string)) return legacyCheck;
    return false;
  }
  const wanted = typeof capability === "string" ? [capability] : capability;
  return wanted.some((key) => capabilities.has(key));
}
