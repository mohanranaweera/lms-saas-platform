package com.lms.paymentmanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.lms.coursemanagement.course.domain.CoursePricingModel;
import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.web.dto.CourseCreateRequest;
import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.paymentmanagement.order.web.dto.OrderResponse;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * KLASS-T8b (D23, D24 - payment review H-1 / M-1), Testcontainers/MockMvc.
 *
 * <ul>
 * <li>A line order gets at most one live piece of payment evidence: a
 * PENDING gateway payment refuses a slip and vice versa (409 {@code
 * ORDER_PAYMENT_IN_PROGRESS}); a CONFIRMED order refuses any new attempt.</li>
 * <li>The existing retry path still works: replaying the SAME idempotency key
 * returns the same PENDING payment.</li>
 * <li>Two orders for the same item starting payment concurrently: exactly one
 * PENDING payment, the other start refused (re-check under the shared
 * coverage locks inside the inserting transaction).</li>
 * <li>Legacy {@code POST /api/v1/orders} refuses a MONTHLY course with 409
 * {@code USE_PAYMENT_COMPOSER}.</li>
 * </ul>
 */
class LineOrderPaymentStartGuardIntegrationTest extends SlipTestSupport {

	private record Fx(Tenant tenant, String host, String adminToken, String financeToken, String studentToken,
			TenantUser student, UUID course) {

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

		String slug = uniqueSlug(prefix);
		CourseResponse course = createCourseOrFail(host, adminToken,
				new CourseCreateRequest("Course " + slug, slug, "Mathematics", null, null, null, null, null,
						new BigDecimal("3500.00"), null, null, CourseStatus.PUBLIC, teacher.getId(), null,
						CoursePricingModel.MONTHLY, null, null, null, null, null, null, null));
		UUID moduleId = createModuleOrFail(host, adminToken, course.id(), "Module", 1).id();
		UUID october = createLessonOrFail(host, adminToken, course.id(), moduleId, "October class", 1).id();
		jdbcTemplate.update("UPDATE course_lesson SET payment_type = 'COVERED_BY_CLASS_FEES', billing_year = 2026, "
				+ "billing_month = 10 WHERE id = ?", october);

		Fx fx = new Fx(tenant, host, adminToken, financeToken, studentToken, student, course.id());
		MvcResult enrolled = perform(authenticated(post("/api/v1/enrollments/my/courses/{courseId}", course.id()),
				host, studentToken));
		assertThat(enrolled.getResponse().getStatus()).isEqualTo(200);
		return fx;
	}

	private UUID octoberOrder(Fx fx) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("courseId", fx.course());
		body.put("items", List.of(Map.of("scope", "MONTHLY", "year", 2026, "month", 10)));
		MvcResult quote = perform(authenticated(post("/api/v1/checkout/quote").contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(body)), fx.host(), fx.studentToken()));
		body.put("quoteToken",
				objectMapper.readTree(rawContent(quote)).path("data").path("quoteToken").asString());
		MvcResult result = perform(authenticated(post("/api/v1/checkout/orders").contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(body)), fx.host(), fx.studentToken()));
		assertThat(result.getResponse().getStatus()).as(rawContent(result)).isEqualTo(201);
		return UUID.fromString(objectMapper.readTree(rawContent(result)).path("data").path("id").asString());
	}

	private HttpResult<PaymentSlipResponse> slip(Fx fx, UUID orderId, String seed) {
		return uploadSlip(fx.host(), fx.studentToken(), orderId, "REF-" + seed,
				pdfFile(seed + ".pdf", distinctPdfBytes(seed)));
	}

	private long count(String sql, Object... args) {
		Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
		return value == null ? 0 : value;
	}

	private static String code(HttpResult<?> result) {
		return result.getBody() == null || result.getBody().error() == null ? null : result.getBody().error().code();
	}

	@Test
	void pendingGatewayPaymentRefusesASlipAndANewAttemptButTheSameKeyReplays() {
		Fx fx = seed("t8b-gw-slip");
		UUID orderId = octoberOrder(fx);
		UUID key = UUID.randomUUID();

		HttpResult<PaymentInitiationResponse> first = initiatePayment(fx.host(), fx.studentToken(), orderId, key);
		assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		UUID paymentId = first.getBody().data().paymentId();

		// Retry/continue the SAME pending payment (same idempotency key): replayed, not refused.
		HttpResult<PaymentInitiationResponse> replay = initiatePayment(fx.host(), fx.studentToken(), orderId, key);
		assertThat(replay.getStatusCode().is2xxSuccessful()).as(String.valueOf(replay.getBody())).isTrue();
		assertThat(replay.getBody().data().paymentId()).isEqualTo(paymentId);

		// A slip for the same order while the gateway payment is PENDING: refused.
		HttpResult<PaymentSlipResponse> blockedSlip = slip(fx, orderId, "t8b-gw-slip-1");
		assertThat(blockedSlip.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(code(blockedSlip)).isEqualTo("ORDER_PAYMENT_IN_PROGRESS");

		// A second, different gateway attempt for the same order: refused.
		HttpResult<PaymentInitiationResponse> second = initiatePayment(fx.host(), fx.studentToken(), orderId,
				UUID.randomUUID());
		assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(code(second)).isEqualTo("ORDER_PAYMENT_IN_PROGRESS");

		assertThat(count("SELECT count(*) FROM payment WHERE order_id = ?", orderId)).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM payment_slip WHERE order_id = ?", orderId)).isZero();
	}

	@Test
	void openSlipRefusesAGatewayPaymentForTheSameOrder() {
		Fx fx = seed("t8b-slip-gw");
		UUID orderId = octoberOrder(fx);
		assertThat(slip(fx, orderId, "t8b-slip-gw-1").getStatusCode()).isEqualTo(HttpStatus.CREATED);

		HttpResult<PaymentInitiationResponse> blocked = initiatePayment(fx.host(), fx.studentToken(), orderId,
				UUID.randomUUID());
		assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(code(blocked)).isEqualTo("ORDER_PAYMENT_IN_PROGRESS");
		assertThat(count("SELECT count(*) FROM payment WHERE order_id = ?", orderId)).isZero();
	}

	@Test
	void confirmedOrderRefusesAnyNewAttemptAndRejectedEvidenceDoesNotBlockARetry() {
		Fx fx = seed("t8b-confirmed");
		UUID orderId = octoberOrder(fx);
		PaymentInitiationResponse rejected = initiatePaymentOrFail(fx.host(), fx.studentToken(), orderId);
		assertThat(sendPaymentWebhook(rejected.gatewayReference(), false).getStatusCode()).isEqualTo(HttpStatus.OK);

		// REJECTED evidence never blocks: a new attempt on the same order is allowed and confirmed.
		PaymentInitiationResponse confirmed = initiatePaymentOrFail(fx.host(), fx.studentToken(), orderId);
		assertThat(sendPaymentWebhook(confirmed.gatewayReference(), true).getStatusCode()).isEqualTo(HttpStatus.OK);

		HttpResult<PaymentInitiationResponse> again = initiatePayment(fx.host(), fx.studentToken(), orderId,
				UUID.randomUUID());
		assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(code(again)).isEqualTo("ITEM_ALREADY_PAID");
		HttpResult<PaymentSlipResponse> slipAfterPaid = slip(fx, orderId, "t8b-confirmed-1");
		assertThat(slipAfterPaid.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(code(slipAfterPaid)).isEqualTo("ITEM_ALREADY_PAID");
	}

	@Test
	void twoOrdersForTheSameItemStartingPaymentConcurrentlyLeaveExactlyOnePendingPayment() throws Exception {
		Fx fx = seed("t8b-race");
		UUID first = octoberOrder(fx);
		UUID second = octoberOrder(fx);

		ExecutorService pool = Executors.newFixedThreadPool(2);
		List<HttpResult<PaymentInitiationResponse>> results = new ArrayList<>();
		try {
			List<Callable<HttpResult<PaymentInitiationResponse>>> calls = List.of(
					() -> initiatePayment(fx.host(), fx.studentToken(), first, UUID.randomUUID()),
					() -> initiatePayment(fx.host(), fx.studentToken(), second, UUID.randomUUID()));
			for (Future<HttpResult<PaymentInitiationResponse>> future : pool.invokeAll(calls)) {
				results.add(future.get());
			}
		}
		finally {
			pool.shutdownNow();
		}

		assertThat(results).extracting(HttpResult::getStatusCode)
			.containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.CONFLICT);
		HttpResult<PaymentInitiationResponse> refused = results.stream()
			.filter(result -> result.getStatusCode() == HttpStatus.CONFLICT)
			.findFirst()
			.orElseThrow();
		assertThat(code(refused)).isEqualTo("ITEM_PENDING");
		assertThat(count("SELECT count(*) FROM payment p JOIN student_order o ON o.id = p.order_id "
				+ "WHERE o.tenant_id = ? AND p.status = 'PENDING'", fx.tenant().getId())).isEqualTo(1);
	}

	@Test
	void aGatewayPaymentAndASlipForTwoOrdersOfTheSameItemRacingLeaveOnlyOneLiveEvidence() throws Exception {
		Fx fx = seed("t8b-race-mixed");
		UUID first = octoberOrder(fx);
		UUID second = octoberOrder(fx);

		ExecutorService pool = Executors.newFixedThreadPool(2);
		List<HttpStatus> statuses = new ArrayList<>();
		try {
			List<Callable<HttpStatus>> calls = List.of(
					() -> initiatePayment(fx.host(), fx.studentToken(), first, UUID.randomUUID()).getStatusCode(),
					() -> slip(fx, second, "t8b-race-mixed-1").getStatusCode());
			for (Future<HttpStatus> future : pool.invokeAll(calls)) {
				statuses.add(future.get());
			}
		}
		finally {
			pool.shutdownNow();
		}

		assertThat(statuses).containsOnlyOnce(HttpStatus.CONFLICT);
		long live = count("SELECT count(*) FROM payment p JOIN student_order o ON o.id = p.order_id "
				+ "WHERE o.tenant_id = ? AND p.status = 'PENDING'", fx.tenant().getId())
				+ count("SELECT count(*) FROM payment_slip WHERE tenant_id = ? AND status IN ('SUBMITTED', "
						+ "'UNDER_REVIEW')", fx.tenant().getId());
		assertThat(live).isEqualTo(1);
	}

	@Test
	void legacyOrderForAMonthlyCourseIsRefusedWithUsePaymentComposer() {
		Fx fx = seed("t8b-d23");
		HttpResult<OrderResponse> legacy = createOrder(fx.host(), fx.studentToken(), fx.course());
		assertThat(legacy.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(code(legacy)).isEqualTo("USE_PAYMENT_COMPOSER");
		assertThat(count("SELECT count(*) FROM student_order WHERE tenant_id = ?", fx.tenant().getId())).isZero();
	}

}
