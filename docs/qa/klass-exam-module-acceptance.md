# Klass Exam Module — Acceptance Checklist

Spec: `docs/specs/klass-exam-module.md`. All fixtures synthetic. Timing tests use the
injected `Clock` (no sleeps). Notifications only through local/test adapters.
Result values: PASS / FAIL / BLOCKED / TODO, with the evidence (test class, spec file, screenshot path).

Screenshots: `docs/qa/klass-evidence/exams/` at 1440, 820, 390 px.

| ID | Scenario | Type | Result | Evidence |
|---|---|---|---|---|
| QA-QB-1 | Module "Manage Questions" opens module bank; breadcrumbs, title, tabs | E2E + visual | PASS | ModuleQuestionBankIntegrationTest, e2e/exams-question-bank.spec.ts |
| QA-QB-2 | Accordion: Qn, mark chip, expand answers/explanation, correct highlight, Show/Hide Answers, Edit, Delete | E2E + visual | PASS | ModuleQuestionBankIntegrationTest, e2e/exams-question-bank.spec.ts |
| QA-QB-3 | Create question with bold/heading/list/code, image upload, inline math rendered by KaTeX; save, reopen, edit, re-save | E2E | PASS | ModuleQuestionBankIntegrationTest, e2e/exams-question-bank.spec.ts |
| QA-QB-4 | Validation: empty body rejected; image-only / math-only body accepted; marks ≤0 rejected; <2 options rejected; correct-answer rule (D1) | IT + E2E | PASS | ModuleQuestionBankIntegrationTest, e2e/exams-question-bank.spec.ts |
| QA-QB-5 | Copy all questions to another module in an authorized course; independent copies; unauthorized target → 404 | IT | PASS | ModuleQuestionBankIntegrationTest, e2e/exams-question-bank.spec.ts |
| QA-QB-6 | CSV: Klass template valid; Unicode (Sinhala/Tamil) preserved; bad row → 422 with row numbers, nothing imported; size/row limit; same Idempotency-Key → no duplicate; history row & count | IT | PASS | ModuleQuestionBankIntegrationTest, e2e/exams-question-bank.spec.ts |
| QA-EX-1 | Directory tabs counts == rows, status from server clock at boundaries, search within authorized set | IT + E2E | PARTIAL | ExamDirectoryClockIntegrationTest (counts pending C/D) |
| QA-EX-2 | MCQ create: modules after course, available counts, quotas sum == N, quota > available rejected, cutoff ≤ total marks (shown non-zero), no duplicate questions | IT + E2E | PASS | ExamConfigIntegrationTest, e2e/exams-directory-config.spec.ts |
| QA-EX-3 | Written create: paper required, scheme optional, total marks > 0; files protected | IT + E2E | PASS | ExamConfigIntegrationTest, e2e/exams-directory-config.spec.ts |
| QA-EX-4 | Physical create: schedule & duration; no password/question fields | E2E | PASS | ExamConfigIntegrationTest, e2e/exams-directory-config.spec.ts |
| QA-EX-5 | Edit after first attempt: structural fields locked (409), name editable; save/reload/reopen round-trip | IT + E2E | PASS | ExamConfigIntegrationTest, e2e/exams-directory-config.spec.ts |
| QA-EN-1 | Course-enrolled but not exam-enrolled student cannot start; manual enroll/remove; remove after attempt → 409 | IT | TODO | |
| QA-EN-2 | Enroll-all per D4 (late course enrollee) | IT | TODO | |
| QA-EN-3 | Payment event → enrollment once; replayed event no duplicate; multi-course order enrolls only matching exams; Free Card per D5; refund per D5 | IT | TODO | |
| QA-PA-1 | Wrong/missing password rejected; start, answer, refresh resumes same items & answers, submit, result per D2 | IT + E2E | TODO | |
| QA-PA-2 | Shuffled order stable across refresh; grading by option ID | IT | TODO | |
| QA-PA-3 | Deadline = min(start+duration, end); save after deadline 409; auto-finalize; second attempt rejected; before start rejected | IT (Clock) | TODO | |
| QA-PA-4 | Older revision does not overwrite newer; concurrent submit grades once | IT | TODO | |
| QA-INT-3 | Bank edit/delete after exam creation does not change paper, attempts, scores | IT | TODO | |
| QA-MG-1 | Management: summary cards reconcile with table and directory card | IT + E2E + visual | TODO | |
| QA-MG-2 | Written: student uploads sheet in window; staff uploads on behalf; marks within 0..total; marked paper; student sees after publish only | IT + E2E | TODO | |
| QA-MG-3 | Physical: attendance + marks, bounds | IT + E2E | TODO | |
| QA-MG-4 | Every manual mark change audit-logged with before/after | IT | TODO | |
| QA-MG-5 | Notify Marks preview count; send once per key; recipients = published, enrolled; test adapter only | IT | TODO | |
| QA-MG-6 | CSV export = filtered rows; `0006` preserved; `=cmd` cells escaped; totals match screen | IT | TODO | |
| QA-ST-1 | Stats populations: avg/min/max over attended & graded; passed = ≥ cutoff of attended; zero-attended shows "—" not NaN/undefined | IT + E2E | TODO | |
| QA-ST-2 | Per-question correct/incorrect/unanswered; chart has table alternative | IT + E2E | TODO | |
| QA-LB-1 | Leaderboard create requires name + ≥2 exams; ranks per D7; ties; unpublished excluded; empty state | IT + E2E | TODO | |
| QA-SEC-1 | Student payloads never contain `correct`, explanation, marking scheme before release | IT | TODO | |
| QA-SEC-2 | Password never in GET/list/export/logs; reveal audit-logged | IT | TODO | |
| QA-SEC-3 | `<script>`, `onerror`, `javascript:` stripped server-side; foreign-tenant media ref rejected | IT | TODO | |
| QA-SEC-4 | Cross-tenant, cross-teacher, cross-student 404/403 on every new endpoint and download | IT | TODO | |
| QA-VIS | Directory, 3 create tabs, bank, editor, management (MCQ/written/physical), attempt, result, stats, leaderboard at 3 widths | Visual | TODO | |
