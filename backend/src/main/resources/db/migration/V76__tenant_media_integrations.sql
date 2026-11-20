-- VIDEO Phase 5d (ADR-018 Amendment 5 item 2, owner decisions 2026-10-05):
-- an institute may have several NAMED YouTube integrations (Klass-style
-- cards: name, description, allowed teachers), each with at most one live
-- connection. Media Library tabs follow the integrations.
--   1. tenant_media_integration - the cards (exist before they are connected);
--   2. media_provider_connection.integration_id - every institute YouTube
--      connection belongs to one card; one live connection per card replaces
--      one live YouTube connection per tenant;
--   3. tenant_media_integration_teacher - allowed teachers per card (replaces
--      V73's institute-wide tenant_video_upload_teacher, whose rows are copied);
--   4. video_asset.integration_id - the tab a video belongs to;
--   5. the V73 channel name / description columns move onto the card.
-- Existing data: each institute with a YouTube channel (any status), a V73
-- teacher list or a V73 channel name gets one card; all its rows link to it.
-- Backfilled ids are deterministic md5(...)::uuid, as in V56 (V1 forbids a
-- database-side random UUID function).
--
-- V1-V75 are NOT edited - additive only.

-- ---------------------------------------------------------------------------
-- 1. tenant_media_integration (com.lms.integrationmanagement.domain.TenantMediaIntegration)
-- ---------------------------------------------------------------------------
-- Tenant-owned. Deleting a card is a status change (rows and videos are
-- history; its videos are listed under the Media Library's Archived tab).

CREATE TABLE tenant_media_integration (
    id           UUID PRIMARY KEY,
    tenant_id    UUID         NOT NULL REFERENCES tenant (id),
    provider     VARCHAR(20)  NOT NULL,
    name         VARCHAR(100) NOT NULL,
    description  VARCHAR(500),
    status       VARCHAR(10)  NOT NULL,
    deleted_at   TIMESTAMPTZ,
    deleted_by   UUID,
    created_at   TIMESTAMPTZ  NOT NULL,
    updated_at   TIMESTAMPTZ  NOT NULL,
    created_by   UUID,
    updated_by   UUID,
    CONSTRAINT uq_tenant_media_integration_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT ck_tenant_media_integration_provider CHECK (provider IN ('YOUTUBE')),
    CONSTRAINT ck_tenant_media_integration_status CHECK (status IN ('ACTIVE', 'DELETED')),
    CONSTRAINT ck_tenant_media_integration_deleted CHECK (
        (status = 'ACTIVE' AND deleted_at IS NULL)
        OR (status = 'DELETED' AND deleted_at IS NOT NULL))
);

-- Names are unique per institute among live cards (case-insensitive).
CREATE UNIQUE INDEX uq_tenant_media_integration_name
    ON tenant_media_integration (tenant_id, provider, lower(name))
    WHERE status = 'ACTIVE';

CREATE INDEX idx_tenant_media_integration_tenant_provider
    ON tenant_media_integration (tenant_id, provider, status);

INSERT INTO tenant_media_integration (id, tenant_id, provider, name, description, status, created_at, updated_at)
SELECT md5(t.tenant_id::text || 'youtube-integration')::uuid,
       t.tenant_id,
       'YOUTUBE',
       LEFT(COALESCE(NULLIF(TRIM(cfg.channel_display_name), ''),
                     (SELECT NULLIF(TRIM(c.external_account_label), '')
                      FROM media_provider_connection c
                      WHERE c.tenant_id = t.tenant_id AND c.provider = 'YOUTUBE' AND c.scope = 'TENANT'
                      ORDER BY c.created_at DESC
                      LIMIT 1),
                     'YouTube'), 100),
       cfg.channel_description,
       'ACTIVE',
       now(),
       now()
FROM (
    SELECT tenant_id FROM media_provider_connection WHERE provider = 'YOUTUBE' AND scope = 'TENANT'
    UNION
    SELECT tenant_id FROM tenant_video_upload_teacher
    UNION
    SELECT tenant_id FROM tenant_video_configuration
    WHERE channel_display_name IS NOT NULL OR channel_description IS NOT NULL
) t
LEFT JOIN tenant_video_configuration cfg ON cfg.tenant_id = t.tenant_id;

-- ---------------------------------------------------------------------------
-- 2. media_provider_connection.integration_id
-- ---------------------------------------------------------------------------

ALTER TABLE media_provider_connection
    ADD COLUMN integration_id UUID,
    ADD CONSTRAINT fk_media_provider_connection_integration
        FOREIGN KEY (tenant_id, integration_id) REFERENCES tenant_media_integration (tenant_id, id);

UPDATE media_provider_connection c
SET integration_id = i.id
FROM tenant_media_integration i
WHERE i.tenant_id = c.tenant_id AND c.provider = 'YOUTUBE' AND c.scope = 'TENANT';

-- Institute YouTube rows always belong to a card; nothing else does.
ALTER TABLE media_provider_connection ADD CONSTRAINT ck_media_provider_connection_integration CHECK (
    (provider = 'YOUTUBE' AND scope = 'TENANT' AND integration_id IS NOT NULL)
    OR (NOT (provider = 'YOUTUBE' AND scope = 'TENANT') AND integration_id IS NULL));

-- One live connection per (tenant, provider) still holds for DynTube / Bunny;
-- for YouTube it is one live connection per card. A Google channel can still
-- be live only once on the whole platform (uq_media_provider_connection_youtube_channel, V71).
DROP INDEX uq_media_provider_connection_tenant_current;

CREATE UNIQUE INDEX uq_media_provider_connection_tenant_current
    ON media_provider_connection (tenant_id, provider)
    WHERE scope = 'TENANT' AND status <> 'DISCONNECTED' AND provider <> 'YOUTUBE';

CREATE UNIQUE INDEX uq_media_provider_connection_integration_current
    ON media_provider_connection (tenant_id, integration_id)
    WHERE integration_id IS NOT NULL AND status <> 'DISCONNECTED';

CREATE INDEX idx_media_provider_connection_integration
    ON media_provider_connection (tenant_id, integration_id, created_at DESC)
    WHERE integration_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 3. tenant_media_integration_teacher (com.lms.integrationmanagement.domain.TenantMediaIntegrationTeacher)
-- ---------------------------------------------------------------------------
-- Teachers allowed to upload to one card; no rows = every teacher.

CREATE TABLE tenant_media_integration_teacher (
    id              UUID PRIMARY KEY,
    tenant_id       UUID NOT NULL REFERENCES tenant (id),
    integration_id  UUID NOT NULL,
    teacher_user_id UUID NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL,
    created_by      UUID,
    updated_by      UUID,
    CONSTRAINT uq_tenant_media_integration_teacher UNIQUE (tenant_id, integration_id, teacher_user_id),
    CONSTRAINT fk_tenant_media_integration_teacher_integration
        FOREIGN KEY (tenant_id, integration_id) REFERENCES tenant_media_integration (tenant_id, id),
    CONSTRAINT fk_tenant_media_integration_teacher_user
        FOREIGN KEY (tenant_id, teacher_user_id) REFERENCES tenant_user (tenant_id, id)
);

INSERT INTO tenant_media_integration_teacher
    (id, tenant_id, integration_id, teacher_user_id, created_at, updated_at, created_by, updated_by)
SELECT md5(t.tenant_id::text || t.teacher_user_id::text || 'integration-teacher')::uuid,
       t.tenant_id, i.id, t.teacher_user_id, t.created_at, t.updated_at, t.created_by, t.updated_by
FROM tenant_video_upload_teacher t
JOIN tenant_media_integration i ON i.tenant_id = t.tenant_id;

DROP TABLE tenant_video_upload_teacher;

-- ---------------------------------------------------------------------------
-- 4. video_asset.integration_id (the Media Library tab)
-- ---------------------------------------------------------------------------

ALTER TABLE video_asset
    ADD COLUMN integration_id UUID,
    ADD CONSTRAINT fk_video_asset_integration
        FOREIGN KEY (tenant_id, integration_id) REFERENCES tenant_media_integration (tenant_id, id);

UPDATE video_asset a
SET integration_id = c.integration_id
FROM media_provider_connection c
WHERE c.tenant_id = a.tenant_id AND c.id = a.provider_connection_id AND c.integration_id IS NOT NULL;

-- YouTube videos always belong to a card; platform-stored (INTERNAL) ones never do.
ALTER TABLE video_asset ADD CONSTRAINT ck_video_asset_integration CHECK (
    (provider = 'YOUTUBE' AND integration_id IS NOT NULL)
    OR (provider <> 'YOUTUBE' AND integration_id IS NULL));

CREATE INDEX idx_video_asset_tenant_integration
    ON video_asset (tenant_id, integration_id, created_at DESC)
    WHERE integration_id IS NOT NULL AND deleted_at IS NULL;

-- ---------------------------------------------------------------------------
-- 5. the V73 channel name / description now live on the card
-- ---------------------------------------------------------------------------

ALTER TABLE tenant_video_configuration
    DROP COLUMN channel_display_name,
    DROP COLUMN channel_description;
