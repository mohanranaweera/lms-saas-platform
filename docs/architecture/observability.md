# Observability

Status: Living document

## 1. Purpose and scope

This document describes the operational observability approach for the stateless
Spring Boot modular monolith and the Next.js frontend: logging, metrics, tracing, and
alerting. It is deliberately distinct from -- and must not be conflated with --
`audit-log-management`, which is a business/compliance record, not an operational
tool. See section 5 for that distinction.

## 2. Logging

- All application logs are structured (e.g. JSON) and written to stdout/stderr from
  each container, per standard container logging practice -- never to a local file
  path on the container, since app instances are stateless and disposable
  (`.claude/rules/architecture.md`, "Scalability guidance").
- Every log line generated within a request/response cycle should carry: a
  correlation/request id, and where resolved, the `tenant_id` of the authenticated
  context -- this is essential for diagnosing a tenant-specific incident without
  needing to reconstruct it from application state.
- Logs must never contain: raw payment card/slip images, full payment gateway
  credentials, session tokens, or any content prohibited by root `CLAUDE.md`'s Safety
  rules (no real student/financial records in any environment below production; even
  in production, avoid logging sensitive payloads wholesale -- log identifiers and
  status, not full sensitive bodies).
- Background/async work (event listeners, scheduled jobs, notification dispatch) must
  propagate the same correlation id and tenant id into its own log lines, since
  request-scoped context does not automatically cross a thread boundary
  (`.claude/rules/tenancy.md`).

## 3. Metrics

Minimum categories every domain module should be able to expose (mechanism/tool
TBD -- see open questions):

- **Request-level**: latency, throughput, error rate per endpoint/domain -- the
  standard "golden signals" for an HTTP service.
- **Resource-level**: app instance CPU/memory (for horizontal-scaling decisions),
  PostgreSQL connection pool utilization, Redis latency/hit rate.
- **Domain-specific operational signals** that matter for this platform's known risk
  areas:
  - Payment/webhook processing latency and failure rate (`payment-management`,
    `integration-management`) -- distinct from ledger correctness, which is a data
    concern, not a metrics concern.
  - Notification dispatch queue depth/failure rate (`notification-management`) --
    since fan-out is asynchronous, a stuck queue is invisible without a specific
    metric.
  - Video/playback token issuance rate and rejection rate (`video-access-management`)
    -- a spike in rejected tokens can indicate either an integration issue or an abuse
    pattern.
  - Integration health per provider (Zoom, SMS, WhatsApp, email, payment gateway,
    object/video storage) -- see `integration-architecture.md`'s health-check concept;
    health-check results should feed metrics/alerting, not only an admin-facing
    status page.
- Metrics must be tenant-agnostic in aggregate (platform operators watch overall
  health) but should support tenant-id as a label/dimension where feasible for
  diagnosing a single tenant's issue, without that mechanism becoming a way to browse
  tenant business data (metrics show volume/latency/error counts, not content).

## 4. Tracing

- Each request should carry a trace/correlation id from the moment it enters at
  Nginx/the API boundary, through every in-process cross-module call
  (`api`-interface calls and event publication/consumption), so a single request or a
  single async event chain can be reconstructed across module boundaries even though
  everything runs in one process today.
- Because domain events decouple the triggering request from consumer processing
  (e.g., a payment confirmation event consumed asynchronously by
  `notification-management`), the correlation id must be carried as part of the event
  payload/metadata, not only as an in-process thread-local, or the trace breaks at the
  async boundary.
- Distributed tracing infrastructure (e.g. a trace collector/APM) is not yet selected
  -- see open questions. In the interim, structured logs with a consistent correlation
  id field are the minimum viable substitute.

## 5. Audit logging vs. observability -- explicit distinction

These are two different systems with different owners, different guarantees, and
different audiences. Do not merge them:

| | `audit-log-management` (business audit trail) | Observability (this document) |
|---|---|---|
| Audience | Compliance, finance, security investigations, support, platform admins reviewing a specific privileged action | Engineers/operators diagnosing system health and incidents |
| Retention | Append-only, indefinite/compliance-driven, never deleted or updated (`.claude/rules/backend.md`, `.claude/rules/security.md`) | Operational retention window (e.g. days/weeks), rotated/expired per operational policy |
| Content | Actor id, tenant id, action, target entity, before/after state for specific privileged actions (price changes, payment approvals, device resets, impersonation, etc.) | Request metadata, latency, error traces, resource utilization -- not a record of "who approved what" as a compliance artifact |
| Mutability | Immutable; no update/delete endpoint or repository method may ever target it | Logs/metrics can be sampled, aggregated, or expired without compliance impact |
| Tenant scoping | Tenant-owned data -- filtered by tenant like any other tenant-owned table; an admin of tenant A must never list tenant B's audit log | Operational dashboards are typically platform-wide, with tenant as an optional diagnostic dimension, not a tenant-facing product surface |

A privileged action (e.g. a payment approval) will typically produce **both**: an
audit log row (via `audit-log-management`, in the same transaction as the privileged
action, per `.claude/rules/security.md`) **and** ordinary operational log lines/metrics
for that request (via this observability layer). Neither substitutes for the other.

## 6. Alerting

Alerting should be built on the metrics/logs described above, prioritized around:

- Error-rate/latency SLO breaches on request-handling paths.
- Payment/webhook processing failures (a stuck or failing webhook handler is a
  revenue-affecting incident, not just a technical one).
- Integration health-check failures per provider (see `integration-architecture.md`).
- Notification dispatch backlog/failure beyond a threshold.
- Database/Redis resource exhaustion (connection pool saturation, replica lag if
  applicable).

Specific alert thresholds and on-call routing are an operational runbook concern, to
be defined once the tooling in section 7 is selected.

## 7. Open questions

The following require a concrete tool/vendor decision that has not been made in
CLAUDE.md or source requirements. Do not assume a specific product -- flag for
decision:

- **APM / tracing vendor** -- not selected.
- **Metrics backend/dashboarding tool** -- not selected.
- **Log aggregation/storage destination** (where container stdout/stderr logs are
  shipped and retained) -- not selected.
- **Alerting/on-call routing tool** -- not selected.
- **Log/metric retention windows** -- no specific durations have been set; needs an
  operational decision once volume characteristics are known.

## Related

- `docs/architecture/deployment-architecture.md`
- `docs/requirements/non-functional-requirements.md`
