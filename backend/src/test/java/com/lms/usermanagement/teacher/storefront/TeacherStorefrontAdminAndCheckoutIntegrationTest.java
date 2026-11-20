package com.lms.usermanagement.teacher.storefront;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.lms.identityaccessservice.domain.Role;
import com.lms.paymentmanagement.order.web.dto.PaymentInitiationResponse;
import com.lms.tenantmanagement.domain.Tenant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * STORE-01 / ADR-037: storefront administration (slug rules and retirement,
 * product assignment validation, capability gating, audit) and checkout on a
 * storefront host (out-of-scope 422, order-line attribution, entitlements kept
 * after the storefront is disabled).
 */
class TeacherStorefrontAdminAndCheckoutIntegrationTest extends StorefrontTestSupport {

	// ------------------------------------------------------------------
	// Slug rules.
	// ------------------------------------------------------------------

	@Test
	void slugRulesInvalidReservedTakenAndRetiredSlugsAreNeverReassigned() {
		Fx fx = seed("sf-slugs");
		UUID alice = fx.alice().getId();
		UUID bob = fx.bob().getId();
		for (String invalid : List.of("ab", "-abc", "abc-", "a--bc", "has space", "under_score",
				"a".repeat(41), "")) {
			MvcResult result = upsert(fx.host(), fx.adminToken(), alice, invalid, true);
			assertThat(status(result)).as(invalid).isEqualTo(400);
			assertThat(errorCode(result)).as(invalid).isEqualTo("STOREFRONT_SLUG_INVALID");
		}
		for (String reserved : List.of("www", "api", "admin", "teacher", "blog")) {
			MvcResult result = upsert(fx.host(), fx.adminToken(), alice, reserved, true);
			assertThat(status(result)).as(reserved).isEqualTo(400);
			assertThat(errorCode(result)).as(reserved).isEqualTo("STOREFRONT_SLUG_RESERVED");
		}
		// Another teacher's current slug.
		MvcResult taken = upsert(fx.host(), fx.adminToken(), bob, "alice", true);
		assertThat(status(taken)).isEqualTo(409);
		assertThat(errorCode(taken)).isEqualTo("STOREFRONT_SLUG_TAKEN");

		// Alice moves on; her old slug stays hers forever.
		MvcResult renamed = upsert(fx.host(), fx.adminToken(), alice, "Alice-Physics", null);
		assertOk(renamed);
		assertThat(json(renamed).path("data").path("slug").asString()).isEqualTo("alice-physics");
		assertThat(json(renamed).path("data").path("previousSlugs").get(0).asString()).isEqualTo("alice");
		assertThat(json(renamed).path("data").path("enabled").asBoolean()).isTrue();
		MvcResult reuse = upsert(fx.host(), fx.adminToken(), bob, "alice", true);
		assertThat(status(reuse)).isEqualTo(409);
		assertThat(errorCode(reuse)).isEqualTo("STOREFRONT_SLUG_TAKEN");

		// Alice may take her own retired slug back.
		MvcResult back = upsert(fx.host(), fx.adminToken(), alice, "alice", true);
		assertOk(back);
		assertThat(json(back).path("data").path("slug").asString()).isEqualTo("alice");
		assertThat(json(back).path("data").path("previousSlugs").get(0).asString()).isEqualTo("alice-physics");
		assertThat(count("SELECT count(*) FROM teacher_storefront_slug WHERE tenant_id = ? AND storefront_id = "
				+ "(SELECT id FROM teacher_storefront WHERE teacher_id = ?)", fx.tenant().getId(), alice)).isEqualTo(2);
		assertThat(json(back).path("data").path("previewUrl").asString())
			.isEqualTo("http://alice." + fx.tenant().getSubdomain() + ".lms.test");
		assertThat(json(back).path("data").path("publishedUrl").asString())
			.isEqualTo("http://alice." + fx.tenant().getSubdomain() + ".lms.test");

		// Audited, in the caller's tenant.
		assertThat(count("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'teacher_storefront.created'",
				fx.tenant().getId())).isEqualTo(2);
		assertThat(count("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = "
				+ "'teacher_storefront.slug_changed'", fx.tenant().getId())).isEqualTo(2);

		// Disable: audited; publishedUrl gone.
		MvcResult disabled = upsert(fx.host(), fx.adminToken(), alice, "alice", false);
		assertOk(disabled);
		assertThat(json(disabled).path("data").path("publishedUrl").isNull()).isTrue();
		assertThat(count("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = "
				+ "'teacher_storefront.disabled'", fx.tenant().getId())).isEqualTo(1);
	}

	@Test
	void theSameSlugIsAllowedInAnotherTenant() {
		seed("sf-same-a");
		Tenant other = seedActiveTenant(uniqueSubdomain("sf-same-b"));
		seedTenantUser(other.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		var teacher = seedTenantUser(other.getId(), "t@example.test", RAW_PASSWORD, Role.TEACHER);
		seedTeacherProfile(other.getId(), teacher.getId(), "Teacher");
		String host = hostFor(other.getSubdomain());
		assertOk(upsert(host, loginAndGetToken(host, "admin@example.test"), teacher.getId(), "alice", true));
	}

	// ------------------------------------------------------------------
	// Products.
	// ------------------------------------------------------------------

	@Test
	void productsAreLimitedToAssignedCoursesOfTheCallersTenant() {
		Fx fx = seed("sf-products");
		// Bob is not assigned to Alice's own course.
		MvcResult unassigned = products(fx.host(), fx.adminToken(), fx.bob().getId(),
				List.of(item(fx.aliceOnly().id(), true, 0)));
		assertThat(status(unassigned)).isEqualTo(422);
		assertThat(errorCode(unassigned)).isEqualTo("STOREFRONT_COURSE_NOT_ASSIGNED");

		// Another tenant's course id.
		Fx other = seed("sf-products-other");
		MvcResult foreign = products(fx.host(), fx.adminToken(), fx.alice().getId(),
				List.of(item(other.aliceOnly().id(), true, 0)));
		assertThat(status(foreign)).isEqualTo(422);
		assertThat(errorCode(foreign)).isEqualTo("STOREFRONT_COURSE_NOT_ASSIGNED");
		// Nothing changed.
		assertThat(count("SELECT count(*) FROM teacher_storefront_product WHERE tenant_id = ? AND teacher_id = ?",
				fx.tenant().getId(), fx.alice().getId())).isEqualTo(4);

		// Duplicate course ids are refused.
		assertThat(status(products(fx.host(), fx.adminToken(), fx.alice().getId(),
				List.of(item(fx.aliceOnly().id(), true, 0), item(fx.aliceOnly().id(), true, 1))))).isEqualTo(400);

		// Full replace: unlisting removes; assignable courses are the teacher's assigned ones.
		MvcResult replaced = products(fx.host(), fx.adminToken(), fx.alice().getId(),
				List.of(item(fx.sharedBoth().id(), true, 0)));
		assertOk(replaced);
		JsonNode data = json(replaced).path("data");
		assertThat(data.path("products")).hasSize(1);
		assertThat(data.path("products").get(0).path("courseSlug").asString()).isEqualTo(fx.sharedBoth().slug());
		List<String> assignable = new ArrayList<>();
		data.path("assignableCourses").forEach(c -> assignable.add(c.path("slug").asString()));
		assertThat(assignable).containsExactlyInAnyOrder(fx.aliceOnly().slug(), fx.sharedAliceListed().slug(),
				fx.sharedBoth().slug(), fx.aliceHidden().slug());
		assertThat(count("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = "
				+ "'teacher_storefront.products_changed'", fx.tenant().getId())).isEqualTo(3);

		// A teacher with no storefront yet cannot be stocked.
		var carol = seedTenantUser(fx.tenant().getId(), "carol@example.test", RAW_PASSWORD, Role.TEACHER);
		seedTeacherProfile(fx.tenant().getId(), carol.getId(), "Carol");
		assertThat(status(products(fx.host(), fx.adminToken(), carol.getId(), List.of()))).isEqualTo(404);
	}

	// ------------------------------------------------------------------
	// Permissions and cross-tenant access.
	// ------------------------------------------------------------------

	@Test
	void onlyHoldersOfTheCapabilityManageStorefronts() {
		Fx fx = seed("sf-perms");
		UUID tenantId = fx.tenant().getId();

		// Teacher: never granted - not even for their own storefront.
		MvcResult teacherPut = upsert(fx.host(), fx.aliceToken(), fx.alice().getId(), "alice-new", true);
		assertThat(status(teacherPut)).isEqualTo(403);
		assertThat(status(products(fx.host(), fx.aliceToken(), fx.alice().getId(), List.of()))).isEqualTo(403);
		assertThat(status(call(fx.host(), fx.aliceToken(), get(PATH)))).isEqualTo(403);
		assertThat(status(call(fx.host(), fx.aliceToken(), get(PATH + "/{id}", fx.alice().getId())))).isEqualTo(403);
		// ...but may read their own, without the assignable list.
		MvcResult own = call(fx.host(), fx.aliceToken(), get(PATH + "/me"));
		assertOk(own);
		assertThat(json(own).path("data").path("slug").asString()).isEqualTo("alice");
		assertThat(json(own).path("data").has("assignableCourses")).isFalse();
		assertThat(status(call(fx.host(), fx.adminToken(), get(PATH + "/me")))).isEqualTo(403);
		var carol = seedTenantUser(tenantId, "carol@example.test", RAW_PASSWORD, Role.TEACHER);
		seedTeacherProfile(tenantId, carol.getId(), "Carol");
		assertThat(status(call(fx.host(), loginAndGetToken(fx.host(), "carol@example.test"), get(PATH + "/me"))))
			.isEqualTo(404);

		// Student: denied.
		assertThat(status(upsert(fx.host(), fx.studentToken(), fx.alice().getId(), "x-student", true))).isEqualTo(403);

		// Operator: default off, configurable on.
		seedTenantUser(tenantId, "operator@example.test", RAW_PASSWORD, Role.OPERATOR);
		String operator = loginAndGetToken(fx.host(), "operator@example.test");
		assertThat(status(call(fx.host(), operator, get(PATH)))).isEqualTo(403);
		assertThat(status(upsert(fx.host(), operator, fx.alice().getId(), "alice", false))).isEqualTo(403);
		jdbcTemplate.update("INSERT INTO tenant_role_capability_grant (id, tenant_id, role_code, capability_key, "
				+ "granted) VALUES (?, ?, 'OPERATOR', 'TEACHER_STOREFRONT_MANAGE', true)", UUID.randomUUID(), tenantId);
		assertOk(call(fx.host(), operator, get(PATH)));
		assertOk(upsert(fx.host(), operator, fx.alice().getId(), "alice", false));

		// Tenant Admin: the list shows every teacher.
		JsonNode list = json(call(fx.host(), fx.adminToken(), get(PATH))).path("data");
		assertThat(list.path("totalElements").asLong()).isEqualTo(3);

		// Cross-tenant: another tenant admin cannot address this tenant's teacher.
		Fx other = seed("sf-perms-other");
		MvcResult foreignGet = call(other.host(), other.adminToken(), get(PATH + "/{id}", fx.alice().getId()));
		assertThat(status(foreignGet)).isEqualTo(404);
		assertThat(status(upsert(other.host(), other.adminToken(), fx.alice().getId(), "hijack", true)))
			.isEqualTo(404);
		assertThat(status(products(other.host(), other.adminToken(), fx.alice().getId(), List.of()))).isEqualTo(404);
		assertThat(jdbcTemplate.queryForObject("SELECT slug FROM teacher_storefront WHERE teacher_id = ?",
				String.class, fx.alice().getId())).isEqualTo("alice");
	}

	// ------------------------------------------------------------------
	// Checkout on a storefront host.
	// ------------------------------------------------------------------

	@Test
	void outOfScopeCoursesAreRefusedOnAStorefrontAndAllowedOnTheTenantSite() {
		Fx fx = seed("sf-checkout");
		String alice = fx.aliceHost();

		MvcResult quote = quote(alice, fx.studentToken(), cart(List.of(september(fx.bobOnly().id())), List.of()));
		assertThat(status(quote)).isEqualTo(422);
		assertThat(errorCode(quote)).isEqualTo("STOREFRONT_PRODUCT_OUT_OF_SCOPE");
		// Mixed cart: one out-of-scope item poisons the whole cart.
		MvcResult mixed = quote(alice, fx.studentToken(),
				cart(List.of(september(fx.aliceOnly().id()), september(fx.bobOnly().id())), List.of()));
		assertThat(errorCode(mixed)).isEqualTo("STOREFRONT_PRODUCT_OUT_OF_SCOPE");
		// Listed but unpublished.
		assertThat(errorCode(quote(alice, fx.studentToken(), cart(List.of(september(fx.aliceHidden().id())),
				List.of())))).isEqualTo("STOREFRONT_PRODUCT_OUT_OF_SCOPE");
		// Postage for an out-of-scope course.
		assertThat(errorCode(quote(alice, fx.studentToken(), cart(List.of(september(fx.aliceOnly().id())),
				List.of(fx.bobOnly().id()))))).isEqualTo("STOREFRONT_PRODUCT_OUT_OF_SCOPE");
		// Orders and offers too.
		MvcResult order = order(alice, fx.studentToken(), cart(List.of(september(fx.bobOnly().id())), List.of()),
				"x");
		assertThat(status(order)).isEqualTo(422);
		assertThat(errorCode(order)).isEqualTo("STOREFRONT_PRODUCT_OUT_OF_SCOPE");
		MvcResult offers = call(alice, fx.studentToken(),
				get("/api/v1/checkout/offers").param("courseId", fx.bobOnly().id().toString()).param("scope", "MONTHLY"));
		assertThat(status(offers)).isEqualTo(422);
		assertThat(errorCode(offers)).isEqualTo("STOREFRONT_PRODUCT_OUT_OF_SCOPE");
		assertOk(call(alice, fx.studentToken(), get("/api/v1/checkout/offers")
			.param("courseId", fx.aliceOnly().id().toString()).param("scope", "MONTHLY")));

		// The course list on a storefront only offers its products.
		List<String> offered = new ArrayList<>();
		json(call(alice, fx.studentToken(), get("/api/v1/checkout/courses").param("scope", "MONTHLY"))).path("data")
			.forEach(c -> offered.add(c.path("courseId").asString()));
		assertThat(offered).contains(fx.aliceOnly().id().toString(), fx.sharedBoth().id().toString())
			.doesNotContain(fx.bobOnly().id().toString(), fx.aliceHidden().id().toString());
		List<String> tenantOffered = new ArrayList<>();
		json(call(fx.host(), fx.studentToken(), get("/api/v1/checkout/courses").param("scope", "MONTHLY")))
			.path("data")
			.forEach(c -> tenantOffered.add(c.path("courseId").asString()));
		assertThat(tenantOffered).contains(fx.bobOnly().id().toString(), fx.aliceHidden().id().toString());

		// The tenant site still sells everything.
		assertOk(quote(fx.host(), fx.studentToken(), cart(List.of(september(fx.bobOnly().id())), List.of())));
		assertThat(count("SELECT count(*) FROM student_order WHERE tenant_id = ?", fx.tenant().getId())).isZero();
	}

	@Test
	void theStudentPortalCatalogAndFreeEnrollmentAreScopedOnAStorefront() {
		Fx fx = seed("sf-portal");
		seedActiveStudent(fx.tenant().getId(), "newcomer@example.test");
		String newcomer = loginAndGetToken(fx.host(), "newcomer@example.test");

		// Browse (AVAILABLE): only Alice's published products on her storefront.
		assertThat(catalogIds(fx.aliceHost(), newcomer, "AVAILABLE")).containsExactlyInAnyOrder(
				fx.aliceOnly().id().toString(), fx.sharedAliceListed().id().toString(),
				fx.sharedBoth().id().toString());
		// The tenant site still offers every course.
		assertThat(catalogIds(fx.host(), newcomer, "AVAILABLE")).contains(fx.bobOnly().id().toString(),
				fx.aliceHidden().id().toString());

		// Free enrollment: out of scope refused, in scope allowed, tenant site unchanged.
		MvcResult refused = call(fx.aliceHost(), newcomer,
				post("/api/v1/enrollments/my/courses/{courseId}", fx.bobOnly().id()));
		assertThat(status(refused)).isEqualTo(422);
		assertThat(errorCode(refused)).isEqualTo("STOREFRONT_PRODUCT_OUT_OF_SCOPE");
		assertOk(call(fx.aliceHost(), newcomer, post("/api/v1/enrollments/my/courses/{courseId}",
				fx.aliceOnly().id())));
		assertOk(call(fx.host(), newcomer, post("/api/v1/enrollments/my/courses/{courseId}", fx.bobOnly().id())));

		// A student keeps everything already joined, on any host (idempotent re-enroll too).
		assertThat(catalogIds(fx.aliceHost(), fx.studentToken(), "ENROLLED")).contains(
				fx.bobOnly().id().toString(), fx.aliceHidden().id().toString());
		assertOk(call(fx.aliceHost(), fx.studentToken(),
				post("/api/v1/enrollments/my/courses/{courseId}", fx.bobOnly().id())));
	}

	private List<String> catalogIds(String host, String token, String view) {
		List<String> ids = new ArrayList<>();
		json(call(host, token, get("/api/v1/enrollments/my/catalog").param("view", view).param("size", "100")))
			.path("data")
			.path("content")
			.forEach(c -> ids.add(c.path("courseId").asString()));
		return ids;
	}

	@Test
	void orderLinesRecordTheStorefrontAndPurchasedAccessSurvivesDisablingIt() {
		Fx fx = seed("sf-attribution");
		UUID storefrontId = jdbcTemplate.queryForObject("SELECT id FROM teacher_storefront WHERE teacher_id = ?",
				UUID.class, fx.alice().getId());

		UUID storefrontOrder = placeOrder(fx.aliceHost(), fx.studentToken(),
				cart(List.of(september(fx.aliceOnly().id())), List.of()));
		assertThat(jdbcTemplate.queryForList("SELECT storefront_id FROM student_order_line WHERE order_id = ?",
				UUID.class, storefrontOrder)).containsOnly(storefrontId);
		UUID tenantOrder = placeOrder(fx.host(), fx.studentToken(),
				cart(List.of(september(fx.bobOnly().id())), List.of()));
		assertThat(jdbcTemplate.queryForList("SELECT storefront_id FROM student_order_line WHERE order_id = ?",
				UUID.class, tenantOrder)).containsOnlyNulls();

		// Pay the storefront order (verified backend confirmation); access follows.
		PaymentInitiationResponse payment = initiatePaymentOrFail(fx.aliceHost(), fx.studentToken(), storefrontOrder);
		assertThat(sendPaymentWebhook(payment.gatewayReference(), true).getStatusCode().value()).isEqualTo(200);
		assertThat(count("SELECT count(*) FROM content_entitlement WHERE order_id = ?", storefrontOrder)).isEqualTo(1);
		assertThat(launch(fx.host(), fx, fx.aliceOnly())).isEqualTo(200);

		// Disable the storefront and unlist everything: nothing about the purchase changes.
		assertOk(upsert(fx.host(), fx.adminToken(), fx.alice().getId(), "alice", false));
		assertOk(products(fx.host(), fx.adminToken(), fx.alice().getId(), List.of()));
		assertThat(count("SELECT count(*) FROM content_entitlement WHERE order_id = ?", storefrontOrder)).isEqualTo(1);
		assertThat(launch(fx.host(), fx, fx.aliceOnly())).isEqualTo(200);
		assertThat(jdbcTemplate.queryForList("SELECT storefront_id FROM student_order_line WHERE order_id = ?",
				UUID.class, storefrontOrder)).containsOnly(storefrontId);
		// The storefront host itself is gone.
		assertThat(errorCode(anonymousGet(fx.aliceHost(), "/api/v1/public/courses"))).isEqualTo("STOREFRONT_NOT_FOUND");
	}

	private int launch(String host, Fx fx, Course course) {
		return status(call(host, fx.studentToken(),
				post("/api/v1/courses/{courseId}/lessons/{lessonId}/launch", course.id(), course.lessonId())));
	}

}
