# Authentication & Authorization Architecture

> **Change control notice**: Authentication architecture is a change-controlled area
> per root `CLAUDE.md`. Any deviation from what is described in this document —
> including how identity is resolved, how tokens/sessions are issued, how device
> policy precedence works, or how roles/permissions are modeled — requires an
> approved ADR under `docs/adr/` before implementation. This document is the
> approved baseline; a pull request that changes this behavior without a linked
> ADR should not be merged.

## 1. Purpose and scope

This document describes the confirmed authentication and authorization
architecture for the LMS platform: how a user (Student, Teacher, Tenant Admin,
Platform Admin, or a staff sub-role) is identified, how a session/device is
established at login, how device-sharing prevention is enforced, and how
role-based authorization is modeled across the platform. It is the reference
a fresh session should trust for "how auth works here" over any individual
PR diff.

It does not cover video/session playback token issuance — see
`docs/architecture/video-content-security.md` for that.

## 2. Ownership boundary

- `identity-access-service` is the sole owner of authentication: credential
  verification, session/token issuance, device registration, device policy
  enforcement, and suspicious-login detection.
- `tenant-management` and `identity-access-service` are the only two domains
  permitted to resolve tenant identity (per `.claude/rules/tenancy.md`). Every
  other domain is a *consumer* of the tenant context already resolved at the
  authentication layer — no business domain (course, payment, exam, etc.)
  re-derives tenant identity from a token, header, or request parameter.
- Per `.claude/rules/architecture.md`, `identity-access-service` is a
  foundational module: it may be depended on by any business domain, but it
  must never depend back on a business domain. Login/session/device logic
  must not import course, payment, or enrollment concerns.
- Other domains interact with `identity-access-service` only through its
  public `api` package (e.g. "current authenticated principal", "resolved
  tenant context", "does this actor have permission X") — never by reaching
  into its repositories or entities.
- **Implementation note (MVP-002)**: the deny-all-except-health/docs Spring
  Security placeholder shipped by Application Foundation
  (`com.lms.common.config.SecurityConfig`) is retired — it was deleted, not
  merely superseded, in the same commit that introduced the real filter
  chain. Real security configuration (`SecurityFilterChainConfig`,
  `TenantResolutionFilter`, `JwtAuthenticationFilter`) is defined in
  `identity-access-service`, not `com.lms.common`, because the shared kernel
  must never depend back on a business/domain module (this section's own
  rule, one paragraph above). A future contributor should not "helpfully"
  reintroduce a `SecurityConfig` in `com.lms.common` — this is a deliberate
  placement, not an oversight.

## 3. Roles and portals covered

Authentication and authorization must support the following roles/portals,
per `docs/requirements/source-requirements.md` (Student Management, Teacher
Management, Staff Management, Tenant & Institute Management):

- **Platform Admin** — cross-tenant, platform-level operations only (tenant
  approval, platform-wide reporting/support). Never a shortcut for
  tenant-scoped access.
- **Tenant Admin / Institute Owner** — full administrative access within
  exactly one tenant.
- **Teacher** — access scoped to assigned courses within one tenant.
- **Teacher Assistant** — subset of Teacher permissions, scope TBD per
  tenant configuration (see Open Questions).
- **Staff sub-roles** (all tenant-scoped, permission sets distinct per role):
  - Finance Staff
  - Course Coordinator
  - Student Support
  - Content Manager
  - Exam Manager
  - Attendance Operator
  - Read-only Auditor
- **Student** — access scoped to the student's own enrollments/records within
  one tenant.

Every role above authenticates through the same `identity-access-service`
login path; there is no separate/parallel auth stack per portal. Portal
separation (student vs teacher vs admin UI) is a frontend routing concern
(see `.claude/rules/frontend.md`), not a separate backend authentication
mechanism.

## 4. Login and session/tenant resolution

- Tenant identity is resolved **exactly once per request**, at the
  authentication/edge layer inside `identity-access-service`'s auth
  filter/interceptor, from the validated credential/session — never from a
  client-supplied `tenant_id`, subdomain claim taken at face value without
  verification, or request body/query parameter.
- Once resolved, tenant identity and the authenticated principal (user id,
  role, permission set) are propagated through the request lifecycle via a
  request-scoped context. Downstream modules read this context; they do not
  re-parse the credential/token themselves.
- Background/async work (queued notifications, scheduled jobs, event
  listeners) does **not** inherit the request-scoped context automatically.
  Any job/event that needs tenant or actor identity must carry it explicitly
  in its payload — this must be verified for every new background job that
  touches tenant-owned data.
- Successful login is the single point at which:
  1. Credentials are verified.
  2. A session/token is issued to the caller.
  3. Device registration is performed (see Section 5).
  4. Suspicious-login checks run (see Section 8).

## 5. Device registration at login

- Device registration happens **server-side**, at the moment of successful
  login, keyed to the authenticated user id.
- The server generates/verifies the device fingerprint/token. A client-
  supplied device id **alone** must never be trusted as sufficient proof of
  device identity — the server is the source of truth for "is this the same
  device that registered previously."
- Device registration and the login-activity/audit record that logs it must
  be written together as one atomic unit (per `.claude/rules/backend.md`,
  "Transaction boundaries" — device registration + audit record is one of
  the explicitly listed same-transaction pairs).
- Device history (per student) must be retrievable for admin/support review
  and must itself be tenant-filtered like any other tenant-owned data.

## 6. Device limit enforcement and override precedence

Device limit enforcement is a **backend authorization check** — a login that
exceeds the resolved limit must be rejected server-side (401/403), never
just flagged or disabled in the UI.

The limit is resolved using this override precedence, most specific wins:

1. **Student-level exception** — an explicit override for one student
   (e.g. a support-approved exception).
2. **Course-level override** — e.g. a premium course configured for a
   stricter limit (source requirement example: "Premium course: 1 device
   only").
3. **Tenant-level override** — a tenant-wide limit different from the
   plan default.
4. **Plan-level default** — the SaaS plan's baseline device limit (source
   requirement example: "Default: 2 devices per student").

Rules:

- Only one level applies per login attempt — the most specific configured
  override wins; there is no "sum" or "average" of levels.
- The resolution must happen against data read from trusted, tenant-scoped
  configuration — never from a value the login request itself supplies.
- Every device-limit code path requires a test proving: registering devices
  up to the configured limit succeeds, the next login is blocked, and the
  correct override wins when multiple levels are configured for the same
  student (per `.claude/rules/testing.md`'s required-test matrix).

## 7. Device reset flow

Device reset (performed by an authorized admin/support actor) must:

- Require an authorization check that the acting user has device-reset
  permission **for that specific tenant/student** — a Tenant Admin of
  tenant A must never be able to reset a device belonging to tenant B's
  student, and a staff sub-role without device-reset permission (e.g.
  Read-only Auditor) must be rejected server-side even if a UI control is
  somehow reachable.
- Enforce a **backend-persisted cooldown** before the freed device slot can
  be reused for a new device registration (e.g. a persisted `reset_at`
  timestamp checked on the next login attempt). The cooldown must not be
  simulated or enforced only in the frontend.
- Write a mandatory audit log entry (actor id, tenant id, target student id,
  action = device reset, timestamp) in the same transaction/service
  boundary as the reset itself — never as a separate, skippable call (see
  `.claude/rules/security.md`, Audit Logging).
- Be covered by a test proving: reset requires the correct permission,
  the cooldown blocks immediate reuse, and exactly one audit log row is
  written per reset.

## 8. Suspicious-login detection

Suspicious-login detection must run **server-side**, on the login/session
path, for signals including at minimum:

- Impossible travel (e.g. logins from geographically inconsistent locations
  within a short window).
- Rapid device churn (many distinct devices registering/deregistering for
  one account in a short window).
- Many distinct IPs for one account in a short window.

Requirements:

- Detection logic must not rely on frontend heuristics or client-reported
  signals as its sole input.
- At minimum, a detected suspicious-login event must produce an audit/
  security log entry (actor/account id, tenant id, signal type, timestamp,
  relevant context such as IP/device). Whether it also blocks the login
  outright (vs. flag + notify) is a policy decision — see Open Questions.
- This is distinct from, and in addition to, the device-limit check in
  Section 6; a login can pass the device-limit check and still trigger a
  suspicious-login flag (e.g. first-time device from an anomalous location
  within limit).

## 9. Role-based authorization model

- Authorization is enforced **server-side, on every protected endpoint**,
  independent of any client-side role/permission display. A hidden button
  or disabled UI control is never a substitute for a backend check (per
  `.claude/rules/frontend.md` and `.claude/rules/ui-ux.md`).
- The permission-denied state shown in the UI must be driven only by a
  server-verified signal (401/403 response or a role/permission value
  returned in the authenticated session/profile payload) — never computed
  purely from a client-stored role string.
- Staff sub-roles (Finance Staff, Course Coordinator, Student Support,
  Content Manager, Exam Manager, Attendance Operator, Read-only Auditor)
  each have a distinct permission set scoped to their operational area
  within one tenant. A permission matrix (which sub-role may perform which
  action on which domain) is required implementation detail, but the
  concrete matrix is an implementation/configuration artifact tracked in
  `docs/api` alongside the endpoints it governs, not duplicated here.
- Read-only Auditor must never have a server-side path that allows any
  mutating action, regardless of what the UI exposes.
- Platform Admin permissions are platform-scoped by default (tenant
  approval, cross-tenant reporting/support) and must not implicitly grant
  tenant-admin-equivalent access to a specific tenant's operational data
  without an explicit, audited impersonation/support-access flow (see
  Section 10 and `.claude/rules/ui-ux.md` on impersonation UX requirements).

## 10. Interaction with tenant context and impersonation

- All authorization checks are evaluated against the resolved tenant
  context from Section 4 — a permission check that does not also confirm
  "for this tenant" is incomplete for any tenant-owned resource.
- If/when a Platform Admin "view as tenant" or support-impersonation
  capability is implemented, it must be backed by a distinct, backend-issued
  impersonation session (not a locally toggled UI state), and every action
  taken during that session must record both the impersonating admin's
  identity and the impersonated user's identity distinctly — never
  collapsed into a single actor id (per `.claude/rules/security.md`, Audit
  Logging). Impersonation start and end must each produce an audit log
  entry.

## 11. Required tests (summary)

Per `.claude/rules/testing.md`, changes to this area require, at minimum:

- Device-limit-exceeded test (registration up to limit, next login
  blocked, override precedence honored).
- Device reset test (permission required, cooldown enforced, audit row
  written).
- Cross-tenant negative test for any endpoint touching device/session data
  (tenant A actor must not reach tenant B's device/session records).
- Negative-path authorization tests per role/staff sub-role for any new
  protected endpoint, not just the happy path.

## 12. Open questions — resolved by ADR-007

The items below were intentionally not decided by this document. They are now
resolved by `docs/adr/ADR-007-authentication-token-and-device-mechanism.md`
(Accepted 2026-08-02) — implementation should follow that ADR's decisions, not
invent its own answer to any item below:

- Concrete session/token format and signing mechanism (e.g. which token
  standard, which signing/verification approach, token storage location).
- Concrete device fingerprinting technique/library.
- Password hashing algorithm/parameters.
- Whether multi-factor authentication is in scope for any role, and if so
  which roles/flows require it.
- Whether suspicious-login detection blocks the login outright or only
  flags it for review, and whether this differs by role (e.g. stricter for
  Tenant Admin/Platform Admin than Student).
- Concrete permission matrix per staff sub-role (to be captured in
  `docs/api` once each domain's endpoints are contract-reviewed).
- Session/store technology used to track active sessions/devices (subject
  to the constraint in `.claude/rules/architecture.md` that Redis is
  cache/ephemeral-state only, not a source of truth for authoritative data).

## Related

- `docs/architecture/multi-tenancy.md`
- `docs/architecture/video-content-security.md`
- `docs/requirements/user-roles-and-permissions.md`
