# Registration Report — Spec & Contract (RPT-07)

Status: **Implementation in progress (2026-10-25)**. Plan: `docs/plans/reporting-module.md` · Catalog: `docs/specs/reporting-catalog.md`.

## 1. Reference matrix

Sources: `docs/references/klass-reporting-module/Registration Report.png` and `Registration Report.pdf`.
PDF rendering could not be performed locally: no `pdftoppm`, `pdfinfo`, PyMuPDF, or equivalent renderer is installed. Screenshot was inspected.

| Surface | Observed structure |
|---|---|
| Filter panel | Course* selector, Operator selector, Registration Method selector, calendar range with month/year navigation, selected-day count, Cancel and Search |
| Preview | `REGISTERED STUDENTS REPORT - <start> to <end>`, registration-count badge, Export PDF |
| Preview columns | #, Index no., Student, Email, Mobile, Registered By, Last payment, Student Details |
| PDF | title/institute, date range, courses or All Courses, generated time, TOTAL REGISTRATIONS, #, INDEX, STUDENT NAME, MOBILE, COURSES, REGISTERED BY, LAST PAYMENT, DATE, footer |

Course is implemented as explicit **All Courses** when no course is selected despite reference required marker. The filter form mirrors the reference with a blue selected-day panel, separate abbreviated-month and year dropdowns, previous/next controls for each dimension, seven-column calendar, single-day/range selection, and Cancel/Search controls. Selector values are stored as IDs/codes but rendered using option labels.

## 2. Registration meaning and grain

One row is one tenant `student_profile` registration. Registration time is `student_profile.created_at`, which is tenant-owned and therefore remains correct when a shared/global account joins a second tenant. It is not an enrollment, payment, receipt, or global identity-account creation time.

Date filter is inclusive tenant-local dates implemented as `[startDate 00:00, endDate + 1 day 00:00)` in report tenant timezone. A single day has equal start/end dates. Stable order is registration timestamp then profile id; preview and PDF use stored same order. `TOTAL REGISTRATIONS` is complete stored result, never page count.

No students are dropped for missing email, name, index, mobile, attribution, course, or payment. Index values stay strings so leading zeroes remain. Same display names are distinct rows.

## 3. Filters and current-information policy

- **Course:** selected course qualifies registration through its current authorized course association. Date still filters registration time. All Courses includes registrations with no course or payment. Rows show all authorized current associated courses, not only matching selected course(s).
- **Operator / Registered By:** `student_profile.created_by` only. It is actor attribution recorded when the profile was created; missing/system attribution displays `—`. Never use editor, teacher, or payment approver.
- **Registration Method:** actual `student_profile.registration_status`, normalized from established `SELF_REGISTERED` / `ADMIN_CREATED` values. No payment method is used.
- **Courses:** current association as observed when report run is generated; historical course-membership reconstruction is not available.
- **Last Payment:** the established payment module has no narrow, safe batched contract for latest confirmed billing-period lookup by student. This implementation therefore displays `—` rather than joining payment tables or guessing from receipt timestamps. A future enrichment must add a tenant-scoped batched API returning latest confirmed billing period, normalize it as `YYYY-MM`, and preserve `—` when absent. It is current/as-of-run context, not historical state at registration time.

Course/operator/method identifiers must be validated in current tenant/authorized scope. Tenant identity comes only from authenticated context.

## 4. API and snapshot contract

Base: `/api/v1/reports/registrations`. Every endpoint requires backend report-view permission, uses no-store responses, and never accepts tenant id.

- `GET /options` returns authorized courses, available recorded operators, and actual registration methods.
- `POST /runs` accepts `{ courseIds?: uuid[], operatorId?: uuid, registrationMethod?: string, startDate: YYYY-MM-DD, endDate: YYYY-MM-DD }`; validates range/order/bounds and returns normalized filters, tenant branding, generated time/timezone, total and owner-bound run id.
- `GET /runs/{runId}` returns stored header.
- `GET /runs/{runId}/rows?page=&size=` pages stored rows.
- `GET /runs/{runId}/pdf` renders all stored rows.

A Redis snapshot is bound to `(tenant, authenticated user)`, short-lived, and is sole source for preview pages/PDF. Changed filters mark preview stale; an old run cannot export under new labels. Report execution is read-only apart from ephemeral snapshot/rate-limit state.

## 5. PDF policy

Jasper receives typed display rows only; it contains no qualification logic or SQL. Landscape layout uses embedded existing Unicode fonts, repeating column headers, page numbers, readable date column, wrapped names/course lists, and safe row splitting. PDF metadata includes applied Operator and Registration Method when present.

## 6. Security, boundaries, limitations

Student contacts only appear in authenticated no-store preview/PDF responses; no public run URL or logs/cache copy is made. Generation/export and student detail remain independently backend-authorized. Cross-tenant run lookup/download resolves as absent.

No schema migration is required for report semantics. `created_at`, `created_by`, and `registration_status` exist on tenant `student_profile`. Historical course membership and historical last-payment state do not exist, so course list/qualification and last payment are explicitly as-of generated run.
