-- identity-access-service (AUTH-PWD-01, ADR-020): first Tenant Admin
-- invitation, forgot password and change password.
--
-- Additive; V1-V61 are untouched. Re-creates two CHECK constraints under the
-- same meaning plus one new value each:
--   * tenant_user.status gains 'invited' (V3's inline check was auto-named
--     tenant_user_status_check). An invited account has an unusable password
--     and cannot log in until it completes its account-setup link.
--   * notification_outbox.event_type gains ACCOUNT_SETUP_INVITE and
--     PASSWORD_RESET (V28's ck_notification_outbox_event_type).

ALTER TABLE tenant_user DROP CONSTRAINT tenant_user_status_check;
ALTER TABLE tenant_user
    ADD CONSTRAINT ck_tenant_user_status CHECK (status IN ('active', 'suspended', 'invited'));

-- When the password was last set/changed by its owner (setup, reset, change).
ALTER TABLE tenant_user ADD COLUMN password_changed_at TIMESTAMPTZ;

ALTER TABLE notification_outbox DROP CONSTRAINT ck_notification_outbox_event_type;
ALTER TABLE notification_outbox
    ADD CONSTRAINT ck_notification_outbox_event_type CHECK (event_type IN (
        'PAYMENT_CONFIRMED', 'PAYMENT_REJECTED', 'PAYMENT_REFUNDED',
        'ACCOUNT_SETUP_INVITE', 'PASSWORD_RESET'));

-- ---------------------------------------------------------------------------
-- password_token: one-time account-setup / password-reset tokens
-- ---------------------------------------------------------------------------
-- Only the SHA-256 hash of the 256-bit random token is stored; the raw value
-- exists only in the email. A token is usable once (used_at) and can be
-- superseded (invalidated_at) - never both. Rows are kept as a security
-- record, never deleted.
CREATE TABLE password_token (
    id              UUID PRIMARY KEY,
    tenant_id       UUID NOT NULL REFERENCES tenant (id),
    user_id         UUID NOT NULL,
    purpose         VARCHAR(20) NOT NULL,
    token_hash      CHAR(64) NOT NULL,
    expires_at      TIMESTAMPTZ NOT NULL,
    used_at         TIMESTAMPTZ,
    invalidated_at  TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL,
    created_by      UUID,

    CONSTRAINT uq_password_token_tenant_id UNIQUE (tenant_id, id),
    -- Hash of 256 random bits: unique by construction. Lookups still filter by
    -- the request's tenant.
    CONSTRAINT uq_password_token_hash UNIQUE (token_hash),
    -- The user must belong to the same tenant.
    CONSTRAINT fk_password_token_user
        FOREIGN KEY (tenant_id, user_id) REFERENCES tenant_user (tenant_id, id),
    CONSTRAINT ck_password_token_purpose CHECK (purpose IN ('ACCOUNT_SETUP', 'PASSWORD_RESET')),
    CONSTRAINT ck_password_token_single_end CHECK (used_at IS NULL OR invalidated_at IS NULL),
    CONSTRAINT ck_password_token_expiry_after_creation CHECK (expires_at > created_at)
);

-- Open tokens of a user (invalidate on reissue) and the admin panel's
-- "invited at / expires at".
CREATE INDEX idx_password_token_tenant_user
    ON password_token (tenant_id, user_id, purpose, created_at);
