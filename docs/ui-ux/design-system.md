# Design System Approach

Status: Draft
Related rules: `.claude/rules/frontend.md`, `.claude/rules/ui-ux.md`

## Purpose

Defines the *structural and behavioral* design system approach — component
layering, theming mechanism, responsive strategy, and the shared components
required by the frontend/UI-UX rules. This document does not specify visual
design tokens (exact colors, type scale) — those are separate, unresolved
deliverables flagged as open questions below.

## 1. Component layering

- **`components/ui/`** — shadcn/ui-based primitives (Button, Input, Dialog,
  Table, etc.) and other shared, generic UI. These are the only components that
  should look like "raw shadcn" — everything role-specific builds on top of them,
  never forks them per tenant or per role.
- **Feature/role components** — co-located under the owning route group (e.g.
  `app/(tenant-admin)/payments/_components/SlipReviewTable.tsx`), per
  `frontend.md`'s route/folder rule. A component needed by more than one role
  group is a signal to promote it into a shared directory, not to import across
  role-group boundaries.
- **Shared state-pattern components** (see §4) live in a common directory
  (e.g. `components/shared/`) since they are used identically across all four
  role portals and the public storefront.

## 2. Responsive strategy

Per `.claude/rules/ui-ux.md` §5, this project has two distinct responsive
archetypes and must not conflate them:

- **Consumer-style surfaces** (Student, Teacher): mobile-first. Single-column
  stacking below `sm`/`md`, card-based layouts, collapsible/bottom nav on mobile.
- **Admin-heavy surfaces** (Tenant Admin, Platform Admin): `md`+ primary, with an
  explicit, designed mobile fallback — never "desktop-only, hope it degrades."

Breakpoints: Tailwind defaults only — `sm: 640px`, `md: 768px`, `lg: 1024px`,
`xl: 1280px`. No page introduces an ad hoc breakpoint.

Modals/drawers on mobile viewports use a full-screen sheet pattern (not a
fixed-width centered modal) for any form with meaningful input (payment slip
upload, bulk actions, multi-step course creation).

## 3. Tenant theming mechanism

- Tenant branding (logo, color theme, favicon, custom LMS name) is resolved at
  runtime from a tenant-scoped configuration API/endpoint (resolved via
  subdomain/custom domain, or the authenticated tenant context for logged-in
  routes) — never hardcoded per environment/build, never a single global static
  asset shared across tenants.
- Tenant theme colors apply as **CSS variable overrides** layered on top of the
  existing design system tokens (e.g. overriding `--primary`, `--accent` CSS
  custom properties consumed by shadcn/ui's Tailwind theme config) — never as a
  full page reskin or a per-tenant component fork. This keeps shadcn/ui
  component behavior/contracts identical across all tenants; only the token
  *values* change.
- Any client-side cache of branding/theme data (React Query cache, local
  storage) is keyed by `tenant_id`, not by domain string alone or a single
  global key — this matters for impersonation sessions and any dev/test flow
  that navigates between tenant subdomains in one browser session.
- Light/dark mode is orthogonal to and validated together with tenant theming:
  every tenant brand color must have a defined (or programmatically derived)
  light-mode and dark-mode variant. Dark mode is not assumed to "just work" for
  a tenant color the way it does for the platform default palette.
- The branding preview panel (Tenant Admin) renders through the *same* theming
  pipeline used by the live tenant site — no separate preview-only rendering
  path, to avoid preview/production drift.
- Per-tenant favicon, certificate branding, and invoice branding are resolved
  the same way as the logo — tenant-scoped config, not a static default baked
  into the app shell for tenant-facing or generated-document routes.
- If client-side contrast-checking of a tenant's brand color is implemented in
  the branding settings/preview panel, it is a UX safeguard only — final
  acceptance/storage of the branding configuration remains backend-validated
  (see `docs/ui-ux/accessibility.md` for the concrete WCAG AA requirement).

## 4. Required shared, reusable components

These must exist as shared patterns, not be reimplemented ad hoc per page/module,
per `.claude/rules/frontend.md`:

### 4.1 Async/data state components

A shared state-component library (or a React Query status-mapping helper) that
every data-driven screen consumes:

- `LoadingState` — exposes `aria-busy` and/or an `aria-live="polite"` region.
- `EmptyState` — **contextual by call site**: title, description, and CTA are
  supplied per usage, not defaulted. A generic "No data" instance is treated as
  an incomplete implementation (see `.claude/rules/ui-ux.md` §3 for required
  content differentiation, e.g. zero-data vs. filtered-empty vs. per-module
  copy).
- `ErrorState` — uses `role="alert"` or `aria-live="assertive"`; distinguishes
  retryable errors (offers retry action) from non-retryable ones.
- `PermissionDeniedState` — rendered **only** in response to a server-verified
  signal (401/403 response, or a permission/role value from the authenticated
  session/profile payload). Never computed from a client-stored role string or
  guessed from the route (see §5, Security note).

These four map cleanly onto React Query's `status`/`fetchStatus`/error shape, so
a shared "status → component" helper is the recommended implementation pattern
referenced in `.claude/rules/frontend.md`.

### 4.2 Responsive data table

A single shared responsive table component used by all Tenant Admin and
Platform Admin data screens (student lists, payment lists, audit logs, staff
lists, tenant lists, etc.):

- Supports card-view fallback below `md`, and/or column-visibility toggling.
- Must not silently hide/truncate columns on small screens without an
  alternate way to access that data (toggle or expandable row detail).
- Every icon-only row action (edit, delete, reset device, approve/reject)
  requires an `aria-label` (see `accessibility.md`).
- Status columns (payment/exam/device/tenant status) render via the shared
  status-badge pattern (§4.3), never color alone.

### 4.3 Status indicator / badge pattern

A shared status-badge component pairing color with text and/or icon, reused for
payment status, exam status, device status, and tenant status — so meaning is
never conveyed by color alone.

### 4.4 Toast / async-status live-region wrapper

A single consistent pattern (e.g. React Query mutation status mapped to a
shared toast/live-region wrapper) for surfacing async operation status
(loading/success/error) — used for payment submission, exam submission,
save/import operations, bulk actions, etc. This is what keeps accessible
announcements and error copy from being reinvented per module.

## 5. Security note (non-negotiable, not a design choice)

The `PermissionDeniedState` and all route/UI hiding based on role are **UX
convenience only**. They must never be the frontend's own authority — every
hidden or disabled action's backend endpoint independently rejects unauthorized
calls, and the corresponding UI must still handle a 403 response gracefully even
if a stale client-side role check would have allowed the action to render. This
mirrors `.claude/rules/frontend.md`'s explicit rule that permission-denied
states are driven only by a server-verified signal.

## 6. Forms

- Every form uses React Hook Form + a Zod schema (per `frontend.md`); the
  design system's form field primitives (in `components/ui/`) must expose
  props that make label association, error message association
  (`aria-describedby`), and `aria-invalid` straightforward to wire up
  consistently — see `accessibility.md` for the concrete checklist.

## Open questions (visual design — not resolved here)

- Exact default platform color palette (hex values) — not decided; tenant
  colors override tokens, but the *default* token values themselves need a
  separate visual design decision.
- Default typography (font family, type scale) — not decided.
- Default spacing/sizing scale beyond Tailwind's defaults — not decided; this
  document assumes Tailwind's standard scale is used as-is unless a future
  visual design pass specifies otherwise.
- Exact shadcn/ui theme preset (e.g. which shadcn "style"/base theme) to start
  from — not decided.
- Concrete WCAG contrast-checking algorithm/library choice for tenant brand
  colors — see `accessibility.md` open questions.
