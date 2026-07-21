# ADR-001: Modular Monolith Architecture Style

## Status

Accepted (confirmed in `CLAUDE.md` and `.claude/rules/architecture.md`).

## Context

The platform is a multi-tenant SaaS LMS / Institute Management System covering 18
confirmed backend domains (identity-access-service, tenant-management,
user-management, course-management, content-management, video-access-management,
live-class-management, enrollment-management, payment-management,
ledger-settlement-management, attendance-management, exam-management,
finance-expense-management, notification-management, integration-management,
reporting-analytics, audit-log-management, support-management).

A system with this many distinct business domains could reasonably be built as
independently deployable microservices. However, at this stage the platform has no
proven need for independent scaling, independent deployment cadence, or
polyglot technology per domain, and the team has no operational experience running a
distributed system of this shape yet (service discovery, distributed transactions,
cross-service observability, network-partition failure modes).

## Decision

Build the backend as a single Spring Boot (Java 21, Maven) **modular monolith**:

- One deployable application, one JVM process type, scaled horizontally as identical
  stateless instances behind Nginx.
- Each of the 18 confirmed domains is a top-level Java package (`com.lms.<domain>`)
  with an internal `api` / `web` / `service` / `domain` / `repository` / `config`
  structure (see `.claude/rules/architecture.md`).
- Cross-domain calls happen in-process through a domain's `api` package
  (interfaces/DTOs/events) only — never through another domain's repository, entity,
  or controller.
- Microservices are **not** adopted now. Extracting any domain into a separately
  deployable service requires a new ADR (per the "When an ADR is required" section of
  `.claude/rules/architecture.md`), triggered by a concrete, demonstrated need (e.g.
  a domain's load/scaling profile diverges sharply from the rest of the platform).

## Consequences

**Positive**

- Single deployable artifact simplifies local development, CI/CD, and operational
  troubleshooting.
- In-process calls between domains are simple method calls — no network latency,
  serialization overhead, or partial-failure handling for internal calls.
- A single shared PostgreSQL/Redis stack (see ADR-002) avoids distributed-transaction
  complexity entirely for the confirmed MVP/Phase 2/3 scope.
- Refactoring domain boundaries is cheap while requirements are still evolving —
  moving a class between packages is far cheaper than re-drawing a service boundary
  already exposed over a network.

**Negative / trade-offs accepted**

- All domains currently scale together (one instance count for the whole app) even
  though load profiles will differ (e.g. video-access-management and
  notification-management may see very different traffic shapes than
  finance-expense-management). This is an accepted trade-off until a domain's
  operational profile forces reconsideration.
- A bug or resource leak in one domain can, in principle, affect the whole process
  (shared JVM heap, shared thread pools) — mitigated by code review discipline on the
  `api`-only cross-module rule, not by process isolation.
- Package-boundary discipline (no reaching into another domain's `repository`/`domain`
  classes) is enforced by review convention, not by a hard compiler/build boundary.
  This is a known risk to revisit if the team grows significantly.

## Alternatives considered

- **Microservices from day one** — rejected: no demonstrated scaling/deployment need
  yet, and it would add distributed-systems complexity (service discovery, distributed
  tracing, eventual consistency across domains) before the product/business model is
  validated.
- **Single undifferentiated Spring Boot app with no package-level domain boundaries**
  — rejected: without the `api`/`web`/`service`/`domain`/`repository` discipline per
  domain, the codebase would accumulate cross-domain coupling that makes a future
  extraction (if ever needed) far more expensive.

## Related

- `docs/architecture/modular-monolith.md`
- `docs/architecture/solution-architecture.md`
- `.claude/rules/architecture.md`
