# Multi-course checkout, facility-fee override and postage

Status: implemented, integration tests pending (2026-10-19) · Branch `feat/klass-course-enrollment-parity` · Builds on KLASS-T4c
(`docs/plans/klass-enrollment-payment-parity.md`, ADR-028). Ledger entry types, enrollment and
paid-entitlement activation rules are **unchanged**.

## 1. Existing behavior (before this change)

- Composer (`frontend/src/components/payments/composer/*`) holds items for ONE course; switching
  course showed "A payment covers one course at a time, so your selected items will be cleared."
- `CheckoutComposerService` quotes/creates a line order for a single `courseId`;
  `student_order.course_id NOT NULL`; lines (`student_order_line`, V87) already carry `course_id`.
- Paid entitlements are granted per order line (`EntitlementGrantService`, per `line.courseId()`)
  only on verified gateway confirmation or approved slip - already multi-course-capable.
- Tenant facility fee already exists as tenant config `PAYMENT.facility_fee_mode`
  (`NONE|PER_LINE|PER_ORDER`, default `NONE`) + `PAYMENT.facility_fee_amount` (>= 0); no admin UI.
- Collection routing: one ACTIVE PayHere account per tenant (`PaymentGatewayAccountApi`), else bank
  slip. All courses of a tenant route to the same account, so **the only possible incompatibility
  inside one tenant cart is currency**.
- Delivery address: the existing student billing address (`student_profile.address_line`, `city`,
  `GET/PUT` own billing address) - reused, no new address subsystem.
- Discounts: composer discount is always 0 (no discount policy exists) - unchanged.

## 2. Facility-fee calculation basis (decision)

The existing basis is explicit (`facility_fee_mode`), so it is preserved and one mode is added:

| Mode | Charge | Course override applies |
|---|---|---|
| `NONE` (default) | no facility fee | no (UI says fees are off) |
| `PER_COURSE` (new) | effective fee **once per distinct course** in the order, allocated to that course's first line | yes |
| `PER_LINE` (existing) | effective fee of the line's course on every line | yes |
| `PER_ORDER` (existing) | tenant default once per order, on line 1 | no (documented) |

Effective course fee = `course.facility_fee_override` when NOT NULL (0 = no fee), else tenant
`facility_fee_amount`. Null-check only - zero is never treated as missing.
The acceptance fixture uses `PER_COURSE`. Tenant default changes affect only future quotes; orders
keep their snapshot.

## 3. Data / migration (V90 - assigned centrally)

- `course.facility_fee_override NUMERIC(12,2) NULL CHECK (>= 0)`;
  `course.postage_fee NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (>= 0)` (no existing postage config).
- `student_order.course_id` nullable **only for line orders** (multi-course); single-course orders
  still set it. `student_order.postage_amount` (null for legacy) + delivery snapshot columns
  (`delivery_recipient_name`, `delivery_address_line`, `delivery_city`, `delivery_phone`).
  Fee check replaced: `amount = subtotal + facility_fee + coalesce(postage,0) - discount`.
- New immutable `student_order_course_charge` (one row per course per line order): effective fee,
  source (`TENANT_DEFAULT|COURSE_OVERRIDE|NOT_APPLICABLE`), basis, charged facility fee, postage
  selected/amount, currency. Unique `(tenant_id, order_id, course_id)`, composite tenant FKs.
- No backfill; legacy and existing line orders untouched. `PER_COURSE` added to config validator.

## 4. API contract (student, `/api/v1/checkout`, STUDENT only)

- `POST /quote` `{ items:[{courseId, scope, year?, month?, moduleId?, lessonId?}], postageCourseIds:[uuid] }`
  (`courseId` top-level still accepted as default for items without one).
- Quote response adds: `lines[].courseId/courseTitle`, `courses[]` (per-course subtotal,
  facilityFee, facilityFeeSource, postageFee (configured), postageSelected, postageCharge),
  `postageTotal`, `discountTotal`, `feeBasis`, `deliveryRequired`, `delivery` (on-record address),
  `quoteToken` (hash of the full breakdown).
- `POST /orders` = quote body + `quoteToken` (required) + `idempotencyKey`. Server re-quotes under
  coverage locks; token mismatch -> `409 QUOTE_CHANGED` with the fresh quote in `details.quote`.
- New 422 codes: `CURRENCY_MISMATCH` (names the courses/currencies; cart kept client-side),
  `POSTAGE_COURSE_NOT_IN_CART`, `DELIVERY_DETAILS_REQUIRED`, `QUOTE_REQUIRED`. Foreign/unknown course
  -> 404. Client prices/tenant ids are never read.
- Course admin: course response adds `facilityFeeOverride`, `tenantDefaultFacilityFee`,
  `facilityFeeBasis`, `effectiveFacilityFee`, `postageFee`; update accepts
  `facilityFeeInherit` (true -> null override) / `facilityFeeOverride` (>= 0) / `postageFee` (>= 0),
  audit-logged like price changes.
- Tenant default: existing tenant config `PUT` for domain `PAYMENT` (`facility_fee_mode`,
  `facility_fee_amount`).

## 5. Agent ownership

| Agent | Owns |
|---|---|
| parent | plan, contract, integration, docs |
| database-architect | `V90__multi_course_checkout_fees_postage.sql` |
| backend-springboot-engineer | `backend/src/main/java/**` + backend tests |
| frontend-nextjs-engineer | composer, course fee/postage settings, tenant facility-fee settings |
| qa-test-engineer | acceptance tests A-R (backend IT + Playwright) |
| payment-ledger-specialist / security-reviewer | one focused read-only review each |

## 6. Settlement / revenue (ADR-029, owner-approved 2026-10-19)

Payment-ledger review found multi-course payments (NULL `course_id`) never reached teacher
settlements. Fix: per-course *allocations* of each ledger entry (course share = its lines'
`line_total`; postage never allocated; refunds pro rata, remainder to the largest share) in
`LedgerRevenueService`/`RevenueAllocator`; `TeacherSettlementService` settles per
(entry, course); V91 makes `teacher_settlement_item` unique on `(tenant_id, ledger_entry_id,
course_id)`. Postage is excluded from teacher gross (owner decision). Finance totals stay per
ledger entry (now include postage). Course payment summary / audiences include orders with a
line for the course, amount = that course's share. Facility fee in settlement stays status quo
(O-1 still open). Items settled before V91 keep their stored amounts.

## 7. Status, tests, limitations

Implementation: **complete, integration tests not yet run** (Docker Desktop unresponsive during
the session).

Implemented deviations: under `NONE` the per-course effective fee shows 0, under `PER_ORDER` the
tenant default; the delivery address enters `quoteToken` only when postage is selected; idempotency
replay compares coverage keys + postage course ids (different selection = 409); multi-course gateway
item description = "N courses"; course clone copies override/postage; course fee/postage capped
below 1,000,000 like the tenant fee; outstanding/ledger views show "N courses"/"Multiple courses".

Checks actually run:
- V90 `FlywayMigrationIntegrationTest` 1/1 pass (before Docker failed). V91 **not validated**.
- Backend `test-compile` clean; unit tests 137 pass (RevenueAllocator, CourseService,
  CourseKlassRules, EnrollmentActivation, EntitlementGrant, LessonAccess, FinanceUnit,
  TeacherSettlementShareCalculation, SlipReview, SlipUpload, PaymentSlipResponseShape,
  OrderService, PaymentConfirmation).
- Frontend `tsc --noEmit` clean, `npm run lint` 0 errors; Playwright unit specs 13/13
  (`e2e/unit/composer-model.spec.ts`, `fee-validation.spec.ts`), mocked E2E 9/9
  (`e2e/klass-multi-course-checkout.spec.ts`, `klass-fees-postage-admin.spec.ts`), existing
  `klass-composer-tags.spec.ts` 3/3.
- Reviews: payment-ledger (findings fixed, settlement via ADR-029), security (no
  critical/high/medium; low test gaps added).

Still to run (Docker required), from `backend/`:
`MAVEN_OPTS=-Xmx512m ./mvnw.cmd test -Dtest="FlywayMigrationIntegrationTest,MultiCourseCheckoutIntegrationTest,TeacherSettlementIntegrationTest,FinanceReportIntegrationTest,CheckoutComposerEntitlementIntegrationTest,LineOrderPaymentStartGuardIntegrationTest,KlassRolesAndPaymentOwnershipAcceptanceIntegrationTest,TenantConfigIntegrationTest,LedgerControllerCrossTenantIntegrationTest,NotificationDispatchIntegrationTest" -DargLine="-Xmx1536m -XX:+UseSerialGC"`,
then the full `mvnw verify`, then restart the dev backend (applies V90/V91 to local lms_dev) and run
the live `e2e/klass-payment-composer.spec.ts`.

Limitations / open: no staff postage-fulfilment list (postage + delivery visible on slip review
and in `student_order_course_charge`); composer discounts remain 0 (no discount policy exists);
O-1 (facility fee in settlement) still open; new postage/fee owner decisions recorded in ADR-029.
