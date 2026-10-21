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

## AUTH-PWD-01 — First Tenant Admin (ADR-020)

- **`POST /{id}/approve`** now also creates the tenant's first Tenant Admin, in the same transaction, from `contactEmail`, with status `invited`. It queues an account-setup email. If this fails, the approval rolls back.
- **`GET /{id}`** (and the approve and reject responses) add:
  `firstAdmin: { status: NONE|INVITED|ACTIVE|SUSPENDED, emailMasked, invitedAt, inviteExpiresAt }`.
  The email is masked; the other fields are null when status is `NONE`.
- **`POST /{id}/admin-invitation`**, body `{ email? }` → `202 firstAdmin`. What it does depends on the admin's status:
  - `NONE`: invites `email`, or the contact email if none is given.
  - `INVITED`: resends. A different `email` corrects it, and old links stop working at once.
  - `ACTIVE` or `SUSPENDED`: `409`.

  Other responses: `409` if the tenant isn't trial/active or the email is taken; `429` within one minute of the last send; `404` for an unknown tenant. Every action is audit-logged against the tenant.

## TEN-DOM-01 — Custom domains (ADR-021)

A tenant can serve its site on its own hostname. A hostname **resolves to the tenant only while
`ACTIVE`** (DNS ownership proven) and only while the Platform Admin switch for that tenant is on.
Hostnames are unique across the platform. Rows are never deleted.

**Statuses:** `PENDING_VERIFICATION` → `ACTIVE`; `ACTIVE` ↔ `DNS_BROKEN` (daily re-check, and
Verify); any live status → `SUSPENDED` (Platform Admin); `REMOVED` (the tenant removes it, and it
is no longer listed).

**Domain object** (`CustomDomainResponse`):
`{ id, hostname, status, primary, dnsRecords: [{ type: "CNAME"|"TXT", name, value }], verifiedAt,
lastCheckedAt, lastError, suspendedReason, createdAt }`.
`dnsRecords` are the two records the tenant creates: `CNAME <hostname> → <cname target>` (value
`null` while the platform has no target) and `TXT _lms-verify.<hostname> = <verification code>`.

**Overview** (`CustomDomainsResponse`): `{ enabled, available, maxDomains, domains[] }`.
`enabled` is the Platform Admin switch; `available` is `false` while the platform isn't configured
(`PLATFORM_DOMAIN`, `CUSTOM_DOMAIN_CNAME_TARGET`).

### Tenant Admin — `/api/v1/tenant-config/custom-domains`

The tenant comes from the resolved request context only. Permission `CUSTOM_DOMAINS`: `VIEW`
(Tenant Admin, Read-only Auditor), `CREATE_EDIT` (Tenant Admin). Another tenant's domain id is
`404` for every action.

| Method and path | Result | Errors |
|---|---|---|
| `GET` | `200` overview | `403` without `VIEW` |
| `POST` `{ hostname }` | `201` domain, `PENDING_VERIFICATION` | `400 VALIDATION_ERROR` (field `hostname`: not a plain domain, `localhost`, or on the platform's own domain); `403 CUSTOM_DOMAINS_DISABLED`; `409 CUSTOM_DOMAIN_TAKEN`; `409 CUSTOM_DOMAIN_LIMIT_REACHED` (default 3, removed ones not counted); `503` not configured |
| `POST /{id}/verify` | `200` domain. `ACTIVE` when both records match; otherwise the status is unchanged and `lastError` says what's missing. A DNS lookup that can't complete changes nothing. | `403 CUSTOM_DOMAINS_DISABLED`; `404`; `409` suspended; `429` (20 per tenant per hour); `503` |
| `POST /{id}/primary` | `200` domain. The previous primary is cleared. | `409` unless `ACTIVE`; `403`; `404` |
| `DELETE /{id}` | `200`, `data: null`. Soft remove: stops resolving at once and frees the hostname. Allowed while the feature is off. | `404`; `409` suspended |

Audit actions: `custom_domain.added`, `.verified`, `.dns_broken`, `.primary_set`, `.removed`
(target `tenant_custom_domain`, metadata `hostname`).

### Platform Admin — `/api/v1/platform-admin/tenants/{tenantId}`

| Method and path | Result | Errors |
|---|---|---|
| `GET /custom-domains` | `200` overview for that tenant | `404` unknown tenant |
| `PUT /custom-domains-enabled` `{ enabled }` | `200` overview. Switching off stops every domain of the tenant resolving; they come back when switched on. | `400`; `404` |
| `POST /custom-domains/{domainId}/suspend` `{ reason }` | `200` domain, `SUSPENDED`. Stops resolving at once; the tenant can't verify or remove it. | `400` no reason; `404`; `409` already suspended |

Audit actions (against the tenant): `tenant.custom_domains_enabled` / `tenant.custom_domains_disabled`,
`custom_domain.suspended` (with the reason). There is no reinstate endpoint yet (plan §13 follow-up).

`GET /api/v1/platform-admin/tenants/{id}` also returns `customDomainsEnabled`.

### Links

Email links (invitation, reset), PayHere return URLs and media OAuth return URLs use the tenant's
primary `ACTIVE` custom domain when there is one, keeping the configured template's scheme and
port, and otherwise `{subdomain}` from the template.
