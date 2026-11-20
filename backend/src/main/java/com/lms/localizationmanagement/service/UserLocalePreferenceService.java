package com.lms.localizationmanagement.service;

import com.lms.common.tenant.TenantContext;
import com.lms.identityaccessservice.api.AuthenticatedPrincipal;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.localizationmanagement.api.LocalizationException;
import com.lms.localizationmanagement.domain.UserLocalePreference;
import com.lms.localizationmanagement.repository.UserLocalePreferenceRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code GET/PUT /api/v1/me/locale}: the signed-in user's own UI language for the current
 * tenant. The user and tenant come only from the authenticated principal / resolved tenant
 * context; the stored value is always a canonical tag the tenant has enabled.
 */
@Service
public class UserLocalePreferenceService {

	private final UserLocalePreferenceRepository repository;

	private final LocaleRegistry localeRegistry;

	private final TenantLocaleSettings tenantLocaleSettings;

	private final TenantContext tenantContext;

	private final Clock clock;

	public UserLocalePreferenceService(UserLocalePreferenceRepository repository, LocaleRegistry localeRegistry,
			TenantLocaleSettings tenantLocaleSettings, TenantContext tenantContext) {
		this.repository = repository;
		this.localeRegistry = localeRegistry;
		this.tenantLocaleSettings = tenantLocaleSettings;
		this.tenantContext = tenantContext;
		this.clock = Clock.systemUTC();
	}

	@Transactional(readOnly = true)
	public Optional<String> current() {
		return repository.findById(requireSameTenantPrincipal().userId()).map(UserLocalePreference::getLocale);
	}

	/**
	 * Saves the preference ({@code null} clears it). {@code 400 LOCALE_NOT_SUPPORTED} for an
	 * unknown tag, {@code 400 LOCALE_NOT_ENABLED} for a supported tag the tenant has not enabled.
	 */
	@Transactional
	public Optional<String> change(String requested) {
		AuthenticatedPrincipal principal = requireSameTenantPrincipal();
		Optional<UserLocalePreference> existing = repository.findById(principal.userId());
		if (requested == null || requested.isBlank()) {
			existing.ifPresent(repository::delete);
			return Optional.empty();
		}
		String locale = localeRegistry.canonicalize(requested)
			.orElseThrow(() -> LocalizationException.localeNotSupported(requested));
		if (!tenantLocaleSettings.current().enabled().contains(locale)) {
			throw LocalizationException.localeNotEnabled(locale);
		}
		Instant now = clock.instant();
		if (existing.isPresent()) {
			existing.get().change(locale, now);
			repository.save(existing.get());
		}
		else {
			repository.save(new UserLocalePreference(tenantContext.getTenantId(), principal.userId(), locale, now));
		}
		return Optional.of(locale);
	}

	private AuthenticatedPrincipal requireSameTenantPrincipal() {
		AuthenticatedPrincipal principal = AuthenticatedPrincipalHolder.get();
		UUID tenantId = tenantContext.getTenantId();
		if (principal.tenantId() == null || !principal.tenantId().equals(tenantId)) {
			throw new AccessDeniedException("You do not have permission to perform this action");
		}
		return principal;
	}

}
