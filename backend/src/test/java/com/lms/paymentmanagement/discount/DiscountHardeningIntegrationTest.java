package com.lms.paymentmanagement.discount;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.lms.common.tenant.TenantContextHolder;
import com.lms.coursemanagement.course.domain.CoursePricingModel;
import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.web.dto.CourseCreateRequest;
import com.lms.identityaccessservice.domain.Role;
import com.lms.paymentmanagement.api.OrderRevenueBasis;
import com.lms.paymentmanagement.api.PaymentChargebackApi;
import com.lms.paymentmanagement.api.PaymentStatusApi;
import com.lms.paymentmanagement.order.web.dto.PaymentInitiationResponse;
import com.lms.paymentmanagement.slip.web.dto.PaymentSlipResponse;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * DISC-01 hardening (security + payment-ledger reviews; owner decisions
 * 2026-10-21): code-probing rate limit and status collapse, one use per
 * student per rule, bounded gateway holds, re-validation on hold retake, the
 * DISCOUNTS permission area and code masking, cross-tenant negatives for every
 * admin endpoint, chargeback / settlement behaviour of discounted orders, and
 * the V92 CHECK constraints.
 */
class DiscountHardeningIntegrationTest extends DiscountTestSupport {

	@Autowired
	private PaymentChargebackApi paymentChargebackApi;

	@Autowired
	private PaymentStatusApi paymentStatusApi;

	@Autowired
	private PlatformTransactionManager transactionManager;

	private void lapseHold(UUID orderId) {
		jdbcTemplate.update("UPDATE discount_redemption SET expires_at = now() - interval '1 hour' WHERE order_id = ?",
				orderId);
	}

	private Map<String, Object> codeCart(UUID course, java.time.YearMonth month, String code) {
		return with(cart(month(course, month)), "discountCode", code);
	}

	private String codeStatus(JsonNode quote) {
		return quote.path("codeResult").path("status").asString();
	}

	private long auditRows(UUID ruleId) {
		return count("SELECT count(*) FROM audit_log WHERE target_id = ? AND action LIKE 'discount_rule.%'", ruleId);
	}

	// ------------------------------------------------------------------
	// A: code probing.
	// ------------------------------------------------------------------

	@Test
	void invalidCodeAttemptsAreRateLimitedPerTenantAndStudentWith429AndASecurityLogEntry() {
		Logger securityLogger = (Logger) LoggerFactory.getLogger("SECURITY");
		ListAppender<ILoggingEvent> securityLog = new ListAppender<>();
		securityLog.start();
		securityLogger.addAppender(securityLog);
		try {
			rateLimitScenario(securityLog);
		}
		finally {
			securityLogger.detachAppender(securityLog);
		}
	}

	private void rateLimitScenario(ListAppender<ILoggingEvent> securityLog) {
		Fx fx = seed("disc-rl");
		createRule(fx, codeRule("Real", "REALCODE", List.of(fx.a()), "PERCENTAGE", 10));
		String secondStudent = extraStudent(fx, "student2@example.test");

		// 9 invalid quotes + 1 invalid order (the order endpoint counts too) = the allowance of 10.
		for (int i = 0; i < 9; i++) {
			assertThat(codeStatus(quoteOk(fx, codeCart(fx.a(), current, "GUESS" + i + "XX")))).isEqualTo("INVALID");
		}
		Map<String, Object> invalidOrder = codeCart(fx.a(), current, "GUESS9XX");
		JsonNode q = quoteOk(fx, invalidOrder);
		// (that quote was the 10th attempt; the order below the 11th)
		MvcResult limitedOrder = order(fx.host(), fx.studentToken(), invalidOrder, q.path("quoteToken").asString());
		assertThat(status(limitedOrder)).as(rawContent(limitedOrder)).isEqualTo(429);
		assertThat(errorCode(limitedOrder)).isEqualTo("DISCOUNT_CODE_RATE_LIMITED");
		assertThat(count("SELECT count(*) FROM student_order WHERE tenant_id = ?", fx.tenant().getId())).isZero();

		// Locked out: any code - even a valid one - is 429 on quote and order.
		MvcResult limitedQuote = quote(fx.host(), fx.studentToken(), codeCart(fx.a(), current, "REALCODE"));
		assertThat(status(limitedQuote)).isEqualTo(429);
		assertThat(errorCode(limitedQuote)).isEqualTo("DISCOUNT_CODE_RATE_LIMITED");
		assertThat(rawContent(limitedQuote)).doesNotContain("REALCODE", "Real");
		// A cart without a code is never limited.
		assertThat(dec(quoteOk(fx, cart(month(fx.a(), current))), "total")).isEqualByComparingTo("1000.00");
		// Another student of the same tenant is unaffected.
		assertThat(codeStatus(quoteOk(fx.host(), secondStudent, codeCart(fx.a(), current, "REALCODE"))))
			.isEqualTo("APPLIED");
		// Another tenant's student is unaffected.
		Fx other = seed("disc-rl-other");
		createRule(other, codeRule("Real", "REALCODE", List.of(other.a()), "PERCENTAGE", 10));
		assertThat(codeStatus(quoteOk(other, codeCart(other.a(), current, "REALCODE")))).isEqualTo("APPLIED");

		List<ILoggingEvent> refusals = securityLog.list.stream()
			.filter(event -> "discount.code_rate_limited".equals(event.getMessage()))
			.toList();
		assertThat(refusals).hasSize(2);
		assertThat(refusals.get(0).getKeyValuePairs().toString()).contains(fx.tenant().getId().toString(),
				fx.student().getId().toString());
		assertThat(refusals.toString()).doesNotContain("REALCODE", "GUESS");
	}

	@Test
	void validCodesNeverCountAgainstTheLimit() {
		Fx fx = seed("disc-rl-valid");
		createRule(fx, codeRule("Real", "REALCODE", List.of(fx.a()), "PERCENTAGE", 10));
		createRule(fx, codeRule("Needs B", "NEEDSB", List.of(fx.b()), "PERCENTAGE", 10));
		for (int i = 0; i < 12; i++) {
			assertThat(codeStatus(quoteOk(fx, codeCart(fx.a(), current, "REALCODE")))).isEqualTo("APPLIED");
			assertThat(codeStatus(quoteOk(fx, codeCart(fx.a(), current, "NEEDSB")))).isEqualTo("NOT_ELIGIBLE");
		}
	}

	@Test
	void codesShorterThanSixCharactersAreRejectedByTheAdminApiAndTheDatabase() {
		Fx fx = seed("disc-minlen");
		MvcResult tooShort = jsonCall(fx.host(), fx.adminToken(), post(ADMIN),
				codeRule("Short", "AB12C", List.of(fx.a()), "PERCENTAGE", 10));
		assertThat(status(tooShort)).isEqualTo(422);
		assertThat(errorCode(tooShort)).isEqualTo("DISCOUNT_VALIDATION");
		assertThat(json(tooShort).path("error").path("fieldErrors").toString()).contains("code", "6-40");
		UUID ruleId = createRule(fx, codeRule("Six", "AB12CD", List.of(fx.a()), "PERCENTAGE", 10));

		assertThatThrownBy(() -> jdbcTemplate.update("UPDATE discount_rule SET code_normalized = 'AB12C' WHERE id = ?",
				ruleId))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("ck_discount_rule_code_shape");
		UUID versionId = jdbcTemplate.queryForObject("SELECT current_version_id FROM discount_rule WHERE id = ?",
				UUID.class, ruleId);
		assertThatThrownBy(() -> jdbcTemplate.update(
				"INSERT INTO discount_rule_version (id, tenant_id, rule_id, version_no, name, discount_type, "
						+ "selection_mode, value_type, value, code, valid_from, valid_to, currency, created_at, "
						+ "created_by) SELECT ?, tenant_id, rule_id, 99, name, discount_type, selection_mode, "
						+ "value_type, value, 'AB12C', valid_from, valid_to, currency, now(), created_by "
						+ "FROM discount_rule_version WHERE id = ?",
				UUID.randomUUID(), versionId))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("ck_discount_rule_version_code");
	}

	// ------------------------------------------------------------------
	// B: one use per student per rule, gateway TTL.
	// ------------------------------------------------------------------

	@Test
	void aStudentHasAtMostOneCountingRedemptionPerRuleAndALapsedHoldDoesNotBlock() {
		Fx fx = seed("disc-cap");
		UUID code = createRule(fx, codeRule("Ten off", "TENOFF", List.of(fx.a()), "PERCENTAGE", 10));
		// Capped automatic rules are once per student too (uncapped ones repeat - see below).
		UUID bundle = createRule(fx, with(
				valueRule("Bundle", "BUNDLE_AMOUNT_OFF", List.of(fx.b()), "ANY", "FIXED", 100), "maxUses", 100));
		UUID free = createRule(fx, with(with(with(rule("Free C", "BUNDLE_FREE_COURSE", List.of(fx.b())),
				"rewardCourseIds", List.of(fx.c())), "freeMonths", 1), "maxUses", 100));

		JsonNode first = placeOrder(fx, with(with(codeCart(fx.a(), current, "TENOFF"), "items",
				List.of(month(fx.a(), current), month(fx.b(), current))), "acceptedRewardRuleIds", List.of(free)));
		UUID firstOrder = id(first);
		// Code 100 on A, bundle 100 on B, reward C (500).
		assertThat(dec(first, "discountTotal")).isEqualByComparingTo("700.00");
		assertThat(count("SELECT count(*) FROM discount_redemption WHERE order_id = ?", firstOrder)).isEqualTo(3);

		// RESERVED (live) counts: the code is NOT_ELIGIBLE with its own message, the
		// bundle no longer applies, the free course is no longer offered.
		JsonNode again = quoteOk(fx, with(codeCart(fx.a(), next, "tenoff"), "items",
				List.of(month(fx.a(), next), month(fx.b(), next))));
		assertThat(codeStatus(again)).isEqualTo("NOT_ELIGIBLE");
		assertThat(again.path("codeResult").path("message").asString())
			.isEqualTo("You have already used this discount.");
		assertThat(dec(again, "discountTotal")).isEqualByComparingTo("0");
		assertThat(again.path("rewardOffers")).isEmpty();
		assertThat(again.toString()).doesNotContain(bundle.toString(), free.toString());

		// Another student still gets everything.
		String second = extraStudent(fx, "student2@example.test");
		JsonNode otherStudent = quoteOk(fx.host(), second, with(codeCart(fx.a(), current, "TENOFF"), "items",
				List.of(month(fx.a(), current), month(fx.b(), current))));
		assertThat(codeStatus(otherStudent)).isEqualTo("APPLIED");
		assertThat(otherStudent.path("rewardOffers")).hasSize(1);

		// A lapsed hold no longer counts: the student's new order gets the discounts again.
		lapseHold(firstOrder);
		JsonNode afterLapse = placeOrder(fx, with(codeCart(fx.a(), next, "TENOFF"), "items",
				List.of(month(fx.a(), next), month(fx.b(), next))));
		assertThat(dec(afterLapse, "discountTotal")).isEqualByComparingTo("200.00");

		// CONSUMED counts for good.
		confirmByGateway(fx, id(afterLapse));
		assertThat(count("SELECT count(*) FROM discount_redemption WHERE order_id = ? AND status = 'CONSUMED'",
				id(afterLapse))).isEqualTo(2);
		lapseHold(firstOrder);
		JsonNode afterConsume = quoteOk(fx, codeCart(fx.a(), later, "TENOFF"));
		assertThat(codeStatus(afterConsume)).isEqualTo("NOT_ELIGIBLE");
		assertThat(count("SELECT count(*) FROM discount_redemption WHERE rule_id = ? AND student_id = ?", code,
				fx.student().getId())).isEqualTo(2);
	}

	@Test
	void uncappedAutomaticRulesApplyOnEveryOrderOfTheSameStudent() {
		Fx fx = seed("disc-cap-uncapped");
		// (Free-course scope is covered in DiscountRedemptionAndPlacementGuardsTest: its reward
		// window is anchored to the current month, so a repeat is not observable on one clock.)
		UUID bundle = createRule(fx,
				valueRule("Bundle", "BUNDLE_AMOUNT_OFF", List.of(fx.b()), "ANY", "FIXED", 100));

		JsonNode first = placeOrder(fx, cart(month(fx.b(), current)));
		assertThat(dec(first, "discountTotal")).isEqualByComparingTo("100.00");
		assertThat(count("SELECT count(*) FROM discount_redemption WHERE order_id = ?", id(first))).isEqualTo(1);

		// A live hold does not block an uncapped automatic rule ...
		JsonNode whileHeld = placeOrder(fx, cart(month(fx.b(), next)));
		assertThat(dec(whileHeld, "discountTotal")).isEqualByComparingTo("100.00");

		// ... nor does a consumed use.
		confirmByGateway(fx, id(first));
		JsonNode afterConsume = quoteOk(fx, cart(month(fx.b(), later)));
		assertThat(dec(afterConsume, "discountTotal")).isEqualByComparingTo("100.00");
		assertThat(afterConsume.toString()).contains(bundle.toString());
	}

	@Test
	void concurrentOrdersOfOneStudentNeverTakeTheSameRuleTwice() throws Exception {
		Fx fx = seed("disc-cap-race");
		UUID ruleId = createRule(fx, codeRule("Once each", "ONCEEACH", List.of(fx.a()), "FIXED", 100));
		List<Map<String, Object>> bodies = List.of(codeCart(fx.a(), current, "ONCEEACH"),
				codeCart(fx.a(), next, "ONCEEACH"));
		List<String> quoteTokens = new ArrayList<>();
		for (Map<String, Object> body : bodies) {
			JsonNode q = quoteOk(fx, body);
			assertThat(codeStatus(q)).isEqualTo("APPLIED");
			quoteTokens.add(q.path("quoteToken").asString());
		}
		CyclicBarrier barrier = new CyclicBarrier(2);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		List<MvcResult> results = new ArrayList<>();
		try {
			List<Callable<MvcResult>> tasks = new ArrayList<>();
			for (int i = 0; i < 2; i++) {
				Map<String, Object> body = bodies.get(i);
				String token = quoteTokens.get(i);
				tasks.add(() -> {
					barrier.await(30, TimeUnit.SECONDS);
					return order(fx.host(), fx.studentToken(), body, token);
				});
			}
			for (Future<MvcResult> future : executor.invokeAll(tasks, 120, TimeUnit.SECONDS)) {
				results.add(future.get());
			}
		}
		finally {
			executor.shutdownNow();
		}
		List<Integer> statuses = results.stream().map(DiscountTestSupport::status).sorted().toList();
		assertThat(statuses).as(results.stream().map(this::rawContent).toList().toString()).containsExactly(201, 409);
		MvcResult loser = results.stream().filter(r -> status(r) == 409).findFirst().orElseThrow();
		assertThat(errorCode(loser)).isEqualTo("QUOTE_CHANGED");
		assertThat(codeStatus(json(loser).path("error").path("details").path("quote"))).isEqualTo("NOT_ELIGIBLE");
		assertThat(count("SELECT count(*) FROM discount_redemption WHERE rule_id = ? AND student_id = ?", ruleId,
				fx.student().getId())).isEqualTo(1);
	}

	@Test
	void gatewayHoldsAreBoundedAndAVerifiedConfirmationConsumesEvenAfterTheHoldLapsed() {
		Fx fx = seed("disc-gw-ttl");
		createRule(fx, with(codeRule("Capped", "CAPPED1", List.of(fx.a()), "FIXED", 100), "maxUses", 1));
		JsonNode order = placeOrder(fx, codeCart(fx.a(), current, "CAPPED1"));
		UUID orderId = id(order);

		PaymentInitiationResponse payment = initiatePaymentOrFail(fx.host(), fx.studentToken(), orderId);
		assertThat(count("SELECT count(*) FROM discount_redemption WHERE order_id = ? AND status = 'RESERVED' "
				+ "AND expires_at > now() + interval '47 hours' AND expires_at < now() + interval '49 hours'",
				orderId)).isEqualTo(1);

		// The gateway hold lapses while the payment is still PENDING; the verified
		// confirmation must still succeed and consume it.
		lapseHold(orderId);
		assertThat(sendPaymentWebhook(payment.gatewayReference(), true).getStatusCode().value()).isEqualTo(200);
		assertThat(count("SELECT count(*) FROM payment WHERE id = ? AND status = 'CONFIRMED' AND amount = 900",
				payment.paymentId())).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM discount_redemption WHERE order_id = ? AND status = 'CONSUMED' "
				+ "AND expires_at IS NULL AND consumed_at IS NOT NULL", orderId)).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM content_entitlement WHERE order_id = ?", orderId)).isEqualTo(1);
	}

	@Test
	void slipRejectionRestartsTheHoldWithAFresh48hExpiry() {
		Fx fx = seed("disc-slip-ttl");
		createRule(fx, codeRule("Ten off", "SLIPTEN", List.of(fx.a()), "PERCENTAGE", 10));
		UUID orderId = id(placeOrder(fx, codeCart(fx.a(), current, "SLIPTEN")));
		PaymentSlipResponse slip = uploadSlipOrFail(fx.host(), fx.studentToken(), orderId, "REF-DISC-TTL",
				pdfFile("slip.pdf", distinctPdfBytes("disc-ttl")));
		assertThat(slip.orderAmount()).isEqualByComparingTo("900.00");
		assertThat(count("SELECT count(*) FROM discount_redemption WHERE order_id = ? AND expires_at IS NULL",
				orderId)).isEqualTo(1);
		assertThat(rejectSlip(fx.host(), fx.financeToken(), slip.id(), "Unreadable").getStatusCode().value())
			.isEqualTo(200);
		assertThat(count("SELECT count(*) FROM discount_redemption WHERE order_id = ? AND status = 'RESERVED' "
				+ "AND expires_at > now() + interval '47 hours' AND expires_at < now() + interval '49 hours'",
				orderId)).isEqualTo(1);
	}

	// ------------------------------------------------------------------
	// D: re-validation when a lapsed hold is re-taken.
	// ------------------------------------------------------------------

	private String retakeByGateway(Fx fx, UUID orderId) {
		var result = initiatePayment(fx.host(), fx.studentToken(), orderId);
		return result.getStatusCode().is2xxSuccessful() ? "OK"
				: result.getStatusCode().value() + ":" + result.getBody().error().code();
	}

	@Test
	void aLapsedHoldOfADeactivatedArchivedOrExpiredRuleIsNeverRetaken() {
		Fx fx = seed("disc-retake");
		Map<String, Object> body = codeRule("Retake", "RETAKE1", List.of(fx.a()), "PERCENTAGE", 10);
		UUID ruleId = createRule(fx, body);

		// Inactive -> 409; re-activated -> re-taken (gateway TTL).
		UUID inactiveOrder = id(placeOrder(fx, codeCart(fx.a(), current, "RETAKE1")));
		lapseHold(inactiveOrder);
		setActive(fx, ruleId, false);
		assertThat(retakeByGateway(fx, inactiveOrder)).isEqualTo("409:DISCOUNT_NO_LONGER_AVAILABLE");
		setActive(fx, ruleId, true);
		assertThat(retakeByGateway(fx, inactiveOrder)).isEqualTo("OK");
		assertThat(count("SELECT count(*) FROM discount_redemption WHERE order_id = ? AND expires_at > now()",
				inactiveOrder)).isEqualTo(1);

		// Outside the rule's validity window (edited to end yesterday) -> 409.
		Map<String, Object> expired = with(with(body, "validFrom", today.minusDays(5).toString()), "validTo",
				today.minusDays(1).toString());
		Fx fx2 = seed("disc-retake-exp");
		UUID rule2 = createRule(fx2, codeRule("Retake", "RETAKE2", List.of(fx2.a()), "PERCENTAGE", 10));
		UUID expiredOrder = id(placeOrder(fx2, codeCart(fx2.a(), current, "RETAKE2")));
		lapseHold(expiredOrder);
		MvcResult edited = jsonCall(fx2.host(), fx2.adminToken(), put(ADMIN + "/{id}", rule2),
				with(with(expired, "code", "RETAKE2"), "courseIds", List.of(fx2.a())));
		assertThat(status(edited)).as(rawContent(edited)).isEqualTo(200);
		assertThat(retakeByGateway(fx2, expiredOrder)).isEqualTo("409:DISCOUNT_NO_LONGER_AVAILABLE");

		// Archived -> 409 on slip upload too (pre-check before any storage write).
		Fx fx3 = seed("disc-retake-arch");
		UUID rule3 = createRule(fx3, codeRule("Retake", "RETAKE3", List.of(fx3.a()), "PERCENTAGE", 10));
		UUID archivedOrder = id(placeOrder(fx3, codeCart(fx3.a(), current, "RETAKE3")));
		lapseHold(archivedOrder);
		assertThat(status(call(fx3.host(), fx3.adminToken(), delete(ADMIN + "/{id}", rule3)))).isEqualTo(204);
		var upload = uploadSlip(fx3.host(), fx3.studentToken(), archivedOrder, "REF-ARCH",
				pdfFile("slip.pdf", distinctPdfBytes("disc-arch")));
		assertThat(upload.getStatusCode().value()).isEqualTo(409);
		assertThat(upload.getBody().error().code()).isEqualTo("DISCOUNT_NO_LONGER_AVAILABLE");
		assertThat(count("SELECT count(*) FROM payment_slip WHERE order_id = ?", archivedOrder)).isZero();
		assertThat(retakeByGateway(fx3, archivedOrder)).isEqualTo("409:DISCOUNT_NO_LONGER_AVAILABLE");
		assertThat(count("SELECT count(*) FROM payment WHERE order_id = ?", archivedOrder)).isZero();
	}

	@Test
	void retakeComparesUsageWithTheOrdersVersionAndTheOneUsePerStudentCap() {
		Fx fx = seed("disc-retake-cap");
		Map<String, Object> body = with(codeRule("Capped", "CAPONE", List.of(fx.a()), "FIXED", 100), "maxUses", 1);
		UUID ruleId = createRule(fx, body);
		UUID firstOrder = id(placeOrder(fx, codeCart(fx.a(), current, "CAPONE")));
		lapseHold(firstOrder);
		// Another student takes the only use of version 1.
		String second = extraStudent(fx, "student2@example.test");
		Map<String, Object> secondBody = codeCart(fx.a(), current, "CAPONE");
		MvcResult secondOrder = order(fx.host(), second, secondBody,
				quoteOk(fx.host(), second, secondBody).path("quoteToken").asString());
		assertThat(status(secondOrder)).as(rawContent(secondOrder)).isEqualTo(201);
		// The admin raises Maximum Uses in version 2 - the first order was priced
		// against version 1 (max 1), which is what its retake is checked against.
		MvcResult edited = jsonCall(fx.host(), fx.adminToken(), put(ADMIN + "/{id}", ruleId),
				with(body, "maxUses", 10));
		assertThat(status(edited)).as(rawContent(edited)).isEqualTo(200);
		assertThat(retakeByGateway(fx, firstOrder)).isEqualTo("409:DISCOUNT_NO_LONGER_AVAILABLE");

		// One use per student: an uncapped rule, the student's lapsed order is
		// superseded by a newer order with the same rule -> the old one can't be re-taken.
		Fx fx2 = seed("disc-retake-student");
		createRule(fx2, codeRule("Uncapped", "UNCAPPED", List.of(fx2.a()), "FIXED", 100));
		UUID oldOrder = id(placeOrder(fx2, codeCart(fx2.a(), current, "UNCAPPED")));
		lapseHold(oldOrder);
		UUID newOrder = id(placeOrder(fx2, codeCart(fx2.a(), next, "UNCAPPED")));
		assertThat(count("SELECT count(*) FROM discount_redemption WHERE order_id = ?", newOrder)).isEqualTo(1);
		assertThat(retakeByGateway(fx2, oldOrder)).isEqualTo("409:DISCOUNT_NO_LONGER_AVAILABLE");
		assertThat(retakeByGateway(fx2, newOrder)).isEqualTo("OK");
	}

	// ------------------------------------------------------------------
	// C: DISCOUNTS permission area and code masking.
	// ------------------------------------------------------------------

	@Test
	void discountsPermissionAreaSlipOnlyRolesCannotWriteAndViewOnlyCallersSeeMaskedCodes() {
		Fx fx = seed("disc-perm");
		seedTenantUser(fx.tenant().getId(), "auditor@example.test", RAW_PASSWORD, Role.READ_ONLY_AUDITOR);
		seedTenantUser(fx.tenant().getId(), "coord@example.test", RAW_PASSWORD, Role.COURSE_COORDINATOR);
		String auditor = loginAndGetToken(fx.host(), "auditor@example.test");
		String coordinator = loginAndGetToken(fx.host(), "coord@example.test");
		Map<String, Object> body = codeRule("Secret", "SECRET99", List.of(fx.a()), "PERCENTAGE", 10);
		UUID ruleId = createRule(fx, body);

		// Finance Staff holds PAYMENTS_SLIPS VIEW/CREATE_EDIT/APPROVE but DISCOUNTS VIEW only.
		String finance = fx.financeToken();
		assertThat(status(jsonCall(fx.host(), finance, post(ADMIN),
				codeRule("Finance", "FINANCE1", List.of(fx.a()), "PERCENTAGE", 10))))
			.isEqualTo(403);
		assertThat(status(jsonCall(fx.host(), finance, put(ADMIN + "/{id}", ruleId), with(body, "value", 50))))
			.isEqualTo(403);
		assertThat(status(jsonCall(fx.host(), finance, patch(ADMIN + "/{id}/active", ruleId),
				Map.of("active", false))))
			.isEqualTo(403);
		assertThat(status(call(fx.host(), finance, delete(ADMIN + "/{id}", ruleId)))).isEqualTo(403);
		assertThat(count("SELECT count(*) FROM discount_rule_version WHERE rule_id = ?", ruleId)).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM discount_rule WHERE id = ? AND active AND archived_at IS NULL",
				ruleId)).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM discount_rule WHERE tenant_id = ?", fx.tenant().getId())).isEqualTo(1);
		assertThat(auditRows(ruleId)).isEqualTo(1);

		// VIEW-only callers read rules with the code masked; the admin sees it.
		for (String viewer : List.of(finance, auditor)) {
			MvcResult list = call(fx.host(), viewer, get(ADMIN));
			assertThat(status(list)).isEqualTo(200);
			assertThat(json(list).path("data").path("items").get(0).path("code").asString()).isEqualTo("******");
			MvcResult one = call(fx.host(), viewer, get(ADMIN + "/{id}", ruleId));
			assertThat(status(one)).isEqualTo(200);
			assertThat(json(one).path("data").path("code").asString()).isEqualTo("******");
			assertThat(rawContent(list) + rawContent(one)).doesNotContain("SECRET99");
		}
		assertThat(json(call(fx.host(), fx.adminToken(), get(ADMIN + "/{id}", ruleId))).path("data").path("code")
			.asString()).isEqualTo("SECRET99");
		// Roles without DISCOUNTS at all.
		assertThat(status(call(fx.host(), coordinator, get(ADMIN)))).isEqualTo(403);
		assertThat(status(call(fx.host(), coordinator, get(ADMIN + "/course-options")))).isEqualTo(403);
		assertThat(status(call(fx.host(), fx.studentToken(), get(ADMIN + "/{id}", ruleId)))).isEqualTo(403);
	}

	// ------------------------------------------------------------------
	// E: cross-tenant negatives for every admin endpoint.
	// ------------------------------------------------------------------

	@Test
	void tenantBAdminCannotEditToggleListOrPickTenantAResourcesAndForeignCoursesAre422() {
		Fx a = seed("disc-xt-a");
		Fx b = seed("disc-xt-b");
		Map<String, Object> body = codeRule("A rule", "ARULE01", List.of(a.a()), "PERCENTAGE", 10);
		UUID aRule = createRule(a, body);
		UUID aSecond = createRule(a, valueRule("A bundle", "BUNDLE_AMOUNT_OFF", List.of(a.b()), "ANY", "FIXED", 50));
		Set<UUID> bRules = new HashSet<>();
		bRules.add(createRule(b, codeRule("B rule", "BRULE01", List.of(b.a()), "PERCENTAGE", 10)));
		bRules.add(createRule(b, valueRule("B bundle", "BUNDLE_AMOUNT_OFF", List.of(b.b()), "ANY", "FIXED", 50)));
		bRules.add(createRule(b, with(valueRule("B off", "BUNDLE_AMOUNT_OFF", List.of(b.c()), "ANY", "FIXED", 50),
				"active", false)));

		// PUT / PATCH on A's rule from tenant B -> 404, nothing changes, nothing audited.
		MvcResult putForeign = jsonCall(b.host(), b.adminToken(), put(ADMIN + "/{id}", aRule),
				with(with(body, "courseIds", List.of(b.a())), "value", 99));
		assertThat(status(putForeign)).isEqualTo(404);
		MvcResult patchForeign = jsonCall(b.host(), b.adminToken(), patch(ADMIN + "/{id}/active", aRule),
				Map.of("active", false));
		assertThat(status(patchForeign)).isEqualTo(404);
		assertThat(status(call(b.host(), b.adminToken(), get(ADMIN + "/{id}", aRule)))).isEqualTo(404);
		assertThat(count("SELECT count(*) FROM discount_rule_version WHERE rule_id = ?", aRule)).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM discount_rule WHERE id = ? AND active AND archived_at IS NULL", aRule))
			.isEqualTo(1);
		assertThat(auditRows(aRule)).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM audit_log WHERE target_id = ? AND tenant_id = ?", aRule,
				b.tenant().getId())).isZero();

		// List: B's rules are absent from A's items AND counts.
		JsonNode list = json(call(a.host(), a.adminToken(), get(ADMIN))).path("data");
		Set<String> ids = new HashSet<>();
		list.path("items").forEach(item -> ids.add(item.path("id").asString()));
		assertThat(ids).containsExactlyInAnyOrder(aRule.toString(), aSecond.toString());
		assertThat(list.path("counts").path("all").asLong()).isEqualTo(2);
		assertThat(list.path("counts").path("active").asLong()).isEqualTo(2);
		assertThat(list.path("counts").path("inactive").asLong()).isZero();
		JsonNode inactive = json(call(a.host(), a.adminToken(), get(ADMIN + "?status=INACTIVE"))).path("data");
		assertThat(inactive.path("items")).isEmpty();
		bRules.forEach(id -> assertThat(list.toString()).doesNotContain(id.toString()));

		// Course options: only A's courses.
		Set<String> options = new HashSet<>();
		json(call(a.host(), a.adminToken(), get(ADMIN + "/course-options"))).path("data")
			.forEach(option -> options.add(option.path("courseId").asString()));
		assertThat(options).containsExactlyInAnyOrder(a.a().toString(), a.b().toString(), a.c().toString());

		// A REAL tenant-B course id on create / update -> 422 DISCOUNT_COURSE_NOT_FOUND.
		MvcResult createForeign = jsonCall(a.host(), a.adminToken(), post(ADMIN),
				valueRule("Foreign", "BUNDLE_AMOUNT_OFF", List.of(a.a(), b.a()), "ANY", "FIXED", 50));
		assertThat(status(createForeign)).isEqualTo(422);
		assertThat(errorCode(createForeign)).isEqualTo("DISCOUNT_COURSE_NOT_FOUND");
		MvcResult rewardForeign = jsonCall(a.host(), a.adminToken(), post(ADMIN), with(with(
				rule("Foreign free", "BUNDLE_FREE_COURSE", List.of(a.a())), "rewardCourseIds", List.of(b.c())),
				"freeMonths", 1));
		assertThat(status(rewardForeign)).isEqualTo(422);
		assertThat(errorCode(rewardForeign)).isEqualTo("DISCOUNT_COURSE_NOT_FOUND");
		MvcResult updateForeign = jsonCall(a.host(), a.adminToken(), put(ADMIN + "/{id}", aRule),
				with(body, "courseIds", List.of(b.a())));
		assertThat(status(updateForeign)).isEqualTo(422);
		assertThat(errorCode(updateForeign)).isEqualTo("DISCOUNT_COURSE_NOT_FOUND");
		assertThat(count("SELECT count(*) FROM discount_rule_version WHERE rule_id = ?", aRule)).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM discount_rule WHERE tenant_id = ?", a.tenant().getId())).isEqualTo(2);
	}

	// ------------------------------------------------------------------
	// Lifecycle: chargeback, settlement basis, DB CHECKs.
	// ------------------------------------------------------------------

	@Test
	void chargebackOfADiscountedOrderRevokesItsRewardLineEntitlementsAndKeepsFreeEnrollment() {
		Fx fx = seed("disc-cb");
		UUID tenantId = fx.tenant().getId();
		createRule(fx, valueRule("Ten off", "BUNDLE_AMOUNT_OFF", List.of(fx.a()), "ANY", "PERCENTAGE", 10));
		UUID free = createRule(fx, with(with(rule("Free C", "BUNDLE_FREE_COURSE", List.of(fx.a())),
				"rewardCourseIds", List.of(fx.c())), "freeMonths", 1));
		JsonNode order = placeOrder(fx, with(cart(month(fx.a(), current)), "acceptedRewardRuleIds", List.of(free)));
		UUID orderId = id(order);
		assertThat(dec(order, "amount")).isEqualByComparingTo("900.00");
		long participations = count("SELECT count(*) FROM course_participation WHERE tenant_id = ?", tenantId);
		assertThat(participations).isEqualTo(3);
		PaymentInitiationResponse payment = initiatePaymentOrFail(fx.host(), fx.studentToken(), orderId);
		assertThat(sendPaymentWebhook(payment.gatewayReference(), true).getStatusCode().value()).isEqualTo(200);
		assertThat(launch(fx, fx.a(), 0)).isEqualTo(200);
		assertThat(launch(fx, fx.c(), 0)).isEqualTo(200);

		paymentChargebackApi.recordChargeback(payment.gatewayReference(), "DISC-CB-1");
		paymentChargebackApi.recordChargeback(payment.gatewayReference(), "DISC-CB-1");

		assertThat(count("SELECT count(*) FROM content_entitlement WHERE order_id = ?", orderId)).isEqualTo(2);
		assertThat(count("SELECT count(*) FROM content_entitlement WHERE order_id = ? AND revoked_reason = "
				+ "'CHARGEBACK' AND revoked_by_refund_id IS NOT NULL", orderId)).isEqualTo(2);
		assertThat(count("SELECT count(*) FROM content_entitlement e JOIN student_order_line l ON l.id = "
				+ "e.order_line_id WHERE e.order_id = ? AND l.reward AND e.revoked_reason = 'CHARGEBACK'", orderId))
			.isEqualTo(1);
		assertThat(launch(fx, fx.a(), 0)).isNotEqualTo(200);
		assertThat(launch(fx, fx.c(), 0)).isNotEqualTo(200);
		// ADR-028: the free enrollment (participation) is untouched.
		assertThat(count("SELECT count(*) FROM course_participation WHERE tenant_id = ?", tenantId))
			.isEqualTo(participations);
		// Ledger: the CONFIRMED payment is never modified; one REFUND entry reverses it.
		assertThat(count("SELECT count(*) FROM payment WHERE id = ? AND status = 'CONFIRMED' AND amount = 900",
				payment.paymentId())).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM ledger_entry WHERE payment_id = ? AND entry_type = 'REFUND'",
				payment.paymentId())).isEqualTo(1);
		// A chargeback never restores a use.
		assertThat(count("SELECT count(*) FROM discount_redemption WHERE order_id = ? AND status = 'CONSUMED'",
				orderId)).isEqualTo(2);
	}

	@Test
	void settlementSharesFollowTheNetLineTotalOfDiscountedAndRewardLines() {
		// Documents ADR-029 behaviour for the owner's sign-off: course shares are
		// sum(line_total) = unit + fee - discount, so a discount lowers the
		// course's share and a free reward course's share is 0. Not changed here.
		Fx fx = seed("disc-settle");
		createRule(fx, valueRule("Bundle", "BUNDLE_AMOUNT_OFF", List.of(fx.a(), fx.b()), "ALL", "FIXED", 300));
		UUID free = createRule(fx, with(with(rule("Free C", "BUNDLE_FREE_COURSE", List.of(fx.a())),
				"rewardCourseIds", List.of(fx.c())), "freeMonths", 1));
		JsonNode bundle = placeOrder(fx, with(cart(month(fx.a(), current), month(fx.b(), current)),
				"acceptedRewardRuleIds", List.of(free)));
		UUID orderId = id(bundle);
		assertThat(dec(bundle, "amount")).isEqualByComparingTo("2700.00");

		TenantContextHolder.set(fx.tenant().getId());
		List<OrderRevenueBasis> bases;
		try {
			bases = paymentStatusApi.findOrderRevenueBases(List.of(orderId));
		}
		finally {
			TenantContextHolder.clear();
		}
		assertThat(bases).hasSize(1);
		OrderRevenueBasis basis = bases.get(0);
		assertThat(basis.orderAmount()).isEqualByComparingTo("2700.00");
		// 300 split pro rata 1000 : 2000 -> 100 / 200.
		assertThat(basis.courseShares().get(fx.a())).isEqualByComparingTo("900.00");
		assertThat(basis.courseShares().get(fx.b())).isEqualByComparingTo("1800.00");
		assertThat(basis.courseShares().get(fx.c())).isEqualByComparingTo("0.00");
		assertThat(basis.courseShares().values().stream().reduce(BigDecimal.ZERO, BigDecimal::add))
			.isEqualByComparingTo(basis.orderAmount());
	}

	@Test
	void v92CheckConstraintsRejectBadRowsWrittenDirectly() {
		Fx fx = seed("disc-checks");
		createRule(fx, valueRule("A ten", "BUNDLE_AMOUNT_OFF", List.of(fx.a()), "ANY", "PERCENTAGE", 10));
		JsonNode order = placeOrder(fx, cart(month(fx.a(), current), month(fx.b(), current)));
		UUID orderId = id(order);
		assertThat(dec(order, "discountTotal")).isEqualByComparingTo("100.00");

		// D3: a discounted order can never have a zero total (fee-snapshot sum kept consistent).
		assertThatThrownBy(() -> jdbcTemplate.update("UPDATE student_order SET discount_amount = subtotal_amount "
				+ "+ facility_fee_amount + COALESCE(postage_amount, 0), amount = 0 WHERE id = ?", orderId))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("ck_student_order_discount_positive_total");
		// A reward line must be fully discounted by a recorded discount.
		assertThatThrownBy(() -> jdbcTemplate.update(
				"UPDATE student_order_line SET reward = true WHERE order_id = ? AND order_discount_id IS NULL",
				orderId))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("ck_student_order_line_reward");
		// Any line discount must be traceable to an applied discount (line_total kept consistent).
		assertThatThrownBy(() -> jdbcTemplate.update("UPDATE student_order_line SET discount_amount = 1, "
				+ "line_total = line_total - 1 WHERE order_id = ? AND order_discount_id IS NULL", orderId))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("ck_student_order_line_discount_source");

		// A CONSUMED redemption never carries an expiry.
		confirmByGateway(fx, orderId);
		assertThatThrownBy(() -> jdbcTemplate.update(
				"UPDATE discount_redemption SET expires_at = now() + interval '1 hour' WHERE order_id = ?", orderId))
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("ck_discount_redemption_consumed_no_expiry");
	}

	// ------------------------------------------------------------------
	// F: admin validation minors.
	// ------------------------------------------------------------------

	@Test
	void aConcurrentDuplicateCodeIs409DiscountCodeTakenNot500() throws Exception {
		Fx fx = seed("disc-race-code");
		CountDownLatch inserted = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			// A competing transaction holds an UNCOMMITTED live rule with the code, so
			// the API's pre-check cannot see it and its insert blocks on the index.
			Future<?> holder = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(
					tx -> {
						jdbcTemplate.update("INSERT INTO discount_rule (id, tenant_id, discount_type, code_normalized, "
								+ "active, created_at, created_by, updated_at) VALUES (?, ?, 'DISCOUNT_CODE', "
								+ "'RACE01', true, now(), ?, now())", UUID.randomUUID(), fx.tenant().getId(),
								UUID.randomUUID());
						inserted.countDown();
						try {
							release.await(60, TimeUnit.SECONDS);
						}
						catch (InterruptedException ex) {
							Thread.currentThread().interrupt();
						}
					}));
			assertThat(inserted.await(30, TimeUnit.SECONDS)).isTrue();
			Future<MvcResult> create = executor.submit(() -> jsonCall(fx.host(), fx.adminToken(), post(ADMIN),
					codeRule("Race", "race01", List.of(fx.a()), "PERCENTAGE", 10)));
			long deadline = System.currentTimeMillis() + 20_000;
			while (System.currentTimeMillis() < deadline && count("SELECT count(*) FROM pg_stat_activity "
					+ "WHERE wait_event_type = 'Lock' AND datname = current_database()") == 0) {
				Thread.sleep(50);
			}
			release.countDown();
			holder.get(30, TimeUnit.SECONDS);
			MvcResult result = create.get(60, TimeUnit.SECONDS);
			assertThat(status(result)).as(rawContent(result)).isEqualTo(409);
			assertThat(errorCode(result)).isEqualTo("DISCOUNT_CODE_TAKEN");
		}
		finally {
			release.countDown();
			executor.shutdownNow();
		}
		assertThat(count("SELECT count(*) FROM discount_rule WHERE tenant_id = ?", fx.tenant().getId())).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM discount_rule_version WHERE tenant_id = ?", fx.tenant().getId()))
			.isZero();
	}

	private UUID course(Fx fx, String prefix, CourseStatus status) {
		String slug = uniqueSlug(prefix);
		return createCourseOrFail(fx.host(), fx.adminToken(),
				new CourseCreateRequest("Course " + slug, slug, "Mathematics", null, null, null, null, null,
						new BigDecimal("700.00"), null, null, status, fx.teacher().getId(), null,
						CoursePricingModel.MONTHLY, null, null, null, null, null, null, null))
			.id();
	}

	@Test
	void draftAndArchivedCoursesDatesAndOversizedCourseListsAreRejected() {
		Fx fx = seed("disc-valid");
		UUID draft = course(fx, "disc-valid-draft", CourseStatus.DRAFT);
		UUID archived = course(fx, "disc-valid-arch", CourseStatus.PUBLIC);
		jdbcTemplate.update("UPDATE course SET archived_at = now() WHERE id = ?", archived);

		Set<String> options = new HashSet<>();
		json(call(fx.host(), fx.adminToken(), get(ADMIN + "/course-options"))).path("data")
			.forEach(option -> options.add(option.path("courseId").asString()));
		assertThat(options).contains(fx.a().toString()).doesNotContain(draft.toString(), archived.toString());

		for (UUID bad : List.of(draft, archived)) {
			MvcResult required = jsonCall(fx.host(), fx.adminToken(), post(ADMIN),
					valueRule("Bad", "BUNDLE_AMOUNT_OFF", List.of(fx.a(), bad), "ANY", "FIXED", 50));
			assertThat(status(required)).as(rawContent(required)).isEqualTo(422);
			assertThat(errorCode(required)).isEqualTo("DISCOUNT_VALIDATION");
			assertThat(json(required).path("error").path("fieldErrors").toString()).contains("courseIds");
			MvcResult reward = jsonCall(fx.host(), fx.adminToken(), post(ADMIN), with(with(
					rule("Bad free", "BUNDLE_FREE_COURSE", List.of(fx.a())), "rewardCourseIds", List.of(bad)),
					"freeMonths", 1));
			assertThat(status(reward)).as(rawContent(reward)).isEqualTo(422);
			assertThat(json(reward).path("error").path("fieldErrors").toString()).contains("rewardCourseIds");
		}

		Map<String, Object> early = valueRule("Early", "BUNDLE_AMOUNT_OFF", List.of(fx.a()), "ANY", "FIXED", 50);
		early.put("validFrom", "1999-12-31");
		MvcResult tooEarly = jsonCall(fx.host(), fx.adminToken(), post(ADMIN), early);
		assertThat(status(tooEarly)).isEqualTo(422);
		assertThat(json(tooEarly).path("error").path("fieldErrors").toString()).contains("validFrom");
		Map<String, Object> late = valueRule("Late", "BUNDLE_AMOUNT_OFF", List.of(fx.a()), "ANY", "FIXED", 50);
		late.put("validTo", "2101-01-01");
		MvcResult tooLate = jsonCall(fx.host(), fx.adminToken(), post(ADMIN), late);
		assertThat(status(tooLate)).isEqualTo(422);
		assertThat(json(tooLate).path("error").path("fieldErrors").toString()).contains("validTo");

		List<UUID> many = new ArrayList<>();
		for (int i = 0; i < 201; i++) {
			many.add(fx.a());
		}
		MvcResult oversized = jsonCall(fx.host(), fx.adminToken(), post(ADMIN),
				valueRule("Many", "BUNDLE_AMOUNT_OFF", many, "ANY", "FIXED", 50));
		assertThat(status(oversized)).isEqualTo(422);
		assertThat(json(oversized).path("error").path("fieldErrors").toString()).contains("courseIds", "200");
		assertThat(count("SELECT count(*) FROM discount_rule WHERE tenant_id = ?", fx.tenant().getId())).isZero();
	}

	@Test
	void aPutThatFlipsActiveAlsoAuditsTheActivationChange() {
		Fx fx = seed("disc-put-active");
		Map<String, Object> body = valueRule("Toggle", "BUNDLE_AMOUNT_OFF", List.of(fx.a()), "ANY", "FIXED", 50);
		UUID ruleId = createRule(fx, body);
		assertThat(status(jsonCall(fx.host(), fx.adminToken(), put(ADMIN + "/{id}", ruleId),
				with(body, "active", false))))
			.isEqualTo(200);
		assertThat(status(jsonCall(fx.host(), fx.adminToken(), put(ADMIN + "/{id}", ruleId),
				with(body, "active", true))))
			.isEqualTo(200);
		// Same toggle -> no activation audit.
		assertThat(status(jsonCall(fx.host(), fx.adminToken(), put(ADMIN + "/{id}", ruleId),
				with(body, "active", true))))
			.isEqualTo(200);
		List<String> actions = jdbcTemplate.queryForList(
				"SELECT action FROM audit_log WHERE target_id = ? ORDER BY occurred_at, action", String.class, ruleId);
		assertThat(actions).containsExactlyInAnyOrder("discount_rule.created", "discount_rule.updated",
				"discount_rule.deactivated", "discount_rule.updated", "discount_rule.activated",
				"discount_rule.updated");
		String deactivated = jdbcTemplate.queryForObject(
				"SELECT metadata::text FROM audit_log WHERE target_id = ? AND action = 'discount_rule.deactivated'",
				String.class, ruleId);
		assertThat(deactivated).contains("before", "after").containsPattern("\"active\"\\s*:\\s*true")
			.containsPattern("\"active\"\\s*:\\s*false");
	}

	@Test
	void anOrderWhoseRewardMonthsWouldExceedTheLockBoundIsRefused() {
		Fx fx = seed("disc-lock-cap");
		List<UUID> rewards = new ArrayList<>();
		for (int i = 0; i < 11; i++) {
			rewards.add(course(fx, "disc-lock-r" + i, CourseStatus.PUBLIC));
		}
		// 11 reward courses x 24 months = 264 possible reward months > 240.
		UUID huge = createRule(fx, with(with(rule("Huge", "BUNDLE_FREE_COURSE", List.of(fx.a())), "rewardCourseIds",
				rewards), "freeMonths", 24));
		Map<String, Object> body = with(cart(month(fx.a(), current)), "acceptedRewardRuleIds", List.of(huge));
		MvcResult refused = order(fx.host(), fx.studentToken(), body, "0".repeat(64));
		assertThat(status(refused)).as(rawContent(refused)).isEqualTo(422);
		assertThat(errorCode(refused)).isEqualTo("ITEM_NOT_ELIGIBLE");
		assertThat(count("SELECT count(*) FROM student_order WHERE tenant_id = ?", fx.tenant().getId())).isZero();
	}

}
