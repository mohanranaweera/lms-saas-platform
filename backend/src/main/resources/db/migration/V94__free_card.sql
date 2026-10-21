-- enrollment-management (Klass free card, KLASS manual payment & free
-- card): `free_card` - a staff-issued, time-boxed FULL fee waiver for one
-- student on one MONTHLY-priced course (F1-F8).
--
-- Schema contract: docs/plans/klass-manual-payment-free-card.md §5 (V94),
-- decisions F1-F8 (§3).
--
-- Additive only; no existing table is altered and no row is written (no
-- backfill). Not financial (F7): a free card never creates an order,
-- payment, ledger, settlement or postage row. `nominal_monthly_amount`
-- (course monthly price snapshot) and `nominal_value` (monthly x months)
-- are informational only, never revenue.
--
-- Validity (F3) is always bounded: `valid_from` / `valid_to` are tenant-
-- local dates; `effective_from` = start of valid_from and `effective_until`
-- = start of the day after valid_to, both computed in the tenant timezone
-- snapshotted in `timezone`. "valid_to >= today", "span <= 24 months" and
-- "no overlapping non-revoked card for the same student + course" (F5) are
-- application rules (need the tenant clock / an advisory lock).
--
-- `card_type`: FULL only (F2). HALF is reserved and deliberately NOT
-- allowed by the CHECK; enabling it needs a new migration + decision.
--
-- Lifecycle (F8): columns are immutable after insert except the revoke
-- columns (revoked_at, revoked_by, revoked_reason), set together exactly
-- once. Never deleted. The trigger below enforces both.
--
-- ids are application-generated UUIDs (no DB default).

CREATE TABLE free_card (
    id                      UUID PRIMARY KEY,
    tenant_id               UUID NOT NULL REFERENCES tenant (id),
    student_id              UUID NOT NULL,
    course_id               UUID NOT NULL,
    card_type               VARCHAR(10) NOT NULL,
    valid_from              DATE NOT NULL,
    valid_to                DATE NOT NULL,
    timezone                VARCHAR(64) NOT NULL,
    effective_from          TIMESTAMPTZ NOT NULL,
    effective_until         TIMESTAMPTZ NOT NULL,
    reason                  TEXT NOT NULL,
    currency                VARCHAR(3) NOT NULL,
    nominal_monthly_amount  NUMERIC(12,2),
    nominal_value           NUMERIC(12,2),
    issued_by               UUID NOT NULL,
    issued_at               TIMESTAMPTZ NOT NULL,
    idempotency_key         UUID NOT NULL,
    enrollment_created      BOOLEAN NOT NULL,
    revoked_at              TIMESTAMPTZ,
    revoked_by              UUID,
    revoked_reason          TEXT,

    CONSTRAINT uq_free_card_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_free_card_tenant_idempotency_key UNIQUE (tenant_id, idempotency_key),

    CONSTRAINT fk_free_card_student FOREIGN KEY (tenant_id, student_id)
        REFERENCES tenant_user (tenant_id, id),
    -- No cascade: a course with free cards is not silently hard-deleted.
    CONSTRAINT fk_free_card_course FOREIGN KEY (tenant_id, course_id)
        REFERENCES course (tenant_id, id),
    CONSTRAINT fk_free_card_issued_by FOREIGN KEY (tenant_id, issued_by)
        REFERENCES tenant_user (tenant_id, id),
    CONSTRAINT fk_free_card_revoked_by FOREIGN KEY (tenant_id, revoked_by)
        REFERENCES tenant_user (tenant_id, id),

    CONSTRAINT ck_free_card_card_type CHECK (card_type IN ('FULL')),
    CONSTRAINT ck_free_card_valid_range CHECK (valid_to >= valid_from),
    CONSTRAINT ck_free_card_effective_range CHECK (effective_until > effective_from),
    CONSTRAINT ck_free_card_timezone_not_blank CHECK (btrim(timezone) <> ''),
    CONSTRAINT ck_free_card_reason_not_blank CHECK (btrim(reason) <> ''),
    CONSTRAINT ck_free_card_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_free_card_nominal_monthly_amount CHECK (
        nominal_monthly_amount IS NULL OR nominal_monthly_amount >= 0
    ),
    CONSTRAINT ck_free_card_nominal_value CHECK (
        nominal_value IS NULL OR nominal_value >= 0
    ),
    -- Revocation is all-or-none on (revoked_at, revoked_by, revoked_reason),
    -- with a non-blank reason.
    CONSTRAINT ck_free_card_revocation CHECK (
        (revoked_at IS NULL AND revoked_by IS NULL AND revoked_reason IS NULL)
        OR (revoked_at IS NOT NULL AND revoked_by IS NOT NULL
            AND revoked_reason IS NOT NULL AND btrim(revoked_reason) <> '')
    )
);

-- Evaluator / overlap check: cards of one student for one course.
CREATE INDEX idx_free_card_tenant_student_course
    ON free_card (tenant_id, student_id, course_id);

-- Cards of one course (staff list, course FK delete checks).
CREATE INDEX idx_free_card_tenant_course
    ON free_card (tenant_id, course_id);

-- ---------------------------------------------------------------------------
-- Lifecycle enforcement: no DELETE; UPDATE may only set the revoke columns
-- once (NULL -> values). A no-op UPDATE writing identical values (e.g. a
-- full-row ORM flush) is allowed.
-- ---------------------------------------------------------------------------

CREATE FUNCTION enforce_free_card_lifecycle() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'free_card rows are never deleted (revoke instead)';
    END IF;

    IF (NEW.id, NEW.tenant_id, NEW.student_id, NEW.course_id, NEW.card_type,
        NEW.valid_from, NEW.valid_to, NEW.timezone, NEW.effective_from,
        NEW.effective_until, NEW.reason, NEW.currency, NEW.nominal_monthly_amount,
        NEW.nominal_value, NEW.issued_by, NEW.issued_at, NEW.idempotency_key,
        NEW.enrollment_created)
       IS DISTINCT FROM
       (OLD.id, OLD.tenant_id, OLD.student_id, OLD.course_id, OLD.card_type,
        OLD.valid_from, OLD.valid_to, OLD.timezone, OLD.effective_from,
        OLD.effective_until, OLD.reason, OLD.currency, OLD.nominal_monthly_amount,
        OLD.nominal_value, OLD.issued_by, OLD.issued_at, OLD.idempotency_key,
        OLD.enrollment_created) THEN
        RAISE EXCEPTION 'free_card is immutable except for revocation';
    END IF;

    IF OLD.revoked_at IS NOT NULL
       AND (NEW.revoked_at, NEW.revoked_by, NEW.revoked_reason)
           IS DISTINCT FROM (OLD.revoked_at, OLD.revoked_by, OLD.revoked_reason) THEN
        RAISE EXCEPTION 'free_card revocation is set once';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_free_card_lifecycle
    BEFORE UPDATE OR DELETE ON free_card
    FOR EACH ROW EXECUTE FUNCTION enforce_free_card_lifecycle();
