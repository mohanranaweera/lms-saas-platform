-- exam-management (Module 11 / MVP-017, GitHub issue #17): `exam_question`,
-- `exam_question_option`, `exam`, `exam_question_link`, `exam_attempt`,
-- `exam_answer`.
--
-- Owned by com.lms.exammanagement.domain, per .claude/rules/architecture.md's
-- confirmed domain list. Every table is tenant-owned: `tenant_id NOT NULL
-- REFERENCES tenant(id)`, a composite index leading with `tenant_id`, and every
-- cross-table reference to another tenant-owned table is a composite
-- `(tenant_id, ...)` FK - never a bare FK on the child id alone. `id` has no
-- DB-side DEFAULT anywhere - generated application-side (UUIDv7), per V1's
-- baseline convention. `created_by`/`updated_by` are the generic Auditable
-- columns (bare nullable UUID, no FK), matching course/course_module/
-- attendance_record's exact shape. `marked_by`/`marked_at` on exam_answer are
-- explicit domain columns (schema-enforced FK + NOT-NULL-together CHECK),
-- mirroring attendance_record.marked_by's (V25) reasoning.

CREATE TABLE exam_question (
    id             UUID PRIMARY KEY,
    tenant_id      UUID NOT NULL REFERENCES tenant (id),
    course_id      UUID NOT NULL,
    question_type  VARCHAR(20) NOT NULL,
    body           TEXT NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL,
    created_by     UUID,
    updated_by     UUID,

    CONSTRAINT uq_exam_question_tenant_id UNIQUE (tenant_id, id),

    -- No CASCADE: a course's question bank is reusable content with ongoing
    -- value beyond any single exam.
    CONSTRAINT fk_exam_question_course FOREIGN KEY (tenant_id, course_id)
        REFERENCES course (tenant_id, id),

    CONSTRAINT ck_exam_question_type CHECK (question_type IN ('MCQ', 'STRUCTURED')),
    CONSTRAINT ck_exam_question_body_not_blank CHECK (btrim(body) <> '')
);

-- Question-bank browsing read pattern: all questions for one course.
CREATE INDEX idx_exam_question_tenant_course
    ON exam_question (tenant_id, course_id);

CREATE TABLE exam_question_option (
    id            UUID PRIMARY KEY,
    tenant_id     UUID NOT NULL REFERENCES tenant (id),
    question_id   UUID NOT NULL,
    option_text   TEXT NOT NULL,
    is_correct    BOOLEAN NOT NULL DEFAULT false,
    created_at    TIMESTAMPTZ NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL,
    created_by    UUID,
    updated_by    UUID,

    -- CASCADE (deliberate deviation from the default RESTRICT posture): an
    -- option has no independent value once its parent question is gone - a
    -- pure structural child, mirroring V14's course_module/course_lesson
    -- CASCADE reasoning. Deleting exam_question itself is still blocked once
    -- linked into an exam (fk_exam_question_link_question) or answered
    -- (fk_exam_answer_question) - this CASCADE only fires for a never-used
    -- question's own options.
    CONSTRAINT fk_exam_question_option_question FOREIGN KEY (tenant_id, question_id)
        REFERENCES exam_question (tenant_id, id) ON DELETE CASCADE,

    CONSTRAINT ck_exam_question_option_text_not_blank CHECK (btrim(option_text) <> '')
);

CREATE INDEX idx_exam_question_option_tenant_question
    ON exam_question_option (tenant_id, question_id);

CREATE TABLE exam (
    id                    UUID PRIMARY KEY,
    tenant_id             UUID NOT NULL REFERENCES tenant (id),
    course_id             UUID NOT NULL,
    title                 VARCHAR(255) NOT NULL,
    scheduled_start       TIMESTAMPTZ NOT NULL,
    scheduled_end         TIMESTAMPTZ NOT NULL,
    time_limit_minutes    INTEGER NOT NULL,
    status                VARCHAR(10) NOT NULL,
    -- Nullable gate, fully independent of `status` above (see plan §7's boxed
    -- note): NULL means results are not yet visible to students. Set once, by
    -- an authorized APPROVE-gated action, only once status = 'CLOSED'
    -- (service-layer precondition, plan §12 - not a DB CHECK, since encoding
    -- it here would need a cross-column CHECK re-evaluated on every status
    -- update, and the service is the single non-bypassable write path anyway).
    results_published_at  TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL,
    updated_at            TIMESTAMPTZ NOT NULL,
    created_by            UUID,
    updated_by            UUID,

    CONSTRAINT uq_exam_tenant_id UNIQUE (tenant_id, id),

    -- No CASCADE: an exam that has ever been attempted must outlive a course
    -- delete attempt (attempts/answers are academic history); in practice a
    -- course delete is already blocked transitively once any exam_attempt
    -- exists.
    CONSTRAINT fk_exam_course FOREIGN KEY (tenant_id, course_id)
        REFERENCES course (tenant_id, id),

    CONSTRAINT ck_exam_title_not_blank CHECK (btrim(title) <> ''),
    CONSTRAINT ck_exam_status CHECK (status IN ('DRAFT', 'SCHEDULED', 'PUBLISHED', 'CLOSED')),
    CONSTRAINT ck_exam_schedule_window CHECK (scheduled_end > scheduled_start),
    CONSTRAINT ck_exam_time_limit_minutes_positive CHECK (time_limit_minutes > 0)
);

-- Teacher/exam-manager's exam-list-by-course read, chronological.
CREATE INDEX idx_exam_tenant_course_scheduled_start
    ON exam (tenant_id, course_id, scheduled_start DESC);

-- Status-filtered, time-ordered listing (admin/ops dashboard), mirroring
-- course.idx_course_tenant_status_created_at's exact shape (V11).
CREATE INDEX idx_exam_tenant_status_scheduled_start
    ON exam (tenant_id, status, scheduled_start DESC);

-- Results-read gate: the exam-side half of the (tenant_id, student_id) x
-- results_published_at IS NOT NULL intersection the issue calls out
-- explicitly.
CREATE INDEX idx_exam_tenant_results_published
    ON exam (tenant_id, id)
    WHERE results_published_at IS NOT NULL;

CREATE TABLE exam_question_link (
    id          UUID PRIMARY KEY,
    tenant_id   UUID NOT NULL REFERENCES tenant (id),
    exam_id     UUID NOT NULL,
    question_id UUID NOT NULL,
    sequence    INTEGER NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL,
    created_by  UUID,
    updated_by  UUID,

    CONSTRAINT uq_exam_question_link_tenant_exam_sequence
        UNIQUE (tenant_id, exam_id, sequence),
    CONSTRAINT uq_exam_question_link_tenant_exam_question
        UNIQUE (tenant_id, exam_id, question_id),

    -- CASCADE: a purely structural bridge row, no independent value once its
    -- parent exam is gone (mirrors V14). In practice this can only fire for
    -- an exam with no attempts yet.
    CONSTRAINT fk_exam_question_link_exam FOREIGN KEY (tenant_id, exam_id)
        REFERENCES exam (tenant_id, id) ON DELETE CASCADE,
    -- No CASCADE: a question already scheduled into an exam must not be
    -- deletable out from under that exam.
    CONSTRAINT fk_exam_question_link_question FOREIGN KEY (tenant_id, question_id)
        REFERENCES exam_question (tenant_id, id),

    CONSTRAINT ck_exam_question_link_sequence_positive CHECK (sequence > 0)
);

-- Exam-taking / exam-authoring ordered question list for one exam.
CREATE INDEX idx_exam_question_link_tenant_exam_sequence
    ON exam_question_link (tenant_id, exam_id, sequence);

-- Reverse lookup: "which exams use this question" (explains delete-blocked).
CREATE INDEX idx_exam_question_link_tenant_question
    ON exam_question_link (tenant_id, question_id);

CREATE TABLE exam_attempt (
    id            UUID PRIMARY KEY,
    tenant_id     UUID NOT NULL REFERENCES tenant (id),
    exam_id       UUID NOT NULL,
    student_id    UUID NOT NULL,
    started_at    TIMESTAMPTZ NOT NULL,
    submitted_at  TIMESTAMPTZ,
    status        VARCHAR(20) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL,
    created_by    UUID,
    updated_by    UUID,

    CONSTRAINT uq_exam_attempt_tenant_id UNIQUE (tenant_id, id),

    -- No CASCADE: an attempt is academic/exam history, must outlive the
    -- exam's own lifecycle edits (and blocks exam deletion once any attempt
    -- exists).
    CONSTRAINT fk_exam_attempt_exam FOREIGN KEY (tenant_id, exam_id)
        REFERENCES exam (tenant_id, id),
    CONSTRAINT fk_exam_attempt_student FOREIGN KEY (tenant_id, student_id)
        REFERENCES tenant_user (tenant_id, id),

    -- Status values proposed by this plan (the issue names only the column,
    -- no values): IN_PROGRESS (started, not yet submitted), SUBMITTED
    -- (student submitted within the time limit/window), EXPIRED (window/time
    -- limit elapsed before submission - see plan §12 for who/what writes
    -- this transition).
    CONSTRAINT ck_exam_attempt_status CHECK (status IN ('IN_PROGRESS', 'SUBMITTED', 'EXPIRED')),
    CONSTRAINT ck_exam_attempt_submitted_requires_timestamp CHECK (
        status = 'IN_PROGRESS' OR submitted_at IS NOT NULL
    ),
    CONSTRAINT ck_exam_attempt_submitted_after_started CHECK (
        submitted_at IS NULL OR submitted_at >= started_at
    )
);

-- Student's own attempts/results read pattern, most recent first - also the
-- student-scoped half of the results-read intersection.
CREATE INDEX idx_exam_attempt_tenant_student_started_at
    ON exam_attempt (tenant_id, student_id, started_at DESC);

-- Teacher/exam-manager's "all attempts for this exam" read pattern.
CREATE INDEX idx_exam_attempt_tenant_exam
    ON exam_attempt (tenant_id, exam_id);

-- At most one concurrently IN_PROGRESS attempt per (tenant, exam, student).
-- Does NOT block a second SEQUENTIAL attempt once the first is
-- SUBMITTED/EXPIRED - attempt-count limiting is explicitly Phase 2 (see
-- plan §6/§21 item 3), so this is scoped narrowly to a data-integrity
-- concern (no two simultaneously live sessions), not a business
-- attempt-limit policy.
CREATE UNIQUE INDEX uq_exam_attempt_tenant_exam_student_in_progress
    ON exam_attempt (tenant_id, exam_id, student_id)
    WHERE status = 'IN_PROGRESS';

CREATE TABLE exam_answer (
    id            UUID PRIMARY KEY,
    tenant_id     UUID NOT NULL REFERENCES tenant (id),
    attempt_id    UUID NOT NULL,
    question_id   UUID NOT NULL,
    -- Denormalized from attempt_id -> exam_attempt.exam_id, following
    -- attendance_record.course_id's (V25) exact precedent: a documented,
    -- accepted denormalization, not a schema-enforced invariant. Needed
    -- because the issue explicitly requires a marking-queue read filtered to
    -- (tenant_id, exam_id). ExamAnswerService MUST derive exam_id
    -- server-side from the attempt's real parent exam, never trust a
    -- client-supplied examId. A negative test proving an attempt from exam A
    -- cannot be recorded under exam_id B is required (plan §18).
    exam_id       UUID NOT NULL,
    response      TEXT,
    auto_score    NUMERIC(6,2),
    manual_score  NUMERIC(6,2),
    marked_by     UUID,
    marked_at     TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL,
    created_by    UUID,
    updated_by    UUID,

    CONSTRAINT uq_exam_answer_tenant_attempt_question
        UNIQUE (tenant_id, attempt_id, question_id),

    -- No CASCADE: a submitted answer is exam/academic history.
    CONSTRAINT fk_exam_answer_attempt FOREIGN KEY (tenant_id, attempt_id)
        REFERENCES exam_attempt (tenant_id, id),
    CONSTRAINT fk_exam_answer_question FOREIGN KEY (tenant_id, question_id)
        REFERENCES exam_question (tenant_id, id),
    CONSTRAINT fk_exam_answer_exam FOREIGN KEY (tenant_id, exam_id)
        REFERENCES exam (tenant_id, id),
    -- Nullable until manually marked (STRUCTURED questions only).
    CONSTRAINT fk_exam_answer_marked_by FOREIGN KEY (tenant_id, marked_by)
        REFERENCES tenant_user (tenant_id, id),

    CONSTRAINT ck_exam_answer_auto_score_nonnegative CHECK (auto_score IS NULL OR auto_score >= 0),
    CONSTRAINT ck_exam_answer_manual_score_nonnegative CHECK (manual_score IS NULL OR manual_score >= 0),
    -- Mirrors payment_slip.ck_payment_slip_reviewed_requires_reviewer (V21)
    -- and reactivation_request's analogous CHECK (V22): a manual mark must
    -- carry who/when together, never a bare score with no accountable marker.
    CONSTRAINT ck_exam_answer_marked_fields_together CHECK (
        (manual_score IS NULL AND marked_by IS NULL AND marked_at IS NULL) OR
        (manual_score IS NOT NULL AND marked_by IS NOT NULL AND marked_at IS NOT NULL)
    )
);

-- Attempt-detail / student-review read pattern: all answers for one attempt.
CREATE INDEX idx_exam_answer_tenant_attempt
    ON exam_answer (tenant_id, attempt_id);

-- Marking-queue read, filtered to (tenant_id, exam_id) per the issue's
-- explicit requirement. "Pending only" (STRUCTURED + manual_score IS NULL)
-- is a query-time filter joined against exam_question.question_type.
CREATE INDEX idx_exam_answer_tenant_exam
    ON exam_answer (tenant_id, exam_id);

-- Backs ExamAnswerRepository#existsByQuestionId (QuestionBankService's
-- "has this question ever been answered" freeze-check) - previously
-- unindexed beyond the bare tenant_id filter.
CREATE INDEX idx_exam_answer_tenant_question
    ON exam_answer (tenant_id, question_id);
