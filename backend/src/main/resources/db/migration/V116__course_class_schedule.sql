-- course-management (CAL-01 Class Calendar & Timetable - docs/specs/class-calendar.md section 2):
-- the recurring weekly class timetable of a course.
--
-- Purely additive. No earlier migration is edited. Dated class occurrences are NOT stored:
-- they are computed for a bounded interval from these rules (plus class_session rows, which
-- stay unchanged in live-class-management).
--
-- Revision model (spec 2.1): every Save appends one course_class_schedule_revision (with its
-- slots); rows are never updated or deleted by the application. A revision governs the
-- occurrences whose start instant falls in [effective_at, next revision effective_at), so a
-- schedule change never rewrites past or in-progress occurrences. A revision with zero slots
-- means "no recurring classes from now on".
--
-- Tenant-owned: tenant_id NOT NULL FK, tenant-leading indexes, composite same-tenant FKs.

-- 1. course_class_schedule_revision
--      effective_at                 server clock at Save (strictly increasing per course).
--      effective_from/effective_to  optional tenant-local date bounds of the occurrence start
--                                   date, inclusive on both ends (NULL = unbounded).
--      created_by / created_at      the editor and the save time.

CREATE TABLE course_class_schedule_revision (
    id              UUID PRIMARY KEY,
    tenant_id       UUID NOT NULL REFERENCES tenant (id),
    course_id       UUID NOT NULL,
    effective_at    TIMESTAMPTZ NOT NULL,
    effective_from  DATE,
    effective_to    DATE,
    created_by      UUID NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,

    CONSTRAINT uq_course_class_schedule_revision_tenant_id UNIQUE (tenant_id, id),

    -- Schedule rows go with their course on a (rare, only-when-unused) course hard delete.
    CONSTRAINT fk_course_class_schedule_revision_course FOREIGN KEY (tenant_id, course_id)
        REFERENCES course (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_course_class_schedule_revision_created_by FOREIGN KEY (tenant_id, created_by)
        REFERENCES tenant_user (tenant_id, id),

    CONSTRAINT ck_course_class_schedule_revision_bounds CHECK (
        effective_from IS NULL OR effective_to IS NULL OR effective_from <= effective_to
    )
);

-- One revision per instant per course; also the current / governing revision read index
-- (tenant-leading, newest first).
CREATE UNIQUE INDEX uq_course_class_schedule_revision_tenant_course_effective
    ON course_class_schedule_revision (tenant_id, course_id, effective_at DESC);

-- 2. course_class_schedule_slot: one weekly row of a revision (ISO day of week, 1 = Monday ..
--    7 = Sunday; minute-precision local wall-clock times; same-day only - overnight classes
--    are rejected, end_time > start_time).

CREATE TABLE course_class_schedule_slot (
    id           UUID PRIMARY KEY,
    tenant_id    UUID NOT NULL REFERENCES tenant (id),
    revision_id  UUID NOT NULL,
    day_of_week  SMALLINT NOT NULL,
    start_time   TIME(0) NOT NULL,
    end_time     TIME(0) NOT NULL,

    CONSTRAINT uq_course_class_schedule_slot_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_course_class_schedule_slot_row UNIQUE (revision_id, day_of_week, start_time, end_time),

    CONSTRAINT fk_course_class_schedule_slot_revision FOREIGN KEY (tenant_id, revision_id)
        REFERENCES course_class_schedule_revision (tenant_id, id) ON DELETE CASCADE,

    CONSTRAINT ck_course_class_schedule_slot_day CHECK (day_of_week BETWEEN 1 AND 7),
    CONSTRAINT ck_course_class_schedule_slot_minutes CHECK (
        date_part('second', start_time) = 0 AND date_part('second', end_time) = 0
    ),
    CONSTRAINT ck_course_class_schedule_slot_order CHECK (end_time > start_time)
);

-- Slots of a set of revisions (tenant-leading).
CREATE INDEX idx_course_class_schedule_slot_tenant_revision
    ON course_class_schedule_slot (tenant_id, revision_id);

-- 3. Append-only guard. UPDATE is always rejected, and so is a direct DELETE. The only
--    accepted delete is the cascade of a course hard delete (run by the FK referential-action
--    trigger, so pg_trigger_depth() > 1); CourseService only hard-deletes a course that no
--    student, enrollment, order or entitlement references.

CREATE FUNCTION course_class_schedule_guard() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'DELETE' AND pg_trigger_depth() > 1 THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'course class schedule revisions are append-only'
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

CREATE TRIGGER trg_course_class_schedule_revision_guard
    BEFORE UPDATE OR DELETE ON course_class_schedule_revision
    FOR EACH ROW EXECUTE FUNCTION course_class_schedule_guard();

CREATE TRIGGER trg_course_class_schedule_slot_guard
    BEFORE UPDATE OR DELETE ON course_class_schedule_slot
    FOR EACH ROW EXECUTE FUNCTION course_class_schedule_guard();
