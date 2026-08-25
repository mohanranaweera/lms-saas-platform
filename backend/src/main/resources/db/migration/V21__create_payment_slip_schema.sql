-- payment-management (Payment Slip Intelligence sub-module) / audit-log-management
-- (MVP-011 Manual Payment Slips): `payment_slip`, `payment_slip_flag`,
-- `fk_enrollment_activating_slip` (the FK V19 deliberately deferred), and a
-- minimal `audit_log` table.
--
-- Per docs/plans/MVP-011 Manual Payment Slips.md §7-9, `payment_slip` and
-- `payment_slip_flag` are owned by `com.lms.paymentmanagement.slip.domain`
-- (not a new top-level domain - manual slips are a sub-module of
-- payment-management), and `audit_log` is owned by a new
-- `com.lms.auditlogmanagement.domain` package. One migration file covers both
-- because they ship together for this module, mirroring V19's own precedent
-- of shipping `payment-management`/`ledger-settlement-management`/
-- `enrollment-management` tables in one file - each table is still owned by
-- exactly one domain package at the Java/JPA level, per
-- .claude/rules/architecture.md.
--
-- Every table here is tenant-owned: `tenant_id UUID NOT NULL REFERENCES
-- tenant (id)`, a composite index leading with `tenant_id`, and every
-- cross-table reference to another tenant-owned table is a composite
-- `(tenant_id, ...)` FK - never a bare FK on the child id alone - per
-- .claude/rules/tenancy.md. `id` has no DB-side DEFAULT - generated
-- application-side (UUIDv7), per V1's baseline convention. No FK in this
-- migration carries `ON DELETE CASCADE` - `payment_slip`/`payment_slip_flag`/
-- `audit_log` are all financial/audit history that must outlive their
-- parents, per root CLAUDE.md's "never delete financial history" and
-- .claude/rules/security.md's audit-log append-only rule. V19 and V20 are
-- never edited - the `fk_enrollment_activating_slip` FK they both left open
-- is added below as a new, additive statement only.
--
-- Design decisions carried over from the approved plan (§8, §21), and the
-- three open design points (§21 items 5-7) resolved here rather than left
-- open, per this task's explicit instruction:
--
--   * §21 item 5 (append-only vs. in-place status update for
--     `payment_slip`): resolved as a narrow in-place `status` `UPDATE`,
--     mirroring `payment.status`'s existing `PENDING -> (CONFIRMED|REJECTED)`
--     precedent (V19), for consistency with the rest of this schema rather
--     than introducing a second modeling style in the same domain. Legal
--     transitions (`SUBMITTED -> UNDER_REVIEW -> APPROVED|REJECTED`) are
--     enforced at the service layer (mirroring `payment.status`'s own
--     enforcement, which also has no DB trigger); `updated_at` tracks the
--     last transition timestamp exactly like `payment.updated_at` does.
--     `payment_slip_flag` remains the one genuinely append-only table here
--     (no `updated_at`, no update/delete anywhere) since a flag must never
--     be cleared or overwritten, only added to - spec 25's explicit rule.
--
--   * §21 item 6 (`image_hash` synchronous vs. asynchronous): resolved as
--     synchronous - `image_hash VARCHAR NOT NULL`, computed at upload time
--     before the row is persisted, matching the plan's own §8 draft exactly.
--     No concrete reason was found in this codebase's existing upload paths
--     (`content-management`'s `MaterialService.createMaterial` computes its
--     own content-sniffing/validation synchronously, before persisting) to
--     require deferring hash computation to an async job, and synchronous
--     keeps the duplicate-check flow (which must run at submission time
--     regardless, per SLIP-2) simple - one write, no "hash pending"
--     intermediate state, no second async job needed to unblock the
--     duplicate-check gate.
--
--   * §21 item 7 (at-most-one-active-slip-per-order partial unique index):
--     resolved as ADDING one - `uq_payment_slip_tenant_order_active` below.
--     This was originally left open at implementation time (a product-policy
--     question the plan itself flagged for product-owner confirmation rather
--     than deciding), and has since been resolved by the product owner
--     during a later review pass (not part of the original implementation-
--     time design): a student may not submit a second concurrent
--     SUBMITTED/UNDER_REVIEW slip against an order that already has one.
--     `SlipUploadService.uploadSlip`'s `paymentSlipRepository.save` now
--     throws `DataIntegrityViolationException` (mapped to `409 CONFLICT` by
--     `GlobalExceptionHandler`) when this is violated.
--
--   * `audit_log` is the minimal `audit-log-management` forward-pull per the
--     product owner's approved decision (plan §21 item 2, option (A)): a
--     real, durable table with all identity/target/time columns `NOT NULL`
--     per .claude/rules/backend.md's "Schema-enforced invariants... Audit
--     log" section (`tenant_id`, `actor_id`, `action`, `target_entity`,
--     `target_id`, `occurred_at`). No read/query UI, no consumption of other
--     domains' pending events (e.g. `CoursePriceChangedEvent` stays
--     unconsumed) - intentionally the narrowest usable slice, mirroring how
--     V19 itself pulled forward a minimal `enrollment` table for MVP-010.
--     `target_id` deliberately carries no FK - `target_entity` names an
--     arbitrary table (`payment_slip`, `payment`, `course`, ...) this table
--     must stay decoupled from, so a single polymorphic FK is not possible;
--     the pairing is validated at the service layer, not the schema layer.
--     `actor_id` DOES carry a composite FK to `tenant_user` - the actor of
--     every audit-logged action in this codebase's current scope is always
--     an authenticated tenant user (never a platform-admin or system actor
--     yet), so that linkage is safe to enforce structurally now. Fully
--     append-only: no `updated_at`, no update/delete exposed anywhere,
--     mirroring `payment_slip_flag`/`ledger_entry`'s exact shape.

-- ---------------------------------------------------------------------------
-- payment_slip (com.lms.paymentmanagement.slip.domain) - SLIP-1/2/3
-- ---------------------------------------------------------------------------

CREATE TABLE payment_slip (
    id                   UUID PRIMARY KEY,
    tenant_id            UUID NOT NULL REFERENCES tenant (id),
    order_id             UUID NOT NULL,
    student_id           UUID NOT NULL,
    storage_object_key   VARCHAR NOT NULL,
    reference_number     VARCHAR NOT NULL,
    image_hash           VARCHAR NOT NULL,
    status               VARCHAR(20) NOT NULL,
    submitted_at         TIMESTAMPTZ NOT NULL,
    reviewer_id          UUID,
    reviewed_at          TIMESTAMPTZ,
    created_at           TIMESTAMPTZ NOT NULL,
    updated_at           TIMESTAMPTZ NOT NULL,

    CONSTRAINT uq_payment_slip_tenant_id UNIQUE (tenant_id, id),

    CONSTRAINT fk_payment_slip_order FOREIGN KEY (tenant_id, order_id)
        REFERENCES student_order (tenant_id, id),
    -- Cross-domain reference to the foundational identity table, not
    -- student-management's `student_profile` - same precedent V19 already
    -- set for `student_order.student_id`/`enrollment.student_id`.
    CONSTRAINT fk_payment_slip_student FOREIGN KEY (tenant_id, student_id)
        REFERENCES tenant_user (tenant_id, id),
    -- Nullable until reviewed - see ck_payment_slip_reviewed_requires_reviewer.
    CONSTRAINT fk_payment_slip_reviewer FOREIGN KEY (tenant_id, reviewer_id)
        REFERENCES tenant_user (tenant_id, id),

    CONSTRAINT ck_payment_slip_reference_number_not_blank CHECK (btrim(reference_number) <> ''),
    CONSTRAINT ck_payment_slip_image_hash_not_blank CHECK (btrim(image_hash) <> ''),
    CONSTRAINT ck_payment_slip_storage_object_key_not_blank CHECK (btrim(storage_object_key) <> ''),
    CONSTRAINT ck_payment_slip_status CHECK (status IN ('SUBMITTED', 'UNDER_REVIEW', 'APPROVED', 'REJECTED')),
    -- Mirrors payment.ck_payment_confirmed_requires_reference: a terminal
    -- review decision must carry who/when, never a bare status flip.
    CONSTRAINT ck_payment_slip_reviewed_requires_reviewer CHECK (
        status NOT IN ('APPROVED', 'REJECTED') OR (reviewer_id IS NOT NULL AND reviewed_at IS NOT NULL)
    )
);

-- Review-queue read pattern: SUBMITTED/UNDER_REVIEW slips, oldest first
-- (FIFO), avoiding reviewer starvation on old slips - matches
-- SlipReviewController.getReviewQueue's actual @PageableDefault
-- (submittedAt,ASC).
CREATE INDEX idx_payment_slip_tenant_status_submitted_at
    ON payment_slip (tenant_id, status, submitted_at DESC);

-- One active (SUBMITTED/UNDER_REVIEW) slip per order, per tenant - product-
-- owner-approved policy (see header comment, §21 item 7). Terminal
-- (APPROVED/REJECTED) slips are deliberately excluded from this constraint -
-- a resubmission against a terminal order is still allowed, mirroring this
-- schema's existing append-only-resubmission precedent.
CREATE UNIQUE INDEX uq_payment_slip_tenant_order_active
    ON payment_slip (tenant_id, order_id)
    WHERE status IN ('SUBMITTED', 'UNDER_REVIEW');

-- "All slips for one order" read pattern (e.g. second-slip-on-terminal-order
-- checks, order detail view).
CREATE INDEX idx_payment_slip_tenant_order ON payment_slip (tenant_id, order_id);

-- Student's own slip history / "my payment slips" read pattern.
CREATE INDEX idx_payment_slip_tenant_student ON payment_slip (tenant_id, student_id);

-- Critical duplicate-check lookup indexes (spec 25 §2/§7) - tenant-scoped,
-- NOT unique: a duplicate is a soft flag-and-review gate, never a hard
-- DB-level block (auto-flag, never auto-reject). No unique constraint on
-- (tenant_id, order_id)/(tenant_id, reference_number)/(tenant_id,
-- image_hash) is deliberate for the same reason plus the append-only
-- resubmission precedent (a rejected slip is terminal, so a resubmission is
-- a new row, mirroring payment.order_id's no-uniqueness precedent).
CREATE INDEX idx_payment_slip_tenant_reference_number
    ON payment_slip (tenant_id, reference_number);
CREATE INDEX idx_payment_slip_tenant_image_hash
    ON payment_slip (tenant_id, image_hash);

-- ---------------------------------------------------------------------------
-- payment_slip_flag (com.lms.paymentmanagement.slip.domain) - SLIP-2
-- ---------------------------------------------------------------------------

CREATE TABLE payment_slip_flag (
    id           UUID PRIMARY KEY,
    tenant_id    UUID NOT NULL REFERENCES tenant (id),
    slip_id      UUID NOT NULL,
    flag_type    VARCHAR(30) NOT NULL,
    detected_at  TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_payment_slip_flag_slip FOREIGN KEY (tenant_id, slip_id)
        REFERENCES payment_slip (tenant_id, id),

    -- Minimal, change-controlled enum matching spec 25's MVP scope
    -- (exact-match only). Mirrors ledger_entry.ck_ledger_entry_type's
    -- "adding a third value needs an ADR" precedent - OCR-derived flag types
    -- are explicitly Phase 3, not added speculatively here.
    CONSTRAINT ck_payment_slip_flag_flag_type CHECK (
        flag_type IN ('DUPLICATE_REFERENCE', 'DUPLICATE_IMAGE_HASH')
    )
);

-- Slip-detail "full flag history" read pattern (never latest-only - spec 25's
-- "never clear/overwrite a prior flag" rule means every row must stay
-- queryable).
CREATE INDEX idx_payment_slip_flag_tenant_slip ON payment_slip_flag (tenant_id, slip_id);

-- ---------------------------------------------------------------------------
-- enrollment.activating_slip_id FK - closes the gap V19/V20 deliberately left
-- open (payment_slip did not exist yet). V19 is never edited; this is a new,
-- additive ALTER statement only.
--
-- NOTE for future migrations: this single-statement ADD CONSTRAINT takes a
-- full validating lock on `enrollment` for the duration of the FK check.
-- Safe here only because both `enrollment` and `payment_slip` are empty at
-- this point in migration history (this table is created earlier in the same
-- migration sequence, before any application traffic exists). Do NOT reuse
-- this exact pattern for a future FK addition against a table that may
-- already hold rows - use `ADD CONSTRAINT ... NOT VALID` followed by a
-- separate `VALIDATE CONSTRAINT` statement instead, so the initial DDL only
-- takes a brief lock and the (potentially slow) existing-row validation runs
-- without blocking concurrent writes for its full duration.
-- ---------------------------------------------------------------------------

ALTER TABLE enrollment
    ADD CONSTRAINT fk_enrollment_activating_slip
    FOREIGN KEY (tenant_id, activating_slip_id)
    REFERENCES payment_slip (tenant_id, id);

-- ---------------------------------------------------------------------------
-- audit_log (com.lms.auditlogmanagement.domain) - minimal forward-pulled
-- slice, see header comment. No read/query API, no event consumption - a
-- narrow, durable write target only.
-- ---------------------------------------------------------------------------

CREATE TABLE audit_log (
    id             UUID PRIMARY KEY,
    tenant_id      UUID NOT NULL REFERENCES tenant (id),
    actor_id       UUID NOT NULL,
    action         VARCHAR(100) NOT NULL,
    target_entity  VARCHAR(100) NOT NULL,
    target_id      UUID NOT NULL,
    reason         TEXT,
    metadata       JSONB,
    occurred_at    TIMESTAMPTZ NOT NULL,

    CONSTRAINT uq_audit_log_tenant_id UNIQUE (tenant_id, id),

    -- Every audit-logged action in this codebase's current scope is
    -- performed by an authenticated tenant user - see header comment.
    CONSTRAINT fk_audit_log_actor FOREIGN KEY (tenant_id, actor_id)
        REFERENCES tenant_user (tenant_id, id),

    CONSTRAINT ck_audit_log_action_not_blank CHECK (btrim(action) <> ''),
    CONSTRAINT ck_audit_log_target_entity_not_blank CHECK (btrim(target_entity) <> ''),
    -- reason is nullable (most audited actions - e.g. a plain approve/reject
    -- - carry no reason), but a supplied reason must not be blank, mirroring
    -- payment_refund.ck_payment_refund_reason_not_blank. An override-approval
    -- reason is mandatory at the service layer (.claude/rules/payments.md
    -- §3), not at this shared table's schema layer, since not every audit_log
    -- row is an override.
    CONSTRAINT ck_audit_log_reason_not_blank CHECK (reason IS NULL OR btrim(reason) <> '')
);

-- "Full audit trail for one target entity" read pattern (e.g. a slip's own
-- override-audit history) - the one query shape this minimal slice must
-- already support even with no query UI, since SLIP-4's own audit write uses
-- it implicitly via (tenant_id, target_entity, target_id).
CREATE INDEX idx_audit_log_tenant_target
    ON audit_log (tenant_id, target_entity, target_id);

-- Time-ordered tenant-wide audit log read pattern (future Module 19 query
-- UI's baseline shape).
CREATE INDEX idx_audit_log_tenant_occurred_at
    ON audit_log (tenant_id, occurred_at DESC);

-- "Actions by one actor" read pattern (e.g. reviewing one staff member's
-- override history).
CREATE INDEX idx_audit_log_tenant_actor
    ON audit_log (tenant_id, actor_id);
