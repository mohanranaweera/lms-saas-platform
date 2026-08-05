---
name: wordpress-migration
description: Use when planning migration of data or functionality from the legacy WordPress, MasterStudy, WooCommerce, and MariaDB stack into this platform. Read-only until migration implementation is explicitly approved; never connects to production.
---

# WordPress Migration

## When to use

Use when a task involves planning (or, once explicitly approved, implementing) migration of legacy data or functionality from WordPress, MasterStudy LMS, WooCommerce, and MariaDB into this platform's PostgreSQL-backed, multi-tenant data model.

## Scope boundary

- Read-only (planning, mapping, risk assessment) until a task explicitly approves migration implementation. Even once approved, implementation work follows `database-migration`, `implement-backend`, and `implement-frontend` for the actual changes — this skill defines the plan, it doesn't become the implementer.
- Never connects to a production database, production WordPress site, or production MariaDB instance — migration planning and testing use exported/sample/dev data only.

## Checklist

- [ ] Legacy entities (courses, enrollments, orders, users, roles) are mapped explicitly to this platform's tenant-aware data model
- [ ] Every migrated record is assigned a `tenant_id` explicitly — never inferred implicitly or left null
- [ ] Financial/order history from the legacy system is preserved and reconciled during migration — never dropped or summarized away
- [ ] Data-integrity risks are identified (duplicate accounts, orphaned records, inconsistent legacy schemas) with a proposed resolution
- [ ] The plan calls out anything that would require production database or production site access, and defers it for explicit human approval rather than proceeding
- [ ] Migration implementation (scripts, code, data changes) does not begin until a task explicitly approves it
- [ ] Once implementation is approved, it still follows this project's tenant-isolation, testing, and documentation requirements like any other module
