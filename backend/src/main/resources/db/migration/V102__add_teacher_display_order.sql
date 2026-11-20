ALTER TABLE teacher_profile ADD COLUMN display_order INTEGER;
CREATE INDEX idx_teacher_profile_tenant_order ON teacher_profile (tenant_id, display_order, id);
