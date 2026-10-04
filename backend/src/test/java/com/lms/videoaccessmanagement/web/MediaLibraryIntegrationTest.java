package com.lms.videoaccessmanagement.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

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

	@Test
	void teacherSeesOnlyOwnUploadsWhileTenantAdminSeesTheWholeLibrary() {
		VideoFixture f = seedVideoFixture("media-visibility");
		seedTenantUser(f.tenant().getId(), "teacher2@example.test", RAW_PASSWORD, Role.TEACHER);
		String teacher2Token = loginAndGetToken(f.host(), "teacher2@example.test");
		VideoAssetResponse otherUpload = uploadVideoOrFail(f.host(), teacher2Token, mp4File("other.mp4"));

		assertThat(ids(listMediaOrFail(f.host(), f.teacherToken(), ""))).containsExactly(f.videoAssetId());
		assertThat(ids(listMediaOrFail(f.host(), teacher2Token, ""))).containsExactly(otherUpload.id());
		assertThat(ids(listMediaOrFail(f.host(), f.adminToken(), "")))
			.containsExactlyInAnyOrder(f.videoAssetId(), otherUpload.id());

		// Another teacher's asset in the same tenant is not visible by id either.
		assertThat(getMedia(f.host(), teacher2Token, f.videoAssetId()).getStatusCode())
			.isEqualTo(HttpStatus.NOT_FOUND);
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
