-- MVP-020 Platform Admin Dashboard (PADASH-2): platform-wide, cross-tenant
-- "recent activity" scans on payment, ledger_entry, and audit_log.
--
-- Every existing index on these three append-only tables leads with
-- `tenant_id` (correct for the tenant-scoped query shape every other module
-- uses - see .claude/rules/backend.md's "Entity and index design for
-- tenant-owned tables"). PADASH-2 introduces the first genuinely
-- cross-tenant, platform-wide `ORDER BY <timestamp> DESC` scan against each
-- of these tables: the Platform Admin payment dashboard and platform-wide
-- audit log view, which are filterable (by tenant, status, action, etc.) but
-- NOT tenant-scoped by default. None of the existing `(tenant_id, ...)`
-- indexes serve that access pattern efficiently once these tables grow to
-- platform-wide (multi-tenant-total) row counts, since a leading `tenant_id`
-- column cannot help an unfiltered, cross-tenant, time-ordered scan.
--
-- This migration is purely additive - no table/column changes, no data
-- changes. `payment.created_at`, `ledger_entry.created_at`, and
-- `audit_log.occurred_at` are all confirmed NOT NULL timestamp columns
-- already (see V19__create_payment_management_schema.sql and
-- V21__create_payment_slip_schema.sql), so no backfill or nullability
-- concern applies here.
--
-- `tenant_id` is included as a trailing column (not an `INCLUDE` clause) so
-- that the per-row tenant attribution the dashboard must display can be
-- read directly from the index without an extra heap fetch, while keeping
-- this migration compatible with all currently-supported PostgreSQL
-- versions (`INCLUDE` support/behavior varies more across versions/index
-- types than a plain trailing column).
--
-- `CREATE INDEX CONCURRENTLY` is deliberately NOT used here, for the same
-- reason already documented in V30__add_audit_log_action_index.sql: this
-- project's Flyway configuration runs every SQL migration inside a single
-- transaction, and `CONCURRENTLY` cannot run inside a transaction block.
-- Accepted for now because the project remains pre-launch / low-row-count.
--
-- Note this migration builds THREE blocking `CREATE INDEX` statements
-- back-to-back inside that one transaction, against the three most
-- write-heavy tables in this schema (`payment`, `ledger_entry`, and
-- `audit_log` - the last of which V30's own comment already calls out as
-- "the fastest-growing, most write-concentrated table"). The lock window
-- that matters operationally here is the CUMULATIVE duration of all three
-- builds, not any single index's build time in isolation - time this
-- migration end-to-end against production-representative row counts for
-- all three tables before a live deploy, not just each `CREATE INDEX`
-- individually.

CREATE INDEX idx_payment_created_at_tenant
    ON payment (created_at DESC, tenant_id);

CREATE INDEX idx_ledger_entry_created_at_tenant
    ON ledger_entry (created_at DESC, tenant_id);

CREATE INDEX idx_audit_log_occurred_at_tenant
    ON audit_log (occurred_at DESC, tenant_id);
