package com.lms.usermanagement.teacher.storefront;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.paymentmanagement.order.web.dto.OrderResponse;
import com.lms.paymentmanagement.order.web.dto.PaymentInitiationResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * ADR-037 decision 9 (amended): on a storefront host the signed-in student
 * portal's course LISTS (My courses / ENROLLED catalog, dashboard enrollment
 * lists, calendar, exams) show only that storefront's published courses.
 * Detail URLs and access checks are never filtered, the tenant host lists
 * everything, and another tenant is unaffected.
 */
class TeacherStorefrontStudentPortalIntegrationTest extends StorefrontTestSupport {

	@Test
	void enrolledCalendarAndExamListsShowOnlyTheStorefrontsCoursesOnItsHost() {
		Fx fx = seed("sf-lists");
		UUID aliceExam = seedExam(fx, fx.aliceOnly().id());
		UUID bobExam = seedExam(fx, fx.bobOnly().id());

		List<String> alice = ids(fx.aliceOnly(), fx.sharedAliceListed(), fx.sharedBoth());
		List<String> all = ids(fx.aliceOnly(), fx.bobOnly(), fx.sharedAliceListed(), fx.sharedBoth(),
				fx.aliceHidden());

		// My courses (ENROLLED): Alice's published products on her host, Bob's on his, all on the tenant site.
		assertThat(catalogIds(fx.aliceHost(), fx.studentToken())).containsExactlyInAnyOrderElementsOf(alice);
		assertThat(catalogIds(fx.bobHost(), fx.studentToken())).containsExactlyInAnyOrder(
				fx.bobOnly().id().toString(), fx.sharedBoth().id().toString());
		assertThat(catalogIds(fx.host(), fx.studentToken())).containsExactlyInAnyOrderElementsOf(all);

		// Calendar course options follow the same rule.
		assertThat(calendarCourseIds(fx.aliceHost(), fx.studentToken())).containsExactlyInAnyOrderElementsOf(alice);
		assertThat(calendarCourseIds(fx.host(), fx.studentToken())).containsExactlyInAnyOrderElementsOf(all);

		// Exams list: only exams of the storefront's courses; the tenant site lists both.
		assertThat(examIds(fx.aliceHost(), fx.studentToken(), null)).containsExactly(aliceExam.toString());
		assertThat(examIds(fx.host(), fx.studentToken(), null)).containsExactlyInAnyOrder(aliceExam.toString(),
				bobExam.toString());

		// Direct access to an enrolled but unlisted course keeps working on Alice's host.
		String bob = fx.bobOnly().id().toString();
		assertOk(call(fx.aliceHost(), fx.studentToken(), get("/api/v1/courses/{courseId}/content", bob)));
		assertThat(status(call(fx.aliceHost(), fx.studentToken(), get("/api/v1/courses/{courseId}", bob))))
			.isEqualTo(status(call(fx.host(), fx.studentToken(), get("/api/v1/courses/{courseId}", bob))));
		assertOk(call(fx.aliceHost(), fx.studentToken(), get("/api/v1/calendar/occurrences")
			.param("from", "2026-09-01").param("to", "2026-09-30").param("courseId", bob)));
		assertThat(examIds(fx.aliceHost(), fx.studentToken(), fx.bobOnly().id())).containsExactly(bobExam.toString());
		assertOk(call(fx.aliceHost(), fx.studentToken(), get("/api/v1/student/exams/{examId}", bobExam)));
	}

	@Test
	void dashboardEnrollmentListsAreScopedOnTheStorefrontAndPerCourseAccessIsNot() {
		Fx fx = seed("sf-dash");
		CourseResponse aliceCourse = createCourseOrFail(fx.host(), fx.adminToken(),
				newCourseRequest(uniqueSlug("alice-once"), fx.alice().getId(), CourseStatus.PUBLIC));
		CourseResponse bobCourse = createCourseOrFail(fx.host(), fx.adminToken(),
				newCourseRequest(uniqueSlug("bob-once"), fx.bob().getId(), CourseStatus.PUBLIC));
		buy(fx, aliceCourse.id());
		buy(fx, bobCourse.id());
		assertOk(products(fx.host(), fx.adminToken(), fx.alice().getId(),
				List.of(item(fx.aliceOnly().id(), true, 0), item(aliceCourse.id(), true, 1))));

		// GET /enrollments/my and /enrollments/my/courses (dashboard, attendance filter).
		assertThat(enrollmentCourseIds(fx.aliceHost(), fx.studentToken())).containsExactly(aliceCourse.id().toString());
		assertThat(enrollmentCourseIds(fx.host(), fx.studentToken()))
			.containsExactlyInAnyOrder(aliceCourse.id().toString(), bobCourse.id().toString());
		assertThat(summaryIds(fx.aliceHost(), fx.studentToken())).containsExactly(aliceCourse.id().toString());
		assertThat(summaryIds(fx.host(), fx.studentToken()))
			.containsExactlyInAnyOrder(aliceCourse.id().toString(), bobCourse.id().toString());

		// The paid course outside the storefront stays reachable directly on Alice's host.
		assertOk(call(fx.aliceHost(), fx.studentToken(),
				get("/api/v1/courses/{courseId}/content", bobCourse.id())));
	}

	@Test
	void anotherTenantIsUnaffectedAndCannotUseTheStorefrontHost() {
		Fx fx = seed("sf-xt-a");
		Fx other = seed("sf-xt-b");

		// Tenant B's own site still lists every course its student joined.
		assertThat(catalogIds(other.host(), other.studentToken())).hasSize(5);
		// Tenant B's token is never accepted on tenant A's storefront host.
		MvcResult crossTenant = call(fx.aliceHost(), other.studentToken(),
				get("/api/v1/enrollments/my/catalog").param("view", "ENROLLED"));
		assertThat(status(crossTenant)).isIn(401, 403, 404);
		// And tenant A's storefront scope never leaks tenant B's courses.
		assertThat(catalogIds(fx.aliceHost(), fx.studentToken())).doesNotContain(other.aliceOnly().id().toString());
	}

	// ------------------------------------------------------------------

	private void buy(Fx fx, UUID courseId) {
		OrderResponse order = createOrderOrFail(fx.host(), fx.studentToken(), courseId);
		PaymentInitiationResponse payment = initiatePaymentOrFail(fx.host(), fx.studentToken(), order.id());
		assertThat(sendPaymentWebhook(payment.gatewayReference(), true).getStatusCode().value()).isEqualTo(200);
	}

	/** A live Klass exam on {@code courseId} the fixture student is enrolled in. */
	private UUID seedExam(Fx fx, UUID courseId) {
		UUID examId = UUID.randomUUID();
		Instant start = Instant.now().plus(2, ChronoUnit.DAYS);
		jdbcTemplate.update("INSERT INTO exam (id, tenant_id, course_id, title, scheduled_start, scheduled_end, "
				+ "time_limit_minutes, status, config_version, exam_type, created_at, updated_at) "
				+ "VALUES (?, ?, ?, ?, ?, ?, 60, 'DRAFT', 2, 'PHYSICAL', now(), now())", examId,
				fx.tenant().getId(), courseId, "Exam " + examId, Timestamp.from(start),
				Timestamp.from(start.plus(2, ChronoUnit.HOURS)));
		jdbcTemplate.update("INSERT INTO exam_enrollment (id, tenant_id, exam_id, student_id, source, created_at, "
				+ "updated_at) VALUES (?, ?, ?, ?, 'MANUAL', now(), now())", UUID.randomUUID(), fx.tenant().getId(),
				examId, fx.student().getId());
		return examId;
	}

	private static List<String> ids(Course... courses) {
		List<String> ids = new ArrayList<>();
		for (Course course : courses) {
			ids.add(course.id().toString());
		}
		return ids;
	}

	private List<String> catalogIds(String host, String token) {
		MvcResult result = call(host, token,
				get("/api/v1/enrollments/my/catalog").param("view", "ENROLLED").param("size", "100"));
		assertOk(result);
		return collect(json(result).path("data").path("content"), "courseId");
	}

	private List<String> calendarCourseIds(String host, String token) {
		MvcResult result = call(host, token, get("/api/v1/calendar/courses"));
		assertOk(result);
		return collect(json(result).path("data"), "id");
	}

	private List<String> examIds(String host, String token, UUID courseId) {
		var request = get("/api/v1/student/exams");
		if (courseId != null) {
			request = request.param("courseId", courseId.toString());
		}
		MvcResult result = call(host, token, request);
		assertOk(result);
		JsonNode data = json(result).path("data");
		List<String> ids = new ArrayList<>();
		for (String bucket : List.of("upcoming", "inProgress", "previous")) {
			ids.addAll(collect(data.path(bucket), "id"));
		}
		return ids;
	}

	private List<String> enrollmentCourseIds(String host, String token) {
		MvcResult result = call(host, token, get("/api/v1/enrollments/my"));
		assertOk(result);
		return collect(json(result).path("data"), "courseId");
	}

	private List<String> summaryIds(String host, String token) {
		MvcResult result = call(host, token, get("/api/v1/enrollments/my/courses"));
		assertOk(result);
		return collect(json(result).path("data"), "id");
	}

	private static List<String> collect(JsonNode array, String field) {
		List<String> values = new ArrayList<>();
		array.forEach(node -> values.add(node.path(field).asString()));
		return values;
	}

}
