# Unpaid Students Report — Acceptance Evidence

## Automated coverage

| Cases | Evidence |
|---|---|
| A, B, C, D, E | `UnpaidStudentsCalculatorTest.includesOnlyEnrolledStudentsWithoutFullCashCoverageAndPreservesIndexes`; exact purchased month/order-line allocation, payment timestamp ignored, wrong course excluded |
| F, G, H | `UnpaidStudentsCalculatorTest.paymentReceiptDateIsIrrelevantButPartialAndReversalRemainUnpaid`; pending/rejected/failed have no ledger evidence; partial remains; signed reversal restores unpaid |
| I, J, K, L, M | `UnpaidStudentsCalculatorTest.excludesFreePaymentAndAppliesAttendanceOnlyAfterUnpaidQualification`; non-cash ignored, ancillary amounts excluded by line allocation, optional attendance filter |
| N, O, P | calculator de-duplicates ledger entry/attendance record/session IDs, keys rows by student UUID, preserves index strings, applies tenant timezone month boundaries |
| R | `UnpaidStudentsPdfRendererTest.multiPagePdfRepeatsHeadersAndCarriesPageNumbersAndUnicodeRows`; one stored run feeds rows and full PDF |
| U | report services and cross-domain APIs are read-only; only Redis snapshot/rate counters write |

## PDF checks

`UnpaidStudentsPdfRendererTest` covers multi-page output, repeated column headings, page numbers, explicit attendance metadata, complete count, empty output, long names and Sinhala/Tamil text through bundled LMS Noto fonts. Supplied Klass fixture was inspected once: 3 pages, 63 rows, title/institute/course/month/generated metadata, two KPI cards and reference table. Implementation intentionally improves missing `No`, continuation headers and page numbers.

## Commands run

- `backend/mvnw.cmd -f backend/pom.xml -DskipTests compile` — PASS.
- `backend/mvnw.cmd -f backend/pom.xml -DskipTests process-classes` — PASS; all six Jasper templates compiled.
- `backend/mvnw.cmd -f backend/pom.xml "-Dtest=UnpaidStudentsCalculatorTest,UnpaidStudentsPdfRendererTest" test` — PASS, 6 tests (4 calculator + 2 PDF).
- `npx eslint` on new/changed unpaid-report frontend files — PASS.
- `npx tsc --noEmit` — PASS.
- `npm run build` — PASS; `/tenant-admin/reports/unpaid-students` generated.

## Remaining integrated checks

`UnpaidStudentsReportIntegrationTest` — PASS, 3 Testcontainers HTTP tests: tenant/run-owner isolation, permission-before-validation + no-store, and no business-table mutations. Combined RPT-06 backend set — PASS, 9 tests. Focused Playwright execution still needs a running test stack. Full backend `verify` exceeded its 30-minute harness limit and was stopped. Whole frontend lint has one unrelated pre-existing `react-hooks/set-state-in-effect` failure in Student Payment workspace. No E2E pass claim is made here.
