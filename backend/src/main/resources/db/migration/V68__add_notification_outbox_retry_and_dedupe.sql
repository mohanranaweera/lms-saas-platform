-- notification-management (Wave 11, docs/parity/waves/wave-11-plan.md §2.7,
-- decision D3): bounded retry and duplicate-event protection for the
-- transactional email outbox. V28/V29 are NOT edited - this is a new,
-- additive migration.
--
-- Retry: a transient send failure (SMTP unavailable) now returns the row to
-- PENDING with next_attempt_at in the future instead of FAILED, up to 3
-- attempts in total; the third transient failure, or any permanent failure,
-- is FAILED as before. Returning to PENDING clears claimed_at, which keeps
-- V29's ck_notification_outbox_claimed_together valid unchanged. This
-- supersedes docs/requirements/open-decisions.md §22's "no retry" item by
-- explicit product-owner decision (2026-10-01).
--
-- Dedupe: dedupe_key identifies the business event a row was created for
-- (e.g. PAYMENT_CONFIRMED:{paymentId}); a second row for the same event in
-- the same tenant is rejected by the partial unique index, so a re-delivered
-- event never sends a second email. Rows without a natural key leave it NULL.

ALTER TABLE notification_outbox
    ADD COLUMN attempt_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at TIMESTAMPTZ,
    ADD COLUMN dedupe_key VARCHAR(120);

-- Every row that has already been claimed was attempted exactly once (there
-- was no retry before this migration).
UPDATE notification_outbox
SET attempt_count = 1
WHERE status IN ('SENDING', 'SENT', 'FAILED');

ALTER TABLE notification_outbox
    ADD CONSTRAINT ck_notification_outbox_attempt_count CHECK (attempt_count >= 0 AND attempt_count <= 3),
    ADD CONSTRAINT ck_notification_outbox_next_attempt_pending_only
        CHECK (next_attempt_at IS NULL OR status = 'PENDING');

CREATE UNIQUE INDEX uq_notification_outbox_tenant_dedupe_key
    ON notification_outbox (tenant_id, dedupe_key)
    WHERE dedupe_key IS NOT NULL;

-- The poller now selects PENDING rows that are due (next_attempt_at NULL or
-- in the past). Replace V28's partial index with one matching that shape.
DROP INDEX idx_notification_outbox_status_pending_created_at;

CREATE INDEX idx_notification_outbox_pending_due
    ON notification_outbox (next_attempt_at, created_at)
    WHERE status = 'PENDING';
