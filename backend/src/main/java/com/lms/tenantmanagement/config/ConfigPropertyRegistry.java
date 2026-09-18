package com.lms.tenantmanagement.config;

import com.lms.tenantmanagement.api.ConfigDomain;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Aggregates the per-domain property lists for the typed tenant
 * configuration framework (Wave 1). Only {@link ConfigDomain#GENERAL}/
 * {@link ConfigDomain#BRANDING} have real registered properties - every
 * other domain resolves to an empty list, so {@code GET
 * /api/v1/tenant-config/{domain}} returns {@code 200} with an empty array
 * for an unimplemented domain, never {@code 404} (a later wave populates
 * the rest by adding to {@link #buildRegistry()}, never by changing this
 * class's public shape).
 */
@Component
public class ConfigPropertyRegistry {

	private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

	private static final Pattern CURRENCY_CODE_PATTERN = Pattern.compile("^[A-Z]{3}$");

	private static final Pattern HEX_COLOR_PATTERN = Pattern.compile("^#[0-9A-Fa-f]{6}$");

	private static final double MIN_AA_CONTRAST_RATIO = 4.5;

	/** Cap for free-text identity/contact fields - generous, not a UX limit, just a storage-pressure guard. */
	private static final int MAX_TEXT_LENGTH = 255;

	/** Cap for URL fields - generous enough for any real logo/favicon URL, including query strings. */
	private static final int MAX_URL_LENGTH = 2048;

	private final Map<ConfigDomain, List<ConfigPropertyDefinition>> propertiesByDomain = buildRegistry();

	public List<ConfigPropertyDefinition> propertiesFor(ConfigDomain domain) {
		return propertiesByDomain.getOrDefault(domain, List.of());
	}

	public Optional<ConfigPropertyDefinition> find(ConfigDomain domain, String key) {
		return propertiesFor(domain).stream().filter(definition -> definition.key().equals(key)).findFirst();
	}

	private static Map<ConfigDomain, List<ConfigPropertyDefinition>> buildRegistry() {
		Map<ConfigDomain, List<ConfigPropertyDefinition>> registry = new EnumMap<>(ConfigDomain.class);
		for (ConfigDomain domain : ConfigDomain.values()) {
			registry.put(domain, List.of());
		}
		registry.put(ConfigDomain.GENERAL, generalProperties());
		registry.put(ConfigDomain.BRANDING, brandingProperties());
		return Map.copyOf(registry);
	}

	private static List<ConfigPropertyDefinition> generalProperties() {
		return List.of(
				// Required, no default - GET with no row set surfaces null/absent
				// rather than throwing (see TenantConfigService#getDomain); PUT
				// rejects a blank/missing value.
				ConfigPropertyDefinition.of(ConfigDomain.GENERAL, "institute_name", ConfigValueType.STRING, null,
						(value, batch) -> value instanceof String s && !s.isBlank() && s.length() <= MAX_TEXT_LENGTH),
				ConfigPropertyDefinition.of(ConfigDomain.GENERAL, "support_email", ConfigValueType.STRING, null,
						(value, batch) -> value instanceof String s && s.length() <= MAX_TEXT_LENGTH
								&& EMAIL_PATTERN.matcher(s).matches()),
				// "No format constraint" per the brief - only the type and length are checked.
				ConfigPropertyDefinition.of(ConfigDomain.GENERAL, "support_phone", ConfigValueType.STRING, null,
						(value, batch) -> value instanceof String s && s.length() <= MAX_TEXT_LENGTH),
				ConfigPropertyDefinition.of(ConfigDomain.GENERAL, "default_timezone", ConfigValueType.STRING, "UTC",
						(value, batch) -> value instanceof String s && ZoneId.getAvailableZoneIds().contains(s)),
				// Shape-only ISO-4217 validation (^[A-Z]{3}$) - deliberately no
				// hardcoded currency allowlist, per the brief.
				ConfigPropertyDefinition.of(ConfigDomain.GENERAL, "default_currency", ConfigValueType.STRING, "USD",
						(value, batch) -> value instanceof String s && CURRENCY_CODE_PATTERN.matcher(s).matches()));
	}

	private static List<ConfigPropertyDefinition> brandingProperties() {
		return List.of(
				ConfigPropertyDefinition.of(ConfigDomain.BRANDING, "primary_color", ConfigValueType.STRING, null,
						(value, batch) -> value instanceof String s && HEX_COLOR_PATTERN.matcher(s).matches()),
				ConfigPropertyDefinition.of(ConfigDomain.BRANDING, "secondary_color", ConfigValueType.STRING, null,
						ConfigPropertyRegistry::validateSecondaryColor),
				ConfigPropertyDefinition.of(ConfigDomain.BRANDING, "logo_url", ConfigValueType.STRING, null,
						(value, batch) -> value instanceof String s && s.length() <= MAX_URL_LENGTH && isValidHttpUrl(s)),
				ConfigPropertyDefinition.of(ConfigDomain.BRANDING, "favicon_url", ConfigValueType.STRING, null,
						(value, batch) -> value instanceof String s && s.length() <= MAX_URL_LENGTH && isValidHttpUrl(s)));
	}

	/**
	 * {@code secondary_color}'s own hex-format check, PLUS (per the property
	 * definition's contract) a cross-field WCAG AA contrast check against
	 * {@code primary_color} - but only when {@code primary_color} is ALSO
	 * present, with a valid hex value of its own, in the same batch of
	 * values being saved together; a {@code secondary_color}-only update
	 * (leaving an already-persisted {@code primary_color} untouched) is not
	 * cross-checked, per the brief's "when both ... are present in the same
	 * update" wording.
	 */
	private static boolean validateSecondaryColor(Object value, Map<String, Object> batch) {
		if (!(value instanceof String secondary) || !HEX_COLOR_PATTERN.matcher(secondary).matches()) {
			return false;
		}
		Object primaryCandidate = batch.get("primary_color");
		if (primaryCandidate instanceof String primary && HEX_COLOR_PATTERN.matcher(primary).matches()) {
			return WcagContrast.ratio(primary, secondary) >= MIN_AA_CONTRAST_RATIO;
		}
		return true;
	}

	private static boolean isValidHttpUrl(String value) {
		try {
			URI uri = new URI(value);
			String scheme = uri.getScheme();
			return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) && uri.getHost() != null
					&& !uri.getHost().isBlank();
		}
		catch (URISyntaxException e) {
			return false;
		}
	}

}
