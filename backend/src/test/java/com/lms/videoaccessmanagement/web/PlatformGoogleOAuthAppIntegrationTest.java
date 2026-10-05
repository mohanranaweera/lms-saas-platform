package com.lms.videoaccessmanagement.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * VIDEO Phase 5c (ADR-018 Amendment 5) - the platform's Google OAuth app
 * entered by a Platform Admin: platform-only access, the write-only secret,
 * the append-only history, the env-var fallback, the effect on the
 * institutes' Connect, and Verify against (fake) Google.
 */
class PlatformGoogleOAuthAppIntegrationTest extends MediaUploadTestSupport {

	private static final String APP = "/api/v1/platform-admin/oauth-apps/google";

	private static final String CLIENT_ID = "123456789012-abcdefghijk.apps.googleusercontent.com";

	private static final String SECRET = "GOCSPX-test-only-secret-value";

	private String platformToken;

	@BeforeEach
	void platformAdmin() {
		jdbcTemplate.update("DELETE FROM platform_oauth_app");
		String email = "oauth-app-" + UUID.randomUUID().toString().substring(0, 8) + "@platform.test";
		seedPlatformAdmin(email, RAW_PASSWORD);
		platformToken = platformAdminLogin(null, email, RAW_PASSWORD).getBody().data().accessToken();
	}

	@AfterEach
	void removeSavedApp() {
		// The database is shared with other test classes, which expect the env-var client.
		jdbcTemplate.update("DELETE FROM platform_oauth_app");
	}

	@Test
	void onlyAPlatformAdminCanReadOrChangeTheApp() {
		Ctx ctx = seedUploadTenant("gapp-roles", null);

		assertThat(status(perform(get(APP)))).isEqualTo(401);
		assertThat(status(perform(authenticated(get(APP), ctx.host(), ctx.adminToken())))).isIn(401, 403);
		assertThat(status(putApp(ctx.host(), ctx.adminToken(), CLIENT_ID, SECRET))).isIn(401, 403);
		assertThat(status(perform(authenticated(delete(APP), ctx.host(), ctx.adminToken())))).isIn(401, 403);
		assertThat(status(perform(authenticated(post(APP + "/verify"), ctx.host(), ctx.teacherToken()))))
			.isIn(401, 403);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM platform_oauth_app", Integer.class)).isZero();
	}

	@Test
	void withNothingSavedTheEnvironmentAppIsShownWithTheRedirectUri() {
		JsonNode view = json(perform(authenticated(get(APP), null, platformToken))).path("data");

		assertThat(view.path("source").asString()).isEqualTo("ENVIRONMENT");
		assertThat(view.path("clientId").asString()).isEqualTo("test-google-client");
		assertThat(view.path("secretSaved").asBoolean()).isFalse();
		assertThat(view.path("encryptionAvailable").asBoolean()).isTrue();
		assertThat(view.path("redirectUri").asString())
			.isEqualTo("https://app.example.test/api/v1/integrations/oauth/youtube/callback");
	}

	@Test
	void aSavedAppIsEncryptedNeverReturnedAndUsedByInstitutesOnConnect() {
		MvcResult saved = putApp(null, platformToken, "  " + CLIENT_ID + " ", SECRET);

		assertThat(status(saved)).isEqualTo(200);
		assertThat(rawContent(saved)).doesNotContain(SECRET);
		JsonNode view = json(saved).path("data");
		assertThat(view.path("source").asString()).isEqualTo("SAVED");
		assertThat(view.path("clientId").asString()).isEqualTo(CLIENT_ID);
		assertThat(view.path("secretSaved").asBoolean()).isTrue();
		String ciphertext = jdbcTemplate.queryForObject("SELECT client_secret_ciphertext FROM platform_oauth_app",
				String.class);
		assertThat(ciphertext).isNotBlank().doesNotContain(SECRET);
		Map<String, Object> change = jdbcTemplate.queryForMap(
				"SELECT * FROM platform_oauth_app_change ORDER BY occurred_at DESC, id DESC LIMIT 1");
		assertThat(change.get("action")).isEqualTo("SAVED");
		assertThat(change.get("client_id_after")).isEqualTo("123456…ontent.com");
		assertThat(change.get("secret_changed")).isEqualTo(true);
		assertThat(change.values()).noneMatch(value -> String.valueOf(value).contains(SECRET));
		assertThat(rawContent(perform(authenticated(get(APP), null, platformToken)))).doesNotContain(SECRET);

		// An institute's Connect now uses the saved client, without a restart.
		Ctx ctx = seedUploadTenant("gapp-connect", null);
		String url = json(perform(authenticated(post(PROVIDERS_BASE + "/youtube/connect"), ctx.host(),
				ctx.adminToken()))).path("data").path("authorizationUrl").asString();
		assertThat(url).contains("client_id=" + CLIENT_ID);
		connectChannel(ctx, "UC-gapp-" + UUID.randomUUID().toString().substring(0, 8));
		assertThat(PROVIDERS.requests("POST", "/token"))
			.anyMatch(request -> request.body().contains("client_secret=" + SECRET));

		// Saving the same secret again records that it did not change.
		putApp(null, platformToken, CLIENT_ID, SECRET);
		assertThat(jdbcTemplate.queryForObject("SELECT secret_changed FROM platform_oauth_app_change "
				+ "ORDER BY occurred_at DESC, id DESC LIMIT 1", Boolean.class)).isFalse();
	}

	@Test
	void invalidValuesAreRejectedAndNothingIsSaved() {
		MvcResult invalid = putApp(null, platformToken, "not-a-google-client", " ");

		assertThat(status(invalid)).isEqualTo(400);
		assertThat(rawContent(invalid)).contains("clientId").contains("clientSecret");
		assertThat(status(putApp(null, platformToken, CLIENT_ID, "has spaces in it"))).isEqualTo(400);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM platform_oauth_app", Integer.class)).isZero();
	}

	@Test
	void removingTheSavedAppFallsBackToTheEnvironment() {
		putApp(null, platformToken, CLIENT_ID, SECRET);

		MvcResult removed = perform(authenticated(delete(APP), null, platformToken));

		assertThat(status(removed)).isEqualTo(200);
		assertThat(json(removed).path("data").path("source").asString()).isEqualTo("ENVIRONMENT");
		assertThat(jdbcTemplate.queryForObject("SELECT action FROM platform_oauth_app_change "
				+ "ORDER BY occurred_at DESC, id DESC LIMIT 1", String.class)).isEqualTo("REMOVED");
		Ctx ctx = seedUploadTenant("gapp-removed", null);
		String url = json(perform(authenticated(post(PROVIDERS_BASE + "/youtube/connect"), ctx.host(),
				ctx.adminToken()))).path("data").path("authorizationUrl").asString();
		assertThat(url).contains("client_id=test-google-client");
	}

	@Test
	void verifyAsksGoogleWhetherTheClientIsValid() {
		putApp(null, platformToken, CLIENT_ID, SECRET);
		JsonNode valid = json(perform(authenticated(post(APP + "/verify"), null, platformToken))).path("data");
		assertThat(valid.path("result").asString()).isEqualTo("VALID");
		assertThat(valid.path("source").asString()).isEqualTo("SAVED");

		putApp(null, platformToken, CLIENT_ID, "wrong");
		JsonNode rejected = json(perform(authenticated(post(APP + "/verify"), null, platformToken))).path("data");
		assertThat(rejected.path("result").asString()).isEqualTo("REJECTED");
		assertThat(PROVIDERS.requests("POST", "/token"))
			.anyMatch(request -> request.body().contains("code=lms-client-check-not-a-real-code"));
	}

	@Test
	void theHistoryIsAppendOnly() {
		putApp(null, platformToken, CLIENT_ID, SECRET);

		assertThatThrownBy(() -> jdbcTemplate.update("UPDATE platform_oauth_app_change SET secret_changed = false"))
			.hasMessageContaining("append-only");
		assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM platform_oauth_app_change"))
			.hasMessageContaining("append-only");
	}

	private MvcResult putApp(String host, String token, String clientId, String clientSecret) {
		return perform(authenticated(put(APP).contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("clientId", clientId, "clientSecret", clientSecret))),
				host, token));
	}

}
