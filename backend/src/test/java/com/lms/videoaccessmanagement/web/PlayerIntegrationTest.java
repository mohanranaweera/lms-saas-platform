package com.lms.videoaccessmanagement.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.web.dto.CourseLessonResponse;
import com.lms.coursemanagement.course.web.dto.CourseModuleResponse;
import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.videoaccessmanagement.service.player.PlayerRequestVerifier;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * VIDEO Phase 8 (ADR-025 + Amendment 1): AOC Player pairing, launch codes,
 * exchange, signed heartbeat/end and revocation, end to end over HTTP against
 * Testcontainers Postgres + Redis. A {@link TestPlayer} holds a real ECDSA
 * P-256 key pair and signs requests exactly as the Windows player will.
 */
class PlayerIntegrationTest extends MediaUploadTestSupport {

	private static final String PLAYBACK = "/api/v1/playback/sessions";

	private static final String VERSION = "1.0.0";

	@Autowired
	private StringRedisTemplate redis;

	private record Lesson(Ctx ctx, UUID studentId, UUID studentProfileId, UUID courseId, UUID lessonId, UUID assetId,
			String videoId, UUID lessonMediaId) {
	}

	@BeforeEach
	void enableNativePlayback() {
		featureFlags.setNativePlayer(true);
		featureFlags.setYoutubeStandardPlayback(true);
		Set<String> keys = redis.keys("player:ratelimit:*");
		if (keys != null && !keys.isEmpty()) {
			redis.delete(keys);
		}
	}

	@AfterEach
	void restoreFlags() {
		featureFlags.setNativePlayer(false);
		featureFlags.setYoutubeStandardPlayback(false);
	}

	// ------------------------------------------------------------------
	// Happy path
	// ------------------------------------------------------------------

	@Test
	void aPairedPlayerClaimsALaunchHeartbeatsAndEnds() {
		Lesson lesson = nativeLesson("pl-happy");
		TestPlayer player = pair(lesson.ctx());

		JsonNode installations = json(perform(authenticated(get("/api/v1/player/installations"), lesson.ctx().host(),
				lesson.ctx().studentToken()))).path("data");
		assertThat(installations).hasSize(1);
		assertThat(installations.get(0).path("status").asString()).isEqualTo("ACTIVE");
		assertThat(installations.get(0).path("id").asString()).isEqualTo(player.installationId.toString());

		MvcResult started = play(lesson);
		assertThat(status(started)).isEqualTo(201);
		JsonNode start = json(started).path("data");
		assertThat(start.path("channel").asString()).isEqualTo("NATIVE");
		assertThat(start.path("descriptor").isNull()).isTrue();
		UUID sessionId = UUID.fromString(start.path("sessionId").asString());
		String launchUrl = start.path("launch").path("url").asString();
		assertThat(launchUrl).startsWith("aocplayer://play?code=").doesNotContain(lesson.videoId());
		assertThat(sessionStatus(lesson, sessionId)).isEqualTo("PENDING_LAUNCH");

		MvcResult exchanged = exchange(player, codeOf(launchUrl));
		assertThat(status(exchanged)).isEqualTo(200);
		JsonNode exchange = json(exchanged).path("data");
		assertThat(exchange.path("sessionId").asString()).isEqualTo(sessionId.toString());
		assertThat(exchange.path("descriptor").path("provider").asString()).isEqualTo("YOUTUBE");
		assertThat(exchange.path("descriptor").path("providerVideoId").asString()).isEqualTo(lesson.videoId());
		assertThat(exchange.path("descriptor").path("watermark").path("enabled").asBoolean()).isTrue();
		assertThat(exchange.path("descriptor").path("watermark").path("text").asString())
				.isEqualTo("Student ID: " + lesson.studentId().toString().substring(0, 8));
		assertThat(exchange.path("descriptor").path("watermark").path("placement").asString()).isEqualTo("OUTSIDE_PLAYER");
		assertThat(exchange.path("descriptor").path("captureProtection").path("enabled").asBoolean()).isTrue();
		assertThat(exchange.path("descriptor").path("captureProtection").path("required").asBoolean()).isFalse();
		String token = exchange.path("playbackToken").asString();
		assertThat(token).isNotBlank();
		assertThat(rawContent(exchanged)).doesNotContain(accessTokenOf(lesson.ctx().tenant().getId()));

		JsonNode status = json(perform(authenticated(get(PLAYBACK + "/{id}/status", sessionId), lesson.ctx().host(),
				lesson.ctx().studentToken()))).path("data");
		assertThat(status.path("status").asString()).isEqualTo("ACTIVE");
		assertThat(status.path("exchanged").asBoolean()).isTrue();

		JsonNode beat = json(heartbeat(player, sessionId, token)).path("data");
		assertThat(beat.path("action").asString()).isEqualTo("CONTINUE");
		String refreshed = beat.path("playbackToken").asString();
		assertThat(refreshed).isNotBlank();

		assertThat(status(end(player, sessionId, refreshed))).isEqualTo(200);
		JsonNode afterEnd = json(heartbeat(player, sessionId, refreshed)).path("data");
		assertThat(afterEnd.path("action").asString()).isEqualTo("STOP");

		// Codes are stored hashed only; the plaintext never reaches the database.
		String code = codeOf(launchUrl);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM playback_launch_code WHERE code_hash = ?",
				Integer.class, code)).isZero();
		assertThat(auditActions(lesson.ctx().tenant().getId())).contains("player.paired", "video.playback_authorized");
	}

	// ------------------------------------------------------------------
	// Launch codes
	// ------------------------------------------------------------------

	@Test
	void configuredFullNameWatermarkUsesTenantScopedStudentContact() {
		Lesson lesson = nativeLesson("pl-full-name-watermark");
		jdbcTemplate.update("UPDATE student_profile SET name = ?, updated_at = now() WHERE id = ?",
				"Demo Student", lesson.studentProfileId());
		jdbcTemplate.update("UPDATE tenant_video_configuration SET watermark_fields = ? WHERE tenant_id = ?",
				"STUDENT_FULL_NAME", lesson.ctx().tenant().getId());
		TestPlayer player = pair(lesson.ctx());
		String launchUrl = json(play(lesson)).path("data").path("launch").path("url").asString();

		JsonNode exchange = json(exchange(player, codeOf(launchUrl))).path("data");
		assertThat(exchange.path("descriptor").path("watermark").path("text").asString())
				.isEqualTo("Student name: Demo Student");
	}

	@Test
	void configuredMultipleWatermarkFieldsUseOneLabeledRowPerAttribute() {
		Lesson lesson = nativeLesson("pl-multi-watermark");
		jdbcTemplate.update("UPDATE student_profile SET name = ?, mobile = ?, updated_at = now() WHERE id = ?",
				"Demo Student", "+94771234567", lesson.studentProfileId());
		jdbcTemplate.update("UPDATE tenant_video_configuration SET watermark_fields = ? WHERE tenant_id = ?",
				"STUDENT_FULL_NAME,MASKED_PHONE,SESSION_ID", lesson.ctx().tenant().getId());
		TestPlayer player = pair(lesson.ctx());
		String launchUrl = json(play(lesson)).path("data").path("launch").path("url").asString();

		JsonNode exchange = json(exchange(player, codeOf(launchUrl))).path("data");
		String watermark = exchange.path("descriptor").path("watermark").path("text").asText();
		assertThat(watermark).startsWith("Student name: Demo Student\nPhone: +947•••567\nSession: ");
		assertThat(watermark.lines()).hasSize(3);
	}

	@Test
	void configuredMaskedPhoneWatermarkUsesTenantScopedStudentContact() {
		Lesson lesson = nativeLesson("pl-phone-watermark");
		jdbcTemplate.update("UPDATE student_profile SET mobile = ?, updated_at = now() WHERE id = ?",
				"+94771234567", lesson.studentProfileId());
		jdbcTemplate.update("UPDATE tenant_video_configuration SET watermark_fields = ? WHERE tenant_id = ?",
				"MASKED_PHONE", lesson.ctx().tenant().getId());
		TestPlayer player = pair(lesson.ctx());
		String launchUrl = json(play(lesson)).path("data").path("launch").path("url").asString();

		JsonNode exchange = json(exchange(player, codeOf(launchUrl))).path("data");
		assertThat(exchange.path("descriptor").path("watermark").path("text").asString())
				.isEqualTo("Phone: +947•••567");
	}

	@Test
	void aLaunchCodeWorksOnceAndNotAfterItExpires() {
		Lesson lesson = nativeLesson("pl-once");
		TestPlayer player = pair(lesson.ctx());
		String code = codeOf(json(play(lesson)).path("data").path("launch").path("url").asString());

		assertThat(status(exchange(player, code))).isEqualTo(200);
		MvcResult replay = exchange(player, code);
		assertThat(status(replay)).isEqualTo(400);
		assertThat(errorCode(replay)).isEqualTo("PLAYBACK_LAUNCH_CODE_INVALID");

		String expiring = codeOf(json(play(lesson)).path("data").path("launch").path("url").asString());
		jdbcTemplate.update("UPDATE playback_launch_code SET created_at = now() - interval '2 minutes', "
				+ "expires_at = now() - interval '90 seconds' WHERE tenant_id = ? AND status = 'ISSUED'",
				lesson.ctx().tenant().getId());
		MvcResult expired = exchange(player, expiring);
		assertThat(status(expired)).isEqualTo(400);
		assertThat(errorCode(expired)).isEqualTo("PLAYBACK_LAUNCH_CODE_INVALID");
	}

	@Test
	void tenConcurrentExchangesOfOneCodeLetExactlyOneThrough() throws Exception {
		Lesson lesson = nativeLesson("pl-race");
		TestPlayer player = pair(lesson.ctx());
		String code = codeOf(json(play(lesson)).path("data").path("launch").path("url").asString());

		ExecutorService pool = Executors.newFixedThreadPool(10);
		CountDownLatch go = new CountDownLatch(1);
		List<Future<Integer>> results = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			Callable<Integer> attempt = () -> {
				go.await();
				return status(exchange(player, code));
			};
			results.add(pool.submit(attempt));
		}
		go.countDown();
		List<Integer> statuses = new ArrayList<>();
		for (Future<Integer> result : results) {
			statuses.add(result.get());
		}
		pool.shutdown();

		assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(1);
		assertThat(statuses.stream().filter(s -> s == 400).count()).isEqualTo(9);
	}

	@Test
	void aWrongKeyOrAnotherStudentsPlayerCannotUseTheCodeAndDoesNotBurnIt() {
		Lesson lesson = nativeLesson("pl-wrongkey");
		TestPlayer player = pair(lesson.ctx());
		seedActiveStudent(lesson.ctx().tenant().getId(), "student2@example.test");
		String otherToken = loginAndGetToken(lesson.ctx().host(), "student2@example.test");
		TestPlayer otherStudentsPlayer = pair(lesson.ctx().host(), otherToken);
		String code = codeOf(json(play(lesson)).path("data").path("launch").path("url").asString());

		TestPlayer impostor = new TestPlayer();
		impostor.installationId = player.installationId;
		assertThat(errorCode(exchange(impostor, code))).isEqualTo("PLAYBACK_LAUNCH_CODE_INVALID");
		assertThat(errorCode(exchange(otherStudentsPlayer, code))).isEqualTo("PLAYBACK_LAUNCH_CODE_INVALID");

		assertThat(status(exchange(player, code))).isEqualTo(200);
	}

	// ------------------------------------------------------------------
	// Signed requests and tokens
	// ------------------------------------------------------------------

	@Test
	void aReplayedNonceAStaleTimestampOrABadTokenIsRefused() {
		Lesson lesson = nativeLesson("pl-replay");
		TestPlayer player = pair(lesson.ctx());
		ActiveSession session = start(lesson, player);

		String nonce = TestPlayer.nonce();
		byte[] body = heartbeatBody();
		String path = "/api/v1/player/sessions/" + session.id() + "/heartbeat";
		long now = Instant.now().getEpochSecond();
		assertThat(status(perform(player.sign(post(path).header("X-Player-Token", session.token()), path, body, now,
				nonce)))).isEqualTo(200);
		MvcResult replayed = perform(player.sign(post(path).header("X-Player-Token", session.token()), path, body, now,
				nonce));
		assertThat(status(replayed)).isEqualTo(401);
		assertThat(errorCode(replayed)).isEqualTo("PLAYBACK_SESSION_EXPIRED");

		MvcResult stale = perform(player.sign(post(path).header("X-Player-Token", session.token()), path, body,
				now - 600, TestPlayer.nonce()));
		assertThat(status(stale)).isEqualTo(401);

		assertThat(status(heartbeat(player, session.id(), "not-a-token"))).isEqualTo(401);
		assertThat(status(heartbeat(player, session.id(), null))).isEqualTo(401);
	}

	@Test
	void aPlayerTokenIsNeverAcceptedByTheLegacyBrowserEndpoint() {
		Lesson lesson = nativeLesson("pl-audience");
		TestPlayer player = pair(lesson.ctx());
		ActiveSession session = start(lesson, player);

		MvcResult legacy = perform(authenticated(post("/api/v1/videos/playback-sessions/{id}/progress", session.id())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"playbackToken\":\"" + session.token() + "\",\"positionSeconds\":1,\"watchedDeltaSeconds\":1}"),
				lesson.ctx().host(), lesson.ctx().studentToken()));
		assertThat(status(legacy)).isEqualTo(401);
	}

	// ------------------------------------------------------------------
	// Revocation
	// ------------------------------------------------------------------

	@Test
	void resettingTheDeviceRevokesItsPlayerAndStopsPlayback() {
		Lesson lesson = nativeLesson("pl-device");
		TestPlayer player = pair(lesson.ctx());
		ActiveSession session = start(lesson, player);
		UUID deviceId = jdbcTemplate.queryForObject(
				"SELECT device_id FROM player_installation WHERE id = ?", UUID.class, player.installationId);

		MvcResult reset = perform(authenticated(post("/api/v1/students/{studentId}/devices/{deviceId}/reset",
				lesson.studentProfileId(), deviceId).contentType(MediaType.APPLICATION_JSON)
			.content("{\"reason\":\"Lost laptop\"}"), lesson.ctx().host(), lesson.ctx().adminToken()));
		assertThat(status(reset)).isEqualTo(200);

		Map<String, Object> installation = jdbcTemplate.queryForMap(
				"SELECT status, revocation_source FROM player_installation WHERE id = ?", player.installationId);
		assertThat(installation.get("status")).isEqualTo("REVOKED");
		assertThat(installation.get("revocation_source")).isEqualTo("DEVICE_REVOKED");
		assertThat(jdbcTemplate.queryForObject("SELECT revoked_reason FROM video_watch_session WHERE id = ?",
				String.class, session.id())).isEqualTo("DEVICE_REVOKED");
		JsonNode beat = json(heartbeat(player, session.id(), session.token())).path("data");
		assertThat(beat.path("action").asString()).isEqualTo("STOP");
	}

	@Test
	void theStudentCanRemoveTheirPlayerAndRePairingReplacesIt() {
		Lesson lesson = nativeLesson("pl-own");
		TestPlayer first = pair(lesson.ctx());
		ActiveSession session = start(lesson, first);

		TestPlayer second = pair(lesson.ctx());
		assertThat(jdbcTemplate.queryForObject("SELECT revocation_source FROM player_installation WHERE id = ?",
				String.class, first.installationId)).isEqualTo("REPAIRED");
		assertThat(json(heartbeat(first, session.id(), session.token())).path("data").path("action").asString())
			.isEqualTo("STOP");

		MvcResult removed = perform(authenticated(post("/api/v1/player/installations/{id}/revoke",
				second.installationId), lesson.ctx().host(), lesson.ctx().studentToken()));
		assertThat(status(removed)).isEqualTo(200);
		assertThat(json(removed).path("data").path("status").asString()).isEqualTo("REVOKED");
		// Removed: the next launch carries a pairing code; the removed player still can't claim it.
		String launchUrl = json(play(lesson)).path("data").path("launch").path("url").asString();
		assertThat(param(launchUrl, "pair")).isNotNull();
		assertThat(status(exchange(second, codeOf(launchUrl)))).isEqualTo(400);
	}

	@Test
	void usedUpWatchTimeStopsThePlayerAndBlocksTheNextLaunch() {
		Lesson lesson = nativeLesson("pl-budget");
		UUID tenantId = lesson.ctx().tenant().getId();
		jdbcTemplate.update("UPDATE tenant_video_configuration SET watch_time_policy = 'FIXED_MINUTES', "
				+ "watch_time_fixed_minutes = 1, watch_time_multiplier = NULL WHERE tenant_id = ?", tenantId);
		TestPlayer player = pair(lesson.ctx());
		ActiveSession session = start(lesson, player);
		// The claim anchors accounting, so the first interval counts.
		assertThat(jdbcTemplate.queryForObject("SELECT last_heartbeat_at IS NOT NULL FROM video_watch_session "
				+ "WHERE id = ?", Boolean.class, session.id())).isTrue();

		// Paused time never counts.
		backdateHeartbeat(session.id(), 30);
		JsonNode paused = json(heartbeat(player, session.id(), session.token(), 0, "PAUSED")).path("data");
		assertThat(paused.path("action").asString()).isEqualTo("CONTINUE");
		assertThat(paused.path("remainingWatchSeconds").asInt()).isEqualTo(60);

		backdateHeartbeat(session.id(), 40);
		JsonNode playing = json(heartbeat(player, session.id(), paused.path("playbackToken").asString(), 40,
				"PLAYING")).path("data");
		assertThat(playing.path("action").asString()).isEqualTo("CONTINUE");
		assertThat(playing.path("remainingWatchSeconds").asInt()).isEqualTo(20);

		backdateHeartbeat(session.id(), 30);
		JsonNode exhausted = json(heartbeat(player, session.id(), playing.path("playbackToken").asString(), 70,
				"PLAYING")).path("data");
		assertThat(exhausted.path("action").asString()).isEqualTo("STOP");
		assertThat(exhausted.path("reason").asString()).isEqualTo("WATCH_TIME_EXHAUSTED");
		assertThat(exhausted.path("playbackToken").isNull()).isTrue();
		assertThat(jdbcTemplate.queryForObject("SELECT authorized_watch_seconds FROM video_watch_progress "
				+ "WHERE tenant_id = ? AND video_asset_id = ?", Integer.class, tenantId, lesson.assetId()))
			.isEqualTo(60);

		MvcResult again = play(lesson);
		assertThat(status(again)).isEqualTo(409);
		assertThat(errorCode(again)).isEqualTo("PLAYBACK_WATCH_LIMIT");
	}

	@Test
	void aLaunchWithoutAPlayerOnThisDevicePairsAndPlaysInOneStep() {
		Lesson lesson = nativeLesson("pl-autopair");
		UUID tenantId = lesson.ctx().tenant().getId();

		// Never paired: the launch link also carries a one-time pairing code.
		String first = json(play(lesson)).path("data").path("launch").path("url").asString();
		assertThat(first).matches("aocplayer://play\\?code=[A-Za-z0-9_-]{43}&pair=[A-Za-z0-9_-]{43}");
		// Stored hashed only.
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM player_pairing_code WHERE tenant_id = ? "
				+ "AND code_hash = ?", Integer.class, tenantId, param(first, "pair"))).isZero();

		// The player pairs with it, then claims the launch: one click for the student.
		TestPlayer player = new TestPlayer();
		MvcResult paired = completePairing(param(first, "pair"), player.publicKey(), VERSION);
		assertThat(status(paired)).isEqualTo(201);
		player.installationId = UUID.fromString(json(paired).path("data").path("installationId").asString());
		assertThat(status(exchange(player, codeOf(first)))).isEqualTo(200);
		// The pairing code is one-time.
		assertThat(status(completePairing(param(first, "pair"), new TestPlayer().publicKey(), VERSION)))
			.isEqualTo(400);

		// Paired now: the next launch carries no pairing code.
		String second = json(play(lesson)).path("data").path("launch").path("url").asString();
		assertThat(param(second, "pair")).isNull();

		// Revoked while the player was closed: the next launch pairs again and plays.
		jdbcTemplate.update("UPDATE player_installation SET status = 'REVOKED', revoked_at = now(), "
				+ "revocation_source = 'STUDENT', revoke_reason = 'test' WHERE id = ?", player.installationId);
		String afterRevoke = json(play(lesson)).path("data").path("launch").path("url").asString();
		assertThat(param(afterRevoke, "pair")).isNotNull();
		TestPlayer repaired = new TestPlayer();
		MvcResult rePaired = completePairing(param(afterRevoke, "pair"), repaired.publicKey(), VERSION);
		assertThat(status(rePaired)).isEqualTo(201);
		repaired.installationId = UUID.fromString(json(rePaired).path("data").path("installationId").asString());
		assertThat(status(exchange(repaired, codeOf(afterRevoke)))).isEqualTo(200);
	}

	@Test
	void losingAccessStopsTheNextHeartbeat() {
		Lesson lesson = nativeLesson("pl-access");
		TestPlayer player = pair(lesson.ctx());
		ActiveSession session = start(lesson, player);

		MvcResult hidden = perform(authenticated(patch("/api/v1/courses/{courseId}/lessons/{lessonId}/media/{id}",
				lesson.courseId(), lesson.lessonId(), lesson.lessonMediaId()).contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":\"Hidden\",\"sequence\":1,\"visibility\":\"HIDDEN\"}"), lesson.ctx().host(),
				lesson.ctx().teacherToken()));
		assertThat(status(hidden)).isEqualTo(200);

		JsonNode beat = json(heartbeat(player, session.id(), session.token())).path("data");
		assertThat(beat.path("action").asString()).isEqualTo("STOP");
		assertThat(beat.path("reason").asString()).isEqualTo("ACCESS_REVOKED");
	}

	@Test
	void anAdminCanStopALiveSessionWithAnAuditedReason() {
		Lesson lesson = nativeLesson("pl-admin");
		TestPlayer player = pair(lesson.ctx());
		ActiveSession session = start(lesson, player);

		MvcResult missingReason = perform(authenticated(post(PLAYBACK + "/{id}/revoke", session.id())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{}"), lesson.ctx().host(), lesson.ctx().adminToken()));
		assertThat(status(missingReason)).isEqualTo(400);

		MvcResult revoked = perform(authenticated(post(PLAYBACK + "/{id}/revoke", session.id())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"reason\":\"Shared account\"}"), lesson.ctx().host(), lesson.ctx().adminToken()));
		assertThat(status(revoked)).isEqualTo(200);
		JsonNode beat = json(heartbeat(player, session.id(), session.token())).path("data");
		assertThat(beat.path("reason").asString()).isEqualTo("ADMIN");
		assertThat(auditActions(lesson.ctx().tenant().getId())).contains("video.playback_session_revoked");

		MvcResult adminList = perform(authenticated(get("/api/v1/students/{id}/player-installations",
				lesson.studentProfileId()), lesson.ctx().host(), lesson.ctx().adminToken()));
		assertThat(json(adminList).path("data")).hasSize(1);
		MvcResult studentCannot = perform(authenticated(post(PLAYBACK + "/{id}/revoke", session.id())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"reason\":\"x\"}"), lesson.ctx().host(), lesson.ctx().studentToken()));
		assertThat(status(studentCannot)).isEqualTo(403);
	}

	// ------------------------------------------------------------------
	// Settings, kill switch, versions
	// ------------------------------------------------------------------

	@Test
	void aRequiredNativePlayerCannotBeBypassedOrSilentlyDowngraded() {
		Lesson lesson = nativeLesson("pl-required");
		UUID internalAsset = seedInternalReadyAsset(lesson.ctx());
		attach(lesson.ctx(), lesson.courseId(), lesson.lessonId(), internalAsset, 2);
		MvcResult legacy = perform(authenticated(post("/api/v1/videos/{id}/playback-sessions", internalAsset),
				lesson.ctx().host(), lesson.ctx().studentToken()));
		assertThat(status(legacy)).isEqualTo(409);
		assertThat(errorCode(legacy)).isEqualTo("NATIVE_PLAYER_REQUIRED");

		featureFlags.setNativePlayer(false);
		MvcResult refused = play(lesson);
		assertThat(status(refused)).isEqualTo(409);
		assertThat(errorCode(refused)).isEqualTo("NATIVE_PLAYER_UNAVAILABLE");
	}

	@Test
	void anOldPlayerCannotPair() {
		Lesson lesson = nativeLesson("pl-version");
		String code = startPairing(lesson.ctx().host(), lesson.ctx().studentToken());
		MvcResult old = completePairing(code, new TestPlayer().publicKey(), "0.0.1");
		assertThat(status(old)).isEqualTo(426);
		assertThat(errorCode(old)).isEqualTo("PLAYER_VERSION_UNSUPPORTED");
		MvcResult bogusKey = completePairing(code, "bm90LWEta2V5", VERSION);
		assertThat(errorCode(bogusKey)).isEqualTo("PLAYER_PAIRING_CODE_INVALID");
		// Neither attempt consumed the code.
		assertThat(status(completePairing(code, new TestPlayer().publicKey(), VERSION))).isEqualTo(201);
		assertThat(status(completePairing(code, new TestPlayer().publicKey(), VERSION))).isEqualTo(400);
	}

	// ------------------------------------------------------------------
	// Cross-tenant
	// ------------------------------------------------------------------

	@Tag("cross-tenant")
	@Test
	void nothingCrossesTenants() {
		Lesson a = nativeLesson("pl-iso-a");
		Lesson b = nativeLesson("pl-iso-b");
		TestPlayer playerA = pair(a.ctx());
		TestPlayer playerB = pair(b.ctx());
		ActiveSession sessionA = start(a, playerA);

		// B's player cannot claim A's launch code.
		String codeA = codeOf(json(play(a)).path("data").path("launch").path("url").asString());
		assertThat(errorCode(exchange(playerB, codeA))).isEqualTo("PLAYBACK_LAUNCH_CODE_INVALID");
		// B's student cannot read A's session status.
		assertThat(status(perform(authenticated(get(PLAYBACK + "/{id}/status", sessionA.id()), b.ctx().host(),
				b.ctx().studentToken())))).isEqualTo(404);
		// B's admin cannot list or revoke A's player, nor stop A's session.
		assertThat(status(perform(authenticated(get("/api/v1/students/{id}/player-installations", a.studentProfileId()),
				b.ctx().host(), b.ctx().adminToken())))).isEqualTo(404);
		assertThat(status(perform(authenticated(post("/api/v1/students/{id}/player-installations/{inst}/revoke",
				a.studentProfileId(), playerA.installationId).contentType(MediaType.APPLICATION_JSON)
			.content("{\"reason\":\"x\"}"), b.ctx().host(), b.ctx().adminToken())))).isEqualTo(404);
		assertThat(status(perform(authenticated(post(PLAYBACK + "/{id}/revoke", sessionA.id())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"reason\":\"x\"}"), b.ctx().host(), b.ctx().adminToken())))).isEqualTo(404);
		// B's student cannot remove A's player.
		assertThat(status(perform(authenticated(post("/api/v1/player/installations/{id}/revoke",
				playerA.installationId), b.ctx().host(), b.ctx().studentToken())))).isEqualTo(404);
		// B's player cannot heartbeat A's session, even holding A's token.
		assertThat(status(heartbeat(playerB, sessionA.id(), sessionA.token()))).isEqualTo(401);
		// A is untouched by all of the above.
		assertThat(json(heartbeat(playerA, sessionA.id(), sessionA.token())).path("data").path("action").asString())
			.isEqualTo("CONTINUE");
	}

	// ------------------------------------------------------------------
	// Schema
	// ------------------------------------------------------------------

	@Test
	void theSchemaEnforcesCodeLifetimesAndOneActivePlayerPerDevice() {
		Lesson lesson = nativeLesson("pl-schema");
		TestPlayer player = pair(lesson.ctx());
		Map<String, Object> row = jdbcTemplate.queryForMap(
				"SELECT tenant_id, user_id, device_id FROM player_installation WHERE id = ?", player.installationId);
		Timestamp now = Timestamp.from(Instant.now());

		assertThatThrownBy(() -> jdbcTemplate.update("""
				INSERT INTO player_installation (id, tenant_id, user_id, device_id, public_key, key_algorithm, platform,
				    player_version, status, paired_at, last_seen_at)
				VALUES (?, ?, ?, ?, 'k', 'ES256', 'WINDOWS', '1.0.0', 'ACTIVE', ?, ?)
				""", UUID.randomUUID(), row.get("tenant_id"), row.get("user_id"), row.get("device_id"), now, now))
			.isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> jdbcTemplate.update("""
				INSERT INTO player_pairing_code (id, tenant_id, user_id, device_id, code_hash, status, created_at,
				    expires_at)
				VALUES (?, ?, ?, ?, ?, 'ISSUED', now(), now() + interval '10 minutes')
				""", UUID.randomUUID(), row.get("tenant_id"), row.get("user_id"), row.get("device_id"),
				UUID.randomUUID().toString()))
			.isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> jdbcTemplate.update("UPDATE player_installation SET status = 'REVOKED' WHERE id = ?",
				player.installationId))
			.isInstanceOf(DataAccessException.class);
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	private record ActiveSession(UUID id, String token) {
	}

	/** A tenant requiring the native player, with an enrolled student and a READY YouTube lesson video. */
	private Lesson nativeLesson(String prefix) {
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
		enrollStudentOrFail(ctx.host(), ctx.studentToken(), course.id());
		requireNativePlayer(ctx.tenant().getId());
		UUID studentId = jdbcTemplate.queryForObject(
				"SELECT id FROM tenant_user WHERE tenant_id = ? AND email = 'student@example.test'", UUID.class,
				ctx.tenant().getId());
		UUID studentProfileId = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO student_profile (id, tenant_id, user_id, name, created_at, updated_at) "
				+ "VALUES (?, ?, ?, 'Student', now(), now())", studentProfileId, ctx.tenant().getId(), studentId);
		return new Lesson(ctx, studentId, studentProfileId, course.id(), lesson.id(), assetId, videoId, lessonMediaId);
	}

	private void requireNativePlayer(UUID tenantId) {
		Integer rows = jdbcTemplate.queryForObject("SELECT count(*) FROM tenant_video_configuration WHERE tenant_id = ?",
				Integer.class, tenantId);
		if (rows == null || rows == 0) {
			seedVideoConfiguration(tenantId, true, 20);
		}
		jdbcTemplate.update("UPDATE tenant_video_configuration SET native_player_required = true WHERE tenant_id = ?",
				tenantId);
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

	private MvcResult play(Lesson lesson) {
		return perform(authenticated(post(PLAYBACK).contentType(MediaType.APPLICATION_JSON)
			.content("{\"lessonMediaId\":\"" + lesson.lessonMediaId() + "\"}"), lesson.ctx().host(),
				lesson.ctx().studentToken()));
	}

	private String sessionStatus(Lesson lesson, UUID sessionId) {
		return json(perform(authenticated(get(PLAYBACK + "/{id}/status", sessionId), lesson.ctx().host(),
				lesson.ctx().studentToken()))).path("data").path("status").asString();
	}

	private ActiveSession start(Lesson lesson, TestPlayer player) {
		String code = codeOf(json(play(lesson)).path("data").path("launch").path("url").asString());
		MvcResult exchanged = exchange(player, code);
		if (status(exchanged) != 200) {
			throw new IllegalStateException("exchange failed: " + rawContent(exchanged));
		}
		JsonNode data = json(exchanged).path("data");
		return new ActiveSession(UUID.fromString(data.path("sessionId").asString()),
				data.path("playbackToken").asString());
	}

	private TestPlayer pair(Ctx ctx) {
		return pair(ctx.host(), ctx.studentToken());
	}

	private TestPlayer pair(String host, String studentToken) {
		TestPlayer player = new TestPlayer();
		MvcResult completed = completePairing(startPairing(host, studentToken), player.publicKey(), VERSION);
		if (status(completed) != 201) {
			throw new IllegalStateException("pairing failed: " + rawContent(completed));
		}
		player.installationId = UUID.fromString(json(completed).path("data").path("installationId").asString());
		return player;
	}

	private String startPairing(String host, String studentToken) {
		MvcResult started = perform(authenticated(post("/api/v1/player/pairings"), host, studentToken));
		if (status(started) != 201) {
			throw new IllegalStateException("pairing start failed: " + rawContent(started));
		}
		return codeOf(json(started).path("data").path("pairingUrl").asString());
	}

	private MvcResult completePairing(String code, String publicKey, String version) {
		String body = "{\"pairingCode\":\"" + code + "\",\"publicKey\":\"" + publicKey
				+ "\",\"platform\":\"WINDOWS\",\"playerVersion\":\"" + version + "\"}";
		return perform(post("/api/v1/player/pairings/complete").contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private MvcResult exchange(TestPlayer player, String code) {
		String path = "/api/v1/player/exchange";
		byte[] body = ("{\"launchCode\":\"" + code + "\",\"playerVersion\":\"" + VERSION
				+ "\",\"platform\":\"WINDOWS\"}").getBytes(StandardCharsets.UTF_8);
		return perform(player.sign(post(path).header("X-Player-Installation", player.installationId.toString()), path,
				body, Instant.now().getEpochSecond(), TestPlayer.nonce()));
	}

	private MvcResult heartbeat(TestPlayer player, UUID sessionId, String token) {
		String path = "/api/v1/player/sessions/" + sessionId + "/heartbeat";
		MockHttpServletRequestBuilder request = post(path);
		if (token != null) {
			request.header("X-Player-Token", token);
		}
		return perform(player.sign(request, path, heartbeatBody(), Instant.now().getEpochSecond(), TestPlayer.nonce()));
	}

	private MvcResult end(TestPlayer player, UUID sessionId, String token) {
		String path = "/api/v1/player/sessions/" + sessionId + "/end";
		return perform(player.sign(post(path).header("X-Player-Token", token), path, new byte[0],
				Instant.now().getEpochSecond(), TestPlayer.nonce()));
	}

	private static byte[] heartbeatBody() {
		return heartbeatBody(12, "PLAYING");
	}

	private static byte[] heartbeatBody(int positionSeconds, String state) {
		return ("{\"positionSeconds\":" + positionSeconds + ",\"state\":\"" + state + "\",\"playerVersion\":\""
				+ VERSION + "\"}").getBytes(StandardCharsets.UTF_8);
	}

	private MvcResult heartbeat(TestPlayer player, UUID sessionId, String token, int positionSeconds, String state) {
		String path = "/api/v1/player/sessions/" + sessionId + "/heartbeat";
		MockHttpServletRequestBuilder request = post(path).header("X-Player-Token", token);
		return perform(player.sign(request, path, heartbeatBody(positionSeconds, state), Instant.now().getEpochSecond(),
				TestPlayer.nonce()));
	}

	/** Moves the session's last heartbeat into the past, as if that much time had passed. */
	private void backdateHeartbeat(UUID sessionId, int seconds) {
		jdbcTemplate.update("UPDATE video_watch_session SET last_heartbeat_at = now() - (? * interval '1 second') "
				+ "WHERE id = ?", seconds, sessionId);
	}

	private static String codeOf(String url) {
		return param(url, "code");
	}

	/** A query parameter of an {@code aocplayer://} link, or null. */
	private static String param(String url, String name) {
		for (String pair : url.substring(url.indexOf('?') + 1).split("&")) {
			if (pair.startsWith(name + "=")) {
				return pair.substring(name.length() + 1);
			}
		}
		return null;
	}

	private List<String> auditActions(UUID tenantId) {
		return jdbcTemplate.queryForList("SELECT action FROM audit_log WHERE tenant_id = ?", String.class, tenantId);
	}

	/** Stands in for the Windows AOC Player: an ECDSA P-256 key pair that signs requests (P1363, base64url). */
	private static final class TestPlayer {

		private final KeyPair keys;

		private UUID installationId;

		TestPlayer() {
			try {
				KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
				generator.initialize(new ECGenParameterSpec("secp256r1"));
				this.keys = generator.generateKeyPair();
			}
			catch (Exception impossible) {
				throw new IllegalStateException(impossible);
			}
		}

		String publicKey() {
			return Base64.getEncoder().encodeToString(keys.getPublic().getEncoded());
		}

		static String nonce() {
			return UUID.randomUUID().toString().replace("-", "");
		}

		MockHttpServletRequestBuilder sign(MockHttpServletRequestBuilder request, String path, byte[] body,
				long epochSeconds, String nonce) {
			try {
				Signature signer = Signature.getInstance("SHA256withECDSAinP1363Format");
				signer.initSign(keys.getPrivate());
				signer.update(PlayerRequestVerifier.canonical("POST", path, String.valueOf(epochSeconds), nonce, body));
				return request.contentType(MediaType.APPLICATION_JSON)
					.content(body)
					.header("X-Player-Timestamp", String.valueOf(epochSeconds))
					.header("X-Player-Nonce", nonce)
					.header("X-Player-Signature", Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign()));
			}
			catch (Exception impossible) {
				throw new IllegalStateException(impossible);
			}
		}

	}

}
