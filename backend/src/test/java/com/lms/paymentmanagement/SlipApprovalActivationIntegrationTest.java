package com.lms.paymentmanagement;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.identityaccessservice.HttpResult;
import com.lms.paymentmanagement.slip.web.dto.PaymentSlipResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Testcontainers/HTTP coverage for SLIP-3's plain (no unresolved-flag)
 * approve/reject transitions, atomic enrollment activation, and sequential
 * double-approve idempotency (plan §18 items 6-7 & 9's "plain approve/reject
 * also writes a minimum audit row" requirement). The mid-transaction-failure
 * rollback variant lives in {@code SlipApprovalRollbackIntegrationTest}; the
 * genuinely-concurrent double-approve variant lives in {@code
 * SlipApprovalConcurrencyIntegrationTest}; the override-with-reason/audit-row
 * content tests live in {@code SlipOverrideAuditIntegrationTest}.
 */
class SlipApprovalActivationIntegrationTest extends SlipTestSupport {

	@Test
	void approvingACleanSlipActivatesEnrollmentInTheSameTransactionAndWritesAMinimumAuditRow() {
		SlipFixture fixture = seedTenantWithOrder("slip-approve-clean");
		PaymentSlipResponse slip = uploadSlipOrFail(fixture.host(), fixture.studentToken(), fixture.order().id(),
				"REF-APPROVE-CLEAN", pdfFile("slip.pdf"));
		assertThat(slip.status().name()).isEqualTo("UNDER_REVIEW");

		HttpResult<PaymentSlipResponse> result = approveSlip(fixture.host(), fixture.financeToken(), slip.id(),
				null);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(result.getBody().data().status().name()).isEqualTo("APPROVED");
		// Medium finding (item 5): reviewerEmail is null before review and
		// populated once an actual review decision has been made.
		assertThat(result.getBody().data().reviewerEmail()).isEqualTo("finance@example.test");
		assertThat(result.getBody().data().studentEmail()).isEqualTo("student@example.test");
		assertThat(result.getBody().data().orderAmount()).isNotNull();
		assertThat(result.getBody().data().orderCurrency()).isNotNull();

		String status = jdbcTemplate.queryForObject("SELECT status FROM payment_slip WHERE id = ?", String.class,
				slip.id());
		assertThat(status).isEqualTo("APPROVED");

		Long enrollmentCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM enrollment WHERE tenant_id = ? AND student_id = ? AND course_id = ? "
						+ "AND activating_slip_id = ? AND status = 'ACTIVE'",
				Long.class, fixture.tenant().getId(), fixture.student().getId(), fixture.course().id(), slip.id());
		assertThat(enrollmentCount).isEqualTo(1L);

		Long auditCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM audit_log WHERE target_entity = 'payment_slip' AND target_id = ? "
						+ "AND action = 'payment_slip.approved'",
				Long.class, slip.id());
		assertThat(auditCount).isEqualTo(1L);
		String reason = jdbcTemplate.queryForObject(
				"SELECT reason FROM audit_log WHERE target_entity = 'payment_slip' AND target_id = ? "
						+ "AND action = 'payment_slip.approved'",
				String.class, slip.id());
		assertThat(reason).isNull();
	}

	@Test
	void rejectingASlipLeavesEnrollmentInactiveAndWritesAMinimumAuditRow() {
		SlipFixture fixture = seedTenantWithOrder("slip-reject-clean");
		PaymentSlipResponse slip = uploadSlipOrFail(fixture.host(), fixture.studentToken(), fixture.order().id(),
				"REF-REJECT-CLEAN", pdfFile("slip.pdf"));

		HttpResult<PaymentSlipResponse> result = rejectSlip(fixture.host(), fixture.financeToken(), slip.id(),
				"Reference number does not match any bank statement entry");

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(result.getBody().data().status().name()).isEqualTo("REJECTED");

		Long enrollmentCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM enrollment WHERE tenant_id = ? AND student_id = ? AND course_id = ?",
				Long.class, fixture.tenant().getId(), fixture.student().getId(), fixture.course().id());
		assertThat(enrollmentCount).isEqualTo(0L);

		Long auditCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM audit_log WHERE target_entity = 'payment_slip' AND target_id = ? "
						+ "AND action = 'payment_slip.rejected'",
				Long.class, slip.id());
		assertThat(auditCount).isEqualTo(1L);
		String reason = jdbcTemplate.queryForObject(
				"SELECT reason FROM audit_log WHERE target_entity = 'payment_slip' AND target_id = ? "
						+ "AND action = 'payment_slip.rejected'",
				String.class, slip.id());
		assertThat(reason).isEqualTo("Reference number does not match any bank statement entry");
	}

	@Test
	void rejectingASlipWithABlankReasonIsRejectedWithZeroSideEffects() {
		SlipFixture fixture = seedTenantWithOrder("slip-reject-blank-reason");
		PaymentSlipResponse slip = uploadSlipOrFail(fixture.host(), fixture.studentToken(), fixture.order().id(),
				"REF-REJECT-BLANK", pdfFile("slip.pdf"));

		HttpResult<PaymentSlipResponse> result = rejectSlip(fixture.host(), fixture.financeToken(), slip.id(), "   ");

		assertThat(result.getStatusCode().is4xxClientError()).isTrue();
		String status = jdbcTemplate.queryForObject("SELECT status FROM payment_slip WHERE id = ?", String.class,
				slip.id());
		assertThat(status).isEqualTo("UNDER_REVIEW");
		Long auditCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM audit_log WHERE target_entity = 'payment_slip' AND target_id = ?", Long.class,
				slip.id());
		assertThat(auditCount).isEqualTo(0L);
	}

	/**
	 * Sequential double-approve: the second call against an already-{@code
	 * APPROVED} slip is an idempotent no-op success (plan §5 SLIP-3/§18 item
	 * 7 - "approving an already-APPROVED slip a second time is a no-op, no
	 * double activation, no double ledger write") - a 200 returning the
	 * current (already-approved) view, never a 409 and never a second
	 * enrollment/audit row. The genuinely-concurrent variant (proving the
	 * {@code PESSIMISTIC_WRITE} lock actually serializes two overlapping
	 * requests) lives in {@code SlipApprovalConcurrencyIntegrationTest}.
	 */
	@Test
	void approvingAnAlreadyApprovedSlipASecondTimeIsAnIdempotentNoOp() {
		SlipFixture fixture = seedTenantWithOrder("slip-approve-twice");
		PaymentSlipResponse slip = uploadSlipOrFail(fixture.host(), fixture.studentToken(), fixture.order().id(),
				"REF-APPROVE-TWICE", pdfFile("slip.pdf"));
		HttpResult<PaymentSlipResponse> first = approveSlip(fixture.host(), fixture.financeToken(), slip.id(), null);
		assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);

		HttpResult<PaymentSlipResponse> second = approveSlip(fixture.host(), fixture.financeToken(), slip.id(),
				null);

		assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(second.getBody().data().status().name()).isEqualTo("APPROVED");
		Long enrollmentCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM enrollment WHERE tenant_id = ? AND student_id = ? AND course_id = ?",
				Long.class, fixture.tenant().getId(), fixture.student().getId(), fixture.course().id());
		assertThat(enrollmentCount).isEqualTo(1L);
		Long auditCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM audit_log WHERE target_entity = 'payment_slip' AND target_id = ?", Long.class,
				slip.id());
		assertThat(auditCount).isEqualTo(1L);
	}

	@Test
	void approvingANonexistentSlipIdReturns404() {
		// SlipUploadService synchronously runs the duplicate checks and
		// auto-advances SUBMITTED -> UNDER_REVIEW within the same upload
		// request, so a genuinely-still-SUBMITTED slip is never externally
		// observable via HTTP - there is no production code path that leaves
		// a slip row visible in that intermediate state. A never-uploaded id
		// exercises the same "not a legally-approvable slip" guard via
		// SlipReviewService's NotFoundException instead.
		SlipFixture fixture = seedTenantWithOrder("slip-approve-nonexistent");
		UUID neverUploadedSlipId = UUID.randomUUID();

		HttpResult<PaymentSlipResponse> result = approveSlip(fixture.host(), fixture.financeToken(),
				neverUploadedSlipId, null);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

}
