-- PAY-HUB-01 (docs/plans/PAY-HUB-01 Payments Hub (Klass parity).md §4):
-- staff review notes on a manual payment slip (Klass "Add Note"), plus the
-- index backing the Payments hub "All Orders" list.

-- ---------------------------------------------------------------------------
-- payment_slip_note (com.lms.paymentmanagement.slip.domain)
-- ---------------------------------------------------------------------------
-- Internal staff notes only - never exposed to students. Append-only, like
-- payment_slip_flag: a note is never edited or deleted, a correction is a new
-- note. A note never changes the slip's review state.

CREATE TABLE payment_slip_note (
    id              UUID PRIMARY KEY,
    tenant_id       UUID NOT NULL REFERENCES tenant (id),
    slip_id         UUID NOT NULL,
    author_user_id  UUID NOT NULL,
    body            VARCHAR(1000) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Composite FKs keep the slip and the author inside the note's own tenant.
    CONSTRAINT fk_payment_slip_note_slip FOREIGN KEY (tenant_id, slip_id)
        REFERENCES payment_slip (tenant_id, id),
    CONSTRAINT fk_payment_slip_note_author FOREIGN KEY (tenant_id, author_user_id)
        REFERENCES tenant_user (tenant_id, id),

    CONSTRAINT ck_payment_slip_note_body_not_blank CHECK (btrim(body) <> '')
);

-- Slip-detail / row-expander read pattern: every note of one slip, oldest first.
CREATE INDEX idx_payment_slip_note_tenant_slip_created_at
    ON payment_slip_note (tenant_id, slip_id, created_at);

CREATE FUNCTION forbid_payment_slip_note_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'payment_slip_note is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_payment_slip_note_append_only
    BEFORE UPDATE OR DELETE ON payment_slip_note
    FOR EACH ROW EXECUTE FUNCTION forbid_payment_slip_note_mutation();

-- ---------------------------------------------------------------------------
-- student_order: Payments hub "All Orders" list (GET /api/v1/ledger/orders)
-- ---------------------------------------------------------------------------
-- Newest-first, tenant-scoped listing and order-date range filtering. The
-- existing (tenant_id, status, created_at) index leads with status and does
-- not serve an unfiltered date-ordered read; order-number lookup is already
-- covered by idx_student_order_tenant_order_number (V100).
CREATE INDEX idx_student_order_tenant_created_at
    ON student_order (tenant_id, created_at DESC);
