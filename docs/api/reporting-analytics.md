# Reporting Analytics API

All report endpoints require an authenticated tenant context. Tenant IDs are never accepted from clients. Responses use the standard `{data}` envelope and `Cache-Control: no-store`; errors use the standard error envelope.

## Course Summary Report (RPT-05)

Base: `/api/v1/reports/course-summary`. Authorization: `FINANCE_EXPENSES/VIEW` on every endpoint. Report runs are short-lived Redis snapshots bound to the authenticated tenant and user.

### `GET /options`

Returns `{teachers:[{id,name}]}` for the resolved tenant.

### `GET /options/teachers/{teacherId}/courses`

Returns `{courses:[{id,name}]}` for courses whose current primary teacher is the tenant-scoped `teacherId`. Unknown/foreign IDs return an empty list.

### `POST /runs`

Request:

```json
{
  "teacherId": "uuid",
  "courseId": "uuid",
  "startDate": "2026-10-01",
  "endDate": "2026-10-31",
  "month": "2026-10",
  "reportName": "Teacher Report"
}
```

Rules: all selector/period fields required; end must not precede start; both dates must belong to `month`; blank `reportName` normalizes to `Teacher Report`; selected course must be in tenant and assigned to selected primary teacher. Success: `201`. Invalid or foreign/mismatched selection: `422 REPORT_VALIDATION`, without existence disclosure. Oversized scope: `422 REPORT_TOO_LARGE`. Rate limit: `429`.

Response is complete typed snapshot: `runId`, expiry/generated/timezone, institute/currency, teacher/course/title, normalized filters, KPI object, weekly rows, cumulative rows, and notes. KPI `commissionPercent` is weighted institute commission (`total commission / total revenue × 100`), so a 75% teacher share returns `25.00%`; it is `—` when the rate is incomplete or net revenue is zero. KPI and weekly `payments` are distinct confirmed ledger entries for selected-course allocations; their total reconciles with Full Cards + Discounted Cards cumulative counts.

### `GET /runs/{runId}`

Returns same stored snapshot to same tenant/user. Invalid, expired, cross-tenant, or another user's run: `404`.

### `GET /runs/{runId}/pdf`

Returns authenticated `application/pdf` attachment rendered from stored snapshot. Invalid, expired, cross-tenant, or another user's run: `404`. Export never recomputes against current data.

Metric contract and evidence limits: `docs/specs/course-summary-report.md`.
