package com.lms.paymentmanagement.order.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record OrderLineResponse(int lineNo, String scope, UUID courseId, String courseTitle, UUID moduleId,
		UUID lessonId, Integer periodYear, Integer periodMonth, UUID billingPeriodId, String label,
		BigDecimal unitAmount, BigDecimal facilityFeeAmount, BigDecimal discountAmount, BigDecimal lineTotal,
		String currency, Instant createdAt) {
}
