-- exam-management (Klass Exam Module, Slice B - docs/plans/klass-exam-module.md "V111 exam
-- config"; spec docs/specs/klass-exam-module.md EX3-EX7, D3, D6): MCQ / WRITTEN / PHYSICAL
-- exam configuration, module quotas, protected exam documents and the encrypted password.
--
-- Purely additive. No earlier migration is edited. Every existing exam row is a legacy
-- MVP-017 exam: it gets config_version = 1, exam_type = 'MCQ', every new flag false and every
-- new nullable column NULL, and keeps working through the legacy /api/v1/exams endpoints
-- exactly as before.
--
-- config_version distinguishes the two models sharing this table:
--   1 = legacy MVP-017 exam (DRAFT -> SCHEDULED -> PUBLISHED -> CLOSED stored lifecycle,
--       exam_question_link question set).
--   2 = Klass exam (Slice B+): status is DERIVED from scheduled_start/scheduled_end and the
--       server clock; the stored status column stays 'DRAFT' forever, which keeps every
--       legacy lifecycle/attempt code path inert for these rows (they refuse non-PUBLISHED
--       exams); the question set is the frozen paper snapshot (V112).

-- 1. media_file.usage_area gains the two exam document purposes. Like EXAM_QUESTION (V109)
--    they never appear in the Media Library and are only served through the exam module's
--    own authorization (signed, short-lived URLs).

ALTER TABLE media_file DROP CONSTRAINT ck_media_file_usage_area;
ALTER TABLE media_file ADD CONSTRAINT ck_media_file_usage_area
    CHECK (usage_area IN ('COURSE_LIBRARY', 'TEACHER_PROFILE', 'EXAM_QUESTION', 'EXAM_QUESTION_PAPER',
                          'EXAM_MARKING_SCHEME'));

-- 2. exam: Klass configuration columns.
--    password_ciphertext   AES-256-GCM ("v1:" + Base64(iv || ciphertext+tag)) of the exam
--                          password, key EXAM_PASSWORD_ENCRYPTION_KEY, bound to the exam by
--                          AAD. Never the plaintext; never returned except by the audited
--                          reveal endpoint.
--    question_count        MCQ: number of questions drawn into the paper (= sum of quotas).
--    cutoff_marks          pass mark in absolute marks (C3).
--    total_marks           MCQ: sum of the drawn paper's marks; WRITTEN: entered by staff;
--                          PHYSICAL: entered later on the management page (D8, Slice D).
--    payment_criteria      auto-enroll-on-payment criteria (ADR-032), present iff the
--                          toggle is on.
--    deleted_at/deleted_by soft delete (exam history is never hard-deleted).
--    time_limit_minutes    becomes nullable: a PHYSICAL exam's duration is optional. Legacy
--                          rows keep it mandatory (ck_exam_legacy_shape).

ALTER TABLE exam
    ADD COLUMN config_version             INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN exam_type                  VARCHAR(10) NOT NULL DEFAULT 'MCQ',
    ADD COLUMN password_ciphertext        TEXT,
    ADD COLUMN question_count             INTEGER,
    ADD COLUMN cutoff_marks               NUMERIC(10, 2),
    ADD COLUMN total_marks                NUMERIC(10, 2),
    ADD COLUMN shuffle_questions          BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN shuffle_answers            BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN enroll_all_students        BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN auto_enroll_on_payment     BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN payment_criteria           JSONB,
    ADD COLUMN show_answers_with_results  BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN question_paper_media_id    UUID,
    ADD COLUMN marking_scheme_media_id    UUID,
    ADD COLUMN deleted_at                 TIMESTAMPTZ,
    ADD COLUMN deleted_by                 UUID;

ALTER TABLE exam ALTER COLUMN time_limit_minutes DROP NOT NULL;

ALTER TABLE exam
    -- Composite-FK target so exam_module_quota can be schema-enforced to the exam's own
    -- course (and tenant). Redundant with the primary key for uniqueness.
    ADD CONSTRAINT uq_exam_tenant_course_id UNIQUE (tenant_id, course_id, id),

    ADD CONSTRAINT fk_exam_deleted_by FOREIGN KEY (tenant_id, deleted_by)
        REFERENCES tenant_user (tenant_id, id),
    -- RESTRICT: a document referenced by an exam cannot be removed out from under it.
    ADD CONSTRAINT fk_exam_question_paper_media FOREIGN KEY (tenant_id, question_paper_media_id)
        REFERENCES media_file (tenant_id, id),
    ADD CONSTRAINT fk_exam_marking_scheme_media FOREIGN KEY (tenant_id, marking_scheme_media_id)
        REFERENCES media_file (tenant_id, id),

    ADD CONSTRAINT ck_exam_config_version CHECK (config_version IN (1, 2)),
    ADD CONSTRAINT ck_exam_type CHECK (exam_type IN ('MCQ', 'WRITTEN', 'PHYSICAL')),
    -- Legacy rows keep their MVP-017 shape.
    ADD CONSTRAINT ck_exam_legacy_shape CHECK (
        config_version = 2 OR (
            exam_type = 'MCQ' AND time_limit_minutes IS NOT NULL AND password_ciphertext IS NULL
            AND question_count IS NULL AND deleted_at IS NULL
        )
    ),
    -- Klass exams never enter the stored legacy lifecycle (status is derived from the clock).
    ADD CONSTRAINT ck_exam_v2_status_unused CHECK (config_version = 1 OR status = 'DRAFT'),
    ADD CONSTRAINT ck_exam_v2_duration_range CHECK (
        config_version = 1 OR time_limit_minutes IS NULL OR time_limit_minutes <= 1440
    ),
    ADD CONSTRAINT ck_exam_password_ciphertext_format CHECK (
        password_ciphertext IS NULL OR password_ciphertext LIKE 'v1:%'
    ),
    ADD CONSTRAINT ck_exam_question_count_positive CHECK (question_count IS NULL OR question_count >= 1),
    ADD CONSTRAINT ck_exam_cutoff_marks_nonnegative CHECK (cutoff_marks IS NULL OR cutoff_marks >= 0),
    ADD CONSTRAINT ck_exam_total_marks_positive CHECK (total_marks IS NULL OR total_marks > 0),
    ADD CONSTRAINT ck_exam_cutoff_within_total CHECK (
        cutoff_marks IS NULL OR total_marks IS NULL OR cutoff_marks <= total_marks
    ),
    -- "Turn off 'Enroll all students' to enroll by payment instead."
    ADD CONSTRAINT ck_exam_enrollment_modes_exclusive CHECK (
        NOT (enroll_all_students AND auto_enroll_on_payment)
    ),
    ADD CONSTRAINT ck_exam_payment_criteria_iff_auto_enroll CHECK (
        auto_enroll_on_payment = (payment_criteria IS NOT NULL)
    ),
    ADD CONSTRAINT ck_exam_payment_criteria_object CHECK (
        payment_criteria IS NULL OR jsonb_typeof(payment_criteria) = 'object'
    ),
    ADD CONSTRAINT ck_exam_deleted_fields_together CHECK (
        (deleted_at IS NULL AND deleted_by IS NULL) OR (deleted_at IS NOT NULL AND deleted_by IS NOT NULL)
    ),
    -- Per-type shape of Klass exams (the API enforces the full field rules; these are the
    -- cheaply expressible invariants).
    ADD CONSTRAINT ck_exam_v2_mcq_shape CHECK (
        config_version = 1 OR exam_type <> 'MCQ' OR (
            time_limit_minutes IS NOT NULL AND password_ciphertext IS NOT NULL AND question_count IS NOT NULL
            AND total_marks IS NOT NULL AND cutoff_marks IS NOT NULL
            AND question_paper_media_id IS NULL AND marking_scheme_media_id IS NULL
        )
    ),
    ADD CONSTRAINT ck_exam_v2_written_shape CHECK (
        config_version = 1 OR exam_type <> 'WRITTEN' OR (
            time_limit_minutes IS NOT NULL AND password_ciphertext IS NOT NULL AND question_count IS NULL
            AND total_marks IS NOT NULL AND total_marks <= 10000 AND question_paper_media_id IS NOT NULL
            AND NOT shuffle_questions AND NOT shuffle_answers
        )
    ),
    -- total_marks / cutoff_marks stay open for PHYSICAL: D8 has staff enter them later.
    ADD CONSTRAINT ck_exam_v2_physical_shape CHECK (
        config_version = 1 OR exam_type <> 'PHYSICAL' OR (
            password_ciphertext IS NULL AND question_count IS NULL
            AND question_paper_media_id IS NULL AND marking_scheme_media_id IS NULL
            AND NOT shuffle_questions AND NOT shuffle_answers AND NOT auto_enroll_on_payment
            AND NOT show_answers_with_results
        )
    );

-- Directory read: a tenant's live Klass exams (optionally per course), grouped by window.
CREATE INDEX idx_exam_tenant_v2_live_course_window
    ON exam (tenant_id, course_id, scheduled_start, scheduled_end)
    WHERE config_version = 2 AND deleted_at IS NULL;

-- 3. exam_module_quota: how many questions an MCQ exam draws from each selected module.
--    Both FKs carry course_id, so the module must belong to the exam's own course in the
--    same tenant (schema-enforced, not only service-checked). RESTRICT everywhere: a module
--    used by an exam's quota cannot be deleted (exam configuration history wins).

CREATE TABLE exam_module_quota (
    id              UUID PRIMARY KEY,
    tenant_id       UUID NOT NULL REFERENCES tenant (id),
    exam_id         UUID NOT NULL,
    course_id       UUID NOT NULL,
    module_id       UUID NOT NULL,
    question_count  INTEGER NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL,
    created_by      UUID,
    updated_by      UUID,

    CONSTRAINT uq_exam_module_quota_tenant_id UNIQUE (tenant_id, id),
    -- No duplicate module per exam; also the exam's quota read index (tenant-leading).
    CONSTRAINT uq_exam_module_quota_tenant_exam_module UNIQUE (tenant_id, exam_id, module_id),

    CONSTRAINT fk_exam_module_quota_exam FOREIGN KEY (tenant_id, course_id, exam_id)
        REFERENCES exam (tenant_id, course_id, id),
    CONSTRAINT fk_exam_module_quota_module FOREIGN KEY (tenant_id, course_id, module_id)
        REFERENCES course_module (tenant_id, course_id, id),

    CONSTRAINT ck_exam_module_quota_question_count CHECK (question_count >= 0)
);

-- Reverse lookup ("which exams draw from this module") - backs the RESTRICT FK on module
-- delete and future per-module reporting.
CREATE INDEX idx_exam_module_quota_tenant_module
    ON exam_module_quota (tenant_id, module_id);
