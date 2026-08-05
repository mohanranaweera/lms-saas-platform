---
name: payment-ledger-review
description: Use to review any change touching orders, payments, ledger entries, refunds, settlements, or enrollment activation against this project's payment roadmap and ledger rules. Read-only review.
---

# Payment Ledger Review

## When to use

Use before any module touching payments, orders, ledger entries, refunds, settlements, or enrollment activation is marked done. Applies to both `backend/` (ledger logic, webhooks, settlement jobs) and `frontend/` (checkout/success flows).

## Scope boundary

Read-only: this skill reviews and reports, it does not edit code. Hand fixes back to `implement-backend` or `implement-frontend`.

## Checklist

- [ ] Enrollment activates only after verified backend payment confirmation or approved manual payment evidence — confirm no code path activates enrollment directly from a frontend success page
- [ ] The change matches the current payment roadmap stage (platform-centralized collection; settlements/tenant accounts/split payments are staged in per `CLAUDE.md`, not assumed all at once)
- [ ] Financial history is never deleted — refunds, corrections, and reversals are new ledger entries, not mutations or deletions of existing rows
- [ ] Every payment-related table/query enforces tenant isolation like any other tenant-owned data
- [ ] Payment ledger rules and enrollment activation rules are change-controlled — flag any change to either instead of approving it silently
- [ ] Tests exist for the payment/ledger paths reviewed, including failure and idempotency cases (e.g. duplicate webhook delivery)
- [ ] No real payment credentials, real gateway accounts, or production transactions were used in review or testing
