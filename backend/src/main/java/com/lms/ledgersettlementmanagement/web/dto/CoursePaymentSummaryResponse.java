package com.lms.ledgersettlementmanagement.web.dto;

import com.lms.ledgersettlementmanagement.api.PaymentOperationalState;
import com.lms.ledgersettlementmanagement.service.CoursePaymentSummaryView;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Wave 6 §4 - {@code GET /api/v1/ledger/courses/{courseId}/summary}. */
public record CoursePaymentSummaryResponse(UUID courseId, String courseTitle, long totalOrders,
		List<CoursePaymentSummaryBucketResponse> byState) {

	public record CoursePaymentSummaryBucketResponse(PaymentOperationalState state, long count,
			BigDecimal totalAmount) {
	}

	public static CoursePaymentSummaryResponse from(CoursePaymentSummaryView view) {
		List<CoursePaymentSummaryBucketResponse> buckets = view.byState()
			.stream()
			.map(b -> new CoursePaymentSummaryBucketResponse(b.state(), b.count(), b.totalAmount()))
			.toList();
		return new CoursePaymentSummaryResponse(view.courseId(), view.courseTitle(), view.totalOrders(), buckets);
	}

}
