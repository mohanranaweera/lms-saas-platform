# ledger-settlement-management — API Contract

Covers the Payment History / Payment Dashboard read endpoints (MVP-010 / Phase 1 only —
`com.lms.ledgersettlementmanagement`). Settlement-run endpoints (Phase 2) do not exist
yet and are out of this module's scope; nothing in `ledger_entry`'s schema or this API
scaffolds them. Written retroactively alongside `docs/api/payment-management.md` — see
that file's header note for why.

## Response envelope

`com.lms.common.api.ApiResponse<T>` — see `docs/api/identity-access-service.md`.

## Pagination envelope

`GET /api/v1/ledger/dashboard` wraps its list payload in `com.lms.common.api.PageResponse<T>`
(the same convention `docs/api/course-management.md` documents):

```jsonc
{
  "content": [ /* LedgerHistoryEntryResponse[] */ ],
  "page": 0,
  "size": 20,
  "totalElements": 47,
  "totalPages": 3
}
```

Standard Spring `Pageable` query params: `page` (default `0`), `size` (default `20`),
`sort` (default `createdAt,DESC`).

## Auth requirements

Both endpoints require a valid `Authorization: Bearer <accessToken>` header. Neither
accepts a `tenantId`/`studentId` query or body parameter — scoping is exclusively
server-resolved from the authenticated session.

## Authorization model

- `GET /api/v1/ledger/history` — `hasRole('STUDENT')` only, always the caller's own
  history (no id/param to request another student's). No cross-tenant surface exists on
  this endpoint by construction — a student session is always scoped to exactly one
  tenant.
- `GET /api/v1/ledger/dashboard` — `@PreAuthorize("isAuthenticated()")` is the coarse
  controller-level gate; the real check is `DomainArea.PAYMENTS_SLIPS`/`VIEW` in
  `LedgerQueryService.getDashboard`, held by Tenant Admin, Finance Staff, Student
  Support, and Read-only Auditor. Every other role (including a plain Student) is
  rejected `403`.

## Ledger-derived-only guarantee

Both endpoints read exclusively from `ledger_entry` — never from `payment.status` or
`order` directly, per `.claude/rules/payments.md` §2. A `CONFIRMED` payment with no
corresponding `ledger_entry` row (which should never happen in practice, since payment
confirmation and the ledger write commit in one transaction — see
`docs/api/payment-management.md`'s "Refund model" note and
`PaymentConfirmationRollbackIntegrationTest`) is **not** reported as "paid" by either
surface. This is directly tested in both directions (student history and admin
dashboard) via `PaymentAndLedgerIntegrationTest`/`PaymentCrossTenantIntegrationTest`,
each seeding a `CONFIRMED` payment with no ledger row via raw SQL and asserting it's
absent from the read path.

## Endpoints

### `GET /api/v1/ledger/history`

**Success — `200`** (`ApiResponse<LedgerHistoryEntryResponse[]>`, not paginated — this
is always one student's own, naturally bounded history):

```jsonc
[
  {
    "id": "...",
    "orderId": "...",
    "paymentId": "...",           // nullable — left open for a future non-payment-table source
                                    // (e.g. Module 11's manual-slip path), not used by anything today
    "entryType": "PAYMENT_CONFIRMED",   // PAYMENT_CONFIRMED | REFUND — the only two values;
                                          // adding a third requires an ADR per .claude/rules/payments.md §4
    "amount": 49.99,               // positive for PAYMENT_CONFIRMED, negative for REFUND (sign convention)
    "reversesEntryId": null,       // set only on a REFUND entry, pointing at the original PAYMENT_CONFIRMED entry
    "createdAt": "2026-08-23T10:16:31Z"
  }
]
```

### `GET /api/v1/students/{id}/ledger` (Wave 3)

Staff-facing, studentId-scoped ledger read behind Student Detail's Payments tab — lives
here (the owning domain of `ledger_entry`), not duplicated into `user-management`, per
`wave-03-plan.md` §4. `{id}` is the `StudentProfile`'s own resource id, resolved
internally to the opaque cross-domain `studentId` via `user-management.api
.StudentLookupApi`, never a client-supplied `tenant_user` id. Requires
`PAYMENTS_SLIPS`/`VIEW` (Tenant Admin, Finance Staff, Student Support, Read-only
Auditor — the same grant `GET /api/v1/ledger/dashboard` requires below).

**Success — `200`** (`ApiResponse<LedgerHistoryEntryResponse[]>`, not paginated) — same
`LedgerHistoryEntryResponse` shape documented below, strictly ledger-derived (never
`payment.status`/`order` directly), per `.claude/rules/payments.md` §2.

**`404`** — `{id}` doesn't resolve to a student in the caller's own tenant. **`403`** —
caller lacks `PAYMENTS_SLIPS`/`VIEW`.

### `GET /api/v1/ledger/dashboard`

**Success — `200`** (`ApiResponse<PageResponse<LedgerHistoryEntryResponse>>`), same
`LedgerHistoryEntryResponse` shape as above, tenant-scoped (every tenant's staff sees
only their own tenant's entries — no cross-tenant aggregate view exists here; that is a
separate, later Platform-Admin-authorized surface per the plan's §6 scope note).
**`403`** for a caller without `PAYMENTS_SLIPS`/`VIEW`.

## `entry_type` enum — change-controlled

`ledger_entry.entry_type`'s two-value set (`PAYMENT_CONFIRMED`, `REFUND`) is enforced by
a DB `CHECK` constraint and ratified in
`docs/adr/ADR-010-ledger-entry-type-and-enrollment-slice.md`. Do not add, remove, or
change the meaning of a value without a new ADR, per `.claude/rules/payments.md` §4.

## Platform Admin cross-tenant payment dashboard (MVP-020, PADASH-2)

Structurally distinct from `GET /api/v1/ledger/dashboard` above: a different
controller (`PlatformAdminLedgerController`), a different service
(`PlatformAdminLedgerQueryService`), a different response DTO
(`PlatformLedgerEntryResponse` — never a reuse of `LedgerHistoryEntryResponse`, which
has no tenant field and must not be widened for this use per this file's own
approved-contract discipline), and genuinely cross-tenant — the one place in this
domain where a single read spans every tenant's rows.

**Auth**: `Authorization: Bearer <accessToken>` issued via the Platform Admin login
path. **Authorization**: class-level `@PreAuthorize("hasRole('PLATFORM_ADMIN')")`,
re-confirmed at the service layer. Neither endpoint accepts a `tenantId` query/body
param on the dashboard read — cross-tenant scope is the endpoint's whole purpose, not
something callers can narrow via a client-supplied filter (the drill-down below is a
`tenantId` **path** variable, resolved server-side against `TenantLookupApi`, not a
client-trusted filter value).

**Read-only**: no mutation endpoint exists on this controller. No refund/adjustment
action is reachable from either endpoint below — such actions stay on the existing,
tenant-scoped payment/refund endpoints documented above.

### `GET /api/v1/platform-admin/payments/dashboard`

Platform-wide, paginated, default sort `createdAt,DESC`. No filter query params exist
(no status/date-range/tenant filter) — module plan §6's deliberate scope decision; a
client-side filter over one server-paginated page would silently miss matches on other
pages, so none is implemented.

Response: `ApiResponse<PageResponse<PlatformLedgerEntryResponse>>`.

```jsonc
// PlatformLedgerEntryResponse
{
  "id": "uuid",
  "tenantId": "uuid",
  "tenantName": "string | null",   // null if the tenant id no longer resolves to a real tenant row
  "orderId": "uuid",
  "paymentId": "uuid | null",
  "entryType": "PAYMENT_CONFIRMED", // PAYMENT_CONFIRMED | REFUND — same change-controlled enum as above
  "amount": 49.99,
  "reversesEntryId": "uuid | null",
  "createdAt": "instant"
}
```

`tenantName` is `null` only if `tenantId` no longer resolves to a real `tenant` row.
No code path in this codebase hard-deletes a `tenant` row today, so this is currently
unreachable/forward-looking, not a live data-integrity gap — documented here so a
future reader doesn't mistake the nullable field for masking a real problem.

No `currency` field exists — `ledger_entry` has no `currency` column (unlike
`student_order`/`payment`, which each carry `currency VARCHAR(3) NOT NULL`), so a
platform-wide, cross-tenant amount currently renders as a bare decimal with no unit.
This is a known gap (surfaced during MVP-020's frontend review), not an oversight to
silently work around on the client — resolving it requires either a backend/schema
change or a product decision on a platform-wide implicit currency assumption.

### `GET /api/v1/platform-admin/payments/tenants/{tenantId}`

Single-tenant drill-down — same `PlatformLedgerEntryResponse` shape and pagination
defaults as the dashboard above, filtered to `{tenantId}`.

Error cases: `404 NOT_FOUND` ("Tenant not found") if `{tenantId}` doesn't resolve to a
real `tenant` row (checked via `TenantLookupApi` before the ledger query runs, so an
unknown tenant id never returns an empty-but-200 page as if the tenant existed).

## Database indexing for cross-tenant scans (MVP-020, `V31`)

Every pre-existing index on `payment`/`ledger_entry`/`audit_log` leads with `tenant_id`
(correct for every other module's tenant-scoped query shape). The dashboard endpoint
above introduced this domain's first genuinely cross-tenant, platform-wide `ORDER BY
created_at DESC` scan, which a leading-`tenant_id` index cannot serve efficiently at
platform-wide row counts. `V31__add_platform_admin_cross_tenant_dashboard_indexes.sql`
adds `idx_ledger_entry_created_at_tenant ON ledger_entry (created_at DESC, tenant_id)`
(plus matching indexes on `payment` and `audit_log` for the sibling Platform Admin
screens) — purely additive, no table/column change. `tenant_id` is a trailing column
(not an `INCLUDE` clause) so the per-row tenant attribution these dashboards display can
be read directly from the index, while staying compatible with every currently-supported
PostgreSQL version. See `docs/architecture/database-architecture.md` for the fuller
rationale.
