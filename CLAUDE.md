# Project Instructions

## Project

This repository contains a multi-tenant SaaS LMS and Institute Management System.

## Required stack

Backend:
- Java 21
- Spring Boot
- Maven only
- PostgreSQL
- Redis
- Flyway
- Spring Security
- JUnit
- Testcontainers

Frontend:
- Next.js
- React
- TypeScript
- npm
- Tailwind CSS
- shadcn/ui
- Playwright

Infrastructure:
- Docker
- Docker Compose
- Nginx

## Architecture

Use a modular monolith.

Do not create microservices unless an approved architecture decision record explicitly authorizes it.

Do not create a separate Docker deployment for each normal tenant.

## Multi-tenancy

- Tenant isolation is mandatory.
- Every tenant-owned table must contain `tenant_id`.
- Resolve tenant identity from trusted authenticated context.
- Never trust a `tenant_id` supplied by a normal frontend user.
- Every tenant-owned repository query must apply tenant filtering.
- Cross-tenant access tests are mandatory.

## Payment roadmap

1. Platform centrally collects payments.
2. Tenant/tutor settlements.
3. Tenant-specific payment accounts.
4. Split payments only when supported by the gateway.

Never activate enrollment from a frontend success page.

Enrollment may activate only after verified backend payment confirmation or approved manual payment evidence.

Never delete financial history.

## Development workflow

For every business module:

1. Plan the complete module.
2. Implement the Spring Boot backend only.
3. Run and review backend tests.
4. Implement the Next.js frontend only.
5. Run frontend and E2E tests.
6. Perform security, tenant isolation and integration reviews.
7. Update documentation.
8. Commit one logical change.

Do not implement backend and frontend simultaneously unless the task explicitly says `full-stack implementation approved`.

## Change controls

Do not change without explicit approval:

- multi-tenancy strategy
- authentication architecture
- payment ledger rules
- enrollment activation rules
- production deployment strategy
- database migration history
- approved API contracts

## Safety

- Never read or commit real secrets.
- Never connect to production databases.
- Never use real student or financial records during development.
- Never deploy production automatically.
- Never push directly to `main`.
- Never merge a pull request without human approval.

## Planning requirement

Before editing code, report:

- goal
- files affected
- database impact
- API impact
- security impact
- tenant impact
- tests required
