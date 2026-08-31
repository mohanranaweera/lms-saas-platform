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
