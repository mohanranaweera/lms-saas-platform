# user-management — Student & Teacher Management API Contract

Covers the Student Management module (MVP-006, `STU-2`'s admin-authenticated slice plus
the student self-service view/edit endpoints) **and**, as of Wave 3, both the Student
self-registration/bulk-import/lifecycle-action surface and the Teacher Management module
(MVP-007/`TCH-1`, `com.lms.usermanagement.teacher` package) — one file per
`.claude/rules/architecture.md`'s confirmed `user-management` domain, not two separate
files, since Student and Teacher share one top-level Java package.

Originally (MVP-006) this file documented only six Student endpoints and explicitly
deferred self-registration, bulk-import, delete/status-change, history composition, and
teacher roster pending unresolved business decisions (`docs/plans/MVP-006 Student
Management.md` §20/§21). **Wave 3** ("Student and Teacher operational profiles",
`docs/parity/waves/wave-03-plan.md`) resolved those decisions and shipped the deferred
surface — this file now documents the actual, current contract, including two
architectural corrections a Wave 3 fix-pass made after the wave's own plan doc: the
staff "enroll student" endpoint moved to `payment-management` (see that domain's own
contract file) rather than staying in this module, and the studentId/teacherId-scoped
cross-domain reads (enrollments/ledger/attendance/exams) each live in their **owning**
domain's own contract file, not duplicated here — this file documents only what
`com.lms.usermanagement` itself actually owns and exposes.

Like `docs/api/identity-access-service.md`, the original Student section was written
retroactively — after the backend implementation shipped — rather than before, per
`docs/api/README.md`'s "Process gap" precedent; the Teacher section below has the same
retroactive-documentation history (Teacher Management shipped in MVP-007 with no
contract doc at all until this wave's documentation pass produced one for the first
time).

Staff Management's `com.lms.usermanagement.staff` package (MVP-005, already merged) has
the identical undocumented-contract gap — still out of scope for this file, flagged in
`docs/requirements/open-decisions.md`.

## Response envelope

Every endpoint below returns `com.lms.common.api.ApiResponse<T>` (see
`docs/api/identity-access-service.md`'s "Response envelope" section for the exact shape —
identical here, not repeated).

## Auth requirements

- Every endpoint in this file requires a valid `Authorization: Bearer <accessToken>`
  header, **except** the three public self-registration endpoints (`POST
  /students/register`, `.../register/otp/send`, `.../register/otp/verify`) and the public
  registration-policy read (`GET /public/tenant-config/student-registration-policy`) — see
  "Public student self-registration (Wave 3)" below. Missing/invalid/expired token on any
  other endpoint → `401 UNAUTHENTICATED`/`SESSION_REVOKED` (see
  `docs/api/identity-access-service.md`'s "Session invalidity" section — identical
  mechanism, not repeated).
- The staff-facing endpoints (`POST /students`, `GET /students`, `GET /students/{id}`,
  `PATCH /students/{id}`, `POST /students/bulk-import`, `POST /students/{id}/activate`,
  `.../deactivate`, `.../reset-password`) are gated by
  `@PreAuthorize("@permissionCheckService.hasPermission('STUDENTS', '<ACTION>')")`, backed
  by a second, independent `PermissionCheckService.requirePermission(...)` call inside
  `StudentService`/`StudentBulkImportService` (defense-in-depth — the service layer never
  trusts the controller annotation alone). Per `docs/requirements/user-roles-and-permissions.md`
  §2's Students row: Tenant Admin and Student Support hold `CREATE_EDIT` (and Tenant Admin
  alone holds `DELETE`, not exposed by any endpoint here); every other named staff role
  (Finance Staff, Course Coordinator, Content Manager, Exam Manager, Attendance Operator,
  Read-only Auditor) holds `VIEW` only. A caller with neither grant → `403 FORBIDDEN`.
- `GET /students/{id}/activity` is gated only `@PreAuthorize("isAuthenticated()")` at the
  controller — the real, two-layer authorization decision (coarse `AUDIT_LOG`/`VIEW` grant,
  plus a narrower `TENANT_ADMIN`/`READ_ONLY_AUDITOR`-only allowlist) happens inside
  `AuditLogApi.findForTarget` itself; see `docs/api/audit-log-management.md`'s "Per-target
  Activity endpoints" section for the full authorization model — not repeated here.
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
  the authenticated session's tenant context (or, for the public registration endpoints,
  from the request's subdomain via `TenantResolutionFilter`), role is always the literal
  `"STUDENT"` server-side, and `mustChangePassword` is always `true` for every account this
  module creates. `StudentCreateRequest`/`StudentUpdateRequest`/`StudentRegistrationRequest`
  are annotated `@JsonIgnoreProperties(ignoreUnknown = true)` and have no such fields at all
  on the record — there is nothing for a malicious/malformed request body to bind to, not
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

### `POST /api/v1/students/bulk-import` (Wave 3, PAR-03-03)

Multipart CSV import. Tenant Admin or Student Support only (`STUDENTS`/`CREATE_EDIT`).

**Request**: `multipart/form-data`, field name `file` — a CSV with columns
`name,email,password` (one optional header row, auto-detected by the presence of both
"name" and "email" in the first non-blank line). Max file size **2MB**, max **1000 data
rows**. Server-side content-sniffing accepts `.csv`-named files or a small allowlist of
plausible content-types (`text/csv`, `application/vnd.ms-excel`, `text/plain`,
`application/csv`, `application/octet-stream`) — never trusts the extension alone.

**Success — `200`** (`ApiResponse<BulkImportRowResultResponse[]>`) — **always `200`, even
if every row failed** (continue-on-error, per-row result; an explicit judgment call
flagged in `wave-03-plan.md` §10 item 1, not silently decided as all-or-nothing):

```jsonc
[
  { "row": 1, "status": "CREATED", "reason": null, "studentId": "<uuid>" },
  { "row": 2, "status": "FAILED", "reason": "email already registered to a tenant_user in this tenant", "studentId": null }
]
```

Each row is its own independent transaction (`StudentService#createStudent`, called
per-row) — one row's failure/rollback never affects another row's already-committed
success. Guardian/school/grade/stream/mobile columns are **not** part of the bulk-import
row shape — every bulk-imported account is `registrationStatus = ADMIN_CREATED`, same as
`POST /students`, with `mustChangePassword = true`.

**Failure (whole request, before any row is attempted):**

| Status | `error.code` | Condition |
|---|---|---|
| `403` | `FORBIDDEN` | Caller lacks `STUDENTS`/`CREATE_EDIT` |
| `415` | `UNSUPPORTED_MEDIA_TYPE` | Missing/empty file, or the file doesn't look like a CSV |
| `413` | `PAYLOAD_TOO_LARGE` | File exceeds 2MB, or the CSV has more than 1000 data rows |

### `POST /api/v1/students/{id}/activate` / `POST /api/v1/students/{id}/deactivate` (Wave 3)

Tenant Admin or Student Support only (`STUDENTS`/`CREATE_EDIT`). Thin wrappers over
`UserProvisioningApi#activateTenantUser`/`#suspendTenantUser` — flips `tenant_user.status`
between `ACTIVE`/`SUSPENDED`, immediately affecting login. Each writes a real
`AuditLogApi.record(...)` entry (`student.activated`/`student.deactivated`) in the same
transaction as the status change, closing the pre-Wave-3 gap where this module emitted
only an SLF4J trace line, not a durable audit row.

**Success — `200`** (`ApiResponse<StudentResponse>`, reflecting the new `status`).
**`404`** — same uniform not-found/cross-tenant convention as `GET .../{id}`. **`403`** —
caller lacks `STUDENTS`/`CREATE_EDIT`.

### `POST /api/v1/students/{id}/reset-password` (Wave 3)

Tenant Admin or Student Support only (`STUDENTS`/`CREATE_EDIT`). Generates a random
temporary password via a new `UserProvisioningApi#resetPassword(userId)` method, sets
`mustChangePassword = true`, and returns the temporary password **once**, in this
response only — never stored, never logged (per `.claude/rules/security.md`'s
audit-logging requirement, the audit entry records that a reset occurred, never the
password value). Audit-logged as `student.password_reset` (no `reason` field — this is a
standard admin action, not an override of a flag).

**Success — `200`** (`ApiResponse<TemporaryPasswordResponse>`):

```jsonc
{ "temporaryPassword": "Xk4-correct-horse-9pQ" }
```

**`404`**/**`403`** — same conventions as above.

### `GET /api/v1/students/{id}/activity` (Wave 3)

Staff-only, per-student Activity tab — the actual audit trail behind Student Detail's
Activity tab. See `docs/api/audit-log-management.md`'s "Per-target Activity endpoints"
section for the full authorization model (two-layer gate: `AUDIT_LOG`/`VIEW` plus a
`TENANT_ADMIN`/`READ_ONLY_AUDITOR`-only allowlist) and the dual-audit-row behavior for
staff-granted enroll/revoke actions — not repeated here.

**Query params**: standard Spring `Pageable` (`page`/`size`/`sort`, default `size=20`,
`sort=occurredAt,DESC`).

**Success — `200`** (`ApiResponse<PageResponse<StudentActivityResponse>>`):

```jsonc
{
  "content": [
    {
      "id": "<uuid>", "actorId": "<uuid>", "actorDisplayName": "admin@example.test",
      "action": "student.deactivated", "reason": null, "metadata": null,
      "occurredAt": "2026-09-20T10:00:00Z"
    }
  ],
  "page": 0, "size": 20, "totalElements": 1, "totalPages": 1
}
```

**`404`** — `{id}` doesn't exist or belongs to another tenant, checked by `StudentService`
itself (`existsById`) **before** `findForTarget` is ever called, so a cross-tenant id is
never surfaced as `200` with an empty page. **`403`** — caller lacks `AUDIT_LOG`/`VIEW`, or
holds it but isn't `TENANT_ADMIN`/`READ_ONLY_AUDITOR`.

## Public student self-registration (Wave 3, PAR-03-01)

Replaces the previously disabled `(auth)/register` placeholder with a real, tenant-driven
workflow. All four endpoints below are **unauthenticated** (`permitAll()`), with tenant
identity resolved exclusively from the request's subdomain via the existing
`TenantResolutionFilter` (never a client-supplied tenant field) — mirroring
`PublicBrandingController`'s established precedent, not a second resolution mechanism.

### `GET /api/v1/public/tenant-config/student-registration-policy`

Owned by `tenant-management` (`PublicStudentRegistrationPolicyController`), documented in
full in `docs/api/tenant-configuration-management.md`'s "Public student
registration-policy read" section — cross-referenced here because the frontend
registration form fetches this **before** rendering any field, and the endpoints below are
meaningless without it.

### `POST /api/v1/students/register/otp/send`

Email-only. Request body: `{ "email": "..." }`. Rate-limited per `(tenant, email)`,
uniform outcome regardless of whether the email already has an account (no account exists
yet at send time, so there is nothing to enumerate). **`200`** (`ApiResponse<Void>`) on
every call that isn't itself rate-limited.

### `POST /api/v1/students/register/otp/verify`

Request body: `{ "email": "...", "otp": "..." }`. Uniform failure (`409 CONFLICT`, generic
message) for every failure mode — wrong code, expired, already consumed, attempt limit
exhausted, or no OTP row at all — never distinguishing which, per
`.claude/rules/security.md`'s anti-enumeration requirement. **`200`** (`ApiResponse<Void>`)
on success; the verified state is usable as registration evidence for 15 minutes.

### `POST /api/v1/students/register`

Request body (`StudentRegistrationRequest`):

```jsonc
{
  "name": "Jane Student", "email": "jane@example.com", "password": "correct horse",
  "guardianName": null, "guardianPhone": null, "school": null, "grade": null,
  "stream": null, "mobile": null   // all six optional at the DTO level — required-ness
                                     // is resolved server-side against the calling
                                     // tenant's ConfigDomain.STUDENT policy, never a
                                     // client claim
}
```

Gated by the calling tenant's registration policy: `404 NOT_FOUND` ("Student
self-registration is not available") if `public_registration_enabled = false` (the
cross-tenant-404-style convention — availability is never revealed via a distinguishable
status code). `400 VALIDATION_ERROR` (with per-field `fieldErrors`) if a
tenant-required field is missing. `409 CONFLICT` ("Email verification is required before
registering") if `otp_required = true` and no recently-verified OTP exists for this
email. `409 CONFLICT` ("An account with this email already exists") on a duplicate email.

If `approval_required = true`, the created account starts `tenant_user.status =
SUSPENDED` (cannot log in until a staff `POST /students/{id}/activate` call) and
`student_profile.registration_status = SELF_REGISTERED` (distinguishing a
pending-self-registered account from a staff-deactivated one in the activate/deactivate
UI). Writes `student.self_registered` to the audit log.

**Success — `201`** (`ApiResponse<StudentRegistrationResponse>`):

```jsonc
{ "studentProfileId": "<uuid>", "email": "jane@example.com", "pendingApproval": true }
```

`pendingApproval` mirrors the tenant's `approval_required` policy **as evaluated at
registration time** — the frontend uses this live response value, not its earlier
prefetch of the policy, to render the post-submit "pending approval" vs. "you can sign in
now" state.

## Response shapes

**`StudentResponse`** (used by most endpoints above):

```jsonc
{
  "id": "<uuid>",         // StudentProfile.id — NOT the tenant_user id
  "name": "Jane Student",
  "email": "jane@example.com", // read from tenant_user via UserProvisioningApi, never duplicated in this module's own table
  "roleCode": "STUDENT",  // always this literal value through every endpoint in this file
  "status": "ACTIVE"      // mirrors tenant_user.status ("ACTIVE" | "SUSPENDED") — this module has no separate status column, deliberately (see docs/plans/MVP-006 Student Management.md §8.1)
}
```

**`BulkImportRowResultResponse`**: `{ row: number, status: "CREATED"|"FAILED", reason:
string|null, studentId: uuid|null }` — see `POST /students/bulk-import` above.

**`TemporaryPasswordResponse`**: `{ temporaryPassword: string }` — see `POST
/students/{id}/reset-password` above.

**`StudentActivityResponse`**: `{ id, actorId, actorDisplayName, action, reason,
metadata, occurredAt }` — one row of `GET /students/{id}/activity`'s response, same shape
as `docs/api/audit-log-management.md`'s `AuditLogEntryResponse` minus `targetEntity`/
`targetId` (both already implied by which endpoint/id was requested).

**`StudentRegistrationResponse`**: `{ studentProfileId, email, pendingApproval }` — see
`POST /students/register` above.

## Error codes reference

| Code | HTTP status(es) seen in this module | Meaning |
|---|---|---|
| `VALIDATION_ERROR` | 400 | Request body failed `@Valid` (Bean Validation), or a tenant-required registration field is missing; `fieldErrors` populated |
| `UNAUTHENTICATED` | 401 | No/invalid/expired access token |
| `SESSION_REVOKED` | 401 | Valid JWT, invalid session — see `docs/api/identity-access-service.md` |
| `FORBIDDEN` | 403 | Caller authenticated but lacks the required `STUDENTS`/`AUDIT_LOG` grant, or lacks `Role.STUDENT` on a `/me` endpoint |
| `NOT_FOUND` | 404 | `{id}` doesn't exist or belongs to another tenant (uniform, non-distinguishing); or self-registration is unavailable for this tenant |
| `CONFLICT` | 409 | `email` already registered in the caller's tenant; or OTP verification failed/expired/exhausted; or OTP verification required but not completed |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | Bulk-import file missing, empty, or not CSV-shaped |
| `PAYLOAD_TOO_LARGE` | 413 | Bulk-import file over 2MB, or over 1000 data rows |

## Not covered by this contract (deliberately out of scope — see `wave-03-plan.md` §11)

No endpoint exists in this file for: device history/device reset (no device-slot domain —
Wave 10), staff-side notification history (Wave 11), or a generic "extend access" action
beyond the existing reactivation-request flow (no centralized `AccessPolicyService` —
Wave 6). The staff "enroll student" action, per-enrollment "revoke," and the
studentId-scoped enrollments/ledger/attendance/exams reads are **not missing** — they are
real and shipped, but live in their owning domains' own contract files
(`docs/api/payment-management.md`, `docs/api/enrollment-management.md`,
`docs/api/ledger-settlement-management.md`, `docs/api/attendance-management.md`,
`docs/api/exam-management.md`), per `.claude/rules/architecture.md`'s "pick the domain
that owns the primary aggregate" rule — this file intentionally does not duplicate them.

---

# Teacher Management (`com.lms.usermanagement.teacher`)

Covers MVP-007/`TCH-1` (base create/list/get/approve/reject) plus Wave 3's additions
(`suspend`/`reactivate`, the per-teacher Activity tab). Written for the first time during
Wave 3's documentation pass — this module shipped in MVP-007 with no contract file at all
until now (the same "process gap" `docs/api/payment-management.md`/`course-management.md`
record for their own modules).

## Auth requirements

- Every endpoint requires a valid `Authorization: Bearer <accessToken>` header.
  Missing/invalid/expired token → `401 UNAUTHENTICATED`/`SESSION_REVOKED`.
- `POST /teachers`, `GET /teachers`, `GET /teachers/{id}` are gated by
  `@PreAuthorize("@permissionCheckService.hasPermission('TEACHERS', '<ACTION>')")`. Per
  `docs/requirements/user-roles-and-permissions.md` §2's Teachers row: Tenant Admin holds
  `V/C/E/D` (delete not exposed by any endpoint here), Course Coordinator holds `V/C/E`,
  every other staff role holds `V` only or nothing.
- `POST /teachers/{id}/approve`, `.../reject`, `.../suspend`, `.../reactivate` are all
  gated the same way at the controller (`TEACHERS`/`CREATE_EDIT`) — **but each is
  additionally, independently narrowed to Tenant-Admin-only inside `TeacherService`** via
  a hardcoded `requireTenantAdmin()` check, since `PermissionCheckService` has no finer
  primitive than `CREATE_EDIT` for "same domain, higher-trust action, different actor."
  This means **Course Coordinator, despite holding `TEACHERS`/`CREATE_EDIT`, is rejected
  `403` on all four of these transitions** — a deliberate, carried-forward asymmetry (not
  resolved by Wave 3), flagged in `docs/parity/rbac-impact-analysis.md` §4.
- `GET /teachers/{id}/activity` is gated only `@PreAuthorize("isAuthenticated()")` at the
  controller — the real authorization (coarse `AUDIT_LOG`/`VIEW` grant plus a narrower
  `TENANT_ADMIN`/`READ_ONLY_AUDITOR`-only allowlist) happens inside `AuditLogApi
  .findForTarget`; see `docs/api/audit-log-management.md`'s "Per-target Activity
  endpoints" section — not repeated here.
- No endpoint accepts a client-supplied `tenantId`, `roleCode`, or `approvalStatus` field
  — tenant is always server-resolved, role is always literally `"TEACHER"`, and every
  admin-created teacher always starts `PENDING` with `mustChangePassword = true`.
  `TeacherCreateRequest` has no such fields on the record at all.

## Endpoints

### `POST /api/v1/teachers`

Creates a teacher account (`tenant_user` + `TeacherProfile`, one transaction), always
starting `approvalStatus = PENDING` and login-suspended until approved. Tenant Admin or
Course Coordinator.

**Request body** (`TeacherCreateRequest`): `{ name, email, password }` — identical shape
and validation to `StudentCreateRequest` above.

**Success — `201`** (`ApiResponse<TeacherResponse>`). **Failure**: same
`400`/`403`/`409` pattern as `POST /students` above (`TEACHERS`/`CREATE_EDIT` instead of
`STUDENTS`/`CREATE_EDIT`).

### `GET /api/v1/teachers`

Lists every teacher in the caller's tenant. Query param: `approvalStatus` (optional, one
of `PENDING`/`APPROVED`/`REJECTED`/`SUSPENDED`) — omitted returns every status, no
pagination. **`200`** (`ApiResponse<TeacherResponse[]>`).

### `GET /api/v1/teachers/{id}`

Reads one teacher by `TeacherProfile.id`. Same uniform `404` (not-found/cross-tenant
indistinguishable) convention as the Student equivalent.

### `POST /api/v1/teachers/{id}/approve` / `POST /api/v1/teachers/{id}/reject`

`PENDING → APPROVED`/`PENDING → REJECTED` only — one-directional, terminal
(`requirePending()` in `TeacherProfile`). Tenant-Admin-only (see "Auth requirements"
above). `approve` calls `UserProvisioningApi#activateTenantUser`; both write a real
`AuditLogApi.record(...)` entry (`teacher.approved`/`teacher.rejected`). **`409
CONFLICT`** if the profile isn't currently `PENDING`.

### `POST /api/v1/teachers/{id}/suspend` / `POST /api/v1/teachers/{id}/reactivate` (Wave 3, PAR-04-04)

A second, independent transition pair layered on top of approve/reject —
`APPROVED ⇄ SUSPENDED` only, **never** reachable from `PENDING`/`REJECTED`
(`TeacherProfile#suspend`/`#reactivate`, backed by the widened `ApprovalStatus` enum,
`V43__add_teacher_suspended_status.sql`). Tenant-Admin-only, same gate as approve/reject —
an explicit judgment call carrying forward existing precedent rather than resolving
`rbac-impact-analysis.md` §4's separate open question about a distinct Teacher-approval
permission (see `wave-03-plan.md` §10 item 2). `suspend` additionally calls
`UserProvisioningApi#suspendTenantUser` (blocks login immediately); `reactivate` calls
`activateTenantUser`. Both write a real audit entry (`teacher.suspended`/
`teacher.reactivated`).

**Success — `200`** (`ApiResponse<TeacherResponse>`). **`409 CONFLICT`** — the profile is
not currently in the required source state (`APPROVED` for `suspend`, `SUSPENDED` for
`reactivate`). **`403`** — caller lacks `TEACHERS`/`CREATE_EDIT`, or holds it but isn't
Tenant Admin.

### `GET /api/v1/teachers/{id}/activity` (Wave 3)

Same shape and authorization model as `GET /students/{id}/activity` above, scoped to
`targetEntity = "teacher_profile"`. **Response**: `ApiResponse<PageResponse
<TeacherActivityResponse>>` — identical per-row shape to `StudentActivityResponse`.

## `TeacherResponse` shape

```jsonc
{
  "id": "<uuid>",              // TeacherProfile.id
  "name": "Jane Teacher",
  "email": "jane.teacher@example.com",
  "approvalStatus": "APPROVED", // PENDING | APPROVED | REJECTED | SUSPENDED (Wave 3)
  "accountStatus": "ACTIVE",    // mirrors tenant_user.status ("ACTIVE" | "SUSPENDED")
  "approvedBy": "<uuid or null>",
  "approvedAt": "2026-08-01T00:00:00Z or null"
}
```

Never exposes a password/hash field — no such field exists anywhere in this chain.

## Not covered by this contract

No `PATCH /teachers/{id}` (edit) or self-service `/me` endpoint exists for Teacher — a
pre-existing scope boundary, unchanged by Wave 3. Assigned-courses/roster/attendance/exams
reads for a given teacher live in their owning domains (`course-management`,
`enrollment-management`'s `GET /courses/{courseId}/roster`, `attendance-management`,
`exam-management`), not duplicated here.
