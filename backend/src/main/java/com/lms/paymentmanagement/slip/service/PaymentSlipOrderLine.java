package com.lms.paymentmanagement.slip.service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * KLASS-T4c (plan §4.4): one covered item of the order a slip pays for, so a
 * reviewer sees exactly what an approval will unlock. Read-only projection of
 * {@code student_order_line}; empty list for a legacy (line-less) order.
 * V90: {@code courseId}/{@code courseTitle} = the line's course (an order may
 * span several courses; {@code courseTitle} may be {@code null} when not
 * resolved).
 */
public record PaymentSlipOrderLine(int lineNo, String scope, String label, BigDecimal unitAmount,
		BigDecimal facilityFee, BigDecimal lineTotal, UUID courseId, String courseTitle) {

	public PaymentSlipOrderLine(int lineNo, String scope, String label, BigDecimal unitAmount, BigDecimal facilityFee,
			BigDecimal lineTotal) {
		this(lineNo, scope, label, unitAmount, facilityFee, lineTotal, null, null);
	}

}
