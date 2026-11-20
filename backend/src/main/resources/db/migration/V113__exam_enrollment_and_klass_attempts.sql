-- exam-management (Klass Exam Module, Slice C - docs/api/exam-management-v2.md "Slice C";
-- spec docs/specs/klass-exam-module.md D2 / D4 / D5; ADR-032): exam enrollment, Klass MCQ
-- attempts with a persisted per-attempt question/option order, server-side grading, and the
-- exam password failure log backing the 5/min/student/exam rate limit.
--
-- Purely additive. No earlier migration is edited. Payment, ledger and course-enrollment
-- tables are only REFERENCED (read-only provenance FKs), never altered.
--
-- Tenant-owned: every new table has tenant_id NOT NULL FK, tenant-leading indexes and
-- composite same-tenant FKs.

-- 1. exam_enrollment (ADR-032): who may sit a Klass exam. Exam enrollment is a separate
--    concept from (free) course participation and is never created by or creating course
--    enrollment. One row per (tenant, exam, student).
--    source MANUAL      staff added the student (audited).
--           ENROLL_ALL  materialized for an active course participant of an "enroll all" exam (D4).
--           PAYMENT     a confirmed payment matched the exam's payment criteria (payment_id), or
--                       an effective Free Card covered a criteria month (free_card_id, ADR-030 /
--                       D5: Free Cards count for month criteria, never toward amounts).
--    Rows are removed only by an audited staff removal or by refund handling, and never while
--    an attempt exists (fk_exam_attempt_klass_enrollment below backs the 409 HAS_ATTEMPT).

CREATE TABLE exam_enrollment (
    id            UUID PRIMARY KEY,
    tenant_id     UUID NOT NULL REFERENCES tenant (id),
    exam_id       UUID NOT NULL,
    student_id    UUID NOT NULL,
    source        VARCHAR(12) NOT NULL,
    payment_id    UUID,
    free_card_id  UUID,
    created_at    TIMESTAMPTZ NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL,
    created_by    UUID,
    updated_by    UUID,

    CONSTRAINT uq_exam_enrollment_tenant_id UNIQUE (tenant_id, id),
    -- One enrollment per student and exam; the ON CONFLICT DO NOTHING target of every
    -- idempotent insert (enroll-all, payment listener, sync) and the exam's read index.
    CONSTRAINT uq_exam_enrollment_tenant_exam_student UNIQUE (tenant_id, exam_id, student_id),

    CONSTRAINT fk_exam_enrollment_exam FOREIGN KEY (tenant_id, exam_id)
        REFERENCES exam (tenant_id, id),
    CONSTRAINT fk_exam_enrollment_student FOREIGN KEY (tenant_id, student_id)
        REFERENCES tenant_user (tenant_id, id),
    -- Provenance only (read-only reference; payments are never deleted).
    CONSTRAINT fk_exam_enrollment_payment FOREIGN KEY (tenant_id, payment_id)
        REFERENCES payment (tenant_id, id),
    CONSTRAINT fk_exam_enrollment_free_card FOREIGN KEY (tenant_id, free_card_id)
        REFERENCES free_card (tenant_id, id),

    CONSTRAINT ck_exam_enrollment_source CHECK (source IN ('MANUAL', 'ENROLL_ALL', 'PAYMENT')),
    -- A PAYMENT enrollment always names exactly one piece of evidence; other sources none.
    CONSTRAINT ck_exam_enrollment_payment_evidence CHECK (
        (source = 'PAYMENT' AND ((payment_id IS NOT NULL) <> (free_card_id IS NOT NULL)))
        OR (source <> 'PAYMENT' AND payment_id IS NULL AND free_card_id IS NULL)
    )
);

-- Student's own exam list.
CREATE INDEX idx_exam_enrollment_tenant_student
    ON exam_enrollment (tenant_id, student_id);

-- Refund handling: PAYMENT enrollments of one payment.
CREATE INDEX idx_exam_enrollment_tenant_payment
    ON exam_enrollment (tenant_id, payment_id)
    WHERE payment_id IS NOT NULL;

-- Backs fk_exam_enrollment_free_card.
CREATE INDEX idx_exam_enrollment_tenant_free_card
    ON exam_enrollment (tenant_id, free_card_id)
    WHERE free_card_id IS NOT NULL;

-- 2. exam_attempt: Klass attempts (D2). Legacy MVP-017 rows keep klass_attempt = false and every
--    new column NULL (ck_exam_attempt_klass_fields). Existing data may hold several sequential
--    legacy attempts per student, so the one-attempt rule is a PARTIAL unique index scoped to
--    klass_attempt rows only.
--      deadline_at       min(started_at + duration, exam end), fixed at start.
--      score/graded_at   computed once, at finalization, from the frozen paper.
--      answered_count    items with a selected option at finalization.
--      finalized_reason  SUBMITTED (student) | TIMEOUT (deadline reached; status EXPIRED).
--    klass_exam_id is a generated column (exam_id for Klass attempts, NULL for legacy ones) so
--    the composite FK to exam_enrollment applies to Klass attempts only (MATCH SIMPLE): a Klass
--    attempt cannot exist without an exam enrollment, and an enrollment with an attempt cannot
--    be deleted.

ALTER TABLE exam_attempt
    ADD COLUMN klass_attempt     BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN deadline_at       TIMESTAMPTZ,
    ADD COLUMN score             NUMERIC(10, 2),
    ADD COLUMN graded_at         TIMESTAMPTZ,
    ADD COLUMN answered_count    INTEGER,
    ADD COLUMN finalized_reason  VARCHAR(10),
    ADD COLUMN klass_exam_id     UUID GENERATED ALWAYS AS (CASE WHEN klass_attempt THEN exam_id END) STORED;

ALTER TABLE exam_attempt
    -- Composite-FK target so attempt items stay inside their attempt's exam.
    ADD CONSTRAINT uq_exam_attempt_tenant_exam_id UNIQUE (tenant_id, exam_id, id),

    ADD CONSTRAINT fk_exam_attempt_klass_enrollment FOREIGN KEY (tenant_id, klass_exam_id, student_id)
        REFERENCES exam_enrollment (tenant_id, exam_id, student_id),

    ADD CONSTRAINT ck_exam_attempt_klass_fields CHECK (
        klass_attempt OR (
            deadline_at IS NULL AND score IS NULL AND graded_at IS NULL AND answered_count IS NULL
            AND finalized_reason IS NULL
        )
    ),
    ADD CONSTRAINT ck_exam_attempt_klass_deadline CHECK (
        NOT klass_attempt OR (deadline_at IS NOT NULL AND deadline_at > started_at)
    ),
    ADD CONSTRAINT ck_exam_attempt_finalized_reason CHECK (
        finalized_reason IS NULL OR finalized_reason IN ('SUBMITTED', 'TIMEOUT')
    ),
    ADD CONSTRAINT ck_exam_attempt_klass_reason_matches_status CHECK (
        NOT klass_attempt
        OR (status = 'IN_PROGRESS' AND finalized_reason IS NULL)
        OR (status = 'SUBMITTED' AND finalized_reason = 'SUBMITTED')
        OR (status = 'EXPIRED' AND finalized_reason = 'TIMEOUT')
    ),
    ADD CONSTRAINT ck_exam_attempt_grade_fields_together CHECK ((score IS NULL) = (graded_at IS NULL)),
    ADD CONSTRAINT ck_exam_attempt_graded_only_when_finalized CHECK (
        graded_at IS NULL OR status <> 'IN_PROGRESS'
    ),
    ADD CONSTRAINT ck_exam_attempt_score_nonnegative CHECK (score IS NULL OR score >= 0),
    ADD CONSTRAINT ck_exam_attempt_answered_count_nonnegative CHECK (
        answered_count IS NULL OR answered_count >= 0
    );

-- D2: one Klass attempt per student and exam (also the resume lookup).
CREATE UNIQUE INDEX uq_exam_attempt_tenant_exam_student_klass
    ON exam_attempt (tenant_id, exam_id, student_id)
    WHERE klass_attempt;

-- Deadline sweep (cross-tenant scheduled job): open Klass attempts by deadline.
CREATE INDEX idx_exam_attempt_klass_open_deadline
    ON exam_attempt (deadline_at)
    WHERE klass_attempt AND status = 'IN_PROGRESS';

-- A finalized Klass attempt never re-opens and its finalization facts never change.
CREATE FUNCTION exam_attempt_klass_guard() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF NEW.klass_attempt IS DISTINCT FROM OLD.klass_attempt
        OR (OLD.klass_attempt AND NEW.started_at IS DISTINCT FROM OLD.started_at) THEN
        RAISE EXCEPTION 'exam attempt identity is immutable'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF OLD.klass_attempt AND OLD.status <> 'IN_PROGRESS' AND (
        NEW.status IS DISTINCT FROM OLD.status
        OR NEW.finalized_reason IS DISTINCT FROM OLD.finalized_reason
        OR NEW.submitted_at IS DISTINCT FROM OLD.submitted_at
        OR NEW.deadline_at IS DISTINCT FROM OLD.deadline_at
        OR NEW.answered_count IS DISTINCT FROM OLD.answered_count
    ) THEN
        RAISE EXCEPTION 'a finalized exam attempt cannot be changed'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_exam_attempt_klass_guard
    BEFORE UPDATE ON exam_attempt
    FOR EACH ROW EXECUTE FUNCTION exam_attempt_klass_guard();

-- 3. exam_attempt_item: one row per frozen paper item of a Klass MCQ attempt, in the attempt's
--    own persisted order (shuffled once at start when enabled). option_order = the paper
--    option ids in display order. selected_option_id is proven to be an option of the same
--    paper item by the composite FK. revision is the client's monotonic save counter (a save
--    with revision <= stored is ignored). awarded_marks is written once at grading.

CREATE TABLE exam_attempt_item (
    id                  UUID PRIMARY KEY,
    tenant_id           UUID NOT NULL REFERENCES tenant (id),
    attempt_id          UUID NOT NULL,
    exam_id             UUID NOT NULL,
    sequence            INTEGER NOT NULL,
    paper_item_id       UUID NOT NULL,
    option_order        UUID[] NOT NULL,
    selected_option_id  UUID,
    revision            BIGINT NOT NULL DEFAULT 0,
    answered_at         TIMESTAMPTZ,
    awarded_marks       NUMERIC(6, 2),
    created_at          TIMESTAMPTZ NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL,
    created_by          UUID,
    updated_by          UUID,

    CONSTRAINT uq_exam_attempt_item_tenant_id UNIQUE (tenant_id, id),
    -- Attempt order; also the attempt's item read index (tenant-leading).
    CONSTRAINT uq_exam_attempt_item_tenant_attempt_sequence UNIQUE (tenant_id, attempt_id, sequence),
    CONSTRAINT uq_exam_attempt_item_tenant_attempt_paper_item UNIQUE (tenant_id, attempt_id, paper_item_id),

    CONSTRAINT fk_exam_attempt_item_attempt FOREIGN KEY (tenant_id, exam_id, attempt_id)
        REFERENCES exam_attempt (tenant_id, exam_id, id),
    -- The item belongs to the attempt's own exam paper.
    CONSTRAINT fk_exam_attempt_item_paper_item FOREIGN KEY (tenant_id, exam_id, paper_item_id)
        REFERENCES exam_paper_item (tenant_id, exam_id, id),
    -- The selected option belongs to this item (MATCH SIMPLE: NULL = unanswered).
    CONSTRAINT fk_exam_attempt_item_selected_option FOREIGN KEY (tenant_id, paper_item_id, selected_option_id)
        REFERENCES exam_paper_option (tenant_id, paper_item_id, id),

    CONSTRAINT ck_exam_attempt_item_sequence_positive CHECK (sequence > 0),
    CONSTRAINT ck_exam_attempt_item_option_order CHECK (cardinality(option_order) >= 2),
    CONSTRAINT ck_exam_attempt_item_revision_nonnegative CHECK (revision >= 0),
    CONSTRAINT ck_exam_attempt_item_answered_at CHECK (selected_option_id IS NULL OR answered_at IS NOT NULL),
    CONSTRAINT ck_exam_attempt_item_awarded_marks CHECK (awarded_marks IS NULL OR awarded_marks >= 0)
);

-- Reverse lookup for fk_exam_attempt_item_paper_item / per-question stats (Slice E).
CREATE INDEX idx_exam_attempt_item_tenant_exam_paper_item
    ON exam_attempt_item (tenant_id, exam_id, paper_item_id);

-- Items are never deleted; their structure never changes; once graded nothing changes.
CREATE FUNCTION exam_attempt_item_guard() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'exam attempt items are never deleted'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF (NEW.id, NEW.tenant_id, NEW.attempt_id, NEW.exam_id, NEW.sequence, NEW.paper_item_id, NEW.option_order)
        IS DISTINCT FROM
       (OLD.id, OLD.tenant_id, OLD.attempt_id, OLD.exam_id, OLD.sequence, OLD.paper_item_id, OLD.option_order) THEN
        RAISE EXCEPTION 'exam attempt item structure is immutable'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF OLD.awarded_marks IS NOT NULL AND (
        NEW.selected_option_id IS DISTINCT FROM OLD.selected_option_id
        OR NEW.revision IS DISTINCT FROM OLD.revision
        OR NEW.answered_at IS DISTINCT FROM OLD.answered_at
        OR NEW.awarded_marks IS DISTINCT FROM OLD.awarded_marks
    ) THEN
        RAISE EXCEPTION 'a graded exam attempt item cannot be changed'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_exam_attempt_item_guard
    BEFORE UPDATE OR DELETE ON exam_attempt_item
    FOR EACH ROW EXECUTE FUNCTION exam_attempt_item_guard();

-- 4. exam_password_failure: wrong exam passwords per (exam, student), DB-backed so the
--    5-per-minute limit survives restarts and holds across instances. Never stores the
--    attempted value. Old rows are pruned by the attempt sweep.

CREATE TABLE exam_password_failure (
    id          UUID PRIMARY KEY,
    tenant_id   UUID NOT NULL REFERENCES tenant (id),
    exam_id     UUID NOT NULL,
    student_id  UUID NOT NULL,
    failed_at   TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_exam_password_failure_exam FOREIGN KEY (tenant_id, exam_id)
        REFERENCES exam (tenant_id, id),
    CONSTRAINT fk_exam_password_failure_student FOREIGN KEY (tenant_id, student_id)
        REFERENCES tenant_user (tenant_id, id)
);

-- Rate-limit window count.
CREATE INDEX idx_exam_password_failure_tenant_exam_student_failed
    ON exam_password_failure (tenant_id, exam_id, student_id, failed_at);

-- Pruning (cross-tenant scheduled job).
CREATE INDEX idx_exam_password_failure_failed_at
    ON exam_password_failure (failed_at);
