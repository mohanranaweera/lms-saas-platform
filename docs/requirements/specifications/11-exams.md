# Exams

**Domain:** `exam-management` (Module 11) · **Portal(s):** Student, Teacher, Tenant Admin

## 1. Business purpose

Let teachers create/schedule exams (MCQ + structured), auto/manually mark them, and publish
results — the reference product treats exams (and "unlimited exams") as a major,
plan-differentiating platform feature.

Source: `docs/requirements/source-requirements.md` Module 11.

## 2. Actors

- **Teacher** — create/schedule/mark/publish
- **Teacher Assistant** — create/edit exams in draft only; may **not** publish results (PROVISIONAL)
- **Exam Manager** staff — `V/C/E/A`
- **Tenant Admin / Institute Owner** — `V/C/E/A`
- **Student** — takes exam, views published results/review
- **Read-only Auditor** — `V`

## 3. Preconditions

A course exists with enrolled students; the marking actor is authorized for that course.

## 4. Normal flow

1. Teacher/Exam Manager builds a question bank (MCQ + structured).
2. Teacher schedules an exam with time limits, attaches questions.
3. Students take the exam within the scheduled window/time limit.
4. MCQ answers are auto-marked; structured answers go to a manual `Marking Queue`.
5. Teacher/Exam Manager publishes results.
6. Students view `Results & Review` (published results, answer review); exam analytics are generated.

## 5. Alternative flows

- A Teacher Assistant attempts to publish results: rejected per the PROVISIONAL matrix.
- A student attempts to access an exam before its scheduled window, or results before publication: rejected/blocked with a distinct state.
- Model Paper Library ownership between Teacher and Tenant Admin is an explicitly open question.
- Cross-tenant: a student/teacher from tenant A attempts to access an exam/question bank belonging to tenant B — rejected 403/404.
- Dropped connection mid-exam-attempt: no documented recovery UX (Open Decision).

## 6. Authorization rules

Per `docs/requirements/user-roles-and-permissions.md` §2, row "Exams": Institute Owner =
`V/C/E/A`; Exam Manager = `V/C/E/A`; Read-only Auditor = `V`; others = `—`.

## 7. Tenant rules

Exam tables (question bank, scheduling, results) are tenant-owned and course-scoped;
roster/results access must be backend-filtered per teacher assignment, mirroring the course-list
rule.

## 8. Acceptance criteria

- [ ] Given an MCQ exam is submitted, then it is auto-marked deterministically and consistently on re-computation.
- [ ] Given a structured-answer exam, then it enters a `Marking Queue` and is not auto-scored.
- [ ] Given results are unpublished, then students cannot see their score/review even if the exam attempt itself is complete.
- [ ] Given a Teacher Assistant attempts to publish results, then the action is rejected server-side regardless of UI state.
- [ ] Cross-tenant negative test on exam creation, question bank, scheduling, and results/analytics read paths.
- [ ] Empty state distinguishes "no exams scheduled" (nothing created yet) from "no published exams" (drafts exist but nothing visible to student).
- [ ] Exam submission is announced via `aria-busy`/live region, not just a spinner — especially given time-limited context.

## 9. Audit requirements

**Documentation inconsistency to flag.** `functional-requirements.md` FR-EX-2 states result
publication is "a confirmable, audit-considered action," but exam-result publication is **not**
one of the actions named in `.claude/rules/security.md`'s canonical mandatory-audit-action list
(price changes, payment approvals/rejections, device resets, access/expiry extensions,
reactivation approvals, material/course content deletions, settlement amount changes,
impersonation). This should be settled explicitly rather than assumed either way — see
`docs/requirements/open-decisions.md`.

## 10. MVP or later-phase classification

**MVP** for exam creation, question bank, scheduling, time limits, auto/manual marking, results
publishing, answer review, exam analytics (FR-EX-1/2/3; `source-requirements.md` §5 MVP list
"Basic exams"). Negative marking, randomization, pools, attempt limits, rank lists are **Phase 2**
(FR-EX-4); anti-cheating controls, paper discussion videos, model paper library are **Phase 3**
(FR-EX-5).

## UI-state and portal notes

- **Portal placement**: Student `Exams > Exam List`, `Exam Taking`, `Results & Review`; Teacher `Exams > Question Bank`, `Exam Scheduler`, `Marking Queue`, `Results Publishing`; Tenant Admin `Exams > Exam Oversight`, `Model Paper Library`.
- Exam status badges (Draft/Scheduled/Published/Closed) must pair color with text/icon.
- Exam Taking is a consumer-style, mobile-first Student surface.

## Open decisions

- Whether exam-result publication requires an audit-log entry (FR-EX-2 vs. `security.md`'s canonical list disagree).
- Model Paper Library ownership between Teacher and Tenant Admin.
- Exact recovery UX for a dropped connection during a timed exam attempt.
