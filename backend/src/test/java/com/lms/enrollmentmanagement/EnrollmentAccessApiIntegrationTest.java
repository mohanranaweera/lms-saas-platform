package com.lms.enrollmentmanagement;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.enrollmentmanagement.api.EnrollmentAccessApi;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.paymentmanagement.order.web.dto.OrderResponse;
import com.lms.paymentmanagement.order.web.dto.PaymentInitiationResponse;
import com.lms.tenantmanagement.domain.Tenant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

/**
 * Direct, owning-domain coverage for {@link EnrollmentAccessApi#listCurrentlyEnrolledStudentIds(UUID)}
 * (post-ship review of MVP-016 "Attendance," Finding 2) - this method
 * previously had no test of its own in {@code enrollment-management}'s own
 * test package, only indirect exercise via {@code attendance-management}'s
 * {@code AttendanceMarkingServiceTest}/{@code AttendanceMarkingIntegrationTest}.
 *
 * <p>Mirrors {@code paymentmanagement.SlipStatusApiIntegrationTest}'s
 * established technique for a narrow {@code api}-package read exactly:
 * autowire the interface directly (never {@code EnrollmentAccessApiImpl}, per
 * the interface's own "a module may depend only on another module's {@code
 * api} package" rule), and invoke it via {@link #withTenant} to simulate the
 * trusted, already-resolved tenant context without going through the HTTP
 * filter chain. Uses the real Testcontainers Postgres (not a mock of {@code
 * EnrollmentRepository}) so tenant-scoping and the access-currency predicate
 * ({@code supersededAt IS NULL AND (accessExpiresAt IS NULL OR
 * accessExpiresAt > now())}) are genuinely exercised.
 */
class EnrollmentAccessApiIntegrationTest extends EnrollmentManagementTestSupport {

	@Autowired
	private EnrollmentAccessApi enrollmentAccessApi;

	@Test
	void aCurrentlyEnrolledStudentAppearsInTheResult() {
		Fixture fixture = seedActiveEnrollmentFixture("eaa-active");

		List<UUID> result = withTenant(fixture.tenant().getId(),
				() -> enrollmentAccessApi.listCurrentlyEnrolledStudentIds(fixture.course().id()));

		assertThat(result).containsExactly(fixture.student().getId());
	}

	/**
	 * A student whose only enrollment for the course is access-expired (but
	 * still lineage-current, i.e. {@code supersededAt IS NULL}) must be
	 * excluded - "currently enrolled" for this method means access-currency,
	 * not merely lineage-currency, per the interface's own javadoc.
	 */
	@Test
	void aStudentWithOnlyAnAccessExpiredEnrollmentIsExcluded() {
		ExpiredEnrollmentFixture fixture = seedExpiredEnrollmentFixture("eaa-expired");

		List<UUID> result = withTenant(fixture.tenant().getId(),
				() -> enrollmentAccessApi.listCurrentlyEnrolledStudentIds(fixture.course().id()));

		assertThat(result).doesNotContain(fixture.student().getId());
		assertThat(result).isEmpty();
	}

	/**
	 * A superseded enrollment row (lineage-historical, {@code supersededAt}
	 * set) must never be reported as currently enrolled, even though the row
	 * itself would otherwise read as access-current (no expiry). Directly
	 * back-dates {@code superseded_at} via {@code jdbcTemplate}, mirroring
	 * {@code seedExpiredEnrollmentFixture}'s own established technique for
	 * forcing a specific lineage state without needing the full reactivation
	 * HTTP flow.
	 */
	@Test
	void aSupersededEnrollmentIsExcluded() {
		Fixture fixture = seedActiveEnrollmentFixture("eaa-superseded");
		UUID enrollmentId = jdbcTemplate.queryForObject(
				"SELECT id FROM enrollment WHERE tenant_id = ? AND student_id = ? AND course_id = ? "
						+ "AND superseded_at IS NULL",
				UUID.class, fixture.tenant().getId(), fixture.student().getId(), fixture.course().id());
		int updated = jdbcTemplate.update("UPDATE enrollment SET superseded_at = now() WHERE id = ?", enrollmentId);
		if (updated != 1) {
			throw new IllegalStateException("Expected to supersede exactly one enrollment row, updated " + updated);
		}

		List<UUID> result = withTenant(fixture.tenant().getId(),
				() -> enrollmentAccessApi.listCurrentlyEnrolledStudentIds(fixture.course().id()));

		assertThat(result).isEmpty();
	}

	/**
	 * The tenant-scoping proof, mirroring {@code
	 * SlipStatusApiIntegrationTest#isApprovedForCurrentTenantReturnsFalseWhenCalledUnderADifferentTenantForTheSameSlipId}'s
	 * exact technique: a genuinely current, active enrollment for a real
	 * courseId exists in tenant A. Calling this method for THAT SAME courseId
	 * while resolved as tenant B must return empty - never tenant A's
	 * roster - proving the underlying {@code findAll} is genuinely tenant-
	 * scoped (structural on {@code TenantAwareRepositoryImpl}), not merely
	 * "correct because course ids never collide in practice."
	 */
	@Test
	void aDifferentTenantsEnrollmentForTheSameCourseIdIsExcluded() {
		Fixture tenantA = seedActiveEnrollmentFixture("eaa-xt-a");
		Tenant tenantB = seedActiveTenant(uniqueSubdomain("eaa-xt-b"));

		List<UUID> resultUnderTenantB = withTenant(tenantB.getId(),
				() -> enrollmentAccessApi.listCurrentlyEnrolledStudentIds(tenantA.course().id()));

		assertThat(resultUnderTenantB).isEmpty();

		// Sanity: the SAME courseId, resolved under tenant A's own context,
		// DOES report the real roster - proving the emptiness above is
		// tenant isolation, not a broken method.
		List<UUID> resultUnderTenantA = withTenant(tenantA.tenant().getId(),
				() -> enrollmentAccessApi.listCurrentlyEnrolledStudentIds(tenantA.course().id()));
		assertThat(resultUnderTenantA).containsExactly(tenantA.student().getId());
	}

	// ------------------------------------------------------------------
	// Fixture seeding.
	// ------------------------------------------------------------------

	private record Fixture(Tenant tenant, String host, String studentToken, TenantUser student, CourseResponse course) {
	}

	/**
	 * Seeds a tenant, Tenant Admin, Teacher, Student, a published course, and
	 * completes a NORMAL first-time purchase (order -&gt; payment -&gt;
	 * webhook confirm), leaving a genuinely current, non-expired ({@code
	 * ACTIVE}) enrollment - mirrors {@code
	 * CourseSummaryIntegrationTest#seedActiveEnrollmentFixture}'s technique.
	 */
	private Fixture seedActiveEnrollmentFixture(String prefix) {
		Tenant tenant = seedActiveTenant(uniqueSubdomain(prefix));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		TenantUser student = seedActiveStudent(tenant.getId(), "student@example.test");
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String studentToken = loginAndGetToken(host, "student@example.test");
		CourseResponse course = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug(prefix), teacher.getId(), CourseStatus.PUBLIC));

		OrderResponse order = createOrderOrFail(host, studentToken, course.id());
		PaymentInitiationResponse initiation = initiatePaymentOrFail(host, studentToken, order.id());
		HttpResult<Void> webhook = sendPaymentWebhook(initiation.gatewayReference(), true);
		if (webhook.getStatusCode() != HttpStatus.OK) {
			throw new IllegalStateException("Webhook confirmation failed: " + webhook.getStatusCode());
		}

		return new Fixture(tenant, host, studentToken, student, course);
	}

}
