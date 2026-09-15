# audit-log-management — API Contract

Covers AUDIT-3's single read endpoint (MVP-019 — `com.lms.auditlogmanagement`). The
write side (`AuditLogApi.record`, MVP-011) has no HTTP surface — it is an in-process
contract other domains call directly or via `AuditLogEventListener` (MVP-019's event
wiring); nothing in this file documents it.

## Response envelope

`com.lms.common.api.ApiResponse<T>` — see `docs/api/identity-access-service.md`.

## Pagination envelope

`GET /api/v1/audit-log` wraps its list payload in `com.lms.common.api.PageResponse<T>`
(the same convention `docs/api/course-management.md` documents):

```jsonc
{
  "content": [ /* AuditLogEntryResponse[] */ ],
  "page": 0,
  "size": 20,
  "totalElements": 47,
  "totalPages": 3
}
```

Standard Spring `Pageable` query params: `page` (default `0`), `size` (default `20`,
server-clamped to a max of `100` regardless of what the caller requests), `sort`
(default `occurredAt,DESC`).

## Auth requirements

Requires a valid `Authorization: Bearer <accessToken>` header (`@PreAuthorize("isAuthenticated()")`
at the controller). `tenantId` is never an accepted parameter in any form (path, query,
body, header) — tenant scoping is exclusively server-resolved from the authenticated
context by `AuditLogRepository`'s inherited tenant-scoped `findAll(Specification, Pageable)`.

## Authorization model

Two layers, both enforced in `AuditLogQueryService.search` (never only at the
controller):

1. The existing, unchanged `DomainArea.AUDIT_LOG`/`PermissionAction.VIEW` grant check
   via `PermissionCheckService.requirePermission`.
2. **Interim MVP-019 restriction** (plan §21 decision 1, option B): an explicit
   allowlist narrower than the grant above — only `TENANT_ADMIN` (Institute Owner) and
   `READ_ONLY_AUDITOR` may actually reach `200`. Every other role currently holding the
   coarse `AUDIT_LOG`/`VIEW` grant (e.g. `FINANCE_STAFF`, `CONTENT_MANAGER`, and the
   other staff sub-roles) is rejected `403` by this endpoint specifically, pending a
   real "own-area" scoping mechanism. This is intentionally narrower than
   `PermissionCheckServiceImpl`'s matrix as it stands today — do not "fix" the `403`
   for those roles by widening the allowlist without a product decision, per the plan's
   §21 note.

A caller with neither the grant nor the allowlist role (e.g. `STUDENT`, `TEACHER`) is
also rejected `403`. Unauthenticated requests are rejected `401` by the standard filter
chain.

## Endpoint

### `GET /api/v1/audit-log`

**Query params** (all optional):

| Param | Type | Notes |
|---|---|---|
| `page`, `size`, `sort` | standard `Pageable` | `size` clamped server-side to 100 |
| `from` | ISO-8601 instant | inclusive lower bound on `occurredAt` |
| `to` | ISO-8601 instant | inclusive upper bound on `occurredAt` |
| `action` | string | exact match against `audit_log.action` |
| `targetEntity` | string | exact match against `audit_log.target_entity` |

`from` after `to` → **`400 VALIDATION_ERROR`**, raised by `AuditLogSearchCriteria`'s
compact constructor before any query executes. An `action`/`targetEntity` value that
doesn't match any row (including one that only exists for another tenant) is **not** a
validation error — it returns `200` with an empty `content` array, so the response
never distinguishes "no such action exists" from "no such action exists for you", per
the cross-tenant-read-safety requirement.

**Success — `200`** (`ApiResponse<PageResponse<AuditLogEntryResponse>>`):

```jsonc
{
  "success": true,
  "data": {
    "content": [
      {
        "id": "...",
        "actorId": "...",
        "actorDisplayName": "teacher@example.test", // best-effort; null if the actor id
                                                       // no longer resolves to a tenant_user
                                                       // row in the caller's own tenant
        "action": "course.price_changed",             // see "Actions currently written" below
        "targetEntity": "course",
        "targetId": "...",
        "reason": null,                                // populated only for actions that carry one
                                                          // (currently: payment.refunded)
        "metadata": { "previousPrice": 10.00, "newPrice": 20.00 }, // action-specific, nullable
        "occurredAt": "2026-09-11T08:40:00Z"
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1
  }
}
```

**`403`** — caller lacks `AUDIT_LOG`/`VIEW`, or holds it but isn't `TENANT_ADMIN`/
`READ_ONLY_AUDITOR` (see "Authorization model" above). **`401`** — unauthenticated.
No `PUT`/`PATCH`/`DELETE` route exists for this resource, for any role — `audit_log` is
append-only at the schema/repository level (`AuditLogRepository` overrides every
delete-shaped method to throw).

## Actions currently written (AUDIT-2 event wiring)

Only these three actions are produced by MVP-019's new `AuditLogEventListener`; other
`audit_log` rows may already exist from MVP-011/013/017's direct-injection call sites
(slip review, reactivation requests, exam scheduling/marking/results) and remain
queryable through this same endpoint unchanged.

| `action` | `targetEntity` | `targetId` | `reason` | `metadata` keys |
|---|---|---|---|---|
| `course.price_changed` | `course` | course id | `null` | `previousPrice`, `newPrice` |
| `material.deleted` | `material` | material id | `null` | `title`, `courseId` |
| `payment.refunded` | `payment_refund` | refund id | refund reason | `paymentId`, `amount` |

**Deliberate, tracked gap:** `PaymentConfirmedEvent`/`PaymentRejectedEvent` (webhook-driven
gateway confirm/reject) have no listener and produce no audit row — there is no real
`tenant_user` actor behind a gateway webhook callback, and `actor_id` is a `NOT NULL` FK
to `tenant_user`. See `docs/requirements/open-decisions.md` and plan §21 decision 2.

## Tenant isolation

- `AuditLogQueryService.search` builds its filters as a `Specification<AuditLog>`
  (`AuditLogSpecifications`) passed into `AuditLogRepository`'s inherited
  `findAll(Specification, Pageable)` — the tenant predicate is auto-AND-combined by
  `TenantAwareRepositoryImpl` into every query, regardless of filter combination. No
  hand-rolled `WHERE tenant_id = ...` clause exists anywhere in this module.
- Covered by `AuditLogCrossTenantIntegrationTest`: an unfiltered search, and three
  filter combinations deliberately crafted to match another tenant's real rows
  (by `action`, by `targetEntity` + date range, and by a colliding `targetId` value),
  all return zero rows from the other tenant.

## Platform Admin audit log (MVP-020, PADASH-2)

Structurally distinct from `GET /api/v1/audit-log` above: a different controller
(`PlatformAdminAuditLogController`), a different service
(`PlatformAdminAuditLogQueryService`), a different repository query, and a different
response DTO (`PlatformAuditLogEntryResponse` — identical in shape to
`AuditLogEntryResponse` plus a mandatory `tenantId`/`tenantName` discriminator, per
plan §14/§15's "every cross-tenant response row carries an explicit tenant
discriminator" rule; never the `AuditLog` JPA entity itself). This is a genuinely
cross-tenant read — the tenant-scoped viewer's own `AUDIT_LOG`/`VIEW` +
`TENANT_ADMIN`/`READ_ONLY_AUDITOR` allowlist (see above) does not apply here at all.

**Auth**: `Authorization: Bearer <accessToken>` issued via the Platform Admin login
path. **Authorization**: class-level `@PreAuthorize("hasRole('PLATFORM_ADMIN')")`,
re-confirmed at the service layer — not the tenant-scoped module's two-layer
grant+allowlist check.

**Read-only, no exceptions**: only `GET` mappings exist on this controller — no
`PUT`/`PATCH`/`DELETE` route exists anywhere on the platform audit log, for any role,
same as the tenant-scoped viewer above.

### `GET /api/v1/platform-admin/audit-log`

Platform-wide, filterable, paginated. Ordering is always `occurredAt DESC`,
enforced server-side by `PlatformAdminAuditLogQueryService` (which strips any
`Sort` from the incoming `Pageable` before it reaches the native `@Query`, since
native queries don't translate Java property names to SQL columns) — a
client-supplied `sort` query parameter is silently ignored, never merged into or
overriding this order.

Query params:
- `from` (optional) — ISO-8601 instant, inclusive lower bound on `occurredAt`.
- `to` (optional) — ISO-8601 instant, inclusive upper bound on `occurredAt`. `from`
  after `to` → `400 VALIDATION_ERROR`.
- `action` (optional) — exact-match string (not a substring/prefix search).
- `page`, `size` — see Pagination envelope above (this endpoint's own default `size`
  of `20`, same server-side clamp behavior as the tenant-scoped endpoint).

Deliberately **no `targetEntity` param** — that remains a tenant-scoped-only filter on
`GET /api/v1/audit-log` above; do not add it here without confirming the underlying
query/index supports a cross-tenant `targetEntity` scan first.

Response: `ApiResponse<PageResponse<PlatformAuditLogEntryResponse>>`.

```jsonc
// PlatformAuditLogEntryResponse
{
  "id": "uuid",
  "tenantId": "uuid",
  "tenantName": "string | null",   // null if the tenant id no longer resolves to a real tenant row
  "actorId": "uuid",
  "action": "string",               // e.g. "tenant.approved" / "tenant.rejected" — see below
  "targetEntity": "string",
  "targetId": "uuid",
  "reason": "string | null",
  "metadata": { "...": "..." } , // "object | null", action-specific
  "occurredAt": "instant"
}
```

Unlike the tenant-scoped `AuditLogEntryResponse`, there is **no `actorDisplayName`**
field — the frontend renders a `shortId(actorId, "Actor")` fallback for every row (see
`docs/ui-ux/platform-admin-dashboard-conventions.md`). Resolving a cross-tenant actor
display name is a separate, unshipped capability.

### `GET /api/v1/platform-admin/audit-log/tenants/{tenantId}`

Single-tenant drill-down — same query params, response shape, and pagination defaults
as the platform-wide endpoint above, filtered to `{tenantId}`.

Error cases: `404 NOT_FOUND` ("Tenant not found") if `{tenantId}` doesn't resolve to a
real `tenant` row (checked via `TenantLookupApi` before the log query runs, so an
unknown tenant id never returns an empty-but-200 page as if the tenant existed).

### Actions currently written for this endpoint

`tenant.approved` / `tenant.rejected`, written by `TenantApprovalService` on every
successful approve/reject transition (see `docs/api/tenant-management.md`) — actor id
is the approving/rejecting Platform Admin, target entity/id is the tenant. Every other
action already listed in "Actions currently written (AUDIT-2 event wiring)" above
remains tenant-scoped-only and does **not** appear through this endpoint (this
endpoint's underlying query is scoped to actions recorded with a Platform Admin actor,
not a general "audit_log minus tenant filter" view).

### `V32` — actor FK relaxed for Platform Admin actors

`audit_log.actor_id` was originally a `NOT NULL FK` to `tenant_user` (`fk_audit_log_actor`,
added in `V21__create_payment_slip_schema.sql` under the then-valid assumption that every
actor was a tenant-scoped user). A Platform Admin's id lives in `platform_admin_user`
instead, so `V32__relax_audit_log_actor_fk_for_platform_admin_actors.sql` drops
`fk_audit_log_actor` entirely — a pure constraint-loosening, no existing row is
invalidated. `actor_id UUID NOT NULL` itself is untouched; only the FK is removed,
since `actor_id` is now genuinely polymorphic (either table) and a single non-polymorphic
FK can no longer express the invariant, mirroring `target_id`'s own pre-existing,
deliberately-FK-less treatment for the same reason.

DB-level enforcement of "`actor_id` names a real actor" moved to the service layer
instead: `AuditLogService`'s `requireKnownActor(UUID)` guard (shared by both `record()`
and `recordForTenant()`, not Platform-Admin-specific) checks
`identityaccessservice.api.UserProvisioningApi#actorExists(UUID)` before persisting any
audit row, for every domain — not only Platform Admin actions. A full polymorphic-FK
schema redesign (discriminator column + per-table partial FKs) was considered and
rejected as out of proportion for this one gap; see the migration's own header comment
and the plan's §22 post-review addendum.
