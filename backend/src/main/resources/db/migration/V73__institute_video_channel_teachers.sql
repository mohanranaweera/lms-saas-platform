-- VIDEO Phase 5b (docs/plans/multi-tenant video platform + aoc secure player.md,
-- ADR-018 Amendment 4 - owner approval B8, 2026-10-05):
--   1. the institute YouTube channel gets a display name / description;
--   2. the Tenant Admin may restrict which teachers can upload to it
--      (no rows = all teachers);
--   3. YouTube stops being a teacher-level provider: live teacher YouTube
--      connections are disconnected (rows kept as history, credentials wiped).
--
-- V1-V72 are NOT edited - additive only.

-- ---------------------------------------------------------------------------
-- tenant_video_configuration: channel display name / description
-- ---------------------------------------------------------------------------

ALTER TABLE tenant_video_configuration
    ADD COLUMN channel_display_name VARCHAR(100),
    ADD COLUMN channel_description  VARCHAR(500);

-- ---------------------------------------------------------------------------
-- tenant_video_upload_teacher (com.lms.videoaccessmanagement.domain.TenantVideoUploadTeacher)
-- ---------------------------------------------------------------------------
-- Teachers allowed to upload to the institute channel. An empty set means
-- every teacher may upload. The composite FK keeps every teacher inside the
-- row's own tenant.

CREATE TABLE tenant_video_upload_teacher (
    id              UUID PRIMARY KEY,
    tenant_id       UUID NOT NULL REFERENCES tenant (id),
    teacher_user_id UUID NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL,
    created_by      UUID,
    updated_by      UUID,

    CONSTRAINT uq_tenant_video_upload_teacher UNIQUE (tenant_id, teacher_user_id),
    CONSTRAINT fk_tenant_video_upload_teacher_user
        FOREIGN KEY (tenant_id, teacher_user_id) REFERENCES tenant_user (tenant_id, id)
);

-- ---------------------------------------------------------------------------
-- media_provider_connection: retire teacher-level YouTube (ADR-018 Amendment 4)
-- ---------------------------------------------------------------------------
-- No consumer ever used these read-only grants. They cannot be revoked at
-- Google from a migration; the grant stays on the teacher's Google account
-- until they remove it there. 'wiped' matches MediaProviderConnection.WIPED_CREDENTIALS.

UPDATE media_provider_connection
SET status = 'DISCONNECTED',
    disconnected_by = 'SYSTEM',
    disconnected_reason = 'YouTube is now connected once for the whole institute',
    disconnected_at = now(),
    credentials_ciphertext = 'wiped',
    access_token_expires_at = NULL,
    updated_at = now()
WHERE provider = 'YOUTUBE' AND scope = 'TEACHER' AND status <> 'DISCONNECTED';
