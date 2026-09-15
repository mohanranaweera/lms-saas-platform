# MVP-020 — Platform Admin Dashboard — Module Plan

**GitHub issue:** [#20 — \[MVP\] Module 20: Platform Admin dashboard](https://github.com/mohanranaweera/lms-saas-platform/issues/20)
**Backlog source:** `docs/planning/product-backlog.md`, MODULE 20 (stories `PADASH-1`, `PADASH-2`, lines 1197–1236)

This plan was produced by delegating to seven specialist agents in parallel
(`product-requirements-analyst`, `solution-architect`, `database-architect`, `security-reviewer`,
`qa-test-engineer`, `ui-ux-reviewer`, `payment-ledger-specialist`), each independently grounded in
the issue, the backlog, `docs/plans/MVP-004 Tenant Management.md`, `docs/plans/MVP-019 Audit
Logs.md`, `docs/plans/MVP-015 Tenant Admin Dashboard.md`, `docs/adr/ADR-014-audit-log-viewer-
access-scope.md`, the relevant `.claude/rules/*.md` files, and the actual current backend/frontend
code — then reconciled into one document. This is a **plan only** — no application files were
created or edited.

## Grounding note — what a literal reading of the issue would get wrong

Every backend-focused agent (product-requirements-analyst, solution-architect, security-reviewer,
qa-test-engineer) independently surfaced the same structural fact by reading the actual code, not
assuming from the issue/backlog text:

- **TEN-2 (Module 4's tenant-approval backend) is not built.** The issue and backlog both frame
  `PADASH-1` as "reads `tenant-management`'s explicitly-named cross-tenant bypass method... no new
  domain logic beyond TEN-2" — i.e. they assume TEN-2 already ships as live, testable backend
  code. It does not: `TenantStatusService` is pure, unwired state-machine logic with no repository
  call, no controller, no persistence, no audit write; `TenantRepository` is a plain
  `JpaRepository` with no approval-queue bypass method (its own javadoc documents this absence as
  deliberate, deferred to TEN-2); no `TenantAdminController` exists anywhere. Only
  `TenantRegistrationController` (public self-registration) ships today.
- **The blocker MVP-004 originally cited for deferring TEN-2 is now resolved.** MVP-004 §20/§21
  item 13 named `identity-access-service`/RBAC not existing as the reason TEN-2's live endpoints
  couldn't reach Definition of Done. Platform Admin authentication now exists in full
  (`PlatformAdminAuthController`, `PlatformAdminUser`, `PlatformAdminSession`, a JWT fixed to role
  claim `PLATFORM_ADMIN`, Spring Security authority `ROLE_PLATFORM_ADMIN`). This closes the
  original blocker but TEN-2's endpoints were never subsequently built to take advantage of it.
- **`PADASH-2`'s own hard blockers (`PAY-3`, `AUDIT-2`/`AUDIT-3`) are fully shipped** — confirmed
  against git history and the actual `ledgersettlementmanagement`/`auditlogmanagement` code. This
  produces a real asymmetry the issue's own text doesn't distinguish: `PADASH-2` is buildable now;
  `PADASH-1` is not, without also building a meaningful slice of a different domain
  (`tenant-management`) from scratch.
- **`PermissionCheckService`/`DomainArea`/`Role` structurally cannot authorize Platform Admin at
  all.** `PermissionCheckServiceImpl.hasPermission` calls `Role.valueOf(roleClaim)`, which throws
  for the literal string `"PLATFORM_ADMIN"` (deliberately excluded from the `Role` enum) and is
  caught to return `false` unconditionally — by design, per the class's own inline comment:
  "Platform Admin's platform-scoped session must never implicitly gain tenant-operational
  permissions." The issue's own text ("using RBAC-2") is imprecise here: RBAC-2's domain-matrix
  mechanism is not how Platform Admin authorization works in this codebase; a separate,
  role-claim-based mechanism (`hasRole('PLATFORM_ADMIN')`) is required, and no future work should
  add a `PLATFORM_ADMIN` row to that matrix.
- **Platform Admin requests never get `TenantContext` resolved.** `TenantResolutionFilter`
  excludes the entire `/api/v1/platform-admin/**` path prefix (`shouldNotFilter`), and
  `JwtAuthenticationFilter` rejects a Platform Admin token used outside that prefix. This is a
  *mechanical*, not just stylistic, reason every cross-tenant bypass method in this module must be
  an explicit `@Query`-annotated repository method: a plain default method inherited from
  `TenantAwareRepository`/`SimpleJpaRepository` that touches the tenant-scoping machinery would
  throw `TenantContextNotResolvedException` on a platform-admin request, since there is no
  resolved tenant to scope by.
- **This codebase already has a live, shipped naming/annotation precedent for exactly this
  pattern** — `PaymentRepository`'s `findByGatewayReferenceAcrossTenants`/`...AcrossTenantsForUpdate`
  (used by webhook reconciliation) and `NotificationOutboxRepository`'s
  `findPendingIdsAcrossTenants`/`claimPendingByIdAcrossTenantsForUpdateSkipLocked` — both explicit
  `@Query`-annotated methods with an `AcrossTenants` suffix, both citing ADR-006's naming
  convention. This module's three new bypass methods must follow that exact convention, not invent
  a new one.
- **A frontend scaffold for the tenant list already exists**
  (`frontend/src/app/(platform-admin)/platform-admin/tenants/page.tsx` + `mock-tenants.ts`),
  explicitly marked "SCAFFOLD ONLY, not wired to live data." Its table shape, status filter,
  search, responsive table→card breakpoint, and two-empty-state pattern are already correct and
  must be **rewired to live data**, not rebuilt.
- **Neither the Page Header (`showTenantContext` slot) nor the Breadcrumbs component exists in
  code yet**, though both are already specced in `docs/ui-ux/component-library-spec.md` §3.4/§2.12
  for exactly this module's persistent-tenant-context-banner requirement. This module is their
  first implementation, mirroring how MVP-019 was the first implementation of `Accordion`/
  `DateInput`.

These gaps are surfaced as an explicit, unresolved scope decision in §21 (item 1) — this plan does
not silently pick an answer, per root `CLAUDE.md`'s "do not invent unresolved business decisions."

## 1. Business goal

Give Platform Admin the platform's primary cross-tenant control surface: a tenant list/approval
queue built on Module 4's tenant-status state machine, and a read-only, cross-tenant
payment/ledger and platform-level audit-log view with per-tenant drill-down — using only
explicitly-named, reviewed cross-tenant bypass methods (never `TenantAwareRepository`), with every
cross-tenant row visibly naming its tenant and every single-tenant drill-down carrying a
persistent, non-dismissible tenant-context banner. Platform Admin's default permissions must never
implicitly grant tenant-admin-equivalent operational access — this module is explicitly read/
approval-only with respect to tenant data; it does not build "act as tenant" impersonation.

## 2. Roles and permissions

| Role | PADASH-1 (tenant list/approval) | PADASH-2 (cross-tenant payment/audit dashboard) |
|---|---|---|
| **Platform Admin** | Sole role with access, via `hasRole('PLATFORM_ADMIN')` — **not** the `DomainArea`/`PermissionAction` matrix, which structurally denies Platform Admin by design. | Sole role with access, same mechanism. Must never implicitly gain tenant-admin-equivalent operational access to any tenant's data. |
| **Tenant Admin / Institute Owner (any tenant)** | No access; rejected 403/404 regardless of which tenant they administer. | No access to the cross-tenant view; their own tenant's payment dashboard/audit log (already shipped, MVP-010/MVP-015/MVP-019) are unaffected, separate surfaces. |
| **All staff sub-roles, Teacher, Teacher Assistant, Student** | No access. | No access. |
| **Anonymous / Public** | No access. | No access. |

No new `DomainArea` enum value or permission-matrix cell is added for this module. Platform
Admin's authorization posture is deliberately outside that matrix — adding one would blur a
boundary the existing code (and its startup-time invariant check protecting Read-only Auditor)
was built to keep clean. Per `docs/requirements/user-roles-and-permissions.md` §4, Platform Admin
permissions are platform-scoped by default and do not implicitly grant tenant-admin-equivalent
access without an explicit, audited impersonation flow — out of scope here (§6).

## 3. Preconditions

**Shared / structural**
- Platform Admin authentication (`PlatformAdminAuthController`, JWT with `role=PLATFORM_ADMIN`,
  `ROLE_PLATFORM_ADMIN` authority) — shipped.
- The `/api/v1/platform-admin/**` path prefix is already reserved and filtered distinctly from
  tenant-scoped paths (`JwtAuthenticationFilter`, `TenantResolutionFilter.shouldNotFilter`) — new
  endpoints in this module live under that prefix.
- `PermissionCheckService.hasPermission`/`requirePermission` must **not** be used for these
  endpoints — confirmed structurally incapable of granting Platform Admin anything.

**PADASH-1-specific**
- TEN-2's backend reaching real Definition of Done (repository bypass method, controller,
  audit-write integration, authorization wiring) — **not currently met**; see §21 item 1 for the
  scope decision this blocks.
- `tenant` table (TEN-1) exists and is populated via the shipped public registration endpoint —
  met.
- The existing frontend scaffold (`platform-admin/tenants/page.tsx`, `mock-tenants.ts`) exists and
  must be rewired, not rebuilt.

**PADASH-2-specific**
- `GET /api/v1/ledger/dashboard` (PAY-3, tenant-scoped) shipped — met. PADASH-2 needs a **new**,
  separate cross-tenant bypass method; it must not reuse this endpoint with the tenant filter
  stripped at the controller layer.
- `AUDIT-2`/`AUDIT-3` shipped (MVP-019) — met. ADR-014's interim viewer allowlist
  (`TENANT_ADMIN`/`READ_ONLY_AUDITOR` only) is a tenant-level access decision with no bearing on
  Platform Admin's separate, platform-level query path — it does not need to be touched or
  widened.
- No new `payment`/`ledger_entry`/`audit_log` table or column is needed — confirmed by both the
  issue's own "Database requirements: None new" line and the database-architect review (§8).

## 4. User flows

### 4.1 Tenant list / approval queue (PADASH-1)

1. Platform Admin logs in and navigates to `Platform Admin > Tenants`.
2. The list loads via `GET /api/v1/platform-admin/tenants`, filterable by status, paginated,
   showing tenant name/subdomain/status/requested plan/submission date on every row (already the
   scaffold's shape).
3. Platform Admin opens a pending tenant's detail (`GET /api/v1/platform-admin/tenants/{id}`),
   reviews profile/contact/plan, and approves or rejects via a confirmation dialog that visibly
   names the tenant.
4. Approving a `PENDING_APPROVAL` tenant atomically: flips status to `TRIAL`, provisions default
   branding/plan config, and writes exactly one audit row (actor = Platform Admin, tenant =
   target, before/after status, timestamp) — all in one transaction.
5. Rejecting a `PENDING_APPROVAL` tenant sets status to `REJECTED`, provisions no login path,
   writes exactly one audit row.
6. Two Platform Admins concurrently processing the same pending tenant: the second submission gets
   a clean "already processed" conflict response, never a silent no-op or a duplicate audit row.
7. A non-Platform-Admin actor attempting any of these endpoints is rejected server-side, regardless
   of client-side UI state.

**Suspend/cancel of an already-`ACTIVE`/`TRIAL` tenant is treated as out of scope for this
module's first slice** (§6) — see §21 item 1 for why.

### 4.2 Cross-tenant payment dashboard (PADASH-2)

1. Platform Admin navigates to `Platform Admin > Payments`.
2. A platform-wide, ledger-derived, paginated list/summary renders, most-recent-first, with every
   row/bucket carrying its own `tenantId`/tenant name — no row blends two tenants' amounts.
3. Platform Admin drills into a single tenant's payment/ledger history
   (`GET /api/v1/platform-admin/payments/tenants/{tenantId}`); a persistent, non-dismissible
   tenant-context banner names that tenant for the entire duration of the drill-down (surviving
   pagination/filter changes within that context).
4. The dashboard and drill-down are read-only — no refund/adjustment action is reachable from
   here; any such action stays on the existing, already-reviewed tenant-scoped payment/refund
   endpoints.

### 4.3 Platform audit log + per-tenant drill-down (PADASH-2)

1. Platform Admin navigates to `Platform Admin > Audit Log`.
2. A platform-level, paginated list renders (initially: tenant-approval actions, once TEN-2 exists
   and is audited — see §16), filterable by date range/action/tenant, with no update/delete
   affordance anywhere in the DOM or API surface.
3. Drilling into one tenant's audit trail
   (`GET /api/v1/platform-admin/audit-log/tenants/{tenantId}`) renders the same persistent,
   non-dismissible tenant-context banner as §4.2 step 3, for the duration of that drill-down.
4. This query path is structurally distinct from `AuditLogQueryService`'s tenant-scoped,
   `ADR-014`-allowlisted query — the two are never the same method or reachable by the same role.

### 4.4 Negative flows (cross-cutting)

- A tenant-scoped JWT (any `Role` enum value) presented against any `/api/v1/platform-admin/**`
  endpoint is rejected — this reciprocal case (the mirror of the already-implemented "platform-admin
  token rejected outside `/api/v1/platform-admin/**`" check) must be explicitly tested, not assumed
  symmetric.
- An illegal tenant-status transition attempted through the live endpoint (e.g. `REJECTED → *`,
  `PENDING_APPROVAL → SUSPENDED`) is rejected server-side with no partial mutation and no audit row.
- A cross-tenant read filtered/searched to a value that only matches another tenant's data returns
  zero rows, never a distinguishing error and never a leaked row.

## 5. Acceptance criteria

### PADASH-1

1. An authenticated Platform Admin reaches `GET /api/v1/platform-admin/tenants` and
   `GET /api/v1/platform-admin/tenants/{id}` and gets `200` with the expected data.
2. Any non-Platform-Admin actor (tenant-scoped role, or unauthenticated) is rejected server-side on
   every list/detail/mutating endpoint — never a client-side-only redirect.
3. A tenant-scoped JWT presented against a `/api/v1/platform-admin/**` path is rejected.
4. The list/detail/approval endpoints use an explicitly-named `@Query` repository bypass method
   (following the `PaymentRepository`/`NotificationOutboxRepository` `AcrossTenants` precedent
   where genuinely cross-tenant; `Tenant`'s own repository correctly stays a plain
   `JpaRepository`, since `Tenant` is not `TenantOwned` and has no structural filter to bypass).
5. The bypass/mutation path's Platform-Admin-only gate is enforced at the service layer that calls
   it, independent of and in addition to the controller's `@PreAuthorize`.
6. Every row shows the tenant name and a stable identifier; no approve/reject action is
   submittable without the target tenant visibly named next to (or within the same
   row/dialog as) the control.
7. List is filterable by status, paginated; a true zero-data state is a distinct empty state from
   "no tenants match the current filter/search."
8. Approving a `PENDING_APPROVAL` tenant atomically flips status, provisions default config, and
   writes exactly one audit row; a partial failure rolls back the whole operation.
9. Rejecting a `PENDING_APPROVAL` tenant sets `REJECTED`, provisions no login path, writes exactly
   one audit row.
10. An illegal transition is rejected server-side, never silently coerced.
11. Two Platform Admins concurrently processing the same pending tenant: the second gets a `409`,
    never a silent no-op or duplicate audit row.

### PADASH-2

12. `ledger-settlement-management` and `audit-log-management` each gain an explicitly-named,
    `@Query`-annotated cross-tenant read method — never a `TenantAwareRepository` finder, never a
    live join blending tenant A's and tenant B's rows into one row with no tenant attribution.
13. The platform-level audit query is structurally distinct from `AuditLogQueryService`'s
    tenant-scoped, `ADR-014`-allowlisted query.
14. Every row/bucket returned by either bypass method carries its own `tenantId`/tenant-name
    discriminator; per-tenant attribution is retained even in a summary/aggregate view.
15. Drilling from the platform-wide view into a single tenant's payment or audit data renders a
    persistent, non-dismissible banner naming that tenant for the entire drill-down duration,
    surviving in-context navigation (pagination/filter changes).
16. No refund/adjustment/other state-changing action is submittable from the cross-tenant dashboard
    without the target tenant visibly named — and any such action must still independently satisfy
    `.claude/rules/payments.md`'s append-only/no-mutation-of-terminal-rows rules regardless of what
    this dashboard's UI implies.
17. The dashboard/audit views are read-only: no `ledger_entry`, `Payment`, `Order`, or `audit_log`
    write/update/delete path is introduced anywhere in this module.
18. Both new bypass methods are reachable only by an authenticated Platform Admin, enforced at the
    service layer, independent of the controller-level gate.

### Cross-cutting

19. Every criterion above touching a cross-tenant endpoint has an accompanying mandatory
    cross-tenant/platform-admin-only negative test.
20. No `DomainArea`/`Role`/`PermissionCheckService` change is introduced anywhere in this module.

## 6. Out-of-scope items

- **Any "act as tenant" impersonation UI or backend session**, including a disabled placeholder
  button — if/when built, it must be a distinct backend-issued session with dual-identity audit
  logging (start + end) and a non-dismissible visual mode indicator, per
  `docs/requirements/user-roles-and-permissions.md` §5. Nothing in this module builds any part of
  it.
- **Suspend/cancel of an already-`ACTIVE`/`TRIAL` tenant.** The issue's own frontend requirement
  names only "Tenant List/Approval Queue," not a status-control screen for already-active tenants.
  Building this now would force MVP-004 §21 item 9's still-unresolved session-invalidation
  mechanism decision into this module. Deferred as a named follow-up (§21 item 1).
- **Reactivation of a cancelled/suspended tenant** — no transition exists for this in
  `TenantStatusService`; not built here (MVP-004 §6).
- **Rejection-reason capture / applicant notification on tenant rejection** — unspecified anywhere
  (MVP-004 §21 item 7); not invented here.
- **A ledger-derived currency/revenue total** anywhere in the cross-tenant payment dashboard. No
  endpoint anywhere in this codebase computes a currency sum from `ledger_entry` (MVP-015's own
  grounding note already established this for the tenant-scoped dashboard). A client-side `SUM`
  across paginated cross-tenant rows would violate the "ledger is the source of truth, never
  separately computed" rule. This module ships a paginated, per-tenant-attributed entry list, not
  a total.
- **A tenant-profile card** (name/plan/status) beyond the bare name/status already on the tenant
  list/detail and the tenant-context banner — no `GET /api/v1/tenants/me`-equivalent read exists,
  and building a richer one is a separate `tenant-management` `api`-extension decision.
- **Any new `docs/api/platform-admin-dashboard.md` file** — per `docs/api/README.md`'s one-file-
  per-owning-domain convention, new endpoints are documented in each owning domain's existing
  (or, for `tenant-management`, first) contract file, not a new file per consuming UI.
- **A cross-tenant revenue/settlement reporting feature** — Phase 2/3 `reporting-analytics`/
  `ledger-settlement-management` scope, not this module's.
- **Platform-level branding/theme management, custom-domain approval, plan/entitlement
  configuration** — not named in either story.

## 7. Domain model

No new aggregate in any domain. This module adds:

- **`tenant-management`**: a new domain event, `TenantStatusChangedEvent` (`tenantmanagement.api`),
  published by the new `TenantApprovalService` on every transition, carrying `tenantId`, `actorId`
  (the Platform Admin), `previousStatus`, `newStatus`, `occurredAt` — consumed by
  `audit-log-management` (and, if wanted later, `notification-management`) as an event, never
  called into directly, per `.claude/rules/architecture.md`'s event-consumer rule for those two
  domains. `Tenant` gains one new, narrow, package-private mutator (e.g.
  `applyStatusTransition(TenantStatus next)`) — not a public `setStatus` — to preserve its existing
  encapsulation intent while allowing the new service to persist a transition.
- **`ledger-settlement-management`**: a new response DTO family, e.g.
  `PlatformLedgerEntryResponse(UUID id, UUID tenantId, String tenantName, UUID orderId, UUID
  paymentId, LedgerEntryType entryType, BigDecimal amount, UUID reversesEntryId, Instant
  createdAt)` — a genuinely new DTO, not a reuse of the existing tenant-scoped
  `LedgerHistoryEntryResponse` (which has no tenant field and must not be widened for this use, per
  `docs/api/ledger-settlement-management.md`'s approved-contract discipline).
- **`audit-log-management`**: a new response DTO, e.g. `PlatformAuditLogEntryResponse`, identical
  in shape to the existing `AuditLogEntryResponse` plus a mandatory `tenantId`/`tenantName` field.
- **`tenant-management.api`**: one new, narrow, additive `TenantLookupApi` method (e.g.
  `resolveTenantSummaries(Set<UUID> tenantIds)` returning `TenantSummary(UUID id, String name,
  TenantStatus status)`), needed by both `ledger-settlement-management` and `audit-log-management`
  to resolve tenant display names for their cross-tenant responses — following the same disclosed-
  additive-`api`-extension precedent as `CourseLookupApi`/`EnrollmentAccessApi`'s prior narrow
  extensions, not a foreign-domain repository/entity import.

## 8. Database design

**No new table and no schema/column change** — confirmed independently by the database-architect
review against the actual current schema, correcting the backlog's "None new" claim only on the
index question below.

**One new, additive migration required for PADASH-2** (next available version: `V31`), because
every existing index on `payment`, `ledger_entry`, and `audit_log` leads with `tenant_id` (correct
for every existing tenant-scoped query, but none of them serve a genuinely cross-tenant, unfiltered
`ORDER BY <timestamp> DESC` scan efficiently as these append-only tables grow platform-wide, not
per-tenant):

```sql
-- V31__add_platform_admin_cross_tenant_dashboard_indexes.sql (DESIGN DRAFT — do not create
-- until this plan is approved)
--
-- PADASH-2 introduces the first genuinely cross-tenant, non-tenant-scoped read pattern
-- against payment, ledger_entry, and audit_log. Every existing index on these tables leads
-- with tenant_id and does not serve an unfiltered ORDER BY <timestamp> DESC scan. Purely
-- additive - no table, column, or existing index changes. tenant_id is a trailing column
-- (not a covering INCLUDE clause, for broad PostgreSQL version compatibility) so the
-- platform dashboard's required per-row tenant attribution can be read from the index
-- without an extra heap fetch.

CREATE INDEX idx_payment_created_at_tenant
    ON payment (created_at DESC, tenant_id);

CREATE INDEX idx_ledger_entry_created_at_tenant
    ON ledger_entry (created_at DESC, tenant_id);

CREATE INDEX idx_audit_log_occurred_at_tenant
    ON audit_log (occurred_at DESC, tenant_id);
```

**No index is added for `tenant` name/subdomain search** (PADASH-1). The existing
`idx_tenant_status_created_at (status, created_at DESC)` already serves the queue's primary
"filter by status, sort by date" shape. A server-side `ILIKE '%...%'` search (if built) cannot use
a plain B-tree index regardless (leading wildcard) and would need a `pg_trgm` extension + GIN index
— not justified at current/near-term `tenant` row counts. Flagged as a follow-up, not built now
(§21 item 5).

**No new table needed for "who viewed which tenant's drill-down"** — a read-only view is not on
`.claude/rules/security.md`'s mandatory-audit-action list, and the issue does not require logging
platform-admin *views* (only mutations/approvals). If a future compliance requirement wants this,
it is new, explicitly-scoped work.

## 9. Backend design

Per `.claude/rules/architecture.md`'s per-domain package structure; each of the three touched
domains gets its own thin, coarsely-gated controller + service, mirroring
`AuditLogQueryService`/`LedgerQueryService`'s existing style exactly.

### 9.1 `com.lms.tenantmanagement` (PADASH-1)

```
tenantmanagement
|-- api        # + TenantStatusChangedEvent, + TenantLookupApi.resolveTenantSummaries(...)
|-- web        # + PlatformAdminTenantController (new)
|-- service    # + TenantApprovalService (new); TenantStatusService unchanged (reused as-is)
|-- domain     # Tenant + new package-private applyStatusTransition(TenantStatus)
`-- repository # TenantRepository + findAll(Specification<Tenant>, Pageable)/findByStatus
               #   (plain JpaRepository methods — no "AcrossTenants" naming needed, since
               #   Tenant is not TenantOwned and has no structural tenant filter to bypass)
```

- `PlatformAdminTenantController`: `@RequestMapping("/api/v1/platform-admin/tenants")`,
  `@PreAuthorize("hasRole('PLATFORM_ADMIN')")` at class level. `GET` (list, status filter +
  pagination), `GET /{id}` (detail), `POST /{id}/approve`, `POST /{id}/reject`.
- `TenantApprovalService`: loads `Tenant` by id, calls the existing, unmodified
  `TenantStatusService.nextStatus(current, transition)`, persists via the new
  `applyStatusTransition` mutator, publishes `TenantStatusChangedEvent`, all inside one
  `@Transactional` service method (satisfies the "atomic status flip + audit row" requirement via
  `audit-log-management`'s synchronous, same-thread `@EventListener` consuming this event — the
  same pattern MVP-019 already established for `CoursePriceChangedEvent`/`MaterialDeletedEvent`,
  which propagates a listener exception back through `publishEvent` to roll back the source
  mutation).
- Re-confirms the Platform-Admin-only gate a second time inside the service, independent of the
  controller's `@PreAuthorize` (mirrors MVP-004 §15 item 7's "the bypass method must itself require
  Platform Admin authentication at the service layer that calls it").
- Concurrent-approval race: the underlying update must use a DB-level guard (optimistic lock via
  `@Version`, or a conditional `UPDATE ... WHERE status = :expected` checked for zero-rows-affected)
  — not a check-then-update at the service layer alone, which cannot close this race.

### 9.2 `com.lms.ledgersettlementmanagement` (PADASH-2, payment dashboard)

The cross-tenant payment dashboard is housed in `ledger-settlement-management`, not
`payment-management` — mirroring the already-shipped tenant-scoped precedent exactly (the existing
tenant-admin "Payment Dashboard" is ledger-derived, never `order`/`payment.status`-derived, per
`.claude/rules/payments.md` §1).

```
ledgersettlementmanagement
|-- repository # LedgerEntryRepository + findAllAcrossTenantsForPlatformReport(UUID tenantId, Pageable)
|              #   (@Query-annotated JPQL/native method — MUST be @Query, not a default method,
|              #   since TenantContext is never resolved on /api/v1/platform-admin/** requests)
|-- service    # + PlatformAdminLedgerQueryService (new, sibling to LedgerQueryService)
`-- web        # + PlatformAdminLedgerController (new)
```

- `findAllAcrossTenantsForPlatformReport(@Param("tenantId") UUID tenantId, Pageable pageable)`:
  `tenantId == null` → platform-wide; non-null → the drill-down case. Ordered by `createdAt DESC`,
  backed by the new `V31` index.
- `PlatformAdminLedgerQueryService`: re-confirms Platform-Admin-only at the service layer, resolves
  tenant display names via the new `TenantLookupApi.resolveTenantSummaries` batch call (never by
  importing `tenantmanagement.domain.Tenant`), maps to `PlatformLedgerEntryResponse`.
- `PlatformAdminLedgerController`: `@RequestMapping("/api/v1/platform-admin/payments")`,
  `@PreAuthorize("hasRole('PLATFORM_ADMIN')")`. `GET /dashboard` (platform-wide),
  `GET /tenants/{tenantId}` (drill-down; `404` for an unknown tenant id, validated against
  `tenant-management`, never a silent empty list masquerading as "valid tenant, no data").
- No new write method is added to `LedgerEntryApi`/`PaymentConfirmationApi`/`SlipStatusApi` — this
  module is read-only with respect to `payment`/`order`/`ledger_entry`/`payment_slip`/
  `payment_refund`, structurally guaranteed by `LedgerEntryRepository`'s existing append-only
  delete-method overrides.

### 9.3 `com.lms.auditlogmanagement` (PADASH-2, platform audit log)

```
auditlogmanagement
|-- repository # AuditLogRepository + findAllAcrossTenantsForPlatformReport(UUID tenantId, ..., Pageable)
|              #   (@Query-annotated — same TenantContext reasoning as 9.2; existing delete-method
|              #   overrides and append-only guarantee are untouched)
|-- service    # + PlatformAdminAuditLogQueryService (new, sibling to AuditLogQueryService — no
|              #   VIEWER_ALLOWED_ROLES-style allowlist needed here: that mechanism exists because
|              #   several tenant-scoped roles share one coarse grant; Platform Admin has exactly
|              #   one role and no coarse grant to narrow, so hasRole('PLATFORM_ADMIN') alone is
|              #   the correct, non-redundant gate)
`-- web        # + PlatformAdminAuditLogController (new)
```

- `PlatformAdminAuditLogController`: `@RequestMapping("/api/v1/platform-admin/audit-log")`,
  `@PreAuthorize("hasRole('PLATFORM_ADMIN')")`. `GET` (platform-wide, filterable by date
  range/action), `GET /tenants/{tenantId}` (drill-down).
- Structurally distinct code path from `AuditLogQueryService`/`AuditLogController` — different
  class, different repository method, different (and simpler) authorization check. A regression
  test must prove the two are not accidentally merged.

### 9.4 Authorization mechanism (applies to all three controllers above)

`@PreAuthorize("hasRole('PLATFORM_ADMIN')")` at the controller, backed by the already-shipped
`ROLE_PLATFORM_ADMIN` `GrantedAuthority` (`JwtAuthenticationFilter`, line ~125). This is not a novel
mechanism — `LedgerController.getHistory` already uses the structurally identical
`@PreAuthorize("hasRole('STUDENT')")` for a role outside the `DomainArea` matrix. No allowlist
layer (mirroring `AuditLogQueryService.VIEWER_ALLOWED_ROLES`) is needed or appropriate here, since
that pattern exists specifically to narrow *among several roles sharing one coarse grant* — a
distinction that doesn't apply to a single, ungranted Platform Admin role. `PermissionCheckService`,
`DomainArea`, and `Role` must not be modified to accommodate Platform Admin under any
circumstance.

Role-gate passing is necessary but not sufficient on its own for the specific mutation/read (per
`.claude/rules/payments.md` §8's principle, applied here): tenant-status transitions must still
independently pass `TenantStatusService`'s state-machine validation, and every cross-tenant read
must still go through its own explicitly-named bypass method — the role gate authorizes "may call a
platform-level endpoint," not "this specific transition/query shape is valid."

### 9.5 Module-boundary check

No domain reaches into another's `repository`/`domain` classes. The one new cross-module
dependency is the additive `TenantLookupApi.resolveTenantSummaries` method (§7), consumed by
`ledger-settlement-management` and `audit-log-management` — a disclosed, narrow `api` extension,
not a repository/entity import, consistent with `.claude/rules/architecture.md`.

## 10. API contract

Draft only — must be finalized via the `review-api-contract` skill into `docs/api/tenant-
management.md` (**new file** — none exists yet for this domain, despite MVP-004 having drafted one
that was never finalized since TEN-2's endpoints were deferred), and additive sections in
`docs/api/ledger-settlement-management.md` and `docs/api/audit-log-management.md`, before
implementation starts on either side.

| Method + path | Auth | Notes |
|---|---|---|
| `GET /api/v1/platform-admin/tenants` | `hasRole('PLATFORM_ADMIN')` | Filterable by `status`, paginated. Distinguishes zero-data vs. filtered-empty via response metadata. |
| `GET /api/v1/platform-admin/tenants/{id}` | `hasRole('PLATFORM_ADMIN')` | Full profile/contact/plan detail. `404` if unknown. |
| `POST /api/v1/platform-admin/tenants/{id}/approve` | `hasRole('PLATFORM_ADMIN')` | Atomic status flip + default-config provisioning + one audit row. `409` if not currently `PENDING_APPROVAL` (stale-state / concurrent-processing case). |
| `POST /api/v1/platform-admin/tenants/{id}/reject` | `hasRole('PLATFORM_ADMIN')` | Same atomicity/audit pattern. |
| `GET /api/v1/platform-admin/payments/dashboard` | `hasRole('PLATFORM_ADMIN')` | Cross-tenant, paginated, `PlatformLedgerEntryResponse[]`, most-recent-first. |
| `GET /api/v1/platform-admin/payments/tenants/{tenantId}` | `hasRole('PLATFORM_ADMIN')` | Single-tenant drill-down; `404` for an unknown tenant id. |
| `GET /api/v1/platform-admin/audit-log` | `hasRole('PLATFORM_ADMIN')` | Platform-level, paginated, filterable by date range/action, `PlatformAuditLogEntryResponse[]`. No `PUT`/`PATCH`/`DELETE` route exists. |
| `GET /api/v1/platform-admin/audit-log/tenants/{tenantId}` | `hasRole('PLATFORM_ADMIN')` | Single-tenant drill-down. |

All responses use the existing `com.lms.common.api.ApiResponse<T>`/`PageResponse<T>` envelope. No
endpoint above accepts `tenantId` as a request-body field on a mutating call — the tenant id is
always the path variable, resolved server-side and validated to exist. The `tenantId` path
parameter on the two drill-down `GET` endpoints is the one legitimate, explicitly-reviewed
exception to "never trust a client-supplied tenant id": it is *which tenant a verified Platform
Admin chooses to inspect*, not an attempt by a tenant's own user to claim a different tenant's
identity — the two are structurally different actors and different rules.

## 11. Frontend screens

All under `app/(platform-admin)/`. Loading/empty/error/permission-denied states and responsive
behavior are mandatory per `frontend/CLAUDE.md` on every screen below.

| Screen | Route | Key components | Notes |
|---|---|---|---|
| **Tenant List / Approval Queue** | `platform-admin/tenants` (rewire existing scaffold) | Table/card responsive pair (already correct in the scaffold) + Status filter/search (existing) + row Approve/Reject actions + `AlertDialog` confirmation | Live `useQuery`/`useMutation`, replacing `MOCK_TENANTS`; every row and every dialog names the tenant; Reject is destructive-severity; handle `409` "already processed" inline, not as a generic error |
| **Tenant Approval Detail** | `platform-admin/tenants/[tenantId]` | Read-only profile/contact/plan summary + Approve/Reject `AlertDialog`s | Single-tenant view; whether it needs the full persistent tenant-context banner (vs. just the tenant name in its own header) is an open UX question (§21 item 6) |
| **Cross-Tenant Payment Dashboard** | `platform-admin/payments` (new) | New shared `DataTable`-based table/card pair; `PageHeader showTenantContext={false}` | Tenant name/id column on every row; distinct empty states ("no payments recorded platform-wide" vs. "no results for this filter") |
| **Tenant Payment Drill-down** | `platform-admin/payments/[tenantId]` (new) | `Breadcrumbs` + `PageHeader showTenantContext tenantContext={...}` (**first implementation of both components**) | Persistent, non-dismissible banner for the full drill-down; no mutation control anywhere |
| **Platform Audit Log** | `platform-admin/audit-log` (new) | Same `DataTable`/`Accordion`/`DateInput`/`live-region` components MVP-019 built, reused not reimplemented | Two distinct empty states (mirroring MVP-019 §11 exactly); zero update/delete affordance in the DOM |
| **Tenant Audit Log Drill-down** | `platform-admin/audit-log/[tenantId]` (new) | `Breadcrumbs` + `PageHeader showTenantContext` | Same banner requirement as the payment drill-down |

**New shared components (first implementation, per `docs/ui-ux/component-library-spec.md`):**
- `frontend/src/components/ui/page-header.tsx` — `title`, `description?`, `actions?`,
  `showTenantContext?: boolean`, `tenantContext?: { id, name, status }`. The
  `showTenantContext` strip renders with **no dismiss/close affordance** in the DOM — enforced
  structurally by the component, not by convention.
- `frontend/src/components/ui/breadcrumbs.tsx` — chevron-separated links, current page as
  non-interactive text with `aria-current="page"`.

**Navigation**: add "Payments" (`/platform-admin/payments`) and "Audit Log"
(`/platform-admin/audit-log`) to `components/layout/nav/platform-admin-nav.tsx` (currently:
Dashboard, Tenants, Profile, Settings).

**Existing components reused, not reimplemented**: `EmptyState`/`ErrorState`/`LoadingState`/
`PermissionDeniedState`/`QueryStateBoundary` (`components/states/`), `AlertDialog`
(`components/ui/alert-dialog.tsx`, same primitive `LogoutControl` already proves works with an
async mutation), `status-badge.tsx`'s existing icon+text (not color-alone) status treatment.

**Design-system gap carried forward, not resolved here**: `status-badge.tsx`'s own doc comment
confirms the missing `Trial` Status Chip variant / `Pending`/`Pending Approval` ambiguity (MVP-004
§21 item 12) is still open, blocked on an unresolved `success`/`warning` semantic-token decision —
do not invent a color/icon here.

## 12. Validation rules

- Approve/reject request bodies (if any) never accept `tenantId`/`targetStatus`/`status` fields —
  the target tenant comes only from the path variable; the resulting status comes only from which
  endpoint was called, never from client input.
- Tenant-status transitions are validated exclusively by `TenantStatusService`'s existing
  transition graph — no code path may write a status value outside it or attempt an
  undocumented transition (e.g. `SUSPENDED → ACTIVE`, which remains an explicitly unresolved
  decision, §21 item 1).
- `page`/`size` on all list endpoints: standard platform pagination validation (size clamped to
  100, page ≥ 0), matching the existing convention in `AuditLogController`/`LedgerController`.
- Date-range filters on the platform audit log (`from`/`to`): `from` must not be after `to`
  (`400`), mirroring `AuditLogSearchCriteria`'s existing validation.
- The `tenantId` path parameter on both drill-down endpoints must resolve to a real tenant (`404`
  otherwise) before any query executes — never silently treated as "valid but empty."

## 13. Error cases

| Case | Expected behavior |
|---|---|
| Non-Platform-Admin actor on any endpoint in this module | `403`, server-side, always |
| Unauthenticated request | `401` |
| Tenant-scoped JWT presented against a `/api/v1/platform-admin/**` path | Rejected (verify exact layer — see §21 item 3) |
| Approve/reject on a tenant not currently `PENDING_APPROVAL` (stale state / already processed) | `409`, no mutation, no audit row |
| Illegal status transition attempted | Rejected server-side (`409`/`422`), no partial mutation |
| Two Platform Admins process the same pending tenant concurrently | Exactly one succeeds; the other gets `409`, not a silent no-op or duplicate audit row |
| Drill-down `tenantId` doesn't resolve to a real tenant | `404` |
| Cross-tenant filter/search value matches only another tenant's data | `200` with empty result — never a distinguishing error, never a leaked row |
| Attempt to reach `PUT`/`PATCH`/`DELETE` on the platform audit-log route | No such route exists — `404`/`405` at the routing layer |
| Approve/reject audit-write fails mid-transaction | Exception propagates; the status change rolls back entirely — no partial state |

## 14. Tenant-isolation rules

- **`tenant` is platform-level, not tenant-owned** — the standard cross-tenant test pattern doesn't
  apply to it directly (no `tenant_id` column to test); its isolation obligation is instead
  "platform-admin-only bypass-method enforcement": the approval queue is inherently cross-tenant by
  design, and no tenant-scoped role may reach it (403/404), consistent with
  `docs/architecture/multi-tenancy.md` §2's canonical bypass pattern.
- **`payment`/`ledger_entry`/`audit_log` are genuinely tenant-owned** — every new cross-tenant read
  in this module uses an explicitly-named, `@Query`-annotated bypass method (never a
  `TenantAwareRepository`-derived finder), and every returned row carries its own `tenantId`/tenant
  name so isolation is provable per-row, not just "the query has a filter."
- **No repository method in this module accepts a caller-supplied `tenant_id` as an implicit
  scoping parameter** for a normal tenant request — the one sanctioned exception is the drill-down
  `tenantId` path parameter, which is explicitly reviewed, gated by `hasRole('PLATFORM_ADMIN')`,
  validated against a real tenant, and consumed only by the explicitly-named bypass method (§10).
- **Mandatory cross-tenant negative tests, per new endpoint** (§18): a tenant-scoped JWT of any
  role gets `403`/`404`, never `200` with empty/filtered data; a genuine two-tenant fixture proves
  the cross-tenant read never mixes tenant A/B rows into one blended aggregate, and every returned
  row's `tenant_id` matches its seeded origin.
- **The persistent tenant-context banner is a UX control, not a security control** — the backend
  query must be provably scoped to the named tenant regardless of what the frontend displays; the
  banner never substitutes for server-side enforcement, per `docs/architecture/multi-tenancy.md`
  §3.

## 15. Security rules

1. **Every new endpoint gated by `@PreAuthorize("hasRole('PLATFORM_ADMIN')")`** — never routed
   through `PermissionCheckService`/`DomainArea`/`Role`, and neither of those types may be modified
   to accommodate Platform Admin under any circumstance (§9.4).
2. **Role-gate passing is necessary but not sufficient** — tenant-status transitions must
   independently pass `TenantStatusService`'s state machine; cross-tenant reads must independently
   go through their own explicitly-named bypass method, mirroring `.claude/rules/payments.md` §8's
   "a coarse grant is not sufficient authorization for the specific mutation/read" principle,
   applied here to a role gate rather than a domain-area grant.
3. **Reverse-JWT-replay must be explicitly verified, not assumed symmetric.** The already-shipped
   `JwtAuthenticationFilter` rejects a platform-admin token used outside `/api/v1/platform-admin/**`
   (line ~111). The reverse case — a tenant JWT hitting a platform-admin route — is not guarded by
   that same explicit `if`; whether it's structurally blocked depends on `TenantResolutionFilter`'s
   routing rules for that path prefix (a tenant JWT would need `TenantContext` to resolve, which it
   won't on an excluded path, likely producing a `401` via `TenantContextNotResolvedException`, but
   this must be confirmed with a real integration test, not assumed). A repo search during review
   found an existing `CrossRolePlatformAdminTokenReplayIntegrationTest` — **confirm and, if needed,
   extend this test class to cover the reverse direction explicitly**, rather than writing a
   parallel test class.
4. **No implicit impersonation.** No design element in this module may reuse or mint a tenant
   session/JWT from the Platform Admin's own session, invoke a tenant-scoped service method
   directly "for convenience" bypassing the explicit bypass methods, or deep-link into the
   tenant-admin app's own routes with a tenant pre-selected — each would informally reconstruct
   impersonation without its required dual-identity audit trail (§6, risk register R9).
5. **Every cross-tenant response row carries an explicit tenant discriminator** — an aggregate that
   sums/groups without a leading `tenant_id` key, or a DTO that omits `tenantId`, is a defect, not
   a stylistic choice (§14).
6. **The drill-down `tenantId` path parameter is validated against a real tenant and consumed only
   by the explicitly-named bypass method** — never treated as a hint that other filter/pagination
   parameters could widen.
7. **Tenant-status-transition state machine rejects invalid transitions server-side**, reusing
   `TenantStatusService`'s existing, unmodified transition graph and DB `CHECK` constraint on the
   `status` enum — no code path in this module may write a status value outside it.
8. **Approval/rejection is atomic**: status flip + provisioning (approve only) + audit row in one
   transaction, proven by a dedicated rollback test per action (mirroring
   `PaymentConfirmationRollbackIntegrationTest`'s technique).
9. **Concurrent-approval race is closed with a DB-level guard** (optimistic lock or conditional
   `UPDATE ... WHERE status = :expected`), not a service-layer check-then-update alone — this test
   was explicitly left open by MVP-004 (§21 item 11) precisely because TEN-2 didn't exist yet to
   test against; it must be closed here.
10. **No real institute/contact/financial data in test fixtures** — synthetic data only, per root
    `CLAUDE.md`.

## 16. Audit requirements

1. Every tenant approval and rejection writes **exactly one audit row** in the same transaction as
   the status change (actor id = Platform Admin, tenant id = target tenant, action type, before/
   after status, timestamp) — a same-transaction write via the `TenantStatusChangedEvent` →
   `AuditLogEventListener` path (mirroring MVP-019's exact synchronous, same-thread
   `@EventListener` pattern, never `@TransactionalEventListener(AFTER_COMMIT)`), never a separately
   triggered, skippable call.
2. This is the same class of privileged, state-changing admin action already on
   `.claude/rules/security.md`'s mandatory-audit list (price changes, payment approvals/
   rejections, access extensions) even though "tenant approval" isn't named verbatim — MVP-004's
   plan already established this requirement; this module implements it, and does not silently
   re-litigate whether it's required.
3. Tenant-approval audit rows remain platform-level-actor / tenant-target rows, queryable through
   the new platform-level audit-log query path (§9.3) — no tenant-scoped role (including the
   approved tenant's own Tenant Admin) can list/search them via the tenant-scoped Audit Log Viewer.
4. Audit rows are append-only — no update/delete endpoint or repository method may target them
   (re-verified, not newly built, since `AuditLogRepository`'s structural guarantee already covers
   any row this module writes).
5. **Viewing the cross-tenant dashboard or audit log (a pure read) is not itself a mandatory-audit
   action** under current rules — `.claude/rules/security.md`'s list covers mutations, not reads.
   This module does not invent a new "viewed tenant X's data" audit obligation. If a future
   compliance requirement wants one, it is new, explicitly-scoped work, not an implicit extension
   of this module.
6. `AuditLogRepository`'s existing append-only enforcement (every delete-shaped method overridden
   to throw) is re-verified by a regression test against the new platform-level bypass method
   specifically, not assumed inherited.

## 17. Payment impact

**Read-only; no mutation of any kind.** Confirmed structurally (not just by convention):
`LedgerEntryRepository` and `PaymentRepository` already override every delete-shaped method to
throw `UnsupportedOperationException`, and per `.claude/rules/architecture.md` this module (in
whichever domain package it lands) cannot inject those repositories directly — only their `api`
interfaces, none of which this module extends with a write method.

- No new `LedgerEntryType`, no change to `ledger_entry`'s append-only semantics, no settlement
  logic touched, no new order/payment/refund/slip write path.
- The cross-tenant payment dashboard is **ledger-derived only** (`ledger_entry`, via the new
  `findAllAcrossTenantsForPlatformReport` method), never `payment.status`/`order`-derived,
  consistent with `.claude/rules/payments.md` §1's "ledger is the source of truth for what's paid"
  rule applied at platform scope.
- **No currency/revenue-total figure is in scope** (§6) — the response is a paginated, per-tenant-
  attributed entry list, matching the existing tenant-scoped dashboard's own count-only precedent.
- The persistent tenant-context banner on a payment drill-down is a UI control only; it never
  substitutes for the backend's own tenant-scoping of the query (§14/§15).
- No enrollment-activation code path is touched, read, or implicated anywhere in this module.
- No ADR is required for this module's payment-adjacent work, provided it stays within what's
  reviewed here — a future cross-tenant revenue-total or refund-override capability would each be
  separate, explicitly-scoped changes requiring their own review.

## 18. Tests

Reuses `com.lms.common.AbstractIntegrationTest` (Testcontainers Postgres/Redis, `TENANT_A`/
`TENANT_B` constants, `withTenant(...)`) and `AuthIntegrationTestSupport`'s existing
`seedPlatformAdmin(...)`/`platformAdminLogin(...)` helpers (already used by
`CrossRolePlatformAdminTokenReplayIntegrationTest`) so every test below exercises the real Spring
Security filter chain, not a stubbed principal.

### JUnit unit
- Approve/reject request DTOs never bind a client-supplied `tenantId`/`status` field.
- `PlatformLedgerEntryResponse`/`PlatformAuditLogEntryResponse` shape tests: every instance has a
  non-null `tenantId`/tenant-name field (construction-time enforcement, not just "the query happens
  to populate it").
- No new `TenantStatusService` unit tests expected — its existing table-driven transition-graph
  coverage (MVP-004) is reused as-is, unmodified.

### Spring Boot Testcontainers integration

**Tenant list/approval (PADASH-1):**
- `unauthenticatedRequestToTenantListIsRejectedWith401` (and per mutation endpoint).
- `tenantAdminJwtCannotReachPlatformAdminTenantEndpoints403` — every one of the 4 endpoints, never
  `200` with filtered data.
- `platformAdminTenantListReturnsTenantsAcrossBothTenantAAndTenantB` — proves the bypass method,
  not `TenantAwareRepository`.
- `approvingAPendingTenantFlipsStatusAndWritesExactlyOneAuditRowAtomically` /
  `rejectingAPendingTenantWritesExactlyOneAuditRowAtomically`.
- `aFailureInAuditWriteRollsBackTheTenantStatusChange` (per action) — mirrors
  `PaymentConfirmationRollbackIntegrationTest`'s technique.
- `illegalTransitionAttemptedThroughTheLiveEndpointIsRejectedNotSilentlyAccepted`.
- `twoPlatformAdminsProcessingTheSamePendingTenantConcurrentlyResultsInExactlyOneSuccess` —
  **closes MVP-004 §21 item 11's previously-open race**, requiring a real DB-level guard to pass.
- `emptyPendingApprovalQueueIsDistinguishableFromFilteredToZeroResults`.
- `mutationEndpointIgnoresAClientSuppliedTenantIdInTheRequestBody`.

**Cross-tenant payment dashboard (PADASH-2):**
- `unauthenticatedRequestToPlatformPaymentDashboardIsRejectedWith401` /
  `tenantAdminJwtCannotReachPlatformPaymentDashboard403`.
- `crossTenantPaymentDashboardNeverMixesTenantAAndTenantBAmountsInOneAggregateRow`.
- `platformPaymentDashboardIsReadOnlyAndNeverMutatesUnderlyingPaymentOrLedgerRows` — before/after
  row-count and checksum comparison.
- `platformPaymentDashboardUsesAnExplicitlyNamedBypassDistinctFromTenantScopedLedgerQueries`.
- `tenantDrillDownReturnsOnlyThatTenantsRowsWithCorrectDiscriminator`.
- `drillDownEndpointStillRequiresPlatformAdminRoleRegardlessOfTenantIdMatchingCallersOwnTenant`.
- `drillDownWithANonexistentTenantIdReturns404`.

**Platform audit log (PADASH-2):**
- `unauthenticatedRequestToPlatformAuditLogIsRejectedWith401` /
  `tenantAdminJwtCannotReachPlatformAuditLogEndpoint403`.
- `platformAuditLogListsActionsAcrossBothTenantsWithCorrectTenantDiscriminatorPerRow` — including a
  colliding-action/target-id-across-two-tenants fixture (mirroring
  `AuditLogCrossTenantIntegrationTest`'s existing technique, inverted to prove correct attribution
  rather than correct exclusion).
- `platformAuditLogQueryUsesAnExplicitlyNamedBypassDistinctFromTheTenantScopedAuditLogQueryService`.
- `platformAuditLogDrillDownFiltersToOneTenantWithoutLeakingOtherTenantsRows`.
- `platformAuditLogRepositoryPathExposesNoUpdateOrDeleteMethod` — re-run/extend the existing
  append-only regression test against the new repository method specifically.
- `platformAuditLogControllerRejectsPutPatchAndDeleteWith405OrNotMapped`.
- `platformAuditLogAuthorizationIsRoleBasedNotDomainAreaGrantBased` — a fixture user holding a
  broad `DomainArea.AUDIT_LOG`/`VIEW` grant but lacking `ROLE_PLATFORM_ADMIN` still gets `403`.
- `emptyPlatformAuditLogIsDistinguishableFromFilteredToZeroResults`.

**Reverse-JWT-replay (cross-cutting, §15 item 3):**
- Extend (or confirm coverage in) `CrossRolePlatformAdminTokenReplayIntegrationTest`: a real
  tenant-scoped access token presented against each new `/api/v1/platform-admin/**` endpoint is
  rejected, with the exact rejection layer (401 via unresolved `TenantContext`, vs. 403 via role
  mismatch) asserted explicitly, not assumed.

### Playwright (frontend)
- Tenant name/identifier visible on every row of the tenant list, payment dashboard, and audit log
  tables (query by role/text against real seeded multi-tenant fixture data).
- Persistent tenant-context banner: renders immediately on drill-down, has no dismiss/close
  affordance in the DOM (assert absence), remains present after pagination/filter interaction
  within the same drill-down.
- No destructive action (approve/reject) submittable without the target tenant visibly named in
  both the row and any confirmation dialog.
- No update/delete affordance anywhere in the platform audit log's DOM (assert absence, mirroring
  MVP-019's technique).
- Two distinct empty states (zero-data vs. filtered-to-zero) for the tenant list, payment
  dashboard, and audit log.
- Responsive/mobile-fallback behavior for all three admin-heavy tables (card-list fallback below
  `md`, per `.claude/rules/ui-ux.md` §5), re-verifying tenant-name-per-row and no-mutation-
  affordance guarantees hold at that viewport too.
- Confirmation-dialog keyboard operability and focus trap (native to the shared `AlertDialog`
  primitive — verify, don't assume, once wired to a real async mutation).
- Frontend route-guard check (explicitly annotated as guard-only, not a substitute for the backend
  403 tests above): a Tenant Admin navigating directly to a platform-admin URL sees a
  permission-denied state.

### Named follow-ups (not blocked, but explicitly out of this module's test scope per §6)
- Suspend/cancel tests — deferred with that functionality (§21 item 1).
- A revenue-total aggregation correctness test — deferred with that feature (§6).

## 19. Documentation changes

- **`docs/api/tenant-management.md` (new file)** — the tenant-list/approve/reject endpoints (§10);
  this domain has never had a contract file, since TEN-2's endpoints were deferred until now.
- **`docs/api/ledger-settlement-management.md`** — add the two new platform-admin payment endpoints
  as a new section, explicitly distinguished from the existing tenant-scoped
  `GET /api/v1/ledger/dashboard` documentation.
- **`docs/api/audit-log-management.md`** — add the two new platform-admin audit-log endpoints as a
  new section, explicitly cross-referencing that this is a structurally distinct query path from
  `AUDIT-3`'s tenant-scoped viewer (already documented) and ADR-014's allowlist (which does not
  apply here).
- **`docs/architecture/database-architecture.md`** — record the new `V31` indexes and the
  cross-tenant-scan rationale (§8).
- **`docs/ui-ux/`** — new or extended "Platform Admin dashboard conventions" note: the persistent-
  tenant-name-per-row rule, the non-dismissible tenant-context-banner requirement and exactly which
  screens render it (§11), and the Page Header/Breadcrumbs components' first-implementation notes.
- **`docs/plans/MVP-004 Tenant Management.md`** — update its own status note to record that TEN-2's
  live endpoints (approve/reject only — see §21 item 1) shipped under this module, not a
  standalone Module 4 follow-up, once the scope decision is made.
- **`docs/requirements/open-decisions.md`** — carry forward the still-open items this module does
  not resolve: suspend's session-invalidation mechanism (MVP-004 §21 item 9), cancellation
  reversibility, rejection-reason capture, the revenue-total/tenant-profile KPI gaps, and the
  `tenant` name-search index follow-up (§21 item 5 below).
- **`docs/planning/risk-register.md`** — update R9/R15's status to reflect this module's read-only,
  non-impersonating design and the new mandatory tests that verify it (§18).

## 20. Implementation order

Per root `CLAUDE.md`'s workflow (plan → backend → backend tests → frontend → frontend/E2E tests →
security/tenant/integration review → docs → one logical commit), and `.claude/rules/git-
workflow.md`'s "do not bundle backend and frontend without explicit approval":

1. **Resolve §21's open decisions with the product owner** — item 1 (TEN-2 scope: Option A
   narrowed to approve/reject only, vs. Option B defer PADASH-1 entirely) gates whether steps 2–4
   below happen in this module or a follow-up; item 6 (whether the Tenant Approval Detail screen
   needs the full persistent banner) gates part of step 6.
2. Backend, `tenant-management`: `V31` migration is not needed here (no schema change in this
   domain); `TenantStatusChangedEvent`, `Tenant.applyStatusTransition`, `TenantApprovalService`,
   `PlatformAdminTenantController`, the DB-level concurrency guard.
3. Backend, `ledger-settlement-management` + `audit-log-management`: `V31` migration;
   `TenantLookupApi.resolveTenantSummaries`; the two new `@Query` bypass methods; the two new
   query services; the two new controllers.
4. Backend tests per §18's Testcontainers list, including the concurrent-approval race and the
   reverse-JWT-replay extension. Run `backend\mvnw.cmd verify` — must be green.
5. Frontend: `page-header.tsx`, `breadcrumbs.tsx` (first implementation); rewire the existing
   tenant-list scaffold to live data + approve/reject actions; build the four new screens (§11);
   nav updates.
6. Frontend/E2E tests per §18's Playwright list. Run `npx playwright test` — must be green.
7. **`security-review`**, **`tenant-isolation-review`**, **`review-api-contract`** skills —
   explicit passes confirming: no `PermissionCheckService`/`Role`/`DomainArea` change was
   introduced; every cross-tenant response row carries a tenant discriminator; the reverse-JWT-
   replay case is actually proven, not assumed.
8. **`payment-ledger-review`** skill — confirm the payment dashboard stays ledger-derived,
   read-only, with no revenue-total invented.
9. **`ui-ux-review`** skill — confirm the tenant-context banner's non-dismissibility and exactly
   which screens render it, per the item-6 decision from step 1.
10. **`update-documentation`** skill — §19's file list.
11. Commit as separate, logically-scoped units (e.g. "backend: wire tenant approval endpoints
    (PADASH-1)", "backend: add platform-admin cross-tenant payment/audit-log reads (PADASH-2)",
    "frontend: platform admin tenant list, payment dashboard, audit log (MVP-020)", "docs: document
    platform-admin dashboard endpoints and conventions") — never bundling backend and frontend
    without explicit "full-stack implementation approved."

## 21. Risks and unresolved decisions

**None of these are resolved by this plan — implementation must not silently assume an answer, per
root `CLAUDE.md`'s "do not invent unresolved business decisions" instruction:**

1. **TEN-2 scope decision (central, blocks §9.1/§10/§11's tenant-approval pieces).** The issue and
   backlog assume TEN-2's live endpoints already exist; they do not (Grounding note). Two options,
   neither picked by this plan:
   - **(A)** Build TEN-2's live endpoints — narrowed to **approve/reject of a `PENDING_APPROVAL`
     tenant only** — as part of this module, since the originally-cited blocker (Platform Admin
     auth) is now resolved. Suspend/cancel of an already-active tenant is explicitly excluded from
     this option (§6), since that piece alone drags in MVP-004 §21 item 9's still-unresolved
     session-invalidation-mechanism decision.
   - **(B)** Treat `PADASH-1` as still blocked and ship `PADASH-2` only in this module, filing
     TEN-2's live endpoints as their own follow-up module/PR using MVP-004's own already-drafted
     API design as the starting point.
   - Recommendation surfaced by solution-architect and product-requirements-analyst review (not a
     decision): lean toward (A), narrowed as described — but this requires explicit product-owner
     sign-off before implementation begins, not an assumption baked into the branch.
2. **Suspend's session-invalidation mechanism** (MVP-004 §21 item 9, inherited unchanged) — remains
   open regardless of which option above is chosen, since suspend/cancel are out of scope either
   way for this module's first slice.
3. **Cancellation reversibility** and **rejection-reason capture / applicant notification**
   (MVP-004 §21 items 7–8) — remain open, unaffected by this module.
4. **Reverse-JWT-replay's exact rejection layer** (§15 item 3) — must be confirmed by a real
   integration test (extending the existing `CrossRolePlatformAdminTokenReplayIntegrationTest`),
   not assumed symmetric with the already-implemented forward-direction check.
5. **`tenant` name/subdomain server-side search** — not built in this module (client-side search
   against a fully-fetched list is the interim approach, matching the existing scaffold's current
   behavior); a real server-side search would need a `pg_trgm` extension + GIN index as a separate,
   explicitly-justified follow-up once `tenant` row counts or query-latency data warrant it (§8).
6. **Whether the Tenant Approval Detail screen (PADASH-1) needs the full persistent, non-
   dismissible tenant-context banner**, or whether naming the tenant in its own page header is
   sufficient — the issue's acceptance criteria mandate the banner explicitly only for PADASH-2's
   payment/audit drill-downs. Flagged for explicit `ui-ux-review` sign-off rather than assumed
   either way.
7. **Whether a cross-tenant revenue/currency total is wanted at all**, given no endpoint anywhere
   in this codebase currently computes one from `ledger_entry` — this plan's default is to ship
   without one (§6); building one is new scope requiring its own review.
8. **Design-system gap, carried forward, not resolved here**: the missing `Trial` Status Chip
   variant and `Pending`/`Pending Approval` ambiguity (MVP-004 §21 item 12) — do not invent a
   color/icon in this module either.
9. **Whether tenant-approval audit rows should be added to `.claude/rules/security.md`'s literal
   mandatory-audit-action list** — this plan implements the requirement (§16) without editing that
   rules file itself, consistent with how MVP-004 handled the identical documented gap; recommend a
   separate follow-up to reconcile the two documents.

---

*This plan does not authorize implementation of any item until §21 item 1's TEN-2-scope decision
(and, ideally, item 6's banner-scope question) is explicitly resolved by the product owner.*

## 22. Post-review addendum

Added after four specialist reviewers (security, database, architecture, QA) reviewed the
implemented diff. This section discloses and justifies design/schema decisions made during
implementation that were not called out explicitly earlier in this plan, closing the
"undisclosed schema/design change" governance gap the reviewers raised. None of these required
re-opening §21's still-unresolved product decisions.

1. **`V32__relax_audit_log_actor_fk_for_platform_admin_actors.sql` drops `fk_audit_log_actor`
   entirely, replaced by a service-layer guard, not a schema-level polymorphic FK.** `actor_id`
   became a polymorphic reference (`tenant_user` or `platform_admin_user`) the moment
   `recordForTenant` needed to write a row on behalf of a Platform Admin actor - the same situation
   V21 already accepted for `target_id` (no FK, service-layer validation only). A full
   polymorphic-FK schema redesign (discriminator column, per-table partial FKs, or a shared
   lookup table) was considered and rejected as out of proportion for this fix: it would touch
   every existing audited-action call site across the whole platform, not just this module.
   Instead, `AuditLogService.record`/`recordForTenant` both call a new
   `requireKnownActor(UUID)` guard before persisting, backed by a narrow, additive
   `identityaccessservice.api.UserProvisioningApi#actorExists(UUID)` method (checks `tenant_user`
   when a `TenantContext` is resolved, `platform_admin_user` otherwise, mirroring
   `JwtAuthenticationFilter`'s own dual-path actor resolution) - throwing before
   `entityManager.persist(...)`/`auditLogRepository.save(...)` runs. This is a deliberate,
   reviewed choice to enforce the invariant in the application layer rather than the schema, given
   the disproportionate cost of a full polymorphic-FK redesign.
2. **`Tenant#applyStatusTransition` is `public`, not package-private as earlier planning notes in
   this document implied.** Already justified in the method's own javadoc: its only caller,
   `TenantApprovalService`, lives in the sibling `com.lms.tenantmanagement.service` package - a
   different Java package from `com.lms.tenantmanagement.domain` - so package-private visibility
   would not compile. Mirrors `TenantUser#suspend()`/`#activate()`'s existing style in this
   codebase. No further schema/design change needed; this entry exists only to close the
   plan-level disclosure gap.
3. **`V31`'s `idx_audit_log_occurred_at_tenant` does not cover the platform audit log's `action`
   equality filter.** The index is `(occurred_at DESC, tenant_id)`, chosen to serve the
   cross-tenant, time-ordered scan `PlatformAdminAuditLogQueryService` needs; a query that also
   filters by `action` falls back to a sequential/bitmap scan filter on that predicate rather than
   an index-only check. Accepted at current pre-launch row counts, using the same tradeoff framing
   V31 and V30 already use for deferring `CREATE INDEX CONCURRENTLY` - revisit (e.g. an additional
   `(action, occurred_at DESC)` or a composite covering index) once real platform-wide `audit_log`
   row counts make this scan pattern measurably slow. No new index is added by this addendum.
4. **"Provision default branding/plan config" on tenant approval is not implemented, and does not
   need to be.** Investigated per finding H1: `Tenant` has only `requestedPlan` (a plain string
   captured once at registration via `registerPending(...)`) - there is no separate "active
   plan"/"branding config" concept anywhere in `tenant-management` or any billing/plan-adjacent
   domain for approval to provision. Per root `CLAUDE.md`'s "do not invent unresolved business
   decisions" instruction, no new plan/branding domain concept was invented to satisfy this
   literally. `requestedPlan` is already captured and persisted at registration (TEN-1); approval
   (`TenantApprovalService#transition`) requires no additional provisioning step beyond the status
   transition itself. This is a deliberate, reviewed clarification of this plan's original
   "provisions default branding/plan config" language, not a silently-skipped acceptance
   criterion. If a real default-plan/branding provisioning mechanism is designed in a future
   module, tenant approval is the natural place to wire it in, and
   `PlatformAdminTenantApprovalRollbackIntegrationTest` will need extending at that point to cover
   its rollback behavior too.
5. **`AuditLogService#requireKnownActor`'s guard applies retroactively to all four existing
   `AuditLogEventListener` methods, not just the new `onTenantStatusChanged` listener.** A
   follow-up review (solution-architect) noted this wasn't called out explicitly in §7's domain
   model list, which only names the new `TenantStatusChangedEvent` path. In fact `record()` (used
   by `onCoursePriceChanged`/`onMaterialDeleted`/`onPaymentRefunded`) and `recordForTenant()`
   (used by `onTenantStatusChanged`) share the same guard, so every pre-existing audited action
   gained this actor-existence check too, as a correct and desirable parity side effect - not an
   oversight, and not scoped narrowly to PADASH-1/2 by design.
6. **The audit `action` written for tenant approval/rejection is `tenant.approved`/
   `tenant.rejected` (two distinct strings), not a single generic `tenant.status_changed`.** An
   initial implementation of `AuditLogEventListener#onTenantStatusChanged` wrote the single
   literal `"tenant.status_changed"` for both transitions - this diverged from the contract
   already documented in `docs/api/tenant-management.md`/`docs/api/audit-log-management.md` and
   the frontend's own action filter (`platform-audit-log-filter-form.tsx`'s `KNOWN_ACTIONS`),
   which both assumed the two distinct values from the start. A post-review fix (solution-architect
   finding) corrected the listener to derive the action from `event.newStatus()` (`TRIAL` ->
   `tenant.approved`, `REJECTED` -> `tenant.rejected`), matching the docs/UI rather than the other
   way around, since those were the already-reviewed, shipped contract.
7. **`V33__restore_audit_log_actor_integrity_trigger.sql`'s first cut only re-checked actor
   existence, not actor/tenant pairing - closed by `V34__tighten_audit_log_actor_tenant_match_trigger.sql`.**
   See `docs/architecture/database-architecture.md` §3's V32/V33/V34 section for the full
   before/after; summarized here only to keep this addendum's disclosure list complete.
   `V35__drop_unused_payment_created_at_tenant_index.sql` similarly drops `V31`'s
   `idx_payment_created_at_tenant`, confirmed dead once the payment dashboard shipped
   ledger-derived-only (item 4 above) rather than reading `payment` directly.
8. **§21 item 1's TEN-2-scope decision (Option A: narrow TEN-2 to approve/reject only) was
   implemented without a separately dated, named product-owner sign-off distinct from the
   implementing session** - a follow-up review (solution-architect) flagged this as a traceability
   gap against this document's own bolded gate above ("does not authorize implementation... until
   ... explicitly resolved by the product owner"), by contrast with how MVP-004 recorded its own
   equivalent decisions. This addendum entry is disclosure, not resolution: the option actually
   shipped is Option A as described in §21 item 1, and a project maintainer should confirm (and,
   ideally, record here with a name/date) that this was the intended choice before this module is
   considered fully closed out.
