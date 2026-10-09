package com.lms.videoaccessmanagement.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.lms.common.api.ApiErrorCodes;
import com.lms.identityaccessservice.domain.Role;
import com.lms.videoaccessmanagement.VideoAccessManagementTestSupport;
import com.lms.videoaccessmanagement.web.dto.PlaybackSessionResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * A video's own watch-time limit (VIDEO Phase 12, owner decision 2026-10-09):
 * {@code GET/PUT /api/v1/videos/{id}/watch-time-limit}. Set when the video is
 * added to a lesson; when set it wins over the institute rule (higher or
 * lower - see {@code WatchTimeServiceTest}); a change applies to every Student
 * from their next heartbeat.
 */
class VideoWatchTimeLimitIntegrationTest extends VideoAccessManagementTestSupport {

	@Test
	void aChangedLimitAppliesToEveryStudentAndIsAudited() {
		VideoFixture f = seedVideoFixture("wtl-own");

		// No institute rule in this tenant: no limit yet.
		JsonNode initial = data(limit(f, f.teacherToken()));
		assertThat(initial.path("videoLimitSeconds").isNull()).isTrue();
		assertThat(initial.path("instituteDefaultSeconds").isNull()).isTrue();
		assertThat(initial.path("effectiveSeconds").isNull()).isTrue();

		// The course's teacher gives this video 1 minute; the student uses it up.
		JsonNode strict = data(setLimit(f, f.teacherToken(), 60));
		assertThat(strict.path("effectiveSeconds").asInt()).isEqualTo(60);
		watch(f, 70);
		assertThat(issuePlaybackSession(f.host(), f.studentToken(), f.videoAssetId()).getStatusCode())
			.isEqualTo(HttpStatus.CONFLICT);

		// Raised to 5 minutes: the blocked student can watch again; time watched is kept.
		JsonNode generous = data(setLimit(f, f.teacherToken(), 300));
		assertThat(generous.path("videoLimitSeconds").asInt()).isEqualTo(300);
		assertThat(issuePlaybackSession(f.host(), f.studentToken(), f.videoAssetId()).getStatusCode())
			.isEqualTo(HttpStatus.CREATED);
		assertThat(jdbcTemplate.queryForObject("SELECT authorized_watch_seconds FROM video_watch_progress "
				+ "WHERE tenant_id = ? AND video_asset_id = ?", Integer.class, f.tenant().getId(), f.videoAssetId()))
			.isEqualTo(60);

		// Removed: back to the institute rule (none here).
		JsonNode cleared = data(setLimit(f, f.teacherToken(), null));
		assertThat(cleared.path("videoLimitSeconds").isNull()).isTrue();
		assertThat(cleared.path("effectiveSeconds").isNull()).isTrue();

		// Every change is audited with before/after; the other playback rules keep playback's defaults.
		List<Map<String, Object>> audit = jdbcTemplate.queryForList("SELECT actor_id, metadata::text AS metadata "
				+ "FROM audit_log WHERE tenant_id = ? AND action = 'video.watch_time_limit_changed' ORDER BY occurred_at",
				f.tenant().getId());
		assertThat(audit).hasSize(3);
		assertThat(audit.get(0).get("actor_id")).isEqualTo(f.teacher().getId());
		assertThat((String) audit.get(1).get("metadata")).contains("\"before\": 60").contains("\"after\": 300");
		Map<String, Object> policy = jdbcTemplate.queryForMap("SELECT allow_seeking, allow_download, "
				+ "watermark_enabled FROM video_playback_policy WHERE video_asset_id = ?", f.videoAssetId());
		assertThat(policy.get("allow_seeking")).isEqualTo(true);
		assertThat(policy.get("allow_download")).isEqualTo(false);
		assertThat(policy.get("watermark_enabled")).isEqualTo(true);
	}

	@Test
	void onlyTheCoursesTeacherOrMaterialsStaffMayChangeIt() {
		VideoFixture f = seedVideoFixture("wtl-perm");
		seedTenantUser(f.tenant().getId(), "teacher2@example.test", RAW_PASSWORD, Role.TEACHER);
		String otherTeacher = loginAndGetToken(f.host(), "teacher2@example.test");

		assertThat(status(setLimit(f, otherTeacher, 600))).isEqualTo(403);
		assertThat(status(limit(f, otherTeacher))).isEqualTo(403);
		assertThat(status(setLimit(f, f.studentToken(), 600))).isEqualTo(403);
		assertThat(status(setLimit(f, f.adminToken(), 600))).isEqualTo(200);
		assertThat(status(setLimit(f, f.teacherToken(), 30))).isEqualTo(400);
		assertThat(jdbcTemplate.queryForObject("SELECT max_watch_duration_seconds FROM video_playback_policy "
				+ "WHERE video_asset_id = ?", Integer.class, f.videoAssetId())).isEqualTo(600);
	}

	@Tag("cross-tenant")
	@Test
	void anotherTenantsVideoIsNotFound() {
		VideoFixture a = seedVideoFixture("wtl-iso-a");
		VideoFixture b = seedVideoFixture("wtl-iso-b");
		MvcResult read = perform(authenticated(get("/api/v1/videos/{id}/watch-time-limit", a.videoAssetId()),
				b.host(), b.adminToken()));
		MvcResult write = perform(authenticated(put("/api/v1/videos/{id}/watch-time-limit", a.videoAssetId())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"limitSeconds\":600}"), b.host(), b.adminToken()));
		assertThat(status(read)).isEqualTo(404);
		assertThat(status(write)).isEqualTo(404);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM video_playback_policy WHERE video_asset_id = ?",
				Integer.class, a.videoAssetId())).isZero();
	}

	// ------------------------------------------------------------------

	/** Plays {@code seconds} through one WEB heartbeat. */
	private void watch(VideoFixture f, int seconds) {
		PlaybackSessionResponse session = issuePlaybackSessionOrFail(f.host(), f.studentToken(), f.videoAssetId());
		jdbcTemplate.update("UPDATE video_watch_session SET last_heartbeat_at = now() - (? * interval '1 second') "
				+ "WHERE tenant_id = ? AND id = ?", seconds, f.tenant().getId(), session.watchSessionId());
		var result = recordProgress(f.host(), f.studentToken(), session.watchSessionId(), session.playbackToken(),
				seconds, seconds);
		assertThat(result.getBody().error().code()).isEqualTo(ApiErrorCodes.PLAYBACK_WATCH_LIMIT);
	}

	private MvcResult limit(VideoFixture f, String token) {
		return perform(authenticated(get("/api/v1/videos/{id}/watch-time-limit", f.videoAssetId()), f.host(), token));
	}

	private MvcResult setLimit(VideoFixture f, String token, Integer seconds) {
		return perform(authenticated(put("/api/v1/videos/{id}/watch-time-limit", f.videoAssetId())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"limitSeconds\":" + seconds + "}"), f.host(), token));
	}

	private JsonNode data(MvcResult result) {
		assertThat(status(result)).isEqualTo(200);
		return objectMapper.readTree(rawContent(result)).path("data");
	}

	private static int status(MvcResult result) {
		return result.getResponse().getStatus();
	}

}
