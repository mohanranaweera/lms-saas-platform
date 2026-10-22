# Reporting Catalog (Klass parity)

Source: one bounded, read-only pass over `https://demo.klass.lk/dashboard/reports` on 2026-10-22
(already-authenticated browser session; no credentials entered or stored; no report searches executed,
no records changed) plus the local references in `docs/references/klass-reporting-module/`.

Labels: **observed** (seen in Klass UI/reference), **supplied-description-only** (only the card
description seen), **assumed** (our decision), **unverified** (not inspected).

| # | Report (exact name) | Klass card description (observed) | Inputs | Output | Status here |
|---|---|---|---|---|---|
| 1 | Teacher Income Summary Report | "View earnings of teachers for a selected period or specific months." | observed: Teacher(s)* multi-select chips; Payment Method (Cash / Bank Transfer / Card, optional); Period MONTHS (default none selected, current year, year nav, multi-month across years) / DATES (calendar "SELECT DATE / Enter date" — single vs range **unverified**) | observed (reference PDFs): screen preview → summary PDF + per-teacher payslip | **Implemented (RPT-01)** |
| 2 | Student Payment Report | "Find student payments made within a selected time frame or specific months." | observed: Course* ("one or more courses"); Payment Method; MONTHS/DATES. Observed note: "When multiple months or dates are selected, results display students who have paid for all selected periods." | unverified (columns/exports not inspected) | **Next** |
| 3 | Registration Payments Report | "Generate a report of registration payments for a chosen period." | observed: Start Date*, End Date*, Report Name; actions CANCEL / GENERATE REPORT | unverified — looks direct-generate (no preview pane observed) | Backlog |
| 4 | Attendance Report | "Generate a student attendance report for a selected date range." | observed: Select Course (searchable list), Start Date*, End Date*, Report Name | unverified | Backlog (existing attendance reports in our app stay as-is) |
| 5 | Course Summary Report (Beta) | "Generate a detailed report for a selected teacher, course, and time period." | observed: 1. teacher (searchable), 2. course (filtered by teacher), 3. Start/End Date*, Report Name | unverified | Backlog |
| 6 | Unpaid Students Report | "Generate a list of unpaid students for a selected period or specific months." | supplied-description-only | unverified | Backlog |
| 7 | Registration Report | "Generate a list of registrations for a selected date range." | supplied-description-only | unverified | Backlog |
| 8 | Enrollment Report | "Generate a list of registration fee payments of courses with a registration fee, for a selected date range." | supplied-description-only | unverified | Backlog |
| 9 | Student Behavior Report | "Generate a report of new, dropped, and concurrent students for a selected course and month." | supplied-description-only | unverified | Backlog |
| 10 | Undo Payments Report | "Generate a list of undo payments." | supplied-description-only | unverified | Backlog |
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
