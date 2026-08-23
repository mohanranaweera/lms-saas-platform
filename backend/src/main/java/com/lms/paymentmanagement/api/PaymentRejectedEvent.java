package com.lms.paymentmanagement.api;

import com.lms.paymentmanagement.payment.domain.PaymentStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * Published by {@code PaymentConfirmationService} inside the same
 * transaction that writes {@code payment.status = REJECTED} - the
 * counterpart to {@link PaymentConfirmedEvent} for the gateway's failure
 * outcome. See that record's javadoc for the shared rationale (mandatory
 * audit action, no consumer yet, no human actor for this webhook-driven
 * transition).
 */
public record PaymentRejectedEvent(UUID tenantId, UUID paymentId, UUID orderId, PaymentStatus previousStatus,
		PaymentStatus newStatus, Instant rejectedAt) {

}
