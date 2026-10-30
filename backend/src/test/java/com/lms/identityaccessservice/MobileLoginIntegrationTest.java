package com.lms.identityaccessservice;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.web.dto.LoginResponse;
import com.lms.tenantmanagement.domain.Tenant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * ADR-031 (LOGIN-01 Phase C) over HTTP against Testcontainers Postgres +
 * Redis: mobile-number sign-in, the institute switch, per-tenant uniqueness,
 * throttling, the teacher/staff/own-account endpoints and the audit entry.
 * All people and numbers are invented.
 */
class MobileLoginIntegrationTest extends AuthIntegrationTestSupport {

	private static final ObjectMapper JSON = new ObjectMapper();

	private static final String LOGIN = "/api/v1/auth/login";

	private record Fixture(Tenant tenant, String host, String adminToken) {
	}

	// ------------------------------------------------------------------
	// Sign-in.
	// ------------------------------------------------------------------

	@Test
	void aRegisteredStudentSignsInWithTheirMobileInAnyCommonFormat() {
		Fixture t = newTenant("ml-formats");
		assertThat(status(register(t, uniqueEmail("ml-formats"), "077 123 4567"))).isEqualTo(HttpStatus.CREATED);

		for (String typed : List.of("0771234567", "771234567", "+94 77 123 4567", "+94771234567")) {
			MvcResult result = mobileLogin(t.host(), typed, "password123");
			assertThat(status(result)).as(typed).isEqualTo(HttpStatus.OK);
			assertThat(data(result).path("accessToken").asText()).isNotBlank();
		}
		assertThat(jdbcTemplate.queryForObject("SELECT login_mobile FROM tenant_user WHERE tenant_id = ? AND role = 'STUDENT'",
				String.class, t.tenant().getId())).isEqualTo("+94771234567");
	}

	@Test
	void wrongPasswordUnknownNumberAndGarbageAllGetTheSameInvalidCredentials() {
		Fixture t = newTenant("ml-invalid");
		register(t, uniqueEmail("ml-invalid"), "0771234567");

		for (String[] attempt : List.of(new String[] { "0771234567", "wrong-password" },
				new String[] { "0719999999", "password123" }, new String[] { "not-a-number", "password123" })) {
			MvcResult result = mobileLogin(t.host(), attempt[0], attempt[1]);
			assertThat(status(result)).as(attempt[0]).isEqualTo(HttpStatus.UNAUTHORIZED);
			assertThat(body(result).path("error").path("code").asText()).isEqualTo("INVALID_CREDENTIALS");
			assertThat(body(result).path("error").path("message").asText()).isEqualTo("Invalid mobile number or password");
		}
	}

	@Test
	void exactlyOneIdentifierIsRequired() {
		Fixture t = newTenant("ml-one-id");

		assertThat(status(call(HttpMethod.POST, LOGIN, t.host(), null,
				Map.of("email", "a@example.test", "mobile", "0771234567", "password", "password123"))))
			.isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(status(call(HttpMethod.POST, LOGIN, t.host(), null, Map.of("password", "password123"))))
			.isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void whenTheInstituteSwitchesMobileSignInOffItIsRefusedButEmailStillWorks() {
		Fixture t = newTenant("ml-disabled");
		String email = uniqueEmail("ml-disabled");
		register(t, email, "0771234567");
		assertThat(status(call(HttpMethod.PUT, "/api/v1/tenant-config/GENERAL", t.host(), t.adminToken(),
				Map.of("mobile_login_enabled", false)))).isEqualTo(HttpStatus.OK);

		MvcResult mobile = mobileLogin(t.host(), "0771234567", "password123");
		assertThat(status(mobile)).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(body(mobile).path("error").path("code").asText()).isEqualTo("MOBILE_LOGIN_DISABLED");
		assertThat(login(t.host(), email, "password123").getStatusCode()).isEqualTo(HttpStatus.OK);

		MvcResult home = call(HttpMethod.GET, "/api/v1/public/tenant-config/home", t.host(), null, null);
		assertThat(data(home).path("loginOptions").path("mobileEnabled").asBoolean()).isFalse();
	}

	@Test
	void aSuspendedAccountIsStillRefusedWhenSigningInByMobile() {
		Fixture t = newTenant("ml-suspended");
		register(t, uniqueEmail("ml-suspended"), "0771234567");
		UUID userId = jdbcTemplate.queryForObject("SELECT id FROM tenant_user WHERE tenant_id = ? AND role = 'STUDENT'",
				UUID.class, t.tenant().getId());
		suspendTenantUser(userId);

		MvcResult result = mobileLogin(t.host(), "0771234567", "password123");
		assertThat(status(result)).isEqualTo(HttpStatus.FORBIDDEN);
	}

	// ------------------------------------------------------------------
	// Throttling.
	// ------------------------------------------------------------------

	@Test
	void tenFailedAttemptsLockTheNumberEvenForTheRightPassword() {
		Fixture t = newTenant("ml-throttle");
		register(t, uniqueEmail("ml-throttle"), "0771234567");

		for (int i = 0; i < 10; i++) {
			assertThat(status(mobileLogin(t.host(), "0771234567", "wrong-" + i))).isEqualTo(HttpStatus.UNAUTHORIZED);
		}
		MvcResult locked = mobileLogin(t.host(), "+94 77 123 4567", "password123");
		assertThat(status(locked)).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
		assertThat(body(locked).path("error").path("code").asText()).isEqualTo("TOO_MANY_REQUESTS");
	}

	@Test
	void emailSignInIsThrottledToo() {
		Fixture t = newTenant("ml-throttle-email");
		String email = uniqueEmail("ml-throttle-email");
		seedTenantUser(t.tenant().getId(), email, RAW_PASSWORD, Role.TENANT_ADMIN);

		for (int i = 0; i < 10; i++) {
			assertThat(login(t.host(), email, "wrong-" + i).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		}
		assertThat(login(t.host(), email, RAW_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
	}

	@Test
	void aSuccessfulSignInClearsTheFailureCount() {
		Fixture t = newTenant("ml-throttle-reset");
		register(t, uniqueEmail("ml-throttle-reset"), "0771234567");

		for (int i = 0; i < 9; i++) {
			mobileLogin(t.host(), "0771234567", "wrong-" + i);
		}
		assertThat(status(mobileLogin(t.host(), "0771234567", "password123"))).isEqualTo(HttpStatus.OK);
		for (int i = 0; i < 9; i++) {
			mobileLogin(t.host(), "0771234567", "wrong-again-" + i);
		}
		assertThat(status(mobileLogin(t.host(), "0771234567", "password123"))).isEqualTo(HttpStatus.OK);
	}

	// ------------------------------------------------------------------
	// Uniqueness and cross-tenant isolation.
	// ------------------------------------------------------------------

	@Test
	void aSecondRegistrationWithTheSameNumberIsRefusedAndCreatesNothing() {
		Fixture t = newTenant("ml-dup");
		register(t, uniqueEmail("ml-dup-1"), "0771234567");
		String secondEmail = uniqueEmail("ml-dup-2");

		MvcResult second = register(t, secondEmail, "+94 77 123 4567");

		assertThat(status(second)).isEqualTo(HttpStatus.CONFLICT);
		assertThat(body(second).path("error").path("code").asText()).isEqualTo("MOBILE_ALREADY_REGISTERED");
		assertThat(fieldErrors(second)).contains("profileFields.MOBILE");
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM tenant_user WHERE tenant_id = ? AND email = ?",
				Long.class, t.tenant().getId(), secondEmail)).isZero();
	}

	@Test
	void anInvalidRegistrationMobileIsAFieldError() {
		Fixture t = newTenant("ml-bad-reg");

		MvcResult result = register(t, uniqueEmail("ml-bad-reg"), "12345");

		assertThat(status(result)).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(fieldErrors(result)).contains("profileFields.MOBILE");
	}

	@Test
	@Tag("cross-tenant")
	void aNumberRegisteredInOneInstituteNeverSignsInAtAnother() {
		Fixture a = newTenant("ml-cross-a");
		Fixture b = newTenant("ml-cross-b");
		register(a, uniqueEmail("ml-cross-a"), "0771234567");

		assertThat(status(mobileLogin(b.host(), "0771234567", "password123"))).isEqualTo(HttpStatus.UNAUTHORIZED);
		// The same number may belong to one user in each institute.
		assertThat(status(register(b, uniqueEmail("ml-cross-b"), "0771234567"))).isEqualTo(HttpStatus.CREATED);
		assertThat(status(mobileLogin(b.host(), "0771234567", "password123"))).isEqualTo(HttpStatus.OK);
		assertThat(status(mobileLogin(a.host(), "0771234567", "password123"))).isEqualTo(HttpStatus.OK);
	}

	@Test
	void changingTheProfileMobileMovesTheSignInMobileAndIsAudited() {
		Fixture t = newTenant("ml-profile");
		String email = uniqueEmail("ml-profile");
		register(t, email, "0771234567");
		String token = token(t.host(), email, "password123");

		MvcResult update = call(HttpMethod.PUT, "/api/v1/students/me/profile", t.host(), token,
				Map.of("values", Map.of("MOBILE", "0719876543")));
		assertThat(status(update)).isEqualTo(HttpStatus.OK);

		assertThat(status(mobileLogin(t.host(), "0719876543", "password123"))).isEqualTo(HttpStatus.OK);
		assertThat(status(mobileLogin(t.host(), "0771234567", "password123"))).isEqualTo(HttpStatus.UNAUTHORIZED);
		List<String> audits = jdbcTemplate.queryForList(
				"SELECT metadata::text FROM audit_log WHERE tenant_id = ? AND action = 'identity.login_mobile_changed' ORDER BY occurred_at",
				String.class, t.tenant().getId());
		assertThat(audits).hasSize(2);
		assertThat(audits.get(1)).contains("***567").contains("***543").doesNotContain("0719876543")
			.doesNotContain("+94719876543");
	}

	// ------------------------------------------------------------------
	// Teachers, staff and the own-account endpoint.
	// ------------------------------------------------------------------

	@Test
	void anAdminSetsAStaffMembersSignInMobileAndATakenNumberIsRefused() {
		Fixture t = newTenant("ml-staff");
		register(t, uniqueEmail("ml-staff-student"), "0771234567");
		String staffEmail = uniqueEmail("ml-staff");
		MvcResult created = call(HttpMethod.POST, "/api/v1/staff", t.host(), t.adminToken(),
				Map.of("name", "Finance Person", "email", staffEmail, "password", RAW_PASSWORD, "roleCode", "FINANCE_STAFF"));
		String staffId = data(created).path("id").asText();
		String path = "/api/v1/staff/" + staffId + "/login-mobile";

		MvcResult taken = call(HttpMethod.PUT, path, t.host(), t.adminToken(), Map.of("loginMobile", "0771234567"));
		assertThat(status(taken)).isEqualTo(HttpStatus.CONFLICT);
		assertThat(fieldErrors(taken)).contains("loginMobile");

		MvcResult set = call(HttpMethod.PUT, path, t.host(), t.adminToken(), Map.of("loginMobile", "070 555 1234"));
		assertThat(data(set).path("loginMobile").asText()).isEqualTo("+94705551234");
		assertThat(data(call(HttpMethod.GET, path, t.host(), t.adminToken(), null)).path("loginMobile").asText())
			.isEqualTo("+94705551234");
		markOnboarded(staffEmail);
		assertThat(status(mobileLogin(t.host(), "0705551234", RAW_PASSWORD))).isEqualTo(HttpStatus.OK);

		Map<String, Object> clear = new LinkedHashMap<>();
		clear.put("loginMobile", null);
		assertThat(data(call(HttpMethod.PUT, path, t.host(), t.adminToken(), clear)).path("loginMobile").isNull())
			.isTrue();
		assertThat(status(mobileLogin(t.host(), "0705551234", RAW_PASSWORD))).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	@Tag("cross-tenant")
	void anotherInstitutesStaffOrTeacherIdIsNotFound() {
		Fixture a = newTenant("ml-ids-a");
		Fixture b = newTenant("ml-ids-b");
		MvcResult created = call(HttpMethod.POST, "/api/v1/staff", a.host(), a.adminToken(), Map.of("name", "A Staff",
				"email", uniqueEmail("ml-ids"), "password", RAW_PASSWORD, "roleCode", "FINANCE_STAFF"));
		String staffId = data(created).path("id").asText();

		assertThat(status(call(HttpMethod.PUT, "/api/v1/staff/" + staffId + "/login-mobile", b.host(), b.adminToken(),
				Map.of("loginMobile", "0705551234")))).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(status(call(HttpMethod.GET, "/api/v1/teachers/" + UUID.randomUUID() + "/login-mobile", b.host(),
				b.adminToken(), null))).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void staffWithoutStaffPermissionCannotSetSomeoneElsesNumber() {
		Fixture t = newTenant("ml-perm");
		String financeEmail = uniqueEmail("ml-perm-fin");
		seedTenantUser(t.tenant().getId(), financeEmail, RAW_PASSWORD, Role.FINANCE_STAFF);
		String financeToken = token(t.host(), financeEmail, RAW_PASSWORD);
		MvcResult created = call(HttpMethod.POST, "/api/v1/staff", t.host(), t.adminToken(), Map.of("name", "Other",
				"email", uniqueEmail("ml-perm-other"), "password", RAW_PASSWORD, "roleCode", "EXAM_MANAGER"));

		assertThat(status(call(HttpMethod.PUT, "/api/v1/staff/" + data(created).path("id").asText() + "/login-mobile",
				t.host(), financeToken, Map.of("loginMobile", "0705551234")))).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void aTenantAdminSetsTheirOwnNumberButAStudentCannotUseThatEndpoint() {
		Fixture t = newTenant("ml-me");
		MvcResult own = call(HttpMethod.PUT, "/api/v1/auth/me/login-mobile", t.host(), t.adminToken(),
				Map.of("loginMobile", "0766666666"));
		assertThat(data(own).path("loginMobile").asText()).isEqualTo("+94766666666");
		assertThat(status(mobileLogin(t.host(), "0766666666", RAW_PASSWORD))).isEqualTo(HttpStatus.OK);

		String studentEmail = uniqueEmail("ml-me-student");
		register(t, studentEmail, "0771234567");
		String studentToken = token(t.host(), studentEmail, "password123");
		assertThat(status(call(HttpMethod.PUT, "/api/v1/auth/me/login-mobile", t.host(), studentToken,
				Map.of("loginMobile", "0712222222")))).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(status(call(HttpMethod.GET, "/api/v1/auth/me/login-mobile", t.host(), null, null)))
			.isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	// ------------------------------------------------------------------
	// Helpers.
	// ------------------------------------------------------------------

	private Fixture newTenant(String prefix) {
		Tenant tenant = seedActiveTenant(uniqueSubdomain(prefix));
		String adminEmail = uniqueEmail(prefix + "-admin");
		seedTenantUser(tenant.getId(), adminEmail, RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		Fixture fixture = new Fixture(tenant, host, token(host, adminEmail, RAW_PASSWORD));
		// Collect the mobile at registration (MOBILE is optional by default).
		MvcResult settings = call(HttpMethod.PUT, "/api/v1/tenant-config/student-profile-fields", host,
				fixture.adminToken(), Map.of("fields", List.of(Map.of("key", "MOBILE", "enabled", true, "required", false,
						"collectAtRegistration", true))));
		assertThat(status(settings)).isEqualTo(HttpStatus.OK);
		return fixture;
	}

	private MvcResult register(Fixture t, String email, String mobile) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("name", "Registering Student");
		body.put("email", email);
		body.put("password", "password123");
		body.put("profileFields", Map.of("MOBILE", mobile));
		return call(HttpMethod.POST, "/api/v1/students/register", t.host(), null, body);
	}

	private MvcResult mobileLogin(String host, String mobile, String password) {
		return call(HttpMethod.POST, LOGIN, host, null, Map.of("mobile", mobile, "password", password));
	}

	private String token(String host, String email, String password) {
		markOnboarded(email);
		HttpResult<LoginResponse> login = login(host, email, password);
		assertThat(login.getStatusCode()).as("login " + email).isEqualTo(HttpStatus.OK);
		return login.getBody().data().accessToken();
	}

	private static String uniqueEmail(String prefix) {
		return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
	}

	private MvcResult call(HttpMethod method, String path, String host, String token, Object body) {
		try {
			MockHttpServletRequestBuilder request = MockMvcRequestBuilders.request(method, path)
				.header(HttpHeaders.HOST, host);
			if (token != null) {
				request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
			}
			if (body != null) {
				request.contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body));
			}
			return mockMvc.perform(request).andReturn();
		}
		catch (Exception e) {
			throw new IllegalStateException("MockMvc request failed", e);
		}
	}

	private static HttpStatus status(MvcResult result) {
		return HttpStatus.valueOf(result.getResponse().getStatus());
	}

	private static JsonNode body(MvcResult result) {
		try {
			return JSON.readTree(result.getResponse().getContentAsString());
		}
		catch (Exception e) {
			throw new IllegalStateException("Response is not JSON", e);
		}
	}

	private static JsonNode data(MvcResult result) {
		assertThat(status(result).is2xxSuccessful()).as("expected 2xx, got %s: %s", status(result), body(result))
			.isTrue();
		return body(result).path("data");
	}

	private static List<String> fieldErrors(MvcResult result) {
		List<String> fields = new ArrayList<>();
		body(result).path("error").path("fieldErrors").forEach(error -> fields.add(error.path("field").asText()));
		return fields;
	}

}
