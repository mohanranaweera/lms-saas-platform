package com.lms.tenantmanagement.config;

import com.lms.tenantmanagement.api.ConfigDomain;
import java.math.BigDecimal;
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
		registry.put(ConfigDomain.STUDENT, studentProperties());
		registry.put(ConfigDomain.VIDEO, videoProperties());
		registry.put(ConfigDomain.PAYMENT, paymentProperties());
		return Map.copyOf(registry);
	}

	/** Facility fee modes (KLASS-T4c, D07). */
	public static final List<String> FACILITY_FEE_MODES = com.lms.tenantmanagement.api.FacilityFeeSettings.MODES;

	/** Upper bound for a facility fee amount (NUMERIC(12,2) headroom; not a business limit). */
	private static final BigDecimal MAX_FACILITY_FEE = new BigDecimal("1000000");

	/**
	 * KLASS-T4c (D07/D16): payment composer configuration. {@code
	 * facility_fee_mode} NONE|PER_COURSE|PER_LINE|PER_ORDER (default NONE) and {@code
	 * facility_fee_amount} (a JSON number &gt;= 0 with at most 2 decimals,
	 * default 0) are snapshotted onto each composer order. The four bank-slip
	 * display fields have no default ({@code null} = not configured) - they are
	 * shown to students on the slip tab only, never used to route money.
	 */
	private static List<ConfigPropertyDefinition> paymentProperties() {
		return List.of(
				ConfigPropertyDefinition.of(ConfigDomain.PAYMENT, "facility_fee_mode", ConfigValueType.STRING, "NONE",
						(value, batch) -> value instanceof String s && FACILITY_FEE_MODES.contains(s)),
				ConfigPropertyDefinition.of(ConfigDomain.PAYMENT, "facility_fee_amount", ConfigValueType.NUMBER, 0,
						(value, batch) -> isValidFeeAmount(value)),
				ConfigPropertyDefinition.of(ConfigDomain.PAYMENT, "bank_name", ConfigValueType.STRING, null,
						ConfigPropertyRegistry::isShortText),
				ConfigPropertyDefinition.of(ConfigDomain.PAYMENT, "bank_branch", ConfigValueType.STRING, null,
						ConfigPropertyRegistry::isShortText),
				ConfigPropertyDefinition.of(ConfigDomain.PAYMENT, "bank_account_holder", ConfigValueType.STRING, null,
						ConfigPropertyRegistry::isShortText),
				ConfigPropertyDefinition.of(ConfigDomain.PAYMENT, "bank_account_number", ConfigValueType.STRING, null,
						ConfigPropertyRegistry::isShortText));
	}

	private static boolean isShortText(Object value, Map<String, Object> batch) {
		return value instanceof String s && !s.isBlank() && s.length() <= MAX_TEXT_LENGTH;
	}

	/** A JSON number (never a string) {@code >= 0}, at most 2 decimals, below {@link #MAX_FACILITY_FEE}. */
	static boolean isValidFeeAmount(Object value) {
		if (!(value instanceof Number number)) {
			return false;
		}
		try {
			BigDecimal amount = new BigDecimal(number.toString()).stripTrailingZeros();
			return amount.signum() >= 0 && amount.scale() <= 2 && amount.compareTo(MAX_FACILITY_FEE) < 0;
		}
		catch (NumberFormatException ex) {
			return false;
		}
	}

	/**
	 * KLASS-T4b (D16): the AOC Player app store links shown to a student whose
	 * lesson must play in the native player. Optional {@code https://} URLs only
	 * (SEC L6: a store link is shown to students verbatim), no default
	 * ({@code null} = not configured; the lesson launch returns {@code null}).
	 */
	private static List<ConfigPropertyDefinition> videoProperties() {
		return List.of(
				ConfigPropertyDefinition.of(ConfigDomain.VIDEO, "player_android_url", ConfigValueType.STRING, null,
						(value, batch) -> value instanceof String s && s.length() <= MAX_URL_LENGTH
								&& isValidHttpsUrl(s)),
				ConfigPropertyDefinition.of(ConfigDomain.VIDEO, "player_ios_url", ConfigValueType.STRING, null,
						(value, batch) -> value instanceof String s && s.length() <= MAX_URL_LENGTH
								&& isValidHttpsUrl(s)));
	}

	/**
	 * Wave 3 (master instruction §10, Student registration configuration).
	 * {@code public_registration_enabled} defaults {@code true} (the public
	 * self-registration endpoint is reachable out of the box); every other
	 * property defaults {@code false} - a tenant must explicitly opt into
	 * approval/OTP/per-field requirements, matching this registry's existing
	 * "safe, unsurprising default" convention. All eight are plain booleans,
	 * validated only for type (mirrors how a boolean-typed property is
	 * validated nowhere else yet in this registry, so this is the first
	 * simple {@code instanceof Boolean} validator here).
	 *
	 * <p><b>Deprecated (STU-PROF-01):</b> the five {@code require_*} flags are
	 * superseded by the per-field {@code student_profile_field_setting} rows
	 * ({@code StudentProfileFieldConfigService}). They are only read as the
	 * default required-ness for a tenant that has never saved its student
	 * profile field settings; kept registered so existing values stay valid.
	 */
	private static List<ConfigPropertyDefinition> studentProperties() {
		return List.of(
				ConfigPropertyDefinition.of(ConfigDomain.STUDENT, "public_registration_enabled",
						ConfigValueType.BOOLEAN, Boolean.TRUE, ConfigPropertyRegistry::isBoolean),
				ConfigPropertyDefinition.of(ConfigDomain.STUDENT, "approval_required", ConfigValueType.BOOLEAN,
						Boolean.FALSE, ConfigPropertyRegistry::isBoolean),
				ConfigPropertyDefinition.of(ConfigDomain.STUDENT, "otp_required", ConfigValueType.BOOLEAN,
						Boolean.FALSE, ConfigPropertyRegistry::isBoolean),
				ConfigPropertyDefinition.of(ConfigDomain.STUDENT, "require_guardian_info", ConfigValueType.BOOLEAN,
						Boolean.FALSE, ConfigPropertyRegistry::isBoolean),
				ConfigPropertyDefinition.of(ConfigDomain.STUDENT, "require_school", ConfigValueType.BOOLEAN,
						Boolean.FALSE, ConfigPropertyRegistry::isBoolean),
				ConfigPropertyDefinition.of(ConfigDomain.STUDENT, "require_grade", ConfigValueType.BOOLEAN,
						Boolean.FALSE, ConfigPropertyRegistry::isBoolean),
				ConfigPropertyDefinition.of(ConfigDomain.STUDENT, "require_stream", ConfigValueType.BOOLEAN,
						Boolean.FALSE, ConfigPropertyRegistry::isBoolean),
				ConfigPropertyDefinition.of(ConfigDomain.STUDENT, "require_mobile", ConfigValueType.BOOLEAN,
						Boolean.FALSE, ConfigPropertyRegistry::isBoolean));
	}

	private static boolean isBoolean(Object value, Map<String, Object> batch) {
		return value instanceof Boolean;
	}

	private static List<ConfigPropertyDefinition> generalProperties() {
		return List.of(
				// Required, no default - GET with no row set surfaces null/absent
				// rather than throwing (see TenantConfigService#getDomain); PUT
				// rejects a blank/missing value, and (KLASS-T8d) a null - it is
				// never clearable once set.
				ConfigPropertyDefinition.requiredOf(ConfigDomain.GENERAL, "institute_name", ConfigValueType.STRING,
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

	/** Absolute {@code https://} URL with a host (no {@code http}, no other scheme). */
	private static boolean isValidHttpsUrl(String value) {
		return isValidHttpUrl(value) && value.regionMatches(true, 0, "https://", 0, 8);
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
