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
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Genuine concurrent-Testcontainers coverage for MVP-010 review item 1
 * [Critical]: {@code PaymentConfirmationService#confirmByGatewayReference}'s
 * webhook-confirmation read must be lock-protected, not a plain unlocked
 * read-then-write, or two genuinely concurrent webhook deliveries for the
 * same {@code gatewayReference} could both observe {@code PENDING}, both
 * pass the terminal-state guard, and both write their own {@code
 * PAYMENT_CONFIRMED} ledger entry.
 *
 * <p>Unlike {@code PaymentAndLedgerIntegrationTest#aDuplicateWebhookDeliveryForTheSameGatewayReferenceIsIdempotent}
 * (two strictly SEQUENTIAL calls, which only proves idempotency once a row is
 * already terminal), this test forces real overlap via a {@link
 * CyclicBarrier} so both HTTP calls reach {@code
 * PaymentRepository#findByGatewayReferenceAcrossTenantsForUpdate} at
 * effectively the same moment, genuinely exercising the {@code
 * PESSIMISTIC_WRITE} row lock (and, as a defensive backstop, V20's partial
 * unique index on {@code ledger_entry}).
 */
class PaymentWebhookConcurrencyIntegrationTest extends PaymentManagementTestSupport {

	@Test
	void concurrentWebhookDeliveriesForTheSameGatewayReferenceProduceExactlyOnePaymentLedgerEntryAndEnrollment()
			throws Exception {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("pay-webhook-race"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		TenantUser student = seedActiveStudent(tenant.getId(), "student@example.test");
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String studentToken = loginAndGetToken(host, "student@example.test");
		CourseResponse course = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug("pay-webhook-race"), teacher.getId(), CourseStatus.PUBLIC));
		OrderResponse order = createOrderOrFail(host, studentToken, course.id());
		PaymentInitiationResponse initiation = initiatePaymentOrFail(host, studentToken, order.id());

		int concurrency = 2;
		CyclicBarrier barrier = new CyclicBarrier(concurrency);
		ExecutorService executor = Executors.newFixedThreadPool(concurrency);
		List<Callable<HttpResult<Void>>> tasks = new ArrayList<>();
		for (int i = 0; i < concurrency; i++) {
			tasks.add(() -> {
				barrier.await();
				return sendPaymentWebhook(initiation.gatewayReference(), true);
			});
		}
		try {
			List<Future<HttpResult<Void>>> futures = executor.invokeAll(tasks);
			for (Future<HttpResult<Void>> future : futures) {
				// Every delivery must complete cleanly - either the genuine
				// confirmation, or the existing idempotent no-op path (never
				// an unhandled exception/500 from a lock-timeout or a raw
				// constraint-violation leaking through).
				HttpResult<Void> result = future.get(15, TimeUnit.SECONDS);
				assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
			}
		}
		finally {
			executor.shutdownNow();
		}

		Long confirmedPaymentCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM payment WHERE id = ? AND status = 'CONFIRMED'", Long.class,
				initiation.paymentId());
		assertThat(confirmedPaymentCount).isEqualTo(1L);

		Long ledgerEntryCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM ledger_entry WHERE payment_id = ? AND entry_type = 'PAYMENT_CONFIRMED'",
				Long.class, initiation.paymentId());
		assertThat(ledgerEntryCount).isEqualTo(1L);

		Long enrollmentCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM enrollment WHERE tenant_id = ? AND student_id = ? AND course_id = ? "
						+ "AND activating_payment_id = ? AND status = 'ACTIVE'",
				Long.class, tenant.getId(), student.getId(), course.id(), initiation.paymentId());
		assertThat(enrollmentCount).isEqualTo(1L);
	}

}
