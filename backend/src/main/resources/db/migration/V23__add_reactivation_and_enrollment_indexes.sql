-- enrollment-management (MVP-012 Enrollment and Course Access): closes two
-- missing-index gaps found in this module's post-implementation
-- database-safety review, against the schema V22 already shipped. Purely
-- additive `CREATE INDEX` statements only - no table is locked beyond the
-- index build itself, no constraint is dropped/replaced, and neither V19
-- nor V22 is edited (both are already-shared/applied; this repo's migration
-- history is append-only per root CLAUDE.md and .claude/rules/tenancy.md).
--
-- Gap 1 - `reactivation_request` "my requests" query has no supporting
-- index:
--   `ReactivationRequestRepository#findAllByRequestedBy` (backing
--   `GET /api/v1/reactivation-requests/my`) filters
--   `WHERE tenant_id = :tenantId AND requested_by = :requestedBy`, paginated.
--   V22 gave this table indexes leading with `(tenant_id, status)` and
--   `(tenant_id, enrollment_id)` (its admin review-queue and
--   per-enrollment-history read patterns) but nothing keyed on
--   `requested_by` - a student's own request-history page was a sequential
--   scan of the whole table. `idx_reactivation_request_tenant_requested_by`
--   below closes this, leading with `tenant_id` per this schema's
--   tenant-composite-index convention (.claude/rules/backend.md).
--
-- Gap 2 - `enrollment`'s reactivation idempotency pre-checks have no index
-- at all:
--   `EnrollmentRepository#existsByActivatingPaymentId` /
--   `#existsByActivatingSlipId` back the idempotency guard now called at the
--   top of every reactivation attempt in
--   `EnrollmentActivationService`/`ReactivationTransactionService` (plan
--   §9/ADR-013: "checked BEFORE any mutation ... so a retried
--   webhook/approval ... is a true no-op"). V19 added
--   `fk_enrollment_activating_payment` (composite FK to `payment`) and V21
--   deliberately left `activating_slip_id` with NO foreign key (see V21's
--   own header comment), but neither V19 nor V22 ever indexed either
--   column - not bare, not tenant-composite. Every reactivation attempt was
--   therefore paying for a sequential scan of `enrollment` on its very
--   first check. Two separate partial indexes below (mirroring
--   `enrollment`'s own `ck_enrollment_exactly_one_activation_source` (V19)
--   shape, under which exactly one of the two columns is non-null on any
--   given row) - `WHERE ... IS NOT NULL` keeps each index small (only rows
--   actually activated via that source are indexed) and correctly skips
--   the majority of historical rows activated via the other source, rather
--   than one wider, mostly-NULL composite index covering both columns.
--   Both lead with `tenant_id`, matching every other index added to this
--   table (V19/V22) and how the repository's `exists(Specification)` call
--   is actually scoped once `TenantAwareRepositoryImpl`'s structural tenant
--   filter is applied (ADR-006) - the true predicate Postgres evaluates is
--   `tenant_id = ? AND activating_payment_id = ?` (respectively
--   `activating_slip_id`), not the bare column alone.
--
-- CONCURRENTLY was considered and deliberately NOT used, for the same
-- underlying constraint V22's own header comment already documented for
-- `uq_enrollment_tenant_student_course_current`, plus one additional reason
-- specific to this migration:
--
--   - `CREATE INDEX CONCURRENTLY` cannot run inside a transaction block, and
--     this project's Flyway configuration (`application-local.yml`'s
--     `spring.flyway` block, and every other migration in
--     `db/migration/`) runs every SQL migration in Flyway's default
--     single-transaction-per-migration mode - there is no `mixed` flag, no
--     per-script `executeInTransaction = false` override, and no existing
--     precedent for one anywhere in this repo today. Using `CONCURRENTLY`
--     here would not merely lose a non-blocking benefit, as it would have
--     for V22's unique index - it would make this migration fail outright
--     with Postgres error 25001 ("CREATE INDEX CONCURRENTLY cannot run
--     inside a transaction block") the first time it runs, since nothing in
--     this codebase currently takes a migration out of Flyway's default
--     transactional execution.
--   - Introducing non-transactional migration execution as a general
--     capability (a `mixed: true`/per-migration `executeInTransaction`
--     Flyway config change) is an application-configuration change, not a
--     schema change - out of scope for a task scoped to "one new,
--     migration-only file closing two index gaps," and not something to
--     bundle silently into a schema migration. If `enrollment` or
--     `reactivation_request` ever grow large enough that a brief blocking
--     index build becomes operationally risky, that Flyway config change
--     should be proposed and reviewed on its own, the same way V22 already
--     flagged it as a distinct future step rather than doing it inline.
--   - Independent of the Flyway constraint above, this project remains
--     pre-launch / low-row-count per tenant (same framing V22's header
--     already relied on for its own blocking-build trade-off) - three plain
--     `CREATE INDEX` statements building against today's data volume is a
--     brief, accepted blocking cost, not an oversight.

-- ---------------------------------------------------------------------------
-- reactivation_request (com.lms.enrollmentmanagement.domain) - Gap 1.
-- ---------------------------------------------------------------------------

CREATE INDEX idx_reactivation_request_tenant_requested_by
    ON reactivation_request (tenant_id, requested_by);

-- ---------------------------------------------------------------------------
-- enrollment (com.lms.enrollmentmanagement.domain) - Gap 2.
-- ---------------------------------------------------------------------------

CREATE INDEX idx_enrollment_tenant_activating_payment
    ON enrollment (tenant_id, activating_payment_id)
    WHERE activating_payment_id IS NOT NULL;

CREATE INDEX idx_enrollment_tenant_activating_slip
    ON enrollment (tenant_id, activating_slip_id)
    WHERE activating_slip_id IS NOT NULL;
