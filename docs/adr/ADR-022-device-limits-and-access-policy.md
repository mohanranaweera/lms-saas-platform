# ADR-022: Device Limits and the Centralized Access Policy

## Status

Accepted for implementation (2026-10-01) — Wave 10. The three product decisions below (D1–D3)
were taken by the product owner at the start of the wave. **D1 touches the change-controlled
"enrollment activation rules" area and must be confirmed again at PR review** before merge.

## Context

- ADR-007 §2 chose a server-issued device identifier but nothing implemented it:
  `device_session.device_identifier_hash` was a SHA-256 of User-Agent + IP, and no device
  limit, reset, cooldown or device list existed.
- Six domains each re-implemented "is this student entitled to this course right now"
  (`MaterialAccessGuard`, `VideoAccessGuard`, `LiveClassAccessGuard`, `CourseAccessGuard`,
  `ExamAttemptService`, `ExamSchedulingService`), and two more re-implemented availability
  windows (materials, video). Master instruction §29 asks for one `AccessPolicyService`.
- `enrollment.access_expires_at` is immutable (ADR-013 lineage model), so a manual extension
  cannot be an `UPDATE`.

## Decisions

### 1. Device identity
A 256-bit random token is issued by the server at sign-in and kept by the browser in the
`lms_device_id` cookie (`HttpOnly`, `SameSite=Strict`, `Secure` outside `local`,
`Path=/api/v1/auth`, 400 days). Only its SHA-256 is stored (`device.token_hash`, V64).
User-Agent produces a display label only; IP is stored as `last_ip` for staff review only.
Neither is ever used to identify a device. Platform Admin sessions are out of scope and keep
the old fingerprint column semantics.

### 2. Device limits (students only)
- Precedence: **student exception > course override > tenant policy > plan default**
  (`docs/architecture/authentication-authorization.md` §6). The most specific configured level
  wins.
- **D2:** the plan default is **2** (`app.security.device.plan-default-limit`).
- At sign-in (no course known): student > tenant > plan. A new device when every slot is taken
  → `403 DEVICE_LIMIT_REACHED`, checked only after the password is verified. A known device
  always signs in. Registration is serialized per user (`pg_advisory_xact_lock`), so concurrent
  sign-ins cannot both take the last slot.
- At course-content time: a course `max_devices` override applies through `AccessPolicyApi`;
  the authorized devices are the student's N earliest-registered active devices; any other
  device gets `403 DEVICE_NOT_AUTHORIZED`. A student exception outranks it.
- Staff devices are registered and listed but never limited.

### 3. Revocation, reset and cooldown
Revoking a device (admin reset, or student self-service when the tenant enables it) is a
status change — never a delete — that revokes every session on it (Postgres + Redis, same
transaction) and sets `slot_reusable_at = now + tenant cooldown` (default 24 h). A slot in
cooldown still counts as taken, so devices cannot be swapped to share an account. Refresh is
bound to the session's own device cookie; pre-Wave-10 sessions (no device) are refused at
their next refresh, forcing one re-login.

### 4. Centralized access policy
`enrollment-management` owns `AccessPolicyApi`. One `AccessWindowCalculator` computes
`effectiveExpiresAt = max(access_expires_at, newest extension)` and
`accessEndsAt = effectiveExpiresAt + grace`. `resolveAccessState` and every "currently
enrolled" list use it, and every content guard calls `AccessPolicyApi` (entitlement, window,
course device limit) instead of its own comparison. Material and video availability windows
are evaluated by the same API. Each domain keeps its own anti-enumeration 404 for
not-enrolled/expired.

### 5. Grace period (D3)
Default **0 days** (today's hard cutover). Tenant setting, overridden per course
(course beats tenant). A student in grace is ACTIVE.

### 6. Manual extension (D1)
A Tenant Admin (`ACCESS_EXPIRY`/`CREATE_EDIT`) may extend the **current** enrollment of a
student — **whether still active or already expired** — with a mandatory reason. A revoked or
superseded enrollment can never be extended; a lifetime enrollment has nothing to extend. Each
extension is a new row in `enrollment_access_extension` (append-only: repository forbids
deletes, a DB trigger rejects UPDATE/DELETE) and is audit-logged on the enrollment and the
student profile. Payments, ledger and the enrollment row are never touched and no enrollment is
created, so paid reactivation (ADR-013) is unchanged. Bulk extension is not built (its
second-approver question is still open).

## Consequences

- One place decides content access; adding a new content type means calling `AccessPolicyApi`.
- Students may be asked to sign in once after deploy (legacy sessions). A course device limit
  denies a legacy session until it re-logs in.
- Known edge case (not handled, change-controlled path): if a reactivation order was created
  while the enrollment was expired and an admin then extends access, confirming that order
  later supersedes the enrollment and the new paid window replaces the extension.
- The device-slot invariant is enforced by a per-user advisory lock plus a count, not by a
  unique slot-number constraint, because cooldown slots make a fixed slot index unnatural.
- The first expiry of an enrollment is recorded once (`enrollment_expiry_event`); after an
  extension and a second expiry no second event is written.

## Alternatives rejected

- Updating `enrollment.access_expires_at` for extensions — breaks the ADR-013 lineage model.
- Browser fingerprinting or User-Agent/IP identity — rejected by ADR-007 and the Wave 10 brief.
- Enforcing course device limits at sign-in — no course is known there.

## Related

ADR-007, ADR-013, ADR-016, `docs/parity/waves/wave-10-plan.md`,
`docs/api/identity-access-service.md` (Devices), `docs/api/enrollment-management.md`
(Access policy), `docs/api/user-management.md` (Student devices).
