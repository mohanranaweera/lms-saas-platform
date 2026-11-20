# Klass Exam Module — Specification

Status: APPROVED — D1–D9 approved by the product owner on 2026-10-30 (D5 recorded in ADR-032;
D9 = "full-stack implementation approved"). Branch: `feat/klass-exam-module`.
Plan: `docs/plans/klass-exam-module.md`. Acceptance: `docs/qa/klass-exam-module-acceptance.md`.

## 1. Evidence legend

| Code | Meaning |
|---|---|
| **S** | Screenshot-confirmed (`docs/references/Klass-Exam-Module/*.png`) |
| **L** | Live-reference-confirmed (demo.klass.lk, read-only inspection of rendered UI and shipped JS bundle, 2026-10-30) |
| **R** | Existing product requirement (CLAUDE.md, ADR-028, `.claude/rules/*`, MVP-017) |
| **A** | Proposed assumption (ours, not Klass behaviour) |
| **U** | Unverified |

## 2. Reference findings (shared summary — do not re-inspect)

Question bank
- Route `courses/{courseId}/questions/{moduleId}`; bank is **per module** (S, L). Module row action "Manage Questions" (S).
- Title "Questions in {module}", breadcrumbs Courses › {course} › Questions in {module}; tabs ALL QUESTIONS / IMPORTS; buttons COPY QUESTION, CREATE QUESTION (S).
- Accordion rows: `Qn` chip, rendered rich body (images inline), "{n} mark" chip, chevron (S). Expanded: ANSWER 1..n boxes, correct answer tinted green, EXPLANATION box, SHOW/HIDE ANSWERS toggle ("Toggle correct answer highlight"), EDIT, DELETE (S, L).
- Editor page "Add a Question to {course}": QUESTION card ("You can use rich text, images and math"), Marks field default 1 with star icon; ANSWERS card ("Check the checkbox on the correct answer. Students will see these options in a randomised order."), per-answer header with checkbox + "Correct" chip + delete icon, per-answer rich editor, ADD ANSWER; EXPLANATION (optional) "Shown to students after they submit"; CANCEL / SAVE QUESTION (S).
- Toolbar: Bold, Italic, Underline, Strikethrough, Code, H1, H2, Bullet list, Image (S). **No math button and no KaTeX/MathJax in the bundle (L)** — the "math" claim is not backed by a working mechanism in Klass.
- Copy dialog "Copy questions to another module": Course * and Module * selects, Cancel / Copy; **no per-question selection** → copies all questions of the current module (L).
- Import dialog: "Please select the CSV file which contains the questions to import. You can download the sample template…", `.csv` only, "File is required" (L). Imports tab columns File Name, Imported Date, Number of Questions, Actions; search + pagination (L, per task brief).
- **Klass sample template** (embedded data URI, UTF-8, no BOM, 55 bytes): header only — `question, correct answer, answer 2, answer 3 , answer 4` (L). No marks/explanation columns → imported questions have one correct answer (column 2) and default marks.

Exams directory
- Tabs IN PROGRESS / UPCOMING / PREVIOUS EXAMS with count chips; Search exams; LEADERBOARD, NEW EXAM; empty text "No active exams" (S).
- Card: type icon + `WRITTEN`/`MCQ` + status chip (`Finished`), ⋮ menu, name, course, `09 Jun 2024, 08:58 am · 2 years ago`, `30 minutes · 100 marks` (written) / `· 6 questions` (MCQ), `0 / 3 attended · 0%`, right `0 submitted` (written) / `0 completed` (MCQ), progress bar, MANAGE, STATS (S).

Create/Edit dialog "Add New Exam" — tabs MCQ | WRITTEN | PHYSICAL (S)
- MCQ: Name*, Exam Password*, Duration*, From Date*, To Date*, From Time*, To Time*, Number of Questions*, Cutoff Mark*, Course*, Modules* (chips), Shuffle questions, Shuffle answers, Enroll all students in the course, Auto-enroll students on payment (switch), Show correct answers with the exam results; CANCEL / CREATE EXAM (S).
- **Cutoff Mark is absolute marks**, validated `cutOffWithinMarks` against "The chosen questions are worth N marks" (L). Demo shows "worth 0 marks" defect — must not reproduce.
- Selected module exposes "Number of questions from this module" (L).
- Auto-enroll criteria depend on the course payment type: pick months / pick modules / pick dates / installment minimum / full course fee; "…are enrolled automatically **until the exam ends**"; mutually exclusive with Enroll all ("Turn off 'Enroll all students' to enroll by payment instead.") (L).
- Written: Name*, Exam Password*, Duration*, From/To Date*, From/To Time*, Course*, Total Marks*, Question Paper* (file), Marking Scheme (file), Enroll all, Show correct answers (S).
- Physical: Course, Exam Name, Scheduled Date*, Scheduled Time*, Scheduled End Date*, Scheduled End Time*, Duration (minutes) (S). No password, no total marks.

Management / stats / leaderboard
- Manage page: title + status chip (`FINISHED`) + stats icon, course, from/to timestamps, duration, `6 Questions`, password shown in plaintext (S — **not reproduced**, see D6).
- Summary cards: Enrolled "Students added to the exam"; Attended n% "Opened the paper"; Completed n% "Finished the paper with at least one answer" (S). Written: "submitted" = uploaded answer sheet (task brief).
- Enrolled Students: search, EXPORT AS CSV, NOTIFY MARKS; columns Index Number, First Name, Last Name, Result (`NOT PARTICIPATED`), Marks, Actions; paginated (S). Badge "Auto-enroll on payment"; enroll-student button when editable (L). Written adds Answer Document, Marked Paper Document; "Enter answer sheet" dialog (task brief, L).
- Stats: Average / Maximum / Minimum Marks; **Passed "{passed} of {facedStudents} students"** (L) → denominator is attended students; Question Distribution stacked chart Correct / Incorrect / Unanswered by question number; Frequently Missed Questions; Question-Wise Breakdown with "View Answer Options" (L). Demo shows `undefined of undefined` defect — must not reproduce.
- Leaderboard: named groups of exams, rows show rank medal (gold/silver/bronze), name, `totalMarks`, `share` (L). Empty state: ranks appear once marks are entered (task brief).

## 3. Requirement matrix

Status values: PASS / FAIL / BLOCKED / TODO. Rows are grouped; "Existing" = MVP-017 module as of `09af7d9`.

| # | Requirement | Evidence | Existing gap | Implementation | Acceptance | Status |
|---|---|---|---|---|---|---|
| QB1 | Module-scoped bank, "Manage Questions" module action, breadcrumbs, tabs | S,L | Bank is course-scoped only; no module link | `exam_question.module_id`; route `/…/courses/[id]/modules/[mid]/questions` | QA-QB-1 | PASS |
| QB2 | Accordion list, Qn, mark chip, answers, explanation, correct highlight, show/hide, edit/delete | S,L | Flat list, no marks/explanation | New list component | QA-QB-2 | PASS |
| QB3 | Shared create/edit editor; rich text toolbar B/I/U/S/code/H1/H2/list/image | S | Plain textarea | Tiptap editor (new dep) | QA-QB-3 | PASS |
| QB4 | Working math | R(task); Klass has none (L) | none | KaTeX inline/block math node | QA-QB-3 | PASS |
| QB5 | Marks (positive, default 1), answers ≥2, correct config, explanation | S | fixed 1 point | `marks`, `explanation`, option `sequence` | QA-QB-4 | PASS |
| QB6 | Single vs multiple correct | L (template ⇒ single) | allows any | **D1** | QA-QB-4 | PASS |
| QB7 | Server-side HTML sanitation; images tenant-protected | R | none | jsoup Safelist; `media:` refs to `media_file` | QA-SEC-3 | PASS |
| QB8 | Copy all module questions to another authorized module | L | none | `POST …/modules/{mid}/questions/copy` | QA-QB-5 | PASS |
| QB9 | CSV import with Klass template, row errors, limits, history, duplicate guard | L | none | `question_import` table | QA-QB-6 | PASS |
| QB10 | Bank edit/delete never alters attempts | R | delete blocked if used; edit mutates in place | paper snapshot (D3) | QA-INT-3 | TODO |
| EX1 | Directory tabs/counts/search/cards/actions | S | simple table | `GET /exams/directory` | QA-EX-1 | PASS (mocked UI + backend IT; live E2E blocked) |
| EX2 | Server-derived status | R | status column advanced by scheduler | derived from server clock | QA-EX-1 | PASS |
| EX3 | MCQ create/edit with module quotas, cutoff in marks | S,L | none | `exam_module_quota`, `cutoff_marks` | QA-EX-2 | PASS |
| EX4 | Unequal-marks selection model | U | n/a | **D3** | QA-EX-2 | PASS |
| EX5 | Written create with paper/scheme files | S | none | protected media refs | QA-EX-3 | PASS |
| EX6 | Physical create | S | none | type PHYSICAL | QA-EX-4 | PASS |
| EX7 | Lock structural edits once any attempt exists | R | partial (DRAFT only) | service guard | QA-EX-5 | PASS |
| EN1 | Exam enrollment separate from course enrollment | R(ADR-028) | none (any course enrollee may sit) | `exam_enrollment` | QA-EN-1 | PASS |
| EN2 | Enroll all (snapshot vs dynamic) | L(mutual exclusion) | none | **D4** | QA-EN-2 | PASS |
| EN3 | Auto-enroll on payment, criteria by billing type | L | none | **D5** (change-controlled) | QA-EN-3 | PARTIAL (see slice log C) |
| PA1 | Student list/details/eligibility/password/start/resume/save/submit | R | start/save/submit exist, no password/enrollment | extend `ExamAttemptService` | QA-PA-1 | PASS |
| PA2 | Persisted per-attempt question & option order; stable option IDs | R | no shuffle | `exam_attempt_item` | QA-PA-2 | PASS |
| PA3 | Deadline/late start/timeout/attempt limit | R | window only | **D2** | QA-PA-3 | PASS |
| PA4 | Stale autosave cannot overwrite newer answer | R | last-write-wins | client `revision` monotonic check | QA-PA-4 | PASS |
| PA5 | No key/explanation/scheme leakage before release | R | key not exposed today | release gate D2 | QA-SEC-1 | PASS |
| MG1 | Management page, summary cards, enrolled table, search, pagination | S | none | `GET /exams/{id}/management` | QA-MG-1 | PASS (mocked UI + backend IT; live E2E blocked) |
| MG2 | Written: answer upload (student / staff on behalf), marks, marked paper | S,L | none | `exam_submission_document` | QA-MG-2 | PASS (mocked UI + backend IT; live E2E blocked) |
| MG3 | Physical: attendance + marks | A | none | same result table, no attempt | QA-MG-3 | PASS (mocked UI + backend IT; live E2E blocked) |
| MG4 | Marks bounds + audit trail of manual changes | R | audit absent | `audit_log` per change | QA-MG-4 | PASS (mocked UI + backend IT; live E2E blocked) |
| MG5 | Notify Marks: preview audience, idempotent, publication | S | none | notification-management reuse | QA-MG-5 | PASS (mocked UI + backend IT; live E2E blocked) |
| MG6 | CSV export: full filtered set, leading zeros, formula escaping | S | none | server CSV | QA-MG-6 | PASS (mocked UI + backend IT; live E2E blocked) |
| MG7 | Exam password protected, not in logs/exports/student payloads | R | n/a | **D6** | QA-SEC-2 | PASS (mocked UI + backend IT; live E2E blocked) |
| ST1 | Stats with defined populations; no NaN/undefined | S,L | none | `GET /exams/{id}/stats` | QA-ST-1 | PASS (mocked UI + backend IT; live E2E blocked) |
| ST2 | Question distribution chart + accessible table, breakdown | L | none | SVG chart + table | QA-ST-2 | PASS (mocked UI + backend IT; live E2E blocked) |
| LB1 | Leaderboards CRUD (name + ≥2 exams), ranks | L | none | `exam_leaderboard`, **D7** | QA-LB-1 | PASS (mocked UI + backend IT; live E2E blocked) |
| SEC1 | Tenant / teacher / student isolation on every endpoint & file | R | partial | guard reuse + tests | QA-SEC-4 | PASS (mocked UI + backend IT; live E2E blocked) |

## 4. Decision register

Confirmed (evidence-backed) — no approval needed:
- C1 Bank is per module; copy copies **all** questions of the source module (L).
- C2 Klass-compatible CSV header `question, correct answer, answer 2, answer 3 , answer 4` (headers trimmed, case-insensitive; answers 2..n optional beyond the first wrong answer). We additionally accept optional `marks` and `explanation` columns (A) — a file with only Klass columns stays valid.
- C3 Cutoff Mark is in marks, `0 ≤ cutoff ≤ exam max marks` (L).
- C4 Passed denominator = attended students (L).
- C5 Leaderboard rows show rank, name, total marks (L).
- C6 Copied/imported questions are independent rows (no link to the source) (A, consistent with C1).

Approved decisions (2026-10-30):
- **D1 Correct answers** — exactly one correct option per MCQ (UI: checkbox styled as Klass but exclusive). Scoring: full marks if the selected option is correct, else 0.
- **D2 Assessment rules** — one attempt per student; late start allowed until window end; deadline = min(startedAt + duration, window end); server auto-finalizes at deadline (lazy on next request + scheduled sweep); unanswered = 0; no negative or partial marking; MCQ score visible to the student after the exam window closes; correct answers + explanations shown only if "Show correct answers" is on **and** the window has closed; written/physical marks visible after staff publish (Notify Marks).
- **D3 Question selection** — the question set is drawn once per exam from module quotas when the exam is saved (re-drawable until the first attempt), then frozen as an exam paper snapshot; every candidate gets the same questions, each in its own persisted order. Total marks = sum of drawn questions' marks, shown in the form. (Alternative: per-candidate random draw, only allowed when every eligible question in a module has equal marks.)
- **D4 Enroll all** — dynamic: every active course enrollee is eligible until the exam ends (late course enrollees included); materialized as `exam_enrollment` rows with source `ENROLL_ALL` on first access/listing.
- **D5 Payment auto-enroll** (change-controlled: new paid-entitlement activation path, payments.md §7) — on `PaymentConfirmedEvent`, for each order line of the course whose period/module matches the exam's criteria, idempotently insert `exam_enrollment(source=PAYMENT, payment_id)` while the exam has not ended; no backfill of payments confirmed before the exam was created unless staff click "Sync payments"; refund/chargeback removes a PAYMENT-sourced enrollment only if no attempt exists; Free Cards qualify as entitlement for month/module criteria but never count toward amount thresholds.
- **D6 Exam password** — stored AES-GCM encrypted (existing credentials-encryption pattern) so authorized staff can reveal it on the management page (reveal is audit-logged); verified server-side; never sent to students, never logged or exported.
- **D7 Leaderboard ranking** — sum of published raw marks across included exams; an included exam the student did not sit counts 0 only if the student was enrolled in it; non-enrolled students are not listed for that exam; ties share rank (1,1,3); only exams with published results contribute; students see only if staff publish the leaderboard (default staff-only).
- **D8 Physical exams** — management page records Present/Absent + marks; total marks entered on the management page before marks (Klass create form has none).
- **D9 Delivery** — backend and frontend implemented concurrently by separate agents per slice (needs the literal approval `full-stack implementation approved` per CLAUDE.md); existing MVP-017 exam endpoints may change shape (approved-API-contract change).

## 5. Lifecycle & status (server-derived)

`DRAFT` (incomplete config) → `UPCOMING` (now < start) → `IN_PROGRESS` (start ≤ now < end) → `FINISHED` (now ≥ end). Computed from `scheduled_start/end` and the injected server `Clock`; the stored `status` column is retained for MVP-017 compatibility but not used for directory grouping. Results publication is a separate flag (`results_published_at`).

Attempt: `IN_PROGRESS` → `SUBMITTED` | `EXPIRED` (auto-finalized). Internally distinct facts: *attended* (attempt row exists / paper opened), *answered* (≥1 answer saved), *finalized* (SUBMITTED|EXPIRED), *graded* (score computed), *published*.

## 6. API contract (v1, all under `/api/v1`, tenant from token)

Question bank
- `GET /courses/{cid}/modules/{mid}/questions?page&size` → `QuestionResponse{id, sequence, bodyHtml, marks, explanationHtml?, options[{id, sequence, html, correct}]}` (staff only)
- `POST /courses/{cid}/modules/{mid}/questions` / `PUT /exam-questions/{qid}` / `DELETE /exam-questions/{qid}`
- `POST /courses/{cid}/modules/{mid}/questions/copy` `{targetCourseId, targetModuleId}` → `{copied}`
- `POST /courses/{cid}/modules/{mid}/question-imports` multipart `file`, header `Idempotency-Key` → 201 `{id, fileName, importedAt, questionCount, importedByName}`; atomic: any error ⇒ 422 `{errors[{row, column, message}]}`, nothing imported
- `GET /courses/{cid}/modules/{mid}/question-imports?search&page&size`
- `GET /question-imports/template.csv`
- `POST /exam-media` multipart image → `{mediaId}`; `GET /exam-media/{id}` → 302 to short-lived signed URL after access check
- `GET /courses/{cid}/modules/question-counts` → `[{moduleId, name, questionCount, marksTotal}]`

Exams (staff)
- `GET /exams/directory?search` → `{inProgress[], upcoming[], previous[]}` of `ExamCard{id,type,status,name,courseName,start,end,durationMinutes,questionCount?,totalMarks?,enrolled,attended,completedOrSubmitted}`
- `POST /exams` `{type, …typed fields}` / `PUT /exams/{id}` / `DELETE /exams/{id}` (409 once attempts exist)
- `GET /exams/{id}` (edit form; never contains password), `POST /exams/{id}/password/reveal`
- `GET /exams/{id}/management?search&page&size`, `GET /exams/{id}/export.csv?search`
- `POST /exams/{id}/enrollments` `{studentIds}` / `DELETE /exams/{id}/enrollments/{studentId}` (409 if attempted)
- `PUT /exams/{id}/results/{studentId}` `{marks, attendance?, reason?}` (written/physical)
- `POST /exams/{id}/submissions/{studentId}/documents` (staff on behalf), `POST /exams/{id}/results/{studentId}/marked-paper`
- `GET /exams/{id}/notify-marks/preview` → `{recipients, alreadyNotified}`; `POST /exams/{id}/notify-marks` `Idempotency-Key`
- `GET /exams/{id}/stats`
- `GET|POST /exam-leaderboards`, `GET|PUT|DELETE /exam-leaderboards/{id}`, `GET /exam-leaderboards/{id}/ranks`

Student
- `GET /student/exams` (enrolled only), `GET /student/exams/{id}`
- `POST /student/exams/{id}/attempts` `{password?}` → attempt with questions (no `correct`, no explanation) in persisted order, `deadline`, `serverNow`
- `PUT /student/attempts/{aid}/answers/{itemId}` `{optionId|null, revision}` → 409 if finalized/deadline passed; ignored (200, `stale:true`) if `revision` ≤ stored
- `POST /student/attempts/{aid}/submit` (idempotent)
- `GET /student/attempts/{aid}/result` (per release policy)
- `POST /student/exams/{id}/answer-documents` (written, within window)
- Protected downloads: `GET /exams/{id}/paper`, `/marking-scheme` (staff only), `/submissions/{sid}/documents/{docId}`, `/results/{sid}/marked-paper`

Errors: existing `ApiResponse` envelope; 400 validation (field errors), 403 role, 404 cross-tenant/not-owned, 409 state conflict, 422 import rejected.

## 7. Permissions

| Actor | Scope |
|---|---|
| Tenant admin / staff with `DomainArea.EXAMS` | all tenant exams; VIEW / CREATE_EDIT / DELETE / APPROVE (publish & notify) |
| Teacher | courses where `course.teacher_id = self` (existing `ExamAccessGuard`) |
| Teacher assistant | authoring + marking, no publish/delete (existing rule) |
| Student | own `exam_enrollment` rows, own attempts/documents, released results only |
