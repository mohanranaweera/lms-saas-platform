package com.lms.enrollmentmanagement;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.common.api.PageResponse;
import com.lms.enrollmentmanagement.web.dto.ReactivationRequestResponse;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.Role;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Closes plan §2/§18's authorization-matrix requirement for the reactivation
 * queue/approve/reject surface, mirroring {@code
 * SlipCrossTenantIntegrationTest}'s role-boundary tests
 * ({@code studentSupportAndReadOnlyAuditorAreDeniedOnApproveAndReject},
 * {@code studentSupportCanReadTheReviewQueueButNeverMutate},
 * {@code aStudentCallingApproveRejectOrTheReviewQueueDirectlyIsDenied})
 * exactly, extended to every {@code ACCESS_EXPIRY}/{@code VIEW}-only role
 * named in the plan (Finance Staff, Student Support, Read-only Auditor), a
 * role with NO {@code ACCESS_EXPIRY} grant at all (Course Coordinator - see
 * {@code PermissionCheckServiceImpl#buildMatrix}), and the one role that
 * genuinely CAN approve/reject (Tenant Admin, the only role holding {@code
 * ACCESS_EXPIRY}/{@code APPROVE} per plan §2).
 */
class ReactivationAuthorizationIntegrationTest extends EnrollmentManagementTestSupport {

	@Test
	void financeStaffCanViewTheQueueButGets403OnApproveAndReject() {
		assertViewOnlyRoleCanReadQueueButIsDeniedMutation("access-authz-finance", Role.FINANCE_STAFF);
	}

	@Test
	void studentSupportCanViewTheQueueButGets403OnApproveAndReject() {
		assertViewOnlyRoleCanReadQueueButIsDeniedMutation("access-authz-support", Role.STUDENT_SUPPORT);
	}

	@Test
	void readOnlyAuditorCanViewTheQueueButGets403OnApproveAndReject() {
		assertViewOnlyRoleCanReadQueueButIsDeniedMutation("access-authz-auditor", Role.READ_ONLY_AUDITOR);
	}

	/**
	 * Course Coordinator holds no {@code ACCESS_EXPIRY} grant at all (unlike
	 * Finance Staff/Student Support/Read-only Auditor, who at least hold
	 * {@code VIEW}) - the queue read itself must be denied too, not just
	 * approve/reject.
	 */
	@Test
	void courseCoordinatorWithNoAccessExpiryGrantAtAllGets403OnTheQueueToo() {
		ExpiredEnrollmentFixture fixture = seedExpiredEnrollmentFixture("access-authz-coordinator");
		seedTenantUser(fixture.tenant().getId(), "coordinator@example.test", RAW_PASSWORD, Role.COURSE_COORDINATOR);
		String coordinatorToken = loginAndGetToken(fixture.host(), "coordinator@example.test");
		ReactivationRequestResponse request = submitReactivationRequestOrFail(fixture.host(),
				fixture.studentToken(), fixture.enrollmentId());

		assertThat(getReactivationQueue(fixture.host(), coordinatorToken, null).getStatusCode())
			.isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(approveReactivation(fixture.host(), coordinatorToken, request.id(), null).getStatusCode())
			.isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(rejectReactivation(fixture.host(), coordinatorToken, request.id(), "reason").getStatusCode())
			.isEqualTo(HttpStatus.FORBIDDEN);

		String status = jdbcTemplate.queryForObject("SELECT status FROM reactivation_request WHERE id = ?",
				String.class, request.id());
		assertThat(status).isEqualTo("SUBMITTED");
	}

	@Test
	void aStudentCallerGets403OnTheQueueAndOnApproveAndReject() {
		ExpiredEnrollmentFixture fixture = seedExpiredEnrollmentFixture("access-authz-student");
		ReactivationRequestResponse request = submitReactivationRequestOrFail(fixture.host(),
				fixture.studentToken(), fixture.enrollmentId());

		assertThat(getReactivationQueue(fixture.host(), fixture.studentToken(), null).getStatusCode())
			.isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(approveReactivation(fixture.host(), fixture.studentToken(), request.id(), null).getStatusCode())
			.isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(rejectReactivation(fixture.host(), fixture.studentToken(), request.id(), "reason").getStatusCode())
			.isEqualTo(HttpStatus.FORBIDDEN);

		String status = jdbcTemplate.queryForObject("SELECT status FROM reactivation_request WHERE id = ?",
				String.class, request.id());
		assertThat(status).isEqualTo("SUBMITTED");
	}

	@Test
	void tenantAdminTheOnlyApproveGrantedRoleCanApproveARequest() {
		ExpiredEnrollmentFixture fixture = seedExpiredEnrollmentFixture("access-authz-admin-approve");
		ReactivationRequestResponse request = submitReactivationRequestOrFail(fixture.host(),
				fixture.studentToken(), fixture.enrollmentId());

		HttpResult<ReactivationRequestResponse> result = approveReactivation(fixture.host(), fixture.adminToken(),
				request.id(), "Looks legitimate");

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(result.getBody().data().status().name()).isEqualTo("APPROVED");
		Long auditCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM audit_log WHERE target_entity = 'reactivation_request' AND target_id = ? "
						+ "AND action = 'reactivation_request.approved'",
				Long.class, request.id());
		assertThat(auditCount).isEqualTo(1L);
	}

	@Test
	void tenantAdminTheOnlyApproveGrantedRoleCanRejectARequest() {
		ExpiredEnrollmentFixture fixture = seedExpiredEnrollmentFixture("access-authz-admin-reject");
		ReactivationRequestResponse request = submitReactivationRequestOrFail(fixture.host(),
				fixture.studentToken(), fixture.enrollmentId());

		HttpResult<ReactivationRequestResponse> result = rejectReactivation(fixture.host(), fixture.adminToken(),
				request.id(), "Reference could not be verified");

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(result.getBody().data().status().name()).isEqualTo("REJECTED");
		Long auditCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM audit_log WHERE target_entity = 'reactivation_request' AND target_id = ? "
						+ "AND action = 'reactivation_request.rejected'",
				Long.class, request.id());
		assertThat(auditCount).isEqualTo(1L);
	}

	// ------------------------------------------------------------------

	private void assertViewOnlyRoleCanReadQueueButIsDeniedMutation(String prefix, Role role) {
		ExpiredEnrollmentFixture fixture = seedExpiredEnrollmentFixture(prefix);
		seedTenantUser(fixture.tenant().getId(), "viewer@example.test", RAW_PASSWORD, role);
		String viewerToken = loginAndGetToken(fixture.host(), "viewer@example.test");
		ReactivationRequestResponse request = submitReactivationRequestOrFail(fixture.host(),
				fixture.studentToken(), fixture.enrollmentId());

		HttpResult<PageResponse<ReactivationRequestResponse>> queue = getReactivationQueue(fixture.host(),
				viewerToken, null);
		assertThat(queue.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(queue.getBody().data().content()).extracting(ReactivationRequestResponse::id)
			.contains(request.id());

		assertThat(approveReactivation(fixture.host(), viewerToken, request.id(), null).getStatusCode())
			.isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(rejectReactivation(fixture.host(), viewerToken, request.id(), "reason").getStatusCode())
			.isEqualTo(HttpStatus.FORBIDDEN);

		String status = jdbcTemplate.queryForObject("SELECT status FROM reactivation_request WHERE id = ?",
				String.class, request.id());
		assertThat(status).isEqualTo("SUBMITTED");
	}

}
