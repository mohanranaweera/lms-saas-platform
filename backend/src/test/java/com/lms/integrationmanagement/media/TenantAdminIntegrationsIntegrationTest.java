package com.lms.integrationmanagement.media;

import static com.lms.integrationmanagement.media.FakeMediaProviderServer.GOOD_BUNNY_KEY;
import static com.lms.integrationmanagement.media.FakeMediaProviderServer.GOOD_BUNNY_LIBRARY;
import static com.lms.integrationmanagement.media.FakeMediaProviderServer.GOOD_DYNTUBE_KEY;
import static org.assertj.core.api.Assertions.assertThat;

import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.tenantmanagement.domain.Tenant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * MEDIA-INT-01: the Tenant Admin integrations page - the tenant-wide
 * DynTube/Bunny.net accounts, the video host, oversight of teachers'
 * connections, permissions, and tenant isolation.
 */
class TenantAdminIntegrationsIntegrationTest extends MediaIntegrationTestSupport {

	private record AdminCtx(Tenant tenant, String host, String adminToken, TenantUser teacher, String teacherToken) {
	}

	private AdminCtx seedTenant(String prefix) {
		Tenant tenant = seedActiveTenant(uniqueSubdomain(prefix));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		return new AdminCtx(tenant, host, loginAndGetToken(host, "admin@example.test"), teacher,
				loginAndGetToken(host, "teacher@example.test"));
	}

	private static Map<String, Object> bunny(String apiKey, String libraryId, String cdnHostname) {
		Map<String, Object> body = new HashMap<>();
		body.put("apiKey", apiKey);
		body.put("libraryId", libraryId);
		body.put("cdnHostname", cdnHostname);
		return body;
	}

	@Test
	void dynTubeKeysAreVerifiedBeforeBeingStoredAndAreNeverReturned() {
		AdminCtx ctx = seedTenant("ta-dyntube");

		MvcResult missing = adminPut(ctx.host(), ctx.adminToken(), "/dyntube", Map.of());
		assertThat(status(missing)).isEqualTo(400);
		assertThat(errorCode(missing)).isEqualTo("VALIDATION_ERROR");

		MvcResult rejected = adminPut(ctx.host(), ctx.adminToken(), "/dyntube", Map.of("apiKey", "wrong-key"));
		assertThat(status(rejected)).isEqualTo(400);
		assertThat(errorCode(rejected)).isEqualTo("PROVIDER_CREDENTIALS_INVALID");
		assertThat(json(rejected).path("error").path("fieldErrors").path(0).path("field").asString())
			.isEqualTo("apiKey");
		assertThat(connectionCount(ctx.tenant().getId())).isZero();

		MvcResult saved = adminPut(ctx.host(), ctx.adminToken(), "/dyntube", Map.of("apiKey", GOOD_DYNTUBE_KEY));
		assertThat(status(saved)).isEqualTo(200);
		JsonNode view = json(saved).path("data");
		assertThat(view.path("status").asString()).isEqualTo("ACTIVE");
		assertThat(view.path("scope").asString()).isEqualTo("TENANT");
		assertThat(view.path("secretConfigured").asBoolean()).isTrue();
		assertThat(rawContent(saved)).doesNotContain(GOOD_DYNTUBE_KEY);
		assertThat(rawContent(adminOverview(ctx.host(), ctx.adminToken()))).doesNotContain(GOOD_DYNTUBE_KEY);

		// A blank key keeps the stored key - no new row.
		MvcResult unchanged = adminPut(ctx.host(), ctx.adminToken(), "/dyntube", Map.of());
		assertThat(json(unchanged).path("data").path("id").asString()).isEqualTo(view.path("id").asString());
		assertThat(connectionCount(ctx.tenant().getId())).isEqualTo(1);

		// Replacing the key wipes the old row's credentials (security review S2).
		UUID firstId = UUID.fromString(view.path("id").asString());
		assertThat(status(adminPut(ctx.host(), ctx.adminToken(), "/dyntube", Map.of("apiKey", GOOD_DYNTUBE_KEY))))
			.isEqualTo(200);
		assertThat(connectionStatus(firstId)).isEqualTo("DISCONNECTED");
		assertThat(credentialsOf(firstId)).isEqualTo("wiped");
	}

	@Test
	void bunnyKeyLibraryAndHostnameAreAllValidated() {
		AdminCtx ctx = seedTenant("ta-bunny");

		MvcResult badHost = adminPut(ctx.host(), ctx.adminToken(), "/bunny",
				bunny(GOOD_BUNNY_KEY, GOOD_BUNNY_LIBRARY, "http://evil.example.test/x"));
		assertThat(status(badHost)).isEqualTo(400);
		assertThat(json(badHost).path("error").path("fieldErrors").path(0).path("field").asString())
			.isEqualTo("cdnHostname");

		MvcResult badLibrary = adminPut(ctx.host(), ctx.adminToken(), "/bunny",
				bunny(GOOD_BUNNY_KEY, "99999", "vz-abc.b-cdn.net"));
		assertThat(status(badLibrary)).isEqualTo(400);
		assertThat(json(badLibrary).path("error").path("fieldErrors").path(0).path("field").asString())
			.isEqualTo("libraryId");

		MvcResult badKey = adminPut(ctx.host(), ctx.adminToken(), "/bunny",
				bunny("wrong-key", GOOD_BUNNY_LIBRARY, "vz-abc.b-cdn.net"));
		assertThat(errorCode(badKey)).isEqualTo("PROVIDER_CREDENTIALS_INVALID");
		assertThat(connectionCount(ctx.tenant().getId())).isZero();

		MvcResult saved = adminPut(ctx.host(), ctx.adminToken(), "/bunny",
				bunny(GOOD_BUNNY_KEY, GOOD_BUNNY_LIBRARY, "vz-abc.b-cdn.net"));
		assertThat(status(saved)).isEqualTo(200);
		JsonNode settings = json(saved).path("data").path("settings");
		assertThat(settings.path("libraryId").asString()).isEqualTo(GOOD_BUNNY_LIBRARY);
		assertThat(settings.path("cdnHostname").asString()).isEqualTo("vz-abc.b-cdn.net");
		assertThat(settings.path("tokenAuthConfigured").asBoolean()).isFalse();
		assertThat(rawContent(saved)).doesNotContain(GOOD_BUNNY_KEY);
		// The CDN hostname is never used as a request target.
		assertThat(PROVIDERS.requests()).noneMatch(r -> r.path().contains("b-cdn"));
	}

	@Test
	void theVideoHostNeedsAnActiveAccountAndThatAccountCannotBeDisconnectedWhileInUse() {
		AdminCtx ctx = seedTenant("ta-host");

		MvcResult notConnected = adminPut(ctx.host(), ctx.adminToken(), "/video-host", Map.of("videoHost", "BUNNY"));
		assertThat(status(notConnected)).isEqualTo(409);
		assertThat(errorCode(notConnected)).isEqualTo("PROVIDER_NOT_CONNECTED");

		UUID bunnyId = UUID.fromString(json(adminPut(ctx.host(), ctx.adminToken(), "/bunny",
				bunny(GOOD_BUNNY_KEY, GOOD_BUNNY_LIBRARY, "vz-abc.b-cdn.net"))).path("data").path("id").asString());
		assertThat(status(adminPut(ctx.host(), ctx.adminToken(), "/video-host", Map.of("videoHost", "BUNNY"))))
			.isEqualTo(200);
		assertThat(json(adminOverview(ctx.host(), ctx.adminToken())).path("data").path("videoHost").asString())
			.isEqualTo("BUNNY");

		assertThat(status(adminDisconnect(ctx.host(), ctx.adminToken(), bunnyId, null))).isEqualTo(409);

		assertThat(status(adminPut(ctx.host(), ctx.adminToken(), "/video-host", Map.of("videoHost", "NONE"))))
			.isEqualTo(200);
		assertThat(status(adminDisconnect(ctx.host(), ctx.adminToken(), bunnyId, null))).isEqualTo(200);
	}

	@Test
	void adminSeesTeacherConnectionsWithoutTokensAndCanDisconnectThemWithAReason() {
		AdminCtx ctx = seedTenant("ta-oversight");
		connectTeacher(ctx.host(), ctx.teacherToken(), "zoom");
		UUID connectionId = currentConnectionId(ctx.teacher().getId(), "ZOOM");

		MvcResult overview = adminOverview(ctx.host(), ctx.adminToken());
		JsonNode row = json(overview).path("data").path("teacherConnections").path(0);
		assertThat(row.path("teacherId").asString()).isEqualTo(ctx.teacher().getId().toString());
		assertThat(row.path("connection").path("provider").asString()).isEqualTo("ZOOM");
		assertThat(rawContent(overview)).doesNotContain("zoom-at-").doesNotContain("zoom-rt-");

		MvcResult noReason = adminDisconnect(ctx.host(), ctx.adminToken(), connectionId, null);
		assertThat(status(noReason)).isEqualTo(400);

		MvcResult disconnected = adminDisconnect(ctx.host(), ctx.adminToken(), connectionId, "Teacher left");
		assertThat(status(disconnected)).isEqualTo(200);
		assertThat(json(disconnected).path("data").path("disconnectedBy").asString()).isEqualTo("TENANT_ADMIN");
		assertThat(jdbcTemplate.queryForObject(
				"SELECT reason FROM audit_log WHERE action = 'media_integration.disconnected' AND target_id = ?",
				String.class, connectionId))
			.isEqualTo("Teacher left");
	}

	@Test
	void onlyRolesWithTheIntegrationsPermissionCanUseTheAdminPage() {
		AdminCtx ctx = seedTenant("ta-perms");
		seedTenantUser(ctx.tenant().getId(), "finance@example.test", RAW_PASSWORD, Role.FINANCE_STAFF);
		seedTenantUser(ctx.tenant().getId(), "auditor@example.test", RAW_PASSWORD, Role.READ_ONLY_AUDITOR);
		String finance = loginAndGetToken(ctx.host(), "finance@example.test");
		String auditor = loginAndGetToken(ctx.host(), "auditor@example.test");

		assertThat(status(adminOverview(ctx.host(), finance))).isEqualTo(403);
		assertThat(status(adminPut(ctx.host(), finance, "/dyntube", Map.of("apiKey", GOOD_DYNTUBE_KEY))))
			.isEqualTo(403);
		assertThat(status(adminOverview(ctx.host(), ctx.teacherToken()))).isEqualTo(403);

		assertThat(status(adminOverview(ctx.host(), auditor))).isEqualTo(200);
		assertThat(status(adminPut(ctx.host(), auditor, "/teacher-providers",
				Map.of("zoomEnabled", false, "youtubeEnabled", false, "vimeoEnabled", false))))
			.isEqualTo(403);
		assertThat(connectionCount(ctx.tenant().getId())).isZero();
	}

	@Test
	void integrationsAreIsolatedBetweenTenants() {
		AdminCtx tenantA = seedTenant("ta-iso-a");
		UUID dyntubeA = UUID.fromString(json(adminPut(tenantA.host(), tenantA.adminToken(), "/dyntube",
				Map.of("apiKey", GOOD_DYNTUBE_KEY))).path("data").path("id").asString());
		connectTeacher(tenantA.host(), tenantA.teacherToken(), "zoom");
		UUID zoomA = currentConnectionId(tenantA.teacher().getId(), "ZOOM");

		AdminCtx tenantB = seedTenant("ta-iso-b");
		JsonNode overviewB = json(adminOverview(tenantB.host(), tenantB.adminToken())).path("data");
		assertThat(overviewB.path("dyntube").isNull()).isTrue();
		assertThat(overviewB.path("teacherConnections").size()).isZero();

		assertThat(status(adminDisconnect(tenantB.host(), tenantB.adminToken(), zoomA, "not yours"))).isEqualTo(404);
		assertThat(status(adminDisconnect(tenantB.host(), tenantB.adminToken(), dyntubeA, null))).isEqualTo(404);
		assertThat(status(adminTest(tenantB.host(), tenantB.adminToken(), dyntubeA))).isEqualTo(404);
		assertThat(status(adminImpact(tenantB.host(), tenantB.adminToken(), zoomA))).isEqualTo(404);
		// B choosing DynTube as video host is judged on B's own (missing) account.
		assertThat(status(adminPut(tenantB.host(), tenantB.adminToken(), "/video-host",
				Map.of("videoHost", "DYNTUBE"))))
			.isEqualTo(409);

		assertThat(connectionStatus(zoomA)).isEqualTo("ACTIVE");
		assertThat(connectionStatus(dyntubeA)).isEqualTo("ACTIVE");
	}

}
