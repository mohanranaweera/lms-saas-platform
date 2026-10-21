-- VIDEO Phase 2 (docs/plans/multi-tenant video platform + aoc secure player.md
-- §9, VID-ADR-002): evolves V51's `video_asset` into the provider-neutral
-- MediaAsset record instead of adding a parallel `media_asset` table (owner
-- approval B5, 2026-10-04), and adds the append-only
-- `video_asset_status_event` history.
--
-- V1-V69 are NOT edited - this is a new, additive migration only.
--
-- Existing rows are MinIO uploads made by `VideoAssetService#upload`; they
-- all become `provider = 'INTERNAL'` and keep their `storage_object_key`.
-- `storage_object_key` becomes nullable because externally hosted assets
-- (YouTube) have no permanent object in our storage - the provider-shape
-- CHECK below keeps it mandatory for INTERNAL rows.
--
-- `PENDING` was never persisted (see VideoAssetStatus javadoc before this
-- change); the defensive UPDATE maps any such row to its new equivalent
-- `CREATED` before the status CHECK is replaced.
--
-- Tenant isolation: every new reference into another tenant-owned table is a
-- composite `(tenant_id, ...)` FK (`media_provider_connection` and
-- `tenant_user` already carry the matching `UNIQUE (tenant_id, id)`
-- targets), and the provider asset id is unique per tenant, not globally.
--
-- The status-sync scheduling index (`next_sync_at`) is intentionally NOT
-- created here - it ships in Phase 4 together with the job that reads it.

-- ---------------------------------------------------------------------------
-- video_asset: provider-neutral columns
-- ---------------------------------------------------------------------------

ALTER TABLE video_asset ALTER COLUMN status TYPE VARCHAR(20);

UPDATE video_asset SET status = 'CREATED' WHERE status = 'PENDING';

ALTER TABLE video_asset DROP CONSTRAINT ck_video_asset_status;

ALTER TABLE video_asset
    ADD COLUMN provider                VARCHAR(20) NOT NULL DEFAULT 'INTERNAL',
    ADD COLUMN provider_connection_id  UUID,
    ADD COLUMN provider_asset_id       VARCHAR(255),
    ADD COLUMN title                   VARCHAR(255),
    ADD COLUMN description             VARCHAR(5000),
    ADD COLUMN thumbnail_url           VARCHAR(2048),
    ADD COLUMN privacy_status          VARCHAR(10),
    ADD COLUMN provider_failure_reason VARCHAR(500),
    ADD COLUMN last_synced_at          TIMESTAMPTZ,
    ADD COLUMN next_sync_at            TIMESTAMPTZ,
    ADD COLUMN sync_failure_count      INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN deleted_at              TIMESTAMPTZ,
    ADD COLUMN deleted_by              UUID,
    ALTER COLUMN storage_object_key DROP NOT NULL;

-- The default only back-fills existing rows; new rows always set it explicitly.
ALTER TABLE video_asset ALTER COLUMN provider DROP DEFAULT;

ALTER TABLE video_asset
    ADD CONSTRAINT ck_video_asset_status CHECK (status IN
        ('CREATED', 'UPLOADING', 'UPLOADED', 'PROCESSING', 'READY', 'FAILED', 'DELETED')),

    ADD CONSTRAINT ck_video_asset_provider CHECK (provider IN ('INTERNAL', 'YOUTUBE')),

    ADD CONSTRAINT ck_video_asset_privacy_status
        CHECK (privacy_status IS NULL OR privacy_status IN ('PRIVATE', 'UNLISTED', 'PUBLIC')),

    -- INTERNAL = our own object storage (V51 shape); anything else is hosted
    -- by an external provider reached through a tenant provider connection.
    ADD CONSTRAINT ck_video_asset_provider_shape CHECK (
        (provider = 'INTERNAL'
            AND storage_object_key IS NOT NULL
            AND provider_connection_id IS NULL
            AND provider_asset_id IS NULL)
        OR (provider <> 'INTERNAL'
            AND provider_connection_id IS NOT NULL
            AND title IS NOT NULL
            AND privacy_status IS NOT NULL)
    ),

    -- An externally hosted asset cannot be processing/playable without the
    -- provider's own id for it.
    ADD CONSTRAINT ck_video_asset_processed_has_provider_id CHECK (
        provider = 'INTERNAL'
        OR status NOT IN ('PROCESSING', 'READY')
        OR provider_asset_id IS NOT NULL
    ),

    ADD CONSTRAINT ck_video_asset_deleted_consistency CHECK (
        (deleted_at IS NULL) = (deleted_by IS NULL)
        AND (status = 'DELETED') = (deleted_at IS NOT NULL)
    ),

    ADD CONSTRAINT ck_video_asset_sync_failure_count CHECK (sync_failure_count >= 0),

    ADD CONSTRAINT fk_video_asset_provider_connection
        FOREIGN KEY (tenant_id, provider_connection_id)
        REFERENCES media_provider_connection (tenant_id, id),

    ADD CONSTRAINT fk_video_asset_deleted_by
        FOREIGN KEY (tenant_id, deleted_by) REFERENCES tenant_user (tenant_id, id);

-- One LMS asset per provider-side video, per tenant (a provider id colliding
-- across two tenants is not a duplicate).
CREATE UNIQUE INDEX uq_video_asset_provider_asset
    ON video_asset (tenant_id, provider, provider_asset_id)
    WHERE provider_asset_id IS NOT NULL;

-- Media Library: tenant-wide listing (Tenant Admin / staff), newest first.
CREATE INDEX idx_video_asset_tenant_library
    ON video_asset (tenant_id, created_at DESC)
    WHERE deleted_at IS NULL;

-- Media Library: a Teacher's own uploads.
CREATE INDEX idx_video_asset_tenant_uploader
    ON video_asset (tenant_id, uploaded_by, created_at DESC)
    WHERE deleted_at IS NULL;

-- Supports fk_video_asset_provider_connection and per-connection sync batches.
CREATE INDEX idx_video_asset_tenant_provider_connection
    ON video_asset (tenant_id, provider_connection_id)
    WHERE provider_connection_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- video_asset_status_event (com.lms.videoaccessmanagement.domain.VideoAssetStatusEvent)
-- ---------------------------------------------------------------------------
-- Append-only processing history. `actor_id` is NULL for system-driven
-- transitions (upload worker, status sync) - the audit_log actor trigger
-- (V33/V34) requires a real user, so system transitions are recorded here
-- instead (plan Q-013).

CREATE TABLE video_asset_status_event (
    id             UUID PRIMARY KEY,
    tenant_id      UUID NOT NULL REFERENCES tenant (id),
    video_asset_id UUID NOT NULL,
    from_status    VARCHAR(20),
    to_status      VARCHAR(20) NOT NULL,
    reason         VARCHAR(500),
    actor_id       UUID,
    occurred_at    TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_video_asset_status_event_asset
        FOREIGN KEY (tenant_id, video_asset_id) REFERENCES video_asset (tenant_id, id),
    CONSTRAINT fk_video_asset_status_event_actor
        FOREIGN KEY (tenant_id, actor_id) REFERENCES tenant_user (tenant_id, id),
    CONSTRAINT ck_video_asset_status_event_from_status CHECK (from_status IS NULL OR from_status IN
        ('CREATED', 'UPLOADING', 'UPLOADED', 'PROCESSING', 'READY', 'FAILED', 'DELETED')),
    CONSTRAINT ck_video_asset_status_event_to_status CHECK (to_status IN
        ('CREATED', 'UPLOADING', 'UPLOADED', 'PROCESSING', 'READY', 'FAILED', 'DELETED')),
    CONSTRAINT ck_video_asset_status_event_changes CHECK (from_status IS NULL OR from_status <> to_status)
);

CREATE INDEX idx_video_asset_status_event_tenant_asset
    ON video_asset_status_event (tenant_id, video_asset_id, occurred_at);

CREATE FUNCTION forbid_video_asset_status_event_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'video_asset_status_event is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_video_asset_status_event_append_only
    BEFORE UPDATE OR DELETE ON video_asset_status_event
    FOR EACH ROW EXECUTE FUNCTION forbid_video_asset_status_event_mutation();
