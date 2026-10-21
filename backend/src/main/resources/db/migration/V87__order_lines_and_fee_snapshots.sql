-- payment-management (Klass course/enrollment parity, KLASS-T2, ADR-028):
-- order fee snapshot columns on `student_order`, the new immutable
-- `student_order_line` table (D06/D07), and the deferred FKs of
-- `content_entitlement` (V86) to `student_order`, `student_order_line` and
-- `payment_refund`.
--
-- Schema contract: docs/plans/klass-enrollment-payment-parity.md §3 (V87).
--
-- Additive only. No existing order, payment, slip, refund or ledger row is
-- updated or deleted: the four new `student_order` columns are nullable and
-- stay NULL for every legacy (single-amount, line-less) order; no backfill.
-- Ledger entry types (ADR-010) are unchanged - one PAYMENT_CONFIRMED per
-- payment, whatever the number of lines. Two UNIQUE constraints are added
-- to existing financial tables purely as composite-FK targets; both are
-- trivially satisfied by existing rows because they contain the table's
-- primary key:
--   * uq_student_order_tenant_id_student  (tenant_id, id, student_id)
--   * uq_payment_refund_tenant_id         (tenant_id, id)
--
-- Order total invariant for line orders: amount = subtotal_amount +
-- facility_fee_amount - discount_amount (all three set together or none).
-- Per-line: line_total = unit_amount + facility_fee_amount - discount_amount.
-- For facility_fee_mode PER_ORDER (D07) the order-level fee is not spread
-- over lines, so sum(line_total) may be lower than amount by that fee.

-- ---------------------------------------------------------------------------
-- 1. student_order: fee snapshot columns (nullable = legacy order)
-- ---------------------------------------------------------------------------

ALTER TABLE student_order
    ADD COLUMN subtotal_amount      NUMERIC(12,2),
    ADD COLUMN facility_fee_amount  NUMERIC(12,2),
    ADD COLUMN discount_amount      NUMERIC(12,2),
    -- e.g. 'PER_LINE:100.00' / 'PER_ORDER:200.00' / 'NONE' - the tenant
    -- facility-fee config in effect when the order was composed.
    ADD COLUMN fee_policy_snapshot  VARCHAR(100);

ALTER TABLE student_order
    ADD CONSTRAINT ck_student_order_fee_snapshot CHECK (
        (subtotal_amount IS NULL AND facility_fee_amount IS NULL AND discount_amount IS NULL)
        OR (subtotal_amount IS NOT NULL AND facility_fee_amount IS NOT NULL AND discount_amount IS NOT NULL
            AND subtotal_amount >= 0 AND facility_fee_amount >= 0 AND discount_amount >= 0
            AND amount = subtotal_amount + facility_fee_amount - discount_amount)
    ),
    ADD CONSTRAINT ck_student_order_fee_policy_snapshot_not_blank CHECK (
        fee_policy_snapshot IS NULL OR btrim(fee_policy_snapshot) <> ''
    );

-- Composite-FK target so an entitlement's student must be its order's
-- student (contains the PK, so every existing row already satisfies it).
ALTER TABLE student_order
    ADD CONSTRAINT uq_student_order_tenant_id_student UNIQUE (tenant_id, id, student_id);

-- ---------------------------------------------------------------------------
-- 2. student_order_line: immutable, insert-only (no updated_at). One line =
--    one coverage item; same scope shape / coverage_key format as
--    content_entitlement (V86).
-- ---------------------------------------------------------------------------

CREATE TABLE student_order_line (
    id                   UUID PRIMARY KEY,
    tenant_id            UUID NOT NULL REFERENCES tenant (id),
    order_id             UUID NOT NULL,
    line_no              INTEGER NOT NULL,
    scope                VARCHAR(20) NOT NULL,
    course_id            UUID NOT NULL,
    module_id            UUID,
    lesson_id            UUID,
    period_year          SMALLINT,
    period_month         SMALLINT,
    -- MONTHLY price source (the open course_billing_period at quote time).
    billing_period_id    UUID,
    label                VARCHAR(255) NOT NULL,
    unit_amount          NUMERIC(12,2) NOT NULL,
    facility_fee_amount  NUMERIC(12,2) NOT NULL DEFAULT 0,
    discount_amount      NUMERIC(12,2) NOT NULL DEFAULT 0,
    line_total           NUMERIC(12,2) NOT NULL,
    currency             VARCHAR(3) NOT NULL,
    coverage_key         VARCHAR(120) NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL,

    CONSTRAINT uq_student_order_line_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_student_order_line_order_line_no UNIQUE (tenant_id, order_id, line_no),
    CONSTRAINT uq_student_order_line_order_coverage UNIQUE (tenant_id, order_id, coverage_key),
    -- Composite-FK target for content_entitlement (order + line + coverage).
    CONSTRAINT uq_student_order_line_order_id_coverage UNIQUE (tenant_id, order_id, id, coverage_key),

    CONSTRAINT fk_student_order_line_order FOREIGN KEY (tenant_id, order_id)
        REFERENCES student_order (tenant_id, id),
    CONSTRAINT fk_student_order_line_course FOREIGN KEY (tenant_id, course_id)
        REFERENCES course (tenant_id, id),
    -- No cascade: a module/lesson referenced by an order line cannot be
    -- hard-deleted (API maps to 409); financial history is never removed.
    CONSTRAINT fk_student_order_line_module FOREIGN KEY (tenant_id, module_id)
        REFERENCES course_module (tenant_id, id),
    CONSTRAINT fk_student_order_line_lesson FOREIGN KEY (tenant_id, lesson_id)
        REFERENCES course_lesson (tenant_id, id),
    CONSTRAINT fk_student_order_line_billing_period FOREIGN KEY (tenant_id, billing_period_id)
        REFERENCES course_billing_period (tenant_id, id),

    CONSTRAINT ck_student_order_line_line_no CHECK (line_no > 0),
    CONSTRAINT ck_student_order_line_scope CHECK (scope IN ('MONTHLY', 'MODULE', 'LESSON')),
    CONSTRAINT ck_student_order_line_label_not_blank CHECK (btrim(label) <> ''),
    CONSTRAINT ck_student_order_line_currency CHECK (char_length(currency) = 3),
    CONSTRAINT ck_student_order_line_amounts CHECK (
        unit_amount >= 0 AND facility_fee_amount >= 0 AND discount_amount >= 0
        AND line_total >= 0
        AND line_total = unit_amount + facility_fee_amount - discount_amount
    ),
    CONSTRAINT ck_student_order_line_scope_shape CHECK (
        (scope = 'MONTHLY'
            AND period_year IS NOT NULL AND period_month IS NOT NULL
            AND period_month BETWEEN 1 AND 12 AND period_year BETWEEN 2000 AND 2100
            AND module_id IS NULL AND lesson_id IS NULL
            AND coverage_key = 'MONTHLY:' || course_id::text || ':'
                || lpad(period_year::text, 4, '0') || '-' || lpad(period_month::text, 2, '0'))
        OR (scope = 'MODULE'
            AND module_id IS NOT NULL AND lesson_id IS NULL
            AND period_year IS NULL AND period_month IS NULL
            AND billing_period_id IS NULL
            AND coverage_key = 'MODULE:' || module_id::text)
        OR (scope = 'LESSON'
            AND lesson_id IS NOT NULL
            AND period_year IS NULL AND period_month IS NULL
            AND billing_period_id IS NULL
            AND coverage_key = 'LESSON:' || lesson_id::text)
    )
);

-- Lines of one order: served by uq_student_order_line_order_line_no
-- (leads with tenant_id, order_id); a separate (tenant_id, order_id) index
-- would be redundant (V13 precedent).

-- "Is this coverage already pending/paid in another order" lookups.
CREATE INDEX idx_student_order_line_tenant_coverage
    ON student_order_line (tenant_id, coverage_key);

-- Module/lesson delete guards (409) and FK checks on parent delete.
CREATE INDEX idx_student_order_line_tenant_module
    ON student_order_line (tenant_id, module_id)
    WHERE module_id IS NOT NULL;

CREATE INDEX idx_student_order_line_tenant_lesson
    ON student_order_line (tenant_id, lesson_id)
    WHERE lesson_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 3. payment_refund: composite-FK target for content_entitlement revocation
-- ---------------------------------------------------------------------------

ALTER TABLE payment_refund
    ADD CONSTRAINT uq_payment_refund_tenant_id UNIQUE (tenant_id, id);

-- ---------------------------------------------------------------------------
-- 4. content_entitlement (V86): deferred composite FKs
-- ---------------------------------------------------------------------------

ALTER TABLE content_entitlement
    -- The entitled student is the order's student, same tenant.
    ADD CONSTRAINT fk_content_entitlement_order FOREIGN KEY (tenant_id, order_id, student_id)
        REFERENCES student_order (tenant_id, id, student_id),
    -- The line belongs to that order and covers exactly the same item.
    ADD CONSTRAINT fk_content_entitlement_order_line
        FOREIGN KEY (tenant_id, order_id, order_line_id, coverage_key)
        REFERENCES student_order_line (tenant_id, order_id, id, coverage_key),
    ADD CONSTRAINT fk_content_entitlement_revoked_by_refund
        FOREIGN KEY (tenant_id, revoked_by_refund_id)
        REFERENCES payment_refund (tenant_id, id);
