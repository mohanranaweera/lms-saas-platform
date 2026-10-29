-- Tenant Admin teacher-profile visibility control.
-- FALSE keeps existing and newly-created profiles public unless explicitly disabled.
ALTER TABLE teacher_profile
    ADD COLUMN public_profile_disabled BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX idx_teacher_profile_tenant_public_profile
    ON teacher_profile (tenant_id, public_profile_disabled, id);
