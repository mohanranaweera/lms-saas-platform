package com.lms.paymentmanagement.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One immutable {@code student_order_line} (V87, D06) as other domains may read
 * it (KLASS-T4c): the entitlement grant path copies scope / course / module /
 * lesson / period / coverage key from here, never from a request.
 *
 * @param scope {@code MONTHLY|MODULE|LESSON}.
 * @param coverageKey V86/V87 format, see {@code enrollmentmanagement.api.CoverageKeys}.
 * @param studentId the owning order's student.
 */
public record OrderLineSnapshot(UUID lineId, UUID orderId, UUID studentId, int lineNo, String scope, UUID courseId,
		UUID moduleId, UUID lessonId, Integer periodYear, Integer periodMonth, String coverageKey, String label,
		BigDecimal unitAmount, BigDecimal facilityFee, BigDecimal discountAmount, BigDecimal lineTotal, String currency,
		boolean reward) {

	/** Compatibility constructor for existing report fixtures without discount metadata. */
	public OrderLineSnapshot(UUID lineId, UUID orderId, UUID studentId, int lineNo, String scope, UUID courseId,
			UUID moduleId, UUID lessonId, Integer periodYear, Integer periodMonth, String coverageKey, String label,
			BigDecimal unitAmount, BigDecimal facilityFee, BigDecimal lineTotal, String currency) {
		this(lineId, orderId, studentId, lineNo, scope, courseId, moduleId, lessonId, periodYear, periodMonth,
				coverageKey, label, unitAmount, facilityFee, BigDecimal.ZERO.setScale(2), lineTotal, currency, false);
	}

	/** ADR-029 A2: this line's course share ({@code line_total - facility_fee_amount}). */
	public BigDecimal courseShare() {
		return OrderRevenueBasis.courseShareOfLine(lineTotal, facilityFee);
	}

}
