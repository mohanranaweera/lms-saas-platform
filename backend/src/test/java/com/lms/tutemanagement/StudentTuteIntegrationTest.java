package com.lms.tutemanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * TUTE-02 student "Tute Delivery" tab over real HTTP + Postgres: a student sees only their own
 * non-cancelled copies of the course with live status (pending / on hold / dispatched / handed
 * over), never another student's or another tenant's, and never delivery details; staff are
 * refused.
 */
class StudentTuteIntegrationTest extends TuteTestSupport {

	private static final String MY_TUTES = "/api/v1/courses/{c}/my-tutes";

	private JsonNode myTutes(Fx fx, Student s, UUID courseId) {
		MvcResult result = call(fx.host(), s.token(), get(MY_TUTES, courseId));
		assertThat(status(result)).as(rawContent(result)).isEqualTo(200);
		assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
		return data(result);
	}

	@Test
	void studentSeesOwnTutesWithLiveStatus() {
		Fx fx = seed("stute-own");
		Student s = student(fx, "stute-own@example.test", "Synthetic Student", "1 Test Road", "Test City", fx.course());
		Student peer = student(fx, "stute-peer@example.test", "Synthetic Peer", "2 Test Road", "Test City", fx.course());

		assertThat(myTutes(fx, s, fx.course())).isEmpty();

		UUID posted = UUID.fromString(createTuteOk(fx, fx.course(), tuteBody("Unit 1 notes", 5, "FREE", null, null))
			.path("id").asString());
		UUID collected = UUID.fromString(createTuteOk(fx, fx.course(), tuteBody("Unit 2 notes", 5, "FREE", null, null))
			.path("id").asString());
		UUID cancelled = UUID.fromString(createTuteOk(fx, fx.course(), tuteBody("Unit 3 notes", 5, "FREE", null, null))
			.path("id").asString());
		assertThat(status(assign(fx.host(), fx.adminToken(), fx.course(), posted, List.of(s.id(), peer.id()), "POSTAL")))
			.isEqualTo(200);
		assertThat(status(assign(fx.host(), fx.adminToken(), fx.course(), collected, List.of(s.id()), "COLLECTION")))
			.isEqualTo(200);
		assertThat(status(assign(fx.host(), fx.adminToken(), fx.course(), cancelled, List.of(s.id()), "POSTAL")))
			.isEqualTo(200);
		assertThat(status(json(fx.host(), fx.adminToken(),
				post("/api/v1/courses/{c}/tutes/{t}/fulfillments/{f}/cancel", fx.course(), cancelled,
						fulfillmentOf(cancelled, s.id())),
				Map.of("reason", "Student left the class")))).isEqualTo(200);

		JsonNode pending = myTutes(fx, s, fx.course());
		assertThat(pending).hasSize(2);
		assertThat(pending.get(0).path("tuteName").asString()).isEqualTo("Unit 1 notes");
		assertThat(pending.get(0).path("status").asString()).isEqualTo("PENDING");
		assertThat(pending.get(0).path("method").asString()).isEqualTo("POSTAL");
		assertThat(pending.get(0).path("assignedAt").isNull()).isFalse();
		assertThat(pending.get(0).path("dispatchedAt").isNull()).isTrue();
		assertThat(pending.get(1).path("method").asString()).isEqualTo("COLLECTION");

		// Dispatch the postal copy and hand over the collection copy.
		UUID postedRow = fulfillmentOf(posted, s.id());
		assertThat(status(confirm(fx.host(), fx.adminToken(), List.of(postedRow)))).isEqualTo(200);
		assertThat(status(json(fx.host(), fx.adminToken(),
				post("/api/v1/courses/{c}/tutes/{t}/fulfillments/{f}/status", fx.course(), collected,
						fulfillmentOf(collected, s.id())),
				Map.of("action", "MARK_HANDED_OVER")))).isEqualTo(200);

		JsonNode delivered = myTutes(fx, s, fx.course());
		assertThat(delivered).hasSize(2);
		for (JsonNode row : delivered) {
			assertThat(row.path("status").asString()).isEqualTo("DISPATCHED");
			assertThat(row.path("dispatchedAt").isNull()).isFalse();
			// Only display fields - never delivery details, payment or staff identity.
			List<String> fields = new ArrayList<>();
			row.propertyNames().forEach(fields::add);
			assertThat(fields).containsExactlyInAnyOrder("id", "tuteName", "status", "method",
					"assignedAt", "dispatchedAt");
		}

		// The peer's copy is still pending and invisible to the first student.
		JsonNode peerRows = myTutes(fx, peer, fx.course());
		assertThat(peerRows).hasSize(1);
		assertThat(peerRows.get(0).path("status").asString()).isEqualTo("PENDING");
		assertThat(peerRows.get(0).path("id").asString()).isNotEqualTo(postedRow.toString());
	}

	@Test
	void heldCopyShowsOnHold() {
		Fx fx = seed("stute-hold");
		Student s = student(fx, "stute-hold@example.test", "Synthetic Student", "1 Test Road", "Test City", fx.course());
		UUID tute = UUID.fromString(createTuteOk(fx, fx.course(), tuteBody("Held pack", 2, "FREE", null, null))
			.path("id").asString());
		assertThat(status(assign(fx.host(), fx.adminToken(), fx.course(), tute, List.of(s.id()), "POSTAL"))).isEqualTo(200);
		jdbcTemplate.update("UPDATE tute_fulfillment SET payment_reversed_at = now() WHERE id = ?", fulfillmentOf(tute, s.id()));

		assertThat(myTutes(fx, s, fx.course()).get(0).path("status").asString()).isEqualTo("ON_HOLD");
	}

	@Test
	void otherCoursesTenantsAndStaffAreRefused() {
		Fx fx = seed("stute-iso");
		Fx other = seed("stute-iso-other");
		Student s = student(fx, "stute-iso@example.test", "Synthetic Student", "1 Test Road", "Test City", fx.course());
		Student foreign = student(other, "stute-iso@example.test", "Synthetic Foreign", "9 Test Road", "Test City",
				other.course());
		UUID tute = UUID.fromString(createTuteOk(fx, fx.course(), tuteBody("Iso pack", 2, "FREE", null, null))
			.path("id").asString());
		assertThat(status(assign(fx.host(), fx.adminToken(), fx.course(), tute, List.of(s.id()), "POSTAL"))).isEqualTo(200);

		// Copies of one course never show under another course of the tenant.
		assertThat(myTutes(fx, s, fx.otherCourse())).isEmpty();
		// Another tenant's course id, and an unknown id: 404.
		assertThat(status(call(fx.host(), s.token(), get(MY_TUTES, other.course())))).isEqualTo(404);
		assertThat(status(call(fx.host(), s.token(), get(MY_TUTES, UUID.randomUUID())))).isEqualTo(404);
		// A student of another tenant addressing this tenant's course: 404, nothing leaked.
		assertThat(status(call(other.host(), foreign.token(), get(MY_TUTES, fx.course())))).isEqualTo(404);
		// Staff use the staff endpoints; this one is student-only.
		assertThat(status(call(fx.host(), fx.adminToken(), get(MY_TUTES, fx.course())))).isEqualTo(403);
		assertThat(status(call(fx.host(), fx.teacherToken(), get(MY_TUTES, fx.course())))).isEqualTo(403);
	}

}
