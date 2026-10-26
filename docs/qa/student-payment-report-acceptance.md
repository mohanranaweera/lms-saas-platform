# Student Payment Report — Acceptance Evidence (RPT-02)

Date: 2026-10-23
Spec: `docs/specs/student-payment-report.md`

## Implemented behavior

- Tenant-admin Student Payment Report route with server-authorized options, MONTHS/DATES filters, optional course and payment-method filters, loading/empty/error/permission-denied states, responsive table/card layout, grouping switches, pagination, stale-run handling, and PDF export.
- Backend report service uses trusted tenant context and authenticated report owner, validates tenant-owned course IDs, reads confirmed ledger/payment/order snapshots, preserves leading-zero student indexes, separates course payment/service fee/postage, counts distinct students and confirmed receipts, keeps refunds as negative rows, excludes pending/rejected/non-cash financial rows, and stores short-lived Redis run snapshots.
- PDF export uses same stored run as preview, landscape A4, embedded Unicode fonts, period/timezone/course/group metadata, KPI cards, method subtotals, service-fee/postage columns, repeated report metadata on every page, repeated table headings, page numbering, and no financial-state writes.
- Manual receipts use `received_on` for DATES mode; MONTHS mode uses immutable billing-period allocations. Partial course filters allocate staff-adjustment reconciliation proportionally rather than claiming the full receipt adjustment. DATES workload bounds do not double-count a manual confirmation already loaded by the ledger window.

## Checks executed

| Check | Result | Evidence |
|---|---|---|
| Backend compile | PASS | `backend\\mvnw.cmd -f backend\\pom.xml -DskipTests -Dspotless.check.skip=true -Dcheckstyle.skip=true -DskipITs compile` — BUILD SUCCESS |
| Focused backend unit/PDF tests | PASS | `-Dtest=StudentPaymentCalculatorTest,StudentPaymentGroupingTest,StudentPaymentPdfRendererTest` — 24 tests, 0 failures/errors |
| Student Payment integration tests | PASS | `-Dtest=StudentPaymentReportIntegrationTest,StudentPaymentReportAcceptanceIntegrationTest,StudentIndexNumberIntegrationTest` — Student Payment integration 14/14, acceptance 5/5; index-number integration 4/4. Testcontainers Docker/Postgres/Redis available. |
| Frontend TypeScript | PASS | `frontend\\node_modules\\.bin\\tsc.cmd --noEmit -p frontend\\tsconfig.json` — no output/exit 0 |
| Frontend ESLint | BLOCKED | ESLint 9 reports no `eslint.config.js/mjs/cjs`; existing project config does not match installed ESLint 9 flat-config discovery. No lint result claimed. |
| Frontend Playwright | PASS | `playwright test e2e/student-payment-report.spec.ts --workers=1` — 20 passed, 0 failed. |
| Reference PDF rendering | BLOCKED for supplied reference | `pdftoppm` unavailable. PDF was read/extracted through repository tooling. |
| Generated PDF visual samples | PASS with limitation | Jasper tests generated `backend/target/report-samples/rpt02-*` PDFs/PNGs. Reviewed reference, grouped, student/course grouped, refund/postage, and Sinhala/Tamil samples. Empty/multi-page page-2 image review was not possible through the available PDF renderer. |
| Diff whitespace check | PASS | `git diff --check` and `git diff --cached --check` completed without whitespace errors. |

## Acceptance coverage

Backend tests cover reference totals, leading-zero indexes, tenant timezone boundaries, manual receipt received dates, cross-year billing months, multi-course/multi-month allocation grain, course filtering, duplicate student names, grouping invariants, pending/rejected/failed/non-cash exclusion, discount/staff adjustment/refund/postage handling, historical price/fee/postage changes, PDF reconciliation, no financial/access-state mutation, cross-tenant/run ownership rejection, permission ordering, validation, cache headers, TTL, workload bounds, and rate limits.

Frontend E2E covers catalog navigation, optional courses, MONTHS/DATES request semantics, server validation errors, summary/method/fee display, grouping and export, pagination, row retry, refunds/manual date-only rows, stale results, expired export, expired row-run without stuck loading, empty state, 403, 429, 503, and oversized-run messaging.

## Known limitations / follow-up

- ESLint remains blocked by repository/tooling config mismatch (`eslint` 9 flat config absent). Do not treat this as frontend lint success.
- Teacher Income regression suite also passed: 43 tests across calculators, validators, run store, PDF, composer, integration, and acceptance coverage.
- PDF image review depends on a PDF rasterizer (`pdftoppm` or equivalent) unavailable in this environment.
- `excludedNotMonthAttributable.amount` is explicitly course-payment-only; related service fees/postage remain separately excluded and documented rather than silently folded into that field.
- No new Flyway migration was introduced by the report logic beyond the existing Student index/receipt-date migrations already present in the working tree; applied migrations were not edited.
