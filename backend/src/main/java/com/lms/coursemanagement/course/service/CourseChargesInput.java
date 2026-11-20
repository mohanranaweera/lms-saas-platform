package com.lms.coursemanagement.course.service;

import java.math.BigDecimal;

/**
 * Multi-course checkout (V90) course charge settings on {@link
 * CourseService#updateCourse}. Every field {@code null} = unchanged:
 * <ul>
 * <li>{@code facilityFeeInherit = true} clears the override (inherit the
 * tenant default); {@code false} requires {@code facilityFeeOverride}.</li>
 * <li>{@code facilityFeeOverride} (&gt;= 0, 2 decimals; {@code 0} = no fee)
 * sets the override.</li>
 * <li>{@code postageFee} (&gt;= 0, 2 decimals; {@code 0} = free postage).</li>
 * </ul>
 */
public record CourseChargesInput(Boolean facilityFeeInherit, BigDecimal facilityFeeOverride, BigDecimal postageFee) {

	public static final CourseChargesInput NONE = new CourseChargesInput(null, null, null);

	boolean isEmpty() {
		return facilityFeeInherit == null && facilityFeeOverride == null && postageFee == null;
	}

}
