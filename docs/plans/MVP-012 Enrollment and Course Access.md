# MVP-012 Enrollment and Course Access — Module Plan

Issue: [#12](https://github.com/mohanranaweera/lms-saas-platform/issues/12) — "Module 12: Enrollment and
course access." Stories ENR-1 (activation — largely already shipped), ENR-2 (course-level access
expiry), ENR-3 (reactivation request + admin approval).

Status: **Backend implemented (2026-08-30), pending frontend (§11/§20 steps 5-6) and the
remaining `update-documentation` items in §19** (`docs/api/enrollment-management.md` is not
yet written; `docs/architecture/enrollment-access.md` §6/§7/§9 and
`docs/requirements/open-decisions.md` still need the MVP-012 updates §19 describes — track
these before this module is considered fully done, per root `CLAUDE.md`'s development
workflow). Per root `CLAUDE.md`, this module touches multiple
change-controlled areas (enrollment activation rules, database migration history, and — via a
new `OrderService` precondition — approved API contracts). Two structural decisions below (§7,
§9) require the same explicit-approval-before-implementation pattern already established by
`docs/adr/ADR-010-...md` and `docs/adr/ADR-012-...md`, not a unilateral implementation choice.

## Grounding note — this is not a greenfield module

A **minimal `enrollment-management` activation slice already exists and is live in production
schema**, pulled forward into MVP-010/MVP-011 under ADR-010/ADR-012:

- `enrollment` table (`V19`, FK added in `V21`): `tenant_id`, `student_id`, `course_id`,
  `activating_payment_id` (nullable), `activating_slip_id` (nullable), `status` (CHECK, single
  value `'ACTIVE'`), `activated_at`. `ck_enrollment_exactly_one_activation_source` and
  `uq_enrollment_tenant_student_course UNIQUE(tenant_id, student_id, course_id)`.
- `com.lms.enrollmentmanagement`: `Enrollment` (JPA entity, all columns `updatable=false`),
  `EnrollmentStatus` (single value `ACTIVE`), `EnrollmentRepository` (insert-only, every
  delete-shaped method overridden to throw), `EnrollmentActivationApi` /
  `EnrollmentActivationService` with **two already-approved activation paths**:
  `activateFromConfirmedPayment` (re-verifies via `PaymentStatusApi`) and
  `activateFromApprovedSlip` (re-verifies via `SlipStatusApi`, MVP-011). Both are idempotent via
  the unique constraint + `existsByStudentIdAndCourseId` pre-check, both atomic with the
  triggering payment/slip write (`PaymentConfirmationService`, `SlipReviewService`).
- `course.access_duration_days` (`V11`) already exists — `INTEGER`, nullable, `NULL` = unlimited
  access. **This is the exact field ENR-2's course-level expiry window is computed from** — no
  new course-side column is needed.
- `com.lms.auditlogmanagement` (`V21`) already exists: `AuditLogApi.record(AuditLogEntry)`,
  joins the caller's transaction (`REQUIRED` propagation), append-only.
- `DomainArea.ACCESS_EXPIRY` and the "Access & expiry / reactivation" row already exist in
  `PermissionCheckServiceImpl`'s shipped RBAC matrix (see §2).

This plan's job is **ENR-2 (expiry) and ENR-3 (reactivation)**, plus the one still-open piece of
ENR-1 (a real student-facing access-state read — the current slice has none). It explicitly does
**not** re-litigate the already-approved activation paths, does not re-open `entry_type`
(declined per ADR-012), and does not edit `V19`/`V20`/`V21`.

---

## 1. Business goal

Provably and atomically tie "access granted" to "money confirmed" (already substantially built),
extended with: (a) course-level access expiry as its own recorded event — never a mutation of the
payment/ledger record that originally granted access — and (b) a reactivation workflow that always
produces a **new** order/payment/ledger entry and a **new**, Tenant-Admin-approved enrollment
lineage row, never a resurrection or date-extension of the original payment. This closes the
platform's single most security/finance-critical junction for its second half: not just "was
access ever granted correctly," but "does access correctly and provably lapse and get restored."

## 2. Roles and permissions

Per the already-shipped `PermissionCheckServiceImpl` matrix (`DomainArea.ACCESS_EXPIRY`) —
**this plan does not add or change any RBAC grant**, only builds against what's already coded:

| Role | `ACCESS_EXPIRY` grant | Can do in this module |
|---|---|---|
| Tenant Admin (Institute Owner) | `VIEW, CREATE_EDIT, APPROVE` | View any student's access state; view reactivation queue; **approve/reject** reactivation requests (the only role with `APPROVE`) |
| Finance Staff | `VIEW` | View reactivation queue read-only; cannot approve/reject |
| Student Support | `VIEW` | Same as Finance Staff |
| Read-only Auditor | `VIEW` | Same, platform-wide read posture unaffected (still tenant-scoped) |
| Course Coordinator, Content Manager, Exam Manager, Attendance Operator | *(no grant)* | No access to the queue |
| Student | *(not in staff matrix — ownership-scoped)* | View own access state; submit a reactivation request for their own expired enrollment |

**Resolves, for this module's implementation, the open question "whether Finance Staff or
Institute Owner is the correct reactivation approver"** (flagged unresolved in
`enrollment-access.md` §9, `18-smart-expiry.md`, `user-roles-and-permissions.md` Open Q2, and the
issue itself) **by deferring to the RBAC matrix already shipped and live**: only Tenant Admin
holds `APPROVE`, so only Tenant Admin can approve/reject in this implementation. This is **not** a
new business decision — it is following existing, already-approved code. If the product intent is
actually "Finance Staff should also be able to approve," that is a change to the shipped RBAC
matrix (a different, already-existing module) and must go through its own change process, not be
decided inside this plan. Flagged forward in §21.

## 3. Preconditions

- MVP-010 (Order and Payment Foundation) and MVP-011 (Manual Payment Slips) are shipped — a
  confirmed `Payment` or an `APPROVED` `payment_slip` is the only activation evidence, exactly as
  today.
- An `enrollment` row already exists for a student+course (via one of the two existing activation
  paths) before expiry or reactivation logic has anything to act on.
- `course.access_duration_days` is set (or intentionally `NULL` for lifetime access) at the time
  of activation — this module reads it once, at activation time, and snapshots the result; it does
  not re-read `course.access_duration_days` later if a teacher changes it after a student is
  already enrolled (see §12).

## 4. User flows

### 4.1 Course-level access expiry (ENR-2)

1. At activation time (both the confirmed-payment and approved-slip paths), the service computes
   `access_expires_at = activated_at + course.access_duration_days` (via `CourseLookupApi`, a
   read-only cross-domain call already permitted architecturally), or `NULL` if
   `access_duration_days` is `NULL` (lifetime access, mirroring `course`'s own convention).
2. On every access-relevant read (the new `GET /api/v1/courses/{courseId}/access-state` endpoint,
   and any future `video-access-management`/`content-management` caller of the new
   `EnrollmentAccessApi`), access validity is **computed live**: `NOT superseded AND
   (access_expires_at IS NULL OR access_expires_at > now())`. This is a pure function of stored
   data — it is never itself the source of an update to `enrollment`.
3. The first time a live check observes an enrollment as expired, the service performs one
   idempotent, guarded insert into the new `enrollment_expiry_event` table (see §8) — a durable,
   queryable record of "this access lapsed on this date," distinct from and never overwriting
   `activating_payment_id`/`activated_at`/any payment or ledger row. Repeated observations after
   the first do not write a second row (partial unique index).
4. The student sees a **distinct "access expired" state** (not a generic error, not
   permission-denied, not "never enrolled") with a **Reactivate** call-to-action, wherever course
   access is surfaced.

### 4.2 Reactivation request + admin approval (ENR-3)

1. Student, viewing their own expired course access, submits a reactivation request
   (`POST /api/v1/enrollments/{enrollmentId}/reactivation-requests`). This creates a
   `reactivation_request` row in `SUBMITTED` state. **This does not reactivate anything by
   itself** — no enrollment/payment/order state changes yet.
2. Tenant Admin reviews the queue (`Tenant Admin > Access & Expiry > Reactivation Approvals`) and
   approves or rejects (`ACCESS_EXPIRY`/`APPROVE`). Approval/rejection is **audit-logged**
   (`AuditLogApi.record`, atomic with the status write) and irreversible (one-directional, no
   re-open — mirrors the manual-slip state machine's own precedent).
3. If rejected: access stays expired, no further action possible on this request (student may
   submit a new one).
4. If approved: the student may now place a **new** order for the same course
   (`POST /api/v1/orders`, existing endpoint). `OrderService` gains a new precondition — see §9 —
   that requires this exact approved-and-unfulfilled reactivation request to exist before it will
   allow a repeat order for a course the student already has a (superseded) enrollment lineage
   for. Placing this order links it to the reactivation request (`new_order_id`).
5. The student proceeds through the **same** checkout flow as initial purchase — gateway payment
   or manual slip upload, no shortcut, no partial/prorated special-casing (that question is
   explicitly open, see §21 — this MVP always requires a full new order at the course's current
   price).
6. When that new order's payment reaches `CONFIRMED` (or its slip reaches `APPROVED`),
   `EnrollmentActivationService` — via new `reactivateFromConfirmedPayment` /
   `reactivateFromApprovedSlip` methods — atomically: re-verifies payment/slip status (same
   defense-in-depth pattern as today), re-verifies the linked, approved, unfulfilled reactivation
   request, marks the prior enrollment row `superseded_at = now()`, and inserts a **new**
   `enrollment` row (new `activating_payment_id`/`activating_slip_id`, new `access_expires_at`,
   `reactivated_from_enrollment_id` pointing at the prior row). All in one transaction, mirroring
   the existing atomic-activation guarantee exactly.
7. The original, now-superseded `enrollment` row and every payment/ledger row from the original
   purchase are **never mutated** — the full timeline (paid → expired → reactivation requested →
   approved → paid again → reactivated) is reconstructable purely from history.

## 5. Acceptance criteria

Restating the issue's own criteria, made concrete against this design:

- [ ] No persisted `CONFIRMED` payment or `APPROVED` slip exists → no code path can activate or
      reactivate enrollment, even given a plausible-looking request payload. *(Already true for
      initial activation; extended identically to reactivation via the same
      `PaymentStatusApi`/`SlipStatusApi` re-verification.)*
- [ ] `CONFIRMED` payment persisted → activation/reactivation and payment confirmation commit in
      one transaction; the resulting `enrollment` row's activation source is a specific non-null
      FK to the confirming payment/slip row.
- [ ] `enrollment.course_id → course.id` stays enforced same-tenant via composite FK (unchanged;
      no new column touches this).
- [ ] Course access window lapses → student sees a distinct "access expired" state (not generic
      error/permission-denied) with a Reactivate CTA, at the real access-check endpoint — not
      simulated by a client-side countdown.
- [ ] Expiry is recorded as its own event (`enrollment_expiry_event`), never a mutation of the
      originating payment record.
- [ ] Reactivation always produces a new order/payment/ledger entry; the original expired payment
      row and the original `enrollment` row's activation-evidence columns are provably untouched.
- [ ] An unapproved reactivation request → access stays expired; the student cannot place a
      qualifying reactivation order without an `APPROVED` request; no partial activation exists at
      any intermediate step.
- [ ] Reactivation approval/rejection is audit-logged (reviewer identity, tenant, target
      enrollment/request, timestamp, before/after status).
- [ ] Cross-tenant negative test on: enrollment/access-state read, reactivation-request
      creation/read/queue, and reactivation approve/reject.

## 6. Out-of-scope items

Per the issue's own explicit phase boundary and `18-smart-expiry.md` §10's "SPLIT" classification
(course-level expiry + reactivation core = MVP; everything else = Phase 2):

- Session-level, material-level, and video-level expiry (Module 8/`content-management`'s own
  future work — this module only unblocks it via the new read `api`, does not implement it).
- The full expiry **rules engine** (course/tenant/student-level precedence overrides) — MVP
  computes expiry from `course.access_duration_days` only, no override layer.
- **Grace period** (buffer after nominal expiry) — no grace period is implemented; expiry is a
  hard cutover at `access_expires_at`.
- **Auto-reminder notification before expiry** — requires `notification-management` event
  wiring; explicitly Phase 2.
- **Bulk expiry extension** and **student-specific override** — both explicitly Phase 2 per
  `18-smart-expiry.md` §10; no admin action exists in this module to extend an individual or
  bulk set of enrollments without a new payment.
- Prorated/partial reactivation payment — reactivation always requires a full new order at the
  course's current price (see §21 open item).
- Tenant-configurable "does this tenant require approval for reactivation at all" toggle —
  approval is unconditionally required in this MVP (see §21).
- Wiring `content-management`/`video-access-management` over to the new real enrollment-based
  access check (they currently use interim, non-enrollment-based checks per
  `module-catalog.md`) — this module exposes the `api` they need; consuming it is each of those
  modules' own future PR.

## 7. Domain model

**Decision requiring explicit approval before implementation** (mirrors ADR-010/012's pattern —
this is the module's one genuinely new structural choice):

- `enrollment.status` stays a single-value CHECK (`'ACTIVE'`) — it answers "was this row's
  activation valid," never "is access currently live." **Access currency is never stored as an
  enum value on the row** — it is always computed from `superseded_at`/`access_expires_at` vs
  `now()`, since a Postgres `CHECK` constraint cannot reference `now()` and computing it
  server-side at read time (one shared method, unit-tested) is simpler and less bug-prone than
  trying to keep a stored status in sync via a background job this module doesn't otherwise need.
- **Enrollment becomes a lineage of rows per (tenant, student, course)**, not a single row that's
  mutated in place. `uq_enrollment_tenant_student_course` (plain unique) is replaced by a partial
  unique index scoped to "current" rows only (`WHERE superseded_at IS NULL`). A reactivation never
  touches the prior row's `activating_payment_id`/`activating_slip_id`/`activated_at` — it writes
  a **new** row and sets exactly one new column (`superseded_at`) on the old one. This mirrors two
  existing precedents in this codebase: `payment_slip`'s "narrow, single-purpose in-place status
  update, for consistency" reasoning (V21 header comment), and `ledger_entry`'s
  reversal-references-original pattern (`reverses_entry_id`) applied here as
  `reactivated_from_enrollment_id`.
- Alternative considered and rejected: keep one mutable row per (student, course) and update
  `access_expires_at`/`activating_payment_id` in place on reactivation. Rejected because it would
  overwrite the original activation evidence — directly violating "the original expired payment
  record untouched" and "full timeline reconstructable from history alone"
  (`enrollment-access.md` §7). The lineage-row design keeps every historical activation queryable
  forever, at the cost of one extra join to find "the current row."

### New/changed entities

- **`Enrollment` (existing, `com.lms.enrollmentmanagement.domain`)** — gains
  `accessExpiresAt` (nullable `Instant`), `supersededAt` (nullable `Instant`, settable exactly
  once via a new narrow package-private mutator, never via a public setter), and
  `reactivatedFromEnrollmentId` (nullable `UUID`, set only at construction of a reactivation row).
  `fromConfirmedPayment`/`fromApprovedSlip` factories gain an `accessExpiresAt` parameter; two new
  factories `reactivatedFromConfirmedPayment`/`reactivatedFromApprovedSlip` additionally accept
  `reactivatedFromEnrollmentId`.
- **`EnrollmentExpiryEvent` (new)** — `tenantId`, `enrollmentId`, `eventType` (single value
  `'EXPIRED'` at MVP — no `REMINDER`/`GRACE_STARTED` values, mirroring `enrollment.status`'s own
  "don't add values speculatively" precedent), `occurredAt`. Fully append-only (no update/delete
  exposed, matching `payment_slip_flag`'s exact shape).
- **`ReactivationRequest` (new)** — `tenantId`, `enrollmentId` (the row being reactivated, always
  the current/superseded-at-submission-time row), `requestedBy` (student's `tenant_user` id),
  `status` (`SUBMITTED` → `APPROVED` | `REJECTED`, one-directional, mirrors `payment_slip`'s
  shape), `reviewedBy`/`reviewedAt` (nullable until decided), `newOrderId` (nullable until the
  student places the qualifying order). In-place `status` update mirrors `payment_slip.status`'s
  established precedent (V21) rather than introducing a second append-only-vs-in-place convention
  in the same domain.

## 8. Database design

New migration **`V22__create_enrollment_expiry_and_reactivation_schema.sql`**
(`backend/src/main/resources/db/migration/`) — `V19`/`V20`/`V21` are never edited, per this
repository's established convention. Every table/column below is additive only.

```sql
-- enrollment: additive columns + replace plain unique with a partial "current row" unique.
ALTER TABLE enrollment
    ADD COLUMN access_expires_at TIMESTAMPTZ,
    ADD COLUMN superseded_at TIMESTAMPTZ,
    ADD COLUMN reactivated_from_enrollment_id UUID;

ALTER TABLE enrollment
    ADD CONSTRAINT fk_enrollment_reactivated_from
        FOREIGN KEY (tenant_id, reactivated_from_enrollment_id)
        REFERENCES enrollment (tenant_id, id);

ALTER TABLE enrollment DROP CONSTRAINT uq_enrollment_tenant_student_course;

CREATE UNIQUE INDEX uq_enrollment_tenant_student_course_current
    ON enrollment (tenant_id, student_id, course_id)
    WHERE superseded_at IS NULL;

-- enrollment_expiry_event: append-only, one row per (enrollment, event_type) at MVP.
CREATE TABLE enrollment_expiry_event (
    id            UUID PRIMARY KEY,
    tenant_id     UUID NOT NULL REFERENCES tenant (id),
    enrollment_id UUID NOT NULL,
    event_type    VARCHAR(20) NOT NULL,
    occurred_at   TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_enrollment_expiry_event_enrollment
        FOREIGN KEY (tenant_id, enrollment_id) REFERENCES enrollment (tenant_id, id),
    CONSTRAINT ck_enrollment_expiry_event_type CHECK (event_type = 'EXPIRED')
);

CREATE UNIQUE INDEX uq_enrollment_expiry_event_tenant_enrollment_type
    ON enrollment_expiry_event (tenant_id, enrollment_id, event_type);

CREATE INDEX idx_enrollment_expiry_event_tenant_enrollment
    ON enrollment_expiry_event (tenant_id, enrollment_id);

-- reactivation_request
CREATE TABLE reactivation_request (
    id            UUID PRIMARY KEY,
    tenant_id     UUID NOT NULL REFERENCES tenant (id),
    enrollment_id UUID NOT NULL,
    requested_by  UUID NOT NULL,
    status        VARCHAR(20) NOT NULL,
    reviewed_by   UUID,
    reviewed_at   TIMESTAMPTZ,
    new_order_id  UUID,
    created_at    TIMESTAMPTZ NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL,

    CONSTRAINT uq_reactivation_request_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_reactivation_request_enrollment
        FOREIGN KEY (tenant_id, enrollment_id) REFERENCES enrollment (tenant_id, id),
    CONSTRAINT fk_reactivation_request_requested_by
        FOREIGN KEY (tenant_id, requested_by) REFERENCES tenant_user (tenant_id, id),
    CONSTRAINT fk_reactivation_request_reviewed_by
        FOREIGN KEY (tenant_id, reviewed_by) REFERENCES tenant_user (tenant_id, id),
    CONSTRAINT fk_reactivation_request_new_order
        FOREIGN KEY (tenant_id, new_order_id) REFERENCES student_order (tenant_id, id),

    CONSTRAINT ck_reactivation_request_status
        CHECK (status IN ('SUBMITTED', 'APPROVED', 'REJECTED')),
    CONSTRAINT ck_reactivation_request_reviewed_requires_reviewer
        CHECK (status NOT IN ('APPROVED', 'REJECTED')
               OR (reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL))
);

-- At most one open (SUBMITTED) request per enrollment — mirrors
-- uq_payment_slip_tenant_order_active's exact partial-unique pattern.
CREATE UNIQUE INDEX uq_reactivation_request_tenant_enrollment_open
    ON reactivation_request (tenant_id, enrollment_id)
    WHERE status = 'SUBMITTED';

CREATE INDEX idx_reactivation_request_tenant_status
    ON reactivation_request (tenant_id, status);

CREATE INDEX idx_reactivation_request_tenant_enrollment
    ON reactivation_request (tenant_id, enrollment_id);
```

All tenant-isolation conventions from `.claude/rules/tenancy.md`/`database-architecture.md` are
followed: every table `tenant_id NOT NULL REFERENCES tenant(id)`, every cross-table reference is a
composite `(tenant_id, ...)` FK, every table has a `tenant_id`-leading index matching its real
query shape, no `ON DELETE CASCADE` anywhere (this is financial/access history that must outlive
its parents).

## 9. Backend design

**Decision requiring explicit approval before implementation** (the second structural choice):
`OrderService.createOrder` (owned by `payment-management`) needs a new precondition when a
student orders a course they already have an enrollment lineage for:

- No enrollment exists yet → unchanged, ordinary first-time purchase.
- A **current** (`superseded_at IS NULL`), still-**active** (not expired) enrollment exists →
  reject `409 CONFLICT` ("already enrolled") — a new guard; not previously enforced because
  expiry didn't exist before this module.
- A current enrollment exists but is **expired** → require an `APPROVED`, unfulfilled
  (`new_order_id IS NULL`) `reactivation_request` for that enrollment to exist, else reject `409
  CONFLICT` ("reactivation approval required"). If it exists, create the order normally and, in
  the same transaction, link it (`reactivation_request.new_order_id = order.id`) via a new
  cross-domain synchronous call.

This requires `enrollment-management` to expose two new read/write `api` methods that
`payment-management` depends on (one-directional dependency, consistent with the existing
`payment-management → enrollment-management` direction being reversed only for reads/narrow
writes — see `.claude/rules/architecture.md`'s "prefer synchronous calls through injected `api`
service interfaces for request-time reads/writes that must be consistent within the same
transaction" guidance):

```java
// com.lms.enrollmentmanagement.api — NEW
public interface EnrollmentAccessApi {
    Optional<EnrollmentAccessState> resolveAccessState(UUID studentId, UUID courseId);
    // ACTIVE / EXPIRED / NEVER_ENROLLED — computed live, never stored.
}

public interface ReactivationLinkingApi {
    // Called by OrderService inside its own order-creation transaction.
    // Throws IllegalStateException if no APPROVED+unfulfilled request exists —
    // OrderService maps this to 409, never proceeds to create the order.
    void linkApprovedRequestToNewOrder(UUID studentId, UUID courseId, UUID newOrderId);
}
```

`EnrollmentActivationApi` (existing) gains two new methods mirroring the existing pair exactly,
including the same independent re-verification-via-`PaymentStatusApi`/`SlipStatusApi` discipline:

```java
void reactivateFromConfirmedPayment(UUID paymentId, UUID studentId, UUID courseId);
void reactivateFromApprovedSlip(UUID slipId, UUID studentId, UUID courseId);
```

Both: look up the current (superseded_at IS NULL) enrollment row for (student, course) and its
linked, approved, order-matching `reactivation_request`; if any of that chain doesn't resolve,
throw (refuse to activate — payment stays `CONFIRMED`, but no enrollment/access change happens,
which is a legitimate, logged edge case for ops follow-up, not a silent partial activation).
Otherwise, in one `@Transactional` method: mark the prior row `superseded_at = now()`, insert the
new row, mark `reactivation_request` fulfilled implicitly via its `new_order_id` already being set
(no further request-row write needed).

**New services**: `EnrollmentExpiryService` (the live `resolveAccessState` computation + lazy,
idempotent `enrollment_expiry_event` write, guarded by
`uq_enrollment_expiry_event_tenant_enrollment_type` + catch-`DataIntegrityViolationException`,
mirroring the existing activation race-handling pattern), `ReactivationRequestService`
(submit/list/approve/reject, `AuditLogApi.record` call inside the same transaction as
approve/reject — mirrors `SlipReviewService.approve`'s exact atomicity pattern).

**New guard**: `ReactivationAccessGuard` (or extend `PaymentDomainAccessGuard`'s pattern) for
owner-student-or-staff-`ACCESS_EXPIRY`/`VIEW` reads, 404-not-403 anti-enumeration for a
non-owning student, mirroring every existing read guard in this codebase.

## 10. API contract

New file **`docs/api/enrollment-management.md`** (via `review-api-contract` skill before
implementation, per this repo's convention — sketched here for planning purposes only):

| Method + path | Auth | Purpose |
|---|---|---|
| `GET /api/v1/courses/{courseId}/access-state` | Owner student or staff `ACCESS_EXPIRY`/`VIEW` | `{ state: NEVER_ENROLLED\|ACTIVE\|EXPIRED, accessExpiresAt, canRequestReactivation }` — the "real fetch/access endpoint" the AC references |
| `GET /api/v1/enrollments/my` | `hasRole('STUDENT')`, owner-only, no id param (mirrors `user-management`'s `/me` anti-enumeration-by-construction pattern) | Student's own enrollment list (My Courses), each row's access state |
| `POST /api/v1/enrollments/{enrollmentId}/reactivation-requests` | Owning student only | `201`; `409` if not currently expired or an open request already exists |
| `GET /api/v1/reactivation-requests/my` | Owning student only | Student's own request history |
| `GET /api/v1/reactivation-requests` | Staff `ACCESS_EXPIRY`/`VIEW` | Paginated queue, `status` filter, default `SUBMITTED`-first FIFO (mirrors slip review queue) |
| `POST /api/v1/reactivation-requests/{id}/approve` | Staff `ACCESS_EXPIRY`/`APPROVE` (Tenant Admin only, see §2) | `200`; audit-logged; idempotent on an already-`APPROVED` request |
| `POST /api/v1/reactivation-requests/{id}/reject` | Same as approve | `200`; body `{ reason }` required non-blank; audit-logged |

`POST /api/v1/orders` (existing, `payment-management`) — contract addition: new `409 CONFLICT`
response variants ("already enrolled", "reactivation approval required") documented in
`docs/api/payment-management.md`'s existing entry for this endpoint, not a new endpoint.

Every endpoint: `ApiResponse<T>` envelope, tenant/role never accepted client-side, standard
anti-enumeration (404 not 403 for non-owning student reads), matching every convention already
established in `docs/api/payment-management.md`.

## 11. Frontend screens

Per the issue's own explicit frontend scope ("Enrollment activation is backend-only directly,
surfaced through Module 13/8's access states... no dedicated Enrollments screen") plus the fact
that **no "My Courses" page currently exists** in `app/(student)/student/courses/` (only
`[courseId]/modules` subroutes) — flagged as a real gap, not invented scope, in §21.

- **`app/(student)/student/courses/page.tsx` (new — minimal "My Courses" list)**: the natural,
  currently-missing home for the access-expired state + Reactivate CTA to attach to, since no
  other shipped module owns this screen yet. Each row: course name, access state badge
  (Active/Expired — never color-only, per `.claude/rules/ui-ux.md` §4), "Reactivate" CTA for
  expired rows. Empty state: "no active enrollments yet" + CTA to `/courses` catalog (per
  `09-enrollments.md`'s own UI note).
- **`app/(student)/student/payments/reactivation/page.tsx` (new)**: submit a reactivation
  request for a specific expired enrollment (reached via the Reactivate CTA); shows the student's
  own reactivation-request history/status (Submitted / Approved — proceed to checkout / Rejected).
  Nav: added to `StudentNav` under "Payments" (mirrors existing `/student/payments/*` grouping).
- **`app/(tenant-admin)/tenant-admin/access-expiry/reactivation-approvals/page.tsx` (new)**:
  queue list (paginated, status-filterable), detail view, approve (with optional note) / reject
  (reason required) actions. Nav entry gated by `canViewPaymentDashboard`-equivalent new helper
  `canViewAccessExpiryQueue`/`canApproveReactivation` in `lib/auth/permissions.ts`, mirroring the
  existing `canReviewSlips` pattern exactly (Tenant Admin only for approve/reject; Finance
  Staff/Student Support/Read-only Auditor see the queue read-only per their `VIEW` grant).
- Loading/empty/error/permission-denied states, `aria-live`/`aria-busy`/`role="alert"` on
  async actions, and mobile card-view fallback for the admin queue table — all required per
  baseline `frontend/CLAUDE.md` and `.claude/rules/ui-ux.md`, no exceptions for this module.

## 12. Validation rules

- `reactivation_request` creation: `enrollmentId` must resolve to a **current**
  (`superseded_at IS NULL`) enrollment owned by the caller, and its computed access state must be
  `EXPIRED` — else `409`. At most one `SUBMITTED` request per enrollment at a time (schema-enforced
  partial unique index, not just a service-layer check).
- Reject reason: required, non-blank, max 1000 chars — mirrors `payment_slip`'s reject-reason
  convention exactly.
- `access_expires_at` is computed **once**, at (re)activation time, from `course.access_duration_days`
  as it reads at that instant — a later change to the course's `access_duration_days` never
  retroactively changes an already-activated enrollment's `access_expires_at` (consistent with
  `course_price_history`'s "snapshot at the time" pattern for `student_order.amount`).
- Order-creation new preconditions (§9) validated server-side only; no client-side attempt to
  infer "can I reactivate" beyond rendering the CTA — the actual gate is always the backend 409.

## 13. Error cases

| Scenario | Response |
|---|---|
| Reactivation request submitted for a non-expired or nonexistent (cross-tenant/not-owned) enrollment | `404` (anti-enumeration, student caller) / `409` (owned but not expired) |
| Second `SUBMITTED` request while one is already open | `409 CONFLICT` |
| Approve/reject by non-Tenant-Admin staff (Finance Staff, Student Support, Read-only Auditor) | `403` |
| Approve/reject by a student | `403` |
| Reject with blank/missing reason | `400`/`422` before any state change |
| Order creation for an already-active (non-expired) enrollment | `409 CONFLICT` ("already enrolled") |
| Order creation for an expired enrollment with no `APPROVED` unfulfilled request | `409 CONFLICT` ("reactivation approval required") |
| Payment/slip confirms for a reactivation order whose linked request was never approved (should be structurally unreachable given §9's order-creation gate, but re-verified anyway) | Activation refused, payment stays `CONFIRMED`, no enrollment change — logged as an ops-visible inconsistency, not silently swallowed |
| Access-state read for a cross-tenant or another student's course | `404` |

## 14. Tenant-isolation rules

- Every new table (`enrollment_expiry_event`, `reactivation_request`) carries `tenant_id NOT
  NULL REFERENCES tenant(id)`, every FK is composite `(tenant_id, ...)`, every unique/partial-unique
  index leads with `tenant_id`.
- `EnrollmentAccessApi`/`ReactivationLinkingApi` resolve tenant identity exclusively from
  `TenantContext` — no overload accepts a caller-supplied tenant id (mirrors `PaymentStatusApi`/
  `SlipStatusApi` exactly).
- No platform-admin cross-tenant bypass anywhere in this module — aggregate/cross-tenant reporting
  is explicitly `reporting-analytics`'s concern, not this module's.
- Mandatory cross-tenant negative tests (see §18) on: access-state read, reactivation-request
  create/read/queue, approve/reject.

## 15. Security rules

- **Enrollment activation is a named change-controlled area** — the two new activation methods
  (`reactivateFromConfirmedPayment`/`reactivateFromApprovedSlip`) are not a *third* activation
  path (still gated by the same two evidence types), but the new order-creation precondition and
  the lineage-row schema change are structural changes requiring an ADR before implementation,
  per §7/§9 above and this repo's established ADR-010/ADR-012 precedent.
- No code path may accept a client-reported "reactivation payment succeeded" payload as evidence
  — identical structural guarantee to the existing initial-activation path (independent
  `PaymentStatusApi`/`SlipStatusApi` re-verification, never trusting the caller).
- Expiry never deletes/mutates prior payment/ledger history (enforced by construction — no code
  path in this module writes to `payment`/`ledger_entry`/`payment_refund` at all).
- Reactivation always creates a new order/payment/ledger entry — never resurrects/extends the old
  payment's dates (enforced by construction — `OrderService.createOrder` is the only way a new
  `student_order` row is created; this module never touches `payment`/`ledger_entry` rows
  directly).
- Approve/reject actions are `ACCESS_EXPIRY`/`APPROVE`-gated at the endpoint, independently
  re-checked at the service layer (defense-in-depth, mirroring `RefundService`'s pattern) — a
  `PermissionCheckService.hasPermission` `true` result is a category grant only, never sufficient
  authorization for the mutation itself, per `.claude/rules/payments.md` §8.

## 16. Audit requirements

**Mandatory**, per `.claude/rules/security.md`'s canonical list — "reactivation approvals" is
explicitly named:

- Every `reactivation_request` approve/reject writes exactly one `AuditLogApi.record(...)` entry
  — `actorId` = reviewing Tenant Admin, `action` = `"reactivation_request.approved"` /
  `"reactivation_request.rejected"`, `targetEntity` = `"reactivation_request"`, `targetId` =
  request id, `reason` = the rejection reason (or approval note, if supplied), atomic with the
  status write (same transaction, mirroring `SlipReviewService.approve`'s exact pattern).
- **Automatic course-level expiry itself is not an audit-logged action** — it is a system-detected
  state transition with no human actor (`audit_log.actor_id` is `NOT NULL` and FK'd to
  `tenant_user`, structurally incompatible with a system-initiated event). Its durable record is
  `enrollment_expiry_event`, which is the correct, purpose-built home per
  `docs/architecture/enrollment-access.md` §7 — this distinction is called out explicitly so a
  future reviewer doesn't file "missing audit log for expiry" as a defect.
- "Access/expiry extensions" (the other item named in the canonical list) does not apply at MVP —
  bulk extension and student-specific override are both explicitly Phase 2 (§6); no code path in
  this module can extend access without a new payment.

## 17. Payment impact

- **No new `ledger_entry.entry_type` value** — reactivation's new payment/slip goes through the
  exact same `PAYMENT_CONFIRMED` entry-type path already fixed by ADR-010; this module never
  touches `ledger_entry`/`payment`/`payment_refund` schemas or write paths at all.
- **No change to Phase 1/2/3/4 boundaries** — reactivation is a new order at the course's current
  price through the existing Phase 1 centralized-collection flow; nothing here anticipates
  settlement/tenant-payment-account concerns.
- `OrderService.createOrder`'s new preconditions (§9) are the only payment-management-owned code
  this module's implementation needs to touch, and only to add a guard before order creation —
  the order/payment/ledger write paths themselves are unchanged.
- Reactivation is always a full new order — no prorated/partial payment logic exists anywhere in
  this design (flagged open in §21, not decided here).

## 18. Tests

Per `.claude/rules/testing.md`'s required matrix and `module-catalog.md`'s
`enrollment-management` row ("activation test proving enrollment activates only from a persisted,
verified payment/approval record, never from request payload; cross-tenant test on enrollment
read/list"), extended for this module's new surface:

**Unit**
- `EnrollmentExpiryService`: `resolveAccessState` pure-logic matrix (never activated / active,
  no expiry / active, not yet expired / expired, no reactivation / expired, current row +
  reactivated). `access_expires_at` computation from `course.access_duration_days` (incl. `NULL`
  → `NULL`/lifetime).
- `Enrollment` factory invariants: reactivation factories still enforce
  `ck_enrollment_exactly_one_activation_source`-equivalent at construction time.

**Integration / Testcontainers**
- **Activation-eligibility matrix, extended**: enumerate all activation *and reactivation* call
  sites; assert each traces back to a persisted `CONFIRMED` payment or `APPROVED` slip, never a
  request payload alone (mirrors `PaymentConfirmationRollbackIntegrationTest`'s existing
  rollback-proof technique, extended to the reactivation pair).
- Idempotency: duplicate webhook/approval for a reactivation order does not double-write the
  `enrollment` row or double-supersede the prior row.
- Expiry: `enrollment_expiry_event` write is exactly-once under concurrent reads past expiry
  (race test against the partial unique index); original `payment`/`ledger_entry`/`enrollment`
  activation columns provably unchanged (byte-for-byte row comparison before/after expiry
  observation).
- Reactivation append-only proof: after a full reactivation cycle, assert the original
  `enrollment` row's `activating_payment_id`/`activating_slip_id`/`activated_at` are unchanged
  (only `superseded_at` differs), and the original `payment` row is unchanged.
- Order-creation gating: already-active enrollment → `409`; expired with no approved request →
  `409`; expired with approved request → order created and linked; a second order attempt against
  the same already-linked (fulfilled) request → `409` (no double-reactivation).
- **Mandatory cross-tenant negative tests** on: access-state read, reactivation-request
  create/read/queue/approve/reject (Tenant A staff/student must never read/act on Tenant B rows).
- Authorization matrix: Finance Staff/Student Support/Read-only Auditor can view the queue but
  get `403` on approve/reject; a student gets `403` on the queue and on any other student's
  request.

**Frontend / Playwright**
- Student: expired course renders the distinct access-expired state (not a generic error) with a
  working Reactivate CTA; submitting a request shows Submitted status; after admin approval
  (seeded via API), the student can complete a new checkout.
- Tenant Admin: queue empty state vs. filtered-empty state distinction; approve/reject flow
  including required-reason enforcement on reject; audit trail note not fabricated/hidden.
- Accessibility: keyboard-only completion of the reactivation-request form and the admin
  approve/reject actions; `aria-live`/`role="alert"` on submission results.

## 19. Documentation changes

Per the issue's own documentation requirements:

- **`docs/architecture/enrollment-access.md`**: update §6/§7/§9 to reflect the shipped
  lineage-row model (this plan's §7) once implemented and approved, and move the now-resolved
  "MVP scope for course expiry + reactivation core" items out of §9's open-questions list, leaving
  the genuinely still-open ones (grace period, precedence order, prorated reactivation, approver
  scope-widening) explicitly in place — do not silently drop them.
- **`docs/api/enrollment-management.md`** (new file, via `review-api-contract`): the contract
  sketched in §10, finalized against the real shipped shapes.
- **`docs/api/payment-management.md`**: add the two new `409` variants to the existing
  `POST /api/v1/orders` entry.
- **`docs/requirements/specifications/09-enrollments.md`** / **`18-smart-expiry.md`**: update
  MVP/later-phase classification notes to reflect what actually shipped, and record the
  Tenant-Admin-only approver resolution (§2 of this plan) as an implementation note, explicitly
  flagged as "deferred to existing RBAC, not a new business ratification" per this plan's own
  framing.
- **`docs/requirements/open-decisions.md`**: append a new dated entry under a new "§18 Enrollment
  and Course Access (MVP-012)" heading listing every item in this plan's §21, per this log's
  established convention (see its own §15/§16/§17 precedent for MVP-006/008/010).
- **New ADR** under `docs/adr/` (e.g. `ADR-013-enrollment-lineage-and-reactivation-order-gate.md`)
  covering §7 and §9's two structural decisions, following the ADR-010/ADR-012 template exactly —
  **required before implementation begins**, not written retroactively this time, since both
  decisions are identified during planning rather than discovered mid-implementation.

## 20. Implementation order

Per root `CLAUDE.md`'s development workflow (plan → backend → backend tests → frontend → frontend/E2E
tests → security/tenant/integration review → docs → one logical commit per step):

1. **ADR** for §7 (lineage-row model) and §9 (order-creation reactivation gate) — explicit
   product-owner sign-off before any code, per this plan's own framing above.
2. **`database-migration` skill** → `V22__create_enrollment_expiry_and_reactivation_schema.sql`.
3. **`implement-backend`**: `enrollment-management` domain/service/repository changes
   (`Enrollment` entity extension, `EnrollmentExpiryEvent`, `ReactivationRequest`,
   `EnrollmentAccessApi`, `ReactivationLinkingApi`, extended `EnrollmentActivationApi`,
   `EnrollmentExpiryService`, `ReactivationRequestService`, web layer/DTOs); then the narrow
   `payment-management`-side `OrderService` precondition change (a separate, clearly-scoped
   commit within the same PR, per this plan's own cross-domain call design).
4. Backend tests (§18 unit/integration) — run and reviewed before frontend work starts.
5. **`implement-frontend`**: My Courses (new), reactivation request/status pages (student),
   Reactivation Approvals queue (tenant admin), nav updates.
6. Frontend + Playwright E2E tests (§18).
7. **`security-review`**, **`tenant-isolation-review`**, **`payment-ledger-review`** skills —
   explicit passes before commit, given this module's change-controlled surface.
8. **`update-documentation`** skill — §19's file list.
9. Commit as logically-scoped units per `.claude/rules/git-workflow.md` (e.g., "db: add
   enrollment lineage/expiry/reactivation schema (V22)", "backend: implement course-access expiry
   and reactivation workflow", "backend: gate order creation on reactivation approval",
   "frontend: implement access-expired state and reactivation flows", "docs: update
   enrollment-access architecture and API contract for MVP-012") — never bundling backend and
   frontend into one commit per root `CLAUDE.md`.

## 21. Risks and unresolved decisions

Carried over, not invented, from `enrollment-access.md` §9, `18-smart-expiry.md`'s Open
decisions, `user-roles-and-permissions.md` Open Q2, and the issue's own "Out of scope / open
decisions" section — **none of these are resolved by this plan**, and implementation must not
silently assume an answer to any of them:

1. **Exact grace period length(s)** — not specified anywhere; this MVP implements a hard cutover
   at `access_expires_at` with no grace period at all (not "grace period = 0 days" as a ratified
   value — simply "no grace period feature exists yet").
2. **Expiry-rules-engine precedence order** (student/course/tenant/plan) — explicitly **not**
   assumed to mirror the device-limit precedence pattern; not built at all in this MVP (only
   `course.access_duration_days` is read, no override layer).
3. **Whether reactivation always requires a full new payment, or a prorated/partial payment is
   ever allowed** — this MVP always requires a full new order at current price; no partial-payment
   mechanism exists.
4. **Whether bulk expiry extension requires a second-approver step** — moot for this MVP since
   bulk extension itself is out of scope (§6).
5. **Exact reminder timing before expiry** — moot; no reminder notification exists in this MVP
   (Phase 2, requires `notification-management` event wiring).
6. **Whether Finance Staff (in addition to Tenant Admin) should be able to approve
   reactivation requests** — this plan's §2 defers to the already-shipped RBAC matrix
   (Tenant-Admin-only `APPROVE`) rather than deciding this independently; explicitly flagged as
   *possibly* not the final product intent, since three separate source documents still list this
   as an open question the RBAC matrix's shipped state doesn't itself claim to have resolved.
7. **Whether reactivation approval should ever be tenant-configurable** (some tenants skip
   approval entirely) — this MVP makes approval unconditionally required for every tenant; no
   config knob exists.
8. **Two structural technical decisions (§7 lineage-row model, §9 order-creation gate) require
   the ADR named in §19/§20 before implementation** — these are this plan's own design proposals,
   not yet product-owner-approved; do not begin `implement-backend` against them without that
   sign-off, mirroring ADR-010/ADR-012's precedent of getting sign-off *before* code this time
   rather than retroactively.
9. **No "My Courses" page exists anywhere in the frontend today** (confirmed by directory
   listing) — this plan proposes building a minimal one as this module's own scope (§11) since
   nothing else currently owns it and the access-expired-state acceptance criterion needs a real
   surface to render on. If a different module is intended to own this screen's full build-out
   (course browsing, progress, etc.), this minimal version should be treated as a placeholder to
   be extended, not duplicated, by that future module.
10. **`content-management`/`video-access-management` still use interim, non-enrollment-based
    access checks** (per `module-catalog.md`) — this module's new `EnrollmentAccessApi` unblocks
    them switching over, but does not itself perform that switch; those modules' own future PRs
    must do so deliberately, not assume it happens automatically.

## Related

- `docs/architecture/enrollment-access.md`, `docs/architecture/payment-ledger.md`
- `docs/adr/ADR-010-ledger-entry-type-and-enrollment-slice.md`,
  `docs/adr/ADR-012-audit-log-slice-and-slip-enrollment-activation.md`
- `docs/requirements/specifications/09-enrollments.md`,
  `docs/requirements/specifications/18-smart-expiry.md`
- `docs/api/payment-management.md`
- `docs/requirements/open-decisions.md` §4, §8
- `V19__create_payment_management_schema.sql`, `V21__create_payment_slip_schema.sql`
