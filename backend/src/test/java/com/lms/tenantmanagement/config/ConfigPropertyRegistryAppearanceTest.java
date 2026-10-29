package com.lms.tenantmanagement.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.tenantmanagement.api.ConfigDomain;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tenant appearance (spec docs/specs/tenant-appearance-public-home-parity.md
 * section 3): curated font allowlist, theme colors with WCAG cross-checks,
 * logo size tokens and the teacher auto-rotate flag. These values reach the
 * anonymous public page, so anything outside the allowlist must be rejected
 * server-side.
 */
class ConfigPropertyRegistryAppearanceTest {

	private final ConfigPropertyRegistry registry = new ConfigPropertyRegistry();

	// ------------------------------------------------------------------
	// Fonts.
	// ------------------------------------------------------------------

	@ParameterizedTest
	@ValueSource(strings = { "INTER", "ROBOTO", "OPEN_SANS", "LATO", "POPPINS", "MONTSERRAT", "NOTO_SANS",
			"NOTO_SERIF", "GEIST", "SYSTEM", "SERIF" })
	void everyCuratedFontIsAcceptedForHeadingAndBody(String font) {
		assertThat(valid("header_font_family", font)).isTrue();
		assertThat(valid("body_font_family", font)).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = { "Comic Sans MS", "inter", "Inter", "Arial", "", " ", "INTER;", "INTER, sans-serif",
			"font-family: x; } body { display:none", "url(https://evil.example.test/font.woff2)",
			"https://fonts.googleapis.com/css2?family=Inter", "<script>alert(1)</script>", "PAPYRUS" })
	void arbitraryCssOrUrlFontValuesAreRejected(String font) {
		assertThat(valid("header_font_family", font)).isFalse();
		assertThat(valid("body_font_family", font)).isFalse();
	}

	@Test
	void fontsMustBeStrings() {
		assertThat(valid("header_font_family", 1)).isFalse();
		assertThat(valid("body_font_family", Boolean.TRUE)).isFalse();
	}

	@Test
	void bothFontsDefaultToInter() {
		assertThat(definition("header_font_family").defaultValue()).isEqualTo("INTER");
		assertThat(definition("body_font_family").defaultValue()).isEqualTo("INTER");
	}

	// ------------------------------------------------------------------
	// Colors.
	// ------------------------------------------------------------------

	@ParameterizedTest
	@ValueSource(strings = { "primary_color", "accent_color", "background_color", "surface_color", "text_color" })
	void themeColorsAcceptSixDigitHex(String key) {
		assertThat(valid(key, "#0F172A")).isTrue();
		assertThat(valid(key, "#0f172a")).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = { "primary_color", "accent_color", "background_color", "surface_color", "text_color" })
	void themeColorsRejectNonHex(String key) {
		assertThat(valid(key, "blue")).isFalse();
		assertThat(valid(key, "#FFF")).isFalse();
		assertThat(valid(key, "#GGGGGG")).isFalse();
		assertThat(valid(key, "0F172A")).isFalse();
		assertThat(valid(key, "#0F172A80")).isFalse();
		assertThat(valid(key, "rgb(0,0,0)")).isFalse();
		assertThat(valid(key, "#000000; background:url(x)")).isFalse();
		assertThat(valid(key, 123456)).isFalse();
	}

	@Test
	void newThemeColorsHaveNoDefault() {
		for (String key : new String[] { "accent_color", "background_color", "surface_color", "text_color" }) {
			assertThat(definition(key).defaultValue()).as(key).isNull();
		}
	}

	@Test
	void textColorWithLowContrastAgainstBackgroundInTheSameBatchIsRejected() {
		Map<String, Object> batch = batch("text_color", "#777777", "background_color", "#888888");
		assertThat(validIn("text_color", batch)).isFalse();
	}

	@Test
	void textColorWithLowContrastAgainstSurfaceInTheSameBatchIsRejected() {
		Map<String, Object> batch = batch("text_color", "#0F172A", "background_color", "#FFFFFF");
		batch.put("surface_color", "#1E293B");
		assertThat(validIn("text_color", batch)).isFalse();
	}

	@Test
	void textColorWithAaContrastAgainstBackgroundAndSurfaceIsAccepted() {
		Map<String, Object> batch = batch("text_color", "#0F172A", "background_color", "#FFFFFF");
		batch.put("surface_color", "#F8FAFC");
		assertThat(validIn("text_color", batch)).isTrue();
	}

	@Test
	void textColorAloneIsNotCrossChecked() {
		assertThat(validIn("text_color", batch("text_color", "#EEEEEE"))).isTrue();
	}

	@Test
	void textColorIgnoresAnInvalidBackgroundInTheBatch() {
		// The invalid background is reported on its own key; text is not cross-checked against it.
		assertThat(validIn("text_color", batch("text_color", "#EEEEEE", "background_color", "white"))).isTrue();
	}

	@Test
	void primaryBelowThreeToOneAgainstBackgroundIsRejected() {
		// #FFFF00 on white is ~1.07:1.
		assertThat(validIn("primary_color", batch("primary_color", "#FFFF00", "background_color", "#FFFFFF")))
			.isFalse();
	}

	@Test
	void primaryAtLeastThreeToOneAgainstBackgroundIsAccepted() {
		// #1D4ED8 on white is ~6.7:1; #808080 on white is ~3.9:1 (passes 3:1, would fail 4.5:1).
		assertThat(validIn("primary_color", batch("primary_color", "#1D4ED8", "background_color", "#FFFFFF")))
			.isTrue();
		assertThat(validIn("primary_color", batch("primary_color", "#808080", "background_color", "#FFFFFF")))
			.isTrue();
	}

	@Test
	void primaryAloneIsNotCrossChecked() {
		assertThat(validIn("primary_color", batch("primary_color", "#FFFFFF"))).isTrue();
	}

	@Test
	void secondaryColorRuleIsUnchanged() {
		assertThat(validIn("secondary_color", batch("secondary_color", "#EEEEEE", "primary_color", "#FFFFFF")))
			.isFalse();
		assertThat(validIn("secondary_color", batch("secondary_color", "#000000", "primary_color", "#FFFFFF")))
			.isTrue();
	}

	// ------------------------------------------------------------------
	// Logo size and teacher auto-rotate.
	// ------------------------------------------------------------------

	@ParameterizedTest
	@ValueSource(strings = { "SMALL", "STANDARD", "LARGE" })
	void logoDisplaySizeAcceptsTheThreeTokens(String size) {
		assertThat(valid("logo_display_size", size)).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = { "small", "MEDIUM", "XL", "48px", "" })
	void logoDisplaySizeRejectsAnythingElse(String size) {
		assertThat(valid("logo_display_size", size)).isFalse();
	}

	@Test
	void logoDisplaySizeDefaultsToStandard() {
		assertThat(definition("logo_display_size").defaultValue()).isEqualTo("STANDARD");
		assertThat(definition("logo_display_size").valueType()).isEqualTo(ConfigValueType.STRING);
	}

	@Test
	void teacherAutoRotateIsABooleanDefaultingToFalse() {
		ConfigPropertyDefinition definition = definition("teacher_auto_rotate");
		assertThat(definition.valueType()).isEqualTo(ConfigValueType.BOOLEAN);
		assertThat(definition.defaultValue()).isEqualTo(Boolean.FALSE);
		assertThat(valid("teacher_auto_rotate", true)).isTrue();
		assertThat(valid("teacher_auto_rotate", false)).isTrue();
		assertThat(valid("teacher_auto_rotate", "true")).isFalse();
		assertThat(valid("teacher_auto_rotate", 1)).isFalse();
	}

	// ------------------------------------------------------------------
	// Helpers.
	// ------------------------------------------------------------------

	private ConfigPropertyDefinition definition(String key) {
		return registry.find(ConfigDomain.BRANDING, key).orElseThrow(() -> new AssertionError("missing " + key));
	}

	private boolean valid(String key, Object value) {
		return definition(key).validator().test(value, Map.of(key, value));
	}

	private boolean validIn(String key, Map<String, Object> batch) {
		return definition(key).validator().test(batch.get(key), batch);
	}

	private static Map<String, Object> batch(Object... keyValues) {
		Map<String, Object> batch = new HashMap<>();
		for (int i = 0; i < keyValues.length; i += 2) {
			batch.put((String) keyValues[i], keyValues[i + 1]);
		}
		return batch;
	}

}
