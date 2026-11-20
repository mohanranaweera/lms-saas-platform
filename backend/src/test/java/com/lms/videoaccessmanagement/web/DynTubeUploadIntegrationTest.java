package com.lms.videoaccessmanagement.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.web.dto.CourseLessonResponse;
import com.lms.coursemanagement.course.web.dto.CourseModuleResponse;
import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.integrationmanagement.media.FakeDynTube;
import com.lms.integrationmanagement.media.FakeMediaProviderServer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * VIDEO Phase 13 (ADR-026) - DynTube as the institute's managed streaming
 * host, against the fake DynTube API (shapes verified live 2026-10-10):
 * account keys (write-only token key, project check, audit without
 * secrets), upload â†’ whole-file transfer â†’ status sync â†’ READY with the
 * playback key, refusals, provider failures and tenant isolation; and WEB
 * playback as a short-lived tokenized HLS playlist signed with the stored
 * token key (the AOC Player path is in {@code PlayerIntegrationTest}).
 */
class DynTubeUploadIntegrationTest extends MediaUploadTestSupport {


	@BeforeEach
	void enableDynTube() {
		featureFlags.setDyntubeUpload(true);
		featureFlags.setManagedStreamPlayback(true);
	}

	@AfterEach
	void restoreFlags() {
		featureFlags.setDyntubeUpload(false);
		featureFlags.setManagedStreamPlayback(false);
	}

	// ------------------------------------------------------------------
	// Account settings
	// ------------------------------------------------------------------

	@Test
	void theTokenKeyIsWriteOnlyAndTheProjectMustBelongToTheAccount() {
		Ctx ctx = seedUploadTenant("dt-keys", null);

		MvcResult saved = saveDynTube(ctx, FakeMediaProviderServer.GOOD_DYNTUBE_KEY, TOKEN_KEY,
				FakeDynTube.GOOD_PROJECT);

		assertThat(status(saved)).isEqualTo(200);
		JsonNode settings = json(saved).path("data").path("settings");
		assertThat(settings.path("projectId").asString()).isEqualTo(FakeDynTube.GOOD_PROJECT);
		assertThat(settings.path("tokenSecurityKeyConfigured").asBoolean()).isTrue();
		assertThat(rawContent(saved)).doesNotContain(TOKEN_KEY)
			.doesNotContain(FakeMediaProviderServer.GOOD_DYNTUBE_KEY);
		List<String> audit = jdbcTemplate.queryForList("SELECT metadata::text FROM audit_log WHERE tenant_id = ? "
				+ "AND action = 'media_integration.connected'", String.class, ctx.tenant().getId());
		assertThat(audit).isNotEmpty()
			.allSatisfy(metadata -> assertThat(metadata).doesNotContain(TOKEN_KEY)
				.doesNotContain(FakeMediaProviderServer.GOOD_DYNTUBE_KEY));
		String ciphertext = jdbcTemplate.queryForObject("SELECT credentials_ciphertext FROM media_provider_connection "
				+ "WHERE tenant_id = ? AND provider = 'DYNTUBE' AND status = 'ACTIVE'", String.class,
				ctx.tenant().getId());
		assertThat(ciphertext).doesNotContain(TOKEN_KEY);

		MvcResult unknownProject = saveDynTube(ctx, null, null, "projNotMine0000000001");
		assertThat(status(unknownProject)).isEqualTo(400);
		assertThat(rawContent(unknownProject)).contains("projectId");

		MvcResult badKey = saveDynTube(ctx, "wrong-key", null, null);
		assertThat(status(badKey)).isEqualTo(400);
	}

	// ------------------------------------------------------------------
	// Happy path
	// ------------------------------------------------------------------

	@Test
	void anUploadIsStreamedToTheProjectAndBecomesReadyWithItsPlaybackKey() {
		Ctx ctx = dynTubeTenant("dt-happy");
		byte[] video = mp4(PART * 2 + 500);

		MvcResult created = create(ctx.host(), ctx.teacherToken(), dynTubeBody("Lesson 1", video.length));
		assertThat(status(created)).isEqualTo(201);
		JsonNode data = json(created).path("data");
		assertThat(data.path("mediaAsset").path("provider").asString()).isEqualTo("DYNTUBE");
		UUID assetId = assetIdOf(data);
		assertThat(status(complete(ctx.host(), ctx.teacherToken(), assetId, putParts(data, video)))).isEqualTo(202);

		assertThat(worker.drain(5)).isEqualTo(1);

		assertThat(jobStatus(assetId)).isEqualTo("COMPLETED");
		assertThat(assetStatus(assetId)).isEqualTo("PROCESSING");
		FakeDynTube.Upload upload = DYNTUBE.uploads().getFirst();
		assertThat(upload.authorization()).isEqualTo("Bearer " + FakeMediaProviderServer.GOOD_DYNTUBE_KEY);
		assertThat(upload.projectId()).isEqualTo(FakeDynTube.GOOD_PROJECT);
		assertThat(upload.title()).isEqualTo("Lesson 1");
		assertThat(upload.fileBytes()).isEqualTo(video.length);
		String videoId = providerAssetId(assetId);
		assertThat(DYNTUBE.video(videoId)).isNotNull();
		assertThat(STAGING.hasObject(stagingKey(assetId))).isFalse();

		// Still processing at DynTube: checked again later, no key yet.
		makeSyncDue(ctx.tenant().getId());
		statusSync.syncDue();
		assertThat(assetStatus(assetId)).isEqualTo("PROCESSING");
		assertThat(playbackKey(assetId)).isNull();

		DYNTUBE.publish(videoId);
		makeSyncDue(ctx.tenant().getId());
		statusSync.syncDue();

		assertThat(assetStatus(assetId)).isEqualTo("READY");
		assertThat(playbackKey(assetId)).isEqualTo(DYNTUBE.video(videoId).key());
		assertThat(embedKey(assetId)).isEqualTo(FakeDynTube.embedKeyOf(videoId));
		MvcResult read = perform(authenticated(get(MEDIA + "/{id}", assetId), ctx.host(), ctx.teacherToken()));
		JsonNode asset = json(read).path("data");
		assertThat(asset.path("processingStatus").asString()).isEqualTo("READY");
		assertThat(asset.path("durationSeconds").asInt()).isEqualTo(66);
		assertThat(rawContent(read)).doesNotContain(DYNTUBE.video(videoId).key());
	}

	@Test
	void aVideoDynTubeCouldNotProcessIsMarkedFailed() {
		Ctx ctx = dynTubeTenant("dt-failed");
		UUID assetId = stageAndQueueDynTube(ctx, mp4(PART));
		worker.drain(5);

		DYNTUBE.fail(providerAssetId(assetId));
		makeSyncDue(ctx.tenant().getId());
		statusSync.syncDue();

		assertThat(assetStatus(assetId)).isEqualTo("FAILED");
		assertThat(jdbcTemplate.queryForObject("SELECT provider_failure_reason FROM video_asset WHERE id = ?",
				String.class, assetId)).isEqualTo("DynTube could not process this video.");
	}

	@Test
	void editsStayInTheLmsAndWorkEvenWhileTheAccountNeedsReconnecting() {
		Ctx ctx = dynTubeTenant("dt-edit");
		UUID assetId = readyDynTubeAsset(ctx);
		jdbcTemplate.update("UPDATE media_provider_connection SET status = 'NEEDS_RECONNECT' "
				+ "WHERE tenant_id = ? AND provider = 'DYNTUBE' AND status = 'ACTIVE'", ctx.tenant().getId());
		int callsBefore = PROVIDERS.requests().size();

		MvcResult renamed = perform(authenticated(patch(MEDIA + "/{id}", assetId)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":\"Renamed lesson\"}"), ctx.host(), ctx.teacherToken()));

		assertThat(status(renamed)).isEqualTo(200);
		assertThat(json(renamed).path("data").path("title").asString()).isEqualTo("Renamed lesson");
		assertThat(PROVIDERS.requests().size()).isEqualTo(callsBefore);
	}

	@Test
	void theMediaLibraryShowsADynTubeTabAndItsUploadLimitsOnceDynTubeIsTheVideoHost() {
		Ctx ctx = seedUploadTenant("dt-tab", null);
		JsonNode before = json(perform(authenticated(get(MEDIA + "/channels"), ctx.host(), ctx.teacherToken())))
			.path("data");
		assertThat(before.path("managedHost").isNull()).isTrue();

		saveDynTube(ctx, FakeMediaProviderServer.GOOD_DYNTUBE_KEY, TOKEN_KEY, FakeDynTube.GOOD_PROJECT);
		selectVideoHost(ctx, "DYNTUBE");
		JsonNode tab = json(perform(authenticated(get(MEDIA + "/channels"), ctx.host(), ctx.teacherToken())))
			.path("data").path("managedHost");
		assertThat(tab.path("provider").asString()).isEqualTo("DYNTUBE");
		assertThat(tab.path("connected").asBoolean()).isTrue();
		assertThat(tab.path("canUpload").asBoolean()).isTrue();
		assertThat(tab.path("uploadBlockedReason").isNull()).isTrue();

		JsonNode limits = json(perform(authenticated(get(MEDIA + "/upload-limits").param("provider", "DYNTUBE"),
				ctx.host(), ctx.teacherToken()))).path("data");
		assertThat(limits.path("channel").asString()).isEqualTo("CONNECTED");
		assertThat(limits.path("uploadsEnabled").asBoolean()).isTrue();

		featureFlags.setDyntubeUpload(false);
		JsonNode off = json(perform(authenticated(get(MEDIA + "/upload-limits").param("provider", "DYNTUBE"),
				ctx.host(), ctx.teacherToken()))).path("data");
		assertThat(off.path("uploadsDisabledBy").asString()).isEqualTo("PLATFORM");
		JsonNode offTab = json(perform(authenticated(get(MEDIA + "/channels"), ctx.host(), ctx.teacherToken())))
			.path("data").path("managedHost");
		assertThat(offTab.path("canUpload").asBoolean()).isFalse();
		assertThat(offTab.path("uploadBlockedReason").asString()).contains("switched off");
		assertThat(status(perform(authenticated(get(MEDIA + "/upload-limits").param("provider", "DYNTUBE"),
				ctx.host(), ctx.studentToken())))).isEqualTo(403);
	}

	@Test
	void theDynTubeTabSaysWhyUploadingIsUnavailableUntilTheAccountIsFullySetUp() {
		Ctx ctx = seedUploadTenant("dt-why", null);
		saveDynTube(ctx, FakeMediaProviderServer.GOOD_DYNTUBE_KEY, TOKEN_KEY, null);
		selectVideoHost(ctx, "DYNTUBE");

		JsonNode noProject = json(perform(authenticated(get(MEDIA + "/channels"), ctx.host(), ctx.teacherToken())))
			.path("data").path("managedHost");
		assertThat(noProject.path("canUpload").asBoolean()).isFalse();
		assertThat(noProject.path("uploadBlockedReason").asString()).contains("No DynTube project");

		Ctx noKey = seedUploadTenant("dt-why-key", null);
		saveDynTube(noKey, FakeMediaProviderServer.GOOD_DYNTUBE_KEY, null, FakeDynTube.GOOD_PROJECT);
		selectVideoHost(noKey, "DYNTUBE");
		JsonNode missingKey = json(perform(authenticated(get(MEDIA + "/channels"), noKey.host(), noKey.teacherToken())))
			.path("data").path("managedHost");
		assertThat(missingKey.path("canUpload").asBoolean()).isFalse();
		assertThat(missingKey.path("uploadBlockedReason").asString()).contains("token security key");
	}

	// ------------------------------------------------------------------
	// Refusals
	// ------------------------------------------------------------------

	@Test
	void uploadsAreRefusedUntilThePlatformAndTheAccountAreReady() {
		Ctx ctx = seedUploadTenant("dt-refuse", null);
		Map<String, Object> body = dynTubeBody("Video", PART);

		MvcResult notConnected = create(ctx.host(), ctx.teacherToken(), body);
		assertThat(status(notConnected)).isEqualTo(409);
		assertThat(errorCode(notConnected)).isEqualTo("VIDEO_PROVIDER_NOT_CONNECTED");

		assertThat(status(saveDynTube(ctx, FakeMediaProviderServer.GOOD_DYNTUBE_KEY, null, FakeDynTube.GOOD_PROJECT)))
			.isEqualTo(200);
		MvcResult notTheVideoHost = create(ctx.host(), ctx.teacherToken(), body);
		assertThat(status(notTheVideoHost)).isEqualTo(409);
		assertThat(rawContent(notTheVideoHost)).contains("video host");

		selectVideoHost(ctx, "DYNTUBE");
		MvcResult noTokenKey = create(ctx.host(), ctx.teacherToken(), body);
		assertThat(status(noTokenKey)).isEqualTo(409);
		assertThat(rawContent(noTokenKey)).contains("token security key");

		assertThat(status(saveDynTube(ctx, null, TOKEN_KEY, null))).isEqualTo(200);
		featureFlags.setDyntubeUpload(false);
		MvcResult platformOff = create(ctx.host(), ctx.teacherToken(), body);
		assertThat(status(platformOff)).isEqualTo(409);
		assertThat(errorCode(platformOff)).isEqualTo("VIDEO_UPLOADS_DISABLED");

		featureFlags.setDyntubeUpload(true);
		assertThat(status(create(ctx.host(), ctx.teacherToken(), body))).isEqualTo(201);
		assertThat(status(create(ctx.host(), ctx.studentToken(), body))).isEqualTo(403);
	}

	// ------------------------------------------------------------------
	// Provider failures
	// ------------------------------------------------------------------

	@Test
	void aRefusedFileFailsAndATransientErrorIsRetried() {
		Ctx ctx = dynTubeTenant("dt-errors");
		UUID refused = stageAndQueueDynTube(ctx, mp4(PART));
		DYNTUBE.setUploadStatus(415);
		worker.drain(5);
		assertThat(jobStatus(refused)).isEqualTo("FAILED");
		assertThat(assetStatus(refused)).isEqualTo("FAILED");

		UUID transient503 = stageAndQueueDynTube(ctx, mp4(PART));
		DYNTUBE.setUploadStatus(503);
		worker.drain(5);
		assertThat(jobStatus(transient503)).isEqualTo("RETRY_WAIT");

		DYNTUBE.setUploadStatus(200);
		makeJobsDue(ctx.tenant().getId());
		worker.drain(5);
		assertThat(jobStatus(transient503)).isEqualTo("COMPLETED");
		assertThat(STAGING.hasObject(stagingKey(transient503))).isFalse();
	}

	@Test
	void aRejectedApiKeyParksTheUploadAndMarksTheAccountForReconnecting() {
		Ctx ctx = dynTubeTenant("dt-auth");
		UUID assetId = stageAndQueueDynTube(ctx, mp4(PART));
		DYNTUBE.setUploadStatus(401);

		worker.drain(5);

		assertThat(jobStatus(assetId)).isNotIn("COMPLETED", "TRANSFERRING");
		assertThat(jdbcTemplate.queryForObject("SELECT status FROM media_provider_connection WHERE tenant_id = ? "
				+ "AND provider = 'DYNTUBE' AND status <> 'DISCONNECTED'", String.class, ctx.tenant().getId()))
			.isEqualTo("NEEDS_RECONNECT");
	}

	// ------------------------------------------------------------------
	// Tenant isolation
	// ------------------------------------------------------------------

	@Test
	@Tag("cross-tenant")
	void anotherTenantCannotReadOrCompleteADynTubeUpload() {
		Ctx a = dynTubeTenant("dt-iso-a");
		Ctx b = dynTubeTenant("dt-iso-b");
		JsonNode data = json(create(a.host(), a.teacherToken(), dynTubeBody("A's video", PART))).path("data");
		UUID assetId = assetIdOf(data);

		assertThat(status(perform(authenticated(get(MEDIA + "/{id}", assetId), b.host(), b.adminToken()))))
			.isEqualTo(404);
		assertThat(status(complete(b.host(), b.teacherToken(), assetId, putParts(data, mp4(PART))))).isEqualTo(404);
		assertThat(assetStatus(assetId)).isEqualTo("CREATED");
	}

	// ------------------------------------------------------------------
	// WEB playback (DynTube's player through the bridge, ADR-026 Amendment 1)
	// ------------------------------------------------------------------

	@Test
	void aStudentGetsAnEmbedSessionWithTheStoredEmbedKeyAndNoSignedUrl() {
		Ctx ctx = dynTubeTenant("dt-play");
		UUID assetId = readyDynTubeAsset(ctx);
		lessonWith(ctx, "dt-play", assetId);
		String videoId = providerAssetId(assetId);

		MvcResult issued = playbackSession(ctx, ctx.studentToken(), assetId);

		assertThat(status(issued)).isEqualTo(201);
		JsonNode session = json(issued).path("data");
		assertThat(session.path("mediaFormat").asString()).isEqualTo("EMBED");
		assertThat(session.path("watchSessionId").asString()).isNotBlank();
		assertThat(session.path("signedUrl").isNull()).isTrue();
		assertThat(session.path("embed").path("provider").asString()).isEqualTo("DYNTUBE");
		assertThat(session.path("embed").path("key").asString()).isEqualTo(FakeDynTube.embedKeyOf(videoId));
		assertThat(session.path("embed").path("bridgeUrl").asString())
			.isEqualTo("http://embed.lms.test:3000/embed/dyntube-bridge.html");
		assertThat(rawContent(issued)).doesNotContain(TOKEN_KEY)
			.doesNotContain(FakeMediaProviderServer.GOOD_DYNTUBE_KEY)
			.doesNotContain(DYNTUBE.video(videoId).key());
	}

	@Test
	void withManagedStreamingOffOrTheAccountNeedingReconnectNothingStarts() {
		Ctx ctx = dynTubeTenant("dt-play-off");
		UUID assetId = readyDynTubeAsset(ctx);
		lessonWith(ctx, "dt-play-off", assetId);

		featureFlags.setManagedStreamPlayback(false);
		assertThat(status(playbackSession(ctx, ctx.studentToken(), assetId))).isEqualTo(409);

		featureFlags.setManagedStreamPlayback(true);
		jdbcTemplate.update("UPDATE media_provider_connection SET status = 'NEEDS_RECONNECT' "
				+ "WHERE tenant_id = ? AND provider = 'DYNTUBE' AND status = 'ACTIVE'", ctx.tenant().getId());
		assertThat(status(playbackSession(ctx, ctx.studentToken(), assetId))).isEqualTo(409);

		// Refused before any session existed: nothing holds the concurrency slot.
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM video_watch_session WHERE video_asset_id = ?",
				Integer.class, assetId)).isZero();
	}

	@Test
	void aTeacherPreviewIsAlsoAnEmbedSession() {
		Ctx ctx = dynTubeTenant("dt-preview");
		UUID assetId = readyDynTubeAsset(ctx);
		lessonWith(ctx, "dt-preview", assetId);

		JsonNode preview = json(playbackSession(ctx, ctx.teacherToken(), assetId)).path("data");

		assertThat(preview.path("mediaFormat").asString()).isEqualTo("EMBED");
		assertThat(preview.path("watchSessionId").isNull()).isTrue();
		assertThat(preview.path("embed").path("key").asString())
			.isEqualTo(FakeDynTube.embedKeyOf(providerAssetId(assetId)));
	}

	@Test
	void anAlreadyReadyVideoWithoutAnEmbedKeyGetsItFromTheNextSyncAndCannotPlayUntilThen() {
		Ctx ctx = dynTubeTenant("dt-backfill");
		UUID assetId = readyDynTubeAsset(ctx);
		lessonWith(ctx, "dt-backfill", assetId);
		// As left by V83 for a video synced before Phase 13c.
		jdbcTemplate.update("UPDATE video_asset SET provider_embed_key = NULL, next_sync_at = now() - interval '1 second' "
				+ "WHERE id = ?", assetId);

		assertThat(status(playbackSession(ctx, ctx.studentToken(), assetId))).isEqualTo(409);

		DYNTUBE.setWithholdChannelKey(true);
		statusSync.syncDue();
		assertThat(embedKey(assetId)).isNull();
		assertThat(assetStatus(assetId)).isEqualTo("READY");

		DYNTUBE.setWithholdChannelKey(false);
		jdbcTemplate.update("UPDATE video_asset SET next_sync_at = now() - interval '1 second' WHERE id = ?", assetId);
		statusSync.syncDue();

		assertThat(assetStatus(assetId)).isEqualTo("READY");
		assertThat(embedKey(assetId)).isEqualTo(FakeDynTube.embedKeyOf(providerAssetId(assetId)));
		assertThat(jdbcTemplate.queryForObject("SELECT next_sync_at FROM video_asset WHERE id = ?", Instant.class,
				assetId)).isNull();
		assertThat(status(playbackSession(ctx, ctx.studentToken(), assetId))).isEqualTo(201);
	}

	@Test
	@Tag("cross-tenant")
	void anotherTenantsStudentCannotPlayADynTubeVideo() {
		Ctx a = dynTubeTenant("dt-play-iso-a");
		Ctx b = dynTubeTenant("dt-play-iso-b");
		UUID assetA = readyDynTubeAsset(a);
		lessonWith(a, "dt-play-iso-a", assetA);

		MvcResult result = playbackSession(b, b.studentToken(), assetA);

		assertThat(status(result)).isEqualTo(404);
		assertThat(rawContent(result)).doesNotContain(FakeDynTube.embedKeyOf(providerAssetId(assetA)));
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	/** A published course with the video attached to its lesson and the tenant's student enrolled. */
	private void lessonWith(Ctx ctx, String prefix, UUID assetId) {
		CourseResponse course = createCourseOrFail(ctx.host(), ctx.teacherToken(),
				newCourseRequest(uniqueSlug(prefix), ctx.teacherId(), CourseStatus.PUBLIC));
		CourseModuleResponse module = createModuleOrFail(ctx.host(), ctx.teacherToken(), course.id(), "Module 1", 1);
		CourseLessonResponse lesson = createLessonOrFail(ctx.host(), ctx.teacherToken(), course.id(), module.id(),
				"Lesson 1", 1);
		MvcResult attached = perform(authenticated(post("/api/v1/courses/{courseId}/lessons/{lessonId}/media",
				course.id(), lesson.id()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"mediaAssetId\":\"" + assetId + "\",\"sequence\":1}"), ctx.host(), ctx.teacherToken()));
		if (status(attached) != 201) {
			throw new IllegalStateException("attach failed: " + rawContent(attached));
		}
		enrollStudentOrFail(ctx.host(), ctx.studentToken(), course.id());
	}

	private MvcResult playbackSession(Ctx ctx, String token, UUID assetId) {
		return perform(authenticated(post("/api/v1/videos/{id}/playback-sessions", assetId), ctx.host(), token));
	}

	/** DynTube's documented token, computed independently of the production signer. */
	private static String dynTubeToken(String securityKey, long expiresMillis, String videoKey) {
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256")
				.digest((securityKey + expiresMillis + videoKey).getBytes(StandardCharsets.UTF_8));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}


	private String embedKey(UUID assetId) {
		return jdbcTemplate.queryForObject("SELECT provider_embed_key FROM video_asset WHERE id = ?", String.class,
				assetId);
	}

	private String playbackKey(UUID assetId) {
		return jdbcTemplate.queryForObject("SELECT provider_playback_key FROM video_asset WHERE id = ?", String.class,
				assetId);
	}

}
