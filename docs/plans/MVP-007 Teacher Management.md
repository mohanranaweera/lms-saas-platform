# MVP-007 — Teacher Management — Module Plan

**GitHub issue:** #7 — https://github.com/mohanranaweera/lms-saas-platform/issues/7
**Branch:** `feature/teacher-management` (current branch)
**Backlog source:** `docs/planning/product-backlog.md`, MODULE 7 (stories `TCH-1`, `TCH-2`, lines 405-443)
**Spec source:** `docs/requirements/specifications/04-teacher-management.md`
**Backend domain:** `user-management` (`com.lms.usermanagement`) — Teacher Management is Module 4/7 inside
`user-management`, sibling to the already-shipped `staff` sub-package (MVP-005) and the not-yet-built
`student` sub-package (Module 3), per `.claude/rules/architecture.md`'s per-domain package rule.

This plan was produced by delegating research to `product-requirements-analyst`, `solution-architect`,
`database-architect`, `security-reviewer`, `qa-test-engineer`, and `ui-ux-reviewer` framings against the
existing requirements/architecture/ADR corpus and, critically, the **actual current repository state**
(read directly, not assumed). `payment-ledger-specialist` was not engaged — commission/payout settings are
explicitly Phase 2 (issue text, spec §10) and the issue itself states "Commission/payout settings are
explicitly Phase 2 — no columns for this in MVP"; nothing in this MVP slice touches money.

**Grounding note — this module is not greenfield.** Unlike MVP-005's plan (written when only the Module 1
shared kernel existed), this repository now has a real, shipped foundation directly relevant to this module:
`identity-access-service` (auth, JWT, RBAC-2 permission enforcement), `tenant-management`, and
`user-management`'s `staff` sub-package (MVP-005, merged). This plan reuses that shipped foundation's exact
patterns rather than re-deriving them, and cites the concrete files/lines it is grounded in throughout.

Per root `CLAUDE.md`'s planning requirement and "Do not invent unresolved business decisions" instruction,
every genuinely open question below is flagged as such, not silently resolved — but several questions the
issue text calls "open" are in fact **already answered by already-shipped, already-migrated code** (the
`role` catalog table, the RBAC-2 permission matrix); those are resolved here with the concrete evidence, not
invented.

---

## 1. Business goal

Let a tenant onboard teachers through a controlled gate: an authenticated Tenant Admin (or an authorized
Course Coordinator) adds a teacher account, but that account gains no course-assignment or login capability
until a Tenant Admin explicitly approves it. Once approved, the teacher can log in via the shared auth path,
scoped to the Teacher portal (`app/(teacher)/`), and see a "My Courses" view that is strictly backend-filtered
to their own assigned courses — never a broader dataset filtered client-side. This is foundational MVP
capability (`FR-UM-5`): without an approval gate, any account created under the Teacher role would
immediately be able to log in and (once course-management exists) touch course/content/exam data, which is
unacceptable for a role a tenant did not yet vet.

## 2. Roles and permissions

**Acting actors on Teacher Management endpoints**, per `docs/requirements/user-roles-and-permissions.md` §2's
"Teachers" row (`V/C/E/D` / `—` / `V/C/E` / `V` / `—` / `—` / `—` / `V`) — and confirmed identically already
coded, cell-for-cell, in the shipped `PermissionCheckServiceImpl.buildMatrix()`
(`backend/src/main/java/com/lms/identityaccessservice/service/PermissionCheckServiceImpl.java:136,171,181,212`):

| Role | `DomainArea.TEACHERS` grant (as shipped today) |
|---|---|
| Tenant Admin | `VIEW, CREATE_EDIT, DELETE` |
| Course Coordinator | `VIEW, CREATE_EDIT` |
| Student Support | `VIEW` |
| Read-only Auditor | `VIEW` |
| Finance Staff, Content Manager, Exam Manager, Attendance Operator | *(no grant — 403)* |

**Resulting accounts:** Teacher (`tenant_user.role = 'TEACHER'`) — approved/rejected through this module.
**Teacher Assistant** (`role = 'TEACHER_ASSISTANT'`) exists as a catalog value (V7) but its permission
boundary is explicitly **PROVISIONAL, not ratified**
(`docs/requirements/user-roles-and-permissions.md` §3: "a reasonable default, not a confirmed decision; do
not build a hard permission gate against it without sign-off"). This module does **not** build Teacher
Assistant creation/approval — see §6.

**Open decision, already flagged by the issue itself and confirmed against the shipped matrix, not
resolved here:** `PermissionAction.APPROVE` exists as an enum value
(`backend/src/main/java/com/lms/identityaccessservice/api/PermissionAction.java`) and is already granted for
other domains (`PAYMENTS_SLIPS`, `EXAMS`, `ACCESS_EXPIRY`, `REVIEWS_MODERATION`) — but **no role holds
`APPROVE` on `DomainArea.TEACHERS` today**, not even Tenant Admin. This is the literal, already-shipped
version of the issue's "no explicit `A` column exists for Teachers" gap. Two ways to close it:

- **(a) Recommended, minimal-blast-radius:** do not edit the shipped, tested `PermissionCheckServiceImpl`
  matrix at all. Gate `POST /teachers` (create) on the existing `TEACHERS`/`CREATE_EDIT` grant (Tenant Admin
  *and* Course Coordinator, matching the matrix as shipped). Gate `POST /teachers/{id}/approve` and
  `.../reject` on `TEACHERS`/`CREATE_EDIT` **plus an explicit, additional service-layer check that the acting
  principal's role is literally `TENANT_ADMIN`** (read via
  `AuthenticatedPrincipalHolder.get().role()`, an already-`api`-exposed string — no cross-domain entity
  import needed), rejecting Course Coordinator from approval specifically. This mirrors the
  already-shipped "service-layer defense-in-depth" pattern added to `StaffService` in commit `d265597`
  (independent re-check beyond `@PreAuthorize`), extended here to a role-literal check rather than a
  domain-action check, because the matrix has no finer-grained primitive for "same domain, higher-trust
  action, different actor" than `APPROVE`/`CREATE_EDIT` already provide for other domains.
- **(b) Add a `TEACHERS`/`APPROVE` grant to the matrix** (Tenant-Admin-only), which is a direct change to
  already-shipped, already-tested authorization code with its own static Read-only-Auditor-never-gets-a-write
  invariant check (`PermissionCheckServiceImpl`'s static initializer) — a larger, more visible change.

**This plan recommends (a) and designs against it below, but treats it as a recommendation requiring
explicit sign-off before implementation, not a decided fact** — consistent with the issue's own framing
("Whether Course Coordinator's V/C/E includes approval authority is unspecified"). Do not implement either
path without confirming which is intended.

**Cross-cutting rule** (already enforced platform-wide, restated for this module): authorization is
server-side on every endpoint, independent of client display; tenant identity is always resolved from
`TenantContext`/`AuthenticatedPrincipal`, never a client-supplied value.

## 3. Preconditions

- Tenant is active (not suspended/cancelled) — enforced upstream by the already-shipped
  `TenantResolutionFilter` before any request reaches this module's controllers; no separate check needed
  here.
- Acting user is authenticated with a resolved `AuthenticatedPrincipal` (Tenant Admin or Course Coordinator
  for creation; Tenant Admin for approval per §2).
- For approval/rejection: a `teacher_profile` row already exists in `PENDING` status for the addressed id,
  within the acting admin's own resolved tenant.

## 4. User flows

### Normal flow

1. **Creation.** An authenticated Tenant Admin or Course Coordinator opens Tenant Admin's `Teachers >
   Teacher List` and selects "Add teacher," entering name/email/password
   (mirroring `StaffCreateRequest`'s shape exactly — see §12).
2. **Registration mechanism — resolved by existing shipped data, not invented.** The `role` catalog row for
   `TEACHER` (`V7__create_role_catalog.sql:52`) has `self_registers = false` — identical to every staff
   sub-role and unlike `STUDENT` (`self_registers = true`). There is no public self-service teacher
   registration page anywhere in this codebase (`frontend/src/app/(public)/register-institute` is tenant
   onboarding; `app/(auth)/register` is the student flow). This settles the issue's "self-register-then-approve
   vs. invited-only" open decision in favor of **admin/coordinator-created**, mirroring Staff Management's
   already-shipped flow (`StaffService.createStaff`) — not a new self-registration/invite subsystem, which
   exists nowhere else in this codebase and would be new, unreviewed scope.
3. Backend creates the `tenant_user` credential row (role `TEACHER`, `mustChangePassword = true`, no
   self-registration path) via `identity-access-service`'s `UserProvisioningApi`, in the same transaction
   creates this module's `teacher_profile` row with `approval_status = PENDING`, and **immediately transitions
   the freshly-provisioned `tenant_user` row to `status = 'suspended'`** (see §7's design note — this is the
   concrete mechanism that blocks login/course-assignment while pending, reusing the exact pattern V10's own
   migration comment reasons through for staff status, extended here to the login-blocking case staff never
   needed).
4. Teacher's account exists, `PENDING`, cannot log in (`tenant_user.status = 'suspended'` → the existing
   `USER_SUSPENDED` login-rejection path in `AuthenticationService` applies unchanged — zero
   `identity-access-service` login-path code needs to change for this).
5. **Approval.** A Tenant Admin (see §2's open decision) reviews the Teacher List's pending queue and clicks
   Approve (or Reject) on a specific teacher — instance-specific `aria-label`s per the issue's UI-state notes
   (e.g. "Approve teacher Jane Doe").
6. On **approve**: `teacher_profile.approval_status → APPROVED`, `approved_by`/`approved_at` set; the
   `tenant_user` row transitions `status → 'active'`. Teacher can now log in.
7. On **reject**: `teacher_profile.approval_status → REJECTED`, `approved_by`/`approved_at` set (recording who
   decided and when, for both outcomes — the spec's literal column names cover both transitions, see §7).
   `tenant_user.status` stays `'suspended'` permanently — a rejected teacher never gains login capability.
   Both transitions are **one-directional and terminal** — no `APPROVED → PENDING` or `REJECTED → APPROVED`
   reopening, the same one-directional-state-machine shape already endorsed in this codebase for manual
   payment-slip review (`.claude/rules/payments.md` §2) — cited here as a proven pattern to mirror, not
   because teacher approval is a payment concern.
8. Once approved, a Tenant Admin or Course Coordinator (TCH-1's own dependency chain, not this module) assigns
   courses to the teacher — that assignment mechanism is Module 8's `CRS-3` (`course_teacher_assignment`
   table), which does not exist in this codebase yet (confirmed: no `com.lms.coursemanagement` package
   exists at all). This module does not build course assignment.
9. Teacher's `My Courses` view is backend-filtered to only their own assigned courses (TCH-2) — **blocked on
   step 8's table existing**; designed in §9/§10 below but not implementable until `course-management` ships
   `course_teacher_assignment` and exposes a narrow `api` read for it. This is a confirmed forward dependency
   (`product-backlog.md:435,498`: "sequence CRS-3 before TCH-2... regardless of story-ID order"), not new
   information — restated here because it materially changes §20 (Implementation order).

### Alternative / edge-case flows

- **Unapproved-teacher login attempt** — rejected with the existing `403 USER_SUSPENDED` response
  (`docs/api/identity-access-service.md`'s already-documented error code), because the account's
  `tenant_user.status` is `'suspended'` while pending. This resolves the issue's "cannot log in vs. sees no
  assigned courses" open question in favor of **cannot log in at all** — a concrete design choice, not a
  silent invention, since it reuses the exact mechanism `staff_profile`'s migration (V10) already established
  for reusing `tenant_user.status` instead of inventing a new state column. Flagged for explicit confirmation
  before implementation, since the issue itself calls this unresolved.
- **Rejected teacher re-application** — no document specifies whether a rejected teacher can be
  re-created/re-submitted. Given `UNIQUE (tenant_id, user_id)` on `teacher_profile` and
  `UNIQUE (tenant_id, email)` on `tenant_user`, a second creation attempt with the same email fails `409` today
  by construction. Re-approval-after-rejection is out of scope — not built, not silently allowed either.
- **Course Coordinator attempts approve/reject** — rejected per §2's recommended defense-in-depth role check
  (pending sign-off on which of §2(a)/(b) is intended).
- **Cross-tenant** — a Teacher (or Tenant Admin) of tenant A reaching a teacher id belonging to tenant B →
  `403`/`404`, uniform, via the same `TenantAwareRepository` mechanism `staff_profile` already relies on.
- **Empty states** — "no teachers yet" (Add Teacher CTA) distinct from "no teachers match filter" distinct
  from "no pending approvals" (if the list view supports an approval-queue filter — see §11).

## 5. Acceptance criteria

Reconciled from the issue, spec §8, and backlog `TCH-1`/`TCH-2` acceptance criteria.

1. Given a Tenant Admin or Course Coordinator creates a teacher account, the account is created tenant-scoped
   to the acting actor's own tenant (never client-supplied), with `tenant_user.role = 'TEACHER'` and
   `teacher_profile.approval_status = PENDING`.
2. Given a `PENDING` or `REJECTED` teacher account, an attempt to log in is rejected `403 USER_SUSPENDED` —
   the account gains no course-assignment or login capability before approval.
3. Given a Tenant Admin approves a `PENDING` teacher, the account transitions to `APPROVED`,
   `tenant_user.status` becomes `active`, and the teacher can now log in.
4. Given a Tenant Admin rejects a `PENDING` teacher, the account transitions to `REJECTED` and remains unable
   to log in permanently; the transition is one-directional (no `REJECTED → APPROVED`, no `APPROVED →
   PENDING`).
5. Given an actor without `TEACHERS`/`CREATE_EDIT` (or, for approve/reject, without the Tenant-Admin-only
   defense-in-depth check per §2) attempts create/approve/reject, the request is rejected `403` server-side,
   independent of client UI state.
6. Given a Tenant Admin of tenant A addresses a teacher id belonging to tenant B (list, detail, approve,
   reject), the request is rejected `403`/`404`, never `200` with empty/filtered data.
7. Given no teacher accounts exist yet for a tenant, the Teacher List renders "no teachers yet" with an Add
   Teacher CTA, distinct from "no teachers match your filter" and (if built, see §11) "no pending approvals."
8. Approve/Reject icon controls carry instance-specific `aria-label`s (e.g. "Approve teacher Jane Doe," not a
   generic "Approve").
9. **TCH-2, blocked pending `course-management`'s `course_teacher_assignment` table:** given an approved
   Teacher requests `My Courses`, results are limited server-side to their own assignments — this criterion
   cannot be met by this module alone and is not testable/buildable until Module 8/`CRS-3` ships (see §20).
10. Cross-tenant negative test on every new endpoint (create, list, detail, approve, reject) — mandatory, not
    optional, per `.claude/rules/tenancy.md`.
11. Intra-tenant test (once TCH-2 is buildable): a Teacher must be proven unable to view/list courses/rosters
    outside their own assigned-course set, even within their own tenant.

## 6. Out-of-scope items

- **Teacher Assistant creation/approval.** Role value exists in the catalog, but its permission boundary is
  PROVISIONAL/unratified (`user-roles-and-permissions.md` §3) — building a hard approval gate against an
  unconfirmed boundary would itself be the kind of premature commitment root `CLAUDE.md` forbids. If a
  Teacher Assistant needs onboarding before that boundary is ratified, that is a new decision, not this
  module's to make.
- **`My Courses` (TCH-2) implementation.** Fully designed (§9/§10/§11) but **not implementable** until
  `course-management` (Module 8) ships `course_teacher_assignment` and a narrow `api` read method — confirmed
  forward dependency, not a scoping choice this plan is making.
- **Course assignment/reassignment itself** — `CRS-3` (Module 8), a different module and a different domain
  package (`course-management`), not `user-management`.
- **Commission/payout settings (`FR-UM-6`)** — explicitly Phase 2 per the issue text; no columns added for
  this in `teacher_profile`.
- **Availability/payout profile, performance analytics, public teacher profile (`FR-UM-7`)** — Phase 2/3 per
  spec §10.
- **Teacher profile edit (name change) and hard delete/deactivate beyond approve/reject.** The matrix grants
  Tenant Admin `DELETE` on `TEACHERS`, but no document (issue, spec, or backlog) defines what "delete" means
  for a teacher (hard delete vs. a status transition, and its interaction with any already-assigned courses)
  — the same category of gap Staff Management's plan flagged for its own undefined status/removal state
  machine. Not building an invented state machine here; flagged, not resolved.
- **Approval audit logging** — see §16, explicitly open per the issue itself.
- **Public teacher profile / storefront composition** — Phase 2/3, ownership unratified per spec §7.
- **`audit-log-management`, `course-management` modules themselves** — this module calls into
  `course-management`'s future `api` (once it exists) for TCH-2; it does not build that module.

## 7. Domain model

Mirrors Staff Management's already-shipped split exactly (`.claude/rules/architecture.md`'s "one table, one
owning domain"; `StaffProfile`/`staff_profile` as the direct structural precedent):

- **Credential aspect** (email, password hash, `role = 'TEACHER'`, `status`) — owned by
  `identity-access-service`, the existing `tenant_user` row (`V3`). No new columns added to `tenant_user`.
- **Operational/approval aspect** — owned by `user-management`, a new `TeacherProfile` entity
  (`com.lms.usermanagement.teacher.domain`), referencing the `tenant_user` row **by id only** (opaque foreign
  key value, never a JPA `@ManyToOne` across the module boundary) — identical convention to
  `StaffProfile.userId`.
- **Login-gate mechanism** — deliberately **reuses** `tenant_user.status` (`active`/`suspended`) rather than
  inventing a parallel "can this account log in" flag, extending the exact reasoning `V10`'s migration
  comment already applied to staff status ("resolves the... state machine question by reusing what
  identity-access-service already owns"). `teacher_profile.approval_status` is the domain-meaningful state
  (why the account is or isn't active); `tenant_user.status` is the login-gate mechanism (whether it can log
  in right now) — two different concerns on two different tables, kept in sync by `TeacherService` inside one
  transaction per transition, not duplicated as two independently-maintained booleans.
- **`approved_by`/`approved_at`** are populated on **both** `APPROVED` and `REJECTED` transitions (recording
  who decided and when) — the spec's literal column names ("approved_by, approved_at") are read as "decision
  actor/timestamp," not "only set on approval," since a rejection is equally a recorded decision and the spec
  provides no separate `rejected_by`/`rejected_at` pair.
- No `course_teacher_assignment` ownership here — that table (when it exists) belongs to `course-management`
  (per the issue's own text: "'My Courses' service calls course-management's api... never a join against
  course-management's tables").

**`TeacherProfile` (the one entity this module owns):**
- Belongs to exactly one `Tenant`.
- References exactly one `tenant_user` row by id (`userId`, opaque `UUID`).
- Owns `name`, `approvalStatus` (`PENDING`/`APPROVED`/`REJECTED`), `approvedBy` (nullable `UUID`, the
  deciding Tenant Admin's `tenant_user.id`), `approvedAt` (nullable `Instant`).
- No owned child entities. Course assignments (once they exist) are a `course-management`-owned table keyed
  by teacher id, read via that domain's `api`, never an FK/JPA relationship from this entity.

## 8. Database design

### 8.1 `teacher_profile` — new table, next available migration

Only `V1`–`V10` exist today (`V10__create_staff_profile.sql` is the most recent). This module's migration is
**not** forward-blocked the way `staff_profile`'s was in the MVP-005 plan — `tenant_user` (V3) and the `role`
catalog (V7/V8/V9) already exist and are already applied. The next available version is **`V11`**; the actual
file (`V11__create_teacher_profile.sql`) is authored during `implement-backend`/`database-migration`, not by
this planning step, per root `CLAUDE.md`'s "do not implement" instruction for planning — the shape below is
the design to carry into that step.

```sql
CREATE TABLE teacher_profile (
    id               UUID PRIMARY KEY,
    tenant_id        UUID NOT NULL REFERENCES tenant (id),
    user_id          UUID NOT NULL,
    name             VARCHAR(255) NOT NULL,
    approval_status  VARCHAR NOT NULL DEFAULT 'PENDING'
                     CHECK (approval_status IN ('PENDING', 'APPROVED', 'REJECTED')),
    approved_by      UUID NULL,
    approved_at      TIMESTAMPTZ NULL,
    created_at       TIMESTAMPTZ NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL,
    created_by       UUID,
    updated_by       UUID,

    CONSTRAINT uq_teacher_profile_tenant_user UNIQUE (tenant_id, user_id),
    CONSTRAINT fk_teacher_profile_tenant_user FOREIGN KEY (tenant_id, user_id)
        REFERENCES tenant_user (tenant_id, id),
    CONSTRAINT fk_teacher_profile_approved_by FOREIGN KEY (tenant_id, approved_by)
        REFERENCES tenant_user (tenant_id, id)
);

CREATE INDEX idx_teacher_profile_tenant_id ON teacher_profile (tenant_id, id);
CREATE INDEX idx_teacher_profile_tenant_status ON teacher_profile (tenant_id, approval_status);
```

Notes, mirroring `V10`'s own documented reasoning:
- **`email` and `status`(login) are intentionally absent** — already `tenant_user`'s columns (email) or
  covered by `tenant_user.status` (login gate, §7) — not duplicated here.
- **`approved_by`** uses the same composite-FK-against-`(tenant_id, id)` shape as the `user_id` FK, per
  `database-architecture.md`'s same-tenant cross-referencing rule — a Tenant Admin from tenant B can never be
  recorded as the approver of a tenant A teacher, enforced at the schema level, not just by service-layer
  discipline.
- **`id` has no DB-side `DEFAULT`** — application-generated `UUIDv7` (`UuidV7Generator`), per `V1`'s baseline
  convention, matching `StaffProfile`.
- **`created_at`/`updated_at`/`created_by`/`updated_by`** via the existing `Auditable` base class — identical
  to `StaffProfile`.
- Composite indexes lead with `tenant_id` throughout, per `.claude/rules/backend.md`. The second index
  `(tenant_id, approval_status)` is the literal shape the issue's own DB requirements text specifies, for the
  approval-queue ("pending teachers") list query.
- **No `staff_profile`-style reuse of a single table for both staff and teachers** — Teacher and Staff are
  different domain concepts with different lifecycle fields (approval workflow vs. none); a shared table
  would conflate two aggregates, which `database-architecture.md` §5 and `.claude/rules/architecture.md`
  both treat as a structural violation, not a shortcut.

### 8.2 No change to `tenant_user`, `role` catalog, or any Module 1–5 migration

This module does not touch `V1`–`V10`. `tenant_user.role` already accepts `'TEACHER'` (FK to `role.code`,
`V8`); the `role` catalog already has the `TEACHER` row with `self_registers = false` (`V7`). No new Flyway
migration is needed for either.

### 8.3 `course_teacher_assignment` (Module 8/CRS-3) — not this module's table

Named here only because TCH-2 depends on it. This module's migration must not create it, alter it, or assume
its exact column shape — that is `course-management`'s design to make when Module 8 is planned. This plan
only records the dependency, per `database-architecture.md`'s "migration history is append-only and
change-controlled" guidance already applied identically in MVP-005's plan (§8.4) for its own forward
dependency on `tenant_user`.

## 9. Backend design

Package: `com.lms.usermanagement.teacher`, sibling to the shipped `com.lms.usermanagement.staff` package,
same shape:

```
com.lms.usermanagement
|-- api                # unchanged — no other domain currently reads teacher data
|-- staff               # shipped (MVP-005), untouched by this module
|-- teacher              # new
|   |-- web              # TeacherController
|   |-- service          # TeacherService, TeacherAccount, InvalidApprovalStateException
|   |-- domain           # TeacherProfile (JPA entity, implements TenantOwned), ApprovalStatus enum
|   `-- repository       # TeacherProfileRepository extends TenantAwareRepository<TeacherProfile, UUID>
`-- config
```

**Cross-module call shape**, directly mirroring `StaffService.createStaff` (`StaffService.java:83-106`):

- `TeacherService.createTeacher(name, email, rawPassword)` is one `@Transactional` method that (a) checks
  `permissionCheckService.requirePermission(DomainArea.TEACHERS, PermissionAction.CREATE_EDIT)` first
  (defense-in-depth, same pattern as commit `d265597`), (b) pre-flight-checks
  `userProvisioningApi.existsByEmail(email)` (friendly `409`, not the race-safe guard — the real guard is
  `tenant_user`'s `UNIQUE (tenant_id, email)`), (c) calls
  `userProvisioningApi.provisionTenantUser(email, rawPassword, "TEACHER", true)`, (d) **immediately suspends**
  the freshly-provisioned row (see below — a new `UserProvisioningApi` method), (e) persists `TeacherProfile`
  with `approvalStatus = PENDING`. Role is never a client-supplied field on this module's create request
  (unlike Staff's 7-way choice) — always literally `"TEACHER"`.
- `TeacherService.approveTeacher(id)` / `.rejectTeacher(id)`: re-check `TEACHERS`/`CREATE_EDIT`, then the
  additional Tenant-Admin-only role check (§2), then load the `teacher_profile` row (tenant-scoped via
  `TenantAwareRepository` — a tenant B id is structurally invisible, surfaces as `404`), reject with a new
  `InvalidApprovalStateException` (→ `409`) if `approval_status != PENDING` (enforces the one-directional
  transition at the service layer — recommend a DB `CHECK`/trigger only if a future review finds the
  service-layer guard insufficient, consistent with `.claude/rules/backend.md`'s "prefer schema-enforced
  invariants" guidance being a *preference*, not mandatory for every state machine), then update
  `approval_status`/`approved_by`/`approved_at` and call the corresponding `UserProvisioningApi` status
  method.
- `TeacherService.listTeachers(approvalStatusFilter)` / `.getTeacher(id)`: `VIEW` check, then compose
  `TeacherProfile` rows with `TenantUserSummary` batch reads (`userProvisioningApi.findTenantUserSummaries`),
  identical composition pattern to `StaffService.listStaff`/`getStaff` — no N+1 across the module boundary.

**Required extension to `identity-access-service`'s `UserProvisioningApi`** (currently the interface
explicitly documents itself as "deliberately minimal... extend this interface... when a real, concrete need
... arises" — `UserProvisioningApi.java:16-18` — this module is exactly that real need):

```java
/** Transitions a tenant_user row's login-gate status. Tenant-scoped implicitly via the same
 *  resolved TenantContext every other method here uses. */
void setTenantUserStatus(UUID userId, String status); // "active" | "suspended"
```

This is a **backend-impact item on `identity-access-service`**, a foundational, security-sensitive module —
flag for its own security review pass during implementation (standard workflow step 6), even though it's a
narrow, additive change consistent with the interface's own documented extension policy, not a redesign of
auth/session mechanics.

**TCH-2 design (not implementable yet, per §6):** once `course-management` exists, `TeacherService` (or a
narrow read-only companion) would call a `course-management`-owned `api` interface — something shaped like
`CourseAssignmentLookupApi.findCoursesAssignedToTeacher(UUID teacherId)` — never a join against
`course-management`'s own tables, per the issue's explicit instruction. This module does not define that
interface (it isn't this module's to own), only records the expected call shape for when Module 8 lands.

## 10. API contract

Draft only — must be finalized via the `review-api-contract` skill into `docs/api/user-management.md` (does
not exist yet — same "process gap" `docs/api/identity-access-service.md` already flags for Staff Management,
which also shipped without a contract file; this module should not repeat that gap) before implementation
starts on either side. Every response uses `com.lms.common.api.ApiResponse<T>`; validation failures are `400
VALIDATION_ERROR` (confirmed against the shipped `GlobalExceptionHandler`, not the `422` assumption MVP-005's
plan had to flag as unconfirmed).

| Method + path | Auth | Notes |
|---|---|---|
| `POST /api/v1/teachers` | `TEACHERS`/`CREATE_EDIT` (Tenant Admin, Course Coordinator) | Body: `name`, `email`, `password` — no `roleCode` field (always `TEACHER`), no `tenantId`, no `approvalStatus`. `201` with the created teacher (status `PENDING`), `409` on duplicate-email-in-tenant, `400` on validation failure. |
| `GET /api/v1/teachers` | `TEACHERS`/`VIEW` (Tenant Admin, Course Coordinator, Student Support, Read-only Auditor) | Optional `?approvalStatus=PENDING\|APPROVED\|REJECTED` filter for the approval queue. Distinguishes zero-data vs. filtered-empty per response metadata. |
| `GET /api/v1/teachers/{id}` | `TEACHERS`/`VIEW` (same roles) | `403`/`404` uniform for a cross-tenant or nonexistent id. |
| `POST /api/v1/teachers/{id}/approve` | `TEACHERS`/`CREATE_EDIT` + Tenant-Admin-only role check (§2, pending sign-off) | `200` with the updated teacher on success; `409` if not currently `PENDING`; `403`/`404` for cross-tenant. |
| `POST /api/v1/teachers/{id}/reject` | Same as approve | Same shape as approve. |
| `GET /api/v1/teachers/me/courses` (TCH-2) | `TEACHER` role, own-assignment-scoped | **Not implementable until `course-management`/`CRS-3` ships** — contract recorded here for forward planning only; do not build a stub/mock response. |

No endpoint accepts a client-supplied `tenant_id`, `role`, or `approvalStatus`-on-create field. `PATCH`
(profile edit) and `DELETE` are **not** part of this pass — see §6's flagged gap on the undefined delete/edit
semantics.

## 11. Frontend screens

Portal: Tenant Admin (`app/(tenant-admin)/`) for management; Teacher (`app/(teacher)/`) for TCH-2, blocked
per §6. Current shadcn/ui inventory (`frontend/src/components/ui/`): `Button`, `Card`, `Input`, `Label`,
`Sheet`, `Skeleton`, `Alert`, `AlertDialog` — no `Table`, `Badge`, or `RadioGroup` yet (same gap MVP-005's
plan already flagged; still unaddressed since no Tenant Admin CRUD frontend has shipped yet — `dashboard` is
the only page under `app/(tenant-admin)/tenant-admin/` today).

| Screen | Route | Key components | Notes |
|---|---|---|---|
| **Teacher List** | `/tenant-admin/teachers` | Shared responsive data-table (new primitive, shared with any future Staff/Student list per `.claude/rules/frontend.md`) + card-fallback below `md`, filter (approval status/search), `Badge` for approval status (icon+text, never color alone), "Add teacher" CTA | No tenant column/selector (Tenant Admin views are single-tenant, per `ui-ux.md` §1). Approve/Reject icon-only buttons need instance-specific `aria-label`s (issue's explicit requirement). Distinct empty states: zero-data, filtered-empty, and (if the queue filter is built) "no pending approvals." |
| **Teacher Detail** | `/tenant-admin/teachers/[teacherId]` | Read-only profile summary, Approve/Reject action buttons (confirm via existing `AlertDialog`, not a new Dialog primitive), approval-status `Badge` | Approve/Reject only rendered for `PENDING` teachers; already-decided teachers show status read-only, no reopen affordance (matches the one-directional state machine, §4). Cross-tenant/not-found render uniform generic copy. |
| **Add Teacher** | `/tenant-admin/teachers/new` | React Hook Form + Zod (name/email/password) | Dedicated route, not a modal (multi-field form; `ui-ux.md` §5 flags cramped modals for meaningful forms). |
| **My Courses** (TCH-2) | `/teacher/courses` | Course Card grid, Empty State "no assigned courses yet" with contact-admin guidance | **Blocked** — cannot be built against a real endpoint until TCH-2's backend exists (§6/§9). Do not build against a mocked/stubbed response. |

Nav: add `{ label: "Teachers", href: "/tenant-admin/teachers" }` to
`frontend/src/components/layout/nav/tenant-admin-nav.tsx`. The `teacher-nav.tsx` already exists (teacher
dashboard ships today) — add a "My Courses" entry there once TCH-2 is unblocked, not before.

## 12. Validation rules

- **Name:** required, matching `StaffCreateRequest`'s `@NotBlank @Size(max = 255)`.
- **Email:** required, `@Email @Size(max = 255)` — tenant-scoped uniqueness is backend-only, client Zod check
  is UX convenience only.
- **Password:** required, `@Size(min = 8, max = 255)` — identical bound to Staff's, no separate rationale
  found to diverge.
- **No `roleCode` field on the create request at all** (unlike Staff's 7-way enum) — role is always literally
  `"TEACHER"`, set server-side, never client-supplied.
- **No `approvalStatus` field on any client request** — always server-derived (`PENDING` on create;
  `APPROVED`/`REJECTED` only via the dedicated approve/reject endpoints, never a generic profile-edit
  payload).
- **Approve/Reject requests carry no body** — confirm-only actions addressed by path id, matching the
  password-reset-is-confirm-only pattern MVP-005's plan already established for a structurally similar
  "state-changing, no new data from the client" action.

## 13. Error cases

| Case | Expected behavior | Status |
|---|---|---|
| Non-authorized role reaches a teacher endpoint | `PermissionDeniedState`, driven only by a real `403` | `403 FORBIDDEN` |
| Course Coordinator attempts approve/reject | Same 403 pattern, pending §2's sign-off on which enforcement path is intended | `403 FORBIDDEN` |
| Cross-tenant access (guessed/edited teacher id) | Uniform generic "not found" copy regardless of the underlying 403-vs-404 reason | `403`/`404` |
| Duplicate email within tenant | `409` with `fieldErrors: [{field: "email", ...}]`, same `ApiError`/`FieldError` contract as Staff | `409 CONFLICT` |
| Approve/reject a non-`PENDING` teacher | `409`, one-directional transition rejected, current state unchanged | `409 CONFLICT` |
| Unapproved/rejected teacher attempts login | Existing `403 USER_SUSPENDED` path, unchanged | `403 USER_SUSPENDED` |
| `My Courses` request (TCH-2) | Not buildable — do not implement against a stub | **Blocked, not an error case yet** |

## 14. Tenant-isolation rules

- `teacher_profile` carries `NOT NULL tenant_id`, `UNIQUE (tenant_id, user_id)`, composite FKs to
  `tenant_user(tenant_id, id)` for both `user_id` and `approved_by` — never a bare/global FK.
- Every repository method goes through `TeacherProfileRepository extends TenantAwareRepository<...>` — no
  method accepts a caller-supplied `tenant_id` parameter; a tenant B id is structurally invisible to a tenant
  A actor's queries, surfacing as `404`, never a filtered `200`.
- Cross-tenant negative tests required per endpoint (create is single-tenant by construction — no cross-tenant
  create path exists; list/detail/approve/reject each need their own explicit test, per the same enumeration
  shape MVP-005's plan used for Staff): list (no filter/param leaks tenant B rows), detail (tenant B id →
  403/404), approve (tenant B id → 403/404, verified via a follow-up read that the row is unchanged), reject
  (same).
- **Intra-tenant scoping (TCH-2, once buildable):** distinct from tenant isolation — a Teacher within their
  own tenant must never see another Teacher's assigned courses. This needs its own test dimension (issue's
  own explicit callout), not just a cross-tenant test.
- No legitimate cross-tenant read exists anywhere in this module — same confirmation MVP-005's plan already
  made for Staff, on the same grounds (single-tenant operational domain, no aggregation/reporting requirement
  named anywhere in reviewed material).

## 15. Security rules

- **Credential-creation boundary (unchanged from Staff):** `user-management` never constructs, hashes, or
  persists a password/credential value itself — delegates entirely to `UserProvisioningApi`. Reject any code
  that imports/writes an `identity-access-service` entity or repository directly.
- **Login-gate correctness is the primary security property of this module.** A `PENDING` or `REJECTED`
  teacher must be provably unable to log in — covered by reusing the already-tested `USER_SUSPENDED` path
  rather than building a new, unreviewed gate. Any test suite for this module must assert the actual
  `tenant_user.status` value transitions correctly on create/approve/reject, not just that the
  `teacher_profile.approval_status` column is correct in isolation — the two must be verified in sync.
- **Approval-authority enforcement (§2):** implement as a positive allow-list (only the intended actor(s)
  reach the approve/reject handler), not a deny-list — the same reasoning MVP-005's plan applied to
  Read-only-Auditor denial, extended here to whichever actor(s) §2 confirms.
- **Self-escalation is not a concern this module introduces** — Teacher accounts have no elevated
  create/edit/delete authority over other accounts; there is no analogous "approver approves themselves"
  scenario since a Tenant Admin cannot be the subject of this module's approval flow (Tenant Admin accounts
  are provisioned elsewhere, per Staff Management's `ASSIGNABLE_STAFF_ROLES` exclusion list, and Teacher
  accounts have no `TEACHERS`/`CREATE_EDIT` grant themselves).
- **`UserProvisioningApi`'s new `setTenantUserStatus` method (§9)** must itself remain tenant-scoped
  (resolves `tenant_id` from the trusted `TenantContext`, never a parameter) and must not become a general
  "any caller can suspend any account" backdoor — its only two intended call sites in this pass are
  `TeacherService`'s create (suspend) and approve (activate) paths. A future caller reusing this method for
  an unrelated purpose (e.g. a generic account-suspension feature) should go through its own review, not
  ride on this module's justification.

## 16. Audit requirements

**Open decision, presented as such, not resolved** — identical framing to MVP-005's plan for Staff creation/
role changes. The issue's own text: "Approval-audit-logging is an open decision, flagged given approval
grants portal + course/content/exam-management capability." Checked against `.claude/rules/security.md`'s
canonical mandatory-audit list (price changes, payment approvals/rejections, device resets, access/expiry
extensions, reactivation approvals, content deletions, settlement changes, impersonation) — **teacher
approval/rejection is not on that list as currently written.**

**Recommendation only, not a requirement:** given the blast-radius argument the issue itself makes
(approval grants a full portal plus eventual course/content/exam capability, once those modules exist), treat
teacher approval/rejection as audit-worthy, in the same shape MVP-005's plan recommended for staff role
changes: publish a domain event (`TeacherApprovedEvent`/`TeacherRejectedEvent`) inside the same transaction as
the write, for `audit-log-management` (Module 19, not yet built) to consume once it exists — no direct write
into an audit table this module doesn't own. This remains for explicit product/security sign-off, not
something to build unreviewed.

## 17. Payment impact

**None.** Confirmed against the issue's own explicit statement ("Commission/payout settings are explicitly
Phase 2 — no columns for this in MVP") and against every section of this plan — no table, endpoint, or flow
here touches an order, payment, ledger entry, or enrollment activation. `payment-ledger-specialist` review
was not engaged for this reason, matching MVP-005's precedent.

## 18. Tests

Grounded in the **shipped** test infrastructure this module can actually reuse today
(`AuthIntegrationTestSupport`, `HttpResult`, the MockMvc-through-real-filter-chain technique demonstrated in
`StaffManagementIntegrationTest`) — unlike MVP-005's plan, which had to defer nearly all of §18 because
nothing was buildable yet. This module's `TCH-1` slice *is* buildable now; `TCH-2` is not (see §6).

### Unit tests
- `TeacherService`: role restricted to literal `"TEACHER"` (no client input path exists to even test a wrong
  value, unlike Staff's enum check); approval state-machine transitions (`PENDING → APPROVED`,
  `PENDING → REJECTED` succeed; `APPROVED → *`, `REJECTED → *` rejected with `409`/`InvalidApprovalStateException`);
  permission checks invoked before any persistence for every public method (mirroring `StaffServiceTest`'s
  coverage of the `d265597` defense-in-depth check).

### Testcontainers integration tests
- Teacher creation persists both rows correctly, `tenant_user.status = 'suspended'` immediately after
  creation (the key behavioral difference from Staff, which starts `active`) — assert this directly via JDBC,
  the same technique `StaffManagementIntegrationTest` already uses for its own `tenant_user` row assertions.
- Approve: `tenant_user.status → 'active'`, `teacher_profile.approval_status → APPROVED`,
  `approved_by`/`approved_at` populated with the acting Tenant Admin's id and a recent timestamp — both
  changes verified in the same test (not just the HTTP response).
- Reject: `tenant_user.status` remains `'suspended'`, `teacher_profile.approval_status → REJECTED`,
  `approved_by`/`approved_at` populated.
- **Login-blocked-while-pending, real end-to-end:** create a teacher, attempt login with the correct
  password before approval → `403 USER_SUSPENDED`; approve; retry login → succeeds. This is the single most
  important test in this module — it proves the login-gate mechanism (§7) actually works through the real
  `AuthenticationService` path, not a unit-level assumption.
- One-directional transition enforced: approve twice → second call `409`, row unchanged; reject an already-
  approved teacher → `409`, row unchanged.
- Role-gating: Tenant Admin succeeds on create/approve/reject; Course Coordinator succeeds on create,
  fails 403 on approve/reject (per §2's recommended design); every other role fails 403 on create;
  Student Support and Read-only Auditor succeed on list/detail, fail 403 on create/approve/reject.
- **Mandatory cross-tenant negative tests** — list, detail, approve, reject, each proving 403/404 and (for
  approve/reject) a follow-up read confirming no mutation occurred.

### Playwright (once frontend screens are buildable)
- Teacher List's distinct empty states; Add Teacher + resulting `PENDING` badge; Approve/Reject icon buttons'
  `aria-label`s asserted structurally (accessibility tree), not just visually; Course-Coordinator session
  shows no Approve/Reject controls (UI-hiding) **and** a direct API call still fails server-side (UI-hiding
  alone is not evidence of enforcement, per `.claude/rules/ui-ux.md` §1's staff-sub-role guidance applied
  here to Course Coordinator).

### Named follow-ups — explicitly blocked, not silently skipped
1. **All TCH-2 tests** (unit, Testcontainers, Playwright, cross-tenant, intra-tenant) — blocked on
   `course-management`/`CRS-3`. Do not write tests against a hand-rolled fake `course_teacher_assignment`
   table; that would not catch a real bug in the eventual real integration.
2. **Approval audit-log content assertions** — soft-blocked on `audit-log-management` (Module 19) and on
   §16's open decision.

## 19. Documentation changes

- `docs/architecture/database-architecture.md` — new `teacher_profile` table entry once implemented.
- `docs/api/user-management.md` (new) — produced via `review-api-contract` from §10's draft before
  implementation begins on either side; this module should not repeat the "process gap" already flagged for
  both `identity-access-service` and (implicitly) Staff Management.
- `docs/requirements/open-decisions.md` (if it doesn't already track these) — append: the `TEACHERS`/`APPROVE`
  matrix gap and its two resolution paths (§2), the unapproved-teacher-login mechanism confirmation (§4),
  teacher-delete/edit semantics (§6), and approval audit-logging (§16).
- `docs/ui-ux` — empty-state copy differentiation for Teacher List (zero-data / filtered / pending-queue) and
  My Courses (once TCH-2 unblocks).

## 20. Implementation order

Per root `CLAUDE.md`'s development workflow (plan → backend → backend tests → frontend → frontend/E2E tests →
security/tenant/integration review → docs → one logical commit), applied to this module's two-speed reality
(`TCH-1` buildable now, `TCH-2` blocked):

1. **Resolve §2's open decision** (approval-authority enforcement path) and **§4's login-gate mechanism**
   before writing code — both are cheap to confirm now and expensive to unwind after `TeacherService`/tests
   are written against an assumption.
2. `database-migration` — author `V11__create_teacher_profile.sql` per §8.
3. `implement-backend` — `TeacherProfile`/`TeacherProfileRepository`/`TeacherService`/`TeacherController`,
   plus the `UserProvisioningApi.setTenantUserStatus` extension in `identity-access-service` (§9), scoped as
   its own reviewable unit within the same backend pass given it touches a foundational module.
4. Backend tests (§18's unit + Testcontainers list) — run and review before moving to frontend, per workflow
   step 3.
5. `review-api-contract` — produce `docs/api/user-management.md` for the four `TCH-1` endpoints.
6. `implement-frontend` — Teacher List, Teacher Detail, Add Teacher (§11's three buildable screens). **My
   Courses is not built in this pass.**
7. Frontend/Playwright tests for the three buildable screens.
8. `security-review` + `tenant-isolation-review` — both mandatory per this module's own §14/§15, with
   particular attention to the login-gate end-to-end test (§18) and the approval-authority enforcement path
   (§2).
9. `update-documentation` (§19).
10. One logical commit for this `TCH-1` slice (per `.claude/rules/git-workflow.md` — backend and frontend as
    separate commits unless "full-stack implementation approved" was explicitly stated, which it was not
    here).
11. **`TCH-2` is a separate, later effort**, sequenced after `course-management`/`CRS-3` ships (per the
    backlog's own explicit sequencing note), re-entering this same plan-module workflow at that time rather
    than being retrofitted into this pass.

## 21. Risks and unresolved decisions

Every item below requires explicit sign-off before (or during, where noted) implementation — none are
resolved by this plan, per root `CLAUDE.md`'s "do not invent unresolved business decisions" instruction.

1. **Approval-authority enforcement path (§2).** Recommended: (a) Tenant-Admin-only defense-in-depth role
   check on top of the existing `CREATE_EDIT` grant, no matrix edit. Alternative: (b) add a new
   `TEACHERS`/`APPROVE` grant to the shipped `PermissionCheckServiceImpl` matrix. Materially different
   blast radius; pick one before writing `TeacherService.approveTeacher`/`.rejectTeacher`.
2. **Login-gate mechanism (§4/§7).** Recommended: reuse `tenant_user.status` (suspended while
   pending/rejected, active on approval), requiring a new `UserProvisioningApi.setTenantUserStatus` method.
   This is a concrete, grounded recommendation (not a blank open question) but still needs confirmation since
   it's the first time this codebase suspends an account as part of a *creation* flow rather than an
   after-the-fact admin action.
3. **Whether every newly-created teacher account starts `PENDING` regardless of creator**, including
   Tenant-Admin-created ones (vs. Tenant-Admin-created accounts auto-approving, with the approval gate only
   meaningfully applying to Course-Coordinator-created ones). This plan assumes uniform `PENDING` for
   schema/flow simplicity and because the issue's DB requirements text names `approval_status` without
   qualifying it by creator — flagged as an assumption, not a confirmed decision.
4. **Teacher delete/edit semantics (§6).** The matrix grants `DELETE`; no document defines what it means for
   a teacher with (eventually) assigned courses. Not built in this pass.
5. **Approval audit logging (§16).** Explicitly open per the issue itself; recommendation given but not
   decided.
6. **Rejected-teacher re-application** — not specified anywhere; not built (a second creation attempt with
   the same email simply `409`s today).
7. **Teacher Assistant's entire permission boundary** — PROVISIONAL, unratified, out of scope for this module
   (§6) but directly adjacent; any future work building Teacher Assistant onboarding needs its own sign-off
   pass, not a silent extension of this module's pattern.
8. **`course-management`/`CRS-3` timing** — TCH-2 is fully designed but not schedulable until Module 8 lands;
   this is a program-sequencing risk (the issue itself already flags this), not something this plan can
   resolve by itself.
