package com.lms.coursemanagement.course.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

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
 * KLASS-T4a Testcontainers/MockMvc coverage for the Klass admin course,
 * module and session flows: create validation, multi-teacher, tags, linked
 * courses (self/nesting/cross-tenant), registration toggle audit,
 * idempotent create, list search/archived/order, module/lesson reorder and
 * session validation codes, delete-in-use, and cross-tenant 404s.
 */
class CourseKlassAdminIntegrationTest extends CourseManagementTestSupport {

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

	private MvcResult send(Ctx ctx, MockHttpServletRequestBuilder builder, Object body) {
		if (body != null) {
			builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
		}
		return perform(authenticated(builder, ctx.host(), ctx.token()));
	}

	private JsonNode json(MvcResult result) {
		return objectMapper.readTree(rawContent(result));
	}

	private static int status(MvcResult result) {
		return result.getResponse().getStatus();
	}

	private static String code(JsonNode node) {
		return node.path("error").path("code").asString();
	}

	private Map<String, Object> courseBody(String name, Object... extra) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("name", name);
		for (int i = 0; i < extra.length; i += 2) {
			body.put((String) extra[i], extra[i + 1]);
		}
		return body;
	}

	private JsonNode createOk(Ctx ctx, Map<String, Object> body) {
		MvcResult result = send(ctx, post("/api/v1/courses"), body);
		assertThat(status(result)).as(rawContent(result)).isEqualTo(201);
		return json(result).path("data");
	}

	private Map<String, Object> monthlyCourse(Ctx ctx, String name) {
		return courseBody(name, "teacherIds", List.of(ctx.teacher().getId()), "pricingModel", "MONTHLY", "price",
				"4000.00");
	}

	// ------------------------------------------------------------------
	// Create / edit
	// ------------------------------------------------------------------

	@Test
	void createWithKlassFieldsPersistsTeachersTagsPriceDisplayAndDerivedSlug() {
		Ctx ctx = tenantWithAdmin("klass-create");
		JsonNode course = createOk(ctx,
				courseBody("  2027 A/L Biology Theory  ", "teacherIds",
						List.of(ctx.teacher2().getId(), ctx.teacher().getId()), "pricingModel", "MONTHLY", "price",
						"4000.00", "enrollmentFee", "500.00", "paymentValidDays", 30, "commissionRate", "12.50",
						"whatsappInviteUrl", "https://chat.whatsapp.com/abc", "description", "About",
						"tagNames", List.of(" Physics ", "physics", "Combined   Maths")));

		assertThat(course.path("name").asString()).isEqualTo("2027 A/L Biology Theory");
		assertThat(course.path("slug").asString()).isEqualTo("2027-a-l-biology-theory");
		assertThat(course.path("category").asString()).isEqualTo("General");
		assertThat(course.path("teacherId").asString()).isEqualTo(ctx.teacher2().getId().toString());
		assertThat(course.path("teachers")).hasSize(2);
		assertThat(course.path("teachers").get(0).path("id").asString()).isEqualTo(ctx.teacher2().getId().toString());
		assertThat(course.path("teachers").get(0).path("primary").asBoolean()).isTrue();
		assertThat(course.path("pricingModel").asString()).isEqualTo("MONTHLY");
		assertThat(course.path("priceDisplay").path("amount").decimalValue()).isEqualByComparingTo("4000.00");
		assertThat(course.path("priceDisplay").path("period").asString()).isEqualTo("MONTH");
		assertThat(course.path("enrollmentFee").decimalValue()).isEqualByComparingTo("500.00");
		assertThat(course.path("paymentValidDays").asInt()).isEqualTo(30);
		assertThat(course.path("commissionRate").decimalValue()).isEqualByComparingTo("12.50");
		assertThat(course.path("registrationEnabled").asBoolean()).isTrue();
		assertThat(course.path("timezone").asString()).isNotBlank();
		List<String> tagNames = new ArrayList<>();
		course.path("tags").forEach(tag -> tagNames.add(tag.path("name").asString()));
		assertThat(tagNames).containsExactlyInAnyOrder("Physics", "Combined Maths");

		MvcResult tags = send(ctx, get("/api/v1/course-tags"), null);
		assertThat(status(tags)).isEqualTo(200);
		assertThat(json(tags).path("data")).hasSize(2);

		Long auditRows = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'course.created' AND target_id = ?",
				Long.class, ctx.tenant().getId(), UUID.fromString(course.path("id").asString()));
		assertThat(auditRows).isEqualTo(1L);
	}

	@Test
	void secondCourseReusesExistingTenantTagsCaseInsensitively() {
		Ctx ctx = tenantWithAdmin("klass-tags");
		createOk(ctx, courseBody("A", "teacherIds", List.of(ctx.teacher().getId()), "pricingModel", "FREE",
				"tagNames", List.of("Biology")));
		createOk(ctx, courseBody("B", "teacherIds", List.of(ctx.teacher().getId()), "pricingModel", "FREE",
				"tagNames", List.of("BIOLOGY", "Chemistry")));

		Long tagCount = jdbcTemplate.queryForObject("SELECT count(*) FROM course_tag WHERE tenant_id = ?", Long.class,
				ctx.tenant().getId());
		assertThat(tagCount).isEqualTo(2L);
	}

	@Test
	void createValidationErrorsForNameTeacherAndPricing() {
		Ctx ctx = tenantWithAdmin("klass-create-invalid");
		UUID teacher = ctx.teacher().getId();

		assertThat(status(send(ctx, post("/api/v1/courses"), courseBody("   ", "teacherIds", List.of(teacher),
				"pricingModel", "FREE")))).isEqualTo(400);
		MvcResult noTeacher = send(ctx, post("/api/v1/courses"), courseBody("No teacher", "pricingModel", "FREE"));
		assertThat(status(noTeacher)).isEqualTo(400);
		MvcResult session = send(ctx, post("/api/v1/courses"),
				courseBody("Session", "teacherIds", List.of(teacher), "pricingModel", "SESSION"));
		assertThat(status(session)).isEqualTo(400);
		assertThat(code(json(session))).isEqualTo("PRICING_MODEL_NOT_SELECTABLE");
		MvcResult monthlyNoPrice = send(ctx, post("/api/v1/courses"),
				courseBody("Monthly", "teacherIds", List.of(teacher), "pricingModel", "MONTHLY"));
		assertThat(status(monthlyNoPrice)).isEqualTo(400);
		MvcResult freeWithPrice = send(ctx, post("/api/v1/courses"),
				courseBody("Free", "teacherIds", List.of(teacher), "pricingModel", "FREE", "price", "10.00"));
		assertThat(code(json(freeWithPrice))).isEqualTo("PRICE_NOT_ALLOWED");
		assertThat(status(send(ctx, post("/api/v1/courses"), courseBody("Bad url", "teacherIds", List.of(teacher),
				"pricingModel", "FREE", "telegramInviteUrl", "http://t.me/x")))).isEqualTo(400);
		assertThat(status(send(ctx, post("/api/v1/courses"), courseBody("Bad commission", "teacherIds",
				List.of(teacher), "pricingModel", "FREE", "commissionRate", "100.01")))).isEqualTo(400);

		Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM course WHERE tenant_id = ?", Long.class,
				ctx.tenant().getId());
		assertThat(count).isZero();
	}

	@Test
	@Tag("cross-tenant")
	void crossTenantTeacherIdAndLinkedCourseIdAreRejected() {
		Ctx a = tenantWithAdmin("klass-xt-a");
		Ctx b = tenantWithAdmin("klass-xt-b");
		JsonNode courseB = createOk(b, monthlyCourse(b, "Tenant B course"));

		MvcResult foreignTeacher = send(a, post("/api/v1/courses"),
				courseBody("X", "teacherIds", List.of(a.teacher().getId(), b.teacher().getId()), "pricingModel", "FREE"));
		assertThat(status(foreignTeacher)).isEqualTo(400);

		MvcResult foreignLink = send(a, post("/api/v1/courses"), courseBody("Y", "teacherIds",
				List.of(a.teacher().getId()), "pricingModel", "FREE", "linkedCourseIds",
				List.of(courseB.path("id").asString())));
		assertThat(status(foreignLink)).isEqualTo(404);

		Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM course WHERE tenant_id = ?", Long.class,
				a.tenant().getId());
		assertThat(count).isZero();
	}

	@Test
	void selfAndNestedLinksAreRejectedAndOneLevelLinksAreAccepted() {
		Ctx ctx = tenantWithAdmin("klass-links");
		JsonNode a = createOk(ctx, monthlyCourse(ctx, "A"));
		JsonNode b = createOk(ctx, monthlyCourse(ctx, "B"));
		JsonNode c = createOk(ctx, monthlyCourse(ctx, "C"));
		String aId = a.path("id").asString();

		MvcResult self = send(ctx, patch("/api/v1/courses/" + aId),
				courseBody("A", "linkedCourseIds", List.of(aId)));
		assertThat(status(self)).isEqualTo(400);
		assertThat(code(json(self))).isEqualTo("COURSE_LINK_INVALID");

		MvcResult ok = send(ctx, patch("/api/v1/courses/" + aId),
				courseBody("A", "linkedCourseIds", List.of(b.path("id").asString())));
		assertThat(status(ok)).as(rawContent(ok)).isEqualTo(200);
		assertThat(json(ok).path("data").path("linkedCourses").get(0).path("title").asString()).isEqualTo("B");

		// C -> A would make A (a parent) a source: two levels.
		MvcResult nestedViaParent = send(ctx, patch("/api/v1/courses/" + c.path("id").asString()),
				courseBody("C", "linkedCourseIds", List.of(aId)));
		assertThat(code(json(nestedViaParent))).isEqualTo("COURSE_LINK_INVALID");
		// B (a source) -> C would make B a parent: two levels.
		MvcResult nestedViaSource = send(ctx, patch("/api/v1/courses/" + b.path("id").asString()),
				courseBody("B", "linkedCourseIds", List.of(c.path("id").asString())));
		assertThat(code(json(nestedViaSource))).isEqualTo("COURSE_LINK_INVALID");

		MvcResult cleared = send(ctx, patch("/api/v1/courses/" + aId), courseBody("A", "linkedCourseIds", List.of()));
		assertThat(json(cleared).path("data").path("linkedCourses")).isEmpty();
	}

	@Test
	void editChangesCommissionAppendOnlyAndAuditsIt() {
		Ctx ctx = tenantWithAdmin("klass-commission");
		JsonNode course = createOk(ctx, courseBody("Course", "teacherIds", List.of(ctx.teacher().getId()),
				"pricingModel", "ONE_TIME", "price", "100.00", "commissionRate", "10.00"));
		String id = course.path("id").asString();

		send(ctx, patch("/api/v1/courses/" + id), courseBody("Course", "commissionRate", "10.00"));
		MvcResult changed = send(ctx, patch("/api/v1/courses/" + id),
				courseBody("Course", "commissionRate", "15.00", "price", "120.00"));
		assertThat(json(changed).path("data").path("commissionRate").decimalValue()).isEqualByComparingTo("15.00");
		assertThat(json(changed).path("data").path("price").decimalValue()).isEqualByComparingTo("120.00");

		Long rows = jdbcTemplate.queryForObject("SELECT count(*) FROM course_commission_rate WHERE course_id = ?",
				Long.class, UUID.fromString(id));
		assertThat(rows).isEqualTo(2L);
		Long audits = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM audit_log WHERE action = 'course.commission_rate_changed' AND target_id = ?",
				Long.class, UUID.fromString(id));
		assertThat(audits).isEqualTo(1L);
		Long priceHistory = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM course_price_history WHERE course_id = ?", Long.class, UUID.fromString(id));
		assertThat(priceHistory).isEqualTo(1L);
	}

	@Test
	void changingThePrimaryTeacherIsTenantAdminOnly() {
		Ctx ctx = tenantWithAdmin("klass-primary");
		seedTenantUser(ctx.tenant().getId(), "coordinator@example.test", RAW_PASSWORD, Role.COURSE_COORDINATOR);
		String coordinator = loginAndGetToken(ctx.host(), "coordinator@example.test");
		JsonNode course = createOk(ctx, monthlyCourse(ctx, "Primary"));
		String id = course.path("id").asString();
		Ctx asCoordinator = new Ctx(ctx.tenant(), ctx.host(), coordinator, ctx.teacher(), ctx.teacher2());

		MvcResult coTeacher = send(asCoordinator, patch("/api/v1/courses/" + id),
				courseBody("Primary", "teacherIds", List.of(ctx.teacher().getId(), ctx.teacher2().getId())));
		assertThat(status(coTeacher)).as(rawContent(coTeacher)).isEqualTo(200);
		MvcResult swapPrimary = send(asCoordinator, patch("/api/v1/courses/" + id),
				courseBody("Primary", "teacherIds", List.of(ctx.teacher2().getId())));
		assertThat(status(swapPrimary)).isEqualTo(403);
		MvcResult adminSwap = send(ctx, patch("/api/v1/courses/" + id),
				courseBody("Primary", "teacherIds", List.of(ctx.teacher2().getId())));
		assertThat(json(adminSwap).path("data").path("teacherId").asString())
			.isEqualTo(ctx.teacher2().getId().toString());
	}

	@Test
	void sameIdempotencyKeyFromTheSameActorReturnsTheFirstCourse() {
		Ctx ctx = tenantWithAdmin("klass-idem");
		Map<String, Object> body = monthlyCourse(ctx, "Idempotent");
		String key = UUID.randomUUID().toString();

		MvcResult first = perform(authenticated(post("/api/v1/courses").header("Idempotency-Key", key)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(body)), ctx.host(), ctx.token()));
		MvcResult second = perform(authenticated(post("/api/v1/courses").header("Idempotency-Key", key)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(body)), ctx.host(), ctx.token()));

		assertThat(status(first)).isEqualTo(201);
		assertThat(status(second)).isEqualTo(201);
		assertThat(json(second).path("data").path("id").asString())
			.isEqualTo(json(first).path("data").path("id").asString());
		Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM course WHERE tenant_id = ?", Long.class,
				ctx.tenant().getId());
		assertThat(count).isEqualTo(1L);
	}

	// ------------------------------------------------------------------
	// Registration toggle, list, order
	// ------------------------------------------------------------------

	@Test
	void registrationToggleIsPersistedAndAudited() {
		Ctx ctx = tenantWithAdmin("klass-registration");
		String id = createOk(ctx, monthlyCourse(ctx, "Reg")).path("id").asString();

		MvcResult off = send(ctx, patch("/api/v1/courses/" + id + "/registration"), Map.of("enabled", false));
		assertThat(status(off)).isEqualTo(200);
		assertThat(json(off).path("data").path("registrationEnabled").asBoolean()).isFalse();

		Long audits = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM audit_log WHERE action = 'course.registration_changed' AND target_id = ?",
				Long.class, UUID.fromString(id));
		assertThat(audits).isEqualTo(1L);
		Boolean stored = jdbcTemplate.queryForObject("SELECT registration_enabled FROM course WHERE id = ?",
				Boolean.class, UUID.fromString(id));
		assertThat(stored).isFalse();
	}

	@Test
	@Tag("cross-tenant")
	void crossTenantRegistrationToggleReturns404() {
		Ctx a = tenantWithAdmin("klass-reg-xt-a");
		Ctx b = tenantWithAdmin("klass-reg-xt-b");
		String idA = createOk(a, monthlyCourse(a, "A")).path("id").asString();

		assertThat(status(send(b, patch("/api/v1/courses/" + idA + "/registration"), Map.of("enabled", false))))
			.isEqualTo(404);
	}

	@Test
	void listSupportsArchivedSearchAndPersistedSubsetOrder() {
		Ctx ctx = tenantWithAdmin("klass-list");
		String physics = createOk(ctx, monthlyCourse(ctx, "Physics 2027")).path("id").asString();
		String chemistry = createOk(ctx, monthlyCourse(ctx, "Chemistry 2027")).path("id").asString();
		String biology = createOk(ctx, monthlyCourse(ctx, "Biology 2027")).path("id").asString();
		send(ctx, post("/api/v1/courses/" + chemistry + "/archive"), null);

		JsonNode active = json(send(ctx, get("/api/v1/courses?archived=false"), null)).path("data").path("content");
		assertThat(ids(active)).containsExactly(physics, biology);
		JsonNode archived = json(send(ctx, get("/api/v1/courses?archived=true"), null)).path("data").path("content");
		assertThat(ids(archived)).containsExactly(chemistry);
		JsonNode search = json(send(ctx, get("/api/v1/courses?archived=false&search=PHYS"), null)).path("data")
			.path("content");
		assertThat(ids(search)).containsExactly(physics);

		MvcResult reorder = send(ctx, put("/api/v1/courses/order"), Map.of("courseIds", List.of(biology, physics)));
		assertThat(status(reorder)).isEqualTo(204);
		JsonNode reordered = json(send(ctx, get("/api/v1/courses?archived=false"), null)).path("data")
			.path("content");
		assertThat(ids(reordered)).containsExactly(biology, physics);
	}

	@Test
	@Tag("cross-tenant")
	void courseReorderRejectsForeignAndDuplicateIds() {
		Ctx a = tenantWithAdmin("klass-order-xt-a");
		Ctx b = tenantWithAdmin("klass-order-xt-b");
		String idA = createOk(a, monthlyCourse(a, "A")).path("id").asString();
		String idB = createOk(b, monthlyCourse(b, "B")).path("id").asString();

		assertThat(status(send(a, put("/api/v1/courses/order"), Map.of("courseIds", List.of(idA, idB)))))
			.isEqualTo(404);
		assertThat(status(send(a, put("/api/v1/courses/order"), Map.of("courseIds", List.of(idA, idA)))))
			.isEqualTo(400);
	}

	// ------------------------------------------------------------------
	// Modules and sessions
	// ------------------------------------------------------------------

	private String createModule(Ctx ctx, String courseId, String title, Integer sequence, String price) {
		Map<String, Object> body = new HashMap<>();
		body.put("title", title);
		body.put("sequence", sequence);
		body.put("price", price);
		MvcResult result = send(ctx, post("/api/v1/courses/" + courseId + "/modules"), body);
		assertThat(status(result)).as(rawContent(result)).isEqualTo(201);
		return json(result).path("data").path("id").asString();
	}

	private MvcResult createLesson(Ctx ctx, String courseId, String moduleId, Map<String, Object> body) {
		return send(ctx, post("/api/v1/courses/" + courseId + "/modules/" + moduleId + "/lessons"), body);
	}

	@Test
	void modulePriceAndReorderRejectingForeignIds() {
		Ctx ctx = tenantWithAdmin("klass-modules");
		String courseId = createOk(ctx, monthlyCourse(ctx, "Course")).path("id").asString();
		String otherCourse = createOk(ctx, monthlyCourse(ctx, "Other")).path("id").asString();
		String m1 = createModule(ctx, courseId, "Recordings", 1, "1500.00");
		String m2 = createModule(ctx, courseId, "Live", null, null);
		String foreign = createModule(ctx, otherCourse, "Foreign", 1, null);

		MvcResult foreignReorder = send(ctx, put("/api/v1/courses/" + courseId + "/modules/order"),
				Map.of("moduleIds", List.of(m2, foreign)));
		assertThat(status(foreignReorder)).isEqualTo(400);
		assertThat(code(json(foreignReorder))).isEqualTo("REORDER_MISMATCH");
		assertThat(status(send(ctx, put("/api/v1/courses/" + courseId + "/modules/order"),
				Map.of("moduleIds", List.of(m2))))).isEqualTo(400);

		MvcResult ok = send(ctx, put("/api/v1/courses/" + courseId + "/modules/order"),
				Map.of("moduleIds", List.of(m2, m1)));
		assertThat(status(ok)).isEqualTo(200);
		JsonNode modules = json(ok).path("data");
		assertThat(modules.get(0).path("id").asString()).isEqualTo(m2);
		assertThat(modules.get(0).path("sequence").asInt()).isEqualTo(1);
		assertThat(modules.get(1).path("price").decimalValue()).isEqualByComparingTo("1500.00");

		Map<String, Object> zeroPrice = new HashMap<>();
		zeroPrice.put("title", "Bad");
		zeroPrice.put("price", "0.00");
		assertThat(status(send(ctx, post("/api/v1/courses/" + courseId + "/modules"), zeroPrice))).isEqualTo(400);
	}

	@Test
	void sessionValidationCodesExpiredFlagReorderAndPriceAudit() {
		Ctx ctx = tenantWithAdmin("klass-sessions");
		String courseId = createOk(ctx, monthlyCourse(ctx, "Course")).path("id").asString();
		String moduleId = createModule(ctx, courseId, "Module", 1, null);

		Map<String, Object> endsBefore = new HashMap<>(Map.of("title", "S", "startsAt", "2026-10-07T16:20:00Z",
				"endsAt", "2026-10-07T16:00:00Z"));
		assertThat(code(json(createLesson(ctx, courseId, moduleId, endsBefore)))).isEqualTo("ENDS_BEFORE_START");
		assertThat(code(json(createLesson(ctx, courseId, moduleId, Map.of("title", "S", "paymentType", "PAID")))))
			.isEqualTo("PRICE_REQUIRED_FOR_PAID");
		assertThat(code(json(createLesson(ctx, courseId, moduleId,
				Map.of("title", "S", "paymentType", "FREE", "price", "10.00"))))).isEqualTo("PRICE_NOT_ALLOWED");
		assertThat(code(json(createLesson(ctx, courseId, moduleId,
				Map.of("title", "S", "resourceType", "ZOOM_RECORDING"))))).isEqualTo("RESOURCE_TYPE_UNSUPPORTED");
		MvcResult paid = createLesson(ctx, courseId, moduleId,
				Map.of("title", "Extra Class - October 7", "startsAt", "2020-10-07T16:20:00Z", "endsAt",
						"2020-10-31T16:20:00Z", "resourceType", "VIDEO_LINK", "paymentType", "PAID", "price",
						"750.00", "lessonLabel", "Week 1", "billingYear", 2099, "billingMonth", 12));
		assertThat(status(paid)).as(rawContent(paid)).isEqualTo(201);
		JsonNode lesson = json(paid).path("data");
		assertThat(lesson.path("expired").asBoolean()).isTrue();
		assertThat(lesson.path("paymentType").asString()).isEqualTo("PAID");
		assertThat(lesson.path("billingMonth").isNull()).isTrue();
		assertThat(lesson.path("billingYear").isNull()).isTrue();
		String l1 = lesson.path("id").asString();
		MvcResult covered = createLesson(ctx, courseId, moduleId, Map.of("title", "Covered"));
		JsonNode coveredLesson = json(covered).path("data");
		assertThat(coveredLesson.path("paymentType").asString()).isEqualTo("COVERED_BY_CLASS_FEES");
		assertThat(coveredLesson.path("sequence").asInt()).isEqualTo(2);
		assertThat(coveredLesson.path("expired").asBoolean()).isFalse();
		String l2 = coveredLesson.path("id").asString();

		// Edit: PAID -> FREE is an audited price change.
		MvcResult edited = send(ctx, patch("/api/v1/courses/" + courseId + "/modules/" + moduleId + "/lessons/" + l1),
				Map.of("title", "Extra Class", "paymentType", "FREE"));
		assertThat(status(edited)).isEqualTo(200);
		Long audits = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM audit_log WHERE action = 'course_lesson.price_changed' AND target_id = ?",
				Long.class, UUID.fromString(l1));
		assertThat(audits).isEqualTo(2L);

		MvcResult reorder = send(ctx,
				put("/api/v1/courses/" + courseId + "/modules/" + moduleId + "/lessons/order"),
				Map.of("lessonIds", List.of(l2, l1)));
		assertThat(status(reorder)).isEqualTo(200);
		assertThat(json(reorder).path("data").get(0).path("id").asString()).isEqualTo(l2);
		assertThat(status(send(ctx, put("/api/v1/courses/" + courseId + "/modules/" + moduleId + "/lessons/order"),
				Map.of("lessonIds", List.of(l2, UUID.randomUUID().toString()))))).isEqualTo(400);
	}

	@Test
	void monthlyCoveredSessionDerivesTenantLocalMonthAndIgnoresClientBillingFields() {
		Ctx ctx = tenantWithAdmin("klass-derived-month");
		// UTC is still January, but this instant is February in Asia/Colombo.
		MvcResult config = send(ctx, put("/api/v1/tenant-config/GENERAL"),
				Map.of("default_timezone", "Asia/Colombo"));
		assertThat(status(config)).isEqualTo(200);
		String courseId = createOk(ctx, monthlyCourse(ctx, "Monthly")).path("id").asString();
		String moduleId = createModule(ctx, courseId, "Module", 1, null);

		MvcResult created = createLesson(ctx, courseId, moduleId,
				Map.of("title", "Month boundary", "startsAt", "2026-01-31T20:00:00Z", "paymentType",
						"COVERED_BY_CLASS_FEES", "billingYear", 2099, "billingMonth", 12, "resourceType", "IMAGE"));
		assertThat(status(created)).as(rawContent(created)).isEqualTo(201);
		JsonNode lesson = json(created).path("data");
		assertThat(lesson.path("billingYear").asInt()).isEqualTo(2026);
		assertThat(lesson.path("billingMonth").asInt()).isEqualTo(2);
		assertThat(lesson.path("resourceType").asString()).isEqualTo("IMAGE");

		MvcResult paid = send(ctx, patch("/api/v1/courses/" + courseId + "/modules/" + moduleId + "/lessons/"
				+ lesson.path("id").asString()), Map.of("title", "Independent", "startsAt", "2026-03-01T00:00:00Z",
						"paymentType", "PAID", "price", "100.00", "billingYear", 2099, "billingMonth", 12));
		assertThat(status(paid)).isEqualTo(200);
		assertThat(json(paid).path("data").path("billingMonth").isNull()).isTrue();

		MvcResult moved = send(ctx, patch("/api/v1/courses/" + courseId + "/modules/" + moduleId + "/lessons/"
				+ lesson.path("id").asString()), Map.of("title", "Moved", "startsAt", "2026-03-31T20:00:00Z",
						"paymentType", "COVERED_BY_CLASS_FEES", "resourceType", "IMAGE"));
		assertThat(status(moved)).isEqualTo(200);
		assertThat(json(moved).path("data").path("billingMonth").asInt()).isEqualTo(4);

		String oneTime = createOk(ctx, courseBody("Full course", "teacherIds", List.of(ctx.teacher().getId()),
				"pricingModel", "ONE_TIME", "price", "100.00")).path("id").asString();
		String oneTimeModule = createModule(ctx, oneTime, "Module", 1, null);
		MvcResult oneTimeLesson = createLesson(ctx, oneTime, oneTimeModule,
				Map.of("title", "No month", "startsAt", "2026-03-31T20:00:00Z", "billingYear", 2099,
						"billingMonth", 12));
		assertThat(status(oneTimeLesson)).isEqualTo(201);
		assertThat(json(oneTimeLesson).path("data").path("billingMonth").isNull()).isTrue();
	}

	@Test
	@Tag("cross-tenant")
	void crossTenantModuleAndLessonKlassEndpointsReturn404() {
		Ctx a = tenantWithAdmin("klass-ml-xt-a");
		Ctx b = tenantWithAdmin("klass-ml-xt-b");
		String courseA = createOk(a, monthlyCourse(a, "A")).path("id").asString();
		String moduleA = createModule(a, courseA, "Module", 1, null);
		String lessonA = json(createLesson(a, courseA, moduleA, Map.of("title", "L"))).path("data").path("id")
			.asString();

		assertThat(status(send(b, put("/api/v1/courses/" + courseA + "/modules/order"),
				Map.of("moduleIds", List.of(moduleA))))).isEqualTo(404);
		assertThat(status(send(b, put("/api/v1/courses/" + courseA + "/modules/" + moduleA + "/lessons/order"),
				Map.of("lessonIds", List.of(lessonA))))).isEqualTo(404);
		assertThat(status(send(b, patch("/api/v1/courses/" + courseA + "/modules/" + moduleA),
				Map.of("title", "X", "price", "100.00")))).isEqualTo(404);
		assertThat(status(send(b, patch("/api/v1/courses/" + courseA + "/modules/" + moduleA + "/lessons/" + lessonA),
				Map.of("title", "X", "paymentType", "PAID", "price", "1.00")))).isEqualTo(404);
		assertThat(status(send(b, get("/api/v1/courses/" + courseA + "/modules/" + moduleA + "/lessons"), null)))
			.isEqualTo(404);
	}

	@Test
	void courseWithParticipantsCannotBeDeleted() {
		Ctx ctx = tenantWithAdmin("klass-delete-in-use");
		String courseId = createOk(ctx, monthlyCourse(ctx, "In use")).path("id").asString();
		TenantUser student = seedActiveStudent(ctx.tenant().getId(), "student@example.test");
		jdbcTemplate.update(
				"INSERT INTO course_participation (id, tenant_id, student_id, course_id, enrolled_at, source) "
						+ "VALUES (?, ?, ?, ?, now(), 'SELF_ENROLL')",
				UUID.randomUUID(), ctx.tenant().getId(), student.getId(), UUID.fromString(courseId));

		MvcResult result = send(ctx, org.springframework.test.web.servlet.request.MockMvcRequestBuilders
			.delete("/api/v1/courses/" + courseId), null);

		assertThat(status(result)).isEqualTo(409);
		assertThat(code(json(result))).isEqualTo("COURSE_IN_USE");
	}

	@Test
	void lessonResourceTypeCatalogIsExposed() {
		Ctx ctx = tenantWithAdmin("klass-resource-types");
		MvcResult result = send(ctx, get("/api/v1/courses/lesson-resource-types"), null);

		assertThat(status(result)).isEqualTo(200);
		JsonNode types = json(result).path("data");
		assertThat(types).hasSize(12);
		assertThat(types.get(0).path("type").asString()).isEqualTo("VIMEO_VIDEO");
		assertThat(types.get(0).path("support").asString()).isEqualTo("LINK_ONLY");
		assertThat(types.toString()).contains("\"type\":\"IMAGE\"").contains("\"label\":\"Player Video\"");
	}

	@org.springframework.beans.factory.annotation.Autowired
	private com.lms.coursemanagement.api.CourseLookupApi courseLookupApi;

	@Test
	@Tag("cross-tenant")
	void lookupApiExposesLessonPoliciesCatalogInfoLinksAndModulePricesTenantScoped() {
		Ctx ctx = tenantWithAdmin("klass-lookup");
		Ctx other = tenantWithAdmin("klass-lookup-other");
		String source = createOk(ctx, monthlyCourse(ctx, "Source")).path("id").asString();
		String parent = createOk(ctx, courseBody("Parent", "teacherIds", List.of(ctx.teacher().getId()),
				"pricingModel", "MONTHLY", "price", "4000.00", "linkedCourseIds", List.of(source))).path("id").asString();
		String moduleId = createModule(ctx, parent, "Module", 1, "1500.00");
		createLesson(ctx, parent, moduleId, Map.of("title", "Paid", "paymentType", "PAID", "price", "750.00",
				"billingYear", 2026, "billingMonth", 10));
		UUID parentId = UUID.fromString(parent);

		var policies = withTenant(ctx.tenant().getId(), () -> courseLookupApi.findLessonPolicies(parentId));
		assertThat(policies).hasSize(1);
		assertThat(policies.get(0).paymentType()).isEqualTo(com.lms.coursemanagement.api.LessonPaymentType.PAID);
		assertThat(policies.get(0).moduleTitle()).isEqualTo("Module");
		assertThat(policies.get(0).billingMonth()).isNull();
		var info = withTenant(ctx.tenant().getId(), () -> courseLookupApi.findCatalogInfo(parentId)).orElseThrow();
		assertThat(info.pricingModel()).isEqualTo("MONTHLY");
		assertThat(info.monthlyAmount()).isEqualByComparingTo("4000.00");
		assertThat(info.registrationEnabled()).isTrue();
		assertThat(withTenant(ctx.tenant().getId(), () -> courseLookupApi.findLinkedSourceCourseIds(parentId)))
			.containsExactly(UUID.fromString(source));
		assertThat(withTenant(ctx.tenant().getId(), () -> courseLookupApi.findModulePrices(parentId)).get(0).price())
			.isEqualByComparingTo("1500.00");

		// Another tenant sees nothing.
		assertThat(withTenant(other.tenant().getId(), () -> courseLookupApi.findLessonPolicies(parentId))).isEmpty();
		assertThat(withTenant(other.tenant().getId(), () -> courseLookupApi.findCatalogInfo(parentId))).isEmpty();
		assertThat(withTenant(other.tenant().getId(), () -> courseLookupApi.findLinkedSourceCourseIds(parentId)))
			.isEmpty();
		assertThat(withTenant(other.tenant().getId(), () -> courseLookupApi.findModulePrices(parentId))).isEmpty();
	}

	private static List<String> ids(JsonNode content) {
		List<String> ids = new ArrayList<>();
		content.forEach(node -> ids.add(node.path("id").asString()));
		return ids;
	}

}
