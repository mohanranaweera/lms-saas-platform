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
import java.util.UUID;
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

	/** WCAG 2.x AA minimum for large text / UI components (primary color on the page background). */
	private static final double MIN_UI_CONTRAST_RATIO = 3.0;

	/**
	 * Curated public-page font allowlist (tenant appearance spec section 3). The
	 * frontend maps each token to a predeclared self-hosted font; an arbitrary
	 * family name, CSS fragment or URL can never be stored.
	 */
	public static final List<String> APPEARANCE_FONTS = List.of("INTER", "ROBOTO", "OPEN_SANS", "LATO", "POPPINS",
			"MONTSERRAT", "NOTO_SANS", "NOTO_SERIF", "GEIST", "SYSTEM", "SERIF");

	public static final String DEFAULT_APPEARANCE_FONT = "INTER";

	/** Public header logo size tokens; the frontend owns the pixel dimensions. */
	public static final List<String> LOGO_DISPLAY_SIZES = List.of("SMALL", "STANDARD", "LARGE");

	public static final String DEFAULT_LOGO_DISPLAY_SIZE = "STANDARD";

	/** Cap for free-text identity/contact fields - generous, not a UX limit, just a storage-pressure guard. */
	private static final int MAX_TEXT_LENGTH = 255;

	/** Cap for URL fields - generous enough for any real logo/favicon URL, including query strings. */
	private static final int MAX_URL_LENGTH = 2048;

	/** Cap for the public institute introduction (SET-PAY-01) - a few paragraphs of plain text. */
	private static final int MAX_LONG_TEXT_LENGTH = 2000;

	/** Cap for one institute policy document (LOGIN-01 Phase B) - a long plain-text document. */
	public static final int MAX_POLICY_LENGTH = 50_000;

	/** Policy keys in {@link ConfigDomain#GENERAL}, all public plain text (LOGIN-01 Phase B). */
	public static final List<String> POLICY_KEYS = List.of("terms_of_service", "refund_policy", "privacy_policy");

	private static final int MAX_APPEARANCE_LIST_ITEMS = 50;

	private static final int MAX_APPEARANCE_LIST_VALUE_LENGTH = 4096;

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
	 * shown to students on the slip tab only, never used to route money; Tenant
	 * Admin edits them on Settings -> Payments (SET-PAY-01), audit-logged like
	 * every config change. {@code bank_transfer_enabled} (SET-PAY-01, default
	 * {@code true}) is the tenant's raw switch only - whether a student may
	 * actually submit a slip is decided by {@code BankTransferPolicy}, which
	 * keeps slips available while online payment is unavailable.
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
						ConfigPropertyRegistry::isShortText),
				ConfigPropertyDefinition.of(ConfigDomain.PAYMENT, "bank_transfer_enabled", ConfigValueType.BOOLEAN,
						Boolean.TRUE, ConfigPropertyRegistry::isBoolean));
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

	private static boolean isUrlList(Object value, Map<String, Object> batch) {
		if (!(value instanceof List<?> values) || values.size() > MAX_APPEARANCE_LIST_ITEMS) {
			return false;
		}
		return values.stream().allMatch(item -> item instanceof String s && !s.isBlank()
				&& s.length() <= MAX_APPEARANCE_LIST_VALUE_LENGTH && isValidHttpUrl(s));
	}

	private static boolean isUuidList(Object value, Map<String, Object> batch) {
		if (!(value instanceof List<?> values) || values.size() > MAX_APPEARANCE_LIST_ITEMS) {
			return false;
		}
		return values.stream().allMatch(item -> {
			if (!(item instanceof String s) || s.isBlank() || s.length() > 36) {
				return false;
			}
			try {
				UUID.fromString(s);
				return true;
			}
			catch (IllegalArgumentException ex) {
				return false;
			}
		});
	}

	/** {@code https://} URL on {@code facebook.com}/{@code fb.com} or one of their subdomains. */
	private static boolean isFacebookUrl(String value) {
		try {
			String host = new URI(value).getHost();
			if (!isValidHttpsUrl(value) || host == null) {
				return false;
			}
			String normalized = host.toLowerCase(java.util.Locale.ROOT);
			return normalized.equals("facebook.com") || normalized.endsWith(".facebook.com")
					|| normalized.equals("fb.com") || normalized.endsWith(".fb.com");
		}
		catch (URISyntaxException e) {
			return false;
		}
	}

	private static boolean isYouTubeUrl(String value) {
		try {
			URI uri = new URI(value);
			String host = uri.getHost();
			if (!isValidHttpsUrl(value) || host == null) {
				return false;
			}
			String normalized = host.toLowerCase(java.util.Locale.ROOT);
			return normalized.equals("youtube.com") || normalized.endsWith(".youtube.com")
					|| normalized.equals("youtu.be");
		}
		catch (URISyntaxException e) {
			return false;
		}
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
						(value, batch) -> value instanceof String s && CURRENCY_CODE_PATTERN.matcher(s).matches()),
				// SET-PAY-01: institute public information (Klass parity). All
				// optional and public - shown on the tenant's public home page.
				ConfigPropertyDefinition.of(ConfigDomain.GENERAL, "address_line1", ConfigValueType.STRING, null,
						ConfigPropertyRegistry::isShortText),
				ConfigPropertyDefinition.of(ConfigDomain.GENERAL, "address_line2", ConfigValueType.STRING, null,
						ConfigPropertyRegistry::isShortText),
				ConfigPropertyDefinition.of(ConfigDomain.GENERAL, "city", ConfigValueType.STRING, null,
						ConfigPropertyRegistry::isShortText),
				ConfigPropertyDefinition.of(ConfigDomain.GENERAL, "postal_code", ConfigValueType.STRING, null,
						ConfigPropertyRegistry::isShortText),
				ConfigPropertyDefinition.of(ConfigDomain.GENERAL, "institute_introduction", ConfigValueType.STRING,
						null, (value, batch) -> value instanceof String s && !s.isBlank()
								&& s.length() <= MAX_LONG_TEXT_LENGTH),
				ConfigPropertyDefinition.of(ConfigDomain.GENERAL, "facebook_url", ConfigValueType.STRING, null,
						(value, batch) -> value instanceof String s && s.length() <= MAX_URL_LENGTH
								&& isFacebookUrl(s)),
				ConfigPropertyDefinition.of(ConfigDomain.GENERAL, "youtube_url", ConfigValueType.STRING, null,
						(value, batch) -> value instanceof String s && s.length() <= MAX_URL_LENGTH
								&& isYouTubeUrl(s)),
				// LOGIN-01 Phase B: the institute's own policies, plain text,
				// published on /terms, /refund-policy and /privacy.
				policyProperty("terms_of_service"), policyProperty("refund_policy"),
				policyProperty("privacy_policy"),
				// ADR-031 login options. Email sign-in has no switch (an admin
				// without a mobile can never be locked out).
				ConfigPropertyDefinition.of(ConfigDomain.GENERAL, "mobile_login_enabled", ConfigValueType.BOOLEAN,
						Boolean.TRUE, ConfigPropertyRegistry::isBoolean),
				// Region for numbers written without a country code (ISO 3166-1
				// alpha-2 known to libphonenumber, e.g. LK, IN, GB).
				ConfigPropertyDefinition.of(ConfigDomain.GENERAL, "phone_region", ConfigValueType.STRING, "LK",
						(value, batch) -> value instanceof String s
								&& com.google.i18n.phonenumbers.PhoneNumberUtil.getInstance()
									.getSupportedRegions()
									.contains(s)));
	}

	private static ConfigPropertyDefinition policyProperty(String key) {
		return ConfigPropertyDefinition.of(ConfigDomain.GENERAL, key, ConfigValueType.STRING, null,
				(value, batch) -> value instanceof String s && !s.isBlank() && s.length() <= MAX_POLICY_LENGTH);
	}

	private static List<ConfigPropertyDefinition> brandingProperties() {
		return List.of(
				ConfigPropertyDefinition.of(ConfigDomain.BRANDING, "primary_color", ConfigValueType.STRING, null,
						ConfigPropertyRegistry::validatePrimaryColor),
				ConfigPropertyDefinition.of(ConfigDomain.BRANDING, "secondary_color", ConfigValueType.STRING, null,
						ConfigPropertyRegistry::validateSecondaryColor),
				ConfigPropertyDefinition.of(ConfigDomain.BRANDING, "accent_color", ConfigValueType.STRING, null,
						(value, batch) -> isHexColor(value)),
				ConfigPropertyDefinition.of(ConfigDomain.BRANDING, "background_color", ConfigValueType.STRING, null,
						(value, batch) -> isHexColor(value)),
				ConfigPropertyDefinition.of(ConfigDomain.BRANDING, "surface_color", ConfigValueType.STRING, null,
						(value, batch) -> isHexColor(value)),
				ConfigPropertyDefinition.of(ConfigDomain.BRANDING, "text_color", ConfigValueType.STRING, null,
						ConfigPropertyRegistry::validateTextColor),
				ConfigPropertyDefinition.of(ConfigDomain.BRANDING, "logo_url", ConfigValueType.STRING, null,
						(value, batch) -> value instanceof String s && s.length() <= MAX_URL_LENGTH && isValidHttpUrl(s)),
				ConfigPropertyDefinition.of(ConfigDomain.BRANDING, "favicon_url", ConfigValueType.STRING, null,
						(value, batch) -> value instanceof String s && s.length() <= MAX_URL_LENGTH && isValidHttpUrl(s)),
				ConfigPropertyDefinition.of(ConfigDomain.BRANDING, "receipt_logo_url", ConfigValueType.STRING, null,
						(value, batch) -> value instanceof String s && s.length() <= MAX_URL_LENGTH && isValidHttpUrl(s)),
				ConfigPropertyDefinition.of(ConfigDomain.BRANDING, "homepage_url", ConfigValueType.STRING, null,
						(value, batch) -> value instanceof String s && s.length() <= MAX_URL_LENGTH && isValidHttpUrl(s)),
				ConfigPropertyDefinition.of(ConfigDomain.BRANDING, "public_page_enabled", ConfigValueType.BOOLEAN, Boolean.TRUE,
						ConfigPropertyRegistry::isBoolean),
				ConfigPropertyDefinition.of(ConfigDomain.BRANDING, "sub_hero_enabled", ConfigValueType.BOOLEAN, Boolean.TRUE,
						ConfigPropertyRegistry::isBoolean),
				ConfigPropertyDefinition.of(ConfigDomain.BRANDING, "cta_enabled", ConfigValueType.BOOLEAN, Boolean.TRUE,
						ConfigPropertyRegistry::isBoolean),
				ConfigPropertyDefinition.of(ConfigDomain.BRANDING, "featured_course_ids", ConfigValueType.JSON, List.of(),
						ConfigPropertyRegistry::isUuidList),
				ConfigPropertyDefinition.of(ConfigDomain.BRANDING, "support_video_url", ConfigValueType.STRING, null,
						(value, batch) -> value instanceof String s && s.length() <= MAX_URL_LENGTH && isYouTubeUrl(s)),
				ConfigPropertyDefinition.of(ConfigDomain.BRANDING, "desktop_banner_urls", ConfigValueType.JSON, List.of(),
						ConfigPropertyRegistry::isUrlList),
				ConfigPropertyDefinition.of(ConfigDomain.BRANDING, "mobile_banner_urls", ConfigValueType.JSON, List.of(),
						ConfigPropertyRegistry::isUrlList),
				ConfigPropertyDefinition.of(ConfigDomain.BRANDING, "header_font_family", ConfigValueType.STRING,
						DEFAULT_APPEARANCE_FONT, (value, batch) -> value instanceof String s && APPEARANCE_FONTS.contains(s)),
				ConfigPropertyDefinition.of(ConfigDomain.BRANDING, "body_font_family", ConfigValueType.STRING,
						DEFAULT_APPEARANCE_FONT, (value, batch) -> value instanceof String s && APPEARANCE_FONTS.contains(s)),
				ConfigPropertyDefinition.of(ConfigDomain.BRANDING, "logo_display_size", ConfigValueType.STRING,
						DEFAULT_LOGO_DISPLAY_SIZE,
						(value, batch) -> value instanceof String s && LOGO_DISPLAY_SIZES.contains(s)),
				ConfigPropertyDefinition.of(ConfigDomain.BRANDING, "teacher_auto_rotate", ConfigValueType.BOOLEAN,
						Boolean.FALSE, ConfigPropertyRegistry::isBoolean));
	}

	private static boolean isHexColor(Object value) {
		return value instanceof String s && HEX_COLOR_PATTERN.matcher(s).matches();
	}

	/**
	 * {@code primary_color}: hex, and - when a valid {@code background_color}
	 * is saved in the same batch - at least {@value #MIN_UI_CONTRAST_RATIO}:1
	 * against it (buttons/links must stay visible on the public page). Like
	 * {@link #validateSecondaryColor}, a single-key update is not cross-checked
	 * against the already-persisted value.
	 */
	private static boolean validatePrimaryColor(Object value, Map<String, Object> batch) {
		if (!isHexColor(value)) {
			return false;
		}
		Object background = batch.get("background_color");
		return !isHexColor(background)
				|| WcagContrast.ratio((String) value, (String) background) >= MIN_UI_CONTRAST_RATIO;
	}

	/**
	 * {@code text_color}: hex, and at least {@value #MIN_AA_CONTRAST_RATIO}:1
	 * (WCAG AA body text) against {@code background_color} and against
	 * {@code surface_color}, each checked only when that key carries a valid
	 * hex in the same batch.
	 */
	private static boolean validateTextColor(Object value, Map<String, Object> batch) {
		if (!isHexColor(value)) {
			return false;
		}
		String text = (String) value;
		for (String key : List.of("background_color", "surface_color")) {
			Object other = batch.get(key);
			if (isHexColor(other) && WcagContrast.ratio(text, (String) other) < MIN_AA_CONTRAST_RATIO) {
				return false;
			}
		}
		return true;
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
