package com.lms.localizationmanagement.service;

import com.lms.localizationmanagement.domain.DraftAction;
import com.lms.localizationmanagement.domain.TranslationDraft;
import com.lms.localizationmanagement.domain.TranslationRelease;
import com.lms.localizationmanagement.domain.TranslationReleaseEntry;
import com.lms.localizationmanagement.format.MessageFormatter;
import com.lms.localizationmanagement.repository.TranslationDraftRepository;
import com.lms.localizationmanagement.repository.TranslationReleaseEntryRepository;
import com.lms.localizationmanagement.repository.TranslationReleaseRepository;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Effective UI catalogs (doc sections 4 and 9). For every registered key the served value is the
 * first usable one of: published tenant override for the locale -> reviewed platform translation
 * -> English source. Blank or invalid values count as missing at every step; drafts are never in
 * this chain (only in {@link #preview}).
 *
 * <p>Production catalogs are cached in memory keyed {@code (tenantId, locale, releaseNo)}; the
 * current release number is read from PostgreSQL on every call, so a new release (or rollback)
 * changes the key and the cache is never authoritative. Preview catalogs are never cached.
 */
@Service
public class TranslationCatalogService {

	private static final int MAX_CACHED_CATALOGS = 512;

	private static final Pattern NAMESPACE = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,63}$");

	/** One effective catalog: every registered key's served value plus where it came from. */
	public record EffectiveCatalog(String locale, int releaseNo, Map<String, String> messages,
			Map<String, Origin> origins) {
	}

	public enum Origin {

		OVERRIDE, PLATFORM, SOURCE

	}

	private record CacheKey(UUID tenantId, String locale, int releaseNo) {
	}

	/** One published override (value + English source hash at publish time). */
	public record PublishedOverride(String value, String sourceHash) {
	}

	private final PlatformCatalog platformCatalog;

	private final TenantLocaleSettings tenantLocaleSettings;

	private final TranslationReleaseRepository releaseRepository;

	private final TranslationReleaseEntryRepository entryRepository;

	private final TranslationDraftRepository draftRepository;

	private final Map<CacheKey, EffectiveCatalog> cache = Collections
		.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<CacheKey, EffectiveCatalog> eldest) {
				return size() > MAX_CACHED_CATALOGS;
			}
		});

	public TranslationCatalogService(PlatformCatalog platformCatalog, TenantLocaleSettings tenantLocaleSettings,
			TranslationReleaseRepository releaseRepository, TranslationReleaseEntryRepository entryRepository,
			TranslationDraftRepository draftRepository) {
		this.platformCatalog = platformCatalog;
		this.tenantLocaleSettings = tenantLocaleSettings;
		this.releaseRepository = releaseRepository;
		this.entryRepository = entryRepository;
		this.draftRepository = draftRepository;
	}

	/** The current tenant's production catalog for {@code locale} (canonical tag). */
	@Transactional(readOnly = true)
	public EffectiveCatalog effective(String locale) {
		Optional<UUID> tenantId = tenantLocaleSettings.currentTenant();
		if (tenantId.isEmpty()) {
			return build(locale, 0, Map.of());
		}
		Optional<TranslationRelease> latest = releaseRepository.findLatest(locale);
		int releaseNo = latest.map(TranslationRelease::getVersionNo).orElse(0);
		CacheKey key = new CacheKey(tenantId.get(), locale, releaseNo);
		EffectiveCatalog cached = cache.get(key);
		if (cached != null) {
			return cached;
		}
		EffectiveCatalog built = build(locale, releaseNo, latest.map(this::overridesOf).orElse(Map.of()));
		synchronized (cache) {
			// Older releases of the same tenant/locale are never served again.
			cache.keySet()
				.removeIf(k -> k.tenantId().equals(key.tenantId()) && k.locale().equals(locale)
						&& k.releaseNo() < releaseNo);
			cache.put(key, built);
		}
		return built;
	}

	/**
	 * The current tenant's catalog with its drafts applied (SET replaces, RESET drops the
	 * override; invalid drafts are ignored). Never cached and never served publicly.
	 */
	@Transactional(readOnly = true)
	public EffectiveCatalog preview(String locale) {
		Optional<TranslationRelease> latest = releaseRepository.findLatest(locale);
		Map<String, PublishedOverride> overrides = new TreeMap<>(latest.map(this::overridesOf).orElse(Map.of()));
		for (TranslationDraft draft : draftRepository.findByLocale(locale)) {
			if (draft.getAction() == DraftAction.RESET) {
				overrides.remove(draft.getMessageKey());
			}
			else {
				overrides.put(draft.getMessageKey(), new PublishedOverride(draft.getValue(), null));
			}
		}
		return build(locale, latest.map(TranslationRelease::getVersionNo).orElse(0), overrides);
	}

	/** The published overrides of the current release of {@code locale} (key -> override). */
	@Transactional(readOnly = true)
	public Map<String, PublishedOverride> publishedOverrides(String locale) {
		return releaseRepository.findLatest(locale).map(this::overridesOf).orElse(Map.of());
	}

	Map<String, PublishedOverride> overridesOf(TranslationRelease release) {
		Map<String, PublishedOverride> overrides = new TreeMap<>();
		for (TranslationReleaseEntry entry : entryRepository.findByReleaseId(release.getId())) {
			overrides.put(entry.getMessageKey(), new PublishedOverride(entry.getValue(), entry.getSourceHash()));
		}
		return overrides;
	}

	private EffectiveCatalog build(String locale, int releaseNo, Map<String, PublishedOverride> overrides) {
		Map<String, String> messages = new TreeMap<>();
		Map<String, Origin> origins = new TreeMap<>();
		platformCatalog.source().forEach((key, src) -> {
			PublishedOverride override = overrides.get(key);
			if (override != null && MessageFormatter.isUsable(override.value(), locale, src.source())) {
				messages.put(key, override.value());
				origins.put(key, Origin.OVERRIDE);
				return;
			}
			Optional<String> platform = platformCatalog.platformValue(locale, key);
			boolean isSourceLocale = platformCatalog.sourceLocale().equals(locale);
			if (platform.isPresent() && !isSourceLocale) {
				messages.put(key, platform.get());
				origins.put(key, Origin.PLATFORM);
				return;
			}
			messages.put(key, src.source());
			origins.put(key, Origin.SOURCE);
		});
		return new EffectiveCatalog(locale, releaseNo, Collections.unmodifiableMap(messages),
				Collections.unmodifiableMap(origins));
	}

	/**
	 * The requested namespaces (well-formed names only) plus {@code common}, sorted. Unknown
	 * namespaces simply contribute no keys.
	 */
	public static List<String> namespaces(Collection<String> requested) {
		Set<String> out = new TreeSet<>();
		out.add("common");
		if (requested != null) {
			for (String ns : requested) {
				if (ns != null && NAMESPACE.matcher(ns.strip()).matches()) {
					out.add(ns.strip());
				}
			}
		}
		return List.copyOf(out);
	}

	/** {@code messages} restricted to keys whose namespace is in {@code namespaces}. */
	public Map<String, String> restrict(Map<String, String> messages, Collection<String> namespaces) {
		Set<String> wanted = Set.copyOf(namespaces);
		Map<String, String> out = new TreeMap<>();
		messages.forEach((key, value) -> {
			String ns = platformCatalog.message(key)
				.map(PlatformCatalog.SourceMessage::namespace)
				.orElse(PlatformCatalog.namespaceOf(key));
			if (wanted.contains(ns)) {
				out.put(key, value);
			}
		});
		return out;
	}

	/** {@code <platformHash>.<tenantReleaseNo>} (doc section 6). */
	public String version(int releaseNo) {
		return platformCatalog.version() + "." + releaseNo;
	}

}
