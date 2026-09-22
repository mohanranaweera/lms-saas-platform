-- payment-management (Wave 2 - FREE pricing model): widens `payment.amount`'s
-- CHECK constraint from `amount > 0` to `amount >= 0` so a FREE-pricing-model
-- course can create a `payment` row with `amount = 0` that is immediately
-- confirmed via the existing `Payment.confirm()` transition - enrollment
-- activation (which reads only `payment.status = CONFIRMED`, per V19/
-- `.claude/rules/backend.md`'s "activation must have a FK/NOT NULL trail back
-- to the specific confirmed payment" invariant) then works identically for
-- free and paid courses, with no new activation code path.
--
-- V19 (`ck_payment_amount CHECK (amount > 0)`) is never edited - already
-- applied/shared. This is additive, constraint-widening-only DDL: same
-- constraint name, same column, only the boundary changes from `> 0` to
-- `>= 0`. Mirrors `student_order.amount`'s own `ck_student_order_amount
-- CHECK (amount >= 0)` (V19), which already allowed zero for exactly this
-- "future $0/trial course" reason - `payment.amount` was the one column left
-- behind at `> 0` until now.
--
-- Deliberately scoped to `payment.amount` only:
--   * `student_order.amount` (`ck_student_order_amount`) already allows
--     `>= 0` - untouched.
--   * `payment_refund.amount` (`ck_payment_refund_amount CHECK (amount >
--     0)`) is untouched - a refund of a zero-amount payment is not a
--     scenario this change introduces, and refund-amount semantics are not
--     part of this approval.
--   * `ledger_entry.amount` (`ck_ledger_entry_amount_nonzero CHECK (amount
--     <> 0)`) is untouched and structurally unaffected either way - a
--     PAYMENT_CONFIRMED ledger entry for a zero-amount payment would violate
--     `<> 0` if ever written; per plan/V19 header comment, ledger entry
--     types/effects are change-controlled (`.claude/rules/payments.md` §4)
--     and are explicitly out of scope for this approval. Whether/how a
--     free-course confirmation writes (or skips) a ledger entry is a
--     separate, not-yet-approved decision.

ALTER TABLE payment
    DROP CONSTRAINT ck_payment_amount;

ALTER TABLE payment
    ADD CONSTRAINT ck_payment_amount CHECK (amount >= 0);
