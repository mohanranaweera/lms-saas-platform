# Frontend Route Impact Analysis (Wave 0)

Status: analysis only. No route, component, or nav file was modified to produce this document.

## 1. Existing route inventory

See `current-architecture-inventory.md` §3 for the full per-portal route list. Portal route
groups: `(auth)`, `(platform-admin)`, `(public)`, `(student)`, `(teacher)`, `(tenant-admin)` —
matching `.claude/rules/frontend.md`'s required role/audience grouping. This structural
convention is a **MATCHES** finding and should be preserved exactly as new routes are added in
every wave.

## 2. Tenant Admin information architecture — current vs. target

Target (master instruction §6): **Dashboard · Academic (Courses/Classes, Teachers, Students,
Learning Materials, Attendance, Exams) · Finance (Student Payments, Payment Slips, Transactions,
Income, Expenses, Financial Reports) · Communication (Notifications, Email, SMS/WhatsApp) ·
Administration (Staff, Roles & Permissions, Audit Logs) · Institute Configuration (16 config
sub-areas)**.

Current (`TenantAdminNav`, flat, no grouping):

```
Dashboard
Students
[Teachers]                 (role-gated)
Courses
Profile                    ← no href, dead-end
Settings                   ← no href, dead-end
[Payments]                 (role-gated)
[Refunds]                  (role-gated)
[Payment Slips]            (role-gated)
[Reactivation Approvals]   (role-gated)
[Attendance Reports]       (role-gated)
[Mark Attendance]          (role-gated)
[Exams]                    (role-gated)
[Audit Log]                (role-gated)
```

Gap summary:

| Target group | Present today | Missing |
|---|---|---|
| Dashboard | ✅ `dashboard` | — |
| Academic | Courses, Students, [Teachers], [Attendance], [Exams] | **Learning Materials has no standalone nav item** (only reachable via course detail, if at all) |
| Finance | [Payments], [Refunds], [Payment Slips], [Reactivation Approvals] | Transactions, Income, Expenses, Financial Reports — all missing (domains 23/24 not built) |
| Communication | none | Notifications, Email, SMS/WhatsApp — entirely missing (domain 12/21/22 config screens not built) |
| Administration | [Audit Log] | Staff, Roles & Permissions — missing despite `StaffController`/`RoleCatalogController` already existing on the backend (PAR-02-01) |
| Institute Configuration | none | All 16 sub-areas missing — no tenant-configuration framework exists at all (PAR-XC-02) |

`Profile` and `Settings` are present as nav labels with **no `href`** — they render as
non-functional list items. This is the single clearest violation of master instruction §34
("Do not create navigation items whose underlying workflows are not implemented") found in this
audit and should be the first thing fixed in Wave 1, ahead of adding any new groups.

## 3. Per-portal route completeness vs. target screens

**Platform Admin** — `tenants`, `payments`, `audit-log`, `dashboard` exist, matching master
instruction §33's required scope (tenants, approvals, tenant status, platform financial
oversight, platform audit). Missing per §33: SaaS plans management, domains management, platform
integrations management, operational/system-health screens — all reasonable to defer since none
of their backing domains (plan-limit engine, custom domains, integration credential management)
exist yet either.

**Public storefront** — `courses`, `courses/[slug]`, `register-institute` exist. Missing per
master instruction §31: teacher display on the storefront, and (once built) course-review
display. Login is handled by `(auth)/login`.

**Student** — comprehensive coverage of shipped domains (dashboard, courses, checkout, payments
×4 sub-states, attendance, exams, notifications, profile). Missing per shipped-vs-target: a
"My Devices" screen (PAR-16-04), a Live Classes screen (PAR-19-04), no dedicated video-player
route distinct from course/lesson content (PAR-20 gap is backend-first, so this is expected to
trail).

**Teacher** — dashboard, courses (+ new), attendance (mark/reports), exams (+ marking +
questions), notifications. Missing: Live Classes scheduling (PAR-19-03), a Materials Manager
distinct from course editing (may already be embedded in course detail — NEEDS_VERIFICATION,
not re-litigated here since it mirrors PAR-06 in the matrix).

**Tenant Admin** — see §2 above for the IA-level gap; at the route level, missing:
`staff`, `staff/[id]`, `roles-permissions`, `materials` (standalone), `finance/*` (all),
`settings/*` (all 16 config areas), `devices`, `live-classes`, `reviews/moderation`,
`communications/*`.

## 4. Placeholder / stub screen check

Per master instruction §42 ("do not mark TODO/placeholder screens as complete"), the only
literal placeholder found in this pass is the `Profile`/`Settings` dead-end nav pair noted in
§2. No other route in the existing inventory showed evidence of being a stub (all existing
routes correspond to a live backend controller per the cross-reference in
`api-impact-analysis.md`). A line-by-line "does this page actually render real data with
forms" verification per existing route was not performed in this pass — recommend it as a
`definition-of-done`-skill check the first time each existing screen is touched in a later wave,
rather than a blanket Wave 0 re-audit of already-shipped, already-tested pages.

## 5. Shared component / pattern compliance

- Loading/empty/error/permission-denied states: `.claude/rules/frontend.md` requires these as
  shared components rather than ad hoc per page. A component-by-component audit was out of scope
  for Wave 0; `ui-ux-review` skill / `ui-ux-reviewer` agent is the right tool to run this check
  per screen as each wave touches it.
- Responsive data-table pattern: `.claude/rules/ui-ux.md` §5 requires a shared responsive table
  component for admin-heavy surfaces (Student List, Payment List, Audit Log). Not independently
  re-verified in this pass; flagged as a `NEEDS_VERIFICATION` item to fold into whichever wave
  next touches each of those specific lists.
- Role-conditional nav visibility (`canViewTeachers`, `canViewPaymentDashboard`, etc.) is
  genuinely backend-permission-driven per the code comment in `tenant-admin-nav.tsx` itself
  (every destination independently renders `PermissionDeniedState` from a real 403) — this
  pattern is a **MATCHES** and should be the template for every new nav item added under the
  restructured IA in Wave 1, including the currently-missing Staff/Roles & Permissions items.

## 6. Recommended Wave 1 frontend sequencing

1. Fix the two dead-end nav items (`Profile`, `Settings`) — either wire them to real routes or
   remove them, before adding anything new.
2. Restructure `TenantAdminNav` into the six target groups, keeping existing role-gating logic
   verbatim (do not rewrite `canView*` helpers unnecessarily).
3. Build `tenant-admin/staff` and `tenant-admin/roles-permissions` against the already-existing
   `StaffController`/`RoleCatalogController` backend — this is the fastest, lowest-risk parity
   win available (backend done, frontend absent, no new domain needed).
4. Build the Institute Configuration shell (nav group + a generic settings-domain page pattern)
   once the tenant-configuration framework's API contract (PAR-XC-02) is designed — do not build
   the UI ahead of the backend contract per master instruction §40 Step 5 ("do not mock away
   missing backend behavior").
