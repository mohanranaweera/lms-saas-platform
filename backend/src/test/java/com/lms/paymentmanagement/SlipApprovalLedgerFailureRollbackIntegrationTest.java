package com.lms.paymentmanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.lms.identityaccessservice.HttpResult;
import com.lms.ledgersettlementmanagement.api.LedgerEntryApi;
import com.lms.paymentmanagement.slip.web.dto.PaymentSlipResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Wave 6 §3.1 fix - transactional-atomicity coverage: {@link
 * com.lms.paymentmanagement.slip.service.SlipReviewService#approve} writes
 * {@code payment_slip.status}, a {@code Payment} row, a {@code ledger_entry}
 * row, and (via {@code EnrollmentActivationApi}) an {@code enrollment} row,
 * all in ONE {@code @Transactional} method. This test forces the ledger
 * write to throw, using a real Spring context (real transaction manager,
 * real Testcontainers Postgres) with only {@link LedgerEntryApi} replaced by
 * a throwing mock, and proves the ENTIRE transaction rolls back: the slip is
 * NOT left {@code APPROVED}, no {@code payment} row survives, no {@code
 * enrollment} row survives - mirrors {@code
 * SlipApprovalRollbackIntegrationTest}/{@code
 * ManualEnrollmentRollbackIntegrationTest}'s exact technique, applied to the
 * newly-added ledger-write step specifically.
 */
class SlipApprovalLedgerFailureRollbackIntegrationTest extends SlipTestSupport {

	@MockitoBean
	private LedgerEntryApi ledgerEntryApi;

	@Test
	void aFailureWritingTheLedgerEntryRollsBackTheEntireSlipApprovalTransaction() {
		doThrow(new RuntimeException("Simulated mid-transaction failure recording the ledger entry"))
			.when(ledgerEntryApi)
			.recordPaymentConfirmed(any(), any(), any());

		SlipFixture fixture = seedTenantWithOrder("slip-approve-ledger-fail");
		PaymentSlipResponse slip = uploadSlipOrFail(fixture.host(), fixture.studentToken(), fixture.order().id(),
				"REF-APPROVE-LEDGER-FAIL", pdfFile("slip.pdf"));

		HttpResult<PaymentSlipResponse> result = approveSlip(fixture.host(), fixture.financeToken(), slip.id(), null);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

		String status = jdbcTemplate.queryForObject("SELECT status FROM payment_slip WHERE id = ?", String.class,
				slip.id());
		assertThat(status).isEqualTo("UNDER_REVIEW");

		Long paymentCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM payment WHERE tenant_id = ? AND order_id = ?", Long.class,
				fixture.tenant().getId(), fixture.order().id());
		assertThat(paymentCount).isEqualTo(0L);

		Long enrollmentCount = jdbcTemplate.queryForObject("SELECT count(*) FROM enrollment WHERE tenant_id = ?",
				Long.class, fixture.tenant().getId());
		assertThat(enrollmentCount).isEqualTo(0L);

		Long auditCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM audit_log WHERE target_entity = 'payment_slip' AND target_id = ?", Long.class,
				slip.id());
		assertThat(auditCount).isEqualTo(0L);
	}

}
