# ADR-013: Enrollment Lineage-Row Model and Reactivation Order-Creation Gate

## Status

**Accepted (2026-08-25)**, by the project owner, in the same session that approved backend
implementation of MVP-012 (Enrollment and Course Access). Written before any implementation
code, per `docs/plans/MVP-012 Enrollment and Course Access.md` §20 step 1's explicit
requirement — unlike `ADR-010`/`ADR-012`, this sign-off is not retroactive: both decisions
below were presented to and approved by the product owner via a structured decision request
before `database-architect`/`backend-springboot-engineer` were delegated any implementation
work.

## Context

MVP-012's own plan (`docs/plans/MVP-012 Enrollment and Course Access.md` §7, §9) identified
two decisions as individually change-controlled under root `CLAUDE.md`'s "enrollment
activation rules" and "database migration history" categories, and explicitly declined to
make either unilaterally.

### 1. Enrollment lineage-row model (§7)

The shipped `enrollment` table (`V19`/`V21`, per `ADR-010`/`ADR-012`) has a plain
`UNIQUE(tenant_id, student_id, course_id)` constraint and every column `updatable=false` —
one immutable row per (tenant, student, course), forever. Adding course-level expiry (ENR-2)
and reactivation (ENR-3) requires either:

- **(A) Lineage of rows**: replace the plain unique constraint with a partial unique index
  scoped to "current" rows (`WHERE superseded_at IS NULL`); a reactivation writes a brand-new
  `enrollment` row and sets exactly one new column (`superseded_at`) on the prior row, never
  touching its `activating_payment_id`/`activating_slip_id`/`activated_at`.
- **(B) Mutate in place**: keep one row per (student, course) and update
  `access_expires_at`/`activating_payment_id` on reactivation.

Option (B) was rejected in planning because it would overwrite the original activation
evidence, directly violating the "original expired payment record untouched" and "full
timeline reconstructable from history alone" requirements in
`docs/architecture/enrollment-access.md` §7.

### 2. `OrderService` reactivation order-creation gate (§9)

`OrderService.createOrder` (owned by `payment-management`) has no precondition today for a
student re-ordering a course they already have an enrollment for, because expiry did not
exist before this module. Making reactivation the *only* path back to access requires a new
guard: reject repeat orders for a still-active enrollment (`409`, "already enrolled"), and
reject repeat orders for an expired enrollment unless a matching `APPROVED`, unfulfilled
`reactivation_request` exists (`409`, "reactivation approval required"). This adds a new
`payment-management → enrollment-management` read/write dependency (`OrderService` calling
`EnrollmentAccessApi`/`ReactivationLinkingApi`), added to `OrderService`'s existing creation
transaction. Note this is NOT a new *direction* of dependency between these two modules —
`enrollment-management` already depends on `payment-management` (via `PaymentStatusApi`/
`SlipStatusApi`, `ADR-010`/`ADR-012`), so this module's own two dependencies already formed
a circular relationship before this ADR; see the Consequences section below for why that is
accepted, not newly introduced.

## Decision

Both options were presented to the product owner as an explicit decision request before any
implementation code was written. The product owner approved:

### Enrollment lineage-row model — Option (A)

- `enrollment` gains three additive columns in a new migration
  (`V22__create_enrollment_expiry_and_reactivation_schema.sql`): `access_expires_at`
  (nullable `TIMESTAMPTZ`), `superseded_at` (nullable `TIMESTAMPTZ`),
  `reactivated_from_enrollment_id` (nullable `UUID`, composite FK back to
  `enrollment(tenant_id, id)`). `V19`/`V20`/`V21` are not edited.
- `uq_enrollment_tenant_student_course` (plain unique) is dropped and replaced by
  `uq_enrollment_tenant_student_course_current`, a partial unique index on
  `(tenant_id, student_id, course_id) WHERE superseded_at IS NULL`.
- `Enrollment`'s existing columns stay `updatable=false`; `supersededAt` is settable exactly
  once via a new narrow package-private mutator (never a public setter), and only by
  `EnrollmentActivationService`'s new reactivation methods, atomic with the new row's insert.
- `EnrollmentActivationApi` gains `reactivateFromConfirmedPayment` /
  `reactivateFromApprovedSlip`, mirroring the existing pair's independent
  `PaymentStatusApi`/`SlipStatusApi` re-verification discipline exactly — these are not a
  third class of activation evidence, only a lineage-aware wrapper around the same two
  already-approved evidence types (`ADR-010`, `ADR-012`). A later, purely additive
  consolidation (`activateOrReactivateFromConfirmedPayment`/`activateOrReactivateFromApprovedSlip`)
  moved the "resolve current access state, then branch between the first-time-activation and
  reactivation methods above" decision into `EnrollmentActivationService` itself — previously
  duplicated near-identically at both `PaymentConfirmationService`'s and `SlipReviewService`'s
  own call sites. This does not add a new activation-evidence source or change either existing
  method's signature/semantics; it is recorded here explicitly (rather than only in the
  interface's own javadoc) so this module's activation surface stays fully traceable from this
  ADR, per root `CLAUDE.md`'s change-controlled "enrollment activation rules" category.
- Access currency (`ACTIVE`/`EXPIRED`/`NEVER_ENROLLED`) is never stored as an enum value on
  the row — always computed live from `superseded_at`/`access_expires_at` vs. `now()` in one
  shared, unit-tested method (`EnrollmentExpiryService.resolveAccessState`).

### `OrderService` reactivation order-creation gate — approved as specified in §9

- New `enrollment-management`-owned API surface, read by `payment-management`:
  `EnrollmentAccessApi.resolveAccessState(studentId, courseId)` and
  `ReactivationLinkingApi.linkApprovedRequestToNewOrder(studentId, courseId, newOrderId)`.
- `OrderService.createOrder` gains three-way branching before order creation: no enrollment
  → unchanged; current + active → `409 CONFLICT`; current + expired → require
  `ReactivationLinkingApi` to find and link an `APPROVED`, unfulfilled
  (`new_order_id IS NULL`) `reactivation_request`, else `409 CONFLICT`. On success, the link
  write (`reactivation_request.new_order_id = order.id`) happens inside the same transaction
  as order creation.
- `ReactivationLinkingApi` resolves tenant identity exclusively from `TenantContext`, mirroring
  `PaymentStatusApi`/`SlipStatusApi` — no overload accepts a caller-supplied tenant id.
- No new `ledger_entry.entry_type` value. Reactivation's new payment/slip goes through the
  exact same `PAYMENT_CONFIRMED` path `ADR-010` already fixed; this module never writes to
  `payment`/`ledger_entry`/`payment_refund` directly.

### Declined, for the avoidance of doubt

- No grace period, no expiry-rules precedence engine, no prorated/partial reactivation
  payment, no tenant-configurable "skip approval" toggle, no Finance-Staff reactivation-
  approve grant beyond the already-shipped Tenant-Admin-only `ACCESS_EXPIRY`/`APPROVE`. All
  carried forward as explicitly open, unresolved questions per plan §21, not decided by this
  ADR.

## Consequences

**Positive**

- ENR-2/ENR-3's acceptance criteria ("full timeline reconstructable from history,"
  "reactivation never mutates the original payment/enrollment row") are structurally
  guaranteed by the schema itself, not by service-layer discipline alone — the partial unique
  index makes "at most one current row" a database-enforced invariant, and the composite FK
  makes lineage queryable.
- Reactivation reuses every existing atomicity/idempotency/re-verification pattern
  (`activateFromConfirmedPayment`'s `PaymentStatusApi` check, the
  catch-`DataIntegrityViolationException` race guard, `AuditLogApi.record`'s same-transaction
  join) rather than introducing a parallel, weaker mechanism.
- The `OrderService` gate is the only place "can this student re-order this course" is
  decided — no duplicate, inconsistent check anywhere else in `payment-management` or
  `enrollment-management`.

**Negative / trade-offs accepted**

- `enrollment-management` now has a genuine lineage-row model (an extra join to find "the
  current row" for any future read), a persistent complexity cost accepted in exchange for
  never mutating historical activation evidence.
- `payment-management`'s `OrderService` now has a new, non-trivial read/write dependency on
  `enrollment-management` at order-creation time — a new cross-domain call inside an
  already-existing transaction boundary, which any future `OrderService` change must account
  for.
- This makes explicit a circular module dependency between `payment-management` and
  `enrollment-management` that already existed before this ADR (`enrollment-management` →
  `payment-management` via `PaymentStatusApi`/`SlipStatusApi`, now joined by
  `payment-management` → `enrollment-management` via `EnrollmentAccessApi`/
  `ReactivationLinkingApi`). `.claude/rules/architecture.md` instructs avoiding circular
  module dependencies in general; this ADR accepts the circularity as a bounded, narrow
  exception — both directions are single, well-defined `api`-package reads/writes with no
  transitive fan-out — rather than reworking either module's boundary to avoid it, since doing
  so (e.g. moving order-creation preconditions into `enrollment-management`, or moving
  payment/slip status checks out of `enrollment-management`) would cost more architectural
  clarity than the circularity itself costs.

## Alternatives considered

- **Mutate `enrollment` in place on reactivation** (Option B above) — rejected: overwrites
  original activation evidence, which every downstream acceptance criterion in
  `docs/architecture/enrollment-access.md` §7 and this plan's §5 explicitly forbids.
- **A soft/advisory check in `OrderService`** (log a warning but allow the repeat order
  regardless of enrollment/reactivation state) — rejected: would let a student bypass the
  reactivation-approval workflow (ENR-3's central control) simply by placing a new order
  directly, defeating the entire "reactivation must be admin-approved" requirement.
- **A new `ledger_entry.entry_type` for reactivation** — not proposed by the plan and not
  approved here; reactivation's payment is ordinary `PAYMENT_CONFIRMED`, distinguished only by
  the `enrollment` row's `reactivated_from_enrollment_id` lineage pointer.

## Required follow-up if accepted

- If a future module (`content-management`/`video-access-management`) wires itself to the new
  `EnrollmentAccessApi`, that consumption is that module's own scope/PR, not an assumed
  extension of this ADR (plan §6, §21 item 10).
- If a future change proposes a third `EnrollmentActivationApi` activation-evidence source
  beyond confirmed-payment/approved-slip, or a `ledger_entry.entry_type` value for
  reactivation, both remain open, unapproved questions per this ADR, `ADR-010`, and
  `ADR-012` — neither is authorized by this document.
- The open questions carried in plan §21 (grace period, precedence engine, prorated
  reactivation payment, bulk extension, tenant-configurable approval, Finance-Staff approve
  grant) remain unresolved and must not be silently assumed answered by this ADR.

## Addendum 1: `ReactivationTransactionService`'s `REQUIRES_NEW` boundary — resolved (2026-08-30)

A post-implementation 4-agent review (security/database/architecture/QA) of the shipped MVP-012
backend surfaced a real gap this ADR's original decision did not anticipate: `EnrollmentActivationService`'s
reactivation methods (`reactivateFromConfirmedPayment`/`reactivateFromApprovedSlip`) delegated the actual
`enrollment` supersede+insert to `ReactivationTransactionService`, which ran in its own
`Propagation.REQUIRES_NEW` transaction. This was originally introduced as a fix for an earlier bug (a
reactivation refusal was marking the *caller's* payment/slip-confirmation transaction rollback-only,
losing an already-`CONFIRMED`/`APPROVED` write). But because a `REQUIRES_NEW` transaction commits
independently of, and before, the caller's own transaction, a later, unrelated failure in the caller
(after the reactivation had already committed, but before `PaymentConfirmationService`/`SlipReviewService`
itself committed) could leave a durably reactivated enrollment paired with a payment/slip that never
actually reached its terminal `CONFIRMED`/`APPROVED` state — the reverse of the failure mode this ADR's §9
design and `.claude/rules/backend.md`'s payment-activation atomicity rule exist to prevent. A prior revision
of this addendum accepted this as a monitored, unfixed risk; a follow-up review determined the underlying
fix was in fact straightforward and shipped it, superseding that acceptance.

**Root-cause correction: `REQUIRES_NEW` was never necessary to fix the original bug.** The original bug was
that an `IllegalStateException` thrown during reactivation propagated *uncaught* out of the caller's own
`@Transactional` method, which is what actually marks a Spring-managed transaction rollback-only. The fix
that shipped alongside `REQUIRES_NEW` also added an explicit `catch (IllegalStateException)` around the
reactivation call inside `PaymentConfirmationService#confirmByGatewayReference`/`SlipReviewService#approve`
themselves. An exception caught entirely *inside* the boundary of an `@Transactional` method — never
propagating past that method's own return — never reaches Spring's `TransactionInterceptor` and therefore
never marks the transaction rollback-only in the first place. That catch block was therefore already
sufficient on its own; the `REQUIRES_NEW` transaction boundary was solving a problem the catch block had
already solved, while introducing the commits-independently-and-before-the-caller gap described above.

**The one genuine reason `REQUIRES_NEW` mattered: `DataIntegrityViolationException` is a real Postgres
statement failure, not a plain Java exception.** Postgres aborts an entire transaction on any statement
error unless a savepoint is used, and `Propagation.NESTED` (the savepoint-based mechanism that would let a
caught constraint violation coexist safely with a shared transaction) was investigated and confirmed
non-viable with this codebase's `JpaTransactionManager`/Hibernate stack (the stock `HibernateJpaDialect`
never returns an object implementing Spring's `SavepointManager`, so `NestedTransactionNotSupportedException`
is unavoidable without a custom, Spring-unsupported `JpaDialect` — full investigation detail preserved in
`ReactivationTransactionService`'s class javadoc). Catching that specific exception inside a shared
transaction and continuing to use it would genuinely have been unsafe.

**Resolution shipped:** `ReactivationTransactionService`'s two methods, and `EnrollmentActivationService`'s
`reactivateFromConfirmedPayment`/`reactivateFromApprovedSlip` wrappers around them, now carry NO
`@Transactional` annotation of their own at all — not `REQUIRES_NEW`, and, after a first attempt that had to
be reverted, not even plain `@Transactional` (default `REQUIRED`). A first attempt at this fix simply
switched `REQUIRES_NEW` to default `@Transactional` propagation, reasoning that the caller's existing
`catch (IllegalStateException)` would be sufficient — but Spring's `TransactionInterceptor` marks a
transaction rollback-only the moment an exception escapes *any* `@Transactional`-annotated method's own
proxy boundary, regardless of propagation type and regardless of whether an outer caller eventually catches
it. Since the refusal-signaling `.orElseThrow(...)` calls live inside these methods, marking them
`@Transactional` (even `REQUIRED`) reintroduced the exact original bug — confirmed by a real Testcontainers
run where `PaymentConfirmationReactivationRefusalIntegrationTest`/`SlipApprovalReactivationRefusalIntegrationTest`
started failing with `500`s where they expect `200`. Removing the annotation entirely closes this: with no
transactional advice of their own, these methods execute as ordinary Java calls inside the CALLER's single
already-open transaction, so a refusal crosses no proxy boundary until it reaches the caller's own catch
block, and a success's writes commit only when the caller's own transaction commits — making the `enrollment`
mutation and its confirming payment/slip write genuinely atomic, the property this ADR's §9 design always
intended. This is safe against the `DataIntegrityViolationException` concern above because two independent,
already-shipped safeguards make that specific race structurally unreachable in normal operation:

- `PaymentConfirmationService#confirmByGatewayReference`/`SlipReviewService#approve` already take a
  `PESSIMISTIC_WRITE` lock on the payment/slip row before reaching this code path, so two concurrent
  deliveries of the *same* evidence (a retried webhook/approval) fully serialize before either can reach
  `ReactivationTransactionService` — the second delivery observes the already-terminal payment/slip status
  and returns via the idempotent no-op path long before any `enrollment` row is touched.
- `uq_reactivation_request_tenant_enrollment_live` (V24, see Addendum 2 below) now schema-enforces "at most
  one reactivation request per enrollment that could still result in a future order," closing the
  previously-service-layer-only gap where two *different* orders could each end up linked to a live
  reactivation request for the same enrollment.

Combined, the only remaining path into `ReactivationTransactionService`'s supersede+insert is exactly one
uniquely-linked order's confirming payment/slip. The `DataIntegrityViolationException` catch is kept as a
defense-in-depth backstop, not the primary safety mechanism it used to be: if it is ever genuinely hit
despite the above (e.g. a future regression reintroduces a race), the whole ambient transaction aborts and
the caller's own webhook/approval-retry semantics safely recover on redelivery — a fail-safe outcome, not a
silent corruption.

**`EnrollmentReconciliationApi` is retained, relocated to its correctly-attributed home
(`enrollment-management`, matching what this addendum always specified), and reimplemented without raw
cross-schema SQL** — see Addendum 2. It remains a valid general-purpose drift diagnostic (covering, e.g.,
direct data tampering or a future regression) even though the specific gap it was originally built to detect
is now closed by construction rather than by monitoring.

## Addendum 2: `EnrollmentReconciliationApi` placement and implementation — corrected (2026-08-30)

The version of `EnrollmentReconciliationApi`/`EnrollmentReconciliationService` first shipped alongside
Addendum 1 above was implemented in `payment-management` (package `com.lms.paymentmanagement.api`/
`com.lms.paymentmanagement.reconciliation.*`), reading `enrollment`/`payment`/`payment_slip` directly via
one raw `JdbcTemplate` SQL statement. This contradicted this addendum's own text (above), which always
specified `enrollment-management` as the correct home, and violated `.claude/rules/architecture.md`'s rule
that a module may depend only on another module's `api` package — the shipped code reached across the
module boundary by table name, not through an `api` interface, with no ADR exception recorded for doing so.

**Corrected:** `EnrollmentReconciliationApi`/`OrphanedEnrollmentEvidence` now live in
`com.lms.enrollmentmanagement.api`, and `EnrollmentReconciliationService` in
`com.lms.enrollmentmanagement.service`. It batch-reads this module's own `enrollment` rows (a new,
explicitly-named cross-tenant repository method, `EnrollmentRepository#findAllWithActivationEvidenceAcrossTenants`,
mirroring `PaymentRepository#findByGatewayReferenceAcrossTenants`'s ADR-006 convention) and cross-checks each
row's evidence id against its actual terminal status one row at a time via the existing, already-approved,
single-id `PaymentStatusApi#isConfirmedForCurrentTenant`/`SlipStatusApi#isApprovedForCurrentTenant` — never a
raw SQL statement reaching into `payment-management`'s tables. This costs one cross-module call per flagged
row (an acceptable trade-off for a low-frequency ops diagnostic, not a request-time hot path) instead of
growing `PaymentStatusApi`/`SlipStatusApi` with a new batch/cross-tenant variant whose only caller would be
this one diagnostic. Still read-only, still deliberately unwired (no `@Scheduled` job, no admin
endpoint/controller) — see `EnrollmentReconciliationApi`'s own javadoc for the full, current rationale.

## Related

- `docs/plans/MVP-012 Enrollment and Course Access.md` §7, §9, §14-17, §20-21
- `docs/adr/ADR-010-ledger-entry-type-and-enrollment-slice.md`
- `docs/adr/ADR-012-audit-log-slice-and-slip-enrollment-activation.md`
- `.claude/rules/payments.md`, `.claude/rules/tenancy.md`
- `docs/architecture/enrollment-access.md`
- `V19__create_payment_management_schema.sql`, `V21__create_payment_slip_schema.sql`
- `V22__create_enrollment_expiry_and_reactivation_schema.sql` (new, this module)
- `V23__add_reactivation_and_enrollment_indexes.sql`,
  `V24__strengthen_reactivation_request_live_uniqueness.sql` (new, this module — Addendum 1)
