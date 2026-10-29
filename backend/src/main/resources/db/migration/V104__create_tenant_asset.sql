-- Dedicated tenant-owned role assets. These never enter content media_file library.
CREATE TABLE tenant_asset (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenant(id),
    asset_type VARCHAR(40) NOT NULL,
    object_key VARCHAR(1024) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    uploaded_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    created_by UUID,
    updated_by UUID,
    deleted_at TIMESTAMPTZ,
    deleted_by UUID,
    CONSTRAINT uq_tenant_asset_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_tenant_asset_object_key UNIQUE (object_key),
    CONSTRAINT fk_tenant_asset_uploaded_by FOREIGN KEY (tenant_id, uploaded_by)
        REFERENCES tenant_user (tenant_id, id),
    CONSTRAINT fk_tenant_asset_deleted_by FOREIGN KEY (tenant_id, deleted_by)
        REFERENCES tenant_user (tenant_id, id),
    CONSTRAINT ck_tenant_asset_type CHECK (asset_type IN (
        'SITE_LOGO', 'SITE_FAVICON', 'RECEIPT_LOGO', 'DESKTOP_HERO_BANNER',
        'MOBILE_HERO_BANNER', 'TEACHER_PROFILE_IMAGE', 'STUDENT_PROFILE_IMAGE')),
    CONSTRAINT ck_tenant_asset_content_type CHECK (content_type IN ('image/png', 'image/jpeg')),
    CONSTRAINT ck_tenant_asset_size CHECK (size_bytes > 0 AND size_bytes <= 10485760),
    CONSTRAINT ck_tenant_asset_sha256 CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_tenant_asset_deleted_shape CHECK ((deleted_at IS NULL) = (deleted_by IS NULL))
);
CREATE INDEX idx_tenant_asset_tenant_type ON tenant_asset (tenant_id, asset_type, created_at DESC)
    WHERE deleted_at IS NULL;
CREATE INDEX idx_tenant_asset_tenant_sha256 ON tenant_asset (tenant_id, sha256);
CREATE UNIQUE INDEX uq_tenant_asset_live_singleton ON tenant_asset (tenant_id, asset_type)
    WHERE deleted_at IS NULL AND asset_type IN ('SITE_LOGO', 'SITE_FAVICON', 'RECEIPT_LOGO');
