# ADR-007: Authentication Token Format, Device Fingerprinting, and Related Open Decisions

## Status

Accepted (2026-08-02). Drafted during a pre-implementation readiness review to resolve
the open items listed in `docs/architecture/authentication-authorization.md` §12. Not
yet implemented — `backend/pom.xml` still has no JWT/OAuth2 library pinned — but
`identity-access-service` login/session/device code may now be written against the
decisions below.

## Context

`docs/architecture/authentication-authorization.md` fully specifies the *behavior*
required of authentication (device registration, device-limit precedence, suspicious-
login detection, role-based authorization, impersonation) but explicitly defers seven
concrete technology/policy decisions to a future ADR (§12). None of that behavioral
design is revisited here — this ADR only resolves the listed open items, all of which
are prerequisites for writing the first line of `identity-access-service` auth code.

Two platform-wide constraints shape every decision below:

- **All application instances must be stateless** (`.claude/rules/architecture.md`) — no
  in-JVM session state that must be authoritative. This favors self-contained,
  verifiable tokens over server-affinity session objects.
- **Redis is a cache/ephemeral-state layer, not a source of truth** (same rule) — any
  data that must survive a Redis flush or be authoritative for revocation must live in
  PostgreSQL, with Redis only optionally caching fast-path lookups.

## Decisions

### 1. Session/token format and signing

**Decision:** Short-lived signed **JWT access tokens** (HS256, symmetric secret managed
via environment/secrets configuration — not committed, per root `CLAUDE.md` Safety
rules) + a long-lived **opaque refresh token**, persisted server-side.

- Access token: 15-minute expiry. Payload carries `sub` (user id), `tenant_id`,
  `role`, and a `session_id` (foreign key to the persisted device/session record below)
  — it does **not** embed a permission list, so a mid-session role/permission change
  takes effect on the next request rather than requiring token invalidation.
  Authorization checks still re-verify the actor's current role/permissions
  server-side per request (per `.claude/rules/architecture.md`'s authorization model),
  never trusting the token payload as the final word.
- Refresh token: cryptographically random opaque value, stored **hashed** in a
  `device_session` table (PostgreSQL — authoritative, survives a Redis flush), rotated
  on every use (old refresh token invalidated the moment a new one is issued), with an
  absolute lifetime cap (recommend 30 days) independent of rotation.
- `device_session` (PostgreSQL, tenant-owned, `tenant_id`-scoped per
  `.claude/rules/tenancy.md`) is the single source of truth for "is this session still
  valid" — revocation (device reset, logout, suspicious-login block) is a status
  change on this row, checked on every refresh and optionally cached in Redis for
  fast-path access-token-adjacent checks, never authoritative there.
- HS256 (not RS256) is sufficient today because only this one Spring Boot monolith
  issues and verifies tokens (ADR-001). If a separate service ever needs to verify
  tokens independently without sharing the symmetric secret, that is grounds for a new
  ADR revisiting RS256/asymmetric signing — not a default to build in now.

### 2. Device fingerprinting technique

**Decision:** Server-issued opaque device identifier, not passive browser/client
fingerprinting.

- On first successful login from a given client, the server generates a
  cryptographically random device identifier, persists it (hashed) as part of the
  `device_session` row, and returns it to the client to store (secure `HttpOnly` cookie
  for web; secure platform storage — e.g. Keychain/Keystore — for a native app if one
  is ever built).
- The client presents this identifier on subsequent requests; the server verifies it
  against the persisted, hashed value. A client-supplied identifier alone is never
  sufficient proof — per `docs/architecture/authentication-authorization.md` §5, it is
  only trusted because the server generated and persisted it in the first place.
- Passive fingerprinting (canvas/font/screen-based client fingerprinting libraries) is
  explicitly rejected: it is unreliable (collisions, easily spoofed or blocked by
  privacy tooling) and unnecessary once the server owns identifier issuance.

### 3. Password hashing algorithm

**Decision:** **Argon2id**, via Spring Security's `Argon2PasswordEncoder`, with
parameters tuned to OWASP's current recommended baseline for Argon2id at deploy time
(iteration count, memory cost, parallelism — set in `identity-access-service` config,
not hardcoded as magic numbers, so they can be raised as hardware improves without a
data migration). Existing OWASP Password Storage Cheat Sheet guidance ranks Argon2id as
the first-choice modern default over bcrypt/PBKDF2; there is no legacy-compatibility
reason to prefer bcrypt here since this is a greenfield credential store.

### 4. Multi-factor authentication scope

**Decision:** **TOTP-based MFA (RFC 6238), phased by role, not built at MVP launch but
decided now so the mechanism isn't re-litigated per role later:**

- **Mandatory-available, tenant-admin-enforceable** for Tenant Admin/Institute Owner
  and Platform Admin at general availability (these roles can approve payments, reset
  devices, and change prices — highest blast radius per `.claude/rules/security.md`'s
  audit-logged action list).
- **Optional, user-enabled** for Teacher, Teacher Assistant, and staff sub-roles at
  general availability; Finance Staff specifically should be able to be required by a
  Tenant Admin given payment-approval exposure.
- **Not required** for Student at MVP; revisit if abuse patterns emerge.
- MFA enrollment/verification is out of scope for the first `identity-access-service`
  sprint (login without MFA ships first) but the `device_session`/user-credential
  schema should reserve a nullable TOTP-secret column now so it isn't a later migration
  surprise, per the append-only/schema-stability spirit of `.claude/rules/backend.md`.

### 5. Suspicious-login detection: block vs. flag

**Decision:** **Flag + notify by default for every role at launch; do not hard-block
on suspicious-login signals until false-positive rates are measured.**

- A detected signal (impossible travel, rapid device churn, many distinct IPs) always
  produces the mandatory audit/security log entry (`docs/architecture/authentication-
  authorization.md` §8) and triggers a notification to the account (and, for Tenant
  Admin/Platform Admin, an additional alert channel per tenant configuration).
- Hard-blocking a login outright is reserved as a **Phase 2 refinement**, and even then
  should default to the highest-blast-radius roles (Platform Admin, Tenant Admin)
  first — incorrectly locking out a paying tenant's admin over a false positive is a
  worse outcome at this stage than a delayed block.
- This is independent of, and does not relax, the device-limit check (§6 of the
  authentication doc), which does hard-block server-side today regardless of this
  decision.

### 6. Staff sub-role permission matrix

**Decision:** Deliberately **not resolved by this ADR** — `docs/architecture/
authentication-authorization.md` §12 already correctly defers this to `docs/api`,
populated per-endpoint by the `review-api-contract` skill as each domain's endpoints
are contract-reviewed. No architecture-level blocker here; this is ongoing
implementation work, not a pending decision.

### 7. Session/device-tracking store technology

**Decision:** **PostgreSQL is authoritative** (`device_session` table, tenant-owned,
`tenant_id`-leading index per `.claude/rules/backend.md`); **Redis is an optional
read-through cache** for the "is this session/token currently valid" check on the hot
path, populated from and invalidated in lockstep with PostgreSQL — never written to
independently. This directly follows the existing "Redis is cache/ephemeral-state only"
platform rule; it is not a new decision so much as that rule's straightforward
application to this specific table.

## Consequences

**Positive**

- All decisions are consistent with the platform's existing stateless-instance and
  Redis-as-cache constraints — no new infrastructure dependency introduced.
- Access-token payload deliberately excludes permissions, avoiding a whole class of
  "stale permissions in a still-valid token" bugs after a role change or device reset.
- Phased MFA and flag-before-block suspicious-login policy avoid over-building auth UX
  friction before real usage data exists, while still deciding the *mechanism* now so
  schema/config isn't redesigned later.

**Negative / trade-offs accepted**

- HS256 with a shared symmetric secret means the secret must be tightly held within
  the monolith; if a future ADR splits out a separately deployable service that needs
  to verify tokens independently, migrating to RS256 is additional work deferred to
  that point rather than built in speculatively now.
- Flag-not-block suspicious-login policy means a genuinely compromised account is not
  automatically locked out at MVP — mitigated by the mandatory audit/notification
  trail, but this is a real, accepted risk trade-off pending Phase 2 hardening.

## Alternatives considered

- **Server-side session objects (no JWT)** — rejected: conflicts with the stateless
  app-instance requirement without adding a sticky-session layer Nginx isn't configured
  for (`.claude/rules/architecture.md`).
- **bcrypt for password hashing** — rejected in favor of Argon2id: no legacy data to
  preserve compatibility with, and Argon2id is the current recommended default for new
  credential stores.
- **Passive browser/client fingerprinting for device identity** — rejected: unreliable
  and unnecessary once the server owns identifier issuance and persistence.
- **Hard-block on first suspicious-login signal** — rejected at launch: risk of
  locking out legitimate paying-tenant admins before false-positive rates are known.

## Related

- `docs/architecture/authentication-authorization.md` (behavioral baseline this ADR
  supplies the missing technology decisions for)
- `docs/architecture/multi-tenancy.md`
- `.claude/rules/security.md`, `.claude/rules/architecture.md`
- ADR-002-shared-database-tenancy.md, ADR-006-tenant-isolation-repository-mechanism.md
