# Deployment Architecture

Status: Living document

## 1. Scope and constraints

Per root `CLAUDE.md`: infrastructure is Docker / Docker Compose / Nginx. The backend
is one Spring Boot modular monolith (Maven-built), the frontend is Next.js. There is
no per-tenant Docker deployment for normal tenants, and no microservices split, absent
an approved ADR. This document describes deployment topology consistent with those
constraints; it does not introduce a different infrastructure model.

## 2. Deployment topology (Docker Compose based)

```
                         Internet
                            |
                            v
                     +-------------+
                     |    Nginx     |  <- TLS termination, reverse proxy,
                     | (reverse     |     routing by host/path
                     |  proxy)      |
                     +------+-------+
                     +------+-------+
                     v              v
             +---------------+ +---------------------+
             |  Next.js       | |  Spring Boot          |
             |  frontend      | |  app instance(s)      |
             |  container     | |  (N replicas,          |
             |                | |   stateless)           |
             +---------------+ +----------+------------+
                                          |
                             +------------+-------------+
                             v                          v
                     +---------------+          +---------------+
                     |  PostgreSQL    |          |     Redis      |
                     |  (single       |          |  (cache/session|
                     |  primary,      |          |  /ephemeral    |
                     |  Flyway-managed)|         |  state)        |
                     +---------------+          +---------------+
```

- **Nginx** is the single entry point: terminates TLS, routes to the frontend
  container for page routes and to the Spring Boot app for API routes (or the Next.js
  layer proxies API calls server-side -- the specific routing split is an
  implementation detail, not a deployment-architecture decision).
- **Spring Boot app containers** run as N replicas behind Nginx (or behind a Compose
  service definition scaled via `docker compose up --scale`). Nginx load-balances
  across replicas.
- **Next.js frontend** runs as its own container in this Docker Compose model,
  consistent with CLAUDE.md's Docker/Docker Compose stack.
- **PostgreSQL** and **Redis** run as their own Compose services (or, in
  staging/production, potentially as managed equivalents -- see open questions). They
  are shared by all tenants and all app replicas; no per-tenant database.

## 3. Statelessness and horizontal scaling

- Every Spring Boot app instance must be interchangeable: no in-memory session state,
  no in-JVM cache treated as authoritative, no reliance on local container filesystem
  for anything tenant data depends on (per `.claude/rules/architecture.md`,
  "Scalability guidance").
- Session/device/rate-limit state that needs to be shared across instances lives in
  Redis, not in-process.
- Horizontal scaling is achieved by increasing the Spring Boot app replica count
  behind Nginx; because instances are stateless, no sticky-session configuration is
  required at the Nginx layer.
- Video/content binaries are never stored on the application containers -- they live
  in external object/video storage (see `video-content-security.md`), which keeps app
  containers cheap to scale and replace.

## 4. Environment separation (conceptual)

At minimum, the platform distinguishes:

- **Development** -- local Docker Compose stack, synthetic/test data only (per root
  `CLAUDE.md` Safety rules: never real student/financial records, never connect to
  production databases).
- **Staging** -- a pre-production environment for integration/E2E verification, running
  the same container images and Compose/orchestration shape as production, with its
  own isolated Postgres/Redis instances and its own tenant test data.
- **Production** -- the live platform. Deploys to production are never automatic (root
  `CLAUDE.md` Safety rules: "Never deploy production automatically"; "Never merge a
  pull request without human approval").

Each environment has its own database, its own Redis instance, and its own set of
integration credentials (managed by `integration-management`'s credential vault
concept -- see `integration-architecture.md`). Environments must not share credentials
or data.

## 5. Release process (conceptual)

- Flyway migrations are applied as part of the deployment of a new Spring Boot app
  version, ahead of or alongside starting new app containers, so schema and
  application code version move together.
- Because app instances are stateless and horizontally replicated, a rolling deploy
  (bring up new-version containers, drain and remove old-version containers) is the
  expected pattern rather than a hard cutover -- this needs a concrete CI/CD mechanism
  decision (see open questions).
- Database migration history itself is a change-controlled area (root `CLAUDE.md`) --
  a deployment process must never edit or renumber an already-applied Flyway
  migration; new migrations only.

## 6. Explicitly open questions (not decided by this repo)

The following require real infrastructure decisions that neither `CLAUDE.md` nor the
source requirements have made. Do not treat any of the following as decided -- raise an
ADR when a decision is needed:

- **Cloud/hosting provider** -- no specific cloud provider (or self-hosted VPS
  provider) is named. This document assumes only "some host capable of running Docker
  Compose (or a Compose-compatible orchestrator)," not a specific vendor.
- **CI/CD pipeline tooling** -- no specific CI/CD system is selected. The release
  process described in section 5 is conceptual; the actual pipeline implementation is
  unspecified.
- **Domain/DNS provider and certificate management approach** -- **partly decided by ADR-021
  (accepted 2026-09-30):** tenant custom domains are verified by DNS (CNAME to
  `CUSTOM_DOMAIN_CNAME_TARGET` plus a TXT code) and resolved by the backend from the request host.
  Phase 1 is built. **Still open (ADR-021 phase 2):** automatic TLS for custom domains. The
  recommended option is Caddy on-demand TLS with an internal "is this domain allowed" check, with
  Cloudflare for SaaS and a cloud load balancer as alternatives, confirmed once the hosting
  provider is chosen. Until then a custom domain needs a certificate provisioned by hand.
  What the edge must do for tenant sites (platform subdomains and custom domains alike):
  - accept any host (a catch-all `server_name`) and pass it on (`Host $host` or
    `X-Forwarded-Host $host`);
  - route `/api/` to the backend (`127.0.0.1:8080`) and everything else to the frontend;
  - set `PLATFORM_DOMAIN` and `CUSTOM_DOMAIN_CNAME_TARGET` on the backend (Compose already passes
    both).

  Build the frontend with `NEXT_PUBLIC_API_BASE_URL=/api`, the default.
  `NEXT_PUBLIC_AOC_PLAYER_DOWNLOAD_URL` (https) points Students at the signed AOC Player
  installer; leave it unset until a player release has been published (ADR-025 §7).
- **Managed vs. self-hosted PostgreSQL/Redis in staging/production** -- this document
  shows them as Compose services for topology purposes; whether production actually
  runs them as managed cloud services (with Docker Compose used only for
  app/frontend/Nginx) or as self-managed containers is not yet decided.
- **Horizontal scaling automation** -- whether replica count is managed manually via
  Compose scale, or via an orchestrator with autoscaling, is not decided; Docker
  Compose alone does not provide autoscaling.
- ~~**Secrets management mechanism**~~ -- **decided by ADR-019 (accepted 2026-09-30):**
  Docker Compose secrets mounted at `/run/secrets/<NAME>` and read by Spring Boot's
  `configtree` import under a new `prod` profile that refuses to start on missing or
  dev-only critical secrets. Non-secret configuration stays in Compose `environment:`.
  (Distinct from the application-level credential vault in `integration-architecture.md`,
  which covers tenant/provider credentials stored encrypted in the database.)

Any of the above being decided in a way that changes "production deployment strategy"
is a change-controlled area per root `CLAUDE.md` and requires an ADR.

## Related

- `docs/architecture/solution-architecture.md`
- `docs/architecture/observability.md`
- `docs/architecture/backup-disaster-recovery.md`
