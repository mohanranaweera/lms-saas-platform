# Teacher Income Summary Report — Spec & Contract (RPT-01)

Status: **Contract agreed** — metric dictionary reviewed by payment-ledger-specialist; owner decisions
recorded 2026-10-22 (facility fees + postage institute-only → ADR-029 Addendum A2; commission from the
teacher share rate). §2-R supersedes any conflicting row in the §2 table.
Plan: `docs/plans/reporting-module.md`. Catalog: `docs/specs/reporting-catalog.md`.
References: `docs/references/klass-reporting-module/` (screenshot, summary PDF, payslip PDF).

## 1. Reference field/layout matrix (extracted once; agents reuse this, not the images)

### 1.1 Filter dialog (screenshot — observed)
| Element | Observation |
|---|---|
| Title / description | "Teacher Income Summary Report" / "View earnings of teachers for a selected period or specific months" |
| Teacher(s) * | required, multi-select, removable chips |
| Payment Method | single dropdown, empty = all. Options observed in Klass demo 2026-10-22: Cash, Bank Transfer, Card (mapping: `reporting-catalog.md`) |
| Period Selection | toggle MONTHS / DATES |
| Months picker | year nav (‹ 2024 ›), 12 month pills, "N months selected" header, selections across years |
| Dates mode fields | not visible → **unverified** (we use start + end date, inclusive) |
| Actions | CANCEL, SEARCH |
| Empty state | box icon + "No payments to show" |
| Layout | left filter column, right result pane (screen-first) |

### 1.2 Summary PDF (observed)
Dark header band: title + institute name. Applied-filter line: Date Range, Months, Teachers, Generated.
4 KPI cards (coloured left border): TOTAL REVENUE, TEACHER PAYMENTS, INSTITUTE COMMISSION, TOTAL TEACHERS.
Line: "Service fees collected (institute only, not in the figures above): Rs. X".
Per teacher card: name; Total Income · Payouts (red) · Payins (green) · Balance (blue);
table COURSE | NO. PAYMENTS | TOTAL (LKR) | TEACHER (LKR) | COMMISSION (LKR). Footer "Report generated on … | institute".
Amounts "Rs. 1234.00" (no grouping) in reference; we use grouped `1,234.00` (payslip convention).
Reference inconsistency: Teachers filter names one teacher but two teacher sections appear — **not reproduced**.

### 1.3 Payslip PDF (observed)
Narrow receipt layout. Institute name; "PAYSLIP"; "Amounts in LKR"; TEACHER; PERIOD;
COURSE INCOME rows (course name wraps, right-aligned teacher amount, "N payments" sub-line, sorted by course name);
"Total income"; signature line "Received by"; dashed rule; timestamp `dd-MM-yyyy HH:mm:ss`.

## 2. Metric dictionary (financial contract)

Grain: **allocation** = (ledger entry, order line) share. Built only from authoritative, immutable
sources: `ledger_entry` (PAYMENT_CONFIRMED / REFUND, append-only), `student_order_line` snapshots,
`student_order_course_charge` (postage), ADR-029 `RevenueAllocator` (course grain), payment row
(method by `gateway_reference` prefix, currency), `teacher_revenue_share_rate` (append-only,
effective-dated), `teacher_settlement` / `teacher_settlement_item` (settled attribution, rate snapshot, PAID status).

| Metric | Definition | Gross/Net |
|---|---|---|
| Revenue (course row `Total`) | Σ course-share allocations of in-scope ledger entries for (teacher, course, currency). Confirmed entries positive, REFUND entries (refunds + chargebacks) negative, allocated pro rata per ADR-029 decision 3. Net of order-line discounts (line_total). | **Net** of discounts and in-scope refunds |
| Teacher income (`Teacher`) | Per (teacher, course, rate) bucket: `round(bucketRevenue × sharePercent / 100, 2, HALF_UP)`; row = Σ buckets. Rate: if the allocation is settled → the settlement's snapshotted `share_percent`; else the teacher's `teacher_revenue_share_rate` effective on the ledger entry's tenant-local date. **Never** the current rate. | Net |
| Institute commission | `Revenue − Teacher income` per bucket (so they always reconcile exactly). | Net |
| "Teacher Payments" KPI | = Σ Teacher income — **earned share, not cash paid out**. UI/PDF label: "Teacher share (earned)". | Net |
| Service/facility fees | All facility fees + postage = Σ in-scope `entry.amount − Σ course allocations` (ADR-029 A2, §2-R.2). Institute only; not in Revenue/Teacher/Commission. | **Net** (signed; refunds reduce it pro rata) |
| Payment count (`No. Payments`) | Count of **distinct PAYMENT_CONFIRMED ledger entries** (one per confirmed payment) with a non-zero allocation in the row. REFUND entries are never counted; retries (PENDING/REJECTED payments) have no ledger entry. Teacher/KPI counts = distinct entries across their rows (a 2-course order counts once per course row but once in the teacher/KPI total). | — |
| Payouts | Stored figures only: Σ `share_amount` of the teacher's `teacher_settlement` rows with `status = PAID` (REGULAR and PAID ADJUSTMENT, never recomputed) whose tenant-local `paid_at` falls in the window, in the settlement currency. Payment-method filter does not apply. (Superseded draft: per-allocation recomputation — rejected by ledger review, see §2-R.5.) | — |
| Payins | **No source exists** (no teacher→institute money record). Returned as `null` and shown as "Not tracked" — never faked as 0. | — |
| Balance | `Teacher income − Payouts` = earned share in this report's scope **not yet in a PAID settlement**. Period movement, **not** an opening/closing account balance. | — |

Inclusion: payment methods GATEWAY, MANUAL_SLIP (approved slips), MANUAL_RECEIPT (ADR-030 staff
receipt). Excluded: FREE and STAFF_GRANTED confirmations (no cash), PENDING/REJECTED/failed payments
(no ledger entry), Free Cards (create no financial rows, V94/F7), zero allocations (100% discount reward lines).

Teacher attribution: settled allocation → the settlement's `teacher_id` (historical, immutable);
unsettled → the course's **current primary teacher** (`course.teacher_id`) — the same rule the next
settlement run will apply. Data limitation: no attribution snapshot exists on payments, so
reassigning a course's primary teacher moves its *unsettled* revenue. Co-teachers in
`course_teacher_assignment` have no stored share split → **no co-teacher attribution, no equal split**
(100% to primary, matching settlement).

Rows with no effective rate: Revenue counted; Teacher/Commission `null`, row flagged
`rateMissing`, and the summary shows `unratedRevenue` so Revenue ≠ Teacher + Commission is explained.

Currency: every amount grouped by `payment.currency`; never summed across currencies.

### 2-R. Revisions after ledger review + owner decisions (authoritative)
1. **Course share (ADR-029 A2).** A course's share of an order = Σ over its lines of
   `line_total − line.facility_fee_amount` (= unit − discount). Facility fees (PER_LINE, PER_COURSE and the
   PER_ORDER fee the composer stores on line 1) and postage are **institute-only** — for this report *and*
   for settlement runs created after A2. Legacy line-less orders keep one allocation of the full amount.
   Historical settlement items are never recomputed.
2. **Service fees** = Σ over in-scope entries of `entry.amount − Σ(all course allocations of the entry)`,
   **signed** (refunds reduce it pro rata, so it reconciles with the ledger). It is facility fees + postage.
   MONTHS mode: an entry's fees are included once if ≥ 1 of its lines is in scope. Not in Revenue/Teacher/Commission.
3. **Line grain.** Lines are obtained by splitting the allocator's own course allocation (never recomputed
   from the order): pro rata by line share, remainder to the largest line (ties lowest line_no); a course
   with share 0 gets no lines. Per-course sums therefore equal `RevenueAllocator` exactly.
4. **Non-cash exclusions.** FREE (0) and STAFF_GRANTED (carry the real course price — complimentary, no
   cash) are excluded from Revenue and counts and reported as `reconciliation.excludedNonCash {count, amount}`.
   Known gap (not changed here, change-controlled): existing settlement does not filter by method and
   therefore includes STAFF_GRANTED amounts.
5. **Payouts = stored figures only.** Σ effective stored amounts of the teacher's `teacher_settlement`
   rows with `status = PAID` (REGULAR `share_amount` and PAID ADJUSTMENT `share_amount`, never recomputed)
   whose tenant-local `paid_at` falls in the window (DATES: date range; MONTHS: paid month ∈ selected set),
   in the settlement's currency. The payment-method filter does not apply to payouts (stated on screen/PDF).
6. **Balance** = Teacher income − Payouts (+ Payins when a source exists): a **period movement**, not an
   account balance. Payins = `null` ("Not tracked").
7. **Settled basis note.** For in-scope allocations already settled, `reconciliation.settledBasisDifference`
   = Σ stored `teacher_settlement_item.amount` − Σ report allocation (non-zero for items settled before
   ADR-029 postage/A2 facility-fee exclusion). Shown as a note when ≠ 0.
8. **Currency** comes from the entry's payment (`payment.currency`) via a narrow new read API.
9. **Attribution** null course teacher → row under "Unassigned" only if no teacher filter excludes it
   (teacher filter is mandatory, so unassigned allocations are never shown; counted in reconciliation).
10. **Rate hazard.** Rates may be added retroactively; unsettled figures are therefore "as of" `generatedAt`.
11. **DATES mode refunds** can fall in range while the original payment does not → negative row, count 0.
12. **LESSON month** uses the lesson's current billing month (editable) — documented limitation.
13. `course_commission_rate` (O-3) is **not** used.

## 3. Period semantics
- `periodMode = MONTHS`: billing-month allocation. An allocation is in scope iff its order line
  is `MONTHLY` with `(period_year, period_month)` in the selected set, or a `LESSON` line whose lesson
  has `(billing_year, billing_month)` in the set. Refunds follow the original line's month. Legacy
  (line-less) orders, MODULE lines and month-less lessons are **not month-attributable** and are
  reported in `reconciliation.excludedNotMonthAttributable` (count + amount), never guessed (never from
  course names).
- `periodMode = DATES`: receipt date. Entry in scope iff `ledger_entry.created_at ∈
  [startDate 00:00, endDate+1 00:00)` in the tenant timezone (`GENERAL.default_timezone`). Refunds by refund date.
- Only the active mode is read; the other mode's fields are rejected if present.
- Limits: ≤ 36 months selected; date span ≤ 366 days; ≤ 50 teachers.

## 4. API contract (draft, agreed before parallel work)
Base `/api/v1/reports/teacher-income`. Auth: `FINANCE_EXPENSES`/`VIEW` (view + export — the
permission model has no separate export action). Tenant from `TenantContext` only.

- `GET  /options` → `{ teachers:[{id,name}], paymentMethods:["GATEWAY","MANUAL_SLIP","MANUAL_RECEIPT"] }`
- `POST /runs` body `{ teacherIds:[uuid], paymentMethod?:enum, periodMode:"MONTHS"|"DATES", months?:["YYYY-MM"], startDate?, endDate? }`
  → `201 TeacherIncomeReport` (below) incl. `runId`, `expiresAt`. Read-only computation; the run is a
  short-lived (15 min) snapshot bound to (tenant, user).
- `GET  /runs/{runId}` → same dataset (404 if absent/expired/other tenant or user).
- `GET  /runs/{runId}/pdf` → `application/pdf` summary (attachment).
- `GET  /runs/{runId}/teachers/{teacherId}/payslip.pdf` → payslip; teacher must be in the run.

`TeacherIncomeReport`: `{ runId, expiresAt, generatedAt, timezone, institute:{name},
filters:{ teachers:[{id,name}], paymentMethod, periodMode, months, startDate, endDate, periodLabel },
sections:[ { currency, totals:{revenue, teacherIncome, commission, serviceFees, payouts, payins:null,
balance, paymentCount, teacherCount, unratedRevenue}, teachers:[ { teacherId, teacherName,
totals:{…same per teacher…}, courses:[ { courseId, courseName, paymentCount, revenue, teacherIncome,
commission, rateMissing } ] } ] } ], reconciliation:{ grossRevenue, refunds,
excludedNotMonthAttributable:{count, amount}, notes:[] } }`. Amounts are decimal strings (2 dp).
Sorting: sections by currency; teachers by name, id; courses by name, id.

### 4.1 As implemented (authoritative for the frontend)
1. JSON responses use the standard `ApiResponse` envelope `{ data: … }`.
2. `reconciliation = { byCurrency: [{ currency, grossRevenue, refunds, excludedNonCash{count,amount},
   excludedNotMonthAttributable{count,amount}, settledBasisDifference, unassignedRevenue }], notes: [string] }`.
3. Teacher-level `totals` has the section-totals shape with `teacherCount = 1`.
4. Course row `teacherIncome`/`commission` are `null` only when no part of the row has a rate; `rateMissing` = any part unrated.
5. Request: `paymentMethod`, `periodMode`, `months` (`["YYYY-MM"]`), `startDate`, `endDate` (`YYYY-MM-DD`) are strings.
6. Inactive-mode filter values come back `null`; `periodLabel` e.g. "01 Mar 2025 to 31 Mar 2025" / "Dec 2024, Jan 2025".
7. Errors: 422 `REPORT_VALIDATION` (field errors e.g. `teacherIds[0]`, `months`, `endDate`); 422 `REPORT_TOO_LARGE`
   (> 5,000 course rows on PDF); 403 without FINANCE_EXPENSES/VIEW (checked before any lookup); 404 for a missing,
   expired, other-user or other-tenant run and for a payslip teacher not in the run; 503 if the run store is unavailable.
8. A teacher with payouts but no income appears with `courses: []`.
9. Service fees: counted once per section; on teacher totals each entry's fee appears under every selected teacher it
   touches (noted in the report), so teacher service fees are not additive.

`reconciliation` additionally carries `excludedNonCash {count, amount}`, `settledBasisDifference`,
`unassignedRevenue`, and per-section `serviceFees` is signed.

## 5. Owner decisions (2026-10-22)
- **O-1 resolved:** facility fees + postage are institute-only service fees, for settlement too → ADR-029 Addendum A2.
- **Commission source:** teacher share rate (what settlement pays). `course_commission_rate` (O-3) untouched.
