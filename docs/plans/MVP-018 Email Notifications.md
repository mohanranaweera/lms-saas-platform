# MVP-018 Email Notifications — Module Plan

**Post-implementation note (added after a post-ship multi-agent review + remediation
pass):** this module has since shipped, and several implementation-time corrections mean
§5 and §8 below are no longer fully authoritative as written — read them as the
*approved starting design*, not the as-built schema. In particular: `notification_outbox`
gained a `SENDING` intermediate status (`PENDING → SENDING → SENT|FAILED`, not this
plan's original two-state `PENDING → SENT|FAILED`) and a `claimed_at` column
(`V29__add_notification_outbox_claimed_at_reconciliation.sql`, additive — `V28` below was
never edited), and a `GET /api/v1/notifications/unread-count` endpoint was added that does
not appear in §10's contract table. See `docs/requirements/open-decisions.md`'s
notification-management entries for the full, dated rationale for every deviation, and
`docs/api/notification-management.md` / `docs/architecture/modular-monolith.md`'s
`notification-management` worked example for the as-shipped contract and design.

Status: **Approved plan, not yet implemented.** Produced via `plan-module`, using
`product-requirements-analyst`, `solution-architect`, `database-architect`,
`security-reviewer`, `qa-test-engineer`, `ui-ux-reviewer`, and `payment-ledger-specialist`
(this module touches `payment-management`'s event contracts, so unlike MVP-017 it was
in scope) — all read-only analysis, no application code was written.

Source of truth: GitHub issue [#18](https://github.com/mohanranaweera/lms-saas-platform/issues/18)
("[MVP] Module 18: Email notifications"), cross-checked against
`docs/planning/product-backlog.md` (NOTIF-1/NOTIF-2), `docs/requirements/specifications/12-notifications.md`,
`docs/requirements/functional-requirements.md` (FR-NM-1..5), `docs/requirements/module-catalog.md`,
`docs/requirements/open-decisions.md`, `docs/architecture/modular-monolith.md`,
`docs/architecture/integration-architecture.md`, `docs/architecture/multi-tenancy.md`,
`docs/planning/mvp-release-plan.md` (Wave 1), `.claude/rules/architecture.md`,
`.claude/rules/backend.md`, `.claude/rules/tenancy.md`, `.claude/rules/security.md`,
`.claude/rules/payments.md`, and the shipped `payment-management` / `exam-management` /
`identity-access-service` / `integration-management` code and migrations (V1–V27).

## Grounding note — this module is later and larger than originally planned

`docs/planning/mvp-release-plan.md` explicitly called for `NOTIF-1` (dispatch
infrastructure only) to be pulled forward into **Wave 1**, immediately after platform
bedrock, because "nearly every prior module... has a soft dependency *back* on this
story for their async side effects." That pull-forward did not happen: the backend has
no `com.lms.notificationmanagement` package today, and Waves 2/4/5 (Course, Attendance,
Exams, Tenant Admin Dashboard) shipped without it. This plan builds `NOTIF-1` + `NOTIF-2`
from scratch, later than the release plan intended — noted here, not silently absorbed,
per this project's `open-decisions.md` convention of logging deviations rather than
treating them as if they were the original plan.

Three already-shipped, already-published, currently-unconsumed domain events exist
specifically anticipating this module as their consumer or a sibling consumer
(`payment-management.api.PaymentConfirmedEvent`/`PaymentRejectedEvent`/`PaymentRefundedEvent`),
which meaningfully de-risks this build — the "publish" side of the pipeline already
exists and is already tested by payment-management's own suite; this module only adds
the "consume" side.

---

## 1. Business goal

Build the asynchronous, event-driven, tenant-scoped notification dispatch backbone
(`NOTIF-1`) that other domains' side effects rely on, then deliver the MVP-scoped slice
on top of it (`NOTIF-2`): transactional email for payment outcomes, and a persistent
in-app Notification Center so users have a durable record of what they were notified
about. This is explicitly infrastructure-plus-thin-vertical-slice, not the full
Communication Module (Module 15) or Notification Automation Engine (Module E) —
SMS/WhatsApp channels, templates authoring UI, bulk/segment messaging, delivery-log
screens, retry/backoff, and the automation-trigger catalog (class-starting-soon,
absence, access-expiring, device-limit-exceeded, slip-rejected, new-material) are all
explicitly Phase 2 per `functional-requirements.md` FR-NM-2..5 and the issue's own
"Out of scope" list.

## 2. Roles and permissions

No dedicated `DomainArea` exists for notifications in `identityaccessservice.api.DomainArea`
today, matching `open-decisions.md` §2's already-logged gap ("no dedicated
permission-matrix row exists for templates, bulk/segment messaging, or delivery logs").
This module's actual scope does not need one:

| Capability | Who | Authorization mechanism |
|---|---|---|
| Receive an in-app notification | Every role that is a payment payer (Student today; MVP triggers are payment-only) | Recipient is server-derived from the triggering domain event, never client-chosen |
| Read own Notification Center list | The authenticated recipient only | Session-derived `(tenant_id, recipient_user_id)` — no `DomainArea` grant needed, this is a self-service "my own records" read, the same authorization shape as a student's own exam-attempt history |
| Mark own notification read | The authenticated recipient only | Same self-scoping; mutation is limited to `read_at` on the caller's own row |
| Author/edit `notification_template` rows | **Unresolved — explicit open decision, not decided here** (issue: "which staff sub-role(s) may manage templates or trigger sends is unspecified") | N/A — no authoring endpoint exists in this MVP; see §6 and §21 |
| View delivery logs / bulk-send / SMS/WhatsApp | Nobody — Phase 2 | N/A |

Teacher's "activity-feed-only subset" (per the issue's frontend requirements and
`12-notifications.md`) is the same self-scoping rule applied to whatever notification
rows exist for a Teacher recipient — MVP-018 ships zero Teacher-triggering events (see
§6), so this is a real screen with a real, empty-by-construction list at launch; not a
placeholder.

## 3. Preconditions

- The triggering domain event has already been durably committed by its owning
  domain's own transaction (`payment-management`'s `PaymentConfirmationService`/
  `RefundService`) — notification-management never initiates a notification from
  anything that hasn't already committed.
- The event's `tenantId` is present and non-null (structural — every existing event
  already carries it).
- A resolvable recipient exists: the payer (`studentId`) must resolve to a live
  `tenant_user` row in the same tenant via `UserProvisioningApi.findTenantUserSummaries`
  — if it doesn't (deleted/never-provisioned account), dispatch fails closed for that
  row only (marked `FAILED`, logged, no exception propagates to the poller loop).
- At least one `notification_template` row exists for `(tenant_id, template_key)` — see
  §6/§21 for the unresolved question of how that row ever gets created in this MVP.

## 4. User flows

**Flow A — Payment confirmed → email + in-app notification**
1. `PaymentConfirmationService` confirms a payment inside its existing `@Transactional`
   method (unchanged), publishes `PaymentConfirmedEvent` (now additionally carrying
   `studentId`, `amount`, `currency` — see §9.3) via `ApplicationEventPublisher`, and
   commits.
2. `notification-management`'s `@TransactionalEventListener(phase = AFTER_COMMIT)`
   fires on the same (request) thread, after commit — no shared transaction with step 1.
3. The listener inserts one `notification_outbox` row (`tenant_id`, `event_type =
   'PAYMENT_CONFIRMED'`, `recipient_user_id = studentId`, `payload` JSONB with
   `amount`/`currency`/`paymentId`, `status = 'PENDING'`) in its own short transaction,
   then returns. The triggering request is not blocked or delayed beyond this one
   fast insert.
4. On its own schedule (decoupled from any request), the dispatch poller claims
   `PENDING` rows (`SELECT ... FOR UPDATE SKIP LOCKED`, safe under multiple horizontally
   scaled app instances per `architecture.md`'s statelessness rule), and for each row:
   sets `TenantContextHolder` to that row's own `tenant_id`, resolves the recipient's
   email via `UserProvisioningApi.findTenantUserSummaries`, resolves the
   `(tenant_id, template_key)` template row, renders subject/body, calls
   `MessagingProviderApi.sendEmail(...)`, writes `status = SENT`/`FAILED` +
   `dispatched_at`, inserts one `in_app_notification` row, then clears
   `TenantContextHolder` in a `finally` block regardless of outcome.
5. The student's next Notification Center load (or next poll/refresh) shows the new
   `in_app_notification` row with a "no notifications yet"-replacing entry, and — if the
   page is already open — a live-region Toast announcement per the shared pattern.

**Flow B — Payment rejected / refund processed** — identical to Flow A, triggered by
`PaymentRejectedEvent`/`PaymentRefundedEvent` respectively, different `template_key`.

**Flow C — Student reads the Notification Center**
1. Student opens `Student > Notifications`.
2. Frontend calls the list endpoint; backend returns the caller's own
   `(tenant_id, recipient_user_id)`-scoped, newest-first, paginated rows.
3. Empty state ("no notifications yet") renders if the list is empty; otherwise a list
   with unread rows visually and programmatically distinguished (not color-only).
4. Student marks a row read (click/keyboard); backend updates that row's `read_at` if
   and only if it belongs to the caller; frontend reflects the confirmed server result,
   not an optimistic-only client change.

**Flow D — Teacher activity feed**
Same backend endpoint/read path as Flow C, scoped to a Teacher recipient — no
Teacher-triggering event exists in this MVP's wiring (see §6), so this screen is
real but starts empty for every tenant at launch.

## 5. Acceptance criteria

Restated from the issue, all still binding:
- [ ] A triggering domain event (payment confirmed/rejected/refunded) → the resulting
      notification dispatch does not share a transaction with, or block, the triggering
      write.
- [ ] A notification event crossing a thread boundary → `tenant_id` is explicitly
      carried in the payload and applied via the same structural tenant-filtering
      mechanism (`TenantContextHolder` + `TenantAwareRepository`) as request-time code —
      never inherited/ambient.
- [ ] Tenant A's `notification_template`/`notification_outbox`/`in_app_notification`
      rows are unreadable by Tenant B.
- [ ] A dispatched notification appears in the recipient's own in-app Notification
      Center, and in no one else's — including another user in the *same* tenant
      (a same-tenant BOLA case, distinct from the cross-tenant case above).
- [ ] Empty state ("no notifications yet") renders correctly for a recipient with zero
      rows.

Additive, from this planning pass (not inventing new business scope — clarifying how
the above are actually verified):
- [ ] A poller crash/restart between an outbox row's `PENDING` insert and its dispatch
      does not lose the notification — the row is still `PENDING` and gets picked up on
      the next poll (crash-recovery property of the outbox pattern itself; see §9.4).
- [ ] A `FAILED` row is never automatically retried (per the issue's own explicit "do
      not silently implement a retry mechanism" instruction) — it is terminal.
- [ ] `TenantContextHolder` is provably cleared after both a normal and an
      exception-throwing dispatch attempt (see §18).

## 6. Out-of-scope items

Explicitly out, per the issue and `functional-requirements.md` FR-NM-2..5:
- SMS/WhatsApp channels, bulk/segment messaging, delivery-log screens, notification
  preference center, retry/backoff on failed sends, the automation-trigger catalog
  (class-starting-soon, absence, access-expiring, device-limit-exceeded, slip-rejected,
  new-material) — all Phase 2.
- Template-authoring UI/endpoint of any kind — no staff sub-role is authorized for it in
  this MVP (open decision, unresolved — see §21).
- Wiring `ExamResultPublishedEvent` — `functional-requirements.md` FR-NM-5 places
  "result published" inside the Phase-2 automation-engine trigger list; the MVP scope is
  the payment outcomes only. (Note: `12-notifications.md` itself is internally
  inconsistent — its own §4/§8 name "result published" as if MVP-scope with no phase
  qualifier, while its §10 correctly cites FR-NM-5 as Phase 2 — flagging this
  documentation contradiction for a future doc-reconciliation pass rather than resolving
  it by assumption here.)
- Wiring `CoursePriceChangedEvent`/`MaterialDeletedEvent` — both already carry a javadoc
  contract naming `audit-log-management`, not `notification-management`, as their
  intended consumer; repurposing them here would be a scope decision this plan does not
  make unilaterally.
- Any change to `payment-management`'s service/repository/domain/web layers or its REST
  contract (`docs/api/payment-management.md`) beyond the two additive event-record field
  sets in §9.3 — no ledger, refund-eligibility, or terminal-row-mutation logic changes.
- A new `identity-access-service` API — `UserProvisioningApi.findTenantUserSummaries`
  already provides everything needed.

## 7. Domain model

New domain: `com.lms.notificationmanagement`, per `modular-monolith.md`'s confirmed
domain list (`notification-management` is already a named confirmed domain — no ADR
needed to introduce the package).

```
com.lms.notificationmanagement
|-- api        # (none needed outward at MVP — no other domain calls into this one synchronously)
|-- web        # NotificationController (Notification Center list/mark-read)
|-- service    # NotificationOutboxService (AFTER_COMMIT listeners), NotificationDispatchPoller,
|              # NotificationTemplateRenderer, NotificationCenterService
|-- domain     # NotificationOutbox, NotificationTemplate, InAppNotification (JPA entities)
|-- repository # NotificationOutboxRepository, NotificationTemplateRepository,
|              # InAppNotificationRepository (tenant-aware where applicable — see §8)
`-- config     # NotificationSchedulingConfig (@Scheduled poller trigger), mail properties
```

`integration-management` gains: `api.MessagingProviderApi` (new interface),
`mail.SmtpMessagingProviderApi` (new adapter, `spring-boot-starter-mail`-backed).

`payment-management` gains: `studentId` (+ `amount`/`currency` where not already
present) as additive fields on three existing event records — see §9.3.

## 8. Database design

Migration `V28__create_notification_management_schema.sql` (next after V27), following
V26's established conventions exactly (UUID PK with no DB-side default — app-generated
UUIDv7; `created_at`/`updated_at` `TIMESTAMPTZ NOT NULL`; composite `(tenant_id, id)`
FKs to other tenant-owned tables; not-blank `CHECK`s on free-text columns).

Incorporates `database-architect`'s review corrections against the issue's literal
column list:

```sql
CREATE TABLE notification_outbox (
    id                 UUID PRIMARY KEY,
    tenant_id          UUID NOT NULL REFERENCES tenant (id),
    event_type         VARCHAR(30) NOT NULL,
    recipient_user_id  UUID NOT NULL,
    payload            JSONB NOT NULL,
    status             VARCHAR(10) NOT NULL DEFAULT 'PENDING',
    created_at         TIMESTAMPTZ NOT NULL,
    dispatched_at      TIMESTAMPTZ,

    CONSTRAINT fk_notification_outbox_recipient FOREIGN KEY (tenant_id, recipient_user_id)
        REFERENCES tenant_user (tenant_id, id),
    CONSTRAINT ck_notification_outbox_event_type
        CHECK (event_type IN ('PAYMENT_CONFIRMED', 'PAYMENT_REJECTED', 'PAYMENT_REFUNDED')),
    CONSTRAINT ck_notification_outbox_status CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    -- Mirrors exam_answer's marked-fields-together precedent (V26): a dispatched row
    -- must carry its timestamp, a pending one must not.
    CONSTRAINT ck_notification_outbox_dispatched_together CHECK (
        (status = 'PENDING' AND dispatched_at IS NULL) OR
        (status IN ('SENT', 'FAILED') AND dispatched_at IS NOT NULL)
    )
);

-- Dispatch-poller claim query: SELECT ... WHERE status = 'PENDING' ORDER BY created_at
-- FOR UPDATE SKIP LOCKED, safe across multiple horizontally scaled app instances.
CREATE INDEX idx_notification_outbox_tenant_status_created_at
    ON notification_outbox (tenant_id, status, created_at);

CREATE TABLE notification_template (
    id           UUID PRIMARY KEY,
    tenant_id    UUID NOT NULL REFERENCES tenant (id),
    template_key VARCHAR(60) NOT NULL,
    subject      TEXT NOT NULL,
    body         TEXT NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL,
    created_by   UUID,
    updated_by   UUID,

    CONSTRAINT uq_notification_template_tenant_key UNIQUE (tenant_id, template_key),
    CONSTRAINT ck_notification_template_subject_not_blank CHECK (btrim(subject) <> ''),
    CONSTRAINT ck_notification_template_body_not_blank CHECK (btrim(body) <> '')
);

CREATE TABLE in_app_notification (
    id                 UUID PRIMARY KEY,
    tenant_id          UUID NOT NULL REFERENCES tenant (id),
    recipient_user_id  UUID NOT NULL,
    title              TEXT NOT NULL,
    body               TEXT NOT NULL,
    read_at            TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_in_app_notification_recipient FOREIGN KEY (tenant_id, recipient_user_id)
        REFERENCES tenant_user (tenant_id, id),
    CONSTRAINT ck_in_app_notification_title_not_blank CHECK (btrim(title) <> ''),
    CONSTRAINT ck_in_app_notification_body_not_blank CHECK (btrim(body) <> '')
);

-- Notification Center's own read pattern: newest-first, own recipient only.
CREATE INDEX idx_in_app_notification_tenant_recipient_created_at
    ON in_app_notification (tenant_id, recipient_user_id, created_at DESC);
```

Design notes (from `database-architect` review):
- `recipient_user_id` is promoted to a real, `NOT NULL`, FK-backed column on
  `notification_outbox` (not left inside `payload` JSONB as the initial draft proposed)
  — every event type in this MVP has exactly one recipient, so it is a structural
  column, not event-specific data; only genuinely event-specific rendering variables
  (`amount`, `currency`, `paymentId`, etc.) stay in `payload`.
  `event_type` is a small, MVP-fixed, `CHECK`-constrained enum (adding a new event type
  is a new migration, matching this codebase's existing state-machine convention).
- `template_key` stays free-text `VARCHAR NOT NULL` (not a `CHECK` enum) — deliberately,
  so a future template-authoring/seeding migration can insert new keys without a schema
  change. This does not resolve the origination gap in §21; it just avoids baking a
  premature constraint around it.
- `notification_outbox` is not on `backend.md`'s named append-only list
  (`payment-management`/`ledger-settlement-management`/`audit-log-management`), and
  isn't forced into that mold — it's dispatch metadata, not the financial record of
  truth. Exactly one controlled `PENDING → SENT|FAILED` transition is allowed, enforced
  by the together-`CHECK` above, never a second transition, never a delete.
- `notification_template` carries the standard `created_at`/`updated_at`/`created_by`/
  `updated_by` audit columns even though no authoring endpoint exists yet in this MVP —
  matching every other table's convention in this codebase and so a future authoring
  endpoint needs no migration to add them.

## 9. Backend design

### 9.1 Event consumption — synchronous, fast, transaction-decoupled

`NotificationOutboxService` exposes one `@TransactionalEventListener(phase =
TransactionPhase.AFTER_COMMIT)` method per consumed event type (`onPaymentConfirmed`,
`onPaymentRejected`, `onPaymentRefunded`). Each method is **not** `@Async` — it runs
synchronously, on the same thread, immediately after the triggering transaction commits
(so `TenantContext` from the original request is still valid if needed, though this
method does not rely on it — see below). Its entire job: build the `notification_outbox`
row from the event's own fields (`tenantId`, `studentId` → `recipient_user_id`,
`event_type`, `payload`), and persist it in one small `@Transactional` write. No
cross-module calls, no template resolution, no email send happen here — this keeps the
listener's added latency to a single-row insert, satisfying "does not... block the
triggering write" without needing to reason about async-executor behavior on the
request path at all.

### 9.2 Dispatch — a single scheduled poller, not an immediate `@Async` kick

Initial design considered an immediate `@Async` dispatch triggered directly from the
`AFTER_COMMIT` listener. **Rejected during `solution-architect`/`security-reviewer`
review**: it is durability-fragile (a process crash or executor-queue rejection between
commit and the async task actually running silently orphans the outbox row, defeating
the point of having one) and introduces a real correctness risk around Spring's default
`ThreadPoolTaskExecutor` rejection policy — if a rejected task ever runs synchronously on
the calling (request) thread (`CallerRunsPolicy`), an unconditional `TenantContextHolder.clear()`
in that task's `finally` would wipe the *request* thread's own tenant context mid-request.

Final design: a single `@Scheduled(fixedDelay = ...)` `NotificationDispatchPoller`
(interval an implementation-time tuning constant, e.g. 5s — not a business decision),
running on Spring's dedicated scheduler thread, entirely decoupled from any request or
async-executor thread pool:
1. Claims a batch of `PENDING` rows across all tenants via
   `SELECT ... WHERE status = 'PENDING' ORDER BY created_at LIMIT N FOR UPDATE SKIP LOCKED`
   — a deliberate, explicitly-named cross-tenant bypass query (per `backend.md`'s
   "any repository method that bypasses the structural filter must be explicitly
   named... e.g. `findAllAcrossTenants...`" rule), justified because dispatch-polling is
   inherently a platform-level background operation, not a tenant-scoped one, and
   `SKIP LOCKED` is required for correctness once multiple app instances run behind
   Nginx (per `architecture.md`'s statelessness requirement) so two instances never
   double-send the same row.
2. For each claimed row, individually, inside its own outer `try { ... } catch
   (Exception e) { ... } finally { TenantContextHolder.clear(); }` block (one such block
   per row, so one row's failure can never abort the batch or the `@Scheduled`
   invocation itself): `TenantContextHolder.set(row.getTenantId())` as the first
   statement inside `try`; resolve the recipient via
   `UserProvisioningApi.findTenantUserSummaries(Set.of(row.getRecipientUserId()))`
   (now safe — tenant context is explicitly set from the row's own column, never
   inherited/ambient); resolve `(tenant_id, template_key)` from `NotificationTemplateRepository`
   (a real `TenantAwareRepository`, correctly scoped now that context is set); render;
   call `MessagingProviderApi.sendEmail(...)` (a fully synchronous, blocking call —
   `JavaMailSender` is blocking by design, so no nested-thread hop can silently drop the
   thread-local tenant context mid-send); in one short `@Transactional` write, update the
   outbox row's `status`/`dispatched_at` and insert the `in_app_notification` row. The
   `catch` block marks the row `FAILED` (logging the cause) instead of letting the
   exception propagate to the next row's iteration. The `finally` block unconditionally
   calls `TenantContextHolder.clear()` regardless of success or a caught exception,
   before moving to the next claimed row.
3. A row that fails to send (template missing, recipient unresolvable, provider error)
   is marked `FAILED` by that row's own `catch` block and never re-claimed — terminal,
   per the explicit "no retry" open decision (§21). A row still `PENDING` after a crash
   mid-batch (the process died before that row's `@Transactional` status-update
   committed) is naturally re-claimed on the next poll cycle — a crash-recovery property
   of the outbox pattern, not a retry-on-failure policy.

This design fully removes the executor-saturation/`CallerRunsPolicy`/nested-async-thread
risk class flagged in review, at the cost of a small, bounded, non-business-decision
dispatch latency (one poll interval) instead of near-immediate send — an acceptable MVP
trade-off given delivery-timing SLAs are not specified anywhere.

### 9.3 Additive fields on existing `payment-management` events

Verified call-site-by-call-site during `payment-ledger-specialist` review:

- `PaymentConfirmedEvent` and `PaymentRejectedEvent` (both published from
  `PaymentConfirmationService.confirmByGatewayReference`): add `studentId`, `amount`,
  `currency` — all read from the `StudentOrder order` object **already loaded in scope**
  at the existing publish call sites. Zero new queries, zero change to method behavior
  beyond the constructor call.
- `PaymentRefundedEvent` (published from `RefundService.processRefund`): add
  `studentId`. **Not free** — `RefundService` only loads `Payment` (which has no
  `studentId` field), not `StudentOrder`. Populating this requires a new
  `StudentOrderRepository` read by `payment.getOrderId()` inside `RefundService`, a
  small but real scope addition to a financially-sensitive method. **Flagged for
  explicit confirmation before implementation** — see §21. `amount` is already present
  on this event; no change needed there.

Per `payment-ledger-specialist`'s review: this is a normal, additive, disclosed
cross-module extension (identical in kind to the attendance/exam modules' precedent of
adding narrow read methods to another domain's `api` package) — it changes no ledger
semantics, no terminal-row mutation rule, no `payment.status` meaning, and no REST
contract (`docs/api/payment-management.md` is untouched). It does not require an ADR or
formal payments.md sign-off, but must be called out explicitly in the implementation
PR's description per `.claude/rules/git-workflow.md`.

### 9.4 `integration-management`: `MessagingProviderApi`

New `api.MessagingProviderApi { void sendEmail(String toEmail, String subject, String
body); }`. Real implementation (`mail.SmtpMessagingProviderApi`) backed by Spring's
`JavaMailSender`/`spring-boot-starter-mail`, configured via `spring.mail.*` properties —
this follows the `FakePaymentGatewayAdapter` precedent (a real, working, in-process
integration standing in for a not-yet-selected commercial provider), not the
`UnavailableObjectStorageApi` precedent (fail loud, no local fallback), because SMTP is
a protocol, not a vendor, and self-hosting an SMTP relay is not the "self-hosted binary
media storage" architecture.md forbids. The actual production SMTP relay/vendor
selection remains the explicit open decision already logged in
`integration-architecture.md` §8 ("Email/SMTP provider — not selected") — this plan does
not resolve it, it only makes the interface real and locally testable.

### 9.5 Notification Center read/write endpoints

`NotificationController` (self-service, recipient-scoped, no `DomainArea` check needed
— see §2):
- List: filtered to `(tenant_id, recipient_user_id)` from the authenticated principal,
  never a path/query parameter — this filter is **manual/bolt-on** on top of
  `TenantAwareRepository`'s structural `tenant_id`-only scoping (per `security-reviewer`
  finding: `TenantAwareRepositoryImpl` enforces `tenant_id` only, not
  `recipient_user_id`), so it needs its own explicit query condition and its own
  same-tenant, cross-user negative test (§18).
- Mark-read: updates `read_at` only on a row the caller owns; `404`/`403` (not silent
  no-op) if the id doesn't resolve to the caller's own row.

## 10. API contract

Following this codebase's established `ApiResponse<T>`/`PageResponse<T>` envelope
(`com.lms.common.api`). To be written into `docs/api/notification-management.md` by the
`review-api-contract` skill before implementation begins (per that skill's own
requirement) — summarized here for planning purposes:

| Method + path | Auth | Request | Response | Notes |
|---|---|---|---|---|
| `GET /api/v1/notifications?page=&size=` | Any authenticated recipient | Query: `page`, `size` | `200 ApiResponse<PageResponse<NotificationResponse>>` | `NotificationResponse{id, title, body, readAt, createdAt}`. Scoped to `(tenant_id, recipient_user_id)` from the authenticated principal — no id/tenant-id parameter accepted from the client. |
| `PATCH /api/v1/notifications/{id}/read` | Any authenticated recipient, owner only | Path: `id` | `200 ApiResponse<NotificationResponse>` | `404` if `id` doesn't resolve to a row owned by the caller in their own tenant (never `403` — existence itself must not be revealed for another user's row, mirroring this codebase's other self-service-read 404 convention). |

No endpoint is added for `notification_outbox` or `notification_template` in this MVP —
no delivery-log/template-management UI exists to call one, and adding a read endpoint
with no caller would be speculative surface area.

## 11. Frontend screens

Per `ui-ux-reviewer` review:

**Student — `Notifications > Notification Center`** (`docs/ui-ux/screen-map.md:61`,
which currently bundles this with "preference center" — that line needs splitting when
docs are updated post-implementation, since preference center is Phase 2, not this
module):
- List: paginated, newest-first, unread rows visually **and programmatically**
  distinguished (not color-only, per accessibility gap below).
- Loading: skeleton rows (`component-library-spec.md` §5.1 convention), not a full-page
  spinner.
- Empty: distinct "no notifications yet" copy, via the established
  `frontend/src/components/states/empty-state.tsx`.
- Error: retryable, via the established `frontend/src/components/states/query-state-boundary.tsx`.
- Mark-as-read: keyboard-operable (Tab + Enter/Space), reflects the confirmed server
  response — not an optimistic-only client-side change.
- New-arrival announcement: reuses the shared Toast/live-region wrapper
  (`component-library-spec.md` §4.5) — no component-local `aria-live` reimplementation.

**Teacher — activity feed** (genuine IA gap per `ui-ux-reviewer`: `screen-map.md`'s
Teacher Portal section has no Notifications/Activity line today — needs one added as
part of this module's post-implementation documentation pass, not invented as a design
decision here): same underlying list component/endpoint, server-filtered to the
Teacher's own notifications, under a Teacher-scoped route. No distinct visual treatment
is specified anywhere — reusing the Student component under a different route is the
safe default, not a UX invention, since nothing describes an alternative.

**Explicitly left unspecified, not invented (per `ui-ux-reviewer`)**:
- Real-time delivery mechanism (polling vs. websocket vs. next-navigation refresh) —
  any of these satisfies the acceptance criteria; pick the simplest (poll on focus/
  interval) at implementation time as a technical default, not a product decision.
- Exact pagination page size.
- Whether a bulk "mark all as read" action exists (single-item mark-read only, per the
  issue's literal scope).
- Click-through destination when a notification is clicked (no linked-entity deep-link
  is specified for payment notifications).

## 12. Validation rules

- `NotificationController` list: `page >= 0`, `size` bounded to this codebase's
  standard max page size (mirror existing list endpoints' convention).
- Mark-read: `id` must be a well-formed UUID; ownership check (not merely tenant check)
  enforced server-side before any write.
- Template rendering: a missing `(tenant_id, template_key)` row, or a template
  referencing an unresolvable variable, fails that row's dispatch (`status = FAILED`)
  without throwing out of the poller loop — one bad row must never halt the batch.
- Event-payload construction: `recipient_user_id` must be non-null before an outbox row
  is persisted — the `NOT NULL` DB constraint is the backstop, but the service layer
  should fail loud (not insert a row) if a future event type is wired without a
  resolvable recipient, rather than let the constraint violation surface as a generic
  500 from inside a transaction listener.

## 13. Error cases

| Scenario | Behavior |
|---|---|
| Recipient (`studentId`) doesn't resolve to a live `tenant_user` in the event's tenant | Outbox row inserted normally (recipient existence isn't re-checked at insert time — only at dispatch); dispatch marks it `FAILED`, no exception escapes the poller. |
| No `notification_template` row for `(tenant_id, template_key)` | Dispatch marks the row `FAILED` (§21 — the deeper "no template ever exists" gap is a separate, unresolved product question). |
| `MessagingProviderApi.sendEmail` throws (SMTP unreachable, etc.) | Row marked `FAILED`; no retry (explicit open decision); poller continues to the next row. |
| Poller process crashes mid-batch, after claiming a row but before updating its status | Row remains claimed-but-`PENDING` (the claim itself was never persisted as a distinct "claimed" state — see §21 nuance on `SKIP LOCKED` semantics not needing a separate claimed marker since the transaction that would mark it dispatched never committed); re-claimed on next poll. |
| Two app instances' pollers run concurrently | `FOR UPDATE SKIP LOCKED` guarantees each `PENDING` row is claimed by exactly one instance's transaction at a time — no double-send. |
| A user requests `GET /api/v1/notifications` with no `Authorization` | `401`, standard auth-filter behavior, unchanged from every other endpoint. |
| A user calls `PATCH .../{id}/read` for a notification belonging to another user in the same tenant | `404` (existence not revealed), per §10. |
| A user calls `PATCH .../{id}/read` for a notification belonging to another tenant | `404`, same structural reason (fails the `tenant_id` filter before it could even fail the recipient filter). |

## 14. Tenant-isolation rules

- `notification_outbox`, `notification_template`, `in_app_notification` are all
  tenant-owned: `tenant_id NOT NULL REFERENCES tenant(id)`, composite index leading with
  `tenant_id` matching each table's real query shape (§8).
- `NotificationTemplateRepository` and `InAppNotificationRepository` extend
  `TenantAwareRepository`, inheriting the structural `TenantContextHolder`-driven
  `tenant_id` filter on every finder — the same mechanism every other tenant-owned
  repository in this codebase uses, per `security-reviewer`'s confirmation that this is
  the platform's sole sanctioned mechanism (introducing a parameterized-repository
  variant instead would itself require an ADR, per `multi-tenancy.md` §2's
  consistency-across-modules rule).
- The one deliberate, explicitly-named bypass: the dispatch poller's cross-tenant
  `PENDING`-row claim query (§9.2 step 1) — a platform-level background operation, not a
  tenant-scoped read, named accordingly (`findAllAcrossTenantsPendingDispatch` or
  equivalent) per `backend.md`'s bypass-naming convention.
- Async-boundary discipline: `TenantContextHolder.set(tenantId)` is called explicitly,
  once per claimed row, using that row's own persisted `tenant_id` column — never an
  inherited/ambient value — as the **first statement inside `try`**, with
  `TenantContextHolder.clear()` unconditionally in `finally`. This is the first place in
  this codebase a thread boundary is crossed with tenant context in production code; get
  this pattern reviewed carefully during implementation, not just at plan time.
- Notification Center reads/writes add a second, manual scoping dimension
  (`recipient_user_id`) on top of the structural `tenant_id` filter, since two different
  users in the *same* tenant must not see each other's notifications — this needs its
  own dedicated same-tenant negative test, distinct from the standard cross-tenant test
  (§18).
- Recipient-resolution-by-email cross-tenant collision: `user-management`'s email
  uniqueness is scoped per tenant (`UNIQUE (tenant_id, email)`), so two different tenants
  can have a user with the same email address — dispatch must resolve the recipient
  strictly through `(tenant_id, studentId)`, never by email lookup, so it can never
  cross-match a same-email user in a different tenant (§18 test).

## 15. Security rules

- No new `DomainArea`/permission grant is introduced (§2) — Notification Center access
  control is purely session-derived self-scoping, the correct shape for "read/mutate my
  own records only."
- `notification_outbox.payload` JSONB must not accumulate raw sensitive data beyond what
  rendering actually needs (per `security-reviewer`): amount/currency/paymentId are
  fine (already visible to the payer elsewhere); a future notification type must never
  persist a raw password-reset token or similarly sensitive short-lived credential into
  this table in plaintext — that class of notification (not in this MVP's scope) would
  need the same short-lived/signed-token treatment `security.md` requires for video
  playback tokens, not a durable JSONB blob.
- `MessagingProviderApi`'s SMTP credentials (once a real relay is configured beyond
  local dev) are owned exclusively by `integration-management`, per
  `integration-architecture.md` §5 — `notification-management` never holds SMTP
  credentials itself, matching every other domain's relationship to
  `integration-management`.
- Audit logging is **not** required for notification dispatch itself — confirmed against
  `.claude/rules/security.md`'s canonical mandatory-audit-action list (price changes,
  payment approvals/rejections, device resets, access/expiry extensions, reactivation
  approvals, content deletions, settlement changes, impersonation); notification
  send/delivery does not appear on it, and `12-notifications.md` §9 confirms this
  explicitly ("None specified").
- The dispatch poller's claim query must go through the same `TenantAwareRepositoryImpl`-
  adjacent discipline as everything else once tenant context is set per-row — no raw/
  native cross-tenant query anywhere else in this domain besides the one explicitly
  named claim query.

## 16. Audit requirements

None. Per §15, notification send/delivery is not on `security.md`'s mandatory-audit
list, and `12-notifications.md` §9 states this explicitly for the domain generally. No
`audit-log-management` event is published by this module.

## 17. Payment impact

This module does not read, write, or reinterpret any ledger entry, payment status
transition, refund-eligibility rule, or terminal-row mutation. Its only touch on
`payment-management` is additive fields on three existing, already-shipped, currently
unconsumed `api`-package event records (§9.3) — confirmed by `payment-ledger-specialist`
review to require no ADR, no `.claude/rules/payments.md` sign-off, and no REST contract
change. The one item needing explicit confirmation before implementation (not a
payments-rule violation, just added scope to a financially-sensitive method) is the new
`StudentOrder` lookup inside `RefundService.processRefund` needed to populate
`PaymentRefundedEvent.studentId` — see §21.

`.claude/rules/payments.md` §1's "a `Payment` row is immutable once terminal" and §4's
"ledger entries are append-only" are both structurally unaffected: nothing in this
module writes to `payment`, `payment_refund`, or `ledger_entry`.

## 18. Tests

Naming convention: `NotificationCrossTenantIntegrationTest`,
`NotificationDispatchIntegrationTest` (matching this codebase's existing
`{Domain}CrossTenantIntegrationTest` pattern).

**Unit**
- Event-payload schema: every event `notification-management` consumes carries
  `tenantId`/`studentId`(recipient) explicitly (fails the build/test if a new event type
  is wired without one).
- Template variable-substitution/rendering logic (given a template + payload, correct
  subject/body output; missing-variable case fails closed, not with a raw exception
  string leaking into the sent email).
- `TenantContextHolder` set/clear symmetry: a deterministic, non-async, plain unit test
  asserting the thread-local is cleared after both (a) a normal dispatch return and (b)
  a thrown exception mid-dispatch — proves the `try/finally` structure directly, with no
  thread-pool/timing dependency (chosen over a size-1-executor "back-to-back dispatch"
  integration test, which was found during review to not actually prove `clear()` ran,
  only that `set()` ran — a stale leftover value would be silently overwritten, not
  detected, by that style of test).
- Per-row exception isolation: an exception thrown while processing one claimed row
  (template missing, provider error, etc.) is caught and logged inside that row's own
  iteration of the poller's loop, marks that row `FAILED`, and does not propagate out to
  abort the rest of the batch or the `@Scheduled` invocation itself — without asserting
  any specific undecided retry policy. (Note: `qa-test-engineer`'s original review
  proposed this as an `AsyncUncaughtExceptionHandler` test against an `@Async` dispatch
  design; superseded here because §9.2's final design replaced `@Async`/`ThreadPoolTaskExecutor`
  entirely with a single-threaded `@Scheduled` poller, which removes the thread-pool
  exception-swallowing failure mode that test was meant to catch — per-row `try/catch`
  isolation is the equivalent guarantee in the new design. `qa-test-engineer`'s
  secondary "d2" pinned-single-thread-executor regression test is dropped for the same
  reason: there is no longer an executor/thread pool in this design for a leaked
  `ThreadLocal` to survive across pooled-thread reuse — the deterministic `try/finally`
  unit test above is sufficient on its own.)

**Testcontainers**
- Transaction separation: a payment confirmation publishes `PaymentConfirmedEvent`, and
  the resulting `notification_outbox` insert happens in a separate transaction from,
  and does not block, the payment-confirmation write (assert via a real DB round-trip,
  not a mock).
- **Mandatory cross-tenant negative test**: two-tenant fixture proving Tenant B cannot
  read Tenant A's `notification_template`/`notification_outbox`/`in_app_notification`
  rows via any repository method or endpoint.
- **Same-tenant BOLA negative test** (distinct from the above): within one tenant, User B
  cannot list or mark-read User A's `in_app_notification` rows via
  `GET /api/v1/notifications` or `PATCH .../{id}/read`.
- Recipient-resolution cross-tenant collision: two tenants each have a `tenant_user`
  with the *same* email address; dispatching a notification for Tenant A's user must
  never resolve or email Tenant B's same-email user.
- Async consumer's repository access uses the same structural tenant-filtering
  mechanism as request-time code (not a raw-tenant-id-parameter bypass method) — assert
  by inspecting that dispatch-path repository calls go through the `TenantAwareRepository`-
  backed repositories with `TenantContextHolder` set, per the issue's own explicit test
  requirement.
- `SKIP LOCKED` double-claim test: two concurrent poller runs (simulating two app
  instances) against the same batch of `PENDING` rows never both dispatch the same row.
- A `FAILED` row is never re-claimed by a subsequent poll (no implicit retry).
- A still-`PENDING` row (simulating a mid-batch crash before the claiming transaction
  committed) is re-claimed and dispatched on the next poll.

**Playwright**
- "No notifications yet" empty state renders for a recipient with zero rows.
- A new notification appears in the Notification Center after a triggering backend
  event (a test payment confirmation) without a full page reload.
- Mark-as-read persists across a reload (not merely an optimistic client-side flip).
- Teacher's activity-feed route renders the same list pattern, scoped/empty as expected,
  distinct from Student's route.

**Explicitly not required** (flagged, not silently assumed): `module-catalog.md`'s
`notification-management` test row mentions "delivery-log/preference record" isolation
— neither table exists in this MVP's scope; that phrasing is a Phase-2 carryover in the
catalog's wording, not a missed MVP requirement.

## 19. Documentation changes

Per `update-documentation` (to run after implementation, not during this planning pass):
- `docs/api/notification-management.md` — new file, written by `review-api-contract`
  before/during implementation (§10).
- `docs/architecture/modular-monolith.md` — add a fourth "worked example" for
  `notification-management`, matching the existing three (content-management,
  attendance-management, exam-management), documenting the `AFTER_COMMIT` listener +
  scheduled-poller pattern and the `TenantContextHolder` async-boundary discipline as
  the reference precedent for any future domain needing to cross a real thread boundary.
- `docs/ui-ux/screen-map.md` — split the Student "Notification Center, preference
  center" line (preference center is Phase 2, not this module) and add a new Teacher
  Portal "Activity Feed / Notifications" line (currently absent — a real IA gap found
  during this review, not previously documented anywhere).
- `docs/ui-ux/accessibility.md` — add: unread-count-badge accessible name pattern,
  read/unread-not-color-only rule for list rows (extending its existing §5 pattern).
- `docs/requirements/open-decisions.md` — append a new "## 22. Email Notifications
  (MVP-018) — carried-forward decisions" section, following the established §15–§21
  convention, logging: (a) the two decisions the issue itself already names as
  unresolved (template-authoring sub-role, MVP failure-handling), (b) the
  newly-found template-origination/seeding gap (§21 item 1 below), (c) the
  `12-notifications.md` internal phase-inconsistency on "result published" (§6), (d)
  the `RefundService`/`StudentOrder` scope addition once implementation confirms it
  (§21 item 2).
- `docs/architecture/integration-architecture.md` — no content change needed; its §8
  "Email/SMTP provider — not selected" open question already correctly describes this
  module's remaining gap (a real protocol-level SMTP integration exists after this
  module ships, but no production relay/vendor is chosen).

## 20. Implementation order

1. **`integration-management`**: `MessagingProviderApi` + `SmtpMessagingProviderApi` +
   `spring-boot-starter-mail` dependency + `spring.mail.*` config (including a local-dev
   SMTP catcher in `docker-compose` — a `devops-local` follow-up, not this plan's own
   scope to configure). No dependency on anything else in this list; can start first and
   in parallel with step 2.
2. **`payment-management`**: the two additive event-record field changes (§9.3) —
   `studentId`/`amount`/`currency` on `PaymentConfirmedEvent`/`PaymentRejectedEvent`
   (free), `studentId` on `PaymentRefundedEvent` (requires the `RefundService`
   `StudentOrder` lookup — **get explicit confirmation this scope addition is
   acceptable before starting**, per §21 item 2). Small, isolated, independently
   testable against payment-management's own existing test suite.
3. **`notificationmanagement` schema** (V28 migration) — depends on nothing but the
   `tenant`/`tenant_user` tables (already exist).
4. **`notificationmanagement` backend**: entities/repositories → `NotificationOutboxService`
   (`AFTER_COMMIT` listeners, depends on step 2's event shapes) →
   `NotificationDispatchPoller` (depends on steps 1, 3, and `UserProvisioningApi` which
   already exists) → `NotificationController` (Notification Center read/mark-read,
   depends on step 3 only).
5. **`docs/api/notification-management.md`** via `review-api-contract`, before frontend
   work starts (per root `CLAUDE.md`'s development workflow — do not implement backend
   and frontend simultaneously unless explicitly approved).
6. **Backend tests** (§18 Unit + Testcontainers) — before frontend work begins, per
   workflow step 3.
7. **Frontend**: Student Notification Center, Teacher activity-feed route, shared
   Toast/live-region wiring for new-arrival announcements.
8. **Frontend/E2E tests** (§18 Playwright).
9. **Security, tenant-isolation, integration reviews** (this module's own
   `security-review`/`tenant-isolation-review` skill passes against the actually-shipped
   code, not just this plan).
10. **Documentation updates** (§19).
11. One logical commit per completed step above, per root `CLAUDE.md`'s development
    workflow (e.g., "backend: add notification-management dispatch infrastructure" as
    one commit, separate from the payment-management event-field commit, separate from
    the frontend commit).

Note: the template-origination gap (§21 item 1) is **not** on this implementation-order
list as a blocking step — it's a genuine, unresolved product gap that determines whether
any tenant receives real email at MVP launch, but building the pipeline correctly does
not require resolving it first (a tenant with zero template rows simply gets `FAILED`
dispatch rows, which is a real, testable, honest failure mode, not a broken build).

## 21. Risks and unresolved decisions

Restating the issue's own two explicit open decisions (not resolved here, per the
issue's own instruction not to silently decide them):
- Which staff sub-role(s) may manage `notification_template` rows or trigger sends —
  unspecified anywhere (`open-decisions.md` §2, `12-notifications.md` §11).
- MVP-level failure handling for a failed email send is undefined; this plan
  deliberately does not implement retry/backoff (§9.2/§13) rather than silently deciding
  it is needed or not needed.

New items surfaced by this planning pass (not previously logged in `open-decisions.md`
at this level of detail — to be appended there per §19 once implementation confirms
final shape):

1. **Template-origination gap — genuinely unresolved, found during
   `product-requirements-analyst` review.** NOTIF-2's own acceptance criterion requires
   templates to be "tenant-scoped... never a shared static default," but no staff
   role/UI in this MVP's scope can create a `notification_template` row, and no seeding
   mechanism is specified anywhere. Without one of these, no tenant can ever receive
   real email at MVP launch, which is in tension with the module's own stated
   business goal ("deliver MVP-scoped transactional email"). Two options were
   identified, **neither decided here**: (a) `tenant-management`'s tenant-approval/
   creation flow additively seeds default template rows per tenant at provisioning time
   (technically satisfies "tenant-owned, no runtime-shared-default," since each row is a
   genuine per-tenant row, just seeded with identical starting platform copy — but
   touches another already-shipped module's creation path, itself a disclosed
   cross-module addition needing its own sign-off); (b) accept that this MVP ships a
   pipeline with no way to originate templates, and every dispatch attempt fails closed
   until a later module adds authoring/seeding. **Needs explicit product decision before
   step 4 of implementation reaches the point of actually testing an end-to-end send.**
2. **`RefundService`/`StudentOrder` scope addition — needs confirmation, not a
   blocker but not silently assumed either.** Populating `PaymentRefundedEvent.studentId`
   requires a new `StudentOrderRepository` read inside `RefundService.processRefund`
   (payment-ledger-specialist finding, §9.3) — a small, real, additive scope increase to
   a financially-sensitive method that the original issue text implied would be free
   (it explicitly says "e.g. payment confirmed" only, not detailing per-event
   feasibility). Confirm acceptable before implementing step 2, or descope
   `PaymentRefundedEvent` consumption from this module's concrete wiring (dispatch
   infrastructure and the other two events would still ship).
3. **`12-notifications.md` internal documentation inconsistency** (found during
   `product-requirements-analyst` review): its own §4/§8 name "result published" as an
   MVP-scope trigger example with no phase qualifier, while its §10 correctly cites
   `FR-NM-5` placing it in Phase 2. This plan follows the authoritative phase table
   (`functional-requirements.md`), not the inconsistent narrative text — flagged for a
   documentation-reconciliation pass, not resolved by silently editing the spec here.
4. **Async-thread tenant-context discipline is precedent-setting.** This is the first
   production code path in this codebase to cross a real thread boundary and manually
   manage `TenantContextHolder`. `security-reviewer` confirmed the `set`-in-`try`/
   `clear`-in-`finally` pattern is sound and the only sanctioned mechanism, but flagged
   this as the highest-scrutiny code in the module — recommend a dedicated, careful
   implementation-time code review pass on `NotificationDispatchPoller` specifically,
   beyond the standard review, given a bug here is a silent cross-tenant data risk by
   the architecture docs' own explicit characterization ("risk register item").
5. **Teacher activity-feed screen has no prior IA documentation** (`screen-map.md` gap,
   §11/§19) — this plan proposes reusing the Student component under a new route as the
   safe default, but the route path and whether any visual distinction is warranted are
   both genuinely unspecified and should be confirmed during frontend implementation
   rather than treated as this plan's own invention.
6. **Poll interval, page size, and real-time delivery mechanism are implementation-time
   technical defaults**, not business decisions — noted so a future reviewer doesn't
   mistake an arbitrary constant (e.g., a 5-second poll interval) for a ratified SLA.
