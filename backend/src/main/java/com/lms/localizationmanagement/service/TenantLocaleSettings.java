package com.lms.localizationmanagement.service;

import com.lms.common.tenant.TenantContext;
import com.lms.common.tenant.TenantContextNotResolvedException;
import com.lms.localizationmanagement.api.SupportedLocale;
import com.lms.tenantmanagement.api.ConfigDomain;
import com.lms.tenantmanagement.api.TenantConfigApi;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The current tenant's language settings ({@code GENERAL.enabled_locales},
 * {@code default_locale}, {@code browser_language_detection}), re-validated against the
 * supported locales: unknown tags are dropped, an empty list falls back to the platform locale,
 * and a default that is not enabled falls back to the first enabled locale. Tenant identity is
 * only ever the already-resolved {@link TenantContext}.
 */
@Component
public class TenantLocaleSettings {

	/** Effective settings; {@code enabled} is never empty and always contains {@code defaultLocale}. */
	public record Settings(List<String> enabled, String defaultLocale, boolean browserLanguageDetection) {
	}

	private final TenantConfigApi tenantConfigApi;

	private final TenantContext tenantContext;

	private final LocaleRegistry localeRegistry;

	public TenantLocaleSettings(TenantConfigApi tenantConfigApi, TenantContext tenantContext,
			LocaleRegistry localeRegistry) {
		this.tenantConfigApi = tenantConfigApi;
		this.tenantContext = tenantContext;
		this.localeRegistry = localeRegistry;
	}

	/** The current request/job tenant, empty when none is resolved (e.g. the platform host). */
	public Optional<UUID> currentTenant() {
		try {
			return Optional.of(tenantContext.getTenantId());
		}
		catch (TenantContextNotResolvedException ex) {
			return Optional.empty();
		}
	}

	public Settings current() {
		Optional<UUID> tenantId = currentTenant();
		if (tenantId.isEmpty()) {
			return platformDefaults();
		}
		Map<String, Object> general = tenantConfigApi.resolveDomain(tenantId.get(), ConfigDomain.GENERAL);
		Object rawEnabled = general.get("enabled_locales");
		List<String> enabled = localeRegistry
			.canonicalizeAll(rawEnabled instanceof List<?> list ? list : List.of())
			.stream()
			.filter(localeRegistry::isSupported)
			.toList();
		if (enabled.isEmpty()) {
			enabled = List.of(localeRegistry.sourceLocale());
		}
		Object rawDefault = general.get("default_locale");
		String defaultLocale = rawDefault instanceof String s ? localeRegistry.canonicalize(s).orElse(null) : null;
		if (defaultLocale == null || !enabled.contains(defaultLocale)) {
			defaultLocale = enabled.get(0);
		}
		boolean detection = Boolean.TRUE.equals(general.get("browser_language_detection"));
		return new Settings(enabled, defaultLocale, detection);
	}

	private Settings platformDefaults() {
		return new Settings(localeRegistry.locales().stream().map(SupportedLocale::tag).toList(),
				localeRegistry.sourceLocale(), false);
	}

}
