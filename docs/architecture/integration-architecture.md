# Integration Architecture

Status: Living document

## 1. Purpose

This document describes how the platform integrates with third-party systems (Zoom,
SMS provider, WhatsApp Business API, email/SMTP provider, payment gateway, object
storage, secure video hosting) through a single owning module,
`integration-management`, rather than each business domain embedding its own
provider-specific code and credentials. This corresponds to source requirements
module 16, "Integrations Center."

## 2. Why one owning module

Per `.claude/rules/architecture.md`: *"`integration-management` owns all third-party
credentials/webhooks (Zoom, SMS, WhatsApp, email, payment gateway, object storage).
Other domains call it through its `api` interfaces rather than embedding provider
SDKs/credentials directly -- this keeps provider swaps and credential rotation
isolated to one module."*

Concretely, this means:

- **No business domain holds a provider SDK dependency or provider credentials
  directly.** `payment-management` does not hold payment-gateway API keys;
  `live-class-management` does not hold Zoom API credentials; `notification-management`
  does not hold SMS/WhatsApp/email provider credentials. All of these live behind
  `integration-management`.
- **Provider swaps and credential rotation are isolated to one module.** Changing SMS
  providers, or rotating a compromised API key, is a change inside
  `integration-management` plus its adapters -- not a change scattered across every
  domain that happens to send a notification.
- **Business domains depend only on `integration-management`'s `api` interfaces** --
  e.g. a `VideoStorageApi`, `LiveClassProviderApi`, `MessagingProviderApi`,
  `PaymentGatewayApi`, `ObjectStorageApi` -- expressed in domain terms ("issue a signed
  playback URL for this video," "send this templated message to this recipient"), not
  in provider-SDK terms.

## 3. Adapter pattern

`integration-management` is structured (consistent with the standard domain package
convention in `modular-monolith.md`) as:

- `api` -- provider-agnostic interfaces and DTOs that business domains depend on, e.g.:
  - `LiveClassProviderApi` (schedule meeting, generate join URL, fetch recording
    metadata) -- backing implementation talks to Zoom today.
  - `MessagingProviderApi` (send email / send SMS / send WhatsApp message) -- one
    interface per channel, or one interface with a channel discriminator, decided at
    implementation time; the point is business domains call this, not an SMTP/SMS/
    WhatsApp SDK directly.
  - `PaymentGatewayApi` (initiate payment, verify/confirm payment, process refund) --
    provider TBD (see `system-context.md` open questions).
  - `ObjectStorageApi` (store material, generate signed download URL).
  - `VideoStorageApi` (store/reference video, generate signed short-lived playback
    URL/token) -- distinct from `ObjectStorageApi` because video access has additional
    session/device/view-limit semantics owned by `video-access-management` (see
    `.claude/rules/security.md`, "Video & Session Protection").
- **Provider adapters** (internal to `integration-management`, not exposed outside
  it) -- one adapter implementation per concrete provider, implementing the `api`
  interface above. Swapping a provider means writing a new adapter and pointing
  configuration at it; it does not change the `api` contract or any calling domain's
  code.
- **Per-tenant integration configuration** -- where a tenant can bring their own
  account (e.g. tenant's own Zoom account, tenant-specific SMS sender name, per
  source requirements modules 2 and 9), `integration-management` resolves which
  credential/config set to use per tenant, still behind the same `api` interface --
  the calling domain never needs to know whether a given tenant uses a
  platform-default or tenant-specific configuration.
- **Platform-level default integrations** -- for tenants without their own
  configuration, `integration-management` falls back to a platform-level default
  provider account/config, again transparently to the calling domain.

## 4. Webhook handling pattern

Providers that call back into the platform (payment gateway confirmation, Zoom
recording-ready notification, WhatsApp delivery status, etc.) are received exclusively
by `integration-management`'s `web` layer -- no business domain exposes its own
provider-facing webhook endpoint.

Pattern:

1. **Receive** -- `integration-management` exposes one webhook endpoint per provider
   (e.g. `/integrations/webhooks/{provider}`), validates the request's authenticity
   per that provider's signature/verification mechanism, and logs the raw webhook
   event (webhook log, per source requirements module 16's "Webhook logs").
2. **Translate** -- the raw provider payload is translated into an internal,
   provider-agnostic domain event or a direct `api`-interface call into the owning
   business domain (e.g. a payment-gateway confirmation webhook translates into a
   call against `payment-management`'s confirmation-handling `api`, which itself
   persists the confirmed payment per `.claude/rules/backend.md`'s transaction
   boundary rules).
3. **Never trust tenant identity from the webhook payload.** Tenant context for a
   webhook-driven update must be resolved from the platform's own record of which
   tenant/order/session the external reference (order id, meeting id, message id)
   belongs to -- never from a tenant id embedded in the webhook body itself,
   consistent with the platform-wide rule that tenant identity is never trusted from
   externally-controlled input (`.claude/rules/tenancy.md`).
4. **Idempotency** -- webhook delivery from any provider may be retried/duplicated by
   the provider; handling must be idempotent (this is also a mandatory test per
   `.claude/rules/testing.md`'s required-test matrix for payment/ledger operations
   triggered by webhook delivery).
5. **No transaction spans the network call.** Consistent with
   `.claude/rules/backend.md`'s transaction-boundary rules: persist the local state
   change (or a "pending" state) in its own transaction, then/separately make or
   respond to the external call -- never hold a DB transaction open across the webhook
   HTTP request/response cycle itself beyond what's needed to durably record receipt.

## 5. Credential vault concept

Source requirements module 16 calls for an "API credential vault." Architecturally:

- All third-party credentials (Zoom account credentials, SMS/WhatsApp/email provider
  API keys, payment gateway keys, object/video storage access credentials) are stored
  and retrieved exclusively through `integration-management` -- never duplicated into
  another domain's configuration or code.
- Credentials are modeled per scope: platform-level default credentials, and
  tenant-level override credentials (for tenants bringing their own Zoom account,
  custom SMS sender, etc., per source requirements modules 2 and 9).
- Credential storage must follow standard secret-handling practice: encrypted at rest,
  never logged (see `observability.md`, section 2), never returned in any API response
  body beyond what's needed for the owning tenant admin to confirm which integration
  is configured (e.g. a masked key/last-4 display), and rotated without requiring
  changes in any calling business domain.
- The concrete vault mechanism (e.g. which secrets-management technology) is an
  infrastructure decision -- see `deployment-architecture.md`'s open question on
  secrets management. This document describes the architectural requirement (single
  ownership, encrypted, scoped per tenant/platform), not a specific product.

## 6. Integration health-check concept

Source requirements module 16 calls for "Integration health checks." Architecturally:

- `integration-management` periodically (or on-demand) verifies connectivity/validity
  of each configured integration (platform-level and tenant-level) -- e.g. that a
  tenant's Zoom account token hasn't expired, that the SMS provider account is in
  good standing, that the payment gateway credentials are valid.
- Health-check results are:
  - Surfaced to Tenant Admins for their own tenant-level integrations.
  - Surfaced to Platform Admins for platform-level default integrations and, in
    aggregate, across tenants (for support/operational purposes).
  - Fed into the operational alerting/metrics layer described in `observability.md`,
    section 3 -- a failing integration health check is an operational signal, not only
    a UI status badge.
- Health-check failures must never silently degrade a business flow -- the calling
  business domain's `api` call to `integration-management` should surface a clear
  failure/unavailable response that the business domain can handle explicitly (e.g.
  queue for retry, surface a user-facing error).

## 7. What other domains may and may not do

| Allowed | Not allowed |
|---|---|
| Call `integration-management`'s `api` interfaces (e.g. `payment-management` calling `PaymentGatewayApi.confirmPayment(...)`) | Import/depend on a provider SDK directly inside a business domain module |
| Receive a translated domain event or `api` call from `integration-management` after a webhook is processed | Expose a webhook endpoint for a third-party provider from any business domain's `web` package |
| Request tenant-specific integration status/health via `integration-management`'s `api` | Store or read another integration's credentials directly from a business domain's `config`/`repository` |
| Consume webhook/delivery logs owned by `integration-management` for their own domain's reporting via a narrow `api` read method | Query `integration-management`'s repository/entities directly |

## 8. Open questions

- **Payment gateway provider** -- not selected (see `system-context.md`); the
  `PaymentGatewayApi` interface shape may need to accommodate provider-specific
  capabilities (e.g. split payments per source requirements module 12 phase 4) once a
  provider is chosen.
- **Object storage provider** -- not selected (see `system-context.md`). Secure video
  hosting: DynTube and Bunny.net Stream selected per tenant (ADR-018, section 9 below).
- **SMS and WhatsApp Business API provider(s)** -- not selected; source requirements
  name "WhatsApp official API" generically and "SMS provider" generically, no specific
  vendor.
- **Email/SMTP provider** -- Gmail/Google Workspace SMTP, confirmed for MVP-018 (see
  `docs/requirements/open-decisions.md` §22 for the daily-send-cap caveat and the
  no-code-change swap path via `MAIL_HOST`/`MAIL_PORT`).
- **Credential vault technology** -- depends on the secrets-management mechanism
  decision flagged in `deployment-architecture.md`; not decided here.
- **Health-check scheduling mechanism/frequency** -- conceptually described (section
  6); concrete scheduling is an implementation detail not yet decided.

## 9. Media & meeting provider connections (MEDIA-INT-01, ADR-018)

The first concrete realisation of the credential-vault concept (section 5) for media providers.

| Provider | Scope | Credential | Used for (phase) |
|---|---|---|---|
| Zoom | per teacher | OAuth (platform Zoom app) | live classes on the teacher's account (01); recording export (04) |
| YouTube | per teacher | OAuth (platform Google client) | own videos / upload (02); live (04) |
| Vimeo | per teacher | OAuth (platform Vimeo app) | own videos / upload (02); live (04) |
| DynTube | per tenant | API key (Tenant Admin) | secure video hosting (03) |
| Bunny.net Stream | per tenant account, **one library per teacher** | account API key (Tenant Admin); libraries created by the platform (MEDIA-INT-01A) | secure video hosting (03) |

- **Storage:** `media_provider_connection` (V60) - secrets only as one AES-256-GCM blob under
  `MEDIA_CREDENTIALS_ENCRYPTION_KEY` (separate from the payment key), bound to
  tenant/provider/owner; wiped on disconnect; rows never deleted. Tenant switches and the video
  host: `tenant_media_integration_settings`.
- **OAuth flow:** connect (authenticated teacher) -> provider consent -> one platform callback
  (`/api/v1/integrations/oauth/{provider}/callback`, state-protected, PKCE for Zoom/Google) ->
  pending result in Redis -> the same teacher confirms -> saved. Token refresh is server-side,
  5 minutes before expiry, under a per-connection Redis lock; a rejected refresh marks the
  connection NEEDS_RECONNECT.
- **Health checks:** "Test connection" (teacher and admin) calls a cheap read endpoint and
  updates `last_verified_at`/`last_error`; a failure never silently degrades scheduling - the
  live-class flow returns a specific `PROVIDER_*` 409.
- **Webhooks:** `/api/v1/integrations/webhooks/zoom` - Zoom signature + 5-minute timestamp
  window, URL-validation challenge, meeting events (idempotency ledger, tenant from our own
  `class_session`), `app_deauthorized` (the one cross-tenant lookup; disconnect-only).
- **Bunny.net (MEDIA-INT-01A, ADR-018 Amendment 1):** two Bunny APIs are used. The **account
  API** (`integrations.media.bunny.account-api-base-url`, default `https://api.bunny.net`,
  tenant's account key) only lists and creates video libraries - one per teacher, recorded in
  `bunny_teacher_library`. The **Stream API** (`api-base-url`, `https://video.bunnycdn.com`,
  per-library key read from the account API when needed) is for videos in MEDIA-INT-03. No
  per-library secret is stored. Creation commits a `PROVISIONING` row, calls Bunny with no
  transaction open, then activates or fails the row; a retry adopts a library left by an
  interrupted attempt (tag in its name) instead of duplicating it. Other domains call
  `BunnyLibraryApi.ensureTeacherLibrary`.
- **Privacy:** joining a Zoom class shares the student's name and email with the teacher's Zoom
  account; the privacy policy must disclose it.
- **Logging:** tokens/keys are never logged; `SecretMaskingJsonMembersCustomizer` masks them in
  JSON logs as a safety net.

### Operating: registering the provider apps

Configured through environment variables only (see `backend/.env.example`); every value is empty
by default and the provider then shows as "Unavailable". In staging/production the client
**secrets** and the encryption key are Docker Compose secrets and the client IDs / URLs are plain
`environment:` values (ADR-019); each environment registers its own provider apps.

1. **Callback URL** - set `MEDIA_OAUTH_CALLBACK_BASE_URL` to the public origin serving the API.
   Register `{origin}/api/v1/integrations/oauth/{zoom|youtube|vimeo}/callback` as the redirect URI
   in each provider app. Set `MEDIA_TENANT_URL_TEMPLATE` (e.g. `https://{subdomain}.<platform
   domain>`) and `MEDIA_OAUTH_ERROR_URL` (the public `/integration-error` page).
2. **Zoom** - Zoom Marketplace, *user-managed* OAuth app. Scopes: meetings read/write, meeting
   registrants write, user read, recordings read. Event subscription URL
   `{origin}/api/v1/integrations/webhooks/zoom` (meeting started/ended, app deauthorized); copy
   its secret token to `ZOOM_WEBHOOK_SECRET_TOKEN`. Client id/secret -> `ZOOM_OAUTH_CLIENT_ID`/
   `ZOOM_OAUTH_CLIENT_SECRET`. Zoom requires the deauthorization endpoint for published apps.
3. **Google (YouTube)** - Google Cloud OAuth client (web application), scope
   `youtube.readonly` now (`youtube.upload` in MEDIA-INT-02 needs Google app verification - start
   early). -> `GOOGLE_OAUTH_CLIENT_ID`/`GOOGLE_OAUTH_CLIENT_SECRET`.
4. **Vimeo** - Vimeo developer app, scopes `public private`. -> `VIMEO_OAUTH_CLIENT_ID`/
   `VIMEO_OAUTH_CLIENT_SECRET`.
5. **Encryption key** - `MEDIA_CREDENTIALS_ENCRYPTION_KEY` = Base64 of 32 random bytes; changing
   it makes every stored connection unreadable (teachers must reconnect).

## SMS / WhatsApp messaging providers (Wave 11, ADR-023)

- **Contract.** `integrationmanagement.api.MessagingChannelApi` — `availability(channel)` and
  `send(OutboundMessage)` for the *current* tenant (set by the caller from a trusted record,
  never passed as an argument). Email keeps its own `MessagingProviderApi` (platform SMTP).
- **Adapters.** One `gateway.messaging.MessagingProviderAdapter` per vendor; all vendor HTTP,
  payloads and error mapping live there. Only `SandboxMessagingAdapter` ships (no network,
  `app.messaging.sandbox-enabled`, local/test). Real vendors need their own ADR.
- **Accounts.** `tenant_messaging_account` (V66): one ACTIVE row per tenant+channel,
  replaced rows disabled (never deleted); credentials AES-256-GCM under the integrations key
  (`MEDIA_CREDENTIALS_ENCRYPTION_KEY`, AAD `messaging:{tenant}:{channel}:{id}`), write-only.
- **Failures.** Only `MessagingSendException` with a fixed code (`PROVIDER_TRANSIENT`
  retryable, `PROVIDER_REJECTED`, `CHANNEL_NOT_AVAILABLE`); unmapped adapter exceptions are
  treated as transient and never echoed.
- **Caller.** `notification-management`'s delivery dispatcher, outside any transaction, with
  the tenant context taken from the delivery row.

## Related

- `docs/adr/ADR-023-communications-and-messaging-providers.md`
- `docs/architecture/modular-monolith.md`
- `docs/architecture/video-content-security.md`
- `docs/architecture/payment-ledger.md`
