-- VIDEO Phase 5c (ADR-018 Amendment 5, revised by the owner on 2026-10-05):
-- an institute may use its OWN Google OAuth app for its YouTube channel,
-- entered by its Tenant Admin under Settings -> Video providers. Without one,
-- the platform app (platform_oauth_app, V74) or the environment variables
-- apply.
--
-- Tenant-owned: tenant_id NOT NULL + FK, at most one row per tenant and
-- provider, index leading with tenant_id (the unique constraint). The secret
-- is AES-256-GCM ciphertext (MEDIA_CREDENTIALS_ENCRYPTION_KEY) whose AAD is
-- bound to the tenant and provider, so a value copied into another tenant's
-- row cannot be decrypted there. It is never returned by any API. Changes are
-- audited in audit_log (tenant-scoped); removing the row falls back to the
-- platform app.

CREATE TABLE tenant_oauth_app (
    id                        UUID PRIMARY KEY,
    tenant_id                 UUID         NOT NULL,
    provider                  VARCHAR(20)  NOT NULL,
    client_id                 VARCHAR(255) NOT NULL,
    client_secret_ciphertext  TEXT         NOT NULL,
    created_at                TIMESTAMPTZ  NOT NULL,
    updated_at                TIMESTAMPTZ  NOT NULL,
    created_by                UUID,
    updated_by                UUID,
    CONSTRAINT fk_tenant_oauth_app_tenant FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT uq_tenant_oauth_app_provider UNIQUE (tenant_id, provider),
    CONSTRAINT ck_tenant_oauth_app_provider CHECK (provider IN ('GOOGLE'))
);
