# MVP-021 — Integration and Staging Review — Module Plan

**GitHub issue:** [#21 — \[MVP\] Module 21: MVP integration and staging](https://github.com/mohanranaweera/lms-saas-platform/issues/21)
**Backlog source:** `docs/planning/product-backlog.md`, Module 21 (stories `INTG-1`, `INTG-2`, `INTG-3`)

This plan was produced by delegating to seven specialist agents in parallel
(`product-requirements-analyst`, `solution-architect`, `database-architect`, `security-reviewer`,
`qa-test-engineer`, `ui-ux-reviewer`, `payment-ledger-specialist`), each independently grounded in
the issue text, `docs/requirements/`, `docs/architecture/`, every `docs/adr/*.md`, the actual
current backend (`backend/src/main/java`, `backend/src/main/resources/db/migration`,
`backend/src/test/java`) and frontend (`frontend/src/app`, `frontend/e2e`) code, and
`.github/workflows/ci.yml` / `infrastructure/` — then reconciled into one document. This is a
**plan only** — no application files, migrations, Dockerfiles, tests, or documentation were created
or edited during this review.

This module is structurally different from every prior MVP-0xx module: it adds **no new business
domain, no new entity, no new user-facing feature**. Its three stories (INTG-1: consolidated
cross-tenant/idempotency test suite; INTG-2: staging deployment + smoke test; INTG-3: go-live
sign-off review) are a verification, infrastructure, and governance capstone over Modules 1–20.
Several of the 21 sections below are therefore framed as "what this module verifies/produces" and
"what does not apply here, and why" rather than a typical feature's flow.

## Grounding note — what a literal reading of the issue would get wrong

Every specialist agent independently surfaced facts by reading the actual repository state, not by
assuming the issue's own framing is already true:

1. **INTG-2's premise is circular: there is nothing to mirror.** The issue asks staging to run "the
   same container images/Compose shape as production." No Dockerfile exists anywhere in this repo
   (backend or frontend), no staging/production Compose file exists, and no Nginx config exists.
   `infrastructure/docker-compose.dev.yml` only runs Postgres/Redis/Mailpit/MinIO for local dev;
   per `infrastructure/CLAUDE.md`, backend and frontend run **natively** in local dev "for faster
   development." `infrastructure/CLAUDE.md` itself says production/staging "**will** run
   containerized applications" — future tense. Building the platform's first-ever
   container/Compose/Nginx topology is therefore the real, unavoidable starting point of INTG-2,
   and because "production deployment strategy" is an explicitly change-controlled area (root
   `CLAUDE.md`), this first-ever decision requires an **Accepted ADR before implementation**, not
   silent authorship inside a story framed as "review/verification only, no code."
2. **APP-3 (Module 1's own CI/infra story) never shipped what its own backlog text asked for.**
   `docs/planning/product-backlog.md`'s APP-3 acceptance criteria describe "Docker Compose brings up
   Postgres, Redis, backend, frontend, Nginx." MVP-001 deliberately shipped a narrower interim
   (containers for Postgres/Redis/Mailpit/MinIO only, backend/frontend native) — a reasonable
   engineering call, but one **never logged in `docs/requirements/open-decisions.md`**, despite that
   file's own established convention of carrying forward exactly this class of "backlog said X,
   shipped Y" deviation for every other module (see its §15–§23). INTG-2 inherits this real,
   previously-undisclosed gap as its actual starting point — "Module 1's APP-3 (CI/infra)" is
   correctly cited in the issue's dependency line, but that dependency is **not yet actually
   satisfied** for what INTG-2 needs.
3. **Backend cross-tenant test coverage is comprehensively real, but not discoverable by the
   issue's own suggested pattern.** All 13 currently-built backend domains
   (`identity-access-service`, `tenant-management`, `user-management`, `course-management`,
   `content-management`, `payment-management`, `ledger-settlement-management`,
   `attendance-management`, `exam-management`, `notification-management`, `integration-management`,
   `audit-log-management`, and `enrollment-management` — the last of which is not named in the
   issue's own domain list at all, despite having 23 test files) have genuine cross-tenant negative
   test coverage. But a naive grep for `CrossTenant`/`AcrossTenants`/`TenantIsolation` — the exact
   pattern an implementer might reach for — would incorrectly conclude `tenant-management`,
   `user-management`, and `course-management` have partial/no coverage, since their tests use names
   like `tenantBAdminGettingTenantAsStudentAccountByIdReturns404NeverTenantAsData` instead. INTG-1's
   "aggregate every cross-tenant test" work requires a **manual, per-domain verification pass**
   against actual test bodies, not a grep, and should end by establishing one consistent naming/
   tagging convention going forward (§21 item 6).
4. **The idempotency acceptance criterion is already fully met — this is a re-run/naming task, not
   new test-writing.** "Duplicate webhook/approval delivery does not double-activate enrollment or
   double-write ledger entries, end to end" is already proven, both sequentially and under genuine
   concurrent load (`CyclicBarrier`-based), for both the webhook and manual-slip paths:
   `PaymentWebhookConcurrencyIntegrationTest#concurrentWebhookDeliveriesForTheSameGatewayReferenceProduceExactlyOnePaymentLedgerEntryAndEnrollment`,
   `PaymentAndLedgerIntegrationTest#aDuplicateWebhookDeliveryForTheSameGatewayReferenceIsIdempotent`,
   `SlipApprovalConcurrencyIntegrationTest#concurrentApproveRequestsForTheSameSlipProduceExactlyOneActivationAndOneAuditRow`
   — all assert **enrollment row state directly**, not just payment/ledger state. INTG-1's job here
   is to name these as the canonical regression-canary set (§18), not write new coverage.
5. **Frontend E2E is 100% single-tenant and fully mocked — the "4 roles × 2 tenants" requirement is
   genuinely new infrastructure, not an extension.** All 39 specs under `frontend/e2e/` use
   `page.route()`-intercepted, hand-constructed fake JWTs (`fixtures/auth-mocks.ts` hardcodes
   `tenant_id: "tenant-1"`); `playwright.config.ts`'s only `webServer` is the Next.js dev server —
   no backend, no database, no seed data. Every file that mentions "cross-tenant" (16 files) does so
   to **disclaim** proving it, explicitly deferring to the backend's own tests (e.g.
   `audit-log.spec.ts`: "Not covered here... that is proven by the backend's
   `AuditLogCrossTenantIntegrationTest`"). There is no `test.extend` fixture, no `storageState`, no
   two-tenant constant anywhere in `frontend/e2e/`. Building real, backend-backed, two-tenant
   Playwright fixtures is a materially larger, genuinely greenfield lift than "wire up existing
   fixtures" (§21 item 2).
6. **One concrete, previously-undetected test gap: `LedgerController`.** The tenant-facing
   `GET /api/v1/ledger/dashboard`/`/history` endpoints have no dedicated test file; their only
   cross-tenant proof is one incidental assertion inside
   `PaymentCrossTenantIntegrationTest#ledgerDashboardNeverLeaksAnotherTenantsEntries`. Not a true
   zero, but thin enough that a future refactor of `PaymentCrossTenantIntegrationTest` could silently
   drop the only isolation proof this controller has. Recommend a dedicated
   `LedgerControllerCrossTenantIntegrationTest` as part of this module's hardening (§9, §18).
7. **`video-access-management` and `reporting-analytics` are entirely unbuilt**, despite
   `docs/requirements/module-catalog.md` listing MVP-scoped baseline requirements for both. This
   bears directly on the issue's own "100% green across all 61 stories" sign-off criterion (INTG-3)
   — whatever stories map to these two domains cannot pass a gate that has no code to run against.
   This module cannot silently treat that as "N/A" — it must be named as a go-live blocker or an
   explicitly human-approved deferral (§21 item 1).
8. **Database/migrations (V1–V35) are fully clean** — every tenant-owned table has `NOT NULL
   tenant_id` + a tenant-leading index, append-only enforcement is confirmed at the application
   layer (plus a genuine DB trigger for `audit_log` actor/tenant-match integrity, `V33`/`V34`), no
   shared/global multi-tenant-row table lacks a `tenant_id` discriminator, and migration numbering
   has no gaps or renumbering. This confirms the issue's own "no new migration" claim. One minor doc
   drift found: `docs/architecture/database-architecture.md`'s `V31` section doesn't reflect `V35`
   later dropping one of `V31`'s three indexes as unused.
9. **A defense-in-depth inconsistency, not a proven vulnerability.** `attendance-management` and
   `exam-management`'s native-query repository methods that take an explicit `tenantId` parameter
   are backed by a runtime `assertTenantIdMatchesContext` guard (throwing `CrossTenantPersistenceException`
   on mismatch); the structurally identical `findByIdAndTenantIdForUpdate` methods in
   `PaymentRepository`, `PaymentSlipRepository`, and `ReactivationRequestRepository` have no such
   guard — every current call site happens to pass the correct value, but nothing would catch a
   future mistake mechanically. Worth closing during this module's hardening pass (§9, §15).
10. **The audit-row-written "regression canary" cannot be generically claimed for gateway-webhook
    payment confirm/reject.** `docs/requirements/open-decisions.md` §2 records that
    `PaymentConfirmedEvent`/`PaymentRejectedEvent` (real gateway webhook paths) were deliberately
    never wired to `audit-log-management` in MVP-019 — no `tenant_user` actor exists for a
    webhook-driven action, and `audit_log.actor_id` is `NOT NULL`. The canary must therefore be
    scoped explicitly to the manual-slip-approval and refund paths (which **are** audit-wired), with
    an explicit, disclosed exception (not a silent gap) for gateway webhook confirm/reject pending
    that decision (§17, §21 item 3).

None of these ten facts are resolved by this plan — they are named so implementation does not
silently assume an easier starting state than actually exists.

## 1. Business goal

Provide the platform's final quality and release gate before any production go-live decision:

- **INTG-1** — a consolidated, Testcontainers-backed, cross-tenant and idempotency verification
  pass across every built backend domain and a genuinely new two-tenant Playwright E2E layer,
  proving (not merely documenting) that every tenant-owned data path is isolated and every
  payment/ledger/enrollment mutation path is idempotent under concurrent/duplicate delivery.
- **INTG-2** — stand up, for the first time, a staging deployment that runs the platform's
  first-ever defined container/Compose/Nginx topology against isolated, synthetic-data-only
  Postgres/Redis, with Flyway migrations applied as part of deploy, stateless horizontally-scaled
  app instances, and a smoke test proving the deployed system actually works end-to-end.
- **INTG-3** — a human-gated Definition-of-Done and ADR-linkage sign-off confirming every module
  (1–20) meets its own DoD, every change-controlled area touched has a linked Accepted ADR, and
  every item in `docs/requirements/open-decisions.md` is either resolved-with-a-decision-record or
  knowingly, explicitly deferred — never silently dropped.

This module introduces no new product functionality, no new business entity, and no new
user-facing feature. Its deliverable is verification evidence, a staging environment, and a
governance decision record.

## 2. Roles and permissions

There is no new end-user-facing role or permission grant in this module. The relevant actors are
process roles, not application roles:

| Actor | INTG-1 | INTG-2 | INTG-3 |
|---|---|---|---|
| **Implementing engineer/agent** | Writes/tags/re-runs tests; must not invent new business logic to make a test pass. | Authors Dockerfiles/Compose/Nginx config (or defers to `devops-engineer`); never triggers a production deploy. | Compiles the DoD/ADR/open-decisions checklist; must not self-approve go-live. |
| **Human product/engineering owner** | Reviews and accepts the consolidated suite's scope (e.g. which domains, whether video-access-management/reporting-analytics gaps block go-live). | Approves the staging topology ADR before it is built; approves promotion to staging. | **Sole approver of go-live** — root `CLAUDE.md`: "Never merge a pull request without human approval," "Never deploy production automatically." This module must never self-approve. |
| **All existing application roles** (Student, Teacher, Tenant Admin + sub-roles, Platform Admin) | Used only as seeded fixture identities inside tests (real login, real tokens) — no permission model change. | Same — smoke test logs in as each of the 4 top-level portal roles × ≥2 seeded tenants (`docs/requirements/user-roles-and-permissions.md` §1: Student, Teacher, Tenant Admin, Platform Admin are the 4 top-level portal actors; the 7 Tenant Admin staff sub-roles share the `(tenant-admin)` route group and are a narrower, separate concern — see §6). | N/A — review only. |

No `DomainArea`/`Role`/`PermissionCheckService` change is introduced anywhere in this module.

## 3. Preconditions

**Shared / structural**
- Modules 1–20 substantially complete (per the issue's own INTG-1 dependency) — largely true, with
  the two named exceptions in Grounding note item 7.
- `docs/requirements/open-decisions.md` exists and is the authoritative running log this module
  cross-checks against (confirmed current, 23 sections, actively maintained through MVP-020).

**INTG-1-specific**
- Every domain's existing test suite passes today (`backend\mvnw.cmd verify`, `npx playwright test`)
  — this plan does not attempt to fix any currently-failing test as part of scoping; a failing
  baseline would itself be a go-live blocker discovered by, not fixed by, this module unless the
  fix is trivial and undisputed.
- **Not met yet**: no consolidated/tagged view of which tests count as the "cross-tenant/idempotency
  canary set" exists (Grounding note item 3) — this module's first deliverable.
- **Not met yet**: no real-backend, two-tenant Playwright fixture harness exists (Grounding note
  item 5).

**INTG-2-specific**
- **Not met**: no Dockerfile (backend or frontend) exists. **Not met**: no staging/production
  Compose file exists. **Not met**: no Nginx config exists. **Not met**: no Accepted ADR exists for
  a container/deployment topology (none has ever been needed, since none existed).
- `/actuator/health` is already exposed, unauthenticated, and tested
  (`ActuatorHealthIntegrationTest`) — directly reusable by the smoke test once staging exists.
- Backend code is evidence-consistent with stateless/horizontal-scaling readiness today:
  `SecurityFilterChainConfig` sets `SessionCreationPolicy.STATELESS`; the only two cache-adjacent
  classes (`CacheConfig`, `DeviceSessionCacheService`) both use Redis, not in-JVM state, as backing
  store, with Postgres as "sole authority." No `@SessionScope` or in-memory authoritative cache
  found anywhere in `backend/src/main/java`.

**INTG-3-specific**
- Hard-blocks on INTG-2 (per the issue) — cannot sign off go-live before staging exists and passes
  its smoke test.
- Every prior MVP-0xx plan file already contains its own "carried-forward decisions" trail into
  `open-decisions.md` — this module's job is to confirm that trail is complete and current, not
  rebuild it from scratch.

## 4. User flows

This module has no student/teacher/tenant-admin/platform-admin-facing UI flow. "Flows" here are
process flows for the implementing engineer and the human approver.

### 4.1 INTG-1 — consolidated cross-tenant/idempotency verification

1. Build a per-domain inventory of every existing cross-tenant negative test and idempotency/
   concurrency test (already largely done by this plan's own audit — see §18) by reading actual test
   bodies, not filenames.
2. Tag the confirmed set with a consistent JUnit 5 `@Tag` (e.g. `@Tag("cross-tenant")`,
   `@Tag("idempotency")`) so the set is mechanically re-discoverable going forward, closing Grounding
   note item 3's discoverability gap.
3. Close the one confirmed real gap: add a dedicated cross-tenant test for `LedgerController`
   (Grounding note item 6).
4. (Recommended hardening, not a hard gate) Add `assertTenantIdMatchesContext`-style guards to
   `PaymentRepository`/`PaymentSlipRepository`/`ReactivationRequestRepository`'s
   `findByIdAndTenantIdForUpdate` methods, mirroring the existing attendance/exam pattern (Grounding
   note item 9).
5. Build the genuinely new two-tenant Playwright fixture layer: a real, seeded backend + database
   reachable from Playwright (either the newly-stood-up staging environment from INTG-2, or a local
   docker-compose-backed stack — an explicit open decision, §21 item 2), a `test.extend`-based
   authenticated fixture parameterized by role × tenant, and cross-tenant E2E negative specs for the
   ~15 feature areas that already have a backend cross-tenant test but no Playwright equivalent
   (§18).
6. Run the full backend suite (`backend\mvnw.cmd verify`) and full Playwright suite
   (`npx playwright test`) together; confirm 100% green with the tagged cross-tenant/idempotency
   subset explicitly enumerated in the run output/report.

### 4.2 INTG-2 — staging deployment + smoke test

1. **Gate**: an Accepted ADR exists for the platform's first container/deployment topology
   (backend Dockerfile, frontend Dockerfile, staging Compose shape, Nginx routing) — required before
   step 2, since this is the first-ever such decision and is change-controlled (Grounding note item
   1). This plan does not pre-approve that ADR's content; it only names what it must decide (§9.4).
2. Author the backend Dockerfile (multi-stage Maven build → JRE 21 runtime), frontend Dockerfile
   (Next.js production build), `docker-compose.staging.yml` (Nginx + N backend replicas + frontend +
   isolated Postgres + isolated Redis), and Nginx reverse-proxy config, per the ADR's decisions and
   consistent with `docs/architecture/deployment-architecture.md` §2's intended topology.
3. Deploy to staging: Flyway migrations run as part of the backend container's startup (never a
   separate manual step against the staging DB), seeded with synthetic tenant data only — never real
   student/financial records, per root `CLAUDE.md` Safety rules.
4. Run the smoke-test suite: login succeeds for all 4 top-level role fixtures × ≥2 seeded tenants;
   core read paths return `200` with expected shape; `/actuator/health` reports `UP`; a basic
   cross-tenant read/write check confirms structural isolation holds against the deployed instance,
   not just the Testcontainers-backed unit-of-work.
5. Confirm staging never connects to or seeds from production data/credentials (there is currently
   no production environment to connect to, but the isolation must still be verified as a structural
   property of the staging config itself, e.g. distinct `.env.staging` with its own DB/Redis
   endpoints, never inherited from any `.env.dev`/prod-shaped file).

### 4.3 INTG-3 — go-live readiness review

1. For each of Modules 1–20, confirm its own plan's Definition-of-Done is met (tests green,
   documentation updated, tenant-isolation/security review complete) — reusing each module's own
   plan file as the checklist source, not re-deriving criteria.
2. For every change-controlled area touched across the whole build (multi-tenancy strategy,
   authentication architecture, payment ledger rules, enrollment activation rules, production
   deployment strategy, database migration history, approved API contracts), confirm a linked
   Accepted ADR exists — including the new deployment-topology ADR from §4.2 step 1.
3. Walk `docs/requirements/open-decisions.md` in full; for every item, confirm it is either (a)
   resolved, with a decision record added, or (b) explicitly, knowingly deferred with a stated
   reason — never silently absent from the go-live record. This module does **not** resolve any
   product/vendor/business-judgment item itself (§21 item 1) — it only forces the resolved-or-deferred
   determination to be made and recorded by a human.
4. Re-run `backend\mvnw.cmd verify` and `npx playwright test` one final time; confirm 100% green,
   zero skipped cross-tenant tests.
5. Human approver reviews the compiled checklist and either approves go-live (recorded, dated,
   named) or names specific blockers — this module's implementing engineer/agent must never mark
   this step as passed on its own authority.

## 5. Acceptance criteria

Restated from the issue, tightened per the specialist audits' findings (§ references point to where
each tightening originates):

### INTG-1
1. Every tenant-owned endpoint/repository method/query across all 13 currently-built domains has a
   confirmed, tagged cross-tenant negative test — verified by reading actual test bodies, not by
   name-pattern grep alone (Grounding note item 3).
2. Persistence-touching tests use Testcontainers-backed real Postgres/Redis; no mocked repository is
   accepted as isolation proof — already true for every existing test surveyed.
3. The webhook/slip-approval idempotency criterion (no double-activation of enrollment, no
   double-write of ledger entries, end to end) is satisfied by re-running and explicitly naming the
   four already-passing tests identified in Grounding note item 4 — not by writing new tests.
4. `LedgerController`'s cross-tenant isolation gains its own dedicated test, closing Grounding note
   item 6.
5. Playwright gains a genuinely new, real-backend, two-tenant authenticated fixture layer (4 roles ×
   ≥2 tenants), and cross-tenant E2E negative specs exist for every feature area that already has a
   backend cross-tenant test (~15 areas, currently 0-of-15 per the ui-ux/qa audits).
6. The audit-log-row-written regression canary is explicitly scoped to manual-slip-approval and
   refund paths; gateway-webhook confirm/reject is named as a disclosed, out-of-scope exception
   pending `open-decisions.md` §2's resolution (Grounding note item 10) — never silently generalized
   or silently dropped.
7. Any per-module cross-tenant test found missing during this pass is filed as a follow-up
   story/PR against its **owning** module, never patched inline inside INTG-1's own commit
   (preserves "that module's story is incomplete, not a defect of this suite").

### INTG-2
8. An Accepted ADR documenting the platform's first container/deployment topology exists and is
   linked before staging deploy proceeds (Grounding note item 1).
9. Staging runs the topology that ADR decided — isolated Postgres/Redis, synthetic data only, Flyway
   migrations applied as part of deploy (no already-applied migration edited/renumbered), stateless
   horizontally-scaled app instances behind Nginx, no sticky sessions.
10. Smoke test: login for all 4 top-level role fixtures × ≥2 seeded tenants succeeds; core read paths
    return `200` with expected shape; `/actuator/health` reports `UP`; a basic cross-tenant
    read/write check passes against the deployed instance.
11. Smoke test exercises at least one full flow per **actually-built** MVP-0xx module (Grounding note
    item 7 — not the full 18-domain catalog, several of which have zero MVP-phase code).
12. Staging never connects to or seeds from production data/credentials; staging-scoped secrets only
    (a new `.env.staging.example`, following the existing `.env.dev.example` precedent); no automatic
    production-deploy step exists or is triggered.

### INTG-3
13. Definition-of-Done checklist confirmed complete per module (or an explicit, named exception is
    recorded for Modules whose DoD cannot be fully met, e.g. video-access-management/
    reporting-analytics's absence — Grounding note item 7).
14. Every change-controlled area touched (including the new deployment-topology ADR) has a linked
    Accepted ADR.
15. Every `open-decisions.md` item is resolved-with-a-record or knowingly deferred — this module
    forces the determination, never invents the resolution for a product/vendor/business-judgment
    item (§21 item 1).
16. Final full re-run of `backend\mvnw.cmd verify` and `npx playwright test` is 100% green with zero
    skipped cross-tenant tests.
17. Production deployment approval is explicit, human, dated, and named — never automated, never
    self-approved by the implementing agent.

### Cross-cutting
18. This module introduces no new business logic, no new entity, no new migration (beyond the
    already-confirmed "none needed" database finding, §8), and no new `DomainArea`/`Role`/
    `PermissionCheckService` change.

## 6. Out-of-scope items

- **Resolving any product/business/vendor decision named in `docs/requirements/open-decisions.md`**
  (payment gateway selection, grace period length, SMS/WhatsApp provider, refund-window policy,
  settlement cadence, teacher/student registration model, Teacher Assistant's permission boundary,
  etc.) — this module forces resolution-or-deferral to be recorded, never invents an answer (§21
  item 1).
- **Building `video-access-management`, `live-class-management`, `finance-expense-management`,
  `reporting-analytics`, or `support-management`** — these are either not-yet-built MVP-scoped gaps
  (the first two named explicitly in Grounding note item 7) or correctly Phase 2/3 scope; this
  module names the gap, it does not close it.
- **Choosing a cloud/hosting provider, CI/CD pipeline tool, DNS/certificate-management approach, or
  secrets-management mechanism** — all explicitly left open by `docs/architecture/deployment-architecture.md`
  §6; this module's staging topology work is scoped narrowly to "what staging's own container/
  Compose/Nginx artifacts look like," not any of these broader infra decisions.
- **A CI job that auto-builds container images or auto-deploys to staging/production** — whether
  staging deploy becomes a CI-triggered step or stays a manual, human-run `devops-engineer` action is
  an explicit open decision (§21 item 4), not decided or built here.
- **Settlement-run idempotency testing** — `ledger-settlement-management`'s Phase 2 settlement-run
  code does not exist yet; nothing to verify (confirmed by the payment-ledger-specialist audit).
  Building it is not this module's job.
- **A new payment gateway integration, or any real third-party webhook signature verification** — the
  idempotency tests exercise the platform's own internal webhook contract only; this is a disclosed
  limitation (§17), not a gap this module closes.
- **Rewriting or renumbering any existing Flyway migration** — none is needed (§8); if any genuine
  gap were found, it would be a new, additive migration, never an edit.
- **A TLS/certificate strategy for staging beyond what the topology ADR names** — flagged as an open
  decision (§21 item 5), not defaulted here.
- **Fixing any individual module's own incomplete DoD inline inside this module's commits** — per
  acceptance criterion 7/13, gaps are filed as follow-ups against their owning module.

## 7. Domain model

No new business-domain aggregate, entity, or value object in any of the 18 backend domains. This
module's only new "domain objects" are process/infrastructure artifacts:

- **Test taxonomy**: a `@Tag("cross-tenant")`/`@Tag("idempotency")` convention applied to existing
  JUnit test methods (tagging, not new domain code) — see §9.
- **Infrastructure artifacts** (not part of any `com.lms.*` package, per §9.4's module-boundary
  note): `backend/Dockerfile`, `frontend/Dockerfile`, `infrastructure/docker-compose.staging.yml`,
  an Nginx config file, `infrastructure/.env.staging.example`.
- **A new ADR** documenting the deployment-topology decision (next available number after
  ADR-014).
- **A go-live checklist document** (e.g. `docs/planning/go-live-checklist.md`) — a governance
  artifact, not a domain model.
- **Playwright fixture infrastructure**: a `test.extend`-based authenticated-session fixture
  parameterized by role and tenant, plus a two-tenant seed-data mechanism for the target environment
  — new test-support code, not production domain code.

## 8. Database design

**No new migration is required.** Confirmed independently by the database-architect review against
all 35 existing migration files (`V1`–`V35`):

- Every tenant-owned table has `NOT NULL tenant_id` with a `FOREIGN KEY` to `tenant`, and a
  tenant-leading composite index matching its real query shape — no gap found.
- Every genuinely platform-level table (`tenant`, `platform_admin_user`, `platform_admin_session`,
  `role`) is correctly excluded from `tenant_id`, each with a header comment justifying the
  exclusion — confirmed correct by design, not an oversight.
- No shared/global table holding multi-tenant rows without a `tenant_id` discriminator exists.
- Append-only enforcement for `payment`, `ledger_entry`, `payment_slip`, and `audit_log` is
  confirmed at the **application layer** (every delete-shaped Spring Data repository method
  overridden to throw `UnsupportedOperationException`) — no schema-level `DELETE` is blocked by the
  migrations themselves, consistent with prior modules' documented approach. `audit_log` additionally
  has a genuine DB-level trigger (`trg_audit_log_actor_must_exist`, tightened by `V33`/`V34`) enforcing
  actor-existence and actor/tenant-match integrity — the one place a real trigger backstops an
  invariant a single FK couldn't express (polymorphic `actor_id`).
- Two intentional, documented global (non-tenant-scoped) unique constraints exist and are correct by
  design: `tenant.subdomain` (platform-wide DNS/routing namespace) and
  `payment.gateway_reference` (externally-issued, platform-wide gateway identifier, resolved
  across all tenants by the webhook-confirmation lookup path per ADR-011). These should be
  explicitly named on the go-live sign-off checklist as reviewed exceptions, not flagged as new
  findings.
- Migration numbering (`V1`–`V35`) has no gaps, duplicates, or renumbering.

**One documentation-only correction recommended** (not a schema change): update
`docs/architecture/database-architecture.md`'s `V31` section to note that `V35` later dropped
`idx_payment_created_at_tenant` as unused once the payment dashboard shipped ledger-derived-only
(§19).

**One infrastructure-config item flagged for staging sign-off, not a migration gap**: the
architecture doc's claim that production DB-role privileges (not granting `DELETE`) act as a second,
belt-and-suspenders append-only control cannot be verified from migration files alone — this should
be confirmed against the actual staging/production DB-role GRANT configuration once §4.2's
infrastructure work exists, and named explicitly on the INTG-3 checklist.

## 9. Backend design

Per `.claude/rules/architecture.md`'s module-boundary rules, this module makes **no change to any
`com.lms.*` production package**. Its backend-adjacent work is entirely test-layer and
infrastructure-layer, kept structurally separate:

### 9.1 Test-layer work (inside `backend/src/test/java/com/lms/**`, no production code touched)

- Apply a consistent `@Tag("cross-tenant")` / `@Tag("idempotency")` annotation to the confirmed set
  of existing test methods (per-domain inventory already compiled by this plan's audit, §18) — no
  new JUnit `@Suite`/aggregating class is required, since `backend\mvnw.cmd verify` (Surefire/
  Failsafe) already runs every test class in one build, mechanically satisfying "run together to
  catch cross-module regressions." The tagging exists purely to make the canary subset
  **mechanically re-discoverable** (e.g. via a Maven Surefire tag-filtered profile) for the
  INTG-3 sign-off re-run, closing Grounding note item 3's discoverability gap.
- Add one new dedicated test class, `LedgerControllerCrossTenantIntegrationTest` (or equivalent),
  proving a tenant-A actor cannot read tenant-B's `GET /api/v1/ledger/dashboard`/`/history` rows —
  closing the one confirmed real gap (Grounding note item 6). This is additive test code only; no
  production `LedgerController`/`LedgerQueryService` logic changes.
- **Recommended hardening** (flagged, not a hard gate — no proven vulnerability exists today):
  add an `assertTenantIdMatchesContext`-style runtime guard to
  `PaymentRepository#findByIdAndTenantIdForUpdate`,
  `PaymentSlipRepository#findByIdAndTenantIdForUpdate`, and
  `ReactivationRequestRepository#findByIdAndTenantIdForUpdate`, mirroring the existing
  `AttendanceRecordRepository`/exam-repository pattern — this is a small, narrow production-code
  change (a defensive assertion, not new business logic) and should be called out as its own
  separate, reviewable commit if the coordinator decides to include it in this module rather than
  defer it.

### 9.2 Infrastructure-layer work (outside `com.lms.*`, `devops-engineer` territory per its own agent
definition — "Use to manage Docker, Docker Compose, Nginx, CI/CD, and operational scripts")

- `backend/Dockerfile` — multi-stage Maven build → JRE 21 runtime image.
- `frontend/Dockerfile` — Next.js production build/run image.
- `infrastructure/docker-compose.staging.yml` — `nginx`, `backend` (N replicas), `frontend`,
  staging-isolated `postgres`, staging-isolated `redis`. No managed-cloud substitution is assumed —
  `deployment-architecture.md` §6 leaves managed-vs-self-hosted Postgres/Redis explicitly
  undecided, so containerized Postgres/Redis is the only currently-authorized shape for this
  module's own scope.
- Nginx reverse-proxy config — single entry point, routes to `frontend` for page routes and
  `backend` for API routes, per `deployment-architecture.md` §2.
- `infrastructure/.env.staging.example` — following the existing `.env.dev.example` pattern
  (placeholders only, never real secrets).
- Flyway migrations run as part of the backend container's own startup/deploy step, never as a
  separate manual step against the staging database, per `deployment-architecture.md` §5.

This split matters because the issue's own "Backend requirements: no new production code" language
must not be read as also prohibiting this infrastructure work — a Dockerfile/Compose/Nginx config
touches no `com.lms.*` package, adds no REST endpoint/entity/repository, and does not violate any
modular-monolith boundary; it is a distinct, `devops-engineer`-owned concern.

### 9.3 Frontend E2E fixture layer (test-support code, no `frontend/src/app` production route/page
change required)

- A `test.extend`-based authenticated-session fixture under `frontend/e2e/fixtures/`, parameterized
  by role (Student/Teacher/Tenant Admin/Platform Admin) and tenant, performing a real login against
  a real, seeded backend (the newly-stood-up staging environment, or a local docker-compose-backed
  stack reachable from Playwright — an explicit open decision, §21 item 2).
- A two-tenant seed-data mechanism (script or fixture) providing at least two distinct tenants with
  representative data across the built feature areas.
- New cross-tenant E2E negative specs for the ~15 feature areas identified in §18 that already have
  a backend cross-tenant test but currently zero corresponding Playwright coverage.

### 9.4 Module-boundary / change-control checkpoints

- No domain's `api`/`repository`/`domain`/`web` package is modified except the narrow, optional
  hardening in §9.1 (a defensive guard, not new business logic).
- The deployment-topology ADR (§4.2 step 1) must explicitly decide: base image choice/JRE
  distribution and Node/Next.js runtime mode; whether staging and production share one
  parameterized Compose file or deliberately separate files; whether Postgres/Redis stay
  containerized Compose services for staging only (without resolving §6's still-open
  managed-vs-self-hosted question for production); the secrets-injection mechanism for container
  env vars; and Nginx TLS/certificate handling in staging. It must **not** resolve
  `deployment-architecture.md` §6's broader open questions (cloud provider, CI/CD tooling, DNS/cert
  automation, horizontal-scaling automation) — those remain explicitly out of scope (§6).

## 10. API contract

**No new API endpoint is introduced by this module.** `/actuator/health` (already shipped, already
tested by `ActuatorHealthIntegrationTest`) is the only endpoint the smoke test relies on, and it
requires no change. This module does not add, remove, or modify any request/response shape, status
code, or error format for any existing endpoint — its API-contract obligation (per the issue's
Documentation requirements) is purely to **confirm** every existing `docs/api/*.md` file is
accurate against current shipped behavior, and to name explicitly any drift found (none was found
by this review beyond the minor `database-architecture.md` V31/V35 note in §8, which is an
architecture doc, not an API contract doc).

## 11. Frontend screens

**No new user-facing screen or route.** This module's only frontend-adjacent deliverable is test
infrastructure (§9.3) — no page, component, or route under `frontend/src/app/` is added or changed.
The existing 39 Playwright specs and their state-coverage maturity (loading/empty/error/
permission-denied) were assessed as generally strong for what's already built
(`platform-admin-payments.spec.ts` and `audit-log.spec.ts` were cited by the ui-ux-reviewer audit as
particularly thorough examples), but this maturity has so far only been proven against **mocked**
responses — a real-backend staging smoke test may surface issues (real Host-header/subdomain tenant
resolution, real Spring Security 401/403 responses, real pagination envelope shape) that 39 passing
mocked specs have never actually exercised. This is named as a real risk (§21 item 7), not assumed
away by "the mocked tests already pass."

One frontend documentation/behavior mismatch to reconcile as part of this module's review, not a new
feature: `route-groups.spec.ts`'s own code comment shows a prior plan's expected behavior
("sees a permission-denied state") doesn't match the actual shipped `RouteGuard` behavior (a
client-side redirect to `<role>/login?reason=session_expired`) — the plan should confirm during
implementation which behavior the staging smoke test and any new cross-tenant E2E specs should
assert against (§21 item 8).

## 12. Validation rules

No new user-input validation rule is introduced (no new form, no new request DTO). The validation
concerns for this module are test/config correctness, not business-rule validation:

- Any new Playwright fixture must fail loudly (not silently pass) if the target environment is
  unreachable or a seeded tenant/role fixture is missing — never fall back to a mocked response
  inside a test explicitly labeled "cross-tenant E2E," since that would silently reintroduce the
  exact gap this module exists to close.
- The staging Compose/Nginx config must fail startup (not silently degrade) if a required
  environment variable (`.env.staging`) is missing — mirroring `infrastructure/CLAUDE.md`'s existing
  `.env.dev` convention ("or containers start with blank credentials" is explicitly named there as
  the failure mode to avoid).
- The go-live checklist (INTG-3) must not be markable "complete" with any unresolved item silently
  left blank — every `open-decisions.md` entry needs an explicit resolved/deferred marker, not an
  absence.

## 13. Error cases

| Case | Expected behavior |
|---|---|
| A domain's existing cross-tenant test is found to be missing or broken during INTG-1's audit | Filed as a follow-up story/PR against that domain's **owning** module; INTG-1 does not patch it inline, and does not silently mark itself "done" while the gap exists — it is reported as a go-live blocker until closed or explicitly waived. |
| Staging deploy fails a migration mid-rollout | Deploy halts; no partial-migration staging environment is left running; Flyway's own failure semantics apply (no already-applied migration is edited to "fix" a failure — a corrective migration is new, additive, and reviewed like any other). |
| Smoke test finds a role/tenant combination that fails login or returns unexpected shape | Staging promotion is blocked; this is treated as a genuine defect in the underlying module, not something INTG-2 patches around. |
| `open-decisions.md` item cannot be resolved or explicitly deferred by any human before go-live | Go-live is blocked on that item — INTG-3 does not proceed with an unmarked item, and does not invent a resolution to unblock itself. |
| Implementing agent is asked (explicitly or implicitly) to approve production deployment | Refused — root `CLAUDE.md`: "Never deploy production automatically," "Never merge a pull request without human approval." Only a named human may record go-live approval. |
| A Playwright cross-tenant spec cannot reach a real backend (environment not yet available) | The spec is not silently rewritten back to a mocked assertion — it is marked pending/skipped with an explicit, visible reason, never counted toward "100% green" until real coverage exists. |
| Video-access-management/reporting-analytics stories are found to have no code to test at INTG-3 | Named explicitly as a go-live blocker or an explicit, human-recorded deferral — never silently omitted from the 61-story accounting. |

## 14. Tenant-isolation rules

This module's own tenant-isolation obligation is entirely **verification**, not new enforcement
code (beyond the one recommended hardening item in §9.1):

- Every tenant-owned endpoint/repository/query across the 13 built domains must have a confirmed,
  now-tagged cross-tenant negative test — verified against actual test bodies (§18), not assumed
  from a domain's reputation or a naive name-pattern search (Grounding note item 3).
- No repository method in any domain accepts a caller-supplied `tenant_id` outside the two already-
  reviewed, explicitly-sanctioned platform-admin drill-down exceptions
  (`PlatformAdminAuditLogController`, `PlatformAdminLedgerController`, both gated by
  `hasRole('PLATFORM_ADMIN')` and independently tested) — confirmed by a repository-wide grep during
  the security-reviewer audit; no other instance found.
- The staging smoke test's cross-tenant read/write check is a **structural sanity check on the
  deployed instance**, distinct from and in addition to the Testcontainers-backed unit-of-work
  proofs — a passing Testcontainers suite does not by itself prove the deployed, containerized,
  Nginx-routed instance enforces isolation identically (e.g. real Host-header-based tenant
  resolution has never been exercised end-to-end by any existing test, per §11).
- The `assertTenantIdMatchesContext` defense-in-depth pattern (currently only on
  attendance/exam native-query repositories) is recommended, not mandated, to extend to
  `PaymentRepository`/`PaymentSlipRepository`/`ReactivationRequestRepository`'s
  `findByIdAndTenantIdForUpdate` methods (§9.1) — flagged as closing a real inconsistency, though no
  current call site is actually wrong.
- The go-live checklist's own tenant-isolation line item must explicitly re-confirm: (a) every
  tenant-owned table's `NOT NULL tenant_id` + tenant-leading index (§8, already verified clean),
  (b) no migration introduced a shared/global multi-tenant-row table without a discriminator (§8,
  none found), (c) no repository method accepts a caller-supplied `tenant_id` beyond the two
  sanctioned exceptions (this section, none found).

## 15. Security rules

1. **This module is the verification backstop for every tenant-isolation claim made across Modules
   4–20** — per the issue's own framing, a review that looks correct but has no accompanying
   cross-tenant test is treated as isolation being **unverified**, not isolation being present. This
   plan's own audit already performed that verification for the 13 built domains (§18) and found
   comprehensive real coverage — the remaining work is tagging/discoverability and closing the one
   `LedgerController` gap, not building isolation from scratch.
2. **No implicit trust in the deployed staging environment's isolation** until the smoke test's own
   cross-tenant read/write check passes against the real, containerized, Nginx-routed instance — a
   green Testcontainers suite is necessary but not sufficient proof for the deployed shape.
3. **Staging secrets isolation is a genuine open design gap, not a verified control today.** No
   `.env.staging`/`.env.staging.example` exists; no staging/production deploy job exists in CI at
   all (`.github/workflows/ci.yml` has exactly two jobs: backend test, frontend build+test — no
   deploy step). The issue's "no automatic production deploy triggered" requirement is trivially
   true today only because no deploy pipeline exists yet, not because it was deliberately gated —
   this must not be reported as "already satisfied" on the go-live checklist without the new,
   explicit human-gated deploy mechanism this module builds (§9.2).
4. **No secret, credential, or production data was found anywhere in code, config, tests, or
   fixtures** during this review (security-reviewer audit, explicit check) — this module's new
   `.env.staging.example` must follow the same placeholder-only pattern as `.env.dev.example`.
5. **No change to authentication architecture or multi-tenancy strategy** is introduced — the only
   two prior exceptions to the standard tenant-resolution pattern (ADR-007's device/token mechanism,
   ADR-011's webhook tenant-resolution carve-out) remain unchanged, both properly Accepted and
   dated.
6. **The one recommended hardening item (§9.1, §14)** — extending `assertTenantIdMatchesContext` to
   three more repository methods — closes an inconsistency, not a proven vulnerability; it must not
   be characterized as "fixing a security bug" if included, since no exploitable path currently
   exists.
7. **No production deployment step may be automated or self-approved.** Root `CLAUDE.md`: "Never
   deploy production automatically," "Never merge a pull request without human approval." This
   applies to both the staging-promotion step (INTG-2) and the final go-live approval (INTG-3) —
   both require an explicit, named, dated human action, never an agent's own sign-off.
8. **The deployment-topology ADR must precede any Dockerfile/Compose/Nginx authorship** (§4.2 step
   1) — building infrastructure first and writing the ADR retroactively would repeat the exact
   "undisclosed schema/design change" governance gap flagged during MVP-020's post-review addendum.

## 16. Audit requirements

- **No new audited action is introduced.** This module writes no new mutation path, so no new
  `.claude/rules/security.md` mandatory-audit-list entry applies.
- **The audit-log-row-written regression canary is scoped explicitly**, per Grounding note item 10:
  manual-slip-approval (`SlipOverrideAuditIntegrationTest`, `SlipApprovalConcurrencyIntegrationTest`)
  and refund (`PaymentAndLedgerIntegrationTest`'s refund cases) paths are re-run as canaries, since
  both are genuinely audit-wired. Gateway-webhook confirm/reject (`PaymentConfirmedEvent`/
  `PaymentRejectedEvent`) is named as a **disclosed, out-of-scope exception** — no audit wiring
  exists there by design (`open-decisions.md` §2), and this module does not silently claim canary
  coverage it doesn't have, nor does it resolve the underlying actor-gap decision.
- **`audit_log`'s append-only guarantee is confirmed tested in two places**:
  `AuditLogRepositoryAppendOnlyTest` (seeded-row proof) and
  `PlatformAuditLogRepositoryPathAppendOnlyTest` (no-`TenantContextHolder`-set path, mirroring the
  real platform-admin request shape) — both re-run as part of this module's regression pass, no new
  test needed here.
- One process note, not a defect: `AppendOnlyRepositoriesStructuralIntegrationTest` (in
  `payment-management`) reaches directly into `audit-log-management`'s `repository` package to
  re-assert the same guarantee — cross-module test-only access that would be disallowed for
  production code under `.claude/rules/architecture.md`. Not a go-live blocker (coverage is
  correct and duplicated, not missing), but worth a one-line housekeeping note in this module's
  documentation pass (§19) rather than silently ignored.
- The go-live checklist's audit-log line item confirms: append-only enforcement holds (re-verified,
  not newly built), no update/delete endpoint or repository method targets `audit_log`, and every
  canonical mandatory-audit action that is currently wired has a passing row-written test.

## 17. Payment impact

**Purely verification — no new ledger entry type, no new payment/order/refund/slip mutation path,
no schema change, no settlement logic touched.**

- The idempotency criterion this module cares about most — "duplicate webhook/approval delivery does
  not double-activate enrollment or double-write ledger entries, end to end" — is **already met**,
  proven both sequentially and under genuine concurrent load, for both the gateway-webhook and
  manual-slip paths, with direct assertions on `enrollment` row state (not just `payment`/
  `ledger_entry` state): `PaymentWebhookConcurrencyIntegrationTest`, `PaymentAndLedgerIntegrationTest`,
  `SlipApprovalConcurrencyIntegrationTest`, `SlipOverrideAuditIntegrationTest`. This module's job is
  to name and re-run these four as the canonical regression-canary set (§18), not write new coverage.
- Append-only enforcement for `payment`/`ledger_entry`/`payment_slip` is confirmed at the repository
  layer (every delete-shaped method throws) and re-verified structurally by
  `AppendOnlyRepositoriesStructuralIntegrationTest` — no gap.
- `ledger-settlement-management`'s Phase 2 settlement-run idempotency requirement
  (`.claude/rules/payments.md` §5) has nothing to verify yet — no settlement-run code exists in
  `com.lms.ledgersettlementmanagement` beyond the ledger-entry read model. Correctly out of scope,
  not a gap.
- The idempotency tests exercise the platform's own internal webhook contract only — no real
  third-party payment gateway is selected yet (`open-decisions.md` §7/§9), so this is a disclosed
  limitation: full webhook-signature/shape idempotency against a real gateway will need
  re-verification once one is chosen. This module does not select a gateway.
- `open-decisions.md` §17's still-open "approver precedence for refunds and manual payment slips"
  (Finance Staff vs. Tenant Admin, both hold approve rights, no documented precedence) is named as
  still-open, not resolved here — current shipped behavior (first-reviewer-wins via row lock, no
  implicit precedence assumed in code) is not a defect, just an unresolved product question.
- One documentation-accuracy note found: `open-decisions.md` §17's cross-reference to ADR-010 says
  "Proposed, not yet formally accepted," but ADR-010's own Status line says "Accepted (2026-08-23)"
  — a stale cross-reference to correct as part of this module's documentation pass (§19), not a
  payment-logic change.

## 18. Tests

This section **is** the module's core deliverable for INTG-1. Reuses
`com.lms.common.AbstractIntegrationTest` (Testcontainers Postgres/Redis) and each domain's existing
tenant fixtures — no new Testcontainers infrastructure is needed for the backend side.

### 18.1 Backend — confirmed existing coverage to tag as the canary/regression set

**Cross-tenant negative tests, confirmed present and correct per-domain** (tag
`@Tag("cross-tenant")`):
`CrossTenantTokenClaimIntegrationTest`, `CrossTenantSessionReplayIntegrationTest`,
`CrossTenantTenantIdHeaderManipulationIntegrationTest`, `CrossRolePlatformAdminTokenReplayIntegrationTest`
(identity-access-service); `PlatformAdminTenantControllerIntegrationTest`,
`TenantLookupServiceIntegrationTest` (tenant-management); `StudentManagementIntegrationTest`,
`TeacherManagementIntegrationTest`, `StaffManagementIntegrationTest` (each with inline
`tenantB...Returns404NeverTenantAsData`-style cases — user-management); `CourseManagementIntegrationTest`
(course-management, ~9 dedicated cross-tenant cases including bulk/filter endpoints);
`MaterialFetchVisibilityIntegrationTest` (content-management, incl. protected-download-URL ID-guessing);
`PaymentCrossTenantIntegrationTest`, `SlipCrossTenantIntegrationTest`,
`GatewayReferenceGlobalUniquenessIntegrationTest` (payment-management);
`PlatformAdminLedgerControllerIntegrationTest` + the one incidental assertion in
`PaymentCrossTenantIntegrationTest` (ledger-settlement-management — see 18.3 for the gap being
closed); `AttendanceCrossTenantIntegrationTest`, `AttendanceRecordRepositoryTenantGuardTest`
(attendance-management); `ExamCrossTenantIntegrationTest`, `ExamRepositoryTenantGuardIntegrationTest`
(exam-management); `NotificationCrossTenantIntegrationTest` +
`NotificationDispatchPollerPerRowIsolationTest`/`...TenantContextSymmetryTest` (notification-management,
including async-context-carry-forward proof); `AuditLogCrossTenantIntegrationTest`,
`AuditLogPlatformReportQuerySargabilityTest` (audit-log-management); `EnrollmentCrossTenantIntegrationTest`
(enrollment-management, including correct same-tenant-different-student 404-not-403 semantics).
`integration-management`'s isolation evidence lives in `paymentmanagement`'s own tests
(`anUnsignedWebhookIsRejectedAndCreatesNoStateChange`,
`aWebhookWithATamperedSignatureIsRejectedAndCreatesNoStateChange`) per the ADR-011 carve-out — tagged
there, with a note in that domain's test documentation pointing to where its isolation proof
actually lives.

**Idempotency/concurrency tests, confirmed present and correct** (tag `@Tag("idempotency")`):
`PaymentWebhookConcurrencyIntegrationTest#concurrentWebhookDeliveriesForTheSameGatewayReferenceProduceExactlyOnePaymentLedgerEntryAndEnrollment`,
`PaymentAndLedgerIntegrationTest#aDuplicateWebhookDeliveryForTheSameGatewayReferenceIsIdempotent`,
`RefundIdempotencyConcurrencyIntegrationTest`,
`SlipApprovalConcurrencyIntegrationTest#concurrentApproveRequestsForTheSameSlipProduceExactlyOneActivationAndOneAuditRow`,
`SlipOverrideAuditIntegrationTest#reapprovingAnAlreadyOverrideApprovedSlipWithNoOverrideReasonIsStillAnIdempotentNoOp`,
`SlipDuplicateDetectionIntegrationTest` (exact-match reference/hash, correctly tenant-scoped).

**Append-only regression tests, confirmed present** (re-run, not rewritten):
`AppendOnlyRepositoriesStructuralIntegrationTest` (payment/ledger_entry/payment_slip/audit_log,
structural), `AuditLogRepositoryAppendOnlyTest` (seeded-row proof),
`PlatformAuditLogRepositoryPathAppendOnlyTest` (no-TenantContext-set path),
`AuditLogActorIntegrityTriggerIntegrationTest` (DB trigger).

### 18.2 Backend — one new test class required

- `LedgerControllerCrossTenantIntegrationTest` (or equivalent name in
  `com.lms.ledgersettlementmanagement`) — proves a tenant-A actor cannot read tenant-B's
  `GET /api/v1/ledger/dashboard`/`/history` rows via this specific controller, closing the one
  confirmed real gap.

### 18.3 Backend — recommended, not mandatory, additions

- Unit/integration coverage for the optional `assertTenantIdMatchesContext` hardening (§9.1), if the
  coordinator elects to include it.
- Confirm `LoginIntegrationTest` (identity-access-service) covers login-activity logging
  specifically, since device-limit/override-precedence testing is correctly deferred to Phase 2
  (AUTH-1's own scope note) and should not be miscounted as a gap in this module's device-auth row.

### 18.4 Frontend — genuinely new work

- `test.extend`-based authenticated-session fixture (`frontend/e2e/fixtures/`), parameterized by
  role (Student/Teacher/Tenant Admin/Platform Admin) × tenant, performing real login against a real
  seeded backend (target environment per §21 item 2's open decision).
- Two-tenant seed-data mechanism providing representative data across built feature areas.
- New cross-tenant E2E negative specs for the ~15 feature areas with existing backend coverage but
  no Playwright equivalent today: identity/session, tenant management, course management,
  content/material, enrollment, payment (orders), payment slip, ledger/settlement, attendance,
  exams, notifications, audit log, teacher management, student management, staff management.
- Staging smoke-test spec(s): login × 4 roles × ≥2 tenants succeeds; core read paths return `200`
  with expected shape; `/actuator/health` reports `UP`; one basic cross-tenant read/write check.
- Existing `route-groups.spec.ts`/`shared-states.spec.ts` are **not** the basis for this work (both
  are single-tenant, mocked, and — per §11 — partially stale) — they remain as-is, covering what
  they already correctly cover (route-guard redirects, the one remaining static placeholder).

### 18.5 Final sign-off gate (INTG-3)

- `backend\mvnw.cmd verify` — 100% green, zero skipped `@Tag("cross-tenant")`/`@Tag("idempotency")`
  tests.
- `npx playwright test` — 100% green, including the new two-tenant fixture specs.
- Any story found without its required test at this gate is a named go-live blocker, filed against
  its owning module (never patched inline here).

## 19. Documentation changes

- **`docs/architecture/database-architecture.md`** — correct the `V31` section to reflect `V35`
  dropping `idx_payment_created_at_tenant` (§8).
- **`docs/requirements/open-decisions.md`** — add a new, dated entry recording the APP-3/native-dev
  deviation (Grounding note item 2) using this file's own established convention; correct §17's
  stale ADR-010 status cross-reference ("Proposed" → "Accepted (2026-08-23)", §17); record every
  item this module confirms as resolved-or-deferred during INTG-3 (§4.3).
- **New ADR** (`docs/adr/ADR-015-...` or next available number) — the platform's first
  container/deployment-topology decision (§9.4).
- **`docs/architecture/deployment-architecture.md`** — update §6's open questions only for whatever
  this module's own topology ADR actually resolves (base image/Compose-file-sharing/secrets-injection
  for staging); explicitly restate that cloud provider, CI/CD tooling, DNS/cert automation, and
  horizontal-scaling automation remain open, per the issue's own instruction not to silently narrow
  scope.
- **New: `infrastructure/.env.staging.example`** and a short staging-setup note in
  `infrastructure/CLAUDE.md` or a sibling doc, mirroring the existing `.env.dev.example`/local-dev
  documentation pattern.
- **New: a go-live checklist document** (e.g. `docs/planning/go-live-checklist.md`) — the durable
  artifact of INTG-3's per-module DoD/ADR-linkage/open-decisions confirmation, dated and naming the
  human approver.
- **`docs/api/`** — no new file; confirm every existing contract file remains accurate (§10); state
  explicitly if any drift is found (none found beyond the architecture-doc note above).
- **A one-line housekeeping note** (wherever this module's own documentation lands, e.g. a short
  section in the go-live checklist or a code comment) — record that
  `AppendOnlyRepositoriesStructuralIntegrationTest`'s cross-module reach into
  `audit-log-management`'s repository package is known, duplicated-but-correct, test-only, and not a
  production module-boundary violation (§16).
- **`docs/planning/risk-register.md`** — update entries related to tenant-isolation-suite
  completeness and staging-environment absence to reflect this module's findings and remaining gaps.

## 20. Implementation order

Per root `CLAUDE.md`'s workflow and this module's own dependency chain (INTG-1 → INTG-2 → INTG-3):

1. **Resolve §21's open decisions with the product/engineering owner** — items 1, 2, 4, and 5 in
   particular gate what INTG-2 actually builds and how INTG-1's Playwright work targets a real
   backend.
2. **INTG-1, backend**: tag the confirmed existing cross-tenant/idempotency test set
   (§18.1); add `LedgerControllerCrossTenantIntegrationTest` (§18.2); optionally add the
   `assertTenantIdMatchesContext` hardening (§9.1/§18.3) as its own separate, reviewable commit if
   included. Run `backend\mvnw.cmd verify` — must be green.
3. **INTG-2, infrastructure** (gated on step 1's ADR decision): author the deployment-topology ADR;
   build `backend/Dockerfile`, `frontend/Dockerfile`, `docker-compose.staging.yml`, Nginx config,
   `.env.staging.example` (§9.2); deploy to staging with synthetic data only; run the smoke test
   (§4.2 step 4).
4. **INTG-1, frontend** (can start once INTG-2's staging environment exists, or in parallel against
   a local docker-compose stack per §21 item 2's decision): build the `test.extend` fixture layer,
   two-tenant seed data, and the new cross-tenant E2E specs (§18.4). Run `npx playwright test` — must
   be green.
5. **`security-review`**, **`tenant-isolation-review`** skills — explicit passes confirming: no
   `PermissionCheckService`/`Role`/`DomainArea` change was introduced; the one recommended hardening
   item (if included) is correctly scoped; the two sanctioned platform-admin `tenantId`
   path-parameter exceptions remain the only ones.
6. **`payment-ledger-review`** skill — confirm the idempotency canary set is correctly named/scoped
   (including the disclosed gateway-webhook audit exception, §16) and no new payment logic was
   introduced.
7. **INTG-3**: walk every Module 1–20 plan's DoD; confirm ADR linkage (including the new
   deployment-topology ADR); walk `open-decisions.md` in full, recording resolved-or-deferred status
   per item; final full re-run of `backend\mvnw.cmd verify` and `npx playwright test`.
8. **`update-documentation`** skill — §19's file list.
9. **Human go-live approval** — explicit, dated, named; recorded in the go-live checklist document.
   This step cannot be performed by the implementing agent.
10. Commit as separate, logically-scoped units (e.g. "test: tag cross-tenant/idempotency regression
    canary set (INTG-1)", "test: add LedgerController cross-tenant coverage", "infra: add staging
    Dockerfiles/Compose/Nginx topology (INTG-2)", "test: add two-tenant Playwright fixture layer and
    cross-tenant E2E specs (INTG-1)", "docs: MVP-021 go-live checklist and open-decisions
    reconciliation (INTG-3)") — never bundling backend test changes, infrastructure artifacts, and
    frontend E2E work into one commit.

## 21. Risks and unresolved decisions

**None of these are resolved by this plan — implementation must not silently assume an answer, per
root `CLAUDE.md`'s "do not invent unresolved business decisions" instruction:**

1. **Whether `video-access-management` and `reporting-analytics` being entirely unbuilt (despite
   MVP-scoped baseline requirements in `module-catalog.md`) blocks go-live, or is an explicit,
   human-recorded deferral.** This plan does not decide this — it names the gap for INTG-3's
   checklist to force a real answer, not a silent omission.
2. **Which real backend the new two-tenant Playwright fixture layer targets**: the newly-stood-up
   staging environment from INTG-2 (blurring the line between "E2E test" and "staging smoke test"),
   or a separate local docker-compose-backed stack built specifically for CI/local E2E use. Not
   decided here — affects both INTG-1's and INTG-2's actual build order (§20).
3. **Whether the audit-row-written regression canary's gateway-webhook exception (§16, Grounding
   note item 10) is acceptable to ship as a permanent, disclosed limitation, or whether
   `open-decisions.md` §2's underlying actor-gap decision must be resolved first** — a real product/
   architecture tradeoff (synthetic system actor row vs. nullable `actor_id`), not decided here.
4. **Whether staging deploy becomes a new CI-triggered job or stays a manual, human-run
   `devops-engineer` step** — affects the shape of `.github/workflows/ci.yml` changes (if any) and
   the human-approval-gate design for INTG-2/INTG-3. Not decided here.
5. **Nginx TLS/certificate handling in staging** — self-signed, none, or a real cert via an
   as-yet-undecided DNS/cert automation approach (`deployment-architecture.md` §6, still open). Not
   decided here.
6. **Whether to adopt a platform-wide cross-tenant-test naming/tagging convention going forward**
   (beyond this module's own `@Tag` application) so future modules don't repeat the discoverability
   gap named in Grounding note item 3 — a recommendation from the security-reviewer audit, not a
   decision this plan makes unilaterally.
7. **Whether the "one full flow per major MVP module" smoke-test criterion should be read against
   the 20 shipped `MVP-0xx` plan modules specifically (this plan's recommendation, per Grounding note
   item 7) or the full 18-domain catalog** — a scoping clarification the product owner should
   confirm before INTG-2's smoke-test spec list is finalized.
8. **Whether the "58/61 stories" figure implied by INTG-1's dependency line is accurate** — no
   document names "58" directly; the most plausible reading (61 total stories − 3 INTG stories = 58)
   is an inference, not a confirmed fact, and should be verified against
   `docs/planning/product-backlog.md`'s full story list before being treated as a literal completion
   bar.
9. **Whether the recommended `assertTenantIdMatchesContext` hardening for
   `PaymentRepository`/`PaymentSlipRepository`/`ReactivationRequestRepository` (§9.1, §14) is
   included in this module's scope or deferred as its own small follow-up** — not a security
   requirement (no exploitable path exists today), so this is a scheduling/scope call, not a
   security decision.
10. **Whether any remediation found necessary during INTG-1/INTG-3 (e.g. a genuinely missing
    per-module test, or a module found not to meet its own DoD) is filed as a same-PR fix or a
    separate follow-up PR against the owning module** — this plan recommends the latter (§5
    acceptance criterion 7, §13) but the product/engineering owner should confirm this convention
    explicitly before INTG-1 begins, since it affects commit/PR boundaries for the whole module.

---

*This plan does not authorize implementation of any item until §21's open decisions — particularly
items 1, 2, 3, and 4, which gate what INTG-2 actually builds and how INTG-1's frontend work is
targeted — are explicitly resolved by the product/engineering owner. No Dockerfile, Compose file,
Nginx config, ADR, migration, test file, or documentation file has been created by this plan; it is
a plan only, per the explicit instruction it was produced under.*
