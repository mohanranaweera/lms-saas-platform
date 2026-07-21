# Testing Rules

These rules extend the testing requirements already stated in `CLAUDE.md`, `backend/CLAUDE.md`,
and `frontend/CLAUDE.md`. They apply automatically to any testing-related work.

## Unit test vs. Testcontainers-backed integration test

- Use a mocked unit test only for logic with no database/Redis involvement: pure calculations,
  DTO mapping, validation rules, request/response shaping, pricing math, token parsing.
- Use a Testcontainers-backed integration test (real PostgreSQL, real Redis where relevant) for
  anything that executes a repository query, a `@Query`/specification, or a native SQL statement.
  Mocked repositories cannot fail a missing `tenant_id` filter — the mock returns whatever the
  test told it to return, so a query bug ships silently. If the code path touches persistence,
  the test must touch a real database.
- Service-layer tests that call into a repository must use the full Spring context with
  Testcontainers, not a `@Mock` repository, whenever the service is expected to enforce or rely
  on tenant filtering, ordering, uniqueness constraints, or DB-level cascades/triggers.
- Flyway migrations run against the Testcontainers database in every integration test — never
  hand-roll schema in test setup that bypasses migrations.

## Required-test matrix (domain-specific)

| Change touches... | Mandatory test(s) |
|---|---|
| Payment/ledger creation, settlement, refund, commission calc | Idempotency test: run the settlement/ledger operation twice with the same input (e.g. same webhook delivery, same manual approval action) and assert the ledger balance and row count are unchanged on the second run. Never assert only on the happy-path single run. |
| Manual payment slip approval/rejection | Duplicate-slip detection test (same reference number or same image hash rejected/flagged) and an idempotency test on approval (approving twice must not double-activate enrollment or double-write ledger entries). |
| Enrollment activation | Test proving activation only occurs after a persisted, verified payment/approval record exists — never from a request payload alone. |
| Device authentication (registration, limit enforcement, reset) | Device-limit-exceeded test: register devices up to the configured limit, assert the next login is blocked; assert admin reset clears the count; assert plan/tenant/course-level overrides are respected in priority order. |
| Video/session access (playback tokens, signed URLs, session expiry) | Token-expiry test: an expired or already-consumed token must be rejected. Cross-tenant/cross-session negative test: a valid token issued for tenant A / session A must be rejected when replayed against tenant B's session or a different session ID. |
| Any action that produces an audit log entry (payment approval, device reset, price change, material deletion, impersonation, settlement change) | Test asserting exactly one audit log row is written with the correct `actor_id`, `tenant_id`, action type, and target entity — not just that the primary action succeeded. |
| Concurrent session / view-limit enforcement | Test that a second concurrent playback attempt beyond the configured limit is blocked, using real Redis via Testcontainers for the session/lock state. |

If a change falls into one of these rows and the matching test is missing, treat the change as
incomplete — do not merge or report it as done.

## Test data conventions

- Never copy or derive test fixtures from real student, teacher, or financial records, even
  anonymized. Build fixtures programmatically (fixture-builder / test-data-factory pattern) so
  data is obviously synthetic and easy to regenerate.
- Backend: implement builders (e.g. `TenantTestDataBuilder`, `StudentTestDataBuilder`,
  `PaymentTestDataBuilder`) that produce valid, randomized-but-deterministic entities via
  `@BeforeEach`/test fixtures — do not hand-write large literal JSON/SQL blobs of fake production
  data.
- Any test suite (backend or E2E) that exercises a tenant-owned table or endpoint must set up
  **at least two distinct tenants** (e.g. `tenantA`, `tenantB`) with their own users/data in the
  fixture setup, even if the test itself doesn't yet assert cross-tenant behavior. This keeps
  cross-tenant assertions possible to add later without a fixture rewrite, and makes accidental
  single-tenant test bias visible in review.
- Seed/dev fixtures used for manual testing or demos must live under clearly-named
  dev/test-only seed data (e.g. Flyway `V*_test_seed` profiles or a dedicated dev-data script),
  never mixed into production migration history.

## Coverage expectations

- A happy-path test that incidentally touches tenant-owned data does not count as a tenant
  isolation test. Cross-tenant negative assertions must be explicit: attempt the read/write/list
  as tenant B against tenant A's data and assert a 403/404/empty-result, not just assert tenant
  A's own path works.
- Any change to a tenant-owned table (new column, new repository method, new endpoint reading or
  writing that table) must ship with an explicit cross-tenant test in the same change. Treat a
  PR without one as incomplete, regardless of other coverage numbers.
- Security-sensitive logic (authn, authz, device auth, token issuance/validation, payment
  approval, impersonation) needs tests that specifically target the failure/denial path, not only
  the success path. A passing happy-path suite with 0 negative-path tests is not adequate
  coverage for these areas.
- Do not rely on incidental coverage from broader integration tests to justify skipping a
  targeted test for a security- or tenant-sensitive path — write the targeted test even if a
  broader test already exercises the same code.

## Playwright conventions

- Maintain reusable, role-based authenticated fixtures/storage states for at least: Student,
  Teacher, Tenant Admin, and Platform Admin. Keep them in a shared fixtures module so every spec
  can request the role it needs instead of re-implementing login.
- Each role fixture must belong to a specific seeded tenant (not a shared/ambiguous tenant), so
  tests can reason about "this role, this tenant."
- Maintain at least two tenants' worth of role fixtures (e.g. Tenant A Student/Teacher/Admin and
  Tenant B Student/Teacher/Admin) so cross-tenant E2E scenarios don't require ad hoc setup.
- Every feature area that has a backend cross-tenant test must also have at least one E2E
  cross-tenant negative test: log in as a Tenant A role, attempt to reach or act on Tenant B
  data/UI (via direct URL navigation, API call from the browser context, or ID substitution), and
  assert the UI blocks it (permission-denied state, 403, redirect) rather than merely not linking
  to it in the nav. A hidden link is not access control — prove the backend also refuses it from
  the browser context.
- Platform Admin fixtures must be used only for platform-level assertions (tenant approval,
  cross-tenant reporting) — do not use Platform Admin as a shortcut to bypass tenant-scoped setup
  in tests meant to represent normal tenant users.
