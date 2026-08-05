# Modular Monolith Architecture

Status: Living document

## 1. Why modular monolith

The platform is architected as a **single deployable Spring Boot application**
composed of clearly bounded domain modules, rather than as a set of independently
deployed microservices. This decision is confirmed in
`docs/requirements/source-requirements.md` (section 7, "Key architectural decisions
now confirmed": *"Modular monolith first, microservices later only if needed"*) and is
enforced as a standing architectural constraint in root `CLAUDE.md` and
`.claude/rules/architecture.md`. See `docs/adr/ADR-001-modular-monolith.md` for the
full decision record.

Rationale:

- At the current and near-term scale (multiple tenants, shared platform), the
  operational overhead of a distributed system (service discovery, distributed
  transactions, network-partition handling, per-service deployment pipelines) is not
  justified by a team/traffic profile that a single well-scaled application tier can
  serve.
- Module boundaries defined clearly *inside* one process give most of the design
  discipline benefit of service boundaries (single-responsibility ownership, explicit
  contracts) without the runtime cost of network calls between every domain
  interaction.
- If growth later genuinely requires extracting a module into its own service, having
  disciplined `api`-only cross-module contracts from day one makes that extraction a
  bounded, mechanical change rather than a rewrite (see section 6).

This is not a permanent prohibition on microservices — it is a default that must not
be overridden without an ADR (see section 5).

## 2. Confirmed backend domains

Source: `docs/requirements/source-requirements.md`, section 6, "Final module
architecture direction." Do not invent new top-level domains, or merge/split these,
without checking whether the change should be an ADR first
(`.claude/rules/architecture.md`, "When an ADR is required").

| Domain | Responsibility (one line) |
|---|---|
| `identity-access-service` | Authentication, session/token issuance and validation, device authentication, foundational identity for all other domains. |
| `tenant-management` | Tenant lifecycle (registration, approval, status, plan/feature limits, usage tracking), tenant profile/branding data ownership. |
| `user-management` | Student, Teacher, and Staff profile/account management and role assignment within a tenant. |
| `course-management` | Course/module/lesson/session structure, pricing, enrollment rules, visibility, teacher assignment, reviews toggle. |
| `content-management` | Learning materials (PDF/images/notes/attachments) organization, versioning, visibility, expiry — storage delegated externally. |
| `video-access-management` | Secure video playback: signed URL/token issuance, session/device/view-limit enforcement, watermarking coordination. |
| `live-class-management` | Zoom (or equivalent) integration for scheduling live classes, join URLs, recording management, attendance sync trigger. |
| `enrollment-management` | Enrollment lifecycle and activation — reads payment/ledger state, never order state, to decide access. |
| `payment-management` | Orders, payments, manual payment slip workflow, payment slip intelligence (duplicate/OCR checks), refunds. |
| `ledger-settlement-management` | Append-only ledger entries, tenant/tutor settlement calculation and status. |
| `attendance-management` | Class/session attendance capture (manual and Zoom-synced), attendance reporting. |
| `exam-management` | Exam creation, question bank, scheduling, marking, results, exam analytics. |
| `finance-expense-management` | Institute-side income/expense tracking, accounts, payouts, financial reports (distinct from the payment ledger). |
| `notification-management` | Email/SMS/WhatsApp/in-app notification dispatch, templates, delivery logs — consumer of events from other domains. |
| `integration-management` | Sole owner of third-party credentials/webhooks (Zoom, SMS, WhatsApp, email, payment gateway, object storage); see `integration-architecture.md`. |
| `reporting-analytics` | Cross-domain reporting/analytics, built from events/projections rather than live cross-domain joins. |
| `audit-log-management` | Immutable, append-only record of security/compliance-sensitive actions — consumer of events from other domains. |
| `support-management` | Support/helpdesk tickets (student/teacher/tenant), ticket assignment, internal notes, related-entity links. |

## 3. Package structure convention

Each domain is one top-level Java package: `com.lms.<domain>` (dashes to camelCase --
e.g. `com.lms.paymentmanagement`, `com.lms.ledgersettlementmanagement`). Inside each
domain package:

```
com.lms.<domain>
|-- api        # public service interfaces, request/response DTOs, domain events --
|              # the ONLY classes other domains may depend on
|-- web        # REST controllers for this domain only
|-- service    # interface implementations, orchestration, business rules
|-- domain     # JPA entities, value objects
|-- repository # Spring Data repositories -- never exported outside this package
`-- config     # domain-local Spring configuration
```

Placement rules:

- A new REST endpoint, entity, or repository belongs in exactly one domain package.
- If a feature seems to straddle two domains, it belongs to the domain that owns the
  primary aggregate/table; the rest is exposed through that domain's `api` interface
  to the other domain, not duplicated.
- `repository` classes are package-private or otherwise not referenced from other
  domain packages under any circumstance.

### `com.lms.common` -- the one deliberate exception

`com.lms.common` is a shared-kernel package, introduced by the Application Foundation
module, that sits alongside the 18 domain packages above. It holds no business logic,
no domain entities, and no REST endpoints -- only cross-cutting infrastructure every
domain depends on: the common API response envelope (`common.api`), global exception
handling (`common.web`), the shared application-exception hierarchy
(`common.error` -- `ApplicationException` and its subtypes, mapped to responses by
`common.web`'s handler), the base entity/audit-fields/immutable-ID strategy and the
`TenantAwareRepository<T,ID>` structural tenant-filtering mechanism from ADR-006
(`common.persistence`), the `TenantContext` abstraction (`common.tenant`), and
platform-wide Spring configuration for Postgres/Redis/Actuator/logging/security/OpenAPI
(`common.config`).

This is intentional, not an oversight of the "one top-level package per domain" rule:
ADR-006 already establishes that `TenantAwareRepository` is not owned by
`identity-access-service` or `tenant-management` -- both are expected to write
repositories *against* it, meaning the mechanism must sit below both, not nested inside
either. Every future domain module may depend on `com.lms.common`, the same as the
foundational domains, but `com.lms.common` itself must never depend on a business
domain.

## 4. Cross-module communication rules

- A module may depend only on another module's `api` package. It must never inject or
  call another domain's `repository` beans, import another domain's `domain` (entity)
  classes, or call another domain's `web` controllers internally.
- **Synchronous, in-process calls through injected `api` interfaces** are used for
  request-time reads/writes that must be consistent within the same
  transaction/response -- e.g. `enrollment-management` synchronously checking
  `payment-management`'s status via its `api` interface before activating access.
- **Domain events (published and consumed in-process)** are used for side effects that
  don't need to block the triggering request -- especially fan-out to
  `notification-management`, `audit-log-management`, and `reporting-analytics`. These
  three domains are event consumers, not domains other modules reach into directly by
  interface call for every side effect.
- No circular module dependencies. `identity-access-service` and `tenant-management`
  are foundational: any domain may depend on them, but they must never depend back on
  a business domain (course, payment, exam, etc.).
- `integration-management` owns all third-party credentials/webhooks. Other domains
  call it through its `api` interfaces rather than embedding provider SDKs or
  credentials directly -- this isolates provider swaps and credential rotation to one
  module (see `integration-architecture.md`).
- If a query seems to need a foreign domain's repository/entity, that is a signal to
  either (a) add a narrow read method to that domain's `api` interface, or (b) let
  `reporting-analytics` build its own read model from published events/projections
  rather than joining across domain schemas at request time. It is never a signal to
  reach into the other module's repository "just this once."

## 5. When an ADR is required

Raise an ADR **before**, not after, doing any of the following (in addition to the
change-controlled items in root `CLAUDE.md`):

- Extracting any confirmed domain into a separately deployable service/process.
- Giving a module its own datastore, cache, or connection pool distinct from the
  shared platform Postgres/Redis.
- Introducing a new cross-domain communication mechanism (message broker, gRPC, a
  shared library that bypasses `api` interfaces, etc.).
- Making any business domain a dependency of `identity-access-service` or
  `tenant-management` (inverting the foundational-module rule).
- Allowing a module to read/write another module's repository or entities directly as
  a "just this once" stopgap -- this is a boundary violation, not a valid shortcut, and
  needs either a proper `api` extension or an ADR explaining the exception.

## 6. Future extraction path (not proposed now)

Because cross-module coupling is already restricted to `api`-interface calls and
domain events, a future decision to extract a specific domain (e.g.
`video-access-management`, or `payment-management` + `ledger-settlement-management`
together) into its own deployable service would, in principle, involve:

- Replacing in-process `api` interface calls to that domain with a network call
  (REST/gRPC) behind the same interface contract.
- Replacing in-process event publication/consumption with the chosen message broker.
- Giving the extracted domain its own datastore/schema ownership (with a data
  migration plan).

This document does not propose or schedule any such extraction -- it exists only to
note that the current package/communication discipline is what would make an eventual
extraction tractable rather than a rewrite. Any actual extraction remains subject to
the ADR requirement in section 5 and to root `CLAUDE.md`'s prohibition on
microservices absent an approved ADR.

## Related

- `docs/adr/ADR-001-modular-monolith.md`
- `docs/architecture/solution-architecture.md`
- `docs/requirements/module-catalog.md`
