package com.lms.ledgersettlementmanagement.service;

import com.lms.paymentmanagement.api.OrderPaymentDetail;
import com.lms.paymentmanagement.api.OrderRevenueBasis;
import com.lms.paymentmanagement.api.PaymentStatusApi;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * V90/ADR-029: display title for orders without a single course - a
 * multi-course line order shows "N courses". One batched, tenant-scoped read
 * through {@link PaymentStatusApi}; only orders whose {@code courseId} is
 * {@code null} are looked up.
 */
final class MultiCourseOrderTitles {

	private MultiCourseOrderTitles() {
	}

	static Map<UUID, String> of(PaymentStatusApi paymentStatusApi, Collection<OrderPaymentDetail> details) {
		List<UUID> multi = details.stream()
			.filter(detail -> detail.courseId() == null)
			.map(OrderPaymentDetail::orderId)
			.toList();
		if (multi.isEmpty()) {
			return Map.of();
		}
		Map<UUID, String> titles = new HashMap<>();
		for (OrderRevenueBasis basis : paymentStatusApi.findOrderRevenueBases(multi)) {
			int courses = basis.courseShares().size();
			if (courses > 0) {
				titles.put(basis.orderId(), courses == 1 ? "1 course" : courses + " courses");
			}
		}
		return titles;
	}

}
