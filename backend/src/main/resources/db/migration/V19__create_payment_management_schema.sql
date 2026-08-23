-- payment-management / ledger-settlement-management / enrollment-management
-- (MVP-010 Order and Payment Foundation): `student_order`, `payment`,
-- `ledger_entry`, `payment_refund`, `enrollment`.
--
-- One migration covers three conceptually distinct owning domains
-- (`com.lms.paymentmanagement`, `com.lms.ledgersettlementmanagement`,
-- `com.lms.enrollmentmanagement`) because they ship together in this module,
-- per docs/plans/MVP-010 Order and Payment Foundation.md §7-9/§20 - the same
-- precedent this schema already has of `V16` (content-management) shipping
-- alongside `V11`-`V15` (course-management) as a related-but-separate
-- domain. Each table below is still owned by exactly one domain package at
-- the Java/JPA level, per .claude/rules/architecture.md - no cross-domain
-- entity association is implied by any of these tables sharing a migration
-- file.
--
-- Every table here is tenant-owned: `tenant_id UUID NOT NULL REFERENCES
-- tenant (id)`, a composite index leading with `tenant_id` shaped to the
-- module's real query pattern, and every cross-table reference to another
-- tenant-owned table is a composite `(tenant_id, ...)` FK - never a bare FK
-- on the child id alone - so a cross-tenant reference is a schema
-- constraint violation, not just a service-layer bug (per
-- .claude/rules/tenancy.md and docs/architecture/database-architecture.md
-- §1). `id` has no DB-side DEFAULT on any table here - generated
-- application-side (UUIDv7 via com.lms.common.persistence.UuidV7Generator),
-- per V1's baseline convention. No FK in this migration carries
-- `ON DELETE CASCADE` - every one of these tables is either append-only or
-- must outlive its parent's lifecycle, per root CLAUDE.md's "never delete
-- financial history" and `.claude/rules/payments.md`.
--
-- Design decisions carried over verbatim from the approved plan (§7, §8,
-- §21) - do not deviate without a new product-owner/ADR decision:
--
--   * `student_order` (not `order`) - `order` is a reserved SQL keyword.
--     Renaming avoids having to double-quote it in every DDL statement, JPA
--     `@Table` annotation, and native query forever. This is a schema-naming
--     choice only; the aggregate is still conceptually "Order" at the
--     Java/API level.
--
--   * `student_order`/`enrollment` FK `student_id` to `tenant_user`, NOT
--     `student_profile` - following the exact precedent `course.teacher_id`
--     already set (V11): a cross-domain reference is a bare, opaque foreign
--     id into the foundational identity table, never the business-domain
--     profile table. This is a settled decision (plan §21 item 9), not
--     re-opened here.
--
--   * `student_order.status` is intentionally a minimal, incomplete-looking
--     enum (`PLACED`, `PENDING` only - no `CANCELLED`/`EXPIRED`). Order-
--     abandonment/cancellation policy is an explicitly unresolved business
--     decision (plan §21 item 13) - do not invent additional states here;
--     a future migration adds them once that policy is ratified.
--
--   * `currency VARCHAR(3)` on `student_order`/`payment` has no catalog to
--     validate against anywhere in this codebase - `course.price` itself
--     carries no currency column (V11's own header comment: "a single
--     implicit currency is assumed platform-wide/per-tenant at MVP"). This
--     column exists here only because the backlog's literal column list
--     names it; its value is a platform-wide constant resolved in the
--     service layer, not validated against any catalog table. Flagged as a
--     known gap (plan §21 item 7), not silently papered over.
--
--   * `payment.status` keeps `REFUNDED` in its CHECK enum for literal
--     fidelity to the original backlog/spec wording, but application code
--     must NEVER write it. Per `.claude/rules/payments.md` §1, a payment row
--     is immutable once it reaches a terminal state (`CONFIRMED`/
--     `REJECTED`); writing `REFUNDED` onto an already-`CONFIRMED` row would
--     be a second `UPDATE` on a terminal row, which is forbidden. A refund
--     is represented as a new `payment_refund` row plus a reversing
--     `ledger_entry` (`reverses_entry_id`), never a second `UPDATE` on this
--     row. This resolves the contradiction the module plan flagged
--     explicitly (§21 item 8) - `REFUNDED` is intentionally unreachable dead
--     code in the service layer, not an oversight a future reader should
--     "fix" by wiring it up.
--
--   * `ledger_entry.entry_type` CHECK is deliberately the minimal two-value
--     set (`PAYMENT_CONFIRMED`, `REFUND`) this module's own stories (PAY-3,
--     PAY-4) actually require. Per `.claude/rules/payments.md` §4 and
--     `docs/architecture/payment-ledger.md` §5, ledger entry types are
--     change-controlled - adding a third type (even a seemingly small one,
--     e.g. distinguishing a manual-slip-sourced confirmation from a gateway
--     one) requires an ADR before the CHECK constraint changes, not a
--     follow-up migration slipped in without one.
--
--   * `enrollment.activating_slip_id` has deliberately NO foreign key in
--     this migration - `payment_slip` (Module 11) does not exist yet. The
--     column is left nullable with no FK target; a *new* migration must add
--     `fk_enrollment_activating_slip` once Module 11's `payment_slip` table
--     exists. This migration is never edited to backfill that FK later -
--     migration history is append-only (plan §21 item 1, §8 table note).
--
--   * `enrollment.status` CHECK is a single-value enum (`ACTIVE` only) at
--     this minimal-slice stage. Course-level expiry (`ENR-2`) and
--     reactivation (`ENR-3`) states are explicitly out of this module's
--     scope (Module 12's job, plan §6) - do not add `EXPIRED`/`REACTIVATED`
--     values speculatively.
--
--   * `payment`/`payment_refund`/`ledger_entry` carry `created_at` (and
--     `payment` also `updated_at`, for its one narrow justified
--     `PENDING -> (CONFIRMED|REJECTED)` transition) but NO `created_by`/
--     `updated_by` - these tables' only writers are system/webhook-driven
--     service code, never a human-attributed edit, so those columns would
--     be permanently null/meaningless (mirroring `course_price_history`'s
--     precedent, V11, for append-only tables). `ledger_entry` and
--     `payment_refund` are fully append-only (insert-only, no `updated_at`
--     either), matching `course_price_history`'s exact shape.

-- ---------------------------------------------------------------------------
-- student_order (com.lms.paymentmanagement.domain) - PAY-1
-- ---------------------------------------------------------------------------

CREATE TABLE student_order (
    id          UUID PRIMARY KEY,
    tenant_id   UUID NOT NULL REFERENCES tenant (id),
    student_id  UUID NOT NULL,
    course_id   UUID NOT NULL,
    amount      NUMERIC(12,2) NOT NULL,
    currency    VARCHAR(3) NOT NULL,
    status      VARCHAR(20) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL,
    created_by  UUID,
    updated_by  UUID,

    CONSTRAINT uq_student_order_tenant_id UNIQUE (tenant_id, id),

    CONSTRAINT fk_student_order_course FOREIGN KEY (tenant_id, course_id)
        REFERENCES course (tenant_id, id),
    -- Cross-domain reference to the foundational identity table, not
    -- student-management's `student_profile` - see header comment.
    CONSTRAINT fk_student_order_student FOREIGN KEY (tenant_id, student_id)
        REFERENCES tenant_user (tenant_id, id),

    -- Matches course.price's own `>= 0` convention exactly - amount is a
    -- snapshot of that same column at order-creation time, so the same
    -- "future $0/trial course" reasoning applies here too.
    CONSTRAINT ck_student_order_amount CHECK (amount >= 0),
    CONSTRAINT ck_student_order_currency CHECK (char_length(currency) = 3),
    -- Intentionally incomplete enum - see header comment. PLACED = order
    -- created, no payment attempt yet. PENDING = a payment attempt has been
    -- initiated (gateway redirect in progress) but not yet confirmed.
    CONSTRAINT ck_student_order_status CHECK (status IN ('PLACED', 'PENDING'))
);

-- Student's own order history / "my orders" read pattern within a tenant.
CREATE INDEX idx_student_order_tenant_student
    ON student_order (tenant_id, student_id);

-- Status-filtered, time-ordered listing (e.g. an admin/ops view of open
-- orders), matching the same shape as course's own status/created_at index.
CREATE INDEX idx_student_order_tenant_status_created_at
    ON student_order (tenant_id, status, created_at DESC);

-- ---------------------------------------------------------------------------
-- payment (com.lms.paymentmanagement.domain) - PAY-2
-- ---------------------------------------------------------------------------

CREATE TABLE payment (
    id                 UUID PRIMARY KEY,
    tenant_id          UUID NOT NULL REFERENCES tenant (id),
    order_id           UUID NOT NULL,
    amount             NUMERIC(12,2) NOT NULL,
    currency           VARCHAR(3) NOT NULL,
    status             VARCHAR(20) NOT NULL,
    gateway_reference  VARCHAR(255),
    confirmed_at       TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL,
    updated_at         TIMESTAMPTZ NOT NULL,

    CONSTRAINT uq_payment_tenant_id UNIQUE (tenant_id, id),

    CONSTRAINT fk_payment_order FOREIGN KEY (tenant_id, order_id)
        REFERENCES student_order (tenant_id, id),

    CONSTRAINT ck_payment_amount CHECK (amount > 0),
    CONSTRAINT ck_payment_currency CHECK (char_length(currency) = 3),
    -- REFUNDED is kept for literal fidelity to the original backlog/spec
    -- wording, but application code must NEVER write it onto this column -
    -- see header comment ("payment.status keeps REFUNDED..."). A refund is
    -- a new payment_refund row + reversing ledger_entry, never a second
    -- UPDATE here.
    CONSTRAINT ck_payment_status CHECK (
        status IN ('PENDING', 'CONFIRMED', 'REJECTED', 'REFUNDED')
    ),
    CONSTRAINT ck_payment_confirmed_requires_reference CHECK (
        status <> 'CONFIRMED' OR gateway_reference IS NOT NULL
    )
);

-- Status-filtered, time-ordered listing (Payment Dashboard read pattern).
CREATE INDEX idx_payment_tenant_status_created_at
    ON payment (tenant_id, status, created_at DESC);

-- "All payments for one order" read pattern.
CREATE INDEX idx_payment_tenant_order ON payment (tenant_id, order_id);

-- Concrete DB-level idempotency guard for duplicate webhook delivery
-- (PAY-2's mandatory idempotency requirement): a second webhook for the
-- same gateway_reference must hit this constraint, not rely on an
-- application-only check-then-insert race. Expressed as a partial unique
-- index, not a table-level UNIQUE constraint, because a plain UNIQUE
-- constraint cannot carry a WHERE clause and gateway_reference is NULL for
-- every non-terminal PENDING payment (which must not collide with each
-- other under a NULL-inclusive uniqueness rule).
--
-- Deliberately GLOBAL (not tenant-scoped): a gateway reference is an
-- externally-issued, platform-wide identifier (see
-- integrationmanagement.gateway.FakePaymentGatewayAdapter, which mints
-- UUID-based references), and the webhook-confirmation path's sole
-- tenant-resolution mechanism (payment-management's
-- PaymentRepository#findByGatewayReferenceAcrossTenants, called with no
-- tenant predicate at all, per plan §14/§21) depends on this value
-- resolving to at most one payment ACROSS THE WHOLE PLATFORM, not just
-- within one tenant. A tenant-scoped `(tenant_id, gateway_reference)`
-- constraint would let two different tenants coincidentally collide on the
-- same reference without violating the schema, which would make that
-- lookup throw a non-unique-result error (or, worse with a different query
-- shape, silently resolve to the wrong tenant's payment) - security-review
-- finding, fixed here rather than left as a known gap, since it is cheap
-- and correct to close outright.
CREATE UNIQUE INDEX uq_payment_gateway_reference
    ON payment (gateway_reference)
    WHERE gateway_reference IS NOT NULL;

-- ---------------------------------------------------------------------------
-- ledger_entry (com.lms.ledgersettlementmanagement.domain) - PAY-3
-- ---------------------------------------------------------------------------

CREATE TABLE ledger_entry (
    id                 UUID PRIMARY KEY,
    tenant_id          UUID NOT NULL REFERENCES tenant (id),
    order_id           UUID NOT NULL,
    payment_id         UUID,
    entry_type         VARCHAR(30) NOT NULL,
    amount             NUMERIC(12,2) NOT NULL,
    reverses_entry_id  UUID,
    created_at         TIMESTAMPTZ NOT NULL,

    CONSTRAINT uq_ledger_entry_tenant_id UNIQUE (tenant_id, id),

    CONSTRAINT fk_ledger_entry_order FOREIGN KEY (tenant_id, order_id)
        REFERENCES student_order (tenant_id, id),
    -- Nullable - payment_id leaves room for a future non-payment-table
    -- source (e.g. Module 11's manual slip path) without a schema change;
    -- order_id is the mandatory traceability anchor every entry in this
    -- module's scope has.
    CONSTRAINT fk_ledger_entry_payment FOREIGN KEY (tenant_id, payment_id)
        REFERENCES payment (tenant_id, id),
    -- Nullable self-reference: a reversal/correction entry points back at
    -- the entry it reverses. Composite FK means "reversing another
    -- tenant's entry" is a constraint violation, not a service-layer bug.
    CONSTRAINT fk_ledger_entry_reverses FOREIGN KEY (tenant_id, reverses_entry_id)
        REFERENCES ledger_entry (tenant_id, id),

    CONSTRAINT ck_ledger_entry_amount_nonzero CHECK (amount <> 0),
    -- Minimal, change-controlled enum - see header comment. Adding a third
    -- type requires an ADR (.claude/rules/payments.md §4), even for a
    -- seemingly small addition.
    CONSTRAINT ck_ledger_entry_type CHECK (
        entry_type IN ('PAYMENT_CONFIRMED', 'REFUND')
    )
);

-- "All ledger entries for one payment" read pattern (e.g. reconciling a
-- payment's full history including any reversal).
CREATE INDEX idx_ledger_entry_tenant_payment
    ON ledger_entry (tenant_id, payment_id);

-- "All ledger entries for one order" read pattern.
CREATE INDEX idx_ledger_entry_tenant_order
    ON ledger_entry (tenant_id, order_id);

-- Time-ordered listing (Payment History / Payment Dashboard read pattern,
-- both of which must be ledger-derived, never order/payment-status-derived,
-- per .claude/rules/payments.md §2).
CREATE INDEX idx_ledger_entry_tenant_created_at
    ON ledger_entry (tenant_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- payment_refund (com.lms.paymentmanagement.domain) - PAY-4
-- ---------------------------------------------------------------------------

CREATE TABLE payment_refund (
    id                    UUID PRIMARY KEY,
    tenant_id             UUID NOT NULL REFERENCES tenant (id),
    original_payment_id   UUID NOT NULL,
    amount                NUMERIC(12,2) NOT NULL,
    reason                TEXT NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_payment_refund_original_payment
        FOREIGN KEY (tenant_id, original_payment_id)
        REFERENCES payment (tenant_id, id),

    CONSTRAINT ck_payment_refund_amount CHECK (amount > 0),
    -- Reason is mandatory and non-blank by analogy to the manual-slip
    -- override-reason rule (.claude/rules/payments.md §3): a refund with no
    -- recorded reason is not a valid refund.
    CONSTRAINT ck_payment_refund_reason_not_blank CHECK (btrim(reason) <> '')
);

-- "All refunds against one payment" read pattern (also backs the
-- service-layer "sum of prior refunds cannot exceed original amount" guard,
-- which needs cross-row awareness a single CHECK cannot express).
CREATE INDEX idx_payment_refund_tenant_original_payment
    ON payment_refund (tenant_id, original_payment_id);

-- ---------------------------------------------------------------------------
-- enrollment (com.lms.enrollmentmanagement.domain) - minimal activation
-- slice (plan §21 item 1's central scoping decision)
-- ---------------------------------------------------------------------------

CREATE TABLE enrollment (
    id                     UUID PRIMARY KEY,
    tenant_id              UUID NOT NULL REFERENCES tenant (id),
    student_id             UUID NOT NULL,
    course_id              UUID NOT NULL,
    activating_payment_id  UUID,
    -- No FK target yet - payment_slip (Module 11) does not exist. See
    -- header comment: a NEW migration adds fk_enrollment_activating_slip
    -- once that table exists; this migration is never edited to add it
    -- retroactively.
    activating_slip_id     UUID,
    status                 VARCHAR(20) NOT NULL,
    activated_at           TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_enrollment_course FOREIGN KEY (tenant_id, course_id)
        REFERENCES course (tenant_id, id),
    CONSTRAINT fk_enrollment_student FOREIGN KEY (tenant_id, student_id)
        REFERENCES tenant_user (tenant_id, id),
    CONSTRAINT fk_enrollment_activating_payment
        FOREIGN KEY (tenant_id, activating_payment_id)
        REFERENCES payment (tenant_id, id),

    -- Backs idempotent activation: a repeated activation call for the same
    -- student+course must be a no-op via this constraint, not a second row.
    CONSTRAINT uq_enrollment_tenant_student_course
        UNIQUE (tenant_id, student_id, course_id),

    -- ENR-1's literal, load-bearing invariant: activation must trace to
    -- exactly one confirmed-payment or approved-slip row, never a bare
    -- boolean flag (docs/architecture/database-architecture.md §4).
    CONSTRAINT ck_enrollment_exactly_one_activation_source CHECK (
        (activating_payment_id IS NOT NULL AND activating_slip_id IS NULL) OR
        (activating_payment_id IS NULL AND activating_slip_id IS NOT NULL)
    ),

    -- Intentionally a single-value enum at this minimal-slice stage - see
    -- header comment. Course-level expiry (ENR-2) and reactivation (ENR-3)
    -- are Module 12's job, out of this module's scope.
    CONSTRAINT ck_enrollment_status CHECK (status = 'ACTIVE')
);

-- Student's own enrollment list within a tenant.
CREATE INDEX idx_enrollment_tenant_student ON enrollment (tenant_id, student_id);

-- "Who is enrolled in this course" read pattern within a tenant.
CREATE INDEX idx_enrollment_tenant_course ON enrollment (tenant_id, course_id);
