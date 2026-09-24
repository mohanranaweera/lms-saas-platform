package com.lms.exammanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.lms.common.api.PageResponse;
import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.exammanagement.web.dto.ExamAttemptResponse;
import com.lms.exammanagement.web.dto.ExamQuestionResponse;
import com.lms.exammanagement.web.dto.ExamResponse;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.tenantmanagement.domain.Tenant;
import com.lms.usermanagement.student.web.dto.StudentCreateRequest;
import com.lms.usermanagement.student.web.dto.StudentResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Wave 3 fix-pass (security review, "missing cross-tenant negative test") -
 * {@code GET /api/v1/exams/students/{id}/attempts} had zero test coverage at
 * all before this. Like {@link
 * com.lms.attendancemanagement.AttendanceStudentReportIntegrationTest}, this
 * endpoint's {@code {id}} is a {@code student_profile} id, so the fixture
 * here creates a REAL {@code student_profile} row via {@code POST
 * /api/v1/students} rather than {@code seedActiveStudent}'s raw {@code
 * tenant_user} row.
 */
class ExamAttemptStudentReadIntegrationTest extends ExamManagementTestSupport {

	@Test
	void staffViewsAStudentsExamAttemptsScopedToThatStudentInItsOwnTenant() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("exam-student-attempts"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String teacherToken = loginAndGetToken(host, "teacher@example.test");
		StudentResponse studentProfile = createStudentProfileOrFail(host, adminToken, uniqueEmail("exam-attempts"));
		String studentToken = loginAndGetToken(host, studentProfile.email());
		UUID studentUserId = jdbcTemplate.queryForObject("SELECT user_id FROM student_profile WHERE id = ?", UUID.class,
				studentProfile.id());
		CourseResponse course = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug("exam-student-attempts"), teacher.getId(), CourseStatus.PUBLIC));
		enrollStudentOrFail(host, studentToken, course.id());
		ExamQuestionResponse question = createMcqQuestionOrFail(host, teacherToken, course.id(), "2+2?", "4", "3");
		ExamResponse exam = createAndPrepareDraftExamOrFail(host, teacherToken, course.id(), "Midterm",
				Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600), 60, List.of(question.id()));
		scheduleExamOrFail(host, teacherToken, exam.id());
		startAttemptOrFail(host, studentToken, exam.id());

		HttpResult<PageResponse<ExamAttemptResponse>> result = getAttemptsForStudent(host, adminToken,
				studentProfile.id());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(result.getBody().data().content()).extracting(ExamAttemptResponse::studentId)
			.containsExactly(studentUserId);
		assertThat(result.getBody().data().content()).extracting(ExamAttemptResponse::examId).containsExactly(exam.id());
	}

	@Test
	@Tag("cross-tenant")
	void tenantBStaffRequestingTenantAsStudentAttemptsByIdReturns404NeverTenantAsData() {
		Tenant tenantA = seedActiveTenant(uniqueSubdomain("exam-student-attempts-cross-a"));
		Tenant tenantB = seedActiveTenant(uniqueSubdomain("exam-student-attempts-cross-b"));
		seedTenantUser(tenantA.getId(), "admin-a@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		seedTenantUser(tenantB.getId(), "admin-b@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacherA = seedTenantUser(tenantA.getId(), "teacher-a@example.test", RAW_PASSWORD, Role.TEACHER);
		String hostA = hostFor(tenantA.getSubdomain());
		String hostB = hostFor(tenantB.getSubdomain());
		String adminTokenA = loginAndGetToken(hostA, "admin-a@example.test");
		String adminTokenB = loginAndGetToken(hostB, "admin-b@example.test");
		String teacherTokenA = loginAndGetToken(hostA, "teacher-a@example.test");
		StudentResponse studentProfileA = createStudentProfileOrFail(hostA, adminTokenA,
				uniqueEmail("exam-attempts-cross"));
		String studentTokenA = loginAndGetToken(hostA, studentProfileA.email());
		CourseResponse courseA = createCourseOrFail(hostA, adminTokenA,
				newCourseRequest(uniqueSlug("exam-student-attempts-cross"), teacherA.getId(), CourseStatus.PUBLIC));
		enrollStudentOrFail(hostA, studentTokenA, courseA.id());
		ExamQuestionResponse questionA = createMcqQuestionOrFail(hostA, teacherTokenA, courseA.id(), "2+2?", "4", "3");
		ExamResponse examA = createAndPrepareDraftExamOrFail(hostA, teacherTokenA, courseA.id(), "Midterm",
				Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600), 60, List.of(questionA.id()));
		scheduleExamOrFail(hostA, teacherTokenA, examA.id());
		startAttemptOrFail(hostA, studentTokenA, examA.id());

		HttpResult<PageResponse<ExamAttemptResponse>> crossTenantAttempt = getAttemptsForStudent(hostB, adminTokenB,
				studentProfileA.id());

		assertThat(crossTenantAttempt.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

		// Sanity: tenant A's own staff CAN read the same attempts - proves the
		// 404 above is tenant isolation, not a broken endpoint.
		HttpResult<PageResponse<ExamAttemptResponse>> ownTenant = getAttemptsForStudent(hostA, adminTokenA,
				studentProfileA.id());
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

	private HttpResult<PageResponse<ExamAttemptResponse>> getAttemptsForStudent(String host, String token,
			UUID studentProfileId) {
		MockHttpServletRequestBuilder builder = get("/api/v1/exams/students/{id}/attempts", studentProfileId);
		return parsePage(perform(authenticated(builder, host, token)), ExamAttemptResponse.class);
	}

	private static String uniqueEmail(String prefix) {
		return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
	}

}
