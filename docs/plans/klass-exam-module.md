# Klass Exam Module — Implementation Plan

Spec & decisions: `docs/specs/klass-exam-module.md` (D1–D9 approved 2026-10-30).
Extends the MVP-017 `com.lms.exammanagement` module; no parallel model.

## Planning report (CLAUDE.md)

- **Goal**: Klass-parity exam module — module question bank (rich text, math, images, copy, CSV import), MCQ / Written / Physical exams, exam enrollment (manual, enroll-all, payment), student participation with frozen papers, marking, results, stats, leaderboards, CSV export, Notify Marks.
- **Database impact**: additive migrations V109–V114 only (below; V108 was already taken by a Java migration). No edits to V26/V27. Existing exam rows keep working (`exam_type` defaults `MCQ`).
- **API impact**: new endpoints per spec §6; MVP-017 endpoints retained during transition, then retired in a follow-up (change to approved contract → D9).
- **Security impact**: HTML sanitation (jsoup), protected media for question images / papers / answer sheets / marked papers, encrypted exam password, answer-key gating, audit log for manual marks, password reveal, overrides.
- **Tenant impact**: every new table tenant-owned, `tenant_id NOT NULL` FK, composite same-tenant FKs, `tenant_id`-leading indexes, cross-tenant negative tests per endpoint.
- **Payment impact**: read-only consumer of `PaymentConfirmedEvent` / `PaymentRefundedEvent` (D5). No ledger writes, no change to course enrollment or lesson entitlements.
- **Tests**: JUnit + Testcontainers integration per slice (incl. cross-tenant / cross-teacher / cross-student), deterministic `Clock` (`ExamClockConfig`), Playwright E2E with mocks + screenshots at 1440 / 820 / 390 px.

## Schema (new migrations; owner: database-architect review, backend agent writes)

- **V109 question bank** (shipped): `exam_question` + `module_id UUID NULL` (composite FK to `course_module(tenant_id,id)`; NULL = legacy course-level), `marks NUMERIC(6,2) NOT NULL DEFAULT 1 CHECK > 0`, `explanation TEXT`, `sequence INT`, `deleted_at TIMESTAMPTZ` (soft delete so frozen papers & history survive). `exam_question_option` + `sequence INT`. Partial unique index for D1 (one correct per question) enforced in service + deferred check. Index `(tenant_id, module_id, sequence)`.
- **V110 imports** (shipped): `question_import(id, tenant_id, module_id, file_name, file_sha256, idempotency_key, question_count, imported_by, imported_at)`; unique `(tenant_id, module_id, idempotency_key)`.
- **V111 exam config**: `exam` + `exam_type`, `password_ciphertext`, `question_count`, `cutoff_marks`, `total_marks`, `shuffle_questions`, `shuffle_answers`, `enroll_all_students`, `show_answers_with_results`, `auto_enroll_on_payment`, `payment_criteria JSONB`, `question_paper_media_id`, `marking_scheme_media_id`, `deleted_at`. `exam_module_quota(tenant_id, exam_id, module_id, question_count ≥ 0)` unique `(tenant_id, exam_id, module_id)`.
- **V112 paper snapshot**: `exam_paper_item(tenant_id, exam_id, sequence, source_question_id, body_html, marks, explanation_html)`; `exam_paper_option(tenant_id, paper_item_id, sequence, html, correct)`. Immutable once an attempt exists.
- **V113 enrollment & attempts**: `exam_enrollment(tenant_id, exam_id, student_id, source, payment_id, created_at)` unique `(tenant_id, exam_id, student_id)`; `exam_attempt` + `deadline_at`, `score`, `graded_at`, `answered_count`; one attempt per student unique `(tenant_id, exam_id, student_id)` (D2); `exam_attempt_item(tenant_id, attempt_id, sequence, paper_item_id, option_order UUID[], selected_option_id, revision BIGINT, answered_at, awarded_marks)`.
- **V114 written/physical/results**: `exam_result(tenant_id, exam_id, student_id, attendance, marks, marked_paper_media_id, marked_by, marked_at)`; `exam_submission_document(tenant_id, exam_id, student_id, media_id, uploaded_by, uploaded_at)`; `exam_notification_dispatch(tenant_id, exam_id, idempotency_key, recipient_count, sent_by, sent_at)`; `exam_leaderboard(tenant_id, name, published)`, `exam_leaderboard_exam(tenant_id, leaderboard_id, exam_id)`.

## Backend (owner: backend-springboot-engineer; `backend/**` only)

Package `com.lms.exammanagement`: `questionbank/` (service, import parser, copy, sanitizer), `exam/` (config, directory, paper draw), `enrollment/` (manual, enroll-all, payment listener), `attempt/` (start, save, submit, finalize sweep, grading), `marking/` (written/physical results, documents), `results/` (stats, export, notify, leaderboard). Reuse: `ExamAccessGuard`, `CourseLookupApi`, `MediaFileService` (new usage areas `EXAM_QUESTION`, `EXAM_PAPER`, `EXAM_ANSWER_SHEET`, `EXAM_MARKED_PAPER`), `AuditLogService`, notification-management templates, credentials encryption (`PAYMENT_CREDENTIALS_ENCRYPTION_KEY` pattern), `ExamClockConfig`. New Maven dep: `org.jsoup:jsoup`.

## Frontend (owner: frontend-nextjs-engineer; `frontend/**` only)

Routes (tenant-admin + teacher mirrors): `…/courses/[courseId]/modules/[moduleId]/questions` (+ `/new`, `/[questionId]/edit`), `…/exams` (directory), `…/exams/[examId]` (manage), `…/exams/[examId]/stats`, `…/exams/leaderboards`. Student: `/student/exams`, `/student/exams/[examId]/take`, `/student/exams/[examId]/result`. Components: `components/exams/rich-text-editor.tsx` (Tiptap + KaTeX; new deps `@tiptap/react`, `@tiptap/starter-kit`, `@tiptap/extension-underline`, `@tiptap/extension-image`, `katex`), `question-accordion`, `exam-form-dialog` (tabs), `exam-card`, `exam-management`, `exam-stats` (inline SVG chart + table), `leaderboard-*`. Shared: `lib/api/exams.ts`, `lib/validation/exams.ts`.

## Slices (sequential; each ends with targeted tests + qa-test-engineer)

| Slice | Content | Blocked by |
|---|---|---|
| A | V109–V110, question bank, editor, copy, CSV import | D1 |
| B | V111–V112, three create forms, directory, paper draw | D3, D6 |
| C | V113, enrollment (manual, enroll-all), MCQ participation & grading; payment listener | D2, D4; D5 for payment part |
| D | V114 written/physical management, documents, marking | D8 |
| E | stats, export, Notify Marks, leaderboards | D7 |
| F | security-reviewer (answer-key leakage, attempt integrity, protected docs, authz), visual pass, docs | — |

Handoff format per agent: Files changed | Completed requirements | Checks run | Remaining defects.

## Risks

- Size: ~6 migrations, ~60 backend classes, ~30 frontend components; realistically several sessions. Progress tracked in the spec matrix Status column.
- Dev DB auto-applies saved migrations (devtools) — write migrations complete before saving; never edit after first apply.
- Legacy MVP-017 attempts lack paper snapshots; they stay readable through the old endpoints until retired.

## Slice log

- **A (done 2026-10-30)** — V109/V110. Behaviour changes to note: a course module that has bank
  questions (including soft-deleted ones) or import history can no longer be deleted (409, FK-protected
  history); module-scoped questions are invisible to the legacy MVP-017 course-level endpoints; signed
  image `src` expires after 30 min (bank page needs refresh). Cross-teacher access returns 403 (existing
  `ExamAccessGuard` rule), cross-tenant 404.
