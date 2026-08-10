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

-- Pre-flight check (intentional fail-loud behavior, see plan
-- "MVP-003 Roles and Permissions.md" lines 398-412): the ADD CONSTRAINT
-- below will fail with a foreign-key-violation error if any `tenant_user`
-- row still holds the old coarse `role = 'STAFF'` value from V3's 4-value
-- CHECK enum -- that value was decomposed into 7 named sub-roles in the V7
-- catalog and has no corresponding `role.code` row, so it cannot satisfy
-- the FK. This is by design: no silent backfill/default mapping of
-- `STAFF` to a specific sub-role is provided here, per the plan's explicit
-- decision that this must be a business decision, not a migration default.
--
-- Before applying this migration to any environment that might predate
-- this branch (i.e. anything other than a fresh dev database), run:
--
--   SELECT tenant_id, id, role FROM tenant_user WHERE role NOT IN
--     ('TENANT_ADMIN','FINANCE_STAFF','COURSE_COORDINATOR','STUDENT_SUPPORT',
--      'CONTENT_MANAGER','EXAM_MANAGER','ATTENDANCE_OPERATOR','READ_ONLY_AUDITOR',
--      'TEACHER','TEACHER_ASSISTANT','STUDENT');
--
-- If any rows are returned, resolving which sub-role each such row should
-- become is a business decision that must be made and applied (e.g. via a
-- data-fix/backfill step coordinated with the business, not by editing
-- this file) before this migration is run in that environment.
ALTER TABLE tenant_user
    ADD CONSTRAINT fk_tenant_user_role FOREIGN KEY (role) REFERENCES role (code);

CREATE INDEX idx_tenant_user_tenant_role ON tenant_user (tenant_id, role);
