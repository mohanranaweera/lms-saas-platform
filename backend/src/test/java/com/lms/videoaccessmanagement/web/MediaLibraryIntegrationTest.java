package com.lms.videoaccessmanagement.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.lms.common.api.PageResponse;
import com.lms.common.error.ConflictException;
import com.lms.common.error.NotFoundException;
import com.lms.identityaccessservice.HttpResult;
import com.lms.identityaccessservice.domain.Role;
import com.lms.videoaccessmanagement.VideoAccessManagementTestSupport;
import com.lms.videoaccessmanagement.domain.MediaAssetProvider;
import com.lms.videoaccessmanagement.domain.PlaybackMode;
import com.lms.videoaccessmanagement.domain.VideoAssetStatus;
import com.lms.videoaccessmanagement.domain.VideoAssetStatusEvent;
import com.lms.videoaccessmanagement.repository.VideoAssetStatusEventRepository;
import com.lms.videoaccessmanagement.service.MediaAssetLifecycleService;
import com.lms.videoaccessmanagement.service.MediaAssetView;
import com.lms.videoaccessmanagement.web.dto.MediaAssetResponse;
import com.lms.videoaccessmanagement.web.dto.VideoAssetResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * VIDEO Phase 2 - Media Library reads, the asset status machine, and the V70
 * schema guards. Cross-tenant cases cover plan §32 T1/T2/T12.
 */
class MediaLibraryIntegrationTest extends VideoAccessManagementTestSupport {

	@Autowired
	private MediaAssetLifecycleService lifecycleService;

	@Autowired
	private VideoAssetStatusEventRepository statusEventRepository;

	// ------------------------------------------------------------------
	// Visibility inside a tenant
	// ------------------------------------------------------------------

	/**
	 * R2-D2 (owner decision 2026-10-14) replaces the earlier
	 * "teacherSeesOnlyOwnUploadsWhileTenantAdminSeesTheWholeLibrary": teachers
	 * now see (list + get) every institute video so they can attach any of
	 * them, but rename / delete stay owner-or-admin - another teacher's video
	 * is still a 404 for those actions, and the response tells the caller what
	 * they may do ({@code canEdit}/{@code canDelete}).
	 */
	@Test
	void teacherSeesEveryInstituteVideoButManagesOnlyTheirOwn() {
		VideoFixture f = seedVideoFixture("media-visibility");
		seedTenantUser(f.tenant().getId(), "teacher2@example.test", RAW_PASSWORD, Role.TEACHER);
		String teacher2Token = loginAndGetToken(f.host(), "teacher2@example.test");
		VideoAssetResponse otherUpload = uploadVideoOrFail(f.host(), teacher2Token, mp4File("other.mp4"));

		assertThat(ids(listMediaOrFail(f.host(), f.teacherToken(), "")))
			.containsExactlyInAnyOrder(f.videoAssetId(), otherUpload.id());
		assertThat(ids(listMediaOrFail(f.host(), teacher2Token, "")))
			.containsExactlyInAnyOrder(f.videoAssetId(), otherUpload.id());
		assertThat(ids(listMediaOrFail(f.host(), f.adminToken(), "")))
			.containsExactlyInAnyOrder(f.videoAssetId(), otherUpload.id());

		// Visible by id, but flagged read-only for the non-owner teacher.
		HttpResult<MediaAssetResponse> othersVideo = getMedia(f.host(), teacher2Token, f.videoAssetId());
		assertThat(othersVideo.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(othersVideo.getBody().data().canEdit()).isFalse();
		assertThat(othersVideo.getBody().data().canDelete()).isFalse();
		MediaAssetResponse ownVideo = getMedia(f.host(), teacher2Token, otherUpload.id()).getBody().data();
		assertThat(ownVideo.canEdit()).isTrue();
		assertThat(ownVideo.canDelete()).isTrue();
		MediaAssetResponse asAdmin = getMedia(f.host(), f.adminToken(), otherUpload.id()).getBody().data();
		assertThat(asAdmin.canEdit()).isTrue();
		assertThat(asAdmin.canDelete()).isTrue();

		// Rename / delete of another teacher's video stay 404 (owner-or-admin).
		MvcResult rename = perform(authenticated(patch("/api/v1/media/{id}", otherUpload.id())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":\"hijacked\"}"), f.host(), f.teacherToken()));
		assertThat(rename.getResponse().getStatus()).isEqualTo(404);
		MvcResult deleteOthers = perform(
				authenticated(delete("/api/v1/media/{id}", otherUpload.id()), f.host(), f.teacherToken()));
		assertThat(deleteOthers.getResponse().getStatus()).isEqualTo(404);
		assertThat(getMedia(f.host(), f.adminToken(), otherUpload.id()).getBody().data().title())
			.isEqualTo("other.mp4");

		// The owner still can.
		MvcResult deleteOwn = perform(
				authenticated(delete("/api/v1/media/{id}", otherUpload.id()), f.host(), teacher2Token));
		assertThat(deleteOwn.getResponse().getStatus()).isEqualTo(200);
		assertThat(ids(listMediaOrFail(f.host(), f.teacherToken(), ""))).containsExactly(f.videoAssetId());
	}

	/** R2-D2: a teacher may attach another teacher's video to a lesson of their own course. */
	@Test
	void teacherCanAttachAnotherTeachersVideoToTheirOwnLesson() {
		VideoFixture f = seedVideoFixture("media-attach-other");
		var teacher2 = seedTenantUser(f.tenant().getId(), "teacher2@example.test", RAW_PASSWORD, Role.TEACHER);
		String teacher2Token = loginAndGetToken(f.host(), "teacher2@example.test");
		var course = createCourseOrFail(f.host(), f.adminToken(),
				newCourseRequest(uniqueSlug("media-attach-other"), teacher2.getId(),
						com.lms.coursemanagement.course.domain.CourseStatus.PUBLIC));
		var module = createModuleOrFail(f.host(), f.adminToken(), course.id(), "Module 1", 1);
		var lesson = createLessonOrFail(f.host(), f.adminToken(), course.id(), module.id(), "Lesson 1", 1);

		var attached = createMaterialRaw(f.host(), teacher2Token, course.id(), module.id(), lesson.id(),
				"Borrowed video", com.lms.contentmanagement.material.domain.MaterialType.VIDEO, null, null, null,
				f.videoAssetId(), null, null, null, null);

		assertThat(attached.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(getMedia(f.host(), teacher2Token, f.videoAssetId()).getBody().data().attachedLessonCount())
			.isEqualTo(2);
	}

	/**
	 * R2-D2: now that a teacher can see another teacher's (unattached) video,
	 * its playback policy must still be owner-or-admin - the pre-attach
	 * "any teacher" window is limited to the uploader.
	 */
	@Test
	void teacherCannotChangeThePolicyOfAnotherTeachersUnattachedVideo() {
		VideoFixture f = seedVideoFixture("media-policy-owner");
		seedTenantUser(f.tenant().getId(), "teacher2@example.test", RAW_PASSWORD, Role.TEACHER);
		String teacher2Token = loginAndGetToken(f.host(), "teacher2@example.test");
		VideoAssetResponse unattached = uploadVideoOrFail(f.host(), teacher2Token, mp4File("mine.mp4"));
		var request = new com.lms.videoaccessmanagement.web.dto.VideoPlaybackPolicyRequest(null, null, 3, null,
				true, false, true, null);

		assertThat(upsertPolicy(f.host(), f.teacherToken(), unattached.id(), request).getStatusCode())
			.isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(upsertPolicy(f.host(), teacher2Token, unattached.id(), request).getStatusCode())
			.isEqualTo(HttpStatus.OK);
		assertThat(upsertPolicy(f.host(), f.adminToken(), unattached.id(), request).getStatusCode())
			.isEqualTo(HttpStatus.OK);
	}

	@Test
	void attachingAnotherTeachersVideoDoesNotGrantPolicyAuthority() {
		VideoFixture f = seedVideoFixture("media-policy-attach");
		var borrower = seedTenantUser(f.tenant().getId(), "borrower@example.test", RAW_PASSWORD, Role.TEACHER);
		String borrowerToken = loginAndGetToken(f.host(), "borrower@example.test");
		var course = createCourseOrFail(f.host(), f.adminToken(),
				newCourseRequest(uniqueSlug("borrower-course"), borrower.getId(),
						com.lms.coursemanagement.course.domain.CourseStatus.PUBLIC));
		var module = createModuleOrFail(f.host(), f.adminToken(), course.id(), "Module", 1);
		var lesson = createLessonOrFail(f.host(), f.adminToken(), course.id(), module.id(), "Lesson", 1);
		var video = uploadVideoOrFail(f.host(), f.teacherToken(), mp4File("shared-unattached.mp4"));
		var policy = new com.lms.videoaccessmanagement.web.dto.VideoPlaybackPolicyRequest(null, null, 3, null,
				true, false, true, null);
		upsertPolicyOrFail(f.host(), f.teacherToken(), video.id(), policy);
		var attached = createMaterialRaw(f.host(), borrowerToken, course.id(), module.id(), lesson.id(),
				"Borrowed", com.lms.contentmanagement.material.domain.MaterialType.VIDEO, null, null, null,
				video.id(), null, null, null, null);
		assertThat(attached.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		var before = jdbcTemplate.queryForMap("SELECT max_views_per_student, allow_download, watermark_enabled, "
				+ "max_watch_duration_seconds FROM video_playback_policy WHERE video_asset_id = ?", video.id());
		var weakened = new com.lms.videoaccessmanagement.web.dto.VideoPlaybackPolicyRequest(null, null, null,
				null, true, true, false, null);

		assertThat(upsertPolicy(f.host(), borrowerToken, video.id(), weakened).getStatusCode())
			.isEqualTo(HttpStatus.NOT_FOUND);
		MvcResult limit = perform(authenticated(put("/api/v1/videos/{id}/watch-time-limit", video.id())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"limitSeconds\":600}"), f.host(), borrowerToken));
		assertThat(limit.getResponse().getStatus()).isEqualTo(404);
		assertThat(jdbcTemplate.queryForMap("SELECT max_views_per_student, allow_download, watermark_enabled, "
				+ "max_watch_duration_seconds FROM video_playback_policy WHERE video_asset_id = ?", video.id()))
			.isEqualTo(before);
		// Retain the existing all-attached-courses safeguard even for the uploader.
		assertThat(upsertPolicy(f.host(), f.teacherToken(), video.id(), policy).getStatusCode())
			.isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(upsertPolicy(f.host(), f.adminToken(), video.id(), policy).getStatusCode())
			.isEqualTo(HttpStatus.OK);
		assertThat(deleteMaterial(f.host(), borrowerToken, course.id(), module.id(), lesson.id(),
				attached.getBody().data().id()).getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(upsertPolicy(f.host(), f.teacherToken(), video.id(), policy).getStatusCode())
			.isEqualTo(HttpStatus.OK);
	}

	@Test
	void studentCannotUseTheMediaLibrary() {
		VideoFixture f = seedVideoFixture("media-student");

		assertThat(listMedia(f.host(), f.studentToken(), "").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(getMedia(f.host(), f.studentToken(), f.videoAssetId()).getStatusCode())
			.isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void legacyUploadAppearsAsAReadyInternalAssetWithoutLeakingStorageDetails() throws Exception {
		VideoFixture f = seedVideoFixture("media-shape");

		MvcResult raw = perform(authenticated(get("/api/v1/media/{id}", f.videoAssetId()), f.host(), f.teacherToken()));
		HttpResult<MediaAssetResponse> result = parseSingle(raw, MediaAssetResponse.class);

		assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
		MediaAssetResponse media = result.getBody().data();
		assertThat(media.provider()).isEqualTo(MediaAssetProvider.INTERNAL);
		assertThat(media.processingStatus()).isEqualTo(VideoAssetStatus.READY);
		assertThat(media.title()).isEqualTo("media-shape.mp4");
		assertThat(media.enabledPlaybackModes()).containsExactly(PlaybackMode.INTERNAL_SIGNED_URL);
		assertThat(media.capabilities().supportsOverlayWatermark()).isTrue();
		assertThat(raw.getResponse().getContentAsString()).doesNotContain("storageObjectKey")
			.doesNotContain("providerConnectionId");
	}

	@Test
	void listFiltersBySearchTextAndStatus() {
		VideoFixture f = seedVideoFixture("media-filter");
		uploadVideoOrFail(f.host(), f.teacherToken(), mp4File("algebra-week-2.mp4"));

		assertThat(listMediaOrFail(f.host(), f.adminToken(), "?q=ALGEBRA").content())
			.extracting(MediaAssetResponse::title)
			.containsExactly("algebra-week-2.mp4");
		// A LIKE wildcard in the search text is matched literally, not as "everything".
		assertThat(listMediaOrFail(f.host(), f.adminToken(), "?q=%25").content()).isEmpty();
		assertThat(listMediaOrFail(f.host(), f.adminToken(), "?status=PROCESSING").content()).isEmpty();
		assertThat(listMediaOrFail(f.host(), f.adminToken(), "?status=READY").content()).hasSize(2);
		assertThat(listMedia(f.host(), f.adminToken(), "?size=101").getStatusCode())
			.isEqualTo(HttpStatus.BAD_REQUEST);
	}

	// ------------------------------------------------------------------
	// Cross-tenant isolation (mandatory)
	// ------------------------------------------------------------------

	@Tag("cross-tenant")
	@Test
	void tenantACannotListOrRetrieveTenantBMedia() {
		VideoFixture a = seedVideoFixture("media-iso-a");
		VideoFixture b = seedVideoFixture("media-iso-b");

		assertThat(ids(listMediaOrFail(b.host(), b.adminToken(), ""))).containsExactly(b.videoAssetId());
		assertThat(getMedia(b.host(), b.adminToken(), a.videoAssetId()).getStatusCode())
			.isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(getMedia(b.host(), b.teacherToken(), a.videoAssetId()).getStatusCode())
			.isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Tag("cross-tenant")
	@Test
	void aTenantIdSuppliedByTheClientIsIgnored() {
		VideoFixture a = seedVideoFixture("media-inject-a");
		VideoFixture b = seedVideoFixture("media-inject-b");

		PageResponse<MediaAssetResponse> page = listMediaOrFail(b.host(), b.adminToken(),
				"?tenantId=" + a.tenant().getId());

		assertThat(ids(page)).containsExactly(b.videoAssetId());
	}

	@Tag("cross-tenant")
	@Test
	void lifecycleTransitionsAndHistoryStayInsideTheAssetTenant() {
		VideoFixture a = seedVideoFixture("media-life-a");
		VideoFixture b = seedVideoFixture("media-life-b");
		UUID tenantA = a.tenant().getId();
		UUID tenantB = b.tenant().getId();

		assertThatThrownBy(() -> withTenant(tenantB,
				() -> lifecycleService.transition(a.videoAssetId(), VideoAssetStatus.FAILED, "x")))
			.isInstanceOf(NotFoundException.class);

		MediaAssetView failed = withTenant(tenantA,
				() -> lifecycleService.transition(a.videoAssetId(), VideoAssetStatus.FAILED, "rejected by provider"));
		assertThat(failed.processingStatus()).isEqualTo(VideoAssetStatus.FAILED);
		assertThat(failed.failureReason()).isEqualTo("rejected by provider");

		List<VideoAssetStatusEvent> history = withTenant(tenantA, () -> statusEventRepository.findAll())
			.stream()
			.filter(e -> e.getVideoAssetId().equals(a.videoAssetId()))
			.sorted(Comparator.comparing(VideoAssetStatusEvent::getOccurredAt))
			.toList();
		assertThat(history).extracting(VideoAssetStatusEvent::getToStatus)
			.containsExactly(VideoAssetStatus.READY, VideoAssetStatus.FAILED);
		assertThat(history.get(0).getFromStatus()).isNull();
		assertThat(history.get(0).getActorId()).isEqualTo(a.teacher().getId());
		assertThat(history.get(1).getActorId()).isNull();
		assertThat(history.get(1).getReason()).isEqualTo("rejected by provider");

		assertThat(withTenant(tenantB, () -> statusEventRepository.findAll()))
			.noneMatch(e -> e.getVideoAssetId().equals(a.videoAssetId()));
	}

	@Test
	void illegalTransitionsAreConflicts() {
		VideoFixture f = seedVideoFixture("media-illegal");
		UUID tenant = f.tenant().getId();

		assertThatThrownBy(() -> withTenant(tenant,
				() -> lifecycleService.transition(f.videoAssetId(), VideoAssetStatus.UPLOADING, null)))
			.isInstanceOf(ConflictException.class);
		assertThatThrownBy(() -> withTenant(tenant,
				() -> lifecycleService.transition(f.videoAssetId(), VideoAssetStatus.DELETED, null)))
			.isInstanceOf(ConflictException.class);
	}

	// ------------------------------------------------------------------
	// V70 schema guards
	// ------------------------------------------------------------------

	@Test
	void statusHistoryIsAppendOnlyInTheDatabase() {
		VideoFixture f = seedVideoFixture("media-append-only");

		assertThatThrownBy(() -> jdbcTemplate.update(
				"UPDATE video_asset_status_event SET reason = 'edited' WHERE video_asset_id = ?", f.videoAssetId()))
			.isInstanceOf(DataAccessException.class);
		assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM video_asset_status_event WHERE video_asset_id = ?",
				f.videoAssetId()))
			.isInstanceOf(DataAccessException.class);
	}

	@Test
	void externallyHostedAssetsNeedAProviderConnectionAndDeletionMustBeConsistent() {
		VideoFixture f = seedVideoFixture("media-shape-check");
		Timestamp now = Timestamp.from(Instant.now());

		assertThatThrownBy(() -> jdbcTemplate.update("""
				INSERT INTO video_asset (id, tenant_id, provider, original_filename, mime_type, size_bytes, status,
				    uploaded_by, title, privacy_status, created_at, updated_at)
				VALUES (?, ?, 'YOUTUBE', 'a.mp4', 'video/mp4', 10, 'CREATED', ?, 't', 'UNLISTED', ?, ?)
				""", UUID.randomUUID(), f.tenant().getId(), f.teacher().getId(), now, now))
			.isInstanceOf(DataAccessException.class);

		assertThatThrownBy(
				() -> jdbcTemplate.update("UPDATE video_asset SET status = 'DELETED' WHERE id = ?", f.videoAssetId()))
			.isInstanceOf(DataAccessException.class);
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	private HttpResult<PageResponse<MediaAssetResponse>> listMedia(String host, String token, String query) {
		return parsePage(perform(authenticated(get("/api/v1/media" + query), host, token)), MediaAssetResponse.class);
	}

	private PageResponse<MediaAssetResponse> listMediaOrFail(String host, String token, String query) {
		HttpResult<PageResponse<MediaAssetResponse>> result = listMedia(host, token, query);
		if (result.getStatusCode() != HttpStatus.OK) {
			throw new IllegalStateException("Media list failed: " + result.getStatusCode() + " " + result.getBody());
		}
		return result.getBody().data();
	}

	private HttpResult<MediaAssetResponse> getMedia(String host, String token, UUID id) {
		return parseSingle(perform(authenticated(get("/api/v1/media/{id}", id), host, token)),
				MediaAssetResponse.class);
	}

	private static List<UUID> ids(PageResponse<MediaAssetResponse> page) {
		return page.content().stream().map(MediaAssetResponse::id).toList();
	}

}
