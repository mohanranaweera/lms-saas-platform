-- finance-expense-management (Wave 7, PAR-23-01/05): creates
-- `expense_category` and `expense` - the first schema for this domain, which
-- is already a confirmed top-level domain in .claude/rules/architecture.md.
--
-- See docs/parity/waves/wave-07-plan.md §3. V1-V53 are NOT edited - this is a
-- new, additive CREATE TABLE/CREATE INDEX file only.
--
-- Income is deliberately NOT stored here: income reporting is derived
-- exclusively from `ledger_entry` (the authoritative payment record) through
-- ledger-settlement-management's api, so no second, manually-editable source
-- of payment truth exists (wave-07-plan.md §2).
--
-- Tenant isolation: both tables carry `tenant_id UUID NOT NULL`, tenant-leading
-- indexes, and composite `(tenant_id, ...)` FKs into every other tenant-owned
-- table they reference (.claude/rules/tenancy.md).
--
-- `expense` is append-only financial history (root CLAUDE.md "never delete
-- financial history"; wave-07-plan.md §10 judgment call 1): there is no
-- delete path and no in-place edit - the only mutation is the one-way VOID
-- transition, which fills `voided_at`/`voided_by`/`void_reason` exactly
-- once (all-or-none CHECK below). No ON DELETE CASCADE anywhere.

-- ---------------------------------------------------------------------------
-- expense_category (com.lms.financeexpensemanagement.domain.ExpenseCategory)
-- ---------------------------------------------------------------------------

CREATE TABLE expense_category (
    id           UUID PRIMARY KEY,
    tenant_id    UUID NOT NULL REFERENCES tenant (id),
    name         VARCHAR(100) NOT NULL,
    description  VARCHAR(500),
    archived     BOOLEAN NOT NULL DEFAULT false,
    created_at   TIMESTAMPTZ NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL,
    created_by   UUID,
    updated_by   UUID,

    -- Composite-FK target for expense.category_id (mirrors uq_course_tenant_id).
    CONSTRAINT uq_expense_category_tenant_id UNIQUE (tenant_id, id),

    CONSTRAINT ck_expense_category_name_not_blank CHECK (char_length(btrim(name)) > 0)
);

-- "Unique per tenant", case-insensitive - never a global unique name.
CREATE UNIQUE INDEX uq_expense_category_tenant_name
    ON expense_category (tenant_id, lower(name));

CREATE INDEX idx_expense_category_tenant_archived
    ON expense_category (tenant_id, archived);

-- ---------------------------------------------------------------------------
-- expense (com.lms.financeexpensemanagement.domain.Expense)
-- ---------------------------------------------------------------------------

CREATE TABLE expense (
    id                       UUID PRIMARY KEY,
    tenant_id                UUID NOT NULL REFERENCES tenant (id),
    category_id              UUID NOT NULL,
    expense_date             DATE NOT NULL,
    description              VARCHAR(500) NOT NULL,
    amount                   NUMERIC(12, 2) NOT NULL,
    currency                 VARCHAR(3) NOT NULL,
    method                   VARCHAR(20) NOT NULL,
    reference                VARCHAR(255),
    attachment_object_key    VARCHAR(1024),
    attachment_filename      VARCHAR(255),
    attachment_mime_type     VARCHAR(255),
    attachment_size_bytes    BIGINT,
    created_by               UUID NOT NULL,
    created_at               TIMESTAMPTZ NOT NULL,
    voided_at                TIMESTAMPTZ,
    voided_by                UUID,
    void_reason              VARCHAR(500),

    CONSTRAINT uq_expense_tenant_id UNIQUE (tenant_id, id),

    CONSTRAINT fk_expense_category
        FOREIGN KEY (tenant_id, category_id) REFERENCES expense_category (tenant_id, id),
    CONSTRAINT fk_expense_created_by
        FOREIGN KEY (tenant_id, created_by) REFERENCES tenant_user (tenant_id, id),
    CONSTRAINT fk_expense_voided_by
        FOREIGN KEY (tenant_id, voided_by) REFERENCES tenant_user (tenant_id, id),

    CONSTRAINT ck_expense_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_expense_currency CHECK (char_length(currency) = 3),
    CONSTRAINT ck_expense_description_not_blank CHECK (char_length(btrim(description)) > 0),
    CONSTRAINT ck_expense_method CHECK (method IN ('CASH', 'BANK_TRANSFER', 'CARD', 'CHEQUE', 'ONLINE', 'OTHER')),
    CONSTRAINT ck_expense_attachment_all_or_none CHECK (
        (attachment_object_key IS NULL AND attachment_filename IS NULL
            AND attachment_mime_type IS NULL AND attachment_size_bytes IS NULL)
        OR (attachment_object_key IS NOT NULL AND attachment_filename IS NOT NULL
            AND attachment_mime_type IS NOT NULL AND attachment_size_bytes > 0)
    ),
    CONSTRAINT ck_expense_void_all_or_none CHECK (
        (voided_at IS NULL AND voided_by IS NULL AND void_reason IS NULL)
        OR (voided_at IS NOT NULL AND voided_by IS NOT NULL AND char_length(btrim(void_reason)) > 0)
    )
);

-- Date-range expense lists/reports (the dominant read pattern).
CREATE INDEX idx_expense_tenant_date
    ON expense (tenant_id, expense_date DESC);

-- Category filter and the "is this category in use" check.
CREATE INDEX idx_expense_tenant_category
    ON expense (tenant_id, category_id);
