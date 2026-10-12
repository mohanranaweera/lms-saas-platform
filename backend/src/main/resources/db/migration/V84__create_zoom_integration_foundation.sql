-- Zoom Release 1 Wave 1: tenant-owned integration foundation for MAPPED_TEACHER_HOST.
-- Additive only. Existing teacher-owned media_provider_connection rows and APIs remain unchanged.
-- No provider credentials are stored in these foundation tables.

CREATE TABLE zoom_integration (
    id                    UUID PRIMARY KEY,
    tenant_id             UUID NOT NULL REFERENCES tenant (id),
    name                  VARCHAR(100) NOT NULL,
    description           VARCHAR(500),
    status                VARCHAR(32) NOT NULL,
    teacher_policy        VARCHAR(20) NOT NULL,
    host_strategy         VARCHAR(32) NOT NULL,
    readiness             VARCHAR(20) NOT NULL,
    zoom_account_id       VARCHAR(255),
    zoom_account_label    VARCHAR(255),
    connected_at          TIMESTAMPTZ,
    last_verified_at      TIMESTAMPTZ,
    last_error            VARCHAR(500),
    deleted_at            TIMESTAMPTZ,
    deleted_by            UUID,
    version               INTEGER NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ NOT NULL,
    updated_at            TIMESTAMPTZ NOT NULL,
    created_by            UUID,
    updated_by            UUID,

    CONSTRAINT uq_zoom_integration_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT ck_zoom_integration_status CHECK (
        status IN ('CREATED', 'CONNECTING', 'CONNECTED', 'TOKEN_EXPIRED',
                   'AUTHORIZATION_REVOKED', 'ERROR', 'DISCONNECTED', 'DELETED')
    ),
    CONSTRAINT ck_zoom_integration_teacher_policy CHECK (
        teacher_policy IN ('ALL_TEACHERS', 'SELECTED_TEACHERS')
    ),
    CONSTRAINT ck_zoom_integration_host_strategy CHECK (
        host_strategy IN ('MAPPED_TEACHER_HOST')
    ),
    CONSTRAINT ck_zoom_integration_readiness CHECK (
        readiness IN ('UNVALIDATED', 'VALIDATED', 'BLOCKED')
    ),
    CONSTRAINT ck_zoom_integration_deleted_state CHECK (
        (status <> 'DELETED' AND deleted_at IS NULL)
        OR (status = 'DELETED' AND deleted_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uq_zoom_integration_active_name
    ON zoom_integration (tenant_id, lower(name))
    WHERE status <> 'DELETED';

CREATE INDEX idx_zoom_integration_tenant_status_created
    ON zoom_integration (tenant_id, status, created_at);

CREATE TABLE zoom_integration_allowed_teacher (
    id              UUID PRIMARY KEY,
    tenant_id       UUID NOT NULL REFERENCES tenant (id),
    integration_id  UUID NOT NULL,
    teacher_user_id UUID NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL,
    created_by      UUID,
    updated_by      UUID,

    CONSTRAINT uq_zoom_allowed_teacher_tenant_integration_teacher
        UNIQUE (tenant_id, integration_id, teacher_user_id),
    CONSTRAINT fk_zoom_allowed_teacher_integration
        FOREIGN KEY (tenant_id, integration_id)
        REFERENCES zoom_integration (tenant_id, id),
    CONSTRAINT fk_zoom_allowed_teacher_user
        FOREIGN KEY (tenant_id, teacher_user_id)
        REFERENCES tenant_user (tenant_id, id)
);

CREATE INDEX idx_zoom_allowed_teacher_tenant_teacher
    ON zoom_integration_allowed_teacher (tenant_id, teacher_user_id);

CREATE TABLE zoom_external_user (
    id                    UUID PRIMARY KEY,
    tenant_id             UUID NOT NULL REFERENCES tenant (id),
    integration_id        UUID NOT NULL,
    zoom_user_id          VARCHAR(255) NOT NULL,
    email                 VARCHAR(320),
    display_name          VARCHAR(255),
    status                VARCHAR(20) NOT NULL,
    zoom_user_type        INTEGER,
    host_capability       VARCHAR(20) NOT NULL,
    webinar_capability    VARCHAR(20) NOT NULL,
    last_synced_at        TIMESTAMPTZ,
    capability_checked_at TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL,
    updated_at            TIMESTAMPTZ NOT NULL,
    created_by            UUID,
    updated_by            UUID,

    CONSTRAINT uq_zoom_external_user_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_zoom_external_user_provider_id
        UNIQUE (tenant_id, integration_id, zoom_user_id),
    CONSTRAINT uq_zoom_external_user_tenant_integration_id
        UNIQUE (tenant_id, integration_id, id),
    CONSTRAINT fk_zoom_external_user_integration
        FOREIGN KEY (tenant_id, integration_id)
        REFERENCES zoom_integration (tenant_id, id),
    CONSTRAINT ck_zoom_external_user_status CHECK (
        status IN ('ACTIVE', 'PENDING', 'INACTIVE', 'DELETED', 'UNKNOWN')
    ),
    CONSTRAINT ck_zoom_external_user_host_capability CHECK (
        host_capability IN ('UNKNOWN', 'ELIGIBLE', 'INELIGIBLE')
    ),
    CONSTRAINT ck_zoom_external_user_webinar_capability CHECK (
        webinar_capability IN ('UNKNOWN', 'ELIGIBLE', 'INELIGIBLE')
    ),
    CONSTRAINT ck_zoom_external_user_type CHECK (
        zoom_user_type IS NULL OR zoom_user_type IN (1, 2, 4, 99)
    )
);

CREATE INDEX idx_zoom_external_user_tenant_integration_status
    ON zoom_external_user (tenant_id, integration_id, status);

CREATE INDEX idx_zoom_external_user_tenant_email
    ON zoom_external_user (tenant_id, email);

CREATE TABLE zoom_teacher_user_mapping (
    id                    UUID PRIMARY KEY,
    tenant_id             UUID NOT NULL REFERENCES tenant (id),
    integration_id        UUID NOT NULL,
    teacher_user_id       UUID NOT NULL,
    zoom_external_user_id UUID NOT NULL,
    status                VARCHAR(16) NOT NULL,
    mapped_at             TIMESTAMPTZ NOT NULL,
    revoked_at            TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL,
    updated_at            TIMESTAMPTZ NOT NULL,
    created_by            UUID,
    updated_by            UUID,

    CONSTRAINT fk_zoom_teacher_mapping_integration
        FOREIGN KEY (tenant_id, integration_id)
        REFERENCES zoom_integration (tenant_id, id),
    CONSTRAINT fk_zoom_teacher_mapping_teacher
        FOREIGN KEY (tenant_id, teacher_user_id)
        REFERENCES tenant_user (tenant_id, id),
    CONSTRAINT fk_zoom_teacher_mapping_external_user
        FOREIGN KEY (tenant_id, zoom_external_user_id)
        REFERENCES zoom_external_user (tenant_id, id),
    CONSTRAINT fk_zoom_teacher_mapping_external_user_same_integration
        FOREIGN KEY (tenant_id, integration_id, zoom_external_user_id)
        REFERENCES zoom_external_user (tenant_id, integration_id, id),
    CONSTRAINT ck_zoom_teacher_mapping_status CHECK (
        status IN ('ACTIVE', 'REVOKED')
    ),
    CONSTRAINT ck_zoom_teacher_mapping_revoked_state CHECK (
        (status = 'ACTIVE' AND revoked_at IS NULL)
        OR (status = 'REVOKED' AND revoked_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uq_zoom_teacher_mapping_active_teacher
    ON zoom_teacher_user_mapping (tenant_id, integration_id, teacher_user_id)
    WHERE status = 'ACTIVE';

CREATE UNIQUE INDEX uq_zoom_teacher_mapping_active_external_user
    ON zoom_teacher_user_mapping (tenant_id, integration_id, zoom_external_user_id)
    WHERE status = 'ACTIVE';

CREATE INDEX idx_zoom_teacher_mapping_tenant_integration_status
    ON zoom_teacher_user_mapping (tenant_id, integration_id, status);
