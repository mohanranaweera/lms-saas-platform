# Wave 11 Plan — Notifications + Communications + Integrations

Status: **DONE** (2026-10-01) — backend, frontend, tests, security / tenant-isolation / integration
review and documentation complete. See §11–§15 and ADR-023. Judgment calls J1–J4 need confirmation
at PR review (J1 touches secret handling; D2 adds an RBAC area — both change-controlled, both
recorded in ADR-023).

Scope per the Wave 11 brief: complete the approved communication channels (in-app, email,
SMS, WhatsApp), templates, broadcasts and audience targeting, and tenant integration
configuration for messaging providers — with provider logic only behind adapters, explicit
trusted tenant context on every async hop, and write-only, masked, never-logged secrets.

Parity rows addressed: PAR-12-03, PAR-12-04, PAR-21-01, PAR-21-02, PAR-21-03 (delivery logs;
provider still procurement-blocked), PAR-22-01, PAR-22-02, PAR-22-03 (opt-in + template rule;
provider still blocked), PAR-03-04 (notification-history tab), PAR-XC-01 (Communication nav
group), master instruction §26 / §27 (messaging part).

---

## 1. Phase A — Analysis (findings)

### 1.1 Notification module — what exists

- `notification_outbox` (V28/V29): one row per transactional event
  (`PAYMENT_CONFIRMED|REJECTED|REFUNDED`, `ACCOUNT_SETUP_INVITE`, `PASSWORD_RESET`), single
  recipient, `PENDING -> SENDING -> SENT|FAILED`. Rows are written by `AFTER_COMMIT`
  listeners (`NotificationOutboxService`) that set `TenantContextHolder` **from the event's
  own `tenantId`** and clear it in `finally`. Dispatch is a `@Scheduled` poller (5 s) with
  a cross-tenant `FOR UPDATE SKIP LOCKED` claim, a 3-phase claim/send/finalize so no
  transaction spans the SMTP call, and a reconciliation job that fails stuck `SENDING` rows
  forward (never re-sends).
- **No retry**: `FAILED` is terminal by an explicit MVP decision (open-decisions §22).
- **No duplicate guard**: the same event delivered twice would produce two rows.
- `notification_template` (V28): per-tenant `(tenant_id, template_key)` subject/body, seeded
  at tenant registration. **No authoring endpoint/UI** (open-decisions §22). Renderer is
  `{{var}}` substitution with HTML-escaping and fail-closed on unknown variables.
- `in_app_notification` (V28): Notification Center for Student/Teacher (`GET/PATCH
  /api/v1/notifications`, self-scoped, unread badge, 30 s poll). Written only when the
  matching email is `SENT`.
- Email goes through `integrationmanagement.api.MessagingProviderApi#sendEmail` →
  `SmtpMessagingProviderApi` (platform SMTP; sender `app.mail.from-address`). There is **no**
  SMS/WhatsApp contract, adapter, account or config anywhere.

### 1.2 Integrations — what exists

- Payment gateway (ADR-017) and media providers (ADR-018) set the pattern: tenant-owned
  account rows, AES-256-GCM ciphertext with context-bound AAD, write-only secrets, old rows
  disabled (never deleted) on replacement, Tenant Admin `INTEGRATIONS`/`PAYMENT_GATEWAY`
  `CREATE_EDIT`, Read-only Auditor `VIEW`, fail-closed cipher (503) when the key is missing.

### 1.3 Gaps vs. the brief

| Brief item | Gap |
|---|---|
| SMS / WhatsApp | No contract, adapter, tenant account, delivery record |
| Templates | Transactional templates not editable; no reusable broadcast templates; no WhatsApp pre-approval state |
| Broadcasts + audiences | Nothing |
| Retries | Explicitly absent |
| Duplicate events | No dedupe on outbox; nothing for broadcasts |
| Admin UI | No Communication nav group (PAR-XC-01) |
| Recipient UI | Notification Center exists; no preferences/opt-in, no mark-all-read |
| RBAC | No permission row for Communications (PAR-12-04 / open-decisions §22) |

### 1.4 Product-owner decisions taken at the start of this wave (2026-10-01)

- **D1 — SMS/WhatsApp vendors: adapters + sandbox only.** Provider-agnostic contracts and a
  tenant account model with encrypted credentials. The only adapter shipped is `SANDBOX`,
  enabled by `app.messaging.sandbox-enabled` (local/test profiles only). In real
  environments, SMS/WhatsApp stay "not available" and fail closed until a vendor ADR adds a
  real adapter. No vendor is guessed.
- **D2 — RBAC: new `DomainArea.COMMUNICATIONS`.** Tenant Admin `V/C/E`; Student Support
  `V/C/E` (send broadcasts, manage broadcast templates); Read-only Auditor `V`. Teachers may
  broadcast only to courses they own (ownership-scoped, like `LIVE_CLASSES`).
- **D3 — Retry: bounded backoff.** Up to **3 attempts total**: on a transient failure the row
  returns to `PENDING` with `next_attempt_at` = now + 1 min (after attempt 1) / 5 min (after
  attempt 2); a third transient failure, or any permanent failure, is `FAILED`. Applies to the
  new deliveries **and** the existing email outbox. This supersedes open-decisions §22's
  "no retry" item.

---

## 2. Target behavior

### 2.1 Channels

| Channel | Transport | Available when | Address |
|---|---|---|---|
| IN_APP | `in_app_notification` row | always | — |
| EMAIL | platform SMTP via `MessagingProviderApi` (unchanged) | always | `tenant_user.email` |
| SMS | `MessagingChannelApi` → provider adapter | tenant has an ACTIVE SMS account **and** its provider adapter is enabled in this environment | `student_profile.mobile` |
| WHATSAPP | `MessagingChannelApi` → provider adapter | as SMS, for WHATSAPP | `student_profile.whatsapp_number`, else `mobile` |

Teachers have no phone field: SMS/WhatsApp deliveries to a teacher are `SKIPPED (NO_ADDRESS)`.

### 2.2 Adapter boundary (`integration-management`)

- `api.MessagingChannelApi`: `channelAvailability(channel)` and `send(OutboundChannelMessage)`.
  The implementation reads the **current tenant** (`TenantContextHolder`, set by the
  dispatcher from the delivery row's own `tenant_id`), loads that tenant's ACTIVE account,
  decrypts credentials in memory, and routes to a `MessagingProviderAdapter` by provider.
- Failures are typed in the api package: `MessagingTransientException` (retryable),
  `MessagingPermanentException` (not retryable), `MessagingChannelUnavailableException`.
  Messages carry a **safe error code** only, never provider response text or the destination.
- `SandboxMessagingAdapter` (provider `SANDBOX`): no network. Returns `sandbox-<uuid>`.
  Deterministic test numbers: a destination ending `0001` → transient failure, `0002` →
  permanent failure. Records sends in a bounded in-memory log for dev inspection.
- No Student/Course controller or service contains vendor logic (brief requirement).

### 2.3 Tenant messaging accounts (`tenant_messaging_account`)

- One ACTIVE row per `(tenant, channel)`; replacing disables the old row (never deleted —
  deliveries reference the account that sent them).
- `credentials_ciphertext` = AES-256-GCM, key `MEDIA_CREDENTIALS_ENCRYPTION_KEY` (the
  existing integrations key — see judgment call J1), AAD `messaging:{tenant}:{channel}:{id}`.
- API returns `provider`, `senderId` (non-secret), `status`, `credentialHint` ("••••" + last
  4 chars only when the secret is ≥ 12 chars, else "••••"), timestamps. Never the secret.
- Request DTO `toString()` redacts credentials; no log line includes them.
- Configure / disable / test-send are audit-logged without secret material.
- `SANDBOX` cannot be configured where the sandbox adapter is disabled (400
  `MESSAGING_PROVIDER_NOT_AVAILABLE`).

### 2.4 Templates

- **Event templates** (existing `notification_template`): Tenant Admin can view and edit
  subject/body (`COMMUNICATIONS/CREATE_EDIT`). Each key has an allowed variable set; credential
  templates must keep `{{link}}`. Edits audit-logged (before/after).
- **Broadcast templates** (`communication_template`, new): name, subject, body, optional SMS
  text, status `ACTIVE|ARCHIVED` (archive, not delete). Allowed variables: `{{name}}`,
  `{{instituteName}}`. Unknown variable → 400.
- **WhatsApp pre-approval state machine (PAR-22-02)** on a broadcast template:
  `NOT_SUBMITTED -> SUBMITTED -> APPROVED | REJECTED`, `REJECTED -> SUBMITTED` (resubmit).
  Editing subject/body/SMS text of a `SUBMITTED`/`APPROVED` template resets it to
  `NOT_SUBMITTED`. Each transition records actor + time + provider template name/reason and
  is audit-logged. Recording the provider's decision is a manual Tenant Admin action until a
  vendor adapter can sync it.

### 2.5 Broadcasts + audiences

- `POST /communications/broadcasts` with `clientRequestId` (idempotency), `subject`, `body`,
  optional `smsBody`, optional `templateId`, `channels[]`, `audience`.
- Audience types: `ALL_STUDENTS`, `COURSE` (currently enrolled), `SELECTED_STUDENTS` (ids
  student profile ids, verified to be students of this tenant — any foreign id → 400, no partial send),
  `ALL_TEACHERS` (approved, active), `PAYMENT_STATUS` (`PAID|PENDING|REJECTED`, optional
  course). Suspended accounts are always excluded. Cap: 5 000 recipients (400 above).
- Resolution is synchronous inside the create transaction; the broadcast row stores the
  audience definition and `recipient_count`; one `notification_delivery` row per
  `(recipient, channel)` (unique → no duplicate sends). IN_APP rows are written immediately
  (`SENT`); other channels start `PENDING`.
- WhatsApp requires a template in `APPROVED` state (outside the 24-hour session window a
  business-initiated message must use a pre-approved template). Otherwise 400.
- A channel that is not available (§2.1) → 409 `CHANNEL_NOT_AVAILABLE` at create.
- **Preferences/opt-in (PAR-22-03):** WhatsApp requires the recipient's explicit opt-in;
  SMS and broadcast email honor an opt-out. Missing consent → delivery `SKIPPED`
  (`NO_CONSENT`). Transactional emails (payments, password) ignore preferences.
- Teachers: `COURSE` audience for a course they own, channels `IN_APP`/`EMAIL` only (J2).
- Preview endpoint returns recipient count, per-channel reach, and up to 10 names — never
  addresses.

### 2.6 Delivery pipeline (async)

- `notification_delivery`: `PENDING -> SENDING -> SENT | FAILED | PENDING(retry)`; `SKIPPED`
  terminal. Same claim/send/finalize split and `FOR UPDATE SKIP LOCKED` cross-tenant claim
  as the outbox; the dispatcher sets `TenantContextHolder` from the **row's own tenant_id**
  and clears it in `finally` — tenant context is never inherited across the thread hop.
- Address resolved at send time (no PII copied into delivery rows); only a masked
  destination (`+947•••567`, `j•••@example.com`) is stored for logs.
- Stuck `SENDING` rows are failed forward (`STUCK_IN_SENDING`), never re-sent.
- Retry per D3. `last_error_code` holds a safe code (`PROVIDER_TRANSIENT`,
  `PROVIDER_REJECTED`, `NO_ADDRESS`, `NO_CONSENT`, `CHANNEL_NOT_AVAILABLE`, …).

### 2.7 Transactional outbox changes

- Bounded retry per D3 (`attempt_count`, `next_attempt_at`); `MailException` = transient,
  recipient/template/render errors = permanent.
- `dedupe_key` (unique per tenant when present): payment events use
  `{EVENT}:{paymentId}`, so a re-delivered event never produces a second email.

---

## 3. Database impact (additive; V66–V68)

- **V66** `tenant_messaging_account` (integration-management).
- **V67** `communication_template`, `broadcast`, `notification_delivery`,
  `notification_channel_preference` (notification-management).
- **V68** `notification_outbox`: `attempt_count`, `next_attempt_at`, `dedupe_key`; poller
  partial index rebuilt to include `next_attempt_at`.

All tables: `tenant_id NOT NULL` FK, tenant-leading indexes, composite same-tenant FKs to
`tenant_user`, `course`, each other. No existing migration edited; no data rewritten.

## 4. API impact (all `/api/v1`, tenant from trusted context)

| Method | Path | Gate |
|---|---|---|
| GET | `/communications/channels` | COMMUNICATIONS/VIEW or Teacher |
| GET/POST | `/communications/templates` | VIEW / CREATE_EDIT |
| PUT | `/communications/templates/{id}` | CREATE_EDIT |
| POST | `/communications/templates/{id}/archive` | CREATE_EDIT |
| POST | `/communications/templates/{id}/whatsapp-status` | INTEGRATIONS/CREATE_EDIT (Tenant Admin) |
| GET | `/communications/event-templates` | VIEW |
| PUT | `/communications/event-templates/{key}` | CREATE_EDIT + Tenant Admin |
| POST | `/communications/audience-preview` | CREATE_EDIT or Teacher (own course) |
| GET/POST | `/communications/broadcasts` | VIEW / CREATE_EDIT; Teacher sees and sends own only |
| GET | `/communications/broadcasts/{id}`, `/{id}/deliveries` | VIEW; Teacher own only |
| GET | `/communications/students/{studentId}/deliveries` (student profile id) | COMMUNICATIONS/VIEW |
| GET | `/tenant-admin/integrations/messaging` | INTEGRATIONS/VIEW |
| PUT | `/tenant-admin/integrations/messaging/{channel}` | INTEGRATIONS/CREATE_EDIT |
| POST | `/tenant-admin/integrations/messaging/{channel}/disable`, `/test` | INTEGRATIONS/CREATE_EDIT |
| GET/PUT | `/notifications/preferences` | self |
| POST | `/notifications/read-all` | self |

## 5. Frontend impact

- Tenant Admin nav: new **Communication** group (Broadcasts, Templates, Delivery logs live on
  broadcast detail) gated on COMMUNICATIONS/VIEW; **Messaging (SMS & WhatsApp)** under
  Institute Configuration gated on INTEGRATIONS/VIEW.
- Pages: `tenant-admin/communications` (broadcast list), `.../new` (composer with audience
  picker + preview), `.../[broadcastId]` (summary + delivery log), `.../templates`,
  `tenant-admin/settings/messaging`.
- Teacher: `teacher/communications` (own broadcasts + composer limited to own courses).
- Student/Teacher: notification preferences on the Notification Center page; "Mark all as
  read".
- Student detail: **Notifications** tab (PAR-03-04).

## 6. Security impact

- Secrets: write-only, encrypted with AAD, masked hint, redacted `toString`, never in audit
  metadata, logs or responses (tests assert all three).
- Provider errors mapped to safe codes; destinations masked in logs and delivery rows.
- Template rendering: allow-listed variables only; broadcast text is plain text (React
  escapes in-app; email is `text/plain`).
- Abuse limits: 5 000 recipients, body ≤ 5 000 chars, SMS text ≤ 640 chars.
- Audit: broadcast sent, template create/update/archive/WhatsApp status, event template
  update, messaging account configure/disable/test.

## 7. Tenant isolation impact

- Every new repository is `TenantAwareRepository`; the only cross-tenant queries are the
  explicitly named delivery-poller claim/reconcile queries (same pattern as the outbox).
- Audience ids (course, students) are validated in the caller's tenant; foreign ids → 400/404,
  never partial delivery.
- Cross-tenant tests for every new endpoint plus a dispatch test proving a delivery is sent
  with **its own** tenant's account even when two tenants are configured.

## 8. Test plan (Phase D)

Audience correctness (each type, suspended exclusion, foreign ids, cap) · cross-tenant
delivery and endpoint isolation · retries (transient → backoff → success; 3rd transient →
FAILED; permanent → FAILED immediately; stuck SENDING → FAILED, not re-sent; outbox email
retry) · provider failure mapping · duplicate events (broadcast replay with same
`clientRequestId`, duplicate payment event, double claim) · authorization (each role, teacher
ownership, auditor read-only) · secret exposure (responses, audit rows, logs, `toString`) ·
opt-in/opt-out · WhatsApp template rule · Playwright E2E for the admin composer, template,
messaging settings, and recipient Notification Center.

## 9. Judgment calls (flagged for sign-off)

- **J1** Reuse `MEDIA_CREDENTIALS_ENCRYPTION_KEY` (the integrations key) with a distinct AAD
  namespace instead of a new production secret. Avoids changing the ADR-019 production secret
  set (change-controlled). Trade-off: losing that key now also requires re-entering messaging
  credentials — runbook updated.
- **J2** Teachers broadcast only `IN_APP`/`EMAIL` (SMS/WhatsApp cost tenant money and need
  consent tracking that staff own).
- **J3** Email stays on platform SMTP; tenant-owned SMTP/sender domains are deferred (needs
  SPF/DKIM verification flow).
- **J4** Broadcast audience is resolved once at send time (a snapshot); later enrollments do
  not receive it. Scheduled/recurring broadcasts are deferred.

## 10. Deferred (explicit)

Real SMS/WhatsApp vendors (D1, needs ADR + procurement) · provider delivery-receipt webhooks ·
WhatsApp template status sync from a provider · tenant SMTP · scheduled broadcasts ·
absent-student alerts (PAR-10-04) · expiry reminders (PAR-18-04).

---

## 11. Phase B completion (backend)

- **Migrations:** V66 `tenant_messaging_account`; V67 `communication_template`, `broadcast`
  (append-only trigger), `notification_delivery`, `notification_channel_preference`; V68 outbox
  `attempt_count` / `next_attempt_at` / `dedupe_key` (+ due-aware partial index).
- **integration-management:** `api.MessagingChannelApi` (+ nested `Channel`, `OutboundMessage`
  with redacted `toString`, `SendReceipt`, `MessagingSendException`),
  `gateway.messaging.MessagingProviderAdapter`, `SandboxMessagingAdapter`,
  `domain.TenantMessagingAccount`, `repository.TenantMessagingAccountRepository` (no delete),
  `service.MessagingAccountService` (configure / disable / test-send / availability / send),
  `web.MessagingIntegrationController` (`/api/v1/tenant-admin/integrations/messaging`).
- **notification-management:** entities `CommunicationTemplate`, `Broadcast`,
  `NotificationDelivery`, `NotificationChannelPreference`, `DeliveryChannel`; services
  `AudienceResolver`, `BroadcastService`, `BroadcastTextRenderer`, `CommunicationTemplateService`,
  `EventTemplateService`, `NotificationPreferenceService`, `NotificationDeliveryClaimService` /
  `...DispatchService` / `...FinalizeService` / `...Poller`, `DispatchRetryPolicy`;
  `CommunicationController`; `NotificationController` + preferences / read-all; outbox retry +
  dedupe.
- **Cross-module API additions (read-only, tenant-scoped):** `StudentLookupApi.findContacts`,
  `findContactsByStudentIds`, `listAllStudentUserIds`; `StudentContact` + `whatsappNumber`,
  `accountStatus`. `common.logging.ContactMasking`. `ApiErrorCodes.CHANNEL_NOT_AVAILABLE`,
  `MESSAGING_PROVIDER_NOT_AVAILABLE`.
- **RBAC:** `DomainArea.COMMUNICATIONS` (D2), documented in `user-roles-and-permissions.md` §2.
- **Contract adjustments made during implementation** (consistency with the existing
  `/students/{id}` contracts): the audience's `studentIds` and the student history endpoint take
  **student profile ids**; messaging settings live under
  `/api/v1/tenant-admin/integrations/messaging`.

## 12. Phase C completion (frontend)

- Tenant Admin: Communication nav group; `communications` (list), `communications/new`
  (composer: audience picker, server-side preview, channel availability, templates, idempotent
  send), `communications/[broadcastId]` (summary, per-channel stats, filterable delivery log with
  masked destinations and retry state), `communications/templates` (broadcast templates, WhatsApp
  approval recording, system message editing); `settings/messaging` (write-only password inputs,
  masked hint, test send, switch off, "no provider available" state).
- Teacher: `teacher/communications` (+ new, detail) and a "Messages" nav item.
- Recipients: Notification Center "Mark all as read" and channel preferences (student and
  teacher); Student detail **Notifications** tab.
- Shared: `lib/api/communications.ts`, `components/communications/*`,
  `components/notifications/notification-preferences.tsx`, permission helpers.

## 13. Phase D — tests

| Area | Where | Result |
|---|---|---|
| Audience correctness (all 5 types, suspended exclusion, foreign / non-student ids, foreign course) | `CommunicationsBroadcastIntegrationTest` | pass |
| Send + async dispatch through adapters, masked destinations, stats, audit | same | pass |
| Retries (1 min / 5 min backoff, 3rd attempt FAILED, permanent FAILED at once, email transport retry), stuck SENDING failed forward, channel switched off after send | same + `NotificationOutboxRetryAndDedupeIntegrationTest` | pass |
| Duplicate events (broadcast replay, double dispatch, re-delivered payment event) | same | pass |
| Consent (SMS opt-out, WhatsApp opt-in), approved-template rule, edit resets approval, unknown variables rejected | `CommunicationsBroadcastIntegrationTest` | pass |
| Authorization (student, finance, auditor, student support, teacher ownership, Tenant-Admin-only actions) | `CommunicationsSecurityIntegrationTest` | pass |
| Cross-tenant (every endpoint 404; async dispatch uses the recipient tenant's own account) | same | pass |
| Secret exposure (responses, DB ciphertext, audit metadata, captured logs, `toString`, test-send destination) | same + `SandboxMessagingAdapterTest` | pass |
| RBAC matrix vs doc; dispatch unit test updated for retry-aware finalize | `PermissionCheckServiceImplTest`, `NotificationDispatchServiceTenantContextSymmetryTest` | pass |
| E2E: nav, empty state, auditor read-only, compose → preview → send → delivery log, backend refusal shown, secrets never shown, no-provider state, teacher limits, recipient mark-all-read + WhatsApp opt-in | `e2e/communications.spec.ts` (8 tests) | pass |

Full-suite results: §15.

## 14. Phase E — security, tenant-isolation and integration review

Reviewed in code against `.claude/rules/security.md`, `tenancy.md`, `backend.md`, `payments.md`.

- **Tenant isolation:** every new repository is `TenantAwareRepository`; the only cross-tenant
  queries are the four explicitly named `...AcrossTenants` claim/reconcile queries; composite
  FKs keep every reference in-tenant; no repository method takes a tenant id; async dispatch
  sets the context from the row's own `tenant_id`. Proven by the cross-tenant tests.
- **Secrets:** write-only, encrypted with row-bound AAD, masked hint, redacted `toString`, never
  in audit or logs; fails closed (503) without the key.
- **Authorization:** service-layer checks on every method; another teacher's course or
  broadcast is a 404; Tenant-Admin-only actions are double-gated.
- **Payment / ledger / enrollment:** untouched; payment-status audiences only read
  `PaymentStatusApi`.
- **Transactions:** no transaction spans an SMTP / provider call (claim → send → finalize).
- **Residual risks (accepted, documented):** (1) dispatch throughput is 50 rows per 5 s per
  instance, FIFO across tenants — a 5 000-recipient SMS broadcast takes several minutes and can
  delay other tenants' messages; tune batch size or add per-tenant fairness when a real vendor
  lands. (2) No per-tenant send quota beyond the 5 000-recipient cap (every send is
  audit-logged). (3) "All students" loads every student profile before applying the cap.
  (4) A credential email retried after a transport failure issues a fresh token per attempt;
  earlier unused tokens expire.

## 15. Phase F/G — documentation and verification

- Docs: ADR-023; `docs/api/notification-management.md` (Communications);
  `docs/api/integrations.md` (Messaging providers); `user-roles-and-permissions.md`
  (Communications row); `open-decisions.md` §22 (resolved / superseded);
  `integration-architecture.md`, `database-architecture.md`; `production-secrets-runbook.md`
  (J1); parity matrix rows PAR-03-04, PAR-12-03, PAR-12-04, PAR-21-01..03, PAR-22-01..03,
  PAR-XC-01.
- Verification (2026-10-01):
  - Backend compile clean. All Wave 11 test classes pass (10 + 6 + 3 integration, 4 unit), plus
    the updated `PermissionCheckServiceImplTest` and dispatch unit test.
  - First full backend run: 2241 tests, 4 failures — all Wave 11 expectation updates (RBAC doc
    matrix, retry-aware finalize), fixed. **Final full backend run: 2243 tests, 0 failures,
    BUILD SUCCESS.**
  - Frontend `tsc --noEmit` and ESLint clean on all changed files. `communications.spec.ts` (8),
    `notifications.spec.ts` and `devices-and-access.spec.ts` pass (39 tests).
  - **Full Playwright pass (6 shards, 1 worker each): 759 passed, 39 failed, 1 skipped (799).**
    All 39 classified:
    - 1 Wave 11 expectation change — `tenant-admin-nav.spec.ts` asserted the Communication group
      was absent; updated to assert it is shown for Tenant Admin and hidden for Course
      Coordinator. Passes.
    - 20 flaky — `course-modules` (16, dev-server 404 on an existing route) and
      `secure-video-player` (4); all pass on an isolated re-run.
    - 4 stale since AUTH-PWD-01 (`fa65faa`) — `auth-disabled-forms` (3) and `route-groups:79`
      still expect `/forgot-password` to be a disabled placeholder.
    - 14 consistently failing, **pre-existing** — `student-management` (5, add-student sheet),
      `student-actions` (2, enroll sheet), `teacher-integrations` (2), `platform-admin-audit-log`
      (2), `manual-payment-slips` (1), `material-student-types` (1), `student-profile` (1). None
      exercise a Wave 11 page; the ones whose pages share a Wave 11 edit (student detail page,
      teacher nav, tenant-admin nav) still fail with that edit temporarily removed.
  - Earlier full Playwright re-run (2 workers) reached 799/799 but the host was out of memory: 661 of its
    707 failures were `worker process exited unexpectedly (code=3221225794)` (Windows
    `STATUS_DLL_INIT_FAILED` — the OS could not start worker processes), the rest timeouts while
    the dev server was degraded. Triage rerun (fresh server, 1 worker) of every spec with a
    non-crash failure (`auth-disabled-forms`, `course-modules`, `devices-and-access`,
    `enrollment-and-course-access`, `custom-domains`): **87 passed, 3 failed**.
  - The 3 remaining failures are **pre-existing, not Wave 11**: `auth-disabled-forms.spec.ts`
    expects `/forgot-password` to be a disabled placeholder, but AUTH-PWD-01 (`fa65faa`) made it a
    real form; `route-groups.spec.ts:79` has the same stale expectation. Wave 11 did not touch
    those pages or specs. A clean full Playwright pass on a machine with enough memory is still
    outstanding.
