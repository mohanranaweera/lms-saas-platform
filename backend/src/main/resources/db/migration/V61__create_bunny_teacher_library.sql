-- integration-management (MEDIA-INT-01A, ADR-018 Amendment 1): one Bunny
-- Stream video library per teacher, created inside the tenant's Bunny
-- account with the tenant's account-level API key.
--
-- Purely additive: one new table. V60 is untouched. From this migration on,
-- new BUNNY media_provider_connection rows hold an ACCOUNT API key and
-- settings_json = {"keyType":"ACCOUNT"} (V60's comment about library id /
-- CDN hostname in settings_json describes legacy rows only).
--
-- No secret is stored here: a library's own API key and token-auth key are
-- read from Bunny with the account key when needed.
--
-- Rows are never deleted. A failed attempt stays FAILED as history and a
-- retry inserts a new row; a teacher leaving or the Bunny account being
-- replaced leaves the rows untouched (the videos stay the tenant's).
CREATE TABLE bunny_teacher_library (
    id                UUID PRIMARY KEY,
    tenant_id         UUID NOT NULL REFERENCES tenant (id),
    teacher_user_id   UUID NOT NULL,
    connection_id     UUID NOT NULL,
    bunny_library_id  BIGINT,
    library_name      VARCHAR(255) NOT NULL,
    pull_zone_id      BIGINT,
    status            VARCHAR(15) NOT NULL,
    last_error        VARCHAR(500),
    created_at        TIMESTAMPTZ NOT NULL,
    updated_at        TIMESTAMPTZ NOT NULL,
    created_by        UUID,
    updated_by        UUID,

    CONSTRAINT uq_bunny_teacher_library_tenant_id UNIQUE (tenant_id, id),

    -- The teacher and the Bunny connection must both belong to this tenant.
    CONSTRAINT fk_bunny_teacher_library_teacher
        FOREIGN KEY (tenant_id, teacher_user_id) REFERENCES tenant_user (tenant_id, id),
    CONSTRAINT fk_bunny_teacher_library_connection
        FOREIGN KEY (tenant_id, connection_id) REFERENCES media_provider_connection (tenant_id, id),

    CONSTRAINT ck_bunny_teacher_library_status
        CHECK (status IN ('PROVISIONING', 'ACTIVE', 'FAILED')),
    CONSTRAINT ck_bunny_teacher_library_active_has_id
        CHECK (status <> 'ACTIVE' OR bunny_library_id IS NOT NULL)
);

-- At most one live (PROVISIONING/ACTIVE) library per teacher: two concurrent
-- "ensure" calls cannot both start creating a Bunny library.
CREATE UNIQUE INDEX uq_bunny_teacher_library_live
    ON bunny_teacher_library (tenant_id, teacher_user_id)
    WHERE status IN ('PROVISIONING', 'ACTIVE');

-- A Bunny library belongs to at most one live teacher row within a tenant.
CREATE UNIQUE INDEX uq_bunny_teacher_library_bunny_id
    ON bunny_teacher_library (tenant_id, bunny_library_id)
    WHERE bunny_library_id IS NOT NULL AND status = 'ACTIVE';

-- Tenant Admin libraries table / teacher card.
CREATE INDEX idx_bunny_teacher_library_tenant_teacher
    ON bunny_teacher_library (tenant_id, teacher_user_id, created_at);
