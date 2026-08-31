package com.lms.enrollmentmanagement;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.common.api.PageResponse;
import com.lms.enrollmentmanagement.api.EnrollmentAccessStateType;
import com.lms.enrollmentmanagement.domain.ReactivationRequestStatus;
import com.lms.enrollmentmanagement.web.dto.EnrollmentAccessStateResponse;
import com.lms.enrollmentmanagement.web.dto.EnrollmentSummaryResponse;
import com.lms.enrollmentmanagement.web.dto.ReactivationRequestResponse;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.Role;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Closes plan §14/§18's "mandatory cross-tenant negative tests" list for
 * every MVP-012 surface, mirroring {@code SlipCrossTenantIntegrationTest}'s
 * exact structure and assertion style: every cross-tenant read proves 404
 * (never 200 with tenant A's data, never a leaking 403), and every rejected
 * cross-tenant mutation attempt proves zero side effects (no row created, no
 * status change, no audit log entry).
 *
 * <h2>{@code GET /courses/{courseId}/access-state} - why this proves 200/NEVER_ENROLLED, not 404</h2>
 * Per {@code EnrollmentQueryService}'s own javadoc, this endpoint has no
 * {@code studentId} parameter and always resolves the CALLING student's own
 * state for the given {@code courseId} - it never looks at, or reveals
 * anything about, whether that {@code courseId} exists in another tenant.
 * Because {@code enrollment.course_id} is composite-FK'd to
 * {@code course(tenant_id, id)} (V19), a Tenant-B student can never have an
 * {@code enrollment} row whose {@code course_id} equals a Tenant-A course's
 * id - so the tenant-scoped lookup this endpoint performs is always empty for
 * a cross-tenant id, and the endpoint correctly, safely reports
 * {@code NEVER_ENROLLED} (identical to what it would report for any random,
 * wholly nonexistent {@code courseId}). This is not a 404 by design (see the
 * plan's own error table, §13, for the "owner-or-staff" shape this endpoint
 * was NOT ultimately built with), but it is a genuine non-leak: the response
 * carries zero information distinguishing "this course belongs to another
 * tenant" from "this course id does not exist at all" from "I really was
 * never enrolled in my own tenant's course of this id" - proven below.
 */
class EnrollmentCrossTenantIntegrationTest extends EnrollmentManagementTestSupport {

	@Test
	void accessStateCrossTenantCourseIdNeverLeaksAnotherTenantsDataAndReportsNeverEnrolled() {
		ExpiredEnrollmentFixture tenantA = seedExpiredEnrollmentFixture("enr-xt-state-a");
		ExpiredEnrollmentFixture tenantB = seedExpiredEnrollmentFixture("enr-xt-state-b");

		HttpResult<EnrollmentAccessStateResponse> result = getAccessState(tenantB.host(), tenantB.studentToken(),
				tenantA.course().id());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		EnrollmentAccessStateResponse body = result.getBody().data();
		assertThat(body.state()).isEqualTo(EnrollmentAccessStateType.NEVER_ENROLLED);
		assertThat(body.enrollmentId()).isNull();
		assertThat(body.accessExpiresAt()).isNull();
		assertThat(body.canRequestReactivation()).isFalse();

		// Sanity: tenant B's own courseId DOES report its (expired) real state,
		// proving the NEVER_ENROLLED above is tenant isolation, not a broken endpoint.
		HttpResult<EnrollmentAccessStateResponse> ownResult = getAccessState(tenantB.host(), tenantB.studentToken(),
				tenantB.course().id());
		assertThat(ownResult.getBody().data().state()).isEqualTo(EnrollmentAccessStateType.EXPIRED);
	}

	@Test
	void myEnrollmentsNeverContainsAnotherTenantsRowsEvenForASimilarlyNamedCourse() {
		ExpiredEnrollmentFixture tenantA = seedExpiredEnrollmentFixture("enr-xt-my-a");
		ExpiredEnrollmentFixture tenantB = seedExpiredEnrollmentFixture("enr-xt-my-b");

		HttpResult<List<EnrollmentSummaryResponse>> resultB = getMyEnrollments(tenantB.host(),
				tenantB.studentToken());

		assertThat(resultB.getStatusCode()).isEqualTo(HttpStatus.OK);
		List<EnrollmentSummaryResponse> rowsB = resultB.getBody().data();
		assertThat(rowsB).extracting(EnrollmentSummaryResponse::enrollmentId).containsExactly(tenantB.enrollmentId())
			.doesNotContain(tenantA.enrollmentId());
		assertThat(rowsB).extracting(EnrollmentSummaryResponse::courseId).containsExactly(tenantB.course().id())
			.doesNotContain(tenantA.course().id());

		// Sanity: tenant A's own list DOES see its own row.
		HttpResult<List<EnrollmentSummaryResponse>> resultA = getMyEnrollments(tenantA.host(),
				tenantA.studentToken());
		assertThat(resultA.getBody().data()).extracting(EnrollmentSummaryResponse::enrollmentId)
			.containsExactly(tenantA.enrollmentId());
	}

	@Test
	void submitReactivationRequestAgainstAnotherTenantsEnrollmentIdReturns404AndCreatesNoRows() {
		ExpiredEnrollmentFixture tenantA = seedExpiredEnrollmentFixture("enr-xt-submit-a");
		ExpiredEnrollmentFixture tenantB = seedExpiredEnrollmentFixture("enr-xt-submit-b");

		HttpResult<ReactivationRequestResponse> result = submitReactivationRequest(tenantB.host(),
				tenantB.studentToken(), tenantA.enrollmentId());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		Long count = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM reactivation_request WHERE enrollment_id = ?", Long.class,
				tenantA.enrollmentId());
		assertThat(count).isEqualTo(0L);
	}

	@Test
	void getDetailCrossTenantReturns404ForBothAStudentAndAFinanceStaffCaller() {
		ExpiredEnrollmentFixture tenantA = seedExpiredEnrollmentFixture("enr-xt-detail-a");
		ExpiredEnrollmentFixture tenantB = seedExpiredEnrollmentFixture("enr-xt-detail-b");
		seedTenantUser(tenantB.tenant().getId(), "finance@example.test", RAW_PASSWORD, Role.FINANCE_STAFF);
		String financeTokenB = loginAndGetToken(tenantB.host(), "finance@example.test");
		ReactivationRequestResponse requestA = submitReactivationRequestOrFail(tenantA.host(),
				tenantA.studentToken(), tenantA.enrollmentId());

		HttpResult<ReactivationRequestResponse> studentResult = getReactivationRequestDetail(tenantB.host(),
				tenantB.studentToken(), requestA.id());
		assertThat(studentResult.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

		// Closes the M5-equivalent gap for this domain: a staff caller takes a
		// DIFFERENT code path through ReactivationAccessGuard#requireOwnerOrStaffView
		// (the permission-check branch) than a Student caller does (the
		// ownership branch) - both must independently be proven safe against a
		// cross-tenant id.
		HttpResult<ReactivationRequestResponse> staffResult = getReactivationRequestDetail(tenantB.host(),
				financeTokenB, requestA.id());
		assertThat(staffResult.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void reviewQueueNeverLeaksAnotherTenantsRequests() {
		ExpiredEnrollmentFixture tenantA = seedExpiredEnrollmentFixture("enr-xt-queue-a");
		ExpiredEnrollmentFixture tenantB = seedExpiredEnrollmentFixture("enr-xt-queue-b");
		seedTenantUser(tenantA.tenant().getId(), "finance@example.test", RAW_PASSWORD, Role.FINANCE_STAFF);
		seedTenantUser(tenantB.tenant().getId(), "finance@example.test", RAW_PASSWORD, Role.FINANCE_STAFF);
		String financeTokenA = loginAndGetToken(tenantA.host(), "finance@example.test");
		String financeTokenB = loginAndGetToken(tenantB.host(), "finance@example.test");
		submitReactivationRequestOrFail(tenantA.host(), tenantA.studentToken(), tenantA.enrollmentId());

		HttpResult<PageResponse<ReactivationRequestResponse>> queueB = getReactivationQueue(tenantB.host(),
				financeTokenB, null);

		assertThat(queueB.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(queueB.getBody().data().content()).isEmpty();

		// Sanity: tenant A's own queue DOES see its own request, proving the
		// emptiness above is tenant isolation, not a broken queue.
		HttpResult<PageResponse<ReactivationRequestResponse>> queueA = getReactivationQueue(tenantA.host(),
				financeTokenA, null);
		assertThat(queueA.getBody().data().content()).isNotEmpty();
	}

	/**
	 * The status-supplied branch of {@code
	 * ReactivationRequestRepository#findReviewQueue} is a materially different
	 * query ({@code cb.equal(status)} instead of the default-{@code SUBMITTED}
	 * predicate) - mirrors {@code
	 * SlipCrossTenantIntegrationTest#reviewQueueWithAnExplicitStatusFilterNeverLeaksAnotherTenantsSlips}'s
	 * own rationale for why it needs its own isolation proof, separate from
	 * the default-branch test above.
	 */
	@Test
	void reviewQueueWithAnExplicitStatusFilterNeverLeaksAnotherTenantsRequests() {
		ExpiredEnrollmentFixture tenantA = seedExpiredEnrollmentFixture("enr-xt-queue-status-a");
		ExpiredEnrollmentFixture tenantB = seedExpiredEnrollmentFixture("enr-xt-queue-status-b");
		seedTenantUser(tenantA.tenant().getId(), "finance@example.test", RAW_PASSWORD, Role.FINANCE_STAFF);
		seedTenantUser(tenantB.tenant().getId(), "finance@example.test", RAW_PASSWORD, Role.FINANCE_STAFF);
		String financeTokenA = loginAndGetToken(tenantA.host(), "finance@example.test");
		String financeTokenB = loginAndGetToken(tenantB.host(), "finance@example.test");
		submitReactivationRequestOrFail(tenantA.host(), tenantA.studentToken(), tenantA.enrollmentId());

		HttpResult<PageResponse<ReactivationRequestResponse>> queueB = getReactivationQueue(tenantB.host(),
				financeTokenB, ReactivationRequestStatus.SUBMITTED);

		assertThat(queueB.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(queueB.getBody().data().content()).isEmpty();

		// Sanity: tenant A's own status-filtered queue DOES see its own
		// SUBMITTED request.
		HttpResult<PageResponse<ReactivationRequestResponse>> queueA = getReactivationQueue(tenantA.host(),
				financeTokenA, ReactivationRequestStatus.SUBMITTED);
		assertThat(queueA.getBody().data().content()).isNotEmpty();
	}

	@Test
	void approveCrossTenantReturns404WithZeroSideEffects() {
		ExpiredEnrollmentFixture tenantA = seedExpiredEnrollmentFixture("enr-xt-approve-a");
		ExpiredEnrollmentFixture tenantB = seedExpiredEnrollmentFixture("enr-xt-approve-b");
		ReactivationRequestResponse requestA = submitReactivationRequestOrFail(tenantA.host(),
				tenantA.studentToken(), tenantA.enrollmentId());

		HttpResult<ReactivationRequestResponse> result = approveReactivation(tenantB.host(), tenantB.adminToken(),
				requestA.id(), null);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		String status = jdbcTemplate.queryForObject("SELECT status FROM reactivation_request WHERE id = ?",
				String.class, requestA.id());
		assertThat(status).isEqualTo("SUBMITTED");
		Long auditCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM audit_log WHERE target_entity = 'reactivation_request' AND target_id = ?",
				Long.class, requestA.id());
		assertThat(auditCount).isEqualTo(0L);
	}

	@Test
	void rejectCrossTenantReturns404WithZeroSideEffects() {
		ExpiredEnrollmentFixture tenantA = seedExpiredEnrollmentFixture("enr-xt-reject-a");
		ExpiredEnrollmentFixture tenantB = seedExpiredEnrollmentFixture("enr-xt-reject-b");
		ReactivationRequestResponse requestA = submitReactivationRequestOrFail(tenantA.host(),
				tenantA.studentToken(), tenantA.enrollmentId());

		HttpResult<ReactivationRequestResponse> result = rejectReactivation(tenantB.host(), tenantB.adminToken(),
				requestA.id(), "Attempted cross-tenant rejection");

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		String status = jdbcTemplate.queryForObject("SELECT status FROM reactivation_request WHERE id = ?",
				String.class, requestA.id());
		assertThat(status).isEqualTo("SUBMITTED");
		Long auditCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM audit_log WHERE target_entity = 'reactivation_request' AND target_id = ?",
				Long.class, requestA.id());
		assertThat(auditCount).isEqualTo(0L);
	}

	/**
	 * Anti-enumeration (plan §13, mirroring {@code
	 * SlipCrossTenantIntegrationTest#sameTenantDifferentStudentSlipAccessByAStudentReturns404NotForbidden}):
	 * a same-tenant Student who does not own the target enrollment/request
	 * must receive 404, never 403 - never able to distinguish "exists but
	 * isn't mine" from "doesn't exist".
	 */
	@Test
	void sameTenantDifferentStudentSubmissionAndDetailReadReturn404NotForbidden() {
		ExpiredEnrollmentFixture fixture = seedExpiredEnrollmentFixture("enr-anti-enum");
		seedActiveStudent(fixture.tenant().getId(), "other-student@example.test");
		String otherStudentToken = loginAndGetToken(fixture.host(), "other-student@example.test");
		ReactivationRequestResponse ownRequest = submitReactivationRequestOrFail(fixture.host(),
				fixture.studentToken(), fixture.enrollmentId());

		HttpResult<ReactivationRequestResponse> submitResult = submitReactivationRequest(fixture.host(),
				otherStudentToken, fixture.enrollmentId());
		assertThat(submitResult.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

		HttpResult<ReactivationRequestResponse> detailResult = getReactivationRequestDetail(fixture.host(),
				otherStudentToken, ownRequest.id());
		assertThat(detailResult.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

		Long requestCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM reactivation_request WHERE enrollment_id = ?", Long.class,
				fixture.enrollmentId());
		assertThat(requestCount).isEqualTo(1L);
	}

}
