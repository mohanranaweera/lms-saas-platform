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
