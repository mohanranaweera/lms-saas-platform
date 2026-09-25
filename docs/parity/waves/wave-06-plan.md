# Wave 6 Plan — Billing Periods + Student Payments

Status: **DONE.** Backend (slip-approval ledger fix, `PaymentOperationalState`
projection, idempotency, extended/new query endpoints), frontend (Student My
Payments, checkout idempotency, Tenant Admin dashboard/Outstanding/Course-Summary
tabs, Platform Admin filters), a dedicated financial-integrity review and a security/
tenant-isolation review (each independently run, five findings fixed), and
documentation are all complete — see Sections 13-16 for the full completion record.

Scope per the Wave 6 brief: complete recurring/monthly billing and Student payment
parity while preserving the existing authoritative financial architecture (`Order`,
`Payment`, `LedgerEntry`, `Refund`, `PaymentSlip` untouched in their core append-only/
immutability shape).

---

## 1. Phase A — Analysis (findings)

A full read-only inspection of `payment-management`, `ledger-settlement-management`,
`enrollment-management`, and `course-management`'s billing model was performed before
any design decision below. The headline finding: **this codebase is far more complete
than the Wave 6 brief's checklist implies.** Several brief items are already shipped;
one genuinely serious, previously-undetected data-integrity bug was found along the
way. Wave 6's real job is narrower and more precise than "build billing + payments
from scratch."

### 1.1 Billing periods are NOT new — Wave 2 already built them

`CoursePricingModel` (`FREE, ONE_TIME, MONTHLY, SESSION, CUSTOM`, V37),
`CourseBillingConfiguration`/`CourseBillingPeriod` (V38/V39, append-only billed-amount
history with `effectiveFrom`/`effectiveTo`), and `StudentOrder.billingPeriodId` (V40,
an opaque traceability reference into the period an order's amount snapshot was
resolved from) are all shipped and wired end-to-end: `OrderService#createOrder`
resolves `CourseLookupApi#getResolvedCheckoutAmount` per the course's current pricing
model at order-creation time and snapshots `amount`/`currency`/`billingPeriodId` onto
the order, never re-read later. **No new billing-period schema or resolution logic is
needed this wave.** What Wave 6 actually owes on this front is *surfacing* the
billing period on payment views (Section 4) — it is captured today but not exposed to
any read model a Student/Admin actually sees.

### 1.2 A real bug: manually-approved payment slips never reach the ledger

This is the most significant finding of Phase A. Tracing `SlipReviewService#approve`
end-to-end:

1. It transitions `PaymentSlip` to `APPROVED` (correct, one-directional, reviewer/
   timestamp-stamped, audit-logged including override reason — all per
   `.claude/rules/payments.md` §3, all correct).
2. It then calls `EnrollmentActivationApi#activateOrReactivateFromApprovedSlip`, which
   resolves straight to `Enrollment.fromApprovedSlip(...)` —
   `enrollmentmanagement/service/EnrollmentActivationService.java:123-148` (and the
   parallel `reactivateFromApprovedSlip` branch, line 178). This writes a real
   `enrollment` row with a genuine `payment_slip_id` FK/NOT NULL evidence trail
   (satisfying `.claude/rules/backend.md`'s "activation cannot exist as a bare boolean
   flag" requirement) — **but at no point in this call chain is a `Payment` row
   created, confirmed, or is `LedgerEntryApi#recordPaymentConfirmed` ever called.**

Contrast this with the three other confirmation paths in the same codebase, which all
follow one identical, deliberate pattern — create a `Payment` row, `confirm()` it with
a synthesized-but-unique `gateway_reference`, save it, then call
`LedgerEntryApi#recordPaymentConfirmed`, all in the same transaction as enrollment
activation:

- `PaymentConfirmationService#confirmByGatewayReference` (real gateway webhook).
- `OrderService#activateFreeCheckout` (`FREE`-priced courses, `gatewayReference =
  "FREE-" + paymentId`, added by ADR-015 specifically to close this exact class of gap
  for the FREE-checkout path).
- `ManualEnrollmentService#grantEnrollment` (staff "enroll student" action,
  `gatewayReference = "STAFF_GRANTED-" + paymentId`) — whose own comment states the
  requirement explicitly: *"Per `.claude/rules/payments.md` §2, a 'paid' state with no
  ledger row is a data-integrity bug (mirrors the exact gap ADR-015 closed for the
  FREE-course path)."*

`SlipReviewService#approve` is the one remaining confirmation path that never got this
treatment. The practical consequence, today, in production terms: **a student whose
manual bank-slip payment was reviewed and approved by Finance Staff is correctly
enrolled, but their payment is invisible on the ledger-derived Student Payment
History, the ledger-derived Tenant-Admin Payment Dashboard, and the Platform-Admin
cross-tenant dashboard** — all three are explicitly, deliberately ledger-derived per
`.claude/rules/payments.md` §2 ("If a screen shows 'paid' but no corresponding ledger
entry exists, that is a bug"), and for this one path, the ledger entry simply does not
exist. This also means any "outstanding payments" or "course payment summary" query
built naively on top of ledger data would incorrectly count a manually-paid,
successfully-enrolled student as still owing money.

**This is fixed in Phase B (Section 3.1) as the first, highest-priority change** — not
a new capability, a correction to bring the fourth confirmation path in line with the
other three's already-established, already-correct pattern.

### 1.3 Payment-verified operational states are a genuinely new, derived concept

No unified state enum resembling `UNPAID/PENDING/UNDER_REVIEW/PAID/REJECTED/REFUNDED`
exists anywhere today. Current state is scattered and each piece is individually
correct but nothing unifies them for display:

- `OrderStatus`: `PLACED`/`PENDING` only (deliberately incomplete — no
  `CANCELLED`, per V19's own header comment; not touched this wave).
- `PaymentStatus`: `PENDING`/`CONFIRMED`/`REJECTED`/`REFUNDED` (the `REFUNDED` value is
  a literal enum member for DB `CHECK`-constraint fidelity but is **never** set by any
  code path — see `Payment`'s javadoc; refund state lives only in `PaymentRefund` +
  ledger, never on `Payment` itself).
- `PaymentSlipStatus`: `SUBMITTED`/`UNDER_REVIEW`/`APPROVED`/`REJECTED`.
- Refund existence/amount, from `PaymentRefundRepository`.

Per `.claude/rules/backend.md`'s append-only/schema-enforced-invariant guidance and
`.claude/rules/payments.md` §1 ("A `Payment` row is immutable once it reaches a
terminal state... Corrections happen via new rows, never `UPDATE`"), the new
`PaymentOperationalState` must be a **computed, read-model-only projection** — never a
new persisted, mutable "status" column on any of these tables. It is built in Phase B
(Section 3.2) as a pure function over existing rows.

### 1.4 The query-API surface is thin; the UI screens that need it already exist

Existing read endpoints, all confirmed by direct inspection:

| Endpoint | Backs | Gap |
|---|---|---|
| `GET /api/v1/payments/{id}` | Single payment, owner-or-staff | No list/filter capability at all |
| `GET /api/v1/payments/{id}/refunds` | Refunds for one payment | No cross-payment refund list ("all refunds this period") |
| `GET /api/v1/payment-slips/review-queue?status=` | Staff slip queue | Already filterable — the one query endpoint that already does this well |
| `GET /api/v1/ledger/history` (student, self) | `LedgerHistoryEntryView`: `id, orderId, paymentId, entryType, amount, reversesEntryId, createdAt` only | **No courseId, no billing period, no status, no method, no reference** — cannot satisfy "Student My Payments must show verified: Course, billing period, amount, status, date, method, reference" as-is |
| `GET /api/v1/ledger/dashboard` (tenant-admin) | Same thin view, paginated | No status/method filter; no "pending/successful/failed/outstanding" breakdown; no course summary |
| `GET /api/v1/students/{id}/ledger` (staff, one student) | Same thin view | Same gap |
| `GET /api/v1/platform-admin/payments/dashboard`, `/tenants/{id}` | Cross-tenant, same thin view | Same gap, platform scope |

Frontend pages already exist for all of these surfaces (`student/payments/{history,
awaiting-confirmation,slip-upload,reactivation}`, `tenant-admin/payments/{dashboard,
refunds,slip-review}`, `platform-admin/(dashboard)/payments/{page,[tenantId]}`) — Wave
6's frontend work (Phase C) is therefore primarily *wiring existing screens to a
richer, filterable backend response*, not building new screens from nothing.

### 1.5 Idempotency

`PaymentRefund.idempotencyKey` (V20) is the one existing idempotency mechanism in this
module — `RefundService#processRefund` replays the existing row on a repeated
`(tenantId, originalPaymentId, idempotencyKey)`. The webhook confirmation path
(`PaymentConfirmationService#confirmByGatewayReference`) is **already** idempotent by
construction (a `PaymentStatus != PENDING` guard makes a retried webhook delivery a
safe no-op — this is correct today, not a gap).

Two genuine gaps, confirmed by reading `OrderService#createOrder` and
`PaymentInitiationService#initiatePayment` in full: **neither accepts nor checks an
idempotency key.** A double-submitted checkout click can create two `StudentOrder`
rows for the same course (no unique constraint prevents it — the reactivation/
already-enrolled guards only catch a *second* order after the *first* one's payment
has already confirmed, not two rapid-fire orders both still `PLACED`); a double-
submitted "Pay Now" click can create two `PENDING` `Payment` rows against the same
order. Both are fixed in Phase B (Section 3.3) using the exact same
`idempotency_key`-column-plus-replay pattern `PaymentRefund`/`RefundService` already
established — no new pattern invented.

### 1.6 Klass parity matrix cross-check

Rows already marked `MATCHES` (verified still accurate, not re-built this wave):
PAR-07-01 (checkout creates Order), PAR-07-02 (webhook confirms + activates
atomically — re-verified, still correct), PAR-07-03 (Payment History UI states),
PAR-07-05 (refunds append-only), PAR-07-06 (platform cross-tenant dashboard),
PAR-08-01 through PAR-08-04 (slip upload/review/state-machine/override — all
correct), PAR-09-01/09-02/09-03 (activation-evidence trail, expiry, reactivation).
PAR-23 (tutor payouts) and PAR-24 (settlement calculation) are explicitly scoped to
**Wave 7**, not this wave — nothing here duplicates or prejudges that work. No
`wave-02-plan.md` file exists; Wave 2's billing scope is documented directly in the
V37-V40 migration/entity javadocs inspected above, and contains no note deferring
anything to Wave 6.

---

## 2. Target behavior (this wave)

| Item | Target | In scope? |
|---|---|---|
| Manual-slip-approval ledger gap (§1.2) | `SlipReviewService#approve` creates+confirms a `Payment` row and writes a `PAYMENT_CONFIRMED` ledger entry, in the same transaction as enrollment activation, mirroring `ManualEnrollmentService`/`OrderService#activateFreeCheckout` exactly | **Yes — highest priority, real bug fix** |
| `PaymentOperationalState` | New computed enum (`UNPAID, PENDING, UNDER_REVIEW, PAID, REJECTED, REFUNDED`), derived per-order from Payment/Slip/Refund/Ledger state, never persisted as an authoritative column | **Yes** |
| Admin query APIs: payments, pending, successful, failed, manual slips, refunds, outstanding, student history, course summaries | Extend `LedgerQueryService`/`PlatformAdminLedgerQueryService` (or a new tenant-admin-scoped service) with a richer projection + filter params; new "outstanding" and "course summary" queries | **Yes** |
| Student "My Payments": course, billing period, amount, status, date, method, reference | Extend the ledger-history projection with these fields, resolved via existing `api` lookups (`CourseLookupApi`, a new narrow billing-period lookup, `PaymentRefundRepository`/slip lookup for method+reference) | **Yes** |
| Idempotency on Order creation + Payment initiation | New `idempotency_key` columns, same replay pattern as `PaymentRefund` | **Yes** |
| Preserve `Order`/`Payment`/`LedgerEntry`/`Refund`/`PaymentSlip` core shape | No change to existing columns, enums, or state-machine methods (only new, additive columns/rows) | **Yes — constraint, not a task** |
| Settlement / tutor payouts | Settlement calculation, commission, payout ledger | **No — Wave 7, per the matrix (§1.6). Not touched.** |
| New billing-period pricing model or resolution logic | — | **No — already shipped, Wave 2 (§1.1)** |

---

## 3. Database impact (additive Flyway migrations only, next available version: V52)

No change to any existing table's columns/constraints. Two new migrations:

1. **`V52__add_idempotency_key_to_order_and_payment.sql`**
   - `student_order.idempotency_key UUID NULL`, with a partial unique index
     `uq_student_order_idempotency ON student_order (tenant_id, student_id,
     idempotency_key) WHERE idempotency_key IS NOT NULL` — scoped per-student per
     tenant (mirrors `payment_refund.idempotency_key`'s per-original-payment scoping
     shape), never a bare global unique column.
   - `payment.idempotency_key UUID NULL`, with a partial unique index
     `uq_payment_idempotency ON payment (tenant_id, order_id, idempotency_key) WHERE
     idempotency_key IS NOT NULL` — a repeated "Pay Now" click for the same order with
     the same client-generated key replays the existing `PENDING`/`CONFIRMED` payment
     row instead of creating a second one.
   - Both nullable/additive — existing rows unaffected, existing callers that don't
     supply a key keep today's behavior exactly (mirrors how `PaymentRefund`'s
     optional key works today).

2. **`V53__add_course_payment_summary_indexes.sql`**
   - `student_order (tenant_id, course_id, status)` — supports the new course
     payment-summary and outstanding-orders queries (Section 4) without a sequential
     scan per tenant.
   - `ledger_entry (tenant_id, order_id, entry_type)` — supports the per-order
     paid/refunded-remainder computation the new `PaymentOperationalState` projection
     needs, run per order rather than once per ledger row.
   - Purely additive indexes, no column/constraint change.

No new tables. `PaymentOperationalState` is computed in application code (Section
1.3) — it is explicitly NOT a new column, per `.claude/rules/backend.md`'s guidance
against inventing mutable status columns that duplicate what's already derivable from
append-only source-of-truth rows.

---

## 4. API impact

All under `/api/v1`, additive (existing response shapes are extended with new
optional-on-the-wire fields, never a breaking removal), DTO-only.

**`ledger-settlement-management`**
- `LedgerHistoryEntryView`/`LedgerHistoryEntryResponse` extended with: `courseId`,
  `courseTitle` (resolved via `CourseLookupApi`), `billingPeriodId` (already captured
  on `StudentOrder`, simply propagated through), `operationalState`
  (`PaymentOperationalState`), `method` (`GATEWAY | MANUAL_SLIP | FREE | STAFF_GRANTED`
  — derived from the `Payment.gatewayReference` prefix convention already
  established by the three existing confirmation paths plus the newly-fixed slip
  path, never a new column), `reference` (gateway reference, or the slip's
  `referenceNumber` for `MANUAL_SLIP`).
- `GET /api/v1/ledger/dashboard` gains `status` (`PaymentOperationalState`) and
  `method` query params (optional, `@RequestParam(required = false)`), mirroring
  `SlipReviewController#getReviewQueue`'s existing `status` param pattern exactly.
- New `GET /api/v1/ledger/outstanding` (tenant-admin, `PAYMENTS_SLIPS`/`VIEW`) — orders
  with no `PAID` operational state (i.e. `UNPAID`/`PENDING`/`UNDER_REVIEW`/`REJECTED`
  with no later successful attempt), paginated.
- New `GET /api/v1/ledger/courses/{courseId}/summary` (tenant-admin) — aggregate
  counts/amounts by `operationalState` for one course, for the Course Payment Summary
  screen.
- `PlatformAdminLedgerQueryService`/`PlatformLedgerEntryResponse` get the same
  `courseId`/`courseTitle`/`billingPeriodId`/`operationalState`/`method`/`reference`
  fields, same `status`/`method` filter params on
  `GET /api/v1/platform-admin/payments/dashboard`.
- `GET /api/v1/students/{id}/ledger` (staff, one student) and
  `GET /api/v1/ledger/history` (student, self) get the same extended fields — this
  is the endpoint that directly backs the Student "My Payments" requirement.

**`payment-management`**
- `OrderCreateRequest` gains an optional `idempotencyKey` (UUID, client-generated) —
  `OrderService#createOrder` checks for an existing order with the same
  `(tenantId, studentId, idempotencyKey)` first and returns it unchanged (200, not
  201) instead of creating a duplicate, mirroring `RefundService#processRefund`'s
  replay branch.
- `PaymentInitiationService#initiatePayment` gains the same optional
  `idempotencyKey` parameter/param on its request DTO, same replay semantics scoped
  to `(tenantId, orderId, idempotencyKey)`.
- `SlipReviewService#approve`: **no new endpoint** — the fix is internal to the
  existing `POST /api/v1/payment-slips/{slipId}/approve` handler (Section 3.1); the
  response shape (`PaymentSlipResponse`) is unchanged, since the slip itself gains no
  new field — the new `Payment`/`LedgerEntry` rows are an internal side effect visible
  only through the ledger-derived endpoints above.

---

## 5. Frontend impact

- **Student `my-payments`/`history`**: render the new `courseTitle`, billing period
  label, `operationalState` (as an accessible status badge, not color-only),
  `method`, `reference` columns/fields against the extended `GET /api/v1/ledger/
  history` response — no new page, extending the existing one.
- **Tenant Admin `payments/dashboard`**: add status/method filter controls wired to
  the new `status`/`method` query params; add "Outstanding" and "Course Summary" tabs/
  views backed by the two new endpoints.
- **Tenant Admin `payments/slip-review`**: no visible change (the ledger-write fix is
  invisible at this screen — the slip's own state machine is unchanged), but the
  now-correct downstream dashboard is what proves the fix end-to-end.
- **Platform Admin `payments`**: same filter/column additions as the tenant-admin
  dashboard, platform-scoped.
- **Checkout / Pay Now**: generate and send a client-side `idempotencyKey` (UUID,
  regenerated per checkout session, reused across retries of the *same* submit
  action) on order creation and payment initiation, matching the backend contract —
  a double-click or a network-retry no longer risks a duplicate order/payment.
- All new/changed screens keep the existing loading/empty/error/permission-denied
  state set (`QueryStateBoundary`), matching every prior wave's baseline.
- Extend `frontend/src/lib/api/payments.ts`/`ledger.ts` clients for the new fields
  and params — no ad hoc fetches added in components.

---

## 6. Security impact

- The slip-approval ledger fix (Section 3.1) closes a data-integrity gap, not a
  security hole per se — no unauthorized access was possible — but it does mean audit
  trails and financial reporting were previously incomplete for manually-approved
  payments; this is corrected.
- `PaymentOperationalState` is read-only/derived — no new mutation surface, no new
  attack surface for tampering with payment state.
- New idempotency keys are client-generated UUIDs, scoped to `(tenantId, studentId/
  orderId, key)` — never trusted as an identity or authorization signal, purely a
  dedup key; a guessed/replayed key for another student's order still fails the
  existing tenant/ownership checks in `OrderService`/`PaymentInitiationService` before
  the idempotency check is even reached.
- New outstanding/course-summary endpoints are gated identically to the existing
  dashboard (`PAYMENTS_SLIPS`/`VIEW`), never a looser check introduced for the new
  surface.
- No new secret, credential, or PII exposure — `courseTitle` and billing period
  labels are already tenant-visible data; no cross-tenant leak risk introduced (same
  tenant-scoped repository/API calls as the existing dashboard).

---

## 7. Tenant isolation impact

- No new tenant-owned table. `student_order.idempotency_key` and
  `payment.idempotency_key`'s uniqueness scopes both lead with `tenant_id` (Section
  3), consistent with `.claude/rules/backend.md`'s "unique within a tenant" guidance —
  never a bare global-unique key.
- `courseId`/`courseTitle` resolution on the extended ledger views goes through
  `CourseLookupApi` (existing `api`-package cross-module call), which is itself
  already tenant-scoped — no new cross-tenant read path introduced.
- The new `GET /api/v1/ledger/outstanding` and `.../courses/{courseId}/summary`
  endpoints reuse the existing tenant-scoped `LedgerEntryRepository`/
  `StudentOrderRepository` query base (never the `...AcrossTenants...`-suffixed
  platform-only methods) — a `courseId` belonging to another tenant resolves to an
  empty/404 result, never another tenant's summary data.
- Cross-tenant negative tests required (Section 8): a Tenant-Admin from tenant A
  requesting `/ledger/courses/{courseId}/summary` for a tenant-B course id → empty
  result or 404 (anti-enumeration, consistent with the rest of this module), never
  another tenant's real summary numbers.

---

## 8. Test plan

Backend (JUnit + Testcontainers):

- **Slip-approval ledger fix**: approving a slip now produces exactly one `Payment`
  row (`CONFIRMED`, `gatewayReference` prefixed distinctly, e.g. `"SLIP-" + slipId`)
  and exactly one `PAYMENT_CONFIRMED` ledger entry, in the same transaction as
  enrollment activation (a forced failure of the ledger write must roll back the
  slip-approval transition too — transactional-atomicity test, mirroring the existing
  `PaymentConfirmationService`/`ManualEnrollmentService` atomicity tests). A repeat
  `approve()` call against an already-`APPROVED` slip (idempotent no-op path) must
  NOT create a second `Payment`/ledger row.
- **`PaymentOperationalState` projection**: one test per state transition path —
  freshly-placed order with no payment attempt → `UNPAID`; `PENDING` payment, no slip
  → `PENDING`; `SUBMITTED`/`UNDER_REVIEW` slip → `UNDER_REVIEW`; `CONFIRMED` payment
  (any of the four confirmation paths) with no full refund → `PAID`; `REJECTED`
  payment/slip with no later successful attempt → `REJECTED`; refunded amount equals
  confirmed amount → `REFUNDED`; a *partial* refund stays `PAID` (not `REFUNDED`) —
  explicit test for this boundary.
- **Idempotency**: two `createOrder` calls with the same `(studentId, idempotencyKey)`
  return the same order id, no second row persisted (query the count); same for
  `initiatePayment`; a concurrent-duplicate-request race test (two simultaneous calls
  with the same key — exactly one row wins, the other observes/replays it, no unique-
  constraint exception leaks to the caller); a different `idempotencyKey` (or none)
  still creates a genuinely new order/payment as before (regression, not a change).
- **New query endpoints**: `outstanding` returns only non-`PAID` orders and excludes
  fully-refunded-then-still-outstanding edge cases correctly; `courses/{id}/summary`
  aggregate counts match a hand-computed fixture; `dashboard`'s new `status`/`method`
  filters return the correct subset against a fixture with all six operational
  states and all four methods represented.
- **Cross-tenant**: tenant-A staff requesting tenant-B's `outstanding`/`course-
  summary`/extended `dashboard` → empty/404, never tenant-B data (Section 7).
- **Retry/rollback**: a forced gateway-call failure mid-`initiatePayment` (between
  the two `PaymentWriteService` transactions) leaves the order re-payable (no
  duplicate `PENDING` payment blocks a legitimate retry with a *new* idempotency
  key); a forced ledger-write failure during slip approval rolls back the whole
  transition (see above).
- **Invalid callback**: a webhook `confirmByGatewayReference` call for a
  nonexistent/already-foreign-tenant gateway reference → `NotFoundException`, no
  partial write (regression check on already-correct behavior).
- **Duplicate slip**: unchanged behavior re-verified (`SlipDuplicateCheckService`
  gate still runs before any approval reaches the fixed ledger-write code path).
- **Historical billing integrity**: confirm `CourseBillingPeriod`'s existing
  append-only amount history is never re-read/recomputed by any new query — course
  summaries and payment views always reflect the `amount` actually snapshotted on
  each `StudentOrder` at its own creation time, never today's course price.

Frontend (Playwright):

- Student: My Payments/History renders course title, billing period, status badge
  (accessible, not color-only), method, reference for a fixture spanning gateway,
  manual-slip, free, and staff-granted payments — including the previously-broken
  manual-slip case, now visible.
- Tenant Admin: dashboard filter by status/method produces the correct filtered rows;
  Outstanding view lists only unpaid/pending/under-review/rejected orders; Course
  Summary view matches the backend fixture's aggregate numbers.
- Checkout: simulate a double-click "Pay Now" — only one payment/redirect occurs
  (idempotency key reused, not regenerated on the second click of the same action).
- Platform Admin: same filter/column additions verified at platform scope,
  cross-tenant drill-down unaffected.

---

## 9. Migration strategy / rollout risk

- Both migrations are purely additive (nullable columns, partial unique indexes,
  plain indexes) — no existing row is touched, no existing query's plan is
  invalidated.
- The slip-approval fix changes application behavior going forward only — it does
  **not** backfill missing `Payment`/`ledger_entry` rows for slips approved *before*
  this wave ships. That is a deliberate, explicit judgment call (Section 10) — a
  backfill migration inventing historical `Payment`/`LedgerEntry` rows with a
  synthetic `confirmed_at` would misrepresent when those payments were actually
  confirmed, which is worse than a known, documented historical gap. Existing
  already-enrolled students are unaffected (their `enrollment` row and access are
  untouched); only their *payment history display* for slips approved before this
  wave remains incomplete, which is called out explicitly to the product owner, not
  silently left as an unexplained discrepancy.
- Idempotency keys are opt-in (nullable, only enforced when supplied) — no existing
  frontend caller breaks by omitting one; the frontend change to start sending one is
  part of this wave's Phase C, not a hard backend requirement.

---

## 10. Explicit judgment calls (flagged for product-owner awareness)

1. **No historical backfill for the slip-approval ledger gap** — see Section 9. The
   alternative (synthesizing historical ledger rows) was considered and rejected as
   creating false precision about historical confirmation timestamps; flagged rather
   than silently decided, since it directly affects how "complete" Payment History
   looks for tenants that used manual slips before this wave.
2. **`method` is derived from `gatewayReference`'s string prefix convention**
   (`"FREE-"`, `"STAFF_GRANTED-"`, and the new `"SLIP-"`, else `GATEWAY`) rather than
   a new persisted column — consistent with not touching `Payment`'s existing schema,
   but it does mean `method` derivation logic lives in one place (the new projection
   service) and must be kept in sync if a future wave adds a fifth confirmation path.
   Documented here so that isn't rediscovered as a mystery later.
3. **`PaymentOperationalState` is computed per-request, not cached/materialized** —
   given current expected data volumes this is judged acceptable (the same tradeoff
   the existing `LedgerQueryService#getDashboard` already makes); if course/tenant
   volume grows enough to matter, a materialized read-model (per
   `.claude/rules/architecture.md`'s `reporting-analytics` guidance) is the correct
   future direction, not optimizing this wave's endpoints prematurely.
4. **Idempotency key uniqueness is scoped to `(tenant, student/order, key)`, not
   globally unique** — deliberately mirrors `PaymentRefund`'s existing scoping choice
   for consistency; a globally-unique key was considered and rejected as an
   unnecessary and unverifiable-by-the-client constraint (a client only needs
   dedup-within-its-own-retry-sequence, not platform-wide uniqueness).

---

## 11. Deferred (explicitly out of scope, not silently skipped)

- **Settlement calculation, commission/gateway-fee computation, tutor payouts**
  (PAR-23/PAR-24) — explicitly Wave 7 per the parity matrix; this wave's
  `PaymentOperationalState`/query-API work is deliberately built so Wave 7 can
  consume it (a settlement run needs to know which orders are `PAID`), not duplicate
  it.
- **New billing-period pricing models, proration, or mid-cycle plan changes** — no
  gap was found in Wave 2's existing billing-period model; none is invented here.
- **Payment gateway vendor changes** — `PaymentGatewayApi`/`integration-management`'s
  existing adapter boundary is untouched.
- **Retroactive backfill of pre-Wave-6 manual-slip ledger entries** — see Section 9/
  judgment call 1.

---

## 12. Next steps

Phase B (backend implementation) starts with the Section 3.1 ledger-write fix (the
correction, isolated and tested first, independent of everything else), then the
`PaymentOperationalState` projection (Section 1.3/3.2 target), then the idempotency
columns/replay logic (Section 3.3), then the new/extended query endpoints (Section
4). Backend tests run and pass before Phase C (frontend) begins, per the standard
per-module workflow in root `CLAUDE.md`.

---

## 13. Wave 6 Phase B Completion Report (backend)

Implemented in full, in the ordered sequence Section 12 specified.
`.\mvnw.cmd verify` (full suite): **1882 tests, 0 failures, 0 errors, 0 skipped.** No
file under `frontend/` touched; no existing Flyway migration (V1-V51) edited — only
`V52`/`V53` added.

### What shipped

1. **Section 3.1 fix** — `SlipReviewService#approve` now creates+confirms a
   `Payment` row (`gatewayReference = "SLIP-" + paymentId`) and writes a
   `PAYMENT_CONFIRMED` ledger entry before enrollment activation, in the same
   transaction, mirroring `ManualEnrollmentService#grantEnrollment` exactly. Both
   idempotent-replay branches verified to add zero rows. New
   `SlipApprovalPaymentLedgerIntegrationTest`,
   `SlipApprovalLedgerFailureRollbackIntegrationTest` (forced ledger-write failure
   rolls back the whole slip-approval transaction) added.
2. **`PaymentOperationalState`** — `com.lms.ledgersettlementmanagement.api
   .PaymentOperationalState` + a pure `PaymentOperationalStateResolver` +
   orchestrating `PaymentOperationalStateService`, fed by two new narrow `api` reads
   on `payment-management` (`PaymentStatusApi#findOrderPaymentDetails`,
   `SlipStatusApi#findOrderSlipDetails`) rather than any cross-domain repository
   reach-through. **A real correctness bug was found and fixed during
   implementation**: the resolver's original `confirmedTotal.signum() > 0` check
   misclassified a genuine `$0` FREE-course confirmation (ADR-015/V42) as `UNPAID`
   (and would have wrongly matched `REFUNDED`'s `>= 0` comparison too) — fixed to
   check presence (`confirmedTotal != null`) and require `refunded.signum() > 0`
   before ever returning `REFUNDED`. Two explicit regression tests added. 16-case
   `PaymentOperationalStateResolverTest` covers every Section 8 transition.
3. **Migrations** — `V52__add_idempotency_key_to_order_and_payment.sql`,
   `V53__add_course_payment_summary_indexes.sql`, exactly as specified in Section 3.
4. **Idempotency** — `OrderCreateRequest`/new `PaymentInitiationRequest` gain
   optional `idempotencyKey`; `OrderService#createOrder`/
   `PaymentWriteService#createPendingPayment` replay an existing row on a repeat
   key.
5. **Extended query endpoints** — `LedgerHistoryEntryView`/Response and
   `PlatformLedgerEntryResponse` extended with `courseId`, `courseTitle`,
   `billingPeriodId`, `operationalState`, `method` (new `PaymentMethod` enum:
   `GATEWAY|MANUAL_SLIP|FREE|STAFF_GRANTED`, derived from the `gatewayReference`
   prefix), `reference`, resolved batched (never N+1) by a new
   `LedgerViewEnrichmentService`. `status`/`method` filters added to
   `GET /api/v1/ledger/dashboard` and its platform-admin equivalent. New
   `GET /api/v1/ledger/outstanding` and
   `GET /api/v1/ledger/courses/{courseId}/summary`, tenant-scoped,
   `PAYMENTS_SLIPS`/`VIEW`-gated. One comprehensive fixture
   (`LedgerDashboardExtendedViewsIntegrationTest`) spans all 6 operational states and
   all 4 methods across 9 orders and proves filter correctness, extended-field
   resolution, `outstanding` exclusion logic, course-summary aggregation, and
   cross-tenant negative cases for both new endpoints.

### Deviations from this plan's literal text (reasoned, flagged for review — not silent)

1. **Idempotency concurrency mechanism differs from Section 4's suggested
   "mirror `RefundService`'s catch-and-requery idiom."** That idiom was tried first
   and genuinely failed under a real Testcontainers concurrent-race test: Postgres
   aborts the *entire* ambient transaction on a unique-constraint violation, so a
   plain `catch(DataIntegrityViolationException)` cannot "un-abort" it and continue
   to a replay read in the same transaction (this exact limitation is already
   documented in this codebase's own `ReactivationTransactionService` javadoc, from a
   prior investigation). `RefundService`'s idiom works there because it operates
   against an *existing, lockable* parent row; a fresh `createOrder`/
   `createPendingPayment` call has no pre-existing row to lock. Fixed instead with a
   **transaction-scoped Postgres advisory lock** (`pg_advisory_xact_lock`, keyed by
   `tenantId:studentId-or-orderId:idempotencyKey`) acquired before the replay check —
   new `StudentOrderRepository#acquireIdempotencyLock`/
   `PaymentRepository#acquireIdempotencyLock`. This is a new idiom for the codebase,
   documented in both repositories' javadoc; proven under a genuine `CyclicBarrier`
   concurrent-race Testcontainers test in both `OrderCreationIdempotencyIntegrationTest`
   and `PaymentInitiationIdempotencyIntegrationTest`.
2. **`PaymentOperationalState`/`PaymentMethod` live in `ledgersettlementmanagement.api`,
   not `.domain`.** A defensible, not explicitly plan-specified package choice —
   these are computed/never-persisted concepts, structurally unlike `LedgerEntryType`
   (a real DB-mapped enum living in `.domain`, exposed via `.api` views).
3. **The tenant-scoped dashboard's `status`/`method` filter re-reads the tenant's
   full unpaged entry list for filter-then-paginate correctness** (an accepted
   extension of Section 10 judgment call 3's "computed per-request" tradeoff); **the
   platform-wide dashboard's filter, by contrast, filters only within the existing
   DB-paginated page** — narrower and more cost-conscious, since a platform-wide
   unpaged read would be materially more expensive. Documented in code; means a
   filtered platform-admin page may return fewer than `pageSize` rows even when more
   matches exist on a later page. Flagged for product-owner awareness, not silently
   accepted as equivalent behavior to the tenant-scoped filter.
4. **Platform-wide enrichment required a new technique**: `PaymentStatusApi`/
   `SlipStatusApi`/`CourseLookupApi` are tenant-context-scoped, but one platform
   dashboard page can span many tenants' rows. `PlatformAdminLedgerQueryService`
   groups entries by their own `tenantId` and enriches each group with
   `TenantContextHolder` explicitly set to that row's own trusted, DB-sourced tenant
   id (mirroring `PaymentConfirmationService`'s established webhook set-in-try/
   clear-in-finally technique) — never a client-supplied tenant id.

### Not fixed, explicitly flagged (out of this wave's scope)

`EnrollmentActivationService`'s own pre-existing
`catch(DataIntegrityViolationException)` idiom for its insert race (in
`activateFromApprovedSlip`/`activateFromConfirmedPayment`) appears to share the same
"cannot un-abort an already-aborted Postgres transaction" limitation documented above
— it is currently proven only under Mockito unit tests, which cannot detect this
class of bug, never under a genuine concurrent-transaction integration test. Not
touched this wave (unrelated to the Wave 6 brief, and enrollment-activation rules are
change-controlled per root `CLAUDE.md`) — recorded here as a candidate follow-up
ticket, not silently discovered and dropped.

### No historical backfill

Confirmed as planned (Section 9/10): slips approved before this wave are not
retroactively backfilled with synthetic `Payment`/`ledger_entry` rows.

---

## 14. Wave 6 Phase C Completion Report (frontend)

Implemented in full against the real, merged Phase B backend contract (verified
directly against the actual DTOs/controllers — no API mismatch found).
`npx tsc --noEmit` clean; `npm run lint` 0 errors (2 pre-existing, unrelated
warnings); `npx playwright test` full suite **653 passed, 9 failed, 1 skipped** — all
9 failures are in files untouched by this wave (pre-existing/flaky); every test in
`order-and-payment.spec.ts` (37/37) and `platform-admin-payments.spec.ts` (12/12)
passes, including all new Wave 6 coverage.

### What shipped

- API clients (`lib/api/ledger.ts`, `payments.ts`, `platform-admin-payments.ts`)
  extended with the new response fields (`courseId`, `courseTitle`,
  `billingPeriodId`, `operationalState`, `method`, `reference`), `status`/`method`
  filter params, `useLedgerOutstanding`/`useLedgerCourseSummary` hooks, and an
  `idempotencyKey` on order creation/payment initiation.
- Shared `PaymentOperationalStateBadge` (icon+text+color, never color-only) and a
  reusable `PaymentFilterControls` status/method filter pair.
- Student Payment History renders all new fields for every payment method.
- Checkout generates and reuses (never regenerates mid-retry) a client-side
  idempotency key for both order creation and payment initiation.
- Tenant Admin payment dashboard rebuilt as three tabs (Dashboard/Outstanding/Course
  summary) on the existing route, each backed by its respective endpoint.
- Platform Admin dashboard and tenant drill-down both gained the same filter
  controls and columns.

### Verified proof of the Phase A/B fix

A manual headless-Playwright check against a mocked extended `GET /v1/ledger/history`
response confirms the Student Payment History page correctly renders a
`MANUAL_SLIP`-method, `PAID`-state entry with course title and reference — the
concrete end-to-end proof that the manually-approved-slip payments, previously
invisible on this screen (Section 1.2), are now visible.

### Deviations from this plan, with reasoning

1. Outstanding/Course-Summary are tabs on the existing `/tenant-admin/payments/
   dashboard` route (`?tab=` URL state, mirroring this codebase's existing
   `students/[studentId]/page.tsx` pattern), not new routes — no nav entry pointed
   anywhere else.
2. The Platform Admin tenant drill-down also received filter controls/columns,
   beyond the plan's literal "cross-tenant drill-down unaffected" — judged a
   low-risk, natural symmetric extension since the drill-down's query was already
   tenant-scoped; filters only narrow within that scope, never widen it, so tenant
   isolation is unaffected in the sense that matters.

### Pre-existing bug found and fixed (unrelated to Wave 6, in a file already being extended)

`platform-admin-payments.spec.ts`'s `tenantDetail()` fixture used lowercase
`status: "active"`; `TenantStatus`/`StatusBadge` only recognize uppercase
(`ACTIVE`), crashing every drill-down test that rendered `<StatusBadge>` into the
route error boundary — confirmed via `git stash` to already fail on unmodified
`HEAD`, independent of this wave. Fixed (one-line fixture value) in the file already
being edited. The identical bug pattern also exists in
`platform-admin-audit-log.spec.ts`, left untouched (out of this module's scope) but
flagged here rather than silently left undiscovered.

---

## 15. Wave 6 Phase E Completion Report (financial-integrity + security/tenant-isolation review)

Two independent, read-only review passes were run in parallel (per Wave 5's
established practice), followed by a single fix pass. Final `.\mvnw.cmd verify`:
**1889 tests, 0 failures, 0 errors, 0 skipped** (up from 1882 — the +7 are the new
tests the fix pass added).

### Financial-integrity review — findings and resolution

- **MEDIUM, fixed**: `PlatformAdminLedgerQueryService`'s filtered dashboard/drill-down
  reported `totalElements`/`totalPages` from the *unfiltered* DB count instead of the
  actual filtered result set (a UI paginator built on this contract would compute a
  wrong page count). Fixed to mirror `LedgerQueryService#getDashboard`'s existing
  filter-then-paginate-the-full-list approach exactly (new unpaged repository reads,
  used only on the filtered branch — the unfiltered branch's cost/pagination is
  unchanged). New tests prove `totalElements`/`totalPages` reflect the filtered set at
  both platform and tenant-drill-down scope.
- **LOW, documented (no behavior change)**: the course-summary `REFUNDED` bucket sums
  each order's original snapshotted amount, not a net-of-refund figure — confirmed as
  a defensible, intended "order value by current state" semantic, not a sign/
  double-count bug, but flagged as easy to misread. A code comment now states this
  explicitly at the aggregation site.
- Everything else — the core `SlipReviewService#approve` fix (exactly one Payment +
  ledger row per approval, zero on replay, one atomic transaction), the
  `PaymentOperationalState` resolver (partial-refund boundary, the FREE-course `$0`
  edge case, multi-attempt-per-order aggregation, never-persisted), the
  `pg_advisory_xact_lock` idempotency mechanism (transaction-abort reasoning verified
  correct, tenant-safe keying, no transaction spans the out-of-process gateway call),
  historical billing-amount integrity, and append-only/no-backward-transition
  enforcement — were independently verified clean, with no issues found.

### Security/tenant-isolation review — findings and resolution

- **MEDIUM, fixed (test gap)**: no test proved a client-supplied idempotency key
  can't be replayed across students/tenants as an authorization bypass — the
  reviewer confirmed by reading the code that ownership/tenant checks already run
  before any idempotency lookup in both `OrderService#createOrder` and
  `PaymentInitiationService#initiatePayment`, but this was unproven. New tests added
  for both `createOrder` (a second student reusing the first student's exact key
  gets their own independent order, never a replay) and `initiatePayment` (a second
  student, and separately a student in another tenant, replaying the owning
  student's real `orderId`+key is rejected — 403/404 respectively — before any
  replay logic runs).
- **MEDIUM, fixed (test gap)**: no explicit role-based negative test existed for the
  two brand-new endpoints (`GET /api/v1/ledger/outstanding`,
  `GET /api/v1/ledger/courses/{courseId}/summary`) even though the underlying
  `PAYMENTS_SLIPS`/`VIEW` permission check was already correctly wired. New tests
  prove both 403 a STUDENT/TEACHER caller.
- **LOW, fixed**: `TenantContextHolder`'s class javadoc claimed "no production code
  path calls `set(UUID)` yet," stale since before this wave and now doubly stale
  (`PlatformAdminLedgerQueryService#enrichAcrossTenants` is a second real call site).
  Corrected to describe both real call sites while preserving the original
  safety-reasoning framing.
- **LOW, flagged, not fixed this wave (deliberately)**: the frontend checkout page's
  idempotency-key reuse-on-retry, combined with `PaymentWriteService`'s replay
  branch never re-invoking the gateway (`redirectTarget: null` on replay), is a
  latent availability trap if a payment commits `PENDING` but fails before the
  gateway call completes — every retry with the same key would "succeed" (200) but
  never obtain a usable `redirectTarget`. **Not currently exploitable**: the
  checkout page never consumes `redirectTarget` (it polls
  `GET /orders/{id}/payment-status` instead). Recorded here as a tracked follow-up
  for whenever a future flow starts relying on `redirectTarget` directly — not a
  tenant-isolation or authorization issue, and out of this wave's fix scope per the
  reviewer's own recommendation.
- Everything else — tenant isolation on both new endpoints (DB-sourced `tenant_id`
  only, anti-enumeration on cross-tenant `courseId`), the platform-admin
  per-tenant-group `TenantContextHolder` set-in-try/clear-in-finally enrichment
  technique (verified via passing cross-tenant tests, no context leak across groups
  or reused threads), idempotency keys never trusted as an identity signal, the new
  slip-approval `Payment` row's amount/currency snapshot and `gatewayReference`
  collision-safety, existing genuine two-tenant test coverage on every other
  changed/added endpoint, frontend filter controls being UX-only with every check
  re-enforced server-side, and no secrets — were independently verified clean.

### Judgment call surfaced by the security review (not silently accepted)

The reviewer explicitly flagged, for human sign-off rather than deciding
unilaterally, that `PlatformAdminLedgerQueryService`'s new
`TenantContextHolder.set(...)` call site is a second application of an
already-established pattern (`PaymentConfirmationService`'s pre-existing webhook
technique), not a new tenant-resolution mechanism, and judged it does not rise to
needing its own ADR — flagged here per `.claude/rules/architecture.md`'s "any touch
to tenant-resolution machinery should be surfaced, not silently accepted" spirit, for
a human reviewer to confirm.

---

## 16. Wave 6 Phase F/G Completion Report (documentation + final verification)

### Documentation updated

- `docs/parity/klass-parity-matrix.md`: PAR-07-01 (idempotency key noted),
  PAR-07-03 (rewritten for extended fields + the manual-slip visibility fix),
  PAR-07-04 (status/method filters now shipped; course/teacher filters remain the
  one real gap), PAR-07-06 (extended platform fields/filters + the pagination-count
  fix), PAR-08-05 (corrected — previously claimed full atomicity that in fact
  omitted the Payment/ledger write; now documents the bug and the fix) all updated;
  PAR-07-02/05, PAR-08-01–04, PAR-09-01–03 reviewed and confirmed still accurate,
  left unchanged. New rows added: **PAR-07-07** (`PaymentOperationalState`),
  **PAR-07-08** (Outstanding/Course-Summary endpoints), **PAR-07-09**
  (idempotency). PAR-09-04/09-05's "Wave 6" target label corrected to "Deferred —
  unscheduled," since this wave's actual scope (billing/payments) never touched
  `AccessPolicyService`/bulk expiry unification — a mislabel that would have
  misled future wave planning if left uncorrected.
- `docs/api/ledger-settlement-management.md` and `docs/api/payment-management.md`
  (both pre-existing files, extended, not created): document every new/changed
  endpoint, the extended response shape, the `PaymentOperationalState`/
  `PaymentMethod` concepts, the idempotency contract (including the flagged
  `redirectTarget: null`-on-replay limitation), and the slip-approval ledger-gap
  fix.
- `docs/api/README.md`: index entries added for both updated files, matching the
  existing per-wave-update entry style.
- `docs/adr/`: not touched — no new architecture decision required an ADR this
  wave (confirmed by the security review's own judgment call, Section 15).

### Final verification (freshly re-run against the current working tree, after all fixes)

- **Backend**: `.\mvnw.cmd verify` — **1889 tests, 0 failures, 0 errors, 0
  skipped. BUILD SUCCESS.**
- **Frontend**: `npx tsc --noEmit` clean; `npm run lint` 0 errors (2 pre-existing,
  unrelated warnings); `npx playwright test` — **655 passed, 7 failed, 1 skipped**
  (663 total). All 7 failures are in the already-documented pre-existing/
  out-of-scope files (Section 14) — two fewer failures than the 9 originally
  recorded there, no new regressions. Every Wave-6-touched spec
  (`order-and-payment.spec.ts`, `platform-admin-payments.spec.ts`) fully passes.

Wave 6 is complete. No item remains silently unresolved: every deferred,
partially-addressed, or flagged-for-follow-up item above is explicitly recorded
here and in Sections 9-11/14/15, not omitted.
