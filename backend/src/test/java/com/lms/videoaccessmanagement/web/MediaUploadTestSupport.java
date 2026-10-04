package com.lms.videoaccessmanagement.web;

import static com.lms.integrationmanagement.media.FakeMediaProviderServer.TENANT_CHANNEL_SCOPES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import com.lms.identityaccessservice.domain.Role;
import com.lms.integrationmanagement.api.MediaProvider;
import com.lms.integrationmanagement.media.FakeYouTubeUploads;
import com.lms.integrationmanagement.media.FakeYouTubeUploads.Failure;
import com.lms.integrationmanagement.media.MediaIntegrationTestSupport;
import com.lms.integrationmanagement.service.MediaCredentialsCodec;
import com.lms.integrationmanagement.service.MediaCredentialsCodec.CredentialContext;
import com.lms.tenantmanagement.domain.Tenant;
import com.lms.videoaccessmanagement.config.VideoFeatureFlags;
import com.lms.videoaccessmanagement.service.MediaStatusSyncService;
import com.lms.videoaccessmanagement.service.MediaUploadMaintenance;
import com.lms.videoaccessmanagement.service.UploadTransferWorker;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * Shared fixtures for the VIDEO Phase 4/5 Media Library integration tests:
 * tenants with a connected YouTube channel, the upload flow played as the
 * browser (in-memory S3 staging), the worker / sync drivers, and DB probes.
 */
abstract class MediaUploadTestSupport extends MediaIntegrationTestSupport {

	protected static final String MEDIA = "/api/v1/media";

	protected static final String PROVIDERS_BASE = "/api/v1/tenant-admin/video-providers";

	protected static final int PART = 256 * 1024;

	protected static final FakeYouTubeUploads YOUTUBE = PROVIDERS.youtube();

	@Autowired
	protected UploadTransferWorker worker;

	@Autowired
	protected MediaStatusSyncService statusSync;

	@Autowired
	protected MediaUploadMaintenance maintenance;

	@Autowired
	protected VideoFeatureFlags featureFlags;

	@Autowired
	protected MediaCredentialsCodec codec;

	protected record Ctx(Tenant tenant, String host, String adminToken, String teacherToken, String teacher2Token,
			String studentToken, UUID teacherId, String channelId) {

	}

	@BeforeEach
	void isolateFromOtherTests() {
		// The worker and status sync poll across tenants: park whatever earlier tests left behind.
		jdbcTemplate.update("UPDATE media_upload_job SET status = 'CANCELLED', next_attempt_at = NULL "
				+ "WHERE status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED')");
		jdbcTemplate.update("UPDATE video_asset SET next_sync_at = NULL WHERE next_sync_at IS NOT NULL");
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	protected Ctx seedUploadTenant(String prefix, String channelId) {
		Tenant tenant = seedActiveTenant(uniqueSubdomain(prefix));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		UUID teacherId = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER).getId();
		seedTenantUser(tenant.getId(), "teacher2@example.test", RAW_PASSWORD, Role.TEACHER);
		seedActiveStudent(tenant.getId(), "student@example.test");
		String host = hostFor(tenant.getSubdomain());
		Ctx ctx = new Ctx(tenant, host, loginAndGetToken(host, "admin@example.test"),
				loginAndGetToken(host, "teacher@example.test"), loginAndGetToken(host, "teacher2@example.test"),
				loginAndGetToken(host, "student@example.test"), teacherId,
				// Channels are globally unique (V71) and the database is shared with other test classes.
				(channelId == null) ? null : channelId + "-" + UUID.randomUUID().toString().substring(0, 8));
		if (ctx.channelId() != null) {
			connectChannel(ctx, ctx.channelId());
		}
		return ctx;
	}

	/** Tenant Admin connects (or reconnects) the tenant YouTube channel. @return the connection id */
	protected UUID connectChannel(Ctx ctx, String channelId) {
		PROVIDERS.setGoogleScope(TENANT_CHANNEL_SCOPES);
		PROVIDERS.setYoutubeChannel(channelId, "Channel " + channelId);
		String url = json(perform(authenticated(post(PROVIDERS_BASE + "/youtube/connect"), ctx.host(),
				ctx.adminToken()))).path("data").path("authorizationUrl").asString();
		String location = callback("youtube", "code-" + UUID.randomUUID(), null, stateOf(url)).getResponse()
			.getHeader(HttpHeaders.LOCATION);
		MvcResult confirmed = perform(authenticated(post(PROVIDERS_BASE + "/youtube/pending/{id}/confirm",
				pendingIdOf(location)).contentType(MediaType.APPLICATION_JSON)
			.content("{\"replaceChannel\":true}"), ctx.host(), ctx.adminToken()));
		if (status(confirmed) != 200) {
			throw new IllegalStateException("confirm failed: " + rawContent(confirmed));
		}
		return UUID.fromString(json(confirmed).path("data").path("connection").path("id").asString());
	}

	/** Create + all parts + complete, by the tenant's teacher. @return the asset id (job QUEUED) */
	protected UUID stageAndQueue(Ctx ctx, byte[] video) {
		JsonNode data = json(create(ctx.host(), ctx.teacherToken(), body("Video", video.length))).path("data");
		UUID assetId = assetIdOf(data);
		MvcResult completed = complete(ctx.host(), ctx.teacherToken(), assetId, putParts(data, video));
		if (status(completed) != 202) {
			throw new IllegalStateException("complete failed: " + rawContent(completed));
		}
		return assetId;
	}

	/** Plays the browser: PUTs every part to its presigned URL. @return the completed-part list */
	protected static List<Map<String, Object>> putParts(JsonNode createData, byte[] video) {
		List<Map<String, Object>> parts = new ArrayList<>();
		int partSize = createData.path("upload").path("partSizeBytes").asInt();
		for (JsonNode part : createData.path("upload").path("parts")) {
			int number = part.path("partNumber").asInt();
			int from = (number - 1) * partSize;
			byte[] bytes = Arrays.copyOfRange(video, from, Math.min(from + partSize, video.length));
			parts.add(part(number, STAGING.putPart(part.path("url").asString(), bytes)));
		}
		return parts;
	}

	protected static Map<String, Object> part(int number, String etag) {
		return Map.of("partNumber", number, "etag", etag);
	}

	protected static Map<String, Object> body(String title, long size) {
		return Map.of("title", title, "fileName", "lesson.mp4", "mimeType", "video/mp4", "sizeBytes", size);
	}

	/** A minimal ISO-BMFF ("ftyp" box) header followed by deterministic filler. */
	protected static byte[] mp4(int size) {
		byte[] bytes = new byte[size];
		new Random(size).nextBytes(bytes);
		byte[] header = { 0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 0, 0, 2, 0 };
		System.arraycopy(header, 0, bytes, 0, header.length);
		return bytes;
	}

	protected MvcResult create(String host, String token, Map<String, Object> body) {
		return perform(authenticated(post(MEDIA).contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(body)), host, token));
	}

	protected MvcResult complete(String host, String token, UUID assetId, List<Map<String, Object>> parts) {
		return perform(authenticated(post(MEDIA + "/{id}/upload/complete", assetId)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("parts", parts))), host, token));
	}

	protected MvcResult presign(String host, String token, UUID assetId, List<Integer> partNumbers) {
		return perform(authenticated(post(MEDIA + "/{id}/upload/parts", assetId)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("partNumbers", partNumbers))), host, token));
	}

	protected MvcResult cancel(String host, String token, UUID assetId) {
		return perform(authenticated(post(MEDIA + "/{id}/upload/cancel", assetId), host, token));
	}

	protected MvcResult platformPost(String path, String host, String token, Map<String, Object> body) {
		return perform(authenticated(post(path).contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(body)), host, token));
	}

	protected static UUID assetIdOf(JsonNode createData) {
		return UUID.fromString(createData.path("mediaAsset").path("id").asString());
	}

	protected Map<String, Object> latestJob(UUID assetId) {
		return jdbcTemplate.queryForMap("SELECT * FROM media_upload_job WHERE video_asset_id = ? "
				+ "ORDER BY created_at DESC, id DESC LIMIT 1", assetId);
	}

	protected String jobStatus(UUID assetId) {
		return (String) latestJob(assetId).get("status");
	}

	protected String stagingKey(UUID assetId) {
		return (String) latestJob(assetId).get("staging_object_key");
	}

	protected String assetStatus(UUID assetId) {
		return jdbcTemplate.queryForObject("SELECT status FROM video_asset WHERE id = ?", String.class, assetId);
	}

	protected String providerAssetId(UUID assetId) {
		return jdbcTemplate.queryForObject("SELECT provider_asset_id FROM video_asset WHERE id = ?", String.class,
				assetId);
	}

	protected int assetCount(UUID tenantId) {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM video_asset WHERE tenant_id = ?", Integer.class,
				tenantId);
	}

	protected Map<String, Object> usage(UUID tenantId) {
		return jdbcTemplate.queryForMap("SELECT coalesce(sum(uploads), 0) AS uploads, coalesce(sum(bytes), 0) AS bytes,"
				+ " coalesce(sum(api_units), 0) AS api_units FROM tenant_video_usage_daily WHERE tenant_id = ?",
				tenantId);
	}

	protected UUID currentConnection(UUID tenantId) {
		return jdbcTemplate.queryForObject("SELECT id FROM media_provider_connection WHERE tenant_id = ? "
				+ "AND provider = 'YOUTUBE' AND scope = 'TENANT' AND status <> 'DISCONNECTED'", UUID.class, tenantId);
	}

	protected String accessTokenOf(UUID tenantId) {
		String ciphertext = jdbcTemplate.queryForObject(
				"SELECT credentials_ciphertext FROM media_provider_connection WHERE id = ?", String.class,
				currentConnection(tenantId));
		return codec.open(ciphertext, new CredentialContext(tenantId, MediaProvider.YOUTUBE, null)).accessToken();
	}

	protected void makeJobsDue(UUID tenantId) {
		jdbcTemplate.update("UPDATE media_upload_job SET next_attempt_at = now() - interval '1 second' "
				+ "WHERE tenant_id = ? AND status IN ('RETRY_WAIT', 'QUOTA_PAUSED')", tenantId);
	}

	protected void makeSyncDue(UUID tenantId) {
		jdbcTemplate.update("UPDATE video_asset SET next_sync_at = now() - interval '1 second' "
				+ "WHERE tenant_id = ? AND status IN ('UPLOADED', 'PROCESSING')", tenantId);
	}

	protected void seedVideoConfiguration(UUID tenantId, boolean uploadsEnabled, int dailyLimit) {
		jdbcTemplate.update("""
				INSERT INTO tenant_video_configuration (id, tenant_id, default_provider, default_privacy_status,
				    uploads_enabled, native_player_required, watermark_enabled, watermark_opacity,
				    watermark_movement_interval_seconds, watermark_fields, capture_protection_enabled,
				    capture_protection_required, max_concurrent_sessions, watch_time_policy,
				    launch_code_ttl_seconds, heartbeat_interval_seconds, daily_upload_limit, created_at, updated_at)
				VALUES (?, ?, 'YOUTUBE', 'UNLISTED', ?, false, true, 0.25, 30, 'STUDENT_DISPLAY_ID', true, false, 1,
				    'NONE', 45, 20, ?, now(), now())
				""", UUID.randomUUID(), tenantId, uploadsEnabled, dailyLimit);
	}

}
