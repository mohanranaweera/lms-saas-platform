-- course-management (Klass course/enrollment parity, KLASS-T2, ADR-028):
-- additive course / module / lesson ("Session") fields plus five new
-- course-management tables: `course_tag`, `course_tag_assignment`,
-- `course_teacher_assignment`, `course_link`, `course_commission_rate`.
--
-- Schema contract: docs/plans/klass-enrollment-payment-parity.md §3 (V85),
-- decisions D03, D09, D10, D11, D12, D13, D15.
--
-- Purely additive. No existing migration is edited (V11/V14/V15/V37 are
-- already applied/shared). The only statements touching existing rows are
-- the two explicit backfills below:
--   1. `course.display_order` = 1..n per tenant, ordered by created_at, id
--      (UPDATE of a new, previously-NULL column only).
--   2. `course_teacher_assignment` gets exactly one is_primary = TRUE row per
--      existing course, copied from `course.teacher_id` (INSERT only).
-- Nothing is inferred for lessons: every existing lesson gets payment_type
-- 'COVERED_BY_CLASS_FEES' (column default) and a NULL billing month, so
-- existing course-wide paid access (legacy `enrollment`) keeps covering them
-- (D05). No order, payment, slip, ledger, refund, enrollment or audit row is
-- read or written by this migration - payment/ledger history is untouched.
--
-- Conventions (V1/V11/V19): UUID ids generated application-side (backfilled
-- ids are deterministic md5-derived UUIDs, the V56/V76 precedent); every new
-- table is tenant-owned with `tenant_id NOT NULL REFERENCES tenant (id)`, an
-- index leading with tenant_id, composite `(tenant_id, ...)` FKs to
-- same-tenant parents and per-tenant UNIQUE constraints.
--
-- ON DELETE behaviour: `course` can be hard-deleted (CourseService
-- #deleteCourse; V14 cascades its modules/lessons). Tag assignments, teacher
-- assignments and course links are course structure with no independent
-- history value, so they cascade with the course (V14 precedent).
-- `course_commission_rate` is append-only rate history and, exactly like
-- `course_price_history` (V12), carries NO FK to `course` so the history
-- survives a course hard-delete (a composite SET NULL would null tenant_id).
--
-- `created_by` on the new tables follows the Auditable convention used by
-- `course.created_by` (V11): an actor id with no FK, because the acting
-- principal may be a tenant user or (via platform administration) a
-- platform_admin_user (see V32's reasoning for audit_log.actor_id).

-- ---------------------------------------------------------------------------
-- 1. course: new columns
-- ---------------------------------------------------------------------------

ALTER TABLE course
    ADD COLUMN registration_enabled    BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN enrollment_fee          NUMERIC(12,2) NOT NULL DEFAULT 0,
    ADD COLUMN cover_image_object_key  VARCHAR(512),
    ADD COLUMN whatsapp_invite_url     VARCHAR(500),
    ADD COLUMN telegram_invite_url     VARCHAR(500),
    ADD COLUMN display_order           INTEGER;

-- Enrollment fee is informational metadata only (ADR-028 consequence 6):
-- never charged, never gates enrollment.
ALTER TABLE course
    ADD CONSTRAINT ck_course_enrollment_fee CHECK (enrollment_fee >= 0),
    ADD CONSTRAINT ck_course_cover_image_object_key_not_blank CHECK (
        cover_image_object_key IS NULL OR btrim(cover_image_object_key) <> ''
    ),
    -- API contract accepts https links only (§4.1); enforced here too so a
    -- non-https invite link can never be stored by another write path.
    ADD CONSTRAINT ck_course_whatsapp_invite_url_https CHECK (
        whatsapp_invite_url IS NULL OR whatsapp_invite_url LIKE 'https://%'
    ),
    ADD CONSTRAINT ck_course_telegram_invite_url_https CHECK (
        telegram_invite_url IS NULL OR telegram_invite_url LIKE 'https://%'
    ),
    ADD CONSTRAINT ck_course_display_order CHECK (display_order IS NULL OR display_order >= 0);

-- Extend the V37 pricing-model enum (D09). SESSION/CUSTOM stay valid for
-- existing courses; DAILY/MODULE/INSTALLMENT are new. Dropped and re-added
-- under the same name - no row is rewritten, every existing value is still
-- a member of the new set.
ALTER TABLE course DROP CONSTRAINT ck_course_pricing_model;
ALTER TABLE course
    ADD CONSTRAINT ck_course_pricing_model CHECK (
        pricing_model IN ('FREE', 'ONE_TIME', 'MONTHLY', 'SESSION', 'CUSTOM',
                          'DAILY', 'MODULE', 'INSTALLMENT')
    );

-- Backfill 1: display order by creation time within each tenant. New courses
-- created by code that does not yet set display_order keep NULL (sort NULLS
-- LAST, then created_at).
UPDATE course c
SET display_order = o.rn
FROM (
    SELECT id, row_number() OVER (PARTITION BY tenant_id ORDER BY created_at, id) AS rn
    FROM course
) o
WHERE o.id = c.id;

-- Admin course list: sort displayOrder, createdAt within a tenant.
CREATE INDEX idx_course_tenant_display_order
    ON course (tenant_id, display_order, created_at);

-- ---------------------------------------------------------------------------
-- 2. course_module: module price (powers MODULE offers, D09)
-- ---------------------------------------------------------------------------

ALTER TABLE course_module
    ADD COLUMN price NUMERIC(12,2);

ALTER TABLE course_module
    ADD CONSTRAINT ck_course_module_price CHECK (price IS NULL OR price > 0);

-- ---------------------------------------------------------------------------
-- 3. course_lesson ("Session" in the admin UI, D03)
-- ---------------------------------------------------------------------------

ALTER TABLE course_lesson
    ADD COLUMN starts_at      TIMESTAMPTZ,
    ADD COLUMN ends_at        TIMESTAMPTZ,
    ADD COLUMN resource_type  VARCHAR(40),
    ADD COLUMN payment_type   VARCHAR(30) NOT NULL DEFAULT 'COVERED_BY_CLASS_FEES',
    ADD COLUMN price          NUMERIC(12,2),
    ADD COLUMN description    TEXT,
    ADD COLUMN lesson_label   VARCHAR(255),
    ADD COLUMN billing_year   SMALLINT,
    ADD COLUMN billing_month  SMALLINT;

ALTER TABLE course_lesson
    ADD CONSTRAINT ck_course_lesson_time_window CHECK (
        starts_at IS NULL OR ends_at IS NULL OR ends_at > starts_at
    ),
    ADD CONSTRAINT ck_course_lesson_resource_type CHECK (
        resource_type IS NULL OR resource_type IN (
            'VIMEO_VIDEO', 'SECURE_VIDEO', 'DOCUMENT', 'ZOOM_LINK', 'ZOOM_MEETING',
            'ZOOM_WEBINAR', 'ZOOM_RECORDING', 'VIDEO_LINK', 'GOOGLE_DRIVE_LINK',
            'MS_TEAMS_LINK', 'GOOGLE_MEET')
    ),
    ADD CONSTRAINT ck_course_lesson_payment_type CHECK (
        payment_type IN ('FREE', 'COVERED_BY_CLASS_FEES', 'PAID')
    ),
    -- PAID <=> price > 0; FREE/COVERED lessons carry no price.
    ADD CONSTRAINT ck_course_lesson_price CHECK (
        (payment_type = 'PAID' AND price IS NOT NULL AND price > 0)
        OR (payment_type <> 'PAID' AND price IS NULL)
    ),
    -- Explicit billing-month association (never parsed from titles/dates):
    -- both or neither, month 1-12, year 2000-2100.
    ADD CONSTRAINT ck_course_lesson_billing_month CHECK (
        (billing_year IS NULL AND billing_month IS NULL)
        OR (billing_year IS NOT NULL AND billing_month IS NOT NULL
            AND billing_month BETWEEN 1 AND 12
            AND billing_year BETWEEN 2000 AND 2100)
    ),
    ADD CONSTRAINT ck_course_lesson_lesson_label_not_blank CHECK (
        lesson_label IS NULL OR btrim(lesson_label) <> ''
    );

-- Content tree grouping by billing month within a module, and "eligible
-- months" (distinct lesson billing months) for MONTHLY offers.
CREATE INDEX idx_course_lesson_tenant_module_billing_month
    ON course_lesson (tenant_id, module_id, billing_year, billing_month);

-- ---------------------------------------------------------------------------
-- 4. course_tag / course_tag_assignment
-- ---------------------------------------------------------------------------

CREATE TABLE course_tag (
    id               UUID PRIMARY KEY,
    tenant_id        UUID NOT NULL REFERENCES tenant (id),
    name             VARCHAR(100) NOT NULL,
    -- Case-insensitive dedupe key (trimmed, lower-cased by the service).
    normalized_name  VARCHAR(100) NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL,

    CONSTRAINT uq_course_tag_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_course_tag_tenant_normalized_name UNIQUE (tenant_id, normalized_name),

    CONSTRAINT ck_course_tag_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT ck_course_tag_normalized_name_not_blank CHECK (btrim(normalized_name) <> '')
);

CREATE TABLE course_tag_assignment (
    tenant_id  UUID NOT NULL REFERENCES tenant (id),
    course_id  UUID NOT NULL,
    tag_id     UUID NOT NULL,

    CONSTRAINT pk_course_tag_assignment PRIMARY KEY (course_id, tag_id),

    CONSTRAINT fk_course_tag_assignment_course FOREIGN KEY (tenant_id, course_id)
        REFERENCES course (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_course_tag_assignment_tag FOREIGN KEY (tenant_id, tag_id)
        REFERENCES course_tag (tenant_id, id) ON DELETE CASCADE
);

-- Tags of one course.
CREATE INDEX idx_course_tag_assignment_tenant_course
    ON course_tag_assignment (tenant_id, course_id);

-- Student catalog filter by tag (tagId).
CREATE INDEX idx_course_tag_assignment_tenant_tag
    ON course_tag_assignment (tenant_id, tag_id);

-- ---------------------------------------------------------------------------
-- 5. course_teacher_assignment (D12): multi-select teachers.
--    course.teacher_id stays the primary teacher and the sole ownership
--    authority for guards; the service keeps it in sync with is_primary.
-- ---------------------------------------------------------------------------

CREATE TABLE course_teacher_assignment (
    id          UUID PRIMARY KEY,
    tenant_id   UUID NOT NULL REFERENCES tenant (id),
    course_id   UUID NOT NULL,
    teacher_id  UUID NOT NULL,
    is_primary  BOOLEAN NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ NOT NULL,

    CONSTRAINT uq_course_teacher_assignment_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_course_teacher_assignment_course_teacher UNIQUE (tenant_id, course_id, teacher_id),

    CONSTRAINT fk_course_teacher_assignment_course FOREIGN KEY (tenant_id, course_id)
        REFERENCES course (tenant_id, id) ON DELETE CASCADE,
    -- Opaque tenant_user id, same precedent as course.teacher_id (V11).
    CONSTRAINT fk_course_teacher_assignment_teacher FOREIGN KEY (tenant_id, teacher_id)
        REFERENCES tenant_user (tenant_id, id)
);

-- At most one primary teacher per course.
CREATE UNIQUE INDEX uq_course_teacher_assignment_primary
    ON course_teacher_assignment (tenant_id, course_id)
    WHERE is_primary;

-- Teacher's assigned courses within a tenant.
CREATE INDEX idx_course_teacher_assignment_tenant_teacher
    ON course_teacher_assignment (tenant_id, teacher_id);

-- Backfill 2: one primary assignment per existing course.
INSERT INTO course_teacher_assignment (id, tenant_id, course_id, teacher_id, is_primary, created_at)
SELECT md5(c.tenant_id::text || c.id::text || 'primary-teacher-assignment')::uuid,
       c.tenant_id,
       c.id,
       c.teacher_id,
       TRUE,
       c.created_at
FROM course c;

-- ---------------------------------------------------------------------------
-- 6. course_link (D13): one level, same tenant, no self link, no cycle.
-- ---------------------------------------------------------------------------

CREATE TABLE course_link (
    id                UUID PRIMARY KEY,
    tenant_id         UUID NOT NULL REFERENCES tenant (id),
    parent_course_id  UUID NOT NULL,
    source_course_id  UUID NOT NULL,
    created_by        UUID,
    created_at        TIMESTAMPTZ NOT NULL,

    CONSTRAINT uq_course_link_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_course_link_parent_source UNIQUE (tenant_id, parent_course_id, source_course_id),

    CONSTRAINT fk_course_link_parent_course FOREIGN KEY (tenant_id, parent_course_id)
        REFERENCES course (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_course_link_source_course FOREIGN KEY (tenant_id, source_course_id)
        REFERENCES course (tenant_id, id) ON DELETE CASCADE,

    CONSTRAINT ck_course_link_not_self CHECK (parent_course_id <> source_course_id)
);

-- "Which courses link to this source" (reverse lookup, cascade support).
CREATE INDEX idx_course_link_tenant_source
    ON course_link (tenant_id, source_course_id);

-- One-level rule, enforced in the schema: a course that is a link source can
-- never itself be a parent, and a parent can never be used as a source. This
-- also rules out every cycle (A->B->A needs B to be both). Same trigger
-- style as V34. Concurrent inserts can still race; the service must take the
-- same check under its own course-row lock.
CREATE OR REPLACE FUNCTION course_link_must_be_one_level() RETURNS TRIGGER AS $$
BEGIN
    IF EXISTS (
            SELECT 1 FROM course_link
            WHERE tenant_id = NEW.tenant_id AND parent_course_id = NEW.source_course_id) THEN
        RAISE EXCEPTION
            'course_link: source course % is itself a parent of linked courses (links are one level only)',
            NEW.source_course_id
            USING ERRCODE = 'check_violation';
    END IF;
    IF EXISTS (
            SELECT 1 FROM course_link
            WHERE tenant_id = NEW.tenant_id AND source_course_id = NEW.parent_course_id) THEN
        RAISE EXCEPTION
            'course_link: parent course % is itself linked into another course (links are one level only)',
            NEW.parent_course_id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_course_link_one_level
    BEFORE INSERT OR UPDATE ON course_link
    FOR EACH ROW EXECUTE FUNCTION course_link_must_be_one_level();

-- ---------------------------------------------------------------------------
-- 7. course_commission_rate (D11): effective-dated, APPEND-ONLY. Stored and
--    displayed only - not fed into settlement (open item O-3). No repository
--    method may update or delete these rows; a rate change is a new row.
-- ---------------------------------------------------------------------------

CREATE TABLE course_commission_rate (
    id              UUID PRIMARY KEY,
    tenant_id       UUID NOT NULL REFERENCES tenant (id),
    -- No FK to course: history survives course hard-delete (V12 precedent).
    course_id       UUID NOT NULL,
    rate            NUMERIC(5,2) NOT NULL,
    effective_from  TIMESTAMPTZ NOT NULL,
    created_by      UUID,
    created_at      TIMESTAMPTZ NOT NULL,

    CONSTRAINT uq_course_commission_rate_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_course_commission_rate_effective UNIQUE (tenant_id, course_id, effective_from),

    CONSTRAINT ck_course_commission_rate_rate CHECK (rate >= 0 AND rate <= 100)
);

-- No separate index: uq_course_commission_rate_effective already leads with
-- (tenant_id, course_id, effective_from) and serves the "current rate =
-- latest effective_from <= now() for one course" read via a backward scan.
