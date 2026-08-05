# Backup & Disaster Recovery

Status: Living document

## 1. Purpose and scope

This document describes the backup strategy for the platform's sources of truth
(PostgreSQL, and any external object/video storage the platform's data depends on),
the retention approach for Flyway migration history, and a conceptual disaster
recovery (DR) approach. It does not invent specific recovery time/point numbers where
no source material provides them -- those are flagged as open questions rather than
assumed.

## 2. What must be backed up

| Data | System of record | Backup priority | Notes |
|---|---|---|---|
| All tenant business data (tenant profiles, users, courses, enrollments, attendance, exams) | PostgreSQL | Critical | Single shared database across all tenants (shared-schema multi-tenancy) -- a restore affects all tenants simultaneously; there is no per-tenant backup/restore granularity by default. |
| Payment orders, payments, ledger entries, settlements | PostgreSQL | Critical -- must never be lost or corrupted | Append-only by design (`.claude/rules/backend.md`, `.claude/rules/payments.md`). Backup/restore procedures must never "fix" ledger data by editing rows -- restoring from backup restores the append-only history as-is. |
| Audit log entries | PostgreSQL | Critical | Immutable, compliance-relevant; same backup treatment as payment/ledger data. |
| Flyway migration history (`flyway_schema_history` table and the migration script files themselves) | PostgreSQL (history table) + version control (scripts) | Critical | See section 4. |
| Uploaded materials/documents and video content | External object storage / secure video hosting provider (not the application database) | Critical, but backup responsibility may be partly delegated to the provider | See section 3 and open questions -- the platform does not self-host this content per `.claude/rules/architecture.md`. |
| Redis contents (cache, sessions, device/session tracking, short-lived tokens) | Redis | Not backed up as source-of-truth data | By design, Redis holds no data that must survive a flush (`.claude/rules/architecture.md`: "Redis is a cache/ephemeral-state layer, not a source of truth"). A Redis flush is a degraded-service event (e.g. forced re-login), not a data-loss event, and should not be treated as requiring backup/restore. |

## 3. Backup strategy

- **PostgreSQL** is the platform's authoritative source of truth and requires:
  - Regular automated full backups (frequency TBD -- see open questions), plus
    continuous/point-in-time recovery capability (e.g. WAL archiving) if the hosting
    approach supports it, so that a restore isn't limited to the last full-backup
    boundary. The specific mechanism depends on whether Postgres is self-hosted in
    Docker Compose or a managed service (see `deployment-architecture.md` open
    questions) -- this document does not assume either.
  - Backups stored separately from the primary database host/environment (a backup
    that lives only on the same disk/instance as the primary is not a real backup).
  - Backups must never contain real production data copied into a non-production
    environment for testing purposes without explicit, approved handling -- root
    `CLAUDE.md`'s Safety rules already prohibit using real student/financial records
    in development.
- **External object storage / secure video hosting**: the platform stores materials
  and video externally rather than on the application VPS. Backup/durability for that
  content is primarily the responsibility of whichever external provider is selected
  -- this document does not assume a specific provider's durability model. Whatever
  provider is chosen (an open question per `system-context.md` and
  `deployment-architecture.md`), its durability/redundancy/backup guarantees should be
  explicitly verified and documented as part of the ADR that selects it.
- **Application code and configuration** (excluding secrets) is backed up implicitly
  via git history in the platform's version control -- not a separate backup process.
- **Secrets/credentials** (database passwords, integration credentials in the
  credential vault -- see `integration-architecture.md`) require their own
  backup/recovery approach as part of whatever secrets management mechanism is
  selected (see `deployment-architecture.md`, open questions).

## 4. Flyway migration history retention

- Flyway migration scripts are version-controlled application artifacts -- they are
  retained indefinitely in source control, the same as any other code.
- The `flyway_schema_history` table in PostgreSQL is part of the database and is
  covered by the PostgreSQL backup strategy above -- it must never be manually edited,
  truncated, or "cleaned up," including during a restore. A restore should bring the
  schema history table back in the same state as the data it's paired with.
- Per root `CLAUDE.md` and `.claude/rules/tenancy.md`, migration history is a
  change-controlled area: no migration is ever edited or renumbered after being
  applied; corrections are always new migrations. This applies equally to
  disaster-recovery scenarios -- do not "fix" migration history as part of a recovery
  procedure.

## 5. Disaster recovery approach (conceptual)

- **Failure scenarios to plan for** (at a conceptual level; exact procedures depend on
  hosting decisions not yet made):
  - Loss/corruption of the primary PostgreSQL instance -- recover from the most recent
    valid backup/PITR point.
  - Loss of an application container/instance -- no data-loss risk since app instances
    are stateless (`deployment-architecture.md`); recovery is simply starting a
    replacement instance from the same image.
  - Loss of Redis -- no data-loss risk by design (section 2); recovery is restarting
    Redis, with the expected side effect of forced re-authentication/session
    re-establishment for active users.
  - Unavailability of an external integration (Zoom, SMS, WhatsApp, email, payment
    gateway, object/video storage) -- handled as a degraded-mode/integration-health
    concern (see `integration-architecture.md`), not a platform data-loss event.
- **Recovery priority order**: PostgreSQL (all tenant/financial/audit data) first,
  then re-establishing integration connectivity (payment gateway, notifications),
  then restoring any degraded external-content access. Redis and stateless app
  instances are the fastest to recover and are not the bottleneck in a DR scenario.
- **Multi-tenant blast radius**: because all tenants share one database, a DR event
  affecting PostgreSQL affects all tenants simultaneously -- there is no mechanism
  today for a single-tenant-only restore. This is a direct consequence of the
  confirmed shared-schema multi-tenancy strategy and should be communicated to tenants
  as part of the platform's SLA, not treated as a gap to silently work around with
  per-tenant backup mechanics (which would itself be a multi-tenancy strategy change
  requiring an ADR).

## 6. Open questions

No source material (CLAUDE.md, `.claude/rules/*`, `docs/requirements/source-requirements.md`)
specifies concrete numbers or tooling for the following. These are explicitly flagged
as open rather than invented:

- **RPO (Recovery Point Objective)** -- maximum acceptable data loss window is not
  defined anywhere in the source material.
- **RTO (Recovery Time Objective)** -- maximum acceptable downtime to restore service
  after a disaster is not defined anywhere in the source material.
- **Backup frequency and retention window** -- not specified.
- **Backup storage location/provider** and whether it's geographically separated from
  the primary environment -- not specified (depends on the hosting-provider decision
  flagged in `deployment-architecture.md`).
- **Point-in-time recovery (PITR) capability** -- depends on whether PostgreSQL is
  self-hosted or managed -- hosting model not yet decided.
- **DR testing cadence** (how often a restore is actually rehearsed) -- not specified.
- **External object/video storage provider's own backup/durability guarantees** --
  depends on the provider selection, itself an open question in `system-context.md`.

Any RTO/RPO targets or backup tooling decisions, once made, should be recorded here
with their source (ADR reference) rather than silently assumed in future revisions of
this document.

## Related

- `docs/architecture/deployment-architecture.md`
- `docs/architecture/database-architecture.md`
