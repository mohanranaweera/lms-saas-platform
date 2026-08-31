package com.lms.enrollmentmanagement;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.enrollmentmanagement.web.dto.ReactivationRequestResponse;
import com.lms.identityaccessservice.HttpResult;
import com.lms.paymentmanagement.order.web.dto.OrderResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Genuine concurrent-Testcontainers coverage for MVP-012 review finding H8:
 * two genuinely concurrent {@code POST /api/v1/orders} attempts against the
 * SAME approved-unfulfilled reactivation request. Mirrors {@code
 * PaymentWebhookConcurrencyIntegrationTest}/{@code
 * SlipApprovalConcurrencyIntegrationTest}'s exact {@link CyclicBarrier}-based
 * technique - unlike {@code ReactivationLinkingApiImplTest} (a pure Mockito
 * test that stubs the locked finder to return an already-empty {@code
 * Optional}), this forces real overlap so both HTTP calls reach {@code
 * ReactivationRequestRepository#findApprovedUnfulfilledByEnrollmentIdForUpdate}
 * ({@code PESSIMISTIC_WRITE}) at effectively the same moment, genuinely
 * exercising the row lock rather than simulating its outcome.
 *
 * <p>Proves the double-charging-risk property this finding exists for:
 * exactly one of the two concurrent {@code createOrder} calls wins (201,
 * linked to the reactivation request), the other genuinely LOSES the race
 * under real concurrent load (409) - and, critically, the losing attempt
 * leaves no orphaned/half-created order behind, since {@code
 * OrderService#createOrder}'s class-level {@code @Transactional} rolls the
 * just-inserted order row back together with the mapped {@code
 * ConflictException}.
 */
class ReactivationOrderConcurrencyIntegrationTest extends EnrollmentManagementTestSupport {

	@Test
	void concurrentOrderCreationAttemptsAgainstTheSameApprovedUnfulfilledReactivationRequestProduceExactlyOneWinner()
			throws Exception {
		ExpiredEnrollmentFixture fixture = seedExpiredEnrollmentFixture("react-order-race");
		ReactivationRequestResponse submitted = submitReactivationRequestOrFail(fixture.host(),
				fixture.studentToken(), fixture.enrollmentId());
		HttpResult<ReactivationRequestResponse> approveResult = approveReactivation(fixture.host(),
				fixture.adminToken(), submitted.id(), null);
		if (approveResult.getStatusCode() != HttpStatus.OK) {
			throw new IllegalStateException("Approval failed: " + approveResult.getStatusCode());
		}

		int concurrency = 2;
		CyclicBarrier barrier = new CyclicBarrier(concurrency);
		ExecutorService executor = Executors.newFixedThreadPool(concurrency);
		List<Callable<HttpResult<OrderResponse>>> tasks = new ArrayList<>();
		for (int i = 0; i < concurrency; i++) {
			tasks.add(() -> {
				barrier.await();
				return createOrder(fixture.host(), fixture.studentToken(), fixture.course().id());
			});
		}

		List<HttpStatus> statuses = new ArrayList<>();
		try {
			List<Future<HttpResult<OrderResponse>>> futures = executor.invokeAll(tasks);
			for (Future<HttpResult<OrderResponse>> future : futures) {
				HttpResult<OrderResponse> result = future.get(15, TimeUnit.SECONDS);
				statuses.add(result.getStatusCode());
			}
		}
		finally {
			executor.shutdownNow();
		}

		assertThat(statuses).containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.CONFLICT);

		// Exactly two orders total for this (student, course): the ORIGINAL
		// first-time purchase (from seedExpiredEnrollmentFixture) and the ONE
		// winning reactivation order - never a second, orphaned order from
		// the losing concurrent attempt.
		Long orderCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM student_order WHERE tenant_id = ? AND student_id = ? AND course_id = ?",
				Long.class, fixture.tenant().getId(), fixture.student().getId(), fixture.course().id());
		assertThat(orderCount).isEqualTo(2L);

		UUID linkedOrderId = jdbcTemplate.queryForObject(
				"SELECT new_order_id FROM reactivation_request WHERE id = ?", UUID.class, submitted.id());
		assertThat(linkedOrderId).isNotNull();

		// The linked order id must be a REAL, still-existing order row (the
		// winner's), never a dangling reference to a rolled-back attempt.
		Long linkedOrderExists = jdbcTemplate.queryForObject("SELECT count(*) FROM student_order WHERE id = ?",
				Long.class, linkedOrderId);
		assertThat(linkedOrderExists).isEqualTo(1L);
	}

}
