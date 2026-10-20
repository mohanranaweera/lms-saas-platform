-- payment-management + course-management (multi-course checkout, facility
-- fees and postage, ADR-028 follow-up): per-course facility-fee override and
-- postage fee on `course`; multi-course line orders, postage total and a
-- delivery-address snapshot on `student_order`; and the new immutable
-- `student_order_course_charge` table (one row per course per line order).
--
-- Schema contract: docs/plans/multi-course-checkout-fees-postage.md §3 (V90).
--
-- Additive only. No existing course, order, payment, slip, refund or ledger
-- row is updated or deleted; no backfill:
--   * course.facility_fee_override is nullable (NULL = inherit the tenant
--     default, 0 = explicitly no fee); course.postage_fee defaults to 0.
--   * student_order.course_id becomes nullable ONLY for payment-composer
--     line orders (subtotal_amount IS NOT NULL), which may span several
--     courses. Every existing row has course_id set, so the new check holds.
--     fk_student_order_course (V19) is a composite MATCH SIMPLE FK
--     (Postgres default): a row with course_id NULL is simply not checked,
--     a row with course_id set is checked exactly as before.
--   * student_order.postage_amount and the four delivery_* columns are
--     nullable and stay NULL for legacy orders and pre-V90 line orders.
--   * ck_student_order_fee_snapshot (V87) is dropped and re-added under the
--     same name, extended with postage. Existing rows have postage_amount
--     NULL, so the extended check reduces to the V87 check for them.
-- Ledger entry types (ADR-010) are unchanged - still one PAYMENT_CONFIRMED
-- per payment, whatever the number of lines/courses.
--
-- Order total invariant for line orders: amount = subtotal_amount +
-- facility_fee_amount + COALESCE(postage_amount, 0) - discount_amount.

-- ---------------------------------------------------------------------------
-- 1. course: facility-fee override and postage fee
-- ---------------------------------------------------------------------------

ALTER TABLE course
    ADD COLUMN facility_fee_override NUMERIC(12,2),
    ADD COLUMN postage_fee           NUMERIC(12,2) NOT NULL DEFAULT 0;

ALTER TABLE course
    ADD CONSTRAINT ck_course_facility_fee_override CHECK (
        facility_fee_override IS NULL OR facility_fee_override >= 0
    ),
    ADD CONSTRAINT ck_course_postage_fee CHECK (postage_fee >= 0);

-- ---------------------------------------------------------------------------
-- 2. student_order: multi-course line orders, postage, delivery snapshot
-- ---------------------------------------------------------------------------

ALTER TABLE student_order
    ALTER COLUMN course_id DROP NOT NULL;

ALTER TABLE student_order
    ADD COLUMN postage_amount           NUMERIC(12,2),
    ADD COLUMN delivery_recipient_name  VARCHAR(255),
    ADD COLUMN delivery_address_line    VARCHAR(255),
    ADD COLUMN delivery_city            VARCHAR(100),
    ADD COLUMN delivery_phone           VARCHAR(30);

ALTER TABLE student_order
    -- Only payment-composer line orders (always subtotal-bearing) may span
    -- multiple courses and so carry no single course_id.
    ADD CONSTRAINT ck_student_order_course_or_lines CHECK (
        course_id IS NOT NULL OR subtotal_amount IS NOT NULL
    ),
    -- An address snapshot, when present, must be usable for dispatch.
    -- Recipient name and phone are optional.
    ADD CONSTRAINT ck_student_order_delivery_address CHECK (
        delivery_address_line IS NULL
        OR (btrim(delivery_address_line) <> ''
            AND delivery_city IS NOT NULL AND btrim(delivery_city) <> '')
    );

ALTER TABLE student_order
    DROP CONSTRAINT ck_student_order_fee_snapshot;

ALTER TABLE student_order
    ADD CONSTRAINT ck_student_order_fee_snapshot CHECK (
        (subtotal_amount IS NULL AND facility_fee_amount IS NULL AND discount_amount IS NULL
            AND postage_amount IS NULL)
        OR (subtotal_amount IS NOT NULL AND facility_fee_amount IS NOT NULL AND discount_amount IS NOT NULL
            AND subtotal_amount >= 0 AND facility_fee_amount >= 0 AND discount_amount >= 0
            AND (postage_amount IS NULL OR postage_amount >= 0)
            AND amount = subtotal_amount + facility_fee_amount
                         + COALESCE(postage_amount, 0) - discount_amount)
    );

-- ---------------------------------------------------------------------------
-- 3. student_order_course_charge: immutable, insert-only (no updated_at).
--    One row per course per line order - the per-course facility-fee and
--    postage figures in effect when the order was composed.
-- ---------------------------------------------------------------------------

CREATE TABLE student_order_course_charge (
    id                      UUID PRIMARY KEY,
    tenant_id               UUID NOT NULL REFERENCES tenant (id),
    order_id                UUID NOT NULL,
    course_id               UUID NOT NULL,
    currency                VARCHAR(3) NOT NULL,
    fee_basis               VARCHAR(20) NOT NULL,
    facility_fee_source     VARCHAR(20) NOT NULL,
    -- Per-course configured facility fee in effect at order time.
    effective_facility_fee  NUMERIC(12,2) NOT NULL,
    -- Facility fee actually charged to this course in this order.
    facility_fee_amount     NUMERIC(12,2) NOT NULL,
    postage_selected        BOOLEAN NOT NULL,
    -- Configured course postage at order time.
    postage_fee             NUMERIC(12,2) NOT NULL,
    -- Postage actually charged for this course in this order.
    postage_amount          NUMERIC(12,2) NOT NULL,
    created_at              TIMESTAMPTZ NOT NULL,

    CONSTRAINT uq_student_order_course_charge_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_student_order_course_charge_order_course UNIQUE (tenant_id, order_id, course_id),

    CONSTRAINT fk_student_order_course_charge_order FOREIGN KEY (tenant_id, order_id)
        REFERENCES student_order (tenant_id, id),
    -- No cascade: financial history is never removed.
    CONSTRAINT fk_student_order_course_charge_course FOREIGN KEY (tenant_id, course_id)
        REFERENCES course (tenant_id, id),

    CONSTRAINT ck_student_order_course_charge_currency CHECK (char_length(currency) = 3),
    CONSTRAINT ck_student_order_course_charge_fee_basis CHECK (
        fee_basis IN ('NONE', 'PER_COURSE', 'PER_LINE', 'PER_ORDER')
    ),
    CONSTRAINT ck_student_order_course_charge_fee_source CHECK (
        facility_fee_source IN ('TENANT_DEFAULT', 'COURSE_OVERRIDE', 'NOT_APPLICABLE')
    ),
    CONSTRAINT ck_student_order_course_charge_amounts CHECK (
        effective_facility_fee >= 0 AND facility_fee_amount >= 0
        AND postage_fee >= 0 AND postage_amount >= 0
    ),
    CONSTRAINT ck_student_order_course_charge_postage CHECK (
        (postage_selected AND postage_amount = postage_fee)
        OR (NOT postage_selected AND postage_amount = 0)
    )
);

-- Charges of one order: served by uq_student_order_course_charge_order_course
-- (leads with tenant_id, order_id); no separate index (V13 precedent).

-- Per-course fulfillment / report lookups and FK checks on course delete.
CREATE INDEX idx_student_order_course_charge_tenant_course
    ON student_order_course_charge (tenant_id, course_id);
