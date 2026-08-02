# User Roles & Permissions

Status: Draft — consolidated from `docs/requirements/source-requirements.md` (module 5
staff role list), `docs/architecture/authentication-authorization.md` §3/§9 (role model,
authorization posture), and `docs/ui-ux/information-architecture.md` (portal scoping).
Per `authentication-authorization.md` §12, the **concrete endpoint-level permission
matrix** per staff sub-role is tracked in `docs/api` alongside the endpoints it governs,
once each domain's contracts are reviewed — it is not duplicated here. This document
defines the **role list, scope, and domain-level (not endpoint-level) permission
boundaries**, which is the level of detail needed for IA/screen-access decisions and for
`docs/api` to refine later.

Anything below marked **PROVISIONAL** is a reasonable default proposed to unblock design
work, not a ratified business decision — flagged explicitly rather than presented as
settled, per root `CLAUDE.md`'s instruction not to invent business requirements.

Related: `docs/architecture/authentication-authorization.md`, `.claude/rules/security.md`,
`docs/ui-ux/information-architecture.md`

---

## 1. Role list

| Role | Scope | Portal route group | Self-registers? |
|---|---|---|---|
| Platform Admin | Platform-wide, cross-tenant | `app/(platform-admin)/` | No — provisioned internally |
| Tenant Admin / Institute Owner | Single tenant, full administrative access | `app/(tenant-admin)/` | No — created on tenant approval |
| Finance Staff | Single tenant, finance/payments operational area | `app/(tenant-admin)/` | No — created by Tenant Admin |
| Course Coordinator | Single tenant, course operational area | `app/(tenant-admin)/` | No |
| Student Support | Single tenant, support/ticketing operational area | `app/(tenant-admin)/` | No |
| Content Manager | Single tenant, materials/content operational area | `app/(tenant-admin)/` | No |
| Exam Manager | Single tenant, exam operational area | `app/(tenant-admin)/` | No |
| Attendance Operator | Single tenant, attendance operational area | `app/(tenant-admin)/` | No |
| Read-only Auditor | Single tenant, read-only across operational areas | `app/(tenant-admin)/` | No |
| Teacher | Single tenant, scoped to assigned courses | `app/(teacher)/` | No — created/approved by Tenant Admin |
| Teacher Assistant | Single tenant, subset of Teacher scope — see §3 (PROVISIONAL) | `app/(teacher)/` | No |
| Student | Single tenant, scoped to own enrollments/records | `app/(student)/` | Yes — via tenant storefront (see `docs/ui-ux/user-journeys.md` open question on whether this is public or invite-only) |
| Anonymous / Public | No tenant scope beyond the resolved storefront tenant | `app/(public)/` | N/A |

All roles authenticate through the same `identity-access-service` login path — role is a
property of the authenticated principal, never a separate auth mechanism per
`authentication-authorization.md` §3.

## 2. Staff sub-role permission matrix (domain-level)

`V` = view, `C/E` = create/edit, `D` = delete, `A` = approve/publish (a distinct,
higher-trust action than create/edit — e.g. approving a payment slip, publishing exam
results), `—` = no access. This is a starting matrix for IA/navigation purposes;
`docs/api` owns the authoritative endpoint-level version.

| Domain area | Institute Owner (Tenant Admin) | Finance Staff | Course Coordinator | Student Support | Content Manager | Exam Manager | Attendance Operator | Read-only Auditor |
|---|---|---|---|---|---|---|---|---|
| Students | V/C/E/D | V | V | V/C/E | V | V | V | V |
| Teachers | V/C/E/D | — | V/C/E | V | — | — | — | V |
| Staff & roles | V/C/E/D | — | — | — | — | — | — | V |
| Courses | V/C/E/D | V | V/C/E/A | V | V | V | V | V |
| Materials | V/C/E/D | — | V | — | V/C/E/D | — | — | V |
| Payments / slips | V/C/E/A | V/C/E/A | — | V | — | — | — | V |
| Finance & expenses | V/C/E/D | V/C/E/D | — | — | — | — | — | V |
| Attendance | V/C/E | — | — | — | — | — | V/C/E | V |
| Exams | V/C/E/A | — | — | — | — | V/C/E/A | — | V |
| Devices | V/C/E | — | — | V (request only) | — | — | — | V |
| Access & expiry / reactivation | V/C/E/A | V (approve if finance-adjacent) | — | V | — | — | — | V |
| Reviews moderation | V/A | — | V/A | — | — | — | — | V |
| Audit log | V | V (own-area actions) | V (own-area actions) | V (own-area actions) | V (own-area actions) | V (own-area actions) | V (own-area actions) | V (full) |
| Branding & settings | V/C/E | — | — | — | — | — | — | V |
| Support tickets | V/C/E/D | — | — | V/C/E | — | — | — | V |

Read-only Auditor: server-side, **no mutating endpoint may succeed for this role,
regardless of what a stale client UI exposes** (`authentication-authorization.md` §9).

## 3. Teacher vs. Teacher Assistant — PROVISIONAL

No source document defines Teacher Assistant's permission boundary today (flagged as a
gap in `docs/ui-ux/information-architecture.md` review). Proposed default, pending
approval:

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

This split (assistant can do day-to-day content/attendance work, cannot publish,
price, or see financial/contact data) is a reasonable default, not a confirmed
decision — do not build a hard permission gate against it without sign-off.

## 4. Cross-cutting authorization rules

These apply to every role above and are already fixed at the architecture level
(`authentication-authorization.md` §9, `.claude/rules/security.md`,
`.claude/rules/tenancy.md`) — restated here as a single checklist for permission-matrix
consumers:

- Authorization is enforced **server-side on every protected endpoint**, independent of
  any client-side role display. Hidden/disabled UI is a convenience, never the
  authority.
- The permission-denied UI state is driven only by a server-verified 401/403 or a
  role/permission value from the authenticated session payload — never a client-stored
  role string.
- Every permission check is evaluated **for the resolved tenant context** — a check that
  doesn't also confirm "for this tenant" is incomplete for any tenant-owned resource.
- Platform Admin permissions are platform-scoped by default and do not implicitly grant
  tenant-admin-equivalent access to a specific tenant's data without an explicit,
  audited impersonation flow (§5).
- Device-limit override precedence (student > course > tenant > plan) is the
  confirmed pattern for that specific feature (`authentication-authorization.md` §6) —
  it is **not** confirmed to be the same precedence order for the expiry rules engine
  (`enrollment-access.md` §9 open question) or any other feature; do not assume
  precedence orders generalize across features without checking the owning
  architecture doc.

## 5. Impersonation

If/when Platform Admin "view as tenant" is implemented: backed by a distinct,
backend-issued impersonation session (never a locally toggled UI state); every action
during the session records both the impersonating admin's identity and the impersonated
user's identity distinctly (never collapsed into one actor id); start and end each
produce an audit log entry; the UI renders a visually loud, non-dismissible mode
indicator for the duration (`.claude/rules/ui-ux.md` §1).

## Open questions

1. Teacher Assistant permission boundary (§3) — PROVISIONAL, needs sign-off.
2. Whether Finance Staff or Institute Owner (or both) is the correct approver for
   reactivation requests — `docs/ui-ux/user-journeys.md` Journey 3 names "Finance Staff
   or Institute Owner, per role permission" without fully resolving precedence when both
   are eligible.
3. Whether Course Coordinator's course-approval authority (`A` in §2) requires a second
   approver for high-value/published courses, or is single-approver — not specified
   anywhere in current material.
4. Whether tenant self-registration (§1, Student row) is public or invite-only — same
   open question already tracked in `docs/ui-ux/user-journeys.md`.

## Related

- `docs/architecture/authentication-authorization.md`
- `.claude/rules/security.md`
- `docs/ui-ux/information-architecture.md`
- `docs/ui-ux/authentication-design-spec.md`
