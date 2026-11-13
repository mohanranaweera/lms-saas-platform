package com.lms.localizationmanagement.service;

import com.lms.localizationmanagement.api.LocaleInputs;
import com.lms.localizationmanagement.api.ResolvedLocale;
import com.lms.localizationmanagement.service.TranslationCatalogService.EffectiveCatalog;
import com.lms.localizationmanagement.service.TranslationViews.CatalogView;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Anonymous-safe reads for the current host's tenant (doc section 6): language settings and the
 * effective (published) catalog. Never exposes drafts; uses the principal only when present.
 */
@Service
public class PublicI18nService {

	/** {@code GET /api/v1/public/i18n/settings}. */
	public record SettingsView(List<SupportedLocaleView> supportedLocales, List<String> enabledLocales,
			String defaultLocale, boolean browserLanguageDetection) {
	}

	public record SupportedLocaleView(String tag, String autonym, String dir) {
	}

	private final LocaleRegistry localeRegistry;

	private final TenantLocaleSettings tenantLocaleSettings;

	private final LocaleResolutionService localeResolutionService;

	private final TranslationCatalogService catalogService;

	public PublicI18nService(LocaleRegistry localeRegistry, TenantLocaleSettings tenantLocaleSettings,
			LocaleResolutionService localeResolutionService, TranslationCatalogService catalogService) {
		this.localeRegistry = localeRegistry;
		this.tenantLocaleSettings = tenantLocaleSettings;
		this.localeResolutionService = localeResolutionService;
		this.catalogService = catalogService;
	}

	public SettingsView settings() {
		TenantLocaleSettings.Settings settings = tenantLocaleSettings.current();
		return new SettingsView(
				localeRegistry.locales().stream().map(l -> new SupportedLocaleView(l.tag(), l.autonym(), l.dir())).toList(),
				settings.enabled(), settings.defaultLocale(), settings.browserLanguageDetection());
	}

	@Transactional(readOnly = true)
	public CatalogView catalog(LocaleInputs inputs, List<String> requestedNamespaces) {
		ResolvedLocale resolved = localeResolutionService.resolve(inputs);
		EffectiveCatalog catalog = catalogService.effective(resolved.locale());
		List<String> namespaces = TranslationCatalogService.namespaces(requestedNamespaces);
		return new CatalogView(resolved.locale(), resolved.resolvedFrom().name(),
				catalogService.version(catalog.releaseNo()), namespaces,
				catalogService.restrict(catalog.messages(), namespaces));
	}

}
