-- user-management (Wave 3, MVP-007 follow-up - Teacher lifecycle expansion):
-- widens teacher_profile's approval_status CHECK to add 'SUSPENDED', and
-- adds suspended_at/suspended_by/reactivated_at/reactivated_by evidence
-- columns for the new APPROVED <-> SUSPENDED transitions.
--
-- V18 (teacher_profile's origin) is NOT edited - already applied/shared.
-- This is additive, constraint-widening-only DDL: same column, only the
-- allowed value set grows. V18's own CHECK was declared inline/unnamed
-- (`approval_status VARCHAR ... CHECK (approval_status IN (...))`), so
-- Postgres auto-named it `teacher_profile_approval_status_check` (the
-- standard `<table>_<column>_check` convention for an unnamed column-level
-- CHECK) - that generated name is what this migration drops, then replaces
-- with an explicitly-named constraint so any future widening does not need
-- to rely on the naming convention again. Mirrors V41's precedent of
-- widening an already-shared CHECK constraint by DROP/ADD, and V22's
-- precedent of adding nullable evidence columns to an already-shared table.
--
-- No backfill needed - existing PENDING/APPROVED/REJECTED rows are
-- unaffected; the new value is only ever reachable by a future application
-- write (TeacherProfile#suspend/#reactivate), never retroactively applied.
--
-- suspended_by/reactivated_by use the SAME composite-FK pattern V18 already
-- established for approved_by: (tenant_id, suspended_by) REFERENCES
-- tenant_user (tenant_id, id), so a reviewer from a different tenant can
-- never be recorded against this tenant's teacher.

ALTER TABLE teacher_profile
    DROP CONSTRAINT teacher_profile_approval_status_check;

ALTER TABLE teacher_profile
    ADD CONSTRAINT ck_teacher_profile_approval_status
        CHECK (approval_status IN ('PENDING', 'APPROVED', 'REJECTED', 'SUSPENDED'));

ALTER TABLE teacher_profile
    ADD COLUMN suspended_at    TIMESTAMPTZ NULL,
    ADD COLUMN suspended_by    UUID NULL,
    ADD COLUMN reactivated_at  TIMESTAMPTZ NULL,
    ADD COLUMN reactivated_by  UUID NULL;

ALTER TABLE teacher_profile
    ADD CONSTRAINT fk_teacher_profile_suspended_by FOREIGN KEY (tenant_id, suspended_by)
        REFERENCES tenant_user (tenant_id, id),
    ADD CONSTRAINT fk_teacher_profile_reactivated_by FOREIGN KEY (tenant_id, reactivated_by)
        REFERENCES tenant_user (tenant_id, id);
