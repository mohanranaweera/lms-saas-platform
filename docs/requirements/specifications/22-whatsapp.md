# WhatsApp

**Domain:** `notification-management` (dispatch), `integration-management` (WhatsApp Business API credentials) · **Portal(s):** Tenant Admin (config), all portals (recipients)

## 1. Business purpose

Outbound templated WhatsApp Business API notifications, same rationale as SMS, with
provider-governed template pre-approval.

Source: `docs/requirements/source-requirements.md` Module 15, "Template approval for WhatsApp."

## 2. Actors

Same as [21-sms.md](./21-sms.md), plus the WhatsApp-specific template-approval step.

## 3. Preconditions

Same as SMS; additionally, WhatsApp Business API provider selection is open; a template
pre-approval workflow with the provider must be modeled — its exact state machine is not
specified anywhere.

## 4. Normal flow

Similar to SMS, but templates must be pre-approved by the WhatsApp provider before use — Tenant
Admin submits a template via `Communications > Templates`, tracked through an approval status
(mechanism unspecified) before it can be used for sends.

## 5. Alternative flows

- Template rejected by the provider: cannot be used for sends, distinct status shown to Tenant Admin.
- Delivery failure due to recipient opt-in/24-hour session-window constraints (a standard WhatsApp Business API rule): not addressed in any source document (Open Decision).
- Same cross-tenant/health-check/idempotency/retry alternative flows as SMS.

## 6. Authorization rules

Same gap as SMS: no explicit role mapping for who approves WhatsApp templates or triggers bulk
WhatsApp sends.

## 7. Tenant rules

Same shape as SMS — tenant-scoped sender/template config, async job must carry explicit
`tenant_id`, webhook delivery-status callbacks from WhatsApp must resolve tenant from the
platform's own message-id record, never from the webhook payload.

## 8. Acceptance criteria

- [ ] Same async-dispatch, cross-tenant isolation, and retry criteria as SMS.
- [ ] A template not yet in an approved state cannot be selected/used for an outbound send.
- [ ] Template approval status is tenant-scoped and visible to the configuring Tenant Admin only.

## 9. Audit requirements

Same as SMS — not on the mandatory list; delivery/failure logs are operational, not
security-audit.

## 10. MVP or later-phase classification

**Phase 2.** FR-NM-2; `source-requirements.md` line 652.

## UI-state and portal notes

- **Portal placement**: same Communications screens as SMS.
- No UX flow documents what the template-approval-pending UI looks like — WhatsApp Business API template approval is an external Meta-side async process; the UI needs a way to reflect "pending Meta approval" vs. "approved" vs. "rejected," currently undocumented.
- WhatsApp Official API credentials are owned by `integration-management`, never embedded directly by `notification-management`.

## Open decisions

- Exact WhatsApp template-approval workflow/state machine.
- Opt-in/consent handling for the 24-hour session-window constraint.
- Which staff sub-role(s) may approve templates or trigger bulk sends.
- No WhatsApp provider selected.
