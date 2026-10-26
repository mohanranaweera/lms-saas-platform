package com.lms.paymentmanagement.payment.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.List;

public record RefundVerificationView(UUID paymentId, UUID orderId, long orderNumber, String displayOrderNumber,
		BigDecimal paymentAmount, String currency, String paymentStatus, String gatewayReference,
		Instant paymentCreatedAt, BigDecimal refundedAmount, BigDecimal refundableAmount, List<Line> lines) {
	public record Line(int lineNo, UUID courseId, String courseName, String label, String scope,
			BigDecimal unitAmount, BigDecimal facilityFee, BigDecimal discountAmount, BigDecimal lineTotal, String currency) { }
}
