-- Add IMAGE as a first-class course-session resource type.
-- Existing course_lesson rows and tenant constraints remain unchanged.

ALTER TABLE course_lesson
    DROP CONSTRAINT ck_course_lesson_resource_type;

ALTER TABLE course_lesson
    ADD CONSTRAINT ck_course_lesson_resource_type CHECK (
        resource_type IS NULL OR resource_type IN (
            'VIMEO_VIDEO', 'SECURE_VIDEO', 'DOCUMENT', 'IMAGE', 'ZOOM_LINK', 'ZOOM_MEETING',
            'ZOOM_WEBINAR', 'ZOOM_RECORDING', 'VIDEO_LINK', 'GOOGLE_DRIVE_LINK',
            'MS_TEAMS_LINK', 'GOOGLE_MEET')
    );
