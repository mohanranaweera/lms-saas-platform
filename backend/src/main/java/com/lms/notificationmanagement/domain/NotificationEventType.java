package com.lms.notificationmanagement.domain;

/**
 * Mirrors {@code notification_outbox}'s {@code ck_notification_outbox_event_type}
 * CHECK constraint (V28) exactly. A small, MVP-fixed set - adding a new event
 * type is a new migration (V28's header comment), matching this codebase's
 * existing state-machine convention (see {@code PaymentStatus}).
 *
 * <p>The enum name doubles as the {@code notification_template.template_key}
 * value for each of these three MVP event types (see {@code
 * NotificationTemplateSeedingService}/{@code NotificationDispatchService}) -
 * keep this in sync with the seeded template keys if this enum ever changes.
 */
public enum NotificationEventType {

	PAYMENT_CONFIRMED, PAYMENT_REJECTED, PAYMENT_REFUNDED

}
