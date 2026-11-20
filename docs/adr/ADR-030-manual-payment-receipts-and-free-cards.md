# ADR-030: Staff manual payment receipts and Free Cards

- Status: Accepted (feature requested by the project owner in the 2026-10-21 implementation brief
  "Manual Payment and Free Card Issue"); decisions marked *ratify* in
  `docs/plans/klass-manual-payment-free-card.md` §3 await owner confirmation.
- Change-controlled areas touched: payment ledger rules (a new manual-receipt confirmation path),
  paid-entitlement activation rules (ADR-028: a staff-recorded receipt grants line entitlements; a
  Free Card grants complimentary coverage).

## Context

Klass lets staff record money received offline against a student's billing months and issue a
"Free Card" (complimentary class access). The platform had only gateway confirmation, approved
slips and the legacy course-wide staff grant (ADR-016).

## Decision

1. **Manual receipt** — an authorized staff member (`PAYMENTS_SLIPS/APPROVE`) records cash/bank
   transfer/card money actually received. The server builds a payment-composer line order for the
   validated student (same offers, facility fees, postage, automatic discount rules), confirms one
   `Payment` (`MANUAL-` reference) with the cash actually received, writes the existing
   `PAYMENT_CONFIRMED` ledger entry, the immutable `manual_payment_receipt` row and the line
   entitlements via `EntitlementGrantApi`, all in one transaction. No new ledger entry type.
   An explicit, reasoned staff adjustment may reduce the cash below the quoted total; it is recorded
   on the receipt, never as cash. Partial/over payment is refused. Corrections use the existing
   refund workflow.
2. **Free Card** — an authorized staff member (`FREE_CARDS/CREATE_EDIT`, a new area not granted to
   payment recorders) grants a dated, reasoned, revocable complimentary pass for one MONTHLY course.
   It is a separate `free_card` row read by the existing lesson-access evaluator as MONTHLY coverage
   while effective. It creates no order, payment, ledger, settlement or postage row; a nominal value
   is informational only. Revocation (`FREE_CARDS/DELETE`) never touches payment-backed coverage.
3. Enrollment stays free and payment-independent (ADR-028); a Free Card may create the standard
   participation (`STAFF_GRANT`) when absent, audited separately.

## Consequences

- Provenance: payment-backed access → `content_entitlement` → confirmed payment; complimentary
  access → `free_card`. Each lapses independently.
- Not decided here (open): HALF cards (a pricing rule), "Temporary Credit", registration/enrollment
  fee charging, and whether staff refunds revoke access (open decision O-2, unchanged).
