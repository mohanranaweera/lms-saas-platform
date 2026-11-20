package com.lms.paymentmanagement.api;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * ADR-029: the immutable inputs a revenue consumer needs to allocate a ledger
 * entry of {@code orderId} to courses. {@code orderAmount} = the order total
 * the confirmed payment equals (including facility fees and postage). {@code
 * courseShares} = per course, the sum of that course's {@code
 * student_order_line.line_total - facility_fee_amount} (= unit - discount,
 * ADR-029 Addendum A2: facility fees and postage are institute-only and never
 * in a share); empty for a legacy (line-less) order, whose full amount belongs
 * to {@code legacyCourseId}.
 */
public record OrderRevenueBasis(UUID orderId, BigDecimal orderAmount, UUID legacyCourseId,
		Map<UUID, BigDecimal> courseShares) {

	public boolean lineOrder() {
		return !courseShares.isEmpty();
	}

	/**
	 * ADR-029 Addendum A2 - the single definition of one order line's course
	 * share: {@code line_total - facility_fee_amount} (= unit - discount).
	 * Facility fees (PER_LINE, PER_COURSE and the PER_ORDER fee the composer
	 * stores on line 1) and postage are institute-only service fees, never
	 * inside a course share. V92's {@code ck_student_order_line_discount_le_unit}
	 * (discount &lt;= unit) together with V87's {@code line_total = unit + fee -
	 * discount} CHECK guarantee the result is never negative; the clamp to 0 is
	 * a defensive guard only, so a corrupt row can never yield a negative share.
	 */
	public static BigDecimal courseShareOfLine(BigDecimal lineTotal, BigDecimal facilityFeeAmount) {
		BigDecimal fee = facilityFeeAmount == null ? BigDecimal.ZERO : facilityFeeAmount;
		BigDecimal share = lineTotal.subtract(fee);
		return share.signum() < 0 ? BigDecimal.ZERO.setScale(2) : share;
	}

}
