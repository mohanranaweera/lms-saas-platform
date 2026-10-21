# Klass Manual Payment & Free Card — plan, contracts and status

Branch: `feat/klass-manual-payment-free-card`. Spec: `docs/specs/klass-manual-payment-free-card.md`.
QA: `docs/qa/klass-manual-payment-free-card-acceptance.md`. Approval record: `docs/adr/ADR-030-manual-payment-receipts-and-free-cards.md`.
Migrations reserved: **V93** (manual payment receipt), **V94** (free card). Acceptance IDs **A–R** are the brief's (§11).

## 1. Reference evidence (screenshots read once + read-only live inspection)

Screenshots: `docs/references/Klass-manual-payment-free-card/` — `Manual Payment And Free Class Grant Page.png` (S1),
`Manual Payment And Free Class Grant Page - Add Payments.png` (S2), `Manual Payment And Free Class Grant Page - Issue a fee Card.png` (S3).
Live demo (read-only, nothing submitted, no ids/credentials stored): dropdown options only.

| Area | Observed (source) |
|---|---|
| Page | Payments section, tabs COMPLETED · PENDING · REJECTED · FREE CARDS · DISCOUNTS · SEARCH BY REF (S1). Selectors **Course\*** and **Student\*** (`<no> - <name>`); route takes `studentId` + `courseId` query params (live URL). |
| Student card | Initials avatar, profile link icon, `# <student no>`, phone, **ISSUE A FREE CARD** button (S1). |
| History | "`<First>`'s Payments" + course chip; **+ ADD PAYMENT**; rows per billing month "2026 August" with green "✓ LKR 3,000.00", print icon, expand chevron (S1); row action "Undo this payment" (live a11y tree). |
| Side panels | "Registration Fee — MARK AS PAID 500.00", "Course Enrollment Fee — MARK AS PAID 500.00", "Paid resources of `<course>`" (empty: "No paid resources available") (S1). |
| Add Payment | **Month\*** (chip `2026-08`), **Course(s)\*** multi (hint "Select multiple courses to pay at once"), **Discount (LKR)** + refresh, chips 75% / 50% / 25% Discount, **Reference Number**, **Payment Method** (live: Cash, Bank Transfer, Card), "Net Amount: LKR 3,000.00" + refresh, CANCEL / ADD PAYMENT (S2). |
| Issue Free Card | "Student: John Doe", **Course\*** ("python - LKR 3,000.00"), **From** / **To** ("Leave empty if not necessary"), **Type\*** (live: Full, Half), **Temporary Credit** checkbox, CANCEL / ISSUE FREE CARD (S3). |
| Free Cards tab | Filters Course\*, Module; columns Student, Mobile, Email, No. of free cards, Actions (live). |

## 2. Gap matrix (existing → this change)

| Need | Existing | Gap / action |
|---|---|---|
| Staff selects student + course | Student detail page (`/tenant-admin/students/[studentId]`, profile id), ledger-derived payments tab | New page `/tenant-admin/payments/students?studentId=&courseId=` + entry links |
| Price months/fees/postage/discounts | `CheckoutComposerService` (student caller only) | Add a *subject student* seam: staff variants of offers/quote/order for an explicit, server-validated student |
| Staff-confirmed receipt | Legacy `ManualEnrollmentService` (course-wide, `STAFF_GRANTED-`); slip approval (`SLIP-`) | New `manual_payment_receipt` + `MANUAL-` payment, line-order entitlements via `EntitlementGrantApi` |
| Complimentary access | none | New `free_card` (enrollment-management); evaluator adds its MONTHLY keys to the same coverage set |
| Reversal | `POST /api/v1/payments/{id}/refunds` + refunds page | Reuse (no new reversal path); "Undo" maps to refund |
| Permissions | `PermissionCheckServiceImpl` matrix | New `DomainArea.FREE_CARDS`; manual receipt = `PAYMENTS_SLIPS/APPROVE` |
| Registration / enrollment fee "Mark as paid" | `course.enrollment_fee` stored, never charged; no registration fee concept | **Not built** (no offer engine; would invent rules) — documented gap |

## 3. Decision log

| ID | Decision |
|---|---|
| M1 | Manual receipt = one composer line order for the selected student (multi-course, MONTHLY/MODULE/LESSON items, facility fee, postage, automatic discount rules — no codes, no free-course rewards) + one `Payment` (`gateway_reference = "MANUAL-" + paymentId`, CONFIRMED) + `PAYMENT_CONFIRMED` ledger entry + `manual_payment_receipt` + entitlements, **one transaction**. |
| M2 | Permission `PAYMENTS_SLIPS/APPROVE` (same grant that confirms slips; Tenant Admin, Finance Staff). No maker/checker exists for slip approval, so none is invented. Students: 403 (staff endpoints check permission; STUDENT role has none). |
| M3 | Klass "Discount (LKR)" = explicit **staff adjustment** on the receipt: `0 ≤ adjustment < expected`, reason required when > 0, audited. Not a discount rule, not on order lines. `received = expected − adjustment` is the cash recorded on the payment and ledger; ADR-029 allocation splits the cash pro rata per course. Order lines/charges keep the full quoted snapshot. Net 0 is refused ("issue a Free Card instead"). Chips (75/50/25 %) are a UI convenience over the quoted course-fee subtotal. |
| M4 | No partial payment / overpayment: client sends `expectedReceivedAmount`; server recomputes and requires equality (409 `AMOUNT_MISMATCH`). |
| M5 | Pending student slip / pending online payment for an item → 422 `ITEM_PENDING` (existing composer rule); staff approve the slip in Payment Slips. Never auto-approves a slip. |
| M6 | `receivedOn` (DATE, tenant timezone, required, not in the future) is separate from `recorded_at` (server instant). Methods: `CASH`, `BANK_TRANSFER`, `CARD` (Klass options). Reference ≤ 100 chars; notes ≤ 1000. |
| M7 | Idempotency: required client `idempotencyKey` (one per open dialog), `UNIQUE (tenant_id, idempotency_key)` on the receipt, advisory lock; replay returns the same receipt (200); same key for another student → 409 `IDEMPOTENCY_KEY_REUSED`. Concurrent staff for the same month serialize on coverage locks; the loser gets `ITEM_ALREADY_PAID`. |
| M8 | Correction = existing refund (`POST /api/v1/payments/{id}/refunds`, full or partial, reason). "Undo this payment" maps to it. Per open decision O-2 a staff refund does **not** revoke access (unchanged policy). No edit of receipt amounts. |
| F1 | Free Card lives in enrollment-management (`free_card`). Permission `DomainArea.FREE_CARDS`: Tenant Admin V/C-E/D (issue = CREATE_EDIT, revoke = DELETE, never a row delete); Finance Staff, Student Support, Read-only Auditor V. Recording payments does not imply issuing cards (L). |
| F2 | Type **FULL** only. **HALF** is observed but would be a 50 % price rule in the composer (pricing/discount change) → refused 422 `FREE_CARD_TYPE_UNSUPPORTED`, shown disabled. **Temporary Credit** semantics are not visible anywhere → not built. |
| F3 | Validity **required** (deviation from Klass "Leave empty"): `validFrom`/`validTo` dates, `validTo ≥ validFrom`, `validTo ≥ today` (tenant tz), span ≤ 24 covered months. Never lifetime. Canonical `effective_from` = start of `validFrom`, `effective_until` = start of the day after `validTo`, in the tenant timezone snapshotted on the row. |
| F4 | Coverage = `MONTHLY:<courseId>:<yyyy-mm>` for every calendar month touched by [validFrom, validTo], usable only while `effective_from ≤ now < effective_until` and not revoked. FULL covers COVERED_BY_CLASS_FEES lessons of those months only (not PAID add-on lessons, not MODULE purchases, not linked source courses — keys carry the card's own course id). Only MONTHLY-priced courses (else 409 `FREE_CARD_UNSUPPORTED_COURSE`). |
| F5 | Overlap: a non-revoked card of the same student + course whose date range intersects → 409 `FREE_CARD_OVERLAP` (advisory lock per tenant/student/course). Paid entitlements are independent rows; revoking/expiring a card never touches them. |
| F6 | Not a participant → standard participation insert (`STAFF_GRANT`) inside the same transaction if the course is available and registration enabled (else 409 `REGISTRATION_CLOSED` / 404), audited separately (`course.participation_created`). |
| F7 | Financial: no order, payment, ledger, settlement or postage row. `nominal_monthly_amount` (course monthly price snapshot) and `nominal_value` (= monthly × months) are informational, never revenue. Outstanding balances are untouched. |
| F8 | Revocation: reason required; sets `revoked_at/by/reason` once; repeat → 409 `FREE_CARD_ALREADY_REVOKED`. Access re-evaluates on the next request (evaluator has no cache); short-lived player/playback tokens are re-checked at issue; already-downloaded content cannot be recalled. |
| R1 | (security review) Staff composer variants and `grantForStaffRecordedPayment` re-check `PAYMENTS_SLIPS` permission themselves (defence in depth). `receivedOn` ≥ today − 365 days. `validFrom` ≥ first day of the current month (tenant tz) — no back-dated cards unlocking past months. Receipt replay with a different cart/amounts → 409 `IDEMPOTENCY_KEY_REUSED`. Suspended students are not refused (recording arrears is legitimate). |
| R2 | (payment-ledger review) A staff adjustment is refused when postage is selected (422 `ADJUSTMENT_WITH_POSTAGE_UNSUPPORTED`): ADR-029 splits the cash pro rata over course shares + postage, so an adjustment meant for course fees would under-recover postage. **Open owner decision O-MP1**: allow it with postage kept whole (allocator change, ledger rule). A grant that skips any line rolls the receipt back (409). `grantForStaffRecordedPayment` accepts only `MANUAL-` payments. Course summary buckets in the ledger dashboard show order value, not cash, for adjusted manual orders (documented). `collected_by` stays NULL, as for slips; Phase 1/2 settlement must treat `MANUAL-` cash as tenant-collected (open note O-MP2). |
| F9 | Student-facing contracts unchanged: free-card coverage appears as `payment: COVERED` on lessons; composer offers stay payment-based (a student may still pay for a free-card month — payment gives its own lasting coverage). |

## 4. API contract (tenant from auth context; `{studentId}` = student_profile id, resolved server-side; money = decimal strings)

payment-management — `ManualPaymentController`, all `PAYMENTS_SLIPS/VIEW` unless noted:
- `GET /api/v1/staff/students/{studentId}/payment-context` → `{student:{studentId,name,email,mobile}, timezone, courses:[{courseId,title,pricingModel,monthlyAmount,currency,postageFee}]}` (courses = participations, non-DRAFT, title order).
- `GET /api/v1/staff/students/{studentId}/payment-offers?courseId=&scope=MONTHLY|MODULE|LESSON&year=` → existing `CheckoutOffers` for that student.
- `GET /api/v1/staff/students/{studentId}/payment-history?courseId=` → `[{orderId, createdAt, status:"RECEIVED"|"PENDING_SLIP"|"PENDING_ONLINE"|"REJECTED", source:"MANUAL"|"SLIP"|"GATEWAY"|"OTHER", currency, orderTotal, receivedAmount, staffAdjustmentAmount, refundedAmount, paymentId, slipId, receipt:{receiptId,method,referenceNumber,receivedOn,notes,adjustmentReason,recordedBy,recordedByName,recordedAt}|null, lines:[{lineNo,courseId,courseTitle,scope,label,year,month,lineTotal}]}]` newest first; only orders with ≥1 line for `courseId`.
- `POST /api/v1/staff/students/{studentId}/manual-payments/quote` (`APPROVE`) body `{items:[{courseId,scope,year,month,moduleId,lessonId}], postageCourseIds}` → `CheckoutQuote`.
- `POST /api/v1/staff/students/{studentId}/manual-payments` (`APPROVE`) body `{items, postageCourseIds, quoteToken, staffAdjustmentAmount, adjustmentReason, expectedReceivedAmount, paymentMethod, receivedOn, referenceNumber, notes, idempotencyKey}` → 201 / 200 replay `{receiptId, orderId, paymentId, currency, expectedAmount, staffAdjustmentAmount, receivedAmount, paymentMethod, referenceNumber, receivedOn, notes, adjustmentReason, recordedBy, recordedAt, lines, replay}`. Errors: 403; 404 student/course; 422 `ITEM_NOT_ELIGIBLE|ITEM_ALREADY_PAID|ITEM_PENDING|EMPTY_SELECTION|QUOTE_REQUIRED|VALIDATION_ERROR|ADJUSTMENT_REASON_REQUIRED|ADJUSTMENT_TOO_LARGE`; 409 `QUOTE_CHANGED` (fresh quote) `|AMOUNT_MISMATCH|IDEMPOTENCY_KEY_REUSED|ITEM_ALREADY_PAID`.

enrollment-management — `FreeCardController`:
- `GET /api/v1/free-cards?studentId=&courseId=&status=&page=&size=` (`FREE_CARDS/VIEW`) → page of `FreeCardResponse`.
- `POST /api/v1/free-cards` (`FREE_CARDS/CREATE_EDIT`) `{studentId, courseId, cardType, validFrom, validTo, reason, idempotencyKey}` → 201 / 200 replay. Errors 404; 422 `FREE_CARD_TYPE_UNSUPPORTED|VALIDITY_INVALID|VALIDATION_ERROR`; 409 `FREE_CARD_UNSUPPORTED_COURSE|FREE_CARD_OVERLAP|REGISTRATION_CLOSED|IDEMPOTENCY_KEY_REUSED`.
- `POST /api/v1/free-cards/{id}/revoke` (`FREE_CARDS/DELETE`) `{reason}` → `FreeCardResponse`; 404 cross-tenant; 409 `FREE_CARD_ALREADY_REVOKED`.
- `FreeCardResponse = {id, studentId, studentName, courseId, courseTitle, cardType, validFrom, validTo, timezone, effectiveFrom, effectiveUntil, coveredMonths:["2026-08"], status:"SCHEDULED"|"ACTIVE"|"EXPIRED"|"REVOKED", reason, currency, nominalMonthlyAmount, nominalValue, issuedBy, issuedByName, issuedAt, revokedAt, revokedBy, revokedByName, revokedReason, enrollmentCreated, replay}`.

## 5. Schema contract (database-architect)

**V93 `manual_payment_receipt`** — `id`, `tenant_id` FK tenant, `student_id`, `order_id`, `payment_id`, `payment_method` CHECK (CASH, BANK_TRANSFER, CARD), `received_on DATE NOT NULL`, `reference_number VARCHAR(100)`, `notes VARCHAR(1000)`, `currency CHAR(3)`, `expected_amount NUMERIC(12,2) > 0`, `staff_adjustment_amount NUMERIC(12,2) NOT NULL DEFAULT 0 ≥ 0`, `adjustment_reason TEXT` (non-blank iff adjustment > 0), `received_amount NUMERIC(12,2) > 0` with CHECK `received = expected − adjustment`, `recorded_by UUID NOT NULL` (FK tenant_user), `recorded_at TIMESTAMPTZ NOT NULL`, `idempotency_key UUID NOT NULL`. Keys: `UNIQUE (tenant_id, id)`, `UNIQUE (tenant_id, payment_id)`, `UNIQUE (tenant_id, idempotency_key)`; FKs `(tenant_id, order_id, student_id)` → `student_order (tenant_id, id, student_id)`, `(tenant_id, payment_id)` → payment, `(tenant_id, student_id)` → tenant_user. Index `(tenant_id, student_id, recorded_at)`. Immutable (no update/delete).

**V94 `free_card`** — `id`, `tenant_id`, `student_id` FK tenant_user, `course_id` FK course, `card_type` CHECK ('FULL') (HALF reserved, not allowed), `valid_from DATE`, `valid_to DATE` CHECK ≥ valid_from, `timezone VARCHAR(64)`, `effective_from TIMESTAMPTZ`, `effective_until TIMESTAMPTZ` CHECK > effective_from, `reason TEXT` non-blank, `currency CHAR(3)`, `nominal_monthly_amount NUMERIC(12,2) ≥ 0` nullable, `nominal_value NUMERIC(12,2) ≥ 0` nullable, `issued_by` FK tenant_user, `issued_at`, `idempotency_key UUID NOT NULL`, `enrollment_created BOOLEAN NOT NULL`, `revoked_at`, `revoked_by` (FK tenant_user), `revoked_reason` (all-or-none, reason non-blank). Keys `UNIQUE (tenant_id, id)`, `UNIQUE (tenant_id, idempotency_key)`; index `(tenant_id, student_id, course_id)`, `(tenant_id, course_id)`. No delete.

## 6. File ownership

| Owner | Paths |
|---|---|
| database-architect | `backend/src/main/resources/db/migration/V93__*.sql`, `V94__*.sql` |
| backend-springboot-engineer | `backend/src/main/java/**`, unit/MockMvc tests outside `com/lms/klass` |
| qa-test-engineer | `backend/src/test/java/com/lms/klass/manualpayment/**` (Playwright spec moved to the frontend owner) |
| frontend-nextjs-engineer | `frontend/src/app/(tenant-admin)/tenant-admin/payments/students/**`, `.../payments/free-cards/**`, `frontend/src/components/payments/staff/**`, `frontend/src/lib/api/staff-payments.ts`, `frontend/src/lib/api/free-cards.ts`, nav + permissions + student payments-tab link |
| payment-ledger-specialist, security-reviewer | read-only review |
| parent | `docs/**`, ADR-030 |

## 7. Task table

| Task | Owner | Status | Evidence |
|---|---|---|---|
| T1 Discovery, contracts, ADR | parent | Done | this file, ADR-030 |
| T2 V93/V94 | database-architect | Done | FlywayMigrationIntegrationTest green; append-only/lifecycle triggers added |
| T3 Backend | backend-springboot-engineer | Done (+ review fix pass R1/R2) | 309 unit tests, 39 composer/access ITs |
| T4 Integration tests (PostgreSQL) | qa-test-engineer | Done | 20 ITs, A–P, R pass, no defects |
| T5 Frontend + Playwright | frontend-nextjs-engineer | Done | lint/tsc clean, 16 + 46 Playwright |
| T6 Payment/ledger + security review | reviewers | Done | no Critical; findings resolved per R1/R2 |
| T7 Docs, QA evidence | parent | Done | spec, QA acceptance doc, ADR-030 |
