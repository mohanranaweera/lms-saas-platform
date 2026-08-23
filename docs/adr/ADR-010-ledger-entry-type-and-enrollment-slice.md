# ADR-010: `ledger_entry.entry_type` Enum and the Minimal `enrollment-management` Activation Slice

## Status

**Accepted (2026-08-23)**, by the project owner, in the same session that surfaced this
document's absence via a six-specialist review of the completed MVP-010 module. Written
retroactively — the decisions below were already implemented and shipped before this
ADR existed, because the ADR the plan itself required (`docs/plans/MVP-010 Order and
Payment Foundation.md` §19, §21 items 1 and 6) had never been produced. Both decisions
fall under root `CLAUDE.md`'s explicit change-controlled list ("payment ledger rules",
"enrollment activation rules"); this acceptance is that required sign-off, consistent
with `docs/api/course-management.md`'s precedent for a similar retroactively-discovered
gap. The "Required follow-up if accepted" item below calling for the Module 12 owner's
confirmation is satisfied by this same acceptance — this repository currently has a
single owner, with no distinct Module 12 owner yet assigned; re-confirm explicitly if
that changes before Module 12 is built.

## Context

Two decisions in MVP-010's implementation are individually change-controlled and were
each flagged by the plan itself as needing explicit sign-off before/alongside shipping,
but no such sign-off — nor the ADR the plan named as the vehicle for it — ever happened.

### 1. `ledger_entry.entry_type`

`.claude/rules/payments.md` §4: *"Ledger entry types and their effect on
enrollment/settlement state are part of the change-controlled 'payment ledger rules' —
do not add, remove, or change the meaning of a ledger entry type without explicit
approval."* The plan's own draft (§8) proposed a minimal two-value starting set
(`PAYMENT_CONFIRMED`, `REFUND`) "as the minimal candidate set" pending this ADR, not as
an already-ratified decision.

### 2. Minimal `enrollment-management` activation slice pulled into this module's PR

The plan's §21 item 1 states this decision "still needs explicit sign-off... since it
means this module's PR would touch a nominally different module's domain package," and
names the alternative (ship PAY-2 with an interface-only stub, coordinate a separate
ENR-1 PR later) as the fallback if rejected.

## Decision

### `ledger_entry.entry_type`

Adopt exactly two values, DB-enforced via `CHECK (entry_type IN ('PAYMENT_CONFIRMED',
'REFUND'))` (`V19__create_payment_management_schema.sql`):

- `PAYMENT_CONFIRMED` — a credit, written exactly once per `Payment` reaching
  `CONFIRMED`, inside the same local transaction as the confirmation write.
- `REFUND` — a debit (stored with a **negative** `amount`, the module's sign
  convention), written exactly once per `payment_refund` row, with
  `reverses_entry_id` pointing at the original `PAYMENT_CONFIRMED` entry.

No third type (e.g. a separate `SLIP_APPROVED` type to distinguish a future manual-slip-
sourced confirmation from a gateway one) exists. `payment_id` is nullable specifically to
leave room for that future non-payment-table source without a schema change when Module
11 (manual payment slips) is built — but no such type may be added without a new ADR
amending this one.

### Minimal `enrollment-management` activation slice

Ship a minimal `com.lms.enrollmentmanagement` domain package (`enrollment` table,
`EnrollmentActivationApi`, `EnrollmentActivationService`) as part of MVP-010's own PR,
rather than deferring to a separately-coordinated Module 12 PR, because PAY-2's own
acceptance criteria (atomic payment-confirm + enrollment-activate in one transaction)
are structurally untestable without a real, callable activation `api` to invoke —
matching the plan's own §21 item 1 rationale (`docs/planning/dependency-map.md` and
`docs/planning/mvp-release-plan.md` already required this coupling be designed/built
concurrently, not sequentially).

The shipped slice is deliberately narrow: `enrollment.status` has a single reachable
value (`ACTIVE`); there is no student-facing enrollment read endpoint; `ENR-2`
(course-level expiry) and `ENR-3` (reactivation) are explicitly not built. The
`activating_slip_id` foreign key is present as a nullable column with no FK constraint
yet (the target table, `payment_slip`, doesn't exist until Module 11) — a comment in
`V19` records that the constraint must be added by a **new** migration when that table
ships, never by editing this one.

## Consequences

**Positive**

- PAY-2's atomic-activation acceptance criterion is genuinely testable today
  (`PaymentConfirmationRollbackIntegrationTest` proves a mid-transaction activation
  failure rolls back the payment/ledger writes too) rather than deferred behind a stub.
- The two-value `entry_type` set is the literal minimum PAY-3/PAY-4 require; nothing
  speculative (no Phase-2/3/4 concept) was added under it.
- `enrollment`'s schema-enforced invariant (`ck_enrollment_exactly_one_activation_source`
  — exactly one of `activating_payment_id`/`activating_slip_id` non-null) and its
  `uq_enrollment_tenant_student_course` idempotency constraint are both already in place
  and tested, so Module 11's later slip-activation path has a correct schema to build
  onto rather than needing its own migration to retrofit these invariants.

**Negative / trade-offs accepted**

- This module's PR touched `com.lms.enrollmentmanagement`'s domain package before
  Module 12 formally began — exactly the boundary-crossing the plan's own §21 item 1
  flagged as needing sign-off. Module 12's eventual owner inherits a partially-built
  domain rather than a clean slate, and must reconcile their own scope against what's
  already here.
- `entry_type`'s two-value set, while minimal, was shipped and is live in production
  schema before this ADR's formal acceptance — a **rejection** of either decision below
  would require a corrective migration and (for the enrollment slice) a non-trivial
  package-ownership renegotiation, not just a documentation edit.

## Alternatives considered

- **Ship PAY-2 with an `EnrollmentActivationApi` interface-only stub**, deferring the
  real implementation to a separate, later-coordinated ENR-1 PR — the plan's own
  fallback if this decision is rejected. Not chosen because it would leave PAY-2's
  atomic-transaction acceptance criterion untestable until that later PR lands, and the
  plan's cited planning documents already called for concurrent design.
- **A three-or-more-value `entry_type` set** anticipating Module 11 (`SLIP_APPROVED`) or
  Phase 2 (a settlement/payout type) up front — rejected as exactly the "for future-
  proofing" violation `.claude/rules/payments.md` §17 and the plan's own guardrail
  explicitly warn against; the schema adds columns/values when the feature that needs
  them actually ships, via its own ADR.

## Required follow-up if accepted

- Confirm with whoever owns Module 12 (issue #12) that the scope-crossing described
  above is acceptable, and record their sign-off here or in a superseding ADR.
- If Module 11 (manual payment slips) later needs a `SLIP_APPROVED`-equivalent ledger
  entry type, or the `activating_slip_id` FK, both require their own new migration and
  an amendment to (or a new ADR superseding) this one — never a direct edit to `V19` or
  to this document's Decision section.

## Related

- `docs/plans/MVP-010 Order and Payment Foundation.md` §7-9, §21 items 1 and 6
- `docs/api/ledger-settlement-management.md`
- `.claude/rules/payments.md` §4
- `V19__create_payment_management_schema.sql`
