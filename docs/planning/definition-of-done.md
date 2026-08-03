# Definition of Done

Criteria a story from `docs/planning/product-backlog.md` must meet before it is considered
complete and ready to commit as one logical change. This is the MVP-backlog-specific expansion of
the project's existing `.claude/skills/definition-of-done/SKILL.md` gate — that skill's checklist
still applies in full at commit time; this document maps it onto this backlog's specific 13-field
story structure and the risk areas the five-agent review surfaced, so "done" means the same thing
for every one of the 61 stories.

## Universal criteria (every story)

- [ ] Every bullet in the story's "Acceptance criteria" field is demonstrably true — not "should
  work," verified.
- [ ] Every **OPEN DECISION** the story's acceptance criteria flagged is either resolved (with the
  resolution reflected in the actual implementation, not just noted) or explicitly deferred with a
  follow-up story filed — never silently implemented as if it had been decided.
- [ ] The story's "Documentation requirements" field is fully satisfied — every named `docs/`
  file is updated, or the story states explicitly why no update was needed (per
  `update-documentation`'s own rule: a "no update needed" call requires a one-line reason, not
  silence).
- [ ] Backend and frontend for this story were not implemented simultaneously unless the task
  explicitly authorized "full-stack implementation approved," per root `CLAUDE.md`.
- [ ] The change is one logical commit, not bundled with unrelated work from another story.

## Testing gate (maps to the "Testing requirements" field)

- [ ] Every test category named in the story's "Testing requirements" field exists and passes —
  unit, Testcontainers-backed integration, and Playwright/E2E as applicable.
- [ ] If the story's testing requirements name a **cross-tenant negative test**, it exists, is
  named/locatable (not merely implied by broader test coverage), and passes. Per
  `.claude/rules/tenancy.md`: a review that looks correct but has no accompanying cross-tenant test
  is isolation *unverified*, not isolation *present* — this is not satisfied by "the happy-path
  tests all pass."
- [ ] If the story is flagged in the backlog as falling into one of the required-test-matrix rows
  (payment/ledger idempotency, manual-slip duplicate-detection, enrollment activation,
  audit-log-row-written), that specific named test exists and passes — not just general coverage in
  the same area.
- [ ] `backend\mvnw.cmd verify` and `npx playwright test` both pass with this story's changes
  included.

## Security gate (maps to the "Security impact" field)

- [ ] Every concern named in the story's "Security impact" field has a corresponding control in the
  implementation — not just a test, an actual enforced behavior (e.g. "Read-only Auditor never
  succeeds on this mutating endpoint" means the endpoint rejects it, and a test proves it).
- [ ] If the story is named as a **mandatory audit-log action** (in its own field or via
  `AUDIT-2`'s wiring), the audit row is verified written with correct `actor_id`/`tenant_id`/
  `action`/`target_entity`/`target_id`/`occurred_at`, in the same transaction as the privileged
  action — not a best-effort or eventually-consistent write.
- [ ] `security-review` has passed for this change, per the existing project skill.

## Tenant-isolation gate (maps to the "Tenant impact" field)

- [ ] Every tenant-owned table this story introduces or touches has `tenant_id NOT NULL` with a
  tenant-leading composite index matching the actual query shape named in "Tenant impact" — not
  just `tenant_id` present, indexed for how the module actually queries.
- [ ] No repository method introduced by this story accepts a caller-supplied `tenant_id`
  parameter.
- [ ] Any cross-tenant bypass this story introduces (platform-admin reads, reporting aggregation)
  is a distinctly, explicitly named method — never the default/implicit behavior of a
  `TenantAwareRepository`-derived finder.
- [ ] `tenant-isolation-review` has passed for this change, per the existing project skill.

## Payment/ledger gate (maps to the "Payment impact" field, when not "None")

- [ ] Every payment/ledger rule the story's "Payment impact" field cites is actually implemented as
  a schema-or-service-level invariant (DB `CHECK` constraint, no exposed `delete`/`update` method,
  atomic transaction boundary) — not merely documented intent.
- [ ] If the story touches settlement, refund, or override logic, the required idempotency test
  (run the operation twice, assert unchanged state on the second run) exists and passes.
- [ ] `payment-ledger-review` has passed, if the change touches payments, ledger, or enrollment
  activation, per the existing project skill.
- [ ] No Phase 2/3/4 payment-roadmap concern (settlement, tenant payment accounts, split payments)
  was scaffolded into this story's schema/code ahead of its own approved design.

## UI/UX gate (for frontend-touching stories)

- [ ] `ui-ux-review` has passed, per the existing project skill.
- [ ] Loading, empty, error, and (where applicable) permission-denied states are implemented using
  the shared state-component library, not reimplemented ad hoc for this page.
- [ ] Any empty-state copy differentiation named in the story's acceptance criteria (e.g.
  "no X yet" vs. "no X match your filter") is actually distinct, not a shared generic message.

## Change-control gate

- [ ] If this story touched a change-controlled area (multi-tenancy strategy, authentication
  architecture, payment ledger rules, enrollment activation rules, production deployment strategy,
  database migration history, approved API contracts), the deviation is recorded in a linked,
  Accepted ADR under `docs/adr/` — not merged on the basis of "it seemed like the obviously right
  call."

## Final sign-off

- [ ] `qa-regression` has been run and is green for this story's area.
- [ ] No production system, production database, or real student/financial record was touched at
  any point in this story's work.
- [ ] The story is ready to commit as one logical change per `.claude/rules/git-workflow.md`.

## Module- and wave-level Done (beyond individual stories)

A **module** (per `docs/planning/product-backlog.md`'s 21-module grouping) is done when every story
listed under it individually meets the criteria above. A **wave** (per
`docs/planning/mvp-release-plan.md`) is done when every module wholly or partially assigned to it
is done, and the wave's own stated "Exit criteria" is verified true, not assumed from individual
story completion alone — Wave 3 in particular (`docs/planning/mvp-release-plan.md`) requires the
coordinated payment/slip/enrollment slice to be verified as a whole, since its risk (see
`docs/planning/risk-register.md` R1, R4, R5) lives at the integration seam between stories, not
within any single one.

The MVP as a whole is done only when `INTG-3`'s own Definition of Done (above) is met — which
itself requires all 61 stories individually done, `docs/requirements/open-decisions.md` fully
triaged for MVP-affecting items, and human approval for production deployment, per root
`CLAUDE.md`'s Safety rules. No story, module, or wave in this backlog authorizes an automatic
production deployment — that decision is always separate and always human-approved.
