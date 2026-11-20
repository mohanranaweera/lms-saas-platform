-- MSG-PROV-01 / ADR-024: real SMS (Text.lk) and WhatsApp (Meta Cloud API)
-- providers, the tenant-level WhatsApp template language, the per-tenant Meta
-- webhook, and provider delivery status on notification_delivery.
--
-- Purely additive. V1-V68 are untouched; no existing row changes (every
-- existing account is SANDBOX with a null template_language, and every
-- existing delivery gets null provider status columns).

-- 1. New providers (ADR-024 V1). SANDBOX stays local/test only (app config).
ALTER TABLE tenant_messaging_account DROP CONSTRAINT ck_tenant_messaging_account_provider;
ALTER TABLE tenant_messaging_account
    ADD CONSTRAINT ck_tenant_messaging_account_provider
        CHECK (provider IN ('SANDBOX', 'TEXTLK', 'META_WHATSAPP'));

-- 2. Tenant-level WhatsApp template language (ADR-024 V3): required for
-- META_WHATSAPP, absent for every other provider. Not a secret.
ALTER TABLE tenant_messaging_account ADD COLUMN template_language VARCHAR(15);
ALTER TABLE tenant_messaging_account
    ADD CONSTRAINT ck_tenant_messaging_account_template_language CHECK (
        (provider = 'META_WHATSAPP' AND template_language ~ '^[a-z]{2,3}(_[A-Z]{2})?$')
        OR (provider <> 'META_WHATSAPP' AND template_language IS NULL)
    );

-- 3. Per-tenant Meta webhook (ADR-024 V8). One stable row per tenant and
-- provider, kept when credentials are replaced so the URL pasted into the
-- tenant's Meta app keeps working. webhook_key is the unguessable path id
-- that selects the tenant (globally unique); verify_token answers Meta's
-- subscription handshake. Authenticity of status callbacks is the HMAC with
-- the account's write-only App Secret, not these values.
CREATE TABLE tenant_messaging_webhook (
    id              UUID PRIMARY KEY,
    tenant_id       UUID NOT NULL REFERENCES tenant (id),
    provider        VARCHAR(20) NOT NULL,
    webhook_key     VARCHAR(64) NOT NULL,
    verify_token    VARCHAR(64) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL,
    created_by      UUID,
    updated_by      UUID,

    CONSTRAINT uq_tenant_messaging_webhook_tenant_provider UNIQUE (tenant_id, provider),
    CONSTRAINT uq_tenant_messaging_webhook_key UNIQUE (webhook_key),
    CONSTRAINT ck_tenant_messaging_webhook_provider CHECK (provider IN ('META_WHATSAPP')),
    CONSTRAINT ck_tenant_messaging_webhook_key_length CHECK (length(webhook_key) >= 32),
    CONSTRAINT ck_tenant_messaging_webhook_token_length CHECK (length(verify_token) >= 32)
);

-- 4. Provider delivery status (ADR-024 V8). Separate from the dispatch
-- lifecycle `status`, which these columns never change. Only moves forward:
-- SENT -> DELIVERED -> READ, or FAILED from SENT/DELIVERED (enforced in the
-- service; the CHECK keeps the values and their pairing honest).
ALTER TABLE notification_delivery ADD COLUMN provider_status VARCHAR(10);
ALTER TABLE notification_delivery ADD COLUMN provider_status_at TIMESTAMPTZ;
ALTER TABLE notification_delivery ADD COLUMN provider_error_code VARCHAR(20);
ALTER TABLE notification_delivery
    ADD CONSTRAINT ck_notification_delivery_provider_status CHECK (
        (provider_status IS NULL AND provider_status_at IS NULL AND provider_error_code IS NULL)
        OR (provider_status IN ('SENT', 'DELIVERED', 'READ') AND provider_status_at IS NOT NULL
            AND provider_error_code IS NULL)
        OR (provider_status = 'FAILED' AND provider_status_at IS NOT NULL)
    );

-- Webhook lookup: a status callback names the provider message id; always
-- scoped by the tenant the webhook key resolved to.
CREATE INDEX idx_notification_delivery_tenant_provider_message
    ON notification_delivery (tenant_id, provider_message_id)
    WHERE provider_message_id IS NOT NULL;
