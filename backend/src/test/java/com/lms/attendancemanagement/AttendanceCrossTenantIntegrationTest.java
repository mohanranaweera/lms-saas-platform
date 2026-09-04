package com.lms.attendancemanagement;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.attendancemanagement.domain.AttendanceStatus;
import com.lms.attendancemanagement.web.dto.AttendanceMarkResultResponse;
import com.lms.attendancemanagement.web.dto.AttendanceRecordResponse;
import com.lms.attendancemanagement.web.dto.AttendanceRosterResponse;
import com.lms.common.api.PageResponse;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Closes plan §14/§18's "mandatory cross-tenant negative tests" list for
 * every MVP-016 surface, mirroring {@code EnrollmentCrossTenantIntegrationTest}'s
 * exact structure and assertion style: every cross-tenant read/write proves
 * 404 (never 200/403 leaking existence), and every rejected cross-tenant
 * mutation attempt proves zero side effects (no row created, no row
 * mutated).
 */
class AttendanceCrossTenantIntegrationTest extends AttendanceManagementTestSupport {

	/**
	 * Both the mark endpoint AND the roster endpoint reject Tenant A's real
	 * {@code sessionId} when presented by a Tenant B caller - even though
	 * that {@code sessionId} genuinely resolves to a real lesson in Tenant A,
	 * AND that lesson's own {@code courseId} also genuinely resolves to a
	 * real course in Tenant A (plan §18's "a courseId+sessionId pair
	 * spanning two different tenants is rejected, not silently accepted
	 * because each id individually resolves"). {@code
	 * CourseLookupApi#resolveLessonOwnership} is tenant-scoped through the
	 * caller's OWN resolved {@link com.lms.common.tenant.TenantContext}, so
	 * neither id's validity in tenant A leaks through when queried from
	 * tenant B's context - the pair is rejected as a whole, structurally
	 * invisible (404), not partially trusted because each half "individually
	 * resolves" somewhere on the platform.
	 */
	@Test
	void markAndRosterAgainstAnotherTenantsSessionIdBothReturn404AndWriteZeroRows() {
		AttendanceFixture tenantA = seedAttendanceFixture("att-xt-session-a");
		AttendanceFixture tenantB = seedAttendanceFixture("att-xt-session-b");

		HttpResult<List<AttendanceMarkResultResponse>> markResult = markOneStudent(tenantB.host(),
				tenantB.teacherToken(), tenantA.lessonId(), tenantA.student().getId(), AttendanceStatus.PRESENT);
		assertThat(markResult.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

		HttpResult<AttendanceRosterResponse> rosterResult = getRoster(tenantB.host(), tenantB.teacherToken(),
				tenantA.lessonId());
		assertThat(rosterResult.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

		Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM attendance_record WHERE session_id = ?",
				Long.class, tenantA.lessonId());
		assertThat(count).isEqualTo(0L);

		// Sanity: tenant A's own teacher CAN mark/read their own session -
		// proves the 404s above are tenant isolation, not a broken endpoint.
		HttpResult<List<AttendanceMarkResultResponse>> ownMark = markOneStudent(tenantA.host(),
				tenantA.teacherToken(), tenantA.lessonId(), tenantA.student().getId(), AttendanceStatus.PRESENT);
		assertThat(ownMark.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	void attendanceOperatorOfTenantAReadingOrMarkingTenantBsAttendanceReturns404() {
		AttendanceFixture tenantA = seedAttendanceFixture("att-xt-operator-a");
		AttendanceFixture tenantB = seedAttendanceFixture("att-xt-operator-b");
		seedTenantUser(tenantA.tenant().getId(), "operator@example.test", RAW_PASSWORD, Role.ATTENDANCE_OPERATOR);
		String operatorTokenA = loginAndGetToken(tenantA.host(), "operator@example.test");

		HttpResult<List<AttendanceMarkResultResponse>> markResult = markOneStudent(tenantA.host(), operatorTokenA,
				tenantB.lessonId(), tenantB.student().getId(), AttendanceStatus.PRESENT);
		assertThat(markResult.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

		HttpResult<AttendanceRosterResponse> rosterResult = getRoster(tenantA.host(), operatorTokenA,
				tenantB.lessonId());
		assertThat(rosterResult.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

		Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM attendance_record WHERE session_id = ?",
				Long.class, tenantB.lessonId());
		assertThat(count).isEqualTo(0L);
	}

	/**
	 * The report endpoints' isolation model is different from the
	 * session-scoped mark/roster endpoints (plan §14): they are tenant-scoped
	 * by construction via {@code TenantAwareRepository}, so a staff caller
	 * naming another tenant's {@code courseId} in the {@code courseId} filter
	 * gets a genuinely empty result (proving no leak), not a 404 - there is
	 * no "does this course exist" existence check to leak in the first
	 * place, only a tenant-scoped {@code WHERE} that this id can never match.
	 */
	@Test
	void attendanceOperatorReportNeverLeaksAnotherTenantsRowsEvenWithAnExplicitCrossTenantCourseIdFilter() {
		AttendanceFixture tenantA = seedAttendanceFixture("att-xt-report-filter-a");
		AttendanceFixture tenantB = seedAttendanceFixture("att-xt-report-filter-b");
		seedTenantUser(tenantA.tenant().getId(), "operator@example.test", RAW_PASSWORD, Role.ATTENDANCE_OPERATOR);
		String operatorTokenA = loginAndGetToken(tenantA.host(), "operator@example.test");
		markOneStudent(tenantB.host(), tenantB.teacherToken(), tenantB.lessonId(), tenantB.student().getId(),
				AttendanceStatus.PRESENT);

		HttpResult<PageResponse<AttendanceRecordResponse>> result = getAttendanceReport(tenantA.host(),
				operatorTokenA, "courseId=" + tenantB.course().id());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(result.getBody().data().content()).isEmpty();
	}

	/**
	 * The {@code /my} endpoint's own equivalent of {@code
	 * attendanceOperatorReportNeverLeaksAnotherTenantsRowsEvenWithAnExplicitCrossTenantCourseIdFilter}
	 * above (post-ship review Finding 5, MVP-016 plan §22) - redundant
	 * defense-in-depth documentation that {@code
	 * AttendanceSpecifications#withCourseId} is the SAME shared mechanism
	 * behind both {@code GET /my} and {@code GET /reports}, and is genuinely
	 * tenant-scoped on both call sites, not just the one already covered. A
	 * Student in Tenant A naming Tenant B's real {@code courseId} in the
	 * {@code courseId} filter must get a genuinely empty result (the tenant-
	 * scoped {@code WHERE} can never match that id), never another student's
	 * (or another tenant's) row.
	 */
	@Test
	void myAttendanceNeverLeaksAnotherTenantsRowsEvenWithAnExplicitCrossTenantCourseIdFilter() {
		AttendanceFixture tenantA = seedAttendanceFixture("att-xt-my-filter-a");
		AttendanceFixture tenantB = seedAttendanceFixture("att-xt-my-filter-b");
		markOneStudent(tenantA.host(), tenantA.teacherToken(), tenantA.lessonId(), tenantA.student().getId(),
				AttendanceStatus.PRESENT);
		markOneStudent(tenantB.host(), tenantB.teacherToken(), tenantB.lessonId(), tenantB.student().getId(),
				AttendanceStatus.ABSENT);

		HttpResult<PageResponse<AttendanceRecordResponse>> result = getMyAttendance(tenantB.host(),
				tenantB.studentToken(), "courseId=" + tenantA.course().id());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(result.getBody().data().content()).isEmpty();

		// Sanity: tenant B's OWN courseId filter DOES return their own row -
		// proves the emptiness above is tenant isolation, not a broken filter.
		HttpResult<PageResponse<AttendanceRecordResponse>> ownFilterResult = getMyAttendance(tenantB.host(),
				tenantB.studentToken(), "courseId=" + tenantB.course().id());
		assertThat(ownFilterResult.getBody().data().content()).extracting(AttendanceRecordResponse::studentId)
			.containsExactly(tenantB.student().getId());
	}

	/**
	 * Mirrors {@code EnrollmentCrossTenantIntegrationTest
	 * #myCourseSummariesNeverContainsAnotherTenantsRowsEvenForAnIdenticallyNamedCourse}'s
	 * colliding-name fixture pattern: Tenant A and Tenant B each get a course
	 * with the DELIBERATELY colliding slug/category and lesson title, so a
	 * passing test proves real tenant isolation, not merely "different data
	 * happened to differ".
	 */
	@Test
	void reportsNeverReturnAnotherTenantsRowsForStudentTeacherOrStaffEvenUnderCollidingCourseAndLessonNames() {
		String collidingSlug = uniqueSlug("att-xt-collide");
		String collidingCategory = "Physics";
		String collidingLessonTitle = "Week 1 Lecture";
		AttendanceFixture tenantA = seedAttendanceFixture("att-xt-collide-a", collidingSlug, collidingCategory,
				collidingLessonTitle);
		AttendanceFixture tenantB = seedAttendanceFixture("att-xt-collide-b", collidingSlug, collidingCategory,
				collidingLessonTitle);
		assertThat(tenantA.course().name()).isEqualTo(tenantB.course().name());
		assertThat(tenantA.course().slug()).isEqualTo(tenantB.course().slug());
		assertThat(tenantA.course().category()).isEqualTo(tenantB.course().category());
		assertThat(tenantA.course().id()).isNotEqualTo(tenantB.course().id());
		markOneStudent(tenantA.host(), tenantA.teacherToken(), tenantA.lessonId(), tenantA.student().getId(),
				AttendanceStatus.PRESENT);
		markOneStudent(tenantB.host(), tenantB.teacherToken(), tenantB.lessonId(), tenantB.student().getId(),
				AttendanceStatus.ABSENT);

		// Student.
		HttpResult<PageResponse<AttendanceRecordResponse>> studentResult = getMyAttendance(tenantB.host(),
				tenantB.studentToken(), null);
		assertThat(studentResult.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(studentResult.getBody().data().content()).extracting(AttendanceRecordResponse::studentId)
			.containsExactly(tenantB.student().getId())
			.doesNotContain(tenantA.student().getId());

		// Teacher.
		HttpResult<PageResponse<AttendanceRecordResponse>> teacherResult = getAttendanceReport(tenantB.host(),
				tenantB.teacherToken(), null);
		assertThat(teacherResult.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(teacherResult.getBody().data().content()).extracting(AttendanceRecordResponse::courseId)
			.containsExactly(tenantB.course().id())
			.doesNotContain(tenantA.course().id());

		// Tenant-wide staff.
		HttpResult<PageResponse<AttendanceRecordResponse>> staffResult = getAttendanceReport(tenantB.host(),
				tenantB.adminToken(), null);
		assertThat(staffResult.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(staffResult.getBody().data().content()).extracting(AttendanceRecordResponse::courseId)
			.containsExactly(tenantB.course().id())
			.doesNotContain(tenantA.course().id());

		// Sanity: tenant A's own reads DO see its own (identically-named) rows.
		HttpResult<PageResponse<AttendanceRecordResponse>> studentA = getMyAttendance(tenantA.host(),
				tenantA.studentToken(), null);
		assertThat(studentA.getBody().data().content()).extracting(AttendanceRecordResponse::studentId)
			.containsExactly(tenantA.student().getId());
	}

	@Test
	void sameTenantDifferentTeacherIsForbiddenNot404WhileCrossTenantSessionIsAlways404() {
		// Documents the deliberate distinction this module's own security
		// review names (plan §15): a same-tenant Teacher who is simply not
		// the owner gets 403 (an accepted, already-established codebase
		// convention - teachers legitimately see their own tenant's course
		// existence), while a cross-tenant sessionId is always 404
		// (structurally invisible). Both are proven together here so the two
		// codes are never confused with each other.
		AttendanceFixture fixture = seedAttendanceFixture("att-xt-403-vs-404-a");
		AttendanceFixture otherTenant = seedAttendanceFixture("att-xt-403-vs-404-b");
		TenantUser sameTenantOtherTeacher = seedTenantUser(fixture.tenant().getId(), "teacher2@example.test",
				RAW_PASSWORD, Role.TEACHER);
		String sameTenantOtherTeacherToken = loginAndGetToken(fixture.host(), "teacher2@example.test");

		HttpResult<List<AttendanceMarkResultResponse>> sameTenantResult = markOneStudent(fixture.host(),
				sameTenantOtherTeacherToken, fixture.lessonId(), fixture.student().getId(), AttendanceStatus.PRESENT);
		assertThat(sameTenantResult.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		Long sameTenantRowCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM attendance_record WHERE session_id = ?", Long.class, fixture.lessonId());
		assertThat(sameTenantRowCount).isEqualTo(0L);

		HttpResult<List<AttendanceMarkResultResponse>> crossTenantResult = markOneStudent(otherTenant.host(),
				otherTenant.teacherToken(), fixture.lessonId(), fixture.student().getId(), AttendanceStatus.PRESENT);
		assertThat(crossTenantResult.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(sameTenantOtherTeacher.getId()).isNotNull();
	}

}
