-- course-management (Wave 2 - Course/Class model + billing foundation):
-- `course_billing_configuration` - one-per-course billing configuration
-- child entity of the `course` aggregate (V11), holding the settings that
-- vary by `course.pricing_model` (V37) but do not belong on `course` itself
-- (a per-session rate for SESSION pricing, a manual-quote flag for CUSTOM
-- pricing, and the currency those apply in).
--
-- `id` has no DB-side DEFAULT - generated application-side (UUIDv7 via
-- com.lms.common.persistence.UuidV7Generator), per V1's baseline convention,
-- matching every other table in this schema (confirmed against V11's
-- `course` table, which also has no DB-side UUID default).
--
-- `created_at`/`updated_at`/`created_by`/`updated_by` mirror `course`'s own
-- audit-column shape exactly (V11): no DB-side DEFAULT, set by Hibernate's
-- AuditingEntityListener; `created_by`/`updated_by` carry no FK, same as
-- `course.created_by`/`course.updated_by`.
--
-- `currency VARCHAR(3)` with the same `char_length(currency) = 3` CHECK as
-- `student_order.currency`/`payment.currency` (V19) - matches that existing
-- convention exactly rather than inventing a new shape.
--
-- Composite `fk_course_billing_configuration_course` FOREIGN KEY
-- (tenant_id, course_id) REFERENCES course (tenant_id, id) ON DELETE CASCADE
-- - a cross-tenant billing configuration is a schema constraint violation,
-- not just a service-layer bug, per .claude/rules/tenancy.md. `ON DELETE
-- CASCADE` here follows V14's precedent for `course_module`/`course_lesson`,
-- not V12's precedent for `course_price_history`: this table holds live,
-- mutable configuration (analogous to `course_module`), not append-only
-- financial/pricing history, so it has no independent audit value once its
-- owning course is deleted, and must not silently block `CourseService
-- #deleteCourse` the way the pre-V12/pre-V14 FKs used to (see V12/V14's own
-- header comments for that exact defect class). `course_billing_period`
-- (V39) is the table that carries this domain's actual append-only history
-- and is deliberately handled the opposite way (no live FK back to this
-- table at all), so a course delete cascading through this row never
-- touches billing-period history.
--
-- `UNIQUE (tenant_id, course_id)` enforces "one billing configuration per
-- course" and doubles as this table's tenant-leading query index (the only
-- read pattern this table has: "fetch the billing configuration for course
-- X within tenant Y"). `UNIQUE (tenant_id, id)` is added alongside it
-- following this schema's general precedent (`course`, `student_order`,
-- `payment`, `reactivation_request`, ...) of giving every tenant-owned table
-- a `(tenant_id, id)` unique constraint up front, in case a future composite
-- FK from another tenant-owned table needs to reference this table's `id`
-- (mirroring the gap V15 had to fix retroactively for `course_lesson`) - see
-- V39's header comment for why `course_billing_period` itself deliberately
-- does NOT use it to add a live FK back here.

CREATE TABLE course_billing_configuration (
    id                      UUID PRIMARY KEY,
    tenant_id               UUID NOT NULL REFERENCES tenant (id),
    course_id               UUID NOT NULL,
    session_rate            NUMERIC(12,2),
    currency                VARCHAR(3) NOT NULL,
    requires_manual_quote   BOOLEAN NOT NULL DEFAULT FALSE,
    created_at              TIMESTAMPTZ NOT NULL,
    updated_at              TIMESTAMPTZ NOT NULL,
    created_by              UUID,
    updated_by              UUID,

    CONSTRAINT uq_course_billing_configuration_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_course_billing_configuration_tenant_course UNIQUE (tenant_id, course_id),

    CONSTRAINT fk_course_billing_configuration_course FOREIGN KEY (tenant_id, course_id)
        REFERENCES course (tenant_id, id) ON DELETE CASCADE,

    -- Only meaningful when the owning course's pricing_model is SESSION;
    -- left NULL otherwise. Same ">= 0" convention as course.price/
    -- student_order.amount (allows a future free/waived session rate
    -- without a breaking constraint change).
    CONSTRAINT ck_course_billing_configuration_session_rate CHECK (
        session_rate IS NULL OR session_rate >= 0
    ),
    CONSTRAINT ck_course_billing_configuration_currency CHECK (char_length(currency) = 3)
);
