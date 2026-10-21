package com.lms.common.config;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Profiles;
import org.springframework.util.StringUtils;

/**
 * ADR-019 Decision 4: under the {@code prod} profile the application refuses
 * to start unless every critical secret is present, is not a known dev-only
 * value, and (for the two encryption keys) is Base64 for exactly 32 bytes.
 *
 * <p>Registered as an {@link ApplicationContextInitializer} from {@code
 * LmsBackendApplication#main}, like {@code JwtSecretStartupValidator} and for
 * the same reason: it runs before the context refresh opens the web port, so
 * a bad configuration never serves a single request. It reads the raw,
 * already-resolved environment (the {@code configtree} import in {@code
 * application-prod.yml} has been applied by then).
 *
 * <p>Error messages name the missing environment variable / secret file,
 * never its value. Other secrets (OAuth client secrets, webhook secrets,
 * video playback) are deliberately not checked here - their features already
 * fail closed on their own and must not block startup.
 */
public class ProductionSecretsStartupValidator implements ApplicationContextInitializer<ConfigurableApplicationContext> {

	/** A critical secret: the resolved property, and the secret file / env var name operators set. */
	record CriticalSecret(String property, String secretName, boolean aesKey) {

	}

	static final List<CriticalSecret> CRITICAL_SECRETS = List.of(
			new CriticalSecret("spring.datasource.password", "DB_PASSWORD", false),
			new CriticalSecret("app.security.jwt.secret", "JWT_SECRET", false),
			new CriticalSecret("integrations.media.encryption-key", "MEDIA_CREDENTIALS_ENCRYPTION_KEY", true),
			new CriticalSecret("payment.credentials.encryption-key", "PAYMENT_CREDENTIALS_ENCRYPTION_KEY", true));

	/** The exact fallback values committed in application-local.yml. */
	static final Set<String> DEV_ONLY_VALUES = Set.of("change_this_local_password",
			"dev-only-placeholder-secret-change-me-before-any-real-deployment-32bytes+",
			"ZGV2LW9ubHktbWVkaWEtY3JlZC1rZXktMzJieXRlcyE=", "ZGV2LW9ubHktcGF5bWVudC1jcmVkLWtleS0zMmJ5dGU=");

	/** Markers of an obviously non-production value, e.g. a copied or edited dev placeholder. */
	private static final List<String> DEV_MARKERS = List.of("dev-only", "change_this", "change-me", "changeme",
			"placeholder");

	private static final int AES_256_KEY_BYTES = 32;

	@Override
	public void initialize(ConfigurableApplicationContext applicationContext) {
		ConfigurableEnvironment environment = applicationContext.getEnvironment();
		if (!environment.acceptsProfiles(Profiles.of("prod"))) {
			return;
		}
		if (environment.acceptsProfiles(Profiles.of("local"))) {
			throw new IllegalStateException(
					"The 'local' profile must never be active together with 'prod' - it carries dev-only keys");
		}
		List<String> problems = new ArrayList<>();
		for (CriticalSecret secret : CRITICAL_SECRETS) {
			String problem = problemWith(secret, environment.getProperty(secret.property()));
			if (problem != null) {
				problems.add(secret.secretName() + " " + problem);
			}
		}
		if (!problems.isEmpty()) {
			throw new IllegalStateException("Refusing to start with the 'prod' profile (ADR-019): "
					+ String.join("; ", problems)
					+ ". Provide each as a Docker secret file under /run/secrets/ - see "
					+ "docs/operations/production-secrets-runbook.md");
		}
	}

	/** @return why the value is unusable, or {@code null} if it is fine. Never includes the value. */
	static String problemWith(CriticalSecret secret, String value) {
		if (!StringUtils.hasText(value)) {
			return "is missing";
		}
		String trimmed = value.trim();
		if (DEV_ONLY_VALUES.contains(trimmed) || looksLikeDevValue(trimmed)) {
			return "is a development-only value";
		}
		if (secret.aesKey() && !isAes256Key(trimmed)) {
			return "must be Base64 for exactly 32 bytes (generate with: openssl rand -base64 32)";
		}
		return null;
	}

	private static boolean looksLikeDevValue(String value) {
		String lower = value.toLowerCase(Locale.ROOT);
		if (DEV_MARKERS.stream().anyMatch(lower::contains)) {
			return true;
		}
		// A Base64 value whose decoded text is a dev marker (e.g. "dev-only-...-key").
		try {
			String decoded = new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8)
				.toLowerCase(Locale.ROOT);
			return DEV_MARKERS.stream().anyMatch(decoded::contains);
		}
		catch (IllegalArgumentException ex) {
			return false;
		}
	}

	private static boolean isAes256Key(String value) {
		try {
			return Base64.getDecoder().decode(value).length == AES_256_KEY_BYTES;
		}
		catch (IllegalArgumentException ex) {
			return false;
		}
	}

}
