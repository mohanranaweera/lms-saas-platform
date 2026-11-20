# STORE-01 Teacher Storefronts

Decision record: `docs/adr/ADR-037-teacher-storefronts.md`. Migration: `V126__teacher_storefronts.sql`.

## 1. Goal
Opt-in public homepage + catalog per teacher at `<teacher-slug>.<tenant-subdomain>.<platform-domain>`,
selling tenant courses through the tenant's existing auth, cart, checkout, payments and orders.

## 2. Roles
- **Tenant Admin** (and Operator/Manager when granted `TEACHER_STOREFRONT_MANAGE`): enable/disable,
  set slug, list/unlist/publish products.
- **Teacher**: edits existing profile fields (photo, bio) only; read-only view of own storefront
  status/URL.
- **Student / anonymous visitor**: browses storefront, registers/signs in, checks out.

## 3. Acceptance criteria
See ADR-037 decisions 1-10 and the task brief; key edges: unknown/disabled host -> not-found
page (never tenant catalog); retired slug -> 308 to current host while enabled; out-of-scope
detail 404, out-of-scope quote/order 422; same slug allowed in two tenants; slug reused by
another teacher in the same tenant -> 409; reserved/invalid slug -> 400; purchased access kept
after disabling.

## 4. Database
V126 (additive): `teacher_storefront`, `teacher_storefront_slug`, `teacher_storefront_product`,
`student_order_line.storefront_id`. All tenant-owned, composite same-tenant FKs, tenant-leading
indexes. No change to existing rows.

## 5. Backend
- `common.tenant.StorefrontContextHolder` (request-scoped, set only by `TenantResolutionFilter`,
  cleared in `finally`; holds storefrontId + teacherId; empty on tenant hosts).
- `TenantLookupApi.resolveByHost` unchanged for one-label hosts; new method returning the
  tenant plus the optional storefront label for two-label platform hosts.
- New `usermanagement.teacher.storefront` package: entities, repositories, `TeacherStorefrontService`
  (admin ops + audit log), `TeacherStorefrontLookupApi` (resolve by tenant+slug, listed course
  ids, origin for a storefront).
- `CoursePublicService` and `CheckoutComposerService` read the storefront context and scope.
- Order line creation writes `storefront_id`; PayHere return origin per ADR-037 decision 8.
- New `Capability` key `TEACHER_STOREFRONT_MANAGE` (Tenant Admin static matrix: granted;
  Operator/Manager configurable, default not granted; Teacher: not granted).

## 6. API contract
All under the site's own host (`/api` same-origin). Envelope `ApiResponse`, existing error format.

Public (anonymous):
- `GET /api/v1/public/site-context` -> `200 {kind: "TENANT"|"STOREFRONT", storefront: null |
  {slug, teacherId, displayName, photoUrl, bio, host}}`.
- On a storefront-shaped host that doesn't resolve: every `/api/**` (except paths already excluded
  from the filter) -> `404 {code: "STOREFRONT_NOT_FOUND"}`; retired slug of an enabled storefront
  -> `404 {code: "STOREFRONT_MOVED", details: {host: "<current host>"}}`.
- `GET /api/v1/public/courses[...]`, `/tags`, `/{slug}`: unchanged shapes; scoped on storefront hosts.
- `GET /api/v1/public/teachers[/{id}]`: on a storefront host only that teacher.

Checkout (authenticated student, unchanged shapes):
- `GET /api/v1/checkout/courses`, `/offers`, `POST /quote`, `POST /orders`: on a storefront host
  any out-of-scope course -> `422 {code: "STOREFRONT_PRODUCT_OUT_OF_SCOPE"}`.

Admin (`TEACHER_STOREFRONT_MANAGE`; Teacher may GET own via `/me`):
- `GET /api/v1/teacher-storefronts?page&size` -> page of `{teacherId, teacherName, storefront: null|
  StorefrontAdmin}`.
- `GET /api/v1/teacher-storefronts/{teacherId}` -> `StorefrontAdmin = {id, teacherId, slug,
  enabled, previewUrl, publishedUrl, previousSlugs: string[], products: [{courseId, courseTitle,
  courseSlug, published, sortOrder}], assignableCourses: [{courseId, title, slug}]}` (404 if the
  teacher is not in the caller's tenant).
- `PUT /api/v1/teacher-storefronts/{teacherId}` body `{slug, enabled}` -> `200 StorefrontAdmin`
  (creates on first call). `400 STOREFRONT_SLUG_INVALID|STOREFRONT_SLUG_RESERVED`,
  `409 STOREFRONT_SLUG_TAKEN`.
- `PUT /api/v1/teacher-storefronts/{teacherId}/products` body `{items: [{courseId, published,
  sortOrder}]}` (full replace) -> `200 StorefrontAdmin`; `422 STOREFRONT_COURSE_NOT_ASSIGNED`
  for a course the teacher isn't assigned to (or another tenant's course).
- `GET /api/v1/teacher-storefronts/me` (Teacher) -> `StorefrontAdmin` without
  `assignableCourses`, or `404` when none.
- `previewUrl`/`publishedUrl` built from the tenant origin template with the slug label prefixed.

## 7. Frontend
- Root/(public) layout calls `site-context`; on `STOREFRONT` renders the teacher homepage (name,
  photo, bio, tenant theme) + catalog of published products using the shared course cards;
  `/courses` and `/courses/[slug]` already go through the scoped API.
- Not-found page for `STOREFRONT_NOT_FOUND`, 308 redirect for `STOREFRONT_MOVED`.
- Login/register keep working; `returnTo` validated as same-host relative path.
- Tenant Admin: Teachers -> Storefront page (toggle, slug, preview/published URL, product picker).
- Teacher profile: read-only storefront status + URL.
- Student portal on a storefront host (ADR-037 decision 9, amended 2026-11-20): banner "Showing
  your courses from <teacher>'s page." + "See all your courses" link to the tenant site; course
  lists are filtered by the backend, the course page's Exams tab asks per course (`courseId`).

## 8. Security / 9. Tenant impact
Tenant resolved first, storefront second, both server-side from trusted host. Storefront is not
an auth role. All repositories filter by `TenantContextHolder`. Admin endpoints gated by the new
capability. Cross-tenant and cross-storefront negative tests required.

## 10. Tests
Backend (Testcontainers): resolution (unknown/disabled/retired/same slug two tenants), slug
rules/collisions/reserved, product assignment validation incl. cross-tenant course id, scoped
public list/detail/count, checkout 422, order line attribution, entitled access after disable,
permission matrix (Teacher denied admin PUT), existing tenant/custom-domain regressions.
Frontend: unit tests for returnTo validation; Playwright storefront home/catalog, not-found,
admin page; desktop + mobile.

## 11. Docs
ADR-037, this plan, `docs/architecture/multi-tenancy.md`, `docs/api`, operations runbook (DNS/TLS).
