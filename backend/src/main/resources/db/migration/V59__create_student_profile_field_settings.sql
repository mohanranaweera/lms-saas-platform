-- tenant-management / user-management (STU-PROF-01): tenant-configurable
-- student profile fields.
--
-- 1. student_profile_field_setting (owned by tenant-management): one row per
--    tenant per catalogue field (StudentProfileField, code-defined) recording
--    whether the field is enabled, required, and asked at registration. A
--    tenant with no rows gets code-resolved defaults that reproduce the
--    pre-STU-PROF-01 behaviour (the legacy ConfigDomain.STUDENT require_*
--    flags); the first save writes a row for every catalogue field.
--
-- 2. Nullable profile columns on student_profile (owned by user-management)
--    for the catalogue fields that had no column yet. Nullable for the same
--    reason as V44: required-ness is a per-tenant decision enforced at the
--    API layer, never a global NOT NULL.
--
-- Purely additive. No existing migration is edited, no existing row is
-- modified, no backfill.

-- ---------------------------------------------------------------------------
-- student_profile_field_setting
-- ---------------------------------------------------------------------------
CREATE TABLE student_profile_field_setting (
    id                       UUID PRIMARY KEY,
    tenant_id                UUID NOT NULL REFERENCES tenant (id),
    field_key                VARCHAR(64) NOT NULL,
    enabled                  BOOLEAN NOT NULL,
    required                 BOOLEAN NOT NULL,
    collect_at_registration  BOOLEAN NOT NULL,
    created_at               TIMESTAMPTZ NOT NULL,
    updated_at               TIMESTAMPTZ NOT NULL,
    created_by               UUID,
    updated_by               UUID,

    -- Tenant-leading; also the backing index for the only query shape
    -- ("all settings for the current tenant").
    CONSTRAINT uq_student_profile_field_setting_tenant_field UNIQUE (tenant_id, field_key),
    CONSTRAINT ck_student_profile_field_setting_required_needs_enabled CHECK (NOT required OR enabled),
    CONSTRAINT ck_student_profile_field_setting_registration_needs_enabled
        CHECK (NOT collect_at_registration OR enabled),
    CONSTRAINT ck_student_profile_field_setting_field_key_not_blank CHECK (btrim(field_key) <> '')
);

-- ---------------------------------------------------------------------------
-- student_profile: remaining catalogue columns
-- ---------------------------------------------------------------------------
-- Already present: name (V17); guardian_name, guardian_phone, school, grade,
-- stream, mobile (V44); address_line, city (V58).
ALTER TABLE student_profile
    ADD COLUMN date_of_birth           DATE,
    ADD COLUMN fixed_phone             VARCHAR(50),
    ADD COLUMN whatsapp_number         VARCHAR(50),
    ADD COLUMN telegram_number         VARCHAR(50),
    ADD COLUMN tute_delivery_phone_1   VARCHAR(50),
    ADD COLUMN tute_delivery_phone_2   VARCHAR(50),
    ADD COLUMN address_line_2          VARCHAR(255),
    ADD COLUMN postal_code             VARCHAR(20),
    ADD COLUMN country_code            VARCHAR(2),
    ADD COLUMN nic_number              VARCHAR(20),
    ADD COLUMN passport_number         VARCHAR(20),
    ADD COLUMN driving_license_number  VARCHAR(20),
    ADD COLUMN has_siblings            BOOLEAN,
    ADD COLUMN profession              VARCHAR(255),
    ADD COLUMN special_talent          VARCHAR(255),
    ADD COLUMN university              VARCHAR(255),
    ADD COLUMN learning_medium         VARCHAR(50),
    ADD COLUMN al_year                 SMALLINT,
    ADD COLUMN referral_code           VARCHAR(50);

ALTER TABLE student_profile
    ADD CONSTRAINT ck_student_profile_al_year CHECK (al_year IS NULL OR al_year BETWEEN 1990 AND 2100);
