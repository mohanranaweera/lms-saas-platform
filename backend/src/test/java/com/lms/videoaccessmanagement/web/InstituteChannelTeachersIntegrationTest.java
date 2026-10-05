package com.lms.videoaccessmanagement.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * VIDEO Phase 5b - the institute YouTube channel's name / description and
 * allowed teachers (empty = all teachers), their enforcement on uploads, and
 * tenant isolation of the settings.
 */
class InstituteChannelTeachersIntegrationTest extends MediaUploadTestSupport {

	private static final String SETTINGS = PROVIDERS_BASE + "/youtube/settings";

	@Test
	void byDefaultAllTeachersMayUploadAndTheSettingsAreEmpty() {
		Ctx ctx = seedChannelTenant("ct-default");

		JsonNode settings = json(getSettings(ctx.host(), ctx.adminToken())).path("data");
		assertThat(settings.path("allTeachersAllowed").asBoolean()).isTrue();
		assertThat(settings.path("allowedTeachers")).isEmpty();
		assertThat(settings.path("displayName").isNull()).isTrue();
		// The choices are the institute's teachers by user id (what the list accepts).
		assertThat(settings.path("availableTeachers").findValuesAsString("userId"))
			.containsExactlyInAnyOrder(ctx.teacherId().toString(), userId(ctx, "teacher2@example.test").toString());
		assertThat(status(create(ctx.host(), ctx.teacher2Token(), body("Any teacher", PART)))).isEqualTo(201);
	}

	@Test
	void selectedTeachersOnlyMayUploadAndOthersKeepTheirExistingMedia() {
		Ctx ctx = seedChannelTenant("ct-selected");
		UUID earlierAsset = assetIdOf(json(create(ctx.host(), ctx.teacher2Token(), body("Before", PART))).path("data"));

		MvcResult saved = putSettings(ctx.host(), ctx.adminToken(),
				Map.of("displayName", "Institute Channel", "description", "Lesson videos",
						"allowedTeacherIds", List.of(ctx.teacherId().toString())));

		assertThat(status(saved)).isEqualTo(200);
		JsonNode data = json(saved).path("data");
		assertThat(data.path("displayName").asString()).isEqualTo("Institute Channel");
		assertThat(data.path("allTeachersAllowed").asBoolean()).isFalse();
		assertThat(data.path("allowedTeachers").path(0).path("name").asString()).isEqualTo("Teacher One");
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM audit_log WHERE action = ? AND tenant_id = ?",
				Integer.class, "video_provider.settings_updated", ctx.tenant().getId())).isEqualTo(1);

		assertThat(status(create(ctx.host(), ctx.teacherToken(), body("Allowed", PART)))).isEqualTo(201);
		MvcResult refused = create(ctx.host(), ctx.teacher2Token(), body("Not allowed", PART));
		assertThat(status(refused)).isEqualTo(403);
		assertThat(errorCode(refused)).isEqualTo("VIDEO_UPLOAD_NOT_ALLOWED");
		JsonNode limits = json(perform(authenticated(get(MEDIA + "/upload-limits"), ctx.host(), ctx.teacher2Token())))
			.path("data");
		assertThat(limits.path("canUpload").asBoolean()).isFalse();
		assertThat(limits.path("notAllowedReason").asString()).contains("hasn't given you access");
		// Staff are not affected by the teacher list.
		assertThat(status(create(ctx.host(), ctx.adminToken(), body("Admin upload", PART)))).isEqualTo(201);
		// A de-listed teacher still manages what they uploaded before.
		assertThat(status(cancel(ctx.host(), ctx.teacher2Token(), earlierAsset))).isEqualTo(200);

		// Back to "all teachers".
		assertThat(status(putSettings(ctx.host(), ctx.adminToken(), Map.of("allowedTeacherIds", List.of()))))
			.isEqualTo(200);
		assertThat(status(create(ctx.host(), ctx.teacher2Token(), body("Allowed again", PART)))).isEqualTo(201);
	}

	@Test
	void retryIsAlsoLimitedToAllowedTeachers() {
		Ctx ctx = seedChannelTenant("ct-retry");
		UUID assetId = stageAndQueue(ctx, mp4(PART));
		YOUTUBE.failChunk(1, 400, "invalidTitle");
		worker.drain(5);
		assertThat(jobStatus(assetId)).isEqualTo("FAILED");
		putSettings(ctx.host(), ctx.adminToken(),
				Map.of("allowedTeacherIds", List.of(userId(ctx, "teacher2@example.test").toString())));

		MvcResult refused = perform(authenticated(post(MEDIA + "/{id}/upload/retry", assetId), ctx.host(),
				ctx.teacherToken()));

		assertThat(status(refused)).isEqualTo(403);
		assertThat(errorCode(refused)).isEqualTo("VIDEO_UPLOAD_NOT_ALLOWED");
	}

	@Test
	void settingsAreValidatedAndOnlyForTheTenantAdmin() {
		Ctx ctx = seedChannelTenant("ct-valid");

		Map<String, Object> bad = new HashMap<>();
		bad.put("displayName", "<script>");
		bad.put("allowedTeacherIds", List.of(UUID.randomUUID().toString()));
		MvcResult invalid = putSettings(ctx.host(), ctx.adminToken(), bad);
		assertThat(status(invalid)).isEqualTo(400);
		assertThat(rawContent(invalid)).contains("displayName").contains("allowedTeacherIds");
		// A student is not a teacher.
		assertThat(status(putSettings(ctx.host(), ctx.adminToken(),
				Map.of("allowedTeacherIds", List.of(userId(ctx, "student@example.test").toString())))))
			.isEqualTo(400);

		assertThat(status(getSettings(ctx.host(), ctx.teacherToken()))).isEqualTo(403);
		assertThat(status(putSettings(ctx.host(), ctx.teacherToken(), Map.of("allowedTeacherIds", List.of()))))
			.isEqualTo(403);
	}

	@Tag("cross-tenant")
	@Test
	void anotherTenantCannotReadOrUseTheList() {
		Ctx a = seedChannelTenant("ct-iso-a");
		Ctx b = seedChannelTenant("ct-iso-b");
		putSettings(a.host(), a.adminToken(),
				Map.of("displayName", "A channel", "allowedTeacherIds", List.of(a.teacherId().toString())));

		JsonNode bSettings = json(getSettings(b.host(), b.adminToken())).path("data");
		assertThat(bSettings.path("allTeachersAllowed").asBoolean()).isTrue();
		assertThat(bSettings.path("displayName").isNull()).isTrue();
		assertThat(bSettings.path("availableTeachers").findValuesAsString("userId"))
			.doesNotContain(a.teacherId().toString());
		// A teacher of tenant A cannot be put on tenant B's list.
		assertThat(status(putSettings(b.host(), b.adminToken(),
				Map.of("allowedTeacherIds", List.of(a.teacherId().toString()))))).isEqualTo(400);
		// Tenant A's list does not restrict tenant B's teachers.
		assertThat(status(create(b.host(), b.teacher2Token(), body("B upload", PART)))).isEqualTo(201);
	}

	@Test
	void v73RetiredTeacherYouTubeConnectionsAndKeepsTheListInsideTheTenant() {
		Integer live = jdbcTemplate.queryForObject("SELECT count(*) FROM media_provider_connection "
				+ "WHERE provider = 'YOUTUBE' AND scope = 'TEACHER' AND status <> 'DISCONNECTED'", Integer.class);
		assertThat(live).isZero();
		assertThat(jdbcTemplate.queryForList("SELECT conname FROM pg_constraint "
				+ "WHERE conrelid = 'tenant_video_upload_teacher'::regclass", String.class))
			.contains("fk_tenant_video_upload_teacher_user", "uq_tenant_video_upload_teacher");
	}

	// ------------------------------------------------------------------

	private Ctx seedChannelTenant(String prefix) {
		Ctx ctx = seedUploadTenant(prefix, "UC-" + prefix);
		seedTeacherProfile(ctx.tenant().getId(), ctx.teacherId(), "Teacher One");
		seedTeacherProfile(ctx.tenant().getId(), userId(ctx, "teacher2@example.test"), "Teacher Two");
		return ctx;
	}

	private UUID userId(Ctx ctx, String email) {
		return jdbcTemplate.queryForObject("SELECT id FROM tenant_user WHERE tenant_id = ? AND email = ?", UUID.class,
				ctx.tenant().getId(), email);
	}

	private MvcResult getSettings(String host, String token) {
		return perform(authenticated(get(SETTINGS), host, token));
	}

	private MvcResult putSettings(String host, String token, Map<String, Object> body) {
		return perform(authenticated(put(SETTINGS).contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(body)), host, token));
	}

}
