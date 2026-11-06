# CAL-01 Class Calendar & Timetable — Plan

Contract: `docs/specs/class-calendar.md`. Acceptance: `docs/qa/class-calendar-acceptance.md`.

## Planning summary
- **Goal:** Klass-parity weekly class schedules per course (Edit Class Schedule), a role-scoped Calendar & Timetable (month/week/day + PDF), and Today's Classes on the tenant public home.
- **Database impact:** new append-only `V116__course_class_schedule.sql` (`course_class_schedule_revision`, `course_class_schedule_slot`; `tenant_id NOT NULL` + same-tenant composite FKs + tenant-leading indexes). No applied migration edited. New config key `BRANDING.show_todays_classes` (registry entry, no schema change).
- **API impact:** additive only — §6 of the spec (`/courses/{id}/class-schedule` GET/PUT, `/calendar/occurrences`, `/calendar/courses`, `/calendar/timetable.pdf`, `/public/calendar/today`), plus `showTodaysClasses` on the public home response. No existing contract changed.
- **Security impact:** schedule writes behind `CourseAccessGuard` (CREATE_EDIT); reads scoped server-side by role; public projection is an allowlist; audit entry per save; no meeting credentials anywhere in calendar responses.
- **Tenant impact:** all reads/writes via tenant-aware repositories; tenant from authenticated context or host resolution; cross-tenant 404 tests.
- **Change-controlled areas:** none touched (no auth, tenancy strategy, payment/ledger, enrollment/entitlement changes). Occurrences never create sessions, attendance, access or enrollment.

## Decisions
1. Rules are new (no prior weekly model); dated occurrences are computed, not stored; `class_session` stays the dated live-session model and overrides recurring occurrences (spec §4).
2. Revisions (append-only, effective from save instant) keep history stable when a schedule changes.
3. Date bounds per course schedule revision, inclusive, on tenant-local start date.
4. Overnight slots rejected with a clear message (no established representation).
5. Concurrency via `expectedRevisionId` → 409.
6. Status is schedule-based and computed from `serverNow`; never provider telemetry.
7. PDF via existing JasperReports stack; month grid + full agenda appendix.

## Slices and ownership
| Slice | Backend (`backend/**`, backend-springboot-engineer) | Frontend (`frontend/**`, frontend-nextjs-engineer) |
|---|---|---|
| A | V116, schedule GET/PUT, validation, audit, occurrence service | Edit Class Schedule dialog (admin + teacher), schedule page |
| B | `/calendar/occurrences`, `/calendar/courses`, scopes | Calendar month/week/day, filter, details dialog, URL state |
| C | `/public/calendar/today`, `show_todays_classes` | Today's Classes, shared boundary scheduler, appearance switch |
| D | Timetable PDF (Jasper) | Download Timetable, portal nav/pages |
| E | Parent: integration, live rendered verification, PDF inspection, security/tenant spot review, docs, commits (backend and frontend committed separately). |

Full-stack parallel implementation was explicitly requested by the task owner (two concurrent implementation agents).
