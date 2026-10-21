-- VIDEO: allow tenants to select the server-resolved student full name in
-- native-player watermarks. Existing watermark field values remain valid.
ALTER TABLE tenant_video_configuration
    DROP CONSTRAINT ck_tenant_video_configuration_watermark_fields;

ALTER TABLE tenant_video_configuration
    ADD CONSTRAINT ck_tenant_video_configuration_watermark_fields CHECK (watermark_fields ~
        '^(STUDENT_DISPLAY_ID|STUDENT_FULL_NAME|MASKED_EMAIL|MASKED_PHONE|SESSION_ID|TIMESTAMP)(,(STUDENT_DISPLAY_ID|STUDENT_FULL_NAME|MASKED_EMAIL|MASKED_PHONE|SESSION_ID|TIMESTAMP))*$');
