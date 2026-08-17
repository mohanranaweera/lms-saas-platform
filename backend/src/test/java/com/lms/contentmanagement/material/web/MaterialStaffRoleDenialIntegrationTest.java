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
 * Testcontainers-backed HTTP coverage for MVP-009 review finding 3 (plan §5
 * AC #13): "Given Course Coordinator or Read-only Auditor (both {@code
 * MATERIALS} {@code VIEW}-only per {@link
 * com.lms.identityaccessservice.service.PermissionCheckServiceImpl}'s
 * matrix) attempts {@code CREATE_EDIT}/{@code DELETE}, rejected 403". Only a
 * mocked {@code doThrow} unit test existed for this previously ({@code
 * MaterialAccessGuardTest#staffRoleWithoutTheMaterialsGrantIsDenied}), which
 * proves guard -> {@code PermissionCheckService} delegation but not the real
 * grant matrix end-to-end. These tests authenticate as real Course
 * Coordinator/Read-only Auditor users and go through the actual {@code
 * PermissionCheckService}/{@code DomainArea.MATERIALS} grant matrix over real
 * HTTP.
 */
class MaterialStaffRoleDenialIntegrationTest extends ContentManagementTestSupport {

	@Test
	void courseCoordinatorIsDeniedUpdateAndDeleteOnMaterialsDespiteHoldingOtherCourseGrants() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("materials-role-coordinator"));
		seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		seedTenantUser(tenant.getId(), "coordinator@example.test", RAW_PASSWORD, Role.COURSE_COORDINATOR);
		String host = hostFor(tenant.getSubdomain());
		String teacherToken = loginAndGetToken(host, "teacher@example.test");
		String coordinatorToken = loginAndGetToken(host, "coordinator@example.test");
		CourseResponse course = createCourseOrFail(host, teacherToken,
				newCourseRequest(uniqueSlug("materials-role-coordinator"), null));
		CourseModuleResponse module = createModuleOrFail(host, teacherToken, course.id(), "Module 1", 1);
		var lesson = createLessonOrFail(host, teacherToken, course.id(), module.id(), "Lesson 1", 1);
		MaterialResponse material = createMaterialOrFail(host, teacherToken, course.id(), module.id(), lesson.id(),
				"Original Title", pdfFile("original.pdf"));

		// Negative: Course Coordinator holds CREATE_EDIT/APPROVE on
		// DomainArea.COURSES but only VIEW on DomainArea.MATERIALS - both
		// mutation actions must be rejected.
		HttpResult<MaterialResponse> updateResult = updateMaterial(host, coordinatorToken, course.id(), module.id(),
				lesson.id(), material.id(), "Hijacked Title", material.sequence(), MaterialVisibility.HIDDEN);
		assertThat(updateResult.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

		HttpResult<Void> deleteResult = deleteMaterial(host, coordinatorToken, course.id(), module.id(), lesson.id(),
				material.id());
		assertThat(deleteResult.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

		// Neither mutation actually applied - re-verify with the owning
		// Teacher's own token.
		HttpResult<MaterialResponse> fetched = getMaterial(host, teacherToken, course.id(), module.id(), lesson.id(),
				material.id());
		assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(fetched.getBody().data().title()).isEqualTo("Original Title");
		assertThat(fetched.getBody().data().visibility()).isEqualTo(MaterialVisibility.VISIBLE);
	}

	@Test
	void readOnlyAuditorIsDeniedUpdateAndDeleteOnMaterials() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("materials-role-auditor"));
		seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		seedTenantUser(tenant.getId(), "auditor@example.test", RAW_PASSWORD, Role.READ_ONLY_AUDITOR);
		String host = hostFor(tenant.getSubdomain());
		String teacherToken = loginAndGetToken(host, "teacher@example.test");
		String auditorToken = loginAndGetToken(host, "auditor@example.test");
		CourseResponse course = createCourseOrFail(host, teacherToken,
				newCourseRequest(uniqueSlug("materials-role-auditor"), null));
		CourseModuleResponse module = createModuleOrFail(host, teacherToken, course.id(), "Module 1", 1);
		var lesson = createLessonOrFail(host, teacherToken, course.id(), module.id(), "Lesson 1", 1);
		MaterialResponse material = createMaterialOrFail(host, teacherToken, course.id(), module.id(), lesson.id(),
				"Original Title", pdfFile("original.pdf"));

		HttpResult<MaterialResponse> updateResult = updateMaterial(host, auditorToken, course.id(), module.id(),
				lesson.id(), material.id(), "Hijacked Title", material.sequence(), MaterialVisibility.HIDDEN);
		assertThat(updateResult.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

		HttpResult<Void> deleteResult = deleteMaterial(host, auditorToken, course.id(), module.id(), lesson.id(),
				material.id());
		assertThat(deleteResult.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

		HttpResult<MaterialResponse> fetched = getMaterial(host, teacherToken, course.id(), module.id(), lesson.id(),
				material.id());
		assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(fetched.getBody().data().title()).isEqualTo("Original Title");
		assertThat(fetched.getBody().data().visibility()).isEqualTo(MaterialVisibility.VISIBLE);
	}

}
