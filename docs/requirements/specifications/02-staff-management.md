# Staff Management

**Domain:** `user-management` (Module 5) · **Portal(s):** Tenant Admin

## 1. Business purpose

Let each tenant operate with multiple internal staff accounts (not just one owner login), with
role-based access so operational duties can be delegated safely across defined staff sub-roles.

Source: `docs/requirements/source-requirements.md` Module 5.

## 2. Actors

- **Tenant Admin / Institute Owner** — full CRUD on staff accounts and role assignment
- **Staff sub-roles** (once created): Finance Staff, Course Coordinator, Student Support, Content Manager, Exam Manager, Attendance Operator, Read-only Auditor

## 3. Preconditions

- Acting user is Tenant Admin (per the permission matrix, only Institute Owner has full `V/C/E/D` on "Staff & roles")
- Tenant is `active` (not suspended)
- Tenant's plan staff-count limit has not been exceeded (Phase 2)

## 4. Normal flow

1. Tenant Admin opens `Tenant Admin > Staff > Staff List`, selects "Add staff."
2. Tenant Admin creates the account, assigns a role from the fixed sub-role list.
3. Backend creates a staff account scoped to this tenant.
4. Staff logs in via the same shared `identity-access-service` login path (no parallel auth stack); role determines portal scope.
5. Tenant Admin can view/edit staff activity logs and reset a staff member's password.

## 5. Alternative flows

- **Staff count at plan limit**: creating a new staff account is rejected server-side, not merely hidden in the UI (FR-UM-9, Phase 2).
- **Permission violation**: a staff sub-role attempts a mutating action outside its permission set (e.g. Read-only Auditor attempts an edit) — rejected server-side regardless of UI state.
- **Staff status change** (deactivate/suspend): named as a required feature with no defined state machine (Open Decision).
- **Cross-tenant**: a Tenant Admin of tenant A attempts to view/edit tenant B's staff account by ID — rejected 403/404.

## 6. Authorization rules

Per `docs/requirements/user-roles-and-permissions.md` §2, row "Staff & roles": Institute Owner =
`V/C/E/D`; Read-only Auditor = `V`; all other staff sub-roles (Finance Staff, Course Coordinator,
Student Support, Content Manager, Exam Manager, Attendance Operator) = `—` (no access).

## 7. Tenant rules

- Staff accounts are tenant-owned. `UNIQUE (tenant_id, email)` — never a global unique constraint (`.claude/rules/backend.md`).
- No cross-tenant read requirement for staff CRUD itself; FR-UM-9 (staff-count-vs-plan-limit) is a tenant-scoped read of `tenant-management`'s config, not a cross-tenant bypass.
- Owning domain: `user-management` for profile/role *data*; role *enforcement* is `identity-access-service`'s concern.

## 8. Acceptance criteria

- [ ] Given a Tenant Admin creates a staff account, then the account is tenant-scoped, email is unique per tenant (not globally), and the account can log in only within that tenant.
- [ ] Given a staff sub-role with no "Staff & roles" permission, when they attempt to create/edit another staff account, then the request is rejected 403.
- [ ] Given the tenant's plan staff-count limit is reached, when Tenant Admin attempts to add another staff account, then creation is rejected server-side with a clear reason (Phase 2).
- [ ] Given Read-only Auditor role, no mutating staff endpoint succeeds regardless of stale client UI state.
- [ ] Cross-tenant negative test: staff list/detail/role-edit endpoints reject tenant B access from tenant A actor.
- [ ] Role Editor uses `fieldset`/`legend` grouping for permission-assignment checkboxes (accessibility).

## 9. Audit requirements

**Open Decision** — whether staff account creation/role changes must produce an
`audit-log-management` entry is not specified anywhere in reviewed material. Not on
`.claude/rules/security.md`'s canonical mandatory-audit list. Flagged given the high blast-radius
of role assignment; recommend treating as audit-worthy pending an explicit decision.

## 10. MVP or later-phase classification

**MVP** for core (accounts, separate logins, role-based access, activity logs, permission
management, password reset) — `functional-requirements.md` FR-UM-8; `source-requirements.md` §5
MVP list includes "Staff roles." Staff-count-vs-plan-limit enforcement (FR-UM-9) is **Phase 2**,
and depends on the Feature Flag / Plan Limit Engine (Module D), whose ownership is unratified
(see Open Decisions).

## UI-state and portal notes

- **Portal placement**: Tenant Admin `Staff > Staff List`, `Staff Detail / Role Editor`, `Staff > Activity Log`.
- **Empty states**: "no staff accounts yet" (with Add Staff CTA) distinct from "no staff match your filter."
- **Accessibility**: Staff List and Activity Log are data tables needing card-view/sticky-column fallback below `md`.
- No tenant selector — Tenant Admin portal never has one; staff sub-role nav-item hiding is UX convenience only, backend independently enforces.

## Open decisions

- Whether staff account creation/role changes require an audit-log entry.
- No documented state machine for "Staff status" (active/suspended/removed).
- Whether manually-created staff accounts get a "must change password" flag like students (inferred by analogy only, not stated).
- Password-reset flow specifics (self-service vs. admin-triggered vs. both).
- Module D (Feature Flag & Plan Limit Engine) ownership is unratified — blocks FR-UM-9 implementation.
