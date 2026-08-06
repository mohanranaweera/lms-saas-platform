-- Identity & Access Service: `tenant_user` table.
--
-- Tenant-owned table: Tenant Admin, staff sub-roles, Teacher, and Student
-- accounts all live here, scoped by `tenant_id`. `role` is a coarse,
-- CHECK-constrained value only (RBAC-1's permission catalog/staff sub-role
-- breakdown is a later module and is not modeled here).
--
-- `UNIQUE (tenant_id, email)` - login/email uniqueness is per-tenant, never
-- global (per .claude/rules/tenancy.md - two tenants may have staff/students
-- sharing the same email).
--
-- `UNIQUE (tenant_id, id)` is in addition to the `id` primary key: it lets
-- `device_session` (V5) express a composite FK of
-- `(tenant_id, user_id) REFERENCES tenant_user(tenant_id, id)`, so a device
-- session referencing a different tenant's user is a constraint violation,
-- not just a service-layer bug (per docs/architecture/database-architecture.md
-- §1 and .claude/rules/tenancy.md).
--
-- `totp_secret` is reserved and unused - MFA enrollment/verification is not
-- built in this module (ADR-007 §4).
--
-- `id` is generated application-side (UUIDv7), per V1's baseline convention.

CREATE TABLE tenant_user (
    id                    UUID PRIMARY KEY,
    tenant_id             UUID NOT NULL REFERENCES tenant (id),
    email                 VARCHAR NOT NULL,
    password_hash         VARCHAR NOT NULL,
    role                  VARCHAR NOT NULL
                          CHECK (role IN ('TENANT_ADMIN', 'STAFF', 'TEACHER', 'STUDENT')),
    status                VARCHAR NOT NULL DEFAULT 'active'
                          CHECK (status IN ('active', 'suspended')),
    must_change_password  BOOLEAN NOT NULL DEFAULT false,
    totp_secret           VARCHAR NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_tenant_user_tenant_email UNIQUE (tenant_id, email),
    CONSTRAINT uq_tenant_user_tenant_id UNIQUE (tenant_id, id)
);

-- Composite index leading with `tenant_id`, shaped to the module's actual
-- tenant-scoped query pattern (lookup by id within a tenant, and login
-- lookup by email within a tenant). The two UNIQUE constraints above already
-- provide backing btree indexes on (tenant_id, id) and (tenant_id, email),
-- satisfying the tenant-leading-index requirement for this table.
