-- ===========================================================================
-- V77: AOC Player installations, pairing codes, launch codes (VIDEO Phase 8,
-- ADR-025 + Amendment 1, accepted 2026-10-07).
--
-- * player_pairing_code   - one-time code a signed-in Student's browser hands
--                           to a freshly installed player (aocplayer://pair).
-- * player_installation   - a paired player: an ECDSA P-256 public key bound
--                           to the Student's ADR-022 device (Q-008: it shares
--                           that device's slot). Rows are never deleted.
-- * playback_launch_code  - one-time code that lets a paired player claim one
--                           NATIVE playback session (aocplayer://play).
-- * video_watch_session   - evolved (VID-ADR-002) for NATIVE sessions.
--
-- Codes are stored only as HMAC-SHA256(PLAYER_LAUNCH_CODE_PEPPER, code); the
-- plaintext is never stored. code_hash is globally unique because the
-- unauthenticated pairing-complete / exchange endpoints find the row before
-- any tenant is known (ADR-011-style named cross-tenant lookup) - the same
-- accepted exception as payment.gateway_reference (V19).
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- video_watch_session: NATIVE sessions
-- ---------------------------------------------------------------------------

ALTER TABLE video_watch_session
    ADD CONSTRAINT uq_video_watch_session_tenant_id UNIQUE (tenant_id, id);

ALTER TABLE video_watch_session
    ADD COLUMN material_id            UUID,
    ADD COLUMN launch_channel         VARCHAR(10) NOT NULL DEFAULT 'WEB',
    ADD COLUMN player_installation_id UUID,
    ADD COLUMN issued_for_device_id   UUID;

ALTER TABLE video_watch_session
    DROP CONSTRAINT ck_video_watch_session_status,
    DROP CONSTRAINT ck_video_watch_session_revoked_reason;

-- 'PENDING_LAUNCH' does not fit V51's VARCHAR(10).
ALTER TABLE video_watch_session
    ALTER COLUMN status TYPE VARCHAR(20);

ALTER TABLE video_watch_session
    ADD CONSTRAINT ck_video_watch_session_status
        CHECK (status IN ('PENDING_LAUNCH', 'ACTIVE', 'ENDED', 'REVOKED')),
    ADD CONSTRAINT ck_video_watch_session_revoked_reason
        CHECK (revoked_reason IS NULL OR revoked_reason IN ('EXPIRED', 'SUPERSEDED_BY_NEW_SESSION', 'ENDED',
            'POLICY_VIOLATION', 'DEVICE_REVOKED', 'INSTALLATION_REVOKED', 'ACCESS_REVOKED', 'ADMIN',
            'LAUNCH_EXPIRED')),
    ADD CONSTRAINT ck_video_watch_session_launch_channel
        CHECK (launch_channel IN ('WEB', 'NATIVE')),
    -- A NATIVE session always knows its lesson material and the device it was
    -- issued for; once ACTIVE or ENDED it is bound to the claiming installation.
    ADD CONSTRAINT ck_video_watch_session_native_shape
        CHECK (launch_channel = 'WEB'
            OR (issued_for_device_id IS NOT NULL
                AND material_id IS NOT NULL
                AND (status IN ('PENDING_LAUNCH', 'REVOKED') OR player_installation_id IS NOT NULL))),
    ADD CONSTRAINT fk_video_watch_session_material
        FOREIGN KEY (tenant_id, material_id) REFERENCES material (tenant_id, id),
    ADD CONSTRAINT fk_video_watch_session_device
        FOREIGN KEY (tenant_id, issued_for_device_id) REFERENCES device (tenant_id, id);

-- ---------------------------------------------------------------------------
-- player_installation
-- ---------------------------------------------------------------------------

CREATE TABLE player_installation (
    id                  UUID PRIMARY KEY,
    tenant_id           UUID NOT NULL REFERENCES tenant (id),
    user_id             UUID NOT NULL,
    device_id           UUID NOT NULL,
    public_key          TEXT NOT NULL,
    key_algorithm       VARCHAR(10) NOT NULL,
    platform            VARCHAR(20) NOT NULL,
    player_version      VARCHAR(32) NOT NULL,
    status              VARCHAR(10) NOT NULL,
    paired_at           TIMESTAMPTZ NOT NULL,
    last_seen_at        TIMESTAMPTZ NOT NULL,
    revoked_at          TIMESTAMPTZ,
    revoked_by          UUID,
    revocation_source   VARCHAR(20),
    revoke_reason       VARCHAR(500),

    CONSTRAINT uq_player_installation_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_player_installation_user
        FOREIGN KEY (tenant_id, user_id) REFERENCES tenant_user (tenant_id, id),
    CONSTRAINT fk_player_installation_device
        FOREIGN KEY (tenant_id, device_id) REFERENCES device (tenant_id, id),
    CONSTRAINT ck_player_installation_status
        CHECK (status IN ('ACTIVE', 'REVOKED')),
    CONSTRAINT ck_player_installation_key_algorithm
        CHECK (key_algorithm IN ('ES256')),
    CONSTRAINT ck_player_installation_platform
        CHECK (platform IN ('WINDOWS')),
    CONSTRAINT ck_player_installation_revocation_source
        CHECK (revocation_source IS NULL
            OR revocation_source IN ('STUDENT', 'ADMIN', 'DEVICE_REVOKED', 'REPAIRED')),
    CONSTRAINT ck_player_installation_revoked_consistency
        CHECK ((status = 'REVOKED') = (revoked_at IS NOT NULL)
            AND (status = 'REVOKED') = (revocation_source IS NOT NULL))
);

-- Re-pairing on the same device revokes the previous installation first.
CREATE UNIQUE INDEX uq_player_installation_active_device
    ON player_installation (tenant_id, device_id)
    WHERE status = 'ACTIVE';

CREATE INDEX idx_player_installation_tenant_user_status
    ON player_installation (tenant_id, user_id, status);

ALTER TABLE video_watch_session
    ADD CONSTRAINT fk_video_watch_session_installation
        FOREIGN KEY (tenant_id, player_installation_id) REFERENCES player_installation (tenant_id, id);

CREATE INDEX idx_video_watch_session_tenant_installation_status
    ON video_watch_session (tenant_id, player_installation_id, status)
    WHERE player_installation_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- player_pairing_code
-- ---------------------------------------------------------------------------

CREATE TABLE player_pairing_code (
    id                         UUID PRIMARY KEY,
    tenant_id                  UUID NOT NULL REFERENCES tenant (id),
    user_id                    UUID NOT NULL,
    device_id                  UUID NOT NULL,
    code_hash                  VARCHAR(64) NOT NULL,
    status                     VARCHAR(10) NOT NULL,
    created_at                 TIMESTAMPTZ NOT NULL,
    expires_at                 TIMESTAMPTZ NOT NULL,
    consumed_at                TIMESTAMPTZ,
    consumed_installation_id   UUID,

    CONSTRAINT uq_player_pairing_code_hash UNIQUE (code_hash),
    CONSTRAINT fk_player_pairing_code_user
        FOREIGN KEY (tenant_id, user_id) REFERENCES tenant_user (tenant_id, id),
    CONSTRAINT fk_player_pairing_code_device
        FOREIGN KEY (tenant_id, device_id) REFERENCES device (tenant_id, id),
    CONSTRAINT fk_player_pairing_code_installation
        FOREIGN KEY (tenant_id, consumed_installation_id) REFERENCES player_installation (tenant_id, id),
    CONSTRAINT ck_player_pairing_code_status
        CHECK (status IN ('ISSUED', 'CONSUMED', 'EXPIRED')),
    CONSTRAINT ck_player_pairing_code_ttl
        CHECK (expires_at > created_at AND expires_at <= created_at + INTERVAL '120 seconds'),
    CONSTRAINT ck_player_pairing_code_consumed
        CHECK ((status = 'CONSUMED') = (consumed_at IS NOT NULL AND consumed_installation_id IS NOT NULL))
);

CREATE INDEX idx_player_pairing_code_tenant_user_status
    ON player_pairing_code (tenant_id, user_id, status);

-- ---------------------------------------------------------------------------
-- playback_launch_code
-- ---------------------------------------------------------------------------

CREATE TABLE playback_launch_code (
    id                            UUID PRIMARY KEY,
    tenant_id                     UUID NOT NULL REFERENCES tenant (id),
    playback_session_id           UUID NOT NULL,
    student_id                    UUID NOT NULL,
    issued_for_device_id          UUID NOT NULL,
    code_hash                     VARCHAR(64) NOT NULL,
    status                        VARCHAR(10) NOT NULL,
    created_at                    TIMESTAMPTZ NOT NULL,
    expires_at                    TIMESTAMPTZ NOT NULL,
    consumed_at                   TIMESTAMPTZ,
    consumed_by_installation_id   UUID,

    CONSTRAINT uq_playback_launch_code_hash UNIQUE (code_hash),
    CONSTRAINT fk_playback_launch_code_session
        FOREIGN KEY (tenant_id, playback_session_id) REFERENCES video_watch_session (tenant_id, id),
    CONSTRAINT fk_playback_launch_code_student
        FOREIGN KEY (tenant_id, student_id) REFERENCES tenant_user (tenant_id, id),
    CONSTRAINT fk_playback_launch_code_device
        FOREIGN KEY (tenant_id, issued_for_device_id) REFERENCES device (tenant_id, id),
    CONSTRAINT fk_playback_launch_code_installation
        FOREIGN KEY (tenant_id, consumed_by_installation_id) REFERENCES player_installation (tenant_id, id),
    CONSTRAINT ck_playback_launch_code_status
        CHECK (status IN ('ISSUED', 'CONSUMED', 'EXPIRED', 'REVOKED')),
    CONSTRAINT ck_playback_launch_code_ttl
        CHECK (expires_at > created_at AND expires_at <= created_at + INTERVAL '60 seconds'),
    CONSTRAINT ck_playback_launch_code_consumed
        CHECK ((status = 'CONSUMED') = (consumed_at IS NOT NULL AND consumed_by_installation_id IS NOT NULL))
);

-- At most one usable code per session.
CREATE UNIQUE INDEX uq_playback_launch_code_issued_session
    ON playback_launch_code (tenant_id, playback_session_id)
    WHERE status = 'ISSUED';

CREATE INDEX idx_playback_launch_code_tenant_student_status
    ON playback_launch_code (tenant_id, student_id, status);
