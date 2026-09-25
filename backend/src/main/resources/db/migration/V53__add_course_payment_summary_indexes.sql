-- Wave 6 §3/§4: two purely-additive, tenant-leading indexes supporting the
-- new course-payment-summary/outstanding-orders queries and the
-- PaymentOperationalState projection's per-order ledger aggregation - no
-- column/constraint change, no existing query's plan invalidated.

-- Supports GET /api/v1/ledger/courses/{courseId}/summary and
-- GET /api/v1/ledger/outstanding (both filter/group student_order rows by
-- tenant + course + status) without a sequential scan per tenant.
CREATE INDEX idx_student_order_tenant_course_status
    ON student_order (tenant_id, course_id, status);

-- Supports PaymentOperationalStateService#resolveForOrders' per-order
-- confirmed/refunded-amount aggregation, run per order (via
-- LedgerEntryRepository#findAllByOrderIdIn) rather than once per ledger row.
CREATE INDEX idx_ledger_entry_tenant_order_entry_type
    ON ledger_entry (tenant_id, order_id, entry_type);
