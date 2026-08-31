package com.lms.enrollmentmanagement.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link ReactivationRequest}'s state-transition and
 * {@link ReactivationRequest#linkNewOrder(UUID)} invariants - previously only
 * exercised indirectly via {@code ReactivationLinkingApiImplTest} (a
 * service/Mockito-level test whose repository stub already pre-filters to
 * {@code newOrderId IS NULL}, so it never reaches {@code linkNewOrder}'s own
 * "reject a second, different order" or "idempotent no-op on the same order"
 * branches). Mirrors {@link EnrollmentTest}'s domain-entity-level coverage
 * pattern for this same module.
 */
class ReactivationRequestTest {

	private static final UUID TENANT_ID = UUID.randomUUID();

	private static final UUID ENROLLMENT_ID = UUID.randomUUID();

	private static final UUID REQUESTED_BY = UUID.randomUUID();

	private static final UUID REVIEWER_ID = UUID.randomUUID();

	@Test
	void newRequestStartsSubmittedWithNoReviewOrLink() {
		ReactivationRequest request = new ReactivationRequest(TENANT_ID, ENROLLMENT_ID, REQUESTED_BY);

		assertThat(request.getStatus()).isEqualTo(ReactivationRequestStatus.SUBMITTED);
		assertThat(request.getReviewedBy()).isNull();
		assertThat(request.getReviewedAt()).isNull();
		assertThat(request.getNewOrderId()).isNull();
	}

	@Test
	void approveTransitionsToApprovedAndRecordsTheReviewer() {
		ReactivationRequest request = new ReactivationRequest(TENANT_ID, ENROLLMENT_ID, REQUESTED_BY);
		Instant reviewedAt = Instant.now();

		request.approve(REVIEWER_ID, reviewedAt);

		assertThat(request.getStatus()).isEqualTo(ReactivationRequestStatus.APPROVED);
		assertThat(request.getReviewedBy()).isEqualTo(REVIEWER_ID);
		assertThat(request.getReviewedAt()).isEqualTo(reviewedAt);
	}

	@Test
	void rejectTransitionsToRejectedAndRecordsTheReviewer() {
		ReactivationRequest request = new ReactivationRequest(TENANT_ID, ENROLLMENT_ID, REQUESTED_BY);
		Instant reviewedAt = Instant.now();

		request.reject(REVIEWER_ID, reviewedAt);

		assertThat(request.getStatus()).isEqualTo(ReactivationRequestStatus.REJECTED);
		assertThat(request.getReviewedBy()).isEqualTo(REVIEWER_ID);
		assertThat(request.getReviewedAt()).isEqualTo(reviewedAt);
	}

	@Test
	void approveIsOneDirectionalAndCannotBeCalledOnAnAlreadyDecidedRequest() {
		ReactivationRequest request = new ReactivationRequest(TENANT_ID, ENROLLMENT_ID, REQUESTED_BY);
		request.approve(REVIEWER_ID, Instant.now());

		assertThatThrownBy(() -> request.approve(REVIEWER_ID, Instant.now())).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("SUBMITTED");
	}

	@Test
	void rejectCannotBeCalledOnAnAlreadyApprovedRequest() {
		ReactivationRequest request = new ReactivationRequest(TENANT_ID, ENROLLMENT_ID, REQUESTED_BY);
		request.approve(REVIEWER_ID, Instant.now());

		assertThatThrownBy(() -> request.reject(REVIEWER_ID, Instant.now())).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("SUBMITTED");
	}

	@Test
	void linkNewOrderRejectsALinkAttemptWhileStillSubmitted() {
		ReactivationRequest request = new ReactivationRequest(TENANT_ID, ENROLLMENT_ID, REQUESTED_BY);
		UUID orderId = UUID.randomUUID();

		assertThatThrownBy(() -> request.linkNewOrder(orderId)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("APPROVED");
	}

	@Test
	void linkNewOrderRejectsANullOrderId() {
		ReactivationRequest request = new ReactivationRequest(TENANT_ID, ENROLLMENT_ID, REQUESTED_BY);
		request.approve(REVIEWER_ID, Instant.now());

		assertThatThrownBy(() -> request.linkNewOrder(null)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void linkNewOrderSetsTheOrderIdOnAnApprovedRequest() {
		ReactivationRequest request = new ReactivationRequest(TENANT_ID, ENROLLMENT_ID, REQUESTED_BY);
		request.approve(REVIEWER_ID, Instant.now());
		UUID orderId = UUID.randomUUID();

		request.linkNewOrder(orderId);

		assertThat(request.getNewOrderId()).isEqualTo(orderId);
	}

	@Test
	void linkNewOrderIsAnIdempotentNoOpWhenCalledAgainWithTheSameOrderId() {
		ReactivationRequest request = new ReactivationRequest(TENANT_ID, ENROLLMENT_ID, REQUESTED_BY);
		request.approve(REVIEWER_ID, Instant.now());
		UUID orderId = UUID.randomUUID();
		request.linkNewOrder(orderId);

		request.linkNewOrder(orderId);

		assertThat(request.getNewOrderId()).isEqualTo(orderId);
	}

	@Test
	void linkNewOrderRejectsLinkingToADifferentOrderOnceAlreadyLinked() {
		ReactivationRequest request = new ReactivationRequest(TENANT_ID, ENROLLMENT_ID, REQUESTED_BY);
		request.approve(REVIEWER_ID, Instant.now());
		request.linkNewOrder(UUID.randomUUID());
		UUID differentOrderId = UUID.randomUUID();

		assertThatThrownBy(() -> request.linkNewOrder(differentOrderId)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("already linked");
	}

}
