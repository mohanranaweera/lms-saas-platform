-- video-access-management (Wave 5, PAR-17-01 / PAR-20-01..04): creates
-- `video_asset`, `video_playback_policy`, `video_watch_session`, and
-- `video_watch_progress` - the first schema for this domain, which is
-- already a reserved top-level domain in .claude/rules/architecture.md's
-- confirmed backend domain list, not a new domain invented for this wave.
--
-- See docs/parity/waves/wave-05-plan.md §1/§3 for the full analysis. V1-V50
-- are NOT edited by this migration - they are already shared/applied and
-- this repo's migration history is append-only (root CLAUDE.md,
-- .claude/rules/tenancy.md). This is a new, additive
-- `CREATE TABLE`/`CREATE INDEX`/`ALTER TABLE` file only.
--
-- Ownership (per .claude/rules/architecture.md): all four tables below ->
-- com.lms.videoaccessmanagement.domain.
--
-- Tenant isolation: every table carries `tenant_id UUID NOT NULL`, never
-- nullable, with tenant-leading indexes matching this module's real read
-- patterns (asset-status dashboards, per-asset policy lookup, the
-- concurrency/entitlement lookup on watch sessions, per-student progress
-- lookup). Every cross-table reference to another tenant-owned table
-- (`video_asset_id`, `student_id`, `uploaded_by`) is a composite
-- `(tenant_id, ...)` FK into that table - never a bare FK on the child id
-- alone - per .claude/rules/tenancy.md. This is possible with no
-- prerequisite migration for `tenant_user` because it already carries the
-- matching composite unique constraint (`uq_tenant_user_tenant_id`, V3);
-- `video_asset` itself gains the identical `uq_video_asset_tenant_id`
-- composite-FK-target constraint below so `video_playback_policy`/
-- `video_watch_session`/`video_watch_progress` can reference it the same
-- way (mirrors V49's `uq_class_session_tenant_id` precedent exactly).
--
-- `id` has no DB-side DEFAULT on any of these four tables - generated
-- application-side (UUIDv7 via com.lms.common.persistence.UuidV7Generator),
-- per V1's baseline convention, matching every other table in this schema.
--
-- No `ON DELETE CASCADE` on any FK here - a video asset's playback
-- policy/watch-session/watch-progress history must outlive careless
-- deletion attempts, mirroring V25/V49's identical "history outlives its
-- parent" precedent; deleting a `video_asset` that already has policy/
-- session/progress rows is BLOCKED rather than silently cascaded.
--
-- `video_watch_progress` is the sole intentional exception to this schema's
-- append-only financial/audit tables (.claude/rules/backend.md): it is
-- mutable running state (views/duration/furthest-position counters), not a
-- financial or audit trail, so service-layer update-in-place is correct
-- here, unlike `video_watch_session` rows (never mutated in place beyond
-- their own status/revocation columns - a superseded/expired/revoked
-- session is never deleted, only status-transitioned, preserving the full
-- session history for suspicious-activity investigation per
-- .claude/rules/security.md's device/session-anomaly requirements).
--
-- `uq_video_watch_session_jti` is intentionally a GLOBAL unique constraint
-- (not tenant-scoped) - a JWT `jti` is a platform-wide token identity, not a
-- per-tenant one; every lookup FROM a token back to a session still
-- re-checks `tenant_id` against the token's own `tenant_id` claim before
-- trusting the row (plan §7), so this global uniqueness does not create a
-- cross-tenant read/write path.
--
-- `uq_video_watch_session_single_active` (partial unique index) schema-
-- enforces the default and most security-sensitive case,
-- `max_concurrent_sessions = 1`, at the DB level, mirroring
-- .claude/rules/backend.md's "prefer schema-enforced invariants for
-- device-authentication" guidance and V49's identical partial-unique-index
-- technique (`uq_class_session_provider_reference`). A tenant/policy
-- configuring `max_concurrent_sessions > 1` is enforced by a service-layer
-- transactional count-and-lock instead (plan §3/§10 item 5 - the general
-- N-cap case cannot be expressed as a single unique index).

-- ---------------------------------------------------------------------------
-- video_asset (com.lms.videoaccessmanagement.domain.VideoAsset)
-- ---------------------------------------------------------------------------

CREATE TABLE video_asset (
    id                   UUID PRIMARY KEY,
    tenant_id            UUID NOT NULL REFERENCES tenant (id),
    storage_object_key   VARCHAR(1024) NOT NULL,
    original_filename    VARCHAR(255) NOT NULL,
    mime_type            VARCHAR(255) NOT NULL,
    size_bytes           BIGINT NOT NULL,
    duration_seconds     INTEGER,
    status               VARCHAR(10) NOT NULL,
    uploaded_by          UUID NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL,
    updated_at           TIMESTAMPTZ NOT NULL,
    created_by           UUID,
    updated_by           UUID,

    -- Enables composite (tenant_id, id) FKs FROM other tables INTO this one
    -- (video_playback_policy/video_watch_session/video_watch_progress below,
    -- and material.fk_material_video_asset further down this file) - mirrors
    -- uq_class_session_tenant_id (V49)/uq_material_tenant_id (V16)'s
    -- identical precedent for the exact same reason.
    CONSTRAINT uq_video_asset_tenant_id
        UNIQUE (tenant_id, id),

    CONSTRAINT fk_video_asset_uploaded_by
        FOREIGN KEY (tenant_id, uploaded_by) REFERENCES tenant_user (tenant_id, id),

    CONSTRAINT ck_video_asset_size_bytes CHECK (size_bytes > 0),
    CONSTRAINT ck_video_asset_duration_seconds CHECK (duration_seconds IS NULL OR duration_seconds >= 0),
    CONSTRAINT ck_video_asset_status CHECK (status IN ('PENDING', 'READY', 'FAILED'))
);

-- Processing-status dashboards / retry sweeps (tenant-wide, status-filtered).
CREATE INDEX idx_video_asset_tenant_status
    ON video_asset (tenant_id, status);

-- ---------------------------------------------------------------------------
-- video_playback_policy (com.lms.videoaccessmanagement.domain.VideoPlaybackPolicy)
-- ---------------------------------------------------------------------------

CREATE TABLE video_playback_policy (
    id                           UUID PRIMARY KEY,
    tenant_id                    UUID NOT NULL REFERENCES tenant (id),
    video_asset_id               UUID NOT NULL,
    access_start_at              TIMESTAMPTZ,
    access_end_at                TIMESTAMPTZ,
    max_views_per_student        INTEGER,
    max_watch_duration_seconds   INTEGER,
    allow_seeking                BOOLEAN NOT NULL DEFAULT true,
    allow_download               BOOLEAN NOT NULL DEFAULT false,
    watermark_enabled            BOOLEAN NOT NULL DEFAULT true,
    max_concurrent_sessions      INTEGER NOT NULL DEFAULT 1,
    created_at                   TIMESTAMPTZ NOT NULL,
    updated_at                   TIMESTAMPTZ NOT NULL,
    created_by                   UUID,
    updated_by                   UUID,

    -- Exactly one policy per asset - a missing row means "platform default
    -- policy" applied in code, not a nullable-everything row (plan §3).
    CONSTRAINT uq_video_playback_policy_asset
        UNIQUE (tenant_id, video_asset_id),

    CONSTRAINT fk_video_playback_policy_asset
        FOREIGN KEY (tenant_id, video_asset_id) REFERENCES video_asset (tenant_id, id),

    CONSTRAINT ck_video_playback_policy_max_views
        CHECK (max_views_per_student IS NULL OR max_views_per_student > 0),
    CONSTRAINT ck_video_playback_policy_max_watch_duration
        CHECK (max_watch_duration_seconds IS NULL OR max_watch_duration_seconds > 0),
    CONSTRAINT ck_video_playback_policy_max_concurrent_sessions
        CHECK (max_concurrent_sessions > 0)
);

-- ---------------------------------------------------------------------------
-- video_watch_session (com.lms.videoaccessmanagement.domain.VideoWatchSession)
-- ---------------------------------------------------------------------------

CREATE TABLE video_watch_session (
    id                        UUID PRIMARY KEY,
    tenant_id                 UUID NOT NULL REFERENCES tenant (id),
    video_asset_id            UUID NOT NULL,
    student_id                UUID NOT NULL,
    playback_jti              UUID NOT NULL,
    device_fingerprint_hash   VARCHAR(128),
    issued_at                 TIMESTAMPTZ NOT NULL,
    expires_at                TIMESTAMPTZ NOT NULL,
    revoked_at                TIMESTAMPTZ,
    revoked_reason            VARCHAR(30),
    status                    VARCHAR(10) NOT NULL,

    -- A JWT jti is a platform-wide token identity, not a per-tenant one -
    -- see migration header.
    CONSTRAINT uq_video_watch_session_jti
        UNIQUE (playback_jti),

    CONSTRAINT fk_video_watch_session_asset
        FOREIGN KEY (tenant_id, video_asset_id) REFERENCES video_asset (tenant_id, id),
    CONSTRAINT fk_video_watch_session_student
        FOREIGN KEY (tenant_id, student_id) REFERENCES tenant_user (tenant_id, id),

    CONSTRAINT ck_video_watch_session_revoked_reason
        CHECK (revoked_reason IS NULL OR revoked_reason IN ('EXPIRED', 'SUPERSEDED_BY_NEW_SESSION', 'ENDED', 'POLICY_VIOLATION')),
    CONSTRAINT ck_video_watch_session_status
        CHECK (status IN ('ACTIVE', 'ENDED', 'REVOKED'))
);

-- The concurrency/entitlement lookup shape: "does this student already have
-- an active/recent session for this video".
CREATE INDEX idx_video_watch_session_tenant_asset_student_status
    ON video_watch_session (tenant_id, video_asset_id, student_id, status);

-- Schema-enforces the default max_concurrent_sessions = 1 invariant at the
-- DB level - see migration header. A second concurrent ACTIVE row for the
-- same (tenant, video, student) is rejected outright by this index; issuing
-- a new session under this default cap requires transitioning the prior
-- ACTIVE row to a non-ACTIVE status first (service-layer
-- SUPERSEDED_BY_NEW_SESSION transition), never a raw second INSERT.
CREATE UNIQUE INDEX uq_video_watch_session_single_active
    ON video_watch_session (tenant_id, video_asset_id, student_id)
    WHERE status = 'ACTIVE';

-- ---------------------------------------------------------------------------
-- video_watch_progress (com.lms.videoaccessmanagement.domain.VideoWatchProgress)
-- ---------------------------------------------------------------------------

CREATE TABLE video_watch_progress (
    id                            UUID PRIMARY KEY,
    tenant_id                     UUID NOT NULL REFERENCES tenant (id),
    video_asset_id                UUID NOT NULL,
    student_id                    UUID NOT NULL,
    views_count                   INTEGER NOT NULL DEFAULT 0,
    total_watched_seconds         INTEGER NOT NULL DEFAULT 0,
    furthest_position_seconds     INTEGER NOT NULL DEFAULT 0,
    last_watched_at               TIMESTAMPTZ,
    created_at                    TIMESTAMPTZ NOT NULL,
    updated_at                    TIMESTAMPTZ NOT NULL,

    -- One running-total row per student per video - mutable running state,
    -- not a financial/audit trail, so update-in-place is correct here (see
    -- migration header).
    CONSTRAINT uq_video_watch_progress
        UNIQUE (tenant_id, video_asset_id, student_id),

    CONSTRAINT fk_video_watch_progress_asset
        FOREIGN KEY (tenant_id, video_asset_id) REFERENCES video_asset (tenant_id, id),
    CONSTRAINT fk_video_watch_progress_student
        FOREIGN KEY (tenant_id, student_id) REFERENCES tenant_user (tenant_id, id),

    CONSTRAINT ck_video_watch_progress_views_count CHECK (views_count >= 0),
    CONSTRAINT ck_video_watch_progress_total_watched_seconds CHECK (total_watched_seconds >= 0),
    CONSTRAINT ck_video_watch_progress_furthest_position_seconds CHECK (furthest_position_seconds >= 0)
);

-- ---------------------------------------------------------------------------
-- material (com.lms.contentmanagement.material) - completes the V50 change
-- ---------------------------------------------------------------------------
--
-- Added here, not in V50, because it references video_asset which does not
-- exist until this migration (see V50's header - no forward-reference
-- migration). `storage_object_key`/`original_filename`/`mime_type`/
-- `size_bytes` are all relaxed to nullable because `LINK`/`NOTE`/`VIDEO`/
-- `RECORDING` materials (V50) never have an uploaded file - V16 originally
-- defined all four as NOT NULL back when every material was an uploaded
-- file. `size_bytes` keeps its existing `ck_material_size_bytes CHECK
-- (size_bytes > 0)` unchanged - a CHECK constraint is automatically
-- satisfied when the column is NULL (Postgres evaluates the predicate to
-- UNKNOWN, not FALSE), so no separate constraint edit is needed for it.
-- (Found and fixed during this same wave's own integration testing -
-- Material.link/note/video's factories always pass null for these three
-- fields, which the original V51 draft above only accounted for on
-- storage_object_key.)

ALTER TABLE material
    ALTER COLUMN storage_object_key DROP NOT NULL,
    ALTER COLUMN original_filename DROP NOT NULL,
    ALTER COLUMN mime_type DROP NOT NULL,
    ALTER COLUMN size_bytes DROP NOT NULL;

ALTER TABLE material
    ADD COLUMN video_asset_id UUID;

ALTER TABLE material
    ADD CONSTRAINT fk_material_video_asset FOREIGN KEY (tenant_id, video_asset_id)
        REFERENCES video_asset (tenant_id, id);

ALTER TABLE material
    ADD CONSTRAINT ck_material_storage_key_required
        CHECK (material_type NOT IN ('PDF', 'IMAGE', 'DOCUMENT', 'OTHER') OR storage_object_key IS NOT NULL),
    ADD CONSTRAINT ck_material_external_url_required
        CHECK (material_type <> 'LINK' OR external_url IS NOT NULL),
    ADD CONSTRAINT ck_material_note_content_required
        CHECK (material_type <> 'NOTE' OR note_content IS NOT NULL),
    ADD CONSTRAINT ck_material_video_asset_required
        CHECK (material_type NOT IN ('VIDEO', 'RECORDING') OR video_asset_id IS NOT NULL);

-- Reverse lookup used when issuing a playback session ("resolve the owning
-- Material for this video asset" - plan §4) and when deleting/auditing a
-- video asset's material reference.
CREATE INDEX idx_material_tenant_video_asset
    ON material (tenant_id, video_asset_id);
