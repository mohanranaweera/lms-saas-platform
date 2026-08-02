# Non-Functional Requirements

Status: Draft — consolidated from `docs/architecture/solution-architecture.md` §6
("Non-functional posture"), `.claude/rules/architecture.md` ("Scalability guidance"),
`.claude/rules/security.md`, `.claude/rules/tenancy.md`, `.claude/rules/testing.md`, and
`docs/ui-ux/accessibility.md`. This document does not introduce new architectural
decisions — it consolidates NFRs already implied across those documents into one
referenceable place, and flags the handful of NFR targets (response-time budgets,
uptime target, browser support matrix) that are genuinely not yet decided anywhere.

Related: `docs/architecture/solution-architecture.md`, `docs/requirements/
functional-requirements.md`, `docs/requirements/module-catalog.md`

---

## 1. Scalability & performance

- **Stateless application instances.** No in-memory session state, no in-JVM cache
  assumed authoritative, no local filesystem dependency for tenant data — required so
  the backend can run multiple horizontally scaled instances behind Nginx without sticky
  sessions. (`.claude/rules/architecture.md`)
- **Tenant-scoped query performance.** Every tenant-owned table's indexes are validated
  with `tenant_id` as the leading filter column, since table size grows with total
  platform tenants/courses, not per-tenant. (`.claude/rules/architecture.md`,
  `.claude/rules/backend.md`)
- **Asynchronous fan-out for high-volume side effects.** Notification dispatch,
  audit logging, and reporting/analytics consume domain events rather than executing
  inline with the triggering request, so a burst of notifications or reporting load
  cannot block transactional request threads.
- **External video/content storage.** `video-access-management` and
  `content-management` never stream or store binary media through the application VPS
  as a source of truth — the app works in terms of signed URLs/tokens against an
  external provider.
- **Redis as cache/ephemeral layer only.** Used for caching, rate limiting,
  device/session tracking, and short-lived tokens — never as the source of truth for
  tenant, financial, or enrollment state (a Redis flush must never lose authoritative
  data).
- **Response-time budgets, target concurrent-tenant count, and target request
  throughput are not yet decided** — flagged as an open item (§9) rather than
  invented; do not hardcode a specific SLA number anywhere in code or tests until this is
  set.

## 2. Availability & reliability

- No formal uptime target (e.g., "99.9%") has been set — flagged in §9.
- Horizontal scaling behind Nginx (per §1) is the primary reliability mechanism for the
  application tier; PostgreSQL/Redis reliability characteristics are covered in
  `docs/architecture/database-architecture.md` and `docs/architecture/
  backup-disaster-recovery.md`.
- Background/async work (notifications, scheduled expiry jobs, settlement runs) must be
  safe to retry — see idempotency requirements in §5 and `.claude/rules/testing.md`'s
  required-test matrix.

## 3. Security

Full detail lives in `.claude/rules/security.md` — summarized here as an NFR posture:

- Device authentication and account-sharing prevention (server-side device
  registration, override-precedence limit enforcement, audited resets with cooldown).
- Signed, short-lived, single-session-scoped video/session access tokens; server-side
  expiry/revocation enforcement, never only a frontend countdown.
- Mandatory audit logging (append-only) for privileged actions: price changes, payment
  approvals/rejections, device resets, access/expiry extensions, reactivation approvals,
  content deletions, settlement amount changes, impersonation start/end.
- Upload validation (server-side MIME/content sniffing, size, ownership/permission) on
  every upload endpoint, with no partial write on failure.
- No protected content (video, payment slips, documents) reachable via a direct,
  predictable URL/ID — every fetch passes an authorization check scoped to the specific
  resource and requester.
- Secrets/credentials for all third-party integrations owned exclusively by
  `integration-management`, never embedded in other domains.

## 4. Multi-tenancy isolation

Full detail lives in `.claude/rules/tenancy.md` and `docs/architecture/
multi-tenancy.md` — summarized here as an NFR posture:

- Tenant identity resolved exactly once per request, server-side, from trusted
  authenticated context — never from client-supplied input.
- Structural tenant filtering (`TenantAwareRepository` or equivalent) applied
  consistently across all tenant-owned entities — no hand-rolled per-repository
  filtering.
- Every tenant-owned endpoint/repository method/query requires an explicit
  cross-tenant negative test (tenant A denied 403/404 on tenant B's resource by id) as
  a condition of being considered "done," per `.claude/rules/testing.md`.
- Bulk/admin/reporting/export endpoints are explicitly in scope for isolation review —
  not assumed safe because they aggregate rather than return single records.

## 5. Data integrity & financial correctness

- Payment and ledger records are append-only; a payment row is immutable once it
  reaches a terminal state (`CONFIRMED`/`REJECTED`/`REFUNDED`); corrections are always
  new rows, never `UPDATE`/`DELETE`.
- Money columns use fixed-precision types (`NUMERIC`), never floating point.
- Enrollment activation carries a FK/NOT NULL trail to the specific confirmed payment
  or approved manual-evidence row that authorized it — never a bare boolean flag.
- Settlement calculation is idempotent (re-running for an already-settled
  tenant/period must not create duplicate payout entries), guarded by a DB uniqueness
  constraint, not application-logic-only checks.
- Audit log rows are immutable — no update/delete path, by any actor, including
  Platform Admin.

## 6. Accessibility

- WCAG 2.2-oriented design practice across all four portals, per `docs/ui-ux/
  accessibility.md`'s checklist: full keyboard operability (including drag-and-drop
  equivalents), screen-reader-usable async status (`aria-busy`/`aria-live`/`role="alert"`),
  WCAG AA contrast for tenant brand colors validated server-side at configuration time,
  `aria-label` on every icon-only control, status conveyed by text/icon in addition to
  color, programmatically associated form labels with `aria-describedby` error linkage.
- Whether an automated accessibility CI gate (e.g., axe-core in Playwright runs) is
  adopted is not yet decided — flagged in §9, consistent with `docs/ui-ux/
  accessibility.md`'s own open question.

## 7. Observability

- Logging, metrics, and tracing are distinct from business audit logging (the latter is
  a compliance/security record, not a debugging tool) — see `docs/architecture/
  observability.md` for the full separation.
- Every domain's privileged/state-changing actions must be traceable through both
  channels (audit log for "what changed and who did it," observability stack for "is
  the system healthy") without conflating the two into one mechanism.

## 8. Testability / Test Strategy

This is the section `docs/requirements/module-catalog.md`'s "Required tests per
backend domain" table refers to as its normative source — restated here as policy, with
`.claude/rules/testing.md` and `module-catalog.md`'s per-domain table as the detailed
reference.

- **Unit vs. integration boundary:** a mocked unit test is acceptable only for logic
  with no database/Redis involvement (pure calculations, DTO mapping, validation,
  pricing math, token parsing). Anything touching a repository query, `@Query`/
  specification, or native SQL requires a Testcontainers-backed integration test — a
  mocked repository cannot fail a missing `tenant_id` filter.
- **Cross-tenant floor.** Every tenant-owned domain requires a cross-tenant negative
  test as a baseline, in addition to whatever domain-specific test category
  `module-catalog.md`'s table lists for it (idempotency, token-expiry/replay,
  device-limit-precedence, audit-row-written, append-only-enforcement, etc.).
- **Negative-path coverage for security-sensitive logic.** Authn, authz, device auth,
  token issuance/validation, payment approval, and impersonation each require tests
  targeting the denial/failure path specifically — a passing happy-path suite with zero
  negative-path tests is not adequate coverage for these areas.
- **Test data.** Fixtures are built programmatically (builder/factory pattern), never
  copied/derived from real student, teacher, or financial records, even anonymized. Any
  suite touching a tenant-owned table sets up at least two distinct tenants in fixture
  setup.
- **Frontend (Playwright).** Role-based authenticated fixtures for at least Student,
  Teacher, Tenant Admin, Platform Admin, each belonging to a specific seeded tenant;
  at least two tenants' worth of fixtures so cross-tenant E2E scenarios don't require ad
  hoc setup; every backend cross-tenant test's feature area needs a matching E2E
  cross-tenant negative test proving the browser-context path is also blocked, not just
  that no link exists in the nav.

## 9. Compliance & data retention

- Financial history (payments, ledger entries, settlements) is never deleted, per root
  `CLAUDE.md`'s Safety section and the append-only rules in §5.
- Audit logs are immutable and retained indefinitely by default; if a future
  retention/purge policy is required for compliance reasons, that is a separate approved
  process, not a repository `delete` call (`.claude/rules/backend.md`).
- No real student or financial records are used in development/testing at any time.

## 10. Browser & device support

Not yet decided — no minimum supported browser/OS matrix exists in current material.
Flagged in the open-questions list below.

---

## Open questions / not yet decided (flagged, not invented)

1. Response-time budgets, target concurrent-tenant count, and request-throughput
   targets (§1).
2. Formal uptime/availability target (§2).
3. Whether an automated accessibility CI gate (axe-core/Playwright) is adopted, and at
   what severity it blocks a merge (§6, also open in `docs/ui-ux/accessibility.md`).
4. Minimum supported browser/OS/device matrix (§10) — not specified anywhere in current
   architecture or requirements material; needed before responsive/accessibility QA can
   define a concrete test matrix.
5. Formal data-retention/purge policy for non-financial, non-audit data (e.g., how long
   an inactive tenant's data is retained after cancellation) — financial and audit data
   retention is already decided (never deleted); this question is narrower and unaddressed.

## Related

- `docs/architecture/solution-architecture.md`
- `docs/requirements/functional-requirements.md`
- `docs/requirements/module-catalog.md`
- `.claude/rules/testing.md`
