# Student Behavior Report — Acceptance Evidence

## Contract coverage

| Acceptance | Evidence target |
|---|---|
| A–D | Single-month picker: blue header, separate displayed/applied year, 3x4 month grid, selected pill, explicit Search, keyboard focus and programmatic selected state |
| E–F | Backend rejects missing/invalid course/month; January compares previous December |
| G–H | Calculator proves adjacent paid-coverage sets and returning-student behavior |
| I | UUID membership preserves same-name students and deduplicates duplicate source events/orders |
| J–L | Historical availability, provisional current month, exact course/month coverage, pending/free-card/partial/reversal rules |
| M–N | Empty category copy; complete screen rows/counts reconcile with PDF rows/counts |
| O | Cross-tenant, unauthorized course, other-user run and protected PDF requests fail |
| P | Long names, Unicode, repeated headings, page numbers and multi-page PDF |
| Q | Existing report/month-picker tests remain green |
| R | Report leaves financial, enrollment, access and attendance tables unchanged |

## Visual checks

Reference files inspected once. Local browser screenshot and generated-PDF render results must be recorded after implementation; no live demo behavior is claimed because it was not re-inspected.

## Commands/results

- `backend\\mvnw.cmd -f backend\\pom.xml -DskipTests compile` — PASS.
- `backend\\mvnw.cmd -f backend\\pom.xml -DskipTests process-classes` — PASS after correcting Jasper 7 group-expression syntax; all templates, including `student-behavior-report.jrxml`, compiled.
- `backend\\mvnw.cmd -f backend\\pom.xml "-Dtest=StudentBehaviorCalculatorTest" test` — PASS.
- `backend\\mvnw.cmd -f backend\\pom.xml "-Dtest=StudentBehaviorPdfRendererTest" test` — PASS after fixing Jasper bean-property accessors and group-header field expression; this reproduced and resolves the PDF-export 500 path.
- `cd frontend && npm exec -- tsc --noEmit` — PASS.
- focused `eslint` for behavior report page/API/validation/Playwright files — PASS.
- `npm exec -- playwright test e2e/student-behavior-report.spec.ts --list` — PASS; one Chromium test discovered (not executed against a running stack).
- `git diff --check` — PASS; line-ending warnings only.

## Remaining checks / limitations

Focused Playwright execution, local browser screenshot comparison, rendered-PDF visual inspection, cross-tenant HTTP integration tests, and full backend/frontend regression suites have not run yet. No pass claim is made for them. Live Klass behavior was not re-verified. Current implementation reports append-only ledger history as available; environments with incomplete imported historical ledgers still need an explicit source-completeness marker before `historicalAvailability` can distinguish that operational condition.
