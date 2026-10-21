package com.lms.klass;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.paymentmanagement.SlipTestSupport;
import com.lms.paymentmanagement.slip.web.dto.PaymentSlipResponse;
import com.lms.tenantmanagement.domain.Tenant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * KLASS-T6 acceptance gap tests: CM-T02 (student / non-owner teacher cannot
 * perform Klass admin mutations or approve slips), EP-P06 (another student's
 * or another tenant's order/slip is denied server-side) and EP-M04 (client
 * price / target tampering on composer and legacy orders is ignored or
 * rejected; the server re-quotes).
 */
class KlassRolesAndPaymentOwnershipAcceptanceIntegrationTest extends SlipTestSupport {

	private record Fx(Tenant tenant, String host, String adminToken, String financeToken, String teacherToken,
			String teacher2Token, String studentToken, String student2Token, TenantUser teacher, TenantUser teacher2,
			TenantUser student, UUID course, UUID moduleId, UUID octLesson) {

	}

	private Fx seed(String prefix) {
		Tenant tenant = seedActiveTenant(uniqueSubdomain(prefix));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		seedTenantUser(tenant.getId(), "finance@example.test", RAW_PASSWORD, Role.FINANCE_STAFF);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		TenantUser teacher2 = seedTenantUser(tenant.getId(), "teacher2@example.test", RAW_PASSWORD, Role.TEACHER);
		TenantUser student = seedActiveStudent(tenant.getId(), "student@example.test");
		seedActiveStudent(tenant.getId(), "student2@example.test");
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		UUID course = createMonthly(host, adminToken, teacher.getId(), "ICT " + prefix);
		UUID moduleId = createModuleOrFail(host, adminToken, course, "ICT Module", 1).id();
		UUID oct = createLessonOrFail(host, adminToken, course, moduleId, "October class", 1).id();
		jdbcTemplate.update("UPDATE course_lesson SET payment_type = 'COVERED_BY_CLASS_FEES', billing_year = 2026, "
				+ "billing_month = 10 WHERE id = ?", oct);
		Fx fx = new Fx(tenant, host, adminToken, loginAndGetToken(host, "finance@example.test"),
				loginAndGetToken(host, "teacher@example.test"), loginAndGetToken(host, "teacher2@example.test"),
				loginAndGetToken(host, "student@example.test"), loginAndGetToken(host, "student2@example.test"),
				teacher, teacher2, student, course, moduleId, oct);
		assertThat(status(call(host, fx.studentToken(), post("/api/v1/enrollments/my/courses/{c}", course))))
			.isEqualTo(200);
		assertThat(status(call(host, fx.student2Token(), post("/api/v1/enrollments/my/courses/{c}", course))))
			.isEqualTo(200);
		return fx;
	}

	private UUID createMonthly(String host, String token, UUID teacherId, String name) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("name", name);
		body.put("teacherIds", List.of(teacherId));
		body.put("pricingModel", "MONTHLY");
		body.put("price", "3500.00");
		body.put("status", "PUBLIC");
		MvcResult result = send(host, token, post("/api/v1/courses"), body);
		assertThat(status(result)).as(rawContent(result)).isEqualTo(201);
		return UUID.fromString(json(result).path("data").path("id").asString());
	}

	private MvcResult call(String host, String token, MockHttpServletRequestBuilder builder) {
		return perform(authenticated(builder, host, token));
	}

	private MvcResult send(String host, String token, MockHttpServletRequestBuilder builder, Object body) {
		builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
		return call(host, token, builder);
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

	private UUID composerOrder(Fx fx, String token) {
		Map<String, Object> body = Map.of("courseId", fx.course(), "items",
				List.of(Map.of("scope", "MONTHLY", "year", 2026, "month", 10)));
		String quoteToken = json(send(fx.host(), token, post("/api/v1/checkout/quote"), body)).path("data")
			.path("quoteToken")
			.asString();
		MvcResult result = send(fx.host(), token, post("/api/v1/checkout/orders"),
				Map.of("courseId", fx.course(), "items", List.of(Map.of("scope", "MONTHLY", "year", 2026, "month", 10)),
						"quoteToken", quoteToken));
		assertThat(status(result)).as(rawContent(result)).isEqualTo(201);
		return UUID.fromString(json(result).path("data").path("id").asString());
	}

	// ------------------------------------------------------------------
	// CM-T02: role checks.
	// ------------------------------------------------------------------

	@Test
	void studentAndNonOwnerTeacherCannotPerformAdminMutationsOrApproveSlips() {
		Fx fx = seed("klass-t02");
		UUID tenantId = fx.tenant().getId();
		UUID orderId = composerOrder(fx, fx.studentToken());
		PaymentSlipResponse slip = uploadSlipOrFail(fx.host(), fx.studentToken(), orderId, "REF-KLASS-T02",
				pdfFile("slip.pdf", distinctPdfBytes("klass-t02")));
		long courses = count("SELECT count(*) FROM course WHERE tenant_id = ?", tenantId);
		long modules = count("SELECT count(*) FROM course_module WHERE tenant_id = ?", tenantId);
		long lessons = count("SELECT count(*) FROM course_lesson WHERE tenant_id = ?", tenantId);

		Map<String, Object> newCourse = new LinkedHashMap<>();
		newCourse.put("name", "Hijack");
		newCourse.put("teacherIds", List.of(fx.teacher().getId()));
		newCourse.put("pricingModel", "FREE");
		Map<String, Object> newModule = Map.of("title", "Hijack module");
		Map<String, Object> newLesson = new HashMap<>(Map.of("title", "Hijack session", "paymentType", "FREE"));

		// Student: no course/module/session creation, registration toggle, edit, reorder or approval.
		String s = fx.studentToken();
		assertThat(status(send(fx.host(), s, post("/api/v1/courses"), newCourse))).isEqualTo(403);
		assertThat(status(send(fx.host(), s, post("/api/v1/courses/{c}/modules", fx.course()), newModule)))
			.isIn(403, 404);
		assertThat(status(send(fx.host(), s,
				post("/api/v1/courses/{c}/modules/{m}/lessons", fx.course(), fx.moduleId()), newLesson)))
			.isIn(403, 404);
		assertThat(status(send(fx.host(), s, patch("/api/v1/courses/{c}/registration", fx.course()),
				Map.of("enabled", false)))).isIn(403, 404);
		assertThat(status(send(fx.host(), s, patch("/api/v1/courses/{c}", fx.course()),
				Map.of("name", "Renamed by student")))).isIn(403, 404);
		assertThat(status(send(fx.host(), s, put("/api/v1/courses/order"),
				Map.of("courseIds", List.of(fx.course()))))).isIn(403, 404);
		assertThat(approveSlip(fx.host(), s, slip.id(), null).getStatusCode().value()).isEqualTo(403);
		assertThat(rejectSlip(fx.host(), s, slip.id(), "no").getStatusCode().value()).isEqualTo(403);

		// Teacher 2 does not own the course: no edit, module/session creation or registration toggle.
		String t2 = fx.teacher2Token();
		assertThat(status(send(fx.host(), t2, patch("/api/v1/courses/{c}", fx.course()),
				Map.of("name", "Renamed by other teacher")))).isIn(403, 404);
		assertThat(status(send(fx.host(), t2, post("/api/v1/courses/{c}/modules", fx.course()), newModule)))
			.isIn(403, 404);
		assertThat(status(send(fx.host(), t2,
				post("/api/v1/courses/{c}/modules/{m}/lessons", fx.course(), fx.moduleId()), newLesson)))
			.isIn(403, 404);
		assertThat(status(send(fx.host(), t2, patch("/api/v1/courses/{c}/registration", fx.course()),
				Map.of("enabled", false)))).isIn(403, 404);
		assertThat(status(send(fx.host(), t2, put("/api/v1/courses/{c}/modules/order", fx.course()),
				Map.of("moduleIds", List.of(fx.moduleId()))))).isIn(403, 404);
		// No teacher approves payment slips.
		assertThat(approveSlip(fx.host(), fx.teacherToken(), slip.id(), null).getStatusCode().value()).isEqualTo(403);
		assertThat(approveSlip(fx.host(), t2, slip.id(), null).getStatusCode().value()).isEqualTo(403);

		// Nothing changed.
		assertThat(count("SELECT count(*) FROM course WHERE tenant_id = ?", tenantId)).isEqualTo(courses);
		assertThat(count("SELECT count(*) FROM course_module WHERE tenant_id = ?", tenantId)).isEqualTo(modules);
		assertThat(count("SELECT count(*) FROM course_lesson WHERE tenant_id = ?", tenantId)).isEqualTo(lessons);
		assertThat(jdbcTemplate.queryForObject("SELECT registration_enabled FROM course WHERE id = ?", Boolean.class,
				fx.course())).isTrue();
		assertThat(jdbcTemplate.queryForObject("SELECT name FROM course WHERE id = ?", String.class, fx.course()))
			.startsWith("ICT ");
		assertThat(jdbcTemplate.queryForObject("SELECT status FROM payment_slip WHERE id = ?", String.class, slip.id()))
			.isNotEqualTo("APPROVED");
		assertThat(count("SELECT count(*) FROM content_entitlement WHERE tenant_id = ?", tenantId)).isZero();

		// Positive controls: the owning teacher may add a module; finance approves the slip.
		assertThat(status(send(fx.host(), fx.teacherToken(), post("/api/v1/courses/{c}/modules", fx.course()),
				Map.of("title", "Owner module")))).isEqualTo(201);
		assertThat(approveSlip(fx.host(), fx.financeToken(), slip.id(), null).getStatusCode().value()).isEqualTo(200);
		assertThat(count("SELECT count(*) FROM content_entitlement WHERE tenant_id = ?", tenantId)).isEqualTo(1);
	}

	// ------------------------------------------------------------------
	// EP-P06: order / slip ownership.
	// ------------------------------------------------------------------

	@Test
	@Tag("cross-tenant")
	void anotherStudentsOrAnotherTenantsOrderAndSlipAreDenied() {
		Fx fx = seed("klass-p06-a");
		Fx other = seed("klass-p06-b");
		UUID orderId = composerOrder(fx, fx.studentToken());
		PaymentSlipResponse slip = uploadSlipOrFail(fx.host(), fx.studentToken(), orderId, "REF-KLASS-P06",
				pdfFile("slip.pdf", distinctPdfBytes("klass-p06")));

		for (String[] caller : List.of(new String[] { fx.host(), fx.student2Token() },
				new String[] { other.host(), other.studentToken() })) {
			String host = caller[0];
			String token = caller[1];
			assertThat(getOrder(host, token, orderId).getStatusCode().value()).isIn(403, 404);
			assertThat(getOrderPaymentStatus(host, token, orderId).getStatusCode().value()).isIn(403, 404);
			assertThat(initiatePayment(host, token, orderId).getStatusCode().value()).isIn(403, 404);
			assertThat(uploadSlip(host, token, orderId, "REF-KLASS-P06-X",
					pdfFile("x.pdf", distinctPdfBytes("klass-p06-x" + host))).getStatusCode().value()).isIn(403, 404);
			assertThat(getSlip(host, token, slip.id()).getStatusCode().value()).isIn(403, 404);
			assertThat(getSlipDownloadUrl(host, token, slip.id()).getStatusCode().value()).isIn(403, 404);
		}
		// Tenant B's finance staff cannot review tenant A's slip.
		assertThat(approveSlip(other.host(), other.financeToken(), slip.id(), null).getStatusCode().value())
			.isIn(403, 404);
		assertThat(getReviewQueue(other.host(), other.financeToken(), null).getBody().data().content())
			.extracting(PaymentSlipResponse::id)
			.doesNotContain(slip.id());

		assertThat(count("SELECT count(*) FROM payment_slip WHERE order_id = ?", orderId)).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM payment WHERE order_id = ?", orderId))
			.as("no foreign payment initiation succeeded").isZero();
		assertThat(jdbcTemplate.queryForObject("SELECT status FROM payment_slip WHERE id = ?", String.class, slip.id()))
			.isNotEqualTo("APPROVED");
		// The owner still sees their own order.
		assertThat(getOrder(fx.host(), fx.studentToken(), orderId).getStatusCode().value()).isEqualTo(200);
	}

	// ------------------------------------------------------------------
	// EP-M04: tampered price / targets.
	// ------------------------------------------------------------------

	@Test
	void tamperedPricesAreIgnoredAndForeignTargetsAreRejected() {
		Fx fx = seed("klass-m04");
		UUID otherCourse = createMonthly(fx.host(), fx.adminToken(), fx.teacher().getId(), "Physics klass-m04");
		UUID otherModule = createModuleOrFail(fx.host(), fx.adminToken(), otherCourse, "Physics", 1).id();
		UUID otherLesson = createLessonOrFail(fx.host(), fx.adminToken(), otherCourse, otherModule, "Paper", 1).id();
		jdbcTemplate.update("UPDATE course_lesson SET payment_type = 'PAID', price = 900.00 WHERE id = ?", otherLesson);

		Map<String, Object> item = new LinkedHashMap<>();
		item.put("scope", "MONTHLY");
		item.put("year", 2026);
		item.put("month", 10);
		item.put("amount", "1.00");
		item.put("unitAmount", "1.00");
		item.put("price", "1.00");
		item.put("lineTotal", "1.00");
		item.put("currency", "EUR");
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("courseId", fx.course());
		body.put("items", List.of(item));
		body.put("amount", "1.00");
		body.put("total", "1.00");
		body.put("subtotal", "1.00");
		body.put("facilityFeeTotal", "0");
		body.put("currency", "EUR");
		body.put("studentId", UUID.randomUUID());
		body.put("tenantId", UUID.randomUUID());

		MvcResult quote = send(fx.host(), fx.studentToken(), post("/api/v1/checkout/quote"), body);
		assertThat(status(quote)).as(rawContent(quote)).isEqualTo(200);
		JsonNode q = json(quote).path("data");
		assertThat(q.path("total").decimalValue()).isEqualByComparingTo("3500.00");
		String currency = q.path("currency").asString();
		assertThat(currency).isNotEqualTo("EUR");

		body.put("quoteToken", q.path("quoteToken").asString());
		MvcResult order = send(fx.host(), fx.studentToken(), post("/api/v1/checkout/orders"), body);
		assertThat(status(order)).as(rawContent(order)).isEqualTo(201);
		JsonNode o = json(order).path("data");
		UUID orderId = UUID.fromString(o.path("id").asString());
		assertThat(o.path("amount").decimalValue()).isEqualByComparingTo("3500.00");
		assertThat(jdbcTemplate.queryForObject("SELECT amount FROM student_order WHERE id = ?",
				java.math.BigDecimal.class, orderId)).isEqualByComparingTo("3500.00");
		assertThat(jdbcTemplate.queryForObject("SELECT currency FROM student_order WHERE id = ?", String.class, orderId))
			.isEqualTo(currency);
		assertThat(jdbcTemplate.queryForObject("SELECT student_id FROM student_order WHERE id = ?", UUID.class, orderId))
			.isEqualTo(fx.student().getId());
		assertThat(jdbcTemplate.queryForObject("SELECT unit_amount FROM student_order_line WHERE order_id = ?",
				java.math.BigDecimal.class, orderId)).isEqualByComparingTo("3500.00");

		// Target tampering: another course's paid lesson / module on this course's order -> 422, nothing created.
		long before = count("SELECT count(*) FROM student_order WHERE tenant_id = ?", fx.tenant().getId());
		MvcResult foreignLesson = send(fx.host(), fx.studentToken(), post("/api/v1/checkout/orders"),
				Map.of("courseId", fx.course(), "items", List.of(Map.of("scope", "LESSON", "lessonId", otherLesson))));
		assertThat(status(foreignLesson)).isEqualTo(422);
		MvcResult foreignModule = send(fx.host(), fx.studentToken(), post("/api/v1/checkout/orders"),
				Map.of("courseId", fx.course(), "items", List.of(Map.of("scope", "MODULE", "moduleId", otherModule))));
		assertThat(status(foreignModule)).isEqualTo(422);
		// A course the student never joined -> 404.
		MvcResult notJoined = send(fx.host(), fx.studentToken(), post("/api/v1/checkout/orders"),
				Map.of("courseId", otherCourse, "items", List.of(Map.of("scope", "LESSON", "lessonId", otherLesson))));
		assertThat(status(notJoined)).isEqualTo(404);
		assertThat(count("SELECT count(*) FROM student_order WHERE tenant_id = ?", fx.tenant().getId()))
			.isEqualTo(before);

		// Legacy order endpoint: a student's customAmount is rejected outright.
		MvcResult legacy = send(fx.host(), fx.studentToken(), post("/api/v1/orders"),
				Map.of("courseId", otherCourse, "customAmount", "1.00"));
		// Rejected outright (pre-existing contract: 409 for a non-CUSTOM course, 400 for a student on CUSTOM).
		assertThat(status(legacy)).as(rawContent(legacy)).isIn(400, 409);
		assertThat(count("SELECT count(*) FROM student_order WHERE tenant_id = ? AND course_id = ?", fx.tenant().getId(),
				otherCourse)).isZero();
		// ... and a price/amount field on the legacy order is structurally ignored.
		MvcResult legacyPriced = send(fx.host(), fx.studentToken(), post("/api/v1/orders"),
				Map.of("courseId", otherCourse, "amount", "1.00", "price", "1.00"));
		if (status(legacyPriced) == 201) {
			assertThat(json(legacyPriced).path("data").path("amount").decimalValue()).isNotEqualByComparingTo("1.00");
		}
		else {
			assertThat(status(legacyPriced)).as(rawContent(legacyPriced)).isIn(400, 409, 422);
		}
	}

}
