package com.lms.common.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.core.env.MapPropertySource;

/**
 * ADR-019 Decision 4 - the {@code prod} profile's fail-fast check, run
 * directly against an unrefreshed context (as {@code
 * JwtSecretStartupValidatorTest} does). All values are synthetic.
 */
class ProductionSecretsStartupValidatorTest {

	/** Synthetic 32-byte keys (Base64), not used anywhere else. */
	private static final String MEDIA_KEY = "MTExMTExMTExMTExMTExMTExMTExMTExMTExMTExMTE=";

	private static final String PAYMENT_KEY = "MjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjI=";

	private final ProductionSecretsStartupValidator validator = new ProductionSecretsStartupValidator();

	private static Map<String, Object> validSecrets() {
		Map<String, Object> secrets = new HashMap<>();
		secrets.put("spring.datasource.password", "synthetic-db-password-7Hq");
		secrets.put("app.security.jwt.secret", "synthetic-jwt-signing-secret-with-enough-length-9Xk2");
		secrets.put("integrations.media.encryption-key", MEDIA_KEY);
		secrets.put("payment.credentials.encryption-key", PAYMENT_KEY);
		return secrets;
	}

	private static StaticApplicationContext context(Map<String, Object> properties, String... profiles) {
		StaticApplicationContext context = new StaticApplicationContext();
		context.getEnvironment().setActiveProfiles(profiles);
		context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
		return context;
	}

	@Test
	void startsUnderProdWithEveryCriticalSecretSet() {
		assertThatCode(() -> validator.initialize(context(validSecrets(), "prod"))).doesNotThrowAnyException();
	}

	@Test
	void doesNothingOutsideTheProdProfile() {
		// local/test (and any other profile) are guarded elsewhere or use dev fallbacks on purpose.
		assertThatCode(() -> validator.initialize(context(Map.of(), "local"))).doesNotThrowAnyException();
		assertThatCode(() -> validator.initialize(context(Map.of(), "test"))).doesNotThrowAnyException();
		assertThatCode(() -> validator.initialize(context(Map.of()))).doesNotThrowAnyException();
	}

	@Test
	void reportsEveryMissingSecretByNameAtOnce() {
		assertThatThrownBy(() -> validator.initialize(context(Map.of(), "prod")))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("DB_PASSWORD is missing")
			.hasMessageContaining("JWT_SECRET is missing")
			.hasMessageContaining("MEDIA_CREDENTIALS_ENCRYPTION_KEY is missing")
			.hasMessageContaining("PAYMENT_CREDENTIALS_ENCRYPTION_KEY is missing");
	}

	@Test
	void rejectsEachDevOnlyValueFromTheLocalProfile() {
		for (ProductionSecretsStartupValidator.CriticalSecret secret : ProductionSecretsStartupValidator.CRITICAL_SECRETS) {
			for (String devValue : ProductionSecretsStartupValidator.DEV_ONLY_VALUES) {
				Map<String, Object> secrets = validSecrets();
				secrets.put(secret.property(), devValue);

				assertThatThrownBy(() -> validator.initialize(context(secrets, "prod")))
					.hasMessageContaining(secret.secretName() + " is a development-only value");
			}
		}
	}

	@Test
	void rejectsAnEditedCopyOfADevPlaceholder() {
		Map<String, Object> secrets = validSecrets();
		secrets.put("app.security.jwt.secret", "dev-only-placeholder-but-slightly-edited-by-an-operator-123");

		assertThatThrownBy(() -> validator.initialize(context(secrets, "prod")))
			.hasMessageContaining("JWT_SECRET is a development-only value");
	}

	@Test
	void encryptionKeysMustBe32BytesOfBase64() {
		Map<String, Object> notBase64 = validSecrets();
		notBase64.put("integrations.media.encryption-key", "not base64 at all!");
		assertThatThrownBy(() -> validator.initialize(context(notBase64, "prod")))
			.hasMessageContaining("MEDIA_CREDENTIALS_ENCRYPTION_KEY must be Base64 for exactly 32 bytes");

		Map<String, Object> tooShort = validSecrets();
		tooShort.put("payment.credentials.encryption-key", "c2hvcnQta2V5");
		assertThatThrownBy(() -> validator.initialize(context(tooShort, "prod")))
			.hasMessageContaining("PAYMENT_CREDENTIALS_ENCRYPTION_KEY must be Base64 for exactly 32 bytes");
	}

	@Test
	void theLocalProfileCanNeverRunTogetherWithProd() {
		assertThatThrownBy(() -> validator.initialize(context(validSecrets(), "prod", "local")))
			.hasMessageContaining("'local' profile must never be active together with 'prod'");
	}

	@Test
	void neverPutsASecretValueInTheErrorMessage() {
		Map<String, Object> secrets = validSecrets();
		secrets.put("payment.credentials.encryption-key", "c3VwZXItc2VjcmV0LWJ1dC10b28tc2hvcnQ=");

		assertThatThrownBy(() -> validator.initialize(context(secrets, "prod")))
			.message()
			.doesNotContain("c3VwZXItc2VjcmV0LWJ1dC10b28tc2hvcnQ=");
	}

}
