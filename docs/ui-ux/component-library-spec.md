# Component Library Specification

Status: Draft — written spec (Figma canvas build blocked, see `docs/ui-ux/
authentication-design-spec.md` §7 for the current MCP quota status). This is the
concrete, build-ready specification for every component required by the brief's
"Required reusable components" list and "Required status patterns" list. It extends —
does not replace — `docs/ui-ux/design-system.md`, which covers the *structural and
behavioral* approach (layering, theming mechanism, responsive strategy). This document
covers each component's *anatomy, variants, states, and token bindings*, which
`design-system.md` explicitly leaves out of scope.

Related: `docs/ui-ux/design-system.md`, `docs/ui-ux/accessibility.md`,
`.claude/rules/frontend.md`, `.claude/rules/ui-ux.md`

## 0. Conventions used throughout this document

- **Figma location:** every component lives on the `04 Design System` section
  (page A), one frame per component (or one shared frame for a tightly-related family,
  per `design-system.md` §1) — matches the brief's "avoid duplicate components" rule.
  `15 Components` (also page A) holds *instances* of these with usage annotations, never
  redefinitions.
- **Token bindings:** reference the Figma variables already created — `color/*`
  (Color / Light and Color / Dark collections), `radius/*`, `spacing/*` — by name.
  Nothing below introduces a new token; components bind to what exists.
- **Shared state axis** (documented once, referenced per component rather than
  repeated): `Default`, `Hover`, `Focus-visible`, `Active/Pressed`, `Disabled`, plus
  `Loading` and `Error`/`Invalid` where the component supports them. Per the
  design-system-builder guidance on capping variant matrices, **only `Default` and
  `Disabled` are baked as true Figma variants** for most components — `Hover`/
  `Focus-visible`/`Active` are built as Figma **interactive component states**
  (prototype-level, not variant-grid) to keep variant counts sane. Any component where
  this default doesn't apply says so explicitly.
- **Code alignment:** `frontend/src/components/ui/button.tsx` is the only component
  implemented in code so far — it uses `@base-ui/react` primitives (not Radix) + `class-
  variance-authority` (CVA). Every future shadcn/ui component this project adds should
  be assumed to follow the same `@base-ui/react` + CVA pattern unless a component's own
  code says otherwise — flagged here so Figma naming and code naming stay predictable
  once implementation starts.
- **Accessibility baseline** (also documented once): every interactive component is
  fully keyboard-operable, has a visible focus indicator (per `03 Foundations`'s focus
  token), and — for icon-only variants — requires a bound `aria-label` per instance, not
  a shared generic one (`docs/ui-ux/accessibility.md` §4).

---

## 1. Form & input primitives

### 1.1 Button — spec matches code exactly

Code source: `frontend/src/components/ui/button.tsx` (already implemented — this is a
mirror, not a proposal).

| Property | Values |
|---|---|
| `variant` | `default` (primary), `outline`, `secondary`, `ghost`, `destructive`, `link` |
| `size` | `default`, `xs`, `sm`, `lg` |
| `state` | `Default`, `Disabled` (baked variants); `Hover`/`Focus-visible`/`Active` as interactive states |

Icon-only sizes (`icon`, `icon-xs`, `icon-sm`, `icon-lg`) are **not** part of this
component's variant grid — they move to §1.2 Icon Button, since code already treats them
as a distinct size family and combining both here would push the variant matrix past the
30-combination cap (6 variant × 4 size × 2 state = 48 already; adding 4 icon sizes would
make it worse, not better).

- **Token bindings:** `default` variant → fill `color/primary`, text `color/primary-
  foreground`; `outline` → stroke `color/border`, fill `color/background`; `destructive`
  → fill `color/destructive` at reduced opacity per code's `bg-destructive/10` pattern;
  radius `radius/lg` (matches code's `rounded-lg` base); focus ring `color/ring` at 50%
  opacity, 3px, per code's `focus-visible:ring-3 ring-ring/50`.
- **Anatomy:** optional leading icon slot (INSTANCE_SWAP, Lucide) → label text → optional
  trailing icon slot.
- **Accessibility:** disabled state sets `aria-disabled` and is excluded from tab order
  per native `<button disabled>` semantics; loading state (used on form-submit buttons
  across the app, e.g. Authentication screens) is a `Boolean` component property
  (`isLoading`) that swaps the leading-icon slot for a spinner and sets `aria-busy`.

### 1.2 Icon Button

| Property | Values |
|---|---|
| `size` | `xs`, `sm`, `default`, `lg` (matches Button's `icon-*` size family in code) |
| `variant` | `default`, `outline`, `ghost`, `destructive` |
| `state` | `Default`, `Disabled` |

- **Anatomy:** single centered icon slot (INSTANCE_SWAP, Lucide), square bounding box
  matching the size token (e.g. `sm` = 28px per code's `size-7`).
- **Accessibility:** **every instance requires a bound, row/context-specific
  `aria-label`** — this is the component the brief and `accessibility.md` §4 call out
  explicitly (table row actions: edit, delete, reset device, approve/reject). No generic
  default label ships in the component; each usage site sets its own.

### 1.3 Text Input

| Property | Values |
|---|---|
| `state` | `Default`, `Filled`, `Focus`, `Disabled`, `Invalid`, `Read-only` |
| `size` | `default`, `sm` |

- **Anatomy:** optional leading icon slot → input field → optional trailing icon/clear
  button slot. Wrapped by Form Field Wrapper (§1.12) in actual usage, not standalone on
  a form.
- **Token bindings:** border `color/input`/`color/border`; invalid state border/ring
  `color/destructive`; radius `radius/md`.
- **Accessibility:** `Invalid` state sets `aria-invalid="true"` and is always paired with
  a Validation Message (§1.13) via `aria-describedby` — a Text Input is never shown in
  the `Invalid` variant without its paired message in real usage.

### 1.4 Password Input

Text Input (§1.3) + a fixed trailing icon-button slot toggling visibility.

- **Additional property:** `visible` (Boolean) — toggles masked/plain text rendering.
- **Accessibility:** the visibility toggle's `aria-label` reflects current state ("Show
  password" / "Hide password"), per the Authentication spec's requirement.

### 1.5 Textarea

Text Input (§1.3) pattern, vertical resize handle, no leading/trailing icon slots.
Additional property: `rows` (2–3 preset heights: `sm`=3 rows, `default`=5 rows,
`lg`=8 rows) rather than a freeform resize in the static design file.

### 1.6 Select

| Property | Values |
|---|---|
| `state` | `Default`, `Open`, `Disabled`, `Invalid` |
| `size` | `default`, `sm` |

- **Anatomy:** trigger (label text + chevron-down icon) → Dropdown Menu (§4.4) as the
  open-state popover content.
- **Accessibility:** trigger is a real button semantically; options list is keyboard-
  navigable (arrow keys + type-ahead), matching native `<select>` expectations even
  though it's a custom popover.

### 1.7 Checkbox

| Property | Values |
|---|---|
| `state` | `Unchecked`, `Checked`, `Indeterminate`, `Disabled` |

- **Token bindings:** checked fill `color/primary`; box border `color/border`; radius
  `radius/sm`.
- **Accessibility:** grouped checkboxes (e.g. permission assignment) use `fieldset`/
  `legend` at the usage-site level, per `accessibility.md` §6 — the component itself just
  needs a correctly associated single label.

### 1.8 Radio Button

Same state axis as Checkbox (§1.7) minus `Indeterminate`. Circular, `radius/full`.

### 1.9 Switch

| Property | Values |
|---|---|
| `state` | `Off`, `On`, `Disabled` |

- **Token bindings:** on-fill `color/primary`; off-fill `color/muted`.
- **Accessibility:** exposes `role="switch"` + `aria-checked`, not a checkbox role — used
  for immediate-effect toggles (e.g. notification preferences), not form-submit
  checkboxes.

### 1.10 Date Input

Text Input (§1.3) pattern with a trailing calendar-icon button opening a date-picker
popover (out of scope to fully spec the calendar grid here — flag as a sub-component to
detail when the first screen using it is wireframed, e.g. course access-duration
fields).

### 1.11 Search Input

Text Input (§1.3) with a fixed leading search-icon slot and a conditional trailing
clear button (visible only when the field has content).

### 1.12 Form Field Wrapper

Not a visual component on its own — a **structural** component every input above is
composed into: Label → Input slot (instance-swappable: Text/Password/Select/Textarea/
Date/Checkbox-group) → optional help text → Validation Message slot (§1.13, only
rendered in the `Invalid` state).

- **Accessibility:** this is the component responsible for wiring `htmlFor`/`id`
  association, `aria-describedby` (help text + validation message), and `aria-required`
  — per `accessibility.md` §6, these must be consistent every time a form field is used,
  which is why this exists as a dedicated wrapper rather than ad hoc per form.

### 1.13 Validation Message

Small icon (destructive) + text, `color/destructive`, `Body Small` type style.
Always rendered directly below the field it describes (never a toast/tooltip for
inline field errors — those are reserved for async/submit-level feedback, see §4.6/4.7).

---

## 2. Content & data display

### 2.1 Card

| Property | Values |
|---|---|
| `padding` | `default` (`spacing/lg`), `compact` (`spacing/md`) |
| `interactive` | Boolean — adds hover/focus-visible states when the whole card is a click target (e.g. Course Card, §2.3) |

- **Token bindings:** fill `color/card`, text `color/card-foreground`, radius
  `radius/xl`, border `color/border` at 1px (subtle, matches code's neutral-palette
  restraint).
- **Anatomy:** optional header slot (title + optional action) → body slot → optional
  footer slot.

### 2.2 Statistic Card

Card (§2.1) composed with a fixed internal layout: label (Body Small, muted) → large
value (H2/Display weight) → optional trend indicator (up/down icon + delta text,
`color/chart-1`/`color/destructive` for positive/negative — paired with an icon per the
"never color alone" rule, not just a colored arrow).

- **Usage:** Tenant Admin/Platform Admin dashboards (active students, revenue, pending
  approvals, etc.). Also used by the Student Overview (MVP-013) and Teacher Overview
  (MVP-014) pages for their respective assigned/enrolled-course statistics —
  **relocated in MVP-014** from `frontend/src/components/students/stat-card.tsx` to
  `frontend/src/components/dashboard/stat-card.tsx` (pure move, no prop/behavior
  change), since Teacher becoming a second consumer was the concrete trigger
  `.claude/rules/frontend.md`'s "extract to shared only when a second role group needs
  it" rule was written for. A future Tenant-Admin/Platform-Admin dashboard reusing this
  exact composition should continue extending `components/dashboard/stat-card.tsx`
  directly rather than promoting it further or re-deriving it from scratch — see
  `docs/plans/MVP-014 Teacher Dashboard.md` §21 item 3, which leaves this location as a
  deliberately non-final choice, not a settled architecture decision.

**Student "My Courses" / Overview card-grid convention (MVP-013).** Both
`student/courses/page.tsx` and the "recent courses" section of
`student/dashboard/page.tsx` render enrolled courses as a card grid
(`grid-cols-1 sm:grid-cols-2 lg:grid-cols-3`), mirroring `app/(public)/courses/page.tsx`'s
`CourseCard` pattern rather than `DataTable` (§2.4) — `DataTable` remains reserved for
admin-surface tabular data per `.claude/rules/ui-ux.md` §5's consumer-surface rule. The
shipped student course card uses the minimal 4-field `CourseSummaryResponse` shape (name,
slug, category, id) — no thumbnail/teacher-name/progress bar — a deliberate MVP scope
cut (`docs/plans/MVP-013 Student Dashboard.md` §21 item 2), not drift from §2.3's richer
spec; a future polish pass can extend the DTO without touching ownership/auth semantics.

**Teacher "My Courses" / Overview card-grid convention (MVP-014).** Same pattern as the
Student convention above, applied to the Teacher portal: `teacher/courses/page.tsx`
renders its results via a new, Teacher-page-local `TeacherCourseCardGrid`
(`frontend/src/components/courses/teacher-course-card-grid.tsx`,
`grid-cols-1 sm:grid-cols-2 lg:grid-cols-3`, mirroring `CourseCard`'s markup convention),
and `teacher/dashboard/page.tsx`'s "recent courses" section uses the identical breakpoint
grid. This **replaced** `CourseListTable` (§2.4-style admin table pattern) on the Teacher
My Courses page specifically — `CourseListTable` itself, and Tenant Admin's Course List
page (its remaining consumer), are unmodified and remain the correct pattern for that
admin-heavy surface. `TeacherCourseCardGrid` renders the full `CourseResponse` shape
(unlike Student's minimal `CourseSummaryResponse` card above) since Teacher already had
richer authorized data available via the pre-existing `GET /api/v1/courses` read this
module reuses unchanged. The next consumer-style dashboard needing a course/entity card
grid (e.g. Tenant Admin's still-placeholder TADASH-1, if it ever needs a
consumer-style sub-view) should extend this pattern rather than re-deriving it or
reaching for `CourseListTable`.

### 2.3 Course Card

Card (§2.1, `interactive=true`) composed with: thumbnail image (16:9, object-fit
cover, `radius/lg` on top corners only) → title (H4) → teacher name (Body Small, muted)
→ metadata row (Status Chip §2.10 for course status + price or "Enrolled" state) →
optional progress bar (Student "My Courses" context only).

- **States beyond §0's baseline:** `Locked` (used for `Course Access Denied`/`Payment
  Required` contexts, per `docs/ui-ux/screen-map.md`) — dims the thumbnail and overlays
  a lock icon + short reason text, still keyboard-focusable so the reason is announced,
  not just visually implied.

### 2.4 Table

Composed, not a single node: header row (sortable column headers — `aria-sort` on the
active column) → body rows → the shared responsive behavior from `design-system.md` §4.2
(card-view fallback or column-visibility toggle below `md`, never silent truncation).

- **Row variants:** `Default`, `Hover`, `Selected` (bulk-action contexts). Row actions
  use Icon Button (§1.2) with row-specific `aria-label`s.
- **Status columns** render via Status Chip (§2.10), never a bare colored cell.
- **Empty/loading/no-results states:** delegate to §5 (Skeleton, Empty State) rather
  than a table-specific reimplementation.

### 2.5 Pagination

Prev/Next Icon Buttons + numbered page buttons (current page as a `Selected`-style
Button variant) + optional "page size" Select. Keyboard-operable, current page announced
via `aria-current="page"`.

### 2.6 Filters

A horizontal (desktop) / collapsible-sheet (mobile, per `design-system.md` §2's
full-screen-sheet rule) group of Select/Search Input/Date Input instances + a "Clear
filters" text-button, feeding the Table's "no results match your filters" empty state
(§5.2) — never the same empty-state copy as true zero-data.

### 2.7 Tabs

| Property | Values |
|---|---|
| `state` | `Default`, `Active`, `Disabled` |

Horizontal list, active tab underlined with `color/primary`, `role="tablist"`/
`role="tab"`/`aria-selected` wiring, arrow-key navigation between tabs.

### 2.8 Accordion

Header (title + chevron, rotates on expand) → collapsible content region.
`aria-expanded` on the header, content region has a matching `id` referenced by
`aria-controls`. Reduced-motion note: the expand/collapse transition respects
`prefers-reduced-motion` per `03 Foundations`'s motion guidance.

### 2.9 Badge

Small, low-emphasis label chip — visually distinct from Status Chip (§2.10): Badge is
for neutral counts/tags (e.g. "3 new"), Status Chip is specifically for the enumerated
status vocabulary in §2.10. Both share the same base shape (`radius/full`,
`spacing/xs` vertical padding) but Badge has no fixed color vocabulary — it takes
whatever neutral/accent token the usage site needs.

### 2.10 Status Chip — full required vocabulary

One component, `status` property with the following values, each pairing color + icon +
text per the brief's "never color alone" rule and `accessibility.md` §5:

| Status | Color token | Icon (Lucide) |
|---|---|---|
| Active | `color/chart-1` (or a proposed semantic "success" token — see Open Questions) | `circle-check` |
| Inactive | `color/muted-foreground` | `circle-dashed` |
| Pending | proposed "warning" token (see Open Questions) | `clock` |
| Suspended | `color/destructive` | `ban` |
| Cancelled | `color/muted-foreground` | `x-circle` |
| Expired | `color/destructive` | `calendar-x` |
| Draft | `color/muted-foreground` | `pencil` |
| Published | `color/chart-1`/success | `check` |
| Archived | `color/muted-foreground` | `archive` |
| Payment Due | warning | `circle-dollar-sign` |
| Pending Payment | warning | `clock` |
| Paid | success | `check-circle` |
| Failed | `color/destructive` | `x-circle` |
| Refunded | `color/muted-foreground` | `rotate-ccw` |
| Approved | success | `check` |
| Rejected | `color/destructive` | `x` |
| Duplicate Flagged | warning | `alert-triangle` |
| Enrolled | success | `check-circle` |
| Live | `color/destructive` (attention, not error) | `radio` (filled, pulsing — respects reduced-motion) |
| Completed | success | `check-circle` |
| Present | success | `check` |
| Absent | `color/destructive` | `x` |
| Late | warning | `clock` |
| Excused | `color/muted-foreground` | `shield-check` |

- **`color/warning` — built (MVP-011).** A `warning` Badge variant plus `--warning`/
  `--color-warning` tokens now exist in `frontend/src/components/ui/badge.tsx` and
  `frontend/src/app/globals.css` (light mode `oklch(0.52 0.19 70.08)`, dark mode
  `oklch(0.769 0.188 70.08)` — both tuned for ≥4.5:1 text contrast against their own
  tinted background), consumed by `SlipStatusBadge`/`SlipFlagBadge`
  (`frontend/src/components/payments/status-badges.tsx`) for the "Duplicate Flagged"
  chip and related manual-payment-slip statuses. **Still open:** `color/success` has no
  equivalent token yet — the statuses above marked "success" still have no dedicated
  semantic color, only `warning`/`destructive`/neutral exist today.

### 2.11 Avatar

Circular (`radius/full`) image or initials-fallback (2 letters, `color/secondary`
background, `color/secondary-foreground` text). Sizes: `xs`/`sm`/`default`/`lg`. Optional
status-dot overlay (online/offline — only if presence is ever in scope; not currently
required by any screen in `screen-map.md`, so this sub-property is speculative/unused
for now).

### 2.12 Breadcrumbs

Text-link chain separated by a chevron icon, current page as non-interactive text
(`aria-current="page"`). Used for Platform Admin's tenant drill-down context (also
carries the persistent tenant-context banner, §3.4 — breadcrumbs alone are not
sufficient for that requirement, per `ui-ux.md` §1).

---

## 3. Navigation & layout

### 3.1 Desktop Sidebar

Fixed-width (240px default, collapsible to 64px icon-only), vertical nav-item list
(icon + label, active state = `color/sidebar-primary` background per the token already
in Figma), section grouping with small uppercase labels. No tenant selector ever
appears here for the Tenant Admin instance (`ui-ux.md` §1's absence-communicates-scope
rule) — this is a **content** rule the component's usage must respect, not something the
component itself enforces structurally.

### 3.2 Mobile Navigation

Bottom tab bar (Student/Teacher — consumer-style surfaces, ≤5 primary items,
`accessibility.md`-sized touch targets ≥44px) **or** a slide-in drawer (Tenant
Admin/Platform Admin admin-heavy surfaces, reusing Drawer §4.2) — two distinct variants,
not one component forced to serve both archetypes, per `design-system.md` §2's explicit
warning against conflating consumer and admin responsive strategies.

### 3.3 Top Header

Logo/tenant-branding slot (left) → optional global search (center, admin surfaces only)
→ notification bell (Icon Button + unread-count Badge) + Avatar/account menu (right).

### 3.4 Page Header

Page title (H1) → optional description → optional primary action Button(s), right-
aligned on desktop, stacked below title on mobile. **Tenant-context banner slot**
(conditional, Boolean property `showTenantContext`) — this is the component that
satisfies `ui-ux.md` §1's Platform-Admin-drill-down requirement flagged as a gap in
Checkpoint 1: a persistent, non-dismissible strip naming the tenant, rendered as part of
Page Header rather than a separate ad hoc banner per page.

---

## 4. Overlays & feedback

### 4.1 Modal

Centered, `radius/xl`, max-width presets (`sm`/`md`/`lg`), scrim overlay
(`color/foreground` at low opacity). Header (title + close Icon Button) → body → footer
(action buttons, right-aligned). **On mobile, per `design-system.md` §2, forms with
meaningful input use the full-screen sheet pattern instead of this centered variant** —
Modal itself therefore has a `sheet` Boolean property that swaps its mobile layout
accordingly rather than being a separate component.

- **Accessibility:** focus-trapped while open, focus returns to the triggering element
  on close, `Escape` closes (unless a destructive confirmation requires explicit
  choice — see §4.3).

### 4.2 Drawer

Slide-in from the edge (right by default, left for nav use per §3.2). Same focus-trap/
return-focus rules as Modal.

### 4.3 Confirmation Dialog

A constrained Modal variant: no arbitrary body content, fixed anatomy = icon (warning/
destructive) → title → short description → two actions (Cancel, secondary style +
Confirm, matching the action's severity — `destructive` Button variant for irreversible
actions). **`Escape` and scrim-click are disabled for destructive confirmations** — the
user must make an explicit choice, per the brief's clear-destructive-action-confirmation
requirement. This is the component every destructive action enumerated in the original
brief (tenant suspension, user deactivation, role changes, payment approval/rejection,
enrollment suspension, exam/result publication) instantiates.

### 4.4 Dropdown Menu

Popover list of items (optional icon + label + optional keyboard-shortcut hint),
divider support, destructive item styling (`color/destructive` text) for delete-type
actions. Keyboard: arrow-key navigation, `Escape` closes, opening item receives focus.

### 4.5 Toast

Bottom-corner (desktop) / bottom-center full-width above nav (mobile) transient
notification. Variants: `default`, `success`, `destructive`. Auto-dismiss with a
pause-on-hover/focus rule (never auto-dismiss something the user hasn't had a chance to
read) — announced via the shared live-region wrapper from `design-system.md` §4.4, not a
component-local `aria-live` reimplementation.

### 4.6 Alert

Persistent (not auto-dismissing) inline banner — used for form-level errors (Invalid
Credentials, server errors) and page-level success confirmations, per the Authentication
spec's per-screen state definitions. Variants: `default`, `success`, `warning`,
`destructive` (see §2.10's Open Question — `success`/`warning` tokens are shared between
this component and Status Chip, should be added together, not independently invented
twice).

### 4.7 Tooltip

Small popover on hover/focus (not click), short single-line text only. Never the sole
carrier of essential information (i.e., never a substitute for a visible label or an
`aria-label` on an icon-only control — it's a supplementary hint, redundant with, not a
replacement for, accessible naming).

---

## 5. States (loading / empty / error / permission-denied)

These four map directly onto `design-system.md` §4.1's `LoadingState`/`EmptyState`/
`ErrorState`/`PermissionDeniedState` pattern — this section adds the concrete visual
spec that doc intentionally left open.

### 5.1 Skeleton

Pulsing (respects reduced-motion → static low-opacity fill instead of pulse animation)
placeholder blocks matching the approximate shape of the content being loaded (text-line
bars, card outlines, table-row bars) — never a generic full-page spinner for content
that has a known shape, per modern loading-state practice; a full-page/centered spinner
is reserved for full-navigation transitions only.

### 5.2 Empty State

Icon/illustration slot → title → description → optional CTA Button. **Every instance's
title/description/CTA is supplied per call site, never a shared default "No data"
string** — this is a hard requirement from `ui-ux.md` §3, restated here because it's the
single most likely place for a generic implementation to sneak in. Two structurally
identical but content-distinct sub-cases per call site: true zero-data vs.
filtered-empty ("no results match your filters" + a "clear filters" CTA, distinct
copy from the zero-data case).

### 5.3 Error State

Icon (destructive) → title ("Something went wrong" pattern, but call-site-specific
where possible) → description → **Retry** button when the error is retryable, no retry
button when it isn't (e.g. a 403 renders Permission-Denied State instead, not this
component with a doomed retry button).

### 5.4 Permission-Denied State

Distinct from Error State (§5.3) — this is the full-page version of §3.13 in the
Authentication spec (`Shared / Error / Permission Denied`), reused inline within pages
(e.g. a hidden section of an otherwise-visible page when a staff sub-role lacks
access to just that section). Icon (lock) → explanation → link back to an accessible
part of the app. **Trigger is always a server-verified signal** — this component's
usage rule, not its visual spec, is the safety-critical part (see `design-system.md`
§5).

---

## 6. Upload & files

### 6.1 Upload Control

Dashed-border drop zone (`color/border`, `radius/lg`) + "Browse files" Button + visible
accepted-format/size-limit text (per `accessibility.md` §6's file-input requirement —
never hint-only via placeholder or color). States: `Default`, `Drag-over` (border
changes to `color/primary`), `Uploading` (per-file progress bar), `Error` (per-file
Validation Message, e.g. wrong MIME type or size exceeded — client-side check is a UX
convenience only, the server independently validates per `.claude/rules/security.md`).

### 6.2 File Preview

Thumbnail (image) or file-type icon (PDF/doc) + filename + size + remove Icon Button
(pre-submit) or a "View" action (post-submit, e.g. viewing an already-uploaded payment
slip) — the post-submit variant never exposes a raw/predictable storage URL directly, per
the brief's "do not expose raw storage URLs" rule; it routes through an authorized
fetch, which is a backend/API concern this component's usage must respect.

---

## 7. Progress

### 7.1 Step Indicator

Horizontal (desktop) / compact vertical or numbered-only (mobile) sequence of steps,
each `Upcoming`/`Current`/`Completed`. Used in multi-step flows: Course Builder,
Checkout, Bulk Student Import. `aria-current="step"` on the active step; completed steps
are not just visually checked but also announce "completed" to screen readers, not
color alone.

---

## Open questions

1. **Success semantic color token** (§2.10) — `warning` was built in MVP-011 (see §2.10);
   `color/success` is still not present in current code, and the Alert component's
   `success` variant remains unresolved pending it.
2. Date Input's calendar-picker sub-component (§1.10) is deferred to first-use
   wireframing rather than fully specified here.
3. Avatar presence/status-dot (§2.11) is speculative — no current screen requirement
   calls for online/offline presence; flag for removal if it stays unused through
   Checkpoint 3–5.

## Related

- `docs/ui-ux/design-system.md`
- `docs/ui-ux/accessibility.md`
- `docs/ui-ux/authentication-design-spec.md`
