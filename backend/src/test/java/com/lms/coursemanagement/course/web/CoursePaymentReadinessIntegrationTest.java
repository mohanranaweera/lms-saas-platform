package com.lms.coursemanagement.course.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.lms.coursemanagement.CourseManagementTestSupport;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.tenantmanagement.domain.Tenant;
import java.util.ArrayList;
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
 * {@code GET /api/v1/courses/{id}/payment-readiness}: one test per issue code,
 * a clean payable course, FREE never warning, cross-tenant 404, and a teacher
 * of another course / a Student being denied.
 */
class CoursePaymentReadinessIntegrationTest extends CourseManagementTestSupport {

	private record Ctx(Tenant tenant, String host, String token, TenantUser teacher, TenantUser teacher2) {

	}

	private Ctx tenantWithAdmin(String prefix) {
		Tenant tenant = seedActiveTenant(uniqueSubdomain(prefix));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		TenantUser teacher2 = seedTenantUser(tenant.getId(), "teacher2@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		return new Ctx(tenant, host, loginAndGetToken(host, "admin@example.test"), teacher, teacher2);
	}

	private MvcResult send(String host, String token, MockHttpServletRequestBuilder builder, Object body) {
		if (body != null) {
			builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
		}
		return perform(authenticated(builder, host, token));
	}

	private MvcResult send(Ctx ctx, MockHttpServletRequestBuilder builder, Object body) {
		return send(ctx.host(), ctx.token(), builder, body);
	}

	private JsonNode json(MvcResult result) {
		return objectMapper.readTree(rawContent(result));
	}

	private static int status(MvcResult result) {
		return result.getResponse().getStatus();
	}

	private String createCourse(Ctx ctx, String name, String pricingModel, String price) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("name", name);
		body.put("teacherIds", List.of(ctx.teacher().getId()));
		body.put("pricingModel", pricingModel);
		if (price != null) {
			body.put("price", price);
		}
		MvcResult result = send(ctx, post("/api/v1/courses"), body);
		assertThat(status(result)).as(rawContent(result)).isEqualTo(201);
		return json(result).path("data").path("id").asString();
	}

	private String createModule(Ctx ctx, String courseId, String title, String price) {
		Map<String, Object> body = new HashMap<>();
		body.put("title", title);
		body.put("price", price);
		MvcResult result = send(ctx, post("/api/v1/courses/" + courseId + "/modules"), body);
		assertThat(status(result)).as(rawContent(result)).isEqualTo(201);
		return json(result).path("data").path("id").asString();
	}

	private String createLesson(Ctx ctx, String courseId, String moduleId, Map<String, Object> body) {
		MvcResult result = send(ctx, post("/api/v1/courses/" + courseId + "/modules/" + moduleId + "/lessons"), body);
		assertThat(status(result)).as(rawContent(result)).isEqualTo(201);
		return json(result).path("data").path("id").asString();
	}

	private MvcResult readiness(String host, String token, String courseId) {
		return send(host, token, get("/api/v1/courses/" + courseId + "/payment-readiness"), null);
	}

	private JsonNode readinessOk(Ctx ctx, String courseId) {
		MvcResult result = readiness(ctx.host(), ctx.token(), courseId);
		assertThat(status(result)).as(rawContent(result)).isEqualTo(200);
		return json(result).path("data");
	}

	private static List<String> codes(JsonNode readiness) {
		List<String> codes = new ArrayList<>();
		readiness.path("issues").forEach(issue -> codes.add(issue.path("code").asString()));
		return codes;
	}

	private static JsonNode issue(JsonNode readiness, String code) {
		for (JsonNode issue : readiness.path("issues")) {
			if (code.equals(issue.path("code").asString())) {
				return issue;
			}
		}
		throw new AssertionError("No issue " + code + " in " + readiness);
	}

	private static Map<String, Object> coveredSession(String title, String startsAt) {
		return Map.of("title", title, "startsAt", startsAt, "paymentType", "COVERED_BY_CLASS_FEES");
	}

	@Test
	void payableMonthlyCourseHasNoIssues() {
		Ctx ctx = tenantWithAdmin("ready-ok");
		String course = createCourse(ctx, "Monthly OK", "MONTHLY", "4000.00");
		String module = createModule(ctx, course, "Term 1", null);
		createLesson(ctx, course, module, coveredSession("Week 1", "2026-10-07T10:00:00Z"));

		JsonNode readiness = readinessOk(ctx, course);
		assertThat(readiness.path("pricingModel").asString()).isEqualTo("MONTHLY");
		assertThat(readiness.path("payable").asBoolean()).isTrue();
		assertThat(readiness.path("issues")).isEmpty();
	}

	@Test
	void freeCourseNeverWarns() {
		Ctx ctx = tenantWithAdmin("ready-free");
		String course = createCourse(ctx, "Free", "FREE", null);
		JsonNode readiness = readinessOk(ctx, course);
		assertThat(readiness.path("payable").asBoolean()).isTrue();
		assertThat(readiness.path("issues")).isEmpty();
	}

	@Test
	void monthlySessionsSavedBeforeTheSwitchHaveNoBillingMonth() {
		Ctx ctx = tenantWithAdmin("ready-month");
		String course = createCourse(ctx, "Monthly", "MONTHLY", "4000.00");
		String module = createModule(ctx, course, "Term 1", null);
		String l1 = createLesson(ctx, course, module, coveredSession("Week 1", "2026-10-07T10:00:00Z"));
		String l2 = createLesson(ctx, course, module, coveredSession("Week 2", "2026-10-14T10:00:00Z"));
		// As if saved while the course was still ONE_TIME (no billing month derived).
		jdbcTemplate.update("UPDATE course_lesson SET billing_year = NULL, billing_month = NULL WHERE id IN (?, ?)",
				UUID.fromString(l1), UUID.fromString(l2));

		JsonNode readiness = readinessOk(ctx, course);
		assertThat(readiness.path("payable").asBoolean()).isFalse();
		assertThat(codes(readiness)).containsExactly("MONTHLY_SESSIONS_WITHOUT_MONTH");
		JsonNode issue = issue(readiness, "MONTHLY_SESSIONS_WITHOUT_MONTH");
		assertThat(issue.path("count").asInt()).isEqualTo(2);
		assertThat(issue.path("message").asString()).startsWith("2 sessions").contains("save it again");
	}

	@Test
	void monthlyCourseWithoutDatedCoveredSessionsHasNothingToBill() {
		Ctx ctx = tenantWithAdmin("ready-nobill");
		String course = createCourse(ctx, "Monthly", "MONTHLY", "4000.00");
		String module = createModule(ctx, course, "Term 1", null);
		createLesson(ctx, course, module, Map.of("title", "Undated", "paymentType", "COVERED_BY_CLASS_FEES"));

		JsonNode readiness = readinessOk(ctx, course);
		assertThat(readiness.path("payable").asBoolean()).isFalse();
		assertThat(codes(readiness)).containsExactly("MONTHLY_NO_BILLABLE_SESSIONS");
		assertThat(issue(readiness, "MONTHLY_NO_BILLABLE_SESSIONS").has("count")).isFalse();
	}

	@Test
	void monthlyCourseWithoutAnOpenBillingPeriodHasNoAmount() {
		Ctx ctx = tenantWithAdmin("ready-noamt");
		String course = createCourse(ctx, "Monthly", "MONTHLY", "4000.00");
		String module = createModule(ctx, course, "Term 1", null);
		createLesson(ctx, course, module, coveredSession("Week 1", "2026-10-07T10:00:00Z"));
		jdbcTemplate.update("UPDATE course_billing_period SET effective_to = effective_from + interval '1 second' "
				+ "WHERE effective_to IS NULL AND billing_configuration_id IN "
				+ "(SELECT id FROM course_billing_configuration WHERE course_id = ?)", UUID.fromString(course));

		JsonNode readiness = readinessOk(ctx, course);
		assertThat(readiness.path("payable").asBoolean()).isFalse();
		assertThat(codes(readiness)).containsExactly("MONTHLY_NO_AMOUNT");
	}

	@Test
	void modulePricedCourseWithoutAPricedModuleOrPaidSession() {
		Ctx ctx = tenantWithAdmin("ready-module");
		String course = createCourse(ctx, "Modules", "MODULE", null);
		String module = createModule(ctx, course, "Unit 1", null);
		createLesson(ctx, course, module, coveredSession("Session 1", "2026-10-07T10:00:00Z"));

		JsonNode readiness = readinessOk(ctx, course);
		assertThat(readiness.path("pricingModel").asString()).isEqualTo("MODULE");
		assertThat(readiness.path("payable").asBoolean()).isFalse();
		assertThat(codes(readiness)).containsExactly("MODULE_NO_PRICED_MODULE");

		// A Paid session with its own price is a LESSON offer - payable again.
		createLesson(ctx, course, module, Map.of("title", "Paid extra", "paymentType", "PAID", "price", "750.00"));
		JsonNode after = readinessOk(ctx, course);
		assertThat(after.path("payable").asBoolean()).isTrue();
		assertThat(after.path("issues")).isEmpty();

		String priced = createCourse(ctx, "Modules priced", "MODULE", null);
		createModule(ctx, priced, "Unit 1", "1500.00");
		assertThat(readinessOk(ctx, priced).path("payable").asBoolean()).isTrue();
	}

	@Test
	void oneTimeCourseWithoutAPrice() {
		Ctx ctx = tenantWithAdmin("ready-onetime");
		String course = createCourse(ctx, "Full", "ONE_TIME", "100.00");
		assertThat(readinessOk(ctx, course).path("payable").asBoolean()).isTrue();
		jdbcTemplate.update("UPDATE course SET price = 0 WHERE id = ?", UUID.fromString(course));

		JsonNode readiness = readinessOk(ctx, course);
		assertThat(readiness.path("payable").asBoolean()).isFalse();
		assertThat(codes(readiness)).containsExactly("NO_COURSE_PRICE");
	}

	@Test
	void legacySessionCourseWithoutABillingPeriod() {
		Ctx ctx = tenantWithAdmin("ready-session");
		String course = createCourse(ctx, "Legacy", "ONE_TIME", "100.00");
		jdbcTemplate.update("UPDATE course SET pricing_model = 'SESSION' WHERE id = ?", UUID.fromString(course));

		JsonNode readiness = readinessOk(ctx, course);
		assertThat(readiness.path("pricingModel").asString()).isEqualTo("SESSION");
		assertThat(readiness.path("payable").asBoolean()).isFalse();
		assertThat(codes(readiness)).containsExactly("SESSION_NO_AMOUNT");
	}

	@Test
	void dailyCourseHasNoCheckoutUnlessSomethingIsPriced() {
		Ctx ctx = tenantWithAdmin("ready-daily");
		String course = createCourse(ctx, "Daily", "DAILY", null);
		JsonNode readiness = readinessOk(ctx, course);
		assertThat(readiness.path("payable").asBoolean()).isFalse();
		assertThat(codes(readiness)).containsExactly("NO_CHECKOUT_FOR_PRICING_MODEL");
	}

	@Test
	@Tag("cross-tenant")
	void anotherTenantsCourseIsNotFound() {
		Ctx a = tenantWithAdmin("ready-xt-a");
		Ctx b = tenantWithAdmin("ready-xt-b");
		String courseB = createCourse(b, "Tenant B", "MONTHLY", "4000.00");

		MvcResult result = readiness(a.host(), a.token(), courseB);
		assertThat(status(result)).isEqualTo(404);
		assertThat(rawContent(result)).doesNotContain("MONTHLY_");
	}

	@Test
	void teacherOfAnotherCourseAndStudentsAreDenied() {
		Ctx ctx = tenantWithAdmin("ready-authz");
		String course = createCourse(ctx, "Owned by teacher", "MONTHLY", "4000.00");
		// PUBLIC, so the public-course VIEW shortcut cannot open it either.
		jdbcTemplate.update("UPDATE course SET status = 'PUBLIC' WHERE id = ?", UUID.fromString(course));

		String owner = loginAndGetToken(ctx.host(), "teacher@example.test");
		assertThat(status(readiness(ctx.host(), owner, course))).isEqualTo(200);

		String otherTeacher = loginAndGetToken(ctx.host(), "teacher2@example.test");
		assertThat(status(readiness(ctx.host(), otherTeacher, course))).isEqualTo(403);

		seedTenantUser(ctx.tenant().getId(), "student@example.test", RAW_PASSWORD, Role.STUDENT);
		String student = loginAndGetToken(ctx.host(), "student@example.test");
		assertThat(status(readiness(ctx.host(), student, course))).isIn(403, 404);
	}

}
