package com.lms.usermanagement.teacher.storefront;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.tenantmanagement.domain.Tenant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * ADR-037 decision 6 (amended 2026-11-21): on the institute (tenant) host the
 * PUBLIC pages hide teachers who are on a storefront (enabled + live) and every
 * course whose assigned teachers are ALL on a storefront. Shared courses with a
 * teacher not on a storefront stay. Storefront hosts, checkout and the student
 * portal are unchanged.
 *
 * <p>Fixture: Alice (storefront enabled), Bob (no storefront), Carol
 * (storefront enabled). Courses: {@code aliceListed} (Alice, listed on her
 * storefront), {@code aliceUnlisted} (Alice, not listed anywhere), {@code
 * bobOnly}, {@code sharedAliceBob} (Alice primary + Bob), {@code
 * sharedAliceCarol} (Alice primary + Carol, listed on both storefronts).
 */
class TeacherStorefrontInstituteVisibilityIntegrationTest extends StorefrontTestSupport {

	private static final String COURSES = "/api/v1/public/courses";

	private record Vx(Tenant tenant, String host, String aliceHost, String carolHost, String adminToken,
			String studentToken, TenantUser alice, TenantUser bob, TenantUser carol, Course aliceListed,
			Course aliceUnlisted, Course bobOnly, Course sharedAliceBob, Course sharedAliceCarol) {

		List<String> hiddenSlugs() {
			return List.of(aliceListed.slug(), aliceUnlisted.slug(), sharedAliceCarol.slug());
		}

		List<String> visibleSlugs() {
			return List.of(bobOnly.slug(), sharedAliceBob.slug());
		}

	}

	private Vx seedVisibility(String prefix) {
		Tenant tenant = seedActiveTenant(uniqueSubdomain(prefix));
		UUID tenantId = tenant.getId();
		seedTenantUser(tenantId, "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser alice = seedTenantUser(tenantId, "alice@example.test", RAW_PASSWORD, Role.TEACHER);
		TenantUser bob = seedTenantUser(tenantId, "bob@example.test", RAW_PASSWORD, Role.TEACHER);
		TenantUser carol = seedTenantUser(tenantId, "carol@example.test", RAW_PASSWORD, Role.TEACHER);
		seedTeacherProfile(tenantId, alice.getId(), "Alice Perera");
		seedTeacherProfile(tenantId, bob.getId(), "Bob Silva");
		seedTeacherProfile(tenantId, carol.getId(), "Carol Fernando");
		TenantUser student = seedActiveStudent(tenantId, "student@example.test");
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String studentToken = loginAndGetToken(host, "student@example.test");

		Course aliceListed = monthlyCourse(host, adminToken, alice.getId(), "vis-alice-listed");
		Course aliceUnlisted = monthlyCourse(host, adminToken, alice.getId(), "vis-alice-unlisted");
		Course bobOnly = monthlyCourse(host, adminToken, bob.getId(), "vis-bob");
		Course sharedAliceBob = monthlyCourse(host, adminToken, alice.getId(), "vis-alice-bob");
		coTeach(tenantId, sharedAliceBob.id(), bob.getId());
		Course sharedAliceCarol = monthlyCourse(host, adminToken, alice.getId(), "vis-alice-carol");
		coTeach(tenantId, sharedAliceCarol.id(), carol.getId());
		for (Course course : List.of(aliceListed, aliceUnlisted, bobOnly, sharedAliceBob, sharedAliceCarol)) {
			assertThat(status(call(host, studentToken, post("/api/v1/enrollments/my/courses/{courseId}",
					course.id())))).isEqualTo(200);
		}

		assertOk(upsert(host, adminToken, alice.getId(), "alice", true));
		assertOk(products(host, adminToken, alice.getId(), List.of(item(aliceListed.id(), true, 0),
				item(sharedAliceBob.id(), true, 1), item(sharedAliceCarol.id(), true, 2))));
		assertOk(upsert(host, adminToken, carol.getId(), "carol", true));
		assertOk(products(host, adminToken, carol.getId(), List.of(item(sharedAliceCarol.id(), true, 0))));

		String sub = tenant.getSubdomain();
		return new Vx(tenant, host, "alice." + sub + ".lms.test", "carol." + sub + ".lms.test", adminToken,
				studentToken, alice, bob, carol, aliceListed, aliceUnlisted, bobOnly, sharedAliceBob,
				sharedAliceCarol);
	}

	@Test
	void theInstituteCatalogHidesCoursesWhoseEveryTeacherIsOnAStorefront() {
		Vx vx = seedVisibility("vis-list");

		List<String> tenant = slugs(anonymousGet(vx.host(), COURSES + "?size=50"));
		assertThat(tenant).containsExactlyInAnyOrderElementsOf(vx.visibleSlugs());

		// Counts and pagination are computed on the visible set.
		JsonNode page = json(anonymousGet(vx.host(), COURSES + "?page=0&size=1")).path("data");
		assertThat(page.path("content")).hasSize(1);
		assertThat(page.path("totalElements").asLong()).isEqualTo(2);
		assertThat(page.path("totalPages").asInt()).isEqualTo(2);

		// Tags only come from visible courses.
		assertOk(anonymousGet(vx.host(), COURSES + "/tags"));

		// Detail: visible courses are served, hidden ones 404.
		assertOk(anonymousGet(vx.host(), COURSES + "/{slug}", vx.bobOnly().slug()));
		assertOk(anonymousGet(vx.host(), COURSES + "/{slug}", vx.sharedAliceBob().slug()));
		MvcResult listed = anonymousGet(vx.host(), COURSES + "/{slug}", vx.aliceListed().slug());
		assertThat(status(listed)).isEqualTo(404);
		assertThat(errorCode(listed)).isEqualTo("COURSE_ON_STOREFRONT");
		assertThat(json(listed).path("error").path("details").path("host").asString()).isEqualTo(vx.aliceHost());
		// Not listed on any storefront: the generic 404, no host hint.
		MvcResult unlisted = anonymousGet(vx.host(), COURSES + "/{slug}", vx.aliceUnlisted().slug());
		assertThat(status(unlisted)).isEqualTo(404);
		assertThat(errorCode(unlisted)).isEqualTo("NOT_FOUND");
		assertThat(json(unlisted).path("error").path("details").isMissingNode()
				|| json(unlisted).path("error").path("details").isNull()).isTrue();
		// Sold by two storefronts: ambiguous, so the generic 404.
		MvcResult twoStorefronts = anonymousGet(vx.host(), COURSES + "/{slug}", vx.sharedAliceCarol().slug());
		assertThat(status(twoStorefronts)).isEqualTo(404);
		assertThat(errorCode(twoStorefronts)).isEqualTo("NOT_FOUND");
	}

	@Test
	void theInstituteTeacherDirectoryHidesTeachersOnAStorefront() {
		Vx vx = seedVisibility("vis-teachers");

		JsonNode teachers = json(anonymousGet(vx.host(), "/api/v1/public/teachers")).path("data");
		List<String> names = new ArrayList<>();
		teachers.path("content").forEach(node -> names.add(node.path("name").asString()));
		assertThat(names).containsExactly("Bob Silva");
		assertThat(teachers.path("totalElements").asLong()).isEqualTo(1);

		assertThat(status(anonymousGet(vx.host(), "/api/v1/public/teachers/{id}", profileId(vx.alice()))))
			.isEqualTo(404);
		assertThat(status(anonymousGet(vx.host(), "/api/v1/public/teachers/{id}", profileId(vx.carol()))))
			.isEqualTo(404);
		assertOk(anonymousGet(vx.host(), "/api/v1/public/teachers/{id}", profileId(vx.bob())));
	}

	@Test
	void theInstituteHomeDoesNotFeatureHiddenCourses() {
		Vx vx = seedVisibility("vis-home");
		MvcResult branding = jsonCall(vx.host(), vx.adminToken(), put("/api/v1/tenant-config/{domain}", "BRANDING"),
				Map.of("featured_course_ids", List.of(vx.aliceListed().id().toString(), vx.bobOnly().id().toString(),
						vx.sharedAliceCarol().id().toString(), vx.sharedAliceBob().id().toString())));
		assertOk(branding);

		List<String> featured = new ArrayList<>();
		json(anonymousGet(vx.host(), "/api/v1/public/tenant-config/home")).path("data").path("featuredCourses")
			.forEach(node -> featured.add(node.path("slug").asString()));
		// Configured order is kept for what remains.
		assertThat(featured).containsExactly(vx.bobOnly().slug(), vx.sharedAliceBob().slug());
	}

	@Test
	void disablingTheStorefrontOrSuspendingTheTeacherShowsThemOnTheInstituteSiteAgain() {
		Vx vx = seedVisibility("vis-disable");

		assertOk(upsert(vx.host(), vx.adminToken(), vx.alice().getId(), "alice", false));
		// Alice is back; the Alice+Carol course now has a teacher not on a storefront.
		assertThat(slugs(anonymousGet(vx.host(), COURSES + "?size=50"))).containsExactlyInAnyOrder(
				vx.aliceListed().slug(), vx.aliceUnlisted().slug(), vx.bobOnly().slug(), vx.sharedAliceBob().slug(),
				vx.sharedAliceCarol().slug());
		assertOk(anonymousGet(vx.host(), COURSES + "/{slug}", vx.aliceListed().slug()));
		assertOk(anonymousGet(vx.host(), "/api/v1/public/teachers/{id}", profileId(vx.alice())));
		assertThat(status(anonymousGet(vx.host(), "/api/v1/public/teachers/{id}", profileId(vx.carol()))))
			.isEqualTo(404);

		assertOk(upsert(vx.host(), vx.adminToken(), vx.alice().getId(), "alice", true));
		assertThat(slugs(anonymousGet(vx.host(), COURSES + "?size=50")))
			.containsExactlyInAnyOrderElementsOf(vx.visibleSlugs());

		// A suspended teacher's storefront is not live, so the teacher is shown again.
		jdbcTemplate.update("UPDATE tenant_user SET status = 'suspended' WHERE id = ?", vx.alice().getId());
		assertThat(slugs(anonymousGet(vx.host(), COURSES + "?size=50"))).contains(vx.aliceListed().slug(),
				vx.aliceUnlisted().slug(), vx.sharedAliceCarol().slug());
		assertOk(anonymousGet(vx.host(), COURSES + "/{slug}", vx.aliceListed().slug()));
	}

	@Test
	void storefrontHostsAreUnchanged() {
		Vx vx = seedVisibility("vis-hosts");

		assertThat(slugs(anonymousGet(vx.aliceHost(), COURSES + "?size=50"))).containsExactlyInAnyOrder(
				vx.aliceListed().slug(), vx.sharedAliceBob().slug(), vx.sharedAliceCarol().slug());
		assertOk(anonymousGet(vx.aliceHost(), COURSES + "/{slug}", vx.aliceListed().slug()));
		assertThat(slugs(anonymousGet(vx.carolHost(), COURSES + "?size=50")))
			.containsExactly(vx.sharedAliceCarol().slug());
		JsonNode aliceTeachers = json(anonymousGet(vx.aliceHost(), "/api/v1/public/teachers")).path("data");
		assertThat(aliceTeachers.path("content")).hasSize(1);
		assertThat(aliceTeachers.path("content").get(0).path("name").asString()).isEqualTo("Alice Perera");
	}

	@Test
	void anEnrolledStudentStillChecksOutAHiddenCourseOnTheInstituteHost() {
		Vx vx = seedVisibility("vis-checkout");

		MvcResult quote = quote(vx.host(), vx.studentToken(), cart(List.of(september(vx.aliceUnlisted().id())), List.of()));
		assertThat(status(quote)).as(rawContent(quote)).isEqualTo(200);
		assertOk(quote(vx.host(), vx.studentToken(), cart(List.of(september(vx.aliceListed().id())), List.of())));
	}

	@Test
	void anotherTenantIsUnaffected() {
		Vx vx = seedVisibility("vis-tenant-a");
		Tenant other = seedActiveTenant(uniqueSubdomain("vis-tenant-b"));
		seedTenantUser(other.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser otherAlice = seedTenantUser(other.getId(), "alice@example.test", RAW_PASSWORD, Role.TEACHER);
		seedTeacherProfile(other.getId(), otherAlice.getId(), "Other Alice");
		String otherHost = hostFor(other.getSubdomain());
		String otherAdmin = loginAndGetToken(otherHost, "admin@example.test");
		Course otherCourse = monthlyCourse(otherHost, otherAdmin, otherAlice.getId(), "vis-other");

		// Tenant B has no storefront: its teacher and course stay on its site.
		assertThat(slugs(anonymousGet(otherHost, COURSES + "?size=50"))).containsExactly(otherCourse.slug());
		assertOk(anonymousGet(otherHost, COURSES + "/{slug}", otherCourse.slug()));
		JsonNode otherTeachers = json(anonymousGet(otherHost, "/api/v1/public/teachers")).path("data");
		assertThat(otherTeachers.path("content")).hasSize(1);
		// Tenant A's hidden course is not reachable (nor hinted) from tenant B.
		MvcResult foreign = anonymousGet(otherHost, COURSES + "/{slug}", vx.aliceListed().slug());
		assertThat(status(foreign)).isEqualTo(404);
		assertThat(errorCode(foreign)).isEqualTo("NOT_FOUND");
		// And tenant A is still filtered.
		assertThat(slugs(anonymousGet(vx.host(), COURSES + "?size=50")))
			.containsExactlyInAnyOrderElementsOf(vx.visibleSlugs());
	}

	private UUID profileId(TenantUser teacher) {
		return jdbcTemplate.queryForObject("SELECT id FROM teacher_profile WHERE user_id = ?", UUID.class,
				teacher.getId());
	}

}
