# Reporting Catalog (Klass parity)

Source: one bounded, read-only pass over `https://demo.klass.lk/dashboard/reports` on 2026-10-22
(already-authenticated browser session; no credentials entered or stored; no report searches executed,
no records changed) plus the local references in `docs/references/klass-reporting-module/`.

Labels: **observed** (seen in Klass UI/reference), **supplied-description-only** (only the card
description seen), **assumed** (our decision), **unverified** (not inspected).

| # | Report (exact name) | Klass card description (observed) | Inputs | Output | Status here |
|---|---|---|---|---|---|
| 1 | Teacher Income Summary Report | "View earnings of teachers for a selected period or specific months." | observed: Teacher(s)* multi-select chips; Payment Method (Cash / Bank Transfer / Card, optional); Period MONTHS (default none selected, current year, year nav, multi-month across years) / DATES (calendar "SELECT DATE / Enter date" — single vs range **unverified**) | observed (reference PDFs): screen preview → summary PDF + per-teacher payslip | **Implemented (RPT-01)** — `docs/specs/teacher-income-summary-report.md` |
| 2 | Student Payment Report | "Find student payments made within a selected time frame or specific months." | observed: Course* ("one or more courses"); Payment Method; MONTHS / DATES (single-date calendar with month+year nav). Observed note "paid for all selected periods" — **not adopted** (owner D2: any selected period). Course optional here (all when empty) | observed (screenshot + Klass PDF): heading "PAYMENT HISTORY REPORT - <date>", course chip, Group by student / Group by course, EXPORT PDF; columns # · Index no. · Student · Course · Reason (Month/Module) · Payment Method · Date · Amount (+ fee); summary students paid, course total, per-method totals, service fees on top. Combined grouping behavior: student, course, or student → course; PDF uses same stored run and includes service-fee column, period metadata, method subtotals, refunds and postage where present | **Implemented (RPT-02)** — `docs/specs/student-payment-report.md`; QA: `docs/qa/student-payment-report-acceptance.md` |
| 3 | Registration Payments Report | "Generate a report of registration payments for a chosen period." | observed: Start Date*, End Date*, Report Name; actions CANCEL / GENERATE REPORT | unverified — looks direct-generate (no preview pane observed) | Backlog |
| 4 | Attendance Report | "Generate a student attendance report for a selected date range." | observed: Select Course (searchable single-selection list), Start Date*, End Date*, editable Report Name | observed (screenshot + PDF): direct landscape PDF; title/institute/course/range/generated time, TOTAL RECORDS/PRESENT/ABSENT, student/date matrix, footer | **Implemented (RPT-03)** — `docs/specs/attendance-report.md`; raw attendance history remains unchanged |
| 5 | Course Summary Report (Beta) | "Generate a detailed report for a selected teacher, course, and time period." | observed: 1. searchable single teacher, 2. searchable single course filtered by teacher, 3. selected scope + Start Date*, End Date*, Month*, Report Name; our date range is single-calendar-month and controls all metric windows | PDF observed: context header, four KPI tiles, weekly breakdown and cumulative categories (some labels unreadable); direct PDF download | **Implemented (RPT-05)** — `docs/specs/course-summary-report.md`; QA: `docs/qa/course-summary-report-acceptance.md` |
| 6 | Unpaid Students Report | "Generate a list of unpaid students for a selected period or specific months." | observed local reference: one required course, one purchased billing month, optional `At least one attended` | observed local reference: preview + full snapshot PDF, unpaid count and student list | **Implemented (RPT-06)** — `docs/specs/unpaid-students-report.md`; QA: `docs/qa/unpaid-students-report-acceptance.md` |
| 7 | Registration Report | "Generate a list of registrations for a selected date range." | observed local reference: All Courses/authorized course, optional Operator and Registration Method, inclusive calendar range | observed local screenshot/PDF: preview count/table/details/export; PDF current courses, billing-period last payment and registration date | **Implemented (RPT-07)** — `docs/specs/registration-report.md`; QA: `docs/qa/registration-report-acceptance.md` |
| 8 | Enrollment Report | "Generate a list of registration fee payments of courses with a registration fee, for a selected date range." | supplied-description-only | unverified | Backlog |
| 9 | Student Behavior Report | "Generate a report of new, dropped, and concurrent students for a selected course and month." | observed local reference: required single course and selected month; three-column Jan–Dec picker with year navigation | observed screenshot + PDF: preview, New/Dropped/Continuing counts and student lists, snapshot PDF | **Implemented (RPT-08)** — `docs/specs/student-behavior-report.md`; QA: `docs/qa/student-behavior-report-acceptance.md` |
| 10 | Undo Payments Report | "Generate a list of undo payments." | course optional (all when empty), inclusive tenant-local date range | preview + owner-bound PDF; effective refund/course rows, undo actor/date, amount and original payment date | **Implemented (RPT-09)** — `docs/specs/undo-payments-report.md` |
| 11 | Referral Code Report | "Count students registered with each referral code for a selected date range." | supplied-description-only | unverified | Backlog |

## Terms that must not be conflated (each future report needs its own verified definition)
- **Student registration** (#7) — a student account joining the institute; not a payment.
- **Platform registration payments** (#3) — fee for registering with the institute/platform (if any). No equivalent
  exists in our model yet; **unverified**.
- **Course registration / enrollment fee payments** (#8 — Klass calls this "Enrollment Report") — a per-course
  registration fee. Our current rule (ADR-028): **enrollment is free** and payment-independent; no course
  registration fee exists. Any such report needs an approved definition first.
- **Undo payments** (#10) — our ledger is append-only; the analogue is REFUND/chargeback entries, never deletions.

## Payment-method mapping (assumed, RPT-01)
Klass labels → our derived `PaymentMethod`: Card → `GATEWAY` (online); Bank Transfer → `MANUAL_SLIP` (approved
bank slip); Cash → `MANUAL_RECEIPT` (ADR-030 staff receipt). We label them "Online (card)", "Bank transfer (slip)",
"Manual receipt (cash)". FREE and STAFF_GRANTED are non-cash and not selectable.

## Shared conventions (assumed, apply to future reports only when they need them)
Screen-first preview + server-side PDF (JasperReports) from one report service and a short-lived run snapshot;
MONTHS/DATES period mode; tenant timezone; FINANCE_EXPENSES/VIEW for financial reports. Do not build shared
infrastructure ahead of the second report that needs it.
