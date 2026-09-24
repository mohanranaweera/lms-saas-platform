# ADR-016: Staff-Granted Enrollment and Staff-Initiated Revocation

## Status

**Accepted (2026-09-23)**, by the project owner, via `AskUserQuestion` during Wave 3
("Student and Teacher operational profiles") backend implementation planning - recorded
here per the Wave 3 fix-pass (security/payment-ledger/architecture review) finding that
every javadoc/comment implementing these two decisions cited "user-approved 2026-09-23"
with no actual ADR file backing that citation, unlike every other change-controlled
decision in this codebase (ADR-010, ADR-012, ADR-013, ADR-015 - all cited by number at
their own call sites). This document is that missing record, not a retroactive
rubber-stamp of code shipped without approval - both decisions below were surfaced to,
and approved by, the product owner *before* the corresponding service/entity code was
written, exactly as `.claude/rules/payments.md` §7 requires.

## Context

`Enrollment` rows are structurally locked to a fixed, small set of approved write call
sites on `EnrollmentActivationApi` (`activateFromConfirmedPayment`,
`activateFromApprovedSlip`, and their reactivation/consolidated counterparts) - see that
interface's own javadoc. This is deliberate: `.claude/rules/payments.md` §1 requires
"enrollment activation code must read payment/ledger state, never order state," and §7
requires stopping to ask for explicit approval before either (a) introducing *any* new
way for enrollment to activate, or (b) any mutation/deletion path touching a ledger or
terminal-payment row.

Wave 3 (`docs/parity/waves/wave-03-plan.md`, PAR-03-05) requires two new staff-facing
Student actions that each squarely hit one of those two payments.md §7 triggers:

1. **"Enroll student in course"** - a Tenant Admin/Student Support action letting staff
   manually grant a student access to a course (e.g. a scholarship, a goodwill grant, a
   payment collected out-of-band) without the student completing a normal checkout. This
   is, structurally, "a new way for enrollment to activate" - it cannot reuse
   `activateFromConfirmedPayment`/`activateFromApprovedSlip` as-is, since there is
   neither a real gateway-confirmed payment nor a reviewed `payment_slip` behind it.
2. **"Revoke enrollment"** - a Tenant Admin/Student Support action letting staff end a
   student's active access to a course (e.g. a refund processed outside the normal
   flow, a policy violation, an accidental grant). This is a mutation of an otherwise
   structurally-append-only-except-`supersede()` `enrollment` row - squarely payments.md
   §7's "any mutation... touching ledger or terminal payment rows" trigger area, even
   though (per the decision below) it never actually touches a ledger or payment row.

Neither of these could be implemented without stopping to ask first. Both were surfaced
to the product owner as an explicit decision request (via `AskUserQuestion`) before any
service/entity code was written, alongside an explicit "defer to a later wave instead"
alternative for each (see "Alternatives considered" below) - the product owner chose to
approve both, with the specific, narrow scope recorded in the Decision section.

## Decision

### 1. Staff "enroll student in course" creates a real Order + Payment(CONFIRMED,
   STAFF_GRANTED evidence), funneled through a new, 5th approved
   `EnrollmentActivationApi` call site

A staff-initiated enroll is **not** a bypass of the payment/ledger trail - it is treated
as a new, explicit *kind* of manual evidence, the same shape as an approved payment
slip, not a shortcut around one:

- `payment-management`'s `ManualEnrollmentService` (implementing the new
  `ManualEnrollmentApi#grantEnrollment` contract) creates a real `student_order` row and
  a real `payment` row for the course's genuinely resolved checkout amount (never a
  synthesized `$0`, unless the course is genuinely priced `FREE` - mirrors ADR-015's
  precedent exactly), confirms that payment (`Payment#confirm`), and writes a real
  `PAYMENT_CONFIRMED` `ledger_entry` via the same `LedgerEntryApi#recordPaymentConfirmed`
  call every other CONFIRMED-payment path uses (`PaymentConfirmationService`,
  `OrderService#activateFreeCheckout`) - never a bespoke ledger-write code path.
- The evidence trail distinguishing a staff-granted payment from a gateway/slip-confirmed
  one is a synthesized `gateway_reference` of `"STAFF_GRANTED-" + paymentId` (mirroring
  `OrderService#activateFreeCheckout`'s own `"FREE-" + paymentId` precedent - see
  `V47__add_enrollment_staff_granted_evidence.sql`'s header comment for why this mirrors
  that precedent rather than the wave-03 plan's originally-drafted "extend payment's
  method/evidence enum" wording, which does not match the actual, already-shared
  `payment` schema), plus a new, mandatory `payment.staff_grant_reason` column.
- Enrollment activation itself happens through a new, explicit, narrowly-scoped 5th call
  site on `EnrollmentActivationApi`: `fromApprovedManualEvidence(paymentId, orderId,
  studentId, courseId)`. It internally delegates to the SAME
  `activateOrReactivateFromConfirmedPayment` mechanics every other confirmed-payment path
  already uses (same independent `PaymentStatusApi` re-verification, same `Enrollment`
  factories) - it exists as its own named call site purely so the "staff-granted"
  activation path stays structurally distinct and auditable in code, never so a caller
  can skip the independent re-verification every other evidence type already gets.
- A mandatory, non-blank `reason` is required before any row is written, and the write
  is audit-logged (`enrollment.manually_granted`, inside the same transaction as the
  Order/Payment/ledger/Enrollment writes - see `ManualEnrollmentService`).

### 2. Staff "revoke enrollment" is a new, narrow `EnrollmentActivationApi#revoke` call
   site using the EXISTING `supersede()` mutation - no new `EnrollmentStatus` value, no
   ledger/payment write

Revocation does **not** introduce a new activation-evidence type, a new `enrollment`
status value, or any ledger/payment mutation:

- `EnrollmentActivationService#revoke(enrollmentId, reason)` loads the CURRENT
  (`supersededAt IS NULL`) row for the caller's own tenant, requires a mandatory,
  non-blank `reason`, and calls `Enrollment#revoke(revokedBy, reason)` - which itself
  calls nothing but the entity's own pre-existing `supersede()` mutation (the ONLY
  legal in-place mutation this append-mostly aggregate ever allowed, already used by the
  reactivation lineage model, ADR-013), then additionally records the new
  `revoked_at`/`revoked_by`/`revoke_reason` evidence columns
  (`V47__add_enrollment_staff_granted_evidence.sql`) in that same call - never touching
  the immutable activation columns (`activating_payment_id`/`activating_slip_id`/
  `activated_at`).
- No new `EnrollmentStatus` enum value is introduced - `status` still only ever answers
  "was this row's activation valid," exactly as `Enrollment`'s own class javadoc already
  documents; access currency (now including "revoked") is still computed live via
  `isCurrentlyActive`/lineage-and-`supersededAt` inspection, never persisted as a new
  status.
- No ledger entry and no payment mutation is written for a revoke - the payment that
  originally funded the enrollment (if any) is untouched, per `.claude/rules/payments.md`
  §1's "a Payment row is immutable once it reaches a terminal state" and §4's ledger
  append-only rules. If a refund is later warranted, that remains a fully separate,
  already-existing `payment-management` refund flow (its own ledger entry), not something
  this decision's `revoke()` call performs implicitly or automatically.
- Reactivation after a revoke requires no new code path: a revoked enrollment's access
  state resolves the same way a naturally expired one does (not current/not
  access-active), so the existing reactivation-request flow (ADR-013) already covers it.
- Declared on `EnrollmentActivationApi` (not left as a bare method on the service
  implementation only) purely so `EnrollmentController` - its one and only real caller,
  same module - can depend on the stable interface type, keeping this codebase's existing
  `@MockitoBean EnrollmentActivationApi` test-override pattern working unmodified; this is
  not an invitation for a cross-module caller to use it.

## Consequences

**Positive**

- Both new staff actions produce a genuine, ledger/payment-consistent trail (enroll) or a
  minimal, narrowly-scoped mutation with no financial side effect (revoke) - neither
  silently bypasses the payment/ledger integrity rules `.claude/rules/payments.md`
  exists to enforce.
- `Enrollment`'s "structurally locked to a small, enumerable set of write call sites"
  invariant is preserved and extended in a disciplined, auditable way (a 5th named call
  site, not a generic "admin override" backdoor).
- Every write from both actions carries a mandatory, human-readable `reason`,
  audit-logged in the same transaction as the state change, per
  `.claude/rules/security.md`'s audit-logging requirements.

**Negative / trade-offs accepted**

- A staff-granted enrollment's "payment" is not a real charge - a reader of the `payment`
  table must know to check `gateway_reference` for the `STAFF_GRANTED-` prefix (or the
  new `staff_grant_reason` column being non-null) to distinguish it from a genuine
  gateway/slip-confirmed payment, mirroring the exact same trade-off ADR-015 already
  accepted for FREE-course `$0` payments (distinguished by `gateway_reference`'s
  `FREE-` prefix). This was judged acceptable for the same reason ADR-015's was: no
  product requirement was ever surfaced for a dedicated evidence-type enum column, and
  the existing `gateway_reference`-prefix convention is already load-bearing elsewhere.
- Revocation has no automatic refund step - if staff revoke an enrollment that was
  genuinely paid for, a separate, manual refund decision/action is still required. This
  was judged the safer default: automatically inferring "revoke implies refund" would be
  a financial-ledger-affecting behavior this ADR's own scope explicitly did not approve.

## Alternatives considered

- **Defer both actions to a later wave, once a more general "manual evidence"/"access
  override" framework is designed** - offered to the product owner alongside each
  decision above and not chosen; Wave 3's own scope (PAR-03-05, staff Student actions)
  explicitly requires enroll/revoke as part of this wave's Student Detail Actions area,
  and deferring would leave staff with no way to grant/revoke access outside the normal
  checkout flow for the whole of Wave 3 and any wave before the deferred framework lands.
- **Treat staff-granted enrollment as a bypass (write the `enrollment` row directly, no
  Order/Payment/ledger row at all)** - rejected: this is exactly the "new way for
  enrollment to activate" that skips the payment/ledger trail `.claude/rules/payments.md`
  §7 flags as requiring explicit approval, and would leave Payment History/the Payment
  Dashboard unable to show "paid" for a staff-granted enrollment (the same "paid but no
  ledger entry" bug class ADR-015 closed for FREE courses, per §2).
- **Model revocation as a new `EnrollmentStatus.REVOKED` value instead of reusing
  `supersede()`** - rejected: `status` already has a narrow, single meaning ("was this
  row's activation valid," per `Enrollment`'s own class javadoc) that predates this
  decision; overloading it to also encode "no longer current because of a specific
  reason" would conflate lineage-currency (already `supersededAt`'s job) with activation
  validity, and would require touching every existing `isCurrentlyActive`/access-state
  call site to understand a new value. Reusing `supersede()` plus three new narrow
  evidence columns keeps the existing, already-reviewed lineage model exactly as-is.
- **Have revocation automatically write a refund ledger entry** - rejected: whether a
  given revoked enrollment warrants a refund is a business/financial judgment call this
  action's caller (a support/admin actor) is better positioned to make explicitly, via
  the existing separate refund flow, than to have this action infer silently; an
  automatic refund would also be exactly the kind of "mutation touching ledger... rows"
  payments.md §7 requires a *separate* explicit approval for for beyond what this ADR's
  narrow scope covers.

## Required follow-up if accepted

- Any future change that has staff-granted enrollment write anything other than a
  `PAYMENT_CONFIRMED` ledger entry (e.g. a new ledger entry type, or a settlement-visible
  marker distinguishing it from a real payment) is a new, separate decision - this ADR
  does not add one.
- Any future change that has revocation automatically trigger a refund, or that
  introduces a new `EnrollmentStatus` value, is likewise a new, separate decision
  requiring its own stop-and-ask per `.claude/rules/payments.md` §7 - not an extension of
  this ADR's approval.

## Related

- `docs/parity/waves/wave-03-plan.md` - Wave 3 ("Student and Teacher operational
  profiles") module plan, the wave context both decisions were approved within.
- `.claude/rules/payments.md` §1, §2, §4, §7
- `ADR-013-enrollment-lineage-and-reactivation-order-gate.md` (the lineage/`supersede()`
  model this decision's revoke path reuses unchanged)
- `ADR-015-free-course-zero-amount-payment-and-ledger.md` (the `gateway_reference`-prefix
  evidence-distinguishing precedent this decision's enroll path mirrors)
- `V47__add_enrollment_staff_granted_evidence.sql`
- `com.lms.enrollmentmanagement.api.EnrollmentActivationApi` (`fromApprovedManualEvidence`,
  `revoke`)
- `com.lms.enrollmentmanagement.domain.Enrollment` (`revoke`)
- `com.lms.enrollmentmanagement.service.EnrollmentActivationService` (`fromApprovedManualEvidence`,
  `revoke`)
- `com.lms.paymentmanagement.api.ManualEnrollmentApi`,
  `com.lms.paymentmanagement.order.service.ManualEnrollmentService`
