package com.lms.usermanagement.teacher.storefront;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.lms.identityaccessservice.domain.Role;
import com.lms.tenantmanagement.api.TenantStatus;
import com.lms.tenantmanagement.domain.Tenant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * STORE-01 / ADR-037: host resolution (unknown / disabled / retired / same
 * slug in two tenants), server-side catalog scoping on storefront hosts
 * (list, pagination, tags, detail, teachers, site-context) and the tenant
 * host hiding teachers on a storefront (ADR-037 decision 6, amended
 * 2026-11-21 - both fixture teachers have one, so their courses leave the
 * institute's public pages). Every request goes through the real {@code
 * TenantResolutionFilter} via the {@code Host} header.
 */
class TeacherStorefrontCatalogIntegrationTest extends StorefrontTestSupport {

	@Test
	void eachStorefrontListsOnlyItsPublishedProductsWhileTheTenantSiteHidesTheirCourses() {
		Fx fx = seed("sf-catalog");

		List<String> alice = slugs(anonymousGet(fx.aliceHost(), "/api/v1/public/courses?size=50"));
		assertThat(alice).containsExactlyInAnyOrder(fx.aliceOnly().slug(), fx.sharedAliceListed().slug(),
				fx.sharedBoth().slug());

		List<String> bob = slugs(anonymousGet(fx.bobHost(), "/api/v1/public/courses?size=50"));
		assertThat(bob).containsExactlyInAnyOrder(fx.bobOnly().slug(), fx.sharedBoth().slug());

		// ADR-037 decision 6 (amended): every fixture course is taught only by
		// teachers on a storefront, so the institute's public catalog hides them.
		List<String> tenant = slugs(anonymousGet(fx.host(), "/api/v1/public/courses?size=50"));
		assertThat(tenant).doesNotContain(fx.aliceOnly().slug(), fx.bobOnly().slug(), fx.sharedAliceListed().slug(),
				fx.sharedBoth().slug(), fx.aliceHidden().slug());

		// Counts and pagination are computed on the scoped set.
		JsonNode page = json(anonymousGet(fx.aliceHost(), "/api/v1/public/courses?page=0&size=2")).path("data");
		assertThat(page.path("content")).hasSize(2);
		assertThat(page.path("totalElements").asLong()).isEqualTo(3);
		assertThat(page.path("totalPages").asInt()).isEqualTo(2);

		// Detail: listed + published only; everything else is the generic 404.
		assertThat(status(anonymousGet(fx.aliceHost(), "/api/v1/public/courses/{slug}", fx.sharedAliceListed().slug())))
			.isEqualTo(200);
		assertThat(status(anonymousGet(fx.aliceHost(), "/api/v1/public/courses/{slug}", fx.bobOnly().slug())))
			.isEqualTo(404);
		assertThat(status(anonymousGet(fx.aliceHost(), "/api/v1/public/courses/{slug}", fx.aliceHidden().slug())))
			.isEqualTo(404);
		assertThat(status(anonymousGet(fx.bobHost(), "/api/v1/public/courses/{slug}", fx.sharedAliceListed().slug())))
			.isEqualTo(404);
		assertThat(status(anonymousGet(fx.bobHost(), "/api/v1/public/courses/{slug}", fx.sharedBoth().slug())))
			.isEqualTo(200);
		// The tenant site points to the one storefront selling a hidden course,
		// and answers the generic 404 when none (or several) does.
		MvcResult onAlice = anonymousGet(fx.host(), "/api/v1/public/courses/{slug}", fx.aliceOnly().slug());
		assertThat(status(onAlice)).isEqualTo(404);
		assertThat(errorCode(onAlice)).isEqualTo("COURSE_ON_STOREFRONT");
		assertThat(json(onAlice).path("error").path("details").path("host").asString()).isEqualTo(fx.aliceHost());
		assertThat(errorCode(anonymousGet(fx.host(), "/api/v1/public/courses/{slug}", fx.aliceHidden().slug())))
			.isEqualTo("NOT_FOUND");
		assertThat(errorCode(anonymousGet(fx.host(), "/api/v1/public/courses/{slug}", fx.sharedBoth().slug())))
			.isEqualTo("NOT_FOUND");
		assertThat(status(anonymousGet(fx.aliceHost(), "/api/v1/public/courses/tags"))).isEqualTo(200);
	}

	@Test
	void aListedCourseThatIsNotPublicOnTheTenantCatalogIsNotSoldOnTheStorefront() {
		Fx fx = seed("sf-private");
		jdbcTemplate.update("UPDATE course SET status = 'PRIVATE' WHERE id = ?", fx.aliceOnly().id());

		assertThat(slugs(anonymousGet(fx.aliceHost(), "/api/v1/public/courses?size=50")))
			.doesNotContain(fx.aliceOnly().slug());
		assertThat(status(anonymousGet(fx.aliceHost(), "/api/v1/public/courses/{slug}", fx.aliceOnly().slug())))
			.isEqualTo(404);
		MvcResult quote = quote(fx.aliceHost(), fx.studentToken(), cart(List.of(september(fx.aliceOnly().id())), List.of()));
		assertThat(status(quote)).isEqualTo(422);
		assertThat(errorCode(quote)).isEqualTo("STOREFRONT_PRODUCT_OUT_OF_SCOPE");
	}

	@Test
	void siteContextAndPublicTeachersReflectTheHost() {
		Fx fx = seed("sf-context");

		JsonNode tenantSite = json(anonymousGet(fx.host(), "/api/v1/public/site-context")).path("data");
		assertThat(tenantSite.path("kind").asString()).isEqualTo("TENANT");
		assertThat(tenantSite.path("storefront").isNull()).isTrue();

		JsonNode aliceSite = json(anonymousGet(fx.aliceHost(), "/api/v1/public/site-context")).path("data");
		assertThat(aliceSite.path("kind").asString()).isEqualTo("STOREFRONT");
		assertThat(aliceSite.path("storefront").path("slug").asString()).isEqualTo("alice");
		assertThat(aliceSite.path("storefront").path("teacherId").asString()).isEqualTo(fx.alice().getId().toString());
		assertThat(aliceSite.path("storefront").path("displayName").asString()).isEqualTo("Alice Perera");
		assertThat(aliceSite.path("storefront").path("bio").asString()).isEqualTo("Alice Perera teaches physics.");
		assertThat(aliceSite.path("storefront").path("host").asString()).isEqualTo(fx.aliceHost());

		JsonNode teachers = json(anonymousGet(fx.aliceHost(), "/api/v1/public/teachers")).path("data");
		assertThat(teachers.path("content")).hasSize(1);
		assertThat(teachers.path("content").get(0).path("name").asString()).isEqualTo("Alice Perera");
		List<String> courseSlugs = new java.util.ArrayList<>();
		teachers.path("content").get(0).path("courses").forEach(c -> courseSlugs.add(c.path("slug").asString()));
		assertThat(courseSlugs).contains(fx.aliceOnly().slug()).doesNotContain(fx.aliceHidden().slug());

		UUID bobProfileId = jdbcTemplate.queryForObject("SELECT id FROM teacher_profile WHERE user_id = ?", UUID.class,
				fx.bob().getId());
		assertThat(status(anonymousGet(fx.aliceHost(), "/api/v1/public/teachers/{id}", bobProfileId))).isEqualTo(404);
		// ADR-037 decision 6 (amended): both teachers have a storefront, so the
		// institute directory hides them; disabling Bob's brings him back.
		assertThat(status(anonymousGet(fx.host(), "/api/v1/public/teachers/{id}", bobProfileId))).isEqualTo(404);
		assertThat(json(anonymousGet(fx.host(), "/api/v1/public/teachers")).path("data").path("content")).isEmpty();
		assertOk(upsert(fx.host(), fx.adminToken(), fx.bob().getId(), "bob", false));
		assertThat(status(anonymousGet(fx.host(), "/api/v1/public/teachers/{id}", bobProfileId))).isEqualTo(200);
		assertThat(json(anonymousGet(fx.host(), "/api/v1/public/teachers")).path("data").path("content")).hasSize(1);
	}

	@Test
	void unknownDisabledAndRetiredHostsNeverFallBackToTheTenantCatalog() {
		Fx fx = seed("sf-hosts");
		String sub = fx.tenant().getSubdomain();

		MvcResult unknown = anonymousGet("nobody." + sub + ".lms.test", "/api/v1/public/courses");
		assertThat(status(unknown)).isEqualTo(404);
		assertThat(errorCode(unknown)).isEqualTo("STOREFRONT_NOT_FOUND");
		// Every /api path, including sign-in, is refused on such a host.
		assertThat(errorCode(anonymousGet("nobody." + sub + ".lms.test", "/api/v1/public/site-context")))
			.isEqualTo("STOREFRONT_NOT_FOUND");
		assertThat(login("nobody." + sub + ".lms.test", "student@example.test", RAW_PASSWORD).getStatusCode().value())
			.isEqualTo(404);
		// A reserved word is never a storefront.
		assertThat(errorCode(anonymousGet("www." + sub + ".lms.test", "/api/v1/public/courses")))
			.isEqualTo("STOREFRONT_NOT_FOUND");

		// Rename Alice: the old slug redirects while the storefront is enabled.
		assertOk(upsert(fx.host(), fx.adminToken(), fx.alice().getId(), "alice-perera", null));
		MvcResult moved = anonymousGet(fx.aliceHost(), "/api/v1/public/courses");
		assertThat(status(moved)).isEqualTo(404);
		assertThat(errorCode(moved)).isEqualTo("STOREFRONT_MOVED");
		assertThat(json(moved).path("error").path("details").path("host").asString())
			.isEqualTo("alice-perera." + sub + ".lms.test");
		assertThat(status(anonymousGet("alice-perera." + sub + ".lms.test", "/api/v1/public/courses"))).isEqualTo(200);

		// Disabled: current and retired slugs are both plain not-found.
		assertOk(upsert(fx.host(), fx.adminToken(), fx.alice().getId(), "alice-perera", false));
		assertThat(errorCode(anonymousGet("alice-perera." + sub + ".lms.test", "/api/v1/public/courses")))
			.isEqualTo("STOREFRONT_NOT_FOUND");
		assertThat(errorCode(anonymousGet(fx.aliceHost(), "/api/v1/public/courses"))).isEqualTo("STOREFRONT_NOT_FOUND");
		// The tenant site is unaffected.
		assertThat(status(anonymousGet(fx.host(), "/api/v1/public/courses"))).isEqualTo(200);
	}

	@Test
	void aSuspendedTeachersStorefrontFailsClosed() {
		Fx fx = seed("sf-teacher-suspended");
		jdbcTemplate.update("UPDATE tenant_user SET status = 'suspended' WHERE id = ?", fx.alice().getId());

		MvcResult suspended = anonymousGet(fx.aliceHost(), "/api/v1/public/courses");
		assertThat(status(suspended)).isEqualTo(404);
		assertThat(errorCode(suspended)).isEqualTo("STOREFRONT_NOT_FOUND");
		// The other teacher's storefront and the tenant site are unaffected.
		assertThat(status(anonymousGet(fx.bobHost(), "/api/v1/public/courses"))).isEqualTo(200);
		assertThat(status(anonymousGet(fx.host(), "/api/v1/public/courses"))).isEqualTo(200);
	}

	@Test
	void theTenantIsResolvedFirstWithTheUsualStatusChecks() {
		Fx fx = seed("sf-tenant-first");
		// Unknown tenant label: the generic tenant answer, not a storefront answer.
		MvcResult unknownTenant = anonymousGet("alice." + uniqueSubdomain("nope") + ".lms.test", "/api/v1/public/courses");
		assertThat(status(unknownTenant)).isEqualTo(403);
		assertThat(errorCode(unknownTenant)).isEqualTo("TENANT_UNAVAILABLE");

		jdbcTemplate.update("UPDATE tenant SET status = ? WHERE id = ?", TenantStatus.SUSPENDED.toColumnValue(),
				fx.tenant().getId());
		MvcResult suspended = anonymousGet(fx.aliceHost(), "/api/v1/public/courses");
		assertThat(status(suspended)).isEqualTo(403);
		assertThat(errorCode(suspended)).isEqualTo("TENANT_UNAVAILABLE");
	}

	@Test
	void theSameSlugInTwoTenantsResolvesWithinEachTenantOnly() {
		Fx fx = seed("sf-two-a");
		Tenant other = seedActiveTenant(uniqueSubdomain("sf-two-b"));
		seedTenantUser(other.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		var otherAlice = seedTenantUser(other.getId(), "alice@example.test", RAW_PASSWORD, Role.TEACHER);
		seedTeacherProfile(other.getId(), otherAlice.getId(), "Other Alice");
		String otherHost = hostFor(other.getSubdomain());
		String otherAdmin = loginAndGetToken(otherHost, "admin@example.test");
		Course otherCourse = monthlyCourse(otherHost, otherAdmin, otherAlice.getId(), "other-physics");
		assertOk(upsert(otherHost, otherAdmin, otherAlice.getId(), "alice", true));
		assertOk(products(otherHost, otherAdmin, otherAlice.getId(), List.of(item(otherCourse.id(), true, 0))));
		String otherAliceHost = "alice." + other.getSubdomain() + ".lms.test";

		assertThat(slugs(anonymousGet(otherAliceHost, "/api/v1/public/courses?size=50")))
			.containsExactly(otherCourse.slug());
		assertThat(slugs(anonymousGet(fx.aliceHost(), "/api/v1/public/courses?size=50")))
			.doesNotContain(otherCourse.slug());
		assertThat(status(anonymousGet(fx.aliceHost(), "/api/v1/public/courses/{slug}", otherCourse.slug())))
			.isEqualTo(404);
		assertThat(json(anonymousGet(otherAliceHost, "/api/v1/public/site-context")).path("data").path("storefront")
			.path("displayName").asString()).isEqualTo("Other Alice");
	}

	@Test
	void aStudentSignsInOnAStorefrontHostAgainstTheSameTenantAccount() {
		Fx fx = seed("sf-login");
		var response = login(fx.aliceHost(), "student@example.test", RAW_PASSWORD);
		assertThat(response.getStatusCode().value()).isEqualTo(200);
		String token = response.getBody().data().accessToken();
		assertThat(status(call(fx.aliceHost(), token, get("/api/v1/checkout/courses").param("scope", "MONTHLY"))))
			.isEqualTo(200);
		assertThat(status(call(fx.aliceHost(), token, post("/api/v1/enrollments/my/courses/{courseId}",
				fx.aliceOnly().id())))).isEqualTo(200);
	}

}
