-- user-management (MVP-006 Student Management): `student_profile` table.
--
-- Owned by `user-management` (package com.lms.usermanagement.student), per
-- .claude/rules/architecture.md's "one table, one owning domain" rule and
-- docs/architecture/database-architecture.md - this migration does NOT add
-- any column to `tenant_user` (that table is owned by
-- identity-access-service, see V3__create_tenant_user.sql). Same pattern as
-- `staff_profile` (V10, MVP-005): auth/account fields (`email`,
-- `password_hash`, `role`, `status`, `must_change_password`) stay on
-- `tenant_user`.
--
-- This table holds ONLY the profile data `tenant_user` does not carry.
-- Deliberately does NOT model:
--   * `email`    - already on tenant_user.email (V3), unique per tenant.
--   * `role`     - already on tenant_user.role (`STUDENT` value), FK'd to
--                  the `role` catalog (V7/V8). Read directly from
--                  tenant_user; never duplicated here.
--   * `status`   - deliberately NOT modeled here, even though an early
--                  backlog draft suggested one. tenant_user.status
--                  (`active`/`suspended`, V3) already models account-level
--                  state, and duplicating it risks drift between two
--                  independently-maintained values. This mirrors V10's
--                  identical reasoning for omitting `status` on
--                  `staff_profile`. This is a reasoned, documented
--                  deviation from that early draft note, not an oversight -
--                  see docs/plans/MVP-006 Student Management.md §8.1.
--
-- Guardian/school/grade/stream fields are deliberately NOT modeled here -
-- the exact field list is an open, unresolved business decision (see
-- docs/requirements/open-decisions.md and
-- docs/plans/MVP-006 Student Management.md §21 item 3). Adding them is a
-- future, separate migration once ratified - never retrofitted into this
-- one, since migration history is append-only.
--
-- `id` has no DB-side DEFAULT - generated application-side (UUIDv7 via
-- com.lms.common.persistence.UuidV7Generator), per V1's baseline convention.
--
-- `user_id` + the composite FK `(tenant_id, user_id) REFERENCES
-- tenant_user (tenant_id, id)`: NOT a bare FK on `user_id` alone. This is
-- the same same-tenant-enforcement pattern V10 uses for `staff_profile`,
-- backed by `tenant_user`'s existing
-- `CONSTRAINT uq_tenant_user_tenant_id UNIQUE (tenant_id, id)` (V3, no
-- change needed there), so a `student_profile` row can never reference a
-- `tenant_user` belonging to a different tenant than its own `tenant_id`.
--
-- `UNIQUE (tenant_id, user_id)` is this table's own natural key (one
-- student profile per credential per tenant) - distinct from, and in
-- addition to, the composite FK above, which only constrains referential
-- integrity, not cardinality.
--
-- `created_at`/`updated_at`/`created_by`/`updated_by` follow the Auditable
-- convention (com.lms.common.persistence.Auditable): no DB-side DEFAULT,
-- set by Hibernate's AuditingEntityListener, matching V10's shape exactly.

CREATE TABLE student_profile (
    id          UUID PRIMARY KEY,
    tenant_id   UUID NOT NULL REFERENCES tenant (id),
    user_id     UUID NOT NULL,
    name        VARCHAR(255) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL,
    created_by  UUID,
    updated_by  UUID,

    CONSTRAINT uq_student_profile_tenant_user UNIQUE (tenant_id, user_id),

    CONSTRAINT fk_student_profile_tenant_user FOREIGN KEY (tenant_id, user_id)
        REFERENCES tenant_user (tenant_id, id)
);

-- Composite index leading with `tenant_id`, shaped to this module's actual
-- tenant-scoped query pattern (student list for a tenant, and tenant-scoped
-- lookup by this table's own id - the shape TenantAwareRepository needs).
-- Like `staff_profile` and unlike `tenant_user`, this table's PRIMARY KEY is
-- a bare `id`, not a composite including `tenant_id`, so this index must be
-- created explicitly here. The separate `UNIQUE (tenant_id, user_id)`
-- constraint above already provides the backing index for the "lookup by
-- credential id" shape, so no further index is added on top of these two.
CREATE INDEX idx_student_profile_tenant_id ON student_profile (tenant_id, id);
