# attendance-management — API Contract

Covers MVP-016 "Attendance" (`com.lms.attendancemanagement`) — manual Present/Absent/Late
marking against a course lesson (the session-equivalent unit at this MVP) and the three
report/history reads. Written after the fact once the module was reviewed, following the
same "process gap" this project's other API contract files record (`docs/api/payment-management.md`,
`docs/api/course-management.md`, `docs/api/enrollment-management.md`): the plan
(`docs/plans/MVP-016 Attendance.md` §10) sketched a draft contract before implementation,
but the finalized doc was never produced until a post-ship review found the gap. This file
reflects the actual shipped backend (`AttendanceController`), not the plan's pre-implementation
draft — the one deviation from that draft is called out explicitly below.

## Response envelope

Every endpoint returns `com.lms.common.api.ApiResponse<T>` — see
`docs/api/identity-access-service.md`'s "Response envelope" section for the exact shape
(`success`/`data`/`error`/`timestamp`/`traceId`); identical here, not repeated. The two paginated
reads (`GET /my`, `GET /reports`) wrap `com.lms.common.api.PageResponse<T>` inside that envelope,
matching `docs/api/course-management.md`'s established `PageResponse` shape.

## Auth requirements

- Every endpoint below requires a valid `Authorization: Bearer <accessToken>` header.
  `@PreAuthorize` at the controller (`isAuthenticated()` on three endpoints, `hasRole('STUDENT')`
  on `/my`) is a coarse gate only — the real authorization check happens in the service layer
  (`AttendanceMarkingService`/`AttendanceReportService`, via `AttendanceAccessGuard` and
  `PermissionCheckService`), mirroring `course-management`'s `CourseAccessGuard`/
  `MaterialAccessGuard` precedent and every other domain's established discipline in this
  codebase.
- `tenantId` and `markedBy` are never accepted from the client on any request body — `tenantId`
  is always resolved from `TenantContext`; `markedBy` is always
  `AuthenticatedPrincipalHolder.get().userId()`. `courseId` is never accepted from the client on
  the mark endpoint either — see that endpoint's own section below.

## Authorization model

`AttendanceAccessGuard.requireSessionAccess(LessonOwnership, PermissionAction)` is the single
shared guard behind both the roster read and the mark endpoint:

- If the caller's role is `TEACHER`, the guard requires `ownership.teacherId() ==
  principal.userId()` — a Teacher may only read/mark a session within a course they own.
- Every other role falls through to `PermissionCheckService.requirePermission(DomainArea.ATTENDANCE,
  action)` — the already-shipped RBAC matrix grants `VIEW, CREATE_EDIT` to Tenant Admin/Institute
  Owner and Attendance Operator, `VIEW` only to Read-only Auditor, and nothing to any other staff
  sub-role (Finance Staff, Course Coordinator, Student Support, Content Manager, Exam Manager) or
  to Teacher Assistant (out of scope for this MVP — see the plan's §2).

A cross-tenant or genuinely nonexistent `sessionId` is always `404` — `CourseLookupApi
.resolveLessonOwnership(sessionId)` returns empty for it (tenant-scoped by construction), so the
guard is never even reached. A same-tenant Teacher who simply doesn't own the session's course
gets `403` (an accepted, already-established codebase convention — Teachers already have
legitimate visibility into their own tenant's course existence, so this isn't a new enumeration
leak). Both cases are deliberately **indistinguishable from a caller's point of view beyond the
status code itself** — neither response body reveals whether a `sessionId` exists in a different
tenant vs. doesn't exist at all.

`GET /api/v1/attendance/my` bypasses this guard entirely — it's `hasRole('STUDENT')`, owner-only,
with `AttendanceReportService.getMyHistory` independently re-deriving `principal.userId()`
(defense-in-depth, not relying on `@PreAuthorize` alone), mirroring
`EnrollmentQueryService`'s owner-only pattern.

## Endpoints

### `GET /api/v1/attendance/sessions/{sessionId}/roster`

Teacher-ownership-or-staff-`ATTENDANCE`/`VIEW`. Returns the currently-enrolled roster for the
session's course plus any existing marks for this session, so a Mark Attendance UI can
pre-populate already-marked statuses.

**Success — `200`** (`ApiResponse<AttendanceRosterResponse>`):

```jsonc
{
  "courseId": "...",
  "sessionId": "...",
  "roster": [
    { "studentId": "...", "status": "PRESENT" },  // status is null when not yet marked
    { "studentId": "...", "status": null }
  ]
}
```

**`404`** for a cross-tenant or genuinely nonexistent `sessionId` (anti-enumeration, see
"Authorization model" above). **`403`** for a same-tenant Teacher not owning the session's course,
or a staff caller lacking `ATTENDANCE`/`VIEW`.

### `POST /api/v1/attendance/sessions/{sessionId}/records`

Teacher-ownership-or-staff-`ATTENDANCE`/`CREATE_EDIT`. Mark/upsert attendance for one or more
students in this session. Request body:

```jsonc
{ "marks": [{ "studentId": "...", "status": "PRESENT" }] }
```

`marks` is `@NotEmpty`, capped at `@Size(max = 500)` entries (well beyond any realistic single
class-session roster — bounds the `@Transactional` service call's worst-case duration). Each
entry's `status` is `@NotNull`, one of `PRESENT`/`ABSENT`/`LATE` (never `EXCUSED` — out of this
MVP's scope). There is **no `courseId` field on this request** — `courseId` is always derived
server-side from `CourseLookupApi.resolveLessonOwnership(sessionId).courseId()`, never accepted
from the client (the plan's §12 "roster-bypass risk," this codebase's highest-severity item for
this module).

Every submitted `studentId` is checked against `EnrollmentAccessApi
.listCurrentlyEnrolledStudentIds(courseId)` at request time — a `studentId` not on that list
(including one whose only enrollment is expired, not current) is rejected **for that row only**,
never silently dropped and never failing the whole batch.

A re-mark of an already-marked (session, student) pair is an **upsert in place** — the existing
`attendance_record` row's `status`/`marked_by`/`marked_at` are updated, never a second row
inserted and never a `409` returned (per the product-owner decision recorded in the plan's
grounding note). The unique constraint `uq_attendance_record_tenant_session_student` (`tenant_id,
session_id, student_id`) enforces this invariant at the schema level; a concurrent double-submit
race is resolved by a native `ON CONFLICT` upsert, not by application-level locking alone (see
`AttendanceMarkingConcurrencyIntegrationTest`).

**Success — `200`** — returns a per-row batch outcome, `ApiResponse<AttendanceMarkResultResponse[]>`:

```jsonc
[
  { "studentId": "...", "success": true, "record": { /* AttendanceRecordResponse, below */ }, "reason": null },
  { "studentId": "...", "success": false, "record": null, "reason": "Student is not currently enrolled in this course." }
]
```

Exactly one of `record`/`reason` is non-null per row, matching `success`. **This is a deviation
from the plan's §10 draft**, which loosely described the response as "the resulting saved rows" —
the shipped shape is the plan's own §13 "batch-partial marking" contract made concrete: a single
call naming several students never silently fails the whole batch nor silently succeeds on the
valid rows without reporting the rejected ones, so every row's outcome — success or a specific
rejection reason — is always reported back, individually.

**`404`** for a cross-tenant/nonexistent `sessionId`, checked before any row is processed (no
partial writes on an unauthorized session). **`403`** for a same-tenant Teacher not owning the
course, or a staff caller lacking `ATTENDANCE`/`CREATE_EDIT` (a Read-only Auditor's mark attempt
is rejected here regardless of any stale client UI).

### `GET /api/v1/attendance/my`

`hasRole('STUDENT')`, owner-only, no id parameter anywhere on this endpoint. Query params:
`courseId` (optional, `UUID`), `from`/`to` (optional, **`java.time.Instant`** — full ISO-8601
timestamps, e.g. `2026-01-15T00:00:00Z`, not bare `YYYY-MM-DD` dates), standard Spring `Pageable`
params (`page`/`size`/`sort`, default `size=20`, `sort=markedAt,DESC`).

**Success — `200`** → `ApiResponse<PageResponse<AttendanceRecordResponse>>` — only rows where
`student_id` equals the caller's own id and `tenant_id` equals the caller's resolved tenant.

### `GET /api/v1/attendance/reports`

Role-dispatched server-side, no role param: a Teacher's request is backend-derived to their own
owned courses only (a `courseId` filter, if supplied, is intersected with that owned set, never
trusted alone or used to bypass it); Tenant Admin/Attendance Operator/Read-only Auditor get a
tenant-wide read with no course/teacher restriction. Same query params/pagination default as
`GET /my`.

**Success — `200`** → `ApiResponse<PageResponse<AttendanceRecordResponse>>`.

## `AttendanceRecordResponse` shape

Returned inside every `PageResponse` above and as each successful row's `record` on the mark
endpoint:

```jsonc
{
  "id": "...",
  "courseId": "...",
  "sessionId": "...",     // == course_lesson.id — no separate class_session table at this MVP
  "studentId": "...",
  "status": "PRESENT",    // PRESENT | ABSENT | LATE
  "markedBy": "...",      // always the marking actor's own tenant_user id, never client-supplied
  "markedAt": "2026-01-15T09:32:00Z",
  "createdAt": "2026-01-15T09:32:00Z",
  "updatedAt": "2026-01-15T09:32:00Z"
}
```

## Cross-module contract (not REST — recorded here since no other file documents it)

This module depends on two `api`-package methods it does not own, both consumed strictly through
their owning domain's `api` interface — never a foreign repository/entity import:

- `CourseLookupApi.resolveLessonOwnership(UUID lessonId)` — already existed prior to this module
  (added for `content-management`, see `docs/architecture/modular-monolith.md` §4's worked
  example); reused unchanged here to resolve a session's owning course/teacher for the
  authorization guard.
- `CourseLookupApi.getTeacherIdsByCourseId(Set<UUID> courseIds)` — **new**, added during this
  module's post-review hardening (not originally scoped in the plan's §9, which stated no new
  `course-management` method was needed — see the plan's own dated addendum for the full
  rationale). A batched, read-only lookup used by `AttendanceReportService` to resolve which of a
  set of course ids belong to the requesting Teacher, avoiding an N+1 query when narrowing the
  Teacher-report course set.
- `EnrollmentAccessApi.listCurrentlyEnrolledStudentIds(UUID courseId)` — **new**, the plan's §9
  item 2 (disclosed and reviewed as part of this module's own implementation). Returns the
  `studentId` of every currently-enrolled (not superseded, not access-expired) student in
  `courseId` — the roster-bypass check behind the mark endpoint, and the source roster for
  `GET .../roster`. Tenant-scoped exactly like every other method on this interface — no overload
  accepts a caller-supplied tenant id.

Both interfaces resolve tenant identity exclusively from the trusted request context — mirroring
`PaymentStatusApi`/`SlipStatusApi`'s existing discipline documented in
`docs/api/enrollment-management.md`.
