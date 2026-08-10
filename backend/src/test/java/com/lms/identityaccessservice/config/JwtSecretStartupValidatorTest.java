package com.lms.identityaccessservice.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.core.env.MapPropertySource;

/**
 * Unit-level coverage for {@link JwtSecretStartupValidator}, exercising it
 * directly against an unrefreshed {@link StaticApplicationContext} rather
 * than a real Spring Boot startup - it never needs a refreshed context or a
 * real web server (the whole point of the fix this test guards: the check
 * must run and throw before either exists).
 */
class JwtSecretStartupValidatorTest {

	private final JwtSecretStartupValidator validator = new JwtSecretStartupValidator();

	@Test
	void doesNothingUnderLocalProfileEvenWithThePlaceholderSecret() {
		StaticApplicationContext context = contextWith("local", null);

		validator.initialize(context);
	}

	@Test
	void doesNothingUnderTestProfileEvenWithThePlaceholderSecret() {
		StaticApplicationContext context = contextWith("test", null);

		validator.initialize(context);
	}

	@Test
	void rejectsAMissingSecretOutsideLocalAndTest() {
		StaticApplicationContext context = contextWith("staging", null);

		assertThatThrownBy(() -> validator.initialize(context)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("JWT_SECRET");
	}

	@Test
	void rejectsThePlaceholderSecretOutsideLocalAndTest() {
		StaticApplicationContext context = contextWith("staging", JwtProperties.PLACEHOLDER_SECRET);

		assertThatThrownBy(() -> validator.initialize(context)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("JWT_SECRET");
	}

	@Test
	void rejectsAMissingSecretUnderAnUnexpectedOrMissingProfile() {
		// No active profile at all (e.g. a misconfigured deployment that never set
		// SPRING_PROFILES_ACTIVE) must fail closed exactly like an explicit
		// "staging"/"production" profile - never silently treated as local/test.
		StaticApplicationContext context = new StaticApplicationContext();

		assertThatThrownBy(() -> validator.initialize(context)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void acceptsARealSecretOutsideLocalAndTest() {
		StaticApplicationContext context = contextWith("staging",
				"a-real-secret-that-is-not-the-placeholder-and-is-at-least-32-bytes-long");

		validator.initialize(context);
	}

	private static StaticApplicationContext contextWith(String activeProfile, String secretValue) {
		StaticApplicationContext context = new StaticApplicationContext();
		context.getEnvironment().setActiveProfiles(activeProfile);
		if (secretValue != null) {
			context.getEnvironment()
				.getPropertySources()
				.addFirst(new MapPropertySource("test",
						Map.of(JwtSecretStartupValidator.SECRET_PROPERTY, secretValue)));
		}
		return context;
	}

}
