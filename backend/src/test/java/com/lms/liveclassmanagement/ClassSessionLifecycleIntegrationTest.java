package com.lms.liveclassmanagement;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.liveclassmanagement.web.dto.ClassSessionResponse;
import com.lms.liveclassmanagement.web.dto.ClassSessionUpdateRequest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Testcontainers/MockMvc coverage for the full schedule -> provision -> start
 * -> complete/cancel lifecycle and the permission matrix (Wave 4 plan §8).
 */
class ClassSessionLifecycleIntegrationTest extends LiveClassManagementTestSupport {

	@Test
	void teacherSchedulesOwnCourseSessionAndItIsProvisionedAndPersisted() {
		LiveClassFixture fixture = seedLiveClassFixture("lc-schedule");

		ClassSessionResponse created = scheduleSessionOrFail(fixture.host(), fixture.teacherToken(),
				newSessionRequest(fixture.course().id(), fixture.lessonId(), "Algebra Live"));

		assertThat(created.status()).isEqualTo("SCHEDULED");
		assertThat(created.providerStatus()).isEqualTo("PROVISIONED");
		assertThat(created.teacherId()).isEqualTo(fixture.teacher().getId());
		assertThat(created.courseId()).isEqualTo(fixture.course().id());

		var row = jdbcTemplate.queryForMap(
				"SELECT tenant_id, course_id, teacher_id, status, provider_status, provider_reference "
						+ "FROM class_session WHERE id = ?",
				created.id());
		assertThat(row.get("tenant_id")).isEqualTo(fixture.tenant().getId());
		assertThat(row.get("status")).isEqualTo("SCHEDULED");
		assertThat(row.get("provider_status")).isEqualTo("PROVISIONED");
		assertThat(row.get("provider_reference")).asString().startsWith("FAKE-ZOOM-");
	}

	@Test
	void teacherSchedulingForAnotherTeachersCourseIsForbiddenAndWritesNoRow() {
		LiveClassFixture fixture = seedLiveClassFixture("lc-wrong-teacher");
		TenantUser otherTeacher = seedTenantUser(fixture.tenant().getId(), "teacher2@example.test", RAW_PASSWORD,
				Role.TEACHER);
		String otherTeacherToken = loginAndGetToken(fixture.host(), "teacher2@example.test");

		HttpResult<ClassSessionResponse> result = scheduleSession(fixture.host(), otherTeacherToken,
				newSessionRequest(fixture.course().id(), fixture.lessonId(), "Not my course"));

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM class_session WHERE course_id = ?", Long.class,
				fixture.course().id());
		assertThat(count).isEqualTo(0L);
		assertThat(otherTeacher.getId()).isNotNull();
	}

	@Test
	void tenantAdminSchedulesAnySessionViaStaffPermission() {
		LiveClassFixture fixture = seedLiveClassFixture("lc-admin-schedule");

		ClassSessionResponse created = scheduleSessionOrFail(fixture.host(), fixture.adminToken(),
				newSessionRequest(fixture.course().id(), null, "Admin scheduled"));

		assertThat(created.status()).isEqualTo("SCHEDULED");
	}

	@Test
	void courseCoordinatorCanScheduleButStudentCannot() {
		LiveClassFixture fixture = seedLiveClassFixture("lc-coordinator");
		seedTenantUser(fixture.tenant().getId(), "coordinator@example.test", RAW_PASSWORD, Role.COURSE_COORDINATOR);
		String coordinatorToken = loginAndGetToken(fixture.host(), "coordinator@example.test");

		HttpResult<ClassSessionResponse> coordinatorResult = scheduleSession(fixture.host(), coordinatorToken,
				newSessionRequest(fixture.course().id(), null, "Coordinator scheduled"));
		assertThat(coordinatorResult.getStatusCode()).isEqualTo(HttpStatus.CREATED);

		HttpResult<ClassSessionResponse> studentResult = scheduleSession(fixture.host(), fixture.studentToken(),
				newSessionRequest(fixture.course().id(), null, "Student attempt"));
		assertThat(studentResult.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void readOnlyAuditorCannotScheduleASession() {
		LiveClassFixture fixture = seedLiveClassFixture("lc-auditor");
		seedTenantUser(fixture.tenant().getId(), "auditor@example.test", RAW_PASSWORD, Role.READ_ONLY_AUDITOR);
		String auditorToken = loginAndGetToken(fixture.host(), "auditor@example.test");

		HttpResult<ClassSessionResponse> result = scheduleSession(fixture.host(), auditorToken,
				newSessionRequest(fixture.course().id(), null, "Auditor attempt"));

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void fullStatusLifecycleScheduledLiveCompleted() {
		LiveClassFixture fixture = seedLiveClassFixture("lc-lifecycle");
		ClassSessionResponse created = scheduleSessionOrFail(fixture.host(), fixture.teacherToken(),
				newSessionRequest(fixture.course().id(), null, "Lifecycle session"));

		HttpResult<ClassSessionResponse> started = startSession(fixture.host(), fixture.teacherToken(), created.id());
		assertThat(started.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(started.getBody().data().status()).isEqualTo("LIVE");

		HttpResult<ClassSessionResponse> completed = completeSession(fixture.host(), fixture.teacherToken(),
				created.id());
		assertThat(completed.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(completed.getBody().data().status()).isEqualTo("COMPLETED");
	}

	@Test
	void cancelIsLegalFromScheduled() {
		LiveClassFixture fixture = seedLiveClassFixture("lc-cancel");
		ClassSessionResponse created = scheduleSessionOrFail(fixture.host(), fixture.teacherToken(),
				newSessionRequest(fixture.course().id(), null, "Cancel me"));

		HttpResult<ClassSessionResponse> cancelled = cancelSession(fixture.host(), fixture.teacherToken(), created.id());

		assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(cancelled.getBody().data().status()).isEqualTo("CANCELLED");
	}

	@Test
	void completingBeforeStartingIsAnIllegalTransitionReturning409() {
		LiveClassFixture fixture = seedLiveClassFixture("lc-illegal-transition");
		ClassSessionResponse created = scheduleSessionOrFail(fixture.host(), fixture.teacherToken(),
				newSessionRequest(fixture.course().id(), null, "Not yet live"));

		HttpResult<ClassSessionResponse> completed = completeSession(fixture.host(), fixture.teacherToken(),
				created.id());

		assertThat(completed.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		var row = jdbcTemplate.queryForMap("SELECT status FROM class_session WHERE id = ?", created.id());
		assertThat(row.get("status")).isEqualTo("SCHEDULED");
	}

	@Test
	void cancellingAnAlreadyCompletedSessionReturns409() {
		LiveClassFixture fixture = seedLiveClassFixture("lc-cancel-completed");
		ClassSessionResponse created = scheduleSessionOrFail(fixture.host(), fixture.teacherToken(),
				newSessionRequest(fixture.course().id(), null, "Will complete"));
		startSession(fixture.host(), fixture.teacherToken(), created.id());
		completeSession(fixture.host(), fixture.teacherToken(), created.id());

		HttpResult<ClassSessionResponse> cancelled = cancelSession(fixture.host(), fixture.teacherToken(), created.id());

		assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
	}

	@Test
	void retryProvisioningIsIdempotentNoOpWhenAlreadyProvisioned() {
		LiveClassFixture fixture = seedLiveClassFixture("lc-retry-noop");
		ClassSessionResponse created = scheduleSessionOrFail(fixture.host(), fixture.teacherToken(),
				newSessionRequest(fixture.course().id(), null, "Already provisioned"));
		String originalReference = providerReferenceOf(created.id());

		HttpResult<ClassSessionResponse> retried = retryProvisioning(fixture.host(), fixture.teacherToken(),
				created.id());

		assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(retried.getBody().data().providerStatus()).isEqualTo("PROVISIONED");
		assertThat(providerReferenceOf(created.id())).isEqualTo(originalReference);
	}

	@Test
	void updatingASessionIsLegalOnlyWhileScheduled() {
		LiveClassFixture fixture = seedLiveClassFixture("lc-edit");
		ClassSessionResponse created = scheduleSessionOrFail(fixture.host(), fixture.teacherToken(),
				newSessionRequest(fixture.course().id(), null, "Original title"));
		Instant newStart = Instant.now().plus(2, ChronoUnit.DAYS);
		ClassSessionUpdateRequest editRequest = new ClassSessionUpdateRequest("Updated title", "Updated description",
				newStart, newStart.plus(1, ChronoUnit.HOURS));

		HttpResult<ClassSessionResponse> edited = updateSession(fixture.host(), fixture.teacherToken(), created.id(),
				editRequest);
		assertThat(edited.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(edited.getBody().data().title()).isEqualTo("Updated title");

		startSession(fixture.host(), fixture.teacherToken(), created.id());
		HttpResult<ClassSessionResponse> editAfterStart = updateSession(fixture.host(), fixture.teacherToken(),
				created.id(), editRequest);
		assertThat(editAfterStart.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
	}

	@Test
	void listSessionsIsScopedToTeacherOwnCourses() {
		LiveClassFixture fixtureA = seedLiveClassFixture("lc-list-a");
		LiveClassFixture fixtureB = seedLiveClassFixture("lc-list-b");
		scheduleSessionOrFail(fixtureA.host(), fixtureA.teacherToken(),
				newSessionRequest(fixtureA.course().id(), null, "A session"));
		scheduleSessionOrFail(fixtureB.host(), fixtureB.teacherToken(),
				newSessionRequest(fixtureB.course().id(), null, "B session"));

		HttpResult<java.util.List<ClassSessionResponse>> teacherAList = listSessions(fixtureA.host(),
				fixtureA.teacherToken());

		assertThat(teacherAList.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(teacherAList.getBody().data()).extracting(ClassSessionResponse::title).containsOnly("A session");
	}

	@Test
	void listSessionsIsScopedToStudentActiveEnrollmentsOnly() {
		LiveClassFixture fixture = seedLiveClassFixture("lc-list-student");
		scheduleSessionOrFail(fixture.host(), fixture.teacherToken(),
				newSessionRequest(fixture.course().id(), null, "Enrolled course session"));

		HttpResult<java.util.List<ClassSessionResponse>> studentList = listSessions(fixture.host(),
				fixture.studentToken());

		assertThat(studentList.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(studentList.getBody().data()).hasSize(1);
	}

}
