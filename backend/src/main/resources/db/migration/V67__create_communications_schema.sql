-- notification-management (Wave 11, docs/parity/waves/wave-11-plan.md §2.4-§2.6):
-- broadcast templates, broadcasts, per-recipient-per-channel deliveries, and
-- recipient channel preferences (opt-in / opt-out).
--
-- Purely additive: four new tables. V1-V66 are untouched. Every table is
-- tenant-owned (tenant_id NOT NULL FK, tenant-leading index), and every
-- reference to another tenant-owned row is a composite (tenant_id, ...) FK.

-- ---------------------------------------------------------------------------
-- communication_template
-- ---------------------------------------------------------------------------
-- Reusable broadcast copy authored by staff. Archived, never deleted
-- (broadcast.template_id references it). whatsapp_status is the PAR-22-02
-- pre-approval state machine: NOT_SUBMITTED -> SUBMITTED -> APPROVED |
-- REJECTED, REJECTED -> SUBMITTED; an edit to the copy resets it to
-- NOT_SUBMITTED (service-enforced; the CHECKs below keep each state's
-- required fields consistent).
CREATE TABLE communication_template (
    id                          UUID PRIMARY KEY,
    tenant_id                   UUID NOT NULL REFERENCES tenant (id),
    name                        VARCHAR(120) NOT NULL,
    subject                     VARCHAR(200) NOT NULL,
    body                        TEXT NOT NULL,
    sms_body                    VARCHAR(640),
    status                      VARCHAR(10) NOT NULL,
    whatsapp_status             VARCHAR(15) NOT NULL,
    whatsapp_template_name      VARCHAR(100),
    whatsapp_status_reason      VARCHAR(500),
    whatsapp_status_changed_at  TIMESTAMPTZ,
    whatsapp_status_changed_by  UUID,
    created_at                  TIMESTAMPTZ NOT NULL,
    updated_at                  TIMESTAMPTZ NOT NULL,
    created_by                  UUID,
    updated_by                  UUID,

    CONSTRAINT uq_communication_template_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT fk_communication_template_whatsapp_changed_by
        FOREIGN KEY (tenant_id, whatsapp_status_changed_by) REFERENCES tenant_user (tenant_id, id),
    CONSTRAINT ck_communication_template_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_communication_template_whatsapp_status
        CHECK (whatsapp_status IN ('NOT_SUBMITTED', 'SUBMITTED', 'APPROVED', 'REJECTED')),
    CONSTRAINT ck_communication_template_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT ck_communication_template_subject_not_blank CHECK (btrim(subject) <> ''),
    CONSTRAINT ck_communication_template_body_not_blank CHECK (btrim(body) <> ''),
    CONSTRAINT ck_communication_template_body_length CHECK (char_length(body) <= 5000),
    CONSTRAINT ck_communication_template_whatsapp_name CHECK (
        whatsapp_status IN ('NOT_SUBMITTED') OR whatsapp_template_name IS NOT NULL
    )
);

-- Template names are unique per tenant among ACTIVE templates (case-insensitive).
CREATE UNIQUE INDEX uq_communication_template_active_name
    ON communication_template (tenant_id, lower(name))
    WHERE status = 'ACTIVE';

CREATE INDEX idx_communication_template_tenant_status_updated
    ON communication_template (tenant_id, status, updated_at DESC);

-- ---------------------------------------------------------------------------
-- broadcast
-- ---------------------------------------------------------------------------
-- One row per sent broadcast: the message copy and the audience definition
-- as they were at send time. Append-only (trigger below) - a broadcast is a
-- historical record, never edited or deleted. client_request_id makes a
-- repeated submit (double click, network retry) return the original
-- broadcast instead of sending twice.
CREATE TABLE broadcast (
    id                       UUID PRIMARY KEY,
    tenant_id                UUID NOT NULL REFERENCES tenant (id),
    client_request_id        UUID NOT NULL,
    sender_user_id           UUID NOT NULL,
    sender_role              VARCHAR(30) NOT NULL,
    template_id              UUID,
    subject                  VARCHAR(200) NOT NULL,
    body                     TEXT NOT NULL,
    sms_body                 VARCHAR(640),
    channel_in_app           BOOLEAN NOT NULL,
    channel_email            BOOLEAN NOT NULL,
    channel_sms              BOOLEAN NOT NULL,
    channel_whatsapp         BOOLEAN NOT NULL,
    audience_type            VARCHAR(20) NOT NULL,
    audience_course_id       UUID,
    audience_payment_status  VARCHAR(10),
    recipient_count          INTEGER NOT NULL,
    created_at               TIMESTAMPTZ NOT NULL,

    CONSTRAINT uq_broadcast_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uq_broadcast_tenant_client_request UNIQUE (tenant_id, client_request_id),
    CONSTRAINT fk_broadcast_sender FOREIGN KEY (tenant_id, sender_user_id) REFERENCES tenant_user (tenant_id, id),
    CONSTRAINT fk_broadcast_template
        FOREIGN KEY (tenant_id, template_id) REFERENCES communication_template (tenant_id, id),
    CONSTRAINT fk_broadcast_course FOREIGN KEY (tenant_id, audience_course_id) REFERENCES course (tenant_id, id),
    CONSTRAINT ck_broadcast_audience_type
        CHECK (audience_type IN ('ALL_STUDENTS', 'COURSE', 'SELECTED_STUDENTS', 'ALL_TEACHERS', 'PAYMENT_STATUS')),
    CONSTRAINT ck_broadcast_payment_status
        CHECK (audience_payment_status IS NULL OR audience_payment_status IN ('PAID', 'PENDING', 'REJECTED')),
    CONSTRAINT ck_broadcast_audience_shape CHECK (
        (audience_type = 'COURSE' AND audience_course_id IS NOT NULL AND audience_payment_status IS NULL)
        OR (audience_type = 'PAYMENT_STATUS' AND audience_payment_status IS NOT NULL)
        OR (audience_type IN ('ALL_STUDENTS', 'SELECTED_STUDENTS', 'ALL_TEACHERS')
            AND audience_course_id IS NULL AND audience_payment_status IS NULL)
    ),
    CONSTRAINT ck_broadcast_some_channel
        CHECK (channel_in_app OR channel_email OR channel_sms OR channel_whatsapp),
    CONSTRAINT ck_broadcast_subject_not_blank CHECK (btrim(subject) <> ''),
    CONSTRAINT ck_broadcast_body_not_blank CHECK (btrim(body) <> ''),
    CONSTRAINT ck_broadcast_body_length CHECK (char_length(body) <= 5000),
    CONSTRAINT ck_broadcast_recipient_count CHECK (recipient_count >= 0 AND recipient_count <= 5000)
);

CREATE INDEX idx_broadcast_tenant_created ON broadcast (tenant_id, created_at DESC);
CREATE INDEX idx_broadcast_tenant_sender_created ON broadcast (tenant_id, sender_user_id, created_at DESC);

CREATE FUNCTION forbid_broadcast_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'broadcast is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_broadcast_append_only
    BEFORE UPDATE OR DELETE ON broadcast
    FOR EACH ROW EXECUTE FUNCTION forbid_broadcast_mutation();

-- ---------------------------------------------------------------------------
-- notification_delivery
-- ---------------------------------------------------------------------------
-- One row per (broadcast, recipient, channel) - the unique constraint is the
-- duplicate-send guard. Dispatch metadata, not a financial record: status
-- moves PENDING -> SENDING -> SENT | FAILED, or back to PENDING for a bounded
-- retry (attempt_count / next_attempt_at, decision D3); SKIPPED is terminal
-- (no address, no consent, channel unavailable). No delete path.
--
-- The recipient's address is resolved at send time and never copied here -
-- destination_masked is the only form stored, for delivery logs.
-- last_error_code is a safe, fixed code - never provider response text.
CREATE TABLE notification_delivery (
    id                    UUID PRIMARY KEY,
    tenant_id             UUID NOT NULL REFERENCES tenant (id),
    broadcast_id          UUID NOT NULL,
    recipient_user_id     UUID NOT NULL,
    channel               VARCHAR(10) NOT NULL,
    status                VARCHAR(10) NOT NULL,
    attempt_count         INTEGER NOT NULL DEFAULT 0,
    next_attempt_at       TIMESTAMPTZ,
    claimed_at            TIMESTAMPTZ,
    completed_at          TIMESTAMPTZ,
    destination_masked    VARCHAR(80),
    provider_account_id   UUID,
    provider_message_id   VARCHAR(100),
    last_error_code       VARCHAR(40),
    created_at            TIMESTAMPTZ NOT NULL,
    updated_at            TIMESTAMPTZ NOT NULL,

    CONSTRAINT uq_notification_delivery_once UNIQUE (tenant_id, broadcast_id, recipient_user_id, channel),
    CONSTRAINT fk_notification_delivery_broadcast
        FOREIGN KEY (tenant_id, broadcast_id) REFERENCES broadcast (tenant_id, id),
    CONSTRAINT fk_notification_delivery_recipient
        FOREIGN KEY (tenant_id, recipient_user_id) REFERENCES tenant_user (tenant_id, id),
    CONSTRAINT fk_notification_delivery_account
        FOREIGN KEY (tenant_id, provider_account_id) REFERENCES tenant_messaging_account (tenant_id, id),
    CONSTRAINT ck_notification_delivery_channel CHECK (channel IN ('IN_APP', 'EMAIL', 'SMS', 'WHATSAPP')),
    CONSTRAINT ck_notification_delivery_status
        CHECK (status IN ('PENDING', 'SENDING', 'SENT', 'FAILED', 'SKIPPED')),
    CONSTRAINT ck_notification_delivery_attempts CHECK (attempt_count >= 0 AND attempt_count <= 3),
    CONSTRAINT ck_notification_delivery_lifecycle CHECK (
        (status = 'PENDING' AND claimed_at IS NULL AND completed_at IS NULL)
        OR (status = 'SENDING' AND claimed_at IS NOT NULL AND completed_at IS NULL)
        OR (status IN ('SENT', 'FAILED', 'SKIPPED') AND completed_at IS NOT NULL AND next_attempt_at IS NULL)
    )
);

-- Delivery log for one broadcast.
CREATE INDEX idx_notification_delivery_tenant_broadcast_status
    ON notification_delivery (tenant_id, broadcast_id, status);

-- Per-recipient notification history (student detail tab).
CREATE INDEX idx_notification_delivery_tenant_recipient_created
    ON notification_delivery (tenant_id, recipient_user_id, created_at DESC);

-- Dispatch poller claim (explicit cross-tenant query, like the outbox's).
CREATE INDEX idx_notification_delivery_pending_due
    ON notification_delivery (next_attempt_at, created_at)
    WHERE status = 'PENDING';

-- Stuck-SENDING reconciliation.
CREATE INDEX idx_notification_delivery_sending_claimed
    ON notification_delivery (claimed_at)
    WHERE status = 'SENDING';

-- ---------------------------------------------------------------------------
-- notification_channel_preference
-- ---------------------------------------------------------------------------
-- A recipient's own consent per channel for broadcasts (PAR-22-03).
-- No row = channel default: EMAIL and SMS opted in, WHATSAPP opted out
-- (WhatsApp needs explicit opt-in). Transactional messages ignore these.
CREATE TABLE notification_channel_preference (
    id          UUID PRIMARY KEY,
    tenant_id   UUID NOT NULL REFERENCES tenant (id),
    user_id     UUID NOT NULL,
    channel     VARCHAR(10) NOT NULL,
    opted_in    BOOLEAN NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL,

    CONSTRAINT uq_notification_channel_preference UNIQUE (tenant_id, user_id, channel),
    CONSTRAINT fk_notification_channel_preference_user
        FOREIGN KEY (tenant_id, user_id) REFERENCES tenant_user (tenant_id, id),
    CONSTRAINT ck_notification_channel_preference_channel CHECK (channel IN ('EMAIL', 'SMS', 'WHATSAPP'))
);
