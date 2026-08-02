# SMS

**Domain:** `notification-management` (dispatch), `integration-management` (provider credentials) · **Portal(s):** Tenant Admin (config), all portals (recipients)

## 1. Business purpose

Outbound transactional/reminder SMS (payment reminders, class reminders, device-limit alerts).

Source: `docs/requirements/source-requirements.md` Module 15/16.

## 2. Actors

- **`notification-management`** — dispatch
- **`integration-management`** — owns SMS provider credentials/adapter
- **Tenant Admin** — configures sender name/templates
- **Student / Teacher / Staff** — recipients

## 3. Preconditions

`notification-management`'s async email/in-app channel (MVP) as the established async-dispatch
pattern; `integration-management`'s `MessagingProviderApi` and credential vault. **SMS provider
is explicitly not selected.**

## 4. Normal flow

1. A domain event (payment pending, class starting soon, absence alert, etc.) triggers `notification-management` asynchronously.
2. It resolves a tenant-specific SMS sender name/template if configured, else platform default.
3. Calls `integration-management`'s `MessagingProviderApi`.
4. The SMS adapter sends via the provider.
5. Delivery status/log recorded, visible in `Communications > Delivery Logs`.

## 5. Alternative flows

- Provider health-check fails: surfaced as an operational signal; message queued for retry rather than silently dropped.
- Tenant plan lacks SMS entitlement: feature-flag-gated.
- Bulk/segment SMS: must respect marketing-vs-transactional separation and stay strictly tenant-scoped.
- Duplicate delivery-status webhook from the provider: idempotent handling.

## 6. Authorization rules

**Open Decision** — which staff sub-role(s) may trigger bulk/segment SMS campaigns is not
specified anywhere; no dedicated permission-matrix row exists for Communications.

## 7. Tenant rules

Templates, delivery logs, and preference records are tenant-owned; cross-tenant isolation test
required. Tenant-specific SMS sender name is tenant-scoped config resolved via
`integration-management`. Async job payload must explicitly carry `tenant_id`.

## 8. Acceptance criteria

- [ ] Notification dispatch is async and does not share a transaction with the triggering privileged action.
- [ ] Cross-tenant negative test: a Tenant A template/delivery-log record is not readable by Tenant B.
- [ ] Failed sends are retried per a defined retry policy, logged distinctly from successful sends.
- [ ] Plan without SMS entitlement gets a server-side block on the send path, not just a hidden UI control.
- [ ] A bulk-send job triggered for Tenant A must never pull Tenant B's student contact list.

## 9. Audit requirements

**None specified** as a standalone bullet — delivery logs and failed-message retry are
operational logs, not the security-sensitive audit trail defined in `security.md`. Whether
bulk-messaging actions need a distinct audit entry is unresolved.

## 10. MVP or later-phase classification

**Phase 2.** FR-NM-2; `module-catalog.md` lines 164, 176; `source-requirements.md` line 651.

## UI-state and portal notes

- **Portal placement**: Tenant Admin `Communications > Templates`, `Bulk Messaging`, `Delivery Logs`, plus `Integrations > Integration Settings` for credential config.
- Bulk-send operations require `aria-busy`/`aria-live` announced start/completion/failure.
- SMS/WhatsApp delivery-status chip vocabulary is not defined in `docs/ui-ux/component-library-spec.md` §2.10.

## Open decisions

- Which staff sub-role(s) may send SMS/manage templates.
- Whether bulk-messaging actions need an audit entry.
- No SMS provider selected.
