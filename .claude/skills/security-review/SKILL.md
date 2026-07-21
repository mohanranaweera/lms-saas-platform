---
name: security-review
description: Use to review authentication, authorization, tenant isolation, file uploads, protected content access, and secret handling before a change is considered done. Read-only review.
---

# Security Review

## When to use

Use before any module is marked done, per `CLAUDE.md`'s development workflow step "Perform security, tenant isolation and integration reviews." Applies to both `backend/` and `frontend/`.

## Scope boundary

Read-only: this skill reviews and reports, it does not edit code. Hand fixes back to `implement-backend` or `implement-frontend`.

## Checklist

- [ ] Every protected endpoint enforces authentication and the correct role/permission checks server-side, not only in the UI
- [ ] Every tenant-owned query resolves tenant identity from trusted authenticated context, never from client input — cross-tenant access is provably blocked, not just untested
- [ ] File uploads validate type, size, and ownership before acceptance
- [ ] Protected content (e.g. course video, materials) cannot be reached by an unauthorized tenant or role via direct URL/ID guessing
- [ ] No real secrets, credentials, or production data appear in code, config, tests, or fixtures
- [ ] Authentication architecture and multi-tenancy strategy are change-controlled — any change to either is flagged for approval, not applied silently
- [ ] Findings are ranked by severity and point to the exact file/endpoint at fault; nothing is silently fixed by this skill
- [ ] Review was performed against local/dev code only — no production system was accessed
