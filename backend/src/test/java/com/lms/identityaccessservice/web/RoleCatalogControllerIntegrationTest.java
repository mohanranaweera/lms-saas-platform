package com.lms.identityaccessservice.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.lms.common.api.ApiResponse;
import com.lms.identityaccessservice.AuthIntegrationTestSupport;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.api.RoleSummary;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.web.dto.LoginResponse;
import com.lms.tenantmanagement.domain.Tenant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JavaType;

/**
 * RBAC-1's only new REST surface ({@link RoleCatalogController}) had zero
 * test coverage prior to this class (security review finding, Module 3).
 * Proves {@code GET /api/v1/roles} goes through the real Spring Security
 * filter chain and is actually gated by
 * {@code @PreAuthorize("@permissionCheckService.hasPermission('STAFF_AND_ROLES', 'VIEW')")}
 * (per {@link com.lms.identityaccessservice.service.PermissionCheckServiceImpl#buildMatrix()},
 * only {@code TENANT_ADMIN} and {@code READ_ONLY_AUDITOR} are granted that
 * cell), and that the response body is exactly the 11 {@code TENANT}-scope
 * catalog rows (V7) in {@code sort_order}, excluding {@code PLATFORM_ADMIN}.
 * Modeled directly on {@link PermissionEnforcementIntegrationTest}.
 */
class RoleCatalogControllerIntegrationTest extends AuthIntegrationTestSupport {

	private static final List<String> EXPECTED_CODES_IN_SORT_ORDER = List.of("TENANT_ADMIN", "FINANCE_STAFF",
			"COURSE_COORDINATOR", "STUDENT_SUPPORT", "CONTENT_MANAGER", "EXAM_MANAGER", "ATTENDANCE_OPERATOR",
			"READ_ONLY_AUDITOR", "TEACHER", "TEACHER_ASSISTANT", "STUDENT");

	@Test
	void noTokenIsRejectedWithUnauthorized() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("tenant-roles-401"));
		String host = hostFor(tenant.getSubdomain());

		HttpResult<List<RoleSummary>> result = listRoles(host, null);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void staffRoleWithoutStaffAndRolesViewPermissionIsForbidden() {
		// Per PermissionCheckServiceImpl#buildMatrix, only TENANT_ADMIN and
		// READ_ONLY_AUDITOR are granted STAFF_AND_ROLES/VIEW. Exam Manager (used
		// here) has no entry at all for that domain area, same as Content
		// Manager and Finance Staff.
		Tenant tenant = seedActiveTenant(uniqueSubdomain("tenant-roles-403"));
		seedTenantUser(tenant.getId(), "exam-manager@example.test", RAW_PASSWORD, Role.EXAM_MANAGER);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "exam-manager@example.test");

		HttpResult<List<RoleSummary>> result = listRoles(host, token);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(result.getBody().success()).isFalse();
		assertThat(result.getBody().error().code()).isEqualTo("FORBIDDEN");
	}

	@Test
	void tenantAdminWithStaffAndRolesViewSeesTheElevenTenantScopedRolesInCatalogSortOrder() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("tenant-roles-200-admin"));
		seedTenantUser(tenant.getId(), "tenant-admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "tenant-admin@example.test");

		HttpResult<List<RoleSummary>> result = listRoles(host, token);

		assertRolesResponse(result);
	}

	@Test
	void readOnlyAuditorWithStaffAndRolesViewAlsoSeesTheSameElevenRoles() {
		Tenant tenant = seedActiveTenant(uniqueSubdomain("tenant-roles-200-auditor"));
		seedTenantUser(tenant.getId(), "auditor@example.test", RAW_PASSWORD, Role.READ_ONLY_AUDITOR);
		String host = hostFor(tenant.getSubdomain());
		String token = loginAndGetToken(host, "auditor@example.test");

		HttpResult<List<RoleSummary>> result = listRoles(host, token);

		assertRolesResponse(result);
	}

	@Test
	void catalogListingIsIdenticalAcrossTwoDifferentTenantsSinceItIsTenantAgnosticReferenceData() {
		// Low-cost extra: the role catalog is platform-global reference data (V7's
		// own javadoc), not tenant-owned, so two different tenants' Tenant Admins
		// must see the exact same list via their own, independently-issued tokens.
		Tenant tenantA = seedActiveTenant(uniqueSubdomain("tenant-roles-a"));
		Tenant tenantB = seedActiveTenant(uniqueSubdomain("tenant-roles-b"));
		seedTenantUser(tenantA.getId(), "tenant-admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		seedTenantUser(tenantB.getId(), "tenant-admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String hostA = hostFor(tenantA.getSubdomain());
		String hostB = hostFor(tenantB.getSubdomain());
		String tokenA = loginAndGetToken(hostA, "tenant-admin@example.test");
		String tokenB = loginAndGetToken(hostB, "tenant-admin@example.test");

		HttpResult<List<RoleSummary>> resultA = listRoles(hostA, tokenA);
		HttpResult<List<RoleSummary>> resultB = listRoles(hostB, tokenB);

		assertThat(resultA.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(resultB.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(resultA.getBody().data()).isEqualTo(resultB.getBody().data());
	}

	private void assertRolesResponse(HttpResult<List<RoleSummary>> result) {
		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		List<RoleSummary> roles = result.getBody().data();

		assertThat(roles).hasSize(11);
		assertThat(roles).allSatisfy(role -> assertThat(role.scope()).isEqualTo("TENANT"));
		assertThat(roles).extracting(RoleSummary::code).doesNotContain("PLATFORM_ADMIN");
		assertThat(roles).extracting(RoleSummary::code).containsExactlyElementsOf(EXPECTED_CODES_IN_SORT_ORDER);

		roles.forEach(role -> {
			boolean expectedProvisional = "TEACHER_ASSISTANT".equals(role.code());
			assertThat(role.isProvisional()).as(role.code() + ".isProvisional").isEqualTo(expectedProvisional);
		});
	}

	private String loginAndGetToken(String host, String email) {
		HttpResult<LoginResponse> response = login(host, email, RAW_PASSWORD);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		return response.getBody().data().accessToken();
	}

	private HttpResult<List<RoleSummary>> listRoles(String host, String token) {
		MockHttpServletRequestBuilder request = get("/api/v1/roles");
		if (host != null) {
			request.header(HttpHeaders.HOST, host);
		}
		if (token != null) {
			request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
		}
		try {
			MvcResult result = mockMvc.perform(request).andReturn();
			MockHttpServletResponse response = result.getResponse();
			HttpStatus status = HttpStatus.valueOf(response.getStatus());
			String json = response.getContentAsString();
			ApiResponse<List<RoleSummary>> body = null;
			if (json != null && !json.isBlank()) {
				JavaType listType = objectMapper.getTypeFactory().constructCollectionType(List.class, RoleSummary.class);
				JavaType type = objectMapper.getTypeFactory().constructParametricType(ApiResponse.class, listType);
				body = objectMapper.readValue(json, type);
			}
			return new HttpResult<>(status, body, new HttpHeaders());
		}
		catch (Exception e) {
			throw new IllegalStateException("MockMvc request failed", e);
		}
	}

}
