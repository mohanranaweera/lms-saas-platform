package com.lms.attendancemanagement;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.attendancemanagement.domain.AttendanceStatus;
import com.lms.attendancemanagement.web.dto.AttendanceMarkResultResponse;
import com.lms.attendancemanagement.web.dto.AttendanceRecordResponse;
import com.lms.attendancemanagement.web.dto.AttendanceRosterResponse;
import com.lms.common.api.PageResponse;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.Role;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Closes plan §18's authorization-matrix gap flagged by the security review:
 * every staff role with NO {@code DomainArea.ATTENDANCE} grant at all (per
 * {@code PermissionCheckServiceImpl}'s doc matrix - Course Coordinator,
 * Content Manager, Exam Manager, Student Support, Finance Staff - plus
 * Teacher Assistant, deliberately not special-cased by {@code
 * AttendanceAccessGuard}, see its own javadoc) must be denied {@code 403} on
 * every attendance surface a same-tenant caller of that role could otherwise
 * reach: roster, mark, and report. Mirrors {@code
 * ReactivationAuthorizationIntegrationTest}'s established
 * one-@Test-method-per-role-delegating-to-a-shared-helper convention for
 * role-matrix coverage in this codebase (no {@code @ParameterizedTest} usage
 * for HTTP-level role matrices exists yet in this codebase to mirror instead).
 *
 * <p>{@code /my} is deliberately NOT part of the per-role matrix here - it is
 * gated purely by the controller's {@code @PreAuthorize("hasRole('STUDENT')")},
 * a generic Spring Security mechanism common to every non-Student role, not a
 * per-role {@code DomainArea} check - so {@link
 * #myAttendanceRejectsANonStudentCallerRegardlessOfRole()} proves the
 * mechanism once via one representative role rather than repeating the same
 * framework-level assertion six times.
 */
class AttendanceAuthorizationIntegrationTest extends AttendanceManagementTestSupport {

	@Test
	void courseCoordinatorGets403OnRosterMarkAndReports() {
		assertRoleDeniedOnRosterMarkAndReports("attn-authz-coordinator", Role.COURSE_COORDINATOR);
	}

	@Test
	void contentManagerGets403OnRosterMarkAndReports() {
		assertRoleDeniedOnRosterMarkAndReports("attn-authz-content", Role.CONTENT_MANAGER);
	}

	@Test
	void examManagerGets403OnRosterMarkAndReports() {
		assertRoleDeniedOnRosterMarkAndReports("attn-authz-exam", Role.EXAM_MANAGER);
	}

	@Test
	void studentSupportGets403OnRosterMarkAndReports() {
		assertRoleDeniedOnRosterMarkAndReports("attn-authz-support", Role.STUDENT_SUPPORT);
	}

	@Test
	void financeStaffGets403OnRosterMarkAndReports() {
		assertRoleDeniedOnRosterMarkAndReports("attn-authz-finance", Role.FINANCE_STAFF);
	}

	/**
	 * Teacher Assistant is not merely "unlisted" in the doc matrix - {@link
	 * com.lms.attendancemanagement.support.AttendanceAccessGuard}'s own
	 * javadoc calls out that it is deliberately NOT special-cased as a
	 * Teacher-like ownership role, so it falls through to the same
	 * permission-check branch as every other non-Teacher role and is denied.
	 */
	@Test
	void teacherAssistantGets403OnRosterMarkAndReports() {
		assertRoleDeniedOnRosterMarkAndReports("attn-authz-ta", Role.TEACHER_ASSISTANT);
	}

	@Test
	void myAttendanceRejectsANonStudentCallerRegardlessOfRole() {
		AttendanceFixture fixture = seedAttendanceFixture("attn-authz-my");
		seedTenantUser(fixture.tenant().getId(), "coordinator@example.test", RAW_PASSWORD, Role.COURSE_COORDINATOR);
		String coordinatorToken = loginAndGetToken(fixture.host(), "coordinator@example.test");

		HttpResult<PageResponse<AttendanceRecordResponse>> result = getMyAttendance(fixture.host(), coordinatorToken,
				null);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	/**
	 * Distinct from a Student's legitimate access to {@code /my} - a Student
	 * holds no {@code DomainArea.ATTENDANCE} grant, so the staff/teacher
	 * {@code /reports} surface must reject them with {@code 403}. Previously
	 * this was only proven via a mocked {@code AttendanceReportServiceTest}
	 * case; this adds the real HTTP-level equivalent.
	 */
	@Test
	void studentGets403OnReportsDistinctFromTheirLegitimateAccessToMy() {
		AttendanceFixture fixture = seedAttendanceFixture("attn-authz-student-reports");
		markOneStudent(fixture.host(), fixture.teacherToken(), fixture.lessonId(), fixture.student().getId(),
				AttendanceStatus.PRESENT);

		HttpResult<PageResponse<AttendanceRecordResponse>> reportsResult = getAttendanceReport(fixture.host(),
				fixture.studentToken(), null);
		assertThat(reportsResult.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

		HttpResult<PageResponse<AttendanceRecordResponse>> myResult = getMyAttendance(fixture.host(),
				fixture.studentToken(), null);
		assertThat(myResult.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	/**
	 * Complements {@code readOnlyAuditorCannotMarkAttendanceAndWritesZeroRows}
	 * ({@code AttendanceMarkingIntegrationTest}) - {@code READ_ONLY_AUDITOR}
	 * holds {@code DomainArea.ATTENDANCE}/{@code VIEW} only (per {@code
	 * PermissionCheckServiceImpl}'s matrix), so it must be positively ALLOWED
	 * ({@code 200}) on both read surfaces (roster, report) even though it is
	 * denied on the mark (write) surface - proving the guard's VIEW-vs-
	 * CREATE_EDIT distinction is genuinely enforced both ways, not just that
	 * the role is denied everywhere.
	 */
	@Test
	void readOnlyAuditorGets200OnRosterAndReportSurfacesThatOnlyRequireTheViewGrant() {
		AttendanceFixture fixture = seedAttendanceFixture("attn-authz-auditor-positive");
		seedTenantUser(fixture.tenant().getId(), "auditor@example.test", RAW_PASSWORD, Role.READ_ONLY_AUDITOR);
		String auditorToken = loginAndGetToken(fixture.host(), "auditor@example.test");
		markOneStudent(fixture.host(), fixture.teacherToken(), fixture.lessonId(), fixture.student().getId(),
				AttendanceStatus.PRESENT);

		HttpResult<AttendanceRosterResponse> rosterResult = getRoster(fixture.host(), auditorToken,
				fixture.lessonId());
		assertThat(rosterResult.getStatusCode()).isEqualTo(HttpStatus.OK);

		HttpResult<PageResponse<AttendanceRecordResponse>> reportResult = getAttendanceReport(fixture.host(),
				auditorToken, null);
		assertThat(reportResult.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	// ------------------------------------------------------------------

	private void assertRoleDeniedOnRosterMarkAndReports(String prefix, Role role) {
		AttendanceFixture fixture = seedAttendanceFixture(prefix);
		seedTenantUser(fixture.tenant().getId(), "denied@example.test", RAW_PASSWORD, role);
		String deniedToken = loginAndGetToken(fixture.host(), "denied@example.test");

		HttpResult<AttendanceRosterResponse> rosterResult = getRoster(fixture.host(), deniedToken,
				fixture.lessonId());
		assertThat(rosterResult.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

		HttpResult<List<AttendanceMarkResultResponse>> markResult = markOneStudent(fixture.host(), deniedToken,
				fixture.lessonId(), fixture.student().getId(), AttendanceStatus.PRESENT);
		assertThat(markResult.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

		HttpResult<PageResponse<AttendanceRecordResponse>> reportsResult = getAttendanceReport(fixture.host(),
				deniedToken, null);
		assertThat(reportsResult.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

		Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM attendance_record WHERE session_id = ?",
				Long.class, fixture.lessonId());
		assertThat(count).isEqualTo(0L);
	}

}
