-- FIN-01 (docs/specs/finance-module-klass-parity.md): Klass-style Finance account book.
--
-- Purely additive. ledger_entry (student money), expense (append-only expenses) and
-- teacher_settlement (wallet) stay the sources of truth; the tables below only POST those facts
-- to tenant finance accounts and hold manual income / expense / transfer / adjustment postings.
-- Account balances are never stored: balance = SUM(credits) - SUM(debits) of the account's
-- POSTED/REVERSED finance_transaction rows (reversals are new rows, originals are never changed).
--
-- Every table is tenant-owned: tenant_id NOT NULL FK tenant, UNIQUE (tenant_id, id) so children
-- can reference a parent only inside the same tenant, tenant-leading indexes.

-- ---------------------------------------------------------------------------
-- 1. finance_account: Cash / Bank / Card ... accounts. Archived, never deleted.
-- ---------------------------------------------------------------------------
CREATE TABLE finance_account (
    id            UUID PRIMARY KEY,
    tenant_id     UUID NOT NULL REFERENCES tenant (id),
    name          VARCHAR(100) NOT NULL,
    description   VARCHAR(500),
    currency      VARCHAR(3) NOT NULL,
    archived      BOOLEAN NOT NULL DEFAULT false,
    created_by    UUID NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL,
    updated_by    UUID,
    updated_at    TIMESTAMPTZ NOT NULL,
    archived_at   TIMESTAMPTZ,
    archived_by   UUID,

    CONSTRAINT uq_finance_account_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_finance_account_created_by
        FOREIGN KEY (tenant_id, created_by) REFERENCES tenant_user (tenant_id, id),
    CONSTRAINT fk_finance_account_updated_by
        FOREIGN KEY (tenant_id, updated_by) REFERENCES tenant_user (tenant_id, id),
    CONSTRAINT fk_finance_account_archived_by
        FOREIGN KEY (tenant_id, archived_by) REFERENCES tenant_user (tenant_id, id),
    CONSTRAINT ck_finance_account_name_not_blank CHECK (char_length(btrim(name)) > 0),
    CONSTRAINT ck_finance_account_currency CHECK (char_length(currency) = 3),
    CONSTRAINT ck_finance_account_archive_shape CHECK (
        (archived = false AND archived_at IS NULL AND archived_by IS NULL)
        OR (archived = true AND archived_at IS NOT NULL AND archived_by IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uq_finance_account_tenant_name ON finance_account (tenant_id, lower(name));
CREATE INDEX idx_finance_account_tenant_archived ON finance_account (tenant_id, archived);

-- ---------------------------------------------------------------------------
-- 2. finance_payment_routing: which account a confirmed student payment of a given
--    method is posted to. Real method codes; the UI groups them into the four Klass slots
--    (Cash = CASH, Card = CARD, Bank/Online = BANK_TRANSFER + GATEWAY + MANUAL_SLIP, Other = OTHER).
--    The whole set is replaced atomically; a payment with no matching code and no OTHER row
--    stays UNPOSTED (never an arbitrary account).
-- ---------------------------------------------------------------------------
CREATE TABLE finance_payment_routing (
    id            UUID PRIMARY KEY,
    tenant_id     UUID NOT NULL REFERENCES tenant (id),
    method_code   VARCHAR(20) NOT NULL,
    account_id    UUID NOT NULL,
    updated_by    UUID NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL,

    CONSTRAINT uq_finance_payment_routing_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_finance_payment_routing_method UNIQUE (tenant_id, method_code),
    CONSTRAINT fk_finance_payment_routing_account
        FOREIGN KEY (tenant_id, account_id) REFERENCES finance_account (tenant_id, id),
    CONSTRAINT fk_finance_payment_routing_updated_by
        FOREIGN KEY (tenant_id, updated_by) REFERENCES tenant_user (tenant_id, id),
    CONSTRAINT ck_finance_payment_routing_method
        CHECK (method_code IN ('CASH', 'CARD', 'BANK_TRANSFER', 'GATEWAY', 'MANUAL_SLIP', 'OTHER'))
);

CREATE INDEX idx_finance_payment_routing_tenant_account ON finance_payment_routing (tenant_id, account_id);

-- ---------------------------------------------------------------------------
-- 3. finance_transaction: the account book. One row per posting; a TRANSFER holds both legs
--    (from + to) so the two legs can never be half-posted. Monetary columns are immutable;
--    the only mutable state is UNPOSTED -> POSTED (account assigned) and POSTED -> REVERSED
--    (a REVERSAL row was appended).
--
--    type            direction
--    OPENING         credit to_account (amount >= 0, once per account)
--    INCOME          credit to_account
--    EXPENSE         debit  from_account
--    TRANSFER        debit  from_account, credit to_account
--    ADJUSTMENT      credit to_account OR debit from_account (balance pencil, reason required)
--    REFUND          debit  from_account (= the original INCOME posting's account)
--    REVERSAL        mirror of the row it reverses (reason required)
--
--    source_type / source_id link a posting to the fact it posts:
--    LEDGER_ENTRY (ledger_entry.id), EXPENSE (expense.id), WALLET_ENTRY (teacher_settlement.id),
--    SCHEDULE (finance_scheduled_occurrence.id), MANUAL (no source_id).
-- ---------------------------------------------------------------------------
CREATE TABLE finance_transaction (
    id                        UUID PRIMARY KEY,
    tenant_id                 UUID NOT NULL REFERENCES tenant (id),
    type                      VARCHAR(12) NOT NULL,
    status                    VARCHAR(10) NOT NULL,
    amount                    NUMERIC(14, 2) NOT NULL,
    currency                  VARCHAR(3) NOT NULL,
    from_account_id           UUID,
    to_account_id             UUID,
    category_id               UUID,
    transaction_date          DATE NOT NULL,
    posted_at                 TIMESTAMPTZ,
    note                      VARCHAR(500),
    source_type               VARCHAR(20) NOT NULL,
    source_id                 UUID,
    reverses_transaction_id   UUID,
    idempotency_key           VARCHAR(100),
    balance_before            NUMERIC(14, 2),
    balance_after             NUMERIC(14, 2),
    reason                    VARCHAR(500),
    created_by                UUID,
    created_at                TIMESTAMPTZ NOT NULL,
    updated_at                TIMESTAMPTZ NOT NULL,
    reversed_at               TIMESTAMPTZ,
    reversed_by               UUID,

    CONSTRAINT uq_finance_transaction_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_finance_transaction_from_account
        FOREIGN KEY (tenant_id, from_account_id) REFERENCES finance_account (tenant_id, id),
    CONSTRAINT fk_finance_transaction_to_account
        FOREIGN KEY (tenant_id, to_account_id) REFERENCES finance_account (tenant_id, id),
    CONSTRAINT fk_finance_transaction_category
        FOREIGN KEY (tenant_id, category_id) REFERENCES expense_category (tenant_id, id),
    CONSTRAINT fk_finance_transaction_reverses
        FOREIGN KEY (tenant_id, reverses_transaction_id) REFERENCES finance_transaction (tenant_id, id),
    CONSTRAINT fk_finance_transaction_created_by
        FOREIGN KEY (tenant_id, created_by) REFERENCES tenant_user (tenant_id, id),
    CONSTRAINT fk_finance_transaction_reversed_by
        FOREIGN KEY (tenant_id, reversed_by) REFERENCES tenant_user (tenant_id, id),

    CONSTRAINT ck_finance_transaction_type CHECK (type IN
        ('OPENING', 'INCOME', 'EXPENSE', 'TRANSFER', 'ADJUSTMENT', 'REFUND', 'REVERSAL')),
    CONSTRAINT ck_finance_transaction_status CHECK (status IN ('POSTED', 'REVERSED', 'UNPOSTED')),
    CONSTRAINT ck_finance_transaction_source_type CHECK (source_type IN
        ('MANUAL', 'LEDGER_ENTRY', 'EXPENSE', 'WALLET_ENTRY', 'SCHEDULE')),
    CONSTRAINT ck_finance_transaction_amount CHECK (amount >= 0 AND (type = 'OPENING' OR amount > 0)),
    CONSTRAINT ck_finance_transaction_currency CHECK (char_length(currency) = 3),
    CONSTRAINT ck_finance_transaction_source_id CHECK ((source_type = 'MANUAL') = (source_id IS NULL)),
    -- Only a student-payment posting (or its refund) can wait for routing.
    CONSTRAINT ck_finance_transaction_unposted CHECK (
        (status <> 'UNPOSTED' AND posted_at IS NOT NULL)
        OR (status = 'UNPOSTED' AND posted_at IS NULL AND source_type = 'LEDGER_ENTRY'
            AND from_account_id IS NULL AND to_account_id IS NULL)
    ),
    CONSTRAINT ck_finance_transaction_reversed CHECK (
        (status = 'REVERSED') = (reversed_at IS NOT NULL) AND (reversed_at IS NULL) = (reversed_by IS NULL)
    ),
    CONSTRAINT ck_finance_transaction_shape CHECK (
        status = 'UNPOSTED'
        OR (type IN ('OPENING', 'INCOME') AND to_account_id IS NOT NULL AND from_account_id IS NULL)
        OR (type IN ('EXPENSE', 'REFUND') AND from_account_id IS NOT NULL AND to_account_id IS NULL)
        OR (type = 'TRANSFER' AND from_account_id IS NOT NULL AND to_account_id IS NOT NULL
            AND from_account_id <> to_account_id)
        OR (type = 'ADJUSTMENT' AND ((from_account_id IS NULL) <> (to_account_id IS NULL))
            AND balance_before IS NOT NULL AND balance_after IS NOT NULL
            AND reason IS NOT NULL AND char_length(btrim(reason)) > 0)
        OR (type = 'REVERSAL' AND reverses_transaction_id IS NOT NULL
            AND (from_account_id IS NOT NULL OR to_account_id IS NOT NULL)
            AND reason IS NOT NULL AND char_length(btrim(reason)) > 0)
    ),
    CONSTRAINT ck_finance_transaction_refund_link CHECK (type <> 'REFUND' OR reverses_transaction_id IS NOT NULL)
);

-- A fact is posted at most once (payment, refund entry, expense, wallet entry, occurrence).
CREATE UNIQUE INDEX uq_finance_transaction_source
    ON finance_transaction (tenant_id, source_type, source_id) WHERE source_id IS NOT NULL;
-- Idempotent manual postings.
CREATE UNIQUE INDEX uq_finance_transaction_idempotency_key
    ON finance_transaction (tenant_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
-- A row is reversed at most once (partial REFUND rows may share an original).
CREATE UNIQUE INDEX uq_finance_transaction_reversal
    ON finance_transaction (tenant_id, reverses_transaction_id) WHERE type = 'REVERSAL';
-- One opening balance per account.
CREATE UNIQUE INDEX uq_finance_transaction_opening
    ON finance_transaction (tenant_id, to_account_id) WHERE type = 'OPENING';

CREATE INDEX idx_finance_transaction_tenant_date
    ON finance_transaction (tenant_id, transaction_date DESC, created_at DESC);
CREATE INDEX idx_finance_transaction_tenant_from_account ON finance_transaction (tenant_id, from_account_id);
CREATE INDEX idx_finance_transaction_tenant_to_account ON finance_transaction (tenant_id, to_account_id);
CREATE INDEX idx_finance_transaction_tenant_category ON finance_transaction (tenant_id, category_id);
CREATE INDEX idx_finance_transaction_tenant_unposted
    ON finance_transaction (tenant_id, created_at DESC) WHERE status = 'UNPOSTED';

-- ---------------------------------------------------------------------------
-- 4. finance_scheduled_transaction + finance_scheduled_occurrence.
--    A schedule describes a posting; occurrences are its due dates. Occurrences are
--    materialised by a job (or on list refresh) and POSTED by an authorised user - the
--    scheduler never moves money by itself (no existing policy authorises that).
--    Editing or cancelling a schedule never touches POSTED occurrences.
-- ---------------------------------------------------------------------------
CREATE TABLE finance_scheduled_transaction (
    id                UUID PRIMARY KEY,
    tenant_id         UUID NOT NULL REFERENCES tenant (id),
    name              VARCHAR(100) NOT NULL,
    type              VARCHAR(10) NOT NULL,
    amount            NUMERIC(14, 2) NOT NULL,
    currency          VARCHAR(3) NOT NULL,
    from_account_id   UUID,
    to_account_id     UUID,
    category_id       UUID,
    note              VARCHAR(500),
    schedule_type     VARCHAR(10) NOT NULL,
    scheduled_date    DATE,
    day_of_month      SMALLINT,
    starts_on         DATE NOT NULL,
    status            VARCHAR(10) NOT NULL,
    created_by        UUID NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL,
    updated_by        UUID,
    updated_at        TIMESTAMPTZ NOT NULL,
    cancelled_at      TIMESTAMPTZ,
    cancelled_by      UUID,

    CONSTRAINT uq_finance_scheduled_transaction_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_finance_scheduled_transaction_from_account
        FOREIGN KEY (tenant_id, from_account_id) REFERENCES finance_account (tenant_id, id),
    CONSTRAINT fk_finance_scheduled_transaction_to_account
        FOREIGN KEY (tenant_id, to_account_id) REFERENCES finance_account (tenant_id, id),
    CONSTRAINT fk_finance_scheduled_transaction_category
        FOREIGN KEY (tenant_id, category_id) REFERENCES expense_category (tenant_id, id),
    CONSTRAINT fk_finance_scheduled_transaction_created_by
        FOREIGN KEY (tenant_id, created_by) REFERENCES tenant_user (tenant_id, id),
    CONSTRAINT fk_finance_scheduled_transaction_updated_by
        FOREIGN KEY (tenant_id, updated_by) REFERENCES tenant_user (tenant_id, id),
    CONSTRAINT fk_finance_scheduled_transaction_cancelled_by
        FOREIGN KEY (tenant_id, cancelled_by) REFERENCES tenant_user (tenant_id, id),

    CONSTRAINT ck_finance_scheduled_transaction_name CHECK (char_length(btrim(name)) > 0),
    CONSTRAINT ck_finance_scheduled_transaction_type CHECK (type IN ('INCOME', 'EXPENSE', 'TRANSFER')),
    CONSTRAINT ck_finance_scheduled_transaction_amount CHECK (amount > 0),
    CONSTRAINT ck_finance_scheduled_transaction_currency CHECK (char_length(currency) = 3),
    CONSTRAINT ck_finance_scheduled_transaction_status CHECK (status IN ('ACTIVE', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT ck_finance_scheduled_transaction_accounts CHECK (
        (type = 'INCOME' AND to_account_id IS NOT NULL AND from_account_id IS NULL)
        OR (type = 'EXPENSE' AND from_account_id IS NOT NULL AND to_account_id IS NULL)
        OR (type = 'TRANSFER' AND from_account_id IS NOT NULL AND to_account_id IS NOT NULL
            AND from_account_id <> to_account_id)
    ),
    CONSTRAINT ck_finance_scheduled_transaction_schedule CHECK (
        (schedule_type = 'ONE_TIME' AND scheduled_date IS NOT NULL AND day_of_month IS NULL)
        OR (schedule_type = 'MONTHLY' AND scheduled_date IS NULL AND day_of_month BETWEEN 1 AND 31)
    ),
    CONSTRAINT ck_finance_scheduled_transaction_cancelled CHECK (
        (status = 'CANCELLED') = (cancelled_at IS NOT NULL) AND (cancelled_at IS NULL) = (cancelled_by IS NULL)
    )
);

CREATE INDEX idx_finance_scheduled_transaction_tenant_status
    ON finance_scheduled_transaction (tenant_id, status, created_at DESC);

CREATE TABLE finance_scheduled_occurrence (
    id               UUID PRIMARY KEY,
    tenant_id        UUID NOT NULL REFERENCES tenant (id),
    schedule_id      UUID NOT NULL,
    due_date         DATE NOT NULL,
    status           VARCHAR(10) NOT NULL,
    transaction_id   UUID,
    attempts         INTEGER NOT NULL DEFAULT 0,
    last_error       VARCHAR(500),
    executed_at      TIMESTAMPTZ,
    executed_by      UUID,
    created_at       TIMESTAMPTZ NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL,

    CONSTRAINT uq_finance_scheduled_occurrence_tenant_id UNIQUE (tenant_id, id),
    -- (tenant, schedule, occurrence) is posted at most once, whatever the job does.
    CONSTRAINT uq_finance_scheduled_occurrence_due UNIQUE (tenant_id, schedule_id, due_date),
    CONSTRAINT fk_finance_scheduled_occurrence_schedule
        FOREIGN KEY (tenant_id, schedule_id) REFERENCES finance_scheduled_transaction (tenant_id, id),
    CONSTRAINT fk_finance_scheduled_occurrence_transaction
        FOREIGN KEY (tenant_id, transaction_id) REFERENCES finance_transaction (tenant_id, id),
    CONSTRAINT fk_finance_scheduled_occurrence_executed_by
        FOREIGN KEY (tenant_id, executed_by) REFERENCES tenant_user (tenant_id, id),
    CONSTRAINT ck_finance_scheduled_occurrence_status CHECK (status IN ('DUE', 'POSTED', 'FAILED', 'SKIPPED')),
    CONSTRAINT ck_finance_scheduled_occurrence_posted CHECK ((status = 'POSTED') = (transaction_id IS NOT NULL))
);

CREATE INDEX idx_finance_scheduled_occurrence_tenant_status
    ON finance_scheduled_occurrence (tenant_id, status, due_date);
CREATE INDEX idx_finance_scheduled_occurrence_tenant_schedule
    ON finance_scheduled_occurrence (tenant_id, schedule_id, due_date DESC);
