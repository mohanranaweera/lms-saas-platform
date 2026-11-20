-- integration-management (Wave 11, docs/parity/waves/wave-11-plan.md §2.3):
-- a tenant's own SMS / WhatsApp provider account.
--
-- Purely additive: one new table. V1-V65 are untouched.
--
-- credentials_ciphertext is an AES-256-GCM encrypted JSON blob (provider API
-- key/secret) under the integrations key (MEDIA_CREDENTIALS_ENCRYPTION_KEY,
-- wave-11-plan.md judgment call J1), bound by AAD to
-- messaging:{tenant}:{channel}:{id}. No plaintext secret is ever stored, and
-- no API ever returns it. credential_hint is the masked display value only.
--
-- Rows are never deleted: notification_delivery.provider_account_id
-- references the account that sent a message, so replacing credentials marks
-- the old row DISABLED and inserts a new ACTIVE one.
--
-- provider is CHECK-constrained to SANDBOX only (wave-11-plan.md decision
-- D1): adding a real vendor is a new ADR plus a new migration widening this
-- CHECK, never a silent value.
CREATE TABLE tenant_messaging_account (
    id                      UUID PRIMARY KEY,
    tenant_id               UUID NOT NULL REFERENCES tenant (id),
    channel                 VARCHAR(10) NOT NULL,
    provider                VARCHAR(20) NOT NULL,
    sender_id               VARCHAR(40) NOT NULL,
    credentials_ciphertext  TEXT NOT NULL,
    credential_hint         VARCHAR(16) NOT NULL,
    status                  VARCHAR(10) NOT NULL,
    disabled_at             TIMESTAMPTZ,
    disabled_by_user_id     UUID,
    created_at              TIMESTAMPTZ NOT NULL,
    updated_at              TIMESTAMPTZ NOT NULL,
    created_by              UUID,
    updated_by              UUID,

    CONSTRAINT uq_tenant_messaging_account_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_tenant_messaging_account_disabled_by
        FOREIGN KEY (tenant_id, disabled_by_user_id) REFERENCES tenant_user (tenant_id, id),
    CONSTRAINT ck_tenant_messaging_account_channel CHECK (channel IN ('SMS', 'WHATSAPP')),
    CONSTRAINT ck_tenant_messaging_account_provider CHECK (provider IN ('SANDBOX')),
    CONSTRAINT ck_tenant_messaging_account_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT ck_tenant_messaging_account_sender_not_blank CHECK (btrim(sender_id) <> ''),
    CONSTRAINT ck_tenant_messaging_account_disabled_consistency CHECK (
        (status = 'ACTIVE' AND disabled_at IS NULL)
        OR (status = 'DISABLED' AND disabled_at IS NOT NULL)
    )
);

-- At most one ACTIVE account per tenant per channel.
CREATE UNIQUE INDEX uq_tenant_messaging_account_active
    ON tenant_messaging_account (tenant_id, channel)
    WHERE status = 'ACTIVE';

-- Settings page / dispatch lookup.
CREATE INDEX idx_tenant_messaging_account_tenant_channel_created
    ON tenant_messaging_account (tenant_id, channel, created_at DESC);
