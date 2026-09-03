# MVP-015 Tenant Admin Dashboard — Module Plan

Issue: [#15](https://github.com/mohanranaweera/lms-saas-platform/issues/15) — "Module 15: Tenant
Admin dashboard." Stories `TADASH-1` (Tenant Admin home/overview KPIs) and `TADASH-2` (Navigation
shell for staff/student/teacher/course/payments sections), `docs/planning/product-backlog.md`
lines 906-944, `docs/planning/dependency-map.md` lines 168-169.

Status: **Planned, not implemented.** Produced by reading the issue, root/backend/frontend
`CLAUDE.md`, `.claude/rules/{architecture,backend,frontend,ui-ux,payments,security,tenancy,
git-workflow}.md`, `docs/planning/product-backlog.md` (MODULE 15) and `dependency-map.md`,
`docs/requirements/user-roles-and-permissions.md`, `docs/ui-ux/screen-map.md`,
`docs/api/{ledger-settlement-management,course-management}.md`, and the actual current repository
state — backend (`DomainArea`, `PermissionCheckServiceImpl`'s transcribed matrix,
`StudentController`, `TeacherController`, `StaffController`, `CourseController`,
`EnrollmentController`, `TenantRegistrationController`, `docs/api/ledger-settlement-management.md`)
and frontend (`app/(tenant-admin)/**`, `components/layout/nav/tenant-admin-nav.tsx`,
`components/layout/dashboard-shell.tsx`, `components/dashboard/stat-card.tsx`,
`lib/api/{students,teachers,courses,ledger}.ts`) — not assumed from the issue text alone. Framed
against `product-requirements-analyst`, `solution-architect`, `database-architect`,
`security-reviewer`, `qa-test-engineer`, and `ui-ux-reviewer` perspectives.
`payment-ledger-specialist` perspective was applied narrowly: this module reads ledger data but
performs no order/payment/ledger/enrollment-activation write of any kind (§17).

## Grounding note — what a literal reading of the issue would get wrong

Reading the actual shipped code (not just the issue and backlog) surfaced concrete gaps between
what the issue's dependency/database-impact lines imply and what data is actually reachable
without a new backend endpoint. These are **flagged as unresolved decisions (§21), not silently
resolved**, per this project's "do not invent unresolved business decisions" instruction.

- **`GET /api/v1/ledger/dashboard` (PAY-3, the endpoint TADASH-1 must stay "consistent with") has
  no monetary total anywhere.** It is a paginated raw list of `LedgerHistoryEntryResponse` rows
  (`docs/api/ledger-settlement-management.md`); the shipped Payment Dashboard page itself
  (`app/(tenant-admin)/tenant-admin/payments/dashboard/page.tsx`) renders only the entry table plus
  pagination — no revenue/net figure is computed or displayed anywhere today. There is therefore no
  existing "ledger-derived number" a currency KPI tile could be defined as "consistent with" beyond
  entry counts. A true revenue-sum tile would require a new aggregate read (e.g. a
  `GET /api/v1/ledger/summary` endpoint doing a `SUM(amount)` grouped by `entry_type`, scoped by the
  same tenant-scoping the existing endpoint already applies) — which is itself a new `docs/api`
  entry, directly contradicting the issue's own explicit "No new `docs/api` entries for either"
  documentation requirement. **Not resolved by this plan** — see §21 item 1.
- **No tenant-profile read endpoint exists anywhere for TEN-1.** The issue's dependency line names
  `TEN-1` (Module 4, Tenant registration and profile) as a soft dependency for TADASH-1's KPI grid,
  implying a tenant-identity/plan/status card. But `tenantmanagement.web` exposes only
  `POST /api/v1/tenant-registrations` (tenant self-registration) — there is no
  `GET /api/v1/tenants/me` or equivalent, and the authenticated session/login response
  (`docs/api/identity-access-service.md`) carries no tenant name/plan/status field either, only the
  `tenant_id` claim used internally for scoping. **A TEN-1-sourced KPI card cannot be built from any
  existing endpoint.** Not resolved by this plan — see §21 item 1 (same "no new `docs/api` entries"
  tension as the ledger-summary gap above).
- **The issue's own dependency list for TADASH-1 does not include TCH-1 (Teacher Management).**
  Only `STU-1, CRS-1, PAY-3, TEN-1` are named. Teacher headcount data is readily available
  (`GET /api/v1/teachers`, already shipped, already `TEACHERS`/`VIEW`-gated) and it would be easy to
  add a "Total Teachers" tile — but doing so would be inventing scope the issue's own text does not
  ask for. This plan does **not** add a Teachers KPI card for that reason (§6).
- **`courseKeys.list()` (`frontend/src/lib/api/courses.ts`) is not parameter-aware** — every
  `useCourses(params)` call, regardless of `params`, shares the identical React Query cache key
  `["courses", "list"]`. This module's course-count KPI needs two differently-filtered reads (total
  course count, published-course count) fired from the same page; using the existing `useCourses`
  hook for both as-is would let the two calls silently clobber each other's cache entry. This plan
  works around it with a dedicated, separately-keyed hook (§9/§11) rather than fixing the shared
  hook's cache key as an incidental side effect — flagged, not silently patched (§21 item 4).
- **No frontend Staff page exists.** `com.lms.usermanagement.staff` (`StaffController`,
  `GET /api/v1/staff`) shipped in MVP-005, but no `app/(tenant-admin)/tenant-admin/staff/**` route
  was ever built — confirmed, no matches anywhere under `frontend/src/app`. `STAFF-1` is listed only
  as a *soft* dependency for TADASH-2 ("can ship with partial/zero-state... only has real data once
  its source domain exists" framing, per TADASH-1's identical soft-dependency treatment), so this
  plan does not build a Staff destination page (out of scope, §6) and does not add a "Staff" nav
  item pointing at a route that doesn't exist (§11).
- **`TenantAdminNav`'s "Teachers" link is currently shown unconditionally to every role that reaches
  the shell**, but `PermissionCheckServiceImpl`'s matrix grants `TEACHERS`/`VIEW` to only 4 of the 8
  roles that use this shell (Tenant Admin, Course Coordinator, Student Support, Read-only Auditor —
  **not** Finance Staff, Content Manager, Exam Manager, Attendance Operator). This is the concrete,
  already-existing gap TADASH-2's own acceptance criterion ("a staff sub-role without a domain's
  permission → corresponding nav item hidden/disabled") is written to close — not a hypothetical.
  "Students" and "Courses" need no equivalent fix: every one of the 8 roles already holds
  `STUDENTS`/`VIEW` and `COURSES`/`VIEW` in the transcribed matrix, so those two links are already
  correctly always-visible (§11).

## 1. Business goal

Give a Tenant Admin (and, for the navigation shell only, every staff sub-role sharing the
`app/(tenant-admin)/` route group) a single-tenant-scoped operational landing view: a Statistic Card
overview built entirely from existing, already-tenant-scoped domain reads (no new backend
aggregation domain, no cross-schema join), and a navigation shell whose visible items match what the
signed-in role can actually reach — closing the one concrete permission/nav-visibility gap that
exists today (§ Grounding note) — while every destination page keeps its own, already-correct,
independent server-side authorization as the real enforcement layer.

## 2. Roles and permissions

| Role | TADASH-1 (Overview) | TADASH-2 (Nav shell) |
|---|---|---|
| Tenant Admin | Full KPI grid (§6) | Full nav — every link visible (holds `VIEW`/broader on every domain area used by this shell) |
| Finance Staff | KPI grid identical (read-only, tenant-scoped — no per-role KPI variation) | Teachers link hidden (no `TEACHERS`/`VIEW`); Payments/Refunds/Payment Slips/Reactivation Approvals visible (existing, unchanged) |
| Course Coordinator | KPI grid identical | Teachers link visible (holds `TEACHERS`/`VIEW`); Payments-family links hidden (no `PAYMENTS_SLIPS`/`ACCESS_EXPIRY` grant — existing, unchanged) |
| Student Support | KPI grid identical | Teachers link visible; Payments-family links visible (existing, unchanged) |
| Content Manager | KPI grid identical | Teachers link hidden (no `TEACHERS`/`VIEW`); Payments-family links hidden (existing, unchanged) |
| Exam Manager | KPI grid identical | Teachers link hidden; Payments-family links hidden |
| Attendance Operator | KPI grid identical | Teachers link hidden; Payments-family links hidden |
| Read-only Auditor | KPI grid identical | Teachers link visible (holds `TEACHERS`/`VIEW`); Payments-family links visible (view-only queues — existing, unchanged); **no mutating control anywhere** (refund/approve/reject buttons already gated separately by `canProcessRefunds`/`canReviewSlips`/`canApproveReactivation`, none of which include this role — existing, re-verified not rebuilt) |
| Teacher, Teacher Assistant, Student | No access to `app/(tenant-admin)/**` at all — this route group's own `RouteGuard kind="tenant"` plus every destination page's independent backend check already reject them (unchanged; this module does not touch Teacher/Student/Teacher-Assistant routing) |
| Platform Admin | No access — a platform-admin session is a distinct `PrincipalKind` from every tenant-scope role; `PermissionCheckServiceImpl.hasPermission` explicitly denies for a non-tenant-`Role` claim (existing, unchanged) |

The KPI grid itself carries **no per-role variation** — every role that reaches `/tenant-admin/dashboard`
sees the identical, tenant-scoped numbers (this is what "own tenant's data, no tenant selector" means
for TADASH-1); role variation in this module is entirely a TADASH-2 (nav-visibility) concern.

## 3. Preconditions

- MVP-002 (Authentication Foundation) / `APP-2` — session issuance for every tenant-scope role —
  shipped (`AUTH-2`). TADASH-2's own listed hard blocker.
- MVP-006 (Student Management) / `STU-1` — `GET /api/v1/students` — shipped, this module's source
  for the Students KPI tile.
- MVP-008 (Course Management) / `CRS-1` — `GET /api/v1/courses` (server-side `status` filter) —
  shipped, this module's source for the Courses KPI tile.
- MVP-010 (Order and Payment Foundation) / `PAY-3` — `GET /api/v1/ledger/dashboard` — shipped, this
  module's source for the Payments KPI tile, **at count granularity only** (§ Grounding note).
- MVP-004 (Tenant Management) / `TEN-1` — the `tenant` table exists (a Tenant Admin session cannot
  exist without it), but **no read surface exists for its own profile data** (§ Grounding note) — so
  this precondition is satisfied only in the "a tenant row exists" sense, not in the "a KPI card can
  read it" sense.
- The shared `app/(tenant-admin)/layout.tsx` (`RouteGuard kind="tenant"`, `DashboardShell`,
  `TenantAdminNav`) already exists and already implements the sidebar/drawer responsive pattern
  TADASH-2 asks for (§ Grounding note) — this is infrastructure this module reuses, not builds.

## 4. User flows

### 4.1 Tenant Admin Overview (`/tenant-admin/dashboard`, TADASH-1 — rebuild of the existing placeholder)

1. A Tenant Admin (or any staff sub-role — the grid has no role variation, §2) logs in and lands on,
   or navigates to, `/tenant-admin/dashboard`.
2. The page fires three independent, already-tenant-scoped reads in parallel (no new endpoint, no
   cross-domain join — each read stays inside its own owning domain, per
   `.claude/rules/architecture.md`'s narrow-`api`-read guidance):
   - `GET /api/v1/students` (`useStudents`, existing) — total count = `data.length`.
   - `GET /api/v1/courses?size=1` and `GET /api/v1/courses?status=PUBLIC&size=1` (new, separately
     keyed hook, §9/§11) — total count and published count from each response's `totalElements`;
     draft/private count = total − published.
   - `GET /api/v1/ledger/dashboard?page=0&size=1` (`useLedgerDashboard`, existing, already
     params-keyed) — entries-recorded count from `totalElements`.
3. Statistic cards render, computed client-side from these three already-scoped responses (pure
   display arithmetic, mirroring `StatCard`'s existing MVP-013/MVP-014 contract, no business logic):
   Total Students, Total Courses (with a Published/Draft split), Payments Recorded (an entry count,
   explicitly **not** a currency total — §6/§ Grounding note).
4. Each of the three reads that returns a genuine zero (no students, no courses, no ledger entries)
   renders its own card with a "0" value and a short contextual hint (e.g. "No students enrolled
   yet") rather than swapping the whole page for a single generic empty state — these are
   independent domains, not one dataset, so one domain being empty must not read as "the whole
   dashboard is empty" (distinct from MVP-013/MVP-014's single-dataset empty-state pattern, since
   this page composes three unrelated reads).
5. Any of the three reads failing independently renders that one card's own inline error with Retry
   (not a whole-page `ErrorState`) — a Payments read outage must not blank out the Students/Courses
   cards that succeeded. A `401` on any read → the existing `RouteGuard`/redirect-to-login path,
   unchanged (session-level, not per-card).
6. No tenant selector/switcher renders anywhere on this page or its layout (already true — never
   built; explicitly re-verified as a regression check, §18).

### 4.2 Navigation shell (`app/(tenant-admin)/` shared layout, TADASH-2 — targeted fix, not a rebuild)

1. Any of the 8 roles in §2 logs in and reaches any `/tenant-admin/**` page. `DashboardShell`
   renders `TenantAdminNav` as a persistent sidebar at `md`+ and inside a hamburger-triggered `Sheet`
   drawer below `md` — both already implemented, unchanged by this module.
2. `TenantAdminNav` renders Students and Courses unconditionally (already correct — every role in
   scope holds `VIEW` on both), Teachers conditionally on a new `canViewTeachers(role)` check
   (§ Grounding note, §11), and the existing Payments/Refunds/Payment Slips/Reactivation Approvals
   items unchanged (already correctly gated).
3. A role without a given link's permission (e.g. Finance Staff) does not see that link at all, in
   either the desktop sidebar or the mobile drawer.
4. If that same role navigates directly to the hidden route by URL (e.g. Finance Staff typing
   `/tenant-admin/teachers`), the destination page's own existing `QueryStateBoundary` still fires
   the real `GET /api/v1/teachers` call, gets a server `403` (`TEACHERS`/`VIEW` not granted), and
   renders the shared `PermissionDeniedState` — unchanged, already correct, re-verified as a
   regression (§18) per TADASH-2's own explicit "hidden link ≠ access control" acceptance criterion.
5. Read-only Auditor reaches every view-only queue (Payment Slips, Reactivation Approvals, Teachers)
   but never sees a mutating control (refund button, approve/reject actions) on any of them —
   already true today via each page's own existing `canProcessRefunds`/`canReviewSlips`/
   `canApproveReactivation` checks (none include this role); re-verified, not rebuilt.

## 5. Acceptance criteria

Restating the issue's own criteria, made concrete against what actually exists (§ Grounding note):

- [ ] Tenant Admin (and every staff sub-role) logs in → Overview shows only the caller's own
      tenant's data — every read this page performs already resolves tenant identity exclusively
      from the authenticated session (`TenantContext`), never a client-supplied parameter.
- [ ] No tenant selector/switcher renders anywhere in `app/(tenant-admin)/**` — regression check,
      already true.
- [ ] Overview is an explicit admin-heavy-surface responsive layout: a multi-column card grid at
      `md`+, single-column stacking below `md`, per `.claude/rules/ui-ux.md` §5's "admin-heavy
      surfaces... must define an explicit mobile fallback" rule — new work.
- [ ] The Payments KPI card is **structurally** the same figure PAY-3's own dashboard pagination
      would show for `size=1` (an entry count) — not a separately-computed sum — satisfying
      "consistent with the ledger-derived Payment Dashboard, not a separately-computed number" at
      the granularity that's actually buildable without a new endpoint (§ Grounding note; a
      currency-total interpretation of this AC is **not** satisfied and is flagged, §21 item 1).
- [ ] A staff sub-role without a domain's `VIEW` permission does not see that domain's nav
      item/action (concretely: Finance Staff/Content Manager/Exam Manager/Attendance Operator no
      longer see "Teachers" — new fix; Students/Courses/Payments-family already correct).
- [ ] Direct navigation to a hidden nav destination still yields a server-verified permission-denied
      state, never a silent 200 with unauthorized data — regression check, already true.
- [ ] Read-only Auditor sees no mutating control anywhere in the shell — regression check, already
      true.
- [ ] Every async read on the Overview exposes `aria-busy`/`aria-live`/`role="alert"` independently
      per card (§4.1 step 4/5), per `.claude/rules/ui-ux.md` §4 — new work, since this page composes
      three independent reads rather than one `QueryStateBoundary`-wrapped read like every prior
      dashboard module.

## 6. Out-of-scope items

- **A currency/revenue total KPI tile.** No existing endpoint supplies one; building one requires a
  new `docs/api` entry the issue's own text says not to add. Flagged, not built (§21 item 1).
- **A Tenant/TEN-1-sourced KPI tile** (tenant name, plan, subscription status). No existing endpoint
  supplies this either. Flagged, not built (§21 item 1).
- **A Teachers KPI tile on the Overview.** Data is available (`GET /api/v1/teachers`) but `TCH-1` is
  not in the issue's own TADASH-1 dependency list — not added, to avoid inventing scope (§ Grounding
  note).
- **A Staff destination page or nav item.** No frontend Staff page exists; `STAFF-1` is only a soft
  dependency for TADASH-2, and this plan does not build a new Staff Management frontend module as an
  incidental side effect of the nav-shell fix (that is Module 5's own frontend slice, never built,
  and out of this issue's stated scope).
- **Fixing `courseKeys.list()`'s params-blindness in `lib/api/courses.ts` globally.** Worked around
  locally for this module's two course reads (§9); the underlying shared-hook cache-key gap is
  flagged, not fixed everywhere it could theoretically bite (§21 item 4).
- **Any new backend domain, aggregation service, or cross-schema join.** Per the issue's own
  explicit backend requirement and `.claude/rules/architecture.md`'s `reporting-analytics` guidance
  — this module makes zero backend changes at all (§9).
- **Alerts/notifications tiles** ("KPIs and alerts" per `docs/ui-ux/screen-map.md`'s existing,
  broader Overview description) — no alerting domain exists yet; out of scope by construction, same
  treatment as MVP-013/MVP-014's identical exclusions for their own Overview pages.
- **Materials, Live Classes, Attendance, Exams, Finance, Communications, Integrations, Devices,
  Access & Expiry rules, Reviews, Reports, Audit Log, Support, Branding, Settings nav sections**
  (all named in `docs/ui-ux/screen-map.md`'s Tenant Admin Portal list) — none of these have shipped
  frontend pages yet (Access & Expiry's *Reactivation Approvals* queue is the one exception, already
  in nav, unchanged); TADASH-2's own scope line names only "staff/student/teacher/course/payments,"
  and this plan does not add nav entries pointing at unbuilt destinations.
- **Any new RBAC domain area, matrix cell, or `Role` enum value.** `canViewTeachers` (§11) is a pure
  frontend read of the *existing* `TEACHERS`/`VIEW` grant set — no backend permission change.

## 7. Domain model

**No new entity, no new DTO shape.** This module reads existing response shapes exactly as already
modeled and returned: `StudentResponse[]` (student count), `PageResponse<CourseResponse>`
(course counts via `totalElements`), `PageResponse<LedgerHistoryEntryResponse>` (ledger entry count
via `totalElements`). The only new artifacts are frontend-only: a `TenantOverviewSummary`-shaped
client-side composition (not a fetched type — the derived `{ students, coursesTotal,
coursesPublished, ledgerEntries }` values a new hook/page computes from the three existing response
shapes) and a small `canViewTeachers` permission helper (§11).

## 8. Database design

**None.** No migration is added by this plan. Every read this module performs already uses
already-shipped, already correctly tenant-leading-indexed tables/queries:

- `tenant_user` (student/teacher rows) — already indexed for the existing `GET /api/v1/students`/
  `GET /api/v1/teachers` tenant-scoped queries (MVP-005/MVP-006/MVP-007).
- `course` — `idx_course_tenant_teacher_status ON course (tenant_id, teacher_id, status)` (V11) —
  already serves the `status`-filtered count reads this module adds (§9), since `status` is the
  index's third leading column after `tenant_id`.
- `ledger_entry` — already tenant-scoped and already serves `GET /api/v1/ledger/dashboard`'s
  pagination (MVP-010), reused unchanged at `size=1` for a count-only read.

## 9. Backend design

**None — this module makes zero backend changes**, per the issue's own explicit "No dedicated
domain... no new `docs/api` entries" requirement and `.claude/rules/architecture.md`'s
`reporting-analytics` guidance against live cross-schema joins. Every KPI figure is composed by the
**frontend** from three already-existing, already-narrow, already-tenant-scoped domain reads —
`user-management`'s `GET /api/v1/students`, `course-management`'s `GET /api/v1/courses`, and
`ledger-settlement-management`'s `GET /api/v1/ledger/dashboard` — called independently and combined
client-side into the Overview's card grid. This is the concrete form the issue's own "BFF-style
aggregation of narrow `api` reads per domain" recommendation takes here: since "no dedicated domain"
was explicitly ruled out, the frontend itself is the composition layer (an established precedent in
this codebase — MVP-013/MVP-014's Overview pages already compose their own stat cards from a single
existing endpoint's response; this module composes from three).

If a future module needs the flagged, not-yet-buildable figures (§21 item 1 — a currency revenue
total, a tenant-profile card), that is a separate, explicitly-approved narrow-`api`-extension
decision for `ledger-settlement-management`/`tenant-management` to make against their own owning
domain — not something this plan pre-builds speculatively or routes around by joining tables
directly.

`TenantAdminNav`'s Teachers-link fix (§11) is a pure frontend permission-mirroring change (a new
`canViewTeachers` helper in `lib/auth/permissions.ts`, mirroring the existing
`canViewPaymentDashboard`/`canProcessRefunds` pattern exactly) — it reads the already-shipped
`TEACHERS`/`VIEW` grant set, it does not add or change any backend grant.

## 10. API contract

**No new endpoint, no contract change.** Every call this module makes is already documented/shipped:

| Method + path | Doc | Purpose in this module |
|---|---|---|
| `GET /api/v1/students` | `docs/api/user-management.md` | Students KPI tile — `data.length` |
| `GET /api/v1/courses?size=1` | `docs/api/course-management.md` | Total courses KPI — `data.totalElements` |
| `GET /api/v1/courses?status=PUBLIC&size=1` | `docs/api/course-management.md` | Published courses KPI — `data.totalElements`; draft/private derived (total − published) |
| `GET /api/v1/ledger/dashboard?page=0&size=1` | `docs/api/ledger-settlement-management.md` | Payments-recorded KPI (entry count, not currency) — `data.totalElements` |
| `GET /api/v1/teachers` | `docs/api/user-management.md` | Unchanged — only its **nav visibility** changes (§11), not the call itself |

No entry is added to any `docs/api/*.md` file — confirmed against the issue's own explicit "No new
`docs/api` entries for either" requirement, and matching what §9 establishes.

## 11. Frontend screens

- **`app/(tenant-admin)/tenant-admin/dashboard/page.tsx` (rebuild — currently a static placeholder,
  §4.1)**: three independent React Query reads (`useStudents` existing, `useLedgerDashboard({page:0,
  size:1})` existing, and a new `useTenantCourseCounts()` hook, below), each rendered through its own
  small inline loading/error boundary (not one shared `QueryStateBoundary`, since the three reads are
  independent domains that must fail/empty independently, §4.1 step 4/5) feeding three `StatCard`s
  (relocated component, already shared, unchanged) in a responsive grid:
  `grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3`.
- **New `useTenantCourseCounts()` hook** (`lib/api/courses.ts` or a new co-located file, e.g.
  `lib/api/tenant-overview.ts` — implementation detail for `implement-frontend`, not a business
  decision): fires `GET /api/v1/courses?size=1` and `GET /api/v1/courses?status=PUBLIC&size=1` under
  its own distinct query key (e.g. `["tenant-overview", "course-counts"]`), deliberately **not**
  reusing `useCourses`/`courseKeys.list()` directly, to avoid the params-blind cache-key collision
  identified in the Grounding note (§21 item 4). Returns `{ total, published, draft }` (draft =
  total − published) once both reads resolve.
- **`lib/auth/permissions.ts` — new `canViewTeachers(role)` export**: mirrors
  `canViewPaymentDashboard`'s exact shape, returning `true` for `TENANT_ADMIN`, `COURSE_COORDINATOR`,
  `STUDENT_SUPPORT`, `READ_ONLY_AUDITOR` (the exact `TEACHERS`/`VIEW` grant set transcribed in
  `PermissionCheckServiceImpl`) — used only to gate the "Teachers" nav-entry's visibility, never as
  an authorization decision (destination page's existing `403`/`PermissionDeniedState` handling is
  unchanged and remains the real enforcement, per `.claude/rules/frontend.md`).
- **`components/layout/nav/tenant-admin-nav.tsx` (targeted edit)**: move "Teachers" out of
  `BASE_ITEMS` into a conditional push gated by `canViewTeachers(role)`, mirroring the existing
  `canViewPaymentDashboard`/`canProcessRefunds` conditional-push pattern already used for the four
  payments-family items on this same component. "Students"/"Courses" stay in `BASE_ITEMS`
  unconditionally (§ Grounding note — no permission gap exists for either).
- **No layout change** — `app/(tenant-admin)/layout.tsx`'s `RouteGuard`/`DashboardShell`/
  `TenantAdminNav` composition already implements TADASH-2's "Desktop Sidebar (no tenant selector),
  Mobile Navigation drawer variant" requirement in full (§3); this module edits `TenantAdminNav`'s
  item list, not the shell itself.
- Each Overview card individually exposes `aria-busy` while loading and `role="alert"` on its own
  failure (§4.1 step 4/5) — a new, per-card pattern distinct from every prior dashboard module's
  single-`QueryStateBoundary`-per-page convention, since this page is the first to compose more than
  one independent domain read on one screen. Flagged for `ui-ux-review` sign-off on whether this
  per-card boundary pattern (vs. e.g. a shared multi-query boundary helper) is the right shape to
  standardize for future multi-domain dashboards (§21 item 5).

## 12. Validation rules

None — every screen in this module is a pure read (three independent GETs on the Overview; nav-item
visibility is a pure client-side boolean). No form, no mutation, no user-supplied input.

## 13. Error cases

| Scenario | Response |
|---|---|
| Unauthenticated/expired session reaching any `/tenant-admin/**` page | Existing `RouteGuard`/`QueryStateBoundary` redirect-to-login, unchanged |
| A Student/Teacher/Teacher-Assistant/Platform-Admin session somehow reaches `/tenant-admin/**` | Existing `RouteGuard kind="tenant"` plus every destination page's independent backend `403`, unchanged — this module adds no new gap here |
| One of the Overview's three reads fails (network/5xx) while the others succeed | That card alone shows an inline error + Retry; the other two cards keep rendering their successful data (§4.1 step 5) — new behavior, since no prior dashboard composes independent reads |
| All three Overview reads return genuine zero data (brand-new tenant) | Each card renders its own "0" + contextual hint, not a single whole-page empty state (§4.1 step 4) |
| A staff sub-role without `TEACHERS`/`VIEW` directly navigates to `/tenant-admin/teachers` | `403` from the existing `GET /api/v1/teachers` call, rendered via the page's existing `PermissionDeniedState` — unchanged, re-verified (§18) |
| A staff sub-role without `PAYMENTS_SLIPS`/`VIEW` directly navigates to a payments-family route | `403`, unchanged existing behavior (already correct before this module) |

## 14. Tenant-isolation rules

- Every read this module's frontend performs (`GET /api/v1/students`, `GET /api/v1/courses`,
  `GET /api/v1/ledger/dashboard`) already resolves tenant identity exclusively from the
  authenticated request context — no endpoint used here accepts a `tenantId` from the client, and
  this plan adds no new endpoint.
- All three source tables (`tenant_user`, `course`, `ledger_entry`) already carry `NOT NULL
  tenant_id` with a tenant-leading index (§8), unchanged by this plan.
- **This module is exactly the "bulk/admin/reporting endpoint" pattern flagged in
  `.claude/rules/tenancy.md` as a common isolation-bypass source** — but since it introduces zero
  new endpoints and composes only from three already-individually-tenant-scoped, already-tested
  reads, the mandatory cross-tenant proof (§18) is a **regression** check (confirming the
  presentational composition doesn't accidentally introduce a wider fetch) rather than new
  isolation logic to build. `.claude/rules/architecture.md`'s warning against `reporting-analytics`
  doing ad hoc joins is honored by construction (§9) — there is no join to have gotten wrong.
- **Mandatory cross-tenant negative test**, per the issue's own explicit requirement: a two-tenant
  fixture proving Tenant A's Overview counts never include Tenant B's students/courses/ledger
  entries — re-verifying each of the three underlying endpoints' existing tenant-scoping, in the
  specific combined shape this Overview presents them (§18).

## 15. Security rules

- **No new activation, mutation, or state-changing code path** exists anywhere in this module —
  read-only by construction (§9/§12) — no enrollment-activation or payment-ledger change-control
  rule is implicated.
- **Nav-visibility is UX convenience only, restated as this module's own concrete instance of the
  rule already stated in `.claude/rules/security.md`/`ui-ux.md`**: `canViewTeachers` (§11) never
  gates the actual `GET /api/v1/teachers` call's authorization — `TeacherController`'s existing
  `@PreAuthorize("@permissionCheckService.hasPermission('TEACHERS', 'VIEW')")` remains the sole
  enforcement, unchanged. This is re-verified with a direct-navigation Playwright test per role
  (§18), not merely asserted.
- **KPI figures must never be computed from a broader/unfiltered fetch narrowed client-side** — each
  of the three Overview reads is already the same tenant-scoped, permission-scoped query its own
  existing List page uses (Students list, Courses list, Payment Dashboard); this module does not
  introduce a second, less-filtered read path for any of the three domains (mirrors MVP-014 §15's
  identical structural guarantee).
- **Flagged, not silently worked around**: the two data gaps in the Grounding note (no ledger
  revenue-total read, no tenant-profile read) are real absences, not something this plan papers over
  with a client-side-computed approximation (e.g. summing `amount` across every ledger page, which
  would both be wrong under pagination and would violate the "ledger-derived, not separately
  computed" AC in spirit even if not by new-endpoint letter). See §21 item 1.

## 16. Audit requirements

**None.** Per `.claude/rules/security.md`'s canonical mandatory-audit-action list (price changes,
payment approvals/rejections, device resets, access/expiry extensions, reactivation approvals,
content deletions, settlement changes, impersonation) — nothing in this module performs any of
these actions. Every screen here is a pure read or a pure client-side nav-visibility decision. No
`AuditLogApi.record(...)` call is added or needed.

## 17. Payment impact

**None — read-only.** This module adds no new `ledger_entry` row, no `Payment`/`Order` write path,
no enrollment-activation path, and does not touch `PaymentService`, `LedgerQueryService`'s write
side (it has none), or any settlement code. It reads `GET /api/v1/ledger/dashboard` at `size=1`
purely for a count, per `.claude/rules/payments.md` §2/§4's "ledger is the source of truth for
what's paid" rule — never `order`/`payment.status` directly. `payment-ledger-specialist` review
should confirm specifically that (a) no code path in this module treats `order`/`payment` state as
authoritative, and (b) the flagged revenue-total gap (§21 item 1) is not quietly worked around with
a client-side sum that would violate append-only/ledger-derived guarantees.

## 18. Tests

Per the issue's own explicit test requirements and `.claude/rules/tenancy.md`'s mandatory
cross-tenant-test rule for exactly this "bulk/admin/reporting endpoint" shape:

**Backend — no new tests required**, since no backend code changes (§9). The following
**already-existing** tests already cover every endpoint this module reads
(`StudentManagementIntegrationTest`/cross-tenant coverage for `GET /api/v1/students`,
`CourseManagementIntegrationTest`/`CourseTeacherCompositeFkIntegrationTest` for
`GET /api/v1/courses`, `PaymentCrossTenantIntegrationTest` for `GET /api/v1/ledger/dashboard`,
`TeacherManagementIntegrationTest` or equivalent for `TEACHERS`/`VIEW`'s existing 403 behavior) —
re-run as part of this module's CI pass, not re-written. If review finds no existing Testcontainers
fixture proves the *combined* two-tenant shape this Overview specifically composes (Tenant A's three
counts excluding Tenant B's rows, in one seeded fixture), add that one integration test to whichever
of the three domains' existing test classes is the natural home — not a new test class for a
frontend-only module, mirroring MVP-014 §18's identical guidance.

**Frontend / Playwright**
- Overview: independent loading (`aria-busy`) and error (`role="alert"`, Retry) states **per card**,
  not per page (new pattern, §11/§13) — verify one card's simulated failure doesn't blank the other
  two.
- Overview: zero-data state renders three independent "0 + hint" cards, not one page-level empty
  state — populated state shows correct counts against seeded fixture data (students, total/published
  courses, ledger entry count).
- Overview: **mandatory cross-tenant negative test** — Tenant A's Overview counts never include
  Tenant B's seeded students/courses/ledger entries, asserted against the actual rendered card
  values (issue's own explicit requirement, §14).
- Overview: no tenant selector/switcher element exists anywhere on the page (regression, explicit
  issue AC).
- Nav shell: for each of the 8 in-scope role fixtures (§2), assert exactly which of
  Students/Teachers/Courses/Payments/Refunds/Payment Slips/Reactivation Approvals render — the new
  Teachers-hidden-for-4-roles behavior plus regression proof the other three remain correctly gated
  as before.
- Nav shell: for a role with a hidden nav item (e.g. Finance Staff + Teachers), direct navigation to
  that route by URL still renders `PermissionDeniedState` from a real `403` — not a client-side
  redirect and not silently-empty data (issue's own explicit "hidden link ≠ access control" AC).
- Nav shell: Read-only Auditor renders zero mutating controls across every reachable
  destination in this shell (regression sweep across Teachers/Payment Slips/Reactivation
  Approvals/Refunds).
- Nav shell: no tenant selector/switcher renders in the sidebar or mobile drawer for any role
  (regression, explicit issue AC).
- Responsive/accessibility: Overview card grid single-column below `md`, multi-column at `md`+
  (`.claude/rules/ui-ux.md` §5 admin-heavy-surface mobile-fallback requirement); mobile nav drawer
  keyboard-operable (focus trap, return focus on close) — largely re-verifying `DashboardShell`'s
  existing, unchanged behavior in this module's new content context.

## 19. Documentation changes

- **`docs/ui-ux/screen-map.md`**: "Tenant Admin > Dashboard > Overview — tenant KPIs and alerts" —
  update to name the actual three MVP-shipped KPI categories (Students, Courses, Payments-recorded-
  count) and explicitly flag Alerts, a Tenant/TEN-1 tile, and a currency revenue tile as future
  additions once their data sources exist, mirroring MVP-013/MVP-014 §19's identical
  screen-map-correction pattern.
- **`docs/ui-ux/` — new Tenant Admin overview / navigation-shell conventions note** (issue's own
  explicit documentation requirement, both stories): record (a) the three-independent-reads,
  per-card-boundary composition pattern for KPI grids that read from more than one domain (distinct
  from every prior single-`QueryStateBoundary` dashboard, §11/§21 item 5), and (b) the nav
  permission-hiding convention (`canView<Domain>` helpers in `lib/auth/permissions.ts`, mirroring
  `PermissionCheckServiceImpl`'s matrix, gating nav-item visibility only, never authorization) — so
  the next domain added to this nav (Staff, once its frontend module ships) follows the same
  pattern rather than re-deriving it.
- **`docs/requirements/open-decisions.md`**: add an entry recording the two flagged, unresolved KPI
  gaps (§21 item 1 — no ledger-revenue-total read, no tenant-profile read) so they're tracked as an
  open product decision rather than only living in this plan file.
- **No `docs/api/*.md` change** — confirmed, no new/changed endpoint (§10).
- **No `docs/architecture/database-architecture.md` change** — confirmed, no new table/index (§8).

## 20. Implementation order

Per root `CLAUDE.md`'s development workflow, adapted for this module's frontend-only scope (no
backend step applies, per §9):

1. **`implement-frontend`**:
   a. Add `canViewTeachers` to `lib/auth/permissions.ts`; move "Teachers" in
      `tenant-admin-nav.tsx` from `BASE_ITEMS` into a `canViewTeachers`-gated conditional push.
   b. Add the new, separately-keyed `useTenantCourseCounts()` hook (§11) — deliberately not reusing
      `courseKeys.list()` (§ Grounding note item 4).
   c. Rebuild `app/(tenant-admin)/tenant-admin/dashboard/page.tsx` (TADASH-1) with three independent
      per-card read/loading/error boundaries and the responsive `StatCard` grid (§4.1/§11).
2. Frontend + Playwright tests (§18), including the mandatory cross-tenant negative test and the
   per-role nav-visibility matrix.
3. **`security-review`**, **`tenant-isolation-review`** skills — explicit passes confirming: no new
   endpoint was introduced (§9/§10), `canViewTeachers` gates visibility only (§15), and the
   cross-tenant proof actually exercises the combined three-domain shape (§14/§18).
4. **`ui-ux-review`** skill — verify the per-card independent-boundary pattern (§4.1/§11) actually
   satisfies `.claude/rules/ui-ux.md` §4/§5, and flag the pattern-standardization open question
   (§21 item 5) for explicit sign-off rather than assuming it silently.
5. **`update-documentation`** skill — §19's file list, including the new `open-decisions.md` entry
   for §21 item 1.
6. Commit as logically-scoped units per `.claude/rules/git-workflow.md` (e.g., "frontend: gate
   Teachers nav item by TEACHERS/VIEW permission", "frontend: implement Tenant Admin dashboard
   overview (TADASH-1)", "docs: document Tenant Admin overview and nav-shell conventions") — this
   module has no backend commit at all.

## 21. Risks and unresolved decisions

None of these are resolved by this plan — implementation must not silently assume an answer:

1. **The two flagged KPI-data gaps require an explicit decision before implementation reaches for a
   workaround**: (a) no endpoint supplies a ledger-derived currency/revenue total, and (b) no
   endpoint supplies TEN-1 tenant-profile data. Building either requires a new narrow `docs/api`
   entry, which directly contradicts the issue's own "No new `docs/api` entries for either"
   documentation requirement. This plan's default is: ship the Overview with **three** KPI cards
   (Students, Courses, Payments-recorded-count) and explicitly omit a revenue tile and a tenant tile
   for MVP. If a currency total or tenant tile is still wanted at MVP scope, that requires explicit
   sign-off on which constraint yields (accept a new `docs/api` entry, or accept the count-only/
   tenant-tile-omitted scope) — not a decision this plan is authorized to make unilaterally.
2. **Whether "Payments Recorded" (an entry count) is legible enough as a KPI label**, given it is
   deliberately not a currency figure (§ Grounding note) — a UX wording/framing judgment call flagged
   for `ui-ux-review`, not a resolved requirement.
3. **Whether "Total Teachers" should be added to the Overview despite not being in the issue's own
   TADASH-1 dependency list** (§ Grounding note/§6) — data is trivially available; this plan
   deliberately does not add it to avoid inventing scope, but flags the option explicitly in case the
   issue's own dependency list was itself an oversight.
4. **`courseKeys.list()`'s params-blindness** (`lib/api/courses.ts`, § Grounding note) is a real,
   pre-existing gap this plan works around locally (a separately-keyed new hook) rather than fixing
   at the shared-hook level — flagged for a future frontend-hygiene pass, not silently patched as a
   side effect of this module.
5. **Whether the "three independent per-card read/loading/error boundaries" pattern (§4.1/§11) is
   the right long-term shape for a multi-domain dashboard**, versus e.g. a shared "parallel-queries"
   boundary helper component — this plan picks the narrower, page-local implementation since this is
   the first module to need it; a second multi-domain dashboard (Platform Admin's own future
   overview) may prompt extracting a shared helper, not pre-built here speculatively.
6. **Whether Staff should get a nav item once its frontend module ships** is that future module's own
   scope decision (adding a `canViewStaff` helper mirroring this one, per §19's documented
   convention) — not resolved or pre-authorized here.

## Related

- `docs/plans/MVP-013 Student Dashboard.md`, `docs/plans/MVP-014 Teacher Dashboard.md` (structural
  precedent — `StatCard`, single-`QueryStateBoundary`-per-page pattern this module deliberately
  diverges from for its multi-domain composition, §11/§21 item 5)
- `docs/plans/MVP-010 Order and Payment Foundation.md` (source of `GET /api/v1/ledger/dashboard`)
- `docs/plans/MVP-006 Student Management.md`, `MVP-007 Teacher Management.md`, `MVP-008 Course
  Management.md` (source of the other two reused endpoints)
- `docs/api/{user-management,course-management,ledger-settlement-management}.md`
- `docs/ui-ux/screen-map.md`, `.claude/rules/{ui-ux,architecture,tenancy,security,payments}.md`
- `docs/planning/product-backlog.md` (MODULE 15), `docs/planning/dependency-map.md`
- `docs/requirements/user-roles-and-permissions.md` §2 (staff sub-role permission matrix source)
