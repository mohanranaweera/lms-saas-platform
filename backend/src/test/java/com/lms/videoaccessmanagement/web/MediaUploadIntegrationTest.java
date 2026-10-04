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
 * VIDEO Phase 4 - the YouTube upload pipeline end to end against the fake
 * Google/YouTube server and in-memory S3 staging: create → browser parts →
 * complete (size + content checks) → background transfer → status sync;
 * failure handling (retry, resume, session restart, quota pause, token
 * refresh, revoked grant, platform switch-off); budget; roles; and tenant
 * isolation (plan §32 T1-T4, T7, T12, T13).
 */
class MediaUploadIntegrationTest extends MediaIntegrationTestSupport {

	private static final String MEDIA = "/api/v1/media";

	private static final String PROVIDERS_BASE = "/api/v1/tenant-admin/video-providers";

	private static final int PART = 256 * 1024;

	private static final FakeYouTubeUploads YOUTUBE = PROVIDERS.youtube();

	@Autowired
	private UploadTransferWorker worker;

	@Autowired
	private MediaStatusSyncService statusSync;

	@Autowired
	private MediaUploadMaintenance maintenance;

	@Autowired
	private VideoFeatureFlags featureFlags;

	@Autowired
	private MediaCredentialsCodec codec;

	private record Ctx(Tenant tenant, String host, String adminToken, String teacherToken, String teacher2Token,
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
	// Happy path
	// ------------------------------------------------------------------

	@Test
	void aTeacherUploadIsStagedTransferredWithTheTenantTokenAndBecomesReady() {
		Ctx ctx = seedUploadTenant("up-happy", "UC-happy");
		byte[] video = mp4(PART * 2 + 1000);

		MvcResult created = create(ctx.host(), ctx.teacherToken(), body("Lesson 1", video.length));
		assertThat(status(created)).isEqualTo(201);
		JsonNode data = json(created).path("data");
		assertThat(data.path("mediaAsset").path("processingStatus").asString()).isEqualTo("CREATED");
		assertThat(data.path("mediaAsset").path("provider").asString()).isEqualTo("YOUTUBE");
		assertThat(data.path("upload").path("partCount").asInt()).isEqualTo(3);
		assertThat(data.path("upload").path("parts")).hasSize(3);
		assertThat(rawContent(created)).doesNotContain("sessionHandle").doesNotContain("stagingUploadId");
		UUID assetId = UUID.fromString(data.path("mediaAsset").path("id").asString());

		MvcResult completed = complete(ctx.host(), ctx.teacherToken(), assetId, putParts(data, video));
		assertThat(status(completed)).isEqualTo(202);
		assertThat(json(completed).path("data").path("processingStatus").asString()).isEqualTo("UPLOADING");

		assertThat(worker.drain(5)).isEqualTo(1);

		assertThat(jobStatus(assetId)).isEqualTo("COMPLETED");
		assertThat(assetStatus(assetId)).isEqualTo("PROCESSING");
		FakeYouTubeUploads.Session session = YOUTUBE.sessions().getFirst();
		assertThat(session.bytes()).isEqualTo(video);
		assertThat(providerAssetId(assetId)).isEqualTo(session.videoId());
		// T7: every call of this upload carried this tenant's own token.
		String tenantToken = "Bearer " + accessTokenOf(ctx.tenant().getId());
		assertThat(session.authorizations()).isNotEmpty().allMatch(tenantToken::equals);
		assertThat(STAGING.hasObject(stagingKey(assetId))).isFalse();
		assertThat(stagingKey(assetId)).startsWith("video-staging/" + ctx.tenant().getId() + "/");

		List<Map<String, Object>> events = jdbcTemplate.queryForList(
				"SELECT to_status, actor_id FROM video_asset_status_event WHERE video_asset_id = ? ORDER BY occurred_at",
				assetId);
		assertThat(events).extracting(e -> e.get("to_status"))
			.containsExactly("CREATED", "UPLOADING", "UPLOADED", "PROCESSING");
		assertThat(events.get(0).get("actor_id")).isEqualTo(ctx.teacherId());
		assertThat(events.get(2).get("actor_id")).isNull();
		assertThat(events.get(3).get("actor_id")).isNull();

		Map<String, Object> usage = usage(ctx.tenant().getId());
		assertThat(((Number) usage.get("uploads")).intValue()).isEqualTo(1);
		assertThat(((Number) usage.get("bytes")).longValue()).isEqualTo(video.length);
		assertThat(((Number) usage.get("api_units")).longValue()).isGreaterThanOrEqualTo(1600);

		YOUTUBE.setVideoStatus(session.videoId(), "processed");
		makeSyncDue(ctx.tenant().getId());
		statusSync.syncDue();

		assertThat(assetStatus(assetId)).isEqualTo("READY");
		JsonNode asset = json(perform(authenticated(get(MEDIA + "/{id}", assetId), ctx.host(), ctx.teacherToken())))
			.path("data");
		assertThat(asset.path("processingStatus").asString()).isEqualTo("READY");
		assertThat(asset.path("durationSeconds").asInt()).isEqualTo(65);
		assertThat(asset.path("thumbnailUrl").asString()).startsWith("https://");
	}

	@Test
	void aVideoStillProcessingIsCheckedAgainLaterAndAFailedOneIsMarkedFailed() {
		Ctx ctx = seedUploadTenant("up-sync", "UC-sync");
		UUID assetId = stageAndQueue(ctx, mp4(PART));
		worker.drain(5);
		String videoId = providerAssetId(assetId);

		makeSyncDue(ctx.tenant().getId());
		statusSync.syncDue();
		assertThat(assetStatus(assetId)).isEqualTo("PROCESSING");
		assertThat(jdbcTemplate.queryForObject(
				"SELECT next_sync_at > now() AND sync_failure_count = 1 FROM video_asset WHERE id = ?", Boolean.class,
				assetId)).isTrue();

		YOUTUBE.setVideoStatus(videoId, "rejected");
		makeSyncDue(ctx.tenant().getId());
		statusSync.syncDue();
		assertThat(assetStatus(assetId)).isEqualTo("FAILED");
		assertThat(jdbcTemplate.queryForObject("SELECT provider_failure_reason FROM video_asset WHERE id = ?",
				String.class, assetId)).isEqualTo("YouTube rejected this video.");
	}

	// ------------------------------------------------------------------
	// Server-side validation of the staged file
	// ------------------------------------------------------------------

	@Test
	void aFileThatIsNotAVideoIsRejectedDeletedAndItsBudgetSlotReturned() {
		Ctx ctx = seedUploadTenant("up-sniff", "UC-sniff");
		byte[] notAVideo = "#!/bin/sh\necho not a video\n".repeat(20_000).getBytes(StandardCharsets.UTF_8);
		JsonNode data = json(create(ctx.host(), ctx.teacherToken(), body("Fake", notAVideo.length))).path("data");
		UUID assetId = assetIdOf(data);

		MvcResult result = complete(ctx.host(), ctx.teacherToken(), assetId, putParts(data, notAVideo));

		assertThat(status(result)).isEqualTo(422);
		assertThat(errorCode(result)).isEqualTo("MEDIA_UPLOAD_FAILED");
		assertThat(assetStatus(assetId)).isEqualTo("FAILED");
		assertThat(jobStatus(assetId)).isEqualTo("FAILED");
		assertThat(STAGING.hasObject(stagingKey(assetId))).isFalse();
		assertThat(((Number) usage(ctx.tenant().getId()).get("uploads")).intValue()).isZero();
		assertThat(worker.drain(5)).isZero();
	}

	@Test
	void aStagedFileOfTheWrongSizeIsRejected() {
		Ctx ctx = seedUploadTenant("up-size", "UC-size");
		byte[] video = mp4(PART * 2);
		JsonNode data = json(create(ctx.host(), ctx.teacherToken(), body("Short", video.length))).path("data");
		JsonNode parts = data.path("upload").path("parts");
		List<Map<String, Object>> completed = List.of(
				part(1, STAGING.putPart(parts.path(0).path("url").asString(), Arrays.copyOf(video, PART))),
				part(2, STAGING.putPart(parts.path(1).path("url").asString(), new byte[100])));

		MvcResult result = complete(ctx.host(), ctx.teacherToken(), assetIdOf(data), completed);

		assertThat(status(result)).isEqualTo(422);
		assertThat(assetStatus(assetIdOf(data))).isEqualTo("FAILED");
	}

	@Test
	void theRequestItselfIsValidated() {
		Ctx ctx = seedUploadTenant("up-validate", "UC-validate");
		Map<String, Object> bad = new HashMap<>(body("<script>", 10));
		bad.put("privacyStatus", "PUBLIC");
		bad.put("mimeType", "application/x-msdownload");
		bad.put("sizeBytes", 1L << 40);
		MvcResult result = create(ctx.host(), ctx.teacherToken(), bad);
		assertThat(status(result)).isEqualTo(400);
		assertThat(rawContent(result)).contains("title").contains("privacyStatus").contains("mimeType")
			.contains("sizeBytes");

		Map<String, Object> internal = new HashMap<>(body("Ok", 10));
		internal.put("provider", "INTERNAL");
		assertThat(status(create(ctx.host(), ctx.teacherToken(), internal))).isEqualTo(400);
		assertThat(assetCount(ctx.tenant().getId())).isZero();
	}

	// ------------------------------------------------------------------
	// Transfer failures
	// ------------------------------------------------------------------

	@Test
	void aTransientFailureResumesTheSameSessionFromWhereYouTubeStopped() {
		Ctx ctx = seedUploadTenant("up-resume", "UC-resume");
		byte[] video = mp4(PART * 3);
		UUID assetId = stageAndQueue(ctx, video);
		YOUTUBE.failChunk(2, 503, "backendError");

		worker.drain(5);
		assertThat(jobStatus(assetId)).isEqualTo("RETRY_WAIT");
		assertThat(latestJob(assetId).get("bytes_sent")).isEqualTo((long) PART);

		makeJobsDue(ctx.tenant().getId());
		worker.drain(5);

		assertThat(jobStatus(assetId)).isEqualTo("COMPLETED");
		assertThat(YOUTUBE.sessions()).hasSize(1);
		assertThat(YOUTUBE.sessions().getFirst().bytes()).isEqualTo(video);
		assertThat(latestJob(assetId).get("attempt_count")).isEqualTo(1);
	}

	@Test
	void anExpiredSessionIsRestartedFromTheFirstByte() {
		Ctx ctx = seedUploadTenant("up-session", "UC-session");
		byte[] video = mp4(PART * 2);
		UUID assetId = stageAndQueue(ctx, video);
		YOUTUBE.failChunk(2, 404, "notFound");

		// The restart is due at once, so the same drain starts a new session and finishes.
		worker.drain(5);

		assertThat(jobStatus(assetId)).isEqualTo("COMPLETED");
		assertThat(latestJob(assetId).get("attempt_count")).isEqualTo(1);
		assertThat(YOUTUBE.sessions()).hasSize(2);
		assertThat(YOUTUBE.sessions().get(1).bytes()).isEqualTo(video);
	}

	@Test
	void repeatedFailuresFailTheUploadAndRetryQueuesItAgainWithoutANewBudgetSlot() {
		Ctx ctx = seedUploadTenant("up-exhaust", "UC-exhaust");
		UUID assetId = stageAndQueue(ctx, mp4(PART));
		YOUTUBE.failAllChunks(new Failure(503, "backendError"));

		for (int i = 0; i < 10 && !"FAILED".equals(jobStatus(assetId)); i++) {
			worker.drain(5);
			makeJobsDue(ctx.tenant().getId());
		}
		assertThat(jobStatus(assetId)).isEqualTo("FAILED");
		assertThat(assetStatus(assetId)).isEqualTo("FAILED");
		assertThat(STAGING.hasObject(stagingKey(assetId))).isTrue();

		YOUTUBE.failAllChunks(null);
		MvcResult retried = perform(authenticated(post(MEDIA + "/{id}/upload/retry", assetId), ctx.host(),
				ctx.teacherToken()));
		assertThat(status(retried)).isEqualTo(202);
		assertThat(json(retried).path("data").path("processingStatus").asString()).isEqualTo("UPLOADING");
		worker.drain(5);

		assertThat(jobStatus(assetId)).isEqualTo("COMPLETED");
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM media_upload_job WHERE video_asset_id = ?",
				Integer.class, assetId)).isEqualTo(2);
		assertThat(((Number) usage(ctx.tenant().getId()).get("uploads")).intValue()).isEqualTo(1);
		// A completed upload cannot be retried.
		assertThat(status(perform(authenticated(post(MEDIA + "/{id}/upload/retry", assetId), ctx.host(),
				ctx.teacherToken())))).isEqualTo(409);
	}

	@Test
	void aFailedUploadCannotBeRetriedIntoADifferentChannel() {
		Ctx ctx = seedUploadTenant("up-swap", "UC-swap");
		UUID assetId = stageAndQueue(ctx, mp4(PART));
		YOUTUBE.failChunk(1, 400, "invalidTitle");
		worker.drain(5);
		assertThat(jobStatus(assetId)).isEqualTo("FAILED");

		connectChannel(ctx, "UC-other-" + UUID.randomUUID().toString().substring(0, 8));
		MvcResult retried = perform(authenticated(post(MEDIA + "/{id}/upload/retry", assetId), ctx.host(),
				ctx.teacherToken()));

		assertThat(status(retried)).isEqualTo(409);
		assertThat(errorCode(retried)).isEqualTo("MEDIA_UPLOAD_STATE_CONFLICT");
		assertThat(jobStatus(assetId)).isEqualTo("FAILED");
	}

	@Test
	void anExhaustedQuotaPausesOnlyThatTenantsUpload() {
		Ctx a = seedUploadTenant("up-quota-a", "UC-quota-a");
		Ctx b = seedUploadTenant("up-quota-b", "UC-quota-b");
		UUID assetA = stageAndQueue(a, mp4(PART));
		UUID assetB = stageAndQueue(b, mp4(PART));
		YOUTUBE.failNextInit(403, "quotaExceeded");

		worker.drain(5);

		// T13: A's quota error leaves B working.
		assertThat(jobStatus(assetA)).isEqualTo("QUOTA_PAUSED");
		assertThat(jdbcTemplate.queryForObject(
				"SELECT next_attempt_at > now() + interval '1 minute' FROM media_upload_job WHERE video_asset_id = ?",
				Boolean.class, assetA)).isTrue();
		assertThat(jobStatus(assetB)).isEqualTo("COMPLETED");
		assertThat(assetStatus(assetA)).isEqualTo("UPLOADING");
	}

	@Test
	void aRejectedAccessTokenIsRefreshedOnce() {
		Ctx ctx = seedUploadTenant("up-refresh", "UC-refresh");
		UUID assetId = stageAndQueue(ctx, mp4(PART));
		YOUTUBE.failChunk(1, 401, "authError");

		worker.drain(5);

		assertThat(jobStatus(assetId)).isEqualTo("COMPLETED");
		assertThat(PROVIDERS.requests("POST", "/token")).anyMatch(r -> r.body().contains("grant_type=refresh_token"));
		assertThat(connectionStatus(currentConnection(ctx.tenant().getId()))).isEqualTo("ACTIVE");
	}

	@Test
	void aRevokedGrantParksTheUploadUntilTheSameChannelIsReconnected() {
		Ctx ctx = seedUploadTenant("up-revoked", "UC-revoked");
		UUID oldConnection = currentConnection(ctx.tenant().getId());
		UUID assetId = stageAndQueue(ctx, mp4(PART * 2));
		YOUTUBE.failAllChunks(new Failure(401, "authError"));
		PROVIDERS.setRejectGoogleRefresh(true);

		worker.drain(5);

		assertThat(jobStatus(assetId)).isEqualTo("PARKED_RECONNECT");
		assertThat(connectionStatus(oldConnection)).isEqualTo("NEEDS_RECONNECT");
		assertThat(maintenance.resumeParked()).isZero();

		YOUTUBE.failAllChunks(null);
		PROVIDERS.setRejectGoogleRefresh(false);
		UUID newConnection = connectChannel(ctx, ctx.channelId());
		assertThat(maintenance.resumeParked()).isEqualTo(1);
		assertThat(latestJob(assetId).get("provider_connection_id")).isEqualTo(newConnection);

		worker.drain(5);
		assertThat(jobStatus(assetId)).isEqualTo("COMPLETED");
	}

	// ------------------------------------------------------------------
	// Cancel, abandon, budget, switches
	// ------------------------------------------------------------------

	@Test
	void aTenantAdminCancelsATeachersUploadAndTheBudgetSlotComesBack() {
		Ctx ctx = seedUploadTenant("up-cancel", "UC-cancel");
		byte[] video = mp4(PART * 2);
		JsonNode data = json(create(ctx.host(), ctx.teacherToken(), body("Cancel me", video.length))).path("data");
		UUID assetId = assetIdOf(data);
		STAGING.putPart(data.path("upload").path("parts").path(0).path("url").asString(), Arrays.copyOf(video, PART));

		MvcResult cancelled = cancel(ctx.host(), ctx.adminToken(), assetId);

		assertThat(status(cancelled)).isEqualTo(200);
		assertThat(json(cancelled).path("data").path("processingStatus").asString()).isEqualTo("FAILED");
		assertThat(json(cancelled).path("data").path("failureReason").asString()).isEqualTo("Upload cancelled.");
		assertThat(jobStatus(assetId)).isEqualTo("CANCELLED");
		assertThat(STAGING.abortedUploads()).isNotEmpty();
		assertThat(((Number) usage(ctx.tenant().getId()).get("uploads")).intValue()).isZero();
		MvcResult again = cancel(ctx.host(), ctx.adminToken(), assetId);
		assertThat(status(again)).isEqualTo(409);
		assertThat(errorCode(again)).isEqualTo("MEDIA_UPLOAD_STATE_CONFLICT");
	}

	@Test
	void anUploadTheBrowserNeverFinishesIsSweptAway() {
		Ctx ctx = seedUploadTenant("up-abandon", "UC-abandon");
		JsonNode data = json(create(ctx.host(), ctx.teacherToken(), body("Abandoned", PART))).path("data");
		UUID assetId = assetIdOf(data);
		jdbcTemplate.update(
				"UPDATE media_upload_job SET created_at = now() - interval '3 days' WHERE video_asset_id = ?", assetId);

		maintenance.sweep();

		assertThat(jobStatus(assetId)).isEqualTo("CANCELLED");
		assertThat(assetStatus(assetId)).isEqualTo("FAILED");
		assertThat(STAGING.abortedUploads()).isNotEmpty();
		assertThat(latestJob(assetId).get("staging_released_at")).isNotNull();
		assertThat(((Number) usage(ctx.tenant().getId()).get("uploads")).intValue()).isZero();
	}

	@Test
	void theDailyBudgetIsEnforcedPerTenant() {
		Ctx ctx = seedUploadTenant("up-budget", "UC-budget");
		Ctx other = seedUploadTenant("up-budget-other", "UC-budget-other");
		seedVideoConfiguration(ctx.tenant().getId(), true, 1);

		MvcResult first = create(ctx.host(), ctx.teacherToken(), body("One", PART));
		assertThat(status(first)).isEqualTo(201);
		MvcResult second = create(ctx.host(), ctx.teacherToken(), body("Two", PART));
		assertThat(status(second)).isEqualTo(429);
		assertThat(errorCode(second)).isEqualTo("UPLOAD_QUOTA_EXCEEDED");
		// Another tenant's budget is untouched.
		assertThat(status(create(other.host(), other.teacherToken(), body("Elsewhere", PART)))).isEqualTo(201);

		cancel(ctx.host(), ctx.teacherToken(), assetIdOf(json(first).path("data")));
		assertThat(status(create(ctx.host(), ctx.teacherToken(), body("Three", PART)))).isEqualTo(201);
	}

	@Test
	void uploadsNeedTheFlagTheTenantSwitchAndAConnectedChannel() {
		Ctx noChannel = seedUploadTenant("up-nochannel", null);
		MvcResult result = create(noChannel.host(), noChannel.teacherToken(), body("X", PART));
		assertThat(status(result)).isEqualTo(409);
		assertThat(errorCode(result)).isEqualTo("VIDEO_PROVIDER_NOT_CONNECTED");

		Ctx off = seedUploadTenant("up-off", "UC-off");
		seedVideoConfiguration(off.tenant().getId(), false, 20);
		result = create(off.host(), off.teacherToken(), body("X", PART));
		assertThat(status(result)).isEqualTo(409);
		assertThat(errorCode(result)).isEqualTo("VIDEO_UPLOADS_DISABLED");

		Ctx flag = seedUploadTenant("up-flag", "UC-flag");
		featureFlags.setYoutubeUpload(false);
		try {
			result = create(flag.host(), flag.teacherToken(), body("X", PART));
			assertThat(status(result)).isEqualTo(409);
			assertThat(errorCode(result)).isEqualTo("VIDEO_UPLOADS_DISABLED");
		}
		finally {
			featureFlags.setYoutubeUpload(true);
		}
		assertThat(assetCount(noChannel.tenant().getId()) + assetCount(off.tenant().getId())
				+ assetCount(flag.tenant().getId())).isZero();
	}

	// ------------------------------------------------------------------
	// Roles
	// ------------------------------------------------------------------

	@Test
	void studentsCannotUploadAndTeachersCannotTouchEachOthersUploads() {
		Ctx ctx = seedUploadTenant("up-roles", "UC-roles");
		assertThat(status(create(ctx.host(), ctx.studentToken(), body("Nope", PART)))).isEqualTo(403);

		byte[] video = mp4(PART);
		JsonNode data = json(create(ctx.host(), ctx.teacherToken(), body("Mine", video.length))).path("data");
		UUID assetId = assetIdOf(data);
		List<Map<String, Object>> parts = putParts(data, video);

		assertThat(status(complete(ctx.host(), ctx.teacher2Token(), assetId, parts))).isEqualTo(404);
		assertThat(status(cancel(ctx.host(), ctx.teacher2Token(), assetId))).isEqualTo(404);
		assertThat(status(presign(ctx.host(), ctx.teacher2Token(), assetId, List.of(1)))).isEqualTo(404);
		assertThat(status(complete(ctx.host(), ctx.studentToken(), assetId, parts))).isEqualTo(403);
		assertThat(status(presign(ctx.host(), ctx.teacherToken(), assetId, List.of(1)))).isEqualTo(200);
		assertThat(status(complete(ctx.host(), ctx.teacherToken(), assetId, parts))).isEqualTo(202);
	}

	// ------------------------------------------------------------------
	// Tenant isolation
	// ------------------------------------------------------------------

	@Test
	void anotherTenantCannotSeeOrDriveAnUpload() {
		Ctx a = seedUploadTenant("up-iso-a", "UC-iso-a");
		Ctx b = seedUploadTenant("up-iso-b", "UC-iso-b");
		byte[] video = mp4(PART);
		Map<String, Object> request = new HashMap<>(body("A's video", video.length));
		request.put("tenantId", b.tenant().getId().toString());
		JsonNode data = json(create(a.host(), a.teacherToken(), request)).path("data");
		UUID assetId = assetIdOf(data);
		List<Map<String, Object>> parts = putParts(data, video);

		// T12: an injected tenantId is ignored.
		assertThat(jdbcTemplate.queryForObject("SELECT tenant_id FROM video_asset WHERE id = ?", UUID.class, assetId))
			.isEqualTo(a.tenant().getId());
		// T1-T4: B's admin gets 404 for everything about A's asset.
		assertThat(status(perform(authenticated(get(MEDIA + "/{id}", assetId), b.host(), b.adminToken()))))
			.isEqualTo(404);
		assertThat(rawContent(perform(authenticated(get(MEDIA), b.host(), b.adminToken()))))
			.doesNotContain(assetId.toString());
		assertThat(status(presign(b.host(), b.adminToken(), assetId, List.of(1)))).isEqualTo(404);
		assertThat(status(complete(b.host(), b.adminToken(), assetId, parts))).isEqualTo(404);
		assertThat(status(cancel(b.host(), b.adminToken(), assetId))).isEqualTo(404);
		assertThat(status(perform(authenticated(post(MEDIA + "/{id}/upload/retry", assetId), b.host(),
				b.adminToken())))).isEqualTo(404);

		assertThat(status(complete(a.host(), a.teacherToken(), assetId, parts))).isEqualTo(202);
		worker.drain(5);
		assertThat(YOUTUBE.sessions().getFirst().authorizations())
			.allMatch(("Bearer " + accessTokenOf(a.tenant().getId()))::equals);
	}

	// ------------------------------------------------------------------
	// TD-008: Platform Admin switch-off
	// ------------------------------------------------------------------

	@Test
	void aPlatformAdminCanSwitchATenantChannelOffAndOnlyAPlatformAdminCanSwitchItOn() {
		Ctx ctx = seedUploadTenant("up-platform", "UC-platform");
		Ctx other = seedUploadTenant("up-platform-other", "UC-platform-other");
		UUID connectionId = currentConnection(ctx.tenant().getId());
		UUID assetId = stageAndQueue(ctx, mp4(PART));
		seedPlatformAdmin("video-platform-admin@platform.test", RAW_PASSWORD);
		String platformToken = platformAdminLogin(null, "video-platform-admin@platform.test", RAW_PASSWORD).getBody()
			.data()
			.accessToken();
		String base = "/api/v1/platform-admin/tenants/" + ctx.tenant().getId() + "/video-providers/" + connectionId;

		assertThat(status(platformPost(base + "/disable", ctx.host(), ctx.adminToken(), Map.of("reason", "x"))))
			.isIn(401, 403);
		assertThat(status(platformPost(base + "/disable", null, platformToken, Map.of()))).isEqualTo(400);
		assertThat(status(platformPost("/api/v1/platform-admin/tenants/" + other.tenant().getId()
				+ "/video-providers/" + connectionId + "/disable", null, platformToken, Map.of("reason", "x"))))
			.isEqualTo(404);
		MvcResult disabled = platformPost(base + "/disable", null, platformToken, Map.of("reason", "Abuse report"));
		assertThat(status(disabled)).isEqualTo(200);
		assertThat(json(disabled).path("data").path("platformDisabled").asBoolean()).isTrue();
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_log WHERE action = ? AND target_id = ? "
				+ "AND tenant_id = ?", Integer.class, "video_provider.platform_disabled", connectionId,
				ctx.tenant().getId())).isEqualTo(1);

		MvcResult upload = create(ctx.host(), ctx.teacherToken(), body("Blocked", PART));
		assertThat(status(upload)).isEqualTo(409);
		assertThat(errorCode(upload)).isEqualTo("VIDEO_PROVIDER_DISABLED_BY_PLATFORM");
		MvcResult reconnect = perform(authenticated(post(PROVIDERS_BASE + "/youtube/connect"), ctx.host(),
				ctx.adminToken()));
		assertThat(status(reconnect)).isEqualTo(409);
		assertThat(errorCode(reconnect)).isEqualTo("VIDEO_PROVIDER_DISABLED_BY_PLATFORM");
		JsonNode account = json(perform(authenticated(get(PROVIDERS_BASE), ctx.host(), ctx.adminToken())))
			.path("data")
			.path(0);
		assertThat(account.path("platformDisabled").asBoolean()).isTrue();

		worker.drain(5);
		assertThat(jobStatus(assetId)).isEqualTo("PARKED_RECONNECT");
		assertThat(maintenance.resumeParked()).isZero();
		// Other tenants keep uploading.
		UUID otherAsset = stageAndQueue(other, mp4(PART));
		worker.drain(5);
		assertThat(jobStatus(otherAsset)).isEqualTo("COMPLETED");

		assertThat(status(platformPost(base + "/enable", null, platformToken, Map.of()))).isEqualTo(200);
		assertThat(status(platformPost(base + "/enable", null, platformToken, Map.of()))).isEqualTo(409);
		assertThat(maintenance.resumeParked()).isEqualTo(1);
		worker.drain(5);
		assertThat(jobStatus(assetId)).isEqualTo("COMPLETED");
	}

	// ------------------------------------------------------------------
	// Schema
	// ------------------------------------------------------------------

	@Test
	void v72EnforcesTenantBoundariesInTheSchema() {
		List<String> constraints = jdbcTemplate.queryForList(
				"SELECT conname FROM pg_constraint WHERE conrelid IN ('media_upload_job'::regclass, "
						+ "'tenant_video_usage_daily'::regclass, 'media_provider_connection'::regclass)",
				String.class);
		assertThat(constraints).contains("fk_media_upload_job_asset", "fk_media_upload_job_connection",
				"ck_media_upload_job_staging_key", "uq_tenant_video_usage_daily",
				"ck_media_provider_connection_platform_disabled");
		List<String> indexes = jdbcTemplate.queryForList(
				"SELECT indexname FROM pg_indexes WHERE tablename IN ('media_upload_job', 'video_asset')", String.class);
		assertThat(indexes).contains("uq_media_upload_job_live_asset", "idx_media_upload_job_tenant_asset",
				"idx_video_asset_sync_due");

		Ctx a = seedUploadTenant("up-schema-a", "UC-schema-a");
		Ctx b = seedUploadTenant("up-schema-b", "UC-schema-b");
		UUID assetA = assetIdOf(json(create(a.host(), a.teacherToken(), body("A", PART))).path("data"));
		// A job of tenant B pointing at tenant A's asset, or with A's staging prefix, is rejected.
		assertThatThrownBy(() -> jdbcTemplate.update("INSERT INTO media_upload_job (id, tenant_id, video_asset_id, "
				+ "provider_connection_id, status, staging_object_key, part_size_bytes, bytes_total, budget_date, "
				+ "created_at, updated_at) VALUES (?, ?, ?, ?, 'QUEUED', ?, 1, 1, current_date, now(), now())",
				UUID.randomUUID(), b.tenant().getId(), assetA, currentConnection(b.tenant().getId()),
				"video-staging/" + b.tenant().getId() + "/x")).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbcTemplate.update("UPDATE media_upload_job SET staging_object_key = ? "
				+ "WHERE video_asset_id = ?", "video-staging/" + b.tenant().getId() + "/x", assetA))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	private Ctx seedUploadTenant(String prefix, String channelId) {
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
	private UUID connectChannel(Ctx ctx, String channelId) {
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
	private UUID stageAndQueue(Ctx ctx, byte[] video) {
		JsonNode data = json(create(ctx.host(), ctx.teacherToken(), body("Video", video.length))).path("data");
		UUID assetId = assetIdOf(data);
		MvcResult completed = complete(ctx.host(), ctx.teacherToken(), assetId, putParts(data, video));
		if (status(completed) != 202) {
			throw new IllegalStateException("complete failed: " + rawContent(completed));
		}
		return assetId;
	}

	/** Plays the browser: PUTs every part to its presigned URL. @return the completed-part list */
	private static List<Map<String, Object>> putParts(JsonNode createData, byte[] video) {
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

	private static Map<String, Object> part(int number, String etag) {
		return Map.of("partNumber", number, "etag", etag);
	}

	private static Map<String, Object> body(String title, long size) {
		return Map.of("title", title, "fileName", "lesson.mp4", "mimeType", "video/mp4", "sizeBytes", size);
	}

	/** A minimal ISO-BMFF ("ftyp" box) header followed by deterministic filler. */
	private static byte[] mp4(int size) {
		byte[] bytes = new byte[size];
		new Random(size).nextBytes(bytes);
		byte[] header = { 0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 0, 0, 2, 0 };
		System.arraycopy(header, 0, bytes, 0, header.length);
		return bytes;
	}

	private MvcResult create(String host, String token, Map<String, Object> body) {
		return perform(authenticated(post(MEDIA).contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(body)), host, token));
	}

	private MvcResult complete(String host, String token, UUID assetId, List<Map<String, Object>> parts) {
		return perform(authenticated(post(MEDIA + "/{id}/upload/complete", assetId)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("parts", parts))), host, token));
	}

	private MvcResult presign(String host, String token, UUID assetId, List<Integer> partNumbers) {
		return perform(authenticated(post(MEDIA + "/{id}/upload/parts", assetId)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("partNumbers", partNumbers))), host, token));
	}

	private MvcResult cancel(String host, String token, UUID assetId) {
		return perform(authenticated(post(MEDIA + "/{id}/upload/cancel", assetId), host, token));
	}

	private MvcResult platformPost(String path, String host, String token, Map<String, Object> body) {
		return perform(authenticated(post(path).contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(body)), host, token));
	}

	private static UUID assetIdOf(JsonNode createData) {
		return UUID.fromString(createData.path("mediaAsset").path("id").asString());
	}

	private Map<String, Object> latestJob(UUID assetId) {
		return jdbcTemplate.queryForMap("SELECT * FROM media_upload_job WHERE video_asset_id = ? "
				+ "ORDER BY created_at DESC, id DESC LIMIT 1", assetId);
	}

	private String jobStatus(UUID assetId) {
		return (String) latestJob(assetId).get("status");
	}

	private String stagingKey(UUID assetId) {
		return (String) latestJob(assetId).get("staging_object_key");
	}

	private String assetStatus(UUID assetId) {
		return jdbcTemplate.queryForObject("SELECT status FROM video_asset WHERE id = ?", String.class, assetId);
	}

	private String providerAssetId(UUID assetId) {
		return jdbcTemplate.queryForObject("SELECT provider_asset_id FROM video_asset WHERE id = ?", String.class,
				assetId);
	}

	private int assetCount(UUID tenantId) {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM video_asset WHERE tenant_id = ?", Integer.class,
				tenantId);
	}

	private Map<String, Object> usage(UUID tenantId) {
		return jdbcTemplate.queryForMap("SELECT coalesce(sum(uploads), 0) AS uploads, coalesce(sum(bytes), 0) AS bytes,"
				+ " coalesce(sum(api_units), 0) AS api_units FROM tenant_video_usage_daily WHERE tenant_id = ?",
				tenantId);
	}

	private UUID currentConnection(UUID tenantId) {
		return jdbcTemplate.queryForObject("SELECT id FROM media_provider_connection WHERE tenant_id = ? "
				+ "AND provider = 'YOUTUBE' AND scope = 'TENANT' AND status <> 'DISCONNECTED'", UUID.class, tenantId);
	}

	private String accessTokenOf(UUID tenantId) {
		String ciphertext = jdbcTemplate.queryForObject(
				"SELECT credentials_ciphertext FROM media_provider_connection WHERE id = ?", String.class,
				currentConnection(tenantId));
		return codec.open(ciphertext, new CredentialContext(tenantId, MediaProvider.YOUTUBE, null)).accessToken();
	}

	private void makeJobsDue(UUID tenantId) {
		jdbcTemplate.update("UPDATE media_upload_job SET next_attempt_at = now() - interval '1 second' "
				+ "WHERE tenant_id = ? AND status IN ('RETRY_WAIT', 'QUOTA_PAUSED')", tenantId);
	}

	private void makeSyncDue(UUID tenantId) {
		jdbcTemplate.update("UPDATE video_asset SET next_sync_at = now() - interval '1 second' "
				+ "WHERE tenant_id = ? AND status IN ('UPLOADED', 'PROCESSING')", tenantId);
	}

	private void seedVideoConfiguration(UUID tenantId, boolean uploadsEnabled, int dailyLimit) {
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
