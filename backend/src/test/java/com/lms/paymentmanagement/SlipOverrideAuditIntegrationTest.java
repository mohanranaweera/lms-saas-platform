package com.lms.paymentmanagement;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.identityaccessservice.HttpResult;
import com.lms.paymentmanagement.slip.web.dto.PaymentSlipResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Testcontainers/HTTP coverage for SLIP-4's override-with-reason gate and its
 * mandatory audit row (plan §18 items 8-9; {@code .claude/rules/payments.md}
 * §3's "non-negotiable" override-audit requirement). Every override scenario
 * here is built on a genuine {@code DUPLICATE_REFERENCE} flag produced by a
 * real upload (via {@code SlipDuplicateDetectionIntegrationTest}'s same
 * technique), never a synthetically-inserted flag row, so the whole
 * upload-flag-review path is exercised end-to-end.
 */
class SlipOverrideAuditIntegrationTest extends SlipTestSupport {

	@Test
	void approvingAFlaggedSlipWithNoOverrideReasonIsRejectedBeforeAnyStateChangeOrAuditWrite() {
		FlaggedSlip flagged = seedFlaggedSlip("slip-override-no-reason");

		HttpResult<PaymentSlipResponse> nullReasonResult = approveSlip(flagged.fixture().host(),
				flagged.fixture().financeToken(), flagged.slip().id(), null);
		assertThat(nullReasonResult.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

		HttpResult<PaymentSlipResponse> blankReasonResult = approveSlip(flagged.fixture().host(),
				flagged.fixture().financeToken(), flagged.slip().id(), "   ");
		assertThat(blankReasonResult.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

		String status = jdbcTemplate.queryForObject("SELECT status FROM payment_slip WHERE id = ?", String.class,
				flagged.slip().id());
		assertThat(status).isEqualTo("UNDER_REVIEW");

		Long auditCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM audit_log WHERE target_entity = 'payment_slip' AND target_id = ?", Long.class,
				flagged.slip().id());
		assertThat(auditCount).isEqualTo(0L);

		// No enrollment for this student anywhere in the tenant - not just for
		// the flagged slip's own course (activating_slip_id would be the more
		// precise filter, but the slip was never approved, so it was never
		// even a candidate activating id).
		Long enrollmentCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM enrollment WHERE tenant_id = ? AND student_id = ?", Long.class,
				flagged.fixture().tenant().getId(), flagged.fixture().student().getId());
		assertThat(enrollmentCount).isEqualTo(0L);
	}

	@Test
	void aValidOverrideWritesExactlyOneAuditRowWithAllRequiredFieldsPopulated() {
		FlaggedSlip flagged = seedFlaggedSlip("slip-override-valid");
		String overrideReason = "Verified manually against the bank statement - reference number legitimately reused";

		HttpResult<PaymentSlipResponse> result = approveSlip(flagged.fixture().host(),
				flagged.fixture().financeToken(), flagged.slip().id(), overrideReason);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(result.getBody().data().status().name()).isEqualTo("APPROVED");

		Long auditCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM audit_log WHERE target_entity = 'payment_slip' AND target_id = ?", Long.class,
				flagged.slip().id());
		assertThat(auditCount).isEqualTo(1L);

		Map<String, Object> row = jdbcTemplate.queryForMap(
				"SELECT actor_id, tenant_id, target_entity, target_id, action, reason, occurred_at, metadata::text AS metadata "
						+ "FROM audit_log WHERE target_entity = 'payment_slip' AND target_id = ?",
				flagged.slip().id());
		assertThat(row.get("actor_id")).isNotNull();
		assertThat(row.get("tenant_id")).isEqualTo(flagged.fixture().tenant().getId());
		assertThat(row.get("target_entity")).isEqualTo("payment_slip");
		assertThat(row.get("target_id").toString()).isEqualTo(flagged.slip().id().toString());
		assertThat(row.get("action")).isEqualTo("payment_slip.approved_with_override");
		assertThat(row.get("reason")).isEqualTo(overrideReason);
		assertThat(row.get("occurred_at")).isNotNull();
		assertThat((String) row.get("metadata")).contains("DUPLICATE_REFERENCE");

		// The override write and the audit write are the same transaction:
		// enrollment must also be active.
		Long enrollmentCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM enrollment WHERE tenant_id = ? AND activating_slip_id = ? AND status = 'ACTIVE'",
				Long.class, flagged.fixture().tenant().getId(), flagged.slip().id());
		assertThat(enrollmentCount).isEqualTo(1L);
	}

	/**
	 * A slip carrying exactly one {@code DUPLICATE_REFERENCE} flag, still
	 * {@code UNDER_REVIEW}. Deliberately distinct image bytes per upload (see
	 * {@code SlipTestSupport#distinctPdfBytes}) - isolates the
	 * reference-number duplicate check from the image-hash duplicate check,
	 * so the flag count below is exactly 1, not 2.
	 */
	/**
	 * CRITICAL regression coverage (ordering bug fix): {@code
	 * payment_slip_flag} rows are append-only and never cleared, so a slip
	 * originally approved via override permanently carries surviving flags. A
	 * legitimate no-reason retry against that already-{@code APPROVED} slip
	 * must still succeed as an idempotent no-op ({@code 200 OK} with the
	 * already-approved view), not incorrectly hit the reasonless-override
	 * guard and return {@code 409}.
	 */
	@Test
	void reapprovingAnAlreadyOverrideApprovedSlipWithNoOverrideReasonIsStillAnIdempotentNoOp() {
		FlaggedSlip flagged = seedFlaggedSlip("slip-override-reapprove");
		String overrideReason = "Verified manually against the bank statement - reference number legitimately reused";
		HttpResult<PaymentSlipResponse> firstApproval = approveSlip(flagged.fixture().host(),
				flagged.fixture().financeToken(), flagged.slip().id(), overrideReason);
		assertThat(firstApproval.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(firstApproval.getBody().data().status().name()).isEqualTo("APPROVED");

		HttpResult<PaymentSlipResponse> secondApproval = approveSlip(flagged.fixture().host(),
				flagged.fixture().financeToken(), flagged.slip().id(), null);

		assertThat(secondApproval.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(secondApproval.getBody().data().status().name()).isEqualTo("APPROVED");

		Long auditCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM audit_log WHERE target_entity = 'payment_slip' AND target_id = ?", Long.class,
				flagged.slip().id());
		assertThat(auditCount).isEqualTo(1L);
		Long enrollmentCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM enrollment WHERE tenant_id = ? AND activating_slip_id = ? AND status = 'ACTIVE'",
				Long.class, flagged.fixture().tenant().getId(), flagged.slip().id());
		assertThat(enrollmentCount).isEqualTo(1L);
	}

	private FlaggedSlip seedFlaggedSlip(String prefix) {
		SlipFixture fixture = seedTenantWithOrder(prefix);
		var secondOrder = createAnotherOrder(fixture, prefix + "-2");
		uploadSlipOrFail(fixture.host(), fixture.studentToken(), fixture.order().id(), "OVERRIDE-SHARED-REF",
				pdfFile("slip-1.pdf", distinctPdfBytes(prefix + "-first")));
		PaymentSlipResponse flaggedSlip = uploadSlipOrFail(fixture.host(), fixture.studentToken(), secondOrder.id(),
				"OVERRIDE-SHARED-REF", pdfFile("slip-2.pdf", distinctPdfBytes(prefix + "-second")));
		assertThat(flaggedSlip.flags()).hasSize(1);
		return new FlaggedSlip(fixture, flaggedSlip);
	}

	private record FlaggedSlip(SlipFixture fixture, PaymentSlipResponse slip) {
	}

}
