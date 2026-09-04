# MVP-016 Attendance — Module Plan

Status: **Approved plan, not yet implemented.** Produced via `plan-module`, using
`product-requirements-analyst`, `solution-architect`, `database-architect`,
`security-reviewer`, `qa-test-engineer`, `ui-ux-reviewer` (read-only analysis).
`payment-ledger-specialist` was not used — this module has no payment/ledger impact
(see §17).

Source of truth: GitHub issue [#16](https://github.com/mohanranaweera/lms-saas-platform/issues/16)
("[MVP] Module 16: Attendance"). Cross-checked against
`docs/requirements/specifications/10-attendance.md`,
`docs/requirements/user-roles-and-permissions.md`,
`docs/requirements/module-catalog.md`, `docs/requirements/open-decisions.md`, and the
shipped `course-management`/`enrollment-management`/`identity-access-service` code and
migrations (V1–V24).

Two decisions this document depends on were explicitly put to the product owner during
planning (not invented) — see the boxed notes in §7/§8 and §21 for both:

1. **Session-equivalent scope = `course_lesson.id`** (no new `class_session` table).
2. **Re-marking a student for the same session is an in-place upsert**, not a
   reject-on-duplicate conflict.

---

## 1. Business goal

Let a Teacher record Present/Absent/Late status for each currently-enrolled student
against a specific course lesson (the session-equivalent unit at this MVP), scoped
strictly to courses they own. Give every role a role-appropriate, tenant- and
assignment-filtered view into that history: a Student sees only their own records, a
Teacher sees only their own courses' records, and tenant staff (Tenant Admin,
Attendance Operator, Read-only Auditor) see the tenant-wide picture, consistent with
the domain-level `DomainArea.ATTENDANCE` grants that already ship in
`PermissionCheckServiceImpl`.

This is deliberately the "manual attendance + basic reports" slice only
(`module-catalog.md`'s MVP line for `attendance-management`). Zoom-sync ingestion,
absent-student alerts, richer late-arrival time tracking, attendance-based access
restrictions, and QR/smart-card attendance are explicitly Phase 2/3 and out of scope
(see §6).

## 2. Roles and permissions

| Role | Mark attendance | View report | Scope | Source |
|---|---|---|---|---|
| Teacher | Yes — own assigned course only (`course.teacher_id == principal.userId()`) | Yes — own courses only | Ownership-scoped, backend-enforced | Issue ACs; Teacher has no `DomainArea.ATTENDANCE` matrix row (same pattern as `COURSES`/`MATERIALS` — ownership guard, not the flat matrix) |
| Teacher Assistant | **No — out of scope for MVP** | **No — out of scope for MVP** | N/A | `user-roles-and-permissions.md` §3: TA's entire boundary is PROVISIONAL/unratified; `open-decisions.md` §3 names `10-attendance.md` as affected. Follows `CourseAccessGuard`'s literal `"TEACHER"`-only precedent, not `MaterialAccessGuard`'s TA inclusion (which that module's own javadoc flags as currently non-functional — no TA-to-course assignment table exists anywhere) |
| Tenant Admin / Institute Owner | Yes — tenant-wide | Yes — tenant-wide | Tenant | `DomainArea.ATTENDANCE` → `VIEW, CREATE_EDIT` (`PermissionCheckServiceImpl.java:145`, already shipped) |
| Attendance Operator | Yes — tenant-wide | Yes — tenant-wide | Tenant | `DomainArea.ATTENDANCE` → `VIEW, CREATE_EDIT` (`PermissionCheckServiceImpl.java:204-208`, already shipped) |
| Read-only Auditor | No | Yes — tenant-wide, read-only | Tenant | `DomainArea.ATTENDANCE` → `VIEW` only (`PermissionCheckServiceImpl.java:218`, already shipped) |
| Student | No | Yes — own history only, no `{studentId}` param | Self | Issue AC; mirrors `EnrollmentController.myEnrollments()`'s owner-only-by-construction pattern |
| Finance Staff, Course Coordinator, Student Support, Content Manager, Exam Manager | No | No | — | No `DomainArea.ATTENDANCE` grant exists for any of these roles in the shipped matrix |
| Platform Admin | Not applicable at MVP | Not applicable at MVP | — | No cross-tenant attendance aggregation is named in scope anywhere |

`DomainArea.ATTENDANCE` and its full role matrix already exist and ship unchanged — **no
`identity-access-service` change is required for this module.**

Attendance Operator and Read-only Auditor both hold a `DomainArea.ATTENDANCE` grant but
aren't named in the issue's own Frontend requirements (only Teacher/Student/Tenant Admin
screens are listed there). This plan resolves that gap explicitly in §11: both reuse the
Tenant Admin Attendance Reports screen (gated per-role, Auditor without the marking
entry point), and Attendance Operator additionally gets a Mark Attendance entry point
mirroring the Teacher one but with a tenant-wide (not ownership-restricted) course
selector — a direct, mechanical consequence of the permission matrix's existing `V/C/E`
grant for that role, not a new business rule.

## 3. Preconditions

- Tenant is active (enforced structurally by the existing tenant-resolution/auth filter
  chain — not a new per-endpoint check).
- The marking actor is an authenticated Teacher who owns the course the session's lesson
  belongs to, or a staff user holding `DomainArea.ATTENDANCE`/`CREATE_EDIT`.
- The `course_lesson` (session-equivalent) exists within the caller's own tenant.
- The student being marked has a **currently active** enrollment
  (`enrollment.superseded_at IS NULL AND (access_expires_at IS NULL OR
  access_expires_at > now())`) in the session's course.

## 4. User flows

**Flow A — Teacher marks attendance for their own session**
1. Teacher opens Mark Attendance for a `sessionId` (`course_lesson.id`) within one of
   their own courses.
2. Backend resolves the lesson's owning course via `CourseLookupApi
   .resolveLessonOwnership(sessionId)` and confirms `ownership.teacherId() ==
   principal.userId()`.
3. Backend resolves the currently-enrolled roster for `ownership.courseId()` (new
   `EnrollmentAccessApi.listCurrentlyEnrolledStudentIds` — see §9) and loads any
   existing `attendance_record` rows for that session, so the UI can pre-populate
   already-marked statuses.
4. Teacher sets Present/Absent/Late per roster row and submits.
5. Backend upserts one `attendance_record` row per (tenant, session, student) —
   inserting new rows, updating `status`/`marked_by`/`marked_at` in place for rows that
   already exist — rejecting any submitted `studentId` not on the resolved roster.
6. Response reflects saved state; UI shows a success confirmation.

**Flow B — Staff (Attendance Operator / Tenant Admin) marks attendance**
Same as Flow A except step 2 becomes a flat `PermissionCheckService.requirePermission
(DomainArea.ATTENDANCE, CREATE_EDIT)` check — no course-ownership restriction.

**Flow C — Student views "My Attendance"**
1. Student requests their own history — no `{studentId}` parameter anywhere in the
   request.
2. Backend returns only rows where `tenant_id` = caller's tenant and `student_id` =
   caller's own id, optionally filtered by course/date range.
3. Empty state: zero rows at all → "no attendance records yet"; a date/course filter
   applied with zero matches → "no sessions match the selected date filter" (distinct
   copy, per issue AC).

**Flow D — Teacher views Attendance Reports**
1. Teacher requests the report; backend derives the teacher's own course-id set
   server-side (never a client-supplied course filter trusted alone) and returns only
   `attendance_record` rows within that set, optionally further filtered by
   course/date range (intersected with the owned set, never replacing it).
2. Same two distinct empty states as Flow C.

**Flow E — Tenant Admin / Attendance Operator / Read-only Auditor view tenant-wide
Attendance Reports**
1. Staff requests the report; backend scopes by `tenant_id` only (no course/teacher
   restriction), filterable by course/date range.
2. Responsive admin-heavy data table with an explicit mobile fallback.

## 5. Acceptance criteria

**Marking**
- Given a Teacher marks attendance for a lesson within their own assigned course, then
  one `attendance_record` row per student is persisted with `tenant_id`, `course_id`,
  `session_id`, `student_id`, `status`, `marked_by`, `marked_at` all correctly populated.
- Given a Teacher attempts to mark attendance for a lesson in a course they do not own,
  then the request is rejected `403`.
- Given a Teacher or staff caller attempts to mark attendance using a `sessionId` that
  belongs to a different tenant, then the request is rejected `404` (structurally
  invisible, not a distinguishing `403`).
- Given a Tenant Admin or Attendance Operator marks attendance within their own tenant,
  then the row persists identically to the Teacher flow, with no course-ownership
  restriction.
- Given an Attendance Operator of Tenant A attempts to read or mark Tenant B's
  attendance, then the request is rejected `403`/`404` (issue's own explicit AC).
- Given a Read-only Auditor attempts to mark attendance, then the request is rejected
  `403` server-side regardless of any stale client UI.
- Given a Course Coordinator, Content Manager, Exam Manager, Student Support, or Finance
  Staff attempts to mark or read attendance, then the request is rejected `403` (no
  `DomainArea.ATTENDANCE` grant exists for any of these roles).
- Given a marking request names a `studentId` that is not on the resolved
  currently-enrolled roster for that course, then that row is rejected (the request
  does not silently create an attendance record for an unenrolled or expired-enrollment
  student).
- Given a Teacher Assistant attempts to mark or read attendance, then the request is
  rejected `403` (out of scope for MVP — see §2).
- Given a Teacher (or staff) re-marks a student already marked for the same session,
  then the existing row is updated in place (new `status`/`marked_by`/`marked_at`) —
  **no** duplicate row is created and **no** `409` is returned (per the product-owner
  decision recorded at the top of this document).

**Reports**
- Given a Student requests "My Attendance," then the response contains only rows where
  `student_id` equals the caller's own id and `tenant_id` equals the caller's resolved
  tenant.
- Given a Teacher requests their Attendance Report, then the response is backend-derived
  to their own owned courses only; the endpoint must not accept a client-supplied
  `courseId`/`teacherId` filter that bypasses this (issue's explicit security
  requirement).
- Given a Tenant Admin, Attendance Operator, or Read-only Auditor requests the
  tenant-wide report, then the response is scoped to `tenant_id` only, with no
  cross-tenant leakage via any filter/aggregate variant.
- Given zero attendance records exist at all for the resolved scope, then the UI shows
  "no attendance records yet."
- Given attendance records exist but none match an applied date/course filter, then the
  UI shows "no sessions match the selected date filter" — a distinct empty state from
  the above.
- Given a multi-teacher tenant fixture (Teacher A and Teacher B each with their own
  course and attendance history), then Teacher A's report never includes Teacher B's
  rows and vice versa.

**Cross-tenant (mandatory at every role level)**
- Marking and reading are both rejected `403`/`404` across every role above when the
  target tenant differs from the caller's resolved tenant, including via aggregate/
  report endpoints.
- No `attendance-management` repository method accepts a caller-supplied `tenant_id`
  parameter.

**Frontend / E2E**
- Direct URL/id substitution (changing `sessionId`/`courseId`/`studentId` in the
  browser) is blocked server-side, not just hidden in the UI, for every role.
- Mobile-first (Student, Teacher) and responsive-table (Tenant Admin) views are both
  tested at a narrow viewport.

## 6. Out-of-scope items

- Zoom attendance sync (Phase 2) — `attendance-management` consuming
  `live-class-management`'s events; that domain doesn't exist yet.
- Absent-student alerts (Phase 2) — depends on `notification-management` event
  consumption, not wired for this module.
- Richer late-arrival **time** tracking (Phase 2) — the `LATE` **status value** itself
  is MVP (see the documentation-inconsistency note in §21); an actual arrival-time/
  minutes-late figure is not.
- Attendance-based access restrictions (Phase 2) — and its owning-domain boundary
  against `enrollment-management` is an explicitly unresolved cross-domain question
  (`open-decisions.md` §6).
- QR/smart-card attendance (Phase 3).
- Audit logging of marking/correction actions — attendance marking is absent from
  `.claude/rules/security.md`'s mandatory-audit-action list, and
  `10-attendance.md` §9 states this outright. No audit obligation is added (see §16).
- Teacher Assistant marking/reading attendance — PROVISIONAL/unratified role boundary.
- A `class_session` scheduling table/CRUD/UI of any kind (resolved: not building this).
- Correction-with-history (e.g. recording *who changed a mark and from what*) — the
  chosen upsert-in-place design overwrites the prior status with no separate
  change-history row (see §21).

## 7. Domain model

New domain `attendance-management`, package `com.lms.attendancemanagement`, following
`.claude/rules/architecture.md`'s per-domain layout:

- `api` — no consumer-facing methods identified for MVP (no other domain needs to read
  attendance data yet); kept empty/reserved.
- `web` — thin controllers.
- `service` — `AttendanceMarkingService`, `AttendanceReportService`.
- `domain` — `AttendanceRecord` (extends `Auditable`, implements `TenantOwned`),
  `AttendanceStatus` enum.
- `repository` — `AttendanceRecordRepository extends TenantAwareRepository<AttendanceRecord, UUID>`
  (package-private-facing, never exported).
- `support` — `AttendanceAccessGuard` (mirrors `CourseAccessGuard`'s shape).

**`AttendanceRecord`** fields — cross-domain ids are opaque `UUID` columns only, never
JPA `@ManyToOne` across module boundaries, but schema-enforced via composite FKs
(exactly the `Course`/`CourseLesson`/`Enrollment` precedent):

| Field | Type | Notes |
|---|---|---|
| `id` | UUID | app-generated (UUIDv7), no DB default |
| `tenantId` | UUID | `TenantOwned` |
| `courseId` | UUID | opaque, owned by `course-management`; **derived server-side** from `CourseLookupApi.resolveLessonOwnership(sessionId).courseId()` at write time, never accepted from the client — see §12 |
| `sessionId` | UUID | opaque, = `course_lesson.id`, owned by `course-management` |
| `studentId` | UUID | opaque, references `tenant_user`; must be on the course's currently-enrolled roster at write time |
| `status` | `AttendanceStatus` | `PRESENT` \| `ABSENT` \| `LATE`, DB `CHECK`-constrained |
| `markedBy` | UUID | opaque, references `tenant_user`; **always** `AuthenticatedPrincipalHolder.get().userId()`, never client-supplied |
| `markedAt` | Instant | server-generated |
| `createdAt`/`updatedAt`/`createdBy`/`updatedBy` | — | from `Auditable` (generic infra provenance, complementary to `markedBy`/`markedAt`, not a substitute — see §8) |

> **Boxed note — mutability.** `attendance-management` is **not** one of
> `.claude/rules/backend.md`'s named append-only high-integrity domains (payment/
> ledger/device-auth/audit-log). A mutable row, corrected in place on re-mark, is
> compliant. Stated explicitly here since the codebase otherwise leans append-only and
> a reviewer may reflexively expect it.

> **Boxed note — session-equivalent = `course_lesson.id` (confirmed this session).**
> `attendance_record.session_id` is a composite FK straight into `course_lesson
> (tenant_id, id)`; no new `class_session` table. Consequence flagged in §21: a
> lesson reused across multiple real-world calendar occurrences (e.g. a weekly class
> attached to one lesson) cannot be distinguished by this schema — each re-mark for
> that lesson overwrites the same row rather than creating a new occurrence's record.
> Accepted as an MVP limitation, not a bug to silently work around.

## 8. Database design

New migration `V25__create_attendance_management_schema.sql` (V24 is the current
latest; purely additive, no existing migration is altered).

```sql
CREATE TABLE attendance_record (
    id          UUID PRIMARY KEY,
    tenant_id   UUID NOT NULL REFERENCES tenant (id),
    course_id   UUID NOT NULL,
    session_id  UUID NOT NULL,
    student_id  UUID NOT NULL,
    status      VARCHAR(10) NOT NULL,
    marked_by   UUID NOT NULL,
    marked_at   TIMESTAMPTZ NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL,
    created_by  UUID,
    updated_by  UUID,

    -- At most one canonical row per (session, student) within a tenant.
    -- A re-mark is a service-layer UPDATE of this row's status/marked_by/
    -- marked_at, never a second INSERT (product-owner confirmed).
    CONSTRAINT uq_attendance_record_tenant_session_student
        UNIQUE (tenant_id, session_id, student_id),

    CONSTRAINT fk_attendance_record_course
        FOREIGN KEY (tenant_id, course_id) REFERENCES course (tenant_id, id),
    CONSTRAINT fk_attendance_record_session
        FOREIGN KEY (tenant_id, session_id) REFERENCES course_lesson (tenant_id, id),
    CONSTRAINT fk_attendance_record_student
        FOREIGN KEY (tenant_id, student_id) REFERENCES tenant_user (tenant_id, id),
    CONSTRAINT fk_attendance_record_marked_by
        FOREIGN KEY (tenant_id, marked_by) REFERENCES tenant_user (tenant_id, id),

    CONSTRAINT ck_attendance_record_status
        CHECK (status IN ('PRESENT', 'ABSENT', 'LATE'))
);

-- Student's own history, date-range filtered, most recent first.
CREATE INDEX idx_attendance_record_tenant_student_marked_at
    ON attendance_record (tenant_id, student_id, marked_at DESC);

-- Teacher/staff per-course report, date-range filtered. The unique
-- constraint above already leads with (tenant_id, session_id) for the
-- per-session roster read.
CREATE INDEX idx_attendance_record_tenant_course_marked_at
    ON attendance_record (tenant_id, course_id, marked_at DESC);

-- Tenant-wide staff report with no course filter.
CREATE INDEX idx_attendance_record_tenant_marked_at
    ON attendance_record (tenant_id, marked_at DESC);
```

Prerequisites already satisfied — no prerequisite migration needed before this one:
- `course (tenant_id, id)` has `uq_course_tenant_id` (V11).
- `course_lesson (tenant_id, id)` has `uq_course_lesson_tenant_id` (V15).
- `tenant_user (tenant_id, id)` already has its composite unique constraint (used by
  every other domain's staff/student FK).

**`course_id` is a documented, accepted denormalization**, not a schema-enforced
invariant: `course_lesson` (V11) has no `course_id` column of its own — only
`module_id`, resolved to a course transitively via `course_module`. Nothing at the DB
level can guarantee `session_id`'s real parent course equals the stored `course_id`;
`AttendanceMarkingService` **must** derive `course_id` server-side from
`CourseLookupApi.resolveLessonOwnership(sessionId).courseId()` and never trust a
client-supplied `courseId` for the persisted row (see §12). A negative test proving a
`session_id` from course A cannot be recorded under `course_id` B is required (§18).

**No `ON DELETE CASCADE`** from `course`/`course_lesson` onto `attendance_record` —
unlike `course_module`/`course_lesson`'s own cascade from `course` (V14, structural-only,
no independent audit value), attendance is academic history. Default FK behavior means
deleting a course/lesson that already has attendance history will be **blocked** by this
migration rather than silently cascaded away — flagged explicitly as a behavior change
for reviewer awareness, mirroring V22's "no CASCADE on history-like tables" precedent.

`marked_by` is an **explicit domain column**, not inferred from `Auditable.createdBy`:
it's domain-meaningful (who is accountable for this attendance fact, and it must be
re-settable on a re-mark by a different staff member), needs its own FK/`NOT NULL`
enforcement independent of what the generic auditing listener populates, and the issue
names it explicitly as a required schema column — same reasoning V22 used for keeping
`reactivation_request.reviewed_by`/`requested_by` as explicit domain columns alongside
(not replaced by) `Auditable`'s generic provenance fields.

## 9. Backend design

**Services**
- `AttendanceMarkingService` — the mark/upsert endpoint. Resolves
  `CourseLookupApi.resolveLessonOwnership(sessionId)` first (empty → `404`, tenant-scoped
  by construction), runs `AttendanceAccessGuard`, validates each submitted `studentId`
  against `EnrollmentAccessApi.listCurrentlyEnrolledStudentIds(courseId)` (new method,
  below) before upsert, derives `courseId` server-side (never client-supplied), stamps
  `markedBy`/`markedAt` from the authenticated context.
- `AttendanceReportService` — three reads: (a) session roster + existing marks for the
  Mark Attendance screen; (b) a student's own history (owner-only, `principal.userId()`
  only, bypasses `PermissionCheckService` entirely — mirrors
  `EnrollmentQueryService.requireStudent()`); (c) teacher-own-courses or tenant-wide
  report depending on caller role, filterable by course/date range.

**`AttendanceAccessGuard`** (in `support`), mirroring `CourseAccessGuard`'s combined
staff-matrix-or-ownership shape, adapted to take a `LessonOwnership` (never a foreign
domain's JPA entity):

```java
public void requireSessionAccess(LessonOwnership ownership, PermissionAction action) {
    AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
    if ("TEACHER".equals(principal.role())) {
        if (!ownership.teacherId().equals(principal.userId())) {
            throw new AccessDeniedException("You do not have permission to perform this action");
        }
        return;
    }
    permissionCheckService.requirePermission(DomainArea.ATTENDANCE, action);
}
```

No `DELETE` action is in scope — mark/upsert is `CREATE_EDIT`, reports are `VIEW`. A
future delete/void endpoint is undesigned and out of scope for this plan.

**New cross-module API surface (both additive, no ADR required — see §21)**

1. `course-management` — **no new method needed.**
   `CourseLookupApi.resolveLessonOwnership(UUID lessonId)` (already returns
   `LessonOwnership(lessonId, moduleId, courseId, teacherId, coursePublished)`) covers
   marking; `getTeacherId(UUID courseId)` covers any per-course teacher check needed for
   reports.

2. `enrollment-management` — **real, confirmed gap.** No inverse-direction lookup
   ("which students are currently enrolled in course X") exists anywhere in
   `EnrollmentAccessApi`. Add, additively:

   ```java
   /**
    * @return the studentId of every CURRENT (supersededAt IS NULL, not
    * expired) enrollment for courseId — the inverse of
    * findAllCurrentByStudentId, scoped through the same tenant context as
    * every other method on this interface.
    */
   List<UUID> listCurrentlyEnrolledStudentIds(UUID courseId);
   ```

   backed by a new `EnrollmentRepository` default method mirroring
   `findAllCurrentByStudentId`'s exact `Specification` shape, keyed by `courseId`
   instead of `studentId`, with the same `supersededAt IS NULL` scoping (and,
   consistent with §3's precondition, excluding an expired current row). This is
   additive only — no existing method signature changes — but per root `CLAUDE.md`'s
   "approved API contracts" change-control item, it still needs normal PR
   review/sign-off from whoever owns `enrollment-management`, not a unilateral add.

3. **Student display-name resolution (implementation-level detail, not a blocker).**
   No `user-management` `api` package exists yet for cross-domain student-name lookup.
   `identity-access-service`'s existing `UserProvisioningApi.findTenantUserSummaries`
   (already shipped, foundational) can resolve `email`/`role`/`status` per `tenant_user`
   id for roster/report display; if a full display name is wanted and `user-management`
   doesn't expose one by the time this is implemented, fall back to the same
   `shortId`-style convention already used elsewhere in this codebase
   (`frontend/src/lib/format.ts`) rather than inventing a new cross-domain dependency.

**Confirms no ADR is required**: `attendance-management` is already named in
`.claude/rules/architecture.md`'s confirmed domain list and already has a
`module-catalog.md` entry. No new domain, no microservice, no separate datastore — uses
shared Postgres via `TenantAwareRepository`/`TenantOwned` like every other tenant-owned
table.

## 10. API contract

Envelope: `ApiResponse<T>` / `PageResponse<T>` per `docs/api/course-management.md`'s
already-established convention (identical shape, not repeated here). This section is
the input for a dedicated `review-api-contract` pass before/at the start of
implementation — exact param names may be refined there, but the shapes/auth model
below are fixed.

### `GET /api/v1/attendance/sessions/{sessionId}/roster`

Roster + existing marks for one session, for the Mark Attendance screen. Auth:
Teacher-ownership-or-`DomainArea.ATTENDANCE`/`VIEW`. `404` for a cross-tenant or
Teacher-not-owning `sessionId`. Response: `{ courseId, sessionId, roster: [{ studentId,
status: "PRESENT"|"ABSENT"|"LATE"|null }] }` (`null` = not yet marked).

### `POST /api/v1/attendance/sessions/{sessionId}/records`

Mark/upsert attendance for one or more students in one session. Auth:
Teacher-ownership-or-`DomainArea.ATTENDANCE`/`CREATE_EDIT`. Request:
`{ marks: [{ studentId, status }] }`. Each row is validated against the current roster
server-side; a `studentId` not on the roster is rejected for that row (batch-partial
failure — see §13). **Success — `200`**, returns a per-row batch outcome (`{ studentId, success, record, reason }[]`
— exactly one of `record`/`reason` non-null per row, matching §13's batch-partial contract
below), not a flat list of saved rows. `404` for a cross-tenant/unowned `sessionId` before any
row is processed.

### `GET /api/v1/attendance/my`

Student's own history. `hasRole('STUDENT')`, owner-only, no id param. Query params:
`courseId` (optional), `from`/`to` date range (optional), standard pagination.
**Success — `200`** (`PageResponse<AttendanceRecordResponse>`).

### `GET /api/v1/attendance/reports`

Teacher-own-courses-or-tenant-wide-staff report, role-dispatched server-side (no role
param). Query params: `courseId` (optional — for a Teacher, intersected with their owned
set, never trusted alone; for staff, any tenant course), `from`/`to`, standard
pagination. **Success — `200`** (`PageResponse<AttendanceRecordResponse>`).

**`AttendanceRecordResponse`**: `{ id, courseId, sessionId, studentId, status, markedBy,
markedAt, createdAt, updatedAt }`.

## 11. Frontend screens

Route-group convention matches existing modules exactly.

| # | Screen | Route | Access |
|---|---|---|---|
| 1 | Teacher Mark Attendance | `app/(teacher)/teacher/attendance/mark/page.tsx` | Teacher, own courses only |
| 2 | Teacher Attendance Reports | `app/(teacher)/teacher/attendance/reports/page.tsx` | Teacher, own courses only |
| 3 | Student My Attendance | `app/(student)/student/attendance/page.tsx` | Student, own records only |
| 4 | Tenant Admin Attendance Reports | `app/(tenant-admin)/tenant-admin/attendance/reports/page.tsx` | Tenant Admin, Attendance Operator, Read-only Auditor (nav gated by a new `canViewAttendanceReports(role)` helper in `lib/auth/permissions.ts`; enforcement is backend-only, per baseline) |
| 5 | Staff Mark Attendance | `app/(tenant-admin)/tenant-admin/attendance/mark/page.tsx` | Tenant Admin, Attendance Operator — same Mark Attendance UI as #1 but with a tenant-wide (not ownership-filtered) course selector |

Screen #5 resolves the gap the `ui-ux-reviewer` flagged: the permission matrix already
grants Attendance Operator `CREATE_EDIT`, so a marking surface must exist for that role
somewhere — this is a direct, mechanical consequence of §2's existing grant, not a new
business decision.

Shared component: `frontend/src/components/attendance/attendance-status-chip.tsx` — a
read-only display chip (Present/Absent/Late, using the vocabulary and icon/color
mapping already defined in `docs/ui-ux/component-library-spec.md` §2.10 — no new design
tokens needed) and a keyboard-operable 3-way segmented-control variant for the Mark
Attendance roster rows (not a `<select>`, not free text — one click/tap per row).
`Excused` exists in the design-system vocabulary but is explicitly out of this MVP's
scope and must not be offered as an option.

**States per screen** (via the existing shared `QueryStateBoundary`, not hand-rolled):
- Loading: `LoadingState`; skeleton table rows for screen #4.
- Empty (two distinct variants everywhere reports are shown, per `.claude/rules/ui-ux.md`
  §3 — never the same generic copy for both):
  - Zero data ever: *"No attendance records yet"* (+ role-appropriate next-step copy).
  - Filtered to zero: *"No sessions match the selected date filter"* + a clear-filters
    action.
  - Screen #1/#5 (Mark Attendance) additionally distinguish "no lessons exist for this
    course yet" from "lesson selected but the roster is empty" — neither reuses the
    reports-screen empty copy above.
- Error: `ErrorState` with retry.
- Permission-denied: server-verified 403 only (never a client role guess) — the
  realistic case is screen #4 for a staff sub-role without `DomainArea.ATTENDANCE`
  reaching the route by direct URL.

**Responsive strategy** (per `.claude/rules/ui-ux.md` §5's two archetypes):
- Screens #1, #2, #3, #5 — consumer-style, mobile-first: single-column card/list
  layouts, filter bars collapse to a bottom sheet on mobile, no `DataTable`.
- Screen #4 — admin-heavy: reuses the existing shared
  `frontend/src/components/ui/data-table.tsx` unchanged (card-view fallback below `md`
  it already provides), status column renders via the shared Status Chip, never a bare
  colored cell.

**Accessibility**: the Mark Attendance 3-way control per roster row is a real
keyboard-operable group (`role="radiogroup"` + labeled options, not three unlabeled
icon buttons); save success/failure uses `aria-live="polite"`/`role="alert"`
respectively, including per-row surfacing of a partial-batch failure (see §13); every
filter control has a real associated label.

**Role-scope visual unambiguity**: no tenant switcher anywhere (Tenant Admin screens
never have one, per existing convention); the course selector on every Teacher screen is
populated only from a backend endpoint already filtered to that teacher's own courses,
never an unfiltered list filtered client-side.

## 12. Validation rules

- `status` must be one of `PRESENT`/`ABSENT`/`LATE` — enforced by Bean Validation on the
  request DTO and by the DB `CHECK` constraint as a second line of defense.
- `sessionId` must resolve (via `CourseLookupApi.resolveLessonOwnership`) to a lesson in
  the caller's own tenant — anything else is `404` before any other validation runs.
- `courseId` is **never** accepted from the client for the mark endpoint — always
  derived server-side from the resolved lesson's owning course (§8/§9).
- Every `studentId` in a mark request must be present in
  `EnrollmentAccessApi.listCurrentlyEnrolledStudentIds(courseId)` at request time — a
  `studentId` not on that list is rejected for that row, not silently accepted or
  silently dropped without being reported back to the caller.
- `marked_by` is always `AuthenticatedPrincipalHolder.get().userId()` — never a
  client-supplied "on behalf of" field.
- Report date-range filters: `from` must not be after `to` (client-side Zod convenience
  check + server-side rejection).
- Pagination params follow the existing platform convention (`page`/`size`/`sort`, size
  server-clamped to 100).

## 13. Error cases

| Case | Code | Status |
|---|---|---|
| `sessionId` doesn't resolve in caller's tenant | `NOT_FOUND` | `404` |
| Teacher's `sessionId` resolves but course isn't theirs | `FORBIDDEN` | `403` |
| Staff caller lacks `DomainArea.ATTENDANCE`/`CREATE_EDIT` (mark) or `/VIEW` (report) | `FORBIDDEN` | `403` |
| Read-only Auditor attempts to mark | `FORBIDDEN` | `403` |
| A submitted `studentId` is not on the course's current roster | `VALIDATION_ERROR` (per-row, batch-partial) | `400` |
| Invalid `status` value | `VALIDATION_ERROR` | `400` |
| Malformed/non-UUID path variable | `VALIDATION_ERROR` | `400` |
| Teacher report `courseId` filter names a course outside caller's owned set | `FORBIDDEN` | `403` |
| Any endpoint, cross-tenant target | `NOT_FOUND` | `404` |

**Batch-partial marking**: a single `POST .../records` call may name several students;
one invalid row (not on roster, bad status) must not silently fail the whole batch nor
silently succeed on the valid rows without reporting the rejected ones. Response must
enumerate per-row outcomes (success/rejected+reason) so the frontend can surface exactly
which roster rows failed, per §11's accessibility requirement.

## 14. Tenant-isolation rules

- `attendance_record.tenant_id NOT NULL REFERENCES tenant(id)`, never nullable.
- Every cross-table reference (`course_id`, `session_id`, `student_id`, `marked_by`) is
  a composite `(tenant_id, ...)` FK into the referenced tenant-owned table — never a
  bare FK on the child id alone.
- Every index leads with `tenant_id` (§8).
- `AttendanceRecordRepository` extends `TenantAwareRepository` — tenant filtering is
  structural (injected from the trusted `TenantContext`), never an ad hoc `WHERE`
  clause repeated per query, and no method accepts a caller-supplied `tenant_id`.
- The teacher-report and tenant-wide-report endpoints are named explicitly as
  bulk/reporting-endpoint isolation-bypass risks per `.claude/rules/tenancy.md` — both
  require their own dedicated cross-tenant test (§18), not just "the query has a tenant
  filter" as sufficient evidence.
- The roster check (`listCurrentlyEnrolledStudentIds`) is itself tenant-scoped through
  the same trusted `TenantContext` as every other `EnrollmentAccessApi` method — no
  overload accepts a caller-supplied tenant id.

## 15. Security rules

(Full detail from the `security-reviewer` pass; summarized here.)

- **AuthN/authZ**: ownership-vs-staff-matrix split via `AttendanceAccessGuard`, matching
  `CourseAccessGuard`/`MaterialAccessGuard` precedent exactly (§9).
- **Enumeration/IDOR**: cross-tenant ids are always `404` (structurally invisible via
  the tenant-scoped lookup). Same-tenant Teacher-not-assigned is `403` — an accepted,
  already-established codebase convention (teachers already have legitimate visibility
  into their own tenant's course existence), not a new leak. A Student-facing endpoint
  with no id param (as designed) has no enumeration surface by construction.
- **Roster-bypass risk (highest-severity item)**: the mark endpoint must resolve the
  currently-enrolled student set server-side via the new `EnrollmentAccessApi` method
  and reject any submitted `studentId` outside it — a client-supplied student list is
  never trusted at face value, and an expired (non-current) enrollment does not count as
  "currently enrolled." No insert path may bypass this check.
- **Cross-module boundary**: `attendance-management` may not inject
  `EnrollmentRepository`/`CourseRepository` or import their entities directly — the
  roster and lesson-ownership checks go exclusively through `EnrollmentAccessApi`/
  `CourseLookupApi`'s `api` packages (§9).
- **`marked_by` provenance**: always server-resolved from the authenticated principal,
  never a client-supplied field — otherwise spoofable.
- **Non-applicable surfaces**: this module has no file upload, no protected video/media
  delivery, and no device-registration/session surface — the Device/Video/Upload
  sections of `.claude/rules/security.md` are confirmed N/A.
- **No multi-tenancy or auth-architecture change**: this design consumes the existing
  `AuthenticatedPrincipalHolder`/`PermissionCheckService`/`TenantAwareRepository`
  pattern throughout — no new tenant-resolution mechanism, no ADR trigger.

## 16. Audit requirements

**None.** Attendance marking is not on `.claude/rules/security.md`'s mandatory-audit-
action list (price changes, payment approvals/rejections, device resets, access/expiry
extensions, reactivation approvals, material/course content deletions, settlement
amount changes, impersonation), and `10-attendance.md` §9 states this outright. No audit
obligation is added for marking or re-marking. This conclusion was independently
confirmed by the `security-reviewer` pass, not assumed.

If a future requirement adds a distinct "correction" action separate from the routine
upsert-in-place re-mark modeled here, that would start to resemble a
mutable-record-with-history concern and should get its own review at that time — not
retrofitted silently now.

## 17. Payment impact

**None.** This module reads no payment/order/ledger state and writes nothing to any
payment-adjacent table. Its only cross-domain read dependency
(`EnrollmentAccessApi.listCurrentlyEnrolledStudentIds`) is a pure enrollment-currency
check, not a payment check — enrollment activation itself is unaffected, unchanged, and
not re-implemented or re-derived here. No `.claude/rules/payments.md` rule is
implicated. `payment-ledger-specialist` was correctly not invoked for this plan.

## 18. Tests

**Backend JUnit (service-layer)** — `AttendanceMarkingServiceTest`,
`AttendanceReportServiceTest`:
- Marking upserts an existing (session, student) row in place rather than duplicating.
- `marked_by`/`marked_at` always come from the trusted context, never the request body.
- Guard rejects a Teacher marking a lesson outside their owned course; allows one within.
- Staff with `ATTENDANCE`/`CREATE_EDIT` marks regardless of course ownership (a distinct
  code path from the Teacher-ownership branch — needs its own proof).
- `READ_ONLY_AUDITOR` cannot mark.
- Guard rejects marking a student not on the course's current-enrollment roster,
  including a student with only an `EXPIRED` (not current) enrollment.
- Guard rejects a `sessionId`/`courseId` mismatch (session's real course ≠ claimed
  `courseId`, if a variant of the API ever accepts one — otherwise this proves the
  server-derivation path in §12 is exercised).
- Report queries scope correctly per role (student-self-only, teacher-own-courses-only,
  staff-tenant-wide).

**Backend Testcontainers/integration** — `AttendanceMarkingIntegrationTest`,
`AttendanceReportIntegrationTest`, `AttendanceCrossTenantIntegrationTest` (dedicated
file, per this codebase's per-domain cross-tenant-suite convention):
- Teacher marks own session → persists correctly (all columns).
- Teacher marks outside assignment → `403`, zero rows written.
- Re-marking the same (session, student) updates in place → row count stays 1.
- Attendance Operator marks any course in their tenant via staff permission.
- Read-only Auditor cannot mark (zero rows written).
- Marking a student not on the roster → rejected, zero rows written; same for an
  expired-enrollment student specifically.
- Student's own-history read never contains another student's rows, same tenant.
- **Multi-teacher fixture**: Teacher A's report never contains Teacher B's course
  records, same tenant.
- Tenant-wide staff report correctly includes both teachers' courses (proving the
  teacher-scoped-vs-tenant-wide distinction is real).
- **Cross-tenant suite**: mark against another tenant's `sessionId` → `404`, zero rows;
  Attendance Operator of tenant A reading/marking tenant B's attendance → `404`; a
  student's/teacher's/staff's report never returns another tenant's rows even under
  colliding course names (mirrors the existing colliding-name fixture pattern already
  used elsewhere in this codebase); a `courseId`+`sessionId` pair spanning two different
  tenants is rejected, not silently accepted because each id individually resolves.

**Playwright E2E**:
- Teacher marks own session end-to-end (Status Chip control, submit, reload, verify
  persisted).
- Direct URL/id substitution for another teacher's session is blocked server-side
  (assert the intercepted API response is 403/404, not just that the UI hides a button).
- Same test repeated cross-tenant.
- Both distinct empty states render correctly and independently (zero-data vs.
  filtered-to-zero).
- Mobile-first Student/Teacher views and the responsive admin data-table view are each
  tested at a narrow viewport (375×667 or equivalent).
- A read-only staff role sees the report with no mark/edit controls, and a direct
  replayed POST still gets rejected server-side.

**Explicitly deferred/not needed**: no test asserting an audit-log row is written on a
successful mark (§16); no Zoom-sync test (Phase 2, not built).

## 19. Documentation changes

- `docs/architecture/` — new short section (or addition to an existing
  cross-domain-communication doc) describing `attendance_record`'s tenant+course/session
  composite-index shape and the new `EnrollmentAccessApi.listCurrentlyEnrolledStudentIds`
  cross-module read.
- `docs/api/attendance-management.md` — new file, written via the `review-api-contract`
  skill before/at the start of frontend work (per this project's standing process gap
  lesson from MVP-008 — do not repeat that gap here), covering the four endpoints in §10.
- `docs/api/enrollment-management.md` — append the new
  `listCurrentlyEnrolledStudentIds` method to `EnrollmentAccessApi`'s documented surface.
- `docs/ui-ux/screen-map.md` — add the five screens from §11 to the relevant portal
  sections.
- `docs/requirements/specifications/10-attendance.md` — update the "Open decisions"
  section to record both decisions confirmed in this planning session (session-scope =
  lesson id; re-mark = upsert-in-place), superseding the two open bullets currently
  there.
- `docs/requirements/open-decisions.md` — add a new dated entry under a new "§20
  Attendance (MVP-016)" heading recording the two confirmed decisions and the residual
  open items from §21 below, per this log's established per-module convention (§15–§19
  precedent).

## 20. Implementation order

Per root `CLAUDE.md`'s standing development workflow (plan → backend → backend tests →
frontend → frontend/E2E tests → security/tenant/integration review → docs → one logical
commit), sequenced concretely for this module:

1. **`enrollment-management` API extension** — add
   `EnrollmentAccessApi.listCurrentlyEnrolledStudentIds` + the backing repository
   method (§9 item 2). Small, isolated, unlocks everything else; get this reviewed/
   merged first since it's a change to another domain's approved contract.
2. **Backend**: `V25__create_attendance_management_schema.sql` → `AttendanceRecord`
   domain/repository → `AttendanceAccessGuard` → `AttendanceMarkingService` →
   `AttendanceReportService` → controllers/DTOs.
3. **Backend tests**: JUnit + Testcontainers + the dedicated cross-tenant suite (§18) —
   run and green before frontend starts.
4. **API contract review** (`review-api-contract` skill) against the shipped
   controllers — produce `docs/api/attendance-management.md` before frontend work
   begins (explicitly avoiding the MVP-008 process gap noted in §19).
5. **Frontend**: shared `AttendanceStatusChip` → Teacher Mark Attendance (#1) → Teacher
   Reports (#2) → Student My Attendance (#3) → Tenant Admin Reports (#4) → Staff Mark
   Attendance (#5).
6. **Frontend/E2E tests** (§18).
7. **Security, tenant-isolation, and integration reviews** (the corresponding skills) —
   re-verify the roster-bypass guard and cross-tenant suite specifically.
8. **Documentation** (§19).
9. **One logical commit** per root `CLAUDE.md`'s workflow (or the smallest reasonable
   split consistent with `.claude/rules/git-workflow.md` — e.g. the enrollment-management
   API addition as its own commit, backend attendance module as a second, frontend as a
   third).

## 21. Risks and unresolved decisions

**Decided this planning session (not left open):**
- Session-equivalent scope = `course_lesson.id`, no new `class_session` table.
- Re-marking the same (session, student) is an in-place upsert, not a `409` conflict.

**Documentation inconsistency, resolved by precedence (not a business decision):**
- The issue's own schema requirement lists `LATE` in the MVP `CHECK` constraint, while
  `module-catalog.md` tags "late/early tracking" as Phase 2. Resolved: the issue
  (authoritative build request) wins for the bare `LATE` status **value**;
  `module-catalog.md` still correctly scopes out any richer arrival-time feature to
  Phase 2. Both docs are right once split this way — recorded in §19's doc-update list,
  not silently resolved either way beyond this note.

**Accepted MVP limitations (flagged, not silently absorbed):**
- **Recurring-session ambiguity**: a lesson reused across multiple real calendar
  occurrences (e.g. a weekly class attached to one `course_lesson`) cannot be
  distinguished by this schema — the upsert-in-place design means a second week's
  marking overwrites the first week's row rather than creating a new occurrence's
  record. Fine for courses whose lessons are genuinely 1:1 with one attendance event;
  a real limitation otherwise. If this proves unworkable in practice, the fix is a
  schema migration adding an explicit occurrence/date discriminator — not a workaround
  layered on top of the current design.
- **Historical roster accuracy gap**: `listCurrentlyEnrolledStudentIds` (and
  `EnrollmentAccessApi` generally) is computed **live** — there is no "was this student
  enrolled on date X" query. A report re-run after a student unenrolls will not show
  them even for a past date they attended. Accepted as an MVP behavior (reports always
  reflect current enrollment, not historical enrollment-at-the-time) — flagged
  explicitly rather than silently absorbed, since it's a genuine gap in the currently
  available API surface, not a deliberate design choice made for this module.
- **Correction has no separate history**: the upsert-in-place re-mark overwrites the
  prior status with no `previous_status`/`changed_at` trail. No audit requirement was
  specified (§16), so none is proposed — if an audit trail on corrections is later
  wanted, that is an additive `attendance_change_log` table, not a change to this
  migration.

**Known pre-existing gap, unchanged (not new to this module):**
- Teacher Assistant has no functional ownership-resolution path anywhere in this
  codebase (`MaterialAccessGuard`'s own javadoc already documents this as non-functional
  for content-management) — `attendance-management` inherits the same gap by
  deliberately not building TA support at all (§2), consistent with, not a regression
  from, existing precedent.

**Deferred to a normal PR review, not an ADR:**
- The new `EnrollmentAccessApi.listCurrentlyEnrolledStudentIds` method is additive and
  self-authorized by that interface's own "add a narrowly-scoped method" javadoc intent,
  but per root `CLAUDE.md` "approved API contracts" is change-controlled — route it
  through normal review by whoever owns `enrollment-management`, not as a unilateral
  add buried inside the attendance-management PR.

**Confirmed non-decisions (explicitly not in scope, no sign-off needed):**
- No ADR is required for this module — it fits the already-confirmed domain list,
  requires no new datastore, no microservice, and no multi-tenancy/auth-architecture
  change.
- No payment/ledger impact (§17) — independently re-verified against the shipped code by a
  dedicated `payment-ledger-specialist` post-ship review pass (zero findings).
- No audit-logging gap (§16) — independently re-verified against `.claude/rules/security.md`'s
  actual mandatory-audit-action list by a post-ship `security-reviewer` pass, not just assumed
  from this document's own conclusion.

## 22. Post-ship addendum (dated)

Recorded after a comprehensive multi-agent review of the completed implementation
(solution-architect, security-reviewer, database-architect, qa-test-engineer, ui-ux-reviewer,
payment-ledger-specialist), per this project's `.claude/rules/git-workflow.md`/root `CLAUDE.md`
process — the review found the implementation itself sound (no Critical/High correctness,
security, or data-integrity findings across any lane) but flagged two process/governance gaps,
both closed by this addendum plus the accompanying documentation updates it references:

**1. `CourseLookupApi.getTeacherIdsByCourseId(Set<UUID> courseIds)` — undisclosed addition to
`course-management`'s approved API contract, now disclosed and reviewed here.**

§9 item 1 of this plan states, in bold, "no new method needed" for `course-management` — that
was true at the time of planning. During implementation hardening (after the initial backend
review round), `AttendanceReportService`'s Teacher-report path was found to make one
`CourseLookupApi.getTeacherId(courseId)` call per candidate course id — an N+1 query pattern for
a tenant with many courses. The fix added one new, narrowly-scoped, read-only, additive method
to `course-management.api`:

```java
Map<UUID, UUID> getTeacherIdsByCourseId(Set<UUID> courseIds);
```

backed by a single batched `findAllById` read, tenant-scoped identically to every other method
on that interface (no overload accepts a caller-supplied tenant id). No existing method
signature on `CourseLookupApi` changed.

This addition shipped without the same explicit disclosed-review step this plan's own §20
mandated for the analogous `enrollment-management` addition ("needs normal PR review/sign-off
from whoever owns `enrollment-management`, not a unilateral add") — a post-ship
`solution-architect` review correctly flagged this as scope creep against a change-controlled
"approved API contract" (root `CLAUDE.md`), even though the method itself was independently
verified safe by both that review and a separate `security-reviewer` pass (tenant-scoped,
additive, no existing signature touched, no security/tenant-isolation concern).

**Resolution recorded here, serving as the disclosure/sign-off this addition needed:** the
method is additive, read-only, tenant-scoped, has no consumer outside
`AttendanceReportService`, and does not change any existing `course-management` contract or
behavior. It is documented in `docs/api/attendance-management.md`'s "Cross-module contract"
section and `docs/architecture/modular-monolith.md` §4's worked example, and carried forward in
`docs/requirements/open-decisions.md` §20. A direct unit/integration test for
`CourseLookupApiImpl.getTeacherIdsByCourseId` was added to `course-management`'s own test
package (previously it was exercised only indirectly through `attendance-management`'s tests).

**2. Documentation debt — all six updates named in §19 were missing at first review; now
closed.** `docs/api/attendance-management.md` (new), `docs/api/enrollment-management.md`
(appended), `docs/architecture/modular-monolith.md` (worked example added), `docs/ui-ux/screen-map.md`
(all 5 screens marked shipped, the previously-uncatalogued Staff Mark Attendance screen added),
`docs/requirements/specifications/10-attendance.md` (Resolved-decisions section added, two new
open items surfaced), and `docs/requirements/open-decisions.md` §20 (added) — all landed in the
same follow-up session as this addendum.
