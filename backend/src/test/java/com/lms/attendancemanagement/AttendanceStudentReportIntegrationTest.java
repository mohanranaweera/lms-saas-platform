package com.lms.attendancemanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.lms.attendancemanagement.domain.AttendanceStatus;
import com.lms.attendancemanagement.web.dto.AttendanceRecordResponse;
import com.lms.common.api.PageResponse;
import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.web.dto.CourseLessonResponse;
import com.lms.coursemanagement.course.web.dto.CourseModuleResponse;
import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.tenantmanagement.domain.Tenant;
import com.lms.usermanagement.student.web.dto.StudentCreateRequest;
import com.lms.usermanagement.student.web.dto.StudentResponse;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Wave 3 fix-pass (security review, "missing cross-tenant negative test") -
 * {@code GET /api/v1/attendance/students/{id}/report} had zero test coverage
 * at all before this. Unlike {@code AttendanceReportIntegrationTest}'s
 * fixtures (built on {@code seedActiveStudent}, a raw {@code tenant_user} row
 * only), this endpoint's {@code {id}} path variable is a {@code
 * student_profile} id (resolved to the opaque cross-domain {@code studentId}
 * via {@code StudentLookupApi}), so the fixture here creates a REAL {@code
 * student_profile} row through the real {@code POST /api/v1/students}
 * endpoint.
 */
class AttendanceStudentReportIntegrationTest extends AttendanceManagementTestSupport {

	@Test
	void staffViewsAStudentsAttendanceReportScopedToThatStudentInItsOwnTenant() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("att-student-report"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String teacherToken = loginAndGetToken(host, "teacher@example.test");
		StudentResponse studentProfile = createStudentProfileOrFail(host, adminToken, uniqueEmail("att-report"));
		String studentToken = loginAndGetToken(host, studentProfile.email());
		UUID studentUserId = jdbcTemplate.queryForObject("SELECT user_id FROM student_profile WHERE id = ?", UUID.class,
				studentProfile.id());
		CourseResponse course = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug("att-student-report"), teacher.getId(), CourseStatus.PUBLIC));
		CourseModuleResponse module = createModuleOrFail(host, adminToken, course.id(), "Module 1", 1);
		CourseLessonResponse lesson = createLessonOrFail(host, adminToken, course.id(), module.id(), "Lesson 1", 1);
		enrollStudentOrFail(host, studentToken, course.id());
		markOneStudent(host, teacherToken, lesson.id(), studentUserId, AttendanceStatus.PRESENT);

		HttpResult<PageResponse<AttendanceRecordResponse>> result = getAttendanceReportForStudent(host, adminToken,
				studentProfile.id());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(result.getBody().data().content()).extracting(AttendanceRecordResponse::studentId)
			.containsExactly(studentUserId);
		assertThat(result.getBody().data().content()).extracting(AttendanceRecordResponse::status)
			.containsExactly(AttendanceStatus.PRESENT);
	}

	@Test
	@Tag("cross-tenant")
	void tenantBStaffRequestingTenantAsStudentAttendanceReportByIdReturns404NeverTenantAsData() {
		Tenant tenantA = seedActiveTenant(uniqueSubdomain("att-student-report-cross-a"));
		Tenant tenantB = seedActiveTenant(uniqueSubdomain("att-student-report-cross-b"));
		seedTenantUser(tenantA.getId(), "admin-a@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		seedTenantUser(tenantB.getId(), "admin-b@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacherA = seedTenantUser(tenantA.getId(), "teacher-a@example.test", RAW_PASSWORD, Role.TEACHER);
		String hostA = hostFor(tenantA.getSubdomain());
		String hostB = hostFor(tenantB.getSubdomain());
		String adminTokenA = loginAndGetToken(hostA, "admin-a@example.test");
		String adminTokenB = loginAndGetToken(hostB, "admin-b@example.test");
		String teacherTokenA = loginAndGetToken(hostA, "teacher-a@example.test");
		StudentResponse studentProfileA = createStudentProfileOrFail(hostA, adminTokenA,
				uniqueEmail("att-report-cross"));
		String studentTokenA = loginAndGetToken(hostA, studentProfileA.email());
		UUID studentUserIdA = jdbcTemplate.queryForObject("SELECT user_id FROM student_profile WHERE id = ?",
				UUID.class, studentProfileA.id());
		CourseResponse courseA = createCourseOrFail(hostA, adminTokenA,
				newCourseRequest(uniqueSlug("att-student-report-cross"), teacherA.getId(), CourseStatus.PUBLIC));
		CourseModuleResponse moduleA = createModuleOrFail(hostA, adminTokenA, courseA.id(), "Module 1", 1);
		CourseLessonResponse lessonA = createLessonOrFail(hostA, adminTokenA, courseA.id(), moduleA.id(), "Lesson 1", 1);
		enrollStudentOrFail(hostA, studentTokenA, courseA.id());
		markOneStudent(hostA, teacherTokenA, lessonA.id(), studentUserIdA, AttendanceStatus.PRESENT);

		HttpResult<PageResponse<AttendanceRecordResponse>> crossTenantAttempt = getAttendanceReportForStudent(hostB,
				adminTokenB, studentProfileA.id());

		assertThat(crossTenantAttempt.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

		// Sanity: tenant A's own staff CAN read the same report - proves the
		// 404 above is tenant isolation, not a broken endpoint.
		HttpResult<PageResponse<AttendanceRecordResponse>> ownTenant = getAttendanceReportForStudent(hostA,
				adminTokenA, studentProfileA.id());
		assertThat(ownTenant.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(ownTenant.getBody().data().content()).isNotEmpty();
	}

	// ------------------------------------------------------------------
	// Local plumbing helpers.
	// ------------------------------------------------------------------

	private StudentResponse createStudentProfileOrFail(String host, String token, String email) {
		MockHttpServletRequestBuilder builder = post("/api/v1/students").contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(new StudentCreateRequest("Test Student", email, RAW_PASSWORD)));
		HttpResult<StudentResponse> result = parseSingle(perform(authenticated(builder, host, token)),
				StudentResponse.class);
		if (result.getStatusCode() != HttpStatus.CREATED) {
			throw new IllegalStateException("Student creation failed: " + result.getStatusCode());
		}
		return result.getBody().data();
	}

	private HttpResult<PageResponse<AttendanceRecordResponse>> getAttendanceReportForStudent(String host,
			String token, UUID studentProfileId) {
		MockHttpServletRequestBuilder builder = get("/api/v1/attendance/students/{id}/report", studentProfileId);
		return parsePage(perform(authenticated(builder, host, token)), AttendanceRecordResponse.class);
	}

	private static String uniqueEmail(String prefix) {
		return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
	}

}
