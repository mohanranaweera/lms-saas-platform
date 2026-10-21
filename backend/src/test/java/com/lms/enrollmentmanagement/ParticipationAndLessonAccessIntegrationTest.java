package com.lms.enrollmentmanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.lms.contentmanagement.ContentManagementTestSupport;
import com.lms.contentmanagement.material.web.dto.MaterialResponse;
import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.web.dto.CourseLessonResponse;
import com.lms.coursemanagement.course.web.dto.CourseModuleResponse;
import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.liveclassmanagement.web.dto.ClassSessionCreateRequest;
import com.lms.tenantmanagement.domain.Tenant;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * KLASS-T4b Testcontainers/MockMvc coverage (ADR-028, plan §4.2/§4.3): free
 * self-enrollment (idempotent, race-safe, audited, no financial rows),
 * registration closed, non-visible/cross-tenant 404s, the content tree (no
 * URLs, no progress on read), launch (progress once) and the lesson evaluator
 * guarding material download, external links and class-session join for an
 * unpaid participant, pending/confirmed monthly coverage and linked courses.
 */
class ParticipationAndLessonAccessIntegrationTest extends ContentManagementTestSupport {

	private static final String SECRET_LINK = "https://example.test/secret-lesson-link";

	private record Fx(Tenant tenant, String host, String adminToken, String teacherToken, String studentToken,
			TenantUser teacher, TenantUser student, CourseResponse course, UUID moduleId, UUID lessonId,
			MaterialResponse link, MaterialResponse pdf) {

	}

	private Fx seed(String prefix) {
		Tenant tenant = seedActiveTenant(uniqueSubdomain(prefix));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		TenantUser student = seedActiveStudent(tenant.getId(), "student@example.test");
		String host = hostFor(tenant.getSubdomain());
		String adminToken = loginAndGetToken(host, "admin@example.test");
		String teacherToken = loginAndGetToken(host, "teacher@example.test");
		String studentToken = loginAndGetToken(host, "student@example.test");
		CourseResponse course = createCourseOrFail(host, adminToken,
				newCourseRequest(uniqueSlug(prefix), teacher.getId(), CourseStatus.PUBLIC));
		CourseModuleResponse module = createModuleOrFail(host, adminToken, course.id(), "Module 1", 1);
		CourseLessonResponse lesson = createLessonOrFail(host, adminToken, course.id(), module.id(), "Lesson 1", 1);
		MaterialResponse link = createLinkMaterialOrFail(host, teacherToken, course.id(), module.id(), lesson.id(),
				"Lesson link", SECRET_LINK);
		MaterialResponse pdf = createMaterialOrFail(host, teacherToken, course.id(), module.id(), lesson.id(),
				"Lesson notes", pdfFile("notes.pdf"));
		return new Fx(tenant, host, adminToken, teacherToken, studentToken, teacher, student, course, module.id(),
				lesson.id(), link, pdf);
	}

	// ------------------------------------------------------------------
	// HTTP helpers.
	// ------------------------------------------------------------------

	private MvcResult call(String host, String token, MockHttpServletRequestBuilder builder) {
		return perform(authenticated(builder, host, token));
	}

	private MvcResult selfEnroll(String host, String token, UUID courseId) {
		return call(host, token, post("/api/v1/enrollments/my/courses/{courseId}", courseId));
	}

	private MvcResult content(String host, String token, UUID courseId) {
		return call(host, token, get("/api/v1/courses/{courseId}/content", courseId));
	}

	private MvcResult launch(String host, String token, UUID courseId, UUID lessonId) {
		return call(host, token, post("/api/v1/courses/{courseId}/lessons/{lessonId}/launch", courseId, lessonId));
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

	private long financialRows(UUID tenantId) {
		return count("SELECT count(*) FROM student_order WHERE tenant_id = ?", tenantId)
				+ count("SELECT count(*) FROM payment WHERE tenant_id = ?", tenantId)
				+ count("SELECT count(*) FROM ledger_entry WHERE tenant_id = ?", tenantId);
	}

	private JsonNode onlyLessonNode(JsonNode contentData) {
		List<JsonNode> lessons = new ArrayList<>();
		contentData.path("periods").forEach(period -> period.path("modules")
			.forEach(module -> module.path("lessons").forEach(lessons::add)));
		assertThat(lessons).hasSize(1);
		return lessons.get(0);
	}

	// ------------------------------------------------------------------
	// Free enrollment.
	// ------------------------------------------------------------------

	@Test
	void selfEnrollIsIdempotentAuditedAndCreatesNoOrderPaymentOrLedgerRow() {
		Fx fx = seed("t4b-enroll");

		MvcResult first = selfEnroll(fx.host(), fx.studentToken(), fx.course().id());
		MvcResult second = selfEnroll(fx.host(), fx.studentToken(), fx.course().id());

		assertThat(status(first)).as(rawContent(first)).isEqualTo(200);
		assertThat(status(second)).isEqualTo(200);
		JsonNode firstData = json(first).path("data");
		JsonNode secondData = json(second).path("data");
		assertThat(firstData.path("courseId").asString()).isEqualTo(fx.course().id().toString());
		assertThat(firstData.path("created").asBoolean()).isTrue();
		assertThat(secondData.path("created").asBoolean()).isFalse();
		assertThat(secondData.path("enrolledAt").asString()).isEqualTo(firstData.path("enrolledAt").asString());
		assertThat(count("SELECT count(*) FROM course_participation WHERE tenant_id = ? AND student_id = ? "
				+ "AND course_id = ? AND source = 'SELF_ENROLL'", fx.tenant().getId(), fx.student().getId(),
				fx.course().id())).isEqualTo(1);
		assertThat(financialRows(fx.tenant().getId())).isZero();
		assertThat(count("SELECT count(*) FROM enrollment WHERE tenant_id = ?", fx.tenant().getId())).isZero();
		assertThat(count("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'course.participation_created'",
				fx.tenant().getId())).isEqualTo(1);
	}

	@Test
	void concurrentSelfEnrollsCreateExactlyOneParticipation() throws Exception {
		Fx fx = seed("t4b-race");
		int threads = 6;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		try {
			List<Callable<MvcResult>> calls = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				calls.add(() -> selfEnroll(fx.host(), fx.studentToken(), fx.course().id()));
			}
			int created = 0;
			for (Future<MvcResult> future : pool.invokeAll(calls)) {
				MvcResult result = future.get();
				assertThat(status(result)).as(rawContent(result)).isEqualTo(200);
				if (json(result).path("data").path("created").asBoolean()) {
					created++;
				}
			}
			assertThat(created).isEqualTo(1);
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(count("SELECT count(*) FROM course_participation WHERE tenant_id = ? AND course_id = ?",
				fx.tenant().getId(), fx.course().id())).isEqualTo(1);
	}

	@Test
	void closedRegistrationRejectsNewStudentsButKeepsExistingParticipants() {
		Fx fx = seed("t4b-closed");
		seedActiveStudent(fx.tenant().getId(), "student2@example.test");
		String student2Token = loginAndGetToken(fx.host(), "student2@example.test");
		assertThat(status(selfEnroll(fx.host(), fx.studentToken(), fx.course().id()))).isEqualTo(200);

		MvcResult closed = call(fx.host(), fx.adminToken(),
				patch("/api/v1/courses/{id}/registration", fx.course().id()).contentType(MediaType.APPLICATION_JSON)
					.content(objectMapper.writeValueAsString(Map.of("enabled", false))));
		assertThat(status(closed)).as(rawContent(closed)).isEqualTo(200);

		MvcResult rejected = selfEnroll(fx.host(), student2Token, fx.course().id());
		assertThat(status(rejected)).isEqualTo(409);
		assertThat(json(rejected).path("error").path("code").asString()).isEqualTo("REGISTRATION_CLOSED");

		MvcResult again = selfEnroll(fx.host(), fx.studentToken(), fx.course().id());
		assertThat(status(again)).isEqualTo(200);
		assertThat(json(again).path("data").path("created").asBoolean()).isFalse();
		assertThat(status(content(fx.host(), fx.studentToken(), fx.course().id()))).isEqualTo(200);
	}

	@Test
	void draftAndCrossTenantCoursesAreNotFound() {
		Fx a = seed("t4b-tenant-a");
		Fx b = seed("t4b-tenant-b");
		CourseResponse draft = createCourseOrFail(a.host(), a.adminToken(),
				newCourseRequest(uniqueSlug("t4b-draft"), a.teacher().getId(), CourseStatus.DRAFT));

		assertThat(status(selfEnroll(a.host(), a.studentToken(), draft.id()))).isEqualTo(404);
		assertThat(status(selfEnroll(b.host(), b.studentToken(), a.course().id()))).isEqualTo(404);
		assertThat(status(selfEnroll(a.host(), a.studentToken(), a.course().id()))).isEqualTo(200);

		// Tenant B's student can never read tenant A's content tree, live classes or launch its lesson.
		assertThat(status(content(b.host(), b.studentToken(), a.course().id()))).isEqualTo(404);
		assertThat(status(call(b.host(), b.studentToken(),
				get("/api/v1/courses/{courseId}/live-classes", a.course().id())))).isEqualTo(404);
		assertThat(status(launch(b.host(), b.studentToken(), a.course().id(), a.lessonId()))).isEqualTo(404);
		// A non-participant of the same tenant gets no content tree either.
		assertThat(status(content(b.host(), b.studentToken(), b.course().id()))).isEqualTo(404);
		assertThat(count("SELECT count(*) FROM course_participation WHERE tenant_id = ?", b.tenant().getId()))
			.isZero();
	}

	// ------------------------------------------------------------------
	// Unpaid participant vs protected resources.
	// ------------------------------------------------------------------

	@Test
	void unpaidParticipantBrowsesMetadataButEveryProtectedResourceIsDenied() {
		Fx fx = seed("t4b-unpaid");
		assertThat(status(selfEnroll(fx.host(), fx.studentToken(), fx.course().id()))).isEqualTo(200);

		MvcResult tree = content(fx.host(), fx.studentToken(), fx.course().id());
		assertThat(status(tree)).as(rawContent(tree)).isEqualTo(200);
		assertThat(rawContent(tree)).doesNotContain(SECRET_LINK).doesNotContain("externalUrl").doesNotContain("http");
		JsonNode data = json(tree).path("data");
		assertThat(data.path("totalCount").asInt()).isEqualTo(1);
		assertThat(data.path("viewedCount").asInt()).isZero();
		JsonNode node = onlyLessonNode(data);
		assertThat(node.path("access").path("status").asString()).isEqualTo("PAYMENT_REQUIRED");
		assertThat(node.path("access").path("requiredPurchase").path("scope").asString()).isEqualTo("COURSE");

		MvcResult launched = launch(fx.host(), fx.studentToken(), fx.course().id(), fx.lessonId());
		assertThat(status(launched)).isEqualTo(403);
		JsonNode denial = json(launched);
		assertThat(denial.path("code").asString()).isEqualTo("PAYMENT_REQUIRED");
		assertThat(denial.path("error").path("code").asString()).isEqualTo("PAYMENT_REQUIRED");
		assertThat(denial.path("access").path("status").asString()).isEqualTo("PAYMENT_REQUIRED");
		assertThat(count("SELECT count(*) FROM lesson_progress WHERE tenant_id = ?", fx.tenant().getId())).isZero();

		MvcResult pdfDownload = call(fx.host(), fx.studentToken(),
				get("/api/v1/courses/{c}/modules/{m}/lessons/{l}/materials/{id}/download-url", fx.course().id(),
						fx.moduleId(), fx.lessonId(), fx.pdf().id()));
		assertThat(status(pdfDownload)).isEqualTo(403);
		assertThat(json(pdfDownload).path("code").asString()).isEqualTo("PAYMENT_REQUIRED");
		MvcResult linkOpen = call(fx.host(), fx.studentToken(),
				get("/api/v1/courses/{c}/modules/{m}/lessons/{l}/materials/{id}/download-url", fx.course().id(),
						fx.moduleId(), fx.lessonId(), fx.link().id()));
		assertThat(status(linkOpen)).isEqualTo(403);
		assertThat(rawContent(linkOpen)).doesNotContain(SECRET_LINK);

		var listed = listMaterials(fx.host(), fx.studentToken(), fx.course().id(), fx.moduleId(), fx.lessonId());
		assertThat(listed.getStatusCode().value()).isEqualTo(200);
		assertThat(listed.getBody().data()).extracting(MaterialResponse::title)
			.containsExactlyInAnyOrder("Lesson link", "Lesson notes");
		assertThat(listed.getBody().data()).allSatisfy(m -> assertThat(m.externalUrl()).isNull());

		// A class session attached to the lesson: join is denied by the same evaluator.
		Instant start = Instant.now().plus(1, ChronoUnit.DAYS);
		MvcResult scheduled = call(fx.host(), fx.adminToken(), post("/api/v1/class-sessions")
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(new ClassSessionCreateRequest(fx.course().id(), fx.lessonId(),
					"Live", "Live class", start, start.plus(1, ChronoUnit.HOURS)))));
		assertThat(status(scheduled)).as(rawContent(scheduled)).isEqualTo(201);
		UUID sessionId = UUID.fromString(json(scheduled).path("data").path("id").asString());
		jdbcTemplate.update("UPDATE class_session SET status = 'LIVE' WHERE id = ?", sessionId);
		MvcResult join = call(fx.host(), fx.studentToken(), post("/api/v1/class-sessions/{id}/join", sessionId));
		assertThat(status(join)).isEqualTo(403);
		assertThat(json(join).path("code").asString()).isEqualTo("PAYMENT_REQUIRED");

		assertThat(financialRows(fx.tenant().getId())).isZero();
	}

	// ------------------------------------------------------------------
	// Paid (legacy) participant: launch and progress.
	// ------------------------------------------------------------------

	@Test
	void paidParticipantLaunchRecordsProgressOnceAndReadingTheTreeRecordsNothing() {
		Fx fx = seed("t4b-paid");
		enrollStudentOrFail(fx.host(), fx.studentToken(), fx.course().id());
		assertThat(count("SELECT count(*) FROM course_participation WHERE tenant_id = ? AND student_id = ? "
				+ "AND course_id = ? AND source = 'PAID_ACTIVATION'", fx.tenant().getId(), fx.student().getId(),
				fx.course().id())).isEqualTo(1);

		assertThat(status(content(fx.host(), fx.studentToken(), fx.course().id()))).isEqualTo(200);
		assertThat(status(content(fx.host(), fx.studentToken(), fx.course().id()))).isEqualTo(200);
		assertThat(count("SELECT count(*) FROM lesson_progress WHERE tenant_id = ?", fx.tenant().getId())).isZero();

		MvcResult launched = launch(fx.host(), fx.studentToken(), fx.course().id(), fx.lessonId());
		assertThat(status(launched)).as(rawContent(launched)).isEqualTo(200);
		JsonNode data = json(launched).path("data");
		assertThat(data.path("access").path("status").asString()).isEqualTo("ALLOWED");
		List<String> resourceIds = new ArrayList<>();
		data.path("resources").forEach(resource -> {
			assertThat(resource.path("kind").asString()).isEqualTo("MATERIAL");
			resourceIds.add(resource.path("id").asString());
		});
		assertThat(resourceIds).containsExactlyInAnyOrder(fx.link().id().toString(), fx.pdf().id().toString());
		assertThat(data.path("playerHandoffRequired").asBoolean()).isFalse();
		assertThat(data.path("playerLinks").path("androidUrl").isNull()).isTrue();
		assertThat(rawContent(launched)).doesNotContain(SECRET_LINK);

		assertThat(status(launch(fx.host(), fx.studentToken(), fx.course().id(), fx.lessonId()))).isEqualTo(200);
		assertThat(count("SELECT count(*) FROM lesson_progress WHERE tenant_id = ? AND student_id = ? AND lesson_id = ?",
				fx.tenant().getId(), fx.student().getId(), fx.lessonId())).isEqualTo(1);

		JsonNode tree = json(content(fx.host(), fx.studentToken(), fx.course().id())).path("data");
		assertThat(tree.path("viewedCount").asInt()).isEqualTo(1);
		assertThat(onlyLessonNode(tree).path("viewed").asBoolean()).isTrue();

		MvcResult download = call(fx.host(), fx.studentToken(),
				get("/api/v1/courses/{c}/modules/{m}/lessons/{l}/materials/{id}/download-url", fx.course().id(),
						fx.moduleId(), fx.lessonId(), fx.pdf().id()));
		assertThat(status(download)).as(rawContent(download)).isEqualTo(200);
	}

	// ------------------------------------------------------------------
	// Monthly coverage (pending order -> confirmed entitlement).
	// ------------------------------------------------------------------

	@Test
	void pendingMonthlyOrderIsPendingAndAnEntitlementForTheLessonsMonthUnlocksIt() {
		Fx fx = seed("t4b-monthly");
		assertThat(status(selfEnroll(fx.host(), fx.studentToken(), fx.course().id()))).isEqualTo(200);
		UUID tenantId = fx.tenant().getId();
		jdbcTemplate.update("UPDATE course_lesson SET billing_year = 2026, billing_month = 10 WHERE id = ?",
				fx.lessonId());
		String key = "MONTHLY:" + fx.course().id() + ":2026-10";
		UUID orderId = UUID.randomUUID();
		UUID lineId = UUID.randomUUID();
		UUID paymentId = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO student_order (id, tenant_id, student_id, course_id, amount, currency, status, "
				+ "created_at, updated_at, subtotal_amount, facility_fee_amount, discount_amount) "
				+ "VALUES (?, ?, ?, ?, 3500.00, 'LKR', 'PENDING', now(), now(), 3500.00, 0, 0)", orderId, tenantId,
				fx.student().getId(), fx.course().id());
		jdbcTemplate.update("INSERT INTO student_order_line (id, tenant_id, order_id, line_no, scope, course_id, "
				+ "period_year, period_month, label, unit_amount, facility_fee_amount, discount_amount, line_total, "
				+ "currency, coverage_key, created_at) VALUES (?, ?, ?, 1, 'MONTHLY', ?, 2026, 10, 'October 2026', "
				+ "3500.00, 0, 0, 3500.00, 'LKR', ?, now())", lineId, tenantId, orderId, fx.course().id(), key);
		jdbcTemplate.update("INSERT INTO payment (id, tenant_id, order_id, amount, currency, status, created_at, "
				+ "updated_at) VALUES (?, ?, ?, 3500.00, 'LKR', 'PENDING', now(), now())", paymentId, tenantId, orderId);

		JsonNode pendingTree = json(content(fx.host(), fx.studentToken(), fx.course().id())).path("data");
		JsonNode period = pendingTree.path("periods").get(0);
		assertThat(period.path("key").asString()).isEqualTo("2026-10");
		assertThat(period.path("payment").path("status").asString()).isEqualTo("PENDING");
		assertThat(onlyLessonNode(pendingTree).path("access").path("status").asString())
			.isEqualTo("PAYMENT_PENDING");
		MvcResult pendingLaunch = launch(fx.host(), fx.studentToken(), fx.course().id(), fx.lessonId());
		assertThat(status(pendingLaunch)).isEqualTo(403);
		assertThat(json(pendingLaunch).path("code").asString()).isEqualTo("PAYMENT_PENDING");

		jdbcTemplate.update("UPDATE payment SET status = 'CONFIRMED', confirmed_at = now(), gateway_reference = ? WHERE id = ?", "t4b-ref-" + paymentId, paymentId);
		jdbcTemplate.update("INSERT INTO content_entitlement (id, tenant_id, student_id, scope, course_id, period_year, "
				+ "period_month, coverage_key, order_id, order_line_id, source_payment_id, granted_at) "
				+ "VALUES (?, ?, ?, 'MONTHLY', ?, 2026, 10, ?, ?, ?, ?, now())", UUID.randomUUID(), tenantId,
				fx.student().getId(), fx.course().id(), key, orderId, lineId, paymentId);

		JsonNode paidTree = json(content(fx.host(), fx.studentToken(), fx.course().id())).path("data");
		assertThat(paidTree.path("periods").get(0).path("payment").path("status").asString()).isEqualTo("PAID");
		assertThat(status(launch(fx.host(), fx.studentToken(), fx.course().id(), fx.lessonId()))).isEqualTo(200);
	}

	// ------------------------------------------------------------------
	// Linked courses (D13).
	// ------------------------------------------------------------------

	@Test
	void linkedSourceLessonsUseSourceRulesSoPayingTheParentNeverUnlocksThem() {
		Fx fx = seed("t4b-linked");
		CourseResponse parent = createCourseOrFail(fx.host(), fx.adminToken(),
				newCourseRequest(uniqueSlug("t4b-parent"), fx.teacher().getId(), CourseStatus.PUBLIC));
		CourseModuleResponse parentModule = createModuleOrFail(fx.host(), fx.adminToken(), parent.id(), "Parent", 1);
		CourseLessonResponse parentLesson = createLessonOrFail(fx.host(), fx.adminToken(), parent.id(),
				parentModule.id(), "Parent lesson", 1);
		jdbcTemplate.update("INSERT INTO course_link (id, tenant_id, parent_course_id, source_course_id, created_at) "
				+ "VALUES (?, ?, ?, ?, now())", UUID.randomUUID(), fx.tenant().getId(), parent.id(), fx.course().id());
		// Pays (legacy whole-course purchase) for the PARENT only.
		enrollStudentOrFail(fx.host(), fx.studentToken(), parent.id());

		MvcResult tree = content(fx.host(), fx.studentToken(), parent.id());
		assertThat(status(tree)).as(rawContent(tree)).isEqualTo(200);
		List<JsonNode> modules = new ArrayList<>();
		json(tree).path("data").path("periods").forEach(p -> p.path("modules").forEach(modules::add));
		assertThat(modules).hasSize(2);
		JsonNode own = modules.get(0);
		JsonNode linked = modules.get(1);
		assertThat(own.path("sourceCourseId").isNull()).isTrue();
		assertThat(own.path("lessons").get(0).path("access").path("status").asString()).isEqualTo("ALLOWED");
		assertThat(linked.path("sourceCourseId").asString()).isEqualTo(fx.course().id().toString());
		JsonNode sourceLesson = linked.path("lessons").get(0);
		assertThat(sourceLesson.path("courseId").asString()).isEqualTo(fx.course().id().toString());
		assertThat(sourceLesson.path("access").path("status").asString()).isEqualTo("PAYMENT_REQUIRED");

		// Enrolled through the link (403, not 404) but never paid through the parent.
		MvcResult sourceLaunch = launch(fx.host(), fx.studentToken(), fx.course().id(), fx.lessonId());
		assertThat(status(sourceLaunch)).isEqualTo(403);
		assertThat(json(sourceLaunch).path("code").asString()).isEqualTo("PAYMENT_REQUIRED");
		MvcResult viaParentPath = launch(fx.host(), fx.studentToken(), parent.id(), fx.lessonId());
		assertThat(status(viaParentPath)).isEqualTo(404);
		assertThat(status(launch(fx.host(), fx.studentToken(), parent.id(), parentLesson.id()))).isEqualTo(200);
		MvcResult sourceDownload = call(fx.host(), fx.studentToken(),
				get("/api/v1/courses/{c}/modules/{m}/lessons/{l}/materials/{id}/download-url", fx.course().id(),
						fx.moduleId(), fx.lessonId(), fx.pdf().id()));
		assertThat(status(sourceDownload)).isEqualTo(403);
	}

	// ------------------------------------------------------------------
	// Catalog.
	// ------------------------------------------------------------------

	@Test
	void catalogSplitsAvailableAndEnrolledCourses() {
		Fx fx = seed("t4b-catalog");
		MvcResult available = call(fx.host(), fx.studentToken(), get("/api/v1/enrollments/my/catalog?view=AVAILABLE"));
		assertThat(status(available)).as(rawContent(available)).isEqualTo(200);
		JsonNode availableItems = json(available).path("data").path("content");
		assertThat(availableItems).hasSize(1);
		assertThat(availableItems.get(0).path("courseId").asString()).isEqualTo(fx.course().id().toString());
		assertThat(availableItems.get(0).path("enrolled").asBoolean()).isFalse();
		assertThat(availableItems.get(0).path("priceDisplay").path("amount").decimalValue()).isNotNull();

		selfEnroll(fx.host(), fx.studentToken(), fx.course().id());

		JsonNode afterAvailable = json(call(fx.host(), fx.studentToken(),
				get("/api/v1/enrollments/my/catalog?view=AVAILABLE"))).path("data").path("content");
		assertThat(afterAvailable).isEmpty();
		JsonNode enrolled = json(call(fx.host(), fx.studentToken(),
				get("/api/v1/enrollments/my/catalog?view=ENROLLED&search=test"))).path("data").path("content");
		assertThat(enrolled).hasSize(1);
		assertThat(enrolled.get(0).path("enrolled").asBoolean()).isTrue();
		JsonNode noMatch = json(call(fx.host(), fx.studentToken(),
				get("/api/v1/enrollments/my/catalog?view=ENROLLED&search=zzz-no-such-course"))).path("data")
			.path("content");
		assertThat(noMatch).isEmpty();
	}

}
