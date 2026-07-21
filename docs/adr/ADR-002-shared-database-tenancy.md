# ADR-002: Shared-Schema Multi-Tenancy

## Status

Accepted (confirmed in `CLAUDE.md` and `.claude/rules/tenancy.md`).

## Context

The platform serves many tenants (institutes/tutoring businesses) from one SaaS
application. There are three broad multi-tenancy data models to choose from:

1. **Database-per-tenant** — each tenant gets its own PostgreSQL database/schema.
2. **Schema-per-tenant** — one database, one Postgres schema per tenant.
3. **Shared schema, discriminator column** — one database, one schema, every
   tenant-owned table carries a `tenant_id` column that scopes every row.

Root `CLAUDE.md` states the product is "a shared SaaS application with tenant-aware
database model" and explicitly prohibits a separate Docker deployment per normal
tenant. This requirement, combined with the goal of running a modest number of
stateless application instances for a growing number of tenants, rules out the
per-tenant database/schema models as the default approach at MVP.

## Decision

Adopt **shared-schema multi-tenancy with a mandatory `tenant_id` discriminator**:

- Every tenant-owned table has a `NOT NULL tenant_id UUID REFERENCES tenant(id)` —
  never nullable, never optional "for platform-level rows." Platform-level data that
  is genuinely not tenant-owned lives in its own table, not mixed into a tenant table.
- Every tenant-owned table has a composite index leading with `tenant_id` matching the
  module's actual query shape (see `docs/architecture/database-architecture.md`).
- Tenant identity is resolved exactly once per request, at the authentication/edge
  layer inside `identity-access-service`, from the validated token/session — never
  from a client-supplied parameter, header, or body field, and never re-derived
  independently by downstream modules.
- Tenant scoping is enforced **structurally** (a shared `TenantAwareRepository`
  base and/or Hibernate tenant filter applied session-wide — see
  `docs/architecture/database-architecture.md` for the specific mechanism), not left
  to each developer remembering a `WHERE tenant_id = :tenantId` clause per query.
- Any repository method that must legitimately cross tenants (platform-admin
  cross-tenant reporting) is explicitly named to make the bypass visible in review
  (e.g. `findAllAcrossTenants...`).

## Consequences

**Positive**

- One shared connection pool, one shared PostgreSQL instance, and one migration
  history (Flyway) serve all tenants — operationally simple and cost-efficient at
  the tenant counts this platform expects in MVP/Phase 2/3.
- Cross-tenant platform-level reporting/analytics (a real requirement — see
  Platform Admin reports) is straightforward: it's a normal query with an explicit,
  reviewable bypass of tenant scoping, not a fan-out across N databases.
- Schema changes (Flyway migrations) apply once, to one schema, for all tenants —
  no per-tenant migration orchestration.

**Negative / trade-offs accepted**

- A missed or bypassed `tenant_id` filter is a **direct cross-tenant data leak**, not
  merely a performance or noisy-neighbor issue as it might be with per-tenant
  databases. This is why structural enforcement (not manual per-query discipline) and
  mandatory cross-tenant negative tests are non-negotiable requirements, not
  nice-to-haves, for every tenant-owned endpoint/repository/query.
- Very large tenants share table space and query plans with every other tenant —
  index design must lead with `tenant_id` and be validated as data volume grows
  platform-wide, not per tenant.
- A tenant cannot be given a dedicated/isolated datastore without a schema/deployment
  change; if a future enterprise tenant requires physical data isolation for
  compliance reasons, that is a new ADR, not a default capability of this model.

## Alternatives considered

- **Database-per-tenant** — rejected at this stage: contradicts "no separate Docker
  deployment for each normal tenant," and multiplies migration/connection-pool/backup
  operational overhead per tenant. Could be revisited via a new ADR for a specific
  enterprise/compliance-driven exception, not as the platform default.
- **Schema-per-tenant** — rejected for the same operational-overhead reason, with the
  added complexity of dynamic schema resolution per request in a stateless,
  horizontally-scaled app.

## Related

- `docs/architecture/multi-tenancy.md`
- `docs/architecture/database-architecture.md`
- `.claude/rules/tenancy.md`, `.claude/rules/backend.md`
