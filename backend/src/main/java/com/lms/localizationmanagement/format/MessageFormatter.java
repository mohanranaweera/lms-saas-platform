package com.lms.localizationmanagement.format;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * I18N-01 shared message format: an ICU MessageFormat subset
 * (docs/architecture/i18n-translation-management.md section 3.2). This is the Java twin of
 * {@code frontend/src/i18n/icu.ts} and mirrors it 1:1 (parser, quoting rules, plural
 * categories, validation codes); both are tested against {@code i18n/spec/format-vectors.json}.
 *
 * <p>Output is always plain text. Nothing here evaluates or renders HTML; a caller embedding
 * the result in HTML must escape it (or escape the parameters, see the email path).
 */
public final class MessageFormatter {

	public static final int MAX_MESSAGE_LENGTH = 5000;

	/** CLDR integer plural categories for the supported languages (non-integers -> "other"). */
	private static final Map<String, List<String>> PLURAL_CATEGORIES = Map.of("en", List.of("one", "other"), "si",
			List.of("one", "other"), "ta", List.of("one", "other"));

	private static final List<String> DEFAULT_CATEGORIES = List.of("one", "other");

	private static final Map<String, NumberFormat> NUMBER_FORMATS = new ConcurrentHashMap<>();

	private MessageFormatter() {
	}

	/** A parsed message node. */
	public sealed interface Node permits Text, Arg, Pound, Plural {
	}

	public record Text(String value) implements Node {
	}

	public record Arg(String name) implements Node {
	}

	public record Pound() implements Node {
	}

	/** {@code options} keeps source order (selector -> branch). */
	public record Plural(String name, Map<String, List<Node>> options) implements Node {
	}

	/** Thrown for an unparseable message; {@code position} is the UTF-16 offset of the problem. */
	public static final class MessageSyntaxException extends RuntimeException {

		private final int position;

		public MessageSyntaxException(String message, int position) {
			super(message + " at " + position);
			this.position = position;
		}

		public int position() {
			return position;
		}

	}

	public static String languageOf(String locale) {
		return locale.split("-", -1)[0].toLowerCase(Locale.ROOT);
	}

	public static List<String> pluralCategoriesFor(String locale) {
		return PLURAL_CATEGORIES.getOrDefault(languageOf(locale), DEFAULT_CATEGORIES);
	}

	public static String pluralCategory(String locale, double n) {
		if (!isInteger(n)) {
			return "other";
		}
		double abs = Math.abs(n);
		if ("si".equals(languageOf(locale))) {
			return abs == 0 || abs == 1 ? "one" : "other";
		}
		return abs == 1 ? "one" : "other";
	}

	public static List<Node> parse(String text) {
		Parser parser = new Parser(text);
		List<Node> nodes = parser.parseNodes(false, 0);
		if (parser.pos < text.length()) {
			throw new MessageSyntaxException("Unmatched '}'", parser.pos);
		}
		return nodes;
	}

	private static final class Parser {

		private final String text;

		private int pos;

		Parser(String text) {
			this.text = text;
		}

		private char at(int index) {
			return index < text.length() ? text.charAt(index) : '\0';
		}

		private boolean has(int index) {
			return index < text.length();
		}

		/** Parses until end of input (depth 0) or the '}' closing a plural branch (left unconsumed). */
		List<Node> parseNodes(boolean inPlural, int depth) {
			List<Node> nodes = new ArrayList<>();
			StringBuilder buffer = new StringBuilder();
			while (pos < text.length()) {
				char ch = text.charAt(pos);
				if (ch == '\'') {
					boolean hasNext = has(pos + 1);
					char next = at(pos + 1);
					if (hasNext && next == '\'') {
						buffer.append('\'');
						pos += 2;
					}
					else if (hasNext && (next == '{' || next == '}' || (next == '#' && inPlural))) {
						pos += 1;
						boolean closed = false;
						while (pos < text.length()) {
							char q = text.charAt(pos);
							if (q == '\'') {
								if (has(pos + 1) && at(pos + 1) == '\'') {
									buffer.append('\'');
									pos += 2;
									continue;
								}
								pos += 1;
								closed = true;
								break;
							}
							buffer.append(q);
							pos += 1;
						}
						if (!closed) {
							throw new MessageSyntaxException("Unterminated quote", pos);
						}
					}
					else {
						buffer.append('\'');
						pos += 1;
					}
				}
				else if (ch == '{') {
					flush(nodes, buffer);
					nodes.add(parseArgument(depth));
				}
				else if (ch == '}') {
					if (depth == 0) {
						throw new MessageSyntaxException("Unmatched '}'", pos);
					}
					flush(nodes, buffer);
					return nodes;
				}
				else if (ch == '#' && inPlural) {
					flush(nodes, buffer);
					nodes.add(new Pound());
					pos += 1;
				}
				else {
					buffer.append(ch);
					pos += 1;
				}
			}
			if (depth > 0) {
				throw new MessageSyntaxException("Unclosed plural branch", pos);
			}
			flush(nodes, buffer);
			return nodes;
		}

		private static void flush(List<Node> nodes, StringBuilder buffer) {
			if (!buffer.isEmpty()) {
				nodes.add(new Text(buffer.toString()));
				buffer.setLength(0);
			}
		}

		Node parseArgument(int depth) {
			int start = pos;
			pos += 1; // {
			skipWhitespace();
			String name = readName();
			if (name.isEmpty()) {
				throw new MessageSyntaxException("Expected placeholder name", pos);
			}
			skipWhitespace();
			if (has(pos) && at(pos) == '}') {
				pos += 1;
				return new Arg(name);
			}
			if (!has(pos) || at(pos) != ',') {
				throw new MessageSyntaxException("Expected '}' or ','", pos);
			}
			pos += 1;
			skipWhitespace();
			String kind = readWord();
			if (!"plural".equals(kind)) {
				throw new MessageSyntaxException("Unsupported argument type '" + kind + "'", pos);
			}
			skipWhitespace();
			if (!has(pos) || at(pos) != ',') {
				throw new MessageSyntaxException("Expected ',' after plural", pos);
			}
			pos += 1;
			Map<String, List<Node>> options = new LinkedHashMap<>();
			for (;;) {
				skipWhitespace();
				if (pos >= text.length()) {
					throw new MessageSyntaxException("Unclosed plural argument", start);
				}
				if (at(pos) == '}') {
					pos += 1;
					break;
				}
				String selector = readSelector();
				if (selector.isEmpty()) {
					throw new MessageSyntaxException("Expected plural selector", pos);
				}
				if (options.containsKey(selector)) {
					throw new MessageSyntaxException("Duplicate plural selector '" + selector + "'", pos);
				}
				skipWhitespace();
				if (!has(pos) || at(pos) != '{') {
					throw new MessageSyntaxException("Expected '{' after selector", pos);
				}
				pos += 1;
				options.put(selector, parseNodes(true, depth + 1));
				if (!has(pos) || at(pos) != '}') {
					throw new MessageSyntaxException("Unclosed plural branch", pos);
				}
				pos += 1;
			}
			return new Plural(name, Collections.unmodifiableMap(options));
		}

		void skipWhitespace() {
			while (pos < text.length() && isJsWhitespace(text.charAt(pos))) {
				pos += 1;
			}
		}

		String readName() {
			if (pos >= text.length() || !isAsciiLetter(text.charAt(pos))) {
				return "";
			}
			int start = pos;
			while (pos < text.length() && isNamePart(text.charAt(pos))) {
				pos += 1;
			}
			return text.substring(start, pos);
		}

		String readWord() {
			int start = pos;
			while (pos < text.length() && text.charAt(pos) >= 'a' && text.charAt(pos) <= 'z') {
				pos += 1;
			}
			return text.substring(start, pos);
		}

		String readSelector() {
			if (has(pos) && at(pos) == '=') {
				int start = pos;
				pos += 1;
				while (pos < text.length() && text.charAt(pos) >= '0' && text.charAt(pos) <= '9') {
					pos += 1;
				}
				return pos - start > 1 ? text.substring(start, pos) : "";
			}
			return readWord();
		}

	}

	private static boolean isAsciiLetter(char c) {
		return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
	}

	private static boolean isNamePart(char c) {
		return isAsciiLetter(c) || (c >= '0' && c <= '9') || c == '_';
	}

	/** JavaScript's {@code \s}: ECMAScript WhiteSpace + LineTerminator. */
	private static boolean isJsWhitespace(char c) {
		return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000B' || c == '\f' || c == ' '
				|| c == '﻿' || c == ' ' || c == ' '
				|| Character.getType(c) == Character.SPACE_SEPARATOR;
	}

	/** Placeholder names and plural-argument names used anywhere in the message. */
	public record Arguments(Set<String> placeholders, Set<String> plurals) {
	}

	public static Arguments collectArguments(List<Node> nodes) {
		Set<String> placeholders = new LinkedHashSet<>();
		Set<String> plurals = new LinkedHashSet<>();
		walk(nodes, placeholders, plurals);
		return new Arguments(placeholders, plurals);
	}

	private static void walk(List<Node> list, Set<String> placeholders, Set<String> plurals) {
		for (Node node : list) {
			if (node instanceof Arg arg) {
				placeholders.add(arg.name());
			}
			else if (node instanceof Plural plural) {
				plurals.add(plural.name());
				for (List<Node> branch : plural.options().values()) {
					walk(branch, placeholders, plurals);
				}
			}
		}
	}

	/**
	 * Validation problems as stable codes ({@code CODE} or {@code CODE:detail}), sorted. Empty =
	 * valid. {@code source} (nullable) is the English source the translation must stay
	 * compatible with.
	 */
	public static List<String> validate(String text, String locale, String source) {
		Set<String> problems = new TreeSet<>();
		if (text.length() > MAX_MESSAGE_LENGTH) {
			problems.add("TOO_LONG");
		}
		List<Node> nodes;
		try {
			nodes = parse(text);
		}
		catch (MessageSyntaxException ex) {
			problems.add("SYNTAX");
			return List.copyOf(problems);
		}
		Set<String> allowed = Set.copyOf(pluralCategoriesFor(locale));
		checkPlurals(nodes, allowed, problems);
		if (source != null) {
			List<Node> sourceNodes;
			try {
				sourceNodes = parse(source);
			}
			catch (MessageSyntaxException ex) {
				sourceNodes = null;
			}
			if (sourceNodes != null) {
				Arguments want = collectArguments(sourceNodes);
				Arguments have = collectArguments(nodes);
				for (String name : want.placeholders()) {
					if (!have.placeholders().contains(name)) {
						problems.add("PLACEHOLDER_MISSING:" + name);
					}
				}
				for (String name : have.placeholders()) {
					if (!want.placeholders().contains(name)) {
						problems.add("PLACEHOLDER_UNKNOWN:" + name);
					}
				}
				for (String name : want.plurals()) {
					if (!have.plurals().contains(name)) {
						problems.add("PLURAL_MISSING:" + name);
					}
				}
				for (String name : have.plurals()) {
					if (!want.plurals().contains(name)) {
						problems.add("PLURAL_UNKNOWN:" + name);
					}
				}
			}
		}
		return List.copyOf(problems);
	}

	private static void checkPlurals(List<Node> list, Set<String> allowed, Set<String> problems) {
		for (Node node : list) {
			if (node instanceof Plural plural) {
				if (!plural.options().containsKey("other")) {
					problems.add("PLURAL_MISSING_OTHER:" + plural.name());
				}
				for (Map.Entry<String, List<Node>> option : plural.options().entrySet()) {
					if (!option.getKey().startsWith("=") && !allowed.contains(option.getKey())) {
						problems.add("PLURAL_INVALID_CATEGORY:" + option.getKey());
					}
					checkPlurals(option.getValue(), allowed, problems);
				}
			}
		}
	}

	/** True when {@code text} is a non-blank message with no validation problems against {@code source}. */
	public static boolean isUsable(String text, String locale, String source) {
		return text != null && !text.isBlank() && validate(text, locale, source).isEmpty();
	}

	/** Formats {@code text}; throws {@link MessageSyntaxException} for an unparseable message. */
	public static String format(String text, String locale, Map<String, ?> params) {
		return render(parse(text), locale, params == null ? Map.of() : params, null);
	}

	/** Formats already-parsed nodes (callers that cache the parse). */
	public static String format(List<Node> nodes, String locale, Map<String, ?> params) {
		return render(nodes, locale, params == null ? Map.of() : params, null);
	}

	private static String render(List<Node> nodes, String locale, Map<String, ?> params, Number count) {
		StringBuilder out = new StringBuilder();
		for (Node node : nodes) {
			switch (node) {
				case Text t -> out.append(t.value());
				case Pound p -> out.append(count == null ? "#" : formatNumber(locale, count));
				case Arg arg -> {
					Object value = params.get(arg.name());
					if (value == null) {
						out.append('{').append(arg.name()).append('}');
					}
					else if (value instanceof Number number) {
						out.append(formatNumber(locale, number));
					}
					else {
						out.append(value);
					}
				}
				case Plural plural -> {
					Object raw = params.get(plural.name());
					Double n = toNumber(raw);
					if (raw == null || n == null) {
						out.append(render(plural.options().getOrDefault("other", List.of()), locale, params, null));
						break;
					}
					List<Node> branch = plural.options().get("=" + exactKey(n));
					if (branch == null) {
						branch = plural.options().get(pluralCategory(locale, n));
					}
					if (branch == null) {
						branch = plural.options().getOrDefault("other", List.of());
					}
					Number countValue = raw instanceof Number number ? number : n;
					out.append(render(branch, locale, params, countValue));
				}
			}
		}
		return out.toString();
	}

	/** JavaScript {@code Number(raw)}: numbers as-is, strings parsed (blank = 0), otherwise NaN (null). */
	private static Double toNumber(Object raw) {
		if (raw == null) {
			return null;
		}
		if (raw instanceof Number number) {
			double d = number.doubleValue();
			return Double.isNaN(d) ? null : d;
		}
		String s = String.valueOf(raw).strip();
		if (s.isEmpty()) {
			return 0d;
		}
		try {
			double d = Double.parseDouble(s);
			return Double.isNaN(d) ? null : d;
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	private static String exactKey(double n) {
		if (isInteger(n) && Math.abs(n) < 1e15) {
			return Long.toString((long) n);
		}
		return Double.toString(n);
	}

	private static boolean isInteger(double n) {
		return !Double.isNaN(n) && !Double.isInfinite(n) && Math.floor(n) == n;
	}

	private static String formatNumber(String locale, Number n) {
		NumberFormat prototype = NUMBER_FORMATS.computeIfAbsent(locale,
				tag -> NumberFormat.getInstance(Locale.forLanguageTag(tag)));
		NumberFormat format = (NumberFormat) prototype.clone();
		if (n instanceof Double || n instanceof Float) {
			return format.format(n.doubleValue());
		}
		if (n instanceof java.math.BigDecimal || n instanceof java.math.BigInteger) {
			return format.format(n);
		}
		return format.format(n.longValue());
	}

}
