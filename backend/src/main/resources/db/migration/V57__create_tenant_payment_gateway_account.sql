-- integration-management / payment-management (PAY-GW-01, ADR-017 - payment
-- roadmap Phase 3): tenant-owned payment gateway accounts (PayHere first),
-- plus the payment/refund columns that record which account collected a
-- payment and which refunds came from a gateway chargeback.
--
-- Purely additive: one new table, nullable columns on `payment`, and a
-- defaulted column + nullable column on `payment_refund`. No existing row is
-- rewritten except `payment_refund.source` receiving its 'STAFF' default,
-- which is what every existing refund was (staff-initiated via
-- RefundService). Terminal payments and ledger rows are untouched.

-- ---------------------------------------------------------------------------
-- tenant_payment_gateway_account
-- ---------------------------------------------------------------------------
-- One row per configured merchant account. Rows are never deleted: a payment
-- references the account that collected it (payment.gateway_account_id), so
-- replacing credentials DISABLEs the old row and inserts a new one.
--
-- merchant_secret_ciphertext holds the AES-256-GCM encrypted merchant secret
-- (CredentialCipher, key from PAYMENT_CREDENTIALS_ENCRYPTION_KEY). The
-- plaintext secret is never stored or returned by any API.
--
-- disabled_by records who switched the account off: a PLATFORM switch-off
-- can only be undone by a Platform Admin (ADR-017 owner decision 4).
CREATE TABLE tenant_payment_gateway_account (
    id                          UUID PRIMARY KEY,
    tenant_id                   UUID NOT NULL REFERENCES tenant (id),
    provider                    VARCHAR(20) NOT NULL,
    mode                        VARCHAR(10) NOT NULL,
    merchant_id                 VARCHAR(64) NOT NULL,
    merchant_secret_ciphertext  TEXT NOT NULL,
    status                      VARCHAR(10) NOT NULL,
    disabled_by                 VARCHAR(10),
    disabled_reason             TEXT,
    disabled_at                 TIMESTAMPTZ,
    created_at                  TIMESTAMPTZ NOT NULL,
    updated_at                  TIMESTAMPTZ NOT NULL,
    created_by                  UUID,
    updated_by                  UUID,

    CONSTRAINT uq_tenant_payment_gateway_account_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT ck_tenant_payment_gateway_account_provider CHECK (provider IN ('PAYHERE')),
    CONSTRAINT ck_tenant_payment_gateway_account_mode CHECK (mode IN ('SANDBOX', 'LIVE')),
    CONSTRAINT ck_tenant_payment_gateway_account_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    CONSTRAINT ck_tenant_payment_gateway_account_disabled_by CHECK (
        disabled_by IS NULL OR disabled_by IN ('TENANT', 'PLATFORM')
    ),
    CONSTRAINT ck_tenant_payment_gateway_account_disabled_consistency CHECK (
        (status = 'ACTIVE' AND disabled_by IS NULL AND disabled_at IS NULL)
        OR (status = 'DISABLED' AND disabled_by IS NOT NULL AND disabled_at IS NOT NULL)
    ),
    CONSTRAINT ck_tenant_payment_gateway_account_merchant_id_not_blank CHECK (btrim(merchant_id) <> '')
);

-- At most one ACTIVE account per tenant per provider; replacing credentials
-- DISABLEs the previous row first.
CREATE UNIQUE INDEX uq_tenant_payment_gateway_account_current
    ON tenant_payment_gateway_account (tenant_id, provider)
    WHERE status = 'ACTIVE';

CREATE INDEX idx_tenant_payment_gateway_account_tenant_provider_created
    ON tenant_payment_gateway_account (tenant_id, provider, created_at);

-- ---------------------------------------------------------------------------
-- payment: who collected the money, through which account
-- ---------------------------------------------------------------------------
-- Set once while the payment is still PENDING (together with
-- gateway_reference). NULL for every pre-existing payment and for
-- slip/free/staff-granted payments. collected_by = TENANT payments are the
-- tenant's own revenue: no platform commission/settlement (ADR-017 owner
-- decision 1).
ALTER TABLE payment
    ADD COLUMN collected_by VARCHAR(10),
    ADD COLUMN gateway_account_id UUID;

ALTER TABLE payment
    ADD CONSTRAINT ck_payment_collected_by CHECK (collected_by IS NULL OR collected_by IN ('PLATFORM', 'TENANT'));

-- Composite FK keeps the referenced account inside the payment's own tenant.
ALTER TABLE payment
    ADD CONSTRAINT fk_payment_gateway_account
        FOREIGN KEY (tenant_id, gateway_account_id)
        REFERENCES tenant_payment_gateway_account (tenant_id, id);

CREATE INDEX idx_payment_tenant_gateway_account
    ON payment (tenant_id, gateway_account_id);

-- ---------------------------------------------------------------------------
-- payment_refund: chargebacks reuse the existing refund + REFUND ledger path
-- ---------------------------------------------------------------------------
-- source distinguishes a staff refund from a gateway chargeback (ADR-017
-- owner decision 2). external_reference is the gateway's own reference for
-- the chargeback notification; unique per tenant so a repeated notification
-- can never write a second refund/ledger entry.
ALTER TABLE payment_refund
    ADD COLUMN source VARCHAR(20) NOT NULL DEFAULT 'STAFF',
    ADD COLUMN external_reference VARCHAR(64);

ALTER TABLE payment_refund
    ADD CONSTRAINT ck_payment_refund_source CHECK (source IN ('STAFF', 'CHARGEBACK'));

ALTER TABLE payment_refund
    ADD CONSTRAINT ck_payment_refund_chargeback_reference CHECK (
        source <> 'CHARGEBACK' OR external_reference IS NOT NULL
    );

CREATE UNIQUE INDEX uq_payment_refund_tenant_external_reference
    ON payment_refund (tenant_id, external_reference)
    WHERE external_reference IS NOT NULL;
