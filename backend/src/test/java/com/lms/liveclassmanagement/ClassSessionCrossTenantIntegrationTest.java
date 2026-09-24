package com.lms.liveclassmanagement;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.identityaccessservice.HttpResult;
import com.lms.liveclassmanagement.web.dto.ClassSessionResponse;
import com.lms.liveclassmanagement.web.dto.ClassSessionUpdateRequest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Mandatory cross-tenant negative tests (Wave 4 plan §7/§8) - every new
 * endpoint (schedule, list, detail, status transitions, retry) must be
 * proven to give a Tenant B caller 404 (never 200-with-filtered-data, never
 * a cross-tenant write) when addressing Tenant A's {@code sessionId}/{@code
 * courseId}. Join/recording cross-tenant coverage lives in {@link
 * ClassSessionJoinAndRecordingIntegrationTest} alongside their other
 * entitlement cases.
 */
class ClassSessionCrossTenantIntegrationTest extends LiveClassManagementTestSupport {

	@Test
	@Tag("cross-tenant")
	void tenantBTeacherSchedulingAgainstTenantAsCourseIdGetsNotFoundAndWritesNoRow() {
		LiveClassFixture fixtureA = seedLiveClassFixture("lc-xt-schedule-a");
		LiveClassFixture fixtureB = seedLiveClassFixture("lc-xt-schedule-b");

		HttpResult<ClassSessionResponse> result = scheduleSession(fixtureB.host(), fixtureB.teacherToken(),
				newSessionRequest(fixtureA.course().id(), null, "Cross-tenant attempt"));

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM class_session WHERE course_id = ?", Long.class,
				fixtureA.course().id());
		assertThat(count).isEqualTo(0L);
	}

	@Test
	@Tag("cross-tenant")
	void tenantBListingNeverIncludesTenantAsSession() {
		LiveClassFixture fixtureA = seedLiveClassFixture("lc-xt-list-a");
		LiveClassFixture fixtureB = seedLiveClassFixture("lc-xt-list-b");
		ClassSessionResponse sessionA = scheduleSessionOrFail(fixtureA.host(), fixtureA.teacherToken(),
				newSessionRequest(fixtureA.course().id(), null, "Tenant A session"));
		scheduleSessionOrFail(fixtureB.host(), fixtureB.teacherToken(),
				newSessionRequest(fixtureB.course().id(), null, "Tenant B session"));

		HttpResult<List<ClassSessionResponse>> tenantBAdminList = listSessions(fixtureB.host(), fixtureB.adminToken());

		assertThat(tenantBAdminList.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(tenantBAdminList.getBody().data()).extracting(ClassSessionResponse::id).doesNotContain(sessionA.id());
	}

	@Test
	@Tag("cross-tenant")
	void tenantBAdminGettingTenantAsSessionByIdReturns404() {
		LiveClassFixture fixtureA = seedLiveClassFixture("lc-xt-detail-a");
		LiveClassFixture fixtureB = seedLiveClassFixture("lc-xt-detail-b");
		ClassSessionResponse sessionA = scheduleSessionOrFail(fixtureA.host(), fixtureA.teacherToken(),
				newSessionRequest(fixtureA.course().id(), null, "Tenant A session"));

		HttpResult<ClassSessionResponse> result = getSession(fixtureB.host(), fixtureB.adminToken(), sessionA.id());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	@Tag("cross-tenant")
	void tenantBAdminEditingTenantAsSessionReturns404AndLeavesItUnchanged() {
		LiveClassFixture fixtureA = seedLiveClassFixture("lc-xt-edit-a");
		LiveClassFixture fixtureB = seedLiveClassFixture("lc-xt-edit-b");
		ClassSessionResponse sessionA = scheduleSessionOrFail(fixtureA.host(), fixtureA.teacherToken(),
				newSessionRequest(fixtureA.course().id(), null, "Original title"));
		Instant newStart = Instant.now().plus(3, ChronoUnit.DAYS);

		HttpResult<ClassSessionResponse> result = updateSession(fixtureB.host(), fixtureB.adminToken(), sessionA.id(),
				new ClassSessionUpdateRequest("Hijacked title", "Hijacked", newStart,
						newStart.plus(1, ChronoUnit.HOURS)));

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		var row = jdbcTemplate.queryForMap("SELECT title FROM class_session WHERE id = ?", sessionA.id());
		assertThat(row.get("title")).isEqualTo("Original title");
	}

	@Test
	@Tag("cross-tenant")
	void tenantBAdminStartingTenantAsSessionReturns404AndLeavesStatusUnchanged() {
		LiveClassFixture fixtureA = seedLiveClassFixture("lc-xt-start-a");
		LiveClassFixture fixtureB = seedLiveClassFixture("lc-xt-start-b");
		ClassSessionResponse sessionA = scheduleSessionOrFail(fixtureA.host(), fixtureA.teacherToken(),
				newSessionRequest(fixtureA.course().id(), null, "Tenant A session"));

		HttpResult<ClassSessionResponse> result = startSession(fixtureB.host(), fixtureB.adminToken(), sessionA.id());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		var row = jdbcTemplate.queryForMap("SELECT status FROM class_session WHERE id = ?", sessionA.id());
		assertThat(row.get("status")).isEqualTo("SCHEDULED");
	}

	@Test
	@Tag("cross-tenant")
	void tenantBAdminCancellingTenantAsSessionReturns404() {
		LiveClassFixture fixtureA = seedLiveClassFixture("lc-xt-cancel-a");
		LiveClassFixture fixtureB = seedLiveClassFixture("lc-xt-cancel-b");
		ClassSessionResponse sessionA = scheduleSessionOrFail(fixtureA.host(), fixtureA.teacherToken(),
				newSessionRequest(fixtureA.course().id(), null, "Tenant A session"));

		HttpResult<ClassSessionResponse> result = cancelSession(fixtureB.host(), fixtureB.adminToken(), sessionA.id());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	@Tag("cross-tenant")
	void tenantBAdminRetryingProvisioningOnTenantAsSessionReturns404() {
		LiveClassFixture fixtureA = seedLiveClassFixture("lc-xt-retry-a");
		LiveClassFixture fixtureB = seedLiveClassFixture("lc-xt-retry-b");
		ClassSessionResponse sessionA = scheduleSessionOrFail(fixtureA.host(), fixtureA.teacherToken(),
				newSessionRequest(fixtureA.course().id(), null, "Tenant A session"));

		HttpResult<ClassSessionResponse> result = retryProvisioning(fixtureB.host(), fixtureB.adminToken(),
				sessionA.id());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

}
