-- exam-management (Klass Exam Module, Slice A - docs/plans/klass-exam-module.md "question
-- bank" migration, planned as V108; V108 is taken by the Java migration
-- db.migration.V108__backfill_tenant_user_login_mobile, so this is V109): module-scoped
-- question bank with marks, explanation, ordering, soft delete and protected question images
-- (spec docs/specs/klass-exam-module.md D1 / QB1-QB7).
--
-- Purely additive. No earlier migration is edited. Every existing exam_question row is a
-- legacy MVP-017 course-level question: it keeps module_id = NULL, gets marks = 1 (the fixed
-- one point MVP-017 already scored it at), sequence = NULL and deleted_at = NULL, and stays
-- reachable only through the legacy /api/v1/exams/... endpoints. V27's manual_score <= 1 cap
-- on exam_answer belongs to that legacy path and is deliberately left untouched.
--
-- 1. media_file.usage_area gains EXAM_QUESTION: question/option/explanation images are
--    stored once in object storage like any media file, but never listed in the Media
--    Library (MediaFileRepository#library() filters COURSE_LIBRARY) and never attachable to
--    lessons. They are referenced from sanitized HTML as <img data-media-id="..."> only;
--    a short-lived signed src is added on every authorized read.

ALTER TABLE media_file DROP CONSTRAINT ck_media_file_usage_area;
ALTER TABLE media_file ADD CONSTRAINT ck_media_file_usage_area
    CHECK (usage_area IN ('COURSE_LIBRARY', 'TEACHER_PROFILE', 'EXAM_QUESTION'));

-- 2. course_module gets a (tenant_id, course_id, id) key so a question's module can be
--    schema-enforced to belong to the SAME course (and tenant) as the question itself, not
--    merely to the same tenant. Redundant with the primary key for uniqueness; it exists
--    only as a composite-FK target.

ALTER TABLE course_module
    ADD CONSTRAINT uq_course_module_tenant_course_id UNIQUE (tenant_id, course_id, id);

-- 3. exam_question: module bank columns.
--    module_id   NULL = legacy course-level question (MVP-017); NOT NULL = module bank.
--    marks       per-question marks (0 < marks <= 100, 2 decimals). Legacy rows: 1.
--    explanation sanitized HTML shown after release (optional).
--    sequence    1-based display order inside a module bank (module questions only).
--    deleted_at  soft delete: a deleted question disappears from the bank, but rows that
--                already reference it (exam_question_link, exam_answer, later paper
--                snapshots) keep resolving - no hard delete for module questions.
--    deleted_by  who soft-deleted it (both NULL or both set).

ALTER TABLE exam_question
    ADD COLUMN module_id    UUID,
    ADD COLUMN marks        NUMERIC(6, 2) NOT NULL DEFAULT 1,
    ADD COLUMN explanation  TEXT,
    ADD COLUMN sequence     INTEGER,
    ADD COLUMN deleted_at   TIMESTAMPTZ,
    ADD COLUMN deleted_by   UUID;

-- RESTRICT (no CASCADE): deleting a module whose bank holds questions (including soft-
-- deleted ones still referenced by exams) is refused (409 from CourseModuleService's
-- existing DataIntegrityViolationException handling) - question/exam history wins, the
-- same posture V86/V87 apply to order lines. MATCH SIMPLE: legacy rows (module_id NULL)
-- are not checked.
ALTER TABLE exam_question
    ADD CONSTRAINT fk_exam_question_module FOREIGN KEY (tenant_id, course_id, module_id)
        REFERENCES course_module (tenant_id, course_id, id),
    ADD CONSTRAINT fk_exam_question_deleted_by FOREIGN KEY (tenant_id, deleted_by)
        REFERENCES tenant_user (tenant_id, id),
    ADD CONSTRAINT ck_exam_question_marks_range CHECK (marks > 0 AND marks <= 100),
    ADD CONSTRAINT ck_exam_question_sequence_positive CHECK (sequence IS NULL OR sequence > 0),
    -- The module bank is MCQ-only (D1: exactly one correct option); structured questions
    -- remain a legacy course-level concept.
    ADD CONSTRAINT ck_exam_question_module_is_mcq CHECK (module_id IS NULL OR question_type = 'MCQ'),
    ADD CONSTRAINT ck_exam_question_module_has_sequence CHECK (module_id IS NULL OR sequence IS NOT NULL),
    ADD CONSTRAINT ck_exam_question_deleted_fields_together CHECK (
        (deleted_at IS NULL AND deleted_by IS NULL) OR (deleted_at IS NOT NULL AND deleted_by IS NOT NULL)
    );

-- Module bank read pattern: live questions of one module in display order (list, counts,
-- next-sequence). Partial: soft-deleted rows are never listed.
CREATE INDEX idx_exam_question_tenant_module_sequence
    ON exam_question (tenant_id, module_id, sequence)
    WHERE deleted_at IS NULL;

-- 4. exam_question_option: explicit display order (array order of the editor / CSV
--    columns). Legacy options keep NULL and are ordered by creation as before.

ALTER TABLE exam_question_option
    ADD COLUMN sequence INTEGER;

ALTER TABLE exam_question_option
    ADD CONSTRAINT ck_exam_question_option_sequence_positive CHECK (sequence IS NULL OR sequence > 0);
