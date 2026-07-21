# Security Rules

These rules govern security-sensitive work: authentication, device/session
control, video/content protection, audit logging, and file uploads. They
apply in addition to — and do not restate — the baseline rules already in
root `CLAUDE.md` (secrets handling, tenant-context resolution, cross-tenant
test requirement, change control on auth architecture).

## Device Authentication & Account-Sharing Prevention

- Device registration must happen server-side at successful login, keyed to
  the authenticated user id (never a client-supplied device id alone —
  the server must generate/verify the device fingerprint/token).
- Device limit enforcement is a backend authorization check, evaluated in
  this override order (most specific wins):
  1. Student-level exception
  2. Course-level override
  3. Tenant-level override
  4. Plan-level default
  A login that exceeds the resolved limit must be rejected server-side
  (HTTP 401/403), not just flagged in the UI.
- Device reset (by admin/support) must:
  - Require an authorization check that the actor has device-reset
    permission for that tenant/student.
  - Enforce a cooldown period before the freed device slot can be reused
    for a new device registration — the cooldown must be enforced by the
    backend (e.g. persisted `reset_at` timestamp checked on next login),
    not simulated client-side.
  - Write an audit log entry (see Audit Logging below).
- Suspicious-login detection (e.g. impossible travel, rapid device churn,
  many distinct IPs for one account in a short window) must run
  server-side on the login/session path and must, at minimum, produce an
  audit/security log entry; do not rely on frontend heuristics.
- Every device-limit and device-reset code path requires a test proving:
  login beyond the limit is blocked, and the correct override in the
  precedence order wins.

## Video & Session Protection

Treat this as a security control, not a feature checklist. "Secure" means:

- Playback URLs must be short-lived, signed server-side, and scoped to a
  single user/session/video — never a stable or predictable URL. Expiry
  must be enforced by the video/storage provider or backend, never only by
  a countdown in the frontend player.
- Every playback request must validate an access token issued by the
  backend after the caller's enrollment, tenant, and device checks pass.
  Token validation (signature, expiry, single-use/session-bound where
  applicable) happens server-side; a valid-looking token must still be
  rejected if the underlying enrollment/access has since expired or been
  revoked.
- Tokens/signed URLs must not be guessable (no sequential or low-entropy
  identifiers) and must not be replayable after expiry or after the
  associated session/device is invalidated.
- Concurrent-session blocking (e.g. one active playback session per
  student per video/course, per the configured device policy) must be
  enforced by checking active-session state on the backend before issuing
  a new playback token, not by disabling buttons in the UI.
- IP/device anomaly signals detected during playback (e.g. token used from
  a second device/IP concurrently) must trigger session revocation
  server-side and an audit/security log entry.
- Any new video/session-protection code requires a test that proves an
  expired, revoked, or cross-tenant/cross-student token is rejected, and
  that a second concurrent session is blocked or logged as required by
  the configured policy.

## Audit Logging (Security-Sensitive Actions)

- The following actions must always produce an audit log entry, including
  actor id, tenant id, target entity, timestamp, and the actual change
  (before/after where applicable): course/session price changes, payment
  approvals/rejections, device resets, access/expiry extensions,
  reactivation approvals, material/course content deletions, settlement
  amount changes, and any user-impersonation session start/end.
- Audit log writes must happen server-side, inside the same
  transaction/service boundary as the privileged action — never
  client-triggered as a separate, skippable call.
- Audit log entries are append-only: no update or delete endpoint/repository
  method may target the audit log table. Do not add UI or API affordances
  to edit or purge audit history.
- Audit logs are themselves tenant-owned data and must be filtered by the
  resolved tenant context like any other tenant-owned table — an admin of
  tenant A must never be able to list or search tenant B's audit log.
- User impersonation must be logged both at start and end of the
  impersonation session, capturing the impersonating admin's identity
  distinctly from the impersonated user's identity in every subsequent
  action taken during that session (do not collapse both identities into
  a single actor id).

## Upload Validation & Protected-Content Access

- Every upload endpoint must validate, server-side, before accepting the
  file: MIME type/content sniffing (not just file extension), maximum
  size, and ownership/permission of the uploading actor for the target
  tenant/course/entity. Reject on failure with no partial write to
  storage.
- Uploaded/protected content (course video, payment slips, protected
  documents) must never be reachable via a direct, predictable
  URL/ID — every fetch of protected content must pass through an
  authorization check that confirms the requester's tenant, enrollment,
  and role for that specific resource, not just that the resource id
  exists.
- Do not assume an unguessable filename or UUID alone is sufficient
  access control — enumeration/ID-guessing across tenants and across
  roles (e.g. a student from tenant A requesting a video id belonging to
  tenant B, or a student requesting a payment slip belonging to another
  student) must be an explicit negative test case wherever protected
  content is served.
- Payment slip uploads additionally require duplicate detection
  (reference number and image hash) to run server-side before
  acceptance/approval, per the Payment Slip Intelligence requirements;
  any manual override of a duplicate-slip flag is itself an audit-logged
  action.
