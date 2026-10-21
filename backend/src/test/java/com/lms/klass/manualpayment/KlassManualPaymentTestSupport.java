package com.lms.klass.manualpayment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.paymentmanagement.SlipTestSupport;
import com.lms.tenantmanagement.domain.Tenant;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * Shared fixture for the ADR-030 Klass manual payment / Free Card
 * Testcontainers acceptance suites (docs/plans/klass-manual-payment-free-card.md
 * §3-§4). Every test seeds fresh tenants (manual_payment_receipt and free_card
 * reject UPDATE/DELETE by trigger, so nothing is cleaned up in place).
 *
 * <p>Months are relative to the real clock (tenant timezone = UTC default):
 * {@code PREV}, {@code CUR}, {@code NEXT}. The fixture course is MONTHLY
 * 3000.00 with one COVERED_BY_CLASS_FEES lesson per month plus one PAID
 * lesson (750.00); the student self-enrolled (free participation, ADR-028).
 *
 * <p>Not a test class (name does not match Surefire's patterns).
 */
abstract class KlassManualPaymentTestSupport extends SlipTestSupport {

	static final YearMonth CUR = YearMonth.now(ZoneOffset.UTC);

	static final YearMonth PREV = CUR.minusMonths(1);

	static final YearMonth NEXT = CUR.plusMonths(1);

	static final String MONTHLY_PRICE = "3000.00";

	record Fx(Tenant tenant, String host, TenantUser admin, String adminToken, TenantUser finance,
			String financeToken, TenantUser teacher, String teacherToken, TenantUser student, UUID studentProfileId,
			String studentToken, UUID course, UUID moduleId, UUID prevLesson, UUID curLesson, UUID nextLesson,
			UUID paidLesson) {

		UUID tenantId() {
			return tenant.getId();
		}

	}

	Fx seed(String prefix) {
		Tenant tenant = seedActiveTenant(uniqueSubdomain(prefix));
		TenantUser admin = seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser finance = seedTenantUser(tenant.getId(), "finance@example.test", RAW_PASSWORD,
				Role.FINANCE_STAFF);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		TenantUser student = seedActiveStudent(tenant.getId(), "student@example.test");
		UUID profile = seedProfile(tenant.getId(), student.getId(), "Nimal Silva");
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		UUID course = createCourse(host, adminToken, teacher.getId(), "ICT " + prefix);
		UUID moduleId = createModuleOrFail(host, adminToken, course, "ICT Module", 1).id();
		UUID prev = coveredLesson(host, adminToken, course, moduleId, "Prev month class", 1, PREV);
		UUID cur = coveredLesson(host, adminToken, course, moduleId, "This month class", 2, CUR);
		UUID next = coveredLesson(host, adminToken, course, moduleId, "Next month class", 3, NEXT);
		UUID paid = createLessonOrFail(host, adminToken, course, moduleId, "Paper class", 4).id();
		jdbcTemplate.update("UPDATE course_lesson SET payment_type = 'PAID', price = 750.00 WHERE id = ?", paid);
		String studentToken = loginAndGetToken(host, "student@example.test");
		Fx fx = new Fx(tenant, host, admin, adminToken, finance, loginAndGetToken(host, "finance@example.test"),
				teacher, loginAndGetToken(host, "teacher@example.test"), student, profile, studentToken, course,
				moduleId, prev, cur, next, paid);
		MvcResult enrolled = selfEnroll(host, studentToken, course);
		assertThat(status(enrolled)).as(rawContent(enrolled)).isEqualTo(200);
		return fx;
	}

	UUID seedProfile(UUID tenantId, UUID userId, String name) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO student_profile (id, tenant_id, user_id, name, created_at, updated_at) "
				+ "VALUES (?, ?, ?, ?, now(), now())", id, tenantId, userId, name);
		return id;
	}

	/** Seeds another active student of the fixture tenant with a profile; returns {userId, profileId}, token. */
	record ExtraStudent(TenantUser user, UUID profileId, String token) {

	}

	ExtraStudent extraStudent(Fx fx, String email) {
		TenantUser user = seedActiveStudent(fx.tenantId(), email);
		UUID profile = seedProfile(fx.tenantId(), user.getId(), "Extra " + email);
		return new ExtraStudent(user, profile, loginAndGetToken(fx.host(), email));
	}

	UUID createCourse(String host, String token, UUID teacherId, String name, Object... extra) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("name", name);
		body.put("teacherIds", List.of(teacherId));
		body.put("pricingModel", "MONTHLY");
		body.put("price", MONTHLY_PRICE);
		body.put("status", "PUBLIC");
		for (int i = 0; i < extra.length; i += 2) {
			body.put((String) extra[i], extra[i + 1]);
		}
		MvcResult result = send(host, token, post("/api/v1/courses"), body);
		assertThat(status(result)).as(rawContent(result)).isEqualTo(201);
		return UUID.fromString(json(result).path("data").path("id").asString());
	}

	UUID coveredLesson(String host, String token, UUID course, UUID moduleId, String title, int seq,
			YearMonth month) {
		UUID id = createLessonOrFail(host, token, course, moduleId, title, seq).id();
		jdbcTemplate.update("UPDATE course_lesson SET payment_type = 'COVERED_BY_CLASS_FEES', billing_year = ?, "
				+ "billing_month = ? WHERE id = ?", month.getYear(), month.getMonthValue(), id);
		return id;
	}

	// ------------------------------------------------------------------
	// HTTP helpers.
	// ------------------------------------------------------------------

	MvcResult call(String host, String token, MockHttpServletRequestBuilder builder) {
		return perform(authenticated(builder, host, token));
	}

	MvcResult send(String host, String token, MockHttpServletRequestBuilder builder, Object body) {
		builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
		return call(host, token, builder);
	}

	JsonNode json(MvcResult result) {
		return objectMapper.readTree(rawContent(result));
	}

	JsonNode data(MvcResult result) {
		return json(result).path("data");
	}

	static int status(MvcResult result) {
		return result.getResponse().getStatus();
	}

	String errorCode(MvcResult result) {
		return json(result).path("error").path("code").asString();
	}

	long count(String sql, Object... args) {
		Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
		return value == null ? 0 : value;
	}

	static LocalDate today() {
		return LocalDate.now(ZoneOffset.UTC);
	}

	static Map<String, Object> monthItem(UUID courseId, YearMonth month) {
		Map<String, Object> item = new LinkedHashMap<>();
		item.put("courseId", courseId);
		item.put("scope", "MONTHLY");
		item.put("year", month.getYear());
		item.put("month", month.getMonthValue());
		return item;
	}

	static Map<String, Object> lessonItem(UUID courseId, UUID lessonId) {
		Map<String, Object> item = new LinkedHashMap<>();
		item.put("courseId", courseId);
		item.put("scope", "LESSON");
		item.put("lessonId", lessonId);
		return item;
	}

	static String monthlyKey(UUID courseId, YearMonth month) {
		return "MONTHLY:" + courseId + ":" + month;
	}

	MvcResult selfEnroll(String host, String token, UUID courseId) {
		return call(host, token, post("/api/v1/enrollments/my/courses/{courseId}", courseId));
	}

	// ---- staff manual payment endpoints ----

	String staffBase(UUID studentProfileId) {
		return "/api/v1/staff/students/" + studentProfileId;
	}

	MvcResult paymentContext(String host, String token, UUID profileId) {
		return call(host, token, get(staffBase(profileId) + "/payment-context"));
	}

	MvcResult paymentOffers(String host, String token, UUID profileId, UUID courseId, String scope) {
		return call(host, token,
				get(staffBase(profileId) + "/payment-offers?courseId=" + courseId + "&scope=" + scope));
	}

	MvcResult paymentHistory(String host, String token, UUID profileId, UUID courseId) {
		return call(host, token, get(staffBase(profileId) + "/payment-history?courseId=" + courseId));
	}

	MvcResult staffQuote(String host, String token, UUID profileId, List<Map<String, Object>> items,
			List<UUID> postage) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("items", items);
		body.put("postageCourseIds", postage);
		return send(host, token, post(staffBase(profileId) + "/manual-payments/quote"), body);
	}

	/** Builder for a record request; {@link #submit} fetches a fresh quote token unless one is set. */
	final class Receipt {

		final Map<String, Object> body = new LinkedHashMap<>();

		final String host;

		final String token;

		final UUID profileId;

		boolean autoQuote = true;

		Receipt(String host, String token, UUID profileId, List<Map<String, Object>> items) {
			this.host = host;
			this.token = token;
			this.profileId = profileId;
			body.put("items", items);
			body.put("postageCourseIds", List.of());
			body.put("staffAdjustmentAmount", "0");
			body.put("paymentMethod", "CASH");
			body.put("receivedOn", today().toString());
			body.put("referenceNumber", "RCPT-" + UUID.randomUUID().toString().substring(0, 8));
			body.put("notes", "Paid at the front desk");
			body.put("idempotencyKey", UUID.randomUUID());
		}

		Receipt with(String key, Object value) {
			body.put(key, value);
			if ("quoteToken".equals(key)) {
				autoQuote = false;
			}
			return this;
		}

		Receipt expected(String amount) {
			return with("expectedReceivedAmount", amount);
		}

		Receipt key(UUID key) {
			return with("idempotencyKey", key);
		}

		/** Takes a quote now (fills quoteToken, and expectedReceivedAmount when absent = quote total). */
		Receipt quoteNow() {
			MvcResult quote = staffQuote(host, token, profileId, items(), postage());
			if (status(quote) == 200) {
				JsonNode q = data(quote);
				body.put("quoteToken", q.path("quoteToken").asString());
				body.putIfAbsent("expectedReceivedAmount", q.path("total").decimalValue()
					.subtract(new BigDecimal(body.get("staffAdjustmentAmount").toString()))
					.toPlainString());
			}
			autoQuote = false;
			return this;
		}

		@SuppressWarnings("unchecked")
		private List<Map<String, Object>> items() {
			return (List<Map<String, Object>>) body.get("items");
		}

		@SuppressWarnings("unchecked")
		private List<UUID> postage() {
			return (List<UUID>) body.get("postageCourseIds");
		}

		MvcResult submit() {
			if (autoQuote) {
				quoteNow();
			}
			return send(host, token, post(staffBase(profileId) + "/manual-payments"), body);
		}

	}

	Receipt receipt(Fx fx, String token, List<Map<String, Object>> items) {
		return new Receipt(fx.host(), token, fx.studentProfileId(), items);
	}

	JsonNode recordOrFail(Fx fx, String token, List<Map<String, Object>> items) {
		MvcResult result = receipt(fx, token, items).submit();
		assertThat(status(result)).as(rawContent(result)).isEqualTo(201);
		return data(result);
	}

	// ---- free cards ----

	MvcResult issueCard(String host, String token, UUID profileId, UUID courseId, String type, LocalDate from,
			LocalDate to, UUID key) {
		Map<String, Object> body = new HashMap<>();
		body.put("studentId", profileId);
		body.put("courseId", courseId);
		body.put("cardType", type);
		body.put("validFrom", from == null ? null : from.toString());
		body.put("validTo", to == null ? null : to.toString());
		body.put("reason", "Scholarship - needy student");
		body.put("idempotencyKey", key);
		return send(host, token, post("/api/v1/free-cards"), body);
	}

	JsonNode issueMonthCardOrFail(Fx fx, YearMonth month) {
		MvcResult result = issueCard(fx.host(), fx.adminToken(), fx.studentProfileId(), fx.course(), "FULL",
				month.atDay(1), month.atEndOfMonth(), UUID.randomUUID());
		assertThat(status(result)).as(rawContent(result)).isEqualTo(201);
		return data(result);
	}

	MvcResult revokeCard(String host, String token, UUID cardId, String reason) {
		Map<String, Object> body = new HashMap<>();
		body.put("reason", reason);
		return send(host, token, post("/api/v1/free-cards/{id}/revoke", cardId), body);
	}

	MvcResult listCards(String host, String token, String query) {
		return call(host, token, get("/api/v1/free-cards" + (query == null ? "" : "?" + query)));
	}

	// ---- access ----

	MvcResult launch(String host, String token, UUID courseId, UUID lessonId) {
		return call(host, token, post("/api/v1/courses/{courseId}/lessons/{lessonId}/launch", courseId, lessonId));
	}

	String launchCode(String host, String token, UUID courseId, UUID lessonId) {
		MvcResult result = launch(host, token, courseId, lessonId);
		return status(result) == 200 ? "ALLOWED" : json(result).path("code").asString();
	}

	String launchCode(Fx fx, UUID lessonId) {
		return launchCode(fx.host(), fx.studentToken(), fx.course(), lessonId);
	}

	Map<String, JsonNode> tree(String host, String token, UUID courseId) {
		MvcResult result = call(host, token, get("/api/v1/courses/{courseId}/content", courseId));
		assertThat(status(result)).as(rawContent(result)).isEqualTo(200);
		Map<String, JsonNode> nodes = new LinkedHashMap<>();
		data(result).path("periods").forEach(period -> period.path("modules")
			.forEach(module -> module.path("lessons").forEach(l -> nodes.put(l.path("lessonId").asString(), l))));
		return nodes;
	}

	String treeStatus(Fx fx, UUID lessonId) {
		return tree(fx.host(), fx.studentToken(), fx.course()).get(lessonId.toString())
			.path("access")
			.path("status")
			.asString();
	}

	/** Tree status and launch code must agree (acceptance M). */
	String access(Fx fx, UUID lessonId) {
		String tree = treeStatus(fx, lessonId);
		String launch = launchCode(fx, lessonId);
		assertThat(launch).as("launch agrees with content tree for " + lessonId).isEqualTo(tree);
		return tree;
	}

	void setFacilityFee(Fx fx, String mode, Object amount) {
		Map<String, Object> changes = new LinkedHashMap<>();
		changes.put("facility_fee_mode", mode);
		changes.put("facility_fee_amount", amount);
		MvcResult result = send(fx.host(), fx.adminToken(), put("/api/v1/tenant-config/PAYMENT"), changes);
		assertThat(status(result)).as(rawContent(result)).isEqualTo(200);
	}

	void setPostage(Fx fx, UUID courseId, Object fee) {
		MvcResult result = send(fx.host(), fx.adminToken(), patch("/api/v1/courses/{id}", courseId),
				Map.of("postageFee", fee));
		assertThat(status(result)).as(rawContent(result)).isEqualTo(200);
	}

	// ---- DB snapshots ----

	/** Counts of every financial row a manual receipt / free card could write, for "nothing persisted" checks. */
	Map<String, Long> financialCounts(UUID tenantId) {
		Map<String, Long> counts = new LinkedHashMap<>();
		for (String table : List.of("student_order", "student_order_line", "student_order_course_charge", "payment",
				"ledger_entry", "manual_payment_receipt", "content_entitlement", "payment_refund")) {
			counts.put(table, count("SELECT count(*) FROM " + table + " WHERE tenant_id = ?", tenantId));
		}
		counts.put("audit:manual_payment.recorded", count(
				"SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'manual_payment.recorded'", tenantId));
		return counts;
	}

	String rowSnapshot(String table, UUID id) {
		return jdbcTemplate.queryForObject("SELECT md5(t::text) FROM " + table + " t WHERE t.id = ?", String.class, id);
	}

}
