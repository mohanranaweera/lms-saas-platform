---
paths:
  - "backend/**"
---

# Architecture Rules

These rules apply on top of the always-loaded root `CLAUDE.md`. They govern how the
confirmed backend domain list is realized as a modular monolith, how modules may talk
to each other, and what forces a module boundary to become a real service boundary.

## Confirmed backend domains

The backend is organized into these domains (source: `docs/requirements/source-requirements.md`,
"Final module architecture direction"). Do not invent new top-level domains or merge/split
these without checking whether the change should instead be an ADR (see "When an ADR is
required" below):

identity-access-service, tenant-management, user-management, course-management,
content-management, video-access-management, live-class-management,
enrollment-management, payment-management, ledger-settlement-management,
attendance-management, exam-management, finance-expense-management,
notification-management, integration-management, reporting-analytics,
audit-log-management, support-management

## Package structure

- One top-level Java package per domain, e.g. `com.lms.<domain>` (dashes to camelCase,
  e.g. `paymentmanagement`, `ledgersettlementmanagement`).
- Inside each domain package, separate:
  - `api` — the only classes other domains may depend on: public service interfaces,
    request/response DTOs, domain events. This is the module's contract.
  - `web` — REST controllers for that domain (never call another domain's controllers
    or `web` package from anywhere).
  - `service` — interface implementations, orchestration, business rules.
  - `domain` — JPA entities, value objects.
  - `repository` — Spring Data repositories. **Never public/exported outside the domain
    package** (package-private or explicitly not referenced from other domains).
  - `config` — domain-local Spring configuration.
  - `support` — additionally permitted: owner/access-guard classes shared across a
    domain's own `service` classes (e.g. a single `FooAccessGuard` reused by several
    services in the same domain to apply one consistent authorization/status-code
    rule), kept out of `service` so it isn't mistaken for a domain-events-publishing
    orchestration class.
- A new REST endpoint, entity, or repository belongs in exactly one domain package. If a
  feature seems to straddle two domains, pick the domain that owns the primary
  aggregate/table and expose the rest via that domain's `api` service interface.

## Cross-module communication rules

- A module may depend only on another module's `api` package (interfaces, DTOs, events).
  It must never:
  - inject or call another domain's `repository` beans,
  - import another domain's `domain` (entity) classes,
  - call another domain's `web` controllers internally.
- Prefer synchronous calls through injected `api` service interfaces for request-time
  reads/writes that must be consistent within the same transaction/response
  (e.g. `enrollment-management` calling `payment-management`'s status-check interface).
- Prefer domain events (published in-process, consumed via listeners) for side effects
  that do not need to block the triggering request, especially fan-out to
  `notification-management`, `audit-log-management`, and `reporting-analytics`. These
  three domains should be consumers of events from other domains, not domains that
  other modules reach into directly.
- Avoid circular module dependencies. Treat `identity-access-service` and
  `tenant-management` as foundational — they may be depended on by any other domain,
  but must not depend back on business domains (course, payment, exam, etc.).
- `integration-management` owns all third-party credentials/webhooks (Zoom, SMS,
  WhatsApp, email, payment gateway, object storage). Other domains call it through its
  `api` interfaces rather than embedding provider SDKs/credentials directly — this keeps
  provider swaps and credential rotation isolated to one module.
- If you find yourself needing a foreign domain's repository or entity to satisfy a
  query, that is a signal to either (a) add a narrow read method to that domain's `api`
  interface, or (b) let `reporting-analytics` build its own read model from published
  events/projections rather than joining across domain schemas at request time.

## When an ADR is required

Raise an ADR before, not after, doing any of the following (this is in addition to the
change-controlled items already listed in root `CLAUDE.md`):

- Extracting any of the above domains into a separately deployable service/process.
- Giving a module its own datastore, cache, or connection pool distinct from the shared
  platform Postgres/Redis.
- Introducing a new cross-domain communication mechanism (message broker, gRPC, shared
  library that bypasses `api` interfaces, etc.).
- Making any business domain a dependency of `identity-access-service` or
  `tenant-management` (i.e. inverting the foundational-module rule above).
- Allowing a module to read/write another module's repository or entities directly as a
  stopgap ("just this once") — this is a boundary violation, not a valid shortcut, and
  needs either a proper `api` extension or an ADR explaining the exception.

## Scalability guidance

- **Video/content storage is external, never self-hosted on the application VPS.**
  `video-access-management` and `content-management` must integrate with an external
  secure video/object storage provider and work in terms of signed URLs / tokens, not by
  streaming or storing binary media through the Spring Boot app itself.
- **All application instances must be stateless.** No in-memory session data, no
  in-JVM caches that are assumed to be authoritative, no local file storage for anything
  tenant data depends on. This is required so the backend can run multiple horizontally
  scaled instances behind Nginx without sticky sessions.
- **Redis is a cache/ephemeral-state layer, not a source of truth.** Use it for caching,
  rate limiting, device/session tracking, and short-lived tokens — but any data that
  must survive a Redis flush or be authoritative for tenant/financial/enrollment state
  belongs in PostgreSQL.
- `live-class-management` integrates with Zoom (or equivalent) rather than hosting a
  media/streaming server — do not propose self-hosted conferencing/streaming
  infrastructure without an ADR.
- `notification-management` (email/SMS/WhatsApp/in-app) and other high-fan-out work
  should be handled asynchronously (event-driven, queued/background execution) so a
  burst of notifications does not block request-handling threads as tenant count grows.
- `reporting-analytics` should not be designed as ad hoc live joins across every other
  domain's tables at request time; favor read models built from domain events, or
  scheduled aggregation, so reporting load doesn't degrade transactional workloads as
  data volume grows.
- Design each domain's read paths assuming many tenants share the same tables (per the
  shared-schema multi-tenant model already fixed in root `CLAUDE.md`) — indexes and
  query plans should be validated with `tenant_id` as a leading filter, since table size
  grows with total platform tenants/courses, not per-tenant.
