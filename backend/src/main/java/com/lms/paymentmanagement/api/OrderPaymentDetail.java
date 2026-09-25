package com.lms.paymentmanagement.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Wave 6 (§3.2/§4) batched, per-order payment read model - the narrow
 * cross-module contract {@code ledger-settlement-management}'s {@code
 * PaymentOperationalState} projection and extended ledger views depend on,
 * added to {@link PaymentStatusApi} rather than exposing {@code
 * StudentOrderRepository}/{@code PaymentRepository} directly (per {@code
 * .claude/rules/architecture.md}'s "module may depend only on another
 * module's api package" rule).
 *
 * <p>{@code hasPendingPayment}/{@code hasRejectedPayment} summarize EVERY
 * payment attempt for the order (there can be more than one, e.g. a
 * rejected gateway attempt followed by a retry) - never just the latest
 * one - so the caller's {@code PaymentOperationalState} precedence logic
 * (open attempt beats a prior terminal one) has what it needs without a
 * second round trip. {@code confirmedPaymentId}/{@code
 * confirmedGatewayReference} are non-null only when a CONFIRMED payment
 * exists for this order - PAY-2/PAY-3's own invariant is at most one
 * CONFIRMED payment per order, so this is never ambiguous.
 */
public record OrderPaymentDetail(UUID orderId, UUID studentId, UUID courseId, UUID billingPeriodId,
		BigDecimal orderAmount, String orderCurrency, boolean hasPendingPayment, boolean hasRejectedPayment,
		UUID confirmedPaymentId, String confirmedGatewayReference) {

}
