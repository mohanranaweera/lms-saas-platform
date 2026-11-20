-- payment-management (Klass discount module, DISC-01): tenant-managed
-- discount rules (Advance Payment, Bundle Amount Off, Bundle Free Course,
-- Discount Code), their immutable versions and course lists, the immutable
-- per-order applied-discount snapshot, the per-line discount link on
-- `student_order_line`, and per-order redemption (usage) tracking.
--
-- Schema contract: docs/plans/klass-discount-module.md §4 (V92), decisions
-- D2, D11, D12, D13 (§3).
--
-- Additive only. No existing order, line, payment, slip, refund or ledger
-- row is updated or deleted; no backfill. Ledger entry types (ADR-010) are
-- unchanged. Discounts only populate `student_order_line.discount_amount`
-- (present since V87). Every existing line was written with
-- discount_amount = 0 (CheckoutComposerService always passes ZERO), and the
-- two new columns default to order_discount_id NULL / reward false, so both
-- new `student_order_line` CHECKs hold for every existing row.
--
-- Rule edits never mutate pricing terms: they append a new
-- `discount_rule_version` row (D13) and move `discount_rule.current_version_id`.
-- Orders reference the exact version they used. "Delete" is archive
-- (`archived_at`, D11); versions, snapshots and redemptions are retained.
--
-- `created_by` columns are actor ids with no FK - the Auditable convention of
-- `course.created_by` (V11) / V85 / V39, since the acting principal may be a
-- tenant user or a platform admin (V32). They are NOT NULL here because every
-- rule/version is created by an authenticated admin action.
--
-- Circular reference: `discount_rule.current_version_id` -> version and
-- version -> rule. Both FKs are DEFERRABLE INITIALLY DEFERRED so a header and
-- its first version can be inserted in one transaction in either order.

-- ---------------------------------------------------------------------------
-- 1. discount_rule: header (mutable state only - active / archive / pointer)
-- ---------------------------------------------------------------------------

CREATE TABLE discount_rule (
    id                  UUID PRIMARY KEY,
    tenant_id           UUID NOT NULL REFERENCES tenant (id),
    discount_type       VARCHAR(30) NOT NULL,
    -- strip() + upper-case of the code (D7); DISCOUNT_CODE rules only.
    code_normalized     VARCHAR(40),
    current_version_id  UUID,
    active              BOOLEAN NOT NULL DEFAULT true,
    archived_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL,
    created_by          UUID NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL,

    CONSTRAINT uq_discount_rule_tenant_id UNIQUE (tenant_id, id),
    -- Composite-FK target: a version's discount_type must equal its rule's.
    CONSTRAINT uq_discount_rule_tenant_id_type UNIQUE (tenant_id, id, discount_type),

    CONSTRAINT ck_discount_rule_type CHECK (
        discount_type IN ('ADVANCE_PAYMENT', 'BUNDLE_AMOUNT_OFF', 'BUNDLE_FREE_COURSE', 'DISCOUNT_CODE')
    ),
    -- D7 (hardened 2026-10-21): 6-40 characters, so short codes cannot be
    -- enumerated by guessing.
    CONSTRAINT ck_discount_rule_code_shape CHECK (
        (discount_type = 'DISCOUNT_CODE'
            AND code_normalized IS NOT NULL
            AND code_normalized ~ '^[A-Z0-9_-]{6,40}$')
        OR (discount_type <> 'DISCOUNT_CODE' AND code_normalized IS NULL)
    )
);

-- D7: a code is unique per tenant among non-archived rules.
CREATE UNIQUE INDEX uq_discount_rule_tenant_code_live
    ON discount_rule (tenant_id, code_normalized)
    WHERE code_normalized IS NOT NULL AND archived_at IS NULL;

-- Admin list (All / Active / Inactive tabs, archived hidden) and checkout
-- candidate lookup (non-archived, active).
CREATE INDEX idx_discount_rule_tenant_archived_active
    ON discount_rule (tenant_id, archived_at, active);

-- ---------------------------------------------------------------------------
-- 2. discount_rule_version: immutable, append-only (no updated_at)
-- ---------------------------------------------------------------------------

CREATE TABLE discount_rule_version (
    id                   UUID PRIMARY KEY,
    tenant_id            UUID NOT NULL REFERENCES tenant (id),
    rule_id              UUID NOT NULL,
    version_no           INTEGER NOT NULL,
    name                 VARCHAR(120) NOT NULL,
    description          VARCHAR(1000),
    discount_type        VARCHAR(30) NOT NULL,
    selection_mode       VARCHAR(20) NOT NULL,
    min_count            INTEGER,
    payment_type         VARCHAR(20),
    value_type           VARCHAR(20),
    value                NUMERIC(12,2),
    free_months          INTEGER,
    code                 VARCHAR(40),
    valid_from           DATE NOT NULL,
    valid_to             DATE NOT NULL,
    max_uses             INTEGER,
    min_purchase_amount  NUMERIC(12,2),
    currency             VARCHAR(3) NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL,
    created_by           UUID NOT NULL,

    CONSTRAINT uq_discount_rule_version_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_discount_rule_version_rule_version_no UNIQUE (tenant_id, rule_id, version_no),
    -- Composite-FK target: discount_rule.current_version_id must be a
    -- version of that same rule.
    CONSTRAINT uq_discount_rule_version_rule_id UNIQUE (tenant_id, rule_id, id),
    -- Composite-FK target: an applied-discount snapshot's rule, version and
    -- type must all agree.
    CONSTRAINT uq_discount_rule_version_rule_id_type UNIQUE (tenant_id, rule_id, id, discount_type),

    -- Same tenant, same rule, same discount_type as the header.
    CONSTRAINT fk_discount_rule_version_rule FOREIGN KEY (tenant_id, rule_id, discount_type)
        REFERENCES discount_rule (tenant_id, id, discount_type)
        DEFERRABLE INITIALLY DEFERRED,

    CONSTRAINT ck_discount_rule_version_version_no CHECK (version_no >= 1),
    CONSTRAINT ck_discount_rule_version_name CHECK (
        btrim(name) <> '' AND char_length(name) <= 120
    ),
    CONSTRAINT ck_discount_rule_version_description CHECK (
        description IS NULL OR char_length(description) <= 1000
    ),
    CONSTRAINT ck_discount_rule_version_type CHECK (
        discount_type IN ('ADVANCE_PAYMENT', 'BUNDLE_AMOUNT_OFF', 'BUNDLE_FREE_COURSE', 'DISCOUNT_CODE')
    ),
    CONSTRAINT ck_discount_rule_version_selection CHECK (
        (selection_mode = 'MIN_COUNT' AND min_count IS NOT NULL AND min_count >= 1)
        OR (selection_mode IN ('ALL', 'ANY') AND min_count IS NULL)
    ),
    -- Advance Payment: Payment Type is required and only INSTALLMENTS (D1).
    CONSTRAINT ck_discount_rule_version_payment_type CHECK (
        (discount_type = 'ADVANCE_PAYMENT' AND payment_type = 'INSTALLMENTS')
        OR (discount_type <> 'ADVANCE_PAYMENT' AND payment_type IS NULL)
    ),
    -- Free course carries months, never a value; the others carry a value,
    -- never months (D2/D4).
    CONSTRAINT ck_discount_rule_version_value_shape CHECK (
        (discount_type = 'BUNDLE_FREE_COURSE'
            AND value_type IS NULL AND value IS NULL
            AND free_months IS NOT NULL AND free_months BETWEEN 1 AND 24)
        OR (discount_type <> 'BUNDLE_FREE_COURSE'
            AND value_type IN ('FIXED', 'PERCENTAGE')
            AND value IS NOT NULL AND value > 0
            AND (value_type <> 'PERCENTAGE' OR value <= 100)
            AND free_months IS NULL)
    ),
    -- The normalized code (same shape as discount_rule.code_normalized).
    CONSTRAINT ck_discount_rule_version_code CHECK (
        (discount_type = 'DISCOUNT_CODE' AND code IS NOT NULL AND code ~ '^[A-Z0-9_-]{6,40}$')
        OR (discount_type <> 'DISCOUNT_CODE' AND code IS NULL)
    ),
    CONSTRAINT ck_discount_rule_version_dates CHECK (valid_to >= valid_from),
    CONSTRAINT ck_discount_rule_version_max_uses CHECK (max_uses IS NULL OR max_uses >= 1),
    CONSTRAINT ck_discount_rule_version_min_purchase CHECK (
        min_purchase_amount IS NULL OR min_purchase_amount >= 0
    ),
    CONSTRAINT ck_discount_rule_version_currency CHECK (char_length(currency) = 3)
);

-- Versions of one rule: served by uq_discount_rule_version_rule_version_no
-- (leads with tenant_id, rule_id); no separate index (V13 precedent).

-- Header -> current version, which must belong to this rule. MATCH SIMPLE:
-- a NULL current_version_id is not checked.
ALTER TABLE discount_rule
    ADD CONSTRAINT fk_discount_rule_current_version
        FOREIGN KEY (tenant_id, id, current_version_id)
        REFERENCES discount_rule_version (tenant_id, rule_id, id)
        DEFERRABLE INITIALLY DEFERRED;

-- ---------------------------------------------------------------------------
-- 3. discount_rule_version_course: required / reward course list per
--    version (immutable with its version). "All" stores the explicit list (D9).
-- ---------------------------------------------------------------------------

CREATE TABLE discount_rule_version_course (
    tenant_id   UUID NOT NULL REFERENCES tenant (id),
    version_id  UUID NOT NULL,
    course_id   UUID NOT NULL,
    role        VARCHAR(20) NOT NULL,

    CONSTRAINT pk_discount_rule_version_course PRIMARY KEY (tenant_id, version_id, course_id, role),
    -- D2: reward courses are disjoint from required courses - a course
    -- appears at most once per version, whatever its role.
    CONSTRAINT uq_discount_rule_version_course_course UNIQUE (tenant_id, version_id, course_id),

    CONSTRAINT fk_discount_rule_version_course_version FOREIGN KEY (tenant_id, version_id)
        REFERENCES discount_rule_version (tenant_id, id),
    -- No cascade: a course referenced by a discount version is history.
    CONSTRAINT fk_discount_rule_version_course_course FOREIGN KEY (tenant_id, course_id)
        REFERENCES course (tenant_id, id),

    CONSTRAINT ck_discount_rule_version_course_role CHECK (role IN ('REQUIRED', 'REWARD'))
);

-- Courses of one version: served by the PK (leads with tenant_id, version_id).
-- "Which rules mention this course" lookups and FK checks on course delete.
CREATE INDEX idx_discount_rule_version_course_tenant_course
    ON discount_rule_version_course (tenant_id, course_id);

-- ---------------------------------------------------------------------------
-- 4. student_order_discount: immutable, insert-only snapshot of one applied
--    discount on one order (no updated_at).
-- ---------------------------------------------------------------------------

CREATE TABLE student_order_discount (
    id               UUID PRIMARY KEY,
    tenant_id        UUID NOT NULL REFERENCES tenant (id),
    order_id         UUID NOT NULL,
    rule_id          UUID NOT NULL,
    rule_version_id  UUID NOT NULL,
    discount_type    VARCHAR(30) NOT NULL,
    name             VARCHAR(120) NOT NULL,
    code             VARCHAR(40),
    value_type       VARCHAR(20),
    value            NUMERIC(12,2),
    amount           NUMERIC(12,2) NOT NULL,
    -- JSON: qualifying course ids, advance periods, reward months.
    qualification    TEXT NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL,

    CONSTRAINT uq_student_order_discount_tenant_id UNIQUE (tenant_id, id),
    -- One application of a rule per order.
    CONSTRAINT uq_student_order_discount_order_rule UNIQUE (tenant_id, order_id, rule_id),
    -- Composite-FK target: a line's discount must belong to the line's order.
    CONSTRAINT uq_student_order_discount_order_id UNIQUE (tenant_id, order_id, id),

    CONSTRAINT fk_student_order_discount_order FOREIGN KEY (tenant_id, order_id)
        REFERENCES student_order (tenant_id, id),
    CONSTRAINT fk_student_order_discount_rule FOREIGN KEY (tenant_id, rule_id)
        REFERENCES discount_rule (tenant_id, id),
    -- The version used belongs to rule_id and has the snapshot's type.
    CONSTRAINT fk_student_order_discount_rule_version
        FOREIGN KEY (tenant_id, rule_id, rule_version_id, discount_type)
        REFERENCES discount_rule_version (tenant_id, rule_id, id, discount_type),

    CONSTRAINT ck_student_order_discount_type CHECK (
        discount_type IN ('ADVANCE_PAYMENT', 'BUNDLE_AMOUNT_OFF', 'BUNDLE_FREE_COURSE', 'DISCOUNT_CODE')
    ),
    CONSTRAINT ck_student_order_discount_name CHECK (btrim(name) <> ''),
    CONSTRAINT ck_student_order_discount_amount CHECK (amount > 0),
    CONSTRAINT ck_student_order_discount_value_shape CHECK (
        (discount_type = 'BUNDLE_FREE_COURSE' AND value_type IS NULL AND value IS NULL)
        OR (discount_type <> 'BUNDLE_FREE_COURSE'
            AND value_type IN ('FIXED', 'PERCENTAGE')
            AND value IS NOT NULL AND value > 0
            AND (value_type <> 'PERCENTAGE' OR value <= 100))
    ),
    CONSTRAINT ck_student_order_discount_code CHECK (
        (discount_type = 'DISCOUNT_CODE' AND code IS NOT NULL AND btrim(code) <> '')
        OR (discount_type <> 'DISCOUNT_CODE' AND code IS NULL)
    ),
    CONSTRAINT ck_student_order_discount_qualification CHECK (btrim(qualification) <> '')
);

-- Discounts of one order: served by uq_student_order_discount_order_rule
-- (leads with tenant_id, order_id); no separate index (V13 precedent).

-- Per-rule usage reporting and FK checks.
CREATE INDEX idx_student_order_discount_tenant_rule
    ON student_order_discount (tenant_id, rule_id);

CREATE INDEX idx_student_order_discount_tenant_rule_version
    ON student_order_discount (tenant_id, rule_version_id);

-- ---------------------------------------------------------------------------
-- 5. student_order_line: link to the applied discount + reward marker
-- ---------------------------------------------------------------------------

ALTER TABLE student_order_line
    ADD COLUMN order_discount_id UUID,
    ADD COLUMN reward            BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE student_order_line
    -- The discount belongs to this line's own order (implies the plain
    -- (tenant_id, order_discount_id) reference). MATCH SIMPLE: NULL is not
    -- checked. Deferred so lines and their discount snapshot can be flushed
    -- in either order within the placement transaction.
    ADD CONSTRAINT fk_student_order_line_order_discount
        FOREIGN KEY (tenant_id, order_id, order_discount_id)
        REFERENCES student_order_discount (tenant_id, order_id, id)
        DEFERRABLE INITIALLY DEFERRED,
    -- D2: a reward line is fully discounted (net course value 0) by a
    -- recorded discount.
    ADD CONSTRAINT ck_student_order_line_reward CHECK (
        NOT reward OR (order_discount_id IS NOT NULL AND discount_amount = unit_amount)
    ),
    -- Any non-zero line discount must be traceable to an applied discount.
    ADD CONSTRAINT ck_student_order_line_discount_source CHECK (
        discount_amount = 0 OR order_discount_id IS NOT NULL
    ),
    -- A line is never discounted below its course value (facility fees are
    -- never discounted).
    ADD CONSTRAINT ck_student_order_line_discount_le_unit CHECK (
        discount_amount <= unit_amount
    );

-- D3: a discounted order never has a zero total (no zero-amount payment, so
-- no new activation path). Existing rows have discount_amount NULL / 0.
ALTER TABLE student_order
    ADD CONSTRAINT ck_student_order_discount_positive_total CHECK (
        discount_amount IS NULL OR discount_amount = 0 OR amount > 0
    );

CREATE INDEX idx_student_order_line_tenant_order_discount
    ON student_order_line (tenant_id, order_discount_id)
    WHERE order_discount_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 6. discount_redemption: one usage hold per (order, rule) (D9, D12).
--    Capacity = CONSUMED + RESERVED with expires_at NULL or in the future.
-- ---------------------------------------------------------------------------

CREATE TABLE discount_redemption (
    id           UUID PRIMARY KEY,
    tenant_id    UUID NOT NULL REFERENCES tenant (id),
    rule_id      UUID NOT NULL,
    order_id     UUID NOT NULL,
    student_id   UUID NOT NULL,
    status       VARCHAR(20) NOT NULL,
    -- RESERVED: when the hold lapses - placement / rejection + 48h, gateway
    -- payment start + the gateway TTL (default 48h); NULL only while a bank
    -- slip is open (staff resolve it). CONSUMED: always NULL (D12).
    expires_at   TIMESTAMPTZ,
    reserved_at  TIMESTAMPTZ NOT NULL,
    consumed_at  TIMESTAMPTZ,
    updated_at   TIMESTAMPTZ NOT NULL,

    CONSTRAINT uq_discount_redemption_tenant_id UNIQUE (tenant_id, id),
    -- Idempotent: one row per (order, rule).
    CONSTRAINT uq_discount_redemption_order_rule UNIQUE (tenant_id, order_id, rule_id),

    CONSTRAINT fk_discount_redemption_rule FOREIGN KEY (tenant_id, rule_id)
        REFERENCES discount_rule (tenant_id, id),
    -- The redeeming student is the order's student, same tenant.
    CONSTRAINT fk_discount_redemption_order FOREIGN KEY (tenant_id, order_id, student_id)
        REFERENCES student_order (tenant_id, id, student_id),
    CONSTRAINT fk_discount_redemption_student FOREIGN KEY (tenant_id, student_id)
        REFERENCES tenant_user (tenant_id, id),
    -- A redemption exists only for a discount actually applied to the order.
    -- Deferred so the snapshot and the hold can be flushed in either order.
    CONSTRAINT fk_discount_redemption_order_discount FOREIGN KEY (tenant_id, order_id, rule_id)
        REFERENCES student_order_discount (tenant_id, order_id, rule_id)
        DEFERRABLE INITIALLY DEFERRED,

    CONSTRAINT ck_discount_redemption_status CHECK (status IN ('RESERVED', 'CONSUMED')),
    CONSTRAINT ck_discount_redemption_consumed CHECK (
        (status = 'CONSUMED' AND consumed_at IS NOT NULL)
        OR (status = 'RESERVED' AND consumed_at IS NULL)
    ),
    -- A consumed use never lapses.
    CONSTRAINT ck_discount_redemption_consumed_no_expiry CHECK (
        status <> 'CONSUMED' OR expires_at IS NULL
    )
);

-- Capacity count under the rule row lock (D12).
CREATE INDEX idx_discount_redemption_tenant_rule_status
    ON discount_redemption (tenant_id, rule_id, status);

-- Per-student lookups, the one-use-per-student check (tenant, student, rule)
-- under the rule row lock, and FK checks.
CREATE INDEX idx_discount_redemption_tenant_student
    ON discount_redemption (tenant_id, student_id, rule_id);
