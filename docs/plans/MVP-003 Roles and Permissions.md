# Roles and Permissions — Module Plan (MVP-003 / RBAC-1..3)

Source: GitHub issue #3 (`https://github.com/mohanranaweera/lms-saas-platform/issues/3`),
`docs/planning/product-backlog.md` lines 174-231 (RBAC-1/2/3 stories),
`docs/requirements/user-roles-and-permissions.md`, `docs/architecture/authentication-authorization.md`,
ADR-006, ADR-007, `.claude/rules/*`. Produced by delegating to product-requirements-analyst,
solution-architect, database-architect, security-reviewer, qa-test-engineer, and ui-ux-reviewer in
parallel, then reconciled below. `payment-ledger-specialist` was not invoked — this module has no
direct payment/ledger impact (see §17) — but one specialist review independently surfaced a
documentation contradiction touching payment rules, carried into §21.

**This is a plan only.** No application code, migration, or config file was created or edited to
produce it. Two genuine disagreements between specialist agents are resolved explicitly below (the
`tenant_user.role` mechanism in §8, and the RBAC-2 enforcement mechanism in §9) rather than silently
picking a side.

---

## 1. Business goal

Establish a fixed, tenant-aware role/permission data model covering all 12 roles plus
Anonymous/Public, and make every protected backend endpoint independently enforce authorization
server-side — re-verified for the resolved tenant context on every request — regardless of what any
client UI displays. Pair this with a single shared frontend permission-denied UX driven only by real
backend signals (401/403 or session-payload role), so that from Module 4 onward every new domain
controller inherits a working, testable authorization mechanism instead of each module reinventing
access checks.

---

## 2. Roles and permissions

### 2.1 Role list (12 roles + Anonymous/Public)

Per `docs/requirements/user-roles-and-permissions.md` §1:

| Role | Scope | Portal route group | Self-registers? |
|---|---|---|---|
| Platform Admin | Platform-wide, cross-tenant, **no tenant association** | `app/(platform-admin)/` | No — provisioned internally |
| Tenant Admin / Institute Owner | Single tenant, full admin | `app/(tenant-admin)/` | No — created on tenant approval |
| Finance Staff | Single tenant, finance/payments | `app/(tenant-admin)/` | No — created by Tenant Admin |
| Course Coordinator | Single tenant, courses | `app/(tenant-admin)/` | No |
| Student Support | Single tenant, support/ticketing | `app/(tenant-admin)/` | No |
| Content Manager | Single tenant, materials/content | `app/(tenant-admin)/` | No |
| Exam Manager | Single tenant, exams | `app/(tenant-admin)/` | No |
| Attendance Operator | Single tenant, attendance | `app/(tenant-admin)/` | No |
| Read-only Auditor | Single tenant, read-only across all areas | `app/(tenant-admin)/` | No |
| Teacher | Single tenant, scoped to assigned courses | `app/(teacher)/` | No — created/approved by Tenant Admin |
| Teacher Assistant | Single tenant, subset of Teacher scope — **PROVISIONAL, see §2.3** | `app/(teacher)/` | No |
| Student | Single tenant, own enrollments/records | `app/(student)/` | Yes — via tenant storefront (open question, §21) |
| Anonymous / Public | No tenant scope beyond resolved storefront tenant; **not a persisted role row** | `app/(public)/` | N/A |

Tenant Admin plus the 7 operational roles (Finance Staff through Read-only Auditor) are collectively
the "staff sub-roles," all tenant-scoped. Platform Admin is platform-scoped with no tenant
association — this asymmetry is a modeling requirement, not incidental detail.

### 2.2 Staff sub-role domain-level permission matrix (V/C/E/D/A)

Verbatim from `user-roles-and-permissions.md` §2 — the domain-level starting matrix for IA/navigation
purposes. `docs/api` owns the authoritative endpoint-level version per-domain, populated as each
domain's contracts are reviewed (not duplicated here in full detail):

| Domain area | Institute Owner | Finance Staff | Course Coordinator | Student Support | Content Manager | Exam Manager | Attendance Operator | Read-only Auditor |
|---|---|---|---|---|---|---|---|---|
| Students | V/C/E/D | V | V | V/C/E | V | V | V | V |
| Teachers | V/C/E/D | — | V/C/E | V | — | — | — | V |
| Staff & roles | V/C/E/D | — | — | — | — | — | — | V |
| Courses | V/C/E/D | V | V/C/E/A | V | V | V | V | V |
| Materials | V/C/E/D | — | V | — | V/C/E/D | — | — | V |
| Payments / slips | V/C/E/A | V/C/E/A | — | V | — | — | — | V |
| Finance & expenses | V/C/E/D | V/C/E/D | — | — | — | — | — | V |
| Attendance | V/C/E | — | — | — | — | V/C/E | V |
| Exams | V/C/E/A | — | — | — | — | V/C/E/A | — | V |
| Devices | V/C/E | — | — | V (request only) | — | — | — | V |
| Access & expiry / reactivation | V/C/E/A | V (approve if finance-adjacent) | — | V | — | — | — | V |
| Reviews moderation | V/A | — | V/A | — | — | — | — | V |
| Audit log | V | V (own-area) | V (own-area) | V (own-area) | V (own-area) | V (own-area) | V (own-area) | V (full) |
| Branding & settings | V/C/E | — | — | — | — | — | — | V |
| Support tickets | V/C/E/D | — | — | V/C/E | — | — | — | V |

**Hard, non-negotiable rule regardless of matrix content:** Read-only Auditor has no mutating
endpoint that succeeds under any circumstance, server-side — regardless of what a stale client UI
exposes.

### 2.3 Teacher vs. Teacher Assistant — PROVISIONAL, not ratified

Per §3 of the requirements doc, this split is a **proposed default pending sign-off**, not a
confirmed business decision:

| Capability | Teacher | Teacher Assistant (PROVISIONAL) |
|---|---|---|
| View assigned course content/roster | Yes | Yes |
| Create/edit modules, lessons, materials | Yes | Yes |
| Publish/unpublish a course, change pricing | Yes | No |
| Mark attendance | Yes | Yes |
| Create/edit exams | Yes | Yes (draft only) |
| Publish exam results | Yes | No |
| Respond to course reviews | Yes | No |
| View roster-wide student contact/payment info | Yes | No — attendance/exam-relevant fields only |

The role **value** `TEACHER_ASSISTANT` is real and must exist in the data model now (RBAC-1 must
"support" the split); the **permission boundary** above must not be hard-gated as if ratified. Every
implementation artifact touching this split (code, tests, docs, UI copy) must carry a visible
PROVISIONAL marker so a green build is never mistaken for sign-off.

### 2.4 Cross-cutting rules (already fixed, not open)

Per §4: server-side enforcement independent of client display; permission-denied UI driven only by
verified 401/403 or session-payload role, never a client-stored role string; every check evaluated
for the resolved tenant context; Platform Admin's platform-scoped permissions never implicitly grant
tenant-admin-equivalent access without an explicit, audited impersonation flow (not built in this
module — see §6); device-limit override precedence (student > course > tenant > plan) is confirmed
**only for device limits** and must not be assumed to generalize to this module or any other feature.

---

## 3. Preconditions

1. **Branch/merge gap (hard, factual, must be resolved before implementation).** The active branch
   (`feature/roles-and-permissions`) does not have Module 2 (Authentication) merged. AUTH-1/2/3 exist
   only on a separate, unmerged branch (`feature/authentication-Foundation`), which implements
   `com.lms.identityaccessservice` with `TenantUser` (coarse `role` CHECK-enum: `TENANT_ADMIN`,
   `STAFF`, `TEACHER`, `STUDENT` — that migration's own comment reserves the sub-role breakdown for
   "a later module," i.e. this one), `PlatformAdminUser` (separate table, no `role` column, implicit
   `PLATFORM_ADMIN`), `AuthenticatedPrincipal`/`AuthenticatedPrincipalHolder` (live role re-read every
   request), and the security filter chain (`JwtAuthenticationFilter`, `TenantResolutionFilter`,
   `SecurityFilterChainConfig`). Confirmed directly: `backend/src/main/java/com/lms/identityaccessservice`
   does not exist in this branch's source tree; stale `.class` files under `backend/target/` are local
   build residue from that other branch, not evidence of merged code. **RBAC-1 cannot widen a role
   enum that isn't on this branch — merge/rebase the auth-foundation branch in before RBAC-1 starts.**
2. **APP-1, APP-4 (Module 1)** are hard blockers for RBAC-1 (product-backlog.md line 183) — confirm
   merged/available.
3. **AUTH-2 (resolved-actor context) and effectively TEN-3 (tenant context)** are hard/effective
   blockers for RBAC-2 (line 203) — same branch-gap dependency as precondition 1.
4. **APP-2** is a hard blocker for RBAC-3 (line 222) — the base Next.js shell / shared component
   library foundation, already present in this branch (`components/states/*`, `lib/api/*`).
5. **No real business-domain controllers exist yet to enforce against.** RBAC-2's stated scope is
   "every domain controller from Module 4 onward" — Module 4+ doesn't exist yet. RBAC-2 must prove its
   mechanism against a representative test-only fixture controller (see §18), and the obligation to
   apply it to each real controller carries forward as a standing, recurring requirement on every
   future module — not something RBAC-2 alone discharges.
6. **`TenantAwareRepository`/`TenantContextHolder`** (ADR-006, Application Foundation) must already
   exist and be stable — confirmed present in this branch at
   `backend/src/main/java/com/lms/common/persistence/` and `.../tenant/`.

---

## 4. User flows (domain-model level)

1. Tenant provisioning creates a Tenant Admin — a `TenantUser` row with role Tenant Admin, no
   self-registration path.
2. Tenant Admin creates a staff account and assigns one of the 7 tenant-scoped staff sub-roles,
   scoped to their own tenant only. (The assignment *endpoint* itself belongs to Module 5 — Staff
   Management, which depends on Module 3 per the dependency graph — RBAC-1 supplies the catalog/data
   model Module 5 assigns against; see §10.)
3. Tenant Admin (or approver, per open question §21.2) creates/approves a Teacher account.
4. A staff member with insufficient domain permission attempts a mutating action; the request is
   rejected 403 server-side, independent of what the client UI rendered for them.
5. A Read-only Auditor attempts any mutating call on any domain; it is rejected server-side
   unconditionally, even if a future UI accidentally exposes a mutating control.
6. A request against a tenant-owned resource is evaluated for the resolved tenant context, not just
   role name — a Finance Staff user valid in tenant A must not act on tenant B's resource merely
   because the role label matches.
7. Platform Admin authenticates and acts within platform-scoped areas only; any attempt to reach a
   specific tenant's admin-equivalent data without the (not-yet-built) audited impersonation flow is
   rejected.
8. A logged-in user hits a 401/403; the frontend renders the shared Permission-Denied state (403) or
   redirects to login (401), sourced only from that server signal — never from a cached client role.
9. A user follows a stale/hidden nav link or types a direct URL their role shouldn't reach; the
   client-side guard is UX convenience only — the backend independently rejects the underlying call.
10. **(Deferred, not built now)** Platform Admin "view as tenant" impersonation — the data model must
    not preclude adding it later; when built, start/end must each audit-log, with the impersonating
    and impersonated identities recorded distinctly.

---

## 5. Acceptance criteria

Reconciled from issue #3 and `product-backlog.md` RBAC-1/2/3 — merged and made testable, not a
verbatim repeat of either source.

### Data model (RBAC-1)
- **AC1** — All 12 roles plus Anonymous/Public are representable; Anonymous/Public is explicitly
  *not* a persisted row.
- **AC2** — Every staff sub-role assignment (Tenant Admin + 7 operational roles) carries a non-null
  `tenant_id` resolved from trusted authenticated context.
- **AC3** — Platform Admin is modeled with no tenant association whatsoever (separate table, no
  `tenant_id` column) — not a staff sub-role with a null/sentinel tenant.
- **AC4** — An out-of-set role value is rejected at the DTO/persistence boundary (unit test) and at
  the DB constraint level (Testcontainers).
- **AC5** — Teacher Assistant's role value exists and can be assigned; nothing in the implementation
  (naming, comments, docs, UI copy) presents its permission boundary as ratified — a reviewer must be
  able to find an explicit PROVISIONAL marker.
- **AC6** — Cross-tenant negative test: tenant A's role-assignment rows are not readable by a tenant B
  actor via id (403/404, never 200-with-filtered-empty).
- **AC7** *(edge case, not explicit in either source but implied)* — a role-assignment record cannot
  simultaneously carry a tenant-scoped staff sub-role and represent Platform Admin — structurally
  impossible or explicitly rejected, since the two scopes are documented as mutually exclusive.

### Server-side enforcement (RBAC-2)
- **AC8** — No mutating endpoint succeeds for Read-only Auditor under any request shape, verified per
  mutating endpoint category, not a sample.
- **AC9** — A staff sub-role lacking a domain's permission receives 403 (not 401, not silent
  200-empty).
- **AC10** — Every authorization check re-verifies the actor's *current* role from the source of
  truth against the resolved tenant context on that request — a check based on a JWT-embedded role
  claim alone, without a live re-read, does not satisfy this (must preserve, not regress, the
  auth-foundation's existing "live re-read every request" property).
- **AC11** — Platform Admin cannot perform a tenant-admin-equivalent mutating action against a
  specific tenant's data through any code path built under this module.
- **AC12** — Cross-tenant negative test: a role valid in tenant A cannot act on tenant B's resource
  merely because the role name matches, for every representative protected endpoint.
- **AC13** — At least one full-filter-chain (Testcontainers) test proves a deny returns HTTP 403
  through Spring Security, never a silently empty/200 response.
- **AC14** *(gap, not addressed in either source)* — a role changed mid-session (demoted, sub-role
  revoked) must be enforced against the new role on the very next request, not a stale token-embedded
  value.
- **AC15** *(gap)* — an authenticated user with no role/sub-role assigned yet must be rejected 403 on
  every protected endpoint, never default-allowed and never a 500 from an unhandled null/absent enum.

### Frontend permission-denied wiring (RBAC-3)
- **AC16** — The permission-denied UI state renders only from a real 401/403 or a session-payload
  role/permission value — never from a client-stored/cached role string.
- **AC17** — The permission-denied component uses `role="alert"`/`aria-live`, implemented once in the
  shared state-component library, reused by every route group.
- **AC18** — Client-side route guards are proven, by test, to be UX convenience only — direct
  navigation to a guarded route without the guard still results in the underlying data call failing
  server-side.
- **AC19** — Playwright coverage for each of 4 role fixtures: an out-of-permission action/route
  renders the shared component driven strictly by 401/403; hidden/disabled nav items still fail
  server-side when reached via direct URL.
- **AC20** — Cross-tenant E2E negative test: a Tenant A Tenant Admin navigating directly to a Tenant B
  resource route is blocked with no flash of Tenant B content.
- **AC21** *(gap)* — a 5xx/network failure must never be misrepresented as a permission-denied state
  by the React Query status-mapping helper — this distinction is not addressed in either source and
  must be explicitly designed (see §11).

---

## 6. Out-of-scope items

- **Impersonation implementation.** Requirements §5 and the issue's scope note both frame this as
  future work — this module must not *preclude* it (Platform Admin's platform-only scope must remain
  extensible to an audited impersonation session later) but does not build the session mechanism,
  audit trail, or UI indicator.
- **Concrete endpoint-level permission matrices per domain.** RBAC-1/2 build the *mechanism* (data
  model + enforcement) and the domain-level matrix (§2.2) for IA purposes; `docs/api` records the
  authoritative endpoint-level matrix per domain as each is contract-reviewed, per
  `docs/api/README.md`.
- **Ratifying the Teacher vs. Teacher Assistant split** — modeling support is in scope; business
  sign-off is not (open question §21.1).
- **Resolving any of the 4 open questions** in `user-roles-and-permissions.md` (§21) — surfaced, not
  resolved, by this plan.
- **Multi-factor authentication and its role-differentiated scope** — belongs to the auth-foundation
  module (ADR-007 §4), not RBAC.
- **Device-limit precedence generalization to other features** — explicitly not confirmed to
  generalize; out of scope to decide here.
- **Frontend work for RBAC-1/RBAC-2** — both explicitly "no direct frontend impact"; frontend is
  RBAC-3's concern only.
- **Backend/database work for RBAC-3** — frontend-only story, consumes RBAC-2's output.
- **Role-assignment mutation endpoints** (create/update a staff member's role) — these belong to
  Module 5 (Staff Management), which depends on Module 3 per the dependency map, not the reverse.
  RBAC-1 supplies the role catalog/data model Module 5 assigns against; it does not itself build the
  "invite staff and assign role" flow.
- **Payment-slip duplicate/suspicious-flag checks, ledger mutation rules, settlement logic** — governed
  by `.claude/rules/payments.md`; RBAC only controls *who may call* approve/reject endpoints, not the
  duplicate-check or ledger-append mechanics (see the matrix/payments-rule tension flagged in §21).
- **A DB-backed, tenant-configurable `permission` table.** Nothing in the source docs asks for
  tenant-configurable permissions; a static, code-level permission matrix (mirroring §2.2) is the
  currently-justified scope (see §21).

---

## 7. Domain model

**Recommendation (see §8/§21 for the mechanism decision and its change-control flag): a single
`role_code` assignment column on `tenant_user`, referencing a new platform-global `role` catalog
table as its one source of truth for the valid role set** — not a `tenant_user_role` join table.
Multi-role-per-user has not been confirmed anywhere in the requirements or backlog; RBAC-1's own
acceptance criteria only require *representing* 12 roles + Anonymous/Public, not multi-role
assignment. Building a join table now would be speculative scope beyond what's asked.

- **`role` catalog table** — platform-global reference data, no `tenant_id` (the requirements
  explicitly call for this: "fixed catalog, platform-global, no tenant_id"). Holds both `PLATFORM`-
  and `TENANT`-scoped role codes, with display/IA metadata (display name, portal route group,
  self-registration flag, provisional flag) the "Staff & Roles" admin screen and role-assignment UI
  need per `user-roles-and-permissions.md` §1-§2 — not just a bare enum with no metadata home.
- **`tenant_user.role_code`** — the per-user assignment, tenant-owned (inherits `tenant_user`'s
  existing `tenant_id`), single-valued.
- **Platform Admin** stays exactly as the auth-foundation branch built it: `platform_admin_user` has
  no role column at all, implicit `PLATFORM_ADMIN`, never shares a table with `tenant_user`. The
  catalog may carry a `PLATFORM_ADMIN` row for display/reference completeness only — no FK
  relationship from `platform_admin_user` to the catalog, preserving the existing table-separation
  decision rather than retrofitting a column to make the catalog "complete."
- **Teacher vs. Teacher Assistant** — both get real, distinct, non-provisional role *values*
  (`TEACHER`, `TEACHER_ASSISTANT`) now. This module models role **identity**, not permission sets;
  the §2.3 permission boundary is a separate, still-open concern that must not block the role value
  from existing so a Tenant Admin can assign it.
- **No first-class `permission`/`role_permission` table in this module.** The backlog's RBAC-1
  database-impact line names only the `role` catalog and the assignment column — a DB-backed
  permission catalog is unconfirmed scope. A static, code-level role→permission matrix (mirroring
  §2.2) is the cheaper, currently-justified approach unless a real requirement for
  tenant-configurable permissions emerges later (flagged as a forward-looking risk in §21, not
  decided here).

---

## 8. Database design

**Two additive Flyway migrations** (numbered relative to the auth-foundation branch's existing
V1–V6 once merged in — confirm actual next-available number at implementation time, do not assume
V7/V8 literally):

### Migration A — platform-global `role` catalog table

```sql
-- Vn__create_role_catalog.sql
CREATE TABLE role (
    code                VARCHAR PRIMARY KEY,
    scope               VARCHAR NOT NULL CHECK (scope IN ('PLATFORM', 'TENANT')),
    display_name        VARCHAR NOT NULL,
    description         VARCHAR NULL,
    portal_route_group  VARCHAR NOT NULL,
    self_registers      BOOLEAN NOT NULL DEFAULT false,
    is_provisional      BOOLEAN NOT NULL DEFAULT false,
    is_active           BOOLEAN NOT NULL DEFAULT true,
    sort_order          INTEGER NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO role (code, scope, display_name, portal_route_group, self_registers, is_provisional, sort_order) VALUES
    ('PLATFORM_ADMIN',      'PLATFORM', 'Platform Admin',      'app/(platform-admin)/', false, false, 0),
    ('TENANT_ADMIN',        'TENANT',   'Tenant Admin',        'app/(tenant-admin)/',   false, false, 10),
    ('FINANCE_STAFF',       'TENANT',   'Finance Staff',       'app/(tenant-admin)/',   false, false, 20),
    ('COURSE_COORDINATOR',  'TENANT',   'Course Coordinator',  'app/(tenant-admin)/',   false, false, 30),
    ('STUDENT_SUPPORT',     'TENANT',   'Student Support',     'app/(tenant-admin)/',   false, false, 40),
    ('CONTENT_MANAGER',     'TENANT',   'Content Manager',     'app/(tenant-admin)/',   false, false, 50),
    ('EXAM_MANAGER',        'TENANT',   'Exam Manager',        'app/(tenant-admin)/',   false, false, 60),
    ('ATTENDANCE_OPERATOR', 'TENANT',   'Attendance Operator', 'app/(tenant-admin)/',   false, false, 70),
    ('READ_ONLY_AUDITOR',   'TENANT',   'Read-only Auditor',   'app/(tenant-admin)/',   false, false, 80),
    ('TEACHER',             'TENANT',   'Teacher',             'app/(teacher)/',        false, false, 90),
    ('TEACHER_ASSISTANT',   'TENANT',   'Teacher Assistant',   'app/(teacher)/',        false, true,  100),
    ('STUDENT',             'TENANT',   'Student',             'app/(student)/',        true,  false, 110);
```

`PLATFORM_ADMIN` row is documentary only (see §7). `TEACHER_ASSISTANT.is_provisional = true` is the
structural marker required by AC5.

### Migration B — repoint `tenant_user`'s role assignment

Widen/repoint `tenant_user`'s role column from the coarse 4-value CHECK enum
(`TENANT_ADMIN`/`STAFF`/`TEACHER`/`STUDENT`) to a `role_code` column with `NOT NULL REFERENCES
role(code)`, replacing the CHECK constraint entirely (mechanism decision — see below).

```sql
-- Vn+1__repoint_tenant_user_role.sql
ALTER TABLE tenant_user RENAME COLUMN role TO role_code;
ALTER TABLE tenant_user DROP CONSTRAINT tenant_user_role_check; -- verify actual name against applied schema first
ALTER TABLE tenant_user
    ADD CONSTRAINT fk_tenant_user_role_code FOREIGN KEY (role_code) REFERENCES role (code);
-- role_code must only ever hold a TENANT-scope code; enforce via application-layer validation
-- and a reviewed/tested invariant (see mechanism-decision note below), since a plain FK alone
-- would also accept the PLATFORM_ADMIN row.

CREATE INDEX idx_tenant_user_tenant_role ON tenant_user (tenant_id, role_code);
```

The new `(tenant_id, role_code)` index supports the "list/filter staff by role" query pattern this
module introduces (the existing `(tenant_id, id)`/`(tenant_id, email)` indexes from `V3` don't cover
it).

### Mechanism decision: CHECK-only enum vs. FK-to-catalog — **flagged for explicit approval, not silently resolved**

The database-architect and solution-architect reviews disagreed here:

- **database-architect's design**: keep `tenant_user.role` a plain `CHECK`-constrained enum (as
  today), with the `role` catalog table existing separately as pure display/reference metadata, no FK
  relationship. Rationale: avoids a cross-table trigger to prevent a `PLATFORM_ADMIN` value leaking
  into `tenant_user`, and is a smaller, lower-risk change. Self-identified risk: the CHECK list and
  the catalog's `TENANT`-scope rows become **two independently maintained sources of truth** that can
  drift if one is updated without the other.
- **solution-architect's design** (adopted above): make `tenant_user.role_code` a genuine FK into
  `role.code`, collapsing this to one source of truth and directly eliminating the drift risk. Traded
  cost: this is a real mechanism change beyond literal "widen the CHECK list," and
  `docs/architecture/authentication-authorization.md`'s banner explicitly lists **"how roles/permissions
  are modeled"** as requiring an approved ADR before implementation — no such ADR currently exists for
  either design.

**Resolution for this plan:** adopt the FK-to-catalog design as the *recommended* direction (it
strictly dominates the CHECK-only design by removing a self-identified drift risk with no added
tenant-isolation cost — see §14), but **do not treat this as authorized for implementation**. Per root
`CLAUDE.md`'s change-control list ("authentication architecture" requires explicit approval) and the
authentication-authorization.md banner, this specific mechanism choice should be confirmed (via an ADR
or explicit sign-off) before the migration is written — this plan documents the recommendation and
its rationale, it does not constitute that approval.

### Migration safety

- **Additive only** — no edits to `V1`–`V6`. `V3__create_tenant_user.sql` is treated as
  already-shared/applied the moment the auth-foundation branch is merged, regardless of whether real
  data exists in any environment yet.
- **CHECK-drop mechanics**: Postgres requires `DROP CONSTRAINT` + `ADD CONSTRAINT` (no `ALTER CHECK`).
  Verify the real auto-generated constraint name against the applied schema before finalizing the
  migration file — do not guess it.
- **No `STAFF`/`TEACHER` backfill mapping is defined by this plan.** No source document says which
  existing generic `STAFF` row should map to which of the 7 new sub-role values, and per the grounding
  no such data exists pre-launch. If any `STAFF` row exists in any environment this migration runs
  against, the migration will (correctly) fail loudly at the constraint-add step — resolve via an
  explicit business decision before that happens, do not add a silent default mapping.
- Once the catalog table has live references, retire a role via `is_active = false`, never a hard
  delete — general hygiene, not a hard rule invented here.

---

## 9. Backend design

All new code lives in `com.lms.identityaccessservice` (once the auth-foundation branch is merged
in), per `.claude/rules/architecture.md`'s package/sub-package structure (`api`/`web`/`service`/
`domain`/`repository`/`config`).

### RBAC-1 (data model)
- `domain.RoleCatalogEntry` — new `@Entity` for the `role` table.
- `repository.RoleCatalogRepository extends JpaRepository<RoleCatalogEntry, String>` — deliberately
  **not** `TenantAwareRepository` (the table holds no `tenant_id`; call this out explicitly in review
  as an intentional exception, not a missed tenancy checklist item).
- `domain.TenantUser` — existing entity from the auth-foundation branch, `role` field repointed to
  `role_code` per §8.
- `api.RoleCatalogEntry` (response DTO, distinct from the JPA entity per `backend/CLAUDE.md`'s "do not
  expose JPA entities directly") + a light query surface for listing assignable roles, anticipatory
  for Module 5's staff-invite screen.

### RBAC-2 (enforcement) — the cross-cutting surface every future domain controller depends on
- `api.PermissionCheckService` — the one contract other domains may depend on directly (e.g.
  `void requirePermission(String permissionCode)`), throwing `AccessDeniedException` on denial so it
  lands on the already-wired `GlobalExceptionHandler` → `403 FORBIDDEN` path (no new error-response
  plumbing needed — confirmed already in place: `ApiErrorCodes.FORBIDDEN` and an
  `AccessDeniedException` handler both already exist in `com.lms.common`).
- `service.PermissionCheckServiceImpl` — reads `AuthenticatedPrincipalHolder`/`TenantContextHolder`
  (never re-derives tenant/actor identity itself), evaluates role → permission against the static
  matrix from §2.2.
- `config` — the method-security wiring. **Mechanism choice flagged as open, not yet decided:**
  - (i) `@EnableMethodSecurity` + `@PreAuthorize` SpEL calling a custom `PermissionEvaluator` bean, or
  - (ii) a custom `api`-exported annotation (e.g. `@RequiresPermission("courses.create")`) + a
    `config`-local `@Aspect`/interceptor calling `PermissionCheckService`.

  Either way, whatever annotation/expression a business-domain controller writes becomes part of
  `identity-access-service`'s public contract and belongs in its `api` package; the aspect/interceptor
  implementation belongs in `config`, matching where `JwtAuthenticationFilter`/
  `SecurityFilterChainConfig` already live. **Recommendation:** favor option (ii) — a purpose-built
  annotation reads more directly as "this endpoint requires permission X" in review than a SpEL
  expression string, and keeps the security-reviewer's default-deny goal (§15) easier to statically
  verify (a startup scan can assert every `@RestController` method carries either
  `@RequiresPermission` or an explicit `@PubliclyAccessible` marker). This is a recommendation for the
  implementer to confirm, not a locked decision.

**How a future business-domain controller (e.g. course-management, Module 8) consumes this** without
violating "only depend on another module's `api` package": it imports only
`identityaccessservice.api.PermissionCheckService` and/or the `api`-exported permission annotation,
applied to its own `web`/`service` code — it must never import `identityaccessservice.domain.*` or
`.repository.*`. Same boundary already established for `TenantAwareRepository`/`TenantContextHolder`
living in `com.lms.common`.

### Implementation order (see §20 for the full sequenced list)

RBAC-1 (schema + catalog) → RBAC-2 (`PermissionCheckService` + enforcement mechanism + fixture-controller
tests) → RBAC-3 (frontend wiring, hard-blocked on RBAC-2).

---

## 10. API contract

Per `docs/api/README.md`, contract files are meant to be written by the `review-api-contract` skill
before implementation begins — this plan sketches the minimal surface, not a finalized contract.

**RBAC-2 introduces no REST endpoints of its own.** It is a cross-cutting enforcement mechanism
(`PermissionCheckService` + method-security aspect), consumed internally by other domains' controllers
— not called directly over HTTP.

**RBAC-1's only plausible new REST surface**: a read-only role catalog listing endpoint (e.g.
`GET /api/v1/roles`, tenant-scope-filtered — `TENANT`-scope rows for a tenant-facing "assign role"
dropdown, or unfiltered for a Platform Admin catalog view), backing the "Staff & Roles" admin screen's
future dropdown. Response would use the existing `ApiResponse<T>` envelope and existing error codes
(`UNAUTHENTICATED`/`FORBIDDEN`) — no new error codes needed, consistent with the identity-access-service
auth contract's existing conventions.

**Explicitly out of scope for this module's contract**: any role-*assignment* mutation endpoint
(`POST`/`PATCH` to change a staff member's role) — that belongs to Module 5 (Staff Management) per the
dependency map (M3 → M5), which will call into RBAC-1's catalog as a dependency, not the reverse.

**Recommendation:** run `review-api-contract` against this minimal surface before implementation
starts, writing `docs/api/identity-access-service.md`'s RBAC section (that file already exists from
the auth-foundation branch's AUTH-1/2/3 contract — this module extends it, doesn't replace it) —
following the process this repo's own docs flag as having been skipped once already for the
Authentication module.

---

## 11. Frontend screens

RBAC-3 has no new page-level screen — it is cross-cutting plumbing plus one incremental extension to
an existing shared component.

**a. React Query status-mapping helper — new, not yet built.** Recommend
`frontend/src/lib/api/query-status.ts` (co-located with the existing `error.ts`/`client.ts`/`types.ts`,
not a new `hooks/` directory — keeps this an extension of the existing `lib/api` layer's
responsibility). A pure function narrows `ApiClientError` into `{ kind: "unauthenticated" |
"forbidden" | "error" }` (401 → `unauthenticated`, 403 → `forbidden`, everything else, including
unrecognized codes, → generic `error` — **never** default an unrecognized failure to `forbidden`, per
AC21). A thin wrapper component (e.g. `<QueryStateBoundary>`) dispatches to `LoadingState` /
`EmptyState` / `ErrorState` / `PermissionDeniedState` based on the mapped kind, so no individual page
hand-rolls this branching and loses the accessibility attributes in the process.

**b. 401 vs. 403 — different UX, explicit design:**
- **401 → redirect to `(auth)/login`.** No valid session; nothing useful to render in place.
- **403 → render `PermissionDeniedState` in place.** Backend positively identified the actor and
  rejected the action/route — the user stays on the page with context.
- Trigger signal is strictly `ApiClientError.status`/`code` from a real response — route-group guards
  may redirect proactively on "no session token present at all" as a UX convenience, but the
  authoritative 401/403 classification always comes from the mapping helper reacting to a real
  backend response.

**c. `PermissionDeniedState` — one proposed incremental change.** Add an **optional** `action` prop
(e.g. `{ label, onClick }`, mirroring `EmptyState`'s action shape for consistency) so call sites that
need a "go back"/"contact your administrator" affordance don't reimplement it per page — omit any
default action, consistent with the component's existing "never fabricate copy" doc comment. Do
**not** add a `role`/`requiredPermission` prop — that would tempt a caller into computing denial
client-side.

**d. Route-group guard component — not yet built.** A lightweight per-route-group check for "is there
apparently a session" (redirect to login before render, avoiding a flash of wrong content) — never a
role/permission check. This keeps the guard from becoming "business-authoritative security logic,"
which `frontend/CLAUDE.md`'s baseline rule forbids outright.

---

## 12. Validation rules

No forms are in scope for this module (no Zod schema, no React Hook Form usage) — RBAC-3's only
"validation" concept is the 401/403 response-shape classification in the status-mapping helper (§11),
which must not be described anywhere as "validating permissions," since that would misstate the
frontend as an authority it isn't.

**Explicit constraint carried into implementation and tests:** any client-side route guard is
non-authoritative convenience only. Playwright tests must assert that a hidden/disabled nav item's
destination route, reached directly (typed URL), still produces a server-verified 403 →
`PermissionDeniedState` — a test proving only that the nav link is hidden is not sufficient evidence,
mirroring the tenancy rules' standard for what counts as verified isolation.

---

## 13. Error cases

### Backend (security-relevant)

| # | Scenario | Outcome | Code |
|---|---|---|---|
| 1 | No/invalid/expired credential | 401 | `UNAUTHENTICATED` |
| 2 | Authenticated, role has no permission for the action | 403 | `FORBIDDEN` |
| 3 | Authenticated, correct role, resource belongs to a different tenant | 403 or 404 (domain-module decision — see §21) | `FORBIDDEN` or `NOT_FOUND` |
| 4 | Read-only Auditor (or any role) attempts a structurally-forbidden mutation | 403 | `FORBIDDEN` — identical response shape to #2, no distinguishing signal |
| 5 | Platform Admin attempts a tenant-scoped operational action without an impersonation session | 403 | `FORBIDDEN` — must not silently succeed via a null-tenant bypass (see §15) |
| 6 | Role changed mid-session (demoted) between token issuance and this request | Enforced against the *current* DB-read role — outcome is whichever of #2/#4/#5 applies given the new role | n/a |
| 7 | Authenticated user with no role assigned yet | 403, never default-allow, never a 500 from an unhandled absent value | `FORBIDDEN` |

**No new error codes are introduced.** `ApiErrorCodes.FORBIDDEN` is sufficient for every
"authenticated but insufficient permission" case (#2, #4, #5) — introducing granular codes (e.g.
`FORBIDDEN_WRONG_TENANT`, `FORBIDDEN_READONLY_ROLE`) would leak which specific check failed to a
client probing the boundary, the same information-disclosure concern ADR-007 already avoided for
login failures by keeping `INVALID_CREDENTIALS` deliberately generic. Server-side logging should still
capture the specific denial reason for support/ops investigation — the client-facing response stays
generic, the log does not need to.

### Frontend (UI-facing)

1. 401 on page/route load → redirect to login, no `PermissionDeniedState` render.
2. 403 on page/route load → `PermissionDeniedState` renders in place.
3. 403 on an in-page mutating action (stale UI exposed a control it shouldn't have) → inline/toast
   error via `aria-live="assertive"`/`role="alert"`, not a full-page swap — the user is mid-flow on a
   page they otherwise have access to.
4. Generic/other error (network failure, 500, unrecognized code) → existing `ErrorState` component,
   never `PermissionDeniedState` (AC21) — a 500 must never look like a permission problem.
5. Cross-tenant direct-URL access → same as #2, with the added requirement that no flash of the
   wrong-tenant's content occurs before the blocked state renders.

---

## 14. Tenant-isolation rules

RBAC-2's role/permission check and `TenantAwareRepository`'s resource-level tenant scoping are **two
independent, both-mandatory layers** — neither substitutes for the other:

- **Role/permission check** ("does this role have this capability") — answered by
  `PermissionCheckService`, tenant-agnostic in itself, reading tenant identity only from the
  already-resolved `AuthenticatedPrincipalHolder`/`TenantContextHolder` (never re-deriving it from a
  path/query/body parameter, header, or hidden field — the single most common isolation-bypass
  vector).
- **Resource-level tenant scoping** ("is this specific row this tenant's") — answered exclusively by
  `TenantAwareRepository` at the data-access layer, per ADR-006.

A permission check passing is not evidence of tenant isolation, and a `TenantAwareRepository`-scoped
query is not evidence of authorization — reviewers must verify both independently. The concrete
failure mode to test for: a controller correctly gated by a permission check that then fetches a
resource via a method bypassing `TenantAwareRepository` (raw `EntityManager`, hand-written JPQL, or a
foreign domain's repository reached into directly) — permission passes, tenant isolation silently
doesn't.

**Platform Admin edge case (currently unbuilt anywhere, must be an explicit test case, not an
incidental behavior):** `AuthenticatedPrincipal.tenantId()` is `null` for Platform Admin. A
tenant-scoped check must never do something equivalent to `principal.tenantId == null ||
principal.tenantId == resource.tenantId` — that would accidentally treat "no tenant" as "matches every
tenant." RBAC-2 needs an explicit branch (reject for tenant-scoped endpoints absent an audited
impersonation session, or route to a distinctly-named cross-tenant method per ADR-006).

**Reviewer checklist specific to this module** (applying `.claude/rules/tenancy.md`'s general
checklist to RBAC-2's actual surface):
1. Cross-tenant negative test per protected endpoint category, not just "role X is denied" but "a
   principal with the correct role from tenant A is denied/404'd when addressing tenant B's resource
   id."
2. No permission-check method accepts a caller-supplied `tenant_id` — any signature needing tenant
   context derives it from `principal.tenantId`, never as an independent parameter.
3. No new endpoint (including RBAC-1's own role-catalog listing) skips the shared tenant-context
   resolution mechanism.
4. Bulk/reporting surfaces (e.g. "list all staff and their roles for this tenant") are tenant-scoped
   like any list endpoint.

**The catalog table (`role`) itself needs no tenant-isolation work** — it holds zero tenant-owned
rows, only fixed platform-global reference data (`code` PK is sufficient uniqueness). Do not apply
row-level tenant filtering to it; that would be a misapplication of the rule, not a missed requirement.

---

## 15. Security rules

**Default-deny posture (recommended requirement, not left as a testing-only concern).** The
enforcement mechanism must treat "no explicit grant" as forbidden. Concretely: every controller method
must require an explicit permission annotation to be reachable at all — no code path where a method
lacking an authorization annotation is still callable. Favor a startup-time verification (scan all
`@RestController` methods, fail fast if one has neither a permission annotation nor an explicit public
marker) over a purely opt-in convention a reviewer must remember to check.

**Read-only Auditor's "zero mutating path, ever" must be structural, not just tested.** A passing test
suite proves the *current* matrix has no mutation grant for this role; it does not prevent a future
developer from adding one. Recommend: model permissions so mutating actions belong to an enumerated
write-class category, and make the role-to-permission mapping mechanism reject (at role-definition
time, ideally, or at minimum via a startup assertion) any attempt to grant a write-class permission to
a role flagged read-only — turning "auditor writes" into a build-breaking condition, not a
code-review miss.

**Platform Admin scope leakage must be structurally prevented.** The permission-check mechanism must
treat Platform Admin as a role whose permission set is disjoint from tenant-operational permissions —
not a superset/wildcard "admin" role that happens to pass every tenant-scoped check. Covered by the
null-tenant-bypass test named in §14.

**Secrets/credentials.** This module introduces no new secrets. Confirm during implementation review
that no role/permission test fixture embeds a real JWT signing secret or real user/tenant data, and
that any Flyway seed data (the `role` catalog inserts) contains no PII — names/descriptions only.

---

## 16. Audit requirements

**Recommendation, not an assumed mandate:** role/sub-role assignment changes should be audit-logged,
even though `.claude/rules/security.md`'s canonical audit-required action list (price changes, payment
approvals, device resets, access/expiry extensions, reactivation approvals, content deletions,
settlement changes, impersonation start/end) does not name role changes explicitly. Rationale: a role
change is a privilege-escalation-relevant event (e.g. promoting Student Support to Finance Staff
grants payment-approval capability) sharing the same risk profile as canonical-list items, and this
codebase's existing precedent (device reset, per `authentication-authorization.md` §7) extends
audit-logging to a narrower action than this. **If adopted**, an entry needs: actor id, tenant id,
target user id, before/after role values, timestamp (mirroring the device-reset audit shape).

**Sequencing question, explicitly not resolved here:** this module should **not** build its own
audit-log write path or table. Per `.claude/rules/architecture.md`'s cross-module rules,
`audit-log-management` is its own domain and other modules should be event *publishers*, not direct
writers into another module's table. Whether `AUDIT-1`'s schema (pulled forward per the release plan
into Wave 1) is actually available by the time RBAC-2's role-assignment path ships is not confirmed by
this plan — recommend RBAC-2 publish a `RoleAssignmentChangedEvent` domain event regardless (so no
code changes are needed once a consumer exists), and flag the "is `audit-log-management` a hard
prerequisite or a follow-up" question to whoever owns module sequencing, rather than assuming an
answer.

---

## 17. Payment impact

**None directly.** RBAC-1/2/3 build the role/permission mechanism only; no payment, ledger, or
settlement logic is touched, and no `payment-ledger-specialist` review was needed for this module's
own scope.

**However, a documentation contradiction was found and must be flagged, not silently resolved:** the
domain-level matrix (§2.2, "Finance & expenses" row) grants Institute Owner and Finance Staff `D`
(delete), which is in direct tension with `.claude/rules/payments.md` §4 ("A ledger entry is
append-only… never `UPDATE` or `DELETE`") and root `CLAUDE.md`'s "Never delete financial history." If
"Finance & expenses" refers to ledger/financial-history rows, the literal `D` cannot be implemented as
a real delete without violating a change-controlled payment rule — it may instead mean deleting a
draft/unposted expense record before it becomes a ledger entry, but the requirements doc doesn't make
that distinction. Similarly, "Payments / slips" grants `E` (edit) to the same two roles, which is
plausible only if scoped to pre-terminal-state edits (e.g. a `SUBMITTED` slip's metadata before
review) given `payments.md` §1-§2's immutability/one-directional-state rules. **This module's
permission-check mechanism must not authorize a generic "Finance Staff can E on Payments" grant that a
later payments-module endpoint could use to bypass ledger immutability** — resolving the exact scoping
of these two matrix cells is the payments module's own planning responsibility, not RBAC-1/2's, and is
flagged here so it isn't silently encoded incorrectly when `docs/api` records the payments domain's
endpoint-level matrix later.

---

## 18. Tests

### Backend unit tests
- Fixed role-set validation: `Role`/catalog enum-equivalent count and values match
  `user-roles-and-permissions.md` §1 exactly; a request DTO carrying an out-of-set role value is
  rejected at deserialization (400), independent of the DB-level constraint test.
- Platform-scope vs. tenant-scope invariant: a role helper returns "platform-scoped" only for
  `PLATFORM_ADMIN`.
- **Permission-matrix-driven table test** — the core RBAC-2 obligation. Transcribe §2.2 into a JUnit 5
  `@ParameterizedTest`/`@MethodSource`, one row per (domain area, staff sub-role, action) triple
  (VIEW/CREATE_EDIT/DELETE/APPROVE), covering **every** matrix cell including every blank (`—`) as an
  explicit negative assertion — not just the populated grants. Transcribe by hand from §2.2 (never
  derive the test data from the production implementation itself, which would make the test
  tautological). Read-only Auditor gets a dedicated exhaustive test iterating every domain area ×
  mutating action in one pass, on top of its matrix rows. Teacher/Teacher Assistant (§2.3) gets a
  second, separately-labeled `PROVISIONAL` parameterized test so a green suite is never read as
  ratification.

### Backend integration/Testcontainers tests
(Extend the existing `AbstractIntegrationTest`, reusing its `TENANT_A`/`TENANT_B` constants and
`withTenant(...)` helper — no new container wiring needed.)
- Role/`tenant_user` persistence tenant-scoped, mirroring the existing
  `TenantAwareRepositoryFixtureIntegrationTest` pattern.
- CHECK/FK constraint rejection at the DB layer via a raw `jdbcTemplate` insert bypassing the JPA
  mapping (proves schema-level enforcement, not just application-code enforcement — both required per
  `.claude/rules/backend.md`'s schema-enforced-invariants guidance).
- Platform Admin has no tenant association at the schema level — assert absence is intentional, not a
  nullable `tenant_id` on an otherwise tenant-owned table.
- **Mandatory cross-tenant negative test**: Tenant A's role-assignment row not readable by Tenant B via
  id; a list/search endpoint (once one exists) returns empty, not an existence-leaking error, for
  another tenant's rows.
- **RBAC-2's real-protected-endpoint obligation**, given no Module 4+ controller exists yet: add a
  **test-only fixture controller** (mirroring the existing `ExceptionHandlerTestController` pattern) in
  `identity-access-service`'s test tree, shaped like a real domain controller (VIEW/CREATE_EDIT/
  DELETE/APPROVE endpoints per fixture "domain area"), gated by whichever real RBAC-2 mechanism is
  chosen (§9). Test via `restTemplate` with a real authenticated principal (not a mocked
  `SecurityContext`, for the same reason `TenantAwareRepositoryFixtureIntegrationTest` uses real
  Postgres instead of a mock): Read-only Auditor denied on every mutating fixture endpoint; a staff
  sub-role without the relevant permission denied; a staff sub-role *with* the permission allowed
  (positive control); Platform Admin denied on a tenant-scoped mutating fixture endpoint absent
  impersonation.
- **Mandatory cross-tenant negative test for RBAC-2** (the broadest matrix of any RBAC story per the
  backlog): two staff users with the *same* role name, one per tenant — prove Tenant A's holder of that
  role cannot act on a Tenant B resource id via the fixture endpoint, at least one deny-path test per
  staff sub-role, driven by `@ParameterizedTest` rather than hand-duplicated methods.
- **Explicit flag for reviewers and future modules**: this fixture-controller suite proves the
  *mechanism* works; it does not prove any specific Module 4+ controller is correctly annotated. Every
  future module adding its first protected controller must add its own RBAC-2-pattern test (deny
  returns 403 through the full filter chain, its own matrix rows, its own cross-tenant negative test)
  as part of that module's definition of done — record this explicitly in the RBAC-2 PR description so
  it isn't lost by the time Module 4 starts.

### Frontend Playwright tests
(Written/landed alongside RBAC-3's actual wiring — not authored speculatively before a real protected
route exists, since a component-only harness test "would not prove anything about real app behavior,"
per the existing `shared-states.spec.ts` precedent in this repo.)
- 4 role fixtures (at minimum one from each distinct scope class: Platform Admin, Tenant Admin, a
  narrow-grant staff sub-role such as Read-only Auditor, and Student/Teacher), each attempting an
  out-of-permission action/route, asserting via network interception that the triggering response was
  a real 401/403 (not just DOM inspection), and that the shared `PermissionDeniedState` renders with no
  mutating side effect having occurred.
- Hidden/disabled nav still fails server-side via direct URL navigation, bypassing the nav entirely.
- **Mandatory cross-tenant E2E negative test**: Tenant A's Tenant Admin navigating directly to a
  Tenant B resource route — blocked state renders (403 or 404, matching whatever the backend
  contract settles per §13), with no flash of Tenant B content, asserted via the earliest paint check
  (`domcontentloaded`), not just eventual DOM state.
- Accessibility assertions folded into each case (role="alert" present, content reachable via the
  accessibility tree).

### Test data/fixture conventions
Two-tenant minimum in every suite touching a tenant-owned table (reuse existing `TENANT_A`/`TENANT_B`
constants). A reusable backend role-fixture-seeding helper (mirroring the existing
`TestFixtureWidget`/`TestFixtureWidgetRepository` precedent), seeding via direct `jdbcTemplate` inserts
(not through the API under test) for Tenant Admin, each of the 7 staff sub-roles, Teacher, Teacher
Assistant, Student per tenant, plus a tenant-less Platform Admin — built once here, reused by every
subsequent module. Frontend role fixtures via Playwright `storageState`/a fixture-login helper once a
real auth flow exists — never fake a session by writing a role string into `localStorage`.

### Known testability gaps (see also §21)
RBAC-2 has nothing real to enforce against yet (fixture-controller approach above is a proxy, not
proof any real controller is correct — recurring obligation, not a one-time completion). RBAC-3 cannot
be tested fully end-to-end until AUTH-2 and RBAC-2 both ship at least one real protected page — its
Playwright suite should land in the same PR/story that performs the actual first wiring, not be
authored speculatively now. "Audit log: V (own-area actions)" scoped-view permission (§2.2) can't be
meaningfully tested until the audit-log domain exists — deferred, cross-referenced back to this matrix
row when that module is built.

---

## 19. Documentation changes

- `docs/architecture/` — new role/permission data-model entry (the `role` catalog + `tenant_user`
  repointing decision), once the mechanism question in §8 is explicitly resolved/approved.
- `docs/api/identity-access-service.md` — extend the existing AUTH contract file (already present on
  the auth-foundation branch) with RBAC-1's role-catalog read endpoint, if built; record that
  per-domain endpoint-level permission matrices land progressively in their own domain's contract file
  as each is reviewed, not duplicated here.
- `docs/ui-ux/` — record the shared permission-denied component pattern and the 401-vs-403
  redirect/render-in-place decision (§11), if not already captured.
- `docs/adr/` — if the mechanism decision in §8 (CHECK-only vs. FK-to-catalog) is approved, record it
  as a new ADR per the authentication-authorization.md change-control banner, rather than only as a
  paragraph in this plan.
- **Do not** finalize `docs/requirements/user-roles-and-permissions.md`'s open questions (§21) as part
  of this module's documentation step — those remain open until a separate business decision resolves
  them.

---

## 20. Implementation order

1. **Precondition**: merge/rebase `feature/authentication-Foundation` (AUTH-1/2/3) into the branch
   this module builds from. Do not reimplement auth-foundation pieces from scratch.
2. **Resolve §8's mechanism question** (CHECK-only vs. FK-to-catalog) explicitly before writing any
   RBAC-1 migration, given the change-control flag on "how roles/permissions are modeled."
3. **RBAC-1**: `role` catalog migration + `tenant_user` repointing migration; `RoleCatalogEntry`
   entity/repository; cross-tenant negative test on role-assignment rows (AC6).
4. **RBAC-2**: `PermissionCheckService` (`api`), enforcement mechanism (`config`, per the §9
   recommendation), matrix-driven unit tests (§18), fixture-controller integration tests (§18) since no
   real Module 4+ controller exists yet.
5. **RBAC-3**: React Query status-mapping helper, route-group guard, `PermissionDeniedState` `action`
   prop addition, Playwright suite — landed alongside the first real protected page this can wire
   against.
6. **Standing obligation, not a one-time step**: every Module 4+ story that adds its first protected
   controller applies RBAC-2's mechanism to it and adds its own deny-path + matrix + cross-tenant tests
   as part of that module's own definition of done.

---

## 21. Risks and unresolved decisions

### Open questions from `user-roles-and-permissions.md` (verbatim, not resolved by this plan)
1. Teacher Assistant permission boundary (§2.3) — PROVISIONAL, needs sign-off.
2. Whether Finance Staff or Institute Owner (or both) is the correct approver for reactivation
   requests — not fully resolved by `docs/ui-ux/user-journeys.md` Journey 3.
3. Whether Course Coordinator's course-approval authority requires a second approver for
   high-value/published courses — not specified anywhere in current material.
4. Whether tenant self-registration (Student row, §2.1) is public or invite-only — same open question
   tracked in `docs/ui-ux/user-journeys.md`.

### Genuine specialist disagreements, resolved above rather than silently picked
- **§8**: database-architect's CHECK-only design vs. solution-architect's FK-to-catalog design —
  resolved by recommending the FK design but flagging it needs explicit ADR-level approval before
  implementation, per the authentication-authorization.md change-control banner on "how
  roles/permissions are modeled."
- **§9**: `@PreAuthorize`+`PermissionEvaluator` vs. custom annotation+AOP aspect — resolved by leaning
  toward the custom-annotation approach for review-clarity and static-verifiability, flagged as a
  recommendation for the implementer to confirm, not locked.

### Contradiction found, not resolved here (see §17)
The §2.2 permission matrix's "Finance & expenses" `D` (delete) and "Payments / slips" `E` (edit) cells
are in tension with `.claude/rules/payments.md`'s append-only/immutability rules. Needs clarification
from whoever owns the payments module's `docs/api` matrix work before those cells are encoded as real
endpoints.

### Other gaps found, not addressed by either source document
- **Role change mid-session behavior** (AC14) — must be explicitly decided and tested that a role
  change takes effect on the very next request, consistent with the auth-foundation's existing
  "live re-read every request" pattern, not left implicit.
- **No-role-yet state** (AC15) — a staff account existing before a sub-role is assigned must reject
  every protected endpoint (403), not default-allow or 500.
- **Error-type conflation risk on the frontend** (AC21) — the status-mapping helper must not
  misroute a 5xx/network failure into the permission-denied UI state; not addressed in either source,
  explicitly designed in §11/§13.
- **RBAC-2's scope is open-ended by design** — "apply to every controller from Module 4 onward" can
  never be fully "done" inside this module alone; its own acceptance criteria cover only the mechanism
  + whatever endpoints exist at its implementation time (§18's fixture-controller caveat).
- **403 vs. 404 for cross-tenant resource access** is not decided by any source document and is
  genuinely open — `.claude/rules/tenancy.md` permits either as long as existence isn't leaked via a
  200; whichever domain modules pick, it must be applied consistently and is explicitly *not*
  standardized by RBAC-2 itself (§13 row 3).
- **Whether a DB-backed, tenant-configurable `permission` table will eventually be needed** — nothing
  in current requirements asks for it; flagged as a forward-looking scalability question only, not a
  present gap (§7).
- **Structural (not just tested) guarantees for "Read-only Auditor never mutates" and "Platform Admin
  never tenant-bypasses"** are recommended in §15 but the exact mechanism (startup-time scan,
  role-definition-time validation, or an equivalent) is not yet chosen — needs a concrete decision
  during RBAC-2 implementation.
- **Audit-logging for role changes** is a recommendation (§16), not a settled requirement — if the
  implementation proceeds without it, that should be a visible, explicit decision recorded in the PR
  description, not a silent omission. Its sequencing relative to `AUDIT-1`/`audit-log-management`'s
  actual availability is also unresolved.
