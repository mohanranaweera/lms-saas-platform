# ADR-017: Tenant-Specific Payment Gateway Accounts (Payment Roadmap Phase 3) — PayHere First

## Status

**Accepted (2026-09-26)** by the project owner ("ADR-017 approved"), following the
owner decisions below. Originally proposed the same day. Requested by the
project owner ("there should be a way to configure other payment gateways like payhere in
tenant level"), who chose tenant-level configuration over a platform-level PayHere account
when asked.

**Owner decisions recorded 2026-09-26** (answers to the open questions):
1. The platform takes **no commission** on sales collected into a tenant's own account.
2. A PayHere **chargeback revokes the student's access** to the course it paid for.
3. **Platform Admin can switch off** a tenant's online payments.
4. A Platform-Admin switch-off can **only be undone by a Platform Admin** — the Tenant
   Admin cannot re-enable a `disabled_by = PLATFORM` account.

This ADR touches three change-controlled areas named in root `CLAUDE.md`:
the **payment roadmap** (advancing to Phase 3 while Phase 2 settlements exist only for
teacher revenue share), **payment ledger rules** (who receives funds changes what a
`PAYMENT_CONFIRMED` entry means for settlement), and the **webhook tenant-resolution
carve-out** accepted in ADR-011.

## Context

- ADR-003 fixed a four-phase roadmap: (1) platform collects centrally, (2) settlements,
  (3) tenant-specific payment accounts, (4) split payments if the gateway supports them.
  It also said no gateway may be invented until one is selected. **The owner has now
  selected PayHere** (Sri Lankan Central-Bank-approved gateway; tenants are Sri Lankan
  institutes charging in LKR).
- Today the only `PaymentGatewayApi` implementation is `FakePaymentGatewayAdapter`: it
  returns a non-existent redirect URL and never confirms, so online "Enroll" can never
  complete. Manual bank-slip payment is the only working path.
- Institutes want funds paid straight into **their own** PayHere merchant account, not
  collected by the platform and settled later.
- PayHere specifics that shape the design (to be re-verified against PayHere's official
  developer docs before implementation — the reference used here is a community mirror):
  - Checkout is a browser **form POST** to `https://{sandbox|www}.payhere.lk/pay/checkout`
    carrying `merchant_id`, `order_id`, `amount` (2 dp), `currency`, return/cancel/notify
    URLs, customer fields and `hash = UPPER(MD5(merchant_id + order_id + amount +
    currency + UPPER(MD5(merchant_secret))))`, computed server-side.
  - Confirmation is a server-to-server **`notify_url`** POST
    (`application/x-www-form-urlencoded`) with `merchant_id, order_id, payment_id,
    payhere_amount, payhere_currency, status_code, md5sig`, where
    `md5sig = UPPER(MD5(merchant_id + order_id + payhere_amount + payhere_currency +
    status_code + UPPER(MD5(merchant_secret))))`. `status_code`: 2 success, 0 pending,
    -1 canceled, -2 failed, -3 charged back.
  - The **merchant secret is issued per domain** in the PayHere portal, and `notify_url`
    must be publicly reachable.

## Decision (proposed)

1. **Advance to Phase 3 for PayHere only**, as an opt-in per tenant. A tenant with no
   active gateway account keeps today's behavior (bank-slip only; the online "Enroll"
   button is hidden rather than failing). Phase 1 central collection is **not** built.
2. **New tenant-owned table `tenant_payment_gateway_account`** (one active account per
   tenant per provider): `provider` (`PAYHERE`), `mode` (`SANDBOX`/`LIVE`),
   `merchant_id`, `merchant_secret_ciphertext`, `status` (`ACTIVE`/`DISABLED`),
   `disabled_by` (`TENANT`/`PLATFORM`, so a platform switch-off can only be undone by a
   Platform Admin), audit columns. `payment_refund` gains `source` (`STAFF`/`CHARGEBACK`,
   default `STAFF`) and a unique-per-tenant `external_reference` for chargeback
   idempotency. The merchant secret is **encrypted at rest** (AES-256-GCM, key from
   `PAYMENT_CREDENTIALS_ENCRYPTION_KEY` env var, fail-closed when unset), is write-only
   through the API (never returned, only "configured: yes/last 4 of merchant id"), and is
   never logged. Tenant config (`tenant_config_entry`) is deliberately **not** used: it
   stores values in plaintext.
3. **Gateway routing by tenant**: `PaymentGatewayApi` gains a provider-routing layer
   (`PaymentGatewayRouter`) that picks the adapter from the tenant's active account.
   `PayHereGatewayAdapter` builds the signed checkout form fields; the backend returns
   them to the frontend, which auto-submits the form to PayHere (the frontend never sees
   the merchant secret and never computes a hash).
4. **Webhook tenant resolution (extends ADR-011)**: new endpoint
   `POST /api/v1/integrations/webhooks/payhere` (form-encoded, permitAll, excluded from
   subdomain tenant resolution — same carve-out as ADR-011). The `order_id` sent to
   PayHere is our `Payment.gatewayReference` (opaque, unguessable). Resolution order:
   look up the Payment by `gatewayReference` across tenants (ADR-011's single named
   bypass), load **that payment's tenant's** gateway account, verify `md5sig` with that
   tenant's secret, then check `merchant_id`, amount and currency match the stored
   Payment exactly. Any mismatch → reject with no state change and a security log entry.
   The payload's `merchant_id` is never used to choose the tenant.
5. **Ledger and enrollment rules unchanged in mechanism**: only `status_code = 2` with a
   verified signature confirms the Payment through the existing
   `PaymentConfirmationApi`, writing the existing `PAYMENT_CONFIRMED` entry and
   activating enrollment exactly as today. `-1/-2` mark the payment failed (existing
   terminal state); `0` is ignored (stays pending). The frontend return page still only
   polls backend status.
   **Chargeback (`-3`, verified signature, payment already CONFIRMED)** — owner decision 2:
   in one transaction, (a) insert a `payment_refund` row (source `CHARGEBACK`, system
   actor, full amount) which writes the existing **`REFUND`** ledger entry with
   `reverses_entry_id` = the payment's `PAYMENT_CONFIRMED` entry — no new ledger entry
   type, the confirmed Payment row itself is never updated; (b) revoke the enrollment that
   payment activated through the existing ADR-016 `EnrollmentActivationApi#revoke`
   (reason `PAYHERE_CHARGEBACK`), only if that enrollment is still the current one; (c)
   write an audit entry. A repeated chargeback notification is a no-op. A chargeback for a
   payment that is not CONFIRMED is rejected and security-logged.
6. **Ledger meaning**: a new nullable `payment.collected_by` (`PLATFORM`/`TENANT`) records
   whose account received the money. Owner decision 1: the platform takes no commission
   on `TENANT`-collected payments, so platform settlement/commission logic must exclude
   them entirely (they are the tenant's revenue, already in the tenant's account). No
   ledger entry type is added, removed or redefined. (Teacher revenue-share settlements
   inside a tenant are unaffected — they are the tenant's own internal split.)
7. **Who can configure**: Tenant Admin only (new `DomainArea.PAYMENT_GATEWAY` permission,
   `CREATE_EDIT`), every change audit-logged (actor, tenant, provider, mode, merchant id;
   never the secret). Owner decision 3: Platform Admin can view which tenants have an
   active account and **switch a tenant's online payments off** (sets the account
   DISABLED, reason required, audit-logged); students then see bank-slip only. Platform
   Admin can never read or set secrets. In-flight payments still verify (see plan §3.16).

## Consequences

**Positive** — Online payment actually works; institutes receive funds directly; adding
another gateway later is a new adapter + provider enum value, not a redesign.

**Negative / accepted**
- The platform stops seeing funds for PayHere tenants and, by owner decision, takes no
  commission on them.
- Chargebacks now revoke access automatically; a disputed-but-won chargeback needs a
  staff re-grant (ADR-016 staff-granted enrollment) — no automatic reinstatement.
- Per-domain merchant secrets: a tenant on a custom domain must register that domain in
  PayHere. Documented in the tenant setup guide.
- Local end-to-end testing of `notify_url` needs a public tunnel (e.g. ngrok) plus PayHere
  sandbox credentials; automated tests use signed synthetic notifications instead.
- Encryption key management becomes an operational responsibility (key rotation plan:
  re-encrypt on rotation; out of scope for the first slice).

## Alternatives considered

- **Platform-level PayHere account first (Phase 1)** — lower security surface, but the
  owner explicitly chose tenant-level accounts.
- **Store credentials in `tenant_config_entry` as a `sensitive` property** — rejected:
  masked on read but stored in plaintext.
- **Resolve tenant from the notification's `merchant_id`** — rejected: two tenants could
  share a merchant id by mistake, and it trusts a request field over our own Payment row.

## Required follow-up if accepted

- Module plan: `docs/plans/PAY-GW-01 Tenant Payment Gateway (PayHere).md`.
- Update `docs/architecture/payment-ledger.md`, `.claude/rules/payments.md` (Phase 3 +
  `collected_by`), ADR-003 status note, ADR-011 cross-reference.

## Related

ADR-003, ADR-010, ADR-011, `.claude/rules/payments.md`, `.claude/rules/security.md`.
