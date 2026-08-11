-- Identity & Access Service: `device_session` table.
--
-- Tenant-owned table: one row per issued/rotated refresh-token session for a
-- `tenant_user` login. `id` doubles as the JWT `session_id` claim. This row
-- is also this module's login-activity record (see plan §16) - no separate
-- audit-log-management event/table is needed for login itself.
--
-- Same-tenant enforcement: `user_id` is joined via a composite
-- `FOREIGN KEY (tenant_id, user_id) REFERENCES tenant_user (tenant_id, id)`
-- against tenant_user's `UNIQUE (tenant_id, id)` constraint (V3) - not a
-- bare FK to `tenant_user(id)` alone - so a session row referencing a
-- different tenant's user is rejected by the database, not just caught in
-- code review (per docs/architecture/database-architecture.md §1).
--
-- Rotation/revocation: this table is NOT an append-only domain (only
-- ledger/audit/payment tables are, per docs/architecture/database-
-- architecture.md §3). Refresh-token rotation is an in-place UPDATE of
-- `refresh_token_hash`/`last_rotated_at` on the existing row; combined with
-- `UNIQUE (refresh_token_hash)`, a replayed stale hash matches zero rows
-- once replaced. Revocation is a soft state change
-- (`status = 'revoked', revoked_at = now()`) - rows are never hard-deleted,
-- matching the device-authentication soft-revoke convention.
--
-- `reset_at` is reserved and unused - Phase 2 device-reset cooldown
-- (out of scope for this module).
--
-- `id` is generated application-side (UUIDv7), per V1's baseline convention.

CREATE TABLE device_session (
    id                      UUID PRIMARY KEY,
    tenant_id               UUID NOT NULL REFERENCES tenant (id),
    user_id                 UUID NOT NULL,
    refresh_token_hash      VARCHAR NOT NULL UNIQUE,
    device_identifier_hash  VARCHAR NOT NULL,
    issued_at               TIMESTAMPTZ NOT NULL,
    expires_at              TIMESTAMPTZ NOT NULL,
    last_rotated_at         TIMESTAMPTZ NULL,
    revoked_at              TIMESTAMPTZ NULL,
    reset_at                TIMESTAMPTZ NULL,
    status                  VARCHAR NOT NULL DEFAULT 'active'
                            CHECK (status IN ('active', 'revoked', 'expired')),

    CONSTRAINT ck_device_session_revoked_at
        CHECK (status <> 'revoked' OR revoked_at IS NOT NULL),
    CONSTRAINT fk_device_session_tenant_user
        FOREIGN KEY (tenant_id, user_id) REFERENCES tenant_user (tenant_id, id)
);

-- Composite index leading with `tenant_id`, shaped to the module's actual
-- tenant-scoped query pattern: looking up a given user's sessions within a
-- tenant (session validity checks, logout, future device-limit logic).
CREATE INDEX idx_device_session_tenant_user ON device_session (tenant_id, user_id);
