---
name: database-migration
description: Use when a change requires a new PostgreSQL schema, index, or Flyway migration in the backend. Never edits or rewrites an already-shared/applied migration — only adds new ones.
---

# Database Migration

## When to use

Use whenever a module plan's "database impact" section requires a schema or index change. This skill only touches `backend/` migration files (e.g. Flyway `V*__*.sql`) and related entity/repository code needed to support the migration — not unrelated backend business logic (that's `implement-backend`) and not any frontend file.

## Scope boundary

- Only add new Flyway migration files. Never edit, renumber, or delete a migration that has already been shared/applied — migration history is append-only and is a change-controlled area per `CLAUDE.md`.
- Stay within `backend/`. Do not touch `frontend/`.

## Checklist

- [ ] Every new tenant-owned table includes a `tenant_id` column, with an index that supports tenant-scoped queries
- [ ] Foreign keys, `NOT NULL`, and uniqueness constraints are set where data integrity requires them (not left to application-layer enforcement alone)
- [ ] Financial/ledger tables are designed append-only — no migration deletes or destructively mutates existing financial history
- [ ] The migration is a new file; no existing, already-applied migration was edited
- [ ] A cross-tenant data test exists or is planned (via `qa-test-engineer` / `tenant-isolation-review`) proving the new schema can't leak across tenants
- [ ] The migration was validated against a local/dev database only — never a production or staging database
- [ ] If the schema change is user-visible or contract-relevant, documentation updates are flagged for `update-documentation`
