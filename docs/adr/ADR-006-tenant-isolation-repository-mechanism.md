# ADR-006: Structural Tenant-Isolation Mechanism — `TenantAwareRepository`

## Status

Accepted (2026-08-02). This formalizes the recommendation already written in
`docs/architecture/database-architecture.md` §2, which itself flagged that the choice
needed an approved ADR before the first domain's repository layer is built. Not yet
implemented — no repository code exists yet — but `identity-access-service`/
`tenant-management` repositories may now be written against the
`TenantAwareRepository<T, ID>` mechanism decided below.

## Context

`.claude/rules/backend.md` mandates that tenant filtering be structural, not a manually
repeated `WHERE tenant_id = :tenantId` per query, and names two acceptable mechanisms:

1. A `TenantAwareRepository<T, ID>` base type that every tenant-owned entity's
   repository extends, injecting the resolved `tenant_id` into every finder method.
2. A session-wide Hibernate `@FilterDef`/`@Filter` applied to every tenant-owned
   `@Entity`, enabled per-session from the resolved tenant context.

Both are legitimate; the rule requires picking **one** and applying it consistently
across every domain — "do not mix approaches between modules." This is a platform-wide
pattern all 18 confirmed domains will depend on, and switching it after multiple
domains' repositories exist would be an expensive, risky migration. No domain code
exists yet, so this is the correct moment to decide.

## Decision

Adopt **`TenantAwareRepository<T, ID>` as the primary, mandatory mechanism** for every
tenant-owned entity's repository, with Hibernate `@Filter` explicitly available as a
**future defense-in-depth addition on top of it** (not a replacement), only if a later
review finds a structural gap `TenantAwareRepository` alone doesn't close (e.g.
lazy-loaded association traversal that bypasses repository methods entirely).

- Every tenant-owned entity's Spring Data JPA repository extends
  `TenantAwareRepository<T, ID>` instead of `JpaRepository<T, ID>` directly.
  `TenantAwareRepository` reads the resolved `tenant_id` from the request-scoped tenant
  context (`docs/architecture/multi-tenancy.md`) and injects it into every standard
  finder (`findById`, `findAll`, etc.).
- Custom `@Query`/specification methods on a tenant-owned repository must accept and
  apply `tenant_id` explicitly (or compose against an already-tenant-scoped base query
  method) — `TenantAwareRepository` does not silently rewrite arbitrary JPQL.
- Any method that must legitimately read across tenants (platform-admin cross-tenant
  reporting/support) is **not** placed on `TenantAwareRepository`. It lives on a
  distinct, explicitly named method or interface — e.g.
  `findAllAcrossTenantsForPlatformReport(...)` — so the bypass is visible in code review,
  per `.claude/rules/backend.md`'s "any repository method that bypasses the structural
  filter... must be explicitly named/annotated" rule.
- Background jobs, event listeners, and any code that opens a session outside a normal
  request must resolve and pass `tenant_id` explicitly — `TenantAwareRepository` depends
  on the request-scoped context being present; it is not a substitute for the
  "background work does not inherit tenant context automatically" rule in
  `docs/architecture/multi-tenancy.md`.

### Why `TenantAwareRepository` over Hibernate `@Filter` as the default

- **Visible at the type level.** A reviewer can see "this repository extends
  `TenantAwareRepository`" as a checklist item (per the tenancy review checklist in
  `.claude/rules/tenancy.md`), rather than having to confirm a session-level filter was
  correctly enabled for this particular code path.
- **Fails loudly, not silently, when misused.** Hibernate `@Filter` must be explicitly
  enabled on every `Session`/`EntityManager` at the start of tenant-scoped work; missing
  that enable-step in one place (a new background job, a manually opened session, a
  native query bypassing Hibernate) silently returns **unfiltered, cross-tenant data**
  rather than failing — a materially worse failure mode for a data breach than a missing
  interface a reviewer can visually catch.
- **Composes predictably with native/JPQL queries**, which need explicit parameters
  regardless — `@Filter` and repository-level query methods can otherwise interact in
  non-obvious ways.
- **Gives bypass methods a natural home.** Cross-tenant reads get their own named,
  reviewable methods rather than a filter someone has to remember to disable.

## Consequences

**Positive**

- One consistent, reviewable pattern across all 18 domains from day one.
- Cross-tenant bypass is opt-in and named, not opt-out from a silently-active filter.
- Straightforward to unit-test: a repository either extends `TenantAwareRepository` or
  it doesn't, and mandatory cross-tenant negative tests (per `.claude/rules/testing.md`)
  exercise it directly.

**Negative / trade-offs accepted**

- Does **not** protect ad hoc JPQL/Criteria queries or lazy-loaded association
  traversal that bypasses a repository method entirely — a developer writing a raw
  `EntityManager` query outside `TenantAwareRepository` can still leak cross-tenant
  data. This is why Hibernate `@Filter` is retained as a documented future
  defense-in-depth option, not discarded — if this gap proves real in review, add it
  as a second layer, do not switch away from `TenantAwareRepository` as primary.
- Requires discipline that every new tenant-owned repository actually extends the base
  type — nothing at the database/schema level stops a developer from extending plain
  `JpaRepository` instead. Code review must treat this as a hard checklist item (already
  required by `.claude/rules/tenancy.md`).

## Alternatives considered

- **Hibernate `@Filter` as the primary mechanism** — rejected as the default: the
  silent-unfiltered-data failure mode on a forgotten enable-step is worse than a
  visually-reviewable missing base-class extension, for a system where a missed filter
  is a direct cross-tenant data leak (per ADR-002).
- **Both mechanisms as co-equal, module's choice** — rejected: `.claude/rules/backend.md`
  explicitly requires one consistent approach platform-wide; letting each domain choose
  would make review harder and create inconsistent behavior across a growing domain
  count.

## Related

- `docs/architecture/database-architecture.md` §2 (full technical rationale this ADR
  formalizes)
- `docs/architecture/multi-tenancy.md`
- `.claude/rules/backend.md`, `.claude/rules/tenancy.md`
- ADR-002-shared-database-tenancy.md
