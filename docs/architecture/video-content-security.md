# Video, Session, and Protected-Content Security Architecture

> **Note on change control**: this document does not itself define the
> multi-tenancy strategy or authentication architecture (see
> `docs/architecture/authentication-authorization.md` for the latter), but it
> depends on both. Any change to how tenant/enrollment/role checks gate
> access to protected content, or any change that would make video/session
> protection a separately deployed service or introduce a new datastore for
> it, requires an ADR per `.claude/rules/architecture.md` ("When an ADR is
> required") before implementation.

## 1. Purpose and scope

This document describes the required security properties for:

- Course video playback (signed URLs, access tokens, session protection).
- Protected document/material access (PDFs, notes, teacher materials).
- Payment slip and other protected-file uploads.

It describes required **behaviors and properties**, not specific
cryptographic algorithms, token formats, or vendor products — those are
called out explicitly in the Open Questions section as decisions still
needed.

## 2. Ownership boundary

- `video-access-management` and `content-management` own video/material
  access logic and are the only domains that issue or validate playback
  tokens/signed URLs.
- Per `.claude/rules/architecture.md`, video/content storage is **external**
  and never self-hosted on the application VPS — the backend integrates
  with an external secure video/object storage provider and works in terms
  of signed URLs/tokens, never by streaming or storing binary media through
  the Spring Boot app itself.
- `integration-management` owns the actual third-party storage/video
  provider credentials; `video-access-management`/`content-management` call
  it through `api` interfaces rather than embedding provider SDKs or
  credentials directly.
- Application instances remain stateless: no in-JVM cache is treated as
  authoritative for active-session/concurrency state. Redis is used for
  ephemeral state (active session tracking, rate limiting) per
  `.claude/rules/architecture.md`, with PostgreSQL as the durable source of
  truth for anything that must survive a Redis flush (enrollment/access
  state, audit/security log entries).

## 3. Playback URL and access token properties

Every playback URL/token issued to a caller must have these properties:

- **Short-lived** — a bounded, short expiry window; not a long-lived or
  effectively permanent link.
- **Signed server-side** — the backend (or the external storage/video
  provider, on the backend's instruction) produces the signature; the
  client never constructs or extends a valid URL/token itself.
- **Scoped to a single user, session, and video** — a token issued for
  student X's session to watch video Y must not play video Z, and must not
  be usable by a different authenticated user, even within the same tenant.
- **Non-predictable** — no sequential or low-entropy identifiers; a caller
  must not be able to derive one valid token/URL from another by
  incrementing or guessing an id.
- **Non-replayable after expiry** or after the associated session/device is
  invalidated (see Section 5).

## 4. Preconditions for token issuance

Before a playback token/signed URL is issued, the backend must verify, in
this order, and reject (401/403) if any fail:

1. **Authentication** — caller is an authenticated principal (see
   `docs/architecture/authentication-authorization.md`).
2. **Tenant match** — the video/course belongs to the caller's resolved
   tenant.
3. **Enrollment/access** — the caller has current, non-expired,
   non-revoked enrollment/access to the specific course/video (per Smart
   Expiry/Access Control requirements — course, session, material, and
   video expiry are all independently checked, not inferred from
   enrollment alone).
4. **Device policy** — the caller's current device passes device
   authentication/limit checks (see
   `docs/architecture/authentication-authorization.md`, Sections 5–6).
5. **Concurrent-session policy** — no existing active playback session for
   this student/video/course conflicts with the configured device policy
   (see Section 6).

A token must never be issued on the basis of any of these checks having
"passed in the past" without re-verification at issuance time — enrollment
or device status may have changed since the last check.

## 5. Server-side token validation

- Every playback request validates the token/signed URL **server-side**
  (or via the external provider, on backend-issued rules) — signature
  validity, expiry, and single-use/session-bound constraints where
  applicable.
- Validation must reject a token that is **signature-valid and unexpired**
  if the underlying enrollment/access it depended on has since been
  revoked or expired. A valid-looking token is not sufficient — the
  authorization state it depends on must still hold at the moment of use,
  not only at the moment of issuance.
- Tokens must not be replayable after expiry or after the associated
  session/device has been invalidated (e.g. by a device reset, a
  suspicious-activity revocation, or an admin-initiated access revocation).

## 6. Concurrent-session blocking

- The system enforces one active playback session per student per
  video/course, per the tenant/course's configured device policy — this is
  checked against **backend-tracked active-session state** before issuing
  a new playback token, not by disabling a button in the frontend player.
- Active-session state is ephemeral (Redis-backed per
  `.claude/rules/architecture.md`) but the policy decision (device
  limit/session-count configuration) is authoritative data in PostgreSQL.
- If a second concurrent session is attempted beyond the configured limit,
  the new token request is rejected server-side (not merely warned about),
  and this must be covered by a Testcontainers/real-Redis test per
  `.claude/rules/testing.md`'s required-test matrix ("Concurrent session /
  view-limit enforcement").

## 7. IP/device anomaly detection during playback

- IP/device anomaly signals detected during an active playback session
  (e.g. the same token or session used concurrently from a second
  device/IP) must trigger:
  1. Server-side session revocation (the active playback session is
     invalidated; any outstanding token for it is rejected on next
     validation).
  2. An audit/security log entry recording the anomaly (actor/student id,
     tenant id, video/course id, signal type, timestamp).
- This detection runs on the backend/playback-validation path, not as a
  frontend heuristic.

## 8. Screen recording — explicit non-claim

Per `docs/requirements/source-requirements.md` (Video & Session
Protection): **no system can fully prevent screen recording.** This
architecture does not claim to prevent it. Instead, the platform
*discourages* it through a defense-in-depth combination of:

- A visible student watermark (e.g. student name/identifier) rendered on
  the video during playback.
- Dynamic/moving watermark placement (not a static, easily-cropped
  position).
- Device authentication (Sections 5–6 of the authentication document).
- Session logging (every playback session and validation attempt is
  logged).
- Playback token expiry (Section 3 above).
- IP/device anomaly detection (Section 7 above).

Any product or marketing claim, documentation, or UI copy stating that the
platform "prevents" screen recording or piracy outright is inaccurate and
must be corrected to describe deterrence/detection, not prevention.

## 9. Upload validation (video, materials, payment slips, protected documents)

Every upload endpoint (course video, learning materials, payment slips,
protected documents) must validate, **server-side**, before accepting the
file:

- **MIME type/content sniffing** — validated from actual file content, not
  just the file extension or client-declared `Content-Type`.
- **Maximum size** — enforced server-side, not only via a client-side form
  constraint.
- **Ownership/permission** — the uploading actor must be authorized for the
  target tenant/course/entity (e.g. a Teacher may upload materials only for
  a course they are assigned to; a Student may upload a payment slip only
  for their own order/payment).
- **Reject on failure with no partial write to storage** — a failed
  validation must not leave a partial or unvalidated object in the external
  storage provider.
- Payment slip uploads additionally require the duplicate-detection gates
  (reference number and image hash, scoped per tenant) described in
  `.claude/rules/security.md` and `.claude/rules/payments.md` before the
  slip can move to `APPROVED`.

## 10. Protected-content access control

Uploaded/protected content — course video, payment slips, protected
documents/materials — must never be reachable via a direct, predictable
URL/ID alone. Every fetch of protected content must pass through a
server-side authorization check confirming, for that specific resource:

- The requester's **tenant** matches the resource's tenant.
- The requester's **enrollment/ownership** covers the specific
  resource (e.g. enrolled in the course the video belongs to; the payment
  slip belongs to the requesting student, or the requester is an
  authorized reviewer for that tenant).
- The requester's **role** permits the action (e.g. viewing vs. approving
  a payment slip).

Explicit negative tests are required (per `.claude/rules/security.md` and
`.claude/rules/tenancy.md`) for, at minimum:

- A student from tenant A requesting a video id belonging to tenant B —
  must be rejected (403/404), not silently return empty data or leak
  existence.
- A student requesting another student's payment slip or protected
  document (same tenant, different owner) — must be rejected.
- Sequential/incremented ID guessing across tenants and across roles for
  any protected-content endpoint.

An unguessable filename or UUID alone is never treated as sufficient
access control — the authorization check above must run regardless of how
unguessable the identifier is.

## 11. Required tests (summary)

Per `.claude/rules/testing.md`:

- Token-expiry test: an expired or already-consumed token must be
  rejected.
- Cross-tenant/cross-session negative test: a valid token issued for
  tenant A/session A must be rejected when replayed against tenant B or a
  different session.
- Concurrent-session test: a second concurrent playback attempt beyond
  the configured limit is blocked, using real Redis via Testcontainers.
- Upload validation tests: oversized file rejected, MIME-mismatched file
  rejected (e.g. renamed executable), unauthorized uploader rejected, no
  partial write on rejection.
- Protected-content access tests: cross-tenant ID-guessing rejected,
  cross-student (same-tenant) ID-guessing rejected for payment slips and
  documents.

## 12. Open questions (require explicit technology decisions before implementation)

The following are intentionally **not** decided by this document:

- Concrete signed-URL/token format and signing mechanism (specific
  standard, library, or provider-native signing feature).
- Specific external video/object storage provider and its native
  signed-URL/DRM capabilities (if any) — this document assumes an external
  provider integration per `.claude/rules/architecture.md` but does not
  select one.
- Whether a formal DRM solution is required in addition to
  watermark/session-based deterrence, or whether watermark + session
  controls described in Section 8 are considered sufficient for MVP.
- Specific watermarking implementation (client-side overlay vs.
  provider-side burned-in watermark) and its performance/UX trade-offs.
- Concrete mechanism for "single-use" token enforcement (e.g. what backing
  store records consumed tokens, and its consistency guarantees under
  concurrent requests).
- Exact anomaly-detection thresholds/rules for IP/device signals during
  playback (left as a policy/config decision, not an architectural one).

## Related

- `docs/architecture/authentication-authorization.md`
- `docs/architecture/enrollment-access.md`
- `docs/architecture/integration-architecture.md`
