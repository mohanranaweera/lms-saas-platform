# course-billing — API Contract

Covers Wave 2's ("Course/Class model + billing foundation") additions to
`com.lms.coursemanagement`: the pricing-model/billing-configuration/billing-period surface, and
the course-lifecycle actions (archive/unarchive/clone/pricing-model change) that shipped in the
same wave. Not a separate top-level domain — everything below lives inside `course-management`
(`.claude/rules/architecture.md` has no `course-billing` in the confirmed domain list), extending
the `Course` aggregate `docs/api/course-management.md` already documents (MVP-008). This file is
additive to, not a replacement for, `course-management.md` — read that file first for the base
`Course`/module/lesson contract, auth model, and pagination/error-code conventions, which are
identical here and not repeated.

Written directly from the shipped backend (`CourseBillingController`, `CourseController`'s new
endpoints, `BillingConfigurationService`, `CourseService`) and the Wave 2 module plan
(`docs/plans/MVP-022 Course Billing and Lifecycle.md`), after a full three-agent Phase E review
(architecture, security/tenant-isolation, payment-ledger) — see
`docs/adr/ADR-015-free-course-zero-amount-payment-and-ledger.md` for the one decision that review
surfaced and the product owner explicitly resolved.

## Response envelope

Every endpoint below returns `com.lms.common.api.ApiResponse<T>` — identical shape to every
other domain's contract, see `docs/api/identity-access-service.md`, not repeated here.

## Data shape

### `Course.pricingModel` (V37)

One of `FREE` / `ONE_TIME` / `MONTHLY` / `SESSION` / `CUSTOM`, defaulting to `ONE_TIME` for every
course (matching the pre-Wave-2 behavior — a flat `course.price`). Changed only via
`PATCH /api/v1/courses/{id}/pricing-model`.

| Value | Checkout amount resolves from | Notes |
|---|---|---|
| `FREE` | Always `$0` | The **only** value that auto-activates enrollment on checkout — see "FREE checkout" below. |
| `ONE_TIME` | `course.price` | Pre-Wave-2 behavior, unchanged. |
| `MONTHLY` | The course's current open `course_billing_period.amount` | `null`/unconfigured until a billing configuration + at least one billing period exist. |
| `SESSION` | Same as `MONTHLY`, plus `course_billing_configuration.session_rate` is meaningful | |
| `CUSTOM` | Nothing system-resolved | `requiresManualQuote = true`; an authorized staff actor must supply an amount per order. **No reachable checkout endpoint exists yet** — see "CUSTOM pricing — deferred checkout" below. |

### `Course.archivedAt` (V37)

`null` means not archived. A non-null timestamp is a pure listing-visibility flag — set/cleared
via `POST /api/v1/courses/{id}/archive` / `.../unarchive` — never a delete, and never touches
enrollment/payment/price-history/billing-period data.

### `course_billing_configuration` (V38)

One row per course (`UNIQUE (tenant_id, course_id)`). Holds the settings that vary by pricing
model but don't belong on `Course` itself:

| Field | Meaningful for | Notes |
|---|---|---|
| `sessionRate` | `SESSION` only | Rejected (`400`) if set on a non-`SESSION` course — never silently ignored. |
| `currency` | every model this table applies to | ISO-4217, 3 chars. |
| `requiresManualQuote` | `CUSTOM` only | Rejected (`400`) if `true` on a non-`CUSTOM` course. |

### `course_billing_period` (V39)

Append-only rate history for a billing configuration — the Wave 2 analogue of
`course_price_history` for `course.price`. At most one **open** (`effectiveTo == null`) period
per configuration at a time (`uq_course_billing_period_current`, a partial unique index). Adding
a new period closes the current open one (`effectiveTo = effectiveFrom` of the new period) and
inserts the new one, in one transaction — never an `UPDATE` of a period's `amount`.

## Auth requirements and authorization model

Identical to `docs/api/course-management.md`'s "Auth requirements"/"Authorization model"
sections — every endpoint below requires `Authorization: Bearer <accessToken>`, and
`@PreAuthorize("isAuthenticated()")` is a coarse gate only. The real, combined
staff-matrix-or-Teacher-ownership check happens in `BillingConfigurationService`/`CourseService`
via `CourseAccessGuard`, re-loading the course through the tenant-scoped repository first (so a
cross-tenant course id is structurally invisible — `404` — before ownership is even evaluated),
exactly as every other course-mutation endpoint in this codebase.

## Endpoints

### `GET /api/v1/courses/{courseId}/billing-configuration`

Requires `VIEW`-equivalent course access (staff `DomainArea.COURSES` `VIEW`, or the owning
Teacher). **`404`** if no configuration exists yet for the course.

```jsonc
{
  "success": true,
  "data": {
    "id": "<uuid>", "courseId": "<uuid>",
    "sessionRate": 25.00, "currency": "USD", "requiresManualQuote": false,
    "createdAt": "2026-08-01T00:00:00Z", "updatedAt": "2026-08-01T00:00:00Z"
  }
}
```

### `POST /api/v1/courses/{courseId}/billing-configuration`

Creates the course's one configuration row, or updates it in place if one already exists
(never a second row — `200` either way, `CourseBillingConfigurationRequest`):

```jsonc
{
  "sessionRate": 25.00,          // optional, NUMERIC(12,2) >= 0; only valid when pricingModel == SESSION
  "currency": "USD",             // required, exactly 3 chars
  "requiresManualQuote": false   // defaults false; only valid as true when pricingModel == CUSTOM
}
```

**`200`** (`ApiResponse<CourseBillingConfigurationResponse>`, same shape as the `GET` above).
**`400 VALIDATION_ERROR`** if `sessionRate` is set on a non-`SESSION` course, or
`requiresManualQuote: true` is set on a non-`CUSTOM` course (rejected outright, never silently
dropped).

### `GET /api/v1/courses/{courseId}/billing-periods`

Paginated history (`PageResponse<CourseBillingPeriodResponse>`, default sort
`effectiveFrom,DESC`, standard pagination params per `course-management.md`). **`404`** if no
billing configuration exists for the course yet.

```jsonc
{
  "success": true,
  "data": {
    "content": [
      { "id": "<uuid>", "billingConfigurationId": "<uuid>", "amount": 49.99, "currency": "USD",
        "effectiveFrom": "2026-09-01T00:00:00Z", "effectiveTo": null,
        "createdAt": "2026-09-01T00:00:00Z" }
    ],
    "page": 0, "size": 20, "totalElements": 1, "totalPages": 1
  }
}
```

### `POST /api/v1/courses/{courseId}/billing-periods`

Adds a new billing period, closing the currently-open one (if any) in the same transaction
(`CourseBillingPeriodRequest`):

```jsonc
{
  "amount": 49.99,                       // required, NUMERIC(12,2) >= 0
  "currency": "USD",                     // required, exactly 3 chars
  "effectiveFrom": null                  // optional; null = "effective now" (server-resolved, never client-trusted for anything but a genuinely future date)
}
```

**`201`** (`ApiResponse<CourseBillingPeriodResponse>`). **`404`** if no billing configuration
exists yet for the course ("create one before adding a billing period"). **`409 CONFLICT`** if a
concurrent request has already inserted a current open period for the same configuration
(`uq_course_billing_period_current` race, mapped to a clean `409` rather than surfacing as a
generic `500`/constraint-violation fallback).

### `PATCH /api/v1/courses/{id}/pricing-model`

The one write path for `course.pricingModel` (`CoursePricingModelChangeRequest`):

```jsonc
{ "pricingModel": "SESSION" }   // required, one of FREE/ONE_TIME/MONTHLY/SESSION/CUSTOM
```

**`200`** (`ApiResponse<CourseResponse>`, full course shape — see below). A true no-op (new value
equal to current) writes no audit event, mirroring `PATCH .../price`'s existing no-op behavior.
A genuine change publishes `CoursePricingModelChangedEvent` for audit-log coverage. No
`course_pricing_model_history` append-only table exists (unlike `course.price`) — only the audit
event, a documented, narrower scope than `changePrice`'s history-table discipline.

### `POST /api/v1/courses/{id}/archive` / `POST /api/v1/courses/{id}/unarchive`

Sets/clears `course.archivedAt`. **`200`** (`ApiResponse<CourseResponse>`). A true no-op (already
in the target state) writes no audit event. A genuine change publishes
`CourseArchiveStateChangedEvent`. Purely a listing-visibility flag (`GET /api/v1/courses`'s new
`includeArchived` query param, default `false`) — never touches enrollment, payment, price
history, or billing-period history.

### `POST /api/v1/courses/{id}/clone`

Creates a brand-new course (new id, `DRAFT` status, never archived, a uniqued
`<slug>-copy-<8 hex chars>`) that copies the source course's classification fields, module/lesson
structure, and `pricingModel` only. **Never** copies billing configuration, billing-period
history, price history, or any enrollment/payment/review data — structurally impossible for any
of those to reference the new id, since nothing populates them here. `price` is copied only when
the source course's pricing model is `ONE_TIME`; every other pricing model clones with
`price = 0` (the clone's own `pricingModel` is still copied as-is, so it starts out requiring the
same billing-configuration setup the source course would — deliberately not auto-created).

**`201`** (`ApiResponse<CourseResponse>`, the new cloned course). Gated identically to
`POST /api/v1/courses` on the source course (staff: `CREATE_EDIT`; Teacher: ownership). Publishes
`CourseClonedEvent` for audit-log coverage (added as a Phase E review fix — cloning previously
left no audit trail).

## FREE checkout — auto-activation, gated strictly on `pricingModel`

`CourseLookupApi.getResolvedCheckoutAmount(UUID courseId)` (consumed by
`payment-management`'s `OrderService.createOrder`, not a `course-management` REST endpoint
itself) resolves a `CheckoutAmount` record: `amount`, `currency`, `billingPeriodId`,
`requiresManualQuote`, and `freePricing`.

**`freePricing` is `true` only when the course's own `pricingModel` is genuinely `FREE`** — this
is the field `OrderService` branches its FREE-checkout auto-activation on, **never** the resolved
amount alone being `$0`:

- `freePricing == true` → checkout auto-confirms a real `$0` `Payment` row (driven through the
  same `PENDING → CONFIRMED` transition every gateway-confirmed payment uses) and writes a real
  `$0` `PAYMENT_CONFIRMED` `ledger_entry` row (same `LedgerEntryApi.recordPaymentConfirmed` call
  the real gateway-confirmation path uses) — enrollment activates through the unchanged,
  standard confirmed-payment path.
- `freePricing == false` and the resolved amount is `$0` (a misconfigured/mistyped `$0`
  `ONE_TIME` price, or `$0` `MONTHLY`/`SESSION` billing period — both structurally reachable,
  since neither `PATCH .../price` nor `POST .../billing-periods` enforces a floor above zero) →
  the order is rejected outright with `409 CONFLICT` — *"This course's configured price is zero
  but it is not priced as FREE - check the course's price/billing configuration."* — before any
  `student_order`/`payment` row is persisted. Never silently free-activated.
- `freePricing == false` and the resolved amount is `> 0` → the normal paid-checkout path,
  unchanged.

See `docs/adr/ADR-015-free-course-zero-amount-payment-and-ledger.md` for the full decision
record: a Phase E review found the originally-shipped code gated on the resolved amount alone
(broader than approved) and found no ledger entry was written at all for a genuine FREE
confirmation. Both were fixed — the `freePricing` gate above, plus a new, additive
`V42__allow_zero_amount_ledger_entry_for_free_course_confirmations.sql` migration widening
`ledger_entry.amount`'s CHECK constraint (entry-type-aware: `PAYMENT_CONFIRMED >= 0`,
`REFUND < 0`, never a plain sign-agnostic widening) — before the wave was signed off.

## CUSTOM pricing — deferred checkout, not a bug

`CUSTOM` pricing resolves server-side: `requiresManualQuote = true` on `CheckoutAmount`, and
`OrderService.createOrder` accepts an optional `customAmount` honored **only** when the course
resolves as `CUSTOM` **and** the caller independently holds staff
`DomainArea.COURSES`/`CREATE_EDIT`-equivalent permission. That logic is implemented and tested at
the service layer, but **`POST /api/v1/orders` remains student-only** — there is no
staff-on-behalf-of-student order-creation endpoint, and `OrderCreateRequest` has no target-student
field. A `STUDENT`-role caller can never hold the required staff permission, so **a
`CUSTOM`-priced course cannot actually be purchased by anyone today**. This is a deliberately
deferred capability (adding a target-student field would be a new, security-sensitive
impersonation-shaped endpoint), not a defect — see `OrderService`'s own class javadoc for the
full rationale. The frontend storefront/checkout surfaces this correctly: a `CUSTOM`-priced
course renders "Contact us for pricing" with no checkout form at all (never a form that would
`409`).

## Response shapes (Wave 2 additions to existing shapes)

`CourseResponse` (see `docs/api/course-management.md` for the full pre-Wave-2 field list) gained:

```jsonc
{
  // ...all pre-Wave-2 fields unchanged...
  "pricingModel": "ONE_TIME",       // FREE | ONE_TIME | MONTHLY | SESSION | CUSTOM
  "archivedAt": null,               // null = not archived
  "resolvedAmount": 49.99,          // null only when requiresManualQuote is true (CUSTOM) or MONTHLY/SESSION has no configured billing period yet
  "currency": "USD",
  "requiresManualQuote": false
}
```

`PublicCourseResponse` (storefront) gained the same four fields minus `archivedAt` (never
exposed publicly) — see `docs/api/course-management.md`'s existing shape for the base fields it
omits (`teacherId`, audit columns).

**`CourseBillingConfigurationResponse`**: `{ id, courseId, sessionRate, currency,
requiresManualQuote, createdAt, updatedAt }`.

**`CourseBillingPeriodResponse`**: `{ id, billingConfigurationId, amount, currency,
effectiveFrom, effectiveTo, createdAt }`.

## Error codes (additions to `course-management.md`'s table)

| Case | Code | Status |
|---|---|---|
| `sessionRate`/`requiresManualQuote` set inconsistent with the course's `pricingModel` | `VALIDATION_ERROR` | `400` |
| No billing configuration exists yet for `GET`/`POST .../billing-periods` | `NOT_FOUND` | `404` |
| Concurrent insert races `uq_course_billing_period_current` | `CONFLICT` | `409` |
| Checkout on a non-`FREE` course resolving to `$0` (misconfigured price/billing period) | `CONFLICT` | `409` |
| Checkout on a `CUSTOM` course by a student (no staff on-behalf-of-student endpoint exists) | `FORBIDDEN` | `403` |
