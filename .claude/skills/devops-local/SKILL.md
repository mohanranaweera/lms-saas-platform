---
name: devops-local
description: Use when changing local development infrastructure — Docker Compose, Nginx, environment configuration — for this project. Never deploys or connects to production or staging.
---

# DevOps (Local)

## When to use

Use when a module needs infrastructure changes under `infrastructure/`: Docker Compose services, Nginx config, or local environment variables. This skill is scoped to local development infrastructure only.

## Scope boundary

- Stay within `infrastructure/` (and env-file scaffolding it references). Do not modify `backend/` or `frontend/` application code.
- Never execute or configure a production deployment — production deployment strategy is change-controlled and requires explicit human approval at the time, not by this skill.
- Never connect to a production or staging database or service from a local compose file.

## Checklist

- [ ] No separate Docker stack is created per normal tenant — this is a modular monolith with shared multi-tenant infrastructure
- [ ] Local development infrastructure may run in containers while backend/frontend run natively, per this project's convention; production/staging run fully containerized (planning-only concern here, not executed by this skill)
- [ ] No secrets are committed — configuration uses environment variables and example env files (`.env.*.example`), not real values
- [ ] If a tenant-routing or multi-tenant config detail changes (e.g. Nginx host/tenant routing), tenant isolation implications are noted for `tenant-isolation-review`
- [ ] Changes were validated against local containers only — no production or staging system was touched
- [ ] If this change affects how developers run the stack locally, documentation updates are flagged for `update-documentation`
