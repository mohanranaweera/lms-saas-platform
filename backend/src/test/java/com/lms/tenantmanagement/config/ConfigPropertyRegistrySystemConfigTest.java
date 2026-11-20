package com.lms.tenantmanagement.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.tenantmanagement.api.ConfigDomain;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * SET-CFG-01: the Klass "Configs" keys. The GA measurement id reaches every
 * tracked page's script, so it must only ever be a GA4 id; both switches are
 * plain booleans defaulting to today's behavior ({@code true}).
 */
class ConfigPropertyRegistrySystemConfigTest {

	private final ConfigPropertyRegistry registry = new ConfigPropertyRegistry();

	@ParameterizedTest
	@ValueSource(strings = { "G-ABCD", "G-ABC123XYZ9", "G-12345678901234567890" })
	void gaMeasurementIdAcceptsGa4Ids(String id) {
		assertThat(valid(definition(ConfigDomain.GENERAL, "ga_measurement_id"), id)).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "G-", "G-ABC", "g-abc12345", "UA-12345-1", "G-ABC 123", "G-ABC123\"",
			"G-1');alert(1)//", "G-123456789012345678901", "https://www.googletagmanager.com/gtag/js?id=G-ABCD" })
	void gaMeasurementIdRejectsAnythingElse(String id) {
		assertThat(valid(definition(ConfigDomain.GENERAL, "ga_measurement_id"), id)).isFalse();
	}

	@Test
	void gaMeasurementIdIsOptional() {
		ConfigPropertyDefinition definition = definition(ConfigDomain.GENERAL, "ga_measurement_id");
		assertThat(definition.defaultValue()).isNull();
		assertThat(definition.required()).isFalse();
		assertThat(valid(definition, 12345)).isFalse();
	}

	@Test
	void smsAndSelfEnrollmentSwitchesAreBooleansDefaultingToTrue() {
		for (ConfigPropertyDefinition definition : new ConfigPropertyDefinition[] {
				definition(ConfigDomain.NOTIFICATION, "sms_enabled"),
				definition(ConfigDomain.STUDENT, "self_enrollment_enabled") }) {
			assertThat(definition.valueType()).isEqualTo(ConfigValueType.BOOLEAN);
			assertThat(definition.defaultValue()).isEqualTo(Boolean.TRUE);
			assertThat(valid(definition, false)).isTrue();
			assertThat(valid(definition, "false")).isFalse();
		}
	}

	private ConfigPropertyDefinition definition(ConfigDomain domain, String key) {
		return registry.find(domain, key).orElseThrow(() -> new AssertionError("missing " + key));
	}

	private static boolean valid(ConfigPropertyDefinition definition, Object value) {
		return definition.validator().test(value, Map.of(definition.key(), value));
	}

}
