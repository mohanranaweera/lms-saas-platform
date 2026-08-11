# user-management — Staff Management API Contract

Covers Staff Management (MVP-005 / `STAFF-1`). Derived directly from the shipped backend
implementation on `feature/staff-management` (`com.lms.usermanagement.staff.web.StaffController`),
not from the pre-implementation draft in `docs/plans/MVP-005 Staff Management.md` §10 — that
draft's `password` request field, `DELETE`, and activity-log/password-reset endpoints were not
built; this file documents what actually shipped. Written after implementation, mirroring the
same "process gap" already noted in `docs/api/identity-access-service.md` — this contract should
have been finalized via the `review-api-contract` skill before implementation began.

## Response envelope

Every endpoint below returns `com.lms.common.api.ApiResponse<T>` (same envelope as every other
module — see `docs/api/identity-access-service.md` for the full shape).

## Auth requirements

Every endpoint requires a valid tenant-scoped `Authorization: Bearer <accessToken>` and is
gated by `PermissionCheckService`'s `STAFF_AND_ROLES` domain area
(`docs/requirements/user-roles-and-permissions.md` §2's matrix): **Tenant Admin** has
`VIEW`/`CREATE_EDIT`/`DELETE`; **Read-only Auditor** has `VIEW` only; every other role
(including Teacher, Teacher Assistant, Student, and the other six staff sub-roles) has no
access at all — not even view. Enforced twice: `@PreAuthorize` on the controller method, and an
independent `PermissionCheckService.requirePermission` call at the top of the corresponding
`StaffService` method (defense in depth — see that class's javadoc).

No endpoint accepts a client-supplied `tenantId` in the body, a header, or a query/path
parameter — tenant identity is always resolved from the authenticated session context.

## Endpoints

### `POST /api/v1/staff`

Creates a new staff account. Requires `CREATE_EDIT`.

**Request body** (`StaffCreateRequest`):

```jsonc
{
  "name": "Jane Doe",          // required, max 255
  "email": "jane@example.com", // required, valid email format, max 255
  "roleCode": "FINANCE_STAFF"  // required, one of the 7 fixed values below
}
```

`roleCode` must be exactly one of: `FINANCE_STAFF`, `COURSE_COORDINATOR`, `STUDENT_SUPPORT`,
`CONTENT_MANAGER`, `EXAM_MANAGER`, `ATTENDANCE_OPERATOR`, `READ_ONLY_AUDITOR`. **There is no
`password` field.** The admin never chooses or types the new account's login secret — see
"Credential issuance" below.

**Success — `201`** (`ApiResponse<StaffCreateResponse>`):

```jsonc
{
  "id": "<uuid>",              // StaffProfile id — the resource id every other endpoint addresses
  "name": "Jane Doe",
  "email": "jane@example.com",
  "roleCode": "FINANCE_STAFF",
  "status": "ACTIVE",          // a freshly provisioned tenant_user always starts ACTIVE
  "temporaryPassword": "<raw, high-entropy string>"
}
```

`temporaryPassword` is returned **exactly once, only in this response**. It is never persisted
in plaintext, never logged, and never obtainable again through any other endpoint — `GET
/api/v1/staff` and `GET /api/v1/staff/{id}` use a structurally different response type
(`StaffResponse`) that has no such field. The admin is responsible for relaying this value to
the staff member out-of-band; `mustChangePassword` is always `true` on the underlying account,
so the staff member must set their own credential at first login.

**Failure:**

| Status | `error.code` | Condition |
|---|---|---|
| `400` | `VALIDATION_ERROR` | `name`/`email` blank or over 255 chars, `email` malformed, or `roleCode` not one of the 7 fixed values (rejected at both the DTO `@Pattern` layer and, independently, `StaffService`'s own allow-list check) |
| `403` | `FORBIDDEN` | Caller lacks `CREATE_EDIT` on `STAFF_AND_ROLES` (any role other than Tenant Admin, including Read-only Auditor) |
| `409` | `CONFLICT` | `email` already in use by another account in this tenant (`UNIQUE (tenant_id, email)` on `tenant_user`; race-safe — a concurrent duplicate is caught at the DB constraint even if the friendlier pre-check misses it) |

### `GET /api/v1/staff`

Lists every staff account in the caller's tenant. Requires `VIEW`.

**Success — `200`** (`ApiResponse<List<StaffResponse>>`):

```jsonc
[{ "id": "<uuid>", "name": "Jane Doe", "email": "jane@example.com", "roleCode": "FINANCE_STAFF", "status": "ACTIVE" }]
```

No pagination, filtering, or search — the entire tenant-scoped list is returned. (Filter/search
support and the corresponding "no results match your filter" empty state are not built; only
the true zero-staff empty state exists on the frontend today.)

**Failure:** `403 FORBIDDEN` if the caller lacks `VIEW` on `STAFF_AND_ROLES`.

### `GET /api/v1/staff/{id}`

Reads one staff account by its `StaffProfile` id. Requires `VIEW`.

**Success — `200`** (`ApiResponse<StaffResponse>`): same shape as one list row.

**Failure:**

| Status | `error.code` | Condition |
|---|---|---|
| `403` | `FORBIDDEN` | Caller lacks `VIEW` on `STAFF_AND_ROLES` |
| `404` | `NOT_FOUND` | `{id}` doesn't exist, **or belongs to another tenant** — both cases return this identical status/body. `StaffProfileRepository` (via `TenantAwareRepository`) scopes `findById` to the resolved tenant context, so a cross-tenant id is structurally invisible rather than filtered after the fact. The frontend must treat 403 and 404 on this endpoint as indistinguishable (see "403-vs-404" note below). |

### `PATCH /api/v1/staff/{id}`

Edits an existing staff account's assigned role. Requires `CREATE_EDIT` (same permission as
create — there is no separate "role edit" action in the permission matrix). Edits only
`roleCode`; there is no way to change `name`/`email`/`status` through this endpoint.

**Request body** (`StaffRoleUpdateRequest`):

```jsonc
{ "roleCode": "COURSE_COORDINATOR" } // required, same 7-value restriction as create
```

**Success — `200`** (`ApiResponse<StaffResponse>`): the updated row, same shape as `GET`. No
`temporaryPassword` — this endpoint never touches credentials.

**Failure:**

| Status | `error.code` | Condition |
|---|---|---|
| `400` | `VALIDATION_ERROR` | `roleCode` not one of the 7 fixed values |
| `403` | `FORBIDDEN` | Caller lacks `CREATE_EDIT` (any non-Owner role, including Read-only Auditor), or the caller's own `tenant_user` id equals the target's (self-escalation guard — see below) |
| `404` | `NOT_FOUND` | `{id}` doesn't exist or belongs to another tenant, tenant-scoped identically to `GET /{id}` — a follow-up `GET` by the correct tenant's admin proves the row was never mutated on a cross-tenant attempt |

**Self-escalation guard**: if the authenticated caller's own `tenant_user` id equals the target
`StaffProfile`'s underlying `userId`, the request is rejected as `403 FORBIDDEN` before any
write. This is structurally unreachable today — Tenant Admins are never provisioned through
this staff-creation flow, so a Tenant Admin's own id can never match a `StaffProfile.userId` —
but is kept as a defense-in-depth guard against a future regression (per the module plan §15(e)).

## Not built (deliberately, per current MVP-005 scope)

- `DELETE`/deactivate — no confirmed staff-status state machine exists yet beyond
  `ACTIVE`/`SUSPENDED` (reused directly from `tenant_user.status`); a "removed" state and its
  transition/actor are still undecided. No endpoint exists until that's resolved.
- Activity log (`GET /api/v1/staff/{id}/activity-log`, `GET /api/v1/staff/activity-log`) and
  password reset (`POST /api/v1/staff/{id}/reset-password`) — `STAFF-2`, a distinct follow-on
  story per the module plan's own phasing, not built in this pass.
- Filtering/search/pagination on `GET /api/v1/staff`.

## Credential issuance (resolved decision — see `docs/requirements/open-decisions.md`)

The original module plan explicitly forbade an admin-typed password field pending ratification
(§12, §21 item 3). Resolved: the backend generates the temporary password itself
(`UserProvisioningService`, 128 bits of `SecureRandom` entropy, base64url-encoded, hashed with
the same Argon2 `PasswordEncoder` used for every other credential in this system) and returns it
exactly once via `StaffCreateResponse.temporaryPassword`. The Tenant Admin is never the
custodian of an admin-chosen secret; `mustChangePassword` is unconditionally `true`.

## 403-vs-404 handling (frontend note)

`GET /api/v1/staff/{id}` and `PATCH /api/v1/staff/{id}` intentionally return identical-shaped
403/404 responses for "this id isn't yours," so a client cannot infer which case occurred. The
frontend's `classifyQueryError`/`QueryStateBoundary` (`frontend/src/lib/api/query-status.ts`,
`frontend/src/components/states/query-state-boundary.tsx`) implements this via an opt-in
`notFound`/`treatForbiddenAsNotFound` mechanism scoped to these id-addressed endpoints only —
list/create endpoints keep rendering a normal `PermissionDeniedState` on 403, since there a 403
legitimately means "no access to this feature at all."
