# Runbook: Production Secrets (ADR-019)

For operators setting up or maintaining a **staging or production** host. Mechanism and reasons:
`docs/adr/ADR-019-production-secrets-management.md`. Every secret name, how to generate it and what
breaks without it: `infrastructure/prod-secrets.reference`.

> **Rules**
> - Never put a secret in git, in `docker-compose.prod.yml`, in the `--env-file`, in a ticket or in chat.
> - Each environment (staging, production) gets its **own** values. Never copy between them or from dev.
> - Nothing here deploys anything by itself. A production deployment always needs explicit human
>   approval (root `CLAUDE.md`).

## How it works (one paragraph)

Each secret is one file in a root-only folder on the host (`/etc/lms/secrets/<NAME>`). Docker
Compose mounts each file read-only into only the containers that need it, at `/run/secrets/<NAME>`.
The backend runs with `SPRING_PROFILES_ACTIVE=prod`, whose config tree import turns every file into a
setting with the same name. If `DB_PASSWORD`, `JWT_SECRET`, `MEDIA_CREDENTIALS_ENCRYPTION_KEY` or
`PAYMENT_CREDENTIALS_ENCRYPTION_KEY` is missing, is a development value, or (for the two keys) isn't
32 bytes, the backend **refuses to start** and names the problem secret in the log. It never logs
the value. A trailing newline in a file is fine.

## 1. First-time setup on a new host

Run as root (or with `sudo`) on the host. Use `/etc/lms/secrets` unless you have a reason not to; if
you change it, set `LMS_SECRETS_HOST_DIR` in the non-secret env file.

```bash
# 1. The folder: only root can list or read it.
install -d -m 700 -o root -g root /etc/lms/secrets
cd /etc/lms/secrets
umask 077                      # every file created below is 600

# 2. Generated secrets (random, never typed by a person).
openssl rand -base64 24 > DB_PASSWORD
openssl rand -base64 24 > REDIS_PASSWORD
openssl rand -base64 48 > JWT_SECRET
openssl rand -base64 48 > VIDEO_PLAYBACK_TOKEN_SECRET
openssl rand -base64 32 > MEDIA_CREDENTIALS_ENCRYPTION_KEY     # exactly 32 bytes - do not change the 32
openssl rand -base64 32 > PAYMENT_CREDENTIALS_ENCRYPTION_KEY   # exactly 32 bytes - do not change the 32
openssl rand -base64 32 > LIVE_CLASS_PROVIDER_WEBHOOK_SECRET

# 3. Secrets issued by a provider: paste the value with a text editor, or leave the file EMPTY
#    until that feature is used. Compose needs every listed file to exist; an empty file just
#    keeps that feature switched off ("not available" / 503), it does not stop the backend.
: > ZOOM_OAUTH_CLIENT_SECRET        # Zoom Marketplace app
: > ZOOM_WEBHOOK_SECRET_TOKEN       # Zoom app > Event subscriptions > Secret Token
: > GOOGLE_OAUTH_CLIENT_SECRET      # Google Cloud OAuth client (web application)
: > VIMEO_OAUTH_CLIENT_SECRET       # Vimeo developer app
: > PAYMENT_GATEWAY_WEBHOOK_SECRET
: > OBJECT_STORAGE_SECRET_KEY       # object storage provider
: > MAIL_PASSWORD                   # Google "App Password" for the mail account

# 4. Check: 14 files, all -rw------- root root, folder drwx------.
ls -la /etc/lms/secrets
```

To fill in a provider secret later, open the file with an editor (`sudo nano /etc/lms/secrets/ZOOM_OAUTH_CLIENT_SECRET`),
paste the value, save, then restart the backend (section 4).

Then create the **non-secret** env file, for example `/etc/lms/prod.env` (`chmod 640`). It holds no
secret, only settings such as:

```bash
BACKEND_IMAGE=registry.example.com/lms-backend:1.4.0
FRONTEND_IMAGE=registry.example.com/lms-frontend:1.4.0
PLATFORM_DOMAIN=example.com
POSTGRES_DB=lms
POSTGRES_USER=lms_app
ZOOM_OAUTH_CLIENT_ID=...          # client IDs are not secret
GOOGLE_OAUTH_CLIENT_ID=...
VIMEO_OAUTH_CLIENT_ID=...
```

Starting the stack (only once a deployment has been approved):

```bash
docker compose -f infrastructure/docker-compose.prod.yml --env-file /etc/lms/prod.env up -d
docker compose -f infrastructure/docker-compose.prod.yml --env-file /etc/lms/prod.env logs backend | grep -i "refusing to start"
```

No output from the second command, and a healthy `curl -s http://127.0.0.1:8080/actuator/health`,
means the secrets were accepted.

## 2. Back up the encryption keys (do this before first use)

`MEDIA_CREDENTIALS_ENCRYPTION_KEY` and `PAYMENT_CREDENTIALS_ENCRYPTION_KEY` encrypt data stored in the
database. **If either is lost, that data can never be decrypted again**:

| Key lost | Consequence |
|---|---|
| `MEDIA_CREDENTIALS_ENCRYPTION_KEY` | Every teacher's Zoom/YouTube/Vimeo connection and every tenant's DynTube/Bunny.net account must be reconnected; since Wave 11 (ADR-023 J1) every tenant's SMS / WhatsApp provider credentials must also be re-entered |
| `PAYMENT_CREDENTIALS_ENCRYPTION_KEY` | Every tenant's PayHere account must be entered again; online payments stop until then |

So, for each environment:
1. Copy both key values into the organisation's password manager (restricted vault, named e.g.
   "LMS production - encryption keys"), **or** onto offline storage kept in a locked place.
2. Record who has access. At least two people should be able to reach the backup.
3. Test once: compare the backup against the host file, e.g.
   `sudo sha256sum /etc/lms/secrets/MEDIA_CREDENTIALS_ENCRYPTION_KEY` against the same hash of the
   backup. Compare hashes, never display the key.

Other secrets don't need this level of backup. They can be reissued (see section 3) at the cost of a
restart. `JWT_SECRET` and `VIDEO_PLAYBACK_TOKEN_SECRET` are simply regenerated.

Database backups (`backup-disaster-recovery.md`) **do not include** these keys. A restored database
is useless without the matching encryption keys, so store and restore them together.

## 3. Rotating a secret

General steps: replace the file content, then recreate the containers that use it (section 4).

| Secret | How to rotate | Side effects |
|---|---|---|
| `JWT_SECRET` | New random value | Every user is signed out and must log in again |
| `VIDEO_PLAYBACK_TOKEN_SECRET` | New random value | Video playback links already issued stop working (they are short-lived anyway) |
| `DB_PASSWORD` | Change the password **in PostgreSQL first** (`ALTER ROLE lms_app PASSWORD '…'` via `psql` in the postgres container), then update the file, then restart the backend | Brief backend restart. The postgres container only reads the file when a **new** database is created |
| `REDIS_PASSWORD` | Update the file, recreate `redis` and `backend` together | Cached sessions/rate limits reset; users may need to log in again |
| Provider client secrets / webhook tokens | Generate a new one at the provider, update the file, restart the backend, then revoke the old one at the provider | None if done in that order |
| `MAIL_PASSWORD`, `OBJECT_STORAGE_SECRET_KEY` | New credential at the provider, update the file, restart, revoke the old one | None if done in that order |
| `MEDIA_CREDENTIALS_ENCRYPTION_KEY`, `PAYMENT_CREDENTIALS_ENCRYPTION_KEY` | **Do not rotate** without a separate, approved plan. No re-encryption tool exists yet, so a new key makes every stored credential unreadable (see section 2) | Everything encrypted with the old key must be re-entered |

Rotate immediately (don't wait for a schedule) if a secret may have leaked: pasted somewhere,
committed, shown on screen, or held by someone who has left.

## 4. Applying a changed secret

A running container reads secret files only at start-up, so after any change:

```bash
docker compose -f infrastructure/docker-compose.prod.yml --env-file /etc/lms/prod.env up -d --force-recreate backend
# for REDIS_PASSWORD, recreate both:
docker compose -f infrastructure/docker-compose.prod.yml --env-file /etc/lms/prod.env up -d --force-recreate redis backend
```

Then check the backend log for `Refusing to start` and the health endpoint, as in section 1.

## 5. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Backend log: `Refusing to start with the 'prod' profile (ADR-019): X is missing` | File `X` absent or empty | Create or fill `/etc/lms/secrets/X`, restart |
| `... X is a development-only value` | A value copied from the local dev configuration (contains `dev-only`, `change_this`, `placeholder` ...) | Generate a real value (section 1), restart |
| `... must be Base64 for exactly 32 bytes` | Encryption key typed by hand or generated with the wrong length | `openssl rand -base64 32 > …` for a **new** environment only. For an environment already in use, restore the original key from the backup (section 2) |
| `The 'local' profile must never be active together with 'prod'` | `SPRING_PROFILES_ACTIVE` contains `local` | Set it to `prod` only |
| Compose: `secret file … not found` | A listed secret file doesn't exist | Create it; an empty file is allowed for unused features |
| Zoom/YouTube/Vimeo "not available" | Client secret file empty, or client ID not in the env file | Fill both, restart |

## Related

- `docs/adr/ADR-019-production-secrets-management.md`
- `infrastructure/docker-compose.prod.yml`, `infrastructure/prod-secrets.reference`
- `backend/src/main/resources/application-prod.yml`
- `docs/architecture/deployment-architecture.md`, `docs/architecture/backup-disaster-recovery.md`
