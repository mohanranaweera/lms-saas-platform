# ADR-024: Text.lk (SMS) and Meta WhatsApp Cloud API (WhatsApp) messaging providers

- Status: Accepted (product owner, 2026-10-02: vendors, tenant-level template language,
  delivery receipts in scope)
- Date: 2026-10-02
- Plan: `docs/plans/MSG-PROV-01 Text.lk SMS and Meta WhatsApp Providers.md`
- Related: ADR-023 (communications and messaging providers; this ADR is the "vendor ADR"
  its decision D1 requires), ADR-019 (production secrets)

## Context

ADR-023 shipped provider-agnostic SMS/WhatsApp delivery with a no-network `SANDBOX` adapter
only, and required a separate ADR, adapter and migration for each real vendor. The product
owner has chosen **Text.lk** for SMS and **Meta WhatsApp Cloud API (direct)** for WhatsApp.
Each tenant uses its own vendor account (tenant-level settings), as for PayHere (ADR-017).

## Decisions

**V1 — Two new providers, one channel each.**
`TEXTLK` (SMS only) and `META_WHATSAPP` (WhatsApp only), each a
`gateway.messaging.MessagingProviderAdapter`. V69 widens
`ck_tenant_messaging_account_provider` to `('SANDBOX', 'TEXTLK', 'META_WHATSAPP')`.
`SANDBOX` stays local/test only. Both real adapters are enabled by default
(`app.messaging.textlk.enabled`, `app.messaging.meta-whatsapp.enabled`), so they can be
switched off per environment without a code change.

**V2 — Tenant-level, write-only credentials (unchanged model).**
- Text.lk: secret `apiToken`; sender = the tenant's Text.lk-approved sender ID
  (≤ 11 letters/digits, or a phone number).
- Meta: secrets `accessToken` (a System User permanent token is recommended) and
  `appSecret` (verifies webhooks, V8); sender = the WhatsApp **phone number ID** (digits).
- Secrets keep ADR-023's handling: AES-256-GCM, write-only, masked hint, never logged
  or audited.

**V3 — WhatsApp template language is a tenant-level, non-secret setting.**
Meta templates are addressed by `(name, language)`. A new nullable
`tenant_messaging_account.template_language` (e.g. `en`, `en_US`, `si`, `ta`) is required
for `META_WHATSAPP` and must be null for other providers (DB CHECK). It's returned to the
settings page (it isn't secret).

**V4 — Template parameters are positional, in order of appearance.**
Our copy uses named placeholders (`{{name}}`, `{{instituteName}}`). For WhatsApp, the
dispatcher sends the values of the placeholders in the order they first appear in the
approved template's copy (its SMS/WhatsApp text, else its body) - not the broadcast's
copy, which the sender may have edited after picking the template - as Meta body parameters `{{1}}`, `{{2}}`, …. The tenant registers the
Meta template with the same number and order of variables. A count mismatch is Meta error
132000 → permanent failure (`FAILED`), visible on the delivery row.

**V5 — Phone numbers are normalised before sending.**
Student mobiles are free text. Numbers are reduced to digits, and a national number with a
leading `0` is converted with `app.messaging.default-country-code` (default `94`,
Sri Lanka). Both vendors receive international digits without `+`. An unusable number →
permanent failure `INVALID_DESTINATION`.

**V6 — Error mapping (no response body is ever logged or stored).**
- Text.lk: network errors, HTTP 429 and 5xx → transient (retried per ADR-023 D3);
  401/403 → permanent `PROVIDER_AUTH_REJECTED`; other 4xx, or `status` of
  `error`/`false` in a 200 body → permanent `PROVIDER_REJECTED`.
- Meta: by `error.code`. Transient: 1, 2, 4, 80007, 130429, 131000, 131016, 131048,
  131056, network errors and 5xx. Auth (0, 3, 10, 190, 200) → `PROVIDER_AUTH_REJECTED`.
  Everything else (e.g. 131026, 131047, 132000, 132001) → `PROVIDER_REJECTED`.
- Only our fixed code and, for Meta, the numeric vendor code are logged.

**V7 — Test send.** Text.lk sends the fixed test text. Meta sends Meta's default
`hello_world` template (language `en_US`), because a free-form message is refused outside
the 24-hour customer-service window; the page says so.

**V8 — WhatsApp delivery receipts via a per-tenant Meta webhook (product owner, 2026-10-02).**
- Each tenant with a Meta account gets a stable `tenant_messaging_webhook` row: an
  unguessable `webhook_key` (path id) and a random `verify_token`, kept when credentials
  are replaced. The settings page shows the callback URL
  `…/api/v1/integrations/webhooks/meta-whatsapp/{webhookKey}` and the verify token to paste
  into the tenant's Meta app.
- `GET` answers Meta's subscription handshake only when `hub.verify_token` matches
  (constant-time). This path is added to the public webhook rules (GET was POST-only).
- `POST` is accepted only when `X-Hub-Signature-256` is a valid HMAC-SHA256 of the raw
  body with the **App Secret** of that tenant's latest Meta account (a new write-only
  credential, `appSecret`). The key selects the tenant (ADR-011 style); the payload never
  does, and each change's `metadata.phone_number_id` must equal that account's phone number
  ID. Any failure → 401, nothing written, security log entry.
- Status updates (`sent`, `delivered`, `read`, `failed` + Meta error code) are handed to
  `notification-management` as an event carrying the tenant id. They update new columns
  on `notification_delivery` (`provider_status`, `provider_status_at`,
  `provider_error_code`), only for that tenant's WhatsApp delivery with that provider
  message id, and only forward (`SENT → DELIVERED → READ`; `FAILED` from `SENT`/`DELIVERED`).
  The dispatch `status` (retry lifecycle) is not changed.
- **Text.lk has no delivery-report webhook** for outgoing SMS (its only webhook is inbound
  SMS), and its "view SMS" status values aren't documented. SMS deliveries therefore stay
  "accepted by provider". Polling Text.lk is deferred until its status values are confirmed.

## Consequences

- No change to tenancy resolution, auth architecture, payments, consent rules or the retry
  policy. One new public GET webhook path, signature/token-verified.
- One additive migration (V69); V1–V68 untouched.
- `OutboundMessage` (internal module API) gains an ordered `templateParameters` list.
- Messages cost tenant money: still Tenant Admin / Student Support only (teachers stay
  in-app + email, ADR-023 J2).

## Not decided here

Text.lk delivery status polling, inbound replies (SMS or WhatsApp), automatic WhatsApp
template sync/approval from Meta, Meta embedded signup, per-template languages,
media/header template components, and SMS sender-ID registration workflows.
