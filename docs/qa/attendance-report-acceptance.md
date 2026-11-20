# Attendance Report — Acceptance Evidence (RPT-03)

Date: 2026-10-23

## Implemented

- Tenant-admin catalog card and `/tenant-admin/reports/attendance` workspace.
- Searchable single-course selection preserving selected course while search changes.
- Required inclusive date range and editable report title with blank normalization.
- Server-authorized, tenant-scoped attendance fact query and owner-bound Redis run.
- Date matrix with P/A/— semantics, leading-zero indexes, duplicate-name identity, legend and KPIs.
- Landscape Jasper PDF with six-date readable bands, repeated headings/identity columns, footer and no-data message.
- Authenticated no-store PDF download with sanitized filename.

## Commands run

| Command | Result |
|---|---|
| `backend\\mvnw.cmd -f backend\\pom.xml -DskipTests compile` | Passed |
| `backend\\mvnw.cmd -f backend\\pom.xml -DskipTests process-classes` | Passed; attendance JRXML precompiled |
| `backend\\mvnw.cmd -f backend\\pom.xml -Dtest=com.lms.attendancemanagement.service.AttendanceReportServiceTest test` | Passed: 13 tests |
| `backend\\mvnw.cmd -f backend\\pom.xml -Dtest=com.lms.attendancemanagement.AttendanceReportIntegrationTest test` | Passed: 9 tests |
| `backend\\mvnw.cmd -f backend\\pom.xml -Dtest=com.lms.reportinganalytics.service.pdf.StudentPaymentPdfRendererTest test` | Passed: 7 tests; existing report regression |
| `backend\\mvnw.cmd -f backend\\pom.xml -Dtest=AttendanceReportCalculatorTest,AttendanceReportRequestValidatorTest test` | Passed: 6 tests |
| final focused backend suite (attendance service + RPT-03 calculator/validator/PDF/HTTP integration) | Passed: 26 tests |
| `backend\\mvnw.cmd -f backend\\pom.xml -Dtest=AttendancePdfRendererTest test` | Passed: 4 tests; fixture, bands, Unicode/empty, JRXML compile |
| final focused ESLint + TypeScript check + `npm run test:e2e -- e2e/attendance-report.spec.ts --workers=1` | Passed: 5 browser tests |
| `npm --prefix frontend run lint` | Blocked by pre-existing Student Payment `set-state-in-effect` error; existing branding warning and two unused E2E warnings also reported |
| `npm --prefix frontend run lint -- --file ...` | Not supported by ESLint flat config (`--file` invalid) |

## Focused fixture evidence

Calculator unit tests reconcile 45 distinct student rows, 135 P cells and 0 A cells for a synthetic equivalent of the reference fixture. Additional tests cover:

- same-day PRESENT/LATE precedence over ABSENT;
- missing status remains `—`;
- duplicate record ID does not inflate counts;
- duplicate names remain distinct by student ID;
- leading-zero index strings remain unchanged;
- tenant-local date boundary conversion;
- blank title default and foreign/invalid input validation.

## PDF visual evidence

- `backend/mvnw.cmd -DskipTests process-classes` compiled `attendance-report.jrxml` successfully.
- Existing report PDF renderer regression passed.
- Attendance PDF renderer tests generate reference and wide-band samples under `backend/target/report-samples`.
- Browser PDF inspection completed for reference and wide-band samples: readable six-column bands, KPI labels/values, repeated headers/identity columns, legend/footer/page number and no visible clipping or overlap. Reference fixture spans three readable vertical pages; wide sample spans three date bands.

## Limitations and blockers

- Full `backend\\mvnw.cmd verify` attempted; harness stopped it after 600 seconds under system memory pressure. Focused compile/tests passed.
- Frontend lint remains red from pre-existing Student Payment workspace hook lint error outside Attendance Report files.
- Attendance query uses a new parameterized bounded projection; production query-plan evidence remains unverified. No migration added because existing tenant-leading attendance indexes cover the source course path; re-evaluate with production-like plans before adding an index.
- Existing attendance schema has no finalized flag; stored canonical records are authoritative. Cancelled class-session facts are excluded; observed-record scope is documented; no inferred absences.
