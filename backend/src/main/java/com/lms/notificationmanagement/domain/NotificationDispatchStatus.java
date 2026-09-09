package com.lms.notificationmanagement.domain;

/**
 * Mirrors {@code notification_outbox}'s {@code ck_notification_outbox_status}
 * CHECK constraint (V28) exactly. {@code PENDING -> SENDING -> SENT|FAILED}
 * is the only legal path: {@code SENDING} is the claimed-but-not-yet-terminal
 * marker written by {@code NotificationDispatchClaimService#claim(java.util.UUID)}
 * in its own short transaction, so the row's {@code FOR UPDATE SKIP LOCKED}
 * claim lock is released before the outbound SMTP call is ever made (fix
 * required by {@code .claude/rules/backend.md}'s "do not span a transaction
 * across an outbound call" rule). {@link
 * NotificationOutbox#markSending(java.time.Instant)}/{@link
 * NotificationOutbox#markSent(java.time.Instant)}/{@link
 * NotificationOutbox#markFailed(java.time.Instant)} are the only three
 * application-side paths that may advance this column, and a {@code FAILED}
 * row is never re-claimed (no retry, per the plan's explicit "do not silently
 * implement a retry mechanism" decision).
 */
public enum NotificationDispatchStatus {

	PENDING, SENDING, SENT, FAILED

}
