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
