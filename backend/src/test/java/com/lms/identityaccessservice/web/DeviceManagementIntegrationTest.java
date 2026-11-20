package com.lms.identityaccessservice.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.lms.coursemanagement.CourseManagementTestSupport;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.api.DeviceLimitSource;
import com.lms.identityaccessservice.api.DevicePolicyView;
import com.lms.identityaccessservice.api.DeviceSlotSummary;
import com.lms.identityaccessservice.api.DeviceView;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.identityaccessservice.service.DeviceTokens;
import com.lms.identityaccessservice.web.dto.LoginResponse;
import com.lms.identityaccessservice.web.dto.RefreshResponse;
import com.lms.tenantmanagement.domain.Tenant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

/**
 * Wave 10 device controls, end to end through the real filter chain:
 * server-issued device identity, the login-time limit and its precedence,
 * refresh bound to the device, admin reset + persisted cooldown, student
 * self-service, permissions, audit rows, and cross-tenant isolation.
 */
class DeviceManagementIntegrationTest extends CourseManagementTestSupport {

	private record Fixture(Tenant tenant, String host, String adminToken, TenantUser admin, TenantUser student,
			UUID studentProfileId) {
	}

	private record Session(HttpStatus status, String accessToken, String deviceToken, String refreshToken,
			String errorCode) {
	}

	// ---- registration & identity ----

	@Test
	void loginRegistersAServerIssuedDeviceAndReusesItWhenTheCookieIsPresentedAgain() {
		Fixture f = fixture("dev-reg");

		HttpResult<LoginResponse> first = loginRaw(f.host(), "student@example.test", null);
		assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
		String setCookie = first.getHeaders()
			.get(HttpHeaders.SET_COOKIE)
			.stream()
			.filter(header -> header.startsWith(DEVICE_COOKIE + "="))
			.findFirst()
			.orElseThrow();
		assertThat(setCookie).contains("HttpOnly").contains("SameSite=Strict").contains("Path=/api/v1/auth");
		String deviceToken = extractCookieValue(first, DEVICE_COOKIE);

		// The stored identifier is the hash of the server-issued token - not a User-Agent/IP fingerprint.
		Map<String, Object> session = jdbcTemplate.queryForMap(
				"SELECT device_id, device_identifier_hash FROM device_session WHERE id = ?",
				first.getBody().data().sessionId());
		assertThat(session.get("device_id")).isNotNull();
		assertThat(session.get("device_identifier_hash")).isEqualTo(DeviceTokens.hash(deviceToken));
		assertThat(deviceCount(f)).isEqualTo(1);

		assertThat(signIn(f.host(), "student@example.test", deviceToken).status()).isEqualTo(HttpStatus.OK);
		assertThat(deviceCount(f)).isEqualTo(1);

		assertThat(signIn(f.host(), "student@example.test", null).status()).isEqualTo(HttpStatus.OK);
		assertThat(deviceCount(f)).isEqualTo(2);
		// The device row itself is the registration record (no separate audit row per sign-in).
		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM device WHERE tenant_id = ? AND user_id = ? AND label IS NOT NULL "
						+ "AND first_seen_at IS NOT NULL",
				Integer.class, f.tenant().getId(), f.student().getId()))
			.isEqualTo(2);
	}

	// ---- limit + precedence ----

	@Test
	void aNewDeviceBeyondTheTenantLimitIsRejectedButKnownDevicesStillSignIn() {
		Fixture f = fixture("dev-limit");
		setPolicy(f, 2, 24, false);
		Session d1 = signIn(f.host(), "student@example.test", null);
		Session d2 = signIn(f.host(), "student@example.test", null);
		assertThat(d1.status()).isEqualTo(HttpStatus.OK);
		assertThat(d2.status()).isEqualTo(HttpStatus.OK);
		int sessionsBefore = sessionCount(f);

		Session d3 = signIn(f.host(), "student@example.test", null);

		assertThat(d3.status()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(d3.errorCode()).isEqualTo("DEVICE_LIMIT_REACHED");
		assertThat(deviceCount(f)).isEqualTo(2);
		assertThat(sessionCount(f)).isEqualTo(sessionsBefore);
		assertThat(signIn(f.host(), "student@example.test", d1.deviceToken()).status()).isEqualTo(HttpStatus.OK);
	}

	@Test
	void aWrongPasswordNeverRevealsTheDeviceLimit() {
		Fixture f = fixture("dev-enum");
		setPolicy(f, 1, 24, false);
		signIn(f.host(), "student@example.test", null);

		HttpResult<LoginResponse> wrong = login(f.host(), "student@example.test", WRONG_PASSWORD);

		assertThat(wrong.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(wrong.getBody().error().code()).isEqualTo("INVALID_CREDENTIALS");
	}

	@Test
	void aStudentExceptionOutranksTheTenantLimit() {
		Fixture f = fixture("dev-prec");
		setPolicy(f, 1, 24, false);
		signIn(f.host(), "student@example.test", null);
		assertThat(signIn(f.host(), "student@example.test", null).status()).isEqualTo(HttpStatus.FORBIDDEN);

		HttpResult<DeviceSlotSummary> set = setStudentLimit(f.host(), f.adminToken(), f.studentProfileId(), 2,
				"Two-device exception approved by support");
		assertThat(set.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(set.getBody().data().limit()).isEqualTo(2);
		assertThat(set.getBody().data().limitSource()).isEqualTo(DeviceLimitSource.STUDENT);

		assertThat(signIn(f.host(), "student@example.test", null).status()).isEqualTo(HttpStatus.OK);
		assertThat(signIn(f.host(), "student@example.test", null).status()).isEqualTo(HttpStatus.FORBIDDEN);

		// Clearing the exception falls back to the tenant limit.
		HttpResult<DeviceSlotSummary> cleared = setStudentLimit(f.host(), f.adminToken(), f.studentProfileId(), null,
				"Exception no longer needed");
		assertThat(cleared.getBody().data().limit()).isEqualTo(1);
		assertThat(cleared.getBody().data().limitSource()).isEqualTo(DeviceLimitSource.TENANT);
		assertThat(auditCount(f.tenant().getId(), "device_limit_override.set")).isEqualTo(1);
		assertThat(auditCount(f.tenant().getId(), "device_limit_override.cleared")).isEqualTo(1);
	}

	@Test
	void staffAccountsAreRegisteredButNotDeviceLimited() {
		Fixture f = fixture("dev-staff");
		setPolicy(f, 1, 24, false);

		for (int i = 0; i < 3; i++) {
			assertThat(signIn(f.host(), "admin@example.test", null).status()).isEqualTo(HttpStatus.OK);
		}
	}

	@Test
	void concurrentLoginsFromNewDevicesCannotTakeTheSameLastSlot() throws Exception {
		Fixture f = fixture("dev-race");
		setPolicy(f, 1, 24, false);
		markOnboarded("student@example.test");
		ExecutorService pool = Executors.newFixedThreadPool(4);
		try {
			List<Callable<HttpStatus>> attempts = new ArrayList<>();
			for (int i = 0; i < 4; i++) {
				attempts.add(() -> login(f.host(), "student@example.test", RAW_PASSWORD).getStatusCode());
			}
			List<HttpStatus> results = new ArrayList<>();
			for (Future<HttpStatus> future : pool.invokeAll(attempts)) {
				results.add(future.get());
			}
			assertThat(results).filteredOn(status -> status == HttpStatus.OK).hasSize(1);
			assertThat(results).filteredOn(status -> status == HttpStatus.FORBIDDEN).hasSize(3);
			assertThat(deviceCount(f)).isEqualTo(1);
		}
		finally {
			pool.shutdownNow();
		}
	}

	// ---- refresh is bound to the device ----

	@Test
	void refreshNeedsTheSessionsOwnDeviceCookie() {
		Fixture f = fixture("dev-refresh");
		Session d1 = signIn(f.host(), "student@example.test", null);
		Session d2 = signIn(f.host(), "student@example.test", null);

		assertThat(refresh(f.host(), "lms_refresh_token", d1.refreshToken()).getStatusCode())
			.isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(refresh(f.host(), "lms_refresh_token", d1.refreshToken(), d2.deviceToken()).getStatusCode())
			.isEqualTo(HttpStatus.UNAUTHORIZED);
		HttpResult<RefreshResponse> ok = refresh(f.host(), "lms_refresh_token", d1.refreshToken(), d1.deviceToken());
		assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	void aSessionIssuedBeforeDevicesExistedIsRefusedAtRefresh() {
		Fixture f = fixture("dev-legacy");
		Session s = signIn(f.host(), "student@example.test", null);
		jdbcTemplate.update("UPDATE device_session SET device_id = NULL WHERE tenant_id = ?", f.tenant().getId());

		assertThat(refresh(f.host(), "lms_refresh_token", s.refreshToken(), s.deviceToken()).getStatusCode())
			.isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	// ---- admin reset + cooldown ----

	@Test
	void anAdminResetSignsTheDeviceOutAndItsSlotStaysTakenForTheCooldown() {
		Fixture f = fixture("dev-reset");
		setPolicy(f, 1, 24, false);
		Session d1 = signIn(f.host(), "student@example.test", null);
		assertThat(myDevices(f.host(), d1.accessToken()).getStatusCode()).isEqualTo(HttpStatus.OK);
		UUID deviceId = myDevices(f.host(), d1.accessToken()).getBody().data().devices().get(0).id();

		HttpResult<DeviceView> reset = resetDevice(f.host(), f.adminToken(), f.studentProfileId(), deviceId,
				"Student lost the phone");

		assertThat(reset.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(reset.getBody().data().status()).isEqualTo("REVOKED");
		assertThat(reset.getBody().data().slotReusableAt()).isNotNull();
		// The revoked device's still-unexpired access token no longer works.
		assertThat(myDevices(f.host(), d1.accessToken()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(refresh(f.host(), "lms_refresh_token", d1.refreshToken(), d1.deviceToken()).getStatusCode())
			.isEqualTo(HttpStatus.UNAUTHORIZED);
		// Cooldown: the freed slot cannot be taken by a new device yet - nor by the revoked one.
		Session blocked = signIn(f.host(), "student@example.test", null);
		assertThat(blocked.status()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(blocked.errorCode()).isEqualTo("DEVICE_LIMIT_REACHED");
		assertThat(signIn(f.host(), "student@example.test", d1.deviceToken()).status())
			.isEqualTo(HttpStatus.FORBIDDEN);

		jdbcTemplate.update("UPDATE device SET slot_reusable_at = now() - interval '1 minute' WHERE id = ?", deviceId);
		assertThat(signIn(f.host(), "student@example.test", null).status()).isEqualTo(HttpStatus.OK);

		assertThat(auditCount(f.tenant().getId(), "device.reset")).isEqualTo(1);
		assertThat(auditCount(f.tenant().getId(), "student.device_reset")).isEqualTo(1);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM device WHERE id = ?", Integer.class, deviceId))
			.as("revoked devices are kept, never deleted")
			.isEqualTo(1);
	}

	@Test
	void resetAllRevokesEveryActiveDeviceAndRequiresAReason() {
		Fixture f = fixture("dev-reset-all");
		signIn(f.host(), "student@example.test", null);
		signIn(f.host(), "student@example.test", null);

		HttpResult<Map> noReason = postJson(f.host(), f.adminToken(),
				"/api/v1/students/" + f.studentProfileId() + "/devices/reset", Map.of("reason", " "), Map.class);
		assertThat(noReason.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

		HttpResult<Map> all = postJson(f.host(), f.adminToken(),
				"/api/v1/students/" + f.studentProfileId() + "/devices/reset", Map.of("reason", "Account shared"),
				Map.class);
		assertThat(all.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(all.getBody().data().get("revokedCount")).isEqualTo(2);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM device WHERE tenant_id = ? AND user_id = ? AND status = 'active'", Integer.class,
				f.tenant().getId(), f.student().getId()))
			.isZero();
	}

	// ---- permissions ----

	@Test
	void studentSupportMayViewButNotResetAndTeachersAndStudentsMayDoNeither() {
		Fixture f = fixture("dev-perm");
		seedTenantUser(f.tenant().getId(), "support@example.test", RAW_PASSWORD, Role.STUDENT_SUPPORT);
		seedTenantUser(f.tenant().getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		Session student = signIn(f.host(), "student@example.test", null);
		UUID deviceId = myDevices(f.host(), student.accessToken()).getBody().data().devices().get(0).id();
		String support = loginAndGetToken(f.host(), "support@example.test");
		String teacher = loginAndGetToken(f.host(), "teacher@example.test");

		assertThat(studentDevices(f.host(), support, f.studentProfileId()).getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(resetDevice(f.host(), support, f.studentProfileId(), deviceId, "x").getStatusCode())
			.isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(studentDevices(f.host(), teacher, f.studentProfileId()).getStatusCode())
			.isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(studentDevices(f.host(), student.accessToken(), f.studentProfileId()).getStatusCode())
			.isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(resetDevice(f.host(), student.accessToken(), f.studentProfileId(), deviceId, "x").getStatusCode())
			.isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(putJson(f.host(), support, "/api/v1/device-policy",
				Map.of("maxDevices", 5, "resetCooldownHours", 0, "studentSelfServiceEnabled", true),
				DevicePolicyView.class)
			.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void tenantAdminConfiguresTheDevicePolicyWithValidationAndAudit() {
		Fixture f = fixture("dev-policy");

		HttpResult<DevicePolicyView> defaults = getJson(f.host(), f.adminToken(), "/api/v1/device-policy",
				DevicePolicyView.class);
		assertThat(defaults.getBody().data().maxDevices()).isNull();
		assertThat(defaults.getBody().data().resetCooldownHours()).isEqualTo(24);

		assertThat(putJson(f.host(), f.adminToken(), "/api/v1/device-policy",
				Map.of("maxDevices", 0, "resetCooldownHours", 24, "studentSelfServiceEnabled", false),
				DevicePolicyView.class)
			.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

		HttpResult<DevicePolicyView> saved = putJson(f.host(), f.adminToken(), "/api/v1/device-policy",
				Map.of("maxDevices", 3, "resetCooldownHours", 12, "studentSelfServiceEnabled", true),
				DevicePolicyView.class);
		assertThat(saved.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(saved.getBody().data().effectiveMaxDevices()).isEqualTo(3);
		assertThat(auditCount(f.tenant().getId(), "device_policy.updated")).isEqualTo(1);
	}

	// ---- student self-service ----

	@Test
	void aStudentRemovesAnOtherDeviceOnlyWhenTheTenantAllowsIt() {
		Fixture f = fixture("dev-self");
		Session d1 = signIn(f.host(), "student@example.test", null);
		Session d2 = signIn(f.host(), "student@example.test", null);
		DeviceSlotSummary mine = myDevices(f.host(), d1.accessToken()).getBody().data();
		UUID current = mine.devices().stream().filter(DeviceView::current).findFirst().orElseThrow().id();
		UUID other = mine.devices().stream().filter(d -> !d.current()).findFirst().orElseThrow().id();
		assertThat(mine.selfServiceEnabled()).isFalse();

		HttpResult<Map> disabled = revokeMine(f.host(), d1.accessToken(), other);
		assertThat(disabled.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(disabled.getBody().error().code()).isEqualTo("DEVICE_SELF_SERVICE_DISABLED");

		setPolicy(f, 2, 24, true);
		assertThat(revokeMine(f.host(), d1.accessToken(), current).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(revokeMine(f.host(), d1.accessToken(), other).getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(myDevices(f.host(), d2.accessToken()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		// The cooldown applies to self-service removal too.
		assertThat(signIn(f.host(), "student@example.test", null).status()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(auditCount(f.tenant().getId(), "device.revoked_by_user")).isEqualTo(1);
	}

	@Test
	void aStudentCannotRemoveAnotherStudentsDevice() {
		Fixture f = fixture("dev-other");
		setPolicy(f, 2, 24, true);
		seedActiveStudent(f.tenant().getId(), "other@example.test");
		Session other = signIn(f.host(), "other@example.test", null);
		UUID otherDevice = myDevices(f.host(), other.accessToken()).getBody().data().devices().get(0).id();
		Session me = signIn(f.host(), "student@example.test", null);

		assertThat(revokeMine(f.host(), me.accessToken(), otherDevice).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(myDevices(f.host(), other.accessToken()).getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	// ---- cross-tenant ----

	@Test
	void anotherTenantCannotSeeOrResetDevicesByIdAndGetsNotFound() {
		Fixture a = fixture("dev-xa");
		Fixture b = fixture("dev-xb");
		setPolicy(b, 2, 24, true);
		Session aStudent = signIn(a.host(), "student@example.test", null);
		UUID aDevice = myDevices(a.host(), aStudent.accessToken()).getBody().data().devices().get(0).id();
		Session bStudent = signIn(b.host(), "student@example.test", null);

		assertThat(studentDevices(b.host(), b.adminToken(), a.studentProfileId()).getStatusCode())
			.isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(resetDevice(b.host(), b.adminToken(), a.studentProfileId(), aDevice, "x").getStatusCode())
			.isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(resetDevice(b.host(), b.adminToken(), b.studentProfileId(), aDevice, "x").getStatusCode())
			.isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(revokeMine(b.host(), bStudent.accessToken(), aDevice).getStatusCode())
			.isEqualTo(HttpStatus.NOT_FOUND);
		// Tenant A's token is useless against tenant B's host.
		assertThat(myDevices(b.host(), aStudent.accessToken()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		// And tenant A's device is untouched.
		assertThat(myDevices(a.host(), aStudent.accessToken()).getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	// ---- helpers ----

	private Fixture fixture(String prefix) {
		Tenant tenant = seedActiveTenant(uniqueSubdomain(prefix));
		TenantUser admin = seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser student = seedActiveStudent(tenant.getId(), "student@example.test");
		UUID profileId = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO student_profile (id, tenant_id, user_id, name, created_at, updated_at) "
				+ "VALUES (?, ?, ?, 'Nimal Silva', now(), now())", profileId, tenant.getId(), student.getId());
		String host = hostFor(tenant.getSubdomain());
		return new Fixture(tenant, host, loginAndGetToken(host, "admin@example.test"), admin, student, profileId);
	}

	private void setPolicy(Fixture f, Integer maxDevices, int cooldownHours, boolean selfService) {
		jdbcTemplate.update("INSERT INTO tenant_device_policy (id, tenant_id, max_devices, reset_cooldown_hours, "
				+ "student_self_service_enabled, updated_by, updated_at) VALUES (?, ?, ?, ?, ?, ?, now()) "
				+ "ON CONFLICT (tenant_id) DO UPDATE SET max_devices = EXCLUDED.max_devices, "
				+ "reset_cooldown_hours = EXCLUDED.reset_cooldown_hours, "
				+ "student_self_service_enabled = EXCLUDED.student_self_service_enabled", UUID.randomUUID(),
				f.tenant().getId(), maxDevices, cooldownHours, selfService, f.admin().getId());
	}

	private HttpResult<LoginResponse> loginRaw(String host, String email, String deviceToken) {
		markOnboarded(email);
		return login(host, email, RAW_PASSWORD, deviceToken);
	}

	private Session signIn(String host, String email, String deviceToken) {
		HttpResult<LoginResponse> result = loginRaw(host, email, deviceToken);
		if (result.getStatusCode() != HttpStatus.OK) {
			return new Session(result.getStatusCode(), null, null, null, result.getBody().error().code());
		}
		return new Session(HttpStatus.OK, result.getBody().data().accessToken(),
				extractCookieValue(result, DEVICE_COOKIE), extractCookieValue(result, "lms_refresh_token"), null);
	}

	private int deviceCount(Fixture f) {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM device WHERE tenant_id = ? AND user_id = ?",
				Integer.class, f.tenant().getId(), f.student().getId());
	}

	private int sessionCount(Fixture f) {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM device_session WHERE tenant_id = ? AND user_id = ?",
				Integer.class, f.tenant().getId(), f.student().getId());
	}

	private int auditCount(UUID tenantId, String action) {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = ?",
				Integer.class, tenantId, action);
	}

	private HttpResult<DeviceSlotSummary> myDevices(String host, String token) {
		return getJson(host, token, "/api/v1/devices/my", DeviceSlotSummary.class);
	}

	private HttpResult<Map> revokeMine(String host, String token, UUID deviceId) {
		return postJson(host, token, "/api/v1/devices/my/" + deviceId + "/revoke", Map.of(), Map.class);
	}

	private HttpResult<DeviceSlotSummary> studentDevices(String host, String token, UUID profileId) {
		return getJson(host, token, "/api/v1/students/" + profileId + "/devices", DeviceSlotSummary.class);
	}

	private HttpResult<DeviceView> resetDevice(String host, String token, UUID profileId, UUID deviceId,
			String reason) {
		return postJson(host, token, "/api/v1/students/" + profileId + "/devices/" + deviceId + "/reset",
				Map.of("reason", reason), DeviceView.class);
	}

	private HttpResult<DeviceSlotSummary> setStudentLimit(String host, String token, UUID profileId,
			Integer maxDevices, String reason) {
		Map<String, Object> body = new java.util.HashMap<>();
		body.put("maxDevices", maxDevices);
		body.put("reason", reason);
		return putJson(host, token, "/api/v1/students/" + profileId + "/device-limit", body, DeviceSlotSummary.class);
	}

	private <T> HttpResult<T> getJson(String host, String token, String path, Class<T> type) {
		return parseSingle(perform(authenticated(get(path), host, token)), type);
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
