package com.lms.tenantmanagement.web;

import com.lms.common.api.ApiResponse;
import com.lms.common.tenant.TenantContext;
import com.lms.coursemanagement.api.CourseLookupApi;
import com.lms.coursemanagement.api.PublishedCourseCard;
import com.lms.tenantmanagement.api.ConfigDomain;
import com.lms.tenantmanagement.api.TenantAssetType;
import com.lms.tenantmanagement.api.TenantConfigApi;
import com.lms.tenantmanagement.config.ConfigPropertyRegistry;
import com.lms.tenantmanagement.service.TenantAssetService;
import com.lms.tenantmanagement.web.dto.PublicHomeResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Anonymous, tenant-host-scoped public home projection. The tenant is the one
 * resolved from the request host by the shared tenant-resolution filter; this
 * endpoint accepts no tenant id. Only allowlisted, public-facing fields are
 * projected (see {@link PublicHomeResponse}).
 */
@RestController
@RequestMapping("/api/v1/public/tenant-config/home")
public class PublicHomeController {

	/** Featured course cover URLs share the public asset lifetime. */
	private static final Duration PUBLIC_URL_TTL = Duration.ofMinutes(60);

	private final TenantConfigApi tenantConfigApi;

	private final TenantContext tenantContext;

	private final CourseLookupApi courseLookupApi;

	private final TenantAssetService tenantAssetService;

	public PublicHomeController(TenantConfigApi tenantConfigApi, TenantContext tenantContext,
			CourseLookupApi courseLookupApi, TenantAssetService tenantAssetService) {
		this.tenantConfigApi = tenantConfigApi;
		this.tenantContext = tenantContext;
		this.courseLookupApi = courseLookupApi;
		this.tenantAssetService = tenantAssetService;
	}

	@GetMapping
	public ResponseEntity<ApiResponse<PublicHomeResponse>> getHome() {
		UUID tenantId = tenantContext.getTenantId();
		Map<String, Object> branding = tenantConfigApi.resolveDomain(tenantId, ConfigDomain.BRANDING);
		Map<String, Object> general = tenantConfigApi.resolveDomain(tenantId, ConfigDomain.GENERAL);
		PublicHomeResponse response = new PublicHomeResponse(
				booleanValue(branding, "public_page_enabled", true),
				stringValue(general, "institute_name", ""),
				firstAssetUrl(TenantAssetType.SITE_LOGO, stringValue(branding, "logo_url", null)),
				firstAssetUrl(TenantAssetType.SITE_FAVICON, stringValue(branding, "favicon_url", null)),
				stringValue(branding, "homepage_url", null),
				booleanValue(branding, "sub_hero_enabled", true),
				booleanValue(branding, "cta_enabled", true),
				stringValue(branding, "support_video_url", null),
				bannerUrls(TenantAssetType.DESKTOP_HERO_BANNER, branding, "desktop_banner_urls"),
				bannerUrls(TenantAssetType.MOBILE_HERO_BANNER, branding, "mobile_banner_urls"),
				featuredCourses(branding),
				theme(branding),
				allowlisted(branding, "logo_display_size", ConfigPropertyRegistry.LOGO_DISPLAY_SIZES,
						ConfigPropertyRegistry.DEFAULT_LOGO_DISPLAY_SIZE),
				booleanValue(branding, "teacher_auto_rotate", false),
				stringValue(general, "support_email", null),
				stringValue(general, "support_phone", null),
				new PublicHomeResponse.Institute(stringValue(general, "institute_introduction", null),
						stringValue(general, "address_line1", null), stringValue(general, "address_line2", null),
						stringValue(general, "city", null), stringValue(general, "postal_code", null),
						stringValue(general, "facebook_url", null), stringValue(general, "youtube_url", null)),
				new PublicHomeResponse.Policies(published(general, "terms_of_service"),
						published(general, "refund_policy"), published(general, "privacy_policy")),
				new PublicHomeResponse.LoginOptions(booleanValue(general, "mobile_login_enabled", true)));
		return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(ApiResponse.success(response));
	}

	private static PublicHomeResponse.Theme theme(Map<String, Object> branding) {
		return new PublicHomeResponse.Theme(stringValue(branding, "primary_color", null),
				stringValue(branding, "accent_color", null), stringValue(branding, "background_color", null),
				stringValue(branding, "surface_color", null), stringValue(branding, "text_color", null),
				allowlisted(branding, "header_font_family", ConfigPropertyRegistry.APPEARANCE_FONTS,
						ConfigPropertyRegistry.DEFAULT_APPEARANCE_FONT),
				allowlisted(branding, "body_font_family", ConfigPropertyRegistry.APPEARANCE_FONTS,
						ConfigPropertyRegistry.DEFAULT_APPEARANCE_FONT));
	}

	private String firstAssetUrl(TenantAssetType type, String fallback) {
		return tenantAssetService.publicAssetUrls(type).stream().findFirst().orElse(fallback);
	}

	/** Uploaded banner assets (oldest first) win; the legacy URL list is only a fallback when none exist. */
	private List<String> bannerUrls(TenantAssetType type, Map<String, Object> branding, String legacyKey) {
		List<String> uploaded = tenantAssetService.publicAssetUrls(type);
		return uploaded.isEmpty() ? stringListValue(branding, legacyKey) : uploaded;
	}

	private List<PublicHomeResponse.FeaturedCourse> featuredCourses(Map<String, Object> branding) {
		Object raw = branding.get("featured_course_ids");
		if (!(raw instanceof List<?> values)) {
			return List.of();
		}
		List<UUID> ids = values.stream().filter(String.class::isInstance).map(String.class::cast).map(PublicHomeController::parseUuid)
			.filter(Objects::nonNull).toList();
		return courseLookupApi.getPublishedCourseCards(ids, PUBLIC_URL_TTL).stream()
			.map(PublicHomeController::toFeaturedCourse)
			.toList();
	}

	private static PublicHomeResponse.FeaturedCourse toFeaturedCourse(PublishedCourseCard course) {
		return new PublicHomeResponse.FeaturedCourse(course.id(), course.name(), course.slug(), course.category(),
				course.pricingModel(), course.amount(), course.currency(), course.requiresManualQuote(),
				course.coverImageUrl());
	}

	private static UUID parseUuid(String value) {
		try {
			return UUID.fromString(value);
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

	private static String allowlisted(Map<String, Object> values, String key, List<String> allowed, String fallback) {
		Object value = values.get(key);
		return value instanceof String s && allowed.contains(s) ? s : fallback;
	}

	private static boolean published(Map<String, Object> values, String key) {
		return values.get(key) instanceof String s && !s.isBlank();
	}

	private static boolean booleanValue(Map<String, Object> values, String key, boolean fallback) {
		Object value = values.get(key);
		return value instanceof Boolean ? (Boolean) value : fallback;
	}

	private static String stringValue(Map<String, Object> values, String key, String fallback) {
		Object value = values.get(key);
		return value instanceof String ? (String) value : fallback;
	}

	private static List<String> stringListValue(Map<String, Object> values, String key) {
		Object value = values.get(key);
		if (!(value instanceof List<?> list)) {
			return List.of();
		}
		return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
	}

}
