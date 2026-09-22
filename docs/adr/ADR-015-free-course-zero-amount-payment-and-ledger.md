# ADR-015: FREE-Course `$0` Payment/Ledger Confirmation and the Narrowed Auto-Activation Gate

## Status

**Accepted (2026-09-22)**, by the project owner, following a three-agent Phase E review
(architecture, security/tenant-isolation, payment-ledger) of the shipped Wave 2 (Course/Class
model + billing foundation) backend. All three reviews independently converged on the same
critical/high finding described below; the corrected scope and the new ledger-write decision
were both surfaced to the product owner as an explicit decision request and approved with the
specific, narrow scope recorded in this document — this is not a retroactive rubber-stamp of
code shipped without review, and it is not a unilateral engineering call.

## Context

`V41__allow_zero_amount_payment_for_free_courses.sql` widened `payment.amount`'s CHECK
constraint from `amount > 0` to `amount >= 0` so a genuinely `FREE`-pricing-model course could
create and auto-confirm a `$0` `payment` row, activating enrollment through the same
`Payment.confirm()` → `EnrollmentActivationApi` path a real gateway payment uses. V41's own
migration header describes the approved scope narrowly: *"so a FREE-pricing-model course can
create a payment row with amount=0"*.

The code that shipped alongside V41 did not implement that narrow scope. `OrderService`
(`payment-management`) gated its auto-activation branch on the **resolved checkout amount being
`$0`** (`amount.signum() == 0`), not on the course's `pricing_model` actually being `FREE`:

```java
if (amount.signum() == 0) {
    activateFreeCheckout(order, principal.userId(), courseId);
}
```

This is broader than approved. `course.price` (checked at `ONE_TIME` pricing) and
`course_billing_period.amount` (checked at `MONTHLY`/`SESSION` pricing) both accept `>= 0` with
no floor above zero — `CourseService#changePrice` and `BillingConfigurationService
#addBillingPeriod`'s request DTOs (`CoursePriceChangeRequest`, `CourseBillingPeriodRequest`) both
declare `@DecimalMin(value = "0.0", inclusive = true)`, not `"0.01"`. A staff typo or misuse
setting a `ONE_TIME` price, or a `MONTHLY`/`SESSION` billing period, to `$0.00` is therefore a
real, reachable misconfiguration — and the shipped code silently free-activated it exactly like a
genuine `FREE` course: no `payment_slip`/gateway evidence, no ledger entry (see below), no
distinguishing audit trail, and no test coverage of the distinction at all.

A second, related gap was found by the payment-ledger review specifically: even for a *genuine*
`FREE` checkout, no `ledger_entry` row was ever written for the `$0` payment confirmation.
`ledger_entry.amount`'s CHECK constraint (`ck_ledger_entry_amount_nonzero CHECK (amount <> 0)`,
`V19`) was deliberately left untouched by V41 — its own header comment explicitly deferred
"whether/how a free-course confirmation writes (or skips) a ledger entry" as a separate,
not-yet-approved decision. Per `.claude/rules/payments.md` §2, Payment History and the Payment
Dashboard "must be derived from ledger entries + slip state, not from the order or upload
record. If a screen shows 'paid' but no corresponding ledger entry exists, that is a bug." A
FREE checkout that activates enrollment but leaves no ledger row is exactly that bug: a student
shows as enrolled, but neither surface shows they were ever "paid" (for `$0`) at all.

## Decision

### 1. Auto-activation is gated on `course.pricing_model == FREE`, never on the resolved amount alone

`CourseLookupApi#getResolvedCheckoutAmount`'s `CheckoutAmount` DTO (`coursemanagement.api`)
gains a new field, `boolean freePricing` — `true` only when the course's own `pricing_model` is
genuinely `FREE`. This is a plain `boolean`, not the raw `course.domain.CoursePricingModel` enum,
so this cross-module contract never leaks a `course-management`-internal `domain`-package type
across the module boundary into `payment-management` (`.claude/rules/architecture.md`: a module
may depend only on another module's `api` package, never import another domain's `domain`
classes).

`OrderService.createOrder` now branches on `checkout.freePricing()`:

- `freePricing() == true` → unchanged auto-activation path (`activateFreeCheckout`), regardless
  of the resolved amount (always `$0` for `FREE` pricing by construction).
- `freePricing() == false` **and** the resolved amount is `$0` (a misconfigured `ONE_TIME`
  price, or a misconfigured `MONTHLY`/`SESSION` billing period) → the order is rejected outright
  with `ConflictException` (`409`), before any `student_order`/`payment` row is persisted:
  *"This course's configured price is zero but it is not priced as FREE - check the course's
  price/billing configuration."* Never silently free-activated, and never routed to a real
  payment gateway either (which cannot process an actual `$0` charge).
- `freePricing() == false` and the resolved amount is `> 0` → unchanged (the normal paid-checkout
  path).

### 2. A real `$0` `ledger_entry` is now written for every FREE checkout

`ledger_entry.amount`'s CHECK constraint is widened by a new, additive migration,
`V42__allow_zero_amount_ledger_entry_for_free_course_confirmations.sql` — `V19` is not edited,
same constraint name, same column.

Unlike V41's plain `amount >= 0` widening of `payment.amount` (which only ever stores
non-negative values), a plain `amount >= 0` widening of `ledger_entry.amount` is **incorrect**:
`REFUND` entries are stored with a NEGATIVE amount by this module's own sign convention
(`LedgerEntryService#recordRefund`, documented on `LedgerEntry`'s javadoc). An earlier draft of
this migration made exactly that mistake — `mvnw verify` caught it directly: four integration
tests (`PaymentAndLedgerIntegrationTest`, `RefundIdempotencyConcurrencyIntegrationTest`,
`RefundImmutabilityIntegrationTest`, `PaymentRefundAuditIntegrationTest`) failed with a real
`DataIntegrityViolationException` the moment a refund tried to insert its negative-amount
`ledger_entry` row against the too-strict `amount >= 0` constraint, plus one cascading
notification-dispatch test failure. This was caught and fixed before merge, not shipped and
patched later — recorded here per this ADR's own "decisions were reviewed, not shipped
unilaterally" framing.

The corrected constraint is entry-type-aware, and deliberately **stronger** than a plain
sign-agnostic widening would have been — per `.claude/rules/backend.md`'s guidance to prefer
schema-enforced invariants over service-layer discipline alone for
`payment-management`/`ledger-settlement-management`:

```sql
ALTER TABLE ledger_entry
    ADD CONSTRAINT ck_ledger_entry_amount_nonzero CHECK (
        (entry_type = 'PAYMENT_CONFIRMED' AND amount >= 0)
        OR (entry_type = 'REFUND' AND amount < 0)
    );
```

`PAYMENT_CONFIRMED` entries may now be `>= 0` (permitting the FREE-course `$0` case);
`REFUND` entries must remain strictly `< 0`, unchanged from today's behavior — a `$0` refund is
not a concept this decision introduces.

`OrderService#activateFreeCheckout` now calls `LedgerEntryApi#recordPaymentConfirmed(orderId,
paymentId, amount)` — the exact same method, in the exact same shape, that
`PaymentConfirmationService` calls for the real gateway-confirmation path — inside the same
transaction as the `$0` payment's `CONFIRMED` transition. This is not a new/bespoke ledger-write
code path, and it does not touch `LedgerEntryType`'s two-value set (`PAYMENT_CONFIRMED`/
`REFUND`, still change-controlled per `.claude/rules/payments.md` §4) — a FREE checkout writes
an ordinary `PAYMENT_CONFIRMED` entry with `amount = 0`, distinguished from a paid entry only by
its amount, exactly as a genuine `$0` payment naturally would be.

`REFUND` entries are unaffected: `LedgerEntryService#recordRefund` still negates its input
amount, and a refund of a `$0` FREE-course payment is not a scenario this decision introduces or
addresses.

## Consequences

**Positive**

- The distinction the review found missing — "genuinely FREE" vs. "misconfigured `$0`" — is now
  structural (a dedicated boolean on the resolved-checkout-amount contract), not something a
  future reader has to infer from `amount.signum()`.
- A misconfigured `$0` `ONE_TIME`/`MONTHLY`/`SESSION` course fails loudly and immediately (`409`,
  before any row is persisted) instead of silently granting free access with no payment evidence.
- Payment History and the Payment Dashboard (both ledger-derived, per `.claude/rules/payments.md`
  §2) now correctly show FREE enrollments, closing the "paid but no ledger entry" bug class for
  the `$0` case specifically.
- Both schema changes (V41, now joined by V42) remain narrow and additive — no existing row's
  meaning changes, no already-applied migration is edited.

**Negative / trade-offs accepted**

- `ledger_entry.amount`'s CHECK constraint retains its original name,
  `ck_ledger_entry_amount_nonzero`, even though it no longer forbids a zero amount for
  `PAYMENT_CONFIRMED` entries specifically — chosen deliberately to mirror V41's own precedent
  (`ck_payment_amount` also kept its original name after being widened) rather than introduce a
  rename; the constraint is still effectively "nonzero" for `REFUND` entries (`< 0`, never `= 0`),
  so the name is only a partial, narrower misnomer than an earlier draft's plain `amount >= 0`
  widening would have been.
- A tenant admin who genuinely wants a course to always resolve to `$0` without marking it `FREE`
  (e.g. to preserve the ability to easily flip a real price back on later while keeping billing-
  period history) cannot do so — that case must now use `pricing_model = FREE` explicitly, or set
  a real, nonzero price. This was judged acceptable: no product requirement was ever surfaced for
  a "$0 but not FREE" course, and the ambiguity that case would represent (is this genuinely free,
  or a bug?) is precisely what this ADR closes.

## Alternatives considered

- **Keep gating on `amount.signum() == 0`, add only an audit-log warning for the non-FREE-$0
  case** — rejected: still silently activates enrollment with no genuine payment evidence for a
  misconfigured course, which is the core defect the review flagged; a warning after the fact
  does not prevent the incorrect activation.
- **Reject silently (no order, no error surfaced) instead of `409 ConflictException`** — rejected:
  the caller (a student, or the frontend checkout flow acting on their behalf) needs a clear,
  actionable signal that checkout cannot proceed, consistent with every other rejection path in
  `OrderService#createOrder` (already-enrolled, reactivation-required, custom-quote-required all
  return `409`/`403` with a client-safe message, never a silent no-op).
- **Leave `ledger_entry.amount`'s CHECK constraint untouched, and instead let Payment
  History/Dashboard special-case FREE enrollments from `enrollment`/`payment` data directly** —
  rejected: `.claude/rules/payments.md` §2 requires these surfaces to be ledger-derived, not
  order/payment-derived; special-casing one pricing model would reintroduce exactly the
  inconsistency that rule exists to prevent, and would leave the underlying "a CONFIRMED payment
  exists with no matching ledger entry" data-integrity gap unaddressed for any other future `$0`
  payment source.
- **Expose the raw `CoursePricingModel` enum on `CheckoutAmount` instead of a derived boolean** —
  rejected: `CoursePricingModel` lives in `coursemanagement.course.domain`, a `domain`-package
  type; even though `coursemanagement.api.CheckoutAmount` (the same module) may reference it
  internally, `payment-management`'s `OrderService` importing it directly to compare against
  `CoursePricingModel.FREE` would violate `.claude/rules/architecture.md`'s "never import another
  domain's `domain` classes" rule from the *consuming* module's side. A narrow `freePricing`
  boolean is the minimal, correctly-scoped contract `OrderService` actually needs — mirroring
  `requiresManualQuote`'s existing precedent on the same record.

## Required follow-up if accepted

- If a future module needs to distinguish a FREE-course `PAYMENT_CONFIRMED` ledger entry from a
  paid one beyond "amount = 0" (e.g. for reporting), that is a new, separate decision — this ADR
  does not add a new `ledger_entry.entry_type` value or any other schema marker for "this entry
  came from a FREE checkout."
- Any future change to the `CheckoutAmount` contract (e.g. exposing additional pricing-model
  detail to `payment-management`) must continue to use narrow, derived fields rather than the raw
  `course.domain` enum, per the "Alternatives considered" entry above.

## Related

- `docs/plans/MVP-022 Course Billing and Lifecycle.md` — Wave 2 (Course/Class model + billing
  foundation) module plan (reconstructed retroactively after this ADR; not a substantive change
  to this decision, just a forward reference now that the plan file exists)
- `.claude/rules/payments.md` §1, §2, §4
- `.claude/rules/architecture.md` (cross-module `api`-package-only dependency rule)
- `V19__create_payment_management_schema.sql`
- `V41__allow_zero_amount_payment_for_free_courses.sql`
- `V42__allow_zero_amount_ledger_entry_for_free_course_confirmations.sql` (new, this decision)
- `com.lms.paymentmanagement.order.service.OrderService` (class javadoc references this ADR)
- `com.lms.coursemanagement.api.CheckoutAmount`, `CourseLookupApiImpl`,
  `CourseCheckoutAmountResolver`
