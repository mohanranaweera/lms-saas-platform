# Registration Report — Acceptance Evidence (RPT-07)

Date: 2026-10-25. Scope: tenant student registrations only; no enrollment/payment mutation.

| Acceptance | Evidence / status |
|---|---|
| A. All Courses includes students without enrollment/payment | Backend source query starts from tenant `student_profile` registration window; course relation is optional enrichment. |
| B–C. Tenant registration date/boundaries | `student_profile.created_at` used with tenant-zone `[start, end+1)` interval. Global user creation not queried. |
| D/F. Course qualification / no duplicate rows | Current participation is pre-aggregated by student; one source profile produces one report row. |
| E. Operator/method | `created_by` and `registration_status` are source fields; no editor/payment/teacher substitute. |
| G–H. identity/missing values | Profile id is row identity; index is string; display fallbacks are `—`. |
| I. Last payment | Current backend implementation leaves value `—`: existing narrow payment API cannot safely derive confirmed latest billing period without adding a reviewed batched contract. Limitation documented in spec. |
| J–L. Snapshot reconciliation | Stored `RegistrationRun` supplies all preview pages/PDF; order is profile creation time then id; total is full row set. |
| M–N. authorization/isolation | Every endpoint calls `FINANCE_EXPENSES/VIEW`; snapshots keyed by tenant + authenticated user; source read uses tenant-aware repositories. Dedicated integration tests still required. |
| O. UI states | Options use shared query boundary; workspace has loading/empty/filter-empty/error/403 boundary; stale filters block export. Selector labels map IDs/codes to human labels. Klass-like calendar supports single-day/range selection, direct month/year dropdown selection, and separate previous/next controls for month and year. |
| P. PDF | Landscape Jasper template uses existing Unicode font, repeated headers, page footer/page number, stretch-height course/name cells. Local reference/output visual render blocked: no PDF renderer installed. |
| Q. Existing reports | Backend compile passed. Full regression not run in this session. |
| R. No business mutation | Report services are read-only; only Redis run/rate-limit state is written. |

## Commands

- `backend\mvnw.cmd -f backend\pom.xml -Dtest=RegistrationReportIntegrationTest test` — **passed**: 2 tests, 0 failures/errors; verifies tenant-zone boundaries, All Courses without enrollment/payment, distinct same-name rows, owner-bound run isolation and options.
- `npx --prefix frontend tsc --noEmit -p frontend/tsconfig.json` — **passed**.
- focused `npx eslint` over registration report/API/E2E files — **passed**.
- `cd frontend; npx playwright test e2e/registration-report.spec.ts --reporter=line` — **passed**: selector display labels, calendar range selection, generation and result row.
- `npm --prefix frontend run lint` — registration files pass; full lint remains **blocked by unrelated** `student-payment-workspace.tsx` `react-hooks/set-state-in-effect` error plus existing warnings.
- PDF reference/output rendering — **blocked**: `pdftoppm`, `pdfinfo`, PyMuPDF absent.

## Required follow-up

Run Testcontainers integration tests for date boundaries, tenant isolation/run ownership, course multiplicity and PDF bytes; run Playwright report flow and render first/continuation/last generated PDF pages in an environment with Poppler or equivalent.
