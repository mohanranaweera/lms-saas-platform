---
name: implement-backend
description: Use to implement backend business logic in this Spring Boot + Maven modular monolith (controllers, services, repositories, DTOs, entities, backend tests) after a module plan exists. Must not edit anything under frontend/.
---

# Implement Backend

## When to use

Use after `plan-module` has produced a plan (or an equivalent explicit backend task) for work under `backend/`: controllers, services, repositories, DTOs, entities, and backend tests. Use `database-migration` for schema/Flyway changes and `implement-frontend` for anything under `frontend/`.

## Scope boundary

- Edit only files under `backend/`.
- Do not modify anything under `frontend/` — if implementing this module requires a frontend change, note it and stop; only proceed across both if the task explicitly says "full-stack implementation approved."
- If the plan's API contract doesn't match what's feasible on the backend, report the mismatch via `review-api-contract` before implementing around it.

## Checklist

- [ ] Java 21, Spring Boot, Maven only (never Gradle)
- [ ] Controllers stay thin; business logic lives in services
- [ ] Requests/responses use DTOs; JPA entities are never exposed directly
- [ ] Every tenant-owned repository query applies tenant filtering, with tenant identity resolved from trusted authenticated context — never a client-supplied `tenant_id`
- [ ] Schema changes are delegated to `database-migration`, not written ad hoc here
- [ ] Enrollment activates only after verified backend payment confirmation or approved manual payment evidence — never assume activation from an upstream frontend call
- [ ] Backend tests are added for the new/changed logic, including cross-tenant access tests for any tenant-owned data path
- [ ] Every security-sensitive change has a corresponding test
- [ ] `backend\mvnw.cmd verify` passes before this work is considered done
- [ ] No connection to a production database, and no real student or financial records used
- [ ] If this change alters observable behavior (new endpoint, changed contract, changed business rule), documentation updates are flagged for `update-documentation`
- [ ] `frontend/` was not touched, unless the task explicitly authorized full-stack implementation
