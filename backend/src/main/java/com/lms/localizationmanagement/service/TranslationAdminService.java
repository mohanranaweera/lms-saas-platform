package com.lms.localizationmanagement.service;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.api.ApiErrorCodes;
import com.lms.common.api.PageResponse;
import com.lms.common.error.NotFoundException;
import com.lms.common.tenant.TenantContext;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.Capability;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.localizationmanagement.api.LocaleSource;
import com.lms.localizationmanagement.api.LocalizationException;
import com.lms.localizationmanagement.domain.DraftAction;
import com.lms.localizationmanagement.domain.TranslationDraft;
import com.lms.localizationmanagement.domain.TranslationRelease;
import com.lms.localizationmanagement.domain.TranslationReleaseEntry;
import com.lms.localizationmanagement.format.MessageFormatter;
import com.lms.localizationmanagement.repository.TranslationDraftRepository;
import com.lms.localizationmanagement.repository.TranslationReleaseEntryRepository;
import com.lms.localizationmanagement.repository.TranslationReleaseRepository;
import com.lms.localizationmanagement.service.TranslationCatalogService.EffectiveCatalog;
import com.lms.localizationmanagement.service.TranslationCatalogService.PublishedOverride;
import com.lms.localizationmanagement.service.TranslationViews.CatalogView;
import com.lms.localizationmanagement.service.TranslationViews.Counts;
import com.lms.localizationmanagement.service.TranslationViews.DraftView;
import com.lms.localizationmanagement.service.TranslationViews.EntriesPage;
import com.lms.localizationmanagement.service.TranslationViews.EntryView;
import com.lms.localizationmanagement.service.TranslationViews.ImportProblem;
import com.lms.localizationmanagement.service.TranslationViews.ReleaseView;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Settings -> Translations (doc section 5): the current tenant's override drafts, releases,
 * publish and rollback. Every method re-checks its capability ({@code @RequiresCapability} on
 * the controller is only the first gate); the tenant is only ever the resolved
 * {@link TenantContext} and every repository read is structurally tenant-scoped, so another
 * tenant's draft or release id is simply not found.
 *
 * <p>Any supported locale may be edited and published, including one the tenant has not
 * enabled yet - admins prepare a language before switching it on in Settings -> General.
 */
@Service
public class TranslationAdminService {

	static final int MAX_PAGE_SIZE = 200;

	private static final int MAX_SUMMARY_LENGTH = 500;

	private static final Set<String> STATUSES = Set.of("MISSING", "TRANSLATED", "NEEDS_REVIEW", "OVERRIDDEN",
			"DRAFT");

	private final PlatformCatalog platformCatalog;

	private final LocaleRegistry localeRegistry;

	private final TranslationCatalogService catalogService;

	private final TranslationDraftRepository draftRepository;

	private final TranslationReleaseRepository releaseRepository;

	private final TranslationReleaseEntryRepository entryRepository;

	private final PermissionCheckService permissionCheckService;

	private final AuditLogApi auditLogApi;

	private final TenantContext tenantContext;

	private final Clock clock = Clock.systemUTC();

	public TranslationAdminService(PlatformCatalog platformCatalog, LocaleRegistry localeRegistry,
			TranslationCatalogService catalogService, TranslationDraftRepository draftRepository,
			TranslationReleaseRepository releaseRepository, TranslationReleaseEntryRepository entryRepository,
			PermissionCheckService permissionCheckService, AuditLogApi auditLogApi, TenantContext tenantContext) {
		this.platformCatalog = platformCatalog;
		this.localeRegistry = localeRegistry;
		this.catalogService = catalogService;
		this.draftRepository = draftRepository;
		this.releaseRepository = releaseRepository;
		this.entryRepository = entryRepository;
		this.permissionCheckService = permissionCheckService;
		this.auditLogApi = auditLogApi;
		this.tenantContext = tenantContext;
	}

	// ------------------------------------------------------------------ shared checks

	/** Canonical supported tag or {@code 400 LOCALE_NOT_SUPPORTED}. Enabled-ness is not required. */
	String requireSupportedLocale(String locale) {
		return localeRegistry.canonicalize(locale)
			.filter(localeRegistry::isSupported)
			.orElseThrow(() -> LocalizationException.localeNotSupported(locale));
	}

	/** Canonical supported tag, or {@code null} when unknown/malformed. */
	String requireSupportedLocaleOrNull(String locale) {
		return localeRegistry.canonicalize(locale).filter(localeRegistry::isSupported).orElse(null);
	}

	PlatformCatalog.SourceMessage requireKey(String key) {
		return platformCatalog.message(key)
			.orElseThrow(() -> new LocalizationException(HttpStatus.NOT_FOUND, LocalizationException.UNKNOWN_MESSAGE_KEY,
					"Unknown message key"));
	}

	/** Problem codes for a draft value: {@code EMPTY} for blank, else the formatter's codes. */
	static List<String> problems(String value, String locale, String source) {
		if (value == null || value.isBlank()) {
			return List.of("EMPTY");
		}
		return MessageFormatter.validate(value, locale, source);
	}

	private static LocalizationException invalid(String key, List<String> problems) {
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("key", key);
		details.put("problems", problems);
		return new LocalizationException(HttpStatus.UNPROCESSABLE_ENTITY, LocalizationException.TRANSLATION_INVALID,
				"The translation is not valid", details);
	}

	private static LocalizationException draftStale() {
		return new LocalizationException(HttpStatus.CONFLICT, LocalizationException.TRANSLATION_DRAFT_STALE,
				"This draft was changed by someone else. Reload and try again.");
	}

	private static LocalizationException releaseStale() {
		return new LocalizationException(HttpStatus.CONFLICT, LocalizationException.TRANSLATION_RELEASE_STALE,
				"A newer release was published. Reload and try again.");
	}

	private UUID actorId() {
		return AuthenticatedPrincipalHolder.get().userId();
	}

	// ------------------------------------------------------------------ entries

	@Transactional(readOnly = true)
	public EntriesPage entries(String localeParam, String namespace, String q, String status, int page, int size) {
		permissionCheckService.requireCapability(Capability.TRANSLATIONS_VIEW);
		String locale = requireSupportedLocale(localeParam);
		String statusFilter = status == null || status.isBlank() ? null : status.strip().toUpperCase(Locale.ROOT);
		if (statusFilter != null && !STATUSES.contains(statusFilter)) {
			throw new LocalizationException(HttpStatus.BAD_REQUEST, ApiErrorCodes.VALIDATION_ERROR,
					"status must be one of " + STATUSES);
		}
		int safePage = Math.max(page, 0);
		int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
		String needle = q == null || q.isBlank() ? null : q.strip().toLowerCase(Locale.ROOT);

		EffectiveCatalog effective = catalogService.effective(locale);
		Map<String, PublishedOverride> published = catalogService.publishedOverrides(locale);
		Map<String, TranslationDraft> drafts = draftRepository.findByLocale(locale)
			.stream()
			.collect(Collectors.toMap(TranslationDraft::getMessageKey, Function.identity()));
		boolean sourceLocale = platformCatalog.sourceLocale().equals(locale);

		List<EntryView> filtered = new ArrayList<>();
		long missing = 0;
		long translated = 0;
		long needsReview = 0;
		long overridden = 0;
		long draftCount = 0;
		for (PlatformCatalog.SourceMessage src : platformCatalog.source().values()) {
			if (namespace != null && !namespace.isBlank() && !src.namespace().equals(namespace.strip())) {
				continue;
			}
			EntryView view = entryView(src, locale, sourceLocale, effective, published.get(src.key()),
					drafts.get(src.key()));
			if (needle != null && !matches(view, needle)) {
				continue;
			}
			boolean isMissing = "SOURCE".equals(view.origin()) && !sourceLocale;
			boolean isNeedsReview = "NEEDS_REVIEW".equals(view.platformStatus()) || view.overrideNeedsReview();
			missing += isMissing ? 1 : 0;
			translated += isMissing ? 0 : 1;
			needsReview += isNeedsReview ? 1 : 0;
			overridden += view.publishedOverride() != null ? 1 : 0;
			draftCount += view.draft() != null ? 1 : 0;
			boolean keep = statusFilter == null || switch (statusFilter) {
				case "MISSING" -> isMissing;
				case "TRANSLATED" -> !isMissing;
				case "NEEDS_REVIEW" -> isNeedsReview;
				case "OVERRIDDEN" -> view.publishedOverride() != null;
				default -> view.draft() != null;
			};
			if (keep) {
				filtered.add(view);
			}
		}
		long total = missing + translated;
		int from = Math.min(safePage * safeSize, filtered.size());
		int to = Math.min(from + safeSize, filtered.size());
		int totalPages = (int) Math.ceil(filtered.size() / (double) safeSize);
		PageResponse<EntryView> pageResponse = new PageResponse<>(List.copyOf(filtered.subList(from, to)), safePage,
				safeSize, filtered.size(), totalPages);
		return new EntriesPage(locale, effective.releaseNo(),
				new Counts(total, missing, translated, needsReview, overridden, draftCount), pageResponse);
	}

	private EntryView entryView(PlatformCatalog.SourceMessage src, String locale, boolean sourceLocale,
			EffectiveCatalog effective, PublishedOverride override, TranslationDraft draft) {
		String platformValue = platformCatalog.platformValue(locale, src.key()).orElse(null);
		String suggestion = sourceLocale ? null : platformCatalog.suggestion(locale, src.key()).orElse(null);
		String platformStatus = platformValue != null ? "TRANSLATED" : suggestion != null ? "NEEDS_REVIEW" : "MISSING";
		boolean overrideNeedsReview = override != null && !src.sourceHash().equals(override.sourceHash());
		return new EntryView(src.key(), src.namespace(), src.source(), src.context(), platformValue, platformStatus,
				suggestion, override == null ? null : override.value(), overrideNeedsReview, draftView(draft),
				effective.messages().get(src.key()), effective.origins().get(src.key()).name());
	}

	private static boolean matches(EntryView view, String needle) {
		return contains(view.key(), needle) || contains(view.source(), needle) || contains(view.effectiveValue(), needle)
				|| contains(view.platformValue(), needle)
				|| (view.draft() != null && contains(view.draft().value(), needle));
	}

	private static boolean contains(String haystack, String needle) {
		return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needle);
	}

	static DraftView draftView(TranslationDraft draft) {
		return draft == null ? null
				: new DraftView(draft.getAction().name(), draft.getValue(), draft.getVersion(), draft.getUpdatedAt());
	}

	// ------------------------------------------------------------------ drafts

	/** Creates/updates the SET draft. {@code expectedVersion} must be the draft's version, or null for none. */
	@Transactional
	public DraftView saveDraft(String localeParam, String key, String value, Long expectedVersion) {
		permissionCheckService.requireCapability(Capability.TRANSLATIONS_EDIT);
		String locale = requireSupportedLocale(localeParam);
		PlatformCatalog.SourceMessage src = requireKey(key);
		List<String> problems = problems(value, locale, src.source());
		if (!problems.isEmpty()) {
			throw invalid(key, problems);
		}
		TranslationDraft draft = loadForWrite(locale, key, expectedVersion);
		draft.set(value);
		return draftView(flush(draft));
	}

	/** Creates/updates a RESET draft ("reset to platform translation"). Audited. */
	@Transactional
	public DraftView resetDraft(String localeParam, String key, Long expectedVersion) {
		permissionCheckService.requireCapability(Capability.TRANSLATIONS_EDIT);
		String locale = requireSupportedLocale(localeParam);
		requireKey(key);
		TranslationDraft draft = loadForWrite(locale, key, expectedVersion);
		draft.reset();
		TranslationDraft saved = flush(draft);
		auditLogApi.record(new AuditLogEntry(actorId(), "translation.override_reset", "translation_draft",
				saved.getId(), null, Map.of("locale", locale, "key", key)));
		return draftView(saved);
	}

	/** Discards the draft (the published state is untouched). */
	@Transactional
	public void discardDraft(String localeParam, String key, Long expectedVersion) {
		permissionCheckService.requireCapability(Capability.TRANSLATIONS_EDIT);
		String locale = requireSupportedLocale(localeParam);
		requireKey(key);
		TranslationDraft draft = draftRepository.findByLocaleAndKey(locale, key).orElseThrow(TranslationAdminService::draftStale);
		if (expectedVersion == null || draft.getVersion() != expectedVersion) {
			throw draftStale();
		}
		try {
			draftRepository.delete(draft);
			draftRepository.flush();
		}
		catch (ObjectOptimisticLockingFailureException ex) {
			throw draftStale();
		}
	}

	private TranslationDraft loadForWrite(String locale, String key, Long expectedVersion) {
		Optional<TranslationDraft> existing = draftRepository.findByLocaleAndKey(locale, key);
		if (existing.isPresent()) {
			if (expectedVersion == null || existing.get().getVersion() != expectedVersion) {
				throw draftStale();
			}
			return existing.get();
		}
		if (expectedVersion != null) {
			throw draftStale();
		}
		return new TranslationDraft(tenantContext.getTenantId(), locale, key);
	}

	private TranslationDraft flush(TranslationDraft draft) {
		try {
			return draftRepository.saveAndFlush(draft);
		}
		catch (ObjectOptimisticLockingFailureException | DataIntegrityViolationException ex) {
			throw draftStale();
		}
	}

	// ------------------------------------------------------------------ preview

	@Transactional(readOnly = true)
	public CatalogView preview(String localeParam, List<String> requestedNamespaces) {
		permissionCheckService.requireCapability(Capability.TRANSLATIONS_VIEW);
		String locale = requireSupportedLocale(localeParam);
		EffectiveCatalog catalog = catalogService.preview(locale);
		List<String> namespaces = TranslationCatalogService.namespaces(requestedNamespaces);
		return new CatalogView(locale, LocaleSource.EXPLICIT.name(),
				catalogService.version(catalog.releaseNo()) + ".preview", namespaces,
				catalogService.restrict(catalog.messages(), namespaces));
	}

	// ------------------------------------------------------------------ releases

	@Transactional(readOnly = true)
	public List<ReleaseView> releases(String localeParam) {
		permissionCheckService.requireCapability(Capability.TRANSLATIONS_VIEW);
		String locale = requireSupportedLocale(localeParam);
		List<TranslationRelease> releases = releaseRepository.findByLocale(locale);
		int current = releases.isEmpty() ? 0 : releases.get(0).getVersionNo();
		return releases.stream().map(release -> releaseView(release, current)).toList();
	}

	private static ReleaseView releaseView(TranslationRelease release, int currentVersionNo) {
		return new ReleaseView(release.getId(), release.getLocale(), release.getVersionNo(), release.getSummary(),
				release.getEntryCount(), release.getRollbackOfReleaseId(), release.getPublishedBy(),
				release.getPublishedAt(), release.getVersionNo() == currentVersionNo);
	}

	/**
	 * Publishes every draft of {@code locale} as release {@code expectedVersion + 1} (previous
	 * snapshot + SETs - RESETs). {@code expectedVersion} is the current release number (0 when
	 * none); the {@code UNIQUE (tenant_id, locale, version_no)} constraint is the concurrency
	 * guard. Consumed drafts are deleted; the release itself is immutable. Audited.
	 */
	@Transactional
	public ReleaseView publish(String localeParam, Integer expectedVersion, String summary) {
		permissionCheckService.requireCapability(Capability.TRANSLATIONS_PUBLISH);
		String locale = requireSupportedLocale(localeParam);
		String cleanSummary = summary(summary);
		Optional<TranslationRelease> latest = releaseRepository.findLatest(locale);
		int current = latest.map(TranslationRelease::getVersionNo).orElse(0);
		if (expectedVersion == null || expectedVersion != current) {
			throw releaseStale();
		}
		List<TranslationDraft> drafts = draftRepository.findByLocale(locale);
		if (drafts.isEmpty()) {
			throw new LocalizationException(HttpStatus.CONFLICT, LocalizationException.NOTHING_TO_PUBLISH,
					"There are no draft changes to publish");
		}
		List<ImportProblem> invalid = new ArrayList<>();
		for (TranslationDraft draft : drafts) {
			if (draft.getAction() != DraftAction.SET) {
				continue;
			}
			Optional<PlatformCatalog.SourceMessage> src = platformCatalog.message(draft.getMessageKey());
			List<String> problems = src.isEmpty() ? List.of("UNKNOWN_KEY")
					: problems(draft.getValue(), locale, src.get().source());
			if (!problems.isEmpty()) {
				invalid.add(new ImportProblem(draft.getMessageKey(), problems));
			}
		}
		if (!invalid.isEmpty()) {
			Map<String, Object> details = new LinkedHashMap<>();
			details.put("problems", invalid.stream().flatMap(p -> p.problems().stream()).distinct().sorted().toList());
			details.put("invalid", invalid);
			throw new LocalizationException(HttpStatus.UNPROCESSABLE_ENTITY, LocalizationException.TRANSLATION_INVALID,
					"Some drafts are not valid", details);
		}

		Map<String, PublishedOverride> snapshot = new TreeMap<>(latest.map(catalogService::overridesOf).orElse(Map.of()));
		int sets = 0;
		int resets = 0;
		for (TranslationDraft draft : drafts) {
			if (draft.getAction() == DraftAction.RESET) {
				snapshot.remove(draft.getMessageKey());
				resets++;
			}
			else {
				String hash = platformCatalog.message(draft.getMessageKey()).orElseThrow().sourceHash();
				snapshot.put(draft.getMessageKey(), new PublishedOverride(draft.getValue(), hash));
				sets++;
			}
		}
		TranslationRelease release = insertRelease(locale, current + 1, cleanSummary, snapshot, null);
		try {
			for (TranslationDraft draft : drafts) {
				draftRepository.delete(draft);
			}
			draftRepository.flush();
		}
		catch (ObjectOptimisticLockingFailureException ex) {
			throw draftStale();
		}
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("locale", locale);
		metadata.put("versionNo", release.getVersionNo());
		metadata.put("entryCount", release.getEntryCount());
		metadata.put("set", sets);
		metadata.put("reset", resets);
		metadata.put("keys", drafts.stream().map(TranslationDraft::getMessageKey).sorted().toList());
		auditLogApi.record(new AuditLogEntry(actorId(), "translation.published", "translation_release",
				release.getId(), null, metadata));
		return releaseView(release, release.getVersionNo());
	}

	/**
	 * Rolls back by publishing a NEW release (version {@code expectedVersion + 1}) whose snapshot
	 * copies release {@code releaseId}. Nothing is deleted; drafts are untouched. Another
	 * tenant's release id is not found (404). Audited.
	 */
	@Transactional
	public ReleaseView rollback(UUID releaseId, Integer expectedVersion, String summary) {
		permissionCheckService.requireCapability(Capability.TRANSLATIONS_PUBLISH);
		TranslationRelease target = releaseRepository.findById(releaseId)
			.orElseThrow(() -> new NotFoundException("Release not found"));
		String cleanSummary = summary(summary);
		int current = releaseRepository.findLatest(target.getLocale()).map(TranslationRelease::getVersionNo).orElse(0);
		if (expectedVersion == null || expectedVersion != current) {
			throw releaseStale();
		}
		Map<String, PublishedOverride> snapshot = catalogService.overridesOf(target);
		TranslationRelease release = insertRelease(target.getLocale(), current + 1,
				cleanSummary != null ? cleanSummary : "Rollback to version " + target.getVersionNo(), snapshot,
				target.getId());
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("locale", target.getLocale());
		metadata.put("versionNo", release.getVersionNo());
		metadata.put("rollbackOfReleaseId", target.getId().toString());
		metadata.put("rollbackOfVersionNo", target.getVersionNo());
		metadata.put("entryCount", release.getEntryCount());
		auditLogApi.record(new AuditLogEntry(actorId(), "translation.rolled_back", "translation_release",
				release.getId(), null, metadata));
		return releaseView(release, release.getVersionNo());
	}

	private TranslationRelease insertRelease(String locale, int versionNo, String summary,
			Map<String, PublishedOverride> snapshot, UUID rollbackOf) {
		TranslationRelease release;
		try {
			release = releaseRepository.saveAndFlush(new TranslationRelease(tenantContext.getTenantId(), locale,
					versionNo, summary, snapshot.size(), rollbackOf, actorId(), clock.instant()));
		}
		catch (DataIntegrityViolationException ex) {
			throw releaseStale();
		}
		List<TranslationReleaseEntry> entries = new ArrayList<>();
		snapshot.forEach((key, override) -> entries.add(new TranslationReleaseEntry(tenantContext.getTenantId(),
				release.getId(), key, override.value(), override.sourceHash())));
		entryRepository.saveAll(entries);
		entryRepository.flush();
		return release;
	}

	private static String summary(String summary) {
		if (summary == null || summary.isBlank()) {
			return null;
		}
		String clean = summary.strip();
		if (clean.length() > MAX_SUMMARY_LENGTH) {
			throw new LocalizationException(HttpStatus.BAD_REQUEST, ApiErrorCodes.VALIDATION_ERROR,
					"summary must be at most " + MAX_SUMMARY_LENGTH + " characters");
		}
		return clean;
	}

}
