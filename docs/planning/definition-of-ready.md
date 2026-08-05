# Definition of Ready

Criteria a story from `docs/planning/product-backlog.md` must meet **before** an implementer picks
it up. This is the pre-work gate — it complements, and does not replace, the `plan-module` skill's
per-module planning output, which still runs at the start of implementation. A story failing this
gate should stay in the backlog, not be started "to see how far we get."

## Universal criteria (every story)

- [ ] The story's product-backlog entry has all 13 fields populated — none left as an unexplained
  blank. A field genuinely not applicable says "None" with a one-line reason, per the backlog's own
  convention.
- [ ] Every **hard blocker** listed in the story's "Dependencies" field has shipped and passed its
  own Definition of Done — not just "started" or "in review."
- [ ] The story's position in `docs/planning/mvp-release-plan.md`'s wave order is respected — a
  story is not pulled ahead of its wave without re-checking `docs/planning/dependency-map.md` for
  hard blockers that wave ordering exists to protect against (see risk register R16).
- [ ] Every item in the story's acceptance criteria marked **OPEN DECISION** has one of:
  - a resolution recorded in `docs/requirements/open-decisions.md` (status updated from open to
    resolved, with the decision and who made it), or
  - an explicit, written acceptance that the story ships with the gap and a follow-up story is
    filed — never silent invention of an answer during implementation.
- [ ] If the story's "Security impact," "Tenant impact," or "Payment impact" field names a
  change-controlled area (multi-tenancy strategy, authentication architecture, payment ledger
  rules, enrollment activation rules, production deployment strategy, database migration history,
  approved API contracts), an **Accepted** ADR under `docs/adr/` covers it. A story touching one of
  these areas with no linked ADR is not ready, regardless of how well-understood the change feels.

## Backend-touching stories

- [ ] The target domain package (one of the 18 confirmed domains) is identified in "Backend
  impact" — a story that can't name its owning domain isn't scoped tightly enough to start.
- [ ] Any cross-module dependency named in "Backend impact" (a call into another domain's `api`)
  has that `api` interface already defined, even if only as a stub/contract — not "we'll figure out
  the interface while building this."
- [ ] If "Database impact" names a new/changed table, the `tenant_id`/tenant-scoping requirement
  from "Tenant impact" is understood by whoever picks up the story before migration-writing starts
  (per the `database-migration` skill).

## Frontend-touching stories

- [ ] The target route group (`app/(student)/`, `app/(teacher)/`, `app/(tenant-admin)/`,
  `app/(platform-admin)/`, `app/(public)/`) is identified in "Frontend impact."
- [ ] Any shared component the story depends on (state-component library, responsive table,
  permission-denied wiring) already exists or is itself a completed prerequisite story — not
  something this story is expected to invent inline.
- [ ] If the story's UI depends on a backend endpoint, that endpoint's contract is stable enough to
  build against — per `review-api-contract`'s scope, not a moving target.

## Payment/ledger/enrollment stories (PAY-*, SLIP-*, ENR-*)

- [ ] The story's "Payment impact" field cites the specific rule from `.claude/rules/payments.md`/
  `docs/architecture/payment-ledger.md` it implements — a payment-cluster story with no rule
  citation has not been checked against the append-only/idempotency/audit requirements yet.
- [ ] If the story is part of the coordinated Wave 3 slice (`PAY-2`, `SLIP-3`, `ENR-1`), the shared
  `api` contract between `payment-management`/`payment-management` (slips) and
  `enrollment-management` is agreed before any of the three starts independently — per the release
  plan's explicit "do not split across separate sprints/PRs" instruction.

## Security-sensitive stories (auth, RBAC, audit, protected content)

- [ ] The story's "Security impact" field is not "None" for any story touching authentication,
  authorization, file upload, or protected-content fetch — if it says "None" for one of these areas,
  treat that as a red flag to re-check, not accept at face value.
- [ ] The story's "Testing requirements" field names the specific cross-tenant negative test(s) it
  needs — a security-sensitive story with no named cross-tenant test in Definition of Ready will not
  retroactively get one added at Definition of Done time by default.

## Explicitly not required at this gate

- Full implementation detail (that's what `plan-module` produces at the start of implementation,
  not before backlog entry).
- 100% resolution of every open decision platform-wide — only the ones the specific story's
  acceptance criteria actually depend on.
- A passing test suite (that's Definition of Done, not Ready).
