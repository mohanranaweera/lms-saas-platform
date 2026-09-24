-- user-management (Wave 3 - Student registration expansion): adds
-- registration_status to student_profile, so a self-registered (possibly
-- pending-approval) student is distinguishable from a staff/admin-created
-- one in the activate/deactivate UI and in reporting.
--
-- V17 is NOT edited - already applied/shared. Purely additive ALTER TABLE.
-- NOT NULL DEFAULT 'ADMIN_CREATED' backfills every existing row accurately
-- - no self-registration code path existed before this wave, so every row
-- that already exists really was admin-created.

ALTER TABLE student_profile
    ADD COLUMN registration_status VARCHAR(20) NOT NULL DEFAULT 'ADMIN_CREATED'
        CONSTRAINT ck_student_profile_registration_status
            CHECK (registration_status IN ('ADMIN_CREATED', 'SELF_REGISTERED'));
