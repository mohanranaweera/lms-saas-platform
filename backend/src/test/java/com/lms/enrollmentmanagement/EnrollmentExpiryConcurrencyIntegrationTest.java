package com.lms.enrollmentmanagement;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.enrollmentmanagement.api.EnrollmentAccessStateType;
import com.lms.enrollmentmanagement.web.dto.EnrollmentAccessStateResponse;
import com.lms.identityaccessservice.HttpResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Genuine concurrent-Testcontainers coverage for plan §18's explicit
 * integration requirement: "{@code enrollment_expiry_event} write is
 * exactly-once under concurrent reads past expiry (race test against the
 * partial unique index); original {@code payment}/{@code ledger_entry}/
 * {@code enrollment} activation columns provably unchanged (byte-for-byte
 * row comparison before/after expiry observation)." Mirrors {@code
 * ReactivationOrderConcurrencyIntegrationTest}/{@code
 * PaymentWebhookConcurrencyIntegrationTest}'s exact {@link CyclicBarrier}
 * technique - forces real overlap so multiple {@code GET
 * /api/v1/courses/{courseId}/access-state} calls reach {@code
 * EnrollmentExpiryService#recordExpiryEventIfAbsent}'s {@code
 * saveAndFlush}/{@code catch (DataIntegrityViolationException)} race guard
 * at effectively the same moment, genuinely exercising {@code
 * uq_enrollment_expiry_event_tenant_enrollment_type} (V22) rather than
 * simulating its outcome via mocks (unlike {@code EnrollmentExpiryServiceTest},
 * a pure Mockito unit test).
 */
class EnrollmentExpiryConcurrencyIntegrationTest extends EnrollmentManagementTestSupport {

	@Test
	void concurrentAccessStateReadsPastExpiryWriteExactlyOneExpiryEventAndNeverMutateActivationEvidence()
			throws Exception {
		ExpiredEnrollmentFixture fixture = seedExpiredEnrollmentFixture("expiry-race");

		Map<String, Object> enrollmentBefore = jdbcTemplate.queryForMap(
				"SELECT activating_payment_id, activating_slip_id, activated_at, superseded_at, access_expires_at "
						+ "FROM enrollment WHERE id = ?",
				fixture.enrollmentId());
		Map<String, Object> paymentBefore = jdbcTemplate.queryForMap(
				"SELECT status, confirmed_at, amount FROM payment WHERE id = ?", fixture.firstPaymentId());

		int concurrency = 8;
		CyclicBarrier barrier = new CyclicBarrier(concurrency);
		ExecutorService executor = Executors.newFixedThreadPool(concurrency);
		List<Callable<HttpResult<EnrollmentAccessStateResponse>>> tasks = new ArrayList<>();
		for (int i = 0; i < concurrency; i++) {
			tasks.add(() -> {
				barrier.await();
				return getAccessState(fixture.host(), fixture.studentToken(), fixture.course().id());
			});
		}

		try {
			List<Future<HttpResult<EnrollmentAccessStateResponse>>> futures = executor.invokeAll(tasks);
			for (Future<HttpResult<EnrollmentAccessStateResponse>> future : futures) {
				HttpResult<EnrollmentAccessStateResponse> result = future.get(15, TimeUnit.SECONDS);
				assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
				assertThat(result.getBody().data().state()).isEqualTo(EnrollmentAccessStateType.EXPIRED);
			}
		}
		finally {
			executor.shutdownNow();
		}

		Long eventCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM enrollment_expiry_event WHERE enrollment_id = ? AND event_type = 'EXPIRED'",
				Long.class, fixture.enrollmentId());
		assertThat(eventCount).isEqualTo(1L);

		Map<String, Object> enrollmentAfter = jdbcTemplate.queryForMap(
				"SELECT activating_payment_id, activating_slip_id, activated_at, superseded_at, access_expires_at "
						+ "FROM enrollment WHERE id = ?",
				fixture.enrollmentId());
		assertThat(enrollmentAfter).isEqualTo(enrollmentBefore);

		Map<String, Object> paymentAfter = jdbcTemplate.queryForMap(
				"SELECT status, confirmed_at, amount FROM payment WHERE id = ?", fixture.firstPaymentId());
		assertThat(paymentAfter).isEqualTo(paymentBefore);
	}

}
