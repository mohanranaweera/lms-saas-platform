# Authentication Foundation Module — Plan

**Issue:** [#2 — \[MVP\] Module 2: Authentication foundation](https://github.com/mohanranaweera/lms-saas-platform/issues/2)
**Backlog stories:** `AUTH-1`, `AUTH-2`, `AUTH-3` (`docs/planning/product-backlog.md` MODULE 2), plus the minimal slice of `TEN-1`/`TEN-3` (Module 4) pulled forward per `docs/planning/mvp-release-plan.md` Wave 0.
**Status:** Plan only — no application code, migration, or config file has been created or edited. Produced by delegating to `product-requirements-analyst`, `solution-architect`, `database-architect`, `security-reviewer`, `qa-test-engineer`, and `ui-ux-reviewer` in parallel, each grounded in the existing ADRs/architecture docs/backlog/current code, then reconciled below. `payment-ledger-specialist` was not invoked — this module has no payment/ledger surface (confirmed in the backlog for all three stories).

---

## Context

Application Foundation (Module 1, issue #1) is merged to `main` (`24889fa`, `287fcd1`, `a02bacf`) and already ships the shared-kernel mechanism this module depends on: `com.lms.common.tenant.TenantContext`/`TenantContextHolder` (fail-loud, `ThreadLocal`, **no resolver bound in `src/main` by design** — that resolver is this module's job), `com.lms.common.persistence.TenantAwareRepository` (ADR-006 mechanism, ready for its first real tenant-owned entity), `BaseEntity`/`Auditable`/`UuidV7Generator`, the `ApiResponse`/`ApiError`/`GlobalExceptionHandler` envelope, and a baseline `SecurityConfig` (deny-all except `health`/`docs`). `backend/pom.xml` has Spring Security/JPA/Redis/Flyway but **no JWT library pinned yet**. The only migration so far is `V1__baseline_conventions.sql` (documentation-only marker).

This module is the first to populate `TenantContext` for real, the first to add domain tables, and the first real consumer of `TenantAwareRepository`.

---

## 1. Business goal

Every role in the platform — Platform Admin, Tenant Admin, staff sub-roles, Teacher, Student — needs one shared, secure way to prove identity and stay signed in, scoped to the correct tenant, so every later module can trust "who is this, and which tenant" without re-implementing authentication. This module delivers exactly that: Argon2id credential verification, short-lived JWT access tokens plus rotated, server-persisted opaque refresh tokens, and clean server-side session termination on logout. It does **not** cover device limits, MFA, suspicious-login detection, or password recovery — those are later modules (`docs/architecture/authentication-authorization.md` §1–§4; `docs/planning/product-backlog.md` AUTH-1/2/3).

## 2. Roles and permissions

All roles authenticate through one shared login mechanism — role is a claim on the issued JWT, never a parallel auth stack per portal (`docs/architecture/authentication-authorization.md` §3). This module only needs role to exist as a coarse enum value populated into the JWT `role` claim; it does **not** implement endpoint-level permission enforcement — that is `RBAC-2` (Module 3), which has only a *soft* dependency back onto this module for the enum value (`docs/planning/product-backlog.md` RBAC-1 dependency note).

- **Platform Admin** — authenticates against a separate `platform_admin_user` table with **no** `tenant_id` (never tenant-scoped). Reaches the platform admin login path, not the tenant-branded one.
- **Tenant Admin, staff sub-roles, Teacher, Student** — authenticate against `tenant_user`, scoped by the resolved tenant. This module stores a coarse `role` value only (`TENANT_ADMIN`, `STAFF`, `TEACHER`, `STUDENT`); the staff sub-role breakdown (Finance Staff, Course Coordinator, etc.) is Module 3's data model, not this one's.
- No staff-sub-role-specific login behavior exists at this layer.

## 3. Preconditions

- `APP-1`/`APP-4` (Application Foundation: `TenantContext`, `TenantAwareRepository`, response envelope, base entity) — already merged to `main`.
- A minimal `tenant` table and subdomain-based tenant resolution (`TEN-1`/`TEN-3`, Module 4) — pulled forward into this same delivery wave because login cannot resolve which tenant's user to authenticate against otherwise (`docs/planning/mvp-release-plan.md` Wave 0). Only the table shape and the resolution filter are a precondition here — **not** Module 4's registration form or Platform Admin approval workflow.
- Argon2id password hashing (`AUTH-3`) must land before/alongside login (`AUTH-1`), since credential verification depends on it.
- No JWT library is pinned in `backend/pom.xml` yet — adding one is part of this module's own implementation, not a precondition met elsewhere.

## 4. User flows

**In scope:**

1. **Normal login** (any role, valid credentials, tenant resolved and active): tenant resolved once at the edge from subdomain → Argon2id verify → on success, in one transaction: insert `device_session` row (also serves as the login-activity record, see §16) + issue 15-minute JWT (`sub`, `tenant_id`, `role`, `session_id`) + rotated, hashed, server-persisted opaque refresh token.
2. **Token refresh**: expired access token + valid, non-revoked refresh token → new access token issued, old refresh token invalidated the instant the new one is issued (same DB row updated, not appended).
3. **Logout**: `device_session` row revoked in Postgres (`status='revoked'`, `revoked_at` set) with the Redis fast-path cache entry invalidated in the same service call.
4. **Suspended-tenant login rejection**: tenant status checked as part of tenant resolution, **before** credentials are evaluated — no login form is even meaningfully attemptable.
5. **Suspended-user login rejection**: tenant resolves fine, but the specific `tenant_user`/`platform_admin_user` row's status is suspended — see §21 for password-check-ordering resolution to avoid an enumeration side channel.
6. **Invalid-credential rejection**: generic, anti-enumeration response regardless of whether the email doesn't exist or the password is wrong.
7. **Platform Admin login**: a structurally separate path (see §9) that never runs tenant resolution.

**Explicitly out of scope** (do not silently pull forward):
- Device-slot/limit logic, device reset, suspicious-login detection/blocking — Phase 2 (`docs/requirements/specifications/16-device-authentication.md`; risk R13). `device_session` gets a `device_identifier_hash` column populated at login, but nothing enforces a limit on it yet.
- MFA enrollment/verification — mechanism decided in ADR-007 §4, not built. Schema reserves a nullable TOTP-secret column only.
- Forgot/reset-password flow — **no AUTH-1/2/3 acceptance criterion or FR-IAS item covers this**, despite `docs/ui-ux/authentication-design-spec.md` §3.3–§3.6 fully specifying screens for it. This is a genuine gap between the UI spec and the backlog — treated here as excluded, not silently bundled in. Flag for product decision on which module owns it.
- Full RBAC data model/permission-matrix enforcement (Module 3).
- Full tenant registration/approval workflow and Platform Admin tenant-list UI (Module 4, beyond the minimal table).
- First-Login forced password-change UX — the `must_change_password` column ships with this module's schema (AUTH-3), but AUTH-3 itself states "Frontend impact: None — backend-only," and no acceptance criterion defines a login-response contract or forced-redirect behavior for it. **Recommendation: ship the column and have it present in the login response payload, but defer building the forced-change screen/redirect logic to a follow-up story** — see §21.

## 5. Acceptance criteria

- Given valid credentials for a tenant-resolved user, when login succeeds, then a 15-minute JWT (`sub`, `tenant_id`, `role`, `session_id`, no permission list) and a rotated, hashed, server-persisted opaque refresh token are issued.
- Given an unapproved/suspended tenant, when any user attempts login against that tenant's subdomain, then login is rejected server-side before credentials are evaluated.
- Given tenant identity resolution, then it happens exactly once, at the auth filter, from the validated subdomain — never from a client-supplied `tenant_id`.
- Given a protected endpoint and a valid, non-expired access token, then the request succeeds and the actor's current role/status is re-verified server-side against `tenant_user`/`platform_admin_user` — the JWT `role` claim is never trusted as final.
- Given an expired access token and a valid, non-revoked refresh token, then a new access token is issued and the old refresh token is invalidated the instant the new one is issued.
- Given logout, then the `device_session` row is revoked in Postgres with the Redis cache entry invalidated in the same operation.
- Given a revoked or expired session, when the same access token is replayed, then the request is rejected even though the JWT signature/expiry alone would still pass.
- Given a new credential is stored, then it is hashed with Argon2id via `Argon2PasswordEncoder`, with iteration/memory/parallelism read from configuration, not hardcoded.
- Given a login attempt (successful or not), then the plaintext password never appears in logs, error bodies, or any API response.
- **Cross-tenant (mandatory):** the issued JWT's `tenant_id` claim always matches the resolving tenant; an access token minted for tenant A/session A is rejected when replayed against a request resolving tenant B; a request against tenant A's subdomain carrying a manipulated header/param claiming tenant B's id still resolves to tenant A.
- **Ambiguous, flagged rather than assumed** — see §21: exact password-check-vs-suspension-check ordering; whether `must_change_password` applies to staff accounts by analogy to students; exact refresh-token absolute lifetime value (ADR-007 "recommends" 30 days, not ratified).

## 6. Out-of-scope items

- Device-slot/limit enforcement, device reset/cooldown, suspicious-login detection — Phase 2, Module 17.
- MFA enrollment/verification — Phase 2/later per ADR-007 §4; schema column reserved only.
- Forgot/reset-password flow — no backend story covers it; UI spec screens for it stay in their current disabled placeholder state.
- Full RBAC permission-matrix / staff sub-role data model — Module 3 (`RBAC-1`/`RBAC-2`/`RBAC-3`). Only a coarse `role` enum ships here.
- Full tenant registration form and Platform Admin tenant-approval workflow — Module 4, beyond the minimal `tenant` table + subdomain resolution.
- Account Locked mechanism (`docs/ui-ux/authentication-design-spec.md` §3.8) — no server-side lockout mechanism (failed-attempt threshold, etc.) exists in this module's scope or in any approved architecture doc. The UI screen is not wired to real logic.
- First-Login forced password-change screen/redirect (see §4).

## 7. Domain model

- **`Tenant`** (`com.lms.tenantmanagement.domain`) — platform-level: `id`, `name`, `subdomain` (globally unique), `status`, `plan_id`, timestamps. No `tenant_id` on itself.
- **`TenantUser`** (`com.lms.identityaccessservice.domain`) — implements `TenantOwned`: `tenant_id`, `email`, `password_hash`, `role`, `status`, `must_change_password`, `totp_secret` (nullable, reserved).
- **`PlatformAdminUser`** (`com.lms.identityaccessservice.domain`) — platform-level: `email` (globally unique), `password_hash`, `status`, `must_change_password`, `totp_secret` (nullable, reserved). No `role` column — role is implicit (`PLATFORM_ADMIN`).
- **`DeviceSession`** (`com.lms.identityaccessservice.domain`) — implements `TenantOwned`: session/refresh-token record for `tenant_user` logins.
- **`PlatformAdminSession`** (`com.lms.identityaccessservice.domain`) — same shape as `DeviceSession` but platform-level, no `tenant_id`. **Gap closed during planning**: the backlog's `device_session` table is `tenant_id NOT NULL`, but `platform_admin_user` logins need session/refresh-token persistence too and must never carry a `tenant_id`. This table is added to close that gap — see §21 for confirmation flag.

**Package ownership**: `tenant` is already assigned to `tenant-management` by the confirmed domain list (`.claude/rules/architecture.md`) and by the backlog itself (`TEN-1`/`TEN-3` both name `tenant-management` as owner). Only the *timing* is pulled forward into this wave, not the *ownership* — this is an application of an already-decided rule, not a new package-boundary judgment call (contrast with Application Foundation's genuinely novel `com.lms.common` precedent). `identity-access-service` consumes `Tenant` only through `tenant-management`'s `api` interface (`TenantLookupService.findActiveTenantBySubdomain`), never its repository/entity directly.

## 8. Database design

Four new tenant/platform tables (plus the session-gap fix), each in its own Flyway migration starting at `V2` (never touching the existing `V1__baseline_conventions.sql`):

**`V2__create_tenant.sql`** — `tenant` (platform-level)
```
id            UUID PRIMARY KEY
name          VARCHAR NOT NULL
subdomain     VARCHAR NOT NULL UNIQUE        -- global unique: the one legitimate case, tenant IS the identity table
status        VARCHAR NOT NULL DEFAULT 'trial'
              CHECK (status IN ('trial','active','suspended','cancelled'))
plan_id       UUID NULL                       -- no plan catalog table exists yet; left FK-less, do not fabricate one
created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
```

**`V3__create_tenant_user.sql`** — `tenant_user` (tenant-owned)
```
id                    UUID PRIMARY KEY
tenant_id             UUID NOT NULL REFERENCES tenant(id)
email                 VARCHAR NOT NULL
password_hash         VARCHAR NOT NULL
role                  VARCHAR NOT NULL
                      CHECK (role IN ('TENANT_ADMIN','STAFF','TEACHER','STUDENT'))  -- coarse; RBAC-1 extends/supersedes
status                VARCHAR NOT NULL DEFAULT 'active' CHECK (status IN ('active','suspended'))
must_change_password  BOOLEAN NOT NULL DEFAULT false
totp_secret           VARCHAR NULL             -- reserved, unused (ADR-007 §4)
created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()

UNIQUE (tenant_id, email)     -- never global
UNIQUE (tenant_id, id)        -- lets device_session composite-FK against the parent per database-architecture.md §1
```

**`V4__create_platform_admin_user.sql`** — `platform_admin_user` (platform-level; kept as its own migration since it is structurally distinct and must never share a table with tenant-owned rows)
```
id                    UUID PRIMARY KEY
email                 VARCHAR NOT NULL UNIQUE   -- global: intentional, platform-level entity
password_hash         VARCHAR NOT NULL
status                VARCHAR NOT NULL DEFAULT 'active' CHECK (status IN ('active','suspended'))
must_change_password  BOOLEAN NOT NULL DEFAULT false
totp_secret           VARCHAR NULL              -- reserved
created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
```

**`V5__create_device_session.sql`** — `device_session` (tenant-owned)
```
id                      UUID PRIMARY KEY         -- this is the JWT session_id
tenant_id               UUID NOT NULL REFERENCES tenant(id)
user_id                 UUID NOT NULL
                        FOREIGN KEY (tenant_id, user_id) REFERENCES tenant_user(tenant_id, id)  -- same-tenant only
refresh_token_hash      VARCHAR NOT NULL UNIQUE
device_identifier_hash  VARCHAR NOT NULL
issued_at               TIMESTAMPTZ NOT NULL      -- immutable; anchors the absolute lifetime cap
expires_at              TIMESTAMPTZ NOT NULL      -- issued_at + absolute cap, fixed across rotations
last_rotated_at         TIMESTAMPTZ NULL
revoked_at              TIMESTAMPTZ NULL
reset_at                TIMESTAMPTZ NULL          -- reserved, unused (Phase 2 device-reset cooldown)
status                  VARCHAR NOT NULL DEFAULT 'active'
                        CHECK (status IN ('active','revoked','expired'))
                        CHECK (status <> 'revoked' OR revoked_at IS NOT NULL)

INDEX (tenant_id, user_id)
```

**`V6__create_platform_admin_session.sql`** — `platform_admin_session` (platform-level; closes the gap noted in §7)
```
id                      UUID PRIMARY KEY
admin_id                UUID NOT NULL REFERENCES platform_admin_user(id)
refresh_token_hash      VARCHAR NOT NULL UNIQUE
device_identifier_hash  VARCHAR NOT NULL
issued_at               TIMESTAMPTZ NOT NULL
expires_at              TIMESTAMPTZ NOT NULL
last_rotated_at         TIMESTAMPTZ NULL
revoked_at              TIMESTAMPTZ NULL
status                  VARCHAR NOT NULL DEFAULT 'active'
                        CHECK (status IN ('active','revoked','expired'))
```

**Refresh-rotation invariant**: `device_session`/`platform_admin_session` are **not** append-only domains (only ledger/audit/payment are, per `docs/architecture/database-architecture.md` §3) — rotation is an in-place `UPDATE` of `refresh_token_hash`/`last_rotated_at` on the existing row. Combined with `UNIQUE(refresh_token_hash)`, a replayed stale hash matches zero rows once replaced — giving "old token invalidated the instant the new one is issued" without needing a partial unique index over active rows. `expires_at` stays fixed at issuance so the absolute lifetime cap survives rotation (ADR-007 decision 1). Revocation is `status='revoked', revoked_at=now()` — soft, never a `DELETE`, consistent with the device-authentication soft-revoke convention in `database-architecture.md` §4.

## 9. Backend design

```
com.lms.tenantmanagement
├── domain       (Tenant)
├── repository   (TenantRepository : JpaRepository — platform-level, not TenantAwareRepository)
├── service      (TenantService)
└── api          (TenantLookupService — findActiveTenantBySubdomain)

com.lms.identityaccessservice
├── domain       (TenantUser, PlatformAdminUser, DeviceSession, PlatformAdminSession, Role enum)
├── repository   (TenantUserRepository, DeviceSessionRepository : TenantAwareRepository;
│                 PlatformAdminUserRepository, PlatformAdminSessionRepository : JpaRepository)
├── service      (AuthenticationService, PlatformAdminAuthenticationService, TokenService,
│                 RefreshTokenService, DeviceSessionCacheService)
├── web          (AuthController — tenant login/refresh/logout;
│                 PlatformAdminAuthController — separate controller/path, see below)
├── api          (AuthenticatedPrincipal / AuthenticatedPrincipalHolder — mirrors TenantContextHolder:
│                 ThreadLocal, explicit set/clear, fail-loud)
└── config       (TenantResolutionFilter, JwtAuthenticationFilter, SecurityFilterChainConfig,
                  Argon2 PasswordEncoderConfig, JwtProperties)
```

**Filter chain (in order):** `CorrelationIdFilter` → `TenantResolutionFilter` (resolves subdomain via `TenantLookupService`, `TenantContextHolder.set(...)`, wraps the downstream chain in try/finally with `clear()` — the *only* call site that sets tenant context from request input) → `JwtAuthenticationFilter` (validates JWT signature/expiry, cross-checks the token's `tenant_id` claim against the already-resolved `TenantContextHolder` value and rejects on mismatch, checks `device_session`/`platform_admin_session` validity via the Redis-cache-then-Postgres path, re-reads the current user row's live `role`/`status` — never trusts the JWT `role` claim as final — and populates `SecurityContextHolder` + `AuthenticatedPrincipalHolder`).

**Platform Admin path is structurally separate**, not a role branch inside the tenant path: `TenantResolutionFilter` fails safe with no fallback on an unresolved subdomain, and `platform_admin_user` never has a `tenant_id` to resolve against. Recommendation: `PlatformAdminAuthController` lives under a distinct path prefix (e.g. `/api/v1/platform-admin/auth/**`) that `TenantResolutionFilter` explicitly excludes from tenant resolution, matching the UI spec's own framing of Platform Admin Login as "a fixed, non-tenant-resolved route." This is a design recommendation, not something the backlog states explicitly — flagged in §21 for confirmation before implementation.

**`CorrelationIdFilter` ordering note**: it currently runs before any tenant resolution exists, so its tenant-in-MDC line is presently dead code. This module must reorder `TenantResolutionFilter` ahead of it (or explicitly accept the MDC gap for pre-auth requests) — flag for the implementing engineer.

**Argon2id `PasswordEncoder`** bean lives in `identity-access-service.config` (credential verification is this domain's exclusive concern), parameters via `@ConfigurationProperties`, never hardcoded.

**Redis** is a cache-only fast path for session validity (`DeviceSessionCacheService`), populated/invalidated in the same service method as the Postgres write — Postgres remains authoritative (ADR-007 §7). Every hot-path check falls back to Postgres on a cache miss; a Redis "still valid" hit is bounded by a short TTL as defense in depth against the logout invalidation race (see §21). Do **not** cache `role`/`status` in Redis in this module — no invalidation hook exists until RBAC lands; a per-request, tenant-indexed Postgres read is the safer default.

**MDC/structured-logging**: Authorization headers, JWT/refresh-token values, and full request bodies must never land in MDC or in `GlobalExceptionHandler`'s logged payload — an explicit carry-forward requirement from Application Foundation's plan, now made concrete by this module's actual token-bearing requests.

## 10. API contract

Draft only — to be formalized in `docs/api/identity-access-service.md` by the `review-api-contract` skill before frontend implementation starts, per this repo's existing `docs/api/README.md` convention.

| Method + path | Purpose | Success | Failure |
|---|---|---|---|
| `POST /api/v1/auth/login` | Tenant-scoped login. Body: `{email, password}` — **never** `tenant_id`. | `200 ApiResponse<{accessToken, expiresIn, sessionId}>`; refresh token delivered via `httpOnly` `Secure` cookie (recommended, see §21) | `400` validation; `401` invalid credentials (generic); `403` suspended tenant/user (generic, distinct reason codes but generic copy) |
| `POST /api/v1/auth/refresh` | Rotate access/refresh token pair. | `200` new access token + rotated refresh cookie | `401` invalid/expired/already-rotated/revoked refresh token |
| `POST /api/v1/auth/logout` | Revoke current session. | `200` empty success | `401` if no valid session |
| `POST /api/v1/platform-admin/auth/login` | Platform Admin login — structurally separate path, no tenant resolution. | Same shape as tenant login, no `tenant_id` claim | Same as above, no tenant-suspension case |
| `POST /api/v1/platform-admin/auth/refresh` / `.../logout` | Mirrors the tenant-scoped endpoints for `platform_admin_session`. | — | — |

`409` has no clear use case for these endpoints — not used. Every response uses the existing `ApiResponse<T>`/`ApiError` envelope from `com.lms.common.api`.

## 11. Frontend screens

Existing state (Application Foundation): `frontend/src/app/(auth)/login/page.tsx` and `forgot-password/page.tsx` are visibly disabled placeholder shells (`<fieldset disabled>`, "Not yet implemented" notice); `frontend/src/components/states/permission-denied-state.tsx` exists but takes a bare `string`, not a real error object; `frontend/src/components/ui/` has only `button`, `card`, `input`, `label`, `sheet`, `skeleton` — **no `Alert`, `Checkbox`, `Form Field Wrapper`, or `Validation Message` component exists yet**, though nearly every screen below needs them.

**In scope — wire for real:**
- **Tenant-Branded Login** (`app/(auth)/login/page.tsx`) — remove the disabled fieldset/notice, wire submit to `POST /auth/login` via a `useMutation` through `lib/api/client.ts`, loading state (`aria-busy`, "Signing in…"), invalid-credentials `Alert` (`role="alert"`, generic copy only), password show/hide toggle with a stateful `aria-label`, registration link hidden for Teacher/Tenant Admin instances (needs a portal prop, not shown unconditionally as today).
- **Platform Admin Login** (new: `app/(platform-admin)/login/page.tsx`) — same component pattern minus tenant branding/registration link, wired to `/platform-admin/auth/login`.
- **Logout Confirmation** — Tenant Admin/Platform Admin only per the UX spec's own scoping; wired to `POST /auth/logout`; focus-trapped dialog, returns focus to trigger on cancel.
- **Session Expired / Unauthorized (401)** — built as shared components under `components/states/`, triggered by the API client's silent-refresh-failure path and by any protected-route guard with no session. **Reconciliation**: the UX spec wants a dedicated Session Expired screen, but AUTH-2's own frontend-impact note says "silent token refresh... redirect-to-login on refresh failure, no dedicated page." Resolved here as: redirect to login with a `?reason=session_expired` query state that renders the Session-Expired copy as a banner on the login page, satisfying both.
- **Permission Denied (403)** — reuse the existing shared component; narrow its prop type to accept the real `ApiClientError` object (not a bare string) so "server-verified only" is compiler-enforced, not just documented; add the missing "back to your dashboard" link.
- **Suspended Tenant** — wired for real; AUTH-1's own acceptance criteria explicitly reject suspended-tenant logins server-side.
- **Suspended User** — shell built now; confirm with backend that user-level (not just tenant-level) suspension is actually enforced before finalizing/testing its copy (see §21).

**Explicitly deferred:**
- Forgot Password, Reset Password, Password Reset Success, Invalid/Expired Reset Link — no backend endpoint exists; `forgot-password/page.tsx` stays exactly as-is.
- Account Locked — no server-side trigger exists in this module; not wired.
- First-Login Password Change — deferred per §4/§6.

## 12. Validation rules

- Client-side (Zod, `lib/validation/auth.ts`'s existing `loginSchema`): email format + required, password required, no complexity re-check at login — this is UX convenience only, never a substitute for backend validation.
- Server-side: credential format re-validated regardless of client checks; subdomain-to-tenant resolution failure rejects before any credential lookup, with **no fallback** to a default tenant or cross-tenant email match; tenant status checked before password verification (tenant suspension only — see §21 for the user-suspension-vs-password-check ordering decision).
- Login/refresh/logout request DTOs must never accept a client-supplied `tenant_id` field — this is the single highest-value review check for this module (risk R6).
- Argon2id parameters bound from `@ConfigurationProperties`, validated at startup (fail fast on missing/invalid config), never hardcoded magic numbers.

## 13. Error cases

| Case | Behavior |
|---|---|
| Invalid credentials (email not found vs. wrong password) | Identical generic response either way — no field-level distinction, timing-insensitive where practical |
| Suspended tenant | Rejected before credential evaluation; generic copy, no internal reason |
| Suspended user | Rejected after password verification succeeds (see §21); generic copy, "contact your institute administrator" |
| Expired access token, valid refresh | Silent refresh issues a new pair |
| Expired/invalid/already-rotated refresh token | `401`, generic — does not distinguish "already used" from "unknown token" |
| Revoked/expired session replayed with a signature-still-valid JWT | Rejected — explicit test required, not incidental |
| Cross-tenant token replay | Rejected `401`/`403` — never `200` with filtered/empty data |
| Manipulated tenant-identifying header/param | Ignored entirely; resolution uses subdomain only |

## 14. Tenant-isolation rules

- Tenant identity resolved **exactly once**, in `TenantResolutionFilter`, from the subdomain — never from the JWT claim, a header, a query/path parameter, or a request body field. The JWT `tenant_id` claim is only ever *cross-checked* against the resolved value, never used to *derive* it.
- Mismatch between the token's `tenant_id` claim and the resolved subdomain tenant → reject `401`, no fallback.
- `tenant_user` and `device_session` extend `TenantAwareRepository`; `tenant`, `platform_admin_user`, and `platform_admin_session` are platform-level and must never gain an "optional" `tenant_id` column.
- `device_session`'s FK to `tenant_user` is composite `(tenant_id, user_id)` against `tenant_user`'s `(tenant_id, id)` unique constraint — a session row referencing a different tenant's user is a constraint violation, not just a service-layer bug.
- Login/refresh/logout endpoints' request DTOs contain no `tenant_id` field at all (verified in code review, per risk R6).

## 15. Security rules

- JWT payload strictly `sub`/`tenant_id`/`role`/`session_id` — no permission list (ADR-007 decision 1); any future PR adding claims requires reopening the ADR, not a silent change.
- Refresh rotation invalidates the old token in the same operation that issues the new one.
- Postgres is the sole authority for session validity; Redis is read-through cache only, invalidated in lockstep, never written independently; every check has a Postgres fallback on cache miss/staleness.
- Argon2id via `Argon2PasswordEncoder`, config-driven parameters; no bcrypt/plaintext fallback path left reachable.
- Anti-enumeration: identical invalid-credentials response regardless of which check failed; a closed set of reason codes (invalid-credentials / tenant-suspended / user-suspended) rather than free-text reasons; the invalid-credentials code itself never subdivides further.
- Plaintext password never logged, never in error bodies, never in any API response (including the hash).
- Authorization headers, JWT/refresh-token values, and full request bodies excluded from MDC and from `GlobalExceptionHandler`'s logged payload.
- `platform_admin_user` login is structurally isolated from the tenant login path — not a role branch inside one shared controller.

## 16. Audit requirements

`docs/architecture/authentication-authorization.md` §5 requires device registration and "the login-activity/audit record that logs it" to be written together as one atomic unit. Resolved here: **the `device_session` row itself (capturing `tenant_id`, `user_id`, `issued_at`, `device_identifier_hash`) is the login-activity record for this module's scope** — no separate audit-log-management event/table is needed, since it is already written synchronously in the same transaction as login and already carries the required fields. This avoids inventing a redundant table while satisfying the atomicity requirement as written.

Logout and refresh-token rotation are **not** on `.claude/rules/security.md`'s canonical mandatory-audit-action list (price changes, payment approvals, device resets, access/expiry extensions, reactivation approvals, content deletions, settlement changes, impersonation). The `device_session` row's own `revoked_at`/`last_rotated_at` history is treated as sufficient for these — no separate audit-log-management write is added for them in this module.

Failed-login attempts are **not** audit-logged in this module's scope — building that now would silently pull Phase-2 suspicious-login-detection scope forward (risk R13); explicitly deferred.

## 17. Payment impact

None. Confirmed in `docs/planning/product-backlog.md` for `AUTH-1`, `AUTH-2`, and `AUTH-3` ("Payment impact: None" on all three). This module has no order/payment/ledger/enrollment surface — `.claude/rules/payments.md` does not apply here.

## 18. Tests

**Backend — unit/slice**
- `JwtTokenServiceTest` — payload contains only `sub`/`tenant_id`/`role`/`session_id`; 15-min expiry.
- `AccessTokenValidatorTest` — signature/expiry validation in isolation: valid accepted, expired rejected, tampered signature rejected.
- `CredentialVerificationServiceTest` — wrong-password rejection logic.
- `Argon2PasswordEncoderConfigTest` — bean is `Argon2PasswordEncoder`, not bcrypt/plaintext; two different config profiles yield different encoder parameters (proves config-driven, not just "Argon2id is used").
- `TenantSubdomainResolverTest` — resolves from subdomain; unresolved subdomain fails safe, no fallback to a default/first tenant.

**Backend — Testcontainers integration** (extend `AbstractIntegrationTest`, seed both `TENANT_A`/`TENANT_B` per `docs/architecture/multi-tenancy.md` §3's test-data convention even where a test only asserts single-tenant behavior)
- `LoginIntegrationTest` — successful login persists `device_session` atomically with the login attempt; valid tenant-A credentials posted against tenant-B's resolved subdomain rejected.
- `SuspendedTenantLoginIntegrationTest` — login against a suspended tenant rejected server-side (add explicitly — missing from the backlog's own testing-requirements list despite being a named acceptance criterion).
- `RefreshTokenRotationIntegrationTest` — replaying an already-rotated-out refresh token → 401.
- `LogoutIntegrationTest` — logout revokes the `device_session` row and the Redis key in the same operation; a subsequent request with the old access token is rejected.
- `RevokedSessionBlocksAccessTokenIntegrationTest` — a revoked/expired `device_session` blocks an otherwise signature-valid, non-expired JWT.
- `PlatformAdminLoginIntegrationTest` — platform admin login never touches `TenantResolutionFilter`/tenant context.

**Cross-tenant negative tests (mandatory)**
- `CrossTenantTokenClaimIntegrationTest` — JWT `tenant_id` claim always matches the resolving tenant, even when the same email exists for both tenants.
- `CrossTenantSessionReplayIntegrationTest` — a token minted for tenant A/session A rejected when replayed against a request resolving tenant B.
- `CrossTenantTenantIdHeaderManipulationIntegrationTest` — a manipulated header/param claiming tenant B's id against tenant A's subdomain still resolves to tenant A.

**Frontend/Playwright**
- `login.spec.ts` — login for representative role fixtures (Tenant Admin, Teacher, Student, one staff role) across ≥2 tenant subdomains; correct role-dashboard redirect.
- Invalid-credential error rendered with `role="alert"`.
- `token-refresh.spec.ts` — silent refresh via route interception (mocked 401-then-refresh); redirect-to-login on refresh failure.
- Session-expired interstitial spec.
- Update `route-groups.spec.ts`/`auth-disabled-forms.spec.ts` in the same change — they currently assert the login form is a disabled placeholder, which this module's implementation breaks by design; update, don't leave red.
- Existing `accessibility.spec.ts` uses manual role/label assertions, not `@axe-core/playwright` (not installed) — new specs follow that existing manual pattern rather than assuming axe-core exists.

**Explicitly out of scope**: device-limit/reset, suspicious-login detection, MFA/TOTP, forgot/reset-password, full tenant registration/approval — no corresponding backend logic exists in this module.

## 19. Documentation changes

- `docs/api/identity-access-service.md` — new, via `review-api-contract`, before frontend work starts (per this repo's `docs/api/README.md` convention and the design spec's own flagged dependency).
- `docs/architecture/authentication-authorization.md` — confirm the implementation matches the documented baseline once built; no content change expected unless implementation surfaces a genuine deviation (which would require an ADR, not a doc edit).
- `docs/architecture/database-architecture.md` / `docs/architecture/multi-tenancy.md` — no change expected; this module applies the already-documented mechanism.
- `docs/ui-ux/authentication-design-spec.md` — update to record the Session-Expired-as-banner reconciliation (§11) and the Permission-Denied prop-narrowing change, so the spec doesn't silently drift from the shipped implementation.
- No new ADR required — ADR-007 already covers token format/hashing/MFA phasing, and this module implements within that boundary (per Application Foundation's precedent of confirming no new ADR needed when staying inside an already-accepted decision).

## 20. Implementation order

Per root `CLAUDE.md`'s workflow: backend fully implemented and tested before frontend starts; backend and frontend land as separate commits (this task has not stated "full-stack implementation approved").

**Backend:**
1. `Tenant` entity + `V2__create_tenant.sql` + `TenantRepository`/`TenantLookupService`.
2. `TenantResolutionFilter` wired into `SecurityConfig` (reordered ahead of `CorrelationIdFilter`), with a fail-safe-no-fallback test.
3. Argon2id `PasswordEncoder` bean + config properties (`AUTH-3`).
4. `TenantUser`/`PlatformAdminUser`/`DeviceSession`/`PlatformAdminSession` migrations (`V3`–`V6`) + entities/repositories.
5. Add JWT library dependency to `pom.xml` (recommend `io.jsonwebtoken:jjwt` — flag for team confirmation, see §21).
6. Login service/endpoint: credential verification, coarse `Role` enum, JWT issuance, refresh-token hash + `device_session` insert in one transaction (login-activity record, per §16).
7. `JwtAuthenticationFilter` + `AuthenticatedPrincipalHolder`, live role/status re-check, tenant-claim cross-check.
8. Redis fast-path cache for session validity, with a bounded TTL.
9. Refresh endpoint (rotation, absolute lifetime cap).
10. Logout endpoint (Postgres revoke + cache invalidation, same service call).
11. Platform Admin login/refresh/logout, structurally separate path excluded from `TenantResolutionFilter`.
12. Full backend test suite from §18, including mandatory cross-tenant negative tests.
13. `docs/api/identity-access-service.md`.
14. Run `backend\mvnw.cmd verify` — must be green.
15. Security + tenant-isolation review pass (confirm no endpoint accepts a client-supplied `tenant_id`; confirm MDC exclusions; confirm Redis/Postgres lockstep on logout).
16. Commit backend work as its own logical commit(s).

**Frontend (after backend is fully merged and tested):**
17. Add missing UI primitives (`Alert`, `Checkbox`, `Form Field Wrapper`, `Validation Message`) via shadcn CLI.
18. Wire Tenant-Branded Login, remove disabled fieldset, real API call + error states.
19. Platform Admin Login route + component.
20. Session Expired banner-on-login, Unauthorized/401 shared component, narrow Permission Denied's prop type.
21. Suspended Tenant (real), Suspended User (shell, pending confirmation).
22. Logout Confirmation dialog (Tenant Admin/Platform Admin only).
23. Update `route-groups.spec.ts`/`auth-disabled-forms.spec.ts` for the now-enabled forms; add new Playwright specs from §18.
24. Run `npm run lint && npm run build && npx playwright test` — must be green.
25. Documentation updates from §19.
26. Commit frontend work as a separate logical commit from backend, per `.claude/rules/git-workflow.md`.

## 21. Risks and unresolved decisions

None of the following are resolved by this plan — each requires explicit confirmation before or during implementation, not a silent default:

- **Platform Admin login path structure** — recommended as a distinct path prefix excluded from `TenantResolutionFilter` (§9); not explicitly stated in any backlog story or ADR. Needs sign-off before implementation, since AUTH-1 nominally describes "one shared login path" for all roles and this recommendation is a specific interpretation of how that coexists with tenant resolution's fail-safe-no-fallback rule.
- **`platform_admin_session` table** — added in §7/§8 to close a gap the backlog's own schema left open (session persistence for a table that must never carry `tenant_id`). Flagging for confirmation rather than treating it as silently settled.
- **Password-check vs. suspension-check ordering** — recommend verifying the password first and only then checking user-suspension status, so a wrong password against a suspended account still returns the generic invalid-credentials response (avoiding an enumeration side channel the "generic invalid-credentials" rule doesn't otherwise cover). Not resolved by any source document — needs explicit confirmation before implementation.
- **Redis/Postgres logout invalidation mechanism** — ADR-007 says "in lockstep" but not the concrete mechanism. Recommend: Postgres write first (authoritative), then Redis delete, within the same service method, with a short bounded Redis TTL as defense in depth against the window where a cache hit could momentarily outlive a revocation. Needs confirmation, not just this plan's recommendation.
- **JWT library choice** — recommend `io.jsonwebtoken:jjwt` (HS256, single self-issued secret); ADR-007 didn't pin a library. Needs team confirmation before `pom.xml` is touched.
- **Refresh-token delivery mechanism** — recommend `httpOnly` `Secure` cookie (consistent with ADR-007's device-identifier cookie treatment); not explicitly specified for the refresh token itself. Needs security sign-off.
- **`totp_secret` column placement** — the backlog's own AUTH-1 (§8) and AUTH-3 (§8) entries disagree on which table carries it (`device_session` vs. `tenant_user`/`platform_admin_user`). This plan places it on the per-identity tables (`tenant_user`/`platform_admin_user`) as the semantically correct location, since a TOTP secret belongs to an identity, not a session — flagging the backlog inconsistency rather than silently picking a side without noting it.
- **`must_change_password` applicability to staff accounts** — AUTH-3's own stated open decision; not resolved here.
- **First-Login forced-change UX** — deferred per §4/§6; the exact login-response contract and redirect behavior need a follow-up story before this is buildable.
- **Suspended-user acceptance criterion** — AUTH-1's stated acceptance criteria explicitly cover tenant suspension but not user-level suspension as clearly; confirm with backend that `tenant_user.status != 'active'` actually blocks login before finalizing the frontend's Suspended User screen and its tests.
- **Forgot/reset-password ownership** — genuine gap between the fully-specified UI screens and the empty backlog coverage; needs a product decision on which module/story owns it, rather than continuing to leave it unowned.
- **Refresh-token absolute lifetime** — ADR-007 "recommends" 30 days; treat as a config default, not a hardcoded/ratified constant, until confirmed.
- **Risk R6** (tenant_id trusted from client input) — highest-likelihood risk for this wave per the risk register; mitigated by the DTO-level review check in §14, but remains the single most important thing to verify in code review.
- **Risk R13** (device-auth MVP/Phase-2 scope mismatch) — mitigated by this plan's explicit out-of-scope list (§6); a future reviewer should treat the absence of device-limit logic as correct scoping, not a defect.
- **"Remember me" checkbox** — no backend session-persistence policy exists for it anywhere in the source documents. Recommend removing it from the login form entirely for this module rather than shipping a non-functional checkbox that implies a capability that isn't real; flagged as a UX call needing confirmation, not decided unilaterally here.
