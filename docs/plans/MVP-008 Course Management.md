# MVP-008 — Course Management — Module Plan

**GitHub issue:** #8 — https://github.com/mohanranaweera/lms-saas-platform/issues/8 (could not be fetched in
this session — the GitHub MCP server required an interactive OAuth authorization not available here, the same
limitation noted in the MVP-005 plan. This plan is grounded instead in the repo's internal, already-reconciled
requirements corpus, which is this project's normal source of truth for planning.)
**Branch:** `feature/course-management` (current branch, matches this module's naming convention)
**Spec source:** `docs/requirements/specifications/05-course-management.md`
**Backend domain:** `course-management` (new top-level domain — `com.lms.coursemanagement` — per the confirmed
domain list in `.claude/rules/architecture.md`).

This plan was produced by delegating to seven specialist agents in parallel (product-requirements-analyst,
solution-architect, database-architect, security-reviewer, qa-test-engineer, ui-ux-reviewer,
payment-ledger-specialist — the last included because course pricing is money-adjacent even though this
module does not process payments itself), each grounded in the existing requirements/architecture/ADR corpus
and the actual current repository state, then reconciled into one document — the same process used for
MVP-005. This is a **plan only** — no application files were created or edited. Several genuine gaps and
cross-document contradictions are flagged explicitly below as **open decisions**, not resolved. Per root
`CLAUDE.md`, this plan does not invent unresolved business decisions.

**Grounding note on current repository state**, verified directly (`find backend/src/main/java/com/lms`)
before delegating: the codebase currently contains **only** `com.lms.common` (shared kernel),
`com.lms.identityaccessservice` (auth, RBAC-2 permission engine, `DomainArea.COURSES` already defined in the
matrix), `com.lms.tenantmanagement` (tenant lifecycle/lookup), and `com.lms.usermanagement` (Staff Management
only — no Student/Teacher sub-packages yet). There is **no** `course-management`, `content-management`,
`enrollment-management`, `payment-management`, or `audit-log-management` package anywhere. Ten Flyway
migrations are applied (V1–V10, most recently `staff_profile`). This materially shapes §20 (Implementation
order) and §21 (Risks): `course-management` is a brand-new domain, but unlike Staff Management's blockers
(which were three *prerequisite* modules), Course Management's forward dependencies (`content-management`,
`enrollment-management`) are largely **not** blocking — this module can be built now, referencing them only by
opaque id, with one specific exception: the mandatory price-change audit requirement, which is genuinely
harder here than it was for Staff Management (see §16, §21 item 1).

---

## 1. Business goal

Let an approved Teacher (or Tenant Admin) build a sellable/learnable course — category, subject/stream/grade/
year, module and lesson structure, pricing, enrollment rules, access duration, and visibility — that starts in
`DRAFT` and, once explicitly published, becomes the tenant's inventory for enrollment and public storefront
listings. This is foundational MVP capability (`FR-CM-1` to `FR-CM-4`): without it, a tenant has staff and
teacher accounts but nothing to sell or teach, which blocks every downstream module (enrollment, payments,
content, exams) that assumes a course already exists. Course Management does not itself process payments or
activate enrollment — it produces the priced, structured inventory that `payment-management` and
`enrollment-management` will later act on, once those modules exist.

## 2. Roles and permissions

**Primary owner:** Teacher — creates and edits their own assigned courses (modules, lessons, day-to-day
content structure). Teacher access is **ownership-scoped**, not domain-flat: a Teacher may act on a course only
where `course.teacher_id` equals their own `tenant_user` id. Teacher/Teacher Assistant/Student are deliberately
absent from `identity-access-service`'s `DomainArea` permission matrix (`PermissionCheckServiceImpl`) — their
access model is ownership/assignment-scoped, which this module's service layer must enforce itself, the same
way `PermissionCheckServiceImpl`'s javadoc already documents for exactly this reason.

**Teacher Assistant** — PROVISIONAL, unratified (`docs/requirements/user-roles-and-permissions.md` §3): the
proposed default is create/edit modules/lessons/materials, but **not** publish/unpublish or change pricing.
This split must not be hard-coded as a real permission gate without separate sign-off (§21 item 2).

**Staff sub-roles — already-built matrix, reused as-is.** `DomainArea.COURSES` exists today in
`PermissionCheckServiceImpl` with grants transcribed from `docs/requirements/user-roles-and-permissions.md`
§2's "Courses" row:

| Institute Owner | Finance Staff | Course Coordinator | Student Support | Content Manager | Exam Manager | Attendance Operator | Read-only Auditor |
|---|---|---|---|---|---|---|---|
| V/C/E/D | V | V/C/E/A | V | V | V | V | V |

Every staff-initiated Course Management action must gate on `@permissionCheckService.hasPermission('COURSES',
...)`, matching `StaffController`'s existing `@PreAuthorize` pattern — this infrastructure is already built and
tested; Course Management is simply its second consumer.

**Decided (product owner, this session):** Course Coordinator's `APPROVE`/`CREATE_EDIT` grant on
`DomainArea.COURSES` does **not** extend to teacher reassignment — that action is **Tenant Admin only**, the
narrowest option, matching Staff Management's precedent of keeping the highest-leverage actions
Institute-Owner-only. This closes the gap the security-reviewer surfaced independently of
`open-decisions.md`'s already-tracked list — see §10, §15(a).

**Cross-cutting rules** (already fixed at the architecture level, restated per `user-roles-and-permissions.md`
§4): authorization is enforced server-side on every protected endpoint, independent of client display; every
permission check is evaluated for the resolved tenant context; Read-only Auditor never succeeds on a mutating
endpoint regardless of stale client UI.

## 3. Preconditions

- Acting Teacher is approved and assigned to an active tenant (spec §3). Teacher Management (Module 4) itself
  does not exist as a built domain yet — "approved Teacher" today means a `tenant_user` row with
  `role = 'TEACHER'` and `status = 'active'`, read via `UserProvisioningApi.findTenantUserSummaries`. There is
  no richer Teacher profile/approval-workflow entity to check against yet (see §21 item 3).
- Tenant identity is resolved from the trusted authenticated session context (`TenantContext`), never a
  client-supplied value — for both authenticated (Teacher/staff) and anonymous (public storefront) requests,
  the latter resolved from subdomain per `docs/architecture/multi-tenancy.md` §1.6.
- Acting staff user holds the required `DomainArea.COURSES` grant for the action being attempted.

## 4. User flows

### Normal flow (per spec §4)
1. Teacher opens `My Courses`, selects "New Course."
2. Multi-step Course Builder: category, subject/stream/grade/year, pricing, enrollment rules, access duration,
   visibility (draft/private/public), prerequisites (prerequisites themselves are FR-CM-5, Phase 2 — the
   builder step exists at MVP only insofar as it collects the MVP-scoped fields).
3. Teacher adds Modules & Lessons (structure only — material files are `content-management`'s, referenced by
   opaque id, not implemented here).
4. Course remains `DRAFT` — never visible on the public storefront until explicitly published, regardless of
   direct-URL/ID guessing.
5. Teacher (or Tenant Admin, per tenant policy) submits for review/publish. **Decided MVP scope:** since no
   tenant-configurable approval-policy mechanism exists anywhere in the requirements corpus, MVP publish is a
   direct, immediate action — a Teacher (if owner, no separate approval step) or a permitted staff role
   (`DomainArea.COURSES` `APPROVE`) sets `status = PUBLIC` directly. No "under review" intermediate state, no
   per-tenant policy toggle. If a configurable approval workflow is wanted later, that is new scope requiring
   its own design, not something this module retrofits.
6. On publish (`status = PUBLIC`), the course appears on the public storefront listing/detail for that tenant.
7. Teacher can later update pricing; a price change on a published course writes exactly one audit-shaped
   history entry via a single non-bypassable service method (§16).

### Alternative / edge-case flows
- **Slug/name uniqueness conflict** — backend 422/409 even after client-side Zod validation passes; frontend
  must handle it (spec §5).
- **Teacher reassignment** — only Tenant Admin/permitted staff sub-role may do this; a Teacher, including the
  course's own owner, attempting to change `teacherId` on their own course is rejected 403 server-side (spec
  §5, FR-CM-3). This requires the teacher-assignment field to be excluded from whatever payload a Teacher-role
  caller can submit — not merely hidden in the Teacher UI (§15(a)).
- **Cross-tenant direct-URL/ID manipulation** — a student (or anyone) from tenant A attempting to view/enroll
  in tenant B's course is rejected; for the storefront this means the request is scoped to the tenant resolved
  from the request's own subdomain, never a client-supplied tenant identifier.
- **Cloning / archiving / SEO** — FR-CM-5, Phase 2, out of scope (§6).
- **Course reviews** — FR-CM-6, Phase 2, out of scope; depends on `enrollment-management`'s verified-enrollment
  check, which doesn't exist yet (§6).
- **Empty states** — Teacher's "no assigned courses yet" (contact-tenant-admin guidance) is distinct from
  Tenant Admin's "no courses in this tenant yet" (creation CTA) — both must be independently reachable/testable
  (spec §8, UX review §3).

## 5. Acceptance criteria

Reconciled from the spec's own checklist (§8), FR-CM-1..4's acceptance column, and the product-requirements-
analyst review — deduplicated, MVP scope only.

1. Given a course is created, `tenant_id` is resolved from the authenticated session context (never
   client-supplied); the course defaults to `DRAFT`.
2. Given a course in `DRAFT` or `PRIVATE` status, it never appears on the public storefront listing or detail
   page regardless of direct-URL/slug/ID guessing — the storefront read path filters at the query level
   (`status = 'PUBLIC'`), not after fetch.
3. Given a price change on a course whose status is (or has ever been) `PUBLIC`, exactly one audit-shaped
   history entry is written (actor, tenant, course id, timestamp, before/after price) via one non-bypassable
   service method — see §16 for the exact mechanism and its honest MVP limitation.
4. Given a Teacher attempts to reassign their own course's teacher, the request is rejected 403 server-side,
   even though the same Teacher can otherwise edit the course.
5. Given tenant-scoped slug uniqueness, a duplicate slug within one tenant is rejected (409/422); the same slug
   in a different tenant succeeds.
6. Given a `course.teacher_id`, it can never reference a `tenant_user` row belonging to a different tenant —
   enforced as a composite-FK constraint violation, not merely a service-layer check.
7. Cross-tenant negative test on course CRUD, listing/search, pricing field, enrollment-rule fields, and
   teacher-assignment field — a tenant A actor addressing tenant B's course by id gets 403/404, never 200 with
   cross-tenant data.
8. Given the Read-only Auditor role, it may view course data but no mutating course endpoint succeeds for it.
9. Given a Teacher's "My Courses" view has zero assigned courses, it renders a distinct empty state (guidance
   to contact tenant admin) from Tenant Admin's "Course List" empty state (creation CTA).
10. The Course Builder multi-step form is fully keyboard-navigable: Tab order follows visual step order, step
    transitions move focus predictably, no control is reachable only by mouse.
11. Given a course clone action (**Phase 2, not built at MVP** — listed here only because the spec states the
    invariant explicitly): the new course must have zero enrollment/payment records and zero reviews copied
    over. Not testable/buildable at MVP; tracked for the Phase 2 plan.
12. Given a student without a verified enrollment attempts to submit a course review (**Phase 2, not built at
    MVP**): rejected. Not testable/buildable at MVP.

## 6. Out-of-scope items

- **FR-CM-5** (landing-page builder, trial/free-lesson support, bundles, prerequisites as a real gating
  mechanism, cloning, archive, SEO fields) — Phase 2 per `functional-requirements.md` and spec §10.
- **FR-CM-6** (course reviews — submission, moderation, storefront display) — Phase 2; depends on
  `enrollment-management`'s verified-enrollment check (doesn't exist) and has an unresolved domain-ownership
  question (`module-catalog.md` Open Q1: does `course-management` own the review workflow, or just the toggle,
  with `support-management` owning moderation?) — not resolved here, N/A to this module's MVP scope.
- **Live-class "sessions"** — FR-CM-1 names "modules, lessons, sessions," but "session" here means a live-class
  session, owned by `live-class-management`, which is entirely Phase 2. Not modeled at MVP — same
  phase-boundary resolution already used for the Zoom-recording-attachment contradiction in
  `06-lessons-and-materials.md`.
- **Material file upload/storage/MIME validation** — `content-management`'s scope, not built yet; this module
  stores only an opaque material-reference id, no upload endpoint.
- **Storefront rendering/composition itself (Module C)** — unowned/unratified per `module-catalog.md`;
  course-management supplies listing/detail *data* only (§21 item 5).
- **Tenant-configurable publish-approval policy** — no configuration mechanism exists anywhere in the
  requirements corpus (§21 item 4); MVP publish is a direct permission-gated action, not a configurable
  workflow.
- **A real Teacher-profile/approval entity** — Teacher Management (Module 4) hasn't been built; this module
  treats "Teacher" as an opaque `tenant_user` row with `role = 'TEACHER'`.

## 7. Domain model

`course-management` owns one aggregate root, `Course`, with two structural child entities and one history
entity — all facets of the same aggregate, not separate sub-packages (per the solution-architect review, this
differs from Staff Management's multi-account-type shape, which genuinely needed sibling packages).

- **`Course`** — belongs to exactly one `Tenant`; references its assigned teacher **by id only**
  (`teacher_id`, an opaque `tenant_user` id) — never a JPA entity association across the module boundary, per
  `.claude/rules/architecture.md`. Carries category/subject/stream/grade/year, pricing, enrollment-rule and
  access-duration fields, and `status` (`DRAFT`/`PRIVATE`/`PUBLIC` — this one column *is* both lifecycle and
  visibility; the spec never defines a separate axis, so a second column is not invented).
- **`CourseModule`** — belongs to exactly one `Course`, ordered by `sequence`.
- **`CourseLesson`** — belongs to exactly one `CourseModule`, ordered by `sequence`. No material content lives
  here — that's `content-management`'s table, referencing `course_lesson.id` by FK from its own side once that
  module exists.
- **`CoursePriceHistory`** — append-only, references the `Course` it belongs to and the acting `tenant_user`;
  see §16 for why this exists and what it is (and is not) a substitute for.

No `enrollment-management` or `payment-management` entity/table is referenced by FK anywhere in this domain —
this module stores rule *fields*, not activation state.

## 8. Database design

**Migration:** next real file is `V11__create_course_management_schema.sql` (highest applied is V10). No
forward dependency — only depends on already-applied `tenant` (V2) and `tenant_user` + its
`UNIQUE (tenant_id, id)` (V3), both confirmed present.

### `course`

```
id                     UUID PK (app-generated UUIDv7, no DB default)
tenant_id              UUID NOT NULL REFERENCES tenant(id)
teacher_id             UUID NOT NULL
name                   VARCHAR(255) NOT NULL
slug                   VARCHAR(160) NOT NULL
category               VARCHAR(100) NOT NULL
subject                VARCHAR(100) NULL
stream                 VARCHAR(100) NULL
grade                  VARCHAR(50) NULL
academic_year          VARCHAR(20) NULL
description            TEXT NULL
price                  NUMERIC(12,2) NOT NULL CHECK (price >= 0)   -- no currency column at MVP, see below
access_duration_days   INTEGER NULL CHECK (access_duration_days IS NULL OR access_duration_days > 0)
enrollment_rule        TEXT NULL   -- free-text notes only at MVP, no enforced semantics, see below
status                 VARCHAR(10) NOT NULL DEFAULT 'DRAFT'
                       CHECK (status IN ('DRAFT','PRIVATE','PUBLIC'))
created_at, updated_at, created_by, updated_by   -- Auditable convention

CONSTRAINT uq_course_tenant_id UNIQUE (tenant_id, id)         -- backs child composite FKs
CONSTRAINT uq_course_tenant_slug UNIQUE (tenant_id, slug)
CONSTRAINT fk_course_teacher FOREIGN KEY (tenant_id, teacher_id)
    REFERENCES tenant_user (tenant_id, id)
```

Indexes: `(tenant_id, status, created_at DESC)` — serves both admin status-filtered listing and the storefront
read (`status = 'PUBLIC'` is a leading-prefix match on the same index, no separate visibility index needed).
`(tenant_id, teacher_id, status)` — Teacher's "My Courses."

**`category`/`subject`/`stream`/`grade`/`academic_year` are free-text**, not FK'd to a catalog — no such
catalog table exists anywhere in this codebase yet (unlike `role`, V7), and building one now would be scope
creep with no requirement backing it. **Decided default:** free-text stays for MVP; a normalized catalog is a
future decision, to be made if/when duplicate-value drift or reporting needs actually surface it as a problem.

**`enrollment_rule` is free-text (`TEXT`), no CHECK-constrained enum — decided (product owner, this session):**
at MVP this field is plain descriptive notes (e.g. "max 30 seats, waitlist after"), with zero enforced
semantics anywhere in the backend. No rule engine reads or acts on this column at MVP; it exists purely so the
Course Builder has somewhere to capture the teacher's intent until a real enrollment-rules model is designed
against `enrollment-management` (which doesn't exist yet). Do not build any conditional logic keyed off this
column's contents.

**`price >= 0`, not `> 0`** — **decided default:** FR-CM-5's "trial/free lesson" direction suggests $0 courses
are a legitimate future case, and nothing in MVP scope forbids one; allowing `0` now costs nothing and avoids a
breaking constraint change later if a free/trial course is wanted before FR-CM-5 itself ships.

**No `currency` column — decided (product owner, this session):** a single implicit currency is assumed
(per-tenant or platform-wide, to be settled properly once `payment-management` is actually designed). Adding a
`currency VARCHAR(3)` column later is a non-breaking migration, so deferring it costs nothing now.

### `course_module`

```
id          UUID PK
tenant_id   UUID NOT NULL REFERENCES tenant(id)
course_id   UUID NOT NULL
title       VARCHAR(255) NOT NULL
sequence    INTEGER NOT NULL CHECK (sequence > 0)
Auditable columns

CONSTRAINT uq_course_module_tenant_id UNIQUE (tenant_id, id)   -- backs lesson FK
CONSTRAINT uq_course_module_sequence UNIQUE (tenant_id, course_id, sequence)
CONSTRAINT fk_course_module_course FOREIGN KEY (tenant_id, course_id)
    REFERENCES course (tenant_id, id)
```
Index: `(tenant_id, course_id, sequence)`.

### `course_lesson`

```
id          UUID PK
tenant_id   UUID NOT NULL REFERENCES tenant(id)
module_id   UUID NOT NULL
title       VARCHAR(255) NOT NULL
sequence    INTEGER NOT NULL CHECK (sequence > 0)
Auditable columns

CONSTRAINT uq_course_lesson_sequence UNIQUE (tenant_id, module_id, sequence)
CONSTRAINT fk_course_lesson_module FOREIGN KEY (tenant_id, module_id)
    REFERENCES course_module (tenant_id, id)
```
Index: `(tenant_id, module_id, sequence)`.

### `course_price_history` (append-only — see §16)

```
id               UUID PK
tenant_id        UUID NOT NULL REFERENCES tenant(id)
course_id        UUID NOT NULL   -- composite FK (tenant_id, course_id) REFERENCES course(tenant_id, id)
changed_by       UUID NOT NULL   -- composite FK (tenant_id, changed_by) REFERENCES tenant_user(tenant_id, id)
previous_price   NUMERIC(12,2) NOT NULL CHECK (previous_price >= 0)
new_price        NUMERIC(12,2) NOT NULL CHECK (new_price >= 0)
created_at       TIMESTAMPTZ NOT NULL   -- no updated_at; insert-only, no update/delete repository method
```
Index: `(tenant_id, course_id, created_at DESC)`.

Every composite FK above follows the `staff_profile` (V10) precedent exactly: a child row can never reference
a parent row (or `tenant_user` row) belonging to a different tenant than its own `tenant_id` — a schema-level
guarantee, not a service-layer-only check, per `database-architecture.md` §1.

## 9. Backend design

Package: `com.lms.coursemanagement`, following `modular-monolith.md` §3's convention:

```
com.lms.coursemanagement
|-- api          # flat, domain-root — CourseLookupApi (added even before a real
|                #   consumer exists, since content-management/enrollment-management
|                #   will need it once they're built — narrow read methods only:
|                #   isPublished(courseId), getTeacherId(courseId), price snapshot)
|-- course       # Course, CourseModule, CourseLesson, CoursePriceHistory all live
|   |-- web       #   here as ONE aggregate's facets — not split into sibling
|   |-- service   #   packages the way usermanagement's staff/student/teacher
|   |-- domain    #   sub-roles are, since they are genuinely separate account
|   `-- repository #  types and Course/Module/Lesson/pricing are not.
`-- config
```

**Cross-module calls, day one:**
- `identityaccessservice.api.PermissionCheckService` — gate every staff-initiated endpoint via
  `@permissionCheckService.hasPermission('COURSES', ...)`, re-checked inside the service layer too (defense in
  depth, mirroring `StaffService`'s pattern).
- `identityaccessservice.api.UserProvisioningApi.findTenantUserSummaries(Collection<UUID>)` — **already
  sufficient** to validate a candidate teacher id exists, belongs to this tenant, and has `roleCode() ==
  "TEACHER"` before assignment. No new identity-access-service method is required for the validate-on-assign
  case.
- **Missing today, needed if the Course Builder includes a teacher picker (not just an id field):** a
  "list/search teacher-role users for this tenant" method. `UserProvisioningApi` has no such method yet — per
  its own javadoc's extension policy, add one (e.g. `findTenantUsersByRole("TEACHER")`) only when this real UI
  need is confirmed, not speculatively now.
- `tenantmanagement.api.TenantLookupApi` — for the public storefront path, once identity-access-service's edge
  filter resolves `TenantContext` for anonymous requests from subdomain (confirm this is already wired for
  unauthenticated traffic before building the storefront controller — flag if not; see §21 item 5).

**Teacher-ownership enforcement (net-new pattern for this module, no existing precedent to copy):**
Every Teacher-initiated mutation must resolve the acting user's own `tenant_user` id from the authenticated
principal, then load the course via a tenant-scoped **and** teacher-scoped query
(`findByIdAndTeacherId(courseId, actingTenantUserId)`, still routed through `TenantAwareRepository`) — so
tenant isolation and ownership are proven by the same query, not layered as an afterthought. This check runs
for every mutating method (modules/lessons edit, pricing, publish/unpublish) and is re-verified in the service
layer independent of any controller-level guard, mirroring `StaffService`'s "defense in depth" javadoc.

**Teacher-reassignment isolation:** the teacher-assignment field must not be reachable through whatever
generic "edit course" payload a Teacher-role caller can submit. Recommend a **separate endpoint**
(`POST /courses/{id}/teacher`) gated on `DomainArea.COURSES` staff-role checks only — never reachable via the
Teacher-ownership path at all, closing the gap the security review flagged (§15(a)).

**Price-change isolation:** a single `CourseService.changePrice(courseId, newPrice)` method is the *only* code
path permitted to write the `price` column — re-checks permission/ownership, writes the new price and the
`course_price_history` row in the same `@Transactional` boundary, and publishes a `CoursePriceChangedEvent` for
the future `audit-log-management` consumer (§16). No bulk-update/native-query path may touch `price` outside
this method.

## 10. API contract

Draft only — must be finalized via the `review-api-contract` skill into `docs/api/course-management.md` before
implementation starts on either side. All responses use `com.lms.common.api.ApiResponse<T>`. No client-supplied
`tenant_id`, `teacherId`-as-self-service, or other trust-sensitive field is ever accepted for a Teacher-role
caller.

| Method + path | Auth | Notes |
|---|---|---|
| `POST /api/v1/courses` | Tenant Admin/permitted staff (`COURSES` `CREATE_EDIT`) or Teacher (self as owner — `teacherId` defaults to the caller, not client-suppliable by a Teacher) | `201` with created course, `409` on duplicate-slug-in-tenant, `400` on validation failure. |
| `GET /api/v1/courses` | Staff (`COURSES` `VIEW`) sees all tenant courses; Teacher sees only their own (`teacher_id = self`) | Paginated, filterable by status/category/teacher. Distinguishes zero-data vs. filtered-empty. |
| `GET /api/v1/courses/{id}` | Staff `VIEW`, or Teacher if owner | `403`/`404` uniform for cross-tenant or non-owned-by-Teacher id. |
| `PATCH /api/v1/courses/{id}` | Staff `CREATE_EDIT`, or Teacher if owner | Structural/pricing/enrollment-rule/visibility fields **except** `teacherId` — that field is rejected/ignored on this endpoint for every caller, staff included, to keep exactly one write path (see next row). |
| `POST /api/v1/courses/{id}/teacher` | **Tenant Admin only** (**never** Teacher, even the current owner, and never any other staff sub-role — decided this session) | Reassigns `teacher_id`; validates candidate via `UserProvisioningApi`. `@PreAuthorize` checks `Role.TENANT_ADMIN` directly (or an equivalent single-role check), not `DomainArea.COURSES`'s flat matrix, since Course Coordinator's `CREATE_EDIT`/`APPROVE` grant does not extend to this action. |
| `PATCH /api/v1/courses/{id}/price` | Staff `CREATE_EDIT`, or Teacher if owner | The one write path for `price`; writes `course_price_history` + publishes `CoursePriceChangedEvent` in the same transaction. |
| `POST /api/v1/courses/{id}/publish`, `/unpublish` | Staff `APPROVE`/`CREATE_EDIT` per the unresolved publish-policy question (§21 item 4), or Teacher if owner and no tenant policy requires approval | Status transition only; does not touch other fields. |
| `DELETE /api/v1/courses/{id}` | Tenant Admin only (`COURSES` `DELETE`) | |
| `POST /api/v1/courses/{id}/modules`, `PATCH/DELETE /api/v1/courses/{id}/modules/{moduleId}` | Staff `CREATE_EDIT`, or Teacher/Teacher-Assistant(-provisional) if owner | Structure only, no material content. |
| `POST /api/v1/courses/{id}/modules/{moduleId}/lessons`, `PATCH/DELETE .../lessons/{lessonId}` | Same as modules | |
| `GET /api/v1/public/courses` | Anonymous, tenant resolved from subdomain | Hard-filters `status = 'PUBLIC'` at the query level; no `tenantId` query param accepted. |
| `GET /api/v1/public/courses/{slug}` | Anonymous, tenant resolved from subdomain | `404` (generic "not found," no distinguishing copy) for DRAFT/PRIVATE-in-this-tenant, nonexistent, or wrong-tenant slug alike. |

## 11. Frontend screens

**Teacher** (`app/(teacher)/`): `Courses > My Courses` (`/teacher/courses`) — shared data-table + card
fallback, Status Chip, "New Course" CTA. `Course Builder` (`/teacher/courses/new`,
`/teacher/courses/[courseId]/edit`) — multi-step form (Step Indicator: category → subject/stream/grade/year →
pricing → enrollment rules/access duration → visibility), React Hook Form + Zod per step. `Module & Lesson
Editor` (`/teacher/courses/[courseId]/modules`) — nested reorderable list, with an explicit keyboard-reachable
Move Up/Down alternative to any drag-and-drop reordering.

**Tenant Admin** (`app/(tenant-admin)/`): `Courses > Course List` (`/tenant-admin/courses`) — shared data-table,
filter by category/status/teacher, no tenant column (single-tenant scope). `Course Detail / Approval`
(`/tenant-admin/courses/[courseId]`) — read/edit + publish/approve action + the dedicated teacher-reassignment
control (only surface here, never in the Teacher UI).

**Public** (`app/(public)/`): `Course Listing` (`/courses`) — published-only, tenant-scoped-by-subdomain.
`Course Detail` (`/courses/[slug]`) — through the same branding pipeline as the live storefront, never a
separate preview-only render path (spec §7).

**New `components/ui/` primitives needed** (current inventory: `alert`, `alert-dialog`, `button`, `card`,
`input`, `label`, `sheet`, `skeleton` — no `Table`/`Select`/`RadioGroup`/`Badge`/`Stepper` exists yet, and
MVP-005's flagged "shared data-table" need was never built): a shared responsive data-table (Course Management
would be its **second and third** consumer across My Courses and Course List, reinforcing the "build once"
case first raised in MVP-005), a Step Indicator (component-library-spec §7.1), `Select` (category/subject/
stream/grade/year), `Badge`/Status Chip (Draft/Private/Public — note `component-library-spec` §2.10 has an open
question about missing semantic color tokens for this vocabulary, flagged but not resolved here).

**Empty states** (spec §8 explicit AC): Teacher's "no assigned courses yet" (contact-tenant-admin guidance,
not necessarily a creation CTA, since course-teacher assignment for *existing* courses is admin-controlled)
must render distinct copy from Tenant Admin's "no courses in this tenant yet" (creation CTA) — not the shared
`EmptyState` component's default copy for both.

**Accessibility** (spec §8 explicit AC — Course Builder fully keyboard-navigable): Step Indicator uses
`aria-current="step"`; step transitions move focus to the new step's first field/heading; Back/Next reachable
via Tab in logical order; each field follows the Form Field Wrapper convention (`htmlFor`/`id`,
`aria-describedby`, `aria-required`); Module/Lesson reordering has a keyboard alternative to any drag-and-drop.

**Permission-denied/not-found states:** a Teacher navigating directly to another teacher's course-edit URL
gets a real, server-driven permission-denied state (not a hidden nav link only). The public Course Detail page
renders one generic "Course not found" state for both DRAFT-in-this-tenant and truly-nonexistent slugs —
mirroring the existence-leakage-avoidance pattern already used for Staff Detail in MVP-005.

## 12. Validation rules

- **Name/slug:** required; slug uniqueness is per-tenant, validated server-side (`409`/`422`) even after a
  client-side Zod pre-check.
- **Category:** required, free-text (no catalog exists — see §21 item 6).
- **Price:** required, `NUMERIC`, `>= 0`; no currency field at MVP (decided — single implicit currency).
- **Access duration:** optional positive integer, **decided unit: days** — absent = unlimited/lifetime access.
- **Enrollment rule:** free-text notes at MVP (decided — no enforced semantics); the frontend renders a plain
  text area, not a dropdown implying a settled enum.
- **Status/visibility:** one of `DRAFT`/`PRIVATE`/`PUBLIC`, explicit choice required at creation (default
  `DRAFT`) — no silent default to `PUBLIC`.
- **Teacher assignment:** never accepted as a Teacher-role-suppliable field on the general edit endpoint; only
  accepted via the dedicated staff-only reassignment endpoint, validated against `UserProvisioningApi` for
  same-tenant + `role = TEACHER` existence.
- **Module/lesson sequence:** positive integer, unique within its parent per tenant.

## 13. Error cases

| Case | Expected behavior | Status |
|---|---|---|
| Teacher attempts to reassign own course's teacher | Rejected server-side; field not present/ignored on the general edit path, dedicated endpoint not reachable by Teacher role | Settled (FR-CM-3), enforcement is this module's own to build |
| Duplicate slug within tenant | `409`/`422` with `fieldErrors: [{field: "slug", ...}]`, mapped to inline field error via React Hook Form's `setError` | Settled shape, matches existing `ApiError`/`FieldError` contract |
| Cross-tenant access (guessed/edited course id) | `403`/`404`, uniform generic copy regardless of which status came back | Settled requirement |
| Storefront request for DRAFT/PRIVATE/nonexistent slug | `404`, one generic "not found" message for all three cases | Settled requirement |
| Read-only Auditor attempts a mutation | Controls don't render; a stray direct API attempt fails `403` | Settled, enforcement via existing `DomainArea.COURSES` matrix |
| Teacher attempts to edit a course they don't own | `403`/`404` via the same ownership-scoped query used for legitimate access | **Net-new pattern, no existing precedent — must be built and tested explicitly** |
| Non-Tenant-Admin (including Course Coordinator) attempts teacher reassignment | `403` — decided this session as Tenant-Admin-only, not gated by `DomainArea.COURSES` | Settled |
| Price change on a `DRAFT` course (never published) | **Decided default:** `course_price_history` is written for every price change regardless of status — a status-conditional branch inside the one-true-write-path (§15(c)) is the kind of exception that erodes "single non-bypassable path" over time; writing unconditionally is simpler and strictly a superset of what spec §9 requires | Not audited via a separate mechanism — same `changePrice` write path |

## 14. Tenant-isolation rules

**Ownership and scoping.** `course`, `course_module`, `course_lesson`, and `course_price_history` are all
tenant-owned with `NOT NULL tenant_id`, composite indexes leading with `tenant_id`, and every cross-table FK
(including the teacher-assignment FK to `tenant_user`) expressed as a composite `(tenant_id, ...)` FK so a
cross-tenant reference is a constraint violation, not just a service-layer bug — mirroring `staff_profile`'s
(V10) precedent exactly.

**Mandatory cross-tenant negative tests, per endpoint** (every one needs a passing 403/404 test, never 200
with cross-tenant/filtered data, before that endpoint is considered done):
- Course create (teacher-id-from-another-tenant rejected at both service-check and constraint level).
- Course detail/edit/delete by id.
- Course listing/search (no filter/pagination/search combination leaks another tenant's rows).
- Price-change endpoint by id.
- Enrollment-rule field edits by id.
- Teacher-reassignment endpoint by id.
- Publish/unpublish status-transition endpoint by id.
- Module/lesson create/edit/delete, scoped through their parent course's tenant.
- Public storefront listing (must not accept a `tenantId` query param; subdomain-resolved only) and detail
  (same-slug-different-tenant must resolve to the requesting tenant's own course only, never leaking the
  other tenant's row, and a DRAFT/PRIVATE course in the *same* tenant as a guessed slug must still 404).

**Teacher-ownership as an additional, not alternative, layer.** Ownership scoping (`teacher_id = self`) is
layered on top of tenant scoping, never a substitute for it — a Teacher-scoped query must still run through
`TenantAwareRepository`, so a cross-tenant id is structurally invisible before ownership is even evaluated.

## 15. Security rules

**(a) Teacher-reassignment authorization — decided, Tenant-Admin-only.** `DomainArea.COURSES` grants Course
Coordinator `CREATE_EDIT` + `APPROVE`, but per the product owner's decision this session, that grant does
**not** extend to teacher reassignment. `POST /courses/{id}/teacher` must check `Role.TENANT_ADMIN` directly
(or an equivalent explicit single-role check), never `hasPermission('COURSES', ...)`'s flat matrix — the same
pattern `.claude/rules/payments.md` §8 already uses for "a domain-level grant is not automatically sufficient
for a specific higher-risk mutation." This needs its own explicit test proving Course Coordinator (despite its
`COURSES` `APPROVE` grant) gets 403 on this specific endpoint.

**(b) Ownership-scoping is this module's own responsibility to build.** `PermissionCheckServiceImpl`
deliberately excludes Teacher/Teacher Assistant/Student from its matrix — RBAC-2 will not enforce "Teacher may
edit only their own course" for you. The concrete check: resolve the acting user's own `tenant_user` id from
the authenticated principal (never a request parameter), then query
`findByIdAndTeacherId(courseId, actingId)` through `TenantAwareRepository` before any mutation, re-verified in
the service layer independent of the controller guard.

**(c) Price-change audit — single, non-bypassable write path.** No controller/service method other than
`CourseService.changePrice(...)` may write the `price` column; that method performs the permission/ownership
check, the price update, and the `course_price_history` insert in one `@Transactional` boundary. A future
bulk-price-edit admin tool must call this same method per-row, never a mass `UPDATE`/native query that would
bypass the history write.

**(d) Public storefront isolation.** The storefront controller must use the tenant already resolved from
subdomain by the shared edge-resolution mechanism (per `docs/architecture/multi-tenancy.md` §1.6) — it must
not re-derive tenant from the `Host` header itself, and must filter `status = 'PUBLIC'` at the query level so
a guessed id for a DRAFT/PRIVATE course in the *same* tenant also 404s.

**(e) Read-only Auditor — zero mutating path.** Already enforced structurally by `PermissionCheckServiceImpl`'s
startup-time invariant (Auditor can never hold a write-class grant); Course Management inherits this for free
via the existing `DomainArea.COURSES` matrix, but every mutating endpoint still needs its own explicit deny-path
test per §14.

## 16. Audit requirements

**Mandatory, and genuinely harder here than the analogous question was for Staff Management.** Unlike Staff
Management's audit question (left fully open, per its plan §16, because staff-account auditing was
*unconfirmed* against `.claude/rules/security.md`'s canonical list), course/session price changes are
**already** on that canonical mandatory list — this is not a decision Course Management gets to defer.
`docs/requirements/specifications/05-course-management.md` §9 requires "exactly one audit log entry... with
actor/before/after" for a price change on a published course.

**The honest MVP-scoped mechanism:** `audit-log-management` (the domain that would own the canonical,
compliance-grade audit table) does not exist in this codebase yet. Per `database-architecture.md` §5, audit
tables are event-consumers' own tables — `course-management` must not write directly into an `audit_log` table
it doesn't own, and must not invent one under a different name either. The compliant shape for what's buildable
now:
1. `CourseService.changePrice(...)` writes a `course_price_history` row (§8) — a domain-local record this
   module *does* own, giving the acceptance criterion's "exactly one entry with before/after" a real, tested,
   schema-backed answer today.
2. The same method publishes a `CoursePriceChangedEvent` in the same transaction, so `audit-log-management`
   can later persist its own canonical audit row from that event with zero rework to `course-management`,
   mirroring the event-driven pattern already fixed in `.claude/rules/architecture.md` §4.

**What this does not claim:** AC2/spec-§9's acceptance criterion cannot be *fully* end-to-end verified until
`audit-log-management` exists to actually consume the event — this plan states that limitation explicitly
rather than silently treating `course_price_history` as if it were the canonical audit log (§21 item 12).

## 17. Payment impact

**None.** Course Management stores a `price` field and enrollment-rule/access-duration data — it never creates
an `Order`, processes a `Payment`, or activates enrollment; those remain entirely `payment-management`'s and
`enrollment-management`'s ownership, neither of which exists yet. Confirmed independently by the
payment-ledger-specialist review against `.claude/rules/payments.md` and `docs/architecture/payment-ledger.md`:
no language in the spec implies course-management itself gates activation, and the price-change audit
mechanism (§16) is a domain-local history table, never conflated with `ledger-settlement-management`'s ledger
entries. One forward-looking flag for the Phase 2 clone feature (FR-CM-5, not built now): a naive deep-copy
clone implementation would be the likely way enrollment/payment history accidentally leaks into a cloned
course — worth a payment-ledger cross-check when that feature is actually planned, not designed against now.

## 18. Tests

### Unit tests (service layer, no Spring context)
- Price change on a published course writes exactly one `course_price_history` row with correct
  actor/tenant/before/after; a no-op (same-value) update writes none.
- Teacher-reassignment rejected when actor is the course's own owning Teacher, not an admin/permitted staff
  role — defense-in-depth service check.
- Draft/Private courses never returned from the storefront-scoped read method, called directly (not via HTTP).
- Clone-course logic (once Phase 2 exists) never copies enrollment/payment/review FKs — not built at MVP.

### Testcontainers integration tests (real Postgres)
- Tenant-scoped slug uniqueness: positive (same slug, different tenants), negative (duplicate within one
  tenant), and a genuine concurrent-race test (two threads, same tenant, same slug — exactly one 201, one 409,
  no 500), matching this repo's established rigor bar from Staff Management's email-uniqueness test.
- Composite-FK correctness: an insert with `teacher_id` from a different tenant than `tenant_id` fails at the
  constraint level, not just the service layer.
- `DomainArea.COURSES` role-grant tests, one explicit test method per staff sub-role (8 total, no single
  parameterized loop, matching `StaffManagementIntegrationTest`'s established pattern) — positive and negative
  per role.
- Teacher-ownership-scoped tests: Teacher A cannot edit/delete Teacher B's course even within the same tenant;
  Teacher A can edit their own (positive control).
- Price-change history integration test: real DB read confirms exactly one row per change.

### Mandatory cross-tenant negative tests
Every endpoint enumerated in §14 — create, detail, edit, delete, list/search, price-change, enrollment-rule
edit, teacher-reassignment, publish/unpublish, module/lesson CRUD, storefront listing, storefront detail (both
the same-slug-cross-tenant case and the DRAFT/PRIVATE-in-same-tenant case).

### Playwright, once frontend screens exist
Course Builder's full keyboard Tab-order traversal (not a visual/screenshot check); My Courses vs. Course List
empty states asserting distinct copy, not just "an empty state renders"; storefront draft-course-never-visible
via direct URL; cross-tenant storefront slug-collision resolving to each tenant's own course.

### Named follow-ups — explicitly blocked, not silently skipped
1. **Everything above is blocked until `course-management` itself is implemented** — no package exists yet.
2. Price-change audit-log *canonical* integration test (as opposed to the `course_price_history` test, which
   is buildable now) — blocked on `audit-log-management` not existing; can only be unit-tested via a mocked
   event consumer until then.
3. Clone "zero enrollment/payment/review copied" full integration proof — blocked on `enrollment-management`/
   `payment-management`/the review model; only unit-testable (mocked `api` interfaces) once Phase 2 clone is
   actually built (it isn't, at MVP).
4. Course-review-rejection test (spec §8, "student without verified enrollment... rejected") — Phase 2,
   blocked on `enrollment-management` and the unresolved review-workflow-ownership question.
5. Storefront E2E tests — blocked on the storefront page itself not existing yet (Module C composition, §21
   item 5).

## 19. Documentation changes

- `docs/architecture/database-architecture.md` — new `course`/`course_module`/`course_lesson`/
  `course_price_history` table entries, once §21 items 6/7/9/10's open modeling questions are resolved or
  explicitly accepted as MVP defaults.
- `docs/api/course-management.md` (new) — produced via `review-api-contract` from §10's draft before
  implementation begins on either side.
- `docs/requirements/open-decisions.md` — append the items newly surfaced by this plan that aren't already
  tracked at this level of detail: the Course-Coordinator-vs-teacher-reassignment authorization gap (§21 item
  1), the DRAFT-course price-change audit-scope question (§21 item 11), and the `course_price_history`-vs-
  canonical-audit-log limitation (§21 item 12).
- `docs/requirements/module-catalog.md` — if the storefront (Module C) composition question (§21 item 5) gets
  ratified during this module's implementation, update the "Cross-Cutting/Unowned Items" section to reflect
  that ratification rather than leaving it marked unowned.
- `docs/ui-ux/component-library-spec.md` — if new primitives (Step Indicator, Table, Select, Badge) are built,
  confirm they're documented there per this project's component-library convention.

## 20. Implementation order

1. **Backend domain scaffold** (`com.lms.coursemanagement`): `Course`/`CourseModule`/`CourseLesson`/
   `CoursePriceHistory` entities, `V11` migration, repositories extending `TenantAwareRepository`.
2. **Staff-role CRUD path** (the already-built `DomainArea.COURSES` matrix as the auth mechanism) — course
   create/read/update/delete, module/lesson CRUD, gated by `@permissionCheckService.hasPermission(...)`.
3. **Teacher-ownership path** — the net-new `findByIdAndTeacherId`-style ownership check, layered on top of
   step 2's tenant scoping; Teacher-reachable subset of endpoints only (excludes teacher-reassignment,
   excludes delete).
4. **Dedicated teacher-reassignment endpoint** — Tenant-Admin-only (`Role.TENANT_ADMIN` check, not
   `DomainArea.COURSES`), per the decided authorization scope in §10/§15(a).
5. **Price-change endpoint + `course_price_history`** — the single non-bypassable write path (§16), plus the
   `CoursePriceChangedEvent` publication (even with no consumer yet).
6. **Public storefront read path** — anonymous listing/detail, subdomain-tenant-scoped, `PUBLIC`-status-only —
   confirm the anonymous-request tenant-resolution filter is actually wired before building this step (§21
   item 5); this is a prerequisite check, not assumed.
7. **Backend tests** (§18) — unit, Testcontainers, cross-tenant negative — run and reviewed before frontend
   work starts, per `CLAUDE.md`'s "do not implement backend and frontend simultaneously" workflow rule.
8. **`docs/api/course-management.md`** via `review-api-contract`, before frontend implementation begins.
9. **Frontend**: shared data-table + Step Indicator + Select + Badge primitives first (cross-cutting, reused
   across all three portals' screens), then My Courses / Course Builder / Module & Lesson Editor (Teacher),
   then Course List / Course Detail-Approval (Tenant Admin), then public Course Listing/Detail.
10. **Frontend/E2E tests** (§18), then security/tenant-isolation/integration review, then documentation
    updates (§19), then one logical commit per `CLAUDE.md`'s workflow.

## 21. Risks and unresolved decisions

### Decided in this pass (engineering-judgment defaults, not business decisions — documented so they're
reviewable, not silently assumed)

- **Publish-approval policy** (was item 4): no tenant-configurable approval workflow at MVP; publish is a
  direct, immediate permission-gated action. See §4 step 5.
- **Public storefront (Module C) composition** (was item 5): `course-management` is the composition owner for
  listing/detail *data* — this is `module-catalog.md`'s own suggested resolution to its Open Question 3, not
  an invented answer. Still worth a lightweight, explicit sign-off before §20 step 6 (since `module-catalog.md`
  itself asks for confirmation "before backend module scaffolding begins"), but this plan proceeds on that
  basis rather than blocking on a separate ADR-weight process.
- **Category/subject/stream/grade/academic_year catalog** (was item 6): stays free-text for MVP; no normalized
  catalog table. Revisit only if duplicate-value drift or reporting needs make it a real problem.
- **`price >= 0`, not `> 0`** (was item 8): kept as designed in §8 — cheap to allow now, avoids a breaking
  constraint change if a $0 trial course is wanted before FR-CM-5 ships.
- **`access_duration_days` unit** (was item 10): days, as designed in §8/§12.
- **Price-change audit scope** (was item 11): `course_price_history` is written on every price change
  regardless of course status (not just published), per §13's updated error-case table — simpler than a
  status-conditional branch in the one non-bypassable write path, and strictly a superset of spec §9's literal
  requirement.
- **Teacher-reassignment authority scope** (product owner decision, this session): Tenant Admin only. See §10,
  §15(a), §20 step 4.
- **`enrollment_rule` field scope** (product owner decision, this session): free-text notes, no enforced
  semantics at MVP. See §8, §12.
- **Currency field** (product owner decision, this session): none at MVP — single implicit currency assumed.
  See §8.

### Still genuinely open — not this module's to resolve

1. **Teacher Assistant's provisional boundary** (`user-roles-and-permissions.md` §3, tracked system-wide in
   `open-decisions.md` §3, affecting six modules beyond this one) — not this module's to resolve unilaterally;
   inherited as-is. Do not build a hard permission gate against this split without separate, project-wide
   sign-off.
2. **`course_price_history` is not a substitute for the canonical `audit-log-management` audit row** — not
   fixable within this module: it satisfies spec §9's literal text today but is a domain-local table, not the
   platform's compliance-grade audit log, which doesn't exist yet. Communicate this limitation plainly at
   review time rather than reporting "audit requirement met" without qualification.
3. **No real Teacher-profile/approval entity exists yet** (Teacher Management/Module 4 unbuilt) — informational,
   not a decision to make now; if Module 4 later adds richer semantics than `tenant_user.status`, revisit §3's
   precondition check.
