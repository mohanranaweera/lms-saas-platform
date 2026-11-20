# Teacher Portal Course and Notification Parity

## Scope and authoritative implementations

Teacher course creation now uses Tenant Admin's current course workflow as functional reference:

- `frontend/src/components/courses/admin/course-create-dialog.tsx`
- `frontend/src/components/courses/admin/course-settings-fields.tsx`
- `frontend/src/components/courses/admin/schemas.ts`
- `frontend/src/lib/api/klass-admin.ts`
- `backend/src/main/java/com/lms/coursemanagement/course/web/CourseController.java`
- `backend/src/main/java/com/lms/coursemanagement/course/service/CourseService.java`

Teacher mode reuses the same fields, validation, request mapper, idempotency key, server-error mapping, tag/link option reads, and optional cover upload. It renders teacher assignment read-only as the current authenticated teacher for UX. `CourseService` remains authoritative and forces `AuthenticatedPrincipalHolder` teacher identity; client state never authorizes ownership.

Communications remains shared:

- `frontend/src/components/communications/broadcast-composer.tsx`
- `frontend/src/components/communications/broadcast-list.tsx`
- `frontend/src/components/communications/broadcast-detail.tsx`
- `frontend/src/lib/api/communications.ts`
- `backend/src/main/java/com/lms/notificationmanagement/service/BroadcastService.java`
- `backend/src/main/java/com/lms/notificationmanagement/service/AudienceResolver.java`

Teacher communications continue to use course audience only, owned-course checks, `IN_APP`/`EMAIL` only, own broadcast list/detail/delivery visibility, and idempotent `clientRequestId`. Teacher `/notifications` remains separate self-scoped Notification Center with read/unread and preferences.

## Gap matrix

| Feature | Tenant Admin reference | Teacher before change | Required/implemented change | Status |
|---|---|---|---|---|
| Course entry | `CourseListPanel` opens `CourseCreateDialog` | Legacy `CourseCreateForm` | Teacher `/teacher/courses/new` mounts shared current dialog in `mode="teacher"` | PASS |
| Course fields | Current settings workflow: teacher assignment, six creatable pricing models, price, enrollment fee, payment-valid days, commission, invite links, description, tags, linked courses, cover | Reduced multi-step basics/classification/pricing/access/visibility form | Shared current settings workflow reused; teacher assignment is fixed/read-only in UI and server-owned | PASS |
| Course payload | Current `KlassCourseCreateRequest` and `Idempotency-Key` | Legacy `CourseCreateRequest` mapper | Teacher submits current request mapper; no client-selected tenant; backend forces identity | PASS (backend tests existing) |
| Course success | Course created, optional cover upload, admin list/publish action | Redirected to modules | Teacher redirects to `/teacher/courses/{id}/edit?created=1`; enrollment remains free | PASS |
| Legacy admin create route | Current list dialog | `/tenant-admin/courses/new` old form remained active | Route now redirects to `/tenant-admin/courses`; list Add course remains authoritative | PASS |
| Teacher course ownership | Backend validates role/tenant/active teacher and owns course | UI did not expose current settings but backend protected ownership | No arbitrary teacher picker; server remains sole authority | PASS |
| Communications list/detail | Staff permission-scoped all-tenant broadcasts and delivery logs | Shared components already scoped teacher list/detail | No duplicate implementation; teacher routes keep shared components and backend scope | PASS |
| Communications compose | Staff audiences/channels/templates | Teacher mode already course-only and in-app/email-only | Retain explicit role restriction; recipient preview and send server-authoritative | PASS |
| Notification Center | Self recipient list/read state for current user | Teacher self list/read state | Keep `/teacher/notifications` separate from message management | PASS |

## Teacher-specific restrictions retained

- Tenant and authenticated teacher identity come from trusted backend context.
- Teacher cannot select arbitrary teachers, tenants, or administrator-only settings.
- Teacher can create/edit only assigned/owned course resources under backend authorization.
- Teacher communications target only currently enrolled students of an owned course.
- Teacher cannot send SMS/WhatsApp, use tenant-wide/payment-status/selected-student audiences, or inspect another sender's broadcasts.
- Cross-tenant IDs and another teacher's course must be rejected using existing backend 404/403 behavior.
- Course creation does not activate enrollment or paid access. Enrollment remains payment-independent; paid entitlements require verified payment/approved evidence.

## API and database impact

- API contract reused; no new endpoint, DTO, persistence model, or migration added.
- Course POST remains `/api/v1/courses`; current request fields are supplied by `klass-admin.ts` mapper and `Idempotency-Key`.
- Communications remains `/api/v1/communications/**`; Notification Center remains `/api/v1/notifications/**`.
- No database impact. Existing course, notification, delivery, audit, financial, and enrollment history remains untouched.

## Verification results

| Requirement | Result | Evidence |
|---|---|---|
| Shared Teacher course workflow wired | PASS | Teacher route uses `CourseCreateDialog mode="teacher"`; targeted ESLint passes |
| Admin workflow preserved | PASS | `CourseListPanel` still owns current Add course dialog; staff mode default unchanged |
| Teacher identity/ownership server enforcement | PASS | Existing `CourseManagementIntegrationTest` covers forced teacher identity and ownership/cross-tenant cases |
| Notification workflow parity | PASS | Shared composer/list/detail and existing `CommunicationsSecurityIntegrationTest` cover teacher restrictions, recipient scope, cross-tenant isolation, duplicate-send idempotency |
| Notification Center read/unread | PASS | Shared `NotificationList` and existing notification APIs remain unchanged |
| No external delivery during verification | PASS | No live delivery invoked; existing tests use local/test adapters |
| Targeted frontend lint | PASS | `npx eslint src/components/courses/admin/course-create-dialog.tsx src/app/(teacher)/teacher/courses/new/page.tsx src/app/(tenant-admin)/tenant-admin/courses/new/page.tsx` |
| Full frontend lint | BLOCKED | Existing unrelated error in `src/app/(tenant-admin)/tenant-admin/reports/student-payments/student-payment-workspace.tsx:106`; warnings elsewhere |
| Full TypeScript check | BLOCKED | Existing unrelated `frontend/e2e/student-behavior-report.spec.ts:6` type error |
| Backend focused test execution | PARTIAL | `CourseCoverIntegrationTest` (4/4), selected `CourseManagementIntegrationTest` ownership case (1/1), and `CourseTeacherListEditIntegrationTest` (1/1) passed; communications suites reached fixture setup but errored on pre-existing order-seeding `409 CONFLICT` in `CommunicationsTestSupport.seedFixture` |
| Running-app/browser inspection | BLOCKED | Runtime launch/browser walkthrough not completed in this pass |
| Full Teacher Playwright updates | BLOCKED | Existing course-builder specs target removed legacy form and need migration to current dialog selectors/fixtures; communications Playwright suite passes 10/10 |

## Remaining blockers

1. Update `frontend/e2e/course-builder-keyboard.spec.ts`, `teacher-course-edit.spec.ts`, and related create assertions for current dialog selectors and fields; add Teacher current-workflow fixture coverage.
2. Re-run communications integration suites after resolving their fixture-level order-seeding `409 CONFLICT`; course ownership/cover targeted cases already pass. Focused communications Playwright coverage passes (10/10), while course-create specs still target removed legacy selectors.
3. Resolve or separately track pre-existing full frontend lint and TypeScript errors before declaring full repository green; targeted parity files lint clean.
4. Launch local app and inspect Teacher/Tenant Admin create and communications screens at desktop/mobile/keyboard states.

## Completion status

**PARTIAL.** Implementation wiring and documented security/tenant boundaries pass static targeted checks. Runtime/browser, focused backend execution, and migrated full E2E verification remain blocked.
