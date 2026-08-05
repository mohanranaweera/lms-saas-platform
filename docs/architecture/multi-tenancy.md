# Multi-Tenancy Architecture

Status: living document — describes the confirmed, already-decided tenancy mechanism.
This document does not redesign tenancy; it adds implementation-level clarity to what is
already fixed in `CLAUDE.md` and `.claude/rules/tenancy.md`.

**This entire document describes a change-controlled area.** Per root `CLAUDE.md`,
"multi-tenancy strategy" may not be changed without explicit approval. Any deviation from
what is described here — a different tenant-resolution point, a different propagation
mechanism, a different structural-filtering approach than what
`docs/architecture/database-architecture.md` recommends — requires an ADR under
`docs/adr/` before implementation, not after. See `docs/adr/ADR-002-shared-database-tenancy.md`.

## 1. Tenant identity resolution flow

Tenant identity is resolved **exactly once per request**, at the authentication/edge
layer, and never re-derived downstream.

1. **Resolution point:** `identity-access-service`'s authentication filter/interceptor
   resolves tenant identity from the validated, authenticated token/session (JWT claim,
   session record, or equivalent) at the start of the request lifecycle. This is the only
   place tenant identity is derived from a credential.
2. **Trusted source only.** Tenant identity is never accepted from:
   - a request body field,
   - a query/path parameter,
   - a header the client sets directly,
   - a hidden form field,
   - any other client-controlled input.
   A normal frontend user is never trusted to supply `tenant_id` — this is stated in
   `CLAUDE.md` and repeated here because it is the single most common isolation-bypass
   vector: a repository method or endpoint that accepts a caller-supplied `tenant_id`
   parameter is a bug, not a feature, even if the value happens to match the caller's own
   tenant in the happy path.
3. **Propagation via request-scoped context.** Once resolved, tenant identity is attached
   to a request-scoped context/holder at the filter chain (before the request reaches any
   controller or service). Every downstream module — controllers, services, repositories
   — reads the tenant identity from this shared context, not by re-parsing the token or
   trusting a method argument.
4. **In-process cross-module calls reuse the same context.** Because the backend is a
   modular monolith (`.claude/rules/architecture.md`), one domain calling another domain's
   `api` service interface within the same request does so relying on the already-resolved
   request-scoped tenant context — the callee does not re-authenticate or re-extract
   `tenant_id` from the call arguments. `tenant_id` is not passed as an explicit method
   parameter between in-process domain calls during a request; it is read from context on
   both sides.
5. **Background/async work does not inherit the request-scoped context.** Once work
   crosses a thread boundary — a queued notification job, a scheduled job, an event
   listener consuming a domain event asynchronously — the request-scoped tenant context is
   gone. Any such job/event payload must **explicitly carry `tenant_id`** as part of its
   own data, and the consumer must apply it the same way a request-time handler would
   (i.e. still going through the structural tenant-filtering mechanism below, using the
   carried `tenant_id`, never re-deriving it from ambient state). This matters especially
   for:
   - `notification-management` fan-out (email/SMS/WhatsApp/in-app), which is asynchronous
     by design per the scalability guidance in `.claude/rules/architecture.md`.
   - `audit-log-management` and `reporting-analytics` event consumers.
   - any scheduled job (expiry processing, settlement runs, reminder generation).
6. **Sole owners of tenant derivation.** `identity-access-service` and `tenant-management`
   are the only domains that implement tenant-resolution logic. Every other domain is a
   *consumer* of the already-resolved tenant context — no other domain re-implements
   token parsing, subdomain matching, or any other tenant-derivation mechanism. This
   includes tenant resolution for the public-facing tenant storefront and login pages
   (resolved from subdomain/custom domain by the same foundational layer, not
   independently by `content-management` or the frontend).

## 2. Structural tenant-filtering mechanism

This is the same mechanism recommended in `docs/architecture/database-architecture.md`
§2 — restated here for consistency, not redefined:

- **Primary mechanism: `TenantAwareRepository<T>`.** Every tenant-owned entity's
  repository extends a shared base that injects the resolved `tenant_id` (read from the
  request-scoped context described in §1, or the explicitly-carried `tenant_id` for
  async/job contexts) into every finder method, including custom `@Query` methods. No
  tenant-owned repository hand-rolls its own `WHERE tenant_id = ...` filtering outside
  this base.
- **Explicit, named bypass for legitimate cross-tenant access.** Platform-admin
  cross-tenant reporting, support tooling, or aggregate stats are implemented as
  distinctly named methods (e.g. `findAllAcrossTenantsForPlatformReport`) that are *not*
  on the tenant-scoped base — making the bypass visible to reviewers rather than
  disguised as a normal finder. Every such method must justify why it is legitimately
  platform-level, not tenant-level, in the PR that introduces it.
- **Consistency across modules.** All domains use the same mechanism — do not mix
  `TenantAwareRepository` in one module with a hand-rolled filter or a Hibernate
  `@Filter`-only approach in another. See `docs/architecture/database-architecture.md`
  §2 for the full comparison against the Hibernate `@FilterDef`/`@Filter` alternative,
  and the note that the mechanism choice itself is a candidate for its own ADR before
  the first domain is implemented against it.

## 3. Isolation enforcement and required testing

Structural filtering reduces risk but does not replace verification. The following are
mandatory, per `.claude/rules/tenancy.md`:

### What must exist for a tenant-owned endpoint/repository method/query to be "done"

- **A cross-tenant negative test.** An authenticated user from tenant A must be proven to
  receive `403`/`404` (never `200` with empty or filtered data — that leaks existence of
  the resource) when addressing tenant B's resource by id. This applies to every new or
  changed tenant-owned endpoint, repository method, or query — not just a sample of them.
- **Server-side enforcement only.** Tenant isolation must never be enforced only in the
  frontend (hidden routes, disabled buttons, client-side filtering). Every check that
  matters is re-verified server-side, on the same request path that touches the database.
  Frontend route guards and hidden UI (per `.claude/rules/ui-ux.md`) exist only to avoid a
  flash of wrong content — they are never the source of truth.
- **Bulk/admin/reporting endpoints are in scope too.** Exports, aggregate stats, and
  search endpoints are a common isolation-bypass source and must be tenant-scoped (or
  explicitly, visibly platform-level per §2's bypass pattern) — not assumed safe because
  they "aggregate" rather than return single records.

### What a reviewer must verify (not just "the query has a tenant filter")

A PR touching a tenant-owned table is not adequately reviewed unless the reviewer
confirms, per `.claude/rules/tenancy.md`:

1. A passing cross-tenant negative test was added in the same change.
2. No repository method accepts a caller-supplied `tenant_id` parameter (the filter's
   `tenant_id` must be traceable back to the resolved authenticated/session context
   described in §1 — not a path/query/body parameter, header, or hidden form field).
3. No new endpoint skips the shared tenant-context resolution mechanism (e.g. by
   resolving tenant identity itself from some other input instead of relying on
   `identity-access-service`'s already-resolved context).
4. "The query includes a `WHERE tenant_id = ...`" is **not**, by itself, sufficient
   evidence — that clause's `tenant_id` value must be confirmed to come from trusted
   context, not from something the client controls.

A review that "looks correct" but lacks an accompanying cross-tenant test is treated as
**isolation being unverified**, not isolation being present — do not accept it as done.

### Test data conventions supporting this

Per `.claude/rules/testing.md`, any test suite (backend or E2E) exercising a tenant-owned
table or endpoint sets up **at least two distinct tenants** with their own users/data in
fixture setup, even if a given test doesn't yet assert cross-tenant behavior — this keeps
cross-tenant assertions addable later without a fixture rewrite and makes accidental
single-tenant test bias visible in review. Persistence-touching tests use real
Testcontainers-backed PostgreSQL (and Redis where relevant) — a mocked repository cannot
fail a missing `tenant_id` filter, since the mock simply returns what the test told it to
return.

## 4. Change control

This document describes the confirmed multi-tenancy strategy: shared-schema data model,
single resolution point in `identity-access-service`, request-scoped propagation,
explicit carry-forward for async work, and `TenantAwareRepository` as the structural
filtering mechanism (with Hibernate `@Filter` noted only as a considered alternative in
`docs/architecture/database-architecture.md`).

Any of the following requires an ADR under `docs/adr/` **before** implementation, per
`CLAUDE.md` and `.claude/rules/architecture.md`:

- Resolving tenant identity anywhere other than `identity-access-service`'s auth
  filter/interceptor (e.g. a domain module independently deriving tenant from its own
  logic).
- Propagating tenant identity by any mechanism other than the request-scoped context
  (e.g. passing `tenant_id` as an ordinary method parameter between domains as the
  primary mechanism, rather than context).
- Switching the structural filtering mechanism (e.g. moving off
  `TenantAwareRepository`, or mixing mechanisms across modules).
- Any schema change that stores multiple tenants' rows in a table without a `tenant_id`
  discriminator, or that weakens a per-tenant unique constraint to a global one.
- Any change to how background/async work carries (or fails to carry) tenant identity.

If a task appears to require one of the above, stop and flag it for explicit approval
instead of proceeding.

## Related

- `docs/architecture/database-architecture.md`
- `docs/architecture/authentication-authorization.md`
- `docs/adr/ADR-002-shared-database-tenancy.md`
