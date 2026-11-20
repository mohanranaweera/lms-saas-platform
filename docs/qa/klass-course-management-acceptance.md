# Klass course management — acceptance (CM-)

Spec: `docs/specs/Klass_Course_Management.md` §13. Implementation: `docs/specs/klass-course-management-workflows.md`.
Shared payment/enrollment IDs are evidenced in `docs/qa/klass-enrollment-payment-acceptance.md`; where a
CM- and EP- ID test the same behaviour the evidence is listed once and cross-referenced.

## Commands actually run (2026-10-13 / 14)

| Check | Command (working dir) | Result |
|---|---|---|
| Backend full suite | `MAVEN_OPTS=-Xmx512m ./mvnw.cmd -o verify -DargLine="-Xmx1536m -XX:+UseSerialGC"` (backend/) | BUILD SUCCESS — 2627 tests, 0 failures, 0 errors, 0 skipped (final, after T8b–T8d); parent re-ran frontend tsc/lint/build on the final tree: clean / 0 errors / OK |
| Klass backend acceptance (QA) | `./mvnw.cmd -o test -Dtest='com.lms.klass.**'` | 11/11 pass |
| Engineer Klass suites | CourseKlassRulesTest, CourseKlassAdminIntegrationTest, CourseCoverIntegrationTest, ParticipationAndLessonAccessIntegrationTest, LessonAccessRulesTest, LessonAccessServiceTest, EntitlementGrantServiceTest, CheckoutComposerEntitlementIntegrationTest, LineOrderPaymentStartGuardIntegrationTest, CourseStudentVisibilityIntegrationTest | all pass (94/94 before T8b; re-run inside the 2620) |
| Flyway on Testcontainers | `-Dtest=FlywayMigrationIntegrationTest` | pass (V85–V87 + Hibernate validate) |
| Flyway on local dev DB | backend `spring-boot:run` local profile | "Successfully applied 3 migrations" (V85–V87) after the owner-approved V84 draft-checksum fix |
| Frontend | `npm run lint`, `npx tsc --noEmit`, `npm run build` (frontend/) | lint 0 errors (pre-existing warnings only), tsc clean, build OK |
| Live E2E | `npx playwright test e2e/klass-*.spec.ts --workers=1` against backend + `npm run dev` (demo.lms.test:3000) | 12/12 pass (klass-admin 4, klass-student-enrollment 3, klass-payment-composer 4, klass-player-handoff 1) |
| Existing E2E (updated for new pages) | 10 specs `--workers=2` (mocked) | 132/132 pass |
| Course-edit session/resource completion (2026-10-17) | Focused backend Maven suites (`CourseKlassRulesTest`, `CourseKlassAdminIntegrationTest`, `CourseModuleLessonIntegrationTest`, `MediaFileLibraryIntegrationTest`, `LessonMediaIntegrationTest`, `MaterialTypeAndDownloadPolicyIntegrationTest`, `KlassLessonAccessAcceptanceIntegrationTest`) | 74/74 pass, 0 failures/errors/skips |
| Session editor frontend regression | `course-modules`, `tenant-admin-course-edit-parity`, `session-resource-workflow` (`--workers=2`) | 24/24 pass |
| Tenant Admin workspace regression | `tenant-admin-courses` (`--workers=2`) | 19/19 pass |
| Media integration regression | Combined course/media run before final review | 54/54 pass; final reviewed session suites also pass |
| Final static verification | targeted ESLint, `npm exec -- tsc --noEmit`, `npm run build` | lint clean, typecheck clean, production build success |
| Resource refinement backend | IMAGE/V89/catalog/month derivation + student/media suites | 37/37 core tests, 73/73 downstream tests and 11/11 explicit IMAGE library-access tests passed |
| Resource refinement frontend | `session-resource-workflow`, course/media picker suites, student content/material/access suites | 23/23 editor tests, 36/36 popup/media tests and 68/68 student visibility/access tests passed |
| Live local acceptance | focused `klass-admin` CM-A04/A05 and `klass-student-enrollment` | 1/1 admin session flow and 3/3 student flows passed |

## Acceptance matrix

| ID | Status | Evidence |
|---|---|---|
| CM-A01 | PASS | E2E `klass-admin` "CM-A01" (search, columns, Archived tab, registration switch survives reload); `CourseKlassAdminIntegrationTest.listSupportsArchivedSearchAndPersistedSubsetOrder`, `registrationToggleIsPersistedAndAudited`; screenshot `klass-evidence/01-admin-course-list.png` |
| CM-A02 | PASS (breadcrumb navigation not asserted) | E2E session list scoped to module; `crossTenantModuleAndLessonKlassEndpointsReturn404`, `modulePriceAndReorderRejectingForeignIds` |
| CM-A03 | PASS | E2E name required / cancel creates nothing / survives reload; screenshot 04 |
| CM-A04 | PASS | E2E required errors, additional-settings toggle, resource/payment/billing fields persisted; Document/READY-video filtered paginated pickers, mixed multiple attachments, HTTPS link configuration, gated Zoom surfaces and partial-save retry; `sessionValidationCodesExpiredFlagReorderAndPriceAudit`; screenshot 05 |
| CM-A05 | PASS | E2E end-before-start error; tenant-timezone 16:20–18:00 stored as a 100-minute window; existing From/To can be edited or cleared, while untouched ISO timestamp precision survives rename/reorder |
| CM-A06 | PASS | `KlassLessonAccessAcceptanceIntegrationTest.legacyOneTimeOrderStillActivatesEnrollmentAndTheV86BackfillPreservesEveryHistoricRow` (md5-identical enrollment/order/payment/ledger/refund/audit rows); V85–V87 applied to lms_dev with row counts unchanged |
| CM-S01 | PASS | E2E search/card fields; `catalogSplitsAvailableAndEnrolledCourses`; screenshot 06 |
| CM-S02 | PARTIAL | Five tabs implemented with persistent header (screenshot 13); Content and Live tabs asserted (E2E + mocked specs); Exams/Tute/About tabs not asserted |
| CM-S03 | PASS | E2E Oct/Sep folders newest first with metadata; screenshot 08 |
| CM-S04 | PASS | E2E Sep+Oct Paid, Aug still Pay now (screenshot 13); `gatewayConfirmationGrantsExactlyTheLinesOnceAndOnlyThoseItems` |
| CM-S05 | PASS | `KlassLessonAccessAcceptanceIntegrationTest.liveClassesUseTheSameEvaluatorAsTheContentTree`; mocked live-class specs |
| CM-S06 | PASS | E2E expanding folders stays 0 of 4, launch → 1 of 4; `paidParticipantLaunchRecordsProgressOnceAndReadingTheTreeRecordsNothing` |
| CM-P01 | PASS | see EP-M02 (7,200 fixture; same server calculation as 4,000 + 100) |
| CM-P02 | PASS (backend) | `gatewayConfirmation…` (replay grants nothing), `pendingItemCannotBeOrderedOrPaidAgain…`; online checkout not run E2E (demo tenant gateway not operational locally) |
| CM-P03 | PASS | E2E slip → pending → approval grants (screenshots 10–13); `rejectedSlipGrantsNothing…` |
| CM-P04 | PASS (month entry point) | E2E October Pay now preselects October; lesson-level Pay now not asserted |
| CM-R01 | PASS | `LessonAccessRulesTest`; `KlassLessonAccessAcceptanceIntegrationTest.freeLessonInAnUnpaidCourse…` |
| CM-R02 | PASS | `expiredLessonIsDeniedEvenWhenPaidAndItsMaterialIsDenied` |
| CM-R03 | PASS (configured-link rendering not asserted) | E2E `klass-player-handoff` on real data (screenshot 14, links "Not configured"); `launchReportsPlayerHandoffOnlyForVideoLessons…` |
| CM-T01 | PASS | `draftAndCrossTenantCoursesAreNotFound`, `nonParticipantAndCrossTenantCallersGet404…`, `crossTenant*` tests, `anotherStudentsOrAnotherTenantsOrderAndSlipAreDenied`, mocked cross-tenant UI |
| CM-T02 | PASS | `KlassRolesAndPaymentOwnershipAcceptanceIntegrationTest.studentAndNonOwnerTeacherCannotPerformAdminMutationsOrApproveSlips` |
| CM-U01 | PASS (review) | UX review confirmed no Quick Create/import/QR/fake controls; unsupported resource types disabled; DAILY/INSTALLMENT "No data available" (`offersList…`) |

## Review outcomes

- UX (ui-ux-reviewer, admin): historical review fixes retained. The 2026-10-17 course-edit completion also
  renames the separate live-class workspace tab to **Live classes**, adds session anchors to the content editor,
  and uses the full Session form from both editor paths. Edit still opens the course overview where the course
  edit form lives. The two "copy promises behaviour" Highs were re-checked after T4b/T4c: linked-course content
  and per-month/module/session unlocking now exist and are tested, so the copy is accurate.
- Security and payment findings: see the enrollment/payment acceptance doc.

## Reference fidelity (parent visual check)

Compared screenshots 01, 05, 08, 09, 13 with the Klass references: same structure, labels and states. Known
differences: month folder label "October 2026" vs Klass "2026 October"; our product name "AOC Player" instead of
"Vidrora"; Quick Create/import/QR controls intentionally absent. No creation/composer reference images exist.

## Outstanding gaps

CM-S02 Exams/Tute/About tabs not E2E-asserted; breadcrumbs not asserted; lesson-level Pay now entry not asserted;
online gateway checkout not exercised end-to-end locally (needs an operational PayHere sandbox + public notify URL).
