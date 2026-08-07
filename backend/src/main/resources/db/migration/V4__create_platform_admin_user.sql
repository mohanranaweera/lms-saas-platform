-- Identity & Access Service: `platform_admin_user` table.
--
-- Platform-level table: platform admin accounts are never tenant-scoped and
-- must never share a table with tenant-owned rows (per
-- docs/architecture/database-architecture.md §1), so this is its own
-- migration/table rather than a row-type flag on `tenant_user`. `email` is
-- intentionally a global UNIQUE constraint here - this is a platform-global
-- entity, the documented exception to the per-tenant-uniqueness rule.
--
-- No `role` column: role is implicit (`PLATFORM_ADMIN`) for every row in
-- this table.
--
-- `totp_secret` is reserved and unused - MFA is not built in this module
-- (ADR-007 §4).
--
-- `id` is generated application-side (UUIDv7), per V1's baseline convention.

CREATE TABLE platform_admin_user (
    id                    UUID PRIMARY KEY,
    email                 VARCHAR NOT NULL UNIQUE,
    password_hash         VARCHAR NOT NULL,
    status                VARCHAR NOT NULL DEFAULT 'active'
                          CHECK (status IN ('active', 'suspended')),
    must_change_password  BOOLEAN NOT NULL DEFAULT false,
    totp_secret           VARCHAR NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
