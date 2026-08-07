-- Repoints `tenant_user.role` from V3's inline CHECK-constrained enum to a
-- foreign key against the new `role` catalog (V7), so the catalog becomes
-- the single source of truth for the valid tenant-role set (avoiding two
-- independently-maintained lists that could drift).
--
-- The column is NOT renamed (stays `role`, not `role_code`) -- deliberately,
-- to avoid churning `TenantUser.getRole()`/
-- `TokenService.issueTenantAccessToken(..., Role role, ...)` and existing
-- tests that all reference `.role`/`Role`.
--
-- Type-safety against ever storing `PLATFORM_ADMIN` in this tenant-owned
-- table is enforced at the Java layer (the `Role` enum this column maps to
-- deliberately excludes `PLATFORM_ADMIN` -- a plain FK alone can't express
-- "only TENANT-scope codes," so this is a documented, accepted limitation,
-- not a gap to solve with a trigger).
--
-- The dropped constraint name (`tenant_user_role_check`) was verified
-- against the real applied V1-V6 schema (Postgres's default auto-generated
-- name for an unnamed, single-column CHECK: `<table>_<column>_check`),
-- rather than assumed.

ALTER TABLE tenant_user DROP CONSTRAINT tenant_user_role_check;

ALTER TABLE tenant_user
    ADD CONSTRAINT fk_tenant_user_role FOREIGN KEY (role) REFERENCES role (code);

CREATE INDEX idx_tenant_user_tenant_role ON tenant_user (tenant_id, role);
