-- VIDEO Phase 5c (ADR-018 Amendment 5, owner approval 2026-10-05): the
-- platform's own OAuth app credentials (today: the one Google app used by
-- every institute's YouTube channel), entered by a Platform Admin instead of
-- only the GOOGLE_OAUTH_CLIENT_ID / _SECRET environment variables, which stay
-- the fallback when no row exists.
--
-- Platform-level tables: platform configuration, not tenant data, so no
-- tenant_id (the documented exception for platform-global entities, like
-- platform_admin_user). Tenants never read or write them.

-- ---------------------------------------------------------------------------
-- platform_oauth_app (com.lms.integrationmanagement.domain.PlatformOAuthApp)
-- ---------------------------------------------------------------------------
-- At most one row per provider. The secret is AES-256-GCM ciphertext
-- (MEDIA_CREDENTIALS_ENCRYPTION_KEY, AAD bound to the provider); it is never
-- returned by any API. Removing the saved credentials deletes the row (the
-- env vars apply again); the history below keeps the trail.

CREATE TABLE platform_oauth_app (
    id                        UUID PRIMARY KEY,
    provider                  VARCHAR(20)  NOT NULL,
    client_id                 VARCHAR(255) NOT NULL,
    client_secret_ciphertext  TEXT         NOT NULL,
    updated_at                TIMESTAMPTZ  NOT NULL,
    updated_by                UUID         NOT NULL,
    CONSTRAINT uq_platform_oauth_app_provider UNIQUE (provider),
    CONSTRAINT ck_platform_oauth_app_provider CHECK (provider IN ('GOOGLE')),
    CONSTRAINT fk_platform_oauth_app_updated_by
        FOREIGN KEY (updated_by) REFERENCES platform_admin_user (id)
);

-- ---------------------------------------------------------------------------
-- platform_oauth_app_change (com.lms.integrationmanagement.domain.PlatformOAuthAppChange)
-- ---------------------------------------------------------------------------
-- Append-only history of every save / removal. audit_log is tenant-scoped
-- (tenant_id NOT NULL), so platform configuration changes are kept here.
-- Client ids are stored masked; the secret never appears, only whether it
-- changed.

CREATE TABLE platform_oauth_app_change (
    id                  UUID PRIMARY KEY,
    provider            VARCHAR(20)  NOT NULL,
    action              VARCHAR(20)  NOT NULL,
    client_id_before    VARCHAR(255),
    client_id_after     VARCHAR(255),
    secret_changed      BOOLEAN      NOT NULL,
    actor_id            UUID         NOT NULL,
    occurred_at         TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_platform_oauth_app_change_provider CHECK (provider IN ('GOOGLE')),
    CONSTRAINT ck_platform_oauth_app_change_action CHECK (action IN ('SAVED', 'REMOVED')),
    CONSTRAINT fk_platform_oauth_app_change_actor
        FOREIGN KEY (actor_id) REFERENCES platform_admin_user (id)
);

CREATE INDEX idx_platform_oauth_app_change_provider_time
    ON platform_oauth_app_change (provider, occurred_at DESC);

CREATE FUNCTION forbid_platform_oauth_app_change_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'platform_oauth_app_change is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_platform_oauth_app_change_append_only
    BEFORE UPDATE OR DELETE ON platform_oauth_app_change
    FOR EACH ROW EXECUTE FUNCTION forbid_platform_oauth_app_change_mutation();
