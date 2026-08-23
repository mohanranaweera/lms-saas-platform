/**
 * Client-side role checks used ONLY to decide what to render (e.g. hide the
 * "Add student" button, render a student's profile read-only) — never an
 * authorization decision. Every mutation these checks gate still
 * independently fails server-side (`PermissionCheckService`,
 * `@PreAuthorize`) for a role that shouldn't be able to perform it,
 * regardless of what this file returns; callers must still handle a 403
 * from the mutation itself. See `.claude/rules/frontend.md`'s "permission-
 * denied state must be driven only by a server-verified signal" rule.
 */

/**
 * Roles with `STUDENTS`/`CREATE_EDIT` per `PermissionCheckServiceImpl`'s
 * matrix (Tenant Admin, Student Support) — the only roles that can create or
 * edit a student account.
 */
export function canManageStudents(role: string | null): boolean {
  return role === "TENANT_ADMIN" || role === "STUDENT_SUPPORT";
}

/**
 * Roles holding `PAYMENTS_SLIPS`/`APPROVE` per `PermissionCheckServiceImpl`'s
 * matrix — the only roles that may submit a refund
 * (`POST /api/v1/payments/{id}/refunds`). Finance Staff and Tenant Admin
 * both hold `A`; Student Support and Read-only Auditor hold `VIEW` only and
 * must never see this action rendered.
 */
export function canProcessRefunds(role: string | null): boolean {
  return role === "TENANT_ADMIN" || role === "FINANCE_STAFF";
}

/**
 * Roles holding `PAYMENTS_SLIPS`/`VIEW` per `PermissionCheckServiceImpl`'s
 * matrix — who may view the tenant Payment Dashboard
 * (`GET /api/v1/ledger/dashboard`). A superset of `canProcessRefunds`.
 */
export function canViewPaymentDashboard(role: string | null): boolean {
  return (
    role === "TENANT_ADMIN" ||
    role === "FINANCE_STAFF" ||
    role === "STUDENT_SUPPORT" ||
    role === "READ_ONLY_AUDITOR"
  );
}
