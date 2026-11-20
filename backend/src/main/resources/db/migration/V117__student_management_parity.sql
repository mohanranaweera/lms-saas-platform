-- user-management + identity-access-service (STU-MGMT-02 Klass student management parity -
-- docs/specs/student-management-parity.md, docs/adr/ADR-033-student-support-impersonation.md).
--
-- Purely additive. No earlier migration is edited. Every new table is tenant-owned:
-- tenant_id NOT NULL FK, tenant-leading indexes, composite same-tenant FKs.

-- ---------------------------------------------------------------------------
-- 0. Same-tenant FK targets. student_profile and device_session have a bare-id
--    PRIMARY KEY; a UNIQUE (tenant_id, id) lets child tables reference them with a
--    composite FK so a row can never point at another tenant's parent.
-- ---------------------------------------------------------------------------

ALTER TABLE student_profile ADD CONSTRAINT uq_student_profile_tenant_id UNIQUE (tenant_id, id);
ALTER TABLE device_session ADD CONSTRAINT uq_device_session_tenant_id UNIQUE (tenant_id, id);

-- ---------------------------------------------------------------------------
-- 1. student_profile: Klass form fields and management flags (spec 3).
--      first_name / last_name   name parts; `name` stays maintained as "first last" for every
--                               other module. Nullable only for rows written by raw SQL
--                               outside the application (the application always sets both
--                               first_name and, when given, last_name).
--      guardian_nic             sensitive - staff only, never in QR/labels/CSV.
--      profile_media_file_id    the tenant media_file (usage STUDENT_PROFILE) of the photo.
--      qr_token                 opaque identification token for the student QR
--                               (payload LMS-STU:1:<token>); hex, >= 192 random bits.
--      qr_rotated_at            when the token was last rotated (NULL = never).
--      pending_approval         self-registration awaiting staff approval (Pending tab).
--      removed_at / removed_by  soft removal from the tenant; history is kept.
--      removal_reason           the reason given by the removing staff member.
-- ---------------------------------------------------------------------------

ALTER TABLE student_profile
    ADD COLUMN first_name             VARCHAR(120),
    ADD COLUMN last_name              VARCHAR(120),
    ADD COLUMN guardian_nic           VARCHAR(20),
    ADD COLUMN profile_media_file_id  UUID,
    ADD COLUMN qr_token               VARCHAR(64),
    ADD COLUMN qr_rotated_at          TIMESTAMPTZ,
    ADD COLUMN pending_approval       BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN removed_at             TIMESTAMPTZ,
    ADD COLUMN removed_by             UUID,
    ADD COLUMN removal_reason         VARCHAR(500);

-- Backfill the name parts by splitting `name` on its first whitespace run.
UPDATE student_profile
SET first_name = left(regexp_replace(regexp_replace(name, '^\s+|\s+$', '', 'g'), '\s.*$', ''), 120),
    last_name  = NULLIF(left(regexp_replace(regexp_replace(name, '^\s+|\s+$', '', 'g'), '^\S+\s*', ''), 120), '');

-- Backfill a random token for every existing student (two v4 UUIDs = 64 hex chars, 244
-- random bits). The column default covers rows inserted outside the application; the
-- application itself generates 48 hex chars (192 bits) from SecureRandom.
UPDATE student_profile
SET qr_token = replace(gen_random_uuid()::text, '-', '') || replace(gen_random_uuid()::text, '-', '');

ALTER TABLE student_profile
    ALTER COLUMN qr_token SET DEFAULT (replace(gen_random_uuid()::text, '-', '')
                                       || replace(gen_random_uuid()::text, '-', '')),
    ALTER COLUMN qr_token SET NOT NULL;

-- Self-registered students whose account is still suspended are the ones awaiting
-- approval (StudentRegistrationService suspends the account when approval is required).
UPDATE student_profile sp
SET pending_approval = true
FROM tenant_user tu
WHERE tu.tenant_id = sp.tenant_id
  AND tu.id = sp.user_id
  AND sp.registration_status = 'SELF_REGISTERED'
  AND tu.status = 'suspended';

ALTER TABLE student_profile
    ADD CONSTRAINT uq_student_profile_tenant_qr_token UNIQUE (tenant_id, qr_token),
    ADD CONSTRAINT ck_student_profile_qr_token CHECK (qr_token ~ '^[0-9a-f]{48,64}$'),
    ADD CONSTRAINT fk_student_profile_profile_media_file FOREIGN KEY (tenant_id, profile_media_file_id)
        REFERENCES media_file (tenant_id, id),
    ADD CONSTRAINT fk_student_profile_removed_by FOREIGN KEY (tenant_id, removed_by)
        REFERENCES tenant_user (tenant_id, id),
    ADD CONSTRAINT ck_student_profile_removed CHECK ((removed_at IS NULL) = (removed_by IS NULL));

-- Directory listing (spec 5: tab ACTIVE | PENDING, removed students hidden).
CREATE INDEX idx_student_profile_tenant_directory
    ON student_profile (tenant_id, pending_approval, index_number)
    WHERE removed_at IS NULL;

-- "Is this photo file still referenced by any student?" before discarding a replaced photo.
CREATE INDEX idx_student_profile_tenant_profile_media_file
    ON student_profile (tenant_id, profile_media_file_id)
    WHERE profile_media_file_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 2. media_file.usage_area gains STUDENT_PROFILE (a student's profile photo). Like the
--    other non-library usages it never appears in the Media Library and is served only
--    through user-management's authorized signed-URL endpoints.
-- ---------------------------------------------------------------------------

ALTER TABLE media_file DROP CONSTRAINT ck_media_file_usage_area;
ALTER TABLE media_file ADD CONSTRAINT ck_media_file_usage_area
    CHECK (usage_area IN ('COURSE_LIBRARY', 'TEACHER_PROFILE', 'EXAM_QUESTION', 'EXAM_QUESTION_PAPER',
                          'EXAM_MARKING_SCHEME', 'EXAM_ANSWER_SHEET', 'EXAM_MARKED_PAPER',
                          'STUDENT_PROFILE'));

-- ---------------------------------------------------------------------------
-- 3. student_remark: staff-only internal remarks on a student (spec 4/5). Plain text,
--    immutable once written (no update/delete path in the application).
-- ---------------------------------------------------------------------------

CREATE TABLE student_remark (
    id                  UUID PRIMARY KEY,
    tenant_id           UUID NOT NULL REFERENCES tenant (id),
    student_profile_id  UUID NOT NULL,
    body                VARCHAR(2000) NOT NULL,
    author_user_id      UUID NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL,

    CONSTRAINT uq_student_remark_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_student_remark_student_profile FOREIGN KEY (tenant_id, student_profile_id)
        REFERENCES student_profile (tenant_id, id),
    CONSTRAINT fk_student_remark_author FOREIGN KEY (tenant_id, author_user_id)
        REFERENCES tenant_user (tenant_id, id),
    CONSTRAINT ck_student_remark_body CHECK (char_length(btrim(body)) BETWEEN 1 AND 2000)
);

CREATE INDEX idx_student_remark_tenant_student_created
    ON student_remark (tenant_id, student_profile_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- 4. impersonation_session (ADR-033): an audited, read-only support session in which a
--    Tenant Admin sees the platform as one student. Rows are never deleted.
--      actor_user_id / actor_session_id   the admin and the admin's own device_session.
--      subject_user_id / subject_student_profile_id   the student.
--      subject_index_number / subject_full_name       display snapshot taken at start.
--      handoff_secret_hash   SHA-256 (base64url) of the 256-bit secret in the
--                            lms_impersonation cookie; the raw secret is never stored.
--      expires_at            absolute expiry (default 30 minutes after start).
--      ended_at / end_reason EXITED | REPLACED | EXPIRED | ACTOR_REVOKED.
-- ---------------------------------------------------------------------------

CREATE TABLE impersonation_session (
    id                          UUID PRIMARY KEY,
    tenant_id                   UUID NOT NULL REFERENCES tenant (id),
    actor_user_id               UUID NOT NULL,
    actor_session_id            UUID NOT NULL,
    subject_user_id             UUID NOT NULL,
    subject_student_profile_id  UUID NOT NULL,
    subject_index_number        VARCHAR(20),
    subject_full_name           VARCHAR(255) NOT NULL,
    reason                      VARCHAR(500) NOT NULL,
    handoff_secret_hash         VARCHAR(64) NOT NULL,
    started_at                  TIMESTAMPTZ NOT NULL,
    expires_at                  TIMESTAMPTZ NOT NULL,
    ended_at                    TIMESTAMPTZ,
    end_reason                  VARCHAR(20),

    CONSTRAINT uq_impersonation_session_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_impersonation_session_handoff UNIQUE (handoff_secret_hash),
    CONSTRAINT fk_impersonation_session_actor FOREIGN KEY (tenant_id, actor_user_id)
        REFERENCES tenant_user (tenant_id, id),
    CONSTRAINT fk_impersonation_session_actor_session FOREIGN KEY (tenant_id, actor_session_id)
        REFERENCES device_session (tenant_id, id),
    CONSTRAINT fk_impersonation_session_subject FOREIGN KEY (tenant_id, subject_user_id)
        REFERENCES tenant_user (tenant_id, id),
    CONSTRAINT fk_impersonation_session_subject_profile FOREIGN KEY (tenant_id, subject_student_profile_id)
        REFERENCES student_profile (tenant_id, id),
    CONSTRAINT ck_impersonation_session_not_self CHECK (actor_user_id <> subject_user_id),
    CONSTRAINT ck_impersonation_session_reason CHECK (char_length(btrim(reason)) BETWEEN 5 AND 500),
    CONSTRAINT ck_impersonation_session_expiry CHECK (expires_at > started_at),
    CONSTRAINT ck_impersonation_session_end CHECK (
        (ended_at IS NULL AND end_reason IS NULL)
        OR (ended_at IS NOT NULL AND end_reason IN ('EXITED', 'REPLACED', 'EXPIRED', 'ACTOR_REVOKED'))
    )
);

-- One open session per admin (a new start ends the previous one first; this makes a
-- concurrent double start fail instead of leaving two open sessions).
CREATE UNIQUE INDEX uq_impersonation_session_open_actor
    ON impersonation_session (tenant_id, actor_user_id)
    WHERE ended_at IS NULL;

-- Per-student support history (newest first).
CREATE INDEX idx_impersonation_session_tenant_subject
    ON impersonation_session (tenant_id, subject_user_id, started_at DESC);
