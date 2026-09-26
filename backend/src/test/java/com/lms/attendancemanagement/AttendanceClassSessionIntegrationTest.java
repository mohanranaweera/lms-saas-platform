package com.lms.attendancemanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lms.attendancemanagement.domain.AttendanceSheetSource;
import com.lms.attendancemanagement.domain.AttendanceStatus;
import com.lms.attendancemanagement.web.dto.AttendanceMarkEntryRequest;
import com.lms.attendancemanagement.web.dto.AttendanceMarkResultResponse;
import com.lms.attendancemanagement.web.dto.AttendanceRecordResponse;
import com.lms.attendancemanagement.web.dto.ClassSessionRosterResponse;
import com.lms.common.api.PageResponse;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.liveclassmanagement.web.dto.ClassSessionResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

/**
 * Wave 8 class-session attendance (wave-08-plan.md §8): the ClassSession ->
 * AttendanceSheet -> AttendanceRecord workflow end to end against real
 * Postgres - marking, duplicates, the session-lifecycle gate, the roster
 * (including historical, no-longer-enrolled students), role authorization,
 * wrong-teacher/wrong-course rejection, report filtering and student
 * privacy. Cross-tenant cases live in {@link
 * AttendanceClassSessionCrossTenantIntegrationTest}.
 */
class AttendanceClassSessionIntegrationTest extends AttendanceClassSessionTestSupport {

	// ------------------------------------------------------------------
	// Core workflow + sheet semantics.
	// ------------------------------------------------------------------

	@Test
	void teacherMarksAStartedSessionCreatingExactlyOneClassSessionSheetAndRecord() {
		AttendanceFixture fixture = seedAttendanceFixture("att-cs-mark");
		ClassSessionResponse session = scheduleStartedSession(fixture, "Week 1");

		HttpResult<List<AttendanceMarkResultResponse>> result = markClassSessionOne(fixture.host(),
				fixture.teacherToken(), session.id(), fixture.student().getId(), AttendanceStatus.PRESENT);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		AttendanceMarkResultResponse outcome = result.getBody().data().get(0);
		assertThat(outcome.success()).isTrue();
		AttendanceRecordResponse record = outcome.record();
		assertThat(record.source()).isEqualTo(AttendanceSheetSource.CLASS_SESSION);
		assertThat(record.classSessionId()).isEqualTo(session.id());
		assertThat(record.classSessionTitle()).isEqualTo("Week 1");
		assertThat(record.courseId()).isEqualTo(fixture.course().id());
		assertThat(record.sessionId()).as("legacy lesson id is null for a class-session record").isNull();
		assertThat(record.markedBy()).isEqualTo(fixture.teacher().getId());
		assertThat(countSheetsForSession(session.id())).isEqualTo(1L);
		assertThat(countRecordsForSession(session.id())).isEqualTo(1L);
	}

	@Test
	void reMarkingTheSameStudentUpdatesInPlaceAndTheRosterReflectsTheLatestStatus() {
		AttendanceFixture fixture = seedAttendanceFixture("att-cs-remark");
		ClassSessionResponse session = scheduleStartedSession(fixture, "Week 1");

		markClassSessionOne(fixture.host(), fixture.teacherToken(), session.id(), fixture.student().getId(),
				AttendanceStatus.PRESENT);
		markClassSessionOne(fixture.host(), fixture.adminToken(), session.id(), fixture.student().getId(),
				AttendanceStatus.LATE);

		assertThat(countSheetsForSession(session.id())).isEqualTo(1L);
		assertThat(countRecordsForSession(session.id())).isEqualTo(1L);
		ClassSessionRosterResponse roster = getClassSessionRoster(fixture.host(), fixture.teacherToken(), session.id())
			.getBody()
			.data();
		assertThat(roster.roster()).singleElement().satisfies(entry -> {
			assertThat(entry.studentId()).isEqualTo(fixture.student().getId());
			assertThat(entry.status()).isEqualTo(AttendanceStatus.LATE);
		});
		String markedBy = jdbcTemplate.queryForObject("SELECT ar.marked_by::text FROM attendance_record ar "
				+ "JOIN attendance_sheet s ON s.id = ar.sheet_id WHERE s.class_session_id = ?", String.class,
				session.id());
		assertThat(markedBy).isEqualTo(fixture.admin().getId().toString());
	}

	/** The PAR-10-01 limitation this wave fixes: two occurrences of the same course keep independent marks. */
	@Test
	void twoSessionsOfTheSameCourseKeepIndependentMarksForTheSameStudent() {
		AttendanceFixture fixture = seedAttendanceFixture("att-cs-recurring");
		ClassSessionResponse week1 = scheduleStartedSession(fixture, "Week 1");
		ClassSessionResponse week2 = scheduleStartedSession(fixture, "Week 2");

		markClassSessionOne(fixture.host(), fixture.teacherToken(), week1.id(), fixture.student().getId(),
				AttendanceStatus.PRESENT);
		markClassSessionOne(fixture.host(), fixture.teacherToken(), week2.id(), fixture.student().getId(),
				AttendanceStatus.ABSENT);

		assertThat(statusFor(week1.id(), fixture.student().getId())).isEqualTo("PRESENT");
		assertThat(statusFor(week2.id(), fixture.student().getId())).isEqualTo("ABSENT");
	}

	@Test
	void duplicateStudentIdsInOneBatchLeaveExactlyOneRowWithTheLastSubmittedStatus() {
		AttendanceFixture fixture = seedAttendanceFixture("att-cs-dup-batch");
		ClassSessionResponse session = scheduleStartedSession(fixture, "Week 1");
		UUID studentId = fixture.student().getId();

		HttpResult<List<AttendanceMarkResultResponse>> result = markClassSession(fixture.host(),
				fixture.teacherToken(), session.id(), List.of(new AttendanceMarkEntryRequest(studentId,
						AttendanceStatus.PRESENT), new AttendanceMarkEntryRequest(studentId, AttendanceStatus.ABSENT)));

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(countRecordsForSession(session.id())).isEqualTo(1L);
		assertThat(statusFor(session.id(), studentId)).isEqualTo("ABSENT");
		// The second row's response must reflect the second write, not a stale
		// persistence-context copy of the first (clearAutomatically on the upsert).
		assertThat(result.getBody().data().get(1).record().status()).isEqualTo(AttendanceStatus.ABSENT);
	}

	@Test
	void aBatchWhereEveryRowIsRejectedCreatesNoSheet() {
		AttendanceFixture fixture = seedAttendanceFixture("att-cs-no-empty-sheet");
		ClassSessionResponse session = scheduleStartedSession(fixture, "Week 1");

		HttpResult<List<AttendanceMarkResultResponse>> result = markClassSessionOne(fixture.host(),
				fixture.teacherToken(), session.id(), UUID.randomUUID(), AttendanceStatus.PRESENT);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(result.getBody().data().get(0).success()).isFalse();
		assertThat(countSheetsForSession(session.id())).isZero();
	}

	// ------------------------------------------------------------------
	// Session lifecycle gate.
	// ------------------------------------------------------------------

	@Test
	void aFutureScheduledSessionRejectsMarkingWith409AndTheRosterReportsMarkingClosed() {
		AttendanceFixture fixture = seedAttendanceFixture("att-cs-future");
		ClassSessionResponse session = scheduleFutureSession(fixture, "Tomorrow");

		HttpResult<List<AttendanceMarkResultResponse>> result = markClassSessionOne(fixture.host(),
				fixture.teacherToken(), session.id(), fixture.student().getId(), AttendanceStatus.PRESENT);
		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(countSheetsForSession(session.id())).isZero();

		ClassSessionRosterResponse roster = getClassSessionRoster(fixture.host(), fixture.teacherToken(), session.id())
			.getBody()
			.data();
		assertThat(roster.markingOpen()).isFalse();
		assertThat(roster.markingClosedReason()).isNotBlank();
		assertThat(roster.sessionStatus()).isEqualTo("SCHEDULED");
		assertThat(roster.sheetId()).isNull();
	}

	@Test
	void aCancelledSessionRejectsMarkingWith409EvenAfterItsStartTime() {
		AttendanceFixture fixture = seedAttendanceFixture("att-cs-cancelled");
		ClassSessionResponse session = scheduleStartedSession(fixture, "Cancelled class");
		transitionSessionOrFail(fixture, session.id(), "cancel");

		HttpResult<List<AttendanceMarkResultResponse>> result = markClassSessionOne(fixture.host(),
				fixture.teacherToken(), session.id(), fixture.student().getId(), AttendanceStatus.PRESENT);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(countSheetsForSession(session.id())).isZero();
	}

	@Test
	void liveAndCompletedSessionsAreMarkableEvenWhenScheduledForTheFuture() {
		AttendanceFixture fixture = seedAttendanceFixture("att-cs-live-completed");
		ClassSessionResponse live = scheduleFutureSession(fixture, "Started early");
		transitionSessionOrFail(fixture, live.id(), "start");
		ClassSessionResponse completed = scheduleFutureSession(fixture, "Finished early");
		transitionSessionOrFail(fixture, completed.id(), "start");
		transitionSessionOrFail(fixture, completed.id(), "complete");

		assertThat(markClassSessionOne(fixture.host(), fixture.teacherToken(), live.id(), fixture.student().getId(),
				AttendanceStatus.PRESENT)
			.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(markClassSessionOne(fixture.host(), fixture.teacherToken(), completed.id(),
				fixture.student().getId(), AttendanceStatus.LATE)
			.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	// ------------------------------------------------------------------
	// Roster.
	// ------------------------------------------------------------------

	@Test
	void rosterKeepsAPreviouslyMarkedStudentWhoIsNoLongerEnrolledAsReadOnlyAndRejectsNewMarksForThem() {
		AttendanceFixture fixture = seedAttendanceFixture("att-cs-roster-history");
		TenantUser leaver = seedActiveStudent(fixture.tenant().getId(), "leaver@example.test");
		String leaverToken = loginAndGetToken(fixture.host(), "leaver@example.test");
		enrollStudentOrFail(fixture.host(), leaverToken, fixture.course().id());
		ClassSessionResponse session = scheduleStartedSession(fixture, "Week 1");
		markClassSessionOne(fixture.host(), fixture.teacherToken(), session.id(), leaver.getId(),
				AttendanceStatus.PRESENT);
		jdbcTemplate.update("UPDATE enrollment SET access_expires_at = now() - interval '1 day' "
				+ "WHERE tenant_id = ? AND student_id = ? AND superseded_at IS NULL", fixture.tenant().getId(),
				leaver.getId());

		ClassSessionRosterResponse roster = getClassSessionRoster(fixture.host(), fixture.teacherToken(), session.id())
			.getBody()
			.data();
		assertThat(roster.roster()).hasSize(2);
		assertThat(roster.roster()).filteredOn(entry -> entry.studentId().equals(leaver.getId()))
			.singleElement()
			.satisfies(entry -> {
				assertThat(entry.currentlyEnrolled()).isFalse();
				assertThat(entry.status()).isEqualTo(AttendanceStatus.PRESENT);
			});
		assertThat(roster.roster()).filteredOn(entry -> entry.studentId().equals(fixture.student().getId()))
			.singleElement()
			.satisfies(entry -> assertThat(entry.currentlyEnrolled()).isTrue());

		HttpResult<List<AttendanceMarkResultResponse>> remark = markClassSessionOne(fixture.host(),
				fixture.teacherToken(), session.id(), leaver.getId(), AttendanceStatus.ABSENT);
		assertThat(remark.getBody().data().get(0).success()).isFalse();
		assertThat(statusFor(session.id(), leaver.getId())).as("historical mark preserved").isEqualTo("PRESENT");
	}

	// ------------------------------------------------------------------
	// Unauthorized roster / roles.
	// ------------------------------------------------------------------

	@Test
	void aStudentIsDeniedTheRosterAndMarkingWith403ForBothARealAndANonexistentSessionId() {
		AttendanceFixture fixture = seedAttendanceFixture("att-cs-student-denied");
		ClassSessionResponse session = scheduleStartedSession(fixture, "Week 1");

		assertThat(getClassSessionRoster(fixture.host(), fixture.studentToken(), session.id()).getStatusCode())
			.isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(getClassSessionRoster(fixture.host(), fixture.studentToken(), UUID.randomUUID()).getStatusCode())
			.as("same status for a nonexistent id - no existence oracle")
			.isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(markClassSessionOne(fixture.host(), fixture.studentToken(), session.id(),
				fixture.student().getId(), AttendanceStatus.PRESENT)
			.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(countSheetsForSession(session.id())).isZero();
	}

	@Test
	void staffWithoutAnAttendanceGrantAndTeacherAssistantsAreDeniedWhileGrantedStaffAreAllowed() {
		AttendanceFixture fixture = seedAttendanceFixture("att-cs-roles");
		ClassSessionResponse session = scheduleStartedSession(fixture, "Week 1");
		UUID tenantId = fixture.tenant().getId();
		seedTenantUser(tenantId, "finance@example.test", RAW_PASSWORD, Role.FINANCE_STAFF);
		seedTenantUser(tenantId, "ta@example.test", RAW_PASSWORD, Role.TEACHER_ASSISTANT);
		seedTenantUser(tenantId, "operator@example.test", RAW_PASSWORD, Role.ATTENDANCE_OPERATOR);
		seedTenantUser(tenantId, "auditor@example.test", RAW_PASSWORD, Role.READ_ONLY_AUDITOR);
		String finance = loginAndGetToken(fixture.host(), "finance@example.test");
		String ta = loginAndGetToken(fixture.host(), "ta@example.test");
		String operator = loginAndGetToken(fixture.host(), "operator@example.test");
		String auditor = loginAndGetToken(fixture.host(), "auditor@example.test");

		assertThat(getClassSessionRoster(fixture.host(), finance, session.id()).getStatusCode())
			.isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(getClassSessionRoster(fixture.host(), ta, session.id()).getStatusCode())
			.isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(getClassSessionRoster(fixture.host(), auditor, session.id()).getStatusCode())
			.isEqualTo(HttpStatus.OK);
		assertThat(markClassSessionOne(fixture.host(), auditor, session.id(), fixture.student().getId(),
				AttendanceStatus.PRESENT)
			.getStatusCode()).as("auditor is VIEW-only").isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(markClassSessionOne(fixture.host(), operator, session.id(), fixture.student().getId(),
				AttendanceStatus.PRESENT)
			.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	void anUnauthenticatedCallerReceives401() {
		AttendanceFixture fixture = seedAttendanceFixture("att-cs-unauth");
		ClassSessionResponse session = scheduleStartedSession(fixture, "Week 1");

		assertThat(getClassSessionRoster(fixture.host(), null, session.id()).getStatusCode())
			.isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	// ------------------------------------------------------------------
	// Wrong teacher / course reassignment / wrong course.
	// ------------------------------------------------------------------

	@Test
	void aTeacherOfAnotherCourseInTheSameTenantIsDeniedRosterAndMarkingWithNoSideEffects() {
		AttendanceFixture fixture = seedAttendanceFixture("att-cs-wrong-teacher");
		seedTenantUser(fixture.tenant().getId(), "other-teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String otherTeacher = loginAndGetToken(fixture.host(), "other-teacher@example.test");
		ClassSessionResponse session = scheduleStartedSession(fixture, "Week 1");

		assertThat(getClassSessionRoster(fixture.host(), otherTeacher, session.id()).getStatusCode())
			.isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(markClassSessionOne(fixture.host(), otherTeacher, session.id(), fixture.student().getId(),
				AttendanceStatus.PRESENT)
			.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(countSheetsForSession(session.id())).isZero();
	}

	@Test
	void reassigningTheCourseMovesAttendanceAccessToTheNewTeacher() {
		AttendanceFixture fixture = seedAttendanceFixture("att-cs-reassign");
		TenantUser newTeacher = seedTenantUser(fixture.tenant().getId(), "new-teacher@example.test", RAW_PASSWORD,
				Role.TEACHER);
		String newTeacherToken = loginAndGetToken(fixture.host(), "new-teacher@example.test");
		ClassSessionResponse session = scheduleStartedSession(fixture, "Week 1");

		assertThat(reassignTeacher(fixture.host(), fixture.adminToken(), fixture.course().id(), newTeacher.getId())
			.getStatusCode()).isEqualTo(HttpStatus.OK);

		assertThat(getClassSessionRoster(fixture.host(), fixture.teacherToken(), session.id()).getStatusCode())
			.as("former teacher").isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(markClassSessionOne(fixture.host(), newTeacherToken, session.id(), fixture.student().getId(),
				AttendanceStatus.PRESENT)
			.getStatusCode()).as("current teacher").isEqualTo(HttpStatus.OK);
	}

	/** Course consistency is DB-enforced (V56 composite FKs), not only service discipline. */
	@Test
	void theDatabaseRejectsARecordOrSheetWhoseCourseDisagreesWithItsParent() {
		AttendanceFixture fixture = seedAttendanceFixture("att-cs-wrong-course");
		ClassSessionResponse session = scheduleStartedSession(fixture, "Week 1");
		markClassSessionOne(fixture.host(), fixture.teacherToken(), session.id(), fixture.student().getId(),
				AttendanceStatus.PRESENT);
		UUID sheetId = jdbcTemplate.queryForObject("SELECT id FROM attendance_sheet WHERE class_session_id = ?",
				UUID.class, session.id());
		UUID otherCourseId = createCourseOrFail(fixture.host(), fixture.adminToken(),
				newCourseRequest(uniqueSlug("other"), fixture.teacher().getId()))
			.id();
		UUID tenantId = fixture.tenant().getId();

		assertThatThrownBy(() -> jdbcTemplate.update(
				"UPDATE attendance_record SET course_id = ? WHERE sheet_id = ?", otherCourseId, sheetId))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbcTemplate.update("INSERT INTO attendance_sheet (id, tenant_id, course_id, source, "
				+ "class_session_id, created_at, updated_at) VALUES (?, ?, ?, 'CLASS_SESSION', ?, now(), now())",
				UUID.randomUUID(), tenantId, otherCourseId, UUID.randomUUID()))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbcTemplate.update("INSERT INTO attendance_sheet (id, tenant_id, course_id, source, "
				+ "class_session_id, created_at, updated_at) VALUES (?, ?, ?, 'CLASS_SESSION', ?, now(), now())",
				UUID.randomUUID(), tenantId, fixture.course().id(), session.id()))
			.as("second sheet for the same session")
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	// ------------------------------------------------------------------
	// Reports + student privacy.
	// ------------------------------------------------------------------

	@Test
	void reportsFilteredByClassSessionReturnOnlyThatSessionsRecordsWithItsTitle() {
		AttendanceFixture fixture = seedAttendanceFixture("att-cs-report-filter");
		ClassSessionResponse week1 = scheduleStartedSession(fixture, "Week 1");
		ClassSessionResponse week2 = scheduleStartedSession(fixture, "Week 2");
		markClassSessionOne(fixture.host(), fixture.teacherToken(), week1.id(), fixture.student().getId(),
				AttendanceStatus.PRESENT);
		markClassSessionOne(fixture.host(), fixture.teacherToken(), week2.id(), fixture.student().getId(),
				AttendanceStatus.ABSENT);

		for (String token : List.of(fixture.teacherToken(), fixture.adminToken())) {
			PageResponse<AttendanceRecordResponse> page = getAttendanceReport(fixture.host(), token,
					"classSessionId=" + week2.id())
				.getBody()
				.data();
			assertThat(page.content()).singleElement().satisfies(record -> {
				assertThat(record.classSessionId()).isEqualTo(week2.id());
				assertThat(record.classSessionTitle()).isEqualTo("Week 2");
				assertThat(record.status()).isEqualTo(AttendanceStatus.ABSENT);
			});
		}
		assertThat(getAttendanceReport(fixture.host(), fixture.adminToken(), "classSessionId=" + UUID.randomUUID())
			.getBody()
			.data()
			.content()).isEmpty();
	}

	@Test
	void aStudentOnlyEverSeesTheirOwnRecordsEvenWhenFilteringByASharedSession() {
		AttendanceFixture fixture = seedAttendanceFixture("att-cs-privacy");
		TenantUser classmate = seedActiveStudent(fixture.tenant().getId(), "classmate@example.test");
		String classmateToken = loginAndGetToken(fixture.host(), "classmate@example.test");
		enrollStudentOrFail(fixture.host(), classmateToken, fixture.course().id());
		ClassSessionResponse session = scheduleStartedSession(fixture, "Week 1");
		markClassSession(fixture.host(), fixture.teacherToken(), session.id(),
				List.of(new AttendanceMarkEntryRequest(fixture.student().getId(), AttendanceStatus.PRESENT),
						new AttendanceMarkEntryRequest(classmate.getId(), AttendanceStatus.ABSENT)));

		PageResponse<AttendanceRecordResponse> mine = getMyAttendanceForSession(fixture.host(),
				fixture.studentToken(), session.id())
			.getBody()
			.data();
		assertThat(mine.content()).singleElement()
			.satisfies(record -> assertThat(record.studentId()).isEqualTo(fixture.student().getId()));

		PageResponse<AttendanceRecordResponse> theirs = getMyAttendance(fixture.host(), classmateToken, null).getBody()
			.data();
		assertThat(theirs.content()).extracting(AttendanceRecordResponse::studentId).containsOnly(classmate.getId());

		assertThat(getAttendanceReport(fixture.host(), fixture.studentToken(), null).getStatusCode())
			.as("student cannot use the staff report").isEqualTo(HttpStatus.FORBIDDEN);
	}

	// ------------------------------------------------------------------
	// Legacy compatibility.
	// ------------------------------------------------------------------

	@Test
	void theDeprecatedLessonEndpointStillWorksAndAttachesRowsToALegacyLessonSheet() {
		AttendanceFixture fixture = seedAttendanceFixture("att-cs-legacy");

		HttpResult<List<AttendanceMarkResultResponse>> result = markOneStudent(fixture.host(), fixture.teacherToken(),
				fixture.lessonId(), fixture.student().getId(), AttendanceStatus.PRESENT);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		AttendanceRecordResponse record = result.getBody().data().get(0).record();
		assertThat(record.source()).isEqualTo(AttendanceSheetSource.LEGACY_LESSON);
		assertThat(record.sessionId()).isEqualTo(fixture.lessonId());
		assertThat(record.classSessionId()).isNull();
		Long legacySheets = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM attendance_sheet WHERE source = 'LEGACY_LESSON' AND lesson_id = ?", Long.class,
				fixture.lessonId());
		assertThat(legacySheets).isEqualTo(1L);
	}

	private String statusFor(UUID classSessionId, UUID studentId) {
		return jdbcTemplate.queryForObject("SELECT ar.status FROM attendance_record ar "
				+ "JOIN attendance_sheet s ON s.id = ar.sheet_id WHERE s.class_session_id = ? AND ar.student_id = ?",
				String.class, classSessionId, studentId);
	}

}
