-- integration-management (MEDIA-INT-01, ADR-018): tenant/teacher-owned
-- connections to media & meeting providers, the tenant's integration
-- switches, and the link from a live class to the Zoom connection that
-- hosts it.
--
-- Purely additive: two new tables and one nullable column + FK + index on
-- class_session. No existing row is rewritten; V1-V59 are untouched.

-- ---------------------------------------------------------------------------
-- media_provider_connection
-- ---------------------------------------------------------------------------
-- One row per connected account. ZOOM/YOUTUBE/VIMEO belong to one teacher
-- (scope TEACHER, owner_user_id set); DYNTUBE/BUNNY belong to the tenant
-- (scope TENANT, no owner) - enforced by ck_media_provider_connection_scope.
--
-- credentials_ciphertext is an AES-256-GCM encrypted JSON blob (OAuth
-- tokens or API keys) under MEDIA_CREDENTIALS_ENCRYPTION_KEY. No plaintext
-- secret is ever stored. settings_json holds non-secret provider settings
-- only (e.g. Bunny library id / CDN hostname).
--
-- Rows are never deleted: class_session.provider_connection_id references
-- the account that hosted a meeting, so reconnecting/replacing marks the old
-- row DISCONNECTED and inserts a new one.
CREATE TABLE media_provider_connection (
    id                        UUID PRIMARY KEY,
    tenant_id                 UUID NOT NULL REFERENCES tenant (id),
    provider                  VARCHAR(20) NOT NULL,
    scope                     VARCHAR(10) NOT NULL,
    owner_user_id             UUID,
    auth_type                 VARCHAR(10) NOT NULL,
    external_account_id       VARCHAR(255),
    external_account_label    VARCHAR(255),
    external_account_type     VARCHAR(30),
    credentials_ciphertext    TEXT NOT NULL,
    settings_json             JSONB,
    access_token_expires_at   TIMESTAMPTZ,
    granted_scopes            VARCHAR(1000),
    status                    VARCHAR(20) NOT NULL,
    disconnected_by           VARCHAR(12),
    disconnected_reason       TEXT,
    disconnected_at           TIMESTAMPTZ,
    last_verified_at          TIMESTAMPTZ,
    last_error                VARCHAR(500),
    created_at                TIMESTAMPTZ NOT NULL,
    updated_at                TIMESTAMPTZ NOT NULL,
    created_by                UUID,
    updated_by                UUID,

    CONSTRAINT uq_media_provider_connection_tenant_id UNIQUE (tenant_id, id),

    -- A teacher connection can only belong to a user of the same tenant.
    CONSTRAINT fk_media_provider_connection_owner
        FOREIGN KEY (tenant_id, owner_user_id) REFERENCES tenant_user (tenant_id, id),

    CONSTRAINT ck_media_provider_connection_provider
        CHECK (provider IN ('ZOOM', 'YOUTUBE', 'VIMEO', 'DYNTUBE', 'BUNNY')),
    CONSTRAINT ck_media_provider_connection_auth_type
        CHECK (auth_type IN ('OAUTH', 'API_KEY')),
    CONSTRAINT ck_media_provider_connection_status
        CHECK (status IN ('ACTIVE', 'NEEDS_RECONNECT', 'DISCONNECTED')),
    CONSTRAINT ck_media_provider_connection_disconnected_by
        CHECK (disconnected_by IS NULL OR disconnected_by IN ('OWNER', 'TENANT_ADMIN', 'PROVIDER', 'SYSTEM')),
    CONSTRAINT ck_media_provider_connection_scope CHECK (
        (provider IN ('ZOOM', 'YOUTUBE', 'VIMEO') AND scope = 'TEACHER' AND owner_user_id IS NOT NULL
            AND auth_type = 'OAUTH')
        OR (provider IN ('DYNTUBE', 'BUNNY') AND scope = 'TENANT' AND owner_user_id IS NULL
            AND auth_type = 'API_KEY')
    ),
    CONSTRAINT ck_media_provider_connection_disconnected_consistency CHECK (
        (status <> 'DISCONNECTED' AND disconnected_by IS NULL AND disconnected_at IS NULL)
        OR (status = 'DISCONNECTED' AND disconnected_by IS NOT NULL AND disconnected_at IS NOT NULL)
    )
);

-- At most one live (non-DISCONNECTED) connection per teacher per provider...
CREATE UNIQUE INDEX uq_media_provider_connection_teacher_current
    ON media_provider_connection (tenant_id, provider, owner_user_id)
    WHERE scope = 'TEACHER' AND status <> 'DISCONNECTED';

-- ...and per tenant per tenant-level provider.
CREATE UNIQUE INDEX uq_media_provider_connection_tenant_current
    ON media_provider_connection (tenant_id, provider)
    WHERE scope = 'TENANT' AND status <> 'DISCONNECTED';

-- Teacher's own integrations page / live-class scheduling lookup.
CREATE INDEX idx_media_provider_connection_tenant_owner_provider
    ON media_provider_connection (tenant_id, owner_user_id, provider, created_at);

-- Tenant Admin integrations page.
CREATE INDEX idx_media_provider_connection_tenant_provider_status
    ON media_provider_connection (tenant_id, provider, status);

-- ADR-018 §5: the Zoom app_deauthorized webhook identifies only the Zoom
-- user, so it is the one cross-tenant lookup on this table (it can only
-- disconnect). Deliberately not tenant-leading.
CREATE INDEX idx_media_provider_connection_provider_external_account
    ON media_provider_connection (provider, external_account_id)
    WHERE status <> 'DISCONNECTED';

-- ---------------------------------------------------------------------------
-- tenant_media_integration_settings
-- ---------------------------------------------------------------------------
-- The tenant's switches for teacher-level providers and its choice of video
-- host. At most one row per tenant; an absent row means the defaults (all
-- teacher providers enabled, no video host).
CREATE TABLE tenant_media_integration_settings (
    id                UUID PRIMARY KEY,
    tenant_id         UUID NOT NULL REFERENCES tenant (id),
    zoom_enabled      BOOLEAN NOT NULL DEFAULT TRUE,
    youtube_enabled   BOOLEAN NOT NULL DEFAULT TRUE,
    vimeo_enabled     BOOLEAN NOT NULL DEFAULT TRUE,
    video_host        VARCHAR(10) NOT NULL DEFAULT 'NONE',
    created_at        TIMESTAMPTZ NOT NULL,
    updated_at        TIMESTAMPTZ NOT NULL,
    created_by        UUID,
    updated_by        UUID,

    CONSTRAINT uq_tenant_media_integration_settings_tenant UNIQUE (tenant_id),
    CONSTRAINT ck_tenant_media_integration_settings_video_host
        CHECK (video_host IN ('NONE', 'DYNTUBE', 'BUNNY'))
);

-- ---------------------------------------------------------------------------
-- class_session: which connection hosts the meeting
-- ---------------------------------------------------------------------------
-- NULL for every existing (fake-provider) session and for sessions not yet
-- provisioned. Composite FK keeps the connection inside the session's own
-- tenant.
ALTER TABLE class_session
    ADD COLUMN provider_connection_id UUID;

ALTER TABLE class_session
    ADD CONSTRAINT fk_class_session_provider_connection
        FOREIGN KEY (tenant_id, provider_connection_id)
        REFERENCES media_provider_connection (tenant_id, id);

CREATE INDEX idx_class_session_tenant_provider_connection
    ON class_session (tenant_id, provider_connection_id, scheduled_end);
