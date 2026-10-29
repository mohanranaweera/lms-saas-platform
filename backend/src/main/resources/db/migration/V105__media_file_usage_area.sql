-- Purpose ("file area") of each stored file. Only COURSE_LIBRARY files are listed in the
-- Media Library and can be attached to lessons; TEACHER_PROFILE photos are managed from the
-- teacher's profile and never shown to library users. Existing rows default to COURSE_LIBRARY.
ALTER TABLE media_file ADD COLUMN usage_area VARCHAR(30) NOT NULL DEFAULT 'COURSE_LIBRARY';

ALTER TABLE media_file ADD CONSTRAINT ck_media_file_usage_area
    CHECK (usage_area IN ('COURSE_LIBRARY', 'TEACHER_PROFILE'));

-- Current teacher photos move out of the library. A file that is also attached to a lesson
-- stays in the library: it is genuinely course content.
UPDATE media_file m
   SET usage_area = 'TEACHER_PROFILE'
 WHERE EXISTS (SELECT 1 FROM teacher_profile tp
                WHERE tp.tenant_id = m.tenant_id AND tp.profile_media_file_id = m.id)
   AND NOT EXISTS (SELECT 1 FROM material x
                    WHERE x.tenant_id = m.tenant_id AND x.media_file_id = m.id);

CREATE INDEX idx_media_file_tenant_usage_live_created
    ON media_file (tenant_id, usage_area, created_at DESC)
    WHERE deleted_at IS NULL;
