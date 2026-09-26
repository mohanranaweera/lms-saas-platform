package com.lms.coursemanagement.course.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.lms.contentmanagement.ContentManagementTestSupport;
import com.lms.contentmanagement.material.web.dto.MaterialResponse;
import com.lms.coursemanagement.course.web.dto.CourseLessonResponse;
import com.lms.coursemanagement.course.web.dto.CourseModuleResponse;
import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.Role;
import com.lms.tenantmanagement.domain.Tenant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Testcontainers-backed HTTP coverage for an enrolled Student reading their
 * course content: a Student whose enrollment is ACTIVE (inside the paid
 * access window) can read a PUBLIC or PRIVATE course's detail, modules,
 * lessons, and materials; a DRAFT course, an expired enrollment, a
 * never-enrolled Student, and a Student from another tenant are all denied.
 */
class EnrolledStudentCourseAccessIntegrationTest extends ContentManagementTestSupport {

	@Test
	void activelyEnrolledStudentCanReadAPrivateCoursesDetailModulesLessonsAndMaterials() {
		Fixture f = enrolledStudentInPrivateCourse("enrolled-private");

		assertThat(getCourse(f.host, f.studentToken, f.courseId).getStatusCode()).isEqualTo(HttpStatus.OK);
		HttpResult<List<CourseModuleResponse>> modules = listModules(f.host, f.studentToken, f.courseId);
		assertThat(modules.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(modules.getBody().data()).extracting(CourseModuleResponse::id).containsExactly(f.moduleId);
		HttpResult<List<CourseLessonResponse>> lessons = listLessons(f.host, f.studentToken, f.courseId, f.moduleId);
		assertThat(lessons.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(lessons.getBody().data()).extracting(CourseLessonResponse::id).containsExactly(f.lessonId);
		HttpResult<List<MaterialResponse>> materials = listMaterials(f.host, f.studentToken, f.courseId, f.moduleId,
				f.lessonId);
		assertThat(materials.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(materials.getBody().data()).hasSize(1);
	}

	@Test
	void activelyEnrolledStudentIsDeniedOnceTheCourseIsBackInDraft() {
		Fixture f = enrolledStudentInPrivateCourse("enrolled-draft");
		setCourseStatus(f.courseId, "DRAFT");

		assertThat(getCourse(f.host, f.studentToken, f.courseId).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(listModules(f.host, f.studentToken, f.courseId).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(listMaterials(f.host, f.studentToken, f.courseId, f.moduleId, f.lessonId).getStatusCode())
			.isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void studentWhoseAccessWindowHasLapsedIsDenied() {
		Fixture f = enrolledStudentInPrivateCourse("enrolled-expired");
		jdbcTemplate.update(
				"UPDATE enrollment SET access_expires_at = now() - interval '1 minute' WHERE course_id = ? AND superseded_at IS NULL",
				f.courseId);

		assertThat(getCourse(f.host, f.studentToken, f.courseId).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(listModules(f.host, f.studentToken, f.courseId).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(listMaterials(f.host, f.studentToken, f.courseId, f.moduleId, f.lessonId).getStatusCode())
			.isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void neverEnrolledStudentInTheSameTenantIsDenied() {
		Fixture f = enrolledStudentInPrivateCourse("never-enrolled");
		seedActiveStudent(f.tenantId, "other-student@example.test");
		String otherToken = loginAndGetToken(f.host, "other-student@example.test");

		assertThat(getCourse(f.host, otherToken, f.courseId).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(listModules(f.host, otherToken, f.courseId).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(listLessons(f.host, otherToken, f.courseId, f.moduleId).getStatusCode())
			.isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void enrolledStudentFromAnotherTenantCannotReachThisTenantsCourseById() {
		Fixture tenantA = enrolledStudentInPrivateCourse("cross-tenant-a");
		Fixture tenantB = enrolledStudentInPrivateCourse("cross-tenant-b");

		HttpResult<CourseResponse> course = getCourse(tenantB.host, tenantB.studentToken, tenantA.courseId);
		assertThat(course.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(course.getBody().data()).isNull();
		assertThat(listModules(tenantB.host, tenantB.studentToken, tenantA.courseId).getStatusCode())
			.isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(listMaterials(tenantB.host, tenantB.studentToken, tenantA.courseId, tenantA.moduleId,
				tenantA.lessonId)
			.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	/**
	 * Seeds a tenant with a teacher-owned course (one module, one lesson, one
	 * material), enrolls a Student through the real order -> payment ->
	 * webhook-confirm flow while it is PUBLIC, then moves it to PRIVATE. No
	 * API transitions a course to PRIVATE today, so the status is set
	 * directly.
	 */
	private Fixture enrolledStudentInPrivateCourse(String label) {
		Tenant tenant = seedActiveTenant(uniqueSubdomain(label));
		seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		seedActiveStudent(tenant.getId(), "student@example.test");
		String host = hostFor(tenant.getSubdomain());
		String teacherToken = loginAndGetToken(host, "teacher@example.test");
		String studentToken = loginAndGetToken(host, "student@example.test");
		CourseResponse course = createCourseOrFail(host, teacherToken, newCourseRequest(uniqueSlug(label), null));
		CourseModuleResponse module = createModuleOrFail(host, teacherToken, course.id(), "Module 1", 1);
		CourseLessonResponse lesson = createLessonOrFail(host, teacherToken, course.id(), module.id(), "Lesson 1", 1);
		createMaterialOrFail(host, teacherToken, course.id(), module.id(), lesson.id(), "Notes", pdfFile("notes.pdf"));
		publish(host, teacherToken, course.id());
		enrollStudentOrFail(host, studentToken, course.id());
		setCourseStatus(course.id(), "PRIVATE");
		return new Fixture(tenant.getId(), host, studentToken, course.id(), module.id(), lesson.id());
	}

	private void setCourseStatus(UUID courseId, String status) {
		jdbcTemplate.update("UPDATE course SET status = ? WHERE id = ?", status, courseId);
	}

	private HttpResult<List<CourseLessonResponse>> listLessons(String host, String token, UUID courseId,
			UUID moduleId) {
		return parseList(perform(authenticated(get("/api/v1/courses/{courseId}/modules/{moduleId}/lessons", courseId,
				moduleId), host, token)), CourseLessonResponse.class);
	}

	private record Fixture(UUID tenantId, String host, String studentToken, UUID courseId, UUID moduleId,
			UUID lessonId) {
	}

}
