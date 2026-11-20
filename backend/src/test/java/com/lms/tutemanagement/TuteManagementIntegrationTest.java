package com.lms.tutemanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.lms.identityaccessservice.domain.Role;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * TUTE-01 over real HTTP + Postgres: tute CRUD / validation / archive, paid-session eligibility
 * (cross-course and cross-tenant identical), stock under concurrency, manual assignment,
 * unroll, status actions, permissions (grant / revoke / Teacher scope / auditor) and tenant
 * isolation.
 */
class TuteManagementIntegrationTest extends TuteTestSupport {

	private UUID paidLesson(Fx fx, UUID courseId) {
		UUID module = createModuleOrFail(fx.host(), fx.adminToken(), courseId, "Module", 1).id();
		UUID lesson = createLessonOrFail(fx.host(), fx.adminToken(), courseId, module, "Paid session", 1).id();
		jdbcTemplate.update("UPDATE course_lesson SET payment_type = 'PAID', price = 500 WHERE id = ?", lesson);
		return lesson;
	}

	@Test
	void crudValidationVersioningAndArchive() {
		Fx fx = seed("tute-crud");
		UUID lesson = paidLesson(fx, fx.course());
		UUID foreignLesson = paidLesson(fx, fx.otherCourse());
		Fx other = seed("tute-crud-other");
		UUID otherTenantLesson = paidLesson(other, other.course());

		JsonNode covered = createTuteOk(fx, fx.course(), tuteBody("Unit 1 notes", 5, "COVERED_BY_CLASS_FEES", null, null));
		assertThat(covered.path("available").asLong()).isEqualTo(5);
		assertThat(covered.path("currency").asString()).isNotBlank();
		UUID coveredId = UUID.fromString(covered.path("id").asString());

		JsonNode paid = createTuteOk(fx, fx.course(), tuteBody("Paid pack", 3, "PAID", 250, lesson));
		assertThat(paid.path("paidLessonId").asString()).isEqualTo(lesson.toString());
		assertThat(paid.path("paidLessonTitle").asString()).isEqualTo("Paid session");
		assertThat(paid.path("price").decimalValue()).isEqualByComparingTo("250");

		// Cross-course / cross-tenant / unknown session: identical 400.
		for (UUID bad : List.of(foreignLesson, otherTenantLesson, UUID.randomUUID())) {
			MvcResult rejected = createTute(fx.host(), fx.adminToken(), fx.course(), tuteBody("X", 1, "PAID", 10, bad));
			assertThat(status(rejected)).isEqualTo(400);
			assertThat(errorCode(rejected)).isEqualTo("TUTE_SESSION_NOT_ELIGIBLE");
		}
		MvcResult price = createTute(fx.host(), fx.adminToken(), fx.course(), tuteBody("X", 1, "FREE", 10, null));
		assertThat(errorCode(price)).isEqualTo("TUTE_PRICE_NOT_ALLOWED");
		MvcResult link = createTute(fx.host(), fx.adminToken(), fx.course(),
				tuteBody("X", 1, "COVERED_BY_CLASS_FEES", null, lesson));
		assertThat(errorCode(link)).isEqualTo("TUTE_SESSION_NOT_ELIGIBLE");
		assertThat(status(createTute(fx.host(), fx.adminToken(), fx.course(), tuteBody("", 1, "FREE", null, null))))
			.isEqualTo(400);
		assertThat(status(createTute(fx.host(), fx.adminToken(), UUID.randomUUID(), tuteBody("X", 1, "FREE", null, null))))
			.isEqualTo(404);

		// List + search.
		JsonNode list = data(call(fx.host(), fx.adminToken(), get("/api/v1/courses/{c}/tutes?search=unit", fx.course())));
		assertThat(list.path("totalElements").asLong()).isEqualTo(1);
		JsonNode sessions = data(call(fx.host(), fx.adminToken(), get("/api/v1/courses/{c}/tutes/paid-sessions", fx.course())));
		assertThat(sessions).hasSize(1);
		assertThat(sessions.get(0).path("lessonId").asString()).isEqualTo(lesson.toString());

		// Edit with version; stale version 409; reopen shows the saved values.
		Map<String, Object> edit = tuteBody("Unit 1 notes v2", 8, "COVERED_BY_CLASS_FEES", null, null);
		edit.put("version", covered.path("version").asLong());
		MvcResult updated = updateTute(fx.host(), fx.adminToken(), fx.course(), coveredId, edit);
		assertThat(status(updated)).as(rawContent(updated)).isEqualTo(200);
		assertThat(data(updated).path("version").asLong()).isGreaterThan(covered.path("version").asLong());
		MvcResult stale = updateTute(fx.host(), fx.adminToken(), fx.course(), coveredId, edit);
		assertThat(status(stale)).isEqualTo(409);
		assertThat(errorCode(stale)).isEqualTo("TUTE_VERSION_CONFLICT");
		JsonNode reopened = data(call(fx.host(), fx.adminToken(), get("/api/v1/courses/{c}/tutes/{t}", fx.course(), coveredId)));
		assertThat(reopened.path("name").asString()).isEqualTo("Unit 1 notes v2");
		assertThat(reopened.path("quantity").asInt()).isEqualTo(8);
		// The tute is addressed through its own course only.
		assertThat(status(call(fx.host(), fx.adminToken(),
				get("/api/v1/courses/{c}/tutes/{t}", fx.otherCourse(), coveredId)))).isEqualTo(404);

		// Delete without history = hard delete; with history = archive (hidden, no edits).
		UUID paidId = UUID.fromString(paid.path("id").asString());
		MvcResult deleted = call(fx.host(), fx.adminToken(), delete("/api/v1/courses/{c}/tutes/{t}", fx.course(), paidId));
		assertThat(data(deleted).path("result").asString()).isEqualTo("DELETED");
		assertThat(count("SELECT count(*) FROM tute WHERE id = ?", paidId)).isZero();

		Student s = student(fx, "crud-student@example.test", "Synthetic Student", "1 Test Road", "Test City", fx.course());
		assertThat(status(assign(fx.host(), fx.adminToken(), fx.course(), coveredId, List.of(s.id()), "POSTAL")))
			.isEqualTo(200);
		MvcResult archived = call(fx.host(), fx.adminToken(), delete("/api/v1/courses/{c}/tutes/{t}", fx.course(), coveredId));
		assertThat(data(archived).path("result").asString()).isEqualTo("ARCHIVED");
		assertThat(data(call(fx.host(), fx.adminToken(), get("/api/v1/courses/{c}/tutes", fx.course())))
			.path("totalElements").asLong()).isZero();
		assertThat(data(call(fx.host(), fx.adminToken(), get("/api/v1/courses/{c}/tutes?includeArchived=true", fx.course())))
			.path("totalElements").asLong()).isEqualTo(1);
		edit.put("version", data(call(fx.host(), fx.adminToken(), get("/api/v1/courses/{c}/tutes/{t}", fx.course(), coveredId)))
			.path("version").asLong());
		assertThat(errorCode(updateTute(fx.host(), fx.adminToken(), fx.course(), coveredId, edit))).isEqualTo("TUTE_ARCHIVED");
		assertThat(count("SELECT count(*) FROM tute_fulfillment WHERE tute_id = ?", coveredId)).isEqualTo(1);
		for (String action : List.of("tute.created", "tute.updated", "tute.deleted", "tute.archived",
				"tute.students_assigned")) {
			assertThat(count("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = ?", fx.tenantId(), action))
				.as(action).isPositive();
		}
	}

	@Test
	void concurrentAssignmentsNeverExceedStockAndAreIdempotent() throws Exception {
		Fx fx = seed("tute-stock");
		UUID tute = UUID.fromString(createTuteOk(fx, fx.course(), tuteBody("Stock", 3, "FREE", null, null)).path("id").asString());
		List<Student> students = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			students.add(student(fx, "stock-" + i + "@example.test", "Stock Student " + i, i + " Road", "City", fx.course()));
		}
		ExecutorService pool = Executors.newFixedThreadPool(6);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<Integer>> results = new ArrayList<>();
		for (Student s : students) {
			results.add(pool.submit(() -> {
				start.await();
				return status(assign(fx.host(), fx.adminToken(), fx.course(), tute, List.of(s.id()), "POSTAL"));
			}));
		}
		start.countDown();
		List<Integer> statuses = new ArrayList<>();
		for (Future<Integer> result : results) {
			statuses.add(result.get(60, TimeUnit.SECONDS));
		}
		pool.shutdown();
		assertThat(statuses).filteredOn(code -> code == 200).hasSize(3);
		assertThat(statuses).filteredOn(code -> code == 409).hasSize(3);
		assertThat(count("SELECT count(*) FROM tute_fulfillment WHERE tute_id = ? AND status <> 'CANCELLED'", tute))
			.isEqualTo(3);

		UUID assignedStudent = jdbcTemplate.queryForObject(
				"SELECT student_id FROM tute_fulfillment WHERE tute_id = ? LIMIT 1", UUID.class, tute);
		MvcResult again = assign(fx.host(), fx.adminToken(), fx.course(), tute, List.of(assignedStudent), null);
		assertThat(status(again)).isEqualTo(200);
		assertThat(data(again).path("added").asInt()).isZero();
		assertThat(data(again).path("alreadyAssigned").asInt()).isEqualTo(1);

		// Out of stock writes nothing; quantity below allocated is refused.
		UUID unassigned = students.stream().map(Student::id)
			.filter(id -> count("SELECT count(*) FROM tute_fulfillment WHERE student_id = ?", id) == 0)
			.findFirst()
			.orElseThrow();
		MvcResult out = assign(fx.host(), fx.adminToken(), fx.course(), tute, List.of(unassigned), null);
		assertThat(errorCode(out)).isEqualTo("TUTE_OUT_OF_STOCK");
		long version = data(call(fx.host(), fx.adminToken(), get("/api/v1/courses/{c}/tutes/{t}", fx.course(), tute)))
			.path("version").asLong();
		Map<String, Object> shrink = tuteBody("Stock", 2, "FREE", null, null);
		shrink.put("version", version);
		assertThat(errorCode(updateTute(fx.host(), fx.adminToken(), fx.course(), tute, shrink)))
			.isEqualTo("TUTE_QUANTITY_BELOW_ALLOCATED");

		// A non-participant (or another tenant's user) cannot be assigned.
		Student outsider = student(fx, "outsider@example.test", "Outsider", "x", "y");
		assertThat(status(assign(fx.host(), fx.adminToken(), fx.course(), tute, List.of(outsider.id()), null)))
			.isEqualTo(400);

		// Unroll releases stock; reason required; terminal rows never change.
		UUID fulfillment = fulfillmentOf(tute, assignedStudent);
		String cancelPath = "/api/v1/courses/{c}/tutes/{t}/fulfillments/{f}/cancel";
		assertThat(status(json(fx.host(), fx.adminToken(), post(cancelPath, fx.course(), tute, fulfillment),
				Map.of("reason", " ")))).isEqualTo(400);
		MvcResult cancelled = json(fx.host(), fx.adminToken(), post(cancelPath, fx.course(), tute, fulfillment),
				Map.of("reason", "Student left the class"));
		assertThat(status(cancelled)).as(rawContent(cancelled)).isEqualTo(200);
		assertThat(data(cancelled).path("status").asString()).isEqualTo("CANCELLED");
		assertThat(errorCode(json(fx.host(), fx.adminToken(), post(cancelPath, fx.course(), tute, fulfillment),
				Map.of("reason", "again")))).isEqualTo("TUTE_INVALID_STATE");
		assertThat(status(assign(fx.host(), fx.adminToken(), fx.course(), tute, List.of(unassigned), null))).isEqualTo(200);

		// Status actions: change method, hand over a collection copy (terminal).
		UUID handOver = fulfillmentOf(tute, unassigned);
		String statusPath = "/api/v1/courses/{c}/tutes/{t}/fulfillments/{f}/status";
		assertThat(errorCode(json(fx.host(), fx.adminToken(), post(statusPath, fx.course(), tute, handOver),
				Map.of("action", "MARK_HANDED_OVER")))).isEqualTo("TUTE_INVALID_STATE");
		assertThat(data(json(fx.host(), fx.adminToken(), post(statusPath, fx.course(), tute, handOver),
				Map.of("action", "CHANGE_METHOD", "method", "COLLECTION"))).path("method").asString()).isEqualTo("COLLECTION");
		assertThat(data(json(fx.host(), fx.adminToken(), post(statusPath, fx.course(), tute, handOver),
				Map.of("action", "MARK_HANDED_OVER"))).path("status").asString()).isEqualTo("DISPATCHED");
		assertThat(errorCode(json(fx.host(), fx.adminToken(), post(statusPath, fx.course(), tute, handOver),
				Map.of("action", "RELEASE_HOLD", "reason", "Checked")))).isEqualTo("TUTE_INVALID_STATE");
		JsonNode tuteView = data(call(fx.host(), fx.adminToken(), get("/api/v1/courses/{c}/tutes/{t}", fx.course(), tute)));
		assertThat(tuteView.path("reserved").asLong()).isEqualTo(2);
		assertThat(tuteView.path("dispatched").asLong()).isEqualTo(1);
		assertThat(tuteView.path("available").asLong()).isZero();
		JsonNode fulfillments = data(call(fx.host(), fx.adminToken(),
				get("/api/v1/courses/{c}/tutes/{t}/fulfillments?status=PENDING", fx.course(), tute)));
		assertThat(fulfillments.path("totalElements").asLong()).isEqualTo(2);
		JsonNode eligible = data(call(fx.host(), fx.adminToken(),
				get("/api/v1/courses/{c}/tutes/{t}/eligible-students?search=stock", fx.course(), tute)));
		assertThat(eligible).hasSize(6);
	}

	@Test
	void capabilitiesAreReadPerRequestAndTeachersAreOwnershipScoped() {
		Fx fx = seed("tute-perm");
		UUID tute = UUID.fromString(createTuteOk(fx, fx.course(), tuteBody("Perm", 5, "FREE", null, null)).path("id").asString());
		UUID otherTute = UUID.fromString(createTuteOk(fx, fx.otherCourse(), tuteBody("Other", 5, "FREE", null, null))
			.path("id").asString());
		seedTenantUser(fx.tenantId(), "operator@example.test", RAW_PASSWORD, Role.OPERATOR);
		String operator = loginAndGetToken(fx.host(), "operator@example.test");
		String list = "/api/v1/courses/{c}/tutes";

		assertThat(status(call(fx.host(), operator, get(list, fx.course())))).isEqualTo(403);
		setGrants(fx, "OPERATOR", true, "TUTE_VIEW_TUTES", "TUTE_DISPATCH_VIEW");
		assertThat(status(call(fx.host(), operator, get(list, fx.course())))).isEqualTo(200);
		assertThat(status(call(fx.host(), operator, get(list + "/{t}", fx.course(), tute)))).as("needs TUTE_VIEW_TUTE")
			.isEqualTo(403);
		assertThat(status(createTute(fx.host(), operator, fx.course(), tuteBody("No", 1, "FREE", null, null))))
			.isEqualTo(403);
		assertThat(status(queue(fx.host(), operator, null))).isEqualTo(200);
		assertThat(status(labels(fx.host(), operator, Map.of("fulfillmentIds", List.of(UUID.randomUUID())))))
			.as("viewing the queue never implies printing").isEqualTo(403);
		assertThat(status(confirm(fx.host(), operator, List.of(UUID.randomUUID())))).isEqualTo(403);
		setGrants(fx, "OPERATOR", false, "TUTE_VIEW_TUTES");
		assertThat(status(call(fx.host(), operator, get(list, fx.course())))).as("revoke applies on the next request")
			.isEqualTo(403);

		// Teacher: nothing by default; when granted, own course only.
		assertThat(status(call(fx.host(), fx.teacherToken(), get(list, fx.course())))).isEqualTo(403);
		setGrants(fx, "TEACHER", true, "TUTE_VIEW_TUTES", "TUTE_ENROLL", "TUTE_DISPATCH_VIEW");
		assertThat(status(call(fx.host(), fx.teacherToken(), get(list, fx.course())))).isEqualTo(200);
		assertThat(status(call(fx.host(), fx.teacherToken(), get(list, fx.otherCourse())))).isEqualTo(403);
		assertThat(status(call(fx.host(), fx.teacherToken(), get(list, UUID.randomUUID())))).isEqualTo(404);
		Student own = student(fx, "own@example.test", "Own Student", "1 Road", "City", fx.course(), fx.otherCourse());
		assertThat(status(assign(fx.host(), fx.teacherToken(), fx.course(), tute, List.of(own.id()), "POSTAL")))
			.isEqualTo(200);
		assertThat(status(assign(fx.host(), fx.teacherToken(), fx.otherCourse(), otherTute, List.of(own.id()), "POSTAL")))
			.isEqualTo(403);
		assertThat(status(assign(fx.host(), fx.adminToken(), fx.otherCourse(), otherTute, List.of(own.id()), "POSTAL")))
			.isEqualTo(200);
		JsonNode teacherQueue = data(queue(fx.host(), fx.teacherToken(), null));
		assertThat(teacherQueue.path("itemCount").asLong()).as("own courses only").isEqualTo(1);
		assertThat(data(queue(fx.host(), fx.adminToken(), null)).path("itemCount").asLong()).isEqualTo(2);

		// Student and Read-only Auditor.
		assertThat(status(call(fx.host(), own.token(), get(list, fx.course())))).isEqualTo(403);
		seedTenantUser(fx.tenantId(), "auditor@example.test", RAW_PASSWORD, Role.READ_ONLY_AUDITOR);
		String auditor = loginAndGetToken(fx.host(), "auditor@example.test");
		assertThat(status(call(fx.host(), auditor, get(list, fx.course())))).isEqualTo(403);
		MvcResult matrix = call(fx.host(), fx.adminToken(), get("/api/v1/staff/permission-matrix"));
		long version = data(matrix).path("version").asLong();
		MvcResult writeForAuditor = json(fx.host(), fx.adminToken(),
				org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/staff/permission-matrix"),
				Map.of("expectedVersion", version, "changes",
						List.of(Map.of("roleCode", "READ_ONLY_AUDITOR", "capabilityKey", "TUTE_CREATE", "granted", true))));
		assertThat(status(writeForAuditor)).isEqualTo(400);
		setGrants(fx, "READ_ONLY_AUDITOR", true, "TUTE_VIEW_TUTES");
		assertThat(status(call(fx.host(), auditor, get(list, fx.course())))).isEqualTo(200);
		assertThat(status(createTute(fx.host(), auditor, fx.course(), tuteBody("No", 1, "FREE", null, null))))
			.isEqualTo(403);
	}

	@Test
	@Tag("cross-tenant")
	void anotherTenantNeverReadsPrintsOrConfirmsTheseTutes() {
		Fx a = seed("tute-iso-a");
		Fx b = seed("tute-iso-b");
		UUID tute = UUID.fromString(createTuteOk(a, a.course(), tuteBody("A only", 5, "FREE", null, null)).path("id").asString());
		Student s = student(a, "iso@example.test", "Iso Student", "1 Road", "City", a.course());
		assertThat(status(assign(a.host(), a.adminToken(), a.course(), tute, List.of(s.id()), "POSTAL"))).isEqualTo(200);
		UUID fulfillment = fulfillmentOf(tute, s.id());

		assertThat(status(call(b.host(), b.adminToken(), get("/api/v1/courses/{c}/tutes", a.course())))).isEqualTo(404);
		assertThat(status(call(b.host(), b.adminToken(), get("/api/v1/courses/{c}/tutes/{t}", a.course(), tute))))
			.isEqualTo(404);
		assertThat(status(call(b.host(), b.adminToken(), get("/api/v1/courses/{c}/tutes/{t}", b.course(), tute))))
			.isEqualTo(404);
		assertThat(status(assign(b.host(), b.adminToken(), b.course(), tute, List.of(s.id()), null))).isEqualTo(404);
		MvcResult print = labels(b.host(), b.adminToken(), Map.of("fulfillmentIds", List.of(fulfillment)));
		assertThat(status(print)).isEqualTo(404);
		assertThat(print.getResponse().getContentType()).doesNotContain("pdf");
		assertThat(status(confirm(b.host(), b.adminToken(), List.of(fulfillment)))).isEqualTo(404);
		JsonNode bQueue = data(queue(b.host(), b.adminToken(), null));
		assertThat(bQueue.path("totalElements").asLong()).isZero();
		assertThat(rawContent(queue(b.host(), b.adminToken(), null))).doesNotContain("Iso Student");
		assertThat(count("SELECT count(*) FROM tute_fulfillment WHERE id = ? AND status = 'PENDING'", fulfillment))
			.isEqualTo(1);
	}

}
