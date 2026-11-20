package com.lms.localizationmanagement.service;

import com.lms.common.api.PageResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Response shapes of the translation management API (doc section 6). DTOs only, never entities. */
public final class TranslationViews {

	private TranslationViews() {
	}

	public record DraftView(String action, String value, long version, Instant updatedAt) {
	}

	public record EntryView(String key, String namespace, String source, String context, String platformValue,
			String platformStatus, String suggestion, String publishedOverride, boolean overrideNeedsReview,
			DraftView draft, String effectiveValue, String origin) {
	}

	public record Counts(long total, long missing, long translated, long needsReview, long overridden, long drafts) {
	}

	public record EntriesPage(String locale, int releaseNo, Counts counts, PageResponse<EntryView> page) {
	}

	public record CatalogView(String locale, String resolvedFrom, String version, List<String> namespaces,
			Map<String, String> messages) {
	}

	public record ReleaseView(UUID id, String locale, int versionNo, String summary, int entryCount,
			UUID rollbackOfReleaseId, UUID publishedBy, Instant publishedAt, boolean current) {
	}

	public record ImportProblem(String key, List<String> problems) {
	}

	/**
	 * PO import report. {@code valid} = no invalid message (a non-dry run only writes when
	 * valid). Unknown keys and fuzzy/empty/obsolete entries are skipped and reported, never
	 * written.
	 */
	public record ImportReport(String locale, boolean dryRun, boolean valid, int entries, int created, int updated,
			int unchanged, int skippedEmpty, int skippedFuzzy, int skippedObsolete, int needsReview,
			List<String> unknownKeys,
			List<String> sourceChanged, List<ImportProblem> invalid) {
	}

	public record ExportFile(String filename, String contentType, byte[] content) {
	}

}
