-- Wave 6 §3.3/§5: closes two genuine double-submission gaps confirmed by
-- reading OrderService#createOrder/PaymentInitiationService#initiatePayment
-- in full (plan §1.5) - neither previously accepted nor checked an
-- idempotency key, so a double-submitted checkout click could create two
-- `student_order` rows for the same course, and a double-submitted "Pay Now"
-- click could create two PENDING `payment` rows against the same order.
--
-- Mirrors `payment_refund.idempotency_key` (V20) EXACTLY - the one existing
-- idempotency mechanism in this module - both columns are nullable (existing
-- callers that never supply a key keep today's behavior exactly, no
-- regression) with a partial unique index that only applies once a key is
-- actually supplied (many rows will have no key, and those must not collide
-- with each other under a NULL-inclusive uniqueness rule).
--
-- Both indexes lead with `tenant_id`, per .claude/rules/backend.md's "unique
-- within a tenant" guidance - never a bare global-unique key.

-- A repeated checkout submission for the SAME student with the SAME
-- client-generated key replays the existing order instead of creating a
-- second one. Scoped per (tenant, student) rather than per-course, mirroring
-- payment_refund's per-original-payment scoping shape - a client only needs
-- dedup within its own retry sequence, not a platform-wide uniqueness
-- constraint (plan §10 judgment call 4).
ALTER TABLE student_order ADD COLUMN idempotency_key UUID;

CREATE UNIQUE INDEX uq_student_order_idempotency
    ON student_order (tenant_id, student_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

-- A repeated "Pay Now" click for the SAME order with the SAME
-- client-generated key replays the existing PENDING/CONFIRMED payment row
-- instead of creating a second one.
ALTER TABLE payment ADD COLUMN idempotency_key UUID;

CREATE UNIQUE INDEX uq_payment_idempotency
    ON payment (tenant_id, order_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
