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
