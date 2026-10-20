package com.lms.tenantmanagement.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.lms.tenantmanagement.api.ConfigDomain;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * KLASS-T8b SEC L6: the AOC Player store links ({@code VIDEO.player_android_url}
 * / {@code player_ios_url}) are shown to students verbatim, so only absolute
 * {@code https://} URLs with a host are accepted.
 */
class ConfigPropertyRegistryPlayerUrlTest {

	private final ConfigPropertyRegistry registry = new ConfigPropertyRegistry();

	@ParameterizedTest
	@ValueSource(strings = { "player_android_url", "player_ios_url" })
	void acceptsHttpsLinks(String key) {
		assertThat(valid(key, "https://play.example.test/aoc-player")).isTrue();
		assertThat(valid(key, "HTTPS://apps.example.test/app/id1")).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = { "player_android_url", "player_ios_url" })
	void rejectsNonHttpsLinks(String key) {
		assertThat(valid(key, "http://play.example.test/aoc-player")).isFalse();
		assertThat(valid(key, "javascript:alert(1)")).isFalse();
		assertThat(valid(key, "ftp://files.example.test/player")).isFalse();
		assertThat(valid(key, "https://")).isFalse();
		assertThat(valid(key, "//play.example.test/aoc-player")).isFalse();
		assertThat(valid(key, 42)).isFalse();
	}

	private boolean valid(String key, Object value) {
		ConfigPropertyDefinition definition = registry.find(ConfigDomain.VIDEO, key).orElseThrow();
		return definition.validator().test(value, Map.of());
	}

}
