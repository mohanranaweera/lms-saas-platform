package com.lms.localizationmanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.lms.identityaccessservice.domain.Role;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

/**
 * I18N-01 section 2: locale resolution precedence, tenant language settings and the per-user,
 * per-tenant saved preference. Existing tenants (no settings rows) stay English.
 */
class LocaleResolutionIntegrationTest extends I18nTestSupport {

	private JsonNode catalog(String host, String token, String query, String header, Cookie cookie,
			String acceptLanguage) {
		var builder = get("/api/v1/public/i18n/catalog" + (query == null ? "" : "?" + query));
		if (header != null) {
			builder.header("X-LMS-Locale", header);
		}
		if (cookie != null) {
			builder.cookie(cookie);
		}
		if (acceptLanguage != null) {
			builder.header(HttpHeaders.ACCEPT_LANGUAGE, acceptLanguage);
		}
		MvcResult result = call(host, token, builder);
		assertThat(status(result)).as(rawContent(result)).isEqualTo(200);
		return data(result);
	}

	private static String resolved(JsonNode catalog) {
		return catalog.path("locale").asString() + "/" + catalog.path("resolvedFrom").asString();
	}

	@Test
	void existingTenantsOfferEveryLanguageButStayEnglishByDefault() {
		Fx fx = seed("i18n-default");
		MvcResult settings = call(fx.host(), null, get("/api/v1/public/i18n/settings"));
		assertThat(status(settings)).as(rawContent(settings)).isEqualTo(200);
		JsonNode data = data(settings);
		assertThat(data.path("enabledLocales").toString()).isEqualTo("[\"en-LK\",\"si-LK\",\"ta-LK\"]");
		assertThat(data.path("defaultLocale").asString()).isEqualTo("en-LK");
		assertThat(data.path("browserLanguageDetection").asBoolean()).isFalse();
		assertThat(data.path("supportedLocales").findValuesAsString("tag")).containsExactly("en-LK", "si-LK",
				"ta-LK");

		// With no choice made, visitors get the tenant default (English); Accept-Language is
		// ignored because browser detection is off by default.
		JsonNode catalog = catalog(fx.host(), null, "ns=common", null, null, "si-LK,si;q=0.9");
		assertThat(resolved(catalog)).isEqualTo("en-LK/TENANT_DEFAULT");
		assertThat(catalog.path("messages").path(SAVE_KEY).asString()).isEqualTo("Save");
		assertThat(catalog.path("namespaces").toString()).isEqualTo("[\"common\"]");
		assertThat(catalog.path("version").asString()).endsWith(".0");
		// An explicit choice of a (now enabled by default) language is honoured.
		assertThat(resolved(catalog(fx.host(), null, "locale=si-LK&ns=common", null, null, null)))
			.isEqualTo("si-LK/EXPLICIT");

		// Existing public endpoints still answer as before.
		MvcResult home = call(fx.host(), null, get("/api/v1/public/tenant-config/home"));
		assertThat(status(home)).as(rawContent(home)).isEqualTo(200);
		MvcResult courses = call(fx.host(), null, get("/api/v1/public/courses"));
		assertThat(status(courses)).as(rawContent(courses)).isEqualTo(200);
	}

	@Test
	void catalogResponseIsNoCacheAndVaried() {
		Fx fx = seed("i18n-headers");
		MvcResult result = publicCatalog(fx.host(), null, "ns=teacher,common,bad ns");
		assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-cache");
		assertThat(result.getResponse().getHeaders(HttpHeaders.VARY).toString()).contains("Accept-Language")
			.contains("Cookie")
			.contains("Authorization")
			.contains("X-LMS-Locale");
		assertThat(data(result).path("namespaces").toString()).isEqualTo("[\"common\",\"teacher\"]");
		data(result).path("messages").propertyNames().forEach(key -> assertThat(key).matches("(common|teacher)\\..*"));
	}

	@Test
	void precedenceExplicitUserCookieBrowserTenantDefault() {
		Fx fx = seed("i18n-chain");
		setLanguagesOk(fx, List.of("en-LK", "si-LK", "ta-LK"), "ta-LK", true);
		String student = userToken(fx, "student@example.test", Role.STUDENT);
		assertThat(status(json(fx.host(), student, put("/api/v1/me/locale"), Map.of("locale", "si-LK"))))
			.isEqualTo(200);

		Cookie enCookie = new Cookie("lms_locale", "en-LK");
		// 1. explicit param, then header.
		assertThat(resolved(catalog(fx.host(), student, "locale=en", null, enCookie, "si"))).isEqualTo("en-LK/EXPLICIT");
		assertThat(resolved(catalog(fx.host(), student, null, "ta-lk", enCookie, "si"))).isEqualTo("ta-LK/EXPLICIT");
		// An invalid explicit value is ignored -> 2. saved preference.
		assertThat(resolved(catalog(fx.host(), student, "locale=xx-YY", "<script>", enCookie, "en")))
			.isEqualTo("si-LK/USER");
		// 3. cookie (anonymous).
		assertThat(resolved(catalog(fx.host(), null, null, null, enCookie, "si"))).isEqualTo("en-LK/COOKIE");
		// An unusable cookie is ignored; 4. Accept-Language (detection on) among enabled locales.
		assertThat(resolved(catalog(fx.host(), null, null, null, new Cookie("lms_locale", "fr-FR"),
				"fr-FR,si;q=0.8,ta;q=0.5")))
			.isEqualTo("si-LK/BROWSER");
		// 5. tenant default when nothing else applies.
		assertThat(resolved(catalog(fx.host(), null, null, null, null, "fr-FR,de;q=0.9"))).isEqualTo("ta-LK/TENANT_DEFAULT");
		assertThat(resolved(catalog(fx.host(), null, null, null, null, ";;;garbage"))).isEqualTo("ta-LK/TENANT_DEFAULT");
	}

	@Test
	void acceptLanguageOnlyWithDetectionAndOnlyEnabledLocales() {
		Fx fx = seed("i18n-browser");
		setLanguagesOk(fx, List.of("en-LK", "si-LK"), "en-LK", null);
		// Detection off (default): Accept-Language is ignored.
		assertThat(resolved(catalog(fx.host(), null, null, null, null, "si-LK"))).isEqualTo("en-LK/TENANT_DEFAULT");
		setLanguagesOk(fx, null, null, true);
		assertThat(resolved(catalog(fx.host(), null, null, null, null, "si-LK"))).isEqualTo("si-LK/BROWSER");
		// ta-LK is supported but not enabled: never chosen from the browser.
		assertThat(resolved(catalog(fx.host(), null, null, null, null, "ta-LK"))).isEqualTo("en-LK/TENANT_DEFAULT");
		// A usable cookie wins over the browser.
		assertThat(resolved(catalog(fx.host(), null, null, null, new Cookie("lms_locale", "en-LK"), "si-LK")))
			.isEqualTo("en-LK/COOKIE");
	}

	@Test
	void stalePreferenceForADisabledLocaleIsIgnored() {
		Fx fx = seed("i18n-stale");
		setLanguagesOk(fx, List.of("en-LK", "si-LK"), "en-LK", false);
		String student = userToken(fx, "student@example.test", Role.STUDENT);
		assertThat(status(json(fx.host(), student, put("/api/v1/me/locale"), Map.of("locale", "si-LK"))))
			.isEqualTo(200);
		assertThat(resolved(catalog(fx.host(), student, null, null, null, null))).isEqualTo("si-LK/USER");
		setLanguagesOk(fx, List.of("en-LK"), "en-LK", null);
		assertThat(resolved(catalog(fx.host(), student, null, null, null, null))).isEqualTo("en-LK/TENANT_DEFAULT");
		// The stored value is kept (the user did not change it) but never served while disabled.
		MvcResult mine = call(fx.host(), student, get("/api/v1/me/locale"));
		assertThat(data(mine).path("locale").asString()).isEqualTo("si-LK");
	}

	@Test
	void myLocaleIsPersistedPerTenantAndValidated() {
		Fx a = seed("i18n-me-a");
		Fx b = seed("i18n-me-b");
		setLanguagesOk(a, List.of("en-LK", "si-LK"), "en-LK", false);
		setLanguagesOk(b, List.of("en-LK", "ta-LK"), "en-LK", false);
		String studentA = userToken(a, "same@example.test", Role.STUDENT);
		String studentB = userToken(b, "same@example.test", Role.STUDENT);

		MvcResult empty = call(a.host(), studentA, get("/api/v1/me/locale"));
		assertThat(status(empty)).isEqualTo(200);
		assertThat(data(empty).has("locale")).isTrue();
		assertThat(data(empty).path("locale").isNull()).isTrue();

		MvcResult saved = json(a.host(), studentA, put("/api/v1/me/locale"), Map.of("locale", "si"));
		assertThat(status(saved)).as(rawContent(saved)).isEqualTo(200);
		assertThat(data(saved).path("locale").asString()).isEqualTo("si-LK");

		MvcResult notEnabled = json(b.host(), studentB, put("/api/v1/me/locale"), Map.of("locale", "si-LK"));
		assertThat(status(notEnabled)).isEqualTo(400);
		assertThat(errorCode(notEnabled)).isEqualTo("LOCALE_NOT_ENABLED");
		MvcResult unknown = json(b.host(), studentB, put("/api/v1/me/locale"), Map.of("locale", "klingon"));
		assertThat(status(unknown)).isEqualTo(400);
		assertThat(errorCode(unknown)).isEqualTo("LOCALE_NOT_SUPPORTED");

		// Tenant B's account (same email) has its own, untouched preference.
		assertThat(data(call(b.host(), studentB, get("/api/v1/me/locale"))).path("locale").isNull()).isTrue();
		assertThat(count("SELECT count(*) FROM user_locale_preference WHERE tenant_id = ?", b.tenantId())).isZero();
		assertThat(count("SELECT count(*) FROM user_locale_preference WHERE tenant_id = ? AND locale = 'si-LK'",
				a.tenantId())).isEqualTo(1);

		// A tenant-A token is not accepted on tenant B's host.
		assertThat(status(call(b.host(), studentA, get("/api/v1/me/locale")))).isEqualTo(401);
		assertThat(status(call(a.host(), null, get("/api/v1/me/locale")))).isEqualTo(401);

		// Clearing.
		MvcResult cleared = json(a.host(), studentA, put("/api/v1/me/locale"), java.util.Collections.singletonMap("locale", null));
		assertThat(status(cleared)).isEqualTo(200);
		assertThat(data(cleared).path("locale").isNull()).isTrue();
	}

	@Test
	void defaultLocaleMustBeEnabled() {
		Fx fx = seed("i18n-settings");
		// Every language is enabled by default, so any of them may become the default...
		setLanguagesOk(fx, null, "si-LK", null);
		setLanguagesOk(fx, List.of("en-LK", "si-LK"), "en-LK", null);
		// ...but not one the tenant switched off.
		assertThat(status(setLanguages(fx, null, "ta-LK", null))).isEqualTo(400);
		assertThat(status(setLanguages(fx, List.of("si-LK"), "en-LK", null))).isEqualTo(400);
		assertThat(status(setLanguages(fx, List.of("en-LK", "xx-YY"), null, null))).isEqualTo(400);
		assertThat(status(setLanguages(fx, List.of("en-LK", "en-LK"), null, null))).isEqualTo(400);
		assertThat(status(setLanguages(fx, List.of(), null, null))).isEqualTo(400);
		setLanguagesOk(fx, List.of("si-LK", "en-LK"), "si-LK", null);
		// Removing the default from the enabled list in a later, single-key update is rejected too.
		assertThat(status(setLanguages(fx, List.of("en-LK"), null, null))).isEqualTo(400);
		JsonNode settings = data(call(fx.host(), null, get("/api/v1/public/i18n/settings")));
		assertThat(settings.path("enabledLocales").toString()).isEqualTo("[\"si-LK\",\"en-LK\"]");
		assertThat(settings.path("defaultLocale").asString()).isEqualTo("si-LK");
	}

}
