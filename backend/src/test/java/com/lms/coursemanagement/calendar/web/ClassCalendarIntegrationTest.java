package com.lms.coursemanagement.calendar.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.lms.coursemanagement.CourseManagementTestSupport;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.tenantmanagement.domain.Tenant;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * CAL-01 Class Calendar &amp; Timetable API (Testcontainers/MockMvc): schedule
 * save/reopen/conflict/clear + audit, teacher/student/cross-tenant isolation,
 * caller-scoped occurrences, public "Today's Classes" allowlist and switch,
 * and the timetable PDF matching the JSON occurrences.
 *
 * <p>Recurring schedules that must already govern "today" are seeded directly
 * (a revision effective 30 days ago - synthetic data), because a schedule
 * saved through the API is, by design, effective only from the save instant.
 */
@Tag("cross-tenant")
class ClassCalendarIntegrationTest extends CourseManagementTestSupport {

	private static final ZoneId COLOMBO = ZoneId.of("Asia/Colombo");

	private record Fx(Tenant tenant, String host, String admin, String teacherA, String teacherB, String student,
			TenantUser adminUser, TenantUser teacherAUser, UUID pubA, UUID privB, UUID draftA, UUID pubC,
			UUID archivedA) {

	}

	private Fx seed(String prefix) {
		Tenant tenant = seedActiveTenant(uniqueSubdomain(prefix));
		TenantUser adminUser = seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacherA = seedTenantUser(tenant.getId(), "teacher-a@example.test", RAW_PASSWORD, Role.TEACHER);
		TenantUser teacherB = seedTenantUser(tenant.getId(), "teacher-b@example.test", RAW_PASSWORD, Role.TEACHER);
		seedActiveStudent(tenant.getId(), "student@example.test");
		String host = hostFor(tenant.getSubdomain());
		String admin = loginAndGetToken(host, "admin@example.test");
		MvcResult zone = call(host, admin, put("/api/v1/tenant-config/{d}", "GENERAL"),
				Map.of("default_timezone", "Asia/Colombo"));
		assertThat(status(zone)).as(rawContent(zone)).isEqualTo(200);

		UUID pubA = create(host, admin, "Alpha Biology", teacherA.getId(), "PUBLIC");
		UUID privB = create(host, admin, "Beta Physics", teacherB.getId(), "PRIVATE");
		UUID draftA = create(host, admin, "Gamma Draft", teacherA.getId(), "DRAFT");
		UUID pubC = create(host, admin, "Delta Chem", teacherB.getId(), "PUBLIC");
		UUID archivedA = create(host, admin, "Epsilon Archived", teacherA.getId(), "PUBLIC");
		assertThat(status(call(host, admin, post("/api/v1/courses/{id}/archive", archivedA), null))).isEqualTo(200);

		String student = loginAndGetToken(host, "student@example.test");
		MvcResult enrolled = call(host, student, post("/api/v1/enrollments/my/courses/{c}", pubA), null);
		assertThat(status(enrolled)).as(rawContent(enrolled)).isEqualTo(200);
		return new Fx(tenant, host, admin, loginAndGetToken(host, "teacher-a@example.test"),
				loginAndGetToken(host, "teacher-b@example.test"), student, adminUser, teacherA, pubA, privB, draftA,
				pubC, archivedA);
	}

	private UUID create(String host, String token, String name, UUID teacherId, String status) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("name", name);
		body.put("teacherIds", List.of(teacherId));
		body.put("pricingModel", "FREE");
		body.put("status", status);
		MvcResult result = call(host, token, post("/api/v1/courses"), body);
		assertThat(status(result)).as(rawContent(result)).isEqualTo(201);
		return UUID.fromString(json(result).path("data").path("id").asString());
	}

	/** A revision effective 30 days ago with one 00:00-23:59 slot on every weekday. */
	private void seedDailySchedule(Fx fx, UUID courseId) {
		UUID revisionId = UUID.randomUUID();
		Timestamp longAgo = Timestamp.from(Instant.now().minus(30, ChronoUnit.DAYS));
		jdbcTemplate.update(
				"INSERT INTO course_class_schedule_revision (id, tenant_id, course_id, effective_at, created_by, created_at) "
						+ "VALUES (?, ?, ?, ?, ?, ?)",
				revisionId, fx.tenant().getId(), courseId, longAgo, fx.adminUser().getId(), longAgo);
		for (int day = 1; day <= 7; day++) {
			jdbcTemplate.update(
					"INSERT INTO course_class_schedule_slot (id, tenant_id, revision_id, day_of_week, start_time, end_time) "
							+ "VALUES (?, ?, ?, ?, '00:00', '23:59')",
					UUID.randomUUID(), fx.tenant().getId(), revisionId, day);
		}
	}

	private void seedCancelledSessionToday(Fx fx, UUID courseId, UUID teacherId) {
		LocalDate today = LocalDate.now(COLOMBO);
		Instant start = today.atStartOfDay(COLOMBO).toInstant();
		Instant end = today.atTime(LocalTime.of(23, 59)).atZone(COLOMBO).toInstant();
		jdbcTemplate.update("INSERT INTO class_session (id, tenant_id, course_id, teacher_id, title, scheduled_start, "
				+ "scheduled_end, status, meeting_provider, provider_status, created_at, updated_at) "
				+ "VALUES (?, ?, ?, ?, 'Cancelled today', ?, ?, 'CANCELLED', 'ZOOM', 'PENDING', now(), now())",
				UUID.randomUUID(), fx.tenant().getId(), courseId, teacherId, Timestamp.from(start),
				Timestamp.from(end));
	}

	// ------------------------------------------------------------------
	// Schedule editor.
	// ------------------------------------------------------------------

	@Test
	void saveReopenConflictClearAndAudit() {
		Fx fx = seed("cal-save");

		JsonNode empty = schedule(fx.host(), fx.admin(), fx.pubA(), 200);
		assertThat(empty.path("revisionId").isNull()).isTrue();
		assertThat(empty.path("timezone").asString()).isEqualTo("Asia/Colombo");
		assertThat(empty.path("canEdit").asBoolean()).isTrue();
		assertThat(empty.path("slots")).isEmpty();

		Map<String, Object> body = saveBody(null, "2026-11-01", "2027-03-31",
				List.of(slotBody("WEDNESDAY", "14:00", "15:30"), slotBody("MONDAY", "08:00", "10:00")));
		MvcResult saved = call(fx.host(), fx.admin(), put("/api/v1/courses/{id}/class-schedule", fx.pubA()), body);
		assertThat(status(saved)).as(rawContent(saved)).isEqualTo(200);
		JsonNode savedData = json(saved).path("data");
		String revisionId = savedData.path("revisionId").asString();

		JsonNode reopened = schedule(fx.host(), fx.admin(), fx.pubA(), 200);
		assertThat(reopened.path("revisionId").asString()).isEqualTo(revisionId);
		assertThat(reopened.path("effectiveFrom").asString()).isEqualTo("2026-11-01");
		assertThat(reopened.path("effectiveTo").asString()).isEqualTo("2027-03-31");
		assertThat(reopened.path("updatedByName").asString()).isEqualTo("admin@example.test");
		assertThat(reopened.path("updatedAt").isNull()).isFalse();
		assertThat(slotStrings(reopened)).containsExactly("MONDAY 08:00-10:00", "WEDNESDAY 14:00-15:30");

		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = "
				+ "'COURSE_CLASS_SCHEDULE_UPDATED' AND target_id = ? AND actor_id = ?", Long.class, fx.tenant().getId(),
				fx.pubA(), fx.adminUser().getId())).isEqualTo(1L);
		String metadata = jdbcTemplate.queryForObject("SELECT metadata::text FROM audit_log WHERE tenant_id = ? AND "
				+ "action = 'COURSE_CLASS_SCHEDULE_UPDATED'", String.class, fx.tenant().getId());
		assertThat(metadata).contains("\"before\"", "\"after\"", "WEDNESDAY", "2027-03-31");

		// Stale editor (still holding "no revision") -> 409; nothing changes (same as Cancel).
		MvcResult stale = call(fx.host(), fx.admin(), put("/api/v1/courses/{id}/class-schedule", fx.pubA()),
				saveBody(null, null, null, List.of(slotBody("FRIDAY", "08:00", "09:00"))));
		assertThat(status(stale)).isEqualTo(409);
		assertThat(json(stale).path("error").path("code").asString()).isEqualTo("SCHEDULE_CONFLICT");
		assertThat(slotStrings(schedule(fx.host(), fx.admin(), fx.pubA(), 200)))
			.containsExactly("MONDAY 08:00-10:00", "WEDNESDAY 14:00-15:30");

		// Validation is server-side.
		MvcResult overnight = call(fx.host(), fx.admin(), put("/api/v1/courses/{id}/class-schedule", fx.pubA()),
				saveBody(revisionId, null, null, List.of(slotBody("MONDAY", "22:00", "01:00"))));
		assertThat(status(overnight)).isEqualTo(400);
		assertThat(json(overnight).path("error").path("code").asString()).isEqualTo("VALIDATION_ERROR");
		assertThat(rawContent(overnight)).contains("slots[0].endTime", "Overnight");

		// Clear all -> a new, empty revision; the old one stays (append-only history).
		MvcResult cleared = call(fx.host(), fx.admin(), put("/api/v1/courses/{id}/class-schedule", fx.pubA()),
				saveBody(revisionId, null, null, List.of()));
		assertThat(status(cleared)).as(rawContent(cleared)).isEqualTo(200);
		JsonNode afterClear = schedule(fx.host(), fx.admin(), fx.pubA(), 200);
		assertThat(afterClear.path("slots")).isEmpty();
		assertThat(afterClear.path("revisionId").asString()).isNotEqualTo(revisionId);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM course_class_schedule_revision WHERE tenant_id = ? AND course_id = ?", Long.class,
				fx.tenant().getId(), fx.pubA())).isEqualTo(2L);
	}

	@Test
	void firstRevisionGovernsBackwardButLaterRevisionsNeverRewriteHistory() {
		Fx fx = seed("cal-hist");
		LocalDate today = LocalDate.now(COLOMBO);
		LocalDate pastWeek = today.minusWeeks(5);
		LocalDate nextWeek = today.plusWeeks(2);
		String pastRange = "from=" + pastWeek + "&to=" + pastWeek.plusDays(7) + "&courseId=" + fx.pubA();
		String futureRange = "from=" + nextWeek + "&to=" + nextWeek.plusDays(7) + "&courseId=" + fx.pubA();

		// First save (effective now) still lays the weekly pattern over earlier dates.
		MvcResult first = call(fx.host(), fx.admin(), put("/api/v1/courses/{id}/class-schedule", fx.pubA()),
				saveBody(null, null, null, List.of(slotBody("MONDAY", "08:00", "10:00"), slotBody("THURSDAY", "16:00", "17:00"))));
		assertThat(status(first)).as(rawContent(first)).isEqualTo(200);
		assertThat(occurrences(fx.host(), fx.admin(), pastRange).path("occurrences")).hasSize(2);
		assertThat(occurrences(fx.host(), fx.admin(), futureRange).path("occurrences")).hasSize(2);

		// A later change (clear all) applies from its save instant only: the past keeps the old pattern.
		String revisionId = json(first).path("data").path("revisionId").asString();
		MvcResult cleared = call(fx.host(), fx.admin(), put("/api/v1/courses/{id}/class-schedule", fx.pubA()),
				saveBody(revisionId, null, null, List.of()));
		assertThat(status(cleared)).as(rawContent(cleared)).isEqualTo(200);
		assertThat(occurrences(fx.host(), fx.admin(), pastRange).path("occurrences")).hasSize(2);
		assertThat(occurrences(fx.host(), fx.admin(), futureRange).path("occurrences")).isEmpty();
	}

	@Test
	void teacherEditsOnlyOwnCoursesAndStudentsCannotReadTheEditor() {
		Fx fx = seed("cal-teacher");

		JsonNode own = schedule(fx.host(), fx.teacherA(), fx.pubA(), 200);
		assertThat(own.path("canEdit").asBoolean()).isTrue();
		MvcResult ownSave = call(fx.host(), fx.teacherA(), put("/api/v1/courses/{id}/class-schedule", fx.pubA()),
				saveBody(null, null, null, List.of(slotBody("MONDAY", "08:00", "10:00"))));
		assertThat(status(ownSave)).as(rawContent(ownSave)).isEqualTo(200);

		assertThat(status(call(fx.host(), fx.teacherA(), get("/api/v1/courses/{id}/class-schedule", fx.privB()), null)))
			.isIn(403, 404);
		assertThat(status(call(fx.host(), fx.teacherA(), put("/api/v1/courses/{id}/class-schedule", fx.privB()),
				saveBody(null, null, null, List.of(slotBody("MONDAY", "08:00", "10:00")))))).isIn(403, 404);
		assertThat(status(call(fx.host(), fx.student(), get("/api/v1/courses/{id}/class-schedule", fx.pubA()), null)))
			.isEqualTo(404);
		assertThat(status(call(fx.host(), fx.student(), put("/api/v1/courses/{id}/class-schedule", fx.pubA()),
				saveBody(null, null, null, List.of())))).isEqualTo(404);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM course_class_schedule_revision WHERE tenant_id = ? AND course_id = ?", Long.class,
				fx.tenant().getId(), fx.privB())).isZero();
	}

	@Test
	void crossTenantIdsAreNotFoundEverywhere() {
		Fx a = seed("cal-xa");
		Fx b = seed("cal-xb");
		String range = "from=" + LocalDate.now(COLOMBO) + "&to=" + LocalDate.now(COLOMBO).plusDays(7);

		assertThat(status(call(b.host(), b.admin(), get("/api/v1/courses/{id}/class-schedule", a.pubA()), null)))
			.isEqualTo(404);
		assertThat(status(call(b.host(), b.admin(), put("/api/v1/courses/{id}/class-schedule", a.pubA()),
				saveBody(null, null, null, List.of(slotBody("MONDAY", "08:00", "10:00")))))).isEqualTo(404);
		assertThat(status(call(b.host(), b.admin(),
				get("/api/v1/calendar/occurrences?" + range + "&courseId=" + a.pubA()), null))).isEqualTo(404);
		assertThat(status(call(b.host(), b.admin(),
				get("/api/v1/calendar/timetable.pdf?" + range + "&courseId=" + a.pubA()), null))).isEqualTo(404);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM course_class_schedule_revision WHERE course_id = ?", Long.class, a.pubA()))
			.isZero();
	}

	// ------------------------------------------------------------------
	// Occurrences, scopes, course options, PDF.
	// ------------------------------------------------------------------

	@Test
	void occurrencesFollowTheCallersScopeAndThePdfMatchesTheJson() throws Exception {
		Fx fx = seed("cal-occ");
		for (UUID course : List.of(fx.pubA(), fx.privB(), fx.draftA(), fx.pubC(), fx.archivedA())) {
			seedDailySchedule(fx, course);
		}
		LocalDate today = LocalDate.now(COLOMBO);
		String range = "from=" + today.minusDays(3) + "&to=" + today.plusDays(4);

		JsonNode adminData = occurrences(fx.host(), fx.admin(), range);
		assertThat(adminData.path("timezone").asString()).isEqualTo("Asia/Colombo");
		assertThat(adminData.path("serverNow").isNull()).isFalse();
		assertThat(courseCounts(adminData)).containsExactlyInAnyOrderEntriesOf(
				Map.of("Alpha Biology", 7, "Beta Physics", 7, "Gamma Draft", 7, "Delta Chem", 7));
		JsonNode first = adminData.path("occurrences").get(0);
		assertThat(first.path("id").asString()).startsWith("r_");
		assertThat(first.path("startLocalTime").asString()).isEqualTo("00:00");
		assertThat(first.path("endLocalTime").asString()).isEqualTo("23:59");
		Set<String> fields = new HashSet<>();
		first.propertyNames().forEach(fields::add);
		assertThat(fields).doesNotContain("meetingUrl", "joinUrl", "passcode", "providerReference",
				"meetingProvider");

		assertThat(courseCounts(occurrences(fx.host(), fx.teacherA(), range)))
			.containsExactlyInAnyOrderEntriesOf(Map.of("Alpha Biology", 7, "Gamma Draft", 7));
		assertThat(courseCounts(occurrences(fx.host(), fx.student(), range)))
			.containsExactlyInAnyOrderEntriesOf(Map.of("Alpha Biology", 7));

		// Course filter outside the caller's scope is a 404, inside narrows.
		assertThat(status(call(fx.host(), fx.student(),
				get("/api/v1/calendar/occurrences?" + range + "&courseId=" + fx.privB()), null))).isEqualTo(404);
		assertThat(status(call(fx.host(), fx.teacherA(),
				get("/api/v1/calendar/occurrences?" + range + "&courseId=" + fx.privB()), null))).isEqualTo(404);
		assertThat(status(call(fx.host(), fx.admin(),
				get("/api/v1/calendar/occurrences?" + range + "&courseId=" + fx.archivedA()), null))).isEqualTo(404);
		assertThat(occurrences(fx.host(), fx.admin(), range + "&courseId=" + fx.privB()).path("occurrences"))
			.hasSize(7);

		// Range validation.
		assertThat(status(call(fx.host(), fx.admin(),
				get("/api/v1/calendar/occurrences?from=" + today + "&to=" + today), null))).isEqualTo(400);
		assertThat(status(call(fx.host(), fx.admin(),
				get("/api/v1/calendar/occurrences?from=" + today + "&to=" + today.plusDays(43)), null)))
			.isEqualTo(400);

		// Course filter options.
		MvcResult options = call(fx.host(), fx.student(), get("/api/v1/calendar/courses"), null);
		assertThat(status(options)).isEqualTo(200);
		List<String> names = new ArrayList<>();
		json(options).path("data").forEach(node -> names.add(node.path("name").asString()));
		assertThat(names).containsExactly("Alpha Biology");

		// PDF: same occurrences as the JSON, rendered as text.
		MvcResult pdf = call(fx.host(), fx.admin(), get("/api/v1/calendar/timetable.pdf?" + range + "&view=WEEK"),
				null);
		assertThat(status(pdf)).isEqualTo(200);
		assertThat(pdf.getResponse().getContentType()).isEqualTo(MediaType.APPLICATION_PDF_VALUE);
		assertThat(pdf.getResponse().getHeader("Content-Disposition"))
			.contains("timetable-" + today.minusDays(3) + "-" + today.plusDays(4) + ".pdf");
		byte[] bytes = pdf.getResponse().getContentAsByteArray();
		assertThat(bytes.length).isGreaterThan(1000);
		String text = pdfText(bytes);
		int jsonTotal = adminData.path("occurrences").size();
		assertThat(count(text, "11:59 PM")).isEqualTo(jsonTotal);
		assertThat(count(text, "Alpha Biology")).isEqualTo(7);
		assertThat(text).doesNotContain("Epsilon Archived");

		MvcResult month = call(fx.host(), fx.student(), get("/api/v1/calendar/timetable.pdf?" + range), null);
		assertThat(status(month)).isEqualTo(200);
		assertThat(pdfText(month.getResponse().getContentAsByteArray())).doesNotContain("Beta Physics");
	}

	// ------------------------------------------------------------------
	// Public Today's Classes.
	// ------------------------------------------------------------------

	@Test
	void publicTodayIsAnAllowlistOfPublicNonCancelledClassesAndRespectsTheSwitch() {
		Fx fx = seed("cal-public");
		for (UUID course : List.of(fx.pubA(), fx.privB(), fx.draftA(), fx.pubC(), fx.archivedA())) {
			seedDailySchedule(fx, course);
		}
		seedCancelledSessionToday(fx, fx.pubC(), fx.teacherAUser().getId());

		JsonNode today = publicToday(fx.host());
		assertThat(today.path("enabled").asBoolean()).isTrue();
		assertThat(today.path("date").asString()).isEqualTo(LocalDate.now(COLOMBO).toString());
		List<String> names = new ArrayList<>();
		today.path("occurrences").forEach(node -> names.add(node.path("courseName").asString()));
		assertThat(names).containsExactly("Alpha Biology");
		Set<String> fields = new HashSet<>();
		today.path("occurrences").get(0).propertyNames().forEach(fields::add);
		assertThat(fields).containsExactlyInAnyOrder("id", "courseName", "courseSlug", "coverUrl", "teacherName",
				"teacherPhotoUrl", "start", "end", "startLocalTime", "endLocalTime", "status");
		assertThat(rawContent(call(fx.host(), null, get("/api/v1/public/calendar/today"), null)))
			.doesNotContain(fx.pubA().toString(), fx.privB().toString(), "student@example.test");

		JsonNode home = json(call(fx.host(), null, get("/api/v1/public/tenant-config/home"), null)).path("data");
		assertThat(home.path("showTodaysClasses").asBoolean()).isTrue();

		MvcResult off = call(fx.host(), fx.admin(), put("/api/v1/tenant-config/{d}", "BRANDING"),
				Map.of("show_todays_classes", false));
		assertThat(status(off)).as(rawContent(off)).isEqualTo(200);
		JsonNode disabled = publicToday(fx.host());
		assertThat(disabled.path("enabled").asBoolean()).isFalse();
		assertThat(disabled.path("occurrences")).isEmpty();
		assertThat(json(call(fx.host(), null, get("/api/v1/public/tenant-config/home"), null)).path("data")
			.path("showTodaysClasses")
			.asBoolean()).isFalse();
	}

	@Test
	void deletingAnUnusedCourseRemovesItsScheduleButDirectDeletesAreRejected() {
		Fx fx = seed("cal-delete");
		seedDailySchedule(fx, fx.draftA());

		org.assertj.core.api.Assertions
			.assertThatThrownBy(() -> jdbcTemplate.update(
					"UPDATE course_class_schedule_revision SET effective_from = '2026-01-01' WHERE course_id = ?",
					fx.draftA()))
			.hasMessageContaining("append-only");
		org.assertj.core.api.Assertions
			.assertThatThrownBy(() -> jdbcTemplate.update(
					"DELETE FROM course_class_schedule_slot WHERE tenant_id = ?", fx.tenant().getId()))
			.hasMessageContaining("append-only");

		MvcResult deleted = call(fx.host(), fx.admin(), delete("/api/v1/courses/{id}", fx.draftA()), null);
		assertThat(status(deleted)).as(rawContent(deleted)).isEqualTo(200);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM course_class_schedule_revision WHERE course_id = ?", Long.class, fx.draftA()))
			.isZero();
	}

	// ------------------------------------------------------------------
	// Helpers.
	// ------------------------------------------------------------------

	private static Map<String, Object> saveBody(String expectedRevisionId, String from, String to,
			List<Map<String, Object>> slots) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("expectedRevisionId", expectedRevisionId);
		body.put("effectiveFrom", from);
		body.put("effectiveTo", to);
		body.put("slots", slots);
		return body;
	}

	private static Map<String, Object> slotBody(String day, String start, String end) {
		return Map.of("dayOfWeek", day, "startTime", start, "endTime", end);
	}

	private JsonNode schedule(String host, String token, UUID courseId, int expectedStatus) {
		MvcResult result = call(host, token, get("/api/v1/courses/{id}/class-schedule", courseId), null);
		assertThat(status(result)).as(rawContent(result)).isEqualTo(expectedStatus);
		return json(result).path("data");
	}

	private JsonNode occurrences(String host, String token, String query) {
		MvcResult result = call(host, token, get("/api/v1/calendar/occurrences?" + query), null);
		assertThat(status(result)).as(rawContent(result)).isEqualTo(200);
		return json(result).path("data");
	}

	private JsonNode publicToday(String host) {
		MvcResult result = call(host, null, get("/api/v1/public/calendar/today"), null);
		assertThat(status(result)).as(rawContent(result)).isEqualTo(200);
		return json(result).path("data");
	}

	private static List<String> slotStrings(JsonNode schedule) {
		List<String> slots = new ArrayList<>();
		schedule.path("slots")
			.forEach(slot -> slots.add(slot.path("dayOfWeek").asString() + " " + slot.path("startTime").asString() + "-"
					+ slot.path("endTime").asString()));
		return slots;
	}

	private static Map<String, Integer> courseCounts(JsonNode data) {
		Map<String, Integer> counts = new LinkedHashMap<>();
		data.path("occurrences").forEach(node -> counts.merge(node.path("courseName").asString(), 1, Integer::sum));
		return counts;
	}

	private static String pdfText(byte[] bytes) throws Exception {
		PdfReader reader = new PdfReader(bytes);
		try {
			StringBuilder text = new StringBuilder();
			PdfTextExtractor extractor = new PdfTextExtractor(reader);
			for (int page = 1; page <= reader.getNumberOfPages(); page++) {
				text.append(extractor.getTextFromPage(page)).append('\n');
			}
			return text.toString();
		}
		finally {
			reader.close();
		}
	}

	private static int count(String haystack, String needle) {
		int count = 0;
		for (int index = haystack.indexOf(needle); index >= 0; index = haystack.indexOf(needle, index + 1)) {
			count++;
		}
		return count;
	}

	private MvcResult call(String host, String token, MockHttpServletRequestBuilder builder, Object body) {
		if (body != null) {
			builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
		}
		return perform(authenticated(builder, host, token));
	}

	private JsonNode json(MvcResult result) {
		return objectMapper.readTree(rawContent(result));
	}

	private static int status(MvcResult result) {
		return result.getResponse().getStatus();
	}

}
