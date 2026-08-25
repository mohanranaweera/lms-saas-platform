package com.lms.paymentmanagement;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.identityaccessservice.HttpResult;
import com.lms.paymentmanagement.slip.web.dto.PaymentSlipResponse;
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
 * Genuine concurrent-Testcontainers coverage for plan §18 Testcontainers item
 * 7's "mandatory idempotency test for approval... a genuinely concurrent
 * CyclicBarrier-based variant", explicitly requested per this module's task
 * because {@code RefundIdempotencyConcurrencyIntegrationTest}/{@code
 * PaymentWebhookConcurrencyIntegrationTest} exist specifically because a
 * sequential-only test previously missed a real lock-ordering bug in this
 * same module (MVP-010). {@link
 * com.lms.paymentmanagement.slip.repository.PaymentSlipRepository#findByIdAndTenantIdForUpdate}
 * ({@code PESSIMISTIC_WRITE}) is the mechanism under test here: two
 * genuinely concurrent approve requests for the SAME {@code UNDER_REVIEW}
 * slip must serialize on that lock, so exactly one request performs the real
 * transition (activates enrollment, writes one audit row) and the other -
 * observing the now-{@code APPROVED} slip after the lock releases - completes
 * as the idempotent no-op success plan §5/§18 item 7 specifies, never a
 * second activation/audit row, and never an unhandled exception/500 from a
 * lock-timeout or a raw constraint-violation leaking through.
 */
class SlipApprovalConcurrencyIntegrationTest extends SlipTestSupport {

	@Test
	void concurrentApproveRequestsForTheSameSlipProduceExactlyOneActivationAndOneAuditRow() throws Exception {
		SlipFixture fixture = seedTenantWithOrder("slip-approve-race");
		PaymentSlipResponse slip = uploadSlipOrFail(fixture.host(), fixture.studentToken(), fixture.order().id(),
				"REF-APPROVE-RACE", pdfFile("slip.pdf"));

		int concurrency = 2;
		CyclicBarrier barrier = new CyclicBarrier(concurrency);
		ExecutorService executor = Executors.newFixedThreadPool(concurrency);
		List<Callable<HttpResult<PaymentSlipResponse>>> tasks = new ArrayList<>();
		for (int i = 0; i < concurrency; i++) {
			tasks.add(() -> {
				barrier.await();
				return approveSlip(fixture.host(), fixture.financeToken(), slip.id(), null);
			});
		}

		List<HttpStatus> statuses = new ArrayList<>();
		try {
			List<Future<HttpResult<PaymentSlipResponse>>> futures = executor.invokeAll(tasks);
			for (Future<HttpResult<PaymentSlipResponse>> future : futures) {
				// Every delivery must complete cleanly - either the genuine
				// approval or the idempotent no-op replay, both HttpStatus.OK
				// (never an unhandled exception/500 from a lock-timeout or a
				// raw constraint-violation leaking through).
				HttpResult<PaymentSlipResponse> result = future.get(15, TimeUnit.SECONDS);
				statuses.add(result.getStatusCode());
			}
		}
		finally {
			executor.shutdownNow();
		}

		assertThat(statuses).containsExactly(HttpStatus.OK, HttpStatus.OK);

		Long enrollmentCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM enrollment WHERE tenant_id = ? AND student_id = ? AND course_id = ? "
						+ "AND activating_slip_id = ?",
				Long.class, fixture.tenant().getId(), fixture.student().getId(), fixture.course().id(), slip.id());
		assertThat(enrollmentCount).isEqualTo(1L);

		Long auditCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM audit_log WHERE target_entity = 'payment_slip' AND target_id = ? "
						+ "AND action = 'payment_slip.approved'",
				Long.class, slip.id());
		assertThat(auditCount).isEqualTo(1L);

		String finalStatus = jdbcTemplate.queryForObject("SELECT status FROM payment_slip WHERE id = ?",
				String.class, slip.id());
		assertThat(finalStatus).isEqualTo("APPROVED");
	}

}
