# ADR-028: Free Enrollment and Payment-Gated Lesson Entitlements

## Status

**Accepted (2026-10-12)**, by the project owner, as an explicit instruction in
`docs/specs/Klass_Enrollment_Payment.md` ("Authoritative enrollment correction") and
`docs/specs/Klass_Course_Management.md`, and restated in the implementation brief for the
Klass course/enrollment parity work. This changes the change-controlled "enrollment
activation rules" area named in root `CLAUDE.md`; this ADR is the required record of that
approval. No other change-controlled area is altered.

## Context

Since MVP-012 (ADR-010, ADR-012, ADR-013, ADR-015, ADR-016) an `enrollment` row has meant
"paid access to a course". It could be created only by a confirmed gateway payment, an
approved manual slip, a genuinely free course's zero-amount payment (ADR-015) or a
staff grant (ADR-016), and it carried a FK trail to that evidence. Students therefore had
to pay before they could even open a course.

The owner's business model (Klass parity) is different: any registered student can join an
available course for free, browse its structure, and pay per billing month, module or
lesson to unlock the protected content. The project guidance (`CLAUDE.md`,
`.claude/rules/payments.md`, `.claude/rules/backend.md`, agent definitions, skills and
`docs/requirements/specifications/09-enrollments.md`) said the opposite and had to be
corrected before implementation.

## Decision

> Registered students may enroll freely through an authenticated, authorized backend
> enrollment operation. Enrollment never depends on payment. Paid lesson/session
> entitlements are granted only after verified backend payment confirmation or approved
> manual payment evidence, never from a frontend success page. Payment
> pending/failure/rejection does not cancel enrollment.

Consequences:

1. **Enrollment and entitlement are separate records and separate decisions.** Enrollment
   grants course participation and navigation (safe metadata: module/lesson titles,
   schedules, prices, payment prompts). A paid entitlement grants access to protected leaf
   resources (video tokens, stream manifests, document downloads, meeting join links,
   external-player handoff).
2. **Free enrollment is idempotent and race-safe**: one row per tenant + student + course,
   created with the authenticated student id and the authenticated tenant only, subject to
   course availability (published, not archived, registration enabled). It never creates an
   order, payment, ledger entry or charge, and has no approval step.
3. **Paid entitlements keep every existing financial control.** They are granted in the
   same transaction as the verified gateway confirmation (ADR-017 rules unchanged) or the
   authorized slip approval (duplicate/suspicious checks unchanged), idempotently, with a
   NOT NULL FK trail to the payment that justified them. Reversals follow the existing policy
   (`.claude/rules/payments.md` §9): a chargeback revokes the paid access its payment granted;
   staff refunds keep today's behaviour. Neither ever cancels the free enrollment or mutates
   ledger history. Any new refund-driven revocation rule needs its own approval.
4. **Leaf payment policy decides access**: `FREE` lessons need no payment; `PAID` lessons
   need their own lesson entitlement. A `COVERED_BY_CLASS_FEES` session on a `MONTHLY` course
   receives its billing period (course + year + month) server-side from `startsAt` in the
   tenant's configured timezone (UTC fallback), not from a frontend billing-month field. The
   stored derived period is then the entitlement key; non-Monthly or non-covered sessions carry
   no month association. Existing historical rows are not backfilled or rewritten. One
   server-side evaluator answers for content rows, live schedule and every resource launch.
5. **Existing data is preserved.** Every pre-existing payment-activated / staff-granted
   enrollment keeps its rows, its payment FK and its access window, and continues to grant
   the access it already granted (treated as legacy course-wide paid access). No enrollment,
   order, payment, slip, ledger or audit row is deleted or rewritten.
6. **Enrollment fee** (course creation form) is retained as informational metadata only in
   this mode: it is never charged and never gates enrollment.

## 2026-10-19 approved refinement

The owner approved removing manual Billing month configuration and deriving it from the
session's start instant in the tenant timezone for Monthly/Covered sessions. This supersedes
the original "not the lesson's start date" sentence only for new and edited Monthly/Covered
sessions. The API retains billing fields for compatibility but ignores client values. Prior
session rows, orders, entitlements, payments and ledger history are not rewritten; verified
payment/manual approval remains the only paid-entitlement activation path.

## Not changed

Payment verification (ADR-017 notification verification, amount/currency/merchant checks),
manual slip state machine and duplicate checks, ledger append-only rules (ADR-010 entry
types are not changed), financial-history preservation, tenant isolation (ADR-002/ADR-006),
authentication (ADR-007) and production deployment controls.

## Mechanism

The concrete schema, API and evaluator design is recorded in
`docs/plans/klass-enrollment-payment-parity.md` and
`docs/specs/klass-enrollment-payment-workflows.md`, which are kept in sync with the code.
