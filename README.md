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
