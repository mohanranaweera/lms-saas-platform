-- tute-management (TUTE-01, docs/specs/tute-management-dispatch.md section 10): printed tutes
-- per course and one fulfillment row per student copy (assignment -> dispatch / hand-over).
--
-- Purely additive. No earlier migration is edited. Course, lesson, payment, order and user
-- tables are only REFERENCED (same-tenant composite FKs, provenance only); tutes never create
-- or change orders, payments, ledger rows, enrollments or paid entitlements.
--
-- Tenant-owned: both tables carry tenant_id NOT NULL FK tenant, tenant-leading indexes and
-- composite (tenant_id, id) FKs so a row can never reference another tenant's parent.

-- 1. tute: one printed handout of a course.
--    quantity     total copies the institute holds (lifetime stock); available =
--                 quantity - non-cancelled fulfillments (application-enforced under the row lock).
--    payment_type COVERED_BY_CLASS_FEES | PAID | FREE; price only (and required) when PAID.
--    paid_lesson_id optional PAID session of the same course the tute is sold through; cleared
--                 (not blocking) when that lesson is deleted.
--    status       ACTIVE | ARCHIVED (archive = delete with history; hidden by default).
--    version      optimistic lock for staff edits.
CREATE TABLE tute (
    id              UUID PRIMARY KEY,
    tenant_id       UUID NOT NULL REFERENCES tenant (id),
    course_id       UUID NOT NULL,
    name            VARCHAR(200) NOT NULL,
    description     VARCHAR(2000),
    quantity        INTEGER NOT NULL,
    payment_type    VARCHAR(30) NOT NULL,
    price           NUMERIC(12,2),
    currency        VARCHAR(3) NOT NULL,
    paid_lesson_id  UUID,
    status          VARCHAR(12) NOT NULL DEFAULT 'ACTIVE',
    archived_at     TIMESTAMPTZ,
    archived_by     UUID,
    version         BIGINT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL,
    created_by      UUID,
    updated_by      UUID,

    CONSTRAINT uq_tute_tenant_id UNIQUE (tenant_id, id),
    -- Target of fk_tute_fulfillment_tute: a fulfillment's course is always its tute's course.
    CONSTRAINT uq_tute_tenant_id_course UNIQUE (tenant_id, id, course_id),

    CONSTRAINT fk_tute_course FOREIGN KEY (tenant_id, course_id)
        REFERENCES course (tenant_id, id),
    CONSTRAINT fk_tute_paid_lesson FOREIGN KEY (tenant_id, paid_lesson_id)
        REFERENCES course_lesson (tenant_id, id) ON DELETE SET NULL (paid_lesson_id),

    CONSTRAINT ck_tute_name CHECK (btrim(name) <> ''),
    CONSTRAINT ck_tute_quantity CHECK (quantity BETWEEN 0 AND 1000000),
    CONSTRAINT ck_tute_payment_type CHECK (payment_type IN ('COVERED_BY_CLASS_FEES', 'PAID', 'FREE')),
    CONSTRAINT ck_tute_price CHECK (
        (payment_type = 'PAID' AND price IS NOT NULL AND price > 0 AND price <= 999999.99)
        OR (payment_type <> 'PAID' AND price IS NULL)
    ),
    CONSTRAINT ck_tute_paid_lesson CHECK (paid_lesson_id IS NULL OR payment_type = 'PAID'),
    CONSTRAINT ck_tute_status CHECK (
        (status = 'ACTIVE' AND archived_at IS NULL)
        OR (status = 'ARCHIVED' AND archived_at IS NOT NULL)
    )
);

-- Course "Manage Tutes" list (newest first) and the payment trigger's per-course lookup.
CREATE INDEX idx_tute_tenant_course ON tute (tenant_id, course_id, created_at);

-- Backs fk_tute_paid_lesson (lesson delete) and paid-session matching.
CREATE INDEX idx_tute_tenant_paid_lesson ON tute (tenant_id, paid_lesson_id)
    WHERE paid_lesson_id IS NOT NULL;

-- 2. tute_fulfillment: one student's copy of one tute.
--    status   PENDING -> DISPATCHED (terminal, never undone) | PENDING -> CANCELLED (terminal,
--             releases stock; reason required).
--    method   POSTAL (enters the To Send queue) | COLLECTION (handed over at class); changeable
--             only while PENDING.
--    source   MANUAL (staff) | PAYMENT (a confirmed payment matched; provenance ids required).
--    tute_name / payment_type / price / currency: snapshot of the tute at creation.
--    order_delivery_*: the order's delivery snapshot, copied only when the source order chose
--             postage for this course (address resolution step 1).
--    payment_reversed_at: hold flag set when the source payment is refunded / charged back
--             (PENDING: held out of the queue until released or cancelled; DISPATCHED: flag only).
--    dispatch_*: the resolved address snapshotted at dispatch (later profile edits never
--             rewrite it). Nothing here is ever deleted except together with an unused tute.
CREATE TABLE tute_fulfillment (
    id                           UUID PRIMARY KEY,
    tenant_id                    UUID NOT NULL REFERENCES tenant (id),
    tute_id                      UUID NOT NULL,
    course_id                    UUID NOT NULL,
    student_id                   UUID NOT NULL,
    status                       VARCHAR(12) NOT NULL,
    method                       VARCHAR(12) NOT NULL,
    source                       VARCHAR(10) NOT NULL,
    source_payment_id            UUID,
    source_order_id              UUID,
    tute_name                    VARCHAR(200) NOT NULL,
    payment_type                 VARCHAR(30) NOT NULL,
    price                        NUMERIC(12,2),
    currency                     VARCHAR(3) NOT NULL,
    order_delivery_name          VARCHAR(255),
    order_delivery_address_line  VARCHAR(255),
    order_delivery_city          VARCHAR(100),
    order_delivery_phone         VARCHAR(30),
    payment_reversed_at          TIMESTAMPTZ,
    hold_released_at             TIMESTAMPTZ,
    hold_released_by             UUID,
    dispatched_at                TIMESTAMPTZ,
    dispatched_by                UUID,
    dispatch_recipient_name      VARCHAR(255),
    dispatch_address_line        VARCHAR(255),
    dispatch_address_line_2      VARCHAR(255),
    dispatch_city                VARCHAR(100),
    dispatch_postal_code         VARCHAR(20),
    dispatch_phone               VARCHAR(50),
    dispatch_address_source      VARCHAR(10),
    cancelled_at                 TIMESTAMPTZ,
    cancelled_by                 UUID,
    cancel_reason                VARCHAR(500),
    created_at                   TIMESTAMPTZ NOT NULL,
    updated_at                   TIMESTAMPTZ NOT NULL,
    created_by                   UUID,
    updated_by                   UUID,

    CONSTRAINT uq_tute_fulfillment_tenant_id UNIQUE (tenant_id, id),

    CONSTRAINT fk_tute_fulfillment_tute FOREIGN KEY (tenant_id, tute_id, course_id)
        REFERENCES tute (tenant_id, id, course_id),
    CONSTRAINT fk_tute_fulfillment_course FOREIGN KEY (tenant_id, course_id)
        REFERENCES course (tenant_id, id),
    CONSTRAINT fk_tute_fulfillment_student FOREIGN KEY (tenant_id, student_id)
        REFERENCES tenant_user (tenant_id, id),
    -- Provenance only (read-only references; payments and orders are never deleted).
    CONSTRAINT fk_tute_fulfillment_payment FOREIGN KEY (tenant_id, source_payment_id)
        REFERENCES payment (tenant_id, id),
    CONSTRAINT fk_tute_fulfillment_order FOREIGN KEY (tenant_id, source_order_id)
        REFERENCES student_order (tenant_id, id),

    CONSTRAINT ck_tute_fulfillment_status CHECK (status IN ('PENDING', 'DISPATCHED', 'CANCELLED')),
    CONSTRAINT ck_tute_fulfillment_method CHECK (method IN ('POSTAL', 'COLLECTION')),
    CONSTRAINT ck_tute_fulfillment_source CHECK (
        (source = 'PAYMENT' AND source_payment_id IS NOT NULL AND source_order_id IS NOT NULL)
        OR (source = 'MANUAL' AND source_payment_id IS NULL AND source_order_id IS NULL)
    ),
    CONSTRAINT ck_tute_fulfillment_payment_type CHECK (
        payment_type IN ('COVERED_BY_CLASS_FEES', 'PAID', 'FREE')
    ),
    CONSTRAINT ck_tute_fulfillment_price CHECK (
        (payment_type = 'PAID' AND price IS NOT NULL AND price > 0)
        OR (payment_type <> 'PAID' AND price IS NULL)
    ),
    CONSTRAINT ck_tute_fulfillment_state_timestamps CHECK (
        (status = 'PENDING' AND dispatched_at IS NULL AND cancelled_at IS NULL)
        OR (status = 'DISPATCHED' AND dispatched_at IS NOT NULL AND cancelled_at IS NULL)
        OR (status = 'CANCELLED' AND cancelled_at IS NOT NULL AND dispatched_at IS NULL
            AND cancel_reason IS NOT NULL AND btrim(cancel_reason) <> '')
    ),
    CONSTRAINT ck_tute_fulfillment_hold CHECK (hold_released_at IS NULL OR payment_reversed_at IS NOT NULL),
    CONSTRAINT ck_tute_fulfillment_dispatch_source CHECK (
        dispatch_address_source IS NULL OR dispatch_address_source IN ('ORDER', 'PROFILE', 'NONE')
    )
);

-- At most one non-cancelled copy per (tenant, tute, student): the ON CONFLICT target of every
-- idempotent insert (manual assignment, payment trigger, sync) and the tute's student index.
CREATE UNIQUE INDEX uq_tute_fulfillment_active_student
    ON tute_fulfillment (tenant_id, tute_id, student_id)
    WHERE status <> 'CANCELLED';

-- Tute view (assigned students) and stock counts.
CREATE INDEX idx_tute_fulfillment_tenant_tute_status
    ON tute_fulfillment (tenant_id, tute_id, status);

-- Tute Dispatch queue: pending postal items of the tenant.
CREATE INDEX idx_tute_fulfillment_tenant_pending_postal
    ON tute_fulfillment (tenant_id, course_id)
    WHERE status = 'PENDING' AND method = 'POSTAL';

-- Refund / chargeback hold: fulfillments of one payment.
CREATE INDEX idx_tute_fulfillment_tenant_payment
    ON tute_fulfillment (tenant_id, source_payment_id)
    WHERE source_payment_id IS NOT NULL;

-- Backs fk_tute_fulfillment_order / fk_tute_fulfillment_student / fk_tute_fulfillment_course.
CREATE INDEX idx_tute_fulfillment_tenant_order
    ON tute_fulfillment (tenant_id, source_order_id)
    WHERE source_order_id IS NOT NULL;

CREATE INDEX idx_tute_fulfillment_tenant_student
    ON tute_fulfillment (tenant_id, student_id);

CREATE INDEX idx_tute_fulfillment_tenant_course
    ON tute_fulfillment (tenant_id, course_id);
