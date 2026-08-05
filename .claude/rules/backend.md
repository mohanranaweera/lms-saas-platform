---
paths:
  - "backend/**"
---

# Backend Rules — Data Access & Data Integrity

These rules are specific to the data-access layer and schema design. They apply whenever
touching JPA entities, repositories, services with `@Transactional`, or Flyway migrations.

## Tenant filtering must be structural, not manual

Relying on every developer to remember to add `WHERE tenant_id = :tenantId` to every query is
a data-breach waiting to happen — one missed query in one repository method leaks another
tenant's data, and code review will not reliably catch it at scale.

- Do not implement tenant scoping as an ad hoc `WHERE` clause repeated in each `@Query`.
- Prefer one of:
  - A `TenantAwareRepository<T>` base interface/abstract class that all tenant-owned
    repositories extend, which injects `tenant_id` from the trusted tenant context into every
    finder method (custom `@Query` methods included).
  - A Hibernate `@FilterDef`/`@Filter` (or equivalent JPA/Hibernate tenant filter) enabled
    session-wide for tenant-owned entities, so tenant scoping is applied even to queries a
    developer writes without thinking about tenancy.
- Whichever pattern is chosen, apply it consistently across all tenant-owned entities — do not
  mix approaches between modules.
- Any repository method that bypasses the structural filter (e.g. a native query, a
  platform-admin cross-tenant report) must be explicitly named/annotated to make the bypass
  visible in review (e.g. `findAllAcrossTenants...`) and must justify why it is platform-level,
  not tenant-level.
- New repositories for tenant-owned entities must extend/use the shared tenant-aware base —
  do not hand-roll tenant filtering per repository.

## Transaction boundaries

- `@Transactional` belongs on service-layer methods only. Controllers must not be
  `@Transactional`; repositories must not declare their own transaction boundaries beyond what
  Spring Data provides by default.
- A transaction should wrap one business operation, not a request/response cycle and not a
  single SQL statement in isolation.
- Operations that must be atomic and therefore share one transaction:
  - Verified payment confirmation together with enrollment activation (payment-management +
    enrollment-management) — a confirmed payment must never leave enrollment unactivated, and
    enrollment must never activate without a persisted, confirmed payment record.
  - Ledger entry creation together with the settlement/status change it records
    (ledger-settlement-management).
  - Device registration together with the login-activity/audit record that logs it
    (device-authentication).
- Do not span a transaction across an outbound call to an external system (payment gateway,
  Zoom, SMS/WhatsApp/email provider, object storage). Persist the local state change, commit,
  then make the external call — or persist a "pending" state, make the call, and confirm/commit
  in a separate transaction driven by the callback/webhook. Holding a DB transaction open across
  a network call to a third party is not acceptable.
- Do not let a single transaction span multiple unrelated aggregates/domains (e.g. do not fold
  a notification-send into the same transaction as a payment confirmation) — trigger
  notification-management asynchronously after commit instead.

## Entity and index design for tenant-owned tables

- Every tenant-owned table gets `tenant_id UUID NOT NULL REFERENCES tenant(id)` (or the
  equivalent tenant PK type) — never nullable, never optional "for platform-level rows"; if a
  table genuinely holds platform-level (non-tenant) data, it does not belong in the same table
  as tenant-owned rows.
- Every tenant-owned table needs a composite index leading with `tenant_id` to support
  tenant-scoped lookups, e.g. `(tenant_id, id)` for PK-style lookups and `(tenant_id, <natural
  lookup column>)` for the columns the module actually queries by (e.g.
  `(tenant_id, student_id)`, `(tenant_id, status, created_at)`). A bare index on `tenant_id`
  alone is rarely sufficient — index for the tenant-scoped query shape the module actually uses.
- Any unique constraint that is meant to be "unique within a tenant" must include `tenant_id` in
  the constraint (e.g. `UNIQUE (tenant_id, email)` for staff/teacher accounts, not a global
  `UNIQUE (email)`), unless the entity is intentionally platform-global (e.g. platform admin
  accounts).

### Append-only entity design

The following domains must be modeled append-only — rows are inserted and superseded, never
updated in place or deleted, because they are the audit/financial trail the rest of the system
(and regulators/disputes) rely on:

- `ledger-settlement-management`: every ledger entry, settlement calculation, and settlement
  status transition is a new row referencing the row(s) it supersedes/corrects. Corrections are
  reversal + new entry, never `UPDATE`/`DELETE` on an existing ledger row. No repository method
  for these entities may expose `delete`/`deleteById`, and update methods should be limited to
  narrow, explicitly-justified status columns if unavoidable — prefer new rows even for status
  changes.
- `audit-log-management`: audit log rows are immutable once written — no update, no delete, by
  any actor including platform admins. If retention/compliance later requires purging, that is a
  separate approved process, not a repository `delete` call.
- Payment records under `payment-management` (payment attempts, confirmations, refunds) follow
  the same append-only principle: a refund is a new row linked to the original payment, not a
  mutation of it.

## Schema-enforced invariants for high-integrity domains

For `payment-management`/`ledger-settlement-management`, `device-authentication` (part of
`identity-access-service`), and `audit-log-management`, prefer invariants enforced by the
schema/constraints over invariants enforced only by service-layer discipline, since the latter
is bypassable by a future bug or a direct data fix:

- Payment/ledger: use DB `CHECK` constraints for state machines that must never be violated
  (e.g. a payment `status` column constrained to a fixed enum set; `amount > 0`; a settlement
  cannot reference a payment that isn't in a confirmed state — enforce via FK plus service-layer
  guard, and cover with tests). Money columns must use fixed-precision types (`NUMERIC`), never
  floating point. Enrollment activation must have a FK/NOT NULL trail back to the specific
  confirmed payment or approved manual-payment-evidence row that authorized it — activation
  cannot exist as a bare boolean flag with no linkage to what justified it.
- Device authentication: enforce the "one active device slot per (tenant, student, slot)"
  invariant with a unique constraint (e.g. `UNIQUE (tenant_id, student_id, device_slot)`) rather
  than relying on the service layer to count devices correctly before inserting; device removal
  is a status change/soft-revoke (retain history for suspicious-activity investigation), not a
  hard delete of the device row.
- Audit log: every audit row must carry `tenant_id` (or an explicit platform-scope marker),
  `actor_id`, `action`, `target_entity`/`target_id`, and `occurred_at` as `NOT NULL` — an audit
  row with an unidentified actor or target is not useful and should be rejected at the schema
  level (`NOT NULL` + FK where the actor is a known user), not just discouraged in code review.
