package com.lms.ledgersettlementmanagement.service;

import com.lms.ledgersettlementmanagement.api.PaymentOperationalState;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Wave 6 §4 - one row of {@code GET /api/v1/ledger/outstanding}'s result:
 * an order whose {@link PaymentOperationalState} is NOT {@code PAID} or
 * {@code REFUNDED} (i.e. still genuinely owing, per plan §4's explicit
 * "UNPAID/PENDING/UNDER_REVIEW/REJECTED" list).
 */
public record OutstandingOrderView(UUID orderId, UUID studentId, UUID courseId, String courseTitle,
		BigDecimal amount, String currency, PaymentOperationalState operationalState) {

}
