-- enrollment-management (Klass course/enrollment parity, KLASS-T2, ADR-028):
-- `course_participation` (free enrollment, D01), `content_entitlement` (paid
-- MONTHLY / MODULE / LESSON coverage, D02/D05/D10) and `lesson_progress`
-- (viewed = successful authorized launch, D14).
--
-- Schema contract: docs/plans/klass-enrollment-payment-parity.md §3 (V86).
--
-- Additive only. The existing `enrollment` table (V19/V21/V22/V47/V48) is
-- NOT altered and none of its rows is updated or deleted: every existing
-- enrollment keeps its payment/slip FK, its access window and its meaning,
-- now reinterpreted as legacy course-wide paid access (ADR-028 consequence
-- 5). No order, payment, slip, refund, ledger or audit row is written or
-- rewritten - payment/ledger history is untouched. `content_entitlement`
-- only ever references payment evidence; its rows are created by verified
-- confirmation / approved slip code paths (never by this migration).
--
-- Backfill (the only data written): one `course_participation` row per
-- distinct (tenant_id, student_id, course_id) that still has a NON-REVOKED
-- enrollment lineage, source = 'LEGACY_ENROLLMENT':
--   * A lineage (V22 reactivation chain via reactivated_from_enrollment_id)
--     is represented by its head row = the row no other row reactivates
--     from. Revocation (V47 revoked_at, set together with superseded_at by
--     EnrollmentActivationService#revoke) is recorded on the then-current
--     head. A triple qualifies when at least one of its lineage heads has
--     revoked_at IS NULL. So: a revoked lineage whose older superseded rows
--     were never revoked themselves does NOT qualify; a later fresh
--     enrollment (or a reactivation) after a revoke does qualify; an expired
--     but not revoked lineage qualifies (participation is navigation, not
--     access - the evaluator still checks paid access separately).
--   * enrolled_at = earliest activated_at among that triple's enrollment
--     rows (enrollment has no created_at; activated_at is NOT NULL).
--   * id = md5(tenant|student|course|'legacy-participation')::uuid -
--     deterministic, V56/V76 precedent (ids are otherwise app-generated).
-- Expected count = SELECT count(*) FROM (that DISTINCT triple set); the
-- migration RAISEs a NOTICE with the inserted count so it is visible in the
-- Flyway log.
--
-- `content_entitlement`'s FKs to `student_order` (order + student match),
-- `student_order_line` (order + line + coverage_key match) and
-- `payment_refund` (revoked_by_refund_id) are added in V87, where the line
-- table and the refund (tenant_id, id) key are created. V86 and V87 ship
-- together and no code writes content_entitlement before V87 exists, so
-- the table is empty when those FKs are validated.

-- ---------------------------------------------------------------------------
-- 1. course_participation (D01): one row per tenant + student + course.
--    Inserted with INSERT ... ON CONFLICT DO NOTHING (race-safe, idempotent).
--    Never revoked by payment events (D08).
-- ---------------------------------------------------------------------------

CREATE TABLE course_participation (
    id           UUID PRIMARY KEY,
    tenant_id    UUID NOT NULL REFERENCES tenant (id),
    student_id   UUID NOT NULL,
    course_id    UUID NOT NULL,
    enrolled_at  TIMESTAMPTZ NOT NULL,
    source       VARCHAR(30) NOT NULL,

    CONSTRAINT uq_course_participation_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_course_participation_tenant_student_course
        UNIQUE (tenant_id, student_id, course_id),

    -- Same opaque tenant_user reference as enrollment.student_id (V19).
    CONSTRAINT fk_course_participation_student FOREIGN KEY (tenant_id, student_id)
        REFERENCES tenant_user (tenant_id, id),
    -- No ON DELETE CASCADE, same as enrollment: a course with participants
    -- is not silently hard-deleted together with its student records.
    CONSTRAINT fk_course_participation_course FOREIGN KEY (tenant_id, course_id)
        REFERENCES course (tenant_id, id),

    CONSTRAINT ck_course_participation_source CHECK (
        source IN ('SELF_ENROLL', 'LEGACY_ENROLLMENT', 'PAID_ACTIVATION', 'STAFF_GRANT')
    )
);

-- Student's "my courses": served by uq_course_participation_tenant_student_course,
-- which leads with (tenant_id, student_id). The contract's separate
-- (tenant_id, student_id) index would be redundant (V13 precedent) and is
-- intentionally not created.

-- Participants of one course.
CREATE INDEX idx_course_participation_tenant_course
    ON course_participation (tenant_id, course_id);

-- Backfill from legacy enrollments (see header).
INSERT INTO course_participation (id, tenant_id, student_id, course_id, enrolled_at, source)
SELECT md5(t.tenant_id::text || t.student_id::text || t.course_id::text || 'legacy-participation')::uuid,
       t.tenant_id,
       t.student_id,
       t.course_id,
       t.enrolled_at,
       'LEGACY_ENROLLMENT'
FROM (
    SELECT e.tenant_id, e.student_id, e.course_id, min(e.activated_at) AS enrolled_at
    FROM enrollment e
    WHERE EXISTS (
        SELECT 1
        FROM enrollment h
        WHERE h.tenant_id = e.tenant_id
          AND h.student_id = e.student_id
          AND h.course_id = e.course_id
          AND h.revoked_at IS NULL
          AND NOT EXISTS (
              SELECT 1 FROM enrollment s
              WHERE s.tenant_id = h.tenant_id
                AND s.reactivated_from_enrollment_id = h.id)
    )
    GROUP BY e.tenant_id, e.student_id, e.course_id
) t;

DO $$
DECLARE
    backfilled BIGINT;
BEGIN
    SELECT count(*) INTO backfilled FROM course_participation WHERE source = 'LEGACY_ENROLLMENT';
    RAISE NOTICE 'V86: backfilled % course_participation rows from legacy enrollment', backfilled;
END;
$$;

-- ---------------------------------------------------------------------------
-- 2. content_entitlement (D02): paid coverage granted ONLY by a verified
--    gateway confirmation or an approved slip, in the same transaction.
--    Row columns are immutable after insert except the revoke columns
--    (revoked_at, revoked_reason, revoked_by_refund_id), set once on
--    chargeback revocation (D08). Never deleted.
--
--    coverage_key format (enforced by ck_content_entitlement_scope_shape;
--    UUIDs in canonical lower-case text form, as PostgreSQL's uuid::text and
--    Java's UUID#toString both produce):
--      MONTHLY:<courseId>:<yyyy>-<mm>   MODULE:<moduleId>   LESSON:<lessonId>
-- ---------------------------------------------------------------------------

CREATE TABLE content_entitlement (
    id                    UUID PRIMARY KEY,
    tenant_id             UUID NOT NULL REFERENCES tenant (id),
    student_id            UUID NOT NULL,
    scope                 VARCHAR(20) NOT NULL,
    course_id             UUID NOT NULL,
    module_id             UUID,
    lesson_id             UUID,
    period_year           SMALLINT,
    period_month          SMALLINT,
    coverage_key          VARCHAR(120) NOT NULL,
    order_id              UUID NOT NULL,
    -- FK to student_order_line added in V87.
    order_line_id         UUID NOT NULL,
    source_payment_id     UUID,
    source_slip_id        UUID,
    granted_at            TIMESTAMPTZ NOT NULL,
    -- Snapshot granted_at + course.access_duration_days (D10); NULL = no limit.
    valid_until           TIMESTAMPTZ,
    revoked_at            TIMESTAMPTZ,
    revoked_reason        TEXT,
    revoked_by_refund_id  UUID,

    CONSTRAINT uq_content_entitlement_tenant_id UNIQUE (tenant_id, id),
    -- Idempotent grant: at most one entitlement per order line.
    CONSTRAINT uq_content_entitlement_tenant_order_line UNIQUE (tenant_id, order_line_id),

    CONSTRAINT fk_content_entitlement_student FOREIGN KEY (tenant_id, student_id)
        REFERENCES tenant_user (tenant_id, id),
    CONSTRAINT fk_content_entitlement_course FOREIGN KEY (tenant_id, course_id)
        REFERENCES course (tenant_id, id),
    -- No cascade: deleting a module/lesson that paid coverage references is
    -- refused by the schema (the API maps it to 409).
    CONSTRAINT fk_content_entitlement_module FOREIGN KEY (tenant_id, module_id)
        REFERENCES course_module (tenant_id, id),
    CONSTRAINT fk_content_entitlement_lesson FOREIGN KEY (tenant_id, lesson_id)
        REFERENCES course_lesson (tenant_id, id),
    -- Order FK is added in V87 as (tenant_id, order_id, student_id) so the
    -- entitled student must be the order's student.
    CONSTRAINT fk_content_entitlement_source_payment FOREIGN KEY (tenant_id, source_payment_id)
        REFERENCES payment (tenant_id, id),
    CONSTRAINT fk_content_entitlement_source_slip FOREIGN KEY (tenant_id, source_slip_id)
        REFERENCES payment_slip (tenant_id, id),
    -- FK to payment_refund added in V87 (needs uq_payment_refund_tenant_id).

    CONSTRAINT ck_content_entitlement_scope CHECK (scope IN ('MONTHLY', 'MODULE', 'LESSON')),

    -- Exactly one piece of payment evidence (mirrors V19's
    -- ck_enrollment_exactly_one_activation_source).
    CONSTRAINT ck_content_entitlement_exactly_one_source CHECK (
        (source_payment_id IS NOT NULL AND source_slip_id IS NULL)
        OR (source_payment_id IS NULL AND source_slip_id IS NOT NULL)
    ),

    -- Scope shape + coverage_key format.
    CONSTRAINT ck_content_entitlement_scope_shape CHECK (
        (scope = 'MONTHLY'
            AND period_year IS NOT NULL AND period_month IS NOT NULL
            AND period_month BETWEEN 1 AND 12 AND period_year BETWEEN 2000 AND 2100
            AND module_id IS NULL AND lesson_id IS NULL
            AND coverage_key = 'MONTHLY:' || course_id::text || ':'
                || lpad(period_year::text, 4, '0') || '-' || lpad(period_month::text, 2, '0'))
        OR (scope = 'MODULE'
            AND module_id IS NOT NULL AND lesson_id IS NULL
            AND period_year IS NULL AND period_month IS NULL
            AND coverage_key = 'MODULE:' || module_id::text)
        OR (scope = 'LESSON'
            AND lesson_id IS NOT NULL
            AND period_year IS NULL AND period_month IS NULL
            AND coverage_key = 'LESSON:' || lesson_id::text)
    ),

    CONSTRAINT ck_content_entitlement_valid_until CHECK (
        valid_until IS NULL OR valid_until > granted_at
    ),

    -- Revocation is all-or-nothing on (revoked_at, revoked_reason); a
    -- refund reference only on a revoked row.
    CONSTRAINT ck_content_entitlement_revocation CHECK (
        (revoked_at IS NULL AND revoked_reason IS NULL AND revoked_by_refund_id IS NULL)
        OR (revoked_at IS NOT NULL AND revoked_reason IS NOT NULL AND btrim(revoked_reason) <> '')
    )
);

-- At most one ACTIVE (non-revoked) entitlement per student per coverage.
CREATE UNIQUE INDEX uq_content_entitlement_active_coverage
    ON content_entitlement (tenant_id, student_id, coverage_key)
    WHERE revoked_at IS NULL;

-- Evaluator batch read: all entitlements of one student for one course.
CREATE INDEX idx_content_entitlement_tenant_student_course
    ON content_entitlement (tenant_id, student_id, course_id);

-- Chargeback revocation: entitlements granted by one payment / one slip.
CREATE INDEX idx_content_entitlement_tenant_source_payment
    ON content_entitlement (tenant_id, source_payment_id)
    WHERE source_payment_id IS NOT NULL;

CREATE INDEX idx_content_entitlement_tenant_source_slip
    ON content_entitlement (tenant_id, source_slip_id)
    WHERE source_slip_id IS NOT NULL;

-- All entitlements of one order.
CREATE INDEX idx_content_entitlement_tenant_order
    ON content_entitlement (tenant_id, order_id);

-- Module/lesson delete guards (409) and FK checks on parent delete.
CREATE INDEX idx_content_entitlement_tenant_module
    ON content_entitlement (tenant_id, module_id)
    WHERE module_id IS NOT NULL;

CREATE INDEX idx_content_entitlement_tenant_lesson
    ON content_entitlement (tenant_id, lesson_id)
    WHERE lesson_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 3. lesson_progress (D14): first successful authorized launch per student
--    per lesson. Idempotent upsert (ON CONFLICT DO NOTHING). Not financial;
--    removed together with its lesson/course on hard-delete.
-- ---------------------------------------------------------------------------

CREATE TABLE lesson_progress (
    id               UUID PRIMARY KEY,
    tenant_id        UUID NOT NULL REFERENCES tenant (id),
    student_id       UUID NOT NULL,
    -- Course whose content tree the lesson was launched from (for linked
    -- courses this is the source course of the lesson).
    course_id        UUID NOT NULL,
    lesson_id        UUID NOT NULL,
    first_viewed_at  TIMESTAMPTZ NOT NULL,

    CONSTRAINT uq_lesson_progress_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_lesson_progress_tenant_student_lesson UNIQUE (tenant_id, student_id, lesson_id),

    CONSTRAINT fk_lesson_progress_student FOREIGN KEY (tenant_id, student_id)
        REFERENCES tenant_user (tenant_id, id),
    CONSTRAINT fk_lesson_progress_course FOREIGN KEY (tenant_id, course_id)
        REFERENCES course (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT fk_lesson_progress_lesson FOREIGN KEY (tenant_id, lesson_id)
        REFERENCES course_lesson (tenant_id, id) ON DELETE CASCADE
);

-- viewedCount per course for one student.
CREATE INDEX idx_lesson_progress_tenant_student_course
    ON lesson_progress (tenant_id, student_id, course_id);

-- FK support for lesson delete cascade.
CREATE INDEX idx_lesson_progress_tenant_lesson
    ON lesson_progress (tenant_id, lesson_id);
