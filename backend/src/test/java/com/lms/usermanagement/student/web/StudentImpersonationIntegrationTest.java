package com.lms.usermanagement.student.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.identityaccessservice.service.ParsedToken;
import com.lms.usermanagement.student.StudentManagementTestSupport;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * ADR-033 "Log as Student" support sessions: start / token / current / end, Tenant
 * Admin only, nested and invalid targets refused, cross-tenant 404, read-only (403
 * IMPERSONATION_READ_ONLY on writes), absolute expiry, admin logout / suspension and
 * replacement revoke access, {@code /auth/refresh} cannot extend it, and the audit
 * trail keeps the admin and the student identities apart.
 */
class StudentImpersonationIntegrationTest extends StudentManagementTestSupport {

	private static final String COOKIE = "lms_impersonation";

	private record Started(UUID sessionId, Instant expiresAt, String cookie, String setCookieHeader) {
	}

	private MvcResult startRaw(Fx fx, String token, UUID studentId, String reason) {
		return send(fx.host(), token, post("/api/v1/students/{id}/impersonation", studentId), Map.of("reason", reason));
	}

	private Started start(Fx fx, String token, UUID studentId) {
		MvcResult result = startRaw(fx, token, studentId, "Checking what the student sees on the course page");
		assertThat(status(result)).as(rawContent(result)).isEqualTo(201);
		String header = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)
			.stream()
			.filter(value -> value.startsWith(COOKIE + "="))
			.findFirst()
			.orElseThrow();
		String value = header.substring(COOKIE.length() + 1, header.indexOf(';'));
		JsonNode data = data(result);
		return new Started(UUID.fromString(data.path("sessionId").asString()),
				Instant.parse(data.path("expiresAt").asString()), value, header);
	}

	private MvcResult exchange(Fx fx, String cookie) {
		MockHttpServletRequestBuilder builder = post("/api/v1/auth/impersonation/token");
		if (cookie != null) {
			builder.cookie(new Cookie(COOKIE, cookie));
		}
		return send(fx.host(), null, builder, null);
	}

	private String impersonationToken(Fx fx, Started started) {
		MvcResult result = exchange(fx, started.cookie());
		assertThat(status(result)).as(rawContent(result)).isEqualTo(200);
		return data(result).path("accessToken").asString();
	}

	private String endReason(UUID sessionId) {
		return jdbcTemplate.queryForObject("SELECT end_reason FROM impersonation_session WHERE id = ?", String.class,
				sessionId);
	}

	@Test
	void startTokenCurrentAndEndHappyPathWithSeparateIdentitiesInTheAudit() {
		Fx fx = fixture("imp-happy");
		UUID studentId = createStudentId(fx, "Seen", "Student");
		UUID studentUser = userIdOf(studentId);
		long studentSessionsBefore = count("SELECT count(*) FROM device_session WHERE user_id = ?", studentUser);

		Started started = start(fx, fx.adminToken(), studentId);
		assertThat(started.setCookieHeader()).contains("HttpOnly")
			.contains("SameSite=Strict")
			.contains("Path=/api/v1/auth/impersonation")
			.contains("Secure");
		assertThat(started.expiresAt()).isBefore(Instant.now().plusSeconds(31 * 60))
			.isAfter(Instant.now().plusSeconds(29 * 60));

		MvcResult tokenResult = exchange(fx, started.cookie());
		assertThat(status(tokenResult)).as(rawContent(tokenResult)).isEqualTo(200);
		JsonNode grant = data(tokenResult);
		String token = grant.path("accessToken").asString();
		assertThat(grant.path("student").path("id").asString()).isEqualTo(studentId.toString());
		assertThat(grant.path("student").path("fullName").asString()).isEqualTo("Seen Student");
		assertThat(grant.path("student").path("indexNumber").asString()).isNotBlank();
		assertThat(grant.path("actorName").asString()).isEqualTo("admin@example.test");
		ParsedToken parsed = tokenService.parseAndValidate(token);
		assertThat(parsed.impersonation()).isTrue();
		assertThat(parsed.subject()).isEqualTo(studentUser);
		assertThat(parsed.actorUserId()).isEqualTo(fx.admin().getId());
		assertThat(parsed.sessionId()).isEqualTo(started.sessionId());
		assertThat(parsed.role()).isEqualTo("STUDENT");
		assertThat(parsed.expiresAt()).isBeforeOrEqualTo(started.expiresAt());

		// The student's own view, with the student's rules - and no admin permissions.
		MvcResult me = getAs(fx.host(), token, "/api/v1/students/me");
		assertThat(status(me)).as(rawContent(me)).isEqualTo(200);
		assertThat(data(me).path("name").asString()).isEqualTo("Seen Student");
		assertThat(status(getAs(fx.host(), token, "/api/v1/students/directory"))).isEqualTo(403);
		MvcResult current = getAs(fx.host(), token, "/api/v1/auth/impersonation/current");
		assertThat(status(current)).isEqualTo(200);
		assertThat(data(current).path("sessionId").asString()).isEqualTo(started.sessionId().toString());
		assertThat(data(current).path("actorName").asString()).isEqualTo("admin@example.test");
		// An ordinary session has no current support session.
		assertThat(status(getAs(fx.host(), fx.adminToken(), "/api/v1/auth/impersonation/current"))).isEqualTo(404);

		// No device session / last-login side effect for the student.
		assertThat(count("SELECT count(*) FROM device_session WHERE user_id = ?", studentUser))
			.isEqualTo(studentSessionsBefore);

		// Audit: actor = admin, target = the student profile, reason and subject recorded.
		Map<String, Object> audit = jdbcTemplate.queryForMap("SELECT actor_id, target_id, reason, metadata::text AS m "
				+ "FROM audit_log WHERE tenant_id = ? AND action = 'student.impersonation_started'", fx.tenant().getId());
		assertThat(audit.get("actor_id")).isEqualTo(fx.admin().getId());
		assertThat(audit.get("target_id")).isEqualTo(studentId);
		assertThat(audit.get("reason")).isEqualTo("Checking what the student sees on the course page");
		assertThat((String) audit.get("m")).contains(studentUser.toString()).contains(started.sessionId().toString());
		// The raw handoff secret is never stored.
		assertThat(count("SELECT count(*) FROM impersonation_session WHERE handoff_secret_hash = ?", started.cookie()))
			.isZero();

		// End: cookie cleared, token dead, audited.
		MvcResult ended = send(fx.host(), token, post("/api/v1/auth/impersonation/end"), null);
		assertThat(status(ended)).as(rawContent(ended)).isEqualTo(204);
		assertThat(ended.getResponse().getHeader(HttpHeaders.SET_COOKIE)).startsWith(COOKIE + "=;").contains("Max-Age=0");
		assertThat(endReason(started.sessionId())).isEqualTo("EXITED");
		assertThat(status(getAs(fx.host(), token, "/api/v1/students/me"))).isEqualTo(401);
		assertThat(status(exchange(fx, started.cookie()))).isEqualTo(401);
		assertThat(count("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'student.impersonation_ended' "
				+ "AND actor_id = ? AND target_id = ? AND metadata::text LIKE '%EXITED%'", fx.tenant().getId(),
				fx.admin().getId(), studentId)).isEqualTo(1);
		// The admin's own session is untouched.
		assertThat(status(getAs(fx.host(), fx.adminToken(), "/api/v1/students/directory"))).isEqualTo(200);
		// Ending again (cookie only) is a harmless no-op.
		MockHttpServletRequestBuilder again = post("/api/v1/auth/impersonation/end")
			.cookie(new Cookie(COOKIE, started.cookie()));
		assertThat(status(send(fx.host(), null, again, null))).isEqualTo(204);
	}

	@Test
	void onlyTenantAdminsMayStartAndInvalidTargetsAreRefused() {
		Fx fx = fixture("imp-perm");
		UUID studentId = createStudentId(fx, "Target", "Student");

		assertThat(status(startRaw(fx, staffToken(fx, Role.STUDENT_SUPPORT), studentId, "valid reason")))
			.isEqualTo(403);
		assertThat(status(startRaw(fx, loginAndGetToken(fx.host(), "teacher@example.test"), studentId,
				"valid reason"))).isEqualTo(403);
		assertThat(status(startRaw(fx, studentToken(fx, createStudentId(fx, "Other", "Kid")), studentId,
				"valid reason"))).isEqualTo(403);
		assertThat(status(startRaw(fx, null, studentId, "valid reason"))).isEqualTo(401);
		// Reason 5-500.
		assertThat(status(startRaw(fx, fx.adminToken(), studentId, "abc"))).isEqualTo(400);
		assertThat(status(startRaw(fx, fx.adminToken(), studentId, "x".repeat(501)))).isEqualTo(400);

		// Another tenant's student: 404.
		Fx other = fixture("imp-perm-other");
		UUID foreign = createStudentId(other, "Foreign", "Student");
		assertThat(status(startRaw(fx, fx.adminToken(), foreign, "valid reason"))).isEqualTo(404);
		assertThat(status(startRaw(fx, fx.adminToken(), UUID.randomUUID(), "valid reason"))).isEqualTo(404);

		// Suspended (inactive) student: refused.
		UUID suspended = createStudentId(fx, "Suspended", "Student");
		suspendTenantUser(userIdOf(suspended));
		MvcResult inactive = startRaw(fx, fx.adminToken(), suspended, "valid reason");
		assertThat(status(inactive)).isEqualTo(409);
		assertThat(error(inactive).path("code").asString()).isEqualTo("STUDENT_NOT_ACTIVE");
		// Pending student: refused.
		UUID pending = createStudentId(fx, "Pending", "Student");
		jdbcTemplate.update("UPDATE student_profile SET pending_approval = true WHERE id = ?", pending);
		assertThat(status(startRaw(fx, fx.adminToken(), pending, "valid reason"))).isEqualTo(409);
		// Removed student: 404.
		UUID removed = createStudentId(fx, "Removed", "Student");
		send(fx.host(), fx.adminToken(), delete("/api/v1/students/{id}", removed), Map.of("reason", "gone"));
		assertThat(status(startRaw(fx, fx.adminToken(), removed, "valid reason"))).isEqualTo(404);
		// A student profile attached to a non-student account: refused as not a student.
		TenantUser staff = seedTenantUser(fx.tenant().getId(), email("staffprofile"), RAW_PASSWORD, Role.FINANCE_STAFF);
		UUID oddProfile = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO student_profile (id, tenant_id, user_id, name, created_at, updated_at) "
				+ "VALUES (?, ?, ?, 'Not A Student', now(), now())", oddProfile, fx.tenant().getId(), staff.getId());
		assertThat(status(startRaw(fx, fx.adminToken(), oddProfile, "valid reason"))).isEqualTo(404);

		assertThat(count("SELECT count(*) FROM impersonation_session WHERE tenant_id = ?", fx.tenant().getId()))
			.isZero();
	}

	@Test
	void supportSessionsAreReadOnlyAndCannotNestOrRefresh() {
		Fx fx = fixture("imp-ro");
		UUID studentId = createStudentId(fx, "Read", "Only");
		UUID otherStudent = createStudentId(fx, "Second", "Student");
		Started started = start(fx, fx.adminToken(), studentId);
		String token = impersonationToken(fx, started);
		String passwordHash = jdbcTemplate.queryForObject("SELECT password_hash FROM tenant_user WHERE id = ?",
				String.class, userIdOf(studentId));

		for (MvcResult write : List.of(
				send(fx.host(), token, post("/api/v1/auth/password"),
						Map.of("currentPassword", RAW_PASSWORD, "newPassword", "Another-Password-123!")),
				send(fx.host(), token, put("/api/v1/students/me/billing-address"),
						Map.of("addressLine", "x", "city", "y")),
				send(fx.host(), token, post("/api/v1/enrollments/my/courses/{id}", fx.courseId()), null),
				send(fx.host(), token, post("/api/v1/auth/logout"), null),
				send(fx.host(), token, post("/api/v1/auth/refresh"), null),
				// Nested: a support session cannot start another one.
				send(fx.host(), token, post("/api/v1/students/{id}/impersonation", otherStudent),
						Map.of("reason", "nested attempt")))) {
			assertThat(status(write)).as(rawContent(write)).isEqualTo(403);
			assertThat(error(write).path("code").asString()).isEqualTo("IMPERSONATION_READ_ONLY");
		}
		assertThat(jdbcTemplate.queryForObject("SELECT password_hash FROM tenant_user WHERE id = ?", String.class,
				userIdOf(studentId))).isEqualTo(passwordHash);
		assertThat(count("SELECT count(*) FROM impersonation_session WHERE tenant_id = ?", fx.tenant().getId()))
			.isEqualTo(1);
		// The handoff secret is not a refresh token.
		MockHttpServletRequestBuilder refresh = post("/api/v1/auth/refresh")
			.cookie(new Cookie("lms_refresh_token", started.cookie()));
		assertThat(status(send(fx.host(), null, refresh, null))).isEqualTo(401);
		// The session is still usable for reads.
		assertThat(status(getAs(fx.host(), token, "/api/v1/students/me"))).isEqualTo(200);
		// Cross-tenant replay of the token or cookie fails.
		Fx other = fixture("imp-ro-other");
		assertThat(status(getAs(other.host(), token, "/api/v1/students/me"))).isEqualTo(401);
		assertThat(status(exchange(other, started.cookie()))).isEqualTo(401);
		assertThat(status(exchange(fx, null))).isEqualTo(401);
	}

	@Test
	void expiryActorLogoutActorSuspensionAndReplacementRevokeAccess() {
		Fx fx = fixture("imp-revoke");
		UUID first = createStudentId(fx, "First", "Target");
		UUID second = createStudentId(fx, "Second", "Target");

		// Absolute expiry.
		Started expiring = start(fx, fx.adminToken(), first);
		String expiringToken = impersonationToken(fx, expiring);
		jdbcTemplate.update("UPDATE impersonation_session SET started_at = now() - interval '2 hours', "
				+ "expires_at = now() - interval '1 hour' WHERE id = ?", expiring.sessionId());
		assertThat(status(getAs(fx.host(), expiringToken, "/api/v1/students/me"))).isEqualTo(401);
		assertThat(status(exchange(fx, expiring.cookie()))).isEqualTo(401);
		assertThat(endReason(expiring.sessionId())).isEqualTo("EXPIRED");

		// Replacement: a new start ends the previous session.
		Started a = start(fx, fx.adminToken(), first);
		String aToken = impersonationToken(fx, a);
		Started b = start(fx, fx.adminToken(), second);
		String bToken = impersonationToken(fx, b);
		assertThat(endReason(a.sessionId())).isEqualTo("REPLACED");
		assertThat(status(getAs(fx.host(), aToken, "/api/v1/students/me"))).isEqualTo(401);
		assertThat(status(exchange(fx, a.cookie()))).isEqualTo(401);
		assertThat(status(getAs(fx.host(), bToken, "/api/v1/students/me"))).isEqualTo(200);
		assertThat(count("SELECT count(*) FROM impersonation_session WHERE tenant_id = ? AND ended_at IS NULL",
				fx.tenant().getId())).isEqualTo(1);

		// Admin logout ends support access.
		assertThat(logout(fx.host(), fx.adminToken()).getStatusCode().value()).isEqualTo(200);
		assertThat(status(getAs(fx.host(), bToken, "/api/v1/students/me"))).isEqualTo(401);
		assertThat(endReason(b.sessionId())).isEqualTo("ACTOR_REVOKED");

		// Admin suspension ends support access.
		String adminToken = loginAndGetToken(fx.host(), "admin@example.test");
		Started c = start(fx, adminToken, second);
		String cToken = impersonationToken(fx, c);
		assertThat(status(getAs(fx.host(), cToken, "/api/v1/students/me"))).isEqualTo(200);
		suspendTenantUser(fx.admin().getId());
		assertThat(status(getAs(fx.host(), cToken, "/api/v1/students/me"))).isEqualTo(401);
		assertThat(status(exchange(fx, c.cookie()))).isEqualTo(401);
		assertThat(endReason(c.sessionId())).isEqualTo("ACTOR_REVOKED");

		// Every session row is kept.
		assertThat(count("SELECT count(*) FROM impersonation_session WHERE tenant_id = ?", fx.tenant().getId()))
			.isEqualTo(4);
		assertThat(count("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'student.impersonation_ended'",
				fx.tenant().getId())).isEqualTo(4);
	}

	@Test
	void encodedActivityPathsAreStillRefusedAndAdminDemotionEndsTheSession() {
		Fx fx = fixture("imp-sec");
		UUID studentId = createStudentId(fx, "Encoded", "Path");
		Started started = start(fx, fx.adminToken(), studentId);
		String token = impersonationToken(fx, started);

		// Security review M1: "%2D" decodes to "-" in Spring MVC routing, so the guard
		// must match the decoded path, not the raw URI.
		for (String path : List.of("download-url", "download%2Durl", "download%2durl")) {
			java.net.URI uri = java.net.URI.create("/api/v1/courses/" + UUID.randomUUID() + "/modules/"
					+ UUID.randomUUID() + "/lessons/" + UUID.randomUUID() + "/materials/" + UUID.randomUUID() + "/"
					+ path);
			MvcResult result = send(fx.host(), token, get(uri), null);
			assertThat(status(result)).as(path + " " + rawContent(result)).isEqualTo(403);
			assertThat(error(result).path("code").asString()).isEqualTo("IMPERSONATION_READ_ONLY");
		}

		// Matrix parameters never reach the handler: the HTTP firewall rejects them first.
		MvcResult semicolon = send(fx.host(), token, get(java.net.URI.create("/api/v1/courses/" + UUID.randomUUID()
				+ "/modules/" + UUID.randomUUID() + "/lessons/" + UUID.randomUUID() + "/materials/"
				+ UUID.randomUUID() + "/download-url;x=1")), null);
		assertThat(status(semicolon)).isEqualTo(400);

		// Security review L1: an admin demoted mid-session loses support access at once.
		assertThat(status(getAs(fx.host(), token, "/api/v1/students/me"))).isEqualTo(200);
		jdbcTemplate.update("UPDATE tenant_user SET role = 'STUDENT_SUPPORT' WHERE id = ?", fx.admin().getId());
		assertThat(status(getAs(fx.host(), token, "/api/v1/students/me"))).isEqualTo(401);
		assertThat(status(exchange(fx, started.cookie()))).isEqualTo(401);
		assertThat(endReason(started.sessionId())).isEqualTo("ACTOR_REVOKED");
	}

}
