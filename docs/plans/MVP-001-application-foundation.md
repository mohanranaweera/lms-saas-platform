# Application Foundation Module — Plan

## Context

The repository is a multi-tenant SaaS LMS built as a Spring Boot (Java 21/Maven) modular
monolith + Next.js frontend. Extensive product/architecture planning already exists on
branch `feature/application-foundation` (rebased onto `docs/finalize-requirements`,
commit `aadf7e7`): 8 accepted ADRs, full `.claude/rules/*` and `.claude/agents/*`
definitions, `docs/architecture/*`, and minimal-but-real scaffolding for `backend/`
(Spring Boot 4.1.0, `pom.xml` with web/security/validation/data-jpa/data-redis/cache/
actuator/mail/flyway/testcontainers deps, `application-local.yml`,
`TestcontainersConfiguration.java`), `frontend/` (Next.js 16.2.10, React 19.2.4,
TanStack Query, shadcn/ui already initialized, Tailwind v4 tokens generated), and
`infrastructure/` (`docker-compose.dev.yml` running Postgres 17/Redis 7/Mailpit/MinIO).

None of the 18 confirmed business domains (identity-access-service, tenant-management,
course-management, etc.) exist yet. **Application Foundation is the cross-cutting
plumbing every future domain module will build on** — it is not itself a business
domain. This plan was produced by delegating to the seven named specialist agents in
parallel (solution-architect, backend-springboot-engineer, frontend-nextjs-engineer,
database-architect, security-reviewer, qa-test-engineer, devops-engineer), each grounded
in the existing ADRs/rules/scaffolding, then reconciled into one plan below. Two genuine
disagreements between agents are resolved explicitly (package name, and how to test
`TenantAwareRepository`) — see the relevant sections.

This is a **plan only** — no files were created or edited (plan mode enforced this
throughout, including for the specialist agents).

---

## 1. Architecture Plan

**New top-level package: `com.lms.common`** (backend), sibling to the 18 domain
packages, under `backend/src/main/java/com/lms/common/`.

This is a deliberate, narrow exception to `.claude/rules/architecture.md`'s "one
top-level package per domain" rule: `com.lms.common` holds no business logic, no domain
entities, no REST endpoints — only shared-kernel infrastructure every domain will
depend on. ADR-006 itself implies this precedent ("identity-access-service/
tenant-management repositories *may now be written against* `TenantAwareRepository`"),
meaning the mechanism predates and sits below both foundational domains — it must not
be nested inside either. (solution-architect proposed `com.lms.platform`;
backend-springboot-engineer independently assumed `com.lms.common`. Decision: **use
`com.lms.common`** — it's the more conventional Java shared-kernel name and avoids
collision with the heavily-overloaded "platform" vocabulary already used for
Platform Admin / platform-level rows / platform-level bypass methods throughout the
docs.)

Recommend documenting this package-structure exception in
`docs/architecture/modular-monolith.md` (short addendum) as part of this module's
documentation step — not a full ADR, since `.claude/rules/architecture.md`'s "when an
ADR is required" list doesn't cover shared-kernel package placement, but it is a
precedent every future contributor needs to see explained, not just discover.

Internal structure:

```
com.lms.common
|-- api        # ApiResponse<T>, ApiError, FieldError (response envelope)
|-- web        # GlobalExceptionHandler (@RestControllerAdvice)
|-- error      # ApplicationException hierarchy (NotFoundException, ConflictException, ...)
|-- persistence# BaseEntity/Auditable (@MappedSuperclass), ID generation, TenantAwareRepository<T,ID>
|-- tenant     # TenantContext interface + TenantContextHolder
`-- config     # Postgres (Hikari), Redis/Cache, Actuator, Jackson, correlation-id/logging
```

Plus `backend/src/main/resources/db/migration/` (Flyway foundation — see §4).

**Frontend architecture**: App Router route groups by audience —
`app/(public)/`, `app/(auth)/`, `app/(student)/`, `app/(teacher)/`,
`app/(tenant-admin)/`, `app/(platform-admin)/` — each with its own layout; a shared
`DashboardShell` + per-role nav component; a shared state-component library
(loading/empty/error/permission-denied); a single typed API client + React Query
provider; zod-based env validation. The existing shadcn/ui oklch CSS-variable tokens in
`globals.css` are confirmed sufficient as the design-token foundation — they're
already structured (CSS variables) to support tenant-branding overrides in a later
module; this module only adds documentation, no new tokens.

---

## 2. Backend Files Affected (new unless noted)

**Updated to reflect the as-built state** (original plan guessed some file names/shapes before
`TenantAwareRepository`'s first real implementation existed, per §10's own risk note; this list
is now the accurate record):

```
backend/src/main/java/com/lms/common/api/ApiResponse.java
backend/src/main/java/com/lms/common/api/ApiError.java
backend/src/main/java/com/lms/common/api/ApiErrorCodes.java              # stable error-code constants
backend/src/main/java/com/lms/common/api/FieldError.java
backend/src/main/java/com/lms/common/web/GlobalExceptionHandler.java
backend/src/main/java/com/lms/common/error/ApplicationException.java
backend/src/main/java/com/lms/common/error/NotFoundException.java
backend/src/main/java/com/lms/common/error/ConflictException.java
backend/src/main/java/com/lms/common/persistence/BaseEntity.java          # UUIDv7 id, @MappedSuperclass
backend/src/main/java/com/lms/common/persistence/Auditable.java          # created/updated at/by, @EnableJpaAuditing
backend/src/main/java/com/lms/common/persistence/UuidV7.java             # marker annotation for the generator below
backend/src/main/java/com/lms/common/persistence/UuidV7Generator.java    # custom Hibernate IdentifierGenerator
backend/src/main/java/com/lms/common/persistence/AuditorAwareImpl.java   # Optional.empty() placeholder, flagged
backend/src/main/java/com/lms/common/persistence/TenantOwned.java        # implemented by every tenant-owned entity
backend/src/main/java/com/lms/common/persistence/TenantAwareRepository.java      # @NoRepositoryBean, ADR-006 mechanism
backend/src/main/java/com/lms/common/persistence/TenantAwareRepositoryImpl.java  # SimpleJpaRepository-based enforcement
backend/src/main/java/com/lms/common/persistence/TenantAwareRepositoryFactoryBean.java  # wires the impl in per-entity
backend/src/main/java/com/lms/common/persistence/CrossTenantPersistenceException.java   # save-guard mismatch signal
backend/src/main/java/com/lms/common/tenant/TenantContext.java           # throws if unpopulated
backend/src/main/java/com/lms/common/tenant/TenantContextHolder.java     # ThreadLocal, explicit set/clear
backend/src/main/java/com/lms/common/tenant/TenantContextNotResolvedException.java  # fail-loud signal
backend/src/main/java/com/lms/common/config/TenantConfig.java            # binds the TenantContext bean
backend/src/main/java/com/lms/common/config/JpaAuditingConfig.java       # @EnableJpaAuditing wiring
backend/src/main/java/com/lms/common/config/JpaRepositoryConfig.java     # @EnableJpaRepositories(repositoryFactoryBeanClass=...)
backend/src/main/java/com/lms/common/config/CacheConfig.java             # RedisCacheManager, "lms:" key prefix
backend/src/main/java/com/lms/common/config/OpenApiConfig.java           # springdoc setup
backend/src/main/java/com/lms/common/config/CorrelationIdFilter.java     # MDC: correlationId, tenantId
backend/src/main/java/com/lms/common/config/SecurityConfig.java          # baseline deny-all-except-health/docs posture
backend/src/main/resources/application.yml                               # base/default profile (structured logging via
                                                                           # Spring Boot's native logging.structured
                                                                           # property, not a hand-written logback-spring.xml
                                                                           # as originally guessed -- no PersistenceConfig.java
                                                                           # either; Hikari tuning lives inline in
                                                                           # application-local.yml)
backend/src/main/resources/db/migration/V1__baseline_conventions.sql     # marker/doc only, see §4
backend/.env.example                                                     # new, no real secrets
backend/pom.xml                                                          # modify: springdoc-openapi, spring-boot-starter-flyway,
                                                                          # and Spring Boot 4.1.0's split test modules (see §8)
```

Test files — see §8.

---

## 3. Frontend Files Affected (new unless noted)

```
frontend/src/app/layout.tsx                       (modify: wire QueryProvider)
frontend/src/app/globals.css                       (modify: doc comment only)
frontend/src/app/error.tsx
frontend/src/app/not-found.tsx
frontend/src/app/(public)/layout.tsx
frontend/src/app/(public)/page.tsx                  (replaces src/app/page.tsx)
frontend/src/app/(auth)/layout.tsx
frontend/src/app/(auth)/login/page.tsx              # disabled/placeholder form, see §7
frontend/src/app/(auth)/register/page.tsx           # disabled/placeholder form
frontend/src/app/(auth)/forgot-password/page.tsx    # disabled/placeholder form
frontend/src/app/(student)/layout.tsx
frontend/src/app/(student)/dashboard/page.tsx
frontend/src/app/(student)/error.tsx
frontend/src/app/(teacher)/{layout,dashboard/page,error}.tsx
frontend/src/app/(tenant-admin)/{layout,dashboard/page,error}.tsx
frontend/src/app/(platform-admin)/{layout,dashboard/page,error}.tsx
frontend/src/components/layout/dashboard-shell.tsx
frontend/src/components/layout/nav/student-nav.tsx
frontend/src/components/layout/nav/teacher-nav.tsx
frontend/src/components/layout/nav/tenant-admin-nav.tsx
frontend/src/components/layout/nav/platform-admin-nav.tsx
frontend/src/components/providers/query-provider.tsx
frontend/src/components/states/loading-state.tsx      # aria-busy, aria-live="polite"
frontend/src/components/states/empty-state.tsx        # no default copy — props required per ui-ux.md
frontend/src/components/states/error-state.tsx        # role="alert", onRetry?
frontend/src/components/states/permission-denied-state.tsx  # driven only by real 401/403, see §7
frontend/src/lib/env.ts                                # zod schema for NEXT_PUBLIC_API_BASE_URL etc.
frontend/src/lib/api/client.ts
frontend/src/lib/api/types.ts                          # ApiResponse<T> mirrors backend envelope
frontend/src/lib/api/error.ts                           # ApiError, isApiError()
frontend/src/lib/validation/auth.ts                      # zod schemas for auth forms (client UX only)
frontend/src/hooks/use-query-status.ts
frontend/playwright.config.ts
frontend/e2e/route-groups.spec.ts
frontend/e2e/shared-states.spec.ts
frontend/e2e/accessibility.spec.ts                       # @axe-core/playwright, add as new devDependency
frontend/e2e/fixtures/base.ts
frontend/package.json                                     (modify: add @axe-core/playwright devDependency)
```

---

## 4. Database Impact

**Minimal — this module ships no domain schema.** The `tenant` table belongs to the
future `tenant-management` module; Application Foundation must not create it or any
other domain table (database-architect's explicit finding).

- `backend/src/main/resources/db/migration/V1__baseline_conventions.sql` — a
  documentation-only marker migration (header comment restating the shared-schema
  conventions: `tenant_id NOT NULL` + FK, composite tenant-leading indexes,
  tenant-scoped uniques) so the first migration file itself signals the standard to
  every future domain author. **No Postgres extension needed** — IDs are generated
  app-side (UUIDv7 via a custom Hibernate `IdentifierGenerator`, §1/§2), so
  `pgcrypto`/`uuid-ossp` are not required (Postgres 17 also has `gen_random_uuid()`
  built into core regardless, per database-architect).
- Existing `flyway.enabled: true` + default `db/migration` location in
  `application-local.yml` is correct and sufficient — single shared-schema modular
  monolith uses one migration location for all future domains, numbered sequentially
  regardless of which domain authors a given migration. No config change needed.
- `TestcontainersConfiguration.java`'s `postgres:17-alpine` already matches
  `docker-compose.dev.yml`; Testcontainers + `@ServiceConnection` + `flyway.enabled`
  auto-runs migrations against the test container with no extra wiring.

---

## 5. API Conventions

Envelope (`com.lms.common.api`), returned by every endpoint this and future modules
expose:

```json
// success
{ "success": true, "data": { ... }, "error": null, "timestamp": "...", "traceId": "..." }
// failure
{ "success": false, "data": null, "error": { "code": "VALIDATION_ERROR", "message": "...", "fieldErrors": [{"field": "email", "message": "..."}] }, "timestamp": "...", "traceId": "..." }
```

`GlobalExceptionHandler` status mapping: validation → 400, unauthenticated → 401,
access-denied → 403, not-found → 404, conflict/data-integrity → 409, fallback → 500
(no internal details leaked in the 500 body).

Frontend's typed API client (`frontend/src/lib/api/client.ts`) is built against this
exact shape — confirmed matching between backend-springboot-engineer's and
frontend-nextjs-engineer's independently-produced designs, no reconciliation needed.

API documentation: add `springdoc-openapi` (verify version compatibility with Spring
Boot 4.1.0 before pinning) as a **supplement only** — the hand-written
`docs/api/<domain>.md` contract files (per existing `docs/api/README.md`) remain the
contract of record, populated by the `review-api-contract` skill as each real domain's
endpoints land. Nothing to add there yet — no domain endpoints exist.

---

## 6. Tenant-Context Design

Unanimous across solution-architect, backend-springboot-engineer, security-reviewer,
and qa-test-engineer — this is the single most consequential decision in this module:

- **`TenantContext`** (interface, `com.lms.common.tenant`): exposes `tenantId()`,
  **throws `TenantContextNotResolvedException` if read before being explicitly
  populated.** Never returns null or a default tenant.
- **`TenantContextHolder`**: `ThreadLocal`-backed, explicit `set`/`clear` only (no
  auto-population), consistent with `.claude/rules/tenancy.md`'s requirement that
  background/async work must explicitly carry tenant identity rather than inherit it.
- **`TenantAwareRepository<T, ID>`** (`@NoRepositoryBean`, `com.lms.common.persistence`):
  the ADR-006 structural mechanism — injects the resolved tenant id into every finder.
  No entity extends it yet (no tenant-owned table exists) — it ships as generic,
  reusable infrastructure for the first real consumer (tenant-management).
- **No resolver ships in `src/main` at all.** Not a real JWT-based resolver (out of
  scope — identity-access-service's job, no JWT library even pinned yet per ADR-007),
  and explicitly **not** a dev-profile-gated placeholder either — security-reviewer's
  finding is that a "temporary convenience" resolver is exactly the kind of thing that
  survives into production once identity-access-service exists and nothing structurally
  distinguishes it from the real one. If a resolver is needed to exercise integration
  tests, it must be named unmistakably test-only (e.g. `TestOnlyStaticTenantContext`)
  and live under `src/test` only, never `src/main`.
- Loud Javadoc on `TenantContext`: *"No default implementation ships until
  identity-access-service (ADR-007) is implemented; do not add one here."*

This module implements the *mechanism* ADR-002/ADR-006 already mandate — it does not
redecide multi-tenancy strategy or authentication architecture, so no new ADR is
required, provided implementation stays within this boundary (security-reviewer, §5 of
their report).

---

## 7. Security Impact

(Full detail from security-reviewer; summarized here.)

- Overall low risk — most of this module (response envelope, exception handling,
  validation format, audit-fields base entity, extension-free Flyway migration,
  `health,info`-only actuator, springdoc setup) carries no tenant or auth surface.
- The two load-bearing pieces are `TenantContext`/`TenantAwareRepository` (§6, resolved:
  fail-loud, no resolver in `src/main`) and structured logging MDC — **explicitly
  exclude** Authorization headers, JWT/refresh-token values, and request bodies from
  ever landing in MDC or the exception handler's logged payload; state this as a
  requirement now, before identity-access-service exists to make the risk concrete.
- **Frontend risk**: an `(auth)` route group with fully-functional-looking but
  unwired login/register forms invites someone to bolt on a quick insecure endpoint
  later "to unblock the frontend." **Decision: ship these forms visibly
  disabled/placeholder-labeled** ("Not yet implemented — pending
  identity-access-service"), with no reachable "successful login" client state.
- **Frontend risk**: `permission-denied-state.tsx` must accept only a real HTTP
  status/error object from the typed API client's error shape — **never** a
  boolean/role prop a developer could set directly — so no code path can "hide"
  content without the backend having actually rejected the request.
- Actuator exposure (`health,info`) must stay that restrictive in every profile
  including `local` — flag if a future change loosens `management.endpoints.web.
  exposure.include` to `*` for convenience.
- Testcontainers base must use ephemeral/clearly-fake credentials, never anything
  resembling a real secret, per root `CLAUDE.md` Safety rules.
- springdoc/OpenAPI exposure-by-profile is an open decision to make explicitly before
  the first real domain endpoint ships (not urgent now — no endpoints exist yet).
- Confirmed: this module does not touch multi-tenancy strategy or authentication
  architecture as scoped — no new ADR required (see §6).

---

## 8. Testing Plan

**Backend — unit/slice:** `ApiResponseEnvelopeTest`, `GlobalExceptionHandlerTest`
(via `@WebMvcTest` against a throwaway test controller, proving `@ControllerAdvice`
wiring through real MVC dispatch), `ValidationErrorFormatTest`, `TenantContextTest`
(fail-loud when unpopulated; thread isolation), `AuditableBaseEntityTest`
(`@DataJpaTest`).

**Backend — Testcontainers integration**, all extending a new shared
`com.lms.common.AbstractIntegrationTest` (`@SpringBootTest` +
`@Import(TestcontainersConfiguration.class)`, a `withTenant(UUID, Runnable)` helper for
future domain modules' cross-tenant tests, mandatory `TenantContext` clear in
`@BeforeEach`/`@AfterEach`):
- `FlywayMigrationIntegrationTest` — clean migrate on fresh Postgres 17-alpine.
- `ActuatorHealthIntegrationTest` — `/actuator/health` reports `UP`.
- `OpenApiIntegrationTest` — `/v3/api-docs` valid in local profile.
- `CorrelationIdLoggingIntegrationTest` — correlation-id round-trip in MDC/logs.
- `TenantAwareRepositoryFixtureIntegrationTest` — **see decision below.**

**Resolved disagreement — testing `TenantAwareRepository`:** database-architect
recommended unit/mock tests only, deferring real integration testing until
tenant-management ships the first real tenant-owned table (concern: a disposable
fixture table creates a second migration lineage). qa-test-engineer recommended adding
a disposable test-only fixture entity/table now, seeded via `JdbcTemplate` under two
tenant ids, asserting reads/writes are scoped and that an unpopulated `TenantContext`
throws rather than leaking rows (rationale: this is the platform's single
highest-security-value mechanism, and mocks structurally cannot fail a missing `WHERE
tenant_id = ?`). **Decision: adopt qa-test-engineer's approach**, mitigating
database-architect's lineage concern by keeping the fixture migration in a distinct
test-only resource location (`backend/src/test/resources/db/migration-test/`,
`V9001__test_fixture_entity.sql`, high version number to visibly separate it from real
history) activated only under the `test` Spring profile — never applied to any real
dev/prod database. Keep this fixture permanently as a fast regression guard even after
tenant-management ships a real table, per qa-test-engineer.

**Frontend (Playwright):** `playwright.config.ts` (webServer via `next dev`); smoke
navigation of every route group's placeholder shell; a shared-states spec for
loading/empty/error/permission-denied; an accessibility spec via
`@axe-core/playwright` (zero critical/serious violations) on shared components and
shells. No data-flow/API-mocking tests yet — structural/accessibility smoke only.

**Explicitly deferred (flag as mandatory follow-up, not forgotten):** cross-tenant
negative tests against *real* business data — must be added the moment the first real
domain module (tenant-management) lands, as an explicit checklist item on that PR. No
real JWT-resolution tests, no RLS tests, no prod-profile hardening tests, no
rate-limiting tests, no real login/permission E2E flows — none of these are possible
yet without identity-access-service.

---

## 9. Implementation Sequence

Per root `CLAUDE.md`'s development workflow (backend fully implemented + tested before
frontend starts; backend/frontend land as separate commits unless "full-stack
implementation approved" is stated — it has not been here):

1. Backend: `com.lms.common` package skeleton, `BaseEntity`/`Auditable` + UUIDv7 ID
   generation.
2. Backend: `ApiResponse`/`ApiError`/`FieldError` envelope + `GlobalExceptionHandler` +
   validation error mapping.
3. Backend: `TenantContext` + `TenantContextHolder` + `TenantAwareRepository` (fail-loud,
   no resolver).
4. Backend: `V1__baseline_conventions.sql` + test-only fixture migration/location config.
5. Backend: Postgres (Hikari)/Redis (`CacheConfig`)/Actuator/`CorrelationIdFilter` +
   `logback-spring.xml`.
6. Backend: springdoc-openapi setup (verify Spring Boot 4.1.0 compatibility first).
7. Backend: `AbstractIntegrationTest` + full test suite from §8.
8. Run `backend\mvnw.cmd verify` — must be green before proceeding.
9. Frontend: `lib/env.ts` + API client/types/error (envelope already reconciled, §5).
10. Frontend: React Query provider wired into root layout.
11. Frontend: shared state components (loading/empty/error/permission-denied).
12. Frontend: route groups + layouts + placeholder dashboards + disabled auth-form shell
    (§7).
13. Frontend: dashboard shell + responsive per-role nav.
14. Frontend: Playwright config + smoke/accessibility specs.
15. Run `npm run lint && npm run build && npx playwright test` — must be green.
16. Security + tenant-isolation review pass: re-confirm fail-loud `TenantContext`, no
    resolver under `src/main`, permission-denied component wired only to real API error
    status.
17. Documentation: addendum to `docs/architecture/modular-monolith.md` for the
    shared-kernel package exception (§1); no `docs/api/` changes yet (no domain
    endpoints).
18. Commit as separate backend and frontend commits (not bundled), per git-workflow
    rules.

---

## 10. Risks

- **Package-boundary precedent** (`com.lms.common`) needs to be documented, not just
  implicitly established — see §1's recommended doc addendum.
- **`TenantAwareRepository`'s exact shape is being guessed** before its first real
  consumer (tenant-management) is built against it — keep it minimal (standard finders
  only; explicit named methods for anything custom/cross-tenant per ADR-006) and expect
  revision once a real entity exists, rather than over-fitting speculative query shapes
  now.
- **ID/audit-field shape in `BaseEntity` is effectively permanent** once domain
  migrations start depending on it.
- **Resolver-creep risk** (§6/§7) — the single largest security risk of building this
  ahead of identity-access-service; mitigated by shipping no resolver in `src/main` and
  naming any test-only resolver unmistakably.
- **Auth-shell/permission-denied component could look production-ready** without being
  real — mitigated by disabled/placeholder auth forms and a permission-denied component
  that only accepts real API error status (§7).
- **Actuator/springdoc exposure drift** across profiles over time — flag as an ongoing
  review item, not fully closeable now.
- **Minor infra findings** (devops-engineer, non-blocking): root `.env.example`
  duplicates `infrastructure/.env.dev.example`'s Postgres/MinIO values with no drift
  today but a future-drift risk; Mailpit/MinIO lack healthchecks (Postgres/Redis have
  them); `start-infra` scripts don't poll container readiness before returning (Spring/
  driver will simply retry-fail fast if not ready — low impact).

---

## 11. Explicit Exclusions

- No `tenant` table or any domain table/migration (tenant-management's responsibility).
- No identity-access-service, JWT/session/device logic, or any real authentication —
  neither backend nor frontend (only a disabled UI shell for the latter).
- No real tenant-resolution logic anywhere (only the abstraction/mechanism, §6).
- No business-domain entities, services, controllers, or endpoints of any kind.
- No Hibernate `@Filter` (ADR-006 fixes `TenantAwareRepository` as primary; adding
  `@Filter` later is a separate proposal, not part of this module).
- No cross-tenant negative tests against real business data (deferred to first real
  domain module, flagged as mandatory follow-up, §8).
- No MinIO bucket provisioning or object-storage backend integration (no provider
  selected per ADR-008; infra container existing/reachable is sufficient here).
- No tenant branding/theming override logic (design tokens are prepared for it, §1, but
  the override mechanism itself is a later module).
- No production/staging deployment changes.
- No `docs/api/<domain>.md` contract files (no domain endpoints exist to document yet).
- No payment/ledger/enrollment logic of any kind.

---

## Verification

1. `backend\mvnw.cmd verify` — all new unit/slice/Testcontainers-integration tests
   green, including `TenantAwareRepositoryFixtureIntegrationTest` proving cross-tenant
   scoping and fail-loud behavior.
2. `npm run lint && npm run build` in `frontend/` — clean.
3. `npx playwright test` in `frontend/` — route-group smoke, shared-states, and
   accessibility specs green.
4. Manual: start infra (`scripts/development/start-infra.ps1`), run backend natively
   (`backend\mvnw.cmd spring-boot:run`), hit `GET /actuator/health` → `200 {"status":
   "UP"}`; run frontend natively (`npm run dev`), navigate each role route group's
   placeholder dashboard and the disabled `(auth)` forms in a browser, confirm shared
   loading/empty/error states render correctly.
5. Security/tenant-isolation review pass per §7/§9 step 16 before commit.
