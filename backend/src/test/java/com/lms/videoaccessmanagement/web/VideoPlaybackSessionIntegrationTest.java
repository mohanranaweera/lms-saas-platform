package com.lms.videoaccessmanagement.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.common.api.ApiErrorCodes;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.videoaccessmanagement.VideoAccessManagementTestSupport;
import com.lms.videoaccessmanagement.web.dto.PlaybackSessionResponse;
import com.lms.videoaccessmanagement.web.dto.VideoPlaybackPolicyRequest;
import com.lms.videoaccessmanagement.web.dto.VideoPlaybackPolicyResponse;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Testcontainers/MockMvc integration coverage for the secure-video-playback
 * engine (Wave 5, plan §8) - prioritizes the security-critical entitlement
 * and token-validation paths per the task brief, plus the policy-enforcement
 * paths (concurrency cap, max views, seek restriction, max watch duration).
 */
class VideoPlaybackSessionIntegrationTest extends VideoAccessManagementTestSupport {

	@Test
	void enrolledStudentCanIssueAPlaybackSessionAndRecordProgress() {
		VideoFixture f = seedVideoFixture("vpb-happy");

		PlaybackSessionResponse session = issuePlaybackSessionOrFail(f.host(), f.studentToken(), f.videoAssetId());
		assertThat(session.watchSessionId()).isNotNull();
		assertThat(session.playbackToken()).isNotBlank();
		assertThat(session.signedUrl()).isNotBlank();

		HttpResult<Void> progress = recordProgress(f.host(), f.studentToken(), session.watchSessionId(),
				session.playbackToken(), 5, 5);
		assertThat(progress.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	void unenrolledStudentCannotIssueAPlaybackSession() {
		VideoFixture f = seedVideoFixture("vpb-unenrolled");
		TenantUser outsider = seedActiveStudent(f.tenant().getId(), "outsider@example.test");
		String outsiderToken = loginAndGetToken(f.host(), "outsider@example.test");

		HttpResult<PlaybackSessionResponse> result = issuePlaybackSession(f.host(), outsiderToken, f.videoAssetId());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void crossTenantVideoAssetIdReturnsNotFound() {
		VideoFixture tenantAFixture = seedVideoFixture("vpb-tenant-a");
		VideoFixture tenantBFixture = seedVideoFixture("vpb-tenant-b");

		HttpResult<PlaybackSessionResponse> result = issuePlaybackSession(tenantBFixture.host(),
				tenantBFixture.studentToken(), tenantAFixture.videoAssetId());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void nonOwningTeacherIsDenied() {
		VideoFixture f = seedVideoFixture("vpb-non-owner");
		TenantUser otherTeacher = seedTenantUser(f.tenant().getId(), "other-teacher@example.test", RAW_PASSWORD,
				Role.TEACHER);
		String otherTeacherToken = loginAndGetToken(f.host(), "other-teacher@example.test");

		HttpResult<PlaybackSessionResponse> result = issuePlaybackSession(f.host(), otherTeacherToken,
				f.videoAssetId());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void owningTeacherGetsAPreviewSessionWithNoWatchSessionOrToken() {
		VideoFixture f = seedVideoFixture("vpb-teacher-preview");

		PlaybackSessionResponse session = issuePlaybackSessionOrFail(f.host(), f.teacherToken(), f.videoAssetId());

		assertThat(session.watchSessionId()).isNull();
		assertThat(session.playbackToken()).isNull();
		assertThat(session.signedUrl()).isNotBlank();
	}

	@Test
	void aSyntacticallyInvalidTokenIsRejectedOnTheProgressEndpoint() {
		VideoFixture f = seedVideoFixture("vpb-bad-token");
		PlaybackSessionResponse session = issuePlaybackSessionOrFail(f.host(), f.studentToken(), f.videoAssetId());

		HttpResult<Void> result = recordProgress(f.host(), f.studentToken(), session.watchSessionId(),
				"not-a-real-jwt", 5, 5);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(result.getBody().error().code()).isEqualTo(ApiErrorCodes.PLAYBACK_TOKEN_INVALID);
	}

	@Test
	void aTokenForAnEndedSessionIsRejectedOnAFollowUpProgressCall() {
		VideoFixture f = seedVideoFixture("vpb-ended");
		PlaybackSessionResponse session = issuePlaybackSessionOrFail(f.host(), f.studentToken(), f.videoAssetId());

		HttpResult<Void> end = endPlaybackSession(f.host(), f.studentToken(), session.watchSessionId());
		assertThat(end.getStatusCode()).isEqualTo(HttpStatus.OK);

		HttpResult<Void> progressAfterEnd = recordProgress(f.host(), f.studentToken(), session.watchSessionId(),
				session.playbackToken(), 5, 5);
		assertThat(progressAfterEnd.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void endingAPlaybackSessionIsIdempotent() {
		VideoFixture f = seedVideoFixture("vpb-idempotent-end");
		PlaybackSessionResponse session = issuePlaybackSessionOrFail(f.host(), f.studentToken(), f.videoAssetId());

		assertThat(endPlaybackSession(f.host(), f.studentToken(), session.watchSessionId()).getStatusCode())
			.isEqualTo(HttpStatus.OK);
		assertThat(endPlaybackSession(f.host(), f.studentToken(), session.watchSessionId()).getStatusCode())
			.isEqualTo(HttpStatus.OK);
	}

	@Test
	void issuingASecondSessionSupersedesTheFirstUnderTheDefaultConcurrencyCap() {
		VideoFixture f = seedVideoFixture("vpb-concurrency");

		PlaybackSessionResponse first = issuePlaybackSessionOrFail(f.host(), f.studentToken(), f.videoAssetId());
		PlaybackSessionResponse second = issuePlaybackSessionOrFail(f.host(), f.studentToken(), f.videoAssetId());

		assertThat(second.watchSessionId()).isNotEqualTo(first.watchSessionId());

		HttpResult<Void> progressOnSuperseded = recordProgress(f.host(), f.studentToken(), first.watchSessionId(),
				first.playbackToken(), 5, 5);
		assertThat(progressOnSuperseded.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

		HttpResult<Void> progressOnCurrent = recordProgress(f.host(), f.studentToken(), second.watchSessionId(),
				second.playbackToken(), 5, 5);
		assertThat(progressOnCurrent.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	void maxViewsPerStudentBlocksFurtherSessionIssuanceOnceReached() {
		VideoFixture f = seedVideoFixture("vpb-max-views");
		upsertPolicyOrFail(f.host(), f.teacherToken(), f.videoAssetId(),
				new VideoPlaybackPolicyRequest(null, null, 1, null, true, false, true, 1));

		issuePlaybackSessionOrFail(f.host(), f.studentToken(), f.videoAssetId());
		HttpResult<PlaybackSessionResponse> second = issuePlaybackSession(f.host(), f.studentToken(),
				f.videoAssetId());

		assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(second.getBody().error().code()).isEqualTo(ApiErrorCodes.POLICY_VIOLATION);
	}

	@Test
	void seekingAheadIsRejectedWhenThePolicyDisallowsSeeking() {
		VideoFixture f = seedVideoFixture("vpb-no-seek");
		upsertPolicyOrFail(f.host(), f.teacherToken(), f.videoAssetId(),
				new VideoPlaybackPolicyRequest(null, null, null, null, false, false, true, 1));
		PlaybackSessionResponse session = issuePlaybackSessionOrFail(f.host(), f.studentToken(), f.videoAssetId());

		// Within the seek tolerance of the starting furthestPositionSeconds (0),
		// so this is a normal forward heartbeat, not a seek.
		HttpResult<Void> earlyProgress = recordProgress(f.host(), f.studentToken(), session.watchSessionId(),
				session.playbackToken(), 5, 5);
		assertThat(earlyProgress.getStatusCode()).isEqualTo(HttpStatus.OK);

		HttpResult<Void> seekAhead = recordProgress(f.host(), f.studentToken(), session.watchSessionId(),
				session.playbackToken(), 500, 5);
		assertThat(seekAhead.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(seekAhead.getBody().error().code()).isEqualTo(ApiErrorCodes.SEEK_NOT_ALLOWED);
	}

	@Test
	void exceedingMaxWatchDurationRevokesTheSessionAndRejectsFurtherProgress() {
		VideoFixture f = seedVideoFixture("vpb-max-duration");
		upsertPolicyOrFail(f.host(), f.teacherToken(), f.videoAssetId(),
				new VideoPlaybackPolicyRequest(null, null, null, 10, true, false, true, 1));
		PlaybackSessionResponse session = issuePlaybackSessionOrFail(f.host(), f.studentToken(), f.videoAssetId());

		HttpResult<Void> overLimit = recordProgress(f.host(), f.studentToken(), session.watchSessionId(),
				session.playbackToken(), 20, 20);
		assertThat(overLimit.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(overLimit.getBody().error().code()).isEqualTo(ApiErrorCodes.POLICY_VIOLATION);

		HttpResult<Void> afterRevocation = recordProgress(f.host(), f.studentToken(), session.watchSessionId(),
				session.playbackToken(), 21, 1);
		assertThat(afterRevocation.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	/**
	 * {@code .claude/rules/security.md}: a server-side session revocation
	 * triggered by a policy breach must produce an audit/security log entry.
	 * Also proves the revoke + audit write SURVIVE even though the request
	 * that triggered them is ultimately rejected (409) and its own
	 * transaction rolls back - see {@code VideoWatchSessionRevocationRecorder}'s
	 * javadoc for the {@code REQUIRES_NEW} fix this documents.
	 */
	@Test
	void exceedingMaxWatchDurationWritesAnAuditLogRowThatSurvivesTheRejectedRequest() {
		VideoFixture f = seedVideoFixture("vpb-audit-max-duration");
		upsertPolicyOrFail(f.host(), f.teacherToken(), f.videoAssetId(),
				new VideoPlaybackPolicyRequest(null, null, null, 10, true, false, true, 1));
		PlaybackSessionResponse session = issuePlaybackSessionOrFail(f.host(), f.studentToken(), f.videoAssetId());

		HttpResult<Void> overLimit = recordProgress(f.host(), f.studentToken(), session.watchSessionId(),
				session.playbackToken(), 20, 20);
		assertThat(overLimit.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

		var rows = jdbcTemplate.queryForList(
				"SELECT tenant_id, actor_id, action, target_id, reason FROM audit_log "
						+ "WHERE action = 'video_watch_session.revoked' AND target_id = ?",
				session.watchSessionId());
		assertThat(rows).hasSize(1);
		assertThat(rows.get(0).get("tenant_id")).isEqualTo(f.tenant().getId());
		assertThat(rows.get(0).get("actor_id")).isEqualTo(f.student().getId());
		assertThat(rows.get(0).get("reason")).isEqualTo("max_watch_duration_exceeded");
	}

	@Test
	void accessWindowNotYetOpenBlocksSessionIssuance() {
		VideoFixture f = seedVideoFixture("vpb-not-yet-open");
		upsertPolicyOrFail(f.host(), f.teacherToken(), f.videoAssetId(), new VideoPlaybackPolicyRequest(
				Instant.now().plusSeconds(3600), null, null, null, true, false, true, 1));

		HttpResult<PlaybackSessionResponse> result = issuePlaybackSession(f.host(), f.studentToken(),
				f.videoAssetId());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(result.getBody().error().code()).isEqualTo(ApiErrorCodes.POLICY_VIOLATION);
	}

	@Test
	void accessWindowAlreadyExpiredBlocksSessionIssuance() {
		VideoFixture f = seedVideoFixture("vpb-window-expired");
		upsertPolicyOrFail(f.host(), f.teacherToken(), f.videoAssetId(), new VideoPlaybackPolicyRequest(null,
				Instant.now().minusSeconds(3600), null, null, true, false, true, 1));

		HttpResult<PlaybackSessionResponse> result = issuePlaybackSession(f.host(), f.studentToken(),
				f.videoAssetId());

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(result.getBody().error().code()).isEqualTo(ApiErrorCodes.POLICY_VIOLATION);
	}

	/**
	 * Security-review fix regression test (see {@code
	 * VideoAssetService#requirePolicyAuthorization}'s javadoc): a
	 * same-tenant Teacher who does not teach the course that owns this
	 * video asset's attached {@code Material} must be denied when mutating
	 * its playback policy - previously the endpoint used the loose "any
	 * Teacher/TA in the tenant" upload gate, letting Teacher B silently
	 * strip Teacher A's watermark/download/view-limit protections. This is
	 * an intra-tenant IDOR check (both teachers below share one tenant),
	 * not a cross-tenant one - {@code crossTenantVideoAssetIdReturnsNotFound}
	 * above already covers the cross-tenant case for session issuance.
	 */
	@Test
	void nonOwningTeacherCannotUpsertPolicyButTheOwningTeacherCan() {
		VideoFixture f = seedVideoFixture("vpb-policy-non-owner");
		TenantUser otherTeacher = seedTenantUser(f.tenant().getId(), "other-policy-teacher@example.test",
				RAW_PASSWORD, Role.TEACHER);
		String otherTeacherToken = loginAndGetToken(f.host(), "other-policy-teacher@example.test");
		VideoPlaybackPolicyRequest request = new VideoPlaybackPolicyRequest(null, null, null, null, true, false,
				true, 1);

		HttpResult<VideoPlaybackPolicyResponse> deniedResult = upsertPolicy(f.host(), otherTeacherToken,
				f.videoAssetId(), request);
		assertThat(deniedResult.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

		VideoPlaybackPolicyResponse allowed = upsertPolicyOrFail(f.host(), f.teacherToken(), f.videoAssetId(),
				request);
		assertThat(allowed.videoAssetId()).isEqualTo(f.videoAssetId());
	}

	@Test
	void videoUploadRejectsANonVideoFile() {
		VideoFixture f = seedVideoFixture("vpb-bad-mime");

		var result = uploadVideo(f.host(), f.teacherToken(), pdfFile("not-a-video.pdf"));

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
	}

	@Test
	void studentCannotUploadAVideo() {
		VideoFixture f = seedVideoFixture("vpb-student-upload");

		var result = uploadVideo(f.host(), f.studentToken(), mp4File("student-upload.mp4"));

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

}
