package com.lms.usermanagement.staff;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.identityaccessservice.AuthIntegrationTestSupport;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.web.dto.LoginResponse;
import com.lms.tenantmanagement.domain.Tenant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;

/** Shared MockMvc plumbing for the STAFF-MGMT-02 integration tests (real filter chain, Testcontainers). */
public abstract class StaffApiTestSupport extends AuthIntegrationTestSupport {

	protected static final String STAFF = "/api/v1/staff";

	protected static final String MATRIX = "/api/v1/staff/permission-matrix";

	/** A seeded tenant with a logged-in Tenant Admin. */
	protected record Fx(Tenant tenant, String host, UUID adminId, String adminToken) {

		public UUID tenantId() {
			return tenant.getId();
		}

	}

	protected Fx tenantWithAdmin(String prefix) {
		Tenant tenant = seedActiveTenant(uniqueSubdomain(prefix));
		String host = hostFor(tenant.getSubdomain());
		UUID adminId = seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN).getId();
		return new Fx(tenant, host, adminId, token(host, "admin@example.test"));
	}

	/** Seeds an ACTIVE user with a password and returns its access token. */
	protected String userToken(Fx fx, String email, Role role) {
		seedTenantUser(fx.tenantId(), email, RAW_PASSWORD, role);
		return token(fx.host(), email);
	}

	protected String token(String host, String email) {
		HttpResult<LoginResponse> response = login(host, email, RAW_PASSWORD);
		assertThat(response.getStatusCode()).as("login for " + email).isEqualTo(HttpStatus.OK);
		return response.getBody().data().accessToken();
	}

	protected MvcResult call(HttpMethod method, String path, String host, String token, Object body) {
		MockHttpServletRequestBuilder builder = MockMvcRequestBuilders.request(method, path);
		if (host != null) {
			builder.header(HttpHeaders.HOST, host);
		}
		if (token != null) {
			builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
		}
		if (body != null) {
			builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
		}
		try {
			return mockMvc.perform(builder).andReturn();
		}
		catch (Exception e) {
			throw new IllegalStateException("MockMvc request failed", e);
		}
	}

	protected JsonNode expect(MvcResult result, int status) {
		String content = content(result);
		assertThat(result.getResponse().getStatus()).as(content).isEqualTo(status);
		return content.isBlank() ? null : objectMapper.readTree(content);
	}

	protected static String content(MvcResult result) {
		try {
			return result.getResponse().getContentAsString();
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	protected static String errorCode(JsonNode body) {
		return body.path("error").path("code").asString();
	}

	protected JsonNode createStaff(Fx fx, String first, String last, String email, String role) {
		return expect(call(HttpMethod.POST, STAFF, fx.host(), fx.adminToken(),
				Map.of("firstName", first, "lastName", last, "email", email, "mobileNumber", "0771234567",
						"roleCode", role)),
				201).path("data");
	}

	protected JsonNode matrix(Fx fx) {
		return expect(call(HttpMethod.GET, MATRIX, fx.host(), fx.adminToken(), null), 200).path("data");
	}

	protected MvcResult saveMatrix(Fx fx, String token, long expectedVersion, List<Map<String, Object>> changes) {
		return call(HttpMethod.PUT, MATRIX, fx.host(), token,
				Map.of("expectedVersion", expectedVersion, "changes", changes));
	}

	protected static Map<String, Object> change(String role, String capability, boolean granted) {
		return Map.of("roleCode", role, "capabilityKey", capability, "granted", granted);
	}

	/** Grants (or revokes) capabilities for a role as the tenant admin; returns the new version. */
	protected long setGrants(Fx fx, String role, boolean granted, String... capabilities) {
		long version = matrix(fx).path("version").asLong();
		List<Map<String, Object>> changes = java.util.Arrays.stream(capabilities)
			.map(c -> change(role, c, granted))
			.toList();
		return expect(saveMatrix(fx, fx.adminToken(), version, changes), 200).path("data").path("version").asLong();
	}

	protected static JsonNode cell(JsonNode matrix, String capability, String role) {
		for (JsonNode group : matrix.path("groups")) {
			for (JsonNode row : group.path("rows")) {
				if (capability.equals(row.path("key").asString())) {
					return row.path("cells").path(role);
				}
			}
		}
		throw new AssertionError("No matrix row " + capability);
	}

	protected long auditCount(UUID tenantId, String action) {
		Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = ?",
				Long.class, tenantId, action);
		return count == null ? 0 : count;
	}

	protected static String uniqueEmail(String prefix) {
		return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
	}

}
