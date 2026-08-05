# MVP Product Backlog

Source: five-agent review (product-requirements-analyst, solution-architect, database-architect,
qa-test-engineer, security-reviewer) of the approved documents under `docs/requirements/`,
`docs/architecture/`, `docs/adr/`, and `.claude/rules/`. 21 modules, 61 stories, MVP scope only —
no Phase 2/3/4 sub-features from the underlying specs are included here (see each spec under
`docs/requirements/specifications/` for the full phase breakdown).

Every story lists exactly 13 fields: story ID, business outcome, user role, acceptance criteria,
dependencies, backend impact, frontend impact, database impact, security impact, tenant impact,
payment impact, testing requirements, documentation requirements. Where a field is genuinely not
applicable, it says "None" with a reason rather than being left blank. Items marked **OPEN
DECISION** are tracked centrally in `docs/requirements/open-decisions.md` — do not resolve them
implicitly during implementation.

See also: `docs/planning/mvp-release-plan.md` (sequencing), `docs/planning/dependency-map.md`
(visual dependency graph), `docs/planning/risk-register.md`, `docs/planning/definition-of-ready.md`,
`docs/planning/definition-of-done.md`.

---

## MODULE 1 — Application foundation

### APP-1 — Backend application skeleton

1. **Story ID:** APP-1
2. **Business outcome:** Establishes the deployable Spring Boot modular-monolith foundation (bootstrap, environment-specific config profiles, health/readiness checks) that every domain module and the CI/deployment pipeline depend on.
3. **User role:** No direct end-user role — internal/platform engineering foundation; indirectly enables all roles.
4. **Acceptance criteria:**
   - Given the app boots, when the health/actuator endpoint is queried, then it returns UP with no sensitive data exposed and no authentication bypass created for other endpoints.
   - Given dev/staging/production profiles, then each loads its own isolated configuration/credentials with no shared secrets across environments.
   - Given the package-structure convention (`com.lms.<domain>` with `api/web/service/domain/repository/config`), then the skeleton scaffolds it for the foundational domains.
   - Given the app instance, then it holds no in-memory session state or in-JVM authoritative cache, consistent with the stateless-instance requirement.
5. **Dependencies:** None.
6. **Backend impact:** No single domain — platform bootstrap (Spring Boot app class, `application*.yml` profiles, actuator/health). Establishes the Maven module structure all 18 domain packages will be created under.
7. **Frontend impact:** None — backend-only.
8. **Database impact:** None. No entities/migrations required for bootstrap/health/actuator config.
9. **Security impact:** No direct authZ/tenant logic, but this is where the Spring Security filter chain, actuator endpoint exposure, and config-profile secret handling are first established. Actuator endpoints must not leak secrets/config or be publicly reachable without auth in non-dev profiles. Also the point where the `.env`/secrets-management pattern is set.
10. **Tenant impact:** Platform-level (infrastructure story, not tenant-owned data).
11. **Payment impact:** None.
12. **Testing requirements:** Test-light — pure bootstrap/config, no tenant-owned data path yet. Testcontainers-backed Spring context load test (Postgres + Redis wired, Flyway runs clean); actuator `/health` slice test returns UP with DB/Redis indicators. No cross-tenant test required.
13. **Documentation requirements:** `docs/architecture/deployment-architecture.md` (confirm environment separation/health-check behavior); `docs/architecture/modular-monolith.md` (confirm initial package scaffold matches convention). No `docs/api` entry yet — no business endpoints exist.

### APP-2 — Frontend application shell

1. **Story ID:** APP-2
2. **Business outcome:** Establishes the Next.js App Router shell with role-based route groups and a theme provider, giving every portal a consistent, isolated navigation and branding foundation.
3. **User role:** No direct end-user role at this story; foundational for Student, Teacher, Tenant Admin, Platform Admin, Public.
4. **Acceptance criteria:**
   - Given the App Router structure, then route groups exist for `app/(student)/`, `app/(teacher)/`, `app/(tenant-admin)/`, `app/(platform-admin)/`, `app/(public)/` with no cross-role-group component imports.
   - Given the theme provider, then it is shaped to fetch tenant branding from tenant-scoped config at runtime rather than hardcoding.
   - Given light/dark mode, then both are supported by the theme provider from the start.
   - Given no tenant/role selector renders in the Tenant Admin or Student route groups.
   - Given React Query is established as the data-fetching pattern rather than ad hoc `fetch`/`useEffect`, even before real endpoints exist.
5. **Dependencies:** None — can run fully in parallel with APP-1.
6. **Backend impact:** None.
7. **Frontend impact:** All route groups scaffolded, theme provider, shared layout shells. No page has real data yet.
8. **Database impact:** None (frontend-only story).
9. **Security impact:** Establishes the role-based route-group structure. This is a UX/routing convenience only — must not be treated as the authorization boundary.
10. **Tenant impact:** N/A — no backend data touched.
11. **Payment impact:** None.
12. **Testing requirements:** Playwright: each role route group renders without error; unauthenticated access to a protected group redirects to login (not just hides nav); theme provider falls back to neutral platform-default branding when no tenant context is resolved. No cross-tenant test required yet (re-verify once RBAC-3/TEN-3 exist).
13. **Documentation requirements:** No `docs/api` entry (no endpoints yet). State explicitly: structural scaffold only — conventions already defined in `.claude/rules/frontend.md`/`ui-ux.md`.

### APP-3 — CI pipeline and local dev infrastructure wiring

1. **Story ID:** APP-3
2. **Business outcome:** Gives the team a repeatable, automated build/test pipeline and local Docker Compose stack so every module can be verified consistently before merge.
3. **User role:** No end-user role — internal engineering/CI concern.
4. **Acceptance criteria:**
   - Given a PR, then CI runs backend build+tests and frontend build+tests.
   - Given local dev, then Docker Compose brings up Postgres, Redis, backend, frontend, Nginx, using only synthetic/dev data.
   - Given CI runs Testcontainers-backed integration tests, then Postgres/Redis containers are available in the pipeline environment.
   - Given any CI run, then no production database or production credentials are referenced anywhere in pipeline config.
   - Given branch protection, then direct pushes to `main` are blocked and merges require PR + human approval.
5. **Dependencies:** Soft dependency on APP-1 and APP-2 (needs both app skeletons to have something to build/lint/test), but pipeline definition can be authored in parallel.
6. **Backend impact:** No domain — Docker Compose (Postgres/Redis/Nginx), Maven build wiring, Testcontainers plumbing.
7. **Frontend impact:** npm build/lint/Playwright wiring only; no page-level impact.
8. **Database impact:** None directly, but this story should confirm Flyway runs migrations in the Testcontainers-backed test bootstrap.
9. **Security impact:** Secrets handling in CI (DB credentials, JWT signing secret placeholders, test fixtures) must use non-production, synthetic values only. Verify Docker Compose/CI env don't hardcode a real-looking secret.
10. **Tenant impact:** N/A — infrastructure only.
11. **Payment impact:** None.
12. **Testing requirements:** Test-light/config story — no new unit/integration/E2E tests to author. Verification is that CI runs `backend\mvnw.cmd verify` and `npx playwright test` automatically and fails the build on red. No cross-tenant test required.
13. **Documentation requirements:** `docs/architecture/deployment-architecture.md` §6 flags CI/CD tooling as an **OPEN DECISION** — update once a concrete tool is chosen, otherwise leave flagged.

### APP-4 — Structural tenant-filtering foundation (TenantAwareRepository, ADR-006)

1. **Story ID:** APP-4
2. **Business outcome:** Implements the single, reviewable, platform-wide mechanism (`TenantAwareRepository<T,ID>`) that structurally enforces tenant isolation for every tenant-owned repository across all 18 domains — the single highest-leverage tenant-isolation story in the entire backlog.
3. **User role:** No end-user role — foundational mechanism protecting all tenant-scoped roles' data.
4. **Acceptance criteria:**
   - Given a tenant-owned entity's repository extends `TenantAwareRepository<T,ID>`, then every standard finder is automatically scoped to the resolved `tenant_id` from the request-scoped context, never a caller-supplied value.
   - Given a custom `@Query`/specification method, then it must explicitly apply `tenant_id` or compose against an already-tenant-scoped base method — it is not silently rewritten.
   - Given a legitimate cross-tenant read, then it exists only as a distinctly named, non-`TenantAwareRepository` method (e.g. `findAllAcrossTenantsForPlatformReport`).
   - Given a background job/event listener, then it must explicitly receive and apply `tenant_id` — this base class does not solve async propagation by itself.
5. **Dependencies:** Hard blocker: APP-1. Soft dependency on AUTH-1/AUTH-2 for the request-scoped tenant-context holder to be populated at runtime (compiles before login exists, unverifiable until a real authenticated request flows through it).
6. **Backend impact:** Cross-cutting infrastructure, not owned by one of the 18 domains. Package-ownership ambiguity flagged: likely lives adjacent to `identity-access-service`'s `api` (the sole legitimate resolver of tenant identity) — resolve explicitly before the first domain repository is written. Every other domain's `repository` layer depends on this.
7. **Frontend impact:** None — backend-only.
8. **Database impact:** No new tenant-owned table itself. Coordinate with TEN-1 to avoid a duplicate migration creating `tenant` twice — recommend TEN-1 owns that migration; this story only adds the `TenantAwareRepository` base class referencing `tenant` as a dependency.
9. **Security impact:** The highest-leverage tenant-isolation story in the backlog. Any weakness (e.g. base allows overriding tenant_id via an argument, or silently falls back to unfiltered query if context absent) is a platform-wide cross-tenant breach vector. Must include explicit, differently-named bypass methods and a mechanism ensuring custom `@Query` methods also apply `tenant_id`.
10. **Tenant impact:** This IS the mechanism story — establishes `TenantAwareRepository<T,ID>` per ADR-006, injecting `tenant_id` from the request-scoped trusted context. Defines the named-bypass convention every later platform-admin story depends on.
11. **Payment impact:** None directly, but this base is a hard prerequisite for every payment/ledger repository (PAY-*, SLIP-*).
12. **Testing requirements:** Unit: tenant-context holder resolves/propagates tenant id; fails safely (does not default to unfiltered) when no context is present. Testcontainers: throwaway entity/repository extending `TenantAwareRepository` — standard finders auto-scoped; a custom `@Query` omitting an explicit tenant param proven to leak (documents the accepted trade-off). **Cross-tenant negative test (mandatory, foundational):** seed rows for tenant A/B in the same table; tenant A's repository call returns zero rows for tenant B. Explicit-bypass-method test.
13. **Documentation requirements:** `docs/architecture/database-architecture.md` and `docs/architecture/multi-tenancy.md` already describe this decision — confirm they match the actual implementation. No new ADR needed; ADR-006 already Accepted.

---

## MODULE 2 — Authentication foundation

### AUTH-1 — Login: credential verification + JWT access/refresh token issuance

1. **Story ID:** AUTH-1
2. **Business outcome:** Lets every role authenticate through one shared, secure login path issuing short-lived JWT access tokens plus rotated opaque refresh tokens, establishing the identity foundation every other module depends on.
3. **User role:** All roles (Platform Admin, Tenant Admin, staff sub-roles, Teacher, Student) — one shared login path, no parallel auth stack per portal.
4. **Acceptance criteria:**
   - Given valid credentials for a tenant-resolved user, when login succeeds, then a 15-minute JWT access token (`sub`, `tenant_id`, `role`, `session_id` — no permission list) and a rotated, hashed, server-persisted opaque refresh token are issued.
   - Given an unapproved/suspended tenant, when any user attempts login against that tenant's subdomain, then login is rejected server-side.
   - Given tenant identity resolution, then it happens exactly once at the auth filter/interceptor from the validated credential — never from a client-supplied `tenant_id`.
   - **PHASE-BOUNDARY FLAG:** device-slot/limit logic is Phase 2 (per `16-device-authentication.md`) — this MVP story covers login-activity logging only, not device-limit enforcement; do not silently pull Phase-2 device logic forward.
5. **Dependencies:** Hard blockers: APP-1, APP-4, AUTH-3 (Argon2id verification required before credentials can be checked), **TEN-1 + TEN-3** (tenant table and subdomain resolution must exist — login cannot resolve which tenant's user to check against otherwise; this is a forward reference to Module 4, flagged in the release plan).
6. **Backend impact:** `identity-access-service` — `web` (login endpoint), `service` (credential verification, token issuance), `domain` (user credential/`device_session` entities), `repository` (extends `TenantAwareRepository`). Publishes the `api` interface ("current authenticated principal," "resolved tenant context") every other domain consumes — the single most depended-upon `api` surface in the platform.
7. **Frontend impact:** `app/(public)/` (tenant-scoped login page) — Text Input, Password Input, Button (loading state), Alert (invalid-credentials), Form Field Wrapper.
8. **Database impact:** New tables: `tenant_user` (tenant_id NOT NULL, email, password_hash, role, status) and `device_session` (id, tenant_id, user_id, refresh_token_hash, device_identifier_hash, issued_at, expires_at, revoked_at, reset_at nullable, totp_secret nullable). Platform admin accounts must NOT share this table (separate `platform_admin_user`, no tenant_id) per database-architecture.md §1.
9. **Security impact:** Core authN story. Argon2id password verification, HS256 JWT, tenant identity resolved exactly once at this layer from validated credentials — never from client-supplied `tenant_id`/subdomain claim taken at face value. This is a change-controlled area (authentication architecture) — any deviation from ADR-007 needs a new ADR.
10. **Tenant impact:** `tenant_user`: `tenant_id NOT NULL` + `UNIQUE (tenant_id, email)` (never global) + composite index `(tenant_id, email)`. `device_session`: `tenant_id NOT NULL` + index `(tenant_id, user_id)`. `platform_admin_user` is platform-level, no `tenant_id`.
11. **Payment impact:** None.
12. **Testing requirements:** Unit: JWT payload construction (no permission list embedded); wrong-password rejection logic. Testcontainers: successful login persists `device_session` + login-activity/audit record atomically; login against a mismatched tenant subdomain rejected even with valid tenant-A credentials. **Cross-tenant negative test (mandatory):** issued JWT's `tenant_id` claim always matches the resolving tenant. Playwright: login for all 4 role fixtures across ≥2 tenants; invalid-credential error announced via `role="alert"`.
13. **Documentation requirements:** `docs/architecture/authentication-authorization.md` (confirm implementation matches documented baseline); `docs/api` for the login endpoint contract. No new ADR — ADR-007 already covers token format.

### AUTH-2 — Session/token validation middleware, refresh, and logout

1. **Story ID:** AUTH-2
2. **Business outcome:** Keeps sessions secure and stateless-instance-compatible by validating access tokens on every request, rotating refresh tokens on use, and revoking sessions cleanly on logout.
3. **User role:** All authenticated roles.
4. **Acceptance criteria:**
   - Given a valid, non-expired access token, when a protected endpoint is called, then the request succeeds and re-verifies the actor's current role/permissions server-side — never trusting the token payload as final word.
   - Given an expired access token, when the client refreshes with a valid refresh token, then a new access token is issued and the old refresh token is invalidated the instant the new one is issued.
   - Given logout, then the `device_session` row is revoked in PostgreSQL, with any Redis-cached fast-path check invalidated in lockstep.
   - Given a revoked/expired session, when the same token is replayed, then the request is rejected, even if the JWT itself hasn't technically expired.
5. **Dependencies:** Hard blockers: AUTH-1, APP-4.
6. **Backend impact:** `identity-access-service` — `web` (refresh/logout endpoints), `service` (refresh-token rotation, revocation check), `repository`. Exposes the `api` interceptor/filter every domain's `web` layer sits behind.
7. **Frontend impact:** Cross-cutting (API client layer, not one route group) — silent token refresh in the typed API client, redirect-to-login on refresh failure. No dedicated page.
8. **Database impact:** Additive to `device_session` from AUTH-1 (status/revoked_at columns, refresh-rotation tracking) — no new table.
9. **Security impact:** This is where tenant context and the authenticated principal get propagated into the request-scoped context every downstream domain trusts. A bug here undermines every tenant-isolation and authZ check platform-wide. Refresh rotation must invalidate the prior token immediately on use. Logout must revoke server-side state, not just clear a client cookie.
10. **Tenant impact:** `device_session` remains `tenant_id NOT NULL` with `(tenant_id, user_id)` index; logout/refresh must resolve tenant from the persisted session row, never a client-supplied `tenant_id`.
11. **Payment impact:** None.
12. **Testing requirements:** Unit: signature/expiry validation. Testcontainers: replaying an already-rotated-out refresh token rejected 401; logout revokes the session row; a revoked/expired `device_session` blocks an otherwise signature-valid access token. **Cross-tenant negative test (mandatory):** an access token minted for tenant A/session A is rejected when replayed against a request resolving tenant B.
13. **Documentation requirements:** `docs/architecture/authentication-authorization.md` (confirm refresh/logout flow matches); `docs/api` for refresh/logout endpoint contracts.

### AUTH-3 — Password hashing and credential storage (Argon2id)

1. **Story ID:** AUTH-3
2. **Business outcome:** Protects every tenant's user credentials with a modern, tunable password-hashing algorithm so a database compromise does not trivially expose passwords.
3. **User role:** All roles with password-based credentials.
4. **Acceptance criteria:**
   - Given a new credential is stored, then it is hashed with Argon2id via `Argon2PasswordEncoder`, with iteration/memory/parallelism parameters set in config, not hardcoded.
   - Given a login attempt, then the plaintext password is never logged.
   - Given the credential schema, then a nullable TOTP-secret column is reserved now, even though MFA enrollment itself is out of scope.
   - **OPEN DECISION:** whether the `must_change_password` flag applies to manually-created staff accounts, by analogy to students, is unspecified.
5. **Dependencies:** Hard blocker: APP-1 only.
6. **Backend impact:** `identity-access-service` — `service`/`config` (Argon2id `PasswordEncoder` bean), `domain` (password hash column, nullable TOTP-secret column).
7. **Frontend impact:** None — backend-only.
8. **Database impact:** Additive to `tenant_user`/`platform_admin_user` from AUTH-1: `password_hash` column, `must_change_password BOOLEAN NOT NULL DEFAULT false`. No new table.
9. **Security impact:** No real credentials/passwords may appear in code/tests/fixtures. Verify no legacy/weaker fallback hashing path is left reachable, and that credential storage never logs plaintext passwords.
10. **Tenant impact:** Same tables as AUTH-1; no new tenant-scoping concern.
11. **Payment impact:** None.
12. **Testing requirements:** Unit: Argon2id (not bcrypt/plaintext) configured; parameters read from config; hash verifiable, raw password never logged/returned. Testcontainers: persisted credential row stores only the hash; login succeeds/fails correctly against it. Security test: no API response/error path ever surfaces the raw or hashed password field.
13. **Documentation requirements:** `docs/architecture/authentication-authorization.md`/ADR-007 already record this decision — confirm implementation matches. No new ADR needed.

---

## MODULE 3 — Roles and permissions

### RBAC-1 — Role/permission data model

1. **Story ID:** RBAC-1
2. **Business outcome:** Establishes the data model for the full role list so role-based access and portal scoping can be enforced consistently across every domain.
3. **User role:** All roles (this story defines them).
4. **Acceptance criteria:**
   - Given the role list in `user-roles-and-permissions.md` §1, then all 12 roles plus Anonymous/Public are representable in the data model.
   - Given a staff account, then its role/sub-role is tenant-scoped, while Platform Admin is platform-scoped with no tenant association.
   - **OPEN DECISION:** Teacher Assistant's entire permission boundary is PROVISIONAL/unratified — the model must support the proposed split but must not present it as a confirmed, hard-gated rule without sign-off.
5. **Dependencies:** Hard blockers: APP-1, APP-4. Soft dependency on AUTH-1: the JWT `role` claim needs a minimal role enum in place alongside/before AUTH-1 ships, even though RBAC-1 is numbered after (ship a minimal enum with AUTH-1, let RBAC-1 formalize/extend it).
6. **Backend impact:** `identity-access-service` — `domain`/`repository` (platform role + tenant staff sub-role tables), `api` (permission-check interface) that every other domain's `web`/`service` layer calls — a second foundational cross-module surface alongside AUTH-1's.
7. **Frontend impact:** None directly (frontend consumes the result via RBAC-3).
8. **Database impact:** New tables `role` (fixed catalog) and either a `role` enum column on `tenant_user` (recommended, single-role-per-user per the specs) or a `tenant_user_role` join table if multi-role is later confirmed.
9. **Security impact:** Read-only Auditor must have no server-side mutating path modeled, regardless of what any future UI exposes. This data model underlies every per-endpoint authZ check in RBAC-2 — a missing/ambiguous role here becomes an authZ gap downstream.
10. **Tenant impact:** If a join table is used: `tenant_user_role(tenant_id NOT NULL, user_id, role)` with `(tenant_id, user_id)` index. Role catalog itself is platform-global reference data, no `tenant_id` needed on the catalog table.
11. **Payment impact:** None.
12. **Testing requirements:** Unit: fixed role-set/enum validation — unrecognized role value rejected at the DTO boundary. Testcontainers: role/permission rows persist tenant-scoped; FK/enum constraints reject an out-of-set role. **Cross-tenant negative test:** tenant A's role-assignment rows not readable by tenant B via id.
13. **Documentation requirements:** `docs/architecture` (new role/permission table data-model entry). `docs/api` progressively records the endpoint-level matrix per domain as each is contract-reviewed.

### RBAC-2 — Server-side authorization enforcement

1. **Story ID:** RBAC-2
2. **Business outcome:** Makes every protected endpoint independently reject unauthorized actions server-side, regardless of what any client UI shows.
3. **User role:** All roles — enforced against, especially Read-only Auditor and staff sub-roles scoped to one operational area.
4. **Acceptance criteria:**
   - Given Read-only Auditor role, then no mutating endpoint succeeds for this role under any circumstance, server-side.
   - Given a staff sub-role without a domain's permission, then the request is rejected 403 server-side.
   - Given every authorization check, then it is evaluated for the resolved tenant context — a check that doesn't confirm "for this tenant" is incomplete.
   - Given Platform Admin, then platform-scoped permissions do not implicitly grant tenant-admin-equivalent access without an explicit, audited impersonation flow.
5. **Dependencies:** Hard blockers: RBAC-1, AUTH-2 (resolved-actor context), and effectively TEN-3 (tenant context) since every check must be "for this tenant."
6. **Backend impact:** `identity-access-service` — cross-cutting `service`/`config` (method-security aspect/annotation applied to every other domain's `web` controllers). Every business-domain controller from Module 4 onward depends on this being correct.
7. **Frontend impact:** None — backend-only.
8. **Database impact:** None (enforcement logic, e.g. `@PreAuthorize`/interceptor, not schema).
9. **Security impact:** This is the enforcement mechanism referenced by every spec's "Authorization rules" section — the single point that, if weak, invalidates every module's authorization claims. Requires negative-path authorization tests per role/sub-role for every new protected endpoint.
10. **Tenant impact:** Every check must re-verify actor's current role from `tenant_user` against the resolved tenant context. No repository bypass here — consumes `TenantAwareRepository`-scoped reads only.
11. **Payment impact:** None.
12. **Testing requirements:** Unit: matrix-table-driven test iterating each documented V/C/E/D/A permission per module row. Testcontainers: at least one real protected endpoint per representative module through the full Spring Security filter chain — deny returns 403, not silently empty/200. **Cross-tenant negative test (mandatory):** a role valid in tenant A cannot act on tenant B's resource merely because the role name matches. Broadest matrix of any RBAC story — one deny-path test per staff sub-role at minimum.
13. **Documentation requirements:** `docs/api` — each domain's endpoint contract records its permission matrix as it's reviewed.

### RBAC-3 — Frontend permission-denied state wiring

1. **Story ID:** RBAC-3
2. **Business outcome:** Gives users a clear, accessible "you don't have permission" experience driven only by a real backend signal, never a guessed client-side role check.
3. **User role:** All roles, particularly staff sub-roles and Teacher Assistant (PROVISIONAL) hitting boundary actions.
4. **Acceptance criteria:**
   - Given a 401/403 response or a role/permission value from the authenticated session payload, then the permission-denied UI state renders — never computed purely from a client-stored role string.
   - Given the shared state-component library, then the permission-denied state uses `role="alert"`/`aria-live` and is reused consistently, not reimplemented per page.
   - Given client-side route guards, then they exist only for UX convenience and are never the actual access-control mechanism.
5. **Dependencies:** Hard blockers: RBAC-2 (needs a real 403/permission signal to wire against), APP-2.
6. **Backend impact:** None (frontend consumes RBAC-2's output).
7. **Frontend impact:** Cross-cutting shared component — Permission-Denied State wired into the React Query status-mapping helper, used by every role's route group thereafter.
8. **Database impact:** None (frontend-only story).
9. **Security impact:** Must be driven only by a server-verified 401/403 or session-payload role value. Flag if implementation infers permission state purely from cached client role rather than re-checking against a live 403.
10. **Tenant impact:** N/A.
11. **Payment impact:** None.
12. **Testing requirements:** Playwright: for each of the 4 role fixtures, an out-of-permission action/route renders the shared permission-denied component driven strictly by 401/403; hidden/disabled nav items still fail server-side when reached via direct URL. **Cross-tenant E2E negative test:** Tenant A Tenant Admin navigates directly to a Tenant B resource route — UI blocks it, no flash of Tenant B content. No Testcontainers needed (relies on RBAC-2's backend tests).
13. **Documentation requirements:** `docs/ui-ux` (record the shared permission-denied component pattern if not already captured). No `docs/api` change.

---

## MODULE 4 — Tenant management

### TEN-1 — Tenant registration and profile

1. **Story ID:** TEN-1
2. **Business outcome:** Lets a prospective institute submit a registration application that creates a pending tenant record — the foundational entry point every downstream tenant-owned module depends on.
3. **User role:** Prospective institute (anonymous/public registrant); consumed by Platform Admin in TEN-2.
4. **Acceptance criteria:**
   - Given a prospective institute submits registration via the public entry point, then a tenant record is created in a pending-approval status with profile/contact/requested-plan data captured.
   - Given the `tenant` table is platform-level, then it does not share a table with tenant-owned rows.
   - **OPEN DECISION:** whether registration is public/self-serve or invite-only is unresolved — build the entry point without hard-coding one answer silently.
   - **OPEN DECISION:** uniqueness scope for tenant subdomain/custom domain is implied but never explicitly stated as a constraint.
   - **OPEN DECISION:** behavior for a duplicate/conflicting registration (same domain/subdomain twice) is unspecified.
5. **Dependencies:** Hard blockers: APP-1, APP-4 (though `tenant` itself is platform-level, not tenant-owned — its repository is a plain `JpaRepository`, not `TenantAwareRepository`). Soft dependency on APP-2 for the public registration form.
6. **Backend impact:** `tenant-management` — `web`/`service`/`domain`/`repository`.
7. **Frontend impact:** `app/(public)/` (registration entry point) and `app/(platform-admin)/` (Tenant List row scaffolding, fully populated once TEN-2 exists).
8. **Database impact:** New table `tenant` (id UUID PK, name, subdomain UNIQUE NOT NULL, status CHECK-constrained enum trial/active/suspended/cancelled, plan_id, created_at, updated_at). Root platform table — coordinate with APP-4 to avoid a duplicate migration.
9. **Security impact:** Public-facing entry point; must not allow a registrant to self-assign `active`/`approved` status or bypass Platform Admin approval. Subdomain/custom-domain uniqueness must be enforced at the DB level to prevent tenant-identity collision/hijack.
10. **Tenant impact:** `tenant` itself is platform-level (not tenant-owned — it IS the tenant identity table). No `tenant_id` column on `tenant` itself. `subdomain` needs a global `UNIQUE` constraint (the one legitimate global-unique case).
11. **Payment impact:** None.
12. **Testing requirements:** Unit: registration DTO validation. Testcontainers: tenant created in pending-approval status; subdomain/name uniqueness enforced at DB level; duplicate registration rejected. Registration endpoint never accepts a client-supplied `tenant_id`/status field. Playwright: public registration flow states.
13. **Documentation requirements:** `docs/architecture` (new `tenant` table entry, pending-status lifecycle); `docs/api` for the registration endpoint.

### TEN-2 — Platform Admin tenant approval/status workflow

1. **Story ID:** TEN-2
2. **Business outcome:** Gives Platform Admin sole, auditable control over which institutes become active tenants and over suspending/cancelling them.
3. **User role:** Platform Admin (sole approver); Tenant Admin is the resulting created account.
4. **Acceptance criteria:**
   - Given a Platform Admin approves a tenant, then status flips atomically with provisioning of default branding/plan config, and exactly one audit row records the transition.
   - Given a non-Platform-Admin actor, when they attempt to reach the approval queue or tenant list, then access is rejected server-side.
   - Given a Tenant Admin of tenant A, when they attempt to read/modify tenant B's profile/config/plan/status, then the request is rejected 403/404.
   - Empty state: "no tenants awaiting approval" is distinguishable from "no tenants match filter."
   - Given the Tenant List, then every row shows the tenant name, and no approve/suspend action is submittable without the target tenant visibly named next to it.
5. **Dependencies:** Hard blockers: TEN-1, AUTH-1/AUTH-2/RBAC-2. Soft dependency: AUDIT-1/AUDIT-2 (spec 01's acceptance criteria mandates an audit row even though not on `security.md`'s canonical list).
6. **Backend impact:** `tenant-management` — `web` (approval endpoints), `service` (status state machine), `repository` — explicitly named cross-tenant bypass method (e.g. `findAllPendingTenantsForPlatformApproval`), not a normal tenant-scoped finder.
7. **Frontend impact:** `app/(platform-admin)/` — Tenant List (Table + Status Chip + Pagination + Filters), Tenant Approval detail (Confirmation Dialog).
8. **Database impact:** Additive to `tenant` (TEN-1). Audit trail requires AUDIT-1's table (sequencing flag — see release plan).
9. **Security impact:** Platform-Admin-only with no tenant-scoped equivalent — requires explicit negative test proving no other role reaches the approval queue. Suspension of an active tenant must immediately affect login/access — verify no cached/stale session continues to work post-suspension.
10. **Tenant impact:** This is the canonical "explicitly named bypass" story — approval queue is inherently cross-tenant.
11. **Payment impact:** None.
12. **Testing requirements:** Unit: status state-machine transition validation. Testcontainers: approval flips status and provisions default config atomically (rollback test on simulated partial failure); exactly one audit row per approval/rejection/status-change. **Cross-tenant / platform-admin-only negative test (mandatory).** Playwright: Platform Admin approves/rejects/suspends with tenant name always visible.
13. **Documentation requirements:** `docs/architecture` (named cross-tenant bypass method); `docs/api` for approval/status-change endpoints. Note: this audit obligation is sourced from functional-requirements.md, not `security.md`'s canonical list — flag for AUDIT-2.

### TEN-3 — Tenant identity resolution at the edge

1. **Story ID:** TEN-3
2. **Business outcome:** Resolves which tenant a request belongs to, exactly once, at the edge layer, so every domain trusts the request-scoped tenant context rather than re-deriving it — the mechanical backbone of multi-tenancy.
3. **User role:** No direct end-user role — foundational for every tenant-scoped role's requests.
4. **Acceptance criteria:**
   - Given an incoming request, then tenant identity is resolved exactly once, at `identity-access-service`'s auth filter, from the validated token/session/subdomain — never from body, query/path parameter, header, or hidden field.
   - Given tenant identity is resolved, then it is attached to a request-scoped context read by every downstream layer.
   - Given an unresolvable subdomain/custom domain, then resolution fails safely — no fallback to another tenant.
5. **Dependencies:** Hard blocker: TEN-1. This story is itself a hard blocker for AUTH-1 despite being numbered later (forward reference — see release plan).
6. **Backend impact:** Per `multi-tenancy.md` §1.6, owned by `identity-access-service`/`tenant-management` jointly — the resolution filter lives in `identity-access-service`'s `config`/`web` filter chain but calls `tenant-management`'s `api` to validate the resolved tenant's existence/status.
7. **Frontend impact:** `app/(public)/` — branding/tenant resolution feeding the login/storefront shell; falls back to neutral platform branding on resolution failure.
8. **Database impact:** None new — reads `tenant.subdomain` from TEN-1. (Custom-domain resolution is Phase 2, out of MVP scope.)
9. **Security impact:** This is the mechanism `.claude/rules/tenancy.md` requires — a resolution bug here is a direct cross-tenant data-leak vector for every downstream module, since all of them trust this context without re-deriving it.
10. **Tenant impact:** This IS the sole resolution point per multi-tenancy.md §1. No bypass needed; this story is the trusted-source mechanism.
11. **Payment impact:** None.
12. **Testing requirements:** Unit: subdomain/custom-domain resolution logic, including fallback-to-platform-default on unresolved domain. **Cross-tenant negative test (mandatory, foundational):** a request against tenant A's subdomain with a manipulated header/param claiming tenant B's id still resolves to tenant A. Playwright: navigating two different tenant subdomains yields correctly distinct branding/login pages.
13. **Documentation requirements:** `docs/architecture/multi-tenancy.md` already describes this as the confirmed mechanism — confirm implementation matches exactly. Change-controlled area (multi-tenancy strategy); any deviation requires a new ADR.

---

## MODULE 5 — Staff management

### STAFF-1 — Staff account CRUD + role assignment

1. **Story ID:** STAFF-1
2. **Business outcome:** Lets a Tenant Admin delegate operational duties safely by creating staff accounts scoped to their tenant and assigning them a fixed sub-role, rather than sharing one owner login.
3. **User role:** Tenant Admin (full V/C/E/D); resulting staff sub-roles.
4. **Acceptance criteria:**
   - Given a Tenant Admin creates a staff account, then it is tenant-scoped, email is unique per tenant, and the account can log in only within that tenant.
   - Given a staff sub-role with no "Staff & roles" permission, when they attempt to create/edit another staff account, then the request is rejected 403.
   - Given Read-only Auditor, no mutating staff endpoint succeeds regardless of stale client UI state.
   - Empty state: "no staff accounts yet" (with Add Staff CTA) distinct from "no staff match your filter."
   - **OPEN DECISION:** staff-count-vs-plan-limit enforcement (Phase 2) depends on the unratified Feature Flag & Plan Limit Engine (Module D) — excluded from this MVP story's scope.
5. **Dependencies:** Hard blockers: TEN-1/TEN-2, AUTH-1/AUTH-2, RBAC-1/RBAC-2. Cross-module: calls `identity-access-service`'s `api` to create the underlying login credential row — do not duplicate credential storage.
6. **Backend impact:** `user-management` — `web`/`service`/`domain`/`repository` (extends `TenantAwareRepository`).
7. **Frontend impact:** `app/(tenant-admin)/` — Staff List, Staff Detail/Role Editor (Checkbox group with `fieldset`/`legend`), Empty State.
8. **Database impact:** Additive to `tenant_user` (AUTH-1) — recommend reusing `tenant_user` + `role` enum rather than a parallel `staff` table.
9. **Security impact:** Only Institute Owner has `V/C/E/D`; all other sub-roles have no access. Cross-tenant test required. Role assignment is high-blast-radius — **audit-logging is an open decision, not resolved**; recommend treating as audit-worthy despite the gap.
10. **Tenant impact:** `UNIQUE (tenant_id, email)`, never global. Composite index `(tenant_id, role)` for staff-list-by-role queries.
11. **Payment impact:** None.
12. **Testing requirements:** Unit: staff-creation/role-assignment DTO validation. Testcontainers: `UNIQUE (tenant_id, email)` enforced (not global). **Cross-tenant negative test:** list/detail/role-edit each tested. Security-sensitive: explicit 403 deny-path test per staff sub-role lacking permission. Playwright: create + role-assign flow; Read-only Auditor shows no mutating controls, and direct API attempt still fails.
13. **Documentation requirements:** `docs/architecture` (staff table, tenant-scoped unique constraint); `docs/api` for staff CRUD/role-assignment endpoints. **OPEN DECISION:** whether staff creation/role changes require audit logging — flag for AUDIT-2.

### STAFF-2 — Staff activity log and password reset

1. **Story ID:** STAFF-2
2. **Business outcome:** Gives Tenant Admin visibility into staff actions and a way to reset a staff member's password without a separate self-service recovery flow.
3. **User role:** Tenant Admin / Institute Owner.
4. **Acceptance criteria:**
   - Given a Tenant Admin views a staff member's Activity Log, then only that tenant's staff activity is shown, read-only.
   - Given a Tenant Admin resets a staff member's password, then the staff member must set a new credential at next login.
   - Given Read-only Auditor, then password-reset action is unavailable/rejected server-side.
5. **Dependencies:** Hard blocker: STAFF-1. Soft dependency: AUDIT-1/AUDIT-2 (activity log can't surface real audit rows until Module 19 exists). Soft dependency: NOTIF-1/NOTIF-2 (password-reset email is async, Module 18).
6. **Backend impact:** `user-management` (activity log read, reading from `audit-log-management`'s `api` — never a direct join), plus a `service` call into `identity-access-service`'s `api` to trigger credential reset.
7. **Frontend impact:** `app/(tenant-admin)/` — Staff Activity Log (Table, empty/loading states).
8. **Database impact:** No new table for activity log if `audit_log` is reused; password reset is additive to `tenant_user.password_hash`/`must_change_password`.
9. **Security impact:** Activity log is tenant-owned data requiring the same tenant filtering as any audit-adjacent view. Password-reset flow must not allow one tenant's admin to reset another tenant's staff password, and must not leak whether an email exists cross-tenant.
10. **Tenant impact:** Activity log view must be tenant-scoped like any other audit read (AUDIT-3's `TenantAwareRepository`-based viewer, reused here).
11. **Payment impact:** None.
12. **Testing requirements:** Unit: password-reset token logic (expiry, single-use). Testcontainers: activity log entries tenant-scoped; password reset persists a new hash and invalidates active `device_session` rows. **Cross-tenant negative test:** Tenant Admin of tenant A cannot view tenant B's activity log or reset tenant B's staff password.
13. **Documentation requirements:** `docs/architecture` if a new activity-log read model is introduced; `docs/api` for the activity-log/password-reset endpoints. **OPEN DECISION:** whether staff password reset should be added to `security.md`'s canonical mandatory-audit list.

---

## MODULE 6 — Student management

### STU-1 — Student self-registration

1. **Story ID:** STU-1
2. **Business outcome:** Lets a prospective student create their own account directly on a tenant's storefront, reducing manual onboarding overhead.
3. **User role:** Student (self-registers).
4. **Acceptance criteria:**
   - Given a student self-registers, then the resulting account is tenant-scoped to the resolving tenant only, with tenant resolved server-side — never client-supplied.
   - Given self-registration on a tenant that cannot be resolved, then it fails safely with no fallback to another tenant's registration form.
   - Given a student self-registers, then the account is NOT auto-flagged `must_change_password`.
   - **OPEN DECISION:** whether student self-registration is public or invite-only is unresolved.
5. **Dependencies:** Hard blockers: TEN-1/TEN-3, APP-4. Cross-module: calls `identity-access-service`'s `api` for the credential row, same pattern as STAFF-1.
6. **Backend impact:** `user-management` — `web`/`service`/`domain`/`repository` (tenant-owned Student entity).
7. **Frontend impact:** `app/(public)/` (tenant-scoped registration form).
8. **Database impact:** New table `student_profile(tenant_id, user_id FK, guardian_name, guardian_contact, school, grade, stream, status)` — separates auth (`tenant_user`) from domain profile.
9. **Security impact:** Tenant must be resolved via TEN-3's mechanism — never client-supplied — and a bad/unresolved subdomain must fail safely with no fallback. Primary tenant-boundary entry point for anonymous traffic.
10. **Tenant impact:** `tenant_id NOT NULL` + composite FK `(tenant_id, user_id) -> tenant_user(tenant_id, id)` (same-tenant enforcement) + index `(tenant_id, user_id)`.
11. **Payment impact:** None.
12. **Testing requirements:** Unit: registration DTO validation; `must_change_password` NOT set for self-registration. Testcontainers: resulting account tenant-scoped to the resolving tenant only. **Cross-tenant negative test:** registration via tenant A's subdomain never creates a tenant-B-visible row.
13. **Documentation requirements:** `docs/architecture` (student table entry, tenant-scoped uniqueness); `docs/api` for the registration endpoint.

### STU-2 — Student manual creation and bulk import

1. **Story ID:** STU-2
2. **Business outcome:** Lets Tenant Admin/Student Support onboard students who don't self-register, individually or via CSV bulk import.
3. **User role:** Tenant Admin (V/C/E/D), Student Support (V/C/E).
4. **Acceptance criteria:**
   - Given a manually or bulk-created student account, then it carries a `must_change_password` flag enforced at next login.
   - Given Student Support creates/edits a profile, then it succeeds; given Content Manager (`V` only) attempts the same, then it is rejected 403.
   - **OPEN DECISION:** bulk-import partial-failure behavior is unspecified.
5. **Dependencies:** Hard blocker: STU-1 (shares the same Student entity/creation path), RBAC-2.
6. **Backend impact:** `user-management` — `service` (CSV parsing, `must_change_password` flag), `web` (bulk-import endpoint).
7. **Frontend impact:** `app/(tenant-admin)/` — Bulk Import flow (Upload Control, Step Indicator), Student List Empty State.
8. **Database impact:** Additive to `student_profile`/`tenant_user` from STU-1. No dedicated import-batch table unless partial-failure reporting is decided.
9. **Security impact:** Manually/bulk-created accounts must carry a `must_change_password` flag — omitting this is a credential-hygiene gap. Requires role check: only Tenant Admin/Student Support may create.
10. **Tenant impact:** Same `student_profile`/`tenant_user` constraints as STU-1 — bulk import must not accept a caller-supplied `tenant_id` per row.
11. **Payment impact:** None.
12. **Testing requirements:** Unit: bulk-import row validation/CSV parsing edge cases. Testcontainers: manually/bulk-created accounts carry `must_change_password = true`. **Cross-tenant negative test:** a bulk-import run in tenant A's context cannot write or collide against tenant B rows. Playwright: bulk-import flow with valid + invalid rows, accessible progress/result reporting.
13. **Documentation requirements:** `docs/architecture` (bulk-import mechanism if it introduces an import-job table); `docs/api` for manual-create/bulk-import endpoints, including the undecided partial-failure response shape (flagged open).

### STU-3 — Student profile and cross-domain history views

1. **Story ID:** STU-3
2. **Business outcome:** Gives students, Tenant Admin, and Student Support a single view of a student's identity plus cross-domain history, read from each owning domain rather than duplicated.
3. **User role:** Student (own profile only), Tenant Admin, Student Support (V/C/E), other staff (V only), Teacher (read-only, course-scoped roster).
4. **Acceptance criteria:**
   - Given a student views their own dashboard/history pages, then all data is backend-filtered to their own tenant-scoped records — no student-selector or ID-based navigation exists.
   - Given history views are populated, then they are sourced via each owning domain's `api`, never duplicated into student-management's own tables.
   - Given a Teacher requests a roster view, then it is backend-pre-filtered to assigned courses only.
5. **Dependencies:** Hard blocker: STU-1. Soft dependencies (each history section only populates once its owning domain exists): ENR-1, PAY-3, ATT-1, EXM-3/EXM-5.
6. **Backend impact:** `user-management` — `service` composes narrow `api` reads from `enrollment-management`, `payment-management`, `attendance-management`, `exam-management`, `identity-access-service` — no cross-domain joins. Largest single-story fan-in of cross-module `api` dependencies in the MVP backlog.
7. **Frontend impact:** `app/(student)/` (own profile) and `app/(tenant-admin)/` (Student Detail full timeline) — Tabs component.
8. **Database impact:** None new — per the spec, history views must be read from each owning domain's `api`, never duplicated.
9. **Security impact:** A student must never view another student's profile/history by ID guessing. History views must apply each domain's own tenant filtering, not a denormalized cross-domain join that could bypass per-domain authZ.
10. **Tenant impact:** Each underlying read is already tenant-scoped via its own `TenantAwareRepository`; this story adds no new bypass.
11. **Payment impact:** None — read-only aggregation of already-append-only payment history; must not treat order/upload records as "paid" without a corresponding ledger entry.
12. **Testing requirements:** Unit: history-aggregation DTO shaping with mocked domain `api` interfaces. Testcontainers: history views call each owning domain's `api`; Student Support can edit while Content Manager is rejected 403. **Cross-tenant negative test (mandatory).** Treat as a capstone story to build last within Module 6.
13. **Documentation requirements:** `docs/architecture` (confirm history views are api-composed reads, not duplicated tables); `docs/api` for the student-detail/history endpoints.

---

## MODULE 7 — Teacher management

### TCH-1 — Teacher registration and approval

1. **Story ID:** TCH-1
2. **Business outcome:** Lets a teacher join a tenant through a controlled approval step before gaining course-assignment and login capability.
3. **User role:** Teacher (registers), Tenant Admin (approves), Course Coordinator (V/C/E, approval authority unclear).
4. **Acceptance criteria:**
   - Given an unapproved teacher, when they attempt to log in, then they either cannot log in or see no assigned courses — **OPEN DECISION:** exact UX unspecified.
   - **OPEN DECISION:** exact registration mechanism (self-register-then-approve vs. invited-only) is unspecified.
   - **OPEN DECISION:** whether Course Coordinator's `V/C/E` includes approval authority is unspecified — no explicit `A` column exists for Teachers.
5. **Dependencies:** Hard blockers: TEN-1/TEN-3, AUTH-1, RBAC-1/RBAC-2. Same `identity-access-service` credential-creation cross-module call as STU-1/STAFF-1.
6. **Backend impact:** `user-management` — `web`/`service`/`domain`/`repository`.
7. **Frontend impact:** `app/(tenant-admin)/` — Teacher List/Detail (approve/reject Icon Buttons with instance-specific `aria-label`s).
8. **Database impact:** New table `teacher_profile(tenant_id, user_id FK, approval_status CHECK enum PENDING/APPROVED/REJECTED, approved_by, approved_at)`.
9. **Security impact:** Unapproved teacher must not gain course-assignment or login capability. Approval-audit-logging is an open decision — flag given approval grants a portal + eventual course/content/exam-management capability. Teacher Assistant's PROVISIONAL boundary is a change-control-adjacent flag, not a normal implementation detail.
10. **Tenant impact:** `teacher_profile`: `tenant_id NOT NULL` + composite FK to `tenant_user(tenant_id, id)` + index `(tenant_id, approval_status)` for the approval-queue query shape.
11. **Payment impact:** None (commission/payout settings are explicitly Phase 2 — do not add columns for this in MVP).
12. **Testing requirements:** Unit: approval state-machine transition logic. Testcontainers: unapproved teacher's login/course-access behavior tested against whatever is actually implemented (flag gap if genuinely undefined). **Cross-tenant negative test.** Playwright: approve/reject icon controls carry specific `aria-label`s.
13. **Documentation requirements:** `docs/architecture` (teacher table, approval-state lifecycle); `docs/api` for registration/approval endpoints. **OPEN DECISION:** whether teacher approval requires an audit-log entry — flag for AUDIT-2.

### TCH-2 — Teacher profile and backend-filtered assigned-courses view

1. **Story ID:** TCH-2
2. **Business outcome:** Gives an approved teacher a reliable "My Courses" view limited strictly to their own assignments.
3. **User role:** Teacher, Teacher Assistant (PROVISIONAL).
4. **Acceptance criteria:**
   - Given a Teacher requests `My Courses`, then results are limited server-side to their own assignments, never client-side filtered.
   - Given a Teacher of tenant A, when they attempt to reach a course/roster/material belonging to tenant B, then the request is rejected 403/404.
   - Intra-tenant test: a Teacher cannot view/list courses or rosters outside their own assigned-course set, even within their own tenant.
5. **Dependencies:** Hard blocker: TCH-1. Hard blocker (forward reference): CRS-3 (Module 8) must exist for "assigned courses" to return anything — sequence CRS-3 before TCH-2 in actual sprint planning regardless of story-ID order.
6. **Backend impact:** `user-management` — `service` calls `course-management`'s `api` for a narrow "courses assigned to teacher X" read, never a join against `course-management`'s tables.
7. **Frontend impact:** `app/(teacher)/` — My Courses list (Course Card), Empty State ("no assigned courses yet").
8. **Database impact:** Depends on `course_teacher_assignment` table (introduced in CRS-3) — no new table here.
9. **Security impact:** "My Courses"/roster results must be limited server-side — never a full dataset filtered client-side. Requires an intra-tenant test, a distinct and often-missed authZ dimension from tenant isolation.
10. **Tenant impact:** `My Courses` query must filter by `(tenant_id, teacher_id)` on the assignment table.
11. **Payment impact:** None.
12. **Testing requirements:** Unit: assigned-course filtering DTO logic with mocked repository. Testcontainers: intra-tenant test proving Teacher X never receives Teacher Y's courses even within the same tenant. **Cross-tenant negative test (mandatory).** Playwright: "My Courses" reflects only assignments; empty state with contact-admin guidance.
13. **Documentation requirements:** `docs/architecture` (teacher-course-assignment data model); `docs/api` for the assigned-courses endpoint.

---

## MODULE 8 — Course management

### CRS-1 — Course Builder: category, pricing, enrollment rules, visibility

1. **Story ID:** CRS-1
2. **Business outcome:** Lets an approved teacher build a sellable/learnable course that later converts into storefront listings and enrollments.
3. **User role:** Teacher (creator), Teacher Assistant (create/edit, no publish/pricing — PROVISIONAL), Course Coordinator (V/C/E/A).
4. **Acceptance criteria:**
   - Given a Teacher completes the Course Builder, then the course is created in `DRAFT` status, not visible on the public storefront.
   - Given a tenant-scoped slug/name uniqueness conflict, then the backend returns 422 even if client-side Zod validation passed.
   - Given a Teacher attempts to assign a different teacher to their own course, then the action is rejected unless they hold the permitted authority (full reassignment flow is CRS-3).
   - Course Builder multi-step form is fully keyboard-navigable.
5. **Dependencies:** Hard blockers: TCH-1 (approved teacher must exist), TEN-1.
6. **Backend impact:** `course-management` — `web`/`service`/`domain`/`repository` (tenant-owned Course entity, tenant-scoped slug/name uniqueness).
7. **Frontend impact:** `app/(teacher)/` — Course Builder (Step Indicator, Form Field Wrapper, Select, Date Input for access-duration fields).
8. **Database impact:** New table `course` (id, tenant_id, teacher_id FK, category, subject/stream/grade/year, price NUMERIC, currency, enrollment_rules, access_duration, visibility status enum DRAFT/PRIVATE/PUBLIC, prerequisites). `price NUMERIC` per money-column rule even though this isn't a payment table — course price is source pricing PAY-1 reads.
9. **Security impact:** Course table is tenant-owned; DRAFT courses must never appear on the public storefront regardless of direct-URL/ID guessing. Pricing/enrollment-rule fields are payment-integrity-adjacent — any endpoint allowing a non-authorized role to write price fields is a financial-integrity issue.
10. **Tenant impact:** `tenant_id NOT NULL` + index `(tenant_id, status)` for storefront queries + `(tenant_id, teacher_id)` for "My Courses". `UNIQUE (tenant_id, slug)`.
11. **Payment impact:** None directly — course `price` is reference data PAY-1's `Order` snapshots at order-creation time, not itself a ledger/payment row.
12. **Testing requirements:** Unit: pricing math/validation, multi-step form DTO validation, enrollment-rule validation. Testcontainers: course persists tenant-scoped; `UNIQUE(tenant_id, slug)` enforced (same slug across tenants succeeds — doubles as cross-tenant-negative-of-a-negative test). **Cross-tenant negative test.** Playwright: multi-step form fully keyboard-navigable; backend 422 slug-conflict surfaced.
13. **Documentation requirements:** `docs/architecture` (course table data model); `docs/api` for Course Builder create/update endpoints.

### CRS-2 — Course publish/draft workflow + price-change audit

1. **Story ID:** CRS-2
2. **Business outcome:** Controls when a course becomes publicly visible/sellable and guarantees every price change on a live course is traceable.
3. **User role:** Teacher (publish, subject to tenant policy), Tenant Admin (approval if policy requires), Course Coordinator (V/C/E/A).
4. **Acceptance criteria:**
   - Given a course in `DRAFT`, then it never appears on the public storefront regardless of direct-URL guessing.
   - Given a price change on a published course, then exactly one audit log entry is written (actor/tenant/target/before/after) via a single non-bypassable code path — **mandatory per `.claude/rules/security.md`.**
   - Given tenant policy requires Tenant Admin approval before publish, then the course enters an under-review state — **OPEN DECISION:** no tenant-configuration mechanism for this policy is defined anywhere.
   - **OPEN DECISION:** whether Course Coordinator's approval authority requires a second approver for high-value/published courses is unspecified.
5. **Dependencies:** Hard blocker: CRS-1. Soft dependency: AUDIT-1/AUDIT-2 — cannot reach Definition of Done until Module 19's consumer exists, even though the event-publishing side is built now.
6. **Backend impact:** `course-management` — `service` (single non-bypassable price-change code path, publishes a domain event consumed by `audit-log-management`), `web` (publish/draft transition endpoint).
7. **Frontend impact:** `app/(teacher)/` and `app/(tenant-admin)/` — Status Chip (Draft/Published), Confirmation Dialog for publish.
8. **Database impact:** Additive to `course` (CRS-1) — status transition, no new table. Price-change history captured via AUDIT-2's event-driven audit log, not a separate `course_price_history` table.
9. **Security impact:** **Mandatory audit-log story** — price changes are explicitly on `security.md`'s canonical mandatory-audit list. Must be enforced via a single non-bypassable code path.
10. **Tenant impact:** Same `course` table/index as CRS-1. Publish action must confirm course's own `tenant_id` matches the acting Teacher/Tenant Admin's resolved tenant.
11. **Payment impact:** None on ledger, but this is the mandatory-audit story for price changes — actor/tenant/target course/before-after price all captured.
12. **Testing requirements:** Unit: publish-state-machine transition validation. Testcontainers: a `DRAFT` course never returned by the public storefront query even via direct ID guessing; a price change writes exactly one audit row via a single non-bypassable code path. **Cross-tenant negative test.** Playwright: publish/unpublish flow; anonymous browser cannot reach a `DRAFT` course via direct URL.
13. **Documentation requirements:** `docs/architecture` (publish-state machine, price-change-audit event); `docs/api` for publish/price-update endpoints. No ADR needed — `security.md` already covers this.

### CRS-3 — Teacher assignment/reassignment

1. **Story ID:** CRS-3
2. **Business outcome:** Ensures only an authorized actor, not the teacher themselves, controls which teacher owns a course.
3. **User role:** Tenant Admin, Course Coordinator (V/C/E, unclear if assignment authority is included).
4. **Acceptance criteria:**
   - Given a Teacher attempts to assign a different teacher to their own course, then the action is rejected — only Tenant Admin or a permitted staff sub-role may perform reassignment.
   - Given a course is reassigned, then the new teacher's `My Courses` reflects the change and the old teacher's no longer includes it, both backend-filtered.
   - Given a reassignment, then existing enrollment/payment/material history is preserved unchanged.
5. **Dependencies:** Hard blockers: CRS-1, TCH-1. This story is itself a hard blocker for TCH-2 despite the numbering — sequence CRS-3 before TCH-2 in sprint planning.
6. **Backend impact:** `course-management` — `service`/`web` (assignment mutation restricted to Tenant Admin/permitted staff sub-role, never the teacher themselves).
7. **Frontend impact:** `app/(tenant-admin)/` — Course Detail (teacher-assignment Select control).
8. **Database impact:** New table `course_teacher_assignment(tenant_id, course_id FK, teacher_id FK, assigned_by, assigned_at)` — recommended over a mutable `course.teacher_id` FK, for reassignment history/traceability.
9. **Security impact:** A teacher self-reassigning could be used to gain assignment (and thus content/roster/exam access) to a course they don't own — verify server-side that the acting actor's role, not the target teacher's own request, authorizes this.
10. **Tenant impact:** `tenant_id NOT NULL` + composite FK `(tenant_id, course_id)`, `(tenant_id, teacher_id)` (same-tenant enforcement) + index `(tenant_id, teacher_id)`.
11. **Payment impact:** None.
12. **Testing requirements:** Unit: authorization-decision logic. Testcontainers: reassignment persists tenant-scoped; a Teacher self-assigning is rejected 403 (Testcontainers-backed, not just unit-mocked). **Cross-tenant negative test.** Playwright: Teacher has no reachable reassignment control, and a direct API attempt fails server-side.
13. **Documentation requirements:** `docs/architecture` (confirm course-teacher FK/reassignment event documented); `docs/api` for the reassignment endpoint.

### CRS-4 — Public course listing/detail (storefront read)

1. **Story ID:** CRS-4
2. **Business outcome:** Lets anonymous prospective students browse a tenant's published courses on its branded public storefront.
3. **User role:** Anonymous / Public.
4. **Acceptance criteria:**
   - Given a course is `DRAFT`, then it never appears in the public listing/detail, regardless of direct-URL guessing.
   - Given a public storefront request, then tenant is resolved by subdomain/custom domain the same way as any other request.
   - Given the course preview (teacher-facing), then it renders through the same branding-consistent pipeline as the live storefront — never a separate preview-only rendering path.
5. **Dependencies:** Hard blockers: CRS-2 (only published courses eligible), TEN-3.
6. **Backend impact:** `course-management` — `web` (public, unauthenticated read endpoints, still tenant-scoped by resolved subdomain, not client input).
7. **Frontend impact:** `app/(public)/` — Course Listing/Detail, Course Card.
8. **Database impact:** None new — reads `course` (CRS-1) filtered to `status = PUBLISHED`.
9. **Security impact:** Verify the read path resolves tenant from TEN-3's mechanism and doesn't accept a client-supplied tenant/course-list filter that could enumerate another tenant's unpublished courses.
10. **Tenant impact:** Storefront reads are tenant-scoped-by-subdomain, not a cross-tenant aggregate — no platform-level bypass needed, distinct from Platform Admin cross-tenant reporting.
11. **Payment impact:** None.
12. **Testing requirements:** Unit: listing/detail response shaping (published fields only). Testcontainers: storefront query is tenant-scoped-by-subdomain. **Cross-tenant negative test (mandatory):** a visitor on tenant A's subdomain requesting a tenant B course id is rejected/not found. Playwright: two distinct tenant subdomains show two correctly-scoped catalogs.
13. **Documentation requirements:** `docs/architecture` (note the public-read composition and the Module C ownership gap); `docs/api` for the public listing/detail endpoints.

---

## MODULE 9 — Lessons and learning materials

### MAT-1 — Module & lesson structure within a course

1. **Story ID:** MAT-1
2. **Business outcome:** Lets a teacher organize a course's content into modules/lessons/sessions, the structural scaffold materials attach to.
3. **User role:** Teacher, Teacher Assistant (create/edit — PROVISIONAL), Tenant Admin (full oversight).
4. **Acceptance criteria:**
   - Given a Teacher creates modules/lessons within their own course, then the structure is tenant- and course-scoped.
   - Given drag-and-drop lesson/material ordering, then a keyboard-operable equivalent (explicit "move up/down" controls) exists — required, not optional.
   - Given a Teacher of tenant A, when they attempt to edit module/lesson structure belonging to tenant B, then the request is rejected 403/404.
5. **Dependencies:** Hard blocker: CRS-1 (course must exist to attach structure to).
6. **Backend impact:** `content-management` — `web`/`service`/`domain`/`repository` (tenant-owned, course-scoped).
7. **Frontend impact:** `app/(teacher)/` — Module & Lesson Editor, drag-and-drop ordering with keyboard equivalent.
8. **Database impact:** New tables `course_module(tenant_id, course_id FK, title, sequence)` and `lesson(tenant_id, module_id FK, title, sequence)`.
9. **Security impact:** Standard CRUD authZ (Institute Owner V/C/E/D, Content Manager V/C/E/D, Course Coordinator V only) — verify Course Coordinator's view-only status is enforced server-side, not just hidden in the UI.
10. **Tenant impact:** Both tables: `tenant_id NOT NULL` + composite FK to parent + index `(tenant_id, course_id)` / `(tenant_id, module_id)` for ordered-listing queries.
11. **Payment impact:** None.
12. **Testing requirements:** Unit: ordering/position-integrity validation. Testcontainers: rows persist tenant- and course-scoped; sequential reorder writes remain consistent. **Cross-tenant negative test.** Playwright: drag-and-drop lesson ordering has a working keyboard alternative — required accessibility E2E test.
13. **Documentation requirements:** `docs/architecture` (module/lesson table data model, ordering mechanism); `docs/api` for module/lesson CRUD endpoints.

### MAT-2 — Material upload (PDF/image/notes) with server-side validation

1. **Story ID:** MAT-2
2. **Business outcome:** Lets teachers/content managers attach protected learning materials with mandatory server-side validation, preventing malicious or oversized uploads from ever reaching storage.
3. **User role:** Teacher, Teacher Assistant (PROVISIONAL), Content Manager (V/C/E/D).
4. **Acceptance criteria:**
   - Given a valid PDF/image/notes upload by an authorized uploader, then it succeeds and is server-side validated (MIME/content sniffing, size, ownership) before acceptance.
   - Given an unauthorized uploader or a failed validation, then the upload is rejected with no partial write to storage.
   - Given no binary media is streamed/stored through the Spring Boot app itself, then uploads go through `integration-management`'s external object-storage `api`.
   - **OPEN DECISION:** bulk-upload partial-failure behavior is unspecified.
5. **Dependencies:** Hard blocker: MAT-1. Cross-module hard dependency: `integration-management`'s external object-storage `api` — no dedicated story exists for this; embedded in MAT-2's scope.
6. **Backend impact:** `content-management` — `service` (MIME/content-sniffing, size, ownership validation before any storage write), calling `integration-management`'s `api` rather than embedding a storage SDK directly.
7. **Frontend impact:** `app/(teacher)/` — Upload Control (drag-over/uploading/error states), File Preview.
8. **Database impact:** New table `material(id, tenant_id, lesson_id FK, uploaded_by, storage_object_key, mime_type, size_bytes, visibility, expiry_at nullable, created_at)`. No binary content in Postgres.
9. **Security impact:** Direct application of `.claude/rules/security.md` "Upload Validation" — server-side MIME/content-sniffing (not extension/declared Content-Type), max size enforced server-side, uploader ownership/permission check. Requires tests for oversized file, MIME-mismatched (renamed executable) file, and unauthorized-uploader rejection.
10. **Tenant impact:** `tenant_id NOT NULL` + composite FK `(tenant_id, lesson_id) -> lesson(tenant_id, id)` + index `(tenant_id, lesson_id)`.
11. **Payment impact:** None.
12. **Testing requirements:** Unit: MIME/content-sniffing, size-limit, filename-sanitization logic with fixture byte streams. Testcontainers: valid upload succeeds tenant/course-scoped; unauthorized uploader rejected with zero rows/zero storage writes; oversized/MIME-mismatched file rejected regardless of client-reported extension. **Cross-tenant negative test (mandatory).** Playwright: accessible upload label; failed upload surfaces `role="alert"`.
13. **Documentation requirements:** `docs/architecture` (confirm materials never persist binary through the app tier); `docs/api` for the upload endpoint.

### MAT-3 — Material organization and visibility enforcement at fetch time

1. **Story ID:** MAT-3
2. **Business outcome:** Ensures a student only ever sees materials explicitly attached and visible to them, with every fetch independently authorization-checked.
3. **User role:** Student (consumer), Teacher/Content Manager (organize/set visibility), Tenant Admin (oversight).
4. **Acceptance criteria:**
   - Given a course's material list on the student side, then it reflects only explicitly attached materials, with visibility enforced at fetch time, not just hidden in navigation.
   - Given a student from tenant A (or a different course, same tenant) fetches a material ID by guessing, then the request is rejected 403/404, not silently empty.
   - Given a student requests another student's protected document (same tenant), then the request is rejected.
   - **OPEN DECISION:** no concrete visibility taxonomy is defined anywhere — recommend a minimal CHECK-constrained set now (e.g. VISIBLE/HIDDEN) rather than blocking on the decision.
5. **Dependencies:** Hard blocker: MAT-2. Hard blocker (forward reference): **ENR-1** (Module 12) — fetch-time visibility enforcement requires checking the student's enrollment/access state, which doesn't exist until three modules later; this story will likely ship with an interim access check and be revisited once ENR-1 lands.
6. **Backend impact:** `content-management` — `service` (authorization check on every fetch, confirming tenant + enrollment + role, never a direct predictable URL).
7. **Frontend impact:** `app/(student)/` — Lesson/Material View, Course Card `Locked` state for access-denied.
8. **Database impact:** Additive to `material` (MAT-2) — `sequence`/ordering column, `visibility` enum values. No new table.
9. **Security impact:** Direct protected-content-access story — uploaded material must never be reachable via a direct, predictable URL/ID. **Mandatory negative tests:** cross-tenant and cross-student ID-guessing on the fetch endpoint. **Mandatory audit for deletion only** (material/course content deletions on the canonical list; creation/edit is not).
10. **Tenant impact:** Same `material` table/index — every fetch-by-id must re-verify `tenant_id` match plus enrollment/ownership.
11. **Payment impact:** None.
12. **Testing requirements:** Unit: visibility-rule evaluation logic. Testcontainers: student-side material list reflects only attached materials, enforced server-side at fetch time; a student from tenant A guessing/incrementing a material ID is rejected 403/404. **Cross-tenant/enumeration negative test (mandatory per security.md).** Audit: material deletion writes exactly one audit row.
13. **Documentation requirements:** `docs/architecture` (material-visibility enforcement mechanism); `docs/api` for the material-fetch endpoint (signed-URL/token issuance contract).

---

## MODULE 10 — Order and payment foundation

### PAY-1 — Order creation (server-side, tenant-aware)

1. **Story ID:** PAY-1
2. **Business outcome:** Creates the tenant-aware, server-authoritative record of a student's purchase intent — but explicitly not itself activation evidence.
3. **User role:** Student (initiates), Finance Staff/Tenant Admin (V/C/E/A).
4. **Acceptance criteria:**
   - Given a student selects "Enroll," then the backend creates an `Order` server-side, tenant-aware, with `tenant_id` resolved from trusted context — never from a request body/query param.
   - Given `Order` schema, then it enforces `tenant_id NOT NULL` with FK to tenant and a composite index leading with `tenant_id`.
   - Given an `Order` is `PLACED`/`PENDING`, then it is explicitly not activation evidence — enrollment activation code must never read order state.
5. **Dependencies:** Hard blockers: CRS-1/CRS-4 (course must exist/be published), AUTH-1, APP-4.
6. **Backend impact:** `payment-management` — `web`/`service`/`domain`/`repository` (tenant-owned `Order`).
7. **Frontend impact:** `app/(student)/` — Checkout entry point (note: `docs/ui-ux/user-journeys.md` references a "Checkout" screen not enumerated in `screen-map.md` — flagged inconsistency, not something to silently resolve).
8. **Database impact:** New table `order` (id, tenant_id, student_id FK, course_id FK, amount NUMERIC, currency, status CHECK enum, created_at). Snapshots `course.price` at order time.
9. **Security impact:** `tenant_id` on every `Order` row must be resolved from trusted authenticated context — never from request body/query param, even though the student "initiates" the order. An `Order` being `PLACED`/`PENDING` must never be treated as enrollment-activation evidence.
10. **Tenant impact:** `tenant_id NOT NULL` + composite FK `(tenant_id, student_id)`, `(tenant_id, course_id)` (same-tenant enforcement) + index `(tenant_id, student_id)` and `(tenant_id, status, created_at)`.
11. **Payment impact:** Per `payments.md` §1: `Order` state is never activation evidence. Money column `NUMERIC`, never float.
12. **Testing requirements:** Unit: order pricing/DTO validation. Testcontainers: server-resolved tenant/price wins over any client-supplied value. **Cross-tenant negative test.** Money-column test: `NUMERIC` precision preserved through create/read round-trip. Playwright: checkout creates order server-side with no student-editable price field.
13. **Documentation requirements:** `docs/architecture/payment-ledger.md` (confirm Order/Payment data model matches); `docs/api` for the order-creation endpoint.

### PAY-2 — Gateway payment integration adapter + webhook confirmation

1. **Story ID:** PAY-2
2. **Business outcome:** Confirms student payments through a verified, server-to-server gateway webhook rather than trusting anything the browser reports.
3. **User role:** Student (initiates payment), `integration-management` (owns gateway credentials/webhook verification).
4. **Acceptance criteria:**
   - Given a gateway payment, then the frontend shows a loading/"awaiting confirmation" state on redirect return and does NOT mark enrollment active on redirect return.
   - Given the gateway sends a verified webhook, then the backend persists a `Payment` row transitioning to `CONFIRMED`, and enrollment activation happens in the same transaction.
   - Given a duplicate webhook delivery, then the outcome is idempotent — same ledger/enrollment state on retry.
   - Given payment fails/is rejected, then Payment History shows a failed/rejected state (`role="alert"`) with a retry path; access remains locked.
   - **OPEN DECISION:** no specific payment gateway is named anywhere — do not invent a vendor; build against `integration-management`'s `api` contract only.
5. **Dependencies:** Hard blocker: PAY-1. Hard blocker (forward reference): **ENR-1** (Module 12) — payment confirmation and enrollment activation must commit in one transaction, so this story's completion is gated on `enrollment-management`'s activation `api` existing; design the `api` contract concurrently with PAY-2, not after. Cross-module hard dependency: `integration-management` (embedded here for webhook signature verification).
6. **Backend impact:** `payment-management` (`service`: webhook handling, `Payment` state machine with DB CHECK constraint) synchronously calling `enrollment-management`'s `api` inside the same transaction — the highest-integrity cross-module coupling in the MVP.
7. **Frontend impact:** `app/(student)/` — "awaiting confirmation" loading state, Alert/Toast for confirmed/failed/pending states.
8. **Database impact:** New table `payment` (id, tenant_id, order_id FK, amount NUMERIC, currency, status CHECK enum PENDING/CONFIRMED/REJECTED/REFUNDED, gateway_reference, confirmed_at nullable). `CHECK (amount > 0)`.
9. **Security impact:** Highest financial-integrity story in the payment cluster. Webhook must be verified (signature/server-to-server) before persisting `CONFIRMED` — frontend must never mark enrollment active on gateway-redirect return alone. **Top risk register item** (see risk register #1, #5). **Mandatory audit-log:** payment approvals/rejections.
10. **Tenant impact:** `tenant_id NOT NULL` + composite FK `(tenant_id, order_id) -> order(tenant_id, id)` + index `(tenant_id, status, created_at)`.
11. **Payment impact:** Core payments-cluster story. Append-only; `status` DB CHECK constraint; idempotency; transaction boundary spanning payment confirmation + enrollment activation atomically, never spanning the outbound gateway call itself.
12. **Testing requirements:** Unit: webhook signature-verification logic with fixture payloads. Testcontainers: verified webhook transitions `Payment` to `CONFIRMED` and activates enrollment in the same transaction (simulate mid-transaction failure, confirm rollback). **Idempotency test (mandatory) [Matrix].** **Cross-tenant negative test (mandatory).** Playwright: redirect-return loading state, never marks enrollment active on redirect alone.
13. **Documentation requirements:** `docs/architecture/payment-ledger.md` (confirm two-confirmation-path model); `docs/architecture/integration-architecture.md` (adapter pattern); `docs/api` for the webhook and payment-status endpoints.

### PAY-3 — Payment ledger entry (append-only) + Payment History UI

1. **Story ID:** PAY-3
2. **Business outcome:** Gives students and staff a trustworthy, append-only payment record, and ensures the payment dashboard is always ledger-derived, never a mutable "paid" flag.
3. **User role:** Student (Payment History), Finance Staff/Tenant Admin (Dashboard, V/C/E/A), Read-only Auditor (V), Platform Admin (cross-tenant, oversight only).
4. **Acceptance criteria:**
   - Given a confirmed payment, then an append-only ledger entry is written, carrying a traceable link to tenant and the order/payment that produced it.
   - Given the admin payment dashboard, then it is derived from ledger entries + slip state, not from the order or raw upload record — a "paid" display with no ledger entry is a bug.
   - Empty state: "no payments have been made yet" explicitly distinguished from "no payments match the selected date range/filter."
5. **Dependencies:** Hard blockers: PAY-1, PAY-2 (or SLIP-3, whichever path confirms first).
6. **Backend impact:** `ledger-settlement-management` — new `domain`/`repository` (no `delete`/`deleteById` exposed anywhere), `api` (read for Payment History, dashboards). `payment-management` provides the confirmation events this domain appends from.
7. **Frontend impact:** `app/(student)/` (Payment History) and `app/(tenant-admin)/` (Payment Dashboard) — Status Chip, Empty State.
8. **Database impact:** New table `ledger_entry` (id, tenant_id, payment_id FK/order_id FK, entry_type CHECK enum, amount NUMERIC with sign convention, reverses_entry_id FK nullable self-reference). Every entry immutable, no delete/update repository methods.
9. **Security impact:** Ledger entries are append-only — no `UPDATE`/`DELETE` repository method may exist for terminal payment/ledger rows. Money columns `NUMERIC`, never floating point. Cross-tenant aggregation (even for Platform Admin) is a reporting-layer concern only, never a query-layer default.
10. **Tenant impact:** `tenant_id NOT NULL` + composite FK `(tenant_id, payment_id)` + index `(tenant_id, created_at)` and `(tenant_id, payment_id)`. No orphaned ledger entry — FK NOT NULL.
11. **Payment impact:** This IS the append-only ledger story. Ledger entry types are change-controlled — adding/removing a type needs an ADR.
12. **Testing requirements:** Unit: ledger-entry-type enum validation. Testcontainers: no repository method exposes update/delete on ledger rows (structural test); every entry carries a traceable link to tenant + order/payment. **Cross-tenant negative test.** Playwright: distinct empty states; status badges pair color with text/icon; async status via `aria-live`/`role="alert"`.
13. **Documentation requirements:** `docs/architecture/database-architecture.md` (confirm ledger append-only schema matches §3); `docs/api` for Payment History/Dashboard read endpoints.

### PAY-4 — Refund handling (new row, linked to original)

1. **Story ID:** PAY-4
2. **Business outcome:** Lets Finance Staff/Tenant Admin process refunds without ever mutating or deleting the original payment record.
3. **User role:** Finance Staff, Tenant Admin (V/C/E/A).
4. **Acceptance criteria:**
   - Given a refund is processed, then a new `payment_refund` row is created linked to the original payment ID; the original terminal-state row is never mutated.
   - Given a payment already terminal, then no `UPDATE` path exists on that row.
   - **OPEN DECISION:** refund window/eligibility policy is unresolved — this story implements the mechanism, not a specific policy.
5. **Dependencies:** Hard blockers: PAY-2/PAY-3 (a confirmed payment + ledger entry must exist to refund against). Soft dependency: AUDIT-1/2.
6. **Backend impact:** `payment-management`/`ledger-settlement-management` — new `payment_refund` row + reversal ledger entry (`reverses_entry_id`); original terminal-state row never mutated.
7. **Frontend impact:** `app/(tenant-admin)/` — Refunds screen, Confirmation Dialog (destructive-severity styling).
8. **Database impact:** New table `payment_refund(id, tenant_id, original_payment_id FK, amount NUMERIC, reason, created_at)` plus a corresponding `ledger_entry` row with `reverses_entry_id` set.
9. **Security impact:** Refund must be a new row referencing the original — never a mutation. Refund authorization restricted to `V/C/E/A` roles; verify no student self-service refund-trigger path exists. Change-controlled-adjacent: any new refund trigger mechanism beyond the two approved paths needs an ADR.
10. **Tenant impact:** `tenant_id NOT NULL` + composite FK `(tenant_id, original_payment_id) -> payment(tenant_id, id)` + index `(tenant_id, original_payment_id)`.
11. **Payment impact:** Governed by `payments.md` §1/§4: refund is a new row; original stays untouched; refund's ledger entry references `reverses_entry_id`. `CHECK (amount > 0)`.
12. **Testing requirements:** Unit: refund-amount validation (cannot exceed original). Testcontainers: refund creates a new row linked via `reverses_entry_id`; original row provably unchanged before/after. **Idempotency test (mandatory) [Matrix].** **Cross-tenant negative test.** Audit: exactly one audit row per refund action.
13. **Documentation requirements:** `docs/architecture/payment-ledger.md` (confirm refund-as-new-row pattern); `docs/api` for the refund endpoint.

---

## MODULE 11 — Manual payment slip management

### SLIP-1 — Slip upload (student) + server-side upload validation

1. **Story ID:** SLIP-1
2. **Business outcome:** Supports out-of-band payment methods by letting a student submit payment evidence for human review, without ever granting access on the upload alone.
3. **User role:** Student.
4. **Acceptance criteria:**
   - Given a student uploads a slip, then the backend validates server-side (MIME/content sniffing, size, ownership) before acceptance, rejecting on failure with no partial write.
   - Given a slip is uploaded, then it enters `SUBMITTED`, reflected as "Submitted — under review" (distinct from "paid"); course access remains locked.
   - Given `SUBMITTED` is reached, then it never by itself triggers enrollment activation.
5. **Dependencies:** Hard blocker: PAY-1 (order must exist). Cross-module hard dependency: `integration-management`'s object-storage `api` (same gap as MAT-2, embedded here).
6. **Backend impact:** `payment-management` — `web`/`service` (MIME/size/ownership validation, no partial write on failure), `domain` (`SUBMITTED` initial state).
7. **Frontend impact:** `app/(student)/` — Payment Slip Upload (Upload Control, accessible accepted-format/size label).
8. **Database impact:** New table `payment_slip` (id, tenant_id, order_id FK, student_id FK, storage_object_key, reference_number, status CHECK enum SUBMITTED/UNDER_REVIEW/APPROVED/REJECTED, submitted_at).
9. **Security impact:** Same upload-validation rules as MAT-2 — student may upload a slip only for their own order/payment. Slip file must never be reachable via a direct predictable URL. `SUBMITTED` must never itself trigger activation — verify this boundary is structural, not just a default UI state.
10. **Tenant impact:** `tenant_id NOT NULL` + composite FK `(tenant_id, order_id)`, `(tenant_id, student_id)` + index `(tenant_id, status)` for the review queue.
11. **Payment impact:** `SUBMITTED`/`UNDER_REVIEW` slips must never be activation evidence. Upload validation is server-side per security.md — no partial write on failure.
12. **Testing requirements:** Unit: MIME/size/reference-number-format validation with fixture byte streams. Testcontainers: valid upload persists `SUBMITTED` tenant/order-scoped; rejection on MIME/size/ownership failure leaves zero rows/storage writes. **Cross-tenant negative test.** Playwright: accessible upload label; `SUBMITTED` shown distinctly from "paid."
13. **Documentation requirements:** `docs/architecture/payment-ledger.md` (confirm slip state machine's `SUBMITTED` entry point); `docs/api` for the slip-upload endpoint.

### SLIP-2 — Duplicate reference/image-hash check (tenant-scoped, exact-match)

1. **Story ID:** SLIP-2
2. **Business outcome:** Automatically screens every submitted slip for exact-match duplicates within the same tenant before a human reviewer sees it.
3. **User role:** System/backend (runs automatically); reviewed by Finance Staff/Institute Owner.
4. **Acceptance criteria:**
   - Given a slip is submitted, then duplicate-reference and duplicate-image-hash checks both run server-side, scoped to the requesting tenant's slips only.
   - No approval code path exists that skips either check.
   - Given a flagged slip, then it is auto-flagged but never auto-rejected.
   - Given checks are re-run later, then a new flag/result record is added — never clearing a prior flag.
   - MVP scope is exact-match only; OCR-based reference extraction is Phase 3 — do not build OCR-dependent logic here.
5. **Dependencies:** Hard blocker: SLIP-1.
6. **Backend impact:** `payment-management` (Payment Slip Intelligence sub-module) — `service` (structurally tenant-filtered duplicate queries — must use `TenantAwareRepository`, not an incidental `WHERE`), `domain` (additive, never-cleared flag records).
7. **Frontend impact:** Backend-only for the checks themselves; surfaces read-only flags consumed by SLIP-3's UI.
8. **Database impact:** New table `payment_slip_flag` (id, tenant_id, slip_id FK, flag_type CHECK enum, detected_at) — additive, never overwritten. Requires `reference_number` and `image_hash` columns on `payment_slip`.
9. **Security impact:** Duplicate checks are **explicitly tenant-scoped** per `.claude/rules/payments.md` §3 — a reference number colliding across two tenants is not a duplicate; the query must use structural tenant filtering. Mandatory gates that must run and pass (or be validly overridden) before `APPROVED`.
10. **Tenant impact:** `payment_slip_flag`: `tenant_id NOT NULL` + composite FK `(tenant_id, slip_id)` + **critical** index `(tenant_id, reference_number)` and `(tenant_id, image_hash)` on `payment_slip` itself.
11. **Payment impact:** Mandatory gate per `payments.md` §3-4. Auto-flag allowed, auto-reject never allowed.
12. **Testing requirements:** Unit: exact-match hash/reference comparison as pure logic. Testcontainers: same reference/hash within the same tenant IS flagged; same reference/hash across two different tenants is explicitly NOT flagged **[Matrix: manual payment slip duplicate-detection, mandatory, dual-direction test explicitly named in spec 25]**; re-running checks adds a new flag row, never clears a prior one.
13. **Documentation requirements:** `docs/architecture/payment-ledger.md` (confirm §4 Payment Slip Intelligence Module matches); `docs/api` for the flag-result read surface consumed by SLIP-3.

### SLIP-3 — Manual Slip Review Queue + approve/reject

1. **Story ID:** SLIP-3
2. **Business outcome:** Gives Finance Staff/Institute Owner a queue of pending slips with backend-supplied flag context to approve/reject, with approval atomically activating enrollment.
3. **User role:** Finance Staff, Institute Owner (both hold `A`); Student Support (V only); Read-only Auditor (V).
4. **Acceptance criteria:**
   - Given a reviewer approves a slip, then approval and enrollment activation happen in one transaction.
   - Given the slip state machine, then transitions are one-directional; no code path allows `APPROVED -> SUBMITTED`.
   - Given a rejection, then the slip transitions to `REJECTED`, the student is notified asynchronously, and enrollment stays inactive.
   - Approving twice (idempotency) does not double-activate enrollment or double-write ledger entries.
   - **OPEN DECISION:** whether Finance Staff or Institute Owner (or both, with what precedence) is the correct approver when both are eligible is unresolved.
5. **Dependencies:** Hard blockers: SLIP-2, RBAC-2. Hard blocker (forward reference): **ENR-1** — same atomic-transaction requirement as PAY-2.
6. **Backend impact:** `payment-management` — `web`/`service` (state machine, one-directional), synchronously calling `enrollment-management`'s `api` on approval.
7. **Frontend impact:** `app/(tenant-admin)/` — Manual Slip Review Queue (Table), Slip Detail (Duplicate Flagged Status Chip).
8. **Database impact:** Additive to `payment_slip` (SLIP-1) — `reviewer_id FK`, `reviewed_at`. No new table. On `APPROVED`, this is also the trigger point for ENR-1's atomic activation transaction.
9. **Security impact:** Only Finance Staff/Institute Owner may transition — Student Support and Read-only Auditor must be rejected server-side even if UI is reachable. **Mandatory audit:** approve/reject requires reviewer identity + timestamp at minimum.
10. **Tenant impact:** Same `payment_slip` table; review queue query uses `(tenant_id, status)` index from SLIP-1.
11. **Payment impact:** Approve/reject is a mandatory-audit action. Approval + enrollment activation must be one transaction. Approving twice must be idempotent.
12. **Testing requirements:** Unit: state-machine transition validation. Testcontainers: approval + activation in one transaction (simulated mid-failure rollback test). **Idempotency test (mandatory) [Matrix].** **Cross-tenant negative test.** Playwright: reviewer approve/reject flow; distinct empty states.
13. **Documentation requirements:** `docs/architecture/enrollment-access.md` (confirm the atomic slip-approval-to-activation transaction matches §5); `docs/api` for the review-queue/approve/reject endpoints.

### SLIP-4 — Override-with-reason + mandatory audit log

1. **Story ID:** SLIP-4
2. **Business outcome:** Lets a reviewer approve a flagged slip when they judge the flag a false positive, while making every such override fully accountable — the single most explicitly repeated requirement in the payment rules.
3. **User role:** Finance Staff, Institute Owner (only roles holding `A`).
4. **Acceptance criteria:**
   - Given a flagged slip, then the reviewer must supply an override reason before "Approve anyway" is enabled; the UI never allows approval without a reason present.
   - Given an override with no recorded reason, then the backend rejects it before any state change or audit row is written.
   - Given a valid override, then an audit log entry is written containing reviewer identity, tenant, slip/reference ID, the flag(s) overridden, a reason, and a timestamp — same transaction as the override.
5. **Dependencies:** Hard blocker: SLIP-3. Hard blocker (forward reference): **AUDIT-1** (Module 19) — the override-with-reason gate is defined as unimplementable without a working audit-write path, but `audit-log-management`'s schema is 8 modules away by story-ID order. **This is the sharpest forward-dependency in the entire backlog** — this module cannot reach Definition-of-Done without pulling AUDIT-1 forward (see release plan).
6. **Backend impact:** `payment-management` — `service` (override write path is the *same* code path that produces the audit entry, no separate/skippable call), publishing to `audit-log-management`.
7. **Frontend impact:** `app/(tenant-admin)/` — "Approve anyway" control disabled until reason field is populated (client-side UX convenience only; backend independently rejects a reasonless override).
8. **Database impact:** Additive to `payment_slip_flag` (SLIP-2) — override recorded via AUDIT-1/2's `audit_log` table, not a separate override table, to keep one audit mechanism.
9. **Security impact:** Called out as "the single most explicitly, repeatedly-stated audit requirement" in the ruleset. An override with no recorded reason must be rejected by the system, not merely discouraged via UI. **Top risk register item (#4).**
10. **Tenant impact:** `audit_log` (from AUDIT-1) already `tenant_id NOT NULL`-scoped; no new bypass needed here.
11. **Payment impact:** This is a schema+service invariant, not UI-only — recommend a `NOT NULL reason` column on the audit-log override record enforced at insert, with the approval write and the audit write in one transaction.
12. **Testing requirements:** Unit: override-reason presence validation (empty/whitespace rejected). Testcontainers: override with no reason rejected before any state change or audit row written (zero side effects on rejection); valid override writes exactly one audit row with all fields NOT NULL; a flagged slip is never auto-rejected without human action. **Cross-tenant negative test.** Playwright: keyboard-operable "Approve anyway," direct API call with empty reason still fails server-side.
13. **Documentation requirements:** `docs/architecture` (audit-event schema for slip-override, confirm matches `.claude/rules/payments.md` §3); `docs/api` for the override-approve endpoint (reason field required).

---

## MODULE 12 — Enrollment and course access

### ENR-1 — Enrollment activation (atomic, tied to confirmed payment/approved slip)

1. **Story ID:** ENR-1
2. **Business outcome:** Provably ties "access granted" to "money confirmed" in a single atomic transaction with a traceable FK — the platform's single most security/finance-critical junction, per the change-controlled enrollment activation rules.
3. **User role:** Student (receives access); `enrollment-management` backend is sole activation authority.
4. **Acceptance criteria:**
   - Given no persisted `CONFIRMED` payment or `APPROVED` slip exists, then no code path can activate enrollment, even given a plausible-looking request payload.
   - Given a `CONFIRMED` payment is persisted, then activation and payment confirmation commit together in one transaction.
   - Given an enrollment is active, then querying its activation source returns a specific, non-null FK to the confirming payment/slip row — never a bare boolean flag.
   - Given `enrollment.course_id -> course.id`, then it is enforced same-tenant via composite FK, not service-layer check alone.
5. **Dependencies:** Hard blocker: PAY-1. Mutually with PAY-2/SLIP-3 — this story's `api` contract must be designed and stubbed alongside them, not strictly after, despite module numbering. Really the shared foundation for the Modules 10-12 cluster — plan as one coordinated slice.
6. **Backend impact:** `enrollment-management` — `web`/`service`/`domain` (`activating_payment_id` FK, NOT NULL, exactly one of payment/slip-evidence path populated), `repository`. Consumes `payment-management`/`ledger-settlement-management` `api`s read-only, never their repositories.
7. **Frontend impact:** Backend-only directly; surfaced through SDASH-2/CRS-4 access states.
8. **Database impact:** New table `enrollment` (id, tenant_id, student_id FK, course_id FK, activating_payment_id UUID REFERENCES payment(id) NULLABLE, activating_slip_id UUID REFERENCES payment_slip(id) NULLABLE, status, activated_at) with a CHECK constraint enforcing exactly one of the two activation-source columns is non-null.
9. **Security impact:** The platform's single most security/finance-critical junction. No code path may accept a client-reported "payment succeeded" payload as activation evidence — structurally impossible, not merely untested. **This is a named change-controlled area** — any new activation trigger requires an ADR before implementation. **Top risk register item (#1).**
10. **Tenant impact:** `tenant_id NOT NULL` + composite FK `(tenant_id, course_id) -> course(tenant_id, id)` + composite FK `(tenant_id, student_id)` + index `(tenant_id, student_id)` and `(tenant_id, course_id)`. No platform-admin cross-tenant bypass for enrollment records.
11. **Payment impact:** This is the schema-enforced core of enrollment-activation rules. Activation transaction spans payment/slip state change + this `enrollment` insert atomically.
12. **Testing requirements:** Unit: activation-eligibility pure-logic check. Testcontainers: no persisted `CONFIRMED` payment/`APPROVED` slip means no code path can activate enrollment even given a forged request. **[Matrix: enrollment activation — this story IS that row.]** **Cross-tenant negative test.** Enumerate all activation call sites and assert each traces back to `CONFIRMED`/`APPROVED` evidence.
13. **Documentation requirements:** `docs/architecture/enrollment-access.md` already describes this baseline — confirm implementation matches exactly. New ADR required only if a deviation is introduced. `docs/api` for the enrollment-status-read endpoint.

### ENR-2 — Course-level expiry + access-expired state

1. **Story ID:** ENR-2
2. **Business outcome:** Automatically lapses a student's course access once its payment-covered window ends, surfacing a clear "access expired" state and setting up the reactivation funnel.
3. **User role:** Student.
4. **Acceptance criteria:**
   - Given a course's access window lapses, then the student sees a distinct "access expired" state (not a generic error, not permission-denied) with a Reactivate CTA.
   - Given expiry occurs, then it is recorded as `enrollment-management`'s own event, never a mutation of the originating payment record.
   - MVP scope is course-level expiry only; session/material/video expiry and the full expiry rules engine are explicitly Phase 2 — do not build those in.
   - **OPEN DECISION:** exact grace period length(s) and expiry-rules-engine precedence order are unresolved — do not assume they mirror device-limit precedence.
5. **Dependencies:** Hard blocker: ENR-1.
6. **Backend impact:** `enrollment-management` — `service` (expiry evaluation reading payment coverage period via `api`, not duplicating payment data locally), `domain` (expiry event/state row, never mutating the original payment).
7. **Frontend impact:** `app/(student)/` — distinct "access expired" state with Reactivate CTA.
8. **Database impact:** Additive to `enrollment` (ENR-1) — `access_expires_at` column. New table `enrollment_expiry_event(id, tenant_id, enrollment_id FK, event_type, occurred_at)` recording the transition as its own append-only-style event.
9. **Security impact:** Expiry processing must never delete/mutate prior payment/ledger history — expiry is a state change on enrollment/access recorded as its own event.
10. **Tenant impact:** `enrollment_expiry_event`: `tenant_id NOT NULL` + composite FK `(tenant_id, enrollment_id)` + index `(tenant_id, enrollment_id)`.
11. **Payment impact:** Expiry must never mutate `payment`/`ledger_entry` rows — purely an access-state event referencing the enrollment.
12. **Testing requirements:** Unit: expiry-window calculation logic. Testcontainers: expiry records a state transition without mutating the original payment row; lapsed access returns a distinct "access expired" state at the real fetch/access endpoint. **Cross-tenant negative test (mandatory, explicit in spec 18).** Audit: bulk expiry extension writes exactly one audit row per action.
13. **Documentation requirements:** `docs/architecture/enrollment-access.md` §6/§7 (confirm course-expiry-as-event model matches); `docs/api` for the expiry-state-read endpoint.

### ENR-3 — Reactivation request and admin approval

1. **Story ID:** ENR-3
2. **Business outcome:** Lets a student whose access has expired request reinstatement, always through a brand-new payment/order and (per tenant policy) an admin approval step.
3. **User role:** Student (requests), Finance Staff / Tenant Admin (approves — precedence unresolved).
4. **Acceptance criteria:**
   - Given a reactivation, then it always produces a new order/payment/ledger entry; the original expired payment record is untouched.
   - Given a reactivation request is submitted but never approved, then access stays expired — no partial activation.
   - Given reactivation approval, then it is audit-logged (actor, tenant, scope, before/after) — mandatory per `.claude/rules/security.md`.
   - **OPEN DECISION:** whether reactivation always requires Tenant Admin approval, and whether Finance Staff or Institute Owner is the correct approver, are both unresolved.
5. **Dependencies:** Hard blockers: ENR-2, PAY-1 (reactivation always creates a new order/payment). Soft dependency: AUDIT-1/2.
6. **Backend impact:** `enrollment-management` — `web`/`service` (admin-approval step, audit-logged), never resurrecting the old payment row.
7. **Frontend impact:** `app/(student)/` (reactivation request) and `app/(tenant-admin)/` (Reactivation Approvals queue).
8. **Database impact:** New table `reactivation_request(id, tenant_id, enrollment_id FK, requested_by, status CHECK enum, reviewed_by nullable, reviewed_at nullable, new_order_id FK nullable-until-fulfilled)`.
9. **Security impact:** Reactivation must always create a new order/payment/ledger entry — never resurrect or extend the old payment record's dates. **Mandatory audit:** both "access/expiry extensions" and "reactivation approvals" are on `security.md`'s canonical list.
10. **Tenant impact:** `tenant_id NOT NULL` + composite FK `(tenant_id, enrollment_id)` + index `(tenant_id, status)` for the approvals queue.
11. **Payment impact:** Reactivation must produce a new payment/order/ledger entry — the prior expired payment row stays untouched; no "extend old payment dates" shortcut.
12. **Testing requirements:** Unit: reactivation-request validation (must reference a new order, never resurrect the old payment). Testcontainers: reactivation always produces a NEW order/payment/ledger entry; original expired payment row provably untouched (append-only test). **[Matrix: enrollment activation — second activation path.]** **Cross-tenant negative test.**
13. **Documentation requirements:** `docs/architecture/enrollment-access.md` §6 (confirm reactivation-as-new-order model); `docs/api` for the reactivation-request/approval endpoints.

---

## MODULE 13 — Student dashboard

### SDASH-1 — Student home/overview

1. **Story ID:** SDASH-1
2. **Business outcome:** Gives a student a single, backend-filtered landing view of their own enrollments/status/notifications.
3. **User role:** Student.
4. **Acceptance criteria:**
   - Given a student logs in, then their overview shows only their own tenant-scoped records — no student-selector or ID-based cross-student navigation exists anywhere.
   - Given this is a consumer-style surface, then it is mobile-first: single-column stacking below sm/md, card-based layout.
   - Given no active enrollments exist, then the empty state explains why with a CTA to the course catalog.
5. **Dependencies:** Hard blockers: AUTH-2 (session), ENR-1 (enrollment data to show). Soft dependency: PAY-3, ATT-1, EXM-5 for a fuller overview.
6. **Backend impact:** No new domain — aggregates existing `api` reads (`enrollment-management`, `course-management`).
7. **Frontend impact:** `app/(student)/` — Statistic Card / Course Card composition, Skeleton loading states.
8. **Database impact:** None new — reads across `enrollment`, `payment`, `order` (all already tenant-scoped from prior stories).
9. **Security impact:** All rendered data must be backend-filtered to the authenticated student's own tenant-scoped records. Flag only if this story introduces its own aggregate/cross-domain query rather than reading from each owning domain's already-scoped API.
10. **Tenant impact:** Pure read composition through existing `TenantAwareRepository`-scoped finders; no new bypass.
11. **Payment impact:** None — read-only summary, must reflect ledger-derived payment state, not raw order state.
12. **Testing requirements:** Unit: dashboard-aggregation DTO shaping with mocked domain `api` calls. Testcontainers: overview data assembled via each owning domain's `api`, scoped to the authenticated student — two-student/two-tenant fixture confirms no cross-contamination. **Cross-tenant negative test.**
13. **Documentation requirements:** `docs/ui-ux` (Student home/overview conventions, if not already in screen-map.md). No new `docs/api` entry — composition page.

### SDASH-2 — My Courses list (empty/loading/error states)

1. **Story ID:** SDASH-2
2. **Business outcome:** Gives a student a reliable list of their active/expired course enrollments with properly differentiated states.
3. **User role:** Student.
4. **Acceptance criteria:**
   - Given a student has active enrollments, then `My Courses` lists only backend-confirmed active enrollments.
   - Given no active enrollments, then the empty state explains "no active enrollments yet" with a CTA to the catalog — distinct from a filtered-empty state.
   - Given a course's access has expired, then it shows the distinct "access expired" state (ENR-2) with a Reactivate CTA, not silently disappearing.
5. **Dependencies:** Hard blockers: ENR-1, ENR-2 (needs access-state, not just existence, to render Locked/Active correctly), CRS-1.
6. **Backend impact:** Reads `enrollment-management`'s `api`; no new domain logic.
7. **Frontend impact:** `app/(student)/` — Course Card grid, Empty State, Error State with Retry.
8. **Database impact:** None new — reads `enrollment` (ENR-1) filtered to the authenticated student.
9. **Security impact:** Minimal — primarily a UI-state story. Verify empty-state messages don't leak existence of another tenant's/student's courses through error-message content.
10. **Tenant impact:** Query uses `(tenant_id, student_id)` index from ENR-1 — no bypass.
11. **Payment impact:** None.
12. **Testing requirements:** Unit: course-list DTO shaping/sorting. Testcontainers: list query returns only the authenticated student's own active enrollments, tenant-scoped. **Cross-tenant negative test.** Playwright: explicit tests for loading, empty, error, and populated states.
13. **Documentation requirements:** `docs/ui-ux` (empty/loading/error conventions per the shared state-component pattern). No new `docs/api` entry.

---

## MODULE 14 — Teacher dashboard

### TDASH-1 — Teacher home/overview

1. **Story ID:** TDASH-1
2. **Business outcome:** Gives an approved teacher a single overview of assigned courses, upcoming sessions, and pending actions, backend-filtered to their own assignments.
3. **User role:** Teacher, Teacher Assistant (PROVISIONAL).
4. **Acceptance criteria:**
   - Given a Teacher logs in, then their overview shows only their own backend-filtered assigned-course data.
   - Given this is a consumer-style surface, then it is mobile-first with card-based layout below sm/md.
   - Given no assigned courses exist, then the empty state explains "no assigned courses yet" with guidance to contact the tenant admin.
5. **Dependencies:** Hard blockers: AUTH-2, TCH-2 (assigned-courses read).
6. **Backend impact:** No new domain — reads `course-management`/`user-management` `api`s.
7. **Frontend impact:** `app/(teacher)/` — Statistic Card, mobile-first consumer-style layout.
8. **Database impact:** None new — reads `course`/`course_teacher_assignment` (CRS-1/CRS-3).
9. **Security impact:** Same backend-filtering requirement as TCH-2 — any KPI/summary shown here must be computed from the teacher's own assigned-course set server-side, not client-aggregated from a broader fetch.
10. **Tenant impact:** Query uses `(tenant_id, teacher_id)` index from CRS-3 — no bypass.
11. **Payment impact:** None.
12. **Testing requirements:** Unit: dashboard-aggregation DTO shaping for teacher-relevant widgets. Testcontainers: overview data backend-filtered to the authenticated teacher's own assignments — two-teacher/same-tenant fixture proves no cross-teacher leakage. **Cross-tenant negative test.**
13. **Documentation requirements:** `docs/ui-ux` (Teacher home/overview conventions). No new `docs/api` entry.

### TDASH-2 — My Courses (Teacher) list

1. **Story ID:** TDASH-2
2. **Business outcome:** Gives a teacher a reliable, backend-filtered list of exactly the courses they're assigned to.
3. **User role:** Teacher, Teacher Assistant (PROVISIONAL).
4. **Acceptance criteria:**
   - Given a Teacher requests `My Courses`, then results are limited server-side to their own assignments (shares TCH-2's underlying endpoint).
   - Given a Teacher attempts to reach a course outside their assignment by ID, then the request is rejected 403/404.
   - Given no assigned courses, then a distinct empty state (not the Student "no active enrollments" copy).
5. **Dependencies:** Hard blocker: TCH-2 (duplicative UI target of the same backend read — can reuse TCH-2's endpoint).
6. **Backend impact:** None beyond TCH-2.
7. **Frontend impact:** `app/(teacher)/` — Course Card grid, Empty State.
8. **Database impact:** None new — same source as TCH-2.
9. **Security impact:** Direct reuse of TCH-2's backend-filtered assigned-courses contract — this story must not introduce a parallel, less-filtered query path for list-view convenience.
10. **Tenant impact:** Same as TCH-2 — backend-filtered to assignments, never client-side filtered.
11. **Payment impact:** None.
12. **Testing requirements:** Overlaps with TCH-2's backend query — verify no separate unfiltered-fetch-then-client-filter implementation was introduced (the exact anti-pattern `frontend.md` forbids). Re-verify **cross-tenant negative test** against this specific list endpoint if it is a distinct API call. Playwright: "no assigned courses yet" empty state.
13. **Documentation requirements:** `docs/ui-ux` (empty-state copy differentiation). No new `docs/api` entry beyond TCH-2's assigned-courses endpoint.

---

## MODULE 15 — Tenant Admin dashboard

### TADASH-1 — Tenant Admin home/overview (KPIs)

1. **Story ID:** TADASH-1
2. **Business outcome:** Gives a Tenant Admin a single-tenant-scoped snapshot of key operational metrics to run their institute day to day.
3. **User role:** Tenant Admin / Institute Owner.
4. **Acceptance criteria:**
   - Given a Tenant Admin logs in, then the overview shows only their own tenant's data — no tenant selector/switcher exists anywhere in this portal.
   - Given KPI figures derive from payment/ledger data, then they are consistent with the ledger-derived Payment Dashboard (PAY-3), not a separately-computed number.
   - Given this is an admin-heavy surface, then it defines an explicit mobile fallback, not desktop-only.
5. **Dependencies:** Soft dependencies on STU-1, CRS-1, PAY-3, TEN-1 (each KPI card only has real data once its source domain exists) — can ship with partial/zero-state KPIs earlier.
6. **Backend impact:** No dedicated domain — **risk flagged:** if this aggregates via ad hoc joins across `user-management`/`course-management`/`payment-management` tables at request time, that violates the `reporting-analytics` guidance in `.claude/rules/architecture.md`. Recommend a BFF-style aggregation of narrow `api` reads per domain, not a live cross-schema join, even for MVP KPIs.
7. **Frontend impact:** `app/(tenant-admin)/` — Statistic Card grid.
8. **Database impact:** None new — aggregate reads across `tenant_user`, `course`, `order`/`payment`, `enrollment` for the admin's own tenant only.
9. **Security impact:** KPIs must be computed tenant-scoped server-side; a Tenant Admin of tenant A must never see aggregate numbers that include tenant B's data even indirectly. No tenant selector should appear anywhere in this portal.
10. **Tenant impact:** All aggregates scoped by `(tenant_id, ...)` — this is exactly the kind of "bulk/admin/reporting endpoint" flagged as a common isolation-bypass source; must remain tenant-scoped (not the platform-level bypass pattern).
11. **Payment impact:** KPI figures must be derived from `ledger_entry`/`payment` state, not raw `order` state.
12. **Testing requirements:** Unit: KPI-calculation/aggregation logic with mocked domain `api` responses. Testcontainers: KPI queries tenant-scoped — two-tenant fixture confirms tenant A's KPIs never include tenant B's counts (requires an explicit test, not incidental CRUD coverage). **Cross-tenant negative test.**
13. **Documentation requirements:** `docs/ui-ux` (Tenant Admin overview conventions). No new `docs/api` entry — composition of existing domain reads.

### TADASH-2 — Navigation shell for staff/student/teacher/course/payments sections

1. **Story ID:** TADASH-2
2. **Business outcome:** Gives Tenant Admin a consistent, permission-aware navigation shell into every operational area they're entitled to.
3. **User role:** Tenant Admin, and every staff sub-role once logged into the shared `app/(tenant-admin)/` route group.
4. **Acceptance criteria:**
   - Given a staff sub-role without a domain's permission, then the corresponding nav item/action is hidden or disabled — UX convenience only; the backend independently rejects the call regardless.
   - Given Read-only Auditor, then no mutating control appears anywhere in the shell.
   - Given a hidden/disabled action's backend returns 403 due to stale state, then the shared permission-denied pattern (RBAC-3) handles it gracefully.
5. **Dependencies:** Hard blocker: APP-2. Soft dependencies on STAFF-1, STU-1, TCH-1, CRS-1, PAY-3 existing enough to have real destinations to link to.
6. **Backend impact:** None.
7. **Frontend impact:** `app/(tenant-admin)/` — Desktop Sidebar (no tenant selector), Mobile Navigation drawer variant.
8. **Database impact:** None.
9. **Security impact:** Navigation-item visibility per staff sub-role is UX convenience only — every hidden/disabled nav destination's backend endpoint must independently reject unauthorized calls. Flag if any nav-gated section relies solely on hiding the link.
10. **Tenant impact:** N/A — structural navigation only.
11. **Payment impact:** None.
12. **Testing requirements:** Playwright: for each staff sub-role fixture, nav items outside permission set are hidden AND direct navigation to the hidden route still yields a server-verified permission-denied state (hidden link ≠ access control); no tenant selector/switcher renders anywhere. No new Testcontainers coverage beyond RBAC-2 — state explicitly this story adds only UI-shell Playwright checks.
13. **Documentation requirements:** `docs/ui-ux` (navigation shell / permission-hiding conventions). No new `docs/api` entry.

---

## MODULE 16 — Attendance

### ATT-1 — Mark attendance (Teacher, manual)

1. **Story ID:** ATT-1
2. **Business outcome:** Lets a teacher record present/absent/late status per enrolled student for a scheduled session, the foundational data source for attendance reporting.
3. **User role:** Teacher / Teacher Assistant (both "Yes" per the matrix — not part of the PROVISIONAL split), Attendance Operator (V/C/E), Tenant Admin (V/C/E).
4. **Acceptance criteria:**
   - Given a Teacher marks attendance for their own assigned session, then the record is persisted tenant-scoped and course/session-scoped.
   - Given a Teacher attempts to mark attendance for a session outside their assignments, then the action is rejected 403.
   - Given an Attendance Operator of tenant A attempts to read/mark tenant B's attendance, then the request is rejected 403/404.
   - No audit-log requirement is specified for attendance marking — do not add an unrequired audit obligation.
5. **Dependencies:** Hard blockers: CRS-1 (course/session exists), TCH-2 (assigned-course scope check), ENR-1 (enrolled-student roster to mark against).
6. **Backend impact:** `attendance-management` — `web`/`service`/`domain`/`repository` (tenant + course/session-scoped).
7. **Frontend impact:** `app/(teacher)/` — Mark Attendance (Status Chip Present/Absent/Late).
8. **Database impact:** New table `attendance_record(id, tenant_id, course_id FK, session_id FK, student_id FK, status CHECK enum PRESENT/ABSENT/LATE, marked_by, marked_at)`. **Scope decision flagged:** no session-scheduling table is otherwise in MVP scope — recommend using `lesson_id` from MAT-1 as the session-equivalent scope, or a minimal `class_session` table.
9. **Security impact:** Marking must be rejected for a session outside the marker's assigned course scope — backend-filtered, not client-restricted. Tenant- and course/session-scoped rows.
10. **Tenant impact:** `tenant_id NOT NULL` + composite FK `(tenant_id, course_id)`, `(tenant_id, student_id)` + index `(tenant_id, course_id)` and `(tenant_id, student_id)`.
11. **Payment impact:** None.
12. **Testing requirements:** Unit: attendance-status enum validation. Testcontainers: record persists tenant- and course/session-scoped; a Teacher marking a session outside their assignments is rejected 403. **Cross-tenant negative test.** Playwright: Teacher marks own session; direct URL/id substitution to a foreign session is blocked server-side.
13. **Documentation requirements:** `docs/architecture` (attendance table, tenant+course/session-scoped composite index); `docs/api` for the mark-attendance endpoint.

### ATT-2 — Attendance reports (Student/Teacher/Tenant Admin views)

1. **Story ID:** ATT-2
2. **Business outcome:** Gives each role a role-appropriate view of attendance history — students see only their own, teachers see only their own courses, Tenant Admin sees tenant-wide.
3. **User role:** Student (own only), Teacher (own courses only), Tenant Admin, Attendance Operator, Read-only Auditor.
4. **Acceptance criteria:**
   - Given a student views `My Attendance`, then only their own tenant-scoped history is returned.
   - Given a Teacher's Attendance Reports, then it is backend-limited to their own courses.
   - Empty state: "no attendance records yet" distinct from "no sessions match the selected date filter."
5. **Dependencies:** Hard blocker: ATT-1.
6. **Backend impact:** `attendance-management` — `service` (role- and tenant-filtered report reads; Teacher's report backend-limited to own courses).
7. **Frontend impact:** `app/(student)/` (My Attendance, mobile-first), `app/(teacher)/` and `app/(tenant-admin)/` (Attendance Reports, admin-heavy responsive table).
8. **Database impact:** None new — reads `attendance_record` (ATT-1), aggregated.
9. **Security impact:** Reports must be tenant- and role-filtered — the same intra-tenant-scoping concern flagged for TCH-2. Verify report endpoints don't accept a course/student-ID filter parameter that bypasses the assignment check.
10. **Tenant impact:** Teacher's report must be backend-limited to assigned courses/sessions only.
11. **Payment impact:** None.
12. **Testing requirements:** Unit: report-aggregation/tally logic. Testcontainers: Student's report backend-filtered to own tenant-scoped history; Teacher's report backend-limited to own courses (multi-teacher fixture). **Cross-tenant negative test at every role level.** Playwright: distinct empty states; Teacher's mobile-first view and Tenant Admin's responsive data-table both tested at narrow viewport.
13. **Documentation requirements:** `docs/ui-ux` (confirm the two distinct responsive patterns are documented); `docs/api` for the report-read endpoints (role-scoped variants).

---

## MODULE 17 — Exams

### EXM-1 — Question bank (MCQ + structured)

1. **Story ID:** EXM-1
2. **Business outcome:** Lets teachers/exam managers build a reusable bank of MCQ and structured questions, the raw material every scheduled exam draws from.
3. **User role:** Teacher, Teacher Assistant (draft only — PROVISIONAL), Exam Manager (V/C/E/A), Tenant Admin (V/C/E/A).
4. **Acceptance criteria:**
   - Given a Teacher/Exam Manager builds a question, then it is tenant- and course-scoped, stored for reuse.
   - Given a Teacher Assistant creates/edits a question, then it remains in draft state, per the PROVISIONAL matrix.
   - **OPEN DECISION:** Model Paper Library ownership between Teacher and Tenant Admin is unresolved — this story's scope does not resolve it.
5. **Dependencies:** Hard blockers: CRS-1, TCH-1.
6. **Backend impact:** `exam-management` — `web`/`service`/`domain`/`repository` (tenant + course-scoped).
7. **Frontend impact:** `app/(teacher)/` — Question Bank editor.
8. **Database impact:** New tables `exam_question(id, tenant_id, course_id FK, question_type CHECK enum MCQ/STRUCTURED, body, created_by)` and `exam_question_option(id, tenant_id, question_id FK, option_text, is_correct)`.
9. **Security impact:** Tenant-owned, course-scoped; roster/question-bank access must be backend-filtered per teacher assignment. A leaked question bank across courses/tenants is a content-integrity/fairness issue as well as tenant-isolation.
10. **Tenant impact:** Both tables: `tenant_id NOT NULL` + composite FK `(tenant_id, course_id)` / `(tenant_id, question_id)` + index `(tenant_id, course_id)`.
11. **Payment impact:** None.
12. **Testing requirements:** Unit: question-schema validation (MCQ options/answer marker; structured rubric fields). Testcontainers: rows persist tenant- and course-scoped. **Cross-tenant negative test.** Playwright: authoring form fully keyboard-navigable.
13. **Documentation requirements:** `docs/architecture` (question-bank table, tenant+course-scoped); `docs/api` for question-bank CRUD endpoints.

### EXM-2 — Exam scheduling + time limits

1. **Story ID:** EXM-2
2. **Business outcome:** Lets a teacher/exam manager schedule an exam with a defined time window and attached questions.
3. **User role:** Teacher, Exam Manager (V/C/E/A), Tenant Admin (V/C/E/A).
4. **Acceptance criteria:**
   - Given an exam is scheduled with a time window, then a student attempting access before the window is rejected/blocked with a distinct state.
   - Given exam status (Draft/Scheduled/Published/Closed), then badges pair color with text/icon.
   - Given a Teacher Assistant attempts to publish/schedule beyond draft, then the action is rejected server-side.
5. **Dependencies:** Hard blocker: EXM-1.
6. **Backend impact:** `exam-management` — `service`/`domain` (schedule window, time-limit fields).
7. **Frontend impact:** `app/(teacher)/` — Exam Scheduler (Date Input, Step Indicator).
8. **Database impact:** New tables `exam(id, tenant_id, course_id FK, title, scheduled_start, scheduled_end, time_limit_minutes, status CHECK enum DRAFT/SCHEDULED/PUBLISHED/CLOSED)` and `exam_question_link(tenant_id, exam_id FK, question_id FK, sequence)`.
9. **Security impact:** A student must not be able to access an exam before its scheduled window via direct ID access — must be rejected/blocked with a distinct state, not merely hidden from a list.
10. **Tenant impact:** `tenant_id NOT NULL` on both + composite FK `(tenant_id, course_id)`/`(tenant_id, exam_id)` + index `(tenant_id, course_id, status)`.
11. **Payment impact:** None.
12. **Testing requirements:** Unit: scheduling-window/time-limit validation. Testcontainers: scheduled exam persists tenant/course-scoped; a student accessing before the window is rejected at the real access-check endpoint, not just UI-hidden. **Cross-tenant negative test.**
13. **Documentation requirements:** `docs/architecture` (exam-schedule data model, time-window enforcement); `docs/api` for the scheduling endpoint.

### EXM-3 — Exam taking (student) + auto-marking (MCQ)

1. **Story ID:** EXM-3
2. **Business outcome:** Lets an enrolled student attempt a scheduled exam within its time window, with MCQ answers auto-marked deterministically on submission.
3. **User role:** Student.
4. **Acceptance criteria:**
   - Given an MCQ exam is submitted, then it is auto-marked deterministically and consistently on re-computation.
   - Given exam submission, then it is announced via `aria-busy`/live region, not just a spinner.
   - Given results are unpublished, then the student cannot see their score/review even though the attempt is complete.
   - **OPEN DECISION:** exact recovery UX for a dropped connection mid-exam-attempt is not documented anywhere.
5. **Dependencies:** Hard blockers: EXM-2, ENR-1 (only enrolled students may take the exam — cross-module `api` check against `enrollment-management`).
6. **Backend impact:** `exam-management` — `service` (deterministic auto-marking), `web` (attempt submission).
7. **Frontend impact:** `app/(student)/` — Exam Taking (mobile-first, `aria-busy`/live-region on submit).
8. **Database impact:** New tables `exam_attempt(id, tenant_id, exam_id FK, student_id FK, started_at, submitted_at, status)` and `exam_answer(id, tenant_id, attempt_id FK, question_id FK, response, auto_score nullable)`.
9. **Security impact:** Attempt/submission endpoints must verify enrollment and the exam's active window server-side per request, not trust client-held state. No client-supplied score/answer-correctness field is trusted.
10. **Tenant impact:** `tenant_id NOT NULL` on both + composite FK `(tenant_id, exam_id)`, `(tenant_id, student_id)` + index `(tenant_id, student_id)` and `(tenant_id, exam_id)`.
11. **Payment impact:** None — exams are not payment-gated beyond enrollment (already the access precondition via ENR-1).
12. **Testing requirements:** Unit: MCQ auto-marking is deterministic and idempotent on re-computation. Testcontainers: attempt persists tenant/student-scoped; auto-marked score stable across re-computation; a student accessing another same-tenant student's attempt by id is rejected. **Cross-tenant negative test.** Playwright: exam submission announced via `aria-busy`/live region.
13. **Documentation requirements:** `docs/architecture` (exam-attempt/auto-marking data model); `docs/api` for exam-taking/submission endpoints.

### EXM-4 — Manual marking queue (structured answers)

1. **Story ID:** EXM-4
2. **Business outcome:** Routes structured (non-auto-markable) answers to a dedicated queue for teacher/exam-manager review, ensuring no structured answer is silently auto-scored.
3. **User role:** Teacher, Exam Manager (V/C/E/A).
4. **Acceptance criteria:**
   - Given a structured-answer exam, then it enters a `Marking Queue` and is not auto-scored.
   - Given a Teacher marks an answer for a course they're not assigned to, then the action is rejected 403.
   - Given the marking queue empty state, then it distinguishes "no answers to mark" from "no exams scheduled."
5. **Dependencies:** Hard blocker: EXM-3.
6. **Backend impact:** `exam-management` — `service`/`web` (Marking Queue, teacher/Exam Manager scoped).
7. **Frontend impact:** `app/(teacher)/` — Marking Queue (Table).
8. **Database impact:** Additive to `exam_answer` (EXM-3) — `manual_score` nullable, `marked_by`, `marked_at`. No new table.
9. **Security impact:** Marking-queue access must be scoped to the marker's assigned courses (same pattern as attendance/roster filtering). No student should be able to see un-marked/unpublished results via this queue's data paths.
10. **Tenant impact:** Marking-queue query uses `(tenant_id, exam_id)` index; must be backend-filtered to the marking teacher's own assigned courses.
11. **Payment impact:** None.
12. **Testing requirements:** Unit: marking-queue eligibility logic (structured only, never MCQ). Testcontainers: structured submissions enter the queue and are not auto-scored; marking is tenant/course-scoped — a Teacher cannot mark for an unassigned course. **Cross-tenant negative test.** Playwright: keyboard-operable scoring controls.
13. **Documentation requirements:** `docs/architecture` (marking-queue data model, tenant+course-scoped); `docs/api` for marking-queue read/submit-score endpoints.

### EXM-5 — Results publishing + Results & Review (student)

1. **Story ID:** EXM-5
2. **Business outcome:** Lets a teacher/exam manager formally publish results once marking is complete, and gives students access to their published score plus answer review — never before publication.
3. **User role:** Teacher, Exam Manager (V/C/E/A) publish; Student consumes.
4. **Acceptance criteria:**
   - Given a Teacher Assistant attempts to publish results, then the action is rejected server-side regardless of UI state.
   - Given results are unpublished, then students cannot see their score/review even if the attempt is complete.
   - Empty state distinguishes "no exams scheduled" from "no published exams" (drafts exist but nothing visible).
   - **OPEN DECISION:** whether exam-result publication requires an audit-log entry — FR-EX-2 calls it "audit-considered" but `security.md`'s canonical mandatory-audit list does not name it. Flag rather than silently resolve.
5. **Dependencies:** Hard blocker: EXM-4 (structured marking must complete) and EXM-3 (MCQ auto-marking). Soft dependency: NOTIF-1/2 (result-published notification is async); soft dependency: AUDIT-1/2.
6. **Backend impact:** `exam-management` — `service`/`web` (publish gate, results not visible pre-publication even if attempt is complete).
7. **Frontend impact:** `app/(teacher)/` (Results Publishing) and `app/(student)/` (Results & Review) — Status Chip (color+icon).
8. **Database impact:** Additive to `exam` (EXM-2) — `results_published_at` nullable timestamp gating student visibility. No new table.
9. **Security impact:** Unpublished results must be invisible to students even if their exam attempt is complete — enforced server-side on the results-read endpoint, not by omitting a UI link. A Teacher Assistant attempting to publish must be rejected server-side (PROVISIONAL-role deny-path).
10. **Tenant impact:** Same `exam`/`exam_attempt`/`exam_answer` tables — student's Results & Review query filtered by `(tenant_id, student_id)` and `results_published_at IS NOT NULL`.
11. **Payment impact:** None.
12. **Testing requirements:** Unit: publish-gate logic (results invisible pre-publish). Testcontainers: given unpublished results, students cannot see score/review at the results-fetch endpoint even with a completed attempt. **Cross-tenant negative test.** Test whatever audit approach is actually implemented for the open decision above, and explicitly flag the ambiguity rather than silently assuming.
13. **Documentation requirements:** `docs/architecture` (results-publish state transition); `docs/api` for publish/results-read endpoints. If the audit-log open decision resolves to "yes," a `.claude/rules/security.md` list update is a prerequisite — flagged, not assumed.

---

## MODULE 18 — Email notifications

### NOTIF-1 — Async notification dispatch infrastructure

1. **Story ID:** NOTIF-1
2. **Business outcome:** Builds the asynchronous, event-driven dispatch backbone every other domain relies on to notify users without blocking their own transactions.
3. **User role:** No direct end-user role — infrastructure consumed by every domain on behalf of all roles.
4. **Acceptance criteria:**
   - Given a triggering domain event (e.g. payment confirmed), then the resulting notification dispatch does not share a transaction with, or block, the triggering write.
   - Given a notification event crosses a thread boundary, then `tenant_id` is explicitly carried in the payload and applied via the same structural tenant-filtering mechanism as request-time code.
   - Given Tenant A's notification template/delivery-log/preference record, then Tenant B cannot read it.
5. **Dependencies:** Hard blocker: APP-1. **Architecturally this should have been built far earlier** — nearly every prior module (PAY-2/3, SLIP-3/4, ENR-1/3, EXM-5, STAFF-2) has a soft dependency *back* on this story for their async side effects (see release plan for the recommended pull-forward).
6. **Backend impact:** `notification-management` — `service`/`config` (in-process event publish/consume infrastructure), explicit tenant_id-in-payload convention since async work doesn't inherit request-scoped context.
7. **Frontend impact:** None — backend-only.
8. **Database impact:** New table `notification_outbox` (id, tenant_id, event_type, payload JSONB, status, created_at, dispatched_at nullable) — needed since async work does not inherit request-scoped tenant context.
9. **Security impact:** Background/async work does not inherit request-scoped tenant context automatically — every event/job payload must explicitly carry `tenant_id`. **A missing explicit `tenant_id` in an event payload is a silent-cross-tenant-notification risk (risk register #7).** Notification dispatch must not share a transaction with the triggering write.
10. **Tenant impact:** `tenant_id NOT NULL` + index `(tenant_id, status)` for dispatch-worker polling. This table is the concrete mechanism by which every other domain's async fan-out stays tenant-scoped.
11. **Payment impact:** None — but this is the mechanism PAY-2/SLIP-3/ENR-2/ENR-3's async notifications route through; must never share a transaction with the triggering domain's write.
12. **Testing requirements:** Unit: event-payload schema — every published event includes `tenant_id` explicitly. Testcontainers: a triggering action publishes an event and dispatch does not share a transaction with, or block, the triggering write; a queued job payload carries `tenant_id` explicitly and the consumer applies it via the same structural mechanism as request-time code. **Cross-tenant negative test.**
13. **Documentation requirements:** `docs/architecture` (event-consumer pattern for notification-management, confirm matches modular-monolith.md §4); `docs/api` if a queryable notification-status endpoint is introduced.

### NOTIF-2 — Transactional email templates + in-app Notification Center

1. **Story ID:** NOTIF-2
2. **Business outcome:** Delivers MVP-scoped email and in-app notifications through tenant-scoped templates, giving users a persistent in-app record of notifications received.
3. **User role:** Every role is a recipient; Tenant Admin/Finance Staff compose (sub-role authorization unspecified).
4. **Acceptance criteria:**
   - Given email templates, then they are tenant-scoped branding assets, resolved the same tenant-scoped way as logo/branding, never a shared static default.
   - Given a notification is dispatched, then it appears in the recipient's in-app `Notification Center`.
   - MVP scope is email + in-app channels only; SMS/WhatsApp, bulk/segment messaging, delivery logs/retry are explicitly Phase 2.
   - **OPEN DECISION:** which staff sub-role(s) may manage templates or trigger sends is unspecified.
   - **OPEN DECISION:** MVP-level failure handling for a failed email send is undefined (only Phase 2's retry is named) — do not silently implement a retry mechanism as if decided.
5. **Dependencies:** Hard blocker: NOTIF-1. Cross-module hard dependency: `integration-management`'s email-provider `api` (embedded here).
6. **Backend impact:** `notification-management` — `domain`/`repository` (tenant-owned templates), `web` (Notification Center read endpoint).
7. **Frontend impact:** `app/(student)/` primarily (Notification Center), Teacher gets an activity-feed-only subset; shared Toast/Alert live-region wrapper reused platform-wide.
8. **Database impact:** New tables `notification_template(id, tenant_id, template_key, subject, body)` and `in_app_notification(id, tenant_id, recipient_user_id FK, title, body, read_at nullable, created_at)`.
9. **Security impact:** Templates/delivery-logs/preferences are tenant-owned data — Tenant B must not read Tenant A's templates. Notification Center reads must be scoped to the authenticated recipient only.
10. **Tenant impact:** Both: `tenant_id NOT NULL` + composite index `(tenant_id, template_key)` and `(tenant_id, recipient_user_id, created_at)`.
11. **Payment impact:** None.
12. **Testing requirements:** Unit: template-rendering/variable-substitution logic. Testcontainers: templates tenant-owned/scoped (two-tenant fixture, no cross-tenant leakage); Notification Center list scoped to the authenticated recipient's own tenant/user. **Cross-tenant negative test.** Playwright: "no notifications yet" empty state; new notification appears after a triggering event.
13. **Documentation requirements:** `docs/architecture` (confirm template/notification-center data model, tenant-scoped); `docs/api` for the Notification Center read endpoint; `docs/ui-ux` for the template branding-resolution convention.

---

## MODULE 19 — Audit logs

### AUDIT-1 — Audit log schema + append-only enforcement

1. **Story ID:** AUDIT-1
2. **Business outcome:** Establishes the immutable, structurally enforced audit trail table that every privileged action writes to, with no update/delete path ever exposed to any actor including Platform Admin.
3. **User role:** No direct end-user role — foundational for accountability across all roles.
4. **Acceptance criteria:**
   - Given the audit table, then `tenant_id` (or an explicit platform-scope marker), `actor_id`, `action`, `target_entity`/`target_id`, and `occurred_at` are all `NOT NULL` at the schema level.
   - Given an audit row exists, then no update or delete endpoint/repository method can target it.
   - Given a Tenant Admin of tenant A queries tenant B's audit log, then the request is rejected 403/404.
5. **Dependencies:** Hard blockers: APP-1, APP-4. **This story is a hard blocker retroactively for TEN-2, CRS-2, PAY-2/4, SLIP-3/4, and ENR-3, all of which are built and shipped many modules earlier by story-ID order — this is the sharpest sequencing risk in the entire backlog** (see release plan: recommend pulling AUDIT-1's schema forward alongside Module 10).
6. **Backend impact:** `audit-log-management` — `domain`/`repository` (no `delete`/`deleteById` exposed anywhere; `NOT NULL` on required columns), `api` (event-consumer registration surface every other domain publishes to).
7. **Frontend impact:** None — backend-only.
8. **Database impact:** New table `audit_log(id, tenant_id NULLABLE-only-with-platform-scope-marker, actor_id NOT NULL FK, action NOT NULL, target_entity NOT NULL, target_id NOT NULL, occurred_at NOT NULL, before_state JSONB nullable, after_state JSONB nullable)`. Recommend a CHECK constraint enforcing "either `tenant_id` is set, or `is_platform_scope = true`, never neither, never both" rather than a bare nullable column.
9. **Security impact:** Foundational integrity control. **No update/delete repository method may exist for this table for any actor, including Platform Admin** — must be structurally rejected, not policy-only. **Risk register #10:** get this wrong and every other module's audit claims in the backlog become unverifiable/tamperable.
10. **Tenant impact:** `tenant_id` index `(tenant_id, occurred_at)` for tenant-scoped viewer queries.
11. **Payment impact:** None directly, but this table is the load-bearing dependency for PAY-2/PAY-3/SLIP-3/SLIP-4/ENR-2/ENR-3's mandatory audit requirements.
12. **Testing requirements:** Unit: audit-row DTO validation — rejects construction missing required fields. Testcontainers: schema-level NOT NULL enforcement (DB-level insert rejection); append-only enforcement — no repository method exposes update/delete. **Cross-tenant negative test (mandatory, explicit in spec).** This story is the shared foundation for every other module's audit-log-row-written matrix test.
13. **Documentation requirements:** `docs/architecture` (new audit table schema, append-only enforcement per database-architecture.md §3) — required update since this is a new data model.

### AUDIT-2 — Audit event capture wiring for MVP-mandatory actions

1. **Story ID:** AUDIT-2
2. **Business outcome:** Wires every MVP-scoped privileged action across domains to publish a domain event that AUDIT-1's consumer persists, closing the loop between "action happened" and "it's recorded."
3. **User role:** No direct end-user role — captures actions performed by Tenant Admin, Finance Staff, Course Coordinator, Exam Manager, etc.
4. **Acceptance criteria:**
   - Given any action on the canonical mandatory list (course/session price changes, payment approvals/rejections, access/expiry extensions, reactivation approvals, material/course content deletions — device resets and settlement changes are Phase 2/later, excluded here), then exactly one audit row is written with correct fields.
   - Given a privileged action occurs inside its owning domain's `@Transactional` boundary, then the domain publishes an event; `audit-log-management` persists its own row — never written directly by the triggering domain.
   - Given a slip-approval override with no reason, then the system rejects it before any state change or audit row is written (SLIP-4).
5. **Dependencies:** Hard blocker: AUDIT-1. Hard blockers (retroactive): TEN-2, CRS-2, PAY-2, PAY-4, SLIP-4, ENR-3 must already publish the domain events this story consumes — if those stories didn't define event contracts when they shipped, this story requires reopening them.
6. **Backend impact:** `audit-log-management` — `service` (event listeners per source domain), consuming rather than being called into directly.
7. **Frontend impact:** None — backend-only.
8. **Database impact:** None new — wires domain event listeners into `audit_log` inserts (AUDIT-1).
9. **Security impact:** Implements the payment-cluster audit rules for PAY-2 (approvals/rejections) and SLIP-3/SLIP-4 (approve/reject + override-with-reason). **OPEN DECISION LIST — flag, do not silently resolve:** staff account creation/role changes, student status changes, teacher approval, tenant onboarding approval, branding/theme changes, exam-result publication — none are on the canonical mandatory list; this story must not silently add or silently omit audit wiring for them.
10. **Tenant impact:** Every consumed event must carry `tenant_id` explicitly — this story must verify tenant_id propagation for every wired action.
11. **Payment impact:** Directly implements the payment-cluster audit rules from `payments.md` §2-4 for PAY-2 and SLIP-3/SLIP-4.
12. **Testing requirements:** Testcontainers: one dedicated test per already-shipped mandatory action (CRS-2, PAY-2/PAY-4/SLIP-3, ENR-2, ENR-3, MAT-3, SLIP-4) — each asserting exactly one audit row with correct fields, written in the same transaction as the privileged action. **[Matrix: audit-log-row-written — this story IS that row, applied broadly.]** **Cross-tenant negative test:** event payloads carry the correct acting tenant's id.
13. **Documentation requirements:** `docs/architecture` (event-to-audit-row wiring per domain); flag each open decision above to the parent process for tracking rather than resolving unilaterally.

### AUDIT-3 — Audit Log Viewer (Tenant Admin)

1. **Story ID:** AUDIT-3
2. **Business outcome:** Gives Tenant Admin, staff sub-roles (own-area actions), and Read-only Auditor a read-only, tenant-scoped view into the audit trail.
3. **User role:** Tenant Admin (V, full), staff sub-roles (V, "own-area actions" — enforcement mechanism unspecified), Read-only Auditor (V, full).
4. **Acceptance criteria:**
   - Given a Tenant Admin views the Audit Log Viewer, then only their own tenant's rows are shown, filterable, with no update/delete affordance anywhere.
   - **OPEN DECISION:** the enforcement mechanism for what counts as a staff sub-role's "own area" is undefined anywhere.
   - Empty state: "no audit events yet" vs. "no events match your filter/date-range."
5. **Dependencies:** Hard blockers: AUDIT-2 (real data), RBAC-2.
6. **Backend impact:** `audit-log-management` — `web` (tenant-scoped, read-only query endpoint).
7. **Frontend impact:** `app/(tenant-admin)/` — Audit Log Viewer (Table, no update/delete affordance anywhere in the UI).
8. **Database impact:** None new — reads `audit_log` (AUDIT-1).
9. **Security impact:** Cross-tenant read protection is mandatory. Staff sub-role "own-area actions" scoping has no defined enforcement mechanism anywhere in reviewed material — flag as a concrete implementation gap that risks either over-exposure or inconsistent ad hoc filtering.
10. **Tenant impact:** Standard `TenantAwareRepository`-scoped read via `(tenant_id, occurred_at)` index.
11. **Payment impact:** None.
12. **Testing requirements:** Testcontainers: viewer's list/search query is tenant-scoped; staff sub-role "own-area actions" scoping tested against whatever is actually built (flag if unimplemented). **Cross-tenant negative test (mandatory).** Playwright: no update/delete UI affordance exists anywhere in the viewer; distinct empty states.
13. **Documentation requirements:** `docs/ui-ux` (Audit Log Viewer conventions); `docs/api` for the tenant-scoped audit-log read/search endpoint.

---

## MODULE 20 — Platform Admin dashboard

### PADASH-1 — Platform Admin home/overview + tenant list/approval queue UI

1. **Story ID:** PADASH-1
2. **Business outcome:** Gives Platform Admin a cross-tenant operational view (pending approvals, tenant list) that is the primary control surface for onboarding/monitoring every tenant.
3. **User role:** Platform Admin.
4. **Acceptance criteria:**
   - Given any cross-tenant list/table, then it shows the tenant name/identifier on every row.
   - Given a non-Platform-Admin actor attempts the approval queue or tenant list, then access is rejected server-side.
   - Given no destructive/state-changing action is submittable without the target tenant visibly named next to it.
5. **Dependencies:** Hard blockers: TEN-2 (approval backend), AUTH-1/AUTH-2/RBAC-2.
6. **Backend impact:** Reads `tenant-management`'s explicitly-named cross-tenant bypass methods; no new domain logic beyond TEN-2.
7. **Frontend impact:** `app/(platform-admin)/` — Tenant List/Approval Queue (tenant name/identifier on every row).
8. **Database impact:** None new — reads `tenant` (TEN-1) and the approval-queue bypass method from TEN-2.
9. **Security impact:** This is a platform-level view — uses the explicitly named bypass, never `TenantAwareRepository`. Every row must show tenant name/identifier.
10. **Tenant impact:** Reuses TEN-2's backend state machine — avoid re-testing it; focus new tests on this endpoint's own access control and the UI tenant-naming requirement.
11. **Payment impact:** None.
12. **Testing requirements:** Testcontainers: reconfirm the list/approval-queue endpoint uses the explicitly named cross-tenant bypass method and that only Platform Admin can reach it. **Cross-tenant / platform-admin-only negative test.** Playwright: every row shows tenant name/identifier; distinct empty states.
13. **Documentation requirements:** `docs/ui-ux` (Platform Admin dashboard conventions — persistent tenant-name-per-row rule). No new `docs/api` beyond TEN-2's approval endpoints.

### PADASH-2 — Cross-tenant payment dashboard + platform audit log/tenant drill-down

1. **Story ID:** PADASH-2
2. **Business outcome:** Gives Platform Admin oversight of payment activity and privileged-action history across all tenants, with a non-dismissible tenant-context banner when drilling into one tenant's data.
3. **User role:** Platform Admin.
4. **Acceptance criteria:**
   - Given the cross-tenant payment dashboard, then Order/Payment data is never mixed across tenants in a single aggregate row — per-tenant attribution is retained.
   - Given a Platform Admin drills into a single tenant's payment or audit data, then a persistent, non-dismissible tenant-context banner names that tenant for the duration.
   - Given the platform audit log, then Platform Admin sees platform-level actions plus a per-tenant drill-down, with no update/delete affordance anywhere.
5. **Dependencies:** Hard blockers: PAY-3 (payment data to aggregate), AUDIT-2/AUDIT-3 (platform audit log data and viewer pattern to reuse).
6. **Backend impact:** `payment-management`/`ledger-settlement-management` and `audit-log-management` each need an explicitly-named, justified cross-tenant read method — not a `TenantAwareRepository` finder, must be visibly flagged in review.
7. **Frontend impact:** `app/(platform-admin)/` — persistent, non-dismissible tenant-context banner for any single-tenant drill-down, Breadcrumbs.
8. **Database impact:** None new — reads `payment`, `ledger_entry` (PAY-2/PAY-3) and `audit_log` (AUDIT-1) across tenants.
9. **Security impact:** Highest-risk dashboard in the backlog for accidental cross-tenant data mixing (risk register #9). If any "act as tenant" capability is included, it must be a backend-issued impersonation session with dual-identity audit logging at start and end, not a locally toggled UI state — Platform Admin's default permissions must not implicitly grant tenant-admin-equivalent operational access.
10. **Tenant impact:** This is the canonical platform-level cross-tenant bypass story for the payment/ledger/audit domains. Aggregation must be additive/summary only, not a join that blends tenant A and tenant B rows into one queryable set without a `tenant_id` discriminator on every returned row.
11. **Payment impact:** Aggregation is a `reporting-analytics`-style concern — recommend a read model/summary query over per-tenant `ledger_entry` rows, not ad hoc live joins across every tenant's payment tables at request time.
12. **Testing requirements:** Testcontainers: cross-tenant payment aggregation is read-only, never mixes/mutates cross-tenant data; platform-level audit log query uses an explicitly named bypass method distinct from the tenant-scoped Audit Log Viewer's query. **Cross-tenant / platform-admin-only negative test (mandatory).** Playwright: persistent tenant-context banner for the full drill-down duration; no destructive payment action submittable without the target tenant visibly named.
13. **Documentation requirements:** `docs/ui-ux` (tenant-context-banner requirement for drill-down views); `docs/api` for the cross-tenant payment/audit read endpoints (explicitly named platform-level bypass methods).

---

## MODULE 21 — MVP integration and staging

### INTG-1 — End-to-end cross-tenant integration test suite across all MVP modules

1. **Story ID:** INTG-1
2. **Business outcome:** Proves, with real fixtures across at least two tenants, that every MVP module's tenant isolation, role authorization, and payment/enrollment integrity rules hold together as a system, not just per-module in isolation.
3. **User role:** No direct end-user role — quality gate protecting all roles.
4. **Acceptance criteria:**
   - Given the full MVP module set, then every tenant-owned endpoint/repository method/query exercised has an explicit cross-tenant negative test.
   - Given persistence-touching logic, then tests use Testcontainers-backed real PostgreSQL/Redis — mocked repositories are not accepted as proof of tenant filtering.
   - Given payment/enrollment integrity rules, then an idempotency test proves duplicate webhook/approval delivery does not double-activate enrollment or double-write ledger entries, end to end.
   - Given Playwright E2E coverage, then role-based authenticated fixtures exist for all 4 roles across at least two seeded tenants, with cross-tenant E2E negative tests for every feature area with a backend cross-tenant test.
5. **Dependencies:** Hard blocker: every functional module (1–20) substantially complete — cannot meaningfully start until the cross-tenant surfaces it tests actually exist.
6. **Backend impact:** Testcontainers-backed integration tests across all 18 domains touched by MVP scope; not new production code.
7. **Frontend impact:** Playwright cross-tenant E2E specs across all five route groups, per the two-tenant fixture requirement.
8. **Database impact:** None new — this story exercises every migration end-to-end via Testcontainers PostgreSQL. No hand-rolled schema in test setup.
9. **Security impact:** This story is the verification backstop for every tenant-isolation claim made across Modules 4–20 — "a review that looks correct but has no accompanying cross-tenant test must be treated as isolation being unverified, not isolation being present." **Absence/incompleteness of this suite is itself the top platform-wide risk (risk register #14).**
10. **Tenant impact:** Must set up **at least two distinct tenants** per testing conventions, and must include the mandatory cross-tenant negative test for every tenant-owned table introduced across the backlog.
11. **Payment impact:** Must include the idempotency tests required for PAY-2/PAY-3 (duplicate webhook) and SLIP-3 (duplicate approval), and the enrollment-activation-source test (ENR-1) proving activation never occurs from a request payload alone.
12. **Testing requirements:** This story IS the testing story — the capstone aggregation of every cross-tenant test named across all prior stories: a consolidated Testcontainers suite exercising representative cross-tenant negative assertions per domain, run together to catch cross-module regressions. Also re-runs the idempotency/audit-log-row-written matrix rows for payment/ledger and slip-approval as regression canaries at suite level. If any individual module's cross-tenant test is missing here, treat that module's own story as incomplete, not a defect of INTG-1.
13. **Documentation requirements:** This story does not introduce a new API/data model — its documentation obligation is confirming existing `docs/api` and `docs/architecture` entries are accurate, not creating new ones; state explicitly which gaps (if any) were found and fixed.

### INTG-2 — Staging environment deployment + smoke test

1. **Story ID:** INTG-2
2. **Business outcome:** Proves the full MVP system runs correctly end-to-end in a production-like environment before any production go-live decision is made.
3. **User role:** No direct end-user role — internal release-readiness gate.
4. **Acceptance criteria:**
   - Given staging, then it runs the same container images and Compose/orchestration shape as production, with its own isolated Postgres/Redis instances and its own synthetic tenant test data — never real student/financial records.
   - Given a deployment to staging, then Flyway migrations apply as part of the deploy, ahead of/alongside new app containers, schema and app version moving together; no already-applied migration is edited/renumbered.
   - Given app instances in staging, then they are stateless and horizontally scaled behind Nginx with no sticky-session configuration required.
   - Given a smoke test pass, then it exercises at least one full flow per major MVP module across at least two seeded tenants.
5. **Dependencies:** Hard blockers: APP-3 (CI/infra), INTG-1 (tests must exist and pass before promoting to staging).
6. **Backend impact:** No domain — Docker Compose/Nginx staging topology.
7. **Frontend impact:** Build/deploy verification only.
8. **Database impact:** None new — validates that all migrations from APP-1 through PADASH-2 apply cleanly, in order, against a staging PostgreSQL instance via Flyway.
9. **Security impact:** Verify staging never connects to or seeds from production data/credentials. Confirm secrets used in staging are staging-scoped, not shared/reused production secrets. Confirm no production deploy is triggered automatically.
10. **Tenant impact:** Smoke test should include a basic cross-tenant read/write check against staging as a final structural-isolation sanity check.
11. **Payment impact:** Never connects to production databases or uses real financial records; staging fixtures must be synthetic.
12. **Testing requirements:** Test-light in the unit/integration sense — validates deployment/infrastructure correctness, not new application logic (already covered by APP-1/APP-3/INTG-1). Deliverable: a smoke-test suite against deployed staging — login for all 4 role fixtures × 2 tenants succeeds; core read paths return 200 with expected shape; `/actuator/health` reports UP. No new cross-tenant test artifact required beyond re-running INTG-1's suite against staging.
13. **Documentation requirements:** `docs/architecture/deployment-architecture.md` — if this story resolves any of §6's open questions (CI/CD tooling, hosting provider, secrets management), update accordingly; otherwise state explicitly those remain open.

### INTG-3 — MVP go-live readiness review (definition-of-done sign-off)

1. **Story ID:** INTG-3
2. **Business outcome:** Provides the final human-gated checkpoint confirming every MVP module has passed planning, implementation, tests, tenant-isolation/security review, and documentation before a production deployment decision is made — consistent with the project's "never deploy production automatically" safety rule.
3. **User role:** No direct end-user role — internal release-governance gate (human approval required).
4. **Acceptance criteria:**
   - Given each MVP module, then the `definition-of-done` checklist is confirmed complete (see `docs/planning/definition-of-done.md`).
   - Given any change-controlled area was touched anywhere in MVP scope, then it has a linked, Accepted ADR under `docs/adr` — a story touching one with no linked approval is not ready.
   - Given production deployment, then it is never automatic and never merged/approved by the agent itself — explicit human approval is required.
   - Given `docs/requirements/open-decisions.md`, then every item flagged as affecting an MVP-scoped story in this backlog is explicitly listed as either resolved-with-a-decision-record or knowingly deferred — none silently dropped at go-live.
   - Given the full 61-story MVP backlog, then this review confirms 100% of MVP-classified stories are complete — Phase 2/3 sub-features correctly excluded from the go-live bar.
5. **Dependencies:** Hard blocker: INTG-2.
6. **Backend impact:** None — review/sign-off activity.
7. **Frontend impact:** None.
8. **Database impact:** None new — sign-off checklist re-verifies every tenant-owned table has `tenant_id NOT NULL` + tenant-leading composite index, no repository method accepts a caller-supplied `tenant_id`, and append-only enforcement holds for `payment`/`ledger_entry`/`payment_slip`/`audit_log`.
9. **Security impact:** Formal checkpoint to confirm every change-controlled area touched during MVP build stayed within its approved ADR/baseline, per `.claude/rules/git-workflow.md`. Also confirms every audit-related open decision (staff role-change audit gap, teacher-approval audit gap, reactivation-approver precedence, staff sub-role audit-scoping mechanism) has been explicitly resolved or deferred with owner sign-off.
10. **Tenant impact:** Sign-off checklist item: confirm no migration introduced a shared/global table holding multi-tenant rows without a `tenant_id` discriminator.
11. **Payment impact:** Sign-off checklist item: confirm no Phase 2/3/4 payment-roadmap concern was scaffolded into any Phase 1 table ahead of its own approved design — flag any violation found rather than waiving it.
12. **Testing requirements:** Test-light — this is a checklist/gate story, not a code-testing story. Its only testing activity is a final full re-run of `backend\mvnw.cmd verify` and `npx playwright test`, confirming 100% green and zero skipped cross-tenant tests across all 61 stories. Flag any story found without its required test at this gate as a go-live blocker.
13. **Documentation requirements:** `docs/adr` (any change-controlled deviations found during review must be recorded here before sign-off, not after); no other new documentation is created by this story.
