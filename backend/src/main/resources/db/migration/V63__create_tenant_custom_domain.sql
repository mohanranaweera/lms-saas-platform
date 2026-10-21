-- tenant-management (TEN-DOM-01, ADR-021): tenant custom domains.
--
-- Additive; V1-V62 are untouched.
--   * tenant.custom_domains_enabled - the Platform Admin switch. Off by
--     default; while off, the tenant's domains neither resolve nor appear in
--     links.
--   * tenant_custom_domain - a hostname a tenant has claimed. It resolves to
--     the tenant only while ACTIVE (DNS ownership proven). Rows are never
--     deleted: removal is status REMOVED, so the history stays.

ALTER TABLE tenant ADD COLUMN custom_domains_enabled BOOLEAN NOT NULL DEFAULT false;

CREATE TABLE tenant_custom_domain (
    id                 UUID PRIMARY KEY,
    tenant_id          UUID NOT NULL REFERENCES tenant (id),
    hostname           VARCHAR(253) NOT NULL,
    status             VARCHAR(25) NOT NULL,
    -- Expected in the TXT record _lms-verify.<hostname>. Proves DNS control;
    -- not a credential.
    verification_code  VARCHAR(64) NOT NULL,
    is_primary         BOOLEAN NOT NULL DEFAULT false,
    verified_at        TIMESTAMPTZ,
    last_checked_at    TIMESTAMPTZ,
    last_error         VARCHAR(500),
    suspended_reason   VARCHAR(500),
    created_at         TIMESTAMPTZ NOT NULL,
    updated_at         TIMESTAMPTZ NOT NULL,
    created_by         UUID,
    updated_by         UUID,

    CONSTRAINT uq_tenant_custom_domain_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT ck_tenant_custom_domain_status CHECK (status IN (
        'PENDING_VERIFICATION', 'ACTIVE', 'DNS_BROKEN', 'SUSPENDED', 'REMOVED')),
    CONSTRAINT ck_tenant_custom_domain_hostname_lower CHECK (hostname = lower(hostname)),
    -- Only a verified domain can be ACTIVE.
    CONSTRAINT ck_tenant_custom_domain_active_verified CHECK (status <> 'ACTIVE' OR verified_at IS NOT NULL),
    -- A primary keeps its flag while DNS_BROKEN (restored with the records);
    -- suspension and removal clear it.
    CONSTRAINT ck_tenant_custom_domain_primary_status CHECK (NOT is_primary OR status IN ('ACTIVE', 'DNS_BROKEN')),
    CONSTRAINT ck_tenant_custom_domain_suspended_reason CHECK (status <> 'SUSPENDED' OR suspended_reason IS NOT NULL)
);

-- A hostname belongs to at most one tenant at a time, across the platform.
-- A REMOVED row frees it.
CREATE UNIQUE INDEX uq_tenant_custom_domain_hostname
    ON tenant_custom_domain (hostname)
    WHERE status <> 'REMOVED';

-- At most one primary domain per tenant.
CREATE UNIQUE INDEX uq_tenant_custom_domain_primary
    ON tenant_custom_domain (tenant_id)
    WHERE is_primary;

-- The tenant's own list and the primary-origin lookup.
CREATE INDEX idx_tenant_custom_domain_tenant_status
    ON tenant_custom_domain (tenant_id, status);
