-- TEACHER-WALLET-01 / ADR-036: Teachers -> Show Wallet, layered over teacher settlements.
--
-- The wallet balance is the amount payable to the teacher:
--   SUM(share_amount) of teacher_settlement rows in status CALCULATED (unpaid
--   settlements and their adjustments) or POSTED (wallet entries below).
-- Wallet entries are new append-only teacher_settlement kinds, not a parallel
-- ledger. They never write a ledger_entry and never move money.
--
-- teacher_wallet is a one-per-(tenant, teacher) header: it records the wallet's
-- creation and is the row every balance-changing write locks (SELECT ... FOR
-- UPDATE), so payouts, adjustments, mark-paid and calculation serialize per teacher.
-- Additive only: no existing row is changed, no opening balance is inferred.

CREATE TABLE teacher_wallet (
    id          UUID PRIMARY KEY,
    tenant_id   UUID NOT NULL REFERENCES tenant (id),
    teacher_id  UUID NOT NULL,
    currency    VARCHAR(3) NOT NULL,
    created_by  UUID NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL,

    CONSTRAINT uq_teacher_wallet_tenant_id UNIQUE (tenant_id, id),
    -- One wallet per tenant and teacher, even on retries or concurrent creation.
    CONSTRAINT uq_teacher_wallet_teacher UNIQUE (tenant_id, teacher_id),
    CONSTRAINT fk_teacher_wallet_teacher
        FOREIGN KEY (tenant_id, teacher_id) REFERENCES tenant_user (tenant_id, id),
    CONSTRAINT fk_teacher_wallet_created_by
        FOREIGN KEY (tenant_id, created_by) REFERENCES tenant_user (tenant_id, id),
    CONSTRAINT ck_teacher_wallet_currency CHECK (char_length(currency) = 3)
);

ALTER TABLE teacher_settlement
    ADD COLUMN wallet_id        UUID,
    ADD COLUMN idempotency_key  VARCHAR(100),
    ADD COLUMN request_hash     VARCHAR(64),
    ADD COLUMN balance_before   NUMERIC(14, 2),
    ADD COLUMN balance_after    NUMERIC(14, 2);

ALTER TABLE teacher_settlement
    ADD CONSTRAINT fk_teacher_settlement_wallet
        FOREIGN KEY (tenant_id, wallet_id) REFERENCES teacher_wallet (tenant_id, id);

ALTER TABLE teacher_settlement DROP CONSTRAINT ck_teacher_settlement_kind;
ALTER TABLE teacher_settlement ADD CONSTRAINT ck_teacher_settlement_kind
    CHECK (kind IN ('REGULAR', 'ADJUSTMENT', 'OPENING', 'PAY_IN', 'PAY_OUT', 'BALANCE_ADJ'));

ALTER TABLE teacher_settlement DROP CONSTRAINT ck_teacher_settlement_status;
ALTER TABLE teacher_settlement ADD CONSTRAINT ck_teacher_settlement_status
    CHECK (status IN ('CALCULATED', 'PAID', 'POSTED'));

ALTER TABLE teacher_settlement DROP CONSTRAINT ck_teacher_settlement_paid_consistency;
ALTER TABLE teacher_settlement ADD CONSTRAINT ck_teacher_settlement_paid_consistency CHECK (
    (status IN ('CALCULATED', 'POSTED') AND paid_by IS NULL AND paid_at IS NULL AND payout_reference IS NULL)
    OR (status = 'PAID' AND paid_by IS NOT NULL AND paid_at IS NOT NULL)
);

-- Settlement kinds keep CALCULATED -> PAID; wallet entries are POSTED once and
-- can never be marked paid.
ALTER TABLE teacher_settlement ADD CONSTRAINT ck_teacher_settlement_status_by_kind CHECK (
    (kind IN ('REGULAR', 'ADJUSTMENT') AND status IN ('CALCULATED', 'PAID'))
    OR (kind IN ('OPENING', 'PAY_IN', 'PAY_OUT', 'BALANCE_ADJ') AND status = 'POSTED')
);

ALTER TABLE teacher_settlement ADD CONSTRAINT ck_teacher_settlement_wallet_entry_shape CHECK (
    (kind IN ('REGULAR', 'ADJUSTMENT')
        AND wallet_id IS NULL AND idempotency_key IS NULL AND request_hash IS NULL
        AND balance_before IS NULL AND balance_after IS NULL)
    OR (kind IN ('OPENING', 'PAY_IN', 'PAY_OUT', 'BALANCE_ADJ')
        AND wallet_id IS NOT NULL
        AND balance_before IS NOT NULL AND balance_after IS NOT NULL
        AND balance_after = balance_before + share_amount
        AND period_start IS NULL AND period_end IS NULL
        AND gross_amount IS NULL AND refund_amount IS NULL AND net_amount IS NULL
        AND share_percent IS NULL AND rate_id IS NULL AND adjusts_settlement_id IS NULL
        AND (idempotency_key IS NULL) = (request_hash IS NULL)
        AND (
            (kind = 'OPENING' AND share_amount >= 0)
            OR (kind = 'PAY_IN' AND share_amount > 0)
            OR (kind = 'PAY_OUT' AND share_amount < 0 AND balance_after >= 0)
            OR (kind = 'BALANCE_ADJ' AND share_amount <> 0
                AND reason IS NOT NULL AND char_length(btrim(reason)) > 0)
        ))
);

-- The opening balance is recorded exactly once per teacher.
CREATE UNIQUE INDEX uq_teacher_settlement_wallet_opening
    ON teacher_settlement (tenant_id, teacher_id)
    WHERE kind = 'OPENING';

-- Idempotent posting: a key is used at most once per tenant.
CREATE UNIQUE INDEX uq_teacher_settlement_idempotency_key
    ON teacher_settlement (tenant_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

-- Per-tab recent activity (Pay Out / Pay In) for one teacher.
CREATE INDEX idx_teacher_settlement_tenant_teacher_kind
    ON teacher_settlement (tenant_id, teacher_id, kind, calculated_at DESC);
