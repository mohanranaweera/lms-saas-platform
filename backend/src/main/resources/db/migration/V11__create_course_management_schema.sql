-- course-management (MVP-008 Course Management): `course`, `course_module`,
-- `course_lesson`, `course_price_history`.
--
-- Owned by `course-management` (package com.lms.coursemanagement), per
-- .claude/rules/architecture.md's "one table, one owning domain" rule.
-- `course-management`'s aggregate root is `Course`, with `CourseModule` and
-- `CourseLesson` as structural child entities and `CoursePriceHistory` as an
-- append-only history entity - all facets of the same aggregate, per
-- docs/plans/MVP-008 Course Management.md §7.
--
-- `teacher_id` on `course` is an OPAQUE `tenant_user` id only - never a JPA
-- entity association across the module boundary (per
-- .claude/rules/architecture.md) - but is still schema-enforced via a
-- composite FK, following V10 (`staff_profile`)'s precedent exactly: a
-- `course` row can never reference a `tenant_user` belonging to a different
-- tenant than its own `tenant_id`, a schema-level guarantee, not a
-- service-layer-only check.
--
-- Every cross-table reference in this migration is expressed the same way:
-- `course_module.course_id` -> `course(tenant_id, id)`,
-- `course_lesson.module_id` -> `course_module(tenant_id, id)`,
-- `course_price_history.course_id` -> `course(tenant_id, id)`,
-- `course_price_history.changed_by` -> `tenant_user(tenant_id, id)` - all
-- composite `(tenant_id, ...)` FKs, never a bare FK on the child id alone,
-- so a cross-tenant reference is a schema constraint violation, not just a
-- service-layer bug.
--
-- `id` has no DB-side DEFAULT on any of these four tables - generated
-- application-side (UUIDv7 via com.lms.common.persistence.UuidV7Generator),
-- per V1's baseline convention.
--
-- `created_at`/`updated_at`/`created_by`/`updated_by` on `course`,
-- `course_module`, `course_lesson` follow the Auditable convention
-- (com.lms.common.persistence.Auditable): no DB-side DEFAULT, set by
-- Hibernate's AuditingEntityListener, matching V10's audit-column shape.
-- `course_price_history` is the one exception - append-only, insert-only,
-- so it carries `created_at` only (no `updated_at`/`created_by`/`updated_by`
-- - see below).
--
-- Decisions carried over verbatim from the approved plan (§8) - do not
-- deviate without a new product-owner decision:
--   * `enrollment_rule` is free-text `TEXT`, no CHECK-constrained enum - no
--     rule engine reads or acts on this column at MVP.
--   * No `currency` column on `course` - a single implicit currency is
--     assumed platform-wide/per-tenant at MVP; bare `NUMERIC` price.
--   * `price NUMERIC(12,2) NOT NULL CHECK (price >= 0)` - `>= 0`, not `> 0`,
--     so a future $0/trial course is not blocked by a breaking constraint
--     change later.
--   * `access_duration_days` is optional; NULL means unlimited/lifetime
--     access.
--   * `category`/`subject`/`stream`/`grade`/`academic_year` stay free-text -
--     no catalog table exists anywhere in this codebase yet, and building
--     one now would be scope creep with no requirement backing it.
--   * `course_price_history` is written on EVERY price change regardless of
--     the course's status (not only once published) - simpler than a
--     status-conditional branch in the one non-bypassable write path, and a
--     strict superset of spec §9's literal requirement.

CREATE TABLE course (
    id                    UUID PRIMARY KEY,
    tenant_id             UUID NOT NULL REFERENCES tenant (id),
    teacher_id            UUID NOT NULL,
    name                  VARCHAR(255) NOT NULL,
    slug                  VARCHAR(160) NOT NULL,
    category              VARCHAR(100) NOT NULL,
    subject               VARCHAR(100),
    stream                VARCHAR(100),
    grade                 VARCHAR(50),
    academic_year         VARCHAR(20),
    description           TEXT,
    price                 NUMERIC(12,2) NOT NULL,
    access_duration_days  INTEGER,
    enrollment_rule       TEXT,
    status                VARCHAR(10) NOT NULL DEFAULT 'DRAFT',
    created_at            TIMESTAMPTZ NOT NULL,
    updated_at            TIMESTAMPTZ NOT NULL,
    created_by            UUID,
    updated_by            UUID,

    CONSTRAINT uq_course_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_course_tenant_slug UNIQUE (tenant_id, slug),

    CONSTRAINT fk_course_teacher FOREIGN KEY (tenant_id, teacher_id)
        REFERENCES tenant_user (tenant_id, id),

    CONSTRAINT ck_course_price CHECK (price >= 0),
    CONSTRAINT ck_course_access_duration_days CHECK (
        access_duration_days IS NULL OR access_duration_days > 0
    ),
    CONSTRAINT ck_course_status CHECK (status IN ('DRAFT', 'PRIVATE', 'PUBLIC'))
);

-- Serves both admin status-filtered listing and the storefront read
-- (`status = 'PUBLIC'` is a leading-prefix match on the same index, no
-- separate visibility index needed).
CREATE INDEX idx_course_tenant_status_created_at
    ON course (tenant_id, status, created_at DESC);

-- Teacher's "My Courses" (tenant + own courses, optionally filtered by
-- status).
CREATE INDEX idx_course_tenant_teacher_status
    ON course (tenant_id, teacher_id, status);

CREATE TABLE course_module (
    id          UUID PRIMARY KEY,
    tenant_id   UUID NOT NULL REFERENCES tenant (id),
    course_id   UUID NOT NULL,
    title       VARCHAR(255) NOT NULL,
    sequence    INTEGER NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL,
    created_by  UUID,
    updated_by  UUID,

    CONSTRAINT uq_course_module_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_course_module_sequence UNIQUE (tenant_id, course_id, sequence),

    CONSTRAINT fk_course_module_course FOREIGN KEY (tenant_id, course_id)
        REFERENCES course (tenant_id, id),

    CONSTRAINT ck_course_module_sequence CHECK (sequence > 0)
);

-- Module/lesson ordering read pattern: all modules for one course within one
-- tenant, in sequence order. The `uq_course_module_sequence` constraint above
-- already backs this exact column order, but it is declared explicitly per
-- the plan's §8 index list.
CREATE INDEX idx_course_module_tenant_course_sequence
    ON course_module (tenant_id, course_id, sequence);

CREATE TABLE course_lesson (
    id          UUID PRIMARY KEY,
    tenant_id   UUID NOT NULL REFERENCES tenant (id),
    module_id   UUID NOT NULL,
    title       VARCHAR(255) NOT NULL,
    sequence    INTEGER NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL,
    created_by  UUID,
    updated_by  UUID,

    CONSTRAINT uq_course_lesson_sequence UNIQUE (tenant_id, module_id, sequence),

    CONSTRAINT fk_course_lesson_module FOREIGN KEY (tenant_id, module_id)
        REFERENCES course_module (tenant_id, id),

    CONSTRAINT ck_course_lesson_sequence CHECK (sequence > 0)
);

-- Module/lesson ordering read pattern: all lessons for one module within one
-- tenant, in sequence order. The `uq_course_lesson_sequence` constraint above
-- already backs this exact column order, but it is declared explicitly per
-- the plan's §8 index list.
CREATE INDEX idx_course_lesson_tenant_module_sequence
    ON course_lesson (tenant_id, module_id, sequence);

-- Append-only - see §16 of the plan. `CourseService.changePrice(...)` is the
-- ONLY code path permitted to write the `price` column on `course`; that
-- method writes the new price and this history row in the same
-- @Transactional boundary. No repository method for this table may expose
-- update/delete - insert only, matching the append-only convention already
-- established for financial/ledger-adjacent history tables (see
-- .claude/rules/backend.md "Append-only entity design").
--
-- No `updated_at`/`created_by`/`updated_by` columns here - unlike `course`,
-- `course_module`, `course_lesson`, this row is never updated after insert,
-- so those columns would be permanently null/meaningless. `created_at` is
-- the row's one immutable timestamp.
CREATE TABLE course_price_history (
    id              UUID PRIMARY KEY,
    tenant_id       UUID NOT NULL REFERENCES tenant (id),
    course_id       UUID NOT NULL,
    changed_by      UUID NOT NULL,
    previous_price  NUMERIC(12,2) NOT NULL,
    new_price       NUMERIC(12,2) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_course_price_history_course FOREIGN KEY (tenant_id, course_id)
        REFERENCES course (tenant_id, id),

    CONSTRAINT fk_course_price_history_changed_by FOREIGN KEY (tenant_id, changed_by)
        REFERENCES tenant_user (tenant_id, id),

    CONSTRAINT ck_course_price_history_previous_price CHECK (previous_price >= 0),
    CONSTRAINT ck_course_price_history_new_price CHECK (new_price >= 0)
);

-- Price-history read pattern: all price changes for one course within one
-- tenant, most recent first.
CREATE INDEX idx_course_price_history_tenant_course_created_at
    ON course_price_history (tenant_id, course_id, created_at DESC);
