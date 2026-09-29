package com.lms.common.logging;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.json.JsonWriter.ValueProcessor;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;

/**
 * Masks secrets in the structured (JSON) console log - a safety net on top
 * of "never log a secret" (MEDIA-INT-01 security review S7). Registered via
 * {@code logging.structured.json.customizer}.
 *
 * <ul>
 * <li>Any key/value pair whose key names a secret ({@code accessToken},
 * {@code apiKey}, {@code authorization}...) has its value replaced.</li>
 * <li>Inside any string (e.g. a message or exception text), {@code
 * access_token=...}-style fragments and {@code Bearer ...} credentials are
 * replaced.</li>
 * </ul>
 */
public class SecretMaskingJsonMembersCustomizer implements StructuredLoggingJsonMembersCustomizer<Object> {

	static final String MASK = "***";

	private static final Set<String> SECRET_KEYS = Set.of("accesstoken", "refreshtoken", "apikey", "tokenauthkey",
			"clientsecret", "merchantsecret", "authorization", "accesskey", "password", "codeverifier", "secret",
			"webhooksecret", "credentials", "credentialsciphertext");

	private static final Pattern INLINE_SECRET = Pattern.compile(
			"(?i)(access_token|refresh_token|client_secret|api_?key|accesskey|merchant_?secret|code_verifier|token_?auth_?key)"
					+ "(\"?\\s*[:=]\\s*\"?)([^\"&\\s,}]+)");

	private static final Pattern BEARER = Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]+");

	@Override
	public void customize(JsonWriter.Members<Object> members) {
		members.applyingValueProcessor(
				ValueProcessor.<Object>of((value) -> MASK).whenHasPath((path) -> isSecretKey(path.name())));
		members.applyingValueProcessor(ValueProcessor.of(String.class, SecretMaskingJsonMembersCustomizer::mask));
	}

	/** {@code access_token}, {@code apiKey}, {@code X-Api-Key} ... all normalise to a known secret name. */
	static boolean isSecretKey(String name) {
		if (name == null) {
			return false;
		}
		String normalised = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
		return SECRET_KEYS.contains(normalised) || normalised.endsWith("apikey") || normalised.endsWith("secret");
	}

	static String mask(String value) {
		if (value == null || value.isEmpty()) {
			return value;
		}
		String masked = INLINE_SECRET.matcher(value).replaceAll("$1$2" + MASK);
		return BEARER.matcher(masked).replaceAll("$1" + MASK);
	}

}
