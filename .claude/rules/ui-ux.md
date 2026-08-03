---
paths:
  - "frontend/**"
---

# UI/UX Rules

These rules extend the baseline in `frontend/CLAUDE.md` (loading/empty/error/permission-denied
states, responsive behavior, accessible form labels — required on every page). They define
*what* those states and behaviors must contain in this specific multi-tenant LMS, across the
Student, Teacher, Tenant Admin, and Platform Admin experiences.

## 1. Role scope and visual unambiguity

The UI must make "whose data am I looking at, and as whom" unambiguous at all times. Scope must
never be inferable only from URL params or table contents.

- **Platform Admin**
  - Any cross-tenant list/table view (tenant list, platform-wide reports, support queues) must
    show the tenant name/identifier on every row.
  - When a Platform Admin drills into a single tenant's data (e.g. a tenant's students, a
    tenant's payments), the page must render a persistent, non-dismissible tenant-context
    banner or breadcrumb naming that tenant for as long as the admin remains in that context.
  - No destructive or state-changing action (approve tenant, suspend tenant, refund payment,
    reset device) may be submittable from a screen where the target tenant is not visibly
    named next to the action.
- **Tenant Admin**
  - Views are scoped to exactly one tenant (the admin's own). Do not render a tenant
    selector/switcher anywhere in the Tenant Admin experience — its absence is itself part of
    how scope is communicated, not just styling.
  - If a "support impersonation" or "view as tenant" capability exists for Platform Admin, it
    must be a visually loud, distinct mode (banner + color treatment) driven by a backend-issued
    impersonation session — never a locally toggled UI state.
- **Teacher**
  - Course lists, rosters, and materials must render only backend-authorized assignments. Do
    not fetch a full/unfiltered dataset and filter it client-side "for convenience" — that
    implies the frontend is trusted with data it should never have received.
- **Student**
  - All personal views (enrollments, payment history, exam history, materials, device list)
    render only that student's own backend-returned records; no student-selector or ID-based
    navigation to another student's data should exist in this role's UI.
- **Staff sub-roles** (Finance Staff, Course Coordinator, Student Support, Content Manager,
  Exam Manager, Attendance Operator, Read-only Auditor)
  - Hide or disable actions outside the sub-role's permission set (e.g. Finance Staff sees no
    "Edit Course Content" button; Read-only Auditor sees no mutating controls at all).
  - This hiding is a UX convenience, not enforcement — every hidden/disabled action's backend
    endpoint must still independently reject unauthorized calls, and the corresponding UI must
    still handle a 403 gracefully (see permission-denied state, baseline requirement) in case a
    stale UI state exposes an action it shouldn't.
- **General rule**: any page rendering tenant- or role-scoped data must show that scope in a
  persistent UI element (header, breadcrumb, or banner), never rely solely on the data being
  correct behind the scenes.

## 2. White-label / branding UX rules

- Tenant branding (logo, color theme, favicon, custom LMS name) must be resolved and fetched at
  runtime from tenant-scoped configuration (resolved via subdomain/custom domain or the
  authenticated tenant context) — never hardcoded per environment, never baked into the build,
  never shared as a single static app-shell asset (e.g. one global `favicon.ico` or `logo.svg`
  used for all tenant-facing routes).
- The tenant-specific login page must resolve branding strictly from the tenant matched by the
  incoming request's domain/subdomain. If the tenant cannot be unambiguously resolved, fall back
  to neutral platform-default branding — never to a previously cached or another tenant's
  branding.
- Any client-side caching of branding/theme data must key the cache by tenant id. This matters
  in local/dev/test flows where a browser session may navigate between different tenant
  subdomains or an impersonation session — stale cross-tenant branding is a leak, not a
  cosmetic bug.
- Theme presets and tenant custom colors apply as CSS variable overrides layered on top of the
  existing design system tokens, not as full page reskins or per-tenant component forks. This
  keeps shadcn/ui component behavior/contracts intact across tenants.
- Light/dark mode is orthogonal to tenant theming: both must be validated together. A tenant's
  brand color must have a defined (or programmatically derived) light-mode and dark-mode
  variant — do not assume dark mode "just works" because it does for the platform default
  palette.
- The branding preview panel (admin-facing, for previewing a tenant's theme before saving) must
  render through the same theming pipeline used by the live tenant site. Do not build a separate
  preview-only rendering path — that guarantees preview/production drift.
- Per-tenant favicon, certificate branding, and invoice branding are tenant-scoped assets and
  must be resolved the same way as the logo (tenant-scoped config, not a static default embedded
  in the app shell for tenant-facing or generated-document routes).

## 3. Empty-state content guidance

Do not implement a single generic `<EmptyState />` with static "No data" copy reused across the
app. Empty states must be contextual to the surface and must answer: *why is this empty* and
*what can the user do next*. Examples of required differentiation:

- Empty course list (Student): explain no active enrollments yet, with a CTA to the course
  catalog.
- Empty course list (Teacher): explain no assigned courses yet, with guidance to contact the
  tenant admin if this is unexpected.
- Empty payment history: distinguish "no payments have been made yet" from "no payments match
  the selected date range/filter" — these require different copy and different next actions.
- Empty exam list: distinguish "no exams scheduled" (nothing created yet) from "no published
  exams" (drafts exist but nothing is visible to the student) — different audiences, different
  messaging.
- Empty staff/student list (Tenant Admin): must include the relevant creation/import CTA (add
  staff, bulk-import students) so the empty state is actionable, not a dead end.
- Empty state after applying filters/search must read as "no results match your filters" with a
  clear-filters action — this is a distinct state from a true zero-data empty state and must not
  reuse the same copy.

A shared empty-state *component* is fine and encouraged for consistency, but its title,
description, and action must be supplied per call site — a call site relying on the component's
default/generic copy is treated as an incomplete implementation.

## 4. Accessibility bar (extends "accessible form labels")

The baseline requirement of accessible form labels extends to the full interaction and status
surface of the app:

- **Keyboard navigability**: every interactive flow — multi-step forms (course creation,
  enrollment, payment slip upload), modals/dialogs, dropdown/menu components, data-table row
  actions, drag-and-drop material/lesson ordering — must be fully operable via keyboard alone.
  Modals must trap focus while open and return focus to the triggering element on close. Tab
  order must follow visual/logical order.
- **Screen-reader-usable async status**: loading states must expose `aria-busy` and/or an
  `aria-live="polite"` region so a screen-reader user is told something is in progress (payment
  submission, exam submission, save/import operations). Error messages/toasts resulting from
  async operations must use `role="alert"` or `aria-live="assertive"` so failures are announced,
  not just visually rendered.
- **Color contrast with tenant theme colors**: when a tenant supplies a custom brand color, the
  UI must not assume it is accessible. Run tenant colors through a contrast check against the
  text colors they'll pair with (e.g. WCAG AA against white/black), and either auto-select an
  accessible foreground or flag the color as failing at the point the tenant configures it
  (branding settings / preview panel), rather than silently rendering low-contrast tenant UI.
- **Icon-only controls**: icon-only buttons common in admin tables (edit, delete, reset device,
  approve/reject payment) require an `aria-label`, not just a `title` attribute.
- **Status indicators**: payment status, exam status, device status, tenant status badges must
  not rely on color alone to convey meaning — pair color with text and/or an icon.

## 5. Responsive behavior patterns

This project has two distinct UI archetypes that need different responsive strategies — treat a
page as incomplete if it only targets one when it should target the other.

- **Consumer-style surfaces** (Student and Teacher dashboards, course pages, exam-taking UI):
  mobile-first. Single-column stacking below `sm`/`md`, card-based layouts, and a
  collapsible/bottom navigation pattern on mobile widths.
- **Admin-heavy surfaces** (Tenant Admin and Platform Admin dashboards, data tables, reports):
  optimized primarily for `md` and above, but must define an explicit mobile fallback rather
  than being desktop-only:
    - Dense data tables (student lists, payment lists, audit logs, staff lists) must convert to
      a card/list view below `md`, or use horizontal scroll with a sticky first (identifying)
      column.
    - Never silently hide/truncate table columns on small screens without an alternate way to
      access that data (column-visibility toggle or an expandable row detail view).
- Standardize on Tailwind's default breakpoints (`sm: 640px`, `md: 768px`, `lg: 1024px`,
  `xl: 1280px`). Do not introduce ad hoc, page-specific breakpoints.
- Any data-heavy admin screen must have its mobile/narrow-viewport behavior defined at design
  time, not left as "works fine at desktop width" — flag such pages as incomplete during review.
- Modals/drawers on mobile viewports should use a full-screen sheet pattern rather than a
  fixed-width centered modal, especially for forms with meaningful input (payment slip upload,
  bulk actions, course creation steps) where a cramped modal makes the form unusable.
