# MVP-004 — Tenant Management — Module Plan

**GitHub issue:** [#4 — \[MVP\] Module 4: Tenant management](https://github.com/mohanranaweera/lms-saas-platform/issues/4)
**Branch:** `feature/tenant-management`
**Backlog source:** `docs/planning/product-backlog.md`, MODULE 4 (stories `TEN-1`, `TEN-2`, `TEN-3`, lines 234–296)
**Spec source:** `docs/requirements/specifications/01-tenant-onboarding.md`

This plan was produced by delegating to six specialist agents in parallel (product-requirements-analyst,
solution-architect, database-architect, security-reviewer, qa-test-engineer, ui-ux-reviewer), each grounded
in the existing ADRs, architecture docs, backlog, and current repository state, then reconciled into one
document. **`payment-ledger-specialist` was intentionally not used** — this module's "Payment impact" is
`None` per the backlog (`TEN-1`/`TEN-2`/`TEN-3` field 11), so there is nothing payment/ledger-shaped to
review.

This is a **plan only** — no application files were created or edited. Several genuine gaps/contradictions
surfaced independently by multiple agents are flagged explicitly below as **open decisions**, not resolved.
Per root `CLAUDE.md`, this plan does not invent unresolved business decisions.

**Update (2026-08-08):** the tenant `status` enum contradiction (§21 item 1) was resolved by explicit
approval prior to implementation. During `implement-backend`, the `plan_id` and registration/profile-data
modeling questions (§21 items 2–3) were also resolved as part of making `TEN-1` actually buildable — see
§21 for each decision's rationale. None of the three is a `CLAUDE.md` change-controlled area, so none
required an ADR. Backend implementation for `TEN-1` (registration) plus the unwired `TEN-2`/`TEN-3` pieces
described in §20 is complete and passed a four-agent review (security, database, architecture, test
quality) with no Critical/High defects remaining — see §21's updated risk list for what's still open.

---

## 1. Business goal

Let a prospective institute become an active, isolated tenant on the platform under exclusive Platform
Admin control, and let every subsequent request resolve unambiguously to that one tenant. Concretely: (a)
capture a registration application without granting it any access, (b) give Platform Admin sole authority
to approve/reject/suspend/cancel that tenant with a full audit trail, and (c) make tenant identity
resolution at the edge (subdomain → tenant context) the single trusted mechanism every other module
depends on. This is the foundational module — nothing tenant-owned can exist correctly until this is
right, which is why the release plan pulls `TEN-1`/`TEN-3` into Wave 0 ahead of their literal module
number (`docs/planning/mvp-release-plan.md`).

## 2. Roles and permissions

| Role | Involvement |
|---|---|
| **Anonymous / Public (prospective institute)** | Submits the registration form only (`app/(public)/`). No login, no tenant context yet. |
| **Platform Admin** | Sole role that can view the tenant list/approval queue, approve/reject pending tenants, and suspend/cancel active tenants. Platform-scoped, cross-tenant by design; no tenant-scoped role has an equivalent (`app/(platform-admin)/`). |
| **Tenant Admin / Institute Owner** | Created *as a result of* approval, not a self-provisioning actor. Can (in a later module) read/edit their own tenant's profile only — must be rejected 403/404 on any other tenant's data. Not itself provisioned with credentials by this module (that's `identity-access-service`'s job once it exists). |
| **All other tenant-scoped roles** (staff, teacher, student) | Not involved in this module; they only become reachable once a tenant is approved and `TEN-3` resolves them into that tenant's context. |

Per `docs/requirements/user-roles-and-permissions.md` §4: Platform Admin permissions are platform-scoped by
default and do **not** implicitly grant tenant-admin-equivalent access to a specific tenant without an
explicit, audited impersonation flow — out of scope here.

## 3. Preconditions

- `identity-access-service` (`AUTH-1`/`AUTH-2`) and `RBAC-1`/`RBAC-2` must exist for "Platform Admin" to be
  an authenticated, verifiable role server-side — this is a **hard precondition for `TEN-2`'s live
  endpoints and `TEN-3`'s actual filter chain**, and it does not exist in the codebase today (see §20
  Implementation order, §21 Risks).
- `AUDIT-1` (audit log schema) is a soft precondition for `TEN-2`'s "exactly one audit row" acceptance
  criterion — also does not exist today.
- No conflicting existing tenant record for the registrant — but the uniqueness *scope* itself is an open
  decision (§21), so this precondition is not fully specified.
- Platform Admin is authenticated with a platform-scoped role before reaching the approval queue.

## 4. User flows

### Normal flow
1. Prospective institute submits registration via the public entry point: institute profile, contact info,
   requested plan.
2. Backend creates a `tenant` row in a pending-approval state. No status/id field is client-writable beyond
   what the DTO explicitly allow-lists.
3. Platform Admin views `Platform Admin > Tenants > Tenant List`, filterable by status; every row shows the
   tenant name next to any action.
4. Platform Admin opens `Tenant Approval`, reviews profile/contact/plan.
5. Platform Admin approves: status flips atomically together with provisioning of default branding/plan
   config, and exactly one audit row is written (actor, tenant, before/after status).
6. Tenant Admin (Institute Owner) account is provisioned/notified and can log in via the tenant-specific
   subdomain login page.
7. From this point, every request against that subdomain resolves tenant identity exactly once at the edge
   (`TEN-3`) and propagates it via request-scoped context to all downstream modules.

### Alternative flows
- **Rejection**: tenant stays non-active, no login path provisioned, audit row written. Rejection-reason
  capture and applicant notification are **unspecified** (§21).
- **Suspension of an active tenant**: audit row written; must **immediately** affect login/access — any
  already-issued session/token for that tenant must be rejected on the next request, not just at future
  login (see §15 Security rules).
- **Cancellation of a tenant**: same authorization/audit pattern. Whether cancellation is ever reversible is
  **unspecified anywhere** (§21).
- **Duplicate/conflicting registration** (same subdomain twice): DB-level `UNIQUE` on `subdomain` rejects
  the second insert at minimum, but the **user-facing behavior is an open decision** (§21) — do not
  silently pick "generic 409" vs. "an application for this name is already pending."
- **Non-admin reaching the approval queue**: rejected server-side (403) regardless of client-side
  navigation/UI state.
- **Cross-tenant access by Tenant Admin**: Tenant Admin of tenant A requesting/modifying tenant B's
  profile/config/plan/status → 403/404.
- **Unresolvable subdomain at request time**: resolution fails safely, explicitly **no fallback to another
  tenant**; frontend renders neutral platform-default branding (never a cached or another tenant's
  branding, per `.claude/rules/ui-ux.md` §2).
- **Manipulated tenant claim**: a request against tenant A's subdomain carries a header/param/body field
  claiming tenant B's id — resolution must still resolve to tenant A; this is the mandatory, foundational
  `TEN-3` negative test.
- **Partial provisioning failure**: if approval's status-flip + default-config provisioning partially
  fails, the whole transaction rolls back — a tenant must never end up `active` with no default config, or
  with an audit row that doesn't match reality.

## 5. Acceptance criteria

Reconciled from `TEN-1`/`TEN-2`/`TEN-3`'s own acceptance-criteria fields plus
`docs/requirements/specifications/01-tenant-onboarding.md` §8, de-duplicated.

**Registration**
1. A prospective institute's submission creates a `tenant` row in a pending-approval state with
   profile/contact/requested-plan data captured; no other status is reachable via this endpoint.
2. The registration DTO never accepts a client-supplied `tenant_id`, `status`, or `approved`/`active` flag
   — these are exclusively backend-set, enforced at the service layer even if the DTO happens to be
   well-formed (defense in depth, not just DTO shape).
3. `tenant` is platform-level: no `tenant_id` column on itself, does not share a table with any
   tenant-owned entity, and its repository is a plain `JpaRepository`, never `TenantAwareRepository`.
4. `subdomain` has a DB-level `UNIQUE NOT NULL` constraint (the one legitimate global-unique case in this
   codebase); `status` is CHECK-constrained to `pending_approval | trial | active | suspended | cancelled |
   rejected` (resolved 2026-08-08, see §21 item 1).
5. A duplicate subdomain/name registration attempt is rejected at the DB level (constraint-level, not just
   a check-then-insert race-prone application check — see §15 item 2).
6. Missing/invalid required fields fail server-side validation with field-level errors — client-side
   (Zod) validation alone is never sufficient.

**Approval / status workflow**
7. A Platform Admin's approval flips status atomically together with provisioning of default
   branding/plan config, and exactly one audit row records the transition (actor, tenant, before/after
   status, timestamp).
8. A Platform Admin's rejection changes status accordingly, provisions no login path, and writes exactly
   one audit row.
9. A Platform Admin's suspension or cancellation of an active tenant changes status, writes exactly one
   audit row, and immediately invalidates any live session/token for that tenant.
10. A non-Platform-Admin actor is rejected server-side (403) on every list/detail/mutating endpoint in the
    approval queue, regardless of client-side UI state.
11. A Tenant Admin of tenant A attempting to read/modify tenant B's profile/config/plan/status is rejected
    403/404.
12. Every Tenant List row shows the tenant name/identifier; no approve/suspend/reject/cancel action is
    submittable without the target tenant visibly named next to the control.
13. "No tenants awaiting approval" (true zero-data) is a distinct empty state from "no tenants match the
    current filter."
14. The tenant-list/approval-queue query uses an explicitly named cross-tenant bypass method (e.g.
    `findAllPendingTenantsForPlatformApproval`), never a normal `TenantAwareRepository`-scoped finder.
15. A partially-failed approval-provisioning step rolls back the entire operation — no tenant is left
    `active` without its default config, and no orphaned audit row is written for an incomplete
    transaction.

**Tenant identity resolution at the edge**
16. Tenant identity is resolved exactly once, at the auth filter/interceptor layer, from the validated
    token/session/subdomain — never from request body, query/path parameter, header, or hidden field.
17. Resolved tenant identity is attached to a request-scoped context that every downstream module reads,
    rather than each module re-deriving it independently.
18. An unresolvable subdomain/custom domain fails resolution safely with no fallback to another tenant; the
    frontend renders neutral platform-default branding.
19. A request against tenant A's subdomain carrying a manipulated header/param/body field claiming tenant
    B's id still resolves to tenant A — the manipulated field has zero effect on resolution.
20. A tenant whose status is not login-eligible (pending, suspended, cancelled) is rejected server-side at
    login against that subdomain, not merely hidden in the UI.

**Cross-cutting**
21. Every criterion above touching a tenant-owned or platform-level protected endpoint has an accompanying
    cross-tenant and/or platform-admin-only negative test — per `.claude/rules/tenancy.md`, a review lacking
    this test is "isolation unverified," not "isolation present."
22. Any tenant-owned table this module's provisioning step creates (e.g. default tenant config rows) has
    `tenant_id NOT NULL` with a tenant-leading composite index.

## 6. Out-of-scope items

- **FR-TM-5/FR-TM-6** — custom LMS branding editor (name, color theme, custom domain, tenant-specific
  login/public pages), branding preview panel, theme presets, per-tenant favicon/certificate/invoice
  branding. Explicitly Phase 2. `TEN-2`'s "default branding/plan config" provisioning step only creates
  *default* values — it does not implement the branding editor.
- **Custom-domain resolution** — `TEN-3` is subdomain-only for MVP; custom-domain-to-tenant resolution is
  Phase 2.
- **FR-TM-4 plan-limit enforcement UI** — usage-vs-plan-limit is "MVP core, Phase 2 enforcement UI," and
  actual enforcement depends on the unratified Feature Flag & Plan Limit Engine (Module D, no owning
  domain per `docs/requirements/open-decisions.md` §6). This module records a plan reference; it must not
  attempt to enforce limits.
- **Platform Admin "view as tenant" impersonation** — future capability with its own audit/session
  requirements (`docs/requirements/user-roles-and-permissions.md` §5); not part of this module.
- **Tenant Admin's own profile-edit flow** (FR-TM-2: logo, colors, contact info edited by Tenant Admin
  post-approval) — this module only requires that a Tenant Admin be *blocked* from touching another
  tenant's profile; the editable-profile UI itself is tracked separately.
- **Reactivation of a cancelled/suspended tenant** — no flow is specified anywhere; not built here.
- **Rejection-reason capture, applicant notification, and re-application handling** — explicitly
  unspecified; the minimal entry point is built without inventing this workflow.
- **`identity-access-service`'s actual authentication/session/JWT mechanism** and **`TEN-3`'s actual
  filter/interceptor** — these belong to a different, not-yet-built module. This plan covers only the
  `tenant-management`-owned half of `TEN-3` (the subdomain→tenant lookup `api`). See §20/§21.
- **`TEN-2`'s live approval/status-change endpoints** cannot reach real Definition of Done without
  `identity-access-service` + RBAC — see §20. Their design is planned here; their authorization wiring and
  negative tests are explicitly deferred, not stubbed.

## 7. Domain model

**`Tenant`** — the platform-level identity aggregate, **not** tenant-owned (the one entity in this
codebase that deliberately does not implement `TenantOwned` and does not extend `TenantAwareRepository`).

Conceptual shape:
- `id` (UUIDv7, via existing `com.lms.common.persistence.BaseEntity`/`UuidV7Generator`)
- `name`
- `subdomain` (`UNIQUE NOT NULL`, DNS-label-safe format)
- `status` (CHECK-constrained: `pending_approval | trial | active | suspended | cancelled | rejected` —
  resolved 2026-08-08, see §21 item 1)
- `plan_id` (references a plan catalog that does not yet exist anywhere in the codebase — open decision,
  §21)
- `created_at` / `updated_at` (+ `created_by`/`updated_by` via the existing `Auditable` base, optional —
  flagged as a deviation from the backlog's literal column list, confirm before adopting)

**Provisioned-at-approval data** (separate table(s), not columns bolted onto `tenant`): `TEN-2`'s
acceptance criteria ("provisioning of default branding/plan config") implies at least one additional table
— e.g. `tenant_branding_config` / `tenant_plan_config` — populated only once a tenant is approved. Keep
this distinct from the core identity row rather than widening `tenant` with nullable
"only-populated-after-approval" columns, consistent with the platform-level-vs-tenant-owned discipline in
`docs/architecture/database-architecture.md`.

**Status lifecycle** — resolved 2026-08-08 (see §21 item 1). `trial` is kept as a real post-approval
operating state (not a pre-approval placeholder), consistent with the original 4-value list treating it as
distinct from `active`:

```
(submission) --> PENDING_APPROVAL --> TRIAL (approve)
                                   --> REJECTED (reject; terminal for MVP)
             TRIAL --> ACTIVE (plan/payment confirmed — trigger owned by a later module, not this one)
             TRIAL/ACTIVE --> SUSPENDED
             TRIAL/ACTIVE/SUSPENDED --> CANCELLED (terminal for MVP — no reactivation path, per §6)
```

Every transition not in this graph (e.g. `REJECTED -> *`, `CANCELLED -> *`, `PENDING_APPROVAL ->
SUSPENDED`, `SUSPENDED -> ACTIVE` directly) must be rejected server-side, not silently allowed. Whether a
`SUSPENDED` tenant can ever be reactivated (and to which prior state) remains a separate, narrower open
question — see §21 item 8 — distinct from the enum-shape decision resolved here.

**Registration/profile data**: `TEN-1`'s acceptance criteria says registration captures "profile/contact/
requested-plan data," but the backlog's literal column list has no contact/profile columns beyond `name`
and `subdomain`. Whether this lives on `tenant` itself (a small number of additional nullable columns) or a
separate `tenant_registration`/`tenant_profile` table is unresolved — flagged, not invented (§21).

## 8. Database design

**Migration**: `backend/src/main/resources/db/migration/V2__create_tenant_table.sql` — the first real
schema migration after `V1__baseline_conventions.sql`'s documentation-only marker. Coordinate to ensure no
other in-flight work claims `V2` first.

```sql
-- V2__create_tenant_table.sql (DESIGN DRAFT — plan_id modeling is still an
-- open decision per this plan's §21; do not author the real migration until
-- that is resolved. The status enum below is final, see §21 item 1.)

CREATE TABLE tenant (
    id           UUID PRIMARY KEY,
    name         VARCHAR(255) NOT NULL,
    subdomain    VARCHAR(63)  NOT NULL,
    status       VARCHAR(20)  NOT NULL,
    plan_id      UUID,                 -- open decision: no plan catalog table exists yet (§21)
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by   UUID,
    updated_by   UUID,

    CONSTRAINT uq_tenant_subdomain UNIQUE (subdomain),

    -- Resolved 2026-08-08 (§21 item 1): pending_approval and rejected added
    -- to reconcile the contradiction between the backlog's literal 4-value
    -- list and TEN-1/TEN-2's acceptance criteria, which require a distinct
    -- pre-approval state and a rejection outcome the original 4 values
    -- could not represent.
    CONSTRAINT ck_tenant_status CHECK (
        status IN ('pending_approval', 'trial', 'active', 'suspended', 'cancelled', 'rejected')
    ),

    -- DNS-label shape, lowercase-only, closes case-sensitivity ambiguity at
    -- the constraint level rather than relying on app code to normalize.
    CONSTRAINT ck_tenant_subdomain_format CHECK (
        subdomain ~ '^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$'
    )
);

-- Platform Admin Tenant List: filter by status, most-recent first.
CREATE INDEX idx_tenant_status_created_at ON tenant (status, created_at DESC);

-- No separate subdomain index needed: the UNIQUE constraint's btree index
-- already serves point lookups by subdomain at request time (TEN-3).
```

Design notes:
- No `DEFAULT` on `id`/`status`/`subdomain` — every insert must explicitly state them; identifiers are
  application-generated (UUIDv7), never `gen_random_uuid()`, per `V1`'s documented convention.
- No FK constraints *out* of `tenant` to any business-domain table (foundational-module rule); no other
  module's migration may add an inbound FK to `tenant(id)` until this migration ships.
- A DB `CHECK` cannot express a subdomain reserved-word denylist (`www`, `admin`, `api`, `platform`, etc.)
  — that needs an application-layer validation list in the registration service.
- Audit trail for `TEN-2`'s transitions belongs to `audit-log-management`'s `audit_log` table (`AUDIT-1`),
  which does not exist yet and is **not designed here** — out of this module's ownership.

## 9. Backend design

Package: `com.lms.tenantmanagement`, following `.claude/rules/architecture.md`'s per-domain structure:

```
com.lms.tenantmanagement
|-- api        # TenantLookupApi (consumed later by identity-access-service),
|              # TenantSummary / TenantRegistrationResult DTOs — never the entity
|-- web        # TenantRegistrationController (public, anonymous)
|              # TenantAdminController (platform-admin approval/status — separate
|              #   controller/audience from the public one; DEFERRED, see §20)
|-- service    # TenantRegistrationService, TenantStatusService (state machine)
|-- domain     # Tenant, TenantStatus, TenantBrandingConfig/TenantPlanConfig
|-- repository # TenantRepository extends JpaRepository<Tenant, UUID> — NOT
|              #   TenantAwareRepository; explicitly named cross-tenant method
|              #   findAllPendingTenantsForPlatformApproval(...)
`-- config     # domain-local config only, if any
```

Key boundaries:
- `Tenant`'s repository deliberately does **not** extend `TenantAwareRepository`/implement `TenantOwned` —
  the one place in the codebase where that's correct, and worth a specific PR-checklist callout so a
  reviewer doesn't "fix" it reflexively.
- **The resolution filter/interceptor itself is out of scope for this module.** Per
  `docs/architecture/multi-tenancy.md` §1.6, it lives in `identity-access-service`'s `config`/`web` layer.
  `tenant-management`'s job is a narrow `api` lookup — e.g. `Optional<TenantResolution>
  resolveBySubdomain(String subdomain)` — returning enough for the caller to decide "usable" (active/trial)
  vs. "not usable" (pending, suspended, cancelled) vs. "unknown," without leaking the full entity.
  `tenant-management` must not touch `TenantContextHolder`, must not implement a `SecurityFilterChain`, and
  must not reintroduce filter logic under `com.lms.common` (`docs/architecture/authentication-authorization.md`
  §2 is explicit that the shared kernel must never host that).
- `api` returns DTOs only, never the JPA entity (`backend/CLAUDE.md`: "Do not expose JPA entities
  directly").
- Audit-write placement is an open decision (§21): `TEN-2`'s "exactly one audit row, same transaction"
  requirement is a stronger consistency need than `audit-log-management`'s general async-event-consumer
  pattern — likely resolves to a synchronous in-process `api` call into `audit-log-management` inside the
  approval transaction, but this needs an explicit decision, not a default assumption.
- Cache/invalidation: if a future `identity-access-service` filter caches tenant-status lookups for
  performance, that cache needs an explicit invalidation path tied to `TEN-2` status changes (e.g. a
  status-change domain event `tenant-management` publishes) — otherwise "suspension immediately affects
  access" (AC 9) cannot hold. Design the `api` lookup contract with this in mind now, even though the cache
  itself isn't built yet.

## 10. API contract

Draft only — must be finalized via the `review-api-contract` skill into `docs/api/tenant-management.md`
before implementation starts on either side, per `docs/api/README.md`'s own process.

| Method + path | Auth | Notes |
|---|---|---|
| `POST /api/v1/tenant-registrations` | None (public) | Body: institute name, subdomain, contact info, requested plan reference. Response: `201` with the created tenant's id/status (`pending_approval`), or `409` on subdomain/name conflict, or `422` on validation failure. Never accepts `id`/`status`/`tenant_id` fields — extra fields in the body are ignored, not bound. |
| `GET /api/v1/platform-admin/tenants` | Platform Admin only — **DEFERRED, see §20** | Filterable by status, paginated. Uses the explicitly named bypass repository method. Distinguishes zero-data vs. filtered-empty in the response/metadata so the frontend can render the correct empty state. |
| `GET /api/v1/platform-admin/tenants/{id}` | Platform Admin only — **DEFERRED** | Full profile/contact/plan detail for the approval screen. |
| `POST /api/v1/platform-admin/tenants/{id}/approve` | Platform Admin only — **DEFERRED** | Atomic status flip + default config provisioning + one audit row. `409` if the tenant is not currently `pending_approval` (stale-state / already-processed-by-another-admin case). |
| `POST /api/v1/platform-admin/tenants/{id}/reject` | Platform Admin only — **DEFERRED** | Same atomicity/audit pattern; reason-capture is an open decision (§21). |
| `POST /api/v1/platform-admin/tenants/{id}/suspend` | Platform Admin only — **DEFERRED** | Must trigger immediate session/token invalidation for that tenant (see §15). |
| `POST /api/v1/platform-admin/tenants/{id}/cancel` | Platform Admin only — **DEFERRED** | Same pattern as suspend. |

All responses use the existing `com.lms.common.api.ApiResponse<T>` envelope
(`docs/plans/MVP-001-application-foundation.md` §5). No client-supplied `tenant_id`, role, or other
trust-sensitive field is ever accepted on any endpoint above — tenant/role are always resolved server-side.
Every "Platform Admin only" endpoint above is designed now but cannot be *wired with real authorization* or
tested end-to-end until `identity-access-service`/RBAC exist — see §20.

## 11. Frontend screens

### `app/(public)/`
| Screen | Key components | Notes |
|---|---|---|
| **Tenant Registration** (`/register`) | Multi-field form (institute name, subdomain, contact, plan `Select`) via Form Field Wrapper, Text Input, Select, Validation Message | Loading (`aria-busy` on submit), inline field errors (RHF+Zod) + page-level Alert for backend 409/422, mobile-first consumer-style responsive (per `.claude/rules/ui-ux.md` §5) |
| **Registration confirmation** | Alert/confirmation panel | Must say "submitted, pending review" — never implies active/approved status (mirrors the "enrollment never activates from the frontend" discipline applied to tenant status) |
| **Tenant login/storefront shell branding** | Branding slot resolved from subdomain | Falls back to neutral platform branding on resolution failure, never a cached/stale or another tenant's branding |

### `app/(platform-admin)/` — **DEFERRED, see §20** (design now, build once `identity-access-service` exists)
| Screen | Key components | Notes |
|---|---|---|
| **Tenant List** | Table + Status Chip + Filters + Pagination | Tenant name always visible per row; two distinct empty states (§13); admin-heavy responsive pattern (card/list fallback below `md` or sticky-name-column horizontal scroll) |
| **Tenant Approval detail** | Page Header (tenant name prominent) + read-only profile/contact/plan summary + Confirmation Dialog (Approve / Reject) | Reject dialog is destructive-severity; stale-state handling required if another admin already processed the tenant |
| **Tenant Detail** (status control: suspend/cancel) | Page Header + Status Chip + Confirmation Dialog per action | Tenant name shown next to every state-changing action |

**Design-system gap surfaced by this review**: `docs/ui-ux/component-library-spec.md` §2.10's Status Chip
vocabulary has no `Trial` variant, and `Pending`/`Pending Approval` is ambiguous — both are blocked on the
same unresolved `success`/`warning` semantic-token decision already open in that spec. Do not invent a
color/icon here; file against that existing open question (§21).

Existing scaffolding: `frontend/src/app/(public)/layout.tsx` already links to `/register` (route doesn't
exist yet); `frontend/src/app/(platform-admin)/platform-admin/dashboard/page.tsx` is a placeholder only.
Shared state components (`EmptyState`, `ErrorState`, `LoadingState`, `PermissionDeniedState`) already exist
under `frontend/src/components/states/` and must be reused, not reimplemented.

## 12. Validation rules

- **Registration fields**: institute name, contact info, requested plan reference, subdomain — required.
  Exact field list beyond these is not enumerated anywhere; do not invent additional fields.
- **Subdomain**: `UNIQUE NOT NULL` at the DB level (settled); DNS-label-safe format (lowercase alphanumeric
  + hyphen, length bound) recommended given it becomes part of a live URL, but no authoritative format spec
  exists — flag before hardcoding (§21). Reserved-word denylist (`www`, `admin`, `api`, `platform`, etc.)
  enforced at the application layer, not expressible as a DB `CHECK`.
- **Status**: CHECK-constrained enum `pending_approval | trial | active | suspended | cancelled | rejected`
  (resolved 2026-08-08, §21 item 1). The state machine enforces the transition graph in §7 — no code path
  may write a status value outside the constraint or an illegal transition.
- **Plan**: validate only that a plan reference is present and (once a plan catalog exists) valid — do not
  build limit enforcement here (§6).
- **Uniqueness scope**: `subdomain` is DB-unique (settled). Whether `name` or contact email also require
  uniqueness is unspecified — do not add beyond `subdomain` without confirming (§21).

## 13. Error cases

| Case | Expected behavior | Status |
|---|---|---|
| Duplicate registration (same subdomain twice) | DB-level unique constraint rejects the insert; concurrent-registration race must be closed at the constraint level, not a check-then-insert app check | User-facing content is an **open decision** (§21) |
| Invalid subdomain format | Server-side field-level validation error before any DB call | Format rule itself is a gap (§21) |
| Client submits `status`/`tenant_id` in the registration payload | Ignored — DTO is an allow-list, not a blacklist | Settled requirement |
| Non-admin reaching approval queue/list/mutating endpoints | 403 server-side, always | Settled requirement; **enforcement deferred**, see §20 |
| Tenant Admin of A requests/modifies tenant B's data | 403/404, indistinguishable from "doesn't exist" | Settled requirement; **enforcement deferred**, see §20 |
| Suspended tenant — existing session makes a request | Rejected immediately, no stale-session grace window | Settled requirement; **mechanism is an open decision**, see §15/§21 |
| Pending/suspended/cancelled tenant — new login attempt | Rejected server-side | Settled requirement; **enforcement deferred**, see §20 |
| Unresolvable subdomain | Fails safely, no fallback to another tenant; neutral branding | Settled requirement |
| Manipulated tenant-id claim in header/body/param | Still resolves to the subdomain's real tenant | Settled requirement; mandatory foundational test |
| Approval provisioning partially fails | Whole transaction rolls back | Settled requirement |
| Rejection then re-registration with same subdomain | Unspecified | **Open decision** (§21) |
| Race: two registrations for the same subdomain concurrently | Exactly one succeeds; the other gets a clean 409, not a 500 or silent duplicate | New gap surfaced by this review — must be a concurrency test, not just a logical uniqueness check |
| Two Platform Admins process the same pending tenant concurrently | Second submit gets a "already processed" error, not a silent no-op or a duplicate audit row | New gap surfaced by this review |

## 14. Tenant-isolation rules

`tenant` itself is **platform-level, not tenant-owned** — the standard "cross-tenant negative test" pattern
(tenant A cannot see tenant B's *rows in a tenant-owned table*) does not directly apply to it, since there
is no `tenant_id` column to test. This module's isolation obligations take three different shapes instead:

1. **Global-uniqueness enforcement** (`TEN-1`): `subdomain` cannot collide across two different
   registrants, enforced at the DB level (constraint, not just app-level check), including under
   concurrent-insert races.
2. **Platform-admin-only bypass-method enforcement** (`TEN-2`): the approval queue/tenant list is
   *inherently* cross-tenant by design — this is the canonical "explicitly named bypass" case per
   `docs/architecture/multi-tenancy.md` §2. The repository method is not reachable by a non-Platform-Admin
   role at the service/controller layer, and once tenant-scoped admin views exist, a Tenant Admin of
   tenant A gets 403/404 on tenant B's id, never 200.
3. **Resolution-integrity enforcement** (`TEN-3`): a request against tenant A's subdomain must never
   resolve to tenant B's identity because of any client-supplied signal (header, param, body, stale
   token). This is the foundational mechanism every other module's isolation depends on without
   re-deriving it — a bug here is a direct cross-tenant data-leak vector platform-wide.

Every future tenant-owned table this module's provisioning step creates (e.g. default config rows) must
follow the standard rule: `tenant_id NOT NULL` + tenant-leading composite index + no repository method
accepting a caller-supplied `tenant_id`.

## 15. Security rules

1. **No client-controlled status/id/role fields** on the registration DTO — enforced at the service layer,
   not just DTO shape, as defense in depth.
2. **Subdomain uniqueness must be a DB constraint**, not an app-level pre-check — a pre-check-then-insert
   without the DB constraint is a TOCTOU race producing tenant-identity collision, a hijack vector since
   `TEN-3` trusts subdomain as the resolution key. Concurrent-insert race must be an explicit test.
3. **Subdomain format/reserved-word validation** server-side — reject platform-reserved names (`www`,
   `api`, `admin`, `app`, `platform`, `assets`, etc.) and unsafe characters, to prevent phishing/confusion
   against `TEN-3`'s resolution logic.
4. **Registration-flooding / anonymous-endpoint abuse** — rate limiting (per-IP, ideally per-target-
   subdomain) and a cap on pending/unapproved registrations per IP/email, to prevent subdomain-squatting
   and storage/notification exhaustion. Not in the current acceptance criteria — flagged as a gap to add.
5. **Duplicate-registration handling must fail closed** (reject/409) until the open decision (§21) is
   resolved — never silently upsert/merge, which would let an attacker inject different contact/plan data
   ahead of the real owner's approval.
6. **Server-side role check on every approval-queue/tenant-list/status-change endpoint**, evaluated against
   the authenticated principal's role from the trusted session/token — never a client-sent header/param/
   flag. Do not ship this behind anything except a real role claim from `identity-access-service`; until
   that exists, these endpoints stay behind the current deny-all `SecurityConfig` rather than an ad hoc
   local approximation of "is admin" (see §20/§21 — building a stopgap here would itself be an unapproved
   deviation from the change-controlled authentication architecture).
7. **The named cross-tenant bypass method must itself require Platform Admin authentication at the service
   layer that calls it** — being explicitly named makes the bypass visible in review, it does not make it
   safe by itself.
8. **Status-transition state machine must reject invalid transitions server-side** (e.g.
   `cancelled -> active` with no defined reactivation path) — define the legal transition graph (§7) and
   enforce it in the service layer plus a DB `CHECK` constraint on the enum values.
9. **Approval must be atomic**: status flip + provisioning + audit row in one transaction, tested with a
   simulated partial-failure rollback.
10. **Suspension must immediately invalidate active sessions/tokens for that tenant's users, not just block
    new logins.** This needs a concrete mechanism decision before `TEN-2`'s backend is built: either a
    per-request tenant-status re-check (not cached indefinitely) or a server-side revocation signal
    (Redis-backed fast path, Postgres-authoritative per `.claude/rules/architecture.md`'s "Redis is
    cache/ephemeral-state only" rule). A JWT-only approach with no per-request status re-check will fail
    this requirement — flag as an explicit design decision, not something to defer silently (§21). Attack
    scenario to guard against: a Tenant Admin holds a still-valid JWT issued before suspension; every
    subsequent request must re-check tenant status from trusted server state, not from a claim baked in at
    issuance.
11. **Cross-tenant object-reference test on tenant profile/config/plan/status endpoints** — 403/404,
    indistinguishable between "doesn't exist" and "exists but unauthorized," never 200 with empty/filtered
    data (that leaks existence).
12. **Tenant identity must never be accepted from a request body, query/path parameter, header, or hidden
    field** — and no "convenience" dev/local fallback (e.g. an `X-Tenant-Id` header honored outside strict
    local profiles) may be added; any such fallback is itself a change to the change-controlled
    authentication architecture and needs an ADR, not a silent addition.
13. **Mandatory spoofing negative tests**: manipulated header/param claiming a different tenant while
    hitting a real subdomain; a token issued for tenant A replayed against tenant B's subdomain (must fail
    closed on mismatch, not resolve to either); unresolvable subdomain (no fallback to a default/first
    tenant); a request against a suspended/cancelled tenant's subdomain (must fail resolution for
    login/session purposes).
14. **Every downstream domain must be a pure consumer of `TenantContextHolder`** — never re-deriving tenant
    identity independently. Any future PR reading `tenant_id` from a DTO/param/header instead of the holder
    is a bug, regardless of whether the value happens to match in the happy path.
15. **Async/background work must explicitly carry `tenant_id`** in its payload — `TenantContextHolder` is
    thread-local and request-scoped only; any notification/audit/scheduled job triggered by this module's
    actions must not assume it survives a thread-boundary crossing.
16. **Direct-object-reference/ID-guessing** across tenants and roles — `tenant.id` must be UUID (already
    specified); list/detail endpoints must not leak existence via response-time or error-message
    differences between "doesn't exist" and "exists but unauthorized."
17. **No real institute/contact data in test fixtures** — synthetic names/emails/domains only, per root
    `CLAUDE.md` Safety rules.

## 16. Audit requirements

1. Every approval, rejection, suspension, and cancellation writes **exactly one audit row** in the same
   transaction as the status change (actor id = Platform Admin, tenant id = target tenant, action type,
   before/after status, timestamp) — a same-transaction service-layer write, never a separately triggered,
   skippable event.
2. **Documented cross-document gap, not silently resolved here**: `.claude/rules/security.md`'s canonical
   mandatory-audit-action list does **not** name tenant approval/status-change; the obligation instead
   comes from `functional-requirements.md`/`docs/ui-ux/user-journeys.md` and is already tracked in
   `docs/requirements/open-decisions.md` §5. This plan implements the audit requirement as specified in the
   functional requirements (unambiguous and low-risk to include) but does not edit
   `.claude/rules/security.md` itself — recommend a separate follow-up to reconcile the two documents.
3. Tenant-approval audit rows are platform-scoped: no tenant-level role (including a Tenant Admin of the
   approved tenant) should be able to list/search them, consistent with how audit logs are treated as
   tenant-owned-adjacent, access-controlled data elsewhere in the system.
4. Audit rows are append-only — no update/delete endpoint or repository method may target this table.
5. "Exactly one audit row per transition" must be verified with a real Testcontainers-backed Postgres test,
   not a mock — a mock cannot catch a double-write (e.g. once from the service call, once from an event
   listener) or a missing write under rollback.
6. Registration itself (`TEN-1`) is **not** on any audit-mandatory list — only the Platform Admin's
   subsequent approval/rejection/status-change decision requires an audit row. Ordinary request logs are
   not a substitute for the mandated append-only, actor-attributed audit trail.

## 17. Payment impact

**None.** Confirmed against `TEN-1`/`TEN-2`/`TEN-3`'s own "Payment impact" fields (all `None`) in
`docs/planning/product-backlog.md`. This module records a `plan_id` reference at registration/approval
time but does not process any payment, and plan-limit *enforcement* is explicitly out of scope (§6). No
`payment-ledger-specialist` review was performed for this reason.

## 18. Tests

`.claude/rules/testing.md` does not exist in this repository — the governing "seed at least two tenants"
convention is instead sourced from `docs/architecture/multi-tenancy.md` §3, which itself attributes it to a
file that isn't present; flagged as a minor doc/rule-set gap, not something this plan resolves.

Existing foundation to reuse: `com.lms.common.AbstractIntegrationTest` (Testcontainers Postgres/Redis,
`TENANT_A`/`TENANT_B` constants, `withTenant(...)` helper), `TenantAwareRepositoryFixtureIntegrationTest`
as the pattern reference for cross-tenant Testcontainers tests.

### Buildable and testable now
- **Unit**: registration DTO validation (required fields, subdomain format/reserved words); DTO never binds
  client-supplied `status`/`id`; `TenantStatusStateMachineTest` (table-driven over every legal/illegal
  transition, once §21's enum is resolved); `TenantResolutionServiceTest` (subdomain → tenant lookup logic,
  fallback-to-none on unresolved, table-driven).
- **Testcontainers**: tenant created in pending state on registration; `subdomainUniquenessIsEnforcedAtDbLevel`
  (direct repository/DB-level insert race, including a genuine concurrent-insert test — exactly one
  succeeds); `duplicateRegistrationViaApiIsRejectedNotDuplicated` (through the real endpoint); registration
  endpoint ignores injected `id`/`status` fields in the raw request; `tenantRepositoryIsPlainJpaRepository
  NotTenantAware` (structural regression guard); `approvingATenantFlipsStatusAndProvisionsDefaultConfig
  Atomically`; `partialProvisioningFailureRollsBackTheStatusChange`; `platformAdminBypassRepositoryMethod
  IsExplicitlyNamedAndNeverTenantScoped` (repository-shape half, no HTTP layer needed);
  `emptyPendingQueueIsDistinguishableFromNoMatchesForFilter`; `resolvingKnownSubdomainReturnsCorrectTenant
  AgainstRealDb` (seed tenant A + B, resolve each); `resolvingUnregisteredSubdomainFailsWithNoTenantLeak`;
  `TenantResolutionIgnoresClientSuppliedTenantSignalsTest` (resolver-component-level proxy for the
  mandatory spoofing test, ahead of the real filter existing).
- **Playwright**: public registration form states (happy path, validation errors, duplicate-subdomain
  surfaced as field-level error, loading/success copy that never implies activation); Tenant List/Approval
  UI states against seeded/stubbed data (tenant name always visible, two distinct empty states,
  confirmation dialogs show target tenant name) — annotated as interim, not RBAC-verified; distinct
  branding per subdomain and neutral fallback on an unregistered subdomain (not blocked — needs no login).

### Blocked on `identity-access-service` + RBAC-2 — must be filed as named follow-ups, never silently skipped
- `nonPlatformAdminCannotReachApprovalQueueEndpoint` — real `TestRestTemplate` call through the actual
  Spring Security filter chain with a Tenant-Admin-role token, asserting 403.
- `tenantAdminOfTenantACannotReadOrModifyTenantBsProfile` — once a tenant-scoped admin view exists.
- `TenantResolutionFilterIntegrationTest#manipulatedTenantHeaderAgainstTenantASubdomainStillResolvesToTenantA`
  — the literal mandatory `TEN-3` test through the real filter chain; the resolver-level proxy above is not
  a substitute and must be explicitly labeled as interim if it ships first.
- `exactlyOneAuditRowIsWrittenPerApprovalTransition` — blocked on `AUDIT-1`'s real table; do not assert
  against a mock/in-memory audit sink.
- `suspensionOfActiveTenantIsPersistedImmediately` (state-change half is testable now) vs. "no stale
  session continues to work" (blocked on session/token infrastructure existing).
- Playwright cross-tenant/permission-denied navigation E2E (RBAC-3-shaped) — blocked on real login + RBAC
  existing; do not simulate with a mocked 403, since that doesn't prove the server-side check exists.

Per `docs/planning/definition-of-done.md`'s testing gate: `TEN-2` and `TEN-3` must **not** be marked fully
done on the strength of the interim/unit-level proxies alone — this plan explicitly separates "testable
now" from "blocked, tracked as follow-up" so a future reviewer doesn't mistake the interim tests for
satisfying the mandatory cross-tenant/platform-admin negative-test requirement.

## 19. Documentation changes

- `docs/architecture/database-architecture.md` — new `tenant` table entry, pending-status lifecycle, once
  §21's enum decision lands.
- `docs/architecture/modular-monolith.md` — confirm `tenant-management`'s `api` lookup surface for
  `identity-access-service` to consume, and the explicit non-ownership of the resolution filter.
- `docs/api/tenant-management.md` (new) — produced via `review-api-contract` from §10's draft before
  implementation begins on either side.
- `docs/requirements/open-decisions.md` — append the new gaps this plan surfaced that weren't already
  tracked: the status-enum contradiction, cancellation reversibility, the concurrent-registration race, and
  the two-admins-process-the-same-tenant race (§21).
- `docs/ui-ux/component-library-spec.md` — flag (not resolve) the missing `Trial` Status Chip variant and
  the `Pending`/`Pending Approval` ambiguity against the section's existing open `success`/`warning`
  semantic-token question.
- No change to `.claude/rules/security.md`'s canonical audit-action list as part of this module (§16 item
  2) — flagged as a separate follow-up, not resolved here.

## 20. Implementation order

Per root `CLAUDE.md`'s workflow (plan → backend → backend tests → frontend → frontend/E2E tests → reviews →
docs → one logical commit), and consistent with `docs/planning/mvp-release-plan.md`'s Wave 0/Wave 1
sequencing:

**Buildable and mergeable now, on `feature/tenant-management`, without inventing anything:**
1. The status-enum contradiction (§21 item 1) and the `plan_id`/registration-profile-data modeling
   questions (§21 items 2–3) are all resolved as of 2026-08-08 — see §21 for each decision's rationale.
2. Backend: `V2__create_tenant_table.sql` migration (§8), `Tenant`/`TenantStatus` domain classes, plain
   `TenantRepository`, registration DTO + validation, public registration endpoint (`TEN-1`).
3. Backend: `tenant-management`'s half of `TEN-3` — the `api` lookup method (`resolveBySubdomain` or
   equivalent), fully unit/integration-tested in isolation, with **no HTTP/filter surface**.
4. Backend: `TenantStatusService` state-machine logic as pure domain logic (unit-tested), with **no
   controller wired and no endpoint exposed** — validates the approval workflow's business rules ahead of
   time without requiring auth to exist.
5. Backend tests per §18's "buildable now" list. Run `backend\mvnw.cmd verify` — must be green.
6. Frontend: public registration screen + confirmation state (`TEN-1`), Tenant List "row scaffolding" only
   (no live approval action wired yet, since Platform Admin auth doesn't exist).
7. Frontend/E2E tests per §18's "buildable now" Playwright list. Run `npx playwright test` — must be green.
8. Security + tenant-isolation review pass on what shipped (registration endpoint, DB constraints, the
   `api` lookup method) — confirm no resolver/auth shortcut was introduced.
9. Documentation updates (§19) for what actually shipped in this slice.
10. Commit as separate backend and frontend commits (not bundled), per `.claude/rules/git-workflow.md`.

**Must be explicitly deferred — not stubbed, not worked around — tracked as named follow-up work once their
hard blockers land:**
11. `TEN-3`'s actual filter/interceptor — belongs in `identity-access-service` (does not exist; no
    `AUTH-1`/`AUTH-3` skeleton in the codebase today). Building a temporary resolution filter anywhere else
    (inside `tenant-management` or `com.lms.common`) is directly prohibited by
    `docs/architecture/multi-tenancy.md` §4 without a new ADR.
12. `TEN-2`'s live approval/status-change endpoints and their authorization wiring — hard-blocked on
    `AUTH-1`/`AUTH-2`/`RBAC-2` (Platform Admin authentication + role enforcement) and soft-blocked on
    `AUDIT-1` (audit table). A placeholder/fake admin check to unblock this early is explicitly rejected —
    it is exactly the "resolver-creep" pattern `docs/plans/MVP-001-application-foundation.md` already
    identified and avoided for `TenantContext`, and it would itself be an unapproved deviation from the
    change-controlled authentication architecture.
13. `TEN-2`'s frontend (Tenant Approval detail, Tenant Detail status controls, live Tenant List actions) —
    follows from #12.
14. The mandatory end-to-end negative tests listed as "blocked" in §18.

This matches `docs/planning/mvp-release-plan.md`'s own sequencing: `TEN-1` and `TEN-3`'s lookup contract are
Wave 0 (alongside `AUTH-1`, which needs them); `TEN-2` is Wave 1, after Wave 0 closes. Building only items
1–10 now, on this branch, is consistent with that plan; building 11–13 now would not be.

## 21. Risks and unresolved decisions

**Contradictions/gaps independently surfaced by multiple specialist agents — must be resolved by an
explicit decision before the affected work is implemented, not silently picked:**

1. **Status enum vs. "pending-approval" — RESOLVED 2026-08-08.** The backlog's literal database-impact
   spec for `TEN-1` listed the CHECK-constrained enum as exactly `trial/active/suspended/cancelled` — no
   value represented "pending-approval" or "rejected," both of which `TEN-1`'s and `TEN-2`'s own acceptance
   criteria require. **Decision (explicit approval, this is a schema/data-model decision scoped to
   `tenant-management`, not one of `CLAUDE.md`'s change-controlled areas, so no ADR is required): the enum
   is `pending_approval | trial | active | suspended | cancelled | rejected`** — option (a) from the two
   previously identified (adding values, rather than conflating `trial` with "awaiting approval"), because
   it matches the acceptance criteria literally and keeps `trial` a meaningful, distinct post-approval
   operating state rather than overloading it with a pre-approval meaning. The full transition graph is in
   §7; `rejected` and `cancelled` are terminal for MVP (no reactivation path — consistent with §6's
   out-of-scope). This resolution is now reflected in §5, §7, §8, and §12 above. **Not resolved by this
   decision**: whether a `suspended` tenant can ever be reactivated (item 8 below) — a narrower, separate
   question.
2. **`plan_id` references a plan catalog that doesn't exist — RESOLVED 2026-08-08.** Module D (Feature
   Flag & Plan Limit Engine) is explicitly unratified/unowned (`docs/requirements/open-decisions.md` §6).
   Of the three options originally identified (nullable `UUID` FK-less placeholder; a constrained
   free-text/enum `plan_code`; deferring the column entirely), **the implementer chose a fourth, closer
   variant during `implement-backend`: `requested_plan VARCHAR(100) NOT NULL`, unconstrained free text, no
   FK, no enum/CHECK against invented plan-tier names.** Rationale: a bare UUID `plan_id` has nothing
   meaningful a registrant could submit against (no plan catalog exists to reference), and a constrained
   enum would have required inventing plan-tier names (`basic`/`pro`/`enterprise`) nowhere specified in
   any source document — itself an unresolved business decision this plan must not invent. Free text lets
   Platform Admin read the requested plan during manual approval review without the schema asserting a
   catalog contract it has no authority to define. See `backend/src/main/resources/db/migration/
   V2__create_tenant_table.sql`'s header comment for the same rationale recorded at the schema level.
   Confirmed by independent database-architect and security-reviewer passes as sound and consistently
   applied (DTO validation matches the column exactly). **Follow-up when Module D ships:** a new migration
   should add a real `plan_id UUID REFERENCES plan(id)` and a data-migration path off `requested_plan` —
   do not retrofit by editing this migration.
3. **Registration/profile data modeling — RESOLVED 2026-08-08.** Whether contact/profile fields live as
   additional columns on `tenant` or a separate `tenant_registration`/`tenant_profile` table was
   unresolved. **Decision: additional columns directly on `tenant`** (`contact_name`, `contact_email`,
   `contact_phone` — the latter nullable, the former two required), the lower-commitment option the plan
   itself had already identified as available. Rationale: `TEN-1`'s acceptance criteria require capturing
   this data and no story splits it into its own table; a satellite table would be premature
   normalization for three scalar fields with no independent lifecycle of their own. Confirmed by
   independent database-architect review (column nullability/lengths verified consistent with
   `TenantRegistrationRequest`'s Bean Validation constraints, no drift between the two layers).
4. **Self-registration model**: public/self-serve vs. invite-only (`docs/requirements/open-decisions.md`
   §1). Materially changes the anonymous endpoint's security posture (rate limiting/CAPTCHA/email
   verification needs differ) — build the entry point so this can be swapped without a redesign.
5. **Subdomain/custom-domain uniqueness scope** — only "implied," never explicitly stated beyond the DB
   `UNIQUE` on `subdomain`. Whether `name`/contact-email also need uniqueness is unaddressed.
6. **Duplicate/conflicting registration behavior** — what the user-facing result is (generic conflict vs.
   specific "already pending" messaging) is unspecified; this plan recommends failing closed (§15 item 5)
   pending resolution, not silently choosing the final UX.
7. **Rejection-reason capture, applicant notification, and re-application handling** — entirely
  unspecified.
8. **Cancellation reversibility** — no document says whether a `cancelled` tenant can ever be reactivated,
   or by what flow. New gap surfaced by this planning pass — recommend adding to
   `docs/requirements/open-decisions.md`.
9. **Suspension's session-invalidation mechanism** — per-request tenant-status re-check vs. a Redis-backed
   revocation signal vs. short token TTL is an authentication-architecture-level detail not yet specified
   anywhere; effectively a change-controlled decision (`docs/architecture/authentication-authorization.md`
   is itself change-controlled) that should get explicit sign-off, likely via an ADR update, before `TEN-2`'s
   backend is built.
10. **Audit-write architecture tension.** `docs/architecture/modular-monolith.md` describes
    `audit-log-management` as an async event consumer, but `.claude/rules/security.md` requires
    security-sensitive audit writes in the same transaction/service boundary as the privileged action, and
    `TEN-2`'s own acceptance criteria demands atomicity plus exactly one row. These two defaults point in
    different directions for this specific story — recommend an explicit decision (likely a synchronous
    in-process `api` call into `audit-log-management`) rather than defaulting to the general async pattern.
11. **Concurrent-registration race** (two simultaneous registrations for the same subdomain) — **RESOLVED
    2026-08-08 for the registration half.** Closed at the DB constraint level (`uq_tenant_subdomain`) and
    proven by a genuine two-thread concurrent test (`TenantRegistrationIntegrationTest
    #concurrentRegistrationsForTheSameSubdomainInsertExactlyOneRow`), independently verified by
    database-architect and qa-test-engineer review passes. **Concurrent-approval race** (two Platform
    Admins processing the same pending tenant) remains open — `TEN-2`'s live endpoint doesn't exist yet
    (§20), so this can't be closed until it's built.
12. **Design-system gap**: no `Trial` Status Chip variant, and `Pending`/`Pending Approval` ambiguity — both
    blocked on the Status Chip spec's already-open `success`/`warning` semantic-token question. Do not
    invent a color/icon in implementation; resolve upstream first.
13. **Sequencing gap for `TEN-2`/`TEN-3`'s live surfaces** (see §20) — this is not a business-decision gap
    but a hard structural dependency: `identity-access-service` does not exist in the codebase today (only
    `com.lms.common` does), so `TEN-2`'s real authorization and `TEN-3`'s real filter chain cannot reach
    genuine Definition of Done within this module alone. This plan treats that as a known, named risk to
    track across modules, not something to work around with a shortcut.
14. **Documentation/code drift note (informational, not blocking this module)**:
    `docs/architecture/authentication-authorization.md` §2 describes the Application Foundation's
    `SecurityConfig` placeholder as already "deleted... in the same commit that introduced the real filter
    chain," phrased in the past tense under an "Implementation note (MVP-002)" heading. The actual code
    (`backend/src/main/java/com/lms/common/config/SecurityConfig.java`) is still the original placeholder,
    and no `identity-access-service` package exists anywhere in the repo. This reads as forward-looking/
    prescriptive documentation rather than a record of completed work, but could mislead a future
    contributor. Worth a documentation clarification pass when MVP-002 (identity-access-service) is
    actually planned — not urgent for this module.
15. **Backend implementation reviewed 2026-08-08** — a four-agent read-only pass (security-reviewer,
    database-architect, solution-architect, qa-test-engineer) against the shipped `TEN-1`/unwired-`TEN-2`/
    unwired-`TEN-3` code found no Critical or High defects in security, schema safety, or architecture.
    One High-severity finding was a test-quality issue (`NotReservedSubdomainValidatorTest`'s
    `@MethodSource` was sourced from the same field it was testing, making it unable to catch a
    regression in the reserved-subdomain denylist) — **fixed same day**: the test now asserts against an
    independently-authored literal copy of the denylist. **Registration-flooding / anonymous-endpoint
    abuse (item 4 above / §15 item 4)** was independently reconfirmed absent by two reviewers and is now
    formally tracked as `docs/planning/risk-register.md` R18, rather than living only in this plan's prose
    — see that file for the risk entry and required mitigation before this endpoint is exposed beyond
    controlled/staging traffic.

---

*This plan does not authorize implementation of items 11–14 in §20. Items 1 and 2 (the status-enum and
`plan_id`/registration-profile-data open decisions) are resolved as of 2026-08-08 (§21 items 1–3) and
implementation against plan items 1–10 has proceeded and been reviewed (§21 item 15). Items 11–14 in §20
(`TEN-2`'s live endpoints, `TEN-3`'s actual filter chain) remain unauthorized pending
`identity-access-service`.*
