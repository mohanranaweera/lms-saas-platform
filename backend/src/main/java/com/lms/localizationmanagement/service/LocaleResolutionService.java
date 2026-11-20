package com.lms.localizationmanagement.service;

import com.lms.common.tenant.TenantContextHolder;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.localizationmanagement.api.LocaleApi;
import com.lms.localizationmanagement.api.LocaleInputs;
import com.lms.localizationmanagement.api.LocaleSource;
import com.lms.localizationmanagement.api.ResolvedLocale;
import com.lms.localizationmanagement.api.SupportedLocale;
import com.lms.localizationmanagement.domain.UserLocalePreference;
import com.lms.localizationmanagement.repository.UserLocalePreferenceRepository;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Doc section 2 resolution chain, identical on server and client:
 * <ol>
 * <li>explicit selection for this request ({@code locale} param / {@code X-LMS-Locale});</li>
 * <li>the signed-in user's saved preference for this tenant;</li>
 * <li>the {@code lms_locale} cookie;</li>
 * <li>only when there is no usable cookie and the tenant enabled browser detection: the best
 * {@code Accept-Language} match among the ENABLED locales;</li>
 * <li>the tenant default;</li>
 * <li>{@code en-LK}.</li>
 * </ol>
 * Any value that is unknown, malformed or not enabled for the tenant is ignored and the chain
 * continues. Resolution never writes anything.
 */
@Service
public class LocaleResolutionService implements LocaleApi {

	private final LocaleRegistry localeRegistry;

	private final TenantLocaleSettings tenantLocaleSettings;

	private final UserLocalePreferenceRepository preferenceRepository;

	public LocaleResolutionService(LocaleRegistry localeRegistry, TenantLocaleSettings tenantLocaleSettings,
			UserLocalePreferenceRepository preferenceRepository) {
		this.localeRegistry = localeRegistry;
		this.tenantLocaleSettings = tenantLocaleSettings;
		this.preferenceRepository = preferenceRepository;
	}

	@Override
	public List<SupportedLocale> supportedLocales() {
		return localeRegistry.locales();
	}

	@Override
	public Optional<String> canonicalize(String input) {
		return localeRegistry.canonicalize(input);
	}

	@Override
	public List<String> enabledLocales() {
		return tenantLocaleSettings.current().enabled();
	}

	@Override
	@Transactional(readOnly = true)
	public ResolvedLocale resolve(LocaleInputs inputs) {
		LocaleInputs in = inputs == null ? LocaleInputs.NONE : inputs;
		TenantLocaleSettings.Settings settings = tenantLocaleSettings.current();
		List<String> enabled = settings.enabled();

		Optional<String> explicit = enabledMatch(in.explicit(), enabled);
		if (explicit.isPresent()) {
			return new ResolvedLocale(explicit.get(), LocaleSource.EXPLICIT);
		}
		Optional<String> saved = savedPreference().filter(enabled::contains);
		if (saved.isPresent()) {
			return new ResolvedLocale(saved.get(), LocaleSource.USER);
		}
		Optional<String> cookie = enabledMatch(in.cookie(), enabled);
		if (cookie.isPresent()) {
			return new ResolvedLocale(cookie.get(), LocaleSource.COOKIE);
		}
		if (settings.browserLanguageDetection()) {
			Optional<String> browser = bestAcceptLanguageMatch(in.acceptLanguage(), enabled);
			if (browser.isPresent()) {
				return new ResolvedLocale(browser.get(), LocaleSource.BROWSER);
			}
		}
		if (tenantLocaleSettings.currentTenant().isPresent() && enabled.contains(settings.defaultLocale())) {
			return new ResolvedLocale(settings.defaultLocale(), LocaleSource.TENANT_DEFAULT);
		}
		return new ResolvedLocale(localeRegistry.sourceLocale(), LocaleSource.PLATFORM);
	}

	/**
	 * Recipient locale for emails / queued notifications (doc section 2): saved preference ->
	 * tenant default -> {@code en-LK}; a preference for a locale the tenant no longer enables is
	 * ignored. {@code tenantId} is explicit only because this serves the async/job path, which
	 * carries the tenant in its payload and has set the {@code TenantContext} from it; a
	 * mismatch (or no context at all) fails closed, so this can never read another tenant.
	 */
	@Override
	@Transactional(readOnly = true)
	public String recipientLocale(UUID tenantId, UUID userId) {
		if (tenantId == null || !TenantContextHolder.isSet()
				|| !tenantId.equals(tenantLocaleSettings.currentTenant().orElse(null))) {
			throw new IllegalStateException("recipientLocale requires the job's tenant to be the current TenantContext");
		}
		TenantLocaleSettings.Settings settings = tenantLocaleSettings.current();
		if (userId != null) {
			Optional<String> saved = preferenceRepository.findById(userId)
				.map(UserLocalePreference::getLocale)
				.filter(settings.enabled()::contains);
			if (saved.isPresent()) {
				return saved.get();
			}
		}
		return settings.defaultLocale();
	}

	private Optional<String> enabledMatch(String raw, List<String> enabled) {
		if (raw == null || raw.isBlank()) {
			return Optional.empty();
		}
		return localeRegistry.canonicalize(raw).filter(enabled::contains);
	}

	/** The signed-in user's saved preference for the current tenant (same-tenant principals only). */
	private Optional<String> savedPreference() {
		if (!AuthenticatedPrincipalHolder.isSet()) {
			return Optional.empty();
		}
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		Optional<UUID> tenant = tenantLocaleSettings.currentTenant();
		if (tenant.isEmpty() || principal.tenantId() == null || !principal.tenantId().equals(tenant.get())) {
			return Optional.empty();
		}
		return preferenceRepository.findById(principal.userId()).map(UserLocalePreference::getLocale);
	}

	/** Highest-weighted {@code Accept-Language} range that maps to an enabled locale. */
	Optional<String> bestAcceptLanguageMatch(String header, List<String> enabled) {
		if (header == null || header.isBlank() || header.length() > 1000) {
			return Optional.empty();
		}
		List<Locale.LanguageRange> ranges;
		try {
			ranges = Locale.LanguageRange.parse(header);
		}
		catch (IllegalArgumentException ex) {
			return Optional.empty();
		}
		for (Locale.LanguageRange range : ranges) {
			if (range.getWeight() <= 0 || range.getRange().startsWith("*")) {
				continue;
			}
			Optional<String> match = localeRegistry.canonicalize(range.getRange()).filter(enabled::contains);
			if (match.isPresent()) {
				return match;
			}
		}
		return Optional.empty();
	}

}
