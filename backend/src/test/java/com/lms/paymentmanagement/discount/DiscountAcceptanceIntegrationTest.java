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
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * DISC-01 acceptance integration tests (docs/qa/klass-discount-module-acceptance.md)
 * for the cases {@link DiscountCheckoutSmokeIntegrationTest} does not cover:
 * A4/A5 advance + bundle selection through the real composer, A7/A8 reward
 * windows and cycles, A9 every code status (incl. another tenant's code), A10
 * concurrent Maximum Uses, A12/A13 facility fee / postage invariance, A15/A16
 * completion amount and entitlements, A17 historical immutability + refund,
 * A18 permission boundaries and quote non-disclosure.
 *
 * <p>Dates are relative to the real current month in UTC (the tenant timezone
 * default); {@code DiscountClock} is not mocked. Every course offers three
 * months: current, next, and the one after.
 */
class DiscountAcceptanceIntegrationTest extends DiscountTestSupport {

	// ------------------------------------------------------------------
	// A4: advance boundary.
	// ------------------------------------------------------------------

	@Test
	void a4_advanceAllAndMinCountUseOnlyFutureMonthLinesOfDistinctCourses() {
		Fx fx = seed("disc-a4-modes");
		UUID all = createRule(fx,
				valueRule("Advance all", "ADVANCE_PAYMENT", List.of(fx.a(), fx.b()), "ALL", "FIXED", 300));

		// B only in the current month -> B has no advance line -> ALL fails.
		JsonNode bCurrent = quoteOk(fx, cart(month(fx.a(), next), month(fx.b(), current)));
		assertThat(dec(bCurrent, "discountTotal")).isEqualByComparingTo("0");
		assertThat(bCurrent.path("discounts")).isEmpty();

		// Both next month -> 300 split 100 / 200 (pro rata 1000 : 2000); the current-month A line is untouched.
		JsonNode bothNext = quoteOk(fx, cart(month(fx.a(), current), month(fx.a(), next), month(fx.b(), next)));
		assertThat(dec(bothNext, "discountTotal")).isEqualByComparingTo("300.00");
		assertThat(dec(bothNext.path("lines").get(0), "discountAmount")).isEqualByComparingTo("0");
		assertThat(dec(bothNext.path("lines").get(1), "discountAmount")).isEqualByComparingTo("100.00");
		assertThat(dec(bothNext.path("lines").get(2), "discountAmount")).isEqualByComparingTo("200.00");
		assertThat(bothNext.path("discounts").get(0).path("type").asString()).isEqualTo("ADVANCE_PAYMENT");
		assertThat(bothNext.path("discounts").get(0).path("lineNos").toString()).isEqualTo("[2,3]");
		assertThat(dec(bothNext, "total")).isEqualByComparingTo("3700.00");

		setActive(fx, all, false);
		Map<String, Object> minCount = valueRule("Advance two of three", "ADVANCE_PAYMENT",
				List.of(fx.a(), fx.b(), fx.c()), "MIN_COUNT", "PERCENTAGE", 10);
		minCount.put("minCount", 2);
		createRule(fx, minCount);

		// Two advance months of A + current B = one course with advance lines -> no discount.
		JsonNode oneCourse = quoteOk(fx, cart(month(fx.a(), next), month(fx.a(), later), month(fx.b(), current)));
		assertThat(dec(oneCourse, "discountTotal")).isEqualByComparingTo("0");

		// A next + B next + C current -> 2 courses; 10% on A next (100) and B next (200), never on C current.
		JsonNode two = quoteOk(fx, cart(month(fx.a(), next), month(fx.b(), next), month(fx.c(), current)));
		assertThat(dec(two, "discountTotal")).isEqualByComparingTo("300.00");
		assertThat(dec(two.path("lines").get(0), "discountAmount")).isEqualByComparingTo("100.00");
		assertThat(dec(two.path("lines").get(1), "discountAmount")).isEqualByComparingTo("200.00");
		assertThat(dec(two.path("lines").get(2), "discountAmount")).isEqualByComparingTo("0");
		assertThat(two.path("lines").get(2).path("discountName").isNull()).isTrue();
	}

	@Test
	void a4_paidAndPendingFutureMonthsNeverCountAsAdvanceLines() {
		Fx fx = seed("disc-a4-paid");
		createRule(fx, valueRule("Advance ten", "ADVANCE_PAYMENT", List.of(fx.a()), "ANY", "PERCENTAGE", 10));

		// Pay A next (discounted), leave A later pending.
		JsonNode paid = placeOrder(fx, cart(month(fx.a(), next)));
		assertThat(dec(paid, "amount")).isEqualByComparingTo("900.00");
		confirmByGateway(fx, id(paid));
		JsonNode pending = placeOrder(fx, cart(month(fx.a(), later)));
		initiatePaymentOrFail(fx.host(), fx.studentToken(), id(pending));

		// The current month alone is never advance.
		JsonNode onlyCurrent = quoteOk(fx, cart(month(fx.a(), current)));
		assertThat(dec(onlyCurrent, "discountTotal")).isEqualByComparingTo("0");
		assertThat(dec(onlyCurrent, "total")).isEqualByComparingTo("1000.00");
		// Paid / pending months cannot be priced (so cannot qualify or receive an advance discount).
		assertThat(errorCode(quote(fx.host(), fx.studentToken(), cart(month(fx.a(), current), month(fx.a(), next)))))
			.isEqualTo("ITEM_ALREADY_PAID");
		assertThat(errorCode(quote(fx.host(), fx.studentToken(), cart(month(fx.a(), current), month(fx.a(), later)))))
			.isEqualTo("ITEM_PENDING");
		// Offers mark them PAID / PENDING.
		JsonNode offers = json(call(fx.host(), fx.studentToken(),
				get("/api/v1/checkout/offers?courseId={courseId}&scope=MONTHLY", fx.a()))).path("data").path("items");
		Map<String, String> states = new LinkedHashMap<>();
		offers.forEach(item -> states.put(item.path("year").asInt() + "-" + item.path("month").asInt(),
				item.path("state").asString()));
		assertThat(states).containsEntry(next.getYear() + "-" + next.getMonthValue(), "PAID")
			.containsEntry(later.getYear() + "-" + later.getMonthValue(), "PENDING")
			.containsEntry(current.getYear() + "-" + current.getMonthValue(), "AVAILABLE");
	}

	// ------------------------------------------------------------------
	// A5: bundle counts distinct courses.
	// ------------------------------------------------------------------

	@Test
	void a5_twoMonthsOfOneCourseDoNotSatisfyATwoCourseBundle() {
		Fx fx = seed("disc-a5");
		Map<String, Object> minTwo = valueRule("Any two", "BUNDLE_AMOUNT_OFF", List.of(fx.a(), fx.b()), "MIN_COUNT",
				"FIXED", 500);
		minTwo.put("minCount", 2);
		createRule(fx, minTwo);
		createRule(fx, valueRule("Both", "BUNDLE_AMOUNT_OFF", List.of(fx.a(), fx.b()), "ALL", "PERCENTAGE", 10));

		JsonNode twoMonths = quoteOk(fx, cart(month(fx.a(), current), month(fx.a(), next), month(fx.a(), later)));
		assertThat(dec(twoMonths, "discountTotal")).isEqualByComparingTo("0");
		assertThat(twoMonths.path("discounts")).isEmpty();

		// Two courses: both rules qualify; 500 fixed beats 10% (300) and no line is stacked.
		JsonNode twoCourses = quoteOk(fx, cart(month(fx.a(), current), month(fx.b(), current)));
		assertThat(dec(twoCourses, "discountTotal")).isEqualByComparingTo("500.00");
		assertThat(twoCourses.path("discounts")).hasSize(1);
		assertThat(twoCourses.path("discounts").get(0).path("name").asString()).isEqualTo("Any two");
	}

	// ------------------------------------------------------------------
	// A7 / A8: reward window, cycles, duplicates.
	// ------------------------------------------------------------------

	@Test
	void a7_a8_rewardCoversExactlyTheWindowNeverCyclesAndNeverDuplicates() {
		Fx fx = seed("disc-a7");
		UUID aGivesC = createRule(fx, with(with(rule("A gives C", "BUNDLE_FREE_COURSE", List.of(fx.a())),
				"rewardCourseIds", List.of(fx.c())), "freeMonths", 2));
		UUID cGivesA = createRule(fx, with(with(rule("C gives A", "BUNDLE_FREE_COURSE", List.of(fx.c())),
				"rewardCourseIds", List.of(fx.a())), "freeMonths", 1));

		// Offer: exactly C current + C next (C later is offered by the course but outside the window).
		JsonNode offered = quoteOk(fx, cart(month(fx.a(), current)));
		assertThat(offered.path("rewardOffers")).hasSize(1);
		assertThat(offered.path("rewardOffers").get(0).path("ruleId").asString()).isEqualTo(aGivesC.toString());
		assertThat(offered.path("rewardOffers").get(0).path("months")).hasSize(2);

		// Accepted (twice in the list, plus the cyclic rule): exactly two C reward lines.
		Map<String, Object> accepted = with(cart(month(fx.a(), current)), "acceptedRewardRuleIds",
				List.of(aGivesC, aGivesC, cGivesA));
		JsonNode quote = quoteOk(fx, accepted);
		assertThat(quote.path("lines")).hasSize(3);
		JsonNode aLine = quote.path("lines").get(0);
		assertThat(aLine.path("courseId").asString()).isEqualTo(fx.a().toString());
		// The C reward lines never qualify "C gives A": A stays a normal paid line.
		assertThat(aLine.path("reward").asBoolean()).isFalse();
		assertThat(dec(aLine, "discountAmount")).isEqualByComparingTo("0");
		Set<String> rewardMonths = new HashSet<>();
		for (int i = 1; i < 3; i++) {
			JsonNode line = quote.path("lines").get(i);
			assertThat(line.path("courseId").asString()).isEqualTo(fx.c().toString());
			assertThat(line.path("reward").asBoolean()).isTrue();
			assertThat(dec(line, "discountAmount")).isEqualByComparingTo("500.00");
			rewardMonths.add(line.path("year").asInt() + "-" + line.path("month").asInt());
		}
		assertThat(rewardMonths).containsExactlyInAnyOrder(current.getYear() + "-" + current.getMonthValue(),
				next.getYear() + "-" + next.getMonthValue());
		assertThat(quote.path("discounts")).hasSize(1);
		assertThat(quote.path("discounts").get(0).path("ruleId").asString()).isEqualTo(aGivesC.toString());
		assertThat(dec(quote, "total")).isEqualByComparingTo("1000.00");

		// C current already selected + accepted: converted in place, only C next added - no duplicate key.
		Map<String, Object> selected = with(cart(month(fx.a(), current), month(fx.c(), current)),
				"acceptedRewardRuleIds", List.of(aGivesC));
		JsonNode converted = quoteOk(fx, selected);
		assertThat(converted.path("lines")).hasSize(3);
		Set<String> keys = new HashSet<>();
		converted.path("lines")
			.forEach(line -> assertThat(keys.add(line.path("courseId").asString() + ":" + line.path("year").asInt()
					+ "-" + line.path("month").asInt())).isTrue());
		assertThat(dec(converted, "total")).isEqualByComparingTo("1000.00");

		JsonNode order = placeOrder(fx, selected);
		UUID orderId = id(order);
		assertThat(count("SELECT count(*) FROM student_order_line WHERE order_id = ? AND reward", orderId))
			.isEqualTo(2);
		assertThat(count("SELECT count(DISTINCT coverage_key) FROM student_order_line WHERE order_id = ?", orderId))
			.isEqualTo(3);
		assertThat(count("SELECT count(*) FROM student_order_line WHERE order_id = ? AND course_id = ? AND reward",
				orderId, fx.a())).isZero();
		assertThat(count("SELECT count(*) FROM student_order_discount WHERE order_id = ?", orderId)).isEqualTo(1);
	}

	// ------------------------------------------------------------------
	// A9: code statuses.
	// ------------------------------------------------------------------

	@Test
	void a9_everyCodeStatusIsReportedWithoutLeakingAndTheCartIsPreserved() {
		Fx fx = seed("disc-a9");
		createRule(fx, codeRule("Ten off", "SAVE10", List.of(fx.a()), "PERCENTAGE", 10));
		Map<String, Object> expired = codeRule("Old", "OLDCODE", List.of(fx.a()), "FIXED", 50);
		expired.put("validFrom", today.minusDays(30).toString());
		expired.put("validTo", today.minusDays(1).toString());
		createRule(fx, expired);
		createRule(fx, with(codeRule("Off", "OFFCODE", List.of(fx.a()), "FIXED", 50), "active", false));
		createRule(fx, codeRule("Needs B", "NEEDB1", List.of(fx.b()), "FIXED", 50));
		createRule(fx, with(codeRule("Big buy", "BIGBUY", List.of(fx.a()), "FIXED", 50), "minPurchaseAmount", 5000));
		Map<String, Object> later = codeRule("Later", "LATER1", List.of(fx.a()), "FIXED", 50);
		later.put("validFrom", today.plusDays(1).toString());
		createRule(fx, later);
		createRule(fx, codeRule("Small", "SMALL1", List.of(fx.c()), "FIXED", 50));
		createRule(fx, valueRule("C bundle", "BUNDLE_AMOUNT_OFF", List.of(fx.c()), "ANY", "FIXED", 300));

		Fx other = seed("disc-a9-other");
		createRule(other, codeRule("Other tenant", "OTHER10", List.of(other.a()), "PERCENTAGE", 10));
		UUID otherFree = createRule(other, with(with(rule("Other free", "BUNDLE_FREE_COURSE", List.of(other.a())),
				"rewardCourseIds", List.of(other.c())), "freeMonths", 1));

		Map<String, Object> aCart = cart(month(fx.a(), current), month(fx.a(), next));
		JsonNode plain = quoteOk(fx, aCart);
		assertThat(dec(plain, "total")).isEqualByComparingTo("2000.00");
		assertThat(plain.path("codeResult").isNull()).isTrue();

		JsonNode applied = quoteOk(fx, with(aCart, "discountCode", "  save10\t"));
		assertThat(applied.path("codeResult").path("status").asString()).isEqualTo("APPLIED");
		assertThat(applied.path("codeResult").path("code").asString()).isEqualTo("SAVE10");
		assertThat(dec(applied, "discountTotal")).isEqualByComparingTo("200.00");

		Map<String, String> expectations = new LinkedHashMap<>();
		expectations.put("NOPE123", "INVALID");
		expectations.put("no pe", "INVALID");
		expectations.put("OTHER10", "INVALID");
		// Owner decision 2026-10-21 ("harden fully"): expired, disabled and
		// not-yet-valid codes are reported exactly like an unknown code.
		expectations.put("oldcode", "INVALID");
		expectations.put("OFFCODE", "INVALID");
		expectations.put("NEEDB1", "NOT_ELIGIBLE");
		expectations.put("BIGBUY", "NOT_ELIGIBLE");
		expectations.put("LATER1", "INVALID");
		// Shorter than the 6-character minimum.
		expectations.put("ABCDE", "INVALID");
		JsonNode unknown = null;
		for (Map.Entry<String, String> expectation : expectations.entrySet()) {
			JsonNode result = quoteOk(fx, with(aCart, "discountCode", expectation.getKey()));
			assertThat(result.path("codeResult").path("status").asString()).as(expectation.getKey())
				.isEqualTo(expectation.getValue());
			// The cart is untouched and undiscounted whatever the code status.
			assertThat(result.path("lines")).as(expectation.getKey()).hasSize(2);
			assertThat(dec(result, "discountTotal")).as(expectation.getKey()).isEqualByComparingTo("0");
			assertThat(dec(result, "total")).as(expectation.getKey()).isEqualByComparingTo("2000.00");
			assertThat(result.path("discounts")).as(expectation.getKey()).isEmpty();
			if ("NOPE123".equals(expectation.getKey())) {
				unknown = result;
			}
			if ("INVALID".equals(expectation.getValue())) {
				// Same message as an unknown code - no way to tell a real but unusable code apart.
				assertThat(result.path("codeResult").path("message").asString()).as(expectation.getKey())
					.isEqualTo(unknown.path("codeResult").path("message").asString());
				assertThat(result.toString()).as(expectation.getKey())
					.doesNotContain("expired", "not active");
			}
			if ("OTHER10".equals(expectation.getKey())) {
				// Another tenant's code looks exactly like an unknown code.
				assertThat(result.path("codeResult").path("message").asString())
					.isEqualTo(unknown.path("codeResult").path("message").asString());
				assertThat(result.toString()).doesNotContain("Other tenant");
			}
		}

		// A bigger bundle on C wins over the code on C.
		JsonNode better = quoteOk(fx, with(cart(month(fx.c(), current)), "discountCode", "small1"));
		assertThat(better.path("codeResult").path("status").asString()).isEqualTo("NOT_APPLIED_BETTER_DISCOUNT");
		assertThat(dec(better, "discountTotal")).isEqualByComparingTo("300.00");
		assertThat(better.path("discounts").get(0).path("name").asString()).isEqualTo("C bundle");

		// Removing the code restores the undiscounted quote, line for line.
		JsonNode removed = quoteOk(fx, aCart);
		assertThat(dec(removed, "total")).isEqualByComparingTo(dec(plain, "total"));
		assertThat(removed.path("lines").toString()).isEqualTo(plain.path("lines").toString());
		assertThat(removed.path("quoteToken").asString()).isEqualTo(plain.path("quoteToken").asString());

		// Another tenant's free-course rule id cannot be accepted here.
		JsonNode foreignReward = quoteOk(fx, with(aCart, "acceptedRewardRuleIds", List.of(otherFree)));
		assertThat(foreignReward.path("lines")).hasSize(2);
		assertThat(dec(foreignReward, "discountTotal")).isEqualByComparingTo("0");
		assertThat(foreignReward.toString()).doesNotContain("Other free");

		// The same on order placement: tenant B's code is INVALID and tenant B's
		// reward rule id is ignored - the order is placed undiscounted.
		Map<String, Object> foreignOrder = with(with(aCart, "discountCode", "OTHER10"), "acceptedRewardRuleIds",
				List.of(otherFree));
		JsonNode foreignQuote = quoteOk(fx, foreignOrder);
		assertThat(foreignQuote.path("codeResult").path("status").asString()).isEqualTo("INVALID");
		JsonNode placed = placeOrder(fx, foreignOrder);
		assertThat(dec(placed, "amount")).isEqualByComparingTo("2000.00");
		assertThat(placed.path("discounts")).isEmpty();
		assertThat(placed.path("lines")).hasSize(2);
		assertThat(count("SELECT count(*) FROM student_order_discount WHERE order_id = ?", id(placed))).isZero();
		assertThat(count("SELECT count(*) FROM student_order_line WHERE order_id = ? AND reward", id(placed)))
			.isZero();
		assertThat(count("SELECT count(*) FROM discount_redemption WHERE order_id = ?", id(placed))).isZero();
		assertThat(count("SELECT count(*) FROM discount_redemption WHERE tenant_id = ?", other.tenant().getId()))
			.isZero();
	}

	// ------------------------------------------------------------------
	// A10: Maximum Uses under concurrency.
	// ------------------------------------------------------------------

	@Test
	void a10_concurrentOrdersNeverExceedMaximumUses() throws Exception {
		Fx fx = seed("disc-a10");
		UUID ruleId = createRule(fx,
				with(codeRule("Only one", "ONLYONE", List.of(fx.a()), "FIXED", 100), "maxUses", 1));
		String secondToken = extraStudent(fx, "student2@example.test");
		Map<String, Object> body = with(cart(month(fx.a(), current)), "discountCode", "ONLYONE");
		List<String> tokens = List.of(fx.studentToken(), secondToken);
		List<String> quoteTokens = new ArrayList<>();
		for (String token : tokens) {
			JsonNode q = quoteOk(fx.host(), token, body);
			assertThat(q.path("codeResult").path("status").asString()).isEqualTo("APPLIED");
			quoteTokens.add(q.path("quoteToken").asString());
		}

		CyclicBarrier barrier = new CyclicBarrier(2);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		List<MvcResult> results = new ArrayList<>();
		try {
			List<Callable<MvcResult>> tasks = new ArrayList<>();
			for (int i = 0; i < 2; i++) {
				String token = tokens.get(i);
				String quoteToken = quoteTokens.get(i);
				tasks.add(() -> {
					barrier.await(30, TimeUnit.SECONDS);
					return order(fx.host(), token, body, quoteToken);
				});
			}
			for (Future<MvcResult> future : executor.invokeAll(tasks, 120, TimeUnit.SECONDS)) {
				results.add(future.get());
			}
		}
		finally {
			executor.shutdownNow();
		}

		List<Integer> statuses = results.stream().map(DiscountAcceptanceIntegrationTest::status).sorted().toList();
		assertThat(statuses).as(results.stream().map(this::rawContent).toList().toString()).containsExactly(201, 409);
		MvcResult winner = results.stream().filter(r -> status(r) == 201).findFirst().orElseThrow();
		MvcResult loser = results.stream().filter(r -> status(r) == 409).findFirst().orElseThrow();
		assertThat(dec(json(winner).path("data"), "amount")).isEqualByComparingTo("900.00");
		assertThat(errorCode(loser)).isEqualTo("QUOTE_CHANGED");
		JsonNode fresh = json(loser).path("error").path("details").path("quote");
		assertThat(fresh.path("codeResult").path("status").asString()).isEqualTo("USAGE_LIMIT_REACHED");
		assertThat(dec(fresh, "total")).isEqualByComparingTo("1000.00");
		assertThat(count("SELECT count(*) FROM discount_redemption WHERE rule_id = ?", ruleId)).isEqualTo(1);

		// The loser can still buy, undiscounted, with the fresh quote; uses never exceed 1.
		String loserToken = tokens.get(results.indexOf(loser));
		MvcResult retry = order(fx.host(), loserToken, body, fresh.path("quoteToken").asString());
		assertThat(status(retry)).as(rawContent(retry)).isEqualTo(201);
		assertThat(dec(json(retry).path("data"), "amount")).isEqualByComparingTo("1000.00");
		assertThat(json(retry).path("data").path("discounts")).isEmpty();
		assertThat(count("SELECT count(*) FROM discount_redemption WHERE rule_id = ?", ruleId)).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM student_order_discount WHERE rule_id = ?", ruleId)).isEqualTo(1);
	}

	// ------------------------------------------------------------------
	// A12 / A13: facility fees and postage are never discounted.
	// ------------------------------------------------------------------

	private void setFee(Fx fx, String mode, Object amount) {
		Map<String, Object> changes = new LinkedHashMap<>();
		changes.put("facility_fee_mode", mode);
		changes.put("facility_fee_amount", amount);
		MvcResult result = jsonCall(fx.host(), fx.adminToken(), put("/api/v1/tenant-config/PAYMENT"), changes);
		assertThat(status(result)).as(rawContent(result)).isEqualTo(200);
	}

	private void patchCourse(Fx fx, UUID courseId, Map<String, Object> body) {
		MvcResult result = jsonCall(fx.host(), fx.adminToken(), patch("/api/v1/courses/{id}", courseId), body);
		assertThat(status(result)).as(rawContent(result)).isEqualTo(200);
	}

	/** Fees, postage and subtotal of {@code discounted} equal {@code baseline}; only the discount differs. */
	private void assertFeesUnchanged(JsonNode baseline, JsonNode discounted, Fx fx) {
		assertThat(dec(discounted, "subtotal")).isEqualByComparingTo(dec(baseline, "subtotal"));
		assertThat(dec(discounted, "facilityFeeTotal")).isEqualByComparingTo(dec(baseline, "facilityFeeTotal"));
		assertThat(dec(discounted, "postageTotal")).isEqualByComparingTo(dec(baseline, "postageTotal"));
		for (int i = 0; i < baseline.path("lines").size(); i++) {
			assertThat(dec(discounted.path("lines").get(i), "facilityFee"))
				.isEqualByComparingTo(dec(baseline.path("lines").get(i), "facilityFee"));
			JsonNode line = discounted.path("lines").get(i);
			assertThat(dec(line, "lineTotal")).isEqualByComparingTo(
					dec(line, "unitAmount").add(dec(line, "facilityFee")).subtract(dec(line, "discountAmount")));
			assertThat(dec(line, "lineTotal")).isGreaterThanOrEqualTo(BigDecimal.ZERO);
		}
		for (UUID course : List.of(fx.a(), fx.b())) {
			assertThat(dec(courseCharge(discounted, course), "facilityFee"))
				.isEqualByComparingTo(dec(courseCharge(baseline, course), "facilityFee"));
			assertThat(dec(courseCharge(discounted, course), "postageCharge"))
				.isEqualByComparingTo(dec(courseCharge(baseline, course), "postageCharge"));
		}
		assertThat(dec(discounted, "total")).isEqualByComparingTo(dec(discounted, "subtotal")
			.add(dec(discounted, "facilityFeeTotal"))
			.add(dec(discounted, "postageTotal"))
			.subtract(dec(discounted, "discountTotal")));
	}

	@Test
	void a12_a13_facilityFeesZeroOverrideAndPostageAreNeverDiscounted() {
		Fx fx = seed("disc-a13");
		setFee(fx, "PER_LINE", 100);
		patchCourse(fx, fx.a(), Map.of("postageFee", 200));
		patchCourse(fx, fx.b(), Map.of("facilityFeeInherit", false, "facilityFeeOverride", 0));
		Map<String, Object> body = with(cart(month(fx.a(), current), month(fx.a(), next), month(fx.b(), current)),
				"postageCourseIds", List.of(fx.a()));

		// PER_LINE: A 2 x 100, B override 0, postage 200.
		JsonNode baseline = quoteOk(fx, body);
		assertThat(dec(baseline, "facilityFeeTotal")).isEqualByComparingTo("200.00");
		assertThat(dec(baseline, "postageTotal")).isEqualByComparingTo("200.00");
		assertThat(dec(baseline, "total")).isEqualByComparingTo("4400.00");
		UUID half = createRule(fx,
				valueRule("Half", "BUNDLE_AMOUNT_OFF", List.of(fx.a(), fx.b()), "ANY", "PERCENTAGE", 50));
		JsonNode discounted = quoteOk(fx, body);
		assertThat(dec(discounted, "discountTotal")).isEqualByComparingTo("2000.00");
		assertFeesUnchanged(baseline, discounted, fx);
		assertThat(dec(discounted, "total")).isEqualByComparingTo("2400.00");

		// PER_COURSE: A 100 once, B 0.
		setFee(fx, "PER_COURSE", 100);
		setActive(fx, half, false);
		JsonNode perCourseBaseline = quoteOk(fx, body);
		assertThat(dec(perCourseBaseline, "facilityFeeTotal")).isEqualByComparingTo("100.00");
		setActive(fx, half, true);
		JsonNode perCourse = quoteOk(fx, body);
		assertFeesUnchanged(perCourseBaseline, perCourse, fx);
		assertThat(dec(perCourse, "total")).isEqualByComparingTo("2300.00");

		// A12: a fixed amount far above the eligible total is capped at B's unit amount; never negative.
		setActive(fx, half, false);
		createRule(fx, valueRule("Huge", "BUNDLE_AMOUNT_OFF", List.of(fx.b()), "ANY", "FIXED", 999999));
		JsonNode capped = quoteOk(fx, body);
		assertFeesUnchanged(perCourseBaseline, capped, fx);
		assertThat(dec(capped, "discountTotal")).isEqualByComparingTo("2000.00");
		assertThat(dec(capped.path("lines").get(2), "lineTotal")).isEqualByComparingTo("0");
		setActive(fx, half, true);

		// The placed order snapshots order.amount = subtotal + fees + postage - discount.
		JsonNode order = placeOrder(fx, body);
		UUID orderId = id(order);
		BigDecimal expected = dec(order, "subtotal").add(dec(order, "facilityFeeTotal"))
			.add(dec(order, "postageTotal"))
			.subtract(dec(order, "discountTotal"));
		assertThat(dec(order, "amount")).isEqualByComparingTo(expected);
		assertThat(dec(order, "facilityFeeTotal")).isEqualByComparingTo("100.00");
		assertThat(dec(order, "postageTotal")).isEqualByComparingTo("200.00");
		assertThat(jdbcTemplate.queryForObject(
				"SELECT subtotal_amount + facility_fee_amount + postage_amount - discount_amount - amount "
						+ "FROM student_order WHERE id = ?",
				BigDecimal.class, orderId))
			.isEqualByComparingTo("0");
		assertThat(jdbcTemplate.queryForObject("SELECT amount FROM student_order WHERE id = ?", BigDecimal.class,
				orderId))
			.isEqualByComparingTo(expected);
		assertThat(count("SELECT count(*) FROM student_order_line WHERE order_id = ? AND (discount_amount > "
				+ "unit_amount OR line_total < 0)", orderId)).isZero();
	}

	// ------------------------------------------------------------------
	// A15 / A16: completion amount and entitlements.
	// ------------------------------------------------------------------

	private Set<UUID> orderLineIds(UUID orderId) {
		return new HashSet<>(
				jdbcTemplate.queryForList("SELECT id FROM student_order_line WHERE order_id = ?", UUID.class, orderId));
	}

	private Set<UUID> entitledLineIds(UUID orderId) {
		return new HashSet<>(jdbcTemplate.queryForList(
				"SELECT order_line_id FROM content_entitlement WHERE order_id = ?", UUID.class, orderId));
	}

	@Test
	void a15_a16_gatewayChargesTheDiscountedAmountAndOnlyConfirmationGrantsExactlyTheOrderLines() {
		Fx fx = seed("disc-a15");
		UUID tenantId = fx.tenant().getId();
		createRule(fx, valueRule("Ten off", "BUNDLE_AMOUNT_OFF", List.of(fx.a(), fx.b()), "ANY", "PERCENTAGE", 10));
		UUID free = createRule(fx, with(with(rule("Free C", "BUNDLE_FREE_COURSE", List.of(fx.a())),
				"rewardCourseIds", List.of(fx.c())), "freeMonths", 1));

		JsonNode order = placeOrder(fx,
				with(cart(month(fx.a(), current)), "acceptedRewardRuleIds", List.of(free)));
		UUID orderId = id(order);
		assertThat(dec(order, "amount")).isEqualByComparingTo("900.00");
		assertThat(order.path("lines")).hasSize(2);

		// Gateway initiation charges order.amount; PENDING grants nothing.
		PaymentInitiationResponse first = initiatePaymentOrFail(fx.host(), fx.studentToken(), orderId);
		assertThat(jdbcTemplate.queryForObject("SELECT amount FROM payment WHERE id = ?", BigDecimal.class,
				first.paymentId()))
			.isEqualByComparingTo("900.00");
		assertThat(entitledLineIds(orderId)).isEmpty();
		assertThat(launch(fx, fx.a(), 0)).isNotEqualTo(200);
		assertThat(launch(fx, fx.c(), 0)).isNotEqualTo(200);

		// REJECTED grants nothing.
		assertThat(sendPaymentWebhook(first.gatewayReference(), false).getStatusCode().value()).isEqualTo(200);
		assertThat(count("SELECT count(*) FROM payment WHERE id = ? AND status = 'REJECTED'", first.paymentId()))
			.isEqualTo(1);
		assertThat(entitledLineIds(orderId)).isEmpty();
		assertThat(count("SELECT count(*) FROM ledger_entry WHERE order_id = ?", orderId)).isZero();

		// Confirmation grants exactly the order's lines (incl. the reward line), nothing else.
		UUID paymentId = confirmByGateway(fx, orderId);
		assertThat(jdbcTemplate.queryForObject("SELECT amount FROM payment WHERE id = ?", BigDecimal.class,
				paymentId))
			.isEqualByComparingTo("900.00");
		assertThat(jdbcTemplate.queryForObject("SELECT amount FROM ledger_entry WHERE payment_id = ?",
				BigDecimal.class, paymentId))
			.isEqualByComparingTo("900.00");
		assertThat(entitledLineIds(orderId)).isEqualTo(orderLineIds(orderId));
		assertThat(count("SELECT count(*) FROM content_entitlement WHERE tenant_id = ?", tenantId)).isEqualTo(2);
		assertThat(launch(fx, fx.a(), 0)).isEqualTo(200);
		assertThat(launch(fx, fx.c(), 0)).isEqualTo(200);
		assertThat(launch(fx, fx.a(), 1)).isNotEqualTo(200);
		assertThat(launch(fx, fx.c(), 1)).isNotEqualTo(200);

		// Manual path: a submitted then rejected slip on a discounted order grants nothing; the hold restarts.
		// ("Ten off" is used up for this student - one use per student per rule - so B gets its own rule.)
		createRule(fx, valueRule("B ten off", "BUNDLE_AMOUNT_OFF", List.of(fx.b()), "ANY", "PERCENTAGE", 10));
		JsonNode slipOrder = placeOrder(fx, cart(month(fx.b(), current)));
		UUID slipOrderId = id(slipOrder);
		assertThat(dec(slipOrder, "amount")).isEqualByComparingTo("1800.00");
		PaymentSlipResponse slip = uploadSlipOrFail(fx.host(), fx.studentToken(), slipOrderId, "REF-DISC-A16",
				pdfFile("slip.pdf", distinctPdfBytes("disc-a16")));
		assertThat(slip.orderAmount()).isEqualByComparingTo("1800.00");
		assertThat(entitledLineIds(slipOrderId)).isEmpty();
		assertThat(rejectSlip(fx.host(), fx.financeToken(), slip.id(), "Amount does not match").getStatusCode()
			.value()).isEqualTo(200);
		assertThat(entitledLineIds(slipOrderId)).isEmpty();
		assertThat(launch(fx, fx.b(), 0)).isNotEqualTo(200);
		assertThat(count("SELECT count(*) FROM discount_redemption WHERE order_id = ? AND status = 'RESERVED' "
				+ "AND expires_at > now() + interval '47 hours'", slipOrderId)).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM content_entitlement WHERE tenant_id = ?", tenantId)).isEqualTo(2);
	}

	// ------------------------------------------------------------------
	// A17: history is immutable; refunds use the paid amount.
	// ------------------------------------------------------------------

	private String orderSnapshot(UUID orderId) {
		List<Map<String, Object>> rows = new ArrayList<>();
		rows.addAll(jdbcTemplate.queryForList("SELECT amount, subtotal_amount, facility_fee_amount, discount_amount, "
				+ "postage_amount, currency FROM student_order WHERE id = ?", orderId));
		rows.addAll(jdbcTemplate.queryForList("SELECT * FROM student_order_line WHERE order_id = ? ORDER BY line_no",
				orderId));
		rows.addAll(jdbcTemplate.queryForList("SELECT * FROM student_order_discount WHERE order_id = ?", orderId));
		return rows.toString();
	}

	@Test
	void a17_ruleEditDisableAndArchiveNeverChangeAPlacedOrderAndRefundsUseThePaidAmount() {
		Fx fx = seed("disc-a17");
		Map<String, Object> code = codeRule("Ten off", "HIST10", List.of(fx.a()), "PERCENTAGE", 10);
		UUID ruleId = createRule(fx, code);
		JsonNode order = placeOrder(fx, with(cart(month(fx.a(), current), month(fx.a(), next)), "discountCode",
				"HIST10"));
		UUID orderId = id(order);
		assertThat(dec(order, "amount")).isEqualByComparingTo("1800.00");
		String before = orderSnapshot(orderId);
		UUID versionBefore = jdbcTemplate.queryForObject(
				"SELECT rule_version_id FROM student_order_discount WHERE order_id = ?", UUID.class, orderId);

		// Edit (new value) and disable BEFORE paying: payment still charges the snapshot.
		code.put("value", 50);
		MvcResult edited = jsonCall(fx.host(), fx.adminToken(), put(ADMIN + "/{id}", ruleId), code);
		assertThat(status(edited)).as(rawContent(edited)).isEqualTo(200);
		setActive(fx, ruleId, false);
		assertThat(orderSnapshot(orderId)).isEqualTo(before);
		UUID paymentId = confirmByGateway(fx, orderId);
		assertThat(jdbcTemplate.queryForObject("SELECT amount FROM payment WHERE id = ?", BigDecimal.class,
				paymentId))
			.isEqualByComparingTo("1800.00");

		// Archive after paying.
		assertThat(status(call(fx.host(), fx.adminToken(), delete(ADMIN + "/{id}", ruleId)))).isEqualTo(204);
		assertThat(orderSnapshot(orderId)).isEqualTo(before);
		assertThat(jdbcTemplate.queryForObject("SELECT rule_version_id FROM student_order_discount WHERE order_id = ?",
				UUID.class, orderId)).isEqualTo(versionBefore);
		assertThat(count("SELECT count(*) FROM discount_rule_version WHERE rule_id = ?", ruleId)).isEqualTo(2);

		// Refunds are bounded by the PAID (discounted) amount, never the list price.
		var tooMuch = createRefund(fx.host(), fx.adminToken(), paymentId, new BigDecimal("2000.00"), "Full refund");
		assertThat(tooMuch.getStatusCode().value()).isEqualTo(409);
		var refund = createRefund(fx.host(), fx.adminToken(), paymentId, new BigDecimal("1800.00"), "Full refund");
		assertThat(refund.getStatusCode().is2xxSuccessful()).as(String.valueOf(refund.getBody())).isTrue();
		assertThat(jdbcTemplate.queryForObject(
				"SELECT amount FROM ledger_entry WHERE payment_id = ? AND entry_type = 'REFUND'", BigDecimal.class,
				paymentId).abs())
			.isEqualByComparingTo("1800.00");
		assertThat(count("SELECT count(*) FROM payment WHERE id = ? AND status = 'CONFIRMED' AND amount = 1800",
				paymentId)).isEqualTo(1);
		assertThat(orderSnapshot(orderId)).isEqualTo(before);
		// A refund never restores a use.
		assertThat(count("SELECT count(*) FROM discount_redemption WHERE order_id = ? AND status = 'CONSUMED'",
				orderId)).isEqualTo(1);
	}

	// ------------------------------------------------------------------
	// A18: permission boundaries and non-disclosure.
	// ------------------------------------------------------------------

	@Test
	void a18_studentsAndViewOnlyStaffCannotWriteRulesAndQuotesDiscloseNoOtherRuleConfig() {
		Fx fx = seed("disc-a18");
		seedTenantUser(fx.tenant().getId(), "support@example.test", RAW_PASSWORD, Role.STUDENT_SUPPORT);
		seedTenantUser(fx.tenant().getId(), "content@example.test", RAW_PASSWORD, Role.CONTENT_MANAGER);
		String supportToken = loginAndGetToken(fx.host(), "support@example.test");
		String contentToken = loginAndGetToken(fx.host(), "content@example.test");
		Map<String, Object> applied = with(codeRule("Applied code", "SHOWN10", List.of(fx.a()), "PERCENTAGE", 10),
				"maxUses", 7);
		UUID appliedId = createRule(fx, applied);
		Map<String, Object> hidden = valueRule("Hidden bundle", "BUNDLE_AMOUNT_OFF", List.of(fx.b()), "ANY", "FIXED",
				123);
		hidden.put("maxUses", 5);
		hidden.put("minPurchaseAmount", 98765);
		hidden.put("description", "internal-only-description");
		UUID hiddenId = createRule(fx, hidden);
		Map<String, Object> hiddenCode = codeRule("Hidden code", "HIDDEN99", List.of(fx.a()), "FIXED", 999);
		UUID hiddenCodeId = createRule(fx, with(hiddenCode, "minPurchaseAmount", 99999));

		// Student: no admin endpoint at all.
		for (MockHttpServletRequestBuilder request : List.of(get(ADMIN), get(ADMIN + "/{id}", appliedId),
				get(ADMIN + "/course-options"), delete(ADMIN + "/{id}", appliedId))) {
			assertThat(status(call(fx.host(), fx.studentToken(), request))).isEqualTo(403);
		}
		assertThat(status(jsonCall(fx.host(), fx.studentToken(), post(ADMIN),
				codeRule("Mine", "MINE10", List.of(fx.a()), "PERCENTAGE", 100))))
			.isEqualTo(403);
		assertThat(status(jsonCall(fx.host(), fx.studentToken(), put(ADMIN + "/{id}", appliedId), applied)))
			.isEqualTo(403);
		assertThat(status(jsonCall(fx.host(), fx.studentToken(), patch(ADMIN + "/{id}/active", appliedId),
				Map.of("active", false))))
			.isEqualTo(403);

		// View-only staff (DISCOUNTS VIEW, no CREATE_EDIT): reads yes, writes no.
		assertThat(status(call(fx.host(), supportToken, get(ADMIN)))).isEqualTo(200);
		assertThat(status(call(fx.host(), supportToken, get(ADMIN + "/{id}", appliedId)))).isEqualTo(200);
		assertThat(status(jsonCall(fx.host(), supportToken, post(ADMIN),
				codeRule("Support", "SUPPORT1", List.of(fx.a()), "PERCENTAGE", 100))))
			.isEqualTo(403);
		assertThat(status(jsonCall(fx.host(), supportToken, put(ADMIN + "/{id}", appliedId), with(applied, "value", 99))))
			.isEqualTo(403);
		assertThat(status(jsonCall(fx.host(), supportToken, patch(ADMIN + "/{id}/active", appliedId),
				Map.of("active", false))))
			.isEqualTo(403);
		assertThat(status(call(fx.host(), supportToken, delete(ADMIN + "/{id}", appliedId)))).isEqualTo(403);
		// Staff without DISCOUNTS at all cannot even read.
		assertThat(status(call(fx.host(), contentToken, get(ADMIN)))).isEqualTo(403);
		assertThat(status(call(fx.host(), contentToken, get(ADMIN + "/{id}", appliedId)))).isEqualTo(403);

		// Nothing changed: one version, still active, not archived, no extra rule.
		assertThat(count("SELECT count(*) FROM discount_rule_version WHERE rule_id = ?", appliedId)).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM discount_rule WHERE id = ? AND active AND archived_at IS NULL",
				appliedId)).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM discount_rule WHERE tenant_id = ?", fx.tenant().getId())).isEqualTo(3);

		// The quote shows only the applied rule's own student-visible terms.
		JsonNode quote = quoteOk(fx, with(cart(month(fx.a(), current), month(fx.b(), current)), "discountCode",
				"shown10"));
		assertThat(quote.path("codeResult").path("status").asString()).isEqualTo("APPLIED");
		String raw = quote.toString();
		assertThat(raw).doesNotContain("maxUses", "usedCount", "minPurchase", "validFrom", "validTo", "98765",
				"99999", "internal-only-description", "Hidden code", "HIDDEN99", hiddenCodeId.toString());
		// The bundle on B legitimately applies (it is visible as an applied discount), but its limits are not.
		for (JsonNode discount : quote.path("discounts")) {
			assertThat(discount.has("maxUses")).isFalse();
			assertThat(discount.has("minPurchaseAmount")).isFalse();
			assertThat(discount.has("description")).isFalse();
		}
		assertThat(raw).contains(appliedId.toString());
		// The bundle on B is unapplied (its minimum purchase is not met): neither its id nor its name leaks.
		assertThat(raw).doesNotContain(hiddenId.toString(), "Hidden bundle");
		assertThat(quote.path("discounts")).hasSize(1);
	}

}
