-- Identity & Access Service: `platform_admin_session` table.
--
-- Platform-level table: same shape as `device_session` (V5) but for
-- `platform_admin_user` logins, which must never carry a `tenant_id`. Kept
-- as its own table rather than reusing `device_session` because a platform
-- admin session must never share a table with tenant-owned rows (per
-- docs/architecture/database-architecture.md §1).
--
-- Rotation/revocation follows the same convention as `device_session`: not
-- an append-only domain, rotation is an in-place UPDATE of
-- `refresh_token_hash`/`last_rotated_at`, revocation is a soft state change
-- (`status = 'revoked', revoked_at = now()`) - rows are never hard-deleted.
--
-- `id` is generated application-side (UUIDv7), per V1's baseline convention.

CREATE TABLE platform_admin_session (
    id                      UUID PRIMARY KEY,
    admin_id                UUID NOT NULL REFERENCES platform_admin_user (id),
    refresh_token_hash      VARCHAR NOT NULL UNIQUE,
    device_identifier_hash  VARCHAR NOT NULL,
    issued_at               TIMESTAMPTZ NOT NULL,
    expires_at              TIMESTAMPTZ NOT NULL,
    last_rotated_at         TIMESTAMPTZ NULL,
    revoked_at              TIMESTAMPTZ NULL,
    status                  VARCHAR NOT NULL DEFAULT 'active'
                            CHECK (status IN ('active', 'revoked', 'expired'))
);
