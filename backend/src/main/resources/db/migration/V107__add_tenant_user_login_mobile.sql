-- ADR-031 (LOGIN-01 Phase C): mobile-number login.
--
-- `login_mobile` is the user's sign-in mobile number in E.164 form
-- (e.g. `+94771234567`), normalised by the application (libphonenumber) with
-- the tenant's `GENERAL.phone_region`. NULL = the user signs in by email only.
-- It is owned by identity-access-service; `student_profile.mobile` stays the
-- student's contact field (user-management keeps the two in step through
-- `UserProvisioningApi#setLoginMobile`).
--
-- Unique per tenant (owner decision 2026-10-29): the same number may belong
-- to one user in each of several institutes, never to two users in one. The
-- partial unique index leads with tenant_id, matching the tenant-scoped login
-- lookup `WHERE tenant_id = ? AND login_mobile = ?`.
--
-- Additive only: nullable column, no default, no rewrite of existing rows
-- (the backfill is V108, a separate Java migration).

ALTER TABLE tenant_user
    ADD COLUMN login_mobile VARCHAR(16) NULL;

ALTER TABLE tenant_user
    ADD CONSTRAINT ck_tenant_user_login_mobile_e164
        CHECK (login_mobile IS NULL OR login_mobile ~ '^\+[1-9][0-9]{6,14}$');

CREATE UNIQUE INDEX uq_tenant_user_tenant_login_mobile
    ON tenant_user (tenant_id, login_mobile)
    WHERE login_mobile IS NOT NULL;
