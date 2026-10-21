package com.lms.videoaccessmanagement.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.lms.coursemanagement.CourseManagementTestSupport;
import com.lms.identityaccessservice.domain.Role;
import com.lms.tenantmanagement.domain.Tenant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * VIDEO Phase 3 - tenant video configuration: defaults, update + audit,
 * validation, roles and tenant isolation.
 */
class TenantVideoConfigurationIntegrationTest extends CourseManagementTestSupport {

	private static final String PATH = "/api/v1/tenant-admin/video-configuration";

	private record Ctx(Tenant tenant, String host, String adminToken, String teacherToken, String studentToken) {
	}

	private Ctx seedTenant(String prefix) {
		Tenant tenant = seedActiveTenant(uniqueSubdomain(prefix));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		seedActiveStudent(tenant.getId(), "student@example.test");
		String host = hostFor(tenant.getSubdomain());
		return new Ctx(tenant, host, loginAndGetToken(host, "admin@example.test"),
				loginAndGetToken(host, "teacher@example.test"), loginAndGetToken(host, "student@example.test"));
	}

	@Test
	void aTenantWithoutSettingsGetsThePlatformDefaults() {
		Ctx ctx = seedTenant("vc-defaults");

		JsonNode data = json(read(ctx.host(), ctx.adminToken())).path("data");

		assertThat(data.path("customized").asBoolean()).isFalse();
		JsonNode settings = data.path("settings");
		assertThat(settings.path("defaultProvider").asString()).isEqualTo("INTERNAL");
		assertThat(settings.path("defaultPrivacyStatus").asString()).isEqualTo("UNLISTED");
		assertThat(settings.path("maxConcurrentSessions").asInt()).isEqualTo(1);
		assertThat(settings.path("watchTimePolicy").asString()).isEqualTo("NONE");
		assertThat(settings.path("launchCodeTtlSeconds").asInt()).isEqualTo(45);
	}

	@Test
	void anUpdateIsStoredReturnedAndAuditedWithBeforeAndAfter() {
		Ctx ctx = seedTenant("vc-update");
		Map<String, Object> body = validBody();
		body.put("defaultProvider", "YOUTUBE");
		body.put("nativePlayerRequired", true);
		body.put("watchTimePolicy", "VIDEO_DURATION_MULTIPLIER");
		body.put("watchTimeMultiplier", 1.2);
		body.put("maxConcurrentSessions", 2);

		MvcResult saved = update(ctx.host(), ctx.adminToken(), body);
		assertThat(status(saved)).isEqualTo(200);
		JsonNode settings = json(read(ctx.host(), ctx.adminToken())).path("data").path("settings");
		assertThat(settings.path("defaultProvider").asString()).isEqualTo("YOUTUBE");
		assertThat(settings.path("nativePlayerRequired").asBoolean()).isTrue();
		assertThat(settings.path("watchTimeMultiplier").decimalValue()).isEqualByComparingTo("1.20");
		assertThat(settings.path("maxConcurrentSessions").asInt()).isEqualTo(2);

		body.put("maxConcurrentSessions", 3);
		assertThat(status(update(ctx.host(), ctx.adminToken(), body))).isEqualTo(200);

		List<String> audits = jdbcTemplate.queryForList(
				"SELECT metadata::text FROM audit_log WHERE action = 'video_configuration.updated' AND tenant_id = ? "
						+ "ORDER BY occurred_at",
				String.class, ctx.tenant().getId());
		assertThat(audits).hasSize(2);
		assertThat(audits.get(0)).contains("PLATFORM_DEFAULTS");
		assertThat(audits.get(1)).contains("\"maxConcurrentSessions\": 2").contains("\"maxConcurrentSessions\": 3");
	}

	@Test
	void watchTimeValuesThatDoNotBelongToThePolicyAreDropped() {
		Ctx ctx = seedTenant("vc-normalize");
		Map<String, Object> body = validBody();
		body.put("watchTimeMultiplier", 3.0);
		body.put("watchTimeFixedMinutes", 90);

		assertThat(status(update(ctx.host(), ctx.adminToken(), body))).isEqualTo(200);
		JsonNode settings = json(read(ctx.host(), ctx.adminToken())).path("data").path("settings");
		assertThat(settings.path("watchTimeMultiplier").isNull()).isTrue();
		assertThat(settings.path("watchTimeFixedMinutes").isNull()).isTrue();
	}

	@Test
	void invalidSettingsAreRejectedWithFieldErrors() {
		Ctx ctx = seedTenant("vc-invalid");

		assertInvalid(ctx, "watermarkOpacity", 0.9);
		assertInvalid(ctx, "maxConcurrentSessions", 6);
		assertInvalid(ctx, "launchCodeTtlSeconds", 120);
		assertInvalid(ctx, "defaultPrivacyStatus", "PUBLIC");
		assertInvalid(ctx, "watermarkFields", List.of());

		Map<String, Object> multiplierMissing = validBody();
		multiplierMissing.put("watchTimePolicy", "VIDEO_DURATION_MULTIPLIER");
		assertThat(status(update(ctx.host(), ctx.adminToken(), multiplierMissing))).isEqualTo(400);

		Map<String, Object> requiredWithoutEnabled = validBody();
		requiredWithoutEnabled.put("captureProtectionEnabled", false);
		requiredWithoutEnabled.put("captureProtectionRequired", true);
		assertThat(status(update(ctx.host(), ctx.adminToken(), requiredWithoutEnabled))).isEqualTo(400);

		assertThat(json(read(ctx.host(), ctx.adminToken())).path("data").path("customized").asBoolean()).isFalse();
	}

	@Test
	void teachersAndStudentsCannotReadOrChangeTheConfiguration() {
		Ctx ctx = seedTenant("vc-roles");

		assertThat(status(read(ctx.host(), ctx.teacherToken()))).isEqualTo(403);
		assertThat(status(update(ctx.host(), ctx.teacherToken(), validBody()))).isEqualTo(403);
		assertThat(status(read(ctx.host(), ctx.studentToken()))).isEqualTo(403);
		assertThat(status(update(ctx.host(), ctx.studentToken(), validBody()))).isEqualTo(403);
	}

	@Tag("cross-tenant")
	@Test
	void eachTenantOnlyEverReadsAndWritesItsOwnConfiguration() {
		Ctx a = seedTenant("vc-iso-a");
		Ctx b = seedTenant("vc-iso-b");
		Map<String, Object> aBody = validBody();
		aBody.put("maxConcurrentSessions", 4);
		assertThat(status(update(a.host(), a.adminToken(), aBody))).isEqualTo(200);

		assertThat(json(read(b.host(), b.adminToken())).path("data").path("customized").asBoolean()).isFalse();

		// A tenantId smuggled into B's request never redirects the write to A.
		Map<String, Object> bBody = validBody();
		bBody.put("maxConcurrentSessions", 5);
		bBody.put("tenantId", a.tenant().getId().toString());
		update(b.host(), b.adminToken(), bBody);

		assertThat(json(read(a.host(), a.adminToken())).path("data").path("settings").path("maxConcurrentSessions")
			.asInt()).isEqualTo(4);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM tenant_video_configuration WHERE tenant_id = ?", Integer.class,
				a.tenant().getId())).isEqualTo(1);
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	private void assertInvalid(Ctx ctx, String field, Object value) {
		Map<String, Object> body = validBody();
		body.put(field, value);
		MvcResult result = update(ctx.host(), ctx.adminToken(), body);
		assertThat(status(result)).as(field).isEqualTo(400);
		assertThat(rawContent(result)).as(field).contains(field);
	}

	private static Map<String, Object> validBody() {
		Map<String, Object> body = new HashMap<>();
		body.put("defaultProvider", "INTERNAL");
		body.put("defaultPrivacyStatus", "UNLISTED");
		body.put("uploadsEnabled", true);
		body.put("nativePlayerRequired", false);
		body.put("watermarkEnabled", true);
		body.put("watermarkOpacity", 0.3);
		body.put("watermarkMovementIntervalSeconds", 20);
		body.put("watermarkFields", List.of("STUDENT_DISPLAY_ID", "SESSION_ID"));
		body.put("captureProtectionEnabled", true);
		body.put("captureProtectionRequired", false);
		body.put("maxConcurrentSessions", 1);
		body.put("watchTimePolicy", "NONE");
		body.put("launchCodeTtlSeconds", 30);
		body.put("heartbeatIntervalSeconds", 15);
		return body;
	}

	private MvcResult read(String host, String token) {
		return perform(authenticated(get(PATH), host, token));
	}

	private MvcResult update(String host, String token, Map<String, Object> body) {
		return perform(authenticated(put(PATH).contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(body)), host, token));
	}

	private static int status(MvcResult result) {
		return result.getResponse().getStatus();
	}

	private JsonNode json(MvcResult result) {
		return objectMapper.readTree(rawContent(result));
	}

}
