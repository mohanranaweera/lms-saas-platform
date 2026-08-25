# ADR-012: Minimal `audit-log-management` Slice and `EnrollmentActivationApi.activateFromApprovedSlip`

## Status

**Accepted (2026-08-24)**, by the project owner, in the same session that approved backend
implementation of MVP-011 (Manual Payment Slips). Written to close a governance gap a
security-reviewer pass on the completed MVP-011 implementation surfaced: both decisions
below fall under root `CLAUDE.md`'s explicit change-controlled list ("payment ledger
rules", "enrollment activation rules"), and `docs/adr/ADR-010-ledger-entry-type-and-enrollment-slice.md`'s
own "Required follow-up if accepted" section states verbatim that Module 11 adding the
`activating_slip_id` FK or a slip-based enrollment activation path "requires their own new
migration and an amendment to (or a new ADR superseding) this one." This ADR is that
required sign-off — mirroring `ADR-010`/`ADR-011`'s own precedent of being written
retroactively, after the underlying code was already implemented under real, explicit
product-owner approval, because the ADR the module plan itself named as the sign-off
vehicle (`docs/plans/MVP-011 Manual Payment Slips.md` §21 items 2 and 3) had not yet been
produced when implementation began.

**The underlying approval itself is not retroactive** — both decisions were explicitly
presented to and approved by the product owner via a structured decision request
*before* the corresponding code was written (the same session, prior to delegating
implementation to `backend-springboot-engineer`/`database-architect`). Only the ADR
artifact recording that approval was written after the fact, consistent with this
repository's established pattern.

## Context

MVP-011's own plan (`docs/plans/MVP-011 Manual Payment Slips.md` §21 items 2-3) identified
two decisions as individually change-controlled and explicitly declined to make either
unilaterally:

### 1. The `audit-log-management` central scoping decision

SLIP-4's override-audit requirement (spec `08-manual-payment-slips.md` §9, spec
`25-duplicate-payment-slip-detection.md` §9 — both independently calling it "the single
most explicitly, repeatedly-stated audit requirement" in the ruleset) is a hard
*reject-if-missing* gate on the mutation itself, not best-effort logging. No
`com.lms.auditlogmanagement` domain existed anywhere in the codebase before this module —
MVP-010's `RefundService`/`PaymentConfirmationService` had already hit the identical gap
and substituted a structured `log.atInfo()` line, explicitly self-documented as "an
interim measure only, not a substitute for that module's eventual durable audit trail."
That interim posture cannot satisfy a *reject-the-mutation-if-the-audit-write-fails*
requirement, since a log line does not participate in the database transaction.

The plan's §21 item 2 named three live options and declined to choose: (A) a minimal
forward-pulled `audit-log-management` slice, mirroring `ADR-010`'s own precedent for the
minimal `enrollment-management` slice pulled into MVP-010; (B) an interim domain-local
override-audit table plus a published event, mirroring MVP-008's `course_price_history`
precedent; or (C) deferring SLIP-4 entirely until Module 19 is built as its own module.

### 2. `EnrollmentActivationApi.activateFromApprovedSlip(...)`

SLIP-3's atomic approve-to-activation requirement is structurally untestable and
unbuildable without a real, callable slip-based enrollment-activation method — mirroring
the exact rationale `ADR-010` already accepted once for `activateFromConfirmedPayment`.
`ADR-010` reserved schema room for this (`enrollment.activating_slip_id`, nullable, no FK
target) but explicitly did not authorize the code path itself, naming it as requiring "an
amendment to (or a new ADR superseding)" that document before implementation.

## Decision

Both options were presented to the product owner as an explicit decision request before
any implementation code was written. The product owner approved:

### `audit-log-management` — Option (A), the minimal forward-pulled slice

A new `com.lms.auditlogmanagement` domain package, shipped inside MVP-011's own PR rather
than deferred to a separately-coordinated Module 19 PR or degraded to option (B)'s
domain-local shim:

- **`audit_log` table** (`V21__create_payment_slip_schema.sql`): `id`, `tenant_id`,
  `actor_id`, `action`, `target_entity`, `target_id`, `reason` (nullable), `metadata`
  (nullable, JSON-serialized), `occurred_at` — every identity/target/time column
  `NOT NULL`, per `.claude/rules/backend.md`'s audit-log schema-enforced-invariants
  section. Composite FK `actor_id -> tenant_user(tenant_id, id)`; `target_id` carries no
  FK (polymorphic target across future domains, validated at the service layer, not the
  schema layer). Fully append-only: `AuditLogRepository` overrides every delete-shaped
  method inherited from `TenantAwareRepository`/`JpaRepository` to throw
  `UnsupportedOperationException`.
- **`AuditLogApi.record(AuditLogEntry entry)`** (`com.lms.auditlogmanagement.api`) — the
  only contract other domains may depend on. Resolves `tenant_id`/`occurred_at`
  internally from `TenantContext`/`Instant.now()`; never accepts a caller-supplied tenant
  id or timestamp. Implemented with Spring's default `REQUIRED` transaction propagation
  specifically so the write joins the caller's already-open transaction (e.g.
  `SlipReviewService#approve`) rather than opening its own — the property MVP-010's
  `log.atInfo()` interim measure could not provide, and the one this ADR exists to
  authorize.
- **Deliberately minimal scope, matching `ADR-010`'s own narrowing of the enrollment
  slice**: no read/query endpoint, no admin-facing audit-log viewer, no consumption of
  other domains' already-pending events (e.g. `course-management`'s
  `CoursePriceChangedEvent`), no retention/purge policy. This module writes to the table;
  nothing yet reads from it. Module 19's eventual full build-out (query UI, cross-domain
  event consumption, retention policy) remains that module's own scope, not pulled
  forward here.

### `EnrollmentActivationApi.activateFromApprovedSlip(...)` — approved as a new activation path

- `EnrollmentActivationApi` (`com.lms.enrollmentmanagement.api`) gains
  `void activateFromApprovedSlip(UUID slipId, UUID studentId, UUID courseId)`, alongside
  the existing `activateFromConfirmedPayment`.
- `Enrollment` gains a parallel `fromApprovedSlip(...)` static factory, funneling through
  the same private constructor that enforces
  `ck_enrollment_exactly_one_activation_source` (exactly one of `activating_payment_id`/
  `activating_slip_id` non-null) at construction time, not only at the DB level.
- `EnrollmentActivationService.activateFromApprovedSlip` independently re-verifies via a
  new `SlipStatusApi.isApprovedForCurrentTenant(slipId)` before activating — never trusts
  the calling service's claim alone, mirroring `activateFromConfirmedPayment`'s existing
  `PaymentStatusApi` re-verification exactly.
- Idempotency reuses the existing `uq_enrollment_tenant_student_course` constraint and
  catch-`DataIntegrityViolationException` pattern verbatim — no new, weaker idempotency
  mechanism was introduced.
- `V21__create_payment_slip_schema.sql` adds `fk_enrollment_activating_slip` as a new
  `ALTER TABLE` statement; `V19`/`V20` were not edited.
- The only call site is `SlipReviewService.approve(...)`, inside one `@Transactional`
  method that also writes the slip's `APPROVED` status and the audit entry — all three
  commit or roll back together, satisfying `.claude/rules/backend.md`'s atomic
  payment-confirmation-plus-activation rule, extended by the same logic to the slip path.

### Declined, for the avoidance of doubt

No new `ledger_entry.entry_type` value was added, and none was approved. The plan's §21
item 3 named this as a live option (e.g. a `SLIP_APPROVED` type); the product owner
declined it in favor of reading slip approval state directly from `payment_slip`,
never merging it into the ledger. `ledger_entry`'s CHECK constraint remains exactly the
two values `ADR-010` already fixed (`PAYMENT_CONFIRMED`, `REFUND`) — unchanged by this
ADR or by MVP-011's implementation.

## Consequences

**Positive**

- SLIP-4's override-audit acceptance criterion is genuinely, durably satisfiable today —
  a reasonless override is rejected before any row lock, state change, or audit write
  (verified by a dedicated security-review pass), and a valid override's audit row is
  atomic with the approval it documents, not a best-effort side call.
- `audit-log-management` now has a real schema and write contract other domains (starting
  with Module 19's eventual own build-out) can build on without reconciling a
  domain-local shim later — the exact rework `ADR-010`'s enrollment-slice precedent was
  chosen specifically to avoid.
- SLIP-3's atomic activation is genuinely testable end-to-end, mirroring
  `PaymentConfirmationRollbackIntegrationTest`'s existing rollback-proof technique for the
  gateway-payment path.
- No Phase 2/3/4 payment-roadmap concept, and no new `ledger_entry.entry_type`, was
  introduced — the scope stayed exactly as narrow as the plan's own recommendation.

**Negative / trade-offs accepted**

- `com.lms.auditlogmanagement` now exists as a partially-built domain before Module 19
  formally begins — its eventual owner inherits a real table and write contract rather
  than a clean slate, and must reconcile their own scope (query UI, event consumption
  from other domains, retention policy) against what already exists here, mirroring the
  identical trade-off `ADR-010` already accepted for `enrollment-management`.
- `EnrollmentActivationApi` now has two structurally-approved activation call sites
  (confirmed payment, approved slip); any future third source of activation evidence
  needs its own equivalent sign-off, not an implicit extension of this one.

## Alternatives considered

- **Option (B) for the audit gap** — an interim `payment-management`-local override-audit
  table plus a published domain event, mirroring MVP-008's `course_price_history`
  precedent. Not chosen: unlike `course_price_history` (which satisfies a "confirmable,
  audit-considered" requirement with no hard reject-on-failure gate), SLIP-4's requirement
  is explicitly a gate on the mutation itself — a domain-local shim would duplicate the
  schema Module 19 needs anyway and fragment the "one canonical audit trail" property, for
  a requirement where "eventually reconciled" was judged not good enough given how
  repeatedly and explicitly the source specs frame this as non-negotiable.
- **Option (C), deferring SLIP-4 entirely** — shipping only SLIP-1..3 in this PR and
  coordinating a second PR once Module 19 exists as its own module. Rejected as
  unnecessarily narrow given option (A) was buildable now at a genuinely minimal scope,
  with no speculative Module-19 features pulled forward beyond the one write path this
  module actually needs.
- **A stub-only `activateFromApprovedSlip`**, deferring the real activation path to a
  separately-coordinated Module 12 PR. Rejected for the same reason `ADR-010` rejected the
  equivalent option for `activateFromConfirmedPayment`: it would leave SLIP-3's atomic-
  transaction acceptance criterion untestable until that later PR lands.

## Required follow-up if accepted

- Confirm with whoever owns Module 19 (`audit-log-management`) that the scope-crossing
  described above is acceptable, and record their sign-off here or in a superseding ADR —
  this repository currently has a single owner, with no distinct Module 19 owner yet
  assigned; re-confirm explicitly if that changes before Module 19 is built in full.
- If a future module needs `audit-log-management` to consume domain events from other
  modules (e.g. `CoursePriceChangedEvent`) or expose a query/read endpoint, that is new
  scope requiring its own review against this ADR's "deliberately minimal" framing, not an
  assumed extension of it.
- If a future module proposes a third `EnrollmentActivationApi` activation source, or a
  `ledger_entry.entry_type` value for the slip path, both remain open, unapproved
  questions per this ADR and `ADR-010` — neither is authorized by this document.

## Addendum: object-storage port relocation (recorded, not separately approved)

The module plan's §9 originally recommended a **module-local**
`com.lms.paymentmanagement.slip.storage.SlipStorageApi` port, explicitly deciding
against reusing or promoting a shared storage port. The shipped implementation instead
promoted `ObjectStorageApi`/`SignedDownloadUrl`/`StoreObjectCommand`/`StoredObject`/
`UnavailableObjectStorageApi` out of `content-management` (their original MVP-009 home)
into a new `com.lms.integrationmanagement.api`/`integrationmanagement.storage` location,
shared by both `content-management` and `payment-management`. This is architecturally
sound and matches `.claude/rules/architecture.md`'s existing guidance that
`integration-management` owns third-party storage integrations — a later architecture
review confirmed zero dangling references to the old package and no change in
tenant/access-control semantics for existing `MaterialService`/`MaterialController`
callers. It is recorded here only because it is a real, undocumented deviation from the
plan's own §9 recommendation, not because it required its own change-control sign-off
(it does not touch multi-tenancy, auth, payment-ledger, or deployment-strategy rules).

## Related

- `docs/plans/MVP-011 Manual Payment Slips.md` §7-10, §16-17, §21 items 2-3
- `docs/adr/ADR-010-ledger-entry-type-and-enrollment-slice.md`
- `.claude/rules/payments.md` §3, §7-8
- `.claude/rules/backend.md` (audit-log schema-enforced-invariants section)
- `V21__create_payment_slip_schema.sql`
- `AuditLogApi.java`, `AuditLogService.java`, `AuditLog.java`, `AuditLogRepository.java`
- `EnrollmentActivationApi.java`, `EnrollmentActivationService.java`, `Enrollment.java`
- `SlipReviewService.java`
