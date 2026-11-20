-- VIDEO Phase 13c (ADR-026 Amendment 1): DynTube video plays in DynTube's own
-- player (JS embed) inside an LMS bridge page, because DynTube serves AES keys
-- only to its own player. `provider_embed_key` is DynTube's `channelKey`.
--
-- It is not a secret (DynTube puts it in public embed code); it is returned only
-- inside an authorized playback descriptor. URL-safe charset only, DynTube rows only.
--
-- Status sync fills it. Sync normally stops at READY, so already-READY DynTube
-- rows without a key are re-queued once here; the sync then only stores the key
-- and leaves their status alone.

ALTER TABLE video_asset
    ADD COLUMN provider_embed_key VARCHAR(64),
    ADD CONSTRAINT ck_video_asset_embed_key_charset
        CHECK (provider_embed_key IS NULL OR provider_embed_key ~ '^[A-Za-z0-9_-]{6,64}$'),
    ADD CONSTRAINT ck_video_asset_embed_key_provider
        CHECK (provider_embed_key IS NULL OR provider = 'DYNTUBE');

UPDATE video_asset
SET next_sync_at = now()
WHERE provider = 'DYNTUBE'
  AND status = 'READY'
  AND provider_embed_key IS NULL
  AND provider_asset_id IS NOT NULL;
