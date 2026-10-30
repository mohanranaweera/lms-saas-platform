# Exam Management v2 API (Klass Exam Module)

Authoritative request/response shapes shared by backend and frontend. Envelope, error format and
pagination follow the existing `ApiResponse` / `PageResponse` conventions. Tenant is always taken
from the authenticated principal. Spec: `docs/specs/klass-exam-module.md`.

## Rich content format (questions, options, explanations)

Stored and exchanged as sanitized HTML. Allowed: `p, br, strong, b, em, i, u, s, code, pre, h1, h2,
ul, ol, li, blockquote`, `img[data-media-id]`, `span[data-math=inline][data-latex]`,
`div[data-math=block][data-latex]`. All other tags/attributes are stripped server-side (jsoup);
`style`, `on*`, `href`, `javascript:` never survive. Images are stored **without** `src` — only
`data-media-id` (a `media_file` of usage `EXAM_QUESTION` in the caller's tenant; anything else → 400).
On every authorized read the server adds `src` = short-lived signed URL (30 min). Math is rendered
client-side with KaTeX from `data-latex`.

"Non-empty content" = after sanitizing, has visible text, at least one image, or at least one math node.

## Slice A — question bank

`QuestionResponse`
```json
{ "id": "uuid", "courseId": "uuid", "moduleId": "uuid", "sequence": 1,
  "bodyHtml": "<p>…</p>", "marks": 1.0, "explanationHtml": null,
  "options": [ { "id": "uuid", "sequence": 1, "html": "<p>…</p>", "correct": false } ],
  "importId": null, "createdAt": "…", "updatedAt": "…" }
```
`QuestionUpsertRequest`
```json
{ "bodyHtml": "…", "marks": 1, "explanationHtml": "… or null",
  "options": [ { "id": "uuid|null", "html": "…", "correct": true } ] }
```
Rules: 2–10 options, **exactly one** `correct` (D1); `0 < marks ≤ 100`, max 2 decimals; option html
non-empty; explanation optional (empty → null). Option order = array order. On update, options
with an `id` keep their id; omitted ones are removed.

| Method | Path | Notes |
|---|---|---|
| GET | `/api/v1/courses/{courseId}/modules/{moduleId}/questions?page=0&size=50` | `PageResponse<QuestionResponse>`, ordered by `sequence`, excludes deleted |
| GET | `/api/v1/exam-questions/{questionId}` | single |
| POST | `/api/v1/courses/{courseId}/modules/{moduleId}/questions` | 201 |
| PUT | `/api/v1/exam-questions/{questionId}` | 200 |
| DELETE | `/api/v1/exam-questions/{questionId}` | 204, soft delete; frozen exam papers unaffected |
| POST | `/api/v1/courses/{courseId}/modules/{moduleId}/questions/copy` | body `{ "targetCourseId", "targetModuleId" }` → `{ "copied": n }`; copies **all** non-deleted questions as independent rows appended to the target; target ≠ source; caller needs authoring access to both courses |
| GET | `/api/v1/courses/{courseId}/modules/question-counts` | `[{ "moduleId", "moduleName", "questionCount", "totalMarks" }]` for every module of the course |
| POST | `/api/v1/exam-media` | multipart `file` (PNG/JPEG/GIF/WebP, ≤ 5 MB, sniffed) → `{ "mediaId", "url" }` (`url` signed, for immediate preview) |

Imports

| Method | Path | Notes |
|---|---|---|
| GET | `/api/v1/question-imports/template.csv` | UTF-8 with BOM; header `question,correct answer,answer 2,answer 3,answer 4` |
| POST | `/api/v1/courses/{courseId}/modules/{moduleId}/question-imports` | multipart `file`; header `Idempotency-Key` (required, ≤ 100 chars) |
| GET | `/api/v1/courses/{courseId}/modules/{moduleId}/question-imports?search=&page=0&size=10` | `PageResponse<QuestionImportResponse>` newest first |

CSV rules: `.csv`, ≤ 1 MB, ≤ 500 data rows, UTF-8 (BOM optional). Header matched case-insensitively
after trimming. Required columns: `question`, `correct answer`, `answer 2`. Optional: `answer 3`…
`answer 10`, `marks` (default 1), `explanation`. Blank rows skipped. Cells are plain text (HTML-escaped
into `<p>`). **Atomic**: any invalid row → 422 `{ "errors": [ { "row": 3, "column": "answer 2", "message": "…" } ] }`
and nothing is imported. Same `Idempotency-Key` for the same module → returns the original import
(200, no duplicates). Correct answer is stored as option 1; display order is shuffled for students only
when the exam enables it.

`QuestionImportResponse`: `{ "id", "fileName", "importedAt", "questionCount", "importedByName" }`.
POST success → 201 `QuestionImportResponse`.

Authorization (all above): `ExamAccessGuard.requireAuthoringAccess(courseId, …)` — admin/staff by
`DomainArea.EXAMS` (VIEW for GET, CREATE_EDIT for writes, DELETE for delete), teacher only for own
course, teacher assistant allowed. Module must belong to the course in the tenant, else 404.
Students → 403.

## Slice B — exam configuration, directory, paper

Times are ISO-8601 instants (UTC). The frontend combines date + time inputs in the browser's local
timezone (`src/lib/datetime.ts` convention). Status is derived server-side from the injected `Clock`:
`UPCOMING` (now < startAt), `IN_PROGRESS` (startAt ≤ now < endAt), `FINISHED` (now ≥ endAt).

`ExamUpsertRequest`
```json
{ "type": "MCQ|WRITTEN|PHYSICAL", "name": "…", "courseId": "uuid",
  "password": "string|null", "durationMinutes": 30, "startAt": "…Z", "endAt": "…Z",
  "questionCount": 5, "cutoffMarks": 3, "moduleQuotas": [ { "moduleId": "uuid", "questionCount": 5 } ],
  "shuffleQuestions": false, "shuffleAnswers": false,
  "enrollAllStudents": false, "autoEnrollOnPayment": false,
  "paymentCriteria": { "kind": "MONTHS|MODULES|DATES|INSTALLMENT|FULL_FEE",
                       "months": ["2026-10"], "moduleIds": [], "dates": ["2026-10-31"], "minimumAmount": 0 },
  "showAnswersWithResults": false,
  "totalMarks": 100, "questionPaperMediaId": "uuid", "markingSchemeMediaId": "uuid|null" }
```
Field rules by type (server-enforced; 400 with field errors):
- all: `name` 1–255, `courseId` in tenant & caller has authoring access, `endAt > startAt`.
- MCQ: `password` required on create (4–64 chars; on update `null` = keep), `durationMinutes` 1–1440,
  `questionCount` ≥ 1, `moduleQuotas` non-empty, modules belong to the course, no duplicates, each quota
  integer ≥ 0, Σ quotas = `questionCount`, quota ≤ available questions in that module,
  `0 ≤ cutoffMarks ≤ drawn total marks` ("The chosen questions are worth N marks; the cut-off can't be
  higher"). Written-only fields must be null.
- WRITTEN: `password`, `durationMinutes` as MCQ; `totalMarks` 1–10000; `questionPaperMediaId` required;
  `markingSchemeMediaId` optional; `cutoffMarks` optional (0..totalMarks); MCQ-only fields null.
- PHYSICAL: `durationMinutes` optional (1–1440); no password, quotas, paper, shuffle, enrollment toggles
  except `enrollAllStudents` (default true for physical, so attendance can be recorded).
- `enrollAllStudents` and `autoEnrollOnPayment` are mutually exclusive. `paymentCriteria` required when
  `autoEnrollOnPayment`; allowed `kind` derives from course pricing model: MONTHLY→MONTHS,
  MODULE→MODULES, SESSION/DAILY→DATES, INSTALLMENT→INSTALLMENT, ONE_TIME→FULL_FEE; FREE/CUSTOM → toggle
  unsupported (400).

Paper (MCQ, D3): on create the server draws `quota` random non-deleted questions per module and freezes
them as `exam_paper_item`/`exam_paper_option` snapshots (body, marks, explanation, options + correct).
`totalMarks` = Σ snapshot marks. Changing `questionCount`/`moduleQuotas` before the first attempt
redraws; `POST /api/v1/exams/{id}/paper/redraw` redraws explicitly. After the first attempt, structural
fields (`type, courseId, questionCount, moduleQuotas, totalMarks, durationMinutes, startAt, password,
shuffle*`, paper) are locked → 409 `EXAM_LOCKED`; `name`, `endAt` (extend only), `cutoffMarks`,
`showAnswersWithResults`, enrollment toggles remain editable.

`question-counts` (Slice A endpoint) gains `minMarks`, `maxMarks` per module so the form can show
"The chosen questions are worth N marks" (exact when min = max in every selected module, otherwise
"between X and Y marks").

| Method | Path | Notes |
|---|---|---|
| POST | `/api/v1/exams` | 201 `ExamDetailResponse` |
| GET | `/api/v1/exams/{id}` | `ExamDetailResponse` (edit form + header) |
| PUT | `/api/v1/exams/{id}` | 200; 409 `EXAM_LOCKED` for locked fields |
| DELETE | `/api/v1/exams/{id}` | 204 soft delete; 409 if any attempt/result/submission exists |
| POST | `/api/v1/exams/{id}/paper/redraw` | 200 `ExamDetailResponse`; 409 if locked |
| POST | `/api/v1/exams/{id}/password/reveal` | `{ "password" }`; audit-logged; staff CREATE_EDIT or owning teacher |
| GET | `/api/v1/exams/directory?search=` | `{ "serverNow", "inProgress": [ExamCard], "upcoming": [...], "previous": [...] }` |
| POST | `/api/v1/exam-documents?purpose=QUESTION_PAPER\|MARKING_SCHEME` | multipart `file` (PDF, PNG, JPEG; ≤ 20 MB; sniffed) → `{ "mediaId", "fileName" }` |
| GET | `/api/v1/exams/{id}/documents/{purpose}` | `{ "url", "fileName" }` signed 10 min; staff only in this slice |

`ExamDetailResponse`: all upsert fields except `password` + `{ id, status, hasPassword, totalMarks,
paperQuestionCount, courseName, moduleQuotas[{moduleId, moduleName, questionCount}],
questionPaper{fileName}|null, markingScheme{fileName}|null, locked, createdAt, updatedAt }`.

`ExamCard`: `{ id, type, status, name, courseId, courseName, startAt, endAt, durationMinutes,
questionCount|null, totalMarks|null, enrolled, attended, completed, submitted }` — `completed` (MCQ:
finalized with ≥ 1 answer), `submitted` (WRITTEN: uploaded answer sheet); counts come from Slice C/D
tables and are 0 until those exist. Search: case-insensitive on exam name and course name within the
caller's authorized exams (teacher: own courses). Ordering: in-progress by endAt asc, upcoming by
startAt asc, previous by endAt desc. Password, paper content and correct answers never appear.

Exam password storage (D6): AES-GCM via the existing `integrationmanagement/gateway/AesGcm` helper with
a dedicated key property `exam.password-encryption-key` (env `EXAM_PASSWORD_ENCRYPTION_KEY`; dev/test
profile defaults only). Never logged, exported or returned except by `reveal`.

## Slice C — exam enrollment, MCQ participation, grading

Rules (D2, D4, ADR-032): only exam-enrolled students may start; one attempt per student; late start
allowed until `endAt`; `deadlineAt = min(startedAt + durationMinutes, endAt)`; the server finalizes at the
deadline (lazily on any read/write of the attempt + a scheduled sweep every minute, idempotent); unanswered
= 0; no negative/partial marks; grading by stable option id against the frozen paper; score computed once
at finalization (`graded_at`), never on retries. Result visibility: score after `endAt`; correct answers and
explanations only if `showAnswersWithResults` and after `endAt`.

Enrollment

| Method | Path | Notes |
|---|---|---|
| POST | `/api/v1/exams/{id}/enrollments` | `{ "studentIds": [] }` — each must be an active participant of the exam's course in the tenant → `{ "added", "alreadyEnrolled" }` |
| DELETE | `/api/v1/exams/{id}/enrollments/{studentId}` | 204; 409 `HAS_ATTEMPT` if an attempt/result/submission exists |
| GET | `/api/v1/exams/{id}/enrollment-candidates?search=` | course participants not yet enrolled (index number, first/last name) |
| POST | `/api/v1/exams/{id}/enrollments/sync-payments` | runs the ADR-032 matcher over already-confirmed payments → `{ "added" }` |

Enroll-all (D4) materializes `exam_enrollment(source=ENROLL_ALL)` rows for current course participants on
save and for later course participants on their first exam listing/access before `endAt` (and when listing
management). Payment listener per ADR-032 (AFTER_COMMIT on `PaymentConfirmedEvent`, refund handling on
`PaymentRefundedEvent`), idempotent via unique `(tenant_id, exam_id, student_id)`.

Student

`StudentExamResponse`: `{ id, type, name, courseName, startAt, endAt, durationMinutes, questionCount,
totalMarks, status, requiresPassword, attempt: { id, status, deadlineAt }|null, resultAvailable }`

| Method | Path | Notes |
|---|---|---|
| GET | `/api/v1/student/exams` | enrolled exams `{ serverNow, upcoming, inProgress, previous }` |
| GET | `/api/v1/student/exams/{id}` | 404 if not enrolled |
| POST | `/api/v1/student/exams/{id}/attempts` | `{ "password" }` → `AttemptResponse`; resumes the existing attempt if IN_PROGRESS (password still required); 403 `WRONG_PASSWORD` (rate-limited 5/min/student/exam), 409 `NOT_OPEN`/`CLOSED`/`ALREADY_ATTEMPTED` |
| GET | `/api/v1/student/attempts/{attemptId}` | `AttemptResponse` (resume after refresh) |
| PUT | `/api/v1/student/attempts/{attemptId}/items/{itemId}` | `{ "optionId": "uuid|null", "revision": 7 }` → `{ "saved": true, "stale": false, "revision": 7 }`; `revision` ≤ stored → `stale: true`, no change; 409 `FINALIZED`/`DEADLINE_PASSED`; optionId must belong to the item |
| POST | `/api/v1/student/attempts/{attemptId}/submit` | idempotent → `AttemptResponse` with status SUBMITTED |
| GET | `/api/v1/student/attempts/{attemptId}/result` | `ResultResponse` per release policy, 409 `NOT_RELEASED` before |

`AttemptResponse`: `{ id, examId, status: IN_PROGRESS|SUBMITTED|EXPIRED, startedAt, deadlineAt, serverNow,
items: [ { id, sequence, bodyHtml, marks, options: [ { id, html } ], selectedOptionId, revision } ] }` — items
and options in the persisted per-attempt order (shuffled once at start when enabled). Never contains
`correct`, explanations or the password.

`ResultResponse`: `{ examId, attemptId, score, totalMarks, cutoffMarks, passed, answeredCount, items?: [ {
sequence, bodyHtml, marks, awardedMarks, selectedOptionId, correctOptionId, explanationHtml, options[] } ] }`
(`items` only when answers are released).

Staff view of attempts is part of Slice D/E management.
