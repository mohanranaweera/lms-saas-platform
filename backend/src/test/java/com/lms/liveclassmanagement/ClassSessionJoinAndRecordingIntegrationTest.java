package com.lms.liveclassmanagement;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.Role;
import com.lms.liveclassmanagement.web.dto.ClassSessionJoinResponse;
import com.lms.liveclassmanagement.web.dto.ClassSessionRecordingResponse;
import com.lms.liveclassmanagement.web.dto.ClassSessionResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Testcontainers/MockMvc coverage for entitlement-gated join/recording
 * access (Wave 4 plan §8): Teacher own-course success / other-course 403,
 * Student active-enrollment+LIVE success, Student not-enrolled/expired ->
 * 404 via {@code NotFoundException}, Student valid-enrollment but not-yet
 * -LIVE -> rejected, recording fetch COMPLETED-only.
 */
class ClassSessionJoinAndRecordingIntegrationTest extends LiveClassManagementTestSupport {

	@Test
	void teacherJoinsOwnLiveSessionSuccessfully() {
		LiveClassFixture fixture = seedLiveClassFixture("lc-join-teacher");
		ClassSessionResponse created = scheduleSessionOrFail(fixture.host(), fixture.teacherToken(),
				newSessionRequest(fixture.course().id(), null, "Join me"));
		startSession(fixture.host(), fixture.teacherToken(), created.id());

		HttpResult<ClassSessionJoinResponse> result = join(fixture.host(), fixture.teacherToken(), created.id());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(result.getBody().data().joinUrl()).startsWith("https://live-class-provider.test/join/");
		assertThat(result.getBody().data().expiresAt()).isAfter(java.time.Instant.now());
	}

	@Test
	void teacherJoiningAnotherTeachersSessionIsForbidden() {
		LiveClassFixture fixture = seedLiveClassFixture("lc-join-wrong-teacher");
		ClassSessionResponse created = scheduleSessionOrFail(fixture.host(), fixture.teacherToken(),
				newSessionRequest(fixture.course().id(), null, "Not yours"));
		startSession(fixture.host(), fixture.teacherToken(), created.id());
		seedTenantUser(fixture.tenant().getId(), "teacher2@example.test", RAW_PASSWORD, Role.TEACHER);
		String otherTeacherToken = loginAndGetToken(fixture.host(), "teacher2@example.test");

		HttpResult<ClassSessionJoinResponse> result = join(fixture.host(), otherTeacherToken, created.id());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void studentJoinsLiveSessionOfEnrolledCourseSuccessfully() {
		LiveClassFixture fixture = seedLiveClassFixture("lc-join-student");
		ClassSessionResponse created = scheduleSessionOrFail(fixture.host(), fixture.teacherToken(),
				newSessionRequest(fixture.course().id(), null, "Join as student"));
		startSession(fixture.host(), fixture.teacherToken(), created.id());

		HttpResult<ClassSessionJoinResponse> result = join(fixture.host(), fixture.studentToken(), created.id());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	void studentJoiningBeforeSessionIsLiveIsRejectedNotFound404NeverALeak() {
		LiveClassFixture fixture = seedLiveClassFixture("lc-join-not-live");
		ClassSessionResponse created = scheduleSessionOrFail(fixture.host(), fixture.teacherToken(),
				newSessionRequest(fixture.course().id(), null, "Not live yet"));

		HttpResult<ClassSessionJoinResponse> result = join(fixture.host(), fixture.studentToken(), created.id());

		// Entitlement is valid (enrolled), so this is a genuine state
		// conflict, not an anti-enumeration case - 409, distinct from the
		// 404 a non-entitled Student gets below.
		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
	}

	@Test
	void studentNotEnrolledInTheCourseGetsNotFoundNeverForbidden() {
		LiveClassFixture fixture = seedLiveClassFixture("lc-join-not-enrolled");
		ClassSessionResponse created = scheduleSessionOrFail(fixture.host(), fixture.teacherToken(),
				newSessionRequest(fixture.course().id(), null, "Not enrolled"));
		startSession(fixture.host(), fixture.teacherToken(), created.id());
		seedActiveStudent(fixture.tenant().getId(), "unenrolled@example.test");
		String unenrolledToken = loginAndGetToken(fixture.host(), "unenrolled@example.test");

		HttpResult<ClassSessionJoinResponse> result = join(fixture.host(), unenrolledToken, created.id());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void studentWithExpiredEnrollmentGetsNotFound() {
		LiveClassFixture fixture = seedLiveClassFixture("lc-join-expired");
		ClassSessionResponse created = scheduleSessionOrFail(fixture.host(), fixture.teacherToken(),
				newSessionRequest(fixture.course().id(), null, "Expired access"));
		startSession(fixture.host(), fixture.teacherToken(), created.id());
		expireEnrollment(fixture.tenant().getId(), fixture.student().getId(), fixture.course().id());

		HttpResult<ClassSessionJoinResponse> result = join(fixture.host(), fixture.studentToken(), created.id());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void crossTenantSessionIdGuessingByAStudentReturnsNotFound() {
		LiveClassFixture fixtureA = seedLiveClassFixture("lc-join-cross-a");
		LiveClassFixture fixtureB = seedLiveClassFixture("lc-join-cross-b");
		ClassSessionResponse createdInA = scheduleSessionOrFail(fixtureA.host(), fixtureA.teacherToken(),
				newSessionRequest(fixtureA.course().id(), null, "Tenant A session"));
		startSession(fixtureA.host(), fixtureA.teacherToken(), createdInA.id());

		HttpResult<ClassSessionJoinResponse> result = join(fixtureB.host(), fixtureB.studentToken(), createdInA.id());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	// ------------------------------------------------------------------
	// Recording.
	// ------------------------------------------------------------------

	@Test
	void recordingIsUnavailableUntilTheWebhookDeliversIt() {
		LiveClassFixture fixture = seedLiveClassFixture("lc-recording-pending");
		ClassSessionResponse created = scheduleSessionOrFail(fixture.host(), fixture.teacherToken(),
				newSessionRequest(fixture.course().id(), null, "Recording pending"));
		startSession(fixture.host(), fixture.teacherToken(), created.id());
		completeSession(fixture.host(), fixture.teacherToken(), created.id());

		HttpResult<ClassSessionRecordingResponse> result = getRecording(fixture.host(), fixture.teacherToken(),
				created.id());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void recordingIsAvailableAfterWebhookDeliveryForTeacherAndEntitledStudent() {
		LiveClassFixture fixture = seedLiveClassFixture("lc-recording-available");
		ClassSessionResponse created = scheduleSessionOrFail(fixture.host(), fixture.teacherToken(),
				newSessionRequest(fixture.course().id(), null, "Recording available"));
		startSession(fixture.host(), fixture.teacherToken(), created.id());
		completeSession(fixture.host(), fixture.teacherToken(), created.id());
		String providerReference = providerReferenceOf(created.id());

		HttpResult<Void> webhook = sendLiveClassWebhook(UUID.randomUUID().toString(), "recording.completed",
				providerReference, "FAKE-ZOOM-REC-1", 3600);
		assertThat(webhook.getStatusCode()).isEqualTo(HttpStatus.OK);

		HttpResult<ClassSessionRecordingResponse> teacherResult = getRecording(fixture.host(), fixture.teacherToken(),
				created.id());
		assertThat(teacherResult.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(teacherResult.getBody().data().playbackUrl())
			.startsWith("https://live-class-provider.test/recordings/");

		HttpResult<ClassSessionRecordingResponse> studentResult = getRecording(fixture.host(), fixture.studentToken(),
				created.id());
		assertThat(studentResult.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	void recordingRequiresCompletedStatus() {
		LiveClassFixture fixture = seedLiveClassFixture("lc-recording-not-completed");
		ClassSessionResponse created = scheduleSessionOrFail(fixture.host(), fixture.teacherToken(),
				newSessionRequest(fixture.course().id(), null, "Still scheduled"));

		HttpResult<ClassSessionRecordingResponse> result = getRecording(fixture.host(), fixture.teacherToken(),
				created.id());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
	}

	// ------------------------------------------------------------------
	// Helpers.
	// ------------------------------------------------------------------

	/**
	 * Mirrors {@code EnrollmentManagementTestSupport#seedExpiredEnrollmentFixture}'s
	 * technique: directly back-dates the current {@code enrollment} row's
	 * {@code access_expires_at} into the past.
	 */
	private void expireEnrollment(UUID tenantId, UUID studentId, UUID courseId) {
		UUID enrollmentId = jdbcTemplate.queryForObject(
				"SELECT id FROM enrollment WHERE tenant_id = ? AND student_id = ? AND course_id = ? "
						+ "AND superseded_at IS NULL",
				UUID.class, tenantId, studentId, courseId);
		int updated = jdbcTemplate.update("UPDATE enrollment SET access_expires_at = now() - interval '1 day' "
				+ "WHERE id = ?", enrollmentId);
		if (updated != 1) {
			throw new IllegalStateException("Expected to expire exactly one enrollment row, updated " + updated);
		}
	}

}
