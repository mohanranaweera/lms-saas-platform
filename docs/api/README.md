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
