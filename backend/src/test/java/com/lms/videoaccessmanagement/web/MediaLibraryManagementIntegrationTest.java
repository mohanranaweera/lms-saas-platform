package com.lms.videoaccessmanagement.web;

import static com.lms.integrationmanagement.media.FakeMediaProviderServer.LEGACY_TENANT_CHANNEL_SCOPES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.web.dto.CourseLessonResponse;
import com.lms.coursemanagement.course.web.dto.CourseModuleResponse;
import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.integrationmanagement.media.FakeYouTubeUploads.Failure;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * VIDEO Phase 5 - Media Library management: upload progress and the
 * "attached" flag on reads, editing (LMS-only before the upload, synced to
 * YouTube after - ADR-018 Amendment 3), soft delete with its guards and
 * audit, the upload-limits endpoint, roles and tenant isolation (plan §32
 * T1-T4 for the new endpoints).
 */
class MediaLibraryManagementIntegrationTest extends MediaUploadTestSupport {

	// ------------------------------------------------------------------
	// Reads
	// ------------------------------------------------------------------

	@Test
	void listAndDetailShowUploadProgressAndWhetherALessonUsesTheMedia() {
		Ctx ctx = seedUploadTenant("lib-progress", "UC-lib-progress");
		UUID assetId = stageAndQueue(ctx, mp4(PART));

		JsonNode item = listItem(ctx, ctx.teacherToken(), assetId);
		assertThat(item.path("uploadProgress").path("state").asString()).isEqualTo("QUEUED");
		assertThat(item.path("uploadProgress").path("totalBytes").asLong()).isEqualTo(PART);
		assertThat(item.path("uploadProgress").path("bytesSent").asLong()).isZero();
		assertThat(item.path("attached").asBoolean()).isFalse();

		worker.drain(5);
		JsonNode detail = detail(ctx, ctx.teacherToken(), assetId);
		assertThat(detail.path("uploadProgress").path("state").asString()).isEqualTo("COMPLETED");
		assertThat(detail.path("uploadProgress").path("bytesSent").asLong()).isEqualTo(PART);

		attachToLesson(ctx, assetId);
		assertThat(detail(ctx, ctx.teacherToken(), assetId).path("attached").asBoolean()).isTrue();
		assertThat(listItem(ctx, ctx.adminToken(), assetId).path("attached").asBoolean()).isTrue();
	}

	@Test
	void uploadLimitsDescribeTheBudgetAndTheChannel() {
		Ctx ctx = seedUploadTenant("lib-limits", "UC-lib-limits");
		create(ctx.host(), ctx.teacherToken(), body("One", PART));

		JsonNode limits = limits(ctx.host(), ctx.teacherToken());
		assertThat(limits.path("uploadsEnabled").asBoolean()).isTrue();
		assertThat(limits.path("channel").asString()).isEqualTo("CONNECTED");
		assertThat(limits.path("editingEnabled").asBoolean()).isTrue();
		assertThat(limits.path("dailyLimit").asInt()).isEqualTo(20);
		assertThat(limits.path("usedToday").asInt()).isEqualTo(1);
		assertThat(limits.path("partSizeBytes").asLong()).isEqualTo(PART);
		assertThat(limits.path("allowedMimeTypes").toString()).contains("video/mp4");

		Ctx none = seedUploadTenant("lib-limits-none", null);
		assertThat(limits(none.host(), none.adminToken()).path("channel").asString()).isEqualTo("NOT_CONNECTED");
		assertThat(limits(none.host(), none.adminToken()).path("usedToday").asInt()).isZero();
		assertThat(status(perform(authenticated(get(MEDIA + "/upload-limits"), ctx.host(), ctx.studentToken()))))
			.isEqualTo(403);
	}

	// ------------------------------------------------------------------
	// Edit
	// ------------------------------------------------------------------

	@Test
	void anEditBeforeTheUploadStartsStaysInTheLms() {
		Ctx ctx = seedUploadTenant("lib-edit-early", "UC-lib-edit-early");
		UUID assetId = assetIdOf(json(create(ctx.host(), ctx.teacherToken(), body("Draft", PART))).path("data"));

		MvcResult result = edit(ctx.host(), ctx.teacherToken(), assetId, Map.of("title", "Final title"));

		assertThat(status(result)).isEqualTo(200);
		assertThat(json(result).path("data").path("title").asString()).isEqualTo("Final title");
		assertThat(YOUTUBE.updates()).isEmpty();
		assertThat(auditCount("media_asset.updated", assetId)).isEqualTo(1);
	}

	@Test
	void anEditOfAVideoOnYouTubeIsSentToYouTubeThenStored() {
		Ctx ctx = seedUploadTenant("lib-edit-sync", "UC-lib-edit-sync");
		UUID assetId = stageAndQueue(ctx, mp4(PART));
		worker.drain(5);

		MvcResult result = edit(ctx.host(), ctx.teacherToken(), assetId,
				Map.of("title", "Algebra 1", "description", "Week one", "privacyStatus", "PRIVATE"));

		assertThat(status(result)).isEqualTo(200);
		JsonNode data = json(result).path("data");
		assertThat(data.path("title").asString()).isEqualTo("Algebra 1");
		assertThat(data.path("privacyStatus").asString()).isEqualTo("PRIVATE");
		assertThat(YOUTUBE.updates()).hasSize(1);
		assertThat(YOUTUBE.updates().getFirst()).contains("Algebra 1").contains("Week one").contains("private")
			.contains("categoryId").contains(providerAssetId(assetId));
		assertThat(PROVIDERS.requests("PUT", "/youtube/v3/videos")).allMatch(
				r -> ("Bearer " + accessTokenOf(ctx.tenant().getId())).equals(r.authorization()));
		assertThat(jdbcTemplate.queryForObject("SELECT metadata::text FROM audit_log WHERE action = ? "
				+ "AND target_id = ?", String.class, "media_asset.updated", assetId)).contains("before")
			.contains("Algebra 1");
	}

	@Test
	void editsWhileTheFileIsBeingSentAreRefused() {
		Ctx ctx = seedUploadTenant("lib-edit-busy", "UC-lib-edit-busy");
		UUID assetId = stageAndQueue(ctx, mp4(PART));

		MvcResult result = edit(ctx.host(), ctx.teacherToken(), assetId, Map.of("title", "Too soon"));

		assertThat(status(result)).isEqualTo(409);
		assertThat(errorCode(result)).isEqualTo("MEDIA_UPLOAD_STATE_CONFLICT");
	}

	@Test
	void aChannelConnectedBeforeAmendment3MustReconnectToEdit() {
		Ctx ctx = seedUploadTenant("lib-legacy", "UC-lib-legacy");
		UUID assetId = stageAndQueue(ctx, mp4(PART));
		worker.drain(5);
		jdbcTemplate.update("UPDATE media_provider_connection SET granted_scopes = ? WHERE id = ?",
				LEGACY_TENANT_CHANNEL_SCOPES, currentConnection(ctx.tenant().getId()));

		MvcResult refused = edit(ctx.host(), ctx.teacherToken(), assetId, Map.of("title", "Renamed"));

		assertThat(status(refused)).isEqualTo(409);
		assertThat(errorCode(refused)).isEqualTo("VIDEO_PROVIDER_SCOPE_MISSING");
		assertThat(YOUTUBE.updates()).isEmpty();
		assertThat(detail(ctx, ctx.teacherToken(), assetId).path("title").asString()).isEqualTo("Video");
		assertThat(limits(ctx.host(), ctx.teacherToken()).path("editingEnabled").asBoolean()).isFalse();
		assertThat(json(perform(authenticated(get(PROVIDERS_BASE), ctx.host(), ctx.adminToken()))).path("data")
			.path(0)
			.path("canEditVideos")
			.asBoolean()).isFalse();
		// Uploads still work on the old grant.
		assertThat(status(create(ctx.host(), ctx.teacherToken(), body("Still uploads", PART)))).isEqualTo(201);

		connectChannel(ctx, ctx.channelId());
		assertThat(status(edit(ctx.host(), ctx.teacherToken(), assetId, Map.of("title", "Renamed")))).isEqualTo(200);
	}

	@Test
	void aYouTubeRefusalLeavesTheLmsUnchanged() {
		Ctx ctx = seedUploadTenant("lib-refused", "UC-lib-refused");
		UUID assetId = stageAndQueue(ctx, mp4(PART));
		worker.drain(5);

		YOUTUBE.failUpdates(new Failure(403, "insufficientPermissions"));
		MvcResult scope = edit(ctx.host(), ctx.teacherToken(), assetId, Map.of("title", "X"));
		assertThat(status(scope)).isEqualTo(409);
		assertThat(errorCode(scope)).isEqualTo("VIDEO_PROVIDER_SCOPE_MISSING");

		YOUTUBE.failUpdates(new Failure(503, "backendError"));
		assertThat(status(edit(ctx.host(), ctx.teacherToken(), assetId, Map.of("title", "X")))).isEqualTo(503);

		assertThat(detail(ctx, ctx.teacherToken(), assetId).path("title").asString()).isEqualTo("Video");
		assertThat(auditCount("media_asset.updated", assetId)).isZero();
	}

	@Test
	void editsAreValidated() {
		Ctx ctx = seedUploadTenant("lib-edit-valid", "UC-lib-edit-valid");
		UUID assetId = assetIdOf(json(create(ctx.host(), ctx.teacherToken(), body("Draft", PART))).path("data"));

		assertThat(status(edit(ctx.host(), ctx.teacherToken(), assetId, Map.of()))).isEqualTo(400);
		assertThat(status(edit(ctx.host(), ctx.teacherToken(), assetId, Map.of("privacyStatus", "PUBLIC"))))
			.isEqualTo(400);
		assertThat(status(edit(ctx.host(), ctx.teacherToken(), assetId, Map.of("title", "<b>x</b>"))))
			.isEqualTo(400);
		assertThat(status(edit(ctx.host(), ctx.teacherToken(), assetId, Map.of("title", "  ")))).isEqualTo(400);
		assertThat(status(edit(ctx.host(), ctx.teacherToken(), assetId, Map.of("description", "  "))))
			.isEqualTo(200);
	}

	// ------------------------------------------------------------------
	// Delete
	// ------------------------------------------------------------------

	@Test
	void deleteIsAnAuditedSoftDeleteThatKeepsTheYouTubeCopy() {
		Ctx ctx = seedUploadTenant("lib-delete", "UC-lib-delete");
		UUID assetId = stageAndQueue(ctx, mp4(PART));
		worker.drain(5);
		int youtubeCallsBefore = PROVIDERS.requests().size();

		MvcResult deleted = remove(ctx.host(), ctx.adminToken(), assetId);

		assertThat(status(deleted)).isEqualTo(200);
		assertThat(status(perform(authenticated(get(MEDIA + "/{id}", assetId), ctx.host(), ctx.adminToken()))))
			.isEqualTo(404);
		assertThat(rawContent(perform(authenticated(get(MEDIA), ctx.host(), ctx.adminToken()))))
			.doesNotContain(assetId.toString());
		Map<String, Object> row = jdbcTemplate.queryForMap(
				"SELECT status, deleted_at, deleted_by, next_sync_at FROM video_asset WHERE id = ?", assetId);
		assertThat(row.get("status")).isEqualTo("DELETED");
		assertThat(row.get("deleted_by")).isNotNull();
		assertThat(row.get("next_sync_at")).isNull();
		assertThat(jdbcTemplate.queryForObject("SELECT metadata::text FROM audit_log WHERE action = ? "
				+ "AND target_id = ?", String.class, "media_asset.deleted", assetId)).contains("providerCopyKept");
		assertThat(PROVIDERS.requests().size()).isEqualTo(youtubeCallsBefore);
		assertThat(status(remove(ctx.host(), ctx.adminToken(), assetId))).isEqualTo(404);
	}

	@Test
	void mediaInUseOrStillUploadingCannotBeDeleted() {
		Ctx ctx = seedUploadTenant("lib-delete-guard", "UC-lib-delete-guard");
		UUID uploading = stageAndQueue(ctx, mp4(PART));
		MvcResult busy = remove(ctx.host(), ctx.teacherToken(), uploading);
		assertThat(status(busy)).isEqualTo(409);
		assertThat(errorCode(busy)).isEqualTo("MEDIA_UPLOAD_STATE_CONFLICT");

		worker.drain(5);
		attachToLesson(ctx, uploading);
		MvcResult attached = remove(ctx.host(), ctx.teacherToken(), uploading);
		assertThat(status(attached)).isEqualTo(409);
		assertThat(errorCode(attached)).isEqualTo("MEDIA_ATTACHED");
		assertThat(assetStatus(uploading)).isEqualTo("PROCESSING");
	}

	// ------------------------------------------------------------------
	// Roles and tenant isolation
	// ------------------------------------------------------------------

	@Test
	void onlyTheUploaderOrPermittedStaffCanEditOrDeleteAndOtherTenantsSeeNothing() {
		Ctx a = seedUploadTenant("lib-iso-a", "UC-lib-iso-a");
		Ctx b = seedUploadTenant("lib-iso-b", "UC-lib-iso-b");
		UUID assetId = assetIdOf(json(create(a.host(), a.teacherToken(), body("A's", PART))).path("data"));
		Map<String, Object> rename = Map.of("title", "Hijacked");

		assertThat(status(edit(a.host(), a.teacher2Token(), assetId, rename))).isEqualTo(404);
		assertThat(status(remove(a.host(), a.teacher2Token(), assetId))).isEqualTo(404);
		assertThat(status(edit(a.host(), a.studentToken(), assetId, rename))).isEqualTo(403);
		assertThat(status(remove(a.host(), a.studentToken(), assetId))).isEqualTo(403);
		// T1-T4: tenant B's admin cannot read, edit or delete A's media.
		assertThat(status(perform(authenticated(get(MEDIA + "/{id}", assetId), b.host(), b.adminToken()))))
			.isEqualTo(404);
		assertThat(status(edit(b.host(), b.adminToken(), assetId, rename))).isEqualTo(404);
		assertThat(status(remove(b.host(), b.adminToken(), assetId))).isEqualTo(404);
		assertThat(limits(b.host(), b.adminToken()).path("usedToday").asInt()).isZero();

		assertThat(jdbcTemplate.queryForObject("SELECT title FROM video_asset WHERE id = ?", String.class, assetId))
			.isEqualTo("A's");
		assertThat(status(edit(a.host(), a.adminToken(), assetId, Map.of("title", "Renamed by admin"))))
			.isEqualTo(200);
		// The upload still waits for its parts: delete asks to cancel it first.
		assertThat(status(remove(a.host(), a.adminToken(), assetId))).isEqualTo(409);
		assertThat(status(cancel(a.host(), a.adminToken(), assetId))).isEqualTo(200);
		assertThat(status(remove(a.host(), a.adminToken(), assetId))).isEqualTo(200);
	}

	@Test
	void aNewConnectionMustGrantTheEditScope() {
		Ctx ctx = seedUploadTenant("lib-scope", null);
		PROVIDERS.setGoogleScope(LEGACY_TENANT_CHANNEL_SCOPES);
		PROVIDERS.setYoutubeChannel("UC-lib-scope-" + UUID.randomUUID().toString().substring(0, 8), "Scope");
		String url = json(perform(authenticated(post(PROVIDERS_BASE + "/youtube/connect"), ctx.host(),
				ctx.adminToken()))).path("data").path("authorizationUrl").asString();
		assertThat(url).contains("youtube.force-ssl");

		String location = callback("youtube", "code-" + UUID.randomUUID(), null, stateOf(url)).getResponse()
			.getHeader(HttpHeaders.LOCATION);

		assertThat(location).contains("error=missing_scopes");
		assertThat(connectionCount(ctx.tenant().getId())).isZero();
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	private MvcResult edit(String host, String token, UUID assetId, Map<String, Object> body) {
		return perform(authenticated(patch(MEDIA + "/{id}", assetId).contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(body)), host, token));
	}

	private MvcResult remove(String host, String token, UUID assetId) {
		return perform(authenticated(delete(MEDIA + "/{id}", assetId), host, token));
	}

	private JsonNode limits(String host, String token) {
		return json(perform(authenticated(get(MEDIA + "/upload-limits"), host, token))).path("data");
	}

	private JsonNode detail(Ctx ctx, String token, UUID assetId) {
		return json(perform(authenticated(get(MEDIA + "/{id}", assetId), ctx.host(), token))).path("data");
	}

	private JsonNode listItem(Ctx ctx, String token, UUID assetId) {
		for (JsonNode item : json(perform(authenticated(get(MEDIA), ctx.host(), token))).path("data")
			.path("content")) {
			if (item.path("id").asString().equals(assetId.toString())) {
				return item;
			}
		}
		throw new AssertionError("asset not listed");
	}

	/** A lesson VIDEO material that uses {@code assetId} (Phase 6 will do this through an API). */
	private void attachToLesson(Ctx ctx, UUID assetId) {
		CourseResponse course = createCourseOrFail(ctx.host(), ctx.adminToken(),
				newCourseRequest("media-" + UUID.randomUUID().toString().substring(0, 8), ctx.teacherId(),
						CourseStatus.DRAFT));
		CourseModuleResponse module = createModuleOrFail(ctx.host(), ctx.adminToken(), course.id(), "Module", 1);
		CourseLessonResponse lesson = createLessonOrFail(ctx.host(), ctx.adminToken(), course.id(), module.id(),
				"Lesson", 1);
		Timestamp now = Timestamp.from(Instant.now());
		jdbcTemplate.update("INSERT INTO material (id, tenant_id, lesson_id, title, material_type, video_asset_id, "
				+ "sequence, visibility, uploaded_by, created_at, updated_at) "
				+ "VALUES (?, ?, ?, 'Lesson video', 'VIDEO', ?, 1, 'VISIBLE', ?, ?, ?)", UUID.randomUUID(),
				ctx.tenant().getId(), lesson.id(), assetId, ctx.teacherId(), now, now);
	}

	private int auditCount(String action, UUID targetId) {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM audit_log WHERE action = ? AND target_id = ?",
				Integer.class, action, targetId);
	}

}
