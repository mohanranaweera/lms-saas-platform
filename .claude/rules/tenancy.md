# Tenancy Rules

These rules extend the baseline tenancy rules already in root `CLAUDE.md` (every
tenant-owned table has `tenant_id`; tenant identity comes from trusted authenticated
context, never client input; cross-tenant tests are mandatory). They add the concrete
architecture, enforcement, and data-model mechanics — merged from architecture,
security, and database review.

## Architectural framing

- Tenant identity is resolved exactly once per request, at the authentication/edge
  layer (inside `identity-access-service`'s auth filter/interceptor, from the
  validated token/session) — never re-derived inside individual business modules.
- Once resolved, tenant identity is propagated through the request lifecycle via a
  request-scoped context (e.g. a request-scoped bean/holder attached at the filter
  chain), so every downstream module reads the same already-resolved value instead of
  re-parsing tokens or trusting request parameters.
- In-process cross-module calls (service-to-service within the monolith) rely on this
  shared request context rather than each module independently authenticating or
  extracting `tenant_id` from method arguments/payloads.
- Background and async work (queued notifications, scheduled jobs, event listeners)
  does not inherit the request-scoped context automatically — tenant identity must be
  explicitly carried forward as part of the job/event payload when work crosses a
  thread boundary.
- `identity-access-service` and `tenant-management` are the sole owners of tenant
  resolution; all other domains are consumers of the resolved tenant context, never
  reimplementers of tenant-derivation logic.

## Isolation enforcement & testing

- Every new tenant-owned endpoint, repository method, or query is not "done" until it
  has an explicit cross-tenant negative test: an authenticated user from tenant A must
  be proven to receive 403/404 (not 200 with empty/filtered data leaking existence)
  when addressing tenant B's resource by id.
- Tenant isolation must never be enforced only in the frontend (hidden routes,
  disabled UI, client-side filtering) — every check that matters must be re-verified
  server-side on the same request path that touches the database.
- A reviewer must not accept "the query includes a tenant filter" as sufficient
  evidence of isolation on its own — confirm the `tenant_id` used in that filter is
  read from the authenticated/session context (not from a path/query/body parameter,
  header, or hidden form field the client controls).
- When reviewing a change touching a tenant-owned table, check for: (1) a passing
  cross-tenant test added in the same change, (2) no repository method that accepts a
  caller-supplied `tenant_id` parameter, and (3) no new endpoint that skips the shared
  tenant-context resolution mechanism.
- Bulk/admin/reporting endpoints (exports, aggregate stats, search) are a common
  isolation-bypass source — verify these are tenant-scoped too, not just single-resource
  CRUD endpoints.
- A review that "looks correct" but has no accompanying cross-tenant test must be
  treated as isolation being unverified, not isolation being present.

## Data model enforcement

- Every tenant-owned table has a `NOT NULL tenant_id` column with a `FOREIGN KEY` to
  the tenant table — no nullable `tenant_id`, and no "will backfill later" migrations
  that add it as nullable.
- Every tenant-owned table has an index (or composite index) with `tenant_id` as the
  leading column, matching the module's actual tenant-scoped query pattern — flag any
  new table that lacks one.
- Foreign keys from one tenant-owned table to another must reference rows within the
  same tenant; where the referenced table doesn't enforce this by its own PK shape,
  the migration should add a composite FK or a check/trigger ensuring the parent row's
  `tenant_id` matches, not just an FK on the child ID alone.
- Unique constraints that are conceptually "unique per tenant" must be scoped with
  `tenant_id` in the constraint definition, not left as a global unique constraint.
- Reject any migration that introduces a shared/global table holding rows for multiple
  tenants without a `tenant_id` discriminator, and reject any migration that edits or
  renumbers an already-applied migration instead of adding a new one.
