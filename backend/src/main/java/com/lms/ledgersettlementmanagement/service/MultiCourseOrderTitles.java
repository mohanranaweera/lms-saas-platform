package com.lms.ledgersettlementmanagement.service;

import com.lms.paymentmanagement.api.OrderLineSnapshot;
import com.lms.paymentmanagement.api.OrderPaymentDetail;
import com.lms.paymentmanagement.api.PaymentStatusApi;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * V90/ADR-029: display title for orders without a single course - a
 * multi-course line order shows "N courses", plus " · M items" when it holds
 * more lines than courses (e.g. one course bought for two months). One
 * batched, tenant-scoped read through {@link PaymentStatusApi}; only orders
 * whose {@code courseId} is {@code null} are looked up.
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
		Map<UUID, Set<UUID>> coursesByOrder = new HashMap<>();
		Map<UUID, Integer> itemsByOrder = new HashMap<>();
		for (OrderLineSnapshot line : paymentStatusApi.findOrderLinesForOrders(multi)) {
			coursesByOrder.computeIfAbsent(line.orderId(), key -> new HashSet<>()).add(line.courseId());
			itemsByOrder.merge(line.orderId(), 1, Integer::sum);
		}
		Map<UUID, String> titles = new HashMap<>();
		coursesByOrder.forEach((orderId, courses) -> titles.put(orderId,
				title(courses.size(), itemsByOrder.getOrDefault(orderId, courses.size()))));
		return titles;
	}

	static String title(int courses, int items) {
		String title = courses == 1 ? "1 course" : courses + " courses";
		return items > courses ? title + " · " + items + " items" : title;
	}

}
