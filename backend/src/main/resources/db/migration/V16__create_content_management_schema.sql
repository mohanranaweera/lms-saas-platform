-- content-management (MVP-009 Lessons and Learning Materials): `material`.
--
-- Owned by `content-management` (package com.lms.contentmanagement), per
-- .claude/rules/architecture.md's "one table, one owning domain" rule.
-- References course-management's `course_lesson` by an OPAQUE id only at
-- the Java/JPA level (no cross-domain entity import, no @ManyToOne) - but
-- IS schema-enforced via a composite FK, per .claude/rules/tenancy.md's
-- mandatory rule that a cross-table reference between two tenant-owned
-- tables must be schema-enforced to match tenant_id, not just a
-- service-layer check. See docs/plans/MVP-009 Lessons and Learning
-- Materials.md §7/§8 for the full reconciliation of this design decision.
--
-- fk_material_lesson deliberately has NO ON DELETE CASCADE (unlike
-- course_module/course_lesson's own FKs, V14) - material deletion is a
-- security.md-mandated audited action (an in-transaction
-- MaterialDeletedEvent, see MaterialService#deleteMaterial); an implicit
-- DB-level cascade triggered by deleting the parent lesson would silently
-- bypass that audit requirement and orphan the object-storage entry the
-- row's storage_object_key points to. Deleting a lesson that still has
-- materials attached will therefore fail with a foreign key violation
-- (mapped to 409 by GlobalExceptionHandler) until a real cross-module
-- deletion flow is designed - an intentional, documented tradeoff, not an
-- oversight (plan §21 item 2's sibling concern).
--
-- This also means CourseService#deleteCourse (which relies on V14's
-- ON DELETE CASCADE from course through course_module/course_lesson) will
-- fail with a DataIntegrityViolationException/409 if any lesson anywhere in
-- that course still has an attached material - an intentional consequence
-- of the same tradeoff, not a separate bug, but worth naming explicitly
-- since V14 was written before this table existed and couldn't have
-- anticipated it.
--
-- `id` has no DB-side DEFAULT - generated application-side (UUIDv7 via
-- com.lms.common.persistence.UuidV7Generator), per V1's baseline
-- convention. `created_at`/`updated_at`/`created_by`/`updated_by` follow
-- the Auditable convention, matching V11's shape.
--
-- `visibility` is the issue's own accepted MVP default (VISIBLE/HIDDEN
-- only, CHECK-constrained) - see plan §21 item 4; no richer taxonomy is
-- defined anywhere in the requirements corpus. `expiry_at` exists now for
-- forward compatibility but is unenforced at MVP (plan AC #10) - no
-- application code reads it to deny access yet. `mime_type` is
-- deliberately unconstrained at the DB level - the MIME/content-sniffing
-- allow-list is a service-layer, evolving concern (plan §8).

CREATE TABLE material (
    id                   UUID PRIMARY KEY,
    tenant_id            UUID NOT NULL REFERENCES tenant (id),
    lesson_id            UUID NOT NULL,
    title                VARCHAR(255) NOT NULL,
    original_filename    VARCHAR(255) NOT NULL,
    storage_object_key   VARCHAR(1024) NOT NULL,
    mime_type            VARCHAR(255) NOT NULL,
    size_bytes           BIGINT NOT NULL,
    sequence             INTEGER NOT NULL,
    visibility           VARCHAR(10) NOT NULL DEFAULT 'VISIBLE',
    expiry_at            TIMESTAMPTZ,
    uploaded_by          UUID NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL,
    updated_at           TIMESTAMPTZ NOT NULL,
    created_by           UUID,
    updated_by           UUID,

    CONSTRAINT uq_material_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_material_sequence UNIQUE (tenant_id, lesson_id, sequence),

    CONSTRAINT fk_material_lesson FOREIGN KEY (tenant_id, lesson_id)
        REFERENCES course_lesson (tenant_id, id),
    CONSTRAINT fk_material_uploaded_by FOREIGN KEY (tenant_id, uploaded_by)
        REFERENCES tenant_user (tenant_id, id),

    CONSTRAINT ck_material_sequence CHECK (sequence > 0),
    CONSTRAINT ck_material_size_bytes CHECK (size_bytes > 0),
    CONSTRAINT ck_material_visibility CHECK (visibility IN ('VISIBLE', 'HIDDEN'))
);

-- No separate CREATE INDEX is needed for the "all materials for one lesson
-- within one tenant, in sequence order" read pattern: uq_material_sequence's
-- own backing btree index already covers (tenant_id, lesson_id, sequence) in
-- that exact order, so a duplicate explicit index would add zero query
-- coverage and only extra write-time index-maintenance cost - see
-- V13__drop_redundant_course_structure_indexes.sql for the identical finding
-- previously fixed on course_module/course_lesson.
