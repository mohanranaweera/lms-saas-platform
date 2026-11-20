-- content-management (MEDIA-FOLDERS-01, docs/plans/MEDIA-FOLDERS-01 Media Library Folders.md
-- section 2): folders for the Media Library Uploads tab (media_file, usage_area COURSE_LIBRARY).
--
-- Purely additive. No earlier migration is edited. Internal videos (video_asset), lesson
-- materials (material.media_file_id) and student downloads are untouched: filing a file in a
-- folder only sets media_file.folder_id. Every existing media_file row keeps folder_id = NULL
-- ("Unfiled"), so nobody's visibility changes.
--
-- Tenant-owned: every table carries tenant_id NOT NULL FK tenant, tenant-leading indexes and
-- composite (tenant_id, id) FKs so a row can never reference another tenant's folder or user.

-- 1. media_folder: one folder of the institute's Uploads library.
--    parent_id      NULL = root. Same-tenant composite FK; a folder is never its own parent
--                   (deeper cycles are refused by the application under the tenant's
--                   media-folders advisory lock).
--    name           1..100 characters, not blank (the server strips and validates first);
--                   unique per (tenant, parent) case-insensitively, root included.
--    owner_user_id  the folder's owner (changes only on ownership transfer).
--    created_by     who created it (never changes).
--    version        optimistic version for rename / move / delete / transfer (expectedVersion).
--    Folder delete is a hard delete of an EMPTY folder (no child folder, no live file).
CREATE TABLE media_folder (
    id             UUID PRIMARY KEY,
    tenant_id      UUID NOT NULL REFERENCES tenant (id),
    parent_id      UUID,
    name           VARCHAR(100) NOT NULL,
    owner_user_id  UUID NOT NULL,
    created_by     UUID NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL,
    version        BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT uq_media_folder_tenant_id UNIQUE (tenant_id, id),

    CONSTRAINT fk_media_folder_parent FOREIGN KEY (tenant_id, parent_id)
        REFERENCES media_folder (tenant_id, id),
    CONSTRAINT fk_media_folder_owner FOREIGN KEY (tenant_id, owner_user_id)
        REFERENCES tenant_user (tenant_id, id),
    CONSTRAINT fk_media_folder_created_by FOREIGN KEY (tenant_id, created_by)
        REFERENCES tenant_user (tenant_id, id),

    CONSTRAINT ck_media_folder_not_own_parent CHECK (parent_id IS NULL OR parent_id <> id),
    CONSTRAINT ck_media_folder_name CHECK (btrim(name) <> '' AND char_length(name) <= 100)
);

-- Sibling-name policy (final guard behind the application check): case-insensitive, per
-- tenant and parent, root (parent_id NULL) included.
CREATE UNIQUE INDEX uq_media_folder_sibling_name
    ON media_folder (tenant_id, COALESCE(parent_id, '00000000-0000-0000-0000-000000000000'::uuid), lower(name));

-- Child listings, emptiness check and the composite FK fk_media_folder_parent.
CREATE INDEX idx_media_folder_tenant_parent
    ON media_folder (tenant_id, parent_id);

-- "Folders I own" (non tenant-wide callers) and the composite FK fk_media_folder_owner.
CREATE INDEX idx_media_folder_tenant_owner
    ON media_folder (tenant_id, owner_user_id);

-- 2. media_folder_grant: explicit per-folder access for one member (no inheritance: a grant
--    on a parent says nothing about its children). VIEW = browse/open; UPLOAD = also add
--    files. A grant never gives management rights. Removed with its (empty) folder.
CREATE TABLE media_folder_grant (
    id               UUID PRIMARY KEY,
    tenant_id        UUID NOT NULL REFERENCES tenant (id),
    folder_id        UUID NOT NULL,
    grantee_user_id  UUID NOT NULL,
    access_level     VARCHAR(10) NOT NULL,
    granted_by       UUID NOT NULL,
    granted_at       TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_media_folder_grant_folder FOREIGN KEY (tenant_id, folder_id)
        REFERENCES media_folder (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_media_folder_grant_grantee FOREIGN KEY (tenant_id, grantee_user_id)
        REFERENCES tenant_user (tenant_id, id),
    CONSTRAINT fk_media_folder_grant_granted_by FOREIGN KEY (tenant_id, granted_by)
        REFERENCES tenant_user (tenant_id, id),

    -- One grant per member and folder; also serves "grants of a folder" lookups.
    CONSTRAINT uq_media_folder_grant_folder_grantee UNIQUE (tenant_id, folder_id, grantee_user_id),
    CONSTRAINT ck_media_folder_grant_access_level CHECK (access_level IN ('VIEW', 'UPLOAD'))
);

-- "Folders shared with me" and the composite FK fk_media_folder_grant_grantee.
CREATE INDEX idx_media_folder_grant_tenant_grantee
    ON media_folder_grant (tenant_id, grantee_user_id);

-- 3. media_file.folder_id: NULL = Unfiled (visible to every library viewer, as today).
ALTER TABLE media_file
    ADD COLUMN folder_id UUID;

ALTER TABLE media_file
    ADD CONSTRAINT fk_media_file_folder FOREIGN KEY (tenant_id, folder_id)
        REFERENCES media_folder (tenant_id, id);

-- Folder contents / counts and the "live file blocks folder delete" check.
CREATE INDEX idx_media_file_tenant_folder_live
    ON media_file (tenant_id, folder_id)
    WHERE deleted_at IS NULL;
