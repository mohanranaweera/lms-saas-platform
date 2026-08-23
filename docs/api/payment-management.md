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
  `404` before `PaymentDomainAccessGuard` runs at all. The guard itself only ever raises
  `403`, for a same-tenant caller who is neither the owning student nor a staff member
  holding the grant.
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
  "courseId": "3fae2b1e-..."  // required, must resolve to a PUBLIC course in the caller's own tenant
}
```

No `price`, `amount`, `currency`, `tenantId`, or `studentId` field exists on this type —
not merely ignored if sent, structurally absent. `@JsonIgnoreProperties(ignoreUnknown =
true)` silently drops any extra client-supplied field.

**Success — `201`** (`ApiResponse<OrderResponse>`, see shape below — `amount`/`currency`
snapshotted server-side from `course.price` at this instant). **`409 CONFLICT`** if the
course isn't `PUBLIC`. **`404 NOT_FOUND`** if `courseId` doesn't resolve within the
caller's own tenant.

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
(`ApiResponse<OrderResponse>`). **`403`/`404`** uniform for cross-tenant, another
student's order, or a nonexistent id.

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

### `GET /api/v1/payments/{id}`

Read a single payment. Owner-or-staff-`VIEW` (see the plan-deviation note above).
**`200`** (`ApiResponse<PaymentResponse>`). **`403`/`404`** uniform.

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
**`200`** (`ApiResponse<RefundResponse[]>`). **`403`/`404`** uniform.

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
