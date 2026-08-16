package com.lms.coursemanagement.course.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.lms.common.api.PageResponse;
import com.lms.coursemanagement.CourseManagementTestSupport;
import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.web.dto.CourseCreateRequest;
import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.coursemanagement.course.web.dto.CourseUpdateRequest;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.tenantmanagement.domain.Tenant;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Testcontainers-backed coverage for Course Management (MVP-008)'s
 * course-level endpoints: {@code /api/v1/courses/**}. Modeled on {@code
 * usermanagement.staff.web.StaffManagementIntegrationTest} for style (one
 * explicit test method per role/scenario, real two-thread race test for the
 * tenant-scoped slug UNIQUE constraint).
 *
 * <p>Not {@code @Transactional} - matches {@code StaffManagementIntegrationTest}'s
 * rationale (MockMvc dispatches on a separate thread; the race test needs
 * genuinely independent, committed transactions).
 */
class CourseManagementIntegrationTest extends CourseManagementTestSupport {

	// ------------------------------------------------------------------
	// Happy path.
	// ------------------------------------------------------------------

	@Test
	void tenantAdminCreatesACourseWithASelfSuppliedTeacherIdAndItIsPersistedAndReadable() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("course-create"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "admin@example.test");
		String slug = uniqueSlug("create");

		CourseResponse created = createCourseOrFail(host, token, newCourseRequest(slug, teacher.getId()));

		assertThat(created.slug()).isEqualTo(slug);
		assertThat(created.teacherId()).isEqualTo(teacher.getId());
		assertThat(created.status()).isEqualTo(CourseStatus.DRAFT);

		HttpResult<CourseResponse> getResult = getCourse(host, token, created.id());
		assertThat(getResult.getStatusCode()).isEqualTo(HttpStatus.OK);
		// Field-by-field, not full record equality: the in-memory response
		// from the create call carries Java's (sub-microsecond) Instant
		// precision, while this re-read comes back through Postgres'
		// TIMESTAMPTZ (microsecond precision) - a cosmetic round-trip
		// difference, not a behavioral one.
		CourseResponse reread = getResult.getBody().data();
		assertThat(reread.id()).isEqualTo(created.id());
		assertThat(reread.slug()).isEqualTo(created.slug());
		assertThat(reread.teacherId()).isEqualTo(created.teacherId());
		assertThat(reread.price()).isEqualByComparingTo(created.price());
		assertThat(reread.status()).isEqualTo(created.status());
	}

	@Test
	void teacherCreatingACourseHasTheirOwnIdForcedAsTeacherIdRegardlessOfAnySuppliedValue() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("course-teacher-create"));
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		TenantUser otherTeacher = seedTenantUser(tenant.getId(), "other-teacher@example.test", RAW_PASSWORD,
				Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "teacher@example.test");

		CourseResponse created = createCourseOrFail(host, token,
				newCourseRequest(uniqueSlug("teacher-create"), otherTeacher.getId()));

		assertThat(created.teacherId()).isEqualTo(teacher.getId());
		assertThat(created.teacherId()).isNotEqualTo(otherTeacher.getId());
	}

	// ------------------------------------------------------------------
	// Slug uniqueness: positive / negative / concurrent race.
	// ------------------------------------------------------------------

	@Test
	void duplicateSlugWithinTenantViaTheRealEndpointReturns409() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("course-dup-slug"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "admin@example.test");
		String slug = uniqueSlug("dup");
		createCourseOrFail(host, token, newCourseRequest(slug, teacher.getId()));

		HttpResult<CourseResponse> second = createCourse(host, token, newCourseRequest(slug, teacher.getId()));

		assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(second.getBody().success()).isFalse();
		assertThat(second.getBody().error().code()).isEqualTo("CONFLICT");
	}

	/**
	 * {@link CourseManagementTestSupport#editRequestFor} always echoes a
	 * course's own current slug, so every other PATCH-based test in this
	 * suite exercises only the "slug unchanged" path - this test is the one
	 * exercising duplicate-slug-on-*edit* specifically (as opposed to
	 * duplicate-slug-on-create, covered above), by explicitly constructing a
	 * {@link CourseUpdateRequest} that sets the second course's slug to the
	 * first course's already-taken one.
	 */
	@Test
	void editingACourseToUseAnAlreadyTakenSlugWithinTheSameTenantReturns409() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("course-dup-slug-edit"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "admin@example.test");
		String takenSlug = uniqueSlug("dup-edit-taken");
		createCourseOrFail(host, token, newCourseRequest(takenSlug, teacher.getId()));
		CourseResponse second = createCourseOrFail(host, token, newCourseRequest(uniqueSlug("dup-edit-own"),
				teacher.getId()));
		CourseUpdateRequest requestWithTakenSlug = new CourseUpdateRequest(second.name(), takenSlug,
				second.category(), second.subject(), second.stream(), second.grade(), second.academicYear(),
				second.description(), second.enrollmentRule(), second.accessDurationDays());

		HttpResult<CourseResponse> result = updateCourse(host, token, second.id(), requestWithTakenSlug);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
	}

	@Test
	void theSameSlugInTwoDifferentTenantsBothSucceedProvingUniquenessIsPerTenant() {
		Tenant tenantA = seedActiveTenant(uniqueSubdomain("course-slug-multi-a"));
		Tenant tenantB = seedActiveTenant(uniqueSubdomain("course-slug-multi-b"));
		TenantUser teacherA = seedTenantUser(tenantA.getId(), "teacher-a@example.test", RAW_PASSWORD, Role.TEACHER);
		TenantUser teacherB = seedTenantUser(tenantB.getId(), "teacher-b@example.test", RAW_PASSWORD, Role.TEACHER);
		seedTenantUser(tenantA.getId(), "admin-a@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		seedTenantUser(tenantB.getId(), "admin-b@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String hostA = hostFor(tenantA.getSubdomain());
		String hostB = hostFor(tenantB.getSubdomain());
		String tokenA = loginAndGetToken(hostA, "admin-a@example.test");
		String tokenB = loginAndGetToken(hostB, "admin-b@example.test");
		String sharedSlug = uniqueSlug("shared");

		HttpResult<CourseResponse> resultA = createCourse(hostA, tokenA, newCourseRequest(sharedSlug, teacherA.getId()));
		HttpResult<CourseResponse> resultB = createCourse(hostB, tokenB, newCourseRequest(sharedSlug, teacherB.getId()));

		assertThat(resultA.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(resultB.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(resultA.getBody().data().id()).isNotEqualTo(resultB.getBody().data().id());
	}

	/**
	 * Mandatory race test mirroring {@code StaffManagementIntegrationTest
	 * #concurrentStaffCreationsWithTheIdenticalEmailInsertExactlyOneRow}'s
	 * two-thread {@link CyclicBarrier} technique exactly, proving V11's
	 * {@code uq_course_tenant_slug} constraint - not merely the service's
	 * friendly pre-check - is what actually prevents a double-create.
	 */
	@Test
	void concurrentCourseCreationsWithTheIdenticalSlugInsertExactlyOneRow() throws Exception {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("course-slug-race"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "admin@example.test");
		String slug = uniqueSlug("race");

		ExecutorService executor = Executors.newFixedThreadPool(2);
		CyclicBarrier barrier = new CyclicBarrier(2);
		try {
			List<Future<HttpResult<CourseResponse>>> futures = new ArrayList<>();
			for (int i = 0; i < 2; i++) {
				futures.add(executor.submit(() -> {
					barrier.await();
					return createCourse(host, token, newCourseRequest(slug, teacher.getId()));
				}));
			}
			List<HttpResult<CourseResponse>> responses = new ArrayList<>();
			for (Future<HttpResult<CourseResponse>> future : futures) {
				responses.add(future.get(15, TimeUnit.SECONDS));
			}

			responses.forEach(r -> assertThat(r.getStatusCode()).isNotEqualTo(HttpStatus.INTERNAL_SERVER_ERROR));
			long created = responses.stream().filter(r -> r.getStatusCode() == HttpStatus.CREATED).count();
			long conflicted = responses.stream().filter(r -> r.getStatusCode() == HttpStatus.CONFLICT).count();
			assertThat(created).isEqualTo(1);
			assertThat(conflicted).isEqualTo(1);

			Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM course WHERE tenant_id = ? AND slug = ?",
					Long.class, tenant.getId(), slug);
			assertThat(count).isEqualTo(1L);
		}
		finally {
			executor.shutdownNow();
		}
	}

	// ------------------------------------------------------------------
	// Teacher-reassignment: the module's single most important
	// authorization test (plan §15(a)).
	// ------------------------------------------------------------------

	@Test
	void courseCoordinatorCannotReassignTeacherDespiteHoldingCreateEditAndApproveGrantsOnCourses() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("course-reassign-gap"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacherA = seedTenantUser(tenant.getId(), "teacher-a@example.test", RAW_PASSWORD, Role.TEACHER);
		TenantUser teacherB = seedTenantUser(tenant.getId(), "teacher-b@example.test", RAW_PASSWORD, Role.TEACHER);
		seedTenantUser(tenant.getId(), "coordinator@example.test", RAW_PASSWORD, Role.COURSE_COORDINATOR);
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String coordinatorToken = loginAndGetToken(host, "coordinator@example.test");
		CourseResponse course = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug("reassign-gap"), teacherA.getId()));

		HttpResult<CourseResponse> result = reassignTeacher(host, coordinatorToken, course.id(), teacherB.getId());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		HttpResult<CourseResponse> unchanged = getCourse(host, adminToken, course.id());
		assertThat(unchanged.getBody().data().teacherId()).isEqualTo(teacherA.getId());
	}

	@Test
	void tenantAdminCanReassignTeacher() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("course-reassign-admin"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacherA = seedTenantUser(tenant.getId(), "teacher-a@example.test", RAW_PASSWORD, Role.TEACHER);
		TenantUser teacherB = seedTenantUser(tenant.getId(), "teacher-b@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		CourseResponse course = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug("reassign-ok"), teacherA.getId()));

		HttpResult<CourseResponse> result = reassignTeacher(host, adminToken, course.id(), teacherB.getId());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(result.getBody().data().teacherId()).isEqualTo(teacherB.getId());
	}

	@Test
	void teacherCannotReassignTeacherEvenOnTheirOwnCourse() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("course-reassign-teacher"));
		TenantUser teacherA = seedTenantUser(tenant.getId(), "teacher-a@example.test", RAW_PASSWORD, Role.TEACHER);
		TenantUser teacherB = seedTenantUser(tenant.getId(), "teacher-b@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String teacherAToken = loginAndGetToken(host, "teacher-a@example.test");
		CourseResponse course = createCourseOrFail(host, teacherAToken,
				newCourseRequest(uniqueSlug("reassign-teacher"), null));

		HttpResult<CourseResponse> result = reassignTeacher(host, teacherAToken, course.id(), teacherB.getId());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	// ------------------------------------------------------------------
	// Price change + course_price_history (plan §16/§18).
	// ------------------------------------------------------------------

	@Test
	void priceChangeWritesExactlyOneHistoryRowWithCorrectBeforeAndAfter() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("course-price-history"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "admin@example.test");
		CourseResponse course = createCourseOrFail(host, token,
				newCourseRequest(uniqueSlug("price-hist"), teacher.getId()));

		HttpResult<CourseResponse> result = changePrice(host, token, course.id(), new BigDecimal("199.50"));

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		List<Map<String, Object>> rows = jdbcTemplate.queryForList(
				"SELECT previous_price, new_price, changed_by FROM course_price_history WHERE tenant_id = ? AND course_id = ?",
				tenant.getId(), course.id());
		assertThat(rows).hasSize(1);
		assertThat((BigDecimal) rows.get(0).get("previous_price")).isEqualByComparingTo(course.price());
		assertThat((BigDecimal) rows.get(0).get("new_price")).isEqualByComparingTo("199.50");
	}

	@Test
	void priceChangeWithTheSameValueIsATrueNoOpAndWritesNoHistoryRow() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("course-price-noop"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "admin@example.test");
		CourseResponse course = createCourseOrFail(host, token,
				newCourseRequest(uniqueSlug("price-noop"), teacher.getId()));

		HttpResult<CourseResponse> result = changePrice(host, token, course.id(), course.price());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		Long count = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM course_price_history WHERE tenant_id = ? AND course_id = ?", Long.class,
				tenant.getId(), course.id());
		assertThat(count).isEqualTo(0L);
	}

	@Test
	void priceChangeOnADraftCourseStillWritesAHistoryRow() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("course-price-draft"));
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "teacher@example.test");
		CourseResponse course = createCourseOrFail(host, token,
				newCourseRequest(uniqueSlug("price-draft"), null, CourseStatus.DRAFT));
		assertThat(course.status()).isEqualTo(CourseStatus.DRAFT);

		HttpResult<CourseResponse> result = changePrice(host, token, course.id(), new BigDecimal("10.00"));
		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);

		Long count = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM course_price_history WHERE tenant_id = ? AND course_id = ?", Long.class,
				tenant.getId(), course.id());
		assertThat(count).isEqualTo(1L);
	}

	// ------------------------------------------------------------------
	// Publish / unpublish.
	// ------------------------------------------------------------------

	@Test
	void publishSetsStatusPublicAndUnpublishRevertsToDraft() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("course-publish"));
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "teacher@example.test");
		CourseResponse course = createCourseOrFail(host, token, newCourseRequest(uniqueSlug("publish"), null));
		assertThat(course.status()).isEqualTo(CourseStatus.DRAFT);

		HttpResult<CourseResponse> published = publish(host, token, course.id());
		assertThat(published.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(published.getBody().data().status()).isEqualTo(CourseStatus.PUBLIC);

		HttpResult<CourseResponse> unpublished = unpublish(host, token, course.id());
		assertThat(unpublished.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(unpublished.getBody().data().status()).isEqualTo(CourseStatus.DRAFT);
	}

	// ------------------------------------------------------------------
	// Teacher-ownership scoping (net-new pattern, plan §15(b)).
	// ------------------------------------------------------------------

	@Test
	void teacherCanViewAndEditButNotDeleteTheirOwnCourse() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("course-own"));
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String teacherToken = loginAndGetToken(host, "teacher@example.test");
		CourseResponse course = createCourseOrFail(host, teacherToken, newCourseRequest(uniqueSlug("own"), null));
		assertThat(course.teacherId()).isEqualTo(teacher.getId());

		assertThat(getCourse(host, teacherToken, course.id()).getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(updateCourse(host, teacherToken, course.id(), editRequestFor(course)).getStatusCode())
			.isEqualTo(HttpStatus.OK);
		assertThat(changePrice(host, teacherToken, course.id(), new BigDecimal("55.00")).getStatusCode())
			.isEqualTo(HttpStatus.OK);
		assertThat(publish(host, teacherToken, course.id()).getStatusCode()).isEqualTo(HttpStatus.OK);

		assertThat(deleteCourse(host, teacherToken, course.id()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void nonOwningTeacherCannotViewEditOrDeleteAnotherTeachersCourseInTheSameTenant() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("course-non-owner"));
		seedTenantUser(tenant.getId(), "teacher-a@example.test", RAW_PASSWORD, Role.TEACHER);
		seedTenantUser(tenant.getId(), "teacher-b@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String teacherAToken = loginAndGetToken(host, "teacher-a@example.test");
		String teacherBToken = loginAndGetToken(host, "teacher-b@example.test");
		CourseResponse courseA = createCourseOrFail(host, teacherAToken,
				newCourseRequest(uniqueSlug("non-owner"), null));

		assertThat(getCourse(host, teacherBToken, courseA.id()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(updateCourse(host, teacherBToken, courseA.id(), editRequestFor(courseA)).getStatusCode())
			.isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(changePrice(host, teacherBToken, courseA.id(), new BigDecimal("1.00")).getStatusCode())
			.isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(deleteCourse(host, teacherBToken, courseA.id()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	// ------------------------------------------------------------------
	// DomainArea.COURSES role-grant tests - one explicit method per staff
	// sub-role (8 total: TENANT_ADMIN + the 7 assignable staff roles),
	// matching StaffManagementIntegrationTest's established pattern.
	// ------------------------------------------------------------------

	@Test
	void tenantAdminHasFullAccessToEveryCourseEndpoint() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("course-role-admin"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		TenantUser otherTeacher = seedTenantUser(tenant.getId(), "other-teacher@example.test", RAW_PASSWORD,
				Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "admin@example.test");

		CourseResponse created = createCourseOrFail(host, token,
				newCourseRequest(uniqueSlug("role-admin"), teacher.getId()));
		assertThat(created.teacherId()).isEqualTo(teacher.getId());

		assertThat(getCourse(host, token, created.id()).getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(listCourses(host, token).getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(updateCourse(host, token, created.id(), editRequestFor(created)).getStatusCode())
			.isEqualTo(HttpStatus.OK);
		assertThat(changePrice(host, token, created.id(), new BigDecimal("120.00")).getStatusCode())
			.isEqualTo(HttpStatus.OK);
		assertThat(publish(host, token, created.id()).getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(unpublish(host, token, created.id()).getStatusCode()).isEqualTo(HttpStatus.OK);
		HttpResult<CourseResponse> reassignResult = reassignTeacher(host, token, created.id(), otherTeacher.getId());
		assertThat(reassignResult.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(reassignResult.getBody().data().teacherId()).isEqualTo(otherTeacher.getId());

		// DELETE proven against a separate, freshly-created course, kept
		// independent of deletingACourseWithExistingPriceHistoryCascadesAndSucceeds
		// below so this test's real purpose (the full role sweep) isn't
		// entangled with that test's specific price-history-cascade scenario.
		CourseResponse deletable = createCourseOrFail(host, token,
				newCourseRequest(uniqueSlug("role-admin-deletable"), teacher.getId()));
		assertThat(deleteCourse(host, token, deletable.id()).getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(getCourse(host, token, deletable.id()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	/**
	 * {@code CourseService#deleteCourse} deletes a course's {@code
	 * course_module}/{@code course_lesson} rows (structural, no audit value)
	 * but deliberately leaves its {@code course_price_history} rows alone -
	 * V12 dropped {@code fk_course_price_history_course} specifically so the
	 * price-change audit trail survives the course's own deletion, per root
	 * {@code CLAUDE.md}'s "Never delete financial history" instruction. A
	 * Tenant Admin can still delete a course regardless of its price-change
	 * history (no DELETE failure mode for this case, per the API contract,
	 * plan §10) - the history simply outlives the now-deleted course rather
	 * than blocking or being destroyed by the deletion.
	 */
	@Test
	void deletingACourseLeavesItsPriceHistoryIntactButRemovesTheCourseAndItsStructure() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("course-delete-history"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "admin@example.test");
		CourseResponse course = createCourseOrFail(host, token,
				newCourseRequest(uniqueSlug("delete-history"), teacher.getId()));
		changePrice(host, token, course.id(), new BigDecimal("55.00"));
		createModuleOrFail(host, token, course.id(), "Module 1", 1);

		HttpResult<Void> result = deleteCourse(host, token, course.id());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(getCourse(host, token, course.id()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

		Long moduleCount = jdbcTemplate.queryForObject("SELECT count(*) FROM course_module WHERE course_id = ?",
				Long.class, course.id());
		assertThat(moduleCount).isZero();

		Long historyCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM course_price_history WHERE tenant_id = ? AND course_id = ? AND previous_price = ? AND new_price = ?",
				Long.class, tenant.getId(), course.id(), new BigDecimal("99.99"), new BigDecimal("55.00"));
		assertThat(historyCount).isEqualTo(1L);
	}

	@Test
	void courseCoordinatorCanCreateEditPublishButNotDeleteOrReassignTeacher() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("course-role-coordinator"));
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		seedTenantUser(tenant.getId(), "coordinator@example.test", RAW_PASSWORD, Role.COURSE_COORDINATOR);
		String host = hostFor(tenant.getSubdomain());
		String coordinatorToken = loginAndGetToken(host, "coordinator@example.test");

		CourseResponse created = createCourseOrFail(host, coordinatorToken,
				newCourseRequest(uniqueSlug("role-coordinator"), teacher.getId()));

		assertThat(updateCourse(host, coordinatorToken, created.id(), editRequestFor(created)).getStatusCode())
			.isEqualTo(HttpStatus.OK);
		assertThat(changePrice(host, coordinatorToken, created.id(), new BigDecimal("77.00")).getStatusCode())
			.isEqualTo(HttpStatus.OK);
		assertThat(publish(host, coordinatorToken, created.id()).getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(unpublish(host, coordinatorToken, created.id()).getStatusCode()).isEqualTo(HttpStatus.OK);

		// Negative: DELETE is Tenant-Admin only (no DELETE grant for Course
		// Coordinator on DomainArea.COURSES).
		assertThat(deleteCourse(host, coordinatorToken, created.id()).getStatusCode())
			.isEqualTo(HttpStatus.FORBIDDEN);
		// Negative: teacher reassignment - the plan's specific gap-closing
		// case, re-verified here as part of the full per-role sweep.
		assertThat(reassignTeacher(host, coordinatorToken, created.id(), teacher.getId()).getStatusCode())
			.isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void financeStaffCanOnlyViewCourses() {
		assertRoleCanOnlyViewCourses(Role.FINANCE_STAFF, "finance-staff");
	}

	@Test
	void studentSupportCanOnlyViewCourses() {
		assertRoleCanOnlyViewCourses(Role.STUDENT_SUPPORT, "student-support");
	}

	@Test
	void contentManagerCanOnlyViewCourses() {
		assertRoleCanOnlyViewCourses(Role.CONTENT_MANAGER, "content-manager");
	}

	@Test
	void examManagerCanOnlyViewCourses() {
		assertRoleCanOnlyViewCourses(Role.EXAM_MANAGER, "exam-manager");
	}

	@Test
	void attendanceOperatorCanOnlyViewCourses() {
		assertRoleCanOnlyViewCourses(Role.ATTENDANCE_OPERATOR, "attendance-operator");
	}

	@Test
	void readOnlyAuditorCanViewButNeverMutatesAnyCourseEndpoint() {
		assertRoleCanOnlyViewCourses(Role.READ_ONLY_AUDITOR, "read-only-auditor");
	}

	private void assertRoleCanOnlyViewCourses(Role role, String subdomainPrefix) {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("course-role-" + subdomainPrefix));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String staffEmail = subdomainPrefix + "@example.test";
		seedTenantUser(tenant.getId(), staffEmail, RAW_PASSWORD, role);
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String staffToken = loginAndGetToken(host, staffEmail);
		CourseResponse existing = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug(subdomainPrefix), teacher.getId()));

		// Positive half: VIEW grant works.
		HttpResult<PageResponse<CourseResponse>> listResult = listCourses(host, staffToken);
		assertThat(listResult.getStatusCode()).as(role + " GET /api/v1/courses").isEqualTo(HttpStatus.OK);
		assertThat(listResult.getBody().data().content()).extracting(CourseResponse::id).contains(existing.id());

		HttpResult<CourseResponse> getResult = getCourse(host, staffToken, existing.id());
		assertThat(getResult.getStatusCode()).as(role + " GET /api/v1/courses/{id}").isEqualTo(HttpStatus.OK);

		// Negative half: no CREATE_EDIT/DELETE/APPROVE grant - every
		// mutating endpoint is forbidden.
		HttpResult<CourseResponse> createResult = createCourse(host, staffToken,
				newCourseRequest(uniqueSlug(subdomainPrefix + "-blocked"), teacher.getId()));
		assertThat(createResult.getStatusCode()).as(role + " POST /api/v1/courses").isEqualTo(HttpStatus.FORBIDDEN);

		HttpResult<CourseResponse> updateResult = updateCourse(host, staffToken, existing.id(),
				editRequestFor(existing));
		assertThat(updateResult.getStatusCode()).as(role + " PATCH /api/v1/courses/{id}")
			.isEqualTo(HttpStatus.FORBIDDEN);

		HttpResult<CourseResponse> priceResult = changePrice(host, staffToken, existing.id(), new BigDecimal("50.00"));
		assertThat(priceResult.getStatusCode()).as(role + " PATCH /api/v1/courses/{id}/price")
			.isEqualTo(HttpStatus.FORBIDDEN);

		HttpResult<CourseResponse> publishResult = publish(host, staffToken, existing.id());
		assertThat(publishResult.getStatusCode()).as(role + " POST /api/v1/courses/{id}/publish")
			.isEqualTo(HttpStatus.FORBIDDEN);

		HttpResult<CourseResponse> unpublishResult = unpublish(host, staffToken, existing.id());
		assertThat(unpublishResult.getStatusCode()).as(role + " POST /api/v1/courses/{id}/unpublish")
			.isEqualTo(HttpStatus.FORBIDDEN);

		HttpResult<CourseResponse> reassignResult = reassignTeacher(host, staffToken, existing.id(), teacher.getId());
		assertThat(reassignResult.getStatusCode()).as(role + " POST /api/v1/courses/{id}/teacher")
			.isEqualTo(HttpStatus.FORBIDDEN);

		HttpResult<Void> deleteResult = deleteCourse(host, staffToken, existing.id());
		assertThat(deleteResult.getStatusCode()).as(role + " DELETE /api/v1/courses/{id}")
			.isEqualTo(HttpStatus.FORBIDDEN);
	}

	// ------------------------------------------------------------------
	// Mandatory cross-tenant negative tests (plan §14).
	// ------------------------------------------------------------------

	@Test
	void courseCreateWithATeacherIdFromAnotherTenantIsRejectedByTheServiceLayerCheck() {
		Tenant tenantA = seedActiveTenant(uniqueSubdomain("course-cross-create-a"));
		Tenant tenantB = seedActiveTenant(uniqueSubdomain("course-cross-create-b"));
		seedTenantUser(tenantA.getId(), "admin-a@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacherB = seedTenantUser(tenantB.getId(), "teacher-b@example.test", RAW_PASSWORD, Role.TEACHER);
		String hostA = hostFor(tenantA.getSubdomain());
		String tokenA = loginAndGetToken(hostA, "admin-a@example.test");

		HttpResult<CourseResponse> result = createCourse(hostA, tokenA,
				newCourseRequest(uniqueSlug("cross-teacher"), teacherB.getId()));

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(result.getBody().error().code()).isEqualTo("VALIDATION_ERROR");
		Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM course WHERE tenant_id = ? AND teacher_id = ?",
				Long.class, tenantA.getId(), teacherB.getId());
		assertThat(count).isEqualTo(0L);
	}

	@Test
	void crossTenantCourseDetailReturns404NeverTenantAsData() {
		CrossTenantFixture fixture = seedCrossTenantFixture("cross-detail");

		HttpResult<CourseResponse> result = getCourse(fixture.hostB, fixture.tokenB, fixture.courseA.id());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(result.getBody().error().code()).isEqualTo("NOT_FOUND");
	}

	@Test
	void crossTenantCourseEditReturns404() {
		CrossTenantFixture fixture = seedCrossTenantFixture("cross-edit");

		HttpResult<CourseResponse> result = updateCourse(fixture.hostB, fixture.tokenB, fixture.courseA.id(),
				editRequestFor(fixture.courseA));

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void crossTenantCourseDeleteReturns404AndTenantAsRowSurvives() {
		CrossTenantFixture fixture = seedCrossTenantFixture("cross-delete");

		HttpResult<Void> result = deleteCourse(fixture.hostB, fixture.tokenB, fixture.courseA.id());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM course WHERE id = ?", Long.class,
				fixture.courseA.id());
		assertThat(count).isEqualTo(1L);
	}

	@Test
	void crossTenantCourseListingNeverIncludesTenantARows() {
		CrossTenantFixture fixture = seedCrossTenantFixture("cross-list");

		HttpResult<PageResponse<CourseResponse>> tenantBList = listCourses(fixture.hostB, fixture.tokenB);

		assertThat(tenantBList.getStatusCode()).isEqualTo(HttpStatus.OK);
		// Positive content, not just absence: tenant B's own course (seeded
		// by the fixture) must be present, proving the list isn't merely
		// coincidentally empty - a bug that ignored tenant_id entirely but
		// happened to filter nothing else out would still fail this.
		assertThat(tenantBList.getBody().data().content()).extracting(CourseResponse::id)
			.contains(fixture.courseB.id())
			.doesNotContain(fixture.courseA.id());
	}

	// ------------------------------------------------------------------
	// Pagination & filtering (MVP-008 review Fix 4: the listing endpoint was
	// completely unpaginated).
	// ------------------------------------------------------------------

	@Test
	void listCoursesIsPaginatedAndReturnsABoundedPageWithCorrectTotals() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("course-page-bounds"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "admin@example.test");
		for (int i = 0; i < 3; i++) {
			createCourseOrFail(host, token, newCourseRequest(uniqueSlug("page-bounds-" + i), teacher.getId()));
		}

		HttpResult<PageResponse<CourseResponse>> firstPage = listCourses(host, token, "page=0&size=2");

		assertThat(firstPage.getStatusCode()).isEqualTo(HttpStatus.OK);
		PageResponse<CourseResponse> body = firstPage.getBody().data();
		assertThat(body.content()).hasSize(2);
		assertThat(body.page()).isEqualTo(0);
		assertThat(body.size()).isEqualTo(2);
		assertThat(body.totalElements()).isEqualTo(3);
		assertThat(body.totalPages()).isEqualTo(2);

		HttpResult<PageResponse<CourseResponse>> secondPage = listCourses(host, token, "page=1&size=2");
		assertThat(secondPage.getBody().data().content()).hasSize(1);
	}

	@Test
	void listCoursesClampsAnOversizedRequestedPageSizeToTheServerSideMaximum() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("course-page-clamp"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "admin@example.test");

		HttpResult<PageResponse<CourseResponse>> result = listCourses(host, token, "size=999999");

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(result.getBody().data().size()).isEqualTo(100);
	}

	@Test
	void listCoursesFiltersByStatusAndTeacherIdWithinTenantScope() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("course-filter"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacherA = seedTenantUser(tenant.getId(), "teacher-a@example.test", RAW_PASSWORD, Role.TEACHER);
		TenantUser teacherB = seedTenantUser(tenant.getId(), "teacher-b@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "admin@example.test");
		CourseResponse publicByA = createCourseOrFail(host, token,
				newCourseRequest(uniqueSlug("filter-public-a"), teacherA.getId(), CourseStatus.PUBLIC));
		createCourseOrFail(host, token, newCourseRequest(uniqueSlug("filter-draft-a"), teacherA.getId()));
		createCourseOrFail(host, token, newCourseRequest(uniqueSlug("filter-public-b"), teacherB.getId(),
				CourseStatus.PUBLIC));

		HttpResult<PageResponse<CourseResponse>> byStatus = listCourses(host, token, "status=PUBLIC&size=50");
		assertThat(byStatus.getBody().data().content()).extracting(CourseResponse::status)
			.allMatch(status -> status == CourseStatus.PUBLIC);

		HttpResult<PageResponse<CourseResponse>> byTeacher = listCourses(host, token,
				"teacherId=" + teacherA.getId() + "&size=50");
		assertThat(byTeacher.getBody().data().content()).extracting(CourseResponse::teacherId)
			.containsOnly(teacherA.getId());
		assertThat(byTeacher.getBody().data().content()).extracting(CourseResponse::id).contains(publicByA.id());

		HttpResult<PageResponse<CourseResponse>> byStatusAndTeacher = listCourses(host, token,
				"status=PUBLIC&teacherId=" + teacherA.getId() + "&size=50");
		assertThat(byStatusAndTeacher.getBody().data().content()).extracting(CourseResponse::id)
			.containsExactly(publicByA.id());
	}

	/**
	 * {@code CourseSpecifications.withCategory} is an exact-match predicate
	 * (not a prefix/contains match) - proves {@code GET
	 * /api/v1/courses?category=} actually filters, and that a course of a
	 * different category within the very same tenant is correctly excluded
	 * rather than the filter being a no-op that happens to return everything.
	 */
	@Test
	void listCoursesFiltersByExactCategoryWithinTenantScope() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("course-filter-category"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "admin@example.test");
		CourseResponse mathsCourse = createCourseOrFail(host, token,
				newCourseRequest(uniqueSlug("filter-category-maths"), teacher.getId(), null, "Mathematics"));
		CourseResponse scienceCourse = createCourseOrFail(host, token,
				newCourseRequest(uniqueSlug("filter-category-science"), teacher.getId(), null, "Science"));

		HttpResult<PageResponse<CourseResponse>> byCategory = listCourses(host, token,
				"category=Mathematics&size=50");

		assertThat(byCategory.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(byCategory.getBody().data().content()).extracting(CourseResponse::category)
			.containsOnly("Mathematics");
		assertThat(byCategory.getBody().data().content()).extracting(CourseResponse::id)
			.contains(mathsCourse.id())
			.doesNotContain(scienceCourse.id());
	}

	/**
	 * A staff caller's {@code teacherId} filter must stay tenant-scoped by
	 * construction - {@code CourseSpecifications.withTeacherId} is always
	 * AND-combined with {@code TenantAwareRepositoryImpl}'s own tenant
	 * predicate, so filtering by another tenant's real teacher id must never
	 * leak that tenant's course into this tenant's result set; it must
	 * simply match nothing.
	 */
	@Test
	void teacherIdFilterCannotBeUsedToLeakAnotherTenantsCourses() {
		Tenant tenantA = seedActiveTenant(uniqueSubdomain("course-filter-cross-a"));
		Tenant tenantB = seedActiveTenant(uniqueSubdomain("course-filter-cross-b"));
		seedTenantUser(tenantA.getId(), "admin-a@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		seedTenantUser(tenantB.getId(), "admin-b@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacherA = seedTenantUser(tenantA.getId(), "teacher-a@example.test", RAW_PASSWORD, Role.TEACHER);
		TenantUser teacherB = seedTenantUser(tenantB.getId(), "teacher-b@example.test", RAW_PASSWORD, Role.TEACHER);
		String hostA = hostFor(tenantA.getSubdomain());
		String hostB = hostFor(tenantB.getSubdomain());
		String tokenA = loginAndGetToken(hostA, "admin-a@example.test");
		String tokenB = loginAndGetToken(hostB, "admin-b@example.test");
		createCourseOrFail(hostA, tokenA, newCourseRequest(uniqueSlug("filter-cross-a"), teacherA.getId()));
		CourseResponse ownCourseB = createCourseOrFail(hostB, tokenB,
				newCourseRequest(uniqueSlug("filter-cross-b"), teacherB.getId()));

		// Tenant B's admin attempts to filter by tenant A's real teacher id.
		HttpResult<PageResponse<CourseResponse>> result = listCourses(hostB, tokenB,
				"teacherId=" + teacherA.getId() + "&size=50");

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(result.getBody().data().content()).isEmpty();
		assertThat(result.getBody().data().totalElements()).isZero();

		// Sanity: tenant B's admin can still see their own course when not
		// filtering by the mismatched teacher id.
		HttpResult<PageResponse<CourseResponse>> unfiltered = listCourses(hostB, tokenB, "size=50");
		assertThat(unfiltered.getBody().data().content()).extracting(CourseResponse::id).contains(ownCourseB.id());
	}

	@Test
	void teacherCallersTeacherIdFilterQueryParamIsIgnoredAndAlwaysOverriddenToTheirOwnId() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("course-teacher-filter-ignored"));
		TenantUser teacherA = seedTenantUser(tenant.getId(), "teacher-a@example.test", RAW_PASSWORD, Role.TEACHER);
		TenantUser teacherB = seedTenantUser(tenant.getId(), "teacher-b@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String teacherAToken = loginAndGetToken(host, "teacher-a@example.test");
		String teacherBTokenForSeeding = loginAndGetToken(host, "teacher-b@example.test");
		CourseResponse ownCourse = createCourseOrFail(host, teacherAToken,
				newCourseRequest(uniqueSlug("teacher-filter-own"), null));
		createCourseOrFail(host, teacherBTokenForSeeding, newCourseRequest(uniqueSlug("teacher-filter-other"), null));

		// Teacher A attempts to smuggle Teacher B's id as the teacherId
		// filter - CourseService#listCourses must ignore it and force its
		// own id regardless, so the result never contains Teacher B's course.
		HttpResult<PageResponse<CourseResponse>> result = listCourses(host, teacherAToken,
				"teacherId=" + teacherB.getId() + "&size=50");

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(result.getBody().data().content()).extracting(CourseResponse::id).containsExactly(ownCourse.id());
	}

	@Test
	void crossTenantPriceChangeReturns404() {
		CrossTenantFixture fixture = seedCrossTenantFixture("cross-price");

		HttpResult<CourseResponse> result = changePrice(fixture.hostB, fixture.tokenB, fixture.courseA.id(),
				new BigDecimal("1.00"));

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void crossTenantTeacherReassignmentReturns404() {
		CrossTenantFixture fixture = seedCrossTenantFixture("cross-reassign");
		TenantUser teacherB = seedTenantUser(fixture.tenantB.getId(), "teacher-target@example.test", RAW_PASSWORD,
				Role.TEACHER);

		HttpResult<CourseResponse> result = reassignTeacher(fixture.hostB, fixture.tokenB, fixture.courseA.id(),
				teacherB.getId());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void crossTenantPublishReturns404() {
		CrossTenantFixture fixture = seedCrossTenantFixture("cross-publish");

		HttpResult<CourseResponse> result = publish(fixture.hostB, fixture.tokenB, fixture.courseA.id());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void crossTenantUnpublishReturns404() {
		CrossTenantFixture fixture = seedCrossTenantFixture("cross-unpublish");

		HttpResult<CourseResponse> result = unpublish(fixture.hostB, fixture.tokenB, fixture.courseA.id());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	// ------------------------------------------------------------------
	// Error-handling/validation review findings (M2-M4): malformed input
	// must produce a clean 400, never fall through GlobalExceptionHandler's
	// generic 500 handler.
	// ------------------------------------------------------------------

	@Test
	void malformedCourseIdPathVariableReturnsBadRequestNotServerError() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("course-malformed-id"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "admin@example.test");

		MockHttpServletRequestBuilder builder = get("/api/v1/courses/{id}", "not-a-uuid");
		int status = perform(authenticated(builder, host, token)).getResponse().getStatus();

		assertThat(status).isEqualTo(HttpStatus.BAD_REQUEST.value());
	}

	@Test
	void invalidStatusEnumValueInCreateRequestReturnsBadRequestNotServerError() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("course-invalid-enum"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "admin@example.test");
		String malformedBody = """
				{"name":"Bad Status Course","slug":"%s","category":"Mathematics","price":10.00,\
				"status":"NOT_A_REAL_STATUS","teacherId":"%s"}"""
			.formatted(uniqueSlug("invalid-enum"), teacher.getId());

		MockHttpServletRequestBuilder builder = post("/api/v1/courses").contentType(MediaType.APPLICATION_JSON)
			.content(malformedBody);
		int status = perform(authenticated(builder, host, token)).getResponse().getStatus();

		assertThat(status).isEqualTo(HttpStatus.BAD_REQUEST.value());
	}

	@Test
	void oversizedPriceExceedingColumnPrecisionReturnsBadRequestNotServerError() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("course-oversized-price"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "admin@example.test");
		CourseCreateRequest request = new CourseCreateRequest("Oversized Price Course",
				uniqueSlug("oversized-price"), "Mathematics", null, null, null, null, null,
				new BigDecimal("99999999999999.99"), null, null, null, teacher.getId());

		HttpResult<CourseResponse> result = createCourse(host, token, request);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	private CrossTenantFixture seedCrossTenantFixture(String prefix) {
		Tenant tenantA = seedActiveTenant(uniqueSubdomain("course-" + prefix + "-a"));
		Tenant tenantB = seedActiveTenant(uniqueSubdomain("course-" + prefix + "-b"));
		seedTenantUser(tenantA.getId(), "admin-a@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		seedTenantUser(tenantB.getId(), "admin-b@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacherA = seedTenantUser(tenantA.getId(), "teacher-a@example.test", RAW_PASSWORD, Role.TEACHER);
		TenantUser teacherB = seedTenantUser(tenantB.getId(), "teacher-b@example.test", RAW_PASSWORD, Role.TEACHER);
		String hostA = hostFor(tenantA.getSubdomain());
		String hostB = hostFor(tenantB.getSubdomain());
		String tokenA = loginAndGetToken(hostA, "admin-a@example.test");
		String tokenB = loginAndGetToken(hostB, "admin-b@example.test");
		CourseResponse courseA = createCourseOrFail(hostA, tokenA,
				newCourseRequest(uniqueSlug(prefix), teacherA.getId()));
		// tenant B's own, legitimate course - so a cross-tenant negative test
		// against tenant B's endpoints (e.g. the listing test below) proves
		// isolation by positive content (contains its own, excludes tenant
		// A's), not merely "the list happened to be empty."
		CourseResponse courseB = createCourseOrFail(hostB, tokenB,
				newCourseRequest(uniqueSlug(prefix + "-b-own"), teacherB.getId()));
		return new CrossTenantFixture(tenantA, tenantB, hostA, hostB, tokenA, tokenB, courseA, courseB);
	}

	private record CrossTenantFixture(Tenant tenantA, Tenant tenantB, String hostA, String hostB, String tokenA,
			String tokenB, CourseResponse courseA, CourseResponse courseB) {
	}

}
