# ADR-023: Communications (broadcasts) and tenant messaging providers

- Status: Accepted (product-owner decisions D1–D3, 2026-10-01); judgment calls J1–J4 to be
  confirmed at PR review
- Date: 2026-10-01
- Wave: 11 (`docs/parity/waves/wave-11-plan.md`)
- Related: ADR-002 (shared-database tenancy), ADR-006 (tenant isolation), ADR-009 (RBAC),
  ADR-017 (tenant payment gateway accounts), ADR-018 (media provider connections),
  ADR-019 (production secrets)

## Context

`notification-management` shipped (MVP-018) with transactional email + in-app only: a
single-recipient outbox, no retry, no duplicate guard, no template authoring, and no
staff-facing messaging. The Klass parity brief (master instruction §26/§27) requires
in-app, email, SMS and WhatsApp channels, templates, broadcasts with audience targeting,
and tenant-configured messaging integrations — with provider logic behind adapters,
explicit tenant context on every async hop, and write-only secrets.

Three open items blocked a design: no SMS/WhatsApp vendor has been procured (PAR-21-03,
PAR-22-03); no permission row existed for Communications (open-decisions §22, PAR-12-04);
and "no retry" was an explicit MVP decision (open-decisions §22).

## Decisions

**D1 — Provider-agnostic adapters; sandbox only until a vendor ADR.**
`integration-management` exposes `api.MessagingChannelApi` (availability + send for the
*current* tenant). Each vendor is a `gateway.messaging.MessagingProviderAdapter`. The only
adapter shipped is `SANDBOX` (no network), enabled by `app.messaging.sandbox-enabled`
(local/test only; `false` in `application.yml`). Without an enabled adapter, SMS/WhatsApp
are unavailable everywhere and fail closed (409 `CHANNEL_NOT_AVAILABLE` at send; tenant
cannot configure the provider: 400 `MESSAGING_PROVIDER_NOT_AVAILABLE`). A real vendor
needs its own ADR, an adapter, and a migration widening
`ck_tenant_messaging_account_provider`.

**D2 — New `DomainArea.COMMUNICATIONS`.** Tenant Admin and Student Support V/C/E,
Read-only Auditor V. Teachers are ownership-scoped (own courses only). Editing system
message templates and recording WhatsApp template approval are additionally Tenant Admin
only. Provider credentials stay under `INTEGRATIONS`.

**D3 — Bounded retry.** At most 3 attempts; transient failures back off 1 min then 5 min;
the third transient failure, or any permanent failure, is `FAILED`. Applies to the new
`notification_delivery` rows and the existing `notification_outbox` (V68). Rows stuck in
`SENDING` are still failed forward, never re-sent. Supersedes open-decisions §22's
"no retry".

## Design consequences

- **Tables (V66–V68).** `tenant_messaging_account` (encrypted credentials, never deleted,
  one ACTIVE per tenant+channel); `communication_template` (archive-only, WhatsApp approval
  state machine); `broadcast` (append-only, DB trigger; unique `client_request_id` per
  tenant); `notification_delivery` (one row per broadcast+recipient+channel — the
  duplicate-send guard; masked destination only); `notification_channel_preference`
  (consent); outbox `attempt_count`/`next_attempt_at`/`dedupe_key`.
- **Async tenant context.** Dispatch claims rows with an explicitly named cross-tenant
  `FOR UPDATE SKIP LOCKED` query, then sets the tenant context from the **row's own
  `tenant_id`** and clears it in `finally`. The channel API then resolves *that* tenant's
  provider account — a delivery can never be sent with another tenant's credentials
  (tested).
- **No transaction spans a provider call.** Claim, send, finalize are separate beans and
  transactions, like the existing outbox.
- **Duplicate events.** Broadcast replay with the same `clientRequestId` returns the
  original (200); payment events carry `dedupe_key` (`EVENT:paymentId` / `REFUNDED:refundId`)
  with a partial unique index.
- **Consent.** WhatsApp is opt-in; SMS and broadcast email are opt-out. Transactional
  messages ignore preferences. WhatsApp broadcasts require a template whose WhatsApp status
  is `APPROVED`; editing the copy resets the approval.
- **Secrets.** AES-256-GCM, AAD `messaging:{tenant}:{channel}:{id}`; write-only; response
  carries a hint (`••••` + last 4 only when ≥ 12 chars); request `toString()` redacted;
  never in audit metadata or logs (tested via captured output). Provider failures surface
  only as fixed codes.

## Judgment calls (confirm at review)

- **J1 — Encryption key.** Messaging credentials reuse `MEDIA_CREDENTIALS_ENCRYPTION_KEY`
  (the integrations key) with a distinct AAD namespace rather than adding a production
  secret, so ADR-019's secret set is unchanged. Consequence: losing that key also requires
  re-entering messaging credentials (runbook updated).
- **J2 — Teachers: in-app and email only.** SMS/WhatsApp cost the tenant money and need
  consent tracking owned by staff.
- **J3 — Email stays on platform SMTP.** Tenant SMTP / sender domains need SPF/DKIM
  verification and are deferred.
- **J4 — Audience snapshot.** Recipients are resolved once at send time (cap 5 000); later
  enrollments do not receive an earlier broadcast. Scheduled broadcasts are deferred.

## Not decided here

Real SMS/WhatsApp vendors; provider delivery receipts (webhooks); automatic WhatsApp template
status sync; tenant SMTP; scheduled/recurring broadcasts; per-tenant send quotas.
