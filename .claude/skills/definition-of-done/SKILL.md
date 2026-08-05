---
name: definition-of-done
description: Use as the final gate before committing a module or feature — confirms planning, backend, frontend, tests, tenant isolation, security, and documentation are all complete per this project's development workflow.
---

# Definition of Done

## When to use

Use at the end of every module, immediately before "Commit one logical change" (`CLAUDE.md` → Development workflow, step 8). This skill is a gate/checklist — it does not implement or fix anything itself; failed items get routed back to the relevant skill or agent.

## Checklist

- [ ] `plan-module` output exists and was followed (business goal, roles, acceptance criteria, database/backend/API/frontend/security/tenant impact, tests, docs)
- [ ] Backend implementation is complete and `backend\mvnw.cmd verify` passes
- [ ] Frontend implementation is complete and `npx playwright test` passes
- [ ] Backend and frontend were not implemented simultaneously unless the task explicitly said "full-stack implementation approved"
- [ ] `tenant-isolation-review` has passed — cross-tenant access tests exist and pass for every tenant-owned data path touched
- [ ] `security-review` has passed for this change
- [ ] `payment-ledger-review` has passed, if the change touches payments, ledger, or enrollment activation
- [ ] `ui-ux-review` has passed, if the change touches frontend UI
- [ ] `qa-regression` has been run and is green
- [ ] `update-documentation` has been completed (or explicitly stated as not needed, with a reason)
- [ ] No change-controlled area (multi-tenancy strategy, authentication architecture, payment ledger rules, enrollment activation rules, production deployment strategy, database migration history, approved API contracts) was altered without explicit approval
- [ ] No production system, production database, or real student/financial record was touched at any point in this module's work
- [ ] The change is ready to commit as one logical change, not bundled with unrelated work
