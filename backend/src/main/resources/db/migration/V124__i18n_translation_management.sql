-- I18N-01 (docs/architecture/i18n-translation-management.md sections 2, 5 and 8):
-- tenant UI translation overrides (draft -> immutable releases), per-user UI language
-- preference, and public course title/description variants.
--
-- Purely additive. No earlier migration is edited. Every new table is tenant-owned:
-- tenant_id NOT NULL FK tenant, tenant-leading indexes/uniques, and composite
-- (tenant_id, id) FKs so a row can never reference another tenant's parent.
-- Platform catalogs (English source + reviewed platform translations) are NOT stored here;
-- they ship with the application (classpath:i18n/platform-catalog.json).

-- ---------------------------------------------------------------------------
-- 1. translation_draft: one working row per (tenant, locale, key).
--    action SET   = override the key with value in the next release;
--           RESET = "reset to platform translation" (drop the key from the next release).
--    version      optimistic lock (409 TRANSLATION_DRAFT_STALE).
--    Drafts are working state, not history: publish deletes the drafts it consumed.
-- ---------------------------------------------------------------------------
CREATE TABLE translation_draft (
    id           UUID PRIMARY KEY,
    tenant_id    UUID NOT NULL REFERENCES tenant (id),
    locale       VARCHAR(16) NOT NULL,
    message_key  VARCHAR(200) NOT NULL,
    action       VARCHAR(5) NOT NULL,
    value        TEXT,
    version      BIGINT NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL,
    created_by   UUID,
    updated_by   UUID,

    -- Also the tenant-leading index for the (tenant_id, locale) editor listing.
    CONSTRAINT uq_translation_draft_tenant_locale_key UNIQUE (tenant_id, locale, message_key),

    CONSTRAINT ck_translation_draft_action CHECK (action IN ('SET', 'RESET')),
    CONSTRAINT ck_translation_draft_value CHECK (
        (action = 'SET' AND value IS NOT NULL AND btrim(value) <> '' AND char_length(value) <= 5000)
        OR (action = 'RESET' AND value IS NULL)
    ),
    CONSTRAINT ck_translation_draft_locale CHECK (locale ~ '^[a-z]{2,3}-[A-Z]{2}$'),
    CONSTRAINT ck_translation_draft_key CHECK (message_key ~ '^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$')
);

-- ---------------------------------------------------------------------------
-- 2. translation_release: immutable published versions per (tenant, locale).
--    version_no 1, 2, 3, ... - UNIQUE (tenant_id, locale, version_no) is the publish
--    concurrency guard (409 TRANSLATION_RELEASE_STALE). A rollback is a new release whose
--    snapshot copies an older release of the same tenant and locale. Append-only.
-- ---------------------------------------------------------------------------
CREATE TABLE translation_release (
    id                      UUID PRIMARY KEY,
    tenant_id               UUID NOT NULL REFERENCES tenant (id),
    locale                  VARCHAR(16) NOT NULL,
    version_no              INTEGER NOT NULL,
    summary                 VARCHAR(500),
    entry_count             INTEGER NOT NULL,
    rollback_of_release_id  UUID,
    published_by            UUID NOT NULL,
    published_at            TIMESTAMPTZ NOT NULL,

    CONSTRAINT uq_translation_release_tenant_id UNIQUE (tenant_id, id),
    -- Target of the rollback self-FK: a rollback copies a release of the same locale.
    CONSTRAINT uq_translation_release_tenant_id_locale UNIQUE (tenant_id, id, locale),
    -- Concurrency guard and the "current release" lookup (highest version_no).
    CONSTRAINT uq_translation_release_tenant_locale_version UNIQUE (tenant_id, locale, version_no),

    CONSTRAINT fk_translation_release_publisher FOREIGN KEY (tenant_id, published_by)
        REFERENCES tenant_user (tenant_id, id),
    CONSTRAINT fk_translation_release_rollback_of FOREIGN KEY (tenant_id, rollback_of_release_id, locale)
        REFERENCES translation_release (tenant_id, id, locale),

    CONSTRAINT ck_translation_release_version_no CHECK (version_no >= 1),
    CONSTRAINT ck_translation_release_entry_count CHECK (entry_count >= 0),
    CONSTRAINT ck_translation_release_locale CHECK (locale ~ '^[a-z]{2,3}-[A-Z]{2}$')
);

-- ---------------------------------------------------------------------------
-- 3. translation_release_entry: the full override snapshot of one release.
--    source_hash = SHA-256 (hex) of the English source at publish time; a mismatch with the
--    current source shows "needs review" in the editor (the override is still served).
--    Same-tenant as its release by the composite FK. Append-only.
-- ---------------------------------------------------------------------------
CREATE TABLE translation_release_entry (
    id           UUID PRIMARY KEY,
    tenant_id    UUID NOT NULL REFERENCES tenant (id),
    release_id   UUID NOT NULL,
    message_key  VARCHAR(200) NOT NULL,
    value        TEXT NOT NULL,
    source_hash  VARCHAR(64) NOT NULL,

    -- Also the tenant-leading index for "entries of one release".
    CONSTRAINT uq_translation_release_entry_key UNIQUE (tenant_id, release_id, message_key),

    CONSTRAINT fk_translation_release_entry_release FOREIGN KEY (tenant_id, release_id)
        REFERENCES translation_release (tenant_id, id),

    CONSTRAINT ck_translation_release_entry_value CHECK (btrim(value) <> '' AND char_length(value) <= 5000),
    CONSTRAINT ck_translation_release_entry_source_hash CHECK (source_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_translation_release_entry_key CHECK (message_key ~ '^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$')
);

CREATE FUNCTION forbid_translation_release_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_translation_release_append_only
    BEFORE UPDATE OR DELETE ON translation_release
    FOR EACH ROW EXECUTE FUNCTION forbid_translation_release_mutation();

CREATE TRIGGER trg_translation_release_entry_append_only
    BEFORE UPDATE OR DELETE ON translation_release_entry
    FOR EACH ROW EXECUTE FUNCTION forbid_translation_release_mutation();

-- ---------------------------------------------------------------------------
-- 4. user_locale_preference: a signed-in user's UI language for one tenant. Written only by
--    an explicit switcher action (PUT /api/v1/me/locale). Pure UI state, removed with the user.
-- ---------------------------------------------------------------------------
CREATE TABLE user_locale_preference (
    tenant_id   UUID NOT NULL REFERENCES tenant (id),
    user_id     UUID NOT NULL,
    locale      VARCHAR(16) NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_user_locale_preference PRIMARY KEY (tenant_id, user_id),
    CONSTRAINT fk_user_locale_preference_user FOREIGN KEY (tenant_id, user_id)
        REFERENCES tenant_user (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT ck_user_locale_preference_locale CHECK (locale ~ '^[a-z]{2,3}-[A-Z]{2}$')
);

-- ---------------------------------------------------------------------------
-- 5. Course content language and public title/description variants (section 8).
--    course.content_locale: nullable; legacy rows stay NULL ("not declared") - nothing is
--    inferred or backfilled.
--    course_localization: one row per (course, locale). title/description are the working
--    copy edited in the course editor; published_title/published_description are what the
--    public storefront serves while status = PUBLISHED (so editing a published variant never
--    changes public text until it is published again). The course row keeps its id, slug,
--    owner and authorization; variants are additional fields only. Removed with the course.
-- ---------------------------------------------------------------------------
ALTER TABLE course ADD COLUMN content_locale VARCHAR(16) NULL;

ALTER TABLE course ADD CONSTRAINT ck_course_content_locale
    CHECK (content_locale IS NULL OR content_locale ~ '^[a-z]{2,3}-[A-Z]{2}$');

CREATE TABLE course_localization (
    id                     UUID PRIMARY KEY,
    tenant_id              UUID NOT NULL REFERENCES tenant (id),
    course_id              UUID NOT NULL,
    locale                 VARCHAR(16) NOT NULL,
    title                  VARCHAR(255) NOT NULL,
    description            TEXT,
    published_title        VARCHAR(255),
    published_description  TEXT,
    status                 VARCHAR(10) NOT NULL DEFAULT 'DRAFT',
    published_at           TIMESTAMPTZ,
    published_by           UUID,
    version                BIGINT NOT NULL DEFAULT 0,
    created_at             TIMESTAMPTZ NOT NULL,
    updated_at             TIMESTAMPTZ NOT NULL,
    created_by             UUID,
    updated_by             UUID,

    -- Also the tenant-leading index for "variants of one course" (editor + public reads).
    CONSTRAINT uq_course_localization_course_locale UNIQUE (tenant_id, course_id, locale),

    CONSTRAINT fk_course_localization_course FOREIGN KEY (tenant_id, course_id)
        REFERENCES course (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_course_localization_publisher FOREIGN KEY (tenant_id, published_by)
        REFERENCES tenant_user (tenant_id, id),

    CONSTRAINT ck_course_localization_locale CHECK (locale ~ '^[a-z]{2,3}-[A-Z]{2}$'),
    CONSTRAINT ck_course_localization_title CHECK (btrim(title) <> ''),
    CONSTRAINT ck_course_localization_description CHECK (
        (description IS NULL OR char_length(description) <= 20000)
        AND (published_description IS NULL OR char_length(published_description) <= 20000)
    ),
    CONSTRAINT ck_course_localization_status CHECK (
        (status = 'PUBLISHED' AND published_title IS NOT NULL AND btrim(published_title) <> ''
            AND published_at IS NOT NULL AND published_by IS NOT NULL)
        OR (status = 'DRAFT' AND published_title IS NULL AND published_description IS NULL
            AND published_at IS NULL AND published_by IS NULL)
    )
);
