# ADR-034 — Tenant-configurable role permissions (Operator, Manager, Teacher)

Status: Accepted 2026-11-07 (approved by the task owner's explicit STAFF-MGMT-02 requirement to
reproduce Klass's Staff → Permissions matrix). Touches the change-controlled authorization model
additively; the static matrix in `PermissionCheckServiceImpl` (MVP-003) is unchanged for every
existing role.

## Context

RBAC was a static code matrix (`DomainArea` × `PermissionAction`). Klass lets each institute tick
individual capabilities per role (Operator, Manager, Teacher). The task requires real server
enforcement of every editable cell, no parallel role system, no renamed keys, and no silent
broadening of existing access.

## Decision

1. **Roles.** `OPERATOR` and `MANAGER` are added to the `role` catalog and `Role` enum (V118).
   They hold nothing by default. Legacy staff sub-roles and `TENANT_ADMIN` keep their static grants.
   Staff Management assigns only Operator/Manager; existing legacy assignments are preserved.
2. **Capability catalog.** `identityaccessservice.api.Capability` lists every Klass row (plus our
   CSV/QR exports and Log as Student) with a primary `(DomainArea, PermissionAction)` equal to the
   endpoint's existing gate, the pairs its code path checks (`implied`), the roles it is configurable
   for, and an availability: `ENFORCED`, `LOCKED` (system-managed, reason) or `UNAVAILABLE` (no
   backing operation, reason). Only `ENFORCED` cells are editable.
3. **Enforcement.** Controller methods carry `@RequiresCapability`. A `HandlerInterceptor` that runs
   before method security:
   - Operator/Manager: missing grant → 403. Granted → the capability's implied pairs are elevated
     for that request only (thread-local, cleared before and after). On every other endpoint
     `hasPermission` is false for these roles (fail closed).
   - Teacher: a teacher-configurable capability can only be revoked (default granted). It never
     elevates, and ownership checks still apply.
   - All other roles: no-op.
4. **Storage.** `tenant_role_capability_grant` (absent row = catalog default) and
   `tenant_role_permission_version` (optimistic concurrency, stale save → 409 `PERMISSIONS_STALE`).
   Grants are read per request (memoised per request), so a revoked grant is denied on the next
   request of an existing session. Permissions are never embedded in tokens; roles were already
   re-read live per request.
5. **Delegation.** Only Tenant Admin holds `STAFF_PERMISSIONS_EDIT` / `STAFF_ASSIGN_ROLES`. An actor
   cannot edit its own role, cannot grant what it does not hold, and cannot touch `LOCKED`/`STAFF_*`
   capabilities. Role assignment never targets self, Tenant Admin, Teacher or Student memberships.
6. **Audit.** Every staff and permission change is audited in the same transaction; a failed or
   rolled-back change leaves no entry.

## Consequences

- Payment rules are unchanged: delegating `PAYMENTS_APPROVE_PENDING` or `PAYMENTS_UNDO` changes who
  may act, not how slips, refunds or the ledger behave (duplicate checks, append-only refunds,
  reviewer identity all still apply).
- Adding a capability requires wiring an endpoint and a grant/revoke test before it becomes
  `ENFORCED`.
- Async work does not inherit elevation; an Operator-triggered path that re-checks permissions on
  another thread fails closed.
- Access differences introduced: none for existing roles. New roles start with no access.
