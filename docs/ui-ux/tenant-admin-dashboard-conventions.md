# Tenant Admin Dashboard — Frontend Conventions (MVP-015)

Short convention note, not a spec — recorded per
`docs/plans/MVP-015 Tenant Admin Dashboard.md` §19/§11/§21 item 5, so the next multi-domain
dashboard or nav addition (e.g. a future Staff nav item) follows the same pattern instead of
re-deriving it.

## 1. Per-card independent `QueryStateBoundary` for a multi-domain KPI grid

Every prior dashboard (`app/(student)/student/dashboard/page.tsx`,
`app/(teacher)/teacher/dashboard/page.tsx`) wraps its whole page in a single
`QueryStateBoundary` because each page has exactly one data source. The Tenant Admin Overview
(`app/(tenant-admin)/tenant-admin/dashboard/page.tsx`) composes **three** independent,
unrelated domain reads (Students, Courses, Ledger) on one screen. Wrapping the whole page in one
boundary would mean one domain's outage or loading state blanks or hides the other two — not
acceptable for three independent domains.

Convention: each `StatCard` gets its **own** `QueryStateBoundary`, each with its own
`loadingLabel`, and no shared `isEmpty`/`emptyState` props — a zero-count renders as `"0"` plus
a short contextual hint directly on the `StatCard`, not a swapped-out `EmptyState` component
(deliberately different from the single-dataset whole-page empty-state swap MVP-013/MVP-014
use).

This is a **page-local pattern**, not (yet) a shared helper component. Whether it should become
one — e.g. a shared "parallel queries, independent per-card boundary" helper — is an open
question, not resolved by this module (see `docs/requirements/open-decisions.md` §19, and plan
§21 item 5). A second multi-domain dashboard (e.g. a future Platform Admin overview) may prompt
extracting a shared helper at that point.

## 2. `canView<Domain>(role)` nav-visibility helpers (`lib/auth/permissions.ts`)

Convention already established by `canViewPaymentDashboard`/`canProcessRefunds`/
`canViewAccessExpiryQueue`, extended by this module's `canViewTeachers`: a small, named,
role-string predicate function per nav-gated domain, mirroring the exact role set granted the
corresponding `DomainArea`/`VIEW` (or narrower) permission in `PermissionCheckServiceImpl`'s
backend matrix.

Rules for this convention:

- The helper gates **nav-item visibility only** — it decides whether a link renders in
  `TenantAdminNav` (or an equivalent role-scoped nav component), nothing else.
- It is **never** a substitute for backend authorization. The destination page's own existing
  `QueryStateBoundary` + `PermissionDeniedState` handling (driven by a real server `403`) remains
  the sole enforcement — a role without the grant that navigates directly to the hidden route by
  URL must still see a real permission-denied state, not a client-side redirect and not silently
  empty data.
- Name it `canView<Domain>` (or `can<Verb><Domain>` for a mutation-gated action button, e.g.
  `canProcessRefunds`), document the exact backend `DomainArea`/permission it mirrors and the
  exact role set in a doc comment, and keep it a pure function of `role: string | null`.

The next domain added to `TenantAdminNav` (e.g. a future Staff nav item, once its frontend
module ships) should add a `canViewStaff` helper following this same shape rather than
re-deriving the pattern.

## 3. Audit Log Viewer conventions (MVP-019)

`app/(tenant-admin)/tenant-admin/audit-log/page.tsx` follows the established filtered-list
screen shape (`AttendanceFilterForm` + `DataTable` + `QueryStateBoundary`), plus three
conventions specific to this screen worth calling out for the next similar module:

- **Two distinct empty states, never shared copy.** "No audit events yet" (whole-page, via
  `QueryStateBoundary`'s `emptyState`, only when no filters are active and the tenant truly has
  zero rows) is a different message from "No events match your filters" (inline `EmptyState` +
  "Reset filters", when a filter yields zero rows) — per `.claude/rules/ui-ux.md` §3, these must
  never reuse the same title/description.
- **No-mutation-affordance rule.** `audit_log` has no `PUT`/`PATCH`/`DELETE` route for any role
  (append-only at the backend). The viewer accordingly has no row action column, no bulk-action
  checkbox column, and no context menu — none of these are built at all, not hidden/disabled.
- **Metadata accordion pattern.** A row's `reason`/`metadata` don't fit a compact cell — they're
  exposed via `DataTable`'s new `renderExpandedRow` prop (an additive extension, see the
  component itself), with `metadata` further collapsed behind the new shared `Accordion`
  component (`components/ui/accordion.tsx`) showing a field-count summary that expands to a
  `<pre>`-formatted JSON block — never an inline stringified blob.
