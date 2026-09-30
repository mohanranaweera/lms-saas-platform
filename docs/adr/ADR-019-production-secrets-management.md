# ADR-019: Production Secrets Management — Docker Compose Secrets Read by Spring Boot `configtree`

## Status

**Accepted (2026-09-30)** by the project owner ("ADR-019 approved"). Originally proposed the
same day at the owner's request ("draft ADR-019 with option 2"), choosing Docker Compose
secrets read by Spring Boot `configtree` over the alternatives below.

This ADR resolves an open question that `docs/architecture/deployment-architecture.md` §6 lists
as undecided: the **secrets management mechanism**, meaning how the platform's own deployment
secrets reach its containers. Choosing it changes the **production deployment strategy**, a
change-controlled area in root `CLAUDE.md`. It also answers the backup question that
`docs/architecture/backup-disaster-recovery.md` §3 leaves to "whatever secrets management
mechanism is selected".

It does **not** cover tenant credentials that tenants enter themselves in the UI (PayHere,
DynTube and Bunny.net keys). Those stay encrypted in the database (ADR-017, ADR-018). This ADR
covers the keys that protect them.

## Context

- The stack is fixed: Docker, Docker Compose and Nginx, with the Spring Boot backend and the
  Next.js frontend as separate containers (ADR-004). The cloud or hosting provider, CI/CD
  tool and managed-vs-self-hosted Postgres/Redis are **still open** (deployment-architecture
  §6). The chosen mechanism must work on any Docker Compose host.
- The backend reads every secret through a Spring property placeholder, for example
  `${MEDIA_CREDENTIALS_ENCRYPTION_KEY:}`. It never reads a `.env` file itself. So what's
  needed is a way to **deliver** values; the code doesn't have to change how it **reads**
  them.
- In `application.yml`, secrets default to **empty**, and each feature **fails closed**
  (503, or "not available") when one is missing.
- **There is no production profile yet.** The database connection (`DB_URL`, `DB_USERNAME`,
  `DB_PASSWORD`), Redis (`REDIS_HOST`, `REDIS_PORT`) and the login-token signing secret
  (`JWT_SECRET`) are only defined in `application-local.yml`. That profile also contains
  **dev-only fallback keys**: the media and payment encryption keys and a JWT placeholder.
  Running it in production would silently use fake keys.
- Plain environment variables (`environment:` or `env_file:`) work, but every value shows in
  `docker inspect` and in the container's `/proc/<pid>/environ`. It can also be passed on to
  child processes and turn up in crash reports or logs.

## Decision

### 1. Secrets are delivered as Docker Compose secrets and mounted as files

- Each secret is a separate file on the production host, outside the repository, in a
  directory only root can read (for example `/etc/lms/secrets/`, directory `700`, files
  `600`).
- `docker-compose.prod.yml` declares them under top-level `secrets:` and grants each service
  **only the secrets it needs**. Compose mounts them read-only at `/run/secrets/<NAME>`.
- The file name is the **exact placeholder name** the application already uses
  (`/run/secrets/JWT_SECRET`, `/run/secrets/ZOOM_OAUTH_CLIENT_SECRET`, …), so no property is
  renamed.

### 2. Spring Boot reads them with a config tree import

In the new production profile (see Decision 4):

```yaml
spring:
  config:
    import: "optional:configtree:/run/secrets/"
```

Spring Boot turns each file in that directory into a property named after the file, with the
file's contents as its value. An existing placeholder such as `${JWT_SECRET:}` then resolves
from the mounted file, with no code change. `optional:` lets a container without secrets
(for example the frontend, or a one-off tool) start. Missing values still fall back to the
existing empty defaults and fail closed.

*Confirm while implementing:* the exact `configtree` behaviour on the Spring Boot version in
`pom.xml`, especially that placeholders resolve from config-tree properties and how trailing
newlines in secret files are handled. (The current docs couldn't be fetched while drafting.)

### 3. What is a secret and what is plain configuration

| Kind | Values (current names) | Delivery |
|---|---|---|
| **Secrets** (file-mounted) | `DB_PASSWORD`, `JWT_SECRET`, `VIDEO_PLAYBACK_TOKEN_SECRET`, `MEDIA_CREDENTIALS_ENCRYPTION_KEY`, `PAYMENT_CREDENTIALS_ENCRYPTION_KEY`, `PAYMENT_GATEWAY_WEBHOOK_SECRET`, `LIVE_CLASS_PROVIDER_WEBHOOK_SECRET`, `ZOOM_WEBHOOK_SECRET_TOKEN`, `ZOOM_OAUTH_CLIENT_SECRET`, `GOOGLE_OAUTH_CLIENT_SECRET`, `VIMEO_OAUTH_CLIENT_SECRET`, `OBJECT_STORAGE_SECRET_KEY`, `MAIL_PASSWORD`, and a Redis password once Redis requires one | Docker secret → `/run/secrets/<NAME>` |
| **Plain configuration** | `SPRING_PROFILES_ACTIVE`, `DB_URL`, `DB_USERNAME`, `REDIS_HOST`, `REDIS_PORT`, `MEDIA_OAUTH_CALLBACK_BASE_URL`, `MEDIA_TENANT_URL_TEMPLATE`, `MEDIA_OAUTH_ERROR_URL`, `ZOOM_OAUTH_CLIENT_ID`, `GOOGLE_OAUTH_CLIENT_ID`, `VIMEO_OAUTH_CLIENT_ID`, `OBJECT_STORAGE_ENDPOINT`, `OBJECT_STORAGE_REGION`, `OBJECT_STORAGE_BUCKET`, `OBJECT_STORAGE_ACCESS_KEY`, `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_FROM_ADDRESS`, `PAYHERE_NOTIFY_URL`, `PAYHERE_RETURN_URL_TEMPLATE` | `environment:` in `docker-compose.prod.yml` |

The rule for anything added later: **if a value lets someone sign, decrypt, authenticate or
impersonate, it is a secret.** Every new secret is added to this table in the same change.

### 4. A production profile that can never use dev fallbacks

- Add `application-prod.yml`. It declares the config tree import and binds the database,
  Redis and JWT placeholders that today exist only in `application-local.yml`, **with no
  defaults for secrets**.
- Production containers run with `SPRING_PROFILES_ACTIVE=prod`, and the **`local` profile is
  never active** outside developer machines.
- Add a startup guard in the prod profile: if a **critical** secret (`DB_PASSWORD`,
  `JWT_SECRET`, the two encryption keys) is missing, or equals a known dev-only value, the
  application **refuses to start**. Features that already fail closed on their own (OAuth
  providers, video playback) keep doing so and do not block startup.

### 5. Key lifetime and backup

- Each environment (staging, production) gets its **own** generated values, never shared with
  another environment or with dev (deployment-architecture §4).
- Encryption keys are generated once (`openssl rand -base64 32`) and **backed up securely,
  offline or in a password manager with restricted access**. Losing or changing:
  - `MEDIA_CREDENTIALS_ENCRYPTION_KEY` makes every teacher and tenant media connection
    unreadable, and they all have to be reconnected;
  - `PAYMENT_CREDENTIALS_ENCRYPTION_KEY` makes every tenant PayHere account unreadable.
- Other secrets (signing secrets, provider client secrets, passwords) can be reissued at the
  source if lost. Their backup protects availability, not data.
- Rotation: replace the file on the host, then restart the affected service (`docker compose
  up -d --force-recreate backend`). A running container picks up a changed file only on
  restart. Rotating `JWT_SECRET` signs every user out. Rotating an **encryption** key
  additionally needs a re-encryption step that doesn't exist yet. Until one is built,
  rotating it is a reconnect-everything event and needs its own approval.

### 6. Who puts the files on the host

Until a CI/CD tool is chosen (still open), an authorised operator writes the files by hand
over SSH, following a runbook. Once CI/CD is chosen, its protected environment secrets write
the same files during a **human-approved** deploy (root `CLAUDE.md`: production is never
deployed automatically). The files stay the single delivery point, so the application side
doesn't change.

### Illustrative `docker-compose.prod.yml` fragment (placeholders only)

```yaml
services:
  backend:
    image: lms-backend:${RELEASE_TAG}
    environment:
      SPRING_PROFILES_ACTIVE: prod
      DB_URL: jdbc:postgresql://postgres:5432/lms
      DB_USERNAME: lms_app
      MEDIA_OAUTH_CALLBACK_BASE_URL: https://api.example.com
      MEDIA_TENANT_URL_TEMPLATE: https://{subdomain}.example.com
    secrets:
      - DB_PASSWORD
      - JWT_SECRET
      - MEDIA_CREDENTIALS_ENCRYPTION_KEY
      - PAYMENT_CREDENTIALS_ENCRYPTION_KEY
      - ZOOM_OAUTH_CLIENT_SECRET
      # …every backend secret from Decision 3

secrets:
  DB_PASSWORD:
    file: /etc/lms/secrets/DB_PASSWORD
  JWT_SECRET:
    file: /etc/lms/secrets/JWT_SECRET
  # …one entry per secret
```

The frontend container is granted no backend secrets.

## Consequences

**Positive**
- Secrets are not environment variables. They don't appear in `docker inspect`, process
  environments, or inherited environments of child processes.
- Each container sees only the secrets granted to it.
- Uses only Docker Compose and Spring Boot features already in the stack. No new service and
  no vendor lock-in, so it stays valid whichever host is chosen.
- No application code or property names change. The existing fail-closed defaults still
  apply.
- The new prod profile and startup guard close a real hole: today nothing stops production
  from starting on the `local` profile's fake keys.

**Negative**
- Secrets are still plain text **on the host disk**. Anyone with root on the host can read
  them, so host hardening and access control matter. (A cloud secret manager or Vault would
  reduce this; see Alternatives.)
- Compose file-based secrets have **no built-in rotation, versioning or access audit**. That
  depends on the runbook until a manager is adopted.
- Operators must keep the host files, the Compose `secrets:` list and Decision 3's table in
  sync; a secret forgotten in one place fails closed at runtime or at startup.

## Alternatives considered

1. **`env_file:` on the host.** Simplest, but every secret becomes an environment variable,
   visible in `docker inspect` and process environments. Rejected as the primary mechanism;
   acceptable for the plain-configuration values in Decision 3.
2. **Cloud secret manager** (AWS Secrets Manager or SSM, GCP Secret Manager, Azure Key Vault)
   **or HashiCorp Vault.** Best rotation, versioning and audit. Rejected **for now** because
   it depends on the undecided hosting provider and adds a service to operate. It can replace
   Decision 6's delivery step later (the manager writes the same `/run/secrets` files or feeds
   Spring directly) without changing Decisions 2–4. Revisit when the hosting provider is
   chosen.
3. **CI/CD injection only** (for example repository or environment secrets written into the
   container environment at deploy). Rejected as the primary mechanism, since it still ends in
   environment variables. Adopted as the future **delivery** step in Decision 6.
4. **Baking secrets into images.** Rejected outright: secrets would live in image layers and
   registries.

## Required follow-up if accepted

1. `application-prod.yml`: config tree import, database/Redis/JWT placeholders, no secret
   defaults, and the startup guard from Decision 4, with tests that the prod profile refuses
   to start without the critical secrets or with dev values. (`implement-backend`)
2. `infrastructure/docker-compose.prod.yml` with placeholders only, and a reference file
   listing every secret name with **no values**. (`devops-local`; no production deployment as
   part of this.)
3. An operator runbook: creating the secrets directory, generating each value, backing up
   the encryption keys, rotation steps, and what breaks if each value is missing.
4. Documentation:
   - `docs/architecture/deployment-architecture.md` §6: mark "Secrets management mechanism"
     as decided by this ADR.
   - `docs/architecture/backup-disaster-recovery.md` §3: point secrets backup at Decision 5.
   - `docs/architecture/integration-architecture.md` §9 ("registering the provider apps"):
     production values go in Docker secrets.
5. Revisit Alternative 2 when the hosting provider ADR is written.

## Related

- `docs/architecture/deployment-architecture.md` (§4 environment separation, §6 open questions)
- `docs/architecture/backup-disaster-recovery.md` (§3 secrets backup)
- `docs/architecture/integration-architecture.md` (§5 credential vault concept, §9 provider apps)
- ADR-004 (separate Next.js and Spring Boot applications)
- ADR-007 (authentication tokens; `JWT_SECRET`)
- ADR-008 (video content protection; `VIDEO_PLAYBACK_TOKEN_SECRET`)
- ADR-017 (tenant payment gateway accounts; `PAYMENT_CREDENTIALS_ENCRYPTION_KEY`)
- ADR-018 (media provider connections; `MEDIA_CREDENTIALS_ENCRYPTION_KEY`, OAuth app secrets)
