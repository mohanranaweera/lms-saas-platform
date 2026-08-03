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
