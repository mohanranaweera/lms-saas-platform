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
		String supportPhone) {

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

	public record FeaturedCourse(UUID id, String name, String slug, String category, String pricingModel,
			BigDecimal amount, String currency, boolean requiresManualQuote, String coverUrl) {
	}
}
