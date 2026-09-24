package com.lms.paymentmanagement.api;

import java.math.BigDecimal;
import java.util.UUID;

/** Result of {@link ManualEnrollmentApi#grantEnrollment(UUID, UUID, String)}. */
public record ManualEnrollmentResult(UUID orderId, UUID paymentId, BigDecimal amount, String currency) {

}
