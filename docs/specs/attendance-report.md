# Attendance Report — Spec & Contract (RPT-03)

Status: **Implemented (2026-10-23)**. QA evidence: `docs/qa/attendance-report-acceptance.md`.

## Reference matrix

Sources: `docs/references/klass-reporting-module/Attendance Report.png` and `Attendance Report.pdf`, inspected 2026-10-23.

| Element | Observed contract |
|---|---|
| Form heading/description | Attendance Report / Filter and generate student attendance reports by date range |
| Inputs | Searchable single-course list; Start Date*; End Date*; Report Name (default Attendance Report); Cancel; Generate Report |
| PDF | Landscape matrix; title/institute/course/date range/generated time; TOTAL RECORDS, PRESENT, ABSENT; #, INDEX, STUDENT NAME, attendance-date columns; footer |
| Fixture | 45 rows; dates 2026-10-03, 07, 10, 14, 17, 21; PRESENT 135; ABSENT 0 |

## Attendance semantics

- Source: canonical `attendance_record` rows owned by attendance-management, joined to `attendance_sheet`. One row is unique per `(tenant, sheet, student)`; re-mark/upsert and repeated imports do not add facts.
- Occurrence date: class-session scheduled start, otherwise legacy lesson `starts_at`, otherwise legacy record `marked_at`. Instant is converted to tenant timezone before matrix grouping. Inclusive request interval is `[startDate 00:00, endDate + 1 day 00:00)` in tenant zone.
- Row policy: observed-record scope. Include distinct students with authoritative records in selected course/range, including explicit absences and historically inactive students. This is not a complete class roster.
- Date columns: only actual authoritative occurrence dates; sorted chronologically. No inferred calendar-day columns. No linked-course attribution.
- Cell policy: `P` when any same-day fact is PRESENT or LATE; `A` only when all applicable same-day facts are explicitly ABSENT; `—` for missing/unknown status. Missing facts never become absent. Duplicate record IDs are ignored by calculator.
- KPIs: TOTAL RECORDS = distinct matrix student rows; PRESENT = P cells; ABSENT = A cells across complete matrix, not per PDF band.
- Legend: `P = Present (including Late)`, `A = Explicitly absent`, `— = No recorded status`.
- Stable order: index number is a string preserving leading zeros, then case-insensitive student name, then student ID. Duplicate names remain distinct by ID.
- Generation never changes attendance, enrollment, payment, entitlement, or access state.

## API

Base: `/api/v1/reports/attendance`; every response is `Cache-Control: no-store`; tenant comes from authenticated context.

- `GET /options` → `{courses:[{id,name}]}`. Teacher options are assigned-course scoped; staff requires `ATTENDANCE/VIEW`.
- `POST /runs` → `{courseId, startDate, endDate, reportName?}`. Dates are ISO `YYYY-MM-DD`; report name trims and blank normalizes to `Attendance Report`; server validates required values, order, range, title length, tenant course and role ownership. Response stores complete typed matrix in a short-lived owner-bound Redis run.
- `GET /runs/{runId}` → stored report header/matrix for same tenant and authenticated user only.
- `GET /runs/{runId}/pdf` → stored-run landscape PDF for same tenant/user only. Filename is sanitized separately from displayed title.

Report dataset fields: run metadata, tenant timezone/institute, selected course, title/filters, ordered dates, student IDs/indexes/names/cells, KPIs, legend. PDF uses this same snapshot; no recomputation from visible page.

## Security and limits

- `ATTENDANCE/VIEW` enforced server-side. Teachers pass assigned-course ownership; students and ungranted staff are denied before sensitive read. Unknown/cross-tenant courses do not resolve as valid options.
- Attendance query is parameterized and tenant-scoped from `TenantContext`, bounded by configured fact count, and fetches schedule occurrence in one projection query. No per-student/date query.
- Redis run key binds report namespace, tenant, user and run ID; TTL 15 minutes. Configurable facts, students, date columns, matrix cells, PDF rows, snapshot bytes and run/PDF rate limits reject oversized requests.
- PDFs are no-store authenticated attachments. Report title is plain text in trusted JRXML. Download filename uses ASCII slug and fixed `.pdf` suffix.

## PDF behavior and limits

Landscape A4 uses six dates per readable band. Every band repeats report metadata, identity columns, date headings and table header; vertical continuation repeats headers. Global KPIs are rendered once per report dataset. Empty result renders explicit no-records message without fabricated totals or trailing blank pages. Unicode names use existing embedded LMS fonts.

## Assumptions/limitations

- Existing attendance schema has no separate finalized flag; only canonical stored records are authoritative. Cancelled class-session facts are excluded; no future/unrecorded absence is inferred.
- Historical legacy records without lesson schedule use `marked_at` as occurrence fallback.
- The current contract reports observed attendance facts, not roster eligibility for dates with no record.
- No interactive preview was added; generation downloads PDF, matching reference evidence and existing report infrastructure.
