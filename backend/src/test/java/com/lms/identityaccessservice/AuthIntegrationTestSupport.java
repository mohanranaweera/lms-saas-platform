package com.lms.identityaccessservice;

import com.lms.common.AbstractIntegrationTest;
import com.lms.common.api.ApiResponse;
import com.lms.identityaccessservice.domain.PlatformAdminUser;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.identityaccessservice.repository.DeviceSessionRepository;
import com.lms.identityaccessservice.repository.PlatformAdminSessionRepository;
import com.lms.identityaccessservice.repository.PlatformAdminUserRepository;
import com.lms.identityaccessservice.repository.TenantUserRepository;
import com.lms.identityaccessservice.service.TokenService;
import com.lms.identityaccessservice.web.dto.LoginRequest;
import com.lms.identityaccessservice.web.dto.LoginResponse;
import com.lms.identityaccessservice.web.dto.RefreshResponse;
import com.lms.tenantmanagement.api.TenantStatus;
import com.lms.tenantmanagement.domain.Tenant;
import com.lms.tenantmanagement.repository.TenantRepository;
import jakarta.servlet.http.Cookie;
import java.util.Enumeration;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;

/**
 * Shared seeding/HTTP helpers for the Authentication Foundation module's
 * Testcontainers integration tests. Every helper here exercises the real
 * Spring Security filter chain (via {@code MockMvc}, see {@link HttpResult}'s
 * javadoc for why MockMvc rather than a real socket round-trip is used) so
 * that {@code TenantResolutionFilter}/{@code JwtAuthenticationFilter}
 * behavior is proven end-to-end, not mocked.
 *
 * <p>Not itself a test class (no {@code @Test} methods, and its name does
 * not match Surefire's default {@code *Test}/{@code Test*} inclusion
 * patterns), so it is never run directly.
 */
@AutoConfigureMockMvc
public abstract class AuthIntegrationTestSupport extends AbstractIntegrationTest {

	protected static final String RAW_PASSWORD = "Correct-Horse-Battery-Staple-1!";

	protected static final String WRONG_PASSWORD = "Totally-Wrong-Password-9!";

	@Autowired
	protected TenantRepository tenantRepository;

	@Autowired
	protected TenantUserRepository tenantUserRepository;

	@Autowired
	protected DeviceSessionRepository deviceSessionRepository;

	@Autowired
	protected PlatformAdminUserRepository platformAdminUserRepository;

	@Autowired
	protected PlatformAdminSessionRepository platformAdminSessionRepository;

	@Autowired
	protected PasswordEncoder passwordEncoder;

	@Autowired
	protected TokenService tokenService;

	@Autowired
	protected JdbcTemplate jdbcTemplate;

	@Autowired
	protected MockMvc mockMvc;

	@Autowired
	protected ObjectMapper objectMapper;

	protected static String uniqueSubdomain(String prefix) {
		return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
	}

	protected static String hostFor(String subdomain) {
		return subdomain + ".lms.test";
	}

	/**
	 * {@code Tenant} deliberately exposes no public constructor and no bare
	 * status setter (see its own javadoc) - {@code TEN-2}'s real
	 * approval/status-change orchestration is not wired yet, so there is no
	 * legitimate production code path to reach any status other than
	 * {@code PENDING_APPROVAL} via {@code Tenant.registerPending(...)}. Tests
	 * that need a tenant already in an arbitrary lifecycle state (to test
	 * login/session behavior per status) seed it directly via SQL instead,
	 * the same pattern already established in
	 * {@code TenantLookupServiceIntegrationTest#seedTenant}.
	 */
	protected Tenant seedTenant(String subdomain, TenantStatus status) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update(
				"INSERT INTO tenant (id, name, subdomain, status, requested_plan, contact_name, contact_email, "
						+ "contact_phone, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, now(), now())",
				id, "Test Institute " + subdomain, subdomain, status.toColumnValue(), "starter", "Test Contact",
				"contact-" + subdomain + "@example.test", "+1-555-0100");
		return tenantRepository.findById(id).orElseThrow();
	}

	protected Tenant seedActiveTenant(String subdomain) {
		return seedTenant(subdomain, TenantStatus.ACTIVE);
	}

	protected TenantUser seedTenantUser(UUID tenantId, String email, String rawPassword, Role role) {
		return withTenant(tenantId,
				() -> tenantUserRepository.save(new TenantUser(tenantId, email, passwordEncoder.encode(rawPassword), role)));
	}

	protected TenantUser seedActiveStudent(UUID tenantId, String email) {
		return seedTenantUser(tenantId, email, RAW_PASSWORD, Role.STUDENT);
	}

	protected void suspendTenantUser(UUID tenantUserId) {
		jdbcTemplate.update("UPDATE tenant_user SET status = 'suspended' WHERE id = ?", tenantUserId);
	}

	protected void suspendPlatformAdmin(UUID adminId) {
		jdbcTemplate.update("UPDATE platform_admin_user SET status = 'suspended' WHERE id = ?", adminId);
	}

	protected PlatformAdminUser seedPlatformAdmin(String email, String rawPassword) {
		return platformAdminUserRepository.save(new PlatformAdminUser(email, passwordEncoder.encode(rawPassword)));
	}

	protected HttpResult<LoginResponse> login(String host, String email, String password) {
		MockHttpServletRequestBuilder request = jsonPost("/api/v1/auth/login", host)
			.content(writeJson(new LoginRequest(email, password)));
		return exchange(request, LoginResponse.class);
	}

	/** Sends a login request whose JSON body carries an extra, client-supplied {@code tenantId} field. */
	protected HttpResult<LoginResponse> loginWithManipulatedTenantId(String host, String email, String password,
			UUID manipulatedTenantId, String manipulatedHeaderValue) {
		Map<String, Object> body = Map.of("email", email, "password", password, "tenantId",
				manipulatedTenantId.toString());
		MockHttpServletRequestBuilder request = jsonPost("/api/v1/auth/login", host).content(writeJson(body));
		if (manipulatedHeaderValue != null) {
			request.header("X-Tenant-Id", manipulatedHeaderValue);
		}
		return exchange(request, LoginResponse.class);
	}

	protected HttpResult<RefreshResponse> refresh(String host, String refreshCookieName, String rawCookieValue) {
		MockHttpServletRequestBuilder request = jsonPost("/api/v1/auth/refresh", host)
			.cookie(new Cookie(refreshCookieName, rawCookieValue));
		return exchange(request, RefreshResponse.class);
	}

	protected HttpResult<Void> logout(String host, String accessToken) {
		MockHttpServletRequestBuilder request = jsonPost("/api/v1/auth/logout", host);
		if (accessToken != null) {
			request.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
		}
		return exchange(request, Void.class);
	}

	protected HttpResult<LoginResponse> platformAdminLogin(String host, String email, String password) {
		MockHttpServletRequestBuilder request = jsonPost("/api/v1/platform-admin/auth/login", host)
			.content(writeJson(new LoginRequest(email, password)));
		return exchange(request, LoginResponse.class);
	}

	protected HttpResult<RefreshResponse> platformAdminRefresh(String rawCookieValue) {
		MockHttpServletRequestBuilder request = jsonPost("/api/v1/platform-admin/auth/refresh", null)
			.cookie(new Cookie("lms_platform_admin_refresh_token", rawCookieValue));
		return exchange(request, RefreshResponse.class);
	}

	protected HttpResult<Void> platformAdminLogout(String accessToken) {
		MockHttpServletRequestBuilder request = jsonPost("/api/v1/platform-admin/auth/logout", null);
		if (accessToken != null) {
			request.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
		}
		return exchange(request, Void.class);
	}

	protected String extractCookieValue(HttpResult<?> response, String cookieName) {
		return response.getHeaders()
			.get(HttpHeaders.SET_COOKIE)
			.stream()
			.filter(header -> header.startsWith(cookieName + "="))
			.map(header -> {
				String withoutName = header.substring((cookieName + "=").length());
				int semicolon = withoutName.indexOf(';');
				return semicolon >= 0 ? withoutName.substring(0, semicolon) : withoutName;
			})
			.findFirst()
			.orElseThrow(() -> new AssertionError("No Set-Cookie header found for " + cookieName));
	}

	private MockHttpServletRequestBuilder jsonPost(String path, String host) {
		MockHttpServletRequestBuilder request = MockMvcRequestBuilders.post(path).contentType(MediaType.APPLICATION_JSON);
		if (host != null) {
			request.header(HttpHeaders.HOST, host);
		}
		return request;
	}

	private <T> HttpResult<T> exchange(MockHttpServletRequestBuilder request, Class<T> dataType) {
		try {
			MvcResult result = mockMvc.perform(request).andReturn();
			MockHttpServletResponse response = result.getResponse();
			HttpStatus status = HttpStatus.valueOf(response.getStatus());
			String json = response.getContentAsString();
			ApiResponse<T> body = null;
			if (json != null && !json.isBlank()) {
				JavaType type = objectMapper.getTypeFactory().constructParametricType(ApiResponse.class, dataType);
				body = objectMapper.readValue(json, type);
			}
			HttpHeaders headers = new HttpHeaders();
			Enumeration<String> headerNames = response.getHeaderNames() == null ? null
					: java.util.Collections.enumeration(response.getHeaderNames());
			if (headerNames != null) {
				while (headerNames.hasMoreElements()) {
					String name = headerNames.nextElement();
					response.getHeaders(name).forEach(value -> headers.add(name, value));
				}
			}
			return new HttpResult<>(status, body, headers);
		}
		catch (Exception e) {
			throw new IllegalStateException("MockMvc request failed", e);
		}
	}

	private String writeJson(Object value) {
		return objectMapper.writeValueAsString(value);
	}

}
