# Klass parity round 2 — validation and remaining blocker

Plan: `docs/plans/klass-gaps-round2.md`. Branch: `feat/klass-course-enrollment-parity`.
Resumed validation: 2026-10-16. R2-SEC-H1 has an owner-approved conservative fix and passing targeted tests/re-review. **Full backend verification passed: 2651 tests, zero failures/errors/skips. Round-2 implementation and scoped validation are complete; no commit/push made.**

## Evidence

| Check | Result |
|---|---|
| Backend full verification | After the separately approved backend security step: `mvnw.cmd -o verify` BUILD SUCCESS, 323 Surefire reports, **2651 tests**, zero failures/errors/skips (16:36 minutes). Before the backend step, existing 2649-test reports were inspected rather than re-run during frontend-only work. |
| TypeScript | `npm exec --prefix frontend -- tsc --noEmit -p frontend/tsconfig.json` passed. |
| Lint | `npm --prefix frontend run lint` passed (exit 0). |
| Production build | `npm --prefix frontend run build` passed before and after frontend review fixes. |
| R2-T6a regression | Six specs: klass-admin, klass-student-enrollment, tenant-admin-courses, teacher-course-edit, tenant-admin-course-create, klass-composer-tags; Chromium, one worker: **43/43 passed**. |
| R2-T6b + adjacent regression | `klass-media-library.spec.ts` **23/23** and `lesson-media.spec.ts` **6/6**, Chromium, one worker: **29/29**, zero skipped/flaky. |
| Local browser | Uploads-first library, counts/filter/search and upload dialog rendered against local backend; Escape restored trigger focus; phone-width page had no horizontal overflow. Tall course list menu preserved scrollY and trigger coordinates on open/close. No data mutations performed in this manual check. |

The first media test run had one setup failure: the Tenant Admin picker test visited the modules table instead of the content editor. The helper now uses `/tenant-admin/courses/{id}/content`; the final 29-test run passed. All new E2E cases use synthetic mocked data. Mocked 403/404 cases prove UI behavior, **not** backend isolation.

## Frontend review fixes

- Media-file list and summary cache keys include authenticated viewer identity/role/principal kind; queries wait for a tenant session. Backend remains the authorization authority; no auth architecture or contract changed.
- Open reserves a blank tab synchronously, removes its opener, applies no-referrer, then navigates only after the backend returns a fresh signed URL. Blocked popups report recovery guidance; signing failures close the blank tab.
- Library file picker cannot close during attachment and uses the correct role dashboard on backend permission denial.
- Shared-video watch-time copy distinguishes attachment from the separate global-policy mutation. Empty preserves existing rules; failed policy saving retains the successful attachment and offers Done without promising unauthorized later editing.
- Focused read-only re-review found no correctness bugs in these fixes.

## R2-SEC-H1 — HIGH: attachment confers another uploader's video settings authority

Location: `backend/src/main/java/com/lms/videoaccessmanagement/service/VideoAssetService.java`, `requirePolicyAuthorization`, attached branch (lines 247–253 at review).

Verified by tracing the code, not a new exploit/test execution:
1. Teacher A uploads an unattached READY video.
2. Teacher B lists and attaches that institute video to B's lesson (allowed by R2-D2).
3. B can call video policy/watch-time-limit PUT because the attached branch checks only ownership of every attached course, not the video's uploader.
4. B can change A's protection settings. The uploader check exists only in the unattached branch.

This conflicts with R2-D2's owner/admin mutation requirement. Do not treat passing current tests as security approval.

### Approved backend-only fix (2026-10-16)

Owner selected **“Fix conservatively (Recommended)”** in the current session. Implemented uploader ownership for every Teacher/TA policy mutation **and preserved the existing every-attached-course guard**. Staff/admin permission behavior is unchanged. No schema/API/auth/enrollment changes. The uploader is still denied while another teacher's course uses the video; allowing uploader-only global mutation in that situation requires owner clarification, not silently dropping the prior guard.

Red/green evidence: `attachingAnotherTeachersVideoDoesNotGrantPolicyAuthority` first failed with expected 404 / actual 200. After the fix, the four targeted suites passed **46/46**, zero failures/errors/skips: MediaLibraryIntegrationTest (13), MediaFileLibraryIntegrationTest (11), VideoWatchTimeLimitIntegrationTest (3), VideoPlaybackSessionIntegrationTest (19).

The regression asserts: A uploads → B attaches → B policy and watch-time-limit denied with stored settings unchanged; uploader remains denied while foreign-course attachment exists; admin allowed; after detach uploader allowed. Existing cross-tenant video settings tests pass. Focused Java/security re-review passed with no verified defects. Full `MAVEN_OPTS=-Xmx512m ./mvnw.cmd -o verify -DargLine="-Xmx1536m -XX:+UseSerialGC"` passed: **2651 tests, zero failures/errors/skips**, BUILD SUCCESS, 16:36 minutes. Evidence: `backend/target/r2-security-verify.log` and 323 Surefire XML reports (local generated artifacts).

## Isolation evidence gap closed

Added `MediaFileLibraryIntegrationTest.studentCannotDownloadAnotherTenantsLibraryBackedMaterial` in the separately approved backend step. An authenticated student with valid local paid access requests both a foreign course/lesson/material hierarchy and the foreign material under their own lesson: both return 404 with null data, no URL/object key, unchanged download count, and the stored file intact. It passes in the 46-test targeted run. The pre-existing unpaid/non-enrolled evaluator tests remain passing.

## New Media video chooser correction (2026-10-16)

Owner reported that New Media's File picker offered documents/images only. The owner then refined the correction: **no destination question; upload to the active tab, including tenant internal storage**. This supersedes the earlier selector implementation/tests.

- Uploads New Media accepts videos alongside documents/images. Videos use existing `useUploadVideo` -> multipart `/videos`, capped at the existing 25 MiB; filename-derived title is explained. Documents/images retain `/media-files`.
- DynTube and named YouTube tabs open the existing `MediaUploadPanel` with the exact current destination. No destination selector or fallback. Archived remains read-only.
- Internal pending uploads cannot dismiss the modal/double-submit; success refreshes video data and View videos selects the Videos filter. Hosted uploads keep signed staging/cancel and now block tab switching through completion/cancellation.

The final refined version passed **65/65** Chromium tests (18 routing/lifecycle, 23 file-library, 24 provider-library), zero skipped/flaky, including active hosted-upload tab-switch blocking and release after cancellation. TypeScript, lint and final production build passed. Focused React/TS/security/UX re-review found no remaining defects after the hosted busy-tab fix. Local browser confirms internal destination with no selector and DynTube fixed-tab form. No real external provider upload; synthetic mocked transfer tests only. No backend/schema/API changes.

## Media Library filters, pagination and popup consistency (2026-10-16)

- Uploads **All types** now shows documents/images and INTERNAL videos together in labeled sections. Each source keeps its own 24-item server-side pagination; this is not a globally sorted combined page. Provider videos remain in their respective tabs. One Uploads search applies to both sources; Documents/Images/Videos limit the visible source, and video status remains video-specific.
- Pagination already existed in both APIs and UIs. Regression fixtures now actually filter and slice pages, with tests for >24 files/videos, independent Next/Previous controls, filter resets and recovery after deleting the final file on a later page.
- New media consistently opens the existing responsive dialog shell on Uploads, DynTube and each YouTube integration. Destination is fixed by the active tab; provider-specific form fields/limits and Archived's read-only behavior remain intact. Active requests cannot dismiss the dialog; background tabs are inaccessible while the modal is open. Cancellation remains explicit.
- No backend, database, approved API contract, tenant isolation or upload authorization changes. Mocked denials prove UI handling only, not new backend isolation evidence.
- Validation: **78/78 Chromium tests passed**, one worker, zero skipped/flaky, across `klass-media-library`, `media-library`, `klass-new-media-video` and `lesson-media`. Final TypeScript check, targeted ESLint (zero warnings/errors), and production build passed. Local evidence: `frontend/media-ui-regression.log` and `frontend/media-ui-build.log` (generated, ignored files). The first runs exposed an incorrect `aria-modal` test assumption and an old inline-upload test clicking background tabs; both tests were updated to verify actual modal focus confinement and guarded cancellation/close behavior.
- Browser coverage includes all four upload destinations, Escape/focus return, a 390px full-screen sheet without horizontal overflow, paginated mixed libraries, provider-specific payloads and pending-upload guards. All transfers use synthetic mocked data; no real provider uploads or production access.
- Direct code review checked conditional query/render behavior, pagination bounds/reset/recovery, dialog busy-state release and preservation of existing authenticated API calls/server capability handling. No remaining correctness defect was identified. Independent React/TypeScript/code review agents could not run because configured models returned `model_not_found`; independent agent review remains unverified, not passed. No commit or push was made.

## Course edit session/resource workflow completion (2026-10-17)

- The remaining **Modules & lessons** editor is now **Modules & sessions** and shares the full Session form used by the Klass Sessions table. Inline rename sends the complete current session shape, module rename keeps optional price, and module/session Move controls use atomic `PUT .../order` endpoints instead of metadata-replacing PATCH swaps.
- Existing From/To values are visible in the tenant timezone, editable and clearable. New sessions still require a start; optional end-time behavior remains 23:59. Untouched instants retain their original seconds/milliseconds, and rename/reorder preserve payment type, price, resource type, description, label and billing month.
- Document automatically opens a document-only paginated Media File picker. Player Video opens a READY-only paginated video picker. Images remain a supplementary option. Multiple/mixed file, image, video and HTTPS link drafts attach after metadata save through existing APIs; Cancel writes no draft attachments.
- Partial addition failure leaves the saved session and successful resources intact. Retry re-reads server lists and submits only outstanding additions. Existing attachments stay on type change; explicit remove detaches only the session reference and never deletes the library asset.
- Link-only types show the relevant HTTPS field. Zoom Integration Name, Mark Attendance When Joining and Require Registration are visible but disabled with an honest parked-integration explanation. Zoom Recording shows an unavailable-library state and cannot be saved; the documented alternative is upload-as-AOC-Player-Video. No Zoom provider work was resumed.
- The separate workspace route for scheduled provider meetings is labelled **Live classes**, while course content uses Sessions. Session material actions deep-link to `#session-{id}`.
- No backend/schema/migration/API/auth/tenancy/payment changes. Focused backend suites passed **74/74**. Final session editor suites passed **24/24**, Tenant Admin workspace **19/19**, and the combined pre-review course/media run passed **54/54**. Targeted ESLint and TypeScript checks are clean; production build succeeded. Review agents initially failed `model_not_found`; a later fork review ran and its findings (label ambiguity, portal fallback, nullable availability, retry success mode) were fixed and reverified.

## Session resource refinement (2026-10-19)

- Resource Type is the sole primary-resource control. `IMAGE` is persisted through V89 and catalog/API output; `SECURE_VIDEO` displays as **Player Video** without changing secure playback contracts. Document, Image and Player Video open centered focus-trapped Media Library dialogs on desktop and full-screen dialogs on mobile, each constrained to the appropriate server-side filter.
- The duplicate bordered **Session resources** panel and its Add buttons were removed. Compact pending/saved attachment rows remain directly below Resource Type with Change/Add, explicit Remove and existing partial-save retry behavior. Multiple attachments remain supported; no type change silently removes one.
- Billing month input/validation was removed. For a Monthly/Covered session, backend derives the stored period from `startsAt` in `GENERAL.default_timezone` (UTC fallback); all other combinations store null. Client billing values are compatibility-only and ignored. V89 is additive; historical session/order/payment/entitlement/ledger rows remain untouched. Changing a course pricing model does not bulk-rewrite existing session associations; sessions rederive when edited, preserving historical rows.
- Additional settings now labels `lessonLabel` as **Session**. Link-only/Zoom-gated behavior stays unchanged; Zoom Recording remains unavailable and no integration credentials/provider calls were added.
- Student verification: enrolled locked/not-started/expired metadata remains visible in the course tree; material payloads stay redacted until server authorization; HIDDEN content is anti-enumeration 404; image downloads reuse the same lesson evaluator and tenant-scoped library guard as documents. Frontend student suites passed **68/68**; backend media/access suites passed **73/73**, including an explicit IMAGE library-backed student access test.

## Scope and disposition

Frontend implementation/validation finished before the separately owner-approved backend-only security fix began; the two were not implemented simultaneously. The backend step changes one service guard and two integration test classes. No migrations, payment/enrollment rules, production systems, or real records were changed. Existing uncommitted first-round and round-2 work is retained. No commit/push was made. Governance confirmation and first-round owner decisions remain as documented in the earlier acceptance/plan files.
