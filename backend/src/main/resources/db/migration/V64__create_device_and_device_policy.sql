-- Wave 10 (device + access policy) - identity-access-service device model.
--
-- `device`: one row per server-issued device identity (ADR-007 §2). The raw
-- device token lives only in the client's HttpOnly `lms_device_id` cookie;
-- only its SHA-256 hash is stored here. User-Agent/IP are display/review data
-- (`label`, `last_ip`), never identity. Rows are never deleted - revocation is
-- a status change, and `slot_reusable_at` is the persisted reset cooldown
-- (the slot a revoked device held cannot be taken by a NEW device before it).
--
-- `device_session.device_id`: links each session to its device. Nullable only
-- because sessions issued before this migration have no device; those are
-- refused at their next refresh (forcing one re-login that registers a device).
--
-- `tenant_device_policy`: per-tenant device settings (null max_devices = the
-- plan default from app.security.device.plan-default-limit).
--
-- `student_device_limit_override`: the most specific level of the device-limit
-- precedence (student > course > tenant > plan).
--
-- Every table is tenant-owned: NOT NULL tenant_id, tenant-leading indexes and
-- composite same-tenant FKs (.claude/rules/tenancy.md).

CREATE TABLE device (
    id                  UUID PRIMARY KEY,
    tenant_id           UUID NOT NULL REFERENCES tenant (id),
    user_id             UUID NOT NULL,
    token_hash          VARCHAR(64) NOT NULL,
    label               VARCHAR(120) NOT NULL,
    first_seen_at       TIMESTAMPTZ NOT NULL,
    last_seen_at        TIMESTAMPTZ NOT NULL,
    last_ip             VARCHAR(64) NULL,
    status              VARCHAR(16) NOT NULL DEFAULT 'active'
                        CHECK (status IN ('active', 'revoked')),
    revoked_at          TIMESTAMPTZ NULL,
    revoked_by          UUID NULL,
    revocation_source   VARCHAR(16) NULL
                        CHECK (revocation_source IN ('admin', 'student', 'system')),
    revoke_reason       VARCHAR(500) NULL,
    slot_reusable_at    TIMESTAMPTZ NULL,

    CONSTRAINT uq_device_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_device_tenant_user_token UNIQUE (tenant_id, user_id, token_hash),
    CONSTRAINT ck_device_revoked_fields
        CHECK (status <> 'revoked' OR (revoked_at IS NOT NULL AND revocation_source IS NOT NULL)),
    CONSTRAINT fk_device_tenant_user
        FOREIGN KEY (tenant_id, user_id) REFERENCES tenant_user (tenant_id, id)
);

CREATE INDEX idx_device_tenant_user_status ON device (tenant_id, user_id, status);

ALTER TABLE device_session ADD COLUMN device_id UUID NULL;

ALTER TABLE device_session
    ADD CONSTRAINT fk_device_session_device
        FOREIGN KEY (tenant_id, device_id) REFERENCES device (tenant_id, id);

CREATE INDEX idx_device_session_tenant_device ON device_session (tenant_id, device_id);

CREATE TABLE tenant_device_policy (
    id                              UUID PRIMARY KEY,
    tenant_id                       UUID NOT NULL REFERENCES tenant (id),
    max_devices                     INTEGER NULL CHECK (max_devices BETWEEN 1 AND 20),
    reset_cooldown_hours            INTEGER NOT NULL DEFAULT 24
                                    CHECK (reset_cooldown_hours BETWEEN 0 AND 720),
    student_self_service_enabled    BOOLEAN NOT NULL DEFAULT FALSE,
    updated_by                      UUID NOT NULL,
    updated_at                      TIMESTAMPTZ NOT NULL,

    CONSTRAINT uq_tenant_device_policy_tenant UNIQUE (tenant_id)
);

CREATE TABLE student_device_limit_override (
    id              UUID PRIMARY KEY,
    tenant_id       UUID NOT NULL REFERENCES tenant (id),
    user_id         UUID NOT NULL,
    max_devices     INTEGER NOT NULL CHECK (max_devices BETWEEN 1 AND 20),
    reason          VARCHAR(500) NOT NULL,
    set_by          UUID NOT NULL,
    set_at          TIMESTAMPTZ NOT NULL,

    CONSTRAINT uq_student_device_limit_override_tenant_user UNIQUE (tenant_id, user_id),
    CONSTRAINT fk_student_device_limit_override_tenant_user
        FOREIGN KEY (tenant_id, user_id) REFERENCES tenant_user (tenant_id, id)
);
