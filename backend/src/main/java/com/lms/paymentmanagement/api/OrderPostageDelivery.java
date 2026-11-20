package com.lms.paymentmanagement.api;

import java.util.UUID;

/**
 * TUTE-01: one immutable V90 {@code student_order_course_charge} row's postage selection
 * together with its order's delivery snapshot ({@code student_order.delivery_*}, set only when
 * postage was selected somewhere on the order). Read-only; never recomputed from current
 * course or profile data.
 *
 * @param postageSelected the student chose postage for {@code courseId} on this order (a zero
 * postage fee still counts as selected).
 * @param recipientName / addressLine / city / phone the order's delivery snapshot; all
 * {@code null} when the order has none.
 */
public record OrderPostageDelivery(UUID orderId, UUID courseId, boolean postageSelected, String recipientName,
		String addressLine, String city, String phone) {

}
