-- ledger-settlement-management (Wave 2 - FREE pricing model, ADR-015): widens
-- `ledger_entry.amount`'s CHECK constraint so a genuinely FREE-pricing-model
-- course's `$0` payment confirmation can write a real `PAYMENT_CONFIRMED`
-- ledger entry, exactly like every paid checkout does -
-- `OrderService#activateFreeCheckout` now calls the SAME
-- `LedgerEntryApi#recordPaymentConfirmed` method `PaymentConfirmationService`
-- calls for the real gateway-confirmation path - never a new/bespoke ledger
-- -write code path.
--
-- Why this is needed: Payment History and the Payment Dashboard are, per
-- `.claude/rules/payments.md` §2, required to be derived from ledger entries
-- + slip state - never from `order`/`payment` status alone ("If a screen
-- shows 'paid' but no corresponding ledger entry exists, that is a bug.").
-- V41 (`ck_payment_amount CHECK (amount >= 0)`) already let a `$0` `payment`
-- row reach `CONFIRMED`, but deliberately left `ledger_entry.amount`'s CHECK
-- untouched, as a separate, not-yet-approved decision (see V41's own header
-- comment) - without this migration, a FREE checkout would still activate
-- enrollment, but Payment History/the Payment Dashboard would silently show
-- nothing for it, which is exactly the invariant `.claude/rules/payments.md`
-- §2 forbids. This migration, and the corresponding `OrderService` change
-- that starts writing the entry, were both reviewed and approved together -
-- see `docs/adr/ADR-015-free-course-zero-amount-payment-and-ledger.md`.
--
-- V19 (`ck_ledger_entry_amount_nonzero CHECK (amount <> 0)`) is never
-- edited - already applied/shared. This is additive, constraint-widening
-- -only DDL under the SAME constraint name.
--
-- NOT a plain `amount >= 0` widening (unlike how V41 widened
-- `ck_payment_amount`, which only ever stores non-negative values): a naive
-- `amount >= 0` here would incorrectly forbid `REFUND` entries, which
-- `LedgerEntryService#recordRefund` stores with a NEGATIVE amount by design
-- (this module's own sign convention - see `LedgerEntry`'s javadoc). An
-- earlier draft of this migration made exactly that mistake and was caught
-- by `mvnw verify` itself (`PaymentAndLedgerIntegrationTest`/
-- `RefundIdempotencyConcurrencyIntegrationTest`/`RefundImmutabilityIntegrationTest`/
-- `PaymentRefundAuditIntegrationTest` all failed with a real
-- `DataIntegrityViolationException` on `ck_ledger_entry_amount_nonzero` the
-- moment a refund was recorded) before it ever reached review.
--
-- The corrected, entry-type-aware CHECK below is deliberately STRONGER than
-- a plain sign-agnostic widening would be, per `.claude/rules/backend.md`'s
-- guidance to prefer schema-enforced invariants over service-layer
-- discipline alone for payment-management/ledger-settlement-management:
--   * `PAYMENT_CONFIRMED` entries must be `>= 0` (now permits the FREE-course
--     `$0` case, same as `payment.amount`/`student_order.amount`).
--   * `REFUND` entries must remain strictly `< 0` (unchanged from today's
--     `LedgerEntryService#recordRefund` behavior) - a `$0` refund is not a
--     concept this migration introduces.
-- `ledger_entry.entry_type`'s own CHECK (`PAYMENT_CONFIRMED`/`REFUND` only,
-- V19) is untouched - adding a third entry type remains change-controlled
-- per `.claude/rules/payments.md` §4 and is not part of this migration.

ALTER TABLE ledger_entry
    DROP CONSTRAINT ck_ledger_entry_amount_nonzero;

ALTER TABLE ledger_entry
    ADD CONSTRAINT ck_ledger_entry_amount_nonzero CHECK (
        (entry_type = 'PAYMENT_CONFIRMED' AND amount >= 0)
        OR (entry_type = 'REFUND' AND amount < 0)
    );
