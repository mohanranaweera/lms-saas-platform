# WordPress Migration

**Domain:** Not part of the confirmed 18-domain backend list — exists only as an internal engineering skill/agent definition · **Portal(s):** None (internal engineering process, not a product-facing flow)

## Status: undocumented and unscoped

**This feature has no product-requirements footprint anywhere in the repository.** A search
across `docs/requirements/`, `docs/architecture/`, `docs/ui-ux/`, and `docs/adr/` confirms zero
mentions of WordPress, MasterStudy, or WooCommerce outside of `.claude/skills/wordpress-migration/SKILL.md`
and `.claude/agents/legacy-migration-engineer.md`. It is not present in
`docs/requirements/source-requirements.md`'s module list (1-20, A-F), not in
`functional-requirements.md`'s FR table, not in `module-catalog.md`'s domain mapping, and not in
`source-requirements.md` §5's MVP/Phase 2/Phase 3 breakdown.

Most of the 10 sections below cannot be completed as product requirements — only what the
engineering-skill definition itself specifies is recorded. **Recommend this be raised explicitly
with the product/architecture owner and given a proper requirements-doc entry (business purpose,
scope, target domains, phase) before being treated as a scoped, phase-tagged feature on par with
the other 27 in this catalog.**

## 1. Business purpose

Not defined in any product requirement. Inferred only from the engineering-skill definition:
migrate data from a legacy WordPress + MasterStudy LMS + WooCommerce + MariaDB stack into this
platform. Whether this is a one-time migration for a specific existing customer, or a general
product feature (self-service "import from WordPress" for any tenant), is unresolved.

## 2. Actors

No product-facing actor is defined anywhere. The only defined "actor" is an internal engineering
process — a `legacy-migration-engineer` sub-agent (`tools: Read, Grep, Glob`,
`permissionMode: plan`) — not a Tenant Admin, Student, or Platform Admin acting inside the
product UI. Whether this ever becomes a Tenant-Admin-facing self-service import tool is
unresolved.

## 3. Preconditions

Per `.claude/skills/wordpress-migration/SKILL.md`'s checklist: legacy entities (courses,
enrollments, orders, users, roles) must be explicitly mapped to this platform's tenant-aware
data model before any migration; every migrated record must be assigned an explicit `tenant_id`
(never inferred/null); financial/order history must be preserved and reconciled, never dropped.
This requires the target-side domains (`course-management`, `enrollment-management`,
`payment-management`, `user-management`) to already exist with their tenant-aware schemas.

## 4. Normal flow (planning-only today)

1. `legacy-migration-engineer` agent (read-only) is invoked to map legacy WordPress/MasterStudy/WooCommerce/MariaDB entities to platform tables.
2. It produces a migration plan/mapping/risk assessment (duplicate accounts, orphaned records, inconsistent legacy schemas identified with proposed resolutions).
3. The plan calls out anything requiring production database/site access and defers it for explicit human approval.
4. Migration implementation (scripts/code/data changes) does not begin until a task explicitly approves it.

## 5. Alternative flows

Any request to connect to a production WordPress site, production MariaDB instance, or
production database must be refused. Once implementation is approved, it must still follow the
platform's standard tenant-isolation, testing, and documentation requirements like any other
module — no exemption from the tenant-isolation ADR, cross-tenant test requirements, or
append-only ledger rules just because data originates from a migration rather than live use.

## 6. Authorization rules

Not applicable in product terms — this is a data-migration/engineering activity, not a
runtime end-user feature, so the role/permission matrix does not apply. Read-only
(planning/mapping/risk-assessment) until explicitly approved for implementation.

## 7. Tenant rules

Every migrated record is assigned a `tenant_id` explicitly — never inferred implicitly or left
null. Financial/order history from the legacy system must be preserved and reconciled — never
dropped or summarized away. Since legacy WordPress data has no native multi-tenant concept, the
migration plan itself is the isolation risk: every migrated user/course/order/enrollment record
must be verifiably assigned to exactly one tenant with no cross-tenant bleed.

## 8. Acceptance criteria (for the planning deliverable, not a product feature)

- [ ] Legacy entities (courses, enrollments, orders, users, roles) are explicitly mapped to this platform's tenant-aware data model.
- [ ] Every migrated record is assigned a `tenant_id` explicitly — never inferred implicitly or left null.
- [ ] Financial/order history from the legacy system is preserved and reconciled during migration — never dropped or summarized away.
- [ ] Data-integrity risks (duplicate accounts, orphaned records, inconsistent legacy schemas) are identified with a proposed resolution.
- [ ] Anything requiring production database/site access is explicitly called out and deferred for human approval, not proceeded on.
- [ ] No migration implementation begins without explicit task-level approval.
- [ ] Once approved, implementation still passes standard cross-tenant negative tests: query migrated data as Tenant A and confirm zero rows from Tenant B's migrated dataset are visible.

## 9. Audit requirements

Not itself a runtime feature with a defined audit-log action. If/when implemented, standard
per-domain audit requirements apply to whatever target tables the migration writes into.

## 10. MVP or later-phase classification

**Open Decision — not phase-tagged anywhere.** No architecture document or ADR currently
addresses WordPress migration technically — only the skill/agent definitions exist.

## Change control flag

Migrated financial/order history must not violate append-only ledger rules; migrated enrollment
records must satisfy the same activation-evidence trail as normal enrollments; migrated data
must never come from a production database connection. If legacy password hashes require a
different verification path than Argon2id (ADR-007), that is itself a change-controlled
authentication-architecture question, not something to solve ad hoc inside the migration script.
Given the migration would populate ledger/enrollment tables that are themselves
change-controlled, **a dedicated ADR should be raised before any implementation.**

## Open decisions

- Whether this is a one-time migration or a general product feature.
- No business purpose, actor, phase, or scope is documented in `docs/requirements/` at all — needs a proper requirements pass before implementation planning.
- Which target domains receive migrated data and how tenant_id scoping applies is not mapped anywhere.
