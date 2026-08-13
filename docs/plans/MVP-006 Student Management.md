# MVP-006 — Student Management — Module Plan

**GitHub issue:** #6 — https://github.com/mohanranaweera/lms-saas-platform/issues/6 (could not be fetched in
this session — GitHub MCP required an interactive OAuth authorization not available here, same limitation
noted in `docs/plans/MVP-005 Staff Management.md`. This plan is grounded instead in the repo's internal,
already-reconciled requirements corpus, which is this project's normal source of truth for planning.)
**Branch:** `feature/student-management` (current branch, matching this module's naming convention)
**Backlog source:** `docs/planning/product-backlog.md`, MODULE 6 (stories `STU-1`, `STU-2`, `STU-3`, lines 343-402)
**Spec source:** `docs/requirements/specifications/03-student-management.md`
**Backend domain:** `user-management` (per `.claude/rules/architecture.md`'s confirmed domain list — Student
Management is Module 3 *inside* `user-management`, sibling to the already-implemented Staff Management
(`com.lms.usermanagement.staff`, MVP-005) and the not-yet-built Teacher Management (Module 4)).

This plan was produced by delegating to six specialist agents in parallel (product-requirements-analyst,
solution-architect, database-architect, security-reviewer, qa-test-engineer, ui-ux-reviewer), each grounded
in the existing requirements/architecture corpus and the actual current repository state, then reconciled
into one document. **`payment-ledger-specialist` was intentionally not used** — this module's "Payment
impact" is `None` per all three backlog stories' own fields (item 11 in each), independently confirmed by
every parallel review — nothing in the spec, backlog, or role matrix ties this module to money in any form.
This mirrors MVP-005's identical reasoning for the same domain family.

This is a **plan only** — no application files were created or edited. Per root `CLAUDE.md`, this plan does
not invent unresolved business decisions; every genuine gap or cross-document contradiction surfaced (in
several cases independently by more than one specialist review, which is corroborating evidence) is flagged
explicitly in §21, not resolved.

**Grounding note on current repository state**, verified directly before delegating: unlike MVP-005 (which
was planned when only the Module 1 shared kernel existed), this module's hard dependencies — Module 2
(`identity-access-service`: `tenant_user`, login, `UserProvisioningApi`), Module 3 (RBAC:
`PermissionCheckService`, `DomainArea.STUDENTS` already exists in the enum, `Role.STUDENT` already exists),
and Module 4 (`tenant-management`: `TenantLookupApi`, `TenantResolutionFilter`) — are **already implemented
and merged**, along with the directly analogous precedent, Module 5 Staff Management
(`com.lms.usermanagement.staff`). Student Management is therefore genuinely implementable now, not blocked
the way MVP-005 was at its own planning time. `enrollment-management`, `payment-management`,
`attendance-management`, and `exam-management` — the four domains STU-3's cross-domain history view reads
from — do **not** yet exist; this materially shapes §18/§20 below (STU-3 is a partial/incremental slice, not
a full blocker the way MVP-005's entire module was).

---

## 1. Business goal

Give tenant staff and the student a single, tenant-scoped source of truth for a student's identity and
guardian/school data, onboarded either by self-registration, manual staff creation, or CSV bulk import.
Student Management itself owns only identity/profile data; it must never duplicate enrollment, payment,
attendance, exam, device, or communication data — those are always read live from their owning domains
through each domain's `api`, so a student's "full picture" stays consistent with the systems of record
instead of drifting into a second copy. The module also provides a strictly scoped, read-only roster view
for teachers, and enforces that manual/bulk-created accounts start with weak-credential hygiene
(`must_change_password`) while self-registered accounts do not, since the student sets their own credential
directly at registration. This is foundational MVP capability (`FR-UM-1`/`FR-UM-2`/`FR-UM-3`) — without it,
every tenant has no student-facing identity at all, which blocks Course Management, Enrollment, Payments,
Attendance, and Exams from having anyone to enroll, bill, mark, or examine.

Source: `docs/requirements/source-requirements.md` Module 3; `docs/requirements/specifications/03-student-management.md`.

## 2. Roles and permissions

Grounded verbatim in `docs/requirements/user-roles-and-permissions.md` §2, row "Students":

| Role | Grant | Notes |
|---|---|---|
| Institute Owner / Tenant Admin | `V/C/E/D` | Full CRUD including delete — **delete semantics are entirely undefined, see §21 item 5** |
| Student Support | `V/C/E` | The only staff sub-role besides Tenant Admin that can create/edit; no delete |
| Finance Staff | `V` | View only |
| Course Coordinator | `V` | View only |
| Content Manager | `V` | View only |
| Exam Manager | `V` | View only |
| Attendance Operator | `V` | View only |
| Read-only Auditor | `V` | View only; per §2's general note, **no mutating endpoint may succeed for this role server-side**, regardless of UI state — first-class negative test required per role, not one generic test |
| Student | Self-only, no row in the staff matrix | Creates own account via self-registration; views/edits **own** profile and **own** history only; explicitly **no** ID-based or selector-based navigation to another student's data (spec §5) — "Student" is a separate actor with "own record only" scope, not a column in the staff permission matrix |
| Teacher | Scoped `V` (roster only) | Read-only, backend-pre-filtered to the teacher's assigned courses — never a full roster filtered client-side (spec §7). **See §21 item 2 for why this cannot be honestly built at this module's actual buildable MVP-006 slice.** |
| Teacher Assistant | **Undefined for this module** | Not mentioned anywhere in the spec's actor list; `user-roles-and-permissions.md` §3's PROVISIONAL table implies partial roster visibility (attendance/exam-relevant fields only, no contact/payment info) but that entire role boundary is itself unratified — see §21 item 8 |
| Platform Admin | **No implicit grant** | Per §4's cross-cutting rule, Platform Admin does not implicitly get tenant-admin-equivalent access to a tenant's student data without an explicit, audited impersonation flow (§5 of that doc). This module's endpoints must not silently admit a Platform Admin token as if it were a Tenant Admin token. |

Cross-cutting rules from `user-roles-and-permissions.md` §4 apply without exception to every Student
Management endpoint: authorization is enforced server-side on every protected endpoint independent of client
display; the permission-denied UI state is driven only by a server-verified 401/403; every permission check
is evaluated for the resolved tenant context; Platform Admin access is never implicit.

Testable derivations required by the matrix but not spelled out in the spec's own AC list:
- Every `V`-only role (Finance Staff, Course Coordinator, Content Manager, Exam Manager, Attendance
  Operator, Read-only Auditor — not just Content Manager, the spec's one worked example) must be tested
  individually against every mutating endpoint (create, edit, delete/status-change) and rejected 403.
- A student must never be gated by `PermissionCheckService.hasPermission(DomainArea.STUDENTS, ...)` for
  their own "own profile" endpoint — that check is modeled for staff-type roles and would incorrectly
  default-deny a student viewing their own data (see §9 and §15 for the structural fix: no id parameter on
  the self-service endpoint at all).

## 3. Preconditions

- Tenant is active — explicit precondition in the spec (§3). **Gap, not resolved here:** neither the spec
  nor the backlog states whether self-registration, manual creation, or bulk import should be blocked (vs.
  silently allowed) when the tenant is `trial`/`suspended`/`cancelled`, even though `FR-TM-3` states tenant
  status transitions "immediately affect login/access." This is a genuine precondition gap, not previously
  logged in `docs/requirements/open-decisions.md` — see §21 item 11.
- For self-registration: the tenant's storefront must correctly resolve tenant identity from
  subdomain/custom domain — this mechanism (`TenantResolutionFilter`, already implemented) already runs
  before any controller, for every request not in its explicit exclusion list, independent of
  authentication. A bad/unresolved subdomain must fail safely with no fallback to another tenant's form.
- For manual creation/bulk import: actor is authenticated as Tenant Admin or Student Support for that
  tenant. Unlike MVP-005 at its own planning time, RBAC-2 enforcement (`PermissionCheckService`,
  `@PreAuthorize`) already exists and is functioning — this precondition is genuinely satisfiable today.
- For profile self-service and history views: student is authenticated, and the endpoint resolves the
  caller's own `user_id` from the authenticated principal — never from a path/query parameter.
- For history views specifically: the underlying owning domain (`enrollment-management`,
  `payment-management`, `attendance-management`, `exam-management`) must exist and be reachable via its
  `api` for that section to show real data; none of the four exist yet in this codebase today, so every
  history section must degrade to an explicit "not yet available" state rather than a silent empty state
  (an empty *result* from a real domain must remain visually distinguishable from a *missing* domain — see
  §9).

## 4. User flows

### 4.1 Self-registration (STU-1)

**Normal flow:**
1. Anonymous visitor reaches the tenant's storefront; tenant identity is resolved server-side from
   subdomain/custom domain, never client-supplied.
2. Visitor submits the registration form (name, email, password); the resulting `tenant_user` row is
   created with `role = STUDENT` (hardcoded server-side, never derived from the request) and
   `must_change_password = false` (the student is setting their own credential directly — this is the
   opposite of every other account-creation path built so far, all of which hardcode `true`; getting this
   backwards in either direction is a concrete regression risk, see §15).
3. Student can then log in and complete their profile (guardian info, school/grade/stream — exact field
   list open, see §21 item 3).

**Alternative / edge-case flows:**
- Self-registration on a tenant that cannot be resolved (bad/unrecognized subdomain): fails safely, no
  fallback to another tenant's registration form — enforced upstream by `TenantResolutionFilter`'s existing
  anti-enumeration design (an unresolved subdomain and a resolved-but-not-login-eligible tenant are
  deliberately collapsed into one identical "unavailable" response before any controller runs).
- **Public vs. invite-only registration is an unresolved open decision** (spec, `user-roles-and-permissions.md`
  §1/Open Q4, `open-decisions.md` §1) — this is not cosmetic; it determines whether the endpoint is a truly
  anonymous public write or requires an invite-token precondition, materially changing this flow's shape.
  See §21 item 1.
- Duplicate registration (same email re-registering within the same tenant): rejected `409`, same
  duplicate-email pattern as Staff Management, but on a *public* endpoint the response must not become an
  enumeration vector (see §15).
- Cross-tenant: the same email registering independently on tenant A's and tenant B's storefronts must both
  succeed as two independent rows — per-tenant uniqueness, not global (mirrors `UNIQUE(tenant_id, email)`
  already on `tenant_user`).
- No email/identity-verification step is specified anywhere for student self-registration — unlike what
  you'd normally expect for a public, unauthenticated, credential-issuing endpoint. Not resolved here — see
  §21 item 12.
- A request body attempting to inject `tenantId`, `role`, or `mustChangePassword` fields must be silently
  ignored — the server-resolved values always win.

### 4.2 Manual creation (STU-2)

**Normal flow:**
1. Tenant Admin or Student Support creates a student record individually, mirroring `StaffService.createStaff`'s
   established pattern (permission re-check in the service layer as defense-in-depth on top of
   `@PreAuthorize`, single `@Transactional` method spanning the `UserProvisioningApi` call and the
   `StudentProfile` write).
2. Account carries `must_change_password = true`, enforced at next login.

**Alternative / edge-case flows:**
- A `V`-only role (any of the six named in §2) attempts manual creation: rejected 403, generalizing the
  spec's one worked Content-Manager example to every `V`-only role.
- Duplicate email within tenant: `409`, same pattern as Staff Management.
- **Role-collision** — a manual-create/import row targets an email already associated with a different role
  (Teacher, Staff) in the same tenant: **not addressed anywhere in the corpus** — see §21 item 13.
- Student Support attempting to set `must_change_password = false` at creation time (bypassing credential
  hygiene): must be rejected/ignored server-side — this DTO field should not exist on the manual-create
  request at all (mirrors Staff Management's `StaffCreateRequest`, which has no client-settable
  `mustChangePassword` field).

### 4.3 Bulk import via CSV (STU-2)

**Normal flow:**
1. Tenant Admin/Student Support uploads a CSV; server-side upload validation (MIME/content-sniffing, size,
   uploader permission) runs and rejects before any row is parsed, with no partial write on failure — per
   `.claude/rules/security.md`'s "Upload Validation" rule, which applies to every upload endpoint even
   though `03-student-management.md` itself doesn't restate it.
2. Each valid row creates a student account with `must_change_password = true`, tenant resolved once from
   the acting admin's session context for the whole batch — never per-row from file content.

**Alternative / edge-case flows (largest gap cluster in this module):**
- **Partial-row-failure behavior is an explicit, named open decision** in three independent sources (spec's
  own Open Decisions, STU-2's own AC #3, `open-decisions.md` §10) — all-or-nothing vs. row-level partial
  success with an error report is unresolved. This blocks writing a concrete AC for "N valid rows, M invalid
  rows" until decided. See §21 item 2 (renumbered relative to source reports — see the consolidated list).
- Duplicate rows within the same CSV file (two rows, same email): unspecified.
- A CSV row containing a `tenant_id`- or `role`-shaped column: must never override the server-resolved
  tenant/role, even if the parser is lenient about extra columns.
- Cross-tenant collision: a bulk-import run in tenant A's context must never write to or collide against
  tenant B rows, even when a row's email exactly matches an existing tenant B student's email (per-tenant
  uniqueness means this succeeds as a new tenant-A row, never a cross-tenant update/reject).
- Empty CSV / header-only CSV / wrong-column CSV: not addressed anywhere — should be an explicit edge case
  (reject with a clear error, no partial table state).
- Doing partial-row-failure handling carelessly is itself a tenant-isolation risk, not just a UX question: a
  row-level implementation that re-derives tenant from a per-row value instead of the one resolved session
  context would reopen the "never trust a client-supplied `tenant_id`" rule via a file upload rather than a
  request field. Flag as a specific risk to check for once the partial-failure design is ratified.

### 4.4 Profile edit

**Normal flow:**
- Student edits their own profile (guardian info, school/grade/stream, exact field list open — §21 item 3).
- Tenant Admin/Student Support edits any student's profile in-tenant.

**Alternative / edge-case flows:**
- A `V`-only staff role attempts edit: rejected 403 (spec's explicit worked example, generalized to every
  `V`-only role).
- **Student attempts to edit another student's profile by ID (same tenant):** rejected — the spec's explicit
  "no student-selector or ID-based cross-student navigation" rule is stated for *viewing* (§5) but applies
  equally to *editing*; this needs its own explicit acceptance criterion and test, distinct from the
  cross-tenant case (see §5, AC item 15/16).
- Cross-tenant: Student Support of tenant A attempts to edit tenant B's student: rejected 403/404 (explicit
  spec requirement), verified via a follow-up read proving the row is actually unchanged, not just the
  response code.
- Delete (`D`, Institute Owner only): **no flow is described anywhere for what "delete" actually does** —
  hard delete vs. deactivation/status change. Root `CLAUDE.md`'s "never delete financial history" combined
  with a student row's likely future FK-linkage to enrollment/payment/ledger rows makes a literal hard
  delete high-risk. Not resolved here — see §21 item 5.

### 4.5 History views (STU-3)

**Normal flow:**
- Student views own dashboard: enrollment/payment/attendance/exam/device/communication history, all
  backend-filtered to that student's own tenant-scoped records, each sourced via the owning domain's `api`
  (never duplicated/joined into `user-management`'s own tables).
- Tenant Admin/Student Support views a `Student Detail` page with the same data plus admin context, for any
  student in-tenant. Other `V`-only staff can view (no create/edit) per the matrix.

**Alternative / edge-case flows:**
- Student attempts to view another student's history by ID guessing: rejected — same no-selector,
  no-ID-navigation rule as the profile view, enforced structurally (see §9/§15: the self-service endpoint
  takes no id parameter at all).
- **Owning domain not yet implemented** (all four of enrollment/payment/attendance/exam-management, today):
  the corresponding history section must render an explicit "not yet available" state, never a silent empty
  state indistinguishable from "this student genuinely has zero enrollments/payments/etc." Conflating "no
  data" with "no capability" is a correctness bug in its own right, not just a polish item.
- Cross-tenant: Student Support of tenant A requests tenant B's student detail/history: rejected 403/404 —
  explicit mandatory cross-tenant test, and STU-3 is the module's own designated capstone story precisely
  because of this cross-domain fan-in.
- Payment history specifically must reflect ledger + slip state, never raw order/upload rows treated as
  "paid," consistent with `.claude/rules/payments.md` §2 — this constraint belongs to the *consuming* read
  in `payment-management`'s eventual `api`, not something `user-management` can violate on its own, but the
  composition layer must not "helpfully" infer a paid status from an order-shaped field if one is ever
  exposed loosely.
- **FR-UM-3 tags device/communication history as MVP, but the domains that would populate them
  (device-authentication, notification delivery logs) are both Phase 2** — a genuine phase-tag contradiction,
  not just a missing cross-reference (see §21 item 7). STU-3's own backlog dependency list doesn't even name
  a dependency for these two tabs, despite them being named in the module's business purpose.

### 4.6 Teacher roster view

**Normal flow:**
- Teacher requests their course roster; backend pre-filters to courses assigned to that teacher.

**Alternative / edge-case flows:**
- Teacher with zero assigned courses: empty roster, not an error.
- Teacher attempts to fetch a student outside any of their assigned courses by ID guessing: rejected — not
  explicitly named in the spec's own Teacher-roster line but required by the general enumeration-testing
  rule in `.claude/rules/security.md`.
- Teacher Assistant requesting the same roster: undefined (§21 item 8).
- Cross-tenant: rejected 403/404 — the spec's cross-tenant AC is worded around "Tenant Admin/staff" and
  doesn't explicitly enumerate Teacher; should be added explicitly.
- **This flow cannot be honestly built at this module's actual MVP-006 slice** — see §9, §15, and §21 item 2.

## 5. Acceptance criteria

Reconciled and deduplicated from the spec's own §8 checklist plus all three backlog stories' acceptance
criteria; items newly surfaced during this review (absent from the spec's own list but required by
cross-cutting rules elsewhere in the corpus) are marked **[added]**.

**Self-registration (STU-1):**
1. A self-registered student's account is tenant-scoped only to the resolving tenant, with tenant resolved
   server-side — never client-supplied.
2. Self-registration on an unresolvable tenant fails safely with no fallback to another tenant's form.
3. A self-registered account is **not** flagged `must_change_password`. **[added — present in STU-1's own AC
   but absent from the spec's §8 checklist; must not be dropped]**
4. Cross-tenant test: registering via tenant A's subdomain never creates a tenant-B-visible row.
5. **[added]** A request body attempting to inject `tenantId`, `role`, or `mustChangePassword` is ignored;
   the server-resolved/hardcoded values always win.

**Manual creation / bulk import (STU-2):**
6. A manually or bulk-created account carries `must_change_password = true`, enforced at next login.
7. Student Support create/edit succeeds; every `V`-only role (Content Manager and, by generalization, every
   other `V`-only role) is rejected 403.
8. Only Tenant Admin/Student Support may create manual or bulk-imported accounts — every other role
   rejected.
9. Bulk import in tenant A's context cannot write to or collide with tenant B rows.
10. Bulk import correctly processes a mix of valid and invalid rows with accessible progress/result
    reporting — **exact success/failure semantics blocked on the open partial-failure decision, §21 item 2.**
11. Bulk-import row validation/CSV parsing edge cases are covered (malformed rows, missing required fields,
    wrong types).
12. **[added]** Upload-level validation (MIME/content-sniffing, size, uploader permission) runs and rejects
    before any row is parsed, with no partial write to storage on failure.

**Profile:**
13. A student can view/edit their own profile fields (guardian info, school/grade/stream, exact field list
    pending §21 item 3).
14. Tenant Admin/Student Support can edit any in-tenant student's profile; other staff cannot.
15. Cross-tenant: Student Support of tenant A cannot view/edit tenant B's student profile — 403/404.
16. **[added]** Same-tenant cross-student enumeration test: student A cannot view/edit student B's profile
    by ID, within the same tenant — required by `.claude/rules/security.md`'s enumeration-testing mandate but
    absent from the spec's own §8 checklist, which only names the cross-tenant case. Distinct from #15.
17. Delete (`D`, Institute Owner only) — **cannot be written as a testable AC until the hard-delete-vs.-
    deactivation ambiguity (§21 item 5) is resolved.**

**History views (STU-3):**
18. A student's own dashboard/history data is 100% backend-filtered to their own tenant-scoped records; no
    student-selector or ID-based cross-student navigation exists anywhere in the UI or API.
19. History views are populated by reading from each owning domain's `api`; no data is duplicated into
    student-management's own tables (verified via architecture review, not just a unit test).
20. A Teacher's roster request is backend-pre-filtered to their assigned courses only — never fetched
    unfiltered and filtered client-side. **See §21 item 2 for this AC's actual buildability today.**
21. Cross-tenant negative test on student list/detail/history endpoints: tenant B's data never reachable by
    tenant A's Tenant Admin/staff/Teacher — treat as capstone per backlog.
22. Payment history reflects ledger + slip state only, never raw order/upload rows treated as "paid" (this
    binds the *future* `payment-management` `api` contract this module consumes, not something enforceable
    inside `user-management` alone).

**Empty/UI states:**
23. Two distinct empty states are implemented and tested: "no students yet" (with add/bulk-import CTA) vs.
    "no students match your filters" — must not be collapsed into one generic empty state.

**Phase-2 boundary:**
24. Any tags/risk indicators/inactive-detection surfaced (even as a stub) must be clearly derived/computed
    data, never a manually re-enterable field that could drift from source — this only applies if any
    Phase-2 scaffolding is touched; otherwise these fields simply must not exist in the MVP schema/UI at
    all.

## 6. Out-of-scope items

Per spec §10 and `FR-UM-4` (all Phase 2 unless noted):
- Student tags.
- Risk indicators.
- Inactive-student detection.
- Timeline / activity feed.
- Any of the above being manually settable/editable state, even if scaffolded early — must be computed,
  never hand-entered.

Also out of scope for this module specifically (owned by other domains, only *consumed* read-only by
Student Management once they exist):
- Device registration, limits, reset, suspicious-login detection (`FR-IAS-3` through `FR-IAS-7` — all Phase
  2). Directly affects whether the "device history" history-view tab can be meaningfully populated at MVP —
  see §21 item 7.
- Notification delivery logs (`FR-NM-4` — Phase 2 recommended), affecting the "communication history" tab
  similarly.
- Enrollment/payment/attendance/exam functionality itself (owned by their respective domains) — Student
  Management only reads from them once they exist; STU-3 is explicitly the capstone story to build last in
  this module for this reason.
- Course-teacher assignment / course-management's data model — the Teacher roster view's actual filtering
  substrate does not exist yet and is out of this module's ownership; see §21 item 2.
- A staff-count-style plan-limit enforcement for students — no such requirement exists anywhere in the
  corpus for Student Management (unlike Staff Management's `FR-UM-9`); not invented here.
- Custom/granular per-field permission overrides beyond the fixed `V`/`V,C,E`/`V,C,E,D` grants in the
  matrix — no source document requests a per-field permission grid for student profiles.

## 7. Domain model

Mirroring the `staff` sibling package's split (auth aspect on `tenant_user`, domain-profile aspect local to
`user-management`), with deliberate structural deviations justified by this module having a materially
different trust shape (an anonymous, unauthenticated creation path) than Staff Management:

- **Credential aspect** (email, password hash, login status, `must_change_password`, the `role` claim) is
  owned by `identity-access-service`, on the existing `tenant_user` table (V3). `Role.STUDENT` already
  exists in the enum.
- **Operational/profile aspect** (guardian/school/grade/stream fields — exact list open, §21 item 3) is
  owned by `user-management` as a new tenant-owned entity, `StudentProfile`, in a new sibling package
  `com.lms.usermanagement.student` alongside the existing `staff` package.
- **History-composition aspect** is not a `user-management` entity or table at all — it is a read-time
  composition service (`StudentHistoryService`) calling narrow `api` methods on `enrollment-management`,
  `payment-management`, `attendance-management`, `exam-management` (once each exists) plus
  `identity-access-service`'s existing `UserProvisioningApi`/`TenantUserSummary` for the credential-derived
  fields (email, status). No cross-domain join, no duplicated storage, per
  `docs/architecture/database-architecture.md` §5 and `.claude/rules/architecture.md`.
- **Bulk-import aspect** has no dedicated entity/table — a CSV import reuses the same single-row creation
  path (`StudentProfile` + `tenant_user`) per valid row; no speculative `import_batch`/`import_row_result`
  table is designed here, matching the backlog's own explicit position ("No dedicated import-batch table
  unless partial-failure reporting is decided") and the open partial-failure decision (§21 item 2).

**`StudentProfile` (the one entity this module owns for MVP-006):**
- Belongs to exactly one `Tenant`.
- References exactly one `tenant_user` credential row **by id only** — never a JPA `@ManyToOne` across the
  module boundary, matching `StaffProfile`'s precedent exactly (`.claude/rules/architecture.md`: a module
  must never import another domain's `domain` classes).
- Has no owned child entities (history is an external, request-time read, not an FK-linked child table).
- Deliberately does **not** carry a local `status` column — see §8's flagged deviation from the backlog's
  literal STU-1 field list.

## 8. Database design

### 8.1 Conceptual `student_profile` column list

Not a migration file — a conceptual design to feed the actual Flyway migration at implementation time.

**Stays on `tenant_user` (V3), NOT duplicated here** — same reasoning as the `staff_profile`/STAFF-1
precedent:
- `email` (`UNIQUE(tenant_id, email)`, already exists)
- `password_hash`
- `role` (`STUDENT`, already FK'd to the `role` catalog per V7/V8)
- `must_change_password` (already exists, `BOOLEAN NOT NULL DEFAULT false`) — reused as-is: `true` for
  manual/bulk creation, left `false` for self-registration (a write-path distinction at the two creation
  entry points, not a schema change)
- `totp_secret` (unused, reserved)

**Flagged deviation from the backlog's literal STU-1 field list — `status`.** STU-1's own database-impact
line (`product-backlog.md` line 358) lists `status` as a `student_profile` column, but this conflicts with
the `staff_profile`/STAFF-1 precedent, which deliberately does **not** duplicate `status` because
`tenant_user.status` (`active`/`suspended`) already models the account-level state, and duplicating it risks
drift. Nothing in the spec or `open-decisions.md` identifies a student-specific status value that
`tenant_user.status` can't express — Phase-2 "inactive detection" is explicitly "derived, never manually
re-enterable state" (spec §8 AC), so it is not a stored column at all. **Recommendation: do not add a
`status` column to `student_profile`, reuse `tenant_user.status`**, consistent with the `staff_profile`
precedent — flagged here as an explicit, reasoned deviation from the backlog's literal text, not a silent
omission. This should be confirmed, not silently assumed, before the actual migration is authored.

**Structurally certain (mechanics, not open content questions):**
- `id` — UUID PK, application-generated (UUIDv7 via `UuidV7Generator`), no DB-side default, per V1's
  baseline convention.
- `tenant_id` — `UUID NOT NULL REFERENCES tenant(id)`.
- `user_id` — `UUID NOT NULL`, backed by the composite FK below (opaque logical FK, never a JPA
  relationship).
- `created_at`, `updated_at`, `created_by`, `updated_by` — via the existing `Auditable` convention, no
  DB-side default, matching `staff_profile`'s shape exactly.

**Provisional / unconfirmed — per `docs/requirements/open-decisions.md`'s explicit "Exact field list for
'guardian/parent information' and 'school/grade/stream' is not itemized," the backlog's own STU-1 sketch
(`guardian_name, guardian_contact, school, grade, stream`) is a rough placeholder, not a ratified column
list. Do not commit final types/lengths/nullability without a follow-up decision:**
- `guardian_name` — type/nullability/multiplicity unconfirmed (is more than one guardian ever needed?).
- `guardian_contact` — phone vs. email vs. both is unspecified.
- `school` — unconfirmed.
- `grade` — free text vs. enum/lookup unspecified.
- `stream` — free text vs. enum/lookup unspecified.

### 8.2 Constraints / indexes (matching `staff_profile`'s rigor)

```
UNIQUE (tenant_id, user_id)     -- one profile per credential per tenant (natural key)
INDEX  (tenant_id, id)          -- tenant-scoped lookup shape TenantAwareRepository needs (explicit,
                                 -- since the PK here is a bare `id`, not composite with tenant_id)

FOREIGN KEY (tenant_id, user_id) REFERENCES tenant_user (tenant_id, id)   -- composite FK, not a bare
                                 -- FK on user_id alone — guarantees a student profile can never
                                 -- reference a different tenant's tenant_user row. Relies on
                                 -- tenant_user's existing UNIQUE(tenant_id, id) from V3; no change
                                 -- to V3 needed.
```

`tenant_id` leads every composite index, per `.claude/rules/backend.md`; no bare `tenant_id`-only index. No
further indexes are speculatively added on the provisional guardian/school/grade/stream fields until their
actual query pattern (filter/search) is confirmed, consistent with `.claude/rules/backend.md`'s "index for
the tenant-scoped query shape the module actually uses," not speculative columns.

### 8.3 Migration numbering/sequencing

`V10__create_staff_profile.sql` is the highest existing migration as of this plan (confirmed across `main`
and all active feature branches). **`V11` is free as of this check** — but this is a snapshot, not a
reservation. Per root `CLAUDE.md` and `docs/architecture/database-architecture.md`, migration history is
append-only and change-controlled; if another module's migration lands on `main` first and claims `V11`
before this module is implemented, the correct response at implementation time is to pick the next free
number, never renumber or edit either migration.

### 8.4 Bulk-import — no dedicated import-batch table

Confirmed: no new table should be speculatively designed for bulk-import batch/partial-failure tracking at
this stage, matching the backlog's own explicit position and the open partial-failure decision (§21 item 2).
If row-level partial-success reporting is later chosen, that becomes a new table added via a new migration
at that time, not retrofitted into this module's initial migration.

## 9. Backend design

Package: `com.lms.usermanagement.student`, sibling to the existing `com.lms.usermanagement.staff` package,
per `.claude/rules/architecture.md`'s per-domain structure.

```
com.lms.usermanagement
|-- api                              # existing convention — reserved, not built yet (no real consumer
|                                    #   exists today; see the forward-looking asks below)
|-- staff/...                        # unchanged (MVP-005)
|-- student
|   |-- domain
|   |   `-- StudentProfile.java              # implements TenantOwned, extends Auditable
|   |-- repository
|   |   `-- StudentProfileRepository.java    # extends TenantAwareRepository<StudentProfile, UUID>
|   |-- service
|   |   |-- StudentRegistrationService.java  # PUBLIC/anonymous path (STU-1) — no PermissionCheckService
|   |   |                                    #   call anywhere in this class; there is no authenticated
|   |   |                                    #   principal on this path
|   |   |-- StudentService.java              # authenticated admin path: manual create/list/detail/edit
|   |   |                                    #   (STU-2/profile), re-checks DomainArea.STUDENTS
|   |   |                                    #   permission in the service layer, mirroring StaffService
|   |   |-- StudentBulkImportService.java    # CSV parsing/row validation/chunked import (STU-2) — kept
|   |   |                                    #   separate from single-row create so the still-open
|   |   |                                    #   partial-failure decision doesn't leak into the simpler
|   |   |                                    #   path's design
|   |   `-- StudentHistoryService.java       # STU-3 cross-domain composition — its own class, given
|   |                                        #   it is "the largest single-story fan-in of cross-module
|   |                                        #   api dependencies in the MVP backlog" per STU-3's own
|   |                                        #   backend-impact line
|   |-- web
|   |   |-- StudentRegistrationController.java   # public, permitAll, POST self-registration only
|   |   |-- StudentController.java               # authenticated CRUD/list/detail/history, gated by
|   |   |                                        #   DomainArea.STUDENTS via @PreAuthorize
|   |   `-- dto/...
|   `-- config
`-- config
```

**Why two controllers, not one.** Staff Management has no unauthenticated write path at all, so
`StaffController` never had to make this distinction. Physically separating `StudentRegistrationController`
from `StudentController` makes it structurally hard to accidentally leave the admin endpoint `permitAll`, or
leave the registration endpoint behind an authentication gate it shouldn't have — a single shared controller
invites exactly that class of mistake. `StudentRegistrationService` must never call
`PermissionCheckService.requirePermission(...)` anywhere (there is no authenticated principal on that path);
copying `StaffService`'s defense-in-depth pattern onto the registration path would simply 403 every
self-registration attempt, or invite a "fix" that loosens the check in a way that also weakens the admin
path.

**One `StudentProfile` entity regardless of creation path.** Self-registration, manual creation, and bulk
import all write the same entity shape — only `must_change_password` (an existing `UserProvisioningApi`
parameter) differs per path. A design that invents a parallel table/entity for "self-registered" vs.
"admin-created" students would violate the one-aggregate-one-table rule for no reason.

**Reusing `UserProvisioningApi` as-is — no interface change required.** `UserProvisioningApi.
provisionTenantUser(email, rawPassword, roleCode, mustChangePassword)` and `existsByEmail(email)` are both
already implemented purely in terms of `TenantContext.getTenantId()` — nothing in the implementation is
admin-caller-shaped, and `TenantResolutionFilter` already resolves tenant identity into `TenantContextHolder`
before Spring Security's authentication filters run at all, for any request not in its explicit exclusion
list (mirroring the existing `/api/v1/auth/login` `permitAll()` + tenant-resolved precedent). Student
self-registration follows the same shape: `roleCode = "STUDENT"` hardcoded (never derived from the request,
mirroring `StaffService`'s `ASSIGNABLE_STAFF_ROLES` restriction pattern) and `mustChangePassword = false`.

**What genuinely is new, and must not be copy-pasted from the Staff precedent without adjustment:**
1. **Routing.** `StudentRegistrationController`'s endpoint must be added to the security config's
   `permitAll()` matcher list (like `/api/v1/auth/login`) but **must not** be added to
   `TenantResolutionFilter.shouldNotFilter`'s exclusion list — that list currently excludes
   `/api/v1/tenant-registrations` specifically because *creating a new tenant* has no subdomain to resolve
   yet. Student registration is the opposite case: it must always resolve an *existing* tenant. Conflating
   these two "public endpoint" precedents is the single most likely implementation mistake in this module.
2. **Abuse protection is a genuinely new gap this module introduces.** No rate-limiting mechanism exists
   anywhere in this backend today (confirmed: no bucket4j/resilience4j-ratelimiter dependency, no
   rate-limit code pattern anywhere). This already affects `/api/v1/auth/login` and
   `/api/v1/tenant-registrations`, but student self-registration is a materially larger abuse surface (mass
   fake-account creation, subdomain/tenant probing at volume). This is a cross-cutting infrastructure gap
   this module inherits, not something to solve ad hoc inside `user-management` — see §15 and §21 item 6.
3. **Password strength.** No password-policy/strength validation exists anywhere in the backend today (only
   Argon2 hashing config). A public self-service account-creation endpoint is exactly the surface where a
   missing password policy matters most — see §15 and §21 item 6.
4. **Enumeration risk on the friendlier pre-check.** Reusing `existsByEmail`'s "friendlier pre-check before
   attempting creation" pattern on a *public* endpoint means an anonymous caller can probe whether an email
   is already registered for a resolved tenant — a materially different risk profile than Staff Management's
   admin-only caller. See §15.
5. **The self-service "own profile"/"own history" endpoints must take no id parameter at all.** The spec's
   alternative flow is explicit: "no student-selector or ID-based cross-student navigation exists."
   `docs/requirements/user-roles-and-permissions.md`'s permission matrix has no "Student" row at all — a
   student is a separate actor with "own record only" scope. The structurally safe design resolves the
   caller's own `user_id` from the authenticated principal and loads `StudentProfile` by
   `(tenantId, userId)` directly — never accepts an `{id}` path parameter and relies on a same-id check
   alone (that pattern is one missed check away from IDOR).

**Cross-domain history composition (STU-3) — the compliant "degrade gracefully" pattern.** Following the
precedent MVP-005 set for its own not-yet-existing dependency (Staff Activity Log deferring cleanly on
`audit-log-management` rather than inventing a mock interface):
- **Do not invent placeholder `api` interfaces for the four missing domains inside `user-management`.** A
  hand-rolled stub interface would misrepresent a contract that each future domain hasn't designed yet, and
  would have to be discarded/reconciled once the real domain lands.
- **Ship this module with each history section genuinely absent/"not yet available," never silently
  empty.** An empty enrollment list must never be indistinguishable from "enrollment-management doesn't
  exist yet" — the former is real data, the latter is a missing capability.
- **When each real domain lands, `StudentHistoryService` adds one narrow, batched `api` read at a time**
  (e.g. `EnrollmentHistoryApi.findEnrollmentsForStudent(userId)` once `enrollment-management` ships its own
  `api`) — never all four wired against guessed contracts up front.
- Each history section must degrade **independently** — one slow/absent dependency must not fail the whole
  composed Student Detail response.
- Never a cross-schema SQL join, never an imported foreign `domain`/`repository` package, even once those
  tables physically exist in the same Postgres instance — both are explicit ADR-required violations per
  `.claude/rules/architecture.md`.

**Forward-looking asks to feed into future domains' own design work** (mirroring how MVP-005 fed two
concrete asks into AUTH-1/RBAC-1):
1. **A batched duplicate-email check on `UserProvisioningApi`**, needed for STU-2's bulk CSV import — today
   the interface only offers `existsByEmail(String)` (single) and `findTenantUserSummaries(Collection<UUID>)`
   (batched, by id). A bulk import calling `existsByEmail` once per CSV row is exactly the in-process N+1
   anti-pattern `findTenantUserSummaries`'s own javadoc warns against. Concrete ask for
   `identity-access-service`: add a tenant-scoped `Set<String> findExistingEmails(Collection<String> emails)`
   (or equivalent) before STU-2 is implemented.
2. **Reserve a `StudentDirectoryApi` slot in `user-management`'s own `api` package now** (documented, not
   necessarily built), for predictable future consumers: `enrollment-management` validating a target student
   exists/is active before creating an enrollment row; `notification-management` needing guardian
   contact info (`user-management`-owned, not duplicable); `attendance-management`/`exam-management`
   wanting a narrow student-summary read to compose their own views without re-storing profile fields.
3. **Device-authentication's per-student override ownership is an open question worth resolving now, not
   discovering later.** `.claude/rules/security.md`'s device-limit override precedence names
   "student-level exception" as the most specific override. Whether that value is data
   `identity-access-service`/device-authentication owns itself, or a `StudentProfile` field read via this
   module's future `api`, isn't resolved by anything in the current corpus — flag, don't decide unilaterally.
4. **Each future domain (`enrollment-management`, `payment-management`, `attendance-management`,
   `exam-management`) should plan a single-student, narrow, tenant-scoped read method in its own `api`
   package from day one of its own planning**, since STU-3 is documented as their first/largest consumer.

## 10. API contract

Draft only — must be finalized via the `review-api-contract` skill into `docs/api/user-management.md`
before implementation starts on either side, per `docs/api/README.md`'s own process. All responses use the
existing `com.lms.common.api.ApiResponse<T>` envelope. No client-supplied `tenant_id`, role, or other
trust-sensitive field is ever accepted.

| Method + path | Auth | Notes |
|---|---|---|
| `POST /api/v1/students/register` | Public (`permitAll`), tenant resolved via `TenantResolutionFilter` from subdomain | Body: name, email, password only — no `tenantId`/`role`/`mustChangePassword` field accepted even if present. `201` with a minimal confirmation, or `409` on duplicate-email-in-tenant (response shape must not leak cross-tenant existence — see §15), or `400` on validation failure. **Blocked on §21 item 1** (public vs. invite-only) before finalizing whether this endpoint exists in this literal shape. |
| `POST /api/v1/students` | Tenant Admin + Student Support only | Body: name, email, password, guardian/school fields (exact set open, §21 item 3). `201`, or `409` on duplicate-email, or `400` on validation failure. Always sets `mustChangePassword = true` server-side — no client-settable field. |
| `POST /api/v1/students/bulk-import` | Tenant Admin + Student Support only | Multipart CSV upload. Server-side upload validation (MIME/size/ownership) before any row parsed. Exact response shape (all-or-nothing vs. row-level report) **blocked on §21 item 2.** |
| `GET /api/v1/students` | Tenant Admin + Student Support + every `V`-only staff role | Paginated, filterable, tenant-scoped. Distinguishes zero-data vs. filtered-empty per §5 AC 23. |
| `GET /api/v1/students/{id}` | Tenant Admin + Student Support + every `V`-only staff role | `403`/`404` (uniform) if `{id}` belongs to another tenant, or another student in the same tenant accessed via a staff-only lens — this is the staff-facing detail endpoint, never reachable by a Student-role caller for another student's id. |
| `PATCH /api/v1/students/{id}` | Tenant Admin + Student Support only | Profile edit. `403`/`404` for cross-tenant `{id}`. |
| `DELETE /api/v1/students/{id}` or a status-change endpoint | Tenant Admin only | **Shape genuinely undecided** pending §21 item 5 — do not build either a hard `DELETE` or an invented status-transition endpoint until resolved. |
| `GET /api/v1/students/me` | Student (self) only | **No `{id}` path parameter** — resolves the caller's own `user_id` from the authenticated principal. Returns the caller's own `StudentProfile` only. |
| `PATCH /api/v1/students/me` | Student (self) only | Same no-id-parameter design; the student-editable field subset may differ from the staff-facing edit endpoint (open, §21 item 3). |
| `GET /api/v1/students/{id}/history` (staff-facing) and `GET /api/v1/students/me/history` (self-service) | Same actor sets as detail/`me` above | Each history sub-section (enrollment/payment/attendance/exam/device/communication) composed via `StudentHistoryService`; sections for domains that don't exist yet return an explicit "not yet available" marker, never a silent empty array. `403`/`404` for cross-tenant `{id}` on the staff-facing variant. |
| `GET /api/v1/teacher/roster` (or similarly scoped path, exact ownership TBD) | Teacher | **Not buildable at this module's actual MVP-006 slice** — see §9/§15/§21 item 2. Deferred, not stubbed. |

Every endpoint above resolves `tenant_id` exclusively from the authenticated session context (or, for the
public registration endpoint, from `TenantResolutionFilter`'s pre-resolved context) — never from request
body/query/path/header.

## 11. Frontend screens

Portals: `app/(public)/` (self-registration), `app/(tenant-admin)/` (Student List/Detail/Bulk Import),
`app/(student)/` (own profile + history), `app/(teacher)/` (roster — deferred, see §9).

**Route finding — `(auth)/register` is NOT the right home for STU-1's self-registration form.** This is
already resolved by precedent in this codebase: `app/(public)/register-institute/page.tsx` contains an
explicit doc comment stating its own route was "Deliberately NOT at `/register` — that path is already owned
by `app/(auth)/register/page.tsx`, an unrelated, pre-existing disabled placeholder for a future generic
user-account-creation flow." That existing placeholder has no tenant-scoping logic, no subdomain resolution,
a disabled `fieldset`, and no submit handler at all — it is not tenant-scoped and not student-specific.
**Recommendation, following the `register-institute` precedent: a new route under `app/(public)/`** (e.g.
`app/(public)/register/page.tsx`), not a repurposing of `(auth)/register`. Flagged as an explicit naming
decision to make deliberately before scaffolding, given two unrelated "registration" concepts already exist
or are pending at similar-sounding paths.

### `app/(tenant-admin)/tenant-admin/students/`

| Screen | Route | Key components | Notes |
|---|---|---|---|
| **Student List** | `/tenant-admin/students` | Shared responsive data-table (**does not exist yet** — the only prior table implementation, `platform-admin/tenants/page.tsx`, is explicitly marked scaffold-only against mock data and hand-rolled as page-local JSX, not extracted to `components/ui/`) + card-fallback below `md`, filter/search, `Badge` for status (new primitive, no shared `Badge` exists yet), "Add student" + "Bulk import" CTAs (two, not one — the empty state must surface both entry points) | Spec explicitly designates this screen as "the canonical example of the shared responsive data-table component" — MVP-006 is the first module where this repo-wide debt (flagged since MVP-005's own plan) must actually be paid, not repeated as a third hand-rolled copy. Two distinct empty states per §5 AC 23. |
| **Student Detail** | `/tenant-admin/students/[studentId]` | Profile editor (RHF + Zod), **Tabs** component (new primitive — doesn't exist yet) for the cross-domain history sections | Cross-tenant/not-found responses render uniform generic copy. Each history tab shows its own loading/populated/empty/"not yet available" state independently — one tab's absence must never block another's render. |
| **Bulk Import** | `/tenant-admin/students/bulk-import` | File-upload control (new primitive), step indicator/stepper (new, likely feature-local rather than generic `ui/`), CSV row-level result report | Full-screen `Sheet` on mobile if modal-based, per `.claude/rules/ui-ux.md` §5's explicit "cramped modal makes bulk-action forms unusable" guidance. Exact result-report shape blocked on §21 item 2. |

### `app/(student)/student/`

| Screen | Route | Notes |
|---|---|---|
| **My Profile** | `/student/profile` | Same Tabs primitive reused for own history sections; no id-based URL parameter anywhere — must be structurally impossible to view another student's data via URL manipulation. Currently a stub nav entry with no `href`. |

### `app/(teacher)/teacher/`

| Screen | Route | Notes |
|---|---|---|
| **Course Roster** | `/teacher/roster` (exact shape TBD) | Reuses the same shared data-table primitive as Student List. **Deferred — not buildable until `course-management`'s teacher-assignment model exists (§21 item 2).** No nav entry exists yet in `teacher-nav.tsx` today either. |

**New `components/ui/` primitives needed**, in priority order for this module (current inventory: `Button`,
`Card`, `Input`, `Label`, `Sheet`, `Skeleton`, `Alert`, `AlertDialog` — no `Table`, `Tabs`, `Select`,
`RadioGroup`, `Checkbox`, `Badge`, or file-upload primitive exists):
1. **Data table** (shared responsive/card-fallback primitive) — blocks Student List and (later) Course
   Roster equally; build once, reuse both places.
2. **Tabs** — blocks Student Detail and My Profile's history sections.
3. **File upload control** — blocks Bulk Import.
4. **Step indicator/stepper** — blocks Bulk Import (scope as feature-local vs. generic `ui/`, TBD).
5. **Select** — Student List filters, Bulk Import field-mapping if applicable.
6. **Badge** — status indicators (must-change-password flag, active/inactive, import-row status).
7. **Checkbox/RadioGroup** — lower confidence, scope-dependent on final Bulk Import row-retry UX.

New primitives should follow the codebase's existing `@base-ui/react`-backed shadcn pattern (confirmed via
`sheet.tsx`), not introduce a second primitive library.

**Nav additions needed:** `tenant-admin-nav.tsx` needs a "Students" entry (currently only
`Dashboard | Profile | Settings`, the latter two unbuilt stubs); `student-nav.tsx`'s existing "Profile" stub
becomes the My Profile route; `teacher-nav.tsx` has no roster entry at all yet (deferred alongside the
screen itself).

**Empty-state requirements** (per `.claude/rules/ui-ux.md` §3, using the existing, correctly-built
`EmptyState`/`ErrorState`/`LoadingState`/`PermissionDeniedState`/`QueryStateBoundary` components as-is —
these already force per-call-site copy, no default reused):
- Student List: "no students yet" (two CTAs: Add student, Bulk import) vs. "no students match your filter"
  (Clear-filters action, no creation CTA) — two distinct states, distinct copy.
- Bulk Import result screen: its own "0 rows imported" zero state, distinct from the List's empty states.
- Each of the six history tabs: its own contextual empty-state copy ("No payments recorded yet" vs. "No
  enrollments yet," etc.) — not one generic "No data in this tab."
- Course Roster (deferred): "no students enrolled in this course" vs. "no assigned courses" as two distinct
  states, per the same pattern `.claude/rules/ui-ux.md` §3 already prescribes for Teacher's empty course
  list elsewhere.

## 12. Validation rules

- **Name:** required.
- **Email:** required, Zod `.email()` client-side format check (UX convenience only — tenant-scoped
  uniqueness is only knowable from the backend response at submit time).
- **Password (self-registration and manual-create both accept a password field, unlike Staff Management's
  admin-set-only pattern):** required, minimum length — **exact policy blocked on §21 item 6** (no
  password-strength policy exists anywhere in this backend today). Do not invent a specific rule set
  unilaterally in this plan.
- **Role:** never a client-settable field on any Student Management DTO — always hardcoded `STUDENT`
  server-side.
- **`mustChangePassword`:** never a client-settable field on any DTO — server sets `true` (manual/bulk) or
  leaves `false` (self-registration) based on which endpoint was called, never a request value.
- **Guardian/school/grade/stream fields:** validation rules cannot be finalized until the exact field
  list/format (free text vs. enum) is resolved — §21 item 3.
- **Bulk-import CSV:** server-side MIME/content-sniffing + size + uploader-permission validation before any
  row is parsed, per `.claude/rules/security.md`. Row-level field validation mirrors the manual-create DTO's
  rules once the field list is settled. Exact malformed-row handling blocked on §21 item 2.
- **Tenant/subdomain fields:** never present on any Student Management form — tenant is always resolved
  server-side.

## 13. Error cases

| Case | Expected behavior | Status |
|---|---|---|
| Self-registration on an unresolvable subdomain | Generic "unavailable" response, no fallback to another tenant — enforced by `TenantResolutionFilter` upstream of any controller | Settled, reuses existing mechanism |
| Duplicate email within tenant (self-registration) | `409`, response shape must not distinguish "exists in this tenant" from any other failure in a way that leaks timing/shape differences at the *tenant-resolution* boundary — this endpoint is the first public, credential-issuing write in the codebase and needs its own explicit enumeration check, not a copy of Staff Management's message | Settled shape; **new enumeration-risk surface, see §15** |
| Duplicate email within tenant (manual create) | `409`, same pattern as Staff Management | Settled, precedent exists |
| Non-Owner/non-Student-Support role reaches a mutating student endpoint | Rejected 403 server-side, independent of client UI state | Settled requirement |
| Read-only Auditor attempts a mutation | Controls don't render (hide, not disable-with-tooltip); direct API attempt still fails 403 | Settled requirement |
| Cross-tenant access (guessed/edited `{id}`) | Backend rejects 403/404; frontend renders uniform generic "not found" copy regardless of which status came back | Settled requirement |
| Same-tenant cross-student access (student browsing another student's data) | Structurally impossible — self-service endpoints take no id parameter at all, per §9's design | Settled requirement, structural not just permission-checked |
| Bulk-import partial row failure | No defined shape — do not build a client-side transition/report UI implying one | **Open decision — §21 item 2** |
| Delete/status-change attempted | No defined state machine — gate any control as unavailable until ratified | **Open decision — §21 item 5** |
| Password too weak | No defined policy — do not invent client-side rules that outrun the (currently nonexistent) backend policy | **Open decision — §21 item 6** |
| History tab for a not-yet-existing domain | Explicit "not yet available" state, never a silent empty array indistinguishable from real zero-data | Settled design requirement (§9) |
| Teacher requests roster | Endpoint does not exist at this module's buildable slice — do not stub with an unfiltered/mock roster | **Deferred — §21 item 2**, not an error case to design around yet |

## 14. Tenant-isolation rules

**Ownership and scoping.** Student accounts are tenant-owned data. `tenant_user` already carries
`NOT NULL tenant_id` + `UNIQUE(tenant_id, email)`; `student_profile` will carry `NOT NULL tenant_id` +
`UNIQUE(tenant_id, user_id)` + a composite FK `(tenant_id, user_id) -> tenant_user(tenant_id, id)`. Never a
global unique constraint on email — reject on sight in review if proposed.

**Cross-tenant negative-test shape, per endpoint** (every one needs a passing test proving 403/404, never
200 with empty/filtered data, mirroring MVP-005's granularity — one test per role where the matrix has
distinct roles, not one generic test):

- **Self-registration (public)** — registering via tenant A's subdomain must never create a row visible or
  queryable under tenant B (verified by querying tenant B's table directly, not just tenant A's list
  endpoint); the same email registering independently under tenant A's and tenant B's subdomains must both
  succeed as two independent rows.
- **Manual create** — a request crafted with any injected tenant-B-shaped field must still create the row
  under the acting admin's own tenant only.
- **Bulk import** — a bulk-import run in tenant A's context cannot write to or collide against tenant B
  rows, including when a CSV row's email exactly matches an existing tenant B student's email (must succeed
  as a new tenant-A row, never a cross-tenant update or silent reject).
- **List** — no filter/pagination/search combination leaks tenant B rows; the query must be structurally
  incapable (via `TenantAwareRepository`), not merely filtered by convention.
- **Detail** — tenant A actor requesting tenant B's student id → 404 (uniform, non-distinguishing), no
  tenant-B-derived field leaked in the response body.
- **Edit** — tenant A actor attempting to edit tenant B's student → 404, verified via a follow-up read that
  the row is actually unchanged, not just the response code.
- **History (each of the up-to-six sub-views independently)** — tenant A actor requesting tenant B's
  student id's history → 404 for *each* history sub-view independently, not assumed covered by one test on
  the parent id if sub-views are separate endpoints/params. Additionally, each domain's own future `api`
  call inside the composition must itself be tenant-scoped from the resolved context, never receive a
  client-supplied tenant id threaded through from `user-management`.
- **Self-service "me" endpoints** — must never accept/honor any id-shaped override parameter; a student
  authenticated in tenant A must never be able to substitute a parameter to view another student's data,
  even within tenant A itself.
- **Teacher roster (once buildable)** — a Teacher in tenant A must never see tenant B's students, and
  separately, must never see tenant A students from a course they are not assigned to — both halves belong
  in the same test file for traceability, but are conceptually distinct checks (tenant isolation vs.
  course-assignment scoping).

**The repository-method check.** No repository method in `user-management`'s student repository may accept
a caller-supplied `tenant_id` parameter — every method relies on `TenantAwareRepository`'s already-resolved
tenant context.

## 15. Security rules

**(a) Self-registration endpoint — the module's single largest new security surface.**
- **Tenant resolution.** The endpoint must rely on `TenantResolutionFilter` having already resolved
  `tenant_id` into `TenantContextHolder` — it must **not** be added to that filter's `shouldNotFilter`
  exclusion list (the existing exclusion for `/api/v1/tenant-registrations` is specific to *new-tenant*
  creation, which has no subdomain to resolve; conflating the two precedents is the single most likely
  implementation mistake here, per §9).
- **Role hardcoding.** `roleCode` must be a literal `"STUDENT"` in `StudentRegistrationService`, never
  derived from any request field — treat "self-registration accepts/derives a role other than STUDENT" as a
  critical defect if found, with an explicit test that a request body containing a `role`/`roleCode` field
  of any other value is ignored.
- **`mustChangePassword = false`, explicitly, not merely "left unset."** This is the first account-creation
  path in the codebase where this flag must be `false` — every prior path (Staff) hardcodes `true`. A
  copy-paste risk in either direction is real and needs its own explicit test, not folded into a general
  "row created correctly" test.
- **Password strength — genuine, currently-unaddressed gap.** No password-policy/strength validation exists
  anywhere in this backend today (confirmed: only Argon2 hashing config exists, no minimum-length/complexity
  check anywhere). A public, unauthenticated, self-service account-creation endpoint is exactly the surface
  where this matters most. Needs an explicit design decision before implementation — not resolved here, see
  §21 item 6.
- **Rate-limiting / abuse prevention — genuine, currently-unaddressed gap.** No rate-limiting mechanism
  exists anywhere in this backend (confirmed: no rate-limit dependency or code pattern found). This already
  affects `/api/v1/auth/login` and `/api/v1/tenant-registrations`, but student self-registration is a
  materially larger abuse surface (mass fake-account creation at volume, subdomain/tenant probing). This is
  a cross-cutting infrastructure gap the module inherits, not something to solve ad hoc inside
  `user-management` — flag as a blocker-or-explicitly-accepted-residual-risk decision, not resolved here,
  see §21 item 6.
- **Enumeration risk, two distinct layers.** `TenantResolutionFilter` already structurally prevents
  tenant-existence enumeration (an unresolved subdomain and a resolved-but-ineligible tenant collapse into
  one identical response, before any controller runs) — this carries over automatically. The **second**
  layer, specific to this new endpoint, is the registration endpoint's own business logic: if any error path
  lets a caller distinguish "subdomain resolves to a real, active tenant" from "subdomain doesn't exist"
  through means *other than* the filter's own response (a differently-shaped 500 on a downstream error for
  one case vs. a clean 4xx for the other, or a timing difference in the duplicate-email check), that
  reintroduces the enumeration vector the filter was designed to close. Needs an explicit test, analogous to
  MVP-005's "not found in my tenant vs. exists in a different tenant must be indistinguishable in response
  status, body shape, and approximate timing" — applied here to the tenant-resolution boundary of a public
  endpoint rather than a same-tenant staff id.
- **No client-supplied `tenant_id`/role/`mustChangePassword`, ever** — same bar as MVP-005 AC 7/§14.
- **This is not an authentication-architecture change requiring an ADR** (it reuses `UserProvisioningApi`/
  `tenant_user` unmodified, the same mechanism `StaffService` already established) — but it materially
  changes the public-attack-surface (the codebase's public-endpoint count roughly doubles, and unlike
  tenant-registration — which only creates a pending-approval tenant with no immediate login — this endpoint
  creates an *immediately-usable, authenticated login* on a real active tenant). Flag explicitly for security
  review sign-off given the blast-radius, without treating it as change-controlled in the formal sense.

**(b) Self-service "own profile"/"own history" — structural, not permission-checked, isolation.** The
correct design resolves the caller's own `user_id` from the authenticated principal and never accepts an
`{id}` parameter at all — making cross-student browsing structurally impossible rather than merely
permission-denied. A naive design gating a `{id}`-accepting endpoint through
`PermissionCheckService.hasPermission(DomainArea.STUDENTS, VIEW)` would be doubly wrong: that check is
modeled for staff-type roles (would incorrectly default-deny a student viewing their own data), and even if
patched to allow it, relying on a same-id check alone is one missed check away from IDOR.

**(c) Staff-facing mutation authorization — same allow-list discipline as MVP-005.** Institute Owner and
Student Support only for create/edit; every other role (including Read-only Auditor) implemented as a
positive allow-list reaching the handler, not a deny-list enumerating denied roles — a deny-list is one
missed enum value away from silently admitting a new sub-role. Both a UI-hiding check and a direct-API
deny-path test required per role; UI-hiding alone is not evidence of enforcement.

**(d) Teacher roster — honest deferral, not a fake filter.** Per §9: `course-management`'s teacher-assignment
data model does not exist yet, so there is no source-of-truth this endpoint could join against or call into
today. Building it now would necessarily either fake the filter with an unenforced stub (violating the
"backend-pre-filtered, never client-filtered" requirement in spirit even if not in literal code shape) or
ship an unfiltered-roster fallback labeled "temporary" — a real over-exposure risk sitting in production
code, not theoretical. **Recommendation: treat this exactly as MVP-005 treated its own hard blockers — defer
explicitly, do not build a stopgap.** See §21 item 2.

**(e) Credential-creation contract boundary.** `user-management` must never itself construct, hash, or
persist a password/credential value — it delegates entirely to `UserProvisioningApi`, same discipline as
Staff Management. At review time: reject any `user-management` code that imports/writes to an
`identity-access-service` entity or repository directly, or that has its own password-hashing dependency.

**(f) Upload validation (bulk import).** Server-side MIME/content-sniffing, max size, and
uploader-permission validation before any row is parsed, with no partial write on failure, per
`.claude/rules/security.md`'s "Upload Validation" section — applies here even though `03-student-management.md`
itself doesn't restate it explicitly.

## 16. Audit requirements

**This is an open decision, presented as such, not resolved.** Whether student profile CRUD or status
changes must produce an `audit-log-management` entry is not specified anywhere in reviewed material (spec
§9's own text, confirmed against `open-decisions.md` §5). Cross-checked against `.claude/rules/security.md`'s
canonical mandatory-audit list (price changes, payment approvals/rejections, device resets, access/expiry
extensions, reactivation approvals, content deletions, settlement changes, impersonation) — plain student
profile CRUD/status changes are **not** on that list as currently written. This module must not silently add
itself to that list, nor silently skip audit logging on the theory that "not on the list means not
required" — both are decisions requiring explicit sign-off, exactly the same posture MVP-005 took for staff
account creation/role changes.

**Professional recommendation, labeled as a recommendation only:** a status change (e.g. suspension) is
access-affecting and structurally similar to the already-mandatory device-reset/access-extension cases,
worth treating as audit-worthy pending an explicit decision. This remains for the product/security
decision-maker to ratify, not something implementation should proceed against as if already decided.

## 17. Payment impact

**None.** Confirmed against `STU-1`/`STU-2`/`STU-3`'s own "Payment impact" fields (all `None`) in
`docs/planning/product-backlog.md`, and independently confirmed by every parallel review — nothing in the
spec, backlog, role matrix, or ADRs ties this module to money in any form. STU-3's payment-history *tab*
reads from `payment-management`'s future `api` (once that domain exists) but does not itself perform any
payment/ledger mutation or business logic — no `payment-ledger-specialist` review was performed for this
reason, mirroring MVP-005's identical reasoning.

## 18. Tests

**Grounding:** unlike MVP-005 at its own planning time, the RBAC/auth/tenant-resolution substrate this
module needs is real and already merged (`DomainArea.STUDENTS` and `Role.STUDENT` already exist). Most of
what follows is buildable now, except the items explicitly flagged in the blocked-items table below.

### Backend unit tests (Mockito, no Spring context)

- **DTO validation**, one class per request DTO, mirroring `StaffCreateRequestValidationTest`'s pattern:
  self-registration request (blank/malformed name/email/password; no `roleCode` field should exist on this
  DTO at all — assert its absence, not merely that a value would be ignored); manual-create request (same
  base fields, plus guardian/school fields **blocked on §21 item 3**); profile-update request (**blocked on
  §21 item 3**); bulk-import row validation (required-field-missing, malformed email, duplicate-within-file
  email, over-length fields — buildable now for the validation half, independent of the still-open
  partial-failure *response shape*).
- **Role/field-restriction logic (service layer, Mockito)**, mirroring `StaffServiceTest`'s pattern:
  self-registration always calls `provisionTenantUser(..., "STUDENT", false)` regardless of request content
  (verify exact arguments, not just "some role"); manual/bulk creation always calls with `true`; every
  service method independently re-checks `DomainArea.STUDENTS` permission first and short-circuits before
  touching any collaborator on denial (`verifyNoInteractions`); the self-service "own profile" method takes
  no id parameter at all (assert at the method-signature level — if it does take one, that itself is a
  design finding, not something to test around); Teacher-roster query (once buildable) is proven at the unit
  level to invoke a course-scoped repository method, never `findAll()` followed by in-memory filtering.

### Backend Testcontainers integration tests (organized by story)

**STU-1 — Self-registration:** tenant-scoped registration persists both `tenant_user` and `student_profile`
rows under the resolving tenant only; `must_change_password = false` asserted via direct DB read (its own
test, not folded into row-persistence); registration on an unresolvable subdomain creates no row anywhere;
duplicate email within tenant → `409` via the real endpoint, not a raw constraint leak; **concurrent
same-email registration race test** (two-thread, proving exactly one row commits) mirroring MVP-005's
uniqueness-under-race rigor; same email under two different tenants both succeed independently; a request
body with injected `role`/`tenantId`/`mustChangePassword` fields is ignored end-to-end.

**STU-2 — Manual creation + bulk import:** manual creation persists `must_change_password = true`; one
explicit test method per role, not a parameterized loop (Student Support succeeds; Content Manager, Finance
Staff, Course Coordinator, Exam Manager, Attendance Operator each separately rejected 403; Read-only Auditor
rejected on mutation but proven to retain `200` on list/detail — both halves in separate assertions);
Teacher and Teacher Assistant and Student roles also rejected 403 on this endpoint. Bulk import: all-valid
file creates one row per row, each with `must_change_password = true`; a bulk-import run in tenant A's
context cannot collide with an existing tenant B row sharing the same email; a CSV row containing a
tenant/role-shaped column cannot override the resolved tenant/role. Partial-row-failure-specific tests are
**blocked**, see below.

**Profile V/C/E boundary:** one explicit test per role for edit (Student Support succeeds; every other
non-Owner role rejected, each its own test method) with a follow-up read confirming no mutation occurred on
rejection — same "assert unchanged, not just the status code" rigor as MVP-005's role-edit tests.

**History-view composition:** unit-level (Mockito) proof that `StudentHistoryService` calls each available
domain's narrow `api` method and shapes the combined DTO correctly, with `verifyNoInteractions` on anything
that isn't an `api`-shaped mock (the concrete enforcement of "no cross-domain join, ever"). Real
Testcontainers coverage against actual `enrollment-management`/`payment-management`/`attendance-management`/
`exam-management` `api` reads is **blocked** until each domain exists — do not substitute a hand-rolled fake
domain-`api` implementation and label the result "integration coverage," per the explicit precedent MVP-005
set for exactly this situation.

### Mandatory cross-tenant negative tests

One test (or test group) per endpoint listed in §14 — not a representative sample. Every history sub-view
gets its own independent cross-tenant test, not one test assumed to cover all sub-views.

### Playwright, once frontend screens exist

Self-registration form (validation, safe failure on unresolvable subdomain with no cross-tenant fallback
link); manual-create + bulk-import flow (accessible step-indicator progress via ARIA, not just visual;
file-picker `accept` attribute is not sufsufficient — server-side MIME validation must still reject a
renamed-extension non-CSV file); Student List's two distinct empty states; permission-denied states for
every `V`-only role, both halves (UI hides the control **and** a direct API call bypassing the UI still
fails server-side); Student Detail/My Profile history tabs switch independently without one tab's absence
blocking another; own-profile view has no id-based URL parameter that has any effect if manually edited.

### Explicitly blocked/deferred test items — dependency named, no invented shape

| Item | Blocked on |
|---|---|
| Bulk-import partial-row-failure response/UI tests | §21 item 2 |
| Exact guardian/school/grade/stream field validation tests | §21 item 3 |
| Audit-log-entry assertions for student CRUD/status changes | §21 item 9 (audit decision) |
| Password-strength-policy tests | §21 item 6 |
| Self-registration public-vs-invite-only endpoint-contract tests | §21 item 1 |
| History-view Testcontainers tests against real enrollment/payment/attendance/exam-management `api` reads | Those four domains not yet built |
| Teacher-roster tests (unit or integration) | `course-management` teacher-assignment model not yet built — §21 item 2 |
| Delete/status-change endpoint tests | §21 item 5 |
| Rate-limiting tests for the self-registration endpoint | §21 item 6 |

## 19. Documentation changes

- `docs/architecture/database-architecture.md` — new `student_profile` table entry, once §8's `status`-
  column deviation and the guardian/school field list (§21 item 3) are resolved.
- `docs/api/user-management.md` (new/extended) — produced via the `review-api-contract` skill from §10's
  draft before implementation begins on either side.
- `docs/requirements/open-decisions.md` — append every newly-surfaced item from §21 below that isn't
  already tracked at this level of detail: the `status`-column deviation from the backlog's literal STU-1
  text, the Teacher-roster architectural gap (course-management dependency), the password-strength-policy
  gap, the rate-limiting/abuse-prevention gap, the self-registration enumeration-risk-at-business-logic-layer
  finding, the role-collision-on-create/import gap, the suspended-tenant-interaction precondition gap, the
  FR-UM-3 device/communication-history phase-tag contradiction, and the same-tenant cross-student
  enumeration AC gap (distinct from the already-logged cross-tenant one).
- `docs/requirements/specifications/03-student-management.md` — its existing "Open decisions" list already
  covers several items below (self-registration public/invite-only, bulk-import partial-failure, field
  lists, audit logging); no edit needed there unless/until those get resolved.

## 20. Implementation order

Unlike MVP-005, this module's hard dependencies exist today, so a real, buildable slice exists. The order
below sequences by risk and dependency, not backlog story-number order — lower-risk, precedent-mirroring
work first, higher-novelty/higher-open-decision-count work later.

**Stage 0 — Decisions to seek sign-off on before starting (does not block starting the lowest-risk slice
below, but blocks finishing several stories):** self-registration public-vs-invite-only (§21 item 1);
bulk-import partial-failure shape (§21 item 2); exact guardian/school/grade/stream field list (§21 item 3);
`student_profile.status` column deviation confirmation (§21 item 4); delete/status-change semantics (§21
item 5); password-strength policy + rate-limiting approach for the new public endpoint (§21 item 6); audit
logging requirement (§21 item 9).

**Stage 1 — Schema + shared entity (blocks everything else):**
1. Flyway migration `V11__create_student_profile.sql` (or next free number at actual implementation time),
   contingent on the `status`-column deviation (§8) being explicitly confirmed.
2. `domain`/`repository`: `StudentProfile implements TenantOwned`, `StudentProfileRepository extends
   TenantAwareRepository<StudentProfile, UUID>`.

**Stage 2 — Manual creation (STU-2's admin-authenticated slice) — lowest risk, mirrors the proven
`StaffService`/`StaffController` pattern almost exactly:**
3. `StudentService` (manual create/list/detail/edit), `StudentController`, gated by `DomainArea.STUDENTS`.
4. Backend tests per §18 for this slice (unit + Testcontainers + cross-tenant + per-role deny-path).

**Stage 3 — Self-registration (STU-1) — higher novelty, sequenced after Stage 2 so the shared entity/schema
is already proven, and after Stage-0 decisions on public-vs-invite-only, password policy, and rate-limiting
are at least provisionally addressed:**
5. `StudentRegistrationService`, `StudentRegistrationController`, security-config `permitAll()` wiring
   (explicitly verified as *not* added to `TenantResolutionFilter`'s exclusion list).
6. Backend tests per §18 for this slice, including the enumeration-risk and rate-limiting checks in §15.

**Stage 4 — Bulk import (STU-2's CSV slice):**
7. `StudentBulkImportService`, upload-validation wiring, happy-path (all-valid-rows) tests now; partial-
   failure-specific tests deferred to whenever Stage 0's decision on that shape lands.

**Stage 5 — History composition (STU-3) — explicitly the capstone, built incrementally as each owning
domain lands, not all at once:**
8. `StudentHistoryService` skeleton with all six sections rendering "not yet available"; wire each real
   section in as `enrollment-management`/`payment-management`/`attendance-management`/`exam-management`
   ship their own `api` packages (order matches whichever domain lands first, not fixed here).
9. Teacher roster view — explicitly **not** started until `course-management`'s teacher-assignment data
   model exists (§21 item 2); tracked as a distinct follow-on module slice, not part of this stage's
   deliverable.

**Stage 6 — Frontend, as a separate follow-on phase per `CLAUDE.md`'s "do not implement backend and frontend
simultaneously unless explicitly approved":** screens per §11, new `components/ui/` primitives (data table
first, since both Student List and the deferred Course Roster depend on it), nav additions, Playwright tests
per §18.

**Stage 7 — Security + tenant-isolation review pass, documentation updates per §19, commit as separate
backend and frontend commits per `.claude/rules/git-workflow.md`.**

This plan does not authorize implementation of Stage 3 onward until Stage 0's relevant decisions have at
least been explicitly raised for sign-off — building Stage 2 alone does not require waiting on them.

## 21. Risks and unresolved decisions

Compiled from all six parallel reviews, consolidated and deduplicated. None of the items below are resolved
by this plan — each is surfaced exactly as the source documents (or the cross-review comparison performed
during this planning pass) leave it.

1. **Self-registration: public vs. invite-only is unresolved**, and is not cosmetic — it determines whether
   the endpoint is a truly anonymous public write or requires an invite-token precondition, changing the
   API contract shape materially. Sources: spec's own Open Decisions; `user-roles-and-permissions.md` §1/Open
   Q4; `open-decisions.md` §1.
2. **Two compounding gaps make the Teacher roster view unbuildable at this module's actual MVP-006 slice, not
   just under-specified:** (a) bulk-import partial-row-failure behavior (all-or-nothing vs. row-level partial
   success + error report) is an explicit open decision (spec, STU-2 AC #3, `open-decisions.md` §10); (b)
   separately, `course-management`'s teacher-course-assignment data model does not exist anywhere in this
   codebase yet, so there is no source-of-truth a roster query could filter against — building it now would
   necessarily fake the filter or ship an unfiltered-roster fallback, both of which violate the spec's own
   §7 requirement in spirit. Recommendation: defer explicitly (§20 Stage 5), same posture MVP-005 took for
   its own hard blockers, rather than build a stopgap.
3. **Exact field list for guardian/parent info and school/grade/stream is not itemized anywhere**, yet
   STU-1's own backlog entry proposes a concrete-looking schema sketch — this is a placeholder, not a
   ratified list, and the tension between the explicit "not itemized" note and the concrete-looking sketch
   should be resolved explicitly, not silently treated as final. Source: `open-decisions.md`, spec's own Open
   Decisions.
4. **The backlog's literal STU-1 database-impact line lists `status` as a `student_profile` column; this
   plan recommends against it** (reuse `tenant_user.status` instead, matching the `staff_profile`/STAFF-1
   precedent) but flags this as a reasoned deviation requiring explicit confirmation, not a silent override.
   See §8.1.
5. **Delete (`D`, Institute Owner only) semantics are entirely undefined, and in probable tension with root
   `CLAUDE.md`'s "never delete financial history" rule** once a student row is FK-linked to future
   enrollment/payment/ledger rows. Mirrors the already-logged, analogous gap for Finance & Expenses' `D`
   grant in `open-decisions.md` §5, but that entry does not name Students — a newly identified instance of
   the same pattern.
6. **No password-strength policy and no rate-limiting/abuse-prevention mechanism exists anywhere in this
   backend today**, and student self-registration is the first public, unauthenticated, credential-issuing
   endpoint materially larger in abuse-surface than the two existing public endpoints (`/auth/login`,
   `/tenant-registrations`). This is a cross-cutting infrastructure gap this module inherits and surfaces
   sharply, not something to solve unilaterally inside `user-management` — needs an explicit
   product/security decision on scope (build now vs. explicitly accept residual risk) before the
   self-registration endpoint ships publicly.
7. **`FR-UM-3` tags device/communication history as MVP, but the domains that would populate them
   (device-authentication: `FR-IAS-3`–`FR-IAS-7`; notification delivery logs: `FR-NM-4`) are both tagged
   Phase 2.** STU-3's own backlog dependency list doesn't even name a dependency for these two tabs despite
   them being named in the module's business purpose — a genuine phase-tag contradiction between `FR-UM-3`
   and the phase tags of the domains it depends on, not just a missing cross-reference. Should be resolved
   (descope explicitly to Phase 2, or clarify a reduced MVP version of each tab) before STU-3 is treated as a
   fully-scoped MVP capstone story.
8. **Teacher Assistant's access to the student roster/profile is entirely unaddressed** by the module spec's
   own actor list, and the role's whole permission boundary is independently unratified per
   `user-roles-and-permissions.md` §3 (`open-decisions.md` §3). Two stacked issues, neither resolved here.
9. **Whether student profile CRUD / status changes require an audit-log entry is unresolved**, confirmed
   against `.claude/rules/security.md`'s canonical mandatory-audit list (not present there). Recommendation
   only, not a decision: treat as audit-worthy given the access-affecting nature of a status change, pending
   explicit sign-off. Sources: spec §9, `open-decisions.md` §5.
10. **Platform Admin has no explicit access grant in the Students matrix row**, and per the cross-cutting
    rule must not get implicit tenant-scoped access without an audited impersonation flow — worth an
    explicit negative test in this module's own plan, since the spec's cross-tenant test framing is worded
    only around "Tenant Admin/staff of another tenant."
11. **Precondition gap: interaction with a suspended/trial/cancelled tenant is unaddressed.** `FR-TM-3`
    states tenant-status transitions "immediately affect login/access," but no document says whether
    self-registration, manual creation, or bulk import should be blocked when the tenant isn't `active`. Not
    previously logged in `open-decisions.md`.
12. **No verification-step / duplicate-registration-handling flow is specified for student self-registration**
    (email/identity verification before activation, re-registration handling) — a public, credential-issuing
    endpoint with no described verification step is a real gap, not an invented requirement to fill it.
13. **Role-collision on create/import is unaddressed** — no document says what happens if a manual-create or
    bulk-import row targets an email already associated with a different role (Teacher, Staff) in the same
    tenant.
14. **FR-UM-2's phrasing ("editable by... Tenant Admin/Staff") is looser than the actual permission matrix**,
    which grants create/edit only to Institute Owner and Student Support, not to "Staff" generically (the
    other six sub-roles are `V`-only). Not a hard contradiction, but worth resolving in wording so an
    implementer doesn't over-grant edit rights based on `FR-UM-2`'s text alone.
15. **Sequencing/feasibility note (not a business decision, but material context, unlike MVP-005's
    situation):** this module's hard blockers (`identity-access-service`, RBAC, `tenant-management`, and the
    directly analogous `staff` package precedent) already exist and are merged — the module genuinely is
    implementable now, unlike MVP-005 at its own planning time. The blockers that remain (§21 items 1–13
    above) are business-decision gaps and forward-domain dependencies (STU-3's four history-source domains,
    Teacher roster's course-assignment dependency), not foundational-infrastructure absence.

---

*This plan does not authorize implementation of Stage 3 onward (§20) until the relevant items in this
section have at least been explicitly raised for sign-off. Stage 1–2 (schema + manual-creation slice) may
proceed without waiting on them, since none of the open items above block that slice's own correctness.*
