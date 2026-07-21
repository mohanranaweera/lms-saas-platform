---
name: tenant-isolation-review
description: Use to verify tenant isolation on any change that touches tenant-owned data — new entities, repositories, queries, endpoints, or UI views — before that change is considered done. Read-only review.
---

# Tenant Isolation Review

## When to use

Use before any module touching tenant-owned data is marked done — this is the check referenced by `CLAUDE.md`'s "Cross-tenant access tests are mandatory" rule and by `plan-module`'s tenant-impact section. Applies to both `backend/` and `frontend/` changes.

## Scope boundary

Read-only: this skill reviews and reports, it does not edit code. If it finds a gap, hand the fix back to `implement-backend`, `implement-frontend`, or `database-migration` as appropriate.

## Checklist

- [ ] Every tenant-owned table has a `tenant_id` column
- [ ] Every repository query against a tenant-owned table filters by `tenant_id`
- [ ] Tenant identity used in every query is resolved from trusted authenticated context (session/JWT/security context) — never from a request parameter, body field, or header supplied by the frontend
- [ ] Every new or changed endpoint that returns or accepts tenant-owned data has a cross-tenant test proving tenant A cannot read, write, enumerate, or infer the existence of tenant B's data
- [ ] Frontend views/permission-denied states reflect backend-enforced tenant boundaries — the frontend is not the only place isolation is enforced
- [ ] No test or review activity used real tenant/student/financial data or a production database
- [ ] Findings are reported with severity and the exact file/query at fault; nothing is silently fixed by this skill

## Applies across both applications

Backend query filtering and frontend permission-denied states are reviewed as two separate items — a correct backend filter does not excuse a frontend gap, and vice versa.
