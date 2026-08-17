package com.lms.contentmanagement.material.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.contentmanagement.ContentManagementTestSupport;
import com.lms.contentmanagement.material.web.dto.MaterialResponse;
import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.tenantmanagement.domain.Tenant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;

/**
 * Testcontainers-backed HTTP coverage for material upload
 * ({@code POST .../materials}, MVP-009). {@code app.content.material.max
 * -file-size-bytes} is overridden to a small value at the class level so the
 * oversized-upload test doesn't need a genuinely huge payload - the fixture
 * byte helpers this class otherwise uses are all well under this limit.
 */
@TestPropertySource(properties = "app.content.material.max-file-size-bytes=1024")
class MaterialUploadIntegrationTest extends ContentManagementTestSupport {

	@Test
	void owningTeacherUploadsAValidPdfSucceedsAndSequenceIncrementsOnASecondUpload() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("upload-pdf"));
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String teacherToken = loginAndGetToken(host, "teacher@example.test");
		CourseResponse course = createCourseOrFail(host, teacherToken, newCourseRequest(uniqueSlug("upload-pdf"), null));
		var module = createModuleOrFail(host, teacherToken, course.id(), "Module 1", 1);
		var lesson = createLessonOrFail(host, teacherToken, course.id(), module.id(), "Lesson 1", 1);

		MaterialResponse first = createMaterialOrFail(host, teacherToken, course.id(), module.id(), lesson.id(),
				"Lecture Slides", pdfFile("slides.pdf"));
		assertThat(first.mimeType()).isEqualTo("application/pdf");
		assertThat(first.sequence()).isEqualTo(1);

		MaterialResponse second = createMaterialOrFail(host, teacherToken, course.id(), module.id(), lesson.id(),
				"Lecture Handout", pdfFile("handout.pdf"));
		assertThat(second.sequence()).isEqualTo(2);
	}

	@Test
	void owningTeacherUploadsAValidImageAndAValidPlainTextNotesFile() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("upload-mixed"));
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String teacherToken = loginAndGetToken(host, "teacher@example.test");
		CourseResponse course = createCourseOrFail(host, teacherToken,
				newCourseRequest(uniqueSlug("upload-mixed"), null));
		var module = createModuleOrFail(host, teacherToken, course.id(), "Module 1", 1);
		var lesson = createLessonOrFail(host, teacherToken, course.id(), module.id(), "Lesson 1", 1);

		MaterialResponse image = createMaterialOrFail(host, teacherToken, course.id(), module.id(), lesson.id(),
				"Diagram", pngFile("diagram.png"));
		assertThat(image.mimeType()).isEqualTo("image/png");

		MaterialResponse notes = createMaterialOrFail(host, teacherToken, course.id(), module.id(), lesson.id(),
				"Notes", textNotesFile("notes.txt"));
		assertThat(notes.mimeType()).isEqualTo("text/plain");
	}

	@Test
	void uploadOfAnExecutableDisguisedAsAPdfReturns415AndPersistsNothing() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("upload-disguised"));
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String teacherToken = loginAndGetToken(host, "teacher@example.test");
		CourseResponse course = createCourseOrFail(host, teacherToken,
				newCourseRequest(uniqueSlug("upload-disguised"), null));
		var module = createModuleOrFail(host, teacherToken, course.id(), "Module 1", 1);
		var lesson = createLessonOrFail(host, teacherToken, course.id(), module.id(), "Lesson 1", 1);

		HttpResult<MaterialResponse> result = createMaterial(host, teacherToken, course.id(), module.id(), lesson.id(),
				"Disguised Executable", disguisedExecutableFile("totally-a.pdf"));

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
		HttpResult<List<MaterialResponse>> listed = listMaterials(host, teacherToken, course.id(), module.id(),
				lesson.id());
		assertThat(listed.getBody().data()).isEmpty();
	}

	@Test
	void uploadExceedingTheConfiguredMaxFileSizeReturns413AndPersistsNothing() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("upload-oversize"));
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String teacherToken = loginAndGetToken(host, "teacher@example.test");
		CourseResponse course = createCourseOrFail(host, teacherToken,
				newCourseRequest(uniqueSlug("upload-oversize"), null));
		var module = createModuleOrFail(host, teacherToken, course.id(), "Module 1", 1);
		var lesson = createLessonOrFail(host, teacherToken, course.id(), module.id(), "Lesson 1", 1);

		HttpResult<MaterialResponse> result = createMaterial(host, teacherToken, course.id(), module.id(), lesson.id(),
				"Too Big", oversizedPdfFile("big.pdf", 2048));

		// Spring Framework 7 exposes both HttpStatus.PAYLOAD_TOO_LARGE (the
		// literal PayloadTooLargeException/GlobalExceptionHandler is defined
		// against) and HttpStatus.CONTENT_TOO_LARGE as separate enum
		// constants for the same numeric code 413 - HttpStatus.valueOf(413),
		// which this HttpResult's status is built from, resolves to the
		// latter.
		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
		assertThat(result.getStatusCode().value()).isEqualTo(413);
		HttpResult<List<MaterialResponse>> listed = listMaterials(host, teacherToken, course.id(), module.id(),
				lesson.id());
		assertThat(listed.getBody().data()).isEmpty();
	}

	@Test
	void uploadByANonOwningTeacherInTheSameTenantReturns403AndPersistsNothing() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("upload-non-owner"));
		seedTenantUser(tenant.getId(), "teacher-a@example.test", RAW_PASSWORD, Role.TEACHER);
		seedTenantUser(tenant.getId(), "teacher-b@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String teacherAToken = loginAndGetToken(host, "teacher-a@example.test");
		String teacherBToken = loginAndGetToken(host, "teacher-b@example.test");
		CourseResponse course = createCourseOrFail(host, teacherAToken,
				newCourseRequest(uniqueSlug("upload-non-owner"), null));
		var module = createModuleOrFail(host, teacherAToken, course.id(), "Module 1", 1);
		var lesson = createLessonOrFail(host, teacherAToken, course.id(), module.id(), "Lesson 1", 1);

		HttpResult<MaterialResponse> result = createMaterial(host, teacherBToken, course.id(), module.id(),
				lesson.id(), "Blocked", pdfFile("blocked.pdf"));

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		HttpResult<List<MaterialResponse>> listed = listMaterials(host, teacherAToken, course.id(), module.id(),
				lesson.id());
		assertThat(listed.getBody().data()).isEmpty();
	}

	/**
	 * Per {@code MaterialAccessGuard}'s own documented anti-enumeration rule
	 * (see its javadoc and {@code MaterialAccessGuardTest}), a Student caller
	 * on any non-VIEW action - including this upload/{@code CREATE_EDIT}
	 * attempt - is denied with {@code NotFoundException} (404), never
	 * {@code AccessDeniedException} (403): a Student must never be able to
	 * distinguish "exists but I lack the right" from "doesn't exist in my
	 * tenant". This is a deliberate discrepancy from a naive "Students never
	 * get CREATE_EDIT -> 403" expectation - the actual, correct behavior per
	 * the guard's own design is 404.
	 */
	@Test
	void uploadByAStudentReturns404PerTheAntiEnumerationRuleAndPersistsNothing() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("upload-student"));
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		seedActiveStudent(tenant.getId(), "student@example.test");
		String host = hostFor(tenant.getSubdomain());
		String teacherToken = loginAndGetToken(host, "teacher@example.test");
		String studentToken = loginAndGetToken(host, "student@example.test");
		CourseResponse course = createCourseOrFail(host, teacherToken,
				newCourseRequest(uniqueSlug("upload-student"), null));
		var module = createModuleOrFail(host, teacherToken, course.id(), "Module 1", 1);
		var lesson = createLessonOrFail(host, teacherToken, course.id(), module.id(), "Lesson 1", 1);
		publish(host, teacherToken, course.id());

		HttpResult<MaterialResponse> result = createMaterial(host, studentToken, course.id(), module.id(), lesson.id(),
				"Blocked", pdfFile("blocked.pdf"));

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		HttpResult<List<MaterialResponse>> listed = listMaterials(host, teacherToken, course.id(), module.id(),
				lesson.id());
		assertThat(listed.getBody().data()).isEmpty();
	}

	@Test
	void uploadAgainstALessonBelongingToADifferentTenantReturns404AndPersistsNothing() {
		Tenant tenantA = seedActiveTenant(uniqueSubdomain("upload-cross-a"));
		Tenant tenantB = seedActiveTenant(uniqueSubdomain("upload-cross-b"));
		TenantUser teacherA = seedTenantUser(tenantA.getId(), "teacher-a@example.test", RAW_PASSWORD, Role.TEACHER);
		seedTenantUser(tenantB.getId(), "teacher-b@example.test", RAW_PASSWORD, Role.TEACHER);
		String hostA = hostFor(tenantA.getSubdomain());
		String hostB = hostFor(tenantB.getSubdomain());
		String tokenA = loginAndGetToken(hostA, "teacher-a@example.test");
		String tokenB = loginAndGetToken(hostB, "teacher-b@example.test");
		CourseResponse courseA = createCourseOrFail(hostA, tokenA, newCourseRequest(uniqueSlug("upload-cross"), null));
		var moduleA = createModuleOrFail(hostA, tokenA, courseA.id(), "Module 1", 1);
		var lessonA = createLessonOrFail(hostA, tokenA, courseA.id(), moduleA.id(), "Lesson 1", 1);

		HttpResult<MaterialResponse> result = createMaterial(hostB, tokenB, courseA.id(), moduleA.id(), lessonA.id(),
				"Cross Tenant", pdfFile("cross.pdf"));

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		HttpResult<List<MaterialResponse>> listed = listMaterials(hostA, tokenA, courseA.id(), moduleA.id(),
				lessonA.id());
		assertThat(listed.getBody().data()).isEmpty();
	}

}
