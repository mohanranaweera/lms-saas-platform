# Wave 4 Plan — Class Sessions + Zoom/Meeting Integration

Status: **DONE.** Backend (`live-class-management` domain, `LiveClassProviderApi`/
`FakeZoomLiveClassProviderAdapter`, webhook, `LIVE_CLASSES` permission row), frontend (Teacher/
Student/Tenant Admin screens + nav), tests (backend JUnit/Testcontainers including cross-tenant/
webhook-idempotency/secret-fail-closed; frontend Playwright), security review, tenant-isolation
review, and UI/UX review are all complete — see the Wave 4 completion report appended at the end
of this document for the full verification summary. One item is intentionally deferred, not
silently skipped: `PAR-10-03`'s attendance-sync **consumer** side (the event is published this
wave; no listener exists yet in `attendance-management` — Wave 8, per §11 below).
Parity IDs in scope: **PAR-19-01, PAR-19-02, PAR-19-03, PAR-19-04, PAR-19-05**, plus
**PAR-10-01** (interaction-only — attendance's existing behavior is verified unchanged, not
modified) and **PAR-10-03** (event-contract side only; full consumption deferred to Wave 8, per
`implementation-roadmap.md` row "4 | ClassSession and Zoom/meeting integration |
PAR-19-01–05, PAR-10-01 (interaction only), PAR-10-03").

This plan adopts, verbatim, the pre-existing architectural resolution recorded in
`docs/parity/migration-strategy.md` §3 ("ClassSession introduction") and
`docs/parity/implementation-roadmap.md` §2 (PAR-19-02's `ARCHITECTURAL_CONFLICT` entry): **Path
A**. `class_session` is a new, independent table. `attendance_record.session_id`'s existing
composite FK into `course_lesson (tenant_id, id)` (V25, MVP-016, product-owner-confirmed) is
**not** touched, repointed, or backfilled. No edit to any already-applied migration.

---

## 1. Phase A — Analysis (findings)

### Does a session-equivalent domain already exist?

**No real `ClassSession`/scheduling domain exists.** What exists today:

- `CourseLesson` (`com.lms.coursemanagement.course.domain`, table `course_lesson`, V11/V15) is a
  **structural, non-scheduled** child of `CourseModule` — `title` + `sequence` only, no
  start/end time, no status, no meeting concept. It is course *content structure*, not a
  calendar event. The wave brief's instruction "do not misuse Lesson as a scheduled teaching
  session" is already a live constraint in this codebase, not a hypothetical: `CourseLesson` has
  no fields to support one.
- `AttendanceRecord.sessionId` (`com.lms.attendancemanagement.domain`, table `attendance_record`,
  V25) is a composite FK **directly into `course_lesson (tenant_id, id)`**. This is not an
  oversight — the attendance spec (`docs/requirements/specifications/10-attendance.md`,
  "Resolved during MVP-016 planning") records an explicit, product-owner-confirmed decision: *"no
  new `class_session` table exists; session-equivalent scope = `course_lesson.id`"*, with the
  known, accepted limitation that a weekly class reusing one lesson overwrites the prior week's
  marks. `CourseLookupApi.resolveLessonOwnership(UUID lessonId)` is what
  `AttendanceMarkingService` calls (its own javadoc literally names the parameter `sessionId` at
  the call site, despite the method being `resolveLessonOwnership(UUID lessonId)` — today,
  session and lesson are the same id).
- No Zoom/meeting-provider code exists anywhere in `integrationmanagement` (only
  `PaymentGatewayApi`/`FakePaymentGatewayAdapter`, `MessagingProviderApi`/SMTP,
  `ObjectStorageApi`/unavailable-stub). `PaymentGatewayApi` + `FakePaymentGatewayAdapter` +
  `PaymentGatewayProperties` + `WebhookSignatureVerifier` + `PaymentWebhookController` is the
  exact structural precedent this wave's meeting-provider abstraction follows (generic
  vendor-free interface in `api`, an env-var-backed `@ConfigurationProperties` secret with a
  fail-closed empty default, a deterministic no-network "Fake" adapter since no real Zoom
  credentials exist in this environment, and a dedicated `integration-management`-owned webhook
  controller with signature-verification-before-parsing).
- `docs/requirements/specifications/19-zoom-live-classes.md` and `module-catalog.md` confirm the
  domain name and ownership: **`live-class-management` (Module 9)**, one of the 17 confirmed
  backend domains in `.claude/rules/architecture.md` — this is not a new domain invented for this
  wave, it is a domain the architecture already reserves and this wave is the first to implement.
  It "consumes `integration-management`'s Zoom API interface (never embeds the Zoom SDK/
  credentials directly); publishes attendance-sync events consumed by `attendance-management`."
- No `recording`/attendance-sync consumption code exists (`PAR-10-03`, `PAR-19-05`:
  `MISSING_WORKFLOW`).
- `docs/parity/klass-parity-matrix.md` PAR-19-03 flags a genuine permission-matrix gap: no
  `DomainArea` row covers "who may schedule a live class." `DomainArea`
  (`identityaccessservice.api`) is a fixed, code-level transcription of
  `docs/requirements/user-roles-and-permissions.md` §2's matrix — this wave adds a new
  `LIVE_CLASSES` row to both the enum and that source doc's table (Phase F), rather than
  overloading `COURSES`.
- Precedent for protected-content entitlement checks already exists:
  `MaterialAccessGuard`/`CourseAccessGuard`/`AttendanceAccessGuard` — Teacher/TA ownership via
  `CourseLookupApi`, Student via `NotFoundException`-on-denial anti-enumeration (never a plain
  403, so a Student cannot distinguish "wrong tenant" from "not enrolled" from "doesn't exist").
  Crucially, `MaterialAccessGuard`'s Student branch checks only `coursePublished()`, **not**
  enrollment — that is insufficient for live-class join access. This wave's student join-access
  check must call `enrollmentmanagement.api.EnrollmentAccessApi#resolveAccessState(studentId,
  courseId)` and require `ACTIVE`, mirroring how `EnrollmentActivationService` is the only
  trusted source of activation truth per `.claude/rules/payments.md` §1.
- No object/video storage pattern applies directly (Zoom hosts its own recordings), but
  `MaterialController`'s short-lived signed-download-URL pattern
  (`MaterialDownloadUrlResponse`) is the precedent this wave follows for **never persisting a
  stable, reusable join URL or recording URL** — every join/playback link is minted fresh,
  server-side, at request time, scoped to the caller and short-lived, per
  `.claude/rules/security.md`'s "Video & Session Protection" section. (`19-zoom-live-classes.md`'s
  "Open decisions" flags this as *not explicitly specified for Zoom* — this plan resolves that
  open decision by applying the same rule to live-class join/recording URLs as the codebase
  already applies to video, since the underlying risk — a guessable/reusable/replayable
  URL — is identical. Flagged as a judgment call, Section 10.)

### Verdict

Build a real `ClassSession` entity under a new `live-class-management` domain package
(`com.lms.liveclassmanagement`), decoupled from `attendance_record`'s existing session scope.
Optionally *associate* a `ClassSession` with the `CourseLesson` it delivers (nullable
`lessonId`), so a Teacher who schedules a live class for a specific lesson can still mark
attendance through the **existing, unchanged** attendance flow using that same lesson id — no
new attendance code path, no FK change, no migration risk. Zoom-synced automatic attendance
(`PAR-10-03`'s full consumption side) stays explicitly deferred to Wave 8, matching the roadmap.

---

## 2. Target behavior (this wave)

| Parity ID | Target | In scope this wave? |
|---|---|---|
| PAR-19-01 | `LiveClassProviderApi` abstraction in `integration-management`, with a deterministic `Fake`/Zoom-shaped adapter (no real Zoom SDK/credentials) | **Yes** |
| PAR-19-02 | Real `ClassSession` domain, lifecycle `SCHEDULED → LIVE → COMPLETED / CANCELLED`, distinct from `Lesson`, Path A (no attendance FK change) | **Yes** |
| PAR-19-03 | Teacher "Schedule Live Class" screen + resolved `LIVE_CLASSES` permission-matrix row | **Yes** |
| PAR-19-04 | Student "Live Classes" (upcoming/past) list + entitlement-checked join | **Yes** |
| PAR-19-05 | Recording import + attendance-sync event surface, consumed only via `api`/events, never a direct table join | **Yes (publishing side only — see below)** |
| PAR-10-01 | Existing manual attendance flow (`course_lesson`-scoped) | **Verify unchanged only, no code change** |
| PAR-10-03 | Zoom-synced attendance full consumption by `attendance-management` | **Event contract only — listener/consumption explicitly deferred (Wave 8)** |

---

## 3. Database impact (additive Flyway migrations only, next available version)

New domain package: `com.lms.liveclassmanagement` (`domain`, `repository`, `service`, `web`,
`web/dto`, `api`, `support`), per `.claude/rules/architecture.md`'s package structure.

1. **`V48__create_live_class_management_schema.sql`**:
   - `class_session` — `id, tenant_id NOT NULL REFERENCES tenant(id), course_id NOT NULL,
     teacher_id NOT NULL, lesson_id (nullable), title NOT NULL, description (nullable),
     scheduled_start TIMESTAMPTZ NOT NULL, scheduled_end TIMESTAMPTZ NOT NULL,
     status VARCHAR NOT NULL CHECK IN ('SCHEDULED','LIVE','COMPLETED','CANCELLED'),
     meeting_provider VARCHAR NOT NULL CHECK IN ('ZOOM'), provider_status VARCHAR NOT NULL CHECK
     IN ('PENDING','PROVISIONED','FAILED'), provider_reference (nullable — the opaque Zoom
     meeting id, never a join URL), provider_failure_reason (nullable),
     created_at/updated_at/created_by/updated_by (Auditable)`.
     - `fk_class_session_course`: composite `(tenant_id, course_id) REFERENCES course
       (tenant_id, id)`.
     - `fk_class_session_lesson`: composite `(tenant_id, lesson_id) REFERENCES course_lesson
       (tenant_id, id)`, nullable column so the FK is only enforced when set.
     - `fk_class_session_teacher`: composite `(tenant_id, teacher_id) REFERENCES tenant_user
       (tenant_id, id)`.
     - No `ON DELETE CASCADE` — mirrors V25's "academic/scheduling history outlives its parent"
       precedent; deleting a course with existing sessions is blocked, not silently cascaded.
     - Indexes: `(tenant_id, course_id, scheduled_start)` (course schedule view),
       `(tenant_id, teacher_id, scheduled_start)` (teacher's own schedule),
       `(tenant_id, status, scheduled_start)` (upcoming/live dashboard queries).
   - `class_session_recording` — `id, tenant_id NOT NULL, session_id NOT NULL, status VARCHAR
     NOT NULL CHECK IN ('PENDING','AVAILABLE','FAILED'), provider_recording_reference (nullable —
     opaque, never a playback URL), duration_seconds (nullable), created_at/updated_at`.
     - `fk_class_session_recording_session`: composite `(tenant_id, session_id) REFERENCES
       class_session (tenant_id, id)`.
     - Index `(tenant_id, session_id)`.
   - `class_session_provider_event` — webhook idempotency ledger, append-only (mirrors
     `audit-log-management`'s append-only pattern): `id, tenant_id (nullable — resolved from the
     platform's own provider-reference record, not trusted from the payload, and may be
     unresolvable for a malformed/unknown event), provider VARCHAR NOT NULL, provider_event_id
     VARCHAR NOT NULL, event_type VARCHAR NOT NULL, received_at TIMESTAMPTZ NOT NULL,
     processed BOOLEAN NOT NULL DEFAULT false`.
     - `uq_class_session_provider_event`: `UNIQUE (provider, provider_event_id)` — the
       idempotency gate (Zoom's own event id is unique per provider regardless of tenant, since
       tenant resolution happens only *after* looking up the referenced meeting).
   All additive, no edit to V1–V47, no backfill (`class_session` starts empty — no pre-existing
   scheduled-class data anywhere in this codebase to migrate).

2. **Permission matrix**: `DomainArea.LIVE_CLASSES` — **code-only enum addition**
   (`identityaccessservice.api.DomainArea`), no migration (matrix is a static code table, not a
   DB seed, per `PermissionCheckServiceImpl`). Grants (Phase F also updates
   `docs/requirements/user-roles-and-permissions.md` §2's source table to keep it in sync):
   `TENANT_ADMIN`: `VIEW, CREATE_EDIT, DELETE, APPROVE` (mirrors its full-grant pattern
   elsewhere). `COURSE_COORDINATOR`: `VIEW, CREATE_EDIT, APPROVE` — no `DELETE`, per the parity
   matrix's own "Course Coordinator V/C/E/A" note for PAR-19-02/19-03. All other staff roles:
   no grant (absence = deny, matching e.g. `EXAM_MANAGER` having no `ATTENDANCE` row). Teacher/
   Student stay outside the flat matrix — ownership/enrollment-scoped via a new
   `LiveClassAccessGuard`, exactly like `MaterialAccessGuard`/`CourseAccessGuard`.

---

## 4. API impact

All new, versioned under `/api/v1`, DTO-only (never a JPA entity in a response), each gets an
explicit auth/permission/tenant-behavior/DTO/error entry in `docs/api/` (Phase F).

**`live-class-management`** (`/api/v1/class-sessions`)
- `POST /class-sessions` — Teacher (own course only, via `LiveClassAccessGuard`) or staff
  (`LIVE_CLASSES`/`CREATE_EDIT`). Body: `{courseId, lessonId?, title, description?,
  scheduledStart, scheduledEnd}`. Persists `SCHEDULED`/`PENDING` provider status **first, in its
  own transaction**, commits, then calls `LiveClassProviderApi.createMeeting(...)` **outside**
  that transaction (per `.claude/rules/backend.md`'s "never span a transaction across an outbound
  call" rule), then persists the returned `provider_reference`/`PROVISIONED` (or
  `FAILED`/reason) in a second transaction. A provider failure never deletes/hides the session —
  it stays `SCHEDULED` with `provider_status=FAILED`, retryable.
- `POST /class-sessions/{id}/retry-provisioning` — same actor gate as create; idempotent no-op
  if already `PROVISIONED`; re-attempts `createMeeting` if `FAILED`/`PENDING`.
- `PATCH /class-sessions/{id}` — reschedule/retitle, same actor gate, only while `SCHEDULED`.
- `POST /class-sessions/{id}/start`, `POST /class-sessions/{id}/complete`,
  `POST /class-sessions/{id}/cancel` — status-transition endpoints, same actor gate,
  transition-guarded exactly like `TeacherProfile.approve()/reject()` (`requireX()` helpers, only
  legal source states accepted, `IllegalStateException`→409 on an illegal transition).
- `GET /class-sessions` (list, filtered by course/status/date range) — Teacher (own courses),
  staff (`LIVE_CLASSES`/`VIEW`), Student (enrolled courses only, entitlement-filtered
  server-side, never trusting a client `courseId` filter to imply access).
- `GET /class-sessions/{id}` — same three-way gate as list, single-resource shaped like
  `MaterialAccessGuard`'s Student anti-enumeration rule (`NotFoundException`, never 403, for a
  Student addressing a session they're not entitled to).
- `POST /class-sessions/{id}/join` — the entitlement-checked join endpoint. Teacher: ownership
  check only. Student: `EnrollmentAccessApi.resolveAccessState(studentId, courseId) == ACTIVE`
  **and** session `status == LIVE` (or a configurable pre-join grace window —
  Section 10 judgment call) required; on success, calls
  `LiveClassProviderApi.getJoinUrl(providerReference, role, displayName)` and returns a
  short-lived, single-use, caller-scoped join URL — **never a stored/reusable one**. Every
  denial for a Student is `NotFoundException` (anti-enumeration, matching
  `MaterialAccessGuard`).
- `GET /class-sessions/{id}/recording` — same entitlement gate as join, `COMPLETED` sessions
  only; returns a short-lived signed playback reference via
  `LiveClassProviderApi.getRecordingPlaybackUrl(...)`, never a stored URL.

**`integration-management`**
- `LiveClassProviderApi` (new `api` interface): `createMeeting(tenantId, sessionId, title,
  scheduledStart, scheduledEnd) -> MeetingCreationResult(providerReference)`,
  `getJoinUrl(providerReference, tenantId, participantRole, displayName) ->
  ShortLivedJoinLink(url, expiresAt)`, `getRecordingPlaybackUrl(providerRecordingReference,
  tenantId) -> ShortLivedPlaybackLink(url, expiresAt)`, `cancelMeeting(providerReference)`,
  `verifyWebhookSignature(rawBody, signatureHeader)`. `FakeZoomLiveClassProviderAdapter`
  (`integrationmanagement.gateway`, mirrors `FakePaymentGatewayAdapter` exactly): deterministic,
  no outbound network call, no vendor SDK, generates a `FAKE-ZOOM-<uuid>` reference and
  `https://live-class-provider.test/join/<token>`-shaped short-lived URLs.
  `LiveClassProviderProperties` (`@ConfigurationProperties(prefix = "live-class.provider")`):
  `webhookSecret` (env-var-backed, empty default, fail-closed — mirrors
  `PaymentGatewayProperties` exactly).
- `POST /api/v1/integrations/webhooks/live-class` (`LiveClassWebhookController`, new, alongside
  `PaymentWebhookController`) — signature-verified **before** any parsing/state change (hard
  gate). Parses a generic `{eventId, eventType, providerReference, ...}` payload. Idempotency:
  inserts into `class_session_provider_event` first (`UNIQUE (provider, provider_event_id)`); a
  duplicate delivery hits the unique-constraint violation, is caught, and returns `200 OK`
  no-op (never a 500, never a second state mutation) — this is the required "duplicate/retry
  behavior" test target (Section 8). Recognized event types: `recording.completed` (creates/
  updates `ClassSessionRecording`), `session.attendance_synced` (publishes
  `ClassSessionAttendanceSyncedEvent` — **published only**, no consumer in
  `attendance-management` this wave, explicitly deferred to Wave 8 per Section 11). Tenant
  identity for the resulting state change is resolved from the **platform's own** `class_session`
  row matched by `provider_reference` — never trusted from the webhook payload, per
  `.claude/rules/security.md`'s "Meeting access must be entitlement checked server-side" /
  tenancy.md's "tenant identity from trusted authenticated context" applied to the
  webhook-resolution equivalent.

---

## 5. Frontend impact

- **Teacher** (`(teacher)/teacher/live-classes`): list (own courses, status filter), `new`
  (schedule form: course, optional lesson link, title, description, start/end), detail
  (status/provider-status badges, start/complete/cancel/retry-provisioning actions, join-as-host
  button once `LIVE`), edit (while `SCHEDULED` only). Course detail workspace gets its
  previously-blocked "Live Sessions" tab unblocked (`course-management`'s structural placeholder
  noted in `implementation-roadmap.md` §8 item 4 — PAR-05-06).
- **Student** (`(student)/student/live-classes`): Upcoming/Past tabs, entitlement-filtered
  server-side (the list endpoint only ever returns sessions for the student's own currently-
  active enrollments — client never filters). Join button enabled only while `LIVE`, calls the
  join endpoint and opens the short-lived URL in a new tab (never embeds/proxies the Zoom
  iframe — out of scope, no self-hosted media per `.claude/rules/architecture.md`). Past tab
  shows a "Recording available" affordance calling the recording endpoint.
- **Tenant Admin** (`(tenant-admin)/tenant-admin/live-classes`): tenant-wide oversight list
  (all courses, all teachers), read-heavy (`LIVE_CLASSES`/`VIEW`), surfaces `provider_status`
  failures distinctly (a filterable "Failed" status) so a Tenant Admin can see a stuck
  provisioning without digging through Teacher-side screens — matches
  `19-zoom-live-classes.md`'s "Zoom health-check failure... must not silently degrade
  scheduling" requirement. No real Zoom-account-connection UI this wave (no real Zoom
  credentials exist in this environment; the Fake adapter needs no tenant-supplied API key) —
  flagged as an explicit deferral (Section 11), not a silent gap.
- All new screens get the full loading/empty/error/permission-denied/validation/success state
  set (`QueryStateBoundary` + existing patterns), matching every prior wave's baseline.
- New `frontend/src/lib/api/class-sessions.ts` client, following the existing per-domain client
  pattern (`attendance.ts`, `courses.ts`, ...) — no ad hoc fetches in components.

---

## 6. Security impact

- **No integration secret is ever exposed to the frontend or logged.** `LiveClassProviderProperties.webhookSecret`
  stays server-side only, sourced from an env var, empty-default fail-closed (mirrors
  `PaymentGatewayProperties`). The Fake adapter makes this concretely testable without a real
  secret.
- **Meeting join access is entitlement-checked server-side on every join request**, never
  inferred from session existence or a client-supplied role claim: Teacher via course-ownership
  (`CourseLookupApi`), Student via `EnrollmentAccessApi.resolveAccessState(...) == ACTIVE`
  **and** session `status == LIVE`. A Student whose enrollment has expired/been revoked (even if
  it was active when the session was scheduled) is re-checked live at join time, never from a
  stale cached grant.
- **Join URLs and recording playback URLs are short-lived, signed server-side, and minted fresh
  per request** — never a stable/reusable/predictable URL stored in `class_session`/
  `class_session_recording` (only opaque provider references are persisted). This resolves
  `19-zoom-live-classes.md`'s open decision ("does Zoom join-link protection follow the same
  rule as video?") — **yes**, flagged as a judgment call (Section 10).
- **Webhook tenant identity is never trusted from the payload** — resolved only via the
  platform's own `provider_reference` lookup, matching `.claude/rules/security.md`'s general
  webhook-trust posture (already established by `PaymentWebhookController`/
  `PaymentConfirmationService`) applied to this new webhook.
- **Webhook idempotency is schema-enforced** (`uq_class_session_provider_event`), not
  service-layer discipline alone — a duplicate Zoom delivery cannot double-create a recording
  row or double-publish an attendance-sync event.
- **Cross-tenant join/recording-fetch by meeting-ID/session-ID guessing is an explicit required
  negative test** (Section 8), per `.claude/rules/security.md`'s upload/protected-content
  enumeration requirement applied to live-class join/recording access.
- Every state-changing action on `ClassSession` (schedule, status transition, provider retry) is
  a normal tenant-scoped mutation; **no new audit-log requirement is introduced beyond what
  `.claude/rules/security.md` already lists explicitly** (its mandatory-audit list names
  price changes, payment approvals, device resets, access/expiry extensions, reactivation
  approvals, content deletions, settlement changes, impersonation — live-class scheduling is not
  on that list). This plan does **not** invent a new mandatory-audit obligation beyond that list;
  if the product owner wants schedule/cancel actions audited, that is a follow-up decision, not
  assumed here.

---

## 7. Tenant isolation impact

- `class_session`, `class_session_recording`, `class_session_provider_event` all carry
  `tenant_id NOT NULL` (the webhook-event table's `tenant_id` is the sole justified exception —
  nullable only because a malformed/unresolvable event genuinely has no tenant yet at insert
  time, matching the "resolved once looked up" pattern, never a bypass of tenant filtering on
  any *read* path) with tenant-leading indexes.
- Every new repository extends the shared tenant-aware base — no repository method accepts a
  caller-supplied `tenant_id`.
- Every composite FK (`course`, `course_lesson`, `tenant_user`) is `(tenant_id, id)`-scoped, per
  `.claude/rules/tenancy.md` — a `class_session` can never reference another tenant's course/
  lesson/teacher even if the bare id happens to exist there.
- Cross-tenant negative tests are mandatory for every new endpoint (Section 8): a Teacher/Student
  from Tenant A addressing Tenant B's `sessionId` (schedule, join, recording, status-transition,
  retry) must get 404 (Student) / 404-or-403 per the existing Teacher/staff convention — never
  200-with-filtered-data.
- Bulk/list endpoints (`GET /class-sessions`) are tenant-scoped through the same
  `TenantContext`-resolved filter as every list endpoint in this codebase — verified with a test
  proving a Tenant A caller's list never contains a Tenant B session even when both tenants have
  sessions scheduled in the same time window.

---

## 8. Test plan

**Backend** (JUnit + Spring Boot integration + Testcontainers, new
`ClassSessionServiceTest`/`ClassSessionManagementIntegrationTest`/`LiveClassProviderTest`/
`LiveClassWebhookControllerTest` suites):
- Unit: `ClassSession` status-transition guard (valid/invalid source states for
  start/complete/cancel), provider-provisioning retry logic (`PENDING`/`FAILED` → retryable,
  `PROVISIONED` → no-op), `FakeZoomLiveClassProviderAdapter` determinism.
- Integration: schedule → provider call happens outside the persisting transaction (verified via
  the same two-phase-commit pattern `OrderService`/`PaymentGatewayApi` already tests), provider
  failure leaves session `SCHEDULED`/`FAILED` (never silently dropped), retry-provisioning
  transitions `FAILED → PROVISIONED`, full status lifecycle, join (Teacher own-course success,
  Teacher other-course 403, Student active-enrollment + `LIVE` success, Student
  expired/no-enrollment → `NotFoundException`/404, Student valid-enrollment but session not yet
  `LIVE` → rejected), recording fetch (same entitlement shape, `COMPLETED`-only).
- **Cross-tenant negative tests are mandatory** for every new endpoint (schedule, list, detail,
  join, recording, status transitions, retry) — `@Tag("cross-tenant")`, matching the existing
  pattern.
- **Webhook tests**: signature-required (missing/invalid signature → rejected, no state
  change — verified with a repository-count assertion before/after), tenant-resolution-from-
  provider-reference (never from payload), **duplicate delivery → idempotent no-op** (second
  delivery of the same `provider_event_id` creates zero additional rows — the explicit
  "duplicate/retry behavior" test the wave brief requires), unknown/malformed event type →
  rejected gracefully, not a 500.
- **Secret protection test**: `LiveClassProviderProperties` with an empty webhook secret rejects
  every signature (fail-closed), mirroring `PaymentGatewayProperties`'s existing test.
- Provider-failure-handling test: a `LiveClassProviderApi` that throws mid-`createMeeting` leaves
  no orphaned/inconsistent DB state (session persisted `SCHEDULED`/`FAILED`, not left in a
  half-written state).

**Frontend** (Playwright, new specs):
- `live-class-schedule.spec.ts` (Teacher: create/edit/retry-provisioning, own-course-only
  enforcement), `live-class-status-transitions.spec.ts` (start/complete/cancel + status badges),
  `live-class-student-join.spec.ts` (enrolled+live → join succeeds; not-enrolled, expired,
  not-yet-live → join unavailable/blocked, each with correct empty/disabled state, not a raw
  error), `live-class-recording.spec.ts` (completed session recording affordance),
  `live-class-cross-tenant.spec.ts` (Tenant A session invisible/unreachable to a Tenant B
  session), `live-class-provider-failure.spec.ts` (Fake adapter forced-failure mode surfaces the
  Tenant-Admin-visible "Failed" status, not a silent gap).

---

## 9. Migration strategy / rollout risk

- `V48` is purely additive — three brand-new tables, zero edits to V1–V47, zero backfill (no
  pre-existing scheduled-class data anywhere in this schema). This is the **lowest-risk** shape
  identified for this migration in `docs/parity/migration-strategy.md` §2's risk table ("standard
  additive-migration risk only").
- `DomainArea.LIVE_CLASSES` is a code-only enum/matrix addition — no migration, no risk to
  existing role grants (the static-initializer guard in `PermissionCheckServiceImpl` already
  fails startup if a future edit accidentally grants `READ_ONLY_AUDITOR` a write action on the
  new area too, with zero additional code needed — the existing structural guard already covers
  every `DomainArea`, including this new one).
- Sequencing: migration + `LiveClassProviderApi`/adapter + `ClassSession` domain code all ship in
  one commit slice (mirrors Wave 3 §9's lesson) — a partially migrated schema with no service
  code is inert (no code path writes to the new tables yet), so this is lower risk than a
  schema-ahead-of-code gap that could be reached by another module.
- Explicitly **not** attempting Path B (repoint `attendance_record.session_id` to
  `class_session`) — confirmed out of scope for this wave per `migration-strategy.md`'s own
  recommendation; the recurring-session ambiguity stays an accepted, documented MVP limitation.

---

## 10. Explicit judgment calls (flagged for product-owner awareness, not silently decided)

1. **Join-link/recording-link short-lived-signed-URL treatment** — `19-zoom-live-classes.md`
   leaves this an open decision; this plan applies the same rule video already uses (never a
   stored/reusable URL, minted server-side per request). If a real Zoom integration later
   provides its own equivalently-protected native join flow, this can be revisited, but the safer
   default is adopted now rather than left unresolved.
2. **Join window**: Student join allowed only while `status == LIVE` (no pre-join grace period
   this wave) — simplest, most conservative interpretation; a grace window (e.g. join 5 minutes
   before `scheduledStart`) is a plausible product refinement, not implemented here to avoid
   guessing an unspecified number.
3. **`LIVE_CLASSES` permission grants** — Tenant Admin full, Course Coordinator `V/C/E/A` (no
   `DELETE`), all other staff roles absent — carries forward the parity matrix's own suggested
   mapping (PAR-19-02/19-03's "Course Coordinator V/C/E/A" note) rather than inventing a new
   split; flagged since no other row explicitly confirms this.
4. **Recording-attach and Zoom-link-sharing-enforcement-failure audit entries** —
   `open-decisions.md` leaves both explicitly unresolved for Domain 19; per Section 6, this wave
   does not add a new mandatory-audit obligation beyond `.claude/rules/security.md`'s existing
   explicit list. If the product owner wants these audited, that's a follow-up, not assumed here.
5. **No real Zoom account/credential connection UI** — the Fake adapter needs no tenant-supplied
   API key, so "Tenant Admin connects their own Zoom account" (per the spec's preconditions) has
   no real backing capability this wave; the Tenant Admin oversight screen is read/status-only.
   Real OAuth/credential-vault Zoom connection is deferred (Section 11) pending an actual Zoom
   developer account, which this environment does not have and must not fabricate
   (`root CLAUDE.md` Safety: never commit real secrets).

---

## 11. Deferred (explicitly out of scope, not silently skipped)

- **Zoom-synced attendance full consumption by `attendance-management`** (Wave 8) — this wave
  publishes `ClassSessionAttendanceSyncedEvent` from the webhook path; no listener exists yet in
  `attendance-management`. `PAR-10-03` stays partially open, matching the roadmap's own
  "Wave 4/8" split.
- **Real Zoom OAuth/credential-vault tenant connection** (blocked — no real Zoom developer
  account/credentials exist in this environment; cannot be built against a real vendor without
  fabricating secrets, which Safety rules forbid).
- **Path B** (repointing `attendance_record.session_id` to `class_session`, fixing the
  recurring-session ambiguity) — explicitly not attempted per `migration-strategy.md`'s own
  recommendation; remains a candidate for a future, separately-scoped decision if it proves
  necessary.
- **Multi-account Zoom, auto-recurring meetings, auto-import/convert recordings, reminder
  automation** (FR-LCM-4, Phase 3 per `functional-requirements.md` — explicitly out of this
  wave's scope).
- **Embedded/in-app Zoom meeting UI** (iframe/SDK embedding) — out of scope per
  `.claude/rules/architecture.md`'s "do not propose self-hosted conferencing/streaming
  infrastructure" guidance; join always opens the provider's own short-lived URL in a new tab.
