-- VIDEO Phase 13 (ADR-026): DynTube as a managed streaming provider.
--
-- 1. `video_asset.provider` may be DYNTUBE. A DynTube asset is shaped like a
--    YouTube one (tenant provider connection, title, privacy) by the existing
--    ck_video_asset_provider_shape, and like every non-YouTube asset it has
--    no integration card (ck_video_asset_integration, V76).
-- 2. `video_asset.provider_playback_key`: DynTube's playback `key`, stored at
--    status-sync time so playback URLs are signed locally (DynTube's API must
--    not be called on the play path). URL-safe charset only, because it is put
--    into a URL path. It is not a secret on its own (the token needs the
--    account's security key) but is never returned by any API. A READY DynTube
--    asset must have one; other providers never do.
--
-- CHECK changes are added NOT VALID and then validated (plan §26), so
-- existing rows are checked without holding a long lock while adding.
-- No table is created; `media_provider_connection` already allows a tenant
-- DYNTUBE row (V60), and its project id / token-key flag live in the existing
-- non-secret `settings_json` (the key itself stays in the sealed credentials).

ALTER TABLE video_asset
    ADD COLUMN provider_playback_key VARCHAR(64);

ALTER TABLE video_asset
    DROP CONSTRAINT ck_video_asset_provider;

ALTER TABLE video_asset
    ADD CONSTRAINT ck_video_asset_provider
        CHECK (provider IN ('INTERNAL', 'YOUTUBE', 'DYNTUBE')) NOT VALID;

ALTER TABLE video_asset
    ADD CONSTRAINT ck_video_asset_playback_key CHECK (
        (provider = 'DYNTUBE'
            AND (provider_playback_key IS NULL OR provider_playback_key ~ '^[A-Za-z0-9_-]{6,64}$')
            AND (status <> 'READY' OR provider_playback_key IS NOT NULL))
        OR (provider <> 'DYNTUBE' AND provider_playback_key IS NULL)
    ) NOT VALID;

ALTER TABLE video_asset VALIDATE CONSTRAINT ck_video_asset_provider;

ALTER TABLE video_asset VALIDATE CONSTRAINT ck_video_asset_playback_key;
