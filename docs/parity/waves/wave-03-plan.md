# Wave 3 Plan — Student and Teacher Operational Profiles

Status: plan complete, implementation proceeding per this document.
Parity IDs in scope: **PAR-03-01** (correcting a stale MATCHES), **PAR-03-02, PAR-03-03,
PAR-03-04, PAR-03-05, PAR-03-06, PAR-04-03, PAR-04-04**, plus master instruction §9/§10
(Student registration expansion) and §11 (Teacher lifecycle expansion), per
`implementation-roadmap.md` §7.

User-approved design decisions (2026-09-23, via `AskUserQuestion`), both change-controlled
under `.claude/rules/payments.md` §7 since `Enrollment` rows are structurally locked to 4
approved write call sites in `EnrollmentActivationService`. Recorded formally in
`docs/adr/ADR-016-staff-granted-enrollment-and-revocation.md` (added in the Wave 3
fix-pass review - see that ADR for the full context/decision/consequences record; every
code comment implementing these two decisions cites ADR-016 by number):

1. **Staff "enroll student in course"** — creates a real `Order` + `Payment(CONFIRMED,
   method=STAFF_GRANTED)` with a mandatory reason, funneled through a new, explicit 5th
   approved call site (`fromApprovedManualEvidence`), audit-logged. Not a bypass of the
   payment/ledger trail — a new *kind* of manual evidence, same shape as an approved slip.
2. **Staff "revoke enrollment"** — a new narrow `EnrollmentActivationService.revoke(...)`
   call site that calls the existing `supersede()` mutation with no replacement row,
   audit-logged with mandatory reason. No new `EnrollmentStatus` value, no ledger/payment
   write. Reactivation afterward reuses the existing reactivation-request flow.

---

## 1. Current implementation (verified by code inspection, not the stale matrix)

### Student (`usermanagement.student`)
- `StudentProfile` (table `student_profile`, V17): `id, tenantId, userId, name` only — no
  `status` (lives on `identityaccessservice.TenantUser.status`: `ACTIVE`/`SUSPENDED`), no
  guardian/school/grade/stream/mobile columns, no `must_change_password` (lives on
  `TenantUser`, hardcoded `true` on every admin-created student — not client-settable, which
  already satisfies **PAR-03-02**'s "with `must_change_password` flag" intent).
- `StudentProfileRepository` extends `TenantAwareRepository` — zero custom queries, tenant
  filtering always server-resolved from `TenantContext`, never client input.
- `StudentService`: `createStudent`, `listStudents`, `getStudent`, `updateStudent(name only)`,
  `getOwnProfile`/`updateOwnProfile`. No real audit-log-management write anywhere — SLF4J
  trace lines only.
- `StudentController` (`/api/v1/students`): `POST`, `GET` (list), `GET /{id}`, `PATCH /{id}`,
  `GET /me`, `PATCH /me`. No delete, no bulk-import, no self-registration, no activate/
  deactivate, no enroll/revoke, no password-reset, no device-reset endpoint.
- **`(auth)/register` is a disabled placeholder shell** (`frontend/src/app/(auth)/register/page.tsx`)
  — a non-submitting form with a "Not yet implemented" notice. The parity matrix's PAR-03-01
  `MATCHES` classification is **stale**; this is corrected to `MISSING_WORKFLOW` in Phase F.
  No student self-registration endpoint exists on the backend at all.
- Cross-domain student data reachable **today** for a given `studentId` by staff: **none** of
  enrollment/payment/attendance/exam has a staff-facing, studentId-scoped read endpoint —
  every existing read in those domains is self-only (`/my`, `/me`) or tenant-wide-unfilterable
  (`ledger/dashboard`, `attendance/reports`). `EnrollmentQueryService`'s own javadoc flags this
  gap explicitly as needing "its own endpoint carrying an explicit studentId" if ever required.
- Device history: genuinely absent — `device_session` is a JWT refresh-session table, not a
  device-limit/device-slot feature; `DomainArea.DEVICES` exists in the permission enum with no
  implementation anywhere. Confirmed out of Wave 3 scope (Wave 10).
- Notification history (staff view of a student's notifications): absent — `NotificationController`
  is self-only. Confirmed out of Wave 3 scope (Wave 11 expands notification-management).

### Teacher (`usermanagement.teacher`)
- `TeacherProfile` (table `teacher_profile`, V18): `id, tenantId, userId, name, approvalStatus`
  (enum `PENDING|APPROVED|REJECTED` — **no `SUSPENDED` value**, contradicting master
  instruction §11's required 4-state lifecycle), `approvedBy`, `approvedAt`. Mutators
  `approve()`/`reject()` are one-directional/terminal via `requirePending()`.
- `TeacherService`: `createTeacher` (provisions `tenant_user` role `TEACHER`, immediately
  suspends login, creates `PENDING` profile), `approveTeacher`/`rejectTeacher` (permission
  `TEACHERS`/`CREATE_EDIT` **plus** a hardcoded `requireTenantAdmin()` check), `listTeachers`,
  `getTeacher`. No real audit-log-management write — no import of it at all in this file.
- `TeacherController` (`/api/v1/teachers`): `POST`, `GET` (list, `?approvalStatus=`), `GET /{id}`,
  `POST /{id}/approve`, `POST /{id}/reject`. No `PATCH`, no self-service `/me`.
- Roster: only a **session-scoped** read exists (`GET /api/v1/attendance/sessions/{id}/roster`),
  backend-filtered to the calling teacher's own course ownership via
  `AttendanceAccessGuard.requireSessionAccess`. No course-wide roster endpoint exists, and no
  roster screen exists in the frontend at all (`TeacherNav` has no such item).
- Teacher assigned courses (`GET /api/v1/courses` for role `TEACHER`) — confirmed
  backend-filtered (`CourseSpecifications.withTeacherId(principal.userId())`, client-supplied
  `teacherId` ignored for a Teacher caller). **PAR-04-02 stays `MATCHES`, unaffected.**
  This confirms **PAR-03-06**'s roster-view concern is about the *missing screen*, not about
  client-side filtering — no client-filtered code path exists to fix.
- Teacher financial summary: confirmed absent — zero `teacherId` references anywhere in
  `ledgersettlementmanagement`. Confirmed out of scope (Wave 7).
- Sessions: confirmed no `live-class-management`/`ClassSession` domain exists at all. Confirmed
  out of scope (Wave 4); attendance today is keyed off `course_lesson`, not a real session.

### Frontend
- `tenant-admin/students`: list (client-side search/filter over an unfiltered `GET /v1/students`),
  `CreateStudentSheet`, and a single flat (no-tabs) detail/edit page wired to `GET`/`PATCH
  /v1/students/{id}`.
- `tenant-admin/teachers`: list, `new`, detail — Approve/Reject wired; **no Suspend/Reactivate
  UI** despite `TeacherAccountStatus` already including a `SUSPENDED` value in the frontend type
  (an existing frontend/backend mismatch — the backend has no way to produce that value today).
- `teacher/**`: no roster/student-list route exists.
- No CSV import UI anywhere. No public student self-registration form exists (the `(auth)/register`
  shell is disabled).
- Loading/empty/error/permission-denied handling is solid and consistent (`QueryStateBoundary`)
  on every existing students/teachers screen — this baseline is reused, not rebuilt.

---

## 2. Target behavior (this wave)

| Parity ID | Target | In scope this wave? |
|---|---|---|
| PAR-03-01 | Real, tenant-configurable public student self-registration replaces the disabled placeholder | **Yes — correcting a stale MATCHES** |
| PAR-03-02 | Manual single-student creation with `must_change_password` — already correct | Verify only, no code change |
| PAR-03-03 | Bulk CSV import of students | **Yes** |
| PAR-03-04 | Student Detail composes Profile/Enrollments/Payments/Attendance/Exams; Devices/Notifications/unified-Activity-timeline stay deferred (Wave 10/11), Activity gets a real per-student audit-log-backed tab since Wave 3 adds real audit writes | **Yes (available parts)** |
| PAR-03-05 | Actions: edit (exists), activate/deactivate, enroll, revoke enrollment, password reset — all server-authorized + audited; device reset and generic access-extension stay deferred (Wave 10/6), but the existing reactivation-request flow is surfaced from the detail page | **Yes (available parts)** |
| PAR-03-06 | A real, backend-filtered course roster screen for Teacher | **Yes** |
| PAR-04-03 | Teacher Detail composes Profile/Assigned Courses/Roster/Attendance/Exams/Activity; Sessions and Financial summary stay deferred (Wave 4/7) | **Yes (available parts)** |
| PAR-04-04 | Teacher lifecycle gains a genuine `SUSPENDED` state with Suspend/Reactivate actions, both audited | **Yes** |
| §10 Registration configuration | `ConfigDomain.STUDENT` gets real properties: `public_registration_enabled`, `approval_required`, `otp_required` (email-only — SMS/WhatsApp providers remain BLOCKED per roadmap §5), and per-field toggles (`require_guardian_info`, `require_school`, `require_grade`, `require_stream`, `require_mobile`) | **Yes** |

---

## 3. Database impact (additive Flyway migrations only, next available version)

1. **`V43__add_teacher_suspended_status.sql`** — widen `teacher_profile`'s
   `ck_teacher_profile_approval_status` CHECK to add `'SUSPENDED'`. No backfill needed
   (additive enum value, existing rows unaffected). Add `suspended_at`/`suspended_by` and
   `reactivated_at`/`reactivated_by` nullable columns (mirrors the existing `approved_by`/
   `approved_at` pattern) for the new transitions' own evidence trail.
2. **`V44__add_student_registration_profile_fields.sql`** — add nullable columns to
   `student_profile`: `guardian_name, guardian_phone, school, grade, stream, mobile`. Nullable
   because required-ness is tenant-configurable (enforced at the API layer against
   `ConfigDomain.STUDENT`), not a DB-level constraint — a global `NOT NULL` here would be wrong
   for tenants that don't require the field.
3. **`V45__add_student_registration_status.sql`** — add `registration_status` (`CHECK IN
   ('SELF_REGISTERED','ADMIN_CREATED')`, `NOT NULL DEFAULT 'ADMIN_CREATED'`) to `student_profile`,
   so a pending-approval self-registered student (whose `tenant_user.status` starts `SUSPENDED`,
   mirroring the existing teacher-pending pattern) is distinguishable in the activate/deactivate
   UI from a staff-deactivated one. Existing rows backfill to `ADMIN_CREATED` (accurate — no
   self-registration path existed before this wave).
4. **`V46__create_student_registration_otp.sql`** — new table `student_registration_otp`
   (`tenant_id NOT NULL`, `email`, `otp_hash`, `expires_at`, `consumed_at`, `attempt_count`),
   `tenant_id`-leading index, short TTL enforced at the service layer. Not linked to
   `student_profile` (no account exists yet at OTP-send time).
5. **`V47__add_enrollment_staff_granted_evidence.sql`** — extend `payment`'s method/evidence
   enum with `STAFF_GRANTED` (additive) and add a nullable `staff_grant_reason` column,
   consistent with ADR-015's precedent of a narrow, explicit CHECK-constraint widening rather
   than a generic "notes" field. Add `revoked_at`/`revoked_by`/`revoke_reason` nullable columns
   to `enrollment` for the new `revoke()` call site's own evidence (columns only — `revoke()`
   still only ever touches `supersededAt` plus these three, never the immutable activation
   columns).
6. `ConfigPropertyRegistry` changes (`STUDENT` domain properties) are **code-only**, no migration
   — `tenant_config_entry` (V36) already stores arbitrary typed values per `(tenant, domain, key)`.

All additive. No edit to an already-applied migration. No destructive backfill.

---

## 4. API impact

New/changed endpoints (all versioned under `/api/v1`, all specify auth/permission/tenant
behavior/DTOs per master instruction §36):

**Student**
- `POST /students/register` — public, tenant-resolved via subdomain (unauthenticated). Reads
  `ConfigDomain.STUDENT` for the calling tenant; 403-equivalent (404-style "not found" per this
  codebase's existing cross-tenant-404 convention) if `public_registration_enabled=false`.
  Validates required fields per tenant config. If `otp_required`, requires a prior verified
  `POST /students/register/otp/send` + `POST /students/register/otp/verify` pair (email-only).
  If `approval_required`, creates the account with `tenant_user.status=SUSPENDED` and
  `registration_status=SELF_REGISTERED`, requiring a subsequent staff `activate` call.
- `POST /students/bulk-import` — multipart CSV, staff (`STUDENTS`/`CREATE_EDIT`). Per-row
  partial-failure: every row is attempted independently, response is a per-row result list
  (`{row, status: CREATED|FAILED, reason?}`) — not all-or-nothing (explicit judgment call,
  flagged for product-owner awareness, consistent with Wave 1/2's precedent of deciding
  pragmatically rather than blocking on an unraised business question).
- `POST /students/{id}/activate`, `POST /students/{id}/deactivate` — staff
  (`STUDENTS`/`CREATE_EDIT`), thin wrappers over the existing `UserProvisioningApi.activateTenantUser`/
  `suspendTenantUser`, now actually audit-logged.
- `POST /students/{id}/reset-password` — staff (`STUDENTS`/`CREATE_EDIT`). New
  `UserProvisioningApi.resetPassword(userId)` method: generates a random temporary password,
  sets `mustChangePassword=true`, returns the temp password once in the response (never stored,
  never logged) for staff to relay out-of-band. Audit-logged (no `reason` needed — not an
  override of a flag, just a standard admin action).
- `POST /students/{id}/enroll` — staff (`STUDENTS`/`CREATE_EDIT` and `PAYMENTS_SLIPS`-equivalent
  manual-evidence gate — exact second gate decided during backend implementation, mirroring how
  slip-approval is gated), body `{courseId, reason}`. Creates `Order`+`Payment(CONFIRMED,
  STAFF_GRANTED)`, calls the new `EnrollmentActivationApi.fromApprovedManualEvidence(...)`
  approved call site, audit-logged with `reason`.
- `POST /enrollments/{id}/revoke` (lives in `enrollment-management`, not `user-management` —
  it's enrollment's own aggregate) — staff, body `{reason}` (mandatory). Calls the new
  `EnrollmentActivationService.revoke(...)`, audit-logged.
- `GET /students/{id}/enrollments` (enrollment-management), `GET /students/{id}/ledger`
  (ledger-settlement-management), `GET /attendance/students/{id}/report` (attendance-management,
  extends existing `AttendanceReportFilter` with an optional `studentId`), `GET
  /exams/students/{id}/attempts` (exam-management) — all new, staff-only, studentId-scoped reads,
  each living in its **owning** domain (not duplicated into `user-management`), each gated on
  that domain's existing staff `VIEW`-level permission (e.g. `ATTENDANCE`/`VIEW`) plus implicitly
  requiring the target student to belong to the caller's own tenant (structural, via
  `TenantAwareRepository`).
- `GET /students/{id}/activity` (audit-log-management, via the existing `AuditLogQueryService`
  filtered by `targetEntity='student_profile' AND targetId={id}`) — staff, gated `AUDIT_LOG`/`VIEW`.

**Teacher**
- `GET /courses/{courseId}/roster` (enrollment-management — reuses the same
  `enrollmentAccessApi.listCurrentlyEnrolledStudentIds(courseId)` the session-scoped roster
  already calls, composed with `user-management`'s student summaries via its own `api`
  interface, never a cross-domain repository join) — Teacher (own-course-only, backend-verified
  ownership via the same pattern `AttendanceAccessGuard` already uses) or staff
  (`STUDENTS`/`VIEW` or `COURSES`/`VIEW`).
- `POST /teachers/{id}/suspend`, `POST /teachers/{id}/reactivate` — staff (`TEACHERS`/`CREATE_EDIT`
  + `requireTenantAdmin()`, matching the existing approve/reject gate exactly — an explicit
  judgment call carried forward from existing precedent, not a new asymmetry; flagged given
  `rbac-impact-analysis.md` §4's noted open question about whether Course Coordinator should
  also hold this power). `suspend` sets `approvalStatus=SUSPENDED` + calls
  `suspendTenantUser` (blocks login); `reactivate` sets `approvalStatus=APPROVED` + calls
  `activateTenantUser`. Both audited. Transition guard: only `APPROVED ⇄ SUSPENDED`, never from
  `PENDING`/`REJECTED`.
- `GET /teachers/{id}/activity` — same pattern as the student activity tab.

**Tenant config**
- No new endpoints — `GET/PUT /api/v1/tenant-config/{domain}` (existing, Wave 1) now returns
  real properties for `domain=STUDENT`.

All new endpoints follow the existing DTO-only (never JPA entity) contract, and all get an
explicit authentication/permission/tenant-behavior/validation/error-response entry in
`docs/api/` (Phase F).

---

## 5. Frontend impact

- **Registration**: replace the disabled `(auth)/register` shell with a real, tenant-driven
  form. Fields render conditionally based on `GET /tenant-config/STUDENT` (never hard-coded per
  tenant, per master instruction §10). OTP step (email) renders only when
  `otp_required=true`. Post-submit state differs by `approval_required` (pending-approval banner
  vs. immediate login prompt).
- **`tenant-admin/students`**: add a `Bulk Import` entry point (CSV upload + per-row result
  table). Rebuild the detail page into tabs (Profile / Enrollments / Payments / Attendance /
  Exams / Activity), each tab backed by its owning domain's client (`lib/api/enrollments.ts`,
  `lib/api/ledger.ts`, `lib/api/attendance.ts`, `lib/api/exams.ts`, a new activity hook in
  `lib/api/audit-log.ts` if one doesn't already exist for tenant-admin use) — **not** new
  student-specific duplicate clients, per the architecture rule against uncontrolled cross-domain
  composition. Add Activate/Deactivate toggle, Enroll action (course picker + reason), Revoke
  action (per-enrollment, reason required) to the Actions area. Add a "Reset password" action
  showing the one-time temp password in a dismissible, copy-once dialog (never persisted client-side
  beyond the dialog's own state). Surface a link into the existing Reactivation Approvals queue
  from the Payments tab rather than building a new "extend access" control.
- **`tenant-admin/teachers`**: extend `TeacherStatusBadge`/status filter to include `SUSPENDED`;
  add Suspend/Reactivate buttons (same gating pattern as Approve/Reject, reusing
  `teacher-decision-dialog.tsx`'s shape). Rebuild detail page into tabs (Profile / Assigned
  Courses / Roster / Attendance / Exams / Activity) — Financial summary and Sessions tabs are
  explicitly **not** added this wave (no backend data exists; per §34, do not add a nav
  item/tab whose workflow isn't implemented).
- **`teacher/**`**: add a course-scoped Roster route/section (e.g. `teacher/courses/[courseId]/roster`
  or a roster panel on the course detail), backend-filtered via the new endpoint.
- All new/changed screens get the full loading/empty/error/permission-denied/validation/success
  state set (`QueryStateBoundary` + existing patterns), search/filter/pagination on any new list
  (bulk-import result table, roster), and unsaved-change protection where a form is involved,
  per master instruction §34.

---

## 6. Security impact

- Every new mutation (activate/deactivate, enroll, revoke, password reset, suspend/reactivate,
  bulk import per-row creation, self-registration) writes a real `AuditLogApi.record(...)` entry
  in the same transaction as the state change — closing the current gap where Student/Teacher
  services only emit SLF4J trace lines, per `.claude/rules/security.md`'s audit-logging
  requirement for "payment approvals/rejections" (enroll/revoke are payment/ledger-adjacent) and
  general staff-privileged-action logging.
- Password reset never logs or persists the generated temporary password anywhere beyond the
  single response payload; the audit entry records that a reset occurred, never the password
  value itself (mirrors `sensitive`-flag handling already in `ConfigPropertyDefinition`).
- Self-registration OTP: rate-limited per (tenant, email) via `student_registration_otp.attempt_count`
  + short TTL, to prevent brute-force/enumeration; OTP verification failure responses are
  uniform (never reveal whether the email already has an account, to avoid account enumeration).
- All new studentId/teacherId-scoped staff reads (enrollments/ledger/attendance/exams/roster/
  activity) require an explicit cross-tenant negative test (Section 8) — this is the same class
  of endpoint `security.md`'s "Upload Validation & Protected-Content Access" section warns about:
  an unguessable-looking UUID id is not itself access control.

---

## 7. Tenant isolation impact

- Every new/changed repository method for `student_profile`, `teacher_profile`, `enrollment`,
  the new `student_registration_otp` table, and every studentId/teacherId-scoped cross-domain
  read continues to route through `TenantAwareRepository`/the server-resolved `TenantContext` —
  no new repository method accepts a caller-supplied `tenant_id`.
- `student_registration_otp` and the new evidence columns on `enrollment`/`payment` get
  `tenant_id NOT NULL` with a tenant-leading index, per `.claude/rules/tenancy.md`.
- The public registration endpoint resolves tenant identity from the request's subdomain
  (existing `TenantResolutionFilter`, ADR-002/006), never from a client-supplied tenant field in
  the registration payload.
- Cross-tenant negative tests required for every new endpoint (Section 8) — an authenticated
  actor from tenant A addressing tenant B's `studentId`/`teacherId`/`courseId`/`enrollmentId`
  must get 404, never 200-with-filtered-data.

---

## 8. Test plan

**Backend** (JUnit + Spring Boot integration + Testcontainers, extending the existing
`StudentServiceTest`/`StudentManagementIntegrationTest`/`TeacherServiceTest`/
`TeacherManagementIntegrationTest` suites, plus new suites per new module surface):
- Unit: `TeacherProfile` suspend/reactivate transition-guard tests (valid/invalid source states),
  `EnrollmentActivationService.revoke`/`fromApprovedManualEvidence` unit tests, OTP hashing/
  expiry/attempt-limit unit tests, bulk-import per-row partial-failure unit tests.
- Integration: registration end-to-end (per registration-policy permutation: public/approval/OTP
  on/off, required-field permutations), activate/deactivate, enroll (full Order→Payment→
  Enrollment chain), revoke, password reset (temp password round-trip, `mustChangePassword`
  forced), suspend/reactivate (including login-blocked assertion post-suspend), roster
  (own-course vs. other-teacher's-course 403), each new studentId/teacherId-scoped read.
- Authorization negative tests: every new endpoint tested against a caller lacking the required
  permission (403), and — for teacher suspend/reactivate — against a caller who is not Tenant
  Admin (403, testing the `requireTenantAdmin()` gate specifically).
- **Cross-tenant negative tests are mandatory** for every new endpoint listed in Section 4,
  following the existing `@Tag("cross-tenant")` pattern already used in
  `StudentManagementIntegrationTest`/`TeacherManagementIntegrationTest`.
- Financial-workflow-specific tests (per master instruction §37) for the enroll/revoke paths:
  idempotency (double-submit enroll doesn't double-activate), transaction rollback (a failure
  partway through Order→Payment→Enrollment leaves no partial state), duplicate submission,
  direct-ID manipulation (enrolling/revoking a different tenant's `enrollmentId`), unauthorized
  state transitions (revoking an already-revoked enrollment, suspending a `PENDING` teacher).

**Frontend** (Playwright, extending `student-management.spec.ts`/`teacher-management.spec.ts`):
- New specs: `student-registration.spec.ts` (per registration-policy permutation), `student-bulk-import.spec.ts`,
  `student-detail-tabs.spec.ts` (each tab's loading/empty/error/permission states),
  `student-actions.spec.ts` (activate/deactivate/enroll/revoke/reset-password), `teacher-suspend-reactivate.spec.ts`,
  `teacher-roster.spec.ts`, `teacher-detail-tabs.spec.ts`.
- Existing `student-management.spec.ts`/`teacher-management.spec.ts` updated for the new tabbed
  layout rather than duplicated.

---

## 9. Migration strategy / rollout risk

- All 5 new migrations (V43–V47) are additive-only; no backfill risk beyond the two safe,
  accurate defaults already noted (teacher CHECK widen, student `registration_status` default).
- Sequencing risk mirrors Wave 2's §3 lesson: the `enrollment`/`payment` evidence-column
  migration (V47) and the enroll/revoke service code must ship in the same slice — a partially
  migrated schema with old activation code would leave `STAFF_GRANTED` payments unreachable, not
  a payment-integrity regression (no code path writes that value until the service exists), so
  this is lower risk than Wave 2's equivalent concern, but still sequenced within one commit.
- `PENDING`/`REJECTED` teachers remain unaffected by the CHECK widen — existing rows keep their
  current values untouched.

---

## 10. Explicit judgment calls (flagged for product-owner awareness, not silently decided)

1. Bulk-import partial-row-failure behavior (continue-on-error, per-row result) — Section 4.
2. Teacher suspend/reactivate gated identically to approve/reject
   (`TEACHERS`/`CREATE_EDIT` + Tenant-Admin-only) rather than resolving `rbac-impact-analysis.md`
   §4's open question about a distinct `A`-level permission — carries forward existing precedent.
3. OTP requirement implemented email-only (SMS/WhatsApp providers remain BLOCKED per
   `implementation-roadmap.md` §5 items 2–3) — a tenant that enables `otp_required` with only a
   phone-based expectation in mind will need product-owner communication that only email delivers
   today.
4. Password reset returns a one-time temporary password in the API response rather than a
   reset-link/token flow — chosen for consistency with the existing "admin creates account with
   known temp password" pattern already in place for create-student/create-teacher, not a new
   mechanism; flagged since a token/link-based reset is the more common pattern elsewhere and may
   be preferred later.

---

## 11. Deferred (explicitly out of scope, not silently skipped)

- Device history/device-reset (Wave 10 — domain doesn't exist).
- Notification history staff view (Wave 11).
- Generic "extend access" beyond the existing reactivation-request flow (Wave 6 —
  `AccessPolicyService`/PAR-XC-04 not built yet).
- Teacher Sessions tab (Wave 4 — no `ClassSession` domain).
- Teacher Financial summary tab (Wave 7 — no teacher-scoped ledger/settlement data).
- SMS/WhatsApp OTP delivery (BLOCKED — provider selection pending, roadmap §5).
