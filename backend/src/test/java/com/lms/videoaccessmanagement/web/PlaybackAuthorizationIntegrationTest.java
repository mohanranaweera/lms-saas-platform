package com.lms.videoaccessmanagement.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.web.dto.CourseLessonResponse;
import com.lms.coursemanagement.course.web.dto.CourseModuleResponse;
import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.videoaccessmanagement.domain.PlaybackMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * VIDEO Phase 7 (B3-only WEB scope): authenticated Student playback
 * authorization for LessonMedia, {@code POST /api/v1/playback/sessions}.
 * A real YouTube asset is uploaded through the fake provider, made READY by
 * status sync and attached to a lesson as LessonMedia.
 */
class PlaybackAuthorizationIntegrationTest extends MediaUploadTestSupport {

	private static final String PLAYBACK = "/api/v1/playback/sessions";

	private record Lesson(Ctx ctx, UUID courseId, UUID lessonId, UUID assetId, String videoId, UUID lessonMediaId) {
	}

	@Test
	void anEnrolledStudentGetsAnAocPlayerLaunchAndNeverTheYouTubeVideoId() {
		Lesson lesson = youTubeLesson("pb-ok", true);
		withYouTubePlayback(true, () -> {
			// The institute does not require the AOC Player; YouTube lessons still use it (ADR-025 Amendment 3).
			MvcResult result = play(lesson.ctx().host(), lesson.ctx().studentToken(), lesson.lessonMediaId());

			assertThat(status(result)).isEqualTo(201);
			JsonNode data = json(result).path("data");
			assertThat(data.path("channel").asString()).isEqualTo("NATIVE");
			assertThat(data.path("descriptor").isNull()).isTrue();
			assertThat(data.path("launch").path("url").asString()).startsWith("aocplayer://play?code=");
			String raw = rawContent(result);
			assertThat(raw).doesNotContain(lesson.videoId())
				.doesNotContain("youtube")
				.doesNotContain(accessTokenOf(lesson.ctx().tenant().getId()))
				.doesNotContain(lesson.assetId().toString())
				.doesNotContain("integrationId")
				.doesNotContain("connection");
		});
	}

	@Test
	void youTubeLessonsAreRefusedNotDowngradedWhileTheAocPlayerIsSwitchedOff() {
		Lesson lesson = youTubeLesson("pb-native-off", true);
		withYouTubePlayback(true, () -> {
			boolean previous = featureFlags.isNativePlayer();
			featureFlags.setNativePlayer(false);
			try {
				MvcResult result = play(lesson.ctx().host(), lesson.ctx().studentToken(), lesson.lessonMediaId());
				assertThat(status(result)).isEqualTo(409);
				assertThat(errorCode(result)).isEqualTo("NATIVE_PLAYER_UNAVAILABLE");
				assertThat(rawContent(result)).doesNotContain(lesson.videoId());
			}
			finally {
				featureFlags.setNativePlayer(previous);
			}
		});
	}

	@Test
	void youTubePlaybackIsRefusedWhileThePlatformFlagIsOff() {
		Lesson lesson = youTubeLesson("pb-flag", true);
		withYouTubePlayback(false, () -> {
			MvcResult result = play(lesson.ctx().host(), lesson.ctx().studentToken(), lesson.lessonMediaId());
			assertThat(status(result)).isEqualTo(409);
			assertThat(errorCode(result)).isEqualTo("VIDEO_PLAYBACK_DISABLED");
		});
	}

	@Test
	void aStudentWhoIsNotEnrolledGets404() {
		Lesson lesson = youTubeLesson("pb-enrol", false);
		withYouTubePlayback(true, () -> assertThat(
				status(play(lesson.ctx().host(), lesson.ctx().studentToken(), lesson.lessonMediaId()))).isEqualTo(404));
	}

	@Test
	void staffCannotCreateStudentPlayback() {
		Lesson lesson = youTubeLesson("pb-staff", true);
		withYouTubePlayback(true, () -> {
			MvcResult teacher = play(lesson.ctx().host(), lesson.ctx().teacherToken(), lesson.lessonMediaId());
			assertThat(status(teacher)).isEqualTo(403);
			assertThat(errorCode(teacher)).isEqualTo("PLAYBACK_NOT_AUTHORIZED");
			assertThat(status(play(lesson.ctx().host(), lesson.ctx().adminToken(), lesson.lessonMediaId())))
				.isEqualTo(403);
		});
	}

	@Test
	void anUnauthenticatedCallIsRejected() {
		Lesson lesson = youTubeLesson("pb-anon", true);
		MvcResult result = perform(withHost(post(PLAYBACK).contentType(MediaType.APPLICATION_JSON)
			.content("{\"lessonMediaId\":\"" + lesson.lessonMediaId() + "\"}"), lesson.ctx().host()));
		assertThat(status(result)).isEqualTo(401);
	}

	@Test
	void hiddenLessonMediaIsNotFoundForTheStudent() {
		Lesson lesson = youTubeLesson("pb-hidden", true);
		MvcResult hidden = perform(authenticated(patch("/api/v1/courses/{courseId}/lessons/{lessonId}/media/{id}",
				lesson.courseId(), lesson.lessonId(), lesson.lessonMediaId()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":\"Hidden\",\"sequence\":1,\"visibility\":\"HIDDEN\"}"), lesson.ctx().host(),
				lesson.ctx().teacherToken()));
		assertThat(status(hidden)).isEqualTo(200);
		withYouTubePlayback(true, () -> assertThat(
				status(play(lesson.ctx().host(), lesson.ctx().studentToken(), lesson.lessonMediaId()))).isEqualTo(404));
	}

	@Test
	void unknownLessonMediaIsNotFound() {
		Lesson lesson = youTubeLesson("pb-unknown", true);
		withYouTubePlayback(true, () -> assertThat(
				status(play(lesson.ctx().host(), lesson.ctx().studentToken(), UUID.randomUUID()))).isEqualTo(404));
	}

	@Test
	void aChannelSwitchedOffByThePlatformStopsPlayback() {
		Lesson lesson = youTubeLesson("pb-switch", true);
		String email = "pb-platform-" + UUID.randomUUID().toString().substring(0, 8) + "@platform.test";
		seedPlatformAdmin(email, RAW_PASSWORD);
		String platformToken = platformAdminLogin(null, email, RAW_PASSWORD).getBody().data().accessToken();
		assertThat(status(platformPost("/api/v1/platform-admin/tenants/" + lesson.ctx().tenant().getId()
				+ "/video-providers/" + currentConnection(lesson.ctx().tenant().getId()) + "/disable", null,
				platformToken, Map.of("reason", "Abuse report")))).isEqualTo(200);

		withYouTubePlayback(true, () -> {
			MvcResult result = play(lesson.ctx().host(), lesson.ctx().studentToken(), lesson.lessonMediaId());
			assertThat(status(result)).isEqualTo(409);
			assertThat(errorCode(result)).isEqualTo("VIDEO_PROVIDER_DISABLED_BY_PLATFORM");
		});
	}

	@Test
	void internalLessonMediaStaysOnTheLegacyEndpoint() {
		Lesson lesson = youTubeLesson("pb-internal", true);
		UUID internalAsset = seedInternalReadyAsset(lesson.ctx());
		UUID internalMedia = attach(lesson.ctx(), lesson.courseId(), lesson.lessonId(), internalAsset, 2);
		withYouTubePlayback(true, () -> assertThat(
				status(play(lesson.ctx().host(), lesson.ctx().studentToken(), internalMedia))).isEqualTo(409));
	}

	@Test
	void theLegacyVideoEndpointRefusesYouTubeAssetsWithoutCreatingASession() {
		Lesson lesson = youTubeLesson("pb-legacy", true);
		String path = "/api/v1/videos/" + lesson.assetId() + "/playback-sessions";

		MvcResult student = perform(authenticated(post(path), lesson.ctx().host(), lesson.ctx().studentToken()));
		assertThat(status(student)).isEqualTo(409);
		MvcResult teacher = perform(authenticated(post(path), lesson.ctx().host(), lesson.ctx().teacherToken()));
		assertThat(status(teacher)).isEqualTo(409);

		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM video_watch_session WHERE video_asset_id = ?",
				Integer.class, lesson.assetId())).isZero();
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM video_watch_progress WHERE video_asset_id = ?",
				Integer.class, lesson.assetId())).isZero();
	}

	@Tag("cross-tenant")
	@Test
	void aStudentOfAnotherTenantCannotPlayForeignLessonMedia() {
		Lesson a = youTubeLesson("pb-iso-a", true);
		Lesson b = youTubeLesson("pb-iso-b", true);
		withYouTubePlayback(true, () -> {
			// T8: B's student, on B's host, addressing A's LessonMedia id.
			MvcResult foreign = play(b.ctx().host(), b.ctx().studentToken(), a.lessonMediaId());
			assertThat(status(foreign)).isEqualTo(404);
			assertThat(rawContent(foreign)).doesNotContain(a.videoId());

			// B's student token presented on A's host does not reach A's tenant.
			MvcResult wrongHost = play(a.ctx().host(), b.ctx().studentToken(), a.lessonMediaId());
			assertThat(status(wrongHost)).isNotEqualTo(201);
			assertThat(rawContent(wrongHost)).doesNotContain(a.videoId());

			// Each tenant's own student still plays its own lesson.
			assertThat(status(play(a.ctx().host(), a.ctx().studentToken(), a.lessonMediaId()))).isEqualTo(201);
			assertThat(status(play(b.ctx().host(), b.ctx().studentToken(), b.lessonMediaId()))).isEqualTo(201);
		});
	}

	@Tag("cross-tenant")
	@Test
	void aTenantIdInjectedIntoTheRequestIsIgnored() {
		Lesson a = youTubeLesson("pb-inject-a", true);
		Lesson b = youTubeLesson("pb-inject-b", true);
		withYouTubePlayback(true, () -> {
			// T12: A's tenant id in query, header and body; B's student on B's host.
			MvcResult result = perform(authenticated(post(PLAYBACK + "?tenantId=" + a.ctx().tenant().getId())
				.header("X-Tenant-Id", a.ctx().tenant().getId().toString())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"lessonMediaId\":\"" + a.lessonMediaId() + "\",\"tenantId\":\"" + a.ctx().tenant().getId()
						+ "\"}"), b.ctx().host(), b.ctx().studentToken()));
			assertThat(status(result)).isNotEqualTo(201);
			assertThat(rawContent(result)).doesNotContain(a.videoId());
		});
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	/** A tenant with a READY YouTube asset attached to a lesson; the student is enrolled when asked. */
	private Lesson youTubeLesson(String prefix, boolean enrollStudent) {
		Ctx ctx = seedUploadTenant(prefix, "UC-" + prefix);
		UUID assetId = stageAndQueue(ctx, mp4(PART + 1000));
		assertThat(worker.drain(5)).isEqualTo(1);
		String videoId = providerAssetId(assetId);
		YOUTUBE.setVideoStatus(videoId, "processed");
		makeSyncDue(ctx.tenant().getId());
		statusSync.syncDue();
		assertThat(assetStatus(assetId)).isEqualTo("READY");

		CourseResponse course = createCourseOrFail(ctx.host(), ctx.teacherToken(),
				newCourseRequest(uniqueSlug(prefix), ctx.teacherId(), CourseStatus.PUBLIC));
		CourseModuleResponse module = createModuleOrFail(ctx.host(), ctx.teacherToken(), course.id(), "Module 1", 1);
		CourseLessonResponse lesson = createLessonOrFail(ctx.host(), ctx.teacherToken(), course.id(), module.id(),
				"Lesson 1", 1);
		UUID lessonMediaId = attach(ctx, course.id(), lesson.id(), assetId, 1);
		if (enrollStudent) {
			enrollStudentOrFail(ctx.host(), ctx.studentToken(), course.id());
		}
		return new Lesson(ctx, course.id(), lesson.id(), assetId, videoId, lessonMediaId);
	}

	private UUID attach(Ctx ctx, UUID courseId, UUID lessonId, UUID assetId, int sequence) {
		MvcResult attached = perform(authenticated(post("/api/v1/courses/{courseId}/lessons/{lessonId}/media", courseId,
				lessonId).contentType(MediaType.APPLICATION_JSON)
			.content("{\"mediaAssetId\":\"" + assetId + "\",\"sequence\":" + sequence + "}"), ctx.host(),
				ctx.teacherToken()));
		if (status(attached) != 201) {
			throw new IllegalStateException("attach failed: " + rawContent(attached));
		}
		return UUID.fromString(json(attached).path("data").path("id").asString());
	}

	/**
	 * A READY platform-storage ({@code provider = INTERNAL}) asset owned by the
	 * teacher. Seeded directly: this context has no legacy object storage.
	 */
	private UUID seedInternalReadyAsset(Ctx ctx) {
		UUID id = UUID.randomUUID();
		Timestamp now = Timestamp.from(Instant.now());
		jdbcTemplate.update("""
				INSERT INTO video_asset (id, tenant_id, provider, storage_object_key, original_filename, mime_type,
				    size_bytes, status, uploaded_by, created_at, updated_at)
				VALUES (?, ?, 'INTERNAL', ?, 'internal.mp4', 'video/mp4', 1024, 'READY', ?, ?, ?)
				""", id, ctx.tenant().getId(), "videos/" + ctx.tenant().getId() + "/" + id, ctx.teacherId(), now, now);
		return id;
	}

	private MvcResult play(String host, String token, UUID lessonMediaId) {
		return perform(authenticated(post(PLAYBACK).contentType(MediaType.APPLICATION_JSON)
			.content("{\"lessonMediaId\":\"" + lessonMediaId + "\"}"), host, token));
	}

	/** YouTube standard playback set as asked; the AOC Player on, since YouTube lessons need it. */
	private void withYouTubePlayback(boolean enabled, Runnable body) {
		boolean previous = featureFlags.isPlaybackModeEnabled(PlaybackMode.YOUTUBE_STANDARD);
		boolean previousNative = featureFlags.isNativePlayer();
		featureFlags.setYoutubeStandardPlayback(enabled);
		featureFlags.setNativePlayer(true);
		try {
			body.run();
		}
		finally {
			featureFlags.setYoutubeStandardPlayback(previous);
			featureFlags.setNativePlayer(previousNative);
		}
	}

}
