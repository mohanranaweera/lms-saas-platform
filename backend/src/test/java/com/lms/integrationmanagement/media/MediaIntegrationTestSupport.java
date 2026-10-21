package com.lms.integrationmanagement.media;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.lms.integrationmanagement.api.MediaStagingApi;
import com.lms.liveclassmanagement.LiveClassManagementTestSupport;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;

/**
 * Shared helpers for MEDIA-INT-01 integration tests. Every provider base URL
 * points at {@link FakeMediaProviderServer} and the platform Zoom/Google/Vimeo
 * OAuth apps are "configured" with test values, so live classes in this
 * Spring context go through the REAL Zoom adapter (against the stub).
 * VIDEO Phase 4: S3 staging is the in-memory {@link #STAGING} fake.
 */
@Import(MediaIntegrationTestSupport.StagingConfiguration.class)
public abstract class MediaIntegrationTestSupport extends LiveClassManagementTestSupport {

	/** Must match src/test/resources/application.yml's integrations.media.zoom-webhook-secret-token. */
	protected static final String ZOOM_WEBHOOK_SECRET = "test-only-zoom-webhook-secret-token";

	protected static final FakeMediaProviderServer PROVIDERS = FakeMediaProviderServer.get();

	protected static final InMemoryMediaStagingApi STAGING = new InMemoryMediaStagingApi();

	@TestConfiguration
	static class StagingConfiguration {

		@Bean
		@Primary
		MediaStagingApi inMemoryMediaStagingApi() {
			return STAGING;
		}

	}

	@DynamicPropertySource
	static void mediaProviderProperties(DynamicPropertyRegistry registry) {
		String base = PROVIDERS.baseUrl();
		for (String provider : new String[] { "zoom", "google", "vimeo" }) {
			registry.add("integrations.media." + provider + ".client-id", () -> "test-" + provider + "-client");
			registry.add("integrations.media." + provider + ".client-secret", () -> "test-" + provider + "-secret");
			registry.add("integrations.media." + provider + ".auth-base-url", () -> base);
			registry.add("integrations.media." + provider + ".token-base-url", () -> base);
			registry.add("integrations.media." + provider + ".api-base-url", () -> base);
		}
		registry.add("integrations.media.dyntube.api-base-url", () -> base);
		registry.add("integrations.media.dyntube.upload-base-url", () -> base);
		registry.add("integrations.media.bunny.api-base-url", () -> base);
		registry.add("integrations.media.bunny.account-api-base-url", () -> base);
	}

	@BeforeEach
	void resetProviders() {
		PROVIDERS.reset();
		STAGING.reset();
	}

	// ------------------------------------------------------------------
	// Teacher integrations
	// ------------------------------------------------------------------

	protected MvcResult teacherOverview(String host, String token) {
		return perform(authenticated(get("/api/v1/teacher/integrations"), host, token));
	}

	protected MvcResult beginConnect(String host, String token, String provider) {
		return perform(authenticated(post("/api/v1/teacher/integrations/{provider}/connect", provider), host, token));
	}

	/** GET callback - public, no Host/Authorization, exactly as the provider redirects the browser. */
	protected MvcResult callback(String provider, String code, String error, String state) {
		MockHttpServletRequestBuilder builder = get("/api/v1/integrations/oauth/{provider}/callback", provider);
		if (code != null) {
			builder.param("code", code);
		}
		if (error != null) {
			builder.param("error", error);
		}
		if (state != null) {
			builder.param("state", state);
		}
		return perform(builder);
	}

	protected static String stateOf(String authorizationUrl) {
		return UriComponentsBuilder.fromUriString(authorizationUrl).build().getQueryParams().getFirst("state");
	}

	/** Begin connect + provider callback, without confirming. @return the callback's redirect Location */
	protected String authorizeTeacher(String host, String token, String provider) {
		MvcResult begin = beginConnect(host, token, provider);
		if (begin.getResponse().getStatus() != 200) {
			throw new IllegalStateException("connect failed: " + begin.getResponse().getStatus() + " " + rawContent(begin));
		}
		String url = json(begin).path("data").path("authorizationUrl").asString();
		MvcResult result = callback(provider, "auth-code-" + UUID.randomUUID(), null, stateOf(url));
		return result.getResponse().getHeader(HttpHeaders.LOCATION);
	}

	protected static String pendingIdOf(String location) {
		return UriComponentsBuilder.fromUriString(location).build().getQueryParams().getFirst("pending");
	}

	protected MvcResult confirmPending(String host, String token, String pendingId) {
		return perform(authenticated(post("/api/v1/teacher/integrations/pending/{id}/confirm", pendingId), host, token));
	}

	/** Full connect: begin, provider callback, then the teacher's own confirm. @return the saved ConnectionView */
	protected JsonNode connectTeacher(String host, String token, String provider) {
		String location = authorizeTeacher(host, token, provider);
		String pendingId = pendingIdOf(location);
		if (pendingId == null) {
			throw new IllegalStateException("callback did not return a pending id: " + location);
		}
		MvcResult confirmed = confirmPending(host, token, pendingId);
		if (confirmed.getResponse().getStatus() != 200) {
			throw new IllegalStateException("confirm failed: " + confirmed.getResponse().getStatus() + " "
					+ rawContent(confirmed));
		}
		return json(confirmed).path("data");
	}

	protected String credentialsOf(UUID connectionId) {
		return jdbcTemplate.queryForObject("SELECT credentials_ciphertext FROM media_provider_connection WHERE id = ?",
				String.class, connectionId);
	}

	protected UUID currentConnectionId(UUID ownerUserId, String provider) {
		return jdbcTemplate.queryForObject(
				"SELECT id FROM media_provider_connection WHERE owner_user_id = ? AND provider = ? AND status <> 'DISCONNECTED'",
				UUID.class, ownerUserId, provider);
	}

	protected String connectionStatus(UUID connectionId) {
		return jdbcTemplate.queryForObject("SELECT status FROM media_provider_connection WHERE id = ?", String.class,
				connectionId);
	}

	protected int connectionCount(UUID tenantId) {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM media_provider_connection WHERE tenant_id = ?",
				Integer.class, tenantId);
	}

	protected MvcResult teacherTest(String host, String token, UUID id) {
		return perform(authenticated(post("/api/v1/teacher/integrations/connections/{id}/test", id), host, token));
	}

	protected MvcResult teacherImpact(String host, String token, UUID id) {
		return perform(authenticated(get("/api/v1/teacher/integrations/connections/{id}/impact", id), host, token));
	}

	protected MvcResult teacherDisconnect(String host, String token, UUID id) {
		return perform(authenticated(post("/api/v1/teacher/integrations/connections/{id}/disconnect", id)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{}"), host, token));
	}

	// ------------------------------------------------------------------
	// Tenant Admin integrations
	// ------------------------------------------------------------------

	protected MvcResult adminOverview(String host, String token) {
		return perform(authenticated(get("/api/v1/tenant-admin/integrations"), host, token));
	}

	protected MvcResult adminPut(String host, String token, String path, Object body) {
		return perform(authenticated(put("/api/v1/tenant-admin/integrations" + path)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(body)), host, token));
	}

	protected MvcResult adminDisconnect(String host, String token, UUID id, String reason) {
		return perform(authenticated(post("/api/v1/tenant-admin/integrations/connections/{id}/disconnect", id)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString((reason == null) ? Map.of() : Map.of("reason", reason))), host,
				token));
	}

	protected MvcResult adminImpact(String host, String token, UUID id) {
		return perform(authenticated(get("/api/v1/tenant-admin/integrations/connections/{id}/impact", id), host, token));
	}

	protected MvcResult adminTest(String host, String token, UUID id) {
		return perform(authenticated(post("/api/v1/tenant-admin/integrations/connections/{id}/test", id), host, token));
	}

	// ------------------------------------------------------------------
	// Bunny teacher libraries (MEDIA-INT-01A)
	// ------------------------------------------------------------------

	protected MvcResult adminBunnyLibraries(String host, String token) {
		return perform(authenticated(get("/api/v1/tenant-admin/integrations/bunny/libraries"), host, token));
	}

	protected MvcResult adminCreateBunnyLibrary(String host, String token, UUID teacherUserId) {
		return perform(authenticated(post("/api/v1/tenant-admin/integrations/bunny/libraries/{id}", teacherUserId),
				host, token));
	}

	/** {@code TeacherLookupApi} lists teachers from {@code teacher_profile}. */
	protected void seedTeacherProfile(UUID tenantId, UUID userId, String name) {
		jdbcTemplate.update("""
				INSERT INTO teacher_profile (id, tenant_id, user_id, name, approval_status, created_at, updated_at)
				VALUES (?, ?, ?, ?, 'APPROVED', now(), now())
				""", UUID.randomUUID(), tenantId, userId, name);
	}

	protected int bunnyLibraryRowCount(UUID tenantId) {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM bunny_teacher_library WHERE tenant_id = ?",
				Integer.class, tenantId);
	}

	// ------------------------------------------------------------------
	// Zoom webhook
	// ------------------------------------------------------------------

	protected MvcResult zoomWebhook(String rawBody, boolean validSignature) {
		String timestamp = String.valueOf(Instant.now().getEpochSecond());
		String signature = "v0=" + hmacHex(validSignature ? ZOOM_WEBHOOK_SECRET : "wrong-secret",
				"v0:" + timestamp + ":" + rawBody);
		return perform(post("/api/v1/integrations/webhooks/zoom").contentType(MediaType.APPLICATION_JSON)
			.content(rawBody)
			.header("x-zm-request-timestamp", timestamp)
			.header("x-zm-signature", signature));
	}

	protected static String hmacHex(String secret, String message) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException(ex);
		}
	}

	// ------------------------------------------------------------------
	// JSON
	// ------------------------------------------------------------------

	protected JsonNode json(MvcResult result) {
		String content = rawContent(result);
		return (content == null || content.isBlank()) ? objectMapper.createObjectNode() : objectMapper.readTree(content);
	}

	protected static int status(MvcResult result) {
		return result.getResponse().getStatus();
	}

	protected String errorCode(MvcResult result) {
		return json(result).path("error").path("code").asString();
	}

}
