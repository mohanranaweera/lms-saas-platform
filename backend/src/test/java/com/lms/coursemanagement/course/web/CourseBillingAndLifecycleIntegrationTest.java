package com.lms.coursemanagement.course.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.coursemanagement.CourseManagementTestSupport;
import com.lms.coursemanagement.course.domain.CoursePricingModel;
import com.lms.coursemanagement.course.web.dto.CourseBillingConfigurationResponse;
import com.lms.coursemanagement.course.web.dto.CourseBillingPeriodResponse;
import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.tenantmanagement.domain.Tenant;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Testcontainers-backed coverage for Wave 2's course billing-configuration/
 * billing-period/archive/unarchive/clone/pricing-model endpoints, mirroring
 * {@code CourseManagementIntegrationTest}'s established technique. Every
 * cross-tenant scenario is a mandatory negative test per {@code
 * .claude/rules/tenancy.md}.
 */
class CourseBillingAndLifecycleIntegrationTest extends CourseManagementTestSupport {

	// ------------------------------------------------------------------
	// Billing configuration / periods - happy path + validation.
	// ------------------------------------------------------------------

	@Test
	void createsAndReadsABillingConfigurationForASessionPricedCourse() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("bill-config"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "admin@example.test");
		CourseResponse course = createCourseOrFail(host, token,
				newCourseRequest(uniqueSlug("bill-config"), teacher.getId()));
		changePricingModel(host, token, course.id(), CoursePricingModel.SESSION);

		HttpResult<CourseBillingConfigurationResponse> created = createOrUpdateBillingConfiguration(host, token,
				course.id(), new BigDecimal("15.00"), "USD", false);
		assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(created.getBody().data().sessionRate()).isEqualByComparingTo("15.00");

		HttpResult<CourseBillingConfigurationResponse> fetched = getBillingConfiguration(host, token, course.id());
		assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(fetched.getBody().data().currency()).isEqualTo("USD");
	}

	@Test
	void sessionRateForAOneTimePricedCourseIsRejectedWith400() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("bill-invalid"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "admin@example.test");
		CourseResponse course = createCourseOrFail(host, token,
				newCourseRequest(uniqueSlug("bill-invalid"), teacher.getId()));

		HttpResult<CourseBillingConfigurationResponse> result = createOrUpdateBillingConfiguration(host, token,
				course.id(), new BigDecimal("15.00"), "USD", false);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void addingASecondBillingPeriodClosesTheFirstAndHistoryPreservesTheOriginalAmount() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("bill-period"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "admin@example.test");
		CourseResponse course = createCourseOrFail(host, token,
				newCourseRequest(uniqueSlug("bill-period"), teacher.getId()));
		changePricingModel(host, token, course.id(), CoursePricingModel.MONTHLY);
		createOrUpdateBillingConfiguration(host, token, course.id(), null, "USD", false);

		HttpResult<CourseBillingPeriodResponse> first = addBillingPeriod(host, token, course.id(),
				new BigDecimal("30.00"), "USD", Instant.now().minusSeconds(3600));
		assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		HttpResult<CourseBillingPeriodResponse> second = addBillingPeriod(host, token, course.id(),
				new BigDecimal("40.00"), "USD", Instant.now());
		assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);

		HttpResult<com.lms.common.api.PageResponse<CourseBillingPeriodResponse>> history = listBillingPeriods(host,
				token, course.id());
		assertThat(history.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(history.getBody().data().content()).hasSize(2);
		CourseBillingPeriodResponse firstAgain = history.getBody()
			.data()
			.content()
			.stream()
			.filter(p -> p.id().equals(first.getBody().data().id()))
			.findFirst()
			.orElseThrow();
		assertThat(firstAgain.amount()).isEqualByComparingTo("30.00"); // never mutated
		assertThat(firstAgain.effectiveTo()).isNotNull(); // closed
	}

	// ------------------------------------------------------------------
	// Archive / unarchive / clone.
	// ------------------------------------------------------------------

	@Test
	void archivedCoursesAreExcludedFromTheDefaultListingButVisibleWithIncludeArchived() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("archive-list"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "admin@example.test");
		CourseResponse course = createCourseOrFail(host, token,
				newCourseRequest(uniqueSlug("archive-list"), teacher.getId()));

		HttpResult<CourseResponse> archived = archiveCourse(host, token, course.id());
		assertThat(archived.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(archived.getBody().data().archivedAt()).isNotNull();

		HttpResult<com.lms.common.api.PageResponse<CourseResponse>> defaultList = listCourses(host, token);
		assertThat(defaultList.getBody().data().content().stream().map(CourseResponse::id)).doesNotContain(
				course.id());

		HttpResult<com.lms.common.api.PageResponse<CourseResponse>> withArchived = listCourses(host, token,
				"includeArchived=true");
		assertThat(withArchived.getBody().data().content().stream().map(CourseResponse::id)).contains(course.id());

		HttpResult<CourseResponse> unarchived = unarchiveCourse(host, token, course.id());
		assertThat(unarchived.getBody().data().archivedAt()).isNull();
		HttpResult<com.lms.common.api.PageResponse<CourseResponse>> afterUnarchive = listCourses(host, token);
		assertThat(afterUnarchive.getBody().data().content().stream().map(CourseResponse::id)).contains(course.id());
	}

	@Test
	void cloneCopiesStructureButNotBillingPeriodsOrPriceHistory() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("clone"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "admin@example.test");
		CourseResponse course = createCourseOrFail(host, token,
				newCourseRequest(uniqueSlug("clone-src"), teacher.getId()));
		createModuleOrFail(host, token, course.id(), "Module 1", 1);
		changePrice(host, token, course.id(), new BigDecimal("55.00"));

		HttpResult<CourseResponse> cloneResult = cloneCourse(host, token, course.id());

		assertThat(cloneResult.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		CourseResponse clone = cloneResult.getBody().data();
		assertThat(clone.id()).isNotEqualTo(course.id());
		assertThat(clone.status()).isEqualTo(com.lms.coursemanagement.course.domain.CourseStatus.DRAFT);
		// Source course's pricing model is (still) ONE_TIME - price IS copied.
		assertThat(clone.price()).isEqualByComparingTo("55.00");
		HttpResult<java.util.List<com.lms.coursemanagement.course.web.dto.CourseModuleResponse>> clonedModules = listModules(
				host, token, clone.id());
		assertThat(clonedModules.getBody().data()).hasSize(1);
		assertThat(clonedModules.getBody().data().get(0).title()).isEqualTo("Module 1");

		// Billing configuration is never copied - the clone starts with none.
		HttpResult<CourseBillingConfigurationResponse> cloneConfig = getBillingConfiguration(host, token, clone.id());
		assertThat(cloneConfig.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	// ------------------------------------------------------------------
	// Cross-tenant negative tests (mandatory per .claude/rules/tenancy.md).
	// ------------------------------------------------------------------

	@Test
	void crossTenantBillingConfigurationReadReturns404NotLeakedData() {
		CrossTenantFixture fixture = seedCrossTenantCourse("bill-xt-config");

		HttpResult<CourseBillingConfigurationResponse> result = getBillingConfiguration(fixture.hostB, fixture.tokenB,
				fixture.courseId);

		assertThat(result.getStatusCode()).isIn(HttpStatus.NOT_FOUND, HttpStatus.FORBIDDEN);
	}

	@Test
	void crossTenantBillingConfigurationWriteReturns404() {
		CrossTenantFixture fixture = seedCrossTenantCourse("bill-xt-config-write");

		HttpResult<CourseBillingConfigurationResponse> result = createOrUpdateBillingConfiguration(fixture.hostB,
				fixture.tokenB, fixture.courseId, null, "USD", false);

		assertThat(result.getStatusCode()).isIn(HttpStatus.NOT_FOUND, HttpStatus.FORBIDDEN);
	}

	@Test
	void crossTenantBillingPeriodsReadAndWriteReturn404() {
		CrossTenantFixture fixture = seedCrossTenantCourse("bill-xt-periods");

		HttpResult<com.lms.common.api.PageResponse<CourseBillingPeriodResponse>> list = listBillingPeriods(
				fixture.hostB, fixture.tokenB, fixture.courseId);
		HttpResult<CourseBillingPeriodResponse> add = addBillingPeriod(fixture.hostB, fixture.tokenB, fixture.courseId,
				new BigDecimal("10.00"), "USD", Instant.now());

		assertThat(list.getStatusCode()).isIn(HttpStatus.NOT_FOUND, HttpStatus.FORBIDDEN);
		assertThat(add.getStatusCode()).isIn(HttpStatus.NOT_FOUND, HttpStatus.FORBIDDEN);
	}

	@Test
	void crossTenantArchiveUnarchiveAndCloneReturn404() {
		CrossTenantFixture fixture = seedCrossTenantCourse("bill-xt-lifecycle");

		HttpResult<CourseResponse> archive = archiveCourse(fixture.hostB, fixture.tokenB, fixture.courseId);
		HttpResult<CourseResponse> unarchive = unarchiveCourse(fixture.hostB, fixture.tokenB, fixture.courseId);
		HttpResult<CourseResponse> clone = cloneCourse(fixture.hostB, fixture.tokenB, fixture.courseId);

		assertThat(archive.getStatusCode()).isIn(HttpStatus.NOT_FOUND, HttpStatus.FORBIDDEN);
		assertThat(unarchive.getStatusCode()).isIn(HttpStatus.NOT_FOUND, HttpStatus.FORBIDDEN);
		assertThat(clone.getStatusCode()).isIn(HttpStatus.NOT_FOUND, HttpStatus.FORBIDDEN);
	}

	@Test
	void crossTenantPricingModelChangeReturns404() {
		CrossTenantFixture fixture = seedCrossTenantCourse("bill-xt-pricing");

		HttpResult<CourseResponse> result = changePricingModel(fixture.hostB, fixture.tokenB, fixture.courseId,
				CoursePricingModel.SESSION);

		assertThat(result.getStatusCode()).isIn(HttpStatus.NOT_FOUND, HttpStatus.FORBIDDEN);
	}

	// ------------------------------------------------------------------
	// Same-tenant non-owning-teacher negative tests (Wave 2 review gap):
	// distinct from the cross-tenant tests above - both teachers here belong
	// to the SAME tenant, so this proves CourseAccessGuard's owner-scoping
	// (not tenant scoping) rejects a teacher who simply isn't this course's
	// assigned teacher, mirroring {@code CourseManagementIntegrationTest
	// #nonOwningTeacherCannotViewEditOrDeleteAnotherTeachersCourseInTheSameTenant}'s
	// established pattern for the Wave 2 billing/archive/clone endpoints that
	// pattern never covered.
	// ------------------------------------------------------------------

	@Test
	void nonOwningTeacherCannotViewOrEditAnotherTeachersCourseBillingConfigurationOrPeriods() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("bill-nonowner"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser owningTeacher = seedTenantUser(tenant.getId(), "owner-teacher@example.test", RAW_PASSWORD,
				Role.TEACHER);
		seedTenantUser(tenant.getId(), "other-teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String otherTeacherToken = loginAndGetToken(host, "other-teacher@example.test");
		CourseResponse course = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug("bill-nonowner"), owningTeacher.getId()));
		changePricingModel(host, adminToken, course.id(), CoursePricingModel.MONTHLY);
		createOrUpdateBillingConfiguration(host, adminToken, course.id(), null, "USD", false);

		HttpResult<CourseBillingConfigurationResponse> getConfig = getBillingConfiguration(host, otherTeacherToken,
				course.id());
		HttpResult<CourseBillingConfigurationResponse> writeConfig = createOrUpdateBillingConfiguration(host,
				otherTeacherToken, course.id(), null, "USD", false);
		HttpResult<com.lms.common.api.PageResponse<CourseBillingPeriodResponse>> listPeriods = listBillingPeriods(
				host, otherTeacherToken, course.id());
		HttpResult<CourseBillingPeriodResponse> addPeriod = addBillingPeriod(host, otherTeacherToken, course.id(),
				new BigDecimal("10.00"), "USD", Instant.now());

		assertThat(getConfig.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(writeConfig.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(listPeriods.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(addPeriod.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void nonOwningTeacherCannotArchiveUnarchiveOrCloneAnotherTeachersCourse() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("bill-nonowner-lc"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser owningTeacher = seedTenantUser(tenant.getId(), "owner-teacher@example.test", RAW_PASSWORD,
				Role.TEACHER);
		seedTenantUser(tenant.getId(), "other-teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String otherTeacherToken = loginAndGetToken(host, "other-teacher@example.test");
		CourseResponse course = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug("bill-nonowner-lc"), owningTeacher.getId()));

		HttpResult<CourseResponse> archive = archiveCourse(host, otherTeacherToken, course.id());
		HttpResult<CourseResponse> unarchive = unarchiveCourse(host, otherTeacherToken, course.id());
		HttpResult<CourseResponse> clone = cloneCourse(host, otherTeacherToken, course.id());

		assertThat(archive.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(unarchive.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(clone.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

		// Never actually archived by the rejected attempt.
		HttpResult<CourseResponse> reread = getCourse(host, adminToken, course.id());
		assertThat(reread.getBody().data().archivedAt()).isNull();
	}

	// ------------------------------------------------------------------
	// Genuine concurrent race on the partial unique index (Wave 2 review
	// gap): BillingConfigurationServiceTest's
	// aPartialUniqueIndexRaceOnAddBillingPeriodIsMappedToAConflictException
	// proves the mapping with a MOCKED DataIntegrityViolationException. This
	// proves the real thing - two genuinely concurrent HTTP requests racing
	// to insert the FIRST billing period for the same course (so neither
	// sees an existing "current open" period to close first) - produces
	// exactly one 201 and one clean 409, never an unhandled 500. See the
	// in-test comment below: the new period's own insert is now explicitly
	// `saveAndFlush()`ed inside BillingConfigurationService#addBillingPeriod's
	// own try/catch (Wave 2 QA gap fix), so the loser's 409 is produced by
	// that method's own dedicated ConflictException mapping, not
	// GlobalExceptionHandler's generic DataIntegrityViolationException
	// fallback.
	// ------------------------------------------------------------------

	@Test
	void concurrentFirstBillingPeriodInsertsForTheSameCourseProduceExactlyOneSuccessAndOneCleanConflict()
			throws Exception {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("bill-race"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "admin@example.test");
		CourseResponse course = createCourseOrFail(host, token, newCourseRequest(uniqueSlug("bill-race"), teacher.getId()));
		changePricingModel(host, token, course.id(), CoursePricingModel.MONTHLY);
		createOrUpdateBillingConfiguration(host, token, course.id(), null, "USD", false);

		int concurrency = 2;
		CyclicBarrier barrier = new CyclicBarrier(concurrency);
		ExecutorService executor = Executors.newFixedThreadPool(concurrency);
		List<Callable<HttpResult<CourseBillingPeriodResponse>>> tasks = new ArrayList<>();
		for (int i = 0; i < concurrency; i++) {
			tasks.add(() -> {
				barrier.await();
				return addBillingPeriod(host, token, course.id(), new BigDecimal("20.00"), "USD", Instant.now());
			});
		}

		List<HttpResult<CourseBillingPeriodResponse>> results = new ArrayList<>();
		try {
			List<Future<HttpResult<CourseBillingPeriodResponse>>> futures = executor.invokeAll(tasks);
			for (Future<HttpResult<CourseBillingPeriodResponse>> future : futures) {
				results.add(future.get(15, TimeUnit.SECONDS));
			}
		}
		finally {
			executor.shutdownNow();
		}

		long successCount = results.stream().filter(r -> r.getStatusCode() == HttpStatus.CREATED).count();
		long conflictCount = results.stream().filter(r -> r.getStatusCode() == HttpStatus.CONFLICT).count();
		assertThat(successCount).isEqualTo(1L);
		assertThat(conflictCount).isEqualTo(1L);

		HttpResult<CourseBillingPeriodResponse> conflictResult = results.stream()
			.filter(r -> r.getStatusCode() == HttpStatus.CONFLICT)
			.findFirst()
			.orElseThrow();
		// Fixed (Wave 2 QA gap): the new CourseBillingPeriod row is now
		// `saveAndFlush()`d, not `save()`d, inside addBillingPeriod's own
		// `catch (DataIntegrityViolationException ex)` block - so for a
		// GENUINE two-thread race, the loser's unique-constraint violation is
		// now actually thrown and caught INSIDE that method, and this is the
		// service's own dedicated "A current billing period already exists
		// for this course" ConflictException message, not
		// GlobalExceptionHandler#handleDataIntegrityViolation's generic
		// fallback message.
		assertThat(conflictResult.getBody().error().code()).isEqualTo("CONFLICT");
		assertThat(conflictResult.getBody().error().message())
			.isEqualTo("A current billing period already exists for this course");

		Long periodCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM course_billing_period cbp "
						+ "JOIN course_billing_configuration cbc ON cbp.billing_configuration_id = cbc.id "
						+ "WHERE cbc.course_id = ?",
				Long.class, course.id());
		assertThat(periodCount).isEqualTo(1L); // only the winner's row was ever persisted
	}

	/**
	 * Seeds tenant A (owner of the course) and tenant B (the cross-tenant
	 * attacker), returning tenant B's own credentials plus tenant A's real
	 * course id - the shape every cross-tenant negative test in this class
	 * needs.
	 */
	private CrossTenantFixture seedCrossTenantCourse(String prefix) {
		Tenant tenantA = seedActiveTenant(uniqueSubdomain(prefix + "-a"));
		Tenant tenantB = seedActiveTenant(uniqueSubdomain(prefix + "-b"));
		seedTenantUser(tenantA.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacherA = seedTenantUser(tenantA.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		seedTenantUser(tenantB.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String hostA = hostFor(tenantA.getSubdomain());
		String hostB = hostFor(tenantB.getSubdomain());
		String tokenA = loginAndGetToken(hostA, "admin@example.test");
		String tokenB = loginAndGetToken(hostB, "admin@example.test");
		CourseResponse course = createCourseOrFail(hostA, tokenA, newCourseRequest(uniqueSlug(prefix), teacherA.getId()));
		return new CrossTenantFixture(hostB, tokenB, course.id());
	}

	private record CrossTenantFixture(String hostB, String tokenB, UUID courseId) {

	}

}
