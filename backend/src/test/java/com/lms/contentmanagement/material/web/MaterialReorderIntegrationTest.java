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
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * M8 (MVP-009 review finding): proves the single-item {@code PATCH
 * .../materials/{materialId}} endpoint genuinely supports the "Move
 * up/Move down" adjacent-swap pattern that {@code
 * frontend/src/lib/courses/reorder.ts}'s {@code reorderAdjacent} already
 * uses for modules/lessons - a 3-step temporary-sequence dance:
 *
 * <ol>
 * <li>Park the mover on an unused temporary sequence.</li>
 * <li>Move the displaced neighbor into the mover's original sequence.</li>
 * <li>Move the mover into the neighbor's original sequence.</li>
 * </ol>
 *
 * translated to material updates over real HTTP/Postgres. This is a plain
 * sequential 3-call proof (matching what a future frontend caller would
 * actually do) - it asserts none of the 3 steps 409s (which a naive
 * two-call swap would, since {@code uq_material_sequence} is {@code UNIQUE
 * (tenant_id, lesson_id, sequence)}) and that the final order is genuinely
 * swapped. It does not assert anything about atomicity across the 3 calls.
 *
 * <p><b>MVP-009 review finding 6:</b> also covers the boundary no-op cases
 * (moving the first item "up" / the last item "down" - i.e. re-submitting a
 * material's own current sequence when it is already at that edge of the
 * list - must succeed, not error, so the keyboard "Move up"/"Move down"
 * control never appears broken at the boundary). The plan §18 test list's
 * remaining case - a cross-tenant/cross-lesson material id in a reorder
 * request returning {@code 404} - is deliberately NOT duplicated here: it is
 * already meaningfully covered by {@code
 * MaterialUpdateIntegrationTest#crossTenantUpdateAttemptReturns404}, since
 * this module's reorder mechanism IS the single-material {@code PATCH}
 * endpoint (there is no separate bulk {@code /reorder} endpoint to test
 * independently).
 */
class MaterialReorderIntegrationTest extends ContentManagementTestSupport {

	@Test
	void movingTheFirstMaterialToItsOwnPositionIsABoundaryNoOpThatSucceeds() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("material-reorder-boundary-first"));
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String teacherToken = loginAndGetToken(host, "teacher@example.test");
		CourseResponse course = createCourseOrFail(host, teacherToken,
				newCourseRequest(uniqueSlug("material-reorder-boundary-first"), null));
		CourseModuleResponse module = createModuleOrFail(host, teacherToken, course.id(), "Module 1", 1);
		var lesson = createLessonOrFail(host, teacherToken, course.id(), module.id(), "Lesson 1", 1);
		MaterialResponse first = createMaterialOrFail(host, teacherToken, course.id(), module.id(), lesson.id(),
				"First Material", pdfFile("first.pdf"));
		createMaterialOrFail(host, teacherToken, course.id(), module.id(), lesson.id(), "Second Material",
				pdfFile("second.pdf"));

		// "Move up" on the already-first item: re-submitting its own sequence
		// (1) is the no-op equivalent - must succeed, not 409/error.
		HttpResult<MaterialResponse> result = updateMaterial(host, teacherToken, course.id(), module.id(), lesson.id(),
				first.id(), first.title(), first.sequence(), MaterialVisibility.VISIBLE);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	void movingTheLastMaterialToItsOwnPositionIsABoundaryNoOpThatSucceeds() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("material-reorder-boundary-last"));
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String teacherToken = loginAndGetToken(host, "teacher@example.test");
		CourseResponse course = createCourseOrFail(host, teacherToken,
				newCourseRequest(uniqueSlug("material-reorder-boundary-last"), null));
		CourseModuleResponse module = createModuleOrFail(host, teacherToken, course.id(), "Module 1", 1);
		var lesson = createLessonOrFail(host, teacherToken, course.id(), module.id(), "Lesson 1", 1);
		createMaterialOrFail(host, teacherToken, course.id(), module.id(), lesson.id(), "First Material",
				pdfFile("first.pdf"));
		MaterialResponse last = createMaterialOrFail(host, teacherToken, course.id(), module.id(), lesson.id(),
				"Second Material", pdfFile("second.pdf"));

		// "Move down" on the already-last item: re-submitting its own sequence
		// (2) is the no-op equivalent - must succeed, not 409/error.
		HttpResult<MaterialResponse> result = updateMaterial(host, teacherToken, course.id(), module.id(), lesson.id(),
				last.id(), last.title(), last.sequence(), MaterialVisibility.VISIBLE);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	void moveUpMoveDownStyleThreeStepSwapReordersTwoAdjacentMaterials() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("material-reorder"));
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String teacherToken = loginAndGetToken(host, "teacher@example.test");
		CourseResponse course = createCourseOrFail(host, teacherToken,
				newCourseRequest(uniqueSlug("material-reorder"), null));
		CourseModuleResponse module = createModuleOrFail(host, teacherToken, course.id(), "Module 1", 1);
		var lesson = createLessonOrFail(host, teacherToken, course.id(), module.id(), "Lesson 1", 1);
		MaterialResponse materialAtSeq1 = createMaterialOrFail(host, teacherToken, course.id(), module.id(),
				lesson.id(), "First Material", pdfFile("first.pdf"));
		MaterialResponse materialAtSeq2 = createMaterialOrFail(host, teacherToken, course.id(), module.id(),
				lesson.id(), "Second Material", pdfFile("second.pdf"));
		assertThat(materialAtSeq1.sequence()).isEqualTo(1);
		assertThat(materialAtSeq2.sequence()).isEqualTo(2);

		int temporarySequence = 1000;

		// Step 1: park the mover (materialAtSeq1) on an unused temporary sequence.
		HttpResult<MaterialResponse> step1 = updateMaterial(host, teacherToken, course.id(), module.id(), lesson.id(),
				materialAtSeq1.id(), materialAtSeq1.title(), temporarySequence, MaterialVisibility.VISIBLE);
		assertThat(step1.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(step1.getStatusCode()).isNotEqualTo(HttpStatus.CONFLICT);

		// Step 2: move the displaced neighbor (materialAtSeq2) into the mover's
		// original sequence (1).
		HttpResult<MaterialResponse> step2 = updateMaterial(host, teacherToken, course.id(), module.id(), lesson.id(),
				materialAtSeq2.id(), materialAtSeq2.title(), materialAtSeq1.sequence(), MaterialVisibility.VISIBLE);
		assertThat(step2.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(step2.getStatusCode()).isNotEqualTo(HttpStatus.CONFLICT);

		// Step 3: move the mover (materialAtSeq1) into the neighbor's original
		// sequence (2).
		HttpResult<MaterialResponse> step3 = updateMaterial(host, teacherToken, course.id(), module.id(), lesson.id(),
				materialAtSeq1.id(), materialAtSeq1.title(), materialAtSeq2.sequence(), MaterialVisibility.VISIBLE);
		assertThat(step3.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(step3.getStatusCode()).isNotEqualTo(HttpStatus.CONFLICT);

		HttpResult<List<MaterialResponse>> listed = listMaterials(host, teacherToken, course.id(), module.id(),
				lesson.id());
		assertThat(listed.getStatusCode()).isEqualTo(HttpStatus.OK);
		List<MaterialResponse> sorted = listed.getBody()
			.data()
			.stream()
			.sorted((a, b) -> Integer.compare(a.sequence(), b.sequence()))
			.toList();
		assertThat(sorted).extracting(MaterialResponse::id)
			.containsExactly(materialAtSeq2.id(), materialAtSeq1.id());
	}

}
