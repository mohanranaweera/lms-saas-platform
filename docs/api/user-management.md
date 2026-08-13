# user-management — Student Management API Contract

Covers the Student Management module (MVP-006, `STU-2`'s admin-authenticated slice plus
the student self-service view/edit endpoints). Derived directly from the shipped backend
implementation (`com.lms.usermanagement.student` package), not from the pre-implementation
draft in `docs/plans/MVP-006 Student Management.md` §10 — that draft describes a larger
surface (self-registration, bulk-import, delete/status-change, history composition,
teacher roster) that was deliberately deferred pending unresolved business decisions (see
the plan's §20/§21) and is **not** covered here. This file documents only the six
endpoints that actually exist.

Like `docs/api/identity-access-service.md`, this file was written retroactively — after
the backend implementation shipped — rather than before, per `docs/api/README.md`'s
"Process gap" precedent. See that file's own note; the same gap recurred here and should
be closed for the next module rather than repeated a third time.

Staff Management's `com.lms.usermanagement.staff` package (MVP-005, already merged) has
the identical undocumented-contract gap — out of scope for this file, flagged in
`docs/requirements/open-decisions.md`.

## Response envelope

Every endpoint below returns `com.lms.common.api.ApiResponse<T>` (see
`docs/api/identity-access-service.md`'s "Response envelope" section for the exact shape —
identical here, not repeated).

## Auth requirements

- Every endpoint in this file requires a valid `Authorization: Bearer <accessToken>`
  header. Missing/invalid/expired token → `401 UNAUTHENTICATED`/`SESSION_REVOKED` (see
  `docs/api/identity-access-service.md`'s "Session invalidity" section — identical
  mechanism, not repeated).
- The four staff-facing endpoints (`POST /students`, `GET /students`, `GET /students/{id}`,
  `PATCH /students/{id}`) are additionally gated by
  `@PreAuthorize("@permissionCheckService.hasPermission('STUDENTS', '<ACTION>')")`, backed
  by a second, independent `PermissionCheckService.requirePermission(...)` call inside
  `StudentService` (defense-in-depth — the service layer never trusts the controller
  annotation alone). Per `docs/requirements/user-roles-and-permissions.md` §2's Students
  row: Tenant Admin and Student Support hold `CREATE_EDIT` (and Tenant Admin alone holds
  `DELETE`, not exposed by any endpoint here); every other named staff role
  (Finance Staff, Course Coordinator, Content Manager, Exam Manager, Attendance Operator,
  Read-only Auditor) holds `VIEW` only. A caller with neither grant → `403 FORBIDDEN`.
- The two self-service endpoints (`GET/PATCH /students/me`) are gated by
  `@PreAuthorize("hasRole('STUDENT')")` — **not** `PermissionCheckService`, deliberately:
  `DomainArea.STUDENTS`'s matrix has no entry for `Role.STUDENT` at all, so gating a
  student's own-profile access through it would incorrectly default-deny. These two
  endpoints take **no `{id}` parameter** — the caller's own `user_id` is resolved from the
  authenticated principal (`AuthenticatedPrincipalHolder`), making it structurally
  impossible (not just permission-denied) for a student to reach another student's data
  through this API.
- No endpoint in this file accepts a client-supplied `tenantId`, `role`, or
  `mustChangePassword` field, in the body or otherwise — tenant identity is resolved from
  the authenticated session's tenant context, role is always the literal `"STUDENT"`
  server-side, and `mustChangePassword` is always `true` for every account this module
  creates (see `POST /students` below). `StudentCreateRequest`/`StudentUpdateRequest` are
  annotated `@JsonIgnoreProperties(ignoreUnknown = true)` and have no such fields at all on
  the record — there is nothing for a malicious/malformed request body to bind to, not
  merely a value that gets silently ignored.

## Endpoints

### `POST /api/v1/students`

Creates a student account (`tenant_user` credential row + this module's `StudentProfile`
row, one transaction). Tenant Admin or Student Support only.

**Request body** (`StudentCreateRequest`):

```jsonc
{
  "name": "Jane Student",   // required, non-blank, max 255
  "email": "jane@example.com", // required, valid email format, max 255, unique per tenant
  "password": "correct horse" // required, min 8, max 255 — this module never hashes/stores
                               // it itself; delegated entirely to identity-access-service's
                               // UserProvisioningApi
}
```

No `tenantId`/`role`/`mustChangePassword` field — never accepted, doesn't exist on the DTO.

**Success — `201`** (`ApiResponse<StudentResponse>`): see "Response shapes" below.
`must_change_password` is always set `true` server-side (manual creation, not
self-registration — the two never share credential-hygiene defaults).

**Failure:**

| Status | `error.code` | Condition |
|---|---|---|
| `400` | `VALIDATION_ERROR` | `name`/`email`/`password` blank, malformed, or over max length |
| `403` | `FORBIDDEN` | Caller lacks `STUDENTS`/`CREATE_EDIT` (every role except Tenant Admin/Student Support) |
| `409` | `CONFLICT` | `email` already registered to a `tenant_user` in the caller's tenant. The pre-check (`existsByEmail`) is a friendlier UX path only — the actual guarantee against a concurrent double-creation race is `tenant_user`'s own `UNIQUE(tenant_id, email)` constraint enforced inside `provisionTenantUser`, proven by a two-thread concurrent-registration test |

### `GET /api/v1/students`

Lists every student in the caller's tenant. Tenant Admin, Student Support, or any
`VIEW`-holding staff role.

**Request:** no query parameters — **no pagination, search, or filter exists server-side**.
Always returns the full tenant-scoped list. (The frontend's search/status-filter UI is
entirely client-side over this full array — see plan §21's noted scalability
follow-up if tenant student counts grow large enough to warrant server-side paging later.)

**Success — `200`** (`ApiResponse<StudentResponse[]>`): array, possibly empty. An empty
array is a genuine "zero students in this tenant" result, not an error.

**Failure:**

| Status | `error.code` | Condition |
|---|---|---|
| `403` | `FORBIDDEN` | Caller has no `STUDENTS` grant at all (e.g. Teacher, Teacher Assistant, Student) |

### `GET /api/v1/students/{id}`

Reads one student by this module's own `StudentProfile.id` (not the `tenant_user` id).
Tenant Admin, Student Support, or any `VIEW`-holding staff role.

**Success — `200`** (`ApiResponse<StudentResponse>`).

**Failure:**

| Status | `error.code` | Condition |
|---|---|---|
| `403` | `FORBIDDEN` | Caller has no `STUDENTS` grant |
| `404` | `NOT_FOUND` | `{id}` doesn't exist, **or belongs to another tenant** — both cases return the identical `"Student account not found"` message and status, deliberately uniform so a cross-tenant `{id}` guess can't be distinguished from a genuinely nonexistent one. `TenantAwareRepository` makes this structural: a tenant-B row is invisible to a tenant-A `findById` call, not filtered after the fact. |

### `PATCH /api/v1/students/{id}`

Edits a student's profile. Tenant Admin or Student Support only. **Only `name` is
editable through this endpoint** — `email`, `role`, and `status` are not, and have no
corresponding field on the request DTO at all.

**Request body** (`StudentUpdateRequest`):

```jsonc
{ "name": "Jane A. Student" } // required, non-blank, max 255 — the only field
```

**Success — `200`** (`ApiResponse<StudentResponse>`): reflects the updated `name`.

**Failure:**

| Status | `error.code` | Condition |
|---|---|---|
| `400` | `VALIDATION_ERROR` | `name` blank or over max length |
| `403` | `FORBIDDEN` | Caller lacks `STUDENTS`/`CREATE_EDIT` |
| `404` | `NOT_FOUND` | Same uniform not-found/cross-tenant behavior as `GET .../{id}` — a cross-tenant edit attempt is proven (by a dedicated integration test) to leave the target row completely unchanged, not just rejected at the response-status level |

### `GET /api/v1/students/me`

Self-service read of the caller's **own** student profile. Student role only. **No `{id}`
path parameter exists on this endpoint** — the caller's own `user_id` is resolved from the
authenticated principal server-side.

**Success — `200`** (`ApiResponse<StudentResponse>`).

**Failure:**

| Status | `error.code` | Condition |
|---|---|---|
| `403` | `FORBIDDEN` | Caller does not hold `Role.STUDENT` (e.g. a Tenant Admin token) |
| `404` | `NOT_FOUND` | No `StudentProfile` row exists for this `tenant_user` (data-integrity edge case — should not occur for an account created through this module's own creation paths) |

### `PATCH /api/v1/students/me`

Self-service edit of the caller's own profile. Student role only, same no-`{id}` design as
`GET .../me`. Same request shape (`{ "name": "..." }`, only field) and failure table as
`GET .../me`, plus:

| Status | `error.code` | Condition |
|---|---|---|
| `400` | `VALIDATION_ERROR` | `name` blank or over max length |

## Response shapes

**`StudentResponse`** (used by every endpoint above):

```jsonc
{
  "id": "<uuid>",         // StudentProfile.id — NOT the tenant_user id
  "name": "Jane Student",
  "email": "jane@example.com", // read from tenant_user via UserProvisioningApi, never duplicated in this module's own table
  "roleCode": "STUDENT",  // always this literal value through every endpoint in this file
  "status": "ACTIVE"      // mirrors tenant_user.status ("ACTIVE" | "SUSPENDED") — this module has no separate status column, deliberately (see docs/plans/MVP-006 Student Management.md §8.1)
}
```

## Error codes reference

| Code | HTTP status(es) seen in this module | Meaning |
|---|---|---|
| `VALIDATION_ERROR` | 400 | Request body failed `@Valid` (Bean Validation); `fieldErrors` populated |
| `UNAUTHENTICATED` | 401 | No/invalid/expired access token |
| `SESSION_REVOKED` | 401 | Valid JWT, invalid session — see `docs/api/identity-access-service.md` |
| `FORBIDDEN` | 403 | Caller authenticated but lacks the required `STUDENTS` grant, or lacks `Role.STUDENT` on a `/me` endpoint |
| `NOT_FOUND` | 404 | `{id}` doesn't exist or belongs to another tenant (uniform, non-distinguishing) |
| `CONFLICT` | 409 | `email` already registered in the caller's tenant |

## Not covered by this contract (deliberately out of scope — see plan §20/§21)

No endpoint exists for: public self-registration, CSV bulk import, delete or
status-change (suspend/activate), student history/dashboard composition
(enrollment/payment/attendance/exam/device/communication), or a teacher roster read. Each
is blocked on an explicit, still-open business decision named in the module plan's §21 —
building any of these would mean guessing at an unresolved contract shape. If/when one of
these is authorized, its contract belongs in a new section of this file (or, if the shape
changes significantly, a revision flagged for explicit approval per this file's
change-controlled status), not silently assumed from the plan's draft §10.
