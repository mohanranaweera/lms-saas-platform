package com.lms.attendancemanagement;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.attendancemanagement.domain.AttendanceStatus;
import com.lms.attendancemanagement.web.dto.AttendanceMarkEntryRequest;
import com.lms.attendancemanagement.web.dto.AttendanceMarkResultResponse;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Testcontainers/MockMvc coverage for {@code POST
 * /api/v1/attendance/sessions/{sessionId}/records} (plan §18's "Backend
 * Testcontainers/integration" list, same-tenant items). Cross-tenant negative
 * cases live in the dedicated {@link AttendanceCrossTenantIntegrationTest}
 * per this codebase's per-domain convention.
 */
class AttendanceMarkingIntegrationTest extends AttendanceManagementTestSupport {

	@Test
	void teacherMarksOwnSessionPersistsAllColumnsCorrectly() {
		AttendanceFixture fixture = seedAttendanceFixture("att-mark-own");

		HttpResult<List<AttendanceMarkResultResponse>> result = markOneStudent(fixture.host(),
				fixture.teacherToken(), fixture.lessonId(), fixture.student().getId(), AttendanceStatus.PRESENT);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		List<AttendanceMarkResultResponse> outcomes = result.getBody().data();
		assertThat(outcomes).hasSize(1);
		assertThat(outcomes.get(0).success()).isTrue();
		assertThat(outcomes.get(0).record().courseId()).isEqualTo(fixture.course().id());
		assertThat(outcomes.get(0).record().sessionId()).isEqualTo(fixture.lessonId());
		assertThat(outcomes.get(0).record().studentId()).isEqualTo(fixture.student().getId());
		assertThat(outcomes.get(0).record().status()).isEqualTo(AttendanceStatus.PRESENT);
		assertThat(outcomes.get(0).record().markedBy()).isEqualTo(fixture.teacher().getId());

		var row = jdbcTemplate.queryForMap(
				"SELECT tenant_id, course_id, session_id, student_id, status, marked_by, marked_at, created_at, "
						+ "updated_at FROM attendance_record WHERE session_id = ? AND student_id = ?",
				fixture.lessonId(), fixture.student().getId());
		assertThat(row.get("tenant_id")).isEqualTo(fixture.tenant().getId());
		assertThat(row.get("course_id")).isEqualTo(fixture.course().id());
		assertThat(row.get("session_id")).isEqualTo(fixture.lessonId());
		assertThat(row.get("student_id")).isEqualTo(fixture.student().getId());
		assertThat(row.get("status")).isEqualTo("PRESENT");
		assertThat(row.get("marked_by")).isEqualTo(fixture.teacher().getId());
		assertThat(row.get("marked_at")).isNotNull();
		assertThat(row.get("created_at")).isNotNull();
		assertThat(row.get("updated_at")).isNotNull();
	}

	@Test
	void teacherMarkingOutsideTheirAssignmentReturns403AndWritesZeroRows() {
		AttendanceFixture fixture = seedAttendanceFixture("att-mark-outside");
		TenantUser otherTeacher = seedTenantUser(fixture.tenant().getId(), "teacher2@example.test", RAW_PASSWORD,
				Role.TEACHER);
		String otherTeacherToken = loginAndGetToken(fixture.host(), "teacher2@example.test");

		HttpResult<List<AttendanceMarkResultResponse>> result = markOneStudent(fixture.host(), otherTeacherToken,
				fixture.lessonId(), fixture.student().getId(), AttendanceStatus.PRESENT);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM attendance_record WHERE session_id = ?",
				Long.class, fixture.lessonId());
		assertThat(count).isEqualTo(0L);
		assertThat(otherTeacher.getId()).isNotNull();
	}

	@Test
	void reMarkingTheSameSessionStudentUpdatesInPlaceRowCountStaysOne() {
		AttendanceFixture fixture = seedAttendanceFixture("att-mark-remark");

		HttpResult<List<AttendanceMarkResultResponse>> first = markOneStudent(fixture.host(), fixture.teacherToken(),
				fixture.lessonId(), fixture.student().getId(), AttendanceStatus.PRESENT);
		assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
		Instant firstMarkedAt = first.getBody().data().get(0).record().markedAt();
		UUID firstRowId = first.getBody().data().get(0).record().id();

		HttpResult<List<AttendanceMarkResultResponse>> second = markOneStudent(fixture.host(),
				fixture.teacherToken(), fixture.lessonId(), fixture.student().getId(), AttendanceStatus.ABSENT);

		assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
		AttendanceMarkResultResponse secondOutcome = second.getBody().data().get(0);
		assertThat(secondOutcome.success()).isTrue();
		assertThat(secondOutcome.record().id()).isEqualTo(firstRowId);
		assertThat(secondOutcome.record().status()).isEqualTo(AttendanceStatus.ABSENT);
		assertThat(secondOutcome.record().markedAt()).isAfterOrEqualTo(firstMarkedAt);
		Long count = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM attendance_record WHERE session_id = ? AND student_id = ?", Long.class,
				fixture.lessonId(), fixture.student().getId());
		assertThat(count).isEqualTo(1L);
		String status = jdbcTemplate.queryForObject(
				"SELECT status FROM attendance_record WHERE session_id = ? AND student_id = ?", String.class,
				fixture.lessonId(), fixture.student().getId());
		assertThat(status).isEqualTo("ABSENT");
	}

	@Test
	void attendanceOperatorMarksAnyCourseInTheirTenantViaStaffPermission() {
		AttendanceFixture fixture = seedAttendanceFixture("att-mark-operator");
		seedTenantUser(fixture.tenant().getId(), "operator@example.test", RAW_PASSWORD, Role.ATTENDANCE_OPERATOR);
		String operatorToken = loginAndGetToken(fixture.host(), "operator@example.test");

		HttpResult<List<AttendanceMarkResultResponse>> result = markOneStudent(fixture.host(), operatorToken,
				fixture.lessonId(), fixture.student().getId(), AttendanceStatus.LATE);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(result.getBody().data().get(0).success()).isTrue();
		Long count = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM attendance_record WHERE session_id = ? AND student_id = ?", Long.class,
				fixture.lessonId(), fixture.student().getId());
		assertThat(count).isEqualTo(1L);
	}

	@Test
	void readOnlyAuditorCannotMarkAttendanceAndWritesZeroRows() {
		AttendanceFixture fixture = seedAttendanceFixture("att-mark-auditor");
		seedTenantUser(fixture.tenant().getId(), "auditor@example.test", RAW_PASSWORD, Role.READ_ONLY_AUDITOR);
		String auditorToken = loginAndGetToken(fixture.host(), "auditor@example.test");

		HttpResult<List<AttendanceMarkResultResponse>> result = markOneStudent(fixture.host(), auditorToken,
				fixture.lessonId(), fixture.student().getId(), AttendanceStatus.PRESENT);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM attendance_record WHERE session_id = ?",
				Long.class, fixture.lessonId());
		assertThat(count).isEqualTo(0L);
	}

	@Test
	void markingAStudentNotOnTheRosterIsRejectedForThatRowWithZeroRowsWritten() {
		AttendanceFixture fixture = seedAttendanceFixture("att-mark-not-enrolled");
		TenantUser neverEnrolledStudent = seedActiveStudent(fixture.tenant().getId(), "never-enrolled@example.test");

		HttpResult<List<AttendanceMarkResultResponse>> result = markOneStudent(fixture.host(),
				fixture.teacherToken(), fixture.lessonId(), neverEnrolledStudent.getId(), AttendanceStatus.PRESENT);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		AttendanceMarkResultResponse outcome = result.getBody().data().get(0);
		assertThat(outcome.success()).isFalse();
		assertThat(outcome.record()).isNull();
		assertThat(outcome.reason()).isNotBlank();
		Long count = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM attendance_record WHERE session_id = ? AND student_id = ?", Long.class,
				fixture.lessonId(), neverEnrolledStudent.getId());
		assertThat(count).isEqualTo(0L);
	}

	@Test
	void markingAStudentWithOnlyAnExpiredEnrollmentIsRejectedForThatRowWithZeroRowsWritten() {
		AttendanceFixture fixture = seedAttendanceFixture("att-mark-expired");
		TenantUser expiredStudent = seedActiveStudent(fixture.tenant().getId(), "expired-student@example.test");
		String expiredStudentToken = loginAndGetToken(fixture.host(), "expired-student@example.test");
		enrollStudentThenExpireAccess(fixture.host(), expiredStudentToken, fixture.tenant().getId(),
				expiredStudent.getId(), fixture.course().id());

		HttpResult<List<AttendanceMarkResultResponse>> result = markOneStudent(fixture.host(),
				fixture.teacherToken(), fixture.lessonId(), expiredStudent.getId(), AttendanceStatus.PRESENT);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		AttendanceMarkResultResponse outcome = result.getBody().data().get(0);
		assertThat(outcome.success()).isFalse();
		assertThat(outcome.record()).isNull();
		Long count = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM attendance_record WHERE session_id = ? AND student_id = ?", Long.class,
				fixture.lessonId(), expiredStudent.getId());
		assertThat(count).isEqualTo(0L);

		// Sanity: the fixture's own currently-enrolled student in the SAME
		// batch call still succeeds - proves this is a per-row rejection, not
		// a whole-request failure.
		HttpResult<List<AttendanceMarkResultResponse>> batch = markAttendance(fixture.host(), fixture.teacherToken(),
				fixture.lessonId(),
				List.of(new AttendanceMarkEntryRequest(fixture.student().getId(), AttendanceStatus.PRESENT),
						new AttendanceMarkEntryRequest(expiredStudent.getId(), AttendanceStatus.PRESENT)));
		assertThat(batch.getStatusCode()).isEqualTo(HttpStatus.OK);
		List<AttendanceMarkResultResponse> outcomes = batch.getBody().data();
		assertThat(outcomes).hasSize(2);
		assertThat(outcomes.stream().filter(o -> o.studentId().equals(fixture.student().getId())).findFirst()
			.orElseThrow().success()).isTrue();
		assertThat(outcomes.stream().filter(o -> o.studentId().equals(expiredStudent.getId())).findFirst()
			.orElseThrow().success()).isFalse();
	}

	// ------------------------------------------------------------------
	// Request-body bean validation (MarkAttendanceRequest/AttendanceMarkEntryRequest).
	// ------------------------------------------------------------------

	@Test
	void markAttendanceWithAnEmptyMarksListIsRejectedWith400() {
		AttendanceFixture fixture = seedAttendanceFixture("att-mark-validation-empty");

		HttpResult<List<AttendanceMarkResultResponse>> result = markAttendance(fixture.host(),
				fixture.teacherToken(), fixture.lessonId(), List.of());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM attendance_record WHERE session_id = ?",
				Long.class, fixture.lessonId());
		assertThat(count).isEqualTo(0L);
	}

	@Test
	void markAttendanceWithAMarkEntryMissingStudentIdIsRejectedWith400() {
		AttendanceFixture fixture = seedAttendanceFixture("att-mark-validation-null-student");

		HttpResult<List<AttendanceMarkResultResponse>> result = markAttendance(fixture.host(),
				fixture.teacherToken(), fixture.lessonId(),
				List.of(new AttendanceMarkEntryRequest(null, AttendanceStatus.PRESENT)));

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM attendance_record WHERE session_id = ?",
				Long.class, fixture.lessonId());
		assertThat(count).isEqualTo(0L);
	}

	@Test
	void markAttendanceWithAMarkEntryMissingStatusIsRejectedWith400() {
		AttendanceFixture fixture = seedAttendanceFixture("att-mark-validation-null-status");

		HttpResult<List<AttendanceMarkResultResponse>> result = markAttendance(fixture.host(),
				fixture.teacherToken(), fixture.lessonId(),
				List.of(new AttendanceMarkEntryRequest(fixture.student().getId(), null)));

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM attendance_record WHERE session_id = ?",
				Long.class, fixture.lessonId());
		assertThat(count).isEqualTo(0L);
	}

	@Test
	void markAttendanceWithMoreThanFiveHundredMarksIsRejectedWith400() {
		AttendanceFixture fixture = seedAttendanceFixture("att-mark-validation-too-many");
		List<AttendanceMarkEntryRequest> tooManyMarks = java.util.stream.Stream
			.generate(() -> new AttendanceMarkEntryRequest(UUID.randomUUID(), AttendanceStatus.PRESENT))
			.limit(501)
			.toList();

		HttpResult<List<AttendanceMarkResultResponse>> result = markAttendance(fixture.host(),
				fixture.teacherToken(), fixture.lessonId(), tooManyMarks);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM attendance_record WHERE session_id = ?",
				Long.class, fixture.lessonId());
		assertThat(count).isEqualTo(0L);
	}

}
