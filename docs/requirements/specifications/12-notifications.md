# Notifications

**Domain:** `notification-management` (Modules 15 + E) · **Portal(s):** Student, Teacher (activity feed only), Tenant Admin

## 1. Business purpose

Deliver email/SMS/WhatsApp/in-app notifications (reminders, alerts, transactional confirmations)
across every module without blocking the triggering request, since notification volume scales
with tenant count.

Source: `docs/requirements/source-requirements.md` Module 15.

## 2. Actors

- **Every role** is a notification recipient
- **Tenant Admin / Finance Staff** — compose bulk/segment messages, manage templates (exact sub-role authorization unspecified — see Open Decisions)
- **`notification-management`** (backend) — sole dispatcher, consuming events from every other domain; never called into directly by other domains' repositories

## 3. Preconditions

A triggering domain event exists (payment confirmed, exam result published, device limit
exceeded, access expiring, material uploaded, absence detected, etc.); recipient has a valid,
tenant-scoped contact channel.

## 4. Normal flow

1. A source domain (e.g. `payment-management`) publishes a domain event after committing its own transaction.
2. `notification-management` consumes the event asynchronously, carrying `tenant_id` explicitly in the event payload since async work does not inherit request-scoped tenant context.
3. `notification-management` resolves the applicable channel/template (email at MVP; SMS/WhatsApp Phase 2) via `integration-management`'s `api` interfaces — never embedding provider SDKs directly.
4. Notification is sent; delivery result is logged (Phase 2: delivery logs).
5. In-app notification appears in the recipient's `Notification Center`.

## 5. Alternative flows

- Delivery failure: Phase 2 requires failed-message retry; MVP failure behavior is unspecified (Open Decision).
- Bulk/segment messaging to a large recipient set: must not block request threads.
- WhatsApp template requiring approval (Phase 2): send blocked/queued until approved — see [22-whatsapp.md](./22-whatsapp.md).
- Marketing vs. transactional message separation (Phase 2): whether preference opt-outs suppress transactional notifications is unaddressed.

## 6. Authorization rules

**Gap to flag.** `user-roles-and-permissions.md` §2's permission matrix has **no dedicated row**
for notification templates, bulk/segment messaging, delivery logs, or preference-center
configuration. Which staff sub-role(s) may create/send bulk notifications, manage templates, or
view delivery logs is unspecified.

## 7. Tenant rules

Templates/delivery-logs/preferences are tenant-owned. Because this domain is entirely
event/async-driven, it does **not** inherit request-scoped tenant context — every triggering
event/job payload must explicitly carry `tenant_id`, applied the same way a request-time handler
would.

## 8. Acceptance criteria

- [ ] Given a payment is confirmed, then the resulting notification dispatch does not share a transaction with, or block, the payment-confirmation write.
- [ ] Given a notification-triggering event crosses a thread boundary (queued job), then `tenant_id` is explicitly carried in the payload and applied via the same structural tenant-filtering mechanism as request-time code.
- [ ] Given Tenant A's notification template/delivery-log/preference record, then Tenant B cannot read it.
- [ ] Given a Tenant Admin composes a bulk/segment message (Phase 2), then recipients are resolved server-side from tenant-scoped student segments, never a client-supplied recipient list.
- [ ] Each automation trigger (payment pending, class starting soon, absence, result published, access expiring, device limit exceeded, slip rejected, new material) is event-driven from its owning domain, not polled.
- [ ] Bulk-send operations expose `aria-busy`/`aria-live` announced start/completion/failure.

## 9. Audit requirements

**None specified.** Notification send/delivery is not on `.claude/rules/security.md`'s
mandatory-audit list. `notification-management` is described purely as an asynchronous consumer
of other domains' events, not as a domain whose own actions require audit entries.

## 10. MVP or later-phase classification

**MVP** — email + in-app channels only, async dispatch (FR-NM-1; `source-requirements.md` §5 MVP
list "Email notifications"). SMS/WhatsApp channels, templates, bulk/segment messaging, reminders,
preference center, delivery logs/retry, automation engine are all **Phase 2** (FR-NM-2 to FR-NM-5).

## UI-state and portal notes

- **Portal placement**: Student `Notifications > Notification Center`; Shared `Notification Preferences`; Tenant Admin `Communications > Templates`, `Bulk Messaging`, `Delivery Logs`.
- Empty state: "no notifications yet" vs. "no delivery logs match filter" — distinct copy.
- Email/SMS/WhatsApp templates are tenant-scoped branding assets — resolved the same tenant-scoped way as logo/branding, never a shared static default.

## Open decisions

- Which staff sub-role(s) may trigger bulk/segment SMS/WhatsApp campaigns or manage templates.
- Whether notification-preference opt-outs suppress transactional notifications or marketing only.
- No specific SMS/WhatsApp/email provider is selected — blocks concrete delivery-log schema design.
- MVP-level failure handling for a failed email send is undefined (only Phase 2's retry is named).
