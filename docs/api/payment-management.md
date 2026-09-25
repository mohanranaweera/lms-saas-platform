# payment-management — API Contract

Covers Order/Payment/Refund management (MVP-010 / `com.lms.paymentmanagement`), plus the
one endpoint it structurally does *not* own (the payment webhook — see "Webhook
ownership" below). Written retroactively, after a full six-specialist review of the
completed MVP-010 module found this contract file had never been produced despite the
plan requiring it (`docs/plans/MVP-010 Order and Payment Foundation.md` §10) before
implementation began — the same "process gap" `docs/api/course-management.md` records
for MVP-008. This file reflects the actual shipped backend, not the plan's pre-
implementation draft; every deviation from that draft is called out explicitly below.

## Response envelope

Every endpoint returns `com.lms.common.api.ApiResponse<T>` — see
`docs/api/identity-access-service.md`'s "Response envelope" section for the exact shape
(`success`/`data`/`error`/`timestamp`/`traceId`); identical here, not repeated.

## Auth requirements

- Every endpoint below requires a valid `Authorization: Bearer <accessToken>` header.
  `@PreAuthorize` at the controller is a coarse gate only (`hasRole('STUDENT')` or
  `isAuthenticated()`) — the real authorization check happens in the service layer
  (`OrderService`, `PaymentQueryService`, `RefundService`, `PaymentDomainAccessGuard`),
  since it depends on data (the order's owning student, or the caller's
  `PAYMENTS_SLIPS` grant) not available to a `@PreAuthorize` SpEL expression without
  loading the entity first anyway.
- `tenant_id`, `student_id`, and `amount`/`price` are never accepted from the client on
  any endpoint below — always resolved from `TenantContext`/`AuthenticatedPrincipal`, or
  (for `amount`) snapshotted server-side from `course.price` at order-creation time.
  `OrderCreateRequest` structurally has no `price`/`tenantId`/`studentId` field to even
  populate.

## Authorization model

- **Order/Payment/Refund reads** (`GET /orders/{id}`, `GET /orders/{id}/payment-status`,
  `GET /payments/{id}`, `GET /payments/{id}/refunds`) use one shared rule,
  `PaymentDomainAccessGuard.requireOwnerOrStaffView`: the owning student may read their
  own resource, or any staff caller holding `DomainArea.PAYMENTS_SLIPS`/`VIEW`
  (Tenant Admin, Finance Staff, Student Support, Read-only Auditor) may read any
  resource in their own tenant. **Deviation from the plan's §10 draft**: the draft
  listed `GET /payments/{id}` as staff-only (no student); the shipped implementation
  additionally allows the owning student, for consistency with every other read
  endpoint in this table (a student can already read the same information via
  `GET /orders/{id}/payment-status`, so excluding them here would have been an
  inconsistent gap, not a deliberate restriction).
- A cross-tenant id is invisible before ownership is even evaluated — the owning
  repository's tenant-scoped `findById` returns empty for it, which callers turn into a
  `404` before `PaymentDomainAccessGuard` runs at all. **Anti-enumeration convention**
  (mirrors `SlipAccessGuard`/`MaterialAccessGuard`): the guard itself raises `404`, not
  `403`, for a same-tenant Student caller who is not the resource's owning student — a
  Student must never be able to distinguish "exists but isn't mine" from "doesn't exist".
  A same-tenant staff caller who lacks the `PAYMENTS_SLIPS`/`VIEW` grant still gets `403`
  — staff already have legitimate visibility into their own tenant's resource existence.
- **Order creation** (`POST /orders`) and **payment initiation**
  (`POST /orders/{id}/payments`) are `hasRole('STUDENT')`-gated at the controller, with
  the service layer independently re-checking (defense-in-depth, mirroring
  `CourseService`'s pattern). Payment initiation is additionally **owning-student-only**
  — a staff caller, even one holding `PAYMENTS_SLIPS`/`VIEW`, may never initiate a
  payment on a student's behalf.
- **Refund creation** (`POST /payments/{id}/refunds`) is gated on
  `DomainArea.PAYMENTS_SLIPS`/`APPROVE` — held only by Tenant Admin and Finance Staff.
  Student Support and Read-only Auditor (both `VIEW`-only) are rejected `403`, as is any
  student attempting to refund their own payment. Per `.claude/rules/payments.md` §8,
  this `hasPermission` check is a coarse category grant only — `RefundService`
  independently re-verifies the payment is in a refund-eligible `CONFIRMED` state and
  that the amount doesn't exceed the refundable remainder, regardless of what the
  permission check returned.

## Endpoints

### `POST /api/v1/orders`

Create an order for a course. `hasRole('STUDENT')`.

**Request body** (`OrderCreateRequest`):

```jsonc
{
  "courseId": "3fae2b1e-...",  // required, must resolve to a PUBLIC course in the caller's own tenant
  "idempotencyKey": "b1e0..."  // optional, Wave 6 — see "Idempotency (Wave 6)" below
}
```

No `price`, `amount`, `currency`, `tenantId`, or `studentId` field exists on this type —
not merely ignored if sent, structurally absent. `@JsonIgnoreProperties(ignoreUnknown =
true)` silently drops any extra client-supplied field.

**Success — `201`** (`ApiResponse<OrderResponse>`, see shape below — `amount`/`currency`
snapshotted server-side from `course.price` at this instant). A repeat call with the same
`idempotencyKey` returns **`200`** (not `201`) with the pre-existing order unchanged —
see "Idempotency (Wave 6)" below. **`409 CONFLICT`** if the course isn't `PUBLIC`.
**`404 NOT_FOUND`** if `courseId` doesn't resolve within the caller's own tenant.

**Reactivation gate — two more `409 CONFLICT` variants (MVP-012/ADR-013 §9), added on top of
the "course isn't `PUBLIC`" case above.** Before creating the order, `OrderService` resolves the
caller's enrollment access state for this course via `enrollment-management`'s
`EnrollmentAccessApi` (see `docs/api/enrollment-management.md`'s "Cross-module contract"
section):

- **`ACTIVE`** (a current, non-expired enrollment already exists) → `409`, message "You are
  already enrolled in this course".
- **`EXPIRED`** with no `APPROVED`, unfulfilled reactivation request for this (student, course)
  pair → `409`, message "Reactivation approval is required before you can re-order this course".
  The student must first submit a reactivation request
  (`POST /api/v1/enrollments/{enrollmentId}/reactivation-requests`) and have a Tenant Admin
  approve it before retrying this call.
- **`EXPIRED`** with an `APPROVED`, unfulfilled request → the order is created normally and, in
  the SAME transaction, linked to that request (`reactivation_request.new_order_id`). A second,
  concurrent `createOrder` call racing against the same already-linked request also gets `409`
  with the identical "Reactivation approval is required..." message (the request has already
  been consumed) — the just-inserted order row rolls back with it, never left half-created.
- **`NEVER_ENROLLED`** → unchanged, ordinary first-time purchase, no new precondition applies.

All three `409` variants share the generic `ApiErrorCodes.CONFLICT` machine-readable code —
callers distinguish them only by `error.message` text, exactly like the pre-existing
"course isn't `PUBLIC`" case above; there is no dedicated error code per variant.

`OrderResponse`:

```jsonc
{
  "id": "...",
  "studentId": "...",
  "courseId": "...",
  "amount": 49.99,
  "currency": "USD",      // hardcoded platform-wide default (OrderService.DEFAULT_CURRENCY) —
                            // course.price has no currency column anywhere in this codebase yet;
                            // see docs/requirements/open-decisions.md
  "status": "PLACED",     // PLACED | PENDING — deliberately incomplete enum, no CANCELLED/EXPIRED value
  "createdAt": "2026-08-23T10:15:00Z",
  "updatedAt": "2026-08-23T10:15:00Z"
}
```

### `GET /api/v1/orders/{id}`

Read a single order. Owner-or-staff-`VIEW` (see "Authorization model"). **`200`**
(`ApiResponse<OrderResponse>`). **`404`** for cross-tenant, another student's order (a
same-tenant Student caller gets `404` here, not `403` — anti-enumeration), or a
nonexistent id. **`403`** only for a same-tenant staff caller lacking `PAYMENTS_SLIPS`/
`VIEW`.

### `GET /api/v1/orders/{id}/payment-status`

Polled by the frontend's awaiting-confirmation screen. Owner-or-staff-`VIEW`. **This is
the only legitimate source of truth for "is this order paid"** — never derive
paid/confirmed state from a redirect query param or any other client-visible signal.

**Success — `200`** (`ApiResponse<OrderPaymentStatusResponse>`):

```jsonc
{
  "hasPaymentAttempt": true,
  "paymentId": "...",         // null if hasPaymentAttempt is false
  "status": "CONFIRMED",      // PENDING | CONFIRMED | REJECTED | REFUNDED | null; REFUNDED is
                                // reachable in the enum but this endpoint's underlying payment.status
                                // column never actually has REFUNDED written to it — see "Refund
                                // model" below. A refund does not surface here at all today (this
                                // read is payment-status-derived, not ledger-derived — see plan §21
                                // item 8 for the resolved payment.status/REFUNDED contradiction).
  "confirmedAt": "2026-08-23T10:16:30Z"  // null unless status is CONFIRMED
}
```

### `POST /api/v1/orders/{id}/payments`

Initiate a gateway payment attempt for this order. `hasRole('STUDENT')`,
**owning-student-only** (staff, even with `VIEW`, get `403`). **Not part of the plan's
§10 draft table** — added because the checkout flow needs an explicit "start paying"
action distinct from order creation, to exercise the plan's own §9 transaction-boundary
shape (a short transaction persists a `PENDING` payment row; the gateway call happens
*outside* any open transaction; a second short transaction persists the returned
reference).

**Request body** (`PaymentInitiationRequest`, Wave 6 — optional at the controller level;
omitting the body entirely behaves identically to this wave's predecessor):

```jsonc
{
  "idempotencyKey": "b1e0..."  // optional, Wave 6 — see "Idempotency (Wave 6)" below
}
```

**Success — `201`** (`ApiResponse<PaymentInitiationResponse>`):

```jsonc
{
  "paymentId": "...",
  "orderId": "...",
  "status": "PENDING",
  "gatewayReference": "gw-ref-...",     // opaque, from integration-management's gateway adapter
  "redirectTarget": "https://..."       // opaque gateway redirect URL; the frontend checkout screen
                                          // never navigates to this itself and never marks anything
                                          // paid based on it — see the awaiting-confirmation screen
}
```

A repeat call with the same `idempotencyKey` for the same order replays the existing
`PENDING`/`CONFIRMED` payment row's view instead of creating a second `Payment` — see
"Idempotency (Wave 6)" below. **Known limitation, flagged not fixed this wave**: the
replay branch never re-invokes the gateway, so `redirectTarget` is `null` on replay. Not
currently exploitable — the checkout page never consumes `redirectTarget` directly (it
polls `GET /orders/{id}/payment-status` instead) — but recorded here as a tracked
follow-up for whenever a future flow starts relying on `redirectTarget` directly
(`wave-06-plan.md` §15).

### `GET /api/v1/payments/{id}`

Read a single payment. Owner-or-staff-`VIEW` (see the plan-deviation note above).
**`200`** (`ApiResponse<PaymentResponse>`). **`404`** for cross-tenant, another
student's payment (anti-enumeration — see "Authorization model"), or a nonexistent id.
**`403`** only for a same-tenant staff caller lacking `PAYMENTS_SLIPS`/`VIEW`.

```jsonc
{
  "id": "...",
  "orderId": "...",
  "amount": 49.99,
  "currency": "USD",
  "status": "CONFIRMED",
  "gatewayReference": "gw-ref-...",   // null until confirmed/rejected
  "confirmedAt": "2026-08-23T10:16:30Z",
  "createdAt": "2026-08-23T10:15:05Z"
}
```

### `POST /api/v1/payments/{id}/refunds`

Create a refund. `DomainArea.PAYMENTS_SLIPS`/`APPROVE` only (Tenant Admin, Finance
Staff). **Request body** (`RefundCreateRequest`):

```jsonc
{
  "amount": 25.00,                        // required, > 0, up to 2 decimals, <= refundable remainder
  "reason": "Duplicate charge.",          // required, non-blank, max 1000 chars
  "idempotencyKey": "b1e0..."             // optional UUID string; a resubmission with the same key
                                            // (same tenant + original payment) replays the original
                                            // refund result instead of creating a second row — added
                                            // to close the resubmission-idempotency gap the module
                                            // review found (no dedup existed on first ship)
}
```

**Success — `201`** (`ApiResponse<RefundResponse>`). **`409 CONFLICT`** if the payment
isn't `CONFIRMED`, or `amount` exceeds the refundable remainder (original amount minus
the sum of any prior refunds) — `fieldErrors: [{field: "amount", ...}]`. **`403`** for
any caller not holding `APPROVE` (including a student refunding their own payment,
Student Support, Read-only Auditor). **`400`/`422`** for an empty/blank `reason` before
any state change.

```jsonc
{
  "id": "...",
  "originalPaymentId": "...",
  "amount": 25.00,
  "reason": "Duplicate charge.",
  "createdAt": "2026-08-23T11:00:00Z"
}
```

A refund never mutates the original `payment` row (see "Refund model" below) — it only
ever creates a new `payment_refund` row plus a reversing `ledger_entry`
(`docs/api/ledger-settlement-management.md`).

### `GET /api/v1/payments/{id}/refunds`

List refunds against a payment. Same owner-or-staff-`VIEW` rule as payment read.
**`200`** (`ApiResponse<RefundResponse[]>`). **`404`** for cross-tenant, another
student's payment (anti-enumeration), or a nonexistent id. **`403`** only for a
same-tenant staff caller lacking `PAYMENTS_SLIPS`/`VIEW`.

## Refund model

`payment.status`'s DB `CHECK` constraint retains a `REFUNDED` value for literal fidelity
to the original spec wording, but no code path ever writes it — `Payment.confirm()`/
`Payment.reject()` are the payment entity's only two status mutators, and `RefundService`
never touches `payment.status` at all. A refund's existence is signaled exclusively by a
`REFUND`-type `ledger_entry` (with `reversesEntryId` pointing at the original
`PAYMENT_CONFIRMED` entry) and by `GET /payments/{id}/refunds`'s non-empty response —
never by `payment.status`. This resolves the "does `REFUNDED` on an already-terminal
payment row violate immutability" question the plan's own draft flagged as open (§21
item 8): the answer is that `REFUNDED` is unreachable by design, so the question doesn't
arise.

## Idempotency (Wave 6)

`POST /api/v1/orders` and `POST /api/v1/orders/{id}/payments` each accept an optional,
client-generated `idempotencyKey` (UUID). It is **never** trusted as an identity or
authorization signal — purely a dedup key. Ownership/tenant checks (the order belongs to
the calling student, in the calling tenant) run **before** any idempotency lookup on both
endpoints — proven by a dedicated Wave 6 security-review test: a second student reusing
the first student's exact key on `createOrder` gets their own independent order (never a
replay of the first student's), and a second student — or a student in another tenant —
replaying the owning student's real `orderId` + key on `initiatePayment` is rejected
(`403`/`404` respectively) before any replay logic runs.

Uniqueness scope mirrors `PaymentRefund.idempotencyKey`'s existing per-parent-row
scoping, never a bare global-unique key: `student_order (tenant_id, student_id,
idempotency_key)` and `payment (tenant_id, order_id, idempotency_key)`, both partial
unique indexes (`WHERE idempotency_key IS NOT NULL`) added by
`V52__add_idempotency_key_to_order_and_payment.sql`. Both columns are nullable/additive —
an existing caller that never supplies a key keeps pre-Wave-6 behavior exactly.

**Concurrency mechanism — a new idiom for this codebase.** The plan's originally
suggested "mirror `RefundService`'s catch-and-requery idiom" was tried first and failed
under a genuine Testcontainers concurrent-race test: Postgres aborts the *entire* ambient
transaction on a unique-constraint violation, so a plain
`catch(DataIntegrityViolationException)` cannot "un-abort" it and continue to a replay
read in the same transaction — `RefundService`'s idiom only works because it operates
against an *existing, lockable* parent row, whereas a fresh `createOrder`/
`createPendingPayment` call has no pre-existing row to lock. Fixed instead with a
**transaction-scoped Postgres advisory lock** (`pg_advisory_xact_lock`, keyed
`tenantId:studentId-or-orderId:idempotencyKey`) acquired *before* the replay check —
`StudentOrderRepository#acquireIdempotencyLock` / `PaymentRepository#acquireIdempotencyLock`
(documented in both repositories' javadoc). Proven under a genuine `CyclicBarrier`
concurrent-race Testcontainers test in both `OrderCreationIdempotencyIntegrationTest` and
`PaymentInitiationIdempotencyIntegrationTest`: exactly one concurrent caller's row wins,
the other observes/replays it, no unique-constraint exception ever leaks to a caller.

## Manual Payment Slip endpoints (MVP-011, `com.lms.paymentmanagement.slip`)

Payment Slip Intelligence sub-module — a distinct state machine from
`payment.status` above, never a generic "payment status" enum with extra values
bolted on. States: `SUBMITTED -> UNDER_REVIEW -> APPROVED | REJECTED`, one-directional
only (no reversal endpoint). See `docs/architecture/payment-ledger.md` §3-4 and
`docs/adr/ADR-012-audit-log-slice-and-slip-enrollment-activation.md` for the full
design/decision record — this section is the endpoint contract only.

**Auth model**: identical owner-or-staff-`VIEW` convention as the rest of this file for
reads (404, not 403, for a same-tenant Student who isn't the slip's own owner — see
`PaymentDomainAccessGuard`, shared with the order/payment/refund reads above).
Review-queue list and every mutating endpoint (approve/reject) require staff
`PAYMENTS_SLIPS`/`APPROVE` specifically — a student is always `403` on those, regardless
of ownership. `tenantId`/`studentId`/`reviewerId`/`status` are never accepted from the
client on any request body.

### `POST /api/v1/orders/{orderId}/slips` (multipart/form-data)

Upload a manual payment slip against an order the caller owns. `hasRole('STUDENT')`,
owning-student-only (server-resolved via `OrderService.loadOrderOwnedByCurrentStudent`,
never a client-supplied student id). Parts: `referenceNumber` (text, required,
non-blank, max 255 chars) + `file` (binary, required).

Server-side validation, in order, before any write: ownership → bounded-size streaming
read → magic-byte content sniff (PDF/PNG/JPEG/GIF signatures only — never trusts
`Content-Type`/filename) → SHA-256 hash → object-store write → `payment_slip` row
insert. Zero partial write on any failure — a save failure after a successful store
triggers a best-effort compensating delete of the just-stored object.

**Success — `201`** (`ApiResponse<PaymentSlipResponse>`, shape below — `flags` may
already be non-empty, since duplicate checks run synchronously before the response is
returned). **`404`** if `orderId` doesn't resolve to an order owned by the caller.
**`409 CONFLICT`** if the target order already has another active (`SUBMITTED`/
`UNDER_REVIEW`) slip — enforced by `uq_payment_slip_tenant_order_active`, one active
slip per order. **`413 PAYLOAD_TOO_LARGE`** if the file exceeds
`app.payment.slip.max-file-size-bytes` (25 MB default, unratified MVP value). **`415
UNSUPPORTED_MEDIA_TYPE`** if the magic-byte sniff fails. **`400 VALIDATION_ERROR`** if
`referenceNumber` is blank/missing or the `file` part is missing.

`PaymentSlipResponse`:

```jsonc
{
  "id": "...",
  "orderId": "...",
  "studentId": "...",
  "referenceNumber": "REF-12345",
  "status": "SUBMITTED",      // SUBMITTED | UNDER_REVIEW | APPROVED | REJECTED — SUBMITTED
                                // is transient: SlipDuplicateCheckService.runChecksAndAdvance
                                // runs synchronously inside the upload call, so every slip
                                // has already advanced to UNDER_REVIEW by the time this
                                // response (or any later read) is returned
  "submittedAt": "2026-08-24T10:15:00Z",
  "reviewerId": null,          // null until reviewed
  "reviewedAt": null,          // null until reviewed
  "flags": [
    { "id": "...", "flagType": "DUPLICATE_REFERENCE", "detectedAt": "2026-08-24T10:15:00Z" }
  ],                            // append-only — every flag ever detected, never filtered
                                 // to latest-only; frontend renders this verbatim, computes
                                 // zero duplicate-detection logic of its own
  "studentEmail": "student@example.com",   // resolved via identity-access-service's
                                             // UserProvisioningApi.findTenantUserSummaries,
                                             // batched once per request/page — never a
                                             // per-row cross-module call. Expected to be
                                             // non-null in practice (FK-backed), but not
                                             // schema-guaranteed — treat as nullable, same
                                             // as reviewerEmail below
  "reviewerEmail": null,        // null until reviewed, same resolution as studentEmail
  "orderAmount": 49.99,         // from the same-domain student_order row this slip is
                                 // evidence for — lets a reviewer cross-check the slip
                                 // against the expected amount
  "orderCurrency": "USD"
}
```

### `GET /api/v1/payment-slips/{slipId}`

Read a single slip's full detail, including complete flag history. Owner student OR
staff `PAYMENTS_SLIPS`/`VIEW`. **`200`** (`ApiResponse<PaymentSlipResponse>`). **`404`**
for cross-tenant, another student's slip (anti-enumeration — a Student caller always
gets 404, never 403, for a slip that isn't theirs), or a nonexistent id. **`403`** only
for a same-tenant staff caller lacking `PAYMENTS_SLIPS`/`VIEW`.

### `GET /api/v1/payment-slips/{slipId}/download-url`

Same auth as the detail read above. **`200`** →
`{ "url": "https://...", "expiresAt": "2026-08-24T10:20:00Z" }` — a short-lived (5
minute TTL) signed URL, never a raw storage key. Mirrors `MaterialController`'s
`GET /{id}/download-url` pattern exactly. Fetch fresh on every click; never cache,
prefetch, or persist client-side.

### `GET /api/v1/payment-slips/review-queue`

Staff `PAYMENTS_SLIPS`/`VIEW` only — `403` for a student regardless of ownership.
Query params: `status` (optional — one of the four enum values; **omitting it entirely**
returns the real default pending queue, `SUBMITTED` + `UNDER_REVIEW` combined; there is
no "ALL" enum value), standard Spring `Pageable` params (`page`, `size`, `sort` —
default `size=20`, `sort=submittedAt,ASC`, i.e. oldest-first/FIFO, to avoid reviewer
starvation on old slips). **`200`** → `ApiResponse<PageResponse<PaymentSlipResponse>>`
(same `PageResponse<T>` shape used elsewhere in this file). Every row's `studentEmail`/
`reviewerEmail`/`orderAmount` is batch-resolved once for the whole page, not per row.

### `POST /api/v1/payment-slips/{slipId}/approve`

Staff `PAYMENTS_SLIPS`/`APPROVE` only. Body (optional): `{ "overrideReason": "..." }`
(max 1000 chars). Approval and enrollment activation
(`EnrollmentActivationApi.activateFromApprovedSlip`) commit in the same transaction as
the slip-status write — and, when overriding, so does the audit-log write
(`AuditLogApi.record`, `com.lms.auditlogmanagement`) — all three or none.

**Ledger-gap fix (Wave 6).** Before this wave, this endpoint transitioned the slip and
activated enrollment atomically but never created/confirmed a `Payment` row or wrote a
`PAYMENT_CONFIRMED` ledger entry — the one confirmation path in this codebase that didn't
follow the pattern the other three (gateway webhook, FREE checkout, staff-granted
enrollment) all establish, violating `.claude/rules/payments.md` §2 ("if a screen shows
'paid' but no corresponding ledger entry exists, that is a bug"). A manually-approved
slip payment was correctly enrolled but invisible on every ledger-derived Payment History
/ Payment Dashboard view (`docs/api/ledger-settlement-management.md`). **Fixed**: this
call now additionally creates+confirms a `Payment` row
(`gatewayReference = "SLIP-" + paymentId`) and writes the `PAYMENT_CONFIRMED` ledger
entry, in the **same** transaction as the slip-status write and enrollment activation — a
forced ledger-write failure rolls back the whole approval transition (tested). The
response shape (`PaymentSlipResponse`) is unchanged — the new `Payment`/`LedgerEntry`
rows are an internal side effect, visible only through the ledger-derived endpoints. **No
historical backfill**: slips approved before this wave keep their pre-existing
enrollment/access untouched, but are not retroactively given synthetic `Payment`/
`ledger_entry` rows (a deliberate, flagged judgment call — `wave-06-plan.md` §9/§10).

**Success — `200`** (`ApiResponse<PaymentSlipResponse>`). **`409 CONFLICT`** if the
slip carries unresolved flags and no/blank `overrideReason` was supplied — rejected
before any row lock, state change, or audit write. **`409`** if the slip isn't
`UNDER_REVIEW` and isn't already `APPROVED` either. **Idempotent**: calling this again
on an already-`APPROVED` slip is always a no-op `200` with the existing view, regardless
of whether `overrideReason` is resupplied (flags are append-only and never cleared, so a
slip originally approved via override permanently carries flag rows — a bare retry must
still succeed, not re-throw the reasonless-override `409`); this also creates zero
additional `Payment`/ledger rows (tested). **`403`** for any caller without `APPROVE`
(including the slip's own student, or a `VIEW`-only staff role).

### `POST /api/v1/payment-slips/{slipId}/reject`

Staff `PAYMENTS_SLIPS`/`APPROVE` only. Body (required): `{ "reason": "..." }`
(non-blank, max 1000 chars). One-directional terminal transition — no reversal
endpoint; enrollment stays inactive. **Success — `200`**
(`ApiResponse<PaymentSlipResponse>`). **`400`/`409`** if `reason` is blank or the slip
isn't `UNDER_REVIEW`. **`403`** for any caller without `APPROVE`.

## Staff-granted manual enrollment (Wave 3, ADR-016)

`POST /api/v1/students/{id}/enroll` — a Tenant Admin/Student Support action letting staff
manually grant a student access to a course (scholarship, goodwill grant, payment
collected out-of-band) without the student completing a normal checkout. Lives here, in
`payment-management` (`com.lms.paymentmanagement.order.web.ManualEnrollmentController`),
**not** in `user-management`, despite the `/students/{id}/...` URL — this is a Wave 3
fix-pass architectural correction: the endpoint originally lived on
`user-management`'s `StudentController` and called outward into this module, closing a
`payment-management → enrollment-management → user-management → payment-management`
cross-module cycle. It now resolves the path's `student_profile` id via
`user-management`'s `StudentLookupApi` (an already-approved `api`-only dependency
direction) instead. The public contract — path, request/response shape — is unchanged by
that move.

This is change-controlled per `.claude/rules/payments.md` §7 (a new way for enrollment to
activate) and was approved by the product owner before implementation, recorded in full in
`docs/adr/ADR-016-staff-granted-enrollment-and-revocation.md` — read that file for the
complete decision record, alternatives considered, and consequences; this section
documents only the REST contract.

### `POST /api/v1/students/{id}/enroll`

Staff (`STUDENTS`/`CREATE_EDIT`, re-verified independently inside `ManualEnrollmentService`
as `PAYMENTS_SLIPS`/`APPROVE` — mirroring `SlipReviewService#approve`'s exact gate, per
`wave-03-plan.md` §4's "exact second gate decided during backend implementation").

**Request body** (`StudentEnrollRequest`):

```jsonc
{ "courseId": "<uuid>", "reason": "Scholarship grant approved by finance committee" }
// courseId required; reason required, non-blank, max 1000 chars
```

Creates a real `student_order` row and a real `payment` row for the course's genuinely
resolved checkout amount (never a synthesized `$0`, unless the course itself is priced
`FREE` — mirrors ADR-015's precedent exactly), confirms that payment, and writes a real
`PAYMENT_CONFIRMED` ledger entry via the same `LedgerEntryApi#recordPaymentConfirmed` call
every other CONFIRMED-payment path uses — **never** a bespoke ledger-write code path. The
evidence trail distinguishing this from a gateway/slip-confirmed payment is a synthesized
`gatewayReference` of `"STAFF_GRANTED-" + paymentId` (mirroring `activateFreeCheckout`'s
own `"FREE-" + paymentId` precedent) plus a new, mandatory `payment.staff_grant_reason`
column (`V47__add_enrollment_staff_granted_evidence.sql`). Enrollment activation happens
through a new, explicit, narrowly-scoped 5th call site on `EnrollmentActivationApi`,
`fromApprovedManualEvidence(paymentId, orderId, studentId, courseId)` — internally
delegates to the SAME `activateOrReactivateFromConfirmedPayment` mechanics every other
confirmed-payment path already uses (same independent `PaymentStatusApi`
re-verification), so this new call site can never skip the re-verification every other
evidence type already gets.

Writes **two** `AuditLogApi.record(...)` entries in the same transaction (a Wave 3
fix-pass addition, same rationale as `POST /enrollments/{id}/revoke`'s dual-row behavior
in `docs/api/enrollment-management.md`): one targeting `payment`/`{paymentId}` (`action:
"enrollment.manually_granted"`), and a second targeting `student_profile`/`{id}` with the
same action/reason, so the grant is discoverable via `GET /students/{id}/activity`.

**Success — `200`** (`ApiResponse<Void>`). **`404`** — `courseId` doesn't resolve in the
caller's own tenant, or isn't published. **`409 CONFLICT`** — the student is already
actively enrolled in this course; or the course is `CUSTOM`-priced (requires a manually
quoted amount, not supported by this action — mirrors `OrderService`'s own "customAmount
required" rejection). **`400`** — `reason` blank/missing, or `courseId` missing. **`403`**
— caller lacks `STUDENTS`/`CREATE_EDIT`, or (independently) lacks `PAYMENTS_SLIPS`/
`APPROVE`.

## Webhook ownership (not this domain's endpoint)

`POST /api/v1/integrations/webhooks/payment` receives the gateway's confirmation
callback. It is owned and exposed by `integration-management`, **not**
`payment-management` — listed here only to make the boundary explicit, per the plan's
own §10 note. It is `permitAll()` (no session/JWT — a webhook has neither) but gated by
a required, fail-closed HMAC-SHA256 signature check
(`com.lms.integrationmanagement.gateway.WebhookSignatureVerifier`) before any state
change; tenant identity for the resulting confirmation is resolved exclusively from the
platform's own matched `payment` row (via `PaymentRepository`'s one deliberate, named
cross-tenant-lookup bypass, `findByGatewayReferenceAcrossTenants`), never from any field
in the webhook payload — the payload structurally carries no tenant claim at all. See
`docs/adr/ADR-011-webhook-tenant-resolution-carve-out.md`.
