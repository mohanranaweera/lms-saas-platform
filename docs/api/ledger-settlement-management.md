# ledger-settlement-management — API Contract

Covers the Payment History / Payment Dashboard read endpoints (MVP-010 / Phase 1 —
`com.lms.ledgersettlementmanagement`) and, since Wave 7, the **teacher settlement
foundation** (see "Wave 7: teacher settlement foundation" at the end). Platform → tenant
settlement (commission %, gateway fees) and split payments still do not exist; nothing in
`ledger_entry`'s schema was changed for settlement. Written retroactively alongside `docs/api/payment-management.md` — see
that file's header note for why.

**Wave 6 update** (`docs/parity/waves/wave-06-plan.md`): every ledger-derived read below
is extended with `courseId`/`courseTitle`/`billingPeriodId`/`operationalState`/`method`/
`reference`; `GET /api/v1/ledger/dashboard` and its Platform-Admin equivalent gain
optional `status`/`method` filters; two new endpoints, `GET /api/v1/ledger/outstanding`
and `GET /api/v1/ledger/courses/{courseId}/summary`, were added. See "Wave 6: extended
fields", "Wave 6: `PaymentOperationalState`/`PaymentMethod`", and "Wave 6: new query
endpoints" below.

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
    "createdAt": "2026-08-23T10:16:31Z",
    // --- Wave 6 additions (see "Wave 6: extended fields" below) ---
    "courseId": "...",
    "courseTitle": "Intro to Algebra",
    "billingPeriodId": "...",      // nullable — the CourseBillingPeriod this order's amount was
                                     // snapshotted from at order-creation time (Wave 2, V38-V40);
                                     // simply propagated through, never re-resolved
    "operationalState": "PAID",     // PaymentOperationalState — see below
    "method": "MANUAL_SLIP",        // PaymentMethod — see below
    "reference": "REF-12345"        // gateway reference, or the slip's referenceNumber for MANUAL_SLIP
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

**Wave 6**: two optional query params, `status` (`PaymentOperationalState`) and `method`
(`PaymentMethod`) — both `@RequestParam(required = false)`, mirroring
`SlipReviewController#getReviewQueue`'s existing `status` param pattern. Omitting either
(or both) returns every entry, unfiltered — identical to this endpoint's pre-Wave-6
behavior. Both may be supplied together (AND-combined). To keep filter-then-paginate
correct, the tenant-scoped implementation (`LedgerQueryService#getDashboard`) re-reads
the tenant's full unpaged entry list on the filtered branch — an accepted, documented
cost/correctness tradeoff at current expected tenant data volumes (`wave-06-plan.md` §10
judgment call 3); the unfiltered branch's cost/pagination is unchanged.

### `GET /api/v1/ledger/outstanding` (Wave 6)

Tenant Admin / Finance Staff / Student Support / Read-only Auditor, `PAYMENTS_SLIPS`/
`VIEW`-gated (same grant as `/dashboard` above), tenant-scoped only. Returns orders with
no `PAID`/`REFUNDED` `PaymentOperationalState` — i.e. `UNPAID`, `PENDING`,
`UNDER_REVIEW`, or `REJECTED` with no later successful attempt — paginated (standard
Spring `Pageable`: `page`, `size`, default `size=20`).

**Success — `200`** (`ApiResponse<PageResponse<OutstandingOrderResponse>>`):

```jsonc
{
  "orderId": "...",
  "studentId": "...",
  "courseId": "...",
  "courseTitle": "Intro to Algebra",
  "amount": 49.99,
  "currency": "USD",
  "operationalState": "PENDING"   // never PAID or REFUNDED, by construction of this query
}
```

**`403`** for a caller without `PAYMENTS_SLIPS`/`VIEW` (proven for STUDENT/TEACHER
callers by a Wave 6 security-review test).

### `GET /api/v1/ledger/courses/{courseId}/summary` (Wave 6)

Same authorization as `/outstanding` above. Aggregate counts/amounts by
`PaymentOperationalState` for one course, tenant-scoped: a `courseId` belonging to
another tenant (or a nonexistent one) resolves to an empty/all-zero summary, never
another tenant's real numbers — never `404`, per this module's established
anti-enumeration convention for aggregate reads.

**Success — `200`** (`ApiResponse<CoursePaymentSummaryResponse>`):

```jsonc
{
  "courseId": "...",
  "courseTitle": "Intro to Algebra",
  "totalOrders": 42,
  "byState": [
    { "state": "PAID", "count": 30, "totalAmount": 1499.70 },
    { "state": "PENDING", "count": 5, "totalAmount": 249.95 },
    { "state": "UNDER_REVIEW", "count": 2, "totalAmount": 99.98 },
    { "state": "REJECTED", "count": 1, "totalAmount": 49.99 },
    { "state": "REFUNDED", "count": 4, "totalAmount": 199.96 }
  ]
}
```

**Note**: the `REFUNDED` bucket's `totalAmount` sums each order's original snapshotted
amount, not a net-of-refund figure — a deliberate, documented "order value by current
state" semantic (financial-integrity review finding, `wave-06-plan.md` §15), not a
sign/double-count bug.

**`403`** for a caller without `PAYMENTS_SLIPS`/`VIEW` (proven for STUDENT/TEACHER
callers by a Wave 6 security-review test).

## Wave 6: `PaymentOperationalState`/`PaymentMethod`

`com.lms.ledgersettlementmanagement.api.PaymentOperationalState` — a computed,
**never-persisted** read-model projection (`PaymentOperationalStateResolver`/
`PaymentOperationalStateService`), derived per-order from `Payment`/`PaymentSlip`/
`PaymentRefund`/ledger state via narrow `payment-management` `api` reads
(`PaymentStatusApi#findOrderPaymentDetails`, `SlipStatusApi#findOrderSlipDetails`), never
a cross-domain repository reach-through. Recomputed on every read — not cached or
materialized (an accepted tradeoff at current data volumes; see `wave-06-plan.md` §10
judgment call 3).

Values: `UNPAID` (no payment attempt or slip ever made) · `PENDING` (a gateway payment
attempt is `PENDING`, no open slip) · `UNDER_REVIEW` (a slip is `SUBMITTED` or
`UNDER_REVIEW`) · `PAID` (a `PAYMENT_CONFIRMED` ledger entry exists and any refund is
less than the confirmed amount) · `REJECTED` (the most recent attempt was rejected, no
attempt ever succeeded) · `REFUNDED` (total refunded amount ≥ the confirmed amount).

`com.lms.ledgersettlementmanagement.api.PaymentMethod` — `GATEWAY | MANUAL_SLIP | FREE |
STAFF_GRANTED`, derived from the confirmed payment's own `gateway_reference` string-
prefix convention (`"FREE-"`, `"STAFF_GRANTED-"`, `"SLIP-"` — the last one new this wave,
see `docs/api/payment-management.md`'s slip-approval section — else `GATEWAY`), computed
in one place (`LedgerViewEnrichmentService`), never a new persisted column. Kept in sync
manually if a future wave adds a fifth confirmation path (`wave-06-plan.md` §10 judgment
call 2).

## Wave 6: extended fields

`LedgerHistoryEntryResponse`/`PlatformLedgerEntryResponse` are both extended with
`courseId`, `courseTitle` (resolved via `CourseLookupApi`), `billingPeriodId` (already
captured on `StudentOrder` since Wave 2, simply propagated through — never re-resolved
against today's course price), `operationalState`, `method`, and `reference` (gateway
reference, or the slip's `referenceNumber` for `MANUAL_SLIP`). Resolution is batched per
page (never N+1) by `LedgerViewEnrichmentService`. All six fields are additive/nullable
on the wire — no existing consumer breaks.

## Wave 6: manual-slip-approval ledger-gap fix

Tracing `SlipReviewService#approve` before this wave: it transitioned `PaymentSlip` to
`APPROVED` and activated enrollment atomically, but never created/confirmed a `Payment`
row or called `LedgerEntryApi#recordPaymentConfirmed` — the one confirmation path in this
codebase that didn't follow the pattern the other three (gateway webhook, FREE checkout,
staff-granted enrollment) all establish. Practical effect: a manually-approved slip
payment was correctly enrolled but invisible on every ledger-derived view in this file
(`GET /ledger/history`, `/dashboard`, `/outstanding`, `/courses/{id}/summary`, and the
Platform Admin equivalents), violating `.claude/rules/payments.md` §2's "if a screen
shows 'paid' but no corresponding ledger entry exists, that is a bug."

Fixed: `SlipReviewService#approve` now creates+confirms a `Payment`
(`gatewayReference = "SLIP-" + paymentId`) and writes a `PAYMENT_CONFIRMED` ledger entry
in the same transaction as the slip-status write and enrollment activation — a forced
ledger-write failure rolls back the whole approval transition (tested,
`SlipApprovalLedgerFailureRollbackIntegrationTest`). The idempotent-replay path (`approve()`
on an already-`APPROVED` slip) adds zero rows (tested,
`SlipApprovalPaymentLedgerIntegrationTest`).

**No historical backfill**: slips approved before this wave are not retroactively given
synthetic `Payment`/`ledger_entry` rows — a deliberate, flagged judgment call
(`wave-06-plan.md` §9/§10 item 1): synthesizing a historical confirmation timestamp was
judged worse than a known, documented display gap for pre-Wave-6 manual-slip payments.

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
  "createdAt": "instant",
  // --- Wave 6 additions — same fields/semantics as LedgerHistoryEntryResponse, see
  // "Wave 6: extended fields" above ---
  "courseId": "uuid | null",
  "courseTitle": "string | null",
  "billingPeriodId": "uuid | null",
  "operationalState": "PAID",
  "method": "GATEWAY",
  "reference": "string | null"
}
```

**Wave 6**: both endpoints below accept the same optional `status`
(`PaymentOperationalState`) and `method` (`PaymentMethod`) query params as the
tenant-scoped `/dashboard` above. Unlike the tenant-scoped implementation, the
platform-wide filter is applied **within the existing DB-paginated page only** (never an
unpaged full-table read, given platform-wide row counts) — a filtered platform-admin page
may therefore return fewer than `pageSize` rows even when more matches exist on a later
page. This is a deliberate, documented, narrower/more cost-conscious tradeoff than the
tenant-scoped dashboard's filter-then-paginate-the-full-list approach, flagged in
`wave-06-plan.md` §13 deviation 3 for product-owner awareness. Per-row enrichment
(`courseId`/`courseTitle`/`operationalState`/etc.) is resolved per tenant-group, with
`TenantContextHolder` explicitly set to that group's own trusted, DB-sourced tenant id
around each group's lookup and cleared in a `finally` block — mirroring
`PaymentConfirmationService`'s established webhook set-in-try/clear-in-finally technique
— never a client-supplied tenant id, and never leaking one tenant-group's context into
another's.

A Wave 6 financial-integrity review found and fixed a pagination-count bug on the
filtered branch of both endpoints below: `totalElements`/`totalPages` were computed from
the *unfiltered* DB count rather than the actual filtered result set. Fixed to compute
both from the filtered set, on both the platform-wide dashboard and the tenant
drill-down.

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


## Wave 7: teacher settlement foundation

`docs/parity/waves/wave-07-plan.md` §2/§3/§10 (PAR-23-03, PAR-24-02/03/04). Tables V55:
`teacher_revenue_share_rate`, `teacher_settlement`, `teacher_settlement_item`.

**Scope boundary (change-controlled payment ledger rules):** settlement **reads** ledger entries
and **never writes, updates or deletes** a `ledger_entry` — no new `LedgerEntryType`. Marking a
statement `PAID` is record-keeping only (no money moves). No commission/gateway-fee/split logic.

**Authorization:** `FINANCE_EXPENSES` — `VIEW` for reads, `CREATE_EDIT` for every write (tenant
Finance triggers teacher settlement — judgment call 2). Teachers/Students/other staff: 403
(including their own statement). Cross-tenant ids: 404.

| Method | Path | Notes |
|---|---|---|
| GET | `/api/v1/finance/teachers` | `[{userId, name, email}]` — payee picker; `userId` is the `tenant_user` id `course.teacher_id` stores (Finance roles have no `TEACHERS` grant, so `/api/v1/teachers` is unavailable to them) |
| GET | `/api/v1/finance/teacher-share-rates?teacherId` | rate history, newest first |
| POST | `/api/v1/finance/teacher-share-rates` `{teacherId, sharePercent 0–100 (2 dp), effectiveFrom}` | 201; append-only; duplicate effective date for the teacher → 409; audited `teacher_share_rate.created` |
| POST | `/api/v1/finance/teacher-settlements` `{teacherId, periodStart, periodEnd}` | 201 `SettlementDetail`; see rules below; audited `teacher_settlement.calculated` |
| GET | `/api/v1/finance/teacher-settlements?teacherId&status&kind&page&size` | `PageResponse<SettlementView>` |
| GET | `/api/v1/finance/teacher-settlements/{id}` | `SettlementDetail {settlement, courses[{courseId, courseTitle, gross, refunds, net, entryCount}], adjustments[]}` |
| POST | `/api/v1/finance/teacher-settlements/{id}/mark-paid` `{payoutReference?}` | `CALCULATED → PAID`, one-way, row-locked; second call 409; audited `teacher_settlement.marked_paid` |
| POST | `/api/v1/finance/teacher-settlements/{id}/adjustments` `{amount ≠ 0 (signed, 2 dp), reason}` | 201; new `ADJUSTMENT` row referencing the original REGULAR statement (adjusting an adjustment → 409); original never mutated; audited `teacher_settlement.adjusted` with `totalBefore`/`totalAfter` |

**Calculation rules:**
- Period is inclusive dates in the tenant timezone; `periodStart <= periodEnd`, `periodEnd` must be
  **before today** (closed period), span < 366 days — else 400.
- A revenue-share rate must be effective on `periodStart` (else 400), and no other rate may start
  inside the period (else 400 — settle each rate's dates separately).
- Source entries: every `ledger_entry` recorded in the period for a course whose **current**
  teacher is the payee, excluding zero-amount entries and entries already included in any
  statement. None left → 409.
- `gross` = Σ positive, `refund` = Σ |negative|, `net = gross − refund`,
  `share = net × percent / 100` rounded HALF_UP to cents (may be negative when refunds exceed
  income). `sharePercent` and `rateId` are snapshotted; figures are never recomputed.
- `effectiveShareAmount` = `shareAmount` + Σ adjustments.

**Schema-enforced idempotency (payments.md §5):** partial unique
`(tenant_id, teacher_id, period_start, period_end) WHERE kind='REGULAR'` (re-run → 409, zero new
rows) and unique `(tenant_id, ledger_entry_id)` on items (a ledger entry is settled at most
once, even across overlapping periods or concurrent runs). All three repositories extend
`AppendOnlyTenantAwareRepository` (every delete method throws).

**`LedgerRevenueApi`** (`ledgersettlementmanagement.api`): `findRevenueEntries(from, to)` →
`LedgerRevenueEntry(entryId, orderId, refund, amount, createdAt, courseId)`, tenant-scoped,
batched order→course resolution. The read contract `finance-expense-management` uses for
ledger-derived income — no other module touches `LedgerEntryRepository`.

Tests: `TeacherSettlementIntegrationTest` (exact figures, re-run/overlap idempotency, refund in
a later period, rate snapshotting, open-period/no-rate/non-teacher rejection, adjustments,
one-way mark-paid incl. a concurrent race, permissions, cross-tenant, DB constraints, no ledger
writes), `TeacherSettlementShareCalculationTest`.
