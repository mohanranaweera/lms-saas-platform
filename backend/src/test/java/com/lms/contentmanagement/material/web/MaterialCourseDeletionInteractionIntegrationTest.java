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
 * Testcontainers-backed HTTP coverage for M2 (MVP-009 review finding):
 * proves that deleting a COURSE (not the lesson itself) which still has a
 * lesson with an attached material is rejected, and that the whole delete
 * transaction rolls back - the material and its lesson both survive
 * unaffected. This is the intentional, documented tradeoff named explicitly
 * by V16's migration comment ({@code fk_material_lesson} deliberately has no
 * {@code ON DELETE CASCADE}, unlike {@code course_module}/{@code
 * course_lesson}'s own V14 FKs) and by {@code CourseService#deleteCourse}'s
 * own javadoc: {@code course} -> {@code course_module} -> {@code
 * course_lesson} cascades at the DB level (V14), but a still-attached {@code
 * material} row blocks that cascade with a foreign key violation, which
 * {@code GlobalExceptionHandler} maps to an HTTP 409.
 */
class MaterialCourseDeletionInteractionIntegrationTest extends ContentManagementTestSupport {

	@Test
	void deletingACourseWhoseLessonStillHasAnAttachedMaterialIsRejectedAndNothingIsRemoved() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("course-delete-fk"));
		// Course deletion is a Tenant Admin-only action (CourseAccessGuard:
		// Teachers can never delete a course, only reassign/edit/publish
		// their own) - the material/lesson are still owned/created by the
		// Teacher, matching the normal division of responsibility.
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String teacherToken = loginAndGetToken(host, "teacher@example.test");
		CourseResponse course = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug("course-delete-fk"), teacher.getId()));
		CourseModuleResponse module = createModuleOrFail(host, teacherToken, course.id(), "Module 1", 1);
		var lesson = createLessonOrFail(host, teacherToken, course.id(), module.id(), "Lesson 1", 1);
		MaterialResponse material = createMaterialOrFail(host, teacherToken, course.id(), module.id(), lesson.id(),
				"Attached Material", pdfFile("attached.pdf"));

		HttpResult<Void> deleteResult = deleteCourse(host, adminToken, course.id());

		assertThat(deleteResult.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

		// The whole transaction rolled back - course, module, lesson, and
		// material are all still present/unaffected.
		HttpResult<CourseResponse> fetchedCourse = getCourse(host, teacherToken, course.id());
		assertThat(fetchedCourse.getStatusCode()).isEqualTo(HttpStatus.OK);

		HttpResult<MaterialResponse> fetchedMaterial = getMaterial(host, teacherToken, course.id(), module.id(),
				lesson.id(), material.id());
		assertThat(fetchedMaterial.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(fetchedMaterial.getBody().data().id()).isEqualTo(material.id());
	}

}
