package com.lms.videoaccessmanagement.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.lms.common.tenant.TenantContextHolder;
import com.lms.integrationmanagement.gateway.MediaCredentialCipher;
import com.lms.integrationmanagement.gateway.media.GoogleClientCredentials;
import com.lms.integrationmanagement.service.MediaTokenService;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * VIDEO Phase 5c (ADR-018 Amendment 5 as revised 2026-10-05) - an
 * institute's own Google app, entered by its Tenant Admin: used for that
 * institute's Connect, code exchange (callback, no tenant context) and token
 * refresh; never for another institute; the platform app otherwise; the
 * secret write-only and tenant-bound; changes audited; a changed app marks
 * the live channel for reconnect.
 */
class TenantGoogleOAuthAppIntegrationTest extends MediaUploadTestSupport {

	private static final String APP = PROVIDERS_BASE + "/youtube/oauth-app";

	private static final String OWN_CLIENT = "333333333333-institute.apps.googleusercontent.com";

	private static final String OWN_SECRET = "GOCSPX-institute-test-only-secret";

	@Autowired
	private MediaTokenService tokenService;

	@Autowired
	private MediaCredentialCipher cipher;

	@Test
	void withoutItsOwnAppAnInstituteUsesThePlatformApp() {
		Ctx ctx = seedUploadTenant("tapp-default", null);

		JsonNode view = json(getApp(ctx.host(), ctx.adminToken())).path("data");

		assertThat(view.path("source").asString()).isEqualTo("PLATFORM");
		assertThat(view.path("clientId").isNull()).isTrue();
		assertThat(view.path("secretSaved").asBoolean()).isFalse();
		assertThat(view.path("platformAppAvailable").asBoolean()).isTrue();
		assertThat(view.path("redirectUri").asString())
			.isEqualTo("https://app.example.test/api/v1/integrations/oauth/youtube/callback");
		assertThat(connectUrl(ctx)).contains("client_id=test-google-client");
	}

	@Test
	void anInstitutesOwnAppIsUsedForItsConnectCallbackAndRefreshAndIsAudited() {
		Ctx ctx = seedUploadTenant("tapp-own", null);

		MvcResult saved = putApp(ctx.host(), ctx.adminToken(), OWN_CLIENT, OWN_SECRET);

		assertThat(status(saved)).isEqualTo(200);
		assertThat(rawContent(saved)).doesNotContain(OWN_SECRET);
		JsonNode view = json(saved).path("data");
		assertThat(view.path("source").asString()).isEqualTo("TENANT");
		assertThat(view.path("clientId").asString()).isEqualTo(OWN_CLIENT);
		assertThat(view.path("secretSaved").asBoolean()).isTrue();
		String ciphertext = jdbcTemplate.queryForObject(
				"SELECT client_secret_ciphertext FROM tenant_oauth_app WHERE tenant_id = ?", String.class,
				ctx.tenant().getId());
		assertThat(ciphertext).doesNotContain(OWN_SECRET);
		// Bound to this tenant: another tenant's context cannot decrypt it.
		assertThatThrownBy(() -> cipher.decrypt(ciphertext, GoogleClientCredentials.tenantSecretAad(UUID.randomUUID())))
			.isInstanceOf(IllegalStateException.class);
		Map<String, Object> audit = jdbcTemplate.queryForMap("SELECT * FROM audit_log WHERE tenant_id = ? AND action = ?",
				ctx.tenant().getId(), "video_provider.oauth_app_saved");
		assertThat(audit.toString()).contains(OWN_CLIENT).doesNotContain(OWN_SECRET);

		// Connect + callback (no tenant context on the public callback) use the institute's app.
		assertThat(connectUrl(ctx)).contains("client_id=" + OWN_CLIENT);
		PROVIDERS.reset();
		UUID connectionId = connectChannel(ctx, "UC-tapp-" + UUID.randomUUID().toString().substring(0, 8));
		assertThat(PROVIDERS.requests("POST", "/token"))
			.anyMatch(request -> request.body().contains("client_id=" + OWN_CLIENT)
					&& request.body().contains("client_secret=" + OWN_SECRET));

		// Refresh uses it too.
		PROVIDERS.reset();
		TenantContextHolder.set(ctx.tenant().getId());
		try {
			tokenService.refreshedAccessToken(connectionId);
		}
		finally {
			TenantContextHolder.clear();
		}
		assertThat(PROVIDERS.requests("POST", "/token")).isNotEmpty()
			.allMatch(request -> request.body().contains("grant_type=refresh_token")
					&& request.body().contains("client_secret=" + OWN_SECRET));
	}

	@Tag("cross-tenant")
	@Test
	void anotherInstituteNeverSeesOrUsesIt() {
		Ctx a = seedUploadTenant("tapp-iso-a", null);
		Ctx b = seedUploadTenant("tapp-iso-b", null);
		putApp(a.host(), a.adminToken(), OWN_CLIENT, OWN_SECRET);

		JsonNode bView = json(getApp(b.host(), b.adminToken())).path("data");
		assertThat(bView.path("source").asString()).isEqualTo("PLATFORM");
		assertThat(bView.toString()).doesNotContain(OWN_CLIENT);
		assertThat(connectUrl(b)).contains("client_id=test-google-client").doesNotContain(OWN_CLIENT);
		PROVIDERS.reset();
		connectChannel(b, "UC-tapp-b-" + UUID.randomUUID().toString().substring(0, 8));
		assertThat(PROVIDERS.requests("POST", "/token")).noneMatch(request -> request.body().contains(OWN_SECRET));

		// B removing "its" app touches nothing of A's.
		assertThat(status(perform(authenticated(delete(APP), b.host(), b.adminToken())))).isEqualTo(200);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM tenant_oauth_app WHERE tenant_id = ?",
				Integer.class, a.tenant().getId())).isEqualTo(1);
	}

	@Test
	void changingTheAppMarksTheConnectedChannelForReconnectAndRemovingFallsBack() {
		Ctx ctx = seedUploadTenant("tapp-change", "UC-tapp-change");
		UUID connectionId = currentConnection(ctx.tenant().getId());
		assertThat(json(getApp(ctx.host(), ctx.adminToken())).path("data").path("channelConnected").asBoolean())
			.isTrue();

		putApp(ctx.host(), ctx.adminToken(), OWN_CLIENT, OWN_SECRET);

		assertThat(jdbcTemplate.queryForObject("SELECT status FROM media_provider_connection WHERE id = ?",
				String.class, connectionId)).isEqualTo("NEEDS_RECONNECT");
		assertThat(jdbcTemplate.queryForObject("SELECT metadata::text FROM audit_log WHERE tenant_id = ? AND action = ?",
				String.class, ctx.tenant().getId(), "video_provider.oauth_app_saved"))
			.containsPattern("\"channelMarkedForReconnect\"\\s*:\\s*true");

		MvcResult removed = perform(authenticated(delete(APP), ctx.host(), ctx.adminToken()));
		assertThat(json(removed).path("data").path("source").asString()).isEqualTo("PLATFORM");
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = ?",
				Integer.class, ctx.tenant().getId(), "video_provider.oauth_app_removed")).isEqualTo(1);
		assertThat(connectUrl(ctx)).contains("client_id=test-google-client");
	}

	@Test
	void onlyATenantAdminCanChangeItAndValuesAreValidated() {
		Ctx ctx = seedUploadTenant("tapp-roles", null);

		assertThat(status(getApp(ctx.host(), ctx.teacherToken()))).isEqualTo(403);
		assertThat(status(putApp(ctx.host(), ctx.teacherToken(), OWN_CLIENT, OWN_SECRET))).isEqualTo(403);
		assertThat(status(perform(authenticated(post(APP + "/verify"), ctx.host(), ctx.teacherToken()))))
			.isEqualTo(403);
		MvcResult invalid = putApp(ctx.host(), ctx.adminToken(), "nope", "has spaces");
		assertThat(status(invalid)).isEqualTo(400);
		assertThat(rawContent(invalid)).contains("clientId").contains("clientSecret");
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM tenant_oauth_app WHERE tenant_id = ?",
				Integer.class, ctx.tenant().getId())).isZero();
	}

	@Test
	void verifyChecksTheAppThisInstituteUses() {
		Ctx ctx = seedUploadTenant("tapp-verify", null);
		putApp(ctx.host(), ctx.adminToken(), OWN_CLIENT, OWN_SECRET);

		JsonNode valid = json(perform(authenticated(post(APP + "/verify"), ctx.host(), ctx.adminToken())))
			.path("data");
		assertThat(valid.path("result").asString()).isEqualTo("VALID");
		assertThat(valid.path("source").asString()).isEqualTo("TENANT");

		putApp(ctx.host(), ctx.adminToken(), OWN_CLIENT, "wrong");
		JsonNode rejected = json(perform(authenticated(post(APP + "/verify"), ctx.host(), ctx.adminToken())))
			.path("data");
		assertThat(rejected.path("result").asString()).isEqualTo("REJECTED");
	}

	private String connectUrl(Ctx ctx) {
		return json(perform(authenticated(post(PROVIDERS_BASE + "/youtube/connect"), ctx.host(), ctx.adminToken())))
			.path("data")
			.path("authorizationUrl")
			.asString();
	}

	private MvcResult getApp(String host, String token) {
		return perform(authenticated(get(APP), host, token));
	}

	private MvcResult putApp(String host, String token, String clientId, String clientSecret) {
		return perform(authenticated(put(APP).contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("clientId", clientId, "clientSecret", clientSecret))),
				host, token));
	}

}
