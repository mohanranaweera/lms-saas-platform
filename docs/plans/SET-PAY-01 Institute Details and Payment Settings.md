# SET-PAY-01 — Institute Details and Payment Settings (Klass parity)

Status: **Implemented** (backend + frontend). Date: 2026-10-29.
Reference screenshots: `docs/references/Klass-institute-related-info/` (`Institute Details.png`,
`Payment Settings.png`).

Decisions taken with the product owner (2026-10-29):

- **Disable bank transfer** switch is in scope and **enforced server-side**.
- **One** institute bank account (reuse the four existing `PAYMENT.bank_*` keys) — no multi-account table.
- New public institute details are **captured and shown on the public home page**.
- Klass "Institute Admin Details" and "Login Options" are **out of scope** (the admin name is user-profile
  data, and login options change the authentication architecture, which is change-controlled).
- Work stays on branch `feat/tenant-appearance-public-home`.

## 1. Business goal

Today the institute record has only a name, support email/phone, time zone and currency (Settings →
General). Payment configuration is split: PayHere and the facility fee are on a separate **Online
payments** page in the sidebar, default currency sits in General, and the institute bank account shown
to students at checkout (`PAYMENT.bank_name/bank_branch/bank_account_holder/bank_account_number`)
**has no admin screen at all**, so a tenant can't set it.

SET-PAY-01 brings this in line with Klass:

| Settings tab | Sections |
|---|---|
| **General** | *Institute public information*: institute name, contact phone, contact email, address line 1, address line 2, city, postal code, institute introduction ("shown on your public page"), Facebook, YouTube. Default time zone. (Default currency **removed** from here.) |
| **Payments** (new tab) | *Currency settings* (moved from General, now a dropdown) · *Facility fee* (= Klass "Service fee", moved from Online payments) · *Payment gateway — PayHere* (moved from Online payments, unchanged behaviour) · *Bank details* (new UI over the existing keys) + *Bank transfer* on/off switch (new). |

The separate "Online Payments" sidebar entry goes away; its URL `/tenant-admin/settings/payments`
becomes the Payments tab, so existing links keep working.

Payment, ledger, slip-review, enrollment and entitlement **rules are unchanged**. The only new
payment behaviour is the bank-transfer switch, which only removes an *option to submit a slip*; it
never grants, revokes or changes any payment, entitlement or enrollment.

## 2. User roles

| Role | General tab | Payments tab |
|---|---|---|
| Tenant Admin | View + edit (`BRANDING_SETTINGS` VIEW/CREATE_EDIT) | Edit every section (`BRANDING_SETTINGS` for currency/fee/bank; `PAYMENT_GATEWAY` CREATE_EDIT for PayHere) |
| Read-only Auditor | View only | View only (each section read-only) |
| Finance Staff | As today (whatever `BRANDING_SETTINGS` VIEW grants) | PayHere status as today (`PAYMENT_GATEWAY` VIEW); other sections show view-only or permission-denied **inside that section** |
| Student | — | Sees the bank details at checkout (already) and the slip option only when bank transfer is on; sees the new public info on the public home page |
| Anonymous visitor | — | Sees the new public info on the public home page |
| Platform Admin | Unchanged (tenant PayHere card on the tenant detail page is untouched) | Unchanged |

No permission model change: every section keeps the domain-area grant its data already uses.

## 3. Acceptance criteria

**Settings navigation**

1. The Settings tab strip shows **Payments** after General, visible when the role holds
   `canViewInstituteConfig` **or** `canViewPaymentGateway` (UX only; backend re-checks).
2. `/tenant-admin/settings/payments` renders inside the Settings header with the Payments tab active.
3. The sidebar no longer has "Online Payments"; the Settings entry is highlighted on the Payments tab.
4. The Settings index page describes General and Payments accurately (no "default currency" under General).

**General tab — institute public information**

5. New fields: Address line 1, Address line 2, City, Postal code, Institute introduction (multi-line,
   helper "This will show on your public page."), Facebook URL, YouTube URL. All **optional** server-side
   (existing tenants have none of them; Klass's `*` markers are not enforced so no tenant is blocked from
   saving the form).
6. Validation (server, mirrored client-side): text fields ≤ 255 chars; introduction ≤ 2000 chars;
   Facebook must be an `https://` URL on `facebook.com`/`fb.com` (or a subdomain); YouTube must be an
   `https://` URL on `youtube.com`/`youtu.be` (same rule as `support_video_url`). Invalid → `400` with a
   field error on that key.
7. A field can be cleared (blank → `null`, existing clear semantics); `institute_name` stays required.
8. Default currency is no longer on this tab. Saving General never sends `default_currency`.
9. Read-only Auditor sees every value with all fields disabled and no Save.

**Payments tab**

10. *Currency settings*: a select "Currency students are charged in" listing a curated set (LKR, USD,
    EUR, GBP, AUD, INR, …); if the stored value is outside the list it is still shown and selectable.
    Helper text: "Applies to every course, receipt and report from here on. Payments already recorded
    keep the currency they were taken in." A badge in the card header shows the current code. Saves
    `GENERAL.default_currency` (key and backend validation unchanged).
11. *Facility fee*: existing section, unchanged behaviour, retitled "Service fee (facility fee)".
12. *Payment gateway — PayHere*: existing status card, connect/update form, sandbox/live, switch-off
    dialog, platform-disabled and not-checkout-ready alerts, all unchanged in behaviour. Merchant secret
    stays write-only and is never prefilled.
13. *Bank details*: Account holder, Account number, Bank name, Branch — view mode with **Edit**; Save /
    Cancel in edit mode. Each ≤ 255 chars; blank clears. Shown to students at checkout exactly as today.
14. *Bank transfer* switch "Allow bank transfer (payment slip upload)", default **on**.
    - Turning it off while PayHere is not checkout-ready shows a warning that students would have no
      way to pay, and the backend treats bank transfer as **still available** (fail-safe, see 17).
15. Each section has its own loading / error / permission-denied state; a 403 in one section does not
    hide the others.

**Bank transfer enforcement (server-side)**

16. New key `PAYMENT.bank_transfer_enabled` (boolean, default `true`).
17. Effective rule, computed server-side in one place: **slip allowed = `bank_transfer_enabled` OR online
    payment is not available** for the tenant. So turning the switch off never leaves students without a
    payment method, including when PayHere is later switched off by the tenant or platform.
18. When slip is not allowed:
    - `GET /api/v1/checkout/payment-options` returns `bankSlip: false`;
    - checkout quotes return `methods.slip = false`;
    - `POST /api/v1/orders/{orderId}/slips` (student upload) is rejected with `409 BANK_TRANSFER_DISABLED`,
      before any storage write;
    - the student checkout UI hides the slip option and the bank details.
19. Not affected: slips already submitted (still reviewed/approved normally), staff-recorded manual
    payments and free cards, existing orders and enrollments. Enrollment never depends on the switch
    (ADR-028).

**Public home page**

20. `GET /api/v1/public/home` adds an `institute` object: `introduction`, `addressLine1`,
    `addressLine2`, `city`, `postalCode`, `facebookUrl`, `youtubeUrl` (nulls when unset). Existing fields
    unchanged.
21. The public home shows an "About us" block with the introduction (plain text, line breaks kept, no
    HTML rendered), and the footer shows the address and Facebook / YouTube links
    (`rel="noopener noreferrer"`, new tab). Sections with no data are not rendered.

## 4. Database impact

**No migration.** All new values are rows in the existing tenant-owned `tenant_config_entry` table
(`tenant_id NOT NULL`, FK, tenant-leading unique key already in place). New registered keys:

| Domain | Key | Type | Default |
|---|---|---|---|
| GENERAL | `address_line1`, `address_line2`, `city`, `postal_code` | STRING | `null` |
| GENERAL | `institute_introduction` | STRING | `null` |
| GENERAL | `facebook_url`, `youtube_url` | STRING | `null` |
| PAYMENT | `bank_transfer_enabled` | BOOLEAN | `true` |

`GENERAL.default_currency` stays in GENERAL (only its UI moves), so `TenantConfigApi
.resolveDefaultCurrency` and all money code are untouched. No change to migration history.

## 5. Backend impact

- `tenantmanagement/config/ConfigPropertyRegistry`: register the 8 keys above with validators
  (`isShortText`-style length checks, new `MAX_LONG_TEXT_LENGTH = 2000`, new `isFacebookUrl`, reuse
  `isYouTubeUrl`, `isBoolean`). Update class/method Javadoc (bank keys now have an admin UI).
- `tenantmanagement/api/TenantConfigApi`: add `boolean isBankTransferEnabled(UUID tenantId)` (raw flag,
  default `true`). Tenant-management stays foundational: it does **not** look at PayHere.
- `paymentmanagement`: new `BankTransferPolicy` (service) — `boolean isSlipAllowed()` =
  `tenantConfigApi.isBankTransferEnabled(tenantId) || !paymentGatewayAccountApi.isOnlinePaymentAvailable()`,
  tenant from `TenantContext`. Used by:
  - `CheckoutComposerService#toQuote` → `new Methods(online, bankTransferPolicy.isSlipAllowed())`;
    bank details are still returned (the frontend hides them when `slip=false`).
  - `SlipUploadService#uploadSlip` → if not allowed, throw `BankTransferDisabledException` before
    sniffing/storage; mapped to `409 BANK_TRANSFER_DISABLED` in the payment module's exception handler.
- `integrationmanagement/web/CheckoutPaymentOptionsController`: `bankSlip` = same rule. To avoid an
  integration→payment dependency it computes it from `TenantConfigApi.isBankTransferEnabled` and its own
  `PaymentGatewayAccountApi` (same two inputs; covered by a shared-rule test on both paths).
- `tenantmanagement/web/PublicHomeController` + `dto/PublicHomeResponse`: add nested
  `Institute(introduction, addressLine1, addressLine2, city, postalCode, facebookUrl, youtubeUrl)`.
- Audit: no new code — `TenantConfigService` already writes `tenant_config.updated` / `.cleared` audit
  entries (actor, tenant, before/after) in the same transaction; this now covers bank account changes
  and the bank-transfer switch. Verify before/after is recorded for these keys in tests.

## 6. API contract

All changes are **additive**; feed into `review-api-contract` before implementation.

| Endpoint | Change |
|---|---|
| `GET/PUT /api/v1/tenant-config/GENERAL` | 7 more keys in the property array / accepted in the body. Unknown-key and validation errors unchanged (`400`, field errors keyed by property key). |
| `GET/PUT /api/v1/tenant-config/PAYMENT` | `bank_transfer_enabled` (boolean). |
| `GET /api/v1/checkout/payment-options` | `bankSlip` now reflects the effective rule (was always `true`). Shape unchanged. |
| `POST /api/v1/checkout/quote` (+ staff quote) | `methods.slip` reflects the rule for the student checkout; **staff manual-payment quote keeps `slip=true`** (staff recording is unaffected). Shape unchanged. |
| `POST /api/v1/orders/{orderId}/slips` | New error: `409` `{ code: "BANK_TRANSFER_DISABLED", message: "Bank transfer is not accepted by this institute. Please pay online." }`. |
| `GET /api/v1/public/home` | New `institute` object (all fields nullable). |

## 7. Frontend impact

- `components/settings/settings-tab-strip.tsx`: add `{ label: "Payments", href: "/tenant-admin/settings/payments", visible: canViewInstituteConfig || canViewPaymentGateway }` after General.
- `components/layout/nav/tenant-admin-nav.tsx`: remove "Online Payments"; add
  `/tenant-admin/settings/payments` to Settings' `activePrefixes`.
- `settings/page.tsx` (index): update General card text; add Payments card.
- `settings/general/general-config-form.tsx` + `lib/validation/tenant-config.ts`: drop
  `default_currency`; add the 7 fields grouped as "Institute public information" (two-column grid like
  Klass, introduction as `Textarea`); page description updated.
- `settings/payments/page.tsx`: wrap in `SettingsHeader active="/tenant-admin/settings/payments"`;
  split into section cards: `currency-section.tsx` (new, GENERAL domain), `facility-fee-section.tsx`
  (existing), PayHere section (existing body moved to `payhere-section.tsx`), `bank-details-section.tsx`
  (new, PAYMENT domain: view/Edit mode + bank-transfer `Switch`). Each section owns its
  `QueryStateBoundary`.
- `lib/api/tenant-config.ts`/validation: schemas for currency, bank details, bank-transfer flag.
- Student checkout (`components/payments/composer/composer-summary-panel.tsx`, and wherever
  `payment-gateway.ts#bankSlip` is consumed): hide slip option + bank card when `slip/bankSlip` is
  false; map `409 BANK_TRANSFER_DISABLED` to a friendly message.
- Public: `lib/api/public-home.ts` type, `app/(public)/page.tsx` "About us" block,
  `components/public/public-chrome.tsx` footer address + social links.

## 8. Security impact

- **Bank account details are a fraud target** (changing the account redirects student transfers). Edit
  stays Tenant-Admin-only (`BRANDING_SETTINGS` CREATE_EDIT, re-checked on `PUT`), and every change is
  audit-logged with before/after (existing mechanism). No public endpoint exposes bank details; they are
  returned only to authenticated students in checkout (unchanged).
- Bank-transfer enforcement is backend-only: hiding the UI option is UX; the slip upload endpoint
  rejects regardless (`409`, no partial storage write, checked before upload validation).
- PayHere secret handling unchanged (write-only, encrypted, never returned) — the component is moved,
  not rewritten.
- Public institute fields: introduction rendered as text (React escaping, no `dangerouslySetInnerHTML`);
  social URLs restricted to `https` on allowlisted hosts, so no `javascript:` or arbitrary-site links.
  Address/introduction are intentionally public (the admin enters them for the public page; helper text
  says so).
- No secrets, no auth-architecture change, no payment-ledger or entitlement-rule change.

## 9. Tenant impact

- All values live in `tenant_config_entry` keyed by the tenant from `TenantContext` (auth/host
  resolution); no endpoint accepts a `tenant_id`.
- `BankTransferPolicy` and the options controller read config and gateway state for the resolved tenant
  only; tenant A switching bank transfer off has no effect on tenant B.
- Public home resolves the tenant from the host (existing); a tenant's address/introduction never
  appears on another tenant's site.

## 10. Tests

**Backend (JUnit + Spring Boot/Testcontainers)**

- `ConfigPropertyRegistry` unit tests: each new key valid/invalid (lengths, Facebook/YouTube hosts,
  `http://`/`javascript:` rejected, boolean type).
- `TenantConfigIntegrationTest`: GENERAL/PAYMENT round-trip of the new keys; clear to null; Auditor `403`
  on PUT; audit entry with before/after for `bank_account_number` and `bank_transfer_enabled`;
  **cross-tenant**: tenant A's PUT does not change tenant B's values, and tenant B reads its own.
- `BankTransferPolicy` unit tests: truth table (flag on/off × online available/unavailable).
- Slip upload integration: flag off + PayHere active → `409 BANK_TRANSFER_DISABLED`, no slip row, no
  storage write; flag off + PayHere not available → upload accepted (fail-safe); already-submitted slip
  still approvable after switch-off; **cross-tenant**: tenant A off does not block tenant B uploads.
- Checkout quote + `payment-options`: `slip/bankSlip` follow the rule; staff manual-payment quote still
  `slip=true`.
- `PublicHomeController` integration: `institute` object populated/null; tenant resolved by host only.

**Frontend (Playwright + component tests)**

- Settings: Payments tab visible/active; sidebar has no "Online Payments"; old URL still renders.
- General: new fields save and reload; invalid Facebook URL shows field error; currency field absent.
- Payments: currency select saves; bank details Edit/Save/Cancel; bank-transfer switch saves and the
  warning shows when PayHere not ready; PayHere connect flow unchanged (existing tests updated for the
  new layout); Auditor sees read-only everywhere.
- Checkout: with `slip=false`, the slip option and bank card are hidden; `409` shows the friendly error.
- Public home: About block and footer links render when set, absent when not.

## 11. Documentation updates

- `docs/api/tenant-configuration-management.md` — new GENERAL/PAYMENT keys, validation, defaults.
- `docs/specs/klass-enrollment-payment-workflows.md` — bank-transfer switch and the fail-safe rule.
- `docs/parity/klass-parity-matrix.md` — Institute details / Payment settings rows.
- `docs/parity/implementation-roadmap.md` — SET-PAY-01 entry.
- This plan → status updated on completion.
