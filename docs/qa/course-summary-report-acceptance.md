# Course Summary Report — Acceptance Evidence

Date: 2026-10-24

## Checks executed

| Check | Result |
|---|---|
| Backend clean compile | **PASS** — `backend/mvnw.cmd clean compile -DskipTests`; 1,306 sources compiled. |
| Jasper precompile | **PASS** — `backend/mvnw.cmd process-classes -DskipTests`; all five report templates, including `course-summary-report.jrxml`, compiled. |
| Course Summary unit/PDF tests | **PASS** — `-Dtest=CourseSummaryPdfRendererTest,CourseSummaryCalculatorTest,CourseSummaryRequestValidatorTest`; 7 tests, 0 failures. Renderer produced valid `%PDF-` bytes and expected report text; calculator covers commission complement, weekly/cumulative payment reconciliation, legacy line-less payments, refunds, and entry deduplication. |
| Existing report calculator regression | **PASS** — `-Dtest=CourseSummaryPdfRendererTest,CourseSummaryCalculatorTest,CourseSummaryRequestValidatorTest,AttendanceReportCalculatorTest,TeacherIncomeCalculatorTest,StudentPaymentCalculatorTest`; 33 tests, 0 failures at that run. Course Summary edge-case test later raised its focused suite to 6 tests, all passing. |
| Frontend TypeScript | **PASS** — `frontend` `npx tsc --noEmit`; production `npm run build` also exited 0. |
| Course Summary Playwright | **PASS** — `npx playwright test e2e/course-summary-report.spec.ts`; 3 passed. |
| Frontend full lint | **BLOCKED by pre-existing unrelated error** — `student-payment-workspace.tsx:106` setState-in-effect; existing branding warning and two existing unused-variable warnings also reported. Course Summary files produced no lint errors after unused local removal. |
| PDF layout parity | **PASS / partial visual** — renderer test generated `backend/target/report-samples/course-summary-reference.pdf`, asserts one 595×842 page and required header/KPI/weekly/cumulative/footer text; template precompile passed. It now uses measured reference gutter/header/card/row geometry. Raster image review remains blocked: `pdftoppm` and ImageMagick are unavailable. Long Unicode/multi-page visual review remains required. |

No production database, credentials, production records, deployment, push, merge, or migration was used.

## Acceptance coverage

- **A** wizard order: Playwright verifies teacher → course → parameters and progress text.
- **B** required validation/back state: Playwright checks missing teacher and Back course retention; validator tests required/date/month rules.
- **C** upstream invalidation: Playwright verifies teacher change clears incompatible course.
- **D** delayed options: API uses React Query keyed by teacher; implementation uses dependent query and teacher change clears course. A delayed-response adversarial test remains to add.
- **E** forged/inconsistent parameters: server validator checks tenant-scoped course primary teacher; validator test covers mismatch.
- **F** tenant date semantics: server requires single month and calculator uses tenant zone; boundary integration test remains to add.
- **G** metric source/grain: calculator separates participation, sessions, attendance, and ledger sources; unit tests verify a 75% teacher share produces `25.00%` Commission, monetary amounts remain 75/25, and Weekly Payments equals cumulative Full + Discounted Card count.
- **H** multi-course/multi-teacher duplication: ledger course allocation path filters selected course; fixture reconciliation test remains to add.
- **I** pending/free/reversal cases: ledger excludes non-cash methods and refunds stay signed; focused calculator fixture test remains to add.
- **J** preview/export reconciliation: direct PDF receives stored report snapshot; visual output check remains.
- **K** stale filters: frontend disables PDF re-download after filter changes.
- **L** loading/empty/error/permission: page uses QueryStateBoundary, dependent query state, EmptyState, live status, and backend 403 handling; Playwright covers 403.
- **M** cross-tenant/unauthorized: service uses tenant-aware APIs and permission gate; full Testcontainers cross-tenant integration remains to add.
- **N** long/Unicode/multi-page PDF: template uses LMS font and stretch-safe fields; visual fixture remains.
- **O** existing reports: no existing report template or endpoint contract changed; regression suite not rerun in this pass.
- **P** no business mutations: report orchestration uses read-only source reads and Redis snapshot only; no write path exists.

## Remaining limitations

Full lint and visual PDF acceptance are not green/completed due to the stated pre-existing lint error and missing render inspection. Before commit/PR, add remaining calculator, authorization/tenant integration, boundary, delayed-response, and PDF visual tests, then rerun relevant report regressions.
