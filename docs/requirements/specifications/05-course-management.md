# Course Management

**Domain:** `course-management` (Module 6) · **Portal(s):** Teacher, Tenant Admin, Public (storefront)

## 1. Business purpose

Let teachers (and tenant admins) build sellable/learnable courses (category, structure, pricing,
enrollment rules, materials) that convert into enrollments and public storefront listings.

Source: `docs/requirements/source-requirements.md` Module 6.

## 2. Actors

- **Teacher** — creator, primary owner
- **Teacher Assistant** — create/edit content, no publish/pricing (PROVISIONAL)
- **Tenant Admin / Institute Owner** — full CRUD + approval
- **Course Coordinator** — `V/C/E/A`
- **Content Manager, Finance Staff, Student Support, Exam Manager, Attendance Operator** — `V` only
- **Student** — consumer, enrolls
- **Anonymous / Public** — views published courses on storefront

## 3. Preconditions

Acting Teacher is approved and assigned to the tenant; tenant is active.

## 4. Normal flow

1. Teacher opens `My Courses`, selects "New Course."
2. Multi-step Course Builder: category, subject/stream/grade/year, pricing, enrollment rules, access duration, visibility (draft/private/public), prerequisites.
3. Teacher adds Modules & Lessons, uploads Materials.
4. Course remains `DRAFT` — not visible on public storefront until explicitly published.
5. Teacher (or Tenant Admin, per tenant policy) submits for review/publish; if tenant policy requires approval, course enters an under-review state visible to Tenant Admin.
6. On publish, course appears on the public storefront listing/detail, subject to visibility settings.
7. Teacher can later clone, archive, or update pricing; price changes on a published course are audit-logged via a single non-bypassable code path.

## 5. Alternative flows

- Backend 422 on tenant-scoped slug/name uniqueness conflict even after client-side Zod validation passed — frontend must still handle it.
- Teacher assigns/reassigns a course's teacher: only Tenant Admin or a permitted staff sub-role may do this — not the teacher themselves.
- Cloning a course: must never carry over enrollment/payment history, and never carries over prior reviews.
- Reviews on a course: only verified-enrollment students may submit; only approved reviews are publicly visible (see [26-course-reviews.md](./26-course-reviews.md)).
- A student from tenant A attempts to view/enroll in a course belonging to tenant B by direct URL/ID manipulation: rejected.

## 6. Authorization rules

Per `docs/requirements/user-roles-and-permissions.md` §2, row "Courses": Institute Owner =
`V/C/E/D`; Finance Staff = `V`; Course Coordinator = `V/C/E/A`; Student Support = `V`; Content
Manager = `V`; Exam Manager = `V`; Attendance Operator = `V`; Read-only Auditor = `V`. Only
Tenant Admin (or a permitted staff sub-role) can assign/reassign a course's teacher (FR-CM-3).

## 7. Tenant rules

- Course table is tenant-owned; not visible on storefront until explicitly published.
- Public storefront listing reads are tenant-scoped-by-subdomain (not a cross-tenant aggregate) — no repository bypass needed here, distinct from Platform Admin cross-tenant reporting.
- Course preview must render through the same branding-consistent pipeline as the live storefront — never a separate preview-only rendering path.

## 8. Acceptance criteria

- [ ] Given a course in `DRAFT` status, then it never appears on the public storefront regardless of direct-URL guessing.
- [ ] Given a price change on a published course, then exactly one audit log entry is written with actor/before/after.
- [ ] Given a Teacher attempts to assign a different teacher to their own course, then the action is rejected unless they hold the permitted authority.
- [ ] Given a course clone action, then the new course has zero enrollment/payment records and zero reviews copied over.
- [ ] Given a student without a verified enrollment, when they attempt to submit a course review, then the submission is rejected.
- [ ] Cross-tenant negative test on course CRUD, pricing/enrollment-rule fields, and course listing/search.
- [ ] Empty state for Teacher's "My Courses" (no assigned courses yet) differs from Tenant Admin's course-list empty state.
- [ ] Course Builder multi-step form is fully keyboard-navigable.

## 9. Audit requirements

**Mandatory.** Pricing changes on a published course must be audit-logged via a single
non-bypassable code path, matching `.claude/rules/security.md`'s mandatory list item
"course/session price changes." Entry must capture actor ID, tenant ID, target course ID,
timestamp, and before/after price.

## 10. MVP or later-phase classification

**MVP** for creation/category/pricing/enrollment rules/teacher assignment/materials attachment
(FR-CM-1 to FR-CM-4; `source-requirements.md` §5 MVP list "Course management"). Landing-page
builder/bundles/prerequisites/cloning/archive/SEO (FR-CM-5) and reviews (FR-CM-6) are **Phase 2**.

## UI-state and portal notes

- **Portal placement**: Teacher `Courses > My Courses`, `Course Builder`, `Module & Lesson Editor`, `Landing Page & SEO`, `Course Reviews`; Tenant Admin `Courses > Course List`, `Course Detail / Approval`; Public `Course Listing`, `Course Detail`.
- Empty states: Teacher "no assigned courses yet" (guidance to contact tenant admin) is distinct from Student "no active enrollments yet."

## Open decisions

- Whether Course Coordinator's course-approval authority (`A`) requires a second approver for high-value/published courses — not specified anywhere.
- Whether `course-management` or a not-yet-named domain owns the review submission/moderation *workflow* vs. just the toggle — unresolved domain-ownership question.
- Publish-approval policy itself ("if tenant policy requires Tenant Admin approval") is described as conditional per tenant but no tenant-configuration mechanism for this policy is defined anywhere.
- **Phase-boundary note carried from Lessons & Materials**: `source-requirements.md` module 7 implies MVP scope includes attaching Zoom recordings to lessons, but `live-class-management` (which owns Zoom recording management) is entirely Phase 2 — see [06-lessons-and-materials.md](./06-lessons-and-materials.md).
