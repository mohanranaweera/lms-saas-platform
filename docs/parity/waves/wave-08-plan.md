# Wave 8 Plan — Attendance Parity (ClassSession-scoped)

Status: **DONE (uncommitted — awaiting human review).** Backend (V56 + class-session
attendance + summaries), frontend (Course → Class session marking, summaries, session labels),
tests, migration/tenant-isolation review (one defect found and fixed) and documentation are
complete — see Sections 12–16 for the completion record.

Scope per the Wave 8 brief: implement the master-instruction §22 attendance workflow
(Teacher → select course/session → load authorized roster → mark Present/Absent/Late →
save) with `ClassSession` as the teaching-session context, using the conceptual structure
`ClassSession → AttendanceSheet → AttendanceRecord`, while preserving every historical
(lesson-scoped) attendance row and orphaning none.

Parity rows: PAR-10-01, PAR-10-02, PAR-10-03, PAR-10-04 (roadmap row for Wave 8), PAR-19-05
(attendance-sync side).

---

## 1. Phase A — Analysis (findings)

### 1.1 What already exists

| Area | Finding |
|---|---|
| Schema | `attendance_record` (V25): one row per `(tenant_id, session_id, student_id)`, where **`session_id` is a composite FK into `course_lesson`**, not a class session. `course_id` is a service-derived denormalization. Status CHECK `PRESENT/ABSENT/LATE`. Mutable by design: a re-mark is an in-place update (product-owner confirmed, MVP-016 §7). No `ON DELETE CASCADE`. |
| Backend | `attendancemanagement`: `AttendanceMarkingService` (batch-partial marking, atomic native `INSERT … ON CONFLICT (tenant_id, session_id, student_id)` upsert), `AttendanceReportService` (roster, `/my`, role-dispatched `/reports`, staff `/students/{id}/report`), `AttendanceAccessGuard` (Teacher = owning teacher of the lesson's course; otherwise `ATTENDANCE` permission). Repository guards explicit-`tenantId` queries against `TenantContextHolder`. |
| ClassSession | `class_session` (V49, Wave 4 "Path A"): independent table, `course_id`/`teacher_id`/optional `lesson_id`, lifecycle `SCHEDULED → LIVE → COMPLETED`, `SCHEDULED/LIVE → CANCELLED`. Composite unique `(tenant_id, id)`. `LiveClassAccessGuard` re-verifies Teacher ownership against the course's *live* teacher. **No `api` read surface** exists for other modules to look up a session. |
| Zoom sync | `ClassSessionAttendanceSyncedEvent(tenantId, sessionId, courseId, syncedAt)` is published on the `session.attendance_synced` webhook. It carries **no participant data**. No consumer exists. |
| RBAC | `DomainArea.ATTENDANCE`: Tenant Admin `VIEW/CREATE_EDIT`, Attendance Operator `VIEW/CREATE_EDIT`, Read-only Auditor `VIEW`; everyone else none. Teacher is ownership-based (not matrix-based). Teacher Assistant is deliberately denied (MVP-016 §2). |
| Frontend | `MarkAttendancePanel` uses a Course → Module → Lesson ("Session") cascade; roster rows show a short id only (no names). Reports: `student/attendance`, `teacher/attendance/reports`, `tenant-admin/attendance/reports`; plus Student/Teacher detail tabs. `lib/api/class-sessions.ts#useClassSessions({courseId})` already lists a course's sessions (role-scoped server-side). E2E is mock-backed (`e2e/attendance.spec.ts`). |
| Known limitation | PAR-10-01: a weekly class reusing one lesson overwrites the previous week's marks — the lesson is not an occurrence. This is the core defect the ClassSession move fixes. |

### 1.2 Historical data & compatibility requirements

1. Every existing `attendance_record` row references a `course_lesson` and must stay
   valid, visible in every report it appears in today, and attributable to its lesson.
2. **Path B from `migration-strategy.md` §3 is rejected** (repointing `session_id` to
   `class_session` and backfilling one fake `class_session` per lesson). It would invent
   scheduling facts (start/end/status) that never existed and move the recurring-lesson
   ambiguity into `class_session`.
3. Chosen approach (**"Path A+ / sheet layer"**): introduce `attendance_sheet` as the
   single parent of every record. A sheet has a `source`:
   - `CLASS_SESSION` — exactly one per `class_session` (the new workflow);
   - `LEGACY_LESSON` — exactly one per legacy `course_lesson` that already has attendance
     (backfilled; the historical data stays lesson-scoped, honestly labelled).
   Every existing record gets a `sheet_id` pointing at its legacy sheet, so nothing is
   orphaned and no scheduling fact is fabricated.
4. `attendance_record.session_id` keeps its name and FK (migration history is append-only;
   renaming adds churn with no isolation benefit). It becomes nullable, because a
   class-session record has no lesson, and is documented as the **legacy lesson id**
   (`AttendanceRecord#lessonId` in Java).
5. The legacy lesson endpoints (`/attendance/sessions/{lessonId}/…`) stay working for API
   compatibility, because approved API contracts are change-controlled. They now also
   attach rows to the lesson's `LEGACY_LESSON` sheet. They are marked **deprecated** in
   the API docs, and the frontend stops using them. Removing them or freezing legacy
   writes needs product-owner sign-off (see §10).

---

## 2. Target behavior

- **Mark (Teacher/Staff):** select Course → select Class Session (title + date/time +
  status) → roster loads (currently-enrolled students + any previously marked students no
  longer enrolled, shown read-only) → set Present/Absent/Late → Save (batch-partial
  outcome per row, unchanged contract).
- **Session lifecycle gate (server-side):**
  - `LIVE`, `COMPLETED` → marking allowed;
  - `SCHEDULED` → allowed only once `scheduled_start <= now` (a teacher who never pressed
    "Start" can still take attendance for a class that has happened);
  - `SCHEDULED` with a future start → `409`;
  - `CANCELLED` → `409`.
  The roster read works for any status and reports `markingOpen` + `markingClosedReason`.
- **Sheet creation:** lazily, on the first successful mark for a session (a read never
  creates a sheet). Race-safe via `INSERT … ON CONFLICT DO NOTHING` on the partial unique
  index, then a re-read.
- **Access:**
  - Teacher: only sessions whose course's *live* teacher is the caller (mirrors
    `LiveClassAccessGuard`; a course reassigned after scheduling moves access with it).
  - Staff: `ATTENDANCE` `VIEW` (roster) or `CREATE_EDIT` (mark).
  - Student: `403` on every roster/mark endpoint, returned **before** any session lookup,
    so existence is never revealed. Own data via `/my` only.
  - Cross-tenant session id: `404`.
- **Reports** (existing endpoints, extended): optional `classSessionId` filter on `/my`,
  `/reports` and `/students/{id}/report`; each record now also carries `sheetId`,
  `source`, `classSessionId` and `classSessionTitle` (nullable), with `sessionId` kept
  as the legacy lesson id (nullable).
- **Summaries / percentages** (new, aggregated in SQL — never loading all rows):
  - `GET /attendance/summary?courseId=…&from&to` (Teacher: own course; Staff:
    `ATTENDANCE/VIEW`): per-student counts `present/late/absent/total` + `attendanceRate`.
  - `GET /attendance/my/summary?from&to` (Student): the caller's per-course counts +
    rate.
  - `attendanceRate = (present + late) / total marked`, returned alongside the raw counts
    so the UI never has to recompute it. This formula is a judgment call (§10).

## 3. Database impact (additive; next version **V56**)

`V56__create_attendance_sheet_and_backfill_legacy.sql`:

1. `ALTER TABLE class_session ADD CONSTRAINT uq_class_session_tenant_id_course UNIQUE
   (tenant_id, id, course_id)`. This is a superset of the existing unique, so it always
   holds. It enables a composite FK that makes "a sheet's course = its session's course"
   DB-enforced.
2. `CREATE TABLE attendance_sheet` (`id`, `tenant_id NOT NULL → tenant`, `course_id NOT
   NULL`, `source`, `class_session_id NULL`, `lesson_id NULL`, auditable columns):
   - `uq_attendance_sheet_tenant_id (tenant_id, id)`,
     `uq_attendance_sheet_tenant_id_course (tenant_id, id, course_id)`;
   - FK `(tenant_id, course_id) → course`;
   - FK `(tenant_id, class_session_id, course_id) → class_session (tenant_id, id,
     course_id)`;
   - FK `(tenant_id, lesson_id) → course_lesson`;
   - `ck_attendance_sheet_source` (`CLASS_SESSION`, `LEGACY_LESSON`) +
     `ck_attendance_sheet_source_shape` (a CLASS_SESSION sheet has a session and no
     lesson; a LEGACY_LESSON sheet has a lesson and no session);
   - partial unique `(tenant_id, class_session_id) WHERE class_session_id IS NOT NULL`;
   - partial unique `(tenant_id, lesson_id) WHERE source = 'LEGACY_LESSON'`;
   - index `(tenant_id, course_id, created_at DESC)`.
3. **Pre-check:** if any legacy row's stored `course_id` disagrees with its lesson's real
   course (via `course_lesson → course_module`), the migration raises an exception with a
   clear message instead of silently picking one. The service always derived it
   server-side, so none are expected.
4. **Backfill:** one `LEGACY_LESSON` sheet per distinct `(tenant_id, session_id)` in
   `attendance_record`. The id is deterministic (`md5(tenant||lesson||'legacy-sheet')::uuid`)
   because V1's convention forbids DB-side random-UUID functions and there is no Java
   migration precedent. `created_at` = earliest `created_at` of its records;
   `created_by` = that record's `created_by`.
5. `ALTER TABLE attendance_record ADD COLUMN sheet_id UUID`, backfilled from (4), then
   `SET NOT NULL`. `session_id DROP NOT NULL`. Add FK `(tenant_id, sheet_id, course_id) →
   attendance_sheet (tenant_id, id, course_id)` (record course = sheet course,
   DB-enforced), `uq_attendance_record_tenant_sheet_student (tenant_id, sheet_id,
   student_id)` (the duplicate gate for the new path). The old
   `uq_attendance_record_tenant_session_student` stays (NULLs don't collide).
6. `COMMENT ON COLUMN attendance_record.session_id` documenting its legacy-lesson meaning.

No row is updated except to set `sheet_id`; no row is deleted. V1–V55 are unedited.

## 4. API impact (all `/api/v1`, tenant from trusted context)

New (`AttendanceController`):

| Method | Path | Auth |
|---|---|---|
| GET | `/attendance/class-sessions/{classSessionId}/roster` | Teacher-owner or `ATTENDANCE/VIEW`; Student 403 |
| POST | `/attendance/class-sessions/{classSessionId}/records` | Teacher-owner or `ATTENDANCE/CREATE_EDIT`; lifecycle gate 409 |
| GET | `/attendance/summary?courseId&from&to` | Teacher-owner or `ATTENDANCE/VIEW`; `courseId` required |
| GET | `/attendance/my/summary?from&to` | Student only |

Extended (additive):
- `classSessionId` filter on `/attendance/my`, `/attendance/reports` and
  `/attendance/students/{id}/report`;
- `AttendanceRecordResponse` gains `sheetId`, `source`, `classSessionId`,
  `classSessionTitle`; `sessionId` becomes nullable.

Deprecated (still functional): `/attendance/sessions/{lessonId}/roster|records`.

Cross-module: new `liveclassmanagement.api.ClassSessionLookupApi` (tenant-scoped
`findSession`, batched `getSessionSummaries`) + `ClassSessionSummary` record. Attendance
never imports `liveclassmanagement.domain`/`repository`.

## 5. Frontend impact

- `lib/api/attendance.ts`: class-session roster/mark hooks, summary hooks, new fields,
  `classSessionId` filter.
- `MarkAttendancePanel`: Course → Class Session selector (via `useClassSessions`), a
  session header with status + marking-closed banner, student names, and "no longer
  enrolled" rows shown read-only. The same component serves the Teacher and Staff routes.
- Reports (Teacher/Admin): an attendance summary table (per-student counts + rate) when a
  course is selected; a session column in the record table.
- Student My Attendance: per-course summary cards + a session column.
- `e2e/attendance.spec.ts`: rewritten for the class-session cascade + new states.

## 6. Security impact

- Student is denied before lookup (no existence oracle). A cross-tenant id returns 404
  (tenant-scoped repository / API).
- Teacher ownership is re-verified against the course's live teacher on every call.
- `courseId` is never accepted from the client on a write; it is derived from the
  session.
- The lifecycle gate is enforced server-side only; the UI just mirrors
  `markingOpen`.
- No new audit-log obligation: attendance marking is not in `security.md`'s mandatory
  audit list. Unchanged from MVP-016.

## 7. Tenant isolation impact

- `attendance_sheet` is tenant-owned: `tenant_id NOT NULL` + FK, tenant-leading indexes,
  every FK composite with `tenant_id`, repository `TenantAwareRepository`.
- Explicit-`tenantId` native queries (sheet insert, record upsert, summary aggregation)
  are guarded by the same `TenantContextHolder` equality assertion as the existing
  methods.
- Cross-tenant negative tests are required for roster, mark, summary and the
  `classSessionId` report filter.

## 8. Test plan (Phase D)

| Case | Test |
|---|---|
| Unauthorized roster | Student → 403 (both existing and random id); TA/Finance Staff → 403; unauthenticated → 401 |
| Wrong course | A mark cannot land under another course (course derived from the session; DB composite FK rejects a mismatched sheet/record via direct JDBC) |
| Wrong teacher | A teacher of another course → 403 on roster, mark, summary; a course reassigned to another teacher moves access |
| Cross-tenant | Tenant B's session id → 404 roster/mark; summary for B's course → 404; `classSessionId` filter to B's session → empty/404 |
| Duplicates | Re-mark updates in place (one row); concurrent first marks → one sheet + one row per student; duplicate studentIds in one batch → one row |
| Session lifecycle | CANCELLED → 409; future SCHEDULED → 409; past-start SCHEDULED, LIVE, COMPLETED → 200; roster reports `markingOpen` |
| Student privacy | `/my` and `/my/summary` return only the caller's rows; the roster never lists another tenant's students; a student cannot filter `/my` into another student's data |
| Historical migration | Flyway to V55 in an isolated schema → seed legacy rows → migrate to V56 → every row has a LEGACY_LESSON sheet, row count/status unchanged, the legacy endpoint still marks, and the mismatch pre-check raises |
| Roster | Enrolled students + previously marked unenrolled students (`currentlyEnrolled=false`); marking the latter is rejected per-row |
| Summary | Counts/rate correct across PRESENT/LATE/ABSENT; teacher scope; date filter |

## 9. Migration / rollout risk

- V56 is data-touching (backfill of `sheet_id`). It is bounded: one INSERT…SELECT plus
  one UPDATE…FROM over `attendance_record`, both tenant-leading-index backed. The
  pre-check fails the migration loudly rather than corrupting data.
- Rollback: forward-only (new migration). The legacy endpoints keep working throughout.

## 10. Judgment calls (flagged for product-owner sign-off)

1. **Sheet layer instead of Path B** (§1.2). Legacy data stays lesson-scoped under honest
   `LEGACY_LESSON` sheets; no fabricated class sessions.
2. **Legacy lesson endpoints kept + deprecated**, not removed or frozen. Freezing legacy
   writes would fully retire the recurring-lesson ambiguity, but it changes an approved
   API contract.
3. **Lifecycle gate:** SCHEDULED-but-started is markable; future SCHEDULED and CANCELLED
   are not. There is no finalize/lock step (not in the verified requirements).
4. **`attendanceRate = (present + late) / total`**. LATE counts as attended. Raw counts
   are always returned too.
5. **Re-mark stays in-place with no change history** (unchanged MVP-016 decision).
6. **Teacher Assistant stays denied** (unchanged MVP-016 decision), even though
   `LiveClassAccessGuard` provisionally treats TA like Teacher.

## 11. Deferred (explicit)

- **Zoom-synced attendance consumer (PAR-10-03).** The published event carries no
  participant list, and no provider participant-report adapter exists. A consumer would
  have nothing to write. This needs an integration-adapter design (participant → student
  identity matching) first. It stays deferred, now with a concrete blocker recorded.
- `EXCUSED` status (not verified; excluded by MVP-016 §11).
- Course-scoped Attendance tab in the Course workspace (PAR-05-06 remains unscoped).
- **Absent-student alerts (PAR-10-04).** The roadmap lists this row under Wave 8, but the
  Wave 8 brief does not include it. The spec (`10-attendance.md` §4.6) ties it to the Zoom sync
  job (Phase 2), and its trigger and recipient rules are unspecified. Not built; recorded as a
  deferral in the matrix and in `open-decisions.md` §20a.
- Retroactive marking of a student who was enrolled at the time of a past session but is no
  longer enrolled (and was never marked): still rejected. `listCurrentlyEnrolledStudentIds`
  is live, not as-of-date (pre-existing MVP-016 gap, narrowed but not closed).

---

## 12. Phase B completion (backend)

- **V56** `V56__create_attendance_sheet_and_backfill_legacy.sql`: `attendance_sheet`
  (CLASS_SESSION / LEGACY_LESSON), course-consistent composite FKs
  (`class_session (tenant_id, id, course_id)` ← sheet ← record), a pre-check that aborts on
  inconsistent legacy course ids, a deterministic legacy-sheet backfill,
  `attendance_record.sheet_id NOT NULL`, `session_id` made nullable (legacy lesson id), and
  `uq_attendance_record_tenant_sheet_student`.
- **`live-class-management` api:** `ClassSessionLookupApi` + `ClassSessionSummary`, implemented
  by `ClassSessionLookupService` (tenant-scoped, read-only).
- **`attendance-management`:**
  - `AttendanceSheet`/`AttendanceSheetSource`, and `AttendanceSheetRepository` (race-safe
    `INSERT … ON CONFLICT DO NOTHING` on the partial unique indexes).
  - `AttendanceSheetService` (find-or-create, `Propagation.MANDATORY`).
  - `AttendanceClassSessionService` (roster + mark, lifecycle gate via
    `ClassSessionMarkingPolicy` and the shared `Clock` bean).
  - `AttendanceSummaryService` + `AttendanceSummaryRepository` (SQL `GROUP BY`; tenant taken
    from `TenantContext` inside the class).
  - `AttendanceRecordViewAssembler` (batched sheet + session-title enrichment).
  - `AttendanceAccessGuard` split into pre-lookup and post-lookup halves.
  - Legacy `AttendanceMarkingService` now attaches rows to `LEGACY_LESSON` sheets.
  - `AttendanceTenantAssertions` factors out the tenant-equality guard shared by every
    explicit-`tenantId` query.
- **Controller:** four new endpoints; `classSessionId` filter on three report reads; additive
  record fields; lesson endpoints marked `@Deprecated`.

## 13. Phase C completion (frontend)

- `lib/api/attendance.ts`: `useClassSessionRoster`, `useMarkClassSessionAttendance`,
  `useCourseAttendanceSummary`, `useMyAttendanceSummary`, new record fields, `classSessionId`
  param. The lesson hooks are marked `@deprecated`.
- `MarkAttendancePanel` (shared by Teacher and Staff): Course → Class session (most recent first,
  title + time + status); session header with status badge; marking-closed banner that disables
  controls and hides Save; student names; "No longer enrolled" read-only rows; a 409 on submit
  surfaced as an alert.
- Reports (Teacher and Tenant Admin): `AttendanceSummaryTable` when a course filter is applied;
  session column via `formatAttendanceSession` (session title, or "Lesson #… (legacy)").
- Student My Attendance: `MyAttendanceSummary` per-course rate cards, course names, session
  labels. Student Detail → Attendance tab uses the session label too.

## 14. API mismatch check

None found. The frontend types mirror the shipped DTOs, and the one nullable change
(`sessionId`) was handled at every call site. Previously `shortId(record.sessionId)` would
have thrown on `null`; all four sites now use `formatAttendanceSession`.

## 15. Phase D/E — tests, migration and tenant-isolation review

**New backend tests (all green):**

| Test class | Tests | Covers |
|---|---|---|
| `AttendanceClassSessionIntegrationTest` | 18 | Mark/sheet semantics, re-mark in place, independent weekly occurrences, duplicate studentIds in a batch, all-rejected batch leaves no sheet, lifecycle (future/cancelled 409; live/completed 200), roster history for unenrolled students, Student 403 (real and random id), Finance/TA 403, auditor view-only, operator mark, 401, wrong teacher 403, course reassignment, DB-level wrong-course rejection, report `classSessionId` filter, student privacy, legacy endpoint → LEGACY_LESSON sheet |
| `AttendanceClassSessionCrossTenantIntegrationTest` | 4 | Roster/mark 404 + zero writes (teacher, admin, operator), summary 404, report/`my` filter by foreign session → empty |
| `AttendanceSummaryIntegrationTest` | 4 | Counts + 50.0% rate (teacher and staff), legacy rows included, wrong teacher/student 403, `/my/summary` own-only |
| `AttendanceClassSessionConcurrencyIntegrationTest` | 1 | Concurrent first marks → exactly one sheet and one record |
| `AttendanceSheetMigrationIntegrationTest` | 2 | Real Flyway V55 → seed legacy rows → V56: every row attached to one deterministic LEGACY_LESSON sheet, columns untouched, no fabricated sessions; mismatch pre-check aborts |
| `ClassSessionMarkingPolicyTest` | 4 | Gate truth table; rate rounding |

Updated for the new signatures: `AttendanceMarkingServiceTest`, `AttendanceReportServiceTest`
and `AttendanceRecordRepositoryTenantGuardTest` (now also asserts the class-session upsert
guard).

**Migration review:**
- Additive, except for the bounded `sheet_id` backfill. V1–V55 are unedited.
- The pre-check fails loudly rather than guessing.
- Legacy sheet ids are deterministic.
- **Rollout note:** `ALTER TABLE attendance_record … SET NOT NULL` and the new FK/unique
  validations take `ACCESS EXCLUSIVE` locks and scan the table. For a large production
  `attendance_record`, apply V56 in a maintenance window.

**Tenant-isolation review:**
- `attendance_sheet` has `tenant_id NOT NULL` + FK, tenant-leading indexes, and composite FKs.
  Per-tenant uniqueness is enforced through partial unique indexes.
- Every read goes through `TenantAwareRepository` or `TenantContext`.
- The explicit-`tenantId` native writes follow the module's existing guarded precedent
  (`AttendanceTenantAssertions`). They are called only with `TenantContext#getTenantId()`.
- `ClassSessionLookupApi` is tenant-scoped, and the cross-tenant tests are present.
- Rule check (`tenancy.md` "no repository method accepting caller-supplied tenant_id"): the
  sheet inserts take `tenantId` like the pre-existing upsert, and are guarded identically. The
  new summary repository avoids the pattern entirely.

**Defect found and fixed during review:** a native upsert followed by a re-read inside the same
transaction could return a stale persistence-context entity. When the same student appeared
twice in one batch, the second row's response showed the first status (the database was
correct). Fixed with `@Modifying(clearAutomatically = true)` on both record upserts, plus a
regression assertion. The legacy path had the same latent issue and is fixed too.

## 16. Phase F/G — documentation and final verification

**Docs updated:**
- `docs/api/attendance-management.md` (Wave 8 endpoints, deprecations, record shape,
  cross-module contract);
- `docs/parity/klass-parity-matrix.md` (PAR-10-01/02/03/04, PAR-19-02/05);
- `docs/parity/implementation-roadmap.md` (Wave 8 status);
- `docs/parity/migration-strategy.md` (Wave 8 resolution of the ClassSession/attendance
  question);
- `docs/requirements/open-decisions.md` §20a.

**Verification:** see the Wave 8 completion report in the session transcript. Summary:
- backend full suite (`mvnw test`): 1958 tests, 0 failures, 0 errors. After the
  `clearAutomatically` fix, the attendance + live-class suites were re-run: 153 tests, 0
  failures;
- frontend `tsc --noEmit` clean, `npm run lint` 0 errors (2 pre-existing warnings in
  `material-upload-form.tsx`), `npm run build` succeeds;
- Playwright `attendance.spec.ts` 36/36, `student-detail-tabs` + `teacher-detail-tabs` 23/23.
