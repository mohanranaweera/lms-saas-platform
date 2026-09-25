# Starts local development infrastructure (Postgres, Redis, Mailpit, MinIO).
# Run from anywhere; paths are resolved relative to the repo root.

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$envFile = Join-Path $repoRoot "infrastructure\.env.dev"
$composeFile = Join-Path $repoRoot "infrastructure\docker-compose.dev.yml"

if (-not (Test-Path $envFile)) {
    Write-Error "Missing $envFile`nCopy infrastructure\.env.dev.example to infrastructure\.env.dev and fill in local-only values first."
    exit 1
}

# Docker Compose only auto-loads a file literally named ".env" in the compose
# file's directory; our vars live in .env.dev, so --env-file must be explicit.
docker compose --env-file $envFile -f $composeFile up -d

# Provision the object-storage bucket the backend's "local" Spring profile
# expects (application-local.yml's object-storage.bucket, default "lms-dev").
# Nothing in docker-compose.dev.yml creates this automatically, so a fresh
# lms_minio_data volume leaves every upload failing closed with "Uploads
# aren't available right now" (S3ObjectStorageApi's NoSuchBucket -> generic
# 503). The minio/minio server image already bundles the `mc` client, so this
# reuses the running container instead of pulling a separate minio/mc image.
$envValues = @{}
Get-Content $envFile | Where-Object { $_ -match '^\s*[^#\s][^=]*=' } | ForEach-Object {
    $key, $value = $_ -split '=', 2
    $envValues[$key.Trim()] = $value.Trim()
}
$bucket = if ($envValues.ContainsKey("OBJECT_STORAGE_BUCKET")) { $envValues["OBJECT_STORAGE_BUCKET"] } else { "lms-dev" }

Write-Host "Waiting for MinIO to accept the local alias..."
for ($i = 0; $i -lt 30; $i++) {
    docker exec lms-minio-dev mc alias set local "http://localhost:9000" $envValues["MINIO_ROOT_USER"] $envValues["MINIO_ROOT_PASSWORD"] 2>$null
    if ($?) {
        docker exec lms-minio-dev mc mb --ignore-existing "local/$bucket"
        break
    }
    Start-Sleep -Seconds 1
}
