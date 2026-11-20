package com.lms.paymentmanagement.discount;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.lms.coursemanagement.course.domain.CoursePricingModel;
import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.web.dto.CourseCreateRequest;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.paymentmanagement.SlipTestSupport;
import com.lms.paymentmanagement.slip.web.dto.PaymentSlipResponse;
import com.lms.tenantmanagement.domain.Tenant;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
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
 * DISC-01 backend smoke test of the full path (the QA suite adds the exhaustive
 * acceptance ITs): admin CRUD + versioning + validation errors + cross-tenant
 * 404, code / free-course / zero-total pricing through the real composer,
 * order snapshot + RESERVED hold, slip upload (open-ended hold), approval
 * (CONSUMED) and the student-side usage limit. Dates are relative to the real
 * current month (tenant timezone = UTC default).
 */
class DiscountCheckoutSmokeIntegrationTest extends SlipTestSupport {

	private static final String ADMIN = "/api/v1/admin/discounts";

	private final YearMonth current = YearMonth.now(ZoneOffset.UTC);

	private final YearMonth next = current.plusMonths(1);

	private record Fx(Tenant tenant, String host, String adminToken, String financeToken, String studentToken,
			TenantUser student, UUID a, UUID b, UUID c) {

	}

	private Fx seed(String prefix) {
		Tenant tenant = seedActiveTenant(uniqueSubdomain(prefix));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		seedTenantUser(tenant.getId(), "finance@example.test", RAW_PASSWORD, Role.FINANCE_STAFF);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		TenantUser student = seedActiveStudent(tenant.getId(), "student@example.test");
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String financeToken = loginAndGetToken(host, "finance@example.test");
		String studentToken = loginAndGetToken(host, "student@example.test");
		UUID a = monthlyCourse(host, adminToken, teacher.getId(), prefix + "-a", "1000.00");
		UUID b = monthlyCourse(host, adminToken, teacher.getId(), prefix + "-b", "2000.00");
		UUID c = monthlyCourse(host, adminToken, teacher.getId(), prefix + "-c", "500.00");
		for (UUID course : List.of(a, b, c)) {
			UUID module = createModuleOrFail(host, adminToken, course, "Module", 1).id();
			UUID first = createLessonOrFail(host, adminToken, course, module, "This month", 1).id();
			UUID second = createLessonOrFail(host, adminToken, course, module, "Next month", 2).id();
			covered(first, current);
			covered(second, next);
			assertThat(status(call(host, studentToken, post("/api/v1/enrollments/my/courses/{courseId}", course))))
				.isEqualTo(200);
		}
		return new Fx(tenant, host, adminToken, financeToken, studentToken, student, a, b, c);
	}

	private UUID monthlyCourse(String host, String adminToken, UUID teacherId, String prefix, String fee) {
		String slug = uniqueSlug(prefix);
		return createCourseOrFail(host, adminToken,
				new CourseCreateRequest("Course " + slug, slug, "Mathematics", null, null, null, null, null,
						new BigDecimal(fee), null, null, CourseStatus.PUBLIC, teacherId, null,
						CoursePricingModel.MONTHLY, null, null, null, null, null, null, null))
			.id();
	}

	private void covered(UUID lessonId, YearMonth month) {
		jdbcTemplate.update("UPDATE course_lesson SET payment_type = 'COVERED_BY_CLASS_FEES', billing_year = ?, "
				+ "billing_month = ? WHERE id = ?", month.getYear(), month.getMonthValue(), lessonId);
	}

	private MvcResult call(String host, String token, MockHttpServletRequestBuilder builder) {
		return perform(authenticated(builder, host, token));
	}

	private MvcResult jsonCall(String host, String token, MockHttpServletRequestBuilder builder, Object body) {
		return call(host, token,
				builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
	}

	private JsonNode json(MvcResult result) {
		return objectMapper.readTree(rawContent(result));
	}

	private static int status(MvcResult result) {
		return result.getResponse().getStatus();
	}

	private long count(String sql, Object... args) {
		Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
		return value == null ? 0 : value;
	}

	private Map<String, Object> rule(String name, String type, List<UUID> courses) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("name", name);
		body.put("discountType", type);
		body.put("courseIds", courses);
		body.put("selectionMode", "ALL");
		body.put("validFrom", LocalDate.now(ZoneOffset.UTC).minusDays(1).toString());
		body.put("validTo", LocalDate.now(ZoneOffset.UTC).plusDays(60).toString());
		return body;
	}

	private UUID createRule(Fx fx, Map<String, Object> body) {
		MvcResult result = jsonCall(fx.host(), fx.adminToken(), post(ADMIN), body);
		assertThat(status(result)).as(rawContent(result)).isEqualTo(201);
		return UUID.fromString(json(result).path("data").path("id").asString());
	}

	private static Map<String, Object> month(UUID courseId, YearMonth month) {
		Map<String, Object> item = new LinkedHashMap<>();
		item.put("courseId", courseId);
		item.put("scope", "MONTHLY");
		item.put("year", month.getYear());
		item.put("month", month.getMonthValue());
		return item;
	}

	private JsonNode quoteOk(Fx fx, Map<String, Object> body) {
		MvcResult result = jsonCall(fx.host(), fx.studentToken(), post("/api/v1/checkout/quote"), body);
		assertThat(status(result)).as(rawContent(result)).isEqualTo(200);
		return json(result).path("data");
	}

	private MvcResult order(Fx fx, Map<String, Object> body, String token) {
		Map<String, Object> request = new LinkedHashMap<>(body);
		request.put("quoteToken", token);
		return jsonCall(fx.host(), fx.studentToken(), post("/api/v1/checkout/orders"), request);
	}

	@Test
	void codeDiscountIsSnapshottedReservedHeldAndConsumedAndAdminRulesAreVersionedAndIsolated() {
		Fx fx = seed("disc-code");
		Map<String, Object> code = rule("Ten off", "DISCOUNT_CODE", List.of(fx.a()));
		code.put("selectionMode", "ANY");
		code.put("valueType", "PERCENTAGE");
		code.put("value", 10);
		code.put("code", "  save10 ");
		code.put("maxUses", 1);
		UUID ruleId = createRule(fx, code);

		// Admin validation and conflicts.
		MvcResult duplicate = jsonCall(fx.host(), fx.adminToken(), post(ADMIN), code);
		assertThat(status(duplicate)).isEqualTo(409);
		assertThat(json(duplicate).path("error").path("code").asString()).isEqualTo("DISCOUNT_CODE_TAKEN");
		Map<String, Object> invalid = rule(" ", "DISCOUNT_CODE", List.of(fx.a()));
		MvcResult validation = jsonCall(fx.host(), fx.adminToken(), post(ADMIN), invalid);
		assertThat(status(validation)).isEqualTo(422);
		assertThat(json(validation).path("error").path("code").asString()).isEqualTo("DISCOUNT_VALIDATION");
		assertThat(json(validation).path("error").path("fieldErrors").toString()).contains("name", "code", "value");
		Map<String, Object> foreignCourse = rule("Foreign", "BUNDLE_AMOUNT_OFF", List.of(UUID.randomUUID()));
		foreignCourse.put("valueType", "FIXED");
		foreignCourse.put("value", 50);
		MvcResult notFound = jsonCall(fx.host(), fx.adminToken(), post(ADMIN), foreignCourse);
		assertThat(status(notFound)).isEqualTo(422);
		assertThat(json(notFound).path("error").path("code").asString()).isEqualTo("DISCOUNT_COURSE_NOT_FOUND");
		assertThat(status(call(fx.host(), fx.studentToken(), get(ADMIN)))).isEqualTo(403);
		JsonNode options = json(call(fx.host(), fx.adminToken(), get(ADMIN + "/course-options"))).path("data");
		assertThat(options).hasSize(3);
		assertThat(options.get(0).path("monthly").asBoolean()).isTrue();

		// Quote with the (case-insensitive) code.
		Map<String, Object> cart = new LinkedHashMap<>();
		cart.put("items", List.of(month(fx.a(), current)));
		cart.put("discountCode", "Save10");
		JsonNode quote = quoteOk(fx, cart);
		assertThat(quote.path("codeResult").path("status").asString()).isEqualTo("APPLIED");
		assertThat(quote.path("discountTotal").decimalValue()).isEqualByComparingTo("100.00");
		assertThat(quote.path("total").decimalValue()).isEqualByComparingTo("900.00");
		assertThat(quote.path("lines").get(0).path("discountAmount").decimalValue()).isEqualByComparingTo("100.00");
		assertThat(quote.path("lines").get(0).path("discountName").asString()).isEqualTo("Ten off");
		assertThat(quote.path("discounts").get(0).path("code").asString()).isEqualTo("SAVE10");
		assertThat(quote.toString()).doesNotContain("maxUses");

		MvcResult placed = order(fx, cart, quote.path("quoteToken").asString());
		assertThat(status(placed)).as(rawContent(placed)).isEqualTo(201);
		JsonNode order = json(placed).path("data");
		UUID orderId = UUID.fromString(order.path("id").asString());
		assertThat(order.path("amount").decimalValue()).isEqualByComparingTo("900.00");
		assertThat(order.path("discounts").get(0).path("ruleId").asString()).isEqualTo(ruleId.toString());
		assertThat(count("SELECT count(*) FROM student_order_discount WHERE order_id = ?", orderId)).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM student_order_line WHERE order_id = ? AND order_discount_id IS NOT NULL",
				orderId)).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM discount_redemption WHERE order_id = ? AND status = 'RESERVED' "
				+ "AND expires_at IS NOT NULL", orderId)).isEqualTo(1);

		// The reservation counts as this student's one use of the rule (owner
		// decision 2026-10-21); it also holds the only capacity.
		Map<String, Object> again = new LinkedHashMap<>();
		again.put("items", List.of(month(fx.a(), next)));
		again.put("discountCode", "SAVE10");
		JsonNode againQuote = quoteOk(fx, again);
		assertThat(againQuote.path("codeResult").path("status").asString()).isEqualTo("NOT_ELIGIBLE");
		assertThat(againQuote.path("codeResult").path("message").asString())
			.isEqualTo("You have already used this discount.");

		// Slip upload -> open-ended hold; approval -> CONSUMED, discounted amount paid.
		PaymentSlipResponse slip = uploadSlipOrFail(fx.host(), fx.studentToken(), orderId, "REF-DISC-1",
				pdfFile("slip.pdf", distinctPdfBytes("disc-1")));
		assertThat(slip.orderAmount()).isEqualByComparingTo("900.00");
		assertThat(count("SELECT count(*) FROM discount_redemption WHERE order_id = ? AND expires_at IS NULL",
				orderId)).isEqualTo(1);
		assertThat(approveSlip(fx.host(), fx.financeToken(), slip.id(), null).getStatusCode().value()).isEqualTo(200);
		assertThat(count("SELECT count(*) FROM discount_redemption WHERE order_id = ? AND status = 'CONSUMED'",
				orderId)).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM payment WHERE order_id = ? AND status = 'CONFIRMED' AND amount = 900",
				orderId)).isEqualTo(1);

		// Edit = new version; the order keeps its version. Toggle, archive, isolation.
		code.put("value", 15);
		code.remove("active");
		MvcResult edited = jsonCall(fx.host(), fx.adminToken(), put(ADMIN + "/{id}", ruleId), code);
		assertThat(status(edited)).as(rawContent(edited)).isEqualTo(200);
		assertThat(json(edited).path("data").path("versionNo").asInt()).isEqualTo(2);
		assertThat(json(edited).path("data").path("usedCount").asLong()).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM student_order_discount d JOIN discount_rule_version v "
				+ "ON v.id = d.rule_version_id WHERE d.order_id = ? AND v.version_no = 1", orderId)).isEqualTo(1);
		assertThat(status(jsonCall(fx.host(), fx.adminToken(), patch(ADMIN + "/{id}/active", ruleId),
				Map.of("active", false)))).isEqualTo(200);
		JsonNode list = json(call(fx.host(), fx.adminToken(), get(ADMIN + "?status=INACTIVE"))).path("data");
		assertThat(list.path("counts").path("inactive").asLong()).isEqualTo(1);
		assertThat(list.path("items")).hasSize(1);

		Fx other = seed("disc-other");
		assertThat(status(call(other.host(), other.adminToken(), get(ADMIN + "/{id}", ruleId)))).isEqualTo(404);
		assertThat(status(call(other.host(), other.adminToken(), delete(ADMIN + "/{id}", ruleId)))).isEqualTo(404);

		assertThat(status(call(fx.host(), fx.adminToken(), delete(ADMIN + "/{id}", ruleId)))).isEqualTo(204);
		assertThat(status(call(fx.host(), fx.adminToken(), get(ADMIN + "/{id}", ruleId)))).isEqualTo(404);
		assertThat(json(call(fx.host(), fx.adminToken(), get(ADMIN))).path("data").path("counts").path("all").asLong())
			.isZero();
		assertThat(count("SELECT count(*) FROM audit_log WHERE target_id = ? AND action LIKE 'discount_rule.%'",
				ruleId)).isEqualTo(4);
	}

	@Test
	void gatewayEvidenceRetakesHoldsRejectionRestartsTheTtlAndConfirmationConsumes() {
		Fx fx = seed("disc-gw");
		Map<String, Object> code = rule("Once", "DISCOUNT_CODE", List.of(fx.a()));
		code.put("valueType", "FIXED");
		code.put("value", 100);
		code.put("code", "ONCE01");
		code.put("maxUses", 1);
		createRule(fx, code);

		Map<String, Object> first = new LinkedHashMap<>();
		first.put("items", List.of(month(fx.a(), current)));
		first.put("discountCode", "ONCE01");
		MvcResult placedFirst = order(fx, first, quoteOk(fx, first).path("quoteToken").asString());
		assertThat(status(placedFirst)).as(rawContent(placedFirst)).isEqualTo(201);
		UUID firstOrder = UUID.fromString(json(placedFirst).path("data").path("id").asString());

		// The first hold lapses (it no longer counts as this student's use); a
		// second order by the same student takes the only use.
		jdbcTemplate.update("UPDATE discount_redemption SET expires_at = now() - interval '1 hour' WHERE order_id = ?",
				firstOrder);
		Map<String, Object> second = new LinkedHashMap<>();
		second.put("items", List.of(month(fx.a(), next)));
		second.put("discountCode", "ONCE01");
		JsonNode secondQuote = quoteOk(fx, second);
		assertThat(secondQuote.path("codeResult").path("status").asString()).isEqualTo("APPLIED");
		MvcResult placedSecond = order(fx, second, secondQuote.path("quoteToken").asString());
		assertThat(status(placedSecond)).as(rawContent(placedSecond)).isEqualTo(201);
		UUID secondOrder = UUID.fromString(json(placedSecond).path("data").path("id").asString());

		// Paying the first order can no longer re-take its lapsed hold.
		var refused = initiatePayment(fx.host(), fx.studentToken(), firstOrder);
		assertThat(refused.getStatusCode().value()).isEqualTo(409);
		assertThat(refused.getBody().error().code()).isEqualTo("DISCOUNT_NO_LONGER_AVAILABLE");

		// A gateway payment holds for the gateway TTL (48h from payment start), never open-ended.
		var payment = initiatePaymentOrFail(fx.host(), fx.studentToken(), secondOrder);
		assertThat(count("SELECT count(*) FROM discount_redemption WHERE order_id = ? AND status = 'RESERVED' "
				+ "AND expires_at > now() + interval '47 hours' AND expires_at < now() + interval '49 hours'",
				secondOrder)).isEqualTo(1);
		assertThat(sendPaymentWebhook(payment.gatewayReference(), false).getStatusCode().value()).isEqualTo(200);
		assertThat(count("SELECT count(*) FROM discount_redemption WHERE order_id = ? AND status = 'RESERVED' "
				+ "AND expires_at > now() + interval '47 hours'", secondOrder)).isEqualTo(1);

		var retry = initiatePaymentOrFail(fx.host(), fx.studentToken(), secondOrder);
		assertThat(sendPaymentWebhook(retry.gatewayReference(), true).getStatusCode().value()).isEqualTo(200);
		assertThat(sendPaymentWebhook(retry.gatewayReference(), true).getStatusCode().value()).isEqualTo(200);
		assertThat(count("SELECT count(*) FROM discount_redemption WHERE order_id = ? AND status = 'CONSUMED'",
				secondOrder)).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM payment WHERE order_id = ? AND status = 'CONFIRMED' AND amount = 900",
				secondOrder)).isEqualTo(1);
	}

	@Test
	void freeCourseRewardIsOfferedAddedOnAcceptanceAndAZeroTotalOrderIsBlocked() {
		Fx fx = seed("disc-free");
		Map<String, Object> free = rule("Free C", "BUNDLE_FREE_COURSE", List.of(fx.a()));
		free.put("rewardCourseIds", List.of(fx.c()));
		free.put("freeMonths", 1);
		UUID freeRule = createRule(fx, free);

		Map<String, Object> cart = new LinkedHashMap<>();
		cart.put("items", List.of(month(fx.a(), current)));
		JsonNode offered = quoteOk(fx, cart);
		assertThat(offered.path("rewardOffers")).hasSize(1);
		assertThat(offered.path("rewardOffers").get(0).path("courseId").asString()).isEqualTo(fx.c().toString());
		assertThat(offered.path("rewardOffers").get(0).path("months")).hasSize(1);
		assertThat(offered.path("total").decimalValue()).isEqualByComparingTo("1000.00");

		cart.put("acceptedRewardRuleIds", List.of(freeRule));
		JsonNode accepted = quoteOk(fx, cart);
		assertThat(accepted.path("lines")).hasSize(2);
		JsonNode reward = accepted.path("lines").get(1);
		assertThat(reward.path("reward").asBoolean()).isTrue();
		assertThat(reward.path("courseId").asString()).isEqualTo(fx.c().toString());
		assertThat(reward.path("discountAmount").decimalValue()).isEqualByComparingTo("500.00");
		assertThat(accepted.path("total").decimalValue()).isEqualByComparingTo("1000.00");
		assertThat(accepted.path("discounts").get(0).path("type").asString()).isEqualTo("BUNDLE_FREE_COURSE");

		// A prerequisite removed -> notice, no reward.
		Map<String, Object> withoutA = new LinkedHashMap<>();
		withoutA.put("items", List.of(month(fx.b(), current)));
		withoutA.put("acceptedRewardRuleIds", List.of(freeRule));
		JsonNode removed = quoteOk(fx, withoutA);
		assertThat(removed.path("notices")).hasSize(1);
		assertThat(removed.path("lines")).hasSize(1);

		MvcResult placed = order(fx, cart, accepted.path("quoteToken").asString());
		assertThat(status(placed)).as(rawContent(placed)).isEqualTo(201);
		UUID orderId = UUID.fromString(json(placed).path("data").path("id").asString());
		assertThat(count("SELECT count(*) FROM student_order_line WHERE order_id = ? AND reward", orderId))
			.isEqualTo(1);

		// 100% bundle on B -> zero total is flagged and cannot be ordered.
		Map<String, Object> all = rule("All of B", "BUNDLE_AMOUNT_OFF", List.of(fx.b()));
		all.put("valueType", "PERCENTAGE");
		all.put("value", 100);
		createRule(fx, all);
		Map<String, Object> onlyB = new LinkedHashMap<>();
		onlyB.put("items", List.of(month(fx.b(), next)));
		JsonNode zero = quoteOk(fx, onlyB);
		assertThat(zero.path("total").decimalValue()).isEqualByComparingTo("0");
		assertThat(zero.path("blockingIssue").asString()).isEqualTo("ORDER_TOTAL_ZERO");
		MvcResult blocked = order(fx, onlyB, zero.path("quoteToken").asString());
		assertThat(status(blocked)).isEqualTo(422);
		assertThat(json(blocked).path("error").path("code").asString()).isEqualTo("ORDER_TOTAL_ZERO");
	}

}
