#!/usr/bin/env bash
# Starts local development infrastructure (Postgres, Redis, Mailpit, MinIO).
# Run from anywhere; paths are resolved relative to the repo root.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
env_file="$repo_root/infrastructure/.env.dev"
compose_file="$repo_root/infrastructure/docker-compose.dev.yml"

if [ ! -f "$env_file" ]; then
	echo "Missing $env_file" >&2
	echo "Copy infrastructure/.env.dev.example to infrastructure/.env.dev and fill in local-only values first." >&2
	exit 1
fi

# Docker Compose only auto-loads a file literally named ".env" in the compose
# file's directory; our vars live in .env.dev, so --env-file must be explicit.
docker compose --env-file "$env_file" -f "$compose_file" up -d
