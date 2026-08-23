package com.lms.paymentmanagement.order.web.dto;

import com.lms.paymentmanagement.payment.domain.PaymentStatus;
import java.time.Instant;
import java.util.UUID;

public record OrderPaymentStatusResponse(boolean hasPaymentAttempt, UUID paymentId, PaymentStatus status,
		Instant confirmedAt) {

}
