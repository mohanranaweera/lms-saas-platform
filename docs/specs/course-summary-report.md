# Course Summary Report — Contract (RPT-05)

Status: **Implemented 2026-10-24**. QA: `docs/qa/course-summary-report-acceptance.md`.

## Reference evidence

Inspected once: `docs/references/klass-reporting-module/Course Summery Report.png` and `Course Summery Report.pdf`.

| Item | Observed contract |
|---|---|
| Dialog | Course Summary Report; selected teacher/course summary; Cancel and Generate Report. |
| Step 1 | `1. SELECT TEACHER`, searchable single choice. |
| Step 2 | `2. SELECT COURSE`, searchable single choice dependent on teacher. |
| Step 3 | `3. REPORT PARAMETERS`: Start Date*, End Date*, Month*, Report Name. |
| PDF | Header: institute/course/teacher/period/generated. KPI tiles; Weekly Breakdown: week, days, new registration, classes, attendance, payments plus three amount columns; Cumulated Summary: Full Cards, Discounted Cards, Free Card Students, Not Paid Full Cards. |

PDF text does not expose several KPI/money-column labels. This implementation uses owner-approved audited labels below; it does not claim literal label parity where evidence is unreadable.

## Request/API

Base `/api/v1/reports/course-summary`; full endpoint contract: `docs/api/reporting-analytics.md`. All responses use `Cache-Control: no-store` and the `{data}` envelope.

- `GET /options` → `{teachers:[{id,name}]}`.
- `GET /options/teachers/{teacherId}/courses` → `{courses:[{id,name}]}`. Tenant-scoped primary-teacher assignment only.
- `POST /runs` body `{teacherId, courseId, startDate:"YYYY-MM-DD", endDate:"YYYY-MM-DD", month:"YYYY-MM", reportName?}` → 201 complete typed run.
- `GET /runs/{runId}` → owner-bound stored run.
- `GET /runs/{runId}/pdf` → authenticated attachment; filename separate from displayed title.

`teacherId`, `courseId`, dates and month are required. Blank title becomes `Teacher Report`; title max 255. End cannot precede start. Month must equal both dates' calendar month, so ranges cannot span months. Invalid/foreign/mismatched teacher-course values return `422 REPORT_VALIDATION`; the response does not distinguish foreign from unknown resources.

## Period and source rules

Tenant timezone controls date interpretation. Every source window is start-inclusive/end-next-day-exclusive: `[startDate 00:00, endDate + 1 day 00:00)` in tenant zone.

| Field | Source/grain | Rule |
|---|---|---|
| Total students / New registrations | `course_participation`, unique participation/student | Participation `enrolled_at` in range. Free enrollment is not payment evidence. |
| Payments / Students paid | positive confirmed `ledger_entry` × selected-course allocation, distinct confirmed entry | Ledger creation date in range. Pending/rejected/failed/FREE/STAFF_GRANTED excluded; refunds never create a payment. Weekly `PAYMENTS` and cumulative Full/Discounted Card counts use the same confirmed-entry grain, so they reconcile. |
| Classes | distinct non-cancelled `class_session` | `scheduled_start` in range. |
| Attendance | distinct canonical `attendance_record` | Occurrence in range; PRESENT/LATE only. Missing record is not absence. |
| Revenue | course allocation from immutable enriched ledger entry | Confirmations minus refund allocations; net of discounts; facility fee/postage excluded. Multi-course totals never repeat. |
| Commission % / teacher share | settlement share snapshot or teacher share rate effective on ledger-local payment date | `Commission % = 100% - teacher share %`; monetary commission is revenue minus teacher share. Historical snapshot/rate only; never current course commission setting. |
| Full/Discounted Cards | one positive confirmed ledger entry for selected course, classified from immutable order-line discounts | Exactly one cumulative paid-card count per confirmed entry. Discounted iff any non-reward selected-course line has positive `discountAmount`; otherwise Full. Line price/list value use immutable snapshots; a legacy line-less payment is Full and uses allocated revenue as its available price snapshot. |
| Free Card Students | unrevoked `free_card` overlapping selected date range | Complimentary access only; no revenue/commission/share. |
| Not Paid Full Cards | no authoritative historical request/card source exists | Report marks `Not tracked` / `—`; never invents zero. |

Sources are independently bounded and aggregated before combination. No payment × attendance × enrollment join exists.

## Dataset/PDF/security

A complete typed report snapshot contains normalized filters, institute/currency/timezone, generated metadata, KPI values, weekly rows, cumulative rows, and explanatory notes. Jasper receives typed rows and parameters only; no template arithmetic. Preview/download behavior is direct PDF generation, matching reference workflow.

PDF layout follows the one-page portrait reference: A4 595×842 pt, 34 pt content gutter/border, `#1A2332` 69 pt header, compact three-column metadata, four 47 pt KPI cards, 34 pt weekly rows, a separate cumulative table, and centered generated/institute footer. Reference geometry drives visual placement; application `LMS Sans` remains embedded for Sinhala/Tamil Unicode. Amount-table labels remain audited where reference text showed only `LKR`.

Every endpoint requires authenticated `FINANCE_EXPENSES/VIEW`. Tenant derives from trusted context. Redis keys bind report namespace, tenant, user, run id; TTL 15 minutes. Run/PDF limits are configurable under `app.reporting.course-summary`. Report reads run in read-only transactions and never create or change business records.

## Limitations

- Course dependency follows current primary teacher assignment; historic reassignment attribution is unavailable.
- `Not Paid Full Cards` lacks an authoritative historical source.
- Source PDF obscures several original headings; approved audited labels document our semantics.
- Existing participation/free-card indexes lead with `(tenant_id, course_id)` but do not include report time-window columns. No migration/index was added because no representative query plan was captured; inspect plan before adding a tenant-leading time-range index.
