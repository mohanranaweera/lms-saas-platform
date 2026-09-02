# MVP-014 Teacher Dashboard — Module Plan

Issue: [#14](https://github.com/mohanranaweera/lms-saas-platform/issues/14) — "Module 14: Teacher
dashboard." Stories `TDASH-1` (Teacher home/overview) and `TDASH-2` (My Courses list),
`docs/planning/product-backlog.md` lines 862-902.

Status: **Implemented and reviewed.** Frontend-only implementation complete per §20; reviewed by
`solution-architect`, `security-reviewer`, `database-architect`, `qa-test-engineer`, and
`ui-ux-reviewer` (no Critical/High findings; two Medium documentation gaps and several Low items
identified and closed — see `docs/ui-ux/screen-map.md` and `docs/ui-ux/component-library-spec.md`
§2.2 for the resulting updates). Originally produced by reading the issue, root/backend/frontend
`CLAUDE.md`, `.claude/rules/{architecture,backend,frontend,ui-ux}.md`, the confirmed-shipped
`docs/plans/MVP-007 Teacher Management.md` and `docs/plans/MVP-008 Course Management.md` plans,
`docs/api/course-management.md`, `docs/planning/{product-backlog,dependency-map}.md`,
`docs/requirements/open-decisions.md`, `docs/ui-ux/screen-map.md`, and the actual current
repository state (backend `com.lms.usermanagement.teacher`/`com.lms.coursemanagement` packages,
frontend `app/(teacher)/**`, `lib/api/courses.ts`, `components/students/stat-card.tsx`,
`components/courses/course-list-table.tsx`) — not assumed from the issue text alone. Framed
against `product-requirements-analyst`, `solution-architect`, `database-architect`,
`security-reviewer`, `qa-test-engineer`, and `ui-ux-reviewer` perspectives.
`payment-ledger-specialist` was **not** engaged — this module touches no order, payment, ledger,
or enrollment-activation code path anywhere (§17).

## Grounding note — this is not a greenfield module, and the issue's own text is stale in two places

Reading the actual shipped code (not just the issue and backlog) surfaced two things a literal
reading of the issue would get wrong:

- **"TCH-2" is not a `user-management` endpoint — it does not exist as `GET /teachers/.../courses`
  anywhere in this codebase.** `com.lms.usermanagement.teacher.web.TeacherController` (MVP-007,
  shipped) exposes only `POST /teachers`, `GET /teachers`, `GET /teachers/{id}`,
  `POST /teachers/{id}/approve`, `POST /teachers/{id}/reject` — all Tenant-Admin/Course-Coordinator
  operations on *other* teachers, gated by `DomainArea.TEACHERS`. There is no
  teacher-reads-their-own-assigned-courses method anywhere in `user-management`. Instead, the
  actual "TCH-2" contract shipped as part of **Module 8 (Course Management, MVP-008)**:
  `GET /api/v1/courses` (`CourseController` → `CourseService.listCourses`) already returns, for a
  caller whose `principal.role() == "TEACHER"`, only courses where `course.teacher_id` equals the
  caller's own id — server-enforced, confirmed by reading `CourseService.listCourses` and
  `docs/api/course-management.md` directly, not inferred from the issue's "reuse TCH-2" language.
  This plan treats `GET /api/v1/courses` as the real, already-shipped TCH-2 contract this module
  must reuse, per `dependency-map.md`'s own hard-dependency line ("TDASH-1 | AUTH-2, TCH-2 |",
  "TDASH-2 | TCH-2 |") — satisfied today, not blocked.
- **No `course_teacher_assignment` table exists.** The issue's and backlog's database-impact text
  ("reads `course`/`course_teacher_assignment` (CRS-1/CRS-3)") describes an M8-planning-stage
  design (`docs/plans/MVP-008 Course Management.md` §7 originally scoped a separate assignment
  table) that was **not** what shipped. The actual `V11__create_course_management_schema.sql`
  puts `teacher_id` directly on `course` with a composite FK to `tenant_user` and a dedicated
  index `idx_course_tenant_teacher_status ON course (tenant_id, teacher_id, status)` — this *is*
  the "`(tenant_id, teacher_id)` index from CRS-3" the backlog's tenant-impact line refers to, just
  on `course` itself rather than a separate join table. This plan builds against the real shipped
  shape, not the stale table name.
- **Two of the three frontend surfaces this module touches already exist, partially built**, from
  MVP-007's scaffold and MVP-008's frontend slice (mirroring the exact situation
  `docs/plans/MVP-013 Student Dashboard.md`'s own grounding note found for the Student portal):
  - `frontend/src/app/(teacher)/teacher/dashboard/page.tsx` ("Overview," TDASH-1) is still the
    original static `EmptyState`-only placeholder from the application-foundation scaffold — this
    module's main new-content work.
  - `frontend/src/app/(teacher)/teacher/courses/page.tsx` ("My Courses," TDASH-2) **already
    fetches the correct, already-backend-filtered data** (`useCourses()` → `GET /api/v1/courses`)
    and **already has the correct, distinct empty state** ("No assigned courses yet" + a
    create-course CTA and contact-tenant-admin guidance — already differentiated from the Student
    "no active enrollments" copy, satisfying that specific AC today). What it does **not** have is
    a mobile-first Course Card grid: it renders through `CourseListTable`, a shared
    table-on-`md`+/card-fallback-below-`md` component also used by Tenant Admin's Course List —
    the admin-heavy-surface responsive pattern (`.claude/rules/ui-ux.md` §5), not the
    card-based-at-every-width pattern §5 mandates for "Student and **Teacher** dashboards, course
    pages" as consumer-style surfaces. This is the same category of gap MVP-013 found and fixed
    for the Student "My Courses" page (its own §11), applied here to Teacher's page specifically
    — **`CourseListTable` and Tenant Admin's Course List page are not touched by this plan.**
  - `frontend/src/components/students/stat-card.tsx` (`StatCard`, built for MVP-013 SDASH-1) is
    exactly the component TDASH-1 needs, and MVP-013's own §21 item 5 explicitly flagged that a
    second role-group dashboard reusing it (rather than duplicating it) would be the trigger to
    relocate it out of `components/students/` per `.claude/rules/frontend.md`'s "extract to shared
    only when ≥2 role groups need it" rule. **Teacher is that second role group — this module is
    the trigger MVP-013 predicted, not a new decision.**

## 1. Business goal

Give an approved Teacher a single, backend-filtered, mobile-first landing view of their own
assigned-course portfolio (Overview) and a reliable, correctly-scoped Course Card list of exactly
the courses they own (My Courses) — both composed entirely from data that already exists and is
already teacher-scoped and tenant-scoped in `course-management`. This is explicitly a
**read/presentation-composition module, not a new domain**: it introduces no new backend endpoint,
no new table, and no new business rule — its only real work is (a) building the Overview page's
statistic-card content from data the app already fetches correctly, and (b) reworking My Courses'
presentation from a table-first admin pattern to a genuine mobile-first Course Card grid, without
changing what data is fetched or how it is authorized.

## 2. Roles and permissions

**Teacher-only module**, ownership-scoped exactly as `course-management` already enforces it — no
RBAC-matrix change (`PermissionCheckServiceImpl`, `DomainArea` enum) is needed or proposed.

| Role | Access |
|---|---|
| Teacher (`tenant_user.role = 'TEACHER'`, approved per MVP-007's `teacher_profile.approval_status = APPROVED`) | Views own Overview and My Courses — own-tenant-scoped, own-`teacher_id`-scoped data only, enforced today by `CourseService.listCourses`/`CourseAccessGuard`, not by anything this plan adds. |
| Teacher Assistant (issue/backlog: "PROVISIONAL") | **No behavior built for this role.** Per `docs/requirements/open-decisions.md` §3 and MVP-007's plan §6, Teacher Assistant's entire permission boundary is unratified and no creation/approval path for this role exists anywhere in the shipped system — there is no operable Teacher Assistant account today to test or design against. This module does not special-case it; if/when Teacher Assistant onboarding is built, that module's own plan decides whether it reuses this one's screens. |
| Every other role (Student, Staff sub-roles, Tenant Admin, Platform Admin) | No intended access to `/teacher/dashboard` or `/teacher/courses`. If reached, `GET /api/v1/courses` still enforces its existing rule (Teacher-ownership branch does not apply; a Staff-role caller without `DomainArea.COURSES` `VIEW` gets `403` server-side; a Student gets `403` — Student holds no `COURSES` grant at all) — see §13/§15 for the one related, pre-existing gap this plan flags but does not silently fix as a "resolved decision." |

**Cross-cutting rule** (already enforced platform-wide, restated per `.claude/rules/ui-ux.md` §1's
Teacher bullet): "Course lists... must render only backend-authorized assignments. Do not fetch a
full/unfiltered dataset and filter it client-side." This module's entire security posture is
*preserving* that already-true property while reworking presentation — see §15.

## 3. Preconditions

- MVP-002 (Authentication Foundation) — Teacher login/session issuance — shipped (`AUTH-2`).
- MVP-007 (Teacher Management) — Teacher account creation + Tenant-Admin approval gate
  (`teacher_profile.approval_status`, `tenant_user.status`) — shipped. A `PENDING`/`REJECTED`
  teacher cannot log in at all (`403 USER_SUSPENDED`), so every session that reaches this module's
  pages is already a login-gate-cleared, approved Teacher.
- MVP-008 (Course Management) — `course` table with `teacher_id`, `GET /api/v1/courses`'s
  Teacher-ownership-scoped listing, `CourseAccessGuard` — shipped and is this module's sole data
  source.
- `live-class-management`, `exam-management`, and `attendance-management` **do not exist in the
  backend yet** (confirmed — no `com.lms.livelassmanagement`/`com.lms.exammanagement`/
  `com.lms.attendancemanagement` packages exist). The backlog's own TDASH-1 business-outcome line
  names "upcoming sessions" and "pending actions" — this precondition gap makes both
  unbuildable at MVP; §6 makes the consequence explicit, mirroring MVP-013 §3/§6's identical
  treatment of the same three missing domains for the Student Overview.

## 4. User flows

### 4.1 Teacher Overview (`/teacher/dashboard`, TDASH-1)

1. Teacher logs in (or navigates to) `/teacher/dashboard`.
2. Page fetches, via the existing `useCourses()` hook (no new call): `GET /api/v1/courses`,
   already teacher-ownership-scoped server-side, already fetched as a single `size=100` page (the
   same call `/teacher/courses` already makes — see §9 on why this plan does not introduce a
   second, separately-filtered read for the Overview).
3. Statistic cards render, computed client-side from the already-fetched, already-scoped result
   (pure display arithmetic, no business logic, mirroring `StatCard`'s existing MVP-013 contract):
   total assigned courses, published (`PUBLIC`) count, and draft/private (not-yet-published) count.
4. Zero assigned courses → the issue's own required empty state: "no assigned courses yet" with
   guidance to contact the tenant admin — reusing the exact copy already shipped on
   `/teacher/courses` today (not a newly invented string, so the two screens agree).
5. A short "recent courses" section (top N courses by `updatedAt`, matching MVP-013's own
   "recent courses" precedent) links into `/teacher/courses`; each row shows name, category, and
   status — no per-course workspace deep-link (module/lesson editor) is added at the Overview
   level (that already exists at `/teacher/courses/[courseId]/modules` and is reached via My
   Courses, not the Overview, unchanged).
6. The read fails → `ErrorState` with Retry (existing `QueryStateBoundary` behavior), never a raw
   stack trace or blank page. A non-Teacher/expired session → the existing 401 → redirect-to-login
   path, unchanged.

### 4.2 My Courses (`/teacher/courses`, TDASH-2 — presentational rework of the existing page)

1. Teacher navigates to My Courses (nav item already exists — `TeacherNav`).
2. `useCourses()` (existing, unchanged) fetches the teacher's own courses — no new backend call.
3. Each course renders as a **card** (not a table row) in a responsive grid, mirroring
   `app/(public)/courses/page.tsx`'s existing `CourseCard` markup convention (already establishes
   this exact grid pattern in this codebase): course name, category,
   status badge (`CourseStatusBadge`, existing component, `DRAFT`/`PRIVATE`/`PUBLIC` — color is
   never the only signal, per `.claude/rules/ui-ux.md` §4), price, and the existing Edit/Modules
   action links (unchanged).
4. The existing search box, category filter, and status filter (client-side, over the fetched
   page) are preserved unchanged — this is a presentational rework of how each result renders,
   not a removal of already-working, already-correct filter UX.
5. Zero courses → the already-shipped, already-correct empty state (unchanged copy/CTA).
6. Filtered-to-zero (a search/filter combination matching no course) → the already-shipped,
   already-correct distinct "no courses match your filters" state with a clear-filters action
   (unchanged).
7. A read fails → `ErrorState` with Retry (already correct via `QueryStateBoundary`, unchanged).

## 5. Acceptance criteria

Restating the issue's own criteria, made concrete against the actual shipped mechanism (§ Grounding
note):

- [ ] Teacher logs in → Overview shows only the teacher's own backend-filtered assigned-course
      data — verified server-side by `GET /api/v1/courses`'s existing Teacher-ownership branch
      (`CourseService.listCourses`) — no endpoint this page calls accepts a `teacherId` parameter
      from a Teacher-role caller (it is silently ignored per the existing contract).
- [ ] Overview is mobile-first, single-column stacking below `sm`/`md`, card-based — new work,
      built to `.claude/rules/ui-ux.md` §5's consumer-style-surface rule.
- [ ] Zero assigned courses → Overview renders the distinct "no assigned courses yet" empty state
      with guidance to contact the tenant admin (issue's own AC, reusing the already-shipped copy
      from `/teacher/courses`).
- [ ] My Courses results are limited server-side to the teacher's own assignments (shares
      `GET /api/v1/courses`'s teacher-ownership branch — already true today, not newly built).
- [ ] Reaching a course outside the teacher's assignment by id (e.g.
      `/teacher/courses/{other-teachers-course-id}/edit`) → `403`/`404` — already true today via
      `CourseAccessGuard.requireCourseAccess`, unchanged by this plan; re-verified, not re-built
      (§18).
- [ ] My Courses renders as a mobile-first Course Card grid, single column below `sm`, multi-column
      at `lg` — new work (§ Grounding note, §11).
- [ ] Zero assigned courses on My Courses → the already-shipped distinct empty state (guidance to
      contact tenant admin), never the Student "no active enrollments" copy — already true today,
      re-verified as a regression check (§18), not newly built.
- [ ] Every async operation (initial load, retry) exposes `aria-busy`/`aria-live`/`role="alert"`
      per `.claude/rules/ui-ux.md` §4 — already true via `QueryStateBoundary`/`ErrorState`
      (verified, not re-implemented), and re-verified to still hold once each page's content
      renders as a card grid rather than a placeholder/table.

## 6. Out-of-scope items

Per the issue's own explicit scope (`TDASH-1`/`TDASH-2` only) and this plan's own grounding
findings:

- **"Upcoming sessions" and "pending actions" tiles** (named in the backlog's TDASH-1 business
  outcome, not in the issue's own scope/AC bullets) — `live-class-management`, `exam-management`,
  and `attendance-management` do not exist in the backend yet (§3). No stat card, alert, or read
  call for any of these is built in this module; when those domains ship, adding their Overview
  tiles is that future module's own scope, not a retrofit obligation on this plan — identical
  treatment to MVP-013 §6's attendance/exam exclusion for the Student Overview.
- **A new `GET /teachers/.../courses` (or any other new) backend endpoint.** The issue's own
  Security requirement explicitly forbids "a parallel, less-filtered query path for list-view
  convenience" — building any second endpoint that re-implements `GET /api/v1/courses`'s
  teacher-ownership filter would itself violate that requirement (parallel, not "less-filtered,"
  but still a duplicated, independently-maintained filter implementation the issue's own rule is
  written to prevent). This module reuses the existing endpoint from both screens, unchanged.
- **Any change to `CourseListTable` or Tenant Admin's Course List page** (`/tenant-admin/courses`)
  — that component/page remains the correct, intentional admin-heavy-surface pattern for its own
  consumer; this plan builds a separate, Teacher-page-local Course Card component instead of
  editing the shared one (§11).
- **Course Roster, Live Classes, Attendance, Exams, Reports, Support, Profile & Availability**
  (all named in `docs/ui-ux/screen-map.md`'s Teacher Portal section) — none of these domains exist
  yet; out of scope by construction, not a scoping choice this plan is making.
- **Teacher Assistant-specific behavior** — no operable Teacher Assistant account exists anywhere
  in the shipped system (§2); not built here.
- **Pagination redesign of `GET /api/v1/courses`** — remains the existing single-large-page
  (`size=100`) fetch with client-side filtering; an "approved API contract" change-controlled area
  this plan does not touch (flagged forward in §21, mirroring MVP-013 §6's identical flag for
  `/enrollments/my`).
- **Fixing the pre-existing `(teacher)/layout.tsx` missing-`RouteGuard` gap** as a business
  decision — see §15/§21 for why this plan recommends, but does not treat as self-evidently
  authorized, adding the same `RouteGuard kind="tenant"` wrapper Student/Tenant-Admin layouts
  already have.

## 7. Domain model

**No new entity, no new table, no new DTO shape.** This module reads existing `Course` rows
(via `GET /api/v1/courses`, `CourseResponse`) exactly as already modeled and returned. The only new
artifacts are frontend-only: a relocated `StatCard` component (moved, not redesigned — see §11)
and a new Teacher-page-local `CourseCard` presentational component (new markup, reusing
`CourseResponse` field-for-field, no new backend shape).

## 8. Database design

**None.** No migration is added by this plan. The one read this module relies on
(`GET /api/v1/courses`, teacher-ownership-scoped) already uses the already-shipped, already
correctly-indexed schema:

- `course` — `idx_course_tenant_teacher_status ON course (tenant_id, teacher_id, status)` (V11)
  already serves exactly this module's access pattern (tenant + own-teacher + optional status
  filter) — no new index is needed, per the Grounding note's correction of the issue's stale
  `course_teacher_assignment` reference.

## 9. Backend design

**None — this module makes zero backend changes.** `GET /api/v1/courses`'s existing
Teacher-ownership branch (`CourseService.listCourses`, `CourseAccessGuard`) already is the
"TCH-2" contract both TDASH-1 and TDASH-2 need (§ Grounding note); this plan explicitly does
**not** add a `com.lms.usermanagement.teacher` read method, a `course-management`-owned
"dashboard summary" endpoint, or any other new `api`/REST surface. This is the concrete
implementation of the issue's own instruction not to introduce "a parallel, less-filtered query
path" — the only way to guarantee no divergence from TCH-2's contract is to call the literal same
endpoint from both screens, which is what both `useCourses()` call sites in §11 do.

If a future module needs richer Overview data (e.g. real upcoming-session counts once
`live-class-management` ships), that is that future module's own narrow `api`-extension decision
to make against its own owning domain — not something this plan pre-builds speculatively.

## 10. API contract

**No new endpoint, no contract change.** Both screens call the same, already-documented endpoint:

| Method + path | Doc | Purpose in this module |
|---|---|---|
| `GET /api/v1/courses` | `docs/api/course-management.md` | Teacher-ownership-scoped course list — both Overview's stat-card/recent-courses data and My Courses' card grid, via the existing `useCourses()` hook. No query parameter this module sends differs from what `/teacher/courses` already sends today. |

No entry is added to `docs/api/course-management.md` — the issue's own text explicitly confirms
this ("No new `docs/api` entries").

## 11. Frontend screens

- **`app/(teacher)/teacher/dashboard/page.tsx` (rebuild — currently a static placeholder)**:
  Overview per §4.1. Reuses `useCourses()` (already imported pattern from
  `teacher/courses/page.tsx`) and the relocated `StatCard` (see below). New, page-local
  computation only (active/published/draft counts, recent-courses slice) — no new hook, no new
  component beyond what's listed here.
- **`StatCard` relocation**: move `frontend/src/components/students/stat-card.tsx` →
  `frontend/src/components/dashboard/stat-card.tsx` (a new, role-neutral shared location for
  dashboard-only primitives — not `components/ui/`, since it is still dashboard-specific, not a
  general-purpose shadcn-style primitive; not `components/students/`, since Teacher is now a
  second consumer). Update the one existing import in
  `app/(student)/student/dashboard/page.tsx` accordingly. No prop/behavior change — pure move,
  per `.claude/rules/frontend.md`'s "extract to shared only when ≥2 role groups need it" rule,
  which this module is the concrete trigger for (§ Grounding note).
- **`app/(teacher)/teacher/courses/page.tsx` (rework — see grounding note)**: replace this page's
  `CourseListTable` usage with a new, Teacher-page-local `CourseCard` grid component — a
  `<ul className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">` of card `<li>`s,
  mirroring `app/(public)/courses/page.tsx`'s existing `CourseCard` markup convention exactly,
  extended with `CourseStatusBadge` and the existing Edit/Modules action links. Co-located under
  `components/courses/` alongside the existing `CourseListTable`/`CourseStatusBadge` (not
  `components/teachers/`, since nothing here is Teacher-specific data — it is a second, sibling
  rendering of the same `CourseResponse[]` `CourseListTable` already renders, kept in its existing
  shared-by-course-data location). `CourseListTable` itself, and Tenant Admin's Course List page,
  are **not modified**. All existing search/category/status client-side filter logic on this page
  is preserved unchanged, only its results-rendering call site swaps from `<CourseListTable
  courses={filtered} .../>` to `<TeacherCourseCardGrid courses={filtered} .../>`.
- **Shared empty-state copy** (retroactively documented — implemented but not named at planning
  time): `frontend/src/lib/copy/teacher-empty-states.ts` exports the "no assigned courses yet"
  title/description/action strings as constants, imported verbatim by both
  `teacher/dashboard/page.tsx` and `teacher/courses/page.tsx`, so the copy-parity requirement in
  §4.1 step 4 is structurally guaranteed (one source of truth) rather than two independently
  typed matching strings that could drift.
- Both pages: loading (`LoadingState`, `aria-busy`), contextual empty state (`EmptyState`/inline
  empty-state markup, unchanged/reused copy), error state with Retry (`ErrorState`,
  `role="alert"`), and permission-denied handling via `QueryStateBoundary`'s existing
  `permissionDenied={{ dashboardHref: "/teacher/dashboard" }}` pattern (already wired on
  `/teacher/courses`; add the equivalent on the rebuilt `/teacher/dashboard`) — all via the
  existing shared state components, not reimplemented per page.
- No nav change — `TeacherNav`'s "Dashboard" and "Courses" entries already point at both routes.
- **Recommended, flagged fix bundled into this module** (not a new business decision — a
  consistency fix mirroring an already-established pattern): add
  `<RouteGuard kind="tenant" loginPath="/login">` around `app/(teacher)/layout.tsx`'s
  `DashboardShell`, matching `(student)/layout.tsx` and `(tenant-admin)/layout.tsx`, both of which
  already have it and Teacher's layout does not. This is UX-only (avoids a loading-shell flash of
  Teacher-portal chrome before a real backend 401/403 is possible) — the actual authorization
  remains `GET /api/v1/courses`'s existing server-side check regardless of this guard's presence,
  per `RouteGuard`'s own documented "not the authorization mechanism" contract. See §15/§21.

## 12. Validation rules

None — both screens are pure reads with no form, no mutation, no user-supplied input beyond
navigation and the already-existing client-side search/filter controls (unchanged).

## 13. Error cases

| Scenario | Response |
|---|---|
| Unauthenticated/expired session on either screen | `401` → `QueryStateBoundary`'s existing redirect-to-login (`loginPath="/login"`), unchanged pattern |
| A Staff/Student/Tenant-Admin session somehow reaches `/teacher/**` | **Pre-existing gap, not newly introduced or newly fixed by this plan**: `(teacher)/layout.tsx` currently has no `RouteGuard`, unlike Student/Tenant-Admin (§11 recommends adding one for UX consistency). Even without it, the backend still independently rejects the underlying `GET /api/v1/courses` call for a non-Teacher, non-`COURSES`-VIEW-holding caller (`403`), surfaced via `QueryStateBoundary`'s `permissionDenied` state — so no cross-role data ever renders, only a possible page-shell flash before that 403 resolves |
| `GET /api/v1/courses` called with zero assigned courses | `200` with an empty page (`content: []`) — not an error; both screens render their own empty states from this, unchanged |
| The read fails (network/5xx) | `ErrorState` with Retry, unchanged existing behavior on My Courses; identically wired on the rebuilt Overview |
| A Teacher reaches another teacher's course by id (`/teacher/courses/{id}/edit`, `/teacher/courses/{id}/modules`) | `403`/`404` uniform, via the already-shipped `CourseAccessGuard.requireCourseAccess` — unchanged, re-verified not re-built (§18) |

## 14. Tenant-isolation rules

- Every read this module's frontend performs (`GET /api/v1/courses`) already resolves tenant
  identity exclusively from the authenticated request context (`TenantContext`) — no endpoint
  used here accepts a `tenantId` from the client, and this plan adds no new endpoint.
- `course` carries `NOT NULL tenant_id` with `idx_course_tenant_teacher_status` leading on
  `tenant_id` — already correct, unchanged by this plan (§8).
- **Mandatory cross-tenant negative test** (§18), already required by TDASH-1's own backlog entry:
  re-verify (not newly build) that a Teacher in Tenant A's Overview/My Courses never includes a
  course belonging to a same-named/coincidentally-similar course in Tenant B — this is exactly
  `CourseService.listCourses`'s existing tenant-scoped `findAll(Specification, Pageable)`
  behavior; this module's job is to confirm the presentational rework does not accidentally widen
  what's rendered (e.g. by introducing a second, differently-filtered fetch for the Overview,
  which §9 explicitly forbids).

## 15. Security rules

- **No new activation, mutation, or state-changing code path** exists anywhere in this module —
  read-only by construction (§9), so no enrollment-activation/payment-ledger change-control rule
  is implicated.
- **Backend-filtering invariant preserved, not re-derived**: both screens' KPI/summary/list content
  is computed entirely from `GET /api/v1/courses`'s single, already-teacher-scoped server response
  — never from a broader/unfiltered fetch narrowed client-side. This is the issue's own explicit
  Security requirement; §9/§11 make it structural (one hook, one endpoint, two render call sites)
  rather than a convention to remember.
- **Flagged, not silently fixed**: `(teacher)/layout.tsx` lacks the `RouteGuard` wrapper that
  `(student)/layout.tsx` and `(tenant-admin)/layout.tsx` both have (§11/§13). This is a real,
  pre-existing gap discovered while grounding this plan, not introduced by it. It is **not a
  security hole** — the backend independently authorizes every request regardless of this
  client-side guard's presence — but it is a UX/consistency gap this plan recommends closing
  alongside its own layout-adjacent work, subject to the same review as every other change here
  (§20/§21), not treated as pre-approved simply because it's convenient to bundle.

## 16. Audit requirements

**None.** Per `.claude/rules/security.md`'s canonical mandatory-audit-action list (price changes,
payment approvals/rejections, device resets, access/expiry extensions, reactivation approvals,
content deletions, settlement changes, impersonation) — nothing in this module performs any of
these actions. Both screens are pure reads. No `AuditLogApi.record(...)` call is added or needed.

## 17. Payment impact

**None — read-only.** This module adds no new `ledger_entry`, no `Payment`/`Order` write path, no
enrollment-activation path, and does not touch `OrderService`, `CourseService.changePrice`, or any
ledger/settlement code. `payment-ledger-specialist` was correctly not engaged for this reason.

## 18. Tests

Per `product-backlog.md`'s TDASH-1/TDASH-2 testing-requirements lines and
`module-catalog.md`'s cross-tenant-test mandate for `course-management`, scoped to what this
module actually changes (frontend presentation) plus explicit **regression** proof that the
already-correct backend-filtering behavior survives the rework:

**Backend — no new tests required**, since no backend code changes. The following
**already-existing** `course-management` tests (`CourseServiceTest`, `CourseAccessGuardTest`,
`CourseManagementIntegrationTest`, `CourseTeacherCompositeFkIntegrationTest`) already cover
`GET /api/v1/courses`'s teacher-ownership scoping and its cross-tenant negative-test obligation —
re-run as part of this module's own CI pass (not re-written), confirming no backend regression
from a frontend-only change. If a review finds a gap in that existing coverage specifically for
the Overview's *aggregate* use of the same data (e.g. a "two-teacher/same-tenant fixture" proving
no cross-teacher leakage in the exact shape the backlog's TDASH-1 line names), add it to
`CourseManagementIntegrationTest` rather than inventing a new test class for a frontend-only
module.

**Frontend / Playwright**
- Overview: loading skeleton/`aria-busy` state; empty state ("no assigned courses yet" + contact-
  tenant-admin guidance) with correct copy parity against My Courses' existing empty state;
  populated state shows correct total/published/draft counts against seeded fixture data; error
  state with a working Retry.
- My Courses: populated state renders a Course Card grid at `sm`/`md`/`lg` breakpoints (visual/
  layout assertion — single column below `sm`, multi-column at `lg`); existing search/category/
  status filter behavior is unchanged (**regression** test proving the presentational rework
  didn't change filter logic); zero-result-after-filter state unchanged; empty-state copy
  unchanged and distinct from the Student "no active enrollments" copy (**regression**, per the
  issue's own explicit AC).
- Cross-course-boundary regression: a Teacher navigating directly to another teacher's
  `/teacher/courses/{id}/edit` or `/teacher/courses/{id}/modules` URL still gets the existing
  permission-denied/not-found handling (**regression**, not new — proves the rework didn't
  accidentally loosen `CourseAccessGuard`'s enforcement, which this module never touches).
- Accessibility: keyboard-only navigation through both card grids and their action links;
  `aria-live`/`role="alert"` on load/error per `.claude/rules/ui-ux.md` §4, re-verified once
  content renders as a card grid rather than a table/placeholder.
- If §11's `RouteGuard` addition is implemented: a Playwright check that an unauthenticated
  request to `/teacher/dashboard` or `/teacher/courses` redirects to `/login` before any portal
  chrome renders (mirrors the existing Student/Tenant-Admin `RouteGuard` test pattern, if one
  exists — confirm during implementation).

## 19. Documentation changes

- **`docs/ui-ux/screen-map.md`**: "Teacher > Dashboard > Overview" already lists a description
  broader than what ships here ("assigned courses, pending marking, upcoming classes") — update
  it to reflect that this MVP ships assigned-course statistics only, with pending-marking/
  upcoming-classes flagged as future additions once `exam-management`/`live-class-management`
  exist (mirrors MVP-013 §19's identical correction for the Student Overview's screen-map entry).
- **`docs/ui-ux/` — Teacher home/overview conventions note** (issue's own explicit documentation
  requirement): record the Statistic-Card / Course-Card composition pattern established here, its
  relationship to `CourseListTable`'s continued admin-surface-only scope (§11), and the
  `components/dashboard/stat-card.tsx` relocation, so the next consumer-style dashboard module
  (Tenant Admin's TADASH-1, still a placeholder) reuses this pattern rather than re-deriving it.
- **`docs/requirements/open-decisions.md`**: no new entry required — this plan resolves no open
  business decision and invents none; the one pre-existing gap it surfaces (§15's `RouteGuard`
  gap) is a code-consistency finding, not a business decision, and is tracked in this plan's own
  §21 instead.
- **No `docs/api/course-management.md` change** — confirmed, no new/changed endpoint (§10).
- **No `docs/architecture/database-architecture.md` change** — confirmed, no new table/index
  (§8).

## 20. Implementation order

Per root `CLAUDE.md`'s development workflow, adapted for this module's frontend-only scope (no
backend step applies):

1. **`implement-frontend`**:
   a. Move `components/students/stat-card.tsx` → `components/dashboard/stat-card.tsx`; update the
      one existing Student Dashboard import.
   b. Build the new `TeacherCourseCardGrid` component under `components/courses/`.
   c. Rebuild `app/(teacher)/teacher/dashboard/page.tsx` (TDASH-1) using `useCourses()` + the
      relocated `StatCard`.
   d. Rework `app/(teacher)/teacher/courses/page.tsx` (TDASH-2) to render
      `TeacherCourseCardGrid` in place of `CourseListTable`, preserving all existing filter logic.
   e. Add `RouteGuard kind="tenant"` to `app/(teacher)/layout.tsx` (§11/§15's flagged fix), subject
      to review sign-off rather than assumed pre-approved.
2. Frontend + Playwright tests (§18), including the explicit regression checks for unchanged
   filter/empty-state/access-guard behavior.
3. **`security-review`**, **`tenant-isolation-review`** skills — explicit passes, focused on
   confirming no new fetch/endpoint was introduced (§9/§14/§15) and that the `RouteGuard` addition
   (if included) does not change the underlying authorization model, only client-side UX.
4. **`ui-ux-review`** skill — verify both reworked pages actually satisfy `.claude/rules/ui-ux.md`
   §4/§5 (accessibility bar, consumer-surface card-based responsive pattern) rather than assuming
   it from this plan's text alone.
5. **`update-documentation`** skill — §19's file list.
6. Commit as logically-scoped units per `.claude/rules/git-workflow.md` (e.g., "frontend: relocate
   StatCard to a shared dashboard location", "frontend: implement Teacher dashboard overview
   (TDASH-1)", "frontend: rework Teacher My Courses to a card grid (TDASH-2)", "frontend: add
   RouteGuard to the Teacher portal layout", "docs: document Teacher dashboard UI conventions") —
   this module has no backend commit at all, so the usual backend/frontend commit-separation rule
   is moot here, not violated.

## 21. Risks and unresolved decisions

None of these are resolved by this plan — implementation must not silently assume an answer:

1. **`(teacher)/layout.tsx`'s missing `RouteGuard`** (§11/§15) is a real, pre-existing gap this
   plan recommends fixing as part of its own layout-adjacent work, but that recommendation itself
   needs explicit sign-off before implementation — it is a judgment call about bundling an
   unrelated-to-the-issue fix into this module's PR, not a decision this plan is authorized to
   make unilaterally. If declined, the gap should still be logged (e.g. in a future frontend audit
   pass), not silently dropped.
2. **The backlog's TDASH-1 business-outcome line names "upcoming sessions" and "pending actions,"**
   but the issue's own AC/scope bullets do not require either, and no backend domain exists to
   supply either (§3/§6). This plan implements only what the issue's own AC requires (assigned-
   course statistics). If "upcoming sessions"/"pending actions" are still wanted at MVP scope
   (rather than deferred to `live-class-management`/`exam-management`'s own future modules), that
   is a scope decision requiring its own sign-off, not something this plan should backfill with a
   placeholder or a fake/static tile.
3. **Whether `components/dashboard/stat-card.tsx` is the right long-term shared location**
   (vs. e.g. `components/ui/stat-card.tsx` as a true shadcn-style primitive) — this plan picks the
   narrower "dashboard-scoped shared component" location consistent with MVP-013's own deferred
   framing ("components/ui/ (or a new components/dashboard/) location"), but does not treat this
   as a final architectural decision; a third consumer (e.g. Tenant Admin's still-placeholder
   TADASH-1) may prompt revisiting it.
4. **Whether the Overview's "recent courses" ordering (by `updatedAt`) is the right default** — no
   document specifies an ordering for this list; this plan assumes most-recently-updated-first as
   a reasonable display default (pure UI judgment, not a business rule), flagged here rather than
   presented as a settled requirement.
5. **`GET /api/v1/courses`'s unpaginated-in-practice (`size=100`-fetched-then-client-filtered)
   shape** (§6) is a real, if distant, scale risk for a teacher with a very large course count —
   not addressed here since changing that endpoint's shape is an "approved API contract"
   change-controlled area outside this plan's remit; flagged forward, mirroring MVP-013 §21 item
   3's identical flag for `/enrollments/my`.

## Related

- `docs/plans/MVP-007 Teacher Management.md` (source of `TeacherController`/`TeacherService`, and
  the original, since-superseded TCH-2 design note this plan corrects against actual shipped code)
- `docs/plans/MVP-008 Course Management.md` (source of `GET /api/v1/courses`'s actual
  Teacher-ownership-scoped TCH-2 implementation)
- `docs/plans/MVP-013 Student Dashboard.md` (direct structural precedent for this plan's shape,
  its `StatCard` component, and its Course-Card-grid-over-DataTable rework pattern)
- `docs/api/course-management.md`
- `docs/ui-ux/screen-map.md`, `.claude/rules/ui-ux.md`, `.claude/rules/frontend.md`
- `docs/planning/product-backlog.md` (MODULE 14), `docs/planning/dependency-map.md`
- `docs/requirements/open-decisions.md` §3 (Teacher Assistant), §16 (Course Management
  carried-forward limitations)
