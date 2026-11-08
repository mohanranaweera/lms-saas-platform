-- identity-access-service (STAFF-MGMT-03 - docs/specs/staff-management-permissions-parity.md §6.1,
-- ADR-034): the seven seeded staff sub-roles become configurable permission-matrix columns.
--
-- Purely additive: no earlier migration is edited and no row is touched. An absent
-- tenant_role_capability_grant row for a seeded role still means "the static code matrix
-- default", so existing access is unchanged until a Tenant Admin saves an override.
-- The CHECK is dropped and re-added in one migration (one transaction).

ALTER TABLE tenant_role_capability_grant
    DROP CONSTRAINT chk_tenant_role_capability_grant_role;

ALTER TABLE tenant_role_capability_grant
    ADD CONSTRAINT chk_tenant_role_capability_grant_role CHECK (role_code IN (
        'OPERATOR', 'MANAGER', 'TEACHER',
        'FINANCE_STAFF', 'COURSE_COORDINATOR', 'STUDENT_SUPPORT', 'CONTENT_MANAGER',
        'EXAM_MANAGER', 'ATTENDANCE_OPERATOR', 'READ_ONLY_AUDITOR'
    ));
