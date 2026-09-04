-- attendance-management (MVP-016 Attendance): creates `attendance_record`, the
-- sole table for this new domain at MVP scope - Teacher/staff marking of
-- Present/Absent/Late against a course lesson (the session-equivalent unit at
-- this MVP; no new `class_session` table, per docs/plans/MVP-016
-- Attendance.md §7/§8's boxed note - a product-owner-confirmed decision, not
-- invented here).
--
-- Ownership (per .claude/rules/architecture.md): `attendance_record` ->
-- com.lms.attendancemanagement.domain.
--
-- V1-V24 are NOT edited by this migration - they are already shared/applied
-- and this repo's migration history is append-only (root CLAUDE.md,
-- .claude/rules/tenancy.md). This is a new, additive `CREATE TABLE` /
-- `CREATE INDEX` file only.
--
-- Tenant isolation: `tenant_id UUID NOT NULL REFERENCES tenant (id)`, never
-- nullable. Every cross-table reference to another tenant-owned table
-- (`course_id`, `session_id`, `student_id`, `marked_by`) is a composite
-- `(tenant_id, ...)` FK into that table - never a bare FK on the child id
-- alone - per .claude/rules/tenancy.md, so a row can never point at another
-- tenant's course/lesson/user even if the bare child id happens to exist
-- there. This is possible with no prerequisite migration because the
-- referenced tables already carry the matching composite unique constraint:
--   - `course (tenant_id, id)` -> `uq_course_tenant_id` (V11).
--   - `course_lesson (tenant_id, id)` -> `uq_course_lesson_tenant_id` (V15).
--   - `tenant_user (tenant_id, id)` -> `uq_tenant_user_tenant_id` (V3).
-- Every index below leads with `tenant_id`, shaped to this module's three
-- real read patterns (student-own-history, teacher/staff per-course report,
-- tenant-wide staff report - plan §8/§14).
--
-- `id` has no DB-side DEFAULT - generated application-side (UUIDv7 via
-- com.lms.common.persistence.UuidV7Generator), per V1's baseline convention,
-- matching every other table in this schema.
--
-- No `ON DELETE CASCADE` on any FK here - unlike `course_module`/
-- `course_lesson`'s own structural-only cascade from `course` (V14),
-- attendance is academic history that must outlive its parent course/lesson;
-- default FK behavior means deleting a course/lesson that already has
-- attendance history will be BLOCKED rather than silently cascaded away.
-- Mirrors V22's "no CASCADE on history-like tables" precedent
-- (`enrollment`/`enrollment_expiry_event`/`reactivation_request`).
--
-- `course_id` is a documented, accepted denormalization, not a
-- schema-enforced invariant (plan §8): `course_lesson` (V11) has no
-- `course_id` column of its own - only `module_id`, resolved to a course
-- transitively via `course_module`. Nothing at the DB level can guarantee
-- `session_id`'s real parent course equals the stored `course_id`;
-- `AttendanceMarkingService` MUST derive `course_id` server-side from
-- `CourseLookupApi.resolveLessonOwnership(sessionId).courseId()` and must
-- never trust a client-supplied `courseId` for the persisted row - a
-- negative test proving a `session_id` from course A cannot be recorded
-- under `course_id` B is required (plan §18). The `fk_attendance_record_course`
-- and `fk_attendance_record_session` constraints below each independently
-- guarantee their own referenced row belongs to the same tenant, but neither
-- one, nor both together, can enforce that `session_id`'s real parent course
-- equals the stored `course_id` - that cross-column consistency is a
-- service-layer obligation only.
--
-- `marked_by` is an explicit domain column, not inferred from
-- `Auditable.createdBy`: it is domain-meaningful (who is accountable for
-- this attendance fact, and it must be re-settable on a re-mark by a
-- different staff member), needs its own FK/NOT NULL enforcement independent
-- of what the generic auditing listener populates, and the issue names it
-- explicitly as a required schema column - same reasoning V22 used for
-- keeping `reactivation_request.reviewed_by`/`requested_by` as explicit
-- domain columns alongside (not replaced by) `Auditable`'s generic
-- provenance fields.
--
-- Re-marking the same (session, student) is a service-layer in-place UPDATE
-- of this row's `status`/`marked_by`/`marked_at`, never a second INSERT
-- (product-owner confirmed - plan §7 boxed note) - `attendance_record` is
-- therefore mutable by design, unlike this schema's append-only
-- payment/ledger/audit-log tables (.claude/rules/backend.md); the
-- `uq_attendance_record_tenant_session_student` constraint below is what
-- makes "at most one canonical row per (session, student)" a
-- database-enforced invariant rather than service-layer discipline alone.

-- ---------------------------------------------------------------------------
-- attendance_record (com.lms.attendancemanagement.domain)
-- ---------------------------------------------------------------------------

CREATE TABLE attendance_record (
    id          UUID PRIMARY KEY,
    tenant_id   UUID NOT NULL REFERENCES tenant (id),
    course_id   UUID NOT NULL,
    session_id  UUID NOT NULL,
    student_id  UUID NOT NULL,
    status      VARCHAR(10) NOT NULL,
    marked_by   UUID NOT NULL,
    marked_at   TIMESTAMPTZ NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL,
    created_by  UUID,
    updated_by  UUID,

    -- At most one canonical row per (session, student) within a tenant.
    -- A re-mark is a service-layer UPDATE of this row's status/marked_by/
    -- marked_at, never a second INSERT (product-owner confirmed).
    CONSTRAINT uq_attendance_record_tenant_session_student
        UNIQUE (tenant_id, session_id, student_id),

    CONSTRAINT fk_attendance_record_course
        FOREIGN KEY (tenant_id, course_id) REFERENCES course (tenant_id, id),
    CONSTRAINT fk_attendance_record_session
        FOREIGN KEY (tenant_id, session_id) REFERENCES course_lesson (tenant_id, id),
    CONSTRAINT fk_attendance_record_student
        FOREIGN KEY (tenant_id, student_id) REFERENCES tenant_user (tenant_id, id),
    CONSTRAINT fk_attendance_record_marked_by
        FOREIGN KEY (tenant_id, marked_by) REFERENCES tenant_user (tenant_id, id),

    CONSTRAINT ck_attendance_record_status
        CHECK (status IN ('PRESENT', 'ABSENT', 'LATE'))
);

-- Student's own history, date-range filtered, most recent first.
CREATE INDEX idx_attendance_record_tenant_student_marked_at
    ON attendance_record (tenant_id, student_id, marked_at DESC);

-- Teacher/staff per-course report, date-range filtered. The unique
-- constraint above already leads with (tenant_id, session_id) for the
-- per-session roster read.
CREATE INDEX idx_attendance_record_tenant_course_marked_at
    ON attendance_record (tenant_id, course_id, marked_at DESC);

-- Tenant-wide staff report with no course filter.
CREATE INDEX idx_attendance_record_tenant_marked_at
    ON attendance_record (tenant_id, marked_at DESC);
