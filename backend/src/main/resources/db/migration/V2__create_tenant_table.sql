-- tenant-management: root identity table.
--
-- `tenant` is the platform's ROOT identity table, not a tenant-owned table.
-- It intentionally has no `tenant_id` column on itself - a tenant row cannot
-- belong to a tenant. Its JPA repository must be a plain `JpaRepository`,
-- never a `TenantAwareRepository` (see .claude/rules/backend.md "Tenant
-- filtering must be structural, not manual" - that base type exists for
-- tenant-owned tables only, and applying it here would be a category error).
-- Every other domain table in this codebase depends on this table via
-- `tenant_id UUID NOT NULL REFERENCES tenant(id)`, per the conventions
-- recorded in V1__baseline_conventions.sql.
--
-- `subdomain`'s UNIQUE constraint is the one legitimate *global* unique
-- constraint in this codebase: subdomains are a platform-wide DNS/routing
-- namespace, not a per-tenant concept, so it is correctly a bare
-- `UNIQUE (subdomain)` rather than `UNIQUE (tenant_id, subdomain)`. See
-- docs/architecture/database-architecture.md SS1 for the general "unique
-- constraints scoped per tenant" rule and why this table is the documented
-- exception.
--
-- `id`, `created_at`, and `updated_at` have no DB-side DEFAULT: identifiers
-- are generated application-side (UUIDv7, see V1's note on
-- com.lms.common.persistence.UuidV7Generator), and audit timestamps are set
-- by Hibernate's AuditingEntityListener. This mirrors the audit-column shape
-- already used by backend/src/test/resources/db/migration-test/
-- V9001__test_fixture_entity.sql (id / tenant_id / created_at / updated_at /
-- created_by / updated_by), minus tenant_id for the reason above.
--
-- `status` includes `pending_approval` and `rejected` in addition to the
-- original 4-value trial/active/suspended/cancelled list. This resolves a
-- contradiction documented in `docs/plans/MVP-004 Tenant Management.md`
-- SS21 item 1 (tenant self-registration/approval workflow needs pre-trial
-- and rejected-application states); see that section for full rationale.
--
-- `requested_plan` is a free-text VARCHAR, not a foreign key, because no
-- subscription-plan catalog table exists yet anywhere in this codebase -
-- Module D / the Feature Flag & Plan Limit Engine is explicitly unratified
-- per docs/requirements/module-catalog.md. Do not add a CHECK constraint or
-- FK against plan tier names until that catalog table is approved and
-- shipped; doing so here would invent a contract this migration has no
-- authority to define.
CREATE TABLE tenant (
    id              UUID PRIMARY KEY,
    name            VARCHAR(255) NOT NULL,
    subdomain       VARCHAR(63)  NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    requested_plan  VARCHAR(100) NOT NULL,
    contact_name    VARCHAR(255) NOT NULL,
    contact_email   VARCHAR(255) NOT NULL,
    contact_phone   VARCHAR(50),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by      UUID,
    updated_by      UUID,

    CONSTRAINT uq_tenant_subdomain UNIQUE (subdomain),

    CONSTRAINT ck_tenant_status CHECK (
        status IN ('pending_approval', 'trial', 'active', 'suspended', 'cancelled', 'rejected')
    ),

    CONSTRAINT ck_tenant_subdomain_format CHECK (
        subdomain ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'
    )
);

CREATE INDEX idx_tenant_status_created_at ON tenant (status, created_at DESC);
