package com.lms.paymentmanagement;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.identityaccessservice.HttpResult;
import com.lms.paymentmanagement.order.domain.StudentOrder;
import com.lms.paymentmanagement.order.repository.StudentOrderRepository;
import com.lms.paymentmanagement.slip.web.dto.PaymentSlipResponse;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

/**
 * The manual-slip counterpart of {@code
 * PaymentConfirmationReactivationRefusalIntegrationTest} (Bug-1 regression,
 * MVP-012 review): {@code SlipReviewService#approve} calling {@code
 * EnrollmentActivationApi#reactivateFromApprovedSlip} and having it refuse
 * (no APPROVED reactivation request at all for this order) must NOT roll
 * back this slip's own {@code APPROVED} transition or its audit log entry -
 * contrasted directly with {@link SlipApprovalRollbackIntegrationTest}, which
 * proves the OPPOSITE property for a genuinely unrelated (first-time
 * activation) failure.
 */
class SlipApprovalReactivationRefusalIntegrationTest extends SlipTestSupport {

	@Autowired
	private StudentOrderRepository studentOrderRepository;

	@Test
	void anApprovalWithNoMatchingReactivationRequestStillApprovesTheSlipAndWritesTheAuditEntry() {
		SlipFixture fixture = seedTenantWithOrder("slip-react-refusal");

		// First-time purchase: upload + approve the slip against the
		// fixture's own order, activating the enrollment normally.
		PaymentSlipResponse firstSlip = uploadSlipOrFail(fixture.host(), fixture.studentToken(),
				fixture.order().id(), "REF-FIRST", pdfFile("first.pdf"));
		HttpResult<PaymentSlipResponse> firstApproval = approveSlip(fixture.host(), fixture.financeToken(),
				firstSlip.id(), null);
		if (firstApproval.getStatusCode() != HttpStatus.OK) {
			throw new IllegalStateException("First-purchase slip approval failed: "
					+ firstApproval.getStatusCode());
		}

		int updated = jdbcTemplate.update(
				"UPDATE enrollment SET access_expires_at = now() - interval '1 day' "
						+ "WHERE tenant_id = ? AND student_id = ? AND course_id = ? AND superseded_at IS NULL",
				fixture.tenant().getId(), fixture.student().getId(), fixture.course().id());
		if (updated != 1) {
			throw new IllegalStateException("Expected to expire exactly one current enrollment row, updated "
					+ updated);
		}

		// A second order + slip, directly seeded (bypassing OrderService's
		// reactivation gate on purpose - this refusal path is structurally
		// unreachable through the normal API, but must still be safe if
		// ever reached, per EnrollmentActivationApi's own javadoc) with NO
		// reactivation_request linked to it at all.
		StudentOrder secondOrder = withTenant(fixture.tenant().getId(),
				() -> studentOrderRepository.save(new StudentOrder(fixture.tenant().getId(),
						fixture.student().getId(), fixture.course().id(), new BigDecimal("99.99"), "USD")));
		// distinctPdfBytes (not the fixed validPdfBytes() content the first
		// upload used) so this slip's image hash doesn't collide with the
		// first slip's and trigger a DUPLICATE_IMAGE_HASH flag, which would
		// require a non-null override reason to approve - orthogonal to
		// what this test is proving.
		PaymentSlipResponse secondSlip = uploadSlipOrFail(fixture.host(), fixture.studentToken(), secondOrder.getId(),
				"REF-SECOND", pdfFile("second.pdf", distinctPdfBytes("second")));

		HttpResult<PaymentSlipResponse> secondApproval = approveSlip(fixture.host(), fixture.financeToken(),
				secondSlip.id(), null);

		assertThat(secondApproval.getStatusCode()).isEqualTo(HttpStatus.OK);
		String status = jdbcTemplate.queryForObject("SELECT status FROM payment_slip WHERE id = ?", String.class,
				secondSlip.id());
		assertThat(status).isEqualTo("APPROVED");
		Long auditCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM audit_log WHERE target_entity = 'payment_slip' AND target_id = ?", Long.class,
				secondSlip.id());
		assertThat(auditCount).isEqualTo(1L);

		// The original enrollment must be untouched - still exactly one
		// current row, still pointing at the FIRST slip as its activation
		// evidence.
		Long enrollmentCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM enrollment WHERE tenant_id = ? AND student_id = ? AND course_id = ?",
				Long.class, fixture.tenant().getId(), fixture.student().getId(), fixture.course().id());
		assertThat(enrollmentCount).isEqualTo(1L);
		UUID activatingSlipId = jdbcTemplate.queryForObject(
				"SELECT activating_slip_id FROM enrollment WHERE tenant_id = ? AND student_id = ? "
						+ "AND course_id = ? AND superseded_at IS NULL",
				UUID.class, fixture.tenant().getId(), fixture.student().getId(), fixture.course().id());
		assertThat(activatingSlipId).isEqualTo(firstSlip.id());
	}

}
