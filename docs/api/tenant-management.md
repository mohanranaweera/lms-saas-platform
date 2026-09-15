# tenant-management — API Contract

Covers PADASH-1's tenant list/approval queue endpoints (MVP-020 —
`com.lms.tenantmanagement`), the narrowed TEN-2 slice: approve/reject of a
`pending_approval` tenant only. `tenant-management` had no HTTP contract file before
this — TEN-2's endpoints were deferred until MVP-020 (see `docs/plans/MVP-004 Tenant
Management.md`'s own status note). Suspend/cancel of an already-active tenant, plan
limits/feature flags, and any tenant-profile/usage editing are **not** covered here —
none of that shipped in this module (module plan §6/§21 item 1).

## Response envelope

`com.lms.common.api.ApiResponse<T>` — see `docs/api/identity-access-service.md`.

## Pagination envelope

`GET /api/v1/platform-admin/tenants` wraps its list payload in
`com.lms.common.api.PageResponse<T>` (the same convention `docs/api/course-management.md`
documents):

```jsonc
{
  "content": [ /* TenantSummaryResponse[] */ ],
  "page": 0,
  "size": 20,
  "totalElements": 47,
  "totalPages": 3
}
```

Standard Spring `Pageable` query params: `page` (default `0`), `size` (default `20`,
server-clamped to a max of `100` regardless of what the caller requests), `sort`
(default `createdAt,DESC`).

## Auth requirements

Every endpoint on this controller requires a valid `Authorization: Bearer <accessToken>`
header issued via the **Platform Admin** login path (`docs/api/identity-access-service.md`'s
`PrincipalKind.PLATFORM_ADMIN`) — never the tenant-scoped login path. Unauthenticated
requests are rejected `401`.

## Authorization model

Class-level `@PreAuthorize("hasRole('PLATFORM_ADMIN')")`, re-confirmed independently at
the service layer (`TenantApprovalService`, via `AuthenticatedPrincipalHolder`) on every
method — not only at the controller — mirroring `LedgerController`/`AuditLogController`'s
thin-controller style. Any non-Platform-Admin caller (tenant-scoped role, or
unauthenticated) is rejected `403`/`401` on every endpoint below, regardless of what a
per-tenant permission grant would otherwise allow.

No endpoint on this controller accepts a `tenantId` field from a request body — the
target tenant is always the path variable (`{id}`), and the resulting status is
determined solely by which endpoint was called, never by a client-supplied status value.

## Endpoints

### `GET /api/v1/platform-admin/tenants`

List/filter the tenant registration queue.

Query params:
- `status` (optional) — one of `pending_approval | trial | active | suspended |
  cancelled | rejected`. Omit for all statuses.
- `page`, `size`, `sort` — see Pagination envelope above.

Response: `ApiResponse<PageResponse<TenantSummaryResponse>>`.

```jsonc
// TenantSummaryResponse
{
  "id": "uuid",
  "name": "string",
  "subdomain": "string",
  "status": "pending_approval",
  "requestedPlan": "string",
  "createdAt": "instant"
}
```

No `tenant`-name/subdomain search query param exists (module plan §8's deliberate
scope decision — see the plan's own note on why no search index was added). Do not
add client-side filtering over a partial page as a substitute; extend the query with
a real search param and index if this becomes a requirement.

### `GET /api/v1/platform-admin/tenants/{id}`

Fetch one tenant's full detail.

Response: `ApiResponse<TenantDetailResponse>`.

```jsonc
// TenantDetailResponse
{
  "id": "uuid",
  "name": "string",
  "subdomain": "string",
  "status": "pending_approval",
  "requestedPlan": "string",
  "contactName": "string | null",
  "contactEmail": "string | null",
  "contactPhone": "string | null",
  "createdAt": "instant"
}
```

Error cases: `404 NOT_FOUND` ("Tenant not found") if `{id}` doesn't resolve to a real
`tenant` row.

### `POST /api/v1/platform-admin/tenants/{id}/approve`

No request body. Transitions the tenant from `pending_approval` to `trial` and
publishes `TenantStatusChangedEvent` (actor id = the authenticated Platform Admin,
tenant id = `{id}`), which writes the `tenant.approved` audit row (see
`docs/api/audit-log-management.md`'s Platform Audit Log section). No separate
provisioning step exists — `requestedPlan` was already captured at registration
(TEN-1); there is no branding/plan-config concept anywhere in this codebase for
approval to provision beyond the status transition itself (module plan §22 addendum
item 4).

Response: `ApiResponse<TenantDetailResponse>` — the tenant's new state.

Error cases:
- `404 NOT_FOUND` — `{id}` doesn't resolve to a real `tenant` row.
- `409 CONFLICT` ("Tenant is not pending approval") — the tenant is not currently
  `pending_approval` (already processed by another admin, or never was pending). The
  frontend (`approve-tenant-dialog.tsx`) surfaces this inline with the dialog kept
  open, never as a generic error banner.

### `POST /api/v1/platform-admin/tenants/{id}/reject`

Same request/response/error shape as `approve`, transitioning to `rejected` instead.
`rejected` is a one-directional terminal state — no login path is provisioned, and
there is no reopen/reversal endpoint. Publishes the same `TenantStatusChangedEvent`
shape, recording `tenant.rejected`.

## Row-level locking

Both `approve` and `reject` read the target tenant via
`TenantRepository#findByIdForUpdate` (a pessimistic row lock) inside the enclosing
`@Transactional` service method, so two concurrent approve/reject calls against the
same tenant serialize instead of racing — the loser observes the already-updated
status and gets the `409` above, never a lost update.

## Not covered by this file

- Suspend/cancel/reactivate of an already-`active` tenant — no endpoint exists.
- Plan/feature-flag editing — no endpoint exists.
- Tenant usage metrics — no endpoint exists.
- Tenant name/subdomain search — no query param exists (see above).

These remain open items — see `docs/requirements/open-decisions.md` and
`docs/plans/MVP-004 Tenant Management.md`'s status note.
