# Klass parity — round 2 gaps (media library, course edit, tags, row actions)

Owner-reported gaps (2026-10-14) after the first Klass parity round
(`docs/plans/klass-enrollment-payment-parity.md`). Same branch `feat/klass-course-enrollment-parity`.
References: `docs/references/Klass gaps/` (owner screenshot + view-only capture of demo.klass.lk Media Library).
Design: solution-architect KLASS-G-ARCH. IDs **G1–G5**.

## Goal
- **G1** Media Library manages documents and images stored in our object storage (not only videos), in the
  Tenant Admin and Teacher portals, Klass-style (counts, Uploads tab, search, type filter, New Media upload,
  cards with Open / Rename / Delete); library files can be attached to sessions without copying bytes.
- **G2** Teacher assignment on course edit uses the multi-select teacher picker (no UUID typing).
- **G3** Course edit mirrors the Add Course modal (incl. Enrollment Fee, Cover); Pricing model / Billing
  configuration / Billing period UI and the "Fees & Billing" tab are hidden.
- **G4** Tags editable on the course (multi-add); tag filter chips in the student catalog (exists) and the
  payment composer course selector; slug never shown (server-generated).
- **G5** Course row actions in an accessible overflow menu: Modules, Edit, Edit class schedule, Roster,
  Publish/Unpublish, Archive/Unarchive, Delete. Tute Management omitted until that module exists.

## Owner decisions (2026-10-14)
| ID | Decision |
|---|---|
| R2-D1 | Library is institute-wide. Tenant Admin manages all items; teachers see/open/attach all institute items and rename/delete only their own uploads. |
| R2-D2 | Videos follow the same rule (widens the previous teacher video visibility "own + attached to own courses"); edit/delete/settings of a video stay owner-or-admin. |
| R2-D3 | Upload types unchanged: PDF, PNG, JPEG, GIF, UTF-8 text, 25 MB, content-sniffed. No Office formats. |
| R2-D4 | Tute Management omitted from menus until built. |
| R2-D5 | Course edit hides Pricing model / Billing configuration / Billing period and the Fees & Billing tab; monthly price edits keep recording billing-period history server-side; history retained. |
| R2-D6 | Additive `tags` on `GET /api/v1/checkout/courses` items (owner asked for tag filtering in payment selection). |
| R2-D7 | Teacher-list edits validate only newly added teachers (a suspended existing co-teacher must not block edits). |

## Database impact — V88 (`V88__media_file_library.sql`)
New tenant-owned `media_file` (kind DOCUMENT/IMAGE/OTHER, title, original_filename, content_type, size_bytes,
object_key unique, sha256, uploaded_by, audit columns, soft delete `deleted_at/deleted_by`), composite tenant
FKs, partial indexes on live rows. `material.media_file_id` (composite FK), `ck_material_storage_key_required`
re-created to accept library-backed materials, `ck_material_media_file_shape` (library materials keep
`storage_object_key` NULL), unique live attachment per lesson. No backfill of existing materials.

## API impact (additive)
- `GET/POST /api/v1/media-files`, `GET /api/v1/media-files/summary`, `PATCH/DELETE /api/v1/media-files/{id}`,
  `GET /api/v1/media-files/{id}/open-url` (staff only; students 403; cross-tenant 404; teacher rename/delete of
  others' items 404; delete of an attached file 409 `MEDIA_ATTACHED`). Items carry server-computed
  `canEdit/canDelete`, `attachedLessonCount`, image `thumbnailUrl`.
- `POST .../lessons/{lessonId}/materials` gains optional `mediaFileId` (attach without copy; duplicate 409).
- `GET /api/v1/media` teacher visibility widened per R2-D2.
- `GET /api/v1/checkout/courses` items gain `tags:[{id,name}]`.
- Course edit uses existing `PATCH /api/v1/courses/{id}`; the new form always sends current values of fields
  it doesn't show (PATCH with `name` replaces legacy fields).

## Security / tenant impact
Library downloads by students only through lesson materials, still gated by the lesson access evaluator
(ADR-028) — no student library endpoint. Detaching or deleting a lesson material never deletes a shared library
object. Upload sniffing/size unchanged. Audit: `media_file.uploaded/renamed/deleted`. All queries tenant from
`TenantContext`. No change to tenancy, auth, ledger, enrollment activation or deployment.

## Ownership
| Owner | Files |
|---|---|
| database-architect | `V88__media_file_library.sql` only |
| integration-engineer | `ObjectStorageApi` signed-URL overload with content-type/disposition + its S3 implementation |
| backend-springboot-engineer | `contentmanagement/library/**`, `MaterialService`/`MaterialController`/`MaterialRepository`, `videoaccessmanagement` teacher visibility, `CourseService.updateTeachers`, checkout `CheckoutCourse.tags` + `CourseLookupApi` tags read, tests |
| frontend-nextjs-engineer | `components/media/**`, `lib/api/media-files.ts`, `components/courses/materials-section.tsx` + library picker, `components/courses/admin/**` (shared course fields, edit form, overflow menu), course workspace tabs, tenant-admin course overview/settings, teacher course edit, composer offers panel, `lib/api/klass-*.ts` |
| qa-test-engineer | `backend/src/test/java/com/lms/klass/**`, `frontend/e2e/klass-*.spec.ts` |

## Tests required
Cross-tenant 404 on every media-file endpoint and attach; students 403; teacher can't rename/delete others' files
(or videos) but can list/open/attach; delete attached → 409; sniff/oversize rejection with no storage write;
attached library file download passes through the lesson evaluator (unpaid denied, non-enrolled 404);
detach never deletes the library object; duplicate attach 409; edit form PATCH preserves hidden legacy fields;
coordinator primary-teacher change 403; suspended existing co-teacher doesn't block edits; monthly price edit
appends a billing period; checkout course tags tenant-scoped; Playwright: library grid/counts/upload/rename/
delete-blocked, attach from session, edit round-trip, row menu by keyboard, composer tag chips.

## Task table
| Task | Owner | Depends | Status |
|---|---|---|---|
| R2-T1 V88 migration | database-architect | — | done (FlywayMigrationIntegrationTest pass, 89 migrations, Hibernate validate OK) |
| R2-T2 object storage signed-URL overload | integration-engineer | — | done (uncompiled; backend compiles + adds InMemoryObjectStorageApi test override) |
| R2-T3 backend library + attach + video visibility + teachers fix + checkout tags | backend | T1, T2 | done (full verify 2649/0/0; 404 for unusable attach; library rejects markup/SVG; unattached video policy owner-only) |
| R2-T4 frontend G2–G5 (course edit, tags, row menu, composer chips) | frontend | contract | done (tsc clean, lint 0 errors; no browser test yet) |
| R2-T5 frontend G1 media library + session picker | frontend | T3 contract | done (2026-10-16: viewer-scoped file caches, synchronous reserved-tab Open, role-correct picker permission link, pending-close guard, accurate shared-video limit partial-success UX; types/lint/build pass) |
| R2-T6 QA backend + Playwright | qa | T3–T5 | R2-T6a re-run 2026-10-16: 43/43; R2-T6b: new klass-media-library 23/23 + adjacent lesson-media 6/6, all mocked UI contracts. Tall-list row-menu browser check: scroll and trigger position unchanged on open/close. Explicit library-backed cross-tenant student download test added/passing in owner-approved backend follow-up (46/46 targeted suites). See docs/qa/klass-round2-acceptance.md |
| R2-T7 security + UX review, fixes | security-reviewer, ui-ux-reviewer | T6 | frontend review fixes complete; R2-SEC-H1 conservative backend fix approved/implemented, red reproduced then 46/46 targeted tests green, security re-review PASS. Full backend verify BUILD SUCCESS: 2651 tests, zero failures/errors/skips (2026-10-16). Round-2 scoped validation complete; no commit/push. See QA record |
