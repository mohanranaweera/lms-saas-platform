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
import com.lms.paymentmanagement.order.web.dto.PaymentInitiationResponse;
import com.lms.paymentmanagement.slip.web.dto.PaymentSlipResponse;
import com.lms.tenantmanagement.domain.Tenant;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * Shared Testcontainers fixture for the DISC-01 integration tests: a tenant
 * with Tenant Admin, Finance Staff, a teacher and an enrolled student, three
 * MONTHLY courses (A 1000, B 2000, C 500) each offering the current month and
 * the two after it, plus HTTP/JSON helpers for admin rules, quotes and orders.
 * Not itself a test class.
 */
public abstract class DiscountTestSupport extends SlipTestSupport {

	protected static final String ADMIN = "/api/v1/admin/discounts";

	protected final YearMonth current = YearMonth.now(ZoneOffset.UTC);

	protected final YearMonth next = current.plusMonths(1);

	protected final YearMonth later = current.plusMonths(2);

	protected final LocalDate today = LocalDate.now(ZoneOffset.UTC);

	/** {@code lessons.get(course)[i]} = the lesson billed in {@code current + i} months. */
	protected record Fx(Tenant tenant, String host, String adminToken, String financeToken, String studentToken,
			TenantUser student, TenantUser teacher, UUID a, UUID b, UUID c, Map<UUID, UUID[]> lessons) {

	}

	// ------------------------------------------------------------------
	// Fixture.
	// ------------------------------------------------------------------

	protected Fx seed(String prefix) {
		Tenant tenant = seedActiveTenant(uniqueSubdomain(prefix));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		seedTenantUser(tenant.getId(), "finance@example.test", RAW_PASSWORD, Role.FINANCE_STAFF);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		TenantUser student = seedActiveStudent(tenant.getId(), "student@example.test");
		jdbcTemplate.update("INSERT INTO student_profile (id, tenant_id, user_id, name, created_at, updated_at, "
				+ "address_line, city, mobile) VALUES (?, ?, ?, 'Nimal Silva', now(), now(), '12 Temple Road', "
				+ "'Kandy', '0771234567')", UUID.randomUUID(), tenant.getId(), student.getId());
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String financeToken = loginAndGetToken(host, "finance@example.test");
		String studentToken = loginAndGetToken(host, "student@example.test");
		UUID a = monthlyCourse(host, adminToken, teacher.getId(), prefix + "-a", "1000.00");
		UUID b = monthlyCourse(host, adminToken, teacher.getId(), prefix + "-b", "2000.00");
		UUID c = monthlyCourse(host, adminToken, teacher.getId(), prefix + "-c", "500.00");
		Map<UUID, UUID[]> lessons = new LinkedHashMap<>();
		for (UUID course : List.of(a, b, c)) {
			UUID module = createModuleOrFail(host, adminToken, course, "Module", 1).id();
			UUID[] ids = new UUID[3];
			for (int i = 0; i < 3; i++) {
				ids[i] = createLessonOrFail(host, adminToken, course, module, "Month " + i, i + 1).id();
				YearMonth month = current.plusMonths(i);
				jdbcTemplate.update("UPDATE course_lesson SET payment_type = 'COVERED_BY_CLASS_FEES', "
						+ "billing_year = ?, billing_month = ? WHERE id = ?", month.getYear(), month.getMonthValue(),
						ids[i]);
			}
			lessons.put(course, ids);
			enroll(host, studentToken, course);
		}
		return new Fx(tenant, host, adminToken, financeToken, studentToken, student, teacher, a, b, c, lessons);
	}

	protected void enroll(String host, String token, UUID course) {
		assertThat(status(call(host, token, post("/api/v1/enrollments/my/courses/{courseId}", course))))
			.isEqualTo(200);
	}

	/** A second, enrolled student of the same tenant; returns its token. */
	protected String extraStudent(Fx fx, String email) {
		TenantUser user = seedActiveStudent(fx.tenant().getId(), email);
		jdbcTemplate.update("INSERT INTO student_profile (id, tenant_id, user_id, name, created_at, updated_at) "
				+ "VALUES (?, ?, ?, 'Kamal Perera', now(), now())", UUID.randomUUID(), fx.tenant().getId(),
				user.getId());
		String token = loginAndGetToken(fx.host(), email);
		for (UUID course : List.of(fx.a(), fx.b(), fx.c())) {
			enroll(fx.host(), token, course);
		}
		return token;
	}

	protected UUID monthlyCourse(String host, String adminToken, UUID teacherId, String prefix, String fee) {
		String slug = uniqueSlug(prefix);
		return createCourseOrFail(host, adminToken,
				new CourseCreateRequest("Course " + slug, slug, "Mathematics", null, null, null, null, null,
						new BigDecimal(fee), null, null, CourseStatus.PUBLIC, teacherId, null,
						CoursePricingModel.MONTHLY, null, null, null, null, null, null, null))
			.id();
	}

	// ------------------------------------------------------------------
	// HTTP / JSON helpers.
	// ------------------------------------------------------------------

	protected MvcResult call(String host, String token, MockHttpServletRequestBuilder builder) {
		return perform(authenticated(builder, host, token));
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

	protected long count(String sql, Object... args) {
		Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
		return value == null ? 0 : value;
	}

	protected Map<String, Object> rule(String name, String type, List<UUID> courses) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("name", name);
		body.put("discountType", type);
		body.put("courseIds", courses);
		body.put("selectionMode", "ALL");
		body.put("validFrom", today.minusDays(1).toString());
		body.put("validTo", today.plusDays(60).toString());
		return body;
	}

	protected Map<String, Object> valueRule(String name, String type, List<UUID> courses, String mode,
			String valueType, Object value) {
		Map<String, Object> body = rule(name, type, courses);
		body.put("selectionMode", mode);
		body.put("valueType", valueType);
		body.put("value", value);
		if ("ADVANCE_PAYMENT".equals(type)) {
			body.put("paymentType", "INSTALLMENTS");
		}
		return body;
	}

	protected Map<String, Object> codeRule(String name, String code, List<UUID> courses, String valueType,
			Object value) {
		Map<String, Object> body = valueRule(name, "DISCOUNT_CODE", courses, "ANY", valueType, value);
		body.put("code", code);
		return body;
	}

	protected UUID createRule(String host, String adminToken, Map<String, Object> body) {
		MvcResult result = jsonCall(host, adminToken, post(ADMIN), body);
		assertThat(status(result)).as(rawContent(result)).isEqualTo(201);
		return UUID.fromString(json(result).path("data").path("id").asString());
	}

	protected UUID createRule(Fx fx, Map<String, Object> body) {
		return createRule(fx.host(), fx.adminToken(), body);
	}

	protected void setActive(Fx fx, UUID ruleId, boolean active) {
		MvcResult result = jsonCall(fx.host(), fx.adminToken(), patch(ADMIN + "/{id}/active", ruleId),
				Map.of("active", active));
		assertThat(status(result)).as(rawContent(result)).isEqualTo(200);
	}

	protected static Map<String, Object> month(UUID courseId, YearMonth month) {
		Map<String, Object> item = new LinkedHashMap<>();
		item.put("courseId", courseId);
		item.put("scope", "MONTHLY");
		item.put("year", month.getYear());
		item.put("month", month.getMonthValue());
		return item;
	}

	@SafeVarargs
	protected static Map<String, Object> cart(Map<String, Object>... items) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("items", List.of(items));
		return body;
	}

	protected static Map<String, Object> with(Map<String, Object> cart, String key, Object value) {
		Map<String, Object> copy = new LinkedHashMap<>(cart);
		copy.put(key, value);
		return copy;
	}

	protected MvcResult quote(String host, String token, Map<String, Object> body) {
		return jsonCall(host, token, post("/api/v1/checkout/quote"), body);
	}

	protected JsonNode quoteOk(Fx fx, Map<String, Object> body) {
		return quoteOk(fx.host(), fx.studentToken(), body);
	}

	protected JsonNode quoteOk(String host, String token, Map<String, Object> body) {
		MvcResult result = quote(host, token, body);
		assertThat(status(result)).as(rawContent(result)).isEqualTo(200);
		return json(result).path("data");
	}

	protected MvcResult order(String host, String token, Map<String, Object> body, String quoteToken) {
		Map<String, Object> request = new LinkedHashMap<>(body);
		request.put("quoteToken", quoteToken);
		return jsonCall(host, token, post("/api/v1/checkout/orders"), request);
	}

	/** Quote, then order with that quote's token; returns the order view. */
	protected JsonNode placeOrder(Fx fx, Map<String, Object> body) {
		MvcResult result = order(fx.host(), fx.studentToken(), body, quoteOk(fx, body).path("quoteToken").asString());
		assertThat(status(result)).as(rawContent(result)).isEqualTo(201);
		return json(result).path("data");
	}

	protected static UUID id(JsonNode node) {
		return UUID.fromString(node.path("id").asString());
	}

	protected UUID confirmByGateway(Fx fx, UUID orderId) {
		PaymentInitiationResponse payment = initiatePaymentOrFail(fx.host(), fx.studentToken(), orderId);
		assertThat(sendPaymentWebhook(payment.gatewayReference(), true).getStatusCode().value()).isEqualTo(200);
		return payment.paymentId();
	}

	protected int launch(Fx fx, UUID courseId, int monthOffset) {
		return status(call(fx.host(), fx.studentToken(), post("/api/v1/courses/{courseId}/lessons/{lessonId}/launch",
				courseId, fx.lessons().get(courseId)[monthOffset])));
	}

	protected static BigDecimal dec(JsonNode node, String field) {
		return node.path(field).decimalValue();
	}

	protected static JsonNode courseCharge(JsonNode quote, UUID courseId) {
		for (JsonNode course : quote.path("courses")) {
			if (course.path("courseId").asString().equals(courseId.toString())) {
				return course;
			}
		}
		throw new AssertionError("No course charge for " + courseId + " in " + quote);
	}

}
