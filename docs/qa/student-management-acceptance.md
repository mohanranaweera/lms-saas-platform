# STU-MGMT-02 — Student management acceptance matrix

Spec `docs/specs/student-management-parity.md` · ADR-033 · Evidence: `docs/qa/student-management-evidence/`.
Legend: **K** = confirmed Klass (screenshots) · **S** = our security requirement · **A** = explicit assumption.

Test sources: **BE** = backend ITs in `backend/src/test/java/com/lms/usermanagement/student/web/`
(`StudentRegistrationV2`, `StudentDirectory`, `StudentPhoto`, `StudentRemarksAndQr`,
`StudentImpersonation`, `StudentManagementQa` IntegrationTest) + `StudentQrLabelPdfRendererTest`,
`ProfileImageNormalizerTest`; **E2E-M** = mocked `frontend/e2e/student-management-parity.spec.ts`
(+ `student-management.spec.ts`); **E2E-L** = live `frontend/e2e/student-management-live.spec.ts`
(`STU_LIVE=1`, real backend + `lms_dev`, synthetic data).

| # | Requirement | Evidence | Existing gap | Implementation | Verification | Status |
|---|---|---|---|---|---|---|
| 1 | Directory: heading, Active/Pending tabs, Black List disabled | K | Flat list | Directory page + `GET /students/directory` | E2E-M; E2E-L `directory-desktop.png` | PASS |
| 2 | Search Field selector + server search over full dataset, page reset | K + A (options) | Client list | `field`/`q`, 300 ms debounce | BE Directory; E2E-M; E2E-L `directory-search.png` | PASS |
| 3 | Columns Index/First/Last/Email/Mobile/Last Login; strings & leading zeros kept | K | No index/mobile/last login | Directory row DTO, tenant-zone 24 h time | BE RegistrationV2 (leading-zero mobile); E2E-L grid | PASS |
| 4 | Row actions + More menu with accessible labels/tooltips | K | Partial | Row actions | E2E-M; `directory-more-menu.png` | PASS |
| 5 | CSV export full filtered scope + formula escaping + audit | K + S | None | `export.csv`, `student.directory_exported` | BE Directory | PASS |
| 6 | Create form fields/order/validation; all fields round-trip | K | name/email/password only | `POST /students/v2`, `PUT /detail` | BE RegistrationV2; E2E-L form → API reopen (17 fields) | PASS |
| 7 | Index server-assigned, unique under concurrency | S | Trigger existed | V95/V97 reused | BE RegistrationV2 concurrent creates | PASS |
| 8 | Unknown DOB stays blank; future DOB rejected | S | — | Validation | BE RegistrationV2; E2E-M | PASS |
| 9 | Courses = free participation; no order/payment/ledger/entitlement; edit add-only | S (ADR-028) | — | `CourseParticipationApi.enrollOnBehalf` | BE RegistrationV2 + Qa `editCourseSelectionIsAddOnly…`; E2E-L profile "No ledger-confirmed payments" | PASS |
| 10 | Password optional → temp password once; edit never touches credentials | S | — | Provisioning API | BE RegistrationV2; E2E-L `create-student-temporary-password.png` | PASS |
| 11 | Email required; case variant of an existing email refused | A (deviation) + S | Case-sensitive check (pre-existing) | `existsByEmailIgnoreCase` on create | BE Qa `upperCaseVariantOfAnExistingEmailIsAConflict`, `existingEmailConflict…` | PASS |
| 12 | Profile image upload/change/remove, validation, private, consistent surfaces | S | None | Photo endpoints, 512² JPEG re-encode, signed URLs, crop editor | BE Photo + normalizer; E2E-L upload → drawer/profile at 3 viewports → removal fallback | PASS |
| 13 | Drawer fields, remarks, devices, courses; no stale-student flash | K + S | None | Drawer keyed by id | E2E-M rapid switching; `details-drawer.png` | PASS |
| 14 | Full profile sections, date-filter scopes, empty vs error states | K | Tabs page | Full profile | E2E-M (error state test); `full-profile-*.png` | PASS (see gap G2) |
| 15 | Remarks staff-only, author/time, plain-text rendering | S | None | `student_remark` | BE RemarksAndQr + Qa `remarkMarkupIsStored…`; E2E-M | PASS |
| 16 | Profile QR, PNG download/print, decodes to opaque payload | K + S | None | `qr`, `qr.png` (ZXing) | BE decode; live `student-qr.png` **and the screenshot `full-profile-qr-card.png` decode** to `LMS-STU:1:<48 hex>` | PASS |
| 17 | Bulk Print QRs selection semantics + PDF labels decode | K | None | Dialog + `qr-export` (Jasper) | E2E-M selection across search/pages; BE 25-label multi-page PDF rasterised @150 dpi → 25/25 decoded; live `bulk-qr-labels.pdf` → exactly the 2 selected payloads | PASS |
| 18 | QR cannot authenticate / leak PII; resolver tenant-scoped | S | — | Opaque token; resolver staff-only | BE RemarksAndQr (anon 401, student 403, cross-tenant 404) | PASS |
| 19 | Log as Student: permission, reason, audit start/end, both identities | S (ADR-033) | None | `impersonation_session`, audit | BE Impersonation; Qa role tests | PASS |
| 20 | Read-only, expiry, actor logout/suspension/demotion, nested/cross-tenant refusal | S | — | Filter + guard | BE Impersonation (5 tests incl. `%2D` path bypass and demotion) | PASS |
| 21 | Banner, exit, cache clear, multi-tab, no token in storage/URL | S | — | `/impersonate`, banner | E2E-M; E2E-L real session: banner, reload stays student, storage/URL token-free, exit → admin | PASS |
| 22 | Pending approve | K | None | `approve` | BE RegistrationV2; E2E-M | PASS |
| 23 | Delete = tenant removal preserving history & other tenants | S | Deactivate only | Soft removal | BE RegistrationV2 + Qa `removalKeepsHistory…` | PASS |
| 24 | Send Message via Communications | K | — | Composer `?studentId=` | E2E-M | PASS |
| 25 | Black List not invented | K (disabled) | — | Disabled tab | E2E-M | PASS |
| 26 | Today's Classes (CAL-01) status/order/exact boundaries/midnight | Product | Done in CAL-01 | Reused, unchanged | `todays-classes.spec.ts` 7/7 (clock-driven); E2E-L `home-todays-classes-*.png` | PASS |
| 27 | Cross-tenant negative tests on every new endpoint | S | — | ITs | BE Qa `everyPerStudentEndpointIs404…` (13 endpoints, no side effects) | PASS |
| 28 | Live demo inspection | — | — | — | No credentials supplied to the session | BLOCKED |

## Known gaps / follow-ups

- **G1** Student dashboard "active enrollments" card reads legacy paid enrollments (`/v1/enrollments/my`),
  so free (ADR-028) participations don't show there — pre-existing, not introduced here.
- **G2** Attended Exams shows PASS/FAIL and title only where the existing attempts endpoint provides them;
  exams/payments/session-view date filtering is client-side over existing endpoints (latest 100 attempts).
- **G3** No "Rotate QR" button in the UI (API exists, audited). QR resolve uses `GET ?payload=` (token may
  appear in access logs; low risk, rotatable).
- **G4** Removed students still appear in older consumers (legacy `GET /students`, communications picker, reports).
- **G5** Lesson content device checks may deny playback during a support session (no device session).
- **G6** Pre-existing failures unrelated to this change: `TeacherManagementIntegrationTest` (4, fail on `main`),
  `student-profile`/`logout`/`token-refresh` e2e (same set fails on `main`).
