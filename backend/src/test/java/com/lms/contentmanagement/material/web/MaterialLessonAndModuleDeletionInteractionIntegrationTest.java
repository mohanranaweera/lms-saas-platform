package com.lms.contentmanagement.material.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.contentmanagement.ContentManagementTestSupport;
import com.lms.contentmanagement.material.web.dto.MaterialResponse;
import com.lms.coursemanagement.course.web.dto.CourseModuleResponse;
import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.tenantmanagement.domain.Tenant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Testcontainers-backed HTTP coverage for MVP-009 review finding 4(b):
 * {@code CourseLessonService#deleteLesson}/{@code
 * CourseModuleService#deleteModule} are separately reachable, Teacher
 * -accessible ({@code CREATE_EDIT}, not the Tenant-Admin-only {@code DELETE}
 * gate {@code CourseService#deleteCourse} uses) endpoints that hit the exact
 * same {@code fk_material_lesson} foreign key violation (V16 - deliberately
 * no {@code ON DELETE CASCADE}) as course deletion already covered by {@link
 * MaterialCourseDeletionInteractionIntegrationTest}, but had no dedicated
 * coverage of their own. Mirrors that test's exact pattern.
 *
 * <p>Per finding 4's own text, part (a) (a service-layer pre-check in
 * course-management raising a specific {@code ConflictException} instead of
 * relying on the generic {@code DataIntegrityViolationException}-to-409
 * mapping) was intentionally NOT implemented in this fix-pass: it would
 * require a new cross-module read method on content-management's {@code api}
 * package consumed FROM course-management, which - combined with
 * content-management already depending on course-management's own {@code
 * CourseLookupApi} - would make course-management and content-management
 * depend on each other's {@code api} packages in both directions. {@code
 * .claude/rules/architecture.md} explicitly says to "avoid circular module
 * dependencies", and V16's own migration comment already documents the
 * current generic-409 behavior as an "intentional, documented tradeoff...
 * until a real cross-module deletion flow is designed" rather than an
 * oversight - so this fix-pass keeps that design decision and only adds test
 * coverage (part (b)) for it, both for the negative (still-attached-material)
 * case and the happy-path regression case.
 */
class MaterialLessonAndModuleDeletionInteractionIntegrationTest extends ContentManagementTestSupport {

	@Test
	void deletingALessonThatStillHasAnAttachedMaterialIsRejectedAndNothingIsRemoved() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("lesson-delete-fk"));
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String teacherToken = loginAndGetToken(host, "teacher@example.test");
		CourseResponse course = createCourseOrFail(host, teacherToken,
				newCourseRequest(uniqueSlug("lesson-delete-fk"), null));
		CourseModuleResponse module = createModuleOrFail(host, teacherToken, course.id(), "Module 1", 1);
		var lesson = createLessonOrFail(host, teacherToken, course.id(), module.id(), "Lesson 1", 1);
		MaterialResponse material = createMaterialOrFail(host, teacherToken, course.id(), module.id(), lesson.id(),
				"Attached Material", pdfFile("attached.pdf"));

		HttpResult<Void> deleteResult = deleteLesson(host, teacherToken, course.id(), module.id(), lesson.id());

		assertThat(deleteResult.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

		// The whole transaction rolled back - lesson and material both still
		// present/unaffected.
		Long lessonCount = jdbcTemplate.queryForObject("SELECT count(*) FROM course_lesson WHERE id = ?", Long.class,
				lesson.id());
		assertThat(lessonCount).isEqualTo(1L);
		HttpResult<MaterialResponse> fetchedMaterial = getMaterial(host, teacherToken, course.id(), module.id(),
				lesson.id(), material.id());
		assertThat(fetchedMaterial.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(fetchedMaterial.getBody().data().id()).isEqualTo(material.id());
	}

	@Test
	void deletingALessonWithNoAttachedMaterialsStillSucceedsAsARegressionGuard() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("lesson-delete-clean"));
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String teacherToken = loginAndGetToken(host, "teacher@example.test");
		CourseResponse course = createCourseOrFail(host, teacherToken,
				newCourseRequest(uniqueSlug("lesson-delete-clean"), null));
		CourseModuleResponse module = createModuleOrFail(host, teacherToken, course.id(), "Module 1", 1);
		var lesson = createLessonOrFail(host, teacherToken, course.id(), module.id(), "Lesson 1", 1);

		HttpResult<Void> deleteResult = deleteLesson(host, teacherToken, course.id(), module.id(), lesson.id());

		assertThat(deleteResult.getStatusCode()).isEqualTo(HttpStatus.OK);
		Long lessonCount = jdbcTemplate.queryForObject("SELECT count(*) FROM course_lesson WHERE id = ?", Long.class,
				lesson.id());
		assertThat(lessonCount).isZero();
	}

	@Test
	void deletingAModuleContainingALessonWithAnAttachedMaterialIsRejectedAndNothingIsRemoved() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("module-delete-fk"));
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String teacherToken = loginAndGetToken(host, "teacher@example.test");
		CourseResponse course = createCourseOrFail(host, teacherToken,
				newCourseRequest(uniqueSlug("module-delete-fk"), null));
		CourseModuleResponse module = createModuleOrFail(host, teacherToken, course.id(), "Module 1", 1);
		var lesson = createLessonOrFail(host, teacherToken, course.id(), module.id(), "Lesson 1", 1);
		MaterialResponse material = createMaterialOrFail(host, teacherToken, course.id(), module.id(), lesson.id(),
				"Attached Material", pdfFile("attached.pdf"));

		HttpResult<Void> deleteResult = deleteModule(host, teacherToken, course.id(), module.id());

		assertThat(deleteResult.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

		// The whole transaction rolled back - module, lesson, and material are
		// all still present/unaffected (the module-level DELETE would
		// otherwise cascade to course_lesson per V14's ON DELETE CASCADE, but
		// that cascade itself is blocked by fk_material_lesson).
		Long moduleCount = jdbcTemplate.queryForObject("SELECT count(*) FROM course_module WHERE id = ?", Long.class,
				module.id());
		assertThat(moduleCount).isEqualTo(1L);
		Long lessonCount = jdbcTemplate.queryForObject("SELECT count(*) FROM course_lesson WHERE id = ?", Long.class,
				lesson.id());
		assertThat(lessonCount).isEqualTo(1L);
		HttpResult<MaterialResponse> fetchedMaterial = getMaterial(host, teacherToken, course.id(), module.id(),
				lesson.id(), material.id());
		assertThat(fetchedMaterial.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(fetchedMaterial.getBody().data().id()).isEqualTo(material.id());
	}

	@Test
	void deletingAModuleWithNoAttachedMaterialsStillSucceedsAsARegressionGuard() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("module-delete-clean"));
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String teacherToken = loginAndGetToken(host, "teacher@example.test");
		CourseResponse course = createCourseOrFail(host, teacherToken,
				newCourseRequest(uniqueSlug("module-delete-clean"), null));
		CourseModuleResponse module = createModuleOrFail(host, teacherToken, course.id(), "Module 1", 1);
		createLessonOrFail(host, teacherToken, course.id(), module.id(), "Lesson 1", 1);

		HttpResult<Void> deleteResult = deleteModule(host, teacherToken, course.id(), module.id());

		assertThat(deleteResult.getStatusCode()).isEqualTo(HttpStatus.OK);
		Long moduleCount = jdbcTemplate.queryForObject("SELECT count(*) FROM course_module WHERE id = ?", Long.class,
				module.id());
		assertThat(moduleCount).isZero();
	}

}
