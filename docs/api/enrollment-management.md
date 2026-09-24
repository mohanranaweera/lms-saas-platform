# enrollment-management — API Contract

Covers MVP-012 "Enrollment and Course Access" (`com.lms.enrollmentmanagement`) — course-level
access expiry (ENR-2) and the reactivation-request workflow (ENR-3). Written after the fact
once the module was reviewed, following the same "process gap" this project's other API
contract files record (`docs/api/payment-management.md`, `docs/api/course-management.md`): the
plan (`docs/plans/MVP-012 Enrollment and Course Access.md` §10) sketched a draft contract before
implementation, but the finalized doc was never produced until this review found the gap. This
file reflects the actual shipped backend, not the plan's pre-implementation draft — every
deviation from that draft is called out explicitly below. See
`docs/adr/ADR-013-enrollment-lineage-and-reactivation-order-gate.md` for the two structural
decisions (lineage-row domain model, order-creation reactivation gate) behind this contract.

This file does **not** cover the pre-existing MVP-010/MVP-011 activation-only slice
(`EnrollmentActivationApi.activateFromConfirmedPayment`/`activateFromApprovedSlip`, and this
module's two new `reactivateFromConfirmedPayment`/`reactivateFromApprovedSlip` methods) — those
are internal, cross-module `api`-package calls (`payment-management` → `enrollment-management`),
never REST endpoints of their own.

**Wave 3** ("Student and Teacher operational profiles") added three REST endpoints to this
module: staff-initiated enrollment revocation, a studentId-scoped enrollment-history read for
Student Detail's Enrollments tab, and a course roster read shared by the Teacher and Tenant Admin
portals. All three are documented in their own sections below. Revocation is change-controlled
per `.claude/rules/payments.md` §7 and approved via
`docs/adr/ADR-016-staff-granted-enrollment-and-revocation.md` — see that ADR for the full
decision record (it also covers the staff "enroll student" action, which lives in
`payment-management`'s own contract file, not here, since `payment-management` owns the
`Order`/`Payment` rows that action creates).

## Response envelope

Every endpoint returns `com.lms.common.api.ApiResponse<T>` — see
`docs/api/identity-access-service.md`'s "Response envelope" section for the exact shape
(`success`/`data`/`error`/`timestamp`/`traceId`); identical here, not repeated.

## Auth requirements

- Every endpoint below requires a valid `Authorization: Bearer <accessToken>` header.
  `@PreAuthorize` at the controller (`hasRole('STUDENT')` or `isAuthenticated()`) is a coarse
  gate only — the real authorization check happens in the service layer
  (`EnrollmentQueryService`, `ReactivationRequestService`, `ReactivationAccessGuard`,
  `PermissionCheckService`), mirroring every other domain's established discipline in this
  codebase.
- `tenantId`, `studentId`, `reviewerId`, and `status` are never accepted from the client on any
  request body — always resolved from `TenantContext`/`AuthenticatedPrincipal`, or (for
  `status`/`reviewedBy`/`reviewedAt`) computed server-side.

## Authorization model

- **`GET /courses/{courseId}/access-state`** is student-only — see the endpoint's own section
  below for why this is a deliberate deviation from the plan's §10 draft, not an oversight.
- **Reactivation-request reads** (`GET /reactivation-requests/{id}`) use
  `ReactivationAccessGuard.requireOwnerOrStaffView`: the owning student may read their own
  request, or any staff caller holding `DomainArea.ACCESS_EXPIRY`/`VIEW` (Tenant Admin, Finance
  Staff, Student Support, Read-only Auditor) may read any request in their own tenant. A
  cross-tenant or not-owned id is invisible before ownership is even evaluated (the owning
  repository's tenant-scoped `findById` returns empty for it) — this collapses to the same
  `404` a genuinely nonexistent id would produce. A same-tenant **Student** caller who is not
  the request's owner also gets `404`, never `403` (anti-enumeration — a student must never be
  able to distinguish "exists but isn't mine" from "doesn't exist"). A same-tenant **staff**
  caller lacking `ACCESS_EXPIRY`/`VIEW` gets `403` — staff already have legitimate visibility
  into their own tenant's resource existence.
- **Reactivation-request submission** (`POST /enrollments/{enrollmentId}/reactivation-requests`)
  is owning-student-only; a cross-tenant or not-owned `enrollmentId` is `404` by the identical
  anti-enumeration convention above.
- **The review queue** (`GET /reactivation-requests`) and **approve/reject**
  (`POST /reactivation-requests/{id}/approve|reject`) are staff-only — `403` for any student
  regardless of ownership. The queue requires `ACCESS_EXPIRY`/`VIEW`; approve/reject require
  `ACCESS_EXPIRY`/`APPROVE`, held **only by Tenant Admin** in the already-shipped RBAC matrix
  (`PermissionCheckServiceImpl`) — Finance Staff, Student Support, and Read-only Auditor all
  hold `VIEW` only and get `403` on approve/reject. Per `.claude/rules/payments.md` §8, this
  `hasPermission` check is a coarse category grant only — `ReactivationRequestService.approve`/
  `reject` independently re-verify the request's actual `status == SUBMITTED` under a
  pessimistic row lock before writing anything, never trusting the permission check alone as
  sufficient authorization for the mutation.

## Endpoints

### `GET /api/v1/courses/{courseId}/access-state`

`hasRole('STUDENT')`. Always resolves the **calling student's own** access state for the given
course — there is no `studentId` parameter anywhere on this endpoint.

**Deviation from the plan's §10 draft**: the draft labeled this "Owner student or staff
`ACCESS_EXPIRY`/`VIEW`", mirroring `PaymentDomainAccessGuard`'s owner-or-staff shape. That shape
requires a known resource owner to check the caller against; this endpoint's URL carries only a
`courseId`, never a `studentId`, so there is no student to resolve an access state *for* when
the caller is staff — "or staff VIEW" cannot be implemented here without inventing an implicit
target, which would be actively wrong. Staff have their own, correctly resource-scoped read path
via the reactivation-request queue/detail endpoints instead. See
`EnrollmentQueryService`'s class javadoc for the full reasoning.

**Success — `200`** (`ApiResponse<EnrollmentAccessStateResponse>`):

```jsonc
{
  "state": "EXPIRED",              // NEVER_ENROLLED | ACTIVE | EXPIRED — computed live on every
                                     // call, never stored on the enrollment row itself (ADR-013)
  "enrollmentId": "...",           // null only when state is NEVER_ENROLLED
  "accessExpiresAt": "2026-09-01T00:00:00Z",  // null for lifetime access (course.access_duration_days
                                                // was NULL at activation time) or when NEVER_ENROLLED
  "canRequestReactivation": true   // true only when state == EXPIRED AND no live (SUBMITTED, or
                                     // APPROVED-and-unfulfilled) reactivation request already exists
                                     // for this enrollment; always false for ACTIVE/NEVER_ENROLLED
}
```

**No `404` for a cross-tenant or genuinely nonexistent `courseId`.** Unlike most reads in this
codebase, a cross-tenant course, a nonexistent course, and a real, in-tenant course the caller
has simply never enrolled in are **indistinguishable** — all three resolve to `200` with
`state: "NEVER_ENROLLED"`. This is a deliberate, reviewed anti-enumeration choice, not a bug: it
leaks strictly less than a `404` would (a `404` at least confirms "no such resource anywhere in
your reach"; this response doesn't even confirm the course id was well-formed or real). A future
reviewer should not file this as a missing `404` case.

### `GET /api/v1/enrollments/my`

`hasRole('STUDENT')`, owner-only, no id parameter — mirrors `user-management`'s `/me`
anti-enumeration-by-construction pattern. Returns a **plain array**, not a `PageResponse` (no
pagination on this endpoint).

**Success — `200`** (`ApiResponse<EnrollmentSummaryResponse[]>`):

```jsonc
[
  {
    "enrollmentId": "...",
    "courseId": "...",
    "state": "ACTIVE",               // NEVER_ENROLLED never appears in this list — every row is a
                                       // real current enrollment by construction
    "accessExpiresAt": null,         // null == lifetime access for this row
    "canRequestReactivation": false
  }
]
```

One row per **current** (`superseded_at IS NULL`) enrollment — a superseded (reactivated-away)
prior lineage row never appears here.

### `GET /api/v1/enrollments/my/courses`

Added for MVP-013 "Student Dashboard" (My Courses / Overview course-name resolution).
`hasRole('STUDENT')`, owner-only, no id parameter — same anti-enumeration-by-construction
shape as `GET /api/v1/enrollments/my` above. Returns a **plain array**, not paginated.

**Deviation from `docs/plans/MVP-013 Student Dashboard.md` §9/§10's draft**: that plan
sketched this as a `course-management`-owned endpoint, `GET /api/v1/courses/my-enrolled-
summary`, backed by a new `EnrollmentAccessApi.myCurrentEnrolledCourseIds()` method. What
shipped instead lives here, in `enrollment-management`, backed by a new
`CourseLookupApi.getCourseSummaries(Set<UUID>)` method added to `course-management`'s `api`
package. Rationale for keeping the as-shipped shape rather than moving it to match the
draft: the primary aggregate this read composes over is "the caller's own current
enrollments" (owned by this module), and `course-management` is consulted only for the
narrow, already-tenant-scoped display fields per enrolled course id — this follows
`.claude/rules/architecture.md`'s "pick the domain that owns the primary aggregate/table"
guidance more directly than the plan's original sketch, and avoids adding a second new
`api`-package method (`myCurrentEnrolledCourseIds()`) that would have had no other
consumer. Both directions were reviewed and confirmed to already satisfy the cross-module
rule (this module depends only on `course-management`'s `api` package, never its
repository/entities).

`EnrollmentQueryService.listMyEnrolledCourseSummaries()` resolves the caller's distinct
current enrolled course ids, then calls `CourseLookupApi.getCourseSummaries(Set)` once,
batched, for all of them; a course id that no longer resolves in `course-management` is
silently omitted, never a `500` (course rows are never hard-deleted post-enrollment, per
this codebase's soft-delete/status-based lifecycle).

**Success — `200`** (`ApiResponse<CourseSummaryResponse[]>`):

```jsonc
[
  {
    "id": "...",
    "name": "Intro to Algebra",
    "slug": "intro-to-algebra",
    "category": "Mathematics"
  }
]
```

Empty array if the caller has zero current enrollments — not an error.

### `POST /api/v1/enrollments/{enrollmentId}/reactivation-requests`

Owning-student-only. No request body. Creates a `reactivation_request` row in `SUBMITTED`
status — this **does not reactivate anything by itself**.

**Success — `201`** (`ApiResponse<ReactivationRequestResponse>`, shape below). **`404`** if
`enrollmentId` doesn't resolve to a **current** enrollment owned by the caller (cross-tenant,
another student's, or nonexistent — all indistinguishable). **`409 CONFLICT`** for any of:
- the target enrollment has already been superseded by a newer lineage row ("This enrollment has
  been superseded by a newer enrollment and can no longer be reactivated")
- the enrollment's live-computed access state is not `EXPIRED` ("This enrollment's access has
  not expired - a reactivation request is not applicable")
- a live request (`SUBMITTED`, or `APPROVED` and unfulfilled) already exists for this enrollment
  ("A reactivation request is already pending for this enrollment") — enforced both at the
  service layer and by the database (`uq_reactivation_request_tenant_enrollment_live`, `V24`),
  so a genuine concurrent-submission race is also rejected `409`, never double-inserted.

### `GET /api/v1/reactivation-requests/my`

Owning-student-only, paginated (`PageResponse<ReactivationRequestResponse>` inside
`ApiResponse`), default sort `createdAt` DESC, default size 20 — the student's own full request
history across every enrollment.

### `GET /api/v1/reactivation-requests/{id}`

**Not in the plan's §10 draft table at all** — added because both the student's own detail view
and the staff detail/review view need one shared read endpoint for a single request, rather than
duplicating the same read behind two different paths. Owner student OR staff
`ACCESS_EXPIRY`/`VIEW` — see "Authorization model" above for the exact 404-vs-403 split.

**Success — `200`** (`ApiResponse<ReactivationRequestResponse>`, shape below).

### `GET /api/v1/reactivation-requests`

Staff `ACCESS_EXPIRY`/`VIEW` only — `403` for a student regardless of ownership. Query params:
`status` (optional — one of `SUBMITTED`/`APPROVED`/`REJECTED`), standard Spring `Pageable`
params (`page`, `size`, `sort` — default `size=20`, `sort=createdAt,ASC`, i.e. oldest-first/FIFO,
matching the manual-slip review queue's own convention). **Omitting `status` returns every
status** — unlike the manual-slip review queue, there is a real backend "all statuses" default
here, so callers must not invent a fake "pending" filter sentinel the way
`lib/api/payment-slips.ts` does for slips.

**Success — `200`** → `ApiResponse<PageResponse<ReactivationRequestResponse>>`.

### `POST /api/v1/reactivation-requests/{id}/approve`

Staff `ACCESS_EXPIRY`/`APPROVE` only (Tenant Admin, per the shipped RBAC matrix). Body
(optional): `{ "note": "..." }` (max 1000 chars).

Does **not** itself touch `enrollment` or trigger reactivation — only flips this row's `status`
to `APPROVED`. The actual reactivation happens later, atomically, when the student's new order's
payment/slip confirms (`EnrollmentActivationService.reactivateFromConfirmedPayment`/
`reactivateFromApprovedSlip`). Writes exactly one `AuditLogApi.record(...)` entry
(`action: "reactivation_request.approved"`), atomic with the status write.

**Success — `200`** (`ApiResponse<ReactivationRequestResponse>`). **Idempotent**: calling this
again on an already-`APPROVED` request is a no-op `200` with the existing view (no duplicate
audit-log entry). **`409 CONFLICT`** if the request's status is anything other than `SUBMITTED`
or `APPROVED` (i.e. it's already `REJECTED` — a one-directional terminal state, no reversal).
**`403`** for any caller without `ACCESS_EXPIRY`/`APPROVE` (Finance Staff, Student Support,
Read-only Auditor, any student).

### `POST /api/v1/reactivation-requests/{id}/reject`

Same auth as approve. Body (required): `{ "reason": "..." }` (non-blank, max 1000 chars).
One-directional terminal transition — no reversal endpoint; access stays expired, but the
student may submit a new reactivation request. Writes exactly one `AuditLogApi.record(...)`
entry (`action: "reactivation_request.rejected"`, `reason` recorded), atomic with the status
write.

**Success — `200`** (`ApiResponse<ReactivationRequestResponse>`). **`400`/`409`** if `reason` is
blank/missing, or the request's status isn't `SUBMITTED`. **`403`** for any caller without
`ACCESS_EXPIRY`/`APPROVE`.

### `POST /api/v1/enrollments/{id}/revoke` (Wave 3, ADR-016)

Staff-initiated enrollment revocation ("Student actions" — Student Detail's Revoke
action). Controller-level `@PreAuthorize("isAuthenticated()")` is a coarse gate only — the
real check, `STUDENTS`/`CREATE_EDIT`, is enforced inside
`EnrollmentActivationService#revoke` itself (defense in depth, matching this codebase's
established pattern of re-verifying inside the service rather than trusting the
controller annotation alone).

**Request body** (`RevokeEnrollmentRequest`): `{ "reason": "..." }` — mandatory,
non-blank, max 1000 chars. A revoke with no recorded reason is rejected outright, never
silently defaulted.

Loads the **current** (`supersededAt IS NULL`) row for `{id}`, scoped to the caller's own
tenant (`TenantAwareRepository` — a cross-tenant `{id}` is structurally invisible, `404`,
never a cross-tenant mutation). Calls `Enrollment#revoke(actorId, reason)`, which itself
calls nothing but the entity's own pre-existing `supersede()` mutation (the only legal
in-place mutation this append-mostly aggregate ever allowed) plus records
`revoked_at`/`revoked_by`/`revoke_reason` (new nullable columns,
`V47__add_enrollment_staff_granted_evidence.sql`) — **never** touches the immutable
activation columns (`activating_payment_id`/`activating_slip_id`/`activated_at`), writes
no ledger entry, and mutates no payment row. **No new `EnrollmentStatus` value is
introduced** — access currency (now including "revoked") is still computed live via
`isCurrentlyActive`/lineage-and-`supersededAt` inspection, exactly as before. Reactivation
after a revoke needs no new code path — a revoked enrollment's access state resolves the
same way a naturally expired one does, so the existing reactivation-request flow (above,
ADR-013) already covers it.

Writes **two** `AuditLogApi.record(...)` entries in the same transaction: one targeting
`enrollment`/`{id}` (`action: "enrollment.revoked"`, preserving the pre-existing
traceability shape), and a second — added in a Wave 3 fix-pass after review found the
first row alone left this action invisible on the student's own Activity tab — targeting
`student_profile`/`<the enrollment's own student's profile id>` with the same action and
reason, so the revocation is discoverable via `GET /students/{id}/activity`.

**Success — `200`** (`ApiResponse<Void>`). **`404`** — `{id}` doesn't resolve to a CURRENT
enrollment in the caller's own tenant (cross-tenant, already-superseded, or nonexistent —
indistinguishable). **`409 CONFLICT`** — the row exists but is already superseded/revoked
("This enrollment is not currently active and cannot be revoked"), or a concurrent
revoke of the same row raced past the read-check (caught by a new `@Version`
optimistic-lock column on `enrollment`, added in a Wave 3 fix-pass — surfaced as a clean
`409`, never a `500`). **`400`** — `reason` blank/missing. **`403`** — caller lacks
`STUDENTS`/`CREATE_EDIT`.

### `GET /api/v1/students/{id}/enrollments` (Wave 3)

Staff-facing, studentId-scoped enrollment-history read behind Student Detail's
Enrollments tab — lives here (the owning domain of `enrollment`), not duplicated into
`user-management`. `{id}` is the `StudentProfile`'s own resource id (matching every other
`/api/v1/students/{id}/...` URL in this codebase) — resolved internally to the opaque
cross-domain `studentId` via `StudentLookupApi`, never a client-supplied `tenant_user` id.
Requires `STUDENTS`/`VIEW`.

**Success — `200`** (`ApiResponse<EnrollmentHistoryEntryResponse[]>`), not paginated —
one row per lineage entry (current **and** superseded), oldest-lineage-first is not
guaranteed, `current` distinguishes the live row:

```jsonc
[
  {
    "enrollmentId": "...", "courseId": "...", "current": true,
    "activatedAt": "2026-08-01T00:00:00Z", "accessExpiresAt": null,
    "supersededAt": null, "revokedAt": null, "revokeReason": null,
    "reactivatedFromEnrollmentId": null
  }
]
```

**`404`** — `{id}` doesn't resolve to a student in the caller's own tenant. **`403`** —
caller lacks `STUDENTS`/`VIEW`.

### `GET /api/v1/courses/{courseId}/roster` (Wave 3, PAR-03-06/PAR-04-03)

A real, backend-filtered course roster — Teacher-own-course-only, or staff holding
`STUDENTS`/`VIEW` or `COURSES`/`VIEW`. Backend ownership check (`CourseLookupApi
#getTeacherId`, never a client claim) mirrors `AttendanceAccessGuard`'s established
Teacher-ownership-or-staff-matrix pattern exactly. Reuses the same
`EnrollmentAccessApi#listCurrentlyEnrolledStudentIds(courseId)` the session-scoped
attendance roster already calls, composed with `user-management`'s `StudentLookupApi`
student summaries — never a cross-domain repository join.

**Success — `200`** (`ApiResponse<CourseRosterEntryResponse[]>`):

```jsonc
[ { "studentId": "<uuid>", "userId": "<uuid>", "name": "Jane Student", "email": "jane@example.com" } ]
```

`studentId` here is `student_profile.id`; `userId` is the underlying `tenant_user` id.
**`404`** — `courseId` doesn't resolve to a course in the caller's own tenant. **`403`** —
a same-tenant Teacher not owning the course, or a staff caller with neither
`STUDENTS`/`VIEW` nor `COURSES`/`VIEW`.

## `ReactivationRequestResponse` shape

Returned by every reactivation-request endpoint above except the plain access-state/enrollment
list reads:

```jsonc
{
  "id": "...",
  "enrollmentId": "...",
  "requestedBy": "...",          // the requesting student's tenant_user id — no email/name is
                                    // resolved server-side on this response; a staff-facing
                                    // caller wanting a display name resolves it separately via
                                    // user-management's GET /v1/students/{id}
  "status": "SUBMITTED",         // SUBMITTED | APPROVED | REJECTED — one-directional only, no
                                    // UNDER_REVIEW step (unlike payment_slip's shape)
  "reviewedBy": null,            // null until APPROVED/REJECTED; the reviewing staff user's id
  "reviewedAt": null,            // null until APPROVED/REJECTED
  "newOrderId": null,            // set once the student places the qualifying new order
                                    // (OrderService links it in the same transaction as order
                                    // creation) — null until then, even after APPROVED
  "createdAt": "2026-08-30T10:15:00Z",
  "updatedAt": "2026-08-30T10:15:00Z"
}
```

## Cross-module contract (not REST — recorded here since no other file documents it)

`payment-management`'s `OrderService.createOrder` (`POST /api/v1/orders`, documented in
`docs/api/payment-management.md`) depends on two narrow, tenant-context-only read/write methods
this module exposes via its `api` package, never via a REST call:

- `EnrollmentAccessApi.resolveAccessState(studentId, courseId)` / `.hasApprovedUnfulfilledReactivationRequest(studentId, courseId)`
  — the exact precondition check behind `POST /orders`'s two new `409` variants.
- `ReactivationLinkingApi.linkApprovedRequestToNewOrder(studentId, courseId, newOrderId)` —
  called inside `OrderService.createOrder`'s own transaction; throws `IllegalStateException` if
  no `APPROVED`+unfulfilled request exists, which `OrderService` maps to the same `409`
  ("reactivation approval required") rather than ever surfacing a raw 500.

Both interfaces resolve tenant identity exclusively from `TenantContext` — there is no overload
accepting a caller-supplied tenant id, mirroring `PaymentStatusApi`/`SlipStatusApi`'s existing
discipline.

`attendance-management` (MVP-016) depends on one additional, additive method on this same
interface:

- `EnrollmentAccessApi.listCurrentlyEnrolledStudentIds(UUID courseId)` — the inverse of
  `resolveAccessState`'s per-student direction: "which students are currently enrolled in course
  X" rather than "is this one student currently enrolled in this one course." Computed live on
  every call (`supersededAt IS NULL AND (accessExpiresAt IS NULL OR accessExpiresAt > now())`) —
  an enrollment with only a superseded or access-expired current row does not count. Consumed by
  `AttendanceMarkingService` as the roster-bypass guard (every submitted `studentId` on a mark
  request must appear in this list) and by `AttendanceReportService`/`AttendanceController`'s
  roster read. Purely additive — no existing method on this interface changed signature; see
  `docs/api/attendance-management.md`'s own "Cross-module contract" section for the consumer
  side.

**New in Wave 3 — this module now also depends on `user-management.api.StudentLookupApi`**
(the reverse of every prior cross-module direction recorded above, but an already-approved
`api`-only dependency, not a new architectural exception): `getStudentSummariesByUserId`/
`resolveUserId` resolve the opaque cross-domain `studentId` (`tenant_user.id`) this
module's own `Enrollment`/roster reads are keyed by to/from the `student_profile.id` that
`GET /students/{id}/enrollments`'s URL, `GET /courses/{courseId}/roster`'s response rows,
and `POST /enrollments/{id}/revoke`'s second audit-log row all need.
`enrollment-management` imports only `user-management`'s `api` package
(`StudentLookupApi`, `StudentSummary`) — never a `user-management` repository or entity.
See `docs/architecture/modular-monolith.md`'s Wave 3 worked example for the full
cross-module edge picture introduced this wave.
