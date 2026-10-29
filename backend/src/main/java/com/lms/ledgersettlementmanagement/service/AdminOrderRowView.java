package com.lms.ledgersettlementmanagement.service;

import com.lms.ledgersettlementmanagement.api.PaymentMethod;
import com.lms.ledgersettlementmanagement.api.PaymentOperationalState;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * PAY-HUB-01: one row of {@code GET /api/v1/ledger/orders}. {@code studentId}
 * is the student PROFILE id (for linking to the student page), {@code null}
 * with {@code studentName} if the profile no longer resolves. See plan §6.1
 * for how {@code method}/{@code reference} are chosen.
 */
public record AdminOrderRowView(UUID orderId, long orderNumber, String displayOrderNumber, Instant createdAt,
		UUID studentId, String studentName, int itemCount, String firstItemTitle, BigDecimal amount,
		String currency, PaymentOperationalState operationalState, PaymentMethod method, String reference) {

}
