package com.lms.paymentmanagement.slip.web.dto;

import com.lms.paymentmanagement.slip.domain.PaymentSlipStatus;
import com.lms.paymentmanagement.slip.service.PaymentSlipDelivery;
import com.lms.paymentmanagement.slip.service.PaymentSlipOrderLine;
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
 *
 * <p>V90: each {@code orderLines[]} entry carries {@code courseId}/{@code
 * courseTitle}; {@code orderPostage} (order postage total, {@code null} for
 * legacy/pre-V90 orders) and {@code orderDelivery} (delivery snapshot or
 * {@code null}) let the reviewer fulfil postage.
 */
public record PaymentSlipResponse(UUID id, UUID orderId, UUID studentId, String referenceNumber,
		PaymentSlipStatus status, Instant submittedAt, UUID reviewerId, Instant reviewedAt,
		List<PaymentSlipFlagResponse> flags, String studentEmail, String reviewerEmail, BigDecimal orderAmount,
		String orderCurrency, List<PaymentSlipOrderLine> orderLines, BigDecimal orderSubtotal,
		BigDecimal orderFacilityFee, BigDecimal orderDiscount, BigDecimal orderPostage,
		PaymentSlipDelivery orderDelivery, UUID studentProfileId, String studentName, String studentMobile,
		Long orderNumber, String displayOrderNumber) {

	/** Pre-PAY-HUB-01 shape (no student profile/name/mobile, no order number). */
	public PaymentSlipResponse(UUID id, UUID orderId, UUID studentId, String referenceNumber, PaymentSlipStatus status,
			Instant submittedAt, UUID reviewerId, Instant reviewedAt, List<PaymentSlipFlagResponse> flags,
			String studentEmail, String reviewerEmail, BigDecimal orderAmount, String orderCurrency,
			List<PaymentSlipOrderLine> orderLines, BigDecimal orderSubtotal, BigDecimal orderFacilityFee,
			BigDecimal orderDiscount, BigDecimal orderPostage, PaymentSlipDelivery orderDelivery) {
		this(id, orderId, studentId, referenceNumber, status, submittedAt, reviewerId, reviewedAt, flags, studentEmail,
				reviewerEmail, orderAmount, orderCurrency, orderLines, orderSubtotal, orderFacilityFee, orderDiscount,
				orderPostage, orderDelivery, null, null, null, null, null);
	}

	/** Pre-V90 shape (no postage / delivery snapshot). */
	public PaymentSlipResponse(UUID id, UUID orderId, UUID studentId, String referenceNumber,
			PaymentSlipStatus status, Instant submittedAt, UUID reviewerId, Instant reviewedAt,
			List<PaymentSlipFlagResponse> flags, String studentEmail, String reviewerEmail, BigDecimal orderAmount,
			String orderCurrency, List<PaymentSlipOrderLine> orderLines, BigDecimal orderSubtotal,
			BigDecimal orderFacilityFee, BigDecimal orderDiscount) {
		this(id, orderId, studentId, referenceNumber, status, submittedAt, reviewerId, reviewedAt, flags, studentEmail,
				reviewerEmail, orderAmount, orderCurrency, orderLines, orderSubtotal, orderFacilityFee, orderDiscount,
				null, null);
	}

	/** Pre-KLASS-T4c shape (no order lines / fee snapshot). */
	public PaymentSlipResponse(UUID id, UUID orderId, UUID studentId, String referenceNumber,
			PaymentSlipStatus status, Instant submittedAt, UUID reviewerId, Instant reviewedAt,
			List<PaymentSlipFlagResponse> flags, String studentEmail, String reviewerEmail, BigDecimal orderAmount,
			String orderCurrency) {
		this(id, orderId, studentId, referenceNumber, status, submittedAt, reviewerId, reviewedAt, flags, studentEmail,
				reviewerEmail, orderAmount, orderCurrency, List.of(), null, null, null, null, null);
	}

}
