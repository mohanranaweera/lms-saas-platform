-- Teacher Management parity fields. Additive only: existing full names and account links remain intact.
-- Legacy rows keep name as their authoritative display fallback; new writes populate first/last.
ALTER TABLE teacher_profile
    ADD COLUMN first_name VARCHAR(120),
    ADD COLUMN last_name VARCHAR(120),
    ADD COLUMN contact_number VARCHAR(40),
    ADD COLUMN qualifications VARCHAR(2000),
    ADD COLUMN description VARCHAR(5000),
    ADD COLUMN institute_commission_percent NUMERIC(5,2),
    ADD COLUMN profile_media_file_id UUID;

ALTER TABLE teacher_profile
    ADD CONSTRAINT ck_teacher_profile_institute_commission
        CHECK (institute_commission_percent IS NULL
               OR (institute_commission_percent >= 0 AND institute_commission_percent <= 100)),
    ADD CONSTRAINT fk_teacher_profile_profile_media
        FOREIGN KEY (tenant_id, profile_media_file_id) REFERENCES media_file (tenant_id, id);

CREATE INDEX idx_teacher_profile_tenant_first_name
    ON teacher_profile (tenant_id, first_name);
CREATE INDEX idx_teacher_profile_tenant_last_name
    ON teacher_profile (tenant_id, last_name);
CREATE INDEX idx_teacher_profile_tenant_contact
    ON teacher_profile (tenant_id, contact_number);
CREATE INDEX idx_teacher_profile_tenant_media
    ON teacher_profile (tenant_id, profile_media_file_id)
    WHERE profile_media_file_id IS NOT NULL;
