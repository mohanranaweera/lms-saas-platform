package com.lms.localizationmanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.notificationmanagement.NotificationManagementTestSupport;
import com.lms.tenantmanagement.domain.Tenant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/** Shared Testcontainers/MockMvc fixtures for the I18N-01 integration tests (synthetic data only). */
public abstract class I18nTestSupport extends NotificationManagementTestSupport {

	protected static final String SAVE_KEY = "common.actions.save";

	protected static final String CHANGED_KEY = "common.language.changed";

	protected static final String PLURAL_KEY = "email.common.expiresInMinutes";

	protected record Fx(Tenant tenant, String host, String adminToken, TenantUser admin) {

		UUID tenantId() {
			return tenant.getId();
		}

	}

	protected Fx seed(String prefix) {
		Tenant tenant = seedActiveTenant(uniqueSubdomain(prefix));
		TenantUser admin = seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		return new Fx(tenant, host, loginAndGetToken(host, "admin@example.test"), admin);
	}

	/** Seeds a user of {@code role} in the fixture tenant and returns its access token. */
	protected String userToken(Fx fx, String email, Role role) {
		if (role == Role.STUDENT) {
			seedActiveStudent(fx.tenantId(), email);
		}
		else {
			seedTenantUser(fx.tenantId(), email, RAW_PASSWORD, role);
		}
		return loginAndGetToken(fx.host(), email);
	}

	/** Tenant Admin -> Settings -> General language settings (existing tenant-config API). */
	protected MvcResult setLanguages(Fx fx, List<String> enabled, String defaultLocale, Boolean detection) {
		Map<String, Object> changes = new LinkedHashMap<>();
		if (enabled != null) {
			changes.put("enabled_locales", enabled);
		}
		if (defaultLocale != null) {
			changes.put("default_locale", defaultLocale);
		}
		if (detection != null) {
			changes.put("browser_language_detection", detection);
		}
		return json(fx.host(), fx.adminToken(), put("/api/v1/tenant-config/GENERAL"), changes);
	}

	protected void setLanguagesOk(Fx fx, List<String> enabled, String defaultLocale, Boolean detection) {
		MvcResult result = setLanguages(fx, enabled, defaultLocale, detection);
		assertThat(status(result)).as(rawContent(result)).isEqualTo(200);
	}

	protected MvcResult saveDraft(Fx fx, String token, String locale, String key, String value, Long expected) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("value", value);
		body.put("expectedVersion", expected);
		return json(fx.host(), token, put("/api/v1/i18n/admin/drafts/{l}/{k}", locale, key), body);
	}

	protected JsonNode saveDraftOk(Fx fx, String locale, String key, String value, Long expected) {
		MvcResult result = saveDraft(fx, fx.adminToken(), locale, key, value, expected);
		assertThat(status(result)).as(rawContent(result)).isEqualTo(200);
		return data(result);
	}

	protected MvcResult publish(Fx fx, String token, String locale, Integer expected, String summary) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("locale", locale);
		body.put("expectedVersion", expected);
		body.put("summary", summary);
		return json(fx.host(), token, post("/api/v1/i18n/admin/publish"), body);
	}

	protected JsonNode publishOk(Fx fx, String locale, int expected) {
		MvcResult result = publish(fx, fx.adminToken(), locale, expected, "synthetic release");
		assertThat(status(result)).as(rawContent(result)).isEqualTo(201);
		return data(result);
	}

	/** Draft + publish one override as the fixture's Tenant Admin. */
	protected JsonNode publishOverride(Fx fx, String locale, String key, String value, int expectedRelease) {
		saveDraftOk(fx, locale, key, value, null);
		return publishOk(fx, locale, expectedRelease);
	}

	protected MvcResult rollback(Fx fx, String token, UUID releaseId, Integer expected) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("expectedVersion", expected);
		return json(fx.host(), token, post("/api/v1/i18n/admin/releases/{id}/rollback", releaseId), body);
	}

	/** Public catalog (anonymous unless {@code token} is given). */
	protected MvcResult publicCatalog(String host, String token, String query) {
		return call(host, token, get("/api/v1/public/i18n/catalog" + (query == null ? "" : "?" + query)));
	}

	protected JsonNode publicCatalogOk(String host, String query) {
		MvcResult result = publicCatalog(host, null, query);
		assertThat(status(result)).as(rawContent(result)).isEqualTo(200);
		return data(result);
	}

	protected void setGrants(Fx fx, String role, boolean granted, String... capabilities) {
		MvcResult matrix = call(fx.host(), fx.adminToken(), get("/api/v1/staff/permission-matrix"));
		assertThat(status(matrix)).as(rawContent(matrix)).isEqualTo(200);
		long version = data(matrix).path("version").asLong();
		List<Map<String, Object>> changes = java.util.Arrays.stream(capabilities)
			.map(c -> Map.<String, Object>of("roleCode", role, "capabilityKey", c, "granted", granted))
			.toList();
		MvcResult saved = json(fx.host(), fx.adminToken(), put("/api/v1/staff/permission-matrix"),
				Map.of("expectedVersion", version, "changes", changes));
		assertThat(status(saved)).as(rawContent(saved)).isEqualTo(200);
	}

	/** Multipart upload with the same host/bearer plumbing as {@link #call}. */
	protected MvcResult upload(String host, String token, String path,
			org.springframework.mock.web.MockMultipartFile file) {
		var builder = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart(path).file(file);
		if (host != null) {
			builder.header(org.springframework.http.HttpHeaders.HOST, host);
		}
		if (token != null) {
			builder.header(org.springframework.http.HttpHeaders.AUTHORIZATION, "Bearer " + token);
		}
		try {
			return mockMvc.perform(builder).andReturn();
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	protected MvcResult call(String host, String token, MockHttpServletRequestBuilder builder) {
		return perform(authenticated(builder, host, token));
	}

	protected MvcResult json(String host, String token, MockHttpServletRequestBuilder builder, Object body) {
		return call(host, token,
				builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
	}

	protected JsonNode body(MvcResult result) {
		return objectMapper.readTree(rawContent(result));
	}

	protected JsonNode data(MvcResult result) {
		return body(result).path("data");
	}

	protected String errorCode(MvcResult result) {
		return body(result).path("error").path("code").asString();
	}

	protected static int status(MvcResult result) {
		return result.getResponse().getStatus();
	}

	protected long count(String sql, Object... args) {
		Long count = jdbcTemplate.queryForObject(sql, Long.class, args);
		return count == null ? 0 : count;
	}

	protected long auditCount(UUID tenantId, String action) {
		return count("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = ?", tenantId, action);
	}

}
