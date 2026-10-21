package com.lms.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * ADR-019 Decisions 1-2 against the real {@code application-prod.yml}:
 * secrets mounted as files are read through the {@code configtree} import and
 * resolve the existing {@code ${NAME:}} placeholders - no property renamed.
 * Also settles the ADR's open "confirm while implementing" points (placeholder
 * resolution and trailing newlines, as {@code echo "..." > file} writes one).
 *
 * <p>Starts only an empty, non-web context (no auto-configuration, no
 * database), just enough to build the Environment. It reads the MAIN
 * {@code src/main/resources} config explicitly: the test classpath's own
 * {@code application.yml} would otherwise shadow the real one.
 */
class ProdProfileConfigTreeTest {

	private static final String SECRETS_DIR_PROPERTY = "LMS_SECRETS_DIR";

	/** Maven runs tests from backend/, so this is the real production config. */
	private static final String MAIN_CONFIG = "file:src/main/resources/";

	/** Synthetic 32-byte keys (Base64). */
	private static final String MEDIA_KEY = "MTExMTExMTExMTExMTExMTExMTExMTExMTExMTExMTE=";

	private static final String PAYMENT_KEY = "MjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjI=";

	@TempDir
	Path secretsDir;

	@Configuration(proxyBeanMethods = false)
	static class EmptyConfiguration {

	}

	@AfterEach
	void clearSystemProperties() {
		System.clearProperty(SECRETS_DIR_PROPERTY);
		System.clearProperty("spring.config.location");
	}

	private void secret(String name, String content) throws IOException {
		Files.writeString(secretsDir.resolve(name), content, StandardCharsets.UTF_8);
	}

	private ConfigurableApplicationContext startProd() {
		System.setProperty(SECRETS_DIR_PROPERTY, secretsDir.toString());
		System.setProperty("spring.config.location", MAIN_CONFIG);
		return new SpringApplicationBuilder(EmptyConfiguration.class).profiles("prod")
			.web(WebApplicationType.NONE)
			.run();
	}

	@Test
	void secretFilesResolveTheExistingPlaceholders() throws IOException {
		// Written the way an operator typically does it: with a trailing newline.
		secret("DB_PASSWORD", "synthetic-db-password\n");
		secret("JWT_SECRET", "synthetic-jwt-signing-secret-with-enough-length\n");
		secret("MEDIA_CREDENTIALS_ENCRYPTION_KEY", MEDIA_KEY + "\n");
		secret("PAYMENT_CREDENTIALS_ENCRYPTION_KEY", PAYMENT_KEY);
		secret("ZOOM_OAUTH_CLIENT_SECRET", "synthetic-zoom-secret\n");

		try (ConfigurableApplicationContext context = startProd()) {
			Environment env = context.getEnvironment();
			// application-prod.yml placeholders
			assertThat(env.getProperty("spring.datasource.password")).isEqualTo("synthetic-db-password");
			assertThat(env.getProperty("app.security.jwt.secret"))
				.isEqualTo("synthetic-jwt-signing-secret-with-enough-length");
			// application.yml placeholders, untouched by this change
			assertThat(env.getProperty("integrations.media.encryption-key")).isEqualTo(MEDIA_KEY);
			assertThat(env.getProperty("payment.credentials.encryption-key")).isEqualTo(PAYMENT_KEY);
			assertThat(env.getProperty("integrations.media.zoom.client-secret")).isEqualTo("synthetic-zoom-secret");

			// Behind the proxy the client IP comes from X-Forwarded-For (AUTH-PWD-01 review M-1).
			assertThat(env.getProperty("server.forward-headers-strategy")).isEqualTo("native");
			// ...and the startup guard accepts the resolved values.
			assertThatCode(() -> new ProductionSecretsStartupValidator().initialize(context))
				.doesNotThrowAnyException();
		}
	}

	@Test
	void aMissingSecretFileLeavesThePropertyEmptyAndTheGuardRefuses() {
		try (ConfigurableApplicationContext context = startProd()) {
			assertThat(context.getEnvironment().getProperty("app.security.jwt.secret")).isEmpty();
			// No dev-only fallback leaks in from application-local.yml.
			assertThat(context.getEnvironment().getProperty("integrations.media.encryption-key")).isEmpty();

			assertThatThrownBy(() -> new ProductionSecretsStartupValidator().initialize(context))
				.hasMessageContaining("JWT_SECRET is missing");
		}
	}

}
