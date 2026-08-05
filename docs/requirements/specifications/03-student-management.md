# Student Management

**Domain:** `user-management` (Module 3) · **Portal(s):** Tenant Admin, Student (self-service), Teacher (read-only roster)

## 1. Business purpose

Give Tenant Admin/staff and the student themselves a complete, tenant-scoped record of a
student's identity, guardian info, and cross-domain history (enrollment/payment/attendance/exam/
device/communication), which other modules read from rather than duplicate.

Source: `docs/requirements/source-requirements.md` Module 3.

## 2. Actors

- **Student** — self-registers or is created; owns their own profile
- **Tenant Admin / Institute Owner** — full CRUD
- **Student Support** staff — `V/C/E`
- **Course Coordinator, Finance Staff, Content Manager, Exam Manager, Attendance Operator, Read-only Auditor** — `V` only
- **Teacher** — scoped, read-only roster view (course-filtered, not full student management)

## 3. Preconditions

- Tenant is active
- For self-registration: tenant's storefront correctly resolves tenant identity from subdomain/custom domain

## 4. Normal flow

1. Student self-registers via `Public > Auth > Student Registration` (tenant-scoped) **or** Tenant Admin/Student Support manually creates the account **or** bulk-imports via CSV.
2. Manual/bulk-created accounts carry a "must change password" flag.
3. Student/staff completes profile: guardian/parent info, school/grade/stream, status.
4. Student's history views (enrollment, payment, attendance, exam, device, communication) are populated by reading from each owning domain — never duplicated into the student record.
5. Tenant Admin/Student Support views `Student Detail` with the full timeline.

## 5. Alternative flows

- Self-registration on a tenant that cannot be resolved (bad/unrecognized subdomain): fails safely, no fallback to another tenant's registration form.
- Bulk import with partial row failures: behavior unspecified (Open Decision).
- A staff sub-role without student-write permission (e.g. Content Manager, Attendance Operator — `V` only) attempts to edit a student profile: rejected 403.
- Student attempts to view another student's profile/history by ID: rejected — no student-selector or ID-based cross-student navigation exists.
- Cross-tenant: Student Support of tenant A attempts to reach tenant B's student record: rejected 403/404.

## 6. Authorization rules

Per `docs/requirements/user-roles-and-permissions.md` §2, row "Students": Institute Owner =
`V/C/E/D`; Finance Staff = `V`; Course Coordinator = `V`; Student Support = `V/C/E`; Content
Manager = `V`; Exam Manager = `V`; Attendance Operator = `V`; Read-only Auditor = `V`.

## 7. Tenant rules

- Student table is tenant-owned. Self-registration must resolve tenant via subdomain/custom domain — never client-supplied.
- History views must be sourced from each owning domain's `api`, never duplicated into student-management's own tables (a concrete instance of the "narrow read method vs. cross-domain join" rule).
- Teacher's roster view must be backend-pre-filtered to assigned courses only, never fetched unfiltered and filtered client-side.

## 8. Acceptance criteria

- [ ] Given a student self-registers, then the resulting account is tenant-scoped to the resolving tenant only.
- [ ] Given a manually/bulk-created student account, then it carries a `must_change_password` flag enforced at next login.
- [ ] Given a student views their own dashboard/history pages, then all data returned is backend-filtered to that student's own tenant-scoped records only.
- [ ] Given Student Support (`V/C/E`) edits a student profile, then the change succeeds; given Content Manager (`V` only) attempts the same edit, then it is rejected 403.
- [ ] Empty state distinguishes "no students yet" (with add/bulk-import CTA) from "no students match your filters."
- [ ] Cross-tenant negative test: student list/detail/history endpoints reject tenant B's data to tenant A's Tenant Admin/staff.
- [ ] Student tags/risk indicators/inactive detection (Phase 2) are derived, never manually re-enterable state.

## 9. Audit requirements

**Open Decision** — no explicit mandate in `.claude/rules/security.md`'s list for plain student
profile CRUD or status changes (e.g. suspension). Not addressed anywhere in reviewed material.

## 10. MVP or later-phase classification

**MVP** for registration, manual creation, bulk import, profile, history views (FR-UM-1/2/3;
`source-requirements.md` §5 MVP list "Student management"). Tags/risk indicators/inactive
detection/timeline (FR-UM-4) are **Phase 2**.

## UI-state and portal notes

- **Portal placement**: Tenant Admin `Students > Student List`, `Student Detail`, `Bulk Import`; Teacher `Roster > Course Roster` (read-only, scoped); Student's own `Profile`.
- **Empty states**: "no students yet" (needs bulk-import/add CTA) vs. "no students match filter" — two distinct states.
- Student List is the canonical example of the shared responsive data-table component.

## Open decisions

- Whether student self-registration is public or invite-only.
- Bulk-import partial-failure behavior (all-or-nothing vs. row-level partial success with an error report) — not specified.
- Exact field list for "guardian/parent information" and "school/grade/stream" is not itemized.
- Whether student status changes / Student Support edits require an audit-log entry.
