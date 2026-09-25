package com.lms.ledgersettlementmanagement.web.dto;

import com.lms.ledgersettlementmanagement.api.PaymentOperationalState;
import com.lms.ledgersettlementmanagement.service.OutstandingOrderView;
import java.math.BigDecimal;
import java.util.UUID;

/** Wave 6 §4 - {@code GET /api/v1/ledger/outstanding} row. */
public record OutstandingOrderResponse(UUID orderId, UUID studentId, UUID courseId, String courseTitle,
		BigDecimal amount, String currency, PaymentOperationalState operationalState) {

	public static OutstandingOrderResponse from(OutstandingOrderView view) {
		return new OutstandingOrderResponse(view.orderId(), view.studentId(), view.courseId(), view.courseTitle(),
				view.amount(), view.currency(), view.operationalState());
	}

}
