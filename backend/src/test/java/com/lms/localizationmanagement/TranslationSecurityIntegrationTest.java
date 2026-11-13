package com.lms.localizationmanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.lms.identityaccessservice.domain.Role;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

/**
 * I18N-01 sections 6-7: capability splits and cross-tenant isolation of drafts, releases,
 * public catalogs and the in-memory catalog cache.
 */
class TranslationSecurityIntegrationTest extends I18nTestSupport {

	private int entriesStatus(Fx fx, String token) {
		return status(call(fx.host(), token, get("/api/v1/i18n/admin/entries?locale=si-LK")));
	}

	@Test
	void teacherStudentAndUngrantedOperatorAreForbidden() {
		Fx fx = seed("i18n-sec-roles");
		String teacher = userToken(fx, "teacher@example.test", Role.TEACHER);
		String student = userToken(fx, "student@example.test", Role.STUDENT);
		String operator = userToken(fx, "operator@example.test", Role.OPERATOR);
		for (String token : List.of(teacher, student, operator)) {
			assertThat(entriesStatus(fx, token)).isEqualTo(403);
			assertThat(status(saveDraft(fx, token, "si-LK", SAVE_KEY, "x", null))).isEqualTo(403);
			assertThat(status(publish(fx, token, "si-LK", 0, null))).isEqualTo(403);
			assertThat(status(call(fx.host(), token, get("/api/v1/i18n/admin/releases?locale=si-LK")))).isEqualTo(403);
			assertThat(status(call(fx.host(), token, get("/api/v1/i18n/admin/preview-catalog?locale=si-LK"))))
				.isEqualTo(403);
			assertThat(status(call(fx.host(), token,
					get("/api/v1/i18n/admin/export?locale=si-LK&scope=OVERRIDES")))).isEqualTo(403);
		}
		assertThat(entriesStatus(fx, null)).isEqualTo(401);
		assertThat(count("SELECT count(*) FROM translation_draft WHERE tenant_id = ?", fx.tenantId())).isZero();
	}

	@Test
	void operatorCapabilitiesAreSplitViewEditPublish() {
		Fx fx = seed("i18n-sec-split");
		String operator = userToken(fx, "operator@example.test", Role.OPERATOR);
		setGrants(fx, "OPERATOR", true, "TRANSLATIONS_VIEW");
		assertThat(entriesStatus(fx, operator)).isEqualTo(200);
		assertThat(status(call(fx.host(), operator, get("/api/v1/i18n/admin/releases?locale=si-LK")))).isEqualTo(200);
		assertThat(status(saveDraft(fx, operator, "si-LK", SAVE_KEY, "x", null))).isEqualTo(403);
		assertThat(status(json(fx.host(), operator, post("/api/v1/i18n/admin/drafts/si-LK/{k}/reset", SAVE_KEY),
				Map.of()))).isEqualTo(403);
		MockMultipartFile file = new MockMultipartFile("file", "x.po", "text/plain",
				"msgid \"\"\nmsgstr \"\"\n".getBytes(StandardCharsets.UTF_8));
		assertThat(status(upload(fx.host(), operator, "/api/v1/i18n/admin/import?locale=si-LK&dryRun=true", file)))
			.isEqualTo(403);
		assertThat(status(publish(fx, operator, "si-LK", 0, null))).isEqualTo(403);

		setGrants(fx, "OPERATOR", true, "TRANSLATIONS_EDIT");
		MvcResult draft = saveDraft(fx, operator, "si-LK", SAVE_KEY, "සුරකින්න", null);
		assertThat(status(draft)).as(rawContent(draft)).isEqualTo(200);
		assertThat(status(publish(fx, operator, "si-LK", 0, null))).isEqualTo(403);

		setGrants(fx, "OPERATOR", true, "TRANSLATIONS_PUBLISH");
		MvcResult published = publish(fx, operator, "si-LK", 0, "operator release");
		assertThat(status(published)).as(rawContent(published)).isEqualTo(201);
		UUID releaseId = UUID.fromString(data(published).path("id").asString());
		setGrants(fx, "OPERATOR", false, "TRANSLATIONS_PUBLISH");
		assertThat(status(rollback(fx, operator, releaseId, 1))).isEqualTo(403);
	}

	@Test
	void tenantsNeverSeeOrTouchEachOthersDraftsReleasesOrCatalogs() {
		Fx a = seed("i18n-sec-a");
		Fx b = seed("i18n-sec-b");
		setLanguagesOk(a, List.of("en-LK", "si-LK"), "en-LK", false);
		setLanguagesOk(b, List.of("en-LK", "si-LK"), "en-LK", false);

		// Warm B's cache before A publishes.
		assertThat(publicCatalogOk(b.host(), "locale=si-LK").path("messages").path(SAVE_KEY).asString())
			.isEqualTo("Save");
		UUID releaseA = UUID.fromString(publishOverride(a, "si-LK", SAVE_KEY, "A-සුරකින්න", 0).path("id").asString());
		assertThat(publicCatalogOk(b.host(), "locale=si-LK").path("messages").path(SAVE_KEY).asString())
			.isEqualTo("Save");
		UUID releaseB = UUID.fromString(publishOverride(b, "si-LK", SAVE_KEY, "B-සුරකින්න", 0).path("id").asString());
		assertThat(publicCatalogOk(a.host(), "locale=si-LK").path("messages").path(SAVE_KEY).asString())
			.isEqualTo("A-සුරකින්න");
		assertThat(publicCatalogOk(b.host(), "locale=si-LK").path("messages").path(SAVE_KEY).asString())
			.isEqualTo("B-සුරකින්න");
		// A's cached catalog is replaced by its next release.
		saveDraftOk(a, "si-LK", SAVE_KEY, "A2-සුරකින්න", null);
		publishOk(a, "si-LK", 1);
		assertThat(publicCatalogOk(a.host(), "locale=si-LK").path("messages").path(SAVE_KEY).asString())
			.isEqualTo("A2-සුරකින්න");
		assertThat(publicCatalogOk(b.host(), "locale=si-LK").path("messages").path(SAVE_KEY).asString())
			.isEqualTo("B-සුරකින්න");

		// A's admin addressing B's release id: not found, nothing created.
		MvcResult crossRollback = rollback(a, a.adminToken(), releaseB, 2);
		assertThat(status(crossRollback)).isEqualTo(404);
		assertThat(count("SELECT count(*) FROM translation_release WHERE tenant_id = ?", a.tenantId())).isEqualTo(2);
		assertThat(count("SELECT count(*) FROM translation_release WHERE tenant_id = ?", b.tenantId())).isEqualTo(1);
		// A's token on B's host is rejected outright.
		assertThat(status(rollback(b, a.adminToken(), releaseB, 1))).isEqualTo(401);
		assertThat(entriesStatus(b, a.adminToken())).isEqualTo(401);

		// B's draft is invisible to A: A's listing/preview/export only show A's data.
		saveDraftOk(b, "si-LK", CHANGED_KEY, "B {language}", null);
		MvcResult listA = call(a.host(), a.adminToken(), get("/api/v1/i18n/admin/entries?locale=si-LK&status=DRAFT"));
		assertThat(data(listA).path("page").path("content")).isEmpty();
		MvcResult previewA = call(a.host(), a.adminToken(), get("/api/v1/i18n/admin/preview-catalog?locale=si-LK"));
		assertThat(rawContent(previewA)).doesNotContain("B {language}").doesNotContain("B-සුරකින්න");
		MvcResult releasesA = call(a.host(), a.adminToken(), get("/api/v1/i18n/admin/releases?locale=si-LK"));
		assertThat(rawContent(releasesA)).doesNotContain(releaseB.toString()).contains(releaseA.toString());
		MvcResult exportA = call(a.host(), a.adminToken(),
				get("/api/v1/i18n/admin/export?locale=si-LK&scope=OVERRIDES"));
		assertThat(new String(exportA.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8))
			.doesNotContain("B-සුරකින්න");
		// A discarding/publishing never touches B's draft.
		MvcResult discard = call(a.host(), a.adminToken(),
				delete("/api/v1/i18n/admin/drafts/si-LK/{k}?expectedVersion=0", CHANGED_KEY));
		assertThat(status(discard)).isEqualTo(409);
		assertThat(status(publish(a, a.adminToken(), "si-LK", 2, null))).isEqualTo(409);
		assertThat(count("SELECT count(*) FROM translation_draft WHERE tenant_id = ?", b.tenantId())).isEqualTo(1);
	}

	@Test
	void auditEntriesStayInTheActingTenant() {
		Fx a = seed("i18n-sec-audit-a");
		Fx b = seed("i18n-sec-audit-b");
		publishOverride(a, "si-LK", SAVE_KEY, "සුරකින්න", 0);
		assertThat(auditCount(a.tenantId(), "translation.published")).isEqualTo(1);
		assertThat(auditCount(b.tenantId(), "translation.published")).isZero();
		assertThat(count("SELECT count(*) FROM audit_log WHERE tenant_id = ? AND action = 'translation.published' "
				+ "AND actor_id = ?", a.tenantId(), a.admin().getId())).isEqualTo(1);
	}

}
