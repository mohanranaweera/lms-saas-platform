# ADR-003: Centralized Payment Collection First (Payment Roadmap Phase 1)

## Status

Accepted (confirmed in `CLAUDE.md` "Payment roadmap" and `.claude/rules/payments.md`).

## Context

The platform must collect payment for course enrollments across many tenants
(institutes). There are two broad models for who "owns" the payment relationship
with the student:

1. **Tenant-specific payment accounts from day one** — each tenant connects its own
   payment gateway account; the platform routes payments directly to the tenant.
2. **Platform-centralized collection first** — the platform collects all student
   payments into one centralized flow, then settles tenant/tutor payouts internally,
   with tenant-specific routing and split payments introduced later.

Tenant-specific payment accounts (option 1) require the platform to have already
solved settlement accounting, per-tenant gateway onboarding/KYC, and payout compliance
— none of which is validated yet. They also assume the payment gateway supports
split/marketplace payments, which has not been confirmed for any specific provider
(no payment provider has been selected at this stage — this is intentional; see
"Open questions").

## Decision

Adopt the four-phase payment roadmap exactly as stated in `CLAUDE.md`, and do not
skip or reorder phases without a new ADR:

1. **Phase 1 (MVP):** Platform centrally collects all student payments. Orders and
   Payments are tenant-aware (`tenant_id` resolved from trusted context) but flow
   through one platform-level payment integration.
2. **Phase 2:** Tenant/tutor settlements — the platform calculates and pays out
   tenant/tutor commissions internally via `ledger-settlement-management`, without
   the tenant holding its own gateway account.
3. **Phase 3:** Tenant-specific payment accounts — tenants may connect their own
   payment account; the platform routes payment configuration per tenant.
4. **Phase 4:** Split payments / marketplace model — **only if the selected payment
   gateway supports native split/marketplace payouts**. This is explicitly
   conditional, not guaranteed.

Enrollment activation, in every phase, is governed by `.claude/rules/payments.md` and
never by order state alone or by a frontend success page — this rule does not change
across phases.

## Consequences

**Positive**

- The platform can launch and validate the product (course sales, enrollment,
  attendance, exams) without first solving multi-tenant payment gateway onboarding,
  KYC, or split-payment compliance — those are deferred to when they're actually
  needed.
- A single, centralized payment integration in Phase 1 means one integration surface
  to secure, test, and audit (via `integration-management`), rather than N per-tenant
  gateway integrations from the start.
- Ledger/settlement logic (Phase 2) can be built and proven against a simple
  centralized-collection model before adding the complexity of tenant-routed funds
  (Phase 3) or gateway-split payments (Phase 4).

**Negative / trade-offs accepted**

- Tenants do not control their own payment gateway relationship in Phase 1/2 — all
  funds flow through the platform's account first. This is a deliberate, accepted
  trade-off, not an oversight; it must be communicated as a product/business
  constraint to tenants (see `docs/product-vision.md`), not treated as a gap.
- Settlement accounting (Phase 2) becomes the platform's operational responsibility —
  commission calculation, gateway-fee tracking, and payout must be correct and
  auditable before tenants would accept losing direct control of their funds.
- Phase 4 (split payments) is not guaranteed to be reachable — it depends entirely on
  a future, unselected payment gateway's capabilities. Do not commit to a split-payment
  timeline until a gateway is selected and confirmed to support it.

## Open questions (explicitly not decided here)

- Which payment gateway(s) will be integrated — not decided; must not be invented in
  any documentation or code. Track as an open item in
  `docs/requirements/functional-requirements.md`.
- Exact commission/settlement percentages and schedules — business decision, not yet
  supplied.
- Compliance/KYC requirements for Phase 3 tenant-specific accounts — depends on
  gateway selection above.

## Related

- `docs/architecture/payment-ledger.md`
- `docs/architecture/enrollment-access.md`
- `.claude/rules/payments.md`
