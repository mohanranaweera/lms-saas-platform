-- attendance-management (Wave 8, PAR-10-01/02/04): introduces `attendance_sheet`
-- as the single parent of every `attendance_record`, making `class_session`
-- (V49) the teaching-session context for attendance while preserving every
-- historical, lesson-scoped attendance row (docs/parity/waves/wave-08-plan.md
-- §1.2/§3).
--
-- Structure: class_session -> attendance_sheet -> attendance_record.
--   - source = 'CLASS_SESSION': exactly one sheet per class_session (the new
--     workflow; created lazily by AttendanceSheetService on first mark).
--   - source = 'LEGACY_LESSON': exactly one sheet per course_lesson that
--     already carried attendance under V25's lesson-as-session model -
--     backfilled below. No class_session row is fabricated for legacy data
--     (migration-strategy.md §3 "Path B" is explicitly rejected: it would
--     invent scheduling facts that never existed).
--
-- V1-V55 are NOT edited - migration history is append-only (root CLAUDE.md,
-- .claude/rules/tenancy.md). This file is additive DDL plus one bounded
-- backfill that ONLY sets the new `attendance_record.sheet_id` column; no
-- existing attendance row's status/marked_by/marked_at/course/lesson/student
-- is changed and no row is deleted.
--
-- Tenant isolation: `attendance_sheet.tenant_id` is NOT NULL + FK to tenant;
-- every cross-table reference is a composite (tenant_id, ...) FK; every index
-- leads with tenant_id.
--
-- Course consistency is DB-enforced end to end (it was a service-only
-- invariant under V25):
--   class_session (tenant_id, id, course_id)
--     <- attendance_sheet (tenant_id, class_session_id, course_id)
--   attendance_sheet (tenant_id, id, course_id)
--     <- attendance_record (tenant_id, sheet_id, course_id)
-- so a record can never be stored under a course different from its sheet's,
-- and a CLASS_SESSION sheet can never disagree with its session's course.
-- `uq_class_session_tenant_id_course` below is a superset of V49's existing
-- `uq_class_session_tenant_id`, so it holds for every existing row by
-- construction.
--
-- Legacy sheet ids are deterministic - md5(tenant_id || lesson_id ||
-- 'legacy-sheet')::uuid - rather than random: V1's convention forbids a
-- DB-side random-UUID function (ids are otherwise UUIDv7, application-side)
-- and there is no Java-migration precedent in this repo. Deterministic ids
-- also make the backfill reproducible across environments.

-- ---------------------------------------------------------------------------
-- 0. Pre-check: legacy course_id must agree with the lesson's real course.
-- ---------------------------------------------------------------------------
-- V25's `course_id` was a service-derived denormalization with no DB-level
-- guarantee. The composite FK added in step 4 would reject a mismatched row
-- anyway; this check fails first with an explicit, actionable message
-- instead of silently choosing one course.
DO $$
DECLARE
    mismatch_count BIGINT;
BEGIN
    SELECT count(*) INTO mismatch_count
    FROM attendance_record ar
    JOIN course_lesson cl ON cl.tenant_id = ar.tenant_id AND cl.id = ar.session_id
    JOIN course_module cm ON cm.tenant_id = cl.tenant_id AND cm.id = cl.module_id
    WHERE cm.course_id <> ar.course_id;

    IF mismatch_count > 0 THEN
        RAISE EXCEPTION 'V56 aborted: % attendance_record row(s) have a course_id that does not match their lesson''s course. Resolve manually before migrating.', mismatch_count;
    END IF;
END $$;

-- ---------------------------------------------------------------------------
-- 1. class_session: composite unique enabling the course-consistent FK.
-- ---------------------------------------------------------------------------
ALTER TABLE class_session
    ADD CONSTRAINT uq_class_session_tenant_id_course UNIQUE (tenant_id, id, course_id);

-- ---------------------------------------------------------------------------
-- 2. attendance_sheet (com.lms.attendancemanagement.domain.AttendanceSheet)
-- ---------------------------------------------------------------------------
CREATE TABLE attendance_sheet (
    id                UUID PRIMARY KEY,
    tenant_id         UUID NOT NULL REFERENCES tenant (id),
    course_id         UUID NOT NULL,
    source            VARCHAR(20) NOT NULL,
    class_session_id  UUID,
    lesson_id         UUID,
    created_at        TIMESTAMPTZ NOT NULL,
    updated_at        TIMESTAMPTZ NOT NULL,
    created_by        UUID,
    updated_by        UUID,

    CONSTRAINT uq_attendance_sheet_tenant_id
        UNIQUE (tenant_id, id),
    -- Target of attendance_record's course-consistent composite FK.
    CONSTRAINT uq_attendance_sheet_tenant_id_course
        UNIQUE (tenant_id, id, course_id),

    CONSTRAINT fk_attendance_sheet_course
        FOREIGN KEY (tenant_id, course_id) REFERENCES course (tenant_id, id),
    CONSTRAINT fk_attendance_sheet_class_session
        FOREIGN KEY (tenant_id, class_session_id, course_id)
        REFERENCES class_session (tenant_id, id, course_id),
    CONSTRAINT fk_attendance_sheet_lesson
        FOREIGN KEY (tenant_id, lesson_id) REFERENCES course_lesson (tenant_id, id),

    CONSTRAINT ck_attendance_sheet_source
        CHECK (source IN ('CLASS_SESSION', 'LEGACY_LESSON')),
    CONSTRAINT ck_attendance_sheet_source_shape
        CHECK ((source = 'CLASS_SESSION' AND class_session_id IS NOT NULL AND lesson_id IS NULL)
            OR (source = 'LEGACY_LESSON' AND class_session_id IS NULL AND lesson_id IS NOT NULL))
);

-- At most one sheet per class session within a tenant (the duplicate-sheet
-- gate; AttendanceSheetRepository's INSERT ... ON CONFLICT DO NOTHING targets it).
CREATE UNIQUE INDEX uq_attendance_sheet_tenant_class_session
    ON attendance_sheet (tenant_id, class_session_id) WHERE class_session_id IS NOT NULL;

-- At most one legacy sheet per lesson within a tenant.
CREATE UNIQUE INDEX uq_attendance_sheet_tenant_legacy_lesson
    ON attendance_sheet (tenant_id, lesson_id) WHERE source = 'LEGACY_LESSON';

-- Per-course sheet listing.
CREATE INDEX idx_attendance_sheet_tenant_course_created_at
    ON attendance_sheet (tenant_id, course_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- 3. Backfill: one LEGACY_LESSON sheet per legacy (tenant, lesson).
-- ---------------------------------------------------------------------------
INSERT INTO attendance_sheet
    (id, tenant_id, course_id, source, class_session_id, lesson_id,
     created_at, updated_at, created_by, updated_by)
SELECT DISTINCT ON (ar.tenant_id, ar.session_id)
    md5(ar.tenant_id::text || ar.session_id::text || 'legacy-sheet')::uuid,
    ar.tenant_id,
    ar.course_id,
    'LEGACY_LESSON',
    NULL,
    ar.session_id,
    ar.created_at,
    ar.created_at,
    ar.created_by,
    ar.created_by
FROM attendance_record ar
ORDER BY ar.tenant_id, ar.session_id, ar.created_at ASC;

-- ---------------------------------------------------------------------------
-- 4. attendance_record: attach every row to a sheet.
-- ---------------------------------------------------------------------------
ALTER TABLE attendance_record ADD COLUMN sheet_id UUID;

UPDATE attendance_record ar
SET sheet_id = s.id
FROM attendance_sheet s
WHERE s.tenant_id = ar.tenant_id
  AND s.source = 'LEGACY_LESSON'
  AND s.lesson_id = ar.session_id;

ALTER TABLE attendance_record ALTER COLUMN sheet_id SET NOT NULL;

-- A CLASS_SESSION-sourced record has no lesson. The existing composite FK
-- into course_lesson still applies whenever the column is set.
ALTER TABLE attendance_record ALTER COLUMN session_id DROP NOT NULL;

ALTER TABLE attendance_record
    ADD CONSTRAINT fk_attendance_record_sheet
        FOREIGN KEY (tenant_id, sheet_id, course_id)
        REFERENCES attendance_sheet (tenant_id, id, course_id);

-- At most one record per (sheet, student) within a tenant - the duplicate
-- gate and ON CONFLICT target for class-session marking. V25's
-- uq_attendance_record_tenant_session_student stays in place for the legacy
-- path (NULL session_id values never collide).
ALTER TABLE attendance_record
    ADD CONSTRAINT uq_attendance_record_tenant_sheet_student
        UNIQUE (tenant_id, sheet_id, student_id);

COMMENT ON COLUMN attendance_record.session_id IS
    'Legacy lesson id (course_lesson.id) for LEGACY_LESSON-sheet rows (V25 lesson-as-session model); NULL for CLASS_SESSION-sheet rows. Resolve the teaching session via sheet_id -> attendance_sheet.class_session_id.';
COMMENT ON TABLE attendance_sheet IS
    'Parent of attendance_record. CLASS_SESSION: one per class_session. LEGACY_LESSON: one per course_lesson with pre-Wave-8 attendance (backfilled by V56).';
