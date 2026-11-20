# PAY-GW-01 — Tenant Payment Gateway (PayHere)

Status: **Approved for implementation** (ADR-017 Accepted 2026-09-26). Backend first, then frontend (no full-stack approval given).

## 1. Business goal

Let each institute (tenant) connect its **own PayHere merchant account** so students can
pay online by card/wallet and the money goes straight to the institute. Today the online
"Enroll" button talks to a fake gateway and can never complete; bank-slip is the only
working payment method. Institutes without a PayHere account keep bank-slip only.

## 2. User roles

| Role | Interaction |
|---|---|
| Tenant Admin | Settings → Payments: add/replace PayHere merchant ID + secret, choose Sandbox/Live, enable/disable, see status. Only role allowed to change it. |
| Finance Staff | View-only: sees whether online payment is enabled and which mode; never the secret. |
| Student | Checkout shows "Pay online (PayHere)" only when the tenant has an active account; is redirected to PayHere and back; sees status from the backend. |
| Platform Admin | Sees per-tenant "online payments: enabled/disabled, provider, mode"; can disable a tenant's account (e.g. fraud); cannot read or set secrets. |
| Teacher / other staff | No access. |

## 3. Acceptance criteria

Configuration
1. Tenant Admin can save PayHere `merchantId`, `merchantSecret`, `mode` (SANDBOX/LIVE); saving
   replaces any previous account for that provider (old row DISABLED, not deleted).
2. The secret is never returned by any API, never logged, never shown in the UI after save;
   the UI shows "Secret configured" and the masked merchant ID (last 4).
3. Saving without a secret when none exists → 400 with a field error. Updating mode or
   merchant ID without re-entering the secret keeps the stored secret.
4. With `PAYMENT_CREDENTIALS_ENCRYPTION_KEY` unset, saving fails closed (503) and nothing is
   stored.
5. Non-Tenant-Admin roles get 403 on write; Finance Staff can read status.
6. Every create/replace/enable/disable writes an audit log entry (actor, tenant, provider,
   mode, masked merchant ID; no secret).

Checkout
7. `GET` checkout options for a course returns `onlinePayment: {provider, mode}` only when the
   tenant has an ACTIVE account; otherwise `onlinePayment: null` and the frontend hides the
   online button (bank-slip still offered).
8. Initiating online payment creates the Payment (PENDING) with an opaque `gatewayReference`
   and returns PayHere form fields (action URL, merchant_id, order_id = gatewayReference,
   amount 2dp, currency = order currency, return/cancel/notify URLs, hash). The frontend
   auto-submits them; the secret never leaves the backend.
9. Amount and currency always come from the stored order snapshot, never from the request.
10. Return page (`/student/payments/awaiting-confirmation/[orderId]`) only reflects backend
    status; landing on it never activates anything. Cancel URL shows "Payment cancelled" and
    offers retry/bank-slip.

Notification (notify_url)
11. `status_code=2` + valid `md5sig` + matching merchant_id/amount/currency → Payment
    CONFIRMED, one `PAYMENT_CONFIRMED` ledger entry, enrollment ACTIVE (existing path).
12. Duplicate notification for an already-confirmed payment → 200, no second ledger entry.
13. Invalid signature, unknown `order_id`, merchant_id not matching the payment's tenant
    account, or amount/currency mismatch → rejected (no state change) + security log entry.
14. `-1`/`-2` → Payment FAILED; `0` → no change.
14a. `-3` chargeback on a CONFIRMED payment (owner decision 2) → in one transaction: a
     `payment_refund` row (`source = CHARGEBACK`, full amount, PayHere `payment_id` as
     `external_reference`) and its existing `REFUND` ledger entry reversing the
     `PAYMENT_CONFIRMED` entry; the enrollment that payment activated is revoked via
     `EnrollmentActivationApi#revokeActivatedByPayment` (reason `PAYHERE_CHARGEBACK`) if
     still current; a security log entry is written (no audit_log row - see ADR-017
     implementation note). The student loses course access immediately. The CONFIRMED
     Payment row is not modified.
14b. A repeated `-3` for the same payment is a no-op (unique external reference). A `-3` for
     a payment that is not CONFIRMED is rejected and security-logged.
14c. Payment history shows the chargeback as a refund line; the admin dashboard shows it
     under refunds with source "Chargeback".
15. A notification signed with tenant A's secret for tenant B's payment is rejected.
16. Tenant account DISABLED after payment initiation: notifications for already-initiated
    payments are still verified with the stored secret and processed (money was taken).

## 4. Database impact

New Flyway migration `V57__create_tenant_payment_gateway_account.sql` (additive only):

- `tenant_payment_gateway_account` — `id UUID PK`, `tenant_id UUID NOT NULL FK tenant(id)`,
  `provider VARCHAR(20) NOT NULL CHECK (provider IN ('PAYHERE'))`,
  `mode VARCHAR(10) NOT NULL CHECK (mode IN ('SANDBOX','LIVE'))`,
  `merchant_id VARCHAR(64) NOT NULL`, `merchant_secret_ciphertext TEXT NOT NULL`,
  `status VARCHAR(10) NOT NULL CHECK (status IN ('ACTIVE','DISABLED'))`,
  `disabled_by VARCHAR(10) CHECK (disabled_by IN ('TENANT','PLATFORM'))`, audit columns,
  `UNIQUE (tenant_id, id)`; partial unique index `(tenant_id, provider) WHERE status='ACTIVE'`;
  index `(tenant_id, provider, created_at)`. Rows are never deleted (history of which
  account received which payments).
- `payment` — add nullable `collected_by VARCHAR(10) CHECK (collected_by IN
  ('PLATFORM','TENANT'))` and nullable `gateway_account_id UUID` with composite FK
  `(tenant_id, gateway_account_id)` → `tenant_payment_gateway_account (tenant_id, id)`.
  Existing rows stay NULL (no backfill). Terminal payments are never updated by this
  migration.
- `payment_refund` — add `source VARCHAR(20) NOT NULL DEFAULT 'STAFF' CHECK (source IN
  ('STAFF','CHARGEBACK'))` and nullable `external_reference VARCHAR(64)` with partial unique
  index `(tenant_id, external_reference) WHERE external_reference IS NOT NULL` (chargeback
  idempotency). Existing rows get `STAFF` via the default; no other change.

## 5. Backend impact

- `integration-management`: `PaymentGatewayRouter` (implements `PaymentGatewayApi`, chooses
  adapter by tenant account); `PayHereGatewayAdapter` (hash + form fields + md5sig verify);
  `CredentialCipher` (AES-256-GCM, key from env, fail-closed); new
  `PayHereNotificationController` (`/api/v1/integrations/webhooks/payhere`, form-encoded).
  `FakePaymentGatewayAdapter` stays for tests/local profile only.
- New `tenant-payment-gateway` slice inside `payment-management` (or `integration-management`
  — decided in `review-api-contract`): entity, repository (tenant-filtered), service
  (save/replace/disable + audit), controller.
- `PaymentInitiationService`: returns `PaymentInitiationView` extended with
  `formAction` + `formFields` (existing `redirectTarget` kept for compatibility).
- `PaymentConfirmationService`: new `confirmPayHereNotification` path reusing the existing
  confirm/fail logic; records `collected_by = TENANT`, `gateway_account_id`.
- `ChargebackService` (payment-management): system refund (`source = CHARGEBACK`) via the
  existing refund/ledger path + `EnrollmentActivationApi#revokeActivatedByPayment` +
  security log, one transaction.
- Settlement/commission: any platform-level settlement or commission computation must skip
  `collected_by = TENANT` payments (owner decision 1: no platform commission). Teacher
  revenue-share settlements inside a tenant are unchanged.
- Platform Admin: `POST /api/v1/platform-admin/tenants/{tenantId}/payment-gateway/disable`
  (owner decision 3).
- Security: `SecurityFilterChainConfig` + `TenantResolutionFilter` add the new webhook path to
  the ADR-011 carve-out; new `DomainArea.PAYMENT_GATEWAY` in the RBAC seed.
- Config: `payment.credentials.encryption-key`, `payment.payhere.public-base-url` (for
  notify/return URLs).

## 6. API contract (to be finalized via `review-api-contract`)

| Method & path | Who | Request | Response |
|---|---|---|---|
| `GET /api/v1/payment-gateway-accounts` | Tenant Admin, Finance Staff | — | `[{id, provider, mode, merchantIdMasked, status, secretConfigured, updatedAt}]` |
| `PUT /api/v1/payment-gateway-accounts/payhere` | Tenant Admin | `{merchantId, merchantSecret?, mode}` | 200 account view; 400 field errors; 503 if encryption key unset |
| `POST /api/v1/payment-gateway-accounts/{id}/disable` | Tenant Admin, Platform Admin (own route) | `{reason}` | 200 |
| `GET /api/v1/checkout/payment-options` (implemented; tenant-wide, not per course) | Any signed-in tenant user | — | `{bankSlip: true, onlinePayment: {provider, mode} \| null}` |
| `POST /api/v1/orders/{id}/payments` (existing) | Student | unchanged | adds `formAction`, `formFields{...}` when provider is PAYHERE |
| `POST /api/v1/integrations/webhooks/payhere` | PayHere | form-encoded notify params | 200 on processed/duplicate; 400 on rejected |
| `GET` / `PUT /api/v1/students/me/billing-address` (V58) | Student (own only) | `{addressLine, city}` (both required on PUT) | `{addressLine, city}`; PayHere checkout returns 400 `billingAddress` until both are set, before any payment row is created |

Errors use the existing `ApiResponse` error envelope.

## 7. Frontend impact

- Tenant Admin: new `tenant-admin/settings/payments` page — status card, PayHere form
  (React Hook Form + Zod; secret input type=password, never prefilled), Sandbox/Live toggle,
  disable with confirm dialog, loading/empty/error/403 states.
- Student checkout: read checkout options; show "Pay online with PayHere" only when enabled;
  on initiate, render a hidden form from `formFields` and submit it to `formAction`.
  Cancel page copy. Awaiting-confirmation page unchanged (polls backend).
- Platform Admin tenant detail: "Online payments" status + disable action.

## 8. Security impact

- Merchant secret: encrypted at rest, write-only API, excluded from logs/audit/metrics,
  masked in UI. Encryption key only via env var; fail-closed when missing.
- Webhook: unauthenticated by design (ADR-011 carve-out); authenticity = per-tenant md5sig +
  exact merchant/amount/currency match against our stored Payment; tenant resolved from our
  Payment row, never from the payload.
- Replay: idempotent confirmation (already-CONFIRMED → no-op); PayHere `payment_id` stored
  for reconciliation.
- Enrollment activation rule unchanged: only verified backend confirmation activates.
- MD5 is PayHere's mandated scheme; it is acceptable here only as PayHere's message
  authentication, never reused elsewhere.

## 9. Tenant impact

- `tenant_payment_gateway_account` carries `tenant_id` (NOT NULL, FK, leading index); all
  reads go through the tenant-filtered repository; no method accepts a caller-supplied
  tenant id.
- Admin endpoints resolve tenant from the authenticated context only.
- Webhook: the only cross-tenant read is ADR-011's existing named `gatewayReference` lookup;
  the gateway account used for verification is loaded **within that payment's tenant**.
- Composite FK keeps `payment.gateway_account_id` inside the same tenant.

## 10. Tests

Backend (JUnit + Testcontainers)
- `PayHereGatewayAdapterTest`: hash and md5sig against PayHere's documented examples;
  amount formatting (2 dp); uppercase rules.
- `CredentialCipherTest`: round-trip, tamper detection, fail-closed without key.
- `TenantPaymentGatewayAccountIntegrationTest`: save/replace/disable, secret never in
  responses, audit entries, 403 for non-admin roles, 400/503 paths.
- `PayHereNotificationIntegrationTest`: success confirms + one ledger entry + enrollment
  active; duplicate is a no-op; bad signature / amount / currency / merchant mismatch
  rejected; `-1/-2/0` behavior; disabled-account still verifies in-flight payments.
- `PayHereChargebackIntegrationTest`: `-3` writes exactly one CHARGEBACK refund + one REFUND
  ledger entry reversing the original, revokes the enrollment (student gets 403 on the
  course), leaves the Payment row unchanged; repeat is a no-op; `-3` on a non-confirmed
  payment rejected; superseded enrollment is not revoked.
- `PlatformAdminPaymentGatewayDisableIntegrationTest`: platform admin disables a tenant's
  account (reason required, audited); checkout options then return `onlinePayment: null`;
  a tenant admin cannot re-enable a platform-disabled account (see remaining open point).
- Cross-tenant: tenant A admin cannot read/modify tenant B's account (404); notification
  signed with tenant A's secret for tenant B's payment rejected; checkout options never
  expose another tenant's account.

Frontend (Playwright, mocked API)
- Settings page: save flow, secret never rendered back, validation, 403 state, disable
  confirm.
- Checkout: online button hidden when `onlinePayment: null`; shown when enabled; initiate
  submits a form to the returned `formAction` with the returned fields (no client hash).
- Cancel page and awaiting-confirmation still never trust redirect params.

Manual: PayHere sandbox end-to-end through a public tunnel (ngrok) with sandbox test cards.

## 11. Documentation updates

- `docs/adr/ADR-017-tenant-payment-gateway-accounts.md` → Accepted; ADR-003 status note;
  ADR-011 cross-reference.
- `docs/architecture/payment-ledger.md` (Phase 3, `collected_by`), `.claude/rules/payments.md`.
- `docs/api/` contract for the new endpoints.
- Tenant Admin setup guide: getting PayHere sandbox/live credentials, registering the
  domain, switching to Live.
- `backend/.env.example`: `PAYMENT_CREDENTIALS_ENCRYPTION_KEY`, `PAYHERE_PUBLIC_BASE_URL`.

## Owner decisions (2026-09-26)

1. **No platform commission** on sales collected into a tenant's own PayHere account.
2. **Chargeback revokes the student's access** (implemented as §3 14a–14c).
3. **Platform Admin can switch off** a tenant's online payments.
4. A Platform-Admin switch-off can only be re-enabled by a Platform Admin
   (`disabled_by = PLATFORM`); the Tenant Admin sees "Disabled by platform" and cannot
   save/enable until a Platform Admin re-enables it.
