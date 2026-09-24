-- user-management (Wave 3 - Student registration expansion): new table
-- student_registration_otp, backing the email-only OTP-verification step of
-- public student self-registration (ConfigDomain.STUDENT's otp_required
-- property).
--
-- Deliberately NOT linked to student_profile/tenant_user - no account
-- exists yet at OTP-send time (the whole point of this table is to verify
-- an email BEFORE an account is created). tenant_id is still NOT NULL and
-- tenant-leading-indexed like every other tenant-owned table, resolved from
-- the request's subdomain (TenantResolutionFilter), never client-supplied,
-- per .claude/rules/tenancy.md.
--
-- otp_hash, never the raw OTP, is persisted - mirrors password_hash's
-- precedent on tenant_user (V3): a 6-digit OTP is guessable enough that the
-- real protection here is short TTL + attempt_count rate-limiting
-- (enforced at the service layer), not the hash alone, but the raw value is
-- still never stored, logged, or persisted beyond the single response/email
-- it was generated for.
--
-- attempt_count supports per-row verify-attempt rate-limiting (reject after
-- N wrong guesses against a still-valid, unconsumed row) in addition to the
-- short TTL (expires_at) - a caller must exhaust neither to succeed.
--
-- consumed_at (nullable, set once) marks a row used - an already-consumed
-- OTP must never verify a second time, closing a replay path.

CREATE TABLE student_registration_otp (
    id             UUID PRIMARY KEY,
    tenant_id      UUID NOT NULL REFERENCES tenant (id),
    email          VARCHAR(255) NOT NULL,
    otp_hash       VARCHAR(255) NOT NULL,
    expires_at     TIMESTAMPTZ NOT NULL,
    consumed_at    TIMESTAMPTZ NULL,
    attempt_count  INTEGER NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL,

    CONSTRAINT ck_student_registration_otp_attempt_count CHECK (attempt_count >= 0)
);

-- Tenant-leading index shaped to this table's real query pattern: "find the
-- most recent still-valid OTP row for (tenant, email)" on both send
-- (rate-limit check) and verify.
CREATE INDEX idx_student_registration_otp_tenant_email
    ON student_registration_otp (tenant_id, email, created_at DESC);
