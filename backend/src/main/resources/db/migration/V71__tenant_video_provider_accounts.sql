-- VIDEO Phase 3 (docs/plans/multi-tenant video platform + aoc secure player.md
-- §10/§11, ADR-018 Amendment 2 - owner approval B1, 2026-10-04):
--   1. a tenant may connect its OWN YouTube channel (scope TENANT, OAuth),
--      alongside the existing per-teacher read-only YouTube connections;
--   2. one YouTube channel can be the live tenant channel of at most one
--      tenant (plan Q-003);
--   3. tenant-wide video configuration.
--
-- V1-V70 are NOT edited - additive only. No existing row is rewritten: every
-- current row already satisfies the widened CHECK.

-- ---------------------------------------------------------------------------
-- media_provider_connection: allow the tenant-scoped YouTube channel
-- ---------------------------------------------------------------------------

ALTER TABLE media_provider_connection DROP CONSTRAINT ck_media_provider_connection_scope;

ALTER TABLE media_provider_connection ADD CONSTRAINT ck_media_provider_connection_scope CHECK (
    (provider IN ('ZOOM', 'YOUTUBE', 'VIMEO') AND scope = 'TEACHER' AND owner_user_id IS NOT NULL
        AND auth_type = 'OAUTH')
    OR (provider IN ('DYNTUBE', 'BUNNY') AND scope = 'TENANT' AND owner_user_id IS NULL
        AND auth_type = 'API_KEY')
    OR (provider = 'YOUTUBE' AND scope = 'TENANT' AND owner_user_id IS NULL
        AND auth_type = 'OAUTH')
);

-- The existing uq_media_provider_connection_tenant_current (tenant_id,
-- provider) WHERE scope = 'TENANT' already limits a tenant to one live
-- YouTube channel.
--
-- Deliberately cross-tenant (not tenant-leading): a channel that is the live
-- video channel of tenant A cannot also be confirmed by tenant B. This index
-- can only reject a write; it never exposes another tenant's row.
CREATE UNIQUE INDEX uq_media_provider_connection_youtube_channel
    ON media_provider_connection (provider, external_account_id)
    WHERE provider = 'YOUTUBE' AND scope = 'TENANT' AND status <> 'DISCONNECTED';

-- ---------------------------------------------------------------------------
-- tenant_video_configuration (com.lms.videoaccessmanagement.domain.TenantVideoConfiguration)
-- ---------------------------------------------------------------------------
-- At most one row per tenant; an absent row means the platform defaults.
-- max_registered_devices is NOT here - it stays in the ADR-022 device policy.

CREATE TABLE tenant_video_configuration (
    id                                   UUID PRIMARY KEY,
    tenant_id                            UUID NOT NULL REFERENCES tenant (id),
    default_provider                     VARCHAR(20) NOT NULL,
    default_privacy_status               VARCHAR(10) NOT NULL,
    uploads_enabled                      BOOLEAN NOT NULL,
    native_player_required               BOOLEAN NOT NULL,
    watermark_enabled                    BOOLEAN NOT NULL,
    watermark_opacity                    NUMERIC(3, 2) NOT NULL,
    watermark_movement_interval_seconds  INTEGER NOT NULL,
    watermark_fields                     VARCHAR(200) NOT NULL,
    capture_protection_enabled           BOOLEAN NOT NULL,
    capture_protection_required          BOOLEAN NOT NULL,
    max_concurrent_sessions              INTEGER NOT NULL,
    watch_time_policy                    VARCHAR(30) NOT NULL,
    watch_time_multiplier                NUMERIC(4, 2),
    watch_time_fixed_minutes             INTEGER,
    launch_code_ttl_seconds              INTEGER NOT NULL,
    heartbeat_interval_seconds           INTEGER NOT NULL,
    created_at                           TIMESTAMPTZ NOT NULL,
    updated_at                           TIMESTAMPTZ NOT NULL,
    created_by                           UUID,
    updated_by                           UUID,

    CONSTRAINT uq_tenant_video_configuration_tenant UNIQUE (tenant_id),

    CONSTRAINT ck_tenant_video_configuration_default_provider
        CHECK (default_provider IN ('INTERNAL', 'YOUTUBE')),
    CONSTRAINT ck_tenant_video_configuration_privacy
        CHECK (default_privacy_status IN ('UNLISTED', 'PRIVATE')),
    CONSTRAINT ck_tenant_video_configuration_watermark_opacity
        CHECK (watermark_opacity BETWEEN 0.05 AND 0.60),
    CONSTRAINT ck_tenant_video_configuration_watermark_interval
        CHECK (watermark_movement_interval_seconds BETWEEN 5 AND 120),
    CONSTRAINT ck_tenant_video_configuration_watermark_fields CHECK (watermark_fields ~
        '^(STUDENT_DISPLAY_ID|MASKED_EMAIL|MASKED_PHONE|SESSION_ID|TIMESTAMP)(,(STUDENT_DISPLAY_ID|MASKED_EMAIL|MASKED_PHONE|SESSION_ID|TIMESTAMP))*$'),
    -- "required" is meaningless when protection itself is off.
    CONSTRAINT ck_tenant_video_configuration_capture
        CHECK (capture_protection_enabled OR NOT capture_protection_required),
    CONSTRAINT ck_tenant_video_configuration_concurrent
        CHECK (max_concurrent_sessions BETWEEN 1 AND 5),
    CONSTRAINT ck_tenant_video_configuration_watch_time CHECK (
        (watch_time_policy = 'NONE'
            AND watch_time_multiplier IS NULL AND watch_time_fixed_minutes IS NULL)
        OR (watch_time_policy = 'VIDEO_DURATION_MULTIPLIER'
            AND watch_time_multiplier BETWEEN 1.00 AND 10.00 AND watch_time_fixed_minutes IS NULL)
        OR (watch_time_policy = 'FIXED_MINUTES'
            AND watch_time_fixed_minutes BETWEEN 1 AND 10000 AND watch_time_multiplier IS NULL)
    ),
    CONSTRAINT ck_tenant_video_configuration_launch_code_ttl
        CHECK (launch_code_ttl_seconds BETWEEN 15 AND 60),
    CONSTRAINT ck_tenant_video_configuration_heartbeat
        CHECK (heartbeat_interval_seconds BETWEEN 10 AND 60)
);
