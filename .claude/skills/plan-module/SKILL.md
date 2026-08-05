---
name: plan-module
description: Use before implementing any new business module or significant feature in this multi-tenant LMS SaaS platform — before any backend or frontend code is written. Produces a complete module plan that implementation skills then follow. Does not write application code.
---

# Plan Module

## When to use

Use at the start of every business module or feature, per this project's required development workflow (`CLAUDE.md` → Development workflow, step 1: "Plan the complete module"). Do not start `implement-backend` or `implement-frontend` work until this plan exists and has been reviewed.

Do not use this skill to write code — it produces a plan document only. Hand the resulting plan to `implement-backend` and `implement-frontend` (in that order, one at a time), and to `database-migration` for schema changes.

## Required output

Produce all 11 sections, in order, for the module being planned:

1. **Business goal** — what this module accomplishes and why, in plain terms.
2. **User roles** — which of Student, Teacher, Tenant Admin, Platform Admin (and any other role) interact with this module, and how.
3. **Acceptance criteria** — complete, testable criteria, including edge cases (empty states, permission boundaries, failure paths).
4. **Database impact** — new/changed tables, columns, indexes; confirm every tenant-owned table carries `tenant_id`.
5. **Backend impact** — services, controllers, repositories, DTOs affected, described separately from frontend impact.
6. **API contract** — endpoints, request/response shapes, status codes, error format (feed this into `review-api-contract` before implementation starts on either side).
7. **Frontend impact** — pages/components affected, described separately from backend impact.
8. **Security impact** — authN/authZ, upload/content-protection concerns, secrets touched.
9. **Tenant impact** — how tenant isolation is enforced for every new or changed data path in this module.
10. **Tests** — what JUnit, integration, Testcontainers, and Playwright tests this module requires, including cross-tenant access tests.
11. **Documentation updates** — which `docs/` files need updating once this module ships.

## Checklist

- [ ] All 11 sections above are present and specific to this module (no section left as "N/A" without a one-line reason)
- [ ] Backend impact and frontend impact are described separately, not merged into one narrative
- [ ] Every tenant-owned table/query named in the plan has an explicit tenant-isolation approach
- [ ] The plan does not silently change multi-tenancy strategy, authentication architecture, payment ledger rules, enrollment activation rules, production deployment strategy, database migration history, or approved API contracts — any of those requires flagging for explicit approval, per `CLAUDE.md` → Change controls
- [ ] Test requirements are listed per layer (backend/JUnit/Testcontainers, frontend/Playwright), including cross-tenant tests
- [ ] Documentation updates are listed with target file(s) under `docs/`
- [ ] No production system or production data is referenced as a source of truth for planning
- [ ] Plan is handed off — not implemented — by this skill
