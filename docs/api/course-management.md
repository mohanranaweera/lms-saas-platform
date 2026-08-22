# course-management — API Contract

Covers Course Management (MVP-008 / `com.lms.coursemanagement`). Derived directly from
the shipped backend implementation (including the post-review-fixup pagination change to
`GET /api/v1/courses` and `GET /api/v1/public/courses`), not from the pre-implementation
draft in `docs/plans/MVP-008 Course Management.md` §10 — that draft is superseded by this
file. Written retroactively, after a full module review found the contract file had never
been produced despite the plan requiring it before frontend work began (§20 step 8); see
the "Process gap" note at the bottom.

## Response envelope

Every endpoint below returns `com.lms.common.api.ApiResponse<T>` — see
`docs/api/identity-access-service.md`'s "Response envelope" section for the exact shape
(`success`/`data`/`error`/`timestamp`/`traceId`); it is identical here, not repeated.

## Pagination envelope

`GET /api/v1/courses` and `GET /api/v1/public/courses` wrap their list payload in
`com.lms.common.api.PageResponse<T>` — the platform's first paginated endpoint, intended
as the reusable convention for future paginated list endpoints:

```jsonc
{
  "content": [ /* T[] */ ],
  "page": 0,          // zero-indexed current page
  "size": 20,          // requested page size, clamped server-side to a max of 100
  "totalElements": 47,
  "totalPages": 3
}
```

Standard Spring pagination query params apply: `page` (default `0`), `size` (default `20`,
server-clamped to 100 regardless of what's requested — a client cannot request an
unbounded page via `?size=999999`), `sort` (default `createdAt,DESC`).

## Auth requirements

- `GET /api/v1/public/courses` and `GET /api/v1/public/courses/{slug}` are `permitAll`
  (`SecurityFilterChainConfig`) — anonymous, tenant resolved server-side from the request's
  subdomain (see "Tenant resolution" in `docs/api/identity-access-service.md`). No
  `tenantId` query/body param is ever accepted on these two routes.
- Every other endpoint below requires a valid `Authorization: Bearer <accessToken>` header.
  `@PreAuthorize("isAuthenticated()")` is a coarse gate only for most endpoints — the real,
  combined staff-matrix-or-Teacher-ownership authorization check happens in
  `CourseService`/`CourseAccessGuard` per method (see "Authorization model" below), because
  it depends on data (the course's `teacher_id`) that isn't available to a `@PreAuthorize`
  SpEL expression without loading the entity first anyway.
- `POST /api/v1/courses/{id}/teacher` is the one exception: gated directly on
  `@PreAuthorize("hasRole('TENANT_ADMIN')")`, never `DomainArea.COURSES`'s flat staff
  matrix and never the Teacher-ownership path — a Course Coordinator's `CREATE_EDIT`/
  `APPROVE` grant does not extend to this action, and the course's own owning Teacher is
  rejected too.

## Authorization model

- **Staff callers** (Tenant Admin, Course Coordinator, Finance Staff, Student Support,
  Content Manager, Exam Manager, Attendance Operator, Read-only Auditor) are gated by
  `PermissionCheckService.hasPermission(DomainArea.COURSES, <action>)` — the existing
  RBAC-2 matrix (`VIEW`/`CREATE_EDIT`/`APPROVE`/`DELETE`), re-checked server-side in
  `CourseService` independent of the controller's coarse gate.
- **Teacher callers** are ownership-scoped, not domain-matrix-scoped (Teacher is
  deliberately absent from `DomainArea.COURSES`'s matrix). `CourseAccessGuard` resolves
  the acting user's own id from the authenticated principal (never a request
  parameter/body field) and loads the course through the tenant-scoped repository first,
  so a cross-tenant course id is structurally invisible (404) before ownership is even
  evaluated. A Teacher may act only on a course where `course.teacher_id` equals their own
  id — except `DELETE` and teacher-reassignment, which are staff/Tenant-Admin-only
  regardless of ownership.
- Every mutating endpoint independently rejects the Read-only Auditor role, inherited from
  `PermissionCheckServiceImpl`'s existing invariant (Auditor never holds a write-class
  grant).

## Endpoints

### `POST /api/v1/courses`

Create a course. **Request body** (`CourseCreateRequest`):

```jsonc
{
  "name": "Intro to Biology",           // required, max 255
  "slug": "intro-to-biology",            // required, max 160, ^[a-z0-9]+(-[a-z0-9]+)*$
  "category": "Science",                 // required, max 100, free-text
  "subject": "Biology",                  // optional, max 100
  "stream": null,                        // optional, max 100
  "grade": "Grade 9",                    // optional, max 50
  "academicYear": "2026",                // optional, max 20
  "description": "...",                  // optional, max 5000
  "price": 49.99,                        // required, NUMERIC(12,2), >= 0
  "accessDurationDays": 180,             // optional, positive int; absent = lifetime access
  "enrollmentRule": "Open enrollment",   // optional, max 1000, free-text notes only, zero enforced semantics
  "status": "DRAFT",                     // required, one of DRAFT/PRIVATE/PUBLIC; no silent default to PUBLIC
  "teacherId": null                      // only ever honored for a staff-role caller; a Teacher-role caller's own id is always used server-side regardless of what's sent here
}
```

**Success — `201`** (`ApiResponse<CourseResponse>`, see shape below). **`409 CONFLICT`**
on duplicate slug within the tenant (`fieldErrors: [{field: "slug", ...}]`). **`400
VALIDATION_ERROR`** on any other Bean Validation failure.

### `GET /api/v1/courses`

List courses. **Query params**: `status` (optional, `DRAFT`/`PRIVATE`/`PUBLIC`),
`category` (optional, exact match), `teacherId` (optional, UUID — **staff-only
effective**; silently ignored for a Teacher-role caller, who always sees only their own
courses regardless of what's sent), plus the standard pagination params above.

- **Teacher-role caller**: sees only courses where `teacher_id` = their own id, further
  narrowed by `status`/`category` if supplied.
- **Staff caller**: requires `DomainArea.COURSES` `VIEW`; sees all tenant courses,
  narrowed by all three filters.

**Success — `200`** (`ApiResponse<PageResponse<CourseResponse>>`).

### `GET /api/v1/courses/{id}`

Course detail. `403`/`404` (uniform, no distinguishing copy) for a cross-tenant id or a
Teacher requesting a course they don't own. **Success — `200`** (`ApiResponse<CourseResponse>`).

### `PATCH /api/v1/courses/{id}`

Structural/classification/enrollment-rule/access-duration edit. **Request body**
(`CourseUpdateRequest`) — deliberately excludes `teacherId`, `price`, and `status`; these
fields are silently dropped (`@JsonIgnoreProperties(ignoreUnknown = true)`) if sent by any
caller, staff included — each has its own dedicated endpoint below, keeping exactly one
write path per sensitive field:

```jsonc
{
  "name": "...", "slug": "...", "category": "...",   // all required
  "subject": null, "stream": null, "grade": null, "academicYear": null,
  "description": null, "enrollmentRule": null, "accessDurationDays": null
}
```

**Success — `200`**. **`409 CONFLICT`** on duplicate slug.

### `POST /api/v1/courses/{id}/teacher`

Tenant-Admin-only teacher reassignment. **Request body** (`CourseTeacherReassignRequest`):
`{ "teacherId": "<uuid>" }` — validated against `UserProvisioningApi` for same-tenant +
`role = TEACHER` existence. **`403`** for every other caller, including the course's own
owning Teacher and any staff role with `COURSES` `CREATE_EDIT`/`APPROVE`.

### `PATCH /api/v1/courses/{id}/price`

The one write path for `price`. **Request body** (`CoursePriceChangeRequest`):
`{ "price": 59.99 }` (`NUMERIC(12,2)`, `>= 0`). Writes the new price, one
`course_price_history` row, and publishes `CoursePriceChangedEvent`, all in one
transaction — regardless of the course's current status (a DRAFT course's price change is
history-tracked too, not just published courses'; see `docs/requirements/open-decisions.md`
for the audit-scope caveat this carries).

### `POST /api/v1/courses/{id}/publish` / `POST /api/v1/courses/{id}/unpublish`

Status transition only, no other field touched. `publish` always sets `status = PUBLIC`.
`unpublish` always sets `status = DRAFT` — **never** `PRIVATE`; there is no endpoint that
transitions a course back to `PRIVATE` once it leaves that state.

### `DELETE /api/v1/courses/{id}`

Tenant-Admin-only (`COURSES` `DELETE` grant — only Tenant Admin holds it in the existing
matrix). `course_module`/`course_lesson` rows cascade-delete (`ON DELETE CASCADE`, V14).
`course_price_history` rows are **not** deleted — V12 deliberately dropped that FK
entirely so a course's price-change trail survives its own deletion, per root
`CLAUDE.md`'s "never delete financial history."

### `POST /api/v1/courses/{id}/modules`, `PATCH`/`DELETE /api/v1/courses/{id}/modules/{moduleId}`

Structure only, no material content. **Request body** (`CourseModuleRequest`):
`{ "title": "...", "sequence": 1 }` — both required, `sequence` a positive integer,
unique within `(tenant_id, course_id)`. Same staff-matrix-or-Teacher-ownership
authorization as the parent course.

### `POST .../modules/{moduleId}/lessons`, `PATCH`/`DELETE .../lessons/{lessonId}`

Same shape (`CourseLessonRequest`: `{ "title": "...", "sequence": 1 }`), scoped through
the parent course's tenant and the parent module.

### `GET /api/v1/public/courses`

Anonymous, tenant resolved from subdomain. Hard-filters `status = 'PUBLIC'` at the query
level (never a post-fetch filter) — a DRAFT/PRIVATE course is structurally absent, not
merely hidden. No `tenantId` query param accepted. **Query params**: standard pagination
only (no `status`/`category`/`teacherId` filters — the `status = PUBLIC` scope is already
hard-fixed and not client-configurable). **Success — `200`**
(`ApiResponse<PageResponse<PublicCourseResponse>>`) — `PublicCourseResponse` omits
`teacherId` and every audit column.

### `GET /api/v1/public/courses/{slug}`

**`404`** (one generic "not found" message, no distinguishing copy) for a DRAFT/PRIVATE
course in the requesting tenant, a nonexistent slug, and a same-slug course belonging to a
different tenant — all three cases are indistinguishable by design (anti-enumeration).

## Response shapes

**`CourseResponse`** (authenticated endpoints):

```jsonc
{
  "id": "<uuid>", "teacherId": "<uuid>", "name": "...", "slug": "...",
  "category": "...", "subject": null, "stream": null, "grade": null,
  "academicYear": null, "description": null, "price": 49.99,
  "accessDurationDays": 180, "enrollmentRule": null,
  "status": "DRAFT", // DRAFT | PRIVATE | PUBLIC
  "createdAt": "2026-08-01T00:00:00Z", "updatedAt": "2026-08-01T00:00:00Z"
}
```

**`PublicCourseResponse`** (public endpoints) — same fields minus `teacherId`,
`createdAt`, `updatedAt`.

**`CourseModuleResponse`**: `{ id, courseId, title, sequence, createdAt, updatedAt }`.
**`CourseLessonResponse`**: `{ id, moduleId, title, sequence, createdAt, updatedAt }`.

Note: `GET .../modules` and `GET .../modules/{moduleId}/lessons` return their lists in
whatever order the underlying repository finder returns (no `ORDER BY sequence` at the
query level) — callers must sort client-side by `sequence` ascending. The frontend already
does this (`sortBySequence` in `frontend/src/lib/courses/reorder.ts`).

## Error codes

Standard `ApiErrorCodes` (see `docs/api/identity-access-service.md`'s error-codes table
for the full platform list) apply. Course-management-specific cases:

| Case | Code | Status |
|---|---|---|
| Duplicate slug within tenant | `CONFLICT` | `409` |
| Bean Validation failure | `VALIDATION_ERROR` | `400` |
| Malformed/non-UUID path variable | `VALIDATION_ERROR` | `400` (previously fell through to `500`; fixed in `GlobalExceptionHandler` as part of this module's backend review) |
| Invalid enum value in request body (e.g. bad `status`) | `VALIDATION_ERROR` | `400` (same fix) |
| Cross-tenant / not-owned-by-Teacher course id | `NOT_FOUND` | `404` |
| Non-Tenant-Admin attempting teacher reassignment | `FORBIDDEN` | `403` |
| Read-only Auditor attempting any mutation | `FORBIDDEN` | `403` |

## Process gap

This file did not exist when frontend implementation began — the module plan
(`docs/plans/MVP-008 Course Management.md` §20 step 8) required it be produced via the
`review-api-contract` skill *before* frontend work started, but frontend was built
directly against the draft contract table and the controllers themselves. A later
multi-agent module review caught the gap; this file is the retroactive correction,
reflecting the actual shipped contract (including the pagination change added in the same
review-remediation pass) rather than the superseded draft.
