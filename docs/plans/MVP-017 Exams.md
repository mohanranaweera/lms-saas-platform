# MVP-017 Exams — Module Plan

Status: **Approved plan, not yet implemented.** Produced via `plan-module`, using
`product-requirements-analyst`, `solution-architect`, `database-architect`,
`security-reviewer`, `qa-test-engineer`, `ui-ux-reviewer` (read-only analysis).
`payment-ledger-specialist` was not used — this module has no payment/ledger impact
(see §17).

Source of truth: GitHub issue [#17](https://github.com/mohanranaweera/lms-saas-platform/issues/17)
("[MVP] Module 17: Exams"). Cross-checked against
`docs/requirements/specifications/11-exams.md`, `docs/requirements/user-roles-and-permissions.md`,
`docs/requirements/module-catalog.md`, `docs/requirements/functional-requirements.md`,
`docs/requirements/open-decisions.md`, and the shipped
`course-management`/`enrollment-management`/`identity-access-service`/`attendance-management`
code and migrations (V1–V25).

Several mechanical design questions this document depends on were resolved during planning
by analogy to already-shipped precedent (not invented business rules) — flagged inline and
collected in §21. Three items are explicitly **not** resolved here because the issue itself
names them as open business decisions: Model Paper Library ownership, dropped-connection
recovery UX, and exam-result-publication audit logging (§6, §16, §21).

---

## 1. Business goal

Deliver the full exam lifecycle for `exam-management` (Module 11): a reusable,
tenant- and course-scoped question bank (MCQ + structured); exam scheduling with a
time window, a time limit, and a Draft/Scheduled/Published/Closed status lifecycle;
student exam-taking constrained to the scheduled window with deterministic,
tamper-proof MCQ auto-marking; a manual marking queue for structured answers scoped to
the marker's assigned courses (Teacher) or the flat tenant-wide `DomainArea.EXAMS`
staff grant (Exam Manager/Tenant Admin); and a hard publish gate so unpublished
results are invisible to students server-side even for a fully completed attempt —
matching the reference product's treatment of exams as a major, plan-differentiating
platform feature (`docs/requirements/specifications/11-exams.md` §1).

This is deliberately the "core exam creation/marking/results" MVP slice only
(`module-catalog.md`'s exam-management MVP line). Negative marking, randomization,
question pools, attempt limits, and rank lists are Phase 2; anti-cheating controls,
paper discussion videos, and the Model Paper Library are Phase 3 (see §6).

## 2. Roles and permissions

`DomainArea.EXAMS` already ships (`backend/src/main/java/com/lms/identityaccessservice/api/DomainArea.java:38`)
and is already granted in `PermissionCheckServiceImpl`: `TENANT_ADMIN` →
`VIEW, CREATE_EDIT, APPROVE` (line 146), `EXAM_MANAGER` → `VIEW, CREATE_EDIT, APPROVE`
(line 201), `READ_ONLY_AUDITOR` → `VIEW` only (line 219). **No
`identity-access-service` change is required for this module.**

`PermissionAction.APPROVE`'s own javadoc already names "publishing exam results" as
its canonical example (`PermissionAction.java:8-9`) — confirming, not inventing, that
schedule/publish-type actions map to `APPROVE`, and ordinary authoring maps to
`CREATE_EDIT`.

Teacher and Teacher Assistant hold **no** `DomainArea.EXAMS` grant at all (neither role
is in the shipped flat matrix — same as Courses/Attendance/Materials) — their access is
ownership-scoped, enforced by a new `ExamAccessGuard` (§9), not by
`PermissionCheckService`.

| Role | Question bank (author) | Draft exam (author/edit) | Schedule / make visible | Take exam | Mark (structured queue) | Publish results | View results/analytics | Source |
|---|---|---|---|---|---|---|---|---|
| Teacher | Yes — own assigned course only | Yes — own course only | Yes — own course only | — | Yes — own assigned courses only | Yes — own course only | Yes — own courses | `11-exams.md` §2; ownership-scoped, no `DomainArea.EXAMS` grant exists for Teacher |
| Teacher Assistant | Yes — see §21 item 1 for course-scoping caveat | Yes, but exam is force-held in `DRAFT` | **No — rejected server-side** | — | **Undecided by the issue — see §21 item 2** | **No — rejected server-side** | Same as Teacher (PROVISIONAL, §3) | `11-exams.md` §2/§8; `user-roles-and-permissions.md` §3 (PROVISIONAL, unratified) |
| Exam Manager | Yes — tenant-wide | Yes — tenant-wide | Yes — tenant-wide | — | Yes — tenant-wide, no course-ownership restriction | Yes — tenant-wide | Yes — tenant-wide | `DomainArea.EXAMS` → `VIEW, CREATE_EDIT, APPROVE` (shipped) |
| Tenant Admin / Institute Owner | Yes — tenant-wide | Yes — tenant-wide | Yes — tenant-wide | — | Yes — tenant-wide | Yes — tenant-wide | Yes — tenant-wide | `DomainArea.EXAMS` → `VIEW, CREATE_EDIT, APPROVE` (shipped) |
| Student | — | — | — | Yes — own enrollment, own attempt only | — | — | Own **published** results only | `11-exams.md` §2 |
| Read-only Auditor | View only | View only | **No** | — | **No** | **No** | View only | `DomainArea.EXAMS` → `VIEW` only (shipped) |
| Finance Staff, Course Coordinator, Student Support, Content Manager, Attendance Operator | No | No | No | No | No | No | No | No `DomainArea.EXAMS` grant exists for any of these roles in the shipped matrix |
| Platform Admin | Not applicable at MVP | — | — | — | — | — | — | No cross-tenant exam aggregation is named in scope anywhere |

## 3. Preconditions

- Tenant is active (structural, existing auth-filter chain — not a new per-endpoint check).
- A course exists in the caller's own tenant (`course.teacher_id` set) — EXM-1 hard-blocks
  on course-management and teacher-management existing, which they already do.
- The authoring/scheduling/marking actor is authorized for that specific course: Teacher =
  `course.teacherId() == principal.userId()`; Exam Manager/Tenant Admin = flat tenant-wide
  `DomainArea.EXAMS` grant, no course check; Teacher Assistant = tenant-wide but
  DRAFT-forced (§21 item 1).
- The student has a **currently active, non-expired** enrollment in the exam's course —
  same currency bar `attendance-management` already established via
  `EnrollmentAccessApi.listCurrentlyEnrolledStudentIds`/`resolveAccessState` (a merely
  historical, now-expired enrollment does not count).
- The exam's stored `status` is `PUBLISHED` (live) and the current server time is within
  `[scheduled_start, scheduled_end)` — re-verified on **every** attempt-related request,
  never cached client-side (see §9, §15).

## 4. User flows

**Flow A — Teacher/Exam Manager authors a question**
1. Actor opens the Question Bank editor for a course (Teacher: own course only; Exam
   Manager/Tenant Admin: any course in tenant; Teacher Assistant: any course in tenant,
   §21 item 1).
2. Submits an MCQ (≥2 options, at least one `is_correct = true`, validated server-side —
   not DB-enforceable as a single-row `CHECK`, see §8) or a structured/free-text question.
3. Backend persists `exam_question`/`exam_question_option` with `tenant_id`, `course_id`,
   `created_by` all server-derived. The question is immediately reusable across multiple
   exams in that course — no per-question draft/publish state exists in this schema (see
   §21 item 1 for why the issue's own AC wording is reconciled with the DB section this
   way).

**Flow B — Exam assembly + scheduling**
1. Teacher/Exam Manager/Tenant Admin (Teacher Assistant: Draft only, see below) creates an
   `exam` (title, `course_id`), attaches ordered questions via `exam_question_link`, sets
   `scheduled_start`/`scheduled_end`/`time_limit_minutes`. Exam starts life as `DRAFT`.
2. The explicit transition `DRAFT → SCHEDULED` (the only *manually* triggered lifecycle
   action — see the boxed note in §7) requires `PermissionAction.APPROVE`-equivalent
   access: owning Teacher or staff with `DomainArea.EXAMS`/`APPROVE`. **Teacher Assistant
   is denied here unconditionally, regardless of any course association** — this is the
   concrete mechanism realizing "Teacher Assistant... may not publish/schedule beyond
   draft" (issue AC).
3. Once `scheduled_start` arrives, the stored status is lazily advanced `SCHEDULED →
   PUBLISHED` (exam now live/visible/takeable) on the next read that observes the
   boundary has passed — mirroring `EnrollmentAccessApi.resolveAccessState`'s existing
   "computed live, one idempotent guarded write" pattern, not a new mechanism (§7 boxed
   note, §21 item 6).
4. Once `scheduled_end` arrives, status is lazily advanced `PUBLISHED → CLOSED` the same
   way — no further attempts may start or continue past this point.

**Flow C — Student takes the exam**
1. Student requests the exam by id; backend re-verifies (a) currently-enrolled/non-expired
   enrollment, (b) `status == PUBLISHED`, and (c) current time within
   `[scheduled_start, scheduled_end)` on **every** request (start, answer-save, submit) —
   never trusting client-held elapsed-time state (issue's explicit security requirement).
2. Backend creates an `exam_attempt` row (`started_at` = server time, `status =
   IN_PROGRESS`) — at most one `IN_PROGRESS` attempt per `(tenant, exam, student)`,
   enforced by a partial unique index (§8), though sequential re-attempts after
   `SUBMITTED`/`EXPIRED` are not blocked at MVP (attempt limits are Phase 2, §6).
3. Student answers each question; on submit, `submitted_at` is set server-side and the
   attempt transitions to `SUBMITTED` — a second submit on an already-submitted attempt is
   rejected (409), not silently accepted or re-scored.
4. MCQ answers are auto-marked immediately at submission, comparing the student's stored
   `response` against `exam_question_option.is_correct` — never against any
   client-supplied correctness/score field (issue's explicit security requirement).
5. Structured answers are written with `auto_score = NULL` and enter the Marking Queue.
6. Submission is announced via `aria-busy`/a live region on the frontend, not merely a
   spinner (issue's explicit accessibility requirement, given the time-limited context).
7. Attempting access outside the window (before `scheduled_start`, after
   `scheduled_end`/`CLOSED`, or after the attempt's own `submitted_at` is already set) is
   rejected with a distinct blocked state at the real access-check endpoint — never merely
   hidden from a list.

**Flow D — MCQ auto-marking**
1. Every MCQ `exam_answer.response` is compared deterministically against the linked
   question's stored correct option(s) at submission time; `auto_score` is written.
2. Recomputing the score later (e.g. an admin-triggered recompute) on the same stored
   `response` against the question's option data **at that later time** must produce an
   identical score to the original computation — this is only guaranteed if a question's
   `is_correct` flags are never edited after live attempts exist against it, which this
   plan does not yet enforce at the schema level (flagged as an unresolved data-integrity
   question, §21 item 4 — do not silently assume either "snapshot at submission" or
   "always re-read live" without product/solution-architect sign-off).

**Flow E — Structured marking queue**
1. Structured `exam_answer` rows with `manual_score IS NULL` appear in the Marking Queue,
   queried by `(tenant_id, exam_id)` per the issue's explicit index requirement.
2. Marker (Teacher: own assigned courses only; Exam Manager/Tenant Admin: tenant-wide, no
   course-ownership restriction — §21 item 2 disambiguates the issue's "assigned courses"
   wording exactly as `MVP-016 Attendance.md` §2 disambiguated the analogous case) opens
   the queue; backend filters server-side to the marker's authorized course set.
3. Marker submits a score; `manual_score`, `marked_by`, `marked_at` are all recorded
   together (schema-enforced, §8) from the trusted context, never the request body.
4. A Teacher attempting to mark an answer for a course they don't own is rejected `403`,
   zero score written (issue's explicit AC).

**Flow F — Results publishing**
1. Once `exam.status == CLOSED` (service-layer precondition — see §12, not a DB `CHECK`),
   the owning Teacher or `DomainArea.EXAMS`/`APPROVE`-holding staff may publish, setting
   `results_published_at`.
2. Teacher Assistant attempting this action is rejected `403` server-side, unconditionally
   (issue's explicit AC).
3. Publishing is a one-way action for this MVP — no unpublish path is designed (issue is
   silent on it; §21 item 6).
4. Publishing raises `ExamResultPublishedEvent` in-process (same transaction, per
   `.claude/rules/backend.md`'s transaction-boundary rules) — no consumer exists yet
   (`notification-management` is not built), same "ship the event now, no consumer needed
   yet" precedent as `PaymentConfirmedEvent`/`CoursePriceChangedEvent`.

**Flow G — Student Results & Review**
1. Student requests results for their own attempt; backend checks `results_published_at
   IS NOT NULL` on the exam **at the results-read endpoint itself** — a completed attempt
   with no publication returns a distinct "not yet published" state, never the score
   (issue's explicit AC, verified by hitting the endpoint directly, not by omitting a UI
   link).
2. Once published: score, per-question review, rendered with a Status Chip.
3. Two distinct empty states, per the issue's explicit AC: "no exams scheduled" (nothing
   created for the student's enrolled courses yet) vs. "no published exams" (exams/drafts
   exist, nothing is visible to this student yet) — never the same generic copy.

## 5. Acceptance criteria

**Question bank / authoring**
- Given a Teacher/Exam Manager creates a question for a course they're authorized on,
  then it persists tenant- and course-scoped and is reusable across multiple exams in
  that course.
- Given a Teacher Assistant creates a question, then it persists normally (no
  question-level draft flag exists in this schema — see §21 item 1) but any exam it is
  attached to cannot leave `DRAFT` while under TA control.
- Given a Teacher attempts to create/edit a question for a course they do not own, then
  rejected `403`, zero rows written.
- Given any caller reads/writes a question/exam by id belonging to a different tenant,
  then rejected `404`.
- Given the question bank list is requested with zero questions ever created for a
  course, then the empty state reads "no questions in the bank yet" — distinct from a
  filtered-to-zero search result.

**Scheduling**
- Given a Teacher/Exam Manager schedules an exam with a valid window (`scheduled_end >
  scheduled_start`) and a positive `time_limit_minutes`, then `status` transitions
  `DRAFT → SCHEDULED` and persists with `tenant_id`, `course_id`.
- Given a Teacher Assistant attempts to transition an exam beyond `DRAFT`, then rejected
  `403` server-side, regardless of what the frontend renders/disables.
- Given a student requests exam access by direct id before `scheduled_start`, then
  rejected/blocked with a distinct "not yet open" state at the real access-check
  endpoint — not merely omitted from a list.
- Given a student requests exam access by direct id after `scheduled_end`/`CLOSED`, then
  rejected with a distinct "window closed" state.
- Given zero exams have ever been scheduled for a student's enrolled courses, then the
  Student exam list shows "no exams scheduled" — distinct copy from "no published exams."
- Given a cross-tenant scheduling request (Teacher/Exam Manager of tenant A targeting a
  course id of tenant B), then rejected `403`/`404`.

**Exam taking / auto-marking**
- Given a currently-enrolled student takes an MCQ exam within the window and submits,
  then each MCQ answer is auto-marked deterministically from stored option data, never
  from any client-supplied score/correctness field.
- Given a structured-answer exam is submitted, then those answers enter the Marking
  Queue and `auto_score` is never populated for them.
- Given a student attempts to access another same-tenant student's attempt by id, then
  rejected `404` (not `403` — a `403` would confirm the attempt id belongs to some other
  real student; see §15).
- Given a student of tenant A attempts to access an exam/attempt/question belonging to
  tenant B by id, then rejected `404`.
- Given a student's enrollment has expired between exam-window-open and their access
  attempt, then rejected — re-verified on every attempt/submission request, not just at
  attempt creation.
- Given a student resubmits an already-`SUBMITTED` attempt, then rejected `409`, no
  re-scoring occurs.
- Given a dropped connection mid-attempt, then behavior beyond "the window/enrollment
  check re-runs on the student's next request" is explicitly undefined pending product
  decision (§6, §21) — no auto-submit-on-timeout or resume-with-restored-time behavior is
  invented here.

**Manual marking queue**
- Given a structured answer exists unmarked, then it appears in the Marking Queue
  filtered to `(tenant_id, exam_id)` and further to the marker's authorized course scope.
- Given a Teacher attempts to mark an answer for a course they are not the assigned
  teacher of, then rejected `403`, zero score written.
- Given an Exam Manager/Tenant Admin marks any structured answer in-tenant, then
  accepted without a course-ownership check (flat tenant-wide grant, §21 item 2).
- Given a marker of tenant A attempts to read/score a queue entry belonging to tenant B,
  then rejected `404`.
- Given zero structured answers are pending for a marker's scope, then the Marking Queue
  shows an explicit "nothing to mark" empty state, not a spinner/error.

**Results publishing / review**
- Given an exam's `status` is not yet `CLOSED`, then the publish-results action is
  rejected (service-layer precondition, §12) — prevents leaking answers to students who
  may still be able to attempt.
- Given a Teacher Assistant attempts to publish, then rejected `403` server-side.
- Given results are unpublished, then a student with a fully complete attempt still
  cannot retrieve score/review at the results-read endpoint — verified by hitting the
  endpoint directly.
- Given results are published, then the student's own Results & Review shows score +
  per-question review with a Status Chip pairing color and text/icon.
- Given a student requests results for an exam with zero published results anywhere in
  their enrolled courses, then "no published exams" is shown, distinct from "no exams
  scheduled."
- Given a Read-only Auditor requests any exam mutation (create/schedule/mark/publish),
  then rejected `403` regardless of stale client UI.

**Cross-cutting / accessibility**
- Given the exam submission is in flight, then `aria-busy`/a live region announces
  state — not merely a visual spinner.
- Given the Question Bank editor, Exam Scheduler, and Marking Queue scoring controls,
  then all are fully keyboard-operable/navigable.

## 6. Out-of-scope items

- Negative marking, randomized questions, question pools, per-student attempt limits,
  rank lists (Phase 2 — `functional-requirements.md` FR-EX-4).
- Anti-cheating controls, paper discussion videos, Model Paper Library (Phase 3 —
  FR-EX-5). **Model Paper Library is explicitly excluded from this MVP-017 build** even
  though `docs/ui-ux/screen-map.md` line 126 lists a Tenant Admin "Model Paper Library"
  screen with no phase qualifier — that listing is stale/ahead of the spec and should be
  corrected in documentation (§19), not silently built now.
- Exam analytics (FR-EX-3) — tagged MVP in `functional-requirements.md` and mentioned in
  `11-exams.md`'s normal flow, but **issue #17 itself (the stated source of truth for
  this module) contains zero mention of analytics** anywhere in its five stories or its
  backend/frontend/database/security/test requirements. Resolved by precedence, issue
  wins for this PR's actual build scope (mirrors how `MVP-016 Attendance.md` resolved an
  analogous FR-vs-issue tension for the `LATE` status value) — analytics is treated as
  not in this module's delivery; the `functional-requirements.md` FR-EX-3 phase tag
  should be revisited separately (§21 item 5), not silently built or silently dropped
  without a documentation update.
- AI-assisted quiz generation (Module F cross-cutting, unratified/unowned per
  `module-catalog.md`).
- **Named open decisions — flagged, not resolved by this module:**
  1. Model Paper Library ownership between Teacher and Tenant Admin (Phase 3 anyway).
  2. Exact recovery UX for a dropped connection mid-exam-attempt — undocumented anywhere;
     no resume/restart/auto-submit policy is invented (§21 item 7).
  3. Whether exam-result publication requires a mandatory audit-log entry —
     `functional-requirements.md` FR-EX-2 calls it "audit-considered" but
     `.claude/rules/security.md`'s canonical mandatory-audit-action list does not name
     it (§16, §21 item 8).

## 7. Domain model

New domain `exam-management`, package `com.lms.exammanagement`, per
`.claude/rules/architecture.md`'s confirmed domain list and per-domain layout:

- `api` — `ExamResultPublishedEvent` (published, no consumer yet — mirrors
  `PaymentConfirmedEvent`/`CoursePriceChangedEvent`'s "ship now, no consumer needed yet"
  precedent). No inbound `api` surface is needed — no sibling module was named as a
  consumer of exam data at MVP.
- `web` — thin controllers, one per portal-facing capability (§10).
- `service` — `QuestionBankService`, `ExamSchedulingService`, `ExamAttemptService`,
  `McqAutoMarkingService`, `MarkingQueueService`, `ResultsPublishingService`.
- `domain` — `ExamQuestion`, `ExamQuestionOption`, `Exam`, `ExamQuestionLink`,
  `ExamAttempt`, `ExamAnswer`. All extend `Auditable`/implement `TenantOwned`; all
  cross-domain ids (`courseId`, `studentId`, `teacherId`, `markedBy`) stay bare `UUID`,
  never a JPA `@ManyToOne` across the module boundary — schema-enforced via composite
  FKs instead (exactly the `Course`/`CourseLesson`/`AttendanceRecord` precedent).
- `repository` — `ExamQuestionRepository`, `ExamRepository`, `ExamAttemptRepository`,
  `ExamAnswerRepository`, all extending the shared `TenantAwareRepository<T, ID>` —
  package-private-facing, never exported.
- `support` — `ExamAccessGuard` (§9).

> **Boxed note — exam status lifecycle interpretation (recommended, needs confirmation).**
> The issue's `status CHECK (DRAFT/SCHEDULED/PUBLISHED/CLOSED)` enum is not accompanied
> by an explicit description of what triggers each transition. This plan adopts the
> reading that best matches the badge ordering in `docs/requirements/specifications/11-exams.md`
> and `docs/ui-ux/accessibility.md:86`, and reuses an already-shipped codebase mechanism
> rather than inventing a new one:
> - `DRAFT`: authoring, not visible to students.
> - `SCHEDULED`: window + questions locked in by an explicit `APPROVE`-gated action
>   (owning Teacher or `DomainArea.EXAMS`/`APPROVE` staff) — the **only** manually
>   triggered transition. The exam is now visible in the student's "upcoming" list, but
>   not yet attemptable (current time still before `scheduled_start`).
> - `PUBLISHED`: the exam is live/attemptable — **system-computed**, not manually
>   triggered: lazily advanced from `SCHEDULED` the first time any read/access-check
>   observes `now() >= scheduled_start`, exactly mirroring
>   `EnrollmentAccessApi.resolveAccessState`'s existing "computed live, one idempotent
>   guarded write" pattern (`EnrollmentAccessState`'s own javadoc).
> - `CLOSED`: the window has ended — same lazy-computed-write mechanism, triggered by
>   `now() >= scheduled_end`.
> - `exam.results_published_at` is a **fully separate, independently-set gate** on top
>   of this lifecycle (§9, §12) — publishing results is never conflated with the
>   `PUBLISHED` status value above, which governs the exam's own visibility/attemptability,
>   not its results' visibility.
>
> This is a mechanical/implementation-pattern decision, reusing existing precedent — not
> a new business rule — but is flagged explicitly (§21 item 6) since the issue itself
> doesn't spell out the transition triggers, and a different interpretation would change
> the API contract (§10).

> **Boxed note — Teacher Assistant question/exam authoring (recommended, needs
> confirmation).** No TA-to-course assignment table exists anywhere in this codebase
> (confirmed absent in every migration through V25) — `MVP-016 Attendance.md` hit this
> exact gap and fully excluded TA from that module. Because issue #17, unlike
> Attendance's issue, explicitly requires TA-authoring behavior as an MVP acceptance
> criterion, this plan adopts the narrowest buildable interpretation instead of
> re-excluding TA entirely: **Teacher Assistant may create/edit a question or a
> `DRAFT`-status exam for any course in their own tenant (no course-ownership check,
> since none is possible today), but can never transition an exam past `DRAFT`
> (`SCHEDULED`/publish-results are both denied unconditionally).** The blast radius is
> contained by the DRAFT-forever ceiling — a TA can never make anything visible/live or
> publish results — but this is weaker least-privilege than true course-scoping would
> give. Flagged for explicit confirmation (§21 item 1); a future TA-to-course assignment
> table would let this be tightened without a breaking change.

## 8. Database design

New migration `V26__create_exam_management_schema.sql` (V25 is the current latest;
purely additive, no existing migration is altered).

```sql
-- exam-management (Module 11 / MVP-017, GitHub issue #17): `exam_question`,
-- `exam_question_option`, `exam`, `exam_question_link`, `exam_attempt`,
-- `exam_answer`.
--
-- Owned by com.lms.exammanagement.domain, per .claude/rules/architecture.md's
-- confirmed domain list. Every table is tenant-owned: `tenant_id NOT NULL
-- REFERENCES tenant(id)`, a composite index leading with `tenant_id`, and every
-- cross-table reference to another tenant-owned table is a composite
-- `(tenant_id, ...)` FK - never a bare FK on the child id alone. `id` has no
-- DB-side DEFAULT anywhere - generated application-side (UUIDv7), per V1's
-- baseline convention. `created_by`/`updated_by` are the generic Auditable
-- columns (bare nullable UUID, no FK), matching course/course_module/
-- attendance_record's exact shape. `marked_by`/`marked_at` on exam_answer are
-- explicit domain columns (schema-enforced FK + NOT-NULL-together CHECK),
-- mirroring attendance_record.marked_by's (V25) reasoning.

CREATE TABLE exam_question (
    id             UUID PRIMARY KEY,
    tenant_id      UUID NOT NULL REFERENCES tenant (id),
    course_id      UUID NOT NULL,
    question_type  VARCHAR(20) NOT NULL,
    body           TEXT NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL,
    created_by     UUID,
    updated_by     UUID,

    CONSTRAINT uq_exam_question_tenant_id UNIQUE (tenant_id, id),

    -- No CASCADE: a course's question bank is reusable content with ongoing
    -- value beyond any single exam.
    CONSTRAINT fk_exam_question_course FOREIGN KEY (tenant_id, course_id)
        REFERENCES course (tenant_id, id),

    CONSTRAINT ck_exam_question_type CHECK (question_type IN ('MCQ', 'STRUCTURED')),
    CONSTRAINT ck_exam_question_body_not_blank CHECK (btrim(body) <> '')
);

-- Question-bank browsing read pattern: all questions for one course.
CREATE INDEX idx_exam_question_tenant_course
    ON exam_question (tenant_id, course_id);

CREATE TABLE exam_question_option (
    id            UUID PRIMARY KEY,
    tenant_id     UUID NOT NULL REFERENCES tenant (id),
    question_id   UUID NOT NULL,
    option_text   TEXT NOT NULL,
    is_correct    BOOLEAN NOT NULL DEFAULT false,
    created_at    TIMESTAMPTZ NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL,
    created_by    UUID,
    updated_by    UUID,

    -- CASCADE (deliberate deviation from the default RESTRICT posture): an
    -- option has no independent value once its parent question is gone - a
    -- pure structural child, mirroring V14's course_module/course_lesson
    -- CASCADE reasoning. Deleting exam_question itself is still blocked once
    -- linked into an exam (fk_exam_question_link_question) or answered
    -- (fk_exam_answer_question) - this CASCADE only fires for a never-used
    -- question's own options.
    CONSTRAINT fk_exam_question_option_question FOREIGN KEY (tenant_id, question_id)
        REFERENCES exam_question (tenant_id, id) ON DELETE CASCADE,

    CONSTRAINT ck_exam_question_option_text_not_blank CHECK (btrim(option_text) <> '')
);

CREATE INDEX idx_exam_question_option_tenant_question
    ON exam_question_option (tenant_id, question_id);

CREATE TABLE exam (
    id                    UUID PRIMARY KEY,
    tenant_id             UUID NOT NULL REFERENCES tenant (id),
    course_id             UUID NOT NULL,
    title                 VARCHAR(255) NOT NULL,
    scheduled_start       TIMESTAMPTZ NOT NULL,
    scheduled_end         TIMESTAMPTZ NOT NULL,
    time_limit_minutes    INTEGER NOT NULL,
    status                VARCHAR(10) NOT NULL,
    -- Nullable gate, fully independent of `status` above (see plan §7's boxed
    -- note): NULL means results are not yet visible to students. Set once, by
    -- an authorized APPROVE-gated action, only once status = 'CLOSED'
    -- (service-layer precondition, plan §12 - not a DB CHECK, since encoding
    -- it here would need a cross-column CHECK re-evaluated on every status
    -- update, and the service is the single non-bypassable write path anyway).
    results_published_at  TIMESTAMPTZ,
    created_at            TIMESTAMPTZ NOT NULL,
    updated_at            TIMESTAMPTZ NOT NULL,
    created_by            UUID,
    updated_by            UUID,

    CONSTRAINT uq_exam_tenant_id UNIQUE (tenant_id, id),

    -- No CASCADE: an exam that has ever been attempted must outlive a course
    -- delete attempt (attempts/answers are academic history); in practice a
    -- course delete is already blocked transitively once any exam_attempt
    -- exists.
    CONSTRAINT fk_exam_course FOREIGN KEY (tenant_id, course_id)
        REFERENCES course (tenant_id, id),

    CONSTRAINT ck_exam_title_not_blank CHECK (btrim(title) <> ''),
    CONSTRAINT ck_exam_status CHECK (status IN ('DRAFT', 'SCHEDULED', 'PUBLISHED', 'CLOSED')),
    CONSTRAINT ck_exam_schedule_window CHECK (scheduled_end > scheduled_start),
    CONSTRAINT ck_exam_time_limit_minutes_positive CHECK (time_limit_minutes > 0)
);

-- Teacher/exam-manager's exam-list-by-course read, chronological.
CREATE INDEX idx_exam_tenant_course_scheduled_start
    ON exam (tenant_id, course_id, scheduled_start DESC);

-- Status-filtered, time-ordered listing (admin/ops dashboard), mirroring
-- course.idx_course_tenant_status_created_at's exact shape (V11).
CREATE INDEX idx_exam_tenant_status_scheduled_start
    ON exam (tenant_id, status, scheduled_start DESC);

-- Results-read gate: the exam-side half of the (tenant_id, student_id) x
-- results_published_at IS NOT NULL intersection the issue calls out
-- explicitly.
CREATE INDEX idx_exam_tenant_results_published
    ON exam (tenant_id, id)
    WHERE results_published_at IS NOT NULL;

CREATE TABLE exam_question_link (
    id          UUID PRIMARY KEY,
    tenant_id   UUID NOT NULL REFERENCES tenant (id),
    exam_id     UUID NOT NULL,
    question_id UUID NOT NULL,
    sequence    INTEGER NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL,
    created_by  UUID,
    updated_by  UUID,

    CONSTRAINT uq_exam_question_link_tenant_exam_sequence
        UNIQUE (tenant_id, exam_id, sequence),
    CONSTRAINT uq_exam_question_link_tenant_exam_question
        UNIQUE (tenant_id, exam_id, question_id),

    -- CASCADE: a purely structural bridge row, no independent value once its
    -- parent exam is gone (mirrors V14). In practice this can only fire for
    -- an exam with no attempts yet.
    CONSTRAINT fk_exam_question_link_exam FOREIGN KEY (tenant_id, exam_id)
        REFERENCES exam (tenant_id, id) ON DELETE CASCADE,
    -- No CASCADE: a question already scheduled into an exam must not be
    -- deletable out from under that exam.
    CONSTRAINT fk_exam_question_link_question FOREIGN KEY (tenant_id, question_id)
        REFERENCES exam_question (tenant_id, id),

    CONSTRAINT ck_exam_question_link_sequence_positive CHECK (sequence > 0)
);

-- Exam-taking / exam-authoring ordered question list for one exam.
CREATE INDEX idx_exam_question_link_tenant_exam_sequence
    ON exam_question_link (tenant_id, exam_id, sequence);

-- Reverse lookup: "which exams use this question" (explains delete-blocked).
CREATE INDEX idx_exam_question_link_tenant_question
    ON exam_question_link (tenant_id, question_id);

CREATE TABLE exam_attempt (
    id            UUID PRIMARY KEY,
    tenant_id     UUID NOT NULL REFERENCES tenant (id),
    exam_id       UUID NOT NULL,
    student_id    UUID NOT NULL,
    started_at    TIMESTAMPTZ NOT NULL,
    submitted_at  TIMESTAMPTZ,
    status        VARCHAR(20) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL,
    created_by    UUID,
    updated_by    UUID,

    CONSTRAINT uq_exam_attempt_tenant_id UNIQUE (tenant_id, id),

    -- No CASCADE: an attempt is academic/exam history, must outlive the
    -- exam's own lifecycle edits (and blocks exam deletion once any attempt
    -- exists).
    CONSTRAINT fk_exam_attempt_exam FOREIGN KEY (tenant_id, exam_id)
        REFERENCES exam (tenant_id, id),
    CONSTRAINT fk_exam_attempt_student FOREIGN KEY (tenant_id, student_id)
        REFERENCES tenant_user (tenant_id, id),

    -- Status values proposed by this plan (the issue names only the column,
    -- no values): IN_PROGRESS (started, not yet submitted), SUBMITTED
    -- (student submitted within the time limit/window), EXPIRED (window/time
    -- limit elapsed before submission - see plan §12 for who/what writes
    -- this transition).
    CONSTRAINT ck_exam_attempt_status CHECK (status IN ('IN_PROGRESS', 'SUBMITTED', 'EXPIRED')),
    CONSTRAINT ck_exam_attempt_submitted_requires_timestamp CHECK (
        status = 'IN_PROGRESS' OR submitted_at IS NOT NULL
    ),
    CONSTRAINT ck_exam_attempt_submitted_after_started CHECK (
        submitted_at IS NULL OR submitted_at >= started_at
    )
);

-- Student's own attempts/results read pattern, most recent first - also the
-- student-scoped half of the results-read intersection.
CREATE INDEX idx_exam_attempt_tenant_student_started_at
    ON exam_attempt (tenant_id, student_id, started_at DESC);

-- Teacher/exam-manager's "all attempts for this exam" read pattern.
CREATE INDEX idx_exam_attempt_tenant_exam
    ON exam_attempt (tenant_id, exam_id);

-- At most one concurrently IN_PROGRESS attempt per (tenant, exam, student).
-- Does NOT block a second SEQUENTIAL attempt once the first is
-- SUBMITTED/EXPIRED - attempt-count limiting is explicitly Phase 2 (see
-- plan §6/§21 item 3), so this is scoped narrowly to a data-integrity
-- concern (no two simultaneously live sessions), not a business
-- attempt-limit policy.
CREATE UNIQUE INDEX uq_exam_attempt_tenant_exam_student_in_progress
    ON exam_attempt (tenant_id, exam_id, student_id)
    WHERE status = 'IN_PROGRESS';

CREATE TABLE exam_answer (
    id            UUID PRIMARY KEY,
    tenant_id     UUID NOT NULL REFERENCES tenant (id),
    attempt_id    UUID NOT NULL,
    question_id   UUID NOT NULL,
    -- Denormalized from attempt_id -> exam_attempt.exam_id, following
    -- attendance_record.course_id's (V25) exact precedent: a documented,
    -- accepted denormalization, not a schema-enforced invariant. Needed
    -- because the issue explicitly requires a marking-queue read filtered to
    -- (tenant_id, exam_id). ExamAnswerService MUST derive exam_id
    -- server-side from the attempt's real parent exam, never trust a
    -- client-supplied examId. A negative test proving an attempt from exam A
    -- cannot be recorded under exam_id B is required (plan §18).
    exam_id       UUID NOT NULL,
    response      TEXT,
    auto_score    NUMERIC(6,2),
    manual_score  NUMERIC(6,2),
    marked_by     UUID,
    marked_at     TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL,
    created_by    UUID,
    updated_by    UUID,

    CONSTRAINT uq_exam_answer_tenant_attempt_question
        UNIQUE (tenant_id, attempt_id, question_id),

    -- No CASCADE: a submitted answer is exam/academic history.
    CONSTRAINT fk_exam_answer_attempt FOREIGN KEY (tenant_id, attempt_id)
        REFERENCES exam_attempt (tenant_id, id),
    CONSTRAINT fk_exam_answer_question FOREIGN KEY (tenant_id, question_id)
        REFERENCES exam_question (tenant_id, id),
    CONSTRAINT fk_exam_answer_exam FOREIGN KEY (tenant_id, exam_id)
        REFERENCES exam (tenant_id, id),
    -- Nullable until manually marked (STRUCTURED questions only).
    CONSTRAINT fk_exam_answer_marked_by FOREIGN KEY (tenant_id, marked_by)
        REFERENCES tenant_user (tenant_id, id),

    CONSTRAINT ck_exam_answer_auto_score_nonnegative CHECK (auto_score IS NULL OR auto_score >= 0),
    CONSTRAINT ck_exam_answer_manual_score_nonnegative CHECK (manual_score IS NULL OR manual_score >= 0),
    -- Mirrors payment_slip.ck_payment_slip_reviewed_requires_reviewer (V21)
    -- and reactivation_request's analogous CHECK (V22): a manual mark must
    -- carry who/when together, never a bare score with no accountable marker.
    CONSTRAINT ck_exam_answer_marked_fields_together CHECK (
        (manual_score IS NULL AND marked_by IS NULL AND marked_at IS NULL) OR
        (manual_score IS NOT NULL AND marked_by IS NOT NULL AND marked_at IS NOT NULL)
    )
);

-- Attempt-detail / student-review read pattern: all answers for one attempt.
CREATE INDEX idx_exam_answer_tenant_attempt
    ON exam_answer (tenant_id, attempt_id);

-- Marking-queue read, filtered to (tenant_id, exam_id) per the issue's
-- explicit requirement. "Pending only" (STRUCTURED + manual_score IS NULL)
-- is a query-time filter joined against exam_question.question_type.
CREATE INDEX idx_exam_answer_tenant_exam
    ON exam_answer (tenant_id, exam_id);
```

**MCQ "at least one correct option" is enforced at the service layer, not a DB
`CHECK`** — it's a cross-row, per-question aggregate invariant that a single-row
`CHECK` cannot express. `QuestionBankService` validates this at question-publish/
exam-schedule time (same class of service-layer-only invariant already accepted
elsewhere in this codebase, e.g. `attendance_record`'s course/session consistency).

**No `ON DELETE CASCADE`** from `course`/`exam`/`exam_question` onto
`exam_attempt`/`exam_answer`/`exam_question_link`'s question side — exam history is
academic history, mirroring V25's "no CASCADE on history-like tables" precedent.
Deleting a course or question that already has exam/attempt history is **blocked**
by this migration rather than silently cascaded away.

**Deviation flagged, not silently added:** `ck_exam_results_published_requires_closed`
(`CHECK (results_published_at IS NULL OR status = 'CLOSED')`) was considered and
deliberately **not** added as a DB constraint — it encodes a business rule the issue
doesn't literally state, even though this plan's own service-layer design (§12)
enforces the same rule at write time. If a future reviewer wants it schema-enforced
too, that's an additive follow-up migration, not a retrofit of this one.

## 9. Backend design

**Services**
- `QuestionBankService` — CRUD on course-scoped questions (MCQ + structured); `tenant_id`/
  `course_id`/`created_by` always server-derived.
- `ExamSchedulingService` — exam creation/editing while `DRAFT`; attaching ordered
  questions; the single explicit `DRAFT → SCHEDULED` transition (APPROVE-gated).
- `ExamAttemptService` — student-facing exam-taking: resolves current lifecycle status
  (lazily advancing `SCHEDULED → PUBLISHED → CLOSED` per §7's boxed note before any other
  check runs), verifies enrollment currency, starts/loads an attempt, accepts
  answer-writes, handles submit (idempotent — a second submit is rejected, not re-scored).
- `McqAutoMarkingService` — computes `auto_score` for MCQ answers at submission time from
  `exam_question_option.is_correct`, never from the request payload.
- `MarkingQueueService` — structured-answer manual marking, scoped to the marker's
  assigned courses (Teacher) or tenant-wide (`DomainArea.EXAMS` staff).
- `ResultsPublishingService` — the publish gate (requires `status == CLOSED`, §12) and the
  student-facing "Results & Review" read (requires `results_published_at IS NOT NULL`).

**`ExamAccessGuard`** (in `support`), with **two** distinct check methods — a genuine
deviation from `CourseAccessGuard`/`AttendanceAccessGuard`'s single-method shape, because
Teacher Assistant needs a different answer for authoring vs. lifecycle-transition actions
(§7's boxed note):

```java
public void requireAuthoringAccess(UUID courseId, PermissionAction action) {
    AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
    if ("TEACHER".equals(principal.role())) {
        requireOwnedCourse(courseId, principal);
        return;
    }
    if ("TEACHER_ASSISTANT".equals(principal.role())) {
        return; // tenant-wide authoring allowed, capped at DRAFT by ExamSchedulingService
    }
    permissionCheckService.requirePermission(DomainArea.EXAMS, action);
}

public void requireLifecycleTransitionAccess(UUID courseId) {
    AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
    if ("TEACHER".equals(principal.role())) {
        requireOwnedCourse(courseId, principal);
        return;
    }
    if ("TEACHER_ASSISTANT".equals(principal.role())) {
        throw new AccessDeniedException("You do not have permission to perform this action");
    }
    permissionCheckService.requirePermission(DomainArea.EXAMS, PermissionAction.APPROVE);
}
```

`requireLifecycleTransitionAccess` is used for `DRAFT → SCHEDULED` and for results
publishing — both are `APPROVE`-class actions, both unconditionally deny Teacher
Assistant. `requireAuthoringAccess` is used for question CRUD and `DRAFT`-exam
editing.

**No new cross-module API surface is needed** — a simpler dependency footprint than
`attendance-management` needed (which added one new `EnrollmentAccessApi` method):
- Course ownership: `CourseLookupApi.getTeacherId(UUID courseId)` (already shipped)
  covers the single-course check; `CourseLookupApi.getTeacherIdsByCourseId(Set<UUID>)`
  (already shipped, added post-review for Attendance) covers any batched
  teacher-scoped report/list view.
- Enrollment currency: `EnrollmentAccessApi.resolveAccessState(UUID studentId, UUID
  courseId)` (already shipped) is the right check at attempt-start time;
  `EnrollmentAccessApi.listCurrentlyEnrolledStudentIds(UUID courseId)` (already shipped)
  covers any course-scoped roster/eligibility view.

**Confirms no ADR is required**: `exam-management` is already named in
`.claude/rules/architecture.md`'s confirmed domain list and already has a
`module-catalog.md` entry. No new domain, no microservice, no separate datastore, no
multi-tenancy/auth-architecture change.

## 10. API contract

Envelope: `ApiResponse<T>` / `PageResponse<T>` per `docs/api/course-management.md`'s
already-established convention (identical shape, not repeated here). This section is
the input for a dedicated `review-api-contract` pass before/at the start of
implementation — exact param names may be refined there, but the shapes/auth model
below are fixed.

### Question bank

- `GET /api/v1/exams/courses/{courseId}/questions` — list, paginated. Auth: owning
  Teacher, Teacher Assistant (tenant-wide), or `DomainArea.EXAMS`/`VIEW` staff.
- `POST /api/v1/exams/courses/{courseId}/questions` — create. Auth:
  `requireAuthoringAccess`. Request: `{ questionType: "MCQ"|"STRUCTURED", body,
  options?: [{ optionText, isCorrect }] }` (MCQ only). **404** for a cross-tenant
  `courseId`, before any other validation.
- `PUT /api/v1/exams/questions/{questionId}` — update. Same auth. **404** cross-tenant.
- `DELETE /api/v1/exams/questions/{questionId}` — delete. Rejected **409** if any
  `exam_question_link`/`exam_answer` references it (schema-enforced `RESTRICT`, §8).

### Exam scheduling

- `POST /api/v1/exams/courses/{courseId}/exams` — create draft exam. Auth:
  `requireAuthoringAccess`. Request: `{ title }`. Starts `status = DRAFT`.
- `PUT /api/v1/exams/{examId}` — edit while `DRAFT` (title, question links,
  `scheduledStart`/`scheduledEnd`/`timeLimitMinutes`). Same auth; rejected **409** if
  `status != DRAFT`.
- `POST /api/v1/exams/{examId}/schedule` — the single explicit lifecycle transition,
  `DRAFT → SCHEDULED`. Auth: `requireLifecycleTransitionAccess` (Teacher Assistant
  always **403**). Rejects if the exam has zero linked questions or an invalid window.
- `GET /api/v1/exams/{examId}` — read. Auth: owning Teacher/TA/staff-view, or an
  enrolled Student (once `status == PUBLISHED`, filtered fields only — no answer keys).

### Exam taking (Student)

- `GET /api/v1/exams/my/upcoming` — the student's own exam list (per-course enrollment
  intersected with `status IN (SCHEDULED, PUBLISHED)`). `hasRole('STUDENT')`, owner-only.
- `POST /api/v1/exams/{examId}/attempts` — start (or resume the single `IN_PROGRESS`)
  attempt. Re-verifies enrollment currency + `status == PUBLISHED` + window on every
  call. **403** before `scheduled_start`/after `scheduled_end` with a distinct reason
  code (`NOT_YET_OPEN` / `WINDOW_CLOSED`) — see §13.
- `PUT /api/v1/exams/attempts/{attemptId}/answers` — save/update answers mid-attempt.
  Re-verifies window on every call; **409** if the attempt is already `SUBMITTED`.
- `POST /api/v1/exams/attempts/{attemptId}/submit` — submit. Sets `submitted_at`,
  triggers `McqAutoMarkingService`, routes structured answers to the queue. **409** on
  an already-submitted attempt (idempotent-reject, not re-score).

### Marking queue

- `GET /api/v1/exams/{examId}/marking-queue` — list unmarked structured answers, scoped
  `(tenant_id, exam_id)` and further to the marker's authorized course set. Auth:
  owning Teacher or `DomainArea.EXAMS`/`VIEW` staff.
- `POST /api/v1/exams/answers/{answerId}/mark` — submit `manualScore`. Auth: owning
  Teacher (via the answer's exam→course chain) or `DomainArea.EXAMS`/`CREATE_EDIT`
  staff. `markedBy`/`markedAt` always server-derived.

### Results publishing / review

- `POST /api/v1/exams/{examId}/publish-results` — sets `results_published_at`. Auth:
  `requireLifecycleTransitionAccess`. **409** if `status != CLOSED`.
- `GET /api/v1/exams/attempts/{attemptId}/results` — student's own result + review.
  `hasRole('STUDENT')`, owner-only (attempt's `studentId` must equal caller — **404**
  otherwise, not 403, per §15). **404**/distinct "not yet published" response if
  `exam.results_published_at IS NULL`, even when the attempt itself is `SUBMITTED`.

**`ExamQuestionResponse`**: `{ id, courseId, questionType, body, options?:
[{ id, optionText }] }` — `isCorrect` is **never** included in any response a Student
or a pre-submission Teacher/staff read could reach; it is only ever read internally by
`McqAutoMarkingService`.

**`ExamAttemptResultResponse`**: `{ attemptId, examId, status, score, maxScore,
answers: [{ questionId, response, autoScore, manualScore }] }` — returned only once
`results_published_at IS NOT NULL`.

## 11. Frontend screens

Route-group convention matches existing modules exactly.

| # | Screen | Route | Access |
|---|---|---|---|
| 1 | Student Exam List | `app/(student)/student/exams/page.tsx` | Student, own enrollment only |
| 2 | Student Exam Taking | `app/(student)/student/exams/[examId]/take/page.tsx` | Student, own attempt only |
| 3 | Student Results & Review | `app/(student)/student/exams/[examId]/results/page.tsx` | Student, own results only |
| 4 | Teacher Question Bank | `app/(teacher)/teacher/exams/questions/page.tsx` | Teacher (own courses), Teacher Assistant (tenant-wide, §21 item 1) |
| 5 | Teacher Exam Scheduler | `app/(teacher)/teacher/exams/[examId]/schedule/page.tsx` | Teacher, own courses; TA may edit `DRAFT` but never reaches the schedule action (backend-verified) |
| 6 | Teacher Marking Queue | `app/(teacher)/teacher/exams/marking/page.tsx` | Teacher (own courses), Exam Manager/Tenant Admin (tenant-wide) |
| 7 | Teacher Results Publishing | `app/(teacher)/teacher/exams/[examId]/publish/page.tsx` | Teacher (own course) or Exam Manager/Tenant Admin — never Teacher Assistant |
| 8 | Tenant Admin Exam Oversight | `app/(tenant-admin)/tenant-admin/exams/page.tsx` | Tenant Admin, Exam Manager (full); Read-only Auditor (view-only) |

**Model Paper Library is explicitly excluded from this build** (§6) — the existing
`docs/ui-ux/screen-map.md` line 126 reference should be corrected to note Phase 3 (§19),
not implemented as screen #9 here.

New permission helpers in `frontend/src/lib/auth/permissions.ts` (mirrors
`canViewAttendanceReports`/`canMarkAttendanceStaff`'s existing pattern — UI convenience
only, never authorization): `canManageExamsStaff(role)` (Tenant Admin, Exam Manager),
`canScheduleOrPublishExam(role)` (Teacher only — explicitly excludes Teacher Assistant).

**States per screen** (via the existing shared `QueryStateBoundary`):
- Loading: `LoadingState`; skeleton table rows for screen #8.
- Empty (screens #1/#3 need the issue's explicit two-variant pair; screen #2 needs a
  **third**, distinct blocked-state family beyond the standard empty/error pair — per
  `.claude/rules/ui-ux.md` §3, never reuse the same generic copy across these):
  - Screen #1: "No exams scheduled yet" (zero-data) vs. "No published exams" (drafts/
    scheduled exams exist, nothing visible to this student) — issue's explicit AC.
  - Screen #2 (Exam Taking): "Not yet open" (before `scheduled_start`, with the next
    available time) / "Window closed" (after `scheduled_end`) / "Already submitted"
    (links to Results & Review) — three distinct sub-states, not one generic
    blocked-screen.
  - Screen #3 (Results & Review): "Your exam has been submitted — results will be
    published soon" (attempt complete, `results_published_at IS NULL`) — distinct from
    both the standard zero-data empty state and from "no published exams" on screen #1.
  - Screen #6 (Marking Queue): "Nothing to mark right now."
  - Screen #7 (Results Publishing): distinguish "no exams exist yet" from "exams exist
    but marking isn't complete yet" — two different next actions, not one generic
    empty state.
- Error: `ErrorState` with retry; a submission failure on screen #2 must be
  visually/announcement-distinct from a load failure.
- Permission-denied: a cross-tenant or cross-student exam/attempt id routes to the
  shared 404 page (never an in-page "permission denied" that would confirm the
  resource's existence) — consistent with §15's 404-not-403 rule for student-facing
  resources.

**Accessibility**:
- Exam submission (screen #2): `aria-busy="true"` on the submitting region during the
  round-trip; `aria-live="polite"`/`role="alert"` announces success/failure distinctly —
  one of the issue's explicit, non-negotiable requirements given the time-limited
  context.
- A sparse `aria-live="polite"` time-remaining announcement (e.g. every 5 minutes, then
  every minute in the final 5) — not every tick, to avoid being disruptive; mirrors the
  existing reduced-motion-respecting precedent for the pulsing `Live` status icon.
- Marking Queue scoring control (screen #6): a real, labeled, keyboard-operable widget —
  a labeled numeric input ("Score for question N, out of X points") with
  `aria-describedby` for the max-points constraint, not free text alone and not
  unlabeled icon buttons; mirrors Attendance's `role="radiogroup"` precedent for
  discrete-value controls where applicable.
- Question Bank authoring form (screen #4): full keyboard navigability — MCQ option
  reordering needs a keyboard equivalent to drag-and-drop (explicit "Move up/down"
  controls, not drag-only); MCQ answer groups use `fieldset`/`legend`; required fields
  via `aria-required`; validation errors tied via `aria-describedby` + `aria-invalid`.

**Responsive strategy** (per `.claude/rules/ui-ux.md`'s two archetypes, resolved
per-screen exactly as `MVP-016 Attendance.md` §11 resolved an analogous tension between
the general rule's literal text and a screen's actual density):
- Screens #1, #2, #3 — consumer-style, mobile-first (issue's explicit "Exam Taking is a
  consumer-style, mobile-first Student surface" framing).
- Screens #4, #5, #6, #7, #8 — admin-heavy: dense authoring forms / `DataTable` with
  the existing card-view mobile fallback, status columns via Status Chip.

**Status Chip gap (confirmed, flag for `docs/ui-ux/component-library-spec.md` update,
§19)**: the current §2.10 vocabulary has `Draft` and `Published` but **no `Scheduled` or
`Closed`** row, even though `docs/ui-ux/accessibility.md` and `11-exams.md` both already
assume all four exist. Two new rows are needed (`Scheduled` — e.g. `calendar-clock`
icon, distinct from `Pending`'s `clock`; `Closed` — e.g. `lock` icon, distinct from
`Expired`'s `calendar-x`).

## 12. Validation rules

- `question_type` must be `MCQ`/`STRUCTURED` — Bean Validation + DB `CHECK` (§8).
- An MCQ question must have at least one option with `isCorrect = true` — service-layer
  validation at question-create/exam-schedule time (not DB-enforceable as a single-row
  `CHECK`, §8).
- `courseId` is **never** accepted from the client for a question/exam's persisted
  `course_id` beyond the initial creation path parameter — always re-validated
  server-side against the resolved tenant/ownership before any write.
- `scheduled_end > scheduled_start`, `time_limit_minutes > 0` — Bean Validation + DB
  `CHECK`.
- `DRAFT → SCHEDULED` requires at least one linked question — service-layer
  precondition, checked before the transition, not merely recommended in the UI.
- **Publish-results requires `exam.status == CLOSED`** — service-layer precondition
  (§8's flagged, deliberately-not-a-DB-CHECK decision); rejected **409** otherwise, to
  prevent leaking answers/results to students who may still be able to attempt.
- Every submitted `studentId`/`markedBy`/`createdBy` is always
  `AuthenticatedPrincipalHolder.get().userId()` — never a client-supplied "on behalf of"
  field.
- No client-supplied `isCorrect`/`score`/`autoScore` field is ever accepted on any
  request DTO — the field must not exist as a bindable property, not merely be ignored
  if present (§15).
- Pagination params follow the existing platform convention (`page`/`size`/`sort`, size
  server-clamped to 100).

## 13. Error cases

| Case | Code | Status |
|---|---|---|
| `courseId`/`examId`/`questionId`/`attemptId` doesn't resolve in caller's tenant | `NOT_FOUND` | `404` |
| Teacher's course/exam resolves but isn't theirs | `FORBIDDEN` | `403` |
| Teacher Assistant attempts `schedule`/`publish-results` | `FORBIDDEN` | `403` |
| Staff caller lacks `DomainArea.EXAMS`/required action | `FORBIDDEN` | `403` |
| Read-only Auditor attempts any mutation | `FORBIDDEN` | `403` |
| Student attempts exam access before `scheduled_start` | `VALIDATION_ERROR` (`NOT_YET_OPEN` reason) | `403` |
| Student attempts exam access after `scheduled_end`/`CLOSED` | `VALIDATION_ERROR` (`WINDOW_CLOSED` reason) | `403` |
| Student's enrollment is not currently active | `FORBIDDEN` | `403` |
| Student accesses another same-tenant student's attempt/result | `NOT_FOUND` | `404` (never `403` — see §15) |
| Second submit on an already-`SUBMITTED` attempt | `CONFLICT` | `409` |
| `schedule` attempted with zero linked questions or invalid window | `VALIDATION_ERROR` | `400` |
| `publish-results` attempted while `status != CLOSED` | `CONFLICT` | `409` |
| MCQ question saved with zero correct options | `VALIDATION_ERROR` | `400` |
| Delete a question referenced by `exam_question_link`/`exam_answer` | `CONFLICT` | `409` |
| Malformed/non-UUID path variable | `VALIDATION_ERROR` | `400` |
| Any endpoint, cross-tenant target | `NOT_FOUND` | `404` |

## 14. Tenant-isolation rules

- Every new table's `tenant_id NOT NULL REFERENCES tenant(id)`, never nullable.
- Every cross-table reference (`course_id`, `question_id`, `exam_id`, `attempt_id`,
  `student_id`, `marked_by`) is a composite `(tenant_id, ...)` FK into the referenced
  tenant-owned table — never a bare FK on the child id alone.
- Every index leads with `tenant_id` (§8).
- All repositories extend `TenantAwareRepository` — tenant filtering is structural,
  never an ad hoc `WHERE` clause, and no method accepts a caller-supplied `tenant_id`.
- The marking-queue and exam-oversight (tenant-wide report) endpoints are named
  explicitly as bulk/reporting-endpoint isolation-bypass risks per
  `.claude/rules/tenancy.md` — both require their own dedicated cross-tenant test
  (§18), not just "the query has a tenant filter" as sufficient evidence.
- `exam_answer.exam_id` is a documented, accepted denormalization (not schema-enforced
  against `attempt_id`'s real parent exam, exactly like `attendance_record.course_id`'s
  V25 precedent) — `ExamAttemptService` must derive it server-side, never trust a
  client-supplied value; a negative test proving an attempt from exam A cannot be
  recorded under `exam_id` B is required (§18).
- The enrollment-currency check (`EnrollmentAccessApi`) is itself tenant-scoped through
  the same trusted `TenantContext` as every other method on that interface — no
  overload accepts a caller-supplied tenant id.

## 15. Security rules

(Full detail from the `security-reviewer` pass; summarized here.)

- **AuthN/authZ**: ownership-vs-staff-matrix split via `ExamAccessGuard`, with the
  two-method deviation (`requireAuthoringAccess` vs. `requireLifecycleTransitionAccess`)
  needed specifically because Teacher Assistant is allowed `CREATE_EDIT`-class access
  but never `APPROVE`-class access (§7 boxed note, §9). This is a real deviation from
  `CourseAccessGuard`/`AttendanceAccessGuard`'s single-method shape and must not be
  accidentally collapsed back into one method during implementation.
- **Enumeration/IDOR — student-facing resources use 404, not 403, for "not mine."** A
  student hitting `GET /attempts/{id}` or `GET /attempts/{id}/results` for another
  same-tenant student's attempt must get `404` — a `403` would confirm the id belongs
  to some other real student, leaking existence (§13). Cross-tenant is `404` everywhere,
  same-tenant-not-owned Teacher/staff is `403` (existing codebase convention — teachers
  already have legitimate visibility into their own tenant's course existence).
- **Time-window bypass is the highest-severity risk specific to this module.** The
  window/enrollment/status check must be recomputed from the current server clock on
  **every** attempt-related request (start, answer-save, submit) — never cached in a
  session/JWT claim, never trusted from client-held elapsed time. This is architecturally
  the same principle `.claude/rules/security.md`'s video-token section states for
  playback tokens ("a valid-looking token must still be rejected if the underlying
  access has since expired"), reused here even though that section's concrete
  signed-URL mechanism does not itself apply (no video/media in this module).
- **Score-trust is the second highest-severity risk.** No request DTO may carry an
  `isCorrect`/`score`/`autoScore` field as a bindable property at all — not merely
  ignored if present. Auto-marking is computed exclusively from stored
  `exam_question_option.is_correct` data at submission time. A manually-entered
  structured-answer score must be range-validated against the question's configured
  max points server-side, never trusted raw from the marker's request.
- **Concurrency/idempotency**: a double-submit or client retry-on-timeout must not
  create two attempts or re-score an already-`submitted_at`-set attempt — enforced by
  the partial unique index on `IN_PROGRESS` (§8) plus a service-layer guard rejecting
  any write once `submitted_at IS NOT NULL` (409, not silent no-op, to avoid a
  race-prone check-then-act).
- **Cross-module boundary**: `exam-management` may not inject
  `EnrollmentRepository`/`CourseRepository` or import their entities directly — course
  ownership and enrollment-currency checks go exclusively through `CourseLookupApi`/
  `EnrollmentAccessApi`'s `api` packages.
- **Non-applicable surfaces**: this module has no file upload, no protected video/media
  delivery, and no device-registration/session surface at MVP scope (no image/diagram
  attachments on questions are named in the issue) — the Device/Video/Upload sections of
  `.claude/rules/security.md` are confirmed N/A. If question-image attachments are added
  later, that sub-feature would need the standard MIME/size/ownership validation from
  the Upload section — flagged for that future scope, not built now.
- **No multi-tenancy or auth-architecture change**: this design consumes the existing
  `AuthenticatedPrincipalHolder`/`PermissionCheckService`/`TenantAwareRepository`
  pattern throughout — no new tenant-resolution mechanism, no ADR trigger.
- **Teacher Assistant boundary is PROVISIONAL, unratified** (`user-roles-and-permissions.md`
  §3) — flag explicitly in the implementation PR description so a reviewer doesn't
  mistake the implemented DRAFT-ceiling gate for an already-approved business rule.

## 16. Audit requirements

**Undecided — flagged, not resolved, per the issue's own explicit instruction.**
`functional-requirements.md` FR-EX-2 calls exam-result publication "a confirmable,
audit-considered action," but it is **not** on `.claude/rules/security.md`'s canonical
mandatory-audit-action list (price changes, payment approvals/rejections, device
resets, access/expiry extensions, reactivation approvals, material/course content
deletions, settlement amount changes, impersonation). Unlike `MVP-016 Attendance.md`
§16 (where the security-reviewer pass concluded audit logging was clearly not required
for marking), this module's own security-reviewer pass read publication as fitting the
*spirit* of that list closely enough (one-directional state transition,
multi-student blast radius) that silently omitting an audit entry should not be the
default assumption either way (§21 item 8).

**This plan's recommendation**: do not build the audit-log write path for MVP-017's
first PR pending explicit product sign-off, but design `ResultsPublishingService` to
raise `ExamResultPublishedEvent` (§7, §9) regardless — the event already exists for
`notification-management`'s future consumption, so `audit-log-management` can
additively subscribe to the same event later with zero schema change if the decision
resolves to "yes." This keeps the door open without inventing the audit-write path
speculatively.

## 17. Payment impact

**None.** This module reads no payment/order/ledger state and writes nothing to any
payment-adjacent table. Its only cross-domain read dependencies
(`CourseLookupApi.getTeacherId`/`getTeacherIdsByCourseId`,
`EnrollmentAccessApi.resolveAccessState`/`listCurrentlyEnrolledStudentIds`) are pure
course-ownership and enrollment-currency checks, not payment checks — enrollment
activation itself is unaffected, unchanged, and not re-implemented or re-derived here.
No `.claude/rules/payments.md` rule is implicated. `payment-ledger-specialist` was
correctly not invoked for this plan.

## 18. Tests

**Backend JUnit (service-layer)**:
- `QuestionBankServiceTest` — tenant/course-derivation, Teacher-ownership rejection,
  Teacher Assistant tenant-wide-authoring allowance, staff `CREATE_EDIT` regardless of
  ownership, Read-only Auditor denied, MCQ at-least-one-correct-option validation,
  structured questions never carry an auto-mark key.
- `ExamSchedulingServiceTest` — tenant/course-derivation, Teacher Assistant forced to
  `DRAFT` and blocked from `schedule`/`publish-results` (throws, not silently ignores),
  non-owning Teacher rejected, `schedule` requires ≥1 question + valid window,
  rescheduling a live exam with existing attempts is blocked/requires an explicit path.
- `ExamAccessCheckServiceTest` (the server-side window/enrollment gate, kept separate
  from attempt-service tests since it's the specific control the issue calls out as
  "not UI-hidden") — rejects before `scheduled_start` and after `scheduled_end` using an
  injected `Clock` (never client-supplied time), allows strictly inside the window,
  rejects a non-currently-enrolled or expired-enrollment student, re-evaluated fresh on
  every call.
- `ExamAttemptServiceTest` — server-derived `student_id`/`tenant_id`/`course_id`/
  `exam_id`, delegates to the access-check gate, blocks a second concurrent
  `IN_PROGRESS` attempt, submit is idempotent (rejects a second submit).
- `McqAutoMarkingServiceTest` — deterministic score across repeated invocations on the
  same stored response, structured answers never reach the auto-mark path, mixed
  MCQ+structured attempts score only the MCQ portion, unanswered MCQ items score zero.
- `MarkingQueueServiceTest` — structured-only enqueue and never-auto-scored, queue reads
  scoped to `(tenant_id, exam_id)` and further to the marker's owned courses,
  non-owning Teacher rejected, staff marks regardless of ownership, marker
  identity/timestamp always server-derived.
- `ResultsPublishingServiceTest` — results invisible pre-publish even for a completed
  attempt, publish requires `status == CLOSED`, non-owning Teacher/Teacher Assistant
  cannot publish.

**Backend Testcontainers/integration** — `QuestionBankIntegrationTest`,
`ExamSchedulingIntegrationTest`, `ExamAttemptIntegrationTest`,
`MarkingQueueIntegrationTest`, `ResultsPublishingIntegrationTest`,
`ExamCrossTenantIntegrationTest` (dedicated file, per this codebase's per-domain
convention), plus `QuestionRepositoryTenantGuardTest`/
`ExamAttemptRepositoryTenantGuardTest` (mirrors `AttendanceRecordRepositoryTenantGuardTest`
— proves the `TenantAwareRepository` base itself filters, not just service-layer
discipline):
- Teacher authors questions/exam for own course → persists correctly; Teacher
  Assistant's exam stays `DRAFT`, publish/schedule attempt rejected, zero mutation.
- Student starts an attempt inside the window → persists; before/after the window →
  rejected, zero attempt row; expired-enrollment student → rejected the same way.
- MCQ auto-score is stable across a re-triggered recompute (persisted-value
  comparison); structured submissions enter the queue with `auto_score` null.
- A student accessing another same-tenant student's attempt by id → `404`.
- Multi-teacher fixture: Teacher A's marking queue never contains Teacher B's course's
  entries; non-owning Teacher marking attempt → `403`, zero score written.
- Results invisible at the fetch endpoint for a completed-but-unpublished attempt;
  visible immediately after an authorized publish; non-owning
  Teacher/Teacher-Assistant publish attempt → `403`, exam stays unpublished.
- **Cross-tenant suite**: tenant B addressing tenant A's real `examId`/`questionId`/
  `attemptId` on every endpoint (authoring, scheduling, attempt-start, marking-queue
  read/mark, results-read) → `404`, zero mutation; colliding-name fixture (same exam
  title/course in both tenants) never cross-leaks in a tenant-wide staff report.

**Playwright E2E**:
- Teacher authoring form (Question Bank + Scheduler) is fully keyboard-navigable;
  Teacher Assistant's publish/schedule control is either absent or, if rendered, the
  intercepted API response is asserted `403`.
- Student exam-taking at a narrow viewport (375×667): full attempt flow;
  `aria-busy`/live-region asserted on submit via the accessibility tree, not just
  visual text; direct-URL access before/after the window intercepted and asserted
  `403` with the corresponding distinct blocked UI state.
- Marking Queue scoring control is fully keyboard-operable end to end.
- Both Results & Review empty-state variants (submitted-unpublished vs. published)
  render distinctly; a student never sees another student's data via id substitution.
- Both Exam List empty-state variants ("no exams scheduled" vs. "no published exams")
  render distinctly.

**Explicitly deferred/not needed**: anti-cheating tests, negative-marking tests,
question-pool/randomization tests, rank-list tests, configurable-attempt-limit tests
beyond the single-concurrent-attempt guard (all Phase 2/3, not built); no
audit-log-write assertion (§16 undecided).

## 19. Documentation changes

- `docs/architecture/` — new short section describing the question-bank table, the
  exam-schedule data model + the lazy-computed `SCHEDULED → PUBLISHED → CLOSED`
  transition mechanism (§7's boxed note), the exam-attempt/auto-marking data model, the
  marking-queue data model, and the results-publish state transition.
- `docs/api/exam-management.md` — new file, written via the `review-api-contract`
  skill before/at the start of frontend work, covering every endpoint in §10.
- `docs/ui-ux/screen-map.md` — add the 8 screens from §11; correct the existing "Model
  Paper Library" line to explicitly note Phase 3/not-in-this-build (§6).
- `docs/ui-ux/component-library-spec.md` §2.10 — add `Scheduled`/`Closed` Status Chip
  rows (§11).
- `docs/requirements/specifications/11-exams.md` — update the "Open decisions" section
  to record the resolutions/clarifications made in this planning session (marking-queue
  staff-scoping, Teacher Assistant course-scoping approach) and carry forward the three
  genuinely unresolved items (audit logging, dropped-connection UX, Model Paper Library
  ownership) plus the new analytics-scope tension (§21 item 5).
- `docs/requirements/open-decisions.md` — add a new dated entry under a new "§8
  Exams (MVP-017)" heading recording this session's confirmed decisions and residual
  open items, per this log's established per-module convention.

## 20. Implementation order

Per root `CLAUDE.md`'s standing development workflow (plan → backend → backend tests →
frontend → frontend/E2E tests → security/tenant/integration review → docs → one logical
commit), sequenced concretely for this module:

1. **Backend**: `V26__create_exam_management_schema.sql` → domain entities
   (`ExamQuestion`, `ExamQuestionOption`, `Exam`, `ExamQuestionLink`, `ExamAttempt`,
   `ExamAnswer`) → repositories → `ExamAccessGuard` → `QuestionBankService` →
   `ExamSchedulingService` → `ExamAttemptService`/`ExamAccessCheckService` →
   `McqAutoMarkingService` → `MarkingQueueService` → `ResultsPublishingService` →
   controllers/DTOs. No cross-module `api` changes are needed this time (§9), so this
   module can proceed without a preceding dependency-unlocking PR (unlike Attendance's
   `enrollment-management` extension step).
2. **Backend tests**: JUnit + Testcontainers + the dedicated cross-tenant suite (§18) —
   run and green before frontend starts.
3. **API contract review** (`review-api-contract` skill) against the shipped
   controllers — produce `docs/api/exam-management.md` before frontend work begins.
4. **Frontend**: shared Status Chip additions (`Scheduled`/`Closed`) → Teacher Question
   Bank (#4) → Teacher Exam Scheduler (#5) → Student Exam List (#1) → Student Exam
   Taking (#2) → Student Results & Review (#3) → Teacher Marking Queue (#6) → Teacher
   Results Publishing (#7) → Tenant Admin Exam Oversight (#8).
5. **Frontend/E2E tests** (§18).
6. **Security, tenant-isolation, and integration reviews** (the corresponding skills) —
   re-verify the time-window/score-trust guards and cross-tenant suite specifically.
7. **Documentation** (§19).
8. **One logical commit** per root `CLAUDE.md`'s workflow (or the smallest reasonable
   split consistent with `.claude/rules/git-workflow.md`).

## 21. Risks and unresolved decisions

**Design questions resolved this planning session by analogy to shipped precedent (not
new business rules, but flagged for explicit confirmation since the issue itself is
silent on the mechanism):**

1. **Teacher Assistant question/exam authoring is tenant-wide, not course-scoped** —
   no TA-to-course assignment table exists anywhere in this codebase (`MVP-016
   Attendance.md` hit the identical gap and fully excluded TA; this plan instead builds
   the narrowest interpretation that satisfies the issue's explicit TA acceptance
   criteria without inventing a new data model). Confirm before implementation whether
   this tenant-wide-but-DRAFT-capped scope is acceptable, or whether a TA-to-course
   assignment table should be built first (bigger, cross-module change, likely too large
   for this module's own PR).
2. **Marking-queue "assigned courses" scoping applies only to Teacher, not to Exam
   Manager/Tenant Admin** (who retain their flat tenant-wide `DomainArea.EXAMS` grant,
   with no course-ownership dimension) — disambiguates the issue's literal wording
   exactly as `MVP-016 Attendance.md` §2 disambiguated the analogous case for
   Attendance Operator vs. Teacher.
3. **Sequential re-attempts are not blocked at MVP** (only simultaneous
   duplicate/concurrent `IN_PROGRESS` attempts are blocked, via a partial unique index) —
   attempt-count limiting is Phase 2; flagged so a reviewer doesn't mistake the absence
   of a hard one-attempt cap for an oversight.
4. **Recomputing an MCQ score after a question's correct-answer flags are edited
   post-submission is unresolved** — the issue requires scores to be "consistent on
   re-computation" but doesn't say against what (the answer key's value at submission
   time, or its current value). This should be resolved the same way payment/ledger
   historical-figure immutability was resolved (`.claude/rules/payments.md` §5's "never
   recompute a historical settlement from current rates" pattern) rather than assumed —
   needs explicit product/solution-architect sign-off before `McqAutoMarkingService` is
   implemented.
5. **Exam analytics (FR-EX-3) scope tension** — tagged MVP in
   `functional-requirements.md`, absent entirely from issue #17. Treated as out of
   scope for this module's actual delivery (§6), but the FR-EX-3 phase tag itself
   should be revisited in a follow-up documentation pass (either soften to Phase 2, or
   confirm a separate follow-up module/PR is intended) — not silently resolved by this
   plan.
6. **Exam status lifecycle transition triggers** (§7's boxed note) — this plan's
   `DRAFT` (manual) → `SCHEDULED` (manual, `APPROVE`-gated) → `PUBLISHED`/`CLOSED`
   (system-computed, lazy-write, mirroring `EnrollmentAccessApi`'s existing pattern)
   design is a recommendation, not a confirmed fact — the issue's own text does not
   spell out what triggers each of the four states. Confirm before implementation,
   since a different model (e.g., an explicit manual "go live" action distinct from
   `schedule`) would change the API contract in §10.

**Explicitly named open business decisions (issue's own wording — not resolved by this
plan, no default assumed):**

7. Exact recovery UX for a dropped connection mid-exam-attempt — undocumented anywhere.
   No auto-submit-on-timeout, resume-with-restored-time, or grace-period policy is
   invented; the current design only guarantees the window/enrollment/submitted-state
   check re-runs correctly on the student's *next* request, whatever that turns out to
   be.
8. Whether exam-result publication requires a mandatory audit-log entry — this
   module's security-reviewer pass read the action as fitting the *spirit* of
   `.claude/rules/security.md`'s canonical list closely (one-directional,
   multi-student blast radius) even though it isn't named on the list; §16 defers the
   actual audit-write decision but designs `ExamResultPublishedEvent` so the decision
   can be honored additively later either way.
9. Model Paper Library ownership between Teacher and Tenant Admin — Phase 3, does not
   block this module, not resolved here.

**Confirmed non-decisions (explicitly not in scope, no sign-off needed):**
- No ADR is required for this module — it fits the already-confirmed domain list,
  requires no new datastore, no microservice, and no multi-tenancy/auth-architecture
  change (§9).
- No payment/ledger impact (§17).
- No new cross-module `api` method is required on `CourseLookupApi` or
  `EnrollmentAccessApi` — both already expose everything this module needs (§9), a
  simpler dependency footprint than `attendance-management` needed.

## 22. Implementation addendum (post-build, recorded during backend review)

This section records deviations from §7/§9/§21 discovered during implementation and the
subsequent 4-agent backend review (security-reviewer, database-architect,
solution-architect, qa-test-engineer). It supersedes the corresponding statements above
for "what was actually approved and shipped" purposes; §7/§9/§21 are left unedited as the
historical planning record.

1. **§21's "Confirmed non-decisions" claim that no new `EnrollmentAccessApi` method is
   needed is superseded — one was added, and is approved.** Implementation found that
   `GET /exams/my/upcoming` (§10) requires a reverse lookup ("which courses is this
   student currently enrolled in") that `EnrollmentAccessApi` did not expose in either
   direction the plan anticipated. Rather than reach into `enrollmentmanagement`
   internals or invent a workaround, the implementing agent stopped and reported the gap;
   the user explicitly approved adding `EnrollmentAccessApi.listCurrentlyEnrolledCourseIds(UUID
   studentId)` (mirroring `listCurrentlyEnrolledStudentIds(UUID courseId)`'s existing
   "currently enrolled" currency semantics, reverse direction) as the fix. This is the
   approval record for that controlled-API-contract change — no separate ADR was deemed
   necessary since it is a minimal, additive, read-only method with no existing caller
   affected.
2. **§7/§18's `ExamAccessCheckService` was not built as a separate class.** The
   window/enrollment/lifecycle-status access-check logic §7 and §18 describe as a
   dedicated, separately-testable service was instead split across
   `ExamAttemptService` (enrollment-currency + window checks, private methods, tested via
   `ExamAttemptServiceTest`) and `ExamLifecycleService` (the lazy
   `SCHEDULED → PUBLISHED → CLOSED` status advancement, tested via
   `ExamLifecycleServiceTest`). Functionally equivalent and fully covered by tests; no
   dedicated `ExamAccessCheckServiceTest` file exists under that name. Recorded here so a
   future reader doesn't need to rediscover this by diffing the plan against the code.
3. **§7's repository/support list is incomplete.** `ExamQuestionOptionRepository` and
   `ExamQuestionLinkRepository` were also needed (one tenant-scoped repository per JPA
   entity, and `ExamQuestionOption`/`ExamQuestionLink` are each their own entity) — a
   gap in the plan's own enumeration, not a deviation from intent. A new `config` package
   (`ExamClockConfig`) was added to supply the injected `Clock` bean §9 requires (no
   `Clock` bean existed anywhere else in the codebase prior to this module).
4. **A grading-integrity gap found by database-architect review was fixed, with a test
   added.** `QuestionBankService.updateQuestion` originally allowed replacing an MCQ
   question's options (delete-all-recreate-with-new-ids) with no guard against the
   question already being linked to a non-`DRAFT` exam or already answered — since
   `exam_answer.response` stores selected option ids as free text with no FK (by design),
   this could silently invalidate a student's previously-correct answer after the fact
   with no error raised. Fixed: `updateQuestion` now rejects (409,
   `QuestionInUseException` or equivalent) an options-replacing edit once the question is
   linked to any non-`DRAFT` exam or has any `exam_answer` row, mirroring
   `ExamSchedulingService.updateDraftExam`'s existing DRAFT-only gate pattern. Covered by
   a new negative test in `QuestionBankServiceTest`/`QuestionBankIntegrationTest`.
5. **Marking-queue read is now paginated**, matching this module's other list endpoints
   and §12's stated pagination convention (`page`/`size`/`sort`, size clamped to 100) —
   originally shipped unpaginated (an omission, not an intentional deviation).
6. **Bulk delete-by-parent-id repository methods replace load-then-delete-each** for
   `exam_question_option` (on question option replacement) and `exam_question_link` (on
   exam edit), for the same reason V-series migrations elsewhere in this codebase prefer
   set-based operations at scale — a Low-severity finding from database-architect review,
   fixed opportunistically alongside item 4 since both touch `QuestionBankService`.
