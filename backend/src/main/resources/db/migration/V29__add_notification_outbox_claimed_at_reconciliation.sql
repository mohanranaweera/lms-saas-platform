-- notification-management follow-up (post-ship multi-agent review finding):
-- closes the disclosed, bounded gap already documented in
-- docs/requirements/open-decisions.md - "a crash between the PENDING ->
-- SENDING transition committing and the terminal-status (SENT/FAILED)
-- commit leaves a notification_outbox row stuck at SENDING with no
-- automatic recovery". V28 (already shared/applied) is NOT edited - this is
-- a new, additive migration per root CLAUDE.md/.claude/rules/tenancy.md's
-- append-only migration-history rule, mirroring V23's precedent for a
-- purely additive follow-up migration against an already-shipped table.
--
-- Adds `claimed_at`: a dedicated timestamp for exactly when a row
-- transitioned PENDING -> SENDING, written by
-- NotificationDispatchClaimService#claim in the same short transaction as
-- the SENDING write itself. Deliberately distinct from:
--   - `created_at` - when the row was originally enqueued, which can
--     legitimately be much earlier than the claim if the outbox is ever
--     backlogged; reusing it as a claim-age proxy would risk a
--     reconciliation query marking a just-claimed row FAILED the instant it
--     is claimed, whenever the backlog age already exceeds the
--     reconciliation timeout.
--   - `dispatched_at` - only ever set at a TERMINAL SENT/FAILED transition,
--     per V28's own ck_notification_outbox_dispatched_together CHECK (not
--     modified here) - NULL for a row stuck at SENDING, which is exactly
--     the row this migration needs a reconciliation query to find.
--
-- This is what makes NotificationDispatchPoller's new reconciliation step
-- (finds SENDING rows whose claimed_at is older than a timeout constant and
-- marks them FAILED - a terminal, no-retry outcome, consistent with this
-- module's existing "FAILED is terminal, no retry" decision, not a retry of
-- the send itself) correct rather than a guess against unrelated timestamps.

ALTER TABLE notification_outbox
    ADD COLUMN claimed_at TIMESTAMPTZ;

-- Best-effort backfill for any pre-existing SENDING/SENT/FAILED row from
-- before this column existed (this table has already shipped, so
-- local/dev/test/staging environments may hold such rows) - dispatched_at
-- is already NOT NULL for every terminal (SENT/FAILED) row per V28's CHECK,
-- so it is the closest available approximation of when that row was
-- claimed; a legacy row stuck at SENDING has neither, so falls back to
-- created_at. Every row claimed AFTER this migration gets a precise,
-- application-set claimed_at instead - this backfill only matters once, for
-- rows that predate the column.
UPDATE notification_outbox
SET claimed_at = COALESCE(dispatched_at, created_at)
WHERE status IN ('SENDING', 'SENT', 'FAILED')
  AND claimed_at IS NULL;

-- Mirrors ck_notification_outbox_dispatched_together's (V28) exact shape:
-- claimed_at is NULL exactly while PENDING (not yet claimed) and NOT NULL
-- for every status reachable only via a claim (SENDING/SENT/FAILED) - once
-- claimed, always claimed, even after a later terminal transition.
ALTER TABLE notification_outbox
    ADD CONSTRAINT ck_notification_outbox_claimed_together CHECK (
        (status = 'PENDING' AND claimed_at IS NULL) OR
        (status IN ('SENDING', 'SENT', 'FAILED') AND claimed_at IS NOT NULL)
    );

-- Reconciliation step's own claim query: SELECT ... WHERE status = 'SENDING'
-- AND claimed_at < :cutoff ORDER BY claimed_at ... FOR UPDATE SKIP LOCKED -
-- the same deliberate, explicitly-named cross-tenant-bypass shape as
-- idx_notification_outbox_status_pending_created_at (V28), scoped to
-- exactly this query's shape (a partial index, WHERE status = 'SENDING')
-- so it stays small and self-pruning as this table grows with total
-- platform notification volume, not per-tenant volume (architecture.md's
-- scalability guidance) - a stuck row leaves SENDING as soon as
-- reconciliation (or, far more commonly, a normal dispatch) resolves it.
CREATE INDEX idx_notification_outbox_status_sending_claimed_at
    ON notification_outbox (status, claimed_at)
    WHERE status = 'SENDING';
