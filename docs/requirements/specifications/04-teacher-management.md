# Teacher Management

**Domain:** `user-management` (Module 4) · **Portal(s):** Tenant Admin, Teacher

## 1. Business purpose

Manage teacher accounts, their approval into the tenant, course assignments, and (later)
commission/payout configuration — distinct from generic staff management since teachers have
their own portal and course-scoped permissions.

Source: `docs/requirements/source-requirements.md` Module 4.

## 2. Actors

- **Teacher** — registers/is invited, then approved; manages own assigned courses
- **Teacher Assistant** — subset scope, **PROVISIONAL, not ratified** (see Open Decisions)
- **Tenant Admin / Institute Owner** — full CRUD + approval
- **Course Coordinator** — `V/C/E` on Teachers
- **Student Support, Read-only Auditor** — `V` only

## 3. Preconditions

- Tenant active
- For approval: a teacher registration/application already exists in a pending state

## 4. Normal flow

1. Teacher registers (or is invited/created by Tenant Admin — exact mechanism unspecified).
2. Teacher account enters an approval-pending state; Tenant Admin approves.
3. On approval, teacher can log in via the shared auth path, scoped to the Teacher portal.
4. Tenant Admin (or Course Coordinator) assigns courses to the teacher.
5. Teacher's `My Courses`/roster views are backend-filtered to only their own assigned courses — never a full dataset filtered client-side.
6. Teacher completes profile (availability, payout profile — Phase 2/3).

## 5. Alternative flows

- Teacher rejected at approval: no course-assignment or login capability granted.
- A Course Coordinator without approval permission attempts to approve a teacher: matrix gives Course Coordinator `V/C/E` on Teachers with no explicit `A` (approve) column — approval likely Institute-Owner-only, but this is not explicitly modeled the way Payments/Courses/Exams have a dedicated `A` column (Open Decision).
- Cross-tenant: a Teacher authenticated in tenant A requests a course/roster ID belonging to tenant B — rejected 403/404.
- Teacher Assistant attempts an action reserved for Teacher only (publish course, change pricing, publish exam results, respond to reviews) — rejected per the PROVISIONAL matrix, pending ratification.

## 6. Authorization rules

Per `docs/requirements/user-roles-and-permissions.md` §2, row "Teachers": Institute Owner =
`V/C/E/D`; Course Coordinator = `V/C/E`; Student Support = `V`; Read-only Auditor = `V`; others =
`—`. Teacher vs. Teacher Assistant boundary (§3) is **explicitly PROVISIONAL**: "a reasonable
default, not a confirmed decision; do not build a hard permission gate against it without
sign-off."

## 7. Tenant rules

- Teacher table is tenant-owned.
- FR-UM-6 (revenue/commission settings, Phase 2) spans two domains: `user-management` owns the settings *config*; `ledger-settlement-management` consumes it via `api` for settlement calc — do not duplicate the rate locally.
- Public teacher profile (Phase 2/3) is composed into the public storefront (Module C, ownership unratified).

## 8. Acceptance criteria

- [ ] Given an unapproved teacher, when they attempt to log in, then they either cannot log in or see no assigned courses (exact UX unspecified — Open Decision).
- [ ] Given a Teacher, when they request `My Courses`, then results are limited server-side to their own assignments.
- [ ] Given a Teacher of tenant A, when they attempt to reach a course/roster/material belonging to tenant B, then the request is rejected 403/404.
- [ ] Commission/payout settings tie into `ledger-settlement-management`'s settlement calculation rather than being duplicated locally.
- [ ] Public teacher profile page shows only Teacher-approved public fields, read-only.
- [ ] Cross-tenant negative test on teacher account CRUD/role assignment.
- [ ] Intra-tenant test: a Teacher must be proven unable to view/list courses or rosters outside their own assigned-course set, even within their own tenant.

## 9. Audit requirements

**Open Decision** — unlike tenant approval, teacher approval (FR-UM-5) is not stated to be
audit-logged anywhere reviewed. No explicit mandate found for teacher CRUD.

## 10. MVP or later-phase classification

**MVP** for registration, approval, profile, assigned-courses view (FR-UM-5;
`source-requirements.md` §5 MVP list "Teacher management"). Commission settings (FR-UM-6) is
**Phase 2**; availability/payout profile/performance analytics/public profile (FR-UM-7) is
**Phase 2/3**.

## UI-state and portal notes

- **Portal placement**: Tenant Admin `Teachers > Teacher List`, `Teacher Detail`.
- **Empty states**: "no teachers yet" (add-teacher CTA) vs. "no teachers match filter."
- Approve/reject icon controls need specific `aria-label`s (e.g. "Approve teacher Jane Doe"), not generic labels.

## Open decisions

- Exact teacher registration mechanism (self-register-then-approve vs. Tenant-Admin-invited-only).
- Whether "Teacher approval" is a distinct `A`-level permission separate from `C/E` for Course Coordinator — the matrix has no explicit approval column for Teachers, unlike Courses/Payments/Exams.
- Teacher Assistant's entire permission boundary (PROVISIONAL, unratified) — affects this feature directly.
- Whether teacher approval requires an audit-log entry.
