package com.lms.coursemanagement;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.tenantmanagement.domain.Tenant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Direct, owning-domain coverage for {@link CourseLookupApi#getTeacherIdsByCourseId(Set)}
 * (post-ship review of MVP-016 "Attendance," Finding 1) - this method
 * previously had no test of its own in {@code course-management}'s own test
 * package, only indirect exercise via {@code attendance-management}'s {@code
 * AttendanceReportServiceTest}/{@code AttendanceReportIntegrationTest} (see
 * the plan's own §22 addendum).
 *
 * <p>Mirrors {@code paymentmanagement.SlipStatusApiIntegrationTest}'s
 * established technique for a narrow {@code api}-package read exactly:
 * autowire the interface directly (never the concrete {@code
 * CourseLookupApiImpl}, matching the "depend only on another module's {@code
 * api} package" rule this same interface's javadoc calls out), and invoke it
 * via {@link #withTenant} to simulate the trusted, already-resolved tenant
 * context without going through the HTTP filter chain. Uses the real
 * Testcontainers Postgres (not a Mockito mock of {@code CourseRepository}) so
 * that {@code findAllById}'s tenant-scoping - structural on {@code
 * TenantAwareRepositoryImpl}, not a hand-rolled {@code WHERE} clause in this
 * method - is genuinely exercised, not merely assumed.
 */
class CourseLookupApiIntegrationTest extends CourseManagementTestSupport {

	@Autowired
	private CourseLookupApi courseLookupApi;

	@Test
	void batchResolvesTheCorrectCourseIdToTeacherIdMapForEveryOwnedCourse() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("clu-batch"));
		TenantUser admin = seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacherA = seedTenantUser(tenant.getId(), "teacher-a@example.test", RAW_PASSWORD, Role.TEACHER);
		TenantUser teacherB = seedTenantUser(tenant.getId(), "teacher-b@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		CourseResponse courseA = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug("clu-batch-a"), teacherA.getId(), CourseStatus.PUBLIC));
		CourseResponse courseB = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug("clu-batch-b"), teacherB.getId(), CourseStatus.PUBLIC));

		Map<UUID, UUID> result = withTenant(tenant.getId(),
				() -> courseLookupApi.getTeacherIdsByCourseId(Set.of(courseA.id(), courseB.id())));

		assertThat(result).hasSize(2);
		assertThat(result).containsEntry(courseA.id(), teacherA.getId());
		assertThat(result).containsEntry(courseB.id(), teacherB.getId());
	}

	/**
	 * The tenant-scoping proof: a course id genuinely belonging to a
	 * different tenant must be silently absent from the result - never an
	 * error for the whole batch, and never resolved to that other tenant's
	 * real teacher id - even though the id is real and would resolve
	 * correctly under its own tenant's context.
	 */
	@Test
	void aCourseIdBelongingToADifferentTenantIsExcludedNotResolved() {
		Tenant tenantA = seedActiveTenant(uniqueSubdomain("clu-xt-a"));
		Tenant tenantB = seedActiveTenant(uniqueSubdomain("clu-xt-b"));
		TenantUser adminA = seedTenantUser(tenantA.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser adminB = seedTenantUser(tenantB.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacherA = seedTenantUser(tenantA.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		TenantUser teacherB = seedTenantUser(tenantB.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String hostA = hostFor(tenantA.getSubdomain());
		String hostB = hostFor(tenantB.getSubdomain());
		String adminTokenA = loginAndGetToken(hostA, "admin@example.test");
		String adminTokenB = loginAndGetToken(hostB, "admin@example.test");
		CourseResponse courseA = createCourseOrFail(hostA, adminTokenA,
				newCourseRequest(uniqueSlug("clu-xt-course-a"), teacherA.getId(), CourseStatus.PUBLIC));
		CourseResponse courseB = createCourseOrFail(hostB, adminTokenB,
				newCourseRequest(uniqueSlug("clu-xt-course-b"), teacherB.getId(), CourseStatus.PUBLIC));

		// Resolved AS tenant A, asking for BOTH tenant A's own course and
		// tenant B's real (but foreign) course id in the same batch.
		Map<UUID, UUID> result = withTenant(tenantA.getId(),
				() -> courseLookupApi.getTeacherIdsByCourseId(Set.of(courseA.id(), courseB.id())));

		assertThat(result).hasSize(1);
		assertThat(result).containsEntry(courseA.id(), teacherA.getId());
		assertThat(result).doesNotContainKey(courseB.id());

		// Sanity: tenant B's own course id DOES resolve correctly under its
		// own tenant's context, proving the exclusion above is tenant
		// isolation, not a broken lookup.
		Map<UUID, UUID> ownResult = withTenant(tenantB.getId(),
				() -> courseLookupApi.getTeacherIdsByCourseId(Set.of(courseB.id())));
		assertThat(ownResult).containsEntry(courseB.id(), teacherB.getId());
	}

	@Test
	void anEmptyInputSetReturnsAnEmptyMapNotAnError() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("clu-empty"));

		Map<UUID, UUID> result = withTenant(tenant.getId(), () -> courseLookupApi.getTeacherIdsByCourseId(Set.of()));

		assertThat(result).isEmpty();
	}

}
