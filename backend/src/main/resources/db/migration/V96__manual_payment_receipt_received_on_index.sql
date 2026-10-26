-- payment-management (RPT-02 Student Payment Report, spec
-- docs/specs/student-payment-report.md section 3-R.4): the DATES report
-- looks up a tenant's manual receipts by `received_on` (the business date,
-- which may be backdated up to 365 days, so the ledger time cannot be used).
-- Tenant-leading index for that query shape. Index only - no data change;
-- V93 is not edited.
CREATE INDEX idx_manual_payment_receipt_tenant_received_on
    ON manual_payment_receipt (tenant_id, received_on);
