package com.lms.paymentmanagement;

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
import com.lms.paymentmanagement.api.PaymentChargebackApi;
import com.lms.paymentmanagement.order.web.dto.PaymentInitiationResponse;
import com.lms.paymentmanagement.slip.web.dto.PaymentSlipResponse;
import com.lms.tenantmanagement.domain.Tenant;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * KLASS-T4c Testcontainers/MockMvc coverage (ADR-028, plan §4.4,
 * payments.md): payment composer offers / quote / multi-line orders, facility
 * fee math, server-side item validation, entitlement GRANT only on a verified
 * gateway confirmation or an authorized slip approval (idempotent, exactly the
 * lines, one ledger entry), rejection grants nothing, chargeback revocation,
 * expired re-purchase (D21), coverage scoping (month / course / sibling
 * lesson), cross-tenant 404s and the D22 launch 404.
 */
class CheckoutComposerEntitlementIntegrationTest extends SlipTestSupport {

	@Autowired
	private PaymentChargebackApi paymentChargebackApi;

	private record Fx(Tenant tenant, String host, String adminToken, String financeToken, String studentToken,
			TenantUser teacher, TenantUser student, UUID ict, UUID ictModule, UUID sep, UUID oct, UUID oct2,
			UUID paidLesson, UUID paidLesson2, UUID physics, UUID physicsModule, UUID physicsOct) {

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

		UUID ict = createMonthlyCourse(host, adminToken, teacher.getId(), prefix + "-ict");
		UUID ictModule = createModuleOrFail(host, adminToken, ict, "ICT Module", 1).id();
		UUID sep = lesson(host, adminToken, ict, ictModule, "September class", 1);
		UUID oct = lesson(host, adminToken, ict, ictModule, "October class 1", 2);
		UUID oct2 = lesson(host, adminToken, ict, ictModule, "October class 2", 3);
		UUID paid = lesson(host, adminToken, ict, ictModule, "Paper class", 4);
		UUID paid2 = lesson(host, adminToken, ict, ictModule, "Paper class 2", 5);
		covered(sep, 2026, 9);
		covered(oct, 2026, 10);
		covered(oct2, 2026, 10);
		paidLesson(paid, "750.00");
		paidLesson(paid2, "600.00");
		jdbcTemplate.update("UPDATE course_module SET price = 5000.00 WHERE id = ?", ictModule);

		UUID physics = createMonthlyCourse(host, adminToken, teacher.getId(), prefix + "-phy");
		UUID physicsModule = createModuleOrFail(host, adminToken, physics, "Physics Module", 1).id();
		UUID physicsOct = lesson(host, adminToken, physics, physicsModule, "Physics October", 1);
		covered(physicsOct, 2026, 10);

		Fx fx = new Fx(tenant, host, adminToken, financeToken, studentToken, teacher, student, ict, ictModule, sep,
				oct, oct2, paid, paid2, physics, physicsModule, physicsOct);
		assertThat(status(selfEnroll(fx, studentToken, ict))).isEqualTo(200);
		assertThat(status(selfEnroll(fx, studentToken, physics))).isEqualTo(200);
		return fx;
	}

	private UUID createMonthlyCourse(String host, String adminToken, UUID teacherId, String prefix) {
		String slug = uniqueSlug(prefix);
		CourseResponse course = createCourseOrFail(host, adminToken,
				new CourseCreateRequest("Course " + slug, slug, "Mathematics", null, null, null, null, null,
						new BigDecimal("3500.00"), null, null, CourseStatus.PUBLIC, teacherId, null,
						CoursePricingModel.MONTHLY, null, null, null, null, null, null, null));
		return course.id();
	}

	private UUID lesson(String host, String token, UUID courseId, UUID moduleId, String title, int sequence) {
		return createLessonOrFail(host, token, courseId, moduleId, title, sequence).id();
	}

	private void covered(UUID lessonId, int year, int month) {
		jdbcTemplate.update("UPDATE course_lesson SET payment_type = 'COVERED_BY_CLASS_FEES', billing_year = ?, "
				+ "billing_month = ? WHERE id = ?", year, month, lessonId);
	}

	private void paidLesson(UUID lessonId, String price) {
		jdbcTemplate.update("UPDATE course_lesson SET payment_type = 'PAID', price = ? WHERE id = ?",
				new BigDecimal(price), lessonId);
	}

	// ------------------------------------------------------------------
	// HTTP helpers.
	// ------------------------------------------------------------------

	private MvcResult call(String host, String token, MockHttpServletRequestBuilder builder) {
		return perform(authenticated(builder, host, token));
	}

	private MvcResult selfEnroll(Fx fx, String token, UUID courseId) {
		return call(fx.host(), token, post("/api/v1/enrollments/my/courses/{courseId}", courseId));
	}

	private MvcResult jsonPost(String host, String token, String path, Object body) {
		return call(host, token,
				post(path).contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
	}

	private MvcResult quote(Fx fx, UUID courseId, List<Map<String, Object>> items) {
		return quote(fx.host(), fx.studentToken(), courseId, items);
	}

	private MvcResult quote(String host, String token, UUID courseId, List<Map<String, Object>> items) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("courseId", courseId);
		body.put("items", items);
		return jsonPost(host, token, "/api/v1/checkout/quote", body);
	}

	private MvcResult order(Fx fx, UUID courseId, List<Map<String, Object>> items, UUID idempotencyKey) {
		return order(fx.host(), fx.studentToken(), courseId, items, idempotencyKey);
	}

	private MvcResult order(String host, String token, UUID courseId, List<Map<String, Object>> items,
			UUID idempotencyKey) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("courseId", courseId);
		body.put("items", items);
		// V90: an order echoes the token of the quote the student confirmed.
		MvcResult quote = jsonPost(host, token, "/api/v1/checkout/quote", body);
		if (quote.getResponse().getStatus() == 200) {
			body.put("quoteToken", json(quote).path("data").path("quoteToken").asString());
		}
		if (idempotencyKey != null) {
			body.put("idempotencyKey", idempotencyKey);
		}
		return jsonPost(host, token, "/api/v1/checkout/orders", body);
	}

	private UUID orderOrFail(Fx fx, UUID courseId, List<Map<String, Object>> items) {
		MvcResult result = order(fx, courseId, items, null);
		assertThat(status(result)).as(rawContent(result)).isEqualTo(201);
		return UUID.fromString(json(result).path("data").path("id").asString());
	}

	private MvcResult offers(Fx fx, String token, UUID courseId, String scope, Integer year) {
		String query = "/api/v1/checkout/offers?courseId=" + courseId + "&scope=" + scope
				+ (year == null ? "" : "&year=" + year);
		return call(fx.host(), token, get(query));
	}

	private static Map<String, Object> monthly(int year, int month) {
		return Map.of("scope", "MONTHLY", "year", year, "month", month);
	}

	private static Map<String, Object> module(UUID moduleId) {
		return Map.of("scope", "MODULE", "moduleId", moduleId);
	}

	private static Map<String, Object> lessonItem(UUID lessonId) {
		return Map.of("scope", "LESSON", "lessonId", lessonId);
	}

	private void setFee(Fx fx, String mode, Object amount) {
		Map<String, Object> changes = new LinkedHashMap<>();
		changes.put("facility_fee_mode", mode);
		changes.put("facility_fee_amount", amount);
		MvcResult result = call(fx.host(), fx.adminToken(), put("/api/v1/tenant-config/PAYMENT")
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(changes)));
		assertThat(status(result)).as(rawContent(result)).isEqualTo(200);
	}

	/** Online payment through the (test) fake gateway: initiate + verified webhook. */
	private PaymentInitiationResponse payOnline(Fx fx, UUID orderId) {
		PaymentInitiationResponse initiation = initiatePaymentOrFail(fx.host(), fx.studentToken(), orderId);
		assertThat(sendPaymentWebhook(initiation.gatewayReference(), true).getStatusCode().value()).isEqualTo(200);
		return initiation;
	}

	private int launch(Fx fx, String token, UUID courseId, UUID lessonId) {
		return status(call(fx.host(), token,
				post("/api/v1/courses/{courseId}/lessons/{lessonId}/launch", courseId, lessonId)));
	}

	private String launchCode(Fx fx, UUID courseId, UUID lessonId) {
		return json(call(fx.host(), fx.studentToken(),
				post("/api/v1/courses/{courseId}/lessons/{lessonId}/launch", courseId, lessonId))).path("code")
			.asString();
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

	private long entitlements(UUID tenantId) {
		return count("SELECT count(*) FROM content_entitlement WHERE tenant_id = ?", tenantId);
	}

	private long activeEntitlements(UUID tenantId, String coverageKey) {
		return count("SELECT count(*) FROM content_entitlement WHERE tenant_id = ? AND coverage_key = ? "
				+ "AND revoked_at IS NULL", tenantId, coverageKey);
	}

	private static String monthlyKey(UUID courseId, int year, int month) {
		return "MONTHLY:" + courseId + ":" + String.format("%04d-%02d", year, month);
	}

	private List<String> offerStates(MvcResult offers) {
		List<String> states = new ArrayList<>();
		json(offers).path("data").path("items").forEach(item -> states.add(item.path("state").asString()));
		return states;
	}

	// ------------------------------------------------------------------
	// Offers.
	// ------------------------------------------------------------------

	@Test
	void offersListMonthsModulesAndPaidLessonsAndHonestlyReportUnsupportedScopes() {
		Fx fx = seed("t4c-offers");

		MvcResult monthly = offers(fx, fx.studentToken(), fx.ict(), "MONTHLY", null);
		assertThat(status(monthly)).as(rawContent(monthly)).isEqualTo(200);
		JsonNode data = json(monthly).path("data");
		assertThat(data.path("supported").asBoolean()).isTrue();
		assertThat(data.path("years").get(0).asInt()).isEqualTo(2026);
		assertThat(data.path("items")).hasSize(2);
		assertThat(data.path("items").get(0).path("month").asInt()).isEqualTo(9);
		assertThat(data.path("items").get(1).path("label").asString()).isEqualTo("October 2026");
		assertThat(data.path("items").get(1).path("amount").decimalValue()).isEqualByComparingTo("3500.00");
		assertThat(offerStates(monthly)).containsOnly("AVAILABLE");
		assertThat(json(offers(fx, fx.studentToken(), fx.ict(), "MONTHLY", 2025)).path("data").path("items"))
			.isEmpty();

		JsonNode modules = json(offers(fx, fx.studentToken(), fx.ict(), "MODULE", null)).path("data");
		assertThat(modules.path("items")).hasSize(1);
		assertThat(modules.path("items").get(0).path("moduleId").asString()).isEqualTo(fx.ictModule().toString());
		assertThat(modules.path("items").get(0).path("amount").decimalValue()).isEqualByComparingTo("5000.00");

		JsonNode lessons = json(offers(fx, fx.studentToken(), fx.ict(), "LESSON", null)).path("data");
		assertThat(lessons.path("items")).hasSize(2);
		assertThat(lessons.path("items").get(0).path("lessonId").asString()).isEqualTo(fx.paidLesson().toString());

		JsonNode daily = json(offers(fx, fx.studentToken(), fx.ict(), "DAILY", null)).path("data");
		assertThat(daily.path("supported").asBoolean()).isFalse();
		assertThat(daily.path("items")).isEmpty();
		// COURSE offer exists only for ONE_TIME courses.
		assertThat(json(offers(fx, fx.studentToken(), fx.ict(), "COURSE", null)).path("data").path("items")).isEmpty();

		MvcResult courses = call(fx.host(), fx.studentToken(), get("/api/v1/checkout/courses?scope=MODULE"));
		assertThat(status(courses)).as(rawContent(courses)).isEqualTo(200);
		JsonNode courseList = json(courses).path("data");
		assertThat(courseList).hasSize(1);
		assertThat(courseList.get(0).path("courseId").asString()).isEqualTo(fx.ict().toString());
		assertThat(json(call(fx.host(), fx.studentToken(), get("/api/v1/checkout/courses?scope=MONTHLY")))
			.path("data")).hasSize(2);
	}

	// ------------------------------------------------------------------
	// Quote math.
	// ------------------------------------------------------------------

	@Test
	void quoteAppliesFacilityFeePerLinePerOrderOrNoneAndIgnoresClientPrices() {
		Fx fx = seed("t4c-quote");

		// Default NONE.
		JsonNode none = json(quote(fx, fx.ict(), List.of(monthly(2026, 9), monthly(2026, 10)))).path("data");
		assertThat(none.path("total").decimalValue()).isEqualByComparingTo("7000.00");
		assertThat(none.path("facilityFeeTotal").decimalValue()).isEqualByComparingTo("0");
		assertThat(none.path("feeNote").isNull()).isTrue();
		assertThat(none.path("methods").path("slip").asBoolean()).isTrue();
		assertThat(none.path("methods").path("online").asBoolean()).isTrue();
		assertThat(none.path("bankDetails").isNull()).isTrue();

		// The fixture: 3500 x 2, PER_LINE 100 -> 7200.00.
		setFee(fx, "PER_LINE", 100);
		Map<String, Object> withClientPrice = new LinkedHashMap<>(monthly(2026, 9));
		withClientPrice.put("amount", 1);
		withClientPrice.put("price", 1);
		withClientPrice.put("unitAmount", 1);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("courseId", fx.ict());
		body.put("items", List.of(withClientPrice, monthly(2026, 10), monthly(2026, 10)));
		body.put("total", 1);
		MvcResult perLine = jsonPost(fx.host(), fx.studentToken(), "/api/v1/checkout/quote", body);
		assertThat(status(perLine)).as(rawContent(perLine)).isEqualTo(200);
		JsonNode q = json(perLine).path("data");
		assertThat(q.path("lines")).hasSize(2);
		assertThat(q.path("lines").get(0).path("unitAmount").decimalValue()).isEqualByComparingTo("3500.00");
		assertThat(q.path("lines").get(0).path("facilityFee").decimalValue()).isEqualByComparingTo("100.00");
		assertThat(q.path("lines").get(1).path("lineTotal").decimalValue()).isEqualByComparingTo("3600.00");
		assertThat(q.path("subtotal").decimalValue()).isEqualByComparingTo("7000.00");
		assertThat(q.path("facilityFeeTotal").decimalValue()).isEqualByComparingTo("200.00");
		assertThat(q.path("discountTotal").decimalValue()).isEqualByComparingTo("0");
		assertThat(q.path("total").decimalValue()).isEqualByComparingTo("7200.00");
		assertThat(rawContent(perLine)).contains("\"total\":7200.00").contains("\"facilityFeeTotal\":200.00");
		assertThat(q.path("feeNote").asString()).isEqualTo("Includes a Facility Fee set by the institute.");

		setFee(fx, "PER_ORDER", 250.5);
		JsonNode perOrder = json(quote(fx, fx.ict(), List.of(monthly(2026, 9), monthly(2026, 10)))).path("data");
		assertThat(perOrder.path("lines").get(0).path("facilityFee").decimalValue()).isEqualByComparingTo("250.50");
		assertThat(perOrder.path("lines").get(1).path("facilityFee").decimalValue()).isEqualByComparingTo("0");
		assertThat(perOrder.path("total").decimalValue()).isEqualByComparingTo("7250.50");

		// An order snapshots the policy and its totals satisfy the V87 CHECKs.
		setFee(fx, "PER_LINE", 100);
		MvcResult created = order(fx, fx.ict(), List.of(monthly(2026, 9), monthly(2026, 10)), null);
		assertThat(status(created)).as(rawContent(created)).isEqualTo(201);
		JsonNode orderData = json(created).path("data");
		assertThat(orderData.path("amount").decimalValue()).isEqualByComparingTo("7200.00");
		assertThat(orderData.path("subtotal").decimalValue()).isEqualByComparingTo("7000.00");
		assertThat(orderData.path("facilityFeeTotal").decimalValue()).isEqualByComparingTo("200.00");
		assertThat(orderData.path("lines")).hasSize(2);
		UUID orderId = UUID.fromString(orderData.path("id").asString());
		assertThat(jdbcTemplate.queryForObject("SELECT fee_policy_snapshot FROM student_order WHERE id = ?",
				String.class, orderId)).isEqualTo("PER_LINE:100.00");
		assertThat(count("SELECT count(*) FROM student_order_line WHERE order_id = ?", orderId)).isEqualTo(2);
		// An order (and a quote) never grants anything.
		assertThat(entitlements(fx.tenant().getId())).isZero();
	}

	@Test
	void quoteRejectsForeignUnknownEmptyAndUnsupportedItemsWith422() {
		Fx fx = seed("t4c-invalid");

		assertThat(errorCode(quote(fx, fx.ict(), List.of()))).isEqualTo("EMPTY_SELECTION");
		// Physics module/lesson ids on an ICT quote are foreign.
		MvcResult foreignModule = quote(fx, fx.ict(), List.of(module(fx.physicsModule())));
		assertThat(status(foreignModule)).isEqualTo(422);
		assertThat(errorCode(foreignModule)).isEqualTo("ITEM_NOT_ELIGIBLE");
		assertThat(errorCode(quote(fx, fx.ict(), List.of(lessonItem(fx.physicsOct()))))).isEqualTo("ITEM_NOT_ELIGIBLE");
		assertThat(errorCode(quote(fx, fx.ict(), List.of(lessonItem(UUID.randomUUID()))))).isEqualTo("ITEM_NOT_ELIGIBLE");
		// A COVERED lesson is not sold as a LESSON; a month with no lessons is not sold.
		assertThat(errorCode(quote(fx, fx.ict(), List.of(lessonItem(fx.oct()))))).isEqualTo("ITEM_NOT_ELIGIBLE");
		assertThat(errorCode(quote(fx, fx.ict(), List.of(monthly(2026, 11))))).isEqualTo("ITEM_NOT_ELIGIBLE");
		assertThat(errorCode(quote(fx, fx.ict(), List.of(Map.of("scope", "COURSE"))))).isEqualTo("ITEM_NOT_ELIGIBLE");
		assertThat(errorCode(quote(fx, fx.ict(), List.of(Map.of("scope", "DAILY"))))).isEqualTo("ITEM_NOT_ELIGIBLE");
		assertThat(status(order(fx, fx.ict(), List.of(module(fx.physicsModule())), null))).isEqualTo(422);
		assertThat(count("SELECT count(*) FROM student_order WHERE tenant_id = ?", fx.tenant().getId())).isZero();
	}

	@Test
	void nonParticipantAndCrossTenantCallersGet404AndStaffAre403() {
		Fx a = seed("t4c-xt-a");
		Fx b = seed("t4c-xt-b");

		// Tenant B's student addressing tenant A's course.
		assertThat(status(quote(b.host(), b.studentToken(), a.ict(), List.of(monthly(2026, 10))))).isEqualTo(404);
		assertThat(status(order(b.host(), b.studentToken(), a.ict(), List.of(monthly(2026, 10)), null)))
			.isEqualTo(404);
		assertThat(status(offers(b, b.studentToken(), a.ict(), "MONTHLY", null))).isEqualTo(404);

		// A same-tenant student who never enrolled.
		seedActiveStudent(a.tenant().getId(), "outsider@example.test");
		String outsider = loginAndGetToken(a.host(), "outsider@example.test");
		assertThat(status(quote(a.host(), outsider, a.ict(), List.of(monthly(2026, 10))))).isEqualTo(404);
		assertThat(status(offers(a, outsider, a.ict(), "MONTHLY", null))).isEqualTo(404);
		assertThat(json(call(a.host(), outsider, get("/api/v1/checkout/courses?scope=MONTHLY"))).path("data"))
			.isEmpty();

		// Staff never compose student orders.
		assertThat(status(quote(a.host(), a.adminToken(), a.ict(), List.of(monthly(2026, 10))))).isEqualTo(403);
		assertThat(count("SELECT count(*) FROM student_order WHERE tenant_id IN (?, ?)", a.tenant().getId(),
				b.tenant().getId())).isZero();
	}

	// ------------------------------------------------------------------
	// Order creation: idempotency and concurrency.
	// ------------------------------------------------------------------

	@Test
	void sameIdempotencyKeyReplaysAndConcurrentSubmitsCreateOneOrder() throws Exception {
		Fx fx = seed("t4c-idem");
		UUID key = UUID.randomUUID();
		int threads = 5;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		List<String> ids = new ArrayList<>();
		try {
			List<Callable<MvcResult>> calls = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				calls.add(() -> order(fx, fx.ict(), List.of(monthly(2026, 10)), key));
			}
			int created = 0;
			for (Future<MvcResult> future : pool.invokeAll(calls)) {
				MvcResult result = future.get();
				assertThat(status(result)).as(rawContent(result)).isIn(200, 201);
				if (status(result) == 201) {
					created++;
				}
				ids.add(json(result).path("data").path("id").asString());
			}
			assertThat(created).isEqualTo(1);
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(ids).containsOnly(ids.get(0));
		assertThat(count("SELECT count(*) FROM student_order WHERE tenant_id = ?", fx.tenant().getId())).isEqualTo(1);

		// Same key for another course is a conflict, never a silent replay.
		assertThat(status(order(fx, fx.physics(), List.of(monthly(2026, 10)), key))).isEqualTo(409);
	}

	@Test
	void pendingItemCannotBeOrderedOrPaidAgainAndTheOnlyRaceBecomesAManualRefundFlag() {
		Fx fx = seed("t4c-pending");
		UUID first = orderOrFail(fx, fx.ict(), List.of(monthly(2026, 10)));
		UUID second = orderOrFail(fx, fx.ict(), List.of(monthly(2026, 10)));

		PaymentInitiationResponse firstPayment = initiatePaymentOrFail(fx.host(), fx.studentToken(), first);
		// Checkout launch alone grants nothing: the item is only PENDING.
		assertThat(entitlements(fx.tenant().getId())).isZero();
		assertThat(launchCode(fx, fx.ict(), fx.oct())).isEqualTo("PAYMENT_PENDING");
		assertThat(offerStates(offers(fx, fx.studentToken(), fx.ict(), "MONTHLY", null))).containsExactly("AVAILABLE",
				"PENDING");
		MvcResult again = order(fx, fx.ict(), List.of(monthly(2026, 10)), null);
		assertThat(status(again)).isEqualTo(422);
		assertThat(errorCode(again)).isEqualTo("ITEM_PENDING");
		var blocked = initiatePayment(fx.host(), fx.studentToken(), second);
		assertThat(blocked.getStatusCode().value()).isEqualTo(409);
		assertThat(uploadSlip(fx.host(), fx.studentToken(), second, "REF-T4C-BLOCKED", pdfFile("b.pdf",
				distinctPdfBytes("blocked"))).getStatusCode().value()).isEqualTo(409);

		// Simulate the one unguardable race (both payments already PENDING).
		UUID racePayment = UUID.randomUUID();
		String raceReference = "t4c-race-" + racePayment;
		jdbcTemplate.update("INSERT INTO payment (id, tenant_id, order_id, amount, currency, status, gateway_reference, "
				+ "created_at, updated_at) SELECT ?, tenant_id, id, amount, currency, 'PENDING', ?, now(), now() "
				+ "FROM student_order WHERE id = ?", racePayment, raceReference, second);

		assertThat(sendPaymentWebhook(firstPayment.gatewayReference(), true).getStatusCode().value()).isEqualTo(200);
		assertThat(sendPaymentWebhook(raceReference, true).getStatusCode().value()).isEqualTo(200);

		// Both payments stay CONFIRMED with their ledger entries; only one grant exists.
		assertThat(count("SELECT count(*) FROM payment WHERE tenant_id = ? AND status = 'CONFIRMED'",
				fx.tenant().getId())).isEqualTo(2);
		assertThat(count("SELECT count(*) FROM ledger_entry WHERE tenant_id = ? AND entry_type = 'PAYMENT_CONFIRMED'",
				fx.tenant().getId())).isEqualTo(2);
		assertThat(activeEntitlements(fx.tenant().getId(), monthlyKey(fx.ict(), 2026, 10))).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM content_entitlement WHERE source_payment_id = ?", racePayment)).isZero();
	}

	// ------------------------------------------------------------------
	// Gateway confirmation grants.
	// ------------------------------------------------------------------

	@Test
	void gatewayConfirmationGrantsExactlyTheLinesOnceAndOnlyThoseItems() {
		Fx fx = seed("t4c-gateway");
		UUID tenantId = fx.tenant().getId();
		UUID orderId = orderOrFail(fx, fx.ict(), List.of(monthly(2026, 10), lessonItem(fx.paidLesson())));
		assertThat(launchCode(fx, fx.ict(), fx.oct())).isEqualTo("PAYMENT_REQUIRED");

		PaymentInitiationResponse payment = payOnline(fx, orderId);
		// Webhook replay: no second grant, no second ledger entry.
		assertThat(sendPaymentWebhook(payment.gatewayReference(), true).getStatusCode().value()).isEqualTo(200);

		assertThat(count("SELECT count(*) FROM content_entitlement WHERE order_id = ?", orderId)).isEqualTo(2);
		assertThat(count("SELECT count(*) FROM content_entitlement WHERE order_id = ? AND source_payment_id = ? "
				+ "AND source_slip_id IS NULL", orderId, payment.paymentId())).isEqualTo(2);
		assertThat(count("SELECT count(*) FROM ledger_entry WHERE payment_id = ?", payment.paymentId())).isEqualTo(1);
		BigDecimal ledgerAmount = jdbcTemplate.queryForObject(
				"SELECT amount FROM ledger_entry WHERE payment_id = ?", BigDecimal.class, payment.paymentId());
		assertThat(ledgerAmount).isEqualByComparingTo("4250.00");
		// No legacy course-wide enrollment for a line order; participation unchanged.
		assertThat(count("SELECT count(*) FROM enrollment WHERE tenant_id = ?", tenantId)).isZero();
		assertThat(count("SELECT count(*) FROM course_participation WHERE tenant_id = ? AND student_id = ?", tenantId,
				fx.student().getId())).isEqualTo(2);

		// ICT October + the one paid session are unlocked ...
		assertThat(launch(fx, fx.studentToken(), fx.ict(), fx.oct())).isEqualTo(200);
		assertThat(launch(fx, fx.studentToken(), fx.ict(), fx.oct2())).isEqualTo(200);
		assertThat(launch(fx, fx.studentToken(), fx.ict(), fx.paidLesson())).isEqualTo(200);
		// ... but not ICT September, the sibling paid session, or Physics October.
		assertThat(launchCode(fx, fx.ict(), fx.sep())).isEqualTo("PAYMENT_REQUIRED");
		assertThat(launchCode(fx, fx.ict(), fx.paidLesson2())).isEqualTo("PAYMENT_REQUIRED");
		assertThat(launchCode(fx, fx.physics(), fx.physicsOct())).isEqualTo("PAYMENT_REQUIRED");

		// Offers now show the bought items as PAID and refuse them in quotes.
		assertThat(offerStates(offers(fx, fx.studentToken(), fx.ict(), "MONTHLY", null))).containsExactly("AVAILABLE",
				"PAID");
		assertThat(errorCode(quote(fx, fx.ict(), List.of(monthly(2026, 10))))).isEqualTo("ITEM_ALREADY_PAID");
	}

	@Test
	void rejectedGatewayPaymentGrantsNothingAndKeepsParticipation() {
		Fx fx = seed("t4c-reject");
		UUID orderId = orderOrFail(fx, fx.ict(), List.of(monthly(2026, 10)));
		PaymentInitiationResponse initiation = initiatePaymentOrFail(fx.host(), fx.studentToken(), orderId);
		assertThat(sendPaymentWebhook(initiation.gatewayReference(), false).getStatusCode().value()).isEqualTo(200);

		assertThat(entitlements(fx.tenant().getId())).isZero();
		assertThat(count("SELECT count(*) FROM course_participation WHERE tenant_id = ? AND course_id = ?",
				fx.tenant().getId(), fx.ict())).isEqualTo(1);
		assertThat(launchCode(fx, fx.ict(), fx.oct())).isEqualTo("PAYMENT_REQUIRED");
		assertThat(offerStates(offers(fx, fx.studentToken(), fx.ict(), "MONTHLY", null))).containsOnly("AVAILABLE");
	}

	// ------------------------------------------------------------------
	// Slip approval grants.
	// ------------------------------------------------------------------

	@Test
	void slipUploadIsPendingApprovalGrantsOnceAndReviewerSeesOrderLines() {
		Fx fx = seed("t4c-slip");
		setFee(fx, "PER_LINE", 100);
		UUID orderId = orderOrFail(fx, fx.ict(), List.of(monthly(2026, 9), monthly(2026, 10)));

		PaymentSlipResponse slip = uploadSlipOrFail(fx.host(), fx.studentToken(), orderId, "REF-T4C-SLIP-1",
				pdfFile("slip.pdf", distinctPdfBytes("t4c-slip-1")));
		assertThat(slip.orderLines()).hasSize(2);
		assertThat(slip.orderAmount()).isEqualByComparingTo("7200.00");
		assertThat(launchCode(fx, fx.ict(), fx.oct())).isEqualTo("PAYMENT_PENDING");
		assertThat(entitlements(fx.tenant().getId())).isZero();

		var queue = getReviewQueue(fx.host(), fx.financeToken(), null);
		PaymentSlipResponse queued = queue.getBody().data().content().get(0);
		assertThat(queued.orderLines()).extracting(line -> line.label()).containsExactly("September 2026",
				"October 2026");
		assertThat(queued.orderFacilityFee()).isEqualByComparingTo("200.00");
		assertThat(queued.orderSubtotal()).isEqualByComparingTo("7000.00");

		assertThat(approveSlip(fx.host(), fx.financeToken(), slip.id(), null).getStatusCode().value()).isEqualTo(200);
		assertThat(approveSlip(fx.host(), fx.financeToken(), slip.id(), null).getStatusCode().value()).isEqualTo(200);

		assertThat(count("SELECT count(*) FROM content_entitlement WHERE order_id = ?", orderId)).isEqualTo(2);
		assertThat(count("SELECT count(*) FROM payment WHERE order_id = ? AND status = 'CONFIRMED'", orderId))
			.isEqualTo(1);
		UUID slipPayment = jdbcTemplate.queryForObject("SELECT id FROM payment WHERE order_id = ?", UUID.class,
				orderId);
		assertThat(count("SELECT count(*) FROM content_entitlement WHERE order_id = ? AND source_payment_id = ?",
				orderId, slipPayment)).isEqualTo(2);
		assertThat(count("SELECT count(*) FROM ledger_entry WHERE payment_id = ?", slipPayment)).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'content_entitlement.granted'",
				fx.tenant().getId())).isEqualTo(2);
		assertThat(count("SELECT count(*) FROM enrollment WHERE tenant_id = ?", fx.tenant().getId())).isZero();
		assertThat(launch(fx, fx.studentToken(), fx.ict(), fx.sep())).isEqualTo(200);
		assertThat(launch(fx, fx.studentToken(), fx.ict(), fx.oct())).isEqualTo(200);
	}

	@Test
	void rejectedSlipGrantsNothingAndTheItemBecomesAvailableAgain() {
		Fx fx = seed("t4c-slip-reject");
		UUID orderId = orderOrFail(fx, fx.ict(), List.of(module(fx.ictModule())));
		PaymentSlipResponse slip = uploadSlipOrFail(fx.host(), fx.studentToken(), orderId, "REF-T4C-REJ-1",
				pdfFile("slip.pdf", distinctPdfBytes("t4c-rej-1")));
		assertThat(offerStates(offers(fx, fx.studentToken(), fx.ict(), "MODULE", null))).containsExactly("PENDING");

		assertThat(rejectSlip(fx.host(), fx.financeToken(), slip.id(), "Amount does not match").getStatusCode()
			.value()).isEqualTo(200);

		assertThat(entitlements(fx.tenant().getId())).isZero();
		assertThat(count("SELECT count(*) FROM course_participation WHERE tenant_id = ? AND course_id = ?",
				fx.tenant().getId(), fx.ict())).isEqualTo(1);
		assertThat(offerStates(offers(fx, fx.studentToken(), fx.ict(), "MODULE", null))).containsExactly("AVAILABLE");
	}

	// ------------------------------------------------------------------
	// Module coverage, chargeback, expiry.
	// ------------------------------------------------------------------

	@Test
	void chargebackRevokesOnlyThatPaymentsEntitlementsAndKeepsParticipation() {
		Fx fx = seed("t4c-chargeback");
		UUID tenantId = fx.tenant().getId();
		UUID octOrder = orderOrFail(fx, fx.ict(), List.of(monthly(2026, 10)));
		PaymentInitiationResponse octPayment = payOnline(fx, octOrder);
		UUID moduleOrder = orderOrFail(fx, fx.ict(), List.of(module(fx.ictModule())));
		payOnline(fx, moduleOrder);
		// MODULE covers every COVERED lesson of the module, any month (D22) - not PAID ones.
		assertThat(launch(fx, fx.studentToken(), fx.ict(), fx.sep())).isEqualTo(200);
		assertThat(launchCode(fx, fx.ict(), fx.paidLesson())).isEqualTo("PAYMENT_REQUIRED");

		paymentChargebackApi.recordChargeback(octPayment.gatewayReference(), "T4C-CB-1");
		paymentChargebackApi.recordChargeback(octPayment.gatewayReference(), "T4C-CB-1");

		String octKey = monthlyKey(fx.ict(), 2026, 10);
		assertThat(activeEntitlements(tenantId, octKey)).isZero();
		assertThat(count("SELECT count(*) FROM content_entitlement WHERE coverage_key = ? AND revoked_reason = "
				+ "'CHARGEBACK' AND revoked_by_refund_id IS NOT NULL", octKey)).isEqualTo(1);
		assertThat(activeEntitlements(tenantId, "MODULE:" + fx.ictModule())).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM payment WHERE id = ? AND status = 'CONFIRMED'", octPayment.paymentId()))
			.isEqualTo(1);
		assertThat(count("SELECT count(*) FROM ledger_entry WHERE payment_id = ? AND entry_type = 'REFUND'",
				octPayment.paymentId())).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM course_participation WHERE tenant_id = ? AND course_id = ?", tenantId,
				fx.ict())).isEqualTo(1);
		// Still covered by the module purchase.
		assertThat(launch(fx, fx.studentToken(), fx.ict(), fx.oct())).isEqualTo(200);
	}

	@Test
	void expiredEntitlementCanBeRepurchasedAndTheOldRowIsSupersededNotDeleted() {
		Fx fx = seed("t4c-expired");
		UUID tenantId = fx.tenant().getId();
		jdbcTemplate.update("UPDATE course SET access_duration_days = 30 WHERE id = ?", fx.ict());
		UUID firstOrder = orderOrFail(fx, fx.ict(), List.of(lessonItem(fx.paidLesson())));
		payOnline(fx, firstOrder);
		Long days = jdbcTemplate.queryForObject("SELECT extract(day FROM valid_until - granted_at)::bigint "
				+ "FROM content_entitlement WHERE order_id = ?", Long.class, firstOrder);
		assertThat(days).isEqualTo(30L);

		jdbcTemplate.update("UPDATE content_entitlement SET granted_at = now() - interval '40 days', "
				+ "valid_until = now() - interval '10 days' WHERE order_id = ?", firstOrder);
		assertThat(launchCode(fx, fx.ict(), fx.paidLesson())).isEqualTo("PAYMENT_REQUIRED");
		assertThat(offerStates(offers(fx, fx.studentToken(), fx.ict(), "LESSON", null))).containsOnly("AVAILABLE");

		UUID secondOrder = orderOrFail(fx, fx.ict(), List.of(lessonItem(fx.paidLesson())));
		payOnline(fx, secondOrder);

		String key = "LESSON:" + fx.paidLesson();
		assertThat(count("SELECT count(*) FROM content_entitlement WHERE coverage_key = ?", key)).isEqualTo(2);
		assertThat(count("SELECT count(*) FROM content_entitlement WHERE order_id = ? AND revoked_reason = "
				+ "'EXPIRED_SUPERSEDED'", firstOrder)).isEqualTo(1);
		assertThat(activeEntitlements(tenantId, key)).isEqualTo(1);
		assertThat(launch(fx, fx.studentToken(), fx.ict(), fx.paidLesson())).isEqualTo(200);
	}

	// ------------------------------------------------------------------
	// D22 + config validation.
	// ------------------------------------------------------------------

	@Test
	void launchOfANonPublicCourseByANonParticipantIs404AndPublicIs403() {
		Fx fx = seed("t4c-d22");
		String slug = uniqueSlug("t4c-private");
		CourseResponse hidden = createCourseOrFail(fx.host(), fx.adminToken(),
				newCourseRequest(slug, fx.teacher().getId(), CourseStatus.PRIVATE));
		UUID hiddenModule = createModuleOrFail(fx.host(), fx.adminToken(), hidden.id(), "Hidden", 1).id();
		UUID hiddenLesson = lesson(fx.host(), fx.adminToken(), hidden.id(), hiddenModule, "Hidden lesson", 1);
		assertThat(launch(fx, fx.studentToken(), hidden.id(), hiddenLesson)).isEqualTo(404);

		seedActiveStudent(fx.tenant().getId(), "outsider@example.test");
		String outsider = loginAndGetToken(fx.host(), "outsider@example.test");
		MvcResult publicLaunch = call(fx.host(), outsider,
				post("/api/v1/courses/{courseId}/lessons/{lessonId}/launch", fx.ict(), fx.oct()));
		assertThat(status(publicLaunch)).isEqualTo(403);
		assertThat(json(publicLaunch).path("code").asString()).isEqualTo("NOT_ENROLLED");
	}

	@Test
	void paymentConfigRejectsInvalidFeeValuesAndExposesBankDetailsInQuotes() {
		Fx fx = seed("t4c-config");
		MvcResult badMode = call(fx.host(), fx.adminToken(), put("/api/v1/tenant-config/PAYMENT")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"facility_fee_mode\":\"PER_CLICK\"}"));
		assertThat(status(badMode)).isEqualTo(400);
		MvcResult negative = call(fx.host(), fx.adminToken(), put("/api/v1/tenant-config/PAYMENT")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"facility_fee_amount\":-1}"));
		assertThat(status(negative)).isEqualTo(400);
		MvcResult bank = call(fx.host(), fx.adminToken(), put("/api/v1/tenant-config/PAYMENT")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"bank_name\":\"Test Bank\",\"bank_account_number\":\"000-TEST-ONLY\"}"));
		assertThat(status(bank)).as(rawContent(bank)).isEqualTo(200);

		JsonNode details = json(quote(fx, fx.ict(), List.of(monthly(2026, 10)))).path("data").path("bankDetails");
		assertThat(details.path("bankName").asString()).isEqualTo("Test Bank");
		assertThat(details.path("accountNumber").asString()).isEqualTo("000-TEST-ONLY");
		assertThat(details.path("branch").isNull()).isTrue();
	}

}
