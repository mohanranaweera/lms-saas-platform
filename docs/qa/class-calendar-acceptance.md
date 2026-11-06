# CAL-01 Class Calendar & Timetable — Acceptance

Spec: `docs/specs/class-calendar.md` · Plan: `docs/plans/class-calendar.md` · Evidence: `docs/qa/class-calendar-evidence/`

Test suites referenced below:
- **GEN** `backend/.../calendar/service/ClassOccurrenceGeneratorTest` (19), **VAL** `ClassScheduleValidatorTest` (7), **INT** `calendar/web/ClassCalendarIntegrationTest` (7, Testcontainers), **PDF** `reportinganalytics/.../TimetablePdfRendererTest` (3)
- **E2E-M** mocked Playwright `frontend/e2e/class-calendar.spec.ts` (16) + `todays-classes.spec.ts` (7) — deterministic `page.clock`
- **E2E-L** live Playwright `frontend/e2e/class-calendar-live.spec.ts` (9, `CAL_LIVE=1`) against the real local backend + `lms_dev`, synthetic courses, tenant zone temporarily `America/New_York` (restored after)

Reference = confirmed from `docs/references/klass-calender/` screenshots. Product = additional requirement from the task brief. Assumption = decided here.

| # | Requirement | Reference / current evidence | Gap before | Implementation | Verification | Result |
|---|---|---|---|---|---|---|
| 1 | Course ⋮ → Edit Class Schedule (admin + teacher) | Reference (Course - Calender Mangement.png) | Old item only linked to `/sessions` | `EditClassScheduleDialog` from admin ⋮ menu, teacher course card, course Schedule tab | E2E-M "teacher with edit rights…", "add / remove / validate / save…"; E2E-L editor screenshot | PASS |
| 2 | Dialog structure: text, Add Day Mon→Sun, rows with required Start/End, ✕, Cancel/Save | Reference (2/3/4.png) | none | Same structure; Add Day menu; rows grouped by weekday | `schedule-editor-dialog.png`; E2E-M | PASS |
| 3 | Usable optional From/To dates (inclusive, tenant zone) | Reference mentions, does not render (Product) | Missing in reference | Labeled date inputs + helper text; per revision; inclusive on tenant-local start date | GEN `effectiveBoundsAreInclusiveTenantLocalDates`; INT save/reopen | PASS |
| 4 | Server validation: weekday/time, end>start, overnight rejected, duplicates, same-day overlap, From≤To, ownership | Product | — | `ClassScheduleValidator`, `CourseAccessGuard` | VAL (7); INT; E2E-L 400s for overnight/duplicate/range | PASS |
| 5 | Cancel discards, Save atomic + refresh, reopen shows saved, clear-all removes only future recurrence | Product | — | Revision append-only; course row lock; query invalidation | INT `saveReopenConflictClearAndAudit`, `firstRevisionGoverns…`; E2E-M "Cancel discards…" | PASS |
| 6 | Lost-update protection | Product | — | `expectedRevisionId` → 409 `SCHEDULE_CONFLICT`, Reload | INT; E2E-M 409 test; E2E-L stale PUT = 409 | PASS |
| 7 | Audit log on schedule change | Security rules | — | `COURSE_CLASS_SCHEDULE_UPDATED` before/after, same transaction | INT (audit row + metadata) | PASS |
| 8 | One occurrence service for calendar / today / PDF | Product | — | `ClassOccurrenceService` → `ClassCalendarApi` (PDF via api) | E2E-L: month JSON count 24 = PDF agenda time-ranges 24 (single course) | PASS |
| 9 | Session replaces recurring slot; cancelled session cancels it; never both | Product | — | Precedence in generator (spec §4) | GEN `activeSessionReplaces…`, `cancelledSessionCancels…`, `activeSessionWins…` | PASS |
| 10 | History preserved on schedule change | Product | — | Revision effective from save instant; first revision unbounded (spec §2.1) | GEN `revisionGoverns…`, `clearedSchedule…`; INT `firstRevisionGovernsBackwardButLaterRevisionsNeverRewriteHistory` | PASS |
| 11 | Bounded generation, stable ids, deterministic sort | Product | — | ≤42-day range; `r_{slot}_{date}` / `s_{session}` | GEN `outputIsDeterministicallySorted`, `singleDay…StableIds`; E2E-L unique ids | PASS |
| 12 | Tenant timezone (not server/browser), half-open ranges, DST policy, month/year boundary, leap day | Product | — | `TenantZoneResolver`; `atStartOfDay(zone)`; java.time DST defaults | GEN `tenantZoneWins…`, `rangeIsHalfOpen…`, `dst*` (3), `leapDayIsGenerated`; E2E-L NY tenant zone on Colombo machine | PASS |
| 13 | Overnight classes | Product | — | Explicitly rejected with message (no established representation) | VAL `endNotAfterStart…`; E2E-L | PASS |
| 14 | Calendar header: heading, Today, ‹ ›, Course (All courses), Month/Week/Day, Download | Reference (Calander.png) | Not built | `ClassCalendar` (+ extra List view) | E2E-M; E2E-L screenshots | PASS |
| 15 | Month: Sunday-first, muted adjacent days, today marker, time+name chips, stable colors, accessible +more | Reference + Product | — | Month grid, `courseColor`, day-list dialog | E2E-M month test; `calendar-month-desktop.png` | PASS |
| 16 | Week/Day: duration positioning, readable overlaps, now line | Product | — | Time grid; overlap lanes capped (3 week / 6 day) with "+N" → full day list | E2E-M "week view splits…", "caps overlapping lanes…"; `calendar-week-desktop.png` | PASS |
| 17 | Navigation by view, Today = tenant today, course filter persists, URL state, stale-request safety | Product | — | `?view&date&course`; abortable queries keyed by range | E2E-M "month/week/day switching keeps the course filter…"; E2E-L filter URL | PASS |
| 18 | Event details dialog with role-appropriate View Course | Product | — | `OccurrenceDetailsDialog` (portal routes) | E2E-M details tests; `event-details-dialog.png` | PASS |
| 19 | Today's Classes on public home, placement, theme tokens | Product | None | `TodaysClasses` after hero/sub-hero, before About/Featured | E2E-M "sits after the sub-hero…"; E2E-L desktop/tablet/mobile | PASS |
| 20 | Status rules incl. exact start=Live, exact end=Over; schedule-based wording | Product | — | `occurrenceStatus`; server `status` at `serverNow` | GEN `statusIsLiveAtTheExactStart…`; E2E-M; E2E-L OVER/LIVE/SCHEDULED live | PASS |
| 21 | Scheduled→Live→Over and tenant-midnight rollover without reload; one shared timer | Product | — | Single next-boundary scheduler + server offset | E2E-M "advances statuses without a reload, across tenant midnight" (page.clock) | PASS |
| 22 | Grouping Live → Scheduled (earliest) → Over (latest ended); Over stays visible; counts | Product | — | Grouped sections + summary | E2E-M; `home-todays-classes-*.png` | PASS |
| 23 | Empty / error / missing image or teacher / long titles / mobile | Product | — | Fallback cover, line-clamp, compact row cards < sm | E2E-M empty/error/mobile; E2E-L no horizontal overflow at 390/768/1440 | PASS |
| 24 | Appearance switch "Show Today's Classes", default on | Product | — | `BRANDING.show_todays_classes` | Registry test; E2E-M; INT public switch-off; `appearance-show-todays-classes.png` | PASS |
| 25 | Public projection: PUBLIC + non-archived only, no cancelled, no meeting/student/admin fields | Product | — | Allowlist DTO, permitAll GET only | INT `publicTodayIsAnAllowlist…`; E2E-L (private excluded; no join/meeting/courseId/email keys) | PASS |
| 26 | Scopes: admin all, teacher own, student enrolled read-only | Product | — | Server-derived scope | INT `occurrencesFollowTheCallersScope…`, `teacherEditsOnlyOwnCourses…`; E2E-L student 200 own-only, 404 on foreign course filter/schedule GET, PUT denied | PASS |
| 27 | Cross-tenant denial | Tenancy rules | — | Tenant-aware repos; 404 | INT `crossTenantIdsAreNotFoundEverywhere` (schedule GET/PUT, occurrences filter, PDF) | PASS |
| 28 | Timetable PDF via existing Jasper stack: header, period, course, timezone, generated, grid + full agenda, footer, Unicode, multi-page, empty | Product (reference PDF is image-only) | — | `timetable.jrxml`, `TimetablePdfRenderer` | PDF tests (3); rendered & text-extracted: 7-page all-courses month, Sinhala via embedded NotoSansSinhala, no ellipses, cell continues across pages | PASS |
| 29 | No occurrence creates lessons/meetings/attendance/access/enrollment | Product | — | Read-only computation | Code review; no writes outside schedule tables | PASS |
| 30 | Existing course/live-class/appearance flows intact | Regression | — | — | 64/64 existing Playwright (tenant-admin-courses, klass-admin [real backend], appearance, public-home, live-class-schedule); backend 362 targeted tests | PASS |
| 31 | Inspect live Klass demo read-only | Reference | — | — | No credentials were supplied to this session; screenshots used as reference of record | BLOCKED |
| 32 | Teacher photo on Today's cards; tenant logo in PDF | Product | — | `teacherPhotoUrl` always null (no cross-module photo API); PDF shows institute name only | Rendered: graceful fallback avatar / name | PARTIAL (documented) |

## Deviations / assumptions
- Teacher calendar scope = courses the teacher owns (`course.teacher_id`) — the same rule as the existing course list and `CourseAccessGuard`.
- No server cache: occurrences are recomputed per request (bounded ≤42 days); nothing to invalidate. Frontend query keys invalidated on save.
- Extra **List** view (default below 640 px) in addition to Month/Week/Day; PDF from List uses MONTH.
- Existing ⋮ item that linked to `/sessions` was relabelled "Live classes".
