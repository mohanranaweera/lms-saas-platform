-- Wave 10 (device + access policy) - enrollment-management access policy.
--
-- `tenant_access_policy`: tenant-wide grace period after access expiry
-- (default 0 = today's hard cutover, product-owner decision D3).
--
-- `course_access_policy`: per-course overrides - grace period (course beats
-- tenant) and a stricter/looser device limit enforced at course-content time
-- (device precedence student > course > tenant > plan).
--
-- `enrollment_access_extension`: manual access extensions (product-owner
-- decision D1, ADR-022). APPEND-ONLY: `enrollment.access_expires_at` is never
-- updated (ADR-013 lineage model); the effective expiry is the later of that
-- column and the newest extension's `new_expires_at`. The trigger below
-- rejects UPDATE/DELETE so history cannot be rewritten even by an admin script.

CREATE TABLE tenant_access_policy (
    id                  UUID PRIMARY KEY,
    tenant_id           UUID NOT NULL REFERENCES tenant (id),
    grace_period_days   INTEGER NOT NULL DEFAULT 0 CHECK (grace_period_days BETWEEN 0 AND 90),
    updated_by          UUID NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL,

    CONSTRAINT uq_tenant_access_policy_tenant UNIQUE (tenant_id)
);

CREATE TABLE course_access_policy (
    id                  UUID PRIMARY KEY,
    tenant_id           UUID NOT NULL REFERENCES tenant (id),
    course_id           UUID NOT NULL,
    grace_period_days   INTEGER NULL CHECK (grace_period_days BETWEEN 0 AND 90),
    max_devices         INTEGER NULL CHECK (max_devices BETWEEN 1 AND 20),
    updated_by          UUID NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL,

    CONSTRAINT uq_course_access_policy_tenant_course UNIQUE (tenant_id, course_id),
    CONSTRAINT fk_course_access_policy_course
        FOREIGN KEY (tenant_id, course_id) REFERENCES course (tenant_id, id)
);

CREATE TABLE enrollment_access_extension (
    id                      UUID PRIMARY KEY,
    tenant_id               UUID NOT NULL REFERENCES tenant (id),
    enrollment_id           UUID NOT NULL,
    previous_expires_at     TIMESTAMPTZ NOT NULL,
    new_expires_at          TIMESTAMPTZ NOT NULL,
    reason                  VARCHAR(500) NOT NULL,
    granted_by              UUID NOT NULL,
    granted_at              TIMESTAMPTZ NOT NULL,

    CONSTRAINT ck_enrollment_access_extension_later CHECK (new_expires_at > previous_expires_at),
    CONSTRAINT ck_enrollment_access_extension_reason CHECK (length(btrim(reason)) > 0),
    CONSTRAINT fk_enrollment_access_extension_enrollment
        FOREIGN KEY (tenant_id, enrollment_id) REFERENCES enrollment (tenant_id, id)
);

CREATE INDEX idx_enrollment_access_extension_tenant_enrollment
    ON enrollment_access_extension (tenant_id, enrollment_id);

CREATE FUNCTION forbid_enrollment_access_extension_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'enrollment_access_extension is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_enrollment_access_extension_append_only
    BEFORE UPDATE OR DELETE ON enrollment_access_extension
    FOR EACH ROW EXECUTE FUNCTION forbid_enrollment_access_extension_mutation();
