# Multi-Tenant SaaS LMS Platform

A white-label, multi-tenant LMS and institute management platform.

## Applications

- `backend/` — Spring Boot backend built with Maven
- `frontend/` — Next.js frontend
- `infrastructure/` — Docker, Nginx and deployment configuration
- `docs/` — Requirements, architecture and UI/UX documentation

## Architecture

- Modular monolith
- Shared SaaS deployment
- PostgreSQL tenant-aware data model
- Redis cache
- Docker deployment
- Centralized platform payment collection in Phase 1

## Development requirements

- Java 21
- Maven 3.9.16
- Node.js 24.18.0
- Docker Desktop
- Claude Code

## Local infrastructure

Backend and frontend run natively for faster development; supporting services
(Postgres, Redis, Mailpit, MinIO) run in Docker via
`infrastructure/docker-compose.dev.yml`.

1. Copy `infrastructure/.env.dev.example` to `infrastructure/.env.dev` and fill
   in local-only values (never commit this file).
2. Start the stack:

   ```
   scripts/development/start-infra.sh      # bash / Git Bash / WSL
   scripts/development/start-infra.ps1     # PowerShell
   ```

   Docker Compose only auto-loads a file literally named `.env`, so running
   `docker compose -f infrastructure/docker-compose.dev.yml up` directly
   without `--env-file infrastructure/.env.dev` will start the containers with
   blank credentials — use the scripts above, or pass `--env-file` yourself.
