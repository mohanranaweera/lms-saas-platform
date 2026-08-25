package com.lms.paymentmanagement.slip.web.dto;

import com.lms.paymentmanagement.slip.domain.PaymentSlipStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Web-layer response shape for a payment slip - never the {@code PaymentSlip}
 * JPA entity. {@code studentEmail}/{@code reviewerEmail}/{@code orderAmount}/
 * {@code orderCurrency} let a reviewer identify who a slip belongs to and
 * cross-check the expected amount without a separate lookup - see
 * {@code PaymentSlipView}'s javadoc for nullability details. In particular,
 * {@code studentEmail} is expected to be non-null in practice (the student
 * id is FK-backed) but is not schema-guaranteed by its lookup path - treat it
 * as nullable.
 */
public record PaymentSlipResponse(UUID id, UUID orderId, UUID studentId, String referenceNumber,
		PaymentSlipStatus status, Instant submittedAt, UUID reviewerId, Instant reviewedAt,
		List<PaymentSlipFlagResponse> flags, String studentEmail, String reviewerEmail, BigDecimal orderAmount,
		String orderCurrency) {

}
