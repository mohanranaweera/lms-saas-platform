-- Wave 1 - Tenant Admin IA + Configuration Framework (typed tenant
-- configuration). Owned by com.lms.tenantmanagement.domain, per
-- .claude/rules/architecture.md: configuration is an extension of the
-- `Tenant` aggregate tenant-management already owns, not a new top-level
-- domain (the confirmed domain list has no tenant-configuration-management).
--
-- One row per (tenant, config_domain, config_key) - the "current value"
-- table for the typed configuration registry (see
-- com.lms.tenantmanagement.config.ConfigPropertyRegistry). Not modeled
-- append-only: this is current-state tenant settings, not financial/audit
-- history (backend.md's append-only list is payment/ledger/audit-log only),
-- so an in-place UPDATE of `value` on the existing row for a key is the
-- correct write path - the audit trail of who-changed-what-when is captured
-- separately via audit_log (see TenantConfigService), not by keeping old
-- config_entry rows around.
--
-- `config_domain` mirrors com.lms.tenantmanagement.api.ConfigDomain's 17
-- values exactly - kept in sync by hand (a new enum constant requires a new
-- migration to widen this CHECK, per "never edit an already-shared
-- migration").

CREATE TABLE tenant_config_entry (
    id             UUID PRIMARY KEY,
    tenant_id      UUID NOT NULL REFERENCES tenant (id),
    config_domain  VARCHAR(32) NOT NULL,
    config_key     VARCHAR(100) NOT NULL,
    value          JSONB NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by     UUID,
    updated_by     UUID,

    CONSTRAINT uq_tenant_config_entry_tenant_domain_key UNIQUE (tenant_id, config_domain, config_key),
    CONSTRAINT ck_tenant_config_entry_domain CHECK (config_domain IN (
        'GENERAL', 'BRANDING', 'ACADEMIC', 'STUDENT', 'TEACHER', 'COURSE', 'PAYMENT', 'FINANCE',
        'ATTENDANCE', 'EXAM', 'CONTENT', 'VIDEO', 'NOTIFICATION', 'SECURITY', 'DEVICE', 'DOMAIN', 'INTEGRATION'))
);

-- Tenant-leading composite index matching this module's actual query shape:
-- TenantConfigService reads either "everything for one domain" (GET
-- /api/v1/tenant-config/{domain}) or, via the unique constraint above, a
-- single (tenant, domain, key) row (PUT upsert) - both are served by this
-- one index (the unique constraint's own index already covers the
-- single-key lookup as a prefix match).
CREATE INDEX idx_tenant_config_entry_tenant_domain ON tenant_config_entry (tenant_id, config_domain);
