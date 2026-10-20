# ADR-029: Per-Course Revenue Allocation for Multi-Course Orders

## Status

**Accepted (2026-10-19)** by the project owner, answering the payment-ledger review of the
multi-course checkout (`docs/plans/multi-course-checkout-fees-postage.md`). This changes the
change-controlled "payment ledger rules" / settlement area (`.claude/rules/payments.md` §5)
only as described below. Ledger entry types, the one-`PAYMENT_CONFIRMED`-per-payment rule,
enrollment and paid-entitlement activation (ADR-028) are unchanged.

## Context

A payment-composer order may now hold lines from several courses, so `student_order.course_id`
is NULL for such orders (V90). Revenue entries, teacher settlements, the course payment summary
and course-based notification audiences attribute a ledger entry to `student_order.course_id`,
so a multi-course payment reached no teacher's gross (teachers silently underpaid). Course
postage (V90) is a pass-through delivery cost but, for a single-course order, was inside the
ledger amount that settlement sums into teacher gross.

## Decision

1. **Allocation, not new ledger rows.** A ledger entry stays one row. For line orders, revenue
   consumers derive per-course *allocations* of that entry from the order's immutable snapshots:
   course share = sum of that course's `student_order_line.line_total` (unit + line facility fee
   - discount). Facility-fee treatment is the status quo (included, as for single-course line
   orders today); O-1 remains open and may change it later.
2. **Postage excluded from teacher gross.** `student_order_course_charge.postage_amount` is never
   allocated to a course for settlement; it is institute revenue (shown unattributed).
3. **Refunds / chargebacks** are allocated pro rata to the same course shares of the original
   order; the rounding remainder (2 dp) goes to the largest share (ties: lowest course id), so
   allocations always sum exactly to the attributable amount.
4. **Legacy (line-less) orders** keep a single allocation of the full amount to
   `student_order.course_id`.
5. **Settlement idempotency** moves from one item per ledger entry to one item per
   (ledger entry, course): `teacher_settlement_item` unique key becomes
   `(tenant_id, ledger_entry_id, course_id)` (new migration V91). A course's allocation is still
   settled at most once across all runs; re-running a period never double-pays.
6. Historical settlements are never recomputed. Entries already settled stay settled; payments of
   multi-course orders made before this change are picked up by the next run because they have no
   settlement item yet.
7. Finance totals and payment counts are computed per ledger entry (never per allocation) so
   splitting never inflates them.

## Consequences

- Teachers receive their course's share of multi-course payments; postage no longer inflates
  teacher gross for new settlement runs.
- Course payment summary / audiences include orders that have a line for the course, using the
  course's share as amount.
- Adds a derived, reproducible allocation step; inputs are immutable snapshots, so allocations are
  stable after rate/config changes.
