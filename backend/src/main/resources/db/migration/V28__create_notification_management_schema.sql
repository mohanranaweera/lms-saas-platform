-- notification-management (Module 14 / MVP-018, GitHub issue #18):
-- `notification_outbox`, `notification_template`, `in_app_notification`.
--
-- Owned by com.lms.notificationmanagement.domain, per
-- .claude/rules/architecture.md's confirmed domain list. Every table is
-- tenant-owned: `tenant_id NOT NULL REFERENCES tenant(id)`, a composite index
-- leading with `tenant_id`, and every cross-table reference to another
-- tenant-owned table is a composite `(tenant_id, ...)` FK - never a bare FK
-- on the child id alone. `id` has no DB-side DEFAULT anywhere - generated
-- application-side (UUIDv7), per V1's baseline convention.
--
-- `notification_outbox` is deliberately NOT modeled append-only, unlike
-- backend.md's named append-only domains (payment-management,
-- ledger-settlement-management, audit-log-management): it is dispatch
-- metadata for an async side effect, not the financial/audit record of
-- truth. `PENDING -> SENDING -> SENT|FAILED` is the only allowed path,
-- schema-enforced by ck_notification_outbox_dispatched_together below (a
-- pending/claimed row must not carry dispatched_at; a terminal row must).
-- SENDING is the claimed-but-not-yet-terminal marker: the dispatch service
-- commits the PENDING->SENDING transition in its own short transaction
-- BEFORE making the outbound SMTP call, so no transaction/row-lock is ever
-- held across that external call (backend.md's "do not span a transaction
-- across an outbound call" rule) - see NotificationDispatchClaimService/
-- NotificationDispatchFinalizeService. There is no second transition after
-- SENT/FAILED and no delete path - a FAILED row is terminal (no retry, per
-- the plan's explicit "do not silently implement a retry mechanism"
-- decision), but that terminality is a service-layer/no-repository-method
-- discipline, not an append-only row-versioning scheme, because there is
-- nothing here that needs to remain reconstructible across corrections the
-- way a ledger entry does.

CREATE TABLE notification_outbox (
    id                 UUID PRIMARY KEY,
    tenant_id          UUID NOT NULL REFERENCES tenant (id),
    event_type         VARCHAR(30) NOT NULL,
    -- Promoted to a real, NOT NULL, FK-backed structural column rather than
    -- left inside `payload` JSONB: every event type this MVP consumes
    -- (PAYMENT_CONFIRMED/REJECTED/REFUNDED) has exactly one recipient, so
    -- "who this notification is for" is structural data the dispatch
    -- poller and the Notification Center read path both need to filter/join
    -- on directly - only genuinely event-specific rendering variables
    -- (amount, currency, paymentId, etc.) belong in payload.
    recipient_user_id  UUID NOT NULL,
    payload            JSONB NOT NULL,
    status             VARCHAR(10) NOT NULL DEFAULT 'PENDING',
    created_at         TIMESTAMPTZ NOT NULL,
    dispatched_at      TIMESTAMPTZ,

    CONSTRAINT fk_notification_outbox_recipient FOREIGN KEY (tenant_id, recipient_user_id)
        REFERENCES tenant_user (tenant_id, id),

    CONSTRAINT ck_notification_outbox_event_type
        CHECK (event_type IN ('PAYMENT_CONFIRMED', 'PAYMENT_REJECTED', 'PAYMENT_REFUNDED')),
    CONSTRAINT ck_notification_outbox_status CHECK (status IN ('PENDING', 'SENDING', 'SENT', 'FAILED')),
    -- Mirrors exam_answer's marked-fields-together precedent (V26): a
    -- terminal (SENT/FAILED) row must carry its timestamp; a
    -- pending-or-claimed (PENDING/SENDING) row must not.
    CONSTRAINT ck_notification_outbox_dispatched_together CHECK (
        (status IN ('PENDING', 'SENDING') AND dispatched_at IS NULL) OR
        (status IN ('SENT', 'FAILED') AND dispatched_at IS NOT NULL)
    )
);

-- Per-tenant reporting/debugging index (e.g. "show tenant X's recent outbox
-- rows by status") - tenant-leading per this codebase's standing convention.
-- Deliberately NOT the index that serves the dispatch poller's own claim
-- query (see idx_notification_outbox_status_pending_created_at below) -
-- that query is a cross-tenant batch scan and gets no benefit from a
-- tenant-leading composite index.
CREATE INDEX idx_notification_outbox_tenant_status_created_at
    ON notification_outbox (tenant_id, status, created_at);

-- Dispatch-poller claim query: SELECT ... WHERE status = 'PENDING' ORDER BY
-- created_at FOR UPDATE SKIP LOCKED - a deliberate, explicitly-named
-- cross-tenant bypass query at the application layer (the poller claims
-- across all tenants in one batch, per plan §9.2/§14) that does NOT filter
-- by tenant_id, so idx_notification_outbox_tenant_status_created_at above
-- cannot serve it efficiently. A partial index scoped to exactly this
-- query's shape - status/created_at, WHERE status = 'PENDING' - keeps the
-- index itself small and self-pruning (rows leave PENDING within one
-- dispatch attempt) as this table grows with total platform notification
-- volume, not per-tenant volume (architecture.md's scalability guidance).
CREATE INDEX idx_notification_outbox_status_pending_created_at
    ON notification_outbox (status, created_at)
    WHERE status = 'PENDING';

CREATE TABLE notification_template (
    id            UUID PRIMARY KEY,
    tenant_id     UUID NOT NULL REFERENCES tenant (id),
    -- Free-text VARCHAR, deliberately not a CHECK-constrained enum: a future
    -- template-authoring/seeding migration can insert new keys without a
    -- schema change. This does not resolve the template-origination gap
    -- (plan §21 item 1 - no MVP role/UI can create these rows yet); it just
    -- avoids baking a premature constraint around a key set that isn't
    -- fixed yet.
    template_key  VARCHAR(60) NOT NULL,
    subject       TEXT NOT NULL,
    body          TEXT NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL,
    created_by    UUID,
    updated_by    UUID,

    CONSTRAINT uq_notification_template_tenant_key UNIQUE (tenant_id, template_key),
    CONSTRAINT ck_notification_template_subject_not_blank CHECK (btrim(subject) <> ''),
    CONSTRAINT ck_notification_template_body_not_blank CHECK (btrim(body) <> '')
);

CREATE TABLE in_app_notification (
    id                 UUID PRIMARY KEY,
    tenant_id          UUID NOT NULL REFERENCES tenant (id),
    recipient_user_id  UUID NOT NULL,
    title              TEXT NOT NULL,
    body               TEXT NOT NULL,
    read_at            TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_in_app_notification_recipient FOREIGN KEY (tenant_id, recipient_user_id)
        REFERENCES tenant_user (tenant_id, id),

    CONSTRAINT ck_in_app_notification_title_not_blank CHECK (btrim(title) <> ''),
    CONSTRAINT ck_in_app_notification_body_not_blank CHECK (btrim(body) <> '')
);

-- Notification Center's own read pattern: newest-first, own recipient only
-- (a same-tenant BOLA-sensitive filter enforced again at the repository/
-- service layer on top of this index - see plan §9.5/§14).
CREATE INDEX idx_in_app_notification_tenant_recipient_created_at
    ON in_app_notification (tenant_id, recipient_user_id, created_at DESC);
