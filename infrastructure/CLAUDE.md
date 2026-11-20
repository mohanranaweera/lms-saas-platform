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

Production/staging secrets follow ADR-019: `docker-compose.prod.yml` mounts each secret as a file
under `/run/secrets/` (host folder `/etc/lms/secrets`, root-only) and the backend runs with the
`prod` profile, which reads them and refuses to start without the critical ones. Secret names:
`prod-secrets.reference`; operator steps: `docs/operations/production-secrets-runbook.md`. Never put
a secret value in any file in this directory.
