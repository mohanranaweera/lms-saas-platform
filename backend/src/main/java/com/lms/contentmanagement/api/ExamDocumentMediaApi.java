package com.lms.contentmanagement.api;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.web.multipart.MultipartFile;

/**
 * Narrow contract the exam module uses for written-exam documents (Klass Exam Module Slice
 * B; media usages {@code EXAM_QUESTION_PAPER} / {@code EXAM_MARKING_SCHEME}, V111). Every
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

	/** The live document {@code mediaId} of exactly this purpose in the caller's tenant. */
	Optional<StoredDocument> findLive(UUID mediaId, Purpose purpose);

	/** A short-lived signed inline URL for a live document of this purpose; empty if absent. */
	Optional<String> signUrl(UUID mediaId, Purpose purpose, Duration ttl);

	enum Purpose {
		QUESTION_PAPER, MARKING_SCHEME
	}

	record StoredDocument(UUID mediaId, String fileName, UUID uploadedBy) {
	}

}
