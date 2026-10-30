-- exam-management (Klass Exam Module, Slice D - docs/api/exam-management-v2.md "Slice D";
-- spec docs/specs/klass-exam-module.md MG1-MG4, D2, D8): written / physical exam results
-- (attendance, marks, marked paper) and written answer-sheet documents.
--
-- Purely additive. No earlier migration is edited. MCQ scores stay on exam_attempt (V113) and
-- are never duplicated here.
--
-- Tenant-owned: every new table has tenant_id NOT NULL FK, tenant-leading indexes and
-- composite same-tenant FKs. A result or a document requires an exam enrollment (composite FK
-- to exam_enrollment), so neither can exist for a student who may not sit the exam, and an
-- enrollment with a result or a document can never be deleted.

-- 1. media_file.usage_area gains the two student-specific exam document purposes. Like the
--    V109 / V111 exam usages they never appear in the Media Library and are only served
--    through the exam module's own authorization (signed, short-lived URLs).
--      EXAM_ANSWER_SHEET  a written exam answer sheet (student upload, or staff on behalf).
--      EXAM_MARKED_PAPER  a marked answer paper returned to the student once results publish.

ALTER TABLE media_file DROP CONSTRAINT ck_media_file_usage_area;
ALTER TABLE media_file ADD CONSTRAINT ck_media_file_usage_area
    CHECK (usage_area IN ('COURSE_LIBRARY', 'TEACHER_PROFILE', 'EXAM_QUESTION', 'EXAM_QUESTION_PAPER',
                          'EXAM_MARKING_SCHEME', 'EXAM_ANSWER_SHEET', 'EXAM_MARKED_PAPER'));

-- 2. exam_result: one row per (exam, enrolled student) of a WRITTEN or PHYSICAL exam, created on
--    the first staff mark / attendance / marked-paper write and updated in place by audited
--    staff actions only (every change writes an audit_log entry with before/after in the same
--    transaction). Never deleted.
--      attendance             PHYSICAL: PRESENT | ABSENT; NULL = not recorded (WRITTEN: NULL).
--      marks                  0 <= marks (<= exam.total_marks, enforced by the service, which
--                             also refuses lowering a physical exam's total below a mark).
--                             An ABSENT student has no marks.
--      marked_paper_media_id  WRITTEN: the marked answer paper (EXAM_MARKED_PAPER media).
--      marked_by / marked_at  who last set or changed the marks / attendance, and when.

CREATE TABLE exam_result (
    id                     UUID PRIMARY KEY,
    tenant_id              UUID NOT NULL REFERENCES tenant (id),
    exam_id                UUID NOT NULL,
    student_id             UUID NOT NULL,
    attendance             VARCHAR(7),
    marks                  NUMERIC(8, 2),
    marked_paper_media_id  UUID,
    marked_by              UUID,
    marked_at              TIMESTAMPTZ,
    created_at             TIMESTAMPTZ NOT NULL,
    updated_at             TIMESTAMPTZ NOT NULL,
    created_by             UUID,
    updated_by             UUID,

    CONSTRAINT uq_exam_result_tenant_id UNIQUE (tenant_id, id),
    -- One result per student and exam; also the exam's result read index (tenant-leading).
    CONSTRAINT uq_exam_result_tenant_exam_student UNIQUE (tenant_id, exam_id, student_id),

    -- A result requires an exam enrollment (same tenant, same exam, same student).
    CONSTRAINT fk_exam_result_enrollment FOREIGN KEY (tenant_id, exam_id, student_id)
        REFERENCES exam_enrollment (tenant_id, exam_id, student_id),
    CONSTRAINT fk_exam_result_marked_paper_media FOREIGN KEY (tenant_id, marked_paper_media_id)
        REFERENCES media_file (tenant_id, id),
    CONSTRAINT fk_exam_result_marked_by FOREIGN KEY (tenant_id, marked_by)
        REFERENCES tenant_user (tenant_id, id),

    CONSTRAINT ck_exam_result_attendance CHECK (attendance IS NULL OR attendance IN ('PRESENT', 'ABSENT')),
    CONSTRAINT ck_exam_result_marks_nonnegative CHECK (marks IS NULL OR marks >= 0),
    CONSTRAINT ck_exam_result_absent_has_no_marks CHECK (attendance IS DISTINCT FROM 'ABSENT' OR marks IS NULL),
    CONSTRAINT ck_exam_result_marked_fields_together CHECK ((marked_by IS NULL) = (marked_at IS NULL)),
    CONSTRAINT ck_exam_result_marks_have_marker CHECK (marks IS NULL OR marked_at IS NOT NULL)
);

-- Backs fk_exam_result_marked_paper_media (media lookups / RESTRICT checks).
CREATE INDEX idx_exam_result_tenant_marked_paper
    ON exam_result (tenant_id, marked_paper_media_id)
    WHERE marked_paper_media_id IS NOT NULL;

-- Results are academic history: never deleted, and a row never moves to another exam/student.
CREATE FUNCTION exam_result_guard() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'exam results are never deleted'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF (NEW.id, NEW.tenant_id, NEW.exam_id, NEW.student_id)
        IS DISTINCT FROM (OLD.id, OLD.tenant_id, OLD.exam_id, OLD.student_id) THEN
        RAISE EXCEPTION 'exam result identity is immutable'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_exam_result_guard
    BEFORE UPDATE OR DELETE ON exam_result
    FOR EACH ROW EXECUTE FUNCTION exam_result_guard();

-- 3. exam_submission_document: a WRITTEN exam answer sheet (EXAM_ANSWER_SHEET media) of one
--    enrolled student. uploaded_by_role STUDENT = the student themself (uploaded_by must be the
--    student), STAFF = "Enter answer sheet" on the student's behalf. Staff may remove a document
--    (audited) only before results are published: removal is a soft delete (deleted_at /
--    deleted_by), the row and its media stay as history. Rows are never hard-deleted.

CREATE TABLE exam_submission_document (
    id                UUID PRIMARY KEY,
    tenant_id         UUID NOT NULL REFERENCES tenant (id),
    exam_id           UUID NOT NULL,
    student_id        UUID NOT NULL,
    media_id          UUID NOT NULL,
    uploaded_by       UUID NOT NULL,
    uploaded_by_role  VARCHAR(7) NOT NULL,
    uploaded_at       TIMESTAMPTZ NOT NULL,
    deleted_at        TIMESTAMPTZ,
    deleted_by        UUID,
    created_at        TIMESTAMPTZ NOT NULL,
    updated_at        TIMESTAMPTZ NOT NULL,
    created_by        UUID,
    updated_by        UUID,

    CONSTRAINT uq_exam_submission_document_tenant_id UNIQUE (tenant_id, id),
    -- A stored file is attached to at most one submission.
    CONSTRAINT uq_exam_submission_document_tenant_media UNIQUE (tenant_id, media_id),

    -- A document requires an exam enrollment (same tenant, same exam, same student).
    CONSTRAINT fk_exam_submission_document_enrollment FOREIGN KEY (tenant_id, exam_id, student_id)
        REFERENCES exam_enrollment (tenant_id, exam_id, student_id),
    CONSTRAINT fk_exam_submission_document_media FOREIGN KEY (tenant_id, media_id)
        REFERENCES media_file (tenant_id, id),
    CONSTRAINT fk_exam_submission_document_uploaded_by FOREIGN KEY (tenant_id, uploaded_by)
        REFERENCES tenant_user (tenant_id, id),
    CONSTRAINT fk_exam_submission_document_deleted_by FOREIGN KEY (tenant_id, deleted_by)
        REFERENCES tenant_user (tenant_id, id),

    CONSTRAINT ck_exam_submission_document_role CHECK (uploaded_by_role IN ('STUDENT', 'STAFF')),
    CONSTRAINT ck_exam_submission_document_student_self CHECK (
        uploaded_by_role <> 'STUDENT' OR uploaded_by = student_id
    ),
    CONSTRAINT ck_exam_submission_document_deleted_together CHECK ((deleted_at IS NULL) = (deleted_by IS NULL))
);

-- The exam's / one student's documents in upload order (management rows, my-documents).
CREATE INDEX idx_exam_submission_document_tenant_exam_student
    ON exam_submission_document (tenant_id, exam_id, student_id, uploaded_at);

-- Documents are never hard-deleted; only the soft-delete columns may change, once.
CREATE FUNCTION exam_submission_document_guard() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'exam submission documents are never deleted'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF (NEW.id, NEW.tenant_id, NEW.exam_id, NEW.student_id, NEW.media_id, NEW.uploaded_by,
        NEW.uploaded_by_role, NEW.uploaded_at)
        IS DISTINCT FROM
       (OLD.id, OLD.tenant_id, OLD.exam_id, OLD.student_id, OLD.media_id, OLD.uploaded_by,
        OLD.uploaded_by_role, OLD.uploaded_at) THEN
        RAISE EXCEPTION 'exam submission document is immutable'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    IF OLD.deleted_at IS NOT NULL AND (NEW.deleted_at IS DISTINCT FROM OLD.deleted_at
        OR NEW.deleted_by IS DISTINCT FROM OLD.deleted_by) THEN
        RAISE EXCEPTION 'a removed exam submission document cannot be restored'
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_exam_submission_document_guard
    BEFORE UPDATE OR DELETE ON exam_submission_document
    FOR EACH ROW EXECUTE FUNCTION exam_submission_document_guard();
