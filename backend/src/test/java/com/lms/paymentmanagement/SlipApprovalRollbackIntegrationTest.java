package com.lms.paymentmanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.lms.enrollmentmanagement.api.EnrollmentActivationApi;
import com.lms.identityaccessservice.HttpResult;
import com.lms.paymentmanagement.slip.web.dto.PaymentSlipResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Closes plan §18 Testcontainers item 6: {@link
 * com.lms.paymentmanagement.slip.service.SlipReviewService#approve} is one
 * {@code @Transactional} method that writes {@code payment_slip.status}, an
 * {@code enrollment} row (via {@link EnrollmentActivationApi}), and an {@code
 * audit_log} row. This test forces the enrollment-activation call to throw,
 * using a real Spring context (real transaction manager, real Testcontainers
 * Postgres) with only {@link EnrollmentActivationApi} replaced by a throwing
 * mock, and proves the entire transaction rolls back - mirroring {@code
 * PaymentConfirmationRollbackIntegrationTest}'s exact technique: no partial
 * state, the slip is not left {@code APPROVED}, and no {@code enrollment}/
 * {@code audit_log} row survives the rollback.
 */
class SlipApprovalRollbackIntegrationTest extends SlipTestSupport {

	@MockitoBean
	private EnrollmentActivationApi enrollmentActivationApi;

	@Autowired
	private com.lms.paymentmanagement.slip.repository.PaymentSlipRepository paymentSlipRepositoryForAssertions;

	@Test
	void aFailureInEnrollmentActivationRollsBackTheEntireSlipApprovalTransaction() {
		doThrow(new RuntimeException("Simulated mid-transaction failure in enrollment activation"))
			.when(enrollmentActivationApi)
			.activateFromApprovedSlip(any(), any(), any());
		SlipFixture fixture = seedTenantWithOrder("slip-approve-rollback");
		PaymentSlipResponse slip = uploadSlipOrFail(fixture.host(), fixture.studentToken(), fixture.order().id(),
				"REF-ROLLBACK", pdfFile("slip.pdf"));

		HttpResult<PaymentSlipResponse> result = approveSlip(fixture.host(), fixture.financeToken(), slip.id(),
				null);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

		String status = jdbcTemplate.queryForObject("SELECT status FROM payment_slip WHERE id = ?", String.class,
				slip.id());
		assertThat(status).isEqualTo("UNDER_REVIEW");

		Long enrollmentCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM enrollment WHERE tenant_id = ?", Long.class, fixture.tenant().getId());
		assertThat(enrollmentCount).isEqualTo(0L);

		Long auditCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM audit_log WHERE target_entity = 'payment_slip' AND target_id = ?", Long.class,
				slip.id());
		assertThat(auditCount).isEqualTo(0L);

		// Also confirm via the repository/entity layer, not just raw SQL.
		var reloaded = withTenant(fixture.tenant().getId(),
				() -> paymentSlipRepositoryForAssertions.findById(slip.id()));
		assertThat(reloaded).isPresent();
		assertThat(reloaded.get().getStatus().name()).isEqualTo("UNDER_REVIEW");
		assertThat(reloaded.get().getReviewerId()).isNull();
		assertThat(reloaded.get().getReviewedAt()).isNull();
	}

}
