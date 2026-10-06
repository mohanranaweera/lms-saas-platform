package com.lms.videoaccessmanagement.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
 * VIDEO Phase 5d (ADR-018 Amendment 5 item 2) - named YouTube integrations
 * (cards) and the Media Library tabs: several cards per institute, per-card
 * teacher lists, uploads to the chosen card, deleting archives the card's
 * videos, the per-channel platform switch-off, and tenant isolation of every
 * card / tab id.
 */
class VideoIntegrationsIntegrationTest extends MediaUploadTestSupport {

	private static final String INTEGRATIONS = PROVIDERS_BASE + "/youtube/integrations";

	@Test
	void cardsAreCreatedEditedAndDeletedWithValidationTheNameRuleTheLimitAndAudit() {
		Ctx ctx = seedUploadTenant("vi-crud", null);
		UUID teacher2 = userId(ctx, "teacher2@example.test");
		seedTeacherProfile(ctx.tenant().getId(), ctx.teacherId(), "Teacher One");
		seedTeacherProfile(ctx.tenant().getId(), teacher2, "Teacher Two");

		UUID science = createIntegration(ctx, "Science", List.of(ctx.teacherId()));
		JsonNode cards = json(perform(authenticated(get(PROVIDERS_BASE), ctx.host(), ctx.adminToken()))).path("data");
		assertThat(cards).hasSize(1);
		assertThat(cards.path(0).path("name").asString()).isEqualTo("Science");
		assertThat(cards.path(0).path("status").asString()).isEqualTo("NOT_CONNECTED");
		assertThat(cards.path(0).path("allTeachersAllowed").asBoolean()).isFalse();
		assertThat(cards.path(0).path("allowedTeachers").path(0).path("name").asString()).isEqualTo("Teacher One");

		// Names are unique per institute (case-insensitive); values are validated.
		assertThat(errorCode(postIntegration(ctx, Map.of("name", "SCIENCE")))).isEqualTo("VIDEO_INTEGRATION_NAME_TAKEN");
		MvcResult invalid = postIntegration(ctx, Map.of("name", " ", "allowedTeacherIds",
				List.of(userId(ctx, "student@example.test").toString())));
		assertThat(status(invalid)).isEqualTo(400);
		assertThat(rawContent(invalid)).contains("name").contains("allowedTeacherIds");

		MvcResult renamed = perform(authenticated(put(INTEGRATIONS + "/{id}", science)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("name", "Physics", "description", "Grade 12",
					"allowedTeacherIds", List.of()))), ctx.host(), ctx.adminToken()));
		assertThat(status(renamed)).isEqualTo(200);
		assertThat(json(renamed).path("data").path("allTeachersAllowed").asBoolean()).isTrue();

		// At most 10 live cards.
		for (int i = 2; i <= 10; i++) {
			createIntegration(ctx, "Channel " + i, List.of());
		}
		assertThat(errorCode(postIntegration(ctx, Map.of("name", "Eleventh")))).isEqualTo("VIDEO_INTEGRATION_LIMIT");
		assertThat(status(perform(authenticated(delete(INTEGRATIONS + "/{id}", science), ctx.host(),
				ctx.adminToken())))).isEqualTo(200);
		assertThat(status(postIntegration(ctx, Map.of("name", "Physics")))).isEqualTo(201);

		assertThat(auditCount(ctx, "video_integration.created")).isEqualTo(11);
		assertThat(auditCount(ctx, "video_integration.updated")).isEqualTo(1);
		assertThat(auditCount(ctx, "video_integration.deleted")).isEqualTo(1);
		// Teachers can't manage cards.
		assertThat(status(perform(authenticated(get(PROVIDERS_BASE), ctx.host(), ctx.teacherToken())))).isEqualTo(403);
		assertThat(status(postIntegration(ctx.host(), ctx.teacherToken(), Map.of("name", "Mine")))).isEqualTo(403);
	}

	@Test
	void twoChannelsInOneInstituteAndAnUploadGoesToTheChosenCard() {
		Ctx ctx = seedUploadTenant("vi-two", "UC-vi-two-a");
		UUID main = mainIntegration(ctx);
		UUID second = createIntegration(ctx, "Second", List.of());
		UUID secondConnection = connectChannel(ctx, second, "UC-vi-two-b-" + UUID.randomUUID().toString().substring(0, 8));

		JsonNode cards = json(perform(authenticated(get(PROVIDERS_BASE), ctx.host(), ctx.adminToken()))).path("data");
		assertThat(cards.path(0).path("status").asString()).isEqualTo("CONNECTED");
		assertThat(cards.path(1).path("status").asString()).isEqualTo("CONNECTED");

		Map<String, Object> request = new HashMap<>(body("To second", PART));
		request.put("integrationId", second.toString());
		MvcResult created = create(ctx.host(), ctx.teacherToken(), request);
		assertThat(status(created)).isEqualTo(201);
		UUID assetId = assetIdOf(json(created).path("data"));
		assertThat(jdbcTemplate.queryForObject("SELECT integration_id FROM video_asset WHERE id = ?", UUID.class,
				assetId)).isEqualTo(second);
		assertThat(latestJob(assetId).get("provider_connection_id")).isEqualTo(secondConnection);

		assertThat(listIds(ctx, ctx.adminToken(), "integrationId=" + second)).containsExactly(assetId.toString());
		assertThat(listIds(ctx, ctx.adminToken(), "integrationId=" + main)).isEmpty();
		JsonNode tabs = json(perform(authenticated(get(MEDIA + "/channels"), ctx.host(), ctx.adminToken())))
			.path("data");
		assertThat(tabs.path("integrations").findValuesAsString("name")).containsExactly("Main channel", "Second");
		assertThat(tabs.path("hasArchived").asBoolean()).isFalse();
		JsonNode limits = json(perform(authenticated(get(MEDIA + "/upload-limits").param("integrationId",
				second.toString()), ctx.host(), ctx.teacherToken()))).path("data");
		assertThat(limits.path("channel").asString()).isEqualTo("CONNECTED");
		// An upload must name a card.
		Map<String, Object> noCard = new HashMap<>(body("No card", PART));
		noCard.put("integrationId", null);
		assertThat(status(perform(authenticated(post(MEDIA).contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(noCard)), ctx.host(), ctx.teacherToken())))).isEqualTo(400);
	}

	@Test
	void eachCardHasItsOwnTeacherListAndTeachersSeeOnlyTheirTabs() {
		Ctx ctx = seedUploadTenant("vi-teachers", "UC-vi-teachers");
		UUID teacher2 = userId(ctx, "teacher2@example.test");
		seedTeacherProfile(ctx.tenant().getId(), ctx.teacherId(), "Teacher One");
		seedTeacherProfile(ctx.tenant().getId(), teacher2, "Teacher Two");
		UUID main = mainIntegration(ctx);
		UUID restricted = createIntegration(ctx, "Teacher One only", List.of(ctx.teacherId()));
		connectChannel(ctx, restricted, "UC-vi-r-" + UUID.randomUUID().toString().substring(0, 8));

		Map<String, Object> toRestricted = new HashMap<>(body("Restricted", PART));
		toRestricted.put("integrationId", restricted.toString());
		MvcResult refused = create(ctx.host(), ctx.teacher2Token(), toRestricted);
		assertThat(status(refused)).isEqualTo(403);
		assertThat(errorCode(refused)).isEqualTo("VIDEO_UPLOAD_NOT_ALLOWED");
		assertThat(status(create(ctx.host(), ctx.teacherToken(), toRestricted))).isEqualTo(201);
		assertThat(status(create(ctx.host(), ctx.teacher2Token(), body("Main is open", PART)))).isEqualTo(201);

		JsonNode teacher2Tabs = json(perform(authenticated(get(MEDIA + "/channels"), ctx.host(), ctx.teacher2Token())))
			.path("data")
			.path("integrations");
		assertThat(teacher2Tabs.findValuesAsString("integrationId")).containsExactly(main.toString());
		assertThat(teacher2Tabs.path(0).path("canUpload").asBoolean()).isTrue();
		JsonNode teacher1Tabs = json(perform(authenticated(get(MEDIA + "/channels"), ctx.host(), ctx.teacherToken())))
			.path("data")
			.path("integrations");
		assertThat(teacher1Tabs.findValuesAsString("integrationId")).containsExactly(main.toString(),
				restricted.toString());
	}

	@Test
	void deletingACardDisconnectsItAndArchivesItsVideos() {
		Ctx ctx = seedUploadTenant("vi-delete", "UC-vi-delete");
		UUID card = createIntegration(ctx, "Old channel", List.of());
		UUID connection = connectChannel(ctx, card, "UC-vi-old-" + UUID.randomUUID().toString().substring(0, 8));
		Map<String, Object> request = new HashMap<>(body("Kept video", PART));
		request.put("integrationId", card.toString());
		UUID assetId = assetIdOf(json(create(ctx.host(), ctx.teacherToken(), request)).path("data"));
		PROVIDERS.reset();

		assertThat(status(perform(authenticated(delete(INTEGRATIONS + "/{id}", card), ctx.host(), ctx.adminToken()))))
			.isEqualTo(200);

		assertThat(jdbcTemplate.queryForObject("SELECT status FROM media_provider_connection WHERE id = ?", String.class,
				connection)).isEqualTo("DISCONNECTED");
		assertThat(PROVIDERS.requests("POST", "/revoke")).isNotEmpty();
		assertThat(json(perform(authenticated(get(PROVIDERS_BASE), ctx.host(), ctx.adminToken()))).path("data")
			.findValuesAsString("integrationId")).doesNotContain(card.toString());
		JsonNode tabs = json(perform(authenticated(get(MEDIA + "/channels"), ctx.host(), ctx.adminToken())))
			.path("data");
		assertThat(tabs.path("integrations").findValuesAsString("integrationId")).doesNotContain(card.toString());
		assertThat(tabs.path("hasArchived").asBoolean()).isTrue();
		assertThat(listIds(ctx, ctx.adminToken(), "archived=true")).containsExactly(assetId.toString());
		// A deleted card takes no uploads and can't be connected again.
		assertThat(status(create(ctx.host(), ctx.teacherToken(), request))).isEqualTo(404);
		assertThat(status(beginConnect(ctx.host(), ctx.adminToken(), card))).isEqualTo(404);
	}

	@Test
	void thePlatformSwitchOffIsPerChannel() {
		Ctx ctx = seedUploadTenant("vi-switch", "UC-vi-switch");
		UUID main = mainIntegration(ctx);
		UUID mainConnection = currentConnectionOf(main);
		UUID other = createIntegration(ctx, "Other", List.of());
		connectChannel(ctx, other, "UC-vi-sw-" + UUID.randomUUID().toString().substring(0, 8));
		String email = "vi-platform-" + UUID.randomUUID().toString().substring(0, 8) + "@platform.test";
		seedPlatformAdmin(email, RAW_PASSWORD);
		String platformToken = platformAdminLogin(null, email, RAW_PASSWORD).getBody().data().accessToken();

		assertThat(status(platformPost("/api/v1/platform-admin/tenants/" + ctx.tenant().getId() + "/video-providers/"
				+ mainConnection + "/disable", null, platformToken, Map.of("reason", "Abuse report")))).isEqualTo(200);

		assertThat(errorCode(create(ctx.host(), ctx.teacherToken(), body("Main is off", PART))))
			.isEqualTo("VIDEO_PROVIDER_DISABLED_BY_PLATFORM");
		Map<String, Object> toOther = new HashMap<>(body("Other still works", PART));
		toOther.put("integrationId", other.toString());
		assertThat(status(create(ctx.host(), ctx.teacherToken(), toOther))).isEqualTo(201);
		JsonNode platformList = json(perform(authenticated(get("/api/v1/platform-admin/tenants/"
				+ ctx.tenant().getId() + "/video-providers"), null, platformToken))).path("data");
		assertThat(platformList.findValuesAsString("integrationName")).contains("Main channel", "Other");
	}

	@Tag("cross-tenant")
	@Test
	void anotherInstituteCannotReachACardOrItsTab() {
		Ctx a = seedUploadTenant("vi-iso-a", "UC-vi-iso-a");
		Ctx b = seedUploadTenant("vi-iso-b", "UC-vi-iso-b");
		UUID aCard = mainIntegration(a);

		assertThat(status(perform(authenticated(put(INTEGRATIONS + "/{id}", aCard)
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("name", "Taken over"))), b.host(), b.adminToken()))))
			.isEqualTo(404);
		assertThat(status(perform(authenticated(delete(INTEGRATIONS + "/{id}", aCard), b.host(), b.adminToken()))))
			.isEqualTo(404);
		assertThat(status(beginConnect(b.host(), b.adminToken(), aCard))).isEqualTo(404);
		assertThat(status(perform(authenticated(get(MEDIA).param("integrationId", aCard.toString()), b.host(),
				b.adminToken())))).isEqualTo(404);
		assertThat(status(perform(authenticated(get(MEDIA + "/upload-limits").param("integrationId", aCard.toString()),
				b.host(), b.teacherToken())))).isEqualTo(404);
		Map<String, Object> intoA = new HashMap<>(body("Into A", PART));
		intoA.put("integrationId", aCard.toString());
		assertThat(status(create(b.host(), b.teacherToken(), intoA))).isEqualTo(404);
		assertThat(json(perform(authenticated(get(PROVIDERS_BASE), b.host(), b.adminToken()))).path("data")
			.findValuesAsString("integrationId")).doesNotContain(aCard.toString());
		assertThat(json(perform(authenticated(get(MEDIA + "/channels"), b.host(), b.adminToken()))).path("data")
			.path("integrations")
			.findValuesAsString("integrationId")).doesNotContain(aCard.toString());
		assertThat(jdbcTemplate.queryForObject("SELECT name FROM tenant_media_integration WHERE id = ?", String.class,
				aCard)).isEqualTo("Main channel");
	}

	@Test
	void theSchemaKeepsOneLiveConnectionPerCardAndYouTubeVideosOnACard() {
		assertThat(jdbcTemplate.queryForList("SELECT conname FROM pg_constraint WHERE conrelid = "
				+ "'video_asset'::regclass", String.class)).contains("ck_video_asset_integration", "fk_video_asset_integration");
		assertThat(jdbcTemplate.queryForList("SELECT indexname FROM pg_indexes WHERE tablename = "
				+ "'media_provider_connection'", String.class)).contains("uq_media_provider_connection_integration_current");
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM information_schema.tables "
				+ "WHERE table_name = 'tenant_video_upload_teacher'", Integer.class)).isZero();
	}

	// ------------------------------------------------------------------

	private UUID userId(Ctx ctx, String email) {
		return jdbcTemplate.queryForObject("SELECT id FROM tenant_user WHERE tenant_id = ? AND email = ?", UUID.class,
				ctx.tenant().getId(), email);
	}

	private UUID currentConnectionOf(UUID integrationId) {
		return jdbcTemplate.queryForObject("SELECT id FROM media_provider_connection WHERE integration_id = ? "
				+ "AND status <> 'DISCONNECTED'", UUID.class, integrationId);
	}

	private MvcResult postIntegration(Ctx ctx, Map<String, Object> body) {
		return postIntegration(ctx.host(), ctx.adminToken(), body);
	}

	private MvcResult postIntegration(String host, String token, Map<String, Object> body) {
		return perform(authenticated(post(INTEGRATIONS).contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(body)), host, token));
	}

	private List<String> listIds(Ctx ctx, String token, String query) {
		return json(perform(authenticated(get(MEDIA + "?" + query), ctx.host(), token))).path("data")
			.path("content")
			.findValuesAsString("id");
	}

	private int auditCount(Ctx ctx, String action) {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = ?",
				Integer.class, ctx.tenant().getId(), action);
	}

}
