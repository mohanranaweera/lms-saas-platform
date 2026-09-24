-- user-management (Wave 3 - Student registration expansion): adds nullable
-- guardian/school/grade/stream/mobile columns to student_profile.
--
-- V17 (student_profile's origin) is NOT edited - already applied/shared.
-- Purely additive ALTER TABLE.
--
-- All six columns are nullable, deliberately - which fields are actually
-- required is a per-tenant, runtime-configurable decision
-- (ConfigDomain.STUDENT's require_guardian_info/require_school/
-- require_grade/require_stream/require_mobile properties, code-only, no
-- migration of their own - see ConfigPropertyRegistry), enforced at the API
-- layer (StudentRegistrationService), never at the DB level - a tenant that
-- does not require a field must still be able to leave it null for any
-- student, admin-created or self-registered alike. A global NOT NULL here
-- would be wrong for a tenant that has the corresponding require_* flag
-- off.

ALTER TABLE student_profile
    ADD COLUMN guardian_name   VARCHAR(255) NULL,
    ADD COLUMN guardian_phone  VARCHAR(50)  NULL,
    ADD COLUMN school          VARCHAR(255) NULL,
    ADD COLUMN grade           VARCHAR(50)  NULL,
    ADD COLUMN stream          VARCHAR(50)  NULL,
    ADD COLUMN mobile          VARCHAR(50)  NULL;
