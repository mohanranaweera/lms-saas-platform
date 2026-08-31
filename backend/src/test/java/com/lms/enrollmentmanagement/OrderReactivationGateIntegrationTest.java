package com.lms.enrollmentmanagement;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.enrollmentmanagement.web.dto.ReactivationRequestResponse;
import com.lms.identityaccessservice.HttpResult;
import com.lms.paymentmanagement.order.web.dto.OrderResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Closes plan §18's "order-creation gating... a second order attempt against
 * the same already-linked (fulfilled) request -&gt; 409 (no
 * double-reactivation)" requirement (MVP-012 review finding H3) - not
 * previously covered anywhere: {@code OrderServiceTest} only exercises
 * {@code OrderService#createOrder} at the Mockito unit level, and {@code
 * ReactivationLifecycleIntegrationTest} only ever places ONE reactivation
 * order per cycle. This proves the real, end-to-end HTTP gate: once a
 * reactivation request has already been consumed by a first reactivation
 * order ({@code new_order_id} set), a second {@code POST /api/v1/orders}
 * attempt for the same course is rejected {@code 409} - with no second order
 * or a second link ever created - even though the underlying enrollment is
 * STILL {@code EXPIRED} at that point (the first reactivation order's
 * payment/slip has deliberately not been confirmed).
 */
class OrderReactivationGateIntegrationTest extends EnrollmentManagementTestSupport {

	@Test
	void aSecondOrderAttemptAgainstAnAlreadyFulfilledReactivationRequestIsRejectedWith409() {
		ExpiredEnrollmentFixture fixture = seedExpiredEnrollmentFixture("order-gate-fulfilled");
		ReactivationRequestResponse submitted = submitReactivationRequestOrFail(fixture.host(),
				fixture.studentToken(), fixture.enrollmentId());
		HttpResult<ReactivationRequestResponse> approveResult = approveReactivation(fixture.host(),
				fixture.adminToken(), submitted.id(), null);
		if (approveResult.getStatusCode() != HttpStatus.OK) {
			throw new IllegalStateException("Approval failed: " + approveResult.getStatusCode());
		}

		// First reactivation order - consumes (links) the APPROVED request.
		// Its payment is deliberately never confirmed, so the enrollment
		// stays EXPIRED for the second attempt below.
		HttpResult<OrderResponse> firstOrderResult = createOrder(fixture.host(), fixture.studentToken(),
				fixture.course().id());
		if (firstOrderResult.getStatusCode() != HttpStatus.CREATED) {
			throw new IllegalStateException(
					"First reactivation order creation failed: " + firstOrderResult.getStatusCode());
		}
		OrderResponse firstOrder = firstOrderResult.getBody().data();

		// Second attempt - the same request is now fulfilled (new_order_id
		// already set), so OrderService's gate must reject this with 409,
		// never a second order/link.
		HttpResult<OrderResponse> secondOrderResult = createOrder(fixture.host(), fixture.studentToken(),
				fixture.course().id());

		assertThat(secondOrderResult.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

		Long orderCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM student_order WHERE tenant_id = ? AND student_id = ? AND course_id = ?",
				Long.class, fixture.tenant().getId(), fixture.student().getId(), fixture.course().id());
		// Exactly two orders total for this (student, course): the ORIGINAL
		// first-time purchase (from seedExpiredEnrollmentFixture) and the ONE
		// successful reactivation order above - never a third from the
		// rejected second attempt.
		assertThat(orderCount).isEqualTo(2L);

		UUID linkedOrderId = jdbcTemplate.queryForObject(
				"SELECT new_order_id FROM reactivation_request WHERE id = ?", UUID.class, submitted.id());
		assertThat(linkedOrderId).isEqualTo(firstOrder.id());
	}

}
