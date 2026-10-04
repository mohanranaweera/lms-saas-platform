package com.lms.integrationmanagement.media;

import static com.lms.integrationmanagement.media.FakeMediaProviderServer.TENANT_CHANNEL_SCOPES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.lms.identityaccessservice.domain.Role;
import com.lms.integrationmanagement.api.MediaProvider;
import com.lms.integrationmanagement.service.MediaCredentialsCodec;
import com.lms.integrationmanagement.service.MediaCredentialsCodec.CredentialContext;
import com.lms.tenantmanagement.domain.Tenant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * VIDEO Phase 3 - the tenant's own YouTube channel (ADR-018 Amendment 2):
 * connect → preview → confirm, scope enforcement, who may confirm, channel
 * replacement, disconnect, and tenant isolation (plan §32 T6/T11, Q-003).
 */
class TenantVideoProviderIntegrationTest extends MediaIntegrationTestSupport {

	private static final String BASE = "/api/v1/tenant-admin/video-providers";

	@Autowired
	private MediaCredentialsCodec codec;

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

	// ------------------------------------------------------------------
	// Happy path
	// ------------------------------------------------------------------

	@Test
	void tenantAdminConnectsPreviewsAndConfirmsTheTenantChannel() {
		Ctx ctx = seedTenant("vp-happy");
		PROVIDERS.setGoogleScope(TENANT_CHANNEL_SCOPES);
		PROVIDERS.setYoutubeChannel("UC-institute-a", "Institute A");

		MvcResult begin = connect(ctx.host(), ctx.adminToken());
		assertThat(status(begin)).isEqualTo(200);
		String url = json(begin).path("data").path("authorizationUrl").asString();
		assertThat(url).contains("youtube.upload").contains("youtube.readonly").doesNotContain("include_granted_scopes");

		String location = callback("youtube", "code-1", null, stateOf(url)).getResponse()
			.getHeader(HttpHeaders.LOCATION);
		assertThat(location).contains("/tenant-admin/settings/video-providers");
		String pendingId = pendingIdOf(location);

		JsonNode preview = json(preview(ctx.host(), ctx.adminToken(), pendingId)).path("data");
		assertThat(preview.path("channelId").asString()).isEqualTo("UC-institute-a");
		assertThat(preview.path("channelTitle").asString()).isEqualTo("Institute A");
		assertThat(preview.path("replacesCurrentChannel").asBoolean()).isFalse();

		MvcResult confirmed = confirm(ctx.host(), ctx.adminToken(), pendingId, false);
		assertThat(status(confirmed)).isEqualTo(200);
		JsonNode account = json(confirmed).path("data");
		assertThat(account.path("status").asString()).isEqualTo("CONNECTED");
		assertThat(account.path("connection").path("scope").asString()).isEqualTo("TENANT");
		assertThat(account.path("connection").path("accountLabel").asString()).isEqualTo("Institute A");
		assertThat(account.path("connectedByUserId").asString()).isNotBlank();
		assertThat(rawContent(confirmed)).doesNotContain("google-at-").doesNotContain("google-rt-");

		UUID connectionId = UUID.fromString(account.path("connection").path("id").asString());
		assertThat(jdbcTemplate.queryForObject(
				"SELECT owner_user_id IS NULL AND scope = 'TENANT' FROM media_provider_connection WHERE id = ?",
				Boolean.class, connectionId)).isTrue();
		assertThat(auditCount("video_provider.connected", connectionId)).isEqualTo(1);

		MvcResult list = list(ctx.host(), ctx.adminToken());
		assertThat(json(list).path("data").path(0).path("status").asString()).isEqualTo("CONNECTED");
		assertThat(rawContent(list)).doesNotContain("google-at-").doesNotContain("google-rt-");
	}

	@Test
	void anAuthorizationWithoutTheUploadScopeIsDiscardedAndRevoked() {
		Ctx ctx = seedTenant("vp-scope");
		// Default fake grant = youtube.readonly only (the admin unticked upload).
		String url = json(connect(ctx.host(), ctx.adminToken())).path("data").path("authorizationUrl").asString();
		String location = callback("youtube", "code-1", null, stateOf(url)).getResponse()
			.getHeader(HttpHeaders.LOCATION);

		assertThat(location).contains("error=missing_scopes");
		assertThat(pendingIdOf(location)).isNull();
		assertThat(PROVIDERS.requests("POST", "/revoke")).isNotEmpty();
		assertThat(connectionCount(ctx.tenant().getId())).isZero();
	}

	// ------------------------------------------------------------------
	// Who may confirm
	// ------------------------------------------------------------------

	@Test
	void aTeacherCannotConfirmATenantChannelAuthorization() {
		Ctx ctx = seedTenant("vp-teacher-confirm");
		String pendingId = authorizeTenantChannel(ctx, "UC-x", "X");

		assertThat(status(confirmPending(ctx.host(), ctx.teacherToken(), pendingId))).isEqualTo(404);
		// The forwarded authorization is consumed and revoked, so it cannot be used later either.
		assertThat(status(confirm(ctx.host(), ctx.adminToken(), pendingId, false))).isEqualTo(404);
		assertThat(PROVIDERS.requests("POST", "/revoke")).isNotEmpty();
		assertThat(connectionCount(ctx.tenant().getId())).isZero();
	}

	@Test
	void aTenantAdminCannotPromoteATeachersPersonalAuthorizationToTheTenantChannel() {
		Ctx ctx = seedTenant("vp-admin-teacher-pending");
		String teacherPending = pendingIdOf(authorizeTeacher(ctx.host(), ctx.teacherToken(), "youtube"));

		assertThat(status(preview(ctx.host(), ctx.adminToken(), teacherPending))).isEqualTo(404);
		assertThat(status(confirm(ctx.host(), ctx.adminToken(), teacherPending, false))).isEqualTo(404);
		assertThat(status(confirmPending(ctx.host(), ctx.teacherToken(), teacherPending))).isEqualTo(404);
		assertThat(connectionCount(ctx.tenant().getId())).isZero();
	}

	@Test
	void onlyTheInitiatingAdminCanUseThePendingAuthorization() {
		Ctx ctx = seedTenant("vp-other-admin");
		seedTenantUser(ctx.tenant().getId(), "admin2@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		String admin2Token = loginAndGetToken(ctx.host(), "admin2@example.test");
		String pendingId = authorizeTenantChannel(ctx, "UC-y", "Y");

		assertThat(status(preview(ctx.host(), admin2Token, pendingId))).isEqualTo(404);
		// A preview by someone else does not consume it ...
		assertThat(status(preview(ctx.host(), ctx.adminToken(), pendingId))).isEqualTo(200);
		// ... but a confirm attempt by someone else burns it.
		assertThat(status(confirm(ctx.host(), admin2Token, pendingId, false))).isEqualTo(404);
		assertThat(status(confirm(ctx.host(), ctx.adminToken(), pendingId, false))).isEqualTo(404);
		assertThat(connectionCount(ctx.tenant().getId())).isZero();
	}

	@Test
	void teachersAndStudentsCannotManageVideoProviders() {
		Ctx ctx = seedTenant("vp-roles");

		assertThat(status(list(ctx.host(), ctx.teacherToken()))).isEqualTo(403);
		assertThat(status(connect(ctx.host(), ctx.teacherToken()))).isEqualTo(403);
		assertThat(status(list(ctx.host(), ctx.studentToken()))).isEqualTo(403);
		assertThat(status(connect(ctx.host(), ctx.studentToken()))).isEqualTo(403);
	}

	// ------------------------------------------------------------------
	// Replace, reauthorize, disconnect
	// ------------------------------------------------------------------

	@Test
	void replacingTheChannelNeedsExplicitConfirmationAndKeepsHistory() {
		Ctx ctx = seedTenant("vp-replace");
		UUID first = connectTenantChannel(ctx, "UC-first", "First");

		// Same channel again = reauthorization, no confirmation needed.
		UUID second = connectTenantChannel(ctx, "UC-first", "First");
		assertThat(connectionStatus(first)).isEqualTo("DISCONNECTED");
		assertThat(credentialsOf(first)).isEqualTo("wiped");
		assertThat(auditCount("video_provider.reauthorized", second)).isEqualTo(1);

		String pendingId = authorizeTenantChannel(ctx, "UC-other", "Other");
		JsonNode preview = json(preview(ctx.host(), ctx.adminToken(), pendingId)).path("data");
		assertThat(preview.path("replacesCurrentChannel").asBoolean()).isTrue();
		assertThat(preview.path("currentChannelTitle").asString()).isEqualTo("First");

		MvcResult unconfirmed = confirm(ctx.host(), ctx.adminToken(), pendingId, false);
		assertThat(status(unconfirmed)).isEqualTo(409);
		assertThat(errorCode(unconfirmed)).isEqualTo("VIDEO_PROVIDER_CHANNEL_REPLACEMENT_UNCONFIRMED");
		assertThat(connectionStatus(second)).isEqualTo("ACTIVE");

		// The pending authorization survived the 409.
		MvcResult replaced = confirm(ctx.host(), ctx.adminToken(), pendingId, true);
		assertThat(status(replaced)).isEqualTo(200);
		UUID third = UUID.fromString(json(replaced).path("data").path("connection").path("id").asString());
		assertThat(connectionStatus(second)).isEqualTo("DISCONNECTED");
		assertThat(auditCount("video_provider.channel_replaced", third)).isEqualTo(1);
		assertThat(connectionCount(ctx.tenant().getId())).isEqualTo(3);
	}

	@Test
	void disconnectRevokesAtGoogleAndTheAccountShowsNotConnected() {
		Ctx ctx = seedTenant("vp-disconnect");
		UUID connectionId = connectTenantChannel(ctx, "UC-d", "D");

		assertThat(status(test(ctx.host(), ctx.adminToken(), connectionId))).isEqualTo(200);
		MvcResult disconnected = disconnect(ctx.host(), ctx.adminToken(), connectionId);
		assertThat(status(disconnected)).isEqualTo(200);

		assertThat(connectionStatus(connectionId)).isEqualTo("DISCONNECTED");
		assertThat(PROVIDERS.requests("POST", "/revoke")).isNotEmpty();
		assertThat(json(list(ctx.host(), ctx.adminToken())).path("data").path(0).path("status").asString())
			.isEqualTo("NOT_CONNECTED");
		assertThat(auditCount("media_integration.disconnected", connectionId)).isEqualTo(1);
	}

	@Test
	void teacherYouTubeConnectionIdsAreNotReachableThroughTheVideoProviderEndpoints() {
		Ctx ctx = seedTenant("vp-teacher-id");
		JsonNode teacherConnection = connectTeacher(ctx.host(), ctx.teacherToken(), "youtube");
		UUID teacherConnectionId = UUID.fromString(teacherConnection.path("id").asString());

		assertThat(status(test(ctx.host(), ctx.adminToken(), teacherConnectionId))).isEqualTo(404);
		assertThat(status(disconnect(ctx.host(), ctx.adminToken(), teacherConnectionId))).isEqualTo(404);
		assertThat(connectionStatus(teacherConnectionId)).isEqualTo("ACTIVE");
	}

	// ------------------------------------------------------------------
	// Tenant isolation (mandatory)
	// ------------------------------------------------------------------

	@Tag("cross-tenant")
	@Test
	void tenantBCannotSeeTestOrDisconnectTenantAChannel() {
		Ctx a = seedTenant("vp-iso-a");
		Ctx b = seedTenant("vp-iso-b");
		UUID aConnection = connectTenantChannel(a, "UC-iso-a", "A");

		assertThat(json(list(b.host(), b.adminToken())).path("data").path(0).path("status").asString())
			.isEqualTo("NOT_CONNECTED");
		assertThat(rawContent(list(b.host(), b.adminToken()))).doesNotContain(aConnection.toString());
		assertThat(status(test(b.host(), b.adminToken(), aConnection))).isEqualTo(404);
		assertThat(status(disconnect(b.host(), b.adminToken(), aConnection))).isEqualTo(404);
		assertThat(connectionStatus(aConnection)).isEqualTo("ACTIVE");
	}

	@Tag("cross-tenant")
	@Test
	void tenantBCannotPreviewOrConfirmTenantAPendingAuthorization() {
		Ctx a = seedTenant("vp-pending-a");
		Ctx b = seedTenant("vp-pending-b");
		String aPending = authorizeTenantChannel(a, "UC-pending-a", "A");

		assertThat(status(preview(b.host(), b.adminToken(), aPending))).isEqualTo(404);
		assertThat(status(confirm(b.host(), b.adminToken(), aPending, true))).isEqualTo(404);
		assertThat(connectionCount(b.tenant().getId())).isZero();
		// Consumed by B's attempt: A cannot use it afterwards either (it was revoked).
		assertThat(status(confirm(a.host(), a.adminToken(), aPending, false))).isEqualTo(404);
		assertThat(connectionCount(a.tenant().getId())).isZero();
	}

	@Tag("cross-tenant")
	@Test
	void oneChannelCannotBeTheVideoChannelOfTwoTenants() {
		Ctx a = seedTenant("vp-channel-a");
		Ctx b = seedTenant("vp-channel-b");
		connectTenantChannel(a, "UC-shared", "Shared");

		String bPending = authorizeTenantChannel(b, "UC-shared", "Shared");
		MvcResult result = confirm(b.host(), b.adminToken(), bPending, true);

		assertThat(status(result)).isEqualTo(409);
		assertThat(errorCode(result)).isEqualTo("VIDEO_PROVIDER_CHANNEL_IN_USE");
		assertThat(connectionCount(b.tenant().getId())).isZero();
		assertThat(PROVIDERS.requests("POST", "/revoke")).isNotEmpty();
	}

	@Tag("cross-tenant")
	@Test
	void storedCredentialsOnlyOpenForTheirOwnTenant() {
		Ctx a = seedTenant("vp-aad-a");
		Ctx b = seedTenant("vp-aad-b");
		UUID connectionId = connectTenantChannel(a, "UC-aad", "AAD");
		String ciphertext = credentialsOf(connectionId);

		assertThat(codec.open(ciphertext, new CredentialContext(a.tenant().getId(), MediaProvider.YOUTUBE, null))
			.refreshToken()).startsWith("google-rt-");
		assertThatThrownBy(
				() -> codec.open(ciphertext, new CredentialContext(b.tenant().getId(), MediaProvider.YOUTUBE, null)))
			.isInstanceOf(RuntimeException.class);
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	/** Connect + Google callback for the tenant channel. @return the pending id */
	private String authorizeTenantChannel(Ctx ctx, String channelId, String title) {
		PROVIDERS.setGoogleScope(TENANT_CHANNEL_SCOPES);
		PROVIDERS.setYoutubeChannel(channelId, title);
		String url = json(connect(ctx.host(), ctx.adminToken())).path("data").path("authorizationUrl").asString();
		String location = callback("youtube", "code-" + UUID.randomUUID(), null, stateOf(url)).getResponse()
			.getHeader(HttpHeaders.LOCATION);
		String pendingId = pendingIdOf(location);
		if (pendingId == null) {
			throw new IllegalStateException("callback did not return a pending id: " + location);
		}
		return pendingId;
	}

	/** Full connect (replacing whatever is current). @return the new connection id */
	private UUID connectTenantChannel(Ctx ctx, String channelId, String title) {
		MvcResult confirmed = confirm(ctx.host(), ctx.adminToken(), authorizeTenantChannel(ctx, channelId, title),
				true);
		if (status(confirmed) != 200) {
			throw new IllegalStateException("confirm failed: " + status(confirmed) + " " + rawContent(confirmed));
		}
		return UUID.fromString(json(confirmed).path("data").path("connection").path("id").asString());
	}

	private MvcResult list(String host, String token) {
		return perform(authenticated(get(BASE), host, token));
	}

	private MvcResult connect(String host, String token) {
		return perform(authenticated(post(BASE + "/youtube/connect"), host, token));
	}

	private MvcResult preview(String host, String token, String pendingId) {
		return perform(authenticated(get(BASE + "/youtube/pending/{id}", pendingId), host, token));
	}

	private MvcResult confirm(String host, String token, String pendingId, boolean replaceChannel) {
		return perform(authenticated(post(BASE + "/youtube/pending/{id}/confirm", pendingId)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("replaceChannel", replaceChannel))), host, token));
	}

	private MvcResult test(String host, String token, UUID id) {
		return perform(authenticated(post(BASE + "/{id}/test", id), host, token));
	}

	private MvcResult disconnect(String host, String token, UUID id) {
		return perform(authenticated(post(BASE + "/{id}/disconnect", id).contentType(MediaType.APPLICATION_JSON)
			.content("{}"), host, token));
	}

	private int auditCount(String action, UUID targetId) {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM audit_log WHERE action = ? AND target_id = ?",
				Integer.class, action, targetId);
	}

}
