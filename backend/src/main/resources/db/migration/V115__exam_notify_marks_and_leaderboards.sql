-- exam-management (Klass Exam Module, Slice E - docs/api/exam-management-v2.md "Slice E";
-- spec docs/specs/klass-exam-module.md D2 / D7): Notify Marks dispatch log and exam leaderboards.
-- notification-management: the EXAM_MARKS_PUBLISHED outbox event type.
--
-- Purely additive. No earlier migration is edited. Results publication itself reuses
-- exam.results_published_at (V26); MCQ scores stay on exam_attempt (V113) and written /
-- physical marks on exam_result (V114).
--
-- Tenant-owned: every new table has tenant_id NOT NULL FK, tenant-leading indexes and
-- composite same-tenant FKs.

-- 1. notification_outbox.event_type gains EXAM_MARKS_PUBLISHED (one row per notified student;
--    the payload carries exam name, marks / total and pass state - never answer keys). The
--    default template is seeded by the application (NotificationTemplateSeedingService), like
--    every other notification template.

ALTER TABLE notification_outbox DROP CONSTRAINT ck_notification_outbox_event_type;
ALTER TABLE notification_outbox
    ADD CONSTRAINT ck_notification_outbox_event_type CHECK (event_type IN (
        'PAYMENT_CONFIRMED', 'PAYMENT_REJECTED', 'PAYMENT_REFUNDED',
        'ACCOUNT_SETUP_INVITE', 'PASSWORD_RESET', 'EXAM_MARKS_PUBLISHED'));

-- 2. exam_notification_dispatch: one row per accepted "Notify Marks" send of an exam. The
--    Idempotency-Key is unique per exam: a replay of the same key returns the stored row and
--    enqueues nothing. resend = true marks a deliberate second (or later) send. Append-only:
--    rows are never updated or deleted (trigger below).
--      recipient_count      enrolled students with marks, one outbox row each.
--      without_marks_count  enrolled students without marks at send time (not notified).
--      sent_by / sent_at    the authorized staff member / owning teacher, and when.

CREATE TABLE exam_notification_dispatch (
    id                   UUID PRIMARY KEY,
    tenant_id            UUID NOT NULL REFERENCES tenant (id),
    exam_id              UUID NOT NULL,
    idempotency_key      VARCHAR(100) NOT NULL,
    recipient_count      INTEGER NOT NULL,
    without_marks_count  INTEGER NOT NULL,
    resend               BOOLEAN NOT NULL,
    sent_by              UUID NOT NULL,
    sent_at              TIMESTAMPTZ NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL,
    updated_at           TIMESTAMPTZ NOT NULL,
    created_by           UUID,
    updated_by           UUID,

    CONSTRAINT uq_exam_notification_dispatch_tenant_id UNIQUE (tenant_id, id),
    -- Idempotency (and the exam's dispatch lookup index, tenant-leading).
    CONSTRAINT uq_exam_notification_dispatch_tenant_exam_key UNIQUE (tenant_id, exam_id, idempotency_key),

    CONSTRAINT fk_exam_notification_dispatch_exam FOREIGN KEY (tenant_id, exam_id)
        REFERENCES exam (tenant_id, id),
    CONSTRAINT fk_exam_notification_dispatch_sent_by FOREIGN KEY (tenant_id, sent_by)
        REFERENCES tenant_user (tenant_id, id),

    CONSTRAINT ck_exam_notification_dispatch_key_not_blank CHECK (btrim(idempotency_key) <> ''),
    CONSTRAINT ck_exam_notification_dispatch_recipients CHECK (recipient_count >= 0),
    CONSTRAINT ck_exam_notification_dispatch_without_marks CHECK (without_marks_count >= 0)
);

-- "Already notified at" (latest send of an exam).
CREATE INDEX idx_exam_notification_dispatch_tenant_exam_sent
    ON exam_notification_dispatch (tenant_id, exam_id, sent_at DESC);

CREATE FUNCTION exam_notification_dispatch_guard() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'exam notification dispatches are append-only'
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

CREATE TRIGGER trg_exam_notification_dispatch_guard
    BEFORE UPDATE OR DELETE ON exam_notification_dispatch
    FOR EACH ROW EXECUTE FUNCTION exam_notification_dispatch_guard();

-- 3. exam_leaderboard (D7): a named grouping of exams of one tenant. published = visible to
--    students enrolled in one of its exams (default staff-only). Deleting a leaderboard is a
--    hard delete of the grouping only - no academic data is attached to it.

CREATE TABLE exam_leaderboard (
    id          UUID PRIMARY KEY,
    tenant_id   UUID NOT NULL REFERENCES tenant (id),
    name        VARCHAR(120) NOT NULL,
    published   BOOLEAN NOT NULL DEFAULT false,
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL,
    created_by  UUID,
    updated_by  UUID,

    CONSTRAINT uq_exam_leaderboard_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT ck_exam_leaderboard_name CHECK (btrim(name) <> '')
);

-- Staff list ordered / searched by name.
CREATE INDEX idx_exam_leaderboard_tenant_name
    ON exam_leaderboard (tenant_id, lower(name));

-- 4. exam_leaderboard_exam: the exams of a leaderboard (>= 2 enforced by the service). Rows are
--    removed with their leaderboard; a (hard-)deleted exam drops out of every grouping.

CREATE TABLE exam_leaderboard_exam (
    id              UUID PRIMARY KEY,
    tenant_id       UUID NOT NULL REFERENCES tenant (id),
    leaderboard_id  UUID NOT NULL,
    exam_id         UUID NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL,
    created_by      UUID,
    updated_by      UUID,

    -- One membership per exam and leaderboard; also the leaderboard's read index.
    CONSTRAINT uq_exam_leaderboard_exam_tenant_board_exam UNIQUE (tenant_id, leaderboard_id, exam_id),

    CONSTRAINT fk_exam_leaderboard_exam_leaderboard FOREIGN KEY (tenant_id, leaderboard_id)
        REFERENCES exam_leaderboard (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_exam_leaderboard_exam_exam FOREIGN KEY (tenant_id, exam_id)
        REFERENCES exam (tenant_id, id) ON DELETE CASCADE
);

-- Student visibility ("published leaderboards including one of my exams") / exam reverse lookup.
CREATE INDEX idx_exam_leaderboard_exam_tenant_exam
    ON exam_leaderboard_exam (tenant_id, exam_id);
