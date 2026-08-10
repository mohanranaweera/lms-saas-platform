# ADR-009: Role/Permission Data Model — `tenant_user.role` as a Foreign Key to a Role Catalog

## Status

Accepted (2026-08-07). Formalizes a decision already made and implemented during Module 3
(Roles and Permissions, MVP-003) — `docs/plans/MVP-003 Roles and Permissions.md` §8 identified
this specific mechanism choice as requiring an approved ADR under `docs/adr/` before
implementation, per `docs/architecture/authentication-authorization.md`'s change-control banner
("how roles/permissions are modeled"), and explicitly stated the plan itself did not constitute
that approval. The human directive "Approved for backend implementation only" that authorized
implementing the MVP-003 plan is the substantive approval this ADR records — this document exists
to close the gap between that approval being given and it being recorded where the change-control
process requires it, surfaced by an independent backend review after implementation.

**Approval confirmation (2026-08-08):** three independent reviews (across two review rounds)
flagged this ADR's approval trail as unverifiable, since it was authored after implementation by
the same agent that did the implementation, citing only a paraphrased directive. The repository
owner, MOHAN RANAWEERA, has directly confirmed — in this project's own words, not an agent's
restatement — that the FK-to-catalog mechanism (over the CHECK-only alternative, see "Alternatives
considered" below) was in fact approved before `V7`–`V9` were written. This closes the
change-control gap the prior reviews raised; no further action is required on this item before
merge.

## Context

Module 2 (Authentication Foundation) shipped `tenant_user.role` as a `VARCHAR` column with an
inline, unnamed `CHECK` constraint restricting it to four coarse values (`TENANT_ADMIN`, `STAFF`,
`TEACHER`, `STUDENT`), with an explicit comment reserving the real permission/staff-sub-role
breakdown for this module. Module 3 needed to formalize that into the full role list (11
tenant-scope values: Tenant Admin, 7 staff sub-roles, Teacher, Teacher Assistant, Student), plus a
platform-global, fixed catalog of role display/reference metadata (`docs/requirements/user-roles-and-permissions.md`
§1 requires a `role` table, "fixed catalog, platform-global, no tenant_id").

Two designs were considered for how `tenant_user.role`'s valid-value set relates to that catalog:

1. **CHECK-only enum**: keep `tenant_user.role` as a plain `CHECK`-constrained enum (as shipped by
   Module 2, just with a wider value list), with the `role` catalog table existing separately as
   pure display/reference metadata, no FK relationship between them.
2. **FK-to-catalog**: repoint `tenant_user.role` to `FOREIGN KEY REFERENCES role(code)`, making the
   catalog the single source of truth for the valid tenant-role set.

## Decision

**Adopt FK-to-catalog (option 2).** `tenant_user.role` is a `FOREIGN KEY` referencing `role.code`
(`V8__repoint_tenant_user_role_to_catalog.sql`). The `role` catalog table
(`V7__create_role_catalog.sql`) is platform-global reference data — no `tenant_id`, seeded with all
12 roles (11 `TENANT`-scope values plus `PLATFORM_ADMIN` for display/reference completeness only).

- **The column keeps its existing name (`role`, not `role_code`)**, deliberately deviating from the
  literal column name sketched in the MVP-003 plan §8, to avoid churning `TenantUser.getRole()`,
  `TokenService.issueTenantAccessToken(..., Role role, ...)`, and every existing Module 2 test that
  references `.role`/`Role` — the FK-vs-CHECK mechanism change is the substantive decision this ADR
  records; the column's name was never part of what needed approval.
- **`PLATFORM_ADMIN` is excluded from ever appearing in `tenant_user.role`** by two independent
  layers: the Java `Role` enum (`com.lms.identityaccessservice.domain.Role`) deliberately omits it,
  and `V9__forbid_platform_admin_on_tenant_user_role.sql` adds
  `CHECK (role <> 'PLATFORM_ADMIN')` as schema-level defense-in-depth, since a plain FK to a
  12-row catalog can't by itself express "only TENANT-scope codes." `platform_admin_user` (Module
  2, V4) remains the sole home for Platform Admin accounts.
- **`RoleCatalogRepository` does not extend `TenantAwareRepository`** — the `role` table holds zero
  tenant-owned rows (fixed reference/display metadata only), so tenant-scoping it would be a
  misapplication of that mechanism, not a missing one.

## Rationale

FK-to-catalog was chosen over CHECK-only because CHECK-only leaves the catalog's `TENANT`-scope
rows and the CHECK constraint's value list as two independently-maintained sources of truth for
the same domain, with no database-level guarantee they stay in sync if one is edited without the
other in a future migration. FK-to-catalog collapses this to one source of truth — the catalog —
at no added tenant-isolation cost (the `role` table is deliberately non-tenant-owned either way).

## Consequences

**Positive**
- Single source of truth for the valid tenant-role set; adding, retiring (`is_active = false`), or
  relabeling a role is a `role` table change only, with the FK preventing `tenant_user` from ever
  drifting out of sync.
- `TEACHER_ASSISTANT.is_provisional = true` gives the unratified Teacher Assistant permission
  boundary (`docs/requirements/user-roles-and-permissions.md` §3) a structural marker in the same
  place its role identity lives, rather than a comment-only flag.

**Negative / trade-offs accepted**
- A plain FK cannot itself restrict "only `TENANT`-scope codes are valid here" — `V9`'s CHECK
  constraint only blocks the specific literal `PLATFORM_ADMIN` value, not "any future
  `PLATFORM`-scope catalog row" in general. If a second platform-scope role code is ever added to
  the catalog, this specific CHECK will need revisiting (a trigger validating `role.scope = 'TENANT'`
  is the documented fallback if that happens) — accepted as adequate for the current two-scope
  catalog, not solved preemptively.
- This is a real mechanism change beyond the Module 2 baseline's plain-CHECK-enum approach,
  meaning any future review of `tenant_user.role`'s design should treat this ADR, not the Module 2
  migration comments, as the current authoritative description of how the column is enforced.

## Alternatives considered

- **CHECK-only enum, no FK** — rejected: leaves two independently-maintained sources of truth for
  the same value set (the catalog and the CHECK list), a drift risk with no compensating benefit
  once a catalog table exists at all.
- **`tenant_user_role` join table (multi-role-per-user)** — rejected: multi-role has not been
  confirmed as a requirement anywhere in `docs/requirements/user-roles-and-permissions.md` or the
  product backlog; building it now would be speculative scope. `docs/requirements/user-roles-and-permissions.md`
  itself frames single-role-per-user as the recommended default pending that confirmation.

## Related

- `docs/plans/MVP-003 Roles and Permissions.md` §7-8 (the plan this ADR retroactively approves)
- `docs/requirements/user-roles-and-permissions.md`
- `docs/architecture/authentication-authorization.md` (the change-control banner this ADR satisfies)
- ADR-002-shared-database-tenancy.md, ADR-006-tenant-isolation-repository-mechanism.md
- `backend/src/main/resources/db/migration/V7__create_role_catalog.sql`,
  `V8__repoint_tenant_user_role_to_catalog.sql`, `V9__forbid_platform_admin_on_tenant_user_role.sql`
