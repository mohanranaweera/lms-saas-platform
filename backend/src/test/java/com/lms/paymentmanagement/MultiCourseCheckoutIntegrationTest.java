package com.lms.paymentmanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.lms.coursemanagement.course.domain.CoursePricingModel;
import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.web.dto.CourseCreateRequest;
import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.paymentmanagement.order.web.dto.PaymentInitiationResponse;
import com.lms.paymentmanagement.slip.web.dto.PaymentSlipResponse;
import com.lms.tenantmanagement.domain.Tenant;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * Multi-course checkout, course facility-fee override and per-course postage
 * (docs/plans/multi-course-checkout-fees-postage.md, V90, ADR-028).
 *
 * <p>Fixture: tenant facility fee {@code PER_COURSE} 100; Course A inherits
 * the default, postage 200, September + October at 3500; Course B override 0,
 * postage 0, October at 4000. Covers the acceptance cases A-R: fee/postage
 * math, override null/0/positive, course-admin validation + authorization +
 * audit, postage-course and delivery rules, cross-tenant 404 and tampered
 * quotes, entitlement grants exactly once per line on a verified gateway
 * confirmation or an approved slip (none while pending, none on rejection),
 * immutable order snapshots and currency mismatch.
 */
class MultiCourseCheckoutIntegrationTest extends SlipTestSupport {

	private static final String SLIP_PATH = "/api/v1/payment-slips/{slipId}";

	private record Fx(Tenant tenant, String host, String adminToken, String financeToken, String teacherToken,
			String studentToken, TenantUser student, UUID a, UUID aSep, UUID aOct, UUID b, UUID bOct, UUID bModule,
			TenantUser teacher, TenantUser teacher2) {

	}

	private Fx seed(String prefix, boolean withAddress) {
		Tenant tenant = seedActiveTenant(uniqueSubdomain(prefix));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		seedTenantUser(tenant.getId(), "finance@example.test", RAW_PASSWORD, Role.FINANCE_STAFF);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		TenantUser teacher2 = seedTenantUser(tenant.getId(), "teacher2@example.test", RAW_PASSWORD, Role.TEACHER);
		TenantUser student = seedActiveStudent(tenant.getId(), "student@example.test");
		jdbcTemplate.update("INSERT INTO student_profile (id, tenant_id, user_id, name, created_at, updated_at) "
				+ "VALUES (?, ?, ?, 'Nimal Silva', now(), now())", UUID.randomUUID(), tenant.getId(), student.getId());
		if (withAddress) {
			jdbcTemplate.update("UPDATE student_profile SET address_line = '12 Temple Road', city = 'Kandy', "
					+ "mobile = '0771234567' WHERE user_id = ?", student.getId());
		}
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String financeToken = loginAndGetToken(host, "finance@example.test");
		String teacherToken = loginAndGetToken(host, "teacher@example.test");
		String studentToken = loginAndGetToken(host, "student@example.test");

		UUID a = monthlyCourse(host, adminToken, teacher.getId(), prefix + "-a", "3500.00");
		UUID aModule = createModuleOrFail(host, adminToken, a, "A Module", 1).id();
		UUID aSep = createLessonOrFail(host, adminToken, a, aModule, "A September", 1).id();
		UUID aOct = createLessonOrFail(host, adminToken, a, aModule, "A October", 2).id();
		covered(aSep, 2026, 9);
		covered(aOct, 2026, 10);
		UUID b = monthlyCourse(host, adminToken, teacher2.getId(), prefix + "-b", "4000.00");
		UUID bModule = createModuleOrFail(host, adminToken, b, "B Module", 1).id();
		UUID bOct = createLessonOrFail(host, adminToken, b, bModule, "B October", 1).id();
		covered(bOct, 2026, 10);

		Fx fx = new Fx(tenant, host, adminToken, financeToken, teacherToken, studentToken, student, a, aSep, aOct, b,
				bOct, bModule, teacher, teacher2);
		setFee(fx, "PER_COURSE", 100);
		assertThat(status(patchCourse(fx, fx.adminToken(), a, Map.of("postageFee", 200)))).isEqualTo(200);
		assertThat(status(patchCourse(fx, fx.adminToken(), b,
				Map.of("facilityFeeInherit", false, "facilityFeeOverride", 0, "postageFee", 0)))).isEqualTo(200);
		assertThat(status(call(host, studentToken, post("/api/v1/enrollments/my/courses/{courseId}", a))))
			.isEqualTo(200);
		assertThat(status(call(host, studentToken, post("/api/v1/enrollments/my/courses/{courseId}", b))))
			.isEqualTo(200);
		return fx;
	}

	private UUID monthlyCourse(String host, String adminToken, UUID teacherId, String prefix, String fee) {
		String slug = uniqueSlug(prefix);
		CourseResponse course = createCourseOrFail(host, adminToken,
				new CourseCreateRequest("Course " + slug, slug, "Mathematics", null, null, null, null, null,
						new BigDecimal(fee), null, null, CourseStatus.PUBLIC, teacherId, null,
						CoursePricingModel.MONTHLY, null, null, null, null, null, null, null));
		return course.id();
	}

	private void covered(UUID lessonId, int year, int month) {
		jdbcTemplate.update("UPDATE course_lesson SET payment_type = 'COVERED_BY_CLASS_FEES', billing_year = ?, "
				+ "billing_month = ? WHERE id = ?", year, month, lessonId);
	}

	// ------------------------------------------------------------------
	// HTTP helpers.
	// ------------------------------------------------------------------

	private MvcResult call(String host, String token, MockHttpServletRequestBuilder builder) {
		return perform(authenticated(builder, host, token));
	}

	private MvcResult jsonCall(String host, String token, MockHttpServletRequestBuilder builder, Object body) {
		return call(host, token, builder.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(body)));
	}

	private MvcResult patchCourse(Fx fx, String token, UUID courseId, Map<String, Object> body) {
		return jsonCall(fx.host(), token, patch("/api/v1/courses/{id}", courseId), body);
	}

	private void setFee(Fx fx, String mode, Object amount) {
		Map<String, Object> changes = new LinkedHashMap<>();
		changes.put("facility_fee_mode", mode);
		changes.put("facility_fee_amount", amount);
		MvcResult result = jsonCall(fx.host(), fx.adminToken(), put("/api/v1/tenant-config/PAYMENT"), changes);
		assertThat(status(result)).as(rawContent(result)).isEqualTo(200);
	}

	private static Map<String, Object> month(UUID courseId, int year, int month) {
		Map<String, Object> item = new LinkedHashMap<>();
		item.put("courseId", courseId);
		item.put("scope", "MONTHLY");
		item.put("year", year);
		item.put("month", month);
		return item;
	}

	private static Map<String, Object> cart(List<Map<String, Object>> items, List<UUID> postage) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("items", items);
		body.put("postageCourseIds", postage);
		return body;
	}

	private MvcResult quote(Fx fx, Map<String, Object> body) {
		return quote(fx.host(), fx.studentToken(), body);
	}

	private MvcResult quote(String host, String token, Map<String, Object> body) {
		return jsonCall(host, token, post("/api/v1/checkout/quote"), body);
	}

	private JsonNode quoteOk(Fx fx, Map<String, Object> body) {
		MvcResult result = quote(fx, body);
		assertThat(status(result)).as(rawContent(result)).isEqualTo(200);
		return json(result).path("data");
	}

	private MvcResult order(Fx fx, Map<String, Object> body, String quoteToken, UUID idempotencyKey) {
		Map<String, Object> request = new LinkedHashMap<>(body);
		if (quoteToken != null) {
			request.put("quoteToken", quoteToken);
		}
		if (idempotencyKey != null) {
			request.put("idempotencyKey", idempotencyKey);
		}
		return jsonCall(fx.host(), fx.studentToken(), post("/api/v1/checkout/orders"), request);
	}

	/** Quote, then order with that quote's token. */
	private JsonNode placeOrder(Fx fx, Map<String, Object> body, UUID idempotencyKey) {
		String token = quoteOk(fx, body).path("quoteToken").asString();
		MvcResult result = order(fx, body, token, idempotencyKey);
		assertThat(status(result)).as(rawContent(result)).isEqualTo(201);
		return json(result).path("data");
	}

	private Map<String, Object> fixtureCart(Fx fx, boolean postageA) {
		return cart(List.of(month(fx.a(), 2026, 9), month(fx.a(), 2026, 10), month(fx.b(), 2026, 10)),
				postageA ? List.of(fx.a()) : List.of());
	}

	private JsonNode json(MvcResult result) {
		return objectMapper.readTree(rawContent(result));
	}

	private static int status(MvcResult result) {
		return result.getResponse().getStatus();
	}

	private String errorCode(MvcResult result) {
		return json(result).path("error").path("code").asString();
	}

	private long count(String sql, Object... args) {
		Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
		return value == null ? 0 : value;
	}

	private static JsonNode course(JsonNode quote, UUID courseId) {
		for (JsonNode course : quote.path("courses")) {
			if (course.path("courseId").asString().equals(courseId.toString())) {
				return course;
			}
		}
		return null;
	}

	private long entitlements(UUID tenantId) {
		return count("SELECT count(*) FROM content_entitlement WHERE tenant_id = ?", tenantId);
	}

	private long launch(Fx fx, UUID courseId, UUID lessonId) {
		return status(call(fx.host(), fx.studentToken(),
				post("/api/v1/courses/{courseId}/lessons/{lessonId}/launch", courseId, lessonId)));
	}

	// ------------------------------------------------------------------
	// A, C, D, E, I, J, Q: quote math.
	// ------------------------------------------------------------------

	@Test
	void multiCourseQuoteChargesFacilityFeeOncePerCourseAndPostageOnlyForSelectedCourses() {
		Fx fx = seed("mcc-quote", true);

		// A: A postage selected -> 11000 + 100 + 200 = 11300.
		JsonNode q = quoteOk(fx, fixtureCart(fx, true));
		assertThat(q.path("courseId").isNull()).isTrue();
		assertThat(q.path("feeBasis").asString()).isEqualTo("PER_COURSE");
		assertThat(q.path("subtotal").decimalValue()).isEqualByComparingTo("11000.00");
		assertThat(q.path("facilityFeeTotal").decimalValue()).isEqualByComparingTo("100.00");
		assertThat(q.path("postageTotal").decimalValue()).isEqualByComparingTo("200.00");
		assertThat(q.path("discountTotal").decimalValue()).isEqualByComparingTo("0");
		assertThat(q.path("total").decimalValue()).isEqualByComparingTo("11300.00");
		assertThat(q.path("quoteToken").asString()).hasSize(64);
		assertThat(q.path("lines")).hasSize(3);
		// C: two months of A do not double A's fee; the fee sits on A's first line.
		assertThat(q.path("lines").get(0).path("courseId").asString()).isEqualTo(fx.a().toString());
		assertThat(q.path("lines").get(0).path("facilityFee").decimalValue()).isEqualByComparingTo("100.00");
		assertThat(q.path("lines").get(1).path("facilityFee").decimalValue()).isEqualByComparingTo("0");
		assertThat(q.path("lines").get(2).path("courseId").asString()).isEqualTo(fx.b().toString());
		assertThat(q.path("lines").get(2).path("courseTitle").asString()).startsWith("Course ");
		assertThat(q.path("lines").get(2).path("facilityFee").decimalValue()).isEqualByComparingTo("0");
		// D: A inherits the tenant default.
		JsonNode a = course(q, fx.a());
		assertThat(a.path("subtotal").decimalValue()).isEqualByComparingTo("7000.00");
		assertThat(a.path("facilityFee").decimalValue()).isEqualByComparingTo("100.00");
		assertThat(a.path("effectiveFacilityFee").decimalValue()).isEqualByComparingTo("100.00");
		assertThat(a.path("facilityFeeSource").asString()).isEqualTo("TENANT_DEFAULT");
		assertThat(a.path("postageFee").decimalValue()).isEqualByComparingTo("200.00");
		assertThat(a.path("postageSelected").asBoolean()).isTrue();
		assertThat(a.path("postageCharge").decimalValue()).isEqualByComparingTo("200.00");
		// E: B's explicit 0 override is honoured (never treated as "missing").
		JsonNode b = course(q, fx.b());
		assertThat(b.path("facilityFee").decimalValue()).isEqualByComparingTo("0");
		assertThat(b.path("effectiveFacilityFee").decimalValue()).isEqualByComparingTo("0");
		assertThat(b.path("facilityFeeSource").asString()).isEqualTo("COURSE_OVERRIDE");
		assertThat(b.path("postageSelected").asBoolean()).isFalse();
		// Delivery from the on-record billing address.
		assertThat(q.path("deliveryRequired").asBoolean()).isTrue();
		assertThat(q.path("delivery").path("addressLine").asString()).isEqualTo("12 Temple Road");
		assertThat(q.path("delivery").path("city").asString()).isEqualTo("Kandy");
		assertThat(q.path("delivery").path("phone").asString()).isEqualTo("0771234567");
		assertThat(q.path("delivery").path("recipientName").asString()).isEqualTo("Nimal Silva");

		// A without postage -> 11100; delivery not required.
		JsonNode noPostage = quoteOk(fx, fixtureCart(fx, false));
		assertThat(noPostage.path("total").decimalValue()).isEqualByComparingTo("11100.00");
		assertThat(noPostage.path("postageTotal").decimalValue()).isEqualByComparingTo("0");
		assertThat(noPostage.path("deliveryRequired").asBoolean()).isFalse();
		assertThat(noPostage.path("quoteToken").asString()).isNotEqualTo(q.path("quoteToken").asString());

		// I: B's postage selected at 0 -> selected, no charge, nothing fabricated.
		JsonNode zeroPostage = quoteOk(fx, cart(List.of(month(fx.b(), 2026, 10)), List.of(fx.b())));
		assertThat(course(zeroPostage, fx.b()).path("postageSelected").asBoolean()).isTrue();
		assertThat(course(zeroPostage, fx.b()).path("postageCharge").decimalValue()).isEqualByComparingTo("0");
		assertThat(zeroPostage.path("postageTotal").decimalValue()).isEqualByComparingTo("0");
		assertThat(zeroPostage.path("total").decimalValue()).isEqualByComparingTo("4000.00");
		assertThat(zeroPostage.path("courseId").asString()).isEqualTo(fx.b().toString());

		// J: removing every A item drops A's fee and postage.
		JsonNode onlyB = quoteOk(fx, cart(List.of(month(fx.b(), 2026, 10)), List.of()));
		assertThat(onlyB.path("courses")).hasSize(1);
		assertThat(onlyB.path("facilityFeeTotal").decimalValue()).isEqualByComparingTo("0");
		assertThat(onlyB.path("total").decimalValue()).isEqualByComparingTo("4000.00");

		// Top-level courseId stays a default for items without their own courseId.
		Map<String, Object> legacyShape = new LinkedHashMap<>();
		legacyShape.put("courseId", fx.a());
		legacyShape.put("items", List.of(Map.of("scope", "MONTHLY", "year", 2026, "month", 9)));
		JsonNode legacy = quoteOk(fx, legacyShape);
		assertThat(legacy.path("total").decimalValue()).isEqualByComparingTo("3600.00");
		assertThat(legacy.path("courseId").asString()).isEqualTo(fx.a().toString());

		// Quotes never persist or grant anything.
		assertThat(count("SELECT count(*) FROM student_order WHERE tenant_id = ?", fx.tenant().getId())).isZero();
		assertThat(entitlements(fx.tenant().getId())).isZero();
	}

	@Test
	void perLineAndPerOrderModesAndAPositiveOverride() {
		Fx fx = seed("mcc-modes", false);
		// F: a positive override replaces the tenant default for that course.
		assertThat(status(patchCourse(fx, fx.adminToken(), fx.a(), Map.of("facilityFeeOverride", 250))))
			.isEqualTo(200);
		JsonNode perCourse = quoteOk(fx, fixtureCart(fx, false));
		assertThat(course(perCourse, fx.a()).path("facilityFee").decimalValue()).isEqualByComparingTo("250.00");
		assertThat(course(perCourse, fx.a()).path("facilityFeeSource").asString()).isEqualTo("COURSE_OVERRIDE");
		assertThat(perCourse.path("total").decimalValue()).isEqualByComparingTo("11250.00");

		setFee(fx, "PER_LINE", 100);
		JsonNode perLine = quoteOk(fx, fixtureCart(fx, false));
		// A: 2 lines x 250; B: override 0.
		assertThat(perLine.path("facilityFeeTotal").decimalValue()).isEqualByComparingTo("500.00");

		setFee(fx, "PER_ORDER", 100);
		JsonNode perOrder = quoteOk(fx, fixtureCart(fx, false));
		// Tenant default once on line 1; overrides do not apply.
		assertThat(perOrder.path("facilityFeeTotal").decimalValue()).isEqualByComparingTo("100.00");
		assertThat(perOrder.path("lines").get(0).path("facilityFee").decimalValue()).isEqualByComparingTo("100.00");
		assertThat(course(perOrder, fx.a()).path("facilityFeeSource").asString()).isEqualTo("NOT_APPLICABLE");

		setFee(fx, "NONE", 100);
		JsonNode none = quoteOk(fx, fixtureCart(fx, false));
		assertThat(none.path("facilityFeeTotal").decimalValue()).isEqualByComparingTo("0");
		assertThat(none.path("feeNote").isNull()).isTrue();
		assertThat(course(none, fx.a()).path("effectiveFacilityFee").decimalValue()).isEqualByComparingTo("0");
	}

	// ------------------------------------------------------------------
	// G: course admin fields, validation, authorization, audit.
	// ------------------------------------------------------------------

	@Test
	void courseAdminFacilityFeeAndPostageAreValidatedAuthorizedAndAudited() {
		Fx fx = seed("mcc-admin", false);
		JsonNode a = json(call(fx.host(), fx.adminToken(), get("/api/v1/courses/{id}", fx.a()))).path("data");
		assertThat(a.path("facilityFeeOverride").isNull()).isTrue();
		assertThat(a.path("tenantDefaultFacilityFee").decimalValue()).isEqualByComparingTo("100.00");
		assertThat(a.path("facilityFeeBasis").asString()).isEqualTo("PER_COURSE");
		assertThat(a.path("effectiveFacilityFee").decimalValue()).isEqualByComparingTo("100.00");
		assertThat(a.path("postageFee").decimalValue()).isEqualByComparingTo("200.00");
		JsonNode b = json(call(fx.host(), fx.adminToken(), get("/api/v1/courses/{id}", fx.b()))).path("data");
		assertThat(b.path("facilityFeeOverride").decimalValue()).isEqualByComparingTo("0");
		assertThat(b.path("effectiveFacilityFee").decimalValue()).isEqualByComparingTo("0");

		// Negative / too-precise values and an override-less "don't inherit" are rejected.
		assertThat(status(patchCourse(fx, fx.adminToken(), fx.a(), Map.of("facilityFeeOverride", -1))))
			.isEqualTo(400);
		assertThat(status(patchCourse(fx, fx.adminToken(), fx.a(), Map.of("postageFee", -5)))).isEqualTo(400);
		assertThat(status(patchCourse(fx, fx.adminToken(), fx.a(), Map.of("postageFee", 1.234)))).isEqualTo(400);
		// Same cap as the tenant facility fee: below 1,000,000.
		assertThat(status(patchCourse(fx, fx.adminToken(), fx.a(), Map.of("postageFee", 1000000)))).isEqualTo(400);
		assertThat(status(patchCourse(fx, fx.adminToken(), fx.a(), Map.of("facilityFeeOverride", 1000000))))
			.isEqualTo(400);
		assertThat(status(patchCourse(fx, fx.adminToken(), fx.a(), Map.of("facilityFeeInherit", false))))
			.isEqualTo(400);
		// A student cannot change course charges; another tenant's admin cannot see the course.
		assertThat(status(patchCourse(fx, fx.studentToken(), fx.a(), Map.of("postageFee", 1)))).isEqualTo(403);
		Fx other = seed("mcc-admin-x", false);
		assertThat(status(patchCourse(other, other.adminToken(), fx.a(), Map.of("postageFee", 1)))).isEqualTo(404);
		assertThat(jdbcTemplate.queryForObject("SELECT postage_fee FROM course WHERE id = ?", BigDecimal.class,
				fx.a())).isEqualByComparingTo("200.00");

		// Inherit again (override -> null) and audit with before/after.
		assertThat(status(patchCourse(fx, fx.adminToken(), fx.b(), Map.of("facilityFeeInherit", true))))
			.isEqualTo(200);
		assertThat(jdbcTemplate.queryForObject("SELECT facility_fee_override FROM course WHERE id = ?",
				BigDecimal.class, fx.b())).isNull();
		assertThat(count("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'course.charges_changed' "
				+ "AND target_id = ? AND metadata -> 'postageFee' ->> 'to' = '200.00'", fx.tenant().getId(), fx.a()))
			.isEqualTo(1);
		assertThat(count("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'course.charges_changed' "
				+ "AND target_id = ? AND metadata -> 'facilityFeeOverride' ->> 'from' = '0.00' "
				+ "AND metadata -> 'facilityFeeOverride' -> 'to' = 'null'::jsonb", fx.tenant().getId(), fx.b()))
			.isEqualTo(1);
		// A no-op edit writes no audit row.
		long before = count("SELECT count(*) FROM audit_log WHERE action = 'course.charges_changed' AND target_id = ?",
				fx.a());
		assertThat(status(patchCourse(fx, fx.adminToken(), fx.a(), Map.of("postageFee", 200)))).isEqualTo(200);
		assertThat(count("SELECT count(*) FROM audit_log WHERE action = 'course.charges_changed' AND target_id = ?",
				fx.a())).isEqualTo(before);
	}

	// ------------------------------------------------------------------
	// H, K, P, Q: rejections.
	// ------------------------------------------------------------------

	@Test
	void postageForACourseNotInTheCartAndPostageWithoutAddressAreRejected() {
		Fx fx = seed("mcc-postage", false);
		MvcResult notInCart = quote(fx, cart(List.of(month(fx.b(), 2026, 10)), List.of(fx.a())));
		assertThat(status(notInCart)).isEqualTo(422);
		assertThat(errorCode(notInCart)).isEqualTo("POSTAGE_COURSE_NOT_IN_CART");

		// Q: no address on record - fine without postage, 422 with postage.
		JsonNode withoutPostage = quoteOk(fx, fixtureCart(fx, false));
		assertThat(withoutPostage.path("deliveryRequired").asBoolean()).isFalse();
		assertThat(withoutPostage.path("delivery").path("addressLine").isNull()).isTrue();
		MvcResult withPostage = quote(fx, fixtureCart(fx, true));
		assertThat(status(withPostage)).isEqualTo(422);
		assertThat(errorCode(withPostage)).isEqualTo("DELIVERY_DETAILS_REQUIRED");
		MvcResult orderWithPostage = order(fx, fixtureCart(fx, true), "0".repeat(64), null);
		assertThat(errorCode(orderWithPostage)).isEqualTo("DELIVERY_DETAILS_REQUIRED");

		// A line order without postage stores no delivery snapshot.
		JsonNode order = placeOrder(fx, fixtureCart(fx, false), null);
		assertThat(order.path("delivery").isNull()).isTrue();
		assertThat(jdbcTemplate.queryForObject("SELECT delivery_address_line FROM student_order WHERE id = ?",
				String.class, UUID.fromString(order.path("id").asString()))).isNull();
	}

	@Test
	void crossTenantCoursesAre404ClientPricesIgnoredAndTamperedQuotesAre409() {
		Fx fx = seed("mcc-tamper", true);
		Fx other = seed("mcc-tamper-x", true);

		// Another tenant's course id among the items -> 404, nothing created.
		MvcResult foreign = quote(fx, cart(List.of(month(fx.a(), 2026, 9), month(other.a(), 2026, 9)), List.of()));
		assertThat(status(foreign)).isEqualTo(404);
		assertThat(status(order(fx, cart(List.of(month(other.a(), 2026, 9)), List.of()), "x", null))).isEqualTo(404);
		Map<String, Object> foreignDefault = cart(List.of(month(fx.a(), 2026, 9)), List.of());
		foreignDefault.put("courseId", other.b());
		assertThat(status(quote(fx, foreignDefault))).isEqualTo(404);

		// Client-sent prices / totals / tenant ids are ignored.
		Map<String, Object> tampered = fixtureCart(fx, true);
		List<Map<String, Object>> items = new ArrayList<>();
		for (Object item : (List<?>) tampered.get("items")) {
			Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) item);
			copy.put("amount", 1);
			copy.put("facilityFee", 0);
			items.add(copy);
		}
		tampered.put("items", items);
		tampered.put("total", 1);
		tampered.put("postageTotal", 0);
		tampered.put("tenantId", other.tenant().getId());
		JsonNode q = quoteOk(fx, tampered);
		assertThat(q.path("total").decimalValue()).isEqualByComparingTo("11300.00");

		// Missing token -> 422; a tampered token -> 409 with the fresh quote; nothing persisted.
		MvcResult missing = order(fx, tampered, null, null);
		assertThat(status(missing)).isEqualTo(422);
		assertThat(errorCode(missing)).isEqualTo("QUOTE_REQUIRED");
		MvcResult changed = order(fx, tampered, "f".repeat(64), null);
		assertThat(status(changed)).as(rawContent(changed)).isEqualTo(409);
		assertThat(errorCode(changed)).isEqualTo("QUOTE_CHANGED");
		JsonNode fresh = json(changed).path("error").path("details").path("quote");
		assertThat(fresh.path("total").decimalValue()).isEqualByComparingTo("11300.00");
		assertThat(fresh.path("quoteToken").asString()).isEqualTo(q.path("quoteToken").asString());
		assertThat(count("SELECT count(*) FROM student_order WHERE tenant_id = ?", fx.tenant().getId())).isZero();

		// A config change between quote and order -> 409 with the new total.
		setFee(fx, "PER_COURSE", 150);
		MvcResult stale = order(fx, tampered, q.path("quoteToken").asString(), null);
		assertThat(status(stale)).isEqualTo(409);
		assertThat(json(stale).path("error").path("details").path("quote").path("total").decimalValue())
			.isEqualByComparingTo("11350.00");
		assertThat(count("SELECT count(*) FROM student_order WHERE tenant_id = ?", fx.tenant().getId())).isZero();
	}

	@Test
	void coursesPricedInDifferentCurrenciesCannotShareAnOrder() {
		Fx fx = seed("mcc-currency", false);
		jdbcTemplate.update("UPDATE course_billing_period SET currency = 'XTS' WHERE billing_configuration_id IN "
				+ "(SELECT id FROM course_billing_configuration WHERE course_id = ?)", fx.b());
		MvcResult mixed = quote(fx, fixtureCart(fx, false));
		assertThat(status(mixed)).isEqualTo(422);
		assertThat(errorCode(mixed)).isEqualTo("CURRENCY_MISMATCH");
		String message = json(mixed).path("error").path("message").asString();
		assertThat(message).contains("XTS").contains("Course ");
		assertThat(status(order(fx, fixtureCart(fx, false), "x", null))).isEqualTo(422);
		assertThat(count("SELECT count(*) FROM student_order WHERE tenant_id = ?", fx.tenant().getId())).isZero();
	}

	// ------------------------------------------------------------------
	// L, R, O: order snapshot, gateway confirmation, replay.
	// ------------------------------------------------------------------

	@Test
	void multiCourseOrderSnapshotsChargesAndGatewayConfirmationGrantsEachLineOnce() {
		Fx fx = seed("mcc-gateway", true);
		UUID tenantId = fx.tenant().getId();
		UUID key = UUID.randomUUID();
		JsonNode order = placeOrder(fx, fixtureCart(fx, true), key);
		UUID orderId = UUID.fromString(order.path("id").asString());
		assertThat(order.path("courseId").isNull()).isTrue();
		assertThat(order.path("amount").decimalValue()).isEqualByComparingTo("11300.00");
		assertThat(order.path("postageTotal").decimalValue()).isEqualByComparingTo("200.00");
		assertThat(order.path("feeBasis").asString()).isEqualTo("PER_COURSE");
		assertThat(order.path("lines")).hasSize(3);
		assertThat(order.path("courses")).hasSize(2);
		assertThat(course(order, fx.a()).path("postageCharge").decimalValue()).isEqualByComparingTo("200.00");
		assertThat(course(order, fx.b()).path("facilityFeeSource").asString()).isEqualTo("COURSE_OVERRIDE");
		assertThat(order.path("delivery").path("city").asString()).isEqualTo("Kandy");

		assertThat(jdbcTemplate.queryForObject("SELECT course_id FROM student_order WHERE id = ?", UUID.class,
				orderId)).isNull();
		assertThat(jdbcTemplate.queryForObject("SELECT postage_amount FROM student_order WHERE id = ?",
				BigDecimal.class, orderId)).isEqualByComparingTo("200.00");
		assertThat(jdbcTemplate.queryForObject("SELECT delivery_address_line FROM student_order WHERE id = ?",
				String.class, orderId)).isEqualTo("12 Temple Road");
		assertThat(count("SELECT count(*) FROM student_order_course_charge WHERE order_id = ?", orderId))
			.isEqualTo(2);
		assertThat(count("SELECT count(*) FROM student_order_course_charge WHERE order_id = ? AND course_id = ? "
				+ "AND fee_basis = 'PER_COURSE' AND facility_fee_source = 'TENANT_DEFAULT' AND facility_fee_amount = 100 "
				+ "AND postage_selected AND postage_amount = 200", orderId, fx.a())).isEqualTo(1);
		// An order grants nothing.
		assertThat(entitlements(tenantId)).isZero();

		// O: later config changes never alter the order snapshot; a replay returns it unchanged.
		setFee(fx, "PER_COURSE", 500);
		assertThat(status(patchCourse(fx, fx.adminToken(), fx.a(),
				Map.of("facilityFeeOverride", 50, "postageFee", 999)))).isEqualTo(200);
		MvcResult replay = order(fx, fixtureCart(fx, true), "stale-token-ignored-on-replay", key);
		assertThat(status(replay)).as(rawContent(replay)).isEqualTo(200);
		assertThat(json(replay).path("data").path("id").asString()).isEqualTo(orderId.toString());
		assertThat(json(replay).path("data").path("amount").decimalValue()).isEqualByComparingTo("11300.00");
		assertThat(course(json(replay).path("data"), fx.a()).path("postageFee").decimalValue())
			.isEqualByComparingTo("200.00");
		assertThat(jdbcTemplate.queryForObject("SELECT amount FROM student_order WHERE id = ?", BigDecimal.class,
				orderId)).isEqualByComparingTo("11300.00");
		assertThat(jdbcTemplate.queryForObject("SELECT fee_policy_snapshot FROM student_order WHERE id = ?",
				String.class, orderId)).isEqualTo("PER_COURSE:100.00");
		assertThat(count("SELECT count(*) FROM student_order_course_charge WHERE order_id = ? AND course_id = ? "
				+ "AND effective_facility_fee = 100 AND postage_fee = 200", orderId, fx.a())).isEqualTo(1);
		// The same key for a different cart is a conflict, never a silent replay.
		assertThat(status(order(fx, cart(List.of(month(fx.b(), 2026, 10)), List.of()), "x", key))).isEqualTo(409);

		// L, R: verified gateway confirmation grants each line once; webhook replay adds nothing.
		PaymentInitiationResponse payment = initiatePaymentOrFail(fx.host(), fx.studentToken(), orderId);
		assertThat(sendPaymentWebhook(payment.gatewayReference(), true).getStatusCode().value()).isEqualTo(200);
		assertThat(sendPaymentWebhook(payment.gatewayReference(), true).getStatusCode().value()).isEqualTo(200);
		assertThat(count("SELECT count(*) FROM content_entitlement WHERE order_id = ?", orderId)).isEqualTo(3);
		assertThat(count("SELECT count(DISTINCT order_line_id) FROM content_entitlement WHERE order_id = ?", orderId))
			.isEqualTo(3);
		assertThat(count("SELECT count(*) FROM ledger_entry WHERE payment_id = ?", payment.paymentId())).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject("SELECT amount FROM ledger_entry WHERE payment_id = ?",
				BigDecimal.class, payment.paymentId())).isEqualByComparingTo("11300.00");
		assertThat(launch(fx, fx.a(), fx.aSep())).isEqualTo(200);
		assertThat(launch(fx, fx.a(), fx.aOct())).isEqualTo(200);
		assertThat(launch(fx, fx.b(), fx.bOct())).isEqualTo(200);
		// Paid items are no longer sold.
		assertThat(errorCode(quote(fx, cart(List.of(month(fx.b(), 2026, 10)), List.of()))))
			.isEqualTo("ITEM_ALREADY_PAID");
	}

	// ------------------------------------------------------------------
	// M, N: slips.
	// ------------------------------------------------------------------

	@Test
	void slipForAMultiCourseOrderIsPendingUntilApprovedThenGrantsAllLinesOnce() {
		Fx fx = seed("mcc-slip", true);
		UUID tenantId = fx.tenant().getId();
		UUID orderId = UUID.fromString(placeOrder(fx, fixtureCart(fx, true), null).path("id").asString());

		PaymentSlipResponse slip = uploadSlipOrFail(fx.host(), fx.studentToken(), orderId, "REF-MCC-SLIP-1",
				pdfFile("slip.pdf", distinctPdfBytes("mcc-slip-1")));
		assertThat(slip.orderAmount()).isEqualByComparingTo("11300.00");
		// M: a pending slip grants nothing; the items are PENDING in both courses.
		assertThat(entitlements(tenantId)).isZero();
		assertThat(errorCode(quote(fx, cart(List.of(month(fx.b(), 2026, 10)), List.of())))).isEqualTo("ITEM_PENDING");

		var queue = getReviewQueue(fx.host(), fx.financeToken(), null);
		PaymentSlipResponse queued = queue.getBody().data().content().get(0);
		assertThat(queued.orderLines()).hasSize(3);
		assertThat(queued.orderLines()).extracting(line -> line.courseId()).containsExactly(fx.a(), fx.a(), fx.b());
		assertThat(queued.orderLines().get(2).courseTitle()).startsWith("Course ");
		assertThat(queued.orderPostage()).isEqualByComparingTo("200.00");
		assertThat(queued.orderDelivery().addressLine()).isEqualTo("12 Temple Road");
		assertThat(queued.orderDelivery().city()).isEqualTo("Kandy");

		assertThat(approveSlip(fx.host(), fx.financeToken(), slip.id(), null).getStatusCode().value()).isEqualTo(200);
		assertThat(approveSlip(fx.host(), fx.financeToken(), slip.id(), null).getStatusCode().value()).isEqualTo(200);
		assertThat(count("SELECT count(*) FROM content_entitlement WHERE order_id = ?", orderId)).isEqualTo(3);
		assertThat(count("SELECT count(*) FROM payment WHERE order_id = ? AND status = 'CONFIRMED'", orderId))
			.isEqualTo(1);
		assertThat(count("SELECT count(*) FROM ledger_entry WHERE order_id = ?", orderId)).isEqualTo(1);
		assertThat(launch(fx, fx.b(), fx.bOct())).isEqualTo(200);
	}

	@Test
	void rejectedSlipForAMultiCourseOrderKeepsParticipationAndGrantsNothing() {
		Fx fx = seed("mcc-slip-reject", true);
		UUID tenantId = fx.tenant().getId();
		UUID orderId = UUID.fromString(placeOrder(fx, fixtureCart(fx, false), null).path("id").asString());
		PaymentSlipResponse slip = uploadSlipOrFail(fx.host(), fx.studentToken(), orderId, "REF-MCC-REJ-1",
				pdfFile("slip.pdf", distinctPdfBytes("mcc-rej-1")));
		assertThat(rejectSlip(fx.host(), fx.financeToken(), slip.id(), "Amount does not match").getStatusCode()
			.value()).isEqualTo(200);

		assertThat(entitlements(tenantId)).isZero();
		assertThat(count("SELECT count(*) FROM course_participation WHERE tenant_id = ? AND student_id = ?", tenantId,
				fx.student().getId())).isEqualTo(2);
		// The items are sellable again.
		assertThat(quoteOk(fx, fixtureCart(fx, false)).path("total").decimalValue())
			.isEqualByComparingTo("11100.00");
	}

	// ------------------------------------------------------------------
	// ADR-029: per-course revenue allocation, settlement, reports.
	// ------------------------------------------------------------------

	private UUID confirm(Fx fx, String token, UUID orderId) {
		PaymentInitiationResponse payment = initiatePaymentOrFail(fx.host(), token, orderId);
		assertThat(sendPaymentWebhook(payment.gatewayReference(), true).getStatusCode().value()).isEqualTo(200);
		return payment.paymentId();
	}

	private void backdate(UUID paymentId, String entryType, String instant) {
		jdbcTemplate.update(
				"UPDATE ledger_entry SET created_at = ?::timestamptz WHERE payment_id = ? AND entry_type = ?",
				instant, paymentId, entryType);
	}

	private void rate(Fx fx, UUID teacherId, String percent) {
		MvcResult result = jsonCall(fx.host(), fx.financeToken(), post("/api/v1/finance/teacher-share-rates"),
				Map.of("teacherId", teacherId, "sharePercent", new BigDecimal(percent), "effectiveFrom",
						"2025-01-01"));
		assertThat(status(result)).as(rawContent(result)).isEqualTo(201);
	}

	private MvcResult settle(Fx fx, UUID teacherId, String start, String end) {
		return jsonCall(fx.host(), fx.financeToken(), post("/api/v1/finance/teacher-settlements"),
				Map.of("teacherId", teacherId, "periodStart", start, "periodEnd", end));
	}

	private JsonNode settleOk(Fx fx, UUID teacherId, String start, String end) {
		MvcResult result = settle(fx, teacherId, start, end);
		assertThat(status(result)).as(rawContent(result)).isEqualTo(201);
		return json(result).path("data");
	}

	@Test
	void settlementAllocatesEachCourseShareToItsTeacherExcludingPostageAndSplitsRefundsProRata() {
		Fx fx = seed("mcc-settle", true);
		UUID tenantId = fx.tenant().getId();
		rate(fx, fx.teacher().getId(), "40.00");
		rate(fx, fx.teacher2().getId(), "50.00");

		// Single-course line order with postage: A Sep 3500 + fee 100 + postage 200 = 3800.
		JsonNode single = placeOrder(fx, cart(List.of(month(fx.a(), 2026, 9)), List.of(fx.a())), null);
		assertThat(single.path("amount").decimalValue()).isEqualByComparingTo("3800.00");
		UUID singlePayment = confirm(fx, fx.studentToken(), UUID.fromString(single.path("id").asString()));
		backdate(singlePayment, "PAYMENT_CONFIRMED", "2025-02-10T10:00:00Z");

		// Multi-course: A Oct 3500 + fee 100 (teacher1) + B Oct 4000 (teacher2) + A postage 200 = 7800.
		JsonNode multi = placeOrder(fx,
				cart(List.of(month(fx.a(), 2026, 10), month(fx.b(), 2026, 10)), List.of(fx.a())), null);
		UUID multiOrder = UUID.fromString(multi.path("id").asString());
		assertThat(multi.path("amount").decimalValue()).isEqualByComparingTo("7800.00");
		// Course charges sum to the header; every line course has a charge row.
		assertThat(jdbcTemplate.queryForObject(
				"SELECT sum(facility_fee_amount) FROM student_order_course_charge WHERE order_id = ?",
				BigDecimal.class, multiOrder))
			.isEqualByComparingTo(multi.path("facilityFeeTotal").decimalValue());
		assertThat(jdbcTemplate.queryForObject(
				"SELECT sum(postage_amount) FROM student_order_course_charge WHERE order_id = ?", BigDecimal.class,
				multiOrder))
			.isEqualByComparingTo("200.00");
		assertThat(jdbcTemplate.queryForObject(
				"SELECT (SELECT sum(line_total) FROM student_order_line WHERE order_id = o.id) + o.postage_amount"
						+ " - o.amount FROM student_order o WHERE o.id = ?",
				BigDecimal.class, multiOrder))
			.isEqualByComparingTo("0");
		assertThat(count("SELECT count(DISTINCT l.course_id) FROM student_order_line l WHERE l.order_id = ? AND NOT "
				+ "EXISTS (SELECT 1 FROM student_order_course_charge c WHERE c.order_id = l.order_id "
				+ "AND c.course_id = l.course_id)", multiOrder))
			.isZero();

		UUID multiPayment = confirm(fx, fx.studentToken(), multiOrder);
		backdate(multiPayment, "PAYMENT_CONFIRMED", "2025-03-05T10:00:00Z");

		// Course payment summary: course B counts the multi-course order with B's share only.
		JsonNode summaryB = json(
				call(fx.host(), fx.financeToken(), get("/api/v1/ledger/courses/{courseId}/summary", fx.b())))
			.path("data");
		assertThat(summaryB.path("totalOrders").asLong()).isEqualTo(1);
		BigDecimal bAmount = BigDecimal.ZERO;
		for (JsonNode bucket : summaryB.path("byState")) {
			bAmount = bAmount.add(bucket.path("totalAmount").decimalValue());
		}
		assertThat(bAmount).isEqualByComparingTo("4000.00");
		// Ledger history titles a multi-course order "2 courses".
		JsonNode dashboard = json(call(fx.host(), fx.financeToken(), get("/api/v1/ledger/dashboard?size=50")))
			.path("data")
			.path("content");
		boolean titled = false;
		for (JsonNode row : dashboard) {
			if (row.path("orderId").asString().equals(multiOrder.toString())) {
				assertThat(row.path("courseTitle").asString()).isEqualTo("2 courses");
				titled = true;
			}
		}
		assertThat(titled).isTrue();

		// Postage excluded from teacher gross for a single-course order.
		JsonNode feb = settleOk(fx, fx.teacher().getId(), "2025-02-01", "2025-02-28").path("settlement");
		assertThat(feb.path("grossAmount").decimalValue()).isEqualByComparingTo("3600.00");

		// Each teacher gets exactly their course's share of the multi-course payment.
		JsonNode march1 = settleOk(fx, fx.teacher().getId(), "2025-03-01", "2025-03-31");
		assertThat(march1.path("settlement").path("grossAmount").decimalValue()).isEqualByComparingTo("3600.00");
		assertThat(march1.path("settlement").path("shareAmount").decimalValue()).isEqualByComparingTo("1440.00");
		assertThat(march1.path("courses")).hasSize(1);
		assertThat(march1.path("courses").get(0).path("courseId").asString()).isEqualTo(fx.a().toString());
		JsonNode march2 = settleOk(fx, fx.teacher2().getId(), "2025-03-01", "2025-03-31");
		assertThat(march2.path("settlement").path("grossAmount").decimalValue()).isEqualByComparingTo("4000.00");
		assertThat(march2.path("settlement").path("shareAmount").decimalValue()).isEqualByComparingTo("2000.00");
		UUID multiEntry = jdbcTemplate.queryForObject(
				"SELECT id FROM ledger_entry WHERE payment_id = ? AND entry_type = 'PAYMENT_CONFIRMED'", UUID.class,
				multiPayment);
		assertThat(count("SELECT count(*) FROM teacher_settlement_item WHERE ledger_entry_id = ?", multiEntry))
			.isEqualTo(2);

		// Re-running never double-pays.
		long items = count("SELECT count(*) FROM teacher_settlement_item WHERE tenant_id = ?", tenantId);
		assertThat(status(settle(fx, fx.teacher().getId(), "2025-03-01", "2025-03-31"))).isEqualTo(409);
		assertThat(status(settle(fx, fx.teacher2().getId(), "2025-03-01", "2025-03-31"))).isEqualTo(409);
		assertThat(count("SELECT count(*) FROM teacher_settlement_item WHERE tenant_id = ?", tenantId))
			.isEqualTo(items);

		// Finance reports: course rows use allocations; totals/counts per ledger entry (postage included).
		JsonNode courseReport = json(call(fx.host(), fx.financeToken(),
				get("/api/v1/finance/reports/course-revenue?from=2025-03-01&to=2025-03-31")))
			.path("data");
		assertThat(courseReport.path("totals").path("gross").decimalValue()).isEqualByComparingTo("7800.00");
		assertThat(courseReport.path("totals").path("paymentCount").asInt()).isEqualTo(1);
		assertThat(courseReport.path("rows")).hasSize(2);

		// A 10% refund (780.00) is split pro rata: A 360.00, B 400.00 (postage part 20.00 unattributed).
		var refund = createRefund(fx.host(), fx.adminToken(), multiPayment, new BigDecimal("780.00"),
				"Partial refund");
		assertThat(refund.getStatusCode().is2xxSuccessful()).as(String.valueOf(refund.getBody())).isTrue();
		backdate(multiPayment, "REFUND", "2025-04-02T09:00:00Z");
		JsonNode april1 = settleOk(fx, fx.teacher().getId(), "2025-04-01", "2025-04-09").path("settlement");
		assertThat(april1.path("refundAmount").decimalValue()).isEqualByComparingTo("360.00");
		JsonNode april2 = settleOk(fx, fx.teacher2().getId(), "2025-04-01", "2025-04-09").path("settlement");
		assertThat(april2.path("refundAmount").decimalValue()).isEqualByComparingTo("400.00");
		assertThat(april2.path("shareAmount").decimalValue()).isEqualByComparingTo("-200.00");
	}

	@Test
	void failedGatewayPaymentForAMultiCourseOrderGrantsNothingAndKeepsParticipation() {
		Fx fx = seed("mcc-gw-fail", true);
		UUID tenantId = fx.tenant().getId();
		UUID orderId = UUID.fromString(placeOrder(fx, fixtureCart(fx, true), null).path("id").asString());
		PaymentInitiationResponse payment = initiatePaymentOrFail(fx.host(), fx.studentToken(), orderId);
		assertThat(sendPaymentWebhook(payment.gatewayReference(), false).getStatusCode().value()).isEqualTo(200);

		assertThat(count("SELECT count(*) FROM payment WHERE id = ? AND status = 'REJECTED'", payment.paymentId()))
			.isEqualTo(1);
		assertThat(entitlements(tenantId)).isZero();
		assertThat(count("SELECT count(*) FROM ledger_entry WHERE order_id = ?", orderId)).isZero();
		assertThat(count("SELECT count(*) FROM course_participation WHERE tenant_id = ? AND student_id = ?", tenantId,
				fx.student().getId()))
			.isEqualTo(2);
		assertThat(launch(fx, fx.b(), fx.bOct())).isNotEqualTo(200);
	}

	// ------------------------------------------------------------------
	// Security negatives.
	// ------------------------------------------------------------------

	private String extraStudent(Fx fx, String email, boolean enrol) {
		TenantUser user = seedActiveStudent(fx.tenant().getId(), email);
		jdbcTemplate.update("INSERT INTO student_profile (id, tenant_id, user_id, name, created_at, updated_at) "
				+ "VALUES (?, ?, ?, 'Kamal Perera', now(), now())", UUID.randomUUID(), fx.tenant().getId(),
				user.getId());
		jdbcTemplate.update("UPDATE student_profile SET address_line = '5 Lake Rd', city = 'Galle', "
				+ "mobile = '0711111111' WHERE user_id = ?", user.getId());
		String token = loginAndGetToken(fx.host(), email);
		if (enrol) {
			for (UUID course : List.of(fx.a(), fx.b())) {
				assertThat(status(call(fx.host(), token, post("/api/v1/enrollments/my/courses/{courseId}", course))))
					.isEqualTo(200);
			}
		}
		return token;
	}

	@Test
	void foreignOrMismatchedIdsAreRejectedAndIdempotencyKeysAreScopedToTheStudent() {
		Fx fx = seed("mcc-sec", true);
		Fx other = seed("mcc-sec-x", true);

		// Same-tenant course the student does not participate in: 404.
		UUID notJoined = monthlyCourse(fx.host(), fx.adminToken(), fx.teacher().getId(), "mcc-sec-c", "1000.00");
		assertThat(status(quote(fx, cart(List.of(month(fx.a(), 2026, 9), month(notJoined, 2026, 9)), List.of()))))
			.isEqualTo(404);
		// Foreign course id as postage course: 422, never a lookup into the other tenant.
		MvcResult foreignPostage = quote(fx, cart(List.of(month(fx.a(), 2026, 9)), List.of(other.a())));
		assertThat(status(foreignPostage)).isEqualTo(422);
		assertThat(errorCode(foreignPostage)).isEqualTo("POSTAGE_COURSE_NOT_IN_CART");
		// moduleId / lessonId of another course or another tenant under course A: 422.
		List<Map<String, Object>> mismatched = List.of(
				Map.of("courseId", fx.a(), "scope", "MODULE", "moduleId", fx.bModule()),
				Map.of("courseId", fx.a(), "scope", "LESSON", "lessonId", fx.bOct()),
				Map.of("courseId", fx.a(), "scope", "LESSON", "lessonId", other.aSep()));
		for (Map<String, Object> item : mismatched) {
			assertThat(status(quote(fx, cart(List.of(item), List.of())))).as(item.toString()).isEqualTo(422);
		}

		// Student B reusing student A's idempotency key gets their own order, never A's.
		UUID key = UUID.randomUUID();
		JsonNode orderA = placeOrder(fx, fixtureCart(fx, true), key);
		String tokenB = extraStudent(fx, "student-b@example.test", true);
		Map<String, Object> body = new LinkedHashMap<>(fixtureCart(fx, true));
		body.put("quoteToken",
				json(quote(fx.host(), tokenB, fixtureCart(fx, true))).path("data").path("quoteToken").asString());
		body.put("idempotencyKey", key);
		MvcResult resultB = jsonCall(fx.host(), tokenB, post("/api/v1/checkout/orders"), body);
		assertThat(status(resultB)).as(rawContent(resultB)).isEqualTo(201);
		JsonNode orderB = json(resultB).path("data");
		assertThat(orderB.path("id").asString()).isNotEqualTo(orderA.path("id").asString());
		assertThat(orderB.path("studentId").asString()).isNotEqualTo(orderA.path("studentId").asString());
		assertThat(rawContent(resultB)).doesNotContain("12 Temple Road");

		// Same key, different selection (postage dropped / a different month) is a 409 for student A.
		assertThat(status(order(fx, fixtureCart(fx, false), "x", key))).isEqualTo(409);
		assertThat(status(order(fx,
				cart(List.of(month(fx.a(), 2026, 9), month(fx.b(), 2026, 10)), List.of(fx.a())), "x", key)))
			.isEqualTo(409);
	}

	@Test
	void multiCourseSlipWithDeliveryDataIsHiddenFromOtherStudentsAndRolesWithoutSlipView() {
		Fx fx = seed("mcc-slip-sec", true);
		UUID orderId = UUID.fromString(placeOrder(fx, fixtureCart(fx, true), null).path("id").asString());
		PaymentSlipResponse slip = uploadSlipOrFail(fx.host(), fx.studentToken(), orderId, "REF-MCC-SEC-1",
				pdfFile("slip.pdf", distinctPdfBytes("mcc-sec-1")));

		String otherStudent = extraStudent(fx, "student-c@example.test", false);
		MvcResult byStudent = call(fx.host(), otherStudent, get(SLIP_PATH, slip.id()));
		assertThat(status(byStudent)).isEqualTo(404);
		assertThat(rawContent(byStudent)).doesNotContain("12 Temple Road");
		MvcResult byTeacher = call(fx.host(), fx.teacherToken(), get(SLIP_PATH, slip.id()));
		assertThat(status(byTeacher)).isIn(403, 404);
		assertThat(rawContent(byTeacher)).doesNotContain("12 Temple Road");
		assertThat(getReviewQueue(fx.host(), fx.teacherToken(), null).getStatusCode().value()).isIn(403, 404);
		// Another tenant's finance staff: 404.
		Fx other = seed("mcc-slip-sec-x", false);
		assertThat(status(call(other.host(), other.financeToken(), get(SLIP_PATH, slip.id())))).isEqualTo(404);
		// Finance of the owning tenant still sees it, with delivery data.
		assertThat(getSlip(fx.host(), fx.financeToken(), slip.id()).getBody().data().orderDelivery().city())
			.isEqualTo("Kandy");
	}

}
