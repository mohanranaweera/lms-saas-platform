package com.lms.paymentmanagement.api;

import java.util.UUID;

/**
 * Wave 6 (§3.2/§4) batched, per-order manual-slip read model - mirrors
 * {@link OrderPaymentDetail}'s exact shape/rationale for the sibling {@link
 * SlipStatusApi} contract. {@code hasOpenSlip} covers BOTH {@code SUBMITTED}
 * and {@code UNDER_REVIEW} (both render as {@code UNDER_REVIEW} on the
 * {@code PaymentOperationalState} projection - see that enum's javadoc).
 * {@code approvedReferenceNumber} is the slip's own bank/reference number
 * (non-null only when an {@code APPROVED} slip exists for this order) - used
 * as the {@code reference} field for the {@code MANUAL_SLIP} payment method
 * on the extended ledger views, in place of the synthesized {@code "SLIP-"
 * + paymentId} gateway reference a student/admin would not recognize.
 */
public record OrderSlipDetail(UUID orderId, boolean hasOpenSlip, boolean hasRejectedSlip,
		String approvedReferenceNumber) {

}
