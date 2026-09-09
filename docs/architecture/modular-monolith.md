# Modular Monolith Architecture

Status: Living document

## 1. Why modular monolith

The platform is architected as a **single deployable Spring Boot application**
composed of clearly bounded domain modules, rather than as a set of independently
deployed microservices. This decision is confirmed in
`docs/requirements/source-requirements.md` (section 7, "Key architectural decisions
now confirmed": *"Modular monolith first, microservices later only if needed"*) and is
enforced as a standing architectural constraint in root `CLAUDE.md` and
`.claude/rules/architecture.md`. See `docs/adr/ADR-001-modular-monolith.md` for the
full decision record.

Rationale:

- At the current and near-term scale (multiple tenants, shared platform), the
  operational overhead of a distributed system (service discovery, distributed
  transactions, network-partition handling, per-service deployment pipelines) is not
  justified by a team/traffic profile that a single well-scaled application tier can
  serve.
- Module boundaries defined clearly *inside* one process give most of the design
  discipline benefit of service boundaries (single-responsibility ownership, explicit
  contracts) without the runtime cost of network calls between every domain
  interaction.
- If growth later genuinely requires extracting a module into its own service, having
  disciplined `api`-only cross-module contracts from day one makes that extraction a
  bounded, mechanical change rather than a rewrite (see section 6).

This is not a permanent prohibition on microservices — it is a default that must not
be overridden without an ADR (see section 5).

## 2. Confirmed backend domains

Source: `docs/requirements/source-requirements.md`, section 6, "Final module
architecture direction." Do not invent new top-level domains, or merge/split these,
without checking whether the change should be an ADR first
(`.claude/rules/architecture.md`, "When an ADR is required").

| Domain | Responsibility (one line) |
|---|---|
| `identity-access-service` | Authentication, session/token issuance and validation, device authentication, foundational identity for all other domains. |
| `tenant-management` | Tenant lifecycle (registration, approval, status, plan/feature limits, usage tracking), tenant profile/branding data ownership. |
| `user-management` | Student, Teacher, and Staff profile/account management and role assignment within a tenant. |
| `course-management` | Course/module/lesson/session structure, pricing, enrollment rules, visibility, teacher assignment, reviews toggle. |
| `content-management` | Learning materials (PDF/images/notes/attachments) organization, versioning, visibility, expiry — storage delegated externally. |
| `video-access-management` | Secure video playback: signed URL/token issuance, session/device/view-limit enforcement, watermarking coordination. |
| `live-class-management` | Zoom (or equivalent) integration for scheduling live classes, join URLs, recording management, attendance sync trigger. |
| `enrollment-management` | Enrollment lifecycle and activation — reads payment/ledger state, never order state, to decide access. |
| `payment-management` | Orders, payments, manual payment slip workflow, payment slip intelligence (duplicate/OCR checks), refunds. |
| `ledger-settlement-management` | Append-only ledger entries, tenant/tutor settlement calculation and status. |
| `attendance-management` | Class/session attendance capture (manual and Zoom-synced), attendance reporting. |
| `exam-management` | Exam creation, question bank, scheduling, marking, results, exam analytics. |
| `finance-expense-management` | Institute-side income/expense tracking, accounts, payouts, financial reports (distinct from the payment ledger). |
| `notification-management` | Email/SMS/WhatsApp/in-app notification dispatch, templates, delivery logs — consumer of events from other domains. |
| `integration-management` | Sole owner of third-party credentials/webhooks (Zoom, SMS, WhatsApp, email, payment gateway, object storage); see `integration-architecture.md`. |
| `reporting-analytics` | Cross-domain reporting/analytics, built from events/projections rather than live cross-domain joins. |
| `audit-log-management` | Immutable, append-only record of security/compliance-sensitive actions — consumer of events from other domains. |
| `support-management` | Support/helpdesk tickets (student/teacher/tenant), ticket assignment, internal notes, related-entity links. |

## 3. Package structure convention

Each domain is one top-level Java package: `com.lms.<domain>` (dashes to camelCase --
e.g. `com.lms.paymentmanagement`, `com.lms.ledgersettlementmanagement`). Inside each
domain package:

```
com.lms.<domain>
|-- api        # public service interfaces, request/response DTOs, domain events --
|              # the ONLY classes other domains may depend on
|-- web        # REST controllers for this domain only
|-- service    # interface implementations, orchestration, business rules
|-- domain     # JPA entities, value objects
|-- repository # Spring Data repositories -- never exported outside this package
`-- config     # domain-local Spring configuration
```

Placement rules:

- A new REST endpoint, entity, or repository belongs in exactly one domain package.
- If a feature seems to straddle two domains, it belongs to the domain that owns the
  primary aggregate/table; the rest is exposed through that domain's `api` interface
  to the other domain, not duplicated.
- `repository` classes are package-private or otherwise not referenced from other
  domain packages under any circumstance.

### `com.lms.common` -- the one deliberate exception

`com.lms.common` is a shared-kernel package, introduced by the Application Foundation
module, that sits alongside the 18 domain packages above. It holds no business logic,
no domain entities, and no REST endpoints -- only cross-cutting infrastructure every
domain depends on: the common API response envelope (`common.api`), global exception
handling (`common.web`), the shared application-exception hierarchy
(`common.error` -- `ApplicationException` and its subtypes, mapped to responses by
`common.web`'s handler), the base entity/audit-fields/immutable-ID strategy and the
`TenantAwareRepository<T,ID>` structural tenant-filtering mechanism from ADR-006
(`common.persistence`), the `TenantContext` abstraction (`common.tenant`), and
platform-wide Spring configuration for Postgres/Redis/Actuator/logging/security/OpenAPI
(`common.config`).

This is intentional, not an oversight of the "one top-level package per domain" rule:
ADR-006 already establishes that `TenantAwareRepository` is not owned by
`identity-access-service` or `tenant-management` -- both are expected to write
repositories *against* it, meaning the mechanism must sit below both, not nested inside
either. Every future domain module may depend on `com.lms.common`, the same as the
foundational domains, but `com.lms.common` itself must never depend on a business
domain.

## 4. Cross-module communication rules

- A module may depend only on another module's `api` package. It must never inject or
  call another domain's `repository` beans, import another domain's `domain` (entity)
  classes, or call another domain's `web` controllers internally.
- **Synchronous, in-process calls through injected `api` interfaces** are used for
  request-time reads/writes that must be consistent within the same
  transaction/response -- e.g. `enrollment-management` synchronously checking
  `payment-management`'s status via its `api` interface before activating access.
- **Domain events (published and consumed in-process)** are used for side effects that
  don't need to block the triggering request -- especially fan-out to
  `notification-management`, `audit-log-management`, and `reporting-analytics`. These
  three domains are event consumers, not domains other modules reach into directly by
  interface call for every side effect.
- No circular module dependencies. `identity-access-service` and `tenant-management`
  are foundational: any domain may depend on them, but they must never depend back on
  a business domain (course, payment, exam, etc.).
- `integration-management` owns all third-party credentials/webhooks. Other domains
  call it through its `api` interfaces rather than embedding provider SDKs or
  credentials directly -- this isolates provider swaps and credential rotation to one
  module (see `integration-architecture.md`).
- If a query seems to need a foreign domain's repository/entity, that is a signal to
  either (a) add a narrow read method to that domain's `api` interface, or (b) let
  `reporting-analytics` build its own read model from published events/projections
  rather than joining across domain schemas at request time. It is never a signal to
  reach into the other module's repository "just this once."

**Worked example (`content-management`, MVP-009):** `content-management` owns the
`material` table but needs to resolve a lesson's owning course/teacher/publish-state,
which lives in `course-management`'s own tables. Rather than importing
`course-management`'s `CourseLesson` entity or repository, `course-management.api`
gained one narrow, additive, read-only method — `CourseLookupApi.resolveLessonOwnership(UUID
lessonId)` (returning an `api`-package `LessonOwnership` record) — that `content-management`
calls in-process. At the Java/JPA level `Material.lessonId` stays a bare `UUID`, no
`@ManyToOne`/no cross-domain entity import; the only place the two tables are actually
coupled is a SQL-level composite foreign key (`material.lesson_id` → `course_lesson.id`,
tenant-scoped), which requires no Java import at all. `content-management` similarly
depends on `integration-management.api.ObjectStorageApi` for object storage — an interface
this module defines its dependency on even though `integration-management` doesn't exist
yet, so the real implementation can be swapped in later without any caller-side change (see
`docs/api/content-management.md`).

**Worked example (`attendance-management`, MVP-016):** `attendance-management` owns the new
`attendance_record` table (one row per tenant/session/student, `UNIQUE (tenant_id, session_id,
student_id)`) but needs two reads it doesn't own: "who is the owning teacher of this session's
course" and "which students are currently enrolled in this course." Both are resolved through
`api`-package calls, never a foreign repository/entity import:

- `CourseLookupApi.resolveLessonOwnership(UUID lessonId)` — the same method
  `content-management` already depends on (this section's first worked example) — reused
  unchanged to resolve a session's owning course/teacher for `AttendanceAccessGuard`'s
  Teacher-ownership-or-staff-matrix check, and to derive `attendance_record.course_id`
  server-side (never accepted from the client).
- `CourseLookupApi.getTeacherIdsByCourseId(Set<UUID> courseIds)` — a second, **new** additive
  method on `course-management.api`, added during this module's post-review hardening to batch-
  resolve a set of course ids' owning teachers (avoiding an N+1 when narrowing a Teacher's
  report to their own courses). See `docs/plans/MVP-016 Attendance.md`'s dated addendum for why
  this wasn't in the original plan and the review that authorized it after the fact.
- `EnrollmentAccessApi.listCurrentlyEnrolledStudentIds(UUID courseId)` — a **new** additive
  method on `enrollment-management.api` (the plan's own §9 item 2, disclosed and reviewed as
  part of this module's implementation) — the roster-bypass guard behind the mark endpoint.

At the Java/JPA level, `AttendanceRecord.courseId`/`sessionId`/`studentId`/`markedBy` stay bare
`UUID` fields — no `@ManyToOne`/no cross-domain entity import. The only place `attendance_record`
is actually coupled to `course`/`course_lesson`/`tenant_user` is a SQL-level composite foreign key
(e.g. `(tenant_id, session_id) -> course_lesson (tenant_id, id)`), which requires no Java import
at all. Every index on `attendance_record` leads with `tenant_id`, matching this module's three
real query shapes (student-own-history, per-course report, tenant-wide report) — see
`docs/api/attendance-management.md` for the full contract and
`backend/src/main/resources/db/migration/V25__create_attendance_management_schema.sql` for the
schema itself.

**Worked example (`exam-management`, MVP-017):** six new tables (`exam_question`,
`exam_question_option`, `exam`, `exam_question_link`, `exam_attempt`, `exam_answer`) — see
`docs/api/exam-management.md` for the full contract and
`backend/src/main/resources/db/migration/V26__create_exam_management_schema.sql`/
`V27__cap_exam_answer_manual_score_at_one_point.sql` for the schema itself. Two cross-module
reads, both through `api`-package calls only:

- `CourseLookupApi.getTeacherId(UUID courseId)` — course-ownership resolution behind
  `ExamAccessGuard`'s Teacher-ownership-or-staff-matrix check (mirrors every other domain's
  established discipline).
- `EnrollmentAccessApi.resolveAccessState(UUID studentId, UUID courseId)` — the
  enrollment-currency gate at attempt-start/save/submit time.
- `EnrollmentAccessApi.listCurrentlyEnrolledCourseIds(UUID studentId)` — a **new** additive
  reverse-lookup method on `enrollment-management.api` (mirroring the already-shipped
  `listCurrentlyEnrolledStudentIds(courseId)`'s "currently enrolled" currency semantics, reverse
  direction), added when implementation found `GET /exams/my/upcoming` needed a read
  `EnrollmentAccessApi` didn't expose in either direction the plan anticipated — stopped and
  reported, then approved as a minimal, additive, read-only extension (no existing caller
  affected).

**`exam.status` is lazily consistent, not authoritative if read directly.** `ExamLifecycleService`
advances `DRAFT → SCHEDULED → PUBLISHED → CLOSED` only when some request path resolves the
current status for that specific exam (attempt start/save/submit, or a list/detail read that
happens to include it) — an exam nobody reads again after its window closes can keep a stale
`SCHEDULED`/`PUBLISHED` value in the stored `status` column indefinitely. Every API response goes
through `ExamLifecycleService.resolveCurrentStatus`, so this is invisible to any client of the
`exam-management` API — but a future direct-SQL/reporting consumer of the raw `exam.status`
column (e.g. a `reporting-analytics` read model built by joining tables rather than consuming
this module's `api` reads/events) must not treat it as current without the same live-resolution
step.

The exam-lifecycle status advance (`DRAFT → SCHEDULED` manual, `SCHEDULED → PUBLISHED →
CLOSED` system-computed) is a lazy, one-idempotent-guarded-write pattern, deliberately reusing
`EnrollmentAccessApi.resolveAccessState`'s own "computed live, one idempotent guarded write"
precedent rather than inventing a scheduled job — see `ExamLifecycleService`/
`ExamStatusAdvanceWriter`. `exam.results_published_at` is a fully separate, independently-set
gate on top of that lifecycle, never conflated with the `PUBLISHED` status value (which governs
the exam's own visibility/attemptability, not its results' visibility). `exam_answer.exam_id` is
a documented, accepted denormalization from `attempt_id`'s real parent exam (mirroring
`attendance_record.course_id`'s V25 precedent) — always server-derived, never client-supplied,
with dedicated test coverage proving it.

**Worked example (`notification-management`, MVP-018) — the reference precedent for crossing
a real thread/timing boundary with `TenantContextHolder`:** three new tables
(`notification_outbox`, `notification_template`, `in_app_notification`) — see
`docs/api/notification-management.md` for the REST contract and
`backend/src/main/resources/db/migration/V28__create_notification_management_schema.sql`/
`V29__add_notification_outbox_claimed_at_reconciliation.sql` for the schema itself. This is
the first shipped domain in this codebase whose own production code must manually manage
`TenantContextHolder` across a boundary where Spring's usual request-scoped propagation
doesn't reach — every future domain needing the same thing should follow this pattern, not
invent a new one.

*Why a boundary-crossing problem exists at all.* Payment-management's own request-time
transaction (`PaymentConfirmationService`/`RefundService`) already runs with
`TenantContextHolder` set from the inbound request. But `notification-management` cannot
just reuse that ambient value, because it consumes work at two points that are **not** the
same request thread:

- `NotificationOutboxService`'s three listeners (`onPaymentConfirmed`/`onPaymentRejected`/
  `onPaymentRefunded`) are `@TransactionalEventListener(phase = AFTER_COMMIT)` — same thread
  as the triggering request, but *after* that request's own transaction has already
  committed, deliberately **not** `@Async` (an `@Async`/`ThreadPoolTaskExecutor` design was
  considered and rejected during review: a rejected-task-runs-on-calling-thread policy
  (`CallerRunsPolicy`) could make an unconditional `TenantContextHolder.clear()` in that
  task's `finally` wipe the *request* thread's own context mid-request — see
  `docs/plans/MVP-018 Email Notifications.md` §9.2 for the full rejected-design rationale).
- `NotificationDispatchPoller` runs on Spring's dedicated `@Scheduled` scheduler thread,
  entirely decoupled from any request thread, claiming `PENDING` rows across **all**
  tenants in one query (`FOR UPDATE SKIP LOCKED`) — there is no "current tenant" for that
  thread at all until a specific claimed row supplies one.

*The pattern.* Every one of this module's five independent call sites that touch a
tenant-owned repository — `NotificationOutboxService`'s three listeners,
`NotificationDispatchClaimService.claim()`, `NotificationDispatchFinalizeService.markSent()`/
`markFailed()`, `NotificationDispatchService.dispatchOne()`, and
`NotificationTemplateSeedingService.onTenantRegistered()` — follows the same shape:
`TenantContextHolder.set(...)` as the **first statement inside `try`**, sourced from that
unit of work's own persisted/event `tenantId` field (never an inherited or ambient value),
with `TenantContextHolder.clear()` **unconditionally in `finally`**, so a thrown exception
can never leave a stale tenant id set for whatever runs next on that thread. Each of these
five sites has its own deterministic, non-Spring, non-thread-pool unit test (see
`NotificationDispatchServiceTenantContextSymmetryTest` and its four siblings under
`backend/src/test/java/com/lms/notificationmanagement/service/`) proving `clear()` ran after
**both** a normal return and a thrown exception — this style was chosen over a
thread-pool/back-to-back-dispatch integration test because that style was found during
review to only prove `set()` ran, not `clear()` (a stale leftover value would be silently
overwritten, not detected).

*Dispatch is three separate short transactions, not one, to avoid holding a transaction (or
its row lock) open across the blocking SMTP call* (`.claude/rules/backend.md`'s "do not span
a transaction across an outbound call to an external system" rule) —
`NotificationDispatchClaimService.claim()` commits the `PENDING → SENDING` transition and
releases the claim lock *before* any SMTP call; the SMTP send itself runs with no open
transaction; `NotificationDispatchFinalizeService` commits the terminal `SENT`/`FAILED`
status (+ the `in_app_notification` insert on success) afterward in its own transaction. A
crash between claim-commit and finalize-commit leaves a row stuck at `SENDING`; V29's
`claimed_at` column plus `NotificationDispatchReconciliationService` (wired into the poller
as a separate, less-frequent `@Scheduled` method) finds any `SENDING` row older than a
timeout constant and marks it `FAILED` — failing a stuck claim forward, not a retry of the
send (this module's "a `FAILED` row is never automatically retried" decision is unaffected).
See `docs/requirements/open-decisions.md`'s notification-management entries for the full,
dated history of this design evolving from the plan's original two-state
(`PENDING → SENT|FAILED`) sketch to the shipped three-state design.

*Cross-module reads, all through `api`-package calls only, never a foreign repository/entity
import:*

- `UserProvisioningApi.findTenantUserSummaries` (`identity-access-service`) — resolves a
  claimed row's recipient email strictly by `(tenant_id, recipientUserId)`, never by email
  lookup (a same-email user in a different tenant must never be cross-matched — `user-management`'s
  email uniqueness is only `UNIQUE (tenant_id, email)`, not global).
- `MessagingProviderApi.sendEmail` (`integration-management`, **new** in this module) — the
  sole outbound email path; `notification-management` never holds SMTP credentials itself.
- `PaymentConfirmedEvent`/`PaymentRejectedEvent`/`PaymentRefundedEvent` (`payment-management`)
  and `TenantRegisteredEvent` (`tenant-management`, **new**, added post-ship so
  `NotificationTemplateSeedingService` can seed default `notification_template` rows at
  tenant-registration time — see `docs/requirements/open-decisions.md` for why this was
  needed: without it, no tenant could ever receive real email at MVP launch) — all consumed
  via `@TransactionalEventListener`, never a synchronous call into either owning domain.

At the Java/JPA level, `NotificationOutbox.recipientUserId`/`InAppNotification.recipientUserId`
stay bare `UUID` fields — no cross-domain entity import. Every index on all three tables leads
with `tenant_id` except the two deliberately cross-tenant, explicitly-named dispatch/
reconciliation claim queries (`findPendingIdsAcrossTenants`,
`claimPendingByIdAcrossTenantsForUpdateSkipLocked`, `findStuckSendingIdsAcrossTenants`,
`claimStuckSendingByIdAcrossTenantsForUpdateSkipLocked` — `NotificationOutboxRepository`),
which are platform-level background operations by design, not tenant-scoped reads, and are
named accordingly per `.claude/rules/backend.md`'s bypass-naming convention.

## 5. When an ADR is required

Raise an ADR **before**, not after, doing any of the following (in addition to the
change-controlled items in root `CLAUDE.md`):

- Extracting any confirmed domain into a separately deployable service/process.
- Giving a module its own datastore, cache, or connection pool distinct from the
  shared platform Postgres/Redis.
- Introducing a new cross-domain communication mechanism (message broker, gRPC, a
  shared library that bypasses `api` interfaces, etc.).
- Making any business domain a dependency of `identity-access-service` or
  `tenant-management` (inverting the foundational-module rule).
- Allowing a module to read/write another module's repository or entities directly as
  a "just this once" stopgap -- this is a boundary violation, not a valid shortcut, and
  needs either a proper `api` extension or an ADR explaining the exception.

## 6. Future extraction path (not proposed now)

Because cross-module coupling is already restricted to `api`-interface calls and
domain events, a future decision to extract a specific domain (e.g.
`video-access-management`, or `payment-management` + `ledger-settlement-management`
together) into its own deployable service would, in principle, involve:

- Replacing in-process `api` interface calls to that domain with a network call
  (REST/gRPC) behind the same interface contract.
- Replacing in-process event publication/consumption with the chosen message broker.
- Giving the extracted domain its own datastore/schema ownership (with a data
  migration plan).

This document does not propose or schedule any such extraction -- it exists only to
note that the current package/communication discipline is what would make an eventual
extraction tractable rather than a rewrite. Any actual extraction remains subject to
the ADR requirement in section 5 and to root `CLAUDE.md`'s prohibition on
microservices absent an approved ADR.

## Related

- `docs/adr/ADR-001-modular-monolith.md`
- `docs/architecture/solution-architecture.md`
- `docs/requirements/module-catalog.md`
