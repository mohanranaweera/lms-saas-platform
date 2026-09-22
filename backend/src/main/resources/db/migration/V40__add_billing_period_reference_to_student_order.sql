-- payment-management (Wave 2 - Course/Class model + billing foundation):
-- adds a nullable, purely-traceability `billing_period_id` reference from
-- `student_order` (V19) to the new `course_billing_period` (V39) table.
--
-- Nullable because ONE_TIME/FREE orders (the only pricing models that
-- existed before V37) never reference a billing period - only orders placed
-- against a MONTHLY/SESSION/CUSTOM-priced course populate this column. This
-- migration does not change `student_order.amount`/`status` semantics in
-- any way: `amount` remains an immutable snapshot taken at order-creation
-- time (V19's own header comment), `billing_period_id` only records which
-- billing period that snapshot was taken against, for traceability/audit.
--
-- `fk_student_order_billing_period` is a composite FK on
-- `(tenant_id, billing_period_id)` REFERENCES `course_billing_period
-- (tenant_id, id)` - a cross-tenant reference is a schema constraint
-- violation, not just a service-layer bug, per .claude/rules/tenancy.md.
-- No `ON DELETE` behavior is declared (default NO ACTION), which is the
-- correct, safe choice here, not an oversight:
--   * `ON DELETE CASCADE` is explicitly forbidden by the task - deleting a
--     billing period must never delete the `student_order` row that
--     references it (append-only financial history, root CLAUDE.md
--     "never delete financial history" / .claude/rules/payments.md).
--   * `ON DELETE SET NULL` is not viable on this COMPOSITE FK for the same
--     reason V12/V39 both document: Postgres's `ON DELETE SET NULL` on a
--     composite FK nulls every column in the FK, including `tenant_id`,
--     which would violate this schema's absolute "tenant_id NOT NULL"
--     rule.
--   * Default `NO ACTION` (block the delete) is safe here - unlike
--     `course_billing_configuration`/`course` (V38/V14), nothing in this
--     schema ever deletes a `course_billing_period` row (V39 is append-
--     only, with no `ON DELETE CASCADE` pointing at it from anywhere), so
--     this FK's blocking behavior is not expected to ever be exercised; it
--     exists purely as a data-integrity guard against a future, currently
--     nonexistent deletion path being added carelessly.

ALTER TABLE student_order
    ADD COLUMN billing_period_id UUID;

ALTER TABLE student_order
    ADD CONSTRAINT fk_student_order_billing_period
        FOREIGN KEY (tenant_id, billing_period_id)
        REFERENCES course_billing_period (tenant_id, id);

-- "Orders for one billing period" read/reconciliation pattern (e.g.
-- confirming every order billed against a given period).
CREATE INDEX idx_student_order_tenant_billing_period
    ON student_order (tenant_id, billing_period_id);
