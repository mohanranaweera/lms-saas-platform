package com.lms.paymentmanagement;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.paymentmanagement.order.web.dto.OrderResponse;
import com.lms.paymentmanagement.order.web.dto.PaymentInitiationResponse;
import com.lms.tenantmanagement.domain.Tenant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Wave 6 §3.3/§4/§8 - {@code PaymentWriteService#createPendingPayment}'s new
 * {@code idempotencyKey} replay behavior, mirroring {@link
 * OrderCreationIdempotencyIntegrationTest}'s exact coverage shape for the
 * sibling "Pay Now" double-click path: a same-key repeat replays the same
 * {@code PENDING} payment with no duplicate row persisted; a genuinely
 * concurrent same-key race is closed by V52's {@code uq_payment_idempotency}
 * partial unique index plus {@code PaymentRepository#acquireIdempotencyLock};
 * a different/absent key still creates a genuinely new payment (regression
 * check).
 */
class PaymentInitiationIdempotencyIntegrationTest extends PaymentManagementTestSupport {

	@Test
	void aRepeatedInitiatePaymentCallWithTheSameIdempotencyKeyReplaysTheSamePaymentWithNoDuplicateRow() {
		Fixture fixture = seedTenantWithOrder("pay-idem-replay");
		UUID idempotencyKey = UUID.randomUUID();

		HttpResult<PaymentInitiationResponse> first = initiatePayment(fixture.host(), fixture.studentToken(),
				fixture.order().id(), idempotencyKey);
		assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

		HttpResult<PaymentInitiationResponse> second = initiatePayment(fixture.host(), fixture.studentToken(),
				fixture.order().id(), idempotencyKey);

		assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(second.getBody().data().paymentId()).isEqualTo(first.getBody().data().paymentId());

		Long paymentCount = jdbcTemplate.queryForObject("SELECT count(*) FROM payment WHERE order_id = ?", Long.class,
				fixture.order().id());
		assertThat(paymentCount).isEqualTo(1L);
	}

	@Test
	void twoGenuinelyConcurrentInitiatePaymentCallsWithTheSameIdempotencyKeyProduceExactlyOnePayment()
			throws Exception {
		Fixture fixture = seedTenantWithOrder("pay-idem-race");
		UUID idempotencyKey = UUID.randomUUID();

		int concurrency = 2;
		CyclicBarrier barrier = new CyclicBarrier(concurrency);
		ExecutorService executor = Executors.newFixedThreadPool(concurrency);
		List<Callable<HttpResult<PaymentInitiationResponse>>> tasks = new ArrayList<>();
		for (int i = 0; i < concurrency; i++) {
			tasks.add(() -> {
				barrier.await();
				return initiatePayment(fixture.host(), fixture.studentToken(), fixture.order().id(), idempotencyKey);
			});
		}

		List<HttpStatus> statuses = new ArrayList<>();
		List<UUID> paymentIds = new ArrayList<>();
		try {
			List<Future<HttpResult<PaymentInitiationResponse>>> futures = executor.invokeAll(tasks);
			for (Future<HttpResult<PaymentInitiationResponse>> future : futures) {
				HttpResult<PaymentInitiationResponse> result = future.get(15, TimeUnit.SECONDS);
				statuses.add(result.getStatusCode());
				if (result.getStatusCode().is2xxSuccessful()) {
					paymentIds.add(result.getBody().data().paymentId());
				}
			}
		}
		finally {
			executor.shutdownNow();
		}

		assertThat(statuses).allMatch(HttpStatus::is2xxSuccessful);
		assertThat(paymentIds).hasSize(2).containsOnly(paymentIds.get(0));

		Long paymentCount = jdbcTemplate.queryForObject("SELECT count(*) FROM payment WHERE order_id = ?", Long.class,
				fixture.order().id());
		assertThat(paymentCount).isEqualTo(1L);
	}

	/**
	 * Security-review follow-up (Wave 6 completion review, finding #2):
	 * {@code PaymentInitiationService#initiatePayment} calls {@code
	 * OrderService#loadOrderOwnedByCurrentStudent} - an ownership check that
	 * runs BEFORE {@code PaymentWriteService#createPendingPayment}'s
	 * idempotency-key lookup is ever reached. A second student (never the
	 * order's owner) who knows/guesses the owning student's real {@code
	 * orderId} AND replays that student's exact {@code idempotencyKey} value
	 * must therefore be rejected by the ownership check itself, exactly as if
	 * no {@code idempotencyKey} had been supplied at all - proving the
	 * idempotency-replay mechanism can never be leveraged as a cross-student
	 * authorization bypass, and that student B never sees student A's payment
	 * data as a "replay".
	 */
	@Test
	void aSecondStudentReplayingTheOwningStudentsRealOrderIdAndIdempotencyKeyIsRejectedBeforeAnyReplayLogic() {
		Fixture fixture = seedTenantWithOrder("pay-idem-cross-student");
		Tenant tenant = fixture.tenant();
		TenantUser studentB = seedActiveStudent(tenant.getId(), "student-b@example.test");
		String studentBToken = loginAndGetToken(fixture.host(), "student-b@example.test");
		UUID idempotencyKey = UUID.randomUUID();

		HttpResult<PaymentInitiationResponse> ownerInitiation = initiatePayment(fixture.host(),
				fixture.studentToken(), fixture.order().id(), idempotencyKey);
		assertThat(ownerInitiation.getStatusCode()).isEqualTo(HttpStatus.CREATED);

		HttpResult<PaymentInitiationResponse> intruderAttempt = initiatePayment(fixture.host(), studentBToken,
				fixture.order().id(), idempotencyKey);

		assertThat(intruderAttempt.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

		// Never a second payment row, and never any row attributed to
		// student B - the ownership check rejects before createPendingPayment
		// (and therefore before any idempotency-key lookup/replay) ever runs.
		Long paymentCount = jdbcTemplate.queryForObject("SELECT count(*) FROM payment WHERE order_id = ?", Long.class,
				fixture.order().id());
		assertThat(paymentCount).isEqualTo(1L);
		Long paymentsOwnedByIntruder = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM payment p JOIN student_order so ON so.id = p.order_id "
						+ "WHERE so.student_id = ?",
				Long.class, studentB.getId());
		assertThat(paymentsOwnedByIntruder).isEqualTo(0L);
	}

	/**
	 * Tenant-scoped equivalent of the cross-student test above: a student in
	 * a completely different tenant, holding a real {@code orderId} that only
	 * exists in tenant A (e.g. leaked/guessed), must never be able to replay
	 * or initiate against it - {@code StudentOrderRepository#findById} is
	 * itself tenant-scoped (per ADR-006/{@code TenantAwareRepository}), so
	 * this order id never resolves inside tenant B's context at all, before
	 * ownership or idempotency logic is even reached.
	 */
	@Test
	@Tag("cross-tenant")
	void aStudentInAnotherTenantReplayingTheOwningStudentsRealOrderIdAndIdempotencyKeyGetsNotFound() {
		Fixture fixture = seedTenantWithOrder("pay-idem-cross-tenant");
		UUID idempotencyKey = UUID.randomUUID();
		HttpResult<PaymentInitiationResponse> ownerInitiation = initiatePayment(fixture.host(),
				fixture.studentToken(), fixture.order().id(), idempotencyKey);
		assertThat(ownerInitiation.getStatusCode()).isEqualTo(HttpStatus.CREATED);

		Tenant otherTenant = seedActiveTenant(uniqueSubdomain("pay-idem-cross-tenant-b"));
		seedTenantUser(otherTenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		seedActiveStudent(otherTenant.getId(), "student@example.test");
		String otherHost = hostFor(otherTenant.getSubdomain());
		String otherStudentToken = loginAndGetToken(otherHost, "student@example.test");

		HttpResult<PaymentInitiationResponse> intruderAttempt = initiatePayment(otherHost, otherStudentToken,
				fixture.order().id(), idempotencyKey);

		assertThat(intruderAttempt.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		Long paymentCount = jdbcTemplate.queryForObject("SELECT count(*) FROM payment WHERE order_id = ?", Long.class,
				fixture.order().id());
		assertThat(paymentCount).isEqualTo(1L);
	}

	@Test
	void aDifferentIdempotencyKeyStillCreatesAGenuinelyNewPaymentAttempt() {
		Fixture fixture = seedTenantWithOrder("pay-idem-diff-key");

		HttpResult<PaymentInitiationResponse> first = initiatePayment(fixture.host(), fixture.studentToken(),
				fixture.order().id(), UUID.randomUUID());
		assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

		HttpResult<PaymentInitiationResponse> second = initiatePayment(fixture.host(), fixture.studentToken(),
				fixture.order().id(), UUID.randomUUID());

		assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(second.getBody().data().paymentId()).isNotEqualTo(first.getBody().data().paymentId());

		Long paymentCount = jdbcTemplate.queryForObject("SELECT count(*) FROM payment WHERE order_id = ?", Long.class,
				fixture.order().id());
		assertThat(paymentCount).isEqualTo(2L);
	}

	@Test
	void noIdempotencyKeySuppliedStillCreatesAGenuinelyNewPaymentAttemptEveryTime() {
		Fixture fixture = seedTenantWithOrder("pay-idem-no-key");

		HttpResult<PaymentInitiationResponse> first = initiatePayment(fixture.host(), fixture.studentToken(),
				fixture.order().id());
		assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

		HttpResult<PaymentInitiationResponse> second = initiatePayment(fixture.host(), fixture.studentToken(),
				fixture.order().id());

		assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(second.getBody().data().paymentId()).isNotEqualTo(first.getBody().data().paymentId());

		Long paymentCount = jdbcTemplate.queryForObject("SELECT count(*) FROM payment WHERE order_id = ?", Long.class,
				fixture.order().id());
		assertThat(paymentCount).isEqualTo(2L);
	}

	// ------------------------------------------------------------------
	// Fixture.
	// ------------------------------------------------------------------

	private Fixture seedTenantWithOrder(String prefix) {
		Tenant tenant = seedActiveTenant(uniqueSubdomain(prefix));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		seedActiveStudent(tenant.getId(), "student@example.test");
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String studentToken = loginAndGetToken(host, "student@example.test");
		CourseResponse course = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug(prefix), teacher.getId(), CourseStatus.PUBLIC));
		OrderResponse order = createOrderOrFail(host, studentToken, course.id());
		return new Fixture(tenant, host, studentToken, order);
	}

	private record Fixture(Tenant tenant, String host, String studentToken, OrderResponse order) {
	}

}
