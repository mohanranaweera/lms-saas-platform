# Reporting Module — Plan (RPT)

Spec: `docs/specs/teacher-income-summary-report.md` (contract) · Catalog: `docs/specs/reporting-catalog.md`
· QA: `docs/qa/teacher-income-summary-report-acceptance.md` · Approval: ADR-029 Addendum A2.

## Scope & delivery order
1. **RPT-01 (this change)** — Reports entry in existing tenant-admin navigation (Finance group), Teacher Income
   Summary: filters, screen preview, summary PDF, per-teacher payslip PDF.
2. **RPT-02 (next, not started)** — Student Payment Report.
3. Backlog: Registration Payments, Attendance, Course Summary, Unpaid Students, Registration, Enrollment,
   Student Behavior, Undo Payments, Referral Code (catalog only; no queries/templates yet).
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
**Next: RPT-02 Student Payment Report** — not started. Klass observations already captured in the catalog
(course multi-select, payment method, MONTHS/DATES, "paid for all selected periods" semantics); it needs its own
plan and verified definitions before any code.
