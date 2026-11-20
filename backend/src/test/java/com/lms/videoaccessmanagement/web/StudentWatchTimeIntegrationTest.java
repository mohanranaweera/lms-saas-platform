package com.lms.videoaccessmanagement.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.lms.common.api.ApiErrorCodes;
import com.lms.videoaccessmanagement.VideoAccessManagementTestSupport;
import com.lms.videoaccessmanagement.web.dto.PlaybackSessionResponse;
import com.lms.videoaccessmanagement.web.dto.VideoPlaybackPolicyRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * Staff watch-time screen and extra-time grants (VIDEO Phase 12, V80):
 * {@code GET /api/v1/students/{studentId}/watch-time} and
 * {@code POST .../watch-time/{videoAssetId}/grants}. A grant lifts a used-up
 * budget without touching time watched, is audited, can't be edited or
 * removed, and never crosses tenants.
 */
class StudentWatchTimeIntegrationTest extends VideoAccessManagementTestSupport {

	@Test
	void aGrantLiftsAUsedUpBudgetAndKeepsTheHistory() {
		VideoFixture f = seedVideoFixture("wtg-grant");
		UUID profileId = seedStudentProfile(f);
		useUpTenSecondBudget(f);

		JsonNode before = only(list(f, f.adminToken(), profileId));
		assertThat(before.path("videoAssetId").asString()).isEqualTo(f.videoAssetId().toString());
		// A platform-storage upload has no title: its file name is shown instead.
		assertThat(before.path("title").asString()).isEqualTo("wtg-grant.mp4");
		assertThat(before.path("limit").asString()).isEqualTo("LIMITED");
		assertThat(before.path("usedSeconds").asInt()).isEqualTo(10);
		assertThat(before.path("allowedSeconds").asInt()).isEqualTo(10);
		assertThat(before.path("remainingSeconds").asInt()).isZero();
		assertThat(before.path("exhausted").asBoolean()).isTrue();
		assertThat(issuePlaybackSession(f.host(), f.studentToken(), f.videoAssetId()).getStatusCode())
			.isEqualTo(HttpStatus.CONFLICT);

		MvcResult granted = grant(f, f.adminToken(), profileId, f.videoAssetId(), 2, "Exam revision");
		assertThat(status(granted)).isEqualTo(201);
		JsonNode after = json(granted).path("data");
		assertThat(after.path("usedSeconds").asInt()).isEqualTo(10);
		assertThat(after.path("grantedSeconds").asInt()).isEqualTo(120);
		assertThat(after.path("allowedSeconds").asInt()).isEqualTo(130);
		assertThat(after.path("remainingSeconds").asInt()).isEqualTo(120);
		assertThat(after.path("exhausted").asBoolean()).isFalse();
		assertThat(after.path("grants")).hasSize(1);
		assertThat(after.path("grants").get(0).path("reason").asString()).isEqualTo("Exam revision");
		assertThat(after.path("grants").get(0).path("grantedBy").asString()).isEqualTo(f.admin().getId().toString());

		// The Student can watch again; time watched was not reduced.
		assertThat(issuePlaybackSession(f.host(), f.studentToken(), f.videoAssetId()).getStatusCode())
			.isEqualTo(HttpStatus.CREATED);
		assertThat(jdbcTemplate.queryForObject("SELECT authorized_watch_seconds FROM video_watch_progress "
				+ "WHERE tenant_id = ? AND video_asset_id = ?", Integer.class, f.tenant().getId(), f.videoAssetId()))
			.isEqualTo(10);

		// Audited with actor, reason and before/after allowed time.
		List<Map<String, Object>> audit = jdbcTemplate.queryForList("SELECT actor_id, target_entity, target_id, reason, "
				+ "metadata::text AS metadata FROM audit_log WHERE tenant_id = ? AND action = 'video.watch_time_granted'",
				f.tenant().getId());
		assertThat(audit).hasSize(1);
		assertThat(audit.get(0).get("actor_id")).isEqualTo(f.admin().getId());
		assertThat(audit.get(0).get("target_entity")).isEqualTo("student_profile");
		assertThat(audit.get(0).get("target_id")).isEqualTo(profileId);
		assertThat(audit.get(0).get("reason")).isEqualTo("Exam revision");
		assertThat((String) audit.get(0).get("metadata")).contains("\"before\": 10").contains("\"after\": 130");

		// A second grant adds up; history keeps both.
		JsonNode twice = json(grant(f, f.adminToken(), profileId, f.videoAssetId(), 1, "Missed class")).path("data");
		assertThat(twice.path("allowedSeconds").asInt()).isEqualTo(190);
		assertThat(twice.path("grants")).hasSize(2);
	}

	@Test
	void grantsAreAppendOnly() {
		VideoFixture f = seedVideoFixture("wtg-append");
		UUID profileId = seedStudentProfile(f);
		useUpTenSecondBudget(f);
		assertThat(status(grant(f, f.adminToken(), profileId, f.videoAssetId(), 1, "x"))).isEqualTo(201);

		assertThatThrownBy(() -> jdbcTemplate.update("UPDATE video_watch_time_grant SET extra_seconds = 99999"))
			.hasMessageContaining("append-only");
		assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM video_watch_time_grant"))
			.hasMessageContaining("append-only");
	}

	@Test
	void invalidRequestsAndVideosWithoutALimitAreRefused() {
		VideoFixture f = seedVideoFixture("wtg-invalid");
		UUID profileId = seedStudentProfile(f);

		// No limit configured: nothing to extend.
		MvcResult unlimited = grant(f, f.adminToken(), profileId, f.videoAssetId(), 5, "x");
		assertThat(status(unlimited)).isEqualTo(409);

		useUpTenSecondBudget(f);
		assertThat(status(grant(f, f.adminToken(), profileId, f.videoAssetId(), 0, "x"))).isEqualTo(400);
		assertThat(status(grant(f, f.adminToken(), profileId, f.videoAssetId(), 6001, "x"))).isEqualTo(400);
		assertThat(status(grant(f, f.adminToken(), profileId, f.videoAssetId(), 5, "  "))).isEqualTo(400);
		assertThat(status(grant(f, f.adminToken(), profileId, UUID.randomUUID(), 5, "x"))).isEqualTo(404);
		assertThat(status(grant(f, f.adminToken(), UUID.randomUUID(), f.videoAssetId(), 5, "x"))).isEqualTo(404);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM video_watch_time_grant WHERE tenant_id = ?",
				Integer.class, f.tenant().getId())).isZero();
	}

	@Test
	void onlyStaffWithAccessExpiryPermissionMayViewOrGrant() {
		VideoFixture f = seedVideoFixture("wtg-perm");
		UUID profileId = seedStudentProfile(f);
		useUpTenSecondBudget(f);

		assertThat(status(list(f, f.teacherToken(), profileId))).isEqualTo(403);
		assertThat(status(grant(f, f.teacherToken(), profileId, f.videoAssetId(), 5, "x"))).isEqualTo(403);
		assertThat(status(list(f, f.studentToken(), profileId))).isEqualTo(403);
		assertThat(status(grant(f, f.studentToken(), profileId, f.videoAssetId(), 5, "x"))).isEqualTo(403);
	}

	@Tag("cross-tenant")
	@Test
	void nothingCrossesTenants() {
		VideoFixture a = seedVideoFixture("wtg-iso-a");
		VideoFixture b = seedVideoFixture("wtg-iso-b");
		UUID profileA = seedStudentProfile(a);
		UUID profileB = seedStudentProfile(b);
		useUpTenSecondBudget(a);

		// B's admin can neither read nor extend A's student.
		assertThat(status(list(b, b.adminToken(), profileA))).isEqualTo(404);
		assertThat(status(grant(b, b.adminToken(), profileA, a.videoAssetId(), 5, "x"))).isEqualTo(404);
		// Nor use A's video for B's own student.
		assertThat(status(grant(b, b.adminToken(), profileB, a.videoAssetId(), 5, "x"))).isEqualTo(404);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM video_watch_time_grant", Integer.class)).isZero();
		// A is untouched and still blocked.
		assertThat(only(list(a, a.adminToken(), profileA)).path("exhausted").asBoolean()).isTrue();
	}

	// ------------------------------------------------------------------

	/** A 10 s per-video limit, fully used through one heartbeat (WEB legacy player). */
	private void useUpTenSecondBudget(VideoFixture f) {
		upsertPolicyOrFail(f.host(), f.teacherToken(), f.videoAssetId(),
				new VideoPlaybackPolicyRequest(null, null, null, 10, true, false, true, 1));
		PlaybackSessionResponse session = issuePlaybackSessionOrFail(f.host(), f.studentToken(), f.videoAssetId());
		jdbcTemplate.update("UPDATE video_watch_session SET last_heartbeat_at = now() - interval '20 seconds' "
				+ "WHERE tenant_id = ? AND id = ?", f.tenant().getId(), session.watchSessionId());
		assertThat(recordProgress(f.host(), f.studentToken(), session.watchSessionId(), session.playbackToken(), 20, 20)
			.getBody()
			.error()
			.code()).isEqualTo(ApiErrorCodes.PLAYBACK_WATCH_LIMIT);
	}

	private UUID seedStudentProfile(VideoFixture f) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO student_profile (id, tenant_id, user_id, name, created_at, updated_at) "
				+ "VALUES (?, ?, ?, 'Student', now(), now())", id, f.tenant().getId(), f.student().getId());
		return id;
	}

	private MvcResult list(VideoFixture f, String token, UUID profileId) {
		return perform(authenticated(get("/api/v1/students/{id}/watch-time", profileId), f.host(), token));
	}

	private MvcResult grant(VideoFixture f, String token, UUID profileId, UUID assetId, int minutes, String reason) {
		String body = "{\"extraMinutes\":" + minutes + ",\"reason\":\"" + reason + "\"}";
		return perform(authenticated(post("/api/v1/students/{id}/watch-time/{asset}/grants", profileId, assetId)
			.contentType(MediaType.APPLICATION_JSON)
			.content(body), f.host(), token));
	}

	private JsonNode only(MvcResult result) {
		assertThat(status(result)).isEqualTo(200);
		JsonNode rows = json(result).path("data");
		assertThat(rows).hasSize(1);
		return rows.get(0);
	}

	private JsonNode json(MvcResult result) {
		return objectMapper.readTree(rawContent(result));
	}

	private static int status(MvcResult result) {
		return result.getResponse().getStatus();
	}

}
