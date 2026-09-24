# live-class-management — API Contract

Covers Wave 4 (PAR-19-01–05, `com.lms.liveclassmanagement`) — scheduling and lifecycle
management of `ClassSession` (Zoom-style live classes), plus the `integration-management`
webhook/provider-abstraction it depends on. See `docs/parity/waves/wave-04-plan.md` for the full
design rationale, including why this is a wholly independent domain from `attendance-management`
(Path A — no FK relationship to `attendance_record`, see `docs/api/attendance-management.md`'s
`sessionId == course_lesson.id` note, which stays unchanged by this wave).

## Response envelope

Every endpoint returns `com.lms.common.api.ApiResponse<T>` — see
`docs/api/identity-access-service.md`'s "Response envelope" section for the exact shape; identical
here, not repeated.

## Auth requirements

- Every endpoint below requires a valid `Authorization: Bearer <accessToken>` header.
  `@PreAuthorize("isAuthenticated()")` on every `ClassSessionController` method is a coarse gate
  only — the real authorization check happens in the service layer
  (`ClassSessionService`/`ClassSessionSchedulingService`, via `LiveClassAccessGuard`), mirroring
  `attendance-management`'s `AttendanceAccessGuard`/`content-management`'s `MaterialAccessGuard`
  precedent.
- `teacherId` is never accepted from the client on `POST /class-sessions` — always derived
  server-side from `CourseLookupApi.getTeacherId(courseId)`. `tenantId` is always resolved from
  `TenantContext`.
- The webhook endpoint (`POST /api/v1/integrations/webhooks/live-class`, owned by
  `integration-management`) is unauthenticated by design (no subdomain/JWT context available to a
  third-party webhook) — see its own section below for the signature-verification gate that
  replaces normal auth.

## Authorization model (`LiveClassAccessGuard`)

- **Teacher/Teacher Assistant**: ownership-only — `courseLookupApi.getTeacherId(session.courseId())`
  is re-derived live and compared against `principal.userId()` on every call (never trusted from
  the stored `class_session.teacher_id` column, which exists for display/audit only). A Teacher
  may only schedule/manage/join a session within a course they actually own.
- **Student**: entitlement-checked live via `EnrollmentAccessApi.resolveAccessState(studentId,
  courseId)`, requiring `ACTIVE` — re-checked on every request, never cached from schedule time.
  **Every denial for a Student-role caller is `NotFoundException` (`404`), never
  `AccessDeniedException`** — anti-enumeration, matching `MaterialAccessGuard`'s established rule:
  a Student cannot distinguish "wrong tenant," "not enrolled," "enrollment expired," or "doesn't
  exist" from the response alone.
- **Staff**: falls through to `PermissionCheckService.requirePermission(DomainArea.LIVE_CLASSES,
  action)`. The matrix (added this wave) grants Tenant Admin `VIEW, CREATE_EDIT, DELETE, APPROVE`
  and Course Coordinator `VIEW, CREATE_EDIT, APPROVE` (no `DELETE`) — every other staff sub-role
  has no grant (absence = deny). See `docs/requirements/user-roles-and-permissions.md` §2's "Live
  classes" row for the source table this was transcribed from.

A cross-tenant or genuinely nonexistent `sessionId` is always `404` for every caller — every read
in `ClassSessionService`/`ClassSessionSchedulingService` resolves the session via the
tenant-scoped `ClassSessionRepository` (extends `TenantAwareRepository`) before the guard even
runs, so a wrong-tenant id never reaches the authorization check at all.

## Endpoints (`/api/v1/class-sessions`)

### `POST /class-sessions`

Teacher (own course) or staff (`LIVE_CLASSES`/`CREATE_EDIT`). Request body:

```jsonc
{
  "courseId": "...",
  "lessonId": null,          // optional — the course_lesson this session delivers, if any
  "title": "Week 3: Fractions",
  "description": null,       // optional, max 5000 chars
  "scheduledStart": "2026-10-01T09:00:00Z",
  "scheduledEnd": "2026-10-01T10:00:00Z"
}
```

Persists the session `SCHEDULED`/`providerStatus=PENDING` in its own transaction, commits, then
calls `LiveClassProviderApi.createMeeting(...)` **outside** that transaction (per
`.claude/rules/backend.md`'s "never span a transaction across an outbound call" rule), then
persists the provider outcome (`PROVISIONED` + `providerReference`, or `FAILED` +
`providerFailureReason`) in a second transaction. A provider failure never deletes/hides the
session — it stays `SCHEDULED`/`FAILED`, retryable via the endpoint below.

**Success — `201`** → `ApiResponse<ClassSessionResponse>` (shape below). **`404`** — `courseId`
(or `lessonId`, if supplied and not actually belonging to `courseId`) doesn't resolve in the
caller's tenant. **`403`** — same-tenant Teacher not owning `courseId`, or staff lacking
`LIVE_CLASSES`/`CREATE_EDIT`.

### `POST /class-sessions/{id}/retry-provisioning`

Same actor gate as create. No-op (returns the current state unchanged) if `providerStatus` is
already `PROVISIONED`; re-attempts `createMeeting` if `PENDING`/`FAILED`.

### `PATCH /class-sessions/{id}`

Same actor gate as create. Legal only while `status == SCHEDULED` — **`409`** otherwise. Body:
`{title, description?, scheduledStart, scheduledEnd}` (no `courseId`/`lessonId` — those are
immutable after scheduling).

### `POST /class-sessions/{id}/start` · `/complete` · `/cancel`

Status-transition endpoints, same actor gate as create. Only the legal source state is accepted
per transition (`SCHEDULED → LIVE → COMPLETED`, `SCHEDULED`/`LIVE → CANCELLED`) — an illegal
transition returns **`409`**, never a silent no-op.

### `GET /class-sessions?courseId=&status=&from=&to=`

All query params optional. Server-side entitlement-filtered by caller role: Teacher sees only
sessions for courses they own, Student sees only sessions for courses with a currently-`ACTIVE`
enrollment, staff (`LIVE_CLASSES`/`VIEW`) sees the full tenant. A client-supplied `courseId` is
never trusted alone to imply access — it narrows an already-entitlement-filtered set, never
bypasses it.

**Success — `200`** → `ApiResponse<ClassSessionResponse[]>` (unpaginated at this wave's expected
volume — see the plan doc if pagination becomes necessary later).

### `GET /class-sessions/{id}`

Same three-way gate as list, single-resource. **`404`** for cross-tenant/nonexistent/
not-entitled (Student).

### `POST /class-sessions/{id}/join`

The entitlement-checked join endpoint. Teacher: ownership only. Student: `ACTIVE` enrollment
**and** `status == LIVE` **and** `providerStatus == PROVISIONED` (a session whose meeting never
successfully provisioned is not joinable even if manually forced to `LIVE` — **`409`**). On
success, calls `LiveClassProviderApi.getJoinUrl(...)` and returns a freshly-minted URL.

**Success — `200`** → `ApiResponse<ClassSessionJoinResponse>`:

```jsonc
{ "joinUrl": "https://live-class-provider.test/join/<token>", "expiresAt": "2026-10-01T09:05:00Z" }
```

`joinUrl` is **short-lived, caller-scoped, and never reused** — call this endpoint fresh on every
click; do not cache the previous response. **`404`** — Student not entitled (anti-enumeration,
indistinguishable from nonexistent). **`409`** — session not yet `LIVE`/`PROVISIONED`. **`403`** —
Teacher not owning the course.

### `GET /class-sessions/{id}/recording`

Same entitlement gate as join, `status == COMPLETED` only (**`409`** otherwise, or `404` if no
recording has been delivered yet by the provider webhook).

**Success — `200`** → `ApiResponse<ClassSessionRecordingResponse>`:

```jsonc
{ "playbackUrl": "https://live-class-provider.test/recording/<token>", "expiresAt": "2026-10-01T10:15:00Z" }
```

Same short-lived, never-cached, never-reused contract as `joinUrl`.

## `ClassSessionResponse` shape

```jsonc
{
  "id": "...",
  "courseId": "...",
  "teacherId": "...",
  "lessonId": null,                 // nullable — the course_lesson this session delivers, if any
  "title": "Week 3: Fractions",
  "description": null,
  "scheduledStart": "2026-10-01T09:00:00Z",
  "scheduledEnd": "2026-10-01T10:00:00Z",
  "status": "SCHEDULED",            // SCHEDULED | LIVE | COMPLETED | CANCELLED
  "meetingProvider": "ZOOM",
  "providerStatus": "PENDING",      // PENDING | PROVISIONED | FAILED
  "providerFailureReason": null,
  "createdAt": "2026-10-01T08:00:00Z",
  "updatedAt": "2026-10-01T08:00:00Z"
}
```

**`providerReference` (the opaque meeting-provider id) is never included** — it is an internal
value used only to resolve webhook events and mint fresh join/playback URLs server-side, never
exposed to any client (`.claude/rules/security.md`'s "never a stable/reusable URL" rule applied to
the identifier that would let one be reconstructed).

## Webhook: `POST /api/v1/integrations/webhooks/live-class`

Owned by `integration-management`, structurally identical in shape/rationale to
`POST /api/v1/integrations/webhooks/payment` (see `PaymentWebhookController`'s own javadoc).
Excluded from subdomain/tenant resolution and `permitAll()` at the security-config level, since a
third-party webhook carries no session/JWT.

- **Signature verification happens first**, before any parsing or state change — a request with a
  missing/invalid signature header (see `LiveClassProviderProperties.webhookSecret`, env-var-backed,
  empty-default **fail-closed**) is rejected with zero rows written anywhere.
- **Tenant identity is never trusted from the payload** — resolved solely via
  `ClassSessionRepository.findByProviderReferenceAcrossTenants(...)`, keyed by the platform's own
  `provider_reference`, matching `PaymentWebhookController`/`PaymentConfirmationService`'s
  established webhook-trust posture.
- **Idempotent by schema**: `class_session_provider_event.uq_class_session_provider_event
  UNIQUE (provider, provider_event_id)` — a duplicate delivery of the same provider event id hits
  this constraint (caught, returns `200 OK` no-op) rather than double-writing a recording row or
  double-publishing an event.
- Recognized event types: `recording.completed` (creates/updates `ClassSessionRecording`),
  `session.attendance_synced` (publishes `ClassSessionAttendanceSyncedEvent` —
  **publish-only this wave**; no listener exists yet in `attendance-management`, deferred to
  Wave 8 per the plan's §11 and `PAR-10-03`'s matrix row).

## Cross-module contract (not REST — recorded here since no other file documents it)

- `CourseLookupApi.getTeacherId(UUID courseId)` — already existed; reused for Teacher-ownership
  checks in `LiveClassAccessGuard` and for deriving `teacherId` server-side at schedule time.
- `EnrollmentAccessApi.resolveAccessState(UUID studentId, UUID courseId)` — already existed
  (`docs/api/enrollment-management.md`); reused, unchanged, as the sole source of Student
  join/view entitlement truth.
- `integrationmanagement.api.LiveClassProviderApi` — **new**, this wave's meeting-provider
  abstraction (`createMeeting`/`getJoinUrl`/`getRecordingPlaybackUrl`/`cancelMeeting`/
  `verifySignature`). Implemented by `FakeZoomLiveClassProviderAdapter` — deterministic, no real
  Zoom SDK/credentials (none exist in this environment; a real adapter is a future, explicitly
  deferred addition, see the plan's §11).
- `liveclassmanagement.api.ClassSessionAttendanceSyncedEvent` — **new**, published (not yet
  consumed) by the webhook path for the future `attendance-management` listener (`PAR-10-03`,
  Wave 8).

All resolve tenant identity exclusively from the trusted request context (or, for the webhook
path, from the platform's own `provider_reference` lookup) — mirroring every other cross-module
`api` interface's established discipline in this codebase.
