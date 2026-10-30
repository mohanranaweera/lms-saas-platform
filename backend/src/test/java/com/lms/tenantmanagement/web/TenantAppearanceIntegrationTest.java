package com.lms.tenantmanagement.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.lms.coursemanagement.CourseManagementTestSupport;
import com.lms.coursemanagement.course.domain.CourseStatus;
import com.lms.coursemanagement.course.web.dto.CourseResponse;
import com.lms.identityaccessservice.domain.Role;
import com.lms.identityaccessservice.domain.TenantUser;
import com.lms.integrationmanagement.InMemoryObjectStorageApiTestConfig;
import com.lms.integrationmanagement.InMemoryObjectStorageApiTestConfig.InMemoryObjectStorageApi;
import com.lms.tenantmanagement.domain.Tenant;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * Tenant appearance / public home (docs/specs/tenant-appearance-public-home-parity.md
 * section 3): tenant asset limits, gallery semantics and listing, admin-only
 * writes, and the anonymous public home projection (theme fields, banner
 * assets over legacy URLs, featured courses limited to the host tenant's
 * published courses, no other tenant's assets).
 */
@Import(InMemoryObjectStorageApiTestConfig.class)
class TenantAppearanceIntegrationTest extends CourseManagementTestSupport {

	private static final String ASSETS = "/api/v1/tenant-assets";

	private static final String HOME = "/api/v1/public/tenant-config/home";

	private static final byte[] PNG_BYTES = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 13 };

	@Autowired
	private InMemoryObjectStorageApi storage;

	private record Ctx(Tenant tenant, String host, String token, TenantUser teacher) {

	}

	private Ctx setUp(String prefix) {
		Tenant tenant = seedActiveTenant(uniqueSubdomain(prefix));
		seedTenantUser(tenant.getId(), "admin@example.test", RAW_PASSWORD, Role.TENANT_ADMIN);
		TenantUser teacher = seedTenantUser(tenant.getId(), "teacher@example.test", RAW_PASSWORD, Role.TEACHER);
		String host = hostFor(tenant.getSubdomain());
		return new Ctx(tenant, host, loginAndGetToken(host, "admin@example.test"), teacher);
	}

	// ------------------------------------------------------------------
	// Tenant assets: size limits, singletons, galleries.
	// ------------------------------------------------------------------

	@Test
	void perTypeSizeLimitsAreEnforcedBeforeAnyStorageWrite() {
		Ctx ctx = setUp("asset-size");
		Set<String> before = new HashSet<>(storage.keySet());

		assertThat(upload(ctx, "SITE_FAVICON", png(1024 * 1024 + 1)).getResponse().getStatus()).isEqualTo(413);
		assertThat(upload(ctx, "SITE_LOGO", png(2 * 1024 * 1024 + 1)).getResponse().getStatus()).isEqualTo(413);
		assertThat(upload(ctx, "RECEIPT_LOGO", png(2 * 1024 * 1024 + 1)).getResponse().getStatus()).isEqualTo(413);
		assertThat(upload(ctx, "DESKTOP_HERO_BANNER", png(2 * 1024 * 1024 + 1)).getResponse().getStatus())
			.isEqualTo(413);
		assertThat(storage.keySet()).isEqualTo(before);

		// Just over the favicon limit is still fine for a logo (2 MB).
		MvcResult logo = upload(ctx, "SITE_LOGO", png(1024 * 1024 + 1));
		assertThat(logo.getResponse().getStatus()).as(rawContent(logo)).isEqualTo(201);
		assertThat(upload(ctx, "SITE_FAVICON", png(1024 * 1024)).getResponse().getStatus()).isEqualTo(201);
	}

	@Test
	void profileImagePurposesAreRejectedWithoutStoring() {
		Ctx ctx = setUp("asset-profile");
		Set<String> before = new HashSet<>(storage.keySet());

		assertThat(upload(ctx, "TEACHER_PROFILE_IMAGE", PNG_BYTES).getResponse().getStatus()).isEqualTo(400);
		assertThat(storage.keySet()).isEqualTo(before);
	}

	@Test
	void singletonUploadReplacesThePreviousAsset() {
		Ctx ctx = setUp("asset-singleton");
		Set<String> before = new HashSet<>(storage.keySet());
		UUID first = id(upload(ctx, "SITE_LOGO", PNG_BYTES));
		String firstKey = newKey(before);

		UUID second = id(upload(ctx, "SITE_LOGO", PNG_BYTES));

		assertThat(second).isNotEqualTo(first);
		assertThat(liveCount(ctx.tenant(), "SITE_LOGO")).isEqualTo(1L);
		assertThat(jdbcTemplate.queryForObject("SELECT deleted_at IS NOT NULL FROM tenant_asset WHERE id = ?",
				Boolean.class, first)).isTrue();
		assertThat(storage.containsKey(firstKey)).as("replaced object removed after commit").isFalse();
		JsonNode list = json(list(ctx.host(), ctx.token(), "SITE_LOGO")).path("data");
		assertThat(list.size()).isEqualTo(1);
		assertThat(list.get(0).path("id").asString()).isEqualTo(second.toString());
	}

	@Test
	void bannersAccumulateOldestFirstAndAreCappedAtSixPerType() {
		Ctx ctx = setUp("asset-banner");
		List<UUID> uploaded = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			MvcResult result = upload(ctx, "DESKTOP_HERO_BANNER", PNG_BYTES);
			assertThat(result.getResponse().getStatus()).as(rawContent(result)).isEqualTo(201);
			uploaded.add(id(result));
		}
		assertThat(liveCount(ctx.tenant(), "DESKTOP_HERO_BANNER")).as("second upload did not replace the first")
			.isEqualTo(6L);
		Set<String> before = new HashSet<>(storage.keySet());

		MvcResult seventh = upload(ctx, "DESKTOP_HERO_BANNER", PNG_BYTES);

		assertThat(seventh.getResponse().getStatus()).isEqualTo(422);
		assertThat(json(seventh).path("error").path("code").asString()).isEqualTo("VALIDATION_ERROR");
		assertThat(storage.keySet()).isEqualTo(before);
		assertThat(liveCount(ctx.tenant(), "DESKTOP_HERO_BANNER")).isEqualTo(6L);
		// The cap is per type: a mobile banner is still accepted.
		assertThat(upload(ctx, "MOBILE_HERO_BANNER", PNG_BYTES).getResponse().getStatus()).isEqualTo(201);

		JsonNode list = json(list(ctx.host(), ctx.token(), "DESKTOP_HERO_BANNER")).path("data");
		List<String> ids = new ArrayList<>();
		list.forEach(node -> {
			ids.add(node.path("id").asString());
			assertThat(node.path("previewUrl").asString()).startsWith("https://test-object-storage.invalid/");
		});
		assertThat(ids).containsExactlyElementsOf(uploaded.stream().map(UUID::toString).toList());

		// Deleting one frees a slot; by-type still answers with several live banners.
		MvcResult deleted = perform(authenticated(delete(ASSETS + "/{id}", uploaded.get(0)), ctx.host(), ctx.token()));
		assertThat(deleted.getResponse().getStatus()).isEqualTo(200);
		assertThat(upload(ctx, "DESKTOP_HERO_BANNER", PNG_BYTES).getResponse().getStatus()).isEqualTo(201);
		MvcResult byType = perform(
				authenticated(get(ASSETS + "/by-type/{type}", "DESKTOP_HERO_BANNER"), ctx.host(), ctx.token()));
		assertThat(byType.getResponse().getStatus()).isEqualTo(200);
	}

	@Test
	@Tag("cross-tenant")
	void listingAndAssetIdsAreScopedToTheCallersTenant() {
		Ctx a = setUp("asset-xt-a");
		Ctx b = setUp("asset-xt-b");
		UUID aBanner = id(upload(a, "DESKTOP_HERO_BANNER", PNG_BYTES));
		id(upload(a, "DESKTOP_HERO_BANNER", PNG_BYTES));
		UUID bBanner = id(upload(b, "DESKTOP_HERO_BANNER", PNG_BYTES));

		JsonNode aList = json(list(a.host(), a.token(), "DESKTOP_HERO_BANNER")).path("data");
		JsonNode bList = json(list(b.host(), b.token(), "DESKTOP_HERO_BANNER")).path("data");
		assertThat(aList.size()).isEqualTo(2);
		assertThat(bList.size()).isEqualTo(1);
		assertThat(bList.get(0).path("id").asString()).isEqualTo(bBanner.toString());
		assertThat(rawContent(list(b.host(), b.token(), "DESKTOP_HERO_BANNER"))).doesNotContain(aBanner.toString());

		assertThat(perform(authenticated(get(ASSETS + "/{id}/preview-url", aBanner), b.host(), b.token()))
			.getResponse()
			.getStatus()).isEqualTo(404);
		assertThat(perform(authenticated(delete(ASSETS + "/{id}", aBanner), b.host(), b.token())).getResponse()
			.getStatus()).isEqualTo(404);
		assertThat(liveCount(a.tenant(), "DESKTOP_HERO_BANNER")).isEqualTo(2L);
	}

	// ------------------------------------------------------------------
	// Authorization: only branding admins may write or list.
	// ------------------------------------------------------------------

	@Test
	void studentCannotUploadListAssetsOrSaveBranding() {
		Ctx ctx = setUp("asset-student");
		seedTenantUser(ctx.tenant().getId(), "student@example.test", RAW_PASSWORD, Role.STUDENT);
		String studentToken = loginAndGetToken(ctx.host(), "student@example.test");
		Ctx asStudent = new Ctx(ctx.tenant(), ctx.host(), studentToken, ctx.teacher());
		Set<String> before = new HashSet<>(storage.keySet());

		assertThat(upload(asStudent, "SITE_LOGO", PNG_BYTES).getResponse().getStatus()).isEqualTo(403);
		assertThat(upload(asStudent, "DESKTOP_HERO_BANNER", PNG_BYTES).getResponse().getStatus()).isEqualTo(403);
		assertThat(list(ctx.host(), studentToken, "DESKTOP_HERO_BANNER").getResponse().getStatus()).isEqualTo(403);
		assertThat(putBranding(ctx.host(), studentToken, Map.of("primary_color", "#000000")).getResponse()
			.getStatus()).isEqualTo(403);
		assertThat(storage.keySet()).isEqualTo(before);
		assertThat(liveCount(ctx.tenant(), "SITE_LOGO")).isZero();
	}

	@Test
	void teacherCannotUploadOrListAssets() {
		Ctx ctx = setUp("asset-teacher");
		String teacherToken = loginAndGetToken(ctx.host(), "teacher@example.test");

		assertThat(upload(new Ctx(ctx.tenant(), ctx.host(), teacherToken, ctx.teacher()), "SITE_LOGO", PNG_BYTES)
			.getResponse()
			.getStatus()).isEqualTo(403);
		assertThat(list(ctx.host(), teacherToken, "SITE_LOGO").getResponse().getStatus()).isEqualTo(403);
	}

	@Test
	void anonymousCannotListAssets() {
		Ctx ctx = setUp("asset-anon");
		assertThat(list(ctx.host(), null, "SITE_LOGO").getResponse().getStatus()).isEqualTo(401);
	}

	@Test
	void invalidThemeIsRejectedOverHttp() {
		Ctx ctx = setUp("theme-invalid");

		assertThat(putBranding(ctx.host(), ctx.token(), Map.of("header_font_family", "Comic Sans MS")).getResponse()
			.getStatus()).isEqualTo(400);
		assertThat(putBranding(ctx.host(), ctx.token(),
				Map.of("text_color", "#777777", "background_color", "#888888")).getResponse().getStatus())
			.isEqualTo(400);
		assertThat(putBranding(ctx.host(), ctx.token(), Map.of("logo_display_size", "HUGE")).getResponse()
			.getStatus()).isEqualTo(400);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM tenant_config_entry WHERE tenant_id = ? AND config_domain = 'BRANDING'",
				Long.class, ctx.tenant().getId())).isZero();
	}

	// ------------------------------------------------------------------
	// Public home projection.
	// ------------------------------------------------------------------

	// ------------------------------------------------------------------
	// SET-PAY-01: institute public information.
	// ------------------------------------------------------------------

	@Test
	void freshTenantHomeHasAnEmptyInstituteBlock() {
		Ctx ctx = setUp("home-inst-empty");

		JsonNode institute = json(perform(authenticated(get(HOME), ctx.host(), null))).path("data").path("institute");

		for (String field : List.of("introduction", "addressLine1", "addressLine2", "city", "postalCode",
				"facebookUrl", "youtubeUrl")) {
			assertThat(institute.path(field).isNull()).as(field).isTrue();
		}
	}

	@Test
	@Tag("cross-tenant")
	void homeProjectsOnlyTheHostTenantsInstituteInformation() {
		Ctx tenantA = setUp("home-inst-a");
		Ctx tenantB = setUp("home-inst-b");
		Map<String, Object> info = new LinkedHashMap<>();
		info.put("institute_introduction", "We teach physics.\nSince 2001.");
		info.put("address_line1", "School Junction");
		info.put("address_line2", "Kodapaluwa");
		info.put("city", "Alawwa");
		info.put("postal_code", "60280");
		info.put("facebook_url", "https://www.facebook.com/demo");
		info.put("youtube_url", "https://www.youtube.com/@demo");
		assertThat(putGeneral(tenantA.host(), tenantA.token(), info).getResponse().getStatus()).isEqualTo(200);

		JsonNode a = json(perform(authenticated(get(HOME), tenantA.host(), null))).path("data").path("institute");
		assertThat(a.path("introduction").asString()).isEqualTo("We teach physics.\nSince 2001.");
		assertThat(a.path("addressLine1").asString()).isEqualTo("School Junction");
		assertThat(a.path("addressLine2").asString()).isEqualTo("Kodapaluwa");
		assertThat(a.path("city").asString()).isEqualTo("Alawwa");
		assertThat(a.path("postalCode").asString()).isEqualTo("60280");
		assertThat(a.path("facebookUrl").asString()).isEqualTo("https://www.facebook.com/demo");
		assertThat(a.path("youtubeUrl").asString()).isEqualTo("https://www.youtube.com/@demo");

		JsonNode b = json(perform(authenticated(get(HOME), tenantB.host(), null))).path("data").path("institute");
		assertThat(b.path("addressLine1").isNull()).isTrue();
		assertThat(b.path("introduction").isNull()).isTrue();
	}

	// ------------------------------------------------------------------
	// LOGIN-01 Phase B: institute policies.
	// ------------------------------------------------------------------

	private static final String POLICIES = "/api/v1/public/tenant-config/policies/{kind}";

	@Test
	void unpublishedPoliciesAreFlaggedFalseAndReadAsNullAnonymously() {
		Ctx ctx = setUp("policy-empty");

		JsonNode flags = json(perform(authenticated(get(HOME), ctx.host(), null))).path("data").path("policies");
		assertThat(flags.path("termsOfService").asBoolean()).isFalse();
		assertThat(flags.path("refundPolicy").asBoolean()).isFalse();
		assertThat(flags.path("privacyPolicy").asBoolean()).isFalse();
		for (String kind : List.of("terms", "refund", "privacy")) {
			MvcResult result = perform(authenticated(get(POLICIES, kind), ctx.host(), null));
			assertThat(result.getResponse().getStatus()).as(kind).isEqualTo(200);
			assertThat(json(result).path("data").path("kind").asString()).isEqualTo(kind);
			assertThat(json(result).path("data").path("text").isNull()).as(kind).isTrue();
		}
	}

	@Test
	void publishedPoliciesAreServedAsPlainTextWithFlags() {
		Ctx ctx = setUp("policy-set");
		Map<String, Object> policies = new LinkedHashMap<>();
		policies.put("terms_of_service", "1. Be kind.\n2. <b>No</b> sharing accounts.");
		policies.put("privacy_policy", "We keep your data in Sri Lanka.");
		assertThat(putGeneral(ctx.host(), ctx.token(), policies).getResponse().getStatus()).isEqualTo(200);

		JsonNode flags = json(perform(authenticated(get(HOME), ctx.host(), null))).path("data").path("policies");
		assertThat(flags.path("termsOfService").asBoolean()).isTrue();
		assertThat(flags.path("refundPolicy").asBoolean()).isFalse();
		assertThat(flags.path("privacyPolicy").asBoolean()).isTrue();
		MvcResult terms = perform(authenticated(get(POLICIES, "terms"), ctx.host(), null));
		assertThat(json(terms).path("data").path("text").asString())
			.isEqualTo("1. Be kind.\n2. <b>No</b> sharing accounts.");
		assertThat(terms.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-cache");
	}

	@Test
	void unknownPolicyKindIs404() {
		Ctx ctx = setUp("policy-unknown");

		assertThat(perform(authenticated(get(POLICIES, "cookies"), ctx.host(), null)).getResponse().getStatus())
			.isEqualTo(404);
		assertThat(perform(authenticated(get(POLICIES, "institute_name"), ctx.host(), null)).getResponse().getStatus())
			.isEqualTo(404);
	}

	@Test
	@Tag("cross-tenant")
	void policiesAreServedOnlyForTheHostTenant() {
		Ctx tenantA = setUp("policy-cross-a");
		Ctx tenantB = setUp("policy-cross-b");
		putGeneral(tenantA.host(), tenantA.token(), Map.of("refund_policy", "Tenant A refunds within 7 days."));

		assertThat(json(perform(authenticated(get(POLICIES, "refund"), tenantA.host(), null))).path("data")
			.path("text")
			.asString()).isEqualTo("Tenant A refunds within 7 days.");
		assertThat(json(perform(authenticated(get(POLICIES, "refund"), tenantB.host(), null))).path("data")
			.path("text")
			.isNull()).isTrue();
		assertThat(json(perform(authenticated(get(HOME), tenantB.host(), null))).path("data")
			.path("policies")
			.path("refundPolicy")
			.asBoolean()).isFalse();
	}

	@Test
	void onlyTenantAdminCanPublishPolicies() {
		Ctx ctx = setUp("policy-roles");
		seedTenantUser(ctx.tenant().getId(), "auditor@example.test", RAW_PASSWORD, Role.READ_ONLY_AUDITOR);
		String auditorToken = loginAndGetToken(ctx.host(), "auditor@example.test");

		assertThat(putGeneral(ctx.host(), auditorToken, Map.of("privacy_policy", "x")).getResponse().getStatus())
			.isEqualTo(403);
		assertThat(rawContent(perform(authenticated(get(POLICIES, "privacy"), ctx.host(), null))))
			.contains("\"text\":null");
	}

	@Test
	void unsafeSocialLinksAreRejectedOverHttpAndNothingIsSaved() {
		Ctx ctx = setUp("home-inst-invalid");

		MvcResult result = putGeneral(ctx.host(), ctx.token(),
				Map.of("facebook_url", "javascript:alert(1)", "city", "Alawwa"));

		assertThat(result.getResponse().getStatus()).isEqualTo(400);
		assertThat(rawContent(result)).contains("facebook_url");
		JsonNode institute = json(perform(authenticated(get(HOME), ctx.host(), null))).path("data").path("institute");
		assertThat(institute.path("city").isNull()).isTrue();
	}

	@Test
	void studentCannotSaveInstituteInformation() {
		Ctx ctx = setUp("home-inst-student");
		seedActiveStudent(ctx.tenant().getId(), "student@example.test");
		String studentToken = loginAndGetToken(ctx.host(), "student@example.test");

		assertThat(putGeneral(ctx.host(), studentToken, Map.of("city", "Alawwa")).getResponse().getStatus())
			.isEqualTo(403);
	}

	@Test
	void freshTenantHomeResolvesThemeDefaults() {
		Ctx ctx = setUp("home-default");

		MvcResult result = perform(authenticated(get(HOME), ctx.host(), null));

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-cache");
		JsonNode data = json(result).path("data");
		JsonNode theme = data.path("theme");
		assertThat(theme.path("primaryColor").isNull()).isTrue();
		assertThat(theme.path("accentColor").isNull()).isTrue();
		assertThat(theme.path("backgroundColor").isNull()).isTrue();
		assertThat(theme.path("surfaceColor").isNull()).isTrue();
		assertThat(theme.path("textColor").isNull()).isTrue();
		assertThat(theme.path("headingFont").asString()).isEqualTo("INTER");
		assertThat(theme.path("bodyFont").asString()).isEqualTo("INTER");
		assertThat(data.path("logoDisplaySize").asString()).isEqualTo("STANDARD");
		assertThat(data.path("teacherAutoRotate").asBoolean()).isFalse();
		assertThat(data.path("supportEmail").isNull()).isTrue();
		assertThat(data.path("desktopBannerUrls").size()).isZero();
		assertThat(data.path("featuredCourses").size()).isZero();
	}

	@Test
	@Tag("cross-tenant")
	void homeProjectsThemeAssetsAndOnlyTheHostTenantsPublishedFeaturedCourses() {
		Ctx a = setUp("home-a");
		Ctx b = setUp("home-b");
		CourseResponse aPublished = createCourseOrFail(a.host(), a.token(),
				newCourseRequest(uniqueSlug("pub"), a.teacher().getId(), CourseStatus.PUBLIC));
		CourseResponse aDraft = createCourseOrFail(a.host(), a.token(),
				newCourseRequest(uniqueSlug("draft"), a.teacher().getId(), CourseStatus.DRAFT));
		CourseResponse aSecond = createCourseOrFail(a.host(), a.token(),
				newCourseRequest(uniqueSlug("pub2"), a.teacher().getId(), CourseStatus.PUBLIC));
		CourseResponse bPublished = createCourseOrFail(b.host(), b.token(),
				newCourseRequest(uniqueSlug("bpub"), b.teacher().getId(), CourseStatus.PUBLIC));

		Map<String, Object> brandingA = new LinkedHashMap<>();
		brandingA.put("primary_color", "#1D4ED8");
		brandingA.put("accent_color", "#F59E0B");
		brandingA.put("background_color", "#FFFFFF");
		brandingA.put("surface_color", "#F8FAFC");
		brandingA.put("text_color", "#0F172A");
		brandingA.put("header_font_family", "POPPINS");
		brandingA.put("body_font_family", "NOTO_SANS");
		brandingA.put("logo_display_size", "LARGE");
		brandingA.put("teacher_auto_rotate", true);
		brandingA.put("desktop_banner_urls", List.of("https://legacy.example.test/desktop.png"));
		brandingA.put("mobile_banner_urls", List.of("https://legacy.example.test/mobile.png"));
		// Configured order: second published, foreign tenant's course, draft, first published.
		brandingA.put("featured_course_ids", List.of(aSecond.id().toString(), bPublished.id().toString(),
				aDraft.id().toString(), aPublished.id().toString()));
		assertThat(putBranding(a.host(), a.token(), brandingA).getResponse().getStatus()).isEqualTo(200);
		assertThat(perform(authenticated(put("/api/v1/tenant-config/{domain}", "GENERAL")
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("institute_name", "Alpha Institute", "support_email",
					"help@alpha.example.test", "support_phone", "+94 11 000 0000"))),
				a.host(), a.token())).getResponse().getStatus()).isEqualTo(200);

		Set<String> beforeA = new HashSet<>(storage.keySet());
		upload(a, "DESKTOP_HERO_BANNER", PNG_BYTES);
		String aBannerKey1 = newKey(beforeA);
		Set<String> beforeA2 = new HashSet<>(storage.keySet());
		upload(a, "DESKTOP_HERO_BANNER", PNG_BYTES);
		String aBannerKey2 = newKey(beforeA2);
		Set<String> beforeB = new HashSet<>(storage.keySet());
		upload(b, "DESKTOP_HERO_BANNER", PNG_BYTES);
		upload(b, "SITE_LOGO", PNG_BYTES);
		upload(b, "MOBILE_HERO_BANNER", PNG_BYTES);
		Set<String> bKeys = new HashSet<>(storage.keySet());
		bKeys.removeAll(beforeB);
		assertThat(bKeys).hasSize(3);

		MvcResult result = perform(authenticated(get(HOME), a.host(), null));

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-cache");
		String body = rawContent(result);
		bKeys.forEach(key -> assertThat(body).as("tenant B asset leaked").doesNotContain(key));
		JsonNode data = json(result).path("data");

		JsonNode theme = data.path("theme");
		assertThat(theme.path("primaryColor").asString()).isEqualTo("#1D4ED8");
		assertThat(theme.path("accentColor").asString()).isEqualTo("#F59E0B");
		assertThat(theme.path("backgroundColor").asString()).isEqualTo("#FFFFFF");
		assertThat(theme.path("surfaceColor").asString()).isEqualTo("#F8FAFC");
		assertThat(theme.path("textColor").asString()).isEqualTo("#0F172A");
		assertThat(theme.path("headingFont").asString()).isEqualTo("POPPINS");
		assertThat(theme.path("bodyFont").asString()).isEqualTo("NOTO_SANS");
		assertThat(data.path("logoDisplaySize").asString()).isEqualTo("LARGE");
		assertThat(data.path("teacherAutoRotate").asBoolean()).isTrue();
		assertThat(data.path("instituteName").asString()).isEqualTo("Alpha Institute");
		assertThat(data.path("supportEmail").asString()).isEqualTo("help@alpha.example.test");
		assertThat(data.path("supportPhone").asString()).isEqualTo("+94 11 000 0000");
		assertThat(data.path("logoUrl").isNull()).as("tenant B's logo must not be used").isTrue();

		// Uploaded banners (oldest first) win over the legacy desktop URL list...
		JsonNode desktop = data.path("desktopBannerUrls");
		assertThat(desktop.size()).isEqualTo(2);
		assertThat(desktop.get(0).asString()).contains(aBannerKey1);
		assertThat(desktop.get(1).asString()).contains(aBannerKey2);
		assertThat(body).doesNotContain("legacy.example.test/desktop.png");
		// ...while the legacy mobile list is the fallback when no mobile banner asset exists.
		assertThat(data.path("mobileBannerUrls").size()).isEqualTo(1);
		assertThat(data.path("mobileBannerUrls").get(0).asString())
			.isEqualTo("https://legacy.example.test/mobile.png");

		JsonNode featured = data.path("featuredCourses");
		assertThat(featured.size()).as("foreign and draft courses dropped").isEqualTo(2);
		assertThat(featured.get(0).path("id").asString()).isEqualTo(aSecond.id().toString());
		assertThat(featured.get(1).path("id").asString()).isEqualTo(aPublished.id().toString());
		JsonNode card = featured.get(0);
		assertThat(card.path("pricingModel").asString()).isEqualTo("ONE_TIME");
		assertThat(card.path("amount").decimalValue()).isEqualByComparingTo(new BigDecimal("99.99"));
		assertThat(card.path("currency").asString()).isEqualTo("USD");
		assertThat(card.path("requiresManualQuote").asBoolean()).isFalse();
		assertThat(card.path("slug").asString()).isEqualTo(aSecond.slug());
		assertThat(body).doesNotContain(bPublished.id().toString()).doesNotContain(aDraft.id().toString());

		// Tenant B's own home shows its own assets and never A's.
		String bBody = rawContent(perform(authenticated(get(HOME), b.host(), null)));
		assertThat(bBody).doesNotContain(aBannerKey1).doesNotContain(aBannerKey2);
		JsonNode bData = json(perform(authenticated(get(HOME), b.host(), null))).path("data");
		assertThat(bData.path("desktopBannerUrls").size()).isEqualTo(1);
		assertThat(bData.path("logoUrl").asString()).startsWith("https://test-object-storage.invalid/");
		assertThat(bData.path("featuredCourses").size()).isZero();
	}

	// ------------------------------------------------------------------
	// Helpers.
	// ------------------------------------------------------------------

	private MvcResult upload(Ctx ctx, String type, byte[] bytes) {
		MockMultipartHttpServletRequestBuilder builder = multipart(ASSETS)
			.file(new MockMultipartFile("file", "image.png", "image/png", bytes));
		builder.param("type", type);
		builder.header(HttpHeaders.HOST, ctx.host());
		if (ctx.token() != null) {
			builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + ctx.token());
		}
		try {
			return mockMvc.perform(builder).andReturn();
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private MvcResult list(String host, String token, String type) {
		return perform(authenticated(get(ASSETS).param("type", type), host, token));
	}

	private MvcResult putGeneral(String host, String token, Map<String, Object> changes) {
		return perform(authenticated(put("/api/v1/tenant-config/{domain}", "GENERAL")
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(changes)), host, token));
	}

	private MvcResult putBranding(String host, String token, Map<String, Object> changes) {
		return perform(authenticated(put("/api/v1/tenant-config/{domain}", "BRANDING")
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(changes)), host, token));
	}

	private long liveCount(Tenant tenant, String type) {
		return jdbcTemplate.queryForObject(
				"SELECT count(*) FROM tenant_asset WHERE tenant_id = ? AND asset_type = ? AND deleted_at IS NULL",
				Long.class, tenant.getId(), type);
	}

	private UUID id(MvcResult result) {
		assertThat(result.getResponse().getStatus()).as(rawContent(result)).isEqualTo(201);
		return UUID.fromString(json(result).path("data").path("id").asString());
	}

	private String newKey(Set<String> before) {
		Set<String> after = new HashSet<>(storage.keySet());
		after.removeAll(before);
		assertThat(after).hasSize(1);
		return after.iterator().next();
	}

	private JsonNode json(MvcResult result) {
		return objectMapper.readTree(rawContent(result));
	}

	private static byte[] png(int size) {
		byte[] bytes = new byte[size];
		System.arraycopy(PNG_BYTES, 0, bytes, 0, PNG_BYTES.length);
		return bytes;
	}

}
