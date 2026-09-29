package com.lms.integrationmanagement.media;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.liveclassmanagement.web.dto.ClassSessionJoinResponse;
import com.lms.liveclassmanagement.web.dto.ClassSessionResponse;
import com.lms.tenantmanagement.domain.Tenant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * MEDIA-INT-01 / ADR-018 §4: live classes as real Zoom meetings on the
 * session teacher's OWN licensed Zoom account, personal join links, and the
 * Zoom webhook.
 */
class ZoomLiveClassIntegrationTest extends MediaIntegrationTestSupport {

	private int classSessions(UUID tenantId) {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM class_session WHERE tenant_id = ?", Integer.class,
				tenantId);
	}

	private UUID providerConnectionOf(UUID sessionId) {
		return jdbcTemplate.queryForObject("SELECT provider_connection_id FROM class_session WHERE id = ?", UUID.class,
				sessionId);
	}

	@Test
	void schedulingIsBlockedUntilTheTeacherConnectsZoom() {
		LiveClassFixture f = seedLiveClassFixture("zl-block");

		HttpResult<ClassSessionResponse> result = scheduleSession(f.host(), f.teacherToken(),
				newSessionRequest(f.course().id(), f.lessonId(), "Blocked"));

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(result.getBody().error().code()).isEqualTo("PROVIDER_NOT_CONNECTED");
		assertThat(classSessions(f.tenant().getId())).isZero();
		assertThat(PROVIDERS.requests("POST", "/users/me/meetings")).isEmpty();
	}

	@Test
	void aBasicZoomAccountCannotHostLiveClasses() {
		LiveClassFixture f = seedLiveClassFixture("zl-basic");
		PROVIDERS.setZoomUserType(1);
		connectTeacher(f.host(), f.teacherToken(), "zoom");

		HttpResult<ClassSessionResponse> result = scheduleSession(f.host(), f.teacherToken(),
				newSessionRequest(f.course().id(), f.lessonId(), "Basic"));

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(result.getBody().error().code()).isEqualTo("PROVIDER_LICENSE_REQUIRED");
		assertThat(classSessions(f.tenant().getId())).isZero();
	}

	@Test
	void meetingsAreCreatedOnTheTeachersOwnAccountAndEveryAttendeeGetsAPersonalLink() {
		LiveClassFixture f = seedLiveClassFixture("zl-happy");
		connectTeacher(f.host(), f.teacherToken(), "zoom");
		UUID connectionId = currentConnectionId(f.teacher().getId(), "ZOOM");

		// Staff scheduling for the course still hosts on the course TEACHER's account.
		ClassSessionResponse session = scheduleSessionOrFail(f.host(), f.adminToken(),
				newSessionRequest(f.course().id(), f.lessonId(), "Algebra"));

		assertThat(session.providerStatus()).isEqualTo("PROVISIONED");
		assertThat(providerConnectionOf(session.id())).isEqualTo(connectionId);
		String meetingId = providerReferenceOf(session.id());
		assertThat(meetingId).matches("\\d+");
		assertThat(PROVIDERS.requests("POST", "/users/me/meetings")).singleElement().satisfies(request -> {
			assertThat(request.authorization()).startsWith("Bearer zoom-at-");
			assertThat(request.body()).contains("\"approval_type\":0").contains("\"registrants_email_notification\":false");
		});
		assertThat(session.hostConnectionStatus()).isEqualTo("ACTIVE");
		assertThat(getSession(f.host(), f.studentToken(), session.id()).getBody().data().hostConnectionStatus())
			.isNull();

		startSession(f.host(), f.teacherToken(), session.id());

		HttpResult<ClassSessionJoinResponse> teacherJoin = join(f.host(), f.teacherToken(), session.id());
		assertThat(teacherJoin.getBody().data().joinUrl()).startsWith("https://zoom.example.test/s/" + meetingId);

		HttpResult<ClassSessionJoinResponse> studentJoin = join(f.host(), f.studentToken(), session.id());
		assertThat(studentJoin.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(studentJoin.getBody().data().joinUrl()).startsWith("https://zoom.example.test/w/" + meetingId)
			.contains("registrant-");
		assertThat(PROVIDERS.requests("POST", "/meetings/" + meetingId + "/registrants")).singleElement()
			.satisfies(request -> assertThat(request.body()).contains("student@example.test"));
		assertThat(PROVIDERS.requests("GET", "/meetings/" + meetingId)).hasSize(1);

		// Zoom cloud recordings are not served in this phase.
		completeSession(f.host(), f.teacherToken(), session.id());
		assertThat(getRecording(f.host(), f.studentToken(), session.id()).getStatusCode())
			.isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void disconnectingZoomBreaksJoiningUntilTheMeetingIsRecreated() {
		LiveClassFixture f = seedLiveClassFixture("zl-recreate");
		connectTeacher(f.host(), f.teacherToken(), "zoom");
		UUID firstConnection = currentConnectionId(f.teacher().getId(), "ZOOM");
		ClassSessionResponse session = scheduleSessionOrFail(f.host(), f.teacherToken(),
				newSessionRequest(f.course().id(), f.lessonId(), "Recreate"));
		String firstMeeting = providerReferenceOf(session.id());

		assertThat(json(teacherImpact(f.host(), f.teacherToken(), firstConnection)).path("data")
			.path("upcomingSessionCount").asLong()).isEqualTo(1);
		assertThat(status(teacherDisconnect(f.host(), f.teacherToken(), firstConnection))).isEqualTo(200);
		startSession(f.host(), f.teacherToken(), session.id());

		HttpResult<ClassSessionJoinResponse> blocked = join(f.host(), f.studentToken(), session.id());
		assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(blocked.getBody().error().code()).isEqualTo("PROVIDER_NOT_CONNECTED");
		assertThat(getSession(f.host(), f.teacherToken(), session.id()).getBody().data().hostConnectionStatus())
			.isEqualTo("DISCONNECTED");

		HttpResult<ClassSessionResponse> notYet = retryProvisioning(f.host(), f.teacherToken(), session.id());
		assertThat(notYet.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(notYet.getBody().error().code()).isEqualTo("PROVIDER_NOT_CONNECTED");

		connectTeacher(f.host(), f.teacherToken(), "zoom");
		UUID secondConnection = currentConnectionId(f.teacher().getId(), "ZOOM");
		HttpResult<ClassSessionResponse> recreated = retryProvisioning(f.host(), f.teacherToken(), session.id());

		assertThat(recreated.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(recreated.getBody().data().hostConnectionStatus()).isEqualTo("ACTIVE");
		assertThat(providerReferenceOf(session.id())).isNotEqualTo(firstMeeting);
		assertThat(providerConnectionOf(session.id())).isEqualTo(secondConnection);
		assertThat(join(f.host(), f.studentToken(), session.id()).getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	void cancellingASessionDeletesItsZoomMeeting() {
		LiveClassFixture f = seedLiveClassFixture("zl-cancel");
		connectTeacher(f.host(), f.teacherToken(), "zoom");
		ClassSessionResponse session = scheduleSessionOrFail(f.host(), f.teacherToken(),
				newSessionRequest(f.course().id(), f.lessonId(), "Cancelled"));
		String meetingId = providerReferenceOf(session.id());

		assertThat(cancelSession(f.host(), f.teacherToken(), session.id()).getStatusCode()).isEqualTo(HttpStatus.OK);

		assertThat(PROVIDERS.requests("DELETE", "/meetings/" + meetingId)).hasSize(1);
	}

	@Test
	void theZoomWebhookRejectsAnInvalidSignature() {
		MvcResult result = zoomWebhook("{\"event\":\"meeting.started\",\"event_ts\":1,\"payload\":{}}", false);

		assertThat(status(result)).isEqualTo(401);
	}

	@Test
	void theZoomUrlValidationChallengeIsAnswered() {
		MvcResult result = zoomWebhook(
				"{\"event\":\"endpoint.url_validation\",\"event_ts\":1,\"payload\":{\"plainToken\":\"plain-123\"}}",
				true);

		assertThat(status(result)).isEqualTo(200);
		JsonNode body = json(result);
		assertThat(body.path("plainToken").asString()).isEqualTo("plain-123");
		assertThat(body.path("encryptedToken").asString()).isEqualTo(hmacHex(ZOOM_WEBHOOK_SECRET, "plain-123"));
	}

	@Test
	void appDeauthorizedDisconnectsOnlyThatZoomUsersConnections() {
		Tenant tenantA = seedActiveTenant(uniqueSubdomain("zl-deauth-a"));
		TenantUser teacherA = seedTenantUser(tenantA.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String hostA = hostFor(tenantA.getSubdomain());
		PROVIDERS.setZoomUserId("zoom-user-deauth-A");
		connectTeacher(hostA, loginAndGetToken(hostA, "teacher@example.test"), "zoom");

		Tenant tenantB = seedActiveTenant(uniqueSubdomain("zl-deauth-b"));
		TenantUser teacherB = seedTenantUser(tenantB.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String hostB = hostFor(tenantB.getSubdomain());
		PROVIDERS.setZoomUserId("zoom-user-deauth-B");
		connectTeacher(hostB, loginAndGetToken(hostB, "teacher@example.test"), "zoom");
		UUID connectionA = currentConnectionId(teacherA.getId(), "ZOOM");
		UUID connectionB = currentConnectionId(teacherB.getId(), "ZOOM");

		MvcResult result = zoomWebhook("{\"event\":\"app_deauthorized\",\"event_ts\":1,\"payload\":{"
				+ "\"user_id\":\"zoom-user-deauth-A\",\"account_id\":\"acc\"}}", true);

		assertThat(status(result)).isEqualTo(200);
		assertThat(connectionStatus(connectionA)).isEqualTo("DISCONNECTED");
		assertThat(jdbcTemplate.queryForObject(
				"SELECT disconnected_by FROM media_provider_connection WHERE id = ?", String.class, connectionA))
			.isEqualTo("PROVIDER");
		assertThat(connectionStatus(connectionB)).isEqualTo("ACTIVE");
	}

	@Test
	void aDuplicateMeetingEventIsRecordedOnceUnderTheSessionsTenant() {
		LiveClassFixture f = seedLiveClassFixture("zl-dup");
		connectTeacher(f.host(), f.teacherToken(), "zoom");
		ClassSessionResponse session = scheduleSessionOrFail(f.host(), f.teacherToken(),
				newSessionRequest(f.course().id(), f.lessonId(), "Events"));
		String meetingId = providerReferenceOf(session.id());
		String body = "{\"event\":\"meeting.started\",\"event_ts\":1700000000000,\"payload\":{\"object\":{\"id\":\""
				+ meetingId + "\"}}}";

		assertThat(status(zoomWebhook(body, true))).isEqualTo(200);
		assertThat(status(zoomWebhook(body, true))).isEqualTo(200);

		String key = "meeting.started:1700000000000:" + meetingId;
		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM class_session_provider_event WHERE provider = 'ZOOM' AND provider_event_id = ?",
				Integer.class, key))
			.isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT tenant_id FROM class_session_provider_event WHERE provider_event_id = ?", UUID.class, key))
			.isEqualTo(f.tenant().getId());
	}

}
