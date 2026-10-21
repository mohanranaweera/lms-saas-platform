-- payment-management (Klass manual payment, KLASS manual payment & free
-- card): `manual_payment_receipt` - the staff-recorded receipt that sits
-- next to the one composer order + one CONFIRMED `payment`
-- (gateway_reference = 'MANUAL-' || payment id) + PAYMENT_CONFIRMED ledger
-- entry + entitlements written in the same transaction (M1).
--
-- Schema contract: docs/plans/klass-manual-payment-free-card.md §5 (V93),
-- decisions M1-M7 (§3).
--
-- Additive only. No existing order, payment, slip, refund or ledger row is
-- updated or deleted; no backfill. Ledger entry types (ADR-010) are
-- unchanged. The only change to an existing table is a new composite
-- UNIQUE key on `payment` (contains the PK, so every existing row already
-- satisfies it) used as the FK target that pins the receipt's payment to
-- the receipt's order.
--
-- Money (M3/M4): `expected_amount` is the server-recomputed quote (full
-- order snapshot); `staff_adjustment_amount` is the explicit audited staff
-- adjustment (0 <= adjustment < expected, reason required iff > 0);
-- `received_amount` = expected - adjustment is the cash recorded on the
-- payment and ledger. Net 0 is refused (received > 0).
--
-- `received_on` (M6) is the business date in the tenant timezone, separate
-- from the server instant `recorded_at`. "Not in the future" is an
-- application rule (needs the tenant timezone) and is not a CHECK.
--
-- Immutable: a receipt is financial history. Corrections are refunds (M8),
-- never an edit; the trigger below rejects UPDATE/DELETE (V65 precedent).
--
-- ids are application-generated UUIDs (no DB default).

-- ---------------------------------------------------------------------------
-- 1. payment: composite FK target (tenant_id, id, order_id) so a receipt's
--    payment must belong to the receipt's order.
-- ---------------------------------------------------------------------------

ALTER TABLE payment
    ADD CONSTRAINT uq_payment_tenant_id_order UNIQUE (tenant_id, id, order_id);

-- ---------------------------------------------------------------------------
-- 2. manual_payment_receipt: insert-only.
-- ---------------------------------------------------------------------------

CREATE TABLE manual_payment_receipt (
    id                       UUID PRIMARY KEY,
    tenant_id                UUID NOT NULL REFERENCES tenant (id),
    student_id               UUID NOT NULL,
    order_id                 UUID NOT NULL,
    payment_id               UUID NOT NULL,
    payment_method           VARCHAR(20) NOT NULL,
    received_on              DATE NOT NULL,
    reference_number         VARCHAR(100),
    notes                    VARCHAR(1000),
    currency                 VARCHAR(3) NOT NULL,
    expected_amount          NUMERIC(12,2) NOT NULL,
    staff_adjustment_amount  NUMERIC(12,2) NOT NULL DEFAULT 0,
    adjustment_reason        TEXT,
    received_amount          NUMERIC(12,2) NOT NULL,
    recorded_by              UUID NOT NULL,
    recorded_at              TIMESTAMPTZ NOT NULL,
    idempotency_key          UUID NOT NULL,

    CONSTRAINT uq_manual_payment_receipt_tenant_id UNIQUE (tenant_id, id),
    -- One receipt per payment.
    CONSTRAINT uq_manual_payment_receipt_tenant_payment UNIQUE (tenant_id, payment_id),
    -- Replay guard (M7).
    CONSTRAINT uq_manual_payment_receipt_tenant_idempotency_key
        UNIQUE (tenant_id, idempotency_key),

    -- The receipt's student must be the order's student (V87 key).
    CONSTRAINT fk_manual_payment_receipt_order FOREIGN KEY (tenant_id, order_id, student_id)
        REFERENCES student_order (tenant_id, id, student_id),
    -- The receipt's payment must belong to the receipt's order.
    CONSTRAINT fk_manual_payment_receipt_payment FOREIGN KEY (tenant_id, payment_id, order_id)
        REFERENCES payment (tenant_id, id, order_id),
    CONSTRAINT fk_manual_payment_receipt_student FOREIGN KEY (tenant_id, student_id)
        REFERENCES tenant_user (tenant_id, id),
    -- Recording staff member of the same tenant (M2: Tenant Admin / Finance Staff).
    CONSTRAINT fk_manual_payment_receipt_recorded_by FOREIGN KEY (tenant_id, recorded_by)
        REFERENCES tenant_user (tenant_id, id),

    CONSTRAINT ck_manual_payment_receipt_payment_method CHECK (
        payment_method IN ('CASH', 'BANK_TRANSFER', 'CARD')
    ),
    CONSTRAINT ck_manual_payment_receipt_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_manual_payment_receipt_expected_amount CHECK (expected_amount > 0),
    CONSTRAINT ck_manual_payment_receipt_staff_adjustment_amount CHECK (
        staff_adjustment_amount >= 0
    ),
    CONSTRAINT ck_manual_payment_receipt_received_amount CHECK (received_amount > 0),
    -- received = expected - adjustment (together with received > 0 this
    -- also enforces adjustment < expected).
    CONSTRAINT ck_manual_payment_receipt_received_formula CHECK (
        received_amount = expected_amount - staff_adjustment_amount
    ),
    -- Reason non-blank iff adjustment > 0.
    CONSTRAINT ck_manual_payment_receipt_adjustment_reason CHECK (
        (staff_adjustment_amount = 0 AND adjustment_reason IS NULL)
        OR (staff_adjustment_amount > 0 AND adjustment_reason IS NOT NULL
            AND btrim(adjustment_reason) <> '')
    )
);

-- Student payment history (newest first).
CREATE INDEX idx_manual_payment_receipt_tenant_student_recorded_at
    ON manual_payment_receipt (tenant_id, student_id, recorded_at);

-- Receipts of one order / FK support for the order key.
CREATE INDEX idx_manual_payment_receipt_tenant_order
    ON manual_payment_receipt (tenant_id, order_id);

-- FK support for the staff actor (tenant_user delete checks).
CREATE INDEX idx_manual_payment_receipt_tenant_recorded_by
    ON manual_payment_receipt (tenant_id, recorded_by);

-- ---------------------------------------------------------------------------
-- 3. Append-only enforcement (financial history, never rewritten).
-- ---------------------------------------------------------------------------

CREATE FUNCTION forbid_manual_payment_receipt_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'manual_payment_receipt is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_manual_payment_receipt_append_only
    BEFORE UPDATE OR DELETE ON manual_payment_receipt
    FOR EACH ROW EXECUTE FUNCTION forbid_manual_payment_receipt_mutation();
