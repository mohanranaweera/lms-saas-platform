# Platform Admin Dashboard — Frontend Conventions (MVP-020)

Short convention note, not a spec — recorded per `docs/plans/MVP-020 Platform Admin
Dashboard.md` §19/§11, mirroring `docs/ui-ux/tenant-admin-dashboard-conventions.md`'s
own format, so the next Platform Admin screen (or another module's cross-tenant admin
view) follows the same pattern instead of re-deriving it.

## 1. Every cross-tenant row/screen names its tenant

Per `.claude/rules/ui-ux.md` §1: any cross-tenant list/table view must show the tenant
name/identifier on every row, and drilling into a single tenant's data must render a
persistent, non-dismissible tenant-context banner for as long as the admin stays in
that context. MVP-020 shipped the first two screens that need this and, with them, the
project's first two shared primitives built specifically to satisfy it:

- **`components/ui/page-header.tsx`** (first implementation) — `title` /
  `description?` / `actions?`, plus `showTenantContext?` / `tenantContext?: { id, name,
  statusBadge? }`. The tenant-context strip has **no dismiss/close affordance anywhere
  in its markup** — enforced structurally (there is no button/icon that closes it), not
  by convention, since a dismissible banner would defeat the "persistent,
  non-dismissible" requirement regardless of how a caller used the component.
  `statusBadge` is a caller-supplied `ReactNode` rather than this component importing a
  feature's own status enum/badge directly — `components/ui/*` is meant to stay the
  dependency-graph floor (`.claude/rules/frontend.md`), so it must not know about
  `TenantStatus` or any other one feature's domain vocabulary.
- **`components/ui/breadcrumbs.tsx`** (first implementation) — chevron-separated link
  chain, current/last item rendered as non-interactive text with `aria-current="page"`.
  Breadcrumbs alone are **not** sufficient to satisfy the persistent-banner
  requirement — pair with `PageHeader`'s `showTenantContext` on any real drill-down
  screen.

**Which screens render the banner, and why not all of them:**

| Screen | `showTenantContext` | Why |
|---|---|---|
| Tenant List (`tenants/page.tsx`) | n/a | Cross-tenant list — every row names its tenant instead. |
| Tenant Detail (`tenants/[tenantId]/page.tsx`) | `false` | Same-tenant detail view — the tenant's own name already anchors the page as its `PageHeader` title. Inspecting a *different* domain's data isn't happening here, which is what the banner exists to disambiguate. |
| Payments Dashboard (`payments/page.tsx`) | n/a | Cross-tenant list — every row names its tenant instead. |
| Payments Tenant Drill-down (`payments/[tenantId]/page.tsx`) | `true` | Inspecting a different domain's (payments) data while "in" a tenant's context. |
| Platform Audit Log (`audit-log/page.tsx`) | n/a | Cross-tenant list — every row names its tenant instead. |
| Audit Log Tenant Drill-down (`audit-log/[tenantId]/page.tsx`) | `true` | Same rationale as the payments drill-down. |

## 2. No destructive/state-changing action without the tenant named next to it

Per `.claude/rules/ui-ux.md` §1: no destructive or state-changing action (approve
tenant, reject tenant) may be submittable from a screen where the target tenant isn't
visibly named next to the action. `ApproveTenantDialog`/`RejectTenantDialog`
(`components/platform-admin/`) are shared by the Tenant List row and the Tenant Detail
screen; both render `Approve {tenant.name}?` / `Reject {tenant.name}?` as the dialog's
own heading — the tenant name is never left implicit from surrounding table/page
context alone.

## 3. Read-only cross-tenant screens: no mutation hook, ever

The Cross-Tenant Payment Dashboard and Platform Audit Log (list + both drill-downs) are
deliberately read-only: `lib/api/platform-admin-payments.ts` and
`lib/api/platform-admin-audit-log.ts` each declare **no** `useMutation` hook, and their
own doc comments say none should be added — any refund/adjustment action stays on the
existing, already-reviewed tenant-scoped payment/refund endpoints (module plan §17).
The next Platform Admin screen that's read-only-by-design should keep the same
discipline: omit the mutation hook entirely rather than defining one nothing calls.

## 4. Shared `DataTable`/skeleton/expanded-row helpers, not per-screen duplicates

The Platform Audit Log and its per-tenant drill-down share one filter form
(`components/platform-admin/platform-audit-log-filter-form.tsx`) and one set of
table-skeleton / expanded-row-renderer / query-string-builder / column helpers
(`components/platform-admin/platform-audit-log-shared.tsx`) — the two pages differ only
in which columns they assemble (the list adds a `tenant` column the drill-down omits,
since every row there already belongs to the one tenant named in the page's own
banner). Both pages initially shipped with byte-identical inline copies of these
helpers (an MVP-020 review finding, fixed in the same module); a third Platform Admin
list/drill-down pair should reuse this file's exported columns and helpers, or extract
a similarly-shaped shared module of its own, rather than re-copying markup.

## 5. `lib/api/*` never imports from `app/*`

`lib/api/platform-admin-tenants.ts` is the canonical definition of `TenantStatus`
(mirroring the backend's `tenant.status` CHECK-constrained enum) — `status-badge.tsx`
(a feature component under `app/(platform-admin)/`) imports and re-exports the type
from there, not the other way around. An earlier draft had this backwards (the `lib/`
API layer importing a type from an `app/` route file), which was fixed during MVP-020's
own review: `lib/` must never depend on `app/`, only the reverse, per
`.claude/rules/frontend.md`'s "Shared, generic UI ... lives in `components/ui/`" /
layering intent.

## 6. Payments Dashboard ships one empty state, not two — a disclosed, deliberate deviation

Plan §11's note calls for two distinct empty states ("no payments recorded
platform-wide" vs. "no results for this filter") on the Cross-Tenant Payment
Dashboard, mirroring the Tenant List and Platform Audit Log. `GET
/api/v1/platform-admin/payments/dashboard` takes no filter query params at all (see
`docs/api/ledger-settlement-management.md`) — a "filtered-to-zero" state is
structurally impossible, so a second empty state would be a phantom UI with nothing
to trigger it. `payments/page.tsx` ships exactly one empty state, proven by a
Playwright test asserting there is no filter UI to produce a second state. If a
filter param is ever added to this endpoint, this page must gain the second empty
state at the same time, not retroactively.
