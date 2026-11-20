# Klass Manual Payment & Free Card — specification

Plan, contracts and decision IDs (M*, F*): `docs/plans/klass-manual-payment-free-card.md`.
Approval: `docs/adr/ADR-030-manual-payment-receipts-and-free-cards.md`.

## 1. Screenshot / live field matrix

| Screen | Field / control | Klass | Implemented as |
|---|---|---|---|
| Page | Course\*, Student\* selectors; `?studentId=&courseId=` | Observed | Student search + course select (student's courses); `?studentId=<profile id>&courseId=`; both untrusted, re-validated server-side |
| Page | Student card (initials, profile link, number, phone) | Observed | Name, email, mobile, link to student profile |
| Page | "ISSUE A FREE CARD" | Observed | Button, shown only with `FREE_CARDS/CREATE_EDIT` |
| Page | "`<Name>`'s Payments" + "ADD PAYMENT" + month rows with amount, print, expand | Observed | History list per order: status badge (Received / Pending slip / Pending online / Rejected), months, received amount, adjustment, refunds, expandable receipt details. Print not built (no receipt template exists) |
| Page | "Undo this payment" | Live | "Reverse (refund)" link to the existing refund flow (M8) — never a delete |
| Page | Registration Fee / Course Enrollment Fee "Mark as paid" | Observed | **Not built**: no charging rules exist for either fee (would invent business rules) |
| Page | "Paid resources of `<course>`" | Observed | Read-only list of PAID lessons with state (Paid / Pending / Not paid) |
| Add Payment | Month\* | Observed (chip) | Multi-select of billing months offered for the selected courses |
| Add Payment | Course(s)\* multi | Observed | Multi-select of the student's MONTHLY courses; items = course × month where an offer exists |
| Add Payment | Discount (LKR), chips 75/50/25 %, refresh | Observed | Staff adjustment (M3); chips compute % of the course-fee subtotal; **reason field added** (required when > 0) |
| Add Payment | Reference Number | Observed | Optional, ≤ 100 chars |
| Add Payment | Payment Method: Cash, Bank Transfer, Card | Live | Required enum CASH / BANK_TRANSFER / CARD |
| Add Payment | Net Amount + refresh | Observed | Server quote; Net = total − adjustment; confirmation summary before submit |
| Add Payment | — | Not in Klass | **Received on** date (required, default today, tenant tz), Notes (optional), Postage per course (only when a course has a postage fee) |
| Free Card | Student (read-only), Course\* "python - LKR 3,000.00" | Observed | Same; course list = student's MONTHLY courses with monthly price |
| Free Card | From / To "Leave empty if not necessary" | Observed | **Required** (F3 deviation: never lifetime) |
| Free Card | Type\*: Full, Half | Live | FULL; HALF shown disabled "not supported yet" (F2) |
| Free Card | Temporary Credit | Observed | **Not built** — meaning not visible anywhere (F2) |
| Free Card | — | Not in Klass | **Reason** (required); confirmation summary (student, course, months, validity) |
| Free Cards tab | Course filter, Student / Mobile / Email / No. of free cards / Actions | Live | `/tenant-admin/payments/free-cards`: course + status filters, one row per card with revoke action |

## 2. Confirmed rules vs assumptions

Confirmed (brief / repository rules): enrollment free and payment-independent (ADR-028); entitlements
only from confirmed backend state; ledger append-only; no frontend-success activation; tenant from
auth context; separate permissions for record vs issue vs revoke; Free Card is non-cash.

Assumptions (owner to ratify): M3 adjustment-as-receipt-field; M4 exact-amount only; M6 method list
is Klass's; F2 HALF/Temporary Credit deferred; F3 required validity and 24-month cap; F4 MONTHLY-only,
class-fee lessons only; F5 overlap rejected rather than merged; F9 free-card months still purchasable.

## 3. Permissions

| Action | Grant | Roles |
|---|---|---|
| View page, history, offers | `PAYMENTS_SLIPS/VIEW` | Tenant Admin, Finance Staff, Student Support, Read-only Auditor |
| Quote / record manual receipt | `PAYMENTS_SLIPS/APPROVE` | Tenant Admin, Finance Staff |
| Reverse (refund) | `PAYMENTS_SLIPS/APPROVE` (existing) | Tenant Admin, Finance Staff |
| View free cards | `FREE_CARDS/VIEW` | Tenant Admin, Finance Staff, Student Support, Read-only Auditor |
| Issue free card | `FREE_CARDS/CREATE_EDIT` | Tenant Admin |
| Revoke free card | `FREE_CARDS/DELETE` (status change, never a row delete) | Tenant Admin |
| Students, teachers | none | — (403) |

## 4. Lifecycles

Manual receipt: (none) → **RECORDED** (payment CONFIRMED, ledger PAYMENT_CONFIRMED, entitlements
granted) — atomic. Correction: refund rows + REFUND ledger entries (existing); the receipt and payment
rows never change.

Free Card: **SCHEDULED** (now < effective_from) → **ACTIVE** → **EXPIRED** (now ≥ effective_until);
any non-revoked state → **REVOKED** (reason, actor, time; terminal). Status is derived from time;
only revocation writes.

## 5. Financial and access treatment

| | Manual receipt | Free Card |
|---|---|---|
| Order / lines | Yes (composer snapshot, full price) | No |
| Payment | One CONFIRMED, amount = cash received | No |
| Ledger | PAYMENT_CONFIRMED (cash) | No |
| Settlement | ADR-029 pro-rata allocation of the cash | Excluded (no ledger row) |
| Discounts | Automatic rules as in checkout; staff adjustment recorded separately with reason | n/a |
| Facility fee / postage | As quoted (postage optional, needs address on record) | None charged, none waived |
| Access | `content_entitlement` per line, evidence = payment | MONTHLY coverage of covered months while effective |
| Reversal | Refund (access unchanged, O-2) | Revocation (only its own coverage) |

Shared evaluator (`LessonAccessService`) serves the content tree, launch, materials, video playback,
player handoff, live-class join; free-card coverage is one more source of active coverage keys, so
publication, lesson window, device, participation and linked-course rules are unchanged.

## 6. Data and API

Migrations V93 (`manual_payment_receipt`), V94 (`free_card`); API per plan §4. No existing table,
migration or API contract changes; `DomainArea.FREE_CARDS` and `PaymentMethod.MANUAL_RECEIPT` are
additive.
