# frontend/e2e — Real-Backend, Two-Tenant Cross-Tenant Layer (MVP-021 / INTG-1)

This document covers only the **new** infrastructure added for
`docs/plans/MVP-021 Integration and Staging Review.md` §9.3/§18.4: a
`test.extend`-based authenticated-session fixture, a two-tenant seed
mechanism, and real (non-mocked) cross-tenant negative specs, all targeting a
**locally-running backend** (not the mocked style used by the existing 39
specs — see "Existing specs, unaffected" below).

**Read this whole file before running anything here.** Two structural,
backend/infrastructure-level gaps were found and verified during this
module's implementation, and they currently block every spec under
`frontend/e2e/cross-tenant/` from completing its real assertions. They are
documented in full below, with evidence, because this module's own governing
rule is: never fall back to a mock to paper over a real gap, and never
silently skip without a visible reason.

## 1. Starting the local backend natively

Per `infrastructure/CLAUDE.md`, backend and frontend run **natively** in
local dev; only Postgres/Redis/Mailpit/MinIO are containerized.

```sh
# 1. Start the containerized infra dependencies (from the repo root)
docker compose -f infrastructure/docker-compose.dev.yml --env-file infrastructure/.env.dev up -d

# 2. Start the backend natively (from backend/)
cd backend
./mvnw.cmd spring-boot:run
# Confirms healthy once GET http://localhost:8080/actuator/health returns {"status":"UP"}

# 3. Start the frontend natively (from frontend/), pointed at the local backend
cd frontend
NEXT_PUBLIC_API_BASE_URL=http://localhost:8080/api npm run dev
```

This matches `backend/src/main/resources/application-local.yml`'s defaults
(`jdbc:postgresql://localhost:5433/lms_dev`, `localhost:6379` Redis) and
`infrastructure/.env.dev`'s credentials — both already present in this repo,
no new secret was introduced.

## 2. Running the seed script

```sh
node frontend/e2e/fixtures/seed/two-tenant-seed.mjs
```

This registers two synthetic tenants (`tenant1`, `tenant2`) via the one
genuinely public, unauthenticated, already-shipped endpoint,
`POST /api/v1/tenant-registrations`. It is idempotent (safe to re-run; a
`409 CONFLICT` on an already-registered subdomain is treated as success) and
was verified against a real, locally-running backend during this module's
implementation — see the transcript in §5.

**It then stops and prints a blocker report, exiting non-zero.** See §4 for
why — this is not a bug in the script.

## 3. Running the new cross-tenant suite

```sh
npx playwright test --grep @cross-tenant
```

New specs live under `frontend/e2e/cross-tenant/` and are tagged
`@cross-tenant` in their test title (Playwright has no first-class `@Tag`
concept like JUnit 5 — a title-embedded tag plus `--grep` is the equivalent
convention used here, mirroring the backend's `@Tag("cross-tenant")`
convention from the same module).

As of this implementation pass, every spec in this directory **skips** with
a clear, visible reason (see the `skip` annotation each produces) rather than
passing or failing — this is intentional, per §4/§5, and is not the same as
"not run"/"silently omitted": the skip reason is attached to the test result
and will show up in any reporter (`--reporter=list`, `--reporter=html`,
`--reporter=json`), and a false "pass" is never fabricated. Confirmed via a
real run against a real local backend:

```
- 1 [chromium] › e2e\cross-tenant\course-management.spec.ts:29:7 ›
    course-management — cross-tenant › Tenant A's Tenant Admin cannot read
    Tenant B's course by id @cross-tenant
1 skipped
```

with the JSON reporter's `annotations[0].description` on that result reading:

```
Real-backend session unavailable: No seeded credential for role=TENANT_ADMIN
tenant=tenant1.lms.local: expected env vars
E2E_TENANT1_TENANT_ADMIN_EMAIL/E2E_TENANT1_TENANT_ADMIN_PASSWORD to be set.
As of this module's implementation pass, no API path exists to provision
this account (see frontend/e2e/README.md 'Blocked: account provisioning'),
so these env vars are never set today — this is expected until that gap
closes.
```

## 4. Why this is blocked today — two structural gaps

### 4.1 Blocked: account provisioning (no bootstrap-admin API)

**Finding:** there is no REST API endpoint anywhere in the shipped backend
that creates a `platform_admin_user` row, or the very first `TENANT_ADMIN`
`tenant_user` row for a tenant. Every other account-creation endpoint
transitively requires one of those two to already exist and be
authenticated:

| Step | Endpoint | Requires |
|---|---|---|
| Register a tenant | `POST /api/v1/tenant-registrations` | Nothing (`permitAll()`) — **works today**, see §5 |
| Approve a tenant (`pending_approval` → `trial`) | `POST /api/v1/platform-admin/tenants/{id}/approve` | An authenticated `PLATFORM_ADMIN` bearer token |
| Create the first Tenant Admin for a tenant | *(no such endpoint exists)* | — |
| Create a Staff/Teacher/Student account | `POST /api/v1/staff`, `POST /api/v1/teachers`, `POST /api/v1/students` | An authenticated `TENANT_ADMIN` (or narrower staff-permission) bearer token |

Evidence gathered while building this module:

- `backend/src/main/resources/db/migration/V4__create_platform_admin_user.sql`
  creates the `platform_admin_user` table with **zero** seed rows.
- A repository-wide search for `CommandLineRunner`/`ApplicationRunner`/a
  dev-profile data seeder anywhere in `backend/src/main/java` returned no
  results — there is no dev-only bootstrap mechanism either.
- `com.lms.usermanagement.staff.web.dto.StaffCreateRequest.roleCode`'s
  `@Pattern` explicitly excludes `TENANT_ADMIN` from the 7 assignable staff
  sub-roles, with its own Javadoc stating "those accounts are provisioned by
  other flows, not this one" — but no such other flow exists anywhere in the
  shipped backend (confirmed by reading `TenantRegistrationService`,
  `TenantApprovalService`, and every listener on `TenantRegisteredEvent`/
  `TenantStatusChangedEvent`: only `notification-management` — template
  seeding — and `audit-log-management` — an audit row — consume those
  events; neither provisions a user).
- `docs/api/user-management.md` documents only the student-management
  surface and explicitly flags Staff Management's contract as an
  undocumented gap in its own text.

**This blocks all 4 top-level portal roles' fixtures identically** (Student,
Teacher, Tenant Admin — all need a Tenant Admin to exist first; Platform
Admin has no provisioning path at all) — it is not specific to any one of
the ~15 feature areas named in the module brief.

**What would unblock it** (out of scope for this task — a `backend/`
change, needs explicit product/engineering approval per root `CLAUDE.md`'s
change-control rules, since it touches authentication/account-provisioning):
a dev/test-only bootstrap-admin endpoint or Spring profile-gated seed bean
(never shipped active in a production profile), or an explicit product
decision on how a tenant's first Tenant Admin is meant to be provisioned in
real life (this is itself an open product question, not just a test-infra
gap — see `docs/requirements/open-decisions.md` §24).

**Requested**: [GitHub issue #26](https://github.com/mohanranaweera/lms-saas-platform/issues/26).

### 4.2 Blocked: no CORS configuration on the backend

**Finding:** `docs/api/identity-access-service.md`'s own "CORS caveat"
section already flagged this; it was independently reproduced against a
real, locally-running backend during this module's implementation.
`SecurityFilterChainConfig` calls `.csrf(...).disable()` but never
`.cors(...)`, and no `WebMvcConfigurer` bean adds a CORS mapping anywhere in
`backend/src/main/java`. The frontend's API client
(`frontend/src/lib/api/client.ts`) makes a real cross-origin `fetch()` from
the browser (Next.js dev server, `http://localhost:3000`) directly to the
backend (`http://localhost:8080`) — different origins by the browser's
same-origin policy (different port).

**Verified behavior** (reproduced via a temporary Playwright probe run
against the real stack, both scenarios below produced an identical result):

- A credentialed, `Content-Type: application/json` `POST` (the shape of the
  real login call) — blocked before any response was observable:
  `Access to fetch at 'http://localhost:8080/api/v1/auth/login' from origin
  'http://localhost:3000' has been blocked by CORS policy: No
  'Access-Control-Allow-Origin' header is present on the requested
  resource.` → `TypeError: Failed to fetch` in page JS; Playwright's own
  `response`/`requestfinished` network events never fired for the request.
- A CORS-"simple" unauthenticated `GET` to a `permitAll()` endpoint
  (`GET /api/v1/public/courses`, no custom headers, no credentials) —
  **identically blocked**, with the same missing-`Access-Control-Allow-Origin`
  error and no observable response, proving this is not specific to
  credentialed/preflighted requests.

This means: **the Host-header rewrite technique this module's brief
specifies works correctly at the protocol level** — independently confirmed
via a direct Playwright `APIRequestContext` call
(`request.newContext().post(...)` with a manually-set `Host` header, which is
Node-side and not subject to browser CORS at all) reaching the real backend
and correctly driving `TenantResolutionFilter`'s per-request tenant
resolution (it returned a genuine `403 TENANT_UNAVAILABLE` for an
unregistered subdomain, not a malformed-request error) — **but it cannot
currently be exercised through an actual rendered browser page**
(`page.goto()` + real UI interaction), because the browser blocks the
underlying cross-origin request entirely before Playwright's
`context.route()`-rewritten `Host` header (or anything else about the
request) is even relevant. `route.continue({ headers })` can rewrite a
request header; it cannot fabricate the `Access-Control-Allow-Origin`
response header a browser requires to let page JS observe the response, nor
bypass the browser's own CORS preflight/same-origin enforcement.

A secondary, independent confirmation: `frontend/src/lib/auth/auth-context.tsx`
deliberately keeps the access token **in-memory only** (never `localStorage`,
never a JS-readable cookie) as an XSS hardening measure. This rules out a
"log in via Node, then inject the token into browser storage" workaround —
there is no client-side storage location to inject into, by design. Every
subsequent authenticated page load in the actual app would also need to
re-establish its session via the browser's own cross-origin `fetch()`
(refresh-cookie based), hitting the identical CORS wall. This is a complete,
no-exceptions blocker for real, browser-rendered E2E against the natively
running backend in this port-3000/port-8080 local topology — not just for
login, but for every single subsequent API call the app's UI would make.

**What would unblock it** (out of scope for this task — a `backend/`
change): an explicit CORS configuration on `SecurityFilterChainConfig`
scoped to the frontend's known local-dev origin(s)
(`http://localhost:3000`) with `allowCredentials(true)`, as
`docs/api/identity-access-service.md`'s own CORS caveat already recommends.
Once INTG-2's staging/Nginx topology exists (same-origin, single entry
point per `docs/architecture/deployment-architecture.md` §2), this
particular gap will not recur there — this is specifically a
native-local-dev-topology problem.

**Requested**: [GitHub issue #27](https://github.com/mohanranaweera/lms-saas-platform/issues/27).

## 5. What was actually verified to work

- **Tenant self-registration**, real, non-mocked, against a real local
  backend: `POST http://localhost:8080/api/v1/tenant-registrations` for two
  distinct subdomains (`tenant1`, `tenant2`) both returned `201` with
  `status: "pending_approval"`; a third call with the same subdomain
  returned `409 CONFLICT` — proving the endpoint's documented uniqueness
  behavior. This is exercised for real by
  `frontend/e2e/fixtures/seed/two-tenant-seed.mjs` (Node's own `fetch`,
  unaffected by browser CORS — see §4.2).
- **The `Host`-header rewrite technique**, verified at the wire/protocol
  level via Playwright's `APIRequestContext` (see §4.2) — the mechanism
  itself is sound and is exactly what
  `frontend/e2e/fixtures/real-backend-session.ts#installHostHeaderRewrite`
  implements via `context.route()`/`route.continue({ headers })`, ready to
  work the moment §4.2's CORS gap is closed.
- **The `test.extend` fixture and one representative cross-tenant spec**
  (`frontend/e2e/cross-tenant/course-management.spec.ts`) run cleanly against
  a real Playwright suite today: they produce a clean, single, well-formed
  `skip` (not a hang, not an unhandled rejection, not a false pass) with the
  exact diagnostic reason from §4 attached — confirmed via
  `npx playwright test e2e/cross-tenant/course-management.spec.ts`.
- **The existing 39 mocked specs are untouched and unaffected** — full-suite
  run after adding this module's files: 509 passed, 1 skipped (the new
  spec above), 2 failed. Both failures
  (`teacher-management.spec.ts:167`, `tenant-admin-courses.spec.ts:178`) are
  pre-existing, already-documented flakiness (`playwright.config.ts`'s own
  comment: Turbopack dev-server route-compile flakiness under parallel
  workers, "33/41 tests failing at default worker count, 41/41 passing at
  `--workers=1`") — reproduced and confirmed unrelated to this module's
  changes by re-running both files alone with `--workers=1`: both passed
  (28/28). No file this module didn't add was modified (`git status` shows
  only new files under `frontend/e2e/`).

## 6. Feature-area status (§18.4's ~15 areas)

Given §4's two blockers apply identically and completely to every role
(Student, Teacher, Tenant Admin, Platform Admin), **no feature area's
cross-tenant spec can complete its real assertion today** — this is not a
per-area gap to triage, it is a single shared root cause. Rather than write
14 more near-identical specs that would all `skip` with the same reason
(explicitly against this task's "do not pad the count with shallow/broken
specs" instruction), this pass delivers:

- The reusable fixture + seed infrastructure (§2, §3) — done, verified,
  ready to activate.
- One fully-real, non-stub demonstration spec,
  `frontend/e2e/cross-tenant/course-management.spec.ts`, showing the exact
  intended pattern (mirroring the backend's own
  `CourseManagementIntegrationTest` cross-tenant proof) for when §4 is
  resolved.
- This README as the authoritative, evidence-backed record of what's
  blocked and why.

**Deferred, pending §4's resolution** (all 15 areas, including the one
demonstrated above): identity/session, tenant management, course management,
content/material, enrollment, payment (orders), payment slip,
ledger/settlement, attendance, exams, notifications, audit log, teacher
management, student management, staff management. Writing the remaining 14
specs is mechanical once §4 is closed (same fixture, same pattern, different
route/endpoint per `docs/api/*.md`) — tracked as explicit follow-up work,
not silently dropped.

## 7. Existing specs, unaffected

`frontend/e2e/route-groups.spec.ts`, `frontend/e2e/shared-states.spec.ts`,
and every other pre-existing spec under `frontend/e2e/` were not modified by
this module and remain fully mocked (`page.route()`-intercepted), single-tenant,
and backend-independent, exactly as before. They continue to cover what they
already correctly cover — see each file's own module doc comment.
