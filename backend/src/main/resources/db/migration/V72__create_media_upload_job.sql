-- VIDEO Phase 4 (docs/plans/multi-tenant video platform + aoc secure player.md
-- §13, VID-ADR-004 - owner approval B2, 2026-10-04): the YouTube upload
-- pipeline.
--   1. media_upload_job - one browser -> S3 staging -> YouTube transfer;
--   2. tenant_video_usage_daily - per-tenant daily upload budget (Q-014);
--   3. tenant_video_configuration.daily_upload_limit (default 20, Q-014);
--   4. the status-sync scheduling index deferred from V70;
--   5. TD-008 - a Platform Admin can switch off a tenant's YouTube channel.
--
-- V1-V71 are NOT edited - additive only. No existing row is rewritten.

-- ---------------------------------------------------------------------------
-- media_upload_job (com.lms.videoaccessmanagement.domain.MediaUploadJob)
-- ---------------------------------------------------------------------------
-- A retry is a NEW row: finished jobs are kept as the upload history of the
-- asset. `session_handle` is the YouTube resumable session URI sealed with
-- AES-256-GCM (tenant-bound AAD) by integration-management - a bearer
-- capability that is never returned by any API or logged.

CREATE TABLE media_upload_job (
    id                     UUID PRIMARY KEY,
    tenant_id              UUID NOT NULL REFERENCES tenant (id),
    video_asset_id         UUID NOT NULL,
    provider_connection_id UUID NOT NULL,
    status                 VARCHAR(20) NOT NULL,
    staging_object_key     VARCHAR(512) NOT NULL,
    staging_upload_id      VARCHAR(1024),
    part_size_bytes        BIGINT NOT NULL,
    bytes_total            BIGINT NOT NULL,
    bytes_sent             BIGINT NOT NULL DEFAULT 0,
    detected_mime_type     VARCHAR(100),
    session_handle         TEXT,
    attempt_count          INTEGER NOT NULL DEFAULT 0,
    next_attempt_at        TIMESTAMPTZ,
    heartbeat_at           TIMESTAMPTZ,
    budget_date            DATE NOT NULL,
    budget_released        BOOLEAN NOT NULL DEFAULT FALSE,
    -- Set once this job no longer owns a staging object (deleted, or handed to a retry job).
    staging_released_at    TIMESTAMPTZ,
    last_error             VARCHAR(500),
    completed_at           TIMESTAMPTZ,
    created_at             TIMESTAMPTZ NOT NULL,
    updated_at             TIMESTAMPTZ NOT NULL,
    created_by             UUID,
    updated_by             UUID,

    CONSTRAINT uq_media_upload_job_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_media_upload_job_asset
        FOREIGN KEY (tenant_id, video_asset_id) REFERENCES video_asset (tenant_id, id),
    CONSTRAINT fk_media_upload_job_connection
        FOREIGN KEY (tenant_id, provider_connection_id) REFERENCES media_provider_connection (tenant_id, id),
    CONSTRAINT ck_media_upload_job_status CHECK (status IN
        ('AWAITING_PARTS', 'QUEUED', 'TRANSFERRING', 'RETRY_WAIT', 'QUOTA_PAUSED', 'PARKED_RECONNECT',
         'COMPLETED', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_media_upload_job_bytes
        CHECK (bytes_total > 0 AND bytes_sent >= 0 AND bytes_sent <= bytes_total),
    CONSTRAINT ck_media_upload_job_part_size CHECK (part_size_bytes > 0),
    CONSTRAINT ck_media_upload_job_attempts CHECK (attempt_count >= 0),
    -- Staging keys are generated server-side under the job's own tenant prefix.
    CONSTRAINT ck_media_upload_job_staging_key
        CHECK (staging_object_key LIKE 'video-staging/' || tenant_id::text || '/%'),
    CONSTRAINT ck_media_upload_job_completed
        CHECK ((status = 'COMPLETED') = (completed_at IS NOT NULL))
);

-- At most one live (unfinished) job per asset.
CREATE UNIQUE INDEX uq_media_upload_job_live_asset
    ON media_upload_job (tenant_id, video_asset_id)
    WHERE status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED');

-- Asset -> its jobs (latest first).
CREATE INDEX idx_media_upload_job_tenant_asset
    ON media_upload_job (tenant_id, video_asset_id, created_at DESC);

-- Worker claim: due jobs across tenants (platform-level poller, like the notification outbox).
CREATE INDEX idx_media_upload_job_claim
    ON media_upload_job (status, next_attempt_at)
    WHERE status IN ('QUEUED', 'RETRY_WAIT', 'QUOTA_PAUSED', 'TRANSFERRING', 'PARKED_RECONNECT');

-- Per-tenant transfer cap check.
CREATE INDEX idx_media_upload_job_tenant_transferring
    ON media_upload_job (tenant_id)
    WHERE status = 'TRANSFERRING';

-- ---------------------------------------------------------------------------
-- tenant_video_usage_daily (com.lms.videoaccessmanagement.domain.TenantVideoUsageDaily)
-- ---------------------------------------------------------------------------
-- usage_date is the America/Los_Angeles calendar day, matching when the
-- YouTube Data API quota resets. Counters are changed only by atomic
-- single-statement upserts/updates.

CREATE TABLE tenant_video_usage_daily (
    id          UUID PRIMARY KEY,
    tenant_id   UUID NOT NULL REFERENCES tenant (id),
    usage_date  DATE NOT NULL,
    uploads     INTEGER NOT NULL DEFAULT 0,
    bytes       BIGINT NOT NULL DEFAULT 0,
    api_units   BIGINT NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL,

    CONSTRAINT uq_tenant_video_usage_daily UNIQUE (tenant_id, usage_date),
    CONSTRAINT ck_tenant_video_usage_daily_counters CHECK (uploads >= 0 AND bytes >= 0 AND api_units >= 0)
);

-- ---------------------------------------------------------------------------
-- tenant_video_configuration: per-tenant daily upload budget (Q-014)
-- ---------------------------------------------------------------------------

ALTER TABLE tenant_video_configuration
    ADD COLUMN daily_upload_limit INTEGER NOT NULL DEFAULT 20,
    ADD CONSTRAINT ck_tenant_video_configuration_daily_upload_limit
        CHECK (daily_upload_limit BETWEEN 0 AND 1000);

-- ---------------------------------------------------------------------------
-- video_asset: status-sync scheduling (deferred from V70)
-- ---------------------------------------------------------------------------

CREATE INDEX idx_video_asset_sync_due
    ON video_asset (next_sync_at)
    WHERE status IN ('UPLOADED', 'PROCESSING') AND next_sync_at IS NOT NULL;

-- ---------------------------------------------------------------------------
-- media_provider_connection: Platform Admin switch-off (TD-008)
-- ---------------------------------------------------------------------------
-- Only the tenant YouTube channel can be switched off by the platform. While
-- switched off the channel takes no uploads, and the tenant can neither
-- re-enable it nor connect another channel; only a Platform Admin can
-- re-enable it. The platform admin is not a tenant user, so there is no
-- actor FK - the actor is in the audit log.

ALTER TABLE media_provider_connection
    ADD COLUMN platform_disabled_at     TIMESTAMPTZ,
    ADD COLUMN platform_disabled_reason VARCHAR(500),
    ADD CONSTRAINT ck_media_provider_connection_platform_disabled CHECK (
        (platform_disabled_at IS NULL AND platform_disabled_reason IS NULL)
        OR (platform_disabled_at IS NOT NULL AND platform_disabled_reason IS NOT NULL
            AND provider = 'YOUTUBE' AND scope = 'TENANT')
    );
