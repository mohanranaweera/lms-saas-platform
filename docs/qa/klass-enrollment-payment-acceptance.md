# Klass enrollment & payment — acceptance (EP-)

Spec: `docs/specs/Klass_Enrollment_Payment.md` §3A and §13. Implementation:
`docs/specs/klass-enrollment-payment-workflows.md`. Commands and environment: see
`docs/qa/klass-course-management-acceptance.md` ("Commands actually run") — same runs (backend verify 2627/0/0 (final, after T8d),
`com.lms.klass` 11/11, live `klass-*` E2E 12/12, updated mocked E2E 132/132, frontend lint/tsc/build clean).

## Acceptance matrix

| ID | Status | Evidence |
|---|---|---|
| EP-C01 | PASS | E2E modal, default cover, advanced toggle, buttons; screenshot `klass-evidence/02-add-course-modal.png` |
| EP-C02 | PASS | E2E three inline required errors and no POST (screenshot 03); `createValidationErrorsForNameTeacherAndPricing` |
| EP-C03 | PASS | `crossTenantTeacherIdAndLinkedCourseIdAreRejected`, `createWithKlassFields…`; E2E chosen teacher persisted |
| EP-C04 | PASS (model) / PARTIAL (UI labels) | `createWithKlassFields…`, `offersList…`; E2E selects "Monthly Fee" only — six labels not enumerated |
| EP-C05 | PASS | `KlassLessonAccessAcceptanceIntegrationTest.nonZeroEnrollmentFeeStillEnrollsFreeWithZeroOrders`; E2E enrolment in a 500-fee course creates nothing to pay |
| EP-C06 | PARTIAL | toggle works in E2E; value retention after collapse not asserted (implemented: values kept in form state) |
| EP-C07 | PASS (API) | `secondCourseReusesExistingTenantTagsCaseInsensitively`; chip UI not asserted |
| EP-C08 | PASS (API) | `CourseCoverIntegrationTest` (valid, wrong type 415, too large 413, cross-tenant 404); E2E cancel creates no course; UI upload not asserted |
| EP-C09 | PASS | `editChangesCommissionAppendOnlyAndAuditsIt`; invalid values 400 (`CourseKlassRulesTest`); commission not used by settlement (O-3) |
| EP-C10 | PASS | `linkedSourceLessonsUseSourceRules…` |
| EP-C11 | PASS | `KlassLessonAccessAcceptanceIntegrationTest.linkedCourseBypassAttemptsAreDenied…`, `selfAndNestedLinks…` |
| EP-C12 | PASS | E2E valid save survives reload, exactly one create request; `sameIdempotencyKeyFromTheSameActorReturnsTheFirstCourse` |
| EP-C13 | PASS | E2E chain: course created → student enrolls free → unpaid lesson denied |
| EP-E01 | PASS | E2E YES enrolls, card moves to Enrolled, nothing to pay; `selfEnrollIsIdempotentAudited…` |
| EP-E02 | PASS | E2E NO changes nothing (screenshot 07) |
| EP-E03 | PASS | `concurrentSelfEnrollsCreateExactlyOneParticipation` (6 concurrent → 1 row) |
| EP-E04 | PASS | `closedRegistration…`, `archivedAndRegistrationClosedCoursesDenyNewEnrollmentButExistingParticipantsKeepAccess` |
| EP-E05 | PASS | `rejectedGatewayPaymentGrantsNothingAndKeepsParticipation`, `rejectedSlipGrantsNothing…` |
| EP-L01 | PASS | E2E metadata + Pay now, no open control, server launch 403 (screenshot 08); `unpaidParticipantBrowsesMetadata…` |
| EP-L02 | PASS | `unpaidParticipant…` (download, link, join, launch denied) |
| EP-L03 | PASS | `freeLessonInAnUnpaidCourseLaunchesButCoveredAndPaidSiblingsStayLocked` |
| EP-L04 | PASS | `gatewayConfirmationGrantsExactlyTheLinesOnceAndOnlyThoseItems` (ICT Oct ≠ ICT Sep ≠ Physics Oct); E2E only paid months unlock |
| EP-L05 | PASS | `expiredEntitlementCanBeRepurchased…`, `LessonAccessRulesTest$LessonWindow` |
| EP-L06 | PASS | sibling paid session stays locked in `gatewayConfirmation…` |
| EP-M01 | PASS | E2E zero totals, no pay action |
| EP-M02 | PASS | E2E two lines, 7,000 + 200 = 7,200 equals server quote (screenshot 09); `quoteAppliesFacilityFee…` |
| EP-M03 | PASS | E2E remove re-quotes (3,600), no duplicate lines |
| EP-M04 | PASS | `tamperedPricesAreIgnoredAndForeignTargetsAreRejected` |
| EP-M05 | NOT TESTED | year/course/tab switching not asserted (single-course rule enforced server-side) |
| EP-P01 | PASS | `pendingItemCannotBeOrderedOrPaidAgain…` (checkout start grants nothing) |
| EP-P02 | PASS | `gatewayConfirmation…` (one ledger entry, replay grants nothing) |
| EP-P03 | NOT TESTED | provider failure/timeout UI not exercised (bounded 30 s steps with retry implemented); online method unavailable on demo tenant locally |
| EP-P04 | PASS | E2E slip → pending (screenshots 10, 11) |
| EP-P05 | PASS | E2E approval in existing review UI (screenshot 12) → Paid (13); rejection grants nothing (backend) |
| EP-P06 | PASS | `anotherStudentsOrAnotherTenantsOrderAndSlipAreDenied`, T02 test |
| EP-P07 | PASS | `pendingItemCannotBeOrderedOrPaidAgain…`, `LineOrderPaymentStartGuardIntegrationTest` (5 race/duplicate cases); E2E 409 `ORDER_PAYMENT_IN_PROGRESS` |
| EP-S01 | PASS (API) | `offersList…` DAILY `supported:false`; UI "No data available" implemented, not asserted |
| EP-S02 | PASS | DAILY/INSTALLMENT rejected 422; no fake payment path |
| EP-D01 | PASS | V86 backfill test (see CM-A06); lms_dev migration with unchanged row counts |

## Review findings and resolution

| Source | Finding | Resolution |
|---|---|---|
| payment-ledger-specialist H-1 | pay-twice guard unlocked | FIXED (D24) — locks + same-order evidence check; `LineOrderPaymentStartGuardIntegrationTest` |
| payment M-1 | legacy MONTHLY order unlocks every month | FIXED (D23) — 409 `USE_PAYMENT_COMPOSER` |
| payment M-2 / security M2 | no system-actor audit for gateway grants / chargeback revocations / duplicate flags | OPEN — O-7 (needs audit-trigger decision); entitlement rows + structured logs |
| payment M-3 | abandoned PENDING payments never expire | OPEN — O-6 (payment-rule change) |
| payment L-1 | DB-level immutability trigger for entitlements | OPEN (optional hardening) |
| payment L-2 | V87 header says PER_ORDER fee "not spread"; code puts it on line 1 | DOCUMENTED here (migration not edited): PER_ORDER fee is carried on line 1 so line totals sum to the order amount |
| payment L-3 | ADR-015 $0 path still callable | UI no longer uses it (enroll is free participation); code kept |
| payment L-4 | past months at current rate; module prices regardless of pricing model | OPEN — O-8 product confirmation |
| security M1 | invite links / private linked courses visible to non-participants | FIXED — `CourseStudentVisibilityIntegrationTest` |
| security L1–L6 | lock gap, tenantId repo params, shared video asset, 403 existence leak, teacher preview across links, http player links | FIXED (T8b) with tests |
| security change-control | governance edits need human confirmation | For owner: edits listed in the final report and plan T0 |
| ui-ux-reviewer | 18 admin findings | FIXED except the three documented exceptions |
| QA | reactivation entry removed (ADR-013 regression) | FIXED (T8c) — 8 tests pass unchanged |
| QA | optional tenant-config keys cannot be cleared | FIXED (T8d) — JSON null clears optional keys (no default, not required), audited `tenant_config.cleared`; required/defaulted keys still 400; full verify 2627/0/0 |
| User report | new course missing from student Available Courses (created as DRAFT; admin list showed no status) | FIXED (T8f) — Status column, Publish/Unpublish row actions (unpublish confirmed), "created as a draft" toast with Publish now; live E2E `klass-admin` T8f tests: Draft hidden → Publish → visible in Available → Unpublish → hidden (9/9 with klass-student-enrollment); screenshots 15–17 |
| QA | prod-build-only catalog toggle failure | FIXED (T8e) — courses page made dynamic + history.replaceState view switch; also fixed sidebar "My Courses" in production; 54/54 on production build and dev |

## Environment notes and data left in lms_dev (demo tenant)

- V84 draft-checksum fix applied by the owner; backup `C:/Projects/lms_dev-pre-v84fix.dump`.
- Test courses "KLASS E2E …", enrollments, two approved test slips (LKR 7,200 each) with CONFIRMED payments,
  ledger entries and entitlements — financial history, intentionally not deleted.
- PAYMENT config: facility fee reset to NONE/0; test bank values ("Test Bank", "000-TEST") remain; remove them with `PUT /api/v1/tenant-config/PAYMENT` sending JSON null for the four bank keys (T8d).
- The demo student's 5th device slot is used by the E2E device; reset it as tenant admin before a new real browser login.
