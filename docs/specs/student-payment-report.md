# Student Payment Report — Spec & Contract (RPT-02)

Status: **Implemented and reviewed (2026-10-23)** — owner decisions in §7; acceptance evidence: `docs/qa/student-payment-report-acceptance.md`.
Plan: `docs/plans/reporting-module.md` · Catalog: `docs/specs/reporting-catalog.md` ·
Reuses RPT-01 (`docs/specs/teacher-income-summary-report.md`) ledger reads, run snapshot, Jasper engine, fonts.

## 1. Reference matrix (extracted once; agents use this, not the images)
Sources: `docs/references/klass-reporting-module/Student Payment Report.png` (screen), `student-payment-summary.pdf`
(Klass PDF export), Klass demo dialog notes in the catalog (observed 2026-10-22).

| Element | Source | Label |
|---|---|---|
| Title "Student Payment Report"; description "Find student payments made within a selected time frame or specific months" | screen | observed |
| Left filter panel / right preview; MONTHS / DATES toggle; DATES = calendar with month+year nav, single date ("Thu, Oct 8") | screen | observed |
| Course* ("one or more courses"), Payment Method dropdown | Klass dialog (catalog) | observed (top of panel, scrolled out of the screenshot) |
| "When multiple months or dates are selected, results display students who have paid for all selected periods" | Klass dialog | observed — **not adopted** (§7 D2) |
| CANCEL / SEARCH | screen | observed |
| Heading "PAYMENT HISTORY REPORT - 2026-10-08"; applied-course chip "Course: …" | screen | observed |
| "Group by student", "Group by course" switches; EXPORT PDF | screen | observed (combined behaviour not shown → §5 assumed) |
| Columns: # · Index no. · Student · Course · Reason (Month/Module) · Payment Method · Date · Amount (LKR) | screen | observed |
| Amount cell: course amount, "+ 100.00 fee" sub-line | screen | observed |
| Index no. with leading zeros (0084, 0223) | screen + PDF | observed |
| Methods shown: CARD, BANK_TRANSFER; Date "08 Oct 2026, 05:54 pm" | screen | observed |
| Summary: "Filtered results show 5 students paid their fees, totaling LKR 20,000.00"; "Total Bank Transfer payments LKR 12,000.00"; "Total Card payments LKR 8,000.00"; "Service fees collected on top of these amounts LKR 500.00" | screen | observed |
| PDF: dark header (title + institute), "Courses: … Generated: …", KPIs TOTAL AMOUNT / TOTAL STUDENTS / TOTAL PAYMENTS, service-fee line, table # · STUDENT · INDEX · COURSE · REASON · METHOD · DATE · AMOUNT · SERVICE FEE, footer | PDF | observed |
| Row order | screen/PDF | unverified (no stable order visible) → we sort by date |

The teacher payslip is **not** a template for this report (no commission/payout/signature sections).

## 2. Filters (server-validated)
`POST /api/v1/reports/student-payments/runs` body:
`{ courseIds?: [uuid] (≤ 50; empty/absent = all courses of the tenant), paymentMethod?: "GATEWAY"|"MANUAL_SLIP"|"MANUAL_RECEIPT",
   periodMode: "MONTHS"|"DATES", months?: ["YYYY-MM"] (1..36), startDate?, endDate? ("YYYY-MM-DD", start ≤ end, span ≤ 366 days) }`
- Only the active mode's fields are allowed (other mode's fields → 422), as RPT-01.
- DATES: a single day = `startDate == endDate`. UI offers a single date (observed) plus an optional end date.
  Interval = `[startDate 00:00, endDate+1 00:00)` in the tenant timezone.
- Course ids must be courses of the caller's tenant (unknown/foreign → identical 422, no existence leak).
- Payment-method filter = the RPT-01 channel enum (Klass Card / Bank Transfer / Cash ≈ GATEWAY / MANUAL_SLIP / MANUAL_RECEIPT).
- No teacher / student selectors (not observed).

## 3. Row grain and money
**Detail row = one (ledger entry, course allocation, order line)** of a `PAYMENT_CONFIRMED` or `REFUND` entry
(legacy line-less order: one row per (entry, course)). Built from RPT-01's `TeacherIncomeLedgerApi` enrichment
(ADR-029 + A2 allocation, `RevenueLineSplitter`). Separate receipts are never merged; a receipt paying Oct + Nov
for one course gives two rows; a 2-course receipt gives rows per course — the order total is never repeated.

| Field | Definition |
|---|---|
| `amount` (course payment) | the line's allocation (A2: unit − discount; net of discounts; negative on refund rows) |
| `serviceFee` | the line's facility-fee part (incl. PER_ORDER fee stored on line 1) |
| `postage` | the course's postage part, attached to the course's lowest-`line_no` row in that entry |
| Ancillary rule | per entry, `entry.amount − Σ all course allocations` (exact) is split over the snapshot parts (each line's `facility_fee_amount`, each course's `postage_amount`) pro rata, remainder to the largest part (ties: lowest line_no, postage last). Confirmed entries therefore show the exact snapshot values; refunds are pro rata; Σ row fees + Σ row postage = ancillary exactly. Never recomputed from current course/fee settings. |
| `reason` | `MONTHLY` → "YYYY-MM"; `MODULE`/`LESSON` → the immutable `student_order_line.label` (prefixed "Module: " / "Session: "); legacy order → "Course payment" |
| `method` (display) | GATEWAY → `CARD` ("Online (card)"); MANUAL_SLIP → `BANK_TRANSFER` ("Bank transfer (slip)"); MANUAL_RECEIPT → the receipt's own `payment_method`: `CASH` / `BANK_TRANSFER` / `CARD` with `manual = true` ("Manual receipt – cash" …). Never inferred from attachments. |
| `paymentDate` | Manual receipt confirmation: `manual_payment_receipt.received_on` (tenant-local **date**, no time; `dateOnly = true`) with `recordedAt` = ledger time as secondary detail. Others and all refunds: ledger `created_at` (tenant tz). |
| student | `student_order.student_id` → name + **index number** (§6) |

**DATES scope:** a confirmation row is in scope iff its `paymentDate` ∈ window (manual: `received_on` date in range;
others: `created_at` in the instant window). Refund rows: refund `created_at` in window. **MONTHS scope:** rows whose
line billing month ∈ selected set (MONTHLY period or LESSON billing month; refunds follow the line month) — "any selected
period" (D2). MODULE / month-less lessons / legacy orders → `reconciliation.excludedNotMonthAttributable`.
**Course filter** applies at allocation level (sibling-course rows of the same receipt are excluded, and so are their fees).

Included: GATEWAY confirmations, approved slips, ADR-030 manual receipts. Excluded: PENDING/REJECTED/failed (no ledger
entry), Free Cards (no financial rows), FREE and STAFF_GRANTED confirmations (non-cash → `reconciliation.excludedNonCash`),
zero-amount rows (100%-discount / reward lines; omitted, as RPT-01). Refunds/chargebacks: **shown as negative rows**
(method of the original payment, reason of the line, refund date), never hidden; totals are **net**.

### 3-R. Revisions after payment-ledger review (implemented; authoritative; supersede §3 where they differ)
1. **Pro rata whenever entry ≠ order amount.** A manual receipt with a staff adjustment records `received = expected −
   adjustment` as the payment/ledger amount while the order keeps `expected`; `RevenueAllocator` scales course parts by
   received/expected, and the ancillary residual (fees) is scaled the same way. Only confirmed entries with entry =
   order amount show the exact snapshot values. The adjustment is never absorbed into fees first (would change ADR-029).
   `reconciliation.byCurrency[].staffAdjustments {count, amount}` (Σ expected − received of in-scope receipts) explains
   gross vs cash. Invariant test: `order.amount = Σ line_total + Σ postage` for line orders.
2. **Row omission:** omit a row only when amount, serviceFee **and** postage are all zero — a fee-only or postage-only
   row (100%-discount line carrying a fee, zero-share course with postage) is kept so Σ row fees + postage = ancillary.
3. **MONTHS `excludedNotMonthAttributable`** is scoped to the fetched orders (orders with ≥ 1 line in a selected month):
   their month-less lines (MODULE, undated LESSON). Legacy/MODULE-only orders are not loaded in MONTHS mode (documented).
4. **DATES fetch:** confirmations are fetched by ledger `created_at` window **plus** manual-receipt confirmations whose
   `received_on` is in the date range (new narrow read; receipts may be backdated up to 365 days). A manual
   confirmation is in scope only by `received_on`. Consequence (documented): a past window's totals can change later
   when a backdated receipt is recorded — the report is "as of" `generatedAt`.
5. **Method:** GATEWAY is a catch-all for non-prefixed references (PayHere card, wallet, online banking, fake gateway) →
   display `ONLINE` ("Online (gateway)"), not CARD. MANUAL_SLIP → `BANK_TRANSFER` ("Bank transfer (slip)").
   MANUAL_RECEIPT → receipt's `CASH`/`BANK_TRANSFER`/`CARD` (manual). Null/unresolvable → `UNKNOWN` (kept in byMethod).
   Refund/chargeback rows inherit the original payment's method (they carry its `paymentId`).
6. **Course filter + PER_ORDER fee:** the PER_ORDER facility fee is stored on line 1, so it is reported under line 1's
   course; filtering to another course of the same order excludes it (documented).
7. **Summary pins:** `receiptCount` counts PAYMENT_CONFIRMED entries with ≥ 1 row in scope after course/zero filters;
   byMethod `receiptCount` values sum to it; `grossCoursePayments` = Σ positive course-payment rows (fees excluded);
   `receivedTotal` = cash actually received after adjustments (course + fees + postage, net of refunds).
8. **Filtered staff adjustments:** when a manual receipt is only partly retained by a course/month filter, its
   `staffAdjustments.amount` is allocated by the same immutable received-value ratio; the report never claims the full
   receipt adjustment for a partial result. `excludedNotMonthAttributable.amount` is explicitly course-payment-only;
   related fees/postage are not silently combined with it.
9. **DATES workload bound:** a manual receipt already present in the ledger date-window result consumes the ledger-entry
   bound once, not again through the `received_on` backfill path.

## 4. Summary (per currency section, over the complete filtered result)
`studentsPaid` = distinct students with ≥ 1 positive (confirmation) row · `receiptCount` = distinct PAYMENT_CONFIRMED
entries · `refundCount` · `rowCount` · `coursePaymentTotal` (net) · `byMethod[{method, manual, total, receiptCount}]`
(Σ = coursePaymentTotal) · `serviceFeeTotal` · `postageTotal` · `receivedTotal` = course + service fees + postage
("Received (net) total"). `reconciliation` per currency: `grossCoursePayments`, `refunds`, `excludedNonCash{count,amount}`,
`excludedNotMonthAttributable{count,amount}`; notes. Currencies never summed.

## 5. Grouping (presentation only — totals and counts never change)
`groupBy` = `NONE` | `STUDENT` | `COURSE` | `STUDENT_COURSE` (both switches: student → course). Keys are ids
(`studentId`, `courseId`), never names; sort groups by display name then id. Each group has subtotals
(coursePayment, serviceFee, postage, receiptCount); nested course subtotals inside a student. Row order within the
finest group: paymentDate, then entry id, then line_no. Same rules in preview and PDF.

## 6. Student index number (owner decision D1)
New `student_profile.index_number` (string, `UNIQUE (tenant_id, index_number)`), auto-assigned per tenant: existing
students backfilled in (created_at, id) order; every new student gets the next number at creation; zero-padded to 4
digits ("0001"), wider when it grows ("10000"). Stored and transported as a **string** (leading zeros preserved in JSON,
UI and PDF). Read-only in this task (no edit UI).

## 7. Owner decisions (2026-10-22)
- **D1** Index no.: auto-assign per tenant (§6).
- **D2** Multiple periods: **any selected period** (diverges from Klass "paid for all selected periods").
- **D3** Date: manual receipts use `received_on` in this report; Teacher Income unchanged (documented difference).
- Course selection: optional multi-select (all courses when empty) — per task instruction; Klass marks it required.

## 8. API (base `/api/v1/reports/student-payments`, FINANCE_EXPENSES/VIEW on every endpoint, `{data}` envelope, no-store)
- `GET /options` → `{ courses:[{id,name}], paymentMethods:[…] }`
- `POST /runs` → 201 `{ runId, expiresAt, generatedAt, timezone, institute{name}, filters{courses[{id,name}], paymentMethod,
  periodMode, months, startDate, endDate, periodLabel}, sections:[{currency, summary{…§4}}], reconciliation{byCurrency[], notes[]},
  rowCount }` (rows are paged separately).
- `GET /runs/{runId}` → same.
- `GET /runs/{runId}/rows?groupBy=&page=&size=` (size ≤ 200) → `{ page, size, totalRows, rows:[{ rowNo, currency, entryId,
  receiptId(paymentId), refund, studentId, studentName, indexNumber, courseId, courseName, reason, scope, method, manual,
  paymentDate, dateOnly, recordedAt, amount, serviceFee, postage }], groups:[{ level:"STUDENT"|"COURSE", key, parentKey,
  label, currency, coursePayment, serviceFee, postage, receiptCount, rowCount }] }` — `groups` = subtotals (computed over
  the whole run) for every group that has a row on this page. Stable order per §5.
- `GET /runs/{runId}/pdf?groupBy=` → landscape A4 PDF of **all** rows of the stored run; report title, institute,
  applied courses/period/method/grouping, generated time and timezone repeat on every page, with repeated table headings.
Errors as RPT-01 (422 `REPORT_VALIDATION` / `REPORT_TOO_LARGE`, 404 run, 429, 503). Limits: ≤ 50,000 ledger entries
(pre-count), ≤ 20,000 rows per run, ≤ 10,000 rows per PDF, run TTL 15 min bound to (tenant, user).
