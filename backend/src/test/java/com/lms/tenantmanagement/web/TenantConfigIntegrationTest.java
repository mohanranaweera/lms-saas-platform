package com.lms.tenantmanagement.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.lms.common.api.ApiResponse;
import com.lms.common.api.FieldError;
import com.lms.identityaccessservice.AuthIntegrationTestSupport;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.web.dto.LoginResponse;
import com.lms.tenantmanagement.domain.Tenant;
import com.lms.tenantmanagement.web.dto.ConfigPropertyResponse;
import com.lms.tenantmanagement.web.dto.PublicBrandingResponse;
import com.lms.tenantmanagement.web.dto.PublicStudentRegistrationPolicyResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JavaType;

/**
 * Testcontainers-backed coverage for Wave 1's typed tenant configuration
 * endpoints: {@code GET /api/v1/tenant-config/{domain}}, {@code PUT
 * /api/v1/tenant-config/{domain}}, and the anonymous {@code GET
 * /api/v1/public/tenant-config/branding}. Modeled directly on {@code
 * com.lms.usermanagement.staff.web.StaffManagementIntegrationTest} for the
 * MockMvc-through-the-real-filter-chain technique.
 */
class TenantConfigIntegrationTest extends AuthIntegrationTestSupport {

	private static final String TENANT_CONFIG_PATH = "/api/v1/tenant-config";

	private static final String PUBLIC_BRANDING_PATH = "/api/v1/public/tenant-config/branding";

	private static final String PUBLIC_STUDENT_REGISTRATION_POLICY_PATH =
			"/api/v1/public/tenant-config/student-registration-policy";

	// ------------------------------------------------------------------
	// Mandatory cross-tenant tests.
	// ------------------------------------------------------------------

	@Test
	@Tag("cross-tenant")
	void eachTenantAdminOnlyEverReadsTheirOwnGeneralAndBrandingConfig() {
		Tenant tenantA = seedActiveTenant(uniqueSubdomain("cfg-cross-read-a"));
		Tenant tenantB = seedActiveTenant(uniqueSubdomain("cfg-cross-read-b"));
		seedTenantUser(tenantA.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		seedTenantUser(tenantB.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String hostA = hostFor(tenantA.getSubdomain());
		String hostB = hostFor(tenantB.getSubdomain());
		String tokenA = loginAndGetToken(hostA, "admin@example.test");
		String tokenB = loginAndGetToken(hostB, "admin@example.test");

		putDomainOrFail(hostA, tokenA, "GENERAL", Map.of("institute_name", "Tenant A Institute"));
		putDomainOrFail(hostB, tokenB, "GENERAL", Map.of("institute_name", "Tenant B Institute"));
		putDomainOrFail(hostA, tokenA, "BRANDING", Map.of("primary_color", "#000000"));
		putDomainOrFail(hostB, tokenB, "BRANDING", Map.of("primary_color", "#FFFFFF"));

		assertThat(valueOf(getDomain(hostA, tokenA, "GENERAL"), "institute_name")).isEqualTo("Tenant A Institute");
		assertThat(valueOf(getDomain(hostB, tokenB, "GENERAL"), "institute_name")).isEqualTo("Tenant B Institute");
		assertThat(valueOf(getDomain(hostA, tokenA, "BRANDING"), "primary_color")).isEqualTo("#000000");
		assertThat(valueOf(getDomain(hostB, tokenB, "BRANDING"), "primary_color")).isEqualTo("#FFFFFF");
	}

	@Test
	@Tag("cross-tenant")
	void tenantAsPutNeverAffectsTenantBsStoredRows() {
		Tenant tenantA = seedActiveTenant(uniqueSubdomain("cfg-cross-write-a"));
		Tenant tenantB = seedActiveTenant(uniqueSubdomain("cfg-cross-write-b"));
		seedTenantUser(tenantA.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		seedTenantUser(tenantB.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String hostA = hostFor(tenantA.getSubdomain());
		String hostB = hostFor(tenantB.getSubdomain());
		String tokenA = loginAndGetToken(hostA, "admin@example.test");
		String tokenB = loginAndGetToken(hostB, "admin@example.test");
		putDomainOrFail(hostB, tokenB, "GENERAL", Map.of("institute_name", "Untouched B Institute"));

		putDomainOrFail(hostA, tokenA, "GENERAL", Map.of("institute_name", "Tenant A Institute"));

		Long tenantBRowCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM tenant_config_entry WHERE tenant_id = ? AND config_key = 'institute_name'",
				Long.class, tenantB.getId());
		assertThat(tenantBRowCount).isEqualTo(1L);
		assertThat(valueOf(getDomain(hostB, tokenB, "GENERAL"), "institute_name")).isEqualTo("Untouched B Institute");
	}

	// ------------------------------------------------------------------
	// Role-gating.
	// ------------------------------------------------------------------

	@Test
	void teacherIsForbiddenOnGetAndPut() {
		assertRoleForbidden(Role.TEACHER, "teacher");
	}

	@Test
	void studentIsForbiddenOnGetAndPut() {
		assertRoleForbidden(Role.STUDENT, "student");
	}

	@Test
	void attendanceOperatorStaffSubRoleIsForbiddenOnGetAndPut() {
		assertRoleForbidden(Role.ATTENDANCE_OPERATOR, "attendance-operator");
	}

	@Test
	void readOnlyAuditorCanViewButNotEdit() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("cfg-auditor"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		seedTenantUser(tenant.getId(), "auditor@example.test", RAW_PASSWORD, Role.READ_ONLY_AUDITOR);
		String host = hostFor(tenant.getSubdomain());
		String auditorToken = loginAndGetToken(host, "auditor@example.test");

		assertThat(getDomain(host, auditorToken, "GENERAL").getStatusCode()).as("auditor GET")
			.isEqualTo(HttpStatus.OK);

		MvcResult putRaw = performPut(host, auditorToken, "GENERAL", Map.of("institute_name", "Blocked"));
		assertThat(HttpStatus.valueOf(putRaw.getResponse().getStatus())).as("auditor PUT")
			.isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void tenantAdminCanViewAndSuccessfullyEdit() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("cfg-admin"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "admin@example.test");

		assertThat(getDomain(host, token, "GENERAL").getStatusCode()).isEqualTo(HttpStatus.OK);

		HttpResult<List<ConfigPropertyResponse>> putResult = putDomainOrFail(host, token, "GENERAL",
				Map.of("institute_name", "My Institute"));
		assertThat(valueOf(putResult, "institute_name")).isEqualTo("My Institute");
	}

	// ------------------------------------------------------------------
	// Validation - rejected and never persisted.
	// ------------------------------------------------------------------

	@Test
	void badEmailIsRejectedAndNotPersisted() {
		assertRejectedAndNotPersisted("GENERAL", Map.of("support_email", "not-an-email"), "support_email");
	}

	@Test
	void nonHexColorIsRejectedAndNotPersisted() {
		assertRejectedAndNotPersisted("BRANDING", Map.of("primary_color", "blue"), "primary_color");
	}

	@Test
	void primaryAndSecondaryColorPairFailingWcagContrastIsRejectedAndNotPersisted() {
		assertRejectedAndNotPersisted("BRANDING", Map.of("primary_color", "#FFFFFF", "secondary_color", "#EEEEEE"),
				"secondary_color");
	}

	@Test
	void unknownConfigKeyIsRejectedAndNotPersisted() {
		assertRejectedAndNotPersisted("GENERAL", Map.of("not_a_real_key", "value"), "not_a_real_key");
	}

	@Test
	void invalidTimezoneIsRejectedAndNotPersisted() {
		assertRejectedAndNotPersisted("GENERAL", Map.of("default_timezone", "Not/A_Zone"), "default_timezone");
	}

	// ------------------------------------------------------------------
	// Registry defaults.
	// ------------------------------------------------------------------

	@Test
	void freshTenantResolvesDefaultCurrencyToUsdAndInstituteNameToNull() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("cfg-default"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "admin@example.test");

		HttpResult<List<ConfigPropertyResponse>> result = getDomain(host, token, "GENERAL");

		assertThat(valueOf(result, "default_currency")).isEqualTo("USD");
		assertThat(valueOf(result, "institute_name")).isNull();
	}

	// ------------------------------------------------------------------
	// Audit log.
	// ------------------------------------------------------------------

	@Test
	void successfulPutWritesExactlyOneAuditLogEntryPerChangedKey() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("cfg-audit"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "admin@example.test");

		putDomainOrFail(host, token, "GENERAL", Map.of("institute_name", "Audited Institute", "default_currency", "LKR"));

		Long auditCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'tenant_config.updated'", Long.class,
				tenant.getId());
		assertThat(auditCount).isEqualTo(2L);
	}

	// ------------------------------------------------------------------
	// Public branding.
	// ------------------------------------------------------------------

	@Test
	void publicBrandingReturnsEachTenantsOwnBrandingByHost() {
		Tenant tenantA = seedActiveTenant(uniqueSubdomain("cfg-public-a"));
		Tenant tenantB = seedActiveTenant(uniqueSubdomain("cfg-public-b"));
		seedTenantUser(tenantA.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		seedTenantUser(tenantB.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String hostA = hostFor(tenantA.getSubdomain());
		String hostB = hostFor(tenantB.getSubdomain());
		String tokenA = loginAndGetToken(hostA, "admin@example.test");
		String tokenB = loginAndGetToken(hostB, "admin@example.test");
		putDomainOrFail(hostA, tokenA, "BRANDING", Map.of("primary_color", "#111111"));
		putDomainOrFail(hostB, tokenB, "BRANDING", Map.of("primary_color", "#222222"));

		HttpResult<PublicBrandingResponse> brandingA = getPublicBranding(hostA);
		HttpResult<PublicBrandingResponse> brandingB = getPublicBranding(hostB);

		assertThat(brandingA.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(brandingA.getBody().data().primaryColor()).isEqualTo("#111111");
		assertThat(brandingB.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(brandingB.getBody().data().primaryColor()).isEqualTo("#222222");
	}

	@Test
	void publicBrandingReturnsPlatformDefaultsForATenantWithNothingSet() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("cfg-public-default"));
		String host = hostFor(tenant.getSubdomain());

		HttpResult<PublicBrandingResponse> branding = getPublicBranding(host);

		assertThat(branding.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(branding.getBody().data().primaryColor()).isNotBlank();
		assertThat(branding.getBody().data().logoUrl()).isNull();
	}

	// ------------------------------------------------------------------
	// Public student registration policy.
	// ------------------------------------------------------------------

	@Test
	void publicStudentRegistrationPolicyReturnsEachTenantsOwnConfiguredValues() {
		Tenant tenantA = seedActiveTenant(uniqueSubdomain("cfg-public-srp-a"));
		Tenant tenantB = seedActiveTenant(uniqueSubdomain("cfg-public-srp-b"));
		seedTenantUser(tenantA.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		seedTenantUser(tenantB.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String hostA = hostFor(tenantA.getSubdomain());
		String hostB = hostFor(tenantB.getSubdomain());
		String tokenA = loginAndGetToken(hostA, "admin@example.test");
		String tokenB = loginAndGetToken(hostB, "admin@example.test");
		putDomainOrFail(hostA, tokenA, "STUDENT",
				Map.of("public_registration_enabled", false, "approval_required", true, "otp_required", true,
						"require_guardian_info", true, "require_school", true, "require_grade", true,
						"require_stream", true, "require_mobile", true));
		putDomainOrFail(hostB, tokenB, "STUDENT", Map.of("public_registration_enabled", true));

		HttpResult<PublicStudentRegistrationPolicyResponse> policyA = getPublicStudentRegistrationPolicy(hostA);
		HttpResult<PublicStudentRegistrationPolicyResponse> policyB = getPublicStudentRegistrationPolicy(hostB);

		assertThat(policyA.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(policyA.getBody().data()).isEqualTo(new PublicStudentRegistrationPolicyResponse(false, true, true,
				true, true, true, true, true));
		assertThat(policyB.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(policyB.getBody().data().publicRegistrationEnabled()).isTrue();
		assertThat(policyB.getBody().data().approvalRequired()).isFalse();
	}

	@Test
	void publicStudentRegistrationPolicyReturnsDocumentedDefaultsForATenantWithNothingSet() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("cfg-public-srp-default"));
		String host = hostFor(tenant.getSubdomain());

		HttpResult<PublicStudentRegistrationPolicyResponse> policy = getPublicStudentRegistrationPolicy(host);

		assertThat(policy.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(policy.getBody().data()).isEqualTo(
				new PublicStudentRegistrationPolicyResponse(true, false, false, false, false, false, false, false));
	}

	@Test
	void publicStudentRegistrationPolicyResponseNeverLeaksPropertiesBeyondTheFixedEightFields() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("cfg-public-srp-shape"));
		String host = hostFor(tenant.getSubdomain());

		MvcResult raw = perform(authenticated(get(PUBLIC_STUDENT_REGISTRATION_POLICY_PATH), host, null));
		Map<String, Object> body = objectMapper.readValue(rawContent(raw), Map.class);
		@SuppressWarnings("unchecked")
		Map<String, Object> data = (Map<String, Object>) body.get("data");

		assertThat(data.keySet()).containsExactlyInAnyOrder("publicRegistrationEnabled", "approvalRequired",
				"otpRequired", "requireGuardianInfo", "requireSchool", "requireGrade", "requireStream",
				"requireMobile");
	}

	// ------------------------------------------------------------------
	// Shared helpers.
	// ------------------------------------------------------------------

	private void assertRoleForbidden(Role role, String subdomainPrefix) {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("cfg-deny-" + subdomainPrefix));
		String email = subdomainPrefix + "@example.test";
		seedTenantUser(tenant.getId(), email, RAW_PASSWORD, role);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, email);

		assertThat(getDomain(host, token, "GENERAL").getStatusCode()).as(role + " GET").isEqualTo(HttpStatus.FORBIDDEN);

		MvcResult putRaw = performPut(host, token, "GENERAL", Map.of("institute_name", "Blocked"));
		assertThat(HttpStatus.valueOf(putRaw.getResponse().getStatus())).as(role + " PUT")
			.isEqualTo(HttpStatus.FORBIDDEN);
	}

	private void assertRejectedAndNotPersisted(String domain, Map<String, Object> changes, String expectedField) {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("cfg-invalid"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "admin@example.test");

		HttpResult<List<ConfigPropertyResponse>> result = parseList(performPut(host, token, domain, changes));

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(result.getBody().success()).isFalse();
		assertThat(result.getBody().error().code()).isEqualTo("VALIDATION_ERROR");
		assertThat(result.getBody().error().fieldErrors()).extracting(FieldError::field).contains(expectedField);

		Long rowCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM tenant_config_entry WHERE tenant_id = ? AND config_domain = ?", Long.class,
				tenant.getId(), domain);
		assertThat(rowCount).isEqualTo(0L);
	}

	private String loginAndGetToken(String host, String email) {
		HttpResult<LoginResponse> response = login(host, email, RAW_PASSWORD);
		assertThat(response.getStatusCode()).as("login for " + email).isEqualTo(HttpStatus.OK);
		return response.getBody().data().accessToken();
	}

	private HttpResult<List<ConfigPropertyResponse>> putDomainOrFail(String host, String token, String domain,
			Map<String, Object> changes) {
		HttpResult<List<ConfigPropertyResponse>> result = parseList(performPut(host, token, domain, changes));
		assertThat(result.getStatusCode()).as("PUT " + domain + " for " + host).isEqualTo(HttpStatus.OK);
		return result;
	}

	private MvcResult performPut(String host, String token, String domain, Map<String, Object> changes) {
		MockHttpServletRequestBuilder builder = put(TENANT_CONFIG_PATH + "/{domain}", domain)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(changes));
		return perform(authenticated(builder, host, token));
	}

	private HttpResult<List<ConfigPropertyResponse>> getDomain(String host, String token, String domain) {
		MockHttpServletRequestBuilder builder = get(TENANT_CONFIG_PATH + "/{domain}", domain);
		return parseList(perform(authenticated(builder, host, token)));
	}

	private HttpResult<PublicBrandingResponse> getPublicBranding(String host) {
		MockHttpServletRequestBuilder builder = get(PUBLIC_BRANDING_PATH);
		MvcResult raw = perform(authenticated(builder, host, null));
		MockHttpServletResponse response = raw.getResponse();
		HttpStatus status = HttpStatus.valueOf(response.getStatus());
		String json = rawContent(raw);
		ApiResponse<PublicBrandingResponse> body = null;
		if (json != null && !json.isBlank()) {
			JavaType type = objectMapper.getTypeFactory().constructParametricType(ApiResponse.class,
					PublicBrandingResponse.class);
			body = objectMapper.readValue(json, type);
		}
		return new HttpResult<>(status, body, new HttpHeaders());
	}

	private HttpResult<PublicStudentRegistrationPolicyResponse> getPublicStudentRegistrationPolicy(String host) {
		MockHttpServletRequestBuilder builder = get(PUBLIC_STUDENT_REGISTRATION_POLICY_PATH);
		MvcResult raw = perform(authenticated(builder, host, null));
		MockHttpServletResponse response = raw.getResponse();
		HttpStatus status = HttpStatus.valueOf(response.getStatus());
		String json = rawContent(raw);
		ApiResponse<PublicStudentRegistrationPolicyResponse> body = null;
		if (json != null && !json.isBlank()) {
			JavaType type = objectMapper.getTypeFactory().constructParametricType(ApiResponse.class,
					PublicStudentRegistrationPolicyResponse.class);
			body = objectMapper.readValue(json, type);
		}
		return new HttpResult<>(status, body, new HttpHeaders());
	}

	private static Object valueOf(HttpResult<List<ConfigPropertyResponse>> result, String key) {
		return result.getBody()
			.data()
			.stream()
			.filter(property -> property.key().equals(key))
			.findFirst()
			.orElseThrow(() -> new AssertionError("No property '" + key + "' in response"))
			.value();
	}

	private HttpResult<List<ConfigPropertyResponse>> parseList(MvcResult raw) {
		MockHttpServletResponse response = raw.getResponse();
		HttpStatus status = HttpStatus.valueOf(response.getStatus());
		String json = rawContent(raw);
		ApiResponse<List<ConfigPropertyResponse>> body = null;
		if (json != null && !json.isBlank()) {
			JavaType listType = objectMapper.getTypeFactory()
				.constructCollectionType(List.class, ConfigPropertyResponse.class);
			JavaType type = objectMapper.getTypeFactory().constructParametricType(ApiResponse.class, listType);
			body = objectMapper.readValue(json, type);
		}
		return new HttpResult<>(status, body, new HttpHeaders());
	}

	private MockHttpServletRequestBuilder authenticated(MockHttpServletRequestBuilder builder, String host,
			String token) {
		if (host != null) {
			builder.header(HttpHeaders.HOST, host);
		}
		if (token != null) {
			builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
		}
		return builder;
	}

	private MvcResult perform(MockHttpServletRequestBuilder request) {
		try {
			return mockMvc.perform(request).andReturn();
		}
		catch (Exception e) {
			throw new IllegalStateException("MockMvc request failed", e);
		}
	}

	private String rawContent(MvcResult raw) {
		try {
			return raw.getResponse().getContentAsString();
		}
		catch (Exception e) {
			throw new IllegalStateException("Failed to read MockMvc response content", e);
		}
	}

}
