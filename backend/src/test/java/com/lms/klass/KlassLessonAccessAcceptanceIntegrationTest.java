package com.lms.klass;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.lms.contentmanagement.ContentManagementTestSupport;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.paymentmanagement.api.PaymentChargebackApi;
import com.lms.paymentmanagement.order.web.dto.OrderResponse;
import com.lms.paymentmanagement.order.web.dto.PaymentInitiationResponse;
import com.lms.tenantmanagement.domain.Tenant;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * KLASS-T6 acceptance gap tests (ADR-028; plan §4.3; specs
 * Klass_Course_Management §13 / Klass_Enrollment_Payment §3A + §13) for IDs
 * not already proven by the engineers' T4a/T4b/T4c suites:
 * CM-A06/EP-D01 (legacy enrollment preserved + V86 backfill), CM-S05 (live
 * classes use the same evaluator), CM-R02 (expiry beats payment), CM-R03
 * (player handoff flags + configured links), EP-C05 (non-zero enrollment fee
 * is informational), EP-E04 (archived/closed course vs existing participant),
 * EP-L03 (free lesson in an unpaid course), EP-C11 (link bypass attempts).
 */
class KlassLessonAccessAcceptanceIntegrationTest extends ContentManagementTestSupport {

	@Autowired
	private PaymentChargebackApi paymentChargebackApi;

	private record Fx(Tenant tenant, String host, String adminToken, String teacherToken, String studentToken,
			TenantUser teacher, TenantUser student) {

	}

	private Fx seed(String prefix) {
		Tenant tenant = seedActiveTenant(uniqueSubdomain(prefix));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		TenantUser student = seedActiveStudent(tenant.getId(), "student@example.test");
		String host = hostFor(tenant.getSubdomain());
		return new Fx(tenant, host, loginAndGetToken(host, "admin@example.test"),
				loginAndGetToken(host, "teacher@example.test"), loginAndGetToken(host, "student@example.test"),
				teacher, student);
	}

	// ------------------------------------------------------------------
	// HTTP / SQL helpers.
	// ------------------------------------------------------------------

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

	/** Klass-style create (plan §4.1) as the tenant admin; PUBLIC so students can see it. */
	private UUID createCourse(Fx fx, String name, Object... extra) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("name", name);
		body.put("teacherIds", List.of(fx.teacher().getId()));
		body.put("pricingModel", "MONTHLY");
		body.put("price", "3500.00");
		body.put("status", "PUBLIC");
		for (int i = 0; i < extra.length; i += 2) {
			body.put((String) extra[i], extra[i + 1]);
		}
		MvcResult result = send(fx.host(), fx.adminToken(), post("/api/v1/courses"), body);
		assertThat(status(result)).as(rawContent(result)).isEqualTo(201);
		return UUID.fromString(json(result).path("data").path("id").asString());
	}

	private UUID createLegacyOneTimeCourse(Fx fx, String prefix) {
		return createCourseOrFail(fx.host(), fx.adminToken(),
				newCourseRequest(uniqueSlug(prefix), fx.teacher().getId(),
						com.lms.coursemanagement.course.domain.CourseStatus.PUBLIC))
			.id();
	}

	private final java.util.concurrent.atomic.AtomicInteger moduleSeq = new java.util.concurrent.atomic.AtomicInteger();

	private UUID module(Fx fx, UUID courseId, String title) {
		return createModuleOrFail(fx.host(), fx.adminToken(), courseId, title, moduleSeq.incrementAndGet()).id();
	}

	private UUID lesson(Fx fx, UUID courseId, UUID moduleId, Map<String, Object> fields) {
		MvcResult result = send(fx.host(), fx.adminToken(),
				post("/api/v1/courses/{c}/modules/{m}/lessons", courseId, moduleId), fields);
		assertThat(status(result)).as(rawContent(result)).isEqualTo(201);
		return UUID.fromString(json(result).path("data").path("id").asString());
	}

	private static Map<String, Object> fields(Object... kv) {
		Map<String, Object> map = new HashMap<>();
		for (int i = 0; i < kv.length; i += 2) {
			map.put((String) kv[i], kv[i + 1]);
		}
		return map;
	}

	private MvcResult selfEnroll(String host, String token, UUID courseId) {
		return call(host, token, post("/api/v1/enrollments/my/courses/{courseId}", courseId));
	}

	private MvcResult launch(String host, String token, UUID courseId, UUID lessonId) {
		return call(host, token, post("/api/v1/courses/{courseId}/lessons/{lessonId}/launch", courseId, lessonId));
	}

	private String launchCode(String host, String token, UUID courseId, UUID lessonId) {
		MvcResult result = launch(host, token, courseId, lessonId);
		return status(result) == 200 ? "ALLOWED" : json(result).path("code").asString();
	}

	private JsonNode content(String host, String token, UUID courseId) {
		MvcResult result = call(host, token, get("/api/v1/courses/{courseId}/content", courseId));
		assertThat(status(result)).as(rawContent(result)).isEqualTo(200);
		return json(result).path("data");
	}

	private static Map<String, JsonNode> lessonNodes(JsonNode contentData) {
		Map<String, JsonNode> nodes = new LinkedHashMap<>();
		contentData.path("periods").forEach(period -> period.path("modules")
			.forEach(module -> module.path("lessons").forEach(l -> nodes.put(l.path("lessonId").asString(), l))));
		return nodes;
	}

	// ------------------------------------------------------------------
	// CM-A06 / EP-D01: legacy enrollment + V86 backfill preservation.
	// ------------------------------------------------------------------

	private String snapshot(String table, UUID tenantId) {
		return jdbcTemplate.queryForObject("SELECT coalesce(md5(string_agg(t::text, '|' ORDER BY t.id)), 'empty') "
				+ "FROM " + table + " t WHERE t.tenant_id = ?", String.class, tenantId);
	}

	private String enrollmentSnapshot(UUID tenantId) {
		return jdbcTemplate.queryForObject("SELECT coalesce(md5(string_agg(concat_ws(',', id, student_id, course_id, "
				+ "activating_payment_id, activated_at, revoked_at), '|' ORDER BY id)), 'empty') FROM enrollment "
				+ "WHERE tenant_id = ?", String.class, tenantId);
	}

	/**
	 * The V86 backfill INSERT, read from the real migration file and scoped to
	 * one tenant (the shared Testcontainers DB holds other tests' tenants).
	 */
	private int runV86BackfillFor(UUID tenantId) throws IOException {
		String migration;
		try (InputStream in = new ClassPathResource(
				"db/migration/V86__course_participation_and_content_entitlements.sql").getInputStream()) {
			migration = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		int start = migration.indexOf("INSERT INTO course_participation");
		int end = migration.indexOf(';', start);
		assertThat(start).as("V86 backfill statement present").isNotNegative();
		String backfill = migration.substring(start, end);
		assertThat(backfill).contains("WHERE EXISTS (");
		String scoped = backfill.replaceFirst("WHERE EXISTS \\(", "WHERE e.tenant_id = ? AND EXISTS (");
		return jdbcTemplate.update(scoped, tenantId);
	}

	@Test
	void legacyOneTimeOrderStillActivatesEnrollmentAndTheV86BackfillPreservesEveryHistoricRow() throws IOException {
		Fx fx = seed("klass-d01");
		UUID tenantId = fx.tenant().getId();
		UUID course = createLegacyOneTimeCourse(fx, "klass-d01-c");
		UUID moduleId = module(fx, course, "Module");
		UUID coveredNoMonth = lesson(fx, course, moduleId, fields("title", "Covered lesson"));
		TenantUser student2 = seedActiveStudent(tenantId, "student2@example.test");
		String student2Token = loginAndGetToken(fx.host(), "student2@example.test");

		// Legacy COURSE (ONE_TIME) order flow: order -> payment -> verified webhook -> enrollment.
		OrderResponse order1 = createOrderOrFail(fx.host(), fx.studentToken(), course);
		PaymentInitiationResponse pay1 = initiatePaymentOrFail(fx.host(), fx.studentToken(), order1.id());
		assertThat(sendPaymentWebhook(pay1.gatewayReference(), true).getStatusCode().value()).isEqualTo(200);
		OrderResponse order2 = createOrderOrFail(fx.host(), student2Token, course);
		PaymentInitiationResponse pay2 = initiatePaymentOrFail(fx.host(), student2Token, order2.id());
		assertThat(sendPaymentWebhook(pay2.gatewayReference(), true).getStatusCode().value()).isEqualTo(200);
		// Student 2's access is later revoked by a chargeback (lineage with no live head).
		paymentChargebackApi.recordChargeback(pay2.gatewayReference(), "KLASS-D01-CB");

		assertThat(count("SELECT count(*) FROM enrollment WHERE tenant_id = ? AND student_id = ? AND course_id = ? "
				+ "AND activating_payment_id = ? AND revoked_at IS NULL", tenantId, fx.student().getId(), course,
				pay1.paymentId())).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM course_participation WHERE tenant_id = ? AND student_id = ? "
				+ "AND source = 'PAID_ACTIVATION'", tenantId, fx.student().getId())).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM content_entitlement WHERE tenant_id = ?", tenantId))
			.as("ONE_TIME keeps the order -> enrollment path (D02), no entitlement rows").isZero();
		assertThat(launchCode(fx.host(), fx.studentToken(), course, coveredNoMonth)).isEqualTo("ALLOWED");

		String enrollments = enrollmentSnapshot(tenantId);
		String orders = snapshot("student_order", tenantId);
		String payments = snapshot("payment", tenantId);
		String ledger = snapshot("ledger_entry", tenantId);
		String refunds = snapshot("payment_refund", tenantId);
		long auditRows = count("SELECT count(*) FROM audit_log WHERE tenant_id = ?", tenantId);

		// Simulate the pre-V86 state for this tenant, then run the real backfill statement.
		jdbcTemplate.update("DELETE FROM course_participation WHERE tenant_id = ?", tenantId);
		assertThat(runV86BackfillFor(tenantId)).isEqualTo(1);

		assertThat(count("SELECT count(*) FROM course_participation p JOIN enrollment e ON e.tenant_id = p.tenant_id "
				+ "AND e.student_id = p.student_id AND e.course_id = p.course_id WHERE p.tenant_id = ? "
				+ "AND p.student_id = ? AND p.source = 'LEGACY_ENROLLMENT' AND p.enrolled_at = e.activated_at",
				tenantId, fx.student().getId())).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM course_participation WHERE tenant_id = ? AND student_id = ?", tenantId,
				student2.getId())).as("fully revoked lineage is not backfilled").isZero();

		// No enrollment / order / payment / ledger / refund / audit row was rewritten or deleted.
		assertThat(enrollmentSnapshot(tenantId)).isEqualTo(enrollments);
		assertThat(snapshot("student_order", tenantId)).isEqualTo(orders);
		assertThat(snapshot("payment", tenantId)).isEqualTo(payments);
		assertThat(snapshot("ledger_entry", tenantId)).isEqualTo(ledger);
		assertThat(snapshot("payment_refund", tenantId)).isEqualTo(refunds);
		assertThat(count("SELECT count(*) FROM audit_log WHERE tenant_id = ?", tenantId)).isEqualTo(auditRows);

		// The legacy holder keeps course-wide access through the backfilled participation.
		assertThat(launchCode(fx.host(), fx.studentToken(), course, coveredNoMonth)).isEqualTo("ALLOWED");
		assertThat(launchCode(fx.host(), student2Token, course, coveredNoMonth)).isEqualTo("NOT_ENROLLED");
	}

	// ------------------------------------------------------------------
	// CM-S05: live classes and content tree share the evaluator.
	// ------------------------------------------------------------------

	@Test
	void liveClassesUseTheSameEvaluatorAsTheContentTree() {
		Fx fx = seed("klass-s05");
		UUID course = createCourse(fx, "Live ICT");
		UUID moduleId = module(fx, course, "Live");
		Instant start = Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
		UUID live = lesson(fx, course, moduleId, fields("title", "Zoom class", "resourceType", "ZOOM_MEETING",
				"paymentType", "COVERED_BY_CLASS_FEES", "startsAt", start.toString(), "endsAt",
				start.plus(2, ChronoUnit.HOURS).toString(), "billingYear", 2026, "billingMonth", 10));
		UUID freeLive = lesson(fx, course, moduleId, fields("title", "Free seminar", "resourceType", "GOOGLE_MEET",
				"paymentType", "FREE", "startsAt", start.plus(1, ChronoUnit.DAYS).toString()));
		Instant began = Instant.now().minus(30, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
		UUID inProgress = lesson(fx, course, moduleId, fields("title", "Running class", "resourceType", "MS_TEAMS_LINK",
				"paymentType", "COVERED_BY_CLASS_FEES", "startsAt", began.toString(), "endsAt",
				began.plus(3, ChronoUnit.HOURS).toString(), "billingYear", 2026, "billingMonth", 10));
		lesson(fx, course, moduleId, fields("title", "Notes", "resourceType", "DOCUMENT", "paymentType", "FREE"));
		assertThat(status(selfEnroll(fx.host(), fx.studentToken(), course))).isEqualTo(200);

		MvcResult liveResult = call(fx.host(), fx.studentToken(), get("/api/v1/courses/{c}/live-classes", course));
		assertThat(status(liveResult)).as(rawContent(liveResult)).isEqualTo(200);
		JsonNode liveList = json(liveResult).path("data");
		assertThat(liveList).hasSize(3);
		// Ordered by startsAt: running class, upcoming class, free seminar.
		assertThat(liveList.get(0).path("lessonId").asString()).isEqualTo(inProgress.toString());
		assertThat(liveList.get(1).path("lessonId").asString()).isEqualTo(live.toString());
		assertThat(liveList.get(2).path("lessonId").asString()).isEqualTo(freeLive.toString());
		assertThat(liveList.get(1).path("moduleTitle").asString()).isEqualTo("Live");
		assertThat(rawContent(liveResult)).doesNotContain("http");

		Map<String, JsonNode> tree = lessonNodes(content(fx.host(), fx.studentToken(), course));
		for (JsonNode liveNode : liveList) {
			JsonNode treeNode = tree.get(liveNode.path("lessonId").asString());
			assertThat(liveNode.path("access")).isEqualTo(treeNode.path("access"));
		}
		// Started + unpaid -> PAYMENT_REQUIRED; not started -> NOT_STARTED wins over payment (D04 precedence)
		// but the payment facet still says REQUIRED; FREE -> no payment needed.
		assertThat(liveList.get(0).path("access").path("status").asString()).isEqualTo("PAYMENT_REQUIRED");
		assertThat(liveList.get(1).path("access").path("status").asString()).isEqualTo("NOT_STARTED");
		assertThat(liveList.get(1).path("access").path("payment").asString()).isEqualTo("REQUIRED");
		assertThat(liveList.get(2).path("access").path("payment").asString()).isEqualTo("FREE");
		assertThat(launchCode(fx.host(), fx.studentToken(), course, inProgress)).isEqualTo("PAYMENT_REQUIRED");
		assertThat(launchCode(fx.host(), fx.studentToken(), course, live)).isEqualTo("NOT_STARTED");
	}

	// ------------------------------------------------------------------
	// CM-R02: an ended lesson is denied even with valid payment.
	// ------------------------------------------------------------------

	@Test
	void expiredLessonIsDeniedEvenWhenPaidAndItsMaterialIsDenied() {
		Fx fx = seed("klass-r02");
		UUID course = createLegacyOneTimeCourse(fx, "klass-r02-c");
		UUID moduleId = module(fx, course, "Module");
		Instant past = Instant.now().minus(10, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
		UUID ended = lesson(fx, course, moduleId, fields("title", "Ended class", "startsAt", past.toString(), "endsAt",
				past.plus(1, ChronoUnit.HOURS).toString()));
		UUID open = lesson(fx, course, moduleId, fields("title", "Open class"));
		var pdf = createMaterialOrFail(fx.host(), fx.teacherToken(), course, moduleId, ended, "Ended notes",
				pdfFile("ended.pdf"));
		enrollStudentOrFail(fx.host(), fx.studentToken(), course); // legacy course-wide paid access

		assertThat(launchCode(fx.host(), fx.studentToken(), course, open)).isEqualTo("ALLOWED");
		MvcResult denied = launch(fx.host(), fx.studentToken(), course, ended);
		assertThat(status(denied)).isEqualTo(403);
		assertThat(json(denied).path("code").asString()).isEqualTo("EXPIRED");
		assertThat(lessonNodes(content(fx.host(), fx.studentToken(), course)).get(ended.toString()).path("access")
			.path("status").asString()).isEqualTo("EXPIRED");
		MvcResult download = call(fx.host(), fx.studentToken(),
				get("/api/v1/courses/{c}/modules/{m}/lessons/{l}/materials/{id}/download-url", course, moduleId, ended,
						pdf.id()));
		assertThat(status(download)).isEqualTo(403);
		assertThat(count("SELECT count(*) FROM lesson_progress WHERE lesson_id = ?", ended)).isZero();
	}

	// ------------------------------------------------------------------
	// CM-R03: player handoff flags and configured links.
	// ------------------------------------------------------------------

	private UUID readyInternalAsset(Fx fx) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO video_asset (id, tenant_id, provider, storage_object_key, original_filename, "
				+ "mime_type, size_bytes, status, uploaded_by, created_at, updated_at) VALUES (?, ?, 'INTERNAL', ?, "
				+ "'lesson.mp4', 'video/mp4', 1024, 'READY', ?, now(), now())", id, fx.tenant().getId(),
				"videos/" + fx.tenant().getId() + "/" + id, fx.teacher().getId());
		return id;
	}

	private void requireNativePlayer(UUID tenantId) {
		jdbcTemplate.update("""
				INSERT INTO tenant_video_configuration (id, tenant_id, default_provider, default_privacy_status,
				    uploads_enabled, native_player_required, watermark_enabled, watermark_opacity,
				    watermark_movement_interval_seconds, watermark_fields, capture_protection_enabled,
				    capture_protection_required, max_concurrent_sessions, watch_time_policy,
				    launch_code_ttl_seconds, heartbeat_interval_seconds, daily_upload_limit, created_at, updated_at)
				VALUES (?, ?, 'YOUTUBE', 'UNLISTED', false, true, true, 0.25, 30, 'STUDENT_DISPLAY_ID', true, false, 1,
				    'NONE', 45, 20, 20, now(), now())
				""", UUID.randomUUID(), tenantId);
	}

	@Test
	void launchReportsPlayerHandoffOnlyForVideoLessonsWhenNativePlayerIsRequiredWithConfiguredLinks() {
		Fx fx = seed("klass-r03");
		UUID course = createLegacyOneTimeCourse(fx, "klass-r03-c");
		UUID moduleId = module(fx, course, "Module");
		UUID videoLesson = lesson(fx, course, moduleId, fields("title", "Video lesson", "resourceType", "SECURE_VIDEO"));
		UUID docLesson = lesson(fx, course, moduleId, fields("title", "Doc lesson", "resourceType", "DOCUMENT"));
		createMaterialOrFail(fx.host(), fx.teacherToken(), course, moduleId, docLesson, "Notes", pdfFile("n.pdf"));
		UUID asset = readyInternalAsset(fx);
		MvcResult attached = send(fx.host(), fx.teacherToken(),
				post("/api/v1/courses/{c}/lessons/{l}/media", course, videoLesson),
				Map.of("mediaAssetId", asset, "sequence", 1));
		assertThat(status(attached)).as(rawContent(attached)).isEqualTo(201);
		enrollStudentOrFail(fx.host(), fx.studentToken(), course);

		// Not required yet: no handoff, no links.
		JsonNode before = json(launch(fx.host(), fx.studentToken(), course, videoLesson)).path("data");
		assertThat(before.path("playerHandoffRequired").asBoolean()).isFalse();

		requireNativePlayer(fx.tenant().getId());
		MvcResult config = send(fx.host(), fx.adminToken(), put("/api/v1/tenant-config/VIDEO"),
				Map.of("player_android_url", "https://play.example.test/aoc-player", "player_ios_url",
						"https://apps.example.test/aoc-player"));
		assertThat(status(config)).as(rawContent(config)).isEqualTo(200);

		MvcResult videoLaunch = launch(fx.host(), fx.studentToken(), course, videoLesson);
		assertThat(status(videoLaunch)).as(rawContent(videoLaunch)).isEqualTo(200);
		JsonNode data = json(videoLaunch).path("data");
		assertThat(data.path("playerHandoffRequired").asBoolean()).isTrue();
		assertThat(data.path("playerLinks").path("androidUrl").asString())
			.isEqualTo("https://play.example.test/aoc-player");
		assertThat(data.path("playerLinks").path("iosUrl").asString()).isEqualTo("https://apps.example.test/aoc-player");
		// The launch payload never carries a playback URL or token - those require the separate short-lived flow.
		assertThat(rawContent(videoLaunch)).doesNotContain("videos/").doesNotContain("playbackToken")
			.doesNotContain(asset.toString());

		JsonNode doc = json(launch(fx.host(), fx.studentToken(), course, docLesson)).path("data");
		assertThat(doc.path("playerHandoffRequired").asBoolean()).isFalse();

		// An unpaid participant never gets the handoff payload.
		seedActiveStudent(fx.tenant().getId(), "unpaid@example.test");
		String unpaid = loginAndGetToken(fx.host(), "unpaid@example.test");
		assertThat(status(selfEnroll(fx.host(), unpaid, course))).isEqualTo(200);
		MvcResult unpaidLaunch = launch(fx.host(), unpaid, course, videoLesson);
		assertThat(status(unpaidLaunch)).isEqualTo(403);
		assertThat(rawContent(unpaidLaunch)).doesNotContain("aoc-player");
	}

	// ------------------------------------------------------------------
	// EP-C05: enrollment fee is informational only.
	// ------------------------------------------------------------------

	@Test
	void nonZeroEnrollmentFeeStillEnrollsFreeWithZeroOrders() {
		Fx fx = seed("klass-c05");
		UUID course = createCourse(fx, "Fee course", "enrollmentFee", "1500.00");
		assertThat(json(call(fx.host(), fx.adminToken(), get("/api/v1/courses/{id}", course))).path("data")
			.path("enrollmentFee").decimalValue()).isEqualByComparingTo("1500.00");

		MvcResult enrolled = selfEnroll(fx.host(), fx.studentToken(), course);
		assertThat(status(enrolled)).as(rawContent(enrolled)).isEqualTo(200);
		assertThat(json(enrolled).path("data").path("created").asBoolean()).isTrue();
		UUID tenantId = fx.tenant().getId();
		assertThat(count("SELECT count(*) FROM student_order WHERE tenant_id = ?", tenantId)).isZero();
		assertThat(count("SELECT count(*) FROM payment WHERE tenant_id = ?", tenantId)).isZero();
		assertThat(count("SELECT count(*) FROM ledger_entry WHERE tenant_id = ?", tenantId)).isZero();
		JsonNode enrolledView = json(call(fx.host(), fx.studentToken(),
				get("/api/v1/enrollments/my/catalog?view=ENROLLED"))).path("data").path("content");
		assertThat(enrolledView).hasSize(1);
		assertThat(enrolledView.get(0).path("courseId").asString()).isEqualTo(course.toString());
	}

	// ------------------------------------------------------------------
	// EP-E04: unavailable courses deny NEW enrollment; existing participants keep access.
	// ------------------------------------------------------------------

	@Test
	void archivedAndRegistrationClosedCoursesDenyNewEnrollmentButExistingParticipantsKeepAccess() {
		Fx fx = seed("klass-e04");
		UUID closed = createCourse(fx, "Closed course");
		UUID closedModule = module(fx, closed, "M");
		UUID closedFree = lesson(fx, closed, closedModule, fields("title", "Free", "paymentType", "FREE"));
		UUID archived = createCourse(fx, "Archived course");
		UUID archivedModule = module(fx, archived, "M");
		UUID archivedFree = lesson(fx, archived, archivedModule, fields("title", "Free", "paymentType", "FREE"));
		assertThat(status(selfEnroll(fx.host(), fx.studentToken(), closed))).isEqualTo(200);
		assertThat(status(selfEnroll(fx.host(), fx.studentToken(), archived))).isEqualTo(200);
		seedActiveStudent(fx.tenant().getId(), "late@example.test");
		String late = loginAndGetToken(fx.host(), "late@example.test");

		assertThat(status(send(fx.host(), fx.adminToken(), patch("/api/v1/courses/{id}/registration", closed),
				Map.of("enabled", false)))).isEqualTo(200);
		MvcResult archiveResult = call(fx.host(), fx.adminToken(), post("/api/v1/courses/{id}/archive", archived));
		assertThat(status(archiveResult)).as(rawContent(archiveResult)).isEqualTo(200);

		MvcResult closedEnroll = selfEnroll(fx.host(), late, closed);
		assertThat(status(closedEnroll)).isEqualTo(409);
		assertThat(json(closedEnroll).path("error").path("code").asString()).isEqualTo("REGISTRATION_CLOSED");
		assertThat(status(selfEnroll(fx.host(), late, archived))).isIn(404, 409);
		assertThat(count("SELECT count(*) FROM course_participation WHERE tenant_id = ? AND course_id IN (?, ?)",
				fx.tenant().getId(), closed, archived)).isEqualTo(2);
		JsonNode available = json(call(fx.host(), late, get("/api/v1/enrollments/my/catalog?view=AVAILABLE")))
			.path("data").path("content");
		List<String> availableIds = new ArrayList<>();
		available.forEach(item -> availableIds.add(item.path("courseId").asString()));
		assertThat(availableIds).doesNotContain(closed.toString(), archived.toString());

		// Existing participant: registration closing never revokes participation.
		assertThat(launchCode(fx.host(), fx.studentToken(), closed, closedFree)).isEqualTo("ALLOWED");
		// Existing participant of an archived course keeps the access it already had (D15).
		assertThat(launchCode(fx.host(), fx.studentToken(), archived, archivedFree)).isEqualTo("ALLOWED");
	}

	// ------------------------------------------------------------------
	// EP-L03: free lesson in an otherwise unpaid course.
	// ------------------------------------------------------------------

	@Test
	void freeLessonInAnUnpaidCourseLaunchesButCoveredAndPaidSiblingsStayLocked() {
		Fx fx = seed("klass-l03");
		UUID course = createCourse(fx, "Unpaid course");
		UUID moduleId = module(fx, course, "M");
		UUID free = lesson(fx, course, moduleId, fields("title", "Free intro", "paymentType", "FREE"));
		UUID covered = lesson(fx, course, moduleId, fields("title", "October", "paymentType", "COVERED_BY_CLASS_FEES",
				"billingYear", 2026, "billingMonth", 10));
		UUID paid = lesson(fx, course, moduleId, fields("title", "Paper", "paymentType", "PAID", "price", "750.00"));
		var notes = createMaterialOrFail(fx.host(), fx.teacherToken(), course, moduleId, free, "Intro notes",
				pdfFile("intro.pdf"));

		// Not enrolled: even the FREE lesson is not launchable (free != public).
		assertThat(launchCode(fx.host(), fx.studentToken(), course, free)).isEqualTo("NOT_ENROLLED");

		assertThat(status(selfEnroll(fx.host(), fx.studentToken(), course))).isEqualTo(200);
		MvcResult freeLaunch = launch(fx.host(), fx.studentToken(), course, free);
		assertThat(status(freeLaunch)).as(rawContent(freeLaunch)).isEqualTo(200);
		assertThat(json(freeLaunch).path("data").path("access").path("payment").asString()).isEqualTo("FREE");
		MvcResult download = call(fx.host(), fx.studentToken(),
				get("/api/v1/courses/{c}/modules/{m}/lessons/{l}/materials/{id}/download-url", course, moduleId, free,
						notes.id()));
		assertThat(status(download)).as(rawContent(download)).isEqualTo(200);
		assertThat(launchCode(fx.host(), fx.studentToken(), course, covered)).isEqualTo("PAYMENT_REQUIRED");
		assertThat(launchCode(fx.host(), fx.studentToken(), course, paid)).isEqualTo("PAYMENT_REQUIRED");
		assertThat(count("SELECT count(*) FROM student_order WHERE tenant_id = ?", fx.tenant().getId())).isZero();
	}

	// ------------------------------------------------------------------
	// EP-C11: link bypass attempts.
	// ------------------------------------------------------------------

	@Test
	@Tag("cross-tenant")
	void linkedCourseBypassAttemptsAreDeniedAndUnpaidSourceLessonsNeverLaunchThroughTheParent() {
		Fx fx = seed("klass-c11");
		Fx other = seed("klass-c11-b");
		UUID foreign = createCourse(other, "Foreign source");
		UUID source = createCourse(fx, "Source");
		UUID sourceModule = module(fx, source, "Source module");
		UUID sourceCovered = lesson(fx, source, sourceModule, fields("title", "Source October", "paymentType",
				"COVERED_BY_CLASS_FEES", "billingYear", 2026, "billingMonth", 10));
		UUID sourcePaid = lesson(fx, source, sourceModule, fields("title", "Source paper", "paymentType", "PAID",
				"price", "900.00"));

		// Cross-tenant link on create -> 404, nothing created.
		Map<String, Object> crossTenant = new LinkedHashMap<>();
		crossTenant.put("name", "Parent cross");
		crossTenant.put("teacherIds", List.of(fx.teacher().getId()));
		crossTenant.put("pricingModel", "FREE");
		crossTenant.put("linkedCourseIds", List.of(foreign));
		assertThat(status(send(fx.host(), fx.adminToken(), post("/api/v1/courses"), crossTenant))).isEqualTo(404);
		assertThat(count("SELECT count(*) FROM course WHERE tenant_id = ? AND name = 'Parent cross'",
				fx.tenant().getId())).isZero();

		UUID parent = createCourse(fx, "Parent", "pricingModel", "FREE", "price", null, "linkedCourseIds",
				List.of(source));
		// Self link and cycle (source -> parent while parent -> source) are rejected on edit.
		MvcResult self = send(fx.host(), fx.adminToken(), patch("/api/v1/courses/{id}", parent),
				Map.of("name", "Parent", "linkedCourseIds", List.of(parent)));
		assertThat(status(self)).as(rawContent(self)).isEqualTo(400);
		assertThat(json(self).path("error").path("code").asString()).isEqualTo("COURSE_LINK_INVALID");
		MvcResult cycle = send(fx.host(), fx.adminToken(), patch("/api/v1/courses/{id}", source),
				Map.of("name", "Source", "linkedCourseIds", List.of(parent)));
		assertThat(status(cycle)).isEqualTo(400);
		assertThat(json(cycle).path("error").path("code").asString()).isEqualTo("COURSE_LINK_INVALID");
		MvcResult foreignEdit = send(fx.host(), fx.adminToken(), patch("/api/v1/courses/{id}", parent),
				Map.of("name", "Parent", "linkedCourseIds", List.of(foreign)));
		assertThat(status(foreignEdit)).isEqualTo(404);
		assertThat(count("SELECT count(*) FROM course_link WHERE tenant_id = ?", fx.tenant().getId())).isEqualTo(1);

		// Free participation in the PARENT only.
		assertThat(status(selfEnroll(fx.host(), fx.studentToken(), parent))).isEqualTo(200);
		Map<String, JsonNode> tree = lessonNodes(content(fx.host(), fx.studentToken(), parent));
		assertThat(tree.get(sourceCovered.toString()).path("courseId").asString()).isEqualTo(source.toString());
		assertThat(tree.get(sourceCovered.toString()).path("access").path("status").asString())
			.isEqualTo("PAYMENT_REQUIRED");
		assertThat(tree.get(sourcePaid.toString()).path("access").path("status").asString())
			.isEqualTo("PAYMENT_REQUIRED");

		assertThat(launchCode(fx.host(), fx.studentToken(), source, sourceCovered)).isEqualTo("PAYMENT_REQUIRED");
		assertThat(launchCode(fx.host(), fx.studentToken(), source, sourcePaid)).isEqualTo("PAYMENT_REQUIRED");
		assertThat(status(launch(fx.host(), fx.studentToken(), parent, sourceCovered))).isEqualTo(404);
		// Tenant B's student cannot reach either course of tenant A.
		assertThat(status(launch(other.host(), other.studentToken(), source, sourceCovered))).isEqualTo(404);
		assertThat(count("SELECT count(*) FROM course_participation WHERE tenant_id = ? AND course_id = ?",
				fx.tenant().getId(), source)).as("linking never auto-enrolls into the source").isZero();
	}

}
