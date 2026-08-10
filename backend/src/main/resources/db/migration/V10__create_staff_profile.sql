-- user-management (MVP-005 Staff Management): `staff_profile` table.
--
-- Owned by `user-management` (package com.lms.usermanagement.staff), per
-- .claude/rules/architecture.md's "one table, one owning domain" rule and
-- docs/architecture/database-architecture.md - this migration does NOT add
-- any column to `tenant_user` (that table is owned by
-- identity-access-service, see V3__create_tenant_user.sql).
--
-- This table holds ONLY the profile data `tenant_user` does not carry.
-- Deliberately does NOT model:
--   * `email`    - already on tenant_user.email (V3), unique per tenant.
--   * `role`     - already on tenant_user.role, FK'd to the `role` catalog
--                  (V7/V8). Read directly from tenant_user; never duplicated
--                  here, to avoid two independently-maintained values that
--                  could drift.
--   * `status`   - tenant_user.status (`active`/`suspended`, V3) already
--                  models the account-level active/suspended state a staff
--                  member needs. Reusing it here is a deliberate, grounded
--                  decision, not a gap: it resolves the "no defined staff
--                  status state machine" open question for the
--                  suspend/active case by reusing what
--                  identity-access-service already owns. A `removed`/
--                  hard-delete concept remains genuinely undecided and is
--                  out of scope for this migration - do not invent a
--                  `removed` state here.
--
-- `id` has no DB-side DEFAULT - generated application-side (UUIDv7 via
-- com.lms.common.persistence.UuidV7Generator), per V1's baseline convention.
--
-- `user_id` + the composite FK `(tenant_id, user_id) REFERENCES
-- tenant_user (tenant_id, id)`: NOT a bare FK on `user_id` alone. This is
-- the pattern docs/architecture/database-architecture.md SS1 requires so a
-- `staff_profile` row can never reference a `tenant_user` belonging to a
-- different tenant than its own `tenant_id` - confirmed against the real
-- applied schema that `tenant_user` already carries
-- `CONSTRAINT uq_tenant_user_tenant_id UNIQUE (tenant_id, id)` (V3, line 39),
-- so this composite FK is valid to add without touching V3.
--
-- `UNIQUE (tenant_id, user_id)` is this table's own natural key (one staff
-- profile per credential per tenant) - distinct from, and in addition to,
-- the composite FK above, which only constrains referential integrity, not
-- cardinality.
--
-- `created_at`/`updated_at`/`created_by`/`updated_by` follow the Auditable
-- convention (com.lms.common.persistence.Auditable): no DB-side DEFAULT,
-- set by Hibernate's AuditingEntityListener, matching
-- V2__create_tenant_table.sql's audit-column shape.

CREATE TABLE staff_profile (
    id          UUID PRIMARY KEY,
    tenant_id   UUID NOT NULL REFERENCES tenant (id),
    user_id     UUID NOT NULL,
    name        VARCHAR(255) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL,
    created_by  UUID,
    updated_by  UUID,

    CONSTRAINT uq_staff_profile_tenant_user UNIQUE (tenant_id, user_id),

    CONSTRAINT fk_staff_profile_tenant_user FOREIGN KEY (tenant_id, user_id)
        REFERENCES tenant_user (tenant_id, id)
);

-- Composite index leading with `tenant_id`, shaped to this module's actual
-- tenant-scoped query pattern (staff list for a tenant, and tenant-scoped
-- lookup by this table's own id - the shape TenantAwareRepository needs).
-- Unlike tenant_user (V3), this table's PRIMARY KEY is a bare `id`, not a
-- composite including `tenant_id`, so - unlike V3, which could rely on its
-- UNIQUE(tenant_id, id) backing index - this index must be created
-- explicitly here. The separate `UNIQUE (tenant_id, user_id)` constraint
-- above already provides the backing index for the "lookup by credential
-- id" shape, so no further index is added on top of these two.
CREATE INDEX idx_staff_profile_tenant_id ON staff_profile (tenant_id, id);
