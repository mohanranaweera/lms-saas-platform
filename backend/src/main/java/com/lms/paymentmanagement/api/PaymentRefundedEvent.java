package com.lms.paymentmanagement.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Published by {@code RefundService#processRefund} inside the same
 * transaction that writes the {@code payment_refund} row and the reversing
 * {@code ledger_entry} (plan §9/§16) - mirroring {@code
 * coursemanagement.api.CoursePriceChangedEvent}'s exact shape/javadoc style.
 * No consumer exists yet - {@code audit-log-management} is expected to be
 * the eventual listener; PAY-4's own acceptance criterion requires exactly
 * one audit row per refund, sourced from this event's {@code actorUserId}
 * (a real, authenticated Finance Staff/Institute Owner - unlike the webhook
 * path's {@code null} actor).
 */
public record PaymentRefundedEvent(UUID tenantId, UUID paymentId, UUID refundId, UUID actorUserId, BigDecimal amount,
		String reason, Instant refundedAt) {

}
