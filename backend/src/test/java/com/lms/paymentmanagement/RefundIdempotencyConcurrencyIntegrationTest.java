package com.lms.paymentmanagement;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.paymentmanagement.order.web.dto.OrderResponse;
import com.lms.paymentmanagement.order.web.dto.PaymentInitiationResponse;
import com.lms.paymentmanagement.payment.web.dto.RefundResponse;
import com.lms.tenantmanagement.domain.Tenant;
import java.math.BigDecimal;
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
 * Genuine concurrent-Testcontainers coverage for the review finding that
 * {@link com.lms.paymentmanagement.payment.service.RefundService#processRefund}'s
 * idempotency-key dedup lookup originally ran BEFORE the payment row's {@code
 * PESSIMISTIC_WRITE} lock was acquired: two genuinely concurrent refund
 * requests carrying the same idempotency key could both miss the pre-lock
 * dedup check, then serialize on the lock, and the second insert would throw
 * an unhandled {@code DataIntegrityViolationException} against V20's unique
 * index instead of returning the first request's row as a replay.
 *
 * <p>The fix moved the dedup lookup to run after the lock is acquired,
 * mirroring {@link PaymentWebhookConcurrencyIntegrationTest}'s
 * lock-then-check shape for the webhook-confirmation path. Unlike {@link
 * com.lms.paymentmanagement.PaymentAndLedgerIntegrationTest#resubmittingARefundWithTheSameIdempotencyKeyReturnsTheOriginalRefundNotADuplicate}
 * (two strictly SEQUENTIAL calls, which only proves the pre-lock check
 * works once a row is already present), this test forces real overlap via a
 * {@link CyclicBarrier} so both HTTP calls reach {@code
 * RefundService#processRefund} at effectively the same moment.
 */
class RefundIdempotencyConcurrencyIntegrationTest extends PaymentManagementTestSupport {

	@Test
	void concurrentRefundRequestsWithTheSameIdempotencyKeyProduceExactlyOneRefundRowAndBothCallsReturnItsId()
			throws Exception {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("pay-refund-race"));
		String adminEmail = "admin@example.test";
		seedTenantUser(tenant.getId(), adminEmail, RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		seedActiveStudent(tenant.getId(), "student@example.test");
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, adminEmail);
		String studentToken = loginAndGetToken(host, "student@example.test");
		CourseResponse course = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug("pay-refund-race"), teacher.getId(), CourseStatus.PUBLIC));
		OrderResponse order = createOrderOrFail(host, studentToken, course.id());
		PaymentInitiationResponse initiation = initiatePaymentOrFail(host, studentToken, order.id());
		sendPaymentWebhook(initiation.gatewayReference(), true);

		UUID idempotencyKey = UUID.randomUUID();
		BigDecimal amount = new BigDecimal("10.00");

		int concurrency = 2;
		CyclicBarrier barrier = new CyclicBarrier(concurrency);
		ExecutorService executor = Executors.newFixedThreadPool(concurrency);
		List<Callable<HttpResult<RefundResponse>>> tasks = new ArrayList<>();
		for (int i = 0; i < concurrency; i++) {
			tasks.add(() -> {
				barrier.await();
				return createRefund(host, adminToken, initiation.paymentId(), amount,
						"Concurrent resubmission with the same idempotency key", idempotencyKey);
			});
		}

		List<UUID> returnedRefundIds = new ArrayList<>();
		try {
			List<Future<HttpResult<RefundResponse>>> futures = executor.invokeAll(tasks);
			for (Future<HttpResult<RefundResponse>> future : futures) {
				// Every delivery must complete cleanly with the created refund's
				// view - never an unhandled 500/constraint-violation leaking
				// through from the second request racing the first.
				HttpResult<RefundResponse> result = future.get(15, TimeUnit.SECONDS);
				assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
				returnedRefundIds.add(result.getBody().data().id());
			}
		}
		finally {
			executor.shutdownNow();
		}

		assertThat(returnedRefundIds).hasSize(concurrency);
		assertThat(returnedRefundIds).containsOnly(returnedRefundIds.get(0));

		Long refundRowCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM payment_refund WHERE original_payment_id = ? AND idempotency_key = ?",
				Long.class, initiation.paymentId(), idempotencyKey);
		assertThat(refundRowCount).isEqualTo(1L);

		Long refundLedgerCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM ledger_entry WHERE payment_id = ? AND entry_type = 'REFUND'", Long.class,
				initiation.paymentId());
		assertThat(refundLedgerCount).isEqualTo(1L);
	}

}
