package com.lms.integrationmanagement.media;

import static com.lms.integrationmanagement.media.FakeMediaProviderServer.GOOD_BUNNY_KEY;
import static com.lms.integrationmanagement.media.FakeMediaProviderServer.GOOD_BUNNY_ACCOUNT_KEY;
import static com.lms.integrationmanagement.media.FakeMediaProviderServer.GOOD_DYNTUBE_KEY;
import static org.assertj.core.api.Assertions.assertThat;

import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.integrationmanagement.api.MediaProvider;
import com.lms.integrationmanagement.service.MediaCredentialsCodec;
import com.lms.integrationmanagement.service.MediaCredentialsCodec.CredentialContext;
import com.lms.integrationmanagement.service.MediaCredentialsCodec.StoredCredentials;
import com.lms.tenantmanagement.domain.Tenant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * MEDIA-INT-01: the Tenant Admin integrations page - the tenant-wide
 * DynTube/Bunny.net accounts, the video host, oversight of teachers'
 * connections, permissions, and tenant isolation.
 */
class TenantAdminIntegrationsIntegrationTest extends MediaIntegrationTestSupport {

	@Autowired
	private MediaCredentialsCodec codec;

	/** A MEDIA-INT-01 library-only key, sealed exactly as the app stores it. */
	private String sealLegacyBunnyKey(UUID tenantId) {
		return codec.seal(StoredCredentials.apiKey(GOOD_BUNNY_KEY, null),
				new CredentialContext(tenantId, MediaProvider.BUNNY, null));
	}

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
	void bunnyNeedsOnlyAnAccountApiKey() {
		AdminCtx ctx = seedTenant("ta-bunny");

		// MEDIA-INT-01 fields are refused by name, not silently ignored.
		Map<String, Object> legacy = new HashMap<>();
		legacy.put("apiKey", GOOD_BUNNY_ACCOUNT_KEY);
		legacy.put("libraryId", "12345");
		MvcResult legacyFields = adminPut(ctx.host(), ctx.adminToken(), "/bunny", legacy);
		assertThat(status(legacyFields)).isEqualTo(400);
		assertThat(errorCode(legacyFields)).isEqualTo("VALIDATION_ERROR");
		assertThat(json(legacyFields).path("error").path("fieldErrors").path(0).path("field").asString())
			.isEqualTo("libraryId");

		assertThat(status(adminPut(ctx.host(), ctx.adminToken(), "/bunny", Map.of()))).isEqualTo(400);

		// A library-only Stream key is not an account key.
		MvcResult libraryKey = adminPut(ctx.host(), ctx.adminToken(), "/bunny", Map.of("apiKey", GOOD_BUNNY_KEY));
		assertThat(status(libraryKey)).isEqualTo(400);
		assertThat(errorCode(libraryKey)).isEqualTo("PROVIDER_CREDENTIALS_INVALID");
		assertThat(json(libraryKey).path("error").path("fieldErrors").path(0).path("message").asString())
			.contains("account API key");
		assertThat(connectionCount(ctx.tenant().getId())).isZero();

		MvcResult saved = adminPut(ctx.host(), ctx.adminToken(), "/bunny", Map.of("apiKey", GOOD_BUNNY_ACCOUNT_KEY));
		assertThat(status(saved)).isEqualTo(200);
		JsonNode view = json(saved).path("data");
		assertThat(view.path("status").asString()).isEqualTo("ACTIVE");
		assertThat(view.path("accountLabel").asString()).isEqualTo("Bunny.net account");
		assertThat(view.path("settings").path("keyType").asString()).isEqualTo("ACCOUNT");
		assertThat(view.path("settings").has("libraryId")).isFalse();
		assertThat(rawContent(saved)).doesNotContain(GOOD_BUNNY_ACCOUNT_KEY);
		// Verified against the account API (the earlier call was the rejected library key).
		assertThat(PROVIDERS.requests("GET", "/videolibrary")).last()
			.matches(r -> GOOD_BUNNY_ACCOUNT_KEY.equals(r.accessKey()));

		// A blank key keeps the stored key - no new row.
		MvcResult unchanged = adminPut(ctx.host(), ctx.adminToken(), "/bunny", Map.of());
		assertThat(json(unchanged).path("data").path("id").asString()).isEqualTo(view.path("id").asString());
		assertThat(connectionCount(ctx.tenant().getId())).isEqualTo(1);
	}

	@Test
	void aLegacyLibraryKeyConnectionNeedsReconnectOnItsNextTest() {
		AdminCtx ctx = seedTenant("ta-bunny-legacy");
		// Saved under MEDIA-INT-01 with a library key (the account API now rejects it).
		UUID legacyId = UUID.fromString(json(adminPut(ctx.host(), ctx.adminToken(), "/bunny",
				Map.of("apiKey", GOOD_BUNNY_ACCOUNT_KEY))).path("data").path("id").asString());
		jdbcTemplate.update("UPDATE media_provider_connection SET credentials_ciphertext = ? WHERE id = ?",
				sealLegacyBunnyKey(ctx.tenant().getId()), legacyId);

		MvcResult tested = adminTest(ctx.host(), ctx.adminToken(), legacyId);
		assertThat(status(tested)).isEqualTo(200);
		assertThat(json(tested).path("data").path("ok").asBoolean()).isFalse();
		assertThat(connectionStatus(legacyId)).isEqualTo("NEEDS_RECONNECT");
	}

	@Test
	void theVideoHostNeedsAnActiveAccountAndThatAccountCannotBeDisconnectedWhileInUse() {
		AdminCtx ctx = seedTenant("ta-host");

		MvcResult notConnected = adminPut(ctx.host(), ctx.adminToken(), "/video-host", Map.of("videoHost", "BUNNY"));
		assertThat(status(notConnected)).isEqualTo(409);
		assertThat(errorCode(notConnected)).isEqualTo("PROVIDER_NOT_CONNECTED");

		UUID bunnyId = UUID.fromString(json(adminPut(ctx.host(), ctx.adminToken(), "/bunny",
				Map.of("apiKey", GOOD_BUNNY_ACCOUNT_KEY))).path("data").path("id").asString());
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
