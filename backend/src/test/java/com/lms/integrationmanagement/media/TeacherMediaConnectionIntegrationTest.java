package com.lms.integrationmanagement.media;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.tenantmanagement.domain.Tenant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * MEDIA-INT-01: a teacher connecting their OWN Zoom/YouTube/Vimeo accounts
 * via OAuth - the single-use state, what is stored, token refresh,
 * disconnect, and teacher/tenant isolation.
 */
class TeacherMediaConnectionIntegrationTest extends MediaIntegrationTestSupport {

	private static final String ERROR_URL = "https://app.example.test/integration-error";

	private record TeacherCtx(Tenant tenant, String host, TenantUser teacher, String token, String adminToken) {
	}

	private TeacherCtx seedTeacher(String prefix) {
		Tenant tenant = seedActiveTenant(uniqueSubdomain(prefix));
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String host = hostFor(tenant.getSubdomain());
		return new TeacherCtx(tenant, host, teacher, loginAndGetToken(host, "teacher@example.test"),
				loginAndGetToken(host, "admin@example.test"));
	}

	@Test
	void connectingZoomStoresAnEncryptedActiveConnectionAndRedirectsBackToTheTenantSite() {
		TeacherCtx ctx = seedTeacher("mi-zoom");

		MvcResult begin = beginConnect(ctx.host(), ctx.token(), "zoom");
		assertThat(status(begin)).isEqualTo(200);
		String authorizationUrl = json(begin).path("data").path("authorizationUrl").asString();
		assertThat(authorizationUrl).contains("code_challenge=").contains("code_challenge_method=S256")
			.contains("redirect_uri=https://app.example.test/api/v1/integrations/oauth/zoom/callback");

		MvcResult callback = callback("zoom", "auth-code-1", null, stateOf(authorizationUrl));
		assertThat(status(callback)).isEqualTo(302);
		String location = callback.getResponse().getHeader(HttpHeaders.LOCATION);
		assertThat(location).startsWith("https://" + ctx.tenant().getSubdomain()
				+ ".example.test/teacher/settings/integrations?pending=")
			.endsWith("&provider=zoom");
		// Security review S1: the callback alone saves nothing.
		assertThat(connectionCount(ctx.tenant().getId())).isZero();

		MvcResult confirmed = confirmPending(ctx.host(), ctx.token(), pendingIdOf(location));
		assertThat(status(confirmed)).isEqualTo(200);
		assertThat(rawContent(confirmed)).doesNotContain("zoom-at-").doesNotContain("zoom-rt-");

		UUID connectionId = currentConnectionId(ctx.teacher().getId(), "ZOOM");
		assertThat(connectionStatus(connectionId)).isEqualTo("ACTIVE");
		String ciphertext = jdbcTemplate.queryForObject(
				"SELECT credentials_ciphertext FROM media_provider_connection WHERE id = ?", String.class, connectionId);
		assertThat(ciphertext).startsWith("v1:").doesNotContain("zoom-at-").doesNotContain("zoom-rt-");

		MvcResult overview = teacherOverview(ctx.host(), ctx.token());
		assertThat(status(overview)).isEqualTo(200);
		JsonNode zoom = json(overview).path("data").path("providers").path(0);
		assertThat(zoom.path("provider").asString()).isEqualTo("ZOOM");
		assertThat(zoom.path("enabledByTenant").asBoolean()).isTrue();
		assertThat(zoom.path("available").asBoolean()).isTrue();
		assertThat(zoom.path("connection").path("status").asString()).isEqualTo("ACTIVE");
		assertThat(zoom.path("connection").path("accountType").asString()).isEqualTo("LICENSED");
		assertThat(zoom.path("connection").path("accountLabel").asString()).isEqualTo("zoom.teacher@example.test");
		assertThat(rawContent(overview)).doesNotContain("zoom-at-").doesNotContain("zoom-rt-").doesNotContain("v1:");

		// Audited, without any secret.
		String metadata = jdbcTemplate.queryForObject(
				"SELECT metadata::text FROM audit_log WHERE action = 'media_integration.connected' AND target_id = ?",
				String.class, connectionId);
		assertThat(metadata).contains("ZOOM").doesNotContain("zoom-at-");
	}

	@Test
	void aStateCanBeUsedOnlyOnceAndUnknownStatesStoreNothing() {
		TeacherCtx ctx = seedTeacher("mi-replay");
		String url = json(beginConnect(ctx.host(), ctx.token(), "zoom")).path("data").path("authorizationUrl")
			.asString();
		String state = stateOf(url);

		assertThat(callback("zoom", "code-1", null, state).getResponse().getHeader(HttpHeaders.LOCATION))
			.contains("?pending=");
		assertThat(callback("zoom", "code-2", null, state).getResponse().getHeader(HttpHeaders.LOCATION))
			.isEqualTo(ERROR_URL);
		assertThat(callback("zoom", "code-3", null, "made-up-state").getResponse().getHeader(HttpHeaders.LOCATION))
			.isEqualTo(ERROR_URL);
		assertThat(callback("zoom", "code-4", null, null).getResponse().getHeader(HttpHeaders.LOCATION))
			.isEqualTo(ERROR_URL);
		assertThat(connectionCount(ctx.tenant().getId())).isZero();
	}

	@Test
	void aStateIssuedForOneProviderCannotCompleteAnother() {
		TeacherCtx ctx = seedTeacher("mi-mismatch");
		String url = json(beginConnect(ctx.host(), ctx.token(), "zoom")).path("data").path("authorizationUrl")
			.asString();

		MvcResult result = callback("vimeo", "code", null, stateOf(url));

		assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION)).isEqualTo(ERROR_URL);
		assertThat(connectionCount(ctx.tenant().getId())).isZero();
	}

	@Test
	void deniedConsentStoresNothing() {
		TeacherCtx ctx = seedTeacher("mi-denied");
		String url = json(beginConnect(ctx.host(), ctx.token(), "vimeo")).path("data").path("authorizationUrl")
			.asString();

		MvcResult result = callback("vimeo", null, "access_denied", stateOf(url));

		assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION))
			.endsWith("/teacher/settings/integrations?error=denied&provider=vimeo");
		assertThat(connectionCount(ctx.tenant().getId())).isZero();
	}

	@Test
	void reconnectingReplacesThePreviousConnectionWithoutDeletingIt() {
		TeacherCtx ctx = seedTeacher("mi-reconnect");
		connectTeacher(ctx.host(), ctx.token(), "zoom");
		UUID first = currentConnectionId(ctx.teacher().getId(), "ZOOM");

		connectTeacher(ctx.host(), ctx.token(), "zoom");

		UUID second = currentConnectionId(ctx.teacher().getId(), "ZOOM");
		assertThat(second).isNotEqualTo(first);
		assertThat(connectionStatus(first)).isEqualTo("DISCONNECTED");
		// Security review S2: the replaced row keeps no credentials.
		assertThat(credentialsOf(first)).isEqualTo("wiped");
		assertThat(jdbcTemplate.queryForObject(
				"SELECT disconnected_by FROM media_provider_connection WHERE id = ?", String.class, first))
			.isEqualTo("SYSTEM");
		assertThat(connectionCount(ctx.tenant().getId())).isEqualTo(2);
	}

	@Test
	void youTubeIsNoLongerATeacherProviderButVimeoStillIs() {
		// ADR-018 Amendment 4 (VIDEO Phase 5b): the institute channel is the only YouTube connection.
		TeacherCtx ctx = seedTeacher("mi-yt-vimeo");

		assertThat(status(beginConnect(ctx.host(), ctx.token(), "youtube"))).isEqualTo(404);
		assertThat(connectTeacher(ctx.host(), ctx.token(), "vimeo").path("status").asString()).isEqualTo("ACTIVE");

		JsonNode providers = json(teacherOverview(ctx.host(), ctx.token())).path("data").path("providers");
		assertThat(providers).hasSize(2);
		assertThat(providers.toString()).doesNotContain("YOUTUBE");
		assertThat(providers.path(0).path("connection").isNull()).isTrue();
		assertThat(providers.path(1).path("connection").path("accountLabel").asString()).isEqualTo("Vimeo Teacher");
		assertThat(rawContent(adminPut(ctx.host(), ctx.adminToken(), "/teacher-providers",
				Map.of("zoomEnabled", true, "youtubeEnabled", true, "vimeoEnabled", true)))).doesNotContain("youtube");
	}

	@Test
	void onlyTeachersCanUseTeacherIntegrationsAndOnlyForTeacherProviders() {
		TeacherCtx ctx = seedTeacher("mi-roles");

		assertThat(status(teacherOverview(ctx.host(), ctx.adminToken()))).isEqualTo(403);
		assertThat(status(beginConnect(ctx.host(), ctx.adminToken(), "zoom"))).isEqualTo(403);
		assertThat(status(beginConnect(ctx.host(), ctx.token(), "dyntube"))).isEqualTo(404);
		assertThat(status(beginConnect(ctx.host(), ctx.token(), "bunny"))).isEqualTo(404);
		assertThat(status(beginConnect(ctx.host(), ctx.token(), "not-a-provider"))).isEqualTo(404);
	}

	@Test
	void aProviderSwitchedOffByTheInstituteCannotBeConnected() {
		TeacherCtx ctx = seedTeacher("mi-disabled");
		assertThat(status(adminPut(ctx.host(), ctx.adminToken(), "/teacher-providers",
				Map.of("zoomEnabled", false, "youtubeEnabled", true, "vimeoEnabled", true))))
			.isEqualTo(200);

		MvcResult result = beginConnect(ctx.host(), ctx.token(), "zoom");

		assertThat(status(result)).isEqualTo(409);
		assertThat(errorCode(result)).isEqualTo("PROVIDER_DISABLED_BY_TENANT");
		assertThat(json(teacherOverview(ctx.host(), ctx.token())).path("data").path("providers").path(0)
			.path("enabledByTenant").asBoolean()).isFalse();
	}

	@Test
	void anExpiringTokenIsRefreshedServerSideAndTheNewTokensAreStored() {
		TeacherCtx ctx = seedTeacher("mi-refresh");
		connectTeacher(ctx.host(), ctx.token(), "zoom");
		UUID id = currentConnectionId(ctx.teacher().getId(), "ZOOM");
		String before = jdbcTemplate.queryForObject(
				"SELECT credentials_ciphertext FROM media_provider_connection WHERE id = ?", String.class, id);
		jdbcTemplate.update(
				"UPDATE media_provider_connection SET access_token_expires_at = now() - interval '1 hour' WHERE id = ?",
				id);

		MvcResult result = teacherTest(ctx.host(), ctx.token(), id);

		assertThat(json(result).path("data").path("ok").asBoolean()).isTrue();
		assertThat(PROVIDERS.requests("POST", "/oauth/token"))
			.anySatisfy(r -> assertThat(r.body()).contains("grant_type=refresh_token"));
		assertThat(jdbcTemplate.queryForObject(
				"SELECT access_token_expires_at > now() FROM media_provider_connection WHERE id = ?", Boolean.class, id))
			.isTrue();
		assertThat(jdbcTemplate.queryForObject(
				"SELECT credentials_ciphertext FROM media_provider_connection WHERE id = ?", String.class, id))
			.isNotEqualTo(before);
	}

	@Test
	void aRejectedRefreshMarksTheConnectionForReconnect() {
		TeacherCtx ctx = seedTeacher("mi-revoked");
		connectTeacher(ctx.host(), ctx.token(), "zoom");
		UUID id = currentConnectionId(ctx.teacher().getId(), "ZOOM");
		jdbcTemplate.update(
				"UPDATE media_provider_connection SET access_token_expires_at = now() - interval '1 hour' WHERE id = ?",
				id);
		PROVIDERS.setRejectRefresh(true);

		JsonNode result = json(teacherTest(ctx.host(), ctx.token(), id)).path("data");

		assertThat(result.path("ok").asBoolean()).isFalse();
		assertThat(result.path("status").asString()).isEqualTo("NEEDS_RECONNECT");
		assertThat(connectionStatus(id)).isEqualTo("NEEDS_RECONNECT");
	}

	@Test
	void disconnectingMarksTheConnectionDisconnectedAndRevokesItAtTheProvider() {
		TeacherCtx ctx = seedTeacher("mi-disconnect");
		connectTeacher(ctx.host(), ctx.token(), "zoom");
		UUID id = currentConnectionId(ctx.teacher().getId(), "ZOOM");

		MvcResult result = teacherDisconnect(ctx.host(), ctx.token(), id);

		assertThat(status(result)).isEqualTo(200);
		assertThat(json(result).path("data").path("status").asString()).isEqualTo("DISCONNECTED");
		assertThat(json(result).path("data").path("disconnectedBy").asString()).isEqualTo("OWNER");
		assertThat(PROVIDERS.requests("POST", "/oauth/revoke")).hasSize(1);
		assertThat(credentialsOf(id)).isEqualTo("wiped");
		assertThat(json(result).path("data").path("secretConfigured").asBoolean()).isFalse();
		assertThat(status(teacherDisconnect(ctx.host(), ctx.token(), id))).isEqualTo(409);
	}

	// ------------------------------------------------------------------
	// Security review S1/S3 - only the teacher who started it can confirm
	// ------------------------------------------------------------------

	@Test
	void anotherTeacherCannotConfirmAndTheGrantIsRevoked() {
		TeacherCtx ctx = seedTeacher("mi-confirm-other");
		seedTenantUser(ctx.tenant().getId(), "teacher2@example.test", RAW_PASSWORD, Role.TEACHER);
		String otherTeacher = loginAndGetToken(ctx.host(), "teacher2@example.test");
		String pendingId = pendingIdOf(authorizeTeacher(ctx.host(), ctx.token(), "zoom"));

		MvcResult stolen = confirmPending(ctx.host(), otherTeacher, pendingId);

		assertThat(status(stolen)).isEqualTo(404);
		assertThat(PROVIDERS.requests("POST", "/oauth/revoke")).hasSize(1);
		// Consumed: not even the right teacher can use it afterwards.
		assertThat(status(confirmPending(ctx.host(), ctx.token(), pendingId))).isEqualTo(404);
		assertThat(connectionCount(ctx.tenant().getId())).isZero();
	}

	@Test
	void aTeacherInAnotherTenantCannotConfirm() {
		TeacherCtx tenantA = seedTeacher("mi-confirm-a");
		TeacherCtx tenantB = seedTeacher("mi-confirm-b");
		String pendingId = pendingIdOf(authorizeTeacher(tenantA.host(), tenantA.token(), "zoom"));

		assertThat(status(confirmPending(tenantB.host(), tenantB.token(), pendingId))).isEqualTo(404);
		assertThat(connectionCount(tenantA.tenant().getId())).isZero();
		assertThat(connectionCount(tenantB.tenant().getId())).isZero();
	}

	@Test
	void aTeacherSuspendedBeforeConfirmingGetsNoConnection() {
		TeacherCtx ctx = seedTeacher("mi-confirm-suspended");
		String pendingId = pendingIdOf(authorizeTeacher(ctx.host(), ctx.token(), "zoom"));
		suspendTenantUser(ctx.teacher().getId());

		assertThat(status(confirmPending(ctx.host(), ctx.token(), pendingId))).isIn(401, 403);
		assertThat(connectionCount(ctx.tenant().getId())).isZero();
	}

	@Test
	void aProviderSwitchedOffBeforeConfirmingIsRefusedAndRevoked() {
		TeacherCtx ctx = seedTeacher("mi-confirm-disabled");
		String pendingId = pendingIdOf(authorizeTeacher(ctx.host(), ctx.token(), "zoom"));
		adminPut(ctx.host(), ctx.adminToken(), "/teacher-providers",
				Map.of("zoomEnabled", false, "youtubeEnabled", true, "vimeoEnabled", true));

		MvcResult result = confirmPending(ctx.host(), ctx.token(), pendingId);

		assertThat(status(result)).isEqualTo(409);
		assertThat(errorCode(result)).isEqualTo("PROVIDER_DISABLED_BY_TENANT");
		assertThat(PROVIDERS.requests("POST", "/oauth/revoke")).hasSize(1);
		assertThat(connectionCount(ctx.tenant().getId())).isZero();
	}

	@Test
	void anotherTeachersOrAnotherTenantsConnectionIsNotFound() {
		TeacherCtx tenantA = seedTeacher("mi-iso-a");
		connectTeacher(tenantA.host(), tenantA.token(), "zoom");
		UUID connectionA = currentConnectionId(tenantA.teacher().getId(), "ZOOM");

		// Same tenant, different teacher.
		seedTenantUser(tenantA.tenant().getId(), "teacher2@example.test", RAW_PASSWORD, Role.TEACHER);
		String otherTeacher = loginAndGetToken(tenantA.host(), "teacher2@example.test");
		assertThat(status(teacherTest(tenantA.host(), otherTeacher, connectionA))).isEqualTo(404);
		assertThat(status(teacherDisconnect(tenantA.host(), otherTeacher, connectionA))).isEqualTo(404);

		// Different tenant.
		TeacherCtx tenantB = seedTeacher("mi-iso-b");
		assertThat(status(teacherTest(tenantB.host(), tenantB.token(), connectionA))).isEqualTo(404);
		assertThat(status(teacherImpact(tenantB.host(), tenantB.token(), connectionA))).isEqualTo(404);
		assertThat(status(teacherDisconnect(tenantB.host(), tenantB.token(), connectionA))).isEqualTo(404);
		assertThat(json(teacherOverview(tenantB.host(), tenantB.token())).path("data").path("providers").path(0)
			.path("connection").isNull()).isTrue();

		assertThat(connectionStatus(connectionA)).isEqualTo("ACTIVE");
	}

}
