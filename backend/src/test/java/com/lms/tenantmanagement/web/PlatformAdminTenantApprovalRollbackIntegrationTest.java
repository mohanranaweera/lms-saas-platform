package com.lms.tenantmanagement.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.common.api.ApiResponse;
import com.lms.identityaccessservice.AuthIntegrationTestSupport;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.web.dto.LoginResponse;
import com.lms.tenantmanagement.api.TenantStatus;
import com.lms.tenantmanagement.domain.Tenant;
import com.lms.tenantmanagement.web.dto.TenantDetailResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JavaType;

/**
 * Closes plan §18 Testcontainers item's "plus a simulated
 * mid-transaction-failure/rollback test" requirement for PADASH-1's
 * approve/reject endpoints - mirrors {@code
 * PaymentConfirmationRollbackIntegrationTest}'s exact technique: a real
 * Spring context/transaction manager/Testcontainers Postgres, with only
 * {@link AuditLogApi} replaced by a throwing {@code @MockitoBean}, proving
 * {@code TenantApprovalService#transition}'s status flip and its
 * {@code TenantStatusChangedEvent}-driven audit write are one atomic unit:
 * a failure in the audit write rolls back the status change too, so the
 * tenant is never left approved/rejected without its accompanying audit
 * row.
 *
 * <p>Deliberately its own test class (not merged into {@link
 * PlatformAdminTenantControllerIntegrationTest}) because a class-level
 * {@code @MockitoBean} on {@link AuditLogApi} replaces the real bean for
 * every test in the class - mixing it into the main flow class would
 * silently turn every other test's real, listener-driven audit write into a
 * Mockito no-op that never persists a row.
 *
 * <p>Note: this class covers rollback of the status-flip + audit-write unit
 * only. Tenant approval currently provisions no default plan/branding
 * config (see {@code docs/plans/MVP-020 Platform Admin Dashboard.md} §22
 * item 4 - {@code requestedPlan} is already fully captured at registration,
 * nothing further is provisioned at approval time) - if a real provisioning
 * step is ever added to {@code TenantApprovalService#transition}, this test
 * class will need extending to cover its rollback behavior too.
 */
class PlatformAdminTenantApprovalRollbackIntegrationTest extends AuthIntegrationTestSupport {

	@MockitoBean
	private AuditLogApi auditLogApi;

	@Test
	void aFailureInAuditWriteRollsBackTheApproveTenantStatusChange() {
		doThrow(new RuntimeException("Simulated failure")).when(auditLogApi).recordForTenant(any(), any());
		Tenant tenant = seedTenant(uniqueSubdomain("padash1-approve-rollback"), TenantStatus.PENDING_APPROVAL);
		String token = seedAndLoginPlatformAdmin("padash1-approve-rollback-admin@platform.test");

		HttpResult<TenantDetailResponse> result = approveTenant(token, tenant.getId());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		String dbStatus = jdbcTemplate.queryForObject("SELECT status FROM tenant WHERE id = ?", String.class,
				tenant.getId());
		assertThat(dbStatus).isEqualTo("pending_approval");
		Long auditCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM audit_log WHERE target_entity = 'tenant' AND target_id = ?", Long.class,
				tenant.getId());
		assertThat(auditCount).isEqualTo(0L);
	}

	@Test
	void aFailureInAuditWriteRollsBackTheRejectTenantStatusChange() {
		doThrow(new RuntimeException("Simulated failure")).when(auditLogApi).recordForTenant(any(), any());
		Tenant tenant = seedTenant(uniqueSubdomain("padash1-reject-rollback"), TenantStatus.PENDING_APPROVAL);
		String token = seedAndLoginPlatformAdmin("padash1-reject-rollback-admin@platform.test");

		HttpResult<TenantDetailResponse> result = rejectTenant(token, tenant.getId());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		String dbStatus = jdbcTemplate.queryForObject("SELECT status FROM tenant WHERE id = ?", String.class,
				tenant.getId());
		assertThat(dbStatus).isEqualTo("pending_approval");
		Long auditCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM audit_log WHERE target_entity = 'tenant' AND target_id = ?", Long.class,
				tenant.getId());
		assertThat(auditCount).isEqualTo(0L);
	}

	// ------------------------------------------------------------------
	// Local HTTP helpers - see PlatformAdminTenantControllerIntegrationTest's
	// class javadoc for why these are duplicated locally rather than added
	// to AuthIntegrationTestSupport.
	// ------------------------------------------------------------------

	private String seedAndLoginPlatformAdmin(String email) {
		seedPlatformAdmin(email, RAW_PASSWORD);
		HttpResult<LoginResponse> loginResult = platformAdminLogin(null, email, RAW_PASSWORD);
		if (loginResult.getStatusCode() != HttpStatus.OK) {
			throw new IllegalStateException("Platform admin login failed: " + loginResult.getStatusCode());
		}
		return loginResult.getBody().data().accessToken();
	}

	private HttpResult<TenantDetailResponse> approveTenant(String token, UUID id) {
		MockHttpServletRequestBuilder builder = post("/api/v1/platform-admin/tenants/{id}/approve", id);
		return parseSingle(perform(bearer(builder, token)), TenantDetailResponse.class);
	}

	private HttpResult<TenantDetailResponse> rejectTenant(String token, UUID id) {
		MockHttpServletRequestBuilder builder = post("/api/v1/platform-admin/tenants/{id}/reject", id);
		return parseSingle(perform(bearer(builder, token)), TenantDetailResponse.class);
	}

	private MockHttpServletRequestBuilder bearer(MockHttpServletRequestBuilder builder, String token) {
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

	private <T> HttpResult<T> parseSingle(MvcResult raw, Class<T> type) {
		MockHttpServletResponse response = raw.getResponse();
		HttpStatus status = HttpStatus.valueOf(response.getStatus());
		String json = rawContent(raw);
		ApiResponse<T> body = null;
		if (json != null && !json.isBlank()) {
			JavaType javaType = objectMapper.getTypeFactory().constructParametricType(ApiResponse.class, type);
			body = objectMapper.readValue(json, javaType);
		}
		return new HttpResult<>(status, body, new HttpHeaders());
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
