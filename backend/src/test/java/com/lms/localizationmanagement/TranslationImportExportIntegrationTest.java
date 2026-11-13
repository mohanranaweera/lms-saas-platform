package com.lms.localizationmanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.lms.localizationmanagement.po.PoFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/** I18N-01 section 5: PO import (drafts only, dry run first, all-or-nothing) and PO/MO export. */
class TranslationImportExportIntegrationTest extends I18nTestSupport {

	private MvcResult importPo(Fx fx, String token, String locale, boolean dryRun, byte[] content) {
		MockMultipartFile file = new MockMultipartFile("file", "si-LK.po", "text/x-gettext-translation", content);
		return upload(fx.host(), token, "/api/v1/i18n/admin/import?locale=" + locale + "&dryRun=" + dryRun, file);
	}

	private static byte[] po(String language, String... entries) {
		StringBuilder out = new StringBuilder("msgid \"\"\nmsgstr \"");
		if (language != null) {
			out.append("Language: ").append(language).append("\\n");
		}
		out.append("Content-Type: text/plain; charset=UTF-8\\n\"\n\n");
		for (String entry : entries) {
			out.append(entry).append("\n\n");
		}
		return out.toString().getBytes(StandardCharsets.UTF_8);
	}

	private static String entry(String key, String msgid, String msgstr) {
		return "msgctxt \"" + key + "\"\nmsgid \"" + msgid + "\"\nmsgstr \"" + msgstr + "\"";
	}

	private long drafts(Fx fx) {
		return count("SELECT count(*) FROM translation_draft WHERE tenant_id = ?", fx.tenantId());
	}

	@Test
	void dryRunReportsWithoutWritingThenImportWritesDraftsOnly() {
		Fx fx = seed("i18n-import");
		byte[] file = po("si-LK", entry(SAVE_KEY, "Save", "සුරකින්න"),
				entry(CHANGED_KEY, "Language changed to {language}", "භාෂාව {language} ලෙස වෙනස් විය"),
				entry("common.removed.key", "Gone", "නැත"), entry("common.actions.cancel", "Cancel", ""),
				"#, fuzzy\n" + entry("common.actions.retry", "Try again", "නැවත"),
				"#~ msgctxt \"common.old\"\n#~ msgid \"Old\"\n#~ msgstr \"පැරණි\"",
				entry("common.state.error", "Something broke.", "යමක් වැරදුණි."));

		MvcResult dry = importPo(fx, fx.adminToken(), "si-LK", true, file);
		assertThat(status(dry)).as(rawContent(dry)).isEqualTo(200);
		JsonNode report = data(dry);
		assertThat(report.path("dryRun").asBoolean()).isTrue();
		assertThat(report.path("valid").asBoolean()).isTrue();
		assertThat(report.path("created").asInt()).isEqualTo(3);
		assertThat(report.path("unknownKeys").toString()).isEqualTo("[\"common.removed.key\"]");
		assertThat(report.path("skippedEmpty").asInt()).isEqualTo(1);
		assertThat(report.path("skippedFuzzy").asInt()).isEqualTo(1);
		assertThat(report.path("skippedObsolete").asInt()).isEqualTo(1);
		assertThat(report.path("sourceChanged").toString()).isEqualTo("[\"common.state.error\"]");
		assertThat(drafts(fx)).isZero();

		MvcResult real = importPo(fx, fx.adminToken(), "si-LK", false, file);
		assertThat(status(real)).as(rawContent(real)).isEqualTo(200);
		assertThat(drafts(fx)).isEqualTo(3);
		assertThat(auditCount(fx.tenantId(), "translation.imported")).isEqualTo(1);
		// Imports never publish.
		assertThat(count("SELECT count(*) FROM translation_release WHERE tenant_id = ?", fx.tenantId())).isZero();
		// Re-importing the same file changes nothing.
		JsonNode again = data(importPo(fx, fx.adminToken(), "si-LK", true, file));
		assertThat(again.path("unchanged").asInt()).isEqualTo(3);
		assertThat(again.path("created").asInt() + again.path("updated").asInt()).isZero();
	}

	@Test
	void anyInvalidMessageRejectsTheWholeImportWithZeroWrites() {
		Fx fx = seed("i18n-import-bad");
		byte[] file = po("si-LK", entry(SAVE_KEY, "Save", "සුරකින්න"),
				entry(CHANGED_KEY, "Language changed to {language}", "භාෂාව වෙනස් විය"));
		JsonNode dry = data(importPo(fx, fx.adminToken(), "si-LK", true, file));
		assertThat(dry.path("valid").asBoolean()).isFalse();
		assertThat(dry.path("invalid").get(0).path("key").asString()).isEqualTo(CHANGED_KEY);
		assertThat(dry.path("invalid").get(0).path("problems").toString()).isEqualTo("[\"PLACEHOLDER_MISSING:language\"]");

		MvcResult real = importPo(fx, fx.adminToken(), "si-LK", false, file);
		assertThat(status(real)).isEqualTo(422);
		assertThat(errorCode(real)).isEqualTo("PO_IMPORT_INVALID");
		assertThat(drafts(fx)).isZero();

		MvcResult plural = importPo(fx, fx.adminToken(), "si-LK", false,
				po("si-LK", "msgctxt \"" + SAVE_KEY + "\"\nmsgid \"Save\"\nmsgid_plural \"Saves\"\nmsgstr[0] \"a\"\nmsgstr[1] \"b\""));
		assertThat(status(plural)).isEqualTo(422);
		assertThat(rawContent(plural)).contains("MSGID_PLURAL");
		assertThat(drafts(fx)).isZero();
	}

	@Test
	void fileLimitsEncodingAndLanguageHeader() {
		Fx fx = seed("i18n-import-limits");
		byte[] big = new byte[1024 * 1024 + 10];
		java.util.Arrays.fill(big, (byte) 'a');
		MvcResult tooBig = importPo(fx, fx.adminToken(), "si-LK", true, big);
		assertThat(status(tooBig)).isEqualTo(413);
		assertThat(errorCode(tooBig)).isEqualTo("PAYLOAD_TOO_LARGE");

		MvcResult latin1 = importPo(fx, fx.adminToken(), "si-LK", true,
				new byte[] { 'm', 's', 'g', 'i', 'd', ' ', '"', (byte) 0xE9, '"' });
		assertThat(status(latin1)).isEqualTo(422);
		assertThat(errorCode(latin1)).isEqualTo("PO_IMPORT_INVALID");

		MvcResult wrongLanguage = importPo(fx, fx.adminToken(), "si-LK", true,
				po("ta-LK", entry(SAVE_KEY, "Save", "சேமி")));
		assertThat(status(wrongLanguage)).isEqualTo(422);
		assertThat(body(wrongLanguage).path("error").path("details").path("reason").asString())
			.isEqualTo("LANGUAGE_MISMATCH");

		MvcResult garbage = importPo(fx, fx.adminToken(), "si-LK", true,
				"this is not a po file".getBytes(StandardCharsets.UTF_8));
		assertThat(status(garbage)).isEqualTo(422);

		MvcResult binary = importPo(fx, fx.adminToken(), "si-LK", true, new byte[] { (byte) 0xde, 0x12, 0x04, 0x00 });
		assertThat(status(binary)).isEqualTo(422);

		// No Language header is fine.
		MvcResult noHeader = importPo(fx, fx.adminToken(), "si-LK", true, po(null, entry(SAVE_KEY, "Save", "x")));
		assertThat(status(noHeader)).as(rawContent(noHeader)).isEqualTo(200);
		assertThat(drafts(fx)).isZero();
	}

	@Test
	void exportOverridesOrEffectiveAsPoOrMo() {
		Fx fx = seed("i18n-export");
		publishOverride(fx, "si-LK", SAVE_KEY, "සුරකින්න", 0);
		saveDraftOk(fx, "si-LK", CHANGED_KEY, "භාෂාව {language}", null);

		MvcResult overrides = call(fx.host(), fx.adminToken(),
				get("/api/v1/i18n/admin/export?locale=si-LK&scope=OVERRIDES&format=po"));
		assertThat(status(overrides)).as(rawContent(overrides)).isEqualTo(200);
		assertThat(overrides.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION)).startsWith("attachment")
			.contains("si-LK-overrides.po");
		PoFile overridePo = PoFile.parse(contentUtf8(overrides));
		assertThat(overridePo.header()).contains("Language: si-LK");
		// Published overrides only - the draft is not exported.
		assertThat(overridePo.entries()).extracting(PoFile.Entry::msgctxt).containsExactly(SAVE_KEY);
		assertThat(overridePo.entries().get(0).msgid()).isEqualTo("Save");
		assertThat(overridePo.entries().get(0).msgstr()).isEqualTo("සුරකින්න");

		MvcResult effective = call(fx.host(), fx.adminToken(),
				get("/api/v1/i18n/admin/export?locale=si-LK&scope=EFFECTIVE"));
		assertThat(status(effective)).isEqualTo(200);
		PoFile effectivePo = PoFile.parse(contentUtf8(effective));
		assertThat(effectivePo.entries().size()).isGreaterThan(8);
		assertThat(effectivePo.entries().stream().filter(e -> e.msgctxt().equals(SAVE_KEY)).findFirst().orElseThrow()
			.msgstr()).isEqualTo("සුරකින්න");
		assertThat(effectivePo.entries().stream().filter(e -> e.msgctxt().equals(CHANGED_KEY)).findFirst().orElseThrow()
			.msgstr()).doesNotContain("භාෂාව");

		MvcResult mo = call(fx.host(), fx.adminToken(),
				get("/api/v1/i18n/admin/export?locale=si-LK&scope=OVERRIDES&format=mo"));
		assertThat(status(mo)).isEqualTo(200);
		assertThat(mo.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION)).contains("si-LK-overrides.mo");
		byte[] bytes = mo.getResponse().getContentAsByteArray();
		assertThat(ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(0)).isEqualTo(0x950412de);
		assertThat(new String(bytes, StandardCharsets.UTF_8)).contains(SAVE_KEY + "\u0004Save").contains("සුරකින්න");

		assertThat(status(call(fx.host(), fx.adminToken(), get("/api/v1/i18n/admin/export?locale=si-LK&scope=ALL"))))
			.isEqualTo(400);
		assertThat(status(call(fx.host(), fx.adminToken(),
				get("/api/v1/i18n/admin/export?locale=si-LK&scope=EFFECTIVE&format=xliff"))))
			.isEqualTo(400);
		// Exported overrides re-import as "unchanged".
		JsonNode report = data(importPo(fx, fx.adminToken(), "si-LK", true, overrides.getResponse().getContentAsByteArray()));
		assertThat(report.path("valid").asBoolean()).isTrue();
		assertThat(report.path("unchanged").asInt()).isEqualTo(1);
	}

	private static String contentUtf8(MvcResult result) {
		return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
	}

}
