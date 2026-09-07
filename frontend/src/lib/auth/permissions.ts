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
 *
 * Also reused (deliberately — see its own call sites) to gate the Manual
 * Slip Review Queue's nav entry: the backend enforces the identical
 * `PAYMENTS_SLIPS`/`VIEW` grant on `GET /api/v1/payment-slips/review-queue`,
 * so no separate "can view slip queue" helper exists.
 */
export function canViewPaymentDashboard(role: string | null): boolean {
  return (
    role === "TENANT_ADMIN" ||
    role === "FINANCE_STAFF" ||
    role === "STUDENT_SUPPORT" ||
    role === "READ_ONLY_AUDITOR"
  );
}

/**
 * Roles holding `PAYMENTS_SLIPS`/`APPROVE` per `PermissionCheckServiceImpl`'s
 * matrix — the only roles that may approve/reject a manual payment slip
 * (`POST /api/v1/payment-slips/{id}/approve|reject`). Happens to be the
 * exact same role set as `canProcessRefunds` today, but is kept as its own
 * named export: "can approve a slip" and "can refund a payment" are
 * conceptually distinct capabilities that only coincidentally share a role
 * set right now, and a future RBAC change could split them without this
 * helper's name becoming misleading.
 */
export function canReviewSlips(role: string | null): boolean {
  return role === "TENANT_ADMIN" || role === "FINANCE_STAFF";
}

/**
 * Roles holding `ACCESS_EXPIRY`/`VIEW` per `PermissionCheckServiceImpl`'s
 * matrix (Tenant Admin, Finance Staff, Student Support, Read-only Auditor) —
 * who may view the Reactivation Approvals queue
 * (`GET /api/v1/reactivation-requests`). A superset of
 * `canApproveReactivation`, mirroring `canViewPaymentDashboard`'s exact role
 * set/shape for the payment-slip queue.
 */
export function canViewAccessExpiryQueue(role: string | null): boolean {
  return (
    role === "TENANT_ADMIN" ||
    role === "FINANCE_STAFF" ||
    role === "STUDENT_SUPPORT" ||
    role === "READ_ONLY_AUDITOR"
  );
}

/**
 * Roles holding `ACCESS_EXPIRY`/`APPROVE` per `PermissionCheckServiceImpl`'s
 * matrix — Tenant Admin is the ONLY role granted `APPROVE` for this domain
 * area (unlike `canReviewSlips`'s two-role `PAYMENTS_SLIPS`/`APPROVE` set),
 * so this is the only role that may approve/reject a reactivation request
 * (`POST /api/v1/reactivation-requests/{id}/approve|reject`).
 */
export function canApproveReactivation(role: string | null): boolean {
  return role === "TENANT_ADMIN";
}

/**
 * Roles holding `TEACHERS`/`VIEW` per `PermissionCheckServiceImpl`'s matrix
 * (Tenant Admin, Course Coordinator, Student Support, Read-only Auditor) —
 * gates only the "Teachers" nav item's visibility
 * (`components/layout/nav/tenant-admin-nav.tsx`). `TeacherController`'s own
 * `@PreAuthorize("@permissionCheckService.hasPermission('TEACHERS', 'VIEW')")`
 * remains the sole enforcement — a role without this grant that navigates
 * directly to `/tenant-admin/teachers` still gets a real 403, unchanged.
 */
export function canViewTeachers(role: string | null): boolean {
  return (
    role === "TENANT_ADMIN" ||
    role === "COURSE_COORDINATOR" ||
    role === "STUDENT_SUPPORT" ||
    role === "READ_ONLY_AUDITOR"
  );
}

/**
 * Roles holding `ATTENDANCE`/`VIEW` per `PermissionCheckServiceImpl`'s matrix
 * (Tenant Admin, Attendance Operator, Read-only Auditor) — gates the
 * "Attendance Reports" nav entry
 * (`components/layout/nav/tenant-admin-nav.tsx`) for the Tenant Admin
 * Attendance Reports screen. `AttendanceController`'s own
 * `AttendanceAccessGuard`/`PermissionCheckService` check remains the sole
 * enforcement — a role without this grant that navigates directly to
 * `/tenant-admin/attendance/reports` still gets a real 403, unchanged.
 */
export function canViewAttendanceReports(role: string | null): boolean {
  return (
    role === "TENANT_ADMIN" ||
    role === "ATTENDANCE_OPERATOR" ||
    role === "READ_ONLY_AUDITOR"
  );
}

/**
 * Roles holding `ATTENDANCE`/`CREATE_EDIT` per `PermissionCheckServiceImpl`'s
 * matrix (Tenant Admin, Attendance Operator) — gates the staff "Mark
 * Attendance" nav entry (`/tenant-admin/attendance/mark`). A subset of
 * `canViewAttendanceReports`: Read-only Auditor holds `VIEW` only and must
 * never see this nav entry, though (per this codebase's UX-convenience-only
 * framing) a direct-URL Read-only Auditor request still independently gets a
 * real backend 403.
 */
export function canMarkAttendanceStaff(role: string | null): boolean {
  return role === "TENANT_ADMIN" || role === "ATTENDANCE_OPERATOR";
}

/**
 * Roles holding the flat tenant-wide `DomainArea.EXAMS` grant
 * (`VIEW`/`CREATE_EDIT`/`APPROVE`) per `PermissionCheckServiceImpl`'s matrix —
 * Tenant Admin and Exam Manager may author questions, schedule/edit exams,
 * mark, and publish results tenant-wide, no course-ownership check
 * (`ExamAccessGuard#requireAuthoringAccess`/`#requireLifecycleTransitionAccess`,
 * MVP-017 plan §9). Read-only Auditor holds `VIEW` only and must NOT be
 * included here — it may view exam screens but every mutating action must
 * still independently reject it server-side.
 */
export function canManageExamsStaff(role: string | null): boolean {
  return role === "TENANT_ADMIN" || role === "EXAM_MANAGER";
}

/**
 * Roles holding the flat tenant-wide `DomainArea.EXAMS` grant at `VIEW` or
 * above per `PermissionCheckServiceImpl`'s matrix — every role
 * `canManageExamsStaff` includes, PLUS `READ_ONLY_AUDITOR`, who holds
 * `EXAMS`/`VIEW` only. Use this (not `canManageExamsStaff`) to gate
 * visibility of the "Exams" nav entry and any other view-only exam surface —
 * `canManageExamsStaff` stays scoped to gating mutation/action UI (create/
 * edit/schedule/publish/mark), where Read-only Auditor must never see a
 * control, only data. UX convenience only; every exam endpoint still
 * independently enforces its own real access check server-side regardless of
 * what this returns.
 */
export function canViewExamsStaff(role: string | null): boolean {
  return canManageExamsStaff(role) || role === "READ_ONLY_AUDITOR";
}

/**
 * Teacher-only: the owning Teacher may transition their own course's exam
 * `DRAFT -> SCHEDULED` and publish results
 * (`ExamAccessGuard#requireLifecycleTransitionAccess`). Deliberately excludes
 * Teacher Assistant (denied this unconditionally server-side regardless of
 * any course association, per the MVP-017 plan's boxed note in §7) and
 * excludes Tenant Admin/Exam Manager (already covered by
 * `canManageExamsStaff` above — do not OR the two role sets together inside
 * this helper). A page needing "can this actor reach the schedule/publish
 * action at all" should check `canScheduleOrPublishExam(role) ||
 * canManageExamsStaff(role)` explicitly at the call site.
 */
export function canScheduleOrPublishExam(role: string | null): boolean {
  return role === "TEACHER";
}

/**
 * Roles that may reach any exam-authoring mutation (create/edit a question,
 * create a draft exam, edit a `DRAFT` exam's details/linked questions) per
 * `ExamAccessGuard.requireAuthoringAccess`: owning `TEACHER`, `TEACHER_ASSISTANT`
 * (tenant-wide, capped server-side at `DRAFT` — a TA is still rejected by
 * `schedule`/`publish-results` regardless of what this helper returns), and
 * staff holding the flat tenant-wide `DomainArea.EXAMS`/`CREATE_EDIT` grant
 * (`TENANT_ADMIN`, `EXAM_MANAGER`). UX convenience only — every gated
 * mutation's real backend endpoint independently re-enforces this via
 * `requireAuthoringAccess`, including the course-ownership check for
 * `TEACHER` that this role-only helper cannot express client-side.
 */
export function canAuthorExams(role: string | null): boolean {
  return (
    role === "TEACHER" || role === "TEACHER_ASSISTANT" || role === "TENANT_ADMIN" || role === "EXAM_MANAGER"
  );
}

/**
 * Roles that may submit a manual mark on a structured exam answer, per
 * `MarkingQueueService#requireMarkingAccess` — deliberately narrower than
 * `canAuthorExams` above, and NOT the same role set: marking authorization is
 * owning-`TEACHER`-or-staff-`CREATE_EDIT`, mirroring `AttendanceAccessGuard`'s
 * shape rather than `ExamAccessGuard.requireAuthoringAccess`'s. Confirmed
 * directly against `MarkingQueueService`'s source: `TEACHER_ASSISTANT` holds
 * no `DomainArea.EXAMS` grant at all in `PermissionCheckServiceImpl` and is
 * denied here (unlike `canAuthorExams`, which does include TA for
 * authoring); `READ_ONLY_AUDITOR` holds `DomainArea.EXAMS`/`VIEW` only and
 * must see a marking-queue row's data but never the score input/"Save score"
 * action gated by this helper. UX convenience only — the real
 * course-ownership check for `TEACHER` still happens server-side.
 */
export function canMarkExamAnswers(role: string | null): boolean {
  return role === "TEACHER" || role === "TENANT_ADMIN" || role === "EXAM_MANAGER";
}

/**
 * True for the two roles that own a course's Teacher Portal identity
 * (`TEACHER`, `TEACHER_ASSISTANT`) — used purely for "Viewing as X"
 * disclosure banners on exam screens nested under `app/(teacher)/` that
 * Tenant Admin/Exam Manager also legitimately reach tenant-wide (see the
 * Scheduler/Marking Queue/Publish pages' own doc comments). Display-only:
 * this has no bearing on what any of those pages' actions are actually
 * allowed to do — `canManageExamsStaff`/`canScheduleOrPublishExam`/
 * `canMarkExamAnswers` remain the real UX-gating checks for that.
 */
export function isTeacherRole(role: string | null): boolean {
  return role === "TEACHER" || role === "TEACHER_ASSISTANT";
}
