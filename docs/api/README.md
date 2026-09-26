# API Contracts

Source of truth for REST contracts between `backend/` and `frontend/`, one file per
domain/endpoint group. Populated and kept in sync by the `review-api-contract` skill —
do not hand-edit a contract file to reflect a shipped change without going through that
skill, since approved contracts are change-controlled per root `CLAUDE.md`.

## File naming

`<domain>.md`, matching the domain names in `.claude/rules/architecture.md` (e.g.
`enrollment-management.md`, `payment-management.md`). One file per domain; group related
endpoints within it by resource.

## What each contract file must contain, per endpoint

- Method + path
- Request shape (path/query params, body), including optional fields and null handling
- Response shape per status code, including error response format for every failure mode
  (validation, not-found, forbidden, conflict)
- Pagination/sorting/filtering conventions, if the endpoint returns a collection
- Auth requirements: which role(s) may call it, behavior on missing/expired auth
- Confirmation that no client-supplied `tenant_id`, role, or other trust-sensitive field is
  accepted — tenant/role are always resolved server-side from authenticated context

## Status

Domain contract files, in the order they landed:

- `identity-access-service.md`
- `user-management.md`
- `course-management.md`
- `content-management.md`
- `payment-management.md`
- `ledger-settlement-management.md`
- `enrollment-management.md`
- `attendance-management.md`
- `exam-management.md`
- `notification-management.md` — MVP-018, written post-ship per this file's own
  documented "process gap" (the plan's §10 draft contract was never finalized into this
  directory until a post-ship review found the gap).
- `audit-log-management.md` — MVP-019 (AUDIT-3's read endpoint only; AUDIT-2's event
  wiring has no HTTP surface of its own).
- `live-class-management.md` — Wave 4 (PAR-19-01–05), plus the `integration-management`-owned
  `live-class` webhook.
- `content-management.md` — updated in Wave 5 (PAR-06-02/03/05, PAR-27-01) for the
  `materialType` discriminator, availability-window/download-limit fields, and the
  Student enrollment-check security fix; originally written retroactively for MVP-009.
- `video-access-management.md` — Wave 5 (PAR-17-01, PAR-20-01–04), new domain's first
  contract file: video upload, playback-policy upsert, and entitlement-checked
  playback-session issuance/heartbeat/end.
- `payment-management.md` — updated in Wave 6 (PAR-07-01/03/09, PAR-08-05) for the
  optional `idempotencyKey` on order creation/payment initiation, the new
  transaction-scoped-advisory-lock idempotency mechanism, and the manual-slip-approval
  ledger-gap fix (`SlipReviewService#approve` now creates+confirms a `Payment` and
  writes the ledger entry it previously omitted).
- `ledger-settlement-management.md` — updated in Wave 6 (PAR-07-03/04/06/07/08) for the
  extended `courseId`/`courseTitle`/`billingPeriodId`/`operationalState`/`method`/
  `reference` fields on every ledger-derived view, the new `status`/`method` dashboard
  filters, the new `PaymentOperationalState`/`PaymentMethod` computed projections, and
  the new `GET /api/v1/ledger/outstanding`/`GET /api/v1/ledger/courses/{courseId}/summary`
  endpoints.
- `finance-expense-management.md` — Wave 7 (PAR-23-01/02/04/05), new domain's first contract
  file: expense categories, append-only expenses with receipt upload/void, and ledger-derived
  finance reports (summary, course/teacher revenue, monthly periods).
- `ledger-settlement-management.md` — updated in Wave 7 (PAR-23-03, PAR-24-02/03/04) for the
  teacher settlement foundation (share rates, calculated statements, adjustments, mark-paid,
  payee list) and the new `LedgerRevenueApi` read contract.
