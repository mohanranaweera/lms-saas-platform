package com.lms.coursemanagement.course.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.coursemanagement.CourseManagementTestSupport;
import com.lms.coursemanagement.course.web.dto.CourseLessonResponse;
import com.lms.coursemanagement.course.web.dto.CourseModuleResponse;
import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.tenantmanagement.domain.Tenant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Testcontainers-backed coverage for Course Management (MVP-008)'s module and
 * lesson "structure only" endpoints, nested under a course. Every module
 * /lesson mutation loads its parent course through the same tenant-scoped
 * read used by the course-level endpoints, so cross-tenant access is proven
 * the same way (plan §14).
 */
class CourseModuleLessonIntegrationTest extends CourseManagementTestSupport {

	@Test
	void tenantAdminCanCreateListUpdateAndDeleteModulesAndLessons() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("modules-crud"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "admin@example.test");
		CourseResponse course = createCourseOrFail(host, token, newCourseRequest(uniqueSlug("modules"), teacher.getId()));

		CourseModuleResponse module = createModuleOrFail(host, token, course.id(), "Module 1", 1);
		assertThat(module.courseId()).isEqualTo(course.id());

		HttpResult<List<CourseModuleResponse>> listResult = listModules(host, token, course.id());
		assertThat(listResult.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(listResult.getBody().data()).extracting(CourseModuleResponse::id).contains(module.id());

		HttpResult<CourseModuleResponse> updateResult = updateModule(host, token, course.id(), module.id(),
				"Module 1 Updated", 2);
		assertThat(updateResult.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(updateResult.getBody().data().title()).isEqualTo("Module 1 Updated");

		CourseLessonResponse lesson = createLessonOrFail(host, token, course.id(), module.id(), "Lesson 1", 1);
		assertThat(lesson.moduleId()).isEqualTo(module.id());

		HttpResult<CourseLessonResponse> lessonUpdate = updateLesson(host, token, course.id(), module.id(),
				lesson.id(), "Lesson 1 Updated", 1);
		assertThat(lessonUpdate.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(lessonUpdate.getBody().data().title()).isEqualTo("Lesson 1 Updated");

		HttpResult<Void> lessonDelete = deleteLesson(host, token, course.id(), module.id(), lesson.id());
		assertThat(lessonDelete.getStatusCode()).isEqualTo(HttpStatus.OK);

		HttpResult<Void> moduleDelete = deleteModule(host, token, course.id(), module.id());
		assertThat(moduleDelete.getStatusCode()).isEqualTo(HttpStatus.OK);

		HttpResult<List<CourseModuleResponse>> afterDelete = listModules(host, token, course.id());
		assertThat(afterDelete.getBody().data()).extracting(CourseModuleResponse::id).doesNotContain(module.id());
	}

	@Test
	void duplicateModuleSequenceWithinTheSameCourseReturns409() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("modules-dup-seq"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "admin@example.test");
		CourseResponse course = createCourseOrFail(host, token,
				newCourseRequest(uniqueSlug("modules-dup"), teacher.getId()));
		createModuleOrFail(host, token, course.id(), "Module 1", 1);

		HttpResult<CourseModuleResponse> second = createModule(host, token, course.id(), "Module 2", 1);

		assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
	}

	@Test
	void duplicateLessonSequenceWithinTheSameModuleReturns409() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("lessons-dup-seq"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "admin@example.test");
		CourseResponse course = createCourseOrFail(host, token,
				newCourseRequest(uniqueSlug("lessons-dup"), teacher.getId()));
		CourseModuleResponse module = createModuleOrFail(host, token, course.id(), "Module 1", 1);
		createLessonOrFail(host, token, course.id(), module.id(), "Lesson 1", 1);

		HttpResult<CourseLessonResponse> second = createLesson(host, token, course.id(), module.id(), "Lesson 2", 1);

		assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
	}

	@Test
	void nonOwningTeacherCannotCreateEditOrDeleteModulesOnAnotherTeachersCourse() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("modules-non-owner"));
		seedTenantUser(tenant.getId(), "teacher-a@example.test", RAW_PASSWORD, Role.TEACHER);
		seedTenantUser(tenant.getId(), "teacher-b@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String teacherAToken = loginAndGetToken(host, "teacher-a@example.test");
		String teacherBToken = loginAndGetToken(host, "teacher-b@example.test");
		CourseResponse courseA = createCourseOrFail(host, teacherAToken,
				newCourseRequest(uniqueSlug("modules-non-owner"), null));
		CourseModuleResponse moduleA = createModuleOrFail(host, teacherAToken, courseA.id(), "Module A", 1);

		assertThat(createModule(host, teacherBToken, courseA.id(), "Blocked", 2).getStatusCode())
			.isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(updateModule(host, teacherBToken, courseA.id(), moduleA.id(), "Blocked Update", 1).getStatusCode())
			.isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(deleteModule(host, teacherBToken, courseA.id(), moduleA.id()).getStatusCode())
			.isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void crossTenantModuleCreateEditAndDeleteAllReturn404ScopedThroughTheParentCoursesTenant() {
		Tenant tenantA = seedActiveTenant(uniqueSubdomain("modules-cross-a"));
		Tenant tenantB = seedActiveTenant(uniqueSubdomain("modules-cross-b"));
		seedTenantUser(tenantA.getId(), "admin-a@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		seedTenantUser(tenantB.getId(), "admin-b@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacherA = seedTenantUser(tenantA.getId(), "teacher-a@example.test", RAW_PASSWORD, Role.TEACHER);
		String hostA = hostFor(tenantA.getSubdomain());
		String hostB = hostFor(tenantB.getSubdomain());
		String tokenA = loginAndGetToken(hostA, "admin-a@example.test");
		String tokenB = loginAndGetToken(hostB, "admin-b@example.test");
		CourseResponse courseA = createCourseOrFail(hostA, tokenA,
				newCourseRequest(uniqueSlug("modules-cross"), teacherA.getId()));
		CourseModuleResponse moduleA = createModuleOrFail(hostA, tokenA, courseA.id(), "Module A", 1);

		assertThat(createModule(hostB, tokenB, courseA.id(), "Cross Module", 1).getStatusCode())
			.isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(updateModule(hostB, tokenB, courseA.id(), moduleA.id(), "Cross Update", 1).getStatusCode())
			.isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(deleteModule(hostB, tokenB, courseA.id(), moduleA.id()).getStatusCode())
			.isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(listModules(hostB, tokenB, courseA.id()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void crossTenantLessonCreateEditAndDeleteAllReturn404ScopedThroughTheParentCoursesTenant() {
		Tenant tenantA = seedActiveTenant(uniqueSubdomain("lessons-cross-a"));
		Tenant tenantB = seedActiveTenant(uniqueSubdomain("lessons-cross-b"));
		seedTenantUser(tenantA.getId(), "admin-a@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		seedTenantUser(tenantB.getId(), "admin-b@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacherA = seedTenantUser(tenantA.getId(), "teacher-a@example.test", RAW_PASSWORD, Role.TEACHER);
		String hostA = hostFor(tenantA.getSubdomain());
		String hostB = hostFor(tenantB.getSubdomain());
		String tokenA = loginAndGetToken(hostA, "admin-a@example.test");
		String tokenB = loginAndGetToken(hostB, "admin-b@example.test");
		CourseResponse courseA = createCourseOrFail(hostA, tokenA,
				newCourseRequest(uniqueSlug("lessons-cross"), teacherA.getId()));
		CourseModuleResponse moduleA = createModuleOrFail(hostA, tokenA, courseA.id(), "Module A", 1);
		CourseLessonResponse lessonA = createLessonOrFail(hostA, tokenA, courseA.id(), moduleA.id(), "Lesson A", 1);

		assertThat(createLesson(hostB, tokenB, courseA.id(), moduleA.id(), "Cross Lesson", 2).getStatusCode())
			.isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(
				updateLesson(hostB, tokenB, courseA.id(), moduleA.id(), lessonA.id(), "Cross Update", 1)
					.getStatusCode())
			.isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(deleteLesson(hostB, tokenB, courseA.id(), moduleA.id(), lessonA.id()).getStatusCode())
			.isEqualTo(HttpStatus.NOT_FOUND);
	}

}
