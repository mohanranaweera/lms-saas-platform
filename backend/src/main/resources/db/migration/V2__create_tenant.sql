-- Tenant Management: `tenant` table.
--
-- Platform-level table: `tenant` IS the tenant identity table, so it carries
-- no `tenant_id` column on itself (see docs/architecture/database-architecture.md
-- §1). `subdomain` is intentionally globally unique - this is the one
-- legitimate case where a bare global UNIQUE constraint is correct, since
-- subdomain routing must resolve to exactly one tenant platform-wide.
--
-- `plan_id` is left FK-less: no subscription-plan catalog table exists yet
-- (out of scope for this module) - do not fabricate one here.
--
-- `id` is generated application-side (UUIDv7 via
-- com.lms.common.persistence.UuidV7Generator), per V1's baseline convention -
-- no `gen_random_uuid()` default.

CREATE TABLE tenant (
    id            UUID PRIMARY KEY,
    name          VARCHAR NOT NULL,
    subdomain     VARCHAR NOT NULL UNIQUE,
    status        VARCHAR NOT NULL DEFAULT 'trial'
                  CHECK (status IN ('trial', 'active', 'suspended', 'cancelled')),
    plan_id       UUID NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
