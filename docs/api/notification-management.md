# notification-management — API Contract

Covers MVP-018 "Email Notifications" (`com.lms.notificationmanagement`) — the Notification
Center list/mark-read/unread-count endpoints (plan §9.5/§10, Flow C/D). Written after the
fact once the module was reviewed, following the same "process gap" this project's other API
contract files record (`docs/api/attendance-management.md`, `docs/api/payment-management.md`,
`docs/api/course-management.md`, `docs/api/enrollment-management.md`): the plan
(`docs/plans/MVP-018 Email Notifications.md` §10) sketched a draft contract before
implementation, but the finalized doc was never produced until a post-ship multi-agent review
found the gap. This file reflects the actual shipped backend (`NotificationController`), not
the plan's pre-implementation draft — including `GET /unread-count`, which did not exist in
the plan's own §10 table and was added during the post-ship remediation pass (see
`docs/requirements/open-decisions.md`'s notification-management entries).

There is no endpoint here for `notification_outbox` or `notification_template` — no
delivery-log/template-management UI exists to call one, matching the plan's own explicit
scope decision (§10).

## Response envelope

Every endpoint returns `com.lms.common.api.ApiResponse<T>` — see
`docs/api/identity-access-service.md`'s "Response envelope" section for the exact shape
(`success`/`data`/`error`/`timestamp`/`traceId`); identical here, not repeated. The list
endpoint wraps `com.lms.common.api.PageResponse<T>` inside that envelope, matching
`docs/api/attendance-management.md`/`docs/api/course-management.md`'s established
`PageResponse` shape.

## Auth requirements

- Every endpoint below requires a valid `Authorization: Bearer <accessToken>` header —
  enforced platform-wide by `SecurityFilterChainConfig`'s `authorize.anyRequest().authenticated()`
  default, not by an endpoint-local `@PreAuthorize` marker.
- **No `DomainArea`/permission-matrix grant is used anywhere in this controller** (plan
  §2/§15) — every endpoint is self-service, scoped to "my own records only," the same
  authorization shape as a student's own exam-attempt history. This means `NotificationController`
  intentionally works identically for any authenticated role: Student today, and a Teacher's
  own (currently empty-by-construction) activity feed, per plan §2/§4 Flow D — there is no
  role check to add or misconfigure here.
- `recipient_user_id` and `tenant_id` are never accepted from the client on any request —
  every method resolves them exclusively from `AuthenticatedPrincipalHolder.get()` /
  the trusted tenant context, never a path/query/body parameter.

## Authorization model

`NotificationCenterService`'s three methods each independently re-derive
`AuthenticatedPrincipalHolder.get().userId()` and pass it into every
`InAppNotificationRepository` call — this is a **second, manual scoping dimension on top
of** the structural `tenant_id` filter every tenant-aware repository already applies, since
`TenantAwareRepository` only enforces `tenant_id`, not `recipient_user_id` (per plan §9.5's
explicit finding: two different users in the *same* tenant must not see each other's
notifications, a same-tenant BOLA case distinct from cross-tenant isolation).

A notification id that doesn't resolve to the caller's own row — whether it belongs to
another tenant entirely, or to a different user within the caller's own tenant — is always
`404 NotFoundException`, **never `403`**, per plan §10's explicit anti-enumeration
requirement: existence of another user's notification must never be revealed by the status
code alone. Both cases are deliberately indistinguishable from the caller's point of view.

## Endpoints

### `GET /api/v1/notifications`

Self-service, any authenticated recipient. Standard Spring `Pageable` query params
(`page`/`size`/`sort`), default `size=20`, `sort=createdAt,DESC`
(`@PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)`).
Requested page size is clamped server-side to a maximum of 100
(`NotificationCenterService.MAX_PAGE_SIZE`, mirroring `ExamAttemptService`'s established
defensive clamp) — a caller requesting a larger size silently gets 100, not a validation
error.

**Success — `200`** → `ApiResponse<PageResponse<NotificationResponse>>`, filtered to
`(tenant_id, recipient_user_id)` derived from the authenticated principal — never a
path/query parameter.

```jsonc
{
  "content": [
    { "id": "...", "title": "Payment confirmed", "body": "...", "readAt": null, "createdAt": "2026-01-15T09:32:00Z" }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

### `GET /api/v1/notifications/unread-count`

Self-service, any authenticated recipient. No parameters. Backs the Notification Center nav
badge — added post-plan (see the "Grounding note" above) once a post-ship review found the
badge feature had no backend data source.

**Success — `200`** → `ApiResponse<UnreadCountResponse>`:

```jsonc
{ "unreadCount": 3 }
```

`unreadCount` is `count(*)` over the caller's own `(tenant_id, recipient_user_id)`-scoped
`in_app_notification` rows where `read_at IS NULL` — the same double-scoping discipline as
the list endpoint, via `InAppNotificationRepository.countByRecipientUserIdAndReadAtIsNull`.

### `PATCH /api/v1/notifications/{id}/read`

Self-service, owner only. Path param `id` (UUID). Updates `read_at` (server-generated
`Instant.now()`, never client-supplied) only on a row the caller owns.

**Success — `200`** → `ApiResponse<NotificationResponse>`, the updated row.

**`404`** (never `403`) if `id` doesn't resolve to a row owned by the caller in their own
tenant — see "Authorization model" above.

## `NotificationResponse` shape

Returned inside `PageResponse` on the list endpoint and as the body of a successful
mark-read call:

```jsonc
{
  "id": "...",
  "title": "Payment confirmed",
  "body": "Your payment of 50.00 USD has been confirmed.",
  "readAt": null,           // null until marked read; server-set Instant afterward
  "createdAt": "2026-01-15T09:32:00Z"
}
```

## Cross-module contract (not REST — recorded here since no other file documents it)

This module has no `api` package exposed outward (plan §7 — "no other domain calls into
this one synchronously"); it only *consumes* other domains' `api` packages and domain
events:

- `UserProvisioningApi.findTenantUserSummaries` (`identity-access-service`) — resolves a
  dispatch row's recipient email at send time, strictly by `(tenant_id, recipientUserId)`,
  never by email lookup (plan §14 — prevents a same-email collision across two different
  tenants from cross-matching a recipient).
- `MessagingProviderApi.sendEmail` (`integration-management`, new in this module) — the
  only outbound email path; `notification-management` never holds SMTP credentials itself.
- `PaymentConfirmedEvent`/`PaymentRejectedEvent`/`PaymentRefundedEvent`
  (`payment-management`) and `TenantRegisteredEvent` (`tenant-management`, added during
  this module's post-ship remediation to seed default `notification_template` rows at
  tenant-registration time — see `docs/requirements/open-decisions.md`) — consumed via
  `@TransactionalEventListener(phase = AFTER_COMMIT)`, never a synchronous call into either
  domain.

None of the above are REST contracts; they exist purely as in-process `api`-package
interfaces/domain events per `.claude/rules/architecture.md`'s cross-module communication
rules.

Wave 11 adds: `MessagingChannelApi` (`integration-management`, SMS/WhatsApp send +
availability for the current tenant), `StudentLookupApi.findContacts` /
`findContactsByStudentIds` / `listAllStudentUserIds`, `TeacherLookupApi`,
`EnrollmentAccessApi.listCurrentlyEnrolledStudentIds`, `CourseLookupApi`
(`getCourseSummaries`, `getTeacherId`) and `PaymentStatusApi` (order payment details) for
audience resolution — all tenant-scoped, read-only.

---

# Wave 11 — Communications (ADR-023)

Broadcasts, broadcast templates, system (event) templates, delivery logs and recipient
preferences. Tenant from the authenticated context only. Permissions: `COMMUNICATIONS`
(Tenant Admin, Student Support `V/C/E`; Read-only Auditor `V`); teachers are
ownership-scoped (own course, in-app/email only, own broadcasts only).

## Transactional outbox changes (V68)

- Bounded retry: an SMTP/transport failure (`MailException`) returns the row to `PENDING`
  with `next_attempt_at` (+1 min, then +5 min); the 3rd failure is `FAILED`. Recipient,
  template and render errors are `FAILED` at once. Stuck `SENDING` rows are still failed
  forward (never re-sent).
- Duplicate events: payment outcome rows carry `dedupe_key`
  (`PAYMENT_CONFIRMED:{paymentId}`, `PAYMENT_REJECTED:{paymentId}`,
  `PAYMENT_REFUNDED:{refundId}`); a re-delivered event is ignored (unique per tenant).

## Endpoints

| Method | Path | Who | Notes |
|---|---|---|---|
| GET | `/api/v1/communications/channels` | VIEW or Teacher | `[{channel, available, allowedForYou, reason}]`; reason `NOT_CONFIGURED` / `PROVIDER_DISABLED_IN_ENVIRONMENT` |
| POST | `/api/v1/communications/audience-preview` | CREATE_EDIT or Teacher (own course) | body = `AudienceRequest`; returns `{recipientCount, reachByChannel, sampleNames≤10}` — never addresses |
| GET | `/api/v1/communications/broadcasts?page&size` | VIEW (all) / Teacher (own) | `PageResponse<BroadcastView>`, newest first |
| POST | `/api/v1/communications/broadcasts` | CREATE_EDIT or Teacher | **201** new, **200** replay of the same `clientRequestId` by the same sender (`replayed: true`), 409 if another sender used that id |
| GET | `/api/v1/communications/broadcasts/{id}` | VIEW / Teacher (own) | 404 cross-tenant or another teacher's |
| GET | `/api/v1/communications/broadcasts/{id}/deliveries?status&channel&page&size` | same | `PageResponse<DeliveryView>`; destinations masked |
| GET | `/api/v1/communications/students/{studentId}/deliveries` | VIEW | `studentId` = student profile id; 404 cross-tenant |
| GET | `/api/v1/communications/templates?includeArchived` | VIEW (teachers: active only) | `TemplateView[]` |
| POST | `/api/v1/communications/templates` | CREATE_EDIT | 201; name unique (case-insensitive) among active |
| PUT | `/api/v1/communications/templates/{id}` | CREATE_EDIT | editing copy of a SUBMITTED/APPROVED template resets WhatsApp status to NOT_SUBMITTED |
| POST | `/api/v1/communications/templates/{id}/archive` | CREATE_EDIT | archive, never delete |
| POST | `/api/v1/communications/templates/{id}/whatsapp-status` | CREATE_EDIT **and** INTEGRATIONS/CREATE_EDIT | `{action: SUBMIT\|APPROVE\|REJECT, providerTemplateName?, reason?}`; illegal transition → 409 |
| GET | `/api/v1/communications/event-templates` | VIEW | one entry per system message key, with allowed/required variables |
| PUT | `/api/v1/communications/event-templates/{key}` | CREATE_EDIT **and** Tenant Admin | variables restricted per key; credential templates must keep `{{link}}` |
| GET / PUT | `/api/v1/notifications/preferences` | self | `{EMAIL?, SMS?, WHATSAPP?: boolean}`; WhatsApp is opt-in |
| POST | `/api/v1/notifications/read-all` | self | `{updated: n}` |

### `AudienceRequest`

```json
{ "type": "ALL_STUDENTS | COURSE | SELECTED_STUDENTS | ALL_TEACHERS | PAYMENT_STATUS",
  "courseId": "uuid (COURSE required; PAYMENT_STATUS optional)",
  "paymentStatus": "PAID | PENDING | REJECTED (PAYMENT_STATUS only)",
  "studentIds": ["student profile ids (SELECTED_STUDENTS only, ≤ 5000)"] }
```

Suspended/invited accounts are always excluded. Any foreign or non-student id in
`studentIds` → 400 (nothing sent). A foreign `courseId` → 404. More than 5 000 recipients →
400. Payment status is evaluated per order: PAID = a confirmed payment; PENDING = an open
attempt and nothing confirmed; REJECTED = only rejected attempts.

### `SendBroadcastRequest`

```json
{ "clientRequestId": "uuid (idempotency key)",
  "subject": "≤ 200", "body": "≤ 5000", "smsBody": "≤ 640, optional",
  "templateId": "uuid, optional (required for WHATSAPP and must be APPROVED)",
  "channels": ["IN_APP", "EMAIL", "SMS", "WHATSAPP"],
  "audience": { ... } }
```

Variables: only `{{name}}` and `{{instituteName}}` (400 otherwise). Plain text. SMS/WhatsApp
send `smsBody`, else `body` (then `body` must be ≤ 640). A channel not available for the
tenant → 409 `CHANNEL_NOT_AVAILABLE`. IN_APP notifications are written in the same
transaction; other channels are queued (`notification_delivery`) for the async dispatcher.

### Delivery statuses and codes

`PENDING` (queued or waiting for retry) → `SENDING` → `SENT` | `FAILED` | `SKIPPED`.
`errorCode`: `NO_ADDRESS`, `NO_CONSENT`, `USER_INACTIVE`, `CHANNEL_NOT_AVAILABLE` (skipped);
`PROVIDER_TRANSIENT`, `EMAIL_TRANSIENT` (retried), `PROVIDER_REJECTED`, `STUCK_IN_SENDING`,
`INTERNAL_ERROR` (failed). Codes are fixed strings — never provider response text.

### Audit actions

`communication.broadcast_sent`, `communication_template.created|updated|archived|whatsapp_status_changed`,
`notification_template.updated` (before/after). Broadcast bodies are not copied into audit
metadata (the `broadcast` row itself is append-only).
