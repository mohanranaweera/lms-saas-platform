package com.lms.contentmanagement.material.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.contentmanagement.ContentManagementTestSupport;
import com.lms.contentmanagement.material.domain.MaterialVisibility;
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
 * Testcontainers-backed HTTP coverage for material update
 * ({@code PATCH .../materials/{materialId}}, MVP-009): full-resource edit
 * (title/sequence/visibility), the sequence-uniqueness conflict, and
 * cross-tenant enumeration.
 */
class MaterialUpdateIntegrationTest extends ContentManagementTestSupport {

	@Test
	void owningTeacherUpdatesTitleSequenceAndVisibilityAndItIsReflectedOnFetch() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("update-owner"));
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String teacherToken = loginAndGetToken(host, "teacher@example.test");
		CourseResponse course = createCourseOrFail(host, teacherToken, newCourseRequest(uniqueSlug("update-owner"), null));
		CourseModuleResponse module = createModuleOrFail(host, teacherToken, course.id(), "Module 1", 1);
		var lesson = createLessonOrFail(host, teacherToken, course.id(), module.id(), "Lesson 1", 1);
		MaterialResponse material = createMaterialOrFail(host, teacherToken, course.id(), module.id(), lesson.id(),
				"Original Title", pdfFile("original.pdf"));

		HttpResult<MaterialResponse> updateResult = updateMaterial(host, teacherToken, course.id(), module.id(),
				lesson.id(), material.id(), "Updated Title", 1, MaterialVisibility.HIDDEN);
		assertThat(updateResult.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(updateResult.getBody().data().title()).isEqualTo("Updated Title");
		assertThat(updateResult.getBody().data().visibility()).isEqualTo(MaterialVisibility.HIDDEN);

		HttpResult<MaterialResponse> fetched = getMaterial(host, teacherToken, course.id(), module.id(), lesson.id(),
				material.id());
		assertThat(fetched.getBody().data().title()).isEqualTo("Updated Title");
		assertThat(fetched.getBody().data().visibility()).isEqualTo(MaterialVisibility.HIDDEN);
	}

	@Test
	void duplicateSequenceWithinTheSameLessonReturns409() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("update-dup-seq"));
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String teacherToken = loginAndGetToken(host, "teacher@example.test");
		CourseResponse course = createCourseOrFail(host, teacherToken,
				newCourseRequest(uniqueSlug("update-dup-seq"), null));
		CourseModuleResponse module = createModuleOrFail(host, teacherToken, course.id(), "Module 1", 1);
		var lesson = createLessonOrFail(host, teacherToken, course.id(), module.id(), "Lesson 1", 1);
		MaterialResponse first = createMaterialOrFail(host, teacherToken, course.id(), module.id(), lesson.id(),
				"First", pdfFile("first.pdf"));
		MaterialResponse second = createMaterialOrFail(host, teacherToken, course.id(), module.id(), lesson.id(),
				"Second", pdfFile("second.pdf"));

		HttpResult<MaterialResponse> updateResult = updateMaterial(host, teacherToken, course.id(), module.id(),
				lesson.id(), second.id(), second.title(), first.sequence(), MaterialVisibility.VISIBLE);

		assertThat(updateResult.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
	}

	/**
	 * Finding 5 (MVP-009 review) - no integration test previously proved a
	 * non-owning Teacher gets 403 on {@code PATCH .../materials/{materialId}}
	 * (source-confirmed as a coverage gap, not a live bug:
	 * {@code MaterialService#updateMaterial} already calls {@code
	 * materialAccessGuard.requireLessonAccess(..., CREATE_EDIT)} first).
	 */
	@Test
	void nonOwningTeacherUpdateAttemptReturns403AndTheMaterialIsUnchanged() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("update-non-owner"));
		seedTenantUser(tenant.getId(), "teacher-a@example.test", RAW_PASSWORD, Role.TEACHER);
		seedTenantUser(tenant.getId(), "teacher-b@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String teacherAToken = loginAndGetToken(host, "teacher-a@example.test");
		String teacherBToken = loginAndGetToken(host, "teacher-b@example.test");
		CourseResponse course = createCourseOrFail(host, teacherAToken,
				newCourseRequest(uniqueSlug("update-non-owner"), null));
		CourseModuleResponse module = createModuleOrFail(host, teacherAToken, course.id(), "Module 1", 1);
		var lesson = createLessonOrFail(host, teacherAToken, course.id(), module.id(), "Lesson 1", 1);
		MaterialResponse material = createMaterialOrFail(host, teacherAToken, course.id(), module.id(), lesson.id(),
				"Owned By A", pdfFile("owned.pdf"));

		HttpResult<MaterialResponse> result = updateMaterial(host, teacherBToken, course.id(), module.id(),
				lesson.id(), material.id(), "Hijacked", material.sequence(), MaterialVisibility.HIDDEN);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		HttpResult<MaterialResponse> fetched = getMaterial(host, teacherAToken, course.id(), module.id(), lesson.id(),
				material.id());
		assertThat(fetched.getBody().data().title()).isEqualTo(material.title());
		assertThat(fetched.getBody().data().sequence()).isEqualTo(material.sequence());
		assertThat(fetched.getBody().data().visibility()).isEqualTo(material.visibility());
	}

	/**
	 * Finding 5 (MVP-009 review) - the Student-caller side of the same
	 * coverage gap; per {@code MaterialAccessGuard}'s anti-enumeration rule a
	 * Student's non-VIEW action is denied with 404, never 403.
	 */
	@Test
	void studentUpdateAttemptReturns404PerTheAntiEnumerationRuleAndTheMaterialIsUnchanged() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("update-student"));
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		seedActiveStudent(tenant.getId(), "student@example.test");
		String host = hostFor(tenant.getSubdomain());
		String teacherToken = loginAndGetToken(host, "teacher@example.test");
		String studentToken = loginAndGetToken(host, "student@example.test");
		CourseResponse course = createCourseOrFail(host, teacherToken,
				newCourseRequest(uniqueSlug("update-student"), null));
		CourseModuleResponse module = createModuleOrFail(host, teacherToken, course.id(), "Module 1", 1);
		var lesson = createLessonOrFail(host, teacherToken, course.id(), module.id(), "Lesson 1", 1);
		publish(host, teacherToken, course.id());
		MaterialResponse material = createMaterialOrFail(host, teacherToken, course.id(), module.id(), lesson.id(),
				"Protected", pdfFile("protected.pdf"));

		HttpResult<MaterialResponse> result = updateMaterial(host, studentToken, course.id(), module.id(),
				lesson.id(), material.id(), "Hijacked", material.sequence(), MaterialVisibility.HIDDEN);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		HttpResult<MaterialResponse> fetched = getMaterial(host, teacherToken, course.id(), module.id(), lesson.id(),
				material.id());
		assertThat(fetched.getBody().data().title()).isEqualTo(material.title());
		assertThat(fetched.getBody().data().visibility()).isEqualTo(material.visibility());
	}

	@Test
	void crossTenantUpdateAttemptReturns404() {
		Tenant tenantA = seedActiveTenant(uniqueSubdomain("update-cross-a"));
		Tenant tenantB = seedActiveTenant(uniqueSubdomain("update-cross-b"));
		TenantUser teacherA = seedTenantUser(tenantA.getId(), "teacher-a@example.test", RAW_PASSWORD, Role.TEACHER);
		seedTenantUser(tenantB.getId(), "teacher-b@example.test", RAW_PASSWORD, Role.TEACHER);
		String hostA = hostFor(tenantA.getSubdomain());
		String hostB = hostFor(tenantB.getSubdomain());
		String tokenA = loginAndGetToken(hostA, "teacher-a@example.test");
		String tokenB = loginAndGetToken(hostB, "teacher-b@example.test");
		CourseResponse courseA = createCourseOrFail(hostA, tokenA, newCourseRequest(uniqueSlug("update-cross"), null));
		CourseModuleResponse moduleA = createModuleOrFail(hostA, tokenA, courseA.id(), "Module 1", 1);
		var lessonA = createLessonOrFail(hostA, tokenA, courseA.id(), moduleA.id(), "Lesson 1", 1);
		MaterialResponse materialA = createMaterialOrFail(hostA, tokenA, courseA.id(), moduleA.id(), lessonA.id(),
				"Tenant A Material", pdfFile("tenant-a.pdf"));

		HttpResult<MaterialResponse> result = updateMaterial(hostB, tokenB, courseA.id(), moduleA.id(), lessonA.id(),
				materialA.id(), "Hijacked", materialA.sequence(), MaterialVisibility.VISIBLE);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(result.getBody().data()).isNull();

		// M6 - re-verify with the legitimate tenant's own token that the
		// rejected cross-tenant PATCH did not change the material's title or
		// visibility, mirroring MaterialDeletionIntegrationTest's
		// re-verify-unchanged-state-afterward pattern.
		HttpResult<MaterialResponse> fetched = getMaterial(hostA, tokenA, courseA.id(), moduleA.id(), lessonA.id(),
				materialA.id());
		assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(fetched.getBody().data().title()).isEqualTo(materialA.title());
		assertThat(fetched.getBody().data().visibility()).isEqualTo(materialA.visibility());
	}

}
