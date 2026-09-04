package com.lms.attendancemanagement;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.attendancemanagement.domain.AttendanceStatus;
import com.lms.attendancemanagement.web.dto.AttendanceRecordResponse;
import com.lms.attendancemanagement.web.dto.AttendanceRosterEntryResponse;
import com.lms.attendancemanagement.web.dto.AttendanceRosterResponse;
import com.lms.common.api.PageResponse;
import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.web.dto.CourseLessonResponse;
import com.lms.coursemanagement.course.web.dto.CourseModuleResponse;
import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Testcontainers/MockMvc coverage for {@code GET /api/v1/attendance/my} and
 * {@code GET /api/v1/attendance/reports} (plan §18's same-tenant report
 * scoping list). Cross-tenant negative cases live in the dedicated {@link
 * AttendanceCrossTenantIntegrationTest}.
 */
class AttendanceReportIntegrationTest extends AttendanceManagementTestSupport {

	@Test
	void studentsOwnHistoryNeverContainsAnotherStudentsRowsInTheSameTenant() {
		AttendanceFixture fixture = seedAttendanceFixture("att-rep-my");
		TenantUser otherStudent = seedActiveStudent(fixture.tenant().getId(), "other-student@example.test");
		String otherStudentToken = loginAndGetToken(fixture.host(), "other-student@example.test");
		enrollStudentOrFail(fixture.host(), otherStudentToken, fixture.course().id());
		markOneStudent(fixture.host(), fixture.teacherToken(), fixture.lessonId(), fixture.student().getId(),
				AttendanceStatus.PRESENT);
		markOneStudent(fixture.host(), fixture.teacherToken(), fixture.lessonId(), otherStudent.getId(),
				AttendanceStatus.ABSENT);

		HttpResult<PageResponse<AttendanceRecordResponse>> result = getMyAttendance(fixture.host(),
				fixture.studentToken(), null);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		PageResponse<AttendanceRecordResponse> page = result.getBody().data();
		assertThat(page.content()).extracting(AttendanceRecordResponse::studentId)
			.containsExactly(fixture.student().getId())
			.doesNotContain(otherStudent.getId());

		// Sanity: the other student's own history DOES see their own row.
		HttpResult<PageResponse<AttendanceRecordResponse>> otherResult = getMyAttendance(fixture.host(),
				otherStudentToken, null);
		assertThat(otherResult.getBody().data().content()).extracting(AttendanceRecordResponse::studentId)
			.containsExactly(otherStudent.getId());
	}

	/** Fixture record for a second Teacher + their own course/lesson/student within the SAME tenant. */
	private record SecondTeacherFixture(TenantUser teacher, String teacherToken, CourseResponse course,
			UUID lessonId, TenantUser student) {
	}

	private SecondTeacherFixture seedSecondTeacher(AttendanceFixture fixture, String prefix) {
		TenantUser teacherB = seedTenantUser(fixture.tenant().getId(), "teacherB@example.test", RAW_PASSWORD,
				Role.TEACHER);
		String teacherBToken = loginAndGetToken(fixture.host(), "teacherB@example.test");
		CourseResponse courseB = createCourseOrFail(fixture.host(), fixture.adminToken(),
				newCourseRequest(uniqueSlug(prefix), teacherB.getId(), CourseStatus.PUBLIC));
		CourseModuleResponse moduleB = createModuleOrFail(fixture.host(), fixture.adminToken(), courseB.id(),
				"Module B", 1);
		CourseLessonResponse lessonB = createLessonOrFail(fixture.host(), fixture.adminToken(), courseB.id(),
				moduleB.id(), "Lesson B", 1);
		TenantUser studentB = seedActiveStudent(fixture.tenant().getId(), "studentB@example.test");
		String studentBToken = loginAndGetToken(fixture.host(), "studentB@example.test");
		enrollStudentOrFail(fixture.host(), studentBToken, courseB.id());
		return new SecondTeacherFixture(teacherB, teacherBToken, courseB, lessonB.id(), studentB);
	}

	@Test
	void multiTeacherFixtureTeacherAsReportNeverContainsTeacherBsCourseRecordsInTheSameTenant() {
		AttendanceFixture fixture = seedAttendanceFixture("att-rep-multi");
		SecondTeacherFixture teacherB = seedSecondTeacher(fixture, "att-rep-multi-b");
		markOneStudent(fixture.host(), fixture.teacherToken(), fixture.lessonId(), fixture.student().getId(),
				AttendanceStatus.PRESENT);
		markOneStudent(fixture.host(), teacherB.teacherToken(), teacherB.lessonId(), teacherB.student().getId(),
				AttendanceStatus.ABSENT);

		HttpResult<PageResponse<AttendanceRecordResponse>> reportA = getAttendanceReport(fixture.host(),
				fixture.teacherToken(), null);
		assertThat(reportA.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(reportA.getBody().data().content()).extracting(AttendanceRecordResponse::courseId)
			.containsExactly(fixture.course().id())
			.doesNotContain(teacherB.course().id());

		HttpResult<PageResponse<AttendanceRecordResponse>> reportB = getAttendanceReport(fixture.host(),
				teacherB.teacherToken(), null);
		assertThat(reportB.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(reportB.getBody().data().content()).extracting(AttendanceRecordResponse::courseId)
			.containsExactly(teacherB.course().id())
			.doesNotContain(fixture.course().id());
	}

	@Test
	void tenantWideStaffReportCorrectlyIncludesBothTeachersCourses() {
		AttendanceFixture fixture = seedAttendanceFixture("att-rep-staff");
		SecondTeacherFixture teacherB = seedSecondTeacher(fixture, "att-rep-staff-b");
		markOneStudent(fixture.host(), fixture.teacherToken(), fixture.lessonId(), fixture.student().getId(),
				AttendanceStatus.PRESENT);
		markOneStudent(fixture.host(), teacherB.teacherToken(), teacherB.lessonId(), teacherB.student().getId(),
				AttendanceStatus.ABSENT);

		HttpResult<PageResponse<AttendanceRecordResponse>> result = getAttendanceReport(fixture.host(),
				fixture.adminToken(), null);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(result.getBody().data().content()).extracting(AttendanceRecordResponse::courseId)
			.containsExactlyInAnyOrder(fixture.course().id(), teacherB.course().id());
	}

	/**
	 * Closes the review gap: the roster endpoint's actual per-student CONTENT
	 * was previously only ever proven to return {@code 200}/{@code 404}
	 * (authorization/cross-tenant tests), never asserted for correct
	 * per-student status content. Covers all three roster row shapes in one
	 * pass: a marked-PRESENT student, a marked-ABSENT student, a
	 * currently-enrolled-but-never-marked student (status must be {@code
	 * null}, not omitted or defaulted), and a student whose ONLY enrollment
	 * has expired (must be excluded from the roster entirely - reuses {@link
	 * AttendanceManagementTestSupport#enrollStudentThenExpireAccess}, the same
	 * fixture technique {@code AttendanceMarkingIntegrationTest}'s own
	 * expired-enrollment case uses).
	 */
	@Test
	void rosterReflectsPerStudentStatusIncludingUnmarkedAndExcludesAStudentWithOnlyAnExpiredEnrollment() {
		AttendanceFixture fixture = seedAttendanceFixture("att-rep-roster-content");
		TenantUser presentStudent = fixture.student();

		TenantUser absentStudent = seedActiveStudent(fixture.tenant().getId(), "roster-absent@example.test");
		String absentToken = loginAndGetToken(fixture.host(), "roster-absent@example.test");
		enrollStudentOrFail(fixture.host(), absentToken, fixture.course().id());

		TenantUser unmarkedStudent = seedActiveStudent(fixture.tenant().getId(), "roster-unmarked@example.test");
		String unmarkedToken = loginAndGetToken(fixture.host(), "roster-unmarked@example.test");
		enrollStudentOrFail(fixture.host(), unmarkedToken, fixture.course().id());

		TenantUser expiredStudent = seedActiveStudent(fixture.tenant().getId(), "roster-expired@example.test");
		String expiredToken = loginAndGetToken(fixture.host(), "roster-expired@example.test");
		enrollStudentThenExpireAccess(fixture.host(), expiredToken, fixture.tenant().getId(), expiredStudent.getId(),
				fixture.course().id());

		markOneStudent(fixture.host(), fixture.teacherToken(), fixture.lessonId(), presentStudent.getId(),
				AttendanceStatus.PRESENT);
		markOneStudent(fixture.host(), fixture.teacherToken(), fixture.lessonId(), absentStudent.getId(),
				AttendanceStatus.ABSENT);

		HttpResult<AttendanceRosterResponse> result = getRoster(fixture.host(), fixture.teacherToken(),
				fixture.lessonId());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		AttendanceRosterResponse roster = result.getBody().data();
		assertThat(roster.courseId()).isEqualTo(fixture.course().id());
		assertThat(roster.sessionId()).isEqualTo(fixture.lessonId());
		assertThat(roster.roster()).extracting(AttendanceRosterEntryResponse::studentId)
			.containsExactlyInAnyOrder(presentStudent.getId(), absentStudent.getId(), unmarkedStudent.getId())
			.doesNotContain(expiredStudent.getId());

		// Collectors.toMap rejects null values (the unmarked student's status
		// IS null here), so build the lookup with an explicit HashMap
		// accumulator instead of a toMap collector.
		Map<UUID, AttendanceStatus> statusByStudent = new HashMap<>();
		roster.roster().forEach(entry -> statusByStudent.put(entry.studentId(), entry.status()));
		assertThat(statusByStudent.get(presentStudent.getId())).isEqualTo(AttendanceStatus.PRESENT);
		assertThat(statusByStudent.get(absentStudent.getId())).isEqualTo(AttendanceStatus.ABSENT);
		assertThat(statusByStudent.get(unmarkedStudent.getId())).isNull();
	}

	/**
	 * End-to-end exercise of {@code AttendanceSpecifications#markedBetween}
	 * through the real {@code /reports} endpoint (plan §18's "Specification
	 * predicate CONTENT is proven at the Testcontainers integration level"
	 * claim - previously aspirational, now backed by this test). Backdates one
	 * record's {@code marked_at} directly via SQL (mirroring {@code
	 * enrollStudentThenExpireAccess}'s own "backdate via jdbcTemplate, no real
	 * wait" technique) so the {@code from}/{@code to} window genuinely narrows
	 * the result set rather than happening to match by coincidence.
	 */
	@Test
	void reportsDateRangeFilterNarrowsResultsToRecordsMarkedWithinTheRequestedWindow() {
		AttendanceFixture fixture = seedAttendanceFixture("att-rep-daterange");
		TenantUser recentStudent = fixture.student();
		TenantUser oldStudent = seedActiveStudent(fixture.tenant().getId(), "old-marked@example.test");
		String oldToken = loginAndGetToken(fixture.host(), "old-marked@example.test");
		enrollStudentOrFail(fixture.host(), oldToken, fixture.course().id());

		markOneStudent(fixture.host(), fixture.teacherToken(), fixture.lessonId(), oldStudent.getId(),
				AttendanceStatus.PRESENT);
		markOneStudent(fixture.host(), fixture.teacherToken(), fixture.lessonId(), recentStudent.getId(),
				AttendanceStatus.PRESENT);
		int updated = jdbcTemplate.update(
				"UPDATE attendance_record SET marked_at = now() - interval '10 days' WHERE session_id = ? "
						+ "AND student_id = ?",
				fixture.lessonId(), oldStudent.getId());
		assertThat(updated).isEqualTo(1);

		Instant from = Instant.now().minus(1, ChronoUnit.DAYS);
		Instant to = Instant.now().plus(1, ChronoUnit.DAYS);

		HttpResult<PageResponse<AttendanceRecordResponse>> result = getAttendanceReport(fixture.host(),
				fixture.teacherToken(), "from=" + from + "&to=" + to);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(result.getBody().data().content()).extracting(AttendanceRecordResponse::studentId)
			.containsExactly(recentStudent.getId())
			.doesNotContain(oldStudent.getId());

		// Sanity: a window that DOES cover the backdated row sees both rows -
		// proves the narrower result above is the date filter working, not the
		// old row being missing/broken for some other reason.
		Instant wideFrom = Instant.now().minus(30, ChronoUnit.DAYS);
		HttpResult<PageResponse<AttendanceRecordResponse>> wideResult = getAttendanceReport(fixture.host(),
				fixture.teacherToken(), "from=" + wideFrom + "&to=" + to);
		assertThat(wideResult.getBody().data().content()).extracting(AttendanceRecordResponse::studentId)
			.containsExactlyInAnyOrder(recentStudent.getId(), oldStudent.getId());
	}

	/**
	 * End-to-end exercise of {@code AttendanceSpecifications#withCourseId} on
	 * the {@code /my} (student history) endpoint - the same student marked in
	 * two different courses, filtered down to one.
	 */
	@Test
	void myHistoryCourseIdFilterOnlyReturnsRecordsForThatCourse() {
		AttendanceFixture fixture = seedAttendanceFixture("att-rep-my-courseid");
		CourseResponse courseB = createCourseOrFail(fixture.host(), fixture.adminToken(),
				newCourseRequest(uniqueSlug("att-rep-my-courseid-b"), fixture.teacher().getId(), CourseStatus.PUBLIC));
		CourseModuleResponse moduleB = createModuleOrFail(fixture.host(), fixture.adminToken(), courseB.id(),
				"Module B", 1);
		CourseLessonResponse lessonB = createLessonOrFail(fixture.host(), fixture.adminToken(), courseB.id(),
				moduleB.id(), "Lesson B", 1);
		enrollStudentOrFail(fixture.host(), fixture.studentToken(), courseB.id());

		markOneStudent(fixture.host(), fixture.teacherToken(), fixture.lessonId(), fixture.student().getId(),
				AttendanceStatus.PRESENT);
		markOneStudent(fixture.host(), fixture.teacherToken(), lessonB.id(), fixture.student().getId(),
				AttendanceStatus.ABSENT);

		HttpResult<PageResponse<AttendanceRecordResponse>> result = getMyAttendance(fixture.host(),
				fixture.studentToken(), "courseId=" + fixture.course().id());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(result.getBody().data().content()).extracting(AttendanceRecordResponse::courseId)
			.containsExactly(fixture.course().id())
			.doesNotContain(courseB.id());

		// Sanity: unfiltered history sees both courses' rows.
		HttpResult<PageResponse<AttendanceRecordResponse>> unfiltered = getMyAttendance(fixture.host(),
				fixture.studentToken(), null);
		assertThat(unfiltered.getBody().data().content()).extracting(AttendanceRecordResponse::courseId)
			.containsExactlyInAnyOrder(fixture.course().id(), courseB.id());
	}

	/**
	 * Real HTTP-level equivalent of {@code
	 * AttendanceReportServiceTest#myHistoryRejectsAFromDateAfterTheToDate} -
	 * proves {@link com.lms.attendancemanagement.service.InvalidDateRangeException}
	 * is actually mapped to {@code 400} by {@code GlobalExceptionHandler}
	 * through the real controller, not just thrown correctly at the mocked
	 * service level.
	 */
	@Test
	void myHistoryWithFromAfterToReturns400ThroughTheRealController() {
		AttendanceFixture fixture = seedAttendanceFixture("att-rep-my-baddate");
		Instant to = Instant.now();
		Instant from = to.plusSeconds(3600);

		HttpResult<PageResponse<AttendanceRecordResponse>> result = getMyAttendance(fixture.host(),
				fixture.studentToken(), "from=" + from + "&to=" + to);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	/**
	 * Real HTTP-level equivalent of {@code
	 * AttendanceReportServiceTest#teacherReportWithAnExplicitCourseIdOwnedByAnotherTeacherIsRejectedWith403}.
	 */
	@Test
	void teacherReportWithAnExplicitCourseIdOwnedByAnotherTeacherInTheSameTenantReturns403() {
		AttendanceFixture fixture = seedAttendanceFixture("att-rep-teacher-403");
		SecondTeacherFixture teacherB = seedSecondTeacher(fixture, "att-rep-teacher-403-b");

		HttpResult<PageResponse<AttendanceRecordResponse>> result = getAttendanceReport(fixture.host(),
				fixture.teacherToken(), "courseId=" + teacherB.course().id());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	/**
	 * Post-ship review Finding 3 (MVP-016 plan §22): {@code
	 * AttendanceReportService#resolveOwnedCourseIdsWithHistory} derives a
	 * Teacher's no-filter "owned courses" set from the courses that already
	 * have at least one attendance row ({@code
	 * AttendanceRecordRepository#findDistinctCourseIdsByTenantId}),
	 * intersected with {@code CourseLookupApi#getTeacherIdsByCourseId} - NOT
	 * the teacher's full owned-course set. This is a genuine end-to-end,
	 * real-content pin of that derivation (unlike {@code
	 * AttendanceReportServiceTest}'s Mockito-level equivalents, which stub
	 * both collaborators' return values directly rather than proving the real
	 * repository/CourseLookupApi combination against genuine data): the SAME
	 * teacher owns two real courses in the same tenant - one with a marked
	 * attendance record, one with a real enrolled student but ZERO attendance
	 * history - and the no-filter report must reflect exactly, and only, the
	 * course with history: not falsely empty (which would happen if the
	 * derivation incorrectly required ALL owned courses to have history), and
	 * not incorrectly including the zero-history course's (nonexistent) rows.
	 */
	@Test
	void teacherNoFilterReportReflectsOnlyTheOwnedCourseWithAttendanceHistoryNotTheZeroHistoryCourse() {
		AttendanceFixture fixture = seedAttendanceFixture("att-rep-owned-history");
		markOneStudent(fixture.host(), fixture.teacherToken(), fixture.lessonId(), fixture.student().getId(),
				AttendanceStatus.PRESENT);

		// A second course owned by the SAME teacher, with a real currently-
		// enrolled student, but no lesson is ever marked - zero attendance
		// history for this course anywhere in the tenant.
		CourseResponse zeroHistoryCourse = createCourseOrFail(fixture.host(), fixture.adminToken(),
				newCourseRequest(uniqueSlug("att-rep-owned-history-zero"), fixture.teacher().getId(),
						CourseStatus.PUBLIC));
		CourseModuleResponse zeroHistoryModule = createModuleOrFail(fixture.host(), fixture.adminToken(),
				zeroHistoryCourse.id(), "Module Zero", 1);
		createLessonOrFail(fixture.host(), fixture.adminToken(), zeroHistoryCourse.id(), zeroHistoryModule.id(),
				"Lesson Zero", 1);
		TenantUser zeroHistoryStudent = seedActiveStudent(fixture.tenant().getId(), "zero-history@example.test");
		String zeroHistoryStudentToken = loginAndGetToken(fixture.host(), "zero-history@example.test");
		enrollStudentOrFail(fixture.host(), zeroHistoryStudentToken, zeroHistoryCourse.id());

		HttpResult<PageResponse<AttendanceRecordResponse>> result = getAttendanceReport(fixture.host(),
				fixture.teacherToken(), null);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(result.getBody().data().content()).isNotEmpty()
			.extracting(AttendanceRecordResponse::courseId)
			.containsExactly(fixture.course().id())
			.doesNotContain(zeroHistoryCourse.id());
		assertThat(result.getBody().data().content()).extracting(AttendanceRecordResponse::studentId)
			.containsExactly(fixture.student().getId());
	}

}
