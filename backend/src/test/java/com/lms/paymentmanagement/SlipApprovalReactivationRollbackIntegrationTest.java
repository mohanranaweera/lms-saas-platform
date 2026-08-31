package com.lms.paymentmanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.lms.enrollmentmanagement.api.EnrollmentActivationApi;
import com.lms.enrollmentmanagement.web.dto.ReactivationRequestResponse;
import com.lms.identityaccessservice.HttpResult;
import com.lms.paymentmanagement.order.web.dto.OrderResponse;
import com.lms.paymentmanagement.slip.web.dto.PaymentSlipResponse;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The manual-slip counterpart of {@link PaymentConfirmationReactivationRollbackIntegrationTest}
 * (MVP-012 review finding H4): proves a genuinely UNEXPECTED exception during
 * the REACTIVATION slip-approval path (a plain {@link RuntimeException} -
 * never the EXPECTED {@link IllegalStateException} refusal already covered by
 * {@link SlipApprovalReactivationRefusalIntegrationTest}) rolls back the
 * whole {@code SlipReviewService#approve} transaction: the second slip stays
 * {@code UNDER_REVIEW}, no audit row survives, AND the prior, still-expired
 * {@code enrollment} row is left completely untouched (still current, {@code
 * superseded_at IS NULL}), with no new {@code enrollment} row ever inserted.
 *
 * <p>Unlike {@code SlipApprovalRollbackIntegrationTest}'s {@code
 * @MockitoBean} (a full replacement, appropriate there since that test's
 * single approval IS the failure), this uses {@link MockitoSpyBean} on
 * {@link EnrollmentActivationApi} - the fixture needs a REAL first-time
 * activation to happen first (upload+approve one slip, expire it, submit +
 * approve a reactivation request, upload a second slip), so only the
 * SUBSEQUENT reactivation approval is stubbed to fail; a blanket mock would
 * silently no-op that first activation too (Mockito's default answer for an
 * unstubbed void method is "do nothing"), leaving no enrollment row to
 * reactivate at all.
 *
 * <p>Extends {@link SlipTestSupport} (not {@code EnrollmentManagementTestSupport},
 * which has no slip-upload helpers) - the two small reactivation-request
 * HTTP helpers it needs are inlined locally below, mirroring {@code
 * EnrollmentManagementTestSupport#submitReactivationRequest}/{@code
 * #approveReactivation}'s exact request shape.
 */
class SlipApprovalReactivationRollbackIntegrationTest extends SlipTestSupport {

	@MockitoSpyBean
	private EnrollmentActivationApi enrollmentActivationApi;

	@Autowired
	private com.lms.paymentmanagement.slip.repository.PaymentSlipRepository paymentSlipRepositoryForAssertions;

	@Test
	void aGenuinelyUnexpectedFailureDuringReactivationSlipApprovalRollsBackTheWholeTransactionAndLeavesThePriorEnrollmentUntouched() {
		SlipFixture fixture = seedTenantWithOrder("slip-react-rollback");

		// First-time purchase: upload + approve the slip against the
		// fixture's own order, activating the enrollment normally.
		PaymentSlipResponse firstSlip = uploadSlipOrFail(fixture.host(), fixture.studentToken(),
				fixture.order().id(), "REF-FIRST", pdfFile("first.pdf"));
		HttpResult<PaymentSlipResponse> firstApproval = approveSlip(fixture.host(), fixture.financeToken(),
				firstSlip.id(), null);
		if (firstApproval.getStatusCode() != HttpStatus.OK) {
			throw new IllegalStateException("First-purchase slip approval failed: " + firstApproval.getStatusCode());
		}

		int updated = jdbcTemplate.update(
				"UPDATE enrollment SET access_expires_at = now() - interval '1 day' "
						+ "WHERE tenant_id = ? AND student_id = ? AND course_id = ? AND superseded_at IS NULL",
				fixture.tenant().getId(), fixture.student().getId(), fixture.course().id());
		if (updated != 1) {
			throw new IllegalStateException("Expected to expire exactly one current enrollment row, updated "
					+ updated);
		}
		UUID currentEnrollmentId = jdbcTemplate.queryForObject(
				"SELECT id FROM enrollment WHERE tenant_id = ? AND student_id = ? AND course_id = ? "
						+ "AND superseded_at IS NULL",
				UUID.class, fixture.tenant().getId(), fixture.student().getId(), fixture.course().id());

		ReactivationRequestResponse submitted = submitReactivationRequestOrFail(fixture.host(),
				fixture.studentToken(), currentEnrollmentId);
		HttpResult<ReactivationRequestResponse> approveResult = approveReactivation(fixture.host(),
				fixture.adminToken(), submitted.id());
		if (approveResult.getStatusCode() != HttpStatus.OK) {
			throw new IllegalStateException("Reactivation approval failed: " + approveResult.getStatusCode());
		}

		HttpResult<OrderResponse> orderResult = createOrder(fixture.host(), fixture.studentToken(),
				fixture.course().id());
		if (orderResult.getStatusCode() != HttpStatus.CREATED) {
			throw new IllegalStateException("Reactivation order creation failed: " + orderResult.getStatusCode());
		}
		OrderResponse secondOrder = orderResult.getBody().data();

		// distinctPdfBytes (not the fixed validPdfBytes() content the first
		// upload used) so this slip's image hash doesn't collide with the
		// first slip's and trigger a DUPLICATE_IMAGE_HASH flag - orthogonal
		// to what this test is proving.
		PaymentSlipResponse secondSlip = uploadSlipOrFail(fixture.host(), fixture.studentToken(), secondOrder.id(),
				"REF-SECOND", pdfFile("second.pdf", distinctPdfBytes("second")));

		// MVP-012 review finding M2: SlipReviewService calls the single
		// consolidated activateOrReactivateFromApprovedSlip method - stub
		// THAT method so this mock is actually reached on the reactivation
		// branch.
		doThrow(new RuntimeException("Simulated mid-transaction failure in enrollment reactivation"))
			.when(enrollmentActivationApi)
			.activateOrReactivateFromApprovedSlip(any(), any(), any(), any());

		HttpResult<PaymentSlipResponse> secondApproval = approveSlip(fixture.host(), fixture.financeToken(),
				secondSlip.id(), null);

		assertThat(secondApproval.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

		String secondSlipStatus = jdbcTemplate.queryForObject("SELECT status FROM payment_slip WHERE id = ?",
				String.class, secondSlip.id());
		assertThat(secondSlipStatus).isEqualTo("UNDER_REVIEW");

		Long auditCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM audit_log WHERE target_entity = 'payment_slip' AND target_id = ?", Long.class,
				secondSlip.id());
		assertThat(auditCount).isEqualTo(0L);

		// The prior (expired) enrollment row must be COMPLETELY untouched.
		Long enrollmentCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM enrollment WHERE tenant_id = ? AND student_id = ? AND course_id = ?",
				Long.class, fixture.tenant().getId(), fixture.student().getId(), fixture.course().id());
		assertThat(enrollmentCount).isEqualTo(1L);

		Instant supersededAt = jdbcTemplate.queryForObject("SELECT superseded_at FROM enrollment WHERE id = ?",
				Instant.class, currentEnrollmentId);
		assertThat(supersededAt).isNull();

		// Also confirm via the repository/entity layer, not just raw SQL.
		var reloaded = withTenant(fixture.tenant().getId(),
				() -> paymentSlipRepositoryForAssertions.findById(secondSlip.id()));
		assertThat(reloaded).isPresent();
		assertThat(reloaded.get().getStatus().name()).isEqualTo("UNDER_REVIEW");
	}

	// ------------------------------------------------------------------
	// Small local reactivation-request HTTP helpers - SlipTestSupport does
	// not extend EnrollmentManagementTestSupport (it needs multipart/slip
	// helpers PaymentManagementTestSupport's other subclass doesn't have),
	// so these mirror EnrollmentManagementTestSupport's identical helpers.
	// ------------------------------------------------------------------

	private ReactivationRequestResponse submitReactivationRequestOrFail(String host, String token,
			UUID enrollmentId) {
		HttpResult<ReactivationRequestResponse> result = submitReactivationRequest(host, token, enrollmentId);
		if (result.getStatusCode() != HttpStatus.CREATED) {
			throw new IllegalStateException(
					"Reactivation request submission failed: " + result.getStatusCode() + " " + result.getBody());
		}
		return result.getBody().data();
	}

	private HttpResult<ReactivationRequestResponse> submitReactivationRequest(String host, String token,
			UUID enrollmentId) {
		MockHttpServletRequestBuilder builder = post("/api/v1/enrollments/{enrollmentId}/reactivation-requests",
				enrollmentId);
		return parseSingle(perform(authenticated(builder, host, token)), ReactivationRequestResponse.class);
	}

	private HttpResult<ReactivationRequestResponse> approveReactivation(String host, String token, UUID id) {
		MockHttpServletRequestBuilder builder = post("/api/v1/reactivation-requests/{id}/approve", id)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{}");
		return parseSingle(perform(authenticated(builder, host, token)), ReactivationRequestResponse.class);
	}

}
