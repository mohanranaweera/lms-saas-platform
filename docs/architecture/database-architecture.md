# Database Architecture

Status: living document — reflects the current, confirmed data-model approach for the
platform. Trust this over `docs/requirements/source-requirements.md` for anything
implementation-related; the requirements doc is historical input only.

Applies to: every Flyway migration and every JPA entity in `backend/`. This document is
descriptive of decisions already made in `CLAUDE.md`, `.claude/rules/tenancy.md`, and
`.claude/rules/backend.md` — it does not introduce new policy. Where this document
proposes an implementation detail not already fixed by those rules, it is marked
**(implementation detail — not change-controlled)**; anything that touches a
change-controlled area per `CLAUDE.md` (multi-tenancy strategy, payment ledger rules,
enrollment activation rules, database migration history) is marked **(change-controlled —
ADR required to alter)**.

## 1. Shared-schema multi-tenant data model

The platform uses one shared PostgreSQL schema serving all tenants (confirmed in
`CLAUDE.md` and `docs/requirements/source-requirements.md` §7 "Tenancy: Shared SaaS
application with tenant-aware database model"). There is no schema-per-tenant and no
database-per-tenant. **(change-controlled — ADR required to alter; this is the
multi-tenancy strategy — see ADR-002.)**

Concretely, every tenant-owned table must have:

- **`tenant_id UUID NOT NULL REFERENCES tenant(id)`** (or the platform's tenant PK type).
  Never nullable. Never added later as nullable "to backfill" — if a table needs a
  transition period, that is handled by the migration's own `NOT NULL` + `DEFAULT`/backfill
  sequencing within one migration script, not by shipping a nullable column permanently.
- If a table is genuinely platform-level (not owned by any tenant — e.g. platform admin
  accounts, subscription plan catalog, global feature-flag definitions), it must **not**
  share a table with tenant-owned rows. Do not add an "optional" `tenant_id` to
  distinguish platform rows from tenant rows in the same table; use a distinct table.
- **A composite index leading with `tenant_id`**, shaped to the query the owning module
  actually runs — not just a bare index on `tenant_id`. Examples:
  - `(tenant_id, id)` for PK-style/tenant-scoped-by-id lookups.
  - `(tenant_id, student_id)` for a student's rows within a tenant.
  - `(tenant_id, status, created_at)` for status-filtered, time-ordered listings (e.g.
    payment dashboards, ticket queues).
  A table with only a bare `tenant_id` index and no query-shaped composite index is
  considered incomplete — indexes must be validated against the module's actual
  tenant-scoped read pattern, since table size scales with total platform tenants, not
  per-tenant volume.
- **Unique constraints scoped per tenant.** Any constraint that means "unique within a
  tenant" (e.g. staff email, course slug, student code) must be `UNIQUE (tenant_id,
  <column>)`, never a bare global `UNIQUE (<column>)` — a global unique constraint on
  something like `email` would incorrectly prevent two different tenants from having
  staff/students with the same email. The only exception is entities that are
  intentionally platform-global (platform admin accounts), which are not tenant-owned
  tables in the first place.
- **Cross-table FKs within the same tenant.** A FK from one tenant-owned table to another
  (e.g. `enrollment.course_id -> course.id`) must not allow linking to a row belonging to
  a different tenant than the child row's own `tenant_id`. Where the referenced table's
  PK alone can't express this, prefer a composite FK against `(tenant_id, id)` on the
  parent table (i.e. give the parent table a unique constraint on `(tenant_id, id)`, then
  FK the child's `(tenant_id, parent_id)` against it) over relying on a service-layer
  check alone. This makes "referencing another tenant's row" a constraint violation, not
  just a bug that passes review.

## 2. Structural tenant-filtering mechanism

`.claude/rules/backend.md` requires tenant filtering to be structural, not a manually
repeated `WHERE tenant_id = :tenantId` per query, and names two acceptable mechanisms:
(a) a `TenantAwareRepository<T>` base type, or (b) a Hibernate `@FilterDef`/`@Filter`
session-level tenant filter. Both are legitimate; this document recommends one as the
platform-wide default so every module implements tenancy the same way, per the "apply
consistently across all tenant-owned entities — do not mix approaches between modules"
rule.

### Recommended: `TenantAwareRepository<T>` base interface

- A shared `TenantAwareRepository<T, ID>` (Spring Data JPA base) that every tenant-owned
  entity's repository extends. It injects the resolved `tenant_id` (read from the
  request-scoped tenant context — see `docs/architecture/multi-tenancy.md`) into every
  standard finder (`findById`, `findAll`, etc.) and exposes tenant-scoped variants of
  common query shapes so per-module `@Query`/specification methods compose against an
  already-tenant-scoped base rather than hand-rolling the filter each time.
- Rationale for choosing this over the Hibernate `@Filter` approach as the default:
  - It keeps tenant scoping visible at the Java type level (repository signature), which
    is easier to review than a session-level filter that silently rewrites SQL — a
    reviewer can see "this repository extends `TenantAwareRepository`" as a checklist
    item, per the tenancy review checklist in `.claude/rules/tenancy.md`.
  - It composes more predictably with native/JPQL `@Query` methods that need explicit
    parameters anyway (Hibernate `@Filter` requires the filter to be explicitly enabled
    on every session/EntityManager use, which is easy to forget in a new
    `@Transactional` service method, a background job, or a manually built native query —
    the same "developer forgets to opt in" failure mode the structural rule exists to
    avoid).
  - It gives a natural, named place for the required bypass pattern: any repository
    method that must read across tenants (platform-admin reporting, cross-tenant
    support tooling) is **not** on `TenantAwareRepository` — it lives on a distinct,
    explicitly named method/interface, e.g. `findAllAcrossTenantsForPlatformReport(...)`,
    making the bypass visible in review rather than hidden behind a disabled session
    filter.
- Every new tenant-owned entity's repository must extend `TenantAwareRepository` (or the
  equivalent named bypass type, with justification) — do not hand-roll tenant filtering
  per repository, per `.claude/rules/backend.md`.

### Considered alternative: Hibernate `@FilterDef`/`@Filter`

- A session-wide Hibernate filter (`@FilterDef(name = "tenantFilter", ...)` applied to
  every tenant-owned `@Entity`, enabled per-session from the tenant context) is the other
  option `.claude/rules/backend.md` names, and is not ruled out.
- Advantage over the repository-base approach: it also protects ad hoc JPQL/Criteria
  queries and lazy-loaded associations that a developer writes without going through a
  repository method at all — the repository-base approach only protects calls that go
  through `TenantAwareRepository`-derived methods.
- Disadvantage (why it is not the default): the filter must be explicitly enabled on
  every `Session`/`EntityManager` at the start of tenant-scoped work; missing that
  enable-step in one place (a new background job, a manually opened session, a native
  query bypassing Hibernate) silently returns unfiltered, cross-tenant data rather than
  failing loudly — which is a worse failure mode for a breach than a repository
  interface a reviewer can visually check.
- If a future review finds structural gaps that `TenantAwareRepository` alone does not
  close (e.g. lazy-loaded child collections leaking across tenants through JPA
  relationship traversal rather than repository calls), adding Hibernate `@Filter` as a
  **defense-in-depth layer on top of** `TenantAwareRepository` (not a replacement) should
  be raised as a proposal, not silently implemented.

### This choice is recorded in ADR-006

This document's recommendation of `TenantAwareRepository` as the primary mechanism (with
Hibernate `@Filter` as a possible future defense-in-depth addition) is now formally
recorded in `docs/adr/ADR-006-tenant-isolation-repository-mechanism.md` (Accepted
2026-08-02). Domain repository implementations should follow that ADR, not this section
directly — this section remains as the technical rationale ADR-006 formalizes.

## 3. Append-only domains

Three domains are append-only by design, per `.claude/rules/backend.md` and
`.claude/rules/payments.md`: **`ledger-settlement-management`**, **`audit-log-
management`**, and the payment-record tables inside **`payment-management`** (payment
attempts, confirmations, refunds). **(change-controlled — payment ledger rules; do not
alter without approval.)**

Schema-level reflection of "append-only":

- **No `DELETE`, ever, by any actor.** No repository method for these entities may expose
  `delete`/`deleteById`. This is enforced at the repository layer (the type simply does
  not offer the method — it does not extend `CrudRepository`'s delete operations, or
  those methods are overridden to throw/be removed from the exposed interface) as well as
  by not granting the application's DB role `DELETE` privilege on these tables in
  production configuration.
- **No `UPDATE` on existing rows to represent a state change or correction.** A
  correction, reversal, refund, or status transition is always a **new row** that
  references the row(s) it supersedes:
  - Ledger: a reversal/correction entry carries `reverses_entry_id` pointing at the
    original entry; the original entry's columns are never rewritten.
  - Payment: a refund is a new `payment` (or `payment_refund`) row linked to the original
    payment id; the original payment row, once in a terminal state (`CONFIRMED`,
    `REJECTED`, `REFUNDED`), is immutable.
  - Audit log: rows are write-once. There is no "edit audit entry" or "delete audit
    entry" affordance anywhere in the schema or API surface, including for platform
    admins.
  - Manual payment slip review state machine (`SUBMITTED -> UNDER_REVIEW -> APPROVED |
    REJECTED`) is one-directional; a reversal of an `APPROVED` slip is a new ledger
    correction entry, not a rollback of the slip's own state column.
- If a narrow, explicitly-justified status column update is genuinely unavoidable for one
  of these tables (e.g. a `settlement_run.export_downloaded_at` timestamp that is not
  part of the financial state machine itself), it must be called out and justified in the
  migration/PR — the default assumption is "no updates," not "updates are fine unless
  someone objects."
- Retention/compliance-driven purging of audit or financial history is a separate,
  explicitly approved process (not a repository `delete` call, not a migration that
  drops rows) — out of scope for normal feature migrations.

## 4. Schema-enforced invariants

Per `.claude/rules/backend.md`, the following domains prefer invariants enforced by the
schema itself over invariants that rely only on service-layer discipline, since a schema
constraint cannot be bypassed by a future bug, a direct data fix, or a new code path that
forgets the rule:

- **Payment / ledger state machines.** `status` columns are constrained with a DB `CHECK`
  constraint to the fixed enum of valid states (e.g. payment: `PENDING`, `CONFIRMED`,
  `REJECTED`, `REFUNDED`; manual slip: `SUBMITTED`, `UNDER_REVIEW`, `APPROVED`,
  `REJECTED`) rather than left as a free-text/unconstrained column. Adding, removing, or
  redefining what a state means is change-controlled ("payment ledger rules" —
  `.claude/rules/payments.md` §4).
- **Money columns.** Always `NUMERIC` (fixed precision), never `FLOAT`/`DOUBLE`. This
  applies to every amount column across payment, ledger, settlement, and finance-expense
  tables without exception.
- **Positive-amount invariants.** `CHECK (amount > 0)` (or the domain-appropriate
  equivalent, e.g. allowing negative only for explicitly-typed reversal rows where the
  sign is part of the entry's meaning) on monetary columns, so a zero or negative amount
  cannot be silently persisted as if valid.
- **Settlement-references-confirmed-payment.** A settlement/ledger row that is supposed
  to reference a payment must do so via FK, and the *confirmed* state of that payment
  should be enforced as close to the schema as practical — a FK to the payment row plus a
  service-layer guard verifying `status = 'CONFIRMED'` at the point of ledger-entry
  creation, with tests covering the guard (a bare FK alone cannot express "must be in a
  specific status," so this is FK + guard + test, not FK alone).
- **Enrollment activation traceability.** Enrollment activation must not exist as a bare
  boolean flag. The `enrollment` table's activation must carry a `NOT NULL` FK back to
  the specific confirmed payment row or approved manual-payment-evidence row that
  authorized it (e.g. `activating_payment_id UUID NOT NULL REFERENCES payment(id)` for
  the gateway path, or an equivalent FK to the approved slip/manual-evidence row for the
  manual path — exactly one of these must be non-null and consistent with how the
  enrollment was actually activated). This makes "activated with no linkage to what
  justified it" a schema violation, not just a code-review miss. **(change-controlled —
  enrollment activation rules.)**
- **Device authentication invariants** (part of `identity-access-service`, documented
  fully in `docs/architecture/multi-tenancy.md` and `.claude/rules/security.md`): "one
  active device slot per (tenant, student, slot)" is enforced with `UNIQUE (tenant_id,
  student_id, device_slot)` rather than a service-layer count-then-insert, closing the
  race condition a pure application-level check would leave open.
- **Audit log completeness.** `tenant_id` (or an explicit platform-scope marker),
  `actor_id`, `action`, `target_entity`/`target_id`, and `occurred_at` are all `NOT NULL`
  on the audit table, with `actor_id` and `target_id` FK'd to known user/entity rows where
  applicable — an audit row with an unidentified actor or target is rejected at insert
  time by the schema, not merely discouraged by convention.

## 5. Mapping to the confirmed backend domain list

The confirmed domain list (`.claude/rules/architecture.md`, sourced from
`docs/requirements/source-requirements.md` §6 "Final module architecture direction") is:

```
identity-access-service, tenant-management, user-management, course-management,
content-management, video-access-management, live-class-management,
enrollment-management, payment-management, ledger-settlement-management,
attendance-management, exam-management, finance-expense-management,
notification-management, integration-management, reporting-analytics,
audit-log-management, support-management
```

Data-model conventions that follow from this list:

- **One logical schema area per domain.** Tables belong to exactly one domain package
  (per the package-structure rule in `.claude/rules/architecture.md`); a table's owning
  domain is whichever domain owns the primary aggregate the table represents (e.g.
  `enrollment` lives in `enrollment-management` even though it references `course` and
  `payment` rows owned by other domains).
- **No cross-domain joins at the database level are the preferred default.** A query that
  would need to `JOIN` across e.g. `course-management` and `payment-management` tables
  directly should instead be served by:
  1. A narrow read method added to the owning domain's `api` service interface (in-
     process synchronous call), for request-time reads that need current, consistent
     data — this is the default for most cross-domain reads.
  2. A `reporting-analytics`-owned read model/projection, built from domain events
     published by the owning domains, for aggregate/reporting-style queries that would
     otherwise require joining many domains' tables at request time. `reporting-
     analytics` should not run ad hoc live joins across every other domain's tables — see
     `.claude/rules/architecture.md`'s scalability guidance.
  A migration that adds a FK or view spanning two domains' tables for the sake of a
  convenient query is a signal the query belongs in the owning domain's `api` interface
  or in a `reporting-analytics` projection instead — not a reason to add a direct
  cross-schema join.
- **Foundational domains, no reverse dependency.** `identity-access-service` and
  `tenant-management` own the `tenant` and core `user`/auth tables that most other
  domains FK against (`tenant_id`, `actor_id`, etc.). Business domains may FK to these
  tables; `identity-access-service`/`tenant-management` tables must never FK to a
  business-domain table (course, payment, exam, etc.) — that would invert the
  foundational-module dependency direction fixed in `.claude/rules/architecture.md`.
- **`integration-management`** owns credential/webhook-log tables (Zoom, SMS, WhatsApp,
  email, payment gateway, object storage config) exclusively; other domains do not
  create their own tables for third-party credentials — they call `integration-
  management`'s `api` interfaces.
- **Audit, notification, and reporting tables are event-consumers' own tables,** not
  shared tables written directly by the domains that trigger them — e.g. a price change
  in `course-management` publishes a domain event; `audit-log-management` persists its
  own audit row from that event, rather than `course-management` writing directly into an
  audit table it doesn't own.

## 6. Migration process reminder

- Every schema or index change is a **new** Flyway migration file. An already-applied
  migration is never edited, renumbered, or rewritten — migration history is append-only,
  per `CLAUDE.md`'s change-controlled list ("database migration history"). If a change
  would require altering already-applied migration history rather than adding a new
  migration, stop and flag it as requiring explicit approval rather than proceeding.
- New tenant-owned tables, new columns, new indexes that other modules or the frontend
  rely on must have this document's data-model section (or `docs/api` for contract-level
  detail) updated in the same change, per `.claude/rules/documentation.md`.

### Known gap: `staff_profile`/`student_profile`/`teacher_profile` predate this catalog

This document was principle-based only (structural rules every tenant-owned table must
follow, no per-table data dictionary) until §7 below added the first per-table catalog
entries, for `course-management`. `staff_profile` (V10, MVP-005), `student_profile` (V11,
MVP-006), and `teacher_profile` (V11, MVP-007) all follow every rule in §1/§2 above
(composite tenant-scoped index, `UNIQUE(tenant_id, ...)`, composite FK preventing
cross-tenant linkage — see each migration file's own header comment for the concrete
reasoning), but none has been backfilled into the catalog format §7 introduces.
`student_profile`'s column set is additionally still provisional pending the
guardian/school/grade/stream field-list decision (`docs/requirements/open-decisions.md`
§15) — its current, real, up-to-date column contract lives in
`docs/api/user-management.md` and the migration file itself, not here. If/when these three
tables are backfilled into the §7-style catalog, do it together, not one at a time.

## 7. course-management tables (MVP-008)

Added by `V11__create_course_management_schema.sql` through
`V14__cascade_delete_course_structure.sql`. One aggregate root (`course`) with two
structural child tables and one append-only history table — not four separate account
types the way `usermanagement`'s staff/student/teacher sub-roles are, so they live as one
domain's facets rather than sibling packages.

- **`course`** — `tenant_id NOT NULL`, `teacher_id` FK'd via a composite
  `(tenant_id, teacher_id) REFERENCES tenant_user (tenant_id, id)` — never a bare-id FK, so
  a cross-tenant teacher assignment is a constraint violation. `status` is both lifecycle
  and visibility (`DRAFT`/`PRIVATE`/`PUBLIC`); no separate axis exists. `category`/
  `subject`/`stream`/`grade`/`academic_year` are free-text, not FK'd to a catalog table (none
  exists yet). No `currency` column — a single implicit currency is assumed platform/tenant-
  wide pending `payment-management`'s design. Indexes: `(tenant_id, status, created_at DESC)`
  and `(tenant_id, teacher_id, status)`.
- **`course_module`** / **`course_lesson`** — structure only, no material content (that's
  `content-management`'s `material` table, below, referencing `course_lesson.id` from its
  own side). Both FK to their parent via composite `(tenant_id, ...)` FKs; `course_module`→
  `course` and `course_lesson`→`course_module` cascade-delete (`ON DELETE CASCADE`, V14).
  `sequence` is a positive integer, unique within its parent per tenant — no dedicated
  index beyond the unique constraint's own backing btree (V13 removed a redundant explicit
  index that duplicated the unique constraint's leading-column order). `course_lesson` also
  carries `uq_course_lesson_tenant_id UNIQUE (tenant_id, id)` (V15) — added specifically so
  `material`'s composite FK below has a matching unique/PK constraint to reference; its
  sibling tables `course`/`course_module` already had the equivalent constraint from V11.
- **`material`** (`content-management`, MVP-009, V16) — one row per uploaded lesson
  material (PDF/image/plain-text "notes" only at MVP). `tenant_id NOT NULL`, `lesson_id`
  FK'd via a composite `fk_material_lesson (tenant_id, lesson_id) REFERENCES course_lesson
  (tenant_id, id)` — **deliberately without `ON DELETE CASCADE`**, unlike every other
  parent/child pair in this schema: a lesson/module/course delete that would silently
  cascade-remove an attached material is instead rejected with a `409` (FK violation), so
  that deletion always goes through `content-management`'s own audited
  `DELETE .../materials/{id}` path (`MaterialDeletedEvent`) rather than an implicit DB-level
  cascade that would orphan the corresponding object-storage entry and bypass the audit
  requirement. `uploaded_by` is a second composite FK to `tenant_user (tenant_id, id)`.
  `sequence` is unique within `(tenant_id, lesson_id)`, same convention as
  `course_module`/`course_lesson` — no dedicated index beyond `uq_material_sequence`'s own
  backing btree (same V13 precedent). `visibility` is `CHECK`-constrained to
  `VISIBLE`/`HIDDEN` only (no richer taxonomy defined anywhere in the requirements corpus
  yet). `expiry_at` exists for forward compatibility but is unenforced — and currently
  unwritable, since no request DTO accepts it yet — at MVP (Phase 2 expiry enforcement).
  `mime_type` is deliberately unconstrained at the DB level; the accepted-format allow-list
  is a service-layer, evolving concern (`ContentSniffer`, magic-byte detection). At the
  Java/JPA level `Material.lessonId` is a bare `UUID` — no `@ManyToOne`, no cross-domain
  entity import — the composite FK is a SQL-only cross-module reference, per
  `.claude/rules/architecture.md`'s boundary rules and `tenancy.md`'s mandatory
  composite-FK rule for cross-table tenant-owned references. See
  `docs/api/content-management.md` for the full endpoint contract.
- **`course_price_history`** — append-only. `CoursePriceHistoryRepository` overrides every
  delete-shaped method (including the three batch-delete variants Spring Data exposes) to
  throw `UnsupportedOperationException`, making this **structural**, not just conventional
  — see §3's append-only-domain pattern. **V12 deliberately drops** (does not `SET NULL`)
  the FK from `course_price_history.course_id` to `course`, rather than cascading it on
  course deletion, so a course's price-change trail survives the course row's own deletion —
  per root `CLAUDE.md`'s "never delete financial history." This means new inserts to this
  column no longer have a DB-level existence guarantee; integrity for new rows relies
  entirely on `CourseService#changePrice` being the sole write path (verified: exactly one
  call site in the codebase), not on the schema. `tenant_id` and `changed_by` composite FKs
  are unaffected and remain enforced. `changePrice` writes the price update and the history
  row in one `@Transactional` boundary. This table is a domain-local record, **not** the
  platform's canonical compliance-grade audit log — `audit-log-management` doesn't exist
  yet; `CoursePriceChangedEvent` is published in the same transaction so that domain can
  later persist its own canonical row from the event with zero rework here. See
  `docs/requirements/open-decisions.md` for this limitation tracked as an open item.

## Related

- `docs/architecture/multi-tenancy.md`
- `docs/architecture/payment-ledger.md`
- `docs/architecture/enrollment-access.md`
- `docs/adr/ADR-002-shared-database-tenancy.md`
