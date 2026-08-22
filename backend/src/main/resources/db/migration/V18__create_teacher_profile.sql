-- user-management (MVP-007 Teacher Management): `teacher_profile` table.
--
-- Owned by `user-management` (package com.lms.usermanagement.teacher), per
-- .claude/rules/architecture.md's "one table, one owning domain" rule and
-- docs/architecture/database-architecture.md - this migration does NOT add
-- any column to `tenant_user` (that table is owned by
-- identity-access-service, see V3__create_tenant_user.sql).
--
-- This table holds ONLY the profile/approval data `tenant_user` does not
-- carry. Deliberately does NOT model:
--   * `email`  - already on tenant_user.email (V3), unique per tenant.
--   * `role`   - already on tenant_user.role, CHECK-constrained to include
--                'TEACHER' (V3), and cross-referenced in the platform-global
--                display catalog (V7). Read directly from tenant_user; never
--                duplicated here.
--   * a login-blocking/active status column - the login gate itself
--                deliberately reuses tenant_user.status (`active`/
--                `suspended`, V3), the same pattern staff_profile (V10)
--                already established, rather than inventing a second status
--                concept here. Application-layer flow (not this migration):
--                a freshly-provisioned teacher's tenant_user row is
--                transitioned to `status = 'suspended'` immediately after
--                provisioning, blocking login until reviewed, then back to
--                `'active'` on approval. This table's `approval_status`
--                column is the domain-meaningful "why" (pending/approved/
--                rejected review outcome); tenant_user.status is purely the
--                login-gate mechanism. Two different concerns, intentionally
--                not collapsed into one column.
--
-- This is deliberately its own table, not folded into staff_profile (V10):
-- teacher and staff are different aggregates with different lifecycle
-- fields (staff has no approval workflow; teacher does), even though both
-- reference tenant_user via the same composite-FK shape.
--
-- `id` has no DB-side DEFAULT - generated application-side (UUIDv7 via
-- com.lms.common.persistence.UuidV7Generator), per V1's baseline convention
-- (same as staff_profile, V10).
--
-- `user_id` + the composite FK `(tenant_id, user_id) REFERENCES
-- tenant_user (tenant_id, id)`: NOT a bare FK on `user_id` alone. This is
-- the pattern docs/architecture/database-architecture.md requires (and
-- V10 already applies for staff_profile) so a `teacher_profile` row can
-- never reference a `tenant_user` belonging to a different tenant than its
-- own `tenant_id` - confirmed against the real applied schema that
-- `tenant_user` already carries `CONSTRAINT uq_tenant_user_tenant_id
-- UNIQUE (tenant_id, id)` (V3, line 39), so this composite FK is valid to
-- add without touching V3.
--
-- `UNIQUE (tenant_id, user_id)` is this table's own natural key (one
-- teacher profile per credential per tenant) - distinct from, and in
-- addition to, the FK above, which only constrains referential integrity,
-- not cardinality.
--
-- `approved_by` uses the SAME composite-FK pattern:
-- `(tenant_id, approved_by) REFERENCES tenant_user (tenant_id, id)`, not a
-- bare FK on `approved_by` alone. This is required so a reviewer (Tenant
-- Admin or other authorized staff) from a different tenant can never be
-- recorded as the approver/rejecter of this tenant's teacher, per the
-- same-tenant cross-referencing rule in
-- docs/architecture/database-architecture.md. This composite FK is valid
-- for the same reason as the `user_id` one above: it targets V3's
-- `uq_tenant_user_tenant_id UNIQUE (tenant_id, id)`. `approved_by` is
-- nullable (NULL while `approval_status = 'PENDING'`).
--
-- `approved_by`/`approved_at` are populated on BOTH the `APPROVED` and the
-- `REJECTED` transition (recording who made the decision and when) - not
-- only on approval, despite the column names reading approval-specific.
-- This migration does not enforce that pairing (approval_status <->
-- approved_by/approved_at consistency) via CHECK, since 'PENDING' rows
-- legitimately have both columns NULL and there is no portable way to
-- express "NULL iff PENDING" without also constraining application-set
-- timing; that invariant is a service-layer responsibility.
--
-- `created_at`/`updated_at`/`created_by`/`updated_by` follow the Auditable
-- convention (com.lms.common.persistence.Auditable): no DB-side DEFAULT,
-- set by Hibernate's AuditingEntityListener, matching staff_profile (V10)
-- and V2__create_tenant_table.sql's audit-column shape.

CREATE TABLE teacher_profile (
    id               UUID PRIMARY KEY,
    tenant_id        UUID NOT NULL REFERENCES tenant (id),
    user_id          UUID NOT NULL,
    name             VARCHAR(255) NOT NULL,
    approval_status  VARCHAR NOT NULL DEFAULT 'PENDING'
                     CHECK (approval_status IN ('PENDING', 'APPROVED', 'REJECTED')),
    approved_by      UUID NULL,
    approved_at      TIMESTAMPTZ NULL,
    created_at       TIMESTAMPTZ NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL,
    created_by       UUID,
    updated_by       UUID,

    CONSTRAINT uq_teacher_profile_tenant_user UNIQUE (tenant_id, user_id),
    CONSTRAINT fk_teacher_profile_tenant_user FOREIGN KEY (tenant_id, user_id)
        REFERENCES tenant_user (tenant_id, id),
    CONSTRAINT fk_teacher_profile_approved_by FOREIGN KEY (tenant_id, approved_by)
        REFERENCES tenant_user (tenant_id, id)
);

-- Composite indexes leading with `tenant_id`, matching this module's actual
-- tenant-scoped query shapes:
--   * (tenant_id, id)              - TenantAwareRepository lookup-by-id shape.
--     (This table's PRIMARY KEY is a bare `id`, not a composite including
--     `tenant_id`, so - unlike tenant_user (V3), which relies on its own
--     UNIQUE(tenant_id, id) backing index - this index must be created
--     explicitly here, same as staff_profile, V10.)
--   * (tenant_id, approval_status) - the approval-queue ("pending teachers")
--     list query named explicitly in the approved MVP-007 plan.
CREATE INDEX idx_teacher_profile_tenant_id ON teacher_profile (tenant_id, id);
CREATE INDEX idx_teacher_profile_tenant_status ON teacher_profile (tenant_id, approval_status);
