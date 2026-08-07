-- Defense-in-depth per .claude/rules/backend.md's "prefer invariants enforced
-- by the schema/constraints over invariants enforced only by service-layer
-- discipline" guidance (security review finding on V8): V8's FK
-- (`tenant_user.role REFERENCES role(code)`) accepts any of the 12 catalog
-- rows, including `PLATFORM_ADMIN` -- a plain FK can't itself express "only
-- TENANT-scope codes." Today this is only prevented at the Java layer (the
-- `Role` enum deliberately excludes `PLATFORM_ADMIN`), which is bypassable by
-- a future bug, a direct data-fix script, or a native-SQL insert. This CHECK
-- closes that specific gap cheaply, without a trigger: `platform_admin_user`
-- (V4) remains the sole home for Platform Admin accounts; this table must
-- never carry that value regardless of what any future code path attempts.

ALTER TABLE tenant_user
    ADD CONSTRAINT chk_tenant_user_role_not_platform_admin CHECK (role <> 'PLATFORM_ADMIN');
