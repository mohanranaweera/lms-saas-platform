package com.lms.enrollmentmanagement;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.enrollmentmanagement.web.dto.CourseSummaryResponse;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.paymentmanagement.order.web.dto.OrderResponse;
import com.lms.paymentmanagement.order.web.dto.PaymentInitiationResponse;
import com.lms.tenantmanagement.domain.Tenant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Integration coverage for {@code GET /api/v1/enrollments/my/courses}
 * (MVP-013 "Student Dashboard" plan §18), mirroring {@code
 * EnrollmentCrossTenantIntegrationTest}'s/{@code
 * EnrollmentManagementTestSupport}'s established Testcontainers/MockMvc
 * conventions exactly. The mandatory cross-tenant negative test for this
 * endpoint lives in {@link EnrollmentCrossTenantIntegrationTest} alongside
 * this module's other cross-tenant proofs, per that class's own established
 * structure.
 */
class CourseSummaryIntegrationTest extends EnrollmentManagementTestSupport {

	@Test
	void aRealCurrentActiveEnrollmentReturnsExactlyThatCoursesSummary() {
		Fixture fixture = seedActiveEnrollmentFixture("cs-active");

		HttpResult<List<CourseSummaryResponse>> result = getMyEnrolledCourseSummaries(fixture.host(),
				fixture.studentToken());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		List<CourseSummaryResponse> rows = result.getBody().data();
		assertThat(rows).hasSize(1);
		CourseSummaryResponse row = rows.get(0);
		assertThat(row.id()).isEqualTo(fixture.course().id());
		assertThat(row.name()).isEqualTo(fixture.course().name());
		assertThat(row.slug()).isEqualTo(fixture.course().slug());
		assertThat(row.category()).isEqualTo(fixture.course().category());
	}

	@Test
	void zeroCurrentEnrollmentsReturnsAnEmptyArrayNotAnError() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("cs-empty"));
		seedActiveStudent(tenant.getId(), "student@example.test");
		String host = hostFor(tenant.getSubdomain());
		String studentToken = loginAndGetToken(host, "student@example.test");

		HttpResult<List<CourseSummaryResponse>> result = getMyEnrolledCourseSummaries(host, studentToken);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(result.getBody().data()).isEmpty();
	}

	/**
	 * Plan §18's other required disjointness proof, distinct from the
	 * mandatory cross-tenant test in {@code EnrollmentCrossTenantIntegrationTest}:
	 * same tenant, two different students, each enrolled in a different
	 * course. A query scoped only by {@code tenant_id} (missing a {@code
	 * student_id} filter) would pass the cross-tenant test but still leak
	 * here - this is the test that would catch that specific bug.
	 */
	@Test
	void aSecondStudentInTheSameTenantWithDifferentEnrollmentsGetsADisjointResult() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("cs-disjoint"));
		TenantUser admin = seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		seedActiveStudent(tenant.getId(), "student-a@example.test");
		seedActiveStudent(tenant.getId(), "student-b@example.test");
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String studentAToken = loginAndGetToken(host, "student-a@example.test");
		String studentBToken = loginAndGetToken(host, "student-b@example.test");

		CourseResponse courseA = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug("cs-disjoint-a"), teacher.getId(), CourseStatus.PUBLIC));
		CourseResponse courseB = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug("cs-disjoint-b"), teacher.getId(), CourseStatus.PUBLIC));

		purchaseCourseOrFail(host, studentAToken, courseA.id());
		purchaseCourseOrFail(host, studentBToken, courseB.id());

		HttpResult<List<CourseSummaryResponse>> resultA = getMyEnrolledCourseSummaries(host, studentAToken);
		HttpResult<List<CourseSummaryResponse>> resultB = getMyEnrolledCourseSummaries(host, studentBToken);

		assertThat(resultA.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(resultA.getBody().data()).extracting(CourseSummaryResponse::id).containsExactly(courseA.id());

		assertThat(resultB.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(resultB.getBody().data()).extracting(CourseSummaryResponse::id).containsExactly(courseB.id());
	}

	@Test
	void anUnauthenticatedCallerIsRejectedAndNeverReceivesACourseList() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("cs-unauth"));
		String host = hostFor(tenant.getSubdomain());

		HttpResult<List<CourseSummaryResponse>> result = getMyEnrolledCourseSummaries(host, null);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void nonStudentRolesAreRejectedAndNeverReceiveACourseList() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("cs-role"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		seedTenantUser(tenant.getId(), "finance@example.test", RAW_PASSWORD, Role.FINANCE_STAFF);
		seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String financeToken = loginAndGetToken(host, "finance@example.test");
		String teacherToken = loginAndGetToken(host, "teacher@example.test");

		HttpResult<List<CourseSummaryResponse>> adminResult = getMyEnrolledCourseSummaries(host, adminToken);
		HttpResult<List<CourseSummaryResponse>> financeResult = getMyEnrolledCourseSummaries(host, financeToken);
		HttpResult<List<CourseSummaryResponse>> teacherResult = getMyEnrolledCourseSummaries(host, teacherToken);

		assertThat(adminResult.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(financeResult.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(teacherResult.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	/**
	 * Regression sanity (plan §18): course-name resolution reads {@code
	 * enrollment.superseded_at IS NULL} ("current"), not {@code ACTIVE} vs.
	 * {@code EXPIRED} access state - an expired-but-current row's course must
	 * still resolve a summary.
	 */
	@Test
	void anExpiredButStillCurrentEnrollmentsCourseStillResolvesASummary() {
		ExpiredEnrollmentFixture fixture = seedExpiredEnrollmentFixture("cs-expired");

		HttpResult<List<CourseSummaryResponse>> result = getMyEnrolledCourseSummaries(fixture.host(),
				fixture.studentToken());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		List<CourseSummaryResponse> rows = result.getBody().data();
		assertThat(rows).extracting(CourseSummaryResponse::id).containsExactly(fixture.course().id());
		assertThat(rows.get(0).name()).isEqualTo(fixture.course().name());
	}

	// ------------------------------------------------------------------
	// Fixture seeding.
	// ------------------------------------------------------------------

	private record Fixture(String host, String studentToken, CourseResponse course) {
	}

	/**
	 * Seeds a tenant, Tenant Admin, Teacher, Student, a published course, and
	 * completes a NORMAL first-time purchase (order -&gt; payment -&gt;
	 * webhook confirm), leaving a genuinely current, non-expired ({@code
	 * ACTIVE}) enrollment - mirrors {@code
	 * EnrollmentReconciliationQueryIntegrationTest#seedConfirmedActivationFixture}'s
	 * technique.
	 */
	private Fixture seedActiveEnrollmentFixture(String prefix) {
		Tenant tenant = seedActiveTenant(uniqueSubdomain(prefix));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		seedActiveStudent(tenant.getId(), "student@example.test");
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String studentToken = loginAndGetToken(host, "student@example.test");
		CourseResponse course = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug(prefix), teacher.getId(), CourseStatus.PUBLIC));

		purchaseCourseOrFail(host, studentToken, course.id());

		return new Fixture(host, studentToken, course);
	}

	/** Completes a NORMAL first-time purchase (order -&gt; payment -&gt; webhook confirm), leaving a current enrollment. */
	private void purchaseCourseOrFail(String host, String studentToken, UUID courseId) {
		OrderResponse order = createOrderOrFail(host, studentToken, courseId);
		PaymentInitiationResponse initiation = initiatePaymentOrFail(host, studentToken, order.id());
		HttpResult<Void> webhook = sendPaymentWebhook(initiation.gatewayReference(), true);
		if (webhook.getStatusCode() != HttpStatus.OK) {
			throw new IllegalStateException("Webhook confirmation failed: " + webhook.getStatusCode());
		}
	}

}
