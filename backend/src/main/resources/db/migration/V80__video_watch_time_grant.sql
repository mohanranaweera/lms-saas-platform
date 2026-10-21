-- VIDEO Phase 12: extra watch time granted by staff.
--
-- `video_watch_time_grant`: a Tenant Admin (ACCESS_EXPIRY C/E) grants a Student
-- extra watch time for one video, with a required reason. APPEND-ONLY: a grant
-- is never edited or removed; the time a Student may watch is the watch-time
-- budget snapshot on `video_watch_progress` plus the sum of their grants, and
-- `authorized_watch_seconds` (time actually watched) is never touched. The
-- trigger below rejects UPDATE/DELETE so history cannot be rewritten even by an
-- admin script (same model as `enrollment_access_extension`, V65).
--
-- Tenant isolation: composite (tenant_id, ...) FKs into `video_asset` and
-- `tenant_user`, and a tenant_id-leading index for the per-student/per-video
-- lookups the accounting and the staff screen run.

CREATE TABLE video_watch_time_grant (
    id               UUID PRIMARY KEY,
    tenant_id        UUID NOT NULL REFERENCES tenant (id),
    video_asset_id   UUID NOT NULL,
    student_id       UUID NOT NULL,
    extra_seconds    INTEGER NOT NULL,
    reason           VARCHAR(500) NOT NULL,
    granted_by       UUID NOT NULL,
    granted_at       TIMESTAMPTZ NOT NULL,

    CONSTRAINT uq_video_watch_time_grant_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT ck_video_watch_time_grant_extra_seconds CHECK (extra_seconds BETWEEN 60 AND 360000),
    CONSTRAINT ck_video_watch_time_grant_reason CHECK (length(btrim(reason)) > 0),
    CONSTRAINT fk_video_watch_time_grant_asset
        FOREIGN KEY (tenant_id, video_asset_id) REFERENCES video_asset (tenant_id, id),
    CONSTRAINT fk_video_watch_time_grant_student
        FOREIGN KEY (tenant_id, student_id) REFERENCES tenant_user (tenant_id, id),
    CONSTRAINT fk_video_watch_time_grant_granted_by
        FOREIGN KEY (tenant_id, granted_by) REFERENCES tenant_user (tenant_id, id)
);

CREATE INDEX idx_video_watch_time_grant_tenant_student_asset
    ON video_watch_time_grant (tenant_id, student_id, video_asset_id);

CREATE FUNCTION forbid_video_watch_time_grant_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'video_watch_time_grant is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_video_watch_time_grant_append_only
    BEFORE UPDATE OR DELETE ON video_watch_time_grant
    FOR EACH ROW EXECUTE FUNCTION forbid_video_watch_time_grant_mutation();
