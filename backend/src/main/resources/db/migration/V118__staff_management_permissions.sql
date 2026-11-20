-- identity-access-service + user-management (STAFF-MGMT-02 Klass staff management & configurable
-- role permissions - docs/specs/staff-management-permissions-parity.md §2.4, ADR-034).
--
-- Purely additive. No earlier migration is edited. Every new table is tenant-owned:
-- tenant_id NOT NULL FK to tenant, tenant-leading indexes, per-tenant unique constraints.

-- ---------------------------------------------------------------------------
-- 1. Role catalog (V7): two new tenant roles whose permissions are tenant-configurable
--    grants (they hold nothing by default). Same portal as the other staff sub-roles.
-- ---------------------------------------------------------------------------

INSERT INTO role (code, scope, display_name, portal_route_group, self_registers, is_provisional, sort_order) VALUES
    ('OPERATOR', 'TENANT', 'Operator', 'app/(tenant-admin)/', false, false, 85),
    ('MANAGER',  'TENANT', 'Manager',  'app/(tenant-admin)/', false, false, 86);

-- ---------------------------------------------------------------------------
-- 2. staff_profile (V10): Klass "Add Staff" form fields. `name` stays NOT NULL and is
--    maintained by the application as "first last" so existing readers keep working.
--    Nullable so rows written before this migration (or by raw SQL) stay valid; the
--    backfill splits `name` on its first whitespace run (same rule as V117 for students).
-- ---------------------------------------------------------------------------

ALTER TABLE staff_profile
    ADD COLUMN first_name     VARCHAR(120),
    ADD COLUMN last_name      VARCHAR(120),
    ADD COLUMN contact_number VARCHAR(32);

UPDATE staff_profile
SET first_name = left(regexp_replace(regexp_replace(name, '^\s+|\s+$', '', 'g'), '\s.*$', ''), 120),
    last_name  = NULLIF(left(regexp_replace(regexp_replace(name, '^\s+|\s+$', '', 'g'), '^\S+\s*', ''), 120), '');

-- ---------------------------------------------------------------------------
-- 3. tenant_role_capability_grant: a tenant's explicit grant/revoke of one capability
--    (identityaccessservice.api.Capability key) for one configurable role. An absent row
--    means the catalog default (Operator/Manager: not granted; Teacher: granted).
--    Only OPERATOR, MANAGER and TEACHER are configurable; TENANT_ADMIN and the legacy
--    staff sub-roles keep the static code matrix.
-- ---------------------------------------------------------------------------

CREATE TABLE tenant_role_capability_grant (
    id              UUID PRIMARY KEY,
    tenant_id       UUID NOT NULL REFERENCES tenant (id),
    role_code       VARCHAR NOT NULL REFERENCES role (code),
    capability_key  VARCHAR(100) NOT NULL,
    granted         BOOLEAN NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by      UUID,

    CONSTRAINT uq_tenant_role_capability_grant UNIQUE (tenant_id, role_code, capability_key),
    CONSTRAINT chk_tenant_role_capability_grant_role CHECK (role_code IN ('OPERATOR', 'MANAGER', 'TEACHER'))
);

-- The unique constraint above already provides the (tenant_id, role_code, capability_key)
-- index used by the per-request "grants for my role" read; no further index is needed.

-- ---------------------------------------------------------------------------
-- 4. tenant_role_permission_version: optimistic-concurrency counter for the tenant's
--    permission matrix. A save is `UPDATE ... SET version = version + 1 WHERE tenant_id = ?
--    AND version = :expected`; zero rows updated = stale editor (HTTP 409 PERMISSIONS_STALE).
--    Absent row = version 0 (created on first save with INSERT ... ON CONFLICT DO NOTHING).
-- ---------------------------------------------------------------------------

CREATE TABLE tenant_role_permission_version (
    tenant_id   UUID PRIMARY KEY REFERENCES tenant (id),
    version     BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by  UUID
);
