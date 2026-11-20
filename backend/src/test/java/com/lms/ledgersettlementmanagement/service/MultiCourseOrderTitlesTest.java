package com.lms.ledgersettlementmanagement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lms.paymentmanagement.api.OrderLineSnapshot;
import com.lms.paymentmanagement.api.OrderPaymentDetail;
import com.lms.paymentmanagement.api.PaymentStatusApi;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MultiCourseOrderTitlesTest {

	private static final UUID COURSE_A = UUID.randomUUID();
	private static final UUID COURSE_B = UUID.randomUUID();

	private final PaymentStatusApi paymentStatusApi = mock(PaymentStatusApi.class);

	@Test
	void anOrderWithMoreLinesThanCoursesCountsBoth() {
		UUID order = UUID.randomUUID();
		when(paymentStatusApi.findOrderLinesForOrders(any())).thenReturn(List.of(
				line(order, 1, COURSE_A, 9), line(order, 2, COURSE_A, 10), line(order, 3, COURSE_B, 9)));

		Map<UUID, String> titles = MultiCourseOrderTitles.of(paymentStatusApi, List.of(detail(order, null)));

		assertThat(titles).containsEntry(order, "2 courses · 3 items");
	}

	@Test
	void oneLinePerCourseKeepsThePlainCourseCount() {
		UUID order = UUID.randomUUID();
		when(paymentStatusApi.findOrderLinesForOrders(any()))
			.thenReturn(List.of(line(order, 1, COURSE_A, 9), line(order, 2, COURSE_B, 9)));

		assertThat(MultiCourseOrderTitles.of(paymentStatusApi, List.of(detail(order, null))))
			.containsEntry(order, "2 courses");
	}

	@Test
	void oneCourseForSeveralMonthsIsOneCourseWithItsItems() {
		UUID order = UUID.randomUUID();
		when(paymentStatusApi.findOrderLinesForOrders(any()))
			.thenReturn(List.of(line(order, 1, COURSE_A, 9), line(order, 2, COURSE_A, 10)));

		assertThat(MultiCourseOrderTitles.of(paymentStatusApi, List.of(detail(order, null))))
			.containsEntry(order, "1 course · 2 items");
	}

	@Test
	void singleCourseOrdersAreNeverLookedUp() {
		Map<UUID, String> titles = MultiCourseOrderTitles.of(paymentStatusApi,
				List.of(detail(UUID.randomUUID(), COURSE_A)));

		assertThat(titles).isEmpty();
		verifyNoInteractions(paymentStatusApi);
	}

	@Test
	void onlyMultiCourseOrdersAreRequested() {
		UUID multi = UUID.randomUUID();
		when(paymentStatusApi.findOrderLinesForOrders(any())).thenReturn(List.of(line(multi, 1, COURSE_A, 9)));

		MultiCourseOrderTitles.of(paymentStatusApi, List.of(detail(multi, null), detail(UUID.randomUUID(), COURSE_B)));

		verify(paymentStatusApi).findOrderLinesForOrders(List.of(multi));
	}

	private static OrderPaymentDetail detail(UUID orderId, UUID courseId) {
		return new OrderPaymentDetail(orderId, UUID.randomUUID(), courseId, null, new BigDecimal("100.00"), "LKR",
				false, false, null, null);
	}

	private static OrderLineSnapshot line(UUID orderId, int lineNo, UUID courseId, int month) {
		return new OrderLineSnapshot(UUID.randomUUID(), orderId, UUID.randomUUID(), lineNo, "MONTHLY", courseId, null,
				null, 2026, month, "coverage-" + lineNo, "Month " + month, new BigDecimal("1000.00"), BigDecimal.ZERO,
				new BigDecimal("1000.00"), "LKR");
	}

}
