# Payment, Ledger & Settlement Rules

Applies to any work touching: orders, payments, payment slips, ledger entries,
enrollments/activation, settlements, commissions, refunds, or payment expiry.

These rules are concrete implementations of the change-controlled areas defined
in root `CLAUDE.md`. If a task requires deviating from any rule below, stop and
request explicit approval before writing code.

## 1. Orders & Payments (Phase 1)

- Every `Order` and every `Payment` row must carry `tenant_id`, resolved from
  the trusted authenticated context — never from a request body or query param.
- An `Order` and its resulting `Payment(s)` must never be mixed across tenants,
  even for platform-admin views. Cross-tenant aggregation is a reporting-layer
  concern, not a query-layer default.
- A `Payment` row is immutable once it reaches a terminal state
  (`CONFIRMED`, `REJECTED`, `REFUNDED`). Corrections happen via new rows
  (see §3), never `UPDATE` on a terminal payment.
- Paid-entitlement activation code (lesson/session/period access, and legacy
  payment-activated course access) must read payment/ledger state, never order
  state. An `Order` being `PLACED` or `PENDING` is not activation evidence.
  Enrollment itself is free and payment-independent (ADR-028): it is created by
  an authenticated backend enrollment operation and is never cancelled by a
  pending, failed, rejected or expired payment.

## 2. Manual Payment Slip Workflow

Manual slip approval is a distinct state machine from automated gateway
payments. Do not collapse it into a generic "payment status" enum.

- States: `SUBMITTED -> UNDER_REVIEW -> APPROVED | REJECTED`.
- Transitions are one-directional. There is no `APPROVED -> SUBMITTED` or
  silent re-open; a reversal of an approved slip is a new ledger entry
  (refund/correction), not a state rollback on the slip.
- `SUBMITTED` slips must never grant a paid entitlement by themselves.
  Entitlement activation only follows the `APPROVED` transition, performed by an
  authorized reviewer/staff action, recorded with reviewer identity and
  timestamp.
- Payment history and the admin payment dashboard must be derived from
  ledger entries + slip state, not from the order or upload record. If a
  screen shows "paid" but no corresponding ledger entry exists, that is a bug.

## 3. Payment Slip Intelligence Module

- Duplicate reference number check and duplicate image hash check are
  mandatory gates that MUST run and MUST pass (or be explicitly overridden,
  see below) before a slip can transition to `APPROVED`. Do not allow an
  approval code path that skips these checks.
- OCR reference extraction feeding the duplicate-reference check must be
  scoped per tenant — a reference number colliding across two different
  tenants is not a duplicate.
- A flagged duplicate or suspicious slip must never be auto-rejected without
  a human reviewer in the loop. Auto-flagging is allowed; auto-rejection
  without human review is not.
- Any manual override of a duplicate/suspicious flag (i.e., a reviewer
  approves a slip despite a flag) MUST write an audit log entry containing
  at minimum: reviewer identity, tenant, slip/reference id, the flag(s)
  overridden, a reason, and a timestamp. An override with no recorded reason
  is not a valid override — reject the change.
- Suspicious-slip flags are additive metadata on the slip/review record.
  Never clear or delete a prior flag when a new check runs — if checks are
  re-run, add a new flag/result record instead of overwriting the old one.

## 4. Ledger Entry Rules (concrete)

- A ledger entry is append-only. Refunds, corrections, and reversals are
  always new entries (with an appropriate sign/type), never `UPDATE` or
  `DELETE` on existing ledger rows. This applies to migrations and admin
  tooling too — no "fix the ledger row" scripts.
- Every ledger entry must carry a traceable link to: the tenant it belongs
  to, and the order/payment (or settlement run, for phase 2) that produced
  it. An orphaned ledger entry with no source reference is invalid.
- A refund/reversal entry must reference the original entry it reverses
  (e.g., `reverses_entry_id`), so the chain is queryable, but the original
  entry itself stays untouched.
- Ledger entry types and their effect on enrollment/settlement state are
  part of the change-controlled "payment ledger rules" — do not add, remove,
  or change the meaning of a ledger entry type without explicit approval.

## 5. Settlement Rules (Phase 2 — once reached)

- Settlement calculation must be idempotent: re-running a settlement job for
  a period/tenant that was already settled must not create duplicate payout
  ledger entries or double-pay. Guard with a uniqueness constraint on
  (tenant_id, settlement_period, run marker), not just application logic.
- Commission and gateway-fee amounts must be computed and stored per
  settlement run at the rate/config in effect at that time. Never recompute
  a historical settlement's commission from current rates — historical
  settlement records must remain reproducible/stable even after rate config
  changes.
- If a settlement needs correction after payout, issue a new adjustment
  settlement entry referencing the original run; do not mutate the original
  settlement's stored figures.

## 6. Payment Expiry & Reactivation

- Expiry processing must never delete or mutate prior payment/ledger
  history. Expiry is a state change on enrollment/access, recorded as its
  own event — the payment record that originally granted access stays as-is.
- Reactivation is always a new payment/order (and new ledger entry) with a
  link back to the prior enrollment/course context. Never resurrect or
  extend the old payment record's dates to simulate reactivation.

## 7. When in doubt

If a task would require any of the following, stop and ask for explicit
approval before implementing:
- A new way for a paid entitlement to activate (free, payment-independent
  enrollment itself is approved by ADR-028).
- Any mutation or deletion path touching ledger or terminal payment rows.
- Skipping or bypassing duplicate/suspicious slip checks.
- Recomputing historical settlement amounts from live rate config.

## 8. RBAC domain-area grants are not a mutation authorization

`identity-access-service`'s `PermissionCheckService.hasPermission(DomainArea, ...)`
(Module 3 / RBAC-2) models coarse, domain-level role capability - e.g. a `true`
result for `DomainArea.FINANCE_EXPENSES`/`DELETE` or
`DomainArea.PAYMENTS_SLIPS`/`CREATE_EDIT` (or `DomainArea.PAYMENT_GATEWAY`, §9). That result is a category grant only.
It never authorizes a literal ledger-row delete, or a mutation of a
`CONFIRMED`/`REJECTED`/`REFUNDED` payment or an `APPROVED` manual slip - the
rules in §1-§4 above govern those tables' actual mutation behavior regardless
of what the permission check returns. Any endpoint gated on one of these two
domain areas must still independently enforce this file's rules; do not treat
`hasPermission` returning `true` as sufficient authorization for the mutation
itself.

## 9. Tenant payment gateway accounts (Phase 3 — ADR-017, PayHere)

Phase 3 is approved for PayHere only (`docs/adr/ADR-017-tenant-payment-gateway-accounts.md`,
plan `docs/plans/PAY-GW-01 Tenant Payment Gateway (PayHere).md`). Phase 1 central
collection is not built; bank-slip remains available to every tenant.

- **Credentials.** A tenant's merchant secret lives only in
  `tenant_payment_gateway_account.merchant_secret_ciphertext`, AES-256-GCM encrypted
  with `PAYMENT_CREDENTIALS_ENCRYPTION_KEY`. It is write-only: never returned by any API,
  never logged, never written to `audit_log` or metrics, never sent to the frontend.
  Never store gateway credentials in `tenant_config_entry` (plaintext).
- **Checkout signing is server-side.** The PayHere hash is computed by the backend from
  the stored order snapshot (amount, currency). The frontend only submits the fields it
  is given; it never computes a hash or chooses an amount/currency.
- **Notifications decide nothing until verified.** A PayHere `notify_url` call is
  resolved to our `Payment` by its opaque `gateway_reference` (the ADR-011 cross-tenant
  lookup), then verified with **that payment's own tenant account** secret, and its
  `merchant_id`, amount and currency must equal the stored account/payment. The
  payload's `merchant_id` must never be used to pick the tenant. Any failure = reject,
  no state change, security log entry.
- **Status handling.** Only `status_code = 2` with a verified signature confirms a
  payment (existing idempotent confirmation → `PAYMENT_CONFIRMED` + paid-entitlement
  grant). `-1`/`-2` reject a PENDING payment. `0` changes nothing. The return page
  never activates anything (§1 still applies).
- **Chargebacks (`-3`) revoke access.** Record a `payment_refund` row with
  `source = CHARGEBACK` and the gateway's reference in `external_reference` (unique per
  tenant → a repeat is a no-op); its existing `REFUND` ledger entry reverses the
  original. The CONFIRMED payment row is never modified. Revoke only the paid access that
  payment granted (never the free enrollment itself, ADR-028). Do not add a new ledger entry type for this.
- **Who collected the money.** `payment.collected_by = TENANT` (+ `gateway_account_id`)
  marks funds paid into the tenant's own account. The platform takes **no commission**
  on these: any platform-level settlement or commission computation must exclude them.
  Teacher revenue-share settlements inside a tenant (§5) are unaffected.
- **Switching off.** Tenant Admin (`DomainArea.PAYMENT_GATEWAY` CREATE_EDIT) may
  configure or disable its own account; replacing credentials disables the old row
  (rows are never deleted — payments reference them). A Platform Admin may disable any
  tenant's account; a `disabled_by = PLATFORM` account can only be re-enabled by a
  Platform Admin. Payments already initiated still verify against the stored account.
- **No fallback in real environments.** Without an ACTIVE tenant account, online
  payment is refused ("pay by bank transfer"). The fake gateway fallback
  (`payment.gateway.fake-fallback-enabled`) is for local/test profiles only.
- Adding another gateway, a platform-level (Phase 1) account, split payments (Phase 4),
  or automatic reinstatement after a won chargeback each needs its own ADR.
