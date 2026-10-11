-- Removing a lesson video must not delete the `material` row: once a video has
-- been played, `video_watch_session` rows reference it (fk_video_watch_session_material,
-- V77) and that playback/device history is security evidence that must be kept.
--
-- A VIDEO/RECORDING material removed from a lesson is now marked removed instead
-- of deleted. The video asset itself stays in the tenant's media library.
-- Removed rows are excluded from every material query (Material's @SQLRestriction).
--
-- uq_material_sequence is replaced by a partial unique index so a removed row no
-- longer occupies its position in the lesson.

ALTER TABLE material
    ADD COLUMN removed_at TIMESTAMPTZ,
    ADD COLUMN removed_by UUID,
    ADD CONSTRAINT ck_material_removed_shape
        CHECK ((removed_at IS NULL) = (removed_by IS NULL));

ALTER TABLE material DROP CONSTRAINT uq_material_sequence;

CREATE UNIQUE INDEX uq_material_sequence
    ON material (tenant_id, lesson_id, sequence)
    WHERE removed_at IS NULL;
