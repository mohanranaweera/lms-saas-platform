-- live-class-management (Wave 4, PAR-19-01..05): creates `class_session`,
-- `class_session_recording`, and `class_session_provider_event` - a brand
-- new, INDEPENDENT domain, decoupled from `attendance_record` (V25).
--
-- Path A (docs/parity/migration-strategy.md §3, docs/parity/waves/wave-04-plan.md
-- §1/§3/§9): `attendance_record.session_id`'s existing composite FK into
-- `course_lesson (tenant_id, id)` (V25, MVP-016, product-owner-confirmed) is
-- NOT touched, repointed, or backfilled by this migration. `class_session`
-- below is a wholly new, independent aggregate - a `ClassSession` may
-- optionally reference the `course_lesson` it delivers (nullable `lesson_id`)
-- purely so a Teacher can still mark attendance through the existing,
-- unchanged `course_lesson`-scoped attendance flow, but no FK/backfill
-- relationship to `attendance_record` exists or is created here. V1-V48 are
-- NOT edited by this migration - they are already shared/applied and this
-- repo's migration history is append-only (root CLAUDE.md, .claude/rules
-- /tenancy.md). This is a new, additive `CREATE TABLE`/`CREATE INDEX` file
-- only.
--
-- Ownership (per .claude/rules/architecture.md): `class_session`/
-- `class_session_recording` -> com.lms.liveclassmanagement.domain;
-- `class_session_provider_event` -> com.lms.integrationmanagement (the
-- webhook idempotency ledger for the meeting-provider webhook that
-- `integrationmanagement.web.LiveClassWebhookController` receives - it is
-- NOT a live-class-management business entity, it is integration-management's
-- own record of "which provider events have we already processed").
--
-- Tenant isolation: `class_session`/`class_session_recording` both carry
-- `tenant_id UUID NOT NULL REFERENCES tenant (id)`, never nullable, with
-- tenant-leading indexes matching this module's real read patterns (course
-- schedule view, teacher's own schedule, upcoming/live dashboard, per-session
-- recording lookup). Every cross-table reference to another tenant-owned
-- table (`course_id`, `lesson_id`, `teacher_id`, `session_id`) is a composite
-- `(tenant_id, ...)` FK into that table - never a bare FK on the child id
-- alone - per .claude/rules/tenancy.md, so a row can never point at another
-- tenant's course/lesson/teacher/session even if the bare child id happens to
-- exist there. This is possible with no prerequisite migration because the
-- referenced tables already carry the matching composite unique constraint:
--   - `course (tenant_id, id)` -> `uq_course_tenant_id` (V11).
--   - `course_lesson (tenant_id, id)` -> `uq_course_lesson_tenant_id` (V15).
--   - `tenant_user (tenant_id, id)` -> `uq_tenant_user_tenant_id` (V3).
--
-- `class_session_provider_event` is the sole justified exception to "never
-- nullable tenant_id" (plan §3/§7): its `tenant_id` is nullable ONLY because a
-- malformed/unresolvable provider event genuinely has no tenant at insert
-- time (tenant resolution happens AFTER looking up the referenced
-- `class_session` by `provider_reference` - never trusted from the webhook
-- payload itself, per .claude/rules/security.md's webhook-trust posture).
-- This is never a bypass of tenant filtering on any READ path - it is a
-- platform-level idempotency ledger, not a tenant-owned business table, and
-- its own repository is a plain (non-tenant-scoped) JpaRepository, never
-- `TenantAwareRepository`.
--
-- `id` has no DB-side DEFAULT on any of these three tables - generated
-- application-side (UUIDv7 via com.lms.common.persistence.UuidV7Generator),
-- per V1's baseline convention, matching every other table in this schema.
--
-- No `ON DELETE CASCADE` on any `class_session`/`class_session_recording` FK
-- - live-class scheduling/recording history must outlive its parent
-- course/lesson/teacher, mirroring V25's identical "academic/scheduling
-- history outlives its parent" precedent; deleting a course/lesson/teacher
-- that already has session history is BLOCKED rather than silently cascaded.
--
-- `provider_reference`/`provider_recording_reference` are OPAQUE ids only -
-- never a join URL or playback URL (.claude/rules/security.md's "Video &
-- Session Protection": every join/playback link is minted fresh, short-lived,
-- server-side, per request via `integrationmanagement.api.LiveClassProviderApi`
-- - never persisted as a stable/reusable URL anywhere in this schema).
--
-- `uq_class_session_provider_reference` (partial unique index, only enforced
-- when the column is set) guarantees a provider's meeting reference resolves
-- to AT MOST ONE `class_session` row platform-wide - the exact invariant
-- `integrationmanagement.web.LiveClassWebhookController`/
-- `liveclassmanagement.service.LiveClassWebhookProcessingService` depends on
-- to resolve tenant identity for a webhook-driven update solely from the
-- platform's own data, never from the payload.
--
-- `uq_class_session_provider_event` (`provider`, `provider_event_id`) is the
-- webhook idempotency gate - a duplicate delivery of the same provider event
-- id hits this unique-constraint violation, which
-- `LiveClassWebhookController` catches and turns into an idempotent 200 OK
-- no-op (never a 500, never a second state mutation).

-- ---------------------------------------------------------------------------
-- class_session (com.lms.liveclassmanagement.domain.ClassSession)
-- ---------------------------------------------------------------------------

CREATE TABLE class_session (
    id                        UUID PRIMARY KEY,
    tenant_id                 UUID NOT NULL REFERENCES tenant (id),
    course_id                 UUID NOT NULL,
    teacher_id                UUID NOT NULL,
    lesson_id                 UUID,
    title                     VARCHAR(255) NOT NULL,
    description               VARCHAR(5000),
    scheduled_start           TIMESTAMPTZ NOT NULL,
    scheduled_end             TIMESTAMPTZ NOT NULL,
    status                    VARCHAR(10) NOT NULL,
    meeting_provider          VARCHAR(10) NOT NULL,
    provider_status           VARCHAR(12) NOT NULL,
    provider_reference        VARCHAR(255),
    provider_failure_reason   VARCHAR(1000),
    created_at                TIMESTAMPTZ NOT NULL,
    updated_at                TIMESTAMPTZ NOT NULL,
    created_by                UUID,
    updated_by                UUID,

    -- Enables composite (tenant_id, id) FKs FROM other tables INTO this one
    -- (class_session_recording below) - mirrors uq_course_tenant_id (V11)/
    -- uq_course_lesson_tenant_id (V15)/uq_tenant_user_tenant_id (V3)'s
    -- identical precedent for the exact same reason.
    CONSTRAINT uq_class_session_tenant_id
        UNIQUE (tenant_id, id),

    CONSTRAINT fk_class_session_course
        FOREIGN KEY (tenant_id, course_id) REFERENCES course (tenant_id, id),
    CONSTRAINT fk_class_session_lesson
        FOREIGN KEY (tenant_id, lesson_id) REFERENCES course_lesson (tenant_id, id),
    CONSTRAINT fk_class_session_teacher
        FOREIGN KEY (tenant_id, teacher_id) REFERENCES tenant_user (tenant_id, id),

    CONSTRAINT ck_class_session_status
        CHECK (status IN ('SCHEDULED', 'LIVE', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT ck_class_session_meeting_provider
        CHECK (meeting_provider IN ('ZOOM')),
    CONSTRAINT ck_class_session_provider_status
        CHECK (provider_status IN ('PENDING', 'PROVISIONED', 'FAILED')),
    CONSTRAINT ck_class_session_scheduled_window
        CHECK (scheduled_end > scheduled_start)
);

-- Course schedule view (Teacher/staff browsing one course's sessions).
CREATE INDEX idx_class_session_tenant_course_start
    ON class_session (tenant_id, course_id, scheduled_start);

-- Teacher's own schedule across all their courses.
CREATE INDEX idx_class_session_tenant_teacher_start
    ON class_session (tenant_id, teacher_id, scheduled_start);

-- Upcoming/live dashboard queries (tenant-wide, status-filtered).
CREATE INDEX idx_class_session_tenant_status_start
    ON class_session (tenant_id, status, scheduled_start);

-- Webhook resolution path: a provider reference must resolve to at most one
-- session platform-wide (see migration header). Partial so PENDING/never
-- -provisioned rows (provider_reference IS NULL) never collide.
CREATE UNIQUE INDEX uq_class_session_provider_reference
    ON class_session (provider_reference) WHERE provider_reference IS NOT NULL;

-- ---------------------------------------------------------------------------
-- class_session_recording (com.lms.liveclassmanagement.domain.ClassSessionRecording)
-- ---------------------------------------------------------------------------

CREATE TABLE class_session_recording (
    id                            UUID PRIMARY KEY,
    tenant_id                     UUID NOT NULL REFERENCES tenant (id),
    session_id                    UUID NOT NULL,
    status                        VARCHAR(10) NOT NULL,
    provider_recording_reference  VARCHAR(255),
    duration_seconds              INTEGER,
    created_at                    TIMESTAMPTZ NOT NULL,
    updated_at                    TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_class_session_recording_session
        FOREIGN KEY (tenant_id, session_id) REFERENCES class_session (tenant_id, id),

    CONSTRAINT ck_class_session_recording_status
        CHECK (status IN ('PENDING', 'AVAILABLE', 'FAILED')),
    CONSTRAINT ck_class_session_recording_duration_seconds
        CHECK (duration_seconds IS NULL OR duration_seconds >= 0),

    -- At most one recording row per session (this wave has no
    -- multi-recording-per-session concept, per plan §11's Phase-3 deferral of
    -- auto-recurring meetings / multi-recording import) - a re-delivered
    -- `recording.completed` webhook event updates this row in place rather
    -- than inserting a second one.
    CONSTRAINT uq_class_session_recording_tenant_session
        UNIQUE (tenant_id, session_id)
);

-- ---------------------------------------------------------------------------
-- class_session_provider_event (com.lms.integrationmanagement.domain.ClassSessionProviderEvent)
-- ---------------------------------------------------------------------------

CREATE TABLE class_session_provider_event (
    id                  UUID PRIMARY KEY,
    tenant_id           UUID REFERENCES tenant (id),
    provider            VARCHAR(10) NOT NULL,
    provider_event_id   VARCHAR(255) NOT NULL,
    event_type          VARCHAR(50) NOT NULL,
    received_at         TIMESTAMPTZ NOT NULL,
    processed           BOOLEAN NOT NULL DEFAULT false,

    -- The webhook idempotency gate (see migration header) - a provider's own
    -- event id is unique per provider regardless of tenant, since tenant
    -- resolution happens only AFTER looking up the referenced meeting.
    CONSTRAINT uq_class_session_provider_event
        UNIQUE (provider, provider_event_id)
);

-- Tenant-scoped audit/support read path, once one exists (e.g. a future
-- Tenant Admin "webhook delivery history" view) - not required by any code
-- path this wave, added proactively per .claude/rules/backend.md's "index
-- for the tenant-scoped query shape the module actually uses" guidance so a
-- later tenant-scoped read doesn't start from an unindexed table.
CREATE INDEX idx_class_session_provider_event_tenant_received_at
    ON class_session_provider_event (tenant_id, received_at DESC);
