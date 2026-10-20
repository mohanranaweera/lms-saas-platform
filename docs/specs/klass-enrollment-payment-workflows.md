# Klass free enrollment, lesson entitlements and payment — implemented workflows

Source spec: `docs/specs/Klass_Enrollment_Payment.md` (IDs **EP-**). Business rule: ADR-028. Decisions
D01–D24, open items O-1–O-8, schema and API contract: `docs/plans/klass-enrollment-payment-parity.md`.
Course structure/admin/content tree: `docs/specs/klass-course-management-workflows.md`.

## 1. Records

| Concept | Table | Rule |
|---|---|---|
| Enrollment (participation) | `course_participation` (V86) | One row per tenant+student+course; free; never created/cancelled by payment state. Backfilled from every non-revoked legacy enrollment lineage (`LEGACY_ENROLLMENT`); legacy activation paths also upsert it (`PAID_ACTIVATION`, `STAFF_GRANT`). |
| Legacy paid course access | `enrollment` (unchanged) | Pre-ADR-028 payment/slip/staff-activated rows keep their window, expiry, reactivation (ADR-013) and chargeback behaviour; they count as course-wide access to COVERED sessions. Full Course (ONE_TIME) purchases still use this path. |
| Paid entitlement | `content_entitlement` (V86) | Scope MONTHLY (course+year+month) / MODULE / LESSON; NOT NULL order line + one evidence source; unique per order line; one active row per coverage; only revoke columns change. |
| Order lines | `student_order_line` (V87) + order snapshot columns | Immutable; price, facility fee, discount (always 0), currency, coverage key; `amount = subtotal + fee − discount` enforced by CHECK. |
| Progress | `lesson_progress` (V86) | First authorized launch. |

## 2. Add New Course (EP §3A)

Modal fields: Name*, Teacher* (multi-select of tenant teachers; first = primary; user ids), Payment Type*
— Monthly Fee (MONTHLY), Full Course (ONE_TIME), Day payments (DAILY), Pay For Module (MODULE),
Installments (INSTALLMENT), Free (FREE); conditional Price for Full Course / Monthly Fee (our design;
Monthly creates the billing configuration and first open period); Enrollment Fee (informational only —
never charged, never gates enrollment); SHOW/HIDE ADDITIONAL SETTINGS (values retained): Payment Valid Days
(= `access_duration_days`; entitlements snapshot `valid_until`; blank/0 = no limit), Institute Commission %
(0–100, append-only history, **not used by settlement** — O-3), WhatsApp / Telegram invitation links (https),
Description (plain text), Tags (select or create by comma/Enter; trimmed, case-insensitive dedupe, tenant
scoped; feed the student chips), Link another course (one level, same tenant, no self/cycle); Cover Image
(JPEG/PNG/WebP ≤5 MB, uploaded after creation; failure leaves the course and offers retry). Duplicate
submits are collapsed by an `Idempotency-Key` (Redis guard; fails open if Redis is down). DAILY and
INSTALLMENT can be chosen but have no purchasable offers yet.

## 3. Catalog and free enrollment

- `/student/courses` (Enrolled / Available pills, search, Sort by Default/Title, tag chips) →
  `GET /api/v1/enrollments/my/catalog`. Available = PUBLIC, not archived, registration enabled, not yet joined.
- Enroll → "Are you sure? / Enroll in <title>?" NO / YES / X → `POST /api/v1/enrollments/my/courses/{id}`:
  idempotent (concurrent requests create one row), no order/payment/ledger, audited
  `course.participation_created`; 409 `REGISTRATION_CLOSED`; 404 when not visible. The card moves to
  Enrolled only after the server confirms. Closing registration or archiving never removes an existing
  participant's enrollment or paid access.
- Expired legacy paid access shows an "Expired paid access" section on the Enrolled view and on
  `/student/payments/reactivation` with the existing reactivation request flow (ADR-013).
- The old `/student/browse` redirects to Available; enrolling never goes through checkout. The ADR-015
  $0 checkout path is no longer used for enrollment (code and history kept).

## 4. Payment composer

Global PAY (student header), a month's Pay now (MONTHLY preselected with that course+month), or a session's
Pay now (uses the server's `requiredPurchase`) open one "Make Payment" dialog:
- Tabs COURSE, MONTHLY, DAILY, MODULE, SESSION, INSTALLMENT. Courses = the student's enrolled courses with
  offers (`GET /api/v1/checkout/courses`); offers from `GET /api/v1/checkout/offers`: MONTHLY = billing
  months that have class-fee sessions, priced at the course's current monthly rate (O-8); MODULE = priced
  modules; SESSION = Paid sessions; COURSE = the Full Course offer bought through the existing checkout page;
  DAILY/INSTALLMENT = "No data available". Items show AVAILABLE / Paid / Pending.
- Summary re-quotes on every change (`POST /api/v1/checkout/quote`): lines, Subtotal, Facility Fee +
  "Includes a Facility Fee set by the institute.", Total. Facility fee = tenant PAYMENT config
  `facility_fee_mode` NONE (default) / PER_LINE / PER_ORDER + `facility_fee_amount`; fixture PER_LINE 100,
  two months at 3,500 → 7,200.00. No discount control (unsupported). One course per order.
- Pay online (shown only when the tenant's gateway is operational, ADR-017) → `POST /api/v1/checkout/orders`
  (server re-quotes, idempotency key) → existing payment initiation → PayHere form; the return page only polls.
- Upload a payment slip → bank details from tenant PAYMENT config (`bank_name`, `bank_branch`,
  `bank_account_holder`, `bank_account_number`; absent → slip disabled), deposit reference number (required
  by the duplicate-reference check) and image/PDF file → order + slip → "Submitted — pending approval".
- Already-paid, pending, foreign or unknown items → 422 (`ITEM_ALREADY_PAID`, `ITEM_PENDING`,
  `ITEM_NOT_ELIGIBLE`, `EMPTY_SELECTION`). Starting a payment or slip re-checks coverage under advisory locks;
  a second live payment attempt on the same order → 409 `ORDER_PAYMENT_IN_PROGRESS`. Legacy `POST /orders`
  refuses MONTHLY courses (409 `USE_PAYMENT_COMPOSER`).

## 5. Granting and revoking

- Verified PayHere confirmation (signature, merchant, amount, currency unchanged — ADR-017) or authorized
  slip approval (duplicate/suspicious checks unchanged) → one `PAYMENT_CONFIRMED` ledger entry (ADR-010
  unchanged) and, in the same transaction, one entitlement per order line (idempotent; webhook replay and
  double approval grant nothing more). Orders without lines keep the legacy enrollment activation.
- An expired active entitlement is superseded (`EXPIRED_SUPERSEDED`) when the same item is bought again.
- If a line is already covered by another payment, the payment still confirms; the line is skipped and
  flagged for manual refund review (log + audit when a human actor exists).
- Chargeback revokes the entitlements of that payment (and legacy enrollment as before); staff refunds
  revoke nothing (O-2). Rejected/failed payments grant nothing. Participation is never touched.
- Slip reviewers see order lines and fee totals.

## 6. Access

See the course-management workflows doc §4 (single evaluator, precedence and coverage). Paying ICT October
covers only ICT sessions associated with 2026-10; a session purchase covers only that session; enrollment
alone pays for nothing.

## 7. Known limits / open owner decisions

O-1 facility fee in teacher settlement gross; O-2 refund-driven revocation; O-3 commission in settlement;
O-4 exam/attendance/roster membership for free participants; O-5 DB-level single confirmed payment per
order; O-6 no expiry for abandoned PENDING payments (items stay Pending until resolved); O-7 no system-actor
audit rows for gateway grants/chargeback revocations/duplicate flags; O-8 past months priced at the current
rate and module prices regardless of course pricing model. Discount codes, daily and installment offers,
cross-course carts and unenroll are not implemented.
