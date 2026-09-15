package com.lms.identityaccessservice.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.lms.common.api.ApiResponse;
import com.lms.identityaccessservice.AuthIntegrationTestSupport;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.web.dto.LoginResponse;
import com.lms.tenantmanagement.domain.Tenant;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JavaType;

/**
 * The reverse of {@link CrossTenantSessionReplayIntegrationTest}: a valid
 * Platform Admin access token must never be usable against a tenant-resolved
 * endpoint, however signature-valid and unexpired it is. Proves {@code
 * JwtAuthenticationFilter}'s {@code parsedToken.isPlatformAdmin() &&
 * !request.getRequestURI().startsWith("/api/v1/platform-admin/")} guard
 * actually runs, before principal resolution ever gets a chance to succeed.
 */
@Tag("cross-tenant")
class CrossRolePlatformAdminTokenReplayIntegrationTest extends AuthIntegrationTestSupport {

	@Test
	void aPlatformAdminTokenReplayedAgainstATenantResolvedEndpointIsRejectedNeverAccepted() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("tenant-a"));
		seedActiveStudent(tenant.getId(), "student@example.test");
		seedPlatformAdmin("cross-role-admin@platform.test", RAW_PASSWORD);

		HttpResult<LoginResponse> platformAdminLoginResponse = platformAdminLogin(null, "cross-role-admin@platform.test",
				RAW_PASSWORD);
		String platformAdminAccessToken = platformAdminLoginResponse.getBody().data().accessToken();

		// Same valid, unexpired, signature-valid Platform Admin token - but presented
		// against a tenant-resolved protected endpoint, not a /api/v1/platform-admin/... one.
		HttpResult<Void> crossRoleAttempt = logout(hostFor(tenant.getSubdomain()), platformAdminAccessToken);

		assertThat(crossRoleAttempt.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(crossRoleAttempt.getBody().success()).isFalse();
		assertThat(crossRoleAttempt.getBody().error().code()).isEqualTo("SESSION_REVOKED");
	}

	/**
	 * The reciprocal direction (MVP-020 plan §15 item 3): a real, valid,
	 * unexpired tenant-scoped access token must never be usable against a
	 * {@code /api/v1/platform-admin/**} endpoint either. Traced (and
	 * confirmed here empirically, not merely from the trace) through {@code
	 * TenantResolutionFilter.shouldNotFilter} (excludes the entire {@code
	 * /api/v1/platform-admin/**} prefix, so {@code TenantContext} is never
	 * resolved on this request) and {@code
	 * JwtAuthenticationFilter#resolveTenantPrincipal} (calls {@code
	 * tenantContext.getTenantId()}, which throws {@code
	 * TenantContextNotResolvedException}, caught and turned into a {@code
	 * null} principal) - the request never reaches {@code @PreAuthorize} at
	 * all; it fails inside {@code JwtAuthenticationFilter} itself with the
	 * same {@code 401 SESSION_REVOKED} outcome as the platform-admin-token-
	 * outside-its-prefix case above, not a {@code 403}. One representative
	 * platform-admin endpoint (the tenant list) is exercised here; every
	 * per-module platform-admin controller test class asserts this same
	 * outcome for its own endpoints individually - see {@code
	 * PlatformAdminTenantControllerIntegrationTest} (all 4 tenant endpoints),
	 * {@code PlatformAdminLedgerControllerIntegrationTest} (both payment
	 * endpoints), and {@code PlatformAdminAuditLogControllerIntegrationTest}
	 * (both audit-log endpoints) - a post-review discoverability note added
	 * so a reader of only this class doesn't undercount the actual coverage.
	 */
	@Test
	void aTenantScopedTokenReplayedAgainstAPlatformAdminEndpointIsRejectedNeverAccepted() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("tenant-jwt-replay"));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		HttpResult<LoginResponse> tenantLoginResponse = login(host, "admin@example.test", RAW_PASSWORD);
		String tenantAccessToken = tenantLoginResponse.getBody().data().accessToken();

		HttpResult<Void> crossRoleAttempt = getPlatformAdminTenantList(tenantAccessToken);

		assertThat(crossRoleAttempt.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(crossRoleAttempt.getBody().success()).isFalse();
		assertThat(crossRoleAttempt.getBody().error().code()).isEqualTo("SESSION_REVOKED");
	}

	private HttpResult<Void> getPlatformAdminTenantList(String accessToken) {
		MockHttpServletRequestBuilder builder = get("/api/v1/platform-admin/tenants");
		if (accessToken != null) {
			builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
		}
		MvcResult raw;
		try {
			raw = mockMvc.perform(builder).andReturn();
		}
		catch (Exception e) {
			throw new IllegalStateException("MockMvc request failed", e);
		}
		MockHttpServletResponse response = raw.getResponse();
		HttpStatus status = HttpStatus.valueOf(response.getStatus());
		String json;
		try {
			json = response.getContentAsString();
		}
		catch (Exception e) {
			throw new IllegalStateException("Failed to read MockMvc response content", e);
		}
		ApiResponse<Void> body = null;
		if (json != null && !json.isBlank()) {
			JavaType type = objectMapper.getTypeFactory().constructParametricType(ApiResponse.class, Void.class);
			body = objectMapper.readValue(json, type);
		}
		return new HttpResult<>(status, body, new HttpHeaders());
	}

}
