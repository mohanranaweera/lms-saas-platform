package com.lms.localizationmanagement.po;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** I18N-01: the Java twin of scripts/i18n/po.mjs - parse, serialize, MO (doc section 3.3). */
class PoFileTest {

	private static final String SAMPLE = String.join("\n", "msgid \"\"",
			"msgstr \"Language: si-LK\\nContent-Type: text/plain; charset=UTF-8\\n\"", "",
			"# translator comment", "#. Generic save button", "#: i18n/source/common.json", "#, icu-format, fuzzy",
			"#| msgid \"Save it\"", "msgctxt \"common.actions.save\"", "msgid \"Save\"", "msgstr \"සුරකින්න\"", "",
			"#. multi-line + escapes", "msgctxt \"teacher.courses.count\"", "msgid \"\"",
			"\"{count, plural, one {# course} \"", "\"other {# courses}}\"",
			"msgstr \"{count, plural, one {පාඨමාලා #} other {පාඨමාලා #ක්}}\"", "",
			"msgctxt \"common.quote\"", "msgid \"Say \\\"hi\\\"\\tto\\n{name}\\\\\"", "msgstr \"\"", "",
			"#~ msgctxt \"common.old\"", "#~ msgid \"Old\"", "#~ msgstr \"පැරණි\"", "");

	@Test
	void parsesContextCommentsFlagsPreviousMultiLineEscapesAndObsolete() {
		PoFile po = PoFile.parse("﻿" + SAMPLE.replace("\n", "\r\n"));
		assertThat(po.header()).isEqualTo("Language: si-LK\nContent-Type: text/plain; charset=UTF-8\n");
		assertThat(po.entries()).hasSize(4);

		PoFile.Entry save = po.entries().get(0);
		assertThat(save.msgctxt()).isEqualTo("common.actions.save");
		assertThat(save.msgid()).isEqualTo("Save");
		assertThat(save.msgstr()).isEqualTo("සුරකින්න");
		assertThat(save.comments()).containsExactly("translator comment");
		assertThat(save.extracted()).containsExactly("Generic save button");
		assertThat(save.references()).containsExactly("i18n/source/common.json");
		assertThat(save.flags()).containsExactly("icu-format", "fuzzy");
		assertThat(save.fuzzy()).isTrue();
		assertThat(save.previous()).isEqualTo("Save it");
		assertThat(save.obsolete()).isFalse();

		PoFile.Entry count = po.entries().get(1);
		assertThat(count.msgid()).isEqualTo("{count, plural, one {# course} other {# courses}}");
		assertThat(count.msgstr()).isEqualTo("{count, plural, one {පාඨමාලා #} other {පාඨමාලා #ක්}}");

		PoFile.Entry quote = po.entries().get(2);
		assertThat(quote.msgid()).isEqualTo("Say \"hi\"\tto\n{name}\\");
		assertThat(quote.msgstr()).isEmpty();

		PoFile.Entry old = po.entries().get(3);
		assertThat(old.obsolete()).isTrue();
		assertThat(old.msgctxt()).isEqualTo("common.old");
		assertThat(old.msgstr()).isEqualTo("පැරණි");
	}

	@Test
	void roundTripIsStable() {
		PoFile first = PoFile.parse(SAMPLE);
		String serialized = first.serialize();
		PoFile second = PoFile.parse(serialized);
		assertThat(second).isEqualTo(first);
		assertThat(second.serialize()).isEqualTo(serialized);
		// ICU placeholders/plurals survive untouched.
		assertThat(second.entries().get(1).msgstr()).contains("{count, plural,").contains("#ක්");
		assertThat(serialized).contains("#~ msgctxt \"common.old\"").contains("#| msgid \"Save it\"")
			.contains("#, icu-format, fuzzy");
	}

	@Test
	void entriesWithoutBlankLinesStillSplit() {
		PoFile po = PoFile.parse("msgctxt \"a.b\"\nmsgid \"A\"\nmsgstr \"x\"\nmsgctxt \"a.c\"\nmsgid \"C\"\nmsgstr \"y\"\n");
		assertThat(po.entries()).extracting(PoFile.Entry::msgctxt).containsExactly("a.b", "a.c");
	}

	@Test
	void msgidPluralIsSurfacedSoTheImportCanRejectIt() {
		PoFile po = PoFile.parse("msgctxt \"a.b\"\nmsgid \"one\"\nmsgid_plural \"many\"\nmsgstr[0] \"x\"\nmsgstr[1] \"y\"\n");
		PoFile.Entry entry = po.entries().get(0);
		assertThat(entry.msgidPlural()).isEqualTo("many");
		assertThat(entry.msgstr()).isEqualTo("xy");
	}

	@Test
	void garbageIsASyntaxError() {
		assertThatThrownBy(() -> PoFile.parse("msgctxt \"a\"\nthis is not po\n"))
			.isInstanceOf(PoFile.PoSyntaxException.class);
		assertThatThrownBy(() -> PoFile.parse("msgid unquoted\n")).isInstanceOf(PoFile.PoSyntaxException.class);
	}

	@Test
	void moIsLittleEndianGnuWithContextKeysAndOnlyShippableEntries() {
		PoFile po = new PoFile("", List.of(PoFile.Entry.of("b.key", "B", "බී", List.of(), List.of("icu-format")),
				PoFile.Entry.of("a.key", "A", "ඒ", List.of(), List.of()),
				PoFile.Entry.of("c.key", "C", "", List.of(), List.of()),
				PoFile.Entry.of("d.key", "D", "fuzzy one", List.of(), List.of("fuzzy"))));
		byte[] mo = po.toMo("si-LK");
		ByteBuffer buf = ByteBuffer.wrap(mo).order(ByteOrder.LITTLE_ENDIAN);
		assertThat(buf.getInt(0)).isEqualTo(0x950412de);
		int n = buf.getInt(8);
		assertThat(n).isEqualTo(3); // header + a.key + b.key
		int keyTable = buf.getInt(12);
		int valueTable = buf.getInt(16);
		Map<String, String> pairs = new HashMap<>();
		String previous = null;
		for (int i = 0; i < n; i++) {
			String key = read(mo, buf.getInt(keyTable + i * 8), buf.getInt(keyTable + i * 8 + 4));
			String value = read(mo, buf.getInt(valueTable + i * 8), buf.getInt(valueTable + i * 8 + 4));
			if (previous != null) {
				assertThat(key.compareTo(previous)).isPositive();
			}
			previous = key;
			pairs.put(key, value);
		}
		assertThat(pairs.get("a.key\u0004A")).isEqualTo("ඒ");
		assertThat(pairs.get("b.key\u0004B")).isEqualTo("බී");
		assertThat(pairs.get("")).contains("Language: si-LK").contains("Plural-Forms: nplurals=1; plural=0;");
	}

	private static String read(byte[] mo, int length, int offset) {
		assertThat(mo[offset + length]).isZero();
		return new String(mo, offset, length, StandardCharsets.UTF_8);
	}

}
