# ADR-032: Exam enrollment and payment-triggered exam auto-enrollment

- Status: Accepted (product owner, 2026-10-30, Klass Exam Module brief; decision D5 in
  `docs/specs/klass-exam-module.md` §4).
- Change-controlled areas touched: paid-entitlement activation rules (ADR-028) — a confirmed
  payment becomes a new trigger for an exam entitlement. Payment ledger rules: **not** touched.

## Context

Klass lets an MCQ/written exam "auto-enroll students on payment": students paying for the
selected months / modules / dates / installment amount / full fee are enrolled into the exam until it
ends (live reference, 2026-10-30). Our course enrollment is free and payment-independent (ADR-028);
exam enrollment must therefore be a separate concept.

## Decision

1. `exam_enrollment(tenant_id, exam_id, student_id, source, payment_id)` with sources `MANUAL`,
   `ENROLL_ALL`, `PAYMENT`; unique per `(tenant_id, exam_id, student_id)`. Only exam-enrolled
   students may start an exam. Course enrollment is never created, changed or cancelled by exams.
2. "Enroll all students" and "Auto-enroll on payment" are mutually exclusive per exam.
   Enroll-all is dynamic: active course participants are eligible until the exam ends.
3. Payment trigger: an `@TransactionalEventListener(AFTER_COMMIT)` on `PaymentConfirmedEvent`
   reads the confirmed payment's order lines (tenant from the event, never from client input) and,
   for each exam of that course with `auto_enroll_on_payment` and `now < scheduled_end` whose
   criteria match the line (billing month, module, session date, cumulative installment amount, or
   full fee), inserts `exam_enrollment(source=PAYMENT, payment_id)` with `ON CONFLICT DO NOTHING`.
   Replays and multi-course orders are therefore idempotent and only touch matching exams.
4. Free Cards qualify for month/module criteria (they are entitlements, ADR-030) but never count as
   cash toward amount thresholds.
5. No automatic backfill: payments confirmed before the exam was configured are enrolled only by an
   explicit staff "Sync payments" action, which runs the same matcher.
6. `PaymentRefundedEvent` (incl. chargebacks): a `PAYMENT`-sourced exam enrollment for that payment
   is removed only when the student has no attempt/result for the exam; otherwise it is kept and the
   academic record preserved. Nothing is written to payments or the ledger.

## Consequences

- Payment state is read-only input; ledger, payment and course-enrollment tables are unchanged.
- Exam access provenance is queryable (`source`, `payment_id`).
- Ex-post criteria edits do not retroactively remove enrollments.
