# Infrastructure Instructions

This directory contains development and deployment infrastructure.

Use:
- Docker
- Docker Compose
- Nginx
- environment-based configuration

Never commit secrets.

Do not create one Docker stack per tenant.

Do not execute a production deployment without explicit human approval.

Local development infrastructure may use containers while frontend and backend run natively for faster development.

`docker-compose.dev.yml` reads its credentials from `.env.dev`, not `.env` — Docker Compose does not auto-load `.env.dev`. Use `scripts/development/start-infra.sh` (or `.ps1`), or pass `--env-file infrastructure/.env.dev` explicitly, or containers start with blank credentials.

Production and staging will run containerized applications.
