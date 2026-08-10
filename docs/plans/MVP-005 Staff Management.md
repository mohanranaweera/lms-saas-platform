# MVP-005 — Staff Management — Module Plan

**GitHub issue:** #5 — https://github.com/mohanranaweera/lms-saas-platform/issues/5 (could not be fetched in
this session — GitHub MCP required an interactive OAuth authorization not available here. This plan is
grounded instead in the repo's internal, already-reconciled requirements corpus, which is this project's
normal source of truth for planning.)
**Branch:** `feature/staff-management` (assumed, matching this module's naming convention)
**Backlog source:** `docs/planning/product-backlog.md`, MODULE 5 (stories `STAFF-1`, `STAFF-2`, lines 299-339)
**Spec source:** `docs/requirements/specifications/02-staff-management.md`
**Backend domain:** `user-management` (per `.claude/rules/architecture.md`'s confirmed domain list — Staff
Management is Module 5 *inside* `user-management`, alongside Student Management/Module 3 and Teacher
Management/Module 4; there is no standalone `staff-management` package).

This plan was produced by delegating to six specialist agents in parallel (product-requirements-analyst,
solution-architect, database-architect, security-reviewer, qa-test-engineer, ui-ux-reviewer), each grounded
in the existing requirements/architecture/ADR corpus and the actual current repository state, then
reconciled into one document. **`payment-ledger-specialist` was intentionally not used** — this module's
"Payment impact" is `None` per both `STAFF-1` and `STAFF-2`'s own backlog fields (item 11 in each), and
nothing in the spec, backlog, or role matrix touches money — so there is nothing payment/ledger-shaped to
review.

This is a **plan only** — no application files were created or edited. Several genuine gaps and
cross-document contradictions surfaced independently (in some cases by more than one agent working in
parallel, which is itself corroborating evidence) are flagged explicitly below as **open decisions**, not
resolved. Per root `CLAUDE.md`, this plan does not invent unresolved business decisions.

**Grounding note on current repository state**, verified directly (`git status`, `find`) before delegating:
the codebase currently contains **only** the Module 1 / MVP-001 application-foundation shared kernel
(`backend/src/main/java/com/lms/common/*` — `BaseEntity`, `Auditable`, `TenantOwned`, `TenantAwareRepository`,
`TenantContext`/`TenantContextHolder`, `UuidV7Generator`, `ApiResponse`/`ApiError`/`FieldError`,
`SecurityConfig`) and one Flyway migration, `V1__baseline_conventions.sql`, which is documentation-only (zero
tables). There is **no** `tenant-management`, `identity-access-service`, `user-management`, or RBAC code
anywhere, and **no** `docs/api/*.md` domain contract files exist yet (`docs/api/README.md` confirms this is
still scaffolding). This materially shapes §20 (Implementation order) and §21 (Risks) below: this module is
blocked on three entire prerequisite modules that do not yet exist, not merely on one or two deferred
endpoints as `MVP-004` (Tenant Management) was.

---

## 1. Business goal

Staff Management lets a tenant (institute) operate with more than one working login. Today the only account
with administrative access to a tenant is the Tenant Admin / Institute Owner; this module lets that owner
create additional, tenant-scoped staff accounts — each assigned one fixed operational role (Finance Staff,
Course Coordinator, Student Support, Content Manager, Exam Manager, Attendance Operator, or Read-only
Auditor) — so day-to-day operational work (finance, course coordination, student support, content upload,
exam management, attendance, or read-only oversight) can be delegated without sharing the owner's
credentials and without giving any delegate more access than their job requires. The owner retains full
control: they alone can create, edit, or remove staff accounts and assign roles; they can review what a
staff member has done via an activity log; and they can force a password reset. This is foundational MVP
capability (`FR-UM-8`) — without it, every tenant is limited to a single login regardless of how many
operational staff the institute actually employs, which blocks onboarding any multi-person institute onto
the platform.

## 2. Roles and permissions

**Acting actor:** Tenant Admin / Institute Owner — the only role with any create/edit/delete authority over
staff accounts and role assignment. This role is single, tenant-scoped, and is provisioned at tenant
approval (not self-registered), per `docs/requirements/user-roles-and-permissions.md` §1.

**Resulting accounts (the 7 fixed staff sub-roles):** Finance Staff, Course Coordinator, Student Support,
Content Manager, Exam Manager, Attendance Operator, Read-only Auditor. Each is a value of a fixed role
enum assigned by the Tenant Admin at creation time — Staff Management is a system for creating accounts
against this closed list, not for defining new roles or granting per-account custom permissions (see §6).

**Explicitly NOT involved in this module:**
- **Teacher / Teacher Assistant** — a separate role family, scoped to `app/(teacher)/`, owned by Teacher
  Management (Module 4), not created through Staff Management's flow. This needs an explicit confirmation
  rather than a silent assumption — see §21 item 6.
- **Student** — owned by Student Management (Module 3).
- **Platform Admin** — platform-wide, cross-tenant, entirely outside tenant-scoped Staff Management. Per
  `docs/requirements/user-roles-and-permissions.md` §4, Platform Admin access to a specific tenant's staff
  requires an explicit, audited impersonation flow, never implicit tenant-admin-equivalent access.

**Domain-level permission table — "Staff & roles" row** (verbatim from
`docs/requirements/user-roles-and-permissions.md` §2, the authoritative domain-level matrix):

| Institute Owner | Finance Staff | Course Coordinator | Student Support | Content Manager | Exam Manager | Attendance Operator | Read-only Auditor |
|---|---|---|---|---|---|---|---|
| V/C/E/D | — | — | — | — | — | — | V |

Institute Owner has full control; Read-only Auditor may view the staff list/detail only; **all other five
sub-roles have no access at all** to this domain area — not even view. This is a stricter boundary than most
other domain rows, where non-Owner roles typically retain at least `V`.

**Related row worth carrying into acceptance criteria — "Audit log":** Institute Owner = `V`, Read-only
Auditor = `V (full)`, all other sub-roles = `V (own-area actions)`. `STAFF-2`'s activity-log feature as
specified narrows this in practice to a Tenant-Admin-only view — see §21 item 9 for the resulting gap.

Cross-cutting rules from §4 of the same document apply without exception to every Staff Management
endpoint: authorization is enforced server-side on every protected endpoint independent of client display;
the permission-denied UI state is driven only by a server-verified 401/403 or session-derived role, never a
client-stored role string; every permission check is evaluated for the resolved tenant context.

## 3. Preconditions

- Acting user is authenticated as Tenant Admin / Institute Owner, with tenant identity resolved from the
  trusted authenticated session context — never a client-supplied `tenant_id`.
- Tenant status is `active` (not suspended) — explicit precondition in the spec (§3). The spec does not
  detail the exact mechanism (what a suspended tenant's Staff Management endpoints return); inferred by
  analogy to `FR-TM-3`'s general suspended-tenant behavior, not spelled out specifically for this module.
- Tenant's plan staff-count limit has not been exceeded — listed as a precondition in the spec, but this
  maps to `FR-UM-9`, which is **Phase 2** and blocked on the unratified Feature Flag & Plan Limit Engine
  (Module D). This precondition does not apply to MVP acceptance and must not gate MVP functionality.
- **Sequencing fact, not a business precondition:** per the backlog, `STAFF-1`'s hard blockers are
  `TEN-1`/`TEN-2` (tenant approval), `AUTH-1`/`AUTH-2` (login/session), and `RBAC-1`/`RBAC-2` (role
  model/enforcement) — none of which currently exist in the codebase (only the Module 1 foundation and a
  documentation-only migration exist). This plan describes requirements and design; it does not claim any
  of it is currently implementable end-to-end (see §20).

## 4. User flows

### Normal flow
1. Tenant Admin navigates to `Tenant Admin > Staff > Staff List` and selects "Add staff."
2. Tenant Admin enters the account's details and assigns a role from the fixed 7-role sub-role list. (The
   exact profile field set beyond name/email/role — e.g. phone, department — is not enumerated anywhere in
   reviewed material; see §21 item 10.)
3. Backend creates a staff account scoped to the acting admin's own tenant, calling
   `identity-access-service`'s `api` to create the underlying login credential row rather than duplicating
   credential storage.
4. Email uniqueness is enforced per tenant (`UNIQUE (tenant_id, email)`), never globally — the same email
   may exist for a different tenant's staff/student/teacher account without conflict.
5. The staff member logs in via the same shared `identity-access-service` login path used by every other
   role (no parallel auth stack); their assigned role determines portal/nav scope.
6. At any later time, the Tenant Admin can view/edit the staff account and role, view that staff member's
   read-only activity log, and trigger a password reset.

### Alternative / edge-case flows
- **Staff-count-at-limit** — Phase 2 only, gated on Module D; does not exist as a flow at MVP. Any MVP build
  that silently implements this would be scope creep.
- **Permission violation** — any of the six non-Owner sub-roles attempting to create/edit/delete a staff
  account (including their own) is rejected 403 server-side regardless of UI state. Since five of the six
  sub-roles have zero access (not even view), the Staff nav section should return 403/404 on direct access
  attempts for those roles, not a filtered-empty 200.
- **Read-only Auditor specifically** — the one sub-role with any visibility (`V`) into Staff & roles: can
  view the staff list/detail, but no mutating endpoint (create/edit/delete/role-change/password-reset) may
  succeed for this role.
- **Staff status change (deactivate/suspend/remove)** — named as a required MVP feature in the raw source
  list, but no reconciled document defines the state machine, transitions, or triggering actor. This flow
  cannot be fully specified as written — see §21 item 2.
- **Password reset** — Tenant Admin-triggered reset forces the staff member to set a new credential at next
  login (`STAFF-2`). Whether staff can *also* self-initiate a reset via the apparently role-agnostic "Shared"
  Reset Password flow described in `docs/ui-ux/authentication-design-spec.md` §3.3/3.4 is not confirmed
  either way — see §21 item 4.
- **Cross-tenant negative** — a Tenant Admin of tenant A attempting to view/edit/role-edit a staff account
  belonging to tenant B by ID is rejected 403/404, never a 200 with empty or filtered data.
- **Partial cross-domain write failure** — `STAFF-1` requires a call into `identity-access-service`'s `api`
  to create the credential row, separate from `user-management`'s own profile/role write. No document
  specifies compensating behavior if one write succeeds and the other fails — see §21 item 11.
- **Empty states** — "no staff accounts yet" (with Add Staff CTA) is a distinct state from "no staff match
  your filter"; both must be independently reachable/testable.

## 5. Acceptance criteria

Reconciled and deduplicated from the spec's own checklist plus both backlog stories' acceptance criteria.

1. Given a Tenant Admin creates a staff account, the account is created tenant-scoped to the acting admin's
   own tenant (`tenant_id` resolved from trusted session context, never client-supplied), and the account can
   authenticate only within that tenant.
2. Given account creation, a role must be assigned from the fixed 7-role sub-role list — no arbitrary/custom
   role value is accepted.
3. Email is unique per tenant (`UNIQUE (tenant_id, email)`), never enforced as a global unique constraint;
   verified via a real database-backed integration test, not application-layer validation alone.
4. Given a staff sub-role account with no create/edit/delete permission on "Staff & roles" (any of the six
   non-Owner sub-roles) attempts to create, edit, or delete a staff account — including its own — the
   request is rejected 403 server-side, independent of client UI state.
5. Given the Read-only Auditor role, it may view (list/detail) staff accounts, but no mutating staff
   endpoint (create/edit/delete/role-change/password-reset) succeeds for this role regardless of stale
   client UI exposing the action.
6. Given a Tenant Admin of tenant A addresses a staff account belonging to tenant B by ID (list, detail, or
   role-edit endpoint), the request is rejected 403/404, not a 200 with empty or filtered data.
7. Given any staff list/detail/role-edit/activity-log/password-reset endpoint, results and effects are
   strictly scoped using the `tenant_id` resolved from the authenticated session context — no endpoint
   accepts a caller-supplied `tenant_id`.
8. Given no staff accounts exist yet for a tenant, the Staff List renders an explicit "no staff accounts
   yet" empty state with an "Add Staff" CTA, distinct from the "no staff match your filter" state.
9. The Staff Detail / Role Editor's role-assignment control is grouped with `fieldset`/`legend` for
   accessibility.
10. Given a Tenant Admin views a staff member's Activity Log, only that staff member's actions within that
    Tenant Admin's own tenant are shown, and the view is read-only (no edit/delete affordance on log
    entries).
11. Given a Tenant Admin resets a staff member's password, the staff member must set a new credential at
    next login; the reset action does not expose the old/current password.
12. Given the Read-only Auditor role attempts a password-reset action, it is unavailable in the UI and
    rejected server-side if attempted directly against the API.
13. A password-reset or staff-lookup request must never allow a Tenant Admin of one tenant to reset, or
    probe the existence of, a staff account belonging to another tenant (no cross-tenant existence leakage
    via response shape or timing).
14. Given a staff sub-role account attempts an action outside its permission set in a domain area other than
    "Staff & roles" (e.g. Finance Staff attempting to edit Course Materials), the same server-side 403
    pattern applies, consistent with the full permission matrix in `user-roles-and-permissions.md` §2.
15. **Out of MVP acceptance scope, deferred to Phase 2:** given the tenant's plan staff-count limit is
    reached, attempting to add another staff account is rejected server-side with a clear reason — not
    testable/buildable until Module D (Feature Flag & Plan Limit Engine) exists; do not gate MVP sign-off on
    this criterion.

## 6. Out-of-scope items

- **`FR-UM-9`** (staff-count-vs-plan-limit enforcement) — Phase 2, blocked on the unratified Feature Flag &
  Plan Limit Engine (Module D). Explicitly named as excluded from `STAFF-1`'s MVP scope in the backlog
  itself.
- **Teacher Management and Student Management** — separate modules (Module 4 and Module 3). Teacher and
  Teacher Assistant accounts are not created through this module's staff-account flow; they belong to a
  different portal route group and a different actor/approval flow (see §21 item 6 for the ambiguity this
  needs resolving before implementation, not silently assuming).
- **Custom/granular per-staff permission overrides beyond the fixed 7 sub-roles** — no source document
  requests a per-staff-member customizable permission grid; the role model is a fixed enum with a
  matrix-defined permission set per role. Building an override mechanism would be scope creep with no
  requirement backing it (this is also why the Role Editor is designed as single-select, not a multi-select
  permission checklist — see §11 and §21 item 13).
- **Self-service password reset initiated by the staff member** — the spec and `STAFF-2` describe only an
  admin-triggered reset. `docs/ui-ux/authentication-design-spec.md` §3.3/3.4 separately describes an
  apparently role-agnostic self-service Reset Password flow not explicitly scoped to exclude staff — this
  creates ambiguity (see §21 item 4), not something this module resolves by building either interpretation.
- **Staff status state-machine implementation** (active/suspended/removed) — named as a required MVP feature
  in the raw source list but cannot be scoped/built without a decision on states/transitions (§21 item 2);
  the mechanics are out of scope until resolved, even though the feature name is nominally in the MVP list.
- **The `audit-log-management` module itself** — `STAFF-2` has a soft dependency on Module 19/AUDIT; Staff
  Activity Log cannot show real audit rows until that module exists, and must read from
  `audit-log-management`'s `api`, never a direct join or a bespoke module-local log store.
- **RBAC enforcement mechanism** — role *enforcement* is `identity-access-service`'s concern;
  `user-management` owns only the staff profile/role *data* (with an ownership ambiguity flagged at §21
  item 14).
- **Password-reset notification delivery** — owned by `notification-management` (Module 15/18); this module
  only triggers the event, per `STAFF-2`'s soft dependency on `NOTIF-1`/`NOTIF-2`.
- **`identity-access-service`'s and RBAC's own schemas** (`tenant_user`, role catalog) — designed by Modules
  2/3, not by this plan. This plan states `user-management`'s dependency/contract needs from them (§8, §9)
  but does not author their DDL.
- **A multi-role-per-user model** — `RBAC-1`'s own story text frames single-role-per-user as the recommended
  model, and the spec treats "role" as singular per staff account. This module's design does not preemptively
  build for multi-role.

## 7. Domain model

Staff Management's data model splits along the domain-package boundary fixed in `.claude/rules/architecture.md`,
even though "a staff account" reads as one concept to a Tenant Admin:

- **Credential aspect** (email, password hash, login status, `must_change_password`, TOTP-secret placeholder,
  the `role`/sub-role claim used for authZ) is owned by `identity-access-service`, materialized as the
  `tenant_user` table `AUTH-1`/`AUTH-3` will create. **Does not exist yet.**
- **Operational/profile aspect** (that this credential row *is* a staff member, plus any staff-specific
  profile attributes and — once resolved — a staff-status lifecycle) is owned by `user-management` (this
  module), as a new tenant-owned entity, `StaffProfile`.
- **Activity-log aspect** is not a `user-management` entity. Per `database-architecture.md` §5 ("audit...
  tables are event-consumers' own tables, not shared tables written directly by the domains that trigger
  them"), the Staff Activity Log is a read surfaced through `audit-log-management`'s `api`, filtered to
  staff-related rows for the tenant — `user-management` does not own or join into the audit table.
- **Role/permission catalog aspect** is not a `user-management` entity either. `RBAC-1` (Module 3, not yet
  built) owns the fixed role catalog and the authoritative role-assignment mechanism. `user-management`
  consumes this, it does not define it.

**`StaffProfile` (the one entity this module owns):**
- Belongs to exactly one `Tenant`.
- References exactly one `tenant_user` credential row **by id only** — never by JPA entity association
  across the module boundary (`.claude/rules/architecture.md`: a module "must never... import another
  domain's `domain` (entity) classes"). The Java side treats `user_id` as an opaque foreign key value; any
  need to read/mutate credential data goes through `identity-access-service`'s `api`, not a JPA
  `@ManyToOne`.
- Optionally carries a **denormalized, read-only projection** of the staff sub-role for `user-management`'s
  own tenant-scoped listing/filtering needs (see §8.3) — RBAC-1 remains the system of record for role
  assignment and authZ enforcement.
- Has no owned child entities (activity log and audit trail are external reads, not FKs).

No new `staff_status` reference table is proposed — "staff status" is an explicitly unresolved state
machine (§21 item 2), so it is called out as an open item rather than modeled with invented values.

## 8. Database design

### 8.1 `tenant_user` reuse vs. a dedicated `user-management`-owned table — flagged tension

The backlog's literal `STAFF-1` text ("Additive to `tenant_user` (AUTH-1) — recommend reusing `tenant_user` +
`role` enum rather than a parallel `staff` table") is in direct tension with two firm architecture rules:

- `.claude/rules/architecture.md`: "A new REST endpoint, entity, or repository belongs in exactly one domain
  package," and a module "must never... import another domain's `domain` (entity) classes" or "inject or
  call another domain's `repository` beans."
- `docs/architecture/database-architecture.md` §5: "Tables belong to exactly one domain package; a table's
  owning domain is whichever domain owns the primary aggregate the table represents."

`tenant_user` is unambiguously `identity-access-service`'s aggregate (per `AUTH-1`'s own backend-impact
line). If `user-management` also wrote columns onto that same physical table — whether by importing
`identity-access-service`'s entity class or by hand-rolling a second JPA entity mapped onto the same table —
either way produces two independently-migrated write paths into one table, which both `.claude/rules/architecture.md`
and `database-architecture.md` §5 treat as a structural violation, not a valid shortcut.

**Recommendation, converged on independently by both the database-architect and solution-architect reviews
(corroborating, not just one opinion):** a dedicated `user-management`-owned table, `staff_profile`, that
references the `tenant_user` row by id — the same pattern the backlog itself uses for Student Management's
`student_profile(tenant_id, user_id FK, ...)`, separating auth from domain profile. `staff_profile` would be
composite-FK'd against `(tenant_id, id)` on `tenant_user` (requiring `tenant_user` to expose a
`UNIQUE (tenant_id, id)`), per the same-tenant cross-referencing rule in `database-architecture.md` §1.

**This is flagged as an open risk, not silently decided** (§21 item 12) — the backlog's literal wording may
reflect a deliberate, considered call by whoever wrote it (e.g. intending only the `role` claim itself to
live on `tenant_user`, which is fine, since that's `RBAC-1`'s table, not a `user-management` table) rather
than a throwaway note. If a shared physical table across two domains is genuinely intended, that is itself a
deviation from `database-architecture.md` §5 and should go through an ADR, not a migration.

### 8.2 `staff_profile` — conceptual column list (not a migration; no version number assigned — see §8.4)

```
staff_profile
  id                UUID        PK, app-generated UUIDv7 (BaseEntity convention, no DEFAULT)
  tenant_id         UUID        NOT NULL REFERENCES tenant(id)
  user_id           UUID        NOT NULL  -- logical FK to tenant_user(id); enforced as a
                                           -- composite FK once tenant_user exists (see 8.3)
  role              <TBD type>  NOT NULL  -- denormalized read-projection of the staff sub-role
                                           -- for local list/filter queries; see 8.3
  status            <TBD type>  NOT NULL  -- OPEN: no confirmed state machine (§21 item 2) —
                                           -- enum values not invented here
  created_at, updated_at, created_by, updated_by   -- via Auditable (existing shared-kernel base)
```

Notes:
- **`email` is intentionally absent.** Per §8.1, email is `tenant_user`'s column. `UNIQUE (tenant_id, email)`
  is therefore **Module 2's constraint, on Module 2's migration** — this module's migration must not
  re-declare it or duplicate the email column. `staff_profile`'s own per-tenant uniqueness is instead
  `UNIQUE (tenant_id, user_id)` — one staff profile per credential row per tenant.
- **`status`**: per the spec's own explicit framing, this is an unresolved state machine
  ("active/suspended/removed" is only *named*, not specified). Once confirmed, `status` should be a
  `CHECK`-constrained enum column (per `.claude/rules/backend.md`'s schema-enforced-invariants guidance for
  state machines) — but this plan does not invent the value set.
- **No `must_change_password` column here** — `AUTH-3`'s backlog entry places that flag on
  `tenant_user`/`platform_admin_user`, not on a profile table. Whether it applies to manually-created staff
  is an API-contract question about what `user-management` passes when calling `identity-access-service`'s
  `api` to create the credential row (§21 item 3), not a `staff_profile` schema question.
- **No dedicated profile fields (phone, department, etc.) are asserted** — the spec's normal flow only
  mentions "creates the account, assigns a role"; anything beyond `tenant_id`/`user_id`/role-projection/
  status would be inventing requirements not present in reviewed material (§21 item 10).

Indexes/constraints, shaped to the query patterns the spec and backlog actually name (staff-list-by-role,
staff-list-by-status):

```
UNIQUE (tenant_id, user_id)     -- one profile per credential per tenant
INDEX  (tenant_id, id)          -- tenant-scoped-by-id lookups (TenantAwareRepository shape)
INDEX  (tenant_id, role)        -- staff-list-by-role (explicit in STAFF-1's tenant-impact field)
INDEX  (tenant_id, status)      -- staff-list-by-status
```

`tenant_id` leads every composite index per `.claude/rules/backend.md`; no bare `tenant_id`-only index.

### 8.3 Cross-table FK considerations

Two forward dependencies, neither of which this module's migration can create on its own:

1. **`tenant_user` FK.** `staff_profile.user_id` needs a composite FK `(tenant_id, user_id) REFERENCES
   tenant_user(tenant_id, id)` — the same shape the backlog specifies for `student_profile`. Requires
   `tenant_user` to exist with a `UNIQUE (tenant_id, id)` available to FK against — Module 2's migration,
   not built yet. This is a forward dependency, not something this module's migration can satisfy today.
2. **Role catalog / role source of truth.** `RBAC-1` has not decided whether the authoritative role lives as
   an enum column on `tenant_user` or a separate `tenant_user_role` join table. Since `user-management` must
   not directly join across domains for query convenience (`database-architecture.md` §5), the recommended
   pattern is that `staff_profile.role` is a local, denormalized read projection, kept in sync via a domain
   event `user-management` consumes from `identity-access-service`/`RBAC-1` whenever the authoritative role
   changes — flagged as an implementation-detail recommendation depending on `RBAC-1`'s still-undecided
   shape, not a settled decision.

### 8.4 Migration numbering/sequencing

Only `V1__baseline_conventions.sql` exists today (documentation-only, zero tables). No speculative version
number is assigned to `staff_profile`'s migration here, because:
- `staff_profile` cannot be correctly authored until `identity-access-service`'s `tenant_user` migration has
  landed and applied — the composite FK target must physically exist first.
- Authoring Module 2's `tenant_user` migration on this module's behalf is out of `user-management`'s
  ownership and would risk pre-committing Module 2's column set before that module's own review.
- Per `CLAUDE.md` and `database-architecture.md` §6, migration history is append-only and change-controlled;
  the only safe action here is to record the dependency and defer, not to reserve or guess a version number.

**Sequencing conclusion:** Staff Management's actual Flyway migration is blocked on Module 2's `tenant_user`
migration landing first — this is already reflected in `STAFF-1`'s own dependency line, reinforced here at
the schema level: there is no partial/nullable-FK workaround, per the "never nullable, never added later as
nullable" rule in `database-architecture.md` §1.

### 8.5 Audit trail — shape if confirmed audit-worthy

Not resolved here (§16, §21 item 1), but described for implementation-readiness: if staff
creation/role-change auditing is confirmed required, `user-management` would **not** write directly into an
`audit_log` table it doesn't own. The service method would publish a domain event
(`StaffAccountCreatedEvent`, `StaffRoleChangedEvent`) inside the same transaction as the write, and
`audit-log-management` would consume it and persist its own append-only row (`tenant_id`, `actor_id`,
`action`, `target_entity`/`target_id`, `occurred_at`, before/after role values — all `NOT NULL` per
`database-architecture.md` §4's audit-completeness invariants).

## 9. Backend design

Package: `com.lms.usermanagement`, per `.claude/rules/architecture.md`'s per-domain structure. This domain
will eventually hold three story groups (Student/Teacher/Staff); only the Staff slice is designed here.

```
com.lms.usermanagement
|-- api            # flat, domain-root — the ONLY package other domains may import.
|                   #   StaffProvisioningApi (not built at MVP — no other domain
|                   #   currently reads staff data; add when a real consumer exists)
|-- staff          # implementation detail, never imported by other domains
|   |-- web         # StaffController
|   |-- service     # StaffService/StaffServiceImpl, StaffMapper
|   |-- domain      # StaffProfile (JPA entity, implements TenantOwned)
|   `-- repository  # StaffProfileRepository extends TenantAwareRepository<StaffProfile, UUID>
`-- config          # domain-local config, shared across future student/, teacher/, staff/
```

Student/Teacher Management will later get sibling `student/`/`teacher/` sub-packages under the same
convention — not designed here (§6).

**Cross-module call shape:**
- Staff account creation is a single `@Transactional` `StaffService.createStaff(...)` method that (a)
  synchronously calls `identity-access-service`'s `api` to create the `tenant_user` row with the chosen
  sub-role and get back its id, then (b) persists the `StaffProfile` row referencing that id — both writes
  land in the same DB transaction (in-process call within one JVM), matching the "synchronous in-process
  `api` call for request-time consistency" pattern this architecture already prescribes elsewhere.
- Listing/filtering staff "by role" must **not** become a `user-management` migration that `ALTER`s
  `tenant_user` — that table isn't this module's to migrate. `user-management` consumes a narrow read
  method (e.g. `TenantUserDirectoryApi.findStaffAccountsForTenant(tenantId)`) composed with `staff_profile`
  rows keyed by `user_id`, never a cross-schema SQL join, per `database-architecture.md` §5.
- Password reset (`STAFF-2`) is likewise a call into `identity-access-service`'s `api` (it owns
  `password_hash`/`must_change_password`/session revocation), not a `user-management` write to
  `tenant_user`.
- Composing `staff_profile` rows with `tenant_user` summaries must use a batch read, not one
  `identity-access-service` call per row — an in-process N+1 across the module boundary degrades the same
  way an N+1 SQL query would.

**Design-decision flag, not a business requirement (architecture judgment call):** the design above is the
recommended compliant interpretation of an ambiguous backlog note, not a certainty. A second, related
ownership ambiguity is worth flagging to whoever owns this area: `module-catalog.md` states `user-management`
"owns... Staff Management — profile/data model **and role assignment** (role *enforcement* is
`identity-access-service`'s concern; `user-management` owns the role/permission **data model**)," while
`RBAC-1`'s own backend-impact line assigns the role table/`tenant_user.role` column to
`identity-access-service`. These two documents do not agree on which module literally owns the role *data*
(as opposed to enforcement). This plan follows `RBAC-1`'s more concrete, implementation-level story text
(role lives on `tenant_user`, owned by `identity-access-service`), but this should be confirmed explicitly
before `RBAC-1`/`STAFF-1` are built — see §21 item 14.

## 10. API contract

Draft only — must be finalized via the `review-api-contract` skill into `docs/api/user-management.md`
before implementation starts on either side, per `docs/api/README.md`'s own process. All responses use the
existing `com.lms.common.api.ApiResponse<T>` envelope. No client-supplied `tenant_id`, role, or other
trust-sensitive field is ever accepted — tenant/role are always resolved server-side.

| Method + path | Auth | Notes |
|---|---|---|
| `POST /api/v1/staff` | Tenant Admin (Institute Owner) only | Body: name, contact fields (exact set open, §21 item 10), role (one of the 7 fixed values). No password field pending §21 item 3's resolution. `201` with the created staff summary, or `409` on duplicate-email-in-tenant, or `422`/`400` on validation failure — reconcile the exact validation status code against this codebase's existing convention (`400` for `MethodArgumentNotValidException`/`ConstraintViolationException` per `com.lms.common.web.GlobalExceptionHandler`, if unchanged since Module 1) rather than assuming `422`. |
| `GET /api/v1/staff` | Tenant Admin + Read-only Auditor (`V`) | Paginated, filterable by role/status, searchable by name/email. Distinguishes zero-data vs. filtered-empty in response/metadata so the frontend renders the correct empty state. |
| `GET /api/v1/staff/{id}` | Tenant Admin + Read-only Auditor (`V`) | `403`/`404` (uniform, non-distinguishing response) if `{id}` belongs to another tenant or doesn't exist. |
| `PATCH /api/v1/staff/{id}` | Tenant Admin only | Profile/role edit. `409` if attempting to assign a role outside the fixed 7-value set. |
| `DELETE /api/v1/staff/{id}` or a status-change endpoint | Tenant Admin only | **Shape genuinely undecided** pending §21 item 2 (no confirmed staff-status state machine) — do not build either a hard `DELETE` or an invented status-transition endpoint until that's resolved. |
| `GET /api/v1/staff/{id}/activity-log` | Tenant Admin + Read-only Auditor (`V`) | Reads via `audit-log-management`'s `api`, never a direct join. `403`/`404` for a cross-tenant `{id}`. |
| `GET /api/v1/staff/activity-log?staffId=` | Tenant Admin + Read-only Auditor (`V`) | Tenant-wide log, optional per-staff filter — supports the Staff Detail page deep-linking into a pre-filtered view without a second table implementation. |
| `POST /api/v1/staff/{id}/reset-password` | Tenant Admin only | Confirm-only — no new-password field submitted by the admin (staff sets their own at next login, per AC 11). Must not leak cross-tenant existence (§14/§15(c)) — same response shape/timing whether `{id}` doesn't exist or belongs to another tenant. |

Every endpoint above resolves `tenant_id` exclusively from the authenticated session context (never
request body/query/path/header) and calls into `identity-access-service`'s `api` for any credential-level
operation — `user-management` never constructs/stores a password hash itself.

## 11. Frontend screens

Portal: Tenant Admin only (`app/(tenant-admin)/`) — no other portal is involved.

**Flag before the screens below — genuine ambiguity in the Role Editor's data model.** The spec's normal
flow says the Tenant Admin "assigns **a role**" (singular), and the backlog recommends a single scalar
`role` enum column, not an independently toggleable permission set — yet the spec's own acceptance criteria
and the backlog's frontend-impact field both literally say "checkbox group" / "permission-assignment
checkboxes." These don't reconcile cleanly: "checkbox group" implies multi-select (a materially larger
feature — a permission-override table, per-permission enforcement, contradicting the "fixed sub-role list"
framing used everywhere else), while "pick one role from a fixed list of 7" implies single-select.
**Recommendation:** design the Role Editor as a single-select `RadioGroup` of the 7 fixed roles, wrapped in
`fieldset`/`legend` — this satisfies the accessibility acceptance criterion without assuming the larger
per-domain-checkbox scope nothing else in the requirements set supports. If literal per-domain checkboxes
are truly intended, that is a scope change requiring an explicit decision (§21 item 13), not something to
build unreviewed based on one ambiguous word in two documents.

### `app/(tenant-admin)/tenant-admin/staff/`

| Screen | Route | Key components | Notes |
|---|---|---|---|
| **Staff List** | `/tenant-admin/staff` | Shared responsive data-table (new primitive — see below) + card-fallback below `md`, filter (role/status/search), `Badge` for role/status (icon+text, never color alone), "Add staff" CTA | No tenant column — Tenant Admin views are single-tenant with no tenant selector, per `ui-ux.md` §1 (the opposite convention from Platform Admin's cross-tenant lists). Two distinct empty states (zero-data vs. filtered-empty, different copy/action each). Row actions are icon-only buttons needing explicit `aria-label`s. |
| **Staff Detail / Role Editor** | `/tenant-admin/staff/[staffId]` (edit), `/tenant-admin/staff/new` (create) | React Hook Form + Zod, `fieldset`/`legend`-wrapped `RadioGroup` (new primitive) for role selection, confirm-only password-reset action (reuse existing `Sheet`, not a new Dialog primitive) | Recommend a dedicated route, not a modal, given the multi-section form (`ui-ux.md` §5 explicitly flags cramped-modal forms as unusable). Cross-tenant/not-found responses render uniform generic copy regardless of the underlying 403-vs-404 reason, to avoid the UI itself leaking which case occurred. Staff-status control either omitted or rendered disabled/"not yet available" pending §21 item 2. |
| **Staff Activity Log** | `/tenant-admin/staff/activity` (optional `?staffId=` for a per-person deep-link from Staff Detail) | Same shared data-table component as Staff List, read-only rows, filter by staff/date/action-type | Read-only — no mutating row actions. Two distinct empty states (no activity yet vs. filtered-no-match). |

**New `components/ui/` primitives needed** (current inventory: `Button`, `Card`, `Input`, `Label`, `Sheet`,
`Skeleton` — no `Table`, `Select`, `RadioGroup`, `Checkbox`, `Dialog`, or `Badge` exists yet): a shared
responsive data-table component (build once, shared with Staff Activity Log and any future Platform
Admin/Tenant Admin list, per `.claude/rules/frontend.md`'s explicit call for one shared table component
across admin surfaces), `RadioGroup` (Role Editor), `Badge` (role/status). No new `Dialog` needed if the
existing `Sheet` covers the password-reset confirmation. These are additions from the already-adopted
shadcn/ui system, not new third-party dependencies, consistent with "do not add unnecessary dependencies."

Nav: add `{ label: "Staff", href: "/tenant-admin/staff" }` to
`frontend/src/components/layout/nav/tenant-admin-nav.tsx`'s existing `Dashboard`/`Profile`/`Settings`
placeholder list.

## 12. Validation rules

- **Name:** required.
- **Email:** required, Zod `.email()` format check client-side (UX convenience only — tenant-scoped
  uniqueness cannot be validated client-side and is only knowable from the backend response at submit time,
  per `frontend/CLAUDE.md`'s forms rule).
- **Role:** required, single value from the fixed 7-value enum (`z.enum([...])`) — no free-text role, no
  default preselection (force an explicit choice so an admin can't accidentally submit a default/wrong
  role).
- **Password at creation:** the spec does not define whether the admin sets an initial password, one is
  generated, or an invite/must-change-password flow is used (§21 item 3). The Add Staff form should **not**
  include a password field until this is resolved — building one now risks inventing an unratified
  credential flow.
- **Reset-password action:** no new-password input on the admin side at all — confirm-only; the staff member
  sets their own credential at next login (AC 11).
- **Subdomain/tenant fields:** never present on any staff form — tenant is always the authenticated admin's
  own, resolved server-side.

## 13. Error cases

| Case | Expected behavior | Status |
|---|---|---|
| Non-Owner sub-role reaches a staff page/endpoint | `PermissionDeniedState`, driven only by a real 401/403 from the fetch — never a client-side-only role check | Settled requirement; **enforcement deferred**, blocked on RBAC-2 |
| Read-only Auditor attempts a mutation | Controls simply don't render (hide, not disable-with-tooltip); a stray direct API attempt still fails 403, surfaced via the shared async-error pattern | Settled requirement; enforcement deferred |
| Cross-tenant access (guessed/edited `staffId` URL) | Backend rejects 403/404; frontend renders **uniform generic "not found" copy** regardless of which status came back, so the UI itself doesn't amplify a cross-tenant existence signal | Settled requirement; enforcement deferred |
| Duplicate email within tenant | Backend `409`/validation error with `fieldErrors: [{field: "email", ...}]`, mapped to an inline field-level error under the Email input via React Hook Form's `setError` — no precedent implementation exists yet anywhere in this codebase to point to (tenant-registration's equivalent flow hasn't been built either), but must conform to the existing `ApiError`/`FieldError` contract in `frontend/src/lib/api/types.ts` | Settled shape; **no existing precedent to copy** |
| Staff-count plan limit reached (Phase 2) | Top-level error/toast, not a field error (not tied to one input); "Add staff" CTA stays visible/clickable, never pre-emptively hidden client-side | Phase 2, design-for-now only |
| Staff status change attempted | No defined state machine (§21 item 2) — do not build a client-side transition UI implying one; gate any control as unavailable until ratified | **Open decision — do not invent** |
| Approval provisioning / two-write partial failure | No document specifies compensating behavior if the `identity-access-service` credential write succeeds but the `staff_profile` write fails, or vice versa | **Open decision — §21 item 11** |

## 14. Tenant-isolation rules

**Ownership and scoping.** Staff accounts are tenant-owned data. The underlying credential row
(`tenant_user`) and this module's `staff_profile` both carry `NOT NULL tenant_id` with
`UNIQUE (tenant_id, email)` (on `tenant_user`) / `UNIQUE (tenant_id, user_id)` (on `staff_profile`) — never
a global unique constraint on email. This must not be relaxed even for convenience (e.g. "email is the
natural login identifier so make it globally unique" is a tenant-isolation violation, reject on sight in
review). Composite indexes lead with `tenant_id`, shaped to the actual query pattern (§8.2).

**No legitimate cross-tenant read for this module — confirmed, not assumed.** The spec's §7 states there is
"no cross-tenant read requirement for staff CRUD itself." This is confirmed: staff management is a
single-tenant operational domain. The one adjacent cross-tenant-shaped concern, `FR-UM-9`'s staff-count
check, is a tenant-scoped read of the *acting tenant's own* plan configuration, not a lookup into another
tenant's data. There is no legitimate reporting/aggregation view in this module that needs a deliberate
cross-tenant bypass. A future Platform-Admin-facing "staff counts across tenants" view would be new scope
needing its own Platform-Admin-authorized design — not to be bolted onto Tenant-Admin-scoped endpoints.

**Cross-tenant negative-test shape, per endpoint** (every one of these needs a passing test proving 403/404,
never 200 with empty/filtered data, before the corresponding endpoint is considered done):
- **Staff list** — a tenant B id or filter param supplied by a tenant A actor must not leak tenant B rows;
  the query must be structurally incapable of returning them, not merely filtered by convention.
- **Staff detail** — tenant A actor requesting tenant B's staff id → 403/404.
- **Role-edit** — tenant A actor attempting to change tenant B's staff role → 403/404, verified via a
  follow-up read that the row is actually unchanged, not just the response code.
- **Activity log** — tenant A actor requesting tenant B's staff activity log → 403/404; the tenant filter
  must be applied by the shared audit-log read path itself, `user-management` must not re-derive or pass
  through a caller-supplied tenant scope when calling that `api`.
- **Password reset** — tenant A actor attempting to reset tenant B's staff password → 403/404, no
  credential/hash mutation, no reset token/email issued cross-tenant.

**The repository-method check.** No repository method in `user-management`'s staff repository may accept a
caller-supplied `tenant_id` parameter — the method signature takes the id being addressed, relying on
`TenantAwareRepository`'s already-resolved tenant context, never a parameter threaded from the controller.

## 15. Security rules

**(a) Authorization — Institute-Owner-only, stricter than the domain norm.** Per the matrix, "Staff &
roles" is `V/C/E/D` for Institute Owner and `—` for every other sub-role, *including* Read-only Auditor
(who elsewhere in the matrix gets at least `V`). Every staff CRUD and role-assignment endpoint — including
list/detail — must reject every actor except Institute Owner, server-side. Do not gate only C/E/D and leave
list/detail open to any authenticated staff member "since read is safer" — for this domain area, read is
also denied to everyone but the Owner. Every endpoint needs an explicit negative-path test per non-Owner
sub-role, not just one generic "non-admin gets 403" test.

**(b) Read-only Auditor — zero mutating path, reinforced.** Already a platform-wide invariant; stricter here
because Auditor has *no* access at all to staff/role data, not even view-with-no-mutate. Implement as a
positive allow-list (only Owner reaches the handler), not a deny-list enumerating denied roles — a deny-list
is one missed enum value away from silently admitting a new sub-role. Both a UI-hiding check and a direct-API
deny-path test are required; UI-hiding alone is not evidence of enforcement.

**(c) Password-reset flow security.**
- *No cross-tenant reset.* The reset endpoint resolves the target staff id's tenant from the database row
  itself and compares it against the acting admin's resolved tenant context — never a client-supplied
  tenant claim.
- *No existence leakage.* "Not found in my tenant" and "exists in a different tenant" must be
  indistinguishable in response status, body shape, and approximate timing. The safest concrete
  implementation is to route password reset exclusively through an internal staff id the admin can only
  have obtained from their own tenant's already-tenant-filtered staff list/detail endpoints, rather than
  exposing any email-lookup-based reset entry point.
- *Read-only Auditor denial.* Same allow-list pattern as (a)/(b), applied to this specific endpoint.

**(d) Credential-creation contract boundary.** `user-management` must never itself construct, hash, or
persist a password/credential value for a staff account — it owns only the tenant-scoped profile/role row
and delegates all credential lifecycle to `identity-access-service`'s `api`. At review time: reject any
`user-management` code that imports/writes to an `identity-access-service` entity or repository directly, or
that has its own password-hashing dependency. **What the admin-facing creation UX flow actually looks like
is not specified and is not decided here** — see §21 item 3.

**(e) Role assignment — self-escalation defense.** The primary, sufficient defense is (a): no sub-role other
than Institute Owner reaches the role-edit endpoint at all, and there is exactly one Owner per tenant, so
the "sub-role edits its own role to Owner" scenario is already foreclosed. **Recommendation, not a decided
requirement:** a defense-in-depth check — the role-edit handler independently refuses to let the acting
user's own id be the target of a role-change request — is a cheap, high-value hardening measure worth
raising for explicit decision at `docs/api` contract time, protecting against a future regression in (a)'s
allow-list.

## 16. Audit requirements

**This is an open decision, presented as such, not resolved.** The spec's §9 states plainly that whether
staff account creation/role changes must produce an `audit-log-management` entry "is not specified anywhere
in reviewed material," and this is confirmed against `.claude/rules/security.md`'s canonical mandatory-audit
list (price changes, payment approvals/rejections, device resets, access/expiry extensions, reactivation
approvals, content deletions, settlement changes, impersonation) — staff creation/role-change/password-reset
are **not** on that list as currently written. This module must not silently add itself to that list, nor
silently skip audit logging on the theory that "not on the list means not required" — both are decisions
requiring explicit sign-off (and, if the canonical list itself changes scope, that change belongs in its own
reviewed update, not an ad hoc edit inside a feature PR).

**Professional recommendation, labeled as a recommendation only, not a requirement:** treat staff-account
creation, role changes, and password reset as audit-worthy, given the blast-radius argument (role assignment
is the single highest-leverage action a Tenant Admin can delegate; password reset is a credential-mutation
action on someone else's account, structurally similar to the already-mandatory device-reset/access-extension
cases). If confirmed, the compliant shape is described in §8.5. This remains for the product/security
decision-maker to ratify, not something implementation should proceed against as if already decided.

## 17. Payment impact

**None.** Confirmed against `STAFF-1`/`STAFF-2`'s own "Payment impact" fields (both `None`) in
`docs/planning/product-backlog.md`, and independently confirmed by every parallel review — nothing in the
spec, backlog, role matrix, or ADRs ties this module to money in any form. No `payment-ledger-specialist`
review was performed for this reason.

## 18. Tests

**Grounding:** verified directly that no code beyond the Module 1 shared kernel exists; per the backlog,
`STAFF-1` hard-blocks on `TEN-1`/`TEN-2`, `AUTH-1`/`AUTH-2`, `RBAC-1`/`RBAC-2`, none of which exist. This
means **no test in this module can exercise a real login, a real resolved-tenant context, or a real
permission check today.**

### Unit tests — honest conclusion: nothing meaningfully unit-testable today

The backlog's "DTO validation" and "password-reset token logic" testing-requirements items both depend on
design decisions still explicitly open (the `tenant_user`/`staff_profile` split, the staff-status state
machine, the password-reset token mechanism). Writing unit tests against an assumed shape now would either
mislead reviewers into believing the shape is settled or need to be rewritten the moment the real design
lands. **Named follow-up**, once `RBAC-1`/`RBAC-2` and the domain model land: staff-create/role-assignment
DTO validation (required fields, email format, role restricted to the 7-value enum); an exhaustive
status-transition-table test, *if* a state machine gets confirmed; password-reset token expiry/single-use
logic in isolation, *if* a token design gets confirmed.

### Testcontainers integration tests, once buildable

- **`UNIQUE (tenant_id, email)` enforced, not global** — positive (same email in two different tenants both
  succeed), negative (duplicate within one tenant rejected cleanly), and a **genuine two-thread concurrent
  race test** (not just sequential check-then-insert) proving exactly one of two simultaneous same-tenant
  same-email creation attempts commits, matching this repo's established rigor bar for uniqueness
  constraints under race conditions.
- Staff creation persists with `tenant_id` sourced from the resolved authenticated context; role restricted
  to the fixed enum at the service layer, not just the DTO.
- Role assignment enforced: only Institute Owner can create/edit/assign a role.
- **Read-only Auditor / non-Owner deny-path — one explicit test method per sub-role**, not a single
  parameterized loop, so each failure surfaces which specific role regressed: Finance Staff, Course
  Coordinator, Student Support, Content Manager, Exam Manager, Attendance Operator each denied on every
  mutating action; Read-only Auditor denied on every mutating action **and** separately proven to retain 200
  on list/detail GETs (its permission is `V`, not `—` — must not collapse into the same test as the other
  six).
- Password reset persists a new hash, sets `must_change_password`, and invalidates active `device_session`
  rows for that staff member; does not leak cross-tenant email existence (same response shape/timing for
  "doesn't exist" vs. "exists in another tenant").
- Activity log tenant-scoped read, proven with real seeded rows in two tenants in the same test run (not by
  seeding only one tenant and asserting an empty result for the other, which would not catch a missing
  filter).

### Mandatory cross-tenant negative tests

Not a representative sample — every new tenant-owned endpoint needs its own, per `.claude/rules/tenancy.md`:
staff list (no filter/pagination/search combination leaks tenant B rows), staff detail, role-edit (plus a
follow-up read confirming no mutation occurred), delete/deactivate (once the status mechanism is designed),
activity log (both the per-staff-id and the list-scoped variant), password reset (plus confirming no
`device_session` side effect occurred cross-tenant).

### Playwright, once the frontend screens are buildable

Staff list's two distinct empty states (different copy/action, not reused); create + role-assign flow,
including a negative case for a role value outside the fixed list; Read-only Auditor's **both halves** — UI
shows no mutating controls, **and** a direct API call (bypassing the UI) still fails server-side, since
UI-hiding alone does not satisfy "no mutating endpoint succeeds regardless of stale UI state"; Role Editor's
`fieldset`/`legend` grouping asserted structurally (accessibility tree / DOM check), not just visually;
password-reset flow plus its Read-only-Auditor deny variant; Activity Log's loading/populated/empty states
and `md`-breakpoint card fallback.

### Named follow-ups — explicitly blocked, not silently skipped

1. **All Testcontainers and cross-tenant negative tests above** — blocked on `TEN-1`/`TEN-2`, `AUTH-1`/`AUTH-2`,
   `RBAC-1`/`RBAC-2`. A test written against a hand-rolled fake principal instead of the real stack would not
   catch a real enforcement bug and must not be substituted for the real thing.
2. **All Playwright tests above** — blocked on the same backend prerequisites plus the frontend screens
   themselves not existing yet.
3. **Staff status/removal state-machine tests** — blocked on §21 item 2's resolution; inventing a state
   machine to make a test pass would itself violate the "do not invent business requirements" instruction.
4. **`must_change_password`-on-manual-creation test** — blocked on §21 item 3.
5. **Password-reset token mechanism tests** — blocked on §21 items 3/4 and on `identity-access-service`'s
   ownership of credential mechanics.
6. **Password-reset email delivery test** — soft-blocked on `NOTIF-1`/`NOTIF-2`; until then, tests stop at
   "reset persisted correctly," not "notification was dispatched."
7. **Real activity-log content assertions** (beyond the tenant-scoping mechanism) — soft-blocked on
   `AUDIT-1`/`AUDIT-2` and on §16's open audit-logging decision.
8. **Staff-count-vs-plan-limit rejection test (`FR-UM-9`)** — explicitly out of MVP scope, blocked on
   unratified Module D; tracked as Phase 2, not part of this module's MVP test plan.

## 19. Documentation changes

- `docs/architecture/database-architecture.md` — new `staff_profile` table entry, once §8.1's
  table-ownership tension and §21 item 2's status state machine are resolved.
- `docs/architecture/modular-monolith.md` / `docs/requirements/module-catalog.md` — reconcile the role-data
  ownership disagreement flagged in §9/§21 item 14 between `user-management` and `identity-access-service`.
- `docs/api/user-management.md` (new) — produced via `review-api-contract` from §10's draft before
  implementation begins on either side.
- `docs/requirements/open-decisions.md` — append the items newly surfaced or sharpened by this plan that
  weren't already tracked with this level of detail: the `tenant_user`/`staff_profile` table-ownership
  tension, the role-data-ownership disagreement between `module-catalog.md` and `RBAC-1`, the checkbox-vs-
  single-role Role Editor ambiguity, the cross-tier `must_change_password` inconsistency (§21 item 3), and
  the cross-domain partial-write-failure gap (§21 item 11).
- `docs/requirements/specifications/02-staff-management.md` — its existing "Open decisions" list already
  covers items 1-5 below; no edit needed there unless/until those get resolved.
- No change to `.claude/rules/security.md`'s canonical audit-action list as part of this module (§16) —
  flagged as a separate follow-up requiring its own sign-off, not resolved here.

## 20. Implementation order

**Honest conclusion: nothing in `STAFF-1` or `STAFF-2` — not even a backend-only slice — can be implemented
before Module 2 (`AUTH-1`/`AUTH-2`/`AUTH-3`), Module 3 (`RBAC-1`/`RBAC-2`), and Module 4 (`TEN-1`/`TEN-2`/
`TEN-3`) are built and passing their own tests.** This is a stronger blocking situation than `MVP-004`
(Tenant Management) faced, where `TEN-1` (registration) was independently buildable because it had a
genuinely unauthenticated entry point. Staff Management has no equivalent unauthenticated slice — every
acceptance criterion presupposes a resolved tenant context, an authenticated Tenant Admin session, *and* a
working role/permission model (half of `STAFF-1`'s acceptance criteria are themselves deny-path tests,
meaningless without `RBAC-2`'s enforcement existing). Building real Spring Boot code now would require
faking one of these three — not proposed here; flagged as the blocker instead, per this task's explicit
instruction not to build a stopgap/fake-auth shortcut.

**What genuinely can happen now (design/handoff work, not code):**
1. This plan itself — settling `staff_profile`'s shape and its dependency on `tenant_user`/RBAC's role model
   *before* those modules freeze their own schemas, rather than discovering a breaking change afterward.
2. Two concrete asks to feed into `AUTH-1`/`AUTH-3`/`RBAC-1`'s own design work, since those modules would
   otherwise design `tenant_user`'s role enum and provisioning `api` with no visibility into Staff's needs:
   - `tenant_user.role`'s enum must reserve the seven named staff sub-roles now, so `RBAC-1`'s migration
     doesn't need a follow-up migration once Staff lands (migration history is append-only — getting the
     enum right upfront has real cost-avoidance value).
   - `identity-access-service`'s `api` needs a tenant-scoped "create `tenant_user` with role" method and a
     "reset credential" method designed with Staff's actual call shape in mind (returns generated id;
     supports setting `must_change_password`), rather than guessed at retroactively by `user-management`
     once `identity-access-service` has already shipped a narrower contract.
3. No Flyway migration for `staff_profile` can be written yet (§8.4) — deferred to Stage 1.
4. No unit/integration tests have real value yet (§18) — they would only exercise mocks of not-yet-designed
   interfaces and would likely need rewriting once those contracts solidify.

**Stage 1 (once `TEN-1`/`TEN-2`/`TEN-3`, `AUTH-1`/`AUTH-2`/`AUTH-3`, `RBAC-1`/`RBAC-2` are all merged with
their own cross-tenant/authz tests green):**
1. Flyway migration for `staff_profile` — contingent on §8.1's table-ownership tension being explicitly
   resolved first (or confirmed that zero new columns are needed if the resolution goes the other way).
2. `domain`/`repository`: `StaffProfile implements TenantOwned`, `StaffProfileRepository extends
   TenantAwareRepository<StaffProfile, UUID>`.
3. `service`: `StaffService` orchestrating the two-step create (`identity-access-service` `api` call +
   `staff_profile` insert) in one `@Transactional` method.
4. `web`: `StaffController`, sitting behind `RBAC-2`'s shared method-security mechanism — not a bespoke
   authz check inside `user-management`.
5. `STAFF-2` (activity log + password reset) as a distinct follow-on after `STAFF-1` (explicit hard
   blocker per the backlog). Its two soft dependencies (`AUDIT-1`/`AUDIT-2`, `NOTIF-1`/`NOTIF-2`) should each
   degrade honestly if not yet ready — an explicit "activity log not yet available" empty state rather than
   a fabricated data source, and a deferred/no-op notice for password-reset confirmation rather than
   inventing a shortcut notification path.
6. Backend tests per §18. Frontend screens (§11) + Playwright tests per §18. Security + tenant-isolation
   review pass. Documentation updates per §19. Commit as separate backend and frontend commits, per
   `.claude/rules/git-workflow.md`.

This plan does not authorize implementation of any of the above until its stated blockers (Modules 2, 3, 4)
exist and are merged.

## 21. Risks and unresolved decisions

Compiled from all six parallel reviews. None of the items below are resolved by this plan — each is
surfaced exactly as the source documents (or the cross-document comparison performed during this review)
leave it.

1. **Audit-log requirement for staff creation/role changes is unspecified**, and is not on
   `.claude/rules/security.md`'s canonical mandatory-audit list. The spec's own text: "recommend treating as
   audit-worthy pending an explicit decision" — a recommendation, not a ratified requirement. See §16.
   Sources: `open-decisions.md` §5; spec §9/"Open decisions"; `STAFF-1` items 9/13.
2. **No documented state machine for "Staff status"** (active/suspended/removed only *named*, not specified
   — states, transitions, and triggering actor all undefined). Sources: spec §5/"Open decisions";
   `source-requirements.md` Module 5.
3. **Whether manually-created staff accounts get a "must change password" flag is unresolved — and there is
   a cross-tier documentation inconsistency.** `FR-UM-1` states student manual/bulk-created accounts "carry
   a 'must change password' flag"; the equivalent is absent for staff in `functional-requirements.md`/
   `open-decisions.md`, and both the spec and `AUTH-3`'s backlog story list it as an open decision. However,
   `docs/ui-ux/authentication-design-spec.md` §3.7 already describes the First-Login-Password-Change flow as
   reached by "admin-created Teacher/Staff/Student accounts... per module 3/4/5 'manual creation by admin'
   flows" — the UI/UX-tier document already assumes the flag applies to Staff, while the requirements-tier
   documents still list it as unresolved. Flagging the inconsistency for reconciliation, not resolving it
   here.
4. **Password-reset flow specifics (self-service vs. admin-triggered vs. both) are unresolved.** `STAFF-2`
   describes only an admin-triggered reset; `docs/ui-ux/authentication-design-spec.md` §3.3/3.4 separately
   describes a role-agnostic self-service Reset Password flow not explicitly scoped to exclude staff,
   creating ambiguity about whether staff get self-service reset "for free" via the shared auth stack.
5. **Module D (Feature Flag & Plan Limit Engine) ownership is unratified**, blocking `FR-UM-9` even once
   Phase 2 is reached. Sources: `open-decisions.md` §6; `module-catalog.md`; spec §10.
6. **Teacher/Teacher Assistant vs. Staff Management's role list — ambiguity between raw and reconciled
   sources, surfaced not resolved.** The raw `source-requirements.md` "Suggested roles" list for Module 5
   includes Teacher and Teacher Assistant alongside the 7 staff sub-roles in one undifferentiated list. The
   reconciled `user-roles-and-permissions.md` and the spec's own "Actors" section place Teacher/Teacher
   Assistant in a separate portal/module. No document explicitly states this separation was an intentional
   reconciliation decision — inferred from consistency across the reconciled docs, not a stated decision.
   Independently, Teacher Assistant's entire permission boundary remains PROVISIONAL/unratified per
   `user-roles-and-permissions.md` §3 — but that is a Teacher Management (Module 4) concern, not a Staff
   Management one, given the reconciled docs' scoping.
7. **Reactivation-approver precedence ambiguity does not apply to this module** — checked and excluded; it
   affects payment-slip/enrollment/expiry modules, not Staff Management.
8. **Expense-deletion tension does not apply directly, but an analogous, module-local gap exists.** Staff
   Management's own matrix grants Institute Owner literal hard `D` on staff accounts. Once staff
   creation/role-change audit logging exists (item 1), no document specifies whether hard-deleting a staff
   account cascades or orphans that staff member's historical audit/activity-log rows, or whether a
   "removed" status (item 2) is the intended mechanism to preserve the audit trail instead of a hard delete.
9. **"Own-area" audit scoping for staff sub-roles is undefined, and appears narrower in `STAFF-2` than the
   permission matrix implies.** The matrix grants every non-Owner, non-Auditor sub-role "V (own-area
   actions)" on the Audit-log domain row, but no document defines "own area," and `STAFF-2`'s own acceptance
   criteria describe the staff Activity Log as Tenant-Admin-only, with no described access path for a
   sub-role viewing their own-area audit rows. Whether these are the same feature or two distinct ones is
   unaddressed.
10. **Staff profile field set is unspecified** beyond name/email/role — no document enumerates phone,
    department, or other fields for the create-staff-account flow.
11. **Cross-module transactional guarantee on staff-account creation is undefined.** `STAFF-1` requires a
    call into `identity-access-service`'s `api` for the credential row, separate from `user-management`'s
    own profile/role write. No document specifies rollback/compensation behavior if one write succeeds and
    the other fails, risking an orphaned credential-without-profile (or vice versa) state.
12. **`tenant_user`-reuse-vs-dedicated-table tension (§8.1).** The backlog's literal wording recommends
    reusing `tenant_user` directly; this plan's database-architect and solution-architect reviews
    independently concluded a dedicated `staff_profile` table is required to comply with the one-table-
    one-domain architecture rule. Flagged for explicit reconciliation before `STAFF-1`'s real migration is
    authored — not silently overridden by this plan.
13. **Checkbox-vs-single-role ambiguity in the Role Editor (§11).** The spec/backlog's literal "checkbox
    group" wording conflicts with the "assigns a role" (singular) framing used everywhere else. This plan
    recommends a single-select `RadioGroup` as the reading most consistent with the rest of the requirements
    set, but flags this as an interpretation, not a confirmed decision.
14. **Role-data ownership disagreement between `module-catalog.md` and `RBAC-1` (§9).** `module-catalog.md`
    states `user-management` owns the role/permission *data model*; `RBAC-1`'s own backend-impact line
    assigns the role table/`tenant_user.role` column to `identity-access-service`. This plan follows
    `RBAC-1`'s more concrete text, but the disagreement should be resolved explicitly before either module
    freezes its schema — a wrong call here is expensive to unwind given migration history is append-only.
15. **Sequencing/feasibility risk (not a business decision, but material context).** `STAFF-1`'s hard
    blockers (`TEN-1`/`TEN-2`, `AUTH-1`/`AUTH-2`, `RBAC-1`/`RBAC-2`) do not exist in the codebase today.
    Several of the "open decisions" above (must-change-password flag, password-hash storage/reset
    mechanics) are really shared decisions at the `tenant_user`/`AUTH-1`/`AUTH-3` level that also affect
    Student (Module 3) and Teacher (Module 4) — they should be resolved once at that shared level rather
    than separately re-litigated per module, to avoid the three modules drifting into inconsistent answers.
    Related: `docs/planning/risk-register.md` R8 ("Read-only Auditor or low-privilege staff sub-role
    reaching a mutating endpoint") is directly relevant here given five of seven sub-roles have zero access
    and Auditor is view-only — this module is a concrete instance of a risk already tracked platform-wide.

---

*This plan does not authorize implementation. Every item in §21 remains open. Per §20, no part of this
module — backend or frontend — may begin implementation until Modules 2 (`identity-access-service`), 3
(RBAC), and 4 (`tenant-management`, specifically `TEN-1`/`TEN-2`/`TEN-3`) exist and are merged with their
own passing tests.*
