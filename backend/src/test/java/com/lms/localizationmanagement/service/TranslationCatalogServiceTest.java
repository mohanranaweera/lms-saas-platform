package com.lms.localizationmanagement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lms.localizationmanagement.domain.TranslationRelease;
import com.lms.localizationmanagement.domain.TranslationReleaseEntry;
import com.lms.localizationmanagement.repository.TranslationDraftRepository;
import com.lms.localizationmanagement.repository.TranslationReleaseEntryRepository;
import com.lms.localizationmanagement.repository.TranslationReleaseRepository;
import com.lms.tenantmanagement.config.ConfigPropertyRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * I18N-01 section 4 fallback (override -> reviewed platform -> source; blank/invalid = missing)
 * and section 9 cache keying, against a synthetic platform catalog.
 */
class TranslationCatalogServiceTest {

	private static final String CATALOG = """
			{"version":"abc123","sourceLocale":"en-LK",
			 "locales":[{"tag":"en-LK","language":"en","autonym":"English","dir":"ltr","aliases":["en"]},
			            {"tag":"si-LK","language":"si","autonym":"සිංහල","dir":"ltr","aliases":["si"]},
			            {"tag":"ta-LK","language":"ta","autonym":"தமிழ்","dir":"ltr","aliases":["ta"]}],
			 "source":{
			   "common.a":{"source":"Hello {name}","context":"","namespace":"common"},
			   "common.b":{"source":"Save","context":"","namespace":"common"},
			   "common.c":{"source":"Cancel","context":"","namespace":"common"},
			   "teacher.d":{"source":"Courses","context":"","namespace":"teacher"}},
			 "translations":{
			   "si-LK":{"messages":{"common.a":"ආයුබෝවන් {name}","common.b":"සුරකින්න","common.c":"  ",
			                        "teacher.d":"broken {"},
			            "needsReview":{"common.c":"අවලංගු"}},
			   "ta-LK":{"messages":{},"needsReview":{}}}}
			""";

	private final UUID tenantA = UUID.randomUUID();

	private final UUID tenantB = UUID.randomUUID();

	private PlatformCatalog catalog;

	private TenantLocaleSettings settings;

	private TranslationReleaseRepository releases;

	private TranslationReleaseEntryRepository entries;

	private TranslationCatalogService service;

	@BeforeEach
	void setUp() {
		catalog = new PlatformCatalog(JsonMapper.builder().build().readTree(CATALOG));
		settings = mock(TenantLocaleSettings.class);
		releases = mock(TranslationReleaseRepository.class);
		entries = mock(TranslationReleaseEntryRepository.class);
		service = new TranslationCatalogService(catalog, settings, releases, entries,
				mock(TranslationDraftRepository.class));
	}

	private TranslationRelease release(UUID tenant, int versionNo) {
		TranslationRelease release = new TranslationRelease(tenant, "si-LK", versionNo, null, 1, null,
				UUID.randomUUID(), Instant.now());
		ReflectionTestUtils.setField(release, "id", UUID.randomUUID(), UUID.class);
		return release;
	}

	@Test
	void fallbackChainSkipsBlankAndInvalidValuesAtEveryStep() {
		when(settings.currentTenant()).thenReturn(Optional.of(tenantA));
		TranslationRelease r1 = release(tenantA, 1);
		when(releases.findLatest("si-LK")).thenReturn(Optional.of(r1));
		when(entries.findByReleaseId(r1.getId())).thenReturn(List.of(
				// valid override wins
				new TranslationReleaseEntry(tenantA, r1.getId(), "common.b", "සුරකින්න!", "h"),
				// invalid override (placeholder missing) -> platform
				new TranslationReleaseEntry(tenantA, r1.getId(), "common.a", "ආයුබෝවන්", "h"),
				// override for a key no longer registered is ignored
				new TranslationReleaseEntry(tenantA, r1.getId(), "common.gone", "x", "h")));

		TranslationCatalogService.EffectiveCatalog si = service.effective("si-LK");
		assertThat(si.messages()).containsEntry("common.b", "සුරකින්න!")
			.containsEntry("common.a", "ආයුබෝවන් {name}")
			// blank platform value -> source; needs-review suggestion is never served
			.containsEntry("common.c", "Cancel")
			// invalid platform value -> source
			.containsEntry("teacher.d", "Courses")
			.doesNotContainKey("common.gone");
		assertThat(si.origins()).containsEntry("common.b", TranslationCatalogService.Origin.OVERRIDE)
			.containsEntry("common.a", TranslationCatalogService.Origin.PLATFORM)
			.containsEntry("common.c", TranslationCatalogService.Origin.SOURCE);
		assertThat(si.releaseNo()).isEqualTo(1);
		assertThat(catalog.suggestion("si-LK", "common.c")).contains("අවලංගු");
		assertThat(service.version(si.releaseNo())).isEqualTo("abc123.1");
		assertThat(service.restrict(si.messages(), TranslationCatalogService.namespaces(List.of())))
			.containsOnlyKeys("common.a", "common.b", "common.c");
	}

	@Test
	void cacheIsKeyedByTenantLocaleAndReleaseNumber() {
		TranslationRelease a1 = release(tenantA, 1);
		TranslationRelease a2 = release(tenantA, 2);
		when(entries.findByReleaseId(any())).thenReturn(List.of());
		when(settings.currentTenant()).thenReturn(Optional.of(tenantA));
		when(releases.findLatest("si-LK")).thenReturn(Optional.of(a1));
		service.effective("si-LK");
		service.effective("si-LK");
		verify(entries, times(1)).findByReleaseId(a1.getId());

		// Another tenant at the same release number has its own entry.
		when(settings.currentTenant()).thenReturn(Optional.of(tenantB));
		TranslationRelease b1 = release(tenantB, 1);
		when(releases.findLatest("si-LK")).thenReturn(Optional.of(b1));
		service.effective("si-LK");
		verify(entries, times(1)).findByReleaseId(b1.getId());

		// A new release in A changes the key -> rebuilt.
		when(settings.currentTenant()).thenReturn(Optional.of(tenantA));
		when(releases.findLatest("si-LK")).thenReturn(Optional.of(a2));
		service.effective("si-LK");
		verify(entries, times(1)).findByReleaseId(a2.getId());
	}

	@Test
	void sourceLocaleServesTheEnglishSourceAndNoTenantMeansPlatformOnly() {
		when(settings.currentTenant()).thenReturn(Optional.empty());
		TranslationCatalogService.EffectiveCatalog en = service.effective("en-LK");
		assertThat(en.messages()).containsEntry("common.b", "Save");
		assertThat(en.origins()).containsEntry("common.b", TranslationCatalogService.Origin.SOURCE);
	}

	@Test
	void localeRegistryCanonicalizesUntrustedInput() {
		LocaleRegistry registry = new LocaleRegistry(catalog);
		assertThat(registry.canonicalize("SI-lk")).contains("si-LK");
		assertThat(registry.canonicalize("si")).contains("si-LK");
		assertThat(registry.canonicalize("si_LK")).contains("si-LK");
		assertThat(registry.canonicalize("en-US")).contains("en-LK");
		assertThat(registry.canonicalize("ta-IN")).contains("ta-LK");
		assertThat(registry.canonicalize("fr-FR")).isEmpty();
		assertThat(registry.canonicalize("<script>")).isEmpty();
		assertThat(registry.canonicalize("")).isEmpty();
		assertThat(registry.canonicalize(null)).isEmpty();
		assertThat(registry.canonicalize("e".repeat(100))).isEmpty();
	}

	@Test
	void tenantSettingsRegistryMatchesTheGeneratedCatalogLocales() {
		PlatformCatalog real = new PlatformCatalog(JsonMapper.builder().build());
		assertThat(real.locales().stream().map(l -> l.tag()).toList())
			.containsExactlyElementsOf(ConfigPropertyRegistry.SUPPORTED_LOCALES);
		assertThat(real.sourceLocale()).isEqualTo(ConfigPropertyRegistry.DEFAULT_LOCALE);
		// Every key Java code formats server-side is registered.
		assertThat(real.message("email.passwordReset.subject")).isPresent();
		assertThat(real.message("email.passwordReset.body")).isPresent();
		assertThat(real.message("email.common.expiresInMinutes")).isPresent();
	}

}
