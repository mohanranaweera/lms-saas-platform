package com.lms.tenantmanagement.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.tenantmanagement.api.ConfigDomain;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * SET-PAY-01: the institute public information keys (GENERAL) and the
 * bank-transfer switch (PAYMENT). The public keys reach the anonymous public
 * home page, so the social links must be {@code https} URLs on their own
 * host and nothing else.
 */
class ConfigPropertyRegistryInstituteTest {

	private final ConfigPropertyRegistry registry = new ConfigPropertyRegistry();

	@ParameterizedTest
	@ValueSource(strings = { "address_line1", "address_line2", "city", "postal_code" })
	void addressFieldsAreOptionalShortText(String key) {
		assertThat(general(key).defaultValue()).isNull();
		assertThat(general(key).required()).isFalse();
		assertThat(valid(general(key), "School Junction")).isTrue();
		assertThat(valid(general(key), "x".repeat(255))).isTrue();
		assertThat(valid(general(key), "x".repeat(256))).isFalse();
		assertThat(valid(general(key), " ")).isFalse();
		assertThat(valid(general(key), 60280)).isFalse();
	}

	@Test
	void introductionAllowsUpTo2000Characters() {
		ConfigPropertyDefinition definition = general("institute_introduction");
		assertThat(definition.defaultValue()).isNull();
		assertThat(valid(definition, "We teach.\nSince 2001.")).isTrue();
		assertThat(valid(definition, "x".repeat(2000))).isTrue();
		assertThat(valid(definition, "x".repeat(2001))).isFalse();
		assertThat(valid(definition, "")).isFalse();
	}

	@ParameterizedTest
	@ValueSource(strings = { "https://facebook.com/demo", "https://www.facebook.com/demo", "https://m.facebook.com/x",
			"https://fb.com/demo", "https://WWW.FACEBOOK.COM/demo" })
	void facebookAcceptsHttpsFacebookHosts(String url) {
		assertThat(valid(general("facebook_url"), url)).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = { "http://facebook.com/demo", "https://facebook.com.evil.test/demo",
			"https://notfacebook.com/demo", "javascript:alert(1)", "facebook.com/demo", "https://youtube.com/demo",
			"" })
	void facebookRejectsOtherSchemesAndHosts(String url) {
		assertThat(valid(general("facebook_url"), url)).isFalse();
	}

	@ParameterizedTest
	@ValueSource(strings = { "https://youtube.com/@demo", "https://www.youtube.com/channel/abc", "https://youtu.be/abc" })
	void youtubeAcceptsHttpsYoutubeHosts(String url) {
		assertThat(valid(general("youtube_url"), url)).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = { "http://youtube.com/@demo", "https://youtube.com.evil.test/x", "javascript:alert(1)",
			"https://facebook.com/demo" })
	void youtubeRejectsOtherSchemesAndHosts(String url) {
		assertThat(valid(general("youtube_url"), url)).isFalse();
	}

	@ParameterizedTest
	@ValueSource(strings = { "terms_of_service", "refund_policy", "privacy_policy" })
	void policiesAreOptionalPlainTextUpTo50000Characters(String key) {
		ConfigPropertyDefinition definition = general(key);
		assertThat(definition.defaultValue()).isNull();
		assertThat(definition.required()).isFalse();
		assertThat(valid(definition, "1. Fees are non-refundable.\n2. Except as below.")).isTrue();
		assertThat(valid(definition, "x".repeat(50_000))).isTrue();
		assertThat(valid(definition, "x".repeat(50_001))).isFalse();
		assertThat(valid(definition, "   ")).isFalse();
		assertThat(valid(definition, 42)).isFalse();
	}

	@Test
	void defaultCurrencyStaysAGeneralKey() {
		assertThat(general("default_currency").defaultValue()).isEqualTo("USD");
	}

	@Test
	void bankTransferEnabledIsABooleanDefaultingToTrue() {
		ConfigPropertyDefinition definition = registry.find(ConfigDomain.PAYMENT, "bank_transfer_enabled")
			.orElseThrow(() -> new AssertionError("missing bank_transfer_enabled"));
		assertThat(definition.valueType()).isEqualTo(ConfigValueType.BOOLEAN);
		assertThat(definition.defaultValue()).isEqualTo(Boolean.TRUE);
		assertThat(valid(definition, false)).isTrue();
		assertThat(valid(definition, true)).isTrue();
		assertThat(valid(definition, "false")).isFalse();
		assertThat(valid(definition, 0)).isFalse();
	}

	private ConfigPropertyDefinition general(String key) {
		return registry.find(ConfigDomain.GENERAL, key).orElseThrow(() -> new AssertionError("missing " + key));
	}

	private static boolean valid(ConfigPropertyDefinition definition, Object value) {
		return definition.validator().test(value, Map.of(definition.key(), value));
	}

}
