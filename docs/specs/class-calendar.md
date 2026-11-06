# Class Calendar & Timetable (CAL-01) — Spec and API Contract

Status: implementation contract (Klass parity + improvements). Branch `feat/class-calendar`.

## 1. Reference behaviour (confirmed from `docs/references/klass-calender/`)

| Ref | Confirmed behaviour |
|---|---|
| Calander.png | "Calendar & Timetable" page: period heading ("October 2026"), TODAY, ‹ ›, Course select ("All courses"), MONTH/WEEK/DAY, DOWNLOAD TIMETABLE. Sunday-first month grid, adjacent-month days muted, colored chips "7:30 AM 2027 A/L Biology…" (time + course name, truncated). |
| Course - Calender Mangement.png | Course list row ⋮ menu → **Edit Class Schedule** (alongside Manage Tutes, Archive Course). |
| … 2.png | Dialog "Edit Class Schedule": "Edit the class schedule for **{course}**. These times will be shown on the student and public page." ADD DAY, CANCEL, SAVE. |
| … 3.png | ADD DAY opens a Monday→Sunday menu. |
| … 4.png | Each row: weekday label, Start Time *, End Time * (required), red ✕ remove. Note: "Select From and To dates to schedule only for the selected period (optional)" — **but no From/To inputs are rendered** (incomplete in reference). |
| Time Table Download.pdf | Image-only month grid export (no selectable text, truncated titles). |

Live demo (demo.klass.lk) was not inspected: no credentials were provided to this session. The screenshots above are the reference of record.

**Additional product requirements (not in the reference):** usable From/To inputs; Today's Classes on the public home; week/day time-grid; details dialog; text-based paginated PDF with agenda appendix; schedule-based statuses; appearance switch.

## 2. Concepts and the authoritative model

| Concept | Representation | Owner |
|---|---|---|
| Recurring timetable rule | `course_class_schedule_revision` + `course_class_schedule_slot` (new, V116) | coursemanagement |
| Dated class occurrence | **Computed, never stored.** Generated for a bounded interval by `ClassOccurrenceService`. | coursemanagement (calendar package) |
| Existing lesson/live session | `class_session` (unchanged) | liveclassmanagement |

There was no weekly-schedule model before CAL-01 (`/courses/{id}/schedule` was a placeholder), so a new rule table is not a duplicate model. `class_session` stays the dated live-session model and is folded into the calendar (§4).

### 2.1 Revisions preserve history
Each Save appends a **revision** (append-only, never updated/deleted) with `effective_at = now()` (server clock). A revision governs occurrences whose **start instant** is in `[revision.effective_at, nextRevision.effective_at)`. Therefore:
- past and in-progress occurrences keep the rule that was in force when they started — a schedule change never rewrites history;
- clearing every row = a revision with zero slots → no future recurring occurrences; lessons, sessions, attendance, payments untouched;
- a course's **first** revision has no lower bound: there is no earlier schedule history to protect, so the weekly pattern also shows on earlier dates (e.g. the rest of the current month and a class already in progress today), still subject to `effectiveFrom`. Decided during integration: without this, a schedule saved mid-class never showed that class as Live now, and the current month's earlier days stayed empty, unlike the reference.

### 2.2 Effective date bounds
`effectiveFrom` / `effectiveTo` (`LocalDate`, optional, per revision, i.e. per course schedule): apply to the occurrence's **tenant-local start date**, **inclusive** on both ends; `null` = unbounded. `effectiveFrom <= effectiveTo` required. Course lifecycle stays authoritative: archived courses and DRAFT courses produce no occurrences on student/public surfaces (admin/teacher calendars still show DRAFT courses, never archived ones).

### 2.3 Slot validation (server-side, 400 `VALIDATION_ERROR` with field messages)
- `dayOfWeek` ∈ MONDAY..SUNDAY; `startTime`, `endTime` `HH:mm` (minute precision).
- `endTime > startTime`. **Overnight classes are not supported**: end ≤ start is rejected with "End time must be after start time. Overnight classes aren't supported — split them into two rows." (no silent overnight interpretation).
- Duplicate row (same day+start+end) rejected; overlapping rows on the same weekday **within one course** rejected. Different courses may overlap freely. Several slots per weekday allowed.
- Max 50 slots per course.

### 2.4 Concurrency
PUT carries `expectedRevisionId` (the revision the editor loaded, `null` when none). If the course's current revision differs → `409 SCHEDULE_CONFLICT` ("This schedule was changed by someone else. Reload to see the latest version."). Save is one transaction that locks the course row (`SELECT … FOR UPDATE`).

## 3. Time rules
- Zone = tenant `GENERAL.default_timezone` (IANA; `UTC` if unset/invalid). Never server or browser zone.
- Recurring slots are local wall-clock: occurrence start = `ZonedDateTime.of(localDate, startTime, zone)`. DST policy = `java.time` defaults: nonexistent local time shifts forward by the gap; ambiguous time takes the earlier offset. (Asia/Colombo has no DST.)
- Intervals are half-open `[start, end)`. An occurrence is in range iff `start < rangeEnd && end > rangeStart`.
- Local-day boundaries: `localDate.atStartOfDay(zone)` (never "+24h").
- Range params are tenant-local dates `from` (inclusive) / `to` (exclusive), max 42 days (6-week month grid). Generation loops only over those dates.
- Every response returns `timezone` and `serverNow` (ISO instant).

## 4. Occurrence assembly and precedence
For each in-scope course: generate recurring occurrences from the governing revision; load `class_session` rows of the same courses overlapping the range (via `liveclassmanagement.api`, tenant-scoped).
- A non-cancelled session (SCHEDULED/LIVE/COMPLETED) that **overlaps** a recurring occurrence of the same course on the same tenant-local start date **replaces** it (rescheduled/realised occurrence; times come from the session). Only the session is emitted (`source=SESSION`, `replacesOccurrenceId` set).
- A CANCELLED session that overlaps a recurring occurrence of the same course (and no active session replaces it) marks that occurrence `cancelled=true` (shown struck-through to staff/students; never Live now/Scheduled; omitted from the public projection).
- Standalone sessions (no matching slot) are emitted as `source=SESSION`. Standalone CANCELLED sessions are emitted `cancelled=true` to staff only.
- Never both original and replacement.
- Stable ids: recurring `r_{slotId}_{yyyyMMdd}`; session `s_{sessionId}`.
- Deterministic sort: `start`, then `end`, then `courseName` (case-insensitive), then `id`.
- An occurrence never creates a lesson/session, starts a meeting, marks attendance, grants access or changes enrollment.

### Schedule status (computed per occurrence, schedule-based only)
`SCHEDULED` if `now < start`; `LIVE` if `start <= now < end`; `OVER` if `now >= end`; `CANCELLED` if cancelled. The API returns `status` at `serverNow`; the UI recomputes with a server-offset clock. "Live now" means the scheduled time window — not provider telemetry.

## 5. Scopes (server-derived, never from request fields)
| Caller | Courses in scope | Writes |
|---|---|---|
| Tenant Admin / staff with `COURSES` VIEW | all non-archived tenant courses | `CourseAccessGuard.requireCourseAccess(course, CREATE_EDIT)` |
| Teacher / TA | courses they own or are assigned to (same rule as the course list) | same guard (ownership) |
| Student | courses they currently have access to (enrollment/access policy, PUBLIC or PRIVATE, not DRAFT) | none (read-only) |
| Public | `status = PUBLIC`, not archived, tenant public page enabled and `show_todays_classes` on | none |

`courseId` filter outside the caller's scope → 404 (no existence leak). Cross-tenant ids → 404.

## 6. API contract (all JSON wrapped in the existing `ApiResponse` envelope)

### 6.1 `GET /api/v1/courses/{courseId}/class-schedule` (staff/teacher with VIEW on the course)
```json
{ "courseId": "uuid", "courseName": "Biology 2027", "timezone": "Asia/Colombo",
  "revisionId": "uuid|null", "effectiveFrom": "2026-11-01|null", "effectiveTo": "2027-03-31|null",
  "updatedAt": "instant|null", "updatedByName": "string|null", "canEdit": true,
  "slots": [ { "id": "uuid", "dayOfWeek": "MONDAY", "startTime": "08:00", "endTime": "10:00" } ] }
```
Slots sorted Monday→Sunday, then start time.

### 6.2 `PUT /api/v1/courses/{courseId}/class-schedule`
Body: `{ "expectedRevisionId": "uuid|null", "effectiveFrom": "date|null", "effectiveTo": "date|null", "slots": [ { "dayOfWeek", "startTime", "endTime" } ] }` → 200 with the §6.1 shape. Errors: 400 VALIDATION_ERROR, 403, 404, 409 SCHEDULE_CONFLICT. Writes an audit log entry (`COURSE_CLASS_SCHEDULE_UPDATED`, before/after slots + bounds) in the same transaction. Evicts the tenant's calendar caches.

### 6.3 `GET /api/v1/calendar/occurrences?from=YYYY-MM-DD&to=YYYY-MM-DD[&courseId=uuid]` (any authenticated tenant user; scope per §5)
```json
{ "timezone": "Asia/Colombo", "serverNow": "2026-11-06T04:30:00Z",
  "from": "2026-11-01", "to": "2026-12-06",
  "occurrences": [ {
    "id": "r_<slotId>_20261106", "source": "RECURRING|SESSION",
    "courseId": "uuid", "courseName": "string", "courseSlug": "string",
    "start": "instant", "end": "instant", "localDate": "2026-11-06",
    "startLocalTime": "08:00", "endLocalTime": "10:00",
    "teacherNames": ["string"], "status": "SCHEDULED|LIVE|OVER|CANCELLED", "cancelled": false,
    "sessionId": "uuid|null", "sessionTitle": "string|null", "replacesOccurrenceId": "string|null" } ] }
```
400 when `to <= from` or span > 42 days. No meeting URLs/passcodes/provider fields — joining stays on the existing live-class endpoints.

### 6.4 `GET /api/v1/calendar/courses` — filter options in caller scope: `[ { "id", "name" } ]` sorted by name.

### 6.5 `GET /api/v1/calendar/timetable.pdf?from&to[&courseId]&view=MONTH|WEEK|DAY` — `application/pdf`, `Content-Disposition: attachment; filename="timetable-<from>-<to>.pdf"`. Same scope, occurrence service, overrides and sorting as §6.3 (all occurrences, never a DOM subset). Rendered by the existing JasperReports stack (`reportinganalytics/service/pdf`).

### 6.6 `GET /api/v1/public/calendar/today` (anonymous; tenant from host via `TenantResolutionFilter`)
```json
{ "enabled": true, "timezone": "Asia/Colombo", "serverNow": "instant", "date": "2026-11-06",
  "occurrences": [ { "id", "courseName", "courseSlug", "coverUrl|null",
    "teacherName|null", "teacherPhotoUrl|null", "start", "end", "startLocalTime", "endLocalTime", "status" } ] }
```
Today = tenant-local `[date 00:00, date+1 00:00)` with the §3 overlap rule. Cancelled omitted. `enabled=false` (and empty list) when the switch is off or the public page is disabled. Allowlist only — no course ids of non-public courses, no student data, no meeting/provider fields.

### 6.7 Appearance switch
`BRANDING.show_todays_classes` (BOOLEAN, default **true**) in `ConfigPropertyRegistry`, editable through the existing appearance settings endpoint; exposed as `showTodaysClasses` on `GET /api/v1/public/tenant-config/home`.

## 7. Caching
Occurrence *times* may be cached (key: tenant, scope key, from, to, courseId, timezone); `status` is always recomputed against `now` at response time. Public today: key (tenant, local date) with ≤ 60 s TTL. Schedule save, course status/archive change evicts the tenant's entries. Frontend: TanStack Query keys `["calendar", ...]`, `["class-schedule", courseId]`, `["public-today-classes"]`, invalidated on save.

## 8. UI
- **Edit Class Schedule** dialog (shared component) from course ⋮ menu in Tenant Admin course list and Teacher course list (when `canEdit`). Rows grouped by weekday, required times, remove ✕, From/To date inputs with helper text, Cancel discards, Save → invalidate.
- **Calendar & Timetable** page at `/tenant-admin/calendar`, `/teacher/calendar`, `/student/calendar` (shared component; URL state `?view=month|week|day&date=YYYY-MM-DD&course=<id>`). Month (Sunday-first, muted adjacent days, today ring, chips with time+name, "+N more" popover), Week/Day time-grid with duration positioning, overlap columns, now line. Mobile (<640px) defaults to an agenda list with view switch retained. Details dialog: course, date, start–end, timezone, teacher(s), status text, View Course (role-appropriate route), Close.
- **Today's Classes** on the public home, after the hero/sub-hero and before featured courses; groups Live now → Scheduled (earliest first) → Over (most recently ended first); single shared next-boundary timer; refetch every 5 min, on visibility return, and at tenant-local midnight.

## 9. Implementation notes (as built)
- Teacher scope = courses the teacher owns (`course.teacher_id`), matching the course list and `CourseAccessGuard`; non-owner assigned teachers do not see the course.
- `teacherPhotoUrl` is always `null` (no cross-module teacher-photo API yet); the PDF header shows the institute name, no logo.
- Students get `404` (not `403`) on `GET /courses/{id}/class-schedule`.
- A slot entirely swallowed by a DST gap produces no occurrence that day.
- No server-side cache (§7 allows it): occurrences and status are recomputed per bounded request.
- UI adds a fourth **List** view (`view=list`, default below 640 px); the PDF for List uses `MONTH`.
- Week/Day overlap lanes are capped (3 / 6); further overlapping classes collapse into a "+N" lane that opens the full day list.
- Public card status badge is rendered once per breakpoint layout (overlay on ≥ sm, inline below sm); the hidden copy is `display:none`.
