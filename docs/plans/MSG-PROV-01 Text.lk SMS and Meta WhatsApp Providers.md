# MSG-PROV-01 — Text.lk SMS and Meta WhatsApp Providers

**Status:** Approved (2026-10-02, full-stack implementation approved), with **ADR-024** (accepted).
Builds on Wave 11 / ADR-023.

## 1. Goal
A Tenant Admin can connect the institute's own **Text.lk** account (SMS) and **Meta WhatsApp
Cloud API** account (WhatsApp) under *Settings → Messaging*. Broadcasts and test sends then go
through those accounts instead of the local-only sandbox.

## 2. Roles
| Role | Interaction |
|---|---|
| Tenant Admin (`INTEGRATIONS` CREATE_EDIT) | Picks the provider per channel, enters sender / phone number ID, secret token, and (WhatsApp) template language; test-sends; disables. |
| Student Support, Auditor (`INTEGRATIONS` VIEW) | See status, provider, sender, masked hint, language. |
| Teacher, Student | No change. |

## 3. Acceptance criteria
1. Settings lists `TEXTLK` for SMS and `META_WHATSAPP` for WhatsApp when enabled in the
   environment (both on by default). `SANDBOX` is still shown only where sandbox is enabled.
2. Text.lk: requires `apiToken` and a sender ID (1–11 letters/digits, or a phone number of
   7–15 digits). Meta: requires `accessToken`, a phone number ID (5–20 digits) and a template
   language (`^[a-z]{2,3}(_[A-Z]{2})?$`), plus `appSecret` for webhook verification.
3. Secrets are never returned, logged, or audited; the page shows only the masked hint.
4. A broadcast SMS is sent with `POST https://app.text.lk/api/v3/sms/send`
   (`Bearer` token, `recipient`, `sender_id`, `type=plain`, `message`); the provider uid is
   stored as the delivery's provider message id.
5. A broadcast WhatsApp message is sent with
   `POST https://graph.facebook.com/{version}/{phoneNumberId}/messages` as a `template`
   message: name = the template's recorded Meta name, language = the account's language,
   body parameters = placeholder values in order of first appearance.
6. Numbers are normalised (ADR-024 V5); unusable numbers fail permanently.
7. Transient vendor errors are retried per ADR-023 D3; permanent ones fail at once (ADR-024 V6).
8. Test send works for both (Meta uses `hello_world`/`en_US`).
9. Each tenant's messages use only that tenant's account (existing cross-tenant test, extended).
10. A Meta account shows a callback URL and verify token. Meta's GET handshake succeeds only with
    the right token; a POST with a valid `X-Hub-Signature-256` updates `provider_status`
    (`SENT → DELIVERED → READ`, or `FAILED` + Meta error code) on that tenant's matching
    WhatsApp delivery only. Bad signature, unknown key, or a phone number ID that doesn't
    match → 401/ignored, nothing written. Tenant A's webhook can never update tenant B's row.
11. The broadcast detail page shows the provider status of each SMS/WhatsApp delivery
    ("Accepted by provider" for Text.lk, which has no delivery reports).

## 4. Files affected
Backend
- `db/migration/V69__add_textlk_and_meta_whatsapp_providers.sql` (new)
- `integrationmanagement/domain/TenantMessagingAccount.java` (provider values, `templateLanguage`)
- `integrationmanagement/gateway/messaging/MessagingProviderAdapter.java` (sender validation,
  settings hook, test-send hook)
- `integrationmanagement/gateway/messaging/TextLkMessagingAdapter.java` (new)
- `integrationmanagement/gateway/messaging/MetaWhatsAppMessagingAdapter.java` (new)
- `integrationmanagement/gateway/messaging/MessagingHttpClient.java` (new; never logs bodies)
- `integrationmanagement/gateway/messaging/PhoneNumbers.java` (new)
- `integrationmanagement/api/MessagingChannelApi.java` (`OutboundMessage.templateParameters`)
- `integrationmanagement/service/MessagingAccountService.java`,
  `integrationmanagement/web/MessagingIntegrationController.java`
- `integrationmanagement/web/MetaWhatsAppWebhookController.java`,
  `integrationmanagement/service/MetaWhatsAppWebhookService.java`,
  `integrationmanagement/domain/TenantMessagingWebhook.java` + repository (new)
- `integrationmanagement/api/MessagingDeliveryStatusEvent.java` (new)
- `identityaccessservice/config/SecurityFilterChainConfig.java` (permit the Meta GET handshake)
- `notificationmanagement/service/BroadcastTextRenderer.java` (ordered values),
  `NotificationDeliveryDispatchService.java`, a delivery-status listener, delivery view
- `application.yml` (`app.messaging.*` URLs, flags, country code)
- Tests: adapter tests against a local fake HTTP server, service/controller integration tests,
  cross-tenant test

Frontend
- `src/app/(tenant-admin)/tenant-admin/settings/messaging/page.tsx` (provider names, field
  labels, password inputs, language field, provider help text)
- `src/lib/api/communications.ts` (types), `e2e/communications.spec.ts`

Docs: ADR-024, this plan, wave-11 plan cross-reference, API docs for the messaging endpoints.

## 5. Database impact
V69 (additive): widen the provider CHECK; add nullable `template_language VARCHAR(15)` with a
CHECK that it's set exactly when `provider = 'META_WHATSAPP'`; new tenant-owned
`tenant_messaging_webhook` (tenant FK, globally unique `webhook_key`, unique per
tenant+provider); `notification_delivery` gains nullable `provider_status`,
`provider_status_at`, `provider_error_code` and a `(tenant_id, provider_message_id)` index.
No data change; existing rows are `SANDBOX` with null language.

## 6. API impact (additive)
- `PUT /api/v1/tenant-admin/integrations/messaging/{channel}`: optional `templateLanguage`.
- `GET …/messaging`: account view adds `templateLanguage`; provider options add
  `settingKeys` (non-secret fields, e.g. `templateLanguage`).
- New provider values `TEXTLK`, `META_WHATSAPP`. No breaking change.
- Account view for `META_WHATSAPP` adds `webhook { callbackUrl, verifyToken }` (managers only).
- Delivery views add `providerStatus`, `providerStatusAt`, `providerErrorCode`.
- New public `GET|POST /api/v1/integrations/webhooks/meta-whatsapp/{webhookKey}`.

## 7. Security impact
New public webhook path: GET token-checked, POST HMAC-verified with the tenant's own App Secret
before parsing; tenant chosen only by the stored key. New outbound calls to two vendor hosts (fixed base URLs from config, not tenant input → no
SSRF). Tokens are decrypted in memory for one call; no vendor response body is logged or
stored. Error messages to the UI are fixed text.

## 8. Tenant impact
None to resolution. Credentials stay per tenant; dispatch still sets tenant context from the
delivery row. Cross-tenant test extended to the new providers.

## 9. Tests
- Adapter tests with a fake HTTP server: request shape, auth header, number normalisation,
  every error class (transient vs permanent), no secret or body in logs.
- Service: per-provider validation (sender, language, credentials), language returned,
  secrets not returned.
- Dispatch: WhatsApp parameters ordered correctly; SMS uses Text.lk.
- Cross-tenant: tenant B's delivery never uses tenant A's account.
- Playwright: configure Text.lk and Meta accounts (mocked API), field labels, validation.

## 10. Out of scope
See ADR-024 "Not decided here".
