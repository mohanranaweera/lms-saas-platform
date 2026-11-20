# Klass Discount Module — Plan (DISC-01)

Branch `feat/klass-discount-module`. Spec: `docs/specs/klass-discount-module.md`.
QA: `docs/qa/klass-discount-module-acceptance.md`.

## 1. Planning report

| Item | Answer |
|---|---|
| Goal | Four Klass discount families (Advance Payment, Bundle Amount Off, Bundle Free Course, Discount Code), admin-managed per tenant, priced server-side in the existing checkout composer. |
| Files | New package `com.lms.paymentmanagement.discount` (domain/repository/service/web); `CheckoutComposerService`/`CheckoutViews`/checkout DTOs (extend); evidence/confirmation/approval hooks for redemptions; frontend `tenant-admin/payments/discounts`, composer summary panel. |
| DB | New migration **V92** only (§4). No existing migration edited. No ledger table/type change. |
| API | New admin `/api/v1/admin/discounts/**`; additive fields on `POST /api/v1/checkout/quote` and `/orders` (request + response). No field removed or renamed. |
| Security | Admin = dedicated `DISCOUNTS` area (VIEW / CREATE_EDIT; owner decision 2026-10-21, replaces the original `PAYMENTS_SLIPS` gate). Student = existing STUDENT checkout. Code lookup is tenant-scoped, returns only a student-facing status for the code entered and is rate-limited per (tenant, student). Every rule change is audited. |
| Tenant | Every new table has `tenant_id NOT NULL` + composite FKs to tenant-owned parents; repositories use `TenantAwareRepository`/`AppendOnlyTenantAwareRepository`; course references validated against the caller's tenant (422 on foreign/unknown). |
| Tests | Unit (evaluators, allocation, rounding); Testcontainers integration (admin CRUD/versions, cross-tenant, quote/order, redemption concurrency, payment/slip completion, zero-total block); frontend unit + Playwright. |

Change-controlled areas touched (each owner-approved 2026-10-21):
- **Settlement payout effect** (payments.md §5): formula unchanged, but discounts now flow into
  ADR-029 shares (net `line_total`; a free reward course's share is 0) — ADR-029 addendum A1.
- **RBAC**: new `DISCOUNTS` domain area (replaces the `PAYMENTS_SLIPS` gate).
- **Approved API contract**: additive checkout quote/order fields; `codeResult.status` no longer emits
  `EXPIRED`/`DISABLED`; new `429 DISCOUNT_CODE_RATE_LIMITED`.

Unchanged: discounts reduce `student_order_line.discount_amount` (columns already present since V87,
previously always 0). Paid entitlements are still granted only by `EntitlementGrantApi` per order line
after verified confirmation / approved slip. No new activation path (zero-total orders are blocked, see
§3 D3). Ledger entry types unchanged.

## 2. Screenshot / demo field matrix (observed)

Common to all four types (header + "Additional Settings"):

| Field | Required | Notes |
|---|---|---|
| Discount Name | yes | Helper: "visible to the students". |
| Discount Type | yes | Options (demo dropdown): Course Bundle - Amount Off, Course Bundle - Free Course, Discount Code, Advance Payment Discount. |
| Description | no | Textarea. |
| Valid From / Valid To | yes | Date pickers (mm/dd/yyyy). |
| Maximum Uses (optional) | no | Integer. |
| Minimum Purchase Amount (LKR) | no | Money. |
| Active (discount is available for use) | toggle | Default on. |

Discount Configuration per type:

| Type | Subtitle | Fields |
|---|---|---|
| Advance Payment Discount | "Get discount for advance payments" | Select Required Courses*, Course selection mode, Payment Type* (only option: **Installments**), Fixed Amount (LKR) / Percentage (%) radio, Discount Value* |
| Course Bundle - Amount Off | "Pay for a set of courses at once and get amount off" | Select Required Courses*, Course selection mode, Fixed/Percentage radio, Discount Value* |
| Course Bundle - Free Course | "Pay for a set of courses and get other courses free for a number of months" | Select Required Courses*, Course selection mode, Select Free Courses*, Free For (Months)* (default 1; helper "Counted from the day the student's payment goes through") |
| Discount Code | "Enter code to get discount" | Select Required Courses*, Course selection mode, Discount Code*, Fixed/Percentage radio, Discount Value* |

Course selection mode (all types): **All selected courses required** ("only if all specified courses are
selected") · **Any selected courses required** ("if at least one course is selected") · **Minimum count
required** → reveals **Minimum Count*** (default 2, helper "Min required items"; mode helper "if minimum
X courses are selected").
Course multi-select: "All" checkbox + "<Course name> - LKR <price>" options (price omitted when none).
List view: tabs All (n) / Active (n) / Inactive (n); card = name, status chip, type, value
("LKR 200.00 OFF"), date range, active toggle, Edit, Delete; "Create Discount" button.

Evidence: six local screenshots + read-only demo inspection 2026-10-20 (no demo data changed; no
credentials stored anywhere).

## 3. Decisions (owner-approved 2026-10-20 unless marked "default")

- **D1 Advance payment** — Payment Type `INSTALLMENTS` = MONTHLY billing-period lines. A line is
  *advance* iff its explicit billing (year, month) is **strictly after the current month in the tenant
  timezone** (`GENERAL.default_timezone`, UTC fallback) and it is an AVAILABLE (not paid, not pending)
  offer. Selection mode counts required courses having ≥1 advance line in this order. The discount
  applies only to advance lines of required courses. Paid/pending periods never count; duplicate
  items collapse by coverage key (existing).
- **D2 Free course reward** — explicit MONTHLY reward lines of the reward course for the calendar
  window [current tenant month, current month + N − 1] ∩ the course's offered billing months, AVAILABLE
  only. Each reward line: original value = monthly fee, discount = 100% of unit amount, net course value
  0. Facility fee / postage follow existing rules and are shown before acceptance. Reward courses must
  be MONTHLY-priced and disjoint from the rule's required courses (server validation). A reward line
  already selected by the student is converted in place; missing reward months are offered and added
  only after explicit acceptance (`acceptedRewardRuleIds`). Access only via the normal per-line grant
  after the order is paid.
- **D3 Zero total** — an order whose final total would be 0 is rejected at placement: `422
  ORDER_TOTAL_ZERO`; the quote returns it with `blockingIssue = ORDER_TOTAL_ZERO` and an explanation.
  No new activation path.
- **D4 Fixed amount** — applied once per qualifying order, capped at the eligible lines' total,
  allocated pro rata by unit amount with largest-remainder rounding (ties → lower `lineNo`).
  Percentage: total = round_half_up(eligible × p / 100), then the same allocation.
- **D4a Fixed amount per billing month** (owner decision 2026-10-21, supersedes D4 for FIXED
  Course Bundle - Amount Off and Discount Code) — eligible lines are grouped by billing month (MONTHLY
  lines) plus one group for lines without a month (course / module / lesson). Each group whose own
  lines meet the course selection mode gets min(value, group's untaken total), allocated within the
  group (D4 rounding). If the order qualifies but no single group does (e.g. an "All" bundle bought in
  different months), the amount applies once per order as D4. Percentages and Advance Payment keep D4.
  Example: Rs. 200 bundle, Biology Sep + Oct → Rs. 400 (Rs. 200 per month). Orders placed before this
  change keep their snapshotted amounts.
- **D5 (default)** Qualification uses only lines in the current order (no existing paid coverage).
- **D6 (default)** Combination: no stacking on a line. Greedy: evaluate all candidates; pick the
  greatest student saving; ties → (free-course > bundle > advance > code) then earlier `validFrom`,
  then rule id; remove its discounted lines; re-evaluate the rest on remaining lines; repeat.
  Qualification is evaluated on the full base (non-reward selected lines); discounts land only on
  untaken eligible lines. Free-course: qualifiers stay discountable by other rules; reward lines are
  taken and never count as qualifiers for any rule (prevents self/cyclic rewards; a free-course rule
  whose qualifier became another rule's reward line no longer qualifies).
- **D7 (default; hardened 2026-10-21)** Codes: `strip()` + upper-case (ROOT); pattern `[A-Z0-9_-]{6,40}`; unique per
  tenant among non-archived rules.
- **D8 (default)** Minimum Purchase Amount compares to the order's course-payment subtotal (sum of
  unit amounts of non-reward lines, before discounts; excludes facility fee and postage).
- **D9 (default)** Maximum Uses = total redemptions across all students, counted per order. Course
  "All" checkbox stores the explicit list (new courses are not auto-added).
- **D10 (default)** Dates are inclusive calendar dates in the tenant timezone.
- **D11 (default)** Delete = archive (`archived_at`); history and versions are retained; archived rules
  never apply and are hidden from the list.
- **D12 (default)** Redemptions: quotes never reserve. Order placement reserves (`RESERVED`,
  `expires_at = now + 48h`) under a row lock on the rule. New payment evidence (gateway initiation /
  slip upload) re-validates; an expired reservation is re-taken if capacity remains, else `409
  DISCOUNT_NO_LONGER_AVAILABLE`; the hold then has no expiry while a bank slip is open, and a gateway
  payment holds for `app.discount.gateway-hold-ttl` (default 48h) from payment start (hardened
  2026-10-21; re-taking a lapsed hold re-validates the rule, spec §4). Payment/slip
  rejection returns the hold to a 48h TTL from that moment. Confirmation / slip approval → `CONSUMED`
  (idempotent: one row per (order, rule)). Refunds and chargebacks do **not** restore a use.
  Capacity = CONSUMED + RESERVED with (`expires_at` null or in the future).
- **D13 (default)** Rule edits create a new immutable version; orders reference the version they
  used. Value currency = tenant default currency; a rule never applies to a cart in another currency.

### Implementation refinements (backend, accepted)

- Free-course rewards resolve first (100% of a line can never be beaten on that line); among several
  free-course rules: greatest saving → earlier `validFrom` → rule id. A rule whose qualifier became a
  reward line is dropped and the plan recomputed.
- Reward months are added only for reward courses the student participates in (existing composer
  rule for every line, ADR-028 enrollment is free); otherwise a notice asks the student to enroll.
- Advance Payment required courses must be MONTHLY-priced; discount type is immutable on edit.
- ~~Code before `validFrom` → `NOT_ELIGIBLE`; `EXPIRED` only after `validTo`.~~ Superseded 2026-10-21:
  disabled, expired and not-yet-valid codes are all reported to students as `INVALID` (spec §6).
- Owner decision 2026-10-21: one counting redemption per (tenant, student, rule) for every discount type;
  order placement locks every applicable rule row, capped or not (spec §6).
- Every applied rule records a redemption (usage tracking); capacity enforced only when `max_uses` set.
- Order placement takes coverage locks (cart + accepted reward months, sorted), then capped-rule row
  locks in id order; payment initiation/slip upload use the same order.
- Abandoned holds simply stop counting after `expires_at` (no cleanup job, no cancellation status).

## 4. Data model (V92)

- `discount_rule` — header (mutable state only): `id, tenant_id, discount_type, code_normalized
  (DISCOUNT_CODE only), current_version_id, active, archived_at, created_at, created_by, updated_at`.
  Partial unique `(tenant_id, code_normalized) WHERE code_normalized IS NOT NULL AND archived_at IS
  NULL`. Index `(tenant_id, archived_at, active)`.
- `discount_rule_version` — immutable, append-only: `id, tenant_id, rule_id, version_no, name,
  description, discount_type, selection_mode (ALL|ANY|MIN_COUNT), min_count, payment_type
  (INSTALLMENTS|null), value_type (FIXED|PERCENTAGE|null), value NUMERIC(12,2), free_months, code,
  valid_from DATE, valid_to DATE, max_uses, min_purchase_amount NUMERIC(12,2), currency, created_at,
  created_by`. Unique `(tenant_id, rule_id, version_no)`; type-shape CHECKs.
- `discount_rule_version_course` — `tenant_id, version_id, course_id, role (REQUIRED|REWARD)`; PK
  `(tenant_id, version_id, course_id, role)`; composite FKs to version and `course(tenant_id, id)`.
- `student_order_discount` — immutable snapshot per applied discount: `id, tenant_id, order_id, rule_id,
  rule_version_id, discount_type, name, code, value_type, value, amount, qualification TEXT (JSON:
  qualifying course ids, advance periods, reward months), created_at`.
- `student_order_line` + `order_discount_id UUID NULL` (FK `(tenant_id, order_discount_id)`), `reward
  BOOLEAN NOT NULL DEFAULT false`; CHECK reward → `order_discount_id IS NOT NULL AND discount_amount =
  unit_amount`; CHECK `discount_amount > 0` → `order_discount_id IS NOT NULL`.
- `discount_redemption` — `id, tenant_id, rule_id, order_id, student_id, status
  (RESERVED|CONSUMED), expires_at, reserved_at, consumed_at, updated_at`; unique `(tenant_id,
  order_id, rule_id)`; index `(tenant_id, rule_id, status)`.

## 5. API contract

Admin (`DISCOUNTS`: VIEW for GET, CREATE_EDIT for writes; `@PreAuthorize` + service check; `code`
masked as `******` for VIEW-only callers):

| Method | Path | Notes |
|---|---|---|
| GET | `/api/v1/admin/discounts?status=ALL\|ACTIVE\|INACTIVE` | list + counts |
| GET | `/api/v1/admin/discounts/{id}` | current version + version count |
| GET | `/api/v1/admin/discounts/course-options` | `{courseId, name, priceLabel, monthly}` |
| POST | `/api/v1/admin/discounts` | 201 |
| PUT | `/api/v1/admin/discounts/{id}` | new version; 200 |
| PATCH | `/api/v1/admin/discounts/{id}/active` | `{active}` |
| DELETE | `/api/v1/admin/discounts/{id}` | archive; 204 |

Request body: `{name, description, discountType, courseIds[], selectionMode, minCount, paymentType,
valueType, value, rewardCourseIds[], freeMonths, code, validFrom, validTo, maxUses,
minPurchaseAmount, active}`. Validation errors → `422 DISCOUNT_VALIDATION` with field errors (existing
`ApiResponse` error shape). Foreign/unknown course → `422 DISCOUNT_COURSE_NOT_FOUND`; foreign rule id →
404; duplicate code → `409 DISCOUNT_CODE_TAKEN`.

Student (additive): quote/order requests gain `discountCode` (≤ 40 chars) and `acceptedRewardRuleIds`
(≤ 10). Quote/order responses gain: per line `discountAmount, reward, discountName`; `discountTotal`
(now real); `discounts[] {ruleId, ruleVersionId, type, name, code, amount, lineNos[], rewardCourseId,
explanation}`; `codeResult {code, status, message}` with status `APPLIED | INVALID | NOT_ELIGIBLE |
USAGE_LIMIT_REACHED | NOT_APPLIED_BETTER_DISCOUNT` (`EXPIRED` / `DISABLED` removed 2026-10-21, spec
§6); `429 DISCOUNT_CODE_RATE_LIMITED` after too many invalid codes; `rewardOffers[] {ruleId,
name, courseId, courseTitle, months[], originalValue, facilityFee, explanation}`; `notices[]`
(e.g. reward removed because a prerequisite left the cart); `blockingIssue` (`ORDER_TOTAL_ZERO` or
null). Quote token covers all discount fields (`v2`).

## 6. Agent ownership / status

| Stage | Owner | Files | Status |
|---|---|---|---|
| Migration V92 | database-architect | `V92__discount_rules.sql` | done — amended in place (unshared) for hardening; applied via Flyway on Testcontainers PG17; constraint probes pass |
| Backend | backend-springboot-engineer | `paymentmanagement/discount/**`, checkout composer, redemption hooks | done — hardening (spec §6) applied 2026-10-21 |
| Backend tests | qa-test-engineer / parent | `src/test/.../discount/**` | done — incl. cross-tenant, lifecycle, settlement and hardening tests |
| Ledger review | payment-ledger-specialist | read-only | done 2026-10-21 — 0 blockers; majors fixed or owner-decided |
| Security review | security-reviewer | read-only | done 2026-10-21 — 0 blockers; majors fixed or owner-decided |
| Frontend | parent | `tenant-admin/payments/discounts/**` (list, new, `[id]`), `lib/api/discounts.ts`, `lib/validation/discounts.ts`, Finance nav entry (`DISCOUNTS`/VIEW), composer code field / applied discounts / free-course offers / zero-total block | done 2026-10-21 — mocked Playwright specs `discounts-admin`, `klass-discount-checkout`, composer unit tests |
| Docs | parent | plan/spec/qa, ADR-029 addendum A1, roles matrix | done; QA results log updated 2026-10-21 |

Frontend notes (2026-10-21):
- Admin UI lives at `/tenant-admin/payments/discounts` (Finance → Discounts). Tenant Admin gets create /
  edit / toggle / delete; Finance Staff, Student Support and Read-only Auditor get a read-only view
  (codes arrive masked). These are render decisions only - the backend re-checks every call.
- `DELETE /api/v1/admin/discounts/{id}` answers `204` with no `ApiResponse` envelope, unlike the
  project's usual `200` + empty `data` DELETE convention. The shared `apiFetch` reports an envelope-less
  204 as an error, so `useArchiveDiscount` treats status 204 as success. Contract unchanged; a follow-up
  may align the endpoint with the 200 convention.
- The composer sends `discountCode` / `acceptedRewardRuleIds` only when set and never an amount; every
  status, line discount, offer and notice is rendered from the quote. A 429 `DISCOUNT_CODE_RATE_LIMITED`
  keeps the code visible so the student can remove it; `ORDER_TOTAL_ZERO` hides the payment methods.

Owner decisions 2026-10-21 (from the reviews): harden code lookup (status collapse, rate limit, 6+
chars); one use per student for code and capped rules only, plus a bounded gateway hold TTL; dedicated
`DISCOUNTS` permission area; re-validate a lapsed hold before retaking it; approve the settlement payout
effect (ADR-029 A1).

Deferred to follow-ups (review minors): free-month window counted from the order month vs the demo's
"from payment" helper text; refund view showing per-line net amounts; idempotent replay ignoring a changed
code (`sameSelection`); DB-level immutability triggers on version/snapshot tables; ArchUnit guard for
`*Unchecked` repository methods.
