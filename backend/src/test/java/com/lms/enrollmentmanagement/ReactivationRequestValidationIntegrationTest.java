package com.lms.enrollmentmanagement;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.enrollmentmanagement.web.dto.ReactivationApproveRequest;
import com.lms.enrollmentmanagement.web.dto.ReactivationRejectRequest;
import com.lms.enrollmentmanagement.web.dto.ReactivationRequestResponse;
import com.lms.identityaccessservice.HttpResult;
import org.junit.jupiter.api.Test;

/**
 * Closes plan §12/§18's DTO validation requirement for the reactivation
 * approve/reject endpoints (MVP-012 review finding H7) - {@link
 * ReactivationRejectRequest#reason} ({@code @NotBlank @Size(max = 1000)})
 * and {@link ReactivationApproveRequest#note} ({@code @Size(max = 1000)})
 * were never exercised end-to-end by any existing test. Mirrors {@code
 * SlipApprovalActivationIntegrationTest#rejectingASlipWithABlankReasonIsRejectedWithZeroSideEffects}'s
 * exact technique: a real HTTP request that fails {@code @Valid} bean
 * validation must be rejected {@code 4xx} BEFORE any state change - the
 * request must still be {@code SUBMITTED}, and zero audit rows may exist.
 */
class ReactivationRequestValidationIntegrationTest extends EnrollmentManagementTestSupport {

	@Test
	void rejectingWithABlankReasonIsRejectedWithZeroSideEffects() {
		ExpiredEnrollmentFixture fixture = seedExpiredEnrollmentFixture("react-validation-reject-blank");
		ReactivationRequestResponse request = submitReactivationRequestOrFail(fixture.host(),
				fixture.studentToken(), fixture.enrollmentId());

		HttpResult<ReactivationRequestResponse> result = rejectReactivation(fixture.host(), fixture.adminToken(),
				request.id(), "   ");

		assertThat(result.getStatusCode().is4xxClientError()).isTrue();
		assertRequestUntouched(request.id());
	}

	@Test
	void rejectingWithAReasonExceedingTheMaxLengthIsRejectedWithZeroSideEffects() {
		ExpiredEnrollmentFixture fixture = seedExpiredEnrollmentFixture("react-validation-reject-toolong");
		ReactivationRequestResponse request = submitReactivationRequestOrFail(fixture.host(),
				fixture.studentToken(), fixture.enrollmentId());
		String tooLongReason = "a".repeat(1001);

		HttpResult<ReactivationRequestResponse> result = rejectReactivation(fixture.host(), fixture.adminToken(),
				request.id(), tooLongReason);

		assertThat(result.getStatusCode().is4xxClientError()).isTrue();
		assertRequestUntouched(request.id());
	}

	@Test
	void approvingWithANoteExceedingTheMaxLengthIsRejectedWithZeroSideEffects() {
		ExpiredEnrollmentFixture fixture = seedExpiredEnrollmentFixture("react-validation-approve-toolong");
		ReactivationRequestResponse request = submitReactivationRequestOrFail(fixture.host(),
				fixture.studentToken(), fixture.enrollmentId());
		String tooLongNote = "a".repeat(1001);

		HttpResult<ReactivationRequestResponse> result = approveReactivation(fixture.host(), fixture.adminToken(),
				request.id(), tooLongNote);

		assertThat(result.getStatusCode().is4xxClientError()).isTrue();
		assertRequestUntouched(request.id());
	}

	private void assertRequestUntouched(java.util.UUID requestId) {
		String status = jdbcTemplate.queryForObject("SELECT status FROM reactivation_request WHERE id = ?",
				String.class, requestId);
		assertThat(status).isEqualTo("SUBMITTED");
		Long auditCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM audit_log WHERE target_entity = 'reactivation_request' AND target_id = ?",
				Long.class, requestId);
		assertThat(auditCount).isEqualTo(0L);
	}

}
