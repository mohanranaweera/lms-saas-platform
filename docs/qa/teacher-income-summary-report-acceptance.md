# Teacher Income Summary Report — Acceptance & QA Evidence (RPT-01)

Spec: `docs/specs/teacher-income-summary-report.md` · Plan: `docs/plans/reporting-module.md` · Date: 2026-10-22.
Only checks that were actually run are listed. Backend tests: JUnit 5 + Testcontainers PostgreSQL/Redis.
Frontend: Playwright with mocked API routes (no live backend E2E was run).

## 1. Test runs
| Run | Command | Result |
|---|---|---|
| Backend full suite (after security fixes + package rename) | `backend\mvnw.cmd verify` | BUILD SUCCESS — 2906 tests, 0 failures, 0 errors |
| Report + allocation + settlement classes | targeted `-Dtest=` runs | 37 report tests + 44 settlement/discount/multi-course tests, all pass |
| Frontend types | `npx tsc --noEmit -p .` | pass |
| Frontend lint | `npm run lint` | exit 0 (3 pre-existing warnings in unrelated files) |
| Frontend build | `npm run build` | success (`/tenant-admin/reports`, `/tenant-admin/reports/teacher-income`) |
| Playwright report spec | `npx playwright test e2e/teacher-income-report.spec.ts` | 20/20 pass |
| Playwright regression (nav) | `npx playwright test e2e/finance.spec.ts` | 15/15 pass |

## 2. Acceptance matrix (requirement → evidence)
| Requirement | Evidence (test) | Status |
|---|---|---|
| Multi-teacher selection + strict single-teacher filtering | `paymentMethodFilterNonCashExclusionAndStrictTeacherFilter`, `datesModeBoundariesInTheTenantTimezoneMethodsNonCashAndStrictTeacherFilter` | Pass |
| Months across years | `monthsModeFollowsTheBillingMonthAcrossYearsWithRefundsAndLessonMonths`, `monthsModeTakesOnlyLinesBilledInTheSelectedMonthsAcrossYears…`; PW "month selection persists across years" | Pass |
| Date boundaries in tenant timezone (start-inclusive, next-day-exclusive) | `datesModeBoundariesInTheTenantTimezone…` (non-UTC tenant, 23:59 vs 00:00) | Pass |
| Payment-method filter; active-mode-only filtering | `paymentMethodFilter…`, `invalidFiltersAre422AndTheInactiveModesFieldsAreRejected`, `periodModeIsRequiredAndOnlyTheActiveModesFieldsAreAllowed`; PW "DATES mode … sends only the active mode" | Pass |
| Empty results / invalid input | validator tests (`teachersAreRequired…`, `monthsMustBeValidAndAtMost36`, `datesMustBeOrdered…`); PW empty + 422 field errors | Pass |
| Multi-course orders without duplication | `multiCourseOrderIsCountedOncePerTeacherAndSectionAndNeverDuplicated` | Pass |
| Discounted revenue + facility fee as service fee | `discountedMultiCourseOrderReportsNetCourseSharesAndFacilityFeesAsServiceFees` (real composer) | Pass |
| Reference fixture 4,000 → 3,000 / 1,000; fee 100 separate; later rate change alters nothing | `referenceFixtureNetAtTheRateInEffectAndFacilityFeeAsServiceFee`, `referenceFixtureNetRevenueTeacherShareCommissionAndServiceFee`, `payoutsComeFromStoredPaidSettlements…ALaterRateChangeAltersNothing` | Pass |
| Historical attribution/rate for settled allocations | `settledAllocationsUseTheSettlementTeacherAndRateAndReportTheBasisDifference` | Pass |
| Multiple-teacher (co-teacher) allocation without duplicate attribution | `TeacherIncomeReportAcceptanceIntegrationTest.coTeacherGetsNoAttributionAndPrimaryIsNotDuplicated` (course created with teacherIds [A,B]; 100% to primary A, once; B nothing) | Pass |
| Manual payment, pending slip, Free Card, reversal | manual/slip: `paymentMethodFilter…`; Free Card: `TeacherIncomeReportComposerIntegrationTest`; refunds: `datesModeUsesTheTenantLocalDate…RefundsByRefundDateWithCountZero`, months refund follows line month; pending/rejected/failed: `TeacherIncomeReportAcceptanceIntegrationTest.pendingRejectedAndFailedPaymentsNeverAppear` (6 payments via real endpoints; only approved slip + receipt count) | Pass |
| Distinct payment count | `multiCourseOrderIsCountedOnce…`; refund-only rows count 0 | Pass |
| Payouts/payins/balance with nonzero fixtures | `payoutsAreStoredPaidSettlementFiguresIncludingAdjustmentsAndBalanceIsTheMovement` (PAID adjustment included; payins null) | Pass |
| Mixed currency | `currenciesAreSeparateSectionsSortedAndNeverSummed`; PW currency sections | Pass |
| Subtotals reconcile with KPIs; screen = PDF = payslip | `datasetReconcilesAndThePdfShowsExactlyItsTotals`, `payslipTotalIsTheTeachersShareAndLinesSumToIt`; PW KPI = server values | Pass |
| Export uses the stored snapshot, all rows | `runSnapshotExportsAndPayslipReadOnlyTheStoredRunAndWriteNothing`; PW stale-after-filter-change disables export | Pass |
| Cross-tenant / unauthorized preview and export | `crossTenantAndCrossUserAccessIsRejected`, `monthsModeNeverReadsAnotherTenantsOrdersLessonsOrRefunds`, `everyEndpointRequiresFinanceView`, `foreignAndUnknownTeacherIdsGetTheSameResponseAndUnpermittedUsersGet403ForAnyRunId`; PW 403 states | Pass |
| Report/payslip changes no financial state | `runSnapshotExports…WriteNothing` (ledger/settlement/payment row counts unchanged) | Pass |
| Resource bounds, rate limit, caching, Redis outage | `resourceBoundsAreEnforcedBeforeAndAfterComputation`, `pdfExportOfAStoredRunAboveTheRowBoundIs422`, `oversizedSnapshotIs422AndNotStored`, `reportResponsesAreNeverCached`, `redisOutageOnSaveOrReadIs503`; PW 422/429/503 | Pass |

## 3. Visual PDF review (rendered PDFs inspected, `backend/target/report-samples/`)
Samples are generated from `TeacherIncomeCalculator` datasets (reconciled, asserted in tests).
- Reference summary/payslip: layout matches reference (header band, filter line, 4 KPI cards, service-fee line,
  teacher cards, course table, footer) — pass.
- Sinhala + Tamil names (teacher, course, institute) in summary and payslip PDFs: correctly shaped with embedded
  Noto fonts — pass.
- Many pages (6 teachers × 28 long-titled courses, 11 pages): column header repeats on each page, teacher headers not
  orphaned, long titles wrap, no clipped amounts, no blank trailing page, "Page X of Y" — pass.
- Unrated rows ("*" + "—"), refund-only negative row, nonzero payouts, USD second section, notes — pass.
- Fixed during review: unreconciled sample data, timestamp wrapping mid-date, green "Not tracked", crammed unrated
  marker, payslip signature alignment.

## 4. QA engineer additions
`TeacherIncomeReportAcceptanceIntegrationTest` (2) + `TeacherIncomeReportIntegrationTest` (13):
`mvnw.cmd test -Dtest=TeacherIncomeReportAcceptanceIntegrationTest,TeacherIncomeReportIntegrationTest` → 15/15 pass.
Limitation: for a selected teacher with no income the test accepts either omission or an empty teacher card.

## 5. Environmental notes / not run
- No live-backend Playwright E2E (frontend E2E uses mocked routes, the repo's existing pattern).
- Linux container fonts: AWT font loading needs fontconfig/freetype in the runtime image (not verified here).
- The Klass demo was inspected read-only; no Klass report was executed (see catalog).
