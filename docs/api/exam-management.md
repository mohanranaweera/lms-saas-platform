# exam-management — API Contract

Covers MVP-017 "Exams" (`com.lms.exammanagement`) — the question bank, exam scheduling,
student exam-taking/auto-marking, the structured-answer marking queue, and results
publishing/review. Written after the fact once the module was reviewed, following the same
"process gap" this project's other API contract files record: `docs/plans/MVP-017 Exams.md`
§10 sketched a draft contract before implementation, but the finalized doc was never produced
until a post-ship review found the gap. This file reflects the actual shipped backend
(`QuestionBankController`/`ExamController`/`ExamAttemptController`/`MarkingQueueController`/
`ResultsController`), including several endpoints added during the post-ship review to close a
real gap (a course-scoped and tenant-wide exam list, and a student's own attempt history) —
each flagged below where it deviates from the original plan.

## Response envelope

Every endpoint returns `com.lms.common.api.ApiResponse<T>` — see
`docs/api/identity-access-service.md`'s "Response envelope" section for the exact shape
(`success`/`data`/`error`/`timestamp`/`traceId`). Paginated reads wrap
`com.lms.common.api.PageResponse<T>` inside that envelope. Pagination follows the platform
convention: `page`/`size`/`sort` query params, `size` server-clamped to 100.

## Auth requirements

- Every endpoint requires a valid `Authorization: Bearer <accessToken>` header.
  `@PreAuthorize` at the controller (`isAuthenticated()` on most endpoints, `hasRole('STUDENT')`
  on the exam-taking/attempt-history endpoints) is a coarse gate only — the real authorization
  check happens in the service layer via `ExamAccessGuard`/`PermissionCheckService` or, for
  marking-queue endpoints, `MarkingQueueService`'s own Teacher-ownership-or-staff-matrix check.
- `tenantId`/`courseId`/`createdBy`/`studentId`/`markedBy`/`markedAt` are never accepted from
  the client on any request body — always server-derived from `TenantContext`/
  `AuthenticatedPrincipalHolder`.
- No response DTO a Student or pre-submission reader can reach ever includes `isCorrect` on an
  MCQ option (`ExamQuestionOptionResponse` has no such field at all — not merely omitted when
  null).

## Authorization model

`ExamAccessGuard` has **two** distinct methods, deliberately never collapsed into one:

- `requireAuthoringAccess(courseId, action)` — gates question CRUD, `DRAFT`-exam editing, and
  every list/read endpoint (with `PermissionAction.VIEW`). Owning Teacher, tenant-wide Teacher
  Assistant (capped at `DRAFT` by `ExamSchedulingService`, never by this guard), or staff holding
  `DomainArea.EXAMS`/the given action.
- `requireLifecycleTransitionAccess(courseId)` — gates the single `DRAFT → SCHEDULED`
  transition and results-publishing (both `APPROVE`-class). Teacher Assistant is denied here
  **unconditionally**, before the course is even resolved.
- `requireStaffTenantWideViewAccess()` — gates the tenant-wide exam list only. Staff-only
  (`DomainArea.EXAMS`/`VIEW`); Teacher/Teacher Assistant hold no tenant-wide grant for this
  domain area at all, so they are denied by the underlying permission matrix, not a role branch.

`MarkingQueueService` deliberately does **not** reuse `ExamAccessGuard` — Teacher Assistant's
marking-queue access is an explicitly unresolved question the plan (§21 item 2) never answers,
so this service applies the more conservative Teacher-ownership-or-staff-matrix shape with no
special case for Teacher Assistant (falls through to the `DomainArea.EXAMS` check and is denied).

A cross-tenant or genuinely nonexistent `courseId`/`examId`/`questionId`/`attemptId`/`answerId`
is always `404`. A same-tenant Teacher who doesn't own the resource's course gets `403`. Student
IDOR on an attempt/result is always `404`, never `403` (an anti-enumeration convention — see
"Security notes" below).

## Endpoints

### Question bank

- `GET /api/v1/exams/courses/{courseId}/questions` — paginated list. Auth: `requireAuthoringAccess(courseId, VIEW)`.
- `POST /api/v1/exams/courses/{courseId}/questions` — create. Auth: `requireAuthoringAccess(courseId, CREATE_EDIT)`. `400 VALIDATION_ERROR` if an MCQ has zero `isCorrect` options (was previously documented as `409` here — corrected to match `InvalidExamScheduleException`'s actual `400` mapping). `body`/`optionText` are capped at 20,000/500 characters respectively (`400` if exceeded).
- `PUT /api/v1/exams/questions/{questionId}` — update body (and, for MCQ, options). Same auth. `409 QUESTION_IN_USE` if the question is linked to a non-`DRAFT` exam or already answered (post-review grading-integrity fix — see §22 addendum item 4 in the plan). Same length caps as create.
- `DELETE /api/v1/exams/questions/{questionId}` — `409` if referenced by any `exam_question_link`/`exam_answer`.

### Exam scheduling and browsing

- `POST /api/v1/exams/courses/{courseId}/exams` — create a `DRAFT` exam (`{ title }`). Auth: `requireAuthoringAccess(courseId, CREATE_EDIT)`.
- `PUT /api/v1/exams/{examId}` — edit while `DRAFT` only (`409` otherwise). Same auth.
- `POST /api/v1/exams/{examId}/schedule` — the single `DRAFT → SCHEDULED` transition. Auth: `requireLifecycleTransitionAccess`. `400` if zero linked questions or an invalid window. Writes an `exam.scheduled` audit log entry (target `exam`) on success.
- `GET /api/v1/exams/{examId}` — full exam incl. questions/options. Owning Teacher/TA/staff-VIEW, or an enrolled Student once the resolved live status is `PUBLISHED`/`CLOSED`.
- `GET /api/v1/exams/my/upcoming` — Student's own exam list, per-course enrollment intersected with `status IN (SCHEDULED, PUBLISHED)`. `hasRole('STUDENT')`, owner-only.
- **`GET /api/v1/exams/courses/{courseId}/exams`** *(added post-review)* — every status for one course, not just upcoming ones. Auth: `requireAuthoringAccess(courseId, VIEW)`. Backs the Teacher's Marking Queue/Publish exam pickers.
- **`GET /api/v1/exams`** *(added post-review)* — tenant-wide, staff-only (`requireStaffTenantWideViewAccess`), optional `status` filter. Backs the Tenant Admin Exam Oversight screen.

### Exam taking (Student)

- `POST /api/v1/exams/{examId}/attempts` — start or resume the caller's single `IN_PROGRESS` attempt. Re-verifies enrollment currency + live status + window on every call. `403 NOT_YET_OPEN`/`WINDOW_CLOSED`; `409` on a concurrent-start race (the partial unique index).
- `PUT /api/v1/exams/attempts/{attemptId}/answers` — save/update one answer. `409` if already `SUBMITTED`. `response` is capped at 20,000 characters (`400` if exceeded).
- `POST /api/v1/exams/attempts/{attemptId}/submit` — idempotent submit; `409` on a second submit (never re-scored). MCQ auto-marking runs exactly once, here.
- **`GET /api/v1/exams/attempts/my`** *(added post-review)* — the calling student's own attempt history, most recent first, including attempts for `CLOSED` exams (which `/my/upcoming` never returns). `hasRole('STUDENT')`, owner-only. Replaces the frontend's earlier `localStorage`-based "remembered attempt" convenience as the primary way to rediscover a past attempt.
- **`GET /api/v1/exams/attempts/{attemptId}/answers`** *(added post-review, second pass)* — the calling student's own saved answers for one attempt (`[{ questionId, response }]`), owner-only (`404` for another student's attempt, same convention as every other attempt endpoint). `hasRole('STUDENT')`. Closes a real bug: without this read, resuming an `IN_PROGRESS` attempt after a refresh/reconnect rendered every question blank in the frontend client, risking a blank re-save overwriting a previously-saved answer with `null`. The frontend take page fetches this once an attempt is available and seeds its local answer state from it.

### Marking queue

- `GET /api/v1/exams/{examId}/marking-queue` — paginated, unmarked `STRUCTURED` answers only, scoped `(tenant_id, exam_id)` and further to the marker's authorized course set.
- `POST /api/v1/exams/answers/{answerId}/mark` — submit `manualScore`. `409` if the answer's question is not `STRUCTURED`. `409` if the answer has already been marked (no silent re-mark/overwrite — a marker must not be able to change a recorded score with no trace; there is no re-mark/override endpoint at this scope). `manualScore` is capped at `1.00` (every question is worth a fixed one point, `ResultsPublishingService.POINTS_PER_QUESTION`) — enforced by `MarkAnswerRequest`'s bean validation (`400`) and a DB `CHECK` backstop (`ck_exam_answer_manual_score_at_most_one_point`, V27). A successful mark writes an `exam_answer.marked` entry via `AuditLogApi` (actor, target `exam_answer`, target id = the answer id), synchronously in the same transaction.

### Results publishing / review

- `POST /api/v1/exams/{examId}/publish-results` — sets `results_published_at`. Auth: `requireLifecycleTransitionAccess`. `409` if `status != CLOSED`. Idempotent (a second call is a no-op success — the audit entry and `ExamResultPublishedEvent` are only written/raised on the actual first-publish transition, never re-fired on a repeat call). Writes an `exam.results_published` audit log entry (target `exam`) alongside the existing `ExamResultPublishedEvent`.
- `GET /api/v1/exams/attempts/{attemptId}/results` — student's own result + review. `hasRole('STUDENT')`, owner-only (`404` for another student's attempt). `{ published: false, result: null }` whenever `results_published_at IS NULL`, even for a fully `SUBMITTED` attempt.

## Security notes

- **Time-window bypass** is re-verified from an injected `Clock` on every attempt-related
  request (start, answer-save, submit) — never cached or trusted from client-held elapsed time.
- **Score-trust**: no request DTO anywhere carries a bindable `isCorrect`/`score`/`autoScore`
  field. Auto-marking is computed exclusively server-side from `exam_question_option.is_correct`.
- **Enumeration**: student-facing `GET /attempts/{id}` and `GET /attempts/{id}/results` return
  `404`, never `403`, for another same-tenant student's resource — a `403` would confirm the id
  belongs to a real student.
- **No file upload, no protected media** surface exists in this module at MVP scope.

## Change log

- V26 (initial ship): question bank, exam scheduling, exam-taking, marking queue, results
  publishing.
- V27 (post-review): DB-level cap on `exam_answer.manual_score` (see "Marking queue" above).
- Post-review, no schema change: `GET /exams/courses/{courseId}/exams`, `GET /exams`, and
  `GET /exams/attempts/my` were added to close a gap the original plan's §9 "no new cross-module
  API surface is needed" claim did not anticipate — these are all *within-module* additive reads,
  not cross-module `api` changes, and required no new migration.
- Post-review, second pass (multi-agent findings fix): `GET /exams/attempts/{attemptId}/answers`
  added (answer-rehydration fix, above); `POST /exams/answers/{answerId}/mark` now rejects a
  re-mark of an already-marked answer with `409` instead of silently overwriting it; `exam.scheduled`/
  `exam.results_published`/`exam_answer.marked` audit log entries added via `AuditLogApi`; length
  caps added to `body`/`optionText`/`response`; `409` corrected to `400` for the zero-`isCorrect`-
  options validation error (question bank); an index was added to `exam_answer(tenant_id, question_id)`
  in `V26` (still uncommitted/unshared at the time, so amended directly rather than via a new migration).
