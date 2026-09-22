-- ledger-settlement-management-adjacent / course-management (Wave 2 -
-- Course/Class model + billing foundation): `course_billing_period` -
-- append-only history of the billed amount in effect for a course's billing
-- configuration (V38) over time, mirroring `course_price_history`'s (V11)
-- role for `course.price`, but scoped to the new per-model billing
-- configuration instead.
--
-- `id` has no DB-side DEFAULT - generated application-side (UUIDv7 via
-- com.lms.common.persistence.UuidV7Generator), per V1's baseline convention.
--
-- Append-only, matching `course_price_history`'s (V11) exact shape: no
-- `updated_at`/`updated_by` columns exist because a row is never updated
-- after insert other than the one narrow, explicitly-allowed exception -
-- closing an open period by setting `effective_to` once - which the service
-- layer performs, never a general-purpose UPDATE. `created_by` is carried
-- (nullable, no FK, same shape as every other audit `created_by` column in
-- this schema) but there is no `updated_by`, since nothing else on this row
-- is ever updated.
--
-- `created_at` intentionally carries `DEFAULT now()`, unlike this schema's
-- usual Hibernate-managed audit-column convention (no DB-side default, e.g.
-- `course`/`course_billing_configuration`'s `created_at`) - this table's
-- rows are simple, system-timestamped, insert-only facts (mirroring
-- `ledger_entry`/`payment_refund`'s `created_at NOT NULL` shape, V19), and a
-- DB-side default is the safer choice for a table whose only mutation path
-- is a narrow, explicitly-audited "close this period" service call rather
-- than a general JPA auditing listener.
--
-- `billing_configuration_id` deliberately carries NO live foreign key to
-- `course_billing_configuration`, even though it is `NOT NULL` and always
-- populated (by application code, from an already tenant-scoped, currently-
-- loaded `course_billing_configuration` row - never client input). This is
-- the exact technique V12 used for `course_price_history.course_id`
-- (dropping/omitting the FK to `course` entirely), applied here proactively
-- rather than retroactively, for the same underlying reason V12 documents:
--   * `course_billing_configuration` (V38) now cascade-deletes when its
--     owning `course` is deleted (`fk_course_billing_configuration_course
--     ... ON DELETE CASCADE`, mirroring V14's `course_module`/
--     `course_lesson` precedent). If `course_billing_period` held a live,
--     blocking FK to `course_billing_configuration`, that cascade would
--     either (a) be blocked outright by the default NO ACTION/RESTRICT
--     behavior - reintroducing exactly the V12/V14 "service layer has to
--     manually delete history rows to unblock a course delete" defect - or
--     (b) require `ON DELETE CASCADE`/`SET NULL` on this FK too, which would
--     destroy or null out billing-period financial history, violating root
--     CLAUDE.md's "never delete financial history" and
--     .claude/rules/payments.md's append-only-ledger-adjacent-history rule.
--   * `ON DELETE SET NULL` is not a viable middle ground either, for the
--     identical composite-FK reason V12 documents: this would be a
--     COMPOSITE FK on `(tenant_id, billing_configuration_id)`, and
--     Postgres's `ON DELETE SET NULL` on a composite FK nulls every column
--     listed in the FK - including `tenant_id` - which would violate this
--     schema's absolute "tenant_id NOT NULL, never nullable" rule.
-- `billing_configuration_id` therefore remains `NOT NULL` and permanently
-- populated, but is intentionally orphan-tolerant with respect to its
-- logical parent's future lifecycle, exactly like `course_price_history
-- .course_id`.
--
-- `tenant_id` still carries its own direct FK to `tenant(id)` (never
-- dropped) - only the cross-tenant-table `billing_configuration_id`
-- reference is omitted, matching V12's precedent exactly (V12 did not touch
-- `course_price_history.tenant_id`'s own FK to `tenant`, only
-- `fk_course_price_history_course`).
--
-- `uq_course_billing_period_current` (partial unique index, `WHERE
-- effective_to IS NULL`) enforces "at most one OPEN/current billing period
-- per billing configuration at a time", mirroring `enrollment`'s (V22)
-- `uq_enrollment_tenant_student_course_current` lineage-row technique
-- exactly: closing a period is the one allowed mutation (`effective_to =
-- now()`), then a brand-new row is inserted as the new current period. Any
-- number of CLOSED (historical) periods may coexist for the same billing
-- configuration.
--
-- `uq_course_billing_period_tenant_id UNIQUE (tenant_id, id)` is added
-- up front, following this schema's general "every tenant-owned table gets
-- one" precedent (see V38's own header comment) - it is what makes it
-- possible for `student_order.billing_period_id` (V40) to hold a composite
-- `(tenant_id, billing_period_id)` FK into this table.

CREATE TABLE course_billing_period (
    id                        UUID PRIMARY KEY,
    tenant_id                 UUID NOT NULL REFERENCES tenant (id),
    billing_configuration_id  UUID NOT NULL,
    amount                    NUMERIC(12,2) NOT NULL,
    currency                  VARCHAR(3) NOT NULL,
    effective_from            TIMESTAMPTZ NOT NULL,
    effective_to              TIMESTAMPTZ,
    created_by                UUID,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_course_billing_period_tenant_id UNIQUE (tenant_id, id),

    -- Matches course.price/student_order.amount's own ">= 0" convention -
    -- see V11/V19 header comments (future $0/trial billing period is not
    -- blocked by a breaking constraint change later).
    CONSTRAINT ck_course_billing_period_amount CHECK (amount >= 0),
    CONSTRAINT ck_course_billing_period_currency CHECK (char_length(currency) = 3),
    CONSTRAINT ck_course_billing_period_effective_range CHECK (
        effective_to IS NULL OR effective_to > effective_from
    )
);

-- At most one open (effective_to IS NULL) period per billing configuration -
-- see header comment. Leads with tenant_id per this schema's tenant-
-- composite-index convention.
CREATE UNIQUE INDEX uq_course_billing_period_current
    ON course_billing_period (tenant_id, billing_configuration_id)
    WHERE effective_to IS NULL;

-- Billing-period history read pattern: all periods for one billing
-- configuration within one tenant, oldest/newest ordered by
-- effective_from.
CREATE INDEX idx_course_billing_period_tenant_configuration_effective_from
    ON course_billing_period (tenant_id, billing_configuration_id, effective_from);
