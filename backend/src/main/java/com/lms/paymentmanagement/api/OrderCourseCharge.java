package com.lms.paymentmanagement.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * RPT-02: the immutable V90 {@code student_order_course_charge} snapshot of one
 * (order, course) - the facility-fee and postage figures in effect when the
 * order was created. Never recomputed from current course or tenant settings.
 *
 * @param postageAmount the course's postage charged on the order (0 when not
 * selected).
 */
public record OrderCourseCharge(UUID orderId, UUID courseId, BigDecimal facilityFeeAmount,
		BigDecimal postageAmount) {

}
