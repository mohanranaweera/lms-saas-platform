package com.lms.localizationmanagement.po;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * I18N-01 PO reader/writer and MO writer - the Java twin of {@code scripts/i18n/po.mjs}
 * (mapping: docs/architecture/i18n-translation-management.md section 3.3). Pure, no I/O.
 *
 * @param header the header entry's msgstr (the {@code msgid ""} entry without msgctxt)
 * @param entries every other entry in file order
 */
public record PoFile(String header, List<Entry> entries) {

	private static final Pattern QUOTED = Pattern.compile("^\"(.*)\"\\s*$");

	private static final Pattern KEYWORD = Pattern
		.compile("^(msgctxt|msgid_plural|msgid|msgstr(?:\\[\\d+\\])?)\\s+(.*)$");

	private static final Pattern ESCAPE = Pattern.compile("\\\\(.)");

	public PoFile {
		header = header == null ? "" : header;
		entries = List.copyOf(entries);
	}

	/** Thrown for a line the parser cannot read (same cases as po.mjs). */
	public static final class PoSyntaxException extends RuntimeException {

		public PoSyntaxException(String message) {
			super(message);
		}

	}

	/**
	 * One PO entry. {@code msgidPlural} is non-null only when the source used
	 * {@code msgid_plural}/{@code msgstr[n]} (rejected by the import - plurals live inside the
	 * ICU string).
	 */
	public record Entry(String msgctxt, String msgid, String msgstr, String msgidPlural, List<String> extracted,
			List<String> references, List<String> flags, List<String> comments, String previous,
			boolean obsolete) {

		public Entry {
			extracted = List.copyOf(extracted);
			references = List.copyOf(references);
			flags = List.copyOf(flags);
			comments = List.copyOf(comments);
		}

		public static Entry of(String msgctxt, String msgid, String msgstr, List<String> extracted,
				List<String> flags) {
			return new Entry(msgctxt, msgid, msgstr, null, extracted, List.of(), flags, List.of(), null, false);
		}

		public boolean fuzzy() {
			return flags.contains("fuzzy");
		}

	}

	private static final class Builder {

		String msgctxt;

		String msgid;

		String msgstr;

		String msgidPlural;

		final List<String> extracted = new ArrayList<>();

		final List<String> references = new ArrayList<>();

		final List<String> flags = new ArrayList<>();

		final List<String> comments = new ArrayList<>();

		String previous;

		boolean obsolete;

		String get(String field) {
			return switch (field) {
				case "msgctxt" -> msgctxt;
				case "msgid" -> msgid;
				case "msgid_plural" -> msgidPlural;
				default -> msgstr;
			};
		}

		void set(String field, String value) {
			switch (field) {
				case "msgctxt" -> msgctxt = value;
				case "msgid" -> msgid = value;
				case "msgid_plural" -> msgidPlural = value;
				default -> msgstr = value;
			}
		}

	}

	static String unescape(String s) {
		Matcher m = ESCAPE.matcher(s);
		StringBuilder out = new StringBuilder();
		while (m.find()) {
			String c = m.group(1);
			String replacement = switch (c) {
				case "n" -> "\n";
				case "t" -> "\t";
				case "r" -> "\r";
				case "\"" -> "\"";
				case "\\" -> "\\";
				default -> c;
			};
			m.appendReplacement(out, Matcher.quoteReplacement(replacement));
		}
		m.appendTail(out);
		return out.toString();
	}

	static String escape(String s) {
		return s.replace("\\", "\\\\")
			.replace("\"", "\\\"")
			.replace("\n", "\\n")
			.replace("\t", "\\t")
			.replace("\r", "\\r");
	}

	private static String quoted(String line) {
		Matcher m = QUOTED.matcher(line);
		if (!m.matches()) {
			throw new PoSyntaxException("PO: expected a quoted string, got: " + line);
		}
		return unescape(m.group(1));
	}

	/** Parses PO text (mirrors {@code parsePo} in po.mjs). */
	public static PoFile parse(String text) {
		String normalized = text.startsWith("﻿") ? text.substring(1) : text;
		String[] lines = normalized.replace("\r\n", "\n").split("\n", -1);
		List<Entry> entries = new ArrayList<>();
		String[] header = { "" };
		Builder[] cur = { null };
		String field = null;
		for (String raw : lines) {
			String line = raw.strip();
			if (line.isEmpty()) {
				finish(cur, entries, header);
				field = null;
				continue;
			}
			boolean obsolete = false;
			if (line.startsWith("#~")) {
				obsolete = true;
				line = line.substring(2).strip();
			}
			if (cur[0] == null) {
				cur[0] = new Builder();
			}
			if (obsolete) {
				cur[0].obsolete = true;
			}
			if (line.startsWith("#|")) {
				String rest = line.substring(2).strip();
				if (rest.startsWith("msgid ")) {
					cur[0].previous = quoted(rest.substring(6));
				}
				else if (rest.startsWith("\"") && cur[0].previous != null) {
					cur[0].previous += quoted(rest);
				}
				continue;
			}
			if (line.startsWith("#.")) {
				cur[0].extracted.add(line.substring(2).strip());
				continue;
			}
			if (line.startsWith("#:")) {
				Arrays.stream(line.substring(2).strip().split("\\s+"))
					.filter(s -> !s.isEmpty())
					.forEach(cur[0].references::add);
				continue;
			}
			if (line.startsWith("#,")) {
				Arrays.stream(line.substring(2).split(",", -1))
					.map(String::strip)
					.filter(s -> !s.isEmpty())
					.forEach(cur[0].flags::add);
				continue;
			}
			if (line.startsWith("#")) {
				cur[0].comments.add(line.substring(1).strip());
				continue;
			}
			Matcher kw = KEYWORD.matcher(line);
			if (kw.matches()) {
				String keyword = kw.group(1);
				if (("msgctxt".equals(keyword) || ("msgid".equals(keyword) && cur[0].msgctxt == null))
						&& cur[0].msgid != null) {
					// A new entry started without a blank line.
					boolean keep = cur[0].obsolete;
					finish(cur, entries, header);
					cur[0] = new Builder();
					cur[0].obsolete = keep;
				}
				field = keyword.startsWith("msgstr[") ? "msgstr_plural" : keyword;
				String value = quoted(kw.group(2));
				if ("msgstr_plural".equals(field)) {
					if (cur[0].msgidPlural == null) {
						cur[0].msgidPlural = "";
					}
					cur[0].msgstr = (cur[0].msgstr == null ? "" : cur[0].msgstr) + value;
				}
				else {
					cur[0].set(field, value);
				}
				continue;
			}
			if (line.startsWith("\"") && field != null) {
				String value = quoted(line);
				if ("msgstr_plural".equals(field)) {
					cur[0].msgstr += value;
				}
				else {
					String existing = cur[0].get(field);
					cur[0].set(field, (existing == null ? "" : existing) + value);
				}
				continue;
			}
			throw new PoSyntaxException("PO: cannot parse line: " + raw);
		}
		finish(cur, entries, header);
		return new PoFile(header[0], entries);
	}

	private static void finish(Builder[] cur, List<Entry> entries, String[] header) {
		Builder b = cur[0];
		if (b != null && b.msgid != null) {
			if (b.msgid.isEmpty() && b.msgctxt == null) {
				header[0] = b.msgstr == null ? "" : b.msgstr;
			}
			else {
				entries.add(new Entry(b.msgctxt == null ? "" : b.msgctxt, b.msgid, b.msgstr == null ? "" : b.msgstr,
						b.msgidPlural, b.extracted, b.references, b.flags, b.comments, b.previous, b.obsolete));
			}
		}
		cur[0] = null;
	}

	/** Serializes (mirrors {@code serializePo}); byte-stable for the same input. */
	public String serialize() {
		List<String> out = new ArrayList<>();
		out.add("msgid \"\"");
		out.add("msgstr \"" + escape(header) + "\"");
		out.add("");
		for (Entry e : entries) {
			String p = e.obsolete() ? "#~ " : "";
			for (String c : e.comments()) {
				out.add(("# " + c).stripTrailing());
			}
			for (String x : e.extracted()) {
				out.add("#. " + x);
			}
			if (!e.references().isEmpty()) {
				out.add("#: " + String.join(" ", e.references()));
			}
			if (!e.flags().isEmpty()) {
				out.add("#, " + String.join(", ", new LinkedHashSet<>(e.flags())));
			}
			if (e.previous() != null) {
				out.add("#| msgid \"" + escape(e.previous()) + "\"");
			}
			out.add(p + "msgctxt \"" + escape(e.msgctxt()) + "\"");
			out.add(p + "msgid \"" + escape(e.msgid()) + "\"");
			out.add(p + "msgstr \"" + escape(e.msgstr()) + "\"");
			out.add("");
		}
		return String.join("\n", out);
	}

	/** GNU MO (little-endian, no hash table). Non-fuzzy, non-empty, non-obsolete entries only. */
	public byte[] toMo(String locale) {
		String moHeader = "Content-Type: text/plain; charset=UTF-8\nLanguage: " + locale + "\n"
				+ "Plural-Forms: nplurals=1; plural=0;\nX-Message-Format: icu-subset\n";
		List<byte[][]> pairs = new ArrayList<>();
		pairs.add(new byte[][] { new byte[0], moHeader.getBytes(StandardCharsets.UTF_8) });
		for (Entry e : entries) {
			if (e.obsolete() || e.msgstr().isEmpty() || e.fuzzy()) {
				continue;
			}
			pairs.add(new byte[][] { (e.msgctxt() + "\u0004" + e.msgid()).getBytes(StandardCharsets.UTF_8),
					e.msgstr().getBytes(StandardCharsets.UTF_8) });
		}
		pairs.sort((a, b) -> Arrays.compareUnsigned(a[0], b[0]));
		int n = pairs.size();
		int tableStart = 28;
		int offset = tableStart + n * 16;
		ByteBuffer head = ByteBuffer.allocate(offset).order(ByteOrder.LITTLE_ENDIAN);
		head.putInt(0, 0x950412de);
		head.putInt(4, 0);
		head.putInt(8, n);
		head.putInt(12, tableStart);
		head.putInt(16, tableStart + n * 8);
		head.putInt(20, 0);
		head.putInt(24, offset);
		ByteArrayOutputStream blobs = new ByteArrayOutputStream();
		for (int i = 0; i < n; i++) {
			byte[] k = pairs.get(i)[0];
			head.putInt(tableStart + i * 8, k.length);
			head.putInt(tableStart + i * 8 + 4, offset);
			blobs.writeBytes(k);
			blobs.write(0);
			offset += k.length + 1;
		}
		for (int i = 0; i < n; i++) {
			byte[] v = pairs.get(i)[1];
			head.putInt(tableStart + n * 8 + i * 8, v.length);
			head.putInt(tableStart + n * 8 + i * 8 + 4, offset);
			blobs.writeBytes(v);
			blobs.write(0);
			offset += v.length + 1;
		}
		ByteArrayOutputStream all = new ByteArrayOutputStream();
		all.writeBytes(head.array());
		all.writeBytes(blobs.toByteArray());
		return all.toByteArray();
	}

}
