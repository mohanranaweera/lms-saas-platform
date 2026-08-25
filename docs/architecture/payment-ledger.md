# Payment & Ledger Architecture

Status: Living document — reflects current design decisions and confirmed roadmap.
Change control: This document describes the "payment ledger rules" area named in
root `CLAUDE.md`'s Change Controls section. Any change to the rules described here
(ledger entry semantics, append-only invariants, settlement idempotency guarantees,
or the phase boundaries below) requires an explicit approval and an accompanying
ADR under `docs/adr/`, per `.claude/rules/documentation.md`. See
`docs/adr/ADR-003-centralized-payments-first.md`.

Owning domains (per `.claude/rules/architecture.md`): `payment-management`,
`ledger-settlement-management`. Related: `enrollment-management` (consumer of
payment/ledger state — see `docs/architecture/enrollment-access.md`),
`integration-management` (owns the actual gateway credentials/webhook handling —
this document treats the payment gateway as a generic, unnamed external provider
by design; no specific provider is assumed or should be invented in this doc).

## 1. Roadmap and phase boundaries

The platform's payment model is built in four confirmed phases (root `CLAUDE.md`,
source requirements section 12):

1. **Phase 1 — Platform centrally collects all student payments.** One platform-level
   collection point; no tenant-specific payment routing yet.
2. **Phase 2 — Tenant/tutor settlements.** The platform periodically calculates what
   each tenant/tutor is owed and records settlement runs; money still physically
   flows through the platform's central collection.
3. **Phase 3 — Tenant-specific payment accounts.** Tenants gain their own payment
   configuration/routing; not yet designed in detail (see §8).
4. **Phase 4 — Split payments, only if the gateway supports it.** Marketplace-style
   split disbursement at the gateway level; contingent on gateway capability and
   explicitly gated behind that support (see §8).

Do not build Phase 3/4 mechanics into Phase 1/2 schemas or code speculatively. Phase
boundaries are a scope control, not just a sequencing preference — pulling a later
phase's concern forward (e.g., a "tenant payment account" column on the Phase 1
`Payment` table) is itself a change to a change-controlled area and needs the same
ADR/approval path as any other ledger-rule change.

## 2. Phase 1 — Centralized Orders & Payments

- Every `Order` and every `Payment` row carries `tenant_id`, resolved from the
  trusted authenticated context (never a request body/query param — see
  `.claude/rules/tenancy.md`).
- An `Order` and its resulting `Payment(s)` are never mixed across tenants, including
  in platform-admin views. Cross-tenant aggregation (e.g., platform revenue reports)
  is a `reporting-analytics` concern built from per-tenant data, not a relaxed
  query-layer default on the payment tables themselves.
- A `Payment` row is **immutable once it reaches a terminal state** (`CONFIRMED`,
  `REJECTED`, `REFUNDED`). Any correction after a terminal state — refund, reversal,
  dispute outcome — happens via a new row/new ledger entry (§5), never an `UPDATE` on
  a terminal payment.
- Money columns use fixed-precision types (`NUMERIC`), never floating point.
- The payment `status` state machine is enforced at the schema level (a `CHECK`
  constraint over a fixed enum set), not only in service-layer code, per
  `.claude/rules/backend.md`'s schema-enforced-invariants guidance.
- `Order` state (`PLACED`, `PENDING`, etc.) is **not** activation evidence for
  anything — order state answers "what did the student intend to buy," payment/ledger
  state answers "did money actually get confirmed." See
  `docs/architecture/enrollment-access.md` for how `enrollment-management` consumes
  this distinction.

### 2.1 Two payment confirmation paths

Phase 1 supports two distinct ways a payment can become `CONFIRMED`, and they must
not be collapsed into one generic mechanism:

1. **Automated/gateway path.** The (unnamed, generic) payment gateway notifies the
   backend of a completed payment via a verified server-to-server mechanism (e.g. a
   signed webhook/callback validated by `integration-management`). The backend, not
   the frontend, is the party that observes and records confirmation.
2. **Manual payment slip path.** A student uploads evidence of an out-of-band payment
   (bank transfer slip, etc.); a human reviewer approves it. This is a materially
   different trust model (human judgment instead of gateway signature verification)
   and is modeled as its own state machine — see §3.

Both paths converge on the same outcome: a persisted, tenant-scoped `Payment` (or
manual-evidence) row in a confirmed/approved terminal state, which is the only thing
`enrollment-management` is allowed to treat as activation evidence.

## 3. Manual Payment Slip Approval Workflow

Manual slip approval is a distinct state machine from automated gateway payments —
it is not a generic "payment status" enum with extra values bolted on.

**States:** `SUBMITTED -> UNDER_REVIEW -> APPROVED | REJECTED`

- Transitions are **one-directional**. There is no `APPROVED -> SUBMITTED` and no
  silent re-open of a decided slip. If an already-approved slip later needs to be
  reversed (e.g., the slip turns out to be fraudulent or duplicate after the fact),
  that is modeled as a new ledger entry (a reversal/correction, §5), not a state
  rollback on the slip row itself.
- `SUBMITTED` slips must never trigger enrollment activation by themselves — a
  student uploading a slip is a claim, not evidence. Activation only follows the
  `APPROVED` transition, and only when that transition is performed by an authorized
  reviewer/staff action, recorded with reviewer identity and timestamp (this is also
  an audit-logged action per `.claude/rules/security.md`).
- The admin payment dashboard and any "payment history" surface must be derived from
  **ledger entries + slip state**, not from the order or the raw upload record. If a
  screen shows a payment as "paid" but no corresponding ledger entry exists, that is a
  bug to flag, not a display nuance.

## 4. Payment Slip Intelligence Module

A dedicated submodule (source requirements §13) responsible for reducing
fraud/duplicate risk in the manual slip path before a human ever has to catch it
manually.

- **OCR reference extraction.** The reference number printed/written on the slip is
  extracted automatically to feed the duplicate check below. OCR output is an input
  to review, not an auto-approval signal.
- **Duplicate reference number check** — mandatory gate. Must run, and must pass (or
  be explicitly overridden per below), before a slip can transition to `APPROVED`.
  There must be no approval code path that skips this check.
  - Scoped **per tenant** — a reference number colliding across two different tenants
    is not a duplicate. The check must query within the requesting tenant's slips
    only (structural tenant filtering per `.claude/rules/backend.md`, not an
    incidental `WHERE` clause).
- **Duplicate image hash check** — mandatory gate, same rule: must run and pass (or
  be explicitly overridden) before `APPROVED`, no bypass code path.
- **Manual override of a flag.** A reviewer may approve a slip despite a
  duplicate/suspicious flag, but every such override **must** write an audit log
  entry containing at minimum: reviewer identity, tenant, slip/reference id, the
  flag(s) overridden, a reason, and a timestamp. An override with no recorded reason
  is not a valid override and must be rejected by the system, not merely discouraged
  by policy.
- **Auto-flag, never auto-reject.** A flagged duplicate or suspicious slip must never
  be auto-rejected without a human reviewer in the loop. Auto-flagging (raising the
  flag for review) is allowed and expected; auto-rejection without human review is
  not.
- **Flags are additive, never cleared.** Suspicious-slip flags are additive metadata
  on the slip/review record. Re-running checks (e.g., after a system update to the
  detection logic) must add a new flag/result record — it must never clear or delete
  a prior flag. The full flag history for a slip stays queryable.

### 3-4 implementation note (MVP-011, shipped)

MVP-011 built §3's state machine and §4's exact-match duplicate/override-audit gates
as described above, with three deviations from this section's original target design,
each an explicit product-owner decision recorded in
`docs/adr/ADR-012-audit-log-slice-and-slip-enrollment-activation.md`:

- **OCR reference extraction (§4, first bullet) was NOT built.** MVP-011 is exact-match
  duplicate detection only (reference-number string equality, image-hash equality) —
  OCR-based extraction remains Phase 3, not pulled forward.
- **"Derived from ledger entries + slip state" (§3, last bullet) is NOT how the shipped
  Payment Dashboard/Payment History read paths work.** The product owner declined
  adding a new `ledger_entry.entry_type` for slip approval; `payment_slip.status` is
  never merged into the ledger at read time. Both screens remain 100%
  `ledger_entry`-derived, which means a slip-approved enrollment is invisible on
  either — not because a screen falsely claims "paid" with no ledger entry (this
  section's literal "that is a bug" framing doesn't apply, since neither screen
  displays a slip-approved row at all, correctly or otherwise), but because no code
  path combines the two signals as this section originally envisioned. This is a
  known, accepted product gap, not a defect — worth tracking as its own future
  decision (a slip-sourced report, or revisiting the declined `entry_type` option) if
  manual-slip revenue visibility becomes a real business need.
- **The override-audit requirement (§4, "Manual override of a flag") is satisfied by a
  new, minimal `com.lms.auditlogmanagement` domain** (a real `audit_log` table + a
  narrow `AuditLogApi.record(...)` write contract, no read/query UI), pulled forward
  specifically for this requirement rather than deferred — see ADR-012 for the full
  reasoning and the two alternatives it considered and rejected.

## 5. Ledger Entry Rules (Append-Only)

`ledger-settlement-management` owns the ledger. The ledger is the single source of
truth financial history is built from, and is explicitly append-only:

- A ledger entry, once written, is never `UPDATE`d or `DELETE`d — not by application
  code, not by migrations, not by ad hoc "fix the ledger row" scripts, not by a
  platform admin. Refunds, corrections, and reversals are always **new entries**
  (with an appropriate sign/type referencing what they affect), never mutations of
  existing rows.
- No repository method for ledger entities may expose `delete`/`deleteById`. Any
  update method on these entities should be limited to narrow, explicitly-justified
  status columns if truly unavoidable, and new rows are strongly preferred even for
  status changes.
- Every ledger entry carries a traceable link to:
  - the **tenant** it belongs to, and
  - the **order/payment** (Phase 1) or the **settlement run** (Phase 2) that produced
    it.
  An orphaned ledger entry with no source reference is invalid and should be treated
  as a data-integrity bug, not a display quirk.
- A refund/reversal entry references the original entry it reverses (e.g.
  `reverses_entry_id`), so the correction chain is queryable end-to-end — but the
  original entry itself is never touched.
- **Ledger entry types, and what effect each type has on enrollment/settlement
  state, are part of the change-controlled "payment ledger rules" area.** Adding,
  removing, or changing the meaning of a ledger entry type requires the same
  explicit-approval + ADR path as any other change-controlled item — this includes
  seemingly small changes like adding a new entry type for a new refund reason code,
  since that changes what downstream settlement/reporting logic must interpret.
- Where practical, invariants are enforced by the schema (CHECK constraints, FK
  relationships, `amount` sign conventions per entry type) rather than relying solely
  on service-layer discipline, consistent with `.claude/rules/backend.md`'s guidance
  for high-integrity domains.

## 6. Phase 2 — Settlement Rules

Once tutor/tenant settlement is reached (source requirements §12, Phase 2):

- **Settlement calculation must be idempotent.** Re-running a settlement job for a
  period/tenant that was already settled must not create duplicate payout ledger
  entries or double-pay. This is guarded by a **uniqueness constraint** on
  `(tenant_id, settlement_period, run marker)` — not application-logic-only checks
  (e.g. "check if already settled" in code is a UX nicety, not the actual guard).
- Commission and gateway-fee amounts are computed and **stored per settlement run**
  at the rate/config in effect at that time. A historical settlement's commission is
  never recomputed from current rates — historical settlement records must stay
  reproducible/stable even after the platform later changes its commission or fee
  configuration.
- If a settlement needs correction **after payout**, that is a new **adjustment
  settlement entry** referencing the original run. The original settlement's stored
  figures are never mutated.
- Settlement calculation, commission %, and fee-tracking rate *values* are business
  decisions this document does not supply — see Open Questions (§10).

## 7. Payment Expiry & Reactivation

- Expiry processing (course/session/material/video access lapsing because a payment
  period ended — see `docs/architecture/enrollment-access.md` §6 for the full expiry
  model) must never delete or mutate prior payment/ledger history. Expiry is a state
  change on enrollment/access, recorded as its own event; the payment record that
  originally granted access stays exactly as it was.
- Reactivation is always a **new payment/order** (and a new ledger entry) with a link
  back to the prior enrollment/course context. The system never resurrects or extends
  the old payment record's dates to simulate reactivation — that would be a mutation
  of financial history in disguise.
- This keeps a full, honest audit trail: "student paid, access expired, student paid
  again to reactivate" reads as three distinct, timestamped facts, not one edited
  record.

## 8. Phase 3 / Phase 4 — Forward Look (not yet designed in detail)

The following are confirmed as future roadmap direction but are **explicitly not
designed at the schema/API level yet**. They are recorded here so future work starts
from the right constraints, not as a spec to implement against today.

- **Phase 3 — Tenant-specific payment accounts.** Each tenant will eventually
  configure its own payment account/routing so that a student's payment for Tenant A's
  course is routed toward Tenant A's account rather than only ever landing in one
  platform-wide collection point. Open design questions include: how tenant payment
  account configuration is validated/secured, how it interacts with the still-active
  Phase 1/2 centralized-collection flow during migration, and what happens to
  in-flight orders when a tenant's payment account configuration changes. None of
  this should be scaffolded into Phase 1 tables preemptively.
- **Phase 4 — Split payments, gated on gateway support.** Marketplace-style split
  disbursement (platform commission automatically separated from tutor/tenant payout
  at the gateway level) is only in scope **if and when the actual gateway selected
  supports it**. This document intentionally does not name a gateway or assume a
  specific split-payment API shape — that would be inventing provider details ahead
  of an actual integration decision, which source requirements explicitly warns
  against. Do not implement any split-payment code path until (a) a gateway is
  approved via the normal integration/ADR process and (b) that gateway's actual
  split-payment capability is confirmed.
- Any implementation work that begins to touch Phase 3/4 concerns should first raise
  an ADR per `.claude/rules/architecture.md`'s "When an ADR is required" section,
  since it very likely also touches the change-controlled "payment ledger rules" and
  "approved API contracts" areas.

## 9. Change control summary

The following require explicit approval + an ADR under `docs/adr/` before
implementation, per root `CLAUDE.md` and `.claude/rules/payments.md` §7:

- Any new way for enrollment to activate.
- Any mutation or deletion path touching ledger or terminal payment rows.
- Skipping or bypassing the duplicate/suspicious slip checks.
- Recomputing historical settlement amounts from live rate config.
- Adding/removing/reinterpreting a ledger entry type.
- Moving any Phase 3/4 concern (tenant payment accounts, split payments) into Phase 1/2
  code or schema ahead of its own approved design.

## 10. Open questions (business decisions not supplied by current material)

These are flagged rather than answered with invented numbers:

- Exact commission percentage(s) and whether commission varies by tenant plan/tier.
- Exact gateway fee handling — is the fee tracked as a pass-through deduction in the
  settlement calculation, or absorbed by the platform? What if it varies by payment
  method?
- Settlement run cadence (weekly/monthly/on-demand?) and the exact definition of a
  "settlement period" boundary (calendar month vs. rolling window).
- Refund window / eligibility policy (how long after payment can a refund be
  requested, and does it depend on course start date, content consumption, etc.).
- Which specific payment gateway will be integrated, and what payment methods it
  supports — required before Phase 4 split-payment feasibility can even be assessed.
- Tenant-specific payment account model in Phase 3: does the platform ever "hold"
  funds pending gateway payout, and what regulatory/licensing implications (if any)
  that has — this is a business/legal decision, not an engineering one.

## Related

- `docs/architecture/enrollment-access.md`
- `docs/architecture/database-architecture.md`
- `docs/adr/ADR-003-centralized-payments-first.md`
