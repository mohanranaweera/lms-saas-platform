package com.lms.attendancemanagement;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.attendancemanagement.domain.AttendanceStatus;
import com.lms.identityaccessservice.domain.Role;
import com.lms.liveclassmanagement.web.dto.ClassSessionResponse;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Wave 8 mandatory cross-tenant negative tests (.claude/rules/tenancy.md) for
 * every new class-session attendance surface: a Tenant B caller addressing
 * Tenant A's real class session / course by id gets 404 (never 200-with-empty,
 * never 403 leaking existence) and causes zero writes; a report filter by a
 * foreign session id yields an empty page, never Tenant A's rows.
 */
@Tag("cross-tenant")
class AttendanceClassSessionCrossTenantIntegrationTest extends AttendanceClassSessionTestSupport {

	@Test
	void rosterAndMarkForAnotherTenantsClassSessionReturn404AndWriteNothing() {
		AttendanceFixture tenantA = seedAttendanceFixture("att-cs-xt-a");
		AttendanceFixture tenantB = seedAttendanceFixture("att-cs-xt-b");
		ClassSessionResponse sessionA = scheduleStartedSession(tenantA, "A week 1");

		for (String tokenB : new String[] { tenantB.teacherToken(), tenantB.adminToken() }) {
			assertThat(getClassSessionRoster(tenantB.host(), tokenB, sessionA.id()).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
			assertThat(markClassSessionOne(tenantB.host(), tokenB, sessionA.id(), tenantA.student().getId(),
					AttendanceStatus.PRESENT)
				.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		}
		assertThat(countSheetsForSession(sessionA.id())).isZero();

		// Sanity: tenant A's own teacher can - the 404s above are isolation, not a broken endpoint.
		assertThat(markClassSessionOne(tenantA.host(), tenantA.teacherToken(), sessionA.id(),
				tenantA.student().getId(), AttendanceStatus.PRESENT)
			.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	void anAttendanceOperatorCannotReachAnotherTenantsSession() {
		AttendanceFixture tenantA = seedAttendanceFixture("att-cs-xt-op-a");
		AttendanceFixture tenantB = seedAttendanceFixture("att-cs-xt-op-b");
		ClassSessionResponse sessionB = scheduleStartedSession(tenantB, "B week 1");
		seedTenantUser(tenantA.tenant().getId(), "operator@example.test", RAW_PASSWORD, Role.ATTENDANCE_OPERATOR);
		String operatorA = loginAndGetToken(tenantA.host(), "operator@example.test");

		assertThat(getClassSessionRoster(tenantA.host(), operatorA, sessionB.id()).getStatusCode())
			.isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(markClassSessionOne(tenantA.host(), operatorA, sessionB.id(), tenantB.student().getId(),
				AttendanceStatus.PRESENT)
			.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void summaryForAnotherTenantsCourseReturns404() {
		AttendanceFixture tenantA = seedAttendanceFixture("att-cs-xt-sum-a");
		AttendanceFixture tenantB = seedAttendanceFixture("att-cs-xt-sum-b");
		ClassSessionResponse sessionA = scheduleStartedSession(tenantA, "A week 1");
		markClassSessionOne(tenantA.host(), tenantA.teacherToken(), sessionA.id(), tenantA.student().getId(),
				AttendanceStatus.PRESENT);

		assertThat(getCourseSummary(tenantB.host(), tenantB.adminToken(), tenantA.course().id()).getStatusCode())
			.isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(getCourseSummary(tenantB.host(), tenantB.teacherToken(), tenantA.course().id()).getStatusCode())
			.isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void reportAndMyFiltersByAnotherTenantsSessionIdReturnEmptyNeverForeignRows() {
		AttendanceFixture tenantA = seedAttendanceFixture("att-cs-xt-rep-a");
		AttendanceFixture tenantB = seedAttendanceFixture("att-cs-xt-rep-b");
		ClassSessionResponse sessionA = scheduleStartedSession(tenantA, "A week 1");
		markClassSessionOne(tenantA.host(), tenantA.teacherToken(), sessionA.id(), tenantA.student().getId(),
				AttendanceStatus.PRESENT);

		assertThat(getAttendanceReport(tenantB.host(), tenantB.adminToken(), "classSessionId=" + sessionA.id())
			.getBody()
			.data()
			.content()).isEmpty();
		assertThat(getMyAttendanceForSession(tenantB.host(), tenantB.studentToken(), sessionA.id()).getBody()
			.data()
			.content()).isEmpty();
	}

}
