-- enrollment-management (MVP-012 Enrollment and Course Access): extends
-- `enrollment` with course-level expiry/lineage columns and adds
-- `enrollment_expiry_event` and `reactivation_request`. Also closes one
-- data-integrity gap discovered against the already-shared `enrollment`
-- table while implementing this module's own composite FKs into it: a
-- missing `UNIQUE (tenant_id, id)` constraint (see the dedicated comment
-- above `uq_enrollment_tenant_id` below).
--
-- Both structural decisions implemented here (the enrollment lineage-row
-- model, and the reactivation-request workflow feeding `OrderService`'s new
-- order-creation gate) were reviewed and approved by the product owner
-- BEFORE this migration was written, per
-- docs/adr/ADR-013-enrollment-lineage-and-reactivation-order-gate.md - do
-- not re-derive or second-guess those decisions here; this file only
-- implements what ADR-013 already ratified.
--
-- Ownership (per .claude/rules/architecture.md - one Java package per
-- domain, even when multiple tables/alterations ship in one migration
-- file, mirroring V19/V21's own precedent):
--   * `enrollment`'s three new columns (`access_expires_at`,
--     `superseded_at`, `reactivated_from_enrollment_id`) and
--     `enrollment_expiry_event` -> com.lms.enrollmentmanagement.domain
--   * `reactivation_request` -> com.lms.enrollmentmanagement.domain
--
-- V19 (`enrollment` origin), V20, and V21 (`fk_enrollment_activating_slip`,
-- `payment_slip`) are NOT edited by this migration - they are already
-- shared/applied and this repo's migration history is append-only (root
-- CLAUDE.md, .claude/rules/tenancy.md). Every change below is a new,
-- additive `ALTER TABLE`/`CREATE TABLE`/`CREATE INDEX` statement only.
--
-- Every table here is tenant-owned: `tenant_id UUID NOT NULL REFERENCES
-- tenant (id)`, a composite index leading with `tenant_id` shaped to the
-- module's real query pattern, and every cross-table reference to another
-- tenant-owned table is a composite `(tenant_id, ...)` FK - never a bare FK
-- on the child id alone - per .claude/rules/tenancy.md. `id` has no DB-side
-- DEFAULT on any table here - generated application-side (UUIDv7 via
-- com.lms.common.persistence.UuidV7Generator), per V1's baseline
-- convention, matching every other table in this schema. No FK in this
-- migration carries `ON DELETE CASCADE` - `enrollment`,
-- `enrollment_expiry_event`, and `reactivation_request` are all
-- access/financial-adjacent history that must outlive their parents, per
-- root CLAUDE.md's "never delete financial history" and
-- .claude/rules/payments.md.
--
-- Why the plain UNIQUE(tenant_id, student_id, course_id) on `enrollment`
-- (V19) is replaced by a partial unique index, not mutated in place
-- (ADR-013, "Enrollment lineage-row model" - Option (A)):
--
--   V19 modeled "one immutable row per (tenant, student, course), forever."
--   Course-level expiry (ENR-2) and reactivation (ENR-3) need a student to
--   pass through this table more than once over time (activate -> expire ->
--   reactivate -> ...) without ever overwriting the original activation
--   evidence (`activating_payment_id`/`activating_slip_id`/`activated_at`),
--   per the "full timeline reconstructable from history alone" requirement
--   in docs/architecture/enrollment-access.md §7. A plain
--   UNIQUE(tenant_id, student_id, course_id) constraint makes that
--   impossible - it structurally forbids a second row for the same
--   (student, course) pair even after the first is logically superseded.
--
--   The lineage-row model (ADR-013 Option (A), the approved choice) instead:
--     - drops the plain unique constraint entirely, and
--     - adds a partial unique index scoped to "current" rows only
--       (`WHERE superseded_at IS NULL`), so at most one CURRENT row can
--       exist per (tenant, student, course) at any time, while any number
--       of SUPERSEDED (historical) rows may coexist for the same pair.
--   A reactivation is then: set `superseded_at = now()` on the prior
--   current row (its only mutation, ever - every other column on that row,
--   including `activating_payment_id`/`activating_slip_id`/`activated_at`,
--   stays `updatable=false` at the JPA level per ADR-013) and INSERT a
--   brand-new row with its own new `activating_payment_id`/
--   `activating_slip_id`/`activated_at`/`access_expires_at`, linked back via
--   `reactivated_from_enrollment_id`. This makes "at most one current row"
--   and "lineage is queryable" database-enforced invariants rather than
--   service-layer discipline alone (ADR-013 Consequences).
--
--   Option (B) (mutate `access_expires_at`/`activating_payment_id` in place
--   on the single existing row) was explicitly considered and rejected in
--   ADR-013 because it would overwrite the original activation evidence -
--   see ADR-013 "Alternatives considered".
--
-- Lock-safety note on this migration's `ALTER TABLE enrollment` statements
-- (contrast with V21's own caution comment on its
-- `fk_enrollment_activating_slip` addition):
--
--   V21's ADD CONSTRAINT was safe as a single blocking statement only
--   because `enrollment` was still empty at that point in migration
--   history (no application traffic had run yet). That is no longer true
--   here - by the time V22 runs, `enrollment` may hold real rows written
--   by the already-shipped MVP-010/MVP-011 activation paths. Despite that,
--   this migration deliberately does NOT use `ADD CONSTRAINT ... NOT VALID`
--   + a separate `VALIDATE CONSTRAINT` for either the new
--   `fk_enrollment_reactivated_from` FK or the dropped/replaced unique
--   constraint, for a concrete correctness reason rather than convenience:
--
--     - `fk_enrollment_reactivated_from` references a column
--       (`reactivated_from_enrollment_id`) that is being added by this same
--       statement batch and is NULL on every existing row (no prior
--       migration ever populated it) - there is no pre-existing data for
--       Postgres to validate against, so the FK validation scan is
--       effectively instantaneous regardless of table size, and `NOT VALID`
--       would add process (a second deploy step to run `VALIDATE
--       CONSTRAINT`) for no real benefit.
--     - Dropping `uq_enrollment_tenant_student_course` and creating
--       `uq_enrollment_tenant_student_course_current` do carry a real lock
--       cost proportional to table size (`DROP CONSTRAINT` on the old
--       unique index is fast/metadata-only, but `CREATE UNIQUE INDEX`
--       requires a full table scan to check the new partial-uniqueness
--       condition). Unlike a `NOT VALID` FK, `CREATE UNIQUE INDEX` has no
--       "validate later" split - `CREATE UNIQUE INDEX CONCURRENTLY` is the
--       actual non-blocking alternative, but it cannot run inside a
--       transaction block, which conflicts with Flyway's default
--       single-transaction-per-migration execution in this project. Given
--       this module's real current data volume (pre-launch/low-row-count
--       tenants only, per the plan's own MVP framing), a brief blocking
--       index build here is an accepted, explicitly-noted trade-off, not an
--       oversight - if `enrollment` ever grows large enough for this to
--       matter operationally, a future migration should switch to
--       `CREATE UNIQUE INDEX CONCURRENTLY` run outside a transaction
--       (a Flyway `executeInTransaction = false` migration), not a rewrite
--       of this one.

-- ---------------------------------------------------------------------------
-- enrollment (com.lms.enrollmentmanagement.domain) - additive lineage/expiry
-- columns, replacing the plain per-(student,course) unique constraint with a
-- "current row" partial unique index. See header comment for full rationale.
-- ---------------------------------------------------------------------------

ALTER TABLE enrollment
    ADD COLUMN access_expires_at TIMESTAMPTZ,
    ADD COLUMN superseded_at TIMESTAMPTZ,
    ADD COLUMN reactivated_from_enrollment_id UUID;

-- Data-integrity gap fix, discovered while adding the composite FKs below:
-- unlike every other tenant-owned table in this schema (student_order,
-- payment, payment_slip, tenant_user, ...), V19's `enrollment` was never
-- given a `UNIQUE (tenant_id, id)` constraint - only a bare
-- `PRIMARY KEY (id)`. A composite `(tenant_id, id)` FK referencing a table
-- requires a matching unique constraint on the referenced columns
-- (Postgres: "there is no unique constraint matching given keys"), so this
-- migration's own `fk_enrollment_reactivated_from` /
-- `fk_enrollment_expiry_event_enrollment` /
-- `fk_reactivation_request_enrollment` (below) cannot be created without
-- it. Fixed here as a new, additive ALTER TABLE - mirroring V20's own
-- precedent of closing a V19 data-integrity gap via additive DDL rather
-- than editing V19 - not folded silently into one of the FK statements
-- below so the gap and its fix stay visible in review.
ALTER TABLE enrollment
    ADD CONSTRAINT uq_enrollment_tenant_id UNIQUE (tenant_id, id);

-- Nullable self-reference: a reactivated enrollment row points back at the
-- prior (now-superseded) row it replaces. Composite FK means "reactivating
-- from another tenant's enrollment" is a constraint violation, not a
-- service-layer bug - same pattern as ledger_entry.fk_ledger_entry_reverses
-- (V19).
ALTER TABLE enrollment
    ADD CONSTRAINT fk_enrollment_reactivated_from
        FOREIGN KEY (tenant_id, reactivated_from_enrollment_id)
        REFERENCES enrollment (tenant_id, id);

-- Replaced by uq_enrollment_tenant_student_course_current below - see header
-- comment for why a plain per-(student,course) unique constraint is
-- incompatible with the lineage-row model.
ALTER TABLE enrollment DROP CONSTRAINT uq_enrollment_tenant_student_course;

-- At most one CURRENT (non-superseded) enrollment row per (tenant, student,
-- course); any number of superseded (historical) rows may coexist for the
-- same pair. Leads with tenant_id per this schema's tenant-composite-index
-- convention.
CREATE UNIQUE INDEX uq_enrollment_tenant_student_course_current
    ON enrollment (tenant_id, student_id, course_id)
    WHERE superseded_at IS NULL;

-- ---------------------------------------------------------------------------
-- enrollment_expiry_event (com.lms.enrollmentmanagement.domain) - ENR-2.
-- Append-only: an expiry is recorded as its own event row, never a mutation
-- of the enrollment row it concerns (root CLAUDE.md "never delete financial
-- history" / docs/architecture/enrollment-access.md §7's "expiry never
-- mutates the original enrollment row" requirement). One row per
-- (enrollment, event_type) at this MVP's scope - only `EXPIRED` exists today
-- (plan §21: no grace-period/precedence-engine event types speculatively
-- added here).
-- ---------------------------------------------------------------------------

CREATE TABLE enrollment_expiry_event (
    id            UUID PRIMARY KEY,
    tenant_id     UUID NOT NULL REFERENCES tenant (id),
    enrollment_id UUID NOT NULL,
    event_type    VARCHAR(20) NOT NULL,
    occurred_at   TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_enrollment_expiry_event_enrollment
        FOREIGN KEY (tenant_id, enrollment_id) REFERENCES enrollment (tenant_id, id),
    -- Minimal, single-value enum at this MVP's scope, mirroring
    -- enrollment.ck_enrollment_status's (V19) own "intentionally a
    -- single-value enum" precedent - do not add further values
    -- speculatively; a future value is a new migration once a real need is
    -- ratified.
    CONSTRAINT ck_enrollment_expiry_event_type CHECK (event_type = 'EXPIRED')
);

-- Idempotency guard: the expiry-detection path (a repeated read/scan past
-- access_expires_at for the same enrollment) must be a database-enforced
-- no-op on a second write, not rely on a service-layer check-then-insert
-- race guard alone - mirrors uq_ledger_entry_tenant_payment_confirmed's
-- (V20) "second, independent line of defense" reasoning.
CREATE UNIQUE INDEX uq_enrollment_expiry_event_tenant_enrollment_type
    ON enrollment_expiry_event (tenant_id, enrollment_id, event_type);

-- "Full expiry history for one enrollment" read pattern.
CREATE INDEX idx_enrollment_expiry_event_tenant_enrollment
    ON enrollment_expiry_event (tenant_id, enrollment_id);

-- ---------------------------------------------------------------------------
-- reactivation_request (com.lms.enrollmentmanagement.domain) - ENR-3.
-- A student's request to regain access to an expired enrollment; approval is
-- a distinct, admin-gated state machine (mirroring payment_slip's
-- SUBMITTED -> UNDER_REVIEW -> APPROVED|REJECTED shape from V21, but this
-- table's own narrower SUBMITTED -> APPROVED|REJECTED per the approved plan
-- §8 sketch - no UNDER_REVIEW state was specified for this workflow).
-- Reactivation itself never happens as a side effect of this table alone -
-- see ADR-013's `OrderService` gate: an APPROVED, unfulfilled
-- (new_order_id IS NULL) request must be linked to a new order before any
-- enrollment row is written.
-- ---------------------------------------------------------------------------

CREATE TABLE reactivation_request (
    id            UUID PRIMARY KEY,
    tenant_id     UUID NOT NULL REFERENCES tenant (id),
    enrollment_id UUID NOT NULL,
    requested_by  UUID NOT NULL,
    status        VARCHAR(20) NOT NULL,
    reviewed_by   UUID,
    reviewed_at   TIMESTAMPTZ,
    new_order_id  UUID,
    created_at    TIMESTAMPTZ NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL,

    CONSTRAINT uq_reactivation_request_tenant_id UNIQUE (tenant_id, id),

    CONSTRAINT fk_reactivation_request_enrollment
        FOREIGN KEY (tenant_id, enrollment_id) REFERENCES enrollment (tenant_id, id),
    -- Cross-domain reference to the foundational identity table, not
    -- student-management's `student_profile` - same precedent V19/V21
    -- already set for student_order.student_id / payment_slip.student_id.
    CONSTRAINT fk_reactivation_request_requested_by
        FOREIGN KEY (tenant_id, requested_by) REFERENCES tenant_user (tenant_id, id),
    -- Nullable until reviewed - see
    -- ck_reactivation_request_reviewed_requires_reviewer.
    CONSTRAINT fk_reactivation_request_reviewed_by
        FOREIGN KEY (tenant_id, reviewed_by) REFERENCES tenant_user (tenant_id, id),
    -- Nullable until OrderService's reactivation gate links a new order onto
    -- an APPROVED, unfulfilled request (ADR-013) - never populated at
    -- request-creation time.
    CONSTRAINT fk_reactivation_request_new_order
        FOREIGN KEY (tenant_id, new_order_id) REFERENCES student_order (tenant_id, id),

    CONSTRAINT ck_reactivation_request_status
        CHECK (status IN ('SUBMITTED', 'APPROVED', 'REJECTED')),
    -- Mirrors payment.ck_payment_confirmed_requires_reference (V19) and
    -- payment_slip.ck_payment_slip_reviewed_requires_reviewer (V21): a
    -- terminal review decision must carry who/when, never a bare status
    -- flip.
    CONSTRAINT ck_reactivation_request_reviewed_requires_reviewer
        CHECK (status NOT IN ('APPROVED', 'REJECTED')
               OR (reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL))
);

-- At most one open (SUBMITTED) request per enrollment - a student may not
-- have two concurrent pending reactivation requests for the same expired
-- enrollment, mirroring payment_slip's
-- uq_payment_slip_tenant_order_active (V21) "at most one active row"
-- pattern. Terminal (APPROVED/REJECTED) requests are deliberately excluded,
-- same rationale as V21's own partial index.
CREATE UNIQUE INDEX uq_reactivation_request_tenant_enrollment_open
    ON reactivation_request (tenant_id, enrollment_id)
    WHERE status = 'SUBMITTED';

-- Admin review-queue read pattern (status-filtered listing, e.g.
-- SUBMITTED-only queue).
CREATE INDEX idx_reactivation_request_tenant_status
    ON reactivation_request (tenant_id, status);

-- "All reactivation requests for one enrollment" read pattern (also backs
-- OrderService's reactivation-gate lookup: find the APPROVED, unfulfilled
-- request for the caller's current enrollment).
CREATE INDEX idx_reactivation_request_tenant_enrollment
    ON reactivation_request (tenant_id, enrollment_id);
