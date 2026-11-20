-- VIDEO Phase 12: server-authoritative watch-time accounting.
-- This migration is additive. Existing progress is preserved and becomes the
-- initial authorized running total; Redis remains only an ephemeral heartbeat
-- coordination layer and PostgreSQL remains authoritative.

ALTER TABLE video_watch_progress
    ADD COLUMN authorized_watch_seconds INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN last_position_seconds INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN watch_budget_seconds_snapshot INTEGER;

UPDATE video_watch_progress
SET authorized_watch_seconds = total_watched_seconds,
    last_position_seconds = furthest_position_seconds;

ALTER TABLE video_watch_progress
    ADD CONSTRAINT ck_video_watch_progress_authorized_watch_seconds
        CHECK (authorized_watch_seconds >= 0),
    ADD CONSTRAINT ck_video_watch_progress_last_position_seconds
        CHECK (last_position_seconds >= 0),
    ADD CONSTRAINT ck_video_watch_progress_watch_budget_snapshot
        CHECK (watch_budget_seconds_snapshot IS NULL OR watch_budget_seconds_snapshot >= 0);

CREATE INDEX idx_video_watch_progress_tenant_student_asset
    ON video_watch_progress (tenant_id, student_id, video_asset_id);

ALTER TABLE video_watch_session
    ADD COLUMN last_heartbeat_at TIMESTAMPTZ,
    ADD COLUMN last_position_seconds INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN last_heartbeat_state VARCHAR(10) NOT NULL DEFAULT 'IDLE';

ALTER TABLE video_watch_session
    ADD CONSTRAINT ck_video_watch_session_last_position_seconds
        CHECK (last_position_seconds >= 0),
    ADD CONSTRAINT ck_video_watch_session_last_heartbeat_state
        CHECK (last_heartbeat_state IN ('PLAYING', 'PAUSED', 'IDLE')),
    DROP CONSTRAINT ck_video_watch_session_revoked_reason,
    ADD CONSTRAINT ck_video_watch_session_revoked_reason
        CHECK (revoked_reason IS NULL OR revoked_reason IN ('EXPIRED', 'SUPERSEDED_BY_NEW_SESSION', 'ENDED',
            'POLICY_VIOLATION', 'DEVICE_REVOKED', 'INSTALLATION_REVOKED', 'ACCESS_REVOKED', 'ADMIN',
            'LAUNCH_EXPIRED', 'WATCH_TIME_EXHAUSTED'));

CREATE INDEX idx_video_watch_session_tenant_heartbeat
    ON video_watch_session (tenant_id, status, last_heartbeat_at)
    WHERE status = 'ACTIVE';
