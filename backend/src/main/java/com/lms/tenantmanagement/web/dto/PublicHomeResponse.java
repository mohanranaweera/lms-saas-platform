package com.lms.tenantmanagement.web.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Allowlisted anonymous projection used by tenant public home pages. Every
 * field is deliberately public-facing; no private configuration, secret or
 * internal id beyond public course ids is ever added here.
 *
 * <p>Asset URLs ({@code logoUrl}, {@code faviconUrl}, banner lists) are
 * short-lived signed URLs (60 minutes) when they come from uploaded tenant
 * assets, or the tenant's legacy configured URL otherwise.
 */
public record PublicHomeResponse(
		boolean publicPageEnabled,
		String instituteName,
		String logoUrl,
		String faviconUrl,
		String homepageUrl,
		boolean subHeroEnabled,
		boolean ctaEnabled,
		String supportVideoUrl,
		List<String> desktopBannerUrls,
		List<String> mobileBannerUrls,
		List<FeaturedCourse> featuredCourses,
		Theme theme,
		String logoDisplaySize,
		boolean teacherAutoRotate,
		String supportEmail,
		String supportPhone,
		Institute institute,
		Policies policies,
		LoginOptions loginOptions,
		boolean showTodaysClasses,
		Analytics analytics,
		StudentOptions studentOptions) {

	public PublicHomeResponse {
		desktopBannerUrls = desktopBannerUrls == null ? List.of() : List.copyOf(desktopBannerUrls);
		mobileBannerUrls = mobileBannerUrls == null ? List.of() : List.copyOf(mobileBannerUrls);
		featuredCourses = featuredCourses == null ? List.of() : List.copyOf(featuredCourses);
	}

	/**
	 * Public-page theme tokens. Colors are the tenant's saved hex values, or
	 * {@code null} when unset (the client applies its documented default).
	 * Fonts are always resolved allowlist tokens (default {@code INTER}).
	 */
	public record Theme(String primaryColor, String accentColor, String backgroundColor, String surfaceColor,
			String textColor, String headingFont, String bodyFont) {
	}

	/**
	 * SET-PAY-01: the institute's public information (Settings -> General).
	 * Every field is {@code null} when unset. {@code introduction} is plain
	 * text; the social links are validated {@code https} URLs on allowlisted
	 * hosts ({@code facebook.com}/{@code fb.com}, {@code youtube.com}/{@code youtu.be}).
	 */
	public record Institute(String introduction, String addressLine1, String addressLine2, String city,
			String postalCode, String facebookUrl, String youtubeUrl) {
	}

	/**
	 * LOGIN-01 Phase B: which institute policies are published (non-empty).
	 * Only flags - the text itself is served by {@code PublicPolicyController}.
	 */
	public record Policies(boolean termsOfService, boolean refundPolicy, boolean privacyPolicy) {
	}

	/** ADR-031: whether the login page offers the MOBILE tab ({@code GENERAL.mobile_login_enabled}). */
	public record LoginOptions(boolean mobileEnabled) {
	}

	/**
	 * SET-CFG-01: the tenant's GA4 measurement id ({@code
	 * GENERAL.ga_measurement_id}), or {@code null}. Public by nature - gtag puts
	 * it in every tracked page's source.
	 */
	public record Analytics(String gaMeasurementId) {
	}

	/**
	 * SET-CFG-01: whether the student UI offers self-enrollment ({@code
	 * STUDENT.self_enrollment_enabled}). UX only - the enroll endpoint enforces it.
	 */
	public record StudentOptions(boolean selfEnrollmentEnabled) {
	}

	public record FeaturedCourse(UUID id, String name, String slug, String category, String pricingModel,
			BigDecimal amount, String currency, boolean requiresManualQuote, String coverUrl) {
	}
}
