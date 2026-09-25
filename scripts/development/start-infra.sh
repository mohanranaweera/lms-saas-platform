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

# Provision the object-storage bucket the backend's "local" Spring profile
# expects (application-local.yml's object-storage.bucket, default "lms-dev").
# Nothing in docker-compose.dev.yml creates this automatically, so a fresh
# lms_minio_data volume leaves every upload failing closed with "Uploads
# aren't available right now" (S3ObjectStorageApi's NoSuchBucket -> generic
# 503). The minio/minio server image already bundles the `mc` client, so this
# reuses the running container instead of pulling a separate minio/mc image.
# Read the specific values needed (not `source`d) so a UTF-8 BOM at the start
# of a Windows-edited .env.dev - a common artifact this file already hits in
# practice - can't break the script the way it breaks a literal `source`.
# Stripping `\r` also guards against a CRLF-saved .env.dev on Windows.
env_lookup() {
	grep -E "^$1=" "$env_file" | tail -1 | cut -d= -f2- | tr -d '\r'
}
minio_user="$(env_lookup MINIO_ROOT_USER)"
minio_password="$(env_lookup MINIO_ROOT_PASSWORD)"
bucket="$(env_lookup OBJECT_STORAGE_BUCKET)"
bucket="${bucket:-lms-dev}"
echo "Waiting for MinIO to accept the local alias..."
for _ in $(seq 1 30); do
	if docker exec lms-minio-dev mc alias set local "http://localhost:9000" "$minio_user" "$minio_password" >/dev/null 2>&1; then
		docker exec lms-minio-dev mc mb --ignore-existing "local/$bucket"
		break
	fi
	sleep 1
done
