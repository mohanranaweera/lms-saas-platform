-- ledger-settlement-management (Wave 7, PAR-24-02/03/04, PAR-23-03): Teacher
-- settlement FOUNDATION - `teacher_revenue_share_rate`,
-- `teacher_settlement`, `teacher_settlement_item`.
--
-- See docs/parity/waves/wave-07-plan.md §3/§10. V1-V54 are NOT edited.
--
-- Scope boundary (change-controlled "payment ledger rules", root CLAUDE.md):
--   * NO new ledger_entry type and NO ledger_entry write. A settlement is a
--     calculated statement that READS confirmed/refund ledger entries; it
--     never produces, mutates, or deletes one. Marking a settlement PAID is a
--     record-keeping transition only (the platform moves no money).
--   * NO platform -> tenant commission/gateway-fee settlement and NO split
--     payments (open decisions; deferred).
--
-- .claude/rules/payments.md §5 mechanics, schema-enforced:
--   * Idempotency: partial unique index on (tenant_id, teacher_id,
--     period_start, period_end) WHERE kind = 'REGULAR' - the "run marker" -
--     so re-running the same period can never create a second statement.
--   * Double-pay guard across DIFFERENT/overlapping periods: every source
--     ledger entry is recorded in `teacher_settlement_item`, unique on
--     (tenant_id, ledger_entry_id) - one ledger entry can be settled at most
--     once, ever.
--   * Stored-at-run-time rate: `share_percent` + `rate_id` are snapshotted
--     onto the settlement row; figures are never recomputed from live config.
--   * Corrections: a new ADJUSTMENT row referencing the original via
--     `adjusts_settlement_id`; the original row's figures are never updated
--     (every figure column is `updatable = false` at the entity layer, and no
--     repository exposes delete).
--
-- Tenant isolation: tenant_id NOT NULL everywhere, tenant-leading indexes,
-- composite (tenant_id, ...) FKs into tenant_user/course/ledger_entry and
-- between these tables (.claude/rules/tenancy.md). No ON DELETE CASCADE.

-- ---------------------------------------------------------------------------
-- teacher_revenue_share_rate (append-only, effective-dated)
-- ---------------------------------------------------------------------------

CREATE TABLE teacher_revenue_share_rate (
    id              UUID PRIMARY KEY,
    tenant_id       UUID NOT NULL REFERENCES tenant (id),
    teacher_id      UUID NOT NULL,
    share_percent   NUMERIC(5, 2) NOT NULL,
    effective_from  DATE NOT NULL,
    created_by      UUID NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,

    CONSTRAINT uq_teacher_revenue_share_rate_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_teacher_revenue_share_rate_effective UNIQUE (tenant_id, teacher_id, effective_from),

    CONSTRAINT fk_teacher_revenue_share_rate_teacher
        FOREIGN KEY (tenant_id, teacher_id) REFERENCES tenant_user (tenant_id, id),
    CONSTRAINT fk_teacher_revenue_share_rate_created_by
        FOREIGN KEY (tenant_id, created_by) REFERENCES tenant_user (tenant_id, id),

    CONSTRAINT ck_teacher_revenue_share_rate_percent CHECK (share_percent >= 0 AND share_percent <= 100)
);

-- ---------------------------------------------------------------------------
-- teacher_settlement
-- ---------------------------------------------------------------------------

CREATE TABLE teacher_settlement (
    id                      UUID PRIMARY KEY,
    tenant_id               UUID NOT NULL REFERENCES tenant (id),
    teacher_id              UUID NOT NULL,
    kind                    VARCHAR(12) NOT NULL,
    period_start            DATE,
    period_end              DATE,
    gross_amount            NUMERIC(12, 2),
    refund_amount           NUMERIC(12, 2),
    net_amount              NUMERIC(12, 2),
    share_percent           NUMERIC(5, 2),
    rate_id                 UUID,
    share_amount            NUMERIC(12, 2) NOT NULL,
    currency                VARCHAR(3) NOT NULL,
    adjusts_settlement_id   UUID,
    reason                  VARCHAR(500),
    status                  VARCHAR(12) NOT NULL,
    calculated_by           UUID NOT NULL,
    calculated_at           TIMESTAMPTZ NOT NULL,
    paid_by                 UUID,
    paid_at                 TIMESTAMPTZ,
    payout_reference        VARCHAR(255),

    CONSTRAINT uq_teacher_settlement_tenant_id UNIQUE (tenant_id, id),

    CONSTRAINT fk_teacher_settlement_teacher
        FOREIGN KEY (tenant_id, teacher_id) REFERENCES tenant_user (tenant_id, id),
    CONSTRAINT fk_teacher_settlement_calculated_by
        FOREIGN KEY (tenant_id, calculated_by) REFERENCES tenant_user (tenant_id, id),
    CONSTRAINT fk_teacher_settlement_paid_by
        FOREIGN KEY (tenant_id, paid_by) REFERENCES tenant_user (tenant_id, id),
    CONSTRAINT fk_teacher_settlement_rate
        FOREIGN KEY (tenant_id, rate_id) REFERENCES teacher_revenue_share_rate (tenant_id, id),
    CONSTRAINT fk_teacher_settlement_adjusts
        FOREIGN KEY (tenant_id, adjusts_settlement_id) REFERENCES teacher_settlement (tenant_id, id),

    CONSTRAINT ck_teacher_settlement_kind CHECK (kind IN ('REGULAR', 'ADJUSTMENT')),
    CONSTRAINT ck_teacher_settlement_status CHECK (status IN ('CALCULATED', 'PAID')),
    CONSTRAINT ck_teacher_settlement_currency CHECK (char_length(currency) = 3),
    CONSTRAINT ck_teacher_settlement_regular_shape CHECK (
        kind <> 'REGULAR' OR (
            period_start IS NOT NULL AND period_end IS NOT NULL AND period_start <= period_end
            AND gross_amount IS NOT NULL AND gross_amount >= 0
            AND refund_amount IS NOT NULL AND refund_amount >= 0
            AND net_amount IS NOT NULL AND net_amount = gross_amount - refund_amount
            AND share_percent IS NOT NULL AND share_percent >= 0 AND share_percent <= 100
            AND rate_id IS NOT NULL
            AND adjusts_settlement_id IS NULL AND reason IS NULL
        )
    ),
    CONSTRAINT ck_teacher_settlement_adjustment_shape CHECK (
        kind <> 'ADJUSTMENT' OR (
            adjusts_settlement_id IS NOT NULL AND reason IS NOT NULL AND char_length(btrim(reason)) > 0
            AND share_amount <> 0
            AND period_start IS NULL AND period_end IS NULL
            AND gross_amount IS NULL AND refund_amount IS NULL AND net_amount IS NULL
            AND share_percent IS NULL AND rate_id IS NULL
        )
    ),
    CONSTRAINT ck_teacher_settlement_paid_consistency CHECK (
        (status = 'CALCULATED' AND paid_by IS NULL AND paid_at IS NULL AND payout_reference IS NULL)
        OR (status = 'PAID' AND paid_by IS NOT NULL AND paid_at IS NOT NULL)
    )
);

-- payments.md §5 run marker: one REGULAR statement per (teacher, period).
CREATE UNIQUE INDEX uq_teacher_settlement_regular_period
    ON teacher_settlement (tenant_id, teacher_id, period_start, period_end)
    WHERE kind = 'REGULAR';

-- Per-teacher payout history / status lists.
CREATE INDEX idx_teacher_settlement_tenant_teacher
    ON teacher_settlement (tenant_id, teacher_id, calculated_at DESC);

CREATE INDEX idx_teacher_settlement_tenant_status
    ON teacher_settlement (tenant_id, status);

CREATE INDEX idx_teacher_settlement_tenant_adjusts
    ON teacher_settlement (tenant_id, adjusts_settlement_id)
    WHERE adjusts_settlement_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- teacher_settlement_item (source ledger entries of a REGULAR settlement)
-- ---------------------------------------------------------------------------

CREATE TABLE teacher_settlement_item (
    id               UUID PRIMARY KEY,
    tenant_id        UUID NOT NULL REFERENCES tenant (id),
    settlement_id    UUID NOT NULL,
    ledger_entry_id  UUID NOT NULL,
    course_id        UUID NOT NULL,
    amount           NUMERIC(12, 2) NOT NULL,

    CONSTRAINT fk_teacher_settlement_item_settlement
        FOREIGN KEY (tenant_id, settlement_id) REFERENCES teacher_settlement (tenant_id, id),
    CONSTRAINT fk_teacher_settlement_item_ledger_entry
        FOREIGN KEY (tenant_id, ledger_entry_id) REFERENCES ledger_entry (tenant_id, id),
    CONSTRAINT fk_teacher_settlement_item_course
        FOREIGN KEY (tenant_id, course_id) REFERENCES course (tenant_id, id),

    -- A ledger entry is settled at most once, across every period/run.
    CONSTRAINT uq_teacher_settlement_item_ledger_entry UNIQUE (tenant_id, ledger_entry_id),

    CONSTRAINT ck_teacher_settlement_item_amount CHECK (amount <> 0)
);

CREATE INDEX idx_teacher_settlement_item_tenant_settlement
    ON teacher_settlement_item (tenant_id, settlement_id);
