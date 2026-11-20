package com.lms.localizationmanagement.format;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * I18N-01: the Java formatter must pass every case of the shared vector file
 * {@code i18n/spec/format-vectors.json} (also run against {@code frontend/src/i18n/icu.ts}).
 */
class MessageFormatterVectorsTest {

	private static final Path VECTORS = Path.of("..", "i18n", "spec", "format-vectors.json");

	private static final ObjectMapper JSON = JsonMapper.builder().build();

	private static JsonNode vectors() {
		try {
			return JSON.readTree(Files.readString(VECTORS, StandardCharsets.UTF_8));
		}
		catch (IOException ex) {
			throw new IllegalStateException("Cannot read " + VECTORS.toAbsolutePath(), ex);
		}
	}

	static Stream<Arguments> formatCases() {
		List<Arguments> cases = new ArrayList<>();
		for (JsonNode c : vectors().path("format")) {
			Map<String, Object> params = new LinkedHashMap<>();
			c.path("params").properties().forEach(e -> params.put(e.getKey(),
					e.getValue().isNumber() ? (Object) e.getValue().numberValue() : e.getValue().asString()));
			cases.add(Arguments.of(c.path("locale").asString(), c.path("message").asString(), params,
					c.path("expected").asString()));
		}
		return cases.stream();
	}

	static Stream<Arguments> validateCases() {
		List<Arguments> cases = new ArrayList<>();
		for (JsonNode c : vectors().path("validate")) {
			List<String> problems = new ArrayList<>();
			c.path("problems").forEach(p -> problems.add(p.asString()));
			cases.add(Arguments.of(c.path("locale").asString(), c.path("message").asString(),
					c.has("source") ? c.path("source").asString() : null, problems));
		}
		return cases.stream();
	}

	@Test
	void vectorFileHasCases() {
		assertThat(formatCases().count()).isGreaterThanOrEqualTo(10);
		assertThat(validateCases().count()).isGreaterThanOrEqualTo(5);
	}

	@ParameterizedTest(name = "[{index}] {0} {1}")
	@MethodSource("formatCases")
	void formatsLikeTheSharedVectors(String locale, String message, Map<String, Object> params, String expected) {
		assertThat(MessageFormatter.format(message, locale, params)).isEqualTo(expected);
	}

	@ParameterizedTest(name = "[{index}] {0} {1}")
	@MethodSource("validateCases")
	void validatesLikeTheSharedVectors(String locale, String message, String source, List<String> expected) {
		assertThat(MessageFormatter.validate(message, locale, source)).containsExactlyElementsOf(expected);
	}

	@Test
	void pluralCategoriesPerLanguage() {
		assertThat(MessageFormatter.pluralCategory("en-LK", 1)).isEqualTo("one");
		assertThat(MessageFormatter.pluralCategory("en-LK", 0)).isEqualTo("other");
		assertThat(MessageFormatter.pluralCategory("si-LK", 0)).isEqualTo("one");
		assertThat(MessageFormatter.pluralCategory("si-LK", 1)).isEqualTo("one");
		assertThat(MessageFormatter.pluralCategory("si-LK", 2)).isEqualTo("other");
		assertThat(MessageFormatter.pluralCategory("ta-LK", 1)).isEqualTo("one");
		assertThat(MessageFormatter.pluralCategory("ta-LK", 1.5)).isEqualTo("other");
	}

	@Test
	void tooLongAndUnparseableMessages() {
		assertThat(MessageFormatter.validate("x".repeat(5001), "en-LK", null)).containsExactly("TOO_LONG");
		assertThat(MessageFormatter.validate("{a, plural, one {x} one {y} other {z}}", "en-LK", null))
			.containsExactly("SYNTAX");
		assertThat(MessageFormatter.validate("'{unterminated", "en-LK", null)).containsExactly("SYNTAX");
		assertThatThrownBy(() -> MessageFormatter.format("Hello {", "en-LK", Map.of()))
			.isInstanceOf(MessageFormatter.MessageSyntaxException.class);
	}

	@Test
	void outputIsNeverHtmlEscapedOrEvaluated() {
		assertThat(MessageFormatter.format("{a}", "en-LK", Map.of("a", "<img src=x onerror=1>")))
			.isEqualTo("<img src=x onerror=1>");
		assertThat(MessageFormatter.format("{n, plural, other {# items}}", "en-LK", Map.of("n", "abc")))
			.isEqualTo("# items");
	}

}
