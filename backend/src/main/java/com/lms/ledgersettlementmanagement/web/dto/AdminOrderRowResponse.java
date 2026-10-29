package com.lms.ledgersettlementmanagement.web.dto;

import com.lms.ledgersettlementmanagement.api.PaymentMethod;
import com.lms.ledgersettlementmanagement.api.PaymentOperationalState;
import com.lms.ledgersettlementmanagement.service.AdminOrderRowView;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** PAY-HUB-01: one row of {@code GET /api/v1/ledger/orders} - see {@link AdminOrderRowView}. */
public record AdminOrderRowResponse(UUID orderId, long orderNumber, String displayOrderNumber, Instant createdAt,
		UUID studentId, String studentName, int itemCount, String firstItemTitle, BigDecimal amount,
		String currency, PaymentOperationalState operationalState, PaymentMethod method, String reference) {

	public static AdminOrderRowResponse from(AdminOrderRowView view) {
		return new AdminOrderRowResponse(view.orderId(), view.orderNumber(), view.displayOrderNumber(),
				view.createdAt(), view.studentId(), view.studentName(), view.itemCount(), view.firstItemTitle(),
				view.amount(), view.currency(), view.operationalState(), view.method(), view.reference());
	}

}
