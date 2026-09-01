# MVP-013 Student Dashboard — Module Plan

Issue: [#13](https://github.com/mohanranaweera/lms-saas-platform/issues/13) — "Module 13: Student
dashboard." Stories SDASH-1 (Student home/overview) and SDASH-2 (My Courses list).

Status: **Planning.** No code written by this plan. Hand off to `implement-backend` (one narrow
addition only, §9) then `implement-frontend` per §20.

## Grounding note — this is not a greenfield module, and one piece already shipped early

Two things a naive read of the issue would miss, found while reading the current frontend tree
and MVP-012's own plan/architecture doc before writing this one:

- **`frontend/src/app/(student)/student/courses/page.tsx` ("My Courses") already exists and is
  functionally complete for SDASH-2's data plumbing** — built as part of MVP-012's frontend slice
  (`docs/plans/MVP-012 Enrollment and Course Access.md` §11, §21 item 9, which explicitly flagged
  this page as *"a minimal version... to be extended, not duplicated, by [a] future module"* if
  one existed to own it). MVP-013 is that future module. The page already calls
  `GET /api/v1/enrollments/my` + `GET /api/v1/reactivation-requests/my` via
  `lib/api/enrollments.ts`, already routes through the shared `QueryStateBoundary` for
  loading/empty/error/permission-denied, and already renders the distinct expired-access state
  with a working Reactivate/Proceed-to-checkout CTA. **This plan's real SDASH-2 work is
  presentational, not data-plumbing**: replace its `DataTable` (an admin-surface component — see
  §11) with a true Course Card grid per the issue's explicit ask, and resolve real course
  names instead of the current placeholder `shortId(courseId)` fragments (see §7/§9's course-name
  gap).
- **`frontend/src/app/(student)/student/dashboard/page.tsx` ("Overview", SDASH-1) is still the
  original `EmptyState`-only placeholder** from the application-foundation scaffold — this is
  this module's other, larger piece of real work.
- **No student-facing way exists today to resolve a course's name.** `GET /api/v1/courses/{id}`
  is unreachable for a `STUDENT` caller — `CourseAccessGuard.requireCourseAccess` only special-
  cases `TEACHER` ownership before falling through to `PermissionCheckService.requirePermission
  (DomainArea.COURSES, ...)`, and `Role.STUDENT` holds no grant in that matrix (confirmed by
  reading `CourseAccessGuard.java` and `CourseController.java` directly). `lib/api/enrollments.ts`
  already documents this gap in its own header comment and works around it by rendering
  `shortId(courseId)` instead of a name. A student-scoped course-name read is new, narrow backend
  scope this plan adds — see §9.
- **"Module 13" numbering collision, noted so a future reader doesn't file it as an error**: the
  GitHub issue titles this "Module 13: Student dashboard" (MVP implementation order), which is a
  *different* number from `docs/requirements/module-catalog.md`'s "Module 13" (that catalog's
  Module 13 = "course access activation" / Student Payments, owned by `enrollment-management`/
  `payment-management` — already shipped under MVP-010/011/012). The two numbering schemes
  (MVP-XXX ship order vs. source-requirements.md's original module list) are known to diverge;
  this is a pre-existing documentation-numbering fact, not something this plan resolves.

## 1. Business goal

Give a student one backend-filtered, mobile-first landing view of their own enrollment/course/
payment status (Overview), and a reliable, correctly access-state-differentiated list of every
course they have access to (My Courses) — both composed entirely from data that already exists
and is already tenant-scoped and student-scoped in `enrollment-management`, `course-management`,
and `payment-management`. This is explicitly a **read-composition layer**, not a new domain: it
introduces no new business rule, no new mutation, and (per §9) exactly one narrow new read
endpoint to close a real display gap (course names).

## 2. Roles and permissions

**Student-only module.** No staff, Teacher, Tenant Admin, or Platform Admin role interacts with
either screen in this plan — there is no admin/staff counterpart to "my dashboard." No RBAC
matrix change (`PermissionCheckServiceImpl`, `DomainArea` enum) is needed or proposed.

| Role | Access |
|---|---|
| Student | Views own Overview and My Courses — own tenant-scoped, own-student-scoped data only. No student-selector, no id-based navigation to another student's data (per `.claude/rules/ui-ux.md` §1, restated in the issue's own AC). |
| Every other role | No access to these two screens (they live under `app/(student)/`, gated by `RouteGuard kind="tenant"` + the student route group — same mechanism already protecting every other `app/(student)/**` page). |

## 3. Preconditions

- MVP-002 (Authentication Foundation) — student login/session issuance — shipped.
- MVP-008 (Course Management) — `course` table, `GET /api/v1/courses` family — shipped.
- MVP-010 (Order and Payment Foundation) — `student_order`, `payment`, `ledger_entry`,
  `GET /api/v1/ledger/history` — shipped.
- MVP-011 (Manual Payment Slips) — shipped (feeds the same ledger history read).
- MVP-012 (Enrollment and Course Access) — `enrollment` lineage rows, `EnrollmentAccessApi`,
  `GET /api/v1/enrollments/my`, the reactivation-request flow and its frontend screens — shipped
  and is this module's primary data source.
- `attendance-management` and `exam-management` do **not exist in the backend yet** (confirmed —
  no `com.lms.attendancemanagement`/`com.lms.exammanagement` packages exist). The issue's own
  "soft-dependency reads... once available" language already anticipates this; §6 makes the
  consequence explicit.

## 4. User flows

### 4.1 Student Overview (`/student/dashboard`, SDASH-1)

1. Student logs in, lands on (or navigates to) `/student/dashboard`.
2. Page fetches, in parallel, via React Query: `GET /api/v1/enrollments/my` (enrollment/access
   summary) and `GET /api/v1/ledger/history` (payment activity) — both already owner-scoped,
   already tenant-scoped, both already have typed hooks (`useMyEnrollments`,
   `useLedgerHistory`). No new backend call is needed for the Overview itself.
3. Statistic cards render, computed client-side from the above (pure display arithmetic, no
   business logic): active-enrollment count, expired/needs-attention count, most recent payment
   entry (or "no payments yet").
4. If the student has at least one `EXPIRED` enrollment, an alert/callout surfaces it with a
   Reactivate CTA linking to `/student/payments/reactivation` — the same distinct "access
   expired" state MVP-012 already established, not a new state.
5. A short "recent courses" list (top N active enrollments) links into `/student/courses` and,
   individually, is not itself a course workspace deep-link (course-workspace navigation is a
   different, not-yet-built module — see §6) — each Overview row links to `/student/courses`
   generically at MVP, not to a per-course detail route that doesn't exist yet.
6. Zero enrollments → the distinct empty state from the issue's own AC: explains why (no active
   enrollments yet) with a CTA to the course catalog (`/courses`, the existing public storefront
   — the only catalog surface that exists).
7. Any read fails → `ErrorState` with Retry (existing `QueryStateBoundary` behavior), never a raw
   stack trace or blank page.

### 4.2 My Courses (`/student/courses`, SDASH-2 — enhancing the existing page)

1. Student navigates to My Courses (nav item already exists — `StudentNav`).
2. `useMyEnrollments()` (existing, unchanged) fetches the student's own current
   (`supersededAt IS NULL`) enrollment rows.
3. **New**: the page also calls the new `GET /api/v1/courses/my-enrolled-summary` (§9) to resolve
   a display name/category for each enrolled course id, replacing the current `shortId(courseId)`
   placeholder.
4. Each course renders as a **card** (not a table row — see §11) in a responsive grid: course
   name, category, an `AccessStateBadge` (existing component, `ACTIVE`/`EXPIRED` — color is never
   the only signal, per `.claude/rules/ui-ux.md` §4), expiry date or "Lifetime access", and the
   existing Reactivate/"already requested"/"Proceed to checkout" CTA logic (unchanged — already
   correct, see the grounding note).
5. Zero enrollments → same contextual empty state as today (already correct): "no active
   enrollments yet" + CTA to the course catalog.
6. A read fails → `ErrorState` with Retry (already correct via `QueryStateBoundary`).

## 5. Acceptance criteria

Restating the issue's own criteria, made concrete against this design:

- [ ] Student logs in → Overview and My Courses show only the authenticated student's own,
      own-tenant-scoped records — verified server-side by the existing `hasRole('STUDENT')` +
      owner-only endpoints (`/enrollments/my`, `/ledger/history`, and the new
      `/courses/my-enrolled-summary`, §9) — no endpoint on either screen accepts a `studentId`
      parameter from the client.
- [ ] No student-selector or id-based cross-student navigation exists anywhere on either screen.
- [ ] Both screens are mobile-first, single-column below `sm`/`md`, card-based — per
      `.claude/rules/ui-ux.md` §5's "consumer-style surfaces" rule (this is the concrete fix for
      the current My Courses page's `DataTable` use — see §11).
- [ ] Zero active enrollments → Overview and My Courses each show a distinct, contextual empty
      state explaining why, with a CTA to the course catalog (not a shared generic "No data").
- [ ] My Courses lists only backend-confirmed current enrollments (`ACTIVE` or `EXPIRED` —
      `NEVER_ENROLLED` structurally cannot appear, since `/enrollments/my` only returns real
      current rows); an `EXPIRED` row never silently disappears — it renders the distinct
      "access expired" state with a working Reactivate CTA (already shipped in MVP-012, reused
      here unchanged).
- [ ] Every async operation (initial load, retry) exposes `aria-busy`/`aria-live`/`role="alert"`
      per `.claude/rules/ui-ux.md` §4 (already true via `LoadingState`/`ErrorState` — verified,
      not re-implemented).
- [ ] Empty-state and error-message copy never leaks the existence of another tenant's or
      student's courses (no id/count/name of another student's data ever appears in any error or
      empty-state string on either screen).
- [ ] Course-name resolution (§9) never returns a name for a course the calling student has no
      current enrollment in — verified by a cross-tenant/cross-student negative test (§18).

## 6. Out-of-scope items

Per the issue's own explicit scope (SDASH-1..2 only) and this plan's own findings:

- **Attendance and exam summary tiles** — `attendance-management` and `exam-management` do not
  exist in the backend yet (§3). No stat card, alert, or read call for either is built in this
  module; when those domains ship, adding their Overview tiles is that future module's own scope,
  not a retrofit obligation on this plan.
- **A per-course "workspace" or lesson/material navigation target** — Overview's "recent courses"
  and My Courses' cards link to `/student/courses` (or the existing checkout/reactivation routes),
  never to a `/student/courses/{courseId}` workspace/dashboard, since no such route/module exists
  yet (`content-management`'s lesson/material viewer lives under a different, already-shipped
  path: `student/courses/[courseId]/modules/[moduleId]/lessons/[lessonId]/materials`, which this
  plan does not link to from the dashboard — wiring "my courses" into the lesson tree is a
  separate, larger navigation-design question outside this module's scope, see §21).
- **Notifications tile/feed** — `notification-management` (Module 15) is unbuilt; no notification
  read exists to surface.
- **Any new "locked" access-state value** — the issue's Scope bullet mentions "active/expired/
  locked states" but no backend material (ADR-013, `EnrollmentAccessStateType`, `enrollment-
  access.md`) defines a `LOCKED` state anywhere in the shipped system. This plan does **not**
  invent one — see §21 for why this is flagged rather than silently resolved.
- **A dedicated course-name/detail endpoint beyond the minimal summary in §9** — no thumbnail,
  teacher name, description, or price is added to the new endpoint; My Courses is a status
  surface for already-purchased access, not a second storefront.
- **Server-side aggregation/composition endpoint** — per the issue's own Security requirement
  ("flag only if this module introduces its own aggregate/cross-domain query rather than reading
  from each owning domain's already-scoped API"), this plan deliberately composes Overview/My
  Courses **in the frontend**, from each owning domain's existing per-domain endpoint, rather than
  building a new backend aggregation service — see §9.
- **Pagination of My Courses** — `GET /api/v1/enrollments/my` is a plain, unpaginated array by
  design (MVP-012's shipped, documented contract); this plan does not change that endpoint's shape
  (an "approved API contract" change-controlled area) — flagged as a possible future scale
  limitation in §21, not fixed here.

## 7. Domain model

**No new entity, no new table.** This module reads existing `enrollment` (via
`GET /api/v1/enrollments/my`), `ledger_entry` (via `GET /api/v1/ledger/history`), and `course`
(via the one new endpoint in §9) rows exactly as already modeled. The only new artifact is a
read-only **projection** — a `CourseSummaryResponse` DTO (`id`, `name`, `slug`, `category`) —
not a new persisted concept.

## 8. Database design

**None.** No migration is added by this plan. All reads use existing, already-indexed,
already-tenant-scoped queries:

- `enrollment` — already indexed `(tenant_id, student_id, ...)` per MVP-012's `V22`/`V23`/`V24`.
- `ledger_entry` — already tenant-and-student-scoped per MVP-010's schema.
- `course` — the new endpoint (§9) does a plain tenant-scoped `findAllById` over a small id set
  (bounded by the calling student's own enrollment count) — no new index is needed; `course`'s
  existing `(tenant_id, id)`-shaped primary-key lookup already serves this.

## 9. Backend design

**One narrow addition, entirely within existing domain boundaries — no new domain, no ADR
required** (this touches neither tenancy strategy, auth architecture, payment ledger rules,
enrollment activation rules, deployment strategy, migration history, nor any *documented*
(`docs/api/*.md`) API contract; it only extends two already-evolving internal
`api`-package interfaces, which — per `docs/api/enrollment-management.md`'s own explicit framing —
are "internal, cross-module `api`-package calls... never REST endpoints of their own," i.e. not
the "approved API contracts" the change-controls list means):

```java
// com.lms.enrollmentmanagement.api.EnrollmentAccessApi — ADD one method,
// following the interface's own "deliberately minimal, grow narrowly per real
// consumer need" precedent (already grew this way once, MVP-012 -> this plan).
Set<UUID> myCurrentEnrolledCourseIds();
// Resolves student + tenant exclusively from AuthenticatedPrincipalHolder/TenantContext
// (no caller-supplied id, matching every other method on this interface). Returns the
// course_id of every CURRENT (supersededAt IS NULL) enrollment row for the calling
// student - i.e. exactly the set GET /api/v1/enrollments/my already returns, expressed
// as course ids only. No superseded/historical course id is included (My Courses never
// renders a superseded row, so no consumer needs one).
```

```java
// com.lms.coursemanagement — NEW endpoint, new DTO, no change to CourseController's
// existing methods.
GET /api/v1/courses/my-enrolled-summary
// @PreAuthorize("hasRole('STUDENT')"). Takes NO id/query parameter at all - mirrors
// GET /api/v1/enrollments/my's "no id param" anti-enumeration-by-construction shape
// exactly, deliberately not a bulk-lookup-by-client-supplied-ids endpoint (that shape
// would need its own ownership check per id and adds enumeration surface for no real
// benefit, since the caller already knows only its own ids matter).
//
// Implementation: CourseSummaryService calls EnrollmentAccessApi.myCurrentEnrolledCourseIds()
// (the one narrow cross-module read above), then CourseRepository.findAllById(...) scoped
// to the caller's tenant (existing tenant-aware repository base - no new bypass), maps to
// CourseSummaryResponse[] (id, name, slug, category). A course id the enrollment read
// returns but that no longer resolves in course-management (should be structurally
// unreachable - course rows are never hard-deleted post-enrollment, per this codebase's
// existing soft-delete/status-based lifecycle) is simply omitted, not a 500.
```

**Explicitly not built**: any general-purpose "look up any course by id as a student" endpoint.
The new endpoint is authorization-by-construction (its result set is always exactly the caller's
own current enrollments) rather than authorization-by-check (accepting ids and validating each) —
this is a deliberate, narrower design, consistent with `/enrollments/my`'s own precedent, and
should not be widened without a real new consumer need (mirrors `EnrollmentAccessApi`'s and
`CourseLookupApi`'s own stated "don't over-build" discipline).

**Overview and My Courses composition itself is frontend-only** (React Query calls to the
existing `/enrollments/my`, `/ledger/history`, and the new `/courses/my-enrolled-summary`,
combined client-side) — no backend aggregation service, no new domain, per §6's explicit framing
and the issue's own security requirement.

## 10. API contract

New endpoint (added to `docs/api/course-management.md` via the `review-api-contract` skill before
implementation — sketched here for planning purposes only):

| Method + path | Auth | Purpose |
|---|---|---|
| `GET /api/v1/courses/my-enrolled-summary` | `hasRole('STUDENT')`, owner-only, no id param | `CourseSummaryResponse[]` — `{ id, name, slug, category }` for every course the caller has a current enrollment in. Empty array if none. |

Every other endpoint this module's frontend calls is **already shipped and already documented**,
reused unchanged:

| Method + path | Doc | Purpose in this module |
|---|---|---|
| `GET /api/v1/enrollments/my` | `docs/api/enrollment-management.md` | Enrollment/access-state list (My Courses rows, Overview stat cards) |
| `GET /api/v1/reactivation-requests/my` | `docs/api/enrollment-management.md` | Distinguishes "reactivation already requested" vs "approved, proceed to checkout" (already wired, unchanged) |
| `GET /api/v1/ledger/history` | `docs/api/payment-management.md` | Recent payment activity (Overview) |

`CourseSummaryResponse` shape:

```jsonc
{
  "id": "...",
  "name": "Intro to Algebra",
  "slug": "intro-to-algebra",
  "category": "Mathematics"
}
```

Response envelope, auth header, and tenant/role-never-client-supplied conventions are identical
to every other endpoint in this codebase (`ApiResponse<T>` — see `docs/api/identity-access-
service.md`'s "Response envelope" section, not repeated here).

## 11. Frontend screens

- **`app/(student)/student/dashboard/page.tsx` (rebuild — currently a static placeholder)**:
  Overview per §4.1. New shared components: a `StatCard` (label, value, optional trend/alert
  styling — generic enough for reuse by other portals' still-placeholder dashboards later, but
  this plan only wires it into the Student portal) under `components/students/` (feature-specific,
  co-located per `.claude/rules/frontend.md`'s "extract to shared only when ≥2 role groups need
  it" guidance — not `components/ui/` yet, since no other portal consumes it in this plan's
  scope), and reuses `AccessStateBadge`/`QueryStateBoundary`/`EmptyState`/`ErrorState` unchanged.
- **`app/(student)/student/courses/page.tsx` (enhance — see grounding note)**: replace the
  `DataTable` usage with a **Course Card grid** — a `<ul className="grid grid-cols-1 sm:grid-
  cols-2 lg:grid-cols-3">` of card `<li>`s, mirroring `app/(public)/courses/page.tsx`'s existing
  `CourseCard` pattern (already establishes this exact visual/markup convention for a course
  grid in this codebase) — each card: course name (from the new endpoint, §9), category,
  `AccessStateBadge`, expiry/"Lifetime access" text, and the existing Reactivate/"already
  requested"/"Proceed to checkout" CTA logic (unchanged). `DataTable` remains correctly in place
  for its actual intended use (admin-surface tables); this plan does not touch it.
- Both pages: loading (`LoadingState`, `aria-busy`), contextual empty state (`EmptyState`, CTA to
  `/courses`), error state with Retry (`ErrorState`, `role="alert"`), and — since neither screen
  has any staff/admin variant — no permission-denied state beyond the existing `RouteGuard`/401
  handling already at the layout level; all via the existing `QueryStateBoundary`, not
  reimplemented per page (per `.claude/rules/frontend.md`'s shared-state-component rule).
- No nav change — `StudentNav`'s "Dashboard" and "My Courses" entries already point at both
  routes.

## 12. Validation rules

None — both screens are pure reads with no form, no mutation, no user-supplied input beyond
navigation. The one new endpoint (§9) takes no request body/params to validate.

## 13. Error cases

| Scenario | Response |
|---|---|
| Unauthenticated/expired session on either screen | `401` → `QueryStateBoundary`'s existing redirect-to-login (`loginPath="/login"` + `?reason=session_expired`), unchanged pattern |
| A non-Student role somehow reaches `/student/**` | Blocked upstream by `RouteGuard kind="tenant"` at the layout; the new endpoint itself also `403`s any non-`STUDENT` caller server-side (defense in depth, never relying on the frontend guard alone) |
| `GET /api/v1/courses/my-enrolled-summary` called with zero current enrollments | `200` with an empty array — not an error; My Courses/Overview render their own empty states from this |
| Any of the three reads fails (network/5xx) | `ErrorState` with Retry, independently per query — one failed read (e.g. ledger history) does not blank out a successful one (e.g. enrollments) on the Overview page |
| A course id present in the caller's enrollment set is somehow no longer resolvable in `course-management` | Silently omitted from the summary response (not a 500) — see §9's note; the enrollment row itself still renders on My Courses with its existing `shortId` fallback if the name lookup omits it |

## 14. Tenant-isolation rules

- Every read this module's frontend performs already resolves tenant identity exclusively from
  the authenticated request context (`TenantContext`/`AuthenticatedPrincipal`) — no endpoint used
  or added here accepts a `tenantId` or `studentId` from the client, on either the existing three
  endpoints or the one new endpoint.
- The new `EnrollmentAccessApi.myCurrentEnrolledCourseIds()` method and the new course-management
  endpoint both resolve tenant/student exclusively server-side, mirroring every existing method on
  both interfaces — no overload accepting a caller-supplied id is added.
- `course-management`'s new `CourseSummaryService` reads `course` rows through the same
  tenant-aware repository base every other `course-management` query already uses — no new
  bypass, no native/cross-tenant query.
- Mandatory cross-tenant negative test (§18) on the new endpoint: a student in Tenant A must never
  receive a course summary for a course id that only exists (even coincidentally, same slug/name)
  in Tenant B.

## 15. Security rules

- No new activation, mutation, or state-changing code path exists anywhere in this module — it is
  read-only by construction, so none of the enrollment-activation/payment-ledger change-control
  rules in `.claude/rules/payments.md`/`enrollment-access.md` are implicated.
- The new endpoint is authorization-by-construction (§9) — it cannot be asked to return a course
  the caller isn't currently enrolled in, because it never accepts an id to check against in the
  first place. This removes an entire class of "did we check ownership correctly" bug that an
  id-accepting bulk-lookup endpoint would otherwise need a test to rule out.
- Empty-state and error copy on both screens is static, role-appropriate text (already true on
  the existing My Courses page; carried into the rebuilt Overview) — neither screen echoes back
  another student's/tenant's identifiers in any string, satisfying the issue's own "must not leak
  existence of another tenant's/student's courses through error-message content" requirement by
  construction (there is no code path where another party's data could even be interpolated into
  a message here).

## 16. Audit requirements

**None.** Per `.claude/rules/security.md`'s canonical audit-logged-action list (price changes,
payment approvals/rejections, device resets, access/expiry extensions, reactivation approvals,
content deletions, settlement changes, impersonation) — nothing in this module performs any of
these actions. Both screens are pure reads; the one new endpoint is a pure read. No
`AuditLogApi.record(...)` call is added or needed.

## 17. Payment impact

**None — read-only.** This module adds no new `ledger_entry`, no new `Payment`/`Order` write
path, no new enrollment-activation path, and does not touch `OrderService`, `PaymentConfirmation
Service`, or any ledger/settlement code. `GET /api/v1/ledger/history` (existing, MVP-010/011) is
read unchanged for the Overview's payment-activity tile. No Phase 1–4 payment-roadmap boundary is
affected.

## 18. Tests

Per `module-catalog.md`'s required-test mapping for `course-management` ("cross-tenant test on
course CRUD... and course listing/search") and `enrollment-management` ("cross-tenant test on
enrollment read/list"), extended for this module's one new surface:

**Backend — unit**
- `CourseSummaryService`: given a set of course ids from `EnrollmentAccessApi`, returns exactly
  the matching `CourseSummaryResponse` rows, tenant-scoped; a stale/unresolvable course id is
  omitted, not thrown.
- `myCurrentEnrolledCourseIds()`: no enrollments → empty set; mixed active/expired current rows →
  both included (course-name resolution doesn't care about access state, only "currently
  enrolled"); a superseded (reactivated-away) row's course id is excluded when the same course
  also has a newer current row for the same student (no duplicate), and excluded entirely if no
  current row exists for it.

**Backend — integration / Testcontainers**
- `GET /api/v1/courses/my-enrolled-summary`: returns only the calling student's own enrolled
  courses; a second student (same tenant) with different enrollments gets a disjoint result from
  the same call.
- **Mandatory cross-tenant negative test**: Student A (Tenant 1) and Student B (Tenant 2), Tenant
  2 has a course with the same name/slug as one of Tenant 1's — Student A's summary must never
  include Tenant 2's course row, and vice versa.
- Authorization: a Teacher, Tenant Admin, or unauthenticated caller hitting the new endpoint gets
  `403`/`401` (never leaks a course list).
- Regression: existing `GET /api/v1/enrollments/my` and `GET /api/v1/ledger/history` tests are
  unaffected (this plan makes no change to either).

**Frontend / Playwright**
- Overview: loading skeleton/`aria-busy` state; empty state (zero enrollments) with working
  catalog CTA; populated state shows correct active/expired counts and a working Reactivate CTA
  when at least one enrollment is expired; error state with a working Retry that re-fires the
  failed query only.
- My Courses: populated state renders real course names (not `shortId` fragments) in a card grid
  at `sm`/`md`/`lg` breakpoints (visual/layout assertion — single column below `sm`, multi-column
  at `lg`); expired row still shows the distinct access-expired state + correct CTA variant
  (Reactivate / already-requested / proceed-to-checkout) exactly as today — a **regression** test
  proving the presentational rework didn't change any of the existing CTA-selection logic from
  the grounding note.
- Accessibility: keyboard-only navigation through both card grids and their CTAs; `aria-live`/
  `role="alert"` on load/error per `.claude/rules/ui-ux.md` §4 (verify, don't just assume the
  shared components carry it through into a grid layout).
- Cross-tenant/cross-student manual-seed check (via API, not through UI navigation, since no UI
  path to another student's data exists to click through in the first place): confirm no course
  name or count from a different tenant's seed data ever renders.

## 19. Documentation changes

- **`docs/api/course-management.md`**: add the new `GET /api/v1/courses/my-enrolled-summary`
  entry (via `review-api-contract`, finalized against the real shipped shape) — §10's sketch.
- **`docs/ui-ux/screen-map.md`**: no new screen rows needed (both `Student > Dashboard > Overview`
  and `Student > Courses > My Courses` already exist as entries) — but its existing one-line
  descriptions should be checked against what actually ships and corrected if they've drifted
  (e.g. "Overview" currently reads "enrollment/payment/attendance/exam summary + alerts" — this
  MVP ships enrollment+payment only, per §6; update the line to reflect that attendance/exam are
  future additions, not silently drop the mention).
- **`docs/ui-ux/` — new or extended conventions note**: per the issue's own documentation
  requirement ("Student home/overview conventions; empty/loading/error conventions per shared
  state-component pattern"), record the Statistic-Card / Course-Card composition pattern
  established here (and its relationship to `DataTable`'s admin-surface-only scope, per §11) so
  the next consumer-style module reuses it rather than re-deriving the table-vs-card-grid
  decision from scratch.
- **`docs/requirements/module-catalog.md`**: no change needed — `course-management` and
  `enrollment-management`'s existing "Owns"/"Consumes" bullets already cover this plan's scope at
  the right granularity (a new narrow read method on each's already-listed `api` boundary is not
  a domain-ownership change).

## 20. Implementation order

Per root `CLAUDE.md`'s development workflow (plan → backend → backend tests → frontend →
frontend/E2E tests → security/tenant/integration review → docs → one logical commit per step):

1. **`implement-backend`**: `EnrollmentAccessApi.myCurrentEnrolledCourseIds()` (enrollment-
   management), `CourseSummaryService` + `GET /api/v1/courses/my-enrolled-summary` +
   `CourseSummaryResponse` DTO (course-management). No migration.
2. Backend tests (§18 unit/integration, including the mandatory cross-tenant test) — run and
   reviewed before frontend work starts.
3. **`implement-frontend`**: add `getMyEnrolledCourseSummaries`/`useMyEnrolledCourseSummaries` to
   `lib/api/courses.ts`; build `StatCard` and rebuild `student/dashboard/page.tsx` (SDASH-1);
   rework `student/courses/page.tsx` to a Course Card grid consuming the new summary data
   (SDASH-2).
4. Frontend + Playwright E2E tests (§18).
5. **`security-review`**, **`tenant-isolation-review`** skills — explicit passes, focused on the
   one new endpoint's authorization-by-construction claim (§9/§15) and the cross-tenant test.
   (`payment-ledger-review` is not required — this module makes no payment/ledger-write change,
   per §17 — but is a cheap sanity pass given `GET /api/v1/ledger/history` is read here.)
6. **`ui-ux-review`** skill — verify the card-grid rework actually satisfies `.claude/rules/
   ui-ux.md` §4/§5 (accessibility bar, consumer-surface responsive pattern) rather than assuming
   it from the plan text alone.
7. **`update-documentation`** skill — §19's file list.
8. Commit as logically-scoped units per `.claude/rules/git-workflow.md` (e.g., "backend: add
   student-scoped enrolled-course summary read", "frontend: implement student dashboard overview
   (SDASH-1)", "frontend: rework My Courses to a card grid with real course names (SDASH-2)",
   "docs: document my-enrolled-summary endpoint and dashboard UI conventions") — never bundling
   backend and frontend into one commit per root `CLAUDE.md`.

## 21. Risks and unresolved decisions

None of these are resolved by this plan — implementation must not silently assume an answer:

1. **The issue's own AC references an "active/expired/locked" tri-state**, but no shipped backend
   material defines a `LOCKED` access state anywhere (`EnrollmentAccessStateType` is exactly
   `NEVER_ENROLLED | ACTIVE | EXPIRED`, per ADR-013 and `docs/api/enrollment-management.md`). This
   plan implements the two states the backend actually has (`ACTIVE`/`EXPIRED` — `NEVER_ENROLLED`
   never appears in a current-enrollment list by construction) and does **not** invent a third.
   If "locked" refers to a genuinely different, not-yet-scoped concept (e.g. a future content-
   level prerequisite lock, unrelated to `enrollment-management`'s access-currency model), that
   needs its own product decision and its own module — not a value bolted onto this plan's scope.
2. **Whether the new `GET /api/v1/courses/my-enrolled-summary` endpoint's shape should grow
   (thumbnail, teacher name, short description)** for a richer Course Card — this plan
   deliberately ships the minimal four-field shape (§9's "don't over-build" framing) since nothing
   in the issue's AC requires more; a future UI-polish pass can extend the DTO without touching
   ownership/auth semantics.
3. **`GET /api/v1/enrollments/my`'s unpaginated-array shape** (§6) is a real, if distant, scale
   risk for a student with a very large number of course enrollments — not addressed here since
   changing that endpoint's shape is an "approved API contract" change-controlled area outside
   this plan's remit; flagged forward for whoever eventually revisits MVP-012's contract.
4. **Whether/how "My Courses" should eventually link into each course's lesson/material
   workspace** (§6) — today there is no `/student/courses/{courseId}` landing route at all (only
   the deep `.../modules/[moduleId]/lessons/[lessonId]/materials` path, which needs a specific
   lesson/module id this dashboard has no way to resolve). This is a real, currently-unowned
   navigation gap in the product, not something this plan should paper over with a placeholder
   link to a route that doesn't exist.
5. **Whether the new `StatCard` component should move to a shared `components/ui/` (or a new
   `components/dashboard/`) location now, anticipating Teacher/Tenant-Admin/Platform-Admin
   dashboards' own still-placeholder Overview pages reusing it** — this plan places it under
   `components/students/` per the "extract to shared only when a second role-group actually needs
   it" rule in `.claude/rules/frontend.md`, deliberately not pre-extracting it speculatively;
   flagged so the next dashboard module doesn't duplicate it without checking here first.

## Related

- `docs/plans/MVP-012 Enrollment and Course Access.md` (source of the current My Courses page and
  its own §21 item 9 forward-flag that this plan resolves)
- `docs/architecture/enrollment-access.md`
- `docs/adr/ADR-013-enrollment-lineage-and-reactivation-order-gate.md`
- `docs/api/enrollment-management.md`, `docs/api/payment-management.md`,
  `docs/api/course-management.md`
- `docs/ui-ux/screen-map.md`, `.claude/rules/ui-ux.md`, `.claude/rules/frontend.md`
- `docs/requirements/module-catalog.md`, `docs/requirements/portals-overview.md`
