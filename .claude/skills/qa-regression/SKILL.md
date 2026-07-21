---
name: qa-regression
description: Use to run and extend the regression test suite (JUnit, Spring Boot integration, Testcontainers, Playwright) for a change before it is considered done, including cross-tenant access tests.
---

# QA Regression

## When to use

Use after `implement-backend` and/or `implement-frontend` complete a change, and before `definition-of-done` is checked. Covers both applications, but each suite runs in its own project.

## Scope boundary

- Backend tests (JUnit, Testcontainers, Spring Boot integration tests) live under `backend/` and run via `backend\mvnw.cmd verify`.
- Frontend/E2E tests (Playwright) live under `frontend/` and run via `npx playwright test`.
- This skill adds and runs tests only — it does not modify application (non-test) source. If a test fails because of a real defect, report it to `implement-backend`/`implement-frontend` rather than fixing production code here.

## Checklist

- [ ] Backend: unit tests cover new service/business logic; integration tests (Testcontainers-backed, real PostgreSQL/Redis) cover new repositories and endpoints
- [ ] Frontend: Playwright tests cover the new/changed user flow, including its loading/empty/error/permission-denied states
- [ ] A cross-tenant access test exists for every new or changed tenant-owned data path, proving tenant A cannot read, write, or enumerate tenant B's data
- [ ] Every security-sensitive change has a corresponding test
- [ ] `backend\mvnw.cmd verify` and `npx playwright test` both pass before this work is considered done
- [ ] No test connects to a production database or uses real student/financial records
- [ ] Test failures caused by real application defects are reported, not silently patched by editing non-test source
