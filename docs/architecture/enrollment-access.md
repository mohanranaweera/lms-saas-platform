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

Required elements:

- **Course expiry** — a course-level access window (e.g., "access ends N days/months
  after activation" as configured per course/pricing plan) after which the
  enrollment is no longer considered active for content access purposes.
- **Session expiry** — access to a specific scheduled session (live class or its
  recording) can expire independently of the overall course access window.
- **Material expiry** — individual learning materials (per `content-management`) can
  carry their own expiry, set at upload/assignment time, independent of course
  expiry.
- **Video expiry** — video-specific access windows, enforced together with the
  signed-URL/playback-token mechanism described in `.claude/rules/security.md`
  ("Video & Session Protection") and `docs/architecture/video-content-security.md` —
  expiry here is enforced server-side/by the storage provider, never only by a
  frontend countdown.
- **Payment-based expiry** — the trigger for course/session/material/video expiry is
  typically the underlying payment's coverage period lapsing (e.g., the period the
  original confirmed payment or approved slip was recorded as covering). This is a
  **read** of payment/ledger data by `enrollment-management` (via `api` interfaces),
  not a duplication of payment data into enrollment tables.
- **Reactivation request + admin approval** — a student whose access has expired can
  submit a reactivation request; this does not reactivate anything by itself. Actual
  reactivation follows the same rule as initial activation (§2–§4): it requires a
  **new** confirmed payment or approved manual evidence, tied to a **new**
  payment/order (per `payment-ledger.md` §7), and (per source requirements) an admin
  approval step in the reactivation workflow.
- **Expiry rules engine** — expiry is not a single hardcoded rule; it is evaluated
  by a rules engine that can account for course-level, tenant-level, and
  student-level configuration (mirroring the override-precedence pattern already
  established for device limits in `.claude/rules/security.md`: most-specific wins).
  The exact precedence order and rule shape for expiry specifically is not yet fixed
  by current material — flagged in §9.
- **Grace period** — a configurable buffer after nominal expiry during which access
  is retained or degraded rather than cut off instantly. Exact duration is a business
  decision (see §9), not something to default to an invented number.
- **Auto reminder before expiry** — a notification (via `notification-management`,
  triggered asynchronously/event-driven per `.claude/rules/architecture.md`) sent
  ahead of the expiry date, not implemented as a synchronous check on every request.
- **Bulk expiry extension** — an admin/staff action that extends access for many
  students/enrollments at once (e.g., a tenant-wide grace extension). This is a
  state-changing admin action and should be audit-logged (actor, tenant, scope of
  extension, before/after where applicable) per `.claude/rules/security.md`.
- **Student-specific override** — an exception granted to an individual student
  (e.g., extended access without an additional payment), which must also be
  authorized and audit-logged, and — per the override-precedence pattern — should sit
  at the most-specific end of whatever precedence order the expiry rules engine
  ultimately defines.

## 7. Expiry's interaction with append-only payment/ledger history

- **Expiry is a state change/event on enrollment/access — it is never a mutation of
  the payment record that originally granted access.** When a course/session/
  material/video expires, `enrollment-management` records that as its own event
  (e.g., an access-state transition or an expiry event row) referencing the
  enrollment, not by editing or deleting the originating `Payment` or ledger entry.
- This mirrors the append-only principle in `docs/architecture/payment-ledger.md` §5
  and §7: the financial history ("this payment was confirmed on this date, for this
  amount") and the access history ("this student's access to this course expired on
  this date") are two related but distinct append-only trails. Neither is allowed to
  overwrite the other.
- Bulk expiry extension and student-specific override (§6) change **access** state
  going forward; they must not be implemented by reaching back into payment/ledger
  data to "extend" a payment's effective date. If an extension is granted without a
  new payment (e.g., a goodwill extension), it should be modeled as its own
  access-level record/event with its own audit trail — not disguised as a payment
  change.
- Reactivation after expiry always produces a **new** payment/order and a **new**
  ledger entry (per `payment-ledger.md` §7); the prior, now-expired payment record is
  left exactly as it was, so the full timeline (paid -> expired -> reactivated) stays
  reconstructable from history alone.

## 8. Testing requirements (cross-reference)

Per `.claude/rules/testing.md`'s required-test matrix, any enrollment-activation
change ships with a test proving activation only occurs after a persisted, verified
payment/approval record exists — never from a request payload alone — and any
ledger/settlement-adjacent change touching this flow needs an idempotency test
(e.g., a duplicate webhook delivery or a duplicate approval action must not
double-activate enrollment or double-write ledger entries).

## 9. Open questions (business decisions not supplied by current material)

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
