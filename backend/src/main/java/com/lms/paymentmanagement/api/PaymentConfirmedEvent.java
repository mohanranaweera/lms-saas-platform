package com.lms.paymentmanagement.api;

import com.lms.paymentmanagement.payment.domain.PaymentStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Published by {@code PaymentConfirmationService} inside the same
 * transaction that writes {@code payment.status = CONFIRMED}, the ledger
 * entry, and the enrollment activation (plan §9/§16), mirroring {@code
 * coursemanagement.api.CoursePriceChangedEvent}'s exact shape/javadoc style.
 * No consumer exists yet - {@code audit-log-management} is expected to be
 * the eventual listener, per {@code .claude/rules/security.md}'s "payment
 * approvals/rejections" canonical mandatory-audit-action. {@code actorId} is
 * {@code null} here (unlike a human-reviewer-driven action) - the webhook
 * path's "actor" is the verified integration/system identity, not a human,
 * so the trail must not imply human judgment where there was none.
 */
public record PaymentConfirmedEvent(UUID tenantId, UUID paymentId, UUID orderId, PaymentStatus previousStatus,
		PaymentStatus newStatus, Instant confirmedAt) {

}
