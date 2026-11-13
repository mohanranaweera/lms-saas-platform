package com.lms.localizationmanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * I18N-01 sections 4-5: fallback chain, draft isolation and preview, publish/rollback history,
 * optimistic concurrency, validation and the editor listing.
 */
class TranslationManagementIntegrationTest extends I18nTestSupport {

	private JsonNode entries(Fx fx, String query) {
		MvcResult result = call(fx.host(), fx.adminToken(), get("/api/v1/i18n/admin/entries?" + query));
		assertThat(status(result)).as(rawContent(result)).isEqualTo(200);
		return data(result);
	}

	private JsonNode releases(Fx fx, String locale) {
		MvcResult result = call(fx.host(), fx.adminToken(), get("/api/v1/i18n/admin/releases?locale=" + locale));
		assertThat(status(result)).as(rawContent(result)).isEqualTo(200);
		return data(result);
	}

	@Test
	void draftsArePrivateUntilPublishedThenServedWithFallbackToSource() {
		Fx fx = seed("i18n-flow");
		setLanguagesOk(fx, List.of("en-LK", "si-LK"), "en-LK", false);

		JsonNode draft = saveDraftOk(fx, "si-LK", SAVE_KEY, "සුරකින්න", null);
		assertThat(draft.path("action").asString()).isEqualTo("SET");
		assertThat(draft.path("version").asLong()).isZero();

		// Draft: not public, visible in the authorized preview (never cached publicly).
		JsonNode before = publicCatalogOk(fx.host(), "locale=si-LK");
		assertThat(before.path("messages").path(SAVE_KEY).asString()).isEqualTo("Save");
		MvcResult preview = call(fx.host(), fx.adminToken(),
				get("/api/v1/i18n/admin/preview-catalog?locale=si-LK&ns=common"));
		assertThat(status(preview)).as(rawContent(preview)).isEqualTo(200);
		assertThat(preview.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
		assertThat(data(preview).path("messages").path(SAVE_KEY).asString()).isEqualTo("සුරකින්න");
		assertThat(publicCatalogOk(fx.host(), "locale=si-LK").path("messages").path(SAVE_KEY).asString())
			.isEqualTo("Save");

		JsonNode release = publishOk(fx, "si-LK", 0);
		assertThat(release.path("versionNo").asInt()).isEqualTo(1);
		assertThat(release.path("entryCount").asInt()).isEqualTo(1);
		JsonNode after = publicCatalogOk(fx.host(), "locale=si-LK");
		assertThat(after.path("messages").path(SAVE_KEY).asString()).isEqualTo("සුරකින්න");
		assertThat(after.path("version").asString()).endsWith(".1");
		// Keys without an override keep the platform/English value (unit-tested in TranslationCatalogServiceTest).
		assertThat(after.path("messages").path("common.actions.cancel").asString())
			.isEqualTo(before.path("messages").path("common.actions.cancel").asString());
		// Drafts were consumed; English catalog untouched.
		assertThat(count("SELECT count(*) FROM translation_draft WHERE tenant_id = ?", fx.tenantId())).isZero();
		assertThat(publicCatalogOk(fx.host(), "locale=en-LK").path("messages").path(SAVE_KEY).asString())
			.isEqualTo("Save");
		assertThat(auditCount(fx.tenantId(), "translation.published")).isEqualTo(1);

		// Editor listing reflects the override.
		JsonNode list = entries(fx, "locale=si-LK&namespace=common&q=save");
		assertThat(list.path("page").path("content")).hasSize(1);
		JsonNode item = list.path("page").path("content").get(0);
		assertThat(item.path("key").asString()).isEqualTo(SAVE_KEY);
		assertThat(item.path("origin").asString()).isEqualTo("OVERRIDE");
		assertThat(item.path("publishedOverride").asString()).isEqualTo("සුරකින්න");
		assertThat(item.path("overrideNeedsReview").asBoolean()).isFalse();
		assertThat(item.path("draft").isNull()).isTrue();
		assertThat(list.path("counts").path("overridden").asLong()).isEqualTo(1);
		assertThat(list.path("counts").path("translated").asLong()).isEqualTo(1);
	}

	@Test
	void resetDraftDropsTheOverrideAndRollbackCreatesANewReleaseKeepingHistory() {
		Fx fx = seed("i18n-rollback");
		setLanguagesOk(fx, List.of("en-LK", "si-LK"), "en-LK", false);
		UUID v1 = UUID.fromString(publishOverride(fx, "si-LK", SAVE_KEY, "සුරකින්න", 0).path("id").asString());

		MvcResult reset = json(fx.host(), fx.adminToken(),
				post("/api/v1/i18n/admin/drafts/si-LK/{k}/reset", SAVE_KEY), Map.of());
		assertThat(status(reset)).as(rawContent(reset)).isEqualTo(200);
		assertThat(data(reset).path("action").asString()).isEqualTo("RESET");
		assertThat(auditCount(fx.tenantId(), "translation.override_reset")).isEqualTo(1);
		publishOk(fx, "si-LK", 1);
		assertThat(publicCatalogOk(fx.host(), "locale=si-LK").path("messages").path(SAVE_KEY).asString())
			.isEqualTo("Save");

		MvcResult rolledBack = rollback(fx, fx.adminToken(), v1, 2);
		assertThat(status(rolledBack)).as(rawContent(rolledBack)).isEqualTo(201);
		assertThat(data(rolledBack).path("versionNo").asInt()).isEqualTo(3);
		assertThat(data(rolledBack).path("rollbackOfReleaseId").asString()).isEqualTo(v1.toString());
		assertThat(publicCatalogOk(fx.host(), "locale=si-LK").path("messages").path(SAVE_KEY).asString())
			.isEqualTo("සුරකින්න");

		JsonNode history = releases(fx, "si-LK");
		assertThat(history.findValuesAsString("versionNo")).containsExactly("3", "2", "1");
		assertThat(history.get(0).path("current").asBoolean()).isTrue();
		assertThat(count("SELECT count(*) FROM translation_release WHERE tenant_id = ?", fx.tenantId())).isEqualTo(3);
		assertThat(auditCount(fx.tenantId(), "translation.rolled_back")).isEqualTo(1);

		// A stale rollback (someone published meanwhile) is refused.
		MvcResult stale = rollback(fx, fx.adminToken(), v1, 2);
		assertThat(status(stale)).isEqualTo(409);
		assertThat(errorCode(stale)).isEqualTo("TRANSLATION_RELEASE_STALE");
	}

	@Test
	void releaseRowsAreAppendOnlyInTheDatabase() {
		Fx fx = seed("i18n-append");
		publishOverride(fx, "si-LK", SAVE_KEY, "සුරකින්න", 0);
		org.assertj.core.api.Assertions
			.assertThatThrownBy(() -> jdbcTemplate.update("UPDATE translation_release_entry SET value = 'x' WHERE tenant_id = ?",
					fx.tenantId()))
			.hasMessageContaining("append-only");
		org.assertj.core.api.Assertions
			.assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM translation_release WHERE tenant_id = ?",
					fx.tenantId()))
			.hasMessageContaining("append-only");
	}

	@Test
	void concurrentEditsAndPublishesGet409() {
		Fx fx = seed("i18n-stale");
		JsonNode first = saveDraftOk(fx, "si-LK", SAVE_KEY, "එක", null);
		// Second "create" without the version: stale.
		MvcResult create = saveDraft(fx, fx.adminToken(), "si-LK", SAVE_KEY, "දෙක", null);
		assertThat(status(create)).isEqualTo(409);
		assertThat(errorCode(create)).isEqualTo("TRANSLATION_DRAFT_STALE");
		long v0 = first.path("version").asLong();
		JsonNode second = saveDraftOk(fx, "si-LK", SAVE_KEY, "දෙක", v0);
		assertThat(second.path("version").asLong()).isEqualTo(v0 + 1);
		MvcResult outdated = saveDraft(fx, fx.adminToken(), "si-LK", SAVE_KEY, "තුන", v0);
		assertThat(status(outdated)).isEqualTo(409);
		assertThat(errorCode(outdated)).isEqualTo("TRANSLATION_DRAFT_STALE");
		MvcResult discardStale = call(fx.host(), fx.adminToken(),
				delete("/api/v1/i18n/admin/drafts/si-LK/{k}?expectedVersion=" + v0, SAVE_KEY));
		assertThat(status(discardStale)).isEqualTo(409);

		// Publish with a stale release number.
		MvcResult stalePublish = publish(fx, fx.adminToken(), "si-LK", 3, null);
		assertThat(status(stalePublish)).isEqualTo(409);
		assertThat(errorCode(stalePublish)).isEqualTo("TRANSLATION_RELEASE_STALE");
		publishOk(fx, "si-LK", 0);
		MvcResult again = publish(fx, fx.adminToken(), "si-LK", 0, null);
		assertThat(status(again)).isEqualTo(409);
		assertThat(errorCode(again)).isEqualTo("TRANSLATION_RELEASE_STALE");
		MvcResult nothing = publish(fx, fx.adminToken(), "si-LK", 1, null);
		assertThat(status(nothing)).isEqualTo(409);
		assertThat(errorCode(nothing)).isEqualTo("NOTHING_TO_PUBLISH");

		// Discard with the right version removes only the draft.
		JsonNode draft = saveDraftOk(fx, "si-LK", CHANGED_KEY, "භාෂාව {language}", null);
		MvcResult discarded = call(fx.host(), fx.adminToken(), delete(
				"/api/v1/i18n/admin/drafts/si-LK/{k}?expectedVersion=" + draft.path("version").asLong(), CHANGED_KEY));
		assertThat(status(discarded)).isEqualTo(204);
		assertThat(count("SELECT count(*) FROM translation_draft WHERE tenant_id = ?", fx.tenantId())).isZero();
	}

	@Test
	void invalidMessagesUnknownKeysAndLocalesAreRejected() {
		Fx fx = seed("i18n-valid");
		MvcResult missing = saveDraft(fx, fx.adminToken(), "si-LK", CHANGED_KEY, "භාෂාව වෙනස් විය", null);
		assertThat(status(missing)).isEqualTo(422);
		assertThat(errorCode(missing)).isEqualTo("TRANSLATION_INVALID");
		assertThat(body(missing).path("error").path("details").path("problems").toString())
			.isEqualTo("[\"PLACEHOLDER_MISSING:language\"]");

		MvcResult plural = saveDraft(fx, fx.adminToken(), "ta-LK", PLURAL_KEY, "{count, plural, few {#} other {#}}",
				null);
		assertThat(status(plural)).isEqualTo(422);
		assertThat(body(plural).path("error").path("details").path("problems").toString())
			.contains("PLURAL_INVALID_CATEGORY:few");
		MvcResult syntax = saveDraft(fx, fx.adminToken(), "si-LK", SAVE_KEY, "Hello }", null);
		assertThat(status(syntax)).isEqualTo(422);
		MvcResult blank = saveDraft(fx, fx.adminToken(), "si-LK", SAVE_KEY, "   ", null);
		assertThat(status(blank)).isEqualTo(422);
		assertThat(body(blank).path("error").path("details").path("problems").toString()).isEqualTo("[\"EMPTY\"]");

		MvcResult okPlural = saveDraft(fx, fx.adminToken(), "ta-LK", PLURAL_KEY,
				"{count, plural, one {# நிமிடம்} other {# நிமிடங்கள்}}", null);
		assertThat(status(okPlural)).as(rawContent(okPlural)).isEqualTo(200);

		MvcResult unknownKey = saveDraft(fx, fx.adminToken(), "si-LK", "common.no.such.key", "x", null);
		assertThat(status(unknownKey)).isEqualTo(404);
		assertThat(errorCode(unknownKey)).isEqualTo("UNKNOWN_MESSAGE_KEY");
		MvcResult unknownLocale = saveDraft(fx, fx.adminToken(), "fr-FR", SAVE_KEY, "Enregistrer", null);
		assertThat(status(unknownLocale)).isEqualTo(400);
		assertThat(errorCode(unknownLocale)).isEqualTo("LOCALE_NOT_SUPPORTED");
		MvcResult badEntries = call(fx.host(), fx.adminToken(), get("/api/v1/i18n/admin/entries?locale=zz"));
		assertThat(status(badEntries)).isEqualTo(400);
		assertThat(count("SELECT count(*) FROM translation_draft WHERE tenant_id = ?", fx.tenantId())).isEqualTo(1);
	}

	@Test
	void entriesListingCountsFiltersAndPagination() {
		Fx fx = seed("i18n-entries");
		saveDraftOk(fx, "si-LK", SAVE_KEY, "සුරකින්න", null);
		JsonNode all = entries(fx, "locale=si-LK&namespace=common&size=3");
		long total = all.path("counts").path("total").asLong();
		assertThat(total).isGreaterThanOrEqualTo(8);
		assertThat(all.path("counts").path("drafts").asLong()).isEqualTo(1);
		assertThat(all.path("counts").path("missing").asLong() + all.path("counts").path("translated").asLong())
			.isEqualTo(total);
		assertThat(all.path("page").path("content")).hasSize(3);
		assertThat(all.path("page").path("totalElements").asLong()).isEqualTo(total);
		JsonNode drafts = entries(fx, "locale=si-LK&status=DRAFT");
		assertThat(drafts.path("page").path("content")).hasSize(1);
		JsonNode item = drafts.path("page").path("content").get(0);
		assertThat(item.path("draft").path("value").asString()).isEqualTo("සුරකින්න");
		assertThat(item.path("effectiveValue").asString()).isEqualTo("Save");
		assertThat(item.path("source").asString()).isEqualTo("Save");
		assertThat(item.path("context").asString()).isNotBlank();
		MvcResult badStatus = call(fx.host(), fx.adminToken(), get("/api/v1/i18n/admin/entries?locale=si-LK&status=NOPE"));
		assertThat(status(badStatus)).isEqualTo(400);
		// English (source locale): nothing is "missing".
		JsonNode english = entries(fx, "locale=en-LK&namespace=common");
		assertThat(english.path("counts").path("missing").asLong()).isZero();
	}

	@Test
	void aLocaleNotYetEnabledCanBePreparedButIsNotServed() {
		Fx fx = seed("i18n-prepare");
		publishOverride(fx, "ta-LK", SAVE_KEY, "சேமி", 0);
		assertThat(publicCatalogOk(fx.host(), "locale=ta-LK").path("locale").asString()).isEqualTo("en-LK");
		setLanguagesOk(fx, List.of("en-LK", "ta-LK"), "en-LK", null);
		assertThat(publicCatalogOk(fx.host(), "locale=ta-LK").path("messages").path(SAVE_KEY).asString())
			.isEqualTo("சேமி");
	}

}
