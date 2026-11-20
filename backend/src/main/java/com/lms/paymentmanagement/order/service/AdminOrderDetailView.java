package com.lms.paymentmanagement.order.service;

import com.lms.ledgersettlementmanagement.api.PaymentMethod;
import com.lms.ledgersettlementmanagement.api.PaymentOperationalState;
import com.lms.paymentmanagement.order.domain.OrderStatus;
import com.lms.paymentmanagement.slip.domain.PaymentSlipStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * PAY-HUB-01 {@code GET /api/v1/orders/{orderId}/admin-detail}: the whole
 * order for the staff Payments hub drawer - products, fee/postage/discount
 * breakdown, delivery snapshot, every payment attempt and slip, and the
 * refunded/remaining figures. Read-only; see plan §6.2 for nullability.
 */
public record AdminOrderDetailView(UUID orderId, long orderNumber, String displayOrderNumber, Instant createdAt,
		OrderStatus orderStatus, PaymentOperationalState operationalState, Student student, String currency,
		List<Line> lines, BigDecimal subtotal, BigDecimal facilityFee, BigDecimal postage, BigDecimal discount,
		BigDecimal total, BigDecimal refunded, BigDecimal remaining, Delivery delivery, List<PaymentAttempt> payments,
		List<Slip> slips) {

	/** {@code studentId} is the student profile id (for linking); any field may be {@code null}. */
	public record Student(UUID studentId, String name, String email, String mobile) {
	}

	public record Line(int lineNo, UUID courseId, String courseTitle, String label, String scope,
			BigDecimal unitAmount, BigDecimal facilityFee, BigDecimal discountAmount, BigDecimal lineTotal) {
	}

	public record Delivery(String recipientName, String addressLine, String city, String phone) {
	}

	/** {@code reference} is the approved slip reference for a slip payment, else the gateway reference. */
	public record PaymentAttempt(UUID paymentId, PaymentMethod method, String status, BigDecimal amount,
			String reference, Instant createdAt) {
	}

	public record Slip(UUID slipId, String referenceNumber, PaymentSlipStatus status, Instant submittedAt) {
	}

}
