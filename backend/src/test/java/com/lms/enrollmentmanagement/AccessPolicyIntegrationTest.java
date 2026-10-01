package com.lms.enrollmentmanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.lms.contentmanagement.ContentManagementTestSupport;
import com.lms.coursemanagement.course.web.dto.CourseLessonResponse;
import com.lms.coursemanagement.course.web.dto.CourseModuleResponse;
import com.lms.enrollmentmanagement.api.EnrollmentAccessStateType;
import com.lms.enrollmentmanagement.service.AccessExtensionService.AccessExtensionView;
import com.lms.enrollmentmanagement.service.AccessPolicyAdminService.CourseAccessPolicyView;
import com.lms.enrollmentmanagement.web.dto.CourseRosterEntryResponse;
import com.lms.enrollmentmanagement.web.dto.EnrollmentAccessStateResponse;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.web.dto.LoginResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

/**
 * Wave 10 centralized access policy, end to end: manual extensions (active and
 * expired enrollments, never revoked/lifetime, append-only), grace period
 * (tenant default, course override), the course-level device limit, and that
 * every content path (materials, course detail, rosters) follows the same
 * decision - plus permissions and cross-tenant isolation of the new endpoints.
 */
class AccessPolicyIntegrationTest extends ContentManagementTestSupport {

	private record Content(UUID moduleId, UUID lessonId) {
	}

	// ---- manual extension ----

	@Test
	void extendingAnExpiredEnrollmentRestoresAccessWithoutTouchingTheEnrollmentRow() {
		ExpiredEnrollmentFixture f = seedExpiredEnrollmentFixture("acc-ext");
		seedProfile(f);
		Content content = seedContent(f);
		Instant originalExpiry = storedExpiry(f.enrollmentId());
		assertThat(getAccessState(f.host(), f.studentToken(), f.course().id()).getBody().data().state())
			.isEqualTo(EnrollmentAccessStateType.EXPIRED);
		assertThat(listMaterials(f.host(), f.studentToken(), f.course().id(), content.moduleId(), content.lessonId())
			.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(roster(f).getBody().data()).noneMatch(entry -> entry.userId().equals(f.student().getId()));

		Instant newExpiry = Instant.now().plus(Duration.ofDays(10)).truncatedTo(ChronoUnit.SECONDS);
		HttpResult<AccessExtensionView> extension = extend(f.host(), f.adminToken(), f.enrollmentId(), newExpiry,
				"Hospitalised during the exam period");

		assertThat(extension.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(extension.getBody().data().previousExpiresAt()).isEqualTo(originalExpiry);
		assertThat(storedExpiry(f.enrollmentId())).as("enrollment.access_expires_at is never updated")
			.isEqualTo(originalExpiry);
		EnrollmentAccessStateResponse state = getAccessState(f.host(), f.studentToken(), f.course().id()).getBody()
			.data();
		assertThat(state.state()).isEqualTo(EnrollmentAccessStateType.ACTIVE);
		assertThat(state.accessExpiresAt()).isEqualTo(newExpiry);
		assertThat(listMaterials(f.host(), f.studentToken(), f.course().id(), content.moduleId(), content.lessonId())
			.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(roster(f).getBody().data()).anyMatch(entry -> entry.userId().equals(f.student().getId()));

		HttpResult<List<AccessExtensionView>> history = parseList(
				perform(authenticated(get("/api/v1/enrollments/" + f.enrollmentId() + "/extensions"), f.host(),
						f.adminToken())),
				AccessExtensionView.class);
		assertThat(history.getBody().data()).hasSize(1);
		assertThat(auditCount(f, "enrollment.access_extended")).isEqualTo(2);
	}

	@Test
	void anExtensionMustMoveExpiryForwardCarryAReasonAndTargetACurrentTimeLimitedEnrollment() {
		ExpiredEnrollmentFixture f = seedExpiredEnrollmentFixture("acc-ext-rules");
		Instant future = Instant.now().plus(Duration.ofDays(5));

		assertThat(extend(f.host(), f.adminToken(), f.enrollmentId(), Instant.now().minus(Duration.ofHours(1)),
				"reason").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(extend(f.host(), f.adminToken(), f.enrollmentId(), future, " ").getStatusCode())
			.isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(extend(f.host(), f.adminToken(), f.enrollmentId(), Instant.now().plus(Duration.ofDays(3000)),
				"too far").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

		jdbcTemplate.update("UPDATE enrollment SET access_expires_at = NULL WHERE id = ?", f.enrollmentId());
		assertThat(extend(f.host(), f.adminToken(), f.enrollmentId(), future, "lifetime").getStatusCode())
			.isEqualTo(HttpStatus.CONFLICT);
		jdbcTemplate.update("UPDATE enrollment SET access_expires_at = now() - interval '1 day' WHERE id = ?",
				f.enrollmentId());

		HttpResult<Map> revoked = postJson(f.host(), f.adminToken(), "/api/v1/enrollments/" + f.enrollmentId()
				+ "/revoke", Map.of("reason", "Refund issued"), Map.class);
		assertThat(revoked.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(extend(f.host(), f.adminToken(), f.enrollmentId(), future, "after revoke").getStatusCode())
			.as("a revoked enrollment can never be extended back to life")
			.isEqualTo(HttpStatus.CONFLICT);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM enrollment_access_extension WHERE tenant_id = ?",
				Integer.class, f.tenant().getId())).isZero();
	}

	@Test
	void extensionsAreTenantAdminOnlyTenantIsolatedAndAppendOnly() {
		ExpiredEnrollmentFixture a = seedExpiredEnrollmentFixture("acc-ext-a");
		ExpiredEnrollmentFixture b = seedExpiredEnrollmentFixture("acc-ext-b");
		Instant future = Instant.now().plus(Duration.ofDays(5));

		assertThat(extend(a.host(), a.studentToken(), a.enrollmentId(), future, "self").getStatusCode())
			.isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(extend(b.host(), b.adminToken(), a.enrollmentId(), future, "cross").getStatusCode())
			.isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(perform(authenticated(get("/api/v1/enrollments/" + a.enrollmentId() + "/extensions"), b.host(),
				b.adminToken())).getResponse().getStatus()).isEqualTo(404);

		assertThat(extend(a.host(), a.adminToken(), a.enrollmentId(), future, "ok").getStatusCode())
			.isEqualTo(HttpStatus.CREATED);
		assertThatThrownBy(() -> jdbcTemplate.update(
				"UPDATE enrollment_access_extension SET reason = 'rewritten' WHERE tenant_id = ?", a.tenant().getId()))
			.isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM enrollment_access_extension WHERE tenant_id = ?",
				a.tenant().getId()))
			.isInstanceOf(DataAccessException.class);
	}

	// ---- grace period ----

	@Test
	void theTenantGracePeriodKeepsAccessOpenAndACourseOverrideBeatsIt() {
		ExpiredEnrollmentFixture f = seedExpiredEnrollmentFixture("acc-grace");
		Content content = seedContent(f);
		Instant expiry = storedExpiry(f.enrollmentId());

		HttpResult<Map> tenantGrace = putJson(f.host(), f.adminToken(), "/api/v1/access-policy",
				Map.of("gracePeriodDays", 3), Map.class);
		assertThat(tenantGrace.getStatusCode()).isEqualTo(HttpStatus.OK);

		EnrollmentAccessStateResponse inGrace = getAccessState(f.host(), f.studentToken(), f.course().id()).getBody()
			.data();
		assertThat(inGrace.state()).isEqualTo(EnrollmentAccessStateType.ACTIVE);
		assertThat(inGrace.inGracePeriod()).isTrue();
		assertThat(inGrace.accessEndsAt()).isEqualTo(expiry.plus(Duration.ofDays(3)));
		assertThat(listMaterials(f.host(), f.studentToken(), f.course().id(), content.moduleId(), content.lessonId())
			.getStatusCode()).isEqualTo(HttpStatus.OK);

		HttpResult<CourseAccessPolicyView> courseOverride = putCoursePolicy(f.host(), f.adminToken(),
				f.course().id(), 0, null);
		assertThat(courseOverride.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(getAccessState(f.host(), f.studentToken(), f.course().id()).getBody().data().state())
			.isEqualTo(EnrollmentAccessStateType.EXPIRED);
		assertThat(listMaterials(f.host(), f.studentToken(), f.course().id(), content.moduleId(), content.lessonId())
			.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

		HttpResult<List<CourseAccessPolicyView>> listed = parseList(
				perform(authenticated(get("/api/v1/access-policy/courses"), f.host(), f.adminToken())),
				CourseAccessPolicyView.class);
		assertThat(listed.getBody().data()).singleElement()
			.satisfies(view -> assertThat(view.courseName()).isNotBlank());
		assertThat(auditCount(f, "access_policy.updated")).isEqualTo(1);
		assertThat(auditCount(f, "course_access_policy.updated")).isEqualTo(1);
	}

	// ---- course-level device limit ----

	@Test
	void aCourseDeviceLimitBlocksContentOnExtraDevicesUnlessTheStudentHasAnException() {
		ExpiredEnrollmentFixture f = seedExpiredEnrollmentFixture("acc-dev");
		Content content = seedContent(f);
		extend(f.host(), f.adminToken(), f.enrollmentId(), Instant.now().plus(Duration.ofDays(30)), "active");
		String secondDeviceToken = secondDeviceToken(f);

		assertThat(putCoursePolicy(f.host(), f.adminToken(), f.course().id(), null, 1).getStatusCode())
			.isEqualTo(HttpStatus.OK);

		assertThat(listMaterials(f.host(), f.studentToken(), f.course().id(), content.moduleId(), content.lessonId())
			.getStatusCode()).as("the first registered device is the authorized one").isEqualTo(HttpStatus.OK);
		HttpResult<?> blocked = listMaterials(f.host(), secondDeviceToken, f.course().id(), content.moduleId(),
				content.lessonId());
		assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(blocked.getBody().error().code()).isEqualTo("DEVICE_NOT_AUTHORIZED");
		assertThat(getAccessState(f.host(), secondDeviceToken, f.course().id()).getBody().data().deviceAuthorized())
			.isFalse();

		// Student exception outranks the course limit (precedence student > course > tenant > plan).
		jdbcTemplate.update("INSERT INTO student_device_limit_override (id, tenant_id, user_id, max_devices, reason, "
				+ "set_by, set_at) VALUES (?, ?, ?, 2, 'exception', ?, now())", UUID.randomUUID(), f.tenant().getId(),
				f.student().getId(), f.admin().getId());
		assertThat(listMaterials(f.host(), secondDeviceToken, f.course().id(), content.moduleId(), content.lessonId())
			.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	void coursePolicyChangesArePermissionCheckedValidatedAndTenantIsolated() {
		ExpiredEnrollmentFixture a = seedExpiredEnrollmentFixture("acc-pol-a");
		ExpiredEnrollmentFixture b = seedExpiredEnrollmentFixture("acc-pol-b");
		seedTenantUser(a.tenant().getId(), "support@example.test", RAW_PASSWORD, Role.STUDENT_SUPPORT);
		String support = loginAndGetToken(a.host(), "support@example.test");

		assertThat(perform(authenticated(get("/api/v1/access-policy"), a.host(), support)).getResponse().getStatus())
			.isEqualTo(200);
		assertThat(putCoursePolicy(a.host(), support, a.course().id(), 2, null).getStatusCode())
			.isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(putCoursePolicy(a.host(), a.studentToken(), a.course().id(), 2, null).getStatusCode())
			.isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(putCoursePolicy(a.host(), a.adminToken(), a.course().id(), null, 0).getStatusCode())
			.isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(putJson(a.host(), a.adminToken(), "/api/v1/access-policy", Map.of("gracePeriodDays", 91),
				Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

		assertThat(putCoursePolicy(b.host(), b.adminToken(), a.course().id(), 2, null).getStatusCode())
			.isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(putCoursePolicy(a.host(), a.adminToken(), a.course().id(), 2, 1).getStatusCode())
			.isEqualTo(HttpStatus.OK);
		HttpResult<List<CourseAccessPolicyView>> bList = parseList(
				perform(authenticated(get("/api/v1/access-policy/courses"), b.host(), b.adminToken())),
				CourseAccessPolicyView.class);
		assertThat(bList.getBody().data()).isEmpty();

		// Both values cleared removes the override.
		assertThat(putCoursePolicy(a.host(), a.adminToken(), a.course().id(), null, null).getStatusCode())
			.isEqualTo(HttpStatus.OK);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM course_access_policy WHERE tenant_id = ?",
				Integer.class, a.tenant().getId())).isZero();
	}

	// ---- helpers ----

	private Content seedContent(ExpiredEnrollmentFixture f) {
		String teacherToken = loginAndGetToken(f.host(), "teacher@example.test");
		CourseModuleResponse module = createModuleOrFail(f.host(), teacherToken, f.course().id(), "Module 1", 1);
		CourseLessonResponse lesson = createLessonOrFail(f.host(), teacherToken, f.course().id(), module.id(),
				"Lesson 1", 1);
		createLinkMaterialOrFail(f.host(), teacherToken, f.course().id(), module.id(), lesson.id(), "Reading",
				"https://example.test/reading");
		return new Content(module.id(), lesson.id());
	}

	private void seedProfile(ExpiredEnrollmentFixture f) {
		jdbcTemplate.update("INSERT INTO student_profile (id, tenant_id, user_id, name, created_at, updated_at) "
				+ "VALUES (?, ?, ?, 'Kamala Perera', now(), now())", UUID.randomUUID(), f.tenant().getId(),
				f.student().getId());
	}

	/** A second login without the device cookie = a second registered device for the same student. */
	private String secondDeviceToken(ExpiredEnrollmentFixture f) {
		HttpResult<LoginResponse> second = login(f.host(), "student@example.test", RAW_PASSWORD);
		assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
		return second.getBody().data().accessToken();
	}

	private Instant storedExpiry(UUID enrollmentId) {
		return jdbcTemplate.queryForObject("SELECT access_expires_at FROM enrollment WHERE id = ?", Instant.class,
				enrollmentId);
	}

	private int auditCount(ExpiredEnrollmentFixture f, String action) {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = ?",
				Integer.class, f.tenant().getId(), action);
	}

	private HttpResult<List<CourseRosterEntryResponse>> roster(ExpiredEnrollmentFixture f) {
		return parseList(perform(authenticated(get("/api/v1/courses/" + f.course().id() + "/roster"), f.host(),
				f.adminToken())), CourseRosterEntryResponse.class);
	}

	private HttpResult<AccessExtensionView> extend(String host, String token, UUID enrollmentId, Instant newExpiresAt,
			String reason) {
		return postJson(host, token, "/api/v1/enrollments/" + enrollmentId + "/extensions",
				Map.of("newExpiresAt", newExpiresAt.toString(), "reason", reason), AccessExtensionView.class);
	}

	private HttpResult<CourseAccessPolicyView> putCoursePolicy(String host, String token, UUID courseId,
			Integer gracePeriodDays, Integer maxDevices) {
		Map<String, Object> body = new HashMap<>();
		body.put("gracePeriodDays", gracePeriodDays);
		body.put("maxDevices", maxDevices);
		return putJson(host, token, "/api/v1/access-policy/courses/" + courseId, body, CourseAccessPolicyView.class);
	}

	private <T> HttpResult<T> postJson(String host, String token, String path, Object body, Class<T> type) {
		return parseSingle(perform(authenticated(
				post(path).contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)), host,
				token)), type);
	}

	private <T> HttpResult<T> putJson(String host, String token, String path, Object body, Class<T> type) {
		return parseSingle(perform(authenticated(
				put(path).contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)), host,
				token)), type);
	}

}
