package com.lms.attendancemanagement;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.attendancemanagement.domain.AttendanceStatus;
import com.lms.attendancemanagement.web.dto.AttendanceSummaryRowResponse;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.liveclassmanagement.web.dto.ClassSessionResponse;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Wave 8 attendance percentages: SQL-aggregated counts and {@code
 * attendanceRate = (present + late) / total}, role-scoped exactly like the
 * rest of the module (Teacher own course, staff {@code ATTENDANCE}/{@code
 * VIEW}, Student only their own).
 */
class AttendanceSummaryIntegrationTest extends AttendanceClassSessionTestSupport {

	@Test
	void courseSummaryCountsEachStatusAndComputesTheRateForTeacherAndStaff() {
		AttendanceFixture fixture = seedAttendanceFixture("att-sum-course");
		ClassSessionResponse s1 = scheduleStartedSession(fixture, "Week 1");
		ClassSessionResponse s2 = scheduleStartedSession(fixture, "Week 2");
		ClassSessionResponse s3 = scheduleStartedSession(fixture, "Week 3");
		ClassSessionResponse s4 = scheduleStartedSession(fixture, "Week 4");
		markClassSessionOne(fixture.host(), fixture.teacherToken(), s1.id(), fixture.student().getId(),
				AttendanceStatus.PRESENT);
		markClassSessionOne(fixture.host(), fixture.teacherToken(), s2.id(), fixture.student().getId(),
				AttendanceStatus.LATE);
		markClassSessionOne(fixture.host(), fixture.teacherToken(), s3.id(), fixture.student().getId(),
				AttendanceStatus.ABSENT);
		markClassSessionOne(fixture.host(), fixture.teacherToken(), s4.id(), fixture.student().getId(),
				AttendanceStatus.ABSENT);

		for (String token : List.of(fixture.teacherToken(), fixture.adminToken())) {
			HttpResult<List<AttendanceSummaryRowResponse>> result = getCourseSummary(fixture.host(), token,
					fixture.course().id());
			assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
			assertThat(result.getBody().data()).singleElement().satisfies(row -> {
				assertThat(row.studentId()).isEqualTo(fixture.student().getId());
				assertThat(row.present()).isEqualTo(1);
				assertThat(row.late()).isEqualTo(1);
				assertThat(row.absent()).isEqualTo(2);
				assertThat(row.total()).isEqualTo(4);
				assertThat(row.attendanceRate()).isEqualByComparingTo(new BigDecimal("50.0"));
			});
		}
	}

	@Test
	void courseSummaryIncludesLegacyLessonRecordsSoHistoryIsNotLost() {
		AttendanceFixture fixture = seedAttendanceFixture("att-sum-legacy");
		markOneStudent(fixture.host(), fixture.teacherToken(), fixture.lessonId(), fixture.student().getId(),
				AttendanceStatus.PRESENT);
		ClassSessionResponse session = scheduleStartedSession(fixture, "Week 1");
		markClassSessionOne(fixture.host(), fixture.teacherToken(), session.id(), fixture.student().getId(),
				AttendanceStatus.ABSENT);

		AttendanceSummaryRowResponse row = getCourseSummary(fixture.host(), fixture.adminToken(),
				fixture.course().id())
			.getBody()
			.data()
			.get(0);
		assertThat(row.total()).isEqualTo(2);
		assertThat(row.present()).isEqualTo(1);
	}

	@Test
	void aTeacherOfAnotherCourseAndAStudentAreDeniedTheCourseSummary() {
		AttendanceFixture fixture = seedAttendanceFixture("att-sum-denied");
		seedTenantUser(fixture.tenant().getId(), "other-teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String otherTeacher = loginAndGetToken(fixture.host(), "other-teacher@example.test");

		assertThat(getCourseSummary(fixture.host(), otherTeacher, fixture.course().id()).getStatusCode())
			.isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(getCourseSummary(fixture.host(), fixture.studentToken(), fixture.course().id()).getStatusCode())
			.isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void myStudentSummaryContainsOnlyTheCallersOwnCounts() {
		AttendanceFixture fixture = seedAttendanceFixture("att-sum-my");
		TenantUser classmate = seedActiveStudent(fixture.tenant().getId(), "classmate@example.test");
		String classmateToken = loginAndGetToken(fixture.host(), "classmate@example.test");
		enrollStudentOrFail(fixture.host(), classmateToken, fixture.course().id());
		ClassSessionResponse session = scheduleStartedSession(fixture, "Week 1");
		markClassSessionOne(fixture.host(), fixture.teacherToken(), session.id(), fixture.student().getId(),
				AttendanceStatus.PRESENT);
		markClassSessionOne(fixture.host(), fixture.teacherToken(), session.id(), classmate.getId(),
				AttendanceStatus.ABSENT);

		List<AttendanceSummaryRowResponse> mine = getMySummary(fixture.host(), fixture.studentToken()).getBody()
			.data();
		assertThat(mine).singleElement().satisfies(row -> {
			assertThat(row.courseId()).isEqualTo(fixture.course().id());
			assertThat(row.courseName()).isNotBlank();
			assertThat(row.present()).isEqualTo(1);
			assertThat(row.absent()).isZero();
			assertThat(row.attendanceRate()).isEqualByComparingTo(new BigDecimal("100.0"));
		});

		assertThat(getMySummary(fixture.host(), fixture.teacherToken()).getStatusCode())
			.isEqualTo(HttpStatus.FORBIDDEN);
	}

}
