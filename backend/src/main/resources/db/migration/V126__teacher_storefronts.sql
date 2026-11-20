-- STORE-01 Teacher Storefronts (ADR-037). Purely additive; no earlier migration is edited.
-- Every new table is tenant-owned: tenant_id NOT NULL FK to tenant, tenant-leading
-- indexes, per-tenant unique constraints and same-tenant composite FKs.
-- A teacher is a tenant_user id (same precedent as course.teacher_id, V11/V85).

-- ---------------------------------------------------------------------------
-- 1. teacher_storefront: one opt-in storefront per teacher. Off by default.
--    `slug` is the teacher's CURRENT slug; every slug ever used is also in
--    teacher_storefront_slug (below), which is what reserves it.
-- ---------------------------------------------------------------------------

CREATE TABLE teacher_storefront (
    id           UUID PRIMARY KEY,
    tenant_id    UUID NOT NULL REFERENCES tenant (id),
    teacher_id   UUID NOT NULL,
    slug         VARCHAR(40) NOT NULL,
    enabled      BOOLEAN NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ NOT NULL,
    created_by   UUID,
    updated_at   TIMESTAMPTZ NOT NULL,
    updated_by   UUID,

    CONSTRAINT uq_teacher_storefront_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_teacher_storefront_teacher UNIQUE (tenant_id, teacher_id),
    CONSTRAINT uq_teacher_storefront_slug UNIQUE (tenant_id, slug),
    CONSTRAINT chk_teacher_storefront_slug CHECK (slug ~ '^[a-z0-9](?:[a-z0-9-]{1,38}[a-z0-9])$' AND slug NOT LIKE '%--%'),
    CONSTRAINT fk_teacher_storefront_teacher FOREIGN KEY (tenant_id, teacher_id)
        REFERENCES tenant_user (tenant_id, id)
);

-- ---------------------------------------------------------------------------
-- 2. teacher_storefront_slug: every slug a storefront has ever used, current or
--    retired. Unique per tenant, so a slug is never reassigned to another
--    teacher (ADR-037 retirement rule). Rows are never deleted. A retired slug
--    redirects to the same storefront's current slug only while it is enabled.
-- ---------------------------------------------------------------------------

CREATE TABLE teacher_storefront_slug (
    id             UUID PRIMARY KEY,
    tenant_id      UUID NOT NULL REFERENCES tenant (id),
    storefront_id  UUID NOT NULL,
    slug           VARCHAR(40) NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL,
    retired_at     TIMESTAMPTZ,

    CONSTRAINT uq_teacher_storefront_slug_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_teacher_storefront_slug_slug UNIQUE (tenant_id, slug),
    CONSTRAINT fk_teacher_storefront_slug_storefront FOREIGN KEY (tenant_id, storefront_id)
        REFERENCES teacher_storefront (tenant_id, id)
);

CREATE INDEX idx_teacher_storefront_slug_storefront
    ON teacher_storefront_slug (tenant_id, storefront_id);

-- ---------------------------------------------------------------------------
-- 3. teacher_storefront_product: the explicit list of courses a storefront
--    sells. The composite FK to course_teacher_assignment means a teacher can
--    only list a course they are assigned to; a shared course (several
--    assigned teachers) appears on each storefront only where it is listed.
--    Unassigning the teacher cascades the listing away - listing never
--    affects enrollment or entitlements (ADR-028).
-- ---------------------------------------------------------------------------

CREATE TABLE teacher_storefront_product (
    id             UUID PRIMARY KEY,
    tenant_id      UUID NOT NULL REFERENCES tenant (id),
    storefront_id  UUID NOT NULL,
    teacher_id     UUID NOT NULL,
    course_id      UUID NOT NULL,
    published      BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order     INTEGER NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL,
    created_by     UUID,
    updated_at     TIMESTAMPTZ NOT NULL,
    updated_by     UUID,

    CONSTRAINT uq_teacher_storefront_product_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_teacher_storefront_product_course UNIQUE (tenant_id, storefront_id, course_id),
    CONSTRAINT fk_teacher_storefront_product_storefront FOREIGN KEY (tenant_id, storefront_id)
        REFERENCES teacher_storefront (tenant_id, id),
    CONSTRAINT fk_teacher_storefront_product_storefront_teacher FOREIGN KEY (tenant_id, teacher_id)
        REFERENCES teacher_storefront (tenant_id, teacher_id),
    CONSTRAINT fk_teacher_storefront_product_assignment FOREIGN KEY (tenant_id, course_id, teacher_id)
        REFERENCES course_teacher_assignment (tenant_id, course_id, teacher_id) ON DELETE CASCADE
);

CREATE INDEX idx_teacher_storefront_product_course
    ON teacher_storefront_product (tenant_id, course_id);

-- ---------------------------------------------------------------------------
-- 4. student_order_line.storefront_id: attribution only, written once at
--    insert (order lines stay insert-only). It never changes course ownership,
--    commission, settlement or the ledger. NULL = bought on the tenant site.
-- ---------------------------------------------------------------------------

ALTER TABLE student_order_line
    ADD COLUMN storefront_id UUID,
    ADD CONSTRAINT fk_student_order_line_storefront FOREIGN KEY (tenant_id, storefront_id)
        REFERENCES teacher_storefront (tenant_id, id);

CREATE INDEX idx_student_order_line_storefront
    ON student_order_line (tenant_id, storefront_id)
    WHERE storefront_id IS NOT NULL;
