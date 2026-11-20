-- exam-management (Klass Exam Module, Slice B - docs/plans/klass-exam-module.md "V112 paper
-- snapshot"; spec D3 / QB10): the frozen question paper of an MCQ exam.
--
-- When an MCQ exam is saved the server draws `quota` random live questions per module and
-- copies them here (body, marks, explanation, options + correct flag). Every candidate sits
-- the same paper; later bank edits or soft deletes never change it. Before the first attempt
-- the paper may be replaced wholesale (redraw = delete + insert, never UPDATE); once an
-- attempt exists it is immutable. Purely additive.
--
-- Tenant-owned: tenant_id NOT NULL FK, tenant-leading indexes, composite same-tenant FKs.

CREATE TABLE exam_paper_item (
    id                  UUID PRIMARY KEY,
    tenant_id           UUID NOT NULL REFERENCES tenant (id),
    exam_id             UUID NOT NULL,
    sequence            INTEGER NOT NULL,
    -- The bank question this item was copied from (traceability / stats). RESTRICT: module
    -- questions are only ever soft-deleted, so this never blocks a bank operation.
    source_question_id  UUID NOT NULL,
    body_html           TEXT NOT NULL,
    marks               NUMERIC(6, 2) NOT NULL,
    explanation_html    TEXT,
    created_at          TIMESTAMPTZ NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL,
    created_by          UUID,
    updated_by          UUID,

    CONSTRAINT uq_exam_paper_item_tenant_id UNIQUE (tenant_id, id),
    -- Composite-FK target for per-attempt rows (Slice C) that must stay inside one exam.
    CONSTRAINT uq_exam_paper_item_tenant_exam_id UNIQUE (tenant_id, exam_id, id),
    -- Paper order; also the exam's paper read index (tenant-leading).
    CONSTRAINT uq_exam_paper_item_tenant_exam_sequence UNIQUE (tenant_id, exam_id, sequence),
    -- A question is drawn at most once per paper.
    CONSTRAINT uq_exam_paper_item_tenant_exam_source UNIQUE (tenant_id, exam_id, source_question_id),

    CONSTRAINT fk_exam_paper_item_exam FOREIGN KEY (tenant_id, exam_id)
        REFERENCES exam (tenant_id, id),
    CONSTRAINT fk_exam_paper_item_source_question FOREIGN KEY (tenant_id, source_question_id)
        REFERENCES exam_question (tenant_id, id),

    CONSTRAINT ck_exam_paper_item_sequence_positive CHECK (sequence > 0),
    CONSTRAINT ck_exam_paper_item_marks_range CHECK (marks > 0 AND marks <= 100),
    CONSTRAINT ck_exam_paper_item_body_not_blank CHECK (btrim(body_html) <> '')
);

-- "Which papers use this bank question" (reverse lookup for the RESTRICT FK).
CREATE INDEX idx_exam_paper_item_tenant_source_question
    ON exam_paper_item (tenant_id, source_question_id);

CREATE TABLE exam_paper_option (
    id             UUID PRIMARY KEY,
    tenant_id      UUID NOT NULL REFERENCES tenant (id),
    paper_item_id  UUID NOT NULL,
    sequence       INTEGER NOT NULL,
    html           TEXT NOT NULL,
    correct        BOOLEAN NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL,
    created_by     UUID,
    updated_by     UUID,

    CONSTRAINT uq_exam_paper_option_tenant_id UNIQUE (tenant_id, id),
    -- Composite-FK target so a selected option can be proven to belong to its item (Slice C).
    CONSTRAINT uq_exam_paper_option_tenant_item_id UNIQUE (tenant_id, paper_item_id, id),
    -- Option order; also the item's option read index (tenant-leading).
    CONSTRAINT uq_exam_paper_option_tenant_item_sequence UNIQUE (tenant_id, paper_item_id, sequence),

    -- CASCADE: an option is a pure structural child of its item (only reachable on a redraw
    -- before the first attempt - see trg_exam_paper_item_no_delete_after_attempt).
    CONSTRAINT fk_exam_paper_option_item FOREIGN KEY (tenant_id, paper_item_id)
        REFERENCES exam_paper_item (tenant_id, id) ON DELETE CASCADE,

    CONSTRAINT ck_exam_paper_option_sequence_positive CHECK (sequence > 0),
    CONSTRAINT ck_exam_paper_option_html_not_blank CHECK (btrim(html) <> '')
);

-- D1: exactly one correct option per item (at most one here; at least one is enforced by
-- the drawing service, which only snapshots valid bank questions).
CREATE UNIQUE INDEX uq_exam_paper_option_one_correct
    ON exam_paper_option (tenant_id, paper_item_id)
    WHERE correct;

-- Immutability: a snapshot row is never updated (a redraw replaces the whole paper), and a
-- paper can no longer be replaced once any attempt exists for its exam.
CREATE FUNCTION exam_paper_forbid_update() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'exam paper snapshots are immutable (%)', TG_TABLE_NAME
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

CREATE TRIGGER trg_exam_paper_item_no_update
    BEFORE UPDATE ON exam_paper_item
    FOR EACH ROW EXECUTE FUNCTION exam_paper_forbid_update();

CREATE TRIGGER trg_exam_paper_option_no_update
    BEFORE UPDATE ON exam_paper_option
    FOR EACH ROW EXECUTE FUNCTION exam_paper_forbid_update();

CREATE FUNCTION exam_paper_item_forbid_delete_after_attempt() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF EXISTS (SELECT 1 FROM exam_attempt a WHERE a.tenant_id = OLD.tenant_id AND a.exam_id = OLD.exam_id) THEN
        RAISE EXCEPTION 'the paper of an exam with attempts cannot be changed'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN OLD;
END;
$$;

CREATE TRIGGER trg_exam_paper_item_no_delete_after_attempt
    BEFORE DELETE ON exam_paper_item
    FOR EACH ROW EXECUTE FUNCTION exam_paper_item_forbid_delete_after_attempt();
