package com.lms.contentmanagement.material.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.contentmanagement.ContentManagementTestSupport;
import com.lms.contentmanagement.material.domain.MaterialVisibility;
import com.lms.contentmanagement.material.web.dto.MaterialDownloadUrlResponse;
import com.lms.contentmanagement.material.web.dto.MaterialResponse;
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
 * Testcontainers-backed HTTP coverage for material fetch (list + single-item
 * GET) visibility rules (MVP-009): Student VISIBLE/HIDDEN filtering, the
 * Student anti-enumeration rule for unpublished courses, and cross-tenant
 * material-id enumeration.
 */
class MaterialFetchVisibilityIntegrationTest extends ContentManagementTestSupport {

	@Test
	void studentListingMaterialsInAPublishedCourseSeesOnlyVisibleNotHiddenMaterials() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("fetch-visibility"));
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		seedActiveStudent(tenant.getId(), "student@example.test");
		String host = hostFor(tenant.getSubdomain());
		String teacherToken = loginAndGetToken(host, "teacher@example.test");
		String studentToken = loginAndGetToken(host, "student@example.test");
		CourseResponse course = createCourseOrFail(host, teacherToken,
				newCourseRequest(uniqueSlug("fetch-visibility"), null));
		CourseModuleResponse module = createModuleOrFail(host, teacherToken, course.id(), "Module 1", 1);
		var lesson = createLessonOrFail(host, teacherToken, course.id(), module.id(), "Lesson 1", 1);
		publish(host, teacherToken, course.id());

		MaterialResponse visible = createMaterialOrFail(host, teacherToken, course.id(), module.id(), lesson.id(),
				"Visible Material", pdfFile("visible.pdf"));
		MaterialResponse hidden = createMaterialOrFail(host, teacherToken, course.id(), module.id(), lesson.id(),
				"Hidden Material", pdfFile("hidden.pdf"));
		updateMaterial(host, teacherToken, course.id(), module.id(), lesson.id(), hidden.id(), hidden.title(),
				hidden.sequence(), MaterialVisibility.HIDDEN);

		HttpResult<List<MaterialResponse>> listed = listMaterials(host, studentToken, course.id(), module.id(),
				lesson.id());

		assertThat(listed.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(listed.getBody().data()).extracting(MaterialResponse::id)
			.containsExactly(visible.id())
			.doesNotContain(hidden.id());
	}

	@Test
	void studentFetchingAHiddenMaterialDirectlyByIdReturns404NotTheMaterial() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("fetch-hidden-single"));
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		seedActiveStudent(tenant.getId(), "student@example.test");
		String host = hostFor(tenant.getSubdomain());
		String teacherToken = loginAndGetToken(host, "teacher@example.test");
		String studentToken = loginAndGetToken(host, "student@example.test");
		CourseResponse course = createCourseOrFail(host, teacherToken,
				newCourseRequest(uniqueSlug("fetch-hidden-single"), null));
		CourseModuleResponse module = createModuleOrFail(host, teacherToken, course.id(), "Module 1", 1);
		var lesson = createLessonOrFail(host, teacherToken, course.id(), module.id(), "Lesson 1", 1);
		publish(host, teacherToken, course.id());
		MaterialResponse hidden = createMaterialOrFail(host, teacherToken, course.id(), module.id(), lesson.id(),
				"Hidden Material", pdfFile("hidden.pdf"));
		updateMaterial(host, teacherToken, course.id(), module.id(), lesson.id(), hidden.id(), hidden.title(),
				hidden.sequence(), MaterialVisibility.HIDDEN);

		HttpResult<MaterialResponse> result = getMaterial(host, studentToken, course.id(), module.id(), lesson.id(),
				hidden.id());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void studentListingAndFetchingMaterialsForANonPublishedCourseBothReturn404() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("fetch-unpublished"));
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		seedActiveStudent(tenant.getId(), "student@example.test");
		String host = hostFor(tenant.getSubdomain());
		String teacherToken = loginAndGetToken(host, "teacher@example.test");
		String studentToken = loginAndGetToken(host, "student@example.test");
		// Left in the default DRAFT status - never published.
		CourseResponse course = createCourseOrFail(host, teacherToken,
				newCourseRequest(uniqueSlug("fetch-unpublished"), null));
		CourseModuleResponse module = createModuleOrFail(host, teacherToken, course.id(), "Module 1", 1);
		var lesson = createLessonOrFail(host, teacherToken, course.id(), module.id(), "Lesson 1", 1);
		MaterialResponse material = createMaterialOrFail(host, teacherToken, course.id(), module.id(), lesson.id(),
				"Some Material", pdfFile("material.pdf"));

		HttpResult<List<MaterialResponse>> listed = listMaterials(host, studentToken, course.id(), module.id(),
				lesson.id());
		HttpResult<MaterialResponse> fetched = getMaterial(host, studentToken, course.id(), module.id(), lesson.id(),
				material.id());

		assertThat(listed.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void staffFromTenantARequestingAMaterialIdBelongingToTenantBReceives404WithNoDataLeaked() {
		Tenant tenantA = seedActiveTenant(uniqueSubdomain("fetch-cross-a"));
		Tenant tenantB = seedActiveTenant(uniqueSubdomain("fetch-cross-b"));
		seedTenantUser(tenantA.getId(), "admin-a@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacherB = seedTenantUser(tenantB.getId(), "teacher-b@example.test", RAW_PASSWORD, Role.TEACHER);
		String hostA = hostFor(tenantA.getSubdomain());
		String hostB = hostFor(tenantB.getSubdomain());
		String tokenA = loginAndGetToken(hostA, "admin-a@example.test");
		String tokenB = loginAndGetToken(hostB, "teacher-b@example.test");
		CourseResponse courseB = createCourseOrFail(hostB, tokenB, newCourseRequest(uniqueSlug("fetch-cross"), null));
		CourseModuleResponse moduleB = createModuleOrFail(hostB, tokenB, courseB.id(), "Module 1", 1);
		var lessonB = createLessonOrFail(hostB, tokenB, courseB.id(), moduleB.id(), "Lesson 1", 1);
		MaterialResponse materialB = createMaterialOrFail(hostB, tokenB, courseB.id(), moduleB.id(), lessonB.id(),
				"Tenant B Material", pdfFile("tenant-b.pdf"));

		// Tenant A staff addresses the SAME path segments (course/module
		// /lesson/material ids) that are real in tenant B, from tenant A's
		// own host/token.
		HttpResult<MaterialResponse> result = getMaterial(hostA, tokenA, courseB.id(), moduleB.id(), lessonB.id(),
				materialB.id());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(result.getBody().data()).isNull();
	}

	@Test
	void aDeletedMaterialsIdIsNoLongerFetchableAfterASuccessfulDelete() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("fetch-after-delete"));
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String teacherToken = loginAndGetToken(host, "teacher@example.test");
		CourseResponse course = createCourseOrFail(host, teacherToken,
				newCourseRequest(uniqueSlug("fetch-after-delete"), null));
		CourseModuleResponse module = createModuleOrFail(host, teacherToken, course.id(), "Module 1", 1);
		var lesson = createLessonOrFail(host, teacherToken, course.id(), module.id(), "Lesson 1", 1);
		MaterialResponse material = createMaterialOrFail(host, teacherToken, course.id(), module.id(), lesson.id(),
				"To Delete", pdfFile("to-delete.pdf"));

		HttpResult<Void> deleteResult = deleteMaterial(host, teacherToken, course.id(), module.id(), lesson.id(),
				material.id());
		assertThat(deleteResult.getStatusCode()).isEqualTo(HttpStatus.OK);

		HttpResult<MaterialResponse> fetched = getMaterial(host, teacherToken, course.id(), module.id(), lesson.id(),
				material.id());
		assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	// ------------------------------------------------------------------
	// /download-url - independently re-authorized protected-content access
	// (security-review finding: this endpoint had no dedicated coverage even
	// though MaterialService#getDownloadUrl runs the same guard/visibility
	// checks as getMaterial).
	// ------------------------------------------------------------------

	@Test
	void studentCanObtainADownloadUrlForAVisibleMaterialInAPublishedCourse() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("download-url-visible"));
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		seedActiveStudent(tenant.getId(), "student@example.test");
		String host = hostFor(tenant.getSubdomain());
		String teacherToken = loginAndGetToken(host, "teacher@example.test");
		String studentToken = loginAndGetToken(host, "student@example.test");
		CourseResponse course = createCourseOrFail(host, teacherToken,
				newCourseRequest(uniqueSlug("download-url-visible"), null));
		CourseModuleResponse module = createModuleOrFail(host, teacherToken, course.id(), "Module 1", 1);
		var lesson = createLessonOrFail(host, teacherToken, course.id(), module.id(), "Lesson 1", 1);
		publish(host, teacherToken, course.id());
		MaterialResponse material = createMaterialOrFail(host, teacherToken, course.id(), module.id(), lesson.id(),
				"Visible Material", pdfFile("visible.pdf"));

		HttpResult<MaterialDownloadUrlResponse> result = getDownloadUrl(host, studentToken, course.id(), module.id(),
				lesson.id(), material.id());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(result.getBody().data().url()).isNotBlank();
		assertThat(result.getBody().data().expiresAt()).isNotNull();
	}

	@Test
	void studentRequestingADownloadUrlForAHiddenMaterialReturns404NotASignedUrl() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("download-url-hidden"));
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		seedActiveStudent(tenant.getId(), "student@example.test");
		String host = hostFor(tenant.getSubdomain());
		String teacherToken = loginAndGetToken(host, "teacher@example.test");
		String studentToken = loginAndGetToken(host, "student@example.test");
		CourseResponse course = createCourseOrFail(host, teacherToken,
				newCourseRequest(uniqueSlug("download-url-hidden"), null));
		CourseModuleResponse module = createModuleOrFail(host, teacherToken, course.id(), "Module 1", 1);
		var lesson = createLessonOrFail(host, teacherToken, course.id(), module.id(), "Lesson 1", 1);
		publish(host, teacherToken, course.id());
		MaterialResponse hidden = createMaterialOrFail(host, teacherToken, course.id(), module.id(), lesson.id(),
				"Hidden Material", pdfFile("hidden.pdf"));
		updateMaterial(host, teacherToken, course.id(), module.id(), lesson.id(), hidden.id(), hidden.title(),
				hidden.sequence(), MaterialVisibility.HIDDEN);

		HttpResult<MaterialDownloadUrlResponse> result = getDownloadUrl(host, studentToken, course.id(), module.id(),
				lesson.id(), hidden.id());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(result.getBody().data()).isNull();
	}

	@Test
	void downloadUrlForACrossTenantMaterialIdReturns404WithNoUrlLeaked() {
		Tenant tenantA = seedActiveTenant(uniqueSubdomain("download-url-cross-a"));
		Tenant tenantB = seedActiveTenant(uniqueSubdomain("download-url-cross-b"));
		seedTenantUser(tenantA.getId(), "admin-a@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacherB = seedTenantUser(tenantB.getId(), "teacher-b@example.test", RAW_PASSWORD, Role.TEACHER);
		String hostA = hostFor(tenantA.getSubdomain());
		String hostB = hostFor(tenantB.getSubdomain());
		String tokenA = loginAndGetToken(hostA, "admin-a@example.test");
		String tokenB = loginAndGetToken(hostB, "teacher-b@example.test");
		CourseResponse courseB = createCourseOrFail(hostB, tokenB,
				newCourseRequest(uniqueSlug("download-url-cross"), null));
		CourseModuleResponse moduleB = createModuleOrFail(hostB, tokenB, courseB.id(), "Module 1", 1);
		var lessonB = createLessonOrFail(hostB, tokenB, courseB.id(), moduleB.id(), "Lesson 1", 1);
		MaterialResponse materialB = createMaterialOrFail(hostB, tokenB, courseB.id(), moduleB.id(), lessonB.id(),
				"Tenant B Material", pdfFile("tenant-b.pdf"));

		HttpResult<MaterialDownloadUrlResponse> result = getDownloadUrl(hostA, tokenA, courseB.id(), moduleB.id(),
				lessonB.id(), materialB.id());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(result.getBody().data()).isNull();
	}

	// ------------------------------------------------------------------
	// L4 - the path-segment-consistency check in MaterialAccessGuard#
	// requireLessonAccess (resolved.moduleId().equals(moduleId) &&
	// resolved.courseId().equals(courseId)) is currently only unit-tested.
	// These prove it end-to-end: a request whose PATH names courseA/moduleA
	// but whose lessonId path segment actually belongs to a DIFFERENT
	// course/module (lessonB's real parent is moduleB/courseB) must be
	// rejected with 404, regardless of whether the same or a different
	// Teacher owns the real parent chain.
	// ------------------------------------------------------------------

	@Test
	void aLessonIdPathSegmentWhoseRealParentIsADifferentCourseModuleOwnedByTheSameTeacherIsRejectedWith404() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("path-mismatch-same-teacher"));
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String teacherToken = loginAndGetToken(host, "teacher@example.test");
		CourseResponse courseA = createCourseOrFail(host, teacherToken,
				newCourseRequest(uniqueSlug("path-mismatch-a"), null));
		CourseModuleResponse moduleA = createModuleOrFail(host, teacherToken, courseA.id(), "Module A", 1);
		createLessonOrFail(host, teacherToken, courseA.id(), moduleA.id(), "Lesson A", 1);
		CourseResponse courseB = createCourseOrFail(host, teacherToken,
				newCourseRequest(uniqueSlug("path-mismatch-b"), null));
		CourseModuleResponse moduleB = createModuleOrFail(host, teacherToken, courseB.id(), "Module B", 1);
		var lessonB = createLessonOrFail(host, teacherToken, courseB.id(), moduleB.id(), "Lesson B", 1);
		MaterialResponse materialB = createMaterialOrFail(host, teacherToken, courseB.id(), moduleB.id(), lessonB.id(),
				"Tenant Material B", pdfFile("material-b.pdf"));

		// PATH says courseA/moduleA, but lessonB's real parent is
		// moduleB/courseB - the caller genuinely owns lessonB, so this is not
		// an ownership failure, only a path-vs-real-parent-chain mismatch.
		HttpResult<List<MaterialResponse>> listed = listMaterials(host, teacherToken, courseA.id(), moduleA.id(),
				lessonB.id());
		assertThat(listed.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

		HttpResult<MaterialResponse> fetched = getMaterial(host, teacherToken, courseA.id(), moduleA.id(),
				lessonB.id(), materialB.id());
		assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(fetched.getBody().data()).isNull();
	}

	@Test
	void aLessonIdPathSegmentWhoseRealParentIsACourseOwnedByADifferentTeacherIsRejectedWith404() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("path-mismatch-diff-teacher"));
		TenantUser teacherA = seedTenantUser(tenant.getId(), "teacher-a@example.test", RAW_PASSWORD, Role.TEACHER);
		TenantUser teacherB = seedTenantUser(tenant.getId(), "teacher-b@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String teacherAToken = loginAndGetToken(host, "teacher-a@example.test");
		String teacherBToken = loginAndGetToken(host, "teacher-b@example.test");
		CourseResponse courseA = createCourseOrFail(host, teacherAToken,
				newCourseRequest(uniqueSlug("path-mismatch-diff-a"), null));
		CourseModuleResponse moduleA = createModuleOrFail(host, teacherAToken, courseA.id(), "Module A", 1);
		createLessonOrFail(host, teacherAToken, courseA.id(), moduleA.id(), "Lesson A", 1);
		CourseResponse courseB = createCourseOrFail(host, teacherBToken,
				newCourseRequest(uniqueSlug("path-mismatch-diff-b"), null));
		CourseModuleResponse moduleB = createModuleOrFail(host, teacherBToken, courseB.id(), "Module B", 1);
		var lessonB = createLessonOrFail(host, teacherBToken, courseB.id(), moduleB.id(), "Lesson B", 1);
		createMaterialOrFail(host, teacherBToken, courseB.id(), moduleB.id(), lessonB.id(), "Tenant Material B",
				pdfFile("material-b.pdf"));

		// Teacher A addresses courseA/moduleA (which A genuinely owns) with
		// lessonB's id, whose real parent chain (moduleB/courseB) belongs to
		// Teacher B, not Teacher A - proving this is a genuine
		// path-vs-real-parent-chain check, not merely an ownership check
		// (Teacher A would already be denied on ownership grounds alone if
		// this were addressed via courseB/moduleB directly).
		HttpResult<List<MaterialResponse>> listed = listMaterials(host, teacherAToken, courseA.id(), moduleA.id(),
				lessonB.id());
		assertThat(listed.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void downloadUrlForANonPublishedCoursesMaterialAsAStudentReturns404() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("download-url-unpublished"));
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		seedActiveStudent(tenant.getId(), "student@example.test");
		String host = hostFor(tenant.getSubdomain());
		String teacherToken = loginAndGetToken(host, "teacher@example.test");
		String studentToken = loginAndGetToken(host, "student@example.test");
		// Left in the default DRAFT status - never published.
		CourseResponse course = createCourseOrFail(host, teacherToken,
				newCourseRequest(uniqueSlug("download-url-unpublished"), null));
		CourseModuleResponse module = createModuleOrFail(host, teacherToken, course.id(), "Module 1", 1);
		var lesson = createLessonOrFail(host, teacherToken, course.id(), module.id(), "Lesson 1", 1);
		MaterialResponse material = createMaterialOrFail(host, teacherToken, course.id(), module.id(), lesson.id(),
				"Some Material", pdfFile("material.pdf"));

		HttpResult<MaterialDownloadUrlResponse> result = getDownloadUrl(host, studentToken, course.id(), module.id(),
				lesson.id(), material.id());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

}
