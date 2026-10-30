package com.lms.contentmanagement.api;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.web.multipart.MultipartFile;

/**
 * Narrow contract the exam module uses for written-exam documents (Klass Exam Module Slice
 * B; media usages {@code EXAM_QUESTION_PAPER} / {@code EXAM_MARKING_SCHEME}, V111; Slice D
 * {@code EXAM_ANSWER_SHEET} / {@code EXAM_MARKED_PAPER}, V114). Every
 * method is tenant-scoped through the resolved {@code TenantContext}; no method accepts a
 * tenant id. Authorization of the caller is the exam module's job ({@code
 * ExamAccessGuard}) - this API performs none, so it must only be called after that check
 * passed. Documents never appear in the Media Library.
 */
public interface ExamDocumentMediaApi {

	/** Hard upper bound for an exam document (20 MB). */
	long MAX_DOCUMENT_BYTES = 20L * 1024 * 1024;

	/**
	 * Validates (bounded size, magic-byte sniffing: PDF, PNG, JPEG only) and stores a
	 * document of the given purpose; audit-logged. A rejected file leaves no stored object
	 * and no row.
	 */
	StoredDocument upload(MultipartFile file, Purpose purpose);

	/**
	 * Atomic multi-file upload: every file is validated (size, sniffed type) before any byte
	 * is stored; then all are stored, and all {@code media_file} rows, their audit entries and
	 * {@code inTransaction} (the caller's own linking writes and state re-checks) run in ONE
	 * database transaction. If anything fails - a file is invalid, storage fails, or {@code
	 * inTransaction} throws - nothing is kept: no row, and every stored object is deleted again.
	 * Must be called with no database transaction open.
	 */
	List<StoredDocument> uploadAll(List<MultipartFile> files, Purpose purpose,
			Consumer<List<StoredDocument>> inTransaction);

	/** The live document {@code mediaId} of exactly this purpose in the caller's tenant. */
	Optional<StoredDocument> findLive(UUID mediaId, Purpose purpose);

	/** The live documents among {@code mediaIds} of exactly this purpose in the caller's tenant, by id. */
	Map<UUID, StoredDocument> findLiveAll(Collection<UUID> mediaIds, Purpose purpose);

	/** A short-lived signed inline URL for a live document of this purpose; empty if absent. */
	Optional<String> signUrl(UUID mediaId, Purpose purpose, Duration ttl);

	enum Purpose {
		QUESTION_PAPER, MARKING_SCHEME, ANSWER_SHEET, MARKED_PAPER
	}

	record StoredDocument(UUID mediaId, String fileName, UUID uploadedBy) {
	}

}
