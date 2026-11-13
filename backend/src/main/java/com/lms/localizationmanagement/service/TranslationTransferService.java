package com.lms.localizationmanagement.service;

import com.lms.auditlogmanagement.api.AuditLogApi;
import com.lms.auditlogmanagement.api.AuditLogEntry;
import com.lms.common.api.ApiErrorCodes;
import com.lms.common.error.PayloadTooLargeException;
import com.lms.common.tenant.TenantContext;
import com.lms.identityaccessservice.api.AuthenticatedPrincipalHolder;
import com.lms.identityaccessservice.api.Capability;
import com.lms.identityaccessservice.api.PermissionCheckService;
import com.lms.localizationmanagement.api.LocalizationException;
import com.lms.localizationmanagement.domain.DraftAction;
import com.lms.localizationmanagement.domain.TranslationDraft;
import com.lms.localizationmanagement.po.PoFile;
import com.lms.localizationmanagement.repository.TranslationDraftRepository;
import com.lms.localizationmanagement.service.TranslationCatalogService.EffectiveCatalog;
import com.lms.localizationmanagement.service.TranslationCatalogService.PublishedOverride;
import com.lms.localizationmanagement.service.TranslationViews.ExportFile;
import com.lms.localizationmanagement.service.TranslationViews.ImportProblem;
import com.lms.localizationmanagement.service.TranslationViews.ImportReport;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * PO import (drafts only) and PO/MO export of the current tenant's overrides (doc section 5).
 * Import limits: 1 MiB, strict UTF-8 text (no NUL bytes), the PO {@code Language} header (when
 * present) must be the selected locale, only registered keys, every message valid. A non-dry
 * run with any invalid message is rejected with no writes; unknown keys are ignored and
 * reported.
 */
@Service
public class TranslationTransferService {

	public static final long MAX_IMPORT_BYTES = 1024L * 1024L;

	private static final int MAX_REPORTED_KEYS = 200;

	private final PlatformCatalog platformCatalog;

	private final TranslationAdminService adminService;

	private final TranslationCatalogService catalogService;

	private final TranslationDraftRepository draftRepository;

	private final PermissionCheckService permissionCheckService;

	private final AuditLogApi auditLogApi;

	private final TenantContext tenantContext;

	public TranslationTransferService(PlatformCatalog platformCatalog, TranslationAdminService adminService,
			TranslationCatalogService catalogService, TranslationDraftRepository draftRepository,
			PermissionCheckService permissionCheckService, AuditLogApi auditLogApi, TenantContext tenantContext) {
		this.platformCatalog = platformCatalog;
		this.adminService = adminService;
		this.catalogService = catalogService;
		this.draftRepository = draftRepository;
		this.permissionCheckService = permissionCheckService;
		this.auditLogApi = auditLogApi;
		this.tenantContext = tenantContext;
	}

	// ------------------------------------------------------------------ import

	@Transactional
	public ImportReport importPo(String localeParam, boolean dryRun, MultipartFile file) {
		permissionCheckService.requireCapability(Capability.TRANSLATIONS_EDIT);
		String locale = adminService.requireSupportedLocale(localeParam);
		PoFile po = parse(read(file), locale);

		Map<String, TranslationDraft> drafts = draftRepository.findByLocale(locale)
			.stream()
			.collect(Collectors.toMap(TranslationDraft::getMessageKey, Function.identity()));
		Map<String, PublishedOverride> published = catalogService.publishedOverrides(locale);

		List<ImportProblem> invalid = new ArrayList<>();
		List<String> unknownKeys = new ArrayList<>();
		List<String> sourceChanged = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		Map<String, String> toWrite = new LinkedHashMap<>();
		int created = 0;
		int updated = 0;
		int unchanged = 0;
		int skippedEmpty = 0;
		int skippedFuzzy = 0;
		int skippedObsolete = 0;
		for (PoFile.Entry entry : po.entries()) {
			if (entry.obsolete()) {
				skippedObsolete++;
				continue;
			}
			String key = entry.msgctxt();
			if (entry.msgidPlural() != null) {
				invalid.add(new ImportProblem(key, List.of("MSGID_PLURAL")));
				continue;
			}
			if (key.isEmpty()) {
				invalid.add(new ImportProblem(key, List.of("MISSING_MSGCTXT")));
				continue;
			}
			if (!seen.add(key)) {
				invalid.add(new ImportProblem(key, List.of("DUPLICATE")));
				continue;
			}
			PlatformCatalog.SourceMessage src = platformCatalog.message(key).orElse(null);
			if (src == null) {
				if (unknownKeys.size() < MAX_REPORTED_KEYS) {
					unknownKeys.add(key);
				}
				continue;
			}
			if (entry.msgstr().isEmpty()) {
				skippedEmpty++;
				continue;
			}
			if (entry.fuzzy()) {
				skippedFuzzy++;
				continue;
			}
			List<String> problems = TranslationAdminService.problems(entry.msgstr(), locale, src.source());
			if (!problems.isEmpty()) {
				invalid.add(new ImportProblem(key, problems));
				continue;
			}
			if (!entry.msgid().equals(src.source()) && sourceChanged.size() < MAX_REPORTED_KEYS) {
				sourceChanged.add(key);
			}
			TranslationDraft draft = drafts.get(key);
			String current = draft != null ? (draft.getAction() == DraftAction.SET ? draft.getValue() : null)
					: published.containsKey(key) ? published.get(key).value()
							: platformCatalog.platformValue(locale, key).orElse(null);
			if (entry.msgstr().equals(current)) {
				unchanged++;
				continue;
			}
			if (draft != null) {
				updated++;
			}
			else {
				created++;
			}
			toWrite.put(key, entry.msgstr());
		}
		boolean valid = invalid.isEmpty();
		ImportReport report = new ImportReport(locale, dryRun, valid, po.entries().size(), created, updated, unchanged,
				skippedEmpty, skippedFuzzy, skippedObsolete, List.copyOf(unknownKeys), List.copyOf(sourceChanged),
				List.copyOf(invalid));
		if (dryRun) {
			return report;
		}
		if (!valid) {
			Map<String, Object> details = new LinkedHashMap<>();
			details.put("problems",
					invalid.stream().flatMap(p -> p.problems().stream()).distinct().sorted().toList());
			details.put("report", report);
			throw rejected("Some messages in the file are not valid; nothing was imported", details);
		}
		if (!toWrite.isEmpty()) {
			List<TranslationDraft> writes = new ArrayList<>();
			toWrite.forEach((key, value) -> {
				TranslationDraft draft = drafts.get(key);
				if (draft == null) {
					draft = new TranslationDraft(tenantContext.getTenantId(), locale, key);
				}
				draft.set(value);
				writes.add(draft);
			});
			draftRepository.saveAll(writes);
			draftRepository.flush();
		}
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("locale", locale);
		metadata.put("created", created);
		metadata.put("updated", updated);
		metadata.put("unchanged", unchanged);
		metadata.put("unknownKeys", unknownKeys.size());
		metadata.put("filename", safeFilename(file.getOriginalFilename()));
		auditLogApi.record(new AuditLogEntry(AuthenticatedPrincipalHolder.get().userId(), "translation.imported",
				"tenant", tenantContext.getTenantId(), null, metadata));
		return report;
	}

	private static LocalizationException rejected(String message, Map<String, Object> details) {
		return new LocalizationException(HttpStatus.UNPROCESSABLE_ENTITY, LocalizationException.PO_IMPORT_INVALID,
				message, details);
	}

	/** Size cap first (no more than limit + 1 bytes are ever read), then strict UTF-8 text. */
	private static String read(MultipartFile file) {
		if (file == null || file.isEmpty()) {
			throw rejected("A .po file is required", Map.of("reason", "EMPTY"));
		}
		if (file.getSize() > MAX_IMPORT_BYTES) {
			throw new PayloadTooLargeException("The file exceeds the 1 MiB import limit");
		}
		byte[] bytes;
		try (InputStream in = file.getInputStream()) {
			bytes = in.readNBytes((int) MAX_IMPORT_BYTES + 1);
		}
		catch (IOException ex) {
			throw rejected("The file could not be read", Map.of("reason", "UNREADABLE"));
		}
		if (bytes.length > MAX_IMPORT_BYTES) {
			throw new PayloadTooLargeException("The file exceeds the 1 MiB import limit");
		}
		for (byte b : bytes) {
			if (b == 0) {
				throw rejected("The file is not a text .po file", Map.of("reason", "NOT_TEXT"));
			}
		}
		try {
			return StandardCharsets.UTF_8.newDecoder()
				.onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT)
				.decode(ByteBuffer.wrap(bytes))
				.toString();
		}
		catch (CharacterCodingException ex) {
			throw rejected("The file is not valid UTF-8", Map.of("reason", "ENCODING"));
		}
	}

	private PoFile parse(String text, String locale) {
		PoFile po;
		try {
			po = PoFile.parse(text);
		}
		catch (PoFile.PoSyntaxException ex) {
			throw rejected("The file is not a valid .po file", Map.of("reason", "SYNTAX"));
		}
		Map<String, String> header = headerFields(po.header());
		String language = header.get("language");
		if (language != null && !language.isBlank()) {
			String canonical = adminService.requireSupportedLocaleOrNull(language);
			if (!locale.equals(canonical)) {
				throw rejected("The file's Language header does not match the selected language",
						Map.of("reason", "LANGUAGE_MISMATCH"));
			}
		}
		String contentType = header.get("content-type");
		if (contentType != null) {
			String lower = contentType.toLowerCase(Locale.ROOT);
			int charset = lower.indexOf("charset=");
			if (charset >= 0 && !lower.substring(charset + 8).strip().startsWith("utf-8")) {
				throw rejected("The file must be UTF-8", Map.of("reason", "CHARSET"));
			}
		}
		return po;
	}

	private static Map<String, String> headerFields(String header) {
		Map<String, String> fields = new LinkedHashMap<>();
		for (String line : header.split("\n")) {
			int colon = line.indexOf(':');
			if (colon > 0) {
				fields.put(line.substring(0, colon).strip().toLowerCase(Locale.ROOT), line.substring(colon + 1).strip());
			}
		}
		return fields;
	}

	private static String safeFilename(String name) {
		if (name == null) {
			return "upload.po";
		}
		String clean = name.replaceAll("[^A-Za-z0-9._-]", "_");
		return clean.length() > 100 ? clean.substring(0, 100) : clean;
	}

	// ------------------------------------------------------------------ export

	@Transactional(readOnly = true)
	public ExportFile export(String localeParam, String scopeParam, String formatParam) {
		permissionCheckService.requireCapability(Capability.TRANSLATIONS_VIEW);
		String locale = adminService.requireSupportedLocale(localeParam);
		String scope = scopeParam == null ? "" : scopeParam.strip().toUpperCase(Locale.ROOT);
		String format = formatParam == null || formatParam.isBlank() ? "po" : formatParam.strip().toLowerCase(Locale.ROOT);
		if (!scope.equals("OVERRIDES") && !scope.equals("EFFECTIVE")) {
			throw new LocalizationException(HttpStatus.BAD_REQUEST, ApiErrorCodes.VALIDATION_ERROR,
					"scope must be OVERRIDES or EFFECTIVE");
		}
		if (!format.equals("po") && !format.equals("mo")) {
			throw new LocalizationException(HttpStatus.BAD_REQUEST, ApiErrorCodes.VALIDATION_ERROR,
					"format must be po or mo");
		}
		List<PoFile.Entry> entries = new ArrayList<>();
		if (scope.equals("OVERRIDES")) {
			catalogService.publishedOverrides(locale).forEach((key, override) -> platformCatalog.message(key)
				.ifPresent(src -> entries.add(entry(src, override.value(),
						!src.sourceHash().equals(override.sourceHash())))));
		}
		else {
			EffectiveCatalog effective = catalogService.effective(locale);
			Map<String, PublishedOverride> published = catalogService.publishedOverrides(locale);
			platformCatalog.source().values().forEach(src -> {
				PublishedOverride override = published.get(src.key());
				boolean needsReview = override != null
						&& effective.origins().get(src.key()) == TranslationCatalogService.Origin.OVERRIDE
						&& !Objects.equals(src.sourceHash(), override.sourceHash());
				entries.add(entry(src, effective.messages().get(src.key()), needsReview));
			});
		}
		PoFile po = new PoFile(header(locale, scope), entries);
		String base = locale + "-" + scope.toLowerCase(Locale.ROOT);
		if (format.equals("mo")) {
			return new ExportFile(base + ".mo", "application/octet-stream", po.toMo(locale));
		}
		return new ExportFile(base + ".po", "text/x-gettext-translation; charset=UTF-8",
				po.serialize().getBytes(StandardCharsets.UTF_8));
	}

	private static PoFile.Entry entry(PlatformCatalog.SourceMessage src, String value, boolean needsReview) {
		List<String> comments = needsReview ? List.of("needs review: the English source changed after publishing")
				: List.of();
		return new PoFile.Entry(src.key(), src.source(), value == null ? "" : value, null,
				src.context().isBlank() ? List.of() : List.of(src.context()),
				List.of("i18n/source/" + src.namespace() + ".json"), List.of("icu-format"), comments, null, false);
	}

	private static String header(String locale, String scope) {
		return String.join("\n", "Project-Id-Version: lms-saas-platform", "Language: " + locale, "MIME-Version: 1.0",
				"Content-Type: text/plain; charset=UTF-8", "Content-Transfer-Encoding: 8bit",
				"X-Message-Format: icu-subset (docs/architecture/i18n-translation-management.md)",
				"X-Export-Scope: " + scope, "X-Generator: lms-backend translation export", "");
	}

}
