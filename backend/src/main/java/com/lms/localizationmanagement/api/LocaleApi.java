package com.lms.localizationmanagement.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * I18N-01 locale contract for other modules. Request-path methods read the tenant from the
 * already-resolved {@code TenantContext} and the (optional) authenticated principal - never a
 * caller-supplied tenant id.
 */
public interface LocaleApi {

	/** Every platform-supported locale, registry order. */
	List<SupportedLocale> supportedLocales();

	/**
	 * The canonical supported tag for {@code input} (exact tag, case-insensitive; alias; else
	 * language-subtag match), empty for anything unknown or malformed.
	 */
	Optional<String> canonicalize(String input);

	/** The current tenant's enabled locales (canonical, never empty). */
	List<String> enabledLocales();

	/** Doc section 2 resolution chain for the current request. */
	ResolvedLocale resolve(LocaleInputs inputs);

	/**
	 * Recipient locale for emails / queued notifications: saved preference -> tenant default ->
	 * {@code en-LK}. Takes an explicit {@code tenantId} only because it serves the async/job
	 * path, where the tenant travels in the job payload; it must equal the {@code TenantContext}
	 * the job has set (fails closed otherwise).
	 */
	String recipientLocale(UUID tenantId, UUID userId);

}
