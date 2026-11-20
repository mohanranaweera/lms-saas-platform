-- content-management (Klass gaps round 2, plan docs/plans/klass-gaps-round2.md
-- "Database impact - V88", decisions R2-D1..R2-D3): institute-wide media file
-- library.
--
-- 1. New tenant-owned `media_file`: one uploaded document/image per row,
--    stored once in object storage (`object_key`) and attachable to any
--    number of lessons without copying. Soft delete only (`deleted_at` /
--    `deleted_by`, both set or both NULL) so attachment/audit history keeps
--    resolving. `kind` follows R2-D3 (upload types unchanged: PDF, PNG,
--    JPEG, GIF, UTF-8 text) - DOCUMENT / IMAGE / OTHER only.
-- 2. `material.media_file_id`: a PDF/IMAGE/DOCUMENT/OTHER material may now be
--    backed by a library file instead of its own `storage_object_key`.
--    `ck_material_storage_key_required` (V51) is re-created to accept either
--    source; `ck_material_media_file_shape` forbids a row carrying both and
--    restricts library backing to file-type materials. A live (not removed,
--    V82) material may attach a given library file to a lesson at most once.
--
-- Additive only. No backfill: every existing material keeps its own
-- `storage_object_key` and `media_file_id` stays NULL; no existing material,
-- video, watch-session or audit row is updated or deleted. Every existing
-- row satisfies the re-created / new CHECKs (media_file_id IS NULL and the
-- old storage-key rule is a strict subset of the new one).

-- ---------------------------------------------------------------------------
-- 1. media_file
-- ---------------------------------------------------------------------------

CREATE TABLE media_file (
    id                 UUID PRIMARY KEY,
    tenant_id          UUID NOT NULL REFERENCES tenant (id),
    kind               VARCHAR(20) NOT NULL,
    title              VARCHAR(255) NOT NULL,
    original_filename  VARCHAR(255) NOT NULL,
    content_type       VARCHAR(100) NOT NULL,
    size_bytes         BIGINT NOT NULL,
    object_key         VARCHAR(1024) NOT NULL,
    sha256             CHAR(64) NOT NULL,
    uploaded_by        UUID NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL,
    updated_at         TIMESTAMPTZ NOT NULL,
    created_by         UUID,
    updated_by         UUID,
    deleted_at         TIMESTAMPTZ,
    deleted_by         UUID,

    CONSTRAINT uq_media_file_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_media_file_object_key UNIQUE (object_key),

    CONSTRAINT fk_media_file_uploaded_by FOREIGN KEY (tenant_id, uploaded_by)
        REFERENCES tenant_user (tenant_id, id),
    CONSTRAINT fk_media_file_deleted_by FOREIGN KEY (tenant_id, deleted_by)
        REFERENCES tenant_user (tenant_id, id),

    CONSTRAINT ck_media_file_kind CHECK (kind IN ('DOCUMENT', 'IMAGE', 'OTHER')),
    CONSTRAINT ck_media_file_title_not_blank CHECK (btrim(title) <> ''),
    CONSTRAINT ck_media_file_original_filename_not_blank CHECK (btrim(original_filename) <> ''),
    CONSTRAINT ck_media_file_content_type_not_blank CHECK (btrim(content_type) <> ''),
    CONSTRAINT ck_media_file_object_key_not_blank CHECK (btrim(object_key) <> ''),
    CONSTRAINT ck_media_file_size_bytes CHECK (size_bytes > 0),
    CONSTRAINT ck_media_file_sha256_hex CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_media_file_deleted_shape CHECK ((deleted_at IS NULL) = (deleted_by IS NULL))
);

-- Library listing: newest first, keyset-paginated, live rows only.
CREATE INDEX idx_media_file_tenant_live_created
    ON media_file (tenant_id, created_at DESC, id DESC)
    WHERE deleted_at IS NULL;

-- Library listing filtered by kind (Documents / Images tabs, summary counts).
CREATE INDEX idx_media_file_tenant_live_kind_created
    ON media_file (tenant_id, kind, created_at DESC)
    WHERE deleted_at IS NULL;

-- "My uploads" filter and the composite FK fk_media_file_uploaded_by.
CREATE INDEX idx_media_file_tenant_uploaded_by
    ON media_file (tenant_id, uploaded_by);

-- Per-tenant duplicate-content lookup on upload.
CREATE INDEX idx_media_file_tenant_sha256
    ON media_file (tenant_id, sha256);

-- ---------------------------------------------------------------------------
-- 2. material.media_file_id
-- ---------------------------------------------------------------------------

ALTER TABLE material
    ADD COLUMN media_file_id UUID;

ALTER TABLE material
    ADD CONSTRAINT fk_material_media_file FOREIGN KEY (tenant_id, media_file_id)
        REFERENCES media_file (tenant_id, id);

ALTER TABLE material DROP CONSTRAINT ck_material_storage_key_required;

ALTER TABLE material
    ADD CONSTRAINT ck_material_storage_key_required
        CHECK (material_type NOT IN ('PDF', 'IMAGE', 'DOCUMENT', 'OTHER')
               OR storage_object_key IS NOT NULL
               OR media_file_id IS NOT NULL),
    ADD CONSTRAINT ck_material_media_file_shape
        CHECK (media_file_id IS NULL
               OR (material_type IN ('PDF', 'IMAGE', 'DOCUMENT', 'OTHER')
                   AND storage_object_key IS NULL));

-- "Which lessons attach this library file" (attachedLessonCount, delete-409
-- MEDIA_ATTACHED) and the composite FK fk_material_media_file.
CREATE INDEX idx_material_tenant_media_file
    ON material (tenant_id, media_file_id)
    WHERE media_file_id IS NOT NULL;

-- A library file is attached to a lesson at most once among live materials;
-- a removed (V82) attachment does not block re-attaching.
CREATE UNIQUE INDEX uq_material_lesson_media_file
    ON material (tenant_id, lesson_id, media_file_id)
    WHERE media_file_id IS NOT NULL AND removed_at IS NULL;
