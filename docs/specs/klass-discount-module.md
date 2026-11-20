# Klass Discount Module — Specification (DISC-01)

Plan, field matrix and decision log: `docs/plans/klass-discount-module.md` (D1–D13 referenced below).

## 1. Observed Klass behaviour vs. our decisions

| Observed (screenshots / demo) | Not established by Klass → our rule |
|---|---|
| Four types; common name/description/dates/max uses/min purchase/active | Dates inclusive, tenant tz (D10); max uses global per rule (D9); min purchase vs course-payment subtotal (D8) |
| Selection modes All / Any / Minimum count (+ Minimum Count) | Count = distinct required courses with ≥1 eligible line in this order (D5) |
| Advance: Payment Type = Installments | Advance = future-month MONTHLY lines vs tenant current month (D1) |
| Free course: Free For (Months), "counted from the day payment goes through" | Calendar-month reward lines from the current tenant month (D2) |
| Fixed (LKR) / Percentage (%) | Fixed once per order, pro-rata allocation (D4) |
| Discount Code field | Trimmed, case-insensitive, tenant-unique, 6-40 characters (D7, hardened §6) |
| Edit / Delete / toggle | Edit = new version; Delete = archive (D11, D13) |
| — | No stacking per line; best saving wins (D6); zero-total blocked (D3); redemption reservations (D12); one use per student per rule (§6) |

## 2. Pricing pipeline (backend-authoritative)

Shared by quote, order placement, payment initiation (via the immutable order), slip submission and
approval (via the immutable order), and entitlement grant (per immutable order line).

1. Existing composer resolves the cart into priced lines (offers, coverage, pending, facility fee,
   postage) — unchanged.
2. Accepted reward rules (`acceptedRewardRuleIds`) may add MONTHLY reward lines (D2) before fees are
   computed, so their facility fee is priced by the existing fee rules.
3. Candidate rules = tenant, not archived, active, `valid_from ≤ today ≤ valid_to` (tenant tz), currency
   = cart currency, capacity available, and the student holds no counting redemption of the rule (§6);
   the code rule is included only when the entered code matches.
4. Each evaluator (advance, bundle amount-off, free course, code) returns eligibility + the eligible
   untaken lines + amount, or a reason.
5. Greedy selection (D6); allocation (D4); amounts are scale 2, HALF_UP; per-line discount ≤ unit amount;
   allocations sum exactly to the discount.
6. Facility fees and postage are never discounted. `line_total = unit + fee − discount`; `order.amount =
   subtotal + fees + postage − discountTotal` (V87/V90 CHECKs).
7. Total = 0 → `blockingIssue = ORDER_TOTAL_ZERO` (quote) / `422 ORDER_TOTAL_ZERO` (order) (D3).
8. Quote token (`v2`) hashes every line's discount, reward flag, rule version ids and code, so any
   change between quote and order → `409 QUOTE_CHANGED` with the revised quote.

The cart is never cleared by the server; codes and reward acceptances are inputs to a re-quote.

## 3. Order snapshot

On placement (same transaction as the order): one `student_order_discount` per applied rule (rule id,
version id, type, name, code, value type/value, amount, qualification JSON: qualifying courses,
advance periods, reward months) and each line's `discount_amount`, `order_discount_id`, `reward`.
Rows are immutable. Editing, disabling or archiving a rule never changes them. Payment initiation and
slip approval use `order.amount`; settlements use ADR-029 `line_total`; refunds/chargebacks use the
existing payment-based ledger rules — nothing is recomputed from current rules.

## 4. Redemption policy (D12)

| Event | Effect on `discount_redemption` |
|---|---|
| Quote | none |
| Order placed | `RESERVED`, expires +48h (every applicable code or capped rule row locked `FOR UPDATE`; capacity and one-use-per-student re-checked) |
| Payment initiated (gateway) | lapsed hold re-validated (below) else `409 DISCOUNT_NO_LONGER_AVAILABLE`; `expires_at = payment start + app.discount.gateway-hold-ttl` (default 48h) |
| Slip uploaded | lapsed hold re-validated (below) else `409 DISCOUNT_NO_LONGER_AVAILABLE`; `expires_at = null` while the slip is open (staff resolve it) |
| Gateway retry / webhook replay / re-approval | no-op (unique per order+rule; status already set) |
| Payment rejected / slip rejected | `expires_at = now + 48h` |
| Payment confirmed / slip approved | `CONSUMED`, `expires_at = null` (same transaction as the grant) - unconditional, even when the hold had lapsed: a verified confirmation never fails because of a hold |
| Refund / chargeback | unchanged (use not restored) |
| Abandoned order | hold lapses at `expires_at` (no order cancellation status exists — V19) |

Re-taking a lapsed hold (owner decision 2026-10-21, "re-validate"), under the rule row lock: refused when
the rule is archived or inactive, today (tenant tz) is outside the rule's current validity window, usage
has reached the Maximum Uses of the version recorded on the order (`student_order_discount.rule_version_id`),
or (code or capped rule only) the student meanwhile holds another counting redemption of the rule.

Because a confirmation is unconditional, a gateway payment confirmed after its hold lapsed (and after
another order took the last use) can exceed Maximum Uses by that one late confirmation. This is the
accepted trade-off: a verified payment is never refused.

## 5. Access control

- Admin endpoints: tenant from `TenantContext`; dedicated `DISCOUNTS` permission area (owner decision
  2026-10-21): Tenant Admin VIEW + CREATE_EDIT; Finance Staff, Student Support and Read-only Auditor VIEW
  only; every other role and the Platform Admin denied. Enforced twice: `@PreAuthorize` on
  `DiscountAdminController` and `DiscountAdminService`. A VIEW-only caller sees every `code` masked as
  `******`. Foreign rule id → 404; foreign/unknown course id → 422 `DISCOUNT_COURSE_NOT_FOUND`; draft or
  archived course → 422 `DISCOUNT_VALIDATION` (the course pickers hide them too); a duplicate live code →
  409 `DISCOUNT_CODE_TAKEN`, including a concurrent create/edit race. Every
  create/edit/toggle/archive audited (actor, tenant, rule, before/after) in the same transaction; a PUT
  that flips `active` additionally writes `discount_rule.activated` / `deactivated`.
- Student: STUDENT only; responses carry only applied discounts, the entered code's status and offered
  rewards — never other rules' configuration, limits or usage counts.

## 6. Hardening (security + payment-ledger reviews, owner decisions 2026-10-21)

**Code statuses shown to students.** `codeResult.status` is one of `APPLIED | INVALID | NOT_ELIGIBLE |
USAGE_LIMIT_REACHED | NOT_APPLIED_BETTER_DISCOUNT`. A disabled, expired or not-yet-valid code is
`INVALID` with the same message as an unknown code (`This discount code is not valid.`), so a real but
unusable code cannot be told apart from a guess. The real reason is only logged server-side. `EXPIRED`
and `DISABLED` are no longer returned.

**Code-probing rate limit.** Per (tenant, student) — both from the authenticated context — a Redis
fixed-window counter of code attempts that resolve to `INVALID`, on `POST /api/v1/checkout/quote` and
`POST /api/v1/checkout/orders`. Default 10 per 15 minutes (`app.discount.code-attempts.max-invalid`,
`app.discount.code-attempts.window`). Once reached, any request that carries a code (valid or not) is
`429 DISCOUNT_CODE_RATE_LIMITED` until the window ends, and a `SECURITY` log entry
(`discount.code_rate_limited`, tenant and student ids, never the code) is written. Requests without a
code are never limited. If Redis is down the limiter fails open and logs a warning.

**Code shape.** 6-40 characters of `A-Z 0-9 _ -` after `strip()` + upper-case (admin validation and V92
CHECKs on `discount_rule.code_normalized` and `discount_rule_version.code`).

**One use per student per rule (code and capped rules).** Owner decision 2026-10-21 (cap scope): the cap
applies to every `DISCOUNT_CODE` rule and to any rule with Maximum Uses set; uncapped automatic rules
(Advance Payment, Bundle Amount Off, Bundle Free Course without Maximum Uses) apply on every order, so a
monthly advance-payment discount keeps working month after month. For a capped rule, a student may hold
at most one counting redemption (`RESERVED` and not lapsed, or `CONSUMED`). A rule the student already holds is
not a candidate for them: a value rule is not applied, a free-course reward is not offered, and an entered
code gets `NOT_ELIGIBLE` with the message `You have already used this discount.` A lapsed hold no longer
counts, so it never blocks a new order. Order placement locks every applicable code / capped rule row
before pricing and re-checks the cap under those locks before writing the reservation, so two concurrent
orders by the same student never both take a rule (the loser gets `409 QUOTE_CHANGED` with a fresh
quote). `CONSUMED` counts for good: a refund or chargeback does not restore the use.

**Bounded work per order.** At most 240 coverage advisory locks per order placement (the cart's items plus
every month an accepted free-course reward could add); more is refused with `422 ITEM_NOT_ELIGIBLE`. At
most 200 course ids per admin request list; `validFrom` and `validTo` must fall between 2000-01-01 and
2100-12-31.

**Invariants checked before commit.** Each `student_order_discount.amount` equals the sum of the lines'
`discount_amount` linked to it, and the order's `discount_amount` equals the sum of all its lines'
discounts. A mismatch aborts the placement transaction. V92 also checks that a `CONSUMED` redemption has
`expires_at IS NULL`.

**Settlement (formula unchanged; owner-approved 2026-10-21, ADR-029 addendum A1).** Revenue shares are the per-course sum of
net `line_total` (`unit + fee − discount`). A discounted bundle splits the discount pro rata into each
course's share, and a free reward course's share is 0. Covered by
`DiscountHardeningIntegrationTest.settlementSharesFollowTheNetLineTotalOfDiscountedAndRewardLines`.
