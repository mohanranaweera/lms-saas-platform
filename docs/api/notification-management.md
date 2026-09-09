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
