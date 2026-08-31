# Enrollment Activation & Access Control Architecture

Status: Living document — reflects current design decisions and confirmed roadmap.
Change control: This document describes the "enrollment activation rules" area named
in root `CLAUDE.md`'s Change Controls section. Any change to the activation source of
truth, the atomic-transaction requirement, or the expiry model described here requires
explicit approval and an accompanying ADR under `docs/adr/`.

Owning domain (per `.claude/rules/architecture.md`): `enrollment-management`.
Consumes read-only state from `payment-management` and
`ledger-settlement-management` via their `api` interfaces only — never their
repositories/entities directly. See `docs/architecture/payment-ledger.md` for the
payment/ledger side of this relationship.

## 1. Overview

Enrollment activation is the single most security/finance-sensitive junction point
in the system: it is where "money confirmed" becomes "access granted." Getting this
wrong in either direction is a real business risk — granting access without confirmed
payment is revenue leakage; failing to grant access after confirmed payment is a
support/trust failure. The rules in this document exist to make the "granted" side
provably tied to the "confirmed" side.

## 2. Activation source of truth: payment/ledger state, never order state alone

- Enrollment activation code reads **payment/ledger state**, never order state. An
  `Order` being `PLACED` or `PENDING` is not activation evidence — an order records
  intent, not confirmed money movement.
- The only things that may authorize activation are:
  1. A **`Payment` row in a `CONFIRMED` terminal state** (automated/gateway path,
     `docs/architecture/payment-ledger.md` §2.1), or
  2. An **`APPROVED` manual payment slip** (manual evidence path,
     `docs/architecture/payment-ledger.md` §3).
- Enrollment activation must have a **FK/NOT NULL trail** back to the specific
  confirmed payment or approved manual-payment-evidence row that authorized it.
  Activation cannot exist as a bare boolean flag (`is_active = true`) with no
  linkage to what justified it — if an auditor or support agent asks "why is this
  student enrolled," the answer must be a specific, queryable row, not "the flag says
  so."

## 3. Prohibited activation path: frontend success page

- **Enrollment must never activate from a frontend success page.** A frontend
  redirect to `/payment/success` (or equivalent) after a gateway checkout flow is a
  UX signal only — it tells the student "you're probably done," it does not and must
  not carry any authority to flip enrollment state.
- Concretely: no API endpoint that activates enrollment may be triggered by a request
  whose payload is "the frontend says the payment succeeded." Any such payload is
  client-controlled and cannot be trusted as payment evidence — it must instead cause
  the backend to check (or wait for) actual confirmed payment/ledger state.
- This applies symmetrically to the manual-slip path: the frontend upload-success
  confirmation ("your slip was submitted") is not activation evidence either —
  `SUBMITTED` and `UNDER_REVIEW` are the only reachable states from a student-facing
  action, and neither activates enrollment (see `payment-ledger.md` §3).

## 4. Allowed activation paths

1. **Verified backend payment confirmation.** The gateway path: a
   server-to-server, signature/authenticity-verified callback/webhook (owned by
   `integration-management`) that results in a `Payment` row transitioning to
   `CONFIRMED` inside the backend. Activation is driven off that persisted state
   change, not off anything the browser reports.
2. **Approved manual payment evidence.** The slip path: an authorized reviewer/staff
   action transitions a slip `UNDER_REVIEW -> APPROVED`, after the mandatory
   duplicate-reference and duplicate-image-hash gates have passed (or been validly,
   audit-logged overridden) — see `payment-ledger.md` §4. That `APPROVED` transition
   is the activation trigger, performed by a human with recorded identity and
   timestamp, never a student self-service action.

No third activation path may be introduced without going through the "new way for
enrollment to activate" approval gate in `.claude/rules/payments.md` §7.

## 5. Atomic transaction requirement

Per `.claude/rules/backend.md`'s transaction-boundary rules: **verified payment
confirmation and enrollment activation happen together, inside one transaction.**

- A confirmed payment must never leave enrollment unactivated (no window where the
  payment is `CONFIRMED` but the student still can't access the course due to a
  crash/partial-failure between the two writes).
- Enrollment must never activate without a persisted, confirmed payment record (no
  window where enrollment looks active but the payment write later rolls back).
- This transaction spans `payment-management` confirming the payment and
  `enrollment-management` activating access — both are part of one business
  operation, not a request/response cycle and not two independently-committed steps
  stitched together by application-level "best effort" sequencing.
- The transaction does **not** span the outbound call to the gateway itself. The
  sequence is: gateway calls back with confirmation -> backend persists `CONFIRMED`
  payment + activates enrollment in one local transaction -> commit. Nothing here
  holds a DB transaction open across a live network call to an external system.
- The same atomicity expectation applies to the manual-slip path: the slip's
  `APPROVED` transition and the resulting enrollment activation happen in one
  transaction, not as an approval write followed by a separate, later activation
  job that could fail silently.

## 6. Smart Expiry / Access Control model

Source requirements module 18 ("Smart Expiry / Access Control") defines the access
lifecycle beyond initial activation. This is owned jointly by `enrollment-management`
(course/session-level access and payment-based expiry) and `content-management` /
`video-access-management` (material/video-level expiry enforcement at fetch time),
consistent with the domain boundaries in `.claude/rules/architecture.md`.

**Status: course-level expiry + the reactivation-request/admin-approval core have
shipped (MVP-012, `com.lms.enrollmentmanagement`), per
`docs/adr/ADR-013-enrollment-lineage-and-reactivation-order-gate.md` and
`docs/api/enrollment-management.md`.** Everything else in this section (session/
material/video expiry, the rules engine, grace period, reminders, bulk extension,
student-specific override) remains the Phase 2 aspiration described below — not yet
built. Each bullet is tagged with its actual status so this section doesn't read as
"all required, all pending" once some of it has shipped.

Required elements:

- **Course expiry — shipped (MVP-012).** A course-level access window
  (`course.access_duration_days`, read once at (re)activation time and snapshotted as
  `enrollment.access_expires_at` — never re-read later if the course's own column
  later changes) after which the enrollment is no longer considered active for
  content access purposes. Access currency is computed **live** on every read
  (`NOT superseded AND (access_expires_at IS NULL OR access_expires_at > now())`),
  never stored as an enum value on the row.
- **Session expiry — not yet built (Phase 2).** Access to a specific scheduled
  session (live class or its recording) can expire independently of the overall
  course access window.
- **Material expiry — not yet built (Phase 2).** Individual learning materials (per
  `content-management`) can carry their own expiry, set at upload/assignment time,
  independent of course expiry.
- **Video expiry — not yet built (Phase 2).** Video-specific access windows,
  enforced together with the signed-URL/playback-token mechanism described in
  `.claude/rules/security.md` ("Video & Session Protection") and
  `docs/architecture/video-content-security.md` — expiry here is enforced
  server-side/by the storage provider, never only by a frontend countdown.
  `content-management`/`video-access-management` still use interim, non-
  enrollment-based access checks today; MVP-012 exposes the read-only
  `EnrollmentAccessApi.resolveAccessState` those modules need to switch over, but
  does not itself perform that switch (tracked in
  `docs/requirements/open-decisions.md` §18).
- **Payment-based expiry — shipped (MVP-012), narrower than originally scoped.** The
  trigger for course expiry is the underlying payment's coverage period
  (`course.access_duration_days`) lapsing — a **read** of course configuration by
  `enrollment-management`, snapshotted at activation time, not a duplication of
  payment data into enrollment tables. Session/material/video expiry's own
  payment-based triggers remain Phase 2, tied to those features' own future build-out.
- **Reactivation request + admin approval — shipped (MVP-012), see §7 for the
  concrete mechanism.** A student whose access has expired can submit a reactivation
  request (`SUBMITTED` status); this does not reactivate anything by itself. A
  Tenant Admin — the only role holding `DomainArea.ACCESS_EXPIRY`/`APPROVE` in the
  already-shipped RBAC matrix — reviews and approves or rejects it
  (one-directional, no re-open). Approval is **unconditionally required for every
  tenant** in this MVP; there is no tenant-configurable "skip approval" toggle
  (tracked as still-open in `docs/requirements/open-decisions.md` §18). Actual
  reactivation follows the same rule as initial activation (§2–§4): it requires a
  **new** confirmed payment or approved manual evidence, tied to a **new**
  payment/order (per `payment-ledger.md` §7).
- **Expiry rules engine — not yet built (Phase 2).** Expiry is not evaluated by a
  rules engine at all in this MVP; only `course.access_duration_days` is read, no
  student/course/tenant/plan override layer exists. When built, it would need to
  account for course-level, tenant-level, and student-level configuration
  (mirroring the override-precedence pattern already established for device limits
  in `.claude/rules/security.md`: most-specific wins) — the exact precedence order
  is not yet fixed by current material, flagged in §9.
- **Grace period — not yet built (Phase 2).** This MVP implements a hard cutover at
  `access_expires_at` with no grace period at all — not "grace period = 0 days" as a
  ratified value, simply "no grace period feature exists yet." A configurable buffer
  after nominal expiry, if built, would need its exact duration decided (see §9).
- **Auto reminder before expiry — not yet built (Phase 2).** Requires
  `notification-management` event wiring; no reminder notification exists today.
- **Bulk expiry extension — not yet built (Phase 2).** No admin action exists
  anywhere in this module to extend an individual or bulk set of enrollments without
  a new payment. When built, this would be a state-changing admin action and should
  be audit-logged (actor, tenant, scope of extension, before/after where
  applicable) per `.claude/rules/security.md`.
- **Student-specific override — not yet built (Phase 2).** An exception granted to
  an individual student (e.g., extended access without an additional payment) does
  not exist today. When built, it must also be authorized and audit-logged, and —
  per the override-precedence pattern — should sit at the most-specific end of
  whatever precedence order the expiry rules engine ultimately defines.

## 7. Expiry's interaction with append-only payment/ledger history

**Shipped mechanism (MVP-012).** `enrollment` is a **lineage of rows** per (tenant,
student, course), not a single row mutated in place — `uq_enrollment_tenant_student_course`
was replaced by a partial unique index scoped to "current" rows only
(`uq_enrollment_tenant_student_course_current ... WHERE superseded_at IS NULL`, `V22`).
A reactivation never touches the prior row's `activating_payment_id`/
`activating_slip_id`/`activated_at` — it writes a **new** row and sets exactly one new
column, `superseded_at`, on the old one. The new row's `reactivated_from_enrollment_id`
points back at the prior row, so the full lineage is queryable forever (mirrors
`ledger_entry`'s own `reverses_entry_id` reversal-references-original pattern).

- **Expiry is a state change/event on enrollment/access — it is never a mutation of
  the payment record that originally granted access, and never a mutation of
  `enrollment` itself either.** Access currency (`ACTIVE`/`EXPIRED`/`NEVER_ENROLLED`)
  is computed **live** at read time (`EnrollmentAccessApi.resolveAccessState`), never
  stored as an enum value on the row (a Postgres `CHECK` constraint can't reference
  `now()`, and a stored status would need a background job to stay in sync — the
  live-compute approach needs neither). The first time a live check observes an
  enrollment as expired, `enrollment-management` performs one idempotent, guarded
  insert into `enrollment_expiry_event` (`tenant_id`, `enrollment_id`, `event_type`
  — a single value, `'EXPIRED'`, at MVP) — a durable, append-only record of "this
  access lapsed on this date," distinct from and never overwriting
  `activating_payment_id`/`activating_slip_id`/`activated_at` or any payment/ledger
  row. This event write is **not** itself an audit-log entry — it is a
  system-detected state transition with no human actor, and `audit_log.actor_id` is
  `NOT NULL` FK'd to `tenant_user`, structurally incompatible with a system-initiated
  event; `enrollment_expiry_event` is the purpose-built home for this record instead.
- This mirrors the append-only principle in `docs/architecture/payment-ledger.md` §5
  and §7: the financial history ("this payment was confirmed on this date, for this
  amount") and the access history ("this student's access to this course expired on
  this date, this enrollment lineage row was superseded on this date") are two
  related but distinct append-only trails. Neither is allowed to overwrite the other.
- Bulk expiry extension and student-specific override (§6) remain unbuilt (Phase 2);
  when built, they must not be implemented by reaching back into payment/ledger data
  to "extend" a payment's effective date — the same constraint this section already
  states for the shipped reactivation mechanism below.
- **Reactivation** (shipped) always produces a **new** order/payment and, atomically
  with that payment/slip confirming, a **new** `enrollment` lineage row
  (`EnrollmentActivationService.reactivateFromConfirmedPayment`/
  `reactivateFromApprovedSlip`) — never a resurrection or date-extension of the
  original payment record's dates. The prior, now-superseded `enrollment` row and
  every payment/ledger row from the original purchase are provably untouched (only
  `superseded_at` differs on the prior row), so the full timeline (paid -> expired ->
  reactivation requested -> approved -> paid again -> reactivated) stays
  reconstructable from history alone.

## 8. Testing requirements (cross-reference)

Per `.claude/rules/testing.md`'s required-test matrix, any enrollment-activation
change ships with a test proving activation only occurs after a persisted, verified
payment/approval record exists — never from a request payload alone — and any
ledger/settlement-adjacent change touching this flow needs an idempotency test
(e.g., a duplicate webhook delivery or a duplicate approval action must not
double-activate enrollment or double-write ledger entries).

## 9. Open questions (business decisions not supplied by current material)

**None of the five items below were resolved by MVP-012.** Shipping course-level
expiry + the reactivation core required no answer to any of them — expiry is a hard
cutover with no grace period, no precedence engine exists (only
`course.access_duration_days` is read), reactivation always requires a full new
payment, bulk extension is simply out of scope (not "resolved to require no
second-approver"), and no reminder feature exists to have a timing decision at all.
They remain exactly as open as before. (Two related, narrower questions — whether
Finance Staff should also hold reactivation-approve, and whether approval should
ever become tenant-configurable — surfaced during MVP-012's implementation and are
tracked in `docs/requirements/open-decisions.md` §18, not repeated here since they
are about the shipped feature's approver model rather than these unbuilt-feature
questions.)

- Exact grace period length(s) — likely varies by tenant plan or course; no default
  duration is specified in current material.
- Exact expiry rules engine precedence order (student > course > tenant > plan, or
  a different order) — the device-limit precedence pattern is confirmed elsewhere,
  but expiry-specific precedence is not yet confirmed and should not be assumed
  identical without an explicit decision.
- Whether reactivation always requires a new full payment, or whether a prorated/
  partial reactivation payment is ever allowed — not specified.
- Whether bulk expiry extension requires additional approval beyond the acting
  admin/staff member's normal permission (e.g., a second-approver step for
  tenant-wide extensions) — not specified.
- Exact reminder timing (how many days before expiry the first/subsequent reminders
  fire) — not specified.

## Related

- `docs/architecture/payment-ledger.md`
- `docs/architecture/video-content-security.md`
- `docs/adr/ADR-003-centralized-payments-first.md`
