package com.lms.coursemanagement.course.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.lms.common.api.PageResponse;
import com.lms.coursemanagement.CourseManagementTestSupport;
import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.coursemanagement.course.web.dto.PublicCourseResponse;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.tenantmanagement.domain.Tenant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Testcontainers-backed coverage for Course Management (MVP-008)'s anonymous
 * public storefront read path: {@code GET /api/v1/public/courses} and
 * {@code GET /api/v1/public/courses/{slug}} (plan §14/§15(d)). Every test
 * here goes through the real {@code TenantResolutionFilter} (via the {@code
 * Host} header), never authenticates, and never supplies a client-side
 * tenant identifier.
 */
class CoursePublicStorefrontIntegrationTest extends CourseManagementTestSupport {

	@Test
	void draftCourseNeverAppearsOnThePublicStorefrontListingOrDetail() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("storefront-draft"));
		seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "teacher@example.test");
		String slug = uniqueSlug("draft-course");
		createCourseOrFail(host, token, newCourseRequest(slug, null, CourseStatus.DRAFT));

		HttpResult<PageResponse<PublicCourseResponse>> listResult = listPublicCourses(host);
		assertThat(listResult.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(listResult.getBody().data().content()).extracting(PublicCourseResponse::slug).doesNotContain(slug);

		HttpResult<PublicCourseResponse> detailResult = getPublicCourse(host, slug);
		assertThat(detailResult.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(detailResult.getBody().error().code()).isEqualTo("NOT_FOUND");
	}

	@Test
	void privateCourseNeverAppearsOnThePublicStorefrontListingOrDetail() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("storefront-private"));
		seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "teacher@example.test");
		String slug = uniqueSlug("private-course");
		createCourseOrFail(host, token, newCourseRequest(slug, null, CourseStatus.PRIVATE));

		HttpResult<PageResponse<PublicCourseResponse>> listResult = listPublicCourses(host);
		assertThat(listResult.getBody().data().content()).extracting(PublicCourseResponse::slug).doesNotContain(slug);

		HttpResult<PublicCourseResponse> detailResult = getPublicCourse(host, slug);
		assertThat(detailResult.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void publicCourseAppearsOnListingAndIsReachableByDetailSlug() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("storefront-public"));
		seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "teacher@example.test");
		String slug = uniqueSlug("public-course");
		CourseResponse created = createCourseOrFail(host, token, newCourseRequest(slug, null, CourseStatus.PUBLIC));

		HttpResult<PageResponse<PublicCourseResponse>> listResult = listPublicCourses(host);
		assertThat(listResult.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(listResult.getBody().data().content()).extracting(PublicCourseResponse::slug).contains(slug);

		HttpResult<PublicCourseResponse> detailResult = getPublicCourse(host, slug);
		assertThat(detailResult.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(detailResult.getBody().data().id()).isEqualTo(created.id());
		assertThat(detailResult.getBody().data().price()).isEqualByComparingTo(created.price());
	}

	@Test
	void aGuessedDraftOrPrivateSlugInTheSameTenantAsAKnownPublicCourseStillReturnsTheSameGeneric404() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("storefront-guess"));
		seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "teacher@example.test");
		String draftSlug = uniqueSlug("guess-draft");
		createCourseOrFail(host, token, newCourseRequest(draftSlug, null, CourseStatus.DRAFT));

		HttpResult<PublicCourseResponse> draftResult = getPublicCourse(host, draftSlug);
		HttpResult<PublicCourseResponse> nonexistentResult = getPublicCourse(host, uniqueSlug("truly-nonexistent"));

		assertThat(draftResult.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(nonexistentResult.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(draftResult.getBody().error().message()).isEqualTo(nonexistentResult.getBody().error().message());
	}

	@Test
	void theSameSlugInTwoDifferentTenantsResolvesToEachTenantsOwnPublicCourseNeverTheOtherTenants() {
		Tenant tenantA = seedActiveTenant(uniqueSubdomain("storefront-collide-a"));
		Tenant tenantB = seedActiveTenant(uniqueSubdomain("storefront-collide-b"));
		seedTenantUser(tenantA.getId(), "teacher-a@example.test", RAW_PASSWORD, Role.TEACHER);
		seedTenantUser(tenantB.getId(), "teacher-b@example.test", RAW_PASSWORD, Role.TEACHER);
		String hostA = hostFor(tenantA.getSubdomain());
		String hostB = hostFor(tenantB.getSubdomain());
		String tokenA = loginAndGetToken(hostA, "teacher-a@example.test");
		String tokenB = loginAndGetToken(hostB, "teacher-b@example.test");
		String sharedSlug = uniqueSlug("collide");
		CourseResponse courseA = createCourseOrFail(hostA, tokenA,
				newCourseRequest(sharedSlug, null, CourseStatus.PUBLIC));
		CourseResponse courseB = createCourseOrFail(hostB, tokenB,
				newCourseRequest(sharedSlug, null, CourseStatus.PUBLIC));
		assertThat(courseA.id()).isNotEqualTo(courseB.id());

		HttpResult<PublicCourseResponse> fromHostA = getPublicCourse(hostA, sharedSlug);
		HttpResult<PublicCourseResponse> fromHostB = getPublicCourse(hostB, sharedSlug);

		assertThat(fromHostA.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(fromHostA.getBody().data().id()).isEqualTo(courseA.id());
		assertThat(fromHostB.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(fromHostB.getBody().data().id()).isEqualTo(courseB.id());
	}

	@Test
	void publicCourseInTenantAIsNotVisibleFromTenantBsStorefrontListing() {
		Tenant tenantA = seedActiveTenant(uniqueSubdomain("storefront-scope-a"));
		Tenant tenantB = seedActiveTenant(uniqueSubdomain("storefront-scope-b"));
		seedTenantUser(tenantA.getId(), "teacher-a@example.test", RAW_PASSWORD, Role.TEACHER);
		seedTenantUser(tenantB.getId(), "teacher-b@example.test", RAW_PASSWORD, Role.TEACHER);
		String hostA = hostFor(tenantA.getSubdomain());
		String hostB = hostFor(tenantB.getSubdomain());
		String tokenA = loginAndGetToken(hostA, "teacher-a@example.test");
		String tokenB = loginAndGetToken(hostB, "teacher-b@example.test");
		String slug = uniqueSlug("scope-only-a");
		createCourseOrFail(hostA, tokenA, newCourseRequest(slug, null, CourseStatus.PUBLIC));
		// Tenant B's own, legitimate published course - so the listing
		// assertion below proves isolation by positive content (contains its
		// own, excludes tenant A's), not merely "the list happened to be
		// empty," which would also pass a bug that ignored tenant scoping
		// entirely but coincidentally matched nothing else.
		String slugB = uniqueSlug("scope-only-b");
		createCourseOrFail(hostB, tokenB, newCourseRequest(slugB, null, CourseStatus.PUBLIC));

		HttpResult<PageResponse<PublicCourseResponse>> listFromB = listPublicCourses(hostB);
		assertThat(listFromB.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(listFromB.getBody().data().content()).extracting(PublicCourseResponse::slug)
			.contains(slugB)
			.doesNotContain(slug);

		HttpResult<PublicCourseResponse> detailFromB = getPublicCourse(hostB, slug);
		assertThat(detailFromB.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

		HttpResult<PublicCourseResponse> ownDetailFromB = getPublicCourse(hostB, slugB);
		assertThat(ownDetailFromB.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	void publicListingIgnoresAnyClientSuppliedTenantIdQueryParameterAndStaysScopedToTheSubdomain() {
		Tenant tenantA = seedActiveTenant(uniqueSubdomain("storefront-param-a"));
		Tenant tenantB = seedActiveTenant(uniqueSubdomain("storefront-param-b"));
		seedTenantUser(tenantB.getId(), "teacher-b@example.test", RAW_PASSWORD, Role.TEACHER);
		String hostA = hostFor(tenantA.getSubdomain());
		String hostB = hostFor(tenantB.getSubdomain());
		String tokenB = loginAndGetToken(hostB, "teacher-b@example.test");
		String tenantBOnlySlug = uniqueSlug("param-only-b");
		createCourseOrFail(hostB, tokenB, newCourseRequest(tenantBOnlySlug, null, CourseStatus.PUBLIC));

		// Request tenant A's own storefront (via Host) while attempting to
		// smuggle tenant B's id as a query param - CoursePublicController
		// binds no such parameter, so this must have zero effect; the
		// listing must remain scoped to tenant A (empty), never leak tenant
		// B's course.
		MockHttpServletRequestBuilder builder = get("/api/v1/public/courses").param("tenantId",
				tenantB.getId().toString());
		builder.header(HttpHeaders.HOST, hostA);
		HttpResult<PageResponse<PublicCourseResponse>> result = parsePage(perform(builder), PublicCourseResponse.class);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(result.getBody().data().content()).extracting(PublicCourseResponse::slug)
			.doesNotContain(tenantBOnlySlug);
	}

	@Test
	void publicStorefrontListingIsPaginatedAndReturnsABoundedPageWithCorrectTotals() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("storefront-page-bounds"));
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, seedTeacherAndReturnEmail(tenant));
		for (int i = 0; i < 3; i++) {
			createCourseOrFail(host, token, newCourseRequest(uniqueSlug("storefront-page-" + i), null, CourseStatus.PUBLIC));
		}

		HttpResult<PageResponse<PublicCourseResponse>> firstPage = listPublicCourses(host, "page=0&size=2");

		assertThat(firstPage.getStatusCode()).isEqualTo(HttpStatus.OK);
		PageResponse<PublicCourseResponse> body = firstPage.getBody().data();
		assertThat(body.content()).hasSize(2);
		assertThat(body.totalElements()).isEqualTo(3);
		assertThat(body.totalPages()).isEqualTo(2);
	}

	private String seedTeacherAndReturnEmail(Tenant tenant) {
		String email = "teacher-" + tenant.getId() + "@example.test";
		seedTenantUser(tenant.getId(), email, RAW_PASSWORD, Role.TEACHER);
		return email;
	}

	// ------------------------------------------------------------------
	// Pricing-model-aware storefront exposure (Wave 2 QA gap fix): the
	// storefront must be able to correctly render "Free"/"$X per
	// month"/"$X per session"/"Contact us" without ever 500ing or silently
	// falling back to $0.
	// ------------------------------------------------------------------

	@Test
	void oneTimePricedPublicCourseExposesItsStaticPriceAsTheResolvedAmount() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("storefront-one-time"));
		String token = loginAndGetToken(hostFor(tenant.getSubdomain()), seedTeacherAndReturnEmail(tenant));
		String host = hostFor(tenant.getSubdomain());
		String slug = uniqueSlug("one-time-course");
		CourseResponse created = createCourseOrFail(host, token, newCourseRequest(slug, null, CourseStatus.PUBLIC));

		HttpResult<PublicCourseResponse> detail = getPublicCourse(host, slug);

		assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(detail.getBody().data().pricingModel())
			.isEqualTo(com.lms.coursemanagement.course.domain.CoursePricingModel.ONE_TIME);
		assertThat(detail.getBody().data().resolvedAmount()).isEqualByComparingTo(created.price());
		assertThat(detail.getBody().data().requiresManualQuote()).isFalse();
	}

	@Test
	void monthlyPricedPublicCourseWithAnOpenBillingPeriodExposesItsCurrentPeriodAmount() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("storefront-monthly"));
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, seedTeacherAndReturnEmail(tenant));
		String slug = uniqueSlug("monthly-course");
		CourseResponse created = createCourseOrFail(host, token, newCourseRequest(slug, null, CourseStatus.PUBLIC));
		changePricingModel(host, token, created.id(), com.lms.coursemanagement.course.domain.CoursePricingModel.MONTHLY);
		createOrUpdateBillingConfiguration(host, token, created.id(), null, "USD", false);
		addBillingPeriod(host, token, created.id(), new java.math.BigDecimal("40.00"), "USD",
				java.time.Instant.now());

		HttpResult<PublicCourseResponse> detail = getPublicCourse(host, slug);

		assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(detail.getBody().data().pricingModel())
			.isEqualTo(com.lms.coursemanagement.course.domain.CoursePricingModel.MONTHLY);
		assertThat(detail.getBody().data().resolvedAmount()).isEqualByComparingTo("40.00");
		assertThat(detail.getBody().data().currency()).isEqualTo("USD");
		assertThat(detail.getBody().data().requiresManualQuote()).isFalse();
	}

	@Test
	void monthlyPricedPublicCourseWithNoBillingConfiguredYetExposesANullResolvedAmountRatherThanErroringOrZero() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("storefront-monthly-unset"));
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, seedTeacherAndReturnEmail(tenant));
		String slug = uniqueSlug("monthly-unset-course");
		CourseResponse created = createCourseOrFail(host, token, newCourseRequest(slug, null, CourseStatus.PUBLIC));
		changePricingModel(host, token, created.id(), com.lms.coursemanagement.course.domain.CoursePricingModel.MONTHLY);
		// Deliberately never calls createOrUpdateBillingConfiguration/
		// addBillingPeriod - this course is MONTHLY-priced but genuinely not
		// yet ready to sell.

		HttpResult<PageResponse<PublicCourseResponse>> listResult = listPublicCourses(host);
		HttpResult<PublicCourseResponse> detail = getPublicCourse(host, slug);

		assertThat(listResult.getStatusCode()).isEqualTo(HttpStatus.OK); // never a 500
		assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK); // never a 500
		assertThat(detail.getBody().data().resolvedAmount()).isNull();
		assertThat(detail.getBody().data().currency()).isNull();
		assertThat(detail.getBody().data().requiresManualQuote()).isFalse();
	}

	@Test
	void customPricedPublicCourseExposesRequiresManualQuoteTrueWithNullAmount() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("storefront-custom"));
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, seedTeacherAndReturnEmail(tenant));
		String slug = uniqueSlug("custom-course");
		CourseResponse created = createCourseOrFail(host, token, newCourseRequest(slug, null, CourseStatus.PUBLIC));
		changePricingModel(host, token, created.id(), com.lms.coursemanagement.course.domain.CoursePricingModel.CUSTOM);
		createOrUpdateBillingConfiguration(host, token, created.id(), null, "USD", true);

		HttpResult<PublicCourseResponse> detail = getPublicCourse(host, slug);

		assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(detail.getBody().data().resolvedAmount()).isNull();
		assertThat(detail.getBody().data().requiresManualQuote()).isTrue();
	}

}
