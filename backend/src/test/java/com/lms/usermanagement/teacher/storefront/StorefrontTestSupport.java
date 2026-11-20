package com.lms.usermanagement.teacher.storefront;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.lms.coursemanagement.course.domain.CoursePricingModel;
import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.web.dto.CourseCreateRequest;
import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.paymentmanagement.PaymentManagementTestSupport;
import com.lms.tenantmanagement.domain.Tenant;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * STORE-01 / ADR-037 fixture: one tenant, two teachers (Alice, Bob) selling
 * the same subject. Courses: {@code aliceOnly} (Alice), {@code bobOnly}
 * (Bob), {@code sharedAliceListed} (Alice primary, Bob co-teacher - listed on
 * Alice's storefront only), {@code sharedBoth} (Bob primary, Alice co-teacher -
 * listed on both), {@code aliceHidden} (Alice, listed but unpublished). Every
 * course is PUBLIC and MONTHLY (September 2026 lesson). The student is
 * enrolled (free, ADR-028) in every course.
 */
abstract class StorefrontTestSupport extends PaymentManagementTestSupport {

	protected static final String PATH = "/api/v1/teacher-storefronts";

	protected record Fx(Tenant tenant, String host, String aliceHost, String bobHost, String adminToken,
			String studentToken, String aliceToken, TenantUser alice, TenantUser bob, TenantUser student,
			Course aliceOnly, Course bobOnly, Course sharedAliceListed, Course sharedBoth, Course aliceHidden) {

	}

	protected record Course(UUID id, String slug, UUID lessonId) {

	}

	protected Fx seed(String prefix) {
		Tenant tenant = seedActiveTenant(uniqueSubdomain(prefix));
		UUID tenantId = tenant.getId();
		seedTenantUser(tenantId, "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser alice = seedTenantUser(tenantId, "alice@example.test", RAW_PASSWORD, Role.TEACHER);
		TenantUser bob = seedTenantUser(tenantId, "bob@example.test", RAW_PASSWORD, Role.TEACHER);
		seedTeacherProfile(tenantId, alice.getId(), "Alice Perera");
		seedTeacherProfile(tenantId, bob.getId(), "Bob Silva");
		TenantUser student = seedActiveStudent(tenantId, "student@example.test");
		jdbcTemplate.update("INSERT INTO student_profile (id, tenant_id, user_id, name, address_line, city, mobile, "
				+ "created_at, updated_at) VALUES (?, ?, ?, 'Nimal Silva', '12 Temple Road', 'Kandy', '0771234567', "
				+ "now(), now())", UUID.randomUUID(), tenantId, student.getId());
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String aliceToken = loginAndGetToken(host, "alice@example.test");
		String studentToken = loginAndGetToken(host, "student@example.test");

		Course aliceOnly = monthlyCourse(host, adminToken, alice.getId(), "physics-alice");
		Course bobOnly = monthlyCourse(host, adminToken, bob.getId(), "physics-bob");
		Course sharedAliceListed = monthlyCourse(host, adminToken, alice.getId(), "physics-shared-a");
		coTeach(tenantId, sharedAliceListed.id(), bob.getId());
		Course sharedBoth = monthlyCourse(host, adminToken, bob.getId(), "physics-shared-b");
		coTeach(tenantId, sharedBoth.id(), alice.getId());
		Course aliceHidden = monthlyCourse(host, adminToken, alice.getId(), "physics-hidden");
		for (Course course : List.of(aliceOnly, bobOnly, sharedAliceListed, sharedBoth, aliceHidden)) {
			assertThat(status(call(host, studentToken, post("/api/v1/enrollments/my/courses/{courseId}",
					course.id())))).isEqualTo(200);
		}

		String aliceHost = "alice." + tenant.getSubdomain() + ".lms.test";
		String bobHost = "bob." + tenant.getSubdomain() + ".lms.test";
		Fx fx = new Fx(tenant, host, aliceHost, bobHost, adminToken, studentToken, aliceToken, alice, bob, student,
				aliceOnly, bobOnly, sharedAliceListed, sharedBoth, aliceHidden);
		assertOk(upsert(host, adminToken, alice.getId(), "alice", true));
		assertOk(upsert(host, adminToken, bob.getId(), "bob", true));
		assertOk(products(host, adminToken, alice.getId(),
				List.of(item(aliceOnly.id(), true, 0), item(sharedAliceListed.id(), true, 1),
						item(sharedBoth.id(), true, 2), item(aliceHidden.id(), false, 3))));
		assertOk(products(host, adminToken, bob.getId(),
				List.of(item(bobOnly.id(), true, 0), item(sharedBoth.id(), true, 1))));
		return fx;
	}

	protected void seedTeacherProfile(UUID tenantId, UUID userId, String name) {
		jdbcTemplate.update("INSERT INTO teacher_profile (id, tenant_id, user_id, name, description, approval_status, "
				+ "created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'APPROVED', now(), now())", UUID.randomUUID(),
				tenantId, userId, name, name + " teaches physics.");
	}

	protected void coTeach(UUID tenantId, UUID courseId, UUID teacherId) {
		jdbcTemplate.update("INSERT INTO course_teacher_assignment (id, tenant_id, course_id, teacher_id, is_primary, "
				+ "created_at) VALUES (?, ?, ?, ?, false, now())", UUID.randomUUID(), tenantId, courseId, teacherId);
	}

	protected Course monthlyCourse(String host, String adminToken, UUID teacherId, String prefix) {
		String slug = uniqueSlug(prefix);
		CourseResponse course = createCourseOrFail(host, adminToken,
				new CourseCreateRequest("Physics " + slug, slug, "Physics", null, null, null, null, null,
						new BigDecimal("3500.00"), null, null, CourseStatus.PUBLIC, teacherId, null,
						CoursePricingModel.MONTHLY, null, null, null, null, null, null, null));
		UUID moduleId = createModuleOrFail(host, adminToken, course.id(), "Module", 1).id();
		UUID lessonId = createLessonOrFail(host, adminToken, course.id(), moduleId, "September", 1).id();
		jdbcTemplate.update("UPDATE course_lesson SET payment_type = 'COVERED_BY_CLASS_FEES', billing_year = 2026, "
				+ "billing_month = 9 WHERE id = ?", lessonId);
		return new Course(course.id(), slug, lessonId);
	}

	// ------------------------------------------------------------------
	// Storefront admin endpoints.
	// ------------------------------------------------------------------

	protected MvcResult upsert(String host, String token, UUID teacherId, String slug, Boolean enabled) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("slug", slug);
		body.put("enabled", enabled);
		return jsonCall(host, token, put(PATH + "/{teacherId}", teacherId), body);
	}

	protected MvcResult products(String host, String token, UUID teacherId, List<Map<String, Object>> items) {
		return jsonCall(host, token, put(PATH + "/{teacherId}/products", teacherId), Map.of("items", items));
	}

	protected static Map<String, Object> item(UUID courseId, boolean published, int sortOrder) {
		Map<String, Object> item = new LinkedHashMap<>();
		item.put("courseId", courseId);
		item.put("published", published);
		item.put("sortOrder", sortOrder);
		return item;
	}

	// ------------------------------------------------------------------
	// Checkout.
	// ------------------------------------------------------------------

	protected static Map<String, Object> september(UUID courseId) {
		Map<String, Object> item = new LinkedHashMap<>();
		item.put("courseId", courseId);
		item.put("scope", "MONTHLY");
		item.put("year", 2026);
		item.put("month", 9);
		return item;
	}

	protected static Map<String, Object> cart(List<Map<String, Object>> items, List<UUID> postage) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("items", items);
		body.put("postageCourseIds", postage);
		return body;
	}

	protected MvcResult quote(String host, String token, Map<String, Object> cart) {
		return jsonCall(host, token, post("/api/v1/checkout/quote"), cart);
	}

	protected MvcResult order(String host, String token, Map<String, Object> cart, String quoteToken) {
		Map<String, Object> body = new LinkedHashMap<>(cart);
		body.put("quoteToken", quoteToken);
		return jsonCall(host, token, post("/api/v1/checkout/orders"), body);
	}

	/** Quote then order on {@code host}; returns the new order id. */
	protected UUID placeOrder(String host, String token, Map<String, Object> cart) {
		MvcResult quote = quote(host, token, cart);
		assertThat(status(quote)).as(rawContent(quote)).isEqualTo(200);
		MvcResult order = order(host, token, cart, json(quote).path("data").path("quoteToken").asString());
		assertThat(status(order)).as(rawContent(order)).isEqualTo(201);
		return UUID.fromString(json(order).path("data").path("id").asString());
	}

	// ------------------------------------------------------------------
	// HTTP plumbing.
	// ------------------------------------------------------------------

	protected MvcResult call(String host, String token, MockHttpServletRequestBuilder builder) {
		return perform(authenticated(builder, host, token));
	}

	protected MvcResult anonymousGet(String host, String path, Object... vars) {
		return perform(get(path, vars).header(HttpHeaders.HOST, host));
	}

	protected MvcResult jsonCall(String host, String token, MockHttpServletRequestBuilder builder, Object body) {
		return call(host, token,
				builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
	}

	protected JsonNode json(MvcResult result) {
		return objectMapper.readTree(rawContent(result));
	}

	protected static int status(MvcResult result) {
		return result.getResponse().getStatus();
	}

	protected String errorCode(MvcResult result) {
		return json(result).path("error").path("code").asString();
	}

	protected void assertOk(MvcResult result) {
		assertThat(status(result)).as(rawContent(result)).isEqualTo(200);
	}

	protected List<String> slugs(MvcResult page) {
		List<String> slugs = new ArrayList<>();
		json(page).path("data").path("content").forEach(node -> slugs.add(node.path("slug").asString()));
		return slugs;
	}

	protected long count(String sql, Object... args) {
		Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
		return value == null ? 0 : value;
	}

}
