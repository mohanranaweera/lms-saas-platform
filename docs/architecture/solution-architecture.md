# Solution Architecture Overview

Status: Living document

## 1. Purpose

This document is the entry point for understanding how the multi-tenant SaaS LMS and
Institute Management System is architected. It summarizes the guiding principles that
apply across every module and links out to the detailed architecture documents that
govern specific concerns. Treat this file as the index — if a design question isn't
answered here, it should be answered in one of the linked documents; if it isn't
answered anywhere, that is a gap to raise, not a decision to make silently in code.

This document describes architecture that has already been decided (see
`docs/requirements/source-requirements.md`, section 7, "Key architectural decisions
now confirmed" and section 6, "Final module architecture direction"). It does not
reopen those decisions. Any change to the items listed under CLAUDE.md's "Change
controls" or `.claude/rules/architecture.md`'s "When an ADR is required" must go
through an ADR (`docs/adr/`) before this document or its linked documents are updated
to reflect it.

## 2. Executive summary

The platform is a single, shared, multi-tenant SaaS application serving multiple
educational institutes ("tenants") from one deployment. Each tenant gets:

- A branded student/teacher/tenant-admin experience (white-labeling, custom
  domain-ready).
- Isolated data within a shared schema (tenant_id-scoped tables, not separate
  databases or separate deployments per tenant).
- Course management, content delivery, live classes (via Zoom), attendance, exams,
  payments, and reporting, all built as modules of one backend.

The backend is a **modular monolith** written in Java 21 / Spring Boot, built and
packaged with Maven, backed by PostgreSQL (source of truth) and Redis (cache/ephemeral
state only). The frontend is a Next.js/TypeScript application. Infrastructure is
Docker/Docker Compose-based with Nginx as the entry point. There is deliberately **no
microservices split** and **no per-tenant deployment** — one platform deployment
serves all normal tenants.

## 3. Guiding principles

These principles apply platform-wide and are the lens every module design should be
checked against:

1. **Modular monolith first.** The backend is organized into clearly bounded domain
   modules (see `modular-monolith.md`) inside one deployable Spring Boot application.
   Microservices are not adopted preemptively — extracting any module into a separate
   service requires an approved ADR (`.claude/rules/architecture.md`, "When an ADR is
   required"; see `docs/adr/ADR-001-modular-monolith.md`).
2. **No microservices, no per-tenant infrastructure, without an ADR.** This includes:
   giving a module its own datastore/cache, standing up a message broker, or deploying
   a separate stack per normal tenant. CLAUDE.md is explicit that a separate Docker
   deployment per normal tenant is not acceptable.
3. **Shared-schema multi-tenancy, enforced structurally.** All tenants share the same
   application, same database, same tables. Every tenant-owned table carries a
   `NOT NULL tenant_id` foreign key. Tenant identity is resolved once, server-side,
   from the authenticated context — never trusted from client input. See
   `multi-tenancy.md` and `docs/adr/ADR-002-shared-database-tenancy.md`.
4. **External video/content storage, never self-hosted media.** `video-access-management`
   and `content-management` integrate with an external secure video/object storage
   provider and work in terms of signed URLs/tokens. The application VPS never streams
   or stores binary media as a source of truth. See `video-content-security.md`.
5. **Stateless application instances.** No in-memory session state, no in-JVM cache
   assumed authoritative, no local filesystem dependency for tenant data. This is what
   allows the backend to scale horizontally behind Nginx without sticky sessions. See
   `deployment-architecture.md`.
6. **Redis is ephemeral, not authoritative.** Caching, rate limiting, device/session
   tracking, short-lived tokens — yes. Source of truth for tenant, financial, or
   enrollment state — no. See `database-architecture.md`.
7. **Asynchronous fan-out for high-volume side effects.** Notification dispatch
   (email/SMS/WhatsApp/in-app), audit logging, and analytics/reporting consume domain
   events rather than being called synchronously inline with the triggering request,
   so a burst of notifications or reporting load doesn't block transactional request
   threads. See `modular-monolith.md` and `observability.md`.
8. **Payment and enrollment integrity is schema-enforced, not just service-enforced.**
   Enrollment only activates from verified, persisted payment/approval evidence.
   Ledger and payment records are append-only. See `payment-ledger.md` and
   `enrollment-access.md`.
9. **Every architectural decision that touches a change-controlled area is recorded as
   an ADR before implementation**, not retrofitted afterward. Change-controlled areas
   (per root `CLAUDE.md`): multi-tenancy strategy, authentication architecture, payment
   ledger rules, enrollment activation rules, production deployment strategy, database
   migration history, approved API contracts.

## 4. High-level component view

```
                        +---------------------------+
                        |        Browser /           |
                        |   (future) Mobile Client    |
                        | Student / Teacher /         |
                        | Tenant Admin / Platform     |
                        | Admin                       |
                        +-------------+---------------+
                                      | HTTPS
                        +-------------v---------------+
                        |           Nginx              |
                        |  (reverse proxy / TLS         |
                        |   termination / routing)      |
                        +-------+--------------+--------+
                                |              |
                 +--------------v---+   +------v---------------+
                 |   Next.js          |   |  Spring Boot          |
                 |   frontend         |   |  modular monolith     |
                 |   (SSR/SSG +       |   |  (N stateless          |
                 |   client app)      |   |  instances, horiz.     |
                 |                    |   |  scaled)               |
                 +--------------------+   +----+----------+--------+
                                               |          |
                                   +-----------v--+   +---v-----------+
                                   | PostgreSQL     |   |  Redis         |
                                   | (source of     |   | (cache/session |
                                   |  truth, Flyway |   |  /ephemeral    |
                                   |  migrated)     |   |  state only)   |
                                   +----------------+   +----------------+

        Spring Boot app also talks outward (via integration-management only) to:
        Zoom, SMS provider, WhatsApp Business API, Email/SMTP provider,
        Payment gateway, External object/video storage
```

Inside the Spring Boot process, the domain modules described in
`modular-monolith.md` communicate only through each other's `api` packages or via
in-process domain events — never by reaching into another module's repository or
entity classes. See that document for the full module list and communication rules.

## 5. Related architecture documents

| Document | Covers |
|---|---|
| `system-context.md` | External actors and external systems the platform talks to; system context diagram. |
| `modular-monolith.md` | Full domain list, package structure convention, cross-module communication rules, ADR triggers for splitting a module out. |
| `multi-tenancy.md` | Tenant resolution, isolation enforcement, and shared-schema data model mechanics. |
| `authentication-authorization.md` | Authentication/authorization architecture, device auth, role model. |
| `database-architecture.md` | PostgreSQL/Redis roles, schema conventions, append-only/schema-enforced invariants, Flyway usage. |
| `payment-ledger.md` | Payment/ledger/settlement architecture. |
| `enrollment-access.md` | Enrollment activation and access/expiry rules. |
| `video-content-security.md` | Secure video/content delivery architecture. |
| `integration-architecture.md` | Third-party integration ownership (`integration-management`), adapter pattern, webhook handling, credential vault. |
| `deployment-architecture.md` | Docker Compose topology, horizontal scaling, environment separation. |
| `observability.md` | Logging, metrics, tracing, alerting; distinction from business audit logging. |
| `backup-disaster-recovery.md` | PostgreSQL/object storage backup strategy, Flyway history retention, DR approach. |

## 6. Non-functional posture (summary)

- **Multi-tenancy**: shared schema, structural tenant filtering, mandatory cross-tenant
  tests (see `.claude/rules/tenancy.md`).
- **Scalability**: stateless app tier, tenant_id-leading indexes, async fan-out,
  external video/object storage (see `.claude/rules/architecture.md` "Scalability
  guidance").
- **Security**: device authentication, signed/short-lived video access tokens, audit
  logging of privileged actions (see `.claude/rules/security.md`).
- **Financial integrity**: append-only payment/ledger records, schema-enforced state
  machines, enrollment activation traceable to a specific confirmed payment or
  approved manual evidence row (see `.claude/rules/payments.md`).

## Related

- `docs/requirements/non-functional-requirements.md`
- `docs/adr/` (all ADRs)
