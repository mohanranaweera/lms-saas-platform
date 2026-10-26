# Reporting Module — Plan (RPT)

Spec: `docs/specs/teacher-income-summary-report.md` (contract) · Catalog: `docs/specs/reporting-catalog.md`
· QA: `docs/qa/teacher-income-summary-report-acceptance.md` · Approval: ADR-029 Addendum A2.

## Scope & delivery order
1. **RPT-01 (this change)** — Reports entry in existing tenant-admin navigation (Finance group), Teacher Income
   Summary: filters, screen preview, summary PDF, per-teacher payslip PDF.
2. **RPT-02 (implemented)** — Student Payment Report (`docs/specs/student-payment-report.md`; QA evidence: `docs/qa/student-payment-report-acceptance.md`).
3. **RPT-03 (implemented)** — Attendance Report (`docs/specs/attendance-report.md`; QA evidence: `docs/qa/attendance-report-acceptance.md`).
4. **RPT-05 (implemented 2026-10-24)** — Course Summary Report: three-step teacher/course/period wizard, bounded typed snapshot and direct PDF (`docs/specs/course-summary-report.md`; QA: `docs/qa/course-summary-report-acceptance.md`).
5. **RPT-06 (implemented 2026-10-25)** — Unpaid Students Report: current course participation minus full confirmed cash coverage for one purchased billing month, optional present-attendance filter, preview + snapshot PDF (`docs/specs/unpaid-students-report.md`; QA: `docs/qa/unpaid-students-report-acceptance.md`).
6. **RPT-07 (implemented 2026-10-25)** — Registration Report: tenant student registrations by inclusive date range, All Courses/current association filter, optional recorded method/operator filters, preview + owner-bound snapshot PDF (`docs/specs/registration-report.md`; QA: `docs/qa/registration-report-acceptance.md`).
7. **RPT-08 (implemented 2026-10-25)** — Student Behavior Report: exact-course confirmed cash coverage compared across adjacent purchased billing months, preview + owner-bound snapshot PDF (`docs/specs/student-behavior-report.md`; QA: `docs/qa/student-behavior-report-acceptance.md`).
8. **RPT-09 (implemented 2026-10-26)** — Undo Payments Report: effective append-only payment refunds, course allocation, preview + owner-bound PDF (`docs/specs/undo-payments-report.md`).
9. Backlog: Registration Payments, Enrollment, Referral Code (catalog only; no queries/templates yet).
Existing reports (Finance Reports, attendance reports, course analytics) are untouched.

## Planning facts
- **Goal:** authoritative, tenant-isolated teacher income summary whose preview, summary PDF and payslip come from
  one dataset and one calculation path.
- **Files affected:** backend `com.lms.reportinganalytics` (the `reporting-analytics` domain from `.claude/rules/architecture.md`: service, web, pdf, DTOs), narrow read APIs in
  `ledgersettlementmanagement` / `paymentmanagement` `api` packages, revenue-allocation basis (A2),
  `pom.xml` (JasperReports), `src/main/resources/reports/*.jrxml` + font extension; frontend `/tenant-admin/reports`
  pages/components + API client; docs listed above.
- **Database impact:** none planned (read-only queries on existing tables; index only if a query plan justifies it).
- **API impact:** new endpoints under `/api/v1/reports/teacher-income` (spec §4). No existing contract changes.
  Behavioural change to existing allocation consumers per ADR-029 A2 (course shares exclude facility fees).
- **Security impact:** FINANCE_EXPENSES/VIEW on every endpoint incl. exports; tenant from `TenantContext`; run
  snapshots bound to (tenant, user), short-lived, never public URLs; PDFs `Cache-Control: no-store`; templates are
  application-owned (no scriptlets/SQL/user JRXML).
- **Tenant impact:** all queries tenant-filtered from context; teacher ids validated as same-tenant TEACHER users;
  cross-tenant preview/export tests mandatory.
- **Tests required:** see QA doc — JUnit/Testcontainers integration (financial fixtures, isolation, read-only),
  Playwright (filter dialog, preview, export/payslip actions, states), visual PDF review.

## Technology decision
Embedded **JasperReports Library 7.1.0** (`jasperreports` + `jasperreports-pdf`; latest on Maven Central,
released 2026-10-14; LGPL-3.0; Java 17+ baseline → OK on Java 21; PDF via Jaspersoft OpenPDF fork). No existing PDF
engine in the app, so no migration cost. No JasperReports Server / BI platform / microservice. Complex scripts
(Sinhala/Tamil) via Jasper's AWT text-layout PDF rendering + Noto fonts (SIL OFL 1.1) in a font extension.

## Owner decisions (2026-10-22)
- Facility fees + postage are institute-only service fees, for settlement too (ADR-029 A2, resolves O-1).
- Teacher/commission split uses the teacher share rate settlement pays with (O-3 `course_commission_rate` unused).

## Known limitations (documented, not hidden)
- Unsettled revenue is attributed to the course's current primary teacher (no attribution snapshot on payments).
- No co-teacher split exists → 100% to primary teacher (matches settlement).
- Payins have no data source → "Not tracked".
- Settlement includes STAFF_GRANTED amounts today; the report excludes them as non-cash and shows the gap
  (fixing settlement is a separate change-controlled decision).
- No separate export permission exists → VIEW covers export.
- Teacher self-service income report is not built (no existing teacher-facing income feature).

## Operational limits (configurable `app.reporting.teacher-income.*`)
≤ 50 teachers; ≤ 36 months or ≤ 366 days; ≤ 50,000 ledger entries per run (counted before loading); ≤ 5,000 course
rows; ≤ 2 MB stored run; run TTL 15 min in Redis bound to (tenant, user); `POST /runs` rate limit 10/min per
(tenant, user) (same Redis fixed-window pattern as `AuthRateLimiter`). Exceeding a bound → 422 `REPORT_TOO_LARGE`
or 429. Runtime images need fontconfig/freetype for AWT font loading (PDF fonts are bundled).

## Status (2026-10-22)
RPT-01 Teacher Income Summary + payslip: **implemented** (backend, frontend, tests, docs).
RPT-02 Student Payment Report: **implemented** (see below and `docs/qa/student-payment-report-acceptance.md`).
RPT-03 Attendance Report: **implemented** (`docs/specs/attendance-report.md`; direct PDF generation, no preview subsystem).
RPT-05 Course Summary Report: **implemented** (`docs/specs/course-summary-report.md`; three-step wizard, direct PDF, no schema migration).
RPT-07 Registration Report: **implemented** (`docs/specs/registration-report.md`; tenant-profile registration date, current course association, preview + snapshot PDF, no schema migration).
RPT-08 Student Behavior Report: **implemented** (`docs/specs/student-behavior-report.md`; exact-course adjacent-month confirmed cash coverage, preview + snapshot PDF, no schema migration).

## RPT-08 Student Behavior — planning facts
- **Goal:** classify distinct students as New, Dropped or Continuing by comparing full confirmed cash coverage for one course in the selected purchased billing month against the immediately preceding month.
- **Files affected:** reporting-analytics report/calculator/controller/run/Jasper files; existing narrow payment/ledger/course/student APIs only where needed; tenant-admin report page/API/validation; focused tests/docs.
- **Database impact:** none; immutable order lines and append-only ledger allocations provide the historical facts.
- **API impact:** new `/api/v1/reports/student-behavior/**`; existing report contracts remain unchanged.
- **Security / tenant impact:** authenticated report-view permission, tenant from trusted context, course/teacher scope, owner-bound short-lived runs, protected no-store exports, cross-tenant tests.
- **Classification:** `New=C-P`, `Dropped=P-C`, `Continuing=C∩P`; returning after a gap is New; Free Cards and non-cash grants excluded; current incomplete month marked provisional.

## RPT-05 Course Summary — planning facts
- **Goal:** one teacher/course, single-month tenant-local range, audited weekly/cumulative summary and direct PDF from one immutable run.
- **Files affected:** reporting-analytics service/controller/calculator/run/Jasper template, narrow course/enrollment/live-class/payment projections, tenant-admin wizard/API client, focused tests/docs.
- **Database impact:** none; existing structurally tenant-filtered tables and indexes are reused.
- **API impact:** new `/api/v1/reports/course-summary/**`; no existing public endpoint changed.
- **Security / tenant impact:** `FINANCE_EXPENSES/VIEW` on all endpoints; tenant from authenticated context; selected course must belong to selected primary teacher; run bound to tenant/user.
- **Evidence gap:** source PDF hides several column labels; approved audited labels/definitions are recorded in the spec. `Not Paid Full Cards` is marked unsupported, never zero-filled.

## RPT-02 Student Payment Report — planning facts
- **Goal:** list confirmed student course payments (receipt × course × order line) with service fees/postage separate,
  per-method totals, distinct students, optional grouping by student/course, preview + landscape PDF from one run.
- **Files affected:** backend `reportinganalytics` (student-payment service/calculator/controller/JRXML; shared run-store,
  rate-limit and validation helpers extracted from RPT-01 only where both use them), narrow `api` reads in payment,
  ledger, course and user modules, `V95__student_index_number.sql`; frontend `/tenant-admin/reports/student-payments`.
- **Database impact:** V95 adds `student_profile.index_number` (NOT NULL, unique per tenant, trigger-assigned from
  `student_index_counter`, immutable; existing students backfilled in (created_at, id) order). No financial table changes.
- **API impact:** new `/api/v1/reports/student-payments/**` (spec §8). RPT-01 API unchanged.
- **Security / tenant impact:** FINANCE_EXPENSES/VIEW on every endpoint; tenant from context; course ids validated
  same-tenant; run snapshots bound to (tenant, user); student personal data only in authenticated, no-store responses.
- **Tests required:** spec acceptance A–R (JUnit/Testcontainers + Playwright), index-number assignment, PDF visual review.
- **Owner decisions:** D1 auto-assigned index numbers; D2 any selected period; D3 manual receipts dated by `received_on`
  (Teacher Income unchanged — documented difference).
