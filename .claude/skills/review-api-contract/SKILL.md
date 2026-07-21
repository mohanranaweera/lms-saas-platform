---
name: review-api-contract
description: Use when defining or changing an API contract between the Spring Boot backend and the Next.js frontend — request/response shapes, status codes, error formats, pagination, auth — before or during implementation on either side.
---

# Review API Contract

## When to use

Use whenever a module plan defines a new endpoint, or an implementer (backend or frontend) reports a mismatch between the intended contract and what's feasible. This skill reviews and records the contract; it does not implement backend or frontend code.

## Scope boundary

- This is a cross-cutting review between `backend/` and `frontend/` — describe both sides, but implement on neither. Hand confirmed contract changes to `implement-backend` and `implement-frontend` separately.
- Approved API contracts are a change-controlled area per `CLAUDE.md` — a contract change on an already-shipped endpoint needs explicit approval, not a silent update.

## Checklist

- [ ] Request and response shapes are fully specified, including optional fields and null handling
- [ ] Status codes and error response format are specified for every failure mode (validation, not-found, forbidden, conflict)
- [ ] Pagination, sorting, and filtering conventions are specified if the endpoint returns a collection
- [ ] Auth requirements are specified (which role(s) may call this, what happens on missing/expired auth)
- [ ] The contract does not accept a client-supplied `tenant_id`, role, or other trust-sensitive field — tenant/role are resolved server-side from authenticated context only
- [ ] Breaking changes to an already-approved contract are flagged for explicit approval rather than applied silently
- [ ] The contract is written down (in the module plan or an ADR/doc under `docs/api`) so both `implement-backend` and `implement-frontend` can work from the same source of truth
- [ ] No production endpoint or production response data was used as a reference
