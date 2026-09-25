package com.lms.paymentmanagement;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.identityaccessservice.HttpResult;
import com.lms.paymentmanagement.slip.web.dto.PaymentSlipResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Wave 6 §3.1 fix coverage: {@link
 * com.lms.paymentmanagement.slip.service.SlipReviewService#approve} must
 * create exactly one {@code Payment} row (CONFIRMED, {@code
 * gatewayReference} prefixed {@code "SLIP-"}) and write exactly one {@code
 * PAYMENT_CONFIRMED} {@code ledger_entry} row per genuine approval, in the
 * same transaction as the slip's own {@code APPROVED} transition and the
 * enrollment activation it triggers (see
 * docs/parity/waves/wave-06-plan.md §1.2/§3.1). The forced-ledger-write-
 * failure rollback variant lives in {@code
 * SlipApprovalLedgerFailureRollbackIntegrationTest} (a separate top-level
 * class so its throwing {@link com.lms.ledgersettlementmanagement.api.LedgerEntryApi}
 * mock doesn't poison these happy-path tests' real ledger-write behavior -
 * mirrors {@code PaymentConfirmationRollbackIntegrationTest}'s own
 * separate-class convention).
 */
class SlipApprovalPaymentLedgerIntegrationTest extends SlipTestSupport {

	@Test
	void approvingACleanSlipCreatesExactlyOneConfirmedPaymentAndOneLedgerEntry() {
		SlipFixture fixture = seedTenantWithOrder("slip-approve-ledger");
		PaymentSlipResponse slip = uploadSlipOrFail(fixture.host(), fixture.studentToken(), fixture.order().id(),
				"REF-APPROVE-LEDGER", pdfFile("slip.pdf"));

		HttpResult<PaymentSlipResponse> result = approveSlip(fixture.host(), fixture.financeToken(), slip.id(), null);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);

		Long paymentCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM payment WHERE tenant_id = ? AND order_id = ? AND status = 'CONFIRMED'",
				Long.class, fixture.tenant().getId(), fixture.order().id());
		assertThat(paymentCount).isEqualTo(1L);

		String gatewayReference = jdbcTemplate.queryForObject(
				"SELECT gateway_reference FROM payment WHERE tenant_id = ? AND order_id = ? AND status = 'CONFIRMED'",
				String.class, fixture.tenant().getId(), fixture.order().id());
		assertThat(gatewayReference).startsWith("SLIP-");

		Long ledgerCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM ledger_entry WHERE tenant_id = ? AND order_id = ? AND entry_type = 'PAYMENT_CONFIRMED'",
				Long.class, fixture.tenant().getId(), fixture.order().id());
		assertThat(ledgerCount).isEqualTo(1L);
	}

	/**
	 * A repeat {@code approve()} call against an already-{@code APPROVED}
	 * slip (the existing idempotent-no-op path) must NOT create a second
	 * {@code Payment}/{@code ledger_entry} row.
	 */
	@Test
	void approvingAnAlreadyApprovedSlipASecondTimeCreatesNoAdditionalPaymentOrLedgerRow() {
		SlipFixture fixture = seedTenantWithOrder("slip-approve-ledger-twice");
		PaymentSlipResponse slip = uploadSlipOrFail(fixture.host(), fixture.studentToken(), fixture.order().id(),
				"REF-APPROVE-LEDGER-TWICE", pdfFile("slip.pdf"));
		HttpResult<PaymentSlipResponse> first = approveSlip(fixture.host(), fixture.financeToken(), slip.id(), null);
		assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

		HttpResult<PaymentSlipResponse> second = approveSlip(fixture.host(), fixture.financeToken(), slip.id(), null);

		assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
		Long paymentCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM payment WHERE tenant_id = ? AND order_id = ?", Long.class,
				fixture.tenant().getId(), fixture.order().id());
		assertThat(paymentCount).isEqualTo(1L);

		Long ledgerCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM ledger_entry WHERE tenant_id = ? AND order_id = ? AND entry_type = 'PAYMENT_CONFIRMED'",
				Long.class, fixture.tenant().getId(), fixture.order().id());
		assertThat(ledgerCount).isEqualTo(1L);
	}

}
