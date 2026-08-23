# ADR-011: Payment Webhook — Authentication and Tenant-Resolution Carve-Out

## Status

**Accepted (2026-08-23)**, by the project owner, in the same session that surfaced this
gap via a six-specialist review of the completed MVP-010 module. The underlying code was
already merged and passing tests before this ADR existed; this decision falls squarely
under root `CLAUDE.md`'s explicit change-controlled list ("authentication architecture",
"multi-tenancy strategy"), and this acceptance is the explicit human sign-off
`.claude/rules/git-workflow.md` requires before a PR touching a change-controlled area
may merge — that condition is now satisfied for the code this ADR describes.

## Context

Every other authenticated request in this platform resolves tenant identity exactly
once, at the edge (`TenantResolutionFilter`, from the request's subdomain), per
`.claude/rules/tenancy.md`: *"Tenant identity is resolved exactly once per request, at
the authentication/edge layer... never re-derived inside individual business modules."*

The payment gateway's confirmation webhook (`POST /api/v1/integrations/webhooks/payment`)
cannot follow this pattern: a webhook has no subdomain to resolve from (gateways call a
single, fixed platform URL) and no JWT/session (the caller is the gateway, not an
authenticated platform user). PAY-2's own design requires this endpoint to exist and to
correctly attribute the confirmation to the right tenant regardless.

## Decision

Two modifications to shared, platform-wide security infrastructure:

1. **`SecurityFilterChainConfig`**: add `/api/v1/integrations/webhooks/**` to the
   `permitAll()` route list — no `Authorization` header is required or checked by Spring
   Security for this path.
2. **`TenantResolutionFilter`**: add the same path to `shouldNotFilter` — the filter does
   not attempt subdomain-based tenant resolution for this path (there is no subdomain to
   resolve from a server-to-server webhook call in general, and even where there is one,
   it must not be trusted — see below).

In place of the platform's normal edge-resolved tenant context, `integration-management`
and `payment-management` cooperate to resolve tenant identity **from trusted server-side
state, downstream of authentication**, never from the request itself:

- `WebhookSignatureVerifier` performs a fail-closed HMAC-SHA256 signature check (rejects
  on any missing/blank/misconfigured secret, never falls back to "accept unsigned")
  before any parsing of the webhook body or any state change.
- `PaymentConfirmationService.confirmByGatewayReference` looks up the `Payment` row via
  `PaymentRepository.findByGatewayReferenceAcrossTenants` — the one deliberately named,
  reviewable cross-tenant repository bypass this module introduces (per
  `.claude/rules/backend.md`'s "any repository method that bypasses the structural
  filter must be explicitly named" rule) — and sets `TenantContextHolder` to **that
  found row's own `tenant_id`**, inside a `try`/`finally` that clears it afterward, for
  the remainder of that one confirmation transaction.
- `PaymentWebhookPayload` (the deserialized webhook body) structurally has no tenant
  field at all — there is nothing in the payload a forger could set to claim a
  false tenant, even before signature verification is considered.

## Consequences

**Positive**

- The only two things that can determine which tenant a confirmation applies to are (a)
  a valid HMAC signature over the payload, and (b) the platform's own prior `Payment`
  row for that `gateway_reference` — never anything the caller directly asserts. A
  tampered-signature webhook, or a webhook for an unresolvable `gateway_reference`,
  changes no state (tested:
  `PaymentAndLedgerIntegrationTest#anUnsignedWebhookIsRejectedAndCreatesNoStateChange`,
  `PaymentCrossTenantIntegrationTest#aWebhookWithATamperedSignatureIsRejectedAndCreatesNoStateChange`).
- `payment.gateway_reference`'s uniqueness is platform-global (not tenant-scoped) by
  necessity — this lookup has no tenant context to scope by until the row is found. This
  is itself flagged as its own explicit, accepted exception to `tenancy.md`'s
  tenant-scoped-uniqueness default (see `V19`'s migration comment); revisit at real
  gateway selection time to confirm the chosen gateway's reference is genuinely
  platform-global, not merchant-account-scoped.

**Negative / trade-offs accepted**

- This is the one code path in the platform where `TenantContextHolder` is set from the
  service layer rather than the edge filter layer — a deliberate, narrow exception to
  the "resolved exactly once, at the edge" framing, not an extension of it. Any future
  reviewer auditing "does every module resolve tenant at the edge" needs to know this
  one path is the documented exception, not a missed case.
- `/api/v1/integrations/webhooks/**` is unauthenticated at the Spring Security layer —
  its entire safety rests on the HMAC check inside the controller/service, not on the
  framework's usual `Authorization`-header gate. A future refactor that touches
  `SecurityFilterChainConfig` broadly must not accidentally widen this `permitAll()`
  pattern to cover more than this one path.

## Alternatives considered

- **A per-tenant webhook URL** (e.g. `/api/v1/integrations/webhooks/payment/{tenantSlug}`),
  letting `TenantResolutionFilter` resolve tenant from the URL segment like every other
  request. Rejected: most real payment gateways call back to a single, merchant-account-
  level fixed URL configured once at integration setup, not a per-tenant URL the gateway
  would need to be told about per transaction — and even if a gateway supported it, a
  URL segment is exactly the kind of "trust the request" signal `tenancy.md` warns
  against (any URL segment is guessable/spoofable) unless independently re-verified
  anyway, which would make the extra URL complexity redundant with the signature-then-
  lookup approach already required.
- **Require the webhook to be signed with a per-tenant secret**, letting the correct
  secret itself imply the tenant. Rejected for the current fake/test adapter (no real
  gateway selected yet, per plan §21 item 17) — deferred to gateway-selection time as a
  possible hardening if the chosen real gateway supports per-merchant-account signing
  secrets natively.

## Required follow-up if accepted

- Confirm at real payment-gateway selection time whether the chosen gateway's reference
  values and/or signing secret can be made tenant- or merchant-account-scoped, and
  revisit the global-uniqueness trade-off above if so.
- Any future change to `SecurityFilterChainConfig`'s route list or
  `TenantResolutionFilter`'s exclusion list should be checked against this ADR, not
  just the diff in isolation.

## Related

- `docs/plans/MVP-010 Order and Payment Foundation.md` §9, §14-15
- `docs/api/payment-management.md` ("Webhook ownership")
- `.claude/rules/tenancy.md`, `.claude/rules/backend.md`
- `SecurityFilterChainConfig.java`, `TenantResolutionFilter.java`,
  `WebhookSignatureVerifier.java`, `PaymentConfirmationService.java`
