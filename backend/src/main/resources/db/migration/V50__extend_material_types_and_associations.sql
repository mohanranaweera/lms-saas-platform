-- content-management (Wave 5, PAR-06-03 / PAR-06-05 / PAR-27-01): extends
-- `material` (V16) with a `material_type` discriminator, an optional
-- `class_session` association alongside the existing Lesson association, and
-- download-limit/availability-window columns.
--
-- See docs/parity/waves/wave-05-plan.md §1/§3 for the full analysis. V1-V49
-- are NOT edited by this migration - they are already shared/applied and
-- this repo's migration history is append-only (root CLAUDE.md,
-- .claude/rules/tenancy.md). This is a new, additive
-- `ALTER TABLE`/`CREATE INDEX` file only.
--
-- `material_type` backfills every pre-existing row to `OTHER` via its
-- `DEFAULT` - an honest "uploaded file of unspecified sub-type" label for
-- data that predates this discriminator, not a guess at data that doesn't
-- exist. `NOT NULL` from the start (no nullable-then-backfill-later
-- migration), per .claude/rules/tenancy.md.
--
-- `lesson_id` becomes nullable here because a material may now instead (or
-- additionally) attach to a `class_session` (Wave 4) via the new nullable
-- `session_id` composite FK - `ck_material_lesson_or_session` guarantees a
-- material always attaches to at least a Lesson, a Session, or both, never
-- neither. `fk_material_session` mirrors `fk_material_lesson`'s existing
-- composite-FK shape and its deliberate absence of `ON DELETE CASCADE`
-- (material deletion stays a service-layer, audited action - see V16's
-- header).
--
-- `video_asset_id`, `fk_material_video_asset`, and the per-`material_type`
-- "required field" CHECK constraints described in
-- docs/parity/waves/wave-05-plan.md §3 are deliberately NOT added here -
-- they reference `video_asset`, which does not exist until V51. Adding a
-- forward-referencing FK/CHECK in this file would be invalid; those
-- statements are added in V51 instead, after `video_asset` exists.
--
-- `download_count`/`max_downloads` are read+written via an atomic
-- `UPDATE ... SET download_count = download_count + 1 WHERE ... AND
-- (max_downloads IS NULL OR download_count < max_downloads)` guarded update
-- at the service layer (0 rows updated = limit already reached) - not a
-- read-then-write race. `available_from_at` is the availability-window
-- start; the pre-existing `expiry_at` (V16) remains the window's end.

-- ---------------------------------------------------------------------------
-- material (com.lms.contentmanagement.material) - additive columns
-- ---------------------------------------------------------------------------

ALTER TABLE material
    ADD COLUMN material_type       VARCHAR(20) NOT NULL DEFAULT 'OTHER',
    ADD COLUMN external_url        VARCHAR(2048),
    ADD COLUMN note_content        TEXT,
    ADD COLUMN session_id          UUID,
    ADD COLUMN max_downloads       INTEGER,
    ADD COLUMN download_count      INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN available_from_at   TIMESTAMPTZ;

-- A material may now attach to a class_session instead of (or in addition
-- to) a Lesson - see ck_material_lesson_or_session below.
ALTER TABLE material
    ALTER COLUMN lesson_id DROP NOT NULL;

ALTER TABLE material
    ADD CONSTRAINT fk_material_session FOREIGN KEY (tenant_id, session_id)
        REFERENCES class_session (tenant_id, id);

ALTER TABLE material
    ADD CONSTRAINT ck_material_type
        CHECK (material_type IN ('PDF', 'IMAGE', 'DOCUMENT', 'LINK', 'NOTE', 'VIDEO', 'RECORDING', 'OTHER')),
    ADD CONSTRAINT ck_material_lesson_or_session
        CHECK (lesson_id IS NOT NULL OR session_id IS NOT NULL),
    ADD CONSTRAINT ck_material_max_downloads
        CHECK (max_downloads IS NULL OR max_downloads > 0);

-- Course-session materials view (Teacher/staff/Student browsing one class
-- session's materials) - mirrors idx_class_session_tenant_course_start's
-- (V49) tenant-leading shape for the new access path this column enables.
CREATE INDEX idx_material_tenant_session
    ON material (tenant_id, session_id);
