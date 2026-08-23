-- payment-management / ledger-settlement-management (MVP-010 six-specialist
-- review follow-up): closes two concrete data-integrity gaps identified
-- against the already-shared V19 schema. V19 itself is never edited (already
-- applied/shared) - both fixes land as new, additive DDL here.
--
-- 1. [Critical] Defensive backstop against a concurrent-duplicate-webhook
--    race: PaymentConfirmationService#confirmByGatewayReference now takes a
--    PESSIMISTIC_WRITE row lock before checking payment.status (see
--    PaymentRepository#findByGatewayReferenceAcrossTenantsForUpdate), which
--    is the primary fix - but per .claude/rules/backend.md's "prefer
--    invariants enforced by the schema/constraints over invariants enforced
--    only by service-layer discipline" guidance, this partial unique index
--    is a second, independent line of defense: even if the lock is ever
--    bypassed (a future refactor, a raw SQL fix, a bug), the database itself
--    rejects a second PAYMENT_CONFIRMED ledger_entry for the same payment.
--    Scoped to (tenant_id, payment_id) - not payment_id alone - to match
--    every other constraint in this schema's tenant-composite-FK convention,
--    even though payment_id is already globally unique to one tenant via
--    payment's own tenant scoping.
CREATE UNIQUE INDEX uq_ledger_entry_tenant_payment_confirmed
    ON ledger_entry (tenant_id, payment_id)
    WHERE entry_type = 'PAYMENT_CONFIRMED';

-- 2. [High] Refund resubmission idempotency: RefundService#processRefund had
--    no dedup mechanism - two identical resubmitted refund requests could
--    each individually pass the refundable-remainder check and together
--    over-refund. idempotency_key is nullable (existing/legacy callers that
--    never supply one behave exactly as before - no regression); the
--    partial unique index only applies once a key is actually supplied,
--    mirroring uq_payment_gateway_reference's "WHERE ... IS NOT NULL"
--    partial-index technique from V19 for the same reason (many rows will
--    have no key, and those must not collide with each other under a
--    NULL-inclusive uniqueness rule).
ALTER TABLE payment_refund ADD COLUMN idempotency_key UUID;

CREATE UNIQUE INDEX uq_payment_refund_tenant_original_payment_idempotency_key
    ON payment_refund (tenant_id, original_payment_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
